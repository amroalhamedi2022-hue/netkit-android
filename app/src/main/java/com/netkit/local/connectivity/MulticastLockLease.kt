package com.netkit.local.connectivity

/**
 * Idempotent lease for Android multicast reception. The injected actions keep
 * acquire/release accounting testable on the JVM without an Android runtime.
 */
internal class MulticastLockLease(
    private val acquireAction: () -> Unit,
    private val releaseAction: () -> Unit
) {
    private var acquired = false
    val isAcquired: Boolean get() = acquired

    fun acquire() {
        if (acquired) return
        acquireAction()
        // Only mark held after the platform action succeeds, so a failed acquire can retry.
        acquired = true
    }

    fun release() {
        if (!acquired) return
        // Preserve the held state if the platform release throws; a later stop can retry.
        releaseAction()
        acquired = false
    }
}
