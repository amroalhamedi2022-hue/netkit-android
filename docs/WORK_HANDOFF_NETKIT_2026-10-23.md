# NetKit — ChatGPT Work execution handoff (2026-10-23)

## User intent / authority
Finish implementation and verification of previously agreed NetKit Android product without rebuilding from scratch. ChatGPT Work should use its available computer/browser and GitHub integration to undertake Android builds, runner diagnostics, source fixes, code review, tests, and deliver reproducible outputs. Working code must remain on an isolated branch until verified. Return a comprehensive end-of-session GitHub checkpoint for Chat-based central supervision. Never claim background asynchronous completion.

## Canonical repository + branch
- Repo: https://github.com/amroalhamedi2022-hue/netkit-android
- Development: `feature/netkit-pro-delivery-m1-m5`, inspected head `e1963b5db4bb5f9bfee52e9cbc764a09e514b206` at handoff time.
- Stable branch: `main`, inspected head `6850cc63cc5c6d1b4736c24a6fb5507f6b3d5029` at handoff time.
- Branches have **diverged**: do not force push or fast-forward blindly. Fetch live heads; reconcile intentionally using PR/merge as appropriate, after tests.
- Repo visibility detected **public** despite older docs saying private. Confirm current desired visibility with user before changing, and audit publicly exposed files for credentials/tokens. Never log them.
- Latest observed Actions failures are runs 39932860432 and 39932700662 (plus preceding runs), all marked failure. Previous investigations reported runner_id=0 and empty job steps; fresh diagnosis mandatory. Do not call this a Kotlin failure without compiler evidence.
- Previous verified baseline `v0.6.0-alpha-build1` built and signed, but device screenshots showed extremely poor experience. New development branch has NOT been verified as a compiled/running Android APK.

## Read these first, in full
1. `docs/NETKIT_MASTER_PLAN_2026-10-22.md`
2. `docs/NETKIT_CR01_STORAGE_HOTSPOT_SETTINGS.md`
3. `docs/NETKIT_VISUAL_CONTRACT.md`
4. `docs/NETKIT_PROMISE_TRACEABILITY.md`
5. `docs/NETKIT_SECURITY_BASELINE.md`
6. `docs/NETKIT_PRO_IMPLEMENTATION_EVIDENCE_2026-10-23.md` (on development branch)
7. `docs/NETKIT_REQUIREMENTS_STORAGE_HOTSPOT_SETTINGS.md`
8. GitHub issue #1, and newest source + Actions YAML.

## Immediate concrete bugs
- On development branch, `.github/workflows/netkit-import-build.yml` has a "Confirm existing Android source" step requiring the OLD `app/src/main/java/com/netkit/local/ui/NetKitApp.kt`, which the feature branch removes in favor of `NetKitProApp.kt`. Repair it as an actual CI failure source (without conflating it with separate runner allocation failure).
- Same workflow has a job attempting to push `HEAD:main` while running on feature branch, with `git add -A -- app/` and questionable permissions. **Remove auto-push-to-main**; keep release/merge gated, preserve PR review.
- CI naming/packaging currently hardcodes old v0.6 alpha labels. Make artifacts distinguishable and don't overwrite the known baseline.
- CI runner allocation failures need account/workflow-level diagnosis, or real alternative free/cloud/local Android SDK build; verify using actual logs and exit codes.
- Source includes FTP/SFTP client code and transfer queue/archive tests but server implementations and true end-to-end verification remain missing.

## Execution order, mandatory milestones
- M0 baseline audit already documented; refresh only as needed.
- M1: overhaul tablet-first Liquid Aurora UI matching user visual style; 3 distinct themes, glass/bubble motion with Reduce Motion, Arabic RTL/English URLs, no empty oversized cards, persistent comprehensive Settings with actual effects. Render real screenshots using emulator where possible.
- M2: HTTPS Browser File Transfer with real directory browsing and recursive folders, streaming folder ZIP, full shared internal storage **only where lawful Android permission APIs allow**, SAF fallback, browser drag/drop and multi-file transfer, QR, upload consent and expiry. Real SHA-256 checks, device+Windows browser evidence.
- M3: secure Android NSD/mDNS discovery, manual IP, QR, user-approved pairing, identity verification, Android ↔ Android bidirectional file transfers.
- M4: persistent queue, actual pause/resume/retry/cancel, chunked upload resume with server-verified offsets/hash, files/folders and transfer state after interruptions; do not mistake queue state tracking for implemented network resume.
- M5: **FTP and SFTP client AND server**, secure authentication and SAF-restricted storage. Check library licenses/maintenance/security before reuse. SFTP must verify host identity. FTP cleartext warns user; no anonymous default.
- M6-M8 future requirements remain required in master plan but must not distract from making M1-M5 real and testable.

## User's explicit added requirements
- Share an ENTIRE selected folder recursively, OR all user-accessible internal shared storage (within actual Android restrictions) rather than file-by-file.
- Hotspot: user wants persistent SSID, password, and selectable 2.4 GHz / 5 GHz. Android ordinary LocalOnlyHotspot on Xiaomi Pad 5 cannot necessarily expose these controls; implement honest capability-aware choices, Android settings handoff where applicable, and show effective not merely desired values. Do not promise unsupported forced band.
- Comprehensive settings for every implemented subsystem, with storage, transfers, discovery, HTTP/FTP/SFTP, hotspot, appearance, security, diagnostics, background use and performance. No inert placebo toggles.
- NO Wi-Fi Analyzer. NO Remote Control. Strict LAN / local-first without root, no desktop NetKit binary required (Windows browser client).
- Prefer existing proven open-source implementations and review licenses, maintenance and vulnerabilities: LocalSend, Primitive FTPd, approved Android APIs; do not copy incompatible GPL code without license decision.

## Testing, acceptance, reporting
- Every changeset: tests (including unit/integration/emulator where feasible), Gradle `:app:assembleDebug`, `:app:lintDebug`, `apksigner verify`, `aapt dump badging`, `sha256sum`; output real, timestamped CI or local evidence.
- A compiled APK is NOT proof of working pairing, SAF sharing, settings, FTP/SFTP, hotspot, or premium animations. Label BUILT vs VERIFIED ON DEVICE separately.
- Test positive + negative auth/path/hash/disconnection cases, and do not reveal network secrets in public logs.
- If physical Xiaomi Pad 5 testing is unavailable in Work, use emulator and explain remaining device-only gate; deliver tests/scripts for user to run once, not endless alpha APKs.
- Work commits code to development branch; PR into main only after green build and required acceptance, without overwriting concurrent work. Update GitHub issue #1 checklists when evidence exists.
- End each substantial execution batch by updating `docs/NETKIT_WORK_CHECKPOINT.md` containing date, head SHA, changed file list, tests, APK artifact URL+SHA if real, milestones accepted/rejected, screenshots if available, blockers, and exact next actions. This allows ChatGPT automation monitoring of GitHub independently from Work chat.
- If a tool is blocked, investigate and use alternative tool/build strategy; do not ask user to manually operate GitHub unless no authorized alternative works.
- Nothing should silently execute or notify later unless a separate automation was explicitly created.
