package com.netkit.local.transfer

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Persisted *intent* is not a resumable transport. The protocol must confirm remote offsets. */
enum class TransferState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class TransferTask(
    val id: String,
    val source: String,
    val destination: String,
    val size: Long,
    val completedBytes: Long = 0,
    val state: TransferState = TransferState.QUEUED,
    val attempts: Int = 0,
    val error: String = ""
)

interface TransferJournal {
    fun load(): List<TransferTask>
    fun save(tasks: List<TransferTask>)
}

/** Thread-safe state transitions; journal failures must never silently leave an in-memory change. */
class TransferQueue(private val journal: TransferJournal) {
    private val mutex = Any()
    private val loaded = journal.load()
    private val tasks = loaded.map {
        if (it.state == TransferState.RUNNING) it.copy(
            state = TransferState.PAUSED, error = "Interrupted; verify remote offset before retry"
        ) else it
    }.toMutableList()

    // Only persist when recovering a previously RUNNING task. Rewriting a
    // healthy journal on every app launch adds I/O and can erase forensic
    // evidence if storage is unreliable, without changing queue state.
    init { if (tasks != loaded) journal.save(tasks) }

    fun snapshot(): List<TransferTask> = synchronized(mutex) { tasks.toList() }

    fun add(source: String, destination: String, size: Long): TransferTask = synchronized(mutex) {
        require(source.isNotBlank() && destination.isNotBlank())
        require(size >= 0 && tasks.size < 1000)
        val task = TransferTask(UUID.randomUUID().toString(), source, destination, size)
        persistChange { add(task) }
        task
    }

    /**
     * Reclaim bounded journal capacity without ever deleting pending, paused or
     * failed work. Terminal records are removed only by an explicit user action.
     * A failed journal write leaves the in-memory queue unchanged.
     */
    fun clearFinished(): Int = synchronized(mutex) {
        val count = tasks.count {
            it.state == TransferState.COMPLETED || it.state == TransferState.CANCELLED
        }
        if (count > 0) persistChange {
            removeAll {
                it.state == TransferState.COMPLETED || it.state == TransferState.CANCELLED
            }
        }
        count
    }

    /** Explicit start of the exact selected task, with no accidental start of a different task. */
    fun begin(id: String): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst { it.id == id && it.state == TransferState.QUEUED && it.attempts < Int.MAX_VALUE }
        if (index < 0) null else edit(index) { it.copy(state = TransferState.RUNNING, attempts = it.attempts + 1, error = "") }
    }

    fun next(): TransferTask? = synchronized(mutex) {
        val item = tasks.firstOrNull { it.state == TransferState.QUEUED && it.attempts < Int.MAX_VALUE }
            ?: return@synchronized null
        begin(item.id)
    }

    /** Reject late progress callbacks from a cancelled or superseded attempt. */
    fun progress(id: String, attempt: Int, bytes: Long): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst { it.id == id && it.state == TransferState.RUNNING && it.attempts == attempt }
        // A callback reporting impossible bytes must not silently fabricate 100% progress.
        if (index < 0 || bytes < 0 || bytes > tasks[index].size) null else edit(index) {
            it.copy(completedBytes = maxOf(it.completedBytes, bytes))
        }
    }

    // API state changes don't magically pause an existing network stream. Execution controller must close it.
    fun pause(id: String) = transition(id, setOf(TransferState.RUNNING, TransferState.QUEUED), TransferState.PAUSED)
    /** A journal byte count is not a verified remote offset: restart from zero. */
    fun resume(id: String): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst { it.id == id && it.state in setOf(TransferState.PAUSED, TransferState.FAILED) }
        if (index < 0) null else edit(index) {
            it.copy(state = TransferState.QUEUED, completedBytes = 0, error = "")
        }
    }
    fun cancel(id: String) = transition(id,
        setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED, TransferState.FAILED), TransferState.CANCELLED)

    fun finish(id: String, attempt: Int): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst { it.id == id && it.state == TransferState.RUNNING && it.attempts == attempt }
        // A transport success flag alone is not evidence of a complete known-size
        // transfer. Never fabricate the missing bytes in the persisted journal.
        // Size 0 is also used by SAF providers that cannot report a size; those
        // transfers still need the transport's own completion/integrity checks.
        if (index < 0 || (tasks[index].size > 0 &&
                tasks[index].completedBytes != tasks[index].size)) null else edit(index) {
            it.copy(state = TransferState.COMPLETED, completedBytes = it.size, error = "")
        }
    }

    fun fail(id: String, attempt: Int, message: String): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst { it.id == id && it.state == TransferState.RUNNING && it.attempts == attempt }
        if (index < 0) null else edit(index) {
            it.copy(state = TransferState.FAILED, error = message.take(200))
        }
    }

    private fun transition(id: String, from: Set<TransferState>, to: TransferState): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst { it.id == id && it.state in from }
        if (index < 0) null else edit(index) { it.copy(state = to) }
    }

    private fun edit(index: Int, action: (TransferTask) -> TransferTask): TransferTask {
        val modified = action(tasks[index])
        persistChange { this[index] = modified }
        return modified
    }

    private fun persistChange(action: MutableList<TransferTask>.() -> Unit) {
        val next = tasks.toMutableList().apply(action)
        journal.save(next) // journal first, then expose the new state
        tasks.clear()
        tasks.addAll(next)
    }
}

/** Atomic on Android 8+: do not delete the good journal before writing its replacement. */
class FileTransferJournal(private val file: File) : TransferJournal {
    override fun load(): List<TransferTask> {
        if (!file.exists()) return emptyList()
        val properties = Properties().also { props -> FileInputStream(file).use(props::load) }
        val count = properties.getProperty("count")?.toIntOrNull()
            ?: throw IllegalStateException("Invalid transfer journal count")
        require(count in 0..1000) { "Too many saved transfer tasks" }
        // A malformed journal must never be silently normalized and re-saved
        // by TransferQueue initialization: reject duplicate task identities.
        val seenIds = HashSet<String>()
        return (0 until count).map { n ->
            val prefix = "$n."
            fun required(key: String) = properties.getProperty(prefix + key)
                ?: throw IllegalStateException("Missing saved task field $n.$key")
            val size = required("size").toLong()
            val done = required("done").toLong()
            require(size >= 0 && done in 0..size) { "Invalid saved task byte count" }
            val id = required("id")
            val source = required("source")
            val destination = required("dest")
            val attempts = required("attempts").toInt()
            val state = TransferState.valueOf(required("state"))
            require(id.isNotBlank() && seenIds.add(id)) { "Duplicate or blank saved task id" }
            require(source.isNotBlank() && destination.isNotBlank()) { "Missing saved task endpoint" }
            require(attempts >= 0) { "Negative saved task attempts" }
            require(state != TransferState.COMPLETED || done == size) {
                "Completed task has incomplete saved byte count"
            }
            TransferTask(
                id = id, source = source, destination = destination,
                size = size, completedBytes = done, state = state,
                attempts = attempts, error = properties.getProperty(prefix + "error", "")
            )
        }
    }

    override fun save(tasks: List<TransferTask>) {
        require(tasks.size <= 1000)
        val properties = Properties()
        properties.setProperty("count", tasks.size.toString())
        tasks.forEachIndexed { index, task ->
            val prefix = "$index."
            properties.setProperty(prefix + "id", task.id)
            properties.setProperty(prefix + "source", task.source)
            properties.setProperty(prefix + "dest", task.destination)
            properties.setProperty(prefix + "size", task.size.toString())
            properties.setProperty(prefix + "done", task.completedBytes.toString())
            properties.setProperty(prefix + "state", task.state.name)
            properties.setProperty(prefix + "attempts", task.attempts.toString())
            properties.setProperty(prefix + "error", task.error)
        }
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, file.name + ".new")
        FileOutputStream(staging).use { stream ->
            properties.store(stream, "NetKit Transfer Journal")
            stream.fd.sync()
        }
        try {
            Files.move(staging.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
