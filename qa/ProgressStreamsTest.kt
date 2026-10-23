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
    println("PASS: accurate FTP/SFTP stream progress, binary integrity, no double counting")
}
