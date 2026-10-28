package com.netkit.local.share

import java.io.IOException

fun main() {
    run {
        var called = false
        FailedUploadStageCleanup.onFailure(null) { called = true; true }
        check(called)
    }

    run {
        val failure = runCatching {
            FailedUploadStageCleanup.onFailure(null) { false }
        }.exceptionOrNull()
        check(failure is IOException)
        check(failure.message!!.contains("incomplete SAF upload stage"))
        check(failure.cause is IOException)
    }

    run {
        val primary = IOException("session expired")
        val cleanup = IllegalStateException("provider refused delete")
        val failure = runCatching {
            FailedUploadStageCleanup.onFailure(primary) { throw cleanup }
        }.exceptionOrNull()
        check(failure is IOException)
        check(failure.message!!.contains("cleanup also failed"))
        check(failure.cause === primary)
        check(failure.suppressed.size == 1)
        check(failure.suppressed.single().cause === cleanup)
    }

    run {
        val primary = IOException("upload hash mismatch")
        val failure = runCatching {
            FailedUploadStageCleanup.onFailure(primary) { false }
        }.exceptionOrNull()
        check(failure is IOException)
        check(failure.cause === primary)
        check(failure.suppressed.single().cause is IOException)
    }

    println("PASS: failed SAF upload cleanup is checked and never silently swallowed")
}
