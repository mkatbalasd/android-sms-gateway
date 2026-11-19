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
RUNNER=com.mnaseb.sim.e2e.test/androidx.test.runner.AndroidJUnitRunner
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

NOT_CLASS=$(bash e2e/quarantine.sh) || { echo "quarantine.txt is malformed"; exit 1; }
echo "quarantined: ${NOT_CLASS:-none}"

P=me.capcom.smsgateway.e2e
# Classes run one `am instrument` each, in this order: the fresh-install checks (smoke, sign-up)
# must see an app that is not linked yet; the later classes link it themselves if needed and
# then share the linked state. (A single run orders classes arbitrarily.)
if [ "$MODE" = smoke ]; then CLASSES="$P.E2eSmokeTest"
else CLASSES="$P.E2eSmokeTest $P.E2eSignUpTest $P.E2eLineVerifyTest $P.E2eGatewayTest $P.E2eOneReportPerTapTest"; fi

RAN=0
: > "$OUT/instrumented-output.txt"
FAILED=0
for c in $CLASSES; do
  case ",$NOT_CLASS," in *",$c,"*) echo "skipping quarantined $c" | tee -a "$OUT/instrumented-output.txt"; continue ;; esac
  echo "=== $c" | tee -a "$OUT/instrumented-output.txt"
  adb shell am instrument -w -e class "$c" "$RUNNER" | tee "$OUT/one.txt" >> "$OUT/instrumented-output.txt"
  cat "$OUT/one.txt"
  if grep -qE "OK \([1-9][0-9]* tests?\)" "$OUT/one.txt" && ! grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=" "$OUT/one.txt"; then
    RAN=$((RAN + 1))
  else
    FAILED=1
  fi
done
curl -fsS http://127.0.0.1:8787/__control/state > "$OUT/stub-state.json" 2>/dev/null || true

# Real pass condition only: every non-quarantined class ended with an OK line (at least one test).
if [ "$FAILED" -ne 0 ] || [ "$RAN" -eq 0 ]; then
  echo "e2e $MODE FAILED (classes OK: $RAN)"
  exit 1
fi
echo "e2e $MODE OK ($RAN classes)"
