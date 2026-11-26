#!/bin/bash
# W2 lane M in-world check (2026-11-19): flag ON vs flag OFF, two farm worlds side by side, PASS/FAIL per gate from log counts.
# NOT before 04:30 UTC 2026-11-19 (the script refuses to start a world earlier). Offline re-read of finished logs: SUMMARY_ONLY.
#   usage: w2-summon-check.sh <steer|loot> [build=farm/builds/w2m]
#   env:   MIN=60 (run minutes)  BOTS=24  WOFF=w01 WON=w02 (steer; loot defaults to w03 / w04)  EXTRA="-D..." (both arms, added last)
#          SUMMARY_ONLY=1 OFFLOG=<world log> ONLOG=<world log> [MIN=<minutes they ran>] [BOTS=n] w2-summon-check.sh <steer|loot>
#   out:   farm/results/w2m-<pair>/result.txt (+ the two world logs and soak summaries next to it)
# Launch = soak.sh, flag line = COMMON + LIVE taken from farm/scenario.sh (so it is the same line as the A0 scenarios) + seed flags.
#
# steer pair (-Dbots.summoning.combatSteer): every bot Summoning 10 with spirit wolf + dreadfowl pouches and scrolls, started at
#   Edgeville bank (Barbarian Village, multi-combat, is the nearest multi hunt). NO -Dsmartbots.seed.hunt: a forced hunt returns
#   from chooseJob before the steer is asked. Both arms have combat + combat2 on; only the ON arm has combatSteer.
# loot pair (-Dbots.fight.lootAfter): every bot hunts cows from Draynor bank (the crowded, littered field of the W1 sc-v2
#   failure), combat + combat2 on in both arms so pouches are at stake; only the ON arm has lootAfter.
export JAVAC="${JAVAC:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/javac}" JAVA="${JAVA:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/java}" FARM_LAYOUT="<PROJECT-DIR>"
F="<PROJECT-DIR>"; PAIR="${1:?pair: steer | loot}"; B="${2:-$F/builds/w2m}"
MIN="${MIN:-60}"; BOTS="${BOTS:-24}"; T="${SCEN_TESTS:-$F/w2m-src/deployment/tests}"; R="$F/results/w2m-$PAIR"; S=smartbots.seed
FAM="-Dbots.summoning.combat=true -Dbots.summoning.combat2=true -D$S.summoning=10 -D$S.pouches=12047:4,12043:4,12445:30"
case "$PAIR" in
  steer) BASE="$FAM -D$S.at=${AT:-3094,3491,0}"; ONF="-Dbots.summoning.combatSteer=true"; WOFF="${WOFF:-w01}"; WON="${WON:-w02}" ;;
  loot)  BASE="$FAM -D$S.hunt=${HUNT:-Cow} -D$S.at=${AT:-3092,3245,0}"; ONF="-Dbots.fight.lootAfter=true"; WOFF="${WOFF:-w03}"; WON="${WON:-w04}" ;;
  *) echo "STOP: unknown pair $PAIR"; exit 1 ;;
esac

n() { grep -ac -- "$1" "$2"; }
ne() { grep -acE -- "$1" "$2"; }
verdict() { if [ "$1" = 1 ]; then echo "PASS $2"; else echo "FAIL $2"; fi; }
ge() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 >= b + 0) }'; }   # ge A B: A >= B (decimals)
# One line of numbers from the [KillRate] lines of a log:
# trips kills attacks loot famKills zeroAttack zeroAttackWithLoot pouchTrips pouchNoFight pouches fightMin
kr() {
  tr -d '\r' < "$1" | awk '
    function v(k,   s) { if (match($0, " " k "=[^ ]*")) { s = substr($0, RSTART + length(k) + 2, RLENGTH - length(k) - 2); return s + 0 } return 0 }
    /^\[KillRate\] / { t++; k += v("kills"); a += v("attacks"); l += v("loot"); f += v("famKills"); p = v("pouches"); pn += p; fm += v("fightMin")
      if (v("attacks") == 0) { z++; if (v("loot") > 0) zl++; if (p >= 1) pz++ }
      if (p >= 1) pt++ }
    END { printf "%d %d %d %d %d %d %d %d %d %d %.1f\n", t, k, a, l, f, z, zl, pt, pz, pn, fm }'
}
perbh() { awk -v c="$1" -v m="$MIN" -v b="$BOTS" 'BEGIN { printf "%.2f", (m > 0 && b > 0 ? c * 60 / m / b : 0) }'; } # per bot-hour
sumfield() { tr -d '\r' < "$2" | grep -a -- "$3" | grep -ao " $1=[0-9]*" | cut -d= -f2 | awk '{ s += $1 } END { print s + 0 }'; }

checks() { # OFFL ONL
  local OFFL="$1" ONL="$2" o x
  read -r oT oK oA oL oF oZ oZL oPT oPZ oPN oFM <<< "$(kr "$OFFL")"
  read -r xT xK xA xL xF xZ xZL xPT xPZ xPN xFM <<< "$(kr "$ONL")"
  local oSeed xSeed oRef xRef; oSeed=$(n '^\[Seed\] .* #[0-9]* half=' "$OFFL"); xSeed=$(n '^\[Seed\] .* #[0-9]* half=' "$ONL"); oRef=$(n '^\[Seed\] refused' "$OFFL"); xRef=$(n '^\[Seed\] refused' "$ONL")
  verdict $(( oSeed > 0 && xSeed > 0 && oRef == 0 && xRef == 0 )) "G0 seed: OFF $oSeed bots, ON $xSeed bots seeded, refused lines $oRef / $xRef"
  verdict $(( oT >= 10 && xT >= 10 )) "G1 enough data: [KillRate] trips OFF $oT, ON $xT (need 10+ each, else INCONCLUSIVE: run longer)"
  local oSum xSum oPlan xPlan oEx xEx oDeath xDeath oStuck xStuck
  oSum=$(n '^\[Summon\] .* summoned a .* for combat at ' "$OFFL"); xSum=$(n '^\[Summon\] .* summoned a .* for combat at ' "$ONL")
  oPlan=$(n '^\[Scen\] .* combat plan pouch=' "$OFFL"); xPlan=$(n '^\[Scen\] .* combat plan pouch=' "$ONL")
  oEx=$(ne '(Exception|Error).*(FightRules|steer|lootOk)|(FightRules|steerOwner|steerMulti|lootOk).*(Exception|Error)' "$OFFL"); xEx=$(ne '(Exception|Error).*(FightRules|steer|lootOk)|(FightRules|steerOwner|steerMulti|lootOk).*(Exception|Error)' "$ONL")
  oDeath=$(n '^\[SmartAdv\] DEATH ' "$OFFL"); xDeath=$(n '^\[SmartAdv\] DEATH ' "$ONL"); oStuck=$(n '\[Watchdog\].*stuck at' "$OFFL"); xStuck=$(n '\[Watchdog\].*stuck at' "$ONL")
  case "$PAIR" in
    steer)
      local oSt xSt xPick xSpot; oSt=$(n '^\[Steer\] ' "$OFFL"); xSt=$(n '^\[Steer\] ' "$ONL"); xPick=$(n '^\[Steer\] .* picks ' "$ONL"); xSpot=$(n '^\[Steer\] .* spot ' "$ONL")
      verdict $(( oSt == 0 )) "S1 flag off is silent: [Steer] lines in OFF $oSt (want 0)"
      verdict $(( xPick + xSpot >= 3 )) "S2 steer engaged: ON [Steer] picks $xPick + spot swaps $xSpot (want 3+; 0 = no multi-combat fight job in reach of the seeded bots, see the note)"
      verdict $(( xPlan >= oPlan + 3 && xPlan * 2 >= oPlan * 3 )) "S3 more pouch trips: combat plans ON $xPlan vs OFF $oPlan (want ON >= OFF + 3 and >= 1.5 x OFF)"
      verdict $(( xSum >= 3 && xSum > oSum )) "S4 more combat summons: ON $xSum vs OFF $oSum (want ON >= 3 and > OFF; the 3 h live read had 8)"
      verdict $(( xF >= 1 )) "S5 a familiar's own kill seen: famKills ON $xF (OFF $oF) (want 1+; first in-world sighting)"
      ge "$(perbh "$xK")" "$(awk -v o="$(perbh "$oK")" 'BEGIN { print o * 0.8 }')"; verdict $(( $? == 0 )) "S6 kills not worse: [KillRate] kills per bot-hour ON $(perbh "$xK") vs OFF $(perbh "$oK") (want ON >= 0.8 x OFF; kills ON $xK / OFF $oK)"
      verdict $(( xDeath <= oDeath + 2 )) "S7 deaths: ON $xDeath vs OFF $oDeath (want ON <= OFF + 2: multi-combat spots hit harder)"
      echo "INFO fights the familiar joined (last tally): ON $(grep -a '^\[Summon\] tally' "$ONL" | tail -1 | grep -o 'fightsJoined=[0-9]*') OFF $(grep -a '^\[Summon\] tally' "$OFFL" | tail -1 | grep -o 'fightsJoined=[0-9]*')"
      echo "INFO $(grep -a '^\[FightRules\] tally' "$ONL" | tail -1 | tr -d '\r' | cut -c1-200)" ;;
    loot)
      local oLA xLA xHeldFirst; oLA=$(n '^\[LootAfter\] ' "$OFFL"); xLA=$(n '^\[LootAfter\] ' "$ONL"); xHeldFirst=$(sumfield heldBeforeFirstAttack "$ONL" '^\[LootAfter\] ')
      verdict $(( oLA == 0 )) "L1 flag off is silent: [LootAfter] lines in OFF $oLA (want 0)"
      verdict $(( xLA >= 1 && xHeldFirst >= 1 )) "L2 rule engaged: ON [LootAfter] lines $xLA, steps held before the first attack $xHeldFirst (want 1+ each)"
      verdict $(( xZL == 0 )) "L3 no loot before a fight: ON trips with attacks=0 and loot>0: $xZL (want 0; OFF had $oZL of $oT trips)"
      verdict $(( xPZ == 0 )) "L4 no pouch burned without a fight: ON trips with pouches>=1 and attacks=0: $xPZ of $xPT pouch trips (want 0; OFF $oPZ of $oPT)"
      ge "$(perbh "$xK")" "$(perbh "$oK")"; verdict $(( $? == 0 )) "L5 kill rate: [KillRate] kills per bot-hour ON $(perbh "$xK") vs OFF $(perbh "$oK") (want ON >= OFF; kills ON $xK / OFF $oK, attack orders ON $xA / OFF $oA)"
      verdict $(( xL * 2 >= oL )) "L6 loot not gutted: loot= summed ON $xL vs OFF $oL (want ON >= 0.5 x OFF; less than OFF is expected, other bots' drops are no longer hoovered)"
      verdict $(( xStuck <= oStuck + 2 )) "L7 no new stalls: watchdog stuck ON $xStuck vs OFF $oStuck (want ON <= OFF + 2)"
      echo "INFO zero-attack trips: ON $xZ of $xT, OFF $oZ of $oT; combat summons ON $xSum, OFF $oSum; famKills ON $xF, OFF $oF"
      echo "INFO $(grep -a '^\[FightRules\] tally' "$ONL" | tail -1 | tr -d '\r' | cut -c1-200)" ;;
  esac
  verdict $(( oEx == 0 && xEx == 0 )) "G2 no exception naming the new code: OFF $oEx, ON $xEx"
  echo "INFO [KillRate] OFF: trips $oT kills $oK attacks $oA loot $oL famKills $oF pouches $oPN fightMin $oFM | ON: trips $xT kills $xK attacks $xA loot $xL famKills $xF pouches $xPN fightMin $xFM"
  echo "INFO health OFF: deaths $oDeath, stuck $oStuck, exception lines $(ne 'Exception|Throwable' "$OFFL") | ON: deaths $xDeath, stuck $xStuck, exception lines $(ne 'Exception|Throwable' "$ONL")"
}

if [ -n "${SUMMARY_ONLY:-}" ]; then
  [ -f "${OFFLOG:-}" ] && [ -f "${ONLOG:-}" ] || { echo "STOP: SUMMARY_ONLY needs OFFLOG and ONLOG"; exit 1; }
  checks "$OFFLOG" "$ONLOG"; exit 0
fi

[ "$(date -u +%s)" -ge "$(date -u -d '2026-11-19 04:30:00' +%s)" ] || { echo "STOP: no worlds before 04:30 UTC 2026-11-19 (now $(date -u +%FT%TZ))"; exit 1; }
[ -d "$B/content" ] && [ -d "$B/core" ] || { echo "STOP: build $B is empty"; exit 1; }
[ -f "$B/content/custom/FightRules.class" ] && [ -f "$B/content/custom/Seed.class" ] || { echo "STOP: FightRules.class / Seed.class missing in $B"; exit 1; }
[ -f "$T/soak.sh" ] || { echo "STOP: no soak.sh in $T"; exit 1; }
[ "$WOFF" != "$WON" ] || { echo "STOP: the two arms need two worlds"; exit 1; }
mkdir -p "$R/locks"
for W in "$WOFF" "$WON"; do
  case "$W" in w0[1-6]) ;; *) echo "STOP: world $W not ours"; exit 1;; esac
  powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ? {\$_.CommandLine -match 'worlds.$W'}) {exit 1}" || { echo "STOP: java already on $W"; exit 1; }
  powershell -NoProfile -Command "if (Get-CimInstance Win32_Process | ? {\$_.Name -match 'bash' -and \$_.CommandLine -match '(soak|run)\.sh.* $W '}) {exit 1}" || { echo "STOP: an older soak/run loop still names $W"; exit 1; }
  for d in "$F"/results/*/locks; do [ -f "$d/$W" ] && [ "$d" != "$R/locks" ] && { echo "STOP: $d/$W lock"; exit 1; }; done
done
for W in "$WOFF" "$WON"; do echo "w2m-$PAIR $(date -u +%FT%TZ), $MIN min" > "$R/locks/$W"; done
trap 'rm -f "$R/locks/$WOFF" "$R/locks/$WON"' EXIT

eval "$(grep -E '^(COMMON|LIVE)=' "$F/scenario.sh")"   # the A0 scenario flag line (COMMON reads $BOTS)
[ -n "$COMMON" ] && [ -n "$LIVE" ] || { echo "STOP: COMMON / LIVE not found in $F/scenario.sh"; exit 1; }
export SEED="${SEED:-$F/seed/p42}" RUN="w2m-$PAIR"
T0=$(date +%s)
( cd "$T" && bash soak.sh "$WOFF" "$B" "$MIN" "w2m-$PAIR-off" $COMMON $LIVE $BASE ${EXTRA:-} > "$R/soak-off-$WOFF.out" 2>&1 ) & P1=$!
sleep 20
( cd "$T" && bash soak.sh "$WON" "$B" "$MIN" "w2m-$PAIR-on" $COMMON $LIVE $BASE $ONF ${EXTRA:-} > "$R/soak-on-$WON.out" 2>&1 ) & P2=$!
wait $P1; RC1=$?; wait $P2; RC2=$?
OFFL="$R/soak-w2m-$PAIR-off-$WOFF.log"; ONL="$R/soak-w2m-$PAIR-on-$WON.log"
[ -f "$OFFL" ] && [ -f "$ONL" ] || { echo "FAIL a world log is missing ($OFFL rc $RC1, $ONL rc $RC2; see $R/soak-*.out)" | tee "$R/result.txt"; exit 1; }
RAN=$(grep -a '^ran: ' "$R/soak-w2m-$PAIR-on-$WON.txt" 2>/dev/null | grep -o '[0-9]* min' | cut -d' ' -f1); [ -n "$RAN" ] && [ "$RAN" -gt 0 ] && MIN=$RAN
{
  echo "w2m $PAIR pair: OFF $WOFF, ON $WON, build $B, $BOTS bots each, $MIN min, started $(date -u -d @$T0 +%FT%TZ), soak rc $RC1 / $RC2 (4 = coin-drop alarm, 5 = deals: not a gate)"
  echo "flags both: $BASE ${EXTRA:-}"; echo "flags ON only: $ONF"
  checks "$OFFL" "$ONL"
} > "$R/result.txt"
cp "$R/result.txt" "$R/result-$(date -u -d @$T0 +%m%dT%H%MZ).txt"
cat "$R/result.txt"
grep -q '^FAIL' "$R/result.txt" && exit 2; exit 0
