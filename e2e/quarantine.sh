#!/usr/bin/env bash
# Prints the quarantined classes as a comma list for `am instrument -e notClass`, and the
# list in Markdown for the job summary. Usage: bash e2e/quarantine.sh [list-file]
set -uo pipefail
FILE=${1:-e2e/quarantine.txt}
[ -f "$FILE" ] || exit 0
awk -F'|' '
  /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
  {
    t=$1; gsub(/^[ \t]+|[ \t]+$/, "", t)
    if (t != "" && t != "test") printf "%s%s", (n++ ? "," : ""), t
  }
  END { printf "\n" }
' "$FILE"
