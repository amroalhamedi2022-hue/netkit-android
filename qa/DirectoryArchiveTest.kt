import com.netkit.local.share.*
// Device-acceptance scope: recursive SAF ZIP and checksum-manifest budget on Xiaomi Pad 5.
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
        reject(listOf("name\uFFFD.txt" to false)) // replacement character from malformed provider
        reject(listOf("name\uFEFF.txt" to false)) // invisible byte-order mark
        reject(listOf("name\u2028.txt" to false)) // Unicode line separator
        reject(listOf("name\u2029.txt" to false)) // Unicode paragraph separator
        reject(listOf("name\uFFF9.txt" to false)) // interlinear annotation control
        reject(listOf("Report.txt" to false, "report.TXT" to false))
        reject(listOf("Cafe\u0301.txt" to false, "Café.txt" to false))
        reject(listOf("folder" to true, "FOLDER" to false))
        // Isolated surrogates silently become replacement characters in UTF-8 ZIP
        // names. Reject them, but allow correctly paired emoji in Arabic names.
        reject(listOf("broken\uD800.txt" to false))
        reject(listOf("broken\uDC00.txt" to false))
        val unicodeSource = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("safe", "ملف آمن.txt", false),
                    ArchiveFile("emoji", "📁 ملف.txt", false)), null)
            override fun open(fileId: String) = ByteArrayInputStream(byteArrayOf(7))
        }
        val safeZip = ByteArrayOutputStream()
        ZipOutputStream(safeZip).use { DirectoryArchive.write("root", unicodeSource, it) }
        ZipInputStream(ByteArrayInputStream(safeZip.toByteArray())).use {
            check(it.nextEntry?.name == "ملف آمن.txt")
            check(it.readBytes().contentEquals(byteArrayOf(7)))
            check(it.nextEntry?.name == "📁 ملف.txt")
            check(it.readBytes().contentEquals(byteArrayOf(7)))
        }
    }
    run {
        // SAF open() may return only after the share lease has expired. Do not
        // emit a ZIP entry header or any file bytes after that boundary.
        var active = true
        val expiredOnOpen = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("late", "late.bin", false)), null)
            override fun open(fileId: String): InputStream {
                active = false
                return ByteArrayInputStream(byteArrayOf(9, 8, 7))
            }
        }
        val bytes = ByteArrayOutputStream()
        val failure = runCatching {
            ZipOutputStream(bytes).use {
                DirectoryArchive.write("root", expiredOnOpen, it, checkActive = {
                    check(active) { "Share lease expired during SAF open" }
                })
            }
        }.exceptionOrNull()
        check(failure is IllegalStateException && failure.message == "Share lease expired during SAF open")
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            check(zip.nextEntry == null) { "Expired SAF open emitted a ZIP entry" }
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

    run {
        // Browser ZIP exports carry a streamed SHA-256 manifest without buffering
        // entire SAF files, including UTF-8 nested filenames.
        val payloads = mapOf("a" to "ABC".toByteArray(), "b" to byteArrayOf(0, 1, 2, 3))
        val source = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int): ArchivePage =
                ArchivePage(when (folderId) {
                    "root" -> listOf(ArchiveFile("a", "alpha.bin", false),
                        ArchiveFile("folder", "مجلد", true))
                    "folder" -> listOf(ArchiveFile("b", "بيانات.bin", false))
                    else -> error("Unexpected folder")
                }, null)
            override fun open(fileId: String): InputStream? =
                payloads[fileId]?.let(::ByteArrayInputStream)
        }
        val bytes = ByteArrayOutputStream()
        val info: ArchiveResult
        ZipOutputStream(bytes).use { info = DirectoryArchive.write(
            "root", source, it, includeChecksums = true) }
        val unzipped = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                unzipped[item.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        check(info.entries == 3 && unzipped.size == 4)
        check(unzipped["alpha.bin"]!!.contentEquals(payloads["a"]!!))
        check(unzipped["مجلد/بيانات.bin"]!!.contentEquals(payloads["b"]!!))
        fun hash(data: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        val expectedManifest = hash(payloads["a"]!!) + "  alpha.bin\n" +
            hash(payloads["b"]!!) + "  مجلد/بيانات.bin\n"
        check(unzipped["NetKit-SHA256SUMS.txt"]!!.toString(Charsets.UTF_8) == expectedManifest)
        check(info.bytes == 7L + expectedManifest.toByteArray().size)
        val quotaFailure = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use {
                DirectoryArchive.write("root", source, it, maximumBytes = 7,
                    includeChecksums = true)
            }
        }.exceptionOrNull()
        check(quotaFailure is IllegalStateException &&
            quotaFailure.message == "Archive byte budget exceeded")
        val collision = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("a", "netkit-sha256sums.TXT", false)), null)
            override fun open(fileId: String) = ByteArrayInputStream(byteArrayOf(1))
        }
        check(runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use {
                DirectoryArchive.write("root", collision, it, includeChecksums = true)
            }
        }.exceptionOrNull() is IllegalArgumentException)
    }
    run {
        // Stopping the HTTPS server interrupts its active worker. A provider
        // must not be queried once that worker has already been cancelled.
        var listings = 0
        val source = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int): ArchivePage {
                listings++
                return ArchivePage(emptyList(), null)
            }
            override fun open(fileId: String): InputStream? = null
        }
        Thread.currentThread().interrupt()
        val failure = try {
            runCatching {
                ZipOutputStream(ByteArrayOutputStream()).use {
                    DirectoryArchive.write("root", source, it)
                }
            }.exceptionOrNull()
        } finally {
            // Clear the test thread's interrupt bit before subsequent cases.
            Thread.interrupted()
        }
        check(failure is java.io.InterruptedIOException &&
            failure.message == "Folder ZIP export cancelled")
        check(listings == 0) { "Cancelled ZIP still enumerated SAF files" }
    }
    run {
        // An interrupted worker must stop after a successful partial read;
        // a still-readable SAF stream is not permission to keep exporting.
        var reads = 0
        val source = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("item", "large.bin", false)), null)
            override fun open(fileId: String): InputStream = object : InputStream() {
                override fun read(): Int = error("Unexpected single-byte read")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reads++
                    check(reads == 1) { "Cancelled ZIP continued reading" }
                    b[off] = 42
                    Thread.currentThread().interrupt()
                    return 1
                }
            }
        }
        val failure = try {
            runCatching {
                ZipOutputStream(ByteArrayOutputStream()).use {
                    DirectoryArchive.write("root", source, it)
                }
            }.exceptionOrNull()
        } finally {
            Thread.interrupted()
        }
        check(failure is java.io.InterruptedIOException &&
            failure.message == "Folder ZIP export cancelled")
        check(reads == 1)
    }
    run {
        // M27: HTTPS lease expiry must interrupt a ZIP while a SAF provider
        // is readable; no extra file bytes or successful checksum manifest.
        var active = true
        var reads = 0
        val source = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("leased", "leased.bin", false)), null)
            override fun open(fileId: String): InputStream = object : InputStream() {
                override fun read(): Int = error("Unexpected single-byte read")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reads++
                    check(reads == 1) { "ZIP continued reading after lease expiry" }
                    b[off] = 42
                    active = false // Lease expires while the provider is reading.
                    return 1
                }
            }
        }
        val error = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use {
                DirectoryArchive.write("root", source, it, includeChecksums = true,
                    checkActive = {
                        if (!active) throw java.io.InterruptedIOException("HTTPS sharing session ended")
                    })
            }
        }.exceptionOrNull()
        check(error is java.io.InterruptedIOException &&
            error.message == "HTTPS sharing session ended")
        check(reads == 1) { "Archive did not stop at the revoked lease boundary" }
    }
    run {
        // Checksum lines are budgeted incrementally, before a huge manifest can accumulate in RAM.
        val source = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("f", "long-name.txt", false)), null)
            override fun open(fileId: String): InputStream = ByteArrayInputStream(byteArrayOf(1))
        }
        val error = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use {
                DirectoryArchive.write("root", source, it, includeChecksums = true,
                    maximumChecksumManifestBytes = 64)
            }
        }.exceptionOrNull()
        check(error is IllegalStateException && error.message == "Archive checksum manifest too large")
    }
    run {
        // A faulty SAF provider must not repeat the same page forever.
        val source = object : ArchiveDirectorySource {
            override fun list(folderId: String, offset: Int, limit: Int) =
                ArchivePage(listOf(ArchiveFile("f", "one.txt", false)), offset)
            override fun open(fileId: String): InputStream = ByteArrayInputStream(byteArrayOf(1))
        }
        val error = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use {
                DirectoryArchive.write("root", source, it)
            }
        }.exceptionOrNull()
        check(error is IllegalStateException && error.message == "Invalid archive pagination loop")
    }
    println("PASS: paged ZIP, nested folders, path/quotas, incremental checksum budget, invalid pagination, transient zero-read recovery, Windows-safe names/collisions and cancellation")
}