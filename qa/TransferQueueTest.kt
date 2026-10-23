import com.netkit.local.transfer.*
import java.io.File
import java.nio.file.Files

fun main() {
    val dir = Files.createTempDirectory("netkit-queue-tests").toFile()
    try {
        val file = File(dir, "queue.properties")
        val queue = TransferQueue(FileTransferJournal(file))
        val first = queue.add("content://safe/a", "peer/a", 100)
        val second = queue.add("content://safe/b", "peer/b", 15)
        check(queue.begin(second.id)?.id == second.id)
        check(queue.begin(second.id) == null)
        check(queue.snapshot().first().state == TransferState.QUEUED)
        check(queue.progress(second.id, 9)?.completedBytes == 9L)
        check(queue.progress(second.id, 4)?.completedBytes == 9L) // monotonic
        check(queue.progress(second.id, 99)?.completedBytes == 15L) // clamped
        check(queue.pause(second.id)?.state == TransferState.PAUSED)
        check(queue.begin(second.id) == null)
        check(TransferQueue(FileTransferJournal(file)).snapshot()[1].completedBytes == 15L)
        check(queue.resume(second.id)?.state == TransferState.QUEUED)
        check(queue.begin(second.id)?.attempts == 2)
        check(queue.finish(second.id)?.state == TransferState.COMPLETED)
        check(queue.fail(second.id, "should be refused") == null)
        check(queue.next()?.id == first.id)
        check(TransferQueue(FileTransferJournal(file)).snapshot()[0].state == TransferState.PAUSED) // crashed RUNNING recovery
        val restored = TransferQueue(FileTransferJournal(file))
        check(restored.resume(first.id)?.state == TransferState.QUEUED)
        check(restored.next()?.id == first.id)
        check(restored.fail(first.id, "timeout")?.state == TransferState.FAILED)
        check(restored.resume(first.id)?.state == TransferState.QUEUED)
        check(restored.cancel(first.id)?.state == TransferState.CANCELLED)
        check(restored.resume(first.id) == null)
        check(TransferQueue(FileTransferJournal(file)).snapshot().single { it.id == second.id }.state == TransferState.COMPLETED)
        println("PASS: exact task selection, durable journal, retry, recover, monotonic progress, cancel")
    } finally { dir.deleteRecursively() }
}
