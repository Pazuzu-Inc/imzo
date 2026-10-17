#!/usr/bin/env bash
# Usage: check_permissions.sh <apk>
# Exits non-zero if the APK's merged manifest requests (or declares) any permission outside the
# allowlist. The approver is camera + biometric only: in particular it must never hold INTERNET.
# AAPT2 may point at the aapt2 binary; otherwise the newest one under $ANDROID_HOME/build-tools is used.
set -euo pipefail

APK="${1:?usage: check_permissions.sh <apk>}"
[ -f "$APK" ] || { echo "check_permissions: no such APK: $APK" >&2; exit 2; }

if [ -z "${AAPT2:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$SDK" ] || { echo "check_permissions: set AAPT2 or ANDROID_HOME" >&2; exit 2; }
  AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)"
fi
[ -x "$AAPT2" ] || { echo "check_permissions: aapt2 not found" >&2; exit 2; }

ALLOWED=(
  "android.permission.CAMERA"
  "android.permission.USE_BIOMETRIC"
  # added by androidx.core for dynamically registered receivers; app-internal, signature-level
  "inc.pazuzu.imzo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)

DUMP="$("$AAPT2" dump permissions "$APK")"
# every "uses-permission[-sdk-23|...]: name='X'" and declared "permission: X" line
FOUND="$(printf '%s\n' "$DUMP" | sed -nE "s/^(uses-permission[^:]*|permission): *(name=')?([^' ]+)'?.*/\3/p" | sort -u)"

echo "check_permissions: $APK"
bad=0
while IFS= read -r p; do
  [ -n "$p" ] || continue
  ok=0
  for a in "${ALLOWED[@]}"; do [ "$p" = "$a" ] && ok=1; done
  if [ "$ok" = 1 ]; then
    echo "  allowed:   $p"
  else
    echo "  FORBIDDEN: $p" >&2
    bad=1
  fi
done <<< "$FOUND"

if [ "$bad" = 1 ]; then
  echo "check_permissions: FAILED - the APK requests a permission that is not on the allowlist" >&2
  exit 1
fi
echo "check_permissions: OK"
