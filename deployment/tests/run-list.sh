#!/bin/bash
# batch 21 F: runs a test list (gate-*.txt, regression-*.txt) on one world, one line after another, and writes a short summary.
#   usage: run-list.sh <list> <build dir> <summary file>      Env: WORLD / HARNESS / FARM_LAYOUT as run-test.sh; WAIT = default wait.
#   Line format: [+] [VAR=value ...] TestName [-D flags...]. "+" keeps the previous line's data (KEEP=1 is usually given too);
#   VAR=value pairs (KEEP=1, WAIT=600, ...) apply to that line only and take precedence over the environment.
#   Summary: "=== <Test> rc=<0 DONE | 2 TIMEOUT | 3 ABORT> <seconds>s <vars>" then the test's RESULT/FAIL/PASS/TIMEOUT lines.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; LIST="$1"; BUILD="$2"; OUT="$3"
: > "$OUT"
grep -v '^#' "$LIST" | grep -v '^[[:space:]]*$' | while read -r line; do
    set -- $line
    [ "$1" = "+" ] && shift
    envs=(); while [[ "$1" == *=* && "$1" != -D* ]]; do envs+=("$1"); shift; done
    name=$1; shift
    start=$(date +%s)
    res=$(env BUILD="$BUILD" WAIT="${WAIT:-1200}" "${envs[@]}" bash "$HERE/run-test.sh" "$name" "$@" 2>&1); rc=$?
    echo "=== $name rc=$rc $(( $(date +%s) - start ))s ${envs[*]}" >> "$OUT"
    echo "$res" | grep -v JAVA_TOOL | grep -aE "RESULT|FAIL|PASS|TIMEOUT|ABORT|Exception" | cut -c1-300 >> "$OUT"
done
echo "LIST FINISHED" >> "$OUT"
