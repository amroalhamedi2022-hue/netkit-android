# NetKit — GitHub Actions Build 1

## Repository isolation

This ZIP does **not** grant permission to use any other user repository. Target a **new private repository** dedicated to NetKit, suggested `netkit-android`. Do not add this source to AquaWord, the download manager, Explode Arena, App Commander, or other projects.

## Required setup

1. Create an empty **private** GitHub repo `netkit-android` owned by the user's account (no template, no README).
2. Configure the ChatGPT GitHub integration to allow access to this new repository if the installation uses selected-repository access.
3. Upload the contents of the inner `NetKit_Android_v0.6` folder to the repository root, preserving `.github/workflows/android-debug.yml`.
4. Push `main` or run **Actions → NetKit Android debug APK → Run workflow**.
5. If the workflow passes, download artifact `NetKit-v0.6.0-alpha-build1`, check `SHA256SUMS.txt`, and test the APK on a real Android device.

## Checks performed by the workflow

- Gradle 8.13 official wrapper jar checksum validation.
- Static code and embedded browser JavaScript validation.
- Kotlin/JVM HTTP transport smoke tests.
- `:app:assembleDebug` and `:app:lintDebug`.
- `apksigner verify` and `aapt dump badging` with expected package `com.netkit.local`.
- Distribution packaging of real APK, repository source with generated wrapper, SHA256SUMS and Lint report.

## Unverified until a run finishes

The earlier locally produced APK is not available. A workflow file is *not* proof of a successful build. Device testing on Xiaomi Pad 5 (Wi-Fi permissions, local-only hotspot, browser HTTPS upload/download, lifecycle and cancellation) remains mandatory after build.
