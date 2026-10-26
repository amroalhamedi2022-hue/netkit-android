package com.netkit.local.qa

import com.netkit.local.tools.WakeOnLan

fun main() {
    val packet = WakeOnLan.magicPacket("02:11:22:aa:BB:cc")
    check(packet.size == 102)
    check(packet.take(6).all { it.toInt() and 0xff == 255 })
    val mac = byteArrayOf(0x02, 0x11, 0x22, 0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte())
    repeat(16) { check(packet.copyOfRange(6 + it * 6, 12 + it * 6).contentEquals(mac)) }
    check(WakeOnLan.magicPacket("02-11-22-AA-BB-CC").contentEquals(packet))
    for (bad in listOf("", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:11:22:33:44:55",
        "02:11:22:33:44", "02:11:22:33:44:GG", "02:11:22:33:44:55\n")) {
        check(runCatching { WakeOnLan.macBytes(bad) }.isFailure) { "Unsafe MAC accepted: $bad" }
    }
    for (good in listOf("255.255.255.255", "192.168.1.255", "172.31.0.255", "10.2.0.255")) {
        check(WakeOnLan.broadcastBytes(good).size == 4)
    }
    for (bad in listOf("8.8.8.255", "224.0.0.255", "192.168.1.1", "localhost", "192.168.1.256",
        "192.168.01.255", "172.32.0.255", "192.168.1.255\n")) {
        check(runCatching { WakeOnLan.broadcastBytes(bad) }.isFailure) { "Unsafe broadcast accepted: $bad" }
    }
    check(runCatching { WakeOnLan.send("02:11:22:33:44:55", "255.255.255.255", 0) }.isFailure)
    println("PASS: WOL magic packet, unicast MAC and private broadcast policy")
}
