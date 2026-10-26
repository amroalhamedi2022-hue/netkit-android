# NetKit — independent development acceptance (2026-10-23)

AUTOMATION_COMPLETE: BLOCKED

## Current patch: bounded SAF ZIP read stalls
- Production: `app/src/main/java/com/netkit/local/share/DirectoryArchive.kt` now tolerates short transient zero-length reads but fails a permanently stalled InputStream after 32 consecutive zero reads. This prevents one malformed SAF provider from spinning indefinitely inside the HTTPS ZIP worker.
- Regression: `qa/DirectoryArchiveTest.kt` checks byte-perfect recovery from two transient empty reads and bounded rejection of a provider returning zero forever. Existing paged/nested/path traversal/quota tests are retained.
- This PR is based on the **existing** `feature/netkit-reliability-20261023` development line, not a reset to old `main`. It does not change Wi-Fi Analyzer or Remote Control scope.
- Production code **changed**. No API charges, secrets, releases, or auto-merges.

## Requirement gate
| Requirement | State | Evidence / next action |
| --- | --- | --- |
| Recursive folder ZIP stream reliability | CODE PATCHED — NOT DEVICE TESTED | `kotlinc .../DirectoryArchive.kt qa/DirectoryArchiveTest.kt -include-runtime -d /tmp/netkit-archive.jar && java -jar /tmp/netkit-archive.jar` plus CI and real SAF provider trials. |
| NetKit HTTPS upload/download, SHA-256 and cancellation | PARTIAL | CI on active development branch exists, but Pad 5↔Windows end-to-end and multi-GB SHA checks remain mandatory. |
| Folder/full shared-storage authorization | PARTIAL | SAF and broad-storage modes exist; permission revoke/traversal/hardware acceptance pending. |
| Native Android discovery/pairing | PARTIAL / NOT VERIFIED | Device discovery work exists on active feature branch, not yet accepted on two devices. |
| FTP/SFTP client and server | NOT VERIFIED | Full feature + authentication and real-device matrix remain. |
| Hotspot settings / Liquid Aurora / performance | PARTIAL | Android public API limits; no forced 5GHz claim, device screenshots/performance missing. |
| Release and independent recheck | BLOCKED | Needs complete feature matrix, CI on exact SHA, real devices, security review, separate recheck. |

**Next:** merge/rebase this narrow PR into the active NetKit development line after its tests pass, verify Android build on resulting SHA, then test SAF ZIP on Pad 5 with a large nested folder. Do not certify product completion.

## Follow-up: Windows-safe folder ZIP extraction (2026-10-23)
- **Production changed:** `DirectoryArchive.kt` rejects Windows reserved filenames/characters, trailing spaces/dots and aliases after Unicode NFC + case folding; treats a file and directory with the same extractable name as a collision. The archive now checks remaining byte quota before incrementing, avoiding Long overflow.
- **Regression source changed:** `qa/DirectoryArchiveTest.kt` adds eight unsafe-name/collision scenarios plus a valid Arabic Unicode filename round-trip. Earlier zero-read recovery and archive quota tests remain.
- **Local verification:** a focused `kotlinc` + `java -jar` reproduction of the updated production ZIP logic and the eight new rejection cases passed on 2026-10-23. This is not a full `qa/run_jvm_tests.sh` run, an Android build, or device verification. Official Dart/Android and full repository CI for this head SHA remain **NOT TESTED**; no GitHub Actions run is associated with the current PR head at the time of writing.
- **Windows naming reference:** https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file
- **Current gate:** PARTIAL — code and focused JVM regression pass; **BLOCKED** for complete Android/SAF/Windows extraction matrix and SHA-256 transfer verification on Xiaomi Pad 5.
- **Next:** run the complete portable JVM suite and Android Gradle build on this exact SHA; verify a nested shared folder and hash its contents after Windows extraction. The PR is not release-ready.

## 2026-10-23 — SAF ZIP defensive hardening and CI trigger (this round)
- **Production source changed:** `DirectoryArchive.kt` rejects bidirectional override/isolate characters and DEL/C1 controls in SAF-supplied ZIP entry names, preventing disguised extensions and confusing extraction labels while retaining ordinary Arabic names. It also aborts immediately when a provider reports an empty page with a non-null continuation offset, instead of traversing an unbounded sequence of empty pages.
- **Regression code changed:** `qa/DirectoryArchiveTest.kt` now exercises four deceptive/control filenames and an endlessly empty SAF page with an assertion that the provider is called exactly once. `qa/run_jvm_tests.sh` now includes this ZIP regression in the standard portable QA gate (previously CI invoked it separately).
- **CI workflow changed:** `.github/workflows/netkit-import-build.yml` now has `pull_request` triggers for the active development bases, so review branches can request Kotlin/Android build checks without writing to `main`. The workflow retains `contents: read` and has **no auto-push-to-main** step.
- **Implementation commits:** `9d501816a5abcffbeb45c69e20ea524fc1413f9f`, `5368f1886d10a335e7ce3e82bc51c1c0b554ebb1`, `eb08fb99453f08b65d4ed13df93c29ee7e46b1c1`, `79bdf16730dfe10c4a5f6c699537114aabdf4983`. PR: https://github.com/amroalhamedi2022-hue/netkit-android/pull/7.
- **PASS (source review):** code changes and regression assertions committed to PR #7, without modifying NetKit's `main` or adding Remote Control.
- **NOT TESTED:** a successful Kotlin/JVM run on this exact SHA; Android Gradle/lint/APK build on this SHA; real SAF provider and Windows extraction; multi-GB SHA-256 verification. No GitHub Actions run was associated with the PR head immediately after the workflow trigger change; check Actions again before attributing failures to source.
- **Remaining:** full M1–M8 acceptance, end-to-end HTTPS folder share and large-file hashes, FTP/SFTP client/server, NSD pairing, LocalOnlyHotspot API-limited controls, design comparison and real-device recheck. This security patch is **not** a feature-complete release.

### Verified CI follow-up — 2026-10-23 (supersedes pending-status text above)
- **PASS — tested source SHA:** `c0b02a6967c50edd47334206aff7aa1b008f9b9f` at https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/39961949177 (workflow `NetKit Android - development build and verification`, conclusion **success**).
- **PASS — portable JVM QA:** `qa/run_jvm_tests.sh`, standalone recursive ZIP regressions including newly added malformed filenames/empty-page tests, static QA and persistent transfer queue tests all completed successfully in the job log.
- **PASS — actual Android build/lint:** `:app:assembleDebug` and `:app:lintDebug` succeeded; APK signature and `com.netkit.local` package verification passed. CI uploaded `NetKit-development-debug` artifact ID `12505760708` with APK, source and checksums.
- **APK SHA-256 (printed in CI logs):** `f77dd7f11e575ebd4f23e1c738d54a987421f473df2f246a5b73f40e43db8657` for `NetKit_v0.6.0-alpha-debug.apk`. Artifact ZIP SHA-256 logged by upload-artifact: `650cfc1c8be0e19b40a45087f1deda80a3975ba7e45db6cbfb35fcedf51c018d`. These are build-artifact hashes, **not** proof of file-transfer hash correctness on devices.
- **NOT TESTED:** actual Android installation, SAF provider behavior, HTTPS transfer of large files, Windows extraction, real hotspot, DNS/ping, FTP/SFTP, and Liquid Aurora visual match. Do not call the product complete.

## 2026-10-23 — HTTPS/SAF zero-read worker-spin prevention (new production patch)
- **Actual production:** `TransferHttpEngine.kt` adds a reusable bounded `StreamReadGuard.read` for known-length HTTPS downloads, unknown-length chunked downloads and `Range` offset skipping; `AndroidShareStorage.kt` uses the same guard for browser uploads. A stream returning 0 forever now fails after 33 attempts instead of monopolizing a worker, and the upload's existing `.part` cleanup path is preserved. Valid short/transient zero reads and EOF are retained.
- **Portable JVM regression:** `qa/CoreSmokeTest.kt` now checks two transient zero reads, byte-perfect recovery, normal EOF, and exactly 33 failed zero reads. Existing real TLS + SHA-256 + Range + ZIP tests remain in `qa/run_jvm_tests.sh`.
- **Implementation SHAs:** `90cb73eb0a2446f3faa74f5deb2890de4e66feb9`, `d4618c2fa164fb85d7a4da13e1f86c1a141dc359`, `c15c121c25a6e4c76ff2c2eb1d87dd2b7303b68f`. Review PR #7; **no main merge**.
- **Status at ledger edit:** source and tests committed, **CI for final SHA PENDING**; previous successful run 39962632739 was for earlier SHA `034967a7...` and must not be cited as a pass for this patch. Device SAF/cloud provider behavior, multi-GB hashes, FTP/SFTP roles, actual hotspot and full M1–M8 remain **NOT TESTED**.
- **Next:** inspect exact-source GitHub Actions, fix any failing Kotlin/Android compilation, then test real Xiaomi Pad 5 ↔ Windows file transfer with SHA-256 and failed-provider cleanup. No new Wi-Fi Analyzer or Remote Control scope.

## 2026-10-23 — Streaming per-file SHA-256 for SAF folder ZIP downloads
- **Production:** `DirectoryArchive.write(includeChecksums = true)` calculates SHA-256 incrementally while writing each SAF file and appends `NetKit-SHA256SUMS.txt` inside the ZIP. The HTTPS browser folder route opts in; other callers keep the old ZIP format by default. A reserved manifest name, manifest-size limit, byte budget and existing Windows-safe path checks prevent silent manifest overwrite or unlimited metadata growth. This detects accidental file corruption after extraction; a checksum shipped inside the same ZIP is **not** proof of authenticity against a malicious sender.
- **Regression:** `qa/DirectoryArchiveTest.kt` checks Arabic nested paths, exact file hashes and payloads, manifest count/byte accounting, quota rejection and manifest-name collision. Java `MessageDigest` is a standard platform API: no new third-party dependency, paid API or license obligation.
- **Changed source commits:** `e7c0075017dd64fabd932bb486814e34549a1cc5`, `c0065d7cfd3bb64ecac22345d1dcf5edd2d53aaa`, `302b434ebac811df7e57478dce31358f54b26bbd`. PR #7 remains DRAFT, based on PR #6's development branch; no main merge.
- **PASS (previous baseline only):** GitHub Actions run `39970419923` completed the earlier source SHA `0abc61269b4a5c62e7221107cdccbe576cf69c0a` successfully; **do not attribute that success to this new checksum patch**.
- **NOT TESTED (current checksum SHA):** exact-SHA JVM, Android build/lint/APK CI run `39978122200` was pending at ledger edit; real Pad 5 SAF/cloud provider, Windows ZIP extraction, multi-GB transfers and device recheck.
- **Next:** inspect CI on the final ledger commit SHA; validate checksum manifest against Windows-extracted files and browser downloads on real hardware. FTP/SFTP server, peer pairing, and complete M1–M8 gates remain incomplete.

### Exact-source CI result — PASS (2026-10-23)
- **PASS** GitHub Actions run [39978258246](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/39978258246) completed successfully on exact source+regression+ledger SHA `79fdc38b4b6b27ffd74f5d59ad66f8d5da4d0809`.
- **PASS** Kotlin/JVM transport QA, persistent queue QA, recursive SAF ZIP regressions including new SHA-256 manifest checks, `:app:assembleDebug`, Android lint and APK signature/package checks, per completed workflow job.
- **PASS** build artifact `NetKit-development-debug` ID `12513145034`; uploaded artifact ZIP SHA-256 `6985e1783fd4ba121e0af5e1198f14fc670b8e1d05c0d3e03edeea4199352f38` (**not** the APK binary hash nor proof of file-transfer integrity).
- **NOT TESTED:** device SAF provider and Windows extraction hashes, actual 4+ GiB transfer/resume, FTP/SFTP client-server, hotspot API behavior, DNS/ping device behavior, Liquid Aurora visual fidelity and independent device recheck. PR #7 stays DRAFT; no main merge.
- This acceptance-only documentation commit does not change the source tested above; a new CI run on the documentation SHA, if triggered, is a separate gate.

## 2026-10-23 — HTTPS browser upload filename hardening (independent NetKit round)
- **Production change:** TransferHttpEngine.kt now rejects deceptive Unicode bidi/control/line-separator filenames, Windows reserved device names and punctuation, trailing dots/spaces and case-insensitive .netkit- temporary-name collisions. The authorized SAF upload destination and explicit upload-enable requirement remain unchanged; ordinary Arabic UTF-8 names still work.
- **Regression:** qa/CoreSmokeTest.kt submits negative cases over an actual loopback TLS server and verifies a valid Arabic filename with exact uploaded bytes. Earlier HTTPS SHA-256, session expiry, Range and recursive ZIP regressions remain in the gate.
- **PASS — exact tested source SHA:** 81e6468233811e55d3376350e858aa52fd868d58, GitHub Actions run https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/39995917975, completed **success**. Kotlin/JVM transport QA (including the new upload cases), recursive ZIP QA, Android :app:assembleDebug, Android lint, APK signature/package verification and artifact upload passed.
- **PASS — available development artifact (NOT final):** NetKit-development-debug ID 12520490752, artifact ZIP SHA-256 88234a202f86bcbe0f3c10037d5480b41eb82117712145bd3289639e021fdc58. Link: https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/39995917975/artifacts/12520490752 . This hash is the ZIP artifact digest, not a real transferred-file checksum.
- **FAIL:** no failing job on this exact source SHA. This does not certify functional release.
- **NOT TESTED / BLOCKED:** physical Xiaomi Pad 5↔Windows/Android LAN transfers, SAF permission revocation, browser certificate/QR acceptance, 4+ GiB resume and device SHA-256, FTP/SFTP server roles, pairing/consent, hotspot/band restrictions, Liquid Aurora screenshot comparison, independent physical-device recheck and installation. M1–M8 are not complete. Keep PR #7 draft, no main merge or release; continue development next round.
- The acceptance ledger commit is documentation-only and **not** the same SHA as the source verified above.

### Independent artifact-byte recheck of NetKit run 39995917975
- **PASS:** downloaded artifact 12520490752 into an isolated workspace; ZIP CRC check passed, computed archive SHA-256 88234a202f86bcbe0f3c10037d5480b41eb82117712145bd3289639e021fdc58 matches GitHub's uploaded digest.
- **PASS:** extracted NetKit_v0.6.0-alpha-debug.apk is 11743760 bytes, SHA-256 1bb1471bebd17539772a923502f92db1b05f71cb86352144a61365aaabe384cf, matching dist/SHA256SUMS.txt inside the artifact and the CI packaging log. This proves byte integrity of the DEVELOPMENT DEBUG APK, not installation, end-to-end transfer correctness or final product readiness.
- **Source/checkout distinction:** PR #7 head 81e6468233811e55d3376350e858aa52fd868d58 was verified by the workflow using GitHub's PR merge checkout cbdf8989779ecdb16a70d1de521019b004acc152. No main update or automatic merge was performed.
- **NOT TESTED:** device installation, Android SAF provider read/write, browser/Windows extraction, large-file resume, FTP/SFTP interoperability, actual QR consent and Liquid Aurora. Continue work and keep automation enabled.

## 2026-10-23 — Source-only development gate and Unicode SAF ZIP hardening
- **CI gate:** `.github/workflows/netkit-import-build.yml` now runs `source-qa` on development push/PR; the existing `verified-build` APK/lint/package/upload job requires explicit `workflow_dispatch` input `release_approved=true` and a passing source job. No APK is built by ordinary development commits. The same source-only policy was applied to the PR #6 branch; the legacy `netkit-build.yml` has only main ZIP and manual triggers, not work-branch push/PR triggers.
- **Production:** `DirectoryArchive.kt` rejects malformed/unpaired UTF-16 surrogate filenames from untrusted SAF providers before ZIP UTF-8 conversion, preserving correctly paired emoji and ordinary Arabic filenames. This prevents replacement-character filename aliasing on extraction. `qa/DirectoryArchiveTest.kt` exercises both rejected isolated surrogate halves and valid emoji.
- **PASS — exact source SHA:** `9bfc0ec35715bc3075cc3b9cd874c12bf34dd60d`, GitHub Actions run https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40002880105 — source-qa completed **success** (static QA, Kotlin/JVM HTTPS/ZIP/session regressions and transfer queue tests); verified-build completed **skipped**. No new APK was generated.
- **PASS — PR #6 gate:** source-qa succeeded and verified-build skipped on SHA `072e0eeb7c77f785cbd86f9d064f04e8d61e5fb8`, run https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40002673396.
- **NOT TESTED:** Android full compilation on new source (intentionally deferred by final-build policy), SAF behavior on Xiaomi Pad 5, Windows ZIP extraction and file hashes, 4+ GiB transfer, FTP/SFTP, hotspot behavior, and Liquid Aurora screenshot acceptance. No merge or release.

## 2026-10-23 — Upload consent revocation and strict Unicode input (source-only)

- **Production:** `TransferHttpEngine.kt` rejects malformed UTF-8 replacement characters and unpaired UTF-16 surrogate names before an upload reaches SAF; correctly paired emoji and ordinary Arabic names remain accepted. The HTTP layer now returns 403 for an upload whose permission is revoked.
- **Android adapter:** `AndroidShareStorage.kt` rechecks browser-upload consent and the selected SAF destination before every streamed block and before final rename. If the user disables browser uploads or changes the destination mid-transfer, the unfinished `.part` document is deleted on the failure path. This does **not** prove instant interruption while a socket read is blocked.
- **Regressions:** `qa/CoreSmokeTest.kt` exercises malformed UTF-8, invalid surrogate halves, valid emoji, HTTP 403 after simulated revocation and the absence of a committed file. These are portable JVM/HTTPS tests, not an Android SAF integration test.
- **PASS:** exact source SHA `74daa547881a61053378e2dca91fc0dbb3c695f2`, GitHub Actions run https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40011453462 — `source-qa` success, `verified-build` skipped. No new APK or package generated.
- **NOT TESTED:** Android compilation of the modified SAF adapter (deferred by explicit final-build rule), real mid-transfer toggle and provider cleanup on Xiaomi Pad 5, device SHA-256/large-file resume, FTP/SFTP server, secure native pairing, and final UI. PR #7 remains draft; no main merge or release.

## 2026-10-24 — Milestone N-FTP-VERIFY: optional full remote SHA-256 readback

**Production path:** FTP and pinned SFTP clients now accept an explicit `verifySha256` upload option. During upload the chosen SAF input stream is hashed without buffering it in memory; after successful upload and size check, the app reads the remote file back, hashes all bytes and compares full SHA-256. FTP uses `retrieveFileStream` followed by `completePendingCommand`; SFTP uses the pinned authenticated session's `get` stream. A mismatch, unavailable readback or incomplete FTP final reply fails the operation rather than displaying a verified success. The Liquid FTP/SFTP UI exposes an opt-in switch (off by default because write-only servers and metered/slow LANs exist), and distinguishes verified readback from size-only success. FTP remains plaintext: SHA-256 is integrity detection, NOT transport authentication.

**Acceptance ledger:**

| Gate | State | Evidence / next check |
| --- | --- | --- |
| N1: portable bounded streaming SHA-256, mismatch and zero-read tests | CODE COMPLETE — CI PENDING | `RemoteDigestVerifier.kt`, `qa/RemoteDigestVerifierTest.kt`, `qa/run_jvm_tests.sh` |
| N2: FTP readback, final FTP reply, explicit opt-in and UI result | CODE COMPLETE — ANDROID COMPILE NOT TESTED | `RemoteFileClient.kt`, `NetKitProScreens.kt`; FTP server readback permission required |
| N3: pinned SFTP readback and digest comparison | CODE COMPLETE — ANDROID COMPILE NOT TESTED | `RemoteFileClient.kt`; remote server must allow read |
| N4: real LAN FTP and SFTP upload SHA equality (0 B, Arabic name, large file, denied readback) | NOT TESTED | Xiaomi Pad 5 ↔ Windows/other trusted server |
| N5: cancellation, protocol error, source mutation, throughput and security review | NOT TESTED | End-to-end device logs and negative tests |

**Current accepted gates:** 0/5 on hardware; 3 code paths implemented but exact-head CI and Android compilation remain unverified. Existing NetKit PR #6 unchanged; PR #7 remains Draft. No APK built in this source milestone. Source reference: https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ftp/FTPClient.html and https://mwiede.github.io/jsch/0.2.7/com.jcraft.jsch/com/jcraft/jsch/ChannelSftp.html . Existing Apache Commons Net and mwiede JSch libraries reused; no new third-party dependency, API charge or license added.

**Important limitation:** remote readback proves that bytes readable immediately after upload match the sent stream. It does not prove durability after server crash, cannot defend against malicious server replay, and does not implement protocol-confirmed resume or an embedded FTP/SFTP server.


## 2026-10-25 — M4 prerequisite: honest SAF unknown-length FTP/SFTP transfers

**Scope:** PR #7 only, layered over PR #6; no change to PR #6/main.
**Functional outcome:** the durable FTP/SFTP transfer queue now distinguishes **unknown size = -1** from **known empty file = 0**. A provider without `OpenableColumns.SIZE` is not misreported as empty. Unknown-size progress can increase, persists across restarts, and retains actual bytes on successful protocol completion. Known-size progress remains bounded; an explicitly empty file cannot accept nonzero progress. UI displays unknown size and does not claim SHA verification when it was not requested.

**Source:** `TransferQueue.kt`, `NetKitProScreens.kt`, `qa/TransferQueueTest.kt`.
**Acceptance gate (6 checks):**
1. Queue sentinel/progress/finish semantics: **PASS (source inspection)**.
2. SAF picker size propagation and honest status text: **PASS (source inspection)**.
3. Persistence/reload and known-empty negative regression added: **PASS (source inspection)**.
4. Kotlin/JVM regression execution on final SHA: **NOT TESTED** until CI finishes: https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40133913426 .
5. Android compilation and physical SAF provider tests: **NOT TESTED**.
6. Verified remote-offset resume, FTP/SFTP server, Pad 5↔Windows acceptance: **FAIL / not implemented**.

**Boundary:** protocol completion is not a cryptographic proof. Unknown-size completion relies on FTP/SFTP success; optional SHA readback remains separate. This is a real upload/download queue correctness fix, **not** verified remote-offset resumption. No APK/EXE build was requested for this source-only gate.


## M43 — Bounded recursive ZIP checksum manifest (2026-10-26)

**Scope:** PR #7 only; branch `automation/netkit-zip-stall-guard-20261023`, based on PR #6's active feature branch. No branch mixing.

- **Source commit:** `3dc5fd7dd75c64179285ef1af4030ed15c4f8852`
- **Regression-test head:** `c636cd98c0f5525c46f0840d5d984bc380ce1f9c`
- **Source QA:** [run 40324439310](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40324439310) — **PASS**.
- **Targeted Android compile:** [run 40324439323](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40324439323) — **PASS**.

### Functional change
- Enforces the ZIP checksum sidecar's 64 MiB maximum incrementally, before appending each UTF-8 line to the in-memory manifest; a huge tree can no longer grow the manifest unchecked and only fail after allocation.
- Verifies the accumulated UTF-8 byte count matches the final manifest bytes.
- Added regressions for an undersized checksum budget and a non-advancing SAF pagination cursor (the latter must fail closed instead of repeating a page).

### Acceptance gates
- Kotlin/JVM transport and recursive SAF ZIP suite: **PASS**
- Static source checks: **PASS**
- Android compile on tested head: **PASS**
- Full APK for real Pad 5 folder/ZIP acceptance: **PENDING** (targeted device-test build requested after this ledger update).
- Physical SAF provider behavior and Windows extraction: **NOT TESTED**.
- FTP/SFTP server roles and protocol-confirmed network resume: **NOT IMPLEMENTED / NOT TESTED**.

No merge, no main changes, no new dependency or paid API.
