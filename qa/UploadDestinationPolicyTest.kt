package com.netkit.local.share

fun main() {
    val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
    check(UploadDestinationPolicy.hasPersistedGrant(uri, uri, true, true))
    check(UploadDestinationPolicy.canRestore(uri, uri, true, true, true, true))

    // A persisted grant remains valid even when a removable/cloud provider is offline.
    // Provider usability is a separate check and must not erase the user's saved URI.
    check(UploadDestinationPolicy.hasPersistedGrant(uri, uri, true, true))
    check(!UploadDestinationPolicy.canRestore(uri, uri, true, true, false, true))
    check(!UploadDestinationPolicy.canRestore(uri, uri, true, true, true, false))

    check(!UploadDestinationPolicy.hasPersistedGrant(null, uri, true, true))
    check(!UploadDestinationPolicy.hasPersistedGrant("", uri, true, true))
    check(!UploadDestinationPolicy.hasPersistedGrant(uri, null, true, true))
    check(!UploadDestinationPolicy.hasPersistedGrant(
        uri, "content://different-provider/tree/Downloads", true, true))
    check(!UploadDestinationPolicy.hasPersistedGrant(uri, uri, false, true))
    check(!UploadDestinationPolicy.hasPersistedGrant(uri, uri, true, false))

    check(!UploadDestinationPolicy.canRestore(null, uri, true, true, true, true))
    check(!UploadDestinationPolicy.canRestore(uri, null, true, true, true, true))
    check(!UploadDestinationPolicy.canRestore(
        uri, "content://different-provider/tree/Downloads", true, true, true, true))
    check(!UploadDestinationPolicy.canRestore(uri, uri, false, true, true, true))
    check(!UploadDestinationPolicy.canRestore(uri, uri, true, false, true, true))
    check(!UploadDestinationPolicy.canRestore("", uri, true, true, true, true))

    check(UploadDestinationPolicy.newlyGranted(
        PersistedGrantFlags(false, false), PersistedGrantFlags(true, true)
    ) == PersistedGrantFlags(true, true))
    check(UploadDestinationPolicy.newlyGranted(
        PersistedGrantFlags(true, false), PersistedGrantFlags(true, true)
    ) == PersistedGrantFlags(false, true)) // preserve the pre-existing read grant
    check(UploadDestinationPolicy.newlyGranted(
        PersistedGrantFlags(true, true), PersistedGrantFlags(true, true)
    ) == PersistedGrantFlags(false, false)) // never revoke a grant we did not add

    println("PASS: exact SAF grant identity is independent from provider availability; usable restore still requires a writable directory")
}
