package com.netkit.local.share

fun main() {
    val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
    check(UploadDestinationPolicy.canRestore(uri, uri, true, true, true, true))

    check(!UploadDestinationPolicy.canRestore(null, uri, true, true, true, true))
    check(!UploadDestinationPolicy.canRestore(uri, null, true, true, true, true))
    check(!UploadDestinationPolicy.canRestore(
        uri, "content://different-provider/tree/Downloads", true, true, true, true))
    check(!UploadDestinationPolicy.canRestore(uri, uri, false, true, true, true))
    check(!UploadDestinationPolicy.canRestore(uri, uri, true, false, true, true))
    check(!UploadDestinationPolicy.canRestore(uri, uri, true, true, false, true))
    check(!UploadDestinationPolicy.canRestore(uri, uri, true, true, true, false))
    check(!UploadDestinationPolicy.canRestore("", uri, true, true, true, true))

    println("PASS: SAF upload destination restore requires matching persisted read/write grant and writable directory")
}
