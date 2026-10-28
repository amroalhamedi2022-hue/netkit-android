package com.netkit.local.connectivity

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Address-scope filter for untrusted NSD advertisements, NOT peer authentication. */
object PeerAddressPolicy {
    fun isLocalPeer(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) {
            return false
        }
        val bytes = address.address
        return when (bytes.size) {
            4 -> isLocalIpv4(bytes)
            16 -> {
                // Android/JVM may expose IPv4-mapped addresses as either Inet4Address
                // or a 16-byte Inet6Address. Apply one identical IPv4 policy to both.
                if (isIpv4Mapped(bytes)) {
                    isLocalIpv4(bytes.copyOfRange(12, 16))
                } else {
                    val first = bytes[0].toInt() and 0xff
                    val second = bytes[1].toInt() and 0xff
                    (first and 0xfe) == 0xfc || // IPv6 ULA fc00::/7
                        (first == 0xfe && (second and 0xc0) == 0x80) // link-local fe80::/10
                }
            }
            else -> false
        }
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xff) == 0xff &&
            (bytes[11].toInt() and 0xff) == 0xff

    private fun isLocalIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }

    /** Stable dashboard candidates; scope filtering is not proof of reachability. */
    fun displayableLocalAddresses(addresses: Iterable<InetAddress>): List<String> =
        addresses.asSequence()
            .filter { isLocalPeer(it) }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
}
