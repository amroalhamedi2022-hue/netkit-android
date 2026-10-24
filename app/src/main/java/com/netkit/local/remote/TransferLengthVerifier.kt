package com.netkit.local.remote

/**
 * Transfer progress counts bytes written/read locally. An FTP MLST or SFTP stat
 * size can catch silent truncation but is NOT a cryptographic integrity proof.
 * If a server does not expose a trustworthy size, the check is unavailable.
 */
object TransferLengthVerifier {
    fun requireMatch(expectedBytes: Long?, actualBytes: Long) {
        require(actualBytes >= 0) { "Negative transfer length" }
        if (expectedBytes == null || expectedBytes < 0) return
        check(expectedBytes == actualBytes) {
            "Remote file size mismatch: expected $expectedBytes bytes, transferred $actualBytes"
        }
    }
}
