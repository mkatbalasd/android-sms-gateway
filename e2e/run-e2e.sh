#!/usr/bin/env bash
# Runs the emulator e2e suite against the stub. Called by emulator-e2e.yml from inside
# reactivecircus/android-emulator-runner (one `script:` line = one process, so the logic lives here).
#
#   bash e2e/run-e2e.sh smoke|full
#
# Needs: a booted emulator, node, the emulatorE2e APK + androidTest APK built with
# -Pe2eBuildType=emulatorE2e. Output goes to $E2E_OUT (default e2e-out/).
set -uo pipefail

MODE=${1:?smoke|full}
OUT=${E2E_OUT:-e2e-out}
APK=${E2E_APK:-app/build/outputs/apk/emulatorE2e/app-emulatorE2e.apk}
TEST_APK=${E2E_TEST_APK:-app/build/outputs/apk/androidTest/emulatorE2e/app-emulatorE2e-androidTest.apk}
RUNNER=com.mnaseb.sim.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$OUT"

case "$MODE" in
  smoke) ANNOTATION=me.capcom.smsgateway.e2e.E2eSmoke ;;
  full)  ANNOTATION=me.capcom.smsgateway.e2e.E2eFull ;;
  *) echo "mode must be smoke or full"; exit 2 ;;
esac

cleanup() {
  [ -f "$OUT/watcher.pid" ] && kill "$(cat "$OUT/watcher.pid")" 2>/dev/null
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

# Inbound SMS: the stub queues a request, this watcher fires it through the emulator console.
if [ "$MODE" = full ]; then
  bash e2e/sms-watcher.sh "$OUT/sms-watcher.log" &
  echo $! > "$OUT/watcher.pid"
fi

adb install -r "$APK" || exit 1
adb install -r "$TEST_APK" || exit 1

NOT_CLASS=$(bash e2e/quarantine.sh)
echo "quarantined: ${NOT_CLASS:-none}"
ARGS=(-w -e annotation "$ANNOTATION" -e package me.capcom.smsgateway.e2e)
[ -n "$NOT_CLASS" ] && ARGS+=(-e notClass "$NOT_CLASS")

adb shell am instrument "${ARGS[@]}" "$RUNNER" | tee "$OUT/instrumented-output.txt"
curl -fsS http://127.0.0.1:8787/__control/state > "$OUT/stub-state.json" 2>/dev/null || true

# Real pass condition only: an OK line with at least one test, and no failure marker.
if ! grep -qE "OK \([1-9][0-9]* tests?\)" "$OUT/instrumented-output.txt" \
   || grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=" "$OUT/instrumented-output.txt"; then
  echo "e2e $MODE FAILED"
  exit 1
fi
echo "e2e $MODE OK"
