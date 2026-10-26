package com.netkit.local.transfer

data class TransferSnapshot(
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Double?,
    val estimatedSecondsRemaining: Long?
)

/** Throughput and ETA are derived exclusively from real cumulative bytes and monotonic time. */
class TransferTelemetry(
    private val nowNanos: () -> Long = System::nanoTime,
    private val smoothing: Double = 0.3,
    private val minimumSampleMillis: Long = 150
) {
    init {
        require(smoothing > 0.0 && smoothing <= 1.0)
        require(minimumSampleMillis in 1..10_000)
    }
    private val lock = Any()
    private var lastNanos = nowNanos()
    private var lastBytes = 0L
    private var rate: Double? = null

    fun sample(bytes: Long, totalBytes: Long? = null): TransferSnapshot = synchronized(lock) {
        require(bytes >= 0L)
        require(totalBytes == null || totalBytes >= 0L)
        require(totalBytes == null || bytes <= totalBytes) {
            "Transferred bytes exceed the declared total"
        }
        require(bytes >= lastBytes) { "Transfer byte count went backwards" }
        val now = nowNanos()
        val elapsed = now - lastNanos
        val minNanos = minimumSampleMillis * 1_000_000L
        if (elapsed >= minNanos && elapsed > 0) {
            val instant = (bytes - lastBytes).toDouble() * 1_000_000_000.0 / elapsed
            rate = rate?.let { smoothing * instant + (1.0 - smoothing) * it } ?: instant
            lastNanos = now
            lastBytes = bytes
        }
        val speed = rate?.takeIf { it > 1e-9 && it.isFinite() }
        val remaining = totalBytes?.let { (it - bytes).coerceAtLeast(0L) }
        val eta = if (remaining == 0L) 0L
            else if (remaining != null && speed != null) (remaining / speed).toLong().coerceAtLeast(0L)
            else null
        TransferSnapshot(bytes, totalBytes, speed ?: 0.0.takeIf { rate != null }, eta)
    }
}
