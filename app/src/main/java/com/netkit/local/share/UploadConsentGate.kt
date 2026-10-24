package com.netkit.local.share

/**
 * A consent generation is captured when a browser upload starts. Disabling and
 * re-enabling uploads, or switching away from and back to a SAF tree, must NOT
 * resurrect the old upload. The short final commit runs under the same lock as
 * consent changes; streaming bytes never holds this lock.
 */
internal class UploadConsentGate {
    private val lock = Any()
    private var generation = 0L

    fun <T> snapshot(readState: () -> T): Pair<Long, T> = synchronized(lock) {
        generation to readState()
    }

    fun change(updateState: () -> Unit) = synchronized(lock) {
        // Invalidate before any potentially throwing settings/storage write.
        generation++
        updateState()
    }

    fun <T> withCurrent(
        ticket: Long,
        stillAllowed: () -> Boolean,
        revoked: () -> Nothing,
        action: () -> T
    ): T = synchronized(lock) {
        if (generation != ticket || !stillAllowed()) revoked()
        action()
    }
}
