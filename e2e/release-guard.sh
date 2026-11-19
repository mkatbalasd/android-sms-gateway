#!/usr/bin/env bash
# T2 release guard (fails closed): the stub-endpoint override must be absent from the release
# build and present in the e2e build (the positive control that proves the grep can see it).
#
#   bash e2e/release-guard.sh <release.apk> <emulatorE2e.apk>
#
# Strings in a dex file are stored as plain MUTF-8, so a byte grep over classes*.dex sees them.
set -uo pipefail

RELEASE_APK=${1:?release apk}
E2E_APK=${2:?emulatorE2e apk}
MARKER='E2E_STUB_OVERRIDE_ACTIVE'
STUB_HOST='10.0.2.2:8787'
ACCOUNT_URL='https://api.mnaseb.com/v1'
GATEWAY_URL='https://smsgate.mnaseb.com/api/mobile/v1'

[ -s "$RELEASE_APK" ] || { echo "::error::release apk missing or empty: $RELEASE_APK"; exit 1; }
[ -s "$E2E_APK" ] || { echo "::error::e2e apk missing or empty: $E2E_APK"; exit 1; }

dex_blob() { # $1 apk -> prints a path to the concatenated dex files
  local dir; dir=$(mktemp -d)
  unzip -o -q "$1" 'classes*.dex' -d "$dir" || { echo "::error::cannot unzip $1" >&2; exit 1; }
  ls "$dir"/classes*.dex >/dev/null || { echo "::error::no dex in $1" >&2; exit 1; }
  cat "$dir"/classes*.dex > "$dir/all.bin" || exit 1
  [ -s "$dir/all.bin" ] || { echo "::error::empty dex blob for $1" >&2; exit 1; }
  echo "$dir/all.bin"
}

has() { grep -a -F -q -- "$2" "$1"; }

REL=$(dex_blob "$RELEASE_APK")
E2E=$(dex_blob "$E2E_APK")
{ [ -s "$REL" ] && [ -s "$E2E" ]; } || { echo "::error::could not read dex of an apk"; exit 1; }
fail=0
check() { # description, condition result (0 = ok)
  if [ "$2" -eq 0 ]; then echo "PASS  $1"; else echo "FAIL  $1"; fail=1; fi
}

# positive controls first: if the grep cannot see these, nothing below proves anything
has "$E2E" "$MARKER";           check "e2e build contains the override marker (control)" $?
has "$E2E" "$STUB_HOST";        check "e2e build contains the stub host (control)" $?
has "$REL" "$ACCOUNT_URL";      check "release still uses the production account URL" $?
has "$REL" "$GATEWAY_URL";      check "release still uses the production gateway URL" $?

# the actual T2 rule
! has "$REL" "$MARKER";         check "release does NOT contain the override marker" $?
! has "$REL" "$STUB_HOST";      check "release does NOT contain the stub host" $?

if [ "$fail" -ne 0 ]; then
  echo "::error::release guard failed (closed): the e2e endpoint override must not ship in release"
  exit 1
fi
echo "release guard OK"
