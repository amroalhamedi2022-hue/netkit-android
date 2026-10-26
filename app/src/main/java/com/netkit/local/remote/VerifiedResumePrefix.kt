package com.netkit.local.remote

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Before an explicit FTP APPE / SFTP APPEND, compare every byte of the existing
 * remote file with the *same* SAF input stream that will supply the suffix.
 * Neither a saved queue counter nor remote SIZE alone authorizes an append.
 *
 * Streams stay open; on success [source] is positioned exactly at [remoteBytes].
 * The caller must still re-stat the remote file before append and verify the
 * entire result with SHA-256 readback. No protection against concurrent writers
 * is possible on arbitrary FTP/SFTP servers without a remote lock/lease.
 */
object VerifiedResumePrefix {
    fun verify(source: InputStream, remote: InputStream, remoteBytes: Long): Long {
        require(remoteBytes > 0) { "Resume requires a nonempty existing remote file" }
        val sourceBuffer = ByteArray(64 * 1024)
        val remoteBuffer = ByteArray(sourceBuffer.size)
        var compared = 0L
        while (compared < remoteBytes) {
            val wanted = minOf(sourceBuffer.size.toLong(), remoteBytes - compared).toInt()
            readExactly(source, sourceBuffer, wanted, "SAF source")
            readExactly(remote, remoteBuffer, wanted, "remote prefix")
            for (i in 0 until wanted) {
                if (sourceBuffer[i] != remoteBuffer[i]) {
                    throw IOException("Remote prefix differs from selected SAF source at byte ${compared + i}")
                }
            }
            compared += wanted
        }
        // Reject a server whose reported SIZE is smaller than its actual stream.
        if (readNonEmpty(remote, remoteBuffer, 1) != -1) {
            throw IOException("Remote file changed or reported a false resume length")
        }
        return compared
    }

    private fun readExactly(stream: InputStream, target: ByteArray, length: Int, label: String) {
        var offset = 0
        while (offset < length) {
            val count = readNonEmpty(stream, target, length - offset, offset)
            if (count == -1) throw EOFException("$label ended before the confirmed resume offset")
            offset += count
        }
    }

    private fun readNonEmpty(stream: InputStream, buffer: ByteArray, length: Int, offset: Int = 0): Int {
        var zeros = 0
        while (true) {
            val count = stream.read(buffer, offset, length)
            if (count != 0) return count
            if (++zeros >= 32) throw IOException("Resume verification stream stalled after 32 empty reads")
        }
    }
}
