import com.netkit.local.remote.RemoteDigestVerifier
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

fun main() {
    val bytes = ByteArray(1024 * 1024 + 31) { ((it * 31) % 251).toByte() }
    val local = MessageDigest.getInstance("SHA-256").digest(bytes)
    val remote = RemoteDigestVerifier.digest(ByteArrayInputStream(bytes))
    RemoteDigestVerifier.requireMatch(local, remote)
    check(remote.contentEquals(local))

    val changed = bytes.clone().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
    val altered = RemoteDigestVerifier.digest(ByteArrayInputStream(changed))
    check(runCatching { RemoteDigestVerifier.requireMatch(local, altered) }
        .exceptionOrNull() is IllegalArgumentException)

    // Short transient zero reads are legal with some network/SAF providers.
    val intermittent = object : InputStream() {
        val delegate = ByteArrayInputStream(bytes)
        var zeros = 0
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (zeros++ < 2) return 0
            return delegate.read(b, off, len)
        }
    }
    check(RemoteDigestVerifier.digest(intermittent).contentEquals(local))
    val stuck = object : InputStream() {
        override fun read(): Int = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int = 0
    }
    check(runCatching { RemoteDigestVerifier.digest(stuck) }.exceptionOrNull() is IOException)
    check(runCatching { RemoteDigestVerifier.requireMatch(byteArrayOf(1), local) }
        .exceptionOrNull() is IllegalArgumentException)
    println("PASS: FTP/SFTP SHA-256 readback equality, tampering, zero-read recovery and bounded stalls")
}
