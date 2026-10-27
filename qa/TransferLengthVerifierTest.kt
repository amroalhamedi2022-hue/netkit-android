import com.netkit.local.remote.TransferLengthVerifier

fun main() {
    TransferLengthVerifier.requireMatch(0L, 0L)
    TransferLengthVerifier.requireMatch(4096L, 4096L)
    TransferLengthVerifier.requireMatch(null, 12345L) // null alone means the server did not report a usable size
    check(runCatching { TransferLengthVerifier.requireMatch(-1L, 12345L) }
        .exceptionOrNull() is IllegalArgumentException) // a malformed negative size must not disable verification
    check(runCatching { TransferLengthVerifier.requireMatch(100L, 99L) }.isFailure)
    check(runCatching { TransferLengthVerifier.requireMatch(100L, 101L) }.isFailure)
    check(runCatching { TransferLengthVerifier.requireMatch(0L, 1L) }.isFailure)
    check(runCatching { TransferLengthVerifier.requireMatch(null, -1L) }.isFailure)
    println("FTP/SFTP transfer length consistency: PASS")
}
