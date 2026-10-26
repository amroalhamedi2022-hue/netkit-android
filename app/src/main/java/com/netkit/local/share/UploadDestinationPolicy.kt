package com.netkit.local.share

/**
 * Pure acceptance policy for restoring a browser-upload destination after process restart.
 * A saved URI is not authority by itself: the exact URI must still have persisted read/write
 * grants and must still resolve to a writable directory.
 */
internal object UploadDestinationPolicy {
    fun canRestore(
        savedUri: String?,
        persistedGrantUri: String?,
        hasReadPermission: Boolean,
        hasWritePermission: Boolean,
        isDirectory: Boolean,
        canWrite: Boolean,
    ): Boolean =
        !savedUri.isNullOrBlank() &&
            savedUri == persistedGrantUri &&
            hasReadPermission &&
            hasWritePermission &&
            isDirectory &&
            canWrite
}
