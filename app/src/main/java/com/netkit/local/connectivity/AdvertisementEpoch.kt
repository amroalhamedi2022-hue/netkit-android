package com.netkit.local.connectivity

/**
 * Generation fence for asynchronous DNS-SD registration callbacks.
 *
 * Android may report onServiceRegistered after the owner already stopped advertising.
 * A stale callback must not be allowed to keep an old LAN endpoint advertised or clear
 * a newer registration. Stop requests are tracked separately from the active generation
 * so an asynchronous unregistration failure remains observable after the epoch is fenced.
 */
internal class AdvertisementEpoch {
    private var next = 0L
    private var active: Long? = null
    private val pendingStops = LinkedHashSet<Long>()
    private val maxPendingStops = 32

    @Synchronized
    fun begin(): Long {
        next = if (next == Long.MAX_VALUE) 1L else next + 1L
        active = next
        return next
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = active == token

    @Synchronized
    fun stop(token: Long) {
        if (active == token) active = null
    }

    /**
     * Fence a requested stop while retaining enough state to report async failure.
     * @return true if a pending-stop ticket had to be evicted at the safety cap.
     */
    @Synchronized
    fun requestStop(token: Long): Boolean {
        if (active == token) active = null
        // Repeated callbacks must not refresh an old ticket's age and let it
        // displace a newer unresolved stop when the bounded ledger fills.
        if (token in pendingStops) return false
        pendingStops.add(token)
        var evicted = false
        while (pendingStops.size > maxPendingStops) {
            val iterator = pendingStops.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
                evicted = true
            }
        }
        return evicted
    }

    /** Returns true only for a stop that was actually awaiting a platform callback. */
    @Synchronized
    fun completeStop(token: Long): Boolean = pendingStops.remove(token)

    @Synchronized
    fun isStopPending(token: Long): Boolean = token in pendingStops
}
