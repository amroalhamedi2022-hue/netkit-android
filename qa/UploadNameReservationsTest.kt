package com.netkit.local.share

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

fun main() {
    val names = UploadNameReservations()
    val existing = mutableSetOf("report.pdf")
    val first = names.reserve("report.pdf") { it in existing }
    val second = names.reserve("report.pdf") { it in existing }
    check(first == "report (2).pdf" && second == "report (3).pdf")
    val third = names.reserve("REPORT.PDF") {
        it.equals("report.pdf", ignoreCase = true) || it in existing
    }
    check(third == "REPORT (4).PDF")

    // Upload A is still streaming; upload B must be able to reserve its name.
    val streaming = CountDownLatch(1)
    val done = CountDownLatch(1)
    val thread = thread {
        streaming.await(2, TimeUnit.SECONDS)
        val other = names.reserve("report.pdf") { it in existing }
        check(other == "report (5).pdf")
        names.release(other)
        done.countDown()
    }
    streaming.countDown()
    check(done.await(2, TimeUnit.SECONDS)) { "Slow upload blocked another reservation" }
    thread.join()

    // An external SAF writer takes the original reserved name before commit.
    existing.add(first)
    val finalized = names.commit(first, "report.pdf", { it in existing }) {
        check(it == "report (5).pdf")
        existing.add(it)
        true
    }
    check(finalized == "report (5).pdf")
    names.release(first)
    names.release(second)
    names.release(third)

    val reuse = names.reserve("fresh.txt") { false }
    check(reuse == "fresh.txt")
    check(runCatching {
        names.commit(reuse, "fresh.txt", { false }, { false })
    }.isFailure)
    names.release(reuse)
    check(names.reserve("fresh.txt") { false } == "fresh.txt")
    // Unicode-equivalent and invariant-case aliases must not reserve the same destination twice.
    run {
        val unicodeNames = UploadNameReservations()
        val composed = unicodeNames.reserve("caf\u00e9.txt") { false }
        val decomposed = unicodeNames.reserve("cafe\u0301.txt") { false }
        check(composed == "caf\u00e9.txt")
        check(decomposed == "cafe\u0301 (2).txt")

        val dottedI = unicodeNames.reserve("reportI.txt") { false }
        val dotlessI = unicodeNames.reserve("report\u0131.txt") { false }
        check(dottedI == "reportI.txt")
        check(dotlessI == "report\u0131 (2).txt")

        val upperSigma = unicodeNames.reserve("\u039f\u03a3.txt") { false }
        val lowerSigma = unicodeNames.reserve("\u03bf\u03c3.txt") { false }
        check(upperSigma == "\u039f\u03a3.txt")
        check(lowerSigma == "\u03bf\u03c3 (2).txt")

        // Production builds the reservation namespace from the selected SAF tree URI
        // plus relative folder segments. Identical paths in distinct trees must not collide.
        val treeA = uploadReservationScope("content://provider/tree/alpha", listOf("shared"))
        val treeB = uploadReservationScope("content://provider/tree/beta", listOf("shared"))
        check(treeA == uploadReservationScope("content://provider/tree/alpha", listOf("shared")))
        check(treeA != treeB)
        check(unicodeNames.reserve("same.txt", scope = treeA) { false } == "same.txt")
        check(unicodeNames.reserve("same.txt", scope = treeB) { false } == "same.txt")
        check(unicodeNames.reserve("same.txt", scope = treeA) { false } == "same (2).txt")
    }

    println("PASS: concurrent upload name reservations, Unicode NFC/case aliases, scoped trees, collision, rollback")
}
