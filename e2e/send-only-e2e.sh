#!/usr/bin/env bash
# CARD-25: the send-only build on the emulator against the stub. Called by emulator-e2e.yml from
# inside reactivecircus/android-emulator-runner.
#
#   bash e2e/send-only-e2e.sh
#
# Needs: a booted emulator, node, the sendOnlyE2e APK + androidTest APK built with
# -Pe2eBuildType=sendOnlyE2e (same ".e2e" package as the two-way e2e build; this job installs
# only the send-only one). Asserts: the four permissions are not declared or granted
# (dumpsys package), sign-up with a hand-typed code and one test send work through the stub,
# and the app does not crash. Output goes to $E2E_OUT (default e2e-out/).
set -uo pipefail

OUT=${E2E_OUT:-e2e-out}
# Not E2E_APK / E2E_TEST_APK: the workflow sets those to the TWO-WAY e2e build.
APK=${SEND_ONLY_APK:-dist-e2e/app-sendOnlyE2e.apk}
TEST_APK=${SEND_ONLY_TEST_APK:-dist-e2e/app-sendOnlyE2e-androidTest.apk}
case "$APK" in *sendOnly*) ;; *) echo "refusing: $APK is not the send-only APK"; exit 1 ;; esac
PKG=com.mnaseb.sim.e2e
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
FORBIDDEN="android.permission.RECEIVE_SMS android.permission.READ_SMS android.permission.RECEIVE_MMS android.permission.RECEIVE_WAP_PUSH"
mkdir -p "$OUT"

cleanup() {
  [ -f "$OUT/stub.pid" ] && kill "$(cat "$OUT/stub.pid")" 2>/dev/null
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
  adb exec-out screencap -p > "$OUT/final.png" 2>/dev/null
}
trap cleanup EXIT

node e2e/stub-server/selftest.js || { echo "stub selftest failed"; exit 1; }
node e2e/stub-server/server.js 8787 > "$OUT/stub.log" 2>&1 &
echo $! > "$OUT/stub.pid"
for _ in $(seq 1 30); do
  curl -fsS http://127.0.0.1:8787/__control/health >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS http://127.0.0.1:8787/__control/health >/dev/null || { echo "stub did not start"; exit 1; }

adb install -r "$APK" || exit 1
adb install -r "$TEST_APK" || exit 1

fail=0
# 1. dumpsys: none of the four permissions is requested, none is granted; SEND_SMS is requested.
adb shell dumpsys package "$PKG" > "$OUT/dumpsys-package.txt" 2>&1
grep -q "SEND_SMS" "$OUT/dumpsys-package.txt" || { echo "FAIL  SEND_SMS not declared (dumpsys control)"; fail=1; }
for p in $FORBIDDEN; do
  if grep -F -q "$p" "$OUT/dumpsys-package.txt"; then echo "FAIL  $p appears in dumpsys package"; fail=1
  else echo "PASS  $p is not declared or granted"; fi
done

# 2. the instrumented classes, one `am instrument` each (same pass rule as run-e2e.sh)
P=me.capcom.smsgateway.e2e
RAN=0
: > "$OUT/instrumented-output.txt"
for c in "$P.E2eSendOnlyTest" "$P.E2eSmokeTest" "$P.E2eSignUpTest" "$P.E2eGatewayTest#t03_oneTapOnSendTestMessageSendsExactlyOneSms"; do
  echo "=== $c" | tee -a "$OUT/instrumented-output.txt"
  adb shell am instrument -w -e class "$c" "$RUNNER" | tee "$OUT/one.txt" >> "$OUT/instrumented-output.txt"
  if grep -qE "OK \([1-9][0-9]* tests?\)" "$OUT/one.txt" && ! grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=" "$OUT/one.txt"; then
    RAN=$((RAN + 1))
  else
    fail=1
  fi
done
curl -fsS http://127.0.0.1:8787/__control/state > "$OUT/stub-state.json" 2>/dev/null || true

# 3. the app did not crash
adb logcat -d > "$OUT/logcat-check.txt" 2>/dev/null
if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-check.txt" | grep -q .; then
  if grep -A3 "FATAL EXCEPTION" "$OUT/logcat-check.txt" | grep -q "Process: $PKG"; then
    echo "FAIL  the app crashed"; fail=1
  fi
fi

if [ "$fail" -ne 0 ] || [ "$RAN" -ne 4 ]; then
  echo "send-only e2e FAILED (classes OK: $RAN of 4)"
  exit 1
fi
echo "send-only e2e OK"
