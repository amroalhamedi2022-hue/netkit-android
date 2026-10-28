package com.netkit.local.qa

import com.netkit.local.share.*
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.net.URI
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** TEST ONLY. The trust-all verifier MUST NEVER be imported into the Android app. */
private fun testLanPeerAddressPolicy() {
    fun allowed(ip: String) = isAllowedLanPeer(InetAddress.getByName(ip))
    check(allowed("192.168.1.8")) { "RFC1918 IPv4 peer should be allowed" }
    check(allowed("169.254.1.8")) { "IPv4 link-local peer should be allowed" }
    check(allowed("127.0.0.1")) { "Loopback remains available for local tests" }
    check(allowed("fd12:3456:789a::1")) { "IPv6 ULA peer should be allowed" }
    check(allowed("fc00::1")) { "The lower edge of IPv6 ULA should be allowed" }
    check(allowed("fe80::1")) { "IPv6 link-local peer should be allowed" }
    check(!allowed("8.8.8.8")) { "Public IPv4 peer must be rejected" }
    check(!allowed("2001:4860:4860::8888")) { "Global IPv6 peer must be rejected" }
}

private fun testStreamReadGuard() {
    val payload = byteArrayOf(3, 1, 4, 1, 5)
    var transientZeros = 2
    var offset = 0
    val recovering = object : InputStream() {
        override fun read(): Int = if (offset >= payload.size) -1 else payload[offset++].toInt() and 0xff
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (transientZeros > 0) { transientZeros--; return 0 }
            if (offset >= payload.size) return -1
            val count = minOf(length, payload.size - offset)
            System.arraycopy(payload, offset, buffer, start, count)
            offset += count
            return count
        }
    }
    val buffer = ByteArray(16)
    val count = StreamReadGuard.read(recovering, buffer, 0, buffer.size)
    check(count == payload.size && buffer.copyOf(count).contentEquals(payload))
    check(StreamReadGuard.read(recovering, buffer, 0, buffer.size) == -1)

    var attempts = 0
    val stalled = object : InputStream() {
        override fun read(): Int = -1
        override fun read(buffer: ByteArray, start: Int, length: Int): Int { attempts++; return 0 }
    }
    val failure = runCatching { StreamReadGuard.read(stalled, buffer, 0, buffer.size) }.exceptionOrNull()
    check(failure is IOException) { "A permanently stalled stream must fail with IOException" }
    check(attempts == 33) { "Expected 33 bounded attempts, got $attempts" }

    // Cancellation must interrupt the backoff rather than wait for all 32 retries.
    val enteredRead = CountDownLatch(1)
    val result = AtomicReference<Throwable?>()
    val interruptPreserved = AtomicReference(false)
    val interruptible = object : InputStream() {
        override fun read(): Int = -1
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            enteredRead.countDown()
            return 0
        }
    }
    val worker = Thread {
        try {
            StreamReadGuard.read(interruptible, buffer, 0, buffer.size)
            result.set(AssertionError("Interrupted stream unexpectedly completed"))
        } catch (failure: Throwable) {
            result.set(failure)
            interruptPreserved.set(Thread.currentThread().isInterrupted)
        }
    }
    worker.start()
    check(enteredRead.await(1, TimeUnit.SECONDS)) { "Worker never entered the stalled stream" }
    worker.interrupt()
    worker.join(1_000)
    check(!worker.isAlive) { "Interrupted SAF read did not stop promptly" }
    check(result.get() is InterruptedIOException) { "Expected InterruptedIOException, got ${result.get()}" }
    check(interruptPreserved.get()) { "Interrupted status was not preserved" }
}


private fun testRequiredUploadChunkRejectsPrematureEof() {
    val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    val buffer = ByteArray(8)
    check(readRequiredUploadChunk(input, buffer, buffer.size) == 3)
    val failure = runCatching {
        readRequiredUploadChunk(input, buffer, buffer.size)
    }.exceptionOrNull()
    check(failure is java.io.EOFException &&
        failure.message == "Connection closed before complete upload") {
        "Premature upload EOF must be reported as EOFException, got $failure"
    }
}


private fun testAbsoluteUploadBodyDeadline() {
    var nowNanos = 0L
    val lease = SessionLease(60_000L) { nowNanos }
    val socket = object : java.net.Socket() {
        var timeoutValue = 0
        override fun setSoTimeout(timeout: Int) { timeoutValue = timeout }
        override fun getSoTimeout(): Int = timeoutValue
    }
    // The peer makes progress every 600 ms, below a 1 s idle timeout. The
    // second read must still fail because the whole body exceeded one second.
    val trickle = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            nowNanos += 600_000_000L
            return super.read(buffer, offset, length)
        }
    }
    val guarded = LeaseBoundUploadInputStream(trickle, socket, 1_000, lease) { nowNanos }
    val one = ByteArray(1)
    check(guarded.read(one, 0, 1) == 1) { "First in-budget trickle byte should be accepted" }
    val failure = runCatching { guarded.read(one, 0, 1) }.exceptionOrNull()
    check(failure is java.net.SocketTimeoutException &&
        failure.message?.contains("absolute deadline") == true) {
        "Slow trickle must hit the absolute body deadline, got $failure"
    }
    check(socket.timeoutValue in 1..1_000) { "Socket timeout must never exceed the body budget" }
    guarded.close()
    socket.close()
}

fun main(args: Array<String>) {
    testLanPeerAddressPolicy()
    testStreamReadGuard()
    testRequiredUploadChunkRejectsPrematureEof()
    testAbsoluteUploadBodyDeadline()
    val ks = KeyStore.getInstance("PKCS12")
    val password = "temporary-test-only".toCharArray()
    File(args[0]).inputStream().use { ks.load(it, password) }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, password)
    val serverSsl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    val trustOnlyForTest = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val clientSsl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustOnlyForTest), SecureRandom()) }
    val itemId = "94285ca7-7903-4b88-8e6d-d69079e0b324"
    val initial = "NetKit test payload 🫧".toByteArray()
    val unknown = ByteArray(88_888) { (it % 247).toByte() }
    val unknownId = "ea54390d-ec27-43bf-acad-5131494470f1"
    val uploaded = mutableMapOf<String, ByteArray>()
    val uploadAttempts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val folderId = "bd8819ba-cafb-45b4-ae70-3ec73580d78d"
    val nestedId = "72c9c931-e477-4a92-a1e7-78b79b85130d"
    var uploadsAllowed = true
    var uploadLimit = 16L * 1024 * 1024 * 1024
    val store = object : ShareStorage, DirectoryShareStorage {
        override fun maxUploadSize(): Long = uploadLimit
        override fun hasSharedDirectory() = true
        override fun browseDirectory(directoryId: String, offset: Int, limit: Int): ShareBrowsePage {
            val rows = when (directoryId) {
                "root" -> listOf(ShareBrowseEntry(folderId, "Photos", true, null))
                folderId -> listOf(ShareBrowseEntry(nestedId, "image.png", false, initial.size.toLong()))
                else -> emptyList()
            }
            return ShareBrowsePage(rows.drop(offset).take(limit), null)
        }
        override fun directoryItem(id: String): ShareItem? =
            if (id == nestedId) ShareItem(nestedId, "image.png", initial.size.toLong()) else null
        override fun openDirectoryItem(id: String): SharedStream? =
            if (id == nestedId) SharedStream(ByteArrayInputStream(initial), initial.size.toLong()) else null
        override fun items() = listOf(ShareItem(itemId, "shared.txt", initial.size.toLong()), ShareItem(unknownId, "unknown.bin", null))
        override fun open(id: String) = when (id) {
            itemId -> SharedStream(ByteArrayInputStream(initial), initial.size.toLong())
            unknownId -> SharedStream(ByteArrayInputStream(unknown), null)
            else -> null
        }
        override fun canUpload() = uploadsAllowed
        override fun upload(fileName: String, body: InputStream, length: Long, expectedSha256: String?, progress: (Long) -> Unit): String {
            uploadAttempts.add(fileName)
            val result = ByteArray(length.toInt())
            var read = 0
            while (read < result.size) {
                val n = body.read(result, read, result.size - read)
                if (n < 0) error("Truncated upload")
                read += n; progress(read.toLong())
            }
            if (expectedSha256 != null) {
                val actual = MessageDigest.getInstance("SHA-256").digest(result).joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) throw UploadHashMismatchException()
            }
            uploaded[fileName] = result
            return fileName
        }
    }
    TransferHttpEngine(serverSsl.serverSocketFactory, store).use { engine ->
        engine.start()
        val root = "https://127.0.0.1:${engine.port}/s/${engine.token}"
        var lastContentRange: String? = null
        fun request(path: String, method: String = "GET", bytes: ByteArray? = null, filename: String? = null,
                    range: String? = null, sha256: String? = null): Pair<Int, ByteArray> {
            val con = URI(path).toURL().openConnection() as HttpsURLConnection
            con.sslSocketFactory = clientSsl.socketFactory
            con.hostnameVerifier = HostnameVerifier { _, _ -> true }
            con.requestMethod = method
            if (range != null) con.setRequestProperty("Range", range)
            if (bytes != null) {
                con.doOutput = true
                con.setFixedLengthStreamingMode(bytes.size)
                con.setRequestProperty("X-File-Name", java.net.URLEncoder.encode(filename!!, "UTF-8"))
                if (sha256 != null) con.setRequestProperty("X-Content-SHA256", sha256)
                con.setRequestProperty("Content-Type", "application/octet-stream")
                con.outputStream.use { it.write(bytes) }
            }
            val code = con.responseCode
            lastContentRange = con.getHeaderField("Content-Range")
            val data = (if (code < 400) con.inputStream else con.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
            con.disconnect()
            return code to data
        }
        check(request(root + "/").first == 200)
        val listing = request(root + "/list")
        check(listing.first == 200 && String(listing.second).contains("shared.txt"))
        val folderRoot = request(root + "/browse?dir=root")
        check(folderRoot.first == 200 && String(folderRoot.second).contains("Photos"))
        val folderFiles = request(root + "/browse?dir=$folderId")
        check(folderFiles.first == 200 && String(folderFiles.second).contains("image.png"))
        check(request(root + "/browse-file/$nestedId").second.contentEquals(initial))
        check(request(root + "/browse-file/$nestedId", range = "bytes=3-8").first == 206)
        check(request(root + "/browse-file/$folderId").first == 404)

        val archiveResponse = request(root + "/browse-archive/$folderId")
        check(archiveResponse.first == 200 && archiveResponse.second.isNotEmpty())
        val archived = mutableMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(archiveResponse.second)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                archived[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        check(archived["image.png"]!!.contentEquals(initial))
        check(request(root + "/browse-archive/not-a-folder").first == 404)

        check(request("https://127.0.0.1:${engine.port}/s/guess/browse").first == 404)
        val dl = request(root + "/file/$itemId")
        check(dl.first == 200 && dl.second.contentEquals(initial))
        // Resumable downloads: known-length SAF streams support single byte ranges.
        val part = request(root + "/file/$itemId", range = "bytes=4-9")
        check(part.first == 206 && part.second.contentEquals(initial.copyOfRange(4, 10)))
        check(lastContentRange == "bytes 4-9/${initial.size}")
        val tail = request(root + "/file/$itemId", range = "bytes=-5")
        check(tail.first == 206 && tail.second.contentEquals(initial.takeLast(5).toByteArray()))
        val rest = request(root + "/file/$itemId", range = "bytes=10-")
        check(rest.first == 206 && rest.second.contentEquals(initial.copyOfRange(10, initial.size)))
        val beyondEnd = request(root + "/file/$itemId", range = "bytes=0-999999")
        check(beyondEnd.first == 206 && beyondEnd.second.contentEquals(initial))
        check(request(root + "/file/$itemId", range = "bytes=999999-").first == 416)
        check(lastContentRange == "bytes */${initial.size}")
        check(request(root + "/file/$itemId", range = "bytes=8-3").first == 416)
        check(request(root + "/file/$itemId", range = "bytes=0-2,5-7").first == 416)
        check(request(root + "/file/$itemId", range = "bytes=-0").first == 416)
        check(request(root + "/file/$itemId", range = "bytes=0-").first == 206)
        val unknownDl = request(root + "/file/$unknownId")
        check(unknownDl.first == 200 && unknownDl.second.contentEquals(unknown))
        val unknownWithRange = request(root + "/file/$unknownId", range = "bytes=10-50")
        check(unknownWithRange.first == 200 && unknownWithRange.second.contentEquals(unknown))
        check(request("https://127.0.0.1:${engine.port}/s/guess/list").first == 404)
        repeat(14) { index ->
            // Exercise early-return paths that historically leaked sockets from activeClients.
            check(request("https://127.0.0.1:${engine.port}/wrong-$index").first == 404)
        }
        // Worker cleanup can finish a few milliseconds after the HTTP client disconnects.
        repeat(40) { if (engine.activeClientCount == 0) return@repeat else Thread.sleep(25) }
        check(engine.activeClientCount == 0) { "Leaked ${engine.activeClientCount} client sockets" }

        val upload = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        check(request(root + "/upload", "POST", upload, "large-test.bin").first == 201)
        check(uploaded["large-test.bin"]!!.contentEquals(upload))
        val validHash = MessageDigest.getInstance("SHA-256").digest(upload).joinToString("") { "%02x".format(it) }
        check(request(root + "/upload", "POST", upload, "verified.bin", sha256 = validHash).first == 201)
        check(uploaded["verified.bin"]!!.contentEquals(upload))
        check(request(root + "/upload", "POST", upload, "wrong-hash.bin", sha256 = "0".repeat(64)).first == 422)
        check("wrong-hash.bin" !in uploaded) { "Mismatched hash must not be committed" }
        check(request(root + "/upload", "POST", byteArrayOf(1), "malformed-hash.bin", sha256 = "not-a-hash").first == 400)
        check("malformed-hash.bin" !in uploaded)
        check(request(root + "/upload", "POST", byteArrayOf(1), "../secrets.txt").first == 400)
        check(request(root + "/upload", "POST", byteArrayOf(1), ".netkit-backdoor.part").first == 400)
        // Browser upload names must be safe on Windows as well as on Android,
        // and must not disguise extensions with invisible Unicode controls.
        val unsafeUploadNames = listOf(
            "CON.txt", "PRN", "AUX.log", "NUL.txt", "LPT1.doc", "COM1.log", "report.", "report ", "bad?.txt", "bad:stream.txt", "bad|name.txt",
            "safe\u202Egpj.exe", "bad\u0085.txt", ".NETKIT-hidden.part"
        )
        for (unsafeName in unsafeUploadNames) {
            check(request(root + "/upload", "POST", byteArrayOf(1), unsafeName).first == 400) {
                "Unsafe upload name was accepted: ${unsafeName.toList()}"
            }
            check(unsafeName !in uploaded)
        }
        val unicodeName = "تقرير طبي 📷.txt"
        check(request(root + "/upload", "POST", byteArrayOf(7), unicodeName).first == 201)
        check(uploaded[unicodeName]?.contentEquals(byteArrayOf(7)) == true)
        check(request(root + "/missing").first == 405)
        uploadsAllowed = false
        check(request(root + "/upload", "POST", byteArrayOf(1), "refused.bin").first == 409)
        uploadsAllowed = true
        uploadLimit = 1
        check(request(root + "/upload", "POST", byteArrayOf(1, 2), "too-large.bin").first == 413)
        check("too-large.bin" !in uploaded)
        uploadLimit = 16L * 1024 * 1024 * 1024
        // Reject an advertised upload beyond guard without reading gigabytes.
        val socket = clientSsl.socketFactory.createSocket("127.0.0.1", engine.port) as SSLSocket
        socket.use {
            it.soTimeout = 5000
            val body = "POST /s/${engine.token}/upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 17179869185\r\nX-File-Name: huge.bin\r\n\r\n"
            it.outputStream.write(body.toByteArray()); it.outputStream.flush()
            val reply = it.inputStream.bufferedReader().readLine().orEmpty()
            check(reply.contains("413")) { "Expected 413 but got: $reply" }
        }
        // Stop the server and make sure the port no longer accepts connections.
        engine.close()
        check(runCatching { java.net.Socket("127.0.0.1", engine.port).use { } }.isFailure)
        println("PASS: index/list, auth denial, socket cleanup, fixed/chunked/resumable downloads, 6 MiB exact upload, SHA-256 verified upload/mismatch/invalid-digest, filenames, upload consent, max-size, closed listener")
    }
    // Active downloads and recursive ZIP exports must stop when the bearer lease expires,
    // not merely reject the next HTTP request. Injected monotonic time makes this deterministic.
    fun assertMidStreamLeaseExpiry(path: String, archive: Boolean) {
        val clock = AtomicLong(0L)
        fun expiringStream(): InputStream = object : InputStream() {
            private var emitted = false
            override fun read(): Int {
                val one = ByteArray(1)
                val count = read(one, 0, 1)
                return if (count < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (emitted) return -1
                emitted = true
                clock.set(1_500_000_000L) // advance beyond the 1-second share lease mid-read
                val count = minOf(length, initial.size)
                System.arraycopy(initial, 0, buffer, offset, count)
                return count
            }
        }
        val slowStore = object : ShareStorage by store, DirectoryShareStorage by store {
            override fun open(id: String): SharedStream? =
                if (!archive && id == itemId) SharedStream(expiringStream(), initial.size.toLong())
                else store.open(id)

            override fun openDirectoryItem(id: String): SharedStream? =
                if (archive && id == nestedId) SharedStream(expiringStream(), initial.size.toLong())
                else store.openDirectoryItem(id)
        }
        TransferHttpEngine(
            serverSsl.serverSocketFactory,
            slowStore,
            sessionLifetimeMillis = 1_000L,
            nowNanos = { clock.get() }
        ).use { timed ->
            timed.start()
            val url = URI("https://127.0.0.1:${timed.port}/s/${timed.token}$path").toURL()
            val connection = url.openConnection() as HttpsURLConnection
            connection.sslSocketFactory = clientSsl.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            try {
                check(connection.responseCode == 200) { "Transfer should start before the injected expiry" }
                val received = java.io.ByteArrayOutputStream()
                val failure = runCatching {
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received.write(buffer, 0, count)
                        }
                    }
                }.exceptionOrNull()
                check(clock.get() >= 1_000_000_000L) { "The stream did not cross the lease deadline" }
                check(failure is IOException) {
                    "Expired ${if (archive) "ZIP" else "file"} transfer must end as an incomplete HTTP body, got $failure"
                }
                if (!archive) {
                    check(received.size() == 0) { "No file bytes may be sent after the lease expires" }
                } else {
                    check(!String(received.toByteArray(), StandardCharsets.UTF_8).contains("NetKit test payload")) {
                        "ZIP file payload must not be emitted after the lease expires"
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }
    assertMidStreamLeaseExpiry("/file/$itemId", archive = false)
    assertMidStreamLeaseExpiry("/browse-archive/$folderId", archive = true)
    println("PASS: active file and recursive ZIP downloads are revoked mid-stream when the share lease expires")

    // End-to-end TLS regression: expired tokens must reject real HTTPS requests, not just report a timer.
    TransferHttpEngine(serverSsl.serverSocketFactory, store, sessionLifetimeMillis = 40L).use { timed ->
        timed.start()
        Thread.sleep(100)
        val url = URI("https://127.0.0.1:${timed.port}/s/${timed.token}/list").toURL()
        val con = url.openConnection() as HttpsURLConnection
        con.sslSocketFactory = clientSsl.socketFactory
        con.hostnameVerifier = HostnameVerifier { _, _ -> true } // test fixture only
        check(con.responseCode == 410) { "Expired share was not denied by HTTPS server" }
        con.disconnect()
        check(timed.sessionRemainingMillis == 0L)
    }
    println("PASS: HTTPS session expiration rejects expired bearer token")

    // An HTTPS share lease must also govern an upload already in progress. A body
    // that stalls across expiry is denied and never reaches the storage commit point.
    TransferHttpEngine(
        serverSsl.serverSocketFactory, store,
        sessionLifetimeMillis = 2_500L, bodyTimeoutMillis = 4_000
    ).use { timedUpload ->
        timedUpload.start()
        val socket = clientSsl.socketFactory.createSocket("127.0.0.1", timedUpload.port) as SSLSocket
        socket.use {
            it.soTimeout = 5_000
            val name = "expired-mid-upload.bin"
            val first = ByteArray(64 * 1024) { (it % 251).toByte() }
            val second = ByteArray(64 * 1024) { ((it + 7) % 251).toByte() }
            val headers = "POST /s/${timedUpload.token}/upload HTTP/1.1\r\n" +
                "Host: localhost\r\nContent-Length: ${first.size + second.size}\r\n" +
                "X-File-Name: ${java.net.URLEncoder.encode(name, "UTF-8")}\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            it.outputStream.write(headers.toByteArray(StandardCharsets.US_ASCII))
            it.outputStream.write(first)
            it.outputStream.flush()
            val attemptDeadline = System.nanoTime() + 1_000_000_000L
            while (!uploadAttempts.contains(name) && System.nanoTime() < attemptDeadline) Thread.sleep(10)
            check(uploadAttempts.contains(name)) { "Upload did not enter storage before the expiry test" }
            Thread.sleep(2_700)
            // The server may already have returned 410 at the lease deadline.
            runCatching { it.outputStream.write(second); it.outputStream.flush() }
            val status = runCatching { it.inputStream.bufferedReader().readLine().orEmpty() }.getOrDefault("")
            check(status.contains("410")) { "Expired in-flight upload should return HTTP 410, got: $status" }
        }
        check("expired-mid-upload.bin" !in uploaded) { "Expired upload must never be committed" }
    }
    println("PASS: active HTTPS upload is revoked at lease expiry and cannot commit")


    // A local LAN client must not monopolize one of the four workers by
    // dripping an incomplete request header just before each socket timeout.
    TransferHttpEngine(
        serverSsl.serverSocketFactory, store,
        headerTimeoutMillis = 300, bodyTimeoutMillis = 2_000
    ).use { guarded ->
        guarded.start()
        val socket = clientSsl.socketFactory.createSocket("127.0.0.1", guarded.port) as SSLSocket
        socket.soTimeout = 2_000
        socket.use {
            it.outputStream.write("G".toByteArray(StandardCharsets.US_ASCII))
            it.outputStream.flush()
            Thread.sleep(120)
            runCatching {
                it.outputStream.write("E".toByteArray(StandardCharsets.US_ASCII))
                it.outputStream.flush()
            }
            Thread.sleep(120)
            runCatching {
                it.outputStream.write("T".toByteArray(StandardCharsets.US_ASCII))
                it.outputStream.flush()
            }
            Thread.sleep(180)
            val closed = runCatching { it.inputStream.read() }.fold(
                onSuccess = { value -> value == -1 },
                onFailure = { error -> error !is java.net.SocketTimeoutException }
            )
            check(closed) { "Slow request header was not closed at the absolute deadline" }
        }
        repeat(40) { if (guarded.activeClientCount != 0) Thread.sleep(25) }
        check(guarded.activeClientCount == 0) { "Slow-header socket remained registered" }
    }
    println("PASS: bounded absolute HTTP request-header deadline and worker cleanup")
}
