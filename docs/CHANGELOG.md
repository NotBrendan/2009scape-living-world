# Change log

What shipped, newest first, condensed. Bug numbers refer to a register that is not published; the behaviour is described in
[bot-behaviour.md](bot-behaviour.md) and [TECHNICAL-REFERENCE.md](TECHNICAL-REFERENCE.md).

## Late November 2026

Everything below is behind its own `-D` switch and **off by default**. The recommended set is in
[../deployment/recommended-flags.txt](../deployment/recommended-flags.txt). Each one went through
paired test runs (switch on vs off) and, where it moves coins, a scripted scenario before it was
added to that set.

* **Skill training spread:** bots share their time across skills (`bots.jobShare`), stop picking
  jobs that give no experience (`bots.brokeJob.zeroSkip`), and use the Mining and Fishing Guilds
  (`bots.mine.guild`, `bots.fish.guild`, `bots.fish.guild2`), power-mine (`bots.mine.power`), fish
  sharks (`bots.fish.shark`), and run Barbarian agility laps (`bots.agility.laps`, `bots.agility.barb`).
* **Combat:** a stance plan and a Defence share (`bots.stancePlan`, `bots.style.defShare`), weapons
  picked for the monster (`bots.weaponByFoe`), special-attack weapons (`bots.attack.spec`), amulets
  and armour above rune (`bots.kit.amulets`, `bots.kitAboveRune`), a melee fallback and better kit
  for rangers (`bots.ranged.melee`, `bots.ranged.kit2`, `bots.style.restock`), cheaper food
  (`bots.fight.cheapFood`), and safe retreats (`bots.fight.safeRetreat`, `bots.fight.karaRetreat`).
* **Slayer:** Vannaka with points, banking first and tasks a poor bot can afford (`bots.slayer.*`),
  fixes for bats, ice giants, Vannaka's door and the errand cap. Chaeldar and Duradel are in the
  code (`bots.slayer.chaeldar`, `bots.slayer.duradel`) but not yet in the recommended set.
* **Summoning:** Wolf Whistle and pouches (`bots.summoning.*`), healing and combat familiars, and the
  beast of burden (`bots.summoning.bob`), which carries a pack from Summoning 13.
* **Farming and Hunter:** herb runs with better seeds (`bots.farming.herbs2`), tree runs
  (`bots.farming.trees`, `bots.farming.trees.run`), compost (`bots.farming.v2`), bird hunting
  (`bots.hunter`).
* **Artisans:** smiths smelt their own bars (`artisans.smithSmelt`), fletchers make arrow shafts and
  their own bows (`bots.fletch.arrows`, `bots.fletch.own`), cooks burn less and cook on fires
  (`bots.cook.burn`, `bots.cook.fire`), firemaking lines (`bots.fire.line`), plank types and a
  Construction hotspot (`bots.plankTypes`, `bots.con.hotspot`), herb supply for herbalists.
* **Magic, Prayer, Runecrafting, Thieving:** teleports (`bots.magic.tele`), any staff counts
  (`bots.magic.anyStaff`), enchanting and High Alchemy of everything worth it (`bots.magic.enchant`,
  `bots.magic.alchAll`), prayer that follows the stats (`bots.prayer.stat`), nature and law rune
  runs (`bots.rc.natureLaw`), Ardougne pickpocketing (`bots.thieve.ardy`).
* **Economy and pathing:** a clerk with a daily budget and a boot-time fair (`clerk.*`), export pay
  (`market.exportPay`), a coin watch that knows the hourly swing (`coinwatch.bandAware`,
  `coinwatch.bootSeaExempt`), and route fixes (`nav.freeExit`, `nav.p93Fix`, `quest.p92Fix`).
* **Test tooling:** `Seed` and `Scen` set up forced states (levels, items, a start tile) for scripted
  scenario runs. They refuse to run unless the server was started by the test harness, so they do
  nothing on a normal server.

## November 2026

* **Reach fixes** (`-Dreach`, default off): bots stop walking into places they cannot leave. The
  Mort Myre swamp is gated on Nature Spirit being started, Shilo Village is closed to bots, the
  Ardougne drawbridge deck no longer traps north-bound walks, the planner gets a built-in record of
  multi-tile gates when its grid file has none, and shop trips pick the nearest shop on the same
  side of the sea. Each part has its own switch (`reach.myre`, `reach.bridge`, `reach.shilo`,
  `reach.gates`); `reach.sea` stays off unless set on its own.
* **Casual chat** (`-Dbots.casualChat`, default off): replies to players are sometimes lower-case or
  shortened and arrive after a typing delay. Lines with prices or numbers are never changed.
* **No bounty premium on bot-bought items** (`-Dbounty.exemptDeals`, default off): units a player
  buys from a bot at the bank or from a travelling trader do not count towards the bounty premium,
  so buying cheap from a bot and reselling to the board earns nothing extra.
* **New trial tests**: Shield of Arrav partner checks and the bounty exemption.
* **Scenes near players** (`Scenes`): a wave and a line for a newcomer near Lumbridge, crews hunt
  near home, one show per hub per 30 minutes, a comment when a stuck bot gets rescued. All behind
  `-Dscenes.*` flags and a `data/scenes.off` kill switch.
* **Coin-drain alarm** (`CoinWatch`): hourly count of the fleet's coins with an alarm when the total
  falls too fast; found and fixed bots paying a sea-crossing fare over and over.
* **Ledger and alchemy fixes**: daily caps restored correctly after a restart, the High Alchemy item
  list reviewed, wallet credits for a bot that came back under a different name.
* **Human-look pass**: bank "sorting" pause, short AFKs when a rock or tree runs out, roadside
  breathers on long walks, run energy only where a player can see.
* **Stuck-bot rounds**: loops near Lumbridge castle and the River Lum, hub crowding, tolls bots
  could not afford, door and gate protocol for the grid planner.
* **Haggle engine** (`Bargain`, `BargainHooks`): one engine behind trader haggling, callouts, player
  trades and the trade window; reads prices out of plain chat.
* **AFK magic** (`AfkMagic`): the `::afk` runner can cast (alch, superheat, enchant) from
  `afk-spells.json`.
* **Dialogue round** (`BotChat`, `TradeTalk`): more things a bot understands, fewer repeats,
  casual chat lines.

## Late October 2026

* **Pre-flight** (`Preflight`): one check before every goal, shop and job walk, so trips that cannot
  succeed are not started.
* **Navigation guard**: the grid planner (`NavGrid`, `NavPlanner`, `NavWalker`) ran in shadow mode
  beside the route map, then live with an auto-revert if rescues climbed.
* **Scenarios and failure records** (`Scenarios`, `Failures`, `EconReplayer`): walks that went wrong
  are recorded and replayed against a fix; non-path failures get the same treatment.
* **Survey writes off the game thread** (`SurveyWriter`); off-ground explorers for stairs, ladders
  and dungeons (`OffGround`, `Explorers`).
* **Save quarantine and startup sanity check**: a bad `smartbots.json` is set aside instead of
  loaded; daily caps live in `ledger.json`; wrong-thread ledger calls are logged.
* **Armour upgrades**: bots buy a matched set at the best tier their levels allow and sell the old
  one.
* **Teleports through `Spectacle.teleport`**: the spell animation only where a player can see it.

## 22 to 24 October 2026

* **Questing bots, waves 1 and 2**: Cook's Assistant, Sheep Shearer, Doric's Quest, Witch's
  Potion, Imp Catcher, Rune Mysteries, The Restless Ghost, Romeo & Juliet, Vampire Slayer, Goblin
  Diplomacy, The Knight's Sword, Pirate's Treasure, Druidic Ritual, Ernest the Chicken, Prince Ali
  Rescue, Black Knights' Fortress, Shield of Arrav (two bots, both gangs). The floor walker
  (`QuestFloors`) for stairs and ladders.
* **Companions** (`Companions`): bots that work alongside a real player.
* **Combat review**: rangers and mages, prayer use, bone burying, flee rules, poison awareness.
* **Audit fixes**: a full pass over server, bots and economy with the fixes that came out of it.

## 18 to 21 October 2026

* **Watchdog** (`Watchdog`): stuck bots rescued after 20 minutes; traps remembered.
* **Gates and heat** (`Gates`, `Heat`): skill and quest rules for where a bot may go; danger and
  deaths raise route cost.
* **Chat round two** (`BotChat`, `TradeTalk`, `Haggle`): directions, prices, quest hints,
  personas with catchphrases and grudges, haggling.
* **Player trades** (`PlayerTrades`): idle bots at a bank buy what a player offers.
* **Spectacle** (`Spectacle`): arm-wrestles, dance-offs, boasts and races at the Exchange when a
  player is watching.
* **Surveyors and the route map** (`Surveyors`, `RouteMap`, `Resources`): bots that walk the map
  in zig-zags so the others learn it.
* **Bounty board, crews, high tier** (`Bounty`, `SmartAdventurers`): buy orders for what the market
  is short of, crews that hunt together, a high-tier population with real gear.
* **Price drift** (`PriceDrift`): prices move towards shortages a little each hour.
* **Economy add-ons** (`Ledger`, `MarketBrain`, `Artisans`, `Deals`, `Economy`, `HuntMap`): bots pay
  bots, artisans turn surplus stock into what nobody sells, hunts built from the server's own drop
  tables.
* **Client plugins**: BorderlessFullscreen, MinimapZoom, NoIdleLogout.

## Before 18 October 2026

* Smart adventurers replaced the stock bots: names that stick, real banks, shifts, breaks, shops
  on foot, gear bought with their own coins. The `::afk` task runner (`AfkTasks`) became the trip
  engine for every bot.
