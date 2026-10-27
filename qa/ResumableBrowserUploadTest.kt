package com.netkit.local.share

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

private class FakeBrowserStorage : ShareStorage {
    var enabled = true
    var epoch = 1L
    val saved = mutableListOf<Pair<String, ByteArray>>()
    var beforeSave: (() -> Unit)? = null
    var afterSave: (() -> Unit)? = null
    override fun items() = emptyList<ShareItem>()
    override fun open(id: String): SharedStream? = null
    override fun canUpload() = enabled
    override fun uploadGrantEpoch() = epoch
    override fun maxUploadSize() = 8L * 1024 * 1024
    override fun upload(fileName: String, body: java.io.InputStream, length: Long,
                        expectedSha256: String?, progress: (Long) -> Unit): String {
        if (!enabled) throw UploadPermissionRevokedException()
        val data = body.readBytes()
        check(data.size.toLong() == length)
        val actual = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256) throw UploadHashMismatchException()
        beforeSave?.invoke()
        saved.add(fileName to data)
        afterSave?.invoke()
        return fileName
    }
}

private fun expectFailure(action: () -> Unit) {
    check(runCatching(action).isFailure) { "Expected a rejected upload" }
}

fun main() {
    val root = File(System.getProperty("java.io.tmpdir"), "netkit-resume-test-" + UUID.randomUUID())
    check(root.mkdirs())
    val store = FakeBrowserStorage()
    var now = 1000L
    val stage = ResumableBrowserUpload(root, store, { store.epoch }, { store.enabled }, { now })
    fun id() = UUID.randomUUID().toString().replace("-", "")
    val content = "Resume after a real interrupted HTTPS chunk, including Arabic: مرحبا".toByteArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it) }
    val a = id()
    val first = content.copyOfRange(0, 19)
    val last = content.copyOfRange(19, content.size)
    check(!stage.append(a, "study.txt", emptyList(), content.size.toLong(), 0,
        first.size.toLong(), digest, ByteArrayInputStream(first)).complete)
    check(stage.status(a)?.received == first.size.toLong())
    expectFailure {
        stage.append(a, "study.txt", emptyList(), content.size.toLong(), 0,
            first.size.toLong(), digest, ByteArrayInputStream(first))
    }
    check(stage.append(a, "study.txt", emptyList(), content.size.toLong(), first.size.toLong(),
        last.size.toLong(), digest, ByteArrayInputStream(last)).complete)
    check(store.saved.single().second.contentEquals(content))
    check(stage.status(a)?.complete == true)
    check(stage.append(a, "study.txt", emptyList(), content.size.toLong(), content.size.toLong(),
        0, digest, ByteArrayInputStream(byteArrayOf())).complete)
    check(store.saved.size == 1) { "Lost final response must not duplicate SAF commit" }

    val b = id()
    val truncated = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    expectFailure {
        stage.append(b, "part.bin", emptyList(), 6, 0, 6, null, truncated)
    }
    check(stage.status(b)?.received == 3L) { "Recover actual persisted offset after short network chunk" }
    check(stage.append(b, "part.bin", emptyList(), 6, 3, 3, null,
        ByteArrayInputStream(byteArrayOf(4, 5, 6))).complete)
    check(store.saved.last().second.contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6)))

    // A socket/provider exception after partial progress must sync the prefix before
    // reporting its resumable offset, not only when the body returns ordinary EOF.
    val interruptedByException = id()
    val throwingBody = object : ByteArrayInputStream(byteArrayOf(31, 32, 33, 34, 35, 36)) {
        var consumed = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= 2) throw IOException("simulated socket reset")
            val n = super.read(b, off, minOf(len, 2 - consumed))
            if (n > 0) consumed += n
            return n
        }
    }
    expectFailure {
        stage.append(interruptedByException, "reset.bin", emptyList(), 6, 0, 6,
            null, throwingBody)
    }
    check(stage.status(interruptedByException)?.received == 2L) {
        "Only the durable partial chunk may be advertised after a socket reset"
    }
    check(File(root, "$interruptedByException.part").readBytes().contentEquals(byteArrayOf(31, 32)))
    check(stage.append(interruptedByException, "reset.bin", emptyList(), 6, 2, 4,
        null, ByteArrayInputStream(byteArrayOf(33, 34, 35, 36))).complete)
    check(store.saved.last().second.contentEquals(byteArrayOf(31, 32, 33, 34, 35, 36)))

    val c = id()
    stage.append(c, "old.bin", emptyList(), 2, 0, 1, null, ByteArrayInputStream(byteArrayOf(7)))
    store.enabled = false
    store.epoch++
    store.enabled = true
    check(stage.status(c) == null) { "Disable/re-enable must revoke old chunks" }
    expectFailure {
        stage.append(c, "old.bin", emptyList(), 2, 1, 1, null, ByteArrayInputStream(byteArrayOf(8)))
    }
    expectFailure {
        stage.append(c, "old.bin", emptyList(), 2, 0, 1, null, ByteArrayInputStream(byteArrayOf(8)))
    }

    val d = id()
    stage.append(d, "original.txt", emptyList(), 3, 0, 1, null, ByteArrayInputStream(byteArrayOf(1)))
    expectFailure {
        stage.append(d, "renamed.txt", emptyList(), 3, 1, 2, null, ByteArrayInputStream(byteArrayOf(2, 3)))
    }
    val e = id()
    expectFailure {
        stage.append(e, "wrong.txt", emptyList(), 1, 0, 1, "0".repeat(64),
            ByteArrayInputStream(byteArrayOf(1)))
    }
    check(stage.status(e) == null)
    val f = id()
    check(stage.append(f, "empty.txt", emptyList(), 0, 0, 0, null,
        ByteArrayInputStream(byteArrayOf())).complete)

    val g = id()
    stage.append(g, "expire.txt", emptyList(), 2, 0, 1, null, ByteArrayInputStream(byteArrayOf(1)))
    now += 16L * 60 * 1000
    check(stage.status(g) == null) { "Expired parts must be deleted" }
    expectFailure {
        stage.append(g, "expire.txt", emptyList(), 2, 0, 1, null, ByteArrayInputStream(byteArrayOf(2)))
    }

    // A long but continuously progressing upload must not expire after 15 minutes.
    val slow = id()
    val slowData = byteArrayOf(11, 12, 13, 14)
    val tickingBody = object : ByteArrayInputStream(slowData) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, minOf(len, 1))
            if (n > 0) now += 5L * 60 * 1000
            return n
        }
    }
    check(stage.append(slow, "slow.bin", emptyList(), slowData.size.toLong(), 0,
        slowData.size.toLong(), null, tickingBody).complete)
    check(stage.status(slow)?.complete == true) {
        "Continuously progressing transfer must remain live across the TTL window"
    }
    check(store.saved.last().second.contentEquals(slowData))

    // Polling a stalled upload must NOT keep its private staging bytes forever.
    val polled = id()
    stage.append(polled, "idle.bin", emptyList(), 2, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(42)))
    check(File(root, "$polled.part").exists())
    now += 10L * 60 * 1000
    check(stage.status(polled)?.received == 1L)
    now += 6L * 60 * 1000
    check(stage.status(polled) == null) { "Status-only polling must not renew idle TTL" }
    check(!File(root, "$polled.part").exists()) { "Expired partial bytes must be removed" }

    // Real resumed progress does renew the inactivity deadline.
    val progressing = id()
    stage.append(progressing, "progress.bin", emptyList(), 3, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(1)))
    now += 10L * 60 * 1000
    stage.append(progressing, "progress.bin", emptyList(), 3, 1, 1, null,
        ByteArrayInputStream(byteArrayOf(2)))
    now += 10L * 60 * 1000
    check(stage.status(progressing)?.received == 2L) {
        "Actual resumed bytes should refresh idle TTL"
    }
    now += 6L * 60 * 1000
    check(stage.status(progressing) == null)

    // Repeated polls must also release completed-session slots after the TTL.
    val completed = id()
    check(stage.append(completed, "done.bin", emptyList(), 1, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(99))).complete)
    now += 10L * 60 * 1000
    check(stage.status(completed)?.complete == true)
    now += 6L * 60 * 1000
    check(stage.status(completed) == null) { "Completed status must eventually expire" }
    // M21: empty requests must not create sessions or keep incomplete files alive.
    val emptyAttempt = id()
    expectFailure {
        stage.append(emptyAttempt, "empty-keepalive.bin", emptyList(), 2, 0, 0, null,
            ByteArrayInputStream(byteArrayOf()))
    }
    check(stage.status(emptyAttempt) == null) {
        "Zero-progress request must not consume one of four upload slots"
    }
    check(!File(root, "$emptyAttempt.part").exists())

    val stalledByEmpty = id()
    stage.append(stalledByEmpty, "no-keepalive.bin", emptyList(), 2, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(7)))
    now += 10L * 60 * 1000
    expectFailure {
        stage.append(stalledByEmpty, "no-keepalive.bin", emptyList(), 2, 1, 0, null,
            ByteArrayInputStream(byteArrayOf()))
    }
    check(stage.status(stalledByEmpty)?.received == 1L)
    now += 6L * 60 * 1000
    check(stage.status(stalledByEmpty) == null) {
        "Rejected zero-progress keepalive must not extend inactivity TTL"
    }
    check(!File(root, "$stalledByEmpty.part").exists())

    // M21: a SAF provider that takes longer than the inactivity TTL must not
    // immediately lose the committed result and risk duplicate publication.
    val longCommit = id()
    store.beforeSave = { now += 16L * 60 * 1000 }
    check(stage.append(longCommit, "long-commit.bin", emptyList(), 1, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(55))).complete)
    store.beforeSave = null
    check(stage.status(longCommit)?.complete == true) {
        "Successful slow SAF commit must start a fresh completion replay window"
    }
    check(stage.append(longCommit, "long-commit.bin", emptyList(), 1, 1, 0, null,
        ByteArrayInputStream(byteArrayOf())).complete)
    check(store.saved.count { it.first == "long-commit.bin" } == 1) {
        "Lost final response after slow commit must not duplicate SAF publication"
    }


    // M25: cleanup must measure TTL after waiting for an in-flight append lock.
    // A stalled/failed chunk can hold the session lock while the monotonic
    // clock advances; sampling before the lock would preserve expired bytes.
    val raceRoot = File(System.getProperty("java.io.tmpdir"),
        "netkit-resume-race-" + UUID.randomUUID())
    check(raceRoot.mkdirs())
    val raceNow = java.util.concurrent.atomic.AtomicLong(1000L)
    val raceStore = FakeBrowserStorage()
    val raceStage = ResumableBrowserUpload(raceRoot, raceStore,
        { raceStore.epoch }, { raceStore.enabled }, { raceNow.get() })
    val raceId = id()
    raceStage.append(raceId, "stalled.bin", emptyList(), 3, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(1)))
    val readEntered = java.util.concurrent.CountDownLatch(1)
    val releaseRead = java.util.concurrent.CountDownLatch(1)
    val stalledBody = object : java.io.InputStream() {
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            readEntered.countDown()
            check(releaseRead.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                "Timed out waiting to release stalled chunk"
            }
            return -1 // Simulate a broken network chunk without byte progress.
        }
    }
    val appendRejected = java.util.concurrent.atomic.AtomicBoolean(false)
    val appendWorker = Thread {
        appendRejected.set(runCatching {
            raceStage.append(raceId, "stalled.bin", emptyList(), 3, 1, 2, null,
                stalledBody)
        }.isFailure)
    }
    appendWorker.start()
    check(readEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
        "Append never acquired its session lock"
    }
    val expiredAfterWait = java.util.concurrent.atomic.AtomicBoolean(false)
    val statusWorker = Thread {
        expiredAfterWait.set(raceStage.status(raceId) == null)
    }
    statusWorker.start()
    try {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (statusWorker.state != Thread.State.BLOCKED &&
            System.nanoTime() < deadline) Thread.sleep(5)
        check(statusWorker.state == Thread.State.BLOCKED) {
            "Status did not wait for the in-flight session lock"
        }
        // TTL expires while cleanup is blocked, not before it enters.
        raceNow.addAndGet(16L * 60 * 1000)
    } finally {
        releaseRead.countDown()
    }
    appendWorker.join(5000)
    statusWorker.join(5000)
    check(!appendWorker.isAlive && !statusWorker.isAlive) {
        "Race regression workers failed to terminate"
    }
    check(appendRejected.get()) { "Truncated stalled chunk must fail" }
    check(expiredAfterWait.get()) {
        "Cleanup must expire an idle partial upload after its lock wait"
    }
    check(!File(raceRoot, "$raceId.part").exists()) {
        "Expired staging file must be removed after the lock wait"
    }
    raceStage.close()
    check(!raceRoot.exists())


    // M25: explicit cancellation must reclaim private bytes immediately, and
    // a delayed in-flight retry must not resurrect the cancelled upload ID.
    val cancelledId = id()
    stage.append(cancelledId, "cancelled.bin", emptyList(), 3, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(8)))
    check(File(root, "$cancelledId.part").exists())
    check(stage.cancel(cancelledId))
    check(!File(root, "$cancelledId.part").exists()) {
        "Explicit cancellation must discard staged bytes without waiting for TTL"
    }
    check(stage.status(cancelledId) == null)
    check(!stage.cancel(cancelledId)) { "Cancelling twice must be idempotent" }
    expectFailure {
        stage.append(cancelledId, "cancelled.bin", emptyList(), 3, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(8)))
    }
    check("cancelled.bin" !in store.saved.map { it.first }) {
        "Cancelled bytes must never reach the SAF destination"
    }
    // A cancellation can arrive before its first network chunk: reject the
    // delayed chunk instead of creating a new private staging file.
    val earlyCancelled = id()
    check(!stage.cancel(earlyCancelled)) { "No existing staging file to discard" }
    expectFailure {
        stage.append(earlyCancelled, "late-first-chunk.bin", emptyList(), 2, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(1)))
    }
    check(stage.status(earlyCancelled) == null)
    val committedId = id()
    check(stage.append(committedId, "already-saved.bin", emptyList(), 1, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(9))).complete)
    check(!stage.cancel(committedId)) {
        "Cancellation must not claim to roll back a committed SAF file"
    }
    check(stage.status(committedId)?.complete == true)
    expectFailure { stage.cancel("invalid-id") }

    // M30: never evict a live retired-ID tombstone. Once the bounded ledger fills,
    // new sessions fail closed, but an already-admitted upload may finish.
    val capRoot = File(System.getProperty("java.io.tmpdir"), "netkit-retired-cap-" + UUID.randomUUID())
    check(capRoot.mkdirs())
    val capStore = FakeBrowserStorage()
    val capNow = java.util.concurrent.atomic.AtomicLong(1000L)
    val capStage = ResumableBrowserUpload(capRoot, capStore,
        { capStore.epoch }, { capStore.enabled }, { capNow.get() })
    val admitted = id()
    capStage.append(admitted, "admitted.bin", emptyList(), 2, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(1)))
    repeat(4096) {
        check(!capStage.cancel(id())) { "Unknown cancellation must not claim a deleted file" }
    }
    val overflow = id()
    check(!capStage.cancel(overflow))
    expectFailure {
        capStage.append(overflow, "overflow.bin", emptyList(), 1, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(9)))
    }
    check(capStage.append(admitted, "admitted.bin", emptyList(), 2, 1, 1, null,
        ByteArrayInputStream(byteArrayOf(2))).complete) {
        "Admission limit must not interrupt an already active upload"
    }
    check(capStore.saved.single().second.contentEquals(byteArrayOf(1, 2)))
    capStage.close()
    check(!capRoot.exists())

    // M35: once the SAF storage adapter has committed the destination, a consent
    // toggle immediately afterward must not turn success into a failed response.
    // The same upload ID remains a read-only final-response replay, never a re-publish.
    val commitThenRevoke = id()
    store.afterSave = {
        store.enabled = false
        store.epoch++
    }
    check(stage.append(commitThenRevoke, "commit-race.bin", emptyList(), 1, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(77))).complete) {
        "A successful SAF commit must be acknowledged even if consent is revoked just after rename"
    }
    store.afterSave = null
    check(stage.status(commitThenRevoke)?.complete == true) {
        "Committed response must remain queryable after upload consent is revoked"
    }
    check(stage.append(commitThenRevoke, "commit-race.bin", emptyList(), 1, 1, 0, null,
        ByteArrayInputStream(byteArrayOf())).complete) {
        "Lost final response must be replayable after consent revocation"
    }
    check(store.saved.count { it.first == "commit-race.bin" } == 1) {
        "Final-response replay must never publish a duplicate file"
    }
    expectFailure {
        stage.append(commitThenRevoke, "commit-race.bin", emptyList(), 1, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(78)))
    }
    expectFailure {
        stage.append(id(), "new-after-revoke.bin", emptyList(), 1, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(1)))
    }

    // Pending-upload admission counts only in-flight sessions, not completed replay records.
    val activeRoot = File(System.getProperty("java.io.tmpdir"), "netkit-pending-cap-" + UUID.randomUUID())
    check(activeRoot.mkdirs())
    val activeStore = FakeBrowserStorage()
    val activeStage = ResumableBrowserUpload(activeRoot, activeStore,
        { activeStore.epoch }, { activeStore.enabled }, { 1000L })
    val activeIds = mutableListOf<String>()
    repeat(4) { index ->
        val taskId = id()
        activeIds += taskId
        check(!activeStage.append(taskId, "pending-$index.bin", emptyList(), 2, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(index.toByte()))).complete)
    }
    expectFailure {
        activeStage.append(id(), "fifth-pending.bin", emptyList(), 2, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(5)))
    }
    check(activeStage.append(activeIds.first(), "pending-0.bin", emptyList(), 2, 1, 1, null,
        ByteArrayInputStream(byteArrayOf(9))).complete)
    check(!activeStage.append(id(), "fifth-pending.bin", emptyList(), 2, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(5))).complete) {
        "Completing one transfer must free a pending slot"
    }
    activeStage.close()
    check(!activeRoot.exists())

    // Completed response records are bounded too. Eviction retires the old ID,
    // preventing a delayed retry from re-publishing a file after its replay record is gone.
    val cacheRoot = File(System.getProperty("java.io.tmpdir"), "netkit-completed-cache-" + UUID.randomUUID())
    check(cacheRoot.mkdirs())
    val cacheStore = FakeBrowserStorage()
    val cacheStage = ResumableBrowserUpload(cacheRoot, cacheStore,
        { cacheStore.epoch }, { cacheStore.enabled }, { 1000L })
    val cacheIds = mutableListOf<String>()
    repeat(65) { index ->
        val taskId = id()
        cacheIds += taskId
        check(cacheStage.append(taskId, "cache-$index.bin", emptyList(), 0, 0, 0, null,
            ByteArrayInputStream(byteArrayOf())).complete)
    }
    check(cacheStage.status(cacheIds.last())?.complete == true)
    check(cacheStage.status(cacheIds.first()) == null)
    expectFailure {
        cacheStage.append(cacheIds.first(), "cache-0.bin", emptyList(), 0, 0, 0, null,
            ByteArrayInputStream(byteArrayOf()))
    }
    check(cacheStore.saved.size == 65) { "Completed cache eviction must not duplicate or lose committed outputs" }
    cacheStage.close()
    check(!cacheRoot.exists())

    // M62: a backwards clock retires every partial upload; later recovery
    // must not revive the old ID or admit a fresh upload in the same share.
    val clockRoot = File(System.getProperty("java.io.tmpdir"),
        "netkit-clock-rollback-" + UUID.randomUUID())
    check(clockRoot.mkdirs())
    val clockStore = FakeBrowserStorage()
    val clockNow = java.util.concurrent.atomic.AtomicLong(1_000L)
    val clockStage = ResumableBrowserUpload(clockRoot, clockStore,
        { clockStore.epoch }, { clockStore.enabled }, { clockNow.get() })
    val clockId = id()
    clockStage.append(clockId, "clock-rollback.bin", emptyList(), 3, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(1)))
    check(File(clockRoot, "$clockId.part").readBytes().contentEquals(byteArrayOf(1)))
    clockNow.set(900L)
    check(clockStage.status(clockId) == null) {
        "Clock rollback must invalidate an existing partial upload"
    }
    check(!File(clockRoot, "$clockId.part").exists()) {
        "Clock rollback must remove private partial bytes"
    }
    clockNow.set(2_000L)
    expectFailure {
        clockStage.append(clockId, "clock-rollback.bin", emptyList(), 3, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(2)))
    }
    expectFailure {
        clockStage.append(id(), "new-after-clock-failure.bin", emptyList(), 1, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(3)))
    }
    check(clockStore.saved.isEmpty()) { "Clock failure must never publish staged data" }
    clockStage.close()
    check(!clockRoot.exists())

    // M64: the final activity timestamp must use the same monotonic guard as
    // session creation and chunk progress. A zero-byte upload has no body read
    // to expose a clock rollback, so regression at this boundary must fail closed.
    val finalClockRoot = File(System.getProperty("java.io.tmpdir"),
        "netkit-final-clock-rollback-" + UUID.randomUUID())
    check(finalClockRoot.mkdirs())
    val finalClockStore = FakeBrowserStorage()
    val finalClockReads = java.util.concurrent.atomic.AtomicInteger()
    val finalClockStage = ResumableBrowserUpload(finalClockRoot, finalClockStore,
        { finalClockStore.epoch }, { finalClockStore.enabled }) {
        when (finalClockReads.getAndIncrement()) {
            0 -> 1_000L // cleanup observes the initial clock
            1 -> 1_000L // newly created session records the same clock
            else -> 999L // old implementation accepted this unguarded final read
        }
    }
    expectFailure {
        finalClockStage.append(id(), "empty-clock-rollback.bin", emptyList(),
            0, 0, 0, null, ByteArrayInputStream(byteArrayOf()))
    }
    check(finalClockStore.saved.isEmpty()) {
        "Clock rollback at the final zero-byte upload boundary must never publish"
    }
    expectFailure {
        finalClockStage.append(id(), "new-after-final-clock-failure.bin", emptyList(),
            0, 0, 0, null, ByteArrayInputStream(byteArrayOf()))
    }
    finalClockStage.close()
    check(!finalClockRoot.exists())

    stage.close()
    check(!root.exists()) { "Stop share must clean private session staging" }
    // M63: an expired upload must remain tracked and fail closed if its private
    // staging file cannot be deleted. A later cleanup retries deletion before
    // dropping the tombstoned session, so the failure is never silently forgotten.
    val cleanupRoot = File(System.getProperty("java.io.tmpdir"),
        "netkit-resume-cleanup-failure-" + UUID.randomUUID())
    check(cleanupRoot.mkdirs())
    val cleanupStore = FakeBrowserStorage()
    var cleanupNow = 1000L
    var allowPartDelete = false
    val cleanupStage = ResumableBrowserUpload(cleanupRoot, cleanupStore,
        { cleanupStore.epoch }, { cleanupStore.enabled }, { cleanupNow },
        { part -> if (allowPartDelete) part.delete() else false })
    val cleanupId = id()
    cleanupStage.append(cleanupId, "cleanup.bin", emptyList(), 2, 0, 1, null,
        ByteArrayInputStream(byteArrayOf(6)))
    val cleanupPart = File(cleanupRoot, "$cleanupId.part")
    check(cleanupPart.exists())
    cleanupNow += 16L * 60 * 1000
    expectFailure { cleanupStage.status(cleanupId) }
    check(cleanupPart.exists()) { "Failed deletion must not be reported as cleaned" }
    allowPartDelete = true
    check(cleanupStage.status(cleanupId) == null)
    check(!cleanupPart.exists()) { "A later cleanup must retry deleting retired staging" }
    expectFailure {
        cleanupStage.append(cleanupId, "cleanup.bin", emptyList(), 2, 0, 1, null,
            ByteArrayInputStream(byteArrayOf(7)))
    }
    cleanupStage.close()
    check(!cleanupRoot.exists())

    println("ResumableBrowserUploadTest PASS: offset, integrity, consent, expiry, polling TTL, zero-progress rejection, long SAF commit, resumed progress, active slow transfer, lock-wait expiry, explicit cancellation, cleanup deletion retry and replay guard")
}
