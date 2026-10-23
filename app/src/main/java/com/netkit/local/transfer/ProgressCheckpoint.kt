package com.netkit.local.transfer

/**
 * Limits synchronous transfer-journal writes during high-throughput streams.
 * flush() must run after success or failure so that the final observed count persists.
 */
class ProgressCheckpoint(
    private val commit: (Long) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    intervalMillis: Long = 250L
) {
    init { require(intervalMillis in 10L..10_000L) }
    private val intervalNanos = intervalMillis * 1_000_000L
    private var deadline = nowNanos() + intervalNanos
    private var lastObserved = 0L
    private var lastCommitted = 0L

    @Synchronized fun onBytes(bytes: Long) {
        require(bytes >= lastObserved) { "Transfer stream progress cannot go backward" }
        lastObserved = bytes
        val now = nowNanos()
        if (bytes != lastCommitted && now - deadline >= 0L) {
            commit(bytes)
            lastCommitted = bytes
            deadline = now + intervalNanos
        }
    }
    @Synchronized fun flush() {
        if (lastObserved != lastCommitted) {
            commit(lastObserved)
            lastCommitted = lastObserved
        }
    }
}
