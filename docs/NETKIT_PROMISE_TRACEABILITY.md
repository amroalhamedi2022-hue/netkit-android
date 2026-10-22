# NetKit — PROMISE TO EVIDENCE TRACKER
| Promise / feature | Existing baseline evidence | Milestone | Acceptance proof | Status |
|---|---|---|---|---|
| Tablet-first liquid visual design | Adaptive dual-column dashboard, corrected text contrast and animated navigation now in source; still not a faithful final UI | M1 | Pad 5 portrait/landscape screenshots + motion capture + accessibility | IN PROGRESS — NEEDS DEVICE CHECK |
| Aurora Liquid theme | Accent palette only | M1 | Complete dark styling across all pages | NOT STARTED |
| Aqua Glass theme | Dedicated light scheme and translucent cards added in source | M1 | Readable light variant across all pages | IN PROGRESS — NEEDS DEVICE CHECK |
| Nebula Flow theme | Palette selector only | M1 | Complete coral/violet variant across all pages | NOT STARTED |
| Browser Transfer download | Core HTTPS GET, SAF, one-range GET | M2 | Android→Windows browser transfer, SHA matching | NEEDS DEVICE CHECK |
| Browser Transfer upload | Core POST, SAF destination, digest | M2 | Windows browser→Android transfer, SHA matching | NEEDS DEVICE CHECK |
| HTTPS browser desktop interface | Existing transfer page enhanced with authenticated directory navigation and lazy child lists | M2 | Responsive upload/download/progress+consent flow | IN PROGRESS — NEEDS DEVICE CHECK |
| Share by QR | Not found in examined UI | M2/M3 | Scan QR with Windows/Android and connect | NOT STARTED |
| Native device discovery | Not found | M3 | 2 Android devices find each other on LAN/hotspot | NOT STARTED |
| Secure pairing | Not found | M3 | approve/reject/unpair and reconnect | NOT STARTED |
| Manual-IP fallback | Text URLs exist but no peer connect UI | M3 | Pair/connect by reachable address | NOT STARTED |
| Android ↔ Android direct send | Browser upload/download only | M3 | Actual two-way files and URLs | NOT STARTED |
| Transfers queue and persistence | Upload progress string only | M4 | pause/retry, 10-file queue, recover state | NOT STARTED |
| Folder transfer | Read-only folder hierarchy browsing and individual file download now coded; transferring whole folder as a batch remains pending | M4 | preserve tree and hashes | PARTIAL |
| Upload resume | Explicitly NOT implemented in transport | M4 | interrupted large upload resume without restart | NOT STARTED |
| Temporary share/expiry/one-time | Session secret until server stopped, no timed expiry | M4 | expired/revoked link rejected | NOT STARTED |
| FTP client | Not found | M5 | browse/send/receive to external FTP test server | NOT STARTED |
| FTP server | Not found | M5 | Windows FTP client uploads/downloads under SAF folder | NOT STARTED |
| SFTP client | Not found | M5 | SSH host-key verified transfer | NOT STARTED |
| SFTP server | Not found | M5 | authenticated Windows SFTP client transfer | NOT STARTED |
| DNS / TCP probe | Diagnostics.kt basic implementations | M6 | controlled host and error cases | PARTIAL |
| ICMP Ping and traceroute | Not found | M6 | accurate ICMP or explicitly restricted fallback | NOT STARTED |
| Local device listing and port checks | Not found | M6 | opt-in scoped local discovery/checks | NOT STARTED |
| LAN Speed Test | Not found | M6 | throughput measured across two devices | NOT STARTED |
| Wake-on-LAN | Not found | M6 | magic packet validated, supporting PC wakes | NOT STARTED |
| Local-only Hotspot | HotspotManager + user's live screenshot | M7 | start, connect client, transfer, stop | PARTIAL |
| Strongest feasible Wi-Fi band | Android controls band; cannot promise forced 5GHz | M7 | report observed capabilities and limitation honestly | PARTIAL |
| Background transfer | Foreground service present, unproven on device | M7 | notification controls, screen off/resume tests | NEEDS DEVICE CHECK |
| Final real-device test & APK | v0.6 Debug APK signed; poor actual UX | M8 | full matrix and approved release | NOT STARTED |

**Excluded forever:** Wi-Fi Analyzer and Remote Control. Neither belongs in a milestone or UI.

## Definition of status
- NOT STARTED = no production implementation found.
- INCOMPLETE/PARTIAL = some code or UI exists, insufficient user experience.
- NEEDS DEVICE CHECK = code seen, no acceptance evidence from real end-to-end workflow.
- BUILT = committed and CI passed, still lacking device proof.
- VERIFIED ON DEVICE = repeatable device/browser evidence for acceptance tests.

**Current gate:** M1 — implementation in progress, not device-accepted. M2/M7 groundwork from the user's newly added requirements is tracked separately; no downstream milestone is considered passed.

## New requirements cross-check (approved 2026-10-22)
| Explicit user request | Implemented code | Actual validation needed |
|---|---|---|
| Share chosen folder recursively, not one-by-one | `SharedDirectoryBrowser.kt`, `AndroidShareStorage.kt`, `NetKitCore.kt`, Compose folder picker and HTTPS `/browse` + `/browse-file/{id}` | Pad5 → Windows browser nested-tree traversal and file hash |
| Share complete internal shared storage | Special `MANAGE_EXTERNAL_STORAGE` permission opt-in, Android Settings flow, lazy scoped browse of shared root | Permission grant/revoke on Pad5, verify accessible and protected dirs |
| Hotspot fixed SSID/password | Encrypted saved preferences and public Android 36.1+ configured LocalOnlyHotspot path | Android 36.1-compatible real device (not Pad5 Android13) |
| 2.4/5 GHz choice | Auto/2.4/5 setting on Android 36.1+ with band support checks; clear fallback Android13 | Actual config/band on compatible hardware |
| Settings for currently existing functions | Appearance/effects, scope, receiver folder, allow uploads, upload size limits, hotspot configuration compatibility | Persist across relaunch and enforce runtime behaviors |
| New UI style | Adaptive home, theme-dependent ink/glass, animated liquid ribbons, crossfade screens | Real device screenshot comparison to approved Liquid Aurora reference |

**Remaining unfulfilled:** M1 full visual quality; folder batch ZIP transfer; Android-to-Android pairing/discovery; temporary share and true upload resume; FTP/SFTP client/server; ICMP/Traceroute/LAN Speed/Wake-on-LAN. They remain in the locked M0–M8 roadmap, not silently removed.

**Updated work status:** M0 complete; M1 IN PROGRESS; M2 folder/browser subset IN PROGRESS; M7 hotspot settings compatibility groundwork IN PROGRESS. Other gates not passed. No new final-release claim.
