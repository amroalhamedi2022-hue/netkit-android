# NetKit v0.3 — Xiaomi Pad 5 smoke checks

**No native APK yet.** Only run this after a successful Android Studio build.

1. **Launch & appearance:** cycle Aurora / Ocean / Nebula, toggle floating bubbles, close app and reopen. Confirm theme persists. Rotate Pad 5 twice; verify selected theme is retained.
2. **Hotspot:** under the Hotspot tab, allow Nearby Wi-Fi Devices. Start local-only Hotspot; confirm SSID/password displayed and laptop can join. This hotspot has no internet. On Android 13, frequency normally is not publicly readable by an ordinary app; never assume 5GHz.
3. **Share:** choose small files (read-only) and a receiving folder. Start HTTPS sharing. Copy URL from a reachable local IP in the Share tab. On laptop, compare displayed SHA-256 certificate fingerprint to certificate details before accepting browser warning.
4. **Download:** open Browser Share from laptop and download a file to laptop. Check exact byte count and hash; do not test with important files first.
5. **Upload:** select files via browser. Observe bytes, % and speed. Success should be reported only after Android saves the file. Compare bytes/hash with laptop source.
6. **Cancel:** upload a 100MB+ disposable test file, press Cancel partway, verify Android does not retain a completed corrupted file. Folder may briefly contain temporary `.netkit-*` objects while upload is in progress.
7. **Rotation:** start disposable upload and rotate tablet; sharing should continue while Activity is re-created. Do **not** assume screen-off/background transfer works yet.
8. **Stop:** stop server while upload is in progress. Laptop should report network cancellation, and restarting server must give a new random session URL token.
9. **No permissions:** refuse Hotspot permission; message should be clear, and existing normal Wi-Fi Browser Share should remain usable.
10. **Network diagnostics:** DNS and TCP check are implemented; true ICMP ping, traceroute, SFTP are not implemented in this alpha.

If the local hotspot runs only at 2.4GHz, test Android's built-in hotspot with 5GHz set via System Settings (if available) and connect both devices to that network; the app should still share over the same LAN. Note: this does not grant the app 5GHz radio control.
