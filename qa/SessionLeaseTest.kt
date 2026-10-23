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
    println("PASS: session expiry, wall-clock isolation, revocation and invalid duration")
}
