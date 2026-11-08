# Install

Everything below was run on Linux and on Windows (Git Bash). Paths use `/`; on Windows the
classpath separator is `;` instead of `:`.

## 1. What you need

* A built 2009scape server. The add-ons were written against the commit pinned in
  [sources.json](sources.json) (`upstream-server` revision). Newer servers will probably work, but
  the overlay replaces `core.game.ge.GrandExchange` and `core.game.ge.BotPrices`, so if those
  classes changed upstream you will see a compile error or odd Exchange behaviour and will need to
  re-port those two files.
* JDK 11 or newer (`javac`). Tested with 21.
* Your server's `server.jar` and its `data/` folder.

## 2. Build the overlay

From the repo root:

```bash
cd deployment/addons-src
javac --release 11 -nowarn -encoding UTF-8 -cp /path/to/server.jar -d ../../overlay content/custom/*.java core/game/ge/*.java
cp content/custom/artisan-recipes.json content/custom/afk-spells.json content/custom/bargain-lines.json ../../overlay/content/custom/
```

You should end with an `overlay/` folder holding `content/custom/*.class`, `core/game/ge/*.class`
and the three JSON files. About 370 classes is normal.

Optional: `deployment/overlay-src/` holds two patched networking classes (see its README). Compile
them the same way into the same `overlay/` folder only if you need them.

## 3. Seed data

Copy the seed folder into your server's `data/` directory **before the first boot with the overlay**:

```bash
cp -r deployment/seed/data/* /path/to/server/data/
```

This gives you 24 remembered adventurers, the artisan and trader roster, the price base, the ledger,
the quest book, and the learned map (`data/survey/`: the route map with nearly 10,000 cells and
over five million recorded crossings, gates, heat, doors, stairs, shortcuts and the planner grid). Everything
else (the rest of the adventurers, the raw survey legs) is created on the first boot and saved as
the bots play. If you skip the seeds the bots still spawn, but the economy starts from zero, nobody
is remembered, and the bots walk in straight lines until the route map has learned the world.

Bot names come from the server's own name pool, so they look like ordinary player names. Do not
reuse these files on a server where a real player already owns one of those names.

## 4. Server config

In `server.conf`:

```
enable_bots = true
max_adv_bots = 0
```

`max_adv_bots = 0` turns off the stock adventurer bots so the smart ones replace them. If you like
the stock ones as background filler, leave a few.

Start the server with the overlay first on the classpath:

```bash
java -Xmx2g -cp overlay:server.jar core.Server
```

On Windows: `java -Xmx2g -cp "overlay;server.jar" core.Server`. Keep `-Xmx2g` or more; the
economy and route data live in memory.

## 5. What a good boot looks like

```
[RouteMap] trained/loaded ...
[Gates] 51 shortcut objects ... 25 gates
[SmartAdv] N adventurers and M high-tier remembered
[Artisans] 472 recipes, 11 artisans scheduled
[Watchdog] stuck bots are rescued after 20 min
[Deals] bank callouts, hub deals and refining splits on
[PlayerTrades] idle bots buy ...
[AfkTasks] ::afk disabled (start with -Dafk.owners=name1,name2 to enable it)
```

Every add-on tick is wrapped in a `try/catch`, so one bot's failure is logged as
`[Component] ...: exception` and never stops the game loop. The only expected exception is
`Major Update Worker ... InterruptedException` at shutdown.

## 6. Flags

All switches are `-D` system properties on the `java` command line. Defaults are what ran on my
server.

| Flag | Default | Meaning |
|---|---|---|
| `smartbots.count`, `smartbots.high` | 90, 10 | adventurers, and how many are high tier (remembered high profiles can exceed `high`) |
| `smartbots.tripMax` | 56 | units gathered per trip |
| `smartbots.shiftSec`, `smartbots.awaySec` | 0 (= 2 to 5 h, 1 to 4 h) | fixed shift and break lengths, for trials |
| `tele.min` | 320 | tiles below which trips are walked instead of teleported |
| `deals`, `deals.min` | true, 20 | bank callouts on; haul size that triggers one |
| `playertrades` | true | idle bots accept a player's trade |
| `watchdog`, `watchdog.min` | true, 20 | stuck rescue after N minutes |
| `routemap`, `gates` | true | learned routes; requirement rules |
| `nav` | false | the grid planner instead of the learned route map (`data/survey/nav.on` toggles it at runtime) |
| `alch.cap` | 150000 | High Alchemy minted margin per day |
| `botrun` | true | run energy for bots a player can see |
| `survey.count`, `survey.clones`, `survey.cloneOf` | 0, 0, (empty) | map surveyors (off; the route map is already trained); clones copy the named player's levels |
| `survey.step/stairs/doors/tunnels` | 48/6/6/4 | surveyor lattice and special surveyors |
| `prices.drift`, `prices.driftDaily` | true, 2 | price drift towards shortages, percent per day |
| `scenes`, `scenes.greet/crews/show/rescueTalk` | true | scenes near players; `data/scenes.off` is a kill switch without a restart |
| `reach` | false | reach fixes: Mort Myre needs Nature Spirit, Shilo closed to bots, drawbridge deck, built-in gate record, same-side shops. Parts: `reach.myre/bridge/shilo/gates` follow `reach`; `reach.sea` is separate and off |
| `bots.casualChat` | false | some replies lower-case or shortened, with a typing delay; price lines unchanged |
| `bounty.exemptDeals` | false | items bought from bots do not earn the bounty premium |
| `afk.owners` | (empty) | accounts allowed to use `::afk` (comma-separated). Empty = the command is off |
| `world.website` | (empty) | a site bots mention when asked for guides or prices. Empty = they say there is none |
| `quests.share`, `quests.coinCap`, `quests.off` | 0.15, 100000, (none) | share of bots allowed to quest, daily quest coins, scripts to ship switched off |
| Trial-only | | `traders.fast`, `afk.debug`, `jobs.debug`, `botchat.debug`, `smartbots.testAgility`, `spectacle.test`, `routemap.test`, `gates.test`, `watchdog.test`, `bounty.test`, `quests.test`, `xp.debug` |

The full list with what each one changes is in [docs/bot-behaviour.md](docs/bot-behaviour.md)
section 14 and [docs/TECHNICAL-REFERENCE.md](docs/TECHNICAL-REFERENCE.md) section 12.

## 7. Client plugins (optional)

`deployment/client-plugins/src-adapted/<Name>/` are source folders for the 2009scape client's
plugin system (Kotlin, compiled by the client at startup). Copy a folder into your client's
`plugins/` directory. Plugins that fetch prices or hiscores read the base URL from
`-Dlivingworld.pricesUrl` on the client JVM and fail quietly if it is unset. See
[deployment/client-plugins/README.md](deployment/client-plugins/README.md) for the list and credits.

## 8. Updating

Rebuild `overlay/`, stop the server when nobody is on, swap the folder, start it again. Bots,
ledger and quest state are saved every few minutes and at shutdown; a restart with the same
`data/` folder brings every remembered bot back under the same name.
