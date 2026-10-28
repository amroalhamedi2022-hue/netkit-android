package com.netkit.local.share

/**
 * Pure policy for restoring a browser-upload destination after process restart.
 * A saved URI is not authority by itself: the exact URI must still have persisted
 * read/write grants. A provider can be temporarily unavailable even while those
 * grants remain valid, so grant validity is deliberately separate from usability.
 */
internal object UploadDestinationPolicy {
    fun hasPersistedGrant(
        savedUri: String?,
        persistedGrantUri: String?,
        hasReadPermission: Boolean,
        hasWritePermission: Boolean,
    ): Boolean =
        !savedUri.isNullOrBlank() &&
            savedUri == persistedGrantUri &&
            hasReadPermission &&
            hasWritePermission

    fun canRestore(
        savedUri: String?,
        persistedGrantUri: String?,
        hasReadPermission: Boolean,
        hasWritePermission: Boolean,
        isDirectory: Boolean,
        canWrite: Boolean,
    ): Boolean =
        hasPersistedGrant(savedUri, persistedGrantUri, hasReadPermission, hasWritePermission) &&
            isDirectory &&
            canWrite

    /** Flags newly acquired by this selection; existing grants must not be revoked. */
    fun newlyGranted(previous: PersistedGrantFlags, current: PersistedGrantFlags) =
        PersistedGrantFlags(
            read = current.read && !previous.read,
            write = current.write && !previous.write
        )
}

internal data class PersistedGrantFlags(val read: Boolean, val write: Boolean)
