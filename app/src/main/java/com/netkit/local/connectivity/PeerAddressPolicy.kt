package com.netkit.local.connectivity

import java.net.InetAddress

/** Address-scope filter for untrusted NSD advertisements, NOT peer authentication. */
object PeerAddressPolicy {
    fun isLocalPeer(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (bytes.size) {
            4 -> bytes[0] == 10 ||
                (bytes[0] == 172 && bytes[1] in 16..31) ||
                (bytes[0] == 192 && bytes[1] == 168) ||
                (bytes[0] == 169 && bytes[1] == 254)
            16 -> (bytes[0] and 0xfe) == 0xfc ||
                (bytes[0] == 0xfe && (bytes[1] and 0xc0) == 0x80)
            else -> false
        }
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
