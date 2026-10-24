package com.netkit.local.qa

import com.netkit.local.share.*
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.KeyStore
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** TEST ONLY. The trust-all verifier MUST NEVER be imported into the Android app. */
fun main(args: Array<String>) {
    // Read guard is shared by HTTPS full/Range downloads and SAF browser uploads.
    // A transient zero read must preserve bytes; an endlessly stalled provider
    // must not monopolize a TLS worker or leave a .part upload spinning.
    run {
        val flaky = object : InputStream() {
            private val payload = byteArrayOf(4, 5, 6)
            private var pos = 0
            private var emptyReads = 2
            override fun read(): Int = if (pos < payload.size) payload[pos++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (emptyReads-- > 0) return 0
                if (pos >= payload.size) return -1
                val count = minOf(len, payload.size - pos)
                payload.copyInto(b, off, pos, pos + count)
                pos += count
                return count
            }
        }
        val bytes = ByteArray(3)
        check(StreamReadGuard.read(flaky, bytes, 0, bytes.size) == 3)
        check(bytes.contentEquals(byteArrayOf(4, 5, 6)))
        check(StreamReadGuard.read(flaky, bytes, 0, bytes.size) == -1)
    }
    run {
        var attempts = 0
        val stalled = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                attempts++
                return 0
            }
        }
        val error = runCatching { StreamReadGuard.read(stalled, ByteArray(8), 0, 8) }.exceptionOrNull()
        check(error is IllegalStateException && error.message == "Shared stream read stalled")
        check(attempts == 33) { "Unexpected zero-read retry budget: $attempts" }
    }
    println("PASS: HTTPS/SAF stream guard recovers transient zeros and rejects permanent stalls")
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
    val folderId = "bd8819ba-cafb-45b4-ae70-3ec73580d78d"
    val nestedId = "72c9c931-e477-4a92-a1e7-78b79b85130d"
    var uploadsAllowed = true
    var rejectUpload = false
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
            if (rejectUpload) throw UploadPermissionRevokedException()
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
        override fun uploadRelative(folders: List<String>, fileName: String, body: InputStream,
                                    length: Long, expectedSha256: String?, progress: (Long) -> Unit): String =
            upload(folders.joinToString("/") + "/" + fileName, body, length, expectedSha256, progress)
    }
    TransferHttpEngine(serverSsl.serverSocketFactory, store).use { engine ->
        // The browser URL decoder can replace invalid UTF-8 with U+FFFD.
        // Invalid UTF-16 surrogates can also alias valid names on SAF providers.
        check(!engine.validName("bad\uFFFDname.txt"))
        check(!engine.validName("broken" + '\uD800' + ".txt"))
        check(!engine.validName("broken" + '\uDC00' + ".txt"))
        check(engine.validName("صورة-😀.png"))
        engine.start()
        val root = "https://127.0.0.1:${engine.port}/s/${engine.token}"
        var lastContentRange: String? = null
        fun request(path: String, method: String = "GET", bytes: ByteArray? = null, filename: String? = null,
                    range: String? = null, sha256: String? = null,
                    relativePath: String? = null): Pair<Int, ByteArray> {
            val con = URI(path).toURL().openConnection() as HttpsURLConnection
            con.sslSocketFactory = clientSsl.socketFactory
            con.hostnameVerifier = HostnameVerifier { _, _ -> true }
            con.requestMethod = method
            if (range != null) con.setRequestProperty("Range", range)
            if (bytes != null) {
                con.doOutput = true
                con.setFixedLengthStreamingMode(bytes.size)
                con.setRequestProperty("X-File-Name", filename!!)
                if (sha256 != null) con.setRequestProperty("X-Content-SHA256", sha256)
                if (relativePath != null) con.setRequestProperty("X-Relative-Path", relativePath)
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
        // Exercise the real TLS request parser, not a mock. Invalid field-name
        // whitespace, folded headers, controls, duplicate framing and chunked
        // request bodies must be rejected before invoking ShareStorage.
        fun rawHeaderStatus(fields: String, target: String = "/s/${engine.token}/list"): String {
            (clientSsl.socketFactory.createSocket("127.0.0.1", engine.port) as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                val requestText = "GET $target HTTP/1.1\r\nHost: localhost\r\n" +
                    fields + "\r\n"
                socket.outputStream.write(requestText.toByteArray(Charsets.ISO_8859_1))
                socket.outputStream.flush()
                return socket.inputStream.bufferedReader().readLine().orEmpty()
            }
        }
        check(rawHeaderStatus("X-Test: safe\r\n").contains("200"))
        // Strict CRLF prevents ambiguous request framing across HTTP parsers.
        check(rawHeaderStatus("X-Test: bare-LF\n").contains("400"))
        // Bound unique-header count even when total bytes stay below 32 KiB.
        val tooManyHeaders = (1..101).joinToString("") { "X-Guard-$it: v\r\n" }
        check(rawHeaderStatus(tooManyHeaders).contains("400"))
        val maxHeaders = (1..99).joinToString("") { "X-Guard-$it: v\r\n" }
        check(rawHeaderStatus(maxHeaders).contains("200"))
        for (badHeaders in listOf(
            "Content-Length : 0\r\n", // whitespace in header name
            "Bad Name: value\r\n",
            "X-Test: okay\r\n folded\r\n", // obsolete line folding
            "X-Test: bad\u0001value\r\n",
            "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n",
            "Content-Length: 0\r\ncontent-length: 0\r\n"
        )) {
            check(rawHeaderStatus(badHeaders).contains("400")) {
                "Unsafe HTTP headers accepted: ${badHeaders.toByteArray().contentToString()}"
            }
        }
        // Browse parameters are security-sensitive SAF selectors. Malformed
        // encodings, duplicates and bad offsets must return HTTP 400 instead
        // of silently browsing root or dropping the TLS connection.
        val browseTarget = "/s/${engine.token}/browse"
        for (badQuery in listOf(
            "dir=not-a-uuid", "dir=root&dir=$folderId", "offset=1&offset=2",
            "offset=-1", "offset=1000001", "offset=abc", "offset=01",
            "dir=%GG", "dir=%C3%28", "dir=root&", "unexpected=value"
        )) {
            val status = rawHeaderStatus("", "$browseTarget?$badQuery")
            check(status.contains("400")) { "Invalid browse query accepted: $badQuery ($status)" }
        }
        check(rawHeaderStatus("", "$browseTarget?dir=root&offset=0").contains("200"))
        check(rawHeaderStatus("", "$browseTarget?dir=$folderId&offset=0").contains("200"))
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
        // Folder uploads preserve relative structure under the explicitly chosen
        // SAF destination. The transport rejects unsafe paths before storage access.
        val folderName = java.net.URLEncoder.encode("صورة-😀.png", "UTF-8")
        val relative = java.net.URLEncoder.encode("Photos/2026/صورة-😀.png", "UTF-8")
        check(request(root + "/upload", "POST", byteArrayOf(1, 2, 3), folderName,
            relativePath = relative).first == 201)
        check(uploaded["Photos/2026/صورة-😀.png"]!!.contentEquals(byteArrayOf(1, 2, 3)))
        for (badPath in listOf(
            "Photos%2F..%2Ffile.txt", "Photos%2Ffile.txt%2F", "Photos%2FCON.txt",
            "Photos%2Fother.txt", "Photos%2F%2Ffile.txt", "Photos%2Fbad%EF%BF%BDfile.txt",
            "Photos%2Ffile.txt%2Fextra", "Photos%2Ffile.txt%ZZ"
        )) {
            check(request(root + "/upload", "POST", byteArrayOf(7), "file.txt",
                relativePath = badPath).first == 400) { "Unsafe folder upload accepted: $badPath" }
        }
        check(request(root + "/upload", "POST", upload, "large-test.bin").first == 201)
        check(uploaded["large-test.bin"]!!.contentEquals(upload))
        val validHash = MessageDigest.getInstance("SHA-256").digest(upload).joinToString("") { "%02x".format(it) }
        check(request(root + "/upload", "POST", upload, "verified.bin", sha256 = validHash).first == 201)
        check(uploaded["verified.bin"]!!.contentEquals(upload))
        check(request(root + "/upload", "POST", upload, "wrong-hash.bin", sha256 = "0".repeat(64)).first == 422)
        check("wrong-hash.bin" !in uploaded) { "Mismatched hash must not be committed" }
        check(request(root + "/upload", "POST", byteArrayOf(1), "malformed-hash.bin", sha256 = "not-a-hash").first == 400)
        check("malformed-hash.bin" !in uploaded)
        check(request(root + "/upload", "POST", byteArrayOf(1), "..%2Fsecrets.txt").first == 400)
        check(request(root + "/upload", "POST", byteArrayOf(1), ".netkit-backdoor.part").first == 400)
        check(request(root + "/upload", "POST", byteArrayOf(1), "bad%EF%BF%BDname.txt").first == 400)
        check("bad\uFFFDname.txt" !in uploaded)
        // Authorized browser uploads still must not create spoofed Windows/SAF names.
        for (unsafe in listOf(
            "CON.txt", "LPT1.log", "COM%C2%B9.txt", "report.", "report%20",
            "bad%3Aname.txt", "invoice%E2%80%AEgpj.exe",
            "hidden%E2%81%A6name.txt", ".NETKIT-shadow.part",
            "bad%E2%80%A8line.txt", "bad%EF%BB%BFmark.txt"
        )) {
            check(request(root + "/upload", "POST", byteArrayOf(7), unsafe).first == 400) {
                "Unsafe browser upload accepted: $unsafe"
            }
        }
        check(uploaded.keys.none { it.startsWith("CON") || it.startsWith("LPT") ||
            it.startsWith("COM") || it.startsWith("bad") || it.startsWith("invoice") })
        // Preserve valid non-ASCII names and actual upload bytes.
        val arabicName = "ملف-آمن.txt"
        val arabicEncoded = java.net.URLEncoder.encode(arabicName, "UTF-8")
        check(request(root + "/upload", "POST", byteArrayOf(10, 20), arabicEncoded).first == 201)
        check(uploaded[arabicName]!!.contentEquals(byteArrayOf(10, 20)))
        check(request(root + "/missing").first == 405)
        uploadsAllowed = false
        check(request(root + "/upload", "POST", byteArrayOf(1), "refused.bin").first == 409)
        uploadsAllowed = true
        rejectUpload = true
        check(request(root + "/upload", "POST", byteArrayOf(42), "revoked.bin").first == 403)
        check("revoked.bin" !in uploaded)
        rejectUpload = false
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
}
