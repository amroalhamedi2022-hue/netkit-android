# NetKit Android — MASTER IMPLEMENTATION PLAN
**Status:** LOCKED BASELINE / implementation pending  
**Baseline:** v0.6.0-alpha-build1 (GitHub Actions signed Debug APK)  
**Established:** 2026-10-22  
**Primary:** Xiaomi Pad 5, Android tablet-first; Android phones supported.  
**Owned repository:** `amroalhamedi2022-hue/netkit-android` (private).

## 0. Binding product contract

NetKit is one **offline/local-first** Android network and file-transfer toolbox, not a screen remote-control product.

IN SCOPE:
- Real, bidirectional file/folder transfer: Android ↔ Android and Android ↔ Windows **browser** on LAN/hotspot.
- Local device discovery, trusted pairing, manual-IP fallback, QR, incoming consent.
- Browser File Transfer without installing anything on Windows, HTTPS local server, temporary shares.
- Resumable upload/download with accurate progress, multi-file queue, pause/resume/cancel/retry, failure recovery, transfer history.
- FTP and SFTP **client and server**, with scoped SAF folder access, authentication and safe defaults.
- Network tools: DNS, TCP checks, genuine ICMP Ping when supported, traceroute with clearly labeled alternative if restricted, local discovery, optional bounded port checks, LAN speed test between cooperating endpoints, Wake-on-LAN.
- Local-only Hotspot with Android-compliant availability, progress, credentials and troubleshooting. Do not falsely claim 5 GHz control.
- Glass/gradient/liquid design system, polished interactive transitions and accessibility.
- Background transfer foreground service with clear notification, stop controls, battery guidance.

OUT OF SCOPE:
- Wi-Fi Analyzer: **DO NOT IMPLEMENT**, including Wi-Fi-channel graphs/scanning UI.
- Remote Control: **DO NOT IMPLEMENT**, including screen streaming/mouse/keyboard control.
- Windows native NetKit app is **not required**; Windows browser workflow is.
- No cloud relay, accounts, root or internet required for everyday transfer.
- Do not promise control over Android-restricted Wi-Fi band settings.

Visual reference uploaded by user in chat on 2026-10-22: a 3-row image of **Liquid Aurora (dark electric blue/purple), Aqua Glass (light aqua), Nebula Flow (dark magenta/coral)**. This artwork belongs to a DIFFERENT remote-control concept: **use only its graphic language**, not remote-control functions, text or screen layouts. UI is original NetKit.

## 1. Evidence-based v0.6 baseline (audit, not product completion)

Inspected:
- `app/src/main/java/com/netkit/local/ui/NetKitApp.kt`
- `app/src/main/java/com/netkit/local/share/TransferHttpEngine.kt`
- `app/src/main/java/com/netkit/local/share/AndroidShareStorage.kt`
- `app/src/main/java/com/netkit/local/connectivity/HotspotManager.kt`
- `app/src/main/java/com/netkit/local/app/NetKitCore.kt`
- `app/src/main/java/com/netkit/local/app/TransferService.kt`
- `app/src/main/java/com/netkit/local/tools/Diagnostics.kt`
- user's actual Android screenshots (2026-10-22).

Exists in code: Compose navigation (five basic tabs), three simple accent themes, limited floating dots, rudimentary HTTPS browser sharing with per-session secret and self-signed cert, SAF-selected files and destination, streaming uploads/downloads, **single-range DOWNLOAD** resume, checksum handling for uploads, LocalOnlyHotspot, DNS and TCP checks, foreground transfer service, basic JVM QA. The app compiled, linted and produced a signed Debug APK.

**Not proven by APK build:** two-device end-to-end transfers, acceptable styling, Android runtime correctness of SAF on varying providers, Android-to-Android peers, throughput, full resume and folder support, real hotspot mode/band, background persistence on HyperOS.

Missing or insufficient: adaptive tablet navigation, professional motion and glass, native discovery/pairing, QR, transfer queue/history, upload resume, FTP/SFTP, LAN speed, true ICMP/traceroute, scoped port tests, Wake-on-LAN, broad real-device QA.

Do not represent the above baseline as a complete NetKit product.

## 2. Delivery discipline (non-negotiable)

- Work in **this same repository**; preserve functional current code until its replacement passes equivalent regression.
- All work proceeds in order M0 → M8; no silent feature de-scoping. Dependencies are explicit.
- Each feature is tagged **NOT STARTED / IN PROGRESS / BUILT / VERIFIED ON DEVICE**. "BUILT" ≠ "VERIFIED".
- Each milestone has real files, diff/commit, observed behavior, screenshot/video where relevant, tests, limitations, and explicit pass/fail evidence.
- Never call an APK "final" if its corresponding user scenarios have not passed. GitHub CI, static checks, and JVM unit tests are necessary but not sufficient.
- Build on CI internally during development; avoid pestering user with repeated cosmetic alpha builds. Device evidence requested only when actual hardware-specific gate is reached. Stop after a failing gate and fix root cause before moving on.
- No arbitrary mock buttons, placebo progress, "coming soon" cards counted as implemented functionality.
- Do not include passwords or bearer-token URLs in build logs, issue descriptions or screenshots. Use session revocation and expiring access.
- Compare the resulting UI against the **approved visual language** before sign-off.
- Existing `v0.6.0-alpha-build1` is a baseline only, never relabel it as a final production release.

## 3. Milestones and acceptance gates

### M0 — Audit + delivery baseline
**Deliverables:**
1. Pin current source/release with known SHA and document existing vs missing.
2. Keep official Gradle wrapper, reproducible GitHub Actions APK, lint/JVM QA, artifacts.
3. Build a traceability matrix: promise → code owner → test → milestone → evidence.
4. Record Android/HyperOS restrictions and security threat model before extending network surface.

**PASS:** repository builds from clean checkout; source inventory and missing-feature truth table exist; all new tasks mapped to gates.  
**No UI/completeness claims from CI.**

### M1 — Liquid Aurora design system + tablet architecture
**Implementation:**
- Decompose giant `NetKitApp.kt` into navigation, `ui/theme`, reusable GlassSurface/LiquidBackground/BubbleLayer, components, screens and state-based ViewModels.
- Palette tokens and exact 3 selectable styles: Liquid Aurora (default deep blue/violet/cyan), Aqua Glass (high-readability light cyan), Nebula Flow (violet/coral). Persistent appearance choice, no black text on dark cards.
- Layered light/refraction-inspired effects (feasible Android rendering), animated liquid shapes, high-quality depth and controlled bloom; **not just static gradient plus four circles**.
- Animated route transitions, button state feedback, spring press interactions, contextual progress visual, subtle activity/connection states, optional reduced motion, battery/performance controls.
- Adaptive tablet dashboard: side navigation or rail where it fits, contextual two-column info/cards, compact phone layout, landscape/portrait, safe-insets; Arabic RTL and English LTR numbers/URLs.
- Home: real network status, nearby devices, Send/Receive, active transfers, Server, Hotspot, diagnostic shortcuts. Avoid giant empty panes or "coming soon" placeholders.

**PASS:** real Compose screens on Pad 5-sized layout and phone layout, three styles visibly distinct, adequate text contrast, meaningful animations during actual interaction, no clipping/overlaps in RTL, acceptable frame performance and battery impact measured (not assumed). Screenshot/short capture evidence.

### M2 — Browser Transfer / local server: actually usable
**Implementation:**
- Preserve working TLS/session core, upgrade desktop responsive web UI (dropzone, files/folders picker where supported, multi-item queue, progress with real bytes/time/ETA, consent, upload/download, cancel, history/error display).
- Share photos/video/files of varied sizes from Android to browser and browser to Android via SAF. Support stable upload destination and per-file authorization.
- Correct multi-address interface selection: show reachable IP/hostname, avoid VPN or unreachable IP as default. QR link, copy/share. Clear self-signed certificate/browser warning UX and safety explanation.
- Explicitly configure/start/stop service; handle disconnects, retries, clean server shutdown and background notification.
- Folders: browser `webkitdirectory` where supported and server safe relative-path handling or negotiated archive strategy. Never expose arbitrary private device paths.

**PASS:** on same Wi-Fi and Android Local-only Hotspot, Android ↔ Windows Chrome/Edge browser both directions, a batch of 10+ mixed files and one large file, correct SHA-256, explicit receiving consent, stop server revokes session. Verify at least one real device+browser session with logs/screen captures; no internet dependency.

### M3 — Native Android device discovery and trust
**Implementation:**
- Android NSD/mDNS with service advertisement/discovery, lifecycle-aware management and user-visible scan state; use MulticastLock only when necessary.
- Manual IP fallback and QR pairing. Device display name/type and online/offline status.
- Pairing confirmation on both ends, timeout, scoped secrets/cert pins, unpair/revoke.
- Native bidirectional Android ↔ Android send/receive for files and URLs with user consent and accurate live progress.
- Local trusted-device history (privacy-preserving), reconnect behavior and duplicate discovery handling.

**PASS:** two Android devices on same LAN discover each other; pair once with human confirmation; successful send both ways; reject/unpair prevents access; manual-IP route works if discovery blocked. Test hotspot scenario separately.

### M4 — Persistent transfer manager, resume, temporary share
**Implementation:**
- Explicit `TransferSession`/`TransferTask` models, Room persistence, SAF URI grant lifecycle, multi-file/folder queue, concurrent transport caps.
- Pause/resume/cancel/retry with clear state-machine, retry backoff and atomic file commit.
- **True resumable uploads**, chunk-offset or session protocol with validation; current download Range alone is insufficient. Recover interrupted large transfers without starting from zero if both endpoints support it. Verify sizes/hash after resume.
- Temporary share with QR, expiration, one-time access where appropriate, access revocation, session activity and progress.
- Accurate speed and ETA from measured transfer bytes/time, with no invented figures.

**PASS:** interrupt and resume a large transfer, byte-identical SHA-256, persistent recoverable queue after app relaunch where Android storage permissions permit; cancelled jobs release sockets/temp files. Batch/file names and folder hierarchy correctly preserved.

### M5 — FTP / SFTP (both client and server)
**Implementation:**
- Evaluate maintained, Android-compatible FTP and SSH/SFTP libraries; review transitive deps, licenses, security advisories and SAF handling before integration.
- FTP client: browse, upload, download, navigation, rename/delete only with explicit confirmation, timeouts, passive/active modes as applicable.
- SFTP client: SSH host key verification, authentication, browse/upload/download/resume if supported; never silently bypass key verification.
- FTP server: opt-in, constrained SAF folder, authentication, default local-only listening, show IP/port, safe stop. Warn clearly when plain FTP transmits data/credentials unencrypted.
- SFTP server: approved SSH library, own host key, authentication and permission-scoped read/write; no anonymous open internet server.
- Built-in local HTTP/HTTPS server kept independently functional.

**PASS:** connect from a known Windows FTP/SFTP client and from NetKit to test servers; list/upload/download/rename/delete authorized test files; reject wrong credentials/path traversal; server stop closes sessions; compare hashes.

### M6 — Genuine diagnostics, LAN tools and Wake-on-LAN
**Implementation:**
- DNS A/AAAA with timing and clear failures; TCP handshake latency with user-selected host/port and timeout.
- Genuine ICMP Ping when permitted by Android; accurately label restricted fallback as TCP probe (never pretend it is ICMP).
- Traceroute using feasible platform capabilities; clearly communicate permission/route restrictions.
- Local device/service discovery view (non-Wi-Fi-analyzer), rate-bounded opt-in port checks for devices user controls.
- **LAN speed test** using cooperating endpoints, separated upload/download throughput, measured units, no internet benchmark mislabeled LAN speed.
- Wake-on-LAN magic packet builder for authorized local PCs, device MAC and broadcast settings; wake success depends on PC BIOS/NIC/network.
- Copy/export results and explain common diagnostics failures.

**PASS:** DNS and TCP verified against controlled host; ICMP/traceroute correctly label unavailable cases; LAN speed measured from two endpoints; WOL packet verified on network and actual wake tested on supporting PC.

### M7 — Hotspot, background reliability, accessibility and polish
**Implementation:**
- Preserve LocalOnlyHotspot; show actual SSID/password with conceal/reveal, QR join and copy; sensible network route suggestions when Wi-Fi stays connected.
- No unsupported guarantees of 5 GHz on Xiaomi Pad 5 / Android 13 or ordinary app privileges. Prefer strongest feasible band only where Android APIs permit; otherwise explain user-configurable system path.
- Foreground service notification with active session/transfer count; handle rotations, permissions, process destruction and thermal/battery policies as OS permits.
- App performance profiling on Pad 5: animation jank, heap consumption, background CPU, wake locks and large-file memory usage. Add reduced-motion/low-power settings.
- Consistent empty/loading/error/success states, Arabic localization/RTL, TalkBack labels, touch targets and visual contrast.

**PASS:** hotspot pairing+browser file share on supported mode, no advertised band claims without evidence, understandable failure/recovery UX, tested lifecycle scenarios.

### M8 — Real-device acceptance + release gate
**Matrix:** Xiaomi Pad 5 + at least one Android phone; Windows Chrome/Edge as browser clients; same Wi-Fi and hotspot; online/offline; landscape/portrait; permission deny/regrant; app screen off where allowed; multi-GB file when practical; reconnection/corruption/cancel/resume; SFTP auth; 3 themes and animations.

**PASS:** all in-scope promises present; no P0/P1 defects; user-visible end-to-end evidence for core transfer, discovery, FTP/SFTP, hotspot and LAN diagnostics; valid APK signature, `aapt`, QA, hash file, source ZIP, license notices; honest documented device limitations. Only then call it a release candidate/final depending on test coverage.

## 4. Open-source-first decision record

Before coding a subsystem, examine source/maintenance/license/API/security; reuse only compatible and useful parts. Visual designs remain custom:
- **LocalSend** — Apache-2.0, established HTTPS/REST discovery and local file transfers; review protocol `https://github.com/localsend/protocol` and `https://github.com/localsend/localsend`. Interoperability optional and separately tested; reference does not imply automatic compatibility.
- **Primitive FTPd** — Apache-2.0 Android FTP/SFTP implementation, review source for SAF integration and Android server behavior: `https://f-droid.org/en/packages/org.primftpd/`.
- **KDE Connect** — GPL, useful device identity/pairing and device transfer architectural reference; **do not paste its GPL implementations into differently licensed code without proper license decision**: `https://github.com/KDE/kdeconnect-android`.
- **Android NsdManager** — first-party service discovery API: `https://developer.android.com/reference/android/net/nsd/NsdManager`.
- **Android SoftApConfiguration** — privileged configuration limitations: `https://developer.android.com/reference/android/net/wifi/SoftApConfiguration`.

For any dependency, record chosen version, SPDX license, compatibility, last security review and alternatives. No copy/paste from random apps or assuming pictured UI code is reusable.

## 5. Execution reporting format (every development turn)
1. Current milestone / gate.
2. **Implemented now** (specific paths, commit links, functional effect).
3. **Verified now** (CI or device evidence; distinguish).
4. **Still missing**, with blockers and exact next actions.
5. **No milestone advance** until its PASS conditions hold.

## 6. Change-control and anti-regression rules
- Scope changes require a visible user decision and a new plan revision. Otherwise this plan is authoritative; tasks cannot silently disappear.
- Critical features are never swapped for a visual mock, and beautiful UI never excuses broken transfers.
- No Wi-Fi Analyzer / no Remote Control in source, UI or future roadmap.
- Keep secure default behavior (user consent, scoped SAF access, bounded networks, expiring secrets, TLS / SSH authentication).
- Initial quality on actual Pad 5 is more important than extra cosmetic screens or a growing Alpha counter.
