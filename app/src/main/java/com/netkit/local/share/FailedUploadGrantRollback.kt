package com.netkit.local.share

import java.io.IOException

/**
 * Makes rollback of newly acquired SAF destination grants observable.
 * The original selection failure remains the cause; rollback evidence is suppressed.
 */
internal object FailedUploadGrantRollback {
    fun onFailure(primaryFailure: Throwable, rollback: () -> Unit) {
        try {
            rollback()
        } catch (rollbackFailure: Exception) {
            val combined = IOException(
                "Upload destination selection failed and persisted grant rollback could not be confirmed",
                primaryFailure,
            )
            combined.addSuppressed(
                IOException("Failed to release or verify newly persisted SAF permission grant", rollbackFailure)
            )
            throw combined
        }
    }
}
