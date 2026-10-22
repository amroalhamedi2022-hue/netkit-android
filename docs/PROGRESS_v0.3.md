# NetKit v0.3 — Status update (2026-10-18)

## Source changes
- Kept NetKit separate from Remote Control and without Wi-Fi Analyzer.
- Rotating Android device no longer intentionally ends the network session (`AndroidViewModel`).
- Browser page redesigned with liquid aqua/violet/pink progress, transparent bubble visuals, progress/speed/ETA, multi-file queue and cancel.
- Server tracks/terminates client sockets on Stop; safer interrupted upload behavior.
- SAF uploads serialize duplicate filename assignment.
- Appearance settings persist across app relaunches.
- Local-only Hotspot start/stop and credentials retained; optional configuration band *only Android 16+*, and not promised as actual radio band.

## Verified with tests in container
- Pure Kotlin/JVM transport compiled with Kotlin 1.9 and executed under Java 21.
- Streaming 6 MiB byte-exact upload, download, listing, invalid tokens, invalid filenames, upload disabled, >16 GiB rejection, stopped listener: PASS.
- Embedded browser JS syntax: Node 22 `--check` PASS.

## Not yet verified
- Android Gradle build (Android SDK/Gradle not installed in environment), Android permissions, HyperOS Hotspot, certificate trust from laptop browser, upload cancellation while Android SAF is writing.

## Next priority
- Run Android Studio sync/build, fix native build issues; install and smoke-test on Xiaomi Pad 5.
- Foreground Service for reliable background transfers, pairing/QR, discovered local interfaces and auto-address selection.
- Only after working local transfer: resumable large files, progress improvements, LAN speed measurement, optional SFTP.
