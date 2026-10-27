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


### M43 device-test build result — 2026-10-26

- **Build head:** `d45bd36f9b80f0905991118f30f45f6049063b4b`
- **Build/QA:** [run 40325362841](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40325362841) — **SUCCESS**; source QA, Android Debug APK build, lint and APK identity/signature verification passed.
- **Artifact:** ID `12649774965`, [download from GitHub Actions](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40325362841/artifacts/12649774965), expires **2026-11-02**.
- **Artifact ZIP SHA-256:** `b5a5db12acb8150e68f8f8c283f9855620649854ed53c88a8317fde9179e2e64`.
- **APK:** `NetKit_v0.6.0-alpha-debug.apk`, 11,825,708 bytes, package `com.netkit.local`, version `0.6.0-alpha`.
- **APK SHA-256:** `2696a97f10ae58c4cd0a3f56a6326f75cf2fe61dcc9a17cee49cd7ec521ecf9a`.
- **Verification:** artifact ZIP integrity passed; downloaded APK SHA-256 matched `dist/SHA256SUMS.txt`; source ZIP SHA-256 also matched its manifest entry.
- **Physical Xiaomi Pad 5 SAF recursive ZIP/checksum and Windows extraction:** **NOT TESTED**. This is a debug acceptance package, not a release.

### M44 — Reject skipped SAF archive pagination offsets (2026-10-26)

- **Functional source + regression commit:** `94b2dceb8ff99656c41e7a0dcd29f1cc6fa4c5ce`.
- **Compatibility follow-up:** `f8ba0534d32cdd228d63ef9e5560622faeb56c04` preserves the existing non-advancing-offset error while rejecting gaps.
- **Source QA:** [run 40330668748](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40330668748) — **PASS** (static checks, HTTPS/SAF ZIP/session JVM suite, persistent transfer queue tests).
- **Android compile gate:** [run 40330668714](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40330668714) — **PASS**; Kotlin/resources compile succeeded. This run did not produce an APK.
- **Functional change:** DirectoryArchive.write() now requires a non-null nextOffset to equal offset + page.files.size, with overflow protection. It rejects providers that skip items, which could otherwise create a valid-looking but incomplete ZIP. Added a regression proving the archive fails on a skipped offset; retained the existing regression for a non-advancing pagination loop.
- **Targeted APK:** requested in the following commit only because recursive SAF ZIP export needs a real-device acceptance attempt; this is a Debug test package, not a release.
- **Xiaomi Pad 5 recursive SAF ZIP + Windows extraction/hash equality:** **NOT TESTED** until physical-device evidence is supplied.
- **FTP/SFTP server roles and protocol-confirmed network resume:** still **NOT IMPLEMENTED**. PR #7 remains isolated from PR #6 and Draft.

No merge, no main changes, no new dependency, no Wi-Fi Analyzer and no paid API.



## M54 — Reject invisible Unicode spoofing in SAF ZIP names (2026-10-27)

**Scope:** PR #7 only; its base remains `feature/netkit-reliability-20261023`. No rebase or cross-branch cherry-pick was performed.

- **Production change:** `DirectoryArchive.safeSegment` now rejects additional default-ignorable filename controls (soft hyphen, combining grapheme joiner, Mongolian vowel separator, zero-width space, word-joiner/invisible operators) and Unicode Tag characters in supplementary planes. Arabic ZWNJ (U+200C) and emoji ZWJ (U+200D) remain allowed.
- **Regression coverage:** rejects the added invisible/tag cases; round-trips an Arabic filename containing ZWNJ and a joined family-emoji filename.
- **Source commit:** `aee98da82b15dd962456d898733b6c9cfdd6a029`
- **Test commits:** `11f8ca5d14f26cf311caee95374bad67bfa9bf06`, `bfa4aed31381db0c0d50a4037cea0cbbcefda20b` (current source/test head before this documentation-only update).
- **Exact-head CI:** **NOT VERIFIED in this run**. The PR branch workflow is push-enabled, but the available run lookup only returns pull-request-triggered runs; no run ID for the push run was exposed. Do not substitute the earlier green run on `ea6e1a6099a84e67988252f3d0e11e1c39efba8e`.
- **Workflow evidence:** [NetKit development workflow filtered to this branch](https://github.com/amroalhamedi2022-hue/netkit-android/actions/workflows/netkit-import-build.yml?query=branch%3Aautomation%2Fnetkit-zip-stall-guard-20261023).
- **Acceptance:** source diff reviewed **PASS**; exact-head Kotlin/JVM CI **NOT TESTED/NOT VERIFIED**; Android compile/APK **NOT TESTED**; SAF provider and Windows extraction on real devices **NOT TESTED**.
- **Safety:** no merge, no `main` changes, no new dependency or APK. PR #7 remains non-mergeable and independent from PR #6.


## M60 — Strict FTP SIZE response framing (2026-10-27)

**Scope:** NetKit PR #7 only; branch `automation/netkit-zip-stall-guard-20261023`, layered over its current PR #6 base. No cross-branch merge or main update.

- **Production:** `FtpSizePolicy.parseReply` accepts one complete CRLF terminator (FTP) or one LF terminator (legacy/test fixtures); it rejects lone CR, repeated terminators, and embedded/multiple response lines before parsing a byte count.
- **Regression:** `qa/FtpSizePolicyTest.kt` adds malformed framing cases while preserving valid zero-length, maximum signed-64-bit, tab-separated and LF-compatible cases.
- **Source/test commits:** production `9a0243000e0d9673ece9100b7d879fcd2b2817b7`, regression `29ada5b6c9944c1071aa91ed27dee42b3491708e`, source-comment cleanup/current branch head `f8e9f759bed59e8902ce0cc1d4a50c569650981f`.
- **CI on exact head:** **NOT VERIFIED** in this run. The repository workflow is configured for pushes to this branch, but the available connector only returns pull-request-triggered workflow runs and exposed no run ID for the push. Do not substitute an earlier green run.
- **Focused local verification:** Kotlin/JVM source/test reproduction compiled with `kotlinc` and executed **PASS**; all valid and malformed framing assertions passed. This is a focused run, not a checkout-wide GitHub CI result. Android compile/APK **NOT TESTED**; real FTP server/client interoperability **NOT TESTED**.
- **Safety:** no new dependency, APK, secret, paid API or main change. FTP remains plaintext unless separately secured; strict SIZE framing is not authentication and size equality is not a cryptographic integrity proof.

## M62 — Browser upload clock health and DNS-SD ledger concurrency (2026-10-27)

**Scope:** NetKit PR #7 only, branch `automation/netkit-zip-stall-guard-20261023`. PR #6 remains independent; no rebase, merge or main change.

- **Production change:** `ResumableBrowserUpload` now observes its injected monotonic clock under a dedicated lock. A backwards reading or runtime clock failure latches the upload manager fail-closed, removes partial staging on the next cleanup, retires affected upload IDs, and rejects new uploads for the rest of that share lifetime. A clock failure after a successful SAF publication does not turn that commit into a failed response; subsequent cleanup retires the replay ID to prevent duplicate publication.
- **Regression:** `qa/ResumableBrowserUploadTest.kt` checks that rollback removes partial bytes, the old upload ID cannot be replayed after clock recovery, and a fresh ID is not admitted by the broken manager. `qa/NsdDiscoveryPolicyTest.kt` adds concurrent begin/finish/invalidate/clear stress coverage for asynchronous Android NSD callbacks.
- **Source/test head:** `629d3640d6abe104b5ae37d379ca937181b1591f`.
- **Exact-head CI:** [run 40462629817](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40462629817) — **SUCCESS**. Kotlin/JVM transport and SAF ZIP suite, NSD policy/ledger tests, multicast-lock lease tests, and persistent transfer-queue state-machine tests passed. No Android APK was built for this source-level reliability gate.
- **Earlier failed attempt:** [run 40461684933](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40461684933) — **FAIL** because an edit temporarily duplicated the `@Synchronized` annotation. The duplicate was removed before the passing exact-head run; the final branch contains a single annotation.
- **Android compile/APK:** **NOT TESTED** on M62. **Real Pad 5 SAF/HTTPS transfer and Windows browser acceptance:** **NOT TESTED**. Clock rollback is an injected-clock defensive gate; the production clock uses `System.nanoTime()`.
- **Safety:** no new dependency, paid API, secret, APK, merge or main change. No Wi-Fi Analyzer or Remote Control functionality added to NetKit.

## 2026-10-27 — M63: fail closed when retired upload staging cannot be deleted

- **Scope:** NetKit PR #7 only, branch `automation/netkit-zip-stall-guard-20261023`. PR #6's branch and `main` were not changed.
- **Production:** `ResumableBrowserUpload` now checks deletion of invalid/expired private `.part` staging before removing the corresponding session from its registry. The ID is retired and the session is marked invalid first. If deletion fails or throws while the file remains, cleanup returns an `IOException` and keeps the session tracked so a later cleanup can retry; the service no longer silently forgets bytes that are still on disk. Cancellation uses the same checked deletion path. The production default remains `File.delete()`; the injectable deleter exists for deterministic regression coverage.
- **Regression:** `qa/ResumableBrowserUploadTest.kt` injects a deletion failure for an expired partial upload. The first status/cleanup must fail while the file remains; a subsequent successful deletion must remove it; the retired ID must remain unable to start a new upload.
- **Source commit:** [47c3e67fb6720188146d0fa337c56a163d7914ff](https://github.com/amroalhamedi2022-hue/netkit-android/commit/47c3e67fb6720188146d0fa337c56a163d7914ff).
- **Regression commit / source-test head:** [d62cfed1f3c28aa6c0a97e3a9dfbf7c4321e2f58](https://github.com/amroalhamedi2022-hue/netkit-android/commit/d62cfed1f3c28aa6c0a97e3a9dfbf7c4321e2f58).
- **Exact-head CI:** **NOT VERIFIED** in this run. The configured development workflow is [NetKit Android - development build and verification](https://github.com/amroalhamedi2022-hue/netkit-android/actions/workflows/netkit-import-build.yml); the connected workflow lookup returned no pull-request-triggered run for this SHA, and a passing run ID could not be confirmed. Do not infer PASS from the prior M62 run.
- **Android compile/APK:** **NOT TESTED** on M63. **Pad 5 SAF/HTTPS, Windows browser, FTP/SFTP, hotspot and UI acceptance:** **NOT TESTED**.
- **Safety:** no new dependency, paid API, secret, artifact, auto-merge or main change. No Wi-Fi Analyzer or Remote Control scope added.

## 2026-10-27 — M66: retryable fail-closed cleanup on share shutdown

- **Scope:** PR #7 only, branch `automation/netkit-zip-stall-guard-20261023`; PR #6 and `main` were not changed.
- **Production fix:** `ResumableBrowserUpload.close()` no longer ignores a failed `.part` deletion and then clears the session/tombstone registries. It now uses the checked deletion path for each private staging file, verifies removal of the private cache directory, and only clears the registries after cleanup succeeds. If the filesystem refuses deletion, shutdown fails closed and preserves enough closed-manager state for a later `close()` retry.
- **Regression:** an injected deleter refuses the first shutdown cleanup. The test confirms the partial bytes remain visible and the call fails; after enabling deletion, a second `close()` removes the staging and cache directory successfully.
- **Commits:** production `6dc5fed4d892f8aaf60cc4827a4d4c3ddf852fc5`; regression and constructor-compatibility fix `61e510c6f81c1cbcb814941bd995e918f431448d`, `d1d2e53f9dc8ccfdb1948f4b79ee0eabbf6fc7d1`.
- **Exact-head CI:** [run 40481353664](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40481353664) — **SUCCESS** on `d1d2e53f9dc8ccfdb1948f4b79ee0eabbf6fc7d1`. Static source checks, full Kotlin/JVM HTTPS/SAF ZIP/session suite, DNS-SD/multicast policy tests, and persistent transfer-queue state-machine tests passed. The new `ResumableBrowserUploadTest` output explicitly includes **shutdown cleanup retry**.
- **Earlier failure retained for audit:** [run 40480814208](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40480814208) — **FAIL** because the newly injected deletion parameter changed Kotlin trailing-lambda binding for an older clock test. The test now names `clockMillis` explicitly; exact-head run 40481353664 passes.
- **Build/device gate:** Android compile/APK **NOT TESTED / NOT BUILT** on M66. Xiaomi Pad 5 SAF/HTTPS, Windows browser, FTP/SFTP, hotspot and Liquid UI acceptance **NOT TESTED**. This is source-level reliability acceptance only.
- **Safety:** no new dependency, paid API, secret, APK, merge or `main` write. NetKit remains local-first; no Wi-Fi Analyzer and no Remote Control scope added.

## Integration gate observation — 2026-10-27

- PR #7 remains **DRAFT / mergeable=false**. Its PR metadata reports base SHA `6a971514906d6194709e4b3de8fa10bdc55d0dc6`, while the live `feature/netkit-reliability-20261023` branch ref currently resolves to `e209308ca41efb2ca2a7c2041c833e7d0e66eef4`.
- This is recorded as a branch-integration discrepancy, not treated as a source-test failure. No rebase, base change, cherry-pick, merge, force-push, or `main` update was performed. Resolve the PR base/mergeability separately after reviewing the two independent histories.

 
## 2026-10-27 — M67: Bound the complete ZIP-relative path for portable Windows extraction

- **Scope:** NetKit PR #7 only, branch `automation/netkit-zip-stall-guard-20261023`; PR #6 and `main` were not changed.
- **Problem:** individual archive names were bounded, but the complete nested ZIP-relative path could still reach 2,048 UTF-16 code units. Such a ZIP can be valid while failing in common Windows extractors that do not have long-path support enabled.
- **Production fix:** `DirectoryArchive` now limits each segment to 200 UTF-16 code units and the complete relative path, including directory separators, to 200 code units. This is a conservative portability budget that leaves room for the destination directory; it does not claim to guarantee every arbitrary extraction-root path. Existing SAF root scoping, Unicode/bidi checks, Windows device-name checks, NFC/case collision rejection, depth/entry/byte quotas and cancellation behavior remain intact.
- **Regression:** `qa/DirectoryArchiveTest.kt` verifies five nested 39-character directory names (exactly 200 code units including separators) remain accepted and a sixth level (240 code units) is rejected before the unsafe ZIP entry is emitted.
- **Production commit:** [effe749553ea0e253ede3ced391b7492bc702a77](https://github.com/amroalhamedi2022-hue/netkit-android/commit/effe749553ea0e253ede3ced391b7492bc702a77).
- **Regression/test head:** [1c5cd398fe2fac26453dd0ebc2c03aaacfbee58f](https://github.com/amroalhamedi2022-hue/netkit-android/commit/1c5cd398fe2fac26453dd0ebc2c03aaacfbee58f).
- **Exact-head CI:** [run 40489023622](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40489023622) — **SUCCESS**. Static checks, Kotlin/JVM HTTPS/SAF ZIP/session tests, recursive ZIP quota/path/cancellation regressions, DNS-SD/multicast tests and persistent transfer-queue tests passed. The log explicitly reports the ZIP traversal/path/quota suite passing and the complete NetKit JVM reliability QA passing.
- **Android assemble/lint/APK:** **NOT TESTED / NOT BUILT** in M67; this was a Kotlin/JVM archive-policy change and no device binary was required.
- **Xiaomi Pad 5↔Windows extraction/large-folder acceptance, SAF provider behavior, FTP/SFTP interoperability, hotspot capability behavior and Liquid UI:** **NOT TESTED**.
- **Integration gate:** PR #7 remains **DRAFT / mergeable=false**. Its PR metadata base SHA is still `6a971514906d6194709e4b3de8fa10bdc55d0dc6`, while the live PR #6 feature branch head is `e209308ca41efb2ca2a7c2041c833e7d0e66eef4`. No base change, rebase, cherry-pick, merge, force-push or `main` write was performed.
- **Safety:** no dependency added, no paid API, no secret or binary artifact; NetKit remains separate from Remote Control and includes no Wi-Fi Analyzer.
- **Live Google Docs Handoff:** **SYNC FAILED** until an authorized Google Docs write/read connector is available; this repository ledger does not replace the live Drive document.

## 2026-10-27 — M68 — Win32 Unicode case-alias collisions in SAF ZIP

- **Production changed:** `DirectoryArchive.canonicalPath` now applies NFC normalization, invariant uppercase using `Locale.ROOT`, then NFC again. Lowercase-only folding can miss Windows case-insensitive aliases such as `I.txt` versus `ı.txt` and Greek sigma variants. The archive rejects those sibling aliases instead of emitting a ZIP that may overwrite data on extraction.
- **Regression changed:** `qa/DirectoryArchiveTest.kt` adds explicit Turkish dotless-I and Greek sigma collision pairs to the existing rejection matrix.
- **Production commit:** [2ab3a515e62b4a06594dccf38961955f7899fec9](https://github.com/amroalhamedi2022-hue/netkit-android/commit/2ab3a515e62b4a06594dccf38961955f7899fec9).
- **Regression/test head:** [67fb31fc3adafea989ff29b8389f377ed7fe23e2](https://github.com/amroalhamedi2022-hue/netkit-android/commit/67fb31fc3adafea989ff29b8389f377ed7fe23e2).
- **Exact-head CI:** **NOT VERIFIED**. The GitHub connector exposed no status checks or pull-request-triggered run for this push head; no run ID/logs could be verified. Do not treat earlier M67 CI as proof of M68.
- **Android assemble/lint/APK:** **NOT TESTED / NOT BUILT**; this is a Kotlin/JVM archive-policy regression and a device artifact is not needed to check the code path.
- **Windows extraction and SAF provider acceptance:** **NOT TESTED** on Windows or Xiaomi Pad 5.
- **Integration gate:** PR #7 remains **DRAFT / mergeable=false**; its base is the NetKit reliability branch. No base change, rebase, cherry-pick, merge, force-push or `main` write was performed.
- **Safety:** no dependency added, no paid API, secret or binary artifact. NetKit remains separate from Remote Control and contains no Wi-Fi Analyzer.
- **Live Google Docs Handoff:** **SYNC FAILED** because no Google Docs write/read connector is available in this session; this ledger is not a substitute for the live Drive document.


## 2026-10-27 — M69: Canonicalize the reserved ZIP checksum sidecar consistently

- **Scope:** NetKit PR #7 only; branch `automation/netkit-zip-stall-guard-20261023`. PR #6 source and `main` untouched.
- **Finding:** M68 changed archive-path collision keys to NFC + invariant uppercase, but the pre-reserved `NetKit-SHA256SUMS.txt` key was still stored using lowercase. A mixed-case user file such as `netkit-sha256sums.TXT` could therefore evade the reserved-sidecar collision guard.
- **Production fix:** reserve the checksum sidecar using the same NFC + `Locale.ROOT` uppercase canonicalization as normal archive paths. The existing regression explicitly supplies `netkit-sha256sums.TXT` and expects rejection when checksum-manifest output is enabled.
- **Source/test head:** `f6fdf5463903da41078a5bd0b0f9e1380c60be1b`.
- **Exact-head CI:** [run 40505012196](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40505012196) — **SUCCESS**. Static checks, Kotlin/JVM HTTPS/session/SAF ZIP suite and persistent transfer-queue tests passed. Log confirms `PASS: paged ZIP, nested folders, path/quotas, incremental checksum budget, invalid pagination, transient zero-read recovery, Windows-safe names/collisions and cancellation`.
- **Android compile gate:** [run 40505012142](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40505012142) — **SUCCESS** for `:app:compileDebugKotlin` and `:app:processDebugResources`. This run did **not** assemble or upload an APK; it is compile evidence only.
- **APK / device acceptance:** **NOT TESTED / NOT BUILT**. Real Windows extraction, SAF provider behavior on Xiaomi Pad 5, FTP/SFTP interoperability, hotspot capability and Liquid UI acceptance remain open.
- **Integration:** PR #7 remains **DRAFT / mergeable=false** with base `feature/netkit-reliability-20261023`; its base SHA mismatch against PR #6's current head remains unresolved. No base retarget, rebase, cherry-pick, merge, force-push or `main` write.
- **Safety:** no dependency, paid API, secret or artifact added; NetKit remains LAN/file-transfer only, with no Wi-Fi Analyzer.
- **Live Google Docs Handoff:** **SYNC FAILED**; no authorized Google Docs write/read connector was available. This ledger does not replace the live Drive document.

## 2026-10-27 — M70: Canonical Unicode-safe SAF upload name reservations

- **Scope:** NetKit PR #7 only; branch `automation/netkit-zip-stall-guard-20261023`. PR #6 source and `main` untouched.
- **Finding:** `UploadNameReservations.key` used lowercase-only folding and no Unicode normalization. Two SAF names with canonically equivalent spellings (composed/decomposed accents) or case aliases such as ASCII I/dotless i and Greek sigma could be reserved separately even when a provider/filesystem treats them as the same destination. This undermines the no-overwrite name reservation during concurrent browser uploads.
- **Production change:** canonicalize the filename key as Unicode NFC → `uppercase(Locale.ROOT)` → NFC, matching the conservative ZIP collision policy. SAF tree scope remains part of the key, so identical names in distinct selected trees stay independent.
- **Regression coverage:** `qa/UploadNameReservationsTest.kt` now checks composed/decomposed `café.txt`, ASCII I/dotless-i, Greek sigma aliases, and independent reservations for `tree-A` vs `tree-B`. The test is included in the default `qa/run_jvm_tests.sh` source gate.
- **Source commit:** [2449734180789658ffc03c999b9c239032c20616](https://github.com/amroalhamedi2022-hue/netkit-android/commit/2449734180789658ffc03c999b9c239032c20616).
- **Regression/test head:** [c66b3f55f91a6579d3b0295674d15a4c55cd98eb](https://github.com/amroalhamedi2022-hue/netkit-android/commit/c66b3f55f91a6579d3b0295674d15a4c55cd98eb).
- **Exact-head CI:** **NOT VERIFIED** in this run. The GitHub connector returned no combined status entries and no pull-request-triggered run record for the new test head; its run lookup does not expose push-triggered runs. Do not infer a pass from M69's earlier CI.
- **Android compile/APK:** **NOT TESTED / NOT BUILT**. This is a JVM source regression; no APK was required.
- **Device acceptance:** **NOT TESTED** — simultaneous browser uploads into real SAF providers, Windows extraction, Pad 5 UX, FTP/SFTP, hotspot and Liquid UI remain open.
- **Integration:** PR #7 remains **DRAFT / mergeable=false**, based on `feature/netkit-reliability-20261023`; the base SHA discrepancy with PR #6 remains unresolved. No rebase, base change, cherry-pick, merge, force-push or `main` write.
- **Safety:** no new dependency, paid API, secret or binary artifact. NetKit remains local-first and contains no Wi-Fi Analyzer.
- **Live Google Docs Handoff:** **SYNC FAILED**; no authorized Google Docs write/read connector was available. This ledger does not replace the live Drive document.

## 2026-10-27 — M70 verification update: upload name aliases

- **Source QA run:** [40516697448](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40516697448), exact head `c66b3f55f91a6579d3b0295674d15a4c55cd98eb`.
- **Observed job-step evidence:** static source checks, Kotlin/JVM transport + SAF ZIP/session tests, and persistent transfer queue tests all completed successfully. The log explicitly reports `PASS: concurrent upload name reservations, Unicode NFC/case aliases, scoped trees, collision, rollback` and all subsequent queue assertions passed.
- **Gate:** targeted source tests **PASS** from job logs. The run/job summary endpoint still reports `source-qa` as `in_progress` despite all returned steps, including cleanup, being marked successful; therefore the overall workflow conclusion is **NOT FINALIZED / NOT VERIFIED** until GitHub closes the job. Package jobs were correctly skipped; no APK was built.
- **Device gate:** **NOT TESTED** — real SAF-provider behavior and Windows filesystem interoperability still require hardware/host acceptance.
 
## 2026-10-27 — PR #6 / PR #7 integration checkpoint

- **PR #6 current head:** `08d97ad2d10eaf5600d08e801f0e894c3039d906`, base `main` at `3c3324f686e69295c76a50725340a392c2047f1f`; PR remains Draft.
- **PR #6 source gate:** [run 40514768663](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40514768663), `source-qa` **PASS**; `verified-build` and `device-test-build` **SKIPPED**. Run-level API still reports in-progress, so the overall workflow conclusion is not asserted here.
- **PR #7 current source/test head:** `c66b3f55f91a6579d3b0295674d15a4c55cd98eb`; [run 40516697448](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40516697448) logs show static checks, JVM transport/SAF ZIP/session suite and transfer-queue tests all completed successfully. The run/job summary remains stale at `source-qa: in_progress`; overall run conclusion is **NOT FINALIZED**.
- **Integration blocker:** PR #7 base SHA is still `6a971514906d6194709e4b3de8fa10bdc55d0dc6`, which does not match PR #6's current head `08d97ad2d10eaf5600d08e801f0e894c3039d906`. Do not retarget, rebase, cherry-pick or merge automatically. Review commit ancestry and the intended dependency boundary before resolving this separately.
- **Package/device:** no APK built by these source-only runs; real Xiaomi Pad 5 and Windows acceptance remains **NOT TESTED**.
- No `main` write, merge, force-push, new dependency or secret.

## 2026-10-27 — M70 CI closure

- **Final exact-head run:** [40516697448](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40516697448) is now **completed / success** for source/test head `c66b3f55f91a6579d3b0295674d15a4c55cd98eb`.
- Check-run API confirms `source-qa: success`; `verified-build` and `device-test-build` were **skipped** as intended. This closes the earlier stale/in-progress summary note above.
- **Acceptance:** Unicode NFC/case-alias name reservation JVM regression **PASS**. Android APK and device acceptance remain **NOT TESTED / NOT BUILT**.
- PR #7 remains Draft / mergeable=false; PR #6 remains a separate branch. No integration action or `main` write.

## 2026-10-27 — M71: Upload-limit consent revocation

- **Production change:** `AndroidShareStorage.setMaxBrowserUploadMiB` now advances the existing `UploadConsentGate` generation when the configured browser-upload limit actually changes. Existing staged upload IDs therefore cannot resume under a newly selected size ceiling; setting the same limit is a no-op and does not interrupt active uploads unnecessarily.
- **Source commit:** `277dd4715df53c8486473824bf9c057a0663c578`.
- **Regression coverage:** `qa/UploadConsentGateTest.kt` verifies that changing the upload limit invalidates a prior ticket and allows a fresh ticket under the new value. `qa/check_static.py` guards the production wiring and unchanged-limit no-op.
- **Exact-head source CI:** [run 40524000818](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40524000818), head `164820d00e4a9c2b9fb8d6ccfb0f35e85f869bd2` — **SUCCESS**. Static source checks, Kotlin/JVM transport/HTTPS/SAF ZIP/session tests, upload-consent regression and persistent transfer-queue state-machine tests passed.
- **Exact-head Android compile:** [run 40524000955](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40524000955) — **SUCCESS** for `:app:compileDebugKotlin` and `:app:processDebugResources`. This was a targeted compile check; no APK was built.
- **Gate:** source/JVM **PASS**; Android Kotlin/resource compile **PASS**; APK/package **NOT BUILT**; Xiaomi Pad 5, Windows browser/SAF and FTP/SFTP interoperability **NOT TESTED**.
- **Branch safety:** PR #7 remains Draft and `mergeable=false` because its base SHA is still different from PR #6's head. PR #6 source/head unchanged. No retarget, rebase, cherry-pick, merge, force-push or `main` write.
- **First guard attempt:** run [40522860231](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40522860231) failed because the newly added static-regex pattern was over-escaped. The pattern was corrected before the exact-head successful run above; this earlier failure is not being hidden.
- **Live Google Docs Handoff:** SYNC FAILED — no authorized Docs write/read connector was available in this run. This repository ledger is not a replacement for the live Drive document.

## 2026-10-27 — M74a: Bound declared browser-upload length at the SAF boundary

- **Production source:** added pure `UploadLengthPolicy.check(length, maximum)` and call it at the start of `AndroidShareStorage.saveUpload`, before SAF destination resolution, directory creation, name reservation or temporary-file creation. Negative declared lengths and values above the currently configured upload ceiling fail closed; zero-byte files and the exact ceiling remain valid.
- **Regression coverage:** `qa/UploadPathPolicyTest.kt` checks zero, exact ceiling, negative, over-ceiling, `Long.MAX_VALUE`, and invalid zero ceiling. `qa/check_static.py` asserts the storage-boundary guard exists before consent/destination work.
- **Implementation commits:** `065f797bef5c1d3a7081246093d4ce63cee5a6cc` (pure policy), `2999d64efef6b9fa78d4acf87838b4b97083f101` (SAF enforcement), `d7f26c0eb973af300acf5bf7cade43ab6f5ce7d4` (regression tests), `10d4398922c625b8dea4dcfa9fff23adb7e72aee` (static guard).
- **PASS — targeted Android compile:** [run 40532108071](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40532108071), exact source SHA `10d4398922c625b8dea4dcfa9fff23adb7e72aee`; Kotlin/resources compile and the ZIP archive lease-boundary JVM regression passed. No APK was built.
- **PASS — static source check:** the `Static source checks` step passed on [source run 40532108042](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40532108042), exact source SHA `10d4398922c625b8dea4dcfa9fff23adb7e72aee`.
- **PASS — full exact-head source QA:** [run 40532108042](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40532108042), source SHA `10d4398922c625b8dea4dcfa9fff23adb7e72aee` — **SUCCESS**. Static source checks, the full Kotlin/JVM transport/HTTPS/SAF ZIP/session suite (including `UploadPathPolicyTest.kt` with the new length-boundary cases), and the persistent transfer-queue state machine tests all passed. Intermediate run 40532055434 was cancelled when the branch advanced and is not counted as a pass.
- **NOT TESTED:** APK/package creation, Xiaomi Pad 5 SAF/browser upload, Windows browser interoperability and physical device acceptance. No artifact SHA-256 was generated.
- **Branch safety:** PR #6 remains on `08d97ad2d10eaf5600d08e801f0e894c3039d906`; PR #7 remains an independent Draft branch based on `feature/netkit-reliability-20261023`, with the existing base/head mismatch unresolved. No rebase, retarget, merge, force-push or `main` write.
- **Live Google Docs Handoff:** **SYNC FAILED** — no authorized Docs write/read connector is available in this run.

## 2026-10-27 — M75: SAF-tree-scoped upload name reservations

- **Problem:** upload reservations used only the relative folder path as their in-flight namespace. A slow upload in one selected SAF tree could force a duplicate suffix for the same filename in a different tree, even though the destinations are independent.
- **Fix:** added `uploadReservationScope(treeUri, folders)`, an unambiguous length-prefixed in-memory scope, and used it consistently for reserve, commit, and release. User-visible return paths remain relative paths; no tree URI is exposed in returned names.
- **Regression:** `UploadNameReservationsTest.kt` verifies stable scope generation, independence across two SAF tree URIs, and duplicate suffixing within the same tree/path. `qa/check_static.py` guards the production wiring.
- **Source commits:** [1831392](https://github.com/amroalhamedi2022-hue/netkit-android/commit/1831392264e41b3860d2f96feb22dc1b004d38c4), [cf07b95](https://github.com/amroalhamedi2022-hue/netkit-android/commit/cf07b950839bc8257ce56a63b00c1f7603fcfc79), [a23d4e3](https://github.com/amroalhamedi2022-hue/netkit-android/commit/a23d4e34f3a3b0f7b36cb3f7cadf01a75f1627f2), [bb16f72](https://github.com/amroalhamedi2022-hue/netkit-android/commit/bb16f72548ac5253136646df55f88b6aef5e75e8).
- **PASS — exact-head source QA:** [run 40542043488](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40542043488), tested source SHA `bb16f72548ac5253136646df55f88b6aef5e75e8`. Static source checks, Kotlin/JVM transport/HTTPS/SAF ZIP/session tests, and persistent transfer queue tests passed.
- **PASS — Android Kotlin/resources compile:** [run 40542042484](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40542042484), same source SHA; no APK in this compile-only job.
- **PASS — targeted device-test APK build:** the `device-test-build` job built, linted, signature-checked and packaged `NetKit_v0.6.0-alpha-debug.apk`. Package ID `com.netkit.local`, version `0.6.0-alpha`, min SDK 26, target SDK 35; v2 APK signature verified. APK SHA-256: `107f0e4c28a2e30c24c845311d480dadcfb30c7cec16f6e32c7a140b86dd7388`. Artifact ZIP SHA-256: `d54c8580a25d07d4a904f231ac40351f1b69c751030659c3784b20da0051fe23`.
- **Artifact:** [NetKit device-test artifact 12751890822](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40542043488/artifacts/12751890822), expires 2026-11-03. This is a targeted test package, not a release.
- **NOT TESTED:** installation and SAF/browser upload behavior on Xiaomi Pad 5; concurrent slow uploads across two different SAF trees; Windows interoperability and physical-device acceptance.
- **Branch safety:** PR #7 remains Draft on `automation/netkit-zip-stall-guard-20261023`; PR #6 source unchanged. Existing PR #7 base/head mismatch remains unresolved. No rebase, retarget, merge, force-push, `main` change, new dependency or Wi-Fi Analyzer.
- **Live Google Docs Handoff:** **SYNC FAILED** — no authorized Google Docs write/read connector was available in this run; this GitHub ledger is not a substitute for the live Drive document.

## 2026-10-27 — M76: Hold committed upload aliases until reservation release

- **Scope:** NetKit PR #7 only; branch `automation/netkit-zip-stall-guard-20261023`. PR #6 and `main` remain untouched.
- **Finding:** when an external writer claims the reserved name during streaming, commit selects an alternate name. A SAF provider may report rename success before `findFile()` reflects the new destination; a concurrent upload could otherwise reserve that final alias in the visibility gap.
- **Fix:** `UploadNameReservations` now links all keys held by an upload reservation. Commit reserves the alternate final key before rename and retains it alongside the original key until `release(originalName, scope)`; failed rename removes the provisional alias. No storage URI is added to user-visible paths.
- **Regression:** `qa/UploadNameReservationsTest.kt` simulates a provider whose rename succeeds while its existence listing remains stale. The next upload must skip the committed alias; releasing the original reservation releases both keys.
- **Source + regression commit:** [c608c66133b5d9bed10ae27099efce3cd4856a3e](https://github.com/amroalhamedi2022-hue/netkit-android/commit/c608c66133b5d9bed10ae27099efce3cd4856a3e).
- **PASS — exact source/test-head CI:** [run 40551003782](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40551003782), tested source/test SHA `c608c66133b5d9bed10ae27099efce3cd4856a3e` — **completed / success**. Static source checks, Kotlin/JVM HTTPS/SAF/ZIP/session and upload-reservation regressions, plus persistent transfer-queue state-machine tests all passed. The new stale-provider-listing regression ran inside the Kotlin/JVM suite. This run was source QA only; it did not build an APK.
- **APK / device acceptance:** **NOT BUILT / NOT TESTED**. This is a source-level concurrency regression; no device binary is needed until a targeted SAF acceptance run is scheduled.
- **Safety:** no dependency change, no paid API, no Wi-Fi Analyzer, no secret, no merge, no rebase/retarget, and no `main` write. PR #7 remains Draft with its existing base/head mismatch.
- **Live Google Docs Handoff:** **SYNC FAILED** — no authorized Google Docs write/read connector was available; this ledger is not a substitute for the live Drive document.

## 2026-10-27 — M77 bounded and cancellable SAF stream reads

- **Production fix:** `app/src/main/java/com/netkit/local/share/TransferHttpEngine.kt` replaces repeated `Thread.yield()` on zero-byte SAF reads with bounded exponential backoff (capped at 16 ms), while retaining the 32-empty-read failure bound. It now checks an already-interrupted worker and converts interruption during backoff to `InterruptedIOException`, preserving the interrupt flag.
- **Regression evidence:** `qa/CoreSmokeTest.kt` retains transient-zero byte integrity and permanent-stall retry-budget checks and adds an interrupted-worker test that requires prompt exit and preserved cancellation status.
- **Source SHA:** `776e705fea6fd230203e208f467425cf63598a8f`.
- **CI:** [run 40564418402](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40564418402) — **PASS**. Static source checks passed; Kotlin/JVM HTTPS/SAF transport, resumable-upload, ZIP/path/quota, session, FTP/SFTP integrity/resume and DNS-SD lifecycle tests passed; persistent transfer queue state-machine tests passed. The warning about an always-true null check in `ResumableBrowserUpload.kt` is pre-existing and non-fatal.
- **Build decision:** Android package jobs were skipped intentionally. This is a stream-cancellation/performance regression with a JVM-level acceptance test; no APK/EXE is justified in this batch.
- **Device acceptance:** **NOT TESTED** on Xiaomi Pad 5 or Windows browser. Real SAF provider timing, nested ZIP and Android↔Windows transfer remain open.
- **Scope/security:** no dependency or license change, no Wi-Fi Analyzer or Remote Control feature, no credentials/secrets, no paid API. PR #7 remains Draft and unmerged; its feature-base mismatch remains a separate integration gate.

## 2026-10-27 — M78: Keep browser-upload names compatible with Windows ZIP extraction

- **Scope:** NetKit PR #7 only, branch `automation/netkit-zip-stall-guard-20261023`. PR #6 and `main` untouched.
- **Finding:** `DirectoryArchive.safeSegment` already rejects Windows console device aliases `CONIN$` and `CONOUT$`, but `UploadPathPolicy.validName` accepted them. A browser upload could therefore create a SAF file that later makes a recursive portable ZIP export fail.
- **Fix:** browser upload validation now rejects `CONIN$` / `CONOUT$` case-insensitively, including stems with spaces before the extension, and trims both trailing spaces and dots when evaluating the device stem.
- **Regression:** `qa/UploadPathPolicyTest.kt` covers `CONIN$.txt`, `CONOUT$.txt`, lowercase aliases, `CONIN$ .txt`, `CONOUT$ .txt`, `CON .txt` and `COM1 .log`, including destination-folder validation.
- **Source commit:** [ecab0c4c1adcbe6f2d6a56cbb322e57d4254f891](https://github.com/amroalhamedi2022-hue/netkit-android/commit/ecab0c4c1adcbe6f2d6a56cbb322e57d4254f891).
- **Test commit / current source-test head before this ledger update:** [348af306bbc51a45f4497218859b0ca4bb856ea3](https://github.com/amroalhamedi2022-hue/netkit-android/commit/348af306bbc51a45f4497218859b0ca4bb856ea3).
- **PASS — isolated local policy smoke:** a small Kotlin/JVM snippet mirroring the production stem rule rejected all 7 reserved-name cases and retained 4 ordinary names. This validates the rule logic only, not compilation of the repository source or the full test suite.
- **CI:** **NOT VERIFIED** on the new head. The connected status endpoint returned no status contexts, and the available workflow-run helper only lists PR-triggered runs; do not infer success from prior M77 CI.
- **APK/build:** **NOT BUILT**; the change is a portable-name validation regression covered by JVM source tests. No new artifact or SHA-256 exists.
- **Device acceptance:** **NOT TESTED** on Xiaomi Pad 5; SAF provider and browser-to-device transfer remain open.
- **Safety:** no new dependencies, paid APIs, secrets, Wi-Fi Analyzer, merge, rebase/retarget, force-push or `main` write. PR #7 remains Draft with the existing base/head mismatch.
- **Live Google Docs Handoff:** **SYNC FAILED** — no authorized Docs write/read connector is available in this run; this ledger is not a substitute for the live Drive document.


## M79 — upload/archive invisible-control policy parity (2026-10-27)
- **Production source:** `app/src/main/java/com/netkit/local/share/UploadPathPolicy.kt`, commit `f929f1ce375e54dc4f9289413c6053afbf39b411`. Browser upload validation now rejects the same LRM/RLM controls (U+200E/U+200F) and the complete U+2060–U+206F formatting-control range as the recursive ZIP policy, while continuing to allow Arabic ZWNJ and emoji ZWJ.
- **Regression:** `qa/UploadPathPolicyTest.kt`, commit `5cb59240bfad48e518cb65b9e48c9925d5591bb0`, adds LRM/RLM and U+2061/U+2065 cases to the existing unsafe-name set; existing Arabic/emoji acceptance, Windows reserved-device aliases, relative-path and scoped-reservation cases remain.
- **Exact-head CI:** [run 40578510063](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40578510063) — **SUCCESS** on `5cb59240bfad48e518cb65b9e48c9925d5591bb0`. Static source checks PASS; full Kotlin/JVM transport/HTTPS/SAF/ZIP/session suite PASS; persistent transfer queue state-machine suite PASS. Logs explicitly report safe relative folder paths, Arabic names, depth quotas and scoped reservations PASS.
- **Not proven:** Android Gradle compile/APK, Xiaomi Pad 5 SAF provider behavior, QR scan, real Android↔Windows transfer, FTP/SFTP interoperability or hotspot behavior. No package was built because this is a policy-level JVM regression.
- **Safety:** no dependency/paid API/secrets; no Wi-Fi Analyzer; no main write or merge. PR #7 remains Draft and its base/head mismatch is unresolved; PR #6 was not modified.


## M81 — 2026-10-28 — interruptible stalled-SAF ZIP regression
- Added a regression in `qa/DirectoryArchiveTest.kt` on branch `automation/netkit-zip-stall-guard-20261023`, commit `80746e67bd0b076f1124df5cad735d828ac4f440`.
- The test waits until a deliberately non-conforming SAF-like stream enters the bounded zero-read sleep, interrupts the worker, and asserts prompt termination, `InterruptedIOException`, restored interrupt status, and stream closure.
- CI: **PASS** on exact source/test SHA `80746e67bd0b076f1124df5cad735d828ac4f440`: [M15 targeted Android compile run 40595030492](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40595030492) and [development source-QA run 40595030473](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40595030473) both succeeded. M15 passed the ZIP archive lease-boundary JVM regression plus `:app:compileDebugKotlin` and `:app:processDebugResources`; source-QA passed static checks, Kotlin/JVM transport/SAF/ZIP/session tests, and persistent transfer-queue tests. The verified-build and device-test-build jobs were **SKIPPED**.
- APK/build: **NOT BUILT**; this milestone is a focused JVM cancellation regression. Android compile result remains **NOT VERIFIED**.
- Device acceptance: **NOT TESTED** on Xiaomi Pad 5 or a real SAF provider.
- Remaining gates: confirm both workflow results on this exact commit, then run a large-folder ZIP/cancel trial with a real SAF provider and Windows browser.

- Live Google Docs handoff: **SYNC FAILED** — no authorized Google Docs/Drive connector is available in this run; this GitHub ledger is not a substitute for the live Drive document.
