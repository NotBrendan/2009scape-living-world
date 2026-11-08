# Change log

What shipped, newest first, condensed from the private tracker (which logged every build with its
live time). Bug numbers refer to a register that is not published; the behaviour is described in
[bot-behaviour.md](bot-behaviour.md) and [TECHNICAL-REFERENCE.md](TECHNICAL-REFERENCE.md).

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
