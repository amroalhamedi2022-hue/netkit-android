# NetKit continuous-development checkpoint — 2026-10-23

**Authoritative feature branch:** `feature/netkit-reliability-20261023`
**Verified source SHA:** `df2e4cd0c63a97dcac336b9be63ae89a7c632efe`
**CI run:** https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/39945512032
**Result:** SUCCESS, all 22 recorded steps passed, including Kotlin/JVM, Android assembleDebug, lintDebug, APK signing/metadata, ZIP packaging and Artifact upload.
**Artifact:** https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/39945512032/artifacts/12498486089
**Artifact label:** NetKit-development-debug, compressed size 11,796,440 bytes, includes signed DEBUG APK, code archive, checksums and reports. This is not a final product release or a device-tested APK.
**Branch safety:** `main` has NOT been merged or overwritten; this checkpoint itself is documentation-only.

## Implementation changes in current reliability sprint
1. `share/SessionLease.kt` — monotonic, revocable, bounded user-session lifetime. `TransferHttpEngine.kt` returns HTTP 410 for expired session token requests and revokes lease on close. `NetKitCore.kt` actually passes saved 5–1440 minute lifetime into the server. `qa/CoreSmokeTest.kt` now tests expired requests over real TLS, not merely clock math.
2. `AndroidShareStorage.kt` — browser uploads are **OFF by default** until user enables them. SAF selected-folder and shared-storage backends preserved.
3. `remote/RemoteFileClient.kt` and `remote/ProgressStreams.kt` — fixed Kotlin API mismatch between FTP/SFTP screens and client, now expose actual cumulative stream progress for FTP and SFTP without inventing percentages. `qa/ProgressStreamsTest.kt` verifies binary integrity and no double counting.
4. `transfer/TransferTelemetry.kt` — measured, smoothed throughput and ETA based on monotonic time, never advertised as actual remote commit. `NetKitProScreens.kt` displays measured MiB/s in Transfer Center when RUNNING.
5. `transfer/ProgressCheckpoint.kt` — throttles expensive sync disk-journal writes to a 250 ms cadence and flushes final bytes on both successful and failed transfer paths. Test proves 30,000 callbacks produce only 2 journal writes.
6. `ui/ShareQrPanel.kt` — real QR image generation for authenticated HTTPS browser-share URLs, hidden unless owner explicitly reveals; does not save link to logs or public media. Integrated into each shared IP link. Dependency `com.google.zxing:core:3.5.4` licensed Apache-2.0: https://central.sonatype.com/artifact/com.google.zxing/core/3.5.4 .
7. `ui/NetKitProApp.kt` — repaired incorrect Compose LocalContentColor import identified via real Kotlin compiler.
8. `.github/workflows/netkit-import-build.yml` — recognizes NetKitPro source, supports branch-specific CI, isolates cancellation per branch, removes unsafe automatic `HEAD:main` pushes, read-only action token.
9. `qa/run_jvm_tests.sh` — compiles correct production source dependencies and includes new session, throughput, byte streams and persistence-throttle tests.

## Actual verification
- GitHub Linux runner allocated and executed full steps after earlier zero-runner failure.
- Kotlin/JVM network regression **PASS**: TLS, secure tokens, range downloads, upload SHA-256 and refusal, ZIP streaming and live session expiry.
- Kotlin/JVM `TransferQueueTest.kt`, `DirectoryArchiveTest.kt`, `ProgressCheckpointTest.kt`, `TransferTelemetryTest.kt`, `ProgressStreamsTest.kt`, `SessionLeaseTest.kt`: passed in the full CI build.
- Real Android `:app:assembleDebug`: PASS in run 39945512032; `:app:lintDebug`: PASS.
- APK verified with `apksigner` and Android metadata with `aapt`; source zip and SHA checks packaged by workflow.
- **NOT VERIFIED**: Pad 5 real UI performance/fidelity, certificate acceptance and QR scan from second device, SAF full-folder end-to-end transfer, FTP/SFTP interoperability, Android native pairing, actual resume after disconnection, hotspot settings/band and battery lifecycles. They remain open.

## Required feature gates — never mark complete without the user acceptance evidence
| Gate | Code/CI status | Real functional acceptance |
| --- | --- | --- |
| M1 Liquid Aurora + comprehensive settings | Compiles, themes/motion present | NOT VERIFIED on Pad 5 |
| M2 Browser+folder share+storage+QR | Server and QR compile, QA passes | NEEDS real Android↔Windows transfers and QR scan |
| M3 Discovery, pairing, native Android↔Android | NSD discovery source exists | Pairing/auth/native transfer NOT IMPLEMENTED |
| M4 Queue, resumable transfers | Journal+progress+telemetry verified | Resume upload / executor NOT IMPLEMENTED |
| M5 FTP/SFTP clients and servers | Clients compile, byte-progress fixed | Server modes NOT IMPLEMENTED; clients need interoperability |
| M6-M8 Other diagnostics/performance/final | Baseline roadmap remains | Future work per master plan |

## Follow-up issues for automated development
- #1 master requirements and gates
- #2 Pad 5 visual/animation acceptance
- #3 secure pairing and native Android transfer
- #4 genuine resumable upload and durable executor
- #5 FTP and SFTP **server** implementation with SAF and authentication

## Instructions to next ChatGPT automation run
1. Inspect latest repository heads, active PRs and workflow runs before editing (there may be parallel automation). Do not blindly cherry-pick or force-push.
2. Work in a dedicated feature branch; use this branch or PR as a tested baseline only where source is compatible. Never overwrite `main` with unverified code.
3. Pick the next concrete unimplemented requirement, implement it, add negative and positive tests, run JVM + Android CI, and create/refresh a checkpoint with exact SHA and evidence. Do **not** spend the whole run merely reading progress.
4. Prioritize user-visible function and end-to-end security rather than cosmetic progress counters. Do not call saved queue state a resumable protocol.
5. Remain local-first, free/open-source-compatible and non-root. **NO Wi-Fi Analyzer; NO Remote Control**.
6. Do not invent impossible hotspot SSID/password/2.4/5GHz control on the Xiaomi Pad 5. Show actual settings and OS capability limits.
7. The repository is presently PUBLIC: never commit credentials, active session tokens, SSH host secrets, hotspot passwords, signing keys or unredacted screenshots containing them.
8. If no real Pad 5 is available to an automation, label device gate clearly and advance another verifiable code task rather than claiming completion.

**Important:** a successful CI APK is a build gate, not product completeness. Continue until every user requirement is implemented and independently rechecked.
