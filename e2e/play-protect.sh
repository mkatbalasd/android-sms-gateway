#!/usr/bin/env bash
# INFORMATION ONLY (the workflow job is continue-on-error): sideload the debug-signed APK on a
# Google Play system image and record what Play Protect / the package installer does.
# Output (screenshots, uiautomator dumps, filtered logcat, a Markdown report) goes to e2e-out/.
# The emulator holds no customer data; the logcat is still filtered to installer/verifier tags.
#
#   bash e2e/play-protect.sh
set -uo pipefail

OUT=${E2E_OUT:-e2e-out}
APK=${E2E_APK:-app/build/outputs/apk/emulatorE2e/app-emulatorE2e.apk}
PKG=com.mnaseb.sim.e2e
REPORT="$OUT/play-protect-report.md"
mkdir -p "$OUT"
: > "$REPORT"
say() { echo "$*" | tee -a "$REPORT"; }

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
dump() { adb shell uiautomator dump /sdcard/"$1".xml >/dev/null 2>&1; adb pull /sdcard/"$1".xml "$OUT/$1.xml" >/dev/null 2>&1; }

say "# Play Protect on a Google Play image (information only)"
say "API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), model $(adb shell getprop ro.product.model | tr -d '\r')"
say "package_verifier_enable (global): $(adb shell settings get global package_verifier_enable | tr -d '\r')"
say "verifier_verify_adb_installs (global): $(adb shell settings get global verifier_verify_adb_installs | tr -d '\r')"
say "package_verifier_user_consent (secure): $(adb shell settings get secure package_verifier_user_consent | tr -d '\r')"
say "Play Store present: $(adb shell pm list packages com.android.vending | tr -d '\r' | grep -c vending)"

adb logcat -c

# (a) adb install: the path a developer uses
RESULT=$(adb install -r "$APK" 2>&1 | tr -d '\r' | tail -3)
say ""
say "## adb install"
say '```'
say "$RESULT"
say '```'
sleep 15 # let a verifier scan finish before the evidence is taken (information run, no assertion)
shot after-adb-install
dump after-adb-install

# (b) the installer UI: what a customer sees when opening a downloaded APK
adb shell pm uninstall "$PKG" >/dev/null 2>&1
adb push "$APK" /data/local/tmp/mnaseb-sim.apk >/dev/null 2>&1
adb shell am start -a android.intent.action.VIEW -t application/vnd.android.package-archive \
  -d file:///data/local/tmp/mnaseb-sim.apk >/dev/null 2>&1
sleep 10 # same: evidence capture
shot installer-ui
dump installer-ui

say ""
say "## Installer screen (uiautomator dump, keyword scan)"
for kw in "Play Protect" "Blocked" "blocked" "Install anyway" "unknown" "Unknown" "Send for scanning" "Scan" "not allowed"; do
  if grep -q -- "$kw" "$OUT/installer-ui.xml" 2>/dev/null; then say "- contains: $kw"; fi
done
[ -s "$OUT/installer-ui.xml" ] || say "- no dump captured"

adb logcat -d 2>/dev/null | grep -iE "PackageInstaller|PlayProtect|verifier|vending|VerifyApps|InstallStart" | head -200 > "$OUT/play-protect-logcat.txt"
say ""
say "Filtered logcat lines: $(wc -l < "$OUT/play-protect-logcat.txt")"
say "Installed after the run: $(adb shell pm list packages "$PKG" | tr -d '\r' | grep -c "$PKG")"
say ""
say "This does NOT predict a real phone: Play Protect's cloud verdict depends on the app signature, Google account state and region. The real result is CARD-18 item 1 on the customer phone."
exit 0
