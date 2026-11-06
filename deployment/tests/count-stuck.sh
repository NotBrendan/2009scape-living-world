#!/bin/bash
# usage: count-stuck.sh <soak log>  -> the stuck-review pass counts (the design notes "Farm test"); used for Batch 26 (the design notes)
L="$1"
echo "rescues (Watchdog stuck at): $(grep -ac '^\[Watchdog\] .* stuck at' "$L")"
echo "  to the bank: $(grep -a '^\[Watchdog\] .* stuck at' "$L" | grep -c 'teleported to the bank')  trip finished (paid): $(grep -ac '^\[Watchdog\] .*to finish the trip' "$L")"
echo "  by path: $(grep -ao '^\[Watchdog\] .* stuck at.*path [a-z]*' "$L" | grep -o 'path [a-z]*$' | sort | uniq -c | tr '\n' ' ')"
echo "  loop rescues (old line: for 5 min): $(grep -a '^\[Watchdog\] .* stuck at' "$L" | grep -c ' for 5 min')"
echo "Got stuck finishes: $(grep -ac 'finished: Got stuck' "$L")   Couldn't find a path: $(grep -ac "finished: Couldn't find a path" "$L")"
echo "paid stall teleports: $(grep -ac 'paid stall teleport' "$L")   paid rescue-trip: $(grep -ac '^\[Paid\] .* rescue-trip' "$L")"
echo "NavShadow outcomes: $(grep -a '^\[NavShadow\] outcome' "$L" | sed -E 's/.*: (arrived|not arrived|teleported|redirected) after.*/\1/' | sort | uniq -c | tr '\n' ' ')"
echo "leg cooldowns set: $(grep -ac " won't walk to " "$L")   jobs skipped no route (distinct): $(grep -ac '^\[Jobs\] no planned route' "$L")"
echo "jobs chosen: $(grep -ac '^\[SmartAdv\] .* -> ' "$L")"
echo "worst (bot) rescues: $(grep -a '^\[Watchdog\] .* stuck at' "$L" | awk '{print $2}' | sort | uniq -c | sort -rn | head -5 | tr '\n' ' ')"
echo "worst (bot, job) rescues: $(grep -a '^\[Watchdog\] .* stuck at' "$L" | sed -E 's/^\[Watchdog\] ([^ ]+) .*\(off getting ([^)]*)\).*/\1|\2/' | grep '|' | sort | uniq -c | sort -rn | head -3 | tr '\n' ' ')"
echo "top tiles: $(grep -ao 'stuck at \[[0-9]*, [0-9]*' "$L" | sed 's/stuck at \[//; s/, /,/' | sort | uniq -c | sort -rn | head -8 | tr '\n' ' ')"
for c in "3145 3152 3294 3300 farmers" "3135 3140 3330 3350 x" ; do :; done
box() { grep -ao 'stuck at \[[0-9]*, [0-9]*' "$L" | sed 's/stuck at \[//; s/, / /' | awk -v a=$1 -v b=$2 -v c=$3 -v d=$4 '$1>=a&&$1<=b&&$2>=c&&$2<=d' | wc -l; }
echo "clusters: farmers(3145-3152,3294-3300) $(box 3145 3152 3294 3300) Harry(2834-2838,3445-3449) $(box 2834 2838 3445 3449) hillgiants(3094-3098,9865-9869) $(box 3094 3098 9865 9869) snails(3506-3510,3446-3450) $(box 3506 3510 3446 3450) darkwiz(3180-3196,3335-3346) $(box 3180 3196 3335 3346) glock(3268-3282,3226-3242) $(box 3268 3282 3226 3242)"
# fix 3: TO_SPOT walks the planner put over 320 tiles that were then walked (the outcome is not "teleported" within 20 s)
echo "goTo TO_SPOT planned >320: $(grep -a '^\[NavShadow\] .* goTo TO_SPOT ' "$L" | grep -oE ': [0-9]+ tiles' | awk '$2>320' | wc -l) of $(grep -ac '^\[NavShadow\] .* goTo TO_SPOT ' "$L") TO_SPOT goTos; NO ROUTE $(grep -a '^\[NavShadow\] .* goTo TO_SPOT ' "$L" | grep -c 'NO ROUTE')"
echo "farmer trips: $(grep -a 'finished:' "$L" | grep -ic 'farmer') finished, collected>0 $(grep -a 'finished:' "$L" | grep -i farmer | grep -vc '(0 collected')"
