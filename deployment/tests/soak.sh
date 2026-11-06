#!/bin/bash
# Timed soak on one farm world (batch 18): starts the world, lets it run for <minutes>, stops it, then writes an analysis.
#   usage: soak.sh <wNN> <builddir> <minutes> <tag> [-D flags...]     (add -Dtest.tickprobe=true for tick numbers and bot deaths)
#   Env: SEED (live | <dir>), RUN (results subfolder, default <date>), JAVA/JAVAC, XMX.
#   Writes <results>/<RUN>/soak-<tag>-<wNN>.txt and the world log next to it. Exceptions exclude the two known shutdown-time ones.
#   batch 24b: a DEALS line (harness/deals-line.sh: Bargain sales/walk-aways logged during the soak); exit 5 if any final is outside [floor, list].
#   batch 22: an ECON line (CoinWatch baseline/last total, paid sea crossings); exit 4 if bot coins fell over ECON_DROP_PCT (default 15).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; W="$1"; BLD="$2"; MIN="$3"; TAG="$4"; shift 4
export WORLD="$W"; . "$HERE/harness/env.sh"
RUN="${RUN:-$(date -u +%Y-%m-%d)}"; R="${FARM_RESULTS:-$ME/results}/$RUN"; mkdir -p "$R"
LOG="soak-$TAG-$(date +%s)"; L="$ME/qt/$LOG.log"
T0=$(date +%s)
WORLD="$W" SEED="${SEED:-}" bash "$HERE/harness/run.sh" "$LOG" "$BLD" "$@" < /dev/null > /dev/null 2>&1 &
sleep 60
if netstat -ano | grep LISTENING | grep ":$GAME_PORT " | grep -vq '127.0.0.1:'; then echo "soak $TAG: port $GAME_PORT beyond 127.0.0.1, stopping"; bash "$HERE/harness/stop.sh"; exit 3; fi
while [ $(( $(date +%s) - T0 )) -lt $(( MIN * 60 )) ]; do sleep 15; done
T1=$(date +%s)
bash "$HERE/harness/stop.sh"
for i in $(seq 1 90); do grep -aq 'successfully terminated' "$L" 2>/dev/null && break; sleep 2; done
cp "$L" "$R/soak-$TAG-$W.log"
SV="$ME/qt/game/data/survey"
EXRE='^(Exception in thread|[a-zA-Z_$][a-zA-Z0-9_$.]*(Exception|Error)(:|$))'
# batch 22 task 4 (bug 296): coins at start/end from the CoinWatch lines, paid sea crossings from the [SmartAdv] crossing line
CB=$(grep -a '^\[CoinWatch\] baseline ' "$L" | head -1 | tr -d '\r' | awk '{print $3}'); CE=$(grep -a '^\[CoinWatch\] total ' "$L" | tail -1 | tr -d '\r' | awk '{print $3}')
SEA=$(grep -a ' crossing the sea to ' "$L" | grep -cE ': (the boat|.* gp\))'); CWA=$(grep -ac '^\[CoinWatch\] ALARM' "$L"); EPCT="${ECON_DROP_PCT:-15}"; ECON_RC=0
if [ -n "$CB" ] && [ -n "$CE" ] && [ "$CB" -gt 0 ] 2>/dev/null; then
  CD=$(( (CB - CE) * 100 / CB )); EV=PASS; [ "$CD" -gt "$EPCT" ] && { EV=FAIL; ECON_RC=4; }
  ECON="ECON $EV: bot coins start $CB end $CE (drop ${CD}%, limit ${EPCT}%), paid sea crossings $SEA, CoinWatch alarms $CWA"
else ECON="ECON n/a: no CoinWatch baseline/total lines (-Dcoinwatch=false, no bots, or a soak under the warm-up), paid sea crossings $SEA, CoinWatch alarms $CWA"; fi
# batch 24b item 4: Bargain deals logged during this soak (lines dated from T0), and the engine's own OFF trips
DL="$ME/qt/game/data/botchat-deals.log"; BOFF=$(grep -ac '^\[Bargain\] OFF' "$L")
DEALS="$(bash "$HERE/harness/deals-line.sh" "$(date -u -d @$T0 +%FT%T)" "$DL.1" "$DL"), [Bargain] OFF lines $BOFF"; DEALS_RC=0; case "$DEALS" in "DEALS FAIL"*) DEALS_RC=5;; esac
role() { local n="$1"; grep -aE "^\[[A-Za-z]+\] $n set out as" "$L" | head -1 | sed 's/.* set out as an\{0,1\} //; s/;.*//'; }
{
  echo "soak: $TAG on $W (world id ${WORLD_ID:-?}, 127.0.0.1:${GAME_PORT:-?}), build $BLD, seed ${SEED:-world survey-pristine}"
  echo "flags: $*"; echo "ran: $(date -u -d @$T0 +%FT%TZ) to $(date -u -d @$T1 +%FT%TZ), $(( (T1-T0) / 60 )) min (timed)"
  echo; echo "== health"
  echo "exceptions (excluding the known shutdown ones): $(grep -aE "$EXRE" "$L" | grep -avE 'scriptEngine|InterruptedException|MajorUpdateWorker' | wc -l)"
  grep -aE "$EXRE" "$L" | grep -avE 'scriptEngine|InterruptedException|MajorUpdateWorker' | sort | uniq -c | sort -rn | head -10
  echo "quarantines (load failed, audit O-1): $(grep -ac 'audit 10-23 O-1' "$L")"; grep -a 'audit 10-23 O-1' "$L" | head -5
  echo "sanity check failures: $(grep -ac 'SANITY CHECK FAILED' "$L")"
  echo "long running pulses: $(grep -ac 'Long running pulse' "$L") (critical: $(grep -ac 'CRITICALLY' "$L"))"; grep -ao 'Long running pulse - [A-Za-z0-9_$.]*' "$L" | sort | uniq -c | sort -rn | head -6
  echo; echo "== ticks (TestTickProbe, one line per window)"
  grep -a '^\[TickProbe\] ticks' "$L" | sed 's/^\[TickProbe\] //'
  grep -a '^\[TickProbe\] ticks' "$L" | awk '{for(i=1;i<=NF;i++){if($i=="interval"){s+=$(i+2);n++; if($(i+5)>m)m=$(i+5)} if($i=="limit"){a+=$(i+1)+0}}} END{if(n) printf "summary: %d windows, mean interval %.0f ms, worst tick %d ms, ticks at the 75 limit %d\n", n, s/n, m, a}'
  echo; echo "== bot deaths by role"
  grep -a '^\[TickProbe\] death ' "$L" | awk '{print $3}' | while read -r n; do r=$(role "$n"); echo "${r:-other bot (smart adventurer, trader, service)}"; done | sort | uniq -c | sort -rn
  echo; echo "== roles at startup"
  grep -aE '^\[(Explore)\] .*scheduled|^\[Survey\] .*pathing surveyors scheduled|^\[Gates\] seed|^\[OffGround\] seed|^\[OffGround\] list ready|^\[Replay\] [0-9]+ rescue replayers|^\[Replay\] read |^\[Replay\] [0-9]+ prove' "$L" | cut -c1-600
  echo "set out: $(grep -aoE ' set out as an? [a-z -]+' "$L" | sort | uniq -c | tr '\n' ';')"
  echo "retired: $(grep -aE '^\[(OffGround|Explore|Replay)\] .* retired' "$L" | sed -E 's/^\[([A-Za-z]+)\].*/\1/' | sort | uniq -c | tr '\n' ';')"
  echo; echo "== replay queue"
  Q=$(grep -a '^\[Replay\] queued ' "$L" | grep -vc captest); echo "queued during the soak (live-style records): $Q"
  grep -a '^\[Replay\] queued ' "$L" | awk '{print $4}' | sort | uniq -c | sort -rn
  echo "results: $(grep -a '^\[Replay\] result ' "$L" | awk '{print $4}' | sort | uniq -c | tr '\n' ';')"
  grep -a '^\[Replay\] result ' "$L" | sed -n 's/.*, lag \([0-9]*\) s.*/\1/p' | sort -n | awk '{a[NR]=$1} END{if(NR) print "lag: n " NR ", median " a[int((NR+1)/2)] " s, max " a[NR] " s"; else print "lag: no results"}'
  echo "to-do over time (from the [Replay] counts lines):"; grep -aoE '[0-9]+ scenarios total, [0-9]+ passed, [0-9]+ failed \(proven\), [0-9]+ still to replay' "$L" | awk 'NR==1||NR%10==0{print "  " $0} END{print "  last: " $0}'
  echo "first queued: $(grep -a '^\[Replay\] queued ' "$L" | grep -v captest | head -1 | cut -c1-200)"
  echo "first result: $(grep -a '^\[Replay\] result ' "$L" | head -1 | cut -c1-250)"
  echo; echo "== survey files at the end"
  for f in offgrid.jsonl stairs.jsonl shortcuts.jsonl doors.jsonl scenarios.jsonl scenario-results.jsonl; do [ -f "$SV/$f" ] && echo "$f: $(wc -l < "$SV/$f") lines"; done
  ls "$ME/qt/game/data/reports" 2>/dev/null | sed 's/^/report: /'
  echo; echo "== economy (batch 22 coin-drain alarm)"; echo "$ECON"; grep -a '^\[CoinWatch\] ALARM' "$L" | head -5 | cut -c1-400
  echo "$DEALS"; grep -a '^\[Bargain\] OFF' "$L" | head -3 | cut -c1-300
} > "$R/soak-$TAG-$W.txt"
echo "soak $TAG on $W done -> $R/soak-$TAG-$W.txt"
echo "$ECON"; echo "$DEALS"; [ "$ECON_RC" -ne 0 ] && exit $ECON_RC; exit $DEALS_RC
