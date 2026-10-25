package com.netkit.local.share

import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ArchiveFile(val id: String, val name: String, val folder: Boolean)
data class ArchivePage(val files: List<ArchiveFile>, val nextOffset: Int?)

interface ArchiveDirectorySource {
    fun list(folderId: String, offset: Int, limit: Int = 100): ArchivePage
    fun open(fileId: String): InputStream?
}

data class ArchiveResult(val entries: Int, val bytes: Long)

/**
 * Streams an authorized folder tree as ZIP without making a temporary copy on Android.
 * The caller must enforce HTTPS authorization and scope ArchiveDirectorySource to its SAF root.
 */
object DirectoryArchive {
    private fun ensureNotCancelled() {
        // shutdownNow() interrupts a worker when the HTTPS share is stopped.
        // SAF streams may still be readable: do not continue exporting a ZIP
        // after explicit cancellation just because the provider stays open.
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Folder ZIP export cancelled")
        }
    }

    fun write(
        rootId: String,
        source: ArchiveDirectorySource,
        zip: ZipOutputStream,
        maximumEntries: Int = 20_000,
        maximumBytes: Long = 64L * 1024 * 1024 * 1024,
        includeChecksums: Boolean = false,
        checkActive: () -> Unit = {}
    ): ArchiveResult {
        require(maximumEntries in 1..100_000)
        require(maximumBytes > 0)
        val visited = HashSet<String>()
        val paths = HashSet<String>()
        // Optional checksum sidecar is reserved before visiting any untrusted SAF name.
        // It detects accidental transfer/extraction corruption, not server authenticity.
        val checksumLines = StringBuilder()
        val checksumName = "NetKit-SHA256SUMS.txt"
        if (includeChecksums) paths.add(checksumName.lowercase(Locale.ROOT))
        var entries = 0
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        fun ensureActive() {
            ensureNotCancelled()
            checkActive()
        }

        fun safeSegment(segment: String): String {
            require(segment.isNotBlank() && segment != "." && segment != "..") { "Unsafe archive filename" }
            // Java Strings may contain isolated UTF-16 surrogates from a broken SAF
            // provider. ZIP UTF-8 encoders replace them, potentially making two
            // distinct document names alias after extraction. Keep valid emoji pairs.
            var index = 0
            while (index < segment.length) {
                val c = segment[index]
                if (Character.isHighSurrogate(c)) {
                    require(index + 1 < segment.length &&
                        Character.isLowSurrogate(segment[index + 1])) { "Malformed Unicode filename" }
                    index += 2
                } else {
                    require(!Character.isLowSurrogate(c)) { "Malformed Unicode filename" }
                    index++
                }
            }
            // Replacement characters and format controls can make two distinct SAF
            // names look identical, or let ZIP extractors display misleading paths.
            // Reject them consistently with browser uploads; Arabic text and valid
            // supplementary-plane characters (including emoji) remain supported.
            require(segment.none {
                it == '\uFFFD' || it == '\u061C' || it == '\uFEFF' ||
                    it.code in 0x2028..0x2029 || it.code in 0xFFF9..0xFFFB
            }) { "Unsafe archive filename Unicode" }
            require(segment.length <= 240 && segment.toByteArray(Charsets.UTF_8).size <= 768)
            // SAF document names are untrusted. Reject control characters and bidi
            // overrides that can disguise a file extension in a downloaded ZIP.
            // Normal Arabic/Hebrew letters remain valid.
            require(segment.none {
                it == '/' || it == '\\' || it.code < 32 ||
                    it.code in 0x7f..0x9f || it in "<>:\"|?*" ||
                    it.code == 0x061c || it.code in 0x200e..0x200f ||
                    it.code in 0x202a..0x202e || it.code in 0x2066..0x2069
            }) { "Unsafe archive filename controls" }
            require(!segment.endsWith('.') && !segment.endsWith(' ')) { "Unsafe Windows archive filename" }
            val stem = segment.substringBefore('.').uppercase(Locale.ROOT)
            val reserved = stem in setOf("CON", "PRN", "AUX", "NUL") ||
                Regex("^(COM|LPT)[1-9¹²³]$").matches(stem)
            require(!reserved) { "Reserved Windows archive filename" }
            return segment
        }

        fun canonicalPath(path: String): String =
            Normalizer.normalize(path.removeSuffix("/"), Normalizer.Form.NFC).lowercase(Locale.ROOT)

        fun walk(folderId: String, prefix: String, depth: Int) {
            ensureActive()
            require(depth <= 32) { "Directory nesting exceeds safe depth" }
            check(visited.add(folderId)) { "Recursive directory provider cycle" }
            var offset = 0
            do {
                ensureActive()
                val page = source.list(folderId, offset, 100)
                require(page.files.size <= 100)
                // A broken SAF provider must not advertise endless empty pages.
                // This also prevents a zero-content request from monopolizing HTTPS.
                check(page.files.isNotEmpty() || page.nextOffset == null) {
                    "Empty archive pagination page"
                }
                for (child in page.files) {
                    ensureActive()
                    entries++
                    check(entries <= maximumEntries) { "Too many archive entries" }
                    val segment = safeSegment(child.name)
                    val path = prefix + segment + if (child.folder) "/" else ""
                    // ZIP may be extracted on case-insensitive Windows filesystems. Reject
                    // aliases (including file-vs-directory and Unicode normalization) rather
                    // than silently overwrite an earlier entry when unzipped.
                    require(path.length <= 2048 && paths.add(canonicalPath(path))) {
                        "Colliding or too-long archive path"
                    }
                    if (child.folder) {
                        zip.putNextEntry(ZipEntry(path))
                        zip.closeEntry()
                        walk(child.id, path, depth + 1)
                    } else {
                        val input = source.open(child.id) ?: error("Shared file became unreadable")
                        val digest = if (includeChecksums) MessageDigest.getInstance("SHA-256") else null
                        input.use {
                            zip.putNextEntry(ZipEntry(path))
                            var consecutiveEmptyReads = 0
                            while (true) {
                                ensureActive()
                                val read = it.read(buffer)
                                ensureActive() // Provider reads may outlive the HTTPS lease.
                                if (read < 0) break
                                if (read == 0) {
                                    // Some SAF providers return a transient zero; a broken
                                    // provider must not spin forever and monopolize a server worker.
                                    check(++consecutiveEmptyReads <= 32) { "Shared file read stalled" }
                                    Thread.yield()
                                    continue
                                }
                                consecutiveEmptyReads = 0
                                check(read.toLong() <= maximumBytes - bytes) { "Archive byte budget exceeded" }
                                bytes += read
                                digest?.update(buffer, 0, read)
                                zip.write(buffer, 0, read)
                            }
                            zip.closeEntry()
                        }
                        if (digest != null) {
                            val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                            checksumLines.append(hex).append("  ").append(path).append('\n')
                        }
                    }
                }
                val next = page.nextOffset
                if (next != null) check(next > offset) { "Invalid archive pagination loop" }
                offset = next ?: -1
            } while (offset >= 0)
        }

        walk(rootId, "", 0)
        ensureActive()
        if (includeChecksums) {
            val manifest = checksumLines.toString().toByteArray(Charsets.UTF_8)
            check(manifest.size <= 64 * 1024 * 1024) { "Archive checksum manifest too large" }
            check(manifest.size.toLong() <= maximumBytes - bytes) { "Archive byte budget exceeded" }
            ensureActive()
            zip.putNextEntry(ZipEntry(checksumName))
            zip.write(manifest)
            zip.closeEntry()
            bytes += manifest.size
        }
        // Entries count includes only original SAF nodes; the optional sidecar is extra.
        return ArchiveResult(entries, bytes)
    }
}