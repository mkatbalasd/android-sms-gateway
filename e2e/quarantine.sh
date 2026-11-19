#!/usr/bin/env bash
# Prints the quarantined classes as a comma list (for `am instrument -e notClass` and for the
# class loop of run-e2e.sh). Every entry needs all three columns, "test | root cause | ROADMAP row";
# an entry missing the root cause or the ROADMAP row is an error (exit 1), so a test cannot be
# silenced without a reason and a tracked row. Usage: bash e2e/quarantine.sh [list-file]
set -uo pipefail
FILE=${1:-e2e/quarantine.txt}
[ -f "$FILE" ] || exit 0
awk -F'|' '
  function trim(x) { gsub(/^[ \t\r]+|[ \t\r]+$/, "", x); return x }
  /^[ \t\r]*#/ || /^[ \t\r]*$/ { next }
  {
    t = trim($1); cause = trim($2); row = trim($3)
    if (t == "" || cause == "" || row == "" || NF != 3) {
      printf "quarantine.txt line %d needs: test | root cause | ROADMAP row\n", NR > "/dev/stderr"
      bad = 1; next
    }
    if (row !~ /^V2-L[0-9]+$/) printf "::warning::quarantine.txt line %d: ROADMAP row \"%s\" is not V2-L<digits>
", NR, row > "/dev/stderr"
    printf "%s%s", (n++ ? "," : ""), t
  }
  END { printf "\n"; exit bad }
' "$FILE"
