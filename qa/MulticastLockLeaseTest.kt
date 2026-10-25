package com.netkit.local.connectivity

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

    println("PASS: multicast lock lease idempotence and retry after platform failures")
}
