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
    run {
        // A SAF provider can occasionally return zero without EOF; recover and
        // preserve exact bytes instead of treating the upload as finished.
        val flaky = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("flaky", "flaky.bin", false)), null)
            override fun open(fileId: String): InputStream = object : InputStream() {
                private val bytes = byteArrayOf(1, 2, 3)
                private var index = 0
                private var zeros = 2
                override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (zeros-- > 0) return 0
                    if (index == bytes.size) return -1
                    val n = minOf(len, bytes.size - index)
                    bytes.copyInto(b, off, index, index + n)
                    index += n
                    return n
                }
            }
        }
        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { DirectoryArchive.write("root", flaky, it) }
        ZipInputStream(ByteArrayInputStream(zipBytes.toByteArray())).use {
            check(it.nextEntry?.name == "flaky.bin")
            check(it.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        }
    }
    run {
        // A permanently stalled provider must not loop indefinitely or block
        // the HTTPS worker forever while exporting a shared folder.
        val stalled = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("stalled", "stalled.bin", false)), null)
            override fun open(fileId: String): InputStream = object : InputStream() {
                override fun read(): Int = -1
                override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            }
        }
        val error = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use { DirectoryArchive.write("root", stalled, it) }
        }.exceptionOrNull()
        check(error is IllegalStateException && error.message == "Shared file read stalled")
    }
    run {
        // Windows extractors may alias case-only names, Unicode NFC variants,
        // device filenames, trailing dots and file/folder collisions.
        fun reject(names: List<Pair<String, Boolean>>) {
            val source = object : ArchiveDirectorySource {
                override fun list(folderId: String, offset: Int, limit: Int): ArchivePage =
                    ArchivePage(if (folderId == "root") names.mapIndexed { i, item ->
                        ArchiveFile("item$i", item.first, item.second)
                    } else emptyList(), null)
                override fun open(fileId: String) = ByteArrayInputStream(byteArrayOf(42))
            }
            val failure = runCatching {
                ZipOutputStream(ByteArrayOutputStream()).use { DirectoryArchive.write("root", source, it) }
            }.exceptionOrNull()
            check(failure is IllegalArgumentException) { "Unsafe ZIP entry accepted: $names ($failure)" }
        }
        reject(listOf("NUL.txt" to false))
        reject(listOf("COM¹.log" to false))
        reject(listOf("bad:name.txt" to false))
        reject(listOf("bad." to false))
        reject(listOf("bad " to false))
        reject(listOf("invoice\u202Egpj.exe" to false)) // bidi right-to-left override
        reject(listOf("safe\u2066name.txt" to false)) // bidi isolate
        reject(listOf("name\u007F.txt" to false)) // DEL control
        reject(listOf("name\u0085.txt" to false)) // C1 control
        reject(listOf("Report.txt" to false, "report.TXT" to false))
        reject(listOf("Cafe\u0301.txt" to false, "Café.txt" to false))
        reject(listOf("folder" to true, "FOLDER" to false))
        val unicodeSource = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("safe", "ملف آمن.txt", false)), null)
            override fun open(fileId: String) = ByteArrayInputStream(byteArrayOf(7))
        }
        val safeZip = ByteArrayOutputStream()
        ZipOutputStream(safeZip).use { DirectoryArchive.write("root", unicodeSource, it) }
        ZipInputStream(ByteArrayInputStream(safeZip.toByteArray())).use {
            check(it.nextEntry?.name == "ملف آمن.txt")
            check(it.readBytes().contentEquals(byteArrayOf(7)))
        }
    }
    run {
        // A malicious/broken provider can report infinitely many empty pages
        // with increasing offsets; reject immediately instead of looping.
        var calls = 0
        val emptyPages = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int): ArchivePage {
                calls++
                return ArchivePage(emptyList(), offset + 100)
            }
            override fun open(fileId: String) = null
        }
        val error = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use {
                DirectoryArchive.write("root", emptyPages, it)
            }
        }.exceptionOrNull()
        check(error is IllegalStateException && error.message == "Empty archive pagination page")
        check(calls == 1) { "Unbounded empty-page traversal: $calls" }
    }
    println("PASS: paged ZIP, nested folders, path/quotas, transient zero-read recovery and stalled-provider rejection, Windows-safe names/collisions")
}