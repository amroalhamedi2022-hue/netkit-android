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
    println("PASS: concurrent upload name reservations, case folding, collision, rollback")
}
