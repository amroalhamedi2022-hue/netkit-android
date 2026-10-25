package com.netkit.local.share

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.Deflater
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import kotlin.concurrent.thread

/** Java/JVM-only transport core. Android storage and certificate code are adapters. */
data class ShareItem(val id: String, val name: String, val length: Long?)
data class SharedStream(val stream: InputStream, val length: Long?)
data class ShareBrowseEntry(val id: String, val name: String, val directory: Boolean, val length: Long?)
data class ShareBrowsePage(val entries: List<ShareBrowseEntry>, val nextOffset: Int?)
interface DirectoryShareStorage {
    fun browseDirectory(directoryId: String, offset: Int, limit: Int = 100): ShareBrowsePage
    fun directoryItem(id: String): ShareItem?
    fun openDirectoryItem(id: String): SharedStream?
    fun hasSharedDirectory(): Boolean
}
class UploadHashMismatchException : IllegalStateException("Upload SHA-256 does not match")
class UploadPermissionRevokedException : IllegalStateException("Browser upload consent revoked")

interface ShareStorage {
    fun items(): List<ShareItem>
    fun open(id: String): SharedStream?
    fun canUpload(): Boolean
    /** Epoch changes whenever Android SAF destination or browser-upload consent changes. */
    fun uploadGrantEpoch(): Long = 0L
    fun maxUploadSize(): Long = 16L * 1024 * 1024 * 1024
    fun upload(fileName: String, body: InputStream, length: Long, expectedSha256: String?, progress: (Long) -> Unit): String
    /** SAF-aware implementations may opt into bounded nested-folder uploads. */
    fun uploadRelative(folders: List<String>, fileName: String, body: InputStream,
                       length: Long, expectedSha256: String?, progress: (Long) -> Unit): String =
        throw UnsupportedOperationException("Folder upload unavailable")
}

/**
 * SAF/cloud providers can transiently return zero bytes for a positive-length read.
 * Bound repeated zero reads so a broken provider cannot spin a download/Range
 * worker indefinitely. This does not alter normal EOF or partial reads.
 */
internal object StreamReadGuard {
    fun read(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        require(length > 0 && offset >= 0 && offset <= buffer.size - length)
        var emptyReads = 0
        while (true) {
            val n = input.read(buffer, offset, length)
            if (n != 0) return n
            check(++emptyReads <= 32) { "Shared stream read stalled" }
            Thread.yield()
        }
    }
}

class TransferHttpEngine(
    private val sslFactory: SSLServerSocketFactory,
    private val storage: ShareStorage,
    private val progress: (Long, Long) -> Unit = { _, _ -> },
    sessionLifetimeMillis: Long = 30L * 60 * 1000,
    private val headerTimeoutMillis: Long = 12_000L,
    private val uploadCacheRoot: File? = null
) : AutoCloseable {
    init { require(headerTimeoutMillis in 1L..120_000L) { "Invalid HTTP header timeout" } }
    companion object { private const val MAX_UPLOAD = 16L * 1024 * 1024 * 1024 } // 16 GiB hard guard
    private val rng = SecureRandom()
    private val secret = ByteArray(24).also(rng::nextBytes)
    val token: String = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    private val prefix = "/s/$token"
    private val lease = SessionLease(sessionLifetimeMillis)
    private val resumable = uploadCacheRoot?.let { root ->
        ResumableBrowserUpload(File(root, "netkit-browser-" + UUID.randomUUID()),
            storage, { storage.uploadGrantEpoch() }, { storage.canUpload() && lease.isValid() })
    }
    val sessionRemainingMillis: Long get() = lease.remainingMillis()
    private val pool = ThreadPoolExecutor(
        4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(12),
        ThreadPoolExecutor.AbortPolicy()
    )
    @Volatile private var alive = false
    // Closing the listener alone does NOT terminate existing uploads: track sockets too.
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    private var listener: SSLServerSocket? = null
    val port: Int get() = listener?.localPort ?: 0

    fun start() {
        check(!alive) { "Already started" }
        // TLS 1.2+; browser must explicitly accept the local, self-signed certificate.
        val server = sslFactory.createServerSocket(0) as SSLServerSocket
        server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        server.soTimeout = 1000
        listener = server
        alive = true
        thread(name = "NetKit HTTPS acceptor", isDaemon = true) {
            while (alive) {
                try {
                    val client = server.accept()
                    if (!client.inetAddress.isSiteLocalAddress &&
                        !client.inetAddress.isLinkLocalAddress &&
                        !client.inetAddress.isLoopbackAddress) {
                        client.close()
                        continue
                    }
                    activeClients.add(client)
                    try { pool.execute { handle(client) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) {
                        activeClients.remove(client)
                        client.close()
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Wake periodically so close() exits cleanly.
                } catch (_: java.net.SocketException) {
                    if (alive) break
                } catch (_: Exception) {
                    // A bad client must not crash the listener.
                }
            }
        }
    }

    override fun close() {
        lease.revoke()
        alive = false
        runCatching { listener?.close() }
        listener = null
        activeClients.forEach { runCatching { it.close() } }
        activeClients.clear()
        pool.shutdownNow()
        resumable?.close()
    }

    // Observable in portable regression tests; should return to zero after every response.
    internal val activeClientCount: Int get() = activeClients.size

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                // Slow clients must not monopolize the four TLS workers by
                // dripping header bytes forever. A read timeout alone is not
                // enough: enforce one absolute budget for request line + headers.
                socket.soTimeout = minOf(headerTimeoutMillis, 5_000L).toInt()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(headerTimeoutMillis)
                val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                try {
                    val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                    val line = readLine(input, 4096, deadline) ?: return
                    val parts = line.split(' ')
                    if (parts.size != 3 || parts[2] != "HTTP/1.1") return fail(out, 400, "Invalid request")
                    val method = parts[0]
                    val path = parts[1].substringBefore('?')
                    val query = parts[1].substringAfter('?', "")
                    // Malformed or ambiguous HTTP headers must receive a clear
                    // rejection before any storage or download operation begins.
                    val headers = try { readHeaders(input, deadline) }
                        catch (_: IllegalArgumentException) { return fail(out, 400, "Invalid headers") }
                    // Large file transfers have their own idle budget; only
                    // request headers are constrained by the short absolute timer.
                    socket.soTimeout = 90_000
                    if (headers.containsKey("transfer-encoding")) {
                        return fail(out, 400, "Transfer-Encoding is not supported")
                    }
                    if (path != prefix && !path.startsWith("$prefix/")) return fail(out, 404, "Not found")
                    if (!lease.isValid()) return fail(out, 410, "Sharing session expired")
                    when {
                        method == "GET" && (path == prefix || path == "$prefix/") -> html(out)
                        method == "GET" && path == "$prefix/list" -> list(out)
                        method == "GET" && path == "$prefix/browse" -> browse(out, query)
                        method == "GET" && path.startsWith("$prefix/browse-file/") -> download(out, path.removePrefix("$prefix/browse-file/"), headers["range"], folderMode = true)
                        method == "GET" && path.startsWith("$prefix/browse-checksum/") -> checksum(out, path.removePrefix("$prefix/browse-checksum/"), folderMode = true)
                        method == "GET" && path.startsWith("$prefix/browse-archive/") -> downloadArchive(out, path.removePrefix("$prefix/browse-archive/"))
                        method == "GET" && path.startsWith("$prefix/file/") -> download(out, path.removePrefix("$prefix/file/"), headers["range"])
                        method == "GET" && path.startsWith("$prefix/checksum/") -> checksum(out, path.removePrefix("$prefix/checksum/"))
                        method == "GET" && path.startsWith("$prefix/upload-status/") -> uploadStatus(out, path.removePrefix("$prefix/upload-status/"))
                        method == "POST" && path == "$prefix/upload-chunk" -> uploadChunk(input, out, headers)
                        method == "POST" && path == "$prefix/upload" -> upload(input, out, headers)
                        else -> fail(out, 405, "Unsupported route")
                    }
                } catch (_: Exception) {
                    // Bad HTTPS clients and cancelled transfers must not kill the server.
                } finally {
                    runCatching { out.flush() }
                }
            }
        } finally {
            // An early return in client.use previously skipped removal and leaked socket refs.
            activeClients.remove(client)
        }
    }

    private fun readLine(input: InputStream, max: Int, deadline: Long): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            check(System.nanoTime() - deadline < 0) { "HTTP header deadline exceeded" }
            val c = input.read()
            check(System.nanoTime() - deadline < 0) { "HTTP header deadline exceeded" }
            if (c < 0) return if (bytes.size() == 0) null else throw IllegalArgumentException("Truncated headers")
            if (c == 10) {
                val data = bytes.toByteArray()
                // Require CRLF, not bare LF: ambiguous HTTP framing must not
                // be accepted by the local HTTPS parser.
                require(data.isNotEmpty() && data.last() == 13.toByte()) { "Bare LF in HTTP request" }
                return String(data, 0, data.size - 1, StandardCharsets.ISO_8859_1)
            }
            if (bytes.size() >= max) throw IllegalArgumentException("Header line too long")
            bytes.write(c)
        }
    }
    private fun readHeaders(input: InputStream, deadline: Long): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var used = 0
        var count = 0
        while (true) {
            val line = readLine(input, 4096, deadline) ?: throw IllegalArgumentException("Missing header terminator")
            used += line.length
            require(used < 32_768) { "Headers too long" }
            if (line.isEmpty()) return headers
            require(++count <= 100) { "Too many HTTP headers" }
            val i = line.indexOf(':')
            require(i > 0) { "Bad header" }
            // RFC 7230 token syntax: never normalize whitespace in a field name.
            // Accepting "Content-Length : ..." or obs-fold permits HTTP parser
            // disagreements and can conceal conflicting request framing.
            val rawName = line.substring(0, i)
            require(rawName.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid header name" }
            val key = rawName.lowercase(java.util.Locale.ROOT)
            require(!headers.containsKey(key)) { "Duplicate header" }
            val value = line.substring(i + 1)
            require(value.none { it.code < 0x20 && it != '\t' || it.code in 0x7f..0x9f }) {
                "Invalid header value"
            }
            headers[key] = value.trim()
        }
    }

    private fun commonHeaders(): Map<String, String> = mapOf(
        "Cache-Control" to "no-store", "Referrer-Policy" to "no-referrer",
        "X-Content-Type-Options" to "nosniff", "X-Frame-Options" to "DENY",
        "Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'"
    )

    private fun head(out: OutputStream, status: Int, type: String, length: Long?, extra: Map<String,String> = emptyMap()) {
        val reasons = mapOf(200 to "OK", 201 to "Created", 206 to "Partial Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 410 to "Gone", 413 to "Content Too Large", 416 to "Range Not Satisfiable", 422 to "Unprocessable Content", 500 to "Server Error", 501 to "Not Implemented")
        val sb = StringBuilder("HTTP/1.1 $status ${reasons[status] ?: "Error"}\r\n")
        val headers = commonHeaders() + extra + mapOf("Content-Type" to type, "Connection" to "close") +
            if (length == null) mapOf("Transfer-Encoding" to "chunked") else mapOf("Content-Length" to length.toString())
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }
    private fun response(out: OutputStream, status: Int, text: String, type: String = "text/plain; charset=utf-8") {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        head(out, status, type, bytes.size.toLong()); out.write(bytes)
    }
    private fun fail(out: OutputStream, status: Int, reason: String) = response(out, status, reason)
    private fun jsonEscape(v: String): String = buildString {
        append('"')
        v.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }}
        append('"')
    }
    private fun list(out: OutputStream) {
        val items = storage.items().joinToString(",") { item ->
            "{\"id\":${jsonEscape(item.id)},\"name\":${jsonEscape(item.name)},\"length\":${item.length ?: "null"}}"
        }
        response(out, 200, "{\"items\":[$items],\"uploadAllowed\":${storage.canUpload()}}", "application/json; charset=utf-8")
    }
    private fun browse(out: OutputStream, query: String) {
        val storage = storage as? DirectoryShareStorage ?: return fail(out, 404, "Folder browsing unavailable")
        if (!storage.hasSharedDirectory()) return response(out, 200, """{"entries":[],"nextOffset":null,"enabled":false}""", "application/json; charset=utf-8")
        // Never silently fall back to the shared root for malformed, duplicate
        // or ambiguous browser queries. Invalid percent-encoding and UTF-8
        // must not cause an unhandled exception that drops the TLS connection.
        val args = try {
            val values = mutableMapOf<String, String>()
            if (query.isNotEmpty()) for (part in query.split('&')) {
                val eq = part.indexOf('=')
                require(eq > 0) { "Malformed browse query" }
                val key = part.substring(0, eq)
                require(key == "dir" || key == "offset") { "Unknown browse parameter" }
                require(!values.containsKey(key)) { "Duplicate browse parameter" }
                val value = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                require('\uFFFD' !in value) { "Malformed browse encoding" }
                values[key] = value
            }
            values
        } catch (_: IllegalArgumentException) {
            return fail(out, 400, "Invalid browse query")
        }
        val dir = args["dir"] ?: "root"
        if (dir != "root" && !dir.matches(Regex("[a-fA-F0-9-]{36}")))
            return fail(out, 400, "Invalid browse directory")
        val rawOffset = args["offset"]
        if (rawOffset != null && (!rawOffset.matches(Regex("0|[1-9][0-9]{0,6}")) ||
                (rawOffset.toIntOrNull() ?: Int.MAX_VALUE) > 1_000_000))
            return fail(out, 400, "Invalid browse offset")
        val offset = rawOffset?.toInt() ?: 0
        val page = storage.browseDirectory(dir, offset, 100)
        val content = page.entries.joinToString(",") { item ->
            """{"id":${jsonEscape(item.id)},"name":${jsonEscape(item.name)},"directory":${item.directory},"length":${item.length ?: "null"}}"""
        }
        val next = page.nextOffset?.toString() ?: "null"
        response(out, 200, """{"entries":[$content],"nextOffset":$next,"enabled":true}""", "application/json; charset=utf-8")
    }

    /** Stream the authorized folder tree as ZIP without staging a copy on Android. */
    private fun downloadArchive(out: OutputStream, id: String) {
        val store = storage as? DirectoryShareStorage ?: return fail(out, 404, "Folder sharing unavailable")
        if (!store.hasSharedDirectory() || (id != "root" && !id.matches(Regex("[a-fA-F0-9-]{36}")))) {
            return fail(out, 404, "Folder not shared")
        }
        val provider = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int): ArchivePage {
                val page = store.browseDirectory(folderId, offset, limit)
                return ArchivePage(page.entries.map { ArchiveFile(it.id, it.name, it.directory) }, page.nextOffset)
            }
            override fun open(fileId: String): InputStream? = store.openDirectoryItem(fileId)?.stream
        }
        val name = if (id == "root") "NetKit-shared-storage.zip" else "NetKit-folder.zip"
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        head(out, 200, "application/zip", null,
            mapOf("Content-Disposition" to "attachment; filename*=UTF-8''$encoded"))
        val framed = object : OutputStream() {
            private var finished = false
            override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (count <= 0) return
                check(!finished)
                out.write((count.toString(16) + "\r\n").toByteArray(StandardCharsets.US_ASCII))
                out.write(bytes, offset, count)
                out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            // ZipOutputStream.close() must not signal a successful HTTP response
            // if exporting a file fails halfway through. Only finishResponse() does.
            override fun close() = Unit
            fun finishResponse() {
                if (!finished) {
                    finished = true
                    out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                }
            }
        }
        ZipOutputStream(framed).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            DirectoryArchive.write(id, provider, zip, includeChecksums = true)
        }
        framed.finishResponse()
    }

    /**
     * On-demand, bounded-memory SHA-256 for an explicitly shared file. This
     * computes the digest over the entire SAF stream without staging it in RAM.
     * A checksum is a point-in-time reading, not a snapshot guarantee if a
     * different app modifies the source during or after the request.
     */
    private fun checksum(out: OutputStream, id: String, folderMode: Boolean = false) {
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return fail(out, 404, "No file")
        val directory = storage as? DirectoryShareStorage
        val item = if (folderMode) directory?.directoryItem(id)
            else storage.items().firstOrNull { it.id == id }
        if (item == null) return fail(out, 404, "No file")
        val source = if (folderMode) directory?.openDirectoryItem(id) else storage.open(id)
        if (source == null) return fail(out, 404, "File unavailable")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val buffer = ByteArray(64 * 1024)
            source.stream.use { input ->
                while (true) {
                    if (!alive || !lease.isValid() || Thread.currentThread().isInterrupted)
                        throw java.io.InterruptedIOException("Checksum cancelled")
                    val n = StreamReadGuard.read(input, buffer, 0, buffer.size)
                    if (n < 0) break
                    if (count > Long.MAX_VALUE - n) error("Checksum byte count overflow")
                    digest.update(buffer, 0, n)
                    count += n
                }
            }
            if (source.length != null && source.length >= 0L && count != source.length)
                return fail(out, 409, "File size changed during checksum")
            val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            response(out, 200, """{"sha256":"$hex","bytes":$count}""", "application/json; charset=utf-8")
        } catch (_: java.io.InterruptedIOException) {
            // Server stop/session expiry closes the socket; never report a partial digest.
        } catch (_: Exception) {
            if (alive && lease.isValid()) fail(out, 500, "Could not calculate checksum")
        }
    }

    /** Single RFC 7233 byte ranges enable clients to resume DOWNLOADS.
     *  Android SAF streams can be non-seekable: we discard bytes to reach the requested offset.
     *  Unknown-length streams ignore Range and continue chunked, which HTTP allows.
     *  Upload resumption is NOT implemented.
     */
    private fun download(out: OutputStream, id: String, rangeHeader: String?, folderMode: Boolean = false) {
        // IDs are server-generated UUIDs, not filesystem paths.
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return fail(out, 404, "No file")
        val directory = storage as? DirectoryShareStorage
        val item = if (folderMode) directory?.directoryItem(id)
            else storage.items().firstOrNull { it.id == id }
        if (item == null) return fail(out, 404, "No file")
        val source = (if (folderMode) directory?.openDirectoryItem(id) else storage.open(id))
            ?: return fail(out, 404, "File unavailable")
        source.stream.use { input ->
            val total = source.length?.takeIf { it >= 0L }
            val filename = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            val extra = mapOf("Content-Disposition" to "attachment; filename*=UTF-8''$filename")
            if (total != null) {
                val range = if (rangeHeader != null) parseSingleRange(rangeHeader, total) else null
                if (rangeHeader != null && range == null) {
                    head(out, 416, "text/plain; charset=utf-8", 0L,
                        mapOf("Content-Range" to "bytes */$total", "Accept-Ranges" to "bytes"))
                    return
                }
                val first = range?.first ?: 0L
                val last = range?.last ?: (total - 1L)
                // Don't advertise successful partial content before we can actually skip to it.
                if (first > 0L && !discardExactly(input, first)) return fail(out, 500, "File became shorter")
                val count = if (range != null) last - first + 1 else total
                val rangeHeaders = if (range != null)
                    mapOf("Content-Range" to "bytes $first-$last/$total", "Accept-Ranges" to "bytes")
                else mapOf("Accept-Ranges" to "bytes")
                head(out, if (range != null) 206 else 200, "application/octet-stream", count, extra + rangeHeaders)
                var remaining = count
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0L) {
                    val n = StreamReadGuard.read(input, buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n < 0) break // Client detects a truncated Content-Length.
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            } else {
                head(out, 200, "application/octet-stream", null, extra)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = StreamReadGuard.read(input, buffer, 0, buffer.size)
                    if (n < 0) break
                    out.write("${n.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
                    out.write(buffer, 0, n); out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                }
                out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
        }
    }

    /** Returns inclusive bounds or null for invalid/unsupported requests, never allocates from range size. */
    private fun parseSingleRange(header: String, size: Long): LongRange? {
        if (size <= 0L || !header.startsWith("bytes=") || header.length > 90) return null
        val spec = header.removePrefix("bytes=")
        if (',' in spec || spec.count { it == '-' } != 1) return null // no multipart ranges
        val dash = spec.indexOf('-')
        val a = spec.substring(0, dash)
        val b = spec.substring(dash + 1)
        if (a.isEmpty()) {
            val suffix = b.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            return (size - minOf(size, suffix))..(size - 1L)
        }
        val begin = a.toLongOrNull() ?: return null
        if (begin < 0L || begin >= size) return null
        val finish = if (b.isEmpty()) size - 1L else (b.toLongOrNull() ?: return null)
        if (finish < begin) return null
        return begin..minOf(finish, size - 1L)
    }

    private fun discardExactly(stream: InputStream, bytes: Long): Boolean {
        var remaining = bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) { remaining -= skipped; continue }
            val n = StreamReadGuard.read(stream, buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) return false
            if (n > 0) remaining -= n
        }
        return true
    }


    /** Query authoritative persisted offset after a lost chunk response. */
    private fun uploadStatus(out: OutputStream, id: String) {
        if (!storage.canUpload()) return fail(out, 403, "Browser uploads disabled")
        val staged = resumable ?: return fail(out, 501, "Resumable uploads unavailable")
        if (!id.matches(Regex("[0-9a-f]{32}"))) return fail(out, 400, "Invalid upload ID")
        val state = staged.status(id) ?: return fail(out, 404, "Upload session not found")
        response(out, 200,
            "{\\"received\\":${state.received},\\"total\\":${state.total},\\"complete\\":${state.complete}}",
            "application/json; charset=utf-8")
    }

    /** At most 1 MiB per request; a full verified SAF commit occurs only after all bytes arrive. */
    private fun uploadChunk(input: InputStream, out: OutputStream, h: Map<String, String>) {
        if (!storage.canUpload()) return fail(out, 403, "Browser uploads disabled")
        val staged = resumable ?: return fail(out, 501, "Resumable uploads unavailable")
        val length = h["content-length"]?.toLongOrNull() ?: return fail(out, 400, "Chunk Content-Length required")
        val total = h["x-upload-total"]?.toLongOrNull() ?: return fail(out, 400, "Upload total required")
        val offset = h["x-upload-offset"]?.toLongOrNull() ?: return fail(out, 400, "Upload offset required")
        val id = h["x-upload-id"] ?: return fail(out, 400, "Upload ID required")
        val raw = h["x-file-name"] ?: return fail(out, 400, "Missing file name")
        val name = try { URLDecoder.decode(raw, "UTF-8") }
            catch (_: Exception) { return fail(out, 400, "Invalid file name") }
        if (!validName(name)) return fail(out, 400, "Invalid file name")
        val folders = try {
            h["x-relative-path"]?.let {
                UploadPathPolicy.folders(URLDecoder.decode(it, "UTF-8"), name)
            } ?: emptyList()
        } catch (_: Exception) { return fail(out, 400, "Invalid folder path") }
        val digest = h["x-content-sha256"]
        if (digest != null && !digest.matches(Regex("[0-9a-fA-F]{64}")))
            return fail(out, 400, "Invalid SHA-256")
        if (length < 0 || length > ResumableBrowserUpload.MAX_CHUNK ||
            total < 0 || total > minOf(MAX_UPLOAD, storage.maxUploadSize()) ||
            offset < 0 || offset > total || length > total - offset)
            return fail(out, 413, "Invalid upload chunk bounds")
        try {
            val state = staged.append(id, name, folders, total, offset, length, digest, input)
            progress(state.received, state.total)
            response(out, if (state.complete) 201 else 200,
                "{\\"received\\":${state.received},\\"total\\":${state.total},\\"complete\\":${state.complete}}",
                "application/json; charset=utf-8")
        } catch (conflict: ResumableBrowserUpload.OffsetConflict) {
            response(out, 409, "{\\"expectedOffset\\":${conflict.expected}}", "application/json; charset=utf-8")
        } catch (_: UploadHashMismatchException) {
            fail(out, 422, "Staged upload SHA-256 mismatch")
        } catch (_: UploadPermissionRevokedException) {
            fail(out, 403, "Upload consent revoked")
        } catch (_: ResumableBrowserUpload.InvalidSession) {
            fail(out, 409, "Upload session expired or metadata changed")
        } catch (_: UnsupportedOperationException) {
            fail(out, 501, "Folder upload unsupported")
        } catch (_: IllegalArgumentException) {
            fail(out, 400, "Invalid upload request")
        } catch (_: Exception) {
            fail(out, 500, "Resumable upload failed; query status before retrying")
        }
    }

    private fun upload(input: InputStream, out: OutputStream, h: Map<String,String>) {
        if (!storage.canUpload()) return fail(out, 409, "Choose upload folder on Android first")
        if (h["transfer-encoding"] != null) return fail(out, 400, "Chunked uploads not supported")
        val length = h["content-length"]?.toLongOrNull() ?: return fail(out, 400, "Content-Length required")
        if (length < 0 || length > minOf(MAX_UPLOAD, storage.maxUploadSize())) return fail(out, 413, "Unsupported file size")
        val raw = h["x-file-name"] ?: return fail(out, 400, "Missing file name")
        val name = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { return fail(out, 400, "Invalid filename") }
        if (!validName(name)) return fail(out, 400, "Invalid filename")
        // Optional end-to-end digest. Reject malformed claims rather than silently skipping checks.
        val expectedSha256 = h["x-content-sha256"]
        if (expectedSha256 != null && !expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            return fail(out, 400, "Invalid SHA-256")
        }
        val folders = h["x-relative-path"]?.let { rawPath ->
            try {
                UploadPathPolicy.folders(URLDecoder.decode(rawPath, "UTF-8"), name)
            } catch (_: IllegalArgumentException) {
                return fail(out, 400, "Invalid upload folder path")
            }
        }
        try {
            val stored = if (folders == null) {
                storage.upload(name, input, length, expectedSha256) { current -> progress(current, length) }
            } else {
                storage.uploadRelative(folders, name, input, length, expectedSha256) {
                    current -> progress(current, length)
                }
            }
            response(out, 201, "Saved: $stored")
        } catch (_: UnsupportedOperationException) {
            fail(out, 501, "Folder uploads not supported by this storage")
        } catch (_: UploadHashMismatchException) {
            fail(out, 422, "Upload SHA-256 mismatch. File was not committed")
        } catch (_: UploadPermissionRevokedException) {
            fail(out, 403, "Browser upload consent revoked. Partial file was not committed")
        } catch (_: Exception) { fail(out, 500, "Could not save upload") }
    }
    /** Browser-supplied filenames are untrusted even when the upload session is authorized.
     * Reject names that Windows/SAF extractors may alias or display deceptively.
     * Normal Arabic filenames remain supported.
     */
    internal fun validName(v: String): Boolean = UploadPathPolicy.validName(v)

    private fun html(out: OutputStream) = response(out, 200, """
        <!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8" />
        <meta name="viewport" content="width=device-width,initial-scale=1" />
        <title>NetKit • Browser Share</title>
        <style>
        :root { color-scheme:dark; font-family:system-ui,Tahoma,Arial,sans-serif }
        * { box-sizing:border-box }
        body { margin:0; color:#eef8ff; background:#081126; min-height:100vh; overflow-x:hidden }
        body:before { content:""; position:fixed; inset:-20%; z-index:-1; pointer-events:none;
          background:radial-gradient(ellipse at 18% 17%,#7446c877,transparent 38%),
                     radial-gradient(ellipse at 87% 34%,#008caf66,transparent 36%),
                     radial-gradient(ellipse at 58% 95%,#ef559f44,transparent 39%);
          animation:drift 18s ease-in-out infinite alternate }
        @keyframes drift { to {transform:translate(3%, -2%) scale(1.05)} }
        .bubble { position:fixed; border-radius:50%; pointer-events:none; z-index:-1;
          border:1px solid #ffffff71; box-shadow:inset -10px -12px 24px #6f42d05c, inset 8px 9px 24px #8fffff5c, 0 15px 40px #14104c;
          background:radial-gradient(circle at 27% 23%, #ffffff90 0%, #8ee0ff3b 27%, #b369db38 52%, #ffffff00 73%);
          animation:bob 9s ease-in-out infinite alternate }
        .b1 { width:118px;height:118px;left:3%;top:13% } .b2 {width:63px;height:63px;right:5%;top:60%;animation-delay:-4s}
        @keyframes bob { to { transform:translateY(-30px) translateX(12px) } }
        main { max-width:840px;margin:4vh auto;padding:clamp(14px,4vw,25px) }
        header { display:flex;align-items:center;gap:14px;margin:0 0 22px }
        .mark { display:grid;place-items:center;width:58px;height:58px;border-radius:21px;font-size:30px;
          background:linear-gradient(130deg,#45e6eb,#907dff,#ff7bcb);box-shadow:0 8px 35px #8a5bf955 }
        h1 {margin:0;font-size:clamp(29px,6vw,39px);font-weight:800;letter-spacing:-1px}
        .gradient {background:linear-gradient(90deg,#6ff2ec,#c2a2ff,#ffa2d0);color:transparent;background-clip:text}
        h2 {font-size:18px;margin:0 0 9px} p,small {color:#c4d3e7} p {line-height:1.65}
        .glass {border:1px solid #ffffff37;background:linear-gradient(132deg,#ffffff19,#ffffff09);
          backdrop-filter:blur(21px);border-radius:25px;padding:clamp(18px,4vw,26px);margin:15px 0;
          box-shadow:0 20px 58px #010b244f}
        .chip {display:inline-flex;align-items:center;border:1px solid #72ffd252;border-radius:20px;
          padding:6px 11px;color:#9dffe7;font-size:12px;background:#16dd9721}
        .actions {display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}
        button {border:0;border-radius:15px;padding:12px 18px;background:linear-gradient(105deg,#44ecec,#a28aff);color:#06142d;font-weight:750;cursor:pointer;font-size:14px}
        button.secondary {background:#ffffff19;color:#eaf5ff;border:1px solid #ffffff36}
        button:disabled {opacity:.4;cursor:default} input {width:100%;max-width:100%;font-size:14px}
        input::file-selector-button {background:#a28aff;color:#06142d;border:0;border-radius:10px;padding:9px;margin-inline-end:10px}
        ul {list-style:none;padding:0;margin:12px 0 0} li {padding:11px 5px;border-top:1px solid #ffffff1c;overflow-wrap:anywhere}
        a {color:#a9f6ff;text-decoration:none} a:hover {text-decoration:underline}
        .meter {height:17px;overflow:hidden;border-radius:24px;background:#ffffff17;border:1px solid #ffffff28}
        #bar {width:0%;height:100%;border-radius:24px;transition:width .12s ease;
          background:radial-gradient(circle at 25% 35%,#ffffffa8 0 3px,transparent 4px),
                     radial-gradient(circle at 69% 65%,#ffffffb2 0 2px,transparent 3px),
                     linear-gradient(95deg,#43eff2,#8478ff,#ff8cc1);box-shadow:0 0 19px #52cce288}
        #status,#stats {white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}
        footer {font-size:12px;line-height:1.6;margin:20px 4px;color:#a2b5d0}
        @media (prefers-reduced-motion:reduce) {body:before,.bubble {animation:none}#bar {transition:none}}
        </style></head><body><div class="bubble b1"></div><div class="bubble b2"></div>
        <main>
          <header><div class="mark">🫧</div><div><h1 class="gradient">NetKit</h1><small>YOUR NETWORK, YOUR TOOLS</small></div></header>
          <span class="chip">● Local · HTTPS Session</span>
          <div class="glass"><h2>📁 تصفّح المجلد المشترك</h2><p>يعرض فقط المجلد أو مساحة التخزين المشتركة التي سمح صاحب الجهاز بمشاركتها. التصفح للقراءة والتنزيل، والمجلدات الفرعية متاحة.</p>
            <div class="actions"><button class="secondary" id="folderBack" disabled>↩ الرجوع</button><span id="folderPath" class="chip">المجلد الرئيسي</span></div>
            <ul id="folders">جارٍ قراءة المجلد…</ul><div class="actions"><button class="secondary" id="folderMore" hidden>عرض المزيد</button></div></div>
          <div class="glass"><h2>📥 ملفات محددة</h2><p>الملفات الفردية المختارة من جهاز Android. التحميل يدعم HTTP Range عندما يكون الحجم معروفًا.</p><ul id="files">جارٍ القراءة…</ul></div>
          <div class="glass"><h2>📤 إرسال ملفات للجهاز</h2><p>اختَر ملفات من اللابتوب لإرسالها إلى المجلد المحدد على Android. يتم التحقق من SHA-256 للملفات حتى 16 MiB عندما يدعم المتصفح Web Crypto؛ الملفات الأكبر تُنقل دون مقارنة تجزئة طرفية حاليًا.</p>
            <input id="choose" type="file" multiple />
            <p>أو اختر مجلدًا كاملًا (متصفح يدعم اختيار المجلدات):</p>
            <input id="chooseFolder" type="file" webkitdirectory multiple />
            <div class="actions"><button id="upload">ابدأ الإرسال</button><button class="secondary" id="cancel" disabled>إلغاء النقل</button></div>
            <div class="meter" role="progressbar" aria-label="Transfer progress"><div id="bar"></div></div>
            <p id="stats">0%</p><p id="status" role="status"></p>
          </div>
          <footer>🔐 هذه الجلسة على شبكتك المحلية فقط. أوقف المشاركة من تطبيق Android بعد الانتهاء، ولا تشارك الرابط مع أشخاص غير موثوقين.</footer>
        </main><script>
        const base=location.pathname.replace(/\/$/,'');
        const el=id=>document.getElementById(id);
        const humanBytes=n=>n>=1048576?(n/1048576).toFixed(1)+' MB':n>=1024?(n/1024).toFixed(1)+' KB':n+' B';
        let activeRequest=null, receivingAllowed=false;
        const foldersState={dir:'root',offset:0,stack:[]};
        function folderLink(folderId) {
          foldersState.stack.push(foldersState.dir);
          foldersState.dir=folderId;
          el('folderBack').disabled=false;
          loadFolder(true);
        }
        async function loadFolder(reset=false) {
          if(reset){foldersState.offset=0;el('folders').replaceChildren();}
          el('folderPath').textContent=foldersState.dir==='root'?'المجلد الرئيسي':'مجلد فرعي';
          const url=base+'/browse?dir='+encodeURIComponent(foldersState.dir)+'&offset='+foldersState.offset;
          const response=await fetch(url,{cache:'no-store'});
          if(!response.ok)throw Error('تعذّر عرض المجلد');
          const page=await response.json();
          if(!page.enabled){el('folders').textContent='فعّل مشاركة مجلد من جهاز Android لعرض محتوياته.';el('folderMore').hidden=true;return}
          for(const entry of page.entries) {
            const li=document.createElement('li');
            if(entry.directory) {
              const b=document.createElement('button');b.className='secondary';
              b.textContent='📁 '+entry.name;b.onclick=()=>folderLink(entry.id);li.append(b);
              const zip=document.createElement('a');zip.href=base+'/browse-archive/'+encodeURIComponent(entry.id);zip.textContent=' ⬇ ZIP';zip.style.marginInlineStart='14px';li.append(zip);
            } else {
              const a=document.createElement('a');a.textContent='📄 '+entry.name+' · '+(entry.length==null?'حجم غير معروف':humanBytes(entry.length));
              a.href=base+'/browse-file/'+encodeURIComponent(entry.id);li.append(a);
              const checksum=document.createElement('a');checksum.href=base+'/browse-checksum/'+encodeURIComponent(entry.id);
              checksum.textContent=' · SHA-256';checksum.target='_blank';checksum.rel='noopener';li.append(checksum);
            }
            el('folders').append(li);
          }
          if(reset && page.entries.length===0)el('folders').textContent='المجلد فارغ.';
          foldersState.offset=page.nextOffset??0;
          el('folderMore').hidden=page.nextOffset===null;
        }
        el('folderMore').onclick=()=>loadFolder(false).catch(e=>{el('folders').textContent=e.message});
        el('folderBack').onclick=()=> {
          if(!foldersState.stack.length)return;
          foldersState.dir=foldersState.stack.pop();
          el('folderBack').disabled=!foldersState.stack.length;
          loadFolder(true).catch(e=>{el('folders').textContent=e.message});
        };
        function resetButtons(busy) {el('upload').disabled=busy||!receivingAllowed;el('cancel').disabled=!busy}
        async function refresh() {
          const r=await fetch(base+'/list',{cache:'no-store'});if(!r.ok)throw Error('Server error');
          const data=await r.json();receivingAllowed=Boolean(data.uploadAllowed);resetButtons(false);
          el('files').replaceChildren();
          for(const f of data.items) {
            const li=document.createElement('li'),a=document.createElement('a');
            a.textContent='📄 '+f.name+' · '+(f.length==null?'حجم غير معروف':humanBytes(f.length));
            a.href=base+'/file/'+encodeURIComponent(f.id);li.append(a);
            const checksum=document.createElement('a');checksum.href=base+'/checksum/'+encodeURIComponent(f.id);
            checksum.textContent=' · SHA-256';checksum.target='_blank';checksum.rel='noopener';li.append(checksum);el('files').append(li);
          }
          if(!data.items.length)el('files').textContent='ما في ملفات محددة للمشاركة حاليًا.';
          if(!receivingAllowed)el('status').textContent='اختَر مجلد استقبال من تطبيق Android أولًا.';
        }
        async function sha256SmallFile(file) {
          // Bound additional browser memory. Large files MUST remain stream-uploaded via XHR.
          if(file.size>16*1024*1024 || !globalThis.crypto?.subtle)return null;
          try {
            const digest=await crypto.subtle.digest('SHA-256',await file.arrayBuffer());
            return Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
          } catch (_) {return null}
        }
        async function sendFile(file, index, count) {
          el('status').textContent='فحص سلامة '+file.name+'…';
          const hash=await sha256SmallFile(file);
          return new Promise((resolve,reject)=>{
          const xhr=new XMLHttpRequest();activeRequest=xhr;
          const start=performance.now();
          xhr.open('POST',base+'/upload');
          xhr.setRequestHeader('X-File-Name',encodeURIComponent(file.name));
          if(file.webkitRelativePath) xhr.setRequestHeader('X-Relative-Path',encodeURIComponent(file.webkitRelativePath));
          if(hash)xhr.setRequestHeader('X-Content-SHA256',hash);
          xhr.setRequestHeader('Content-Type','application/octet-stream');
          xhr.upload.onprogress=e=>{
            if(!e.lengthComputable)return;
            const pct=Math.min(100,Math.round((index+e.loaded/Math.max(1,e.total))/count*100));
            el('bar').style.width=pct+'%';
            const elapsed=Math.max(.1,(performance.now()-start)/1000);
            const speed=e.loaded/elapsed;
            const eta=speed>0?(e.total-e.loaded)/speed:0;
            el('stats').textContent=pct+'% · '+humanBytes(e.loaded)+' / '+humanBytes(e.total)+' · '+humanBytes(speed)+'/s · '+Math.ceil(eta)+'s remaining';
          };
          xhr.onload=()=>{activeRequest=null;xhr.status===201?resolve(Boolean(hash)):reject(Error('HTTP '+xhr.status+': '+xhr.responseText))};
          xhr.onerror=()=>{activeRequest=null;reject(Error('Network disconnected'))};
          xhr.onabort=()=>{activeRequest=null;reject(Error('Transfer cancelled'))};
          xhr.send(file);
        })}
        el('cancel').onclick=()=>{if(activeRequest)activeRequest.abort()};
        el('upload').onclick=async()=>{
          const files=[...Array.from(el('choose').files),...Array.from(el('chooseFolder').files)];if(!files.length)return;
          resetButtons(true);el('bar').style.width='0%';
          try {let verified=0;
          for(let i=0;i<files.length;i++) {
            el('status').textContent='إرسال '+files[i].name+' ('+(i+1)+'/'+files.length+')';
            if(await sendFile(files[i],i,files.length))verified++;
          }
          el('bar').style.width='100%';el('stats').textContent='100% · اكتمل الإرسال';
          el('status').textContent='✅ الملفات وصلت بنجاح · '+verified+'/'+files.length+' تحقق SHA-256';el('choose').value='';el('chooseFolder').value='';
          await refresh();
          } catch(e) {el('status').textContent='⚠️ '+e.message}
          finally {activeRequest=null;resetButtons(false)}
        };
        refresh().catch(e=>{el('files').textContent='تعذّر قراءة الملفات: '+e.message;resetButtons(false)});
        loadFolder(true).catch(e=>{el('folders').textContent='تعذّر قراءة المجلد: '+e.message});
        </script></body></html>
    """.trimIndent(), "text/html; charset=utf-8")
}
