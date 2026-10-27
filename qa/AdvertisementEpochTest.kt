package com.netkit.local.connectivity

fun main() {
    val epochs = AdvertisementEpoch()
    val first = epochs.begin()
    check(epochs.isCurrent(first))

    // Stop may happen before Android calls onServiceRegistered.
    epochs.stop(first)
    check(!epochs.isCurrent(first)) { "A stopped pending registration must be stale" }

    val second = epochs.begin()
    check(second != first)
    check(epochs.isCurrent(second))
    check(!epochs.isCurrent(first)) { "A late callback must not revive the old advertisement" }

    // A late unregistration/failure callback for the old listener must not clear the new one.
    epochs.stop(first)
    check(epochs.isCurrent(second)) { "Stale callback must not cancel a newer advertisement" }

    epochs.stop(second)
    check(!epochs.isCurrent(second))
    println("PASS: stale DNS-SD registration callbacks cannot revive or clear a newer advertisement")
}
