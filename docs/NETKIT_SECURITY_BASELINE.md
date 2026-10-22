# NetKit security and permissions baseline — M0

**Threat model boundaries.** NetKit exposes data and services only after explicit user action. Expected network is private Wi-Fi or local-only hotspot. A LAN may still contain hostile clients. Never assume a local IP is trusted or private just because it is on Wi-Fi. Authentication is required for any data access.

| Risk | Existing measure seen in v0.6 | Required before feature gate |
|---|---|---|
| Unauthorized file enumeration | Per-session random URL token and SAF-selected documents | Expiry, session revocation and short-lived pairing; confirm no routes without auth (M2/M4) |
| Unintended filesystem access | SAF documents and selected tree | Path traversal tests, prevent access outside selected subtree (M2/M5) |
| Unwanted uploads | Receiving folder explicitly selected, upload gated | Per-client and per-session consent, filename safety, collision policy (M2/M3) |
| Local network interception | TLS server with self-signed cert | Fingerprint/QR trust UX, no unsafe automatic trust (M2/M3) |
| Spoofed LAN discovery | Not implemented | Authenticated pairing and identity pinning (M3) |
| Corrupted/partial file commits | Temp document and SHA-256 where supplied | End-to-end digest after resumptions, atomic recovery and bounded temp cleanup (M4) |
| Unlimited resources / DoS | Basic request limits, 16 GiB guard, fixed socket pool | Explicit concurrent transfer quotas, timeouts, bounded path sizes, UI cancellation (M2/M4) |
| Weak FTP authentication | FTP absent | Default OFF, random strong credentials, never anonymous by default, explicit cleartext warning (M5) |
| SSH downgrade/MITM | SFTP absent | Host key verification and strict encryption; deny on mismatch (M5) |
| Dangerous scanning | Port scan absent | Explicit user-host selection, local boundary by default, rate limiting (M6) |
| Sensitive links in logs/screenshots | v0.6 displays live bearer session URLs and hotspot password | redact diagnostic exports and issue screenshots; password conceal; expire on stop (M2/M7) |
| Unexpected background service | User-invoked foreground service exists | Explicit notification, stop action, OS lifecycle and battery tests (M7) |

**Android platform honesty:** regular apps cannot promise forced hotspot band selection or background uptime. Android 17+ local network permissions require review upon targetSdk migration. Do not request broad storage/file permissions where SAF suffices.

**Acceptance discipline:** an engineering tests pass is NOT a runtime security proof. Security-relevant features need negative tests (wrong token, expired share, denied pairing, invalid path, wrong credentials, interrupted upload) in addition to positive happy paths. All vulnerability or severe privacy bugs block release.

**User screenshot hygiene:** screenshots that show hotspot SSID/password or active bearer-token share URL should be treated as exposed session secrets. Do not reuse shown values as examples in public docs; stop/restart hotspot or revoke share sessions where applicable.
