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

## M84 follow-up — 2026-10-28 — checksum-manifest budget preflight

- Source change: `DirectoryArchive.write` reserves the exact UTF-8 size of each checksum manifest line before opening the corresponding SAF stream. Requests that cannot fit the manifest budget fail before content I/O. The final line length is checked against the reserved cost.
- Regression: `qa/DirectoryArchiveTest.kt` asserts that an undersized manifest quota returns `Archive checksum manifest too large` with zero calls to `ArchiveDirectorySource.open`.
- Exact source/test evidence: [source commit 1d556eb5](https://github.com/amroalhamedi2022-hue/netkit-android/commit/1d556eb59d7089f2d4a6198735648ec3ed6c8edd), [test head cc847af7](https://github.com/amroalhamedi2022-hue/netkit-android/commit/cc847af7514a6d0a3585615bb75c252038a05bad).
- Source QA **PASS**: [run 40612079262](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40612079262). Android Kotlin/resource compile **PASS** without APK: [run 40612079361](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40612079361).
- Existing warning remains: `ResumableBrowserUpload.kt:369` reports `now != null` as always true. Not changed in this milestone.
- The M83 discovery-only debug APK is a separate prior source SHA (`0d1aa2eb...`), package `com.netkit.local.devicetest`; it is not the M84 source and is not a release. Downloaded artifact ZIP SHA-256 `48d680e5b239e849200c8088fcd8a2f43b08b571bf90155436c93f9b50f6f5a4`; extracted APK SHA-256 `3b5b3ec90438d39a3f29a050ceaf058a2daee45418d456ea98642399cdeb7b48`. Artifact expiry and direct link are recorded in `docs/AUTONOMOUS_ACCEPTANCE.md`.
- Device acceptance remains NOT TESTED: Xiaomi Pad 5 DNS-SD/multicast lifecycle, SAF folder export and Android↔Windows browser interoperability.
- Live Google Docs handoff remains **SYNC FAILED** because this run has no authorized Docs write/read connector; do not treat this checkpoint as a replacement for that document.

## M85 — Strict ASCII-decimal HTTP framing and Range parsing (2026-10-28)

- **Production change:** `TransferHttpEngine.kt` now parses `Content-Length`, resumable-upload total/offset, and single-byte-range numbers as ASCII decimal digits only. This rejects values that Kotlin's permissive `toLongOrNull()` path could accept with a leading plus sign, as well as negative, hexadecimal-like, fractional, empty and overflowing values. Invalid `Content-Length` is rejected before route dispatch.
- **Regression coverage:** `qa/CoreSmokeTest.kt` checks the strict parser directly and against the real TLS request parser, including `Content-Length: +0/-0/0x0/overflow`, malformed resumable-upload offsets/totals, and byte ranges such as `bytes=+1-2` and `bytes=-+5`.
- **Exact source + test SHA:** `bd04bbf33d0f194f0932fbc8232596310115a1bc`.
- **CI: PASS** — [run 40615821989](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40615821989). Static source checks, Kotlin/JVM HTTPS transport + SAF ZIP + session tests, and persistent transfer queue tests passed on this exact SHA.
- **Android compile / APK:** **NOT TESTED / NOT BUILT** in this batch. The change was verified in the shared Kotlin/JVM transport harness; no package was needed for this protocol-parser milestone.
- **Device acceptance:** **NOT TESTED** — Xiaomi Pad 5, real SAF providers, and Android↔Windows browser transfers still need hardware acceptance.
- **Safety:** no new dependency, paid API, secret or privilege change. PR #6 remains untouched; no merge and no `main` write.
- **Live Google Docs handoff:** **SYNC FAILED** — no authorized Docs/Drive write-and-read connector is available in this run.


## M86 — 2026-10-28 — IPv6 LAN address visibility (PR #7)

- **Scope:** dashboard address candidates now reuse `PeerAddressPolicy`, displaying RFC1918 IPv4, IPv4 link-local, IPv6 ULA and IPv6 link-local while excluding public/global and loopback addresses. Empty-state wording is no longer IPv4-only.
- **Tests:** address display policy regression added to `qa/PeerAddressPolicyTest.kt`; source QA passed on exact head `d9938d2322999673f420f586b4e59759105ccc66` in [CI 40620943177](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40620943177).
- **Build rationale:** a one-off debug APK was justified for IPv6 address display acceptance on hardware, not as a routine build. `:app:assembleDebug`, `lintDebug`, signature and metadata verification all passed; artifact ID `12787980034`.
- **APK SHA-256:** `b77d918cef26de73bfe3df43aa6733ec641c7079dc3a51c13920eed24cdcd433`. **Artifact ZIP SHA-256:** `b347b9f6157a73f5847d3eb53b0d34e3437444f23e35cd1ff0ddbccaf7085ffd`.
- **NOT TESTED:** real Xiaomi Pad 5 IPv6-only/dual-stack visibility/reachability; Android↔Android pairing, full transfer resume and FTP/SFTP server acceptance remain open.
- **PR safety:** PR #7 remains Draft and unmergeable; PR #6 untouched; no merge or main write.

## M87 — 2026-10-28 — DNS-SD peer publication race and multicast-lock lifecycle

- **Functional issue:** a resolved NSD peer could be appended after `onServiceLost` or discovery stop had already cleared the list, because the ticket check, ticket completion and UI-list mutation were separate operations. Start/stop could also race multicast-lock acquisition/release and leave a new scan without its lock.
- **Source change:** added `connectivity/DiscoveryStateGate.kt` and wired it into `PeerDiscovery.kt`. Resolve completion, service loss, scan stop/restart and multicast lease transitions now serialize under one epoch gate. Stale listener callbacks cannot clear a newer scan or repopulate a stopped scan. The multicast lease is acquired/released while the epoch transition is held to avoid cross-session release races.
- **Regression:** added `qa/DiscoveryStateGateTest.kt`, covering an in-flight resolve racing service loss, stale callback rejection after stop, and a fresh epoch after restart. Wired it into `qa/run_jvm_tests.sh` and required its production wiring in `qa/check_static.py`.
- **Exact tested source head:** `4fc7e636afe768ba4f90f58e59ffbb2a4687aafe`.
- **PASS — source QA:** [run 40633577419](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40633577419). Static checks, Kotlin/JVM HTTPS/SAF ZIP/session tests, persistent queue tests, and the new DNS-SD lifecycle race regression passed. Log confirms: `PASS: DNS-SD peer publication is serialized with loss, stop and restart`.
- **PASS — Android compile/resource gate:** [run 40633577423](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40633577423). This compiled the Android source; no APK was produced.
- **Build/device:** no APK build was justified for this state-machine fix. Xiaomi Pad 5 multicast/NSD lifecycle and Android↔Android discovery remain **NOT TESTED** on physical hardware.
- **PR safety:** PR #7 remains Draft and unmergeable against its current base; PR #6 was not changed. No merge, no `main` write, no new dependency, no paid API, no secret or privilege change. No Wi-Fi Analyzer or Remote Control added.
- **Live Google Docs handoff:** **SYNC FAILED** — no authorized Google Docs write/read connector is available in this run; this checkpoint is not a replacement for the live Drive document.



## M89 — 2026-10-28 — IPv4-mapped IPv6 peer-policy consistency

- The address-policy refinement is kept on PR #7 because this branch already owns `PeerAddressPolicy` and the expanded DNS-SD lifecycle; the parallel policy prototype on PR #6 was reverted to avoid duplicate security logic.
- Source/test head: `1ede85bd05d8d6580201171c10d2f7de671c8991`; exact source QA PASS: [40645811813](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40645811813).
- Added explicit 16-byte IPv4-mapped `Inet6Address` tests. Private and link-local mapped values follow the same IPv4 allowlist; public, loopback and CGNAT mapped values are denied. Existing IPv6 ULA/link-local support is preserved.
- No APK/build/device acceptance claimed. Real Pad 5 DNS-SD acceptance remains NOT TESTED.
- PR #7 is still Draft/unmergeable due to stale stacked base SHA `6a971514...`; no rebase/retarget/merge was attempted. PR #6 and `main` remain separate.
- Live Google Docs Handoff: SYNC FAILED because no authorized Docs write/read connector is available.

## M91 — 2026-10-28 — Reject unsafe DNS-SD labels before peer resolution (PR #7)

- **Scope/branch:** `automation/netkit-zip-stall-guard-20261023` only. PR #6, its source branch, and `main` were not modified.
- **Functional change:** `NsdDiscoveryPolicy.isSafeAdvertisement(serviceType, serviceName)` now combines exact NetKit service-type validation with `PeerDisplayNamePolicy`. `PeerDiscovery` rejects unsafe labels before starting a resolve and revalidates the resolved label before publishing a peer. Discovery labels remain untrusted and never imply pairing/authentication.
- **Regression coverage:** added safe Arabic/ordinary labels and rejects for wrong service type, empty/padded names, bidi-control spoofing, non-NFC Unicode, and labels over the 63-byte DNS-SD bound. The JVM harness compiles the display-name policy alongside the discovery policy; static checks assert both production call sites are wired.
- **Exact latest source/test commit available at recording:** [47770cd50e0da462dade8233ec0da82a80ae089d](https://github.com/amroalhamedi2022-hue/netkit-android/commit/47770cd50e0da462dade8233ec0da82a80ae089d). Earlier production wiring is on the same PR #7 branch.
- **CI:** **NOT VERIFIED** at handoff write time. The configured [NetKit source QA workflow](https://github.com/amroalhamedi2022-hue/netkit-android/actions/workflows/netkit-import-build.yml) and [targeted Android compile workflow](https://github.com/amroalhamedi2022-hue/netkit-android/actions/workflows/netkit-m15-compile.yml) were inspected, but the available status/run query returned no checks for this push head. No CI PASS is claimed.
- **Build/device:** no APK built. Android compile result **NOT VERIFIED**; Xiaomi Pad 5 DNS-SD rejection of malicious labels and real peer discovery **NOT TESTED**.
- **PR gate:** PR #7 remains Draft and unmergeable due to its stale stacked base. No rebase, retarget, merge, or automatic branch mixing. No new dependency, paid API, secret, root, Wi-Fi Analyzer, or Remote Control code.
- **Live Google Docs handoff:** to be synchronized and read back separately after this repository batch.
