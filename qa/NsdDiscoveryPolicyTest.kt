package com.netkit.local.connectivity

fun main() {
    check(NsdDiscoveryPolicy.isNetKitServiceType("_netkit._tcp."))
    check(NsdDiscoveryPolicy.isNetKitServiceType("_NETKIT._TCP"))
    check(!NsdDiscoveryPolicy.isNetKitServiceType("_netkit._tcp.evil"))
    check(!NsdDiscoveryPolicy.isNetKitServiceType("_other._tcp"))
    check(!NsdDiscoveryPolicy.isNetKitServiceType("_netkit._tcp.."))

    check(NsdDiscoveryPolicy.isValidPort(1))
    check(NsdDiscoveryPolicy.isValidPort(443))
    check(NsdDiscoveryPolicy.isValidPort(65535))
    for (invalidPort in listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE)) {
        check(!NsdDiscoveryPolicy.isValidPort(invalidPort)) { "Invalid NSD port was accepted: $invalidPort" }
    }

    check(NsdDiscoveryPolicy.isSafeAdvertisement("_netkit._tcp.", "NetKit-Device"))
    check(NsdDiscoveryPolicy.isSafeAdvertisement("_NETKIT._TCP", "جهاز أندرويد"))
    check(!NsdDiscoveryPolicy.isSafeAdvertisement("_other._tcp.", "NetKit-Device"))
    for (unsafeName in listOf("", " padded ", "Host\u202Etxt", "Cafe\u0301", "x".repeat(64))) {
        check(!NsdDiscoveryPolicy.isSafeAdvertisement("_netkit._tcp.", unsafeName)) {
            "Unsafe DNS-SD label reached peer resolution: $unsafeName"
        }
    }

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

    // An NSD implementation that never invokes a callback must not pin
    // one of the bounded resolve slots forever.
    var nowNanos = 0L
    val timed = NsdResolveLedger(
        maxPending = 1,
        resolveTimeoutMillis = 100,
        nowNanos = { nowNanos }
    )
    val stale = timed.begin("NetKit-stalled") ?: error("initial resolve rejected")
    nowNanos = 99_000_000L
    check(timed.size == 1) { "resolve expired before its deadline" }
    nowNanos = 100_000_000L
    val fresh = timed.begin("NetKit-stalled") ?: error("expired resolve kept the slot")
    check(fresh != stale)
    check(!timed.finish("NetKit-stalled", stale)) {
        "late callback from an expired resolve consumed the replacement ticket"
    }
    check(timed.isCurrent("NetKit-stalled", fresh))
    nowNanos += 100_000_000L
    check(!timed.finish("NetKit-stalled", fresh)) { "expired resolve was accepted" }
    check(timed.size == 0)
    check(runCatching { NsdResolveLedger(resolveTimeoutMillis = 0) }.isFailure)
    check(runCatching { NsdResolveLedger(resolveTimeoutMillis = 300_001) }.isFailure)

    // A broken clock must invalidate every old ticket instead of extending
    // their lifetime or letting a late resolve callback resurrect a peer.
    var rollbackNow = 10_000L
    val rollback = NsdResolveLedger(maxPending = 2, nowNanos = { rollbackNow })
    val beforeRollback = rollback.begin("NetKit-clock") ?: error("resolve rejected")
    rollbackNow = 9_000L
    check(!rollback.isCurrent("NetKit-clock", beforeRollback))
    check(!rollback.finish("NetKit-clock", beforeRollback))
    check(rollback.size == 0)
    rollbackNow = 10_000L
    val afterRollback = rollback.begin("NetKit-clock") ?: error("resolve did not recover after reset")
    check(afterRollback != beforeRollback)
    check(rollback.finish("NetKit-clock", afterRollback))

    // Concurrent service-found/service-lost/stop callbacks must not race the
    // LinkedHashMap used by the resolve ledger. Invalidation and clear can be
    // invoked by different Android NSD callback threads.
    val concurrent = NsdResolveLedger(maxPending = 128)
    val start = java.util.concurrent.CountDownLatch(1)
    val done = java.util.concurrent.CountDownLatch(8)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    repeat(8) { worker ->
        Thread {
            try {
                start.await()
                repeat(2_000) { iteration ->
                    val name = "worker-$worker-$iteration"
                    val ticket = concurrent.begin(name)
                    if (ticket != null) {
                        if ((worker + iteration) % 2 == 0) concurrent.invalidate(name)
                        else concurrent.finish(name, ticket)
                    }
                    if (iteration % 29 == 0) concurrent.clear()
                    check(concurrent.size <= 128) { "Concurrent resolve cap exceeded" }
                }
            } catch (problem: Throwable) {
                failure.compareAndSet(null, problem)
            } finally {
                done.countDown()
            }
        }.start()
    }
    start.countDown()
    check(done.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
        "Concurrent DNS-SD ledger workers did not terminate"
    }
    failure.get()?.let { throw IllegalStateException("Concurrent DNS-SD ledger failure", it) }
    check(concurrent.size <= 128)

    println("PASS: exact NetKit DNS-SD type, bounded resolves and stale callback and concurrent invalidation rejection")
}
