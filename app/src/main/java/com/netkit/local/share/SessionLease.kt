package com.netkit.local.share

/**
 * Monotonic session TTL: wall-clock changes cannot prolong a private LAN share.
 *
 * If the monotonic source ever moves backwards, this lease is permanently invalid.
 * Re-enabling it after the clock recovers could resurrect an already-expired share
 * token. The owner must create a new session/lease instead.
 */
class SessionLease(
    lifetimeMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime
) {
    init { require(lifetimeMillis in 1L..86_400_000L) }
    private val durationNanos = lifetimeMillis * 1_000_000L
    private val startedAt = nowNanos()
    private var lastObservedNanos = startedAt
    private var revoked = false
    private var clockBroken = false

    @Synchronized
    fun revoke() {
        revoked = true
    }

    @Synchronized
    fun isValid(): Boolean {
        val elapsed = elapsedNanosOrNull() ?: return false
        return elapsed < durationNanos
    }

    @Synchronized
    fun remainingMillis(): Long {
        val elapsed = elapsedNanosOrNull() ?: return 0L
        if (elapsed >= durationNanos) return 0L
        return (durationNanos - elapsed + 999_999L) / 1_000_000L
    }

    /** Called only while synchronized by the public lease operations. */
    private fun elapsedNanosOrNull(): Long? {
        if (revoked || clockBroken) return null
        val now = nowNanos()
        if (now < lastObservedNanos) {
            // Fail closed permanently; do not let a recovered test/platform clock
            // reactivate the same bearer-token session.
            clockBroken = true
            return null
        }
        lastObservedNanos = now
        val elapsed = now - startedAt
        if (elapsed < 0L) {
            // Defensive overflow guard for an invalid injected source or an
            // implausibly long-running lease.
            clockBroken = true
            return null
        }
        return elapsed
    }
}
