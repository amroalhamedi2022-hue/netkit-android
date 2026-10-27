package com.netkit.local.remote

import java.io.IOException

/**
 * Commons Net FTPClient.getSize() returns the raw FTP reply (e.g. "213 123\r\n").
 * RFC 3659 MLST is optional; strict SIZE fallback enables verified resume
 * without trusting ambiguous LIST output or accepting malformed lengths.
 */
object FtpSizePolicy {
    private val positive = Regex("213[ \\t]+([0-9]{1,19})")

    fun parseReply(reply: String?): Long? {
        if (reply == null) return null
        // Accept a single complete CRLF (FTP) or LF (legacy/test-server) terminator.
        // trimEnd('', '\n') is too permissive: it silently accepts multiple lines
        // and a lone CR, so a malformed/multiline reply can masquerade as one SIZE.
        val line = when {
            reply.endsWith("\r\n") -> reply.dropLast(2)
            reply.endsWith("\n") -> reply.dropLast(1)
            else -> reply
        }
        if ('\r' in line || '\n' in line) {
            throw IOException("Malformed FTP SIZE response framing")
        }
        if (!line.startsWith("213")) return null
        val match = positive.matchEntire(line)
            ?: throw IOException("Malformed FTP SIZE response")
        return match.groupValues[1].toLongOrNull()
            ?: throw IOException("FTP SIZE value overflows 64-bit length")
    }

    fun preferMlst(mlstBytes: Long?, fetchSizeReply: () -> String?): Long? =
        if (mlstBytes != null && mlstBytes >= 0L) mlstBytes else parseReply(fetchSizeReply())
}
