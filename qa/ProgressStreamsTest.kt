import com.netkit.local.remote.ProgressInputStream
import com.netkit.local.remote.ProgressOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

fun main() {
    val bytes = ByteArray(257 * 1024) { (it % 251).toByte() }
    var inputCount = 0L
    var outputCount = 0L
    val output = ByteArrayOutputStream()
    ProgressInputStream(ByteArrayInputStream(bytes)) { inputCount = it }.use { input ->
        ProgressOutputStream(output) { outputCount = it }.use { out ->
            input.copyTo(out, 8192)
        }
    }
    check(inputCount == bytes.size.toLong())
    check(outputCount == bytes.size.toLong())
    check(output.toByteArray().contentEquals(bytes))
    val single = ByteArrayOutputStream()
    ProgressOutputStream(single) { outputCount = it }.use { it.write(42) }
    check(outputCount == 1L && single.toByteArray().contentEquals(byteArrayOf(42)))
    val in2 = ProgressInputStream(ByteArrayInputStream(byteArrayOf(1, 2))) { inputCount = it }
    check(in2.read() == 1 && inputCount == 1L)
    check(in2.read() == 2 && inputCount == 2L)
    check(in2.read() == -1 && inputCount == 2L)
    // Transient zero-byte reads must not corrupt the stream or double-count.
    val transient = object : ByteArrayInputStream(byteArrayOf(7, 8, 9)) {
        var empty = 2
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len > 0 && empty-- > 0) return 0
            return super.read(b, off, len)
        }
    }
    var transientProgress = 0L
    ProgressInputStream(transient) { transientProgress = it }.use { guarded ->
        val data = ByteArray(3)
        check(guarded.read(data, 0, 0) == 0 && transientProgress == 0L)
        check(guarded.read(data, 0, data.size) == 3)
        check(data.contentEquals(byteArrayOf(7, 8, 9)))
        check(transientProgress == 3L)
        check(guarded.read(data, 0, data.size) == -1)
    }
    // A broken provider must fail in bounded time instead of spinning forever.
    val stalled = object : java.io.InputStream() {
        var attempts = 0
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            attempts++
            return 0
        }
    }
    var stallProgress = 0L
    ProgressInputStream(stalled) { stallProgress = it }.use { guarded ->
        val buffer = ByteArray(16)
        val failure = runCatching { guarded.read(buffer, 0, buffer.size) }.exceptionOrNull()
        check(failure is java.io.IOException)
        check(stalled.attempts == 32 && stallProgress == 0L)
    }
    println("PASS: accurate FTP/SFTP progress, integrity, bounded empty reads")
}
