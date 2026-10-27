package com.netkit.local.share

import java.util.Locale

/** Reject invalid or oversized declared lengths before any SAF side effects. */
internal object UploadLengthPolicy {
    fun check(length: Long, maximum: Long) {
        require(maximum > 0L) { "Invalid upload ceiling" }
        require(length >= 0L) { "Invalid upload length" }
        require(length <= maximum) { "Upload exceeds configured limit" }
    }
}

/**
 * Browser-supplied names and folder paths are untrusted, even with a valid
 * share token. Never pass raw browser paths to SAF or an OS filesystem.
 */
internal object UploadPathPolicy {
    fun validName(v: String): Boolean {
        if (v.isBlank() || v.length > 180 || v == "." || v == ".." ||
            v.endsWith('.') || v.endsWith(' ') ||
            v.startsWith(".netkit-", ignoreCase = true)) return false
        if ('\uFFFD' in v) return false
        var index = 0
        while (index < v.length) {
            val c = v[index]
            if (Character.isHighSurrogate(c)) {
                if (index + 1 >= v.length || !Character.isLowSurrogate(v[index + 1])) return false
                // Unicode tag characters are invisible supplementary code points.
                // Reject them without rejecting normal surrogate-pair emoji.
                if (Character.toCodePoint(c, v[index + 1]) in 0xE0000..0xE007F) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                index++
            }
        }
        if (v.any { c ->
                c == '/' || c == '\\' || c.code < 32 || c.code in 0x7f..0x9f ||
                c in "<>:\"|?*" || c.code == 0x061c ||
                c.code == 0x00ad || c.code == 0x034f || c.code == 0x180e ||
                c.code == 0x200b || c.code == 0x2060 ||
                c.code in 0x200e..0x200f || c.code in 0x202a..0x202e ||
                c.code in 0x2066..0x206f || c.code in 0x2028..0x2029 ||
                c.code in 0xfff9..0xfffb || c.code == 0xfeff
            }) return false
        val stem = v.substringBefore('.').trimEnd(' ', '.').uppercase(Locale.ROOT)
        // Keep browser uploads compatible with the portable ZIP policy: Windows
        // reserves CONIN$/CONOUT$ as well as the classic DOS device stems.
        if (stem in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") ||
            Regex("^(COM|LPT)[1-9¹²³]$").matches(stem)) return false
        return true
    }

    /** A relative folder upload must include at least one directory and the exact leaf filename. */
    fun folders(relativePath: String, fileName: String): List<String> {
        require(relativePath.isNotEmpty() && relativePath.length <= 1024) { "Invalid relative path length" }
        val parts = relativePath.split('/')
        require(parts.size in 2..17 && parts.all(::validName) && parts.last() == fileName) {
            "Unsafe or mismatched upload folder path"
        }
        return parts.dropLast(1)
    }

    fun checkFolders(folders: List<String>, fileName: String) {
        require(folders.size in 1..16 && validName(fileName) && folders.all(::validName)) {
            "Unsafe upload destination"
        }
        require(folders.joinToString("/").length + fileName.length + 1 <= 1024) {
            "Upload destination too long"
        }
    }
}
