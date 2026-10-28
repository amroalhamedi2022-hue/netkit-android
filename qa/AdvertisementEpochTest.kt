package com.netkit.local.connectivity

fun main() {
    val epochs = AdvertisementEpoch()
    val first = epochs.begin()
    check(epochs.isCurrent(first))

    // Stop may happen before Android calls onServiceRegistered.
    epochs.stop(first)
    check(!epochs.isCurrent(first)) { "A stopped pending registration must be stale" }

    // An explicit stop fences the old advertisement but retains a bounded callback
    // ticket so a later onUnregistrationFailed callback can be surfaced to the user.
    epochs.requestStop(first)
    check(!epochs.isCurrent(first))
    check(epochs.isStopPending(first)) { "Stop request must remain observable until callback" }

    val second = epochs.begin()
    check(second != first)
    check(epochs.isCurrent(second))
    check(!epochs.isCurrent(first)) { "Late callback must not revive the old advertisement" }

    // Completing an old stop cannot clear the new active registration.
    check(epochs.completeStop(first)) { "Old stop completion should consume its own ticket" }
    check(!epochs.isStopPending(first))
    check(epochs.isCurrent(second)) { "Old unregistration callback cleared the new advertisement" }
    check(!epochs.completeStop(first)) { "A duplicate callback must be idempotently ignored" }

    epochs.requestStop(second)
    check(!epochs.isCurrent(second))
    check(epochs.isStopPending(second))
    check(epochs.completeStop(second))
    check(!epochs.isStopPending(second))
    println("PASS: DNS-SD advertisement epochs fence stale callbacks and retain stop-failure evidence")
}
