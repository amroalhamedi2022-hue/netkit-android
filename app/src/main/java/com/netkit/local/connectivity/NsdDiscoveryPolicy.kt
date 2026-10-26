package com.netkit.local.connectivity

/**
 * DNS-SD input policy and bounded asynchronous resolve bookkeeping.
 * Discovery labels and addresses remain untrusted; this is not authentication.
 */
object NsdDiscoveryPolicy {
    fun isNetKitServiceType(value: String): Boolean =
        value.removeSuffix(".").equals("_netkit._tcp", ignoreCase = true)
}

/** Deduplicates concurrent DNS-SD resolves and rejects stale callbacks after loss/stop. */
class NsdResolveLedger(private val maxPending: Int = 128) {
    private val pending = LinkedHashMap<String, Long>()
    private var nextTicket = 0L

    init {
        require(maxPending in 1..4096) { "Invalid pending resolve limit" }
    }

    val size: Int get() = pending.size

    @Synchronized
    fun begin(serviceName: String): Long? {
        if (serviceName.isBlank() || serviceName in pending || pending.size >= maxPending) {
            return null
        }
        val ticket = allocateTicket()
        pending[serviceName] = ticket
        return ticket
    }

    @Synchronized
    fun isCurrent(serviceName: String, ticket: Long): Boolean =
        pending[serviceName] == ticket

    @Synchronized
    fun finish(serviceName: String, ticket: Long): Boolean {
        if (pending[serviceName] != ticket) return false
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

    private fun allocateTicket(): Long {
        repeat(maxPending + 1) {
            nextTicket = if (nextTicket == Long.MAX_VALUE) 1L else nextTicket + 1L
            if (nextTicket !in pending.values) return nextTicket
        }
        error("Unable to allocate a unique DNS-SD resolve ticket")
    }
}
