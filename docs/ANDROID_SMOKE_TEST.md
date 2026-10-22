# On-device checklist (first compile)

## Xiaomi Pad 5 — Android / HyperOS

- [ ] Gradle sync + install Debug APK (capture any errors verbatim)
- [ ] Home shows actual Wi-Fi status and correct local IPv4
- [ ] Grant Nearby Wi-Fi permission only when tapping Start Hotspot
- [ ] Start local-only Hotspot; verify SSID & passphrase appear, connect Windows
- [ ] Verify both devices are on same LAN/hotspot and Windows can access displayed IP
- [ ] Check hotspot band *from the system/hardware tooling*, not inferred from client 5GHz capability
- [ ] Pick one file for download, select destination folder, start Browser Share
- [ ] On Windows, verify HTTPS cert fingerprint before browser exception, download and compare file hash
- [ ] Upload ~100 MB then 1 GB file from browser, check hashes and available storage
- [ ] Stop server, confirm old link fails, start new session and confirm token changed
- [ ] Stop Hotspot; verify no listener remains and no connectivity regression
- [ ] Interrupt file upload; verify temp file cleanup under SAF provider
- [ ] Lock screen/background app; document current limitations before adding foreground service

Never test real personal or sensitive files until basic TLS and local-only binding are confirmed on device.
