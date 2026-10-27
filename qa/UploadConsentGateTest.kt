package com.netkit.local.share

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

fun main() {
    val gate = UploadConsentGate()
    var enabled = true
    var tree = "folder-a"
    fun allowed(expected: String) = { enabled && tree == expected }
    fun revoked(): Nothing = error("revoked")

    val (first, firstTree) = gate.snapshot { tree }
    gate.withCurrent(first, allowed(firstTree), ::revoked) { check(true) }
    gate.change { enabled = false }
    gate.change { enabled = true }
    check(runCatching { gate.withCurrent(first, allowed(firstTree), ::revoked) { } }.isFailure)

    val (second, secondTree) = gate.snapshot { tree }
    gate.change { tree = "folder-b" }
    gate.change { tree = "folder-a" }
    check(runCatching { gate.withCurrent(second, allowed(secondTree), ::revoked) { } }.isFailure)

    val (third, thirdTree) = gate.snapshot { tree }
    val insideCommit = CountDownLatch(1)
    val releaseCommit = CountDownLatch(1)
    val changed = CountDownLatch(1)
    val committing = thread {
        gate.withCurrent(third, allowed(thirdTree), ::revoked) {
            insideCommit.countDown()
            check(releaseCommit.await(2, TimeUnit.SECONDS))
        }
    }
    check(insideCommit.await(2, TimeUnit.SECONDS))
    val changer = thread {
        gate.change { enabled = false }
        changed.countDown()
    }
    check(!changed.await(100, TimeUnit.MILLISECONDS)) { "Consent changed during atomic commit" }
    releaseCommit.countDown()
    committing.join(); changer.join()
    check(changed.count == 0L)
    check(runCatching { gate.withCurrent(third, allowed(thirdTree), ::revoked) { } }.isFailure)
    // Changing the configured upload ceiling is a consent-generation change too.
    enabled = true
    var maxUploadMiB = 2048
    val (beforeLimitChange, _) = gate.snapshot { maxUploadMiB }
    gate.change { maxUploadMiB = 512 }
    check(runCatching {
        gate.withCurrent(beforeLimitChange, allowed("folder-a"), ::revoked) { }
    }.isFailure) { "Upload-limit changes must revoke staged browser uploads" }
    val (afterLimitChange, capturedLimit) = gate.snapshot { maxUploadMiB }
    check(capturedLimit == 512)
    gate.withCurrent(afterLimitChange, allowed("folder-a"), ::revoked) { check(true) }

    println("PASS: consent revocation survives toggles/tree reuse/upload-limit changes; commit serialized")
}
