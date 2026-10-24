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
        // Corrupt persisted state must fail closed BEFORE queue initialization
        // can overwrite the original evidence with a normalized journal.
        val badFile = File(dir, "bad-queue.properties")
        fun malformed(mutator: (java.util.Properties) -> Unit) {
            val props = java.util.Properties().apply {
                setProperty("count", "2")
                for (i in 0..1) {
                    setProperty("$" + "{i}.id", "task-$" + "{i}")
                    setProperty("$" + "{i}.source", "content://safe/$" + "i")
                    setProperty("$" + "{i}.dest", "peer/$" + "i")
                    setProperty("$" + "{i}.size", "10")
                    setProperty("$" + "{i}.done", "0")
                    setProperty("$" + "{i}.state", "PAUSED")
                    setProperty("$" + "{i}.attempts", "1")
                }
            }
            mutator(props)
            badFile.outputStream().use { props.store(it, "test") }
            val before = badFile.readBytes()
            val rejected = runCatching { TransferQueue(FileTransferJournal(badFile)) }.exceptionOrNull()
            check(rejected is IllegalArgumentException) { "Malformed journal accepted: $" + "rejected" }
            check(badFile.readBytes().contentEquals(before)) { "Corrupt journal was overwritten" }
        }
        malformed { it.setProperty("1.id", "task-0") } // duplicate identities
        malformed { it.setProperty("0.id", "  ") } // blank identity
        malformed { it.setProperty("1.source", "") } // lost SAF endpoint
        malformed { it.setProperty("0.dest", "") } // lost peer destination
        malformed { it.setProperty("0.attempts", "-1") } // impossible retry count
        malformed {
            it.setProperty("1.state", "COMPLETED")
            it.setProperty("1.done", "9")
        } // never mark an incomplete file as completed
        println("PASS: corrupted journal rejection and preservation")
        println("PASS: exact task selection, durable journal, retry, recover, monotonic progress, cancel")
    } finally { dir.deleteRecursively() }
}
