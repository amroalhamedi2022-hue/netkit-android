import com.netkit.local.transfer.ProgressCheckpoint

fun main() {
    var now = 0L
    val events = mutableListOf<Long>()
    val checkpoint = ProgressCheckpoint({ events.add(it) }, { now }, 250)
    repeat(10_000) { checkpoint.onBytes(it.toLong()) }
    check(events.isEmpty())
    now += 250_000_000L
    checkpoint.onBytes(10_000)
    check(events == listOf(10_000L))
    repeat(20_000) { checkpoint.onBytes((10_001 + it).toLong()) }
    check(events.size == 1)
    checkpoint.flush()
    check(events == listOf(10_000L, 30_000L))
    checkpoint.flush()
    check(events.size == 2)
    check(runCatching { checkpoint.onBytes(1) }.isFailure)
    check(runCatching { ProgressCheckpoint({}, intervalMillis = 0) }.isFailure)
    println("PASS: 30000 chunks -> two journal commits; monotonicity; terminal flush")
}
