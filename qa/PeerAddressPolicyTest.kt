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
    val safeNames = listOf(
        "NetKit-Xiaomi Pad 5",
        "جهاز أندرويد",
        "NetKit 🫧",
        "می‌خواهم", // ZWNJ is meaningful in Persian orthography.
        "family-👨‍👩‍👧" // ZWJ is required by this emoji sequence.
    )
    safeNames.forEach { name ->
        check(PeerDisplayNamePolicy.isSafeServiceName(name)) { "Rejected safe service label: $name" }
    }
    val unsafeNames = listOf(
        "", " ", " padded ", "line" + 0x0A.toChar() + "break",
        "Host" + 0x202E.toChar() + "txt", "Host" + 0x200B.toChar() + "name",
        "Host" + 0xFEFF.toChar() + "name", "Host" + 0xFFFD.toChar() + "name",
        "soft" + 0x00AD.toChar() + "hyphen", "grapheme" + 0x034F.toChar() + "joiner",
        "obsolete" + 0x180E.toChar() + "separator",
        "Cafe" + 0x0301.toChar(), "x".repeat(64)
    )
    unsafeNames.forEach { name ->
        check(!PeerDisplayNamePolicy.isSafeServiceName(name)) {
            "Accepted deceptive or overlong service label: $name"
        }
    }
    check(!PeerDisplayNamePolicy.isSafeServiceName("bad" + 0xD800.toChar())) {
        "Accepted an isolated UTF-16 high surrogate"
    }
    check(!PeerDisplayNamePolicy.isSafeServiceName("bad" + 0xDC00.toChar())) {
        "Accepted an isolated UTF-16 low surrogate"
    }
    println("PASS: NetKit NSD address and display-name policies")
}
