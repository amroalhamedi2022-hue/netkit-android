package com.netkit.local.remote

import java.nio.charset.StandardCharsets

/**
 * FTP commands are line based: never forward untrusted CR/LF, NUL or controls
 * from a filename, server listing, username or password into a protocol command.
 * SFTP is binary-framed but shares the same UI and path safety boundary.
 * Paths remain relative or absolute as selected by the user (including ".."
 * navigation); this is NOT a remote filesystem sandbox.
 */
object RemotePathPolicy {
    private fun validUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                    i += 2
                }
                Character.isLowSurrogate(ch) -> return false
                else -> i++
            }
        }
        return true
    }

    private fun safeText(value: String, maximumBytes: Int): String {
        require(value.isNotEmpty() && validUnicode(value)) { "Invalid remote text" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) {
            "Remote text too long"
        }
        require(value.none {
            it.code < 0x20 || it.code in 0x7f..0x9f || it == '\uFFFD' ||
                it.code in 0x202a..0x202e || it.code in 0x2066..0x2069 ||
                it.code == 0x061c || it.code in 0x200e..0x200f
        }) { "Unsafe remote text control character" }
        return value
    }

    fun path(value: String): String = safeText(value, 4096)
    fun credential(value: String): String = safeText(value, 1024)

    fun host(value: String): String {
        require(value.length in 1..253 && value.matches(Regex("[A-Za-z0-9.:%_-]+"))) {
            "Invalid remote host"
        }
        return value
    }

    /** Ignore server-supplied names that would be unsafe to reuse in FTP commands. */
    fun safeEntryName(value: String): Boolean =
        value != "." && value != ".." && '/' !in value && '\\' !in value &&
            runCatching { path(value); true }.getOrDefault(false)
}
