#!/bin/bash
# train-57: the soak ECON line from a world log (was inline in soak.sh, batch 22 task 4 / bug 296).
#   usage: econ-line.sh <world log>      Env: ECON_DROP_PCT (default 15).
#   Start figure: the "[CoinWatch] boot roster in: total N gp" line when the log has one (the whole roster, comparable between runs with and
#   without -Dclerk.bootFair), else the first "[CoinWatch] baseline" line as before (a partly spawned fleet). The line names the source:
#   "start(roster)" or "start(baseline)". End figure: the last "[CoinWatch] total" line.
#   Prints one line "ECON PASS|FAIL|n/a: ..." and exits 4 when bot coins fell more than the limit.
L="$1"; EPCT="${ECON_DROP_PCT:-15}"
CB=$(grep -a '^\[CoinWatch\] boot roster in: total [0-9]* gp' "$L" | head -1 | tr -d '\r' | awk '{print $6}'); SRC=roster
if [ -z "$CB" ]; then CB=$(grep -a '^\[CoinWatch\] baseline ' "$L" | head -1 | tr -d '\r' | awk '{print $3}'); SRC=baseline; fi
CE=$(grep -a '^\[CoinWatch\] total ' "$L" | tail -1 | tr -d '\r' | awk '{print $3}')
SEA=$(grep -a ' crossing the sea to ' "$L" | grep -cE ': (the boat|.* gp\))'); CWA=$(grep -ac '^\[CoinWatch\] ALARM' "$L"); RC=0
if [ -n "$CB" ] && [ -n "$CE" ] && [ "$CB" -gt 0 ] 2>/dev/null; then
  CD=$(( (CB - CE) * 100 / CB )); EV=PASS; [ "$CD" -gt "$EPCT" ] && { EV=FAIL; RC=4; }
  echo "ECON $EV: bot coins start($SRC) $CB end $CE (drop ${CD}%, limit ${EPCT}%), paid sea crossings $SEA, CoinWatch alarms $CWA"
else echo "ECON n/a: no CoinWatch baseline/total lines (-Dcoinwatch=false, no bots, or a soak under the warm-up), paid sea crossings $SEA, CoinWatch alarms $CWA"; fi
exit $RC
