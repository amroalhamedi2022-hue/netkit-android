package com.netkit.local.share

import java.io.InputStream
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
    fun write(
        rootId: String,
        source: ArchiveDirectorySource,
        zip: ZipOutputStream,
        maximumEntries: Int = 20_000,
        maximumBytes: Long = 64L * 1024 * 1024 * 1024
    ): ArchiveResult {
        require(maximumEntries in 1..100_000)
        require(maximumBytes > 0)
        val visited = HashSet<String>()
        val paths = HashSet<String>()
        var entries = 0
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)

        fun safeSegment(segment: String): String {
            require(segment.isNotBlank() && segment != "." && segment != "..") { "Unsafe archive filename" }
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
            require(depth <= 32) { "Directory nesting exceeds safe depth" }
            check(visited.add(folderId)) { "Recursive directory provider cycle" }
            var offset = 0
            do {
                val page = source.list(folderId, offset, 100)
                require(page.files.size <= 100)
                // A broken SAF provider must not advertise endless empty pages.
                // This also prevents a zero-content request from monopolizing HTTPS.
                check(page.files.isNotEmpty() || page.nextOffset == null) {
                    "Empty archive pagination page"
                }
                for (child in page.files) {
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
                        input.use {
                            zip.putNextEntry(ZipEntry(path))
                            var consecutiveEmptyReads = 0
                            while (true) {
                                val read = it.read(buffer)
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
                                zip.write(buffer, 0, read)
                            }
                            zip.closeEntry()
                        }
                    }
                }
                val next = page.nextOffset
                if (next != null) check(next > offset) { "Invalid archive pagination loop" }
                offset = next ?: -1
            } while (offset >= 0)
        }

        walk(rootId, "", 0)
        return ArchiveResult(entries, bytes)
    }
}