import com.netkit.local.transfer.*
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
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

        // Never report a partial transfer as complete, even if the caller invokes finish early.
        val incomplete = queue.add("content://safe/incomplete", "peer/incomplete", 10)
        check(queue.begin(incomplete.id) != null)
        check(queue.finish(incomplete.id) == null)
        check(queue.progress(incomplete.id, 9)?.completedBytes == 9L)
        check(queue.finish(incomplete.id) == null)
        check(queue.progress(incomplete.id, 10)?.completedBytes == 10L)
        check(queue.finish(incomplete.id)?.state == TransferState.COMPLETED)

        // A zero-byte file is complete as soon as its transport is started.
        val empty = queue.add("content://safe/empty", "peer/empty", 0)
        check(queue.begin(empty.id) != null)
        check(queue.finish(empty.id)?.state == TransferState.COMPLETED)
        check(queue.fail(second.id, "should be refused") == null)
        check(queue.next()?.id == first.id)
        check(TransferQueue(FileTransferJournal(file)).snapshot()[0].state == TransferState.PAUSED) // crashed RUNNING recovery
        val restored = TransferQueue(FileTransferJournal(file))
        check(restored.resume(first.id)?.state == TransferState.QUEUED)
        check(restored.next()?.id == first.id)
        check(restored.progress(first.id, 70)?.completedBytes == 70L)
        check(restored.confirmRemoteOffset(first.id, 40)?.completedBytes == 40L) // verified server offset may rewind
        check(restored.confirmRemoteOffset(first.id, 101) == null) // beyond declared file size
        check(restored.confirmRemoteOffset(first.id, -1) == null) // negative offsets fail closed
        check(restored.snapshot().single { it.id == first.id }.completedBytes == 40L)
        check(FileTransferJournal(file).load().single { it.id == first.id }.completedBytes == 40L) // durable reconciliation
        check(restored.fail(first.id, "timeout")?.state == TransferState.FAILED)
        check(restored.confirmRemoteOffset(first.id, 20) == null) // only a running task can reconcile
        check(restored.resume(first.id)?.state == TransferState.QUEUED)
        check(restored.cancel(first.id)?.state == TransferState.CANCELLED)
        check(restored.resume(first.id) == null)
        check(TransferQueue(FileTransferJournal(file)).snapshot().single { it.id == second.id }.state == TransferState.COMPLETED)
        run {
            fun writeJournal(target: File, ids: List<String>, attempts: List<Int>) {
                val properties = Properties()
                properties.setProperty("count", ids.size.toString())
                ids.indices.forEach { i ->
                    val p = "$i."
                    properties.setProperty(p + "id", ids[i])
                    properties.setProperty(p + "source", "content://safe/item-$i")
                    properties.setProperty(p + "dest", "peer/item-$i")
                    properties.setProperty(p + "size", "10")
                    properties.setProperty(p + "done", "0")
                    properties.setProperty(p + "state", "QUEUED")
                    properties.setProperty(p + "attempts", attempts[i].toString())
                    properties.setProperty(p + "error", "")
                }
                FileOutputStream(target).use { properties.store(it, "journal corruption regression") }
            }

            val duplicate = File(dir, "duplicate.properties")
            writeJournal(duplicate, listOf("same-id", "same-id"), listOf(0, 0))
            val duplicateError = runCatching { FileTransferJournal(duplicate).load() }.exceptionOrNull()
            check(duplicateError is IllegalArgumentException &&
                duplicateError.message!!.contains("duplicate"))

            val negativeAttempts = File(dir, "negative-attempts.properties")
            writeJournal(negativeAttempts, listOf("one-id"), listOf(-1))
            check(runCatching { FileTransferJournal(negativeAttempts).load() }.exceptionOrNull()
                is IllegalArgumentException)

            val oversized = File(dir, "oversized.properties")
            FileOutputStream(oversized).use { it.write(ByteArray(16 * 1024 * 1024 + 1)) }
            val oversizedError = runCatching { FileTransferJournal(oversized).load() }.exceptionOrNull()
            check(oversizedError is IllegalArgumentException &&
                oversizedError.message!!.contains("safe size"))
        }
        println("PASS: durable queue, confirmed offsets, corruption rejection, size bounds, retry and recovery")
    } finally { dir.deleteRecursively() }
}
