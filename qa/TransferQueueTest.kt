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
        val secondAttempt1 = checkNotNull(queue.begin(second.id)).attempts
        check(secondAttempt1 == 1)
        check(queue.begin(second.id) == null)
        check(queue.snapshot().first().state == TransferState.QUEUED)
        check(queue.progress(second.id, secondAttempt1, 9)?.completedBytes == 9L)
        check(queue.progress(second.id, secondAttempt1, 4)?.completedBytes == 9L) // monotonic
        check(queue.progress(second.id, secondAttempt1, 99) == null) // reject impossible >size
        check(queue.progress(second.id, secondAttempt1, -1) == null) // reject negative
        check(queue.progress(second.id, secondAttempt1, Long.MAX_VALUE) == null)
        check(queue.progress(second.id, secondAttempt1, 15)?.completedBytes == 15L)
        check(queue.pause(second.id)?.state == TransferState.PAUSED)
        check(queue.begin(second.id) == null)
        check(TransferQueue(FileTransferJournal(file)).snapshot()[1].completedBytes == 15L)
        check(queue.resume(second.id)?.completedBytes == 0L) // no verified remote offset
        val secondAttempt2 = checkNotNull(queue.begin(second.id)).attempts
        check(secondAttempt2 == 2)
        check(queue.progress(second.id, secondAttempt1, 15) == null) // stale callback
        check(queue.finish(second.id, secondAttempt1) == null) // stale completion
        check(queue.fail(second.id, secondAttempt1, "stale failure") == null)
        check(queue.progress(second.id, secondAttempt2, 9)?.completedBytes == 9L)
        check(queue.finish(second.id, secondAttempt2) == null) // partial bytes cannot be fabricated
        check(queue.snapshot().single { it.id == second.id }.state == TransferState.RUNNING)
        check(queue.progress(second.id, secondAttempt2, 15)?.completedBytes == 15L)
        check(queue.finish(second.id, secondAttempt2)?.state == TransferState.COMPLETED)
        check(queue.fail(second.id, secondAttempt2, "should be refused") == null)
        check(queue.next()?.id == first.id)
        check(TransferQueue(FileTransferJournal(file)).snapshot()[0].state == TransferState.PAUSED) // crashed RUNNING recovery
        val restored = TransferQueue(FileTransferJournal(file))
        check(restored.resume(first.id)?.state == TransferState.QUEUED)
        val firstAttempt = checkNotNull(restored.next())
        check(firstAttempt.id == first.id)
        check(restored.fail(first.id, firstAttempt.attempts, "timeout")?.state == TransferState.FAILED)
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
                    setProperty("$i.id", "task-$i")
                    setProperty("$i.source", "content://safe/$i")
                    setProperty("$i.dest", "peer/$i")
                    setProperty("$i.size", "10")
                    setProperty("$i.done", "0")
                    setProperty("$i.state", "PAUSED")
                    setProperty("$i.attempts", "1")
                }
            }
            mutator(props)
            badFile.outputStream().use { props.store(it, "test") }
            val before = badFile.readBytes()
            val rejected = runCatching { TransferQueue(FileTransferJournal(badFile)) }.exceptionOrNull()
            check(rejected is IllegalArgumentException) { "Malformed journal accepted: $rejected" }
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
        // Healthy queue startup is read-only; interrupted RUNNING tasks must
        // still be persisted as PAUSED exactly once before exposure.
        run {
            class CountingJournal(private val initial: List<TransferTask>) : TransferJournal {
                var writes = 0
                var lastSaved: List<TransferTask>? = null
                override fun load() = initial
                override fun save(tasks: List<TransferTask>) {
                    writes++
                    lastSaved = tasks.toList()
                }
            }
            val task = TransferTask("stable", "content://a", "peer/a", 5)
            val healthy = CountingJournal(listOf(task))
            check(TransferQueue(healthy).snapshot() == listOf(task))
            check(healthy.writes == 0) { "Healthy journal was needlessly rewritten" }
            val interrupted = CountingJournal(listOf(task.copy(state = TransferState.RUNNING)))
            val recovered = TransferQueue(interrupted).snapshot().single()
            check(recovered.state == TransferState.PAUSED)
            check(interrupted.writes == 1)
            check(interrupted.lastSaved?.single() == recovered)
        }
        // A restored exhausted task must not starve healthy queued work.
        run {
            val exhausted = TransferTask("exhausted", "content://x", "peer/x", 1,
                attempts = Int.MAX_VALUE)
            val available = TransferTask("available", "content://y", "peer/y", 2)
            val journal = object : TransferJournal {
                private var saved = listOf(exhausted, available)
                override fun load() = saved
                override fun save(tasks: List<TransferTask>) { saved = tasks.toList() }
            }
            val pending = TransferQueue(journal)
            check(pending.begin(exhausted.id) == null)
            check(pending.next()?.id == available.id)
            check(pending.snapshot().first() == exhausted)
            check(pending.next() == null)
        }
        println("PASS: exhausted retry counts cannot starve queued transfers")
        println("PASS: impossible progress and incomplete known-size finish rejected")
        println("PASS: startup journal write elision and durable crash recovery")
        println("PASS: corrupted journal rejection and preservation")
        println("PASS: attempt-scoped callbacks, retry reset, durable journal and cancel")
    } finally { dir.deleteRecursively() }
}
