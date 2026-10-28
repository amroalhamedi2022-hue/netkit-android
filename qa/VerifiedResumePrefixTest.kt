import com.netkit.local.remote.VerifiedResumePrefix
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

private fun rejects(block: () -> Unit) = check(runCatching(block).isFailure)

fun main() {
    val payload = ByteArray(200_000) { ((it * 31) % 251).toByte() }
    for (prefix in listOf(1, 65536, 65537, payload.size)) {
        val source = ByteArrayInputStream(payload)
        val digest = MessageDigest.getInstance("SHA-256")
        val selected = DigestInputStream(source, digest)
        val confirmed = VerifiedResumePrefix.verify(selected, ByteArrayInputStream(payload.copyOf(prefix)), prefix.toLong())
        check(confirmed == prefix.toLong())
        val suffix = selected.readBytes()
        check(suffix.contentEquals(payload.copyOfRange(prefix, payload.size)))
        check(digest.digest().contentEquals(MessageDigest.getInstance("SHA-256").digest(payload)))
    }
    rejects { VerifiedResumePrefix.verify(ByteArrayInputStream(payload), ByteArrayInputStream(byteArrayOf()), 0) }
    rejects { VerifiedResumePrefix.verify(ByteArrayInputStream(payload), ByteArrayInputStream(byteArrayOf()), -1) }
    rejects { VerifiedResumePrefix.verify(ByteArrayInputStream(byteArrayOf(1)), ByteArrayInputStream(byteArrayOf(1, 2)), 2) }
    rejects { VerifiedResumePrefix.verify(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayInputStream(byteArrayOf(1)), 2) }
    rejects { VerifiedResumePrefix.verify(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayInputStream(byteArrayOf(9)), 1) }
    rejects { VerifiedResumePrefix.verify(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayInputStream(byteArrayOf(1, 2)), 1) }
    // A custom or broken provider must not claim more bytes than requested.
    fun oversizedRead(): InputStream = object : InputStream() {
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = len + 1
    }
    rejects { VerifiedResumePrefix.verify(ByteArrayInputStream(payload), oversizedRead(), 1) }
    rejects { VerifiedResumePrefix.verify(oversizedRead(), ByteArrayInputStream(payload.copyOf(1)), 1) }
    // Transient zero-length reads are tolerated without corrupting alignment.
    val flaky = object : ByteArrayInputStream(payload.copyOf(5)) {
        var zeros = 2
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (zeros-- > 0) 0 else super.read(b, off, len)
    }
    check(VerifiedResumePrefix.verify(ByteArrayInputStream(payload), flaky, 5) == 5L)
    // A stuck remote stream must not lock the transfer worker.
    val stalled = object : InputStream() {
        var calls = 0
        override fun read(): Int = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int { calls++; return 0 }
    }
    rejects { VerifiedResumePrefix.verify(ByteArrayInputStream(payload), stalled, 1) }
    check(stalled.calls == 32)
    println("PASS: verified FTP/SFTP resume prefix, SHA continuity, boundaries and stalled reads")
}
