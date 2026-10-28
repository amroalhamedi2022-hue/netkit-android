package com.netkit.local.share

import java.io.IOException

/**
 * Makes failed SAF upload-stage cleanup observable.
 *
 * A provider returning false is not proof that the temporary document was removed.
 * Preserve the original transfer failure as the cause when cleanup also fails.
 */
internal object FailedUploadStageCleanup {
    fun onFailure(primaryFailure: Throwable?, deleteStage: () -> Boolean) {
        try {
            if (!deleteStage()) {
                throw IOException("Storage provider refused deletion of incomplete upload stage")
            }
        } catch (cleanupFailure: Exception) {
            val reportedCleanup = IOException(
                "Failed to confirm removal of incomplete SAF upload stage",
                cleanupFailure,
            )
            if (primaryFailure != null) {
                val combined = IOException(
                    "Upload failed and incomplete SAF stage cleanup also failed",
                    primaryFailure,
                )
                combined.addSuppressed(reportedCleanup)
                throw combined
            }
            throw reportedCleanup
        }
    }
}
