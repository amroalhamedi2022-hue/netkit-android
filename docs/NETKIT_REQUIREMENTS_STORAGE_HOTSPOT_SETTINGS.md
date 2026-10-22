# NetKit requirement amendment — Storage, Hotspot and Complete Settings
Approved 2026-10-22. This amendment is **binding** and expands the M2/M4/M7 criteria. It does NOT replace existing promises.

## Files and storage
1. Replace the file-by-file-only flow with **Share a selected folder** (recursive) through Android SAF, then browse/download children directly from the Windows browser without a native Windows app. Remember explicit tree permissions, provide browse-only and optional upload modes; show folder hierarchy.
2. Provide a distinct opt-in **Share shared internal storage** feature for the entire accessible shared storage volume, not just selected files. On Android 11+, SAF cannot grant the shared-storage root; use Android's `MANAGE_EXTERNAL_STORAGE` special Settings grant only if the user explicitly enables this mode. Do NOT represent this as access to other apps' private `/data/data` or protected `Android/data` and `Android/obb`.
3. All-files permission must be requested only by this opt-in file-manager-like use case; review Google Play policy before distribution. Keep the SAF folder mode working without it.
4. Browser file management is opt-in: first default **read-only**, with a separate explicit allow-upload toggle scoped to the receiver folder. If deletion/rename is ever offered it must require confirmation and permission.
5. Do not pre-index the whole volume in memory at UI start; lazy folder browsing with bounded pages and no accidental directories outside authorized roots. Do not expose file system absolute paths, leaked tokens or unselected app-private data.
6. Real user acceptance: select one folder with nested subfolders and mixed-size files; from Windows browser traverse directories and download. Then opt in to full *shared storage* and browse top-level folders, respecting documented restricted Android directories. Verify hash, stop session revocation, and both directions where explicitly enabled.

## Local hotspot configuration
1. User can set, persist, and validate preferred SSID, WPA2 password and **Auto / 2.4 GHz / 5 GHz** for supported Android versions; the app must show preference and the actual applied values distinctly.
2. **On Android API 36+**, use `WifiManager.startLocalOnlyHotspotWithConfiguration(SoftApConfiguration, Executor, Callback)` for public custom configuration, validating device band support and handling failure explicitly.
3. **On Xiaomi Pad 5 Android 13 (API 33)**, `startLocalOnlyHotspot()` chooses random credentials/band. Normal non-system apps CANNOT force fixed SSID/password/5GHz with this API. Do not display editable settings as actually applied. Provide an honest compatibility explanation plus system hotspot settings option where appropriate; system **tethering** may share Internet and isn't the same as LocalOnlyHotspot.
4. Preferences never imply control until supported; show effective SSID/passphrase/band returned by Android. Do not log passwords.

## Settings everywhere
- Central Settings Hub with per-feature groups: appearance/accessibility/motion, storage/share scope, sender/receiver permissions, browser server/TLS/expiry, transfer concurrency/history/retry/resume, trusted devices/discovery, FTP/SFTP settings, diagnostics, hotspot, notification/battery, privacy/security, import/export.
- Implement feature settings alongside each real module, not as inert toggles for absent features. Early groups for storage + hotspot + appearance + core browser server; other settings appear when modules actually exist.
- Clearly label unimplemented options in technical plan, not empty decorative controls in release UI.

## Gate rule
No milestone M2/M7 passes before above feature-specific device tests. Update traceability and issue #1 in the same repo. CI success counts only as compilation, never Android device proof.

### Official API evidence
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/training/data-storage/manage-all-files
- https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspotWithConfiguration(android.net.wifi.SoftApConfiguration,java.util.concurrent.Executor,android.net.wifi.WifiManager.LocalOnlyHotspotCallback)
