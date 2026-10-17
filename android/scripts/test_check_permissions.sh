#!/usr/bin/env bash
# Drives check_permissions.sh with stub aapt2 outputs and asserts its exit codes.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
touch "$TMP/fake.apk"
cat > "$TMP/aapt2" <<'STUB'
#!/usr/bin/env bash
cat "$STUB_OUT"
STUB
chmod +x "$TMP/aapt2"

VALID="package: inc.pazuzu.imzo
uses-permission: name='android.permission.CAMERA'
uses-permission: name='android.permission.USE_BIOMETRIC'
permission: inc.pazuzu.imzo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='inc.pazuzu.imzo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'"

fails=0
expect() { # name expected_exit dump
  printf '%s\n' "$3" > "$TMP/out"
  [ -z "$3" ] && : > "$TMP/out"
  AAPT2="$TMP/aapt2" STUB_OUT="$TMP/out" "$HERE/check_permissions.sh" "$TMP/fake.apk" >/dev/null 2>&1
  got=$?
  if [ "$got" = "$2" ]; then echo "ok   $1 (exit $got)"; else echo "FAIL $1: expected exit $2, got $got"; fails=1; fi
}

expect "valid"                          0 "$VALID"
expect "valid, indented"                0 "$(printf '%s\n' "$VALID" | sed 's/^/  /')"
expect "empty output"                   2 ""
expect "whitespace only"                2 "   "
expect "garbage, no permissions"       2 "something unexpected"
expect "INTERNET"                       1 "$VALID
uses-permission: name='android.permission.INTERNET'"
expect "indented INTERNET"              1 "$VALID
  uses-permission: name='android.permission.INTERNET'"
expect "ACCESS_NETWORK_STATE"           1 "$VALID
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'"
expect "uses-permission-sdk-23 INTERNET" 1 "$VALID
uses-permission-sdk-23: name='android.permission.INTERNET'"
expect "unknown extra permission"       1 "$VALID
uses-permission: name='com.example.SOMETHING'"
expect "INTERNET with maxSdkVersion"    1 "$VALID
uses-permission: name='android.permission.INTERNET' maxSdkVersion='28'"
expect "unparseable permission line"    2 "$VALID
uses-permission: android.permission.INTERNET"
expect "CAMERA missing"                 2 "package: inc.pazuzu.imzo
uses-permission: name='android.permission.USE_BIOMETRIC'"
expect "declared foreign permission"    1 "$VALID
permission: com.example.EVIL"

[ "$fails" = 0 ] && echo "all guard tests passed" || { echo "guard tests FAILED"; exit 1; }
