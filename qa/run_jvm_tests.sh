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
  "$ROOT/qa/CoreSmokeTest.kt" -include-runtime -d "$TEMP_DIR/transport.jar"
java -jar "$TEMP_DIR/transport.jar" "$TEMP_DIR/test.p12"
echo 'NetKit JVM transport QA passed.'
