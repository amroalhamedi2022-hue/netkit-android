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


## M43 — Transfer journal corruption and size bounds (2026-10-26)

**Scope:** PR #6 only; branch `feature/netkit-reliability-20261023`. PR #7 remains separate.

- **Source commit:** `d73cd8893c249c95281a1fb091f5beb7f818aca8`
- **Regression-test commit / tested head:** `ba05ca7e7b1a24fc9c517a9b36663d1d4da1609b`
- **CI:** [run 40324295276](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40324295276) — **PASS** (static checks, Kotlin/JVM transport/SAF ZIP/session tests, persistent transfer queue tests).

### Functional change
- Bounds the persisted journal to 16 MiB and transfer source/destination references to 1,024 characters.
- Rejects duplicate/invalid task IDs, blank/oversized references, negative retry counts, invalid byte ranges, oversized errors, and invalid task counts before accepting restored state.
- Validates state again before persistence and removes abandoned staging files after journal write/move failures.
- Added corruption regressions for duplicate IDs, negative attempts, and an oversized journal file.

### Acceptance gates
- Transfer queue/journal JVM regression: **PASS**
- Static source checks: **PASS**
- Android assembleDebug/lint on this exact tested head: **NOT TESTED** (no package needed for this journal-only gate).
- Xiaomi Pad 5 SAF recovery and actual LAN resume: **NOT TESTED**.
- Protocol-confirmed network resume remains **NOT IMPLEMENTED**; queue reconciliation is not itself a transport.

No merge, no main changes, no new dependency or paid API.


## M54 — Reject invalid transfer progress instead of silently clamping (2026-10-27)

**Scope:** PR #6 only. PR #7 remains on its separate branch.

- **Production change:** `TransferQueue.progress()` now rejects negative, regressing, and over-size byte reports by returning no update. It no longer turns invalid transport evidence into a plausible in-range count through `coerceIn`.
- **Regression coverage:** `qa/TransferQueueTest.kt` verifies that invalid reports do not change the durable count, valid progress still persists, and a resumed task still requires a peer-confirmed offset before completion.
- **Source commit:** `bf050559c40a626c6a46e0b451cadcdd2015d711`
- **Tested source head:** `d83aeaf38c101bf5a33dd8a2101f15665fe1c0d5`
- **CI:** [run 40412794098](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40412794098) — **PASS**. The source-qa job completed successfully; logs explicitly report the persistent queue state-machine regression passing. Android APK/lint jobs were skipped by design.
- **Acceptance:** strict progress-bound JVM regression **PASS**; static/JVM source QA **PASS**; Android compile on this source SHA **NOT TESTED**; Xiaomi Pad 5/Windows end-to-end transfer **NOT TESTED**.
- **Safety:** no merge, no `main` changes, no new dependencies, no APK created. This queue is still a persisted intent/journal, not a completed network-resume protocol.


## 2026-10-27 — M61: Permanently invalidate HTTPS share lease after monotonic rollback

- **Scope:** PR #6 head branch `feature/netkit-reliability-20261023`; PR #7 remains a separate child branch. No merge or main update.
- **Production:** `SessionLease` tracks the last monotonic observation and permanently invalidates the lease if the source moves backwards or elapsed-time arithmetic becomes negative. A recovered clock cannot resurrect the same HTTPS bearer-token session; the owner must create a new lease/session. Lease operations are synchronized so revocation and validity checks do not race.
- **Regression:** `qa/SessionLeaseTest.kt` now checks rollback after a positive elapsed interval, zero remaining lifetime, and continued invalidity after the clock rises above the original start time.
- **Commits:** source `e480e929e33a3b6a02e316350a26e9a8824d5301`; test/head `f7c3ae737d943413a39fc28c85a80f259f9144f6`.
- **Focused local Kotlin/JVM test:** **PASS** using the installed Kotlin compiler and Java runtime; output: `PASS: session expiry, revocation, bounded lifetime and permanent fail-closed clock rollback`.
- **GitHub exact-head CI:** [run 40454871545](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40454871545) and [run 40454869072](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40454869072) — **SUCCESS** on the source/test head; `source-qa` PASS, Android package jobs SKIPPED.
- **Android compile/APK:** **NOT TESTED** in this batch; no package was needed for the lease-state regression.
- **Real Pad 5/Windows browser session-expiry and stop/revoke tests:** **NOT TESTED**.
- **Boundary:** this protects the local monotonic TTL from rollback; it does not authenticate a peer or replace TLS/certificate and user-consent review.
- **Safety:** no dependency, paid API, secret, artifact build, auto-merge or main change.


## 2026-10-27 — M63: Enforce HTTPS share lease through in-flight upload publication

- **Scope:** NetKit PR #6 only, branch `feature/netkit-reliability-20261023`. PR #7 remains on its own child branch; no cherry-pick, merge or main change.
- **Production:** `TransferHttpEngine` now wraps browser-upload bodies in a lease-aware stream. It checks the monotonic share lease before and after each body read, shortens the socket read timeout to the lease's remaining lifetime, returns HTTP 410 when the lease expires mid-upload, and returns 408 for a genuine body-idle timeout while the lease remains valid.
- **SAF commit boundary:** `ShareStorage.uploadGuarded` adds a live authorization hook. `AndroidShareStorage` checks it while receiving data and immediately before/after the temporary SAF document is renamed. If expiry wins before completion, the temporary document is deleted rather than acknowledged as a successful upload.
- **Regression:** `qa/CoreSmokeTest.kt` opens a real local TLS socket, starts an upload, stalls across the lease deadline, and verifies HTTP 410 plus absence of a committed file.
- **Source commit:** `bf768ddff4d0fb784b52120fb548ebd3172081b7`; regression/test head: `c17fc1828c57af4689483cb7cc8c217b7a94753b`.
- **Exact-source CI:** [run 40468661194](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40468661194) and [run 40468667776](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40468667776) — **SUCCESS**. Static checks, HTTPS/TLS transport regressions, upload/hash checks, lease-expiry rejection, the new mid-upload expiry test, and persistent transfer-queue state-machine tests passed.
- **Earlier failed test attempt:** run [40468329222](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40468329222) failed because the new raw-socket test accidentally emitted literal backslash sequences instead of HTTP CRLF. The test was corrected in `c17fc182`; the subsequent exact-head runs passed.
- **Android compile/APK:** **NOT TESTED / NOT BUILT** in this batch; package jobs were not requested for this source-level lifecycle regression.
- **Xiaomi Pad 5↔Windows browser acceptance, SAF-provider cleanup after expiry, and real Wi-Fi/hotspot behavior:** **NOT TESTED**.
- **Boundary:** this enforces lease expiry during an upload; it does not implement true resumable uploads, nor does it complete native pairing, FTP/SFTP server roles, or device acceptance.
- **Live Google Docs Handoff:** **SYNC FAILED** — no authorized Google Docs write/read connector was available in this run. GitHub checkpoint is updated; the live Drive document is not claimed updated.
- **Safety:** no new dependency, paid API, secret, APK artifact, auto-merge or main change.

## 2026-10-27 — M65: Absolute deadline for browser-upload bodies

- **Scope:** PR #6 only, branch `feature/netkit-reliability-20261023`; no PR #7 or `main` changes.
- **Problem:** `SO_TIMEOUT` is an idle timeout per blocking socket read. A slow client could drip bytes just before each idle timeout and keep one of the four HTTPS workers occupied far beyond the configured body timeout (bounded only by the share lease).
- **Production change:** `LeaseBoundUploadInputStream` now measures one absolute monotonic deadline from the start of each request body. Each socket read timeout is capped to the smaller of the remaining body budget and remaining share lease; the deadline is checked both before and after each read. A slow trickle fails with HTTP 408, while share-lease expiry continues to fail closed with HTTP 410.
- **Regression:** `qa/CoreSmokeTest.kt` uses a deterministic clock and a stream that yields a byte every 600 ms against a 1,000 ms total budget. The first byte is accepted; the second read is rejected even though each individual gap is below the idle timeout.
- **Production commit:** [3d9d085c1e66202f3c40086ef6707eebca19a152](https://github.com/amroalhamedi2022-hue/netkit-android/commit/3d9d085c1e66202f3c40086ef6707eebca19a152).
- **Source/test head:** [2747710027106a00b9880aa54711cb7d325388a3](https://github.com/amroalhamedi2022-hue/netkit-android/commit/2747710027106a00b9880aa54711cb7d325388a3).
- **Exact-source CI:** [run 40477238262](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40477238262) — **PASS**. Static source checks, Kotlin/JVM HTTPS transport + SAF ZIP + session tests, and persistent transfer-queue state-machine tests all succeeded.
- **Android compile/APK:** **NOT TESTED / NOT BUILT**; this source-only deadline change was covered by the portable transport suite and did not require a device package.
- **Device acceptance:** slow-trickle behavior over a real HTTPS socket, Xiaomi Pad 5 SAF publication rollback after 408, and Windows browser interoperability remain **NOT TESTED**.
- **Safety:** no new dependency, paid API, secret, artifact, merge or `main` write. Session lease remains the outer lifetime bound; this deadline adds a stricter per-request cap.


## 2026-10-27 — M70: Fail closed on premature EOF in SAF browser uploads

- **Scope:** NetKit PR #6 only; branch `feature/netkit-reliability-20261023`. PR #7, its ZIP branch and `main` untouched.
- **Finding:** `StreamReadGuard.read()` correctly returns `-1` at EOF. `AndroidShareStorage.uploadInternal()` passed that value directly to `OutputStream.write(buffer, 0, n)` when a request body ended before its declared Content-Length, causing a misleading bounds exception instead of a clear truncation failure. The temporary SAF document was still removed in `finally`, but the failure reason was wrong.
- **Production fix:** added JVM-testable `readRequiredUploadChunk()`, which retains bounded transient-zero recovery and throws `EOFException("Connection closed before complete upload")` on early EOF. SAF upload now uses this helper before writing any returned chunk.
- **Regression:** `qa/CoreSmokeTest.kt` reads a short stream, then verifies the next required read fails with the explicit EOF exception.
- **Commits:** transport helper [`e462a91bb021f9598d70153f6fbfed268bddd473`](https://github.com/amroalhamedi2022-hue/netkit-android/commit/e462a91bb021f9598d70153f6fbfed268bddd473); SAF caller [`e0e5b00e68760667b8a22939f9bb6f0a8e0e4e5e`](https://github.com/amroalhamedi2022-hue/netkit-android/commit/e0e5b00e68760667b8a22939f9bb6f0a8e0e4e5e); regression/source-test head [`8dfb3d739aa8e94d89cd7f68707839b98971315d`](https://github.com/amroalhamedi2022-hue/netkit-android/commit/8dfb3d739aa8e94d89cd7f68707839b98971315d).
- **Exact-head CI:** [run 40512823363](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40512823363) — **SUCCESS**. Static source checks, Kotlin/JVM HTTPS/session/SAF ZIP suite (including the early-EOF regression), and persistent transfer-queue state-machine tests all passed.
- **Android compile / APK:** **NOT TESTED / NOT BUILT** by this run; this targeted JVM regression did not justify a package build.
- **Device acceptance:** **NOT TESTED** — Xiaomi Pad 5 SAF provider truncation, Windows browser upload interruption, FTP/SFTP interoperability, hotspot behavior and Liquid UI acceptance remain open.
- **Safety/integration:** no new dependency, paid API, secret or binary artifact; no merge, rebase, cherry-pick, force-push, base retarget or `main` write. NetKit remains separate from Remote Control and has no Wi-Fi Analyzer.
- **Live Google Docs Handoff:** **SYNC FAILED** until an authorized Google Docs write/read connector is available; this repository checkpoint is not a substitute for the live Drive document.

- **Targeted package attempt:** the commit includes the explicit `[device-test-build]` marker for AndroidShareStorage integration, but the only exposed run was a `pull_request` event; the workflow's package gate accepts that marker only on `push` (or an approved manual dispatch). Therefore `device-test-build` was **SKIPPED**, no APK/ZIP artifact was produced, and no package SHA exists. Exact-head source CI still passed. Do not claim Android compile/package acceptance; the current connector exposes no workflow-dispatch write action.


## 2026-10-27 — M80: cancellation-safe stalled SAF reads (PR #6)

- **Problem:** the PR #6 baseline's JVM StreamReadGuard used Thread.yield() after repeated zero-byte reads. A broken SAF/provider stream could hot-spin a worker, and cancelling the worker did not promptly interrupt the retry loop.
- **Fix:** added a bounded exponential pause (1, 2, 4, 8, then 16 ms capped) and checks thread interruption before each provider read. Interruption during backoff restores the thread interrupt flag and throws InterruptedIOException. The existing 33-attempt hard ceiling remains.
- **Source commit:** [dfc474b9](https://github.com/amroalhamedi2022-hue/netkit-android/commit/dfc474b9f3cb75124fea99cdc50c651f19aab3b8).
- **Regression commit / exact tested head:** [28f262c4](https://github.com/amroalhamedi2022-hue/netkit-android/commit/28f262c4af030418b0ce5ba8da7f09cbf9558741) adds an interrupt-during-stall test checking prompt termination, InterruptedIOException, and preservation of the interrupt flag.
- **Exact-head CI:** [push run 40587380242](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40587380242) and [PR run 40587387977](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40587387977) — both **SUCCESS** on 28f262c4af030418b0ce5ba8da7f09cbf9558741. Static source checks, Kotlin/JVM transport/SAF/ZIP/session tests, and persistent transfer queue state-machine tests passed.
- **Build/device:** APK packaging and lint jobs were **SKIPPED** for this source-only milestone; no APK produced. Physical Xiaomi Pad 5 / SAF acceptance remains **NOT TESTED**.
- **Safety/branching:** PR #6 branch only (feature/netkit-reliability-20261023); PR #7 was not modified. No merge, main write, new dependency, paid API, secret or permission bypass.
- **Live Google Docs Handoff:** **SYNC FAILED** — no authorized Docs write/read connector available in this run; this checkpoint is not a substitute for the live Drive document.


## 2026-10-28 — M82: local HTTPS peer scope includes IPv6 ULA (PR #6)

- **Functional change:** the local HTTPS listener now uses `isAllowedLanPeer(InetAddress)`. It preserves the existing private IPv4, link-local and loopback handling and explicitly permits IPv6 unique-local addresses in `fc00::/7`, which Java's `isSiteLocalAddress()` does not classify as site-local. Public/global IPv4 and IPv6 remain rejected by this guard.
- **Source commit:** [4b190e4a](https://github.com/amroalhamedi2022-hue/netkit-android/commit/4b190e4a1b9009d7c7ef8dcbe2ead68445bea446).
- **Regression commit / exact tested head:** [7f178f5a](https://github.com/amroalhamedi2022-hue/netkit-android/commit/7f178f5a4f0345aa748671f5a6d776f541072ef1) adds JVM checks for RFC1918, IPv4 link-local, loopback, IPv6 link-local, IPv6 ULA, and rejection of public IPv4/global IPv6.
- **Exact-head CI:** [run 40602956860](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40602956860) — **SUCCESS** on 7f178f5a4f0345aa748671f5a6d776f541072ef1. `source-qa` passed static checks, Kotlin/JVM transport/SAF/ZIP/session tests and persistent transfer-queue tests. `device-test-build` and `verified-build` were **SKIPPED**.
- **Build/device:** APK packaging and Android compile were **NOT RUN on this exact head**; no APK or new artifact SHA exists. Xiaomi Pad 5 IPv6 ULA / real LAN acceptance remains **NOT TESTED**.
- **Security boundary:** address classification is defense-in-depth, not peer authentication or proof that a route is physically on the same Wi-Fi. Pairing/consent requirements remain unchanged.
- **Branch safety:** PR #6 branch only (`feature/netkit-reliability-20261023`); PR #7 remains untouched. No merge, main write, dependency, paid API, secret or permission bypass.
- **Live Google Docs Handoff:** **SYNC FAILED** — no authorized Docs write/read connector is available in this run; this GitHub checkpoint is not a replacement for the live Drive document.


## M87 — 2026-10-28 — Ignore stale NSD resolution callbacks after stop/restart

- **Scope:** PR #6 branch `feature/netkit-reliability-20261023` only. PR #7 and `main` were not changed.
- **Functional change:** `PeerDiscovery` now tags each discovery session with a monotonically increasing `DiscoveryEpoch`. Stopping discovery invalidates the epoch before calling Android NSD; service-found/lost and asynchronous resolve callbacks check the captured generation before touching the visible peer list. A resolve that completes after stop or restart can no longer repopulate stale peers.
- **Regression coverage:** new pure-Kotlin `qa/DiscoveryEpochTest.kt` checks start → stop → restart → stop generation behavior without requiring an emulator; `qa/run_jvm_tests.sh` now runs it.
- **Source/test SHA:** `f75d9ce2b1ff7e495751337b74c9044b3766c171`.
- **PASS — source QA:** [run 40627613656](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40627613656) — static checks, Kotlin/JVM HTTPS/SAF/ZIP/session/queue checks and the new discovery epoch regression passed.
- **PASS — targeted Android compile/package:** same run completed `:app:assembleDebug`, `:app:lintDebug`, APK signature verification and `aapt` identity check. Package `com.netkit.local`, version `0.6.0-alpha`.
- **Device-test artifact:** [artifact 12790625443](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40627613656/artifacts/12790625443), 11,836,293-byte ZIP; GitHub artifact SHA-256 `ca1e6f6f5d72b84ad0539f53201e1aaccff3ae6969127031f6fe6686d3698008`. Extracted APK SHA-256 `d91bd53cfb50b264fd716dba4439dabc43692543aa4d0a56445c65dddd039fca`; independently recalculated after downloading and extracting the artifact and matched `dist/SHA256SUMS.txt`.
- **NOT TESTED:** real Xiaomi Pad 5 stop/restart while NSD resolution is pending, IPv4/IPv6 discovery on actual LAN, and two-device pairing/transfer. This is a debug acceptance package, not a release.
- **Safety/branch gate:** no dependency, paid API, secret, root, merge or `main` write. Discovery remains sightings only, not authentication.
- **Live Google Docs handoff:** **SYNC FAILED** — no authorized Docs write/read connector was available in this run.


## 2026-10-28 — M88 SAF upload publication rollback on session expiry

- **Source/test SHA:** `06064aa41ccc7bdb6d7d02e7835c47dc00ea5a51`.
- **Functional fix:** browser uploads now use the URI returned by `DocumentsContract.renameDocument`. If the HTTPS share lease becomes invalid after rename, rollback targets that exact post-rename URI rather than the stale temporary `DocumentFile` URI. If the SAF provider refuses rollback, the operation raises an explicit I/O failure and preserves both the rollback and authorization failures; it does not claim cleanup succeeded.
- **Regression coverage:** `qa/GuardedPublicationTest.kt` covers successful publication, expiry before publication, expiry immediately after rename with exact-URI rollback, provider rollback failure, and rename failure. The test is part of `qa/run_jvm_tests.sh`.
- **PASS — exact source/test CI:** [run 40638530563](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40638530563). Static checks, Kotlin/JVM HTTPS/SAF/ZIP/session tests, the new publication regression, and persistent transfer-queue tests passed.
- **PASS — targeted Android build and package verification:** [run 40639270677](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40639270677) on build head `1291171e261d7ce2d9c61d722d27cb82a2fda8b5`. `:app:assembleDebug`, `:app:lintDebug`, `apksigner verify`, `aapt` identity check, packaging and Artifact upload all passed.
- **Verified device-test Artifact:** [Artifact 12795230533](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40639270677/artifacts/12795230533), ZIP size 11,841,220 bytes. GitHub digest and independently recalculated ZIP SHA-256: `e70048aa96ca0bbc168db188033c5af0801432af66caf08a3d9f92b3b9964d44`.
- **APK identity:** `com.netkit.local`, version `0.6.0-alpha`, versionCode 6, minSdk 26, targetSdk 35, compileSdk 36; APK size 11,760,144 bytes. APK SHA-256 `f6199f20b12690be3f6d0b55a969cada6e5404c2fadb4c2ff1541214c4eaaba1`. I downloaded the Artifact ZIP, verified both entries in `dist/SHA256SUMS.txt`, and independently recalculated the APK and ZIP hashes; all matched.
- **Build classification:** Debug acceptance package only, not a release and not device acceptance.
- **Device acceptance:** **NOT TESTED** — still requires Xiaomi Pad 5 with a user-selected SAF destination and a controlled share-expiry/rollback scenario. Compile success alone is not provider/device acceptance.
- **Safety:** no dependency, paid API, secret, root, merge, or `main` write. Browser upload remains opt-in and the temporary share lease remains authoritative.
- **Live Google Docs handoff:** **SYNC FAILED** — no authorized Docs write/read connector is available in this run.
