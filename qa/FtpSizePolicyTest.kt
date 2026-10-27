import com.netkit.local.remote.FtpSizePolicy

fun main() {
    fun reject(raw: String) {
        check(runCatching { FtpSizePolicy.parseReply(raw) }.isFailure) { "Accepted malformed SIZE: $raw" }
    }
    check(FtpSizePolicy.parseReply(null) == null)
    check(FtpSizePolicy.parseReply("500 Command not understood\r\n") == null)
    check(FtpSizePolicy.parseReply("550 No such file\r\n") == null)
    check(FtpSizePolicy.parseReply("213 0\r\n") == 0L)
    check(FtpSizePolicy.parseReply("213 123456789\r\n") == 123456789L)
    check(FtpSizePolicy.parseReply("213\t42\n") == 42L)
    check(FtpSizePolicy.parseReply("213 9223372036854775807") == Long.MAX_VALUE)
    for (raw in listOf(
        "213", "213 -1", "213 +3", "213 3 trailing", "213 2\n213 3",
        "213 9223372036854775808", "213 999999999999999999999",
        "213 12\r\n550 error", "213 1.5", "213 0x10",
        "213 2\r", "213 2\r\n\r\n", "213 2\n\n", "213 2\r213 3\r\n"
    )) reject(raw)
    var sizeCalls = 0
    check(FtpSizePolicy.preferMlst(0L) { sizeCalls++; "213 9" } == 0L)
    check(FtpSizePolicy.preferMlst(7L) { sizeCalls++; "213 9" } == 7L)
    check(sizeCalls == 0) { "SIZE queried even though MLST was valid" }
    check(FtpSizePolicy.preferMlst(null) { sizeCalls++; "213 9\r\n" } == 9L)
    check(FtpSizePolicy.preferMlst(-1L) { sizeCalls++; "213 11\r\n" } == 11L)
    check(sizeCalls == 2)
    check(FtpSizePolicy.preferMlst(null) { "500 Unsupported" } == null)
    check(runCatching { FtpSizePolicy.preferMlst(null) { "213 9 bad" } }.isFailure)
    println("PASS: FTP MLST/SIZE framing, strict size parsing, overflow and invalid reply rejection")
}
