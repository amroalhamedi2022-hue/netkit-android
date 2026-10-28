package com.netkit.local.connectivity

/**
 * DNS-SD input policy and bounded asynchronous resolve bookkeeping.
 * Discovery labels and addresses remain untrusted; this is not authentication.
 */
object NsdDiscoveryPolicy {
    fun isNetKitServiceType(value: String): Boolean =
        value.removeSuffix(".").equals("_netkit._tcp", ignoreCase = true)

    /** Reject unsafe DNS-SD labels before resolve and again before publishing a peer. */
    fun isSafeAdvertisement(serviceType: String, serviceName: String): Boolean =
        isNetKitServiceType(serviceType) && PeerDisplayNamePolicy.isSafeServiceName(serviceName)
}

/** Deduplicates concurrent DNS-SD resolves and rejects stale callbacks after loss/stop. */
class NsdResolveLedger(
    private val maxPending: Int = 128,
    private val resolveTimeoutMillis: Long = 15_000,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private data class PendingResolve(val ticket: Long, val startedAtNanos: Long)
    private val pending = LinkedHashMap<String, PendingResolve>()
    private var nextTicket = 0L
    private val timeoutNanos = resolveTimeoutMillis * 1_000_000L

    init {
        require(maxPending in 1..4096) { "Invalid pending resolve limit" }
        require(resolveTimeoutMillis in 1..300_000) { "Invalid DNS-SD resolve timeout" }
    }

    private var lastObservedNanos: Long? = null

    /** A broken injected/platform clock fails closed instead of extending old resolve leases. */
    private fun readNowNanos(): Long? {
        val now = nowNanos()
        val previous = lastObservedNanos
        if (previous != null && now < previous) {
            pending.clear()
            lastObservedNanos = now
            return null
        }
        lastObservedNanos = now
        return now
    }

    val size: Int get() = synchronized(this) {
        val now = readNowNanos() ?: return@synchronized 0
        purgeExpired(now)
        pending.size
    }

    @Synchronized
    fun begin(serviceName: String): Long? {
        val now = readNowNanos() ?: return null
        purgeExpired(now)
        if (serviceName.isBlank() || serviceName in pending || pending.size >= maxPending) {
            return null
        }
        val ticket = allocateTicket()
        pending[serviceName] = PendingResolve(ticket, now)
        return ticket
    }

    @Synchronized
    fun isCurrent(serviceName: String, ticket: Long): Boolean {
        val now = readNowNanos() ?: return false
        purgeExpired(now)
        return pending[serviceName]?.ticket == ticket
    }

    @Synchronized
    fun finish(serviceName: String, ticket: Long): Boolean {
        val now = readNowNanos() ?: return false
        purgeExpired(now)
        if (pending[serviceName]?.ticket != ticket) return false
        pending.remove(serviceName)
        return true
    }

    @Synchronized
    fun invalidate(serviceName: String) {
        pending.remove(serviceName)
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }

    private fun purgeExpired(now: Long) {
        // resolveService normally calls back, but OEM/network failures must not
        // pin a slot forever. A timed-out ticket is invalidated, so a late
        // callback cannot consume a newer resolve for the same service name.
        pending.entries.removeAll { (_, entry) -> now - entry.startedAtNanos >= timeoutNanos }
    }

    private fun allocateTicket(): Long {
        repeat(maxPending + 1) {
            nextTicket = if (nextTicket == Long.MAX_VALUE) 1L else nextTicket + 1L
            if (pending.values.none { it.ticket == nextTicket }) return nextTicket
        }
        error("Unable to allocate a unique DNS-SD resolve ticket")
    }
}
