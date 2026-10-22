# NetKit — Project Blueprint v0.1
Date: 2026-10-18
Status: Planning and interactive UI preview complete; native Android implementation not yet built or tested.

## Locked product scope
An Android-first network utility for Xiaomi Pad 5 and Android phones, working offline on a shared Wi-Fi LAN or via a supported local-only Hotspot.

Includes:
- Send/receive files and folders; large-file streaming, cancel, resume after a validated interruption, clear progress, speed and ETA.
- Local file server: first a browser-based upload/download interface, then SFTP, optional FTP for trusted networks only.
- Automatic discovery and manual IP/QR fallback; device pairing and per-session receive approvals.
- Ping/latency, DNS lookups, traceroute where permitted, explicit-target port checks, LAN throughput test, Wake-on-LAN if the target supports it.
- One-tap request for Android local-only hotspot; visual guidance for maximum-speed settings if band selection isn't possible by app API.

Explicitly excluded: Wi-Fi Analyzer/channel graphs/neighbor network analysis, and Remote Control (separate project).

## Design contract
Visual inspiration: user-supplied prior ChatGPT image, agreed 2026-10-18.
- Liquid surfaces with blending aqua/violet/pink gradients and translucent floating bubbles.
- Glassmorphism cards, legible type, responsive phone/tablet layouts and RTL Arabic support.
- Progress indicator styled as colored liquid with small bubbles; animation tied to actual transfer bytes in the native app.
- GPU-friendly animations, reduced-motion preference, and ability to disable visual effects.
- Themes: Aurora Liquid, Ocean Blue, Coral Glow to prototype; final themes require user visual validation.
- Avoid fake 'online', transfer speed or connection status; real app must bind visual status to real system data.

Prototype: `NetKit_Liquid_UI_Preview.html` is a standalone, *visual-only* preview. Navigation, theme swaps and transfer animation are functional demonstrations; hotspot and transfer operations are not.

## Planned platform architecture
Android: Kotlin, Jetpack Compose, Material 3, coroutines/Flow, Android Storage Access Framework for selected files/folders.
Modules (logical at first; separate Gradle modules only when warranted):
- app-ui: Liquid design system, screens, accessibility and navigation.
- connectivity: inspect active LAN, open local-only hotspot, setup help, QR sharing, lifecycle.
- discovery: Android NSD/mDNS, optional documented LocalSend discovery compatibility, manual IP fallback.
- transfer: input stream/output stream streaming, authenticated session state, checkpoint validation, transfer queue and resumability.
- browser-share: embedded local HTTPS file sharing with short-lived session authorization; user-selected storage only.
- network-tools: Ping/TCP latency clearly differentiated; DNS resolver; traceroute capability checks; safe target-limited TCP port connect; LAN throughput with consent.
- settings: persisted preferences, themes, trusted device fingerprints.
- tests: protocol, interrupted transfer, filesystem safety, hotspot failures, permission denials.

Windows: Browser-only connectivity first; optional native lightweight companion after Android MVP. No Windows install should be required just to download/upload via browser.

## Local-only Hotspot capability plan
- `WifiManager.startLocalOnlyHotspot()` is available from API 26, creates a local Wi-Fi network *without Internet*. App must keep the reservation open for the session and close it properly.
- Android 13+ app target requests `NEARBY_WIFI_DEVICES` at runtime. Older targets/platforms may require location permission; all other declared permissions should be minimal and justified.
- API 36 adds `startLocalOnlyHotspotWithConfiguration()` for custom configuration, subject to OS support and restrictions. Prior Android releases generally don't allow a normal third-party app to force 5 GHz via supported API.
- Never promise 5/6 GHz solely from Wi-Fi client capability checks; hotspot/AP capability and regional restrictions differ. Prefer 5 GHz on compatible endpoints and good conditions, and show the actual band once it is reliably observable.
- A local-only hotspot is *not* mobile Internet tethering. If user wants Internet sharing/tethering, device/vendor restrictions may prevent direct control; provide system settings shortcut/help instead.
- Handle `ERROR_NO_CHANNEL`, `ERROR_GENERIC`, `ERROR_INCOMPATIBLE_MODE`, `ERROR_TETHERING_DISALLOWED`; show clear fallbacks for pre-existing hotspots or shared Wi-Fi.
- Some networks enable client isolation and will block peer-to-peer transfers even with shared SSID; diagnose and explain.
- Proposed UI state machine: unavailable → needs-permission → starting → running → stopping → idle/error.
- Test Xiaomi Pad 5 / HyperOS behavior on real device before declaring 5 GHz selection or simultaneous Wi-Fi+Hotspot support.

## Security principles
- Default deny incoming transfers until receiver consents; pairing / one-time code; trusted device option.
- Use modern TLS where possible for HTTP transfer and authenticated browser sharing. No plain unauthenticated file server by default.
- Local network != automatically safe; never expose server to Internet deliberately and never bypass user permissions.
- Prevent directory traversal, invalid filenames, overwrites without confirmation, and unbounded file size/quantity.
- Transfer files with bounded buffers; verify bytes/hash before resuming; use temporary partial files and atomic completion when practical.
- Shut down listeners and close temporary credentials when session ends.

## Research references
- Android local-only hotspot: https://developer.android.com/develop/connectivity/wifi/localonlyhotspot
- Android WifiManager: https://developer.android.com/reference/android/net/wifi/WifiManager
- Android NSD/mDNS: https://developer.android.com/reference/android/net/nsd/NsdManager
- Android foreground service types: https://developer.android.com/about/versions/14/changes/fgs-types-required
- LocalSend protocol: https://github.com/localsend/protocol
- LocalSend reference app: https://github.com/localsend/localsend
- Primitive FTPd: https://github.com/wolpi/prim-ftpd
All open-source code reuse will require license review. Protocol compatibility does not necessarily require copying implementation code.

## Implementation sequence / acceptance criteria
### Stage 1 — Android shell and capability detection
- Launch on Xiaomi Pad 5 / Android phone; adapt screen density and RTL.
- Real network state and connection type; request permission only on relevant action.
- Real local-only hotspot start/stop on supported OS with errors handled.
- UI matches the approved reference within platform conventions.

### Stage 2 — Browser Share MVP (fastest end-to-end utility)
- Choose Android files/folders via SAF; enable a *restricted, authenticated* temporary local server.
- Open page in Windows browser and upload/download one or more files.
- Handle large-file streaming; confirm cancellation, path safety, end-to-end data integrity.
- Test on LAN and a user-started local-only hotspot.

### Stage 3 — Native device discovery & transfers
- Nearby devices via NSD and manual IP/QR fallback.
- Sending to another NetKit instance; resumable transfers and queue.
- Test network drop and reconnect without file corruption.

### Stage 4 — Network diagnostics and file protocols
- DNS lookup, TCP/ICMP distinction, traceroute caveats, port checks, bandwidth test.
- SFTP, optional FTP restricted to trusted LAN; Wake-on-LAN.

### Stage 5 — Optimization, tests and handoff
- Real-device 2.4/5GHz behavior checks, throughput, thermal and battery tests.
- Visual polish and light/dark themes, graceful performance on low-end phones.
- Documentation and release packaging; only then move substantial remaining work into Work if beneficial.

## Current deliverables
- Interactive visual HTML preview: created and available.
- This blueprint: created and available.
- Native Android APK: NOT yet built or tested.
- Work handoff: NOT initiated.
