package com.netkit.local.connectivity

/**
 * Tracks asynchronous DNS-SD resolution work. This prevents duplicate resolves
 * and stale callbacks from repopulating discovery results after a peer is lost
 * or a scan is stopped/restarted. It does not authenticate a peer.
 */
internal class PeerDiscoveryRegistry {
    data class Ticket internal constructor(
        val generation: Long,
        val serviceName: String,
        val requestId: Long
    )

    private var generation = 0L
    private var nextRequestId = 0L
    private val activeServices = mutableSetOf<String>()
    private val pendingRequests = mutableMapOf<String, Long>()

    fun beginScan(): Long {
        generation++
        activeServices.clear()
        pendingRequests.clear()
        return generation
    }

    fun requestResolve(scanGeneration: Long, serviceName: String): Ticket? {
        if (scanGeneration != generation || serviceName.isBlank()) return null
        if (!activeServices.add(serviceName)) return null
        val requestId = ++nextRequestId
        pendingRequests[serviceName] = requestId
        return Ticket(scanGeneration, serviceName, requestId)
    }

    fun isCurrent(ticket: Ticket): Boolean =
        ticket.generation == generation &&
            ticket.serviceName in activeServices &&
            pendingRequests[ticket.serviceName] == ticket.requestId

    /** A successful resolution remains active, but its asynchronous ticket is consumed. */
    fun markResolved(ticket: Ticket): Boolean {
        if (!isCurrent(ticket)) return false
        pendingRequests.remove(ticket.serviceName)
        return true
    }

    /** A failed resolve can be retried if DNS-SD reports the service again. */
    fun markResolveFailed(ticket: Ticket): Boolean {
        if (!isCurrent(ticket)) return false
        pendingRequests.remove(ticket.serviceName)
        activeServices.remove(ticket.serviceName)
        return true
    }

    fun markLost(scanGeneration: Long, serviceName: String): Boolean {
        if (scanGeneration != generation) return false
        val wasActive = activeServices.remove(serviceName)
        pendingRequests.remove(serviceName)
        return wasActive
    }

    fun stop(scanGeneration: Long? = null) {
        if (scanGeneration != null && scanGeneration != generation) return
        generation++
        activeServices.clear()
        pendingRequests.clear()
    }
}
