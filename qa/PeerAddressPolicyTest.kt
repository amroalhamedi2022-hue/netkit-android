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
    val safeNames = listOf("NetKit-Xiaomi Pad 5", "جهاز أندرويد", "NetKit 🫧")
    safeNames.forEach { name ->
        check(PeerDisplayNamePolicy.isSafeServiceName(name)) { "Rejected safe service label: $name" }
    }
    val unsafeNames = listOf(
        "", " ", " padded ", "line\\nbreak", "Host\\u202Etxt", "Host\\u200Bname",
        "Host\\uFEFFname", "Host\\uFFFDname", "Cafe\\u0301", "x".repeat(64)
    )
    unsafeNames.forEach { name ->
        check(!PeerDisplayNamePolicy.isSafeServiceName(name)) {
            "Accepted deceptive or overlong service label: $name"
        }
    }
    check(!PeerDisplayNamePolicy.isSafeServiceName("bad\\uD800")) {
        "Accepted an isolated UTF-16 high surrogate"
    }
    check(!PeerDisplayNamePolicy.isSafeServiceName("bad\\uDC00")) {
        "Accepted an isolated UTF-16 low surrogate"
    }
    println("PASS: NetKit NSD address and display-name policies")
}
