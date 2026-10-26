package com.netkit.local.connectivity

import java.text.Normalizer

/**
 * Validate an untrusted DNS-SD instance label before displaying it in the peer list.
 * This is anti-spoofing/display hygiene only; it is not authentication or pairing.
 */
object PeerDisplayNamePolicy {
    fun isSafeServiceName(value: String): Boolean {
        if (value.isBlank() || value != value.trim() ||
            !Normalizer.isNormalized(value, Normalizer.Form.NFC)) return false
        if (value.toByteArray(Charsets.UTF_8).size !in 1..63) return false

        var index = 0
        while (index < value.length) {
            val current = value[index]
            val codePoint: Int
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length ||
                    !Character.isLowSurrogate(value[index + 1])) return false
                codePoint = Character.toCodePoint(current, value[index + 1])
                index += 2
            } else {
                if (Character.isLowSurrogate(current)) return false
                codePoint = current.code
                index++
            }

            if (codePoint < 0x20 || codePoint in 0x7f..0x9f ||
                codePoint == 0xfffd || codePoint == 0x061c ||
                codePoint in 0x200b..0x200f ||
                codePoint in 0x2028..0x202e ||
                codePoint in 0x2060..0x206f ||
                codePoint == 0xfeff || codePoint in 0xfff9..0xfffb ||
                codePoint in 0xe0000..0xe007f) {
                return false
            }
        }
        return true
    }
}
