# LIVE HANDOFF — NetKit Android
**Updated:** 2026-10-28  
**Purpose:** Continue engineering from the current implementation; do not restart the project.

## Repository and active review lines
- Repository: https://github.com/amroalhamedi2022-hue/netkit-android
- PR #6: https://github.com/amroalhamedi2022-hue/netkit-android/pull/6 — independent development line; do not alter as part of PR #7 work.
- PR #7: https://github.com/amroalhamedi2022-hue/netkit-android/pull/7 — branch `automation/netkit-zip-stall-guard-20261023`; Draft; base/stack relationship remains unresolved.
- Do not merge automatically, rewrite/retarget branches without review, or write to `main`.

## Latest verified milestone — M103 (2026-10-28)
**Goal:** Reduce peak memory while exporting recursive SAF folders to ZIP with an optional SHA-256 checksum manifest.

- Production source commit: [9a8fcfad](https://github.com/amroalhamedi2022-hue/netkit-android/commit/9a8fcfad54e11851f428a22234819d7ea30d54b9).
- Final source/test head: [3ed0e247](https://github.com/amroalhamedi2022-hue/netkit-android/commit/3ed0e2470ab282ac9bf8f9093f918cf711141e82).
- Regression tests cover exact checksum bytes, quota rejection and temporary spool cleanup.
- The manifest is streamed through a bounded temporary spool in app-private cache storage; archive byte budget, cancellation/lease checks and cleanup remain enforced.
- Exact-head source QA: **PASS**, [CI 40746521314](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40746521314).
- Targeted Android compile/resources: **PASS**, [CI 40746521173](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40746521173). This was compile-only; **no APK was produced**.
- Physical device acceptance: **NOT TESTED** — Xiaomi Pad 5 low-memory export, real SAF providers and Windows extraction remain open.
- Security: checksum manifest is integrity metadata, not authentication. No new dependency, paid API, secret, root, Wi-Fi Analyzer or Remote Control scope.

## Product scope that must remain
- LAN tools and local sharing; SAF-authorized folder/storage access.
- Local HTTPS sharing/browser access, reliable transfers, cancellation and SHA-256 verification.
- QR and device discovery/pairing with user consent.
- FTP/SFTP client and server, with host-key verification and secure defaults.
- Hotspot features only within Android API/device capabilities; never promise unsupported 5 GHz control.
- Liquid Aurora UI, optimized for Xiaomi Pad 5.
- **Explicitly out of scope:** Wi-Fi Analyzer and Remote Control functionality.

## Acceptance gates
| Gate | Status | Evidence / next action |
|---|---|---|
| M103 source + regression tests | PASS | [source/test head](https://github.com/amroalhamedi2022-hue/netkit-android/commit/3ed0e2470ab282ac9bf8f9093f918cf711141e82) |
| Portable JVM source QA | PASS | [40746521314](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40746521314) |
| Android Kotlin/resources compile | PASS | [40746521173](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40746521173) |
| APK build/install | NOT BUILT / NOT TESTED | Build only when a defined device test requires it |
| Xiaomi Pad 5 SAF/low-memory acceptance | NOT TESTED | Export nested folders with/without checksums; verify cleanup and memory behavior |
| Windows extraction and SHA-256 interoperability | NOT TESTED | Extract on Windows and compare file tree/content hashes |
| FTP/SFTP client/server interoperability | NOT TESTED | Test from a known Windows client/server; validate auth, host keys and cancellation |
| HTTPS/QR/pairing and transfer resume | PARTIAL / NOT DEVICE ACCEPTED | Test Android↔Windows and Android↔Android on LAN/hotspot |
| PR #7 base/stack relationship | BLOCKED | Review relationship to PR #6 without mixing branches |

## Next work, in order
1. Confirm CI remains green on the exact active PR #7 head before claiming any new pass.
2. Run a focused device acceptance pass on Xiaomi Pad 5 for recursive SAF ZIP export, large folders, checksum sidecar, cancellation and temp-file cleanup.
3. Test Windows extraction and SHA-256 parity; record actual device/OS, file sizes, hashes, logs and result.
4. Continue the next connected feature milestone only after the current acceptance gate is evidenced.
5. Resolve PR #7's base/stack relationship by review; do not merge or modify PR #6/ main automatically.

## Delivery policy
- No routine hourly APK builds. Produce an APK only when it supports a named engineering/device test or final handoff.
- Separate source compile, artifact build, and physical-device acceptance.
- Final delivery requires a verified APK artifact URL, SHA-256, signature/package checks and real-device acceptance evidence.
- Use free tools only; inspect open-source licenses and security implications before adding dependencies.
- Update this handoff after each completed development batch with dated milestone, source/test SHA, CI links, build/device evidence and remaining gates.
