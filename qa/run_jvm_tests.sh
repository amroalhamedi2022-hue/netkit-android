#!/usr/bin/env bash
# Pure Kotlin/JVM transport checks; NEVER bundles a test TLS private key with the app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
command -v kotlinc >/dev/null || { echo 'Kotlin JVM compiler required'; exit 1; }
command -v keytool >/dev/null || { echo 'JDK keytool required'; exit 1; }
keytool -genkeypair -alias test -keyalg RSA -keysize 2048 -validity 2 \
  -keystore "$TEMP_DIR/test.p12" -storetype PKCS12 -storepass temporary-test-only \
  -keypass temporary-test-only -dname 'CN=NetKit QA, O=Tests' -noprompt >/dev/null 2>&1
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/TransferHttpEngine.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/UploadPathPolicy.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/DirectoryArchive.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/SessionLease.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/ResumableBrowserUpload.kt" \
  "$ROOT/qa/CoreSmokeTest.kt" -include-runtime -d "$TEMP_DIR/transport.jar"
java -jar "$TEMP_DIR/transport.jar" "$TEMP_DIR/test.p12"

# The SAF ZIP regression must run in the default QA gate, not only by hand.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/DirectoryArchive.kt" \
  "$ROOT/qa/DirectoryArchiveTest.kt" -include-runtime -d "$TEMP_DIR/archive.jar"
java -jar "$TEMP_DIR/archive.jar"

# Portable domain tests use real Kotlin compilation, no Android SDK or emulator assumed.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/SessionLease.kt" \
  "$ROOT/qa/SessionLeaseTest.kt" -include-runtime -d "$TEMP_DIR/lease.jar"
java -jar "$TEMP_DIR/lease.jar"
kotlinc "$ROOT/app/src/main/java/com/netkit/local/transfer/TransferTelemetry.kt" \
  "$ROOT/qa/TransferTelemetryTest.kt" -include-runtime -d "$TEMP_DIR/telemetry.jar"
java -jar "$TEMP_DIR/telemetry.jar"
kotlinc "$ROOT/app/src/main/java/com/netkit/local/remote/ProgressStreams.kt" \
  "$ROOT/qa/ProgressStreamsTest.kt" -include-runtime -d "$TEMP_DIR/streams.jar"
java -jar "$TEMP_DIR/streams.jar"
kotlinc "$ROOT/app/src/main/java/com/netkit/local/transfer/ProgressCheckpoint.kt" \
  "$ROOT/qa/ProgressCheckpointTest.kt" -include-runtime -d "$TEMP_DIR/checkpoint.jar"
java -jar "$TEMP_DIR/checkpoint.jar"
# Reject public/multicast NSD advertisements; IPv4 RFC1918 and IPv6 ULA/link-local only.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/connectivity/PeerAddressPolicy.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/connectivity/PeerDisplayNamePolicy.kt" \
  "$ROOT/qa/PeerAddressPolicyTest.kt" -include-runtime -d "$TEMP_DIR/nsd-address-policy.jar"
java -jar "$TEMP_DIR/nsd-address-policy.jar"
# Browsing an entire storage tree must not grow remembered SAF handles forever.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/ShareHandleRegistry.kt" \
  "$ROOT/qa/ShareHandleRegistryTest.kt" -include-runtime -d "$TEMP_DIR/share-handles.jar"
java -jar "$TEMP_DIR/share-handles.jar"
# Parallel SAF browser uploads must not hold the name lock for the whole stream.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/UploadNameReservations.kt" \
  "$ROOT/qa/UploadNameReservationsTest.kt" -include-runtime -d "$TEMP_DIR/upload-names.jar"
java -jar "$TEMP_DIR/upload-names.jar"
# Folder upload paths must remain scoped to SAF grants and independent name reservations.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/UploadPathPolicy.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/UploadNameReservations.kt" \
  "$ROOT/qa/UploadPathPolicyTest.kt" -include-runtime -d "$TEMP_DIR/upload-paths.jar"
java -jar "$TEMP_DIR/upload-paths.jar"
# Consent must remain revoked even if settings are immediately restored.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/UploadConsentGate.kt" \
  "$ROOT/qa/UploadConsentGateTest.kt" -include-runtime -d "$TEMP_DIR/upload-consent.jar"
java -jar "$TEMP_DIR/upload-consent.jar"
# Real Wake-on-LAN UDP packet generation and LAN-only destination validation.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/tools/WakeOnLan.kt" \
  "$ROOT/qa/WakeOnLanTest.kt" -include-runtime -d "$TEMP_DIR/wol.jar"
java -jar "$TEMP_DIR/wol.jar"
# Validate every FTP/SFTP path before opening SAF streams or sending FTP commands.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/remote/RemotePathPolicy.kt" \
  "$ROOT/qa/RemotePathPolicyTest.kt" -include-runtime -d "$TEMP_DIR/remote-path-policy.jar"
java -jar "$TEMP_DIR/remote-path-policy.jar"
# Catch silent FTP/SFTP truncation when the remote server reports a file size.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/remote/TransferLengthVerifier.kt" \
  "$ROOT/qa/TransferLengthVerifierTest.kt" -include-runtime -d "$TEMP_DIR/remote-length.jar"
java -jar "$TEMP_DIR/remote-length.jar"
# Optional FTP/SFTP upload readback integrity: compare SHA-256 without
# buffering files, reject changed bytes and bounded stalled network streams.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/remote/RemoteDigestVerifier.kt" \
  "$ROOT/qa/RemoteDigestVerifierTest.kt" -include-runtime -d "$TEMP_DIR/remote-digest.jar"
java -jar "$TEMP_DIR/remote-digest.jar"
# Explicit FTP/SFTP resume requires byte-identical remote prefix and full readback.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/remote/VerifiedResumePrefix.kt" \
  "$ROOT/qa/VerifiedResumePrefixTest.kt" -include-runtime -d "$TEMP_DIR/verified-resume.jar"
java -jar "$TEMP_DIR/verified-resume.jar"
# FTP servers without MLST may expose SIZE; never trust malformed/overflow replies.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/remote/FtpSizePolicy.kt" \
  "$ROOT/qa/FtpSizePolicyTest.kt" -include-runtime -d "$TEMP_DIR/ftp-size.jar"
java -jar "$TEMP_DIR/ftp-size.jar"
# Download resume must not publish partial SAF data and must verify both remote and local hashes.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/remote/VerifiedDownloadStaging.kt" \
  "$ROOT/qa/VerifiedDownloadStagingTest.kt" -include-runtime -d "$TEMP_DIR/verified-download.jar"
java -jar "$TEMP_DIR/verified-download.jar"

# HTTPS browser uploads must resume only from verified disk offsets and never
# resurrect staged files after SAF consent changes or share expiry.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/TransferHttpEngine.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/UploadPathPolicy.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/DirectoryArchive.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/SessionLease.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/ResumableBrowserUpload.kt" \
  "$ROOT/qa/ResumableBrowserUploadTest.kt" -include-runtime -d "$TEMP_DIR/browser-resume.jar"
java -jar "$TEMP_DIR/browser-resume.jar"

echo 'NetKit JVM transport & reliability QA passed.'

# DNS-SD must accept only the exact NetKit service type and bound/deduplicate async resolves.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/connectivity/NsdDiscoveryPolicy.kt" "$ROOT/app/src/main/java/com/netkit/local/connectivity/PeerDisplayNamePolicy.kt" "$ROOT/qa/NsdDiscoveryPolicyTest.kt" -include-runtime -d "$TEMP_DIR/nsd-discovery-policy.jar"
java -jar "$TEMP_DIR/nsd-discovery-policy.jar"
# Late Android DNS-SD registration callbacks must not revive a stopped or superseded advertisement.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/connectivity/AdvertisementEpoch.kt" "$ROOT/qa/AdvertisementEpochTest.kt" -include-runtime -d "$TEMP_DIR/nsd-advertisement-epoch.jar"
java -jar "$TEMP_DIR/nsd-advertisement-epoch.jar"
# Android NSD discovery owns a short-lived multicast lock; test its lease lifecycle without a device.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/connectivity/MulticastLockLease.kt" "$ROOT/qa/MulticastLockLeaseTest.kt" -include-runtime -d "$TEMP_DIR/multicast-lock.jar"
java -jar "$TEMP_DIR/multicast-lock.jar"
# Serialize NSD service-lost/stop callbacks with asynchronous peer publication.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/connectivity/DiscoveryStateGate.kt" "$ROOT/qa/DiscoveryStateGateTest.kt" -include-runtime -d "$TEMP_DIR/nsd-state-gate.jar"
java -jar "$TEMP_DIR/nsd-state-gate.jar"

