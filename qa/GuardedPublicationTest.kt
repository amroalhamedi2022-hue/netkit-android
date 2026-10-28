package com.netkit.local.share

import java.io.IOException

fun main() {
    run {
        var checks = 0
        var published = false
        val result = GuardedPublication.publish(
            ensureAuthorized = { checks++ },
            publish = { published = true; "content://provider/document/renamed" },
            rollback = { error("rollback must not run on success") },
        )
        check(result == "content://provider/document/renamed")
        check(checks == 2 && published)
    }

    run {
        val expired = IOException("session expired")
        var checks = 0
        var rolledBack: String? = null
        val failure = runCatching {
            GuardedPublication.publish(
                ensureAuthorized = { if (++checks == 2) throw expired },
                publish = { "content://provider/document/new-uri-after-rename" },
                rollback = { rolledBack = it },
            )
        }.exceptionOrNull()
        check(failure === expired) { "Original authorization failure should be preserved" }
        check(rolledBack == "content://provider/document/new-uri-after-rename") {
            "Rollback must target the provider's post-rename URI"
        }
    }

    run {
        val expired = IOException("session expired")
        val cleanup = IllegalStateException("provider refused delete")
        var checks = 0
        val failure = runCatching {
            GuardedPublication.publish(
                ensureAuthorized = { if (++checks == 2) throw expired },
                publish = { "published-uri" },
                rollback = { throw cleanup },
            )
        }.exceptionOrNull()
        check(failure is IOException)
        check(failure.message!!.contains("rollback failed"))
        check(failure.cause === cleanup)
        check(failure.suppressed.any { it === expired })
    }

    run {
        var publishCalled = false
        var rollbackCalled = false
        val failure = runCatching {
            GuardedPublication.publish(
                ensureAuthorized = { throw IOException("expired before publication") },
                publish = { publishCalled = true; "never-published" },
                rollback = { rollbackCalled = true },
            )
        }.exceptionOrNull()
        check(failure is IOException)
        check(!publishCalled && !rollbackCalled)
    }

    run {
        var rollbackCalled = false
        val failure = runCatching {
            GuardedPublication.publish(
                ensureAuthorized = {},
                publish = { throw IOException("provider rename failed") },
                rollback = { rollbackCalled = true },
            )
        }.exceptionOrNull()
        check(failure is IOException)
        check(failure.message == "provider rename failed")
        check(!rollbackCalled)
    }

    println("PASS: SAF publication checks authorization before and after rename and rolls back the returned URI on expiry")
}
