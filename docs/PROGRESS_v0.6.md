# NetKit v0.6 — implementation and evidence

Date: 2026-10-18. Source-only Android alpha. No confirmed APK.

## Source changes

1. `TransferHttpEngine.kt` accepts an optional `X-Content-SHA256` ASCII hexadecimal digest with exactly 64 characters; malformed hash -> HTTP 400; hash mismatch -> HTTP 422.
2. `AndroidShareStorage.kt` computes SHA-256 incrementally while writing. It checks **before** renaming `.netkit-*.part` to the destination name and cleans up temp on failure.
3. Browser Share uses Web Crypto SHA-256 only on files <= 16 MiB (up to 16 MiB extra memory). Large files remain streamed normally; browsers without Web Crypto skip hash, and results disclose verified file count.
4. UI/workflow bumped to version 0.6; GitHub Actions artifact name updated and static preflight added.

## Test evidence

- JVM TLS transport: PASS. Includes real TLS socket tests for 6 MiB upload, successful matching SHA-256, mismatched SHA-256 (422 and no commit in the JVM test storage), malformed hash rejection (400), downloads (200/206/416), auth denials, upload consent, size limits and socket cleanup.
- Android source structural checks: PASS. HTML JavaScript parses with Node.
- **NOT PERFORMED:** Android Gradle compile, Android UI run, real SAF document provider behavior, actual Hotspot speed/band, device-to-Windows transfers.

## Limitations and next gate

Actual Android adapter verification is not proven until `:app:assembleDebug` completes and the app is installed on the Pad 5. AndroidKeyStore-generated self-signed TLS needs real browser acceptance/fingerprint validation. On devices like HyperOS, hotspots and long-running foreground services require field testing.

After first successful APK, prioritize tablet UI fidelity and real file-transfer/hotspot smoke tests; QR and resumable uploads follow.

**Work mode:** use when helpful for build/QA, not exclusively as an end-of-project step.
