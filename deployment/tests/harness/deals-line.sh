#!/bin/bash
# Batch 24b item 4: the soak DEALS line from data/botchat-deals.log (Bargain.log format:
#   "<ISO instant> SALE|WALK|BUY bot=<name> player=<name> list=N floor=N final=N disc=N tries=N strikes=N tier=N").
#   usage: deals-line.sh <since ISO, e.g. 2026-11-02T12:50:00> <deals log files...>   (missing files are skipped)
#   Prints one line "DEALS PASS|FAIL: ..." and exits 5 when any SALE final is under its floor or over its list (design 6.3).
SINCE="$1"; shift
cat "$@" 2>/dev/null | tr -d '\r' | awk -v since="$SINCE" '
  $1 < since { next }
  { k = $2; n[k]++ }
  k == "SALE" && match($0, / list=[0-9]+ floor=[0-9]+ final=[0-9]+ disc=[0-9]+/) {
    split(substr($0, RSTART + 1, RLENGTH - 1), f, /[ =]/); l = f[2] + 0; fl = f[4] + 0; fi = f[6] + 0
    if (fi < fl || fi > l) { bad++; if (!first) first = $0 }
    disc += f[8]; list += l; next
  }
  k == "SALE" { bad++; if (!first) first = "unparsed: " $0 }
  END {
    v = bad ? "FAIL" : "PASS"
    printf "DEALS %s: sales %d, walk-aways %d, buys %d, discount %d gp on %d gp listed, bad finals (under floor or over list) %d%s\n", v, n["SALE"], n["WALK"], n["BUY"], disc, list, bad, (first ? "; first bad: " substr(first, 1, 300) : "")
    exit bad ? 5 : 0
  }'
