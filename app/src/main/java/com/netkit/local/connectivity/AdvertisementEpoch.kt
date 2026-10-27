package com.netkit.local.connectivity

/**
 * Generation fence for asynchronous DNS-SD registration callbacks.
 *
 * Android may report onServiceRegistered after the owner already stopped advertising.
 * A stale callback must not be allowed to keep an old LAN endpoint advertised or clear
 * a newer registration.
 */
internal class AdvertisementEpoch {
    private var next = 0L
    private var active: Long? = null

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
}
