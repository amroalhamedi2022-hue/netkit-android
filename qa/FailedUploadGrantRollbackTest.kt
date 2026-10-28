package com.netkit.local.share

import java.io.IOException

fun main() {
    run {
        val primary = IOException("settings commit failed")
        var rolledBack = false
        FailedUploadGrantRollback.onFailure(primary) { rolledBack = true }
        check(rolledBack)
    }

    run {
        val primary = IOException("destination validation failed")
        val providerFailure = SecurityException("release denied")
        val failure = runCatching {
            FailedUploadGrantRollback.onFailure(primary) { throw providerFailure }
        }.exceptionOrNull()
        check(failure is IOException)
        check(failure.message!!.contains("rollback could not be confirmed"))
        check(failure.cause === primary)
        check(failure.suppressed.size == 1)
        check(failure.suppressed.single().cause === providerFailure)
    }

    run {
        val primary = IOException("persisted grant lookup failed")
        val failure = runCatching {
            FailedUploadGrantRollback.onFailure(primary) {
                throw IOException("provider state unavailable")
            }
        }.exceptionOrNull()
        check(failure is IOException && failure.cause === primary)
        check(failure.suppressed.single().message!!.contains("release or verify"))
    }

    println("PASS: SAF destination grant rollback preserves primary failure and surfaces cleanup failure")
}
