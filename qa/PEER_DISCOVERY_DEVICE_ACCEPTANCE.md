# M83 — targeted DNS-SD device acceptance

**State:** test plan prepared; not a release. The one-off APK is built only to verify the new discovery lifecycle on real hardware.

## Why a targeted APK is justified

The current change filters DNS-SD advertisements to the exact NetKit service type and local-only address scopes, uses the existing bounded NsdResolveLedger, invalidates stale registration callbacks with AdvertisementEpoch, and scopes the Android multicast lock to the discovery lifecycle. JVM tests and Android compilation cannot prove the OEM Wi-Fi/NSD behavior on Xiaomi Pad 5, so a single debug artifact is needed for a physical acceptance test.

The test variant uses application ID 'com.netkit.local.devicetest', so it can be installed beside the ordinary 'com.netkit.local' app without uninstalling or replacing user data. It is not a release or a trusted-pairing implementation.

## Test setup

- Xiaomi Pad 5 / HyperOS as the primary device.
- A second Android device with the same targeted test APK.
- Both devices on the same private Wi-Fi network; record whether AP client isolation or VPN is enabled.
- Do not expose the HTTPS share URL, session token, hotspot password, or screenshots containing them.

## Acceptance procedure

1. Install the targeted APK on both devices; verify the package is 'com.netkit.local.devicetest'.
2. Start a local HTTPS share on one device, then open Discovery on the other.
3. Confirm the advertised service is '_netkit._tcp' and the resolved endpoint is a local IPv4 private/link-local or IPv6 ULA/link-local address. Public/global addresses must never appear as discovered peers.
4. Stop the share and verify the stale peer disappears after the NSD loss event. Navigate away from Discovery, reopen it, and confirm discovery can restart.
5. Repeat start/stop and screen navigation at least 10 times. Verify old callbacks do not repopulate a stopped scan and the app does not crash.
6. Repeat on a local-only hotspot only if Android/HyperOS permits it; do not assume a requested band can be forced.
7. Record whether discovery works with internet access disabled. No direct file transfer, QR pairing, or authenticated trust should be claimed from this test.

## Evidence record

| Field | Result |
|---|---|
| Device model / Android build | NOT TESTED |
| Network type / subnet | NOT TESTED |
| Peer discovered on same LAN | NOT TESTED |
| Public/global peer rejected | NOT TESTED |
| Lost peer removed | NOT TESTED |
| Stop/restart stale callback test | NOT TESTED |
| Multicast lock lifecycle / battery observation | NOT TESTED |
| Screenshots/logs with secrets redacted | NOT TESTED |

After the physical run, replace only the relevant evidence cells with PASS/FAIL and attach redacted logs. CI success is not device acceptance.
