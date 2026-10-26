package com.netkit.local.connectivity

fun main() {
    check(NsdDiscoveryPolicy.isNetKitServiceType("_netkit._tcp."))
    check(NsdDiscoveryPolicy.isNetKitServiceType("_NETKIT._TCP"))
    check(!NsdDiscoveryPolicy.isNetKitServiceType("_netkit._tcp.evil"))
    check(!NsdDiscoveryPolicy.isNetKitServiceType("_other._tcp"))
    check(!NsdDiscoveryPolicy.isNetKitServiceType("_netkit._tcp.."))

    val ledger = NsdResolveLedger(maxPending = 2)
    val a = ledger.begin("NetKit-A") ?: error("first resolve rejected")
    check(ledger.begin("NetKit-A") == null) { "duplicate in-flight resolve was accepted" }
    val b = ledger.begin("NetKit-B") ?: error("second resolve rejected")
    check(ledger.size == 2)
    check(ledger.begin("NetKit-C") == null) { "pending resolve cap was exceeded" }
    check(!ledger.finish("NetKit-A", b)) { "wrong ticket completed another service" }
    check(ledger.finish("NetKit-A", a))
    val c = ledger.begin("NetKit-C") ?: error("capacity was not released")
    ledger.invalidate("NetKit-C")
    check(!ledger.finish("NetKit-C", c)) { "late callback survived service loss" }
    val c2 = ledger.begin("NetKit-C") ?: error("service could not be retried")
    check(!ledger.finish("NetKit-C", c)) { "stale callback consumed a newer resolve" }
    check(ledger.isCurrent("NetKit-C", c2))
    ledger.clear()
    check(ledger.size == 0)
    check(!ledger.finish("NetKit-C", c2)) { "late callback survived discovery stop" }

    println("PASS: exact NetKit DNS-SD type, bounded resolves and stale callback rejection")
}
