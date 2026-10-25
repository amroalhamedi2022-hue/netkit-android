package com.netkit.local.share

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Bounded, opt-in HTTPS browser upload resume within ONE active share session.
 * Chunks are stored in app-private cache; no partial data is published to SAF.
 * The full staged SHA-256 is checked again by ShareStorage during SAF commit.
 *
 * Session IDs are random client-generated 128-bit hex values. They are scoped to
 * the secret HTTPS URL and this server instance, not durable across server restart.
 * Stopping the share destroys every pending part and revokes the session.
 */
internal class ResumableBrowserUpload(
    private val directory: File,
    private val storage: ShareStorage,
    private val consentEpoch: () -> Long,
    private val allowed: () -> Boolean,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : AutoCloseable {
    companion object {
        const val MAX_CHUNK = 1024 * 1024
        private const val MAX_SESSIONS = 4
        private const val MAX_CANCELLED_IDS = 4096
        private const val TTL_MILLIS = 15L * 60 * 1000
        private const val DISK_RESERVE = 16L * 1024 * 1024
        private val ID = Regex("[0-9a-f]{32}")
        private val SHA = Regex("[0-9a-fA-F]{64}")
    }

    data class Status(val received: Long, val total: Long, val complete: Boolean)
    class OffsetConflict(val expected: Long) : IOException("Upload offset conflict")
    class InvalidSession(message: String) : IOException(message)

    private class Session(
        val name: String, val folders: List<String>, val total: Long,
        val expectedSha: String?, val epoch: Long, val part: File,
        var touched: Long
    ) {
        var received: Long = 0
        var committed: Boolean = false
        var invalid: Boolean = false
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    // Retired IDs stay blocked for this share lifetime. If the bounded ledger fills,
    // fail closed for NEW sessions instead of evicting an ID that a delayed request could replay.
    private val cancelledIds = LinkedHashSet<String>()
    private var newSessionAdmissionClosed = false
    @Volatile private var closed = false

    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create private upload cache" } }

    fun status(id: String): Status? {
        require(ID.matches(id)) { "Invalid upload ID" }
        synchronized(lock) {
            cleanupLocked()
            val session = sessions[id] ?: return null
            synchronized(session) {
                if (!valid(session)) {
                    retireIdLocked(id)
                    sessions.remove(id)
                    session.part.delete()
                    return null
                }
                // A status-only poll must not extend the private disk staging TTL.
                // Only actual upload progress (or commit) refreshes activity.
                return Status(session.received, session.total, session.committed)
            }
        }
    }

    /** Discard an incomplete browser upload immediately; a committed SAF file is never deleted. */
    fun cancel(id: String): Boolean {
        require(ID.matches(id)) { "Invalid upload ID" }
        synchronized(lock) {
            cleanupLocked()
            val session = sessions[id]
            if (session == null) {
                // The cancellation may overtake the first network chunk. Revoke
                // its ID anyway, but never claim that an unknown file was deleted.
                retireIdLocked(id)
                return false
            }
            synchronized(session) {
                if (session.committed) return false
                session.invalid = true
                retireIdLocked(id)
                if (session.part.exists() && !session.part.delete()) {
                    throw IOException("Could not discard cancelled upload staging")
                }
                sessions.remove(id)
                return true
            }
        }
    }

    private fun retireIdLocked(id: String) {
        if (cancelledIds.contains(id)) return
        if (cancelledIds.size >= MAX_CANCELLED_IDS) {
            // Never evict a live tombstone: doing so would allow a delayed first chunk
            // to resurrect an expired/cancelled upload. Existing sessions may finish,
            // but the share cannot admit new upload IDs until it is restarted.
            newSessionAdmissionClosed = true
            return
        }
        cancelledIds.add(id)
    }

    fun append(
        id: String, name: String, folders: List<String>, total: Long,
        offset: Long, length: Long, expectedSha: String?,
        body: InputStream
    ): Status {
        require(ID.matches(id)) { "Invalid upload ID" }
        require(UploadPathPolicy.validName(name)) { "Invalid upload name" }
        if (folders.isNotEmpty()) UploadPathPolicy.checkFolders(folders, name)
        require(total in 0L..storage.maxUploadSize()) { "Upload exceeds configured limit" }
        require(length in 0L..MAX_CHUNK.toLong()) { "Chunk exceeds 1 MiB limit" }
        require(offset >= 0 && offset <= total && length <= total - offset) { "Invalid chunk range" }
        require(expectedSha == null || SHA.matches(expectedSha)) { "Invalid SHA-256" }
        // A zero-length request for an incomplete file cannot advance its offset.
        // Reject it before allocating a session or refreshing the idle deadline.
        // Zero-byte files and final-response replays (offset == total) remain valid.
        if (length == 0L && offset < total) {
            throw InvalidSession("Empty upload chunk cannot advance offset")
        }
        if (closed || !allowed()) throw InvalidSession("Upload permission is disabled")
        val epoch = consentEpoch()
        val session = synchronized(lock) {
            cleanupLocked()
            if (cancelledIds.contains(id)) {
                throw InvalidSession("Retired upload ID cannot be resumed")
            }
            val existing = sessions[id]
            if (existing != null) existing else {
                if (newSessionAdmissionClosed) {
                    throw InvalidSession("Upload ID safety limit reached; restart the share before starting another upload")
                }
                if (offset != 0L) throw OffsetConflict(0L)
                if (sessions.size >= MAX_SESSIONS) throw InvalidSession("Too many pending uploads")
                val created = Session(name, folders.toList(), total, expectedSha?.lowercase(),
                    epoch, File(directory, "$id.part"), clockMillis())
                check(!created.part.exists()) { "Stale upload part collision" }
                sessions[id] = created
                created
            }
        }
        synchronized(session) {
            if (!valid(session)) throw InvalidSession("Share session or upload consent changed")
            if (session.name != name || session.folders != folders || session.total != total ||
                session.expectedSha != expectedSha?.lowercase() || session.epoch != epoch) {
                throw InvalidSession("Upload ID metadata mismatch")
            }
            if (session.committed) return Status(total, total, true)
            if (offset != session.received) throw OffsetConflict(session.received)
            if (length > 0) {
                if (directory.usableSpace < length + DISK_RESERVE) {
                    throw IOException("Insufficient private cache space")
                }
                val buffer = ByteArray(64 * 1024)
                FileOutputStream(session.part, true).use { output ->
                    try {
                        var remaining = length
                        var emptyReads = 0
                        while (remaining > 0) {
                            if (!valid(session)) throw InvalidSession("Share stopped during chunk")
                            val count = body.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (count < 0) throw IOException("Chunk body ended early")
                            if (count == 0) {
                                if (++emptyReads >= 32) throw IOException("Chunk input stalled")
                                continue
                            }
                            emptyReads = 0
                            output.write(buffer, 0, count)
                            remaining -= count
                            // Active slow transfers must not expire while bytes keep arriving.
                            session.touched = clockMillis()
                        }
                        output.fd.sync()
                    } finally {
                        // A truncated network chunk is resumable from its actual on-disk offset.
                        session.received = session.part.length()
                    }
                }
            }
            session.touched = clockMillis()
            if (session.received == total) {
                if (!session.part.exists()) FileOutputStream(session.part).use { it.fd.sync() }
                if (!valid(session)) throw InvalidSession("Share stopped before SAF commit")
                if (session.part.length() != total) throw IOException("Staging length mismatch")
                val digest = sha256(session.part)
                if (session.expectedSha != null && session.expectedSha != digest) {
                    session.invalid = true
                    session.part.delete()
                    throw UploadHashMismatchException()
                }
                // Always require an independently verified digest at the SAF boundary,
                // including large browser files for which WebCrypto is not used.
                FileInputStream(session.part).use { source ->
                    if (folders.isEmpty()) storage.upload(name, source, total, digest) { }
                    else storage.uploadRelative(folders, name, source, total, digest) { }
                }
                if (!valid(session)) throw InvalidSession("Share revoked during SAF commit")
                session.committed = true
                // A slow SAF provider can take longer than the idle TTL to publish.
                // Start the completed-result replay window only after commit succeeds.
                session.touched = clockMillis()
                session.part.delete()
            }
            return Status(session.received, total, session.committed)
        }
    }

    private fun valid(session: Session): Boolean =
        !closed && !session.invalid && allowed() && consentEpoch() == session.epoch

    private fun cleanupLocked() {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value
            synchronized(session) {
                // Recheck under the session lock: an append may have advanced the
                // activity timestamp while cleanup waited for that append to finish.
                // Retire expired/invalid IDs for the rest of this share lifetime so
                // delayed offset-zero requests cannot create a second publication.
                if (clockMillis() - session.touched > TTL_MILLIS || !valid(session)) {
                    retireIdLocked(entry.key)
                    session.part.delete()
                    iterator.remove()
                }
            }
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) {
            sessions.values.forEach { synchronized(it) { it.part.delete() } }
            sessions.clear()
            cancelledIds.clear()
        }
        directory.deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
