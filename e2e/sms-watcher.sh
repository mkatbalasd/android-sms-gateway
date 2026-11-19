#!/usr/bin/env bash
# Host-side helper: fires the inbound SMS that the tests request from the stub
# (POST /__control/inject/inbound-sms) through the emulator console. The emulator console is
# not reachable from inside the guest, so the host does it: `adb emu sms send <from> <text>`.
# Numbers and texts in the requests are test values; the stub strips everything but
# [0-9+] and [0-9A-Za-z ._-].
#
#   bash e2e/sms-watcher.sh [logfile]      (runs until killed)
set -uo pipefail
LOG=${1:-/dev/null}
TAB=$'\t'
while true; do
  line=$(curl -fsS "http://127.0.0.1:8787/__control/sms-requests.txt" 2>/dev/null || true)
  if [ -n "$line" ]; then
    from=${line%%"$TAB"*}
    text=${line#*"$TAB"}
    text=${text%$'\n'}
    echo "$(date +%T) firing one inbound SMS" >> "$LOG"
    adb emu sms send "$from" "$text" >> "$LOG" 2>&1
  fi
  sleep 1
done
