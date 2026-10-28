package com.netkit.local.share

import java.io.IOException

/**
 * Closes the authorization race around publishing a completed SAF upload.
 *
 * The caller must return the provider's post-rename URI from [publish], not the
 * stale staging URI. If authorization is revoked during publication, rollback
 * targets that exact URI. Failure to roll back is surfaced as an I/O failure,
 * never disguised as an ordinary expired-session response.
 */
internal object GuardedPublication {
    fun <T> publish(
        ensureAuthorized: () -> Unit,
        publish: () -> T,
        rollback: (T) -> Unit,
    ): T {
        ensureAuthorized()
        val published = publish()
        try {
            ensureAuthorized()
        } catch (authorizationFailure: Exception) {
            try {
                rollback(published)
            } catch (rollbackFailure: Exception) {
                val failure = IOException(
                    "Upload authorization expired after publication; rollback failed, verify destination",
                    rollbackFailure,
                )
                failure.addSuppressed(authorizationFailure)
                throw failure
            }
            throw authorizationFailure
        }
        return published
    }
}
