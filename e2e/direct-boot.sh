#!/usr/bin/env bash
# Direct Boot job (CARD-21 A1, A2, A3, A4, A6 observation, A8) on an emulator with a screen-lock PIN.
# API 30+ x86_64 image with file-based encryption. Called from emulator-e2e.yml inside
# reactivecircus/android-emulator-runner.
#
#   bash e2e/direct-boot.sh
#
# Writes e2e-out/direct-boot-report.md (also appended to the job summary by the workflow).
set -uo pipefail

OUT=${E2E_OUT:-e2e-out}
APK=${E2E_APK:-app/build/outputs/apk/emulatorE2e/app-emulatorE2e.apk}
TEST_APK=${E2E_TEST_APK:-app/build/outputs/apk/androidTest/emulatorE2e/app-emulatorE2e-androidTest.apk}
RUNNER=com.mnaseb.sim.e2e.test/androidx.test.runner.AndroidJUnitRunner
PKG=com.mnaseb.sim.e2e
PIN=1234 # a throwaway emulator PIN, never a real one
STUB=http://127.0.0.1:8787/__control
REPORT="$OUT/direct-boot-report.md"
mkdir -p "$OUT"
: > "$REPORT"
FAILED=0

say()  { echo "$*" | tee -a "$REPORT"; }
pass() { say "- PASS $1"; }
fail() { say "- FAIL $1"; FAILED=1; }
info() { say "- INFO $1"; }

# node one-liner over the stub state: stubq 'j.sse.connections'
stubq() {
  curl -fsS "$STUB/state" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);console.log(new Function("j","return "+process.argv[1])(j))})' "$1"
}
prop() { adb shell getprop "$1" | tr -d '\r'; }
ce()   { prop sys.user.0.ce_available; }

cleanup() {
  [ -f "$OUT/stub.pid" ] && kill "$(cat "$OUT/stub.pid")" 2>/dev/null
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
}
trap cleanup EXIT

node e2e/stub-server/server.js 8787 > "$OUT/stub.log" 2>&1 &
echo $! > "$OUT/stub.pid"
for _ in $(seq 1 30); do curl -fsS "$STUB/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "$STUB/health" >/dev/null || { echo "stub did not start"; exit 1; }

adb install -r "$APK" || exit 1
adb install -r "$TEST_APK" || exit 1

say "# Direct Boot on the emulator (CARD-21)"
say "API $(prop ro.build.version.sdk), image $(prop ro.product.model), crypto type: $(prop ro.crypto.type)"

# ---- A1: file-based encryption present (else the rest cannot prove anything)
if [ "$(prop ro.crypto.type)" = "file" ]; then pass "A1 ro.crypto.type=file (FBE)"; else fail "A1 image is not FBE; Direct Boot is not provable here"; fi

# ---- CARD-24: the platform reports an active line on slot 1, the emulator's only SIM; an inbound
# SMS is kept and forwarded only on such a slot
curl -fsS -X POST -H 'Content-Type: application/json' -d '{"slot":1}' "$STUB/lines" >/dev/null || { fail "could not seed the active line on the stub"; exit 1; }

# ---- prepare: link the phone through the UI, then PIN
adb shell am instrument -w -e class me.capcom.smsgateway.e2e.E2eDirectBootTest#prepareLinkedPhone "$RUNNER" | tee "$OUT/prepare.txt"
grep -qE "OK \([1-9][0-9]* tests?\)" "$OUT/prepare.txt" && ! grep -q "FAILURES!!!" "$OUT/prepare.txt" || { fail "prepare: the phone did not link and open a stream"; exit 1; }

CONNS0=$(stubq 'j.sse.connections')
HB0=$(stubq 'j.heartbeats.length')
adb shell locksettings set-pin "$PIN" || { fail "could not set the screen-lock PIN"; exit 1; }
adb shell locksettings get-disabled | grep -qi false && pass "screen lock PIN set" || info "locksettings get-disabled: $(adb shell locksettings get-disabled | tr -d '\r')"

# ---- reboot WITHOUT unlocking
adb reboot
adb wait-for-device
for _ in $(seq 1 120); do [ "$(prop sys.boot_completed)" = 1 ] && break; sleep 2; done

# ---- A2: CE storage still locked
CE=$(ce)
if [ "$CE" != "true" ]; then pass "A2 sys.user.0.ce_available='${CE:-<empty>}' (not unlocked)"; else fail "A2 user 0 is already unlocked after reboot (ce_available=true)"; fi

# ---- A3: the foreground service runs and the stub hears the phone, still locked
A3=0
for _ in $(seq 1 60); do
  if adb shell dumpsys activity services "$PKG" | grep -q SSEForegroundService \
     && [ "$(stubq 'j.sse.connections')" -gt "$CONNS0" ] && [ "$(stubq 'j.heartbeats.length')" -gt "$HB0" ]; then A3=1; break; fi
  sleep 3
done
STILL_LOCKED=$(ce)
if [ "$A3" = 1 ] && [ "$STILL_LOCKED" != "true" ]; then
  pass "A3 SSEForegroundService running, new SSE connection and heartbeat seen by the stub, ce_available='${STILL_LOCKED:-<empty>}'"
else
  fail "A3 service/stream/heartbeat before unlock not proven (service+stub seen=$A3, ce_available='${STILL_LOCKED:-<empty>}')"
fi
adb shell dumpsys activity services "$PKG" | grep -E "SSEForegroundService|createTime" | head -5 > "$OUT/a3-services.txt"

# ---- A4: a message pushed through SSE is sent and reported while still locked
BEFORE_SENT=$(stubq 'j.messageReports.filter(r=>r.state==="Sent").length')
curl -fsS -X POST -H 'Content-Type: application/json' -d '{"to":"+15550000000","text":"e2e locked send"}' "$STUB/inject/outbound" >/dev/null
A4=0
for _ in $(seq 1 40); do
  [ "$(stubq 'j.messageReports.filter(r=>r.state==="Sent").length')" -gt "$BEFORE_SENT" ] && { A4=1; break; }
  sleep 3
done
if [ "$A4" = 1 ] && [ "$(ce)" != "true" ]; then pass "A4 a pushed message was sent and reported Sent while locked"; else fail "A4 no Sent report while locked (seen=$A4, ce_available='$(ce)')"; fi
info "A5 (E2E-encrypted message while locked) is NOT driven: the stub holds no encryption flow"

# ---- A6 observation: an inbound SMS while locked
INBOX0=$(stubq 'j.inbox.length')
adb emu sms send 5551234 "e2e locked inbound" >/dev/null 2>&1
sleep 20 # observation window only; the verdict is taken after the unlock below
EARLY=$(stubq 'j.inbox.length')
if [ "$EARLY" -gt "$INBOX0" ]; then info "A6 OBSERVED: the SMS reached the stub BEFORE unlock (emulator delivers SMS_RECEIVED early; differs from real phones, I-1)"; else info "A6 OBSERVED: the SMS was withheld until unlock (matches I-1)"; fi

# ---- unlock
adb shell input keyevent 82
sleep 1
adb shell input text "$PIN"
adb shell input keyevent 66
for _ in $(seq 1 60); do [ "$(ce)" = "true" ] && break; sleep 2; done
[ "$(ce)" = "true" ] && pass "unlock: ce_available=true" || fail "unlock did not complete (ce_available='$(ce)')"

for _ in $(seq 1 30); do [ "$(stubq 'j.inbox.length')" -gt "$INBOX0" ] && break; sleep 2; done
INBOX1=$(stubq 'j.inbox.length')
if [ "$INBOX1" -eq $((INBOX0 + 1)) ]; then pass "A6 the locked-time SMS reached the stub exactly once after unlock (total new reports: 1)"; else fail "A6 inbox grew by $((INBOX1 - INBOX0)) for one SMS (expected exactly 1)"; fi

# ---- A8: WorkManager jobs, no duplicate sends, still signed in
adb shell am instrument -w -e class me.capcom.smsgateway.e2e.E2eDirectBootTest#afterUnlock "$RUNNER" | tee "$OUT/after-unlock.txt"
if grep -qE "OK \([1-9][0-9]* tests?\)" "$OUT/after-unlock.txt" && ! grep -q "FAILURES!!!" "$OUT/after-unlock.txt"; then
  pass "A8 jobs present after unlock, no message reported Sent twice, link intact"
else
  fail "A8 after-unlock assertions failed (see after-unlock.txt)"
fi
adb shell dumpsys jobscheduler | grep -c "$PKG" > "$OUT/a8-jobs-count.txt"

# ---- CARD-24: the phone is a relay, not an archive.
# Data of the test: the locked send (text + recipient) and the locked inbound SMS (body + sender).
TEXT_OUT="e2e locked send"; NUM_OUT="15550000000"; BODY_IN="e2e locked inbound"; SENDER_IN="5551234"
DE_DIR="/data/user_de/0/$PKG"

# root read of the app's device-protected storage: pulled to the host, searched there (counts only)
de_pull() {
  adb root >/dev/null 2>&1; adb wait-for-device
  rm -rf "$OUT/de"; mkdir -p "$OUT/de"
  adb pull "$DE_DIR" "$OUT/de" >/dev/null 2>&1
  [ -f "$OUT/de/$PKG/databases/gateway" ] || [ -f "$OUT/de/databases/gateway" ]
}
de_hits() { grep -rlaF -- "$1" "$OUT/de" 2>/dev/null | wc -l | tr -d ' '; }

# the unlocked phone, inside every window: the instrumented side checks the text is gone, the
# bare id is there, and plants the rollback copies an upgraded phone has
adb shell am instrument -w -e class me.capcom.smsgateway.e2e.E2eDirectBootTest#retentionBeforeShift "$RUNNER" | tee "$OUT/retention-before.txt"
if grep -qE "OK \([1-9][0-9]* tests?\)" "$OUT/retention-before.txt" && ! grep -q "FAILURES!!!" "$OUT/retention-before.txt"; then
  pass "R0 text deleted at the final state, id row present, recipient still inside its window"
else
  fail "R0 before-shift assertions failed (see retention-before.txt)"
fi
if de_pull; then
  # positive control: the recipient number is still stored (24h window not over), so the search can see it
  [ "$(de_hits "$NUM_OUT")" -gt 0 ] && pass "R1 control: the recipient number is still in device-protected storage inside its window" \
    || fail "R1 control: the recipient number was not found inside its window (the search is blind)"
else
  fail "R1 root read of device-protected storage failed (adb root or pull)"
fi

# the time shift (emulatorE2e only) and the clean-up, through the real service
adb shell am instrument -w -e class me.capcom.smsgateway.e2e.E2eDirectBootTest#retentionAfterShift "$RUNNER" | tee "$OUT/retention-after.txt"
if grep -qE "OK \([1-9][0-9]* tests?\)" "$OUT/retention-after.txt" && ! grep -q "FAILURES!!!" "$OUT/retention-after.txt"; then
  pass "R2 after +4 days: recipient deleted, incoming text and sender deleted, rollback copies removed, a repeated id is refused"
else
  fail "R2 after-shift assertions failed (see retention-after.txt)"
fi

# restart and leave the phone locked
adb reboot
adb wait-for-device
for _ in $(seq 1 120); do [ "$(prop sys.boot_completed)" = 1 ] && break; sleep 2; done
LOCKED_AGAIN=$(ce)
if [ "$LOCKED_AGAIN" != "true" ]; then pass "R3 restarted and left locked (ce_available='${LOCKED_AGAIN:-<empty>}')"; else fail "R3 the phone is unlocked after the restart"; fi
sleep 20 # the locked pipeline starts and may write; whatever it writes is searched too

# ROOT read of the device-protected storage: none of what items 1, 2 and 4 remove
if de_pull; then
  for pair in "message text:$TEXT_OUT" "recipient number:$NUM_OUT" "incoming body:$BODY_IN" "incoming sender:$SENDER_IN"; do
    label=${pair%%:*}; needle=${pair#*:}
    hits=$(de_hits "$needle")
    if [ "$hits" = 0 ]; then pass "R4 root read while locked: no $label in device-protected storage"; else fail "R4 root read while locked: $label found in $hits file(s)"; fi
  done
  ls -R "$OUT/de" 2>/dev/null | grep -qE "^(gateway|mnaseb_device_storage.xml)$" && info "R4 the storage read is the app's (database present)"
else
  fail "R4 root read of device-protected storage failed (adb root or pull)"
fi

# tidy: remove the PIN so the emulator image is not left locked
adb shell locksettings clear --old "$PIN" >/dev/null 2>&1

say ""
say "Not provable on an emulator: A7 (real network loss and Doze over 15 minutes), OEM battery and autostart paths (HyperOS, EMUI), a real carrier SMS_RECEIVED hold (A6 is observed above, not asserted), E2E-encrypted send while locked (A5), delivery reports from a real network (A4 asserts the Sent report only)."
[ "$FAILED" = 0 ] && echo "direct boot OK" || { echo "direct boot FAILED"; exit 1; }
