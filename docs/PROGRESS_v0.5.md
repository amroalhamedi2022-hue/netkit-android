# NetKit 0.5.0-alpha — implementation and QA status

Date: 2026-10-18. This is native Android source, not an APK.

## Changes implemented
- `TransferHttpEngine.kt`: single-range HTTP downloads with 206 / 416, accurate `Content-Length`, `Content-Range` and `Accept-Ranges`. Unknown-length streams continue chunked `200`; download resume is opportunistic, not file integrity validation.
- `AndroidShareStorage.kt`: split upload lock from the document listing/open monitor. Same-destination concurrent uploads serialize for collision avoidance, but downloads/listing can continue during an upload. File sizes below zero are treated as unknown.
- `NetKitApp.kt`: Android ACTION_SEND sharesheet to send a session link; retained Copy and TLS fingerprint verification instructions. No automatic peer pairing is implied.
- Added HTTP range regression cases: bounded, open-ended, suffix, overshooting end, invalid/multi-part, headers and unknown-size compatibility.

## Test evidence
- JVM transport QA: PASS (server, authentication denial, upload 6MiB content match, download, malformed filename, file-size limit, partial download, shutdown).
- Static checks: PASS (AndroidManifest foreground service, Compose call wiring, embedded browser JavaScript parser).
- JavaScript syntax: PASS using Node (when installed).
- **Not run:** Android Gradle compile, instrumentation, physical Pad 5 hotspot/band checks, TLS certificate behavior in a browser, process-kill survival, full Android SAF simultaneous upload/download behavior.

## Work remaining before any installable claim
1. Android Gradle debug build using JDK 17, SDK Platform 36 and Gradle 8.13. Correct build errors from the *actual log*.
2. Install on Xiaomi Pad 5 and test permissions, Hotspot, certificate handshake and sharing background service.
3. File integrity with SHA-256, resumable uploads, explicit trusted-device pairing and QR link/pairing.
4. Polish NetKit UI to more closely match canonical Liquid Aurora / Aqua Glass / Nebula Flow screenshot, while keeping rendering lightweight.

Out of scope: Wi-Fi Analyzer, Remote Control. Work mode reserved for last handoff at user's request.
