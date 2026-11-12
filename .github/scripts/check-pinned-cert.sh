#!/usr/bin/env bash
# Hard gate: the signing certificate SHA-256 of a signed APK must equal the pinned value.
# Usage (PINNED_CERT_SHA256 is required in the environment):
#   check-pinned-cert.sh --digest <value>   compare a digest string (used by the self-test)
#   check-pinned-cert.sh <apk>              extract the digest with apksigner (BUILD_TOOLS must be set)
# Prints only the digest; never keystore material or passwords. Fails closed:
# an empty or unextractable digest, or a mismatch, exits non-zero. On success it
# prints the normalised digest (lowercase, no colons) on stdout.
set -euo pipefail

norm() { printf '%s' "$1" | tr -d ': \t\r\n' | tr 'A-F' 'a-f'; }

PINNED="$(norm "${PINNED_CERT_SHA256:-}")"
if ! printf '%s' "$PINNED" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "::error::Pinned certificate SHA-256 is missing or malformed" >&2
  exit 2
fi

if [ "${1:-}" = "--digest" ]; then
  RAW="${2:-}"
else
  APK="${1:-}"
  test -f "$APK" || { echo "::error::APK not found" >&2; exit 2; }
  RAW="$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | grep -m1 'certificate SHA-256 digest' | sed 's/.*: *//' || true)"
fi

CERT="$(norm "$RAW")"
if [ -z "$CERT" ]; then
  echo "::error::Could not extract the signing certificate SHA-256 - refusing to publish" >&2
  exit 1
fi
if [ "$CERT" != "$PINNED" ]; then
  echo "::error::Signing certificate SHA-256 differs from the pinned value - refusing to publish (built: $CERT, pinned: $PINNED)" >&2
  exit 1
fi
printf '%s\n' "$CERT"
