# First Android build and device smoke test — NetKit v0.5

This source has **not** been Android-compiled. Running the JVM tests alone does not produce a working APK.

## Build prerequisites
- JDK 17 (AGP 8.13 supports JDK 17)
- Gradle 8.13 (this ZIP intentionally does not contain a Gradle wrapper)
- Android SDK Platform 36 and Build Tools 36.0.0
- Internet at first build for Android Gradle Plugin, Kotlin, and AndroidX dependencies

From the project root:

```bash
gradle --version
gradle --no-daemon :app:assembleDebug
```

If successful, the build places the APK in `app/build/outputs/apk/debug/app-debug.apk`.

Alternatively, import into Android Studio, install SDK 36, sync, run the debug build. The optional `.github/workflows/android-debug.yml` can build once the user chooses to set up a private repo. Nothing is uploaded automatically.

## First-run device acceptance checklist
1. Install debug APK onto the Xiaomi Pad 5; launch NetKit; allow only permissions requested for features you choose.
2. Choose one file and an upload destination. Start HTTPS sharing; confirm a foreground notification is present.
3. On laptop same LAN, open one of the *actual shown* IP URLs and verify the SHA-256 certificate fingerprint before accepting a browser warning.
4. Download file, verify SHA-256 hash, upload a small file, then a 500MB+ file. Verify directory and contents.
5. For a resumable download: use a Range-capable client and confirm HTTP 206 / exact slices. Browser download managers may or may not resume.
6. Test upload and download at the same time and confirm file listing is responsive; test cancelling and Stop all notification.
7. Start Local-only Hotspot and join from Windows; verify no Internet expected. Check actual Wi-Fi band/throughput using device-side tools; the app does not force 5GHz on Android 13.
8. Rotate, background, lock screen, disconnect network, revoke SAF grants and restart; record any device-specific failures.

Share session URLs only with trusted receivers. URL token is not equivalent to peer pairing. The local certificate is self-signed, so validate its fingerprint. Do not forward the LAN port onto the public Internet.
