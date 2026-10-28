package com.netkit.local.connectivity

fun main() {
    val registry = PeerDiscoveryRegistry()
    val firstScan = registry.beginScan()
    val first = registry.requestResolve(firstScan, "NetKit-Tablet")
        ?: error("Initial service resolution was not scheduled")
    check(registry.isCurrent(first))
    check(registry.requestResolve(firstScan, "NetKit-Tablet") == null) {
        "Duplicate NSD service-found events must not launch duplicate resolves"
    }
    check(registry.markResolved(first))
    check(!registry.isCurrent(first)) { "A consumed resolution ticket must become stale" }
    check(registry.requestResolve(firstScan, "NetKit-Tablet") == null) {
        "A resolved service must remain deduplicated until it is lost"
    }

    check(registry.markLost(firstScan, "NetKit-Tablet"))
    check(!registry.isCurrent(first))
    val second = registry.requestResolve(firstScan, "NetKit-Tablet")
        ?: error("A lost then rediscovered service must be resolvable again")
    check(second.requestId != first.requestId)
    check(!registry.isCurrent(first)) { "Old callback must not win after rediscovery" }
    check(registry.markResolveFailed(second))
    check(!registry.isCurrent(second))
    val retry = registry.requestResolve(firstScan, "NetKit-Tablet")
        ?: error("Failed resolution must release the service for a later retry")
    check(registry.isCurrent(retry))

    registry.stop(firstScan)
    check(!registry.isCurrent(retry)) { "Stopping a scan must invalidate outstanding callbacks" }
    check(!registry.markLost(firstScan, "NetKit-Tablet")) {
        "Late onServiceLost from an old scan must be ignored"
    }
    val secondScan = registry.beginScan()
    check(secondScan > firstScan)
    check(registry.requestResolve(firstScan, "stale-scan") == null)
    val current = registry.requestResolve(secondScan, "NetKit-Phone")
        ?: error("New scan should accept current services")
    check(registry.isCurrent(current))
    registry.stop()
    check(!registry.isCurrent(current))
}
