# NetKit v0.6 Build 1 — reproducible handoff (2026-10-22)

## Honest status

**NO APK was produced or verified in this execution.** The earlier APK referenced by the previous Work session is not present here; it must not be reused or assigned a new SHA256. The isolated runtime also lacks Gradle, its 8.13 Wrapper JAR, Android SDK, aapt and apksigner. DNS/network calls to Maven/Google/Gradle repositories failed. This source package is not a substitute for a working installable APK.

## Restored from the v0.6 source ZIP

- `ACCESS_COARSE_LOCATION` (maxSdk 32) alongside `ACCESS_FINE_LOCATION` (maxSdk 32), plus `NEARBY_WIFI_DEVICES` (API 33+).
- `ActivityResultContracts.RequestMultiplePermissions()` runtime contract and a permission check before a hotspot is started by the service.
- SharedPreferences Kotlin `edit` extension (`androidx.core:core-ktx`).
- Liquid Aurora launcher icons for density-specific classic/adaptive icons, with a regenerate script.
- Backup/data-extraction exclusions and `allowBackup=false`.
- Extended static assertions, cleaned up deprecated `URL(String)` usage in test-only QA.
- Gradle 8.13 pinned distribution properties with SHA-256 and bootstrap scripts.
- GitHub Actions workflow adjusted to build and lint with `./gradlew`, verify the official wrapper JAR and debug APK.

## Gradle wrapper caveat

There is **no** `gradle/wrapper/gradle-wrapper.jar` inside this ZIP. We intentionally did not fabricate an unverified wrapper JAR. The included `gradlew`/`gradlew.bat` bootstrap entrypoints require an installed `gradle` executable on first use, and generate the official Gradle 8.13 wrapper via `gradle wrapper --gradle-version 8.13 --distribution-type bin`. The CI configuration installs Gradle 8.13 first for the same purpose. After bootstrap, Gradle replaces the scripts with standard scripts and the wrapper JAR is validated against the public Gradle checksum `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.

The official Gradle 8.13 binary ZIP SHA-256 is `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` as shown on the official Gradle checksums page. The original request to ship an already-generated official wrapper **remains incomplete** until this bootstrap successfully runs.

## Verified in this execution

```
python3 qa/check_static.py        # PASS
bash qa/run_jvm_tests.sh         # PASS (portable JVM, TLS socket transport)
```

`./gradlew --no-daemon :app:assembleDebug :app:lintDebug` failed before Android compilation because neither a Gradle executable nor a wrapper JAR was available. Therefore `BUILD SUCCESSFUL` is **not claimed**; lint is **not verified**, and no apksigner/aapt result or APK checksum exists.

## Next steps on a machine/CI with working Android SDK and Maven access

1. Use JDK 17, install Gradle 8.13 and Android SDK platform 36 / Build Tools 36.0.0.
2. On first run, generate official wrapper JAR with `gradle --no-daemon wrapper --gradle-version 8.13 --distribution-type bin` (alternatively use bootstrap `./gradlew`).
3. Check official wrapper JAR checksum before trusting it.
4. Run `./gradlew --no-daemon :app:assembleDebug :app:lintDebug` and fix any compilation/lint failures that appear. Do not assume they'll succeed.
5. Rerun `python3 qa/check_static.py` and `bash qa/run_jvm_tests.sh`.
6. Verify `apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk` and `aapt dump badging app/build/outputs/apk/debug/app-debug.apk`.
7. Name the *actual verified* output `NetKit_v0.6.0-alpha-debug.apk`, compute its SHA256, and distribute alongside updated source.
8. Install and perform real Android tests on Xiaomi Pad 5 or another Android device: runtime Wi-Fi/location permissions, Local-only Hotspot availability and band, notifications/service lifetime, desktop↔tablet browser HTTPS, certificate trust/fingerprint, SAF reads/writes, and clean cancellation. **JVM tests do not replace these tests.**

The original scope is preserved: Liquid Aurora look, Local Hotspot, browser file transfer, no Wi-Fi analyzer and no remote-control subsystem. No GitHub repository was accessed or changed in this execution.
