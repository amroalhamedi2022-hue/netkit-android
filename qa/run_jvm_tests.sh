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
  "$ROOT/app/src/main/java/com/netkit/local/share/DirectoryArchive.kt" \
  "$ROOT/app/src/main/java/com/netkit/local/share/SessionLease.kt" \
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
# Consent must remain revoked even if settings are immediately restored.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/UploadConsentGate.kt" \
  "$ROOT/qa/UploadConsentGateTest.kt" -include-runtime -d "$TEMP_DIR/upload-consent.jar"
java -jar "$TEMP_DIR/upload-consent.jar"
# Real Wake-on-LAN UDP packet generation and LAN-only destination validation.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/tools/WakeOnLan.kt" \
  "$ROOT/qa/WakeOnLanTest.kt" -include-runtime -d "$TEMP_DIR/wol.jar"
java -jar "$TEMP_DIR/wol.jar"
echo 'NetKit JVM transport & reliability QA passed.'
