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
