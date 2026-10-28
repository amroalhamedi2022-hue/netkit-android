package com.netkit.local.connectivity

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Defense-in-depth filter for DNS-SD sightings. A resolved address must be
 * private/link-local before it can appear as a NetKit LAN peer.
 *
 * This is not authentication and does not prove that a route stays on Wi-Fi:
 * VPNs and local routes can still use private address space.
 */
internal object LanPeerAddressPolicy {
    fun isAllowed(address: InetAddress?): Boolean {
        if (address == null || address.isAnyLocalAddress ||
            address.isLoopbackAddress || address.isMulticastAddress
        ) return false

        val bytes = address.address
        return when (address) {
            is Inet4Address -> isAllowedIpv4(bytes)
            is Inet6Address -> isAllowedIpv6(bytes)
            else -> false
        }
    }

    private fun isAllowedIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }

    private fun isAllowedIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false

        // IPv4-mapped IPv6 addresses inherit the same narrow IPv4 allowlist.
        val mappedIpv4 = (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xff) == 0xff &&
            (bytes[11].toInt() and 0xff) == 0xff
        if (mappedIpv4) return isAllowedIpv4(bytes.copyOfRange(12, 16))

        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val uniqueLocal = (first and 0xfe) == 0xfc // fc00::/7
        val linkLocal = first == 0xfe && (second and 0xc0) == 0x80 // fe80::/10
        return uniqueLocal || linkLocal
    }
}
