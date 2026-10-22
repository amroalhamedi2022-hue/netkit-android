> **Build continuation — 2026-10-22:** restored Lint-related source changes and tested JVM transport/static checks. An Android APK has **not** been produced in this runtime (Gradle/SDK unavailable). The project includes Gradle 8.13 distribution config and temporary wrapper bootstrap scripts; the official wrapper JAR must be generated on a machine with Gradle. See [docs/BUILD_RESUMPTION_2026-10-22.md](docs/BUILD_RESUMPTION_2026-10-22.md) for exact evidence and next steps.

# NetKit v0.6.0-alpha 🫧 — Android source

Native Kotlin/Jetpack Compose source for **NetKit**, a private, local-network toolkit for Android tablets and phones. The project's visual direction is **Liquid Aurora / glass / bubbles** with Aurora, Ocean and Nebula themes. Designed for Xiaomi Pad 5 (Android 13/HyperOS) plus compatible Android devices.

**Status: source prototype. This is NOT an APK, has not been compiled with Android Gradle, and has not been tested on a physical Android device.** Transport tests below exercise portable JVM server code, not Android behavior.

## What changed from v0.5

- Added **optional SHA-256 source-to-destination verification** for HTTPS Browser Share uploads. The browser calculates a digest for files up to **16 MiB** using Web Crypto when supported; the Android storage adapter computes SHA-256 incrementally while writing to an invisible `.netkit-*.part` document. A mismatch returns HTTP **422** and removes the temporary document *before* renaming/finalizing it. Invalid digests are rejected as HTTP **400**.
- Files larger than 16 MiB are still streamed normally without loading the complete file into browser memory; **they do not yet get source-to-destination SHA-256 validation**. HTTPS authenticates the transport once the certificate is manually verified, but that is not a complete file-level integrity scheme.
- The browser reports how many uploaded files had SHA-256 verified. If Web Crypto is unavailable, upload works but without the extra digest verification.
- Expanded JVM regressions to check valid hash, mismatched hash, malformed hash and preservation of previously passing resumption/server tests.
- Retained HTTP byte-range download resume, notification foreground service, Android local-only Hotspot, Liquid UI, and DNS/TCP diagnostics.

**Testing done:** `bash qa/run_jvm_tests.sh` passed; `python3 qa/check_static.py` passed (including embedded browser JS syntax). These are portable JVM/static checks, **not Android SDK compilation or a real Pad 5 run**. Android SAF adapter's `.part` cleanup and atomic-ish rename are source-reviewed but *not yet device-tested*. The underlying document provider may not guarantee atomic rename semantics.

## Existing capabilities from v0.2–v0.4

- Liquid Compose Arabic RTL UI with three themes and optional low-cost bubble movement.
- Local-only Hotspot via Android's public `startLocalOnlyHotspot` API. It creates a network **without Internet**, not system Internet tethering. **Android 13 does not let us reliably force 5GHz** through ordinary public APIs, so we never claim it was selected or measured.
- SAF file selection & receiving folder. Local HTTPS file server, per-session unpredictable URL secret, AndroidKeyStore-generated self-signed TLS key, displayed SHA-256 certificate fingerprint. Browser UI with sequential upload queue, progress/cancel and downloads.
- DNS and TCP reachability testing (TCP is NOT ICMP ping); no Wi-Fi Analyzer or Remote Control (both out of scope).

## Test portable HTTPS transport (no Android SDK required)

Requires JDK with `keytool`, Kotlin CLI and Bash:

```bash
bash qa/run_jvm_tests.sh
python3 qa/check_static.py
```

The tests generate disposable certificates. A trust-all client exists **only** in JVM QA source and must never ship in Android production code.

## Build debug APK

### Option A: Android Studio

Prerequisites: Android Studio, JDK 17, Android SDK Platform 36 and Internet for Gradle dependencies on first build. Import this folder as a Gradle project. This ZIP does not contain the Gradle wrapper JAR; use an installed Gradle distribution or the documented GitHub Actions workflow. Use Gradle 8.13 to build:

```bash
gradle :app:assembleDebug
```

The expected output, **after successful build**, is `app/build/outputs/apk/debug/app-debug.apk`.

### Option B: private GitHub Actions workflow

Once you are ready to use a private GitHub repository, push the **contents of this project root**. Open **Actions → NetKit Android debug APK → Run workflow** (or push to `main`). The workflow provisions JDK 17, Android SDK Platform 36 and Gradle 8.13 and *attempts* an Android build. If successful, an artifact called `NetKit-v0.6-debug-apk` contains the APK. The workflow has **not** run yet; any Gradle errors must be fixed based on its logs. Keep the repository **private**.

## Device checklist

1. Install first successful debug APK on the Android tablet and grant the one-time network/notification permissions when requested.
2. Select a test file and receiving directory, start Browser Share. Connect laptop to same Wi-Fi, or start NetKit Local-only Hotspot and join its SSID.
3. Open the HTTPS link and verify self-signed certificate SHA-256 fingerprint from the Android app before trusting the browser warning. Never trust an unfamiliar fingerprint automatically.
4. Test a 1 MB file then 500 MB+ file, compare SHA-256 hashes; test browser upload, cancel, and stop while transfer is active.
5. Rotate the tablet, switch apps, turn off screen and check behavior with/without battery optimization. Verify visible persistent notification and Stop all action.
6. Test hotspot band/speed **on device**. The app shows configured band only where APIs expose it; this is not a real PHY measurement.
7. Test system edge cases: deny Nearby Wi-Fi permission, deny notification permission, stop from notification, kill process, reboot, revoke receiving folder, unreliable LAN and another hotspot already active.

## Limits and safety

- **No confirmed APK**, actual hotspot behavior, or on-device background reliability yet. HyperOS may impose extra restrictions. The foreground service improves resilience, but never guarantees Android won't kill the process.
- The app uses a single local-only server/session. A tokenized URL is **not full trusted-device pairing**. Use only on personal/trusted LAN, and stop when done. Certificate trust still requires manual user verification. Avoid untrusted networks and forwarding the LAN port to the Internet.
- Android 13/HyperOS may auto-pick 2.4 or 5GHz for local-only hotspot; no root/system privilege or guaranteed band control. An optional system Hotspot can be configured from Android settings where available.
- **No real ICMP Ping, mDNS discovery, QR scan, SFTP, resumable uploads, *large-file* SHA-256 verification, or LAN speed benchmark** yet. Partial download resume works only for streams with known sizes and compatible clients; non-seekable SAF data may be slow to seek for very large offsets.
- Android app process death destroys the share; it does **not** automatically restart and expose files.
- The background foreground-service type is `connectedDevice`, intended for communication with local external devices according to Android guidance. Avoid switching it to `dataSync` without considering Android 15's six-hour total background limit.

## Next technical milestones

1. **First successful Android debug build / install**. Run Gradle on a system with Android SDK, then fix actual AGP, Compose and Android API errors from compiler logs. No build result is claimed yet.
2. Pixel-closer Liquid Aurora UI to the user-provided reference image; accessible tab layout on tablet and mobile.
3. QR pairing and verified peer/device authorization (beyond bearer URL token).
4. Large-transfer hash confirmation, resumable uploads and structured per-file progress.
5. Actual network diagnostics, discovery and optional SFTP.

**User workflow decision:** Continue in Chat when appropriate, and switch to Work whenever it is genuinely useful (for example a multi-step Android build/device workflow); Work is not prohibited and does not have to be saved until the end.

## CI build 1 (private GitHub Actions)

The source includes `.github/workflows/android-debug.yml` and `tools/package_verified_build.py`.
This workflow installs the Android SDK and Gradle 8.13, bootstraps and SHA-verifies
the **official** missing Gradle Wrapper JAR, runs portable JVM + static checks,
compiles and lints the Android app, and verifies signature and APK metadata.
Only after all checks pass will it upload the debug APK, packaged source (including
the generated official wrapper), and SHA256SUMS as a workflow artifact.

**This source ZIP is not a successfully built APK.** The connected account needs a
new independent **private NetKit repository**, and this chat's GitHub integration
currently has no create-repository operation. See `docs/GITHUB_ACTIONS_BUILD1.md`
for the exact setup. Test installation and transfer/hotspot functions on Android
hardware after CI; desktop/JVM tests are not a substitute.
