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
        // Explicit cleanup must never delete failed or unfinished work, and
        // must preserve the original state if durable journal writing fails.
        run {
            class Journal(var saved: List<TransferTask>, var rejectWrites: Boolean = false) : TransferJournal {
                override fun load() = saved
                override fun save(tasks: List<TransferTask>) {
                    if (rejectWrites) error("storage unavailable")
                    saved = tasks.toList()
                }
            }
            fun item(id: String, state: TransferState) =
                TransferTask(id, "content://$id", "peer/$id", 1,
                    completedBytes = if (state == TransferState.COMPLETED) 1 else 0,
                    state = state)
            val journal = Journal(listOf(
                item("done", TransferState.COMPLETED),
                item("cancelled", TransferState.CANCELLED),
                item("failed", TransferState.FAILED),
                item("queued", TransferState.QUEUED),
                item("paused", TransferState.PAUSED)
            ))
            val q = TransferQueue(journal)
            journal.rejectWrites = true
            check(runCatching { q.clearFinished() }.isFailure)
            check(q.snapshot().size == 5)
            check(journal.saved.size == 5)
            journal.rejectWrites = false
            check(q.clearFinished() == 2)
            check(q.snapshot().map { it.id } == listOf("failed", "queued", "paused"))
            check(TransferQueue(journal).snapshot().size == 3)
            check(q.clearFinished() == 0)
        }
        // Reclaim the real 1000-task journal capacity, never deleting live work.
        run {
            val full = object : TransferJournal {
                var saved = (0 until 1000).map { index ->
                    TransferTask("item-$index", "content://$index", "peer/$index", 1,
                        completedBytes = if (index % 2 == 0) 1 else 0,
                        state = if (index % 2 == 0) TransferState.COMPLETED else TransferState.PAUSED)
                }
                override fun load() = saved
                override fun save(tasks: List<TransferTask>) { saved = tasks.toList() }
            }
            val queue = TransferQueue(full)
            check(runCatching { queue.add("content://extra", "peer/extra", 1) }.isFailure)
            check(queue.clearFinished() == 500)
            check(queue.snapshot().size == 500)
            check(queue.snapshot().all { it.state == TransferState.PAUSED })
            check(queue.add("content://extra", "peer/extra", 1).state == TransferState.QUEUED)
            check(TransferQueue(full).snapshot().size == 501)
        }
        // Unknown SAF provider size is -1; known zero must stay strictly zero.
        // Never turn observed progress into -1 or silently accept bytes for an
        // explicitly empty file. Persist/reload both cases without corruption.
        run {
            val journalFile = File(dir, "unknown-size.properties")
            val unknownQueue = TransferQueue(FileTransferJournal(journalFile))
            val unknown = unknownQueue.add("content://unknown", "peer/unknown", -1)
            val empty = unknownQueue.add("content://empty", "peer/empty", 0)
            val unknownAttempt = checkNotNull(unknownQueue.begin(unknown.id)).attempts
            val emptyAttempt = checkNotNull(unknownQueue.begin(empty.id)).attempts
            check(unknownQueue.progress(unknown.id, unknownAttempt, 4096)?.completedBytes == 4096L)
            check(unknownQueue.progress(unknown.id, unknownAttempt, 8192)?.completedBytes == 8192L)
            check(unknownQueue.progress(empty.id, emptyAttempt, 1) == null)
            check(unknownQueue.finish(empty.id, emptyAttempt)?.completedBytes == 0L)
            check(unknownQueue.finish(unknown.id, unknownAttempt)?.completedBytes == 8192L)
            val reloaded = TransferQueue(FileTransferJournal(journalFile)).snapshot()
            check(reloaded.single { it.id == unknown.id }.let {
                it.size == -1L && it.completedBytes == 8192L &&
                    it.state == TransferState.COMPLETED
            })
            check(reloaded.single { it.id == empty.id }.let {
                it.size == 0L && it.completedBytes == 0L &&
                    it.state == TransferState.COMPLETED
            })
            val unknownRetry = unknownQueue.add("content://retry", "peer/retry", -1)
            val attempt = checkNotNull(unknownQueue.begin(unknownRetry.id)).attempts
            check(unknownQueue.progress(unknownRetry.id, attempt, 50)?.completedBytes == 50L)
            check(unknownQueue.pause(unknownRetry.id)?.state == TransferState.PAUSED)
            check(unknownQueue.resume(unknownRetry.id)?.completedBytes == 0L)
            check(runCatching { unknownQueue.add("content://bad", "peer/bad", -2) }.isFailure)
        }
        println("PASS: unknown SAF size, known empty, progress, persistence and retry")
        println("PASS: terminal cleanup restores bounded journal capacity")
        println("PASS: explicit terminal cleanup is durable and preserves unfinished work")
        println("PASS: exhausted retry counts cannot starve queued transfers")
        println("PASS: impossible progress and incomplete known-size finish rejected")
        println("PASS: startup journal write elision and durable crash recovery")
        println("PASS: corrupted journal rejection and preservation")
        println("PASS: attempt-scoped callbacks, retry reset, durable journal and cancel")
    } finally { dir.deleteRecursively() }
}
