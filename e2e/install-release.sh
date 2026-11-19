#!/usr/bin/env bash
# Installs the release-signed APK ($APK) on the emulator and checks the first screen opens.
# Uses the production build as shipped (no stub); asserts install + welcome screen only.
set -uo pipefail
OUT=${E2E_OUT:-e2e-out}
PKG=com.mnaseb.sim
mkdir -p "$OUT"
REPORT="$OUT/install-release-report.md"
: > "$REPORT"
say() { echo "$*" | tee -a "$REPORT"; }
FAILED=0

say "# Release-signed APK on API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if adb install -r "$APK" 2>&1 | tee "$OUT/install.txt" | grep -q "Success"; then
  say "- PASS pm install of the release-signed APK"
else
  say "- FAIL pm install: $(tail -2 "$OUT/install.txt" | tr '\n' ' ')"; FAILED=1
fi

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
SEEN=0
for _ in $(seq 1 30); do
  adb shell uiautomator dump /sdcard/launch.xml >/dev/null 2>&1
  adb pull /sdcard/launch.xml "$OUT/launch.xml" >/dev/null 2>&1
  # the first screen of a fresh install is the home tab or the account welcome; either is app UI
  if grep -q "package=\"$PKG\"" "$OUT/launch.xml" 2>/dev/null; then SEEN=1; break; fi
  sleep 2
done
adb exec-out screencap -p > "$OUT/launch.png" 2>/dev/null
if [ "$SEEN" = 1 ]; then say "- PASS the app's own window is on screen after launch"; else say "- FAIL the app did not show a window"; FAILED=1; fi
adb logcat -d | grep -E "AndroidRuntime|FATAL" | head -20 > "$OUT/crash.txt"
if [ -s "$OUT/crash.txt" ]; then say "- FAIL a crash was logged on launch"; FAILED=1; else say "- PASS no crash logged on launch"; fi
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
[ "$FAILED" = 0 ]
