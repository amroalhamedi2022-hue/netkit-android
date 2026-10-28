package com.netkit.local.connectivity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun main() {
    val gate = DiscoveryStateGate()
    var activeEpoch = 0L
    val first = gate.begin { activeEpoch = it } ?: error("first discovery session rejected")
    check(first == activeEpoch)
    check(gate.begin { error("duplicate session must not start") } == null)

    // Reproduce the NSD race: a resolve is about to publish while service-lost
    // invalidates the same service. The gate serializes the two mutations.
    var ticketCurrent = true
    val visiblePeers = mutableSetOf<String>()
    val resolvingEntered = CountDownLatch(1)
    val allowResolveToFinish = CountDownLatch(1)
    val lostStarted = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>(null)

    val resolving = Thread {
        try {
            check(gate.runIfActive(first) {
                resolvingEntered.countDown()
                allowResolveToFinish.await(2, TimeUnit.SECONDS)
                if (ticketCurrent) visiblePeers.add("NetKit-peer")
            })
        } catch (problem: Throwable) {
            failure.compareAndSet(null, problem)
        }
    }
    resolving.start()
    check(resolvingEntered.await(2, TimeUnit.SECONDS)) { "resolve callback did not enter gate" }

    val lost = Thread {
        try {
            lostStarted.countDown()
            gate.runIfActive(first) {
                ticketCurrent = false
                visiblePeers.remove("NetKit-peer")
            }
        } catch (problem: Throwable) {
            failure.compareAndSet(null, problem)
        }
    }
    lost.start()
    check(lostStarted.await(2, TimeUnit.SECONDS))
    allowResolveToFinish.countDown()
    resolving.join(2_000)
    lost.join(2_000)
    check(!resolving.isAlive && !lost.isAlive) { "discovery race workers did not terminate" }
    failure.get()?.let { throw IllegalStateException("discovery gate concurrency failed", it) }
    check(visiblePeers.isEmpty()) { "a service-lost event must win over an in-flight resolve" }

    check(gate.stop(first) { visiblePeers.clear(); true })
    check(!gate.runIfActive(first) { visiblePeers.add("late-old-peer") })
    val second = gate.begin { activeEpoch = it } ?: error("new session did not start")
    check(second != first)
    check(!gate.runIfActive(first) { visiblePeers.add("stale-peer") })
    check(visiblePeers.isEmpty())
    check(gate.stopCurrent { visiblePeers.clear() } == second)
    check(!gate.isActive(second))
    println("PASS: DNS-SD peer publication is serialized with loss, stop and restart")
}
