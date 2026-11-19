#!/usr/bin/env bash
# CARD-25 release guard (fails closed): the send-only APK must declare none of RECEIVE_SMS,
# READ_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH and register no incoming-SMS receiver; the two-way
# release APK must still declare all four (the positive control that proves the check can see them).
#
#   bash e2e/send-only-guard.sh <two-way release.apk> <send-only.apk>
#
# Reads the compiled manifest of each APK with aapt2 (Android SDK build-tools).
set -uo pipefail

TWO_WAY=${1:?two-way release apk}
SEND_ONLY=${2:?send-only apk}
FORBIDDEN="android.permission.RECEIVE_SMS android.permission.READ_SMS android.permission.RECEIVE_MMS android.permission.RECEIVE_WAP_PUSH"
RECEIVERS="modules.receiver.MessagesReceiver modules.receiver.MmsReceiver"

[ -s "$TWO_WAY" ] || { echo "::error::two-way apk missing or empty: $TWO_WAY"; exit 1; }
[ -s "$SEND_ONLY" ] || { echo "::error::send-only apk missing or empty: $SEND_ONLY"; exit 1; }

AAPT2=${AAPT2:-}
if [ -z "$AAPT2" ]; then
  AAPT2=$(ls -1 "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)
fi
[ -x "${AAPT2:-/nonexistent}" ] || { echo "::error::aapt2 not found (set AAPT2)"; exit 1; }

TW=$(mktemp); SO=$(mktemp)
"$AAPT2" dump xmltree --file AndroidManifest.xml "$TWO_WAY" > "$TW" 2>&1 || { echo "::error::cannot read the two-way manifest"; exit 1; }
"$AAPT2" dump xmltree --file AndroidManifest.xml "$SEND_ONLY" > "$SO" 2>&1 || { echo "::error::cannot read the send-only manifest"; exit 1; }
[ -s "$TW" ] && [ -s "$SO" ] || { echo "::error::empty manifest dump"; exit 1; }

fail=0
check() { if [ "$2" -eq 0 ]; then echo "PASS  $1"; else echo "FAIL  $1"; fail=1; fi; }
has() { grep -F -q -- "$2" "$1"; }

# controls first: the two-way release is unchanged and the dump can see what it should see
has "$TW" "android.permission.SEND_SMS"; check "two-way release declares SEND_SMS (control)" $?
for p in $FORBIDDEN; do
  has "$TW" "$p"; check "two-way release still declares ${p#android.permission.}" $?
done
for r in $RECEIVERS; do
  has "$TW" "$r"; check "two-way release still registers ${r#modules.receiver.}" $?
done

# the CARD-25 rule
has "$SO" "android.permission.SEND_SMS"; check "send-only declares SEND_SMS (number proof, delivery reports)" $?
for p in $FORBIDDEN; do
  ! has "$SO" "$p"; check "send-only does NOT declare ${p#android.permission.}" $?
done
for r in $RECEIVERS; do
  ! has "$SO" "$r"; check "send-only does NOT register ${r#modules.receiver.}" $?
done

if [ "$fail" -ne 0 ]; then
  echo "::error::send-only guard failed (closed): the send-only build must not carry incoming-SMS permissions or receivers"
  exit 1
fi
echo "send-only guard OK"
