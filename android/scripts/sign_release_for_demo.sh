#!/usr/bin/env bash
# Usage: sign_release_for_demo.sh
# Signs the UNSIGNED release APK with a throwaway demo key so it can be installed and the release-only
# behaviour (FLAG_SECURE: no screenshots) checked on a phone. Not a release process: the key is a demo
# key and the build has no signing config on purpose.
#
#   input   android/app/build/outputs/apk/release/app-release-unsigned.apk   (./gradlew :app:assembleRelease)
#   output  android/app/build/outputs/apk/release/app-release-demo.apk
#   key     ~/dev/imzo-demo.keystore (override with IMZO_KEYSTORE), created on first use, never in the repo
#
# The keystore password comes from the environment variable IMZO_KS_PASS, or is asked for without echo.
# It is passed to keytool and apksigner through the environment, never on a command line, and never printed.
# Afterwards check_permissions.sh runs on the signed APK (exit status is that of the check).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(dirname "$HERE")"
IN="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
OUT="$ANDROID_DIR/app/build/outputs/apk/release/app-release-demo.apk"
KEYSTORE="${IMZO_KEYSTORE:-$HOME/dev/imzo-demo.keystore}"
ALIAS="imzo-demo"

[ -f "$IN" ] || { echo "sign_release_for_demo: $IN not found; run ./gradlew :app:assembleRelease first" >&2; exit 2; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "sign_release_for_demo: set ANDROID_HOME (source ~/dev/android-env.sh)" >&2; exit 2; }
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)"
BT="${BT%/}"
for tool in zipalign apksigner; do
  [ -x "$BT/$tool" ] || { echo "sign_release_for_demo: $tool not found in ${BT:-$SDK/build-tools}" >&2; exit 2; }
done
command -v keytool >/dev/null || { echo "sign_release_for_demo: keytool not found (JAVA_HOME?)" >&2; exit 2; }

if [ -z "${IMZO_KS_PASS:-}" ]; then
  [ -t 0 ] || { echo "sign_release_for_demo: set IMZO_KS_PASS (no terminal to ask on)" >&2; exit 2; }
  read -r -s -p "Keystore password (at least 6 characters): " IMZO_KS_PASS
  echo >&2
fi
[ "${#IMZO_KS_PASS}" -ge 6 ] || { echo "sign_release_for_demo: the password must have at least 6 characters" >&2; exit 2; }
export IMZO_KS_PASS

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  echo "sign_release_for_demo: creating throwaway keystore $KEYSTORE" >&2
  keytool -genkeypair -storetype PKCS12 -keystore "$KEYSTORE" -alias "$ALIAS" \
    -keyalg EC -groupname secp256r1 -validity 3650 -dname "CN=Imzo demo, O=Pazuzu" \
    -storepass:env IMZO_KS_PASS -keypass:env IMZO_KS_PASS >/dev/null
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$BT/zipalign" -f -p 4 "$IN" "$TMP/aligned.apk"
rm -f "$OUT" "$OUT.idsig"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass env:IMZO_KS_PASS --key-pass env:IMZO_KS_PASS --out "$OUT" "$TMP/aligned.apk"
"$BT/apksigner" verify "$OUT"
echo "sign_release_for_demo: signed $OUT"

exec bash "$HERE/check_permissions.sh" "$OUT"
