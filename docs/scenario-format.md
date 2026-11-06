# Scenario format

The code is `content/custom/Scenarios.java`; its class comment is the short version of this file.

A **scenario** is one walk that went wrong on live (or a walk nobody has proven yet): a start tile, a destination, the walker that was used and why it is interesting. **Rescue replayers** (`-Dsurvey.replay`, default 3) walk scenarios again and write a **result**. The spare-PC farm reads and writes the same two files.

## Files and paths

| file | what | written by |
|---|---|---|
| `data/survey/scenarios.jsonl` | one scenario per line, appended, never rewritten | `Scenarios.record` / `Scenarios.put` (5 s background flush) |
| `data/survey/scenario-results.jsonl` | one result per line, appended | the replayers (`Replayer.end`) |

- In the server: `-Dscenarios.dir=<folder>` moves `scenarios.jsonl`, and `-Dscenarios.results.dir=<folder>` moves the results (default: the scenarios folder, else `data/survey`).
- Farm tooling (scripts outside the server) reads its paths from `<farm root>\farm\layout.json` when that file exists (`scenarios`, `results`, `worlds`, `liveSeed`). Never hardcode the farm root. Without a layout, it uses the harness world's `qt/game/data/survey`.
- Lines are UTF-8 JSON objects. A line that doesn't parse is skipped on read (`Scenarios.readLines`), so a torn last line after a crash costs one record and nothing else.

## Scenario record (`scenarios.jsonl`)

```json
{"v":1,"t":1792900000000,"id":"rescue|shop|408,403,0|405,401,0","source":"rescue","walker":"shop",
 "activity":"walking to Gerrant's Fishy Business","from":[3267,3227,0],"to":[3014,3224,0],"stuckAt":[3268,3228,0],
 "reason":"stuck 5 min in a learned trap cell, shop walk, planner route","profile":{"combat":41,"agility":12,"coins":830}}
```

| field | type | meaning |
|---|---|---|
| `v` | int | format version, 1 |
| `t` | long | epoch ms when it was recorded |
| `id` | string | `source\|walker\|fromCell\|toCell`. A cell is `x>>3,y>>3,z` (an 8x8 tile block), so near-identical trips share an id |
| `source` | string | why it exists, one of the sources below |
| `walker` | string | the code path that walked it: `afk`, `shop`, `quest`, `grave`, `trader`, `crew`, `party`, `other` |
| `activity` | string | the bot's activity line at the time (`BotChat.activityOf`), `""` if none |
| `from`, `to` | [x,y,z] | where the walk started, and its destination |
| `stuckAt` | [x,y,z] or null | where it went wrong (rescue tile, stall tile, where a redirect happened) |
| `reason` | string | short text: the issue this scenario proves |
| `profile` | object | `combat`, `agility`, `coins` of the bot at the time, `-1` when unknown (seeds) |

Rules on writing (`Scenarios.put`):
- `from` or `to` null: the record is dropped. A rescue whose goal Watchdog can't work out leaves no scenario; it still counts in the daily report.
- Dedupe: one line per id per UTC day. The same id is accepted again that day only after a pass or fail result for it.
- Cap: 500 records per UTC day, counting all sources except `prove` and seeds. NavWalker's `no_route` and `redirect` also have their own cap of 60 an hour (`-Dnav.scenPerHour`), so they can't crowd out rescues.
- `record()` is safe from any thread and never throws. On the game thread it is a map put plus a queue add.

### Sources, one example each

| source | recorded by | when | example `reason` |
|---|---|---|---|
| `rescue` | `Watchdog.rescue` | Watchdog teleports a stuck bot out (taken before the teleport) | `stuck 20 min, afk WORK, planner no route` |
| `never_reached` | `SmartAdventurers.shopTick`; also `seedProve` for the three known shops | a shop walk passed its deadline, or couldn't cross the sea | `not there by the deadline, closest 14 tiles, 3 stalls` |
| `failed_trip` | `SmartAdventurers.graveTick` (walker `grave`), `AfkTasks.Runner.walk` (walker `afk`, bots only) | a grave run timed out or the stone crumbled first; an AFK walk ended "Couldn't find a path" | `grave run timed out 37 tiles short` / `no progress after 5 tries walking to the spot, 22 tiles short, last 'step 25 to 3100,3240'` |
| `idle_giveup` | `Watchdog.idle` | a bot stood still with nothing to show it was trying, and gave up its job | `idle 5 min in a learned trap cell, giving up that job` |
| `quest_fail` | `QuestRunner` | a Walk/Buy/Collect/Kill/Gather step failed on "walk to ..." or the time limit | `romeo-juliet step 4 Walk to Juliet: time limit` |
| `no_route` | `NavWalker` (adventurers, traders, herb bot only) | the planner found no route (first plan, stall replan or next stretch); one per bot and destination cell per 10 min | `no surface route (shop, first plan)` |
| `redirect` | `NavWalker` (same bots) | a new destination while a route to an unreached old one (>4 tiles off) was live | `a new goal 50+ tiles away, new goal [3092, 3245, 0]` |
| `prove` | `Scenarios.seedProve` (`-Dsurvey.replay.prove`, on by default) | at startup: surface shops, banks and AFK job spots with no result yet; start = nearest bank, or a walked tile 150 tiles towards it | `prove: walk to the shop Lowe's Archery Emporium` |
| `seed` | tests and farm tooling | hand-made scenarios (e.g. TestB18F's Crandor and Draynor trips) | `seeded failing trip (water, no crossing)` |

Any other source (e.g. TestB18F's `captest` fillers) is stored but never replayed.

## The to-do list and pass rules

- **Proven** = the id has a `pass`, or a `fail` with its stall tile, at or after the scenario's newest record. A proven scenario is never replayed again, unless the same id is recorded again after that result (it went wrong on live again). This is the "never retrace proven steps" rule. Results load at startup from `scenario-results.jsonl`, so it holds across restarts and for farm worlds seeded with the live files.
- **Three daily timeouts = fail** (batch 18 rc3): `gave_up` on 3 different UTC days with no `pass` or `fail` in between closes the scenario as a proven `fail` (no `failAt`; the reason shown is "gave up 3 days running"). `skipped` does not break the run. Nothing extra is written: the rule is worked out from `scenario-results.jsonl` (in time order) at load and on each new result (`[Replay] <id> closed as fail: gave up 3 days running`). Like any proven scenario it comes back only when the same id is recorded again on live; a pass or fail resets the count, and so does a live re-record after the last `gave_up` (owner's decision, 2026-10-25): a scenario that went wrong on live again gets a fresh 3-day run.
- Order: live sources first (`rescue`, `never_reached`, `failed_trip`, `idle_giveup`, `quest_fail`), then `no_route`, `redirect`, `seed`, then `prove`. Newest first within each group.
- Fair queue (batch 18 rc3): every 4th pick (one counter shared by all replayers, on the game thread) takes the **oldest** eligible scenario of any group instead, so old scenarios are not starved by a steady flow of new ones.
- One attempt per scenario per UTC day. `skipped` is not an attempt: it is retried after 15 minutes. "Across the sea" (a crossing that costs coins) waits 24 h.
- A replay walks from `from` to `to`:
  - `pass`: arrived within 2 tiles (shop walks: within 5, or within 9 after 2 stalls).
  - `fail`: no progress for 60 s (100 ticks; new 4x4 ground counts as progress, so a detour round a river is fine). `failAt` = where it stalled.
  - `gave_up`: not there after 10 minutes (1000 ticks).
  - `skipped`: a real player within 20 tiles of the replayer, the start or the destination (checked every tick); attacked (the replayer logs out instead of fighting; this is now rare: from its teleport to the start until its result the replayer carries the engine's `ignore_aggression` attribute, which `AggressiveHandler.selectTarget` honours, so aggressive monsters don't pick it. Only replayers get it, and it is removed at each result and at retirement); coins or items changed; the Al Kharid toll gate (coin neutral: no tolls); could not land at the start.
- The replayer is teleported to `from` before the walk, and never during it. It uses the scenario's walker: AFK walks run `AfkTasks.Runner` stepping without its teleports, bank walk or selling. Shop walks run the shop walk without teleports, food or buying. Everything else uses NavWalker, then the learned routes, then a straight 25-tile hop. Agility and melee levels are set to the scenario's profile and put back afterwards. Coins are never given; a coin mismatch is noted in `nav`.
- Replayers are throwaway bots: never saved, not watched by Watchdog. At most 3 at a time (`Math.min(3, survey.replay)`). A replayer retires when nothing is left to replay.

## Result record (`scenario-results.jsonl`)

```json
{"v":1,"t":1792900600000,"id":"rescue|shop|408,403,0|405,401,0","bot":"Replayer12","result":"fail","ticks":412,
 "failAt":[3268,3228,0],"why":"no progress for 60 s, 31 tiles short",
 "nav":"shop walker; nav x18, via # x2, route stuck x1; planner route 6 hops; profile agility 12 (scenario 12), combat 41 (scenario 41); closest 29 tiles","lag":600}
```

| field | type | meaning |
|---|---|---|
| `v`, `t` | int, long | version 1; epoch ms of the result |
| `id` | string | the scenario id |
| `bot` | string | the replayer's name |
| `result` | string | `pass`, `fail`, `gave_up`, `skipped` |
| `ticks` | int | ticks spent walking (0.6 s each) |
| `failAt` | [x,y,z] or null | the stall tile, for `fail` |
| `why` | string | short reason (`arrived`, `no progress for 60 s, N tiles short`, the skip reason) |
| `nav` | string | trace summary: walker, step labels with counts (numbers masked as `#`), planner notes, the profile used, the closest approach |
| `lag` | long | seconds from the scenario's record (`t`) to this result. The replay queue target is a first result within about 15 min on a quiet server |

Log lines: `[Replay] queued <id> <source> from x,y,z to x,y,z` at record time, and `[Replay] result <id> <result> after <ticks>, lag <s> s[ (stalled at x,y,z)]: <why>`. The daily stuck report (`data/reports/stuck-YYYY-MM-DD.md`) shows replay pass/fail/gave_up/skipped and the median `lag` (read from `Scenarios.resultsFile()`, so `-Dscenarios.results.dir` is honoured), and a section **Failed replays (named bugs)**: one row per scenario whose latest result that UTC day (skips aside) is a `fail`, or the `gave_up` that closed it under the 3-day rule. Columns: time (UTC), id, source, walker, from -> to, failAt, why ("gave up 3 days running (...)" for the 3-day rule), nav (cut to 120 characters). Newest first, at most 40 rows. Source and walker come from the in-memory index, else from `scenarios.jsonl`.
