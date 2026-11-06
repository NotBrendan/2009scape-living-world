#!/bin/bash
# One test, before/after style: builds deployment/addons-src (or the source folder given in SRC) with every test class on the
# path (each test only runs when its -Dtest.<name>=true flag is set), starts the trial world, waits for "[<Test>] DONE",
# prints the test's own lines and stops the world.
#   usage: run-test.sh <TestName> [-Dtest.x=true and other -D flags...]
#   e.g.   run-test.sh TestA1 -Dtest.a1=true -Dsmartbots.count=4 -Dtraders.fast=true -Dquests.off=true
#   before/after: SRC=/path/to/old/source run-test.sh ...   then the same without SRC (the current source).
#   WORLD=wNN runs on that farm world (layout.json); BUILD=<dir> reuses a finished build instead of building;
#   WAIT=<seconds> caps the wait for DONE (default 1800). Exit 0 = DONE seen, 2 = timed out, 3 = aborted (port not on 127.0.0.1).
# Needs HARNESS or WORLD (see harness/env.sh). Never point it at the live server: it runs the trial world only.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; NAME="$1"; shift
WORLD="${WORLD:-}"; . "$HERE/harness/env.sh"
SRC="${SRC:-$HERE/../addons-src}"
if [ -n "${BUILD:-}" ]; then OUT="$BUILD"; else
    OUT="$ME/build-$NAME-$(date +%s)"
    bash "$HERE/harness/build.sh" "$OUT" "$SRC" "$HERE"/content/custom/*.java | tail -2
fi
LOG="t-$NAME-$(date +%s)"
bash "$HERE/harness/run.sh" "$LOG" "$OUT" "$@" > /dev/null 2>&1 &
L="$ME/qt/$LOG.log"; RC=2
for i in $(seq 1 $(( ${WAIT:-1800} / 5 ))); do
    grep -aq "\[$NAME\] DONE" "$L" 2>/dev/null && { RC=0; break; }
    # batch 21 F: a test that needs the harness to send stop mid-run (TestB17A4) prints "[<Test>] HARNESS: stop"; sent once
    if [ -z "${STOPPED:-}" ] && grep -aq "\[$NAME\] HARNESS: stop" "$L" 2>/dev/null; then STOPPED=1; bash "$HERE/harness/stop.sh" > /dev/null 2>&1; fi
    if [ "$i" = 12 ] && [ -n "${GAME_PORT:-}" ] && port_public "$GAME_PORT"; then
        echo "[$NAME] ABORT: port $GAME_PORT is listening beyond 127.0.0.1"; RC=3; break
    fi
    sleep 5
done
grep -a "\[$NAME\]" "$L"
[ $RC -eq 2 ] && echo "[$NAME] TIMEOUT after ${WAIT:-1800}s, last log lines:" && tail -5 "$L"
bash "$HERE/harness/stop.sh" > /dev/null 2>&1
for i in $(seq 1 60); do grep -aq 'successfully terminated' "$L" 2>/dev/null && break; sleep 2; done
echo "log: $L"
exit $RC
