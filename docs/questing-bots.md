# Questing bots (Phase 0 + Phase 1, waves 1 and 2)

Adventurer bots now do real quests: they walk to the quest giver, click through the dialogue the way a player's client does, gather or buy the items, use ladders and stairs, hand in, and get the server's own reward. Nothing is granted by script. Wave 1 (five quests) built 2026-10-22 (local trials in the session scratchpad `qt/`); design in the Claude Doc "questing bots". Wave 2 (the floor walker plus five more quests, ten scripts in all) went live 2026-10-23 03:29 UTC; see the last section. **Status since batch 12 (10-23 14:53 UTC): all quests are on** (`QuestBook.OFF_DEFAULT` is empty; startup "17 scripts loaded (waves 1-2)" with none off). Black Knights' Fortress, Prince Ali Rescue and Ernest the Chicken went live in batch 11 (12:39), Shield of Arrav in batch 12; see "Batch 11" and "Batch 12" below.

## Files (deployment/addons-src/content/custom)

| File | What it does |
|---|---|
| `DialogueDriver.java` | Clicks a bot through a server dialogue: options screens (chatbox 228/230/232/234, option n = child n+1), Topic lists (`DialogueInterpreter.activeTopics`, matched by words), continue screens (child 1). One click every 2–4 ticks. `seen()` is the trace written to failures. |
| `QuestScript.java` | One quest: enum, slug, name, own requirement check (levels, QP via `getPoints()`, earlier quests; never `Quest.hasRequirements`), minutes, coins, reward items, steps, exit steps. |
| `QuestSteps.java` | Step types: `Walk`, `Talk`, `Interact` (scenery/NPC options, ladders and stairs, so floors change as explicit steps), `UseOn`, `Gather` (an `AfkTasks.Runner` with `questHold`), `Kill`, `Buy`, `Collect` (pack, bank, bot stock for glut goods, a sub-plan, then a shop), `PickUp` (ground spawns), `WaitStage`, `Custom`. Shared helpers: the walker (`Mover`: NavWalker/RouteMap hops, bridges, the toll, 40-tick stall rule, opens doors in the way, paid teleport after 4 retries), door opening, varbit objects (a wrapper's child for this bot), the per-NPC talk lock. |
| `QuestRunner.java` | `Pulse(1)` per questing bot. Each run builds its own copy of the steps. Re-reads the real stage every tick; skips steps whose checkpoint holds; a step failing twice parks the quest for that bot for 24 h and writes `data/quests/failures.jsonl`. Quest XP at the 2009 rate (multiplier 1.0 during talk/use/interact steps). On completion: closes the reward scroll (interface 277), books coins to `Ledger.QUEST_PAID` and items to `QUEST_ITEMS`. Boss lock (one bot, no player within 15 tiles) for later waves. |
| `QuestBook.java` | Registry, eligibility, weight, caps, persistence (`data/quests/quests.json`), trial harness. |
| `QuestCooksAssistant/SheepShearer/DoricsQuest/WitchsPotion/ImpCatcher.java` | Wave 1 scripts. |
| `QuestFloors.java` | Wave 2: the floor walker (see below). |
| `QuestRuneMysteries/RestlessGhost/RomeoAndJuliet/VampireSlayer/GoblinDiplomacy.java` | Wave 2 scripts that are live. |
| `QuestKnightsSword/PiratesTreasure.java` | Batch 5 scripts that are live (05:48 UTC 10-23). Prince Ali, Black Knights', Ernest and Shield of Arrav are registered but off by default (see the end). |
| `QuestDruidicRitual.java` | Batch 8 script that is live (08:56 UTC 10-23). |

Hooks in existing files: `SmartAdventurers.Brain.think` (a questing bot is left to its QuestRunner; resume a half-done quest; start the quest `chooseJob` picked), `chooseJob` (quest offer vs the best job), `restore` (attributes + stages before `seedQuests`), `shutdown` (quests saved after graves, before profiles); `AfkTasks.Runner.questHold` (no dialogue closing, haul kept, quest spots in `relocate`); `Gates.seedQuests` (`QuestBook.skipSeed`); `Watchdog` (instances never stuck; quest walks count as trying); `Ledger` (`questPaid`, `questItems`).

## Choosing and caps

- Weight = (coins + 0.9 × item value + 0.05 × useful XP + unlock value) / (1 + minutes/30); ×1.3 in a bot's first 3 shifts; ×0.5 if it finished a quest in the last hour; Imp Catcher ×0.3 unless it holds 2+ beads. A broke bot only takes quests that pay coins. The quest is taken when its weight beats the best job's score.
- At most 15% of online adventurers questing (`-Dquests.share`); at most 2 bots on one quest near its start (counting bots still walking to the giver); quest coins capped at 100,000 per UTC day (`-Dquests.coinCap`), coin-paying quests drop out when hit.
- A script over 30% failures in a UTC day (3+ abandons) switches itself off until midnight. `-Dquests.off=slug,...` ships a script switched off.
- Seeding: a scripted quest is still seeded for bots unless the script is proven (20 completions, < 10% failures over 7 days) or the bot is doing or did it for real.

## Persistence

`data/quests/quests.json` (atomic write every 10 min and at shutdown): per bot the active quest and step, stage, parked-until, completions, every `/save:` attribute, the stages of scripted quests; per script a 14-day daily log {starts, completions, failures}; coins paid today. At restore the attributes come back first, then `setStageNonmonotonic` for the stages, before `seedQuests`; rewards are never paid on restore. A resumed quest starts from step 0 and every finished step's checkpoint skips it.

## Trials

`-Dquests.test=cooks,sheep,doric,witch,imp` spawns one fresh unseeded bot per slug at the giver with an empty pack and 5,000 gp (`-Dquests.testItems` gives Cook's items up front); test bots resume after a restart where they were. `-Dquests.only=true` makes adventurers take any eligible quest; `-Dquests.noseed=true` leaves scripted quests unseeded; `-Dquests.noStock=true` forces real gathering; `-Dquests.probe="x0,y0,x1,y1,z[,filter|#]"` prints scenery (with `#`: the plane's whole object list, hidden objects too).

## Gotchas found in the trials

- Steps hold per-run state: two bots sharing one script's step objects lost quest starts at Fred's and Doric's. Each run builds its own steps.
- Raw beef on a range asks "Dry the meat into sinew / Cook": option 2 cooks.
- The Mill Lane flour bin is a null-named wrapper (36880 at 3166,3306); the bin is its child for the bot. Mill ladder 3164,3307 on all floors; hopper controls 2718 at 3166,3305,2.
- Closed doors: Wizards' Tower (3107,3162), the mill (3166–3167,3302), Hetty's house (2964,3206), the dairy cow pen. Steps open the nearest door toward where they are going.
- Old runner pathing loops north-west of Lumbridge castle (castle exit + River Lum rules): Cook's egg is picked up at Fred's farm with the quest walker instead.

## Wave 2

Build 68, 244 classes; startup line `[Quests] 10 scripts loaded (waves 1-2), coin cap 100000/day`. Scripts: Rune Mysteries, Romeo & Juliet and Goblin Diplomacy written by a Sonnet subagent; The Restless Ghost and Vampire Slayer by Opus subagents; the floor walker by the main session, which also reviewed and trialled every script.

### The floor walker (`QuestFloors` + `QuestSteps.Mover`)

Before wave 2 a walk could only target a tile on the bot's own floor (it failed at once with "on floor X, need Y") and floors changed only through hand-written `Interact` steps. Now `Mover.tick` compares planes: z (0, 1, 2) and "underground" (y above 6400, where dungeons and basements sit 6400 tiles north of the land above them). When the target is on another plane the Mover makes a `Climber` (lazily) and lets it get the bot onto the target's plane; the Mover then walks the rest. Every walking step (Walk, Talk approach, Gather, Kill, Buy) is floor-aware this way.

How it picks a staircase:
- Direction: out of a dungeon is up, into one is down (from any floor); otherwise up if the target's z is higher. A bot upstairs bound for a basement goes down first.
- Candidates: the stair surveyors' proven climbs (`data/survey/stairs.jsonl`, results `up*` and `down*`, within 40 tiles of the target as seen from the bot's floor), then a scan of objects named stair, ladder or trapdoor with a Climb-up/Climb-down option (Go-up/down and Walk-up/down also count) within 15 tiles of the target's projection onto the bot's floor and 10 of the bot. If that finds nothing, the region's object list is read as a fallback.
- Score: distance from the stair to the target plus 0.2 x distance from the bot (a proven climb gets a head start of 3); the lowest wins.
- Climb: walk to within 3 tiles (the normal walker, so it opens doors on the way), click the option the way the client would, wait for the floor to change. If the click didn't take, it calls the climb handler directly, then (up to 3 attempts) opens the nearest door toward the stair and tries again; after that the stair is struck off for this walk and the next is tried. Four picks at most, then the step fails like any other failed walk (two failures park the quest for that bot for 24 h).

Fixes found by the trials: click a staircase from up to 3 tiles (a stair tile can't always be stood next to: Draynor Manor's first floor), walk right up to it first (the walker opens doors; clicking through a shut inner door failed), after a climb that didn't take open the nearest door and retry twice before striking the stair off, a region object-list fallback, and Walk-up/Walk-down stairs.

Limits: one flight at a time, so a trip across several floors is several picks; a stair with no Climb/Go/Walk up-or-down option needs an explicit `Interact` step; same-floor doors are still the old walker's job and are the slow part of a floor trip (not the climbing). Test `TestFloors`, 8 walks, passed twice: Lumbridge castle up and down, Wizards' Tower basement in and out, Blue Moon Inn up and down, Draynor Manor top floor, Falador castle 2nd floor (ladder plus stairs).

### `pocketCoins`

`QuestSteps.pocketCoins(n)` is a one-tick `Custom` step that moves coins from the bank to the pack until the pack holds n. It exists because NPCs that charge coins (Wyson the gardener, Aggie) take them from the pack, while bots keep their coins in the bank. Found by the Goblin Diplomacy trial (bug #163).

### New quests

Each was trialled from an empty pack with `-Dquests.test=<slug>`.

- **Rune Mysteries** (Duke Horacio 741, Sedridor 300, Aubury 553). Duke upstairs in Lumbridge castle gives the air talisman (stage 10) -> Sedridor in the Wizards' Tower basement (talisman taken, research package 290, stage 30) -> Aubury in the Varrock rune shop (package delivered, stage 40, then notes 291, stage 50) -> back to Sedridor (stage 100). Reward: 1 QP, Runecrafting and the rune essence mine. Uses the floor walker for the castle and the basement. Trial: complete in 11 minutes, QP 0 to 1.
- **The Restless Ghost** (Father Aereck 456, Father Urhney 458, the ghost 457). Aereck starts it (stage 10) -> Urhney in the swamp shack gives the Amulet of ghostspeak 552 (stage 20; needs a free slot) -> the ghost in the graveyard shed only talks while the amulet is worn (stage 30) -> search the altar in the Wizards' Tower basement for the skull 964 (stage 40; spawns skeleton 459, level 13, attackable only by this player) -> skull on the open coffin. Reward: 1125 Prayer XP, 1 QP. Quirks: the ghost is one shared NPC, invisible until someone opens the closed coffin 2145; the skull on the closed coffin does nothing. Trial: complete in 18 minutes, QP 0 to 1, Prayer +1125, skeleton fought off.
- **Romeo & Juliet** (Romeo 639, Juliet 637, Father Lawrence 640, Apothecary 638). Romeo asks (stage 10) -> Juliet upstairs in the house west of Varrock gives the message 755 (stage 20) -> back to Romeo (stage 30) -> Lawrence (plan, 40) -> Apothecary (berries wanted, 50) -> cadava berries 753 from bushes 23625/23626 (the empty bush 23627 regrows after 30 ticks) -> Apothecary gives the potion 756 (60) -> Juliet drinks it (70) -> Romeo (100). Reward: 5 QP, no coins or items. Quirk: two cutscenes (Juliet at stage 60, Romeo at 70) open their own continue-only dialogue and close and reopen it between screens, so a `Custom` step clicks continue and never closes anything (the `DialogueDriver` would close it in the gaps). Trial: complete in 22 minutes, QP 0 to 5.
- **Vampire Slayer** (Morgan 755, Dr Harlow 756, Count Draynor 757). Morgan in Draynor (stage 10) -> Harlow in the Blue Moon Inn, Varrock, wants a beer 1917 (stage 20, then 30 and a stake 1549) -> garlic 1550 from the cupboard 33502 upstairs in Morgan's house -> a hammer 2347 from any general store -> the Draynor Manor basement by the stairs 32835 "Walk-down" (explicit steps; both basement stairs are Walk-up/Walk-down) -> open the coffin 2614 -> Count Draynor appears for this bot only and attacks at once. The boss fight is the first use of the runner's `boss()` step: 40 hp, attack 20, defence 20; without both stake and hammer in the pack every hit is zeroed; he heals 1 hp in 7 ticks (1 to 11 hp without garlic in the pack, always 1 with it), so garlic is what makes him killable at these levels. At 0 hp with a hammer the stake is used up. Reward: 4825 Attack XP, 3 QP. Trial: complete in about 7 minutes, QP 0 to 3.
- **Goblin Diplomacy** (General Wartface 4494, General Bentnoze 4493 in the Goblin Village). Generals' menu option 3 starts it (stage 10, orange wanted) -> goblin mails (288, brown) from the crates 16559 and 16560 (Search, once per 500 ticks per crate; goblin drops are the last fallback) -> dye two of them: orange (red dye 1763 on yellow dye 1765, then the orange dye 1769 on a mail) and blue (1767) -> Aggie in Draynor makes the dyes for 5 coins each (red 3 redberries 1951, yellow 2 onions 1957, blue 2 woad leaves 1793), one dye per conversation -> Wyson the gardener in Falador park gives 2 woad leaves for 20 coins -> hand in orange, blue, brown in turn (option 3 with the item in the pack). Reward: 5 QP, 200 Crafting XP, a gold bar. Quirks: each hand-in starts a cutscene in a dynamic region copy; the cutscene step clicks continue only, and after "Put on this armour!" it clicks once and waits, because clicking again would restart the 7 to 16 tick message pulse; Aggie and Wyson charge from the pack (`pocketCoins`). Trial: complete in 18 minutes, QP 0 to 5, Crafting +200.

### Seeding

Rune Mysteries (85%), The Restless Ghost (75%) and Prince Ali Rescue (80%) are seeded as done for most bots (`Gates.SEED`) until the script is proven (20 completions, under 10% failures over 7 days), so those run less often. Romeo & Juliet, Vampire Slayer and Goblin Diplomacy are not seeded.
