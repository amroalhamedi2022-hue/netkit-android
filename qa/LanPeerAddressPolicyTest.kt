import com.netkit.local.connectivity.LanPeerAddressPolicy
import java.net.InetAddress

private fun checkAddress(value: String, expected: Boolean) {
    val actual = LanPeerAddressPolicy.isAllowed(InetAddress.getByName(value))
    check(actual == expected) { "$value expected allowed=$expected but was $actual" }
}

fun main() {
    listOf(
        "10.0.0.1", "10.255.255.254",
        "172.16.0.1", "172.31.255.254",
        "192.168.1.20", "169.254.12.3",
        "fc00::1", "fd12:3456:789a::5", "fe80::1",
        "::ffff:192.168.1.20"
    ).forEach { checkAddress(it, true) }

    listOf(
        "8.8.8.8", "172.15.255.255", "172.32.0.1",
        "100.64.0.1", "192.0.2.1", "169.253.1.1",
        "127.0.0.1", "0.0.0.0", "224.0.0.1",
        "2001:4860:4860::8888", "fec0::1", "::", "::1",
        "ff02::1", "::ffff:8.8.8.8"
    ).forEach { checkAddress(it, false) }

    check(!LanPeerAddressPolicy.isAllowed(null))
    println("PASS: DNS-SD peer addresses are restricted to private/link-local IPv4 and ULA/link-local IPv6")
}
