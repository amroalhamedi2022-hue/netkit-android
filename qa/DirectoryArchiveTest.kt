import com.netkit.local.share.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun main() {
    val expected = mutableMapOf<String, ByteArray>()
    val storage = object : ArchiveDirectorySource {
        override fun list(folderId: String, offset: Int, limit: Int): ArchivePage {
            val all = when (folderId) {
                "root" -> (0..120).map { ArchiveFile("f$it", "img-$it.png", false) } +
                    ArchiveFile("child", "nested", true)
                "child" -> listOf(ArchiveFile("deep", "letter.txt", false), ArchiveFile("empty","empty",true))
                "empty" -> emptyList()
                else -> error("Not shared")
            }
            return ArchivePage(all.drop(offset).take(limit), (offset+limit).takeIf { it < all.size })
        }
        override fun open(fileId: String): InputStream? {
            val data = if (fileId == "deep") "Nested 🫧".toByteArray()
                else ("Content-$fileId").toByteArray()
            expected[fileId] = data
            return ByteArrayInputStream(data)
        }
    }
    val buffer = ByteArrayOutputStream()
    val result: ArchiveResult
    ZipOutputStream(buffer).use { result = DirectoryArchive.write("root", storage, it) }
    check(result.entries == 124) // 121 files + folder + nested file + empty folder
    val read = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(buffer.toByteArray())).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            check(entry.name.split('/').none { it == ".." })
            read[entry.name] = zip.readBytes()
            zip.closeEntry()
        }
    }
    check(read["nested/letter.txt"]!!.contentEquals("Nested 🫧".toByteArray()))
    check(read["nested/empty/"]!!.isEmpty())
    check(read.size == result.entries)
    check(read["img-120.png"]!!.contentEquals("Content-f120".toByteArray()))
    run {
        val malicious = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("evil", "../secrets.txt", false)), null)
            override fun open(fileId: String) = ByteArrayInputStream(byteArrayOf(1))
        }
        val error = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use { DirectoryArchive.write("root", malicious, it) }
        }.exceptionOrNull()
        check(error is IllegalArgumentException)
    }
    run {
        val error = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use {
                DirectoryArchive.write("root", storage, it, maximumEntries=4)
            }
        }.exceptionOrNull()
        check(error is IllegalStateException)
    }
    println("PASS: paged streaming ZIP, nested+empty folders, path rejection, quotas")
}