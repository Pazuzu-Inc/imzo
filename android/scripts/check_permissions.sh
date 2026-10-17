#!/usr/bin/env bash
# Usage: check_permissions.sh <apk>
# Fails closed: exits non-zero unless the APK's merged manifest requests/declares ONLY allowlisted
# permissions and the dump was understood completely. The approver is camera + biometric only: in
# particular it must never hold INTERNET.
#   exit 0  all permissions allowlisted
#   exit 1  a permission outside the allowlist
#   exit 2  cannot tell (no aapt2, empty/unparseable dump, unparsed permission line, CAMERA missing)
# AAPT2 may point at the aapt2 binary (tests use a stub); otherwise the newest one under
# $ANDROID_HOME/build-tools is used.
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

DUMP="$("$AAPT2" dump permissions "$APK")" || { echo "check_permissions: aapt2 failed" >&2; exit 2; }
[ -n "${DUMP//[[:space:]]/}" ] || { echo "check_permissions: FAILED - empty aapt2 output" >&2; exit 2; }

USES_RE="^[[:space:]]*uses-permission[A-Za-z0-9_-]*:[[:space:]]*name='([^']+)'"
DECL_RE="^[[:space:]]*permission:[[:space:]]*([^[:space:]']+)"
PKG_RE="^[[:space:]]*package:"

FOUND=()
unparsed=0
while IFS= read -r line; do
  if [[ "$line" =~ $USES_RE ]] || [[ "$line" =~ $DECL_RE ]]; then
    FOUND+=("${BASH_REMATCH[1]}")
  elif [[ ! "$line" =~ $PKG_RE ]] && [[ "$line" == *[Pp][Ee][Rr][Mm][Ii][Ss][Ss][Ii][Oo][Nn]* ]]; then
    echo "  UNPARSED:  $line" >&2
    unparsed=1
  fi
done <<< "$DUMP"

echo "check_permissions: $APK"
if [ "$unparsed" = 1 ]; then
  echo "check_permissions: FAILED - a permission line in the aapt2 output was not understood" >&2
  exit 2
fi

has_camera=0
for p in ${FOUND[@]+"${FOUND[@]}"}; do [ "$p" = "android.permission.CAMERA" ] && has_camera=1; done
if [ "$has_camera" = 0 ]; then
  echo "check_permissions: FAILED - CAMERA not found, so the aapt2 output was not parsed correctly" >&2
  exit 2
fi

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
done < <(printf '%s\n' "${FOUND[@]}" | sort -u)

if [ "$bad" = 1 ]; then
  echo "check_permissions: FAILED - the APK requests a permission that is not on the allowlist" >&2
  exit 1
fi
echo "check_permissions: OK"
