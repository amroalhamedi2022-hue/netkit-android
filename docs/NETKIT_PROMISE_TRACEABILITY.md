# NetKit — PROMISE TO EVIDENCE TRACKER
| Promise / feature | Existing baseline evidence | Milestone | Acceptance proof | Status |
|---|---|---|---|---|
| Tablet-first liquid visual design | Basic single file Compose, 4 floating dots, poor contrast shown on device | M1 | Pad 5 portrait/landscape screenshots + motion capture + accessibility | NOT STARTED |
| Aurora Liquid theme | Accent palette only | M1 | Complete dark styling across all pages | NOT STARTED |
| Aqua Glass theme | Palette selector only | M1 | Readable light variant across all pages | NOT STARTED |
| Nebula Flow theme | Palette selector only | M1 | Complete coral/violet variant across all pages | NOT STARTED |
| Browser Transfer download | Core HTTPS GET, SAF, one-range GET | M2 | Android→Windows browser transfer, SHA matching | NEEDS DEVICE CHECK |
| Browser Transfer upload | Core POST, SAF destination, digest | M2 | Windows browser→Android transfer, SHA matching | NEEDS DEVICE CHECK |
| HTTPS browser desktop interface | Basic generated page with self-signed cert | M2 | Responsive upload/download/progress+consent flow | INCOMPLETE |
| Share by QR | Not found in examined UI | M2/M3 | Scan QR with Windows/Android and connect | NOT STARTED |
| Native device discovery | Not found | M3 | 2 Android devices find each other on LAN/hotspot | NOT STARTED |
| Secure pairing | Not found | M3 | approve/reject/unpair and reconnect | NOT STARTED |
| Manual-IP fallback | Text URLs exist but no peer connect UI | M3 | Pair/connect by reachable address | NOT STARTED |
| Android ↔ Android direct send | Browser upload/download only | M3 | Actual two-way files and URLs | NOT STARTED |
| Transfers queue and persistence | Upload progress string only | M4 | pause/retry, 10-file queue, recover state | NOT STARTED |
| Folder transfer | Only individual SAF files | M4 | preserve tree and hashes | NOT STARTED |
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

**Current gate:** M0 — source audit established, contracts written, next implement M1 after M0 closeout.
