# NetKit change request CR-01 — Folder/full storage, hotspot preferences, exhaustive settings
Approved 2026-10-23. Supersedes narrower file-by-file scope in M2/M4.

## Storage share (mandatory)
- Offer three distinct modes: (1) selected individual files, (2) **selected folder including descendants**, (3) **entire user-accessible shared/internal storage**, when Android's supported permissions allow.
- The user must be able to browse folders remotely (Windows browser or authorized client), download a folder as an archive or tree, and upload into a chosen destination subtree.
- Android SAF ACTION_OPEN_DOCUMENT_TREE supports user-selected trees, but Android 11+ restricts choosing the root of internal storage and certain protected directories. Do not mislabel a selected subfolder as full internal storage. For truly broad shared-storage access, evaluate MANAGE_EXTERNAL_STORAGE against Play policy, explicit opt-in, privacy and security; do not request it casually or imply access to other apps' private data. Provide graceful SAF folder mode as default and disclose inaccessible paths.
- Browsing is read-only by default; require explicit user choice to enable remote write/delete, with confirmation for destructive actions. Never expose arbitrary directories or silently share the device.
- Persist selected tree permissions; handle revoked grants, filenames, nested paths, pagination, large directories, symbolic traversal and archive streaming. Acceptance: browse nested folder and transfer a folder tree from Pad 5 to Windows browser, hash-match content, reject unauthorized traversal.

## Hotspot preferences (mandatory, platform-constrained)
- Settings UI for preferred SSID, password, security, preferred 2.4GHz/5GHz/Auto band, and persistence; mark unsupported controls as unavailable with explanation, not as fake applied settings.
- Android LocalOnlyHotspot on normal non-privileged apps generally provides system-generated SSID/password; cannot guarantee custom credentials or forced 5GHz. If system tethering settings allow manual configuration, offer explicit deep link to Android hotspot settings and instructions. Do not promise that those settings configure LocalOnlyHotspot.
- Show **actual active** SSID, password (masked by default), effective band if accessible, status and client guidance. Clearly separate user preferences from effective system configuration.
- If implementing custom SSID/band via a documented public API on a supported OS, require real-device proof, permission check and fallback. Never use hidden/privileged APIs or root.
- Acceptance: preferences persist, support matrix is accurate on Xiaomi Pad 5 Android version, real hotspot starts/stops and client joins; user is never told unsupported custom settings took effect.

## Comprehensive settings architecture (mandatory)
- Dedicated Settings hub, not only theme picker: Appearance/motion; Sharing (source mode, folders, read-only/write, destination, temporary expiry, per-client consent); Transfer (concurrency, chunk size where safe, resume, bandwidth caps, retry, notifications); Discovery/pairing; Browser HTTPS; FTP server; SFTP server; FTP/SFTP clients; Network tools (timeouts, scan safety, export); Hotspot; Security/privacy; Storage; Performance/battery; Accessibility; Diagnostics/logs; Backup/restore where appropriate.
- Each setting needs validation, sensible default, persistent storage, observable effect on actual subsystem, reset option, clear error state, and automated tests for supported logic. Disable controls whose subsystem is not implemented, do not imply functionality.
- Maintain traceability: every user request has a row, implementation owner, test, actual-device acceptance. Double-check both positive and negative cases.

## Gate mapping
- M1: design full settings navigation and persistence infrastructure; no fake operational toggles.
- M2: selected folder and recursive browser sharing, remote directory browsing, permissions.
- M4: transfer folder queues, large directory robustness and full-storage mode permission evaluation.
- M7: hotspot settings, platform capability reporting and real-device verification.
- M8: complete settings audit and regression matrix.
