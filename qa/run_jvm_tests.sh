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
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/UploadDestinationPolicy.kt" \
  "$ROOT/qa/UploadDestinationPolicyTest.kt" -include-runtime -d "$TEMP_DIR/upload-destination.jar"
java -jar "$TEMP_DIR/upload-destination.jar"

kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/FailedUploadGrantRollback.kt" \
  "$ROOT/qa/FailedUploadGrantRollbackTest.kt" -include-runtime -d "$TEMP_DIR/upload-grant-rollback.jar"
java -jar "$TEMP_DIR/upload-grant-rollback.jar"

# NSD callback generation gate is pure Kotlin so stale-result lifecycle can be tested without an emulator.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/connectivity/DiscoveryEpoch.kt" \
  "$ROOT/qa/DiscoveryEpochTest.kt" -include-runtime -d "$TEMP_DIR/discovery-epoch.jar"
java -jar "$TEMP_DIR/discovery-epoch.jar"

# SAF publication must roll back the provider's returned URI if the session expires during rename.
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/GuardedPublication.kt" \
  "$ROOT/qa/GuardedPublicationTest.kt" -include-runtime -d "$TEMP_DIR/guarded-publication.jar"
java -jar "$TEMP_DIR/guarded-publication.jar"
kotlinc "$ROOT/app/src/main/java/com/netkit/local/share/FailedUploadStageCleanup.kt" \
  "$ROOT/qa/FailedUploadStageCleanupTest.kt" -include-runtime -d "$TEMP_DIR/upload-stage-cleanup.jar"
java -jar "$TEMP_DIR/upload-stage-cleanup.jar"

echo 'NetKit JVM transport & reliability QA passed.'
