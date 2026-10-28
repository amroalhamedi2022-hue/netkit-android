package com.netkit.local.share

import java.io.InputStream
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
        maximumBytes: Long = 64L * 1024 * 1024 * 1024,
        checkActive: () -> Unit = {}
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
            require(segment.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 32 })
            return segment
        }

        fun walk(folderId: String, prefix: String, depth: Int) {
            checkActive()
            require(depth <= 32) { "Directory nesting exceeds safe depth" }
            check(visited.add(folderId)) { "Recursive directory provider cycle" }
            var offset = 0
            do {
                checkActive()
                val page = source.list(folderId, offset, 100)
                checkActive()
                require(page.files.size <= 100)
                for (child in page.files) {
                    checkActive()
                    entries++
                    check(entries <= maximumEntries) { "Too many archive entries" }
                    val segment = safeSegment(child.name)
                    val path = prefix + segment + if (child.folder) "/" else ""
                    require(path.length <= 2048 && paths.add(path)) { "Duplicate or too-long archive path" }
                    if (child.folder) {
                        zip.putNextEntry(ZipEntry(path))
                        zip.closeEntry()
                        walk(child.id, path, depth + 1)
                    } else {
                        checkActive()
                        val input = source.open(child.id) ?: error("Shared file became unreadable")
                        input.use {
                            checkActive()
                            zip.putNextEntry(ZipEntry(path))
                            while (true) {
                                checkActive()
                                val read = it.read(buffer)
                                checkActive()
                                if (read < 0) break
                                if (read == 0) continue
                                bytes += read
                                check(bytes <= maximumBytes) { "Archive byte budget exceeded" }
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
        checkActive()
        return ArchiveResult(entries, bytes)
    }
}