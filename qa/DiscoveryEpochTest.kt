package com.netkit.local.connectivity

fun main() {
    val gate = DiscoveryEpoch()
    val first = gate.begin()
    check(gate.isCurrent(first)) { "A newly started discovery generation must be current" }

    gate.invalidate()
    check(!gate.isCurrent(first)) { "Stopping discovery must invalidate pending resolve callbacks" }

    val second = gate.begin()
    check(second > first) { "Restart must allocate a new generation" }
    check(gate.isCurrent(second)) { "The restarted generation should be current" }
    check(!gate.isCurrent(first)) { "A stale callback must remain stale after restart" }

    gate.invalidate()
    check(!gate.isCurrent(second)) { "Closing discovery must invalidate the latest generation" }
    println("PASS: stale NSD callbacks cannot repopulate discovery after stop/restart")
}
