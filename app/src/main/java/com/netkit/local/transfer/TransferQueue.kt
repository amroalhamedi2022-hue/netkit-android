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
    /** True after interruption/pause/failure until the peer's durable offset is confirmed. */
    val offsetNeedsConfirmation: Boolean = false,
    val state: TransferState = TransferState.QUEUED,
    val attempts: Int = 0,
    val error: String = ""
)

interface TransferJournal {
    fun load(): List<TransferTask>
    fun save(tasks: List<TransferTask>)
}

private const val MAX_TRANSFER_TASKS = 1000
private const val MAX_TRANSFER_REFERENCE_LENGTH = 1024
private const val MAX_TRANSFER_ERROR_LENGTH = 200
private const val MAX_TRANSFER_JOURNAL_BYTES = 16L * 1024 * 1024

/** Thread-safe state transitions; journal failures must never silently leave an in-memory change. */
class TransferQueue(private val journal: TransferJournal) {
    private val mutex = Any()
    private val tasks = journal.load().map {
        when (it.state) {
            TransferState.RUNNING -> it.copy(
                state = TransferState.PAUSED,
                offsetNeedsConfirmation = true,
                error = "Interrupted; verify remote offset before retry"
            )
            TransferState.PAUSED, TransferState.FAILED ->
                it.copy(offsetNeedsConfirmation = true)
            else -> it
        }
    }.toMutableList()

    init { journal.save(tasks) }

    fun snapshot(): List<TransferTask> = synchronized(mutex) { tasks.toList() }

    fun add(source: String, destination: String, size: Long): TransferTask = synchronized(mutex) {
        require(source.isNotBlank() && destination.isNotBlank())
        require(source.length <= MAX_TRANSFER_REFERENCE_LENGTH &&
            destination.length <= MAX_TRANSFER_REFERENCE_LENGTH) { "Transfer reference is too long" }
        require(size >= 0 && tasks.size < MAX_TRANSFER_TASKS)
        val task = TransferTask(UUID.randomUUID().toString(), source, destination, size)
        persistChange { add(task) }
        task
    }

    /** Explicit start of the exact selected task, with no accidental start of a different task. */
    fun begin(id: String): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst { it.id == id && it.state == TransferState.QUEUED }
        if (index < 0) null else edit(index) { it.copy(state = TransferState.RUNNING, attempts = it.attempts + 1, error = "") }
    }

    fun next(): TransferTask? = synchronized(mutex) {
        val item = tasks.firstOrNull { it.state == TransferState.QUEUED } ?: return@synchronized null
        begin(item.id)
    }

    fun progress(id: String, bytes: Long): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst {
            it.id == id && it.state == TransferState.RUNNING && !it.offsetNeedsConfirmation
        }
        if (index < 0) null else edit(index) {
            it.copy(completedBytes = bytes.coerceIn(it.completedBytes, it.size))
        }
    }

    /**
     * Reconcile progress only after the authenticated peer confirms its durable offset.
     * Unlike progress(), this may move backwards; callers must never pass a guessed local offset.
     */
    fun confirmRemoteOffset(id: String, confirmedBytes: Long): TransferTask? = synchronized(mutex) {
        if (confirmedBytes < 0L) return@synchronized null
        val index = tasks.indexOfFirst {
            it.id == id && it.state == TransferState.RUNNING && confirmedBytes <= it.size
        }
        if (index < 0) null else edit(index) {
            it.copy(completedBytes = confirmedBytes, offsetNeedsConfirmation = false)
        }
    }

    // API state changes don't magically pause an existing network stream. Execution controller must close it.
    fun pause(id: String): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst {
            it.id == id && it.state in setOf(TransferState.RUNNING, TransferState.QUEUED)
        }
        if (index < 0) null else edit(index) {
            it.copy(
                state = TransferState.PAUSED,
                offsetNeedsConfirmation = it.offsetNeedsConfirmation || it.state == TransferState.RUNNING
            )
        }
    }

    fun resume(id: String): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst {
            it.id == id && it.state in setOf(TransferState.PAUSED, TransferState.FAILED)
        }
        if (index < 0) null else edit(index) {
            it.copy(state = TransferState.QUEUED, offsetNeedsConfirmation = true)
        }
    }
    fun cancel(id: String) = transition(id,
        setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED, TransferState.FAILED), TransferState.CANCELLED)

    /** Complete only when the transport has accounted for every declared byte. */
    fun finish(id: String): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst {
            it.id == id && it.state == TransferState.RUNNING &&
                !it.offsetNeedsConfirmation && it.completedBytes == it.size
        }
        if (index < 0) null else edit(index) {
            it.copy(state = TransferState.COMPLETED, error = "")
        }
    }

    fun fail(id: String, message: String): TransferTask? = synchronized(mutex) {
        val index = tasks.indexOfFirst { it.id == id && it.state == TransferState.RUNNING }
        if (index < 0) null else edit(index) {
            it.copy(
                state = TransferState.FAILED,
                offsetNeedsConfirmation = true,
                error = message.take(MAX_TRANSFER_ERROR_LENGTH)
            )
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
        require(file.length() <= MAX_TRANSFER_JOURNAL_BYTES) { "Transfer journal exceeds safe size" }
        val properties = Properties().also { props -> FileInputStream(file).use(props::load) }
        val count = properties.getProperty("count")?.toIntOrNull()
            ?: throw IllegalStateException("Invalid transfer journal count")
        require(count in 0..MAX_TRANSFER_TASKS) { "Too many saved transfer tasks" }
        val ids = HashSet<String>()
        return (0 until count).map { n ->
            val prefix = "$n."
            fun required(key: String) = properties.getProperty(prefix + key)
                ?: throw IllegalStateException("Missing saved task field $n.$key")
            val id = required("id")
            val source = required("source")
            val destination = required("dest")
            val size = required("size").toLong()
            val done = required("done").toLong()
            val attempts = required("attempts").toInt()
            val error = properties.getProperty(prefix + "error", "")
            val offsetNeedsConfirmation = properties.getProperty(prefix + "verifyOffset")?.let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Invalid saved offset verification flag")
                }
            } ?: false
            require(id.isNotBlank() && id.length <= 64 && ids.add(id)) {
                "Invalid or duplicate saved transfer ID"
            }
            require(source.isNotBlank() && source.length <= MAX_TRANSFER_REFERENCE_LENGTH &&
                destination.isNotBlank() && destination.length <= MAX_TRANSFER_REFERENCE_LENGTH) {
                "Invalid saved transfer reference"
            }
            require(size >= 0 && done in 0..size && attempts >= 0 &&
                error.length <= MAX_TRANSFER_ERROR_LENGTH) { "Invalid saved transfer state" }
            TransferTask(
                id = id, source = source, destination = destination,
                size = size, completedBytes = done,
                offsetNeedsConfirmation = offsetNeedsConfirmation,
                state = TransferState.valueOf(required("state")),
                attempts = attempts, error = error
            )
        }
    }

    override fun save(tasks: List<TransferTask>) {
        require(tasks.size <= MAX_TRANSFER_TASKS)
        val ids = HashSet<String>()
        tasks.forEach { task ->
            require(task.id.isNotBlank() && task.id.length <= 64 && ids.add(task.id)) {
                "Invalid or duplicate transfer ID"
            }
            require(task.source.isNotBlank() && task.source.length <= MAX_TRANSFER_REFERENCE_LENGTH &&
                task.destination.isNotBlank() && task.destination.length <= MAX_TRANSFER_REFERENCE_LENGTH) {
                "Invalid transfer reference"
            }
            require(task.size >= 0 && task.completedBytes in 0..task.size &&
                task.attempts >= 0 && task.error.length <= MAX_TRANSFER_ERROR_LENGTH) {
                "Invalid transfer task state"
            }
        }
        val properties = Properties()
        properties.setProperty("count", tasks.size.toString())
        tasks.forEachIndexed { index, task ->
            val prefix = "$index."
            properties.setProperty(prefix + "id", task.id)
            properties.setProperty(prefix + "source", task.source)
            properties.setProperty(prefix + "dest", task.destination)
            properties.setProperty(prefix + "size", task.size.toString())
            properties.setProperty(prefix + "done", task.completedBytes.toString())
            properties.setProperty(prefix + "verifyOffset", task.offsetNeedsConfirmation.toString())
            properties.setProperty(prefix + "state", task.state.name)
            properties.setProperty(prefix + "attempts", task.attempts.toString())
            properties.setProperty(prefix + "error", task.error)
        }
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, file.name + ".new")
        try {
            FileOutputStream(staging).use { stream ->
                properties.store(stream, "NetKit Transfer Journal")
                stream.fd.sync()
            }
            if (staging.length() > MAX_TRANSFER_JOURNAL_BYTES) {
                throw java.io.IOException("Transfer journal exceeds safe size")
            }
            try {
                Files.move(staging.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }
}
