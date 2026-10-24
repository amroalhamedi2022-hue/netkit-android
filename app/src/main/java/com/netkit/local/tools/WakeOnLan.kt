package com.netkit.local.tools

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Send an opt-in Wake-on-LAN magic packet to a user-specified LAN broadcast. */
object WakeOnLan {
    internal fun macBytes(input: String): ByteArray {
        val normalized = input.trim(' ', '\t')
        require(Regex("^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){5}$").matches(normalized)) {
            "MAC must contain six hexadecimal octets"
        }
        val bytes = normalized.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        require(bytes.any { it.toInt() != 0 } && bytes.any { it.toInt() != -1 }) {
            "Invalid target MAC"
        }
        require(bytes[0].toInt() and 1 == 0) { "Target MAC must be unicast" }
        return bytes
    }

    internal fun broadcastBytes(input: String): ByteArray {
        // Never DNS-resolve an arbitrary hostname or transmit to public IPv4.
        val parts = input.trim(' ', '\t').split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) }) {
            "Enter a numeric IPv4 broadcast address"
        }
        val n = parts.map { it.toIntOrNull() ?: -1 }
        require(n.all { it in 0..255 }) { "Invalid IPv4 broadcast address" }
        val limited = n.all { it == 255 }
        val privateLan = n[3] == 255 && (n[0] == 10 ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 192 && n[1] == 168))
        require(limited || privateLan) { "Only private LAN or limited broadcast is allowed" }
        return n.map { it.toByte() }.toByteArray()
    }

    internal fun magicPacket(mac: String): ByteArray {
        val address = macBytes(mac)
        return ByteArray(102).also { packet ->
            packet.fill(0xff.toByte(), 0, 6)
            repeat(16) { address.copyInto(packet, 6 + it * 6) }
        }
    }

    fun send(mac: String, broadcast: String, port: Int = 9): String {
        require(port in 1..65535) { "Invalid UDP port" }
        val payload = magicPacket(mac)
        val address = InetAddress.getByAddress(broadcastBytes(broadcast))
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(payload, payload.size, address, port))
        }
        return "WOL magic packet sent to $broadcast:$port (UDP). PC wake-up is not confirmed."
    }
}
