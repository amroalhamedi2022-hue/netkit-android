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
        saved.add(fileName to data)
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

    val c = id()
    stage.append(c, "old.bin", emptyList(), 2, 0, 1, null, ByteArrayInputStream(byteArrayOf(7)))
    store.enabled = false
    store.epoch++
    store.enabled = true
    check(stage.status(c) == null) { "Disable/re-enable must revoke old chunks" }
    expectFailure {
        stage.append(c, "old.bin", emptyList(), 2, 1, 1, null, ByteArrayInputStream(byteArrayOf(8)))
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

    stage.close()
    check(!root.exists()) { "Stop share must clean private session staging" }
    println("ResumableBrowserUploadTest PASS: offset, integrity, consent, expiry, polling TTL, zero-progress rejection, resumed progress, active slow transfer, empty, duplicate commit")
}
