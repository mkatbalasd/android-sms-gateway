#!/usr/bin/env bash
# Self-test for check-pinned-cert.sh (no secrets, no Android tools needed).
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
PIN="05f3a9e7b96c8306ce46c3312379d0713f5f92aca9b1a9f62492fab3a286f3e2"
fail=0
expect() { # name want(ok|fail) pin args...
  local name="$1" want="$2" pin="$3"; shift 3
  PINNED_CERT_SHA256="$pin" bash "$DIR/check-pinned-cert.sh" "$@" >/dev/null 2>&1
  local rc=$?
  if { [ "$want" = ok ] && [ $rc -eq 0 ]; } || { [ "$want" = fail ] && [ $rc -ne 0 ]; }; then
    echo "PASS $name"
  else
    echo "FAIL $name (rc=$rc)"; fail=1
  fi
}
expect exact ok "$PIN" --digest "$PIN"
expect uppercase ok "$PIN" --digest "05F3A9E7B96C8306CE46C3312379D0713F5F92ACA9B1A9F62492FAB3A286F3E2"
expect colons ok "$PIN" --digest "05:F3:A9:E7:B9:6C:83:06:CE:46:C3:31:23:79:D0:71:3F:5F:92:AC:A9:B1:A9:F6:24:92:FA:B3:A2:86:F3:E2"
expect different fail "$PIN" --digest "0000000000000000000000000000000000000000000000000000000000000000"
expect one-char-off fail "$PIN" --digest "05f3a9e7b96c8306ce46c3312379d0713f5f92aca9b1a9f62492fab3a286f3e3"
expect empty-digest fail "$PIN" --digest ""
expect missing-apk fail "$PIN" /nonexistent.apk
expect unset-pin fail "" --digest "$PIN"
exit $fail
