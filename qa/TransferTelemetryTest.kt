import com.netkit.local.transfer.TransferTelemetry

fun main() {
    var nanos = 0L
    val t = TransferTelemetry({ nanos }, smoothing = 0.5, minimumSampleMillis = 100)
    check(t.sample(0, 2_000).estimatedSecondsRemaining == null)
    nanos += 1_000_000_000L
    val one = t.sample(1_000, 2_000)
    check(one.bytesPerSecond == 1000.0 && one.estimatedSecondsRemaining == 1L)
    nanos += 1_000_000_000L
    check(t.sample(1_500, 2_000).bytesPerSecond == 750.0)
    nanos += 1_000_000_000L
    check(t.sample(2_000, 2_000).estimatedSecondsRemaining == 0L)
    check(runCatching { t.sample(100) }.isFailure)
    val inconsistent = TransferTelemetry({ nanos })
    val overrun = runCatching { inconsistent.sample(11, 10) }.exceptionOrNull()
    check(overrun is IllegalArgumentException &&
        overrun.message!!.contains("exceed the declared total"))
    val stopped = TransferTelemetry({ nanos }, minimumSampleMillis = 100)
    stopped.sample(0, 100)
    nanos += 1_000_000_000L
    check(stopped.sample(0, 100).estimatedSecondsRemaining == null)
    println("PASS: measured throughput, ETA, idle handling, decreasing-byte rejection")
}
