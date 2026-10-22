package com.netkit.local.tools

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

object Diagnostics {
    fun dns(host: String): String {
        val safeHost = host.trim().take(253)
        require(safeHost.isNotEmpty()) { "اكتب اسم نطاق صالح" }
        val ips = InetAddress.getAllByName(safeHost).map { it.hostAddress.orEmpty() }.distinct()
        return "DNS: $safeHost → ${ips.joinToString(", ")}"
    }
    fun tcp(host: String, port: Int): String {
        require(port in 1..65535) { "المنفذ يجب أن يكون بين 1 و65535" }
        val safeHost = host.trim()
        require(safeHost.isNotEmpty()) { "اكتب عنوان جهاز أو نطاق" }
        var elapsed = 0L
        Socket().use { socket ->
            elapsed = measureTimeMillis { socket.connect(InetSocketAddress(safeHost, port), 3000) }
        }
        return "TCP $safeHost:$port — اتصال ناجح في ${elapsed}ms (ليس ICMP Ping)"
    }
}
