package com.netkit.local.connectivity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    var acquires = 0
    var releases = 0
    val lease = MulticastLockLease(
        acquireAction = { acquires++ },
        releaseAction = { releases++ }
    )
    lease.acquire()
    lease.acquire()
    check(lease.isAcquired && acquires == 1) { "Acquire must be idempotent" }
    lease.release()
    lease.release()
    check(!lease.isAcquired && releases == 1) { "Release must be idempotent" }

    var allowAcquire = false
    val retryable = MulticastLockLease(
        acquireAction = { check(allowAcquire) { "simulated permission/platform failure" } },
        releaseAction = {}
    )
    check(runCatching { retryable.acquire() }.isFailure)
    check(!retryable.isAcquired)
    allowAcquire = true
    retryable.acquire()
    check(retryable.isAcquired)

    var allowRelease = false
    val retryRelease = MulticastLockLease(
        acquireAction = {},
        releaseAction = { check(allowRelease) { "simulated release failure" } }
    )
    retryRelease.acquire()
    check(runCatching { retryRelease.release() }.isFailure)
    check(retryRelease.isAcquired) { "Failed release must remain retryable" }
    allowRelease = true
    retryRelease.release()
    check(!retryRelease.isAcquired)

    // Discovery stop and NSD failure callbacks may race. The lease must serialize
    // the platform side-effect with its state transition, not merely guard a flag.
    val concurrentAcquires = AtomicInteger()
    val concurrentReleases = AtomicInteger()
    val concurrentLease = MulticastLockLease(
        acquireAction = {
            concurrentAcquires.incrementAndGet()
            Thread.yield()
        },
        releaseAction = {
            concurrentReleases.incrementAndGet()
            Thread.yield()
        }
    )
    fun race(count: Int, action: () -> Unit) {
        val gate = CountDownLatch(1)
        val threads = List(count) {
            Thread {
                gate.await()
                action()
            }.apply { start() }
        }
        gate.countDown()
        threads.forEach {
            it.join(5_000)
            check(!it.isAlive) { "Concurrent lease operation deadlocked" }
        }
    }
    race(32) { concurrentLease.acquire() }
    check(concurrentLease.isAcquired && concurrentAcquires.get() == 1) {
        "Concurrent acquire must perform exactly one platform acquire"
    }
    race(32) { concurrentLease.release() }
    check(!concurrentLease.isAcquired && concurrentReleases.get() == 1) {
        "Concurrent release must perform exactly one platform release"
    }

    println("PASS: multicast lock lease idempotence, concurrent transitions and retry after platform failures")
}
