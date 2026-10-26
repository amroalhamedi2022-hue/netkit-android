package com.netkit.local.remote

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Reusable, app-private download staging. An interrupted .part is never published to SAF.
 * On retry the entire saved prefix is compared with the remote stream before appending.
 * Remote SHA-256 is independently read back before publish; remote changes during
 * transfer fail closed. This needs free app-cache space and extra network bandwidth.
 */
class RemoteContentChangedException(message: String) : IOException(message)

/** Build an integrity failure while retaining cleanup failure as suppressed evidence. */
internal fun stalePartialFailure(
    part: File,
    reason: String,
    delete: (File) -> Boolean = { it.delete() }
): IOException {
    val failure = IOException(reason)
    if (part.exists() && !delete(part) && part.exists()) {
        failure.addSuppressed(IOException("Could not remove stale partial download"))
    }
    return failure
}

object VerifiedDownloadStaging {
    private const val BLOCK = 64 * 1024

    /**
     * Persist the current prefix even when the network stream fails. A resumed
     * transfer must never trust file length alone after a process interruption.
     * Preserve the original transfer error if syncing the partial file also fails.
     */
    private inline fun <T> syncOnExit(output: FileOutputStream, block: () -> T): T {
        var primary: Throwable? = null
        try {
            return block()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                output.fd.sync()
            } catch (syncFailure: Throwable) {
                val original = primary
                if (original != null) original.addSuppressed(syncFailure)
                else throw syncFailure
            }
        }
    }

    fun stage(remote: InputStream, part: File, expectedBytes: Long, progress: (Long) -> Unit = {}): ByteArray {
        require(expectedBytes >= 0) { "Verified download requires a known remote length" }
        require(part.isFile || !part.exists()) { "Staging path is not a regular file" }
        val saved = if (part.exists()) part.length() else 0L
        if (saved > expectedBytes) {
            throw stalePartialFailure(part, "Saved download exceeds remote size")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BLOCK)
        val localBuffer = ByteArray(BLOCK)
        var received = 0L
        if (saved > 0) {
            FileInputStream(part).use { local ->
                while (received < saved) {
                    val wanted = minOf(BLOCK.toLong(), saved - received).toInt()
                    readExactly(remote, buffer, wanted)
                    readExactly(local, localBuffer, wanted)
                    if (!MessageDigest.isEqual(buffer.copyOf(wanted), localBuffer.copyOf(wanted))) {
                        throw stalePartialFailure(part, "Remote prefix changed")
                    }
                    digest.update(buffer, 0, wanted)
                    received += wanted
                }
            }
        }
        progress(received)
        FileOutputStream(part, true).use { output ->
            syncOnExit(output) {
            var emptyReads = 0
            while (received < expectedBytes) {
                val wanted = minOf(BLOCK.toLong(), expectedBytes - received).toInt()
                val count = remote.read(buffer, 0, wanted)
                if (count < 0) throw IOException("Remote download ended before expected length")
                if (count == 0) {
                    if (++emptyReads >= 32) throw IOException("Remote download stalled after 32 empty reads")
                    continue
                }
                emptyReads = 0
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                received += count
                progress(received)
            }
            if (remote.read() != -1) throw IOException("Remote grew during download")
            }
        }
        check(part.length() == expectedBytes) { "Staged file length mismatch" }
        return digest.digest()
    }

    /**
     * True network-offset retry: hash the durable local prefix, then fetch only
     * the missing suffix. Caller MUST perform an independent full remote SHA-256
     * readback before SAF publication; offset and length do not prove integrity.
     */
    fun stageFromOffset(
        openAt: (Long) -> InputStream,
        part: File,
        expectedBytes: Long,
        progress: (Long) -> Unit = {}
    ): ByteArray {
        require(expectedBytes >= 0) { "Verified download requires a known remote length" }
        require(part.isFile || !part.exists()) { "Staging path is not a regular file" }
        val saved = if (part.exists()) part.length() else 0L
        if (saved > expectedBytes) {
            throw stalePartialFailure(part, "Saved download exceeds remote size")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BLOCK)
        if (saved > 0) {
            FileInputStream(part).use { local ->
                var hashed = 0L
                while (hashed < saved) {
                    val wanted = minOf(BLOCK.toLong(), saved - hashed).toInt()
                    readExactly(local, buffer, wanted)
                    digest.update(buffer, 0, wanted)
                    hashed += wanted
                }
            }
        }
        progress(saved)
        if (saved == expectedBytes) {
            // A complete-sized .part may survive a process interruption before its
            // final metadata/data flush. Re-sync it before treating it as resumable.
            if (part.exists()) {
                FileOutputStream(part, true).use { it.fd.sync() }
            } else {
                FileOutputStream(part).use { it.fd.sync() }
            }
            if (part.length() != expectedBytes) {
                throw IOException("Staged offset file changed while finalizing completed download")
            }
            return digest.digest()
        }
        openAt(saved).use { remote ->
            FileOutputStream(part, true).use { output ->
                syncOnExit(output) {
                var received = saved
                var emptyReads = 0
                while (received < expectedBytes) {
                    val wanted = minOf(BLOCK.toLong(), expectedBytes - received).toInt()
                    val n = remote.read(buffer, 0, wanted)
                    if (n < 0) throw IOException("Remote offset stream ended early")
                    if (n == 0) {
                        if (++emptyReads >= 32) throw IOException("Remote offset stream stalled")
                        continue
                    }
                    emptyReads = 0
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    received += n
                    progress(received)
                }
                if (remote.read() != -1) {
                    throw IOException("Remote ignored offset or grew during download")
                }
                }
            }
        }
        check(part.length() == expectedBytes) { "Staged offset file length mismatch" }
        return digest.digest()
    }

    /** Read a second, independent remote stream to reject changed same-length content. */
    fun verifyReadback(remote: InputStream, expectedBytes: Long, expectedDigest: ByteArray) {
        require(expectedBytes >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BLOCK)
        var count = 0L
        var zeros = 0
        while (count < expectedBytes) {
            val n = remote.read(buffer, 0, minOf(BLOCK.toLong(), expectedBytes - count).toInt())
            if (n < 0) throw IOException("Remote readback was truncated")
            if (n == 0) {
                if (++zeros >= 32) throw IOException("Remote readback stalled")
                continue
            }
            zeros = 0
            digest.update(buffer, 0, n)
            count += n
        }
        if (remote.read() != -1) throw IOException("Remote readback length changed")
        if (!MessageDigest.isEqual(digest.digest(), expectedDigest)) {
            throw RemoteContentChangedException("Remote SHA-256 readback differs from staged download")
        }
    }

    private fun hashStagedFile(part: File, expectedLength: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BLOCK)
        var count = 0L
        var emptyReads = 0
        FileInputStream(part).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) {
                    if (++emptyReads >= 32) throw IOException("Staged file read stalled")
                    continue
                }
                emptyReads = 0
                if (n.toLong() > expectedLength - count) {
                    throw IOException("Staged file grew during verification")
                }
                count += n
                digest.update(buffer, 0, n)
            }
        }
        if (count != expectedLength || part.length() != expectedLength) {
            throw IOException("Staged file length changed during verification")
        }
        return digest.digest()
    }

    /**
     * SAF publishing is not atomic; caller must use a newly created destination URI.
     * Re-verify the app-private stage before opening the SAF destination so a stale
     * or externally modified .part is rejected without exposing corrupted bytes.
     */
    fun publish(part: File, expectedDigest: ByteArray, output: () -> OutputStream, readback: () -> InputStream, rollbackDestination: () -> Unit) {
        require(part.isFile) { "Missing verified staged download" }
        require(expectedDigest.size == 32) { "Expected SHA-256 digest must be 32 bytes" }
        val length = part.length()
        val stagedDigest = hashStagedFile(part, length)
        if (!MessageDigest.isEqual(stagedDigest, expectedDigest)) {
            throw RemoteContentChangedException("Local staged file SHA-256 differs before SAF publish")
        }

        // The caller supplies a newly created SAF document. Once output opening is attempted,
        // any write/readback failure must roll that document back so a partial file is not left
        // looking like a completed download.
        var destinationMayHaveBeenTouched = false
        try {
            // Hash again while copying to detect a stage changed after the preflight read.
            val copyDigest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BLOCK)
            var copied = 0L
            var emptyReads = 0
            destinationMayHaveBeenTouched = true
            output().use { destination ->
                FileInputStream(part).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) {
                            if (++emptyReads >= 32) throw IOException("Staged file read stalled during publish")
                            continue
                        }
                        emptyReads = 0
                        if (n.toLong() > length - copied) {
                            throw IOException("Staged file grew during publish")
                        }
                        destination.write(buffer, 0, n)
                        copyDigest.update(buffer, 0, n)
                        copied += n
                    }
                }
                if (copied != length || part.length() != length) {
                    throw IOException("Staged file length changed during publish")
                }
                if (!MessageDigest.isEqual(copyDigest.digest(), expectedDigest)) {
                    throw RemoteContentChangedException("Local staged file SHA-256 changed during SAF publish")
                }
            }
            readback().use { verifyReadback(it, length, expectedDigest) }
        } catch (failure: Throwable) {
            if (destinationMayHaveBeenTouched) {
                try {
                    rollbackDestination()
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
        // The SAF document is already verified: failed cache cleanup must not report a false transfer failure.
        part.delete()
    }

    private fun readExactly(input: InputStream, buffer: ByteArray, size: Int) {
        var offset = 0
        var zeros = 0
        while (offset < size) {
            val n = input.read(buffer, offset, size - offset)
            if (n < 0) throw IOException("Remote or local prefix was truncated")
            if (n == 0) {
                if (++zeros >= 32) throw IOException("Prefix comparison stalled")
                continue
            }
            zeros = 0
            offset += n
        }
    }
}
