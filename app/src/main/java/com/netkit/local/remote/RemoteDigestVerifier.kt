package com.netkit.local.remote

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Optional FTP/SFTP upload integrity: hash the original SAF stream while
 * uploading, then re-read the stored remote object and compare full SHA-256.
 * This verifies bytes readable after upload, not long-term durability or
 * authenticity of a malicious remote server.
 */
object RemoteDigestVerifier {
    fun digest(stream: InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var emptyReads = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) return hash.digest()
            if (n == 0) {
                if (++emptyReads >= 32) {
                    throw IOException("Remote SHA-256 verification stalled")
                }
                continue
            }
            emptyReads = 0
            hash.update(buffer, 0, n)
        }
    }

    fun requireMatch(localDigest: ByteArray, remoteDigest: ByteArray) {
        require(localDigest.size == 32 && remoteDigest.size == 32 &&
            MessageDigest.isEqual(localDigest, remoteDigest)) {
            "Remote SHA-256 readback differs from the uploaded file"
        }
    }
}
