package com.netkit.local.connectivity

/**
 * Idempotent lease for Android multicast reception. The injected actions keep
 * acquire/release accounting testable on the JVM without an Android runtime.
 *
 * Lifecycle calls may race (for example, a user stopping discovery while an
 * NSD callback reports a failure). Serialize the platform action and state
 * transition together so the underlying WifiManager lock cannot be acquired
 * or released more than once for the same lease transition.
 */
internal class MulticastLockLease(
    private val acquireAction: () -> Unit,
    private val releaseAction: () -> Unit
) {
    private var acquired = false

    val isAcquired: Boolean
        get() = synchronized(this) { acquired }

    @Synchronized
    fun acquire() {
        if (acquired) return
        acquireAction()
        // Only mark held after the platform action succeeds, so a failed acquire can retry.
        acquired = true
    }

    @Synchronized
    fun release() {
        if (!acquired) return
        // Preserve the held state if the platform release throws; a later stop can retry.
        releaseAction()
        acquired = false
    }
}
