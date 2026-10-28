# NetKitPro — Development Evidence 2026-10-23

**Branch:** `feature/netkit-pro-delivery-m1-m5`  
**Baseline:** verified v0.6 debug build on main; feature branch not yet built on Android.  
**Do not merge to main or label a release until a real Android compile and runtime smoke tests pass.**

## Changed code and functionality

### M1 — UI and settings: IMPLEMENTED IN SOURCE, NOT DEVICE VERIFIED
- Native NetKitPro entrypoint replaces legacy broken `NetKitApp`.
- Tablet rail, adaptive dashboard, phone navigation, Aurora/Aqua/Nebula palettes, dark/light content colors.
- Animated screen transitions; deeper layered liquid ribbons, eight floating glass bubbles; Reduce Motion prevents continuous backdrop animation.
- Core settings persisted locally. Disabled operational controls are not presented as working features.

### M2 — Browser sharing: SOURCE + PORTABLE TESTS
- Preserve mainline `SharedDirectoryBrowser` for selected SAF folder and (user-authorized) shared storage access.
- HTTPS directory browsing, scoped handle downloads, local token validation.
- New `DirectoryArchive.kt`: streaming recursive ZIP with pagination, bounded depth/count/bytes, nested/empty directory handling, unsafe-name rejection, loop prevention.
- Actual `/browse-archive/{directoryId}` HTTPS route and download links in browser.
- Critical error guard: incomplete ZIP streams must not send the terminating HTTP chunk signaling a successful response.
- Portable `qa/DirectoryArchiveTest.kt` **PASS**: 121 files + nested and empty folders; metadata/path constraints; max-entry refusal.
- New full TLS integration assertions staged in `qa/CoreSmokeTest.kt` (not run against branch Android source yet).

### M3 — Discovery: SOURCE ONLY
- `PeerDiscovery.kt` implements LAN NSD/DNS-SD registration, discovery and service loss UI.
- Discovery sightings are **not paired/trusted** and no direct Android ↔ Android authenticated transfer has been confirmed.
- Manual-IP pairing, QR trust confirmation and direct peer protocol still outstanding.

### M4 — Durable transfer journal: PORTABLE TESTS PASSED
- Pure Kotlin `TransferQueue.kt` implements exact task start, persistent states, monotonic progress, pause/resume/fail/cancel transitions, interrupted RUNNING -> PAUSED recovery.
- Disk journal uses durable write then atomic replacement, with corruption surfaced rather than silently reset.
- `qa/TransferQueueTest.kt` **PASS** on local JVM.
- **Not a resumable network transport yet**; runtime pause/cancel/restart must still be implemented and device tested.

### M5 — FTP/SFTP clients: SOURCE ONLY; SERVERS MISSING
- FTP and SFTP client source with SAF file streams and progress hooks.
- Explicit SHA256 SSH host key pinning; FTP cleartext warning, passive binary FTP.
- Device-to-server interoperability, FTP/SFTP server modes and complete permission/credential UX remain unverified/unimplemented.
- Reference: Primitive FTPd (Apache-2.0, maintained Android 8+ reference) for later safe server architecture, **not copied without license/compatibility audit**.

## Test evidence and build blocker
- Local Kotlin JVM transport tests: **PASS** for source snapshot available in container (not proof against branch build).
- Local static QA: **PASS** for source snapshot; GitHub branch QA aligned with its scoped directory backend.
- Local transfer queue: **PASS**.
- Local archive exporter: **PASS**.
- GitHub Actions runs on this branch fail within seconds, **runner_id = 0 and steps=[]**, before checkout, Kotlin, Gradle or Android tooling. Therefore **these failures are NOT Kotlin compilation evidence**. Probable GitHub-hosted runner account quota/billing/entitlement scheduling block; exact billing reason cannot be verified through current connector.
- No Android SDK or Gradle build tooling in current isolated runtime. **No new signed APK has been verified or delivered for the feature branch.**
- Android-device testing (Pad 5 and Windows Chrome/Edge) not yet done.

## Next mandatory gates
1. Restore an actual Android CI runner or Android SDK and fix any new Gradle/Kotlin/Lint failures.
2. Test selected full SAF folder tree browser download and ZIP with real tablet/browser, checksum match.
3. Add secure QR/manual pairing and Android ↔ Android direct transport; test both directions.
4. Add resumable upload state machine and backed transfer executor; test interrupted GB-sized files.
5. Integrate and validate both FTP and SFTP server modes with restricted SAF roots, credentials and security review.
6. Check Liquid UI performance and contrast from actual screenshots/videos; keep `main` stable.

## Scope exclusions
No Wi-Fi Analyzer. No Remote Control. The reference concept image is visual only.
