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
