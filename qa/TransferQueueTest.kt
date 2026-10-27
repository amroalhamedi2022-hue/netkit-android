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

        run {
            val invalidReferences = listOf(
                "content://safe/line\nbreak",
                "content://safe/\u0085name",
                "content://safe/\u202Etxt",
                "content://safe/\uD800"
            )
            invalidReferences.forEach { bad ->
                check(runCatching { queue.add(bad, "peer/a", 1) }.exceptionOrNull() is IllegalArgumentException)
                check(runCatching { queue.add("content://safe/a", bad, 1) }.exceptionOrNull() is IllegalArgumentException)
            }
            // Supplementary code points must pass through Unicode policy checks too.
            // U+E0061 is a Unicode Tag character; ordinary emoji remain supported below.
            val tagCharacter = String(Character.toChars(0xE0061))
            val taggedReferences = listOf("content://safe/${tagCharacter}name", "peer/${tagCharacter}name")
            taggedReferences.forEach { bad ->
                check(runCatching { queue.add(bad, "peer/a", 1) }.exceptionOrNull() is IllegalArgumentException)
                check(runCatching { queue.add("content://safe/a", bad, 1) }.exceptionOrNull() is IllegalArgumentException)
            }
            val arabic = queue.add("content://safe/ملف", "peer/ملف", 1)
            check(arabic.source.endsWith("ملف") && arabic.destination.endsWith("ملف"))
            check(queue.cancel(arabic.id)?.state == TransferState.CANCELLED)
            val joinedEmoji = queue.add("content://safe/👨‍👩‍👧‍👦", "peer/👨‍👩‍👧‍👦", 1)
            check(joinedEmoji.source.contains("👨‍👩‍👧‍👦"))
            check(queue.cancel(joinedEmoji.id)?.state == TransferState.CANCELLED)
        }
        check(queue.begin(second.id)?.id == second.id)
        check(queue.begin(second.id) == null)
        check(queue.snapshot().first().state == TransferState.QUEUED)
        check(queue.progress(second.id, 9)?.completedBytes == 9L)
        check(queue.progress(second.id, 4) == null) // reject regressions, do not mask them
        check(queue.progress(second.id, 99) == null) // reject overrun instead of clamping
        check(queue.progress(second.id, -1) == null) // reject negative transport reports
        check(queue.snapshot().single { it.id == second.id }.completedBytes == 9L)
        check(queue.pause(second.id)?.state == TransferState.PAUSED)
        check(queue.begin(second.id) == null)
        check(TransferQueue(FileTransferJournal(file)).snapshot()[1].completedBytes == 9L)
        check(queue.resume(second.id)?.state == TransferState.QUEUED)
        check(queue.begin(second.id)?.attempts == 2)
        check(queue.finish(second.id) == null) // resume cannot trust the saved local offset
        check(queue.confirmRemoteOffset(second.id, 15)?.completedBytes == 15L)
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
        check(restored.progress(first.id, 70) == null) // interrupted offset must be verified first
        check(restored.confirmRemoteOffset(first.id, 70)?.completedBytes == 70L)
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

            fun writeCompletedJournal(target: File, done: Int, verifyOffset: Boolean) {
                val properties = Properties()
                properties.setProperty("count", "1")
                properties.setProperty("0.id", "completed-id")
                properties.setProperty("0.source", "content://safe/completed")
                properties.setProperty("0.dest", "peer/completed")
                properties.setProperty("0.size", "10")
                properties.setProperty("0.done", done.toString())
                properties.setProperty("0.verifyOffset", verifyOffset.toString())
                properties.setProperty("0.state", "COMPLETED")
                properties.setProperty("0.attempts", "1")
                properties.setProperty("0.error", "")
                FileOutputStream(target).use { properties.store(it, "completed-state invariant regression") }
            }

            val partialCompleted = File(dir, "partial-completed.properties")
            writeCompletedJournal(partialCompleted, done = 9, verifyOffset = false)
            val partialCompletedError = runCatching {
                FileTransferJournal(partialCompleted).load()
            }.exceptionOrNull()
            check(partialCompletedError is IllegalArgumentException &&
                partialCompletedError.message!!.contains("Completed transfer"))

            val unverifiedCompleted = File(dir, "unverified-completed.properties")
            writeCompletedJournal(unverifiedCompleted, done = 10, verifyOffset = true)
            val unverifiedCompletedError = runCatching {
                FileTransferJournal(unverifiedCompleted).load()
            }.exceptionOrNull()
            check(unverifiedCompletedError is IllegalArgumentException &&
                unverifiedCompletedError.message!!.contains("Completed transfer"))

            val badSave = File(dir, "bad-completed-save.properties")
            val badSaveError = runCatching {
                FileTransferJournal(badSave).save(listOf(TransferTask(
                    id = "bad-complete", source = "content://safe/a",
                    destination = "peer/a", size = 10, completedBytes = 9,
                    state = TransferState.COMPLETED
                )))
            }.exceptionOrNull()
            check(badSaveError is IllegalArgumentException &&
                badSaveError.message!!.contains("Completed transfer"))
            check(!badSave.exists()) // reject before replacing/creating the journal

            val oversized = File(dir, "oversized.properties")
            FileOutputStream(oversized).use { it.write(ByteArray(16 * 1024 * 1024 + 1)) }
            val oversizedError = runCatching { FileTransferJournal(oversized).load() }.exceptionOrNull()
            check(oversizedError is IllegalArgumentException &&
                oversizedError.message!!.contains("safe size"))
        }
        // A failed attempt that sent all bytes is not complete until the peer confirms its durable offset.
        val retryFile = File(dir, "retry-queue.properties")
        val retryQueue = TransferQueue(FileTransferJournal(retryFile))
        val uncertain = retryQueue.add("content://safe/uncertain", "peer/uncertain", 4)
        check(retryQueue.begin(uncertain.id) != null)
        check(retryQueue.progress(uncertain.id, 4)?.completedBytes == 4L)
        check(retryQueue.fail(uncertain.id, "final acknowledgement lost")?.state == TransferState.FAILED)
        val recoveredRetry = TransferQueue(FileTransferJournal(retryFile))
        check(recoveredRetry.snapshot().single { it.id == uncertain.id }.offsetNeedsConfirmation)
        check(recoveredRetry.resume(uncertain.id)?.state == TransferState.QUEUED)
        check(recoveredRetry.begin(uncertain.id)?.state == TransferState.RUNNING)
        check(recoveredRetry.progress(uncertain.id, 3) == null)
        check(recoveredRetry.finish(uncertain.id) == null)
        check(recoveredRetry.confirmRemoteOffset(uncertain.id, 4)?.offsetNeedsConfirmation == false)
        check(recoveredRetry.finish(uncertain.id)?.state == TransferState.COMPLETED)

        // Remote exception text is untrusted UI/persistence data: strip bidi and controls,
        // while preserving Arabic ZWNJ and emoji ZWJ sequences.
        val unsafeErrorTask = queue.add("content://safe/error", "peer/error", 1)
        check(queue.begin(unsafeErrorTask.id) != null)
        val sanitizedError = queue.fail(unsafeErrorTask.id,
            "peer\u202Etxt\nline\u0085bad\uD800")!!
        check(sanitizedError.error == "peer txt line bad")
        check(FileTransferJournal(file).load().single { it.id == unsafeErrorTask.id }.error ==
            "peer txt line bad")
        val localizedErrorTask = queue.add("content://safe/localized-error", "peer/localized-error", 1)
        check(queue.begin(localizedErrorTask.id) != null)
        val localizedError = "فشل\u200cالنقل 👨‍👩‍👧‍👦"
        check(queue.fail(localizedErrorTask.id, localizedError)?.error == localizedError)
        val unsafeDirectSave = runCatching {
            FileTransferJournal(File(dir, "unsafe-error-save.properties")).save(listOf(
                TransferTask("unsafe-error", "content://safe/a", "peer/a", 1,
                    error = "bad\u202Etext")
            ))
        }.exceptionOrNull()
        check(unsafeDirectSave is IllegalArgumentException)

        println("PASS: durable queue, confirmed offsets, corruption rejection, size bounds, retry and recovery")
    } finally { dir.deleteRecursively() }
}
