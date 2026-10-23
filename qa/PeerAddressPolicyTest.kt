package com.netkit.local.connectivity

import java.net.InetAddress

fun main() {
    val allowed = listOf(
        "10.0.0.2", "172.16.0.1", "172.31.255.254", "192.168.50.10",
        "169.254.1.2", "fc00::1234", "fd12:3456::1", "fe80::1"
    )
    val denied = listOf(
        "8.8.8.8", "1.1.1.1", "172.15.1.1", "172.32.0.1",
        "192.167.1.1", "127.0.0.1", "0.0.0.0", "224.0.0.1",
        "::1", "::", "2001:4860:4860::8888", "ff02::1"
    )
    allowed.forEach { ip ->
        check(PeerAddressPolicy.isLocalPeer(InetAddress.getByName(ip))) { "Rejected valid local address: $ip" }
    }
    denied.forEach { ip ->
        check(!PeerAddressPolicy.isLocalPeer(InetAddress.getByName(ip))) { "Accepted nonlocal address: $ip" }
    }
    println("PASS: NetKit NSD address policy (${allowed.size} allowed, ${denied.size} denied)")
}
