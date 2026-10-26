import com.netkit.local.remote.VerifiedDownloadStaging
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

private fun fail(block: () -> Unit) = check(runCatching(block).isFailure)
fun main() {
    val dir = Files.createTempDirectory("netkit-verified-download").toFile()
    try {
        val bytes = ByteArray(170_033) { (it * 31 % 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val part = File(dir, "test.part")
        var latest = -1L
        // A real interrupted network stream writes a partial and leaves it for retry.
        fail {
            VerifiedDownloadStaging.stage(object : ByteArrayInputStream(bytes) {
                var consumed = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (consumed >= 73_000) throw IOException("disconnected")
                    val n = super.read(b, off, minOf(len, 73_000 - consumed))
                    if (n > 0) consumed += n
                    return n
                }
            }, part, bytes.size.toLong())
        }
        check(part.length() == 73_000L)
        val result = VerifiedDownloadStaging.stage(ByteArrayInputStream(bytes), part, bytes.size.toLong()) { latest = it }
        check(latest == bytes.size.toLong())
        check(MessageDigest.isEqual(result, digest))
        VerifiedDownloadStaging.verifyReadback(ByteArrayInputStream(bytes), bytes.size.toLong(), result)
        val published = ByteArrayOutputStream()
        VerifiedDownloadStaging.publish(part, result, { published }, { ByteArrayInputStream(published.toByteArray()) }, rollbackDestination = { error("rollback must not run after verified publish") })
        check(!part.exists() && published.toByteArray().contentEquals(bytes))
        // A tampered private stage must be rejected before the SAF destination is opened.
        val tamperedStage = File(dir, "tampered-before-publish.part").apply {
            writeBytes(bytes.clone().also { it[0] = (it[0].toInt() xor 1).toByte() })
        }
        val untouchedDestination = ByteArrayOutputStream()
        var readbackOpened = false
        var tamperRollbackCalled = false
        val tamperFailure = runCatching {
            VerifiedDownloadStaging.publish(tamperedStage, digest, { untouchedDestination }, {
                readbackOpened = true
                ByteArrayInputStream(untouchedDestination.toByteArray())
            }, rollbackDestination = { tamperRollbackCalled = true })
        }.exceptionOrNull()
        check(tamperFailure is com.netkit.local.remote.RemoteContentChangedException)
        check(untouchedDestination.size() == 0)
        check(!readbackOpened)
        check(!tamperRollbackCalled) // Preflight rejects the stage before touching SAF.
        check(tamperedStage.isFile) // Keep evidence for retry/recovery.
        // A provider may create a new document before failing to open its output stream.
        val failedOpenStage = File(dir, "failed-open.part").apply { writeBytes(bytes) }
        var failedOpenRolledBack = false
        val failedOpen = runCatching {
            VerifiedDownloadStaging.publish(failedOpenStage, digest,
                { throw IOException("simulated SAF output-open failure") },
                { error("readback must not open") },
                rollbackDestination = { failedOpenRolledBack = true })
        }.exceptionOrNull()
        check(failedOpen is IOException && failedOpen.message == "simulated SAF output-open failure")
        check(failedOpenRolledBack)
        check(failedOpenStage.isFile)
        // A full write followed by a bad readback must trigger rollback; cleanup
        // failure is suppressed without hiding the original integrity error.
        val rollbackStage = File(dir, "rollback-readback.part").apply { writeBytes(bytes) }
        val alteredReadback = bytes.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val rollbackOutput = ByteArrayOutputStream()
        var rollbackAttempts = 0
        val rollbackFailure = runCatching {
            VerifiedDownloadStaging.publish(rollbackStage, digest, { rollbackOutput },
                { ByteArrayInputStream(alteredReadback) },
                rollbackDestination = {
                    rollbackAttempts++
                    throw IOException("simulated SAF delete failure")
                })
        }.exceptionOrNull()
        check(rollbackFailure is com.netkit.local.remote.RemoteContentChangedException)
        check(rollbackAttempts == 1)
        check(rollbackFailure.suppressed.any { it.message == "simulated SAF delete failure" })
        check(rollbackStage.isFile)
        // A modified remote prefix is never appended to an old partial.
        part.writeBytes(bytes.copyOf(5))
        val modified = bytes.clone().apply { this[2] = 0 }
        fail { VerifiedDownloadStaging.stage(ByteArrayInputStream(modified), part, bytes.size.toLong()) }
        check(!part.exists())
        part.writeBytes(bytes.copyOf(8))
        fail { VerifiedDownloadStaging.stage(ByteArrayInputStream(bytes), part, 7) }
        check(!part.exists())
        fail { VerifiedDownloadStaging.stage(ByteArrayInputStream(bytes.copyOf(4)), part, 10) }
        fail { VerifiedDownloadStaging.verifyReadback(ByteArrayInputStream(bytes.copyOf(8)), 10, digest) }
        fail { VerifiedDownloadStaging.verifyReadback(ByteArrayInputStream(modified), bytes.size.toLong(), digest) }
        fail { VerifiedDownloadStaging.verifyReadback(ByteArrayInputStream(bytes + byteArrayOf(1)), bytes.size.toLong(), digest) }
        // True network-offset retry downloads only missing bytes on the first pass.
        val offsetPart = File(dir, "offset.part").apply { writeBytes(bytes.copyOf(73_000)) }
        val offsets = mutableListOf<Long>()
        var resumedProgress = -1L
        val offsetDigest = VerifiedDownloadStaging.stageFromOffset({ offset ->
            offsets += offset
            ByteArrayInputStream(bytes.copyOfRange(offset.toInt(), bytes.size))
        }, offsetPart, bytes.size.toLong()) { resumedProgress = it }
        check(offsets == listOf(73_000L))
        check(resumedProgress == bytes.size.toLong())
        check(offsetPart.readBytes().contentEquals(bytes))
        VerifiedDownloadStaging.verifyReadback(ByteArrayInputStream(bytes), bytes.size.toLong(), offsetDigest)
        // Complete staging must not reopen the network, but still needs readback.
        VerifiedDownloadStaging.stageFromOffset({ error("should not reopen completed stage") },
            offsetPart, bytes.size.toLong())
        // A full-length stage that changes during progress notification must not be
        // accepted merely because its original length matched the remote length.
        val changedComplete = File(dir, "changed-complete.part").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        fail {
            VerifiedDownloadStaging.stageFromOffset({ error("completed stage must not reopen network") },
                changedComplete, 4L) {
                changedComplete.appendBytes(byteArrayOf(5))
            }
        }
        check(changedComplete.length() == 5L)
        // An interrupted true-offset download must leave a resumable prefix.
        val interruptedOffset = File(dir, "interrupted-offset.part").apply {
            writeBytes(bytes.copyOf(73_000))
        }
        fail {
            VerifiedDownloadStaging.stageFromOffset({ offset ->
                check(offset == 73_000L)
                object : ByteArrayInputStream(bytes.copyOfRange(offset.toInt(), bytes.size)) {
                    var consumed = 0
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (consumed >= 12_000) throw IOException("offset disconnected")
                        val n = super.read(b, off, minOf(len, 12_000 - consumed))
                        if (n > 0) consumed += n
                        return n
                    }
                }
            }, interruptedOffset, bytes.size.toLong())
        }
        check(interruptedOffset.length() == 85_000L)
        val retryOffsets = mutableListOf<Long>()
        val retryDigest = VerifiedDownloadStaging.stageFromOffset({ offset ->
            retryOffsets += offset
            ByteArrayInputStream(bytes.copyOfRange(offset.toInt(), bytes.size))
        }, interruptedOffset, bytes.size.toLong())
        check(retryOffsets == listOf(85_000L))
        check(interruptedOffset.readBytes().contentEquals(bytes))
        VerifiedDownloadStaging.verifyReadback(
            ByteArrayInputStream(bytes), bytes.size.toLong(), retryDigest)

        // Server ignoring REST/offset must fail rather than silently duplicating.
        val ignored = File(dir, "ignored.part").apply { writeBytes(bytes.copyOf(100)) }
        fail { VerifiedDownloadStaging.stageFromOffset({ ByteArrayInputStream(bytes) },
            ignored, bytes.size.toLong()) }
        // Same-length remote mutation is caught by independent full SHA readback.
        val altered = bytes.clone().apply { this[3] = (this[3].toInt() xor 0x55).toByte() }
        val stale = File(dir, "stale.part").apply { writeBytes(bytes.copyOf(73_000)) }
        val staleDigest = VerifiedDownloadStaging.stageFromOffset({ offset ->
            ByteArrayInputStream(altered.copyOfRange(offset.toInt(), altered.size))
        }, stale, altered.size.toLong())
        check(runCatching { VerifiedDownloadStaging.verifyReadback(
            ByteArrayInputStream(altered), altered.size.toLong(), staleDigest)
        }.exceptionOrNull() is com.netkit.local.remote.RemoteContentChangedException)
        val emptyOffset = File(dir, "empty-offset.part")
        val emptyOffsetDigest = VerifiedDownloadStaging.stageFromOffset(
            { error("empty should not open network") }, emptyOffset, 0)
        check(emptyOffset.isFile && emptyOffset.length() == 0L)
        VerifiedDownloadStaging.verifyReadback(ByteArrayInputStream(byteArrayOf()), 0, emptyOffsetDigest)
        val empty = File(dir, "empty.part")
        val emptyDigest = VerifiedDownloadStaging.stage(ByteArrayInputStream(byteArrayOf()), empty, 0)
        VerifiedDownloadStaging.verifyReadback(ByteArrayInputStream(byteArrayOf()), 0, emptyDigest)
        val zero = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        fail { VerifiedDownloadStaging.stage(zero, File(dir, "stall.part"), 1) }
        // A stalled stream while validating an existing resume prefix must also
        // fail boundedly; the resumable local partial is retained for a later retry.
        val prefixStallPart = File(dir, "prefix-stall.part").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val prefixStallFailure = runCatching {
            VerifiedDownloadStaging.stage(zero, prefixStallPart, 4)
        }.exceptionOrNull()
        check(prefixStallFailure is IOException &&
            prefixStallFailure.message!!.contains("Prefix comparison stalled"))
        check(prefixStallPart.isFile && prefixStallPart.length() == 3L)
        val offsetStall = File(dir, "offset-stall.part").apply { writeBytes(bytes.copyOf(1)) }
        fail { VerifiedDownloadStaging.stageFromOffset({ zero }, offsetStall, 10) }
        // SAF write/readback failure must preserve the stage for a later retry.
        val hold = File(dir, "hold.part").apply { writeBytes(bytes) }
        var holdRollbackCalled = false
        fail {
            VerifiedDownloadStaging.publish(hold, digest, { ByteArrayOutputStream() },
                { ByteArrayInputStream(byteArrayOf()) },
                rollbackDestination = { holdRollbackCalled = true })
        }
        check(holdRollbackCalled)
        check(hold.isFile && hold.length() == bytes.size.toLong())
        println("PASS: verified FTP/SFTP download, true offset retry, mutation and ignored-offset detection, SHA readback and SAF publish")
    } finally { dir.deleteRecursively() }
}
