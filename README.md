# NetKit Android (private)

Native Android utility with **Liquid Aurora** interface, **Local Hotspot**, and **Browser File Transfer**. No Wi-Fi Analyzer and no Remote Control.

## Source transfer (one-time)

In this private repository, choose **Add file → Upload files**, upload exactly **NetKit_Android_Source_v0.6-build1-ci-ready.zip** to the root of the `main` branch, then commit. Keep the ZIP intact. The workflow `.github/workflows/netkit-build.yml` will automatically extract it, use Java 17 / Gradle 8.13 / Android SDK 36, run the JVM/static checks, execute `:app:assembleDebug` and `:app:lintDebug`, verify signing and package ID, and publish the APK, source ZIP and SHA256SUMS as a workflow artifact **only if every step passes**.

## Download after a green run

Go to **Actions → NetKit Android — verified Debug APK → latest successful run → Artifacts → NetKit-v0.6.0-alpha-build1**. The resulting ZIP contains the real debug APK, updated source package, SHA-256 checksums, and Android validation reports.

A green CI run does **not** replace real Android hardware/emulator testing. If a CI step fails, inspect the failing log and fix code before asserting success.

The repository must remain private. Do not put credentials or local network passwords in the source archive.
