package com.netkit.local.connectivity

fun main() {
    val epochs = AdvertisementEpoch()
    val first = epochs.begin()
    check(epochs.isCurrent(first))

    // An explicit stop fences the active advertisement and retains a bounded callback
    // ticket so a later onUnregistrationFailed callback can be surfaced to the user.
    check(epochs.requestStop(first)) { "Active stop request should create a callback ticket" }
    check(!epochs.isCurrent(first)) { "A stopped registration must be stale" }
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
    check(!epochs.requestStop(first)) {
        "A late registration callback must not resurrect a completed stop ticket"
    }
    check(!epochs.isStopPending(first)) {
        "Completed stop ticket unexpectedly re-entered the pending ledger"
    }

    epochs.requestStop(second)
    check(!epochs.isCurrent(second))
    check(epochs.isStopPending(second))
    check(epochs.completeStop(second))
    check(!epochs.isStopPending(second))
    // The stop-ticket cap is intentional, but evicting an older ticket must be
    // observable so the UI can warn that a delayed platform failure may go unreported.
    val bounded = AdvertisementEpoch()
    val issued = mutableListOf<Long>()
    var evictions = 0
    repeat(40) {
        val token = bounded.begin()
        issued += token
        if (bounded.requestStop(token)) evictions++
    }
    check(evictions == 8) { "Expected explicit overflow signal for eight evicted tickets" }
    check(issued.take(8).none { bounded.isStopPending(it) }) {
        "The oldest tickets should be evicted at the bounded safety cap"
    }
    check(issued.drop(8).all { bounded.isStopPending(it) }) {
        "The newest pending stops must remain tracked"
    }
    check(!bounded.completeStop(issued.first())) { "An evicted ticket must not be resurrected" }
    check(!bounded.requestStop(issued.first())) {
        "An evicted stale generation must not be reinserted by a late callback"
    }
    check(bounded.completeStop(issued.last())) { "Newest stop ticket should still complete" }

    // Re-requesting an already-pending stop must not refresh its age. Otherwise
    // repeated stale callbacks could keep an old ticket alive and evict a newer one.
    val ordered = AdvertisementEpoch()
    val orderedTickets = mutableListOf<Long>()
    repeat(32) {
        val token = ordered.begin()
        check(!ordered.requestStop(token))
        orderedTickets += token
    }
    check(!ordered.requestStop(orderedTickets.first())) {
        "A duplicate pending stop must not be counted as overflow or reordered"
    }
    val newest = ordered.begin()
    check(ordered.requestStop(newest)) { "The 33rd unique stop must signal ledger overflow" }
    check(!ordered.isStopPending(orderedTickets.first())) {
        "Duplicate request improperly refreshed the oldest ticket"
    }
    check(ordered.isStopPending(orderedTickets[1]))
    check(ordered.isStopPending(newest))

    println("PASS: DNS-SD advertisement epochs fence stale callbacks and retain stop-failure evidence")
}
