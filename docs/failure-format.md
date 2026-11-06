# Failure records and bugs

Code: `content/custom/Failures.java`. Tools: `deployment/tests/tools/bugs.py` (read-only), `deployment/tests/survey-merge.py`
(bugs.json merge). This is **logging only**: nothing here changes what a bot does.

Files (all under the scenarios results folder, `data/survey` on live):

| file | written by | mode | cap | kept |
|---|---|---|---|---|
| `failures/YYYY-MM-DD.jsonl` (UTC day) | the `SurveyWriter` thread (a `SurveyWriter.task` serialises and trims, then `append`) | append, one JSON object per line | 120 records/hour, 1,500/day (all domains), per-domain shares inside, `replay_skip` 10/hour, exceptions 1 per (where, class) per 5 min and 200/day, one per bot per source per 60 s, 4,096 bytes a line | 14 daily files |
| `bugs.json` | the `SurveyWriter` thread (one `task` reads, merges and `Ledger.writeAtomic`s) | load-merge-save, atomic (`Ledger.writeAtomic`), at most once a minute when dirty and at shutdown | 2,000 entries (per-domain shares inside) | non-open entries untouched for 90 days and without a note are pruned when over the cap; open entries never |

A record over a cap is not written, but its counters still move: `Watchdog.Daily` notes `fail` = reason (the bare code; codes are
unique across domains), `bug` = bug id, `exc` = where TAB simple class TAB first frame (exceptions), and `failcap` = src for a record
the caps dropped; plus the bug's `count`/`days`/`sources` in bugs.json. A line that does not parse is skipped
by every reader.

## 1. Domains (generic format, owner 2026-10-25 16:33)

Every record and every bug entry has `domain`: `path`, `economy`, `quest`, `combat`, `skilling`, `lifecycle`, `health`, `save`.
Batch 19a emits `path` (trip failures) and `health` / `lifecycle` (exceptions: `lifecycle` when the catch site's `where` starts with
startup, shutdown, login or logout).

- **Reason codes belong to a domain.** `path` has the 14 codes of §4 below and they are the only ones the geometric classifier
  derives. `health` and `lifecycle` have `exception`. Every other code is chosen by its domain's detector at the hook (batch 19c) and
  is unique across domains, so a code names its domain. `unknown` exists in every domain and never groups into a bug.
- **Core keys** mean the same in every domain: `v t fid domain src sid bot snap reason rsub bug why log exc ctx`. A domain adds its
  own keys (path: the trip keys of §2; other domains: an optional `ctx` object). Readers skip keys they do not know, so a new
  domain needs no format change.
- **Bug id** = `"b-" + reason + "-" + key`. The domain is a field of the bug entry, never part of the id (old path ids keep joining).
  Path's key is the 16x16 cluster (§5); another domain's key is its detector's slug (batch 19c §3.3; the stall cluster when a
  detector gives none); `exception` keeps `b-exception-<where>-<SimpleClassName>` whatever the domain.
- **Caps** are global (120/h, 1,500/day) with a share per domain inside them. 19a: path 120/1,500 (the whole budget; 19c lowers it to
  60/600), economy 30/300, quest 15/150, combat 15/150, skilling 15/150, lifecycle 10/80, health 5/40, save 5/30. Exceptions are held by
  their own throttle and 200/day instead of health's share. Trials: `-Dfailures.cap.<domain>=perHour,perDay`,
  `-Dfailures.capHour`, `-Dfailures.capDay`. Throttle: one record per (bot, source) per 60 s for path; per (bot, reason) outside path
  (10 min for economy, skilling, lifecycle).
- **Report**: the daily stuck report's sections are `Watchdog.Daily.failures()` (Builder B): it reads the Daily notes above and bug
  details from `bugs.json` on disk (so this file's format is a contract). `Failures.report(day)` renders the same sections with a
  `domain` column (the 19c layout) for tests and later use; 19c adds per-domain blocks.

## 2. The record (`failures/YYYY-MM-DD.jsonl`)

Key order as written. `[x,y,z]` arrays; null when unknown.

| key | domains | meaning |
|---|---|---|
| `v` | all | format version, 1 |
| `t` | all | epoch ms |
| `fid` | all | `f-<yyyymmdd>-<n>`, unique per day ([Fable] n starts at seconds-since-midnight x 10 at boot, so a restart does not reuse ids); also in the scenario record and the replay result |
| `domain` | all | §1 |
| `src` | all | path: `rescue`, `idle_giveup`, `never_reached`, `failed_trip`, `quest_fail`, `replay_fail`, `replay_timeout`, `replay_skip`; exceptions: `exception` |
| `sid` | all | scenario id `source\|walker\|fromCell\|toCell` (≤ 80), null when none |
| `bot` | all | bot name; never a real player (`"-"` for a server-level record or an exception involving a real player) |
| `snap` | all with a bot | the bot snapshot, §3 |
| `walker`, `dtype`, `from`, `to`, `drv` | path | walker (`afk shop quest grave trader crew party replay other`), destination type, trip start and goal, the driver at the stall (`planner routemap old climber watchdog flee none`) |
| `plan` | path | `{"ans":"route\|no-route\|outside\|off\|none","len","hops","hop","age","replans","why","next":[x,y,z]}`; `age` = seconds since the driver tag was set; `next` = the hop the driver was walking to; `split` = that hop is in another NavPlanner grid component than the bot |
| `path` | path | the last ≤ 32 tiles, newest last: `x,y>x,y`, `,z` only when the plane changes (or on the first tile when not 0), `~>` before a jump of more than 20 tiles |
| `stall` | path; others when the bot has a tile | where it was at the failure |
| `near` | path | `{"objs":[{"id","n","at","st":"closed\|open\|solid\|walk","ty","rule"}],"npcs":[{"id","n","at","agg","lvl"}],"run":n}`: objects of the 7x7 tiles and NPCs within 3, same plane, nearest first, ≤ 12 / 8; `ty` = object type (< 4 wall/door); `rule` = the Gates `need()` text when a gate box covers the object (Al Kharid 2882/2883: "10 gp toll"); `run` = samples in a row with an NPC beside a standing bot |
| `combat` | path | `{"in","hits","h60","died","poison","since"}`: hits = lifepoint drops seen this trip, h60 in the last 60 s, since = seconds since last in combat (-1 never) |
| `mv` | path | what the rules read from the ring: `d24`/`d29` distinct tiles in the last 24/29 s, `m29` moves in 29 s, `still` seconds since the last move, `px16` a plane change in the last 16 tiles, `near3` within 3 tiles of the hop for the whole last 60 s, `wall` a NavGrid wall between stall and hop, `redir2` two redirects between the same two goals in the log |
| `gate` | path, when one applies | `{"id","need","unmet","box"}`: the Gates rule (or the Al Kharid toll) at the stall, a near object or the goal; `unmet` = this bot fails it (a replayer never pays a toll); `box` = the stall is inside a gate box |
| `stuckS`, `tripS` | path | seconds since the Watchdog anchor (else `mv.still`); seconds since the trip started (-1 unknown) |
| `log` | all | the bot's last ≤ 12 log lines (≤ 160 chars), oldest first: `walk: ` = AfkTasks `Runner.last` changes, `replay: ` = replayer labels, others as the hook wrote them |
| `ctx` | 19c domains | the domain's own fields (≤ 700 B); `"trimmed"` when the line cap removed it |
| `reason`, `rsub` | all | the code and the detail it matched (≤ 40) |
| `bug` | all but unknown | the bug id |
| `why` | all | the source's own text (≤ 160) |
| `exc` | exceptions | `{"cls","msg","where","frames":[≤ 6]}` |
| `trim` | when trimmed | what the 4,096-byte cap removed, in order: `log` (to 6), `ctx`, `near` (6 objects, 4 NPCs), `path` (16 tiles), `inv` (4 notable), `frames` (3), `long-strings`, `log-all`, `near-all`. `reason`, `bug`, `stall`, `sid` and the snapshot's core are never dropped |

## 3. The bot snapshot (`snap`)

`role` (`adventurer trader herb replayer explorer-* other`), `shift` (`on`/`over`/`-`), `cb`, `agi`, `job`/`jobLvl` (the AFK task's
skill), `hp` [current, max], `run` 0-100, `pray` [current, static] (grave and quest walks only), `qp`, `quests` (quest gates
at the start, goal or stall: name -> done, ≤ 6), `coins`, `inv` `{"n","food","tele","notable":[≤ 6 "name xN"]}`, `eq` `{"wpn","n"}`,
`combat`, `poison`, `goal`, `step` (Watchdog.goal: walker, kind, last word; planner state), `tripS`, `deaths`, `relog`.
Built on the game thread from the bot object itself; nothing from saves or real players.

## 4. Path reason codes (first match wins; `bugs.py --reclassify` re-derives them from the line)

`tgt` = `plan.next`, else `to` (none when on another plane). Neighbour = the stall's tile one step toward `tgt`.

| # | code | rule (on the record's fields) |
|---|---|---|
| 1 | `death` | `combat.died`, or `why` says died / crumbled |
| 2 | `combat-interrupt` | `combat.in`, or `combat.h60 >= 3`, or `replay_skip` with "attacked" |
| 3 | `timeout` | `mv.d24 >= 10` and a deadline source (`replay_timeout`; `never_reached` "deadline"; `failed_trip` "timed out"; `quest_fail` "time limit") |
| 4 | `outside-grid` | `plan.ans == outside` and not `gate.box` |
| 5 | `rule-gate` | `gate.id` set and `gate.unmet` |
| 6 | `door-closed` | a `closed` object within 2 tiles, wall type or gate/door name, no `rule`, on the `tgt` side of the stall |
| 7 | `npc-blocking` | `near.run >= 3` and an NPC on the neighbour or on `plan.next` |
| 8 | `no-route` | `plan.ans == no-route`, or `plan.split` ([Fable] 19a: the planner's hop lies in another grid component than the bot, a stale route after a teleport; the unicorn pen) |
| 9 | `handoff` | `mv.px16`, or `drv == climber`, or the goal's plane is not the stall's |
| 10 | `wrong-side-hop` | `drv == planner`, `plan.next` ≤ 3 tiles, `mv.near3`, and `mv.wall` or a `solid` object between stall and hop |
| 11 | `oscillation` | `mv.d29 <= 4` and `mv.m29 >= 16`, or `mv.redir2` |
| 12 | `blocked-object` | no `closed` object within 2, and a `solid` object (no NPC on it) on the stall tile, on the line toward `tgt` (≤ 3 tiles; all 8 neighbours when there is no `tgt`) or on `plan.next` |
| 13 | `engine-pathfinder-giveup` | `drv == old` (or `routemap` with the hop > 2 tiles off) and `mv.still >= 60` |
| 14 | `unknown` | anything else (never a bug) |

`health`/`lifecycle`: `exception` (every exception record). Other domains: detector-decided (batch 19c).

## 5. Bugs (`bugs.json`)

| domain | id |
|---|---|
| path | `b-<reason>-<x>>4,<y>>4,<z>` of the stall; `no-route`, `outside-grid`, `timeout`: `b-<reason>-dest-<cluster of the goal>`; `rule-gate`: + `-<gate id>` (`alkharid-toll`, `morytania`, ...) |
| exception (any domain) | `b-exception-<where>-<SimpleClassName>` |
| economy, quest, combat, skilling, lifecycle, health, save | `b-<reason>-<detector key slug>` (batch 19c §3.3) |

```json
{"v":1,"updated":1793000000000,"host":"live","bugs":{"b-rule-gate-204,201,0-alkharid-toll":{"id":"b-rule-gate-204,201,0-alkharid-toll","domain":"path","reason":"rule-gate","rsub":"10 gp toll, coins 3","cell":[204,201,0],"place":"3264-3279, 3216-3231, 0 (Al Kharid gate)","first":1792950000000,"last":1793000000000,"count":213,"days":{"2026-10-24":180,"2026-10-25":33},"sources":{"idle_giveup":190,"rescue":23},"examples":["idle_giveup|shop|408,403,0|405,401,0"],"fids":["f-20261025-233"],"bots":["Hennssey251"],"snap":{"role":"adventurer","cb":41,"coins":3},"status":"open","statusT":1793000000000,"statusBy":"live","note":"","replays":[{"t":1793001000000,"world":"live","bot":"Replayer12","sid":"idle_giveup|shop|408,403,0|405,401,0","result":"skipped","fid":"f-20261025-240","why":"at the Al Kharid toll gate (coin neutral)"}]}}}
```

Caps per entry: `days` 14, `examples`/`fids`/`bots` 5 (newest), `replays` 10 (newest), `note` 500 chars; `snap` = the newest
example's. Exception entries add `"exc":{"where","cls","frame"}` (first frame) and `place` = where. Keys the server does not know are
kept as they are (a newer build's fields, or hand-added ones, survive a rewrite).

Status: `open`, `not-reproduced`, `fixed-in-batch-N`, `wont-fix`; `statusBy`: `live`, `farm`, `owner` (edited by hand).

Merge rules (`Failures.Bugs.save` and `survey-merge.py merge_bugs`, the same rules):

1. Counts are live-only (`count days sources examples fids bots first last snap`); a farm entry never changes them; farm-only ids
   are not carried into the merge package. The server's save takes, per entry, the larger of the file's and its last save's counts,
   then adds what it counted since, so a merge package with older counts cannot lower them; an id the file lacks is put back.
2. Replays: union by (`t`, `world`, `bot`), newest 10.
3. Status: owner > live > farm. `not-reproduced` and `fixed-in-batch-N` (when the build's `Failures.BATCH >= N`) go back to `open`
   on a new live record, note prefixed `reopened <date>: `; `wont-fix` never changes by machine; an owner status is changed only by
   that reopen rule. `open -> not-reproduced` when the newest 3 replays in 7 days are `pass` and no live record came since the
   oldest of those 3 ([Fable] reading of "no live record in those 7 days": since the passes started); the farm may only make that
   move when live did not record the bug after `pulled.txt`'s time, otherwise its proposal is added to `replays` as result
   `proposal` and the status stays.
4. Nothing live is lost: `survey-merge.py` exits 2 when a seed id is missing or its `count`, `first`, `note` or owner status changed.
5. The server writes by load-merge-save on its writer thread, so a merge package copied in while it runs is picked up, not
   overwritten. A `bugs.json` that does not parse is never saved over (the server keeps its counts in memory and retries).

## 6. Example lines (each one is a complete, valid line; `snap` and `log` shortened)

```
{"v":1,"t":1793000000000,"fid":"f-20261026-17","domain":"path","src":"rescue","sid":"rescue|afk|390,420,0|388,418,0","bot":"Irongow687","snap":{"role":"adventurer","cb":41,"hp":[41,41],"coins":830},"walker":"afk","dtype":"job spot","from":[3120,3360,0],"to":[3105,3350,0],"drv":"old","plan":{"ans":"none"},"path":"3110,3356>3109,3356","stall":[3109,3356,0],"near":{"objs":[{"id":1276,"n":"Tree","at":[3108,3355,0],"st":"solid","ty":10}],"npcs":[],"run":0},"combat":{"in":false,"hits":0,"h60":0,"died":false,"poison":false,"since":-1},"mv":{"d24":1,"d29":1,"m29":1,"still":900,"px16":false,"near3":false,"wall":false,"redir2":false},"stuckS":1200,"tripS":1340,"log":["walk: step 12 to 3105,3350"],"reason":"blocked-object","rsub":"Tree 1276 on the way","bug":"b-blocked-object-194,209,0","why":"stuck 20 min, afk job spot, planner none"}
{"v":1,"t":1793000000000,"fid":"f-20261026-18","domain":"path","src":"idle_giveup","sid":"idle_giveup|shop|408,403,0|405,401,0","bot":"Hennssey251","snap":{"role":"adventurer","cb":41,"coins":3},"walker":"shop","dtype":"shop","from":[3270,3170,0],"to":[3240,3210,0],"drv":"planner","plan":{"ans":"route","len":212,"hops":9,"hop":3,"age":17,"next":[3267,3228,0]},"path":"3268,3228>3268,3227>3268,3228","stall":[3268,3228,0],"near":{"objs":[{"id":2882,"n":"Gate","at":[3268,3227,0],"st":"closed","ty":0,"rule":"10 gp toll"}],"npcs":[{"id":925,"n":"Border Guard","at":[3267,3227,0],"agg":false,"lvl":12}],"run":0},"combat":{"in":false,"hits":0,"h60":0,"died":false,"poison":false,"since":-1},"mv":{"d24":2,"d29":2,"m29":6,"still":3,"px16":false,"near3":true,"wall":false,"redir2":false},"gate":{"id":"alkharid-toll","need":"10 gp toll, coins 3","unmet":true,"box":false},"stuckS":300,"tripS":420,"log":[],"reason":"rule-gate","rsub":"10 gp toll, coins 3","bug":"b-rule-gate-204,201,0-alkharid-toll","why":"idle for the full limit, shop shop, planner route"}
{"v":1,"t":1793000000000,"fid":"f-20261026-19","domain":"health","src":"exception","sid":null,"bot":"Hennssey251","snap":{"role":"adventurer","cb":41},"stall":[3222,3218,0],"drv":"old","log":[],"reason":"exception","rsub":"exception","bug":"b-exception-AfkTasks.Runner.step-NullPointerException","why":"java.lang.NullPointerException","exc":{"cls":"java.lang.NullPointerException","msg":"null","where":"AfkTasks.Runner.step","frames":["content.custom.AfkTasks$Runner.step(AfkTasks.java:1405)"]}}
{"v":1,"t":1793000000000,"fid":"f-20261026-20","domain":"path","src":"replay_skip","sid":"rescue|afk|390,420,0|388,418,0","bot":"Replayer2","snap":{"role":"replayer"},"walker":"replay","dtype":"other","from":null,"to":null,"drv":"none","plan":{"ans":"none"},"path":"","stall":[3109,3356,0],"near":{"objs":[],"npcs":[],"run":0},"combat":{"in":false,"hits":0,"h60":0,"died":false,"poison":false,"since":-1},"mv":{"d24":0,"d29":0,"m29":0,"still":0,"px16":false,"near3":false,"wall":false,"redir2":false},"stuckS":0,"tripS":-1,"log":[],"reason":"unknown","rsub":"a real player came within 20 tiles","why":"a real player came within 20 tiles"}
```

The design's §2.2 lists one line per reason code; `TestB19A2` writes 20 synthetic ones (one per rule plus edge cases) and
`bugs.py --reclassify` re-derives all of them.

## 7. Hooks (`Failures`, §14 contract; all never throw)

`driver(bot, DRV_*)`, `log(bot, line)`, `tripStart(bot, from)`, `failure(bot, src, sid, goal, stall, why)` (= domain path),
`failure(bot, domain, src, sid, goal, stall, why)`, `exception(where, bot, t)`, `bugReplayDue(sid)`, `openBugFor(sid)`,
`replayResult(bugId, sid, bot, result, fid, why)`, `snapshotFor(sid)`, plus `watch(bot, role)` (explorers), `flushNow(ms)` (tests;
never from the game thread except in a trial) and `report(day)` (the §8 markdown sections for `Watchdog.Daily.write`). `failure()`
and `exception()` return the fid when the record will be written, null when it was capped or throttled (it is still counted).
`-Dfailures=false`: every hook a no-op, no sampler, no files. `-Dfailures.debug=true`: the sampler's cost every 10 minutes.
`-Dfailures.host=farm-<world>` on a farm world (status changes there are farm proposals).
