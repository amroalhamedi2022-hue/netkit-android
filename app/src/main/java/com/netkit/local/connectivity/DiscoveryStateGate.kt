package com.netkit.local.connectivity

/**
 * Serializes discovery lifecycle transitions with peer publication.
 * Android NSD callbacks can race: a resolve may finish just as a service is
 * lost or discovery is stopped. Actions run under this gate so a stale result
 * cannot repopulate the visible peer list after the invalidating event.
 */
internal class DiscoveryStateGate {
    private var nextEpoch = 0L
    private var activeEpoch: Long? = null

    @Synchronized
    fun begin(onBegin: (Long) -> Unit): Long? {
        if (activeEpoch != null) return null
        nextEpoch = if (nextEpoch == Long.MAX_VALUE) 1L else nextEpoch + 1L
        val epoch = nextEpoch
        activeEpoch = epoch
        try {
            onBegin(epoch)
        } catch (failure: Throwable) {
            activeEpoch = null
            throw failure
        }
        return epoch
    }

    @Synchronized
    fun isActive(epoch: Long): Boolean = activeEpoch == epoch

    @Synchronized
    fun runIfActive(epoch: Long, action: () -> Unit): Boolean {
        if (activeEpoch != epoch) return false
        action()
        return true
    }

    @Synchronized
    fun <T> withActive(epoch: Long, action: () -> T): T? {
        if (activeEpoch != epoch) return null
        return action()
    }

    /** The action returns false when the callback is not the owner of this epoch. */
    @Synchronized
    fun stop(epoch: Long, action: () -> Boolean): Boolean {
        if (activeEpoch != epoch) return false
        val shouldStop = try {
            action()
        } catch (failure: Throwable) {
            activeEpoch = null
            throw failure
        }
        if (!shouldStop) return false
        activeEpoch = null
        return true
    }

    /** Stop the current session even if Android already cleared its callback. */
    @Synchronized
    fun stopCurrent(action: () -> Unit): Long? {
        val previous = activeEpoch
        activeEpoch = null
        action()
        return previous
    }
}
