package com.netkit.local.share

fun main() {
    val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
    check(UploadDestinationPolicy.canRestore(uri, uri, true, true, true, true))

    val rejected = listOf(
        arrayOf(null, uri, true, true, true, true),
        arrayOf(uri, null, true, true, true, true),
        arrayOf(uri, "content://different-provider/tree/Downloads", true, true, true, true),
        arrayOf(uri, uri, false, true, true, true),
        arrayOf(uri, uri, true, false, true, true),
        arrayOf(uri, uri, true, true, false, true),
        arrayOf(uri, uri, true, true, true, false),
        arrayOf("", uri, true, true, true, true),
    )
    rejected.forEachIndexed { index, values ->
        @Suppress("UNCHECKED_CAST")
        val args = values as Array<Any?>
        check(!UploadDestinationPolicy.canRestore(
            args[0] as String?, args[1] as String?,
            args[2] as Boolean, args[3] as Boolean,
            args[4] as Boolean, args[5] as Boolean
        )) { "Invalid persisted upload destination case $index was accepted" }
    }
    println("PASS: SAF upload destination restore requires matching persisted read/write grant and writable directory")
}
