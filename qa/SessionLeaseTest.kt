import com.netkit.local.share.SessionLease

fun main() {
    var now = 5_000_000_000L
    val lease = SessionLease(1000L) { now }
    check(lease.isValid() && lease.remainingMillis() == 1000L)
    now += 500_000_000L
    check(lease.isValid() && lease.remainingMillis() == 500L)
    now += 500_000_000L
    check(!lease.isValid() && lease.remainingMillis() == 0L)
    now = 0L
    check(!lease.isValid())
    val revoked = SessionLease(1_000_000L) { now }
    revoked.revoke()
    check(!revoked.isValid())
    check(runCatching { SessionLease(0L) }.isFailure)
    check(runCatching { SessionLease(86_400_001L) }.isFailure)

    // A rollback must not temporarily expire and then resurrect the same HTTPS lease.
    var rollbackNow = 10_000_000_000L
    val rollbackLease = SessionLease(60_000L) { rollbackNow }
    check(rollbackLease.isValid())
    rollbackNow += 1_000_000_000L
    check(rollbackLease.remainingMillis() == 59_000L)
    rollbackNow -= 1_500_000_000L // below the last observation, but still after lease start
    check(!rollbackLease.isValid())
    check(rollbackLease.remainingMillis() == 0L)
    rollbackNow = 10_500_000_000L // clock recovers above lease start
    check(!rollbackLease.isValid())
    check(rollbackLease.remainingMillis() == 0L)

    println("PASS: session expiry, revocation, bounded lifetime and permanent fail-closed clock rollback")
}
