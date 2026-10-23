package com.netkit.local.share

/** Monotonic session TTL: device wall-clock changes cannot prolong a private LAN share. */
class SessionLease(
    lifetimeMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime
) {
    init { require(lifetimeMillis in 1L..86_400_000L) }
    private val durationNanos = lifetimeMillis * 1_000_000L
    private val startedAt = nowNanos()
    @Volatile private var revoked = false
    fun revoke() { revoked = true }
    fun isValid(): Boolean {
        val elapsed = nowNanos() - startedAt
        return !revoked && elapsed >= 0L && elapsed < durationNanos
    }
    fun remainingMillis(): Long {
        if (revoked) return 0L
        val elapsed = nowNanos() - startedAt
        if (elapsed < 0 || elapsed >= durationNanos) return 0L
        return (durationNanos - elapsed + 999_999L) / 1_000_000L
    }
}
