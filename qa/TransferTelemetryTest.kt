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

    // A resumed transfer may legitimately restart from a lower, peer-confirmed
    // durable offset. Reset the measurement epoch without weakening ordinary
    // monotonic-progress validation.
    val resumed = TransferTelemetry({ nanos }, smoothing = 0.5, minimumSampleMillis = 100)
    resumed.reset(100)
    nanos += 1_000_000_000L
    check(resumed.sample(500, 1_000).bytesPerSecond == 400.0)
    resumed.reset(200)
    nanos += 1_000_000_000L
    val afterResume = resumed.sample(300, 1_000)
    check(afterResume.bytesPerSecond == 100.0)
    check(afterResume.estimatedSecondsRemaining == 7L)
    check(runCatching { resumed.sample(250, 1_000) }.isFailure) {
        "Unconfirmed progress regression must still be rejected"
    }
    println("PASS: measured throughput, ETA, idle handling, strict regression and confirmed resume reset")
}
