# How it works

Seven pictures of the moving parts. The class names are the ones in `deployment/addons-src/content/custom/`;
the long version of each is in [TECHNICAL-REFERENCE.md](TECHNICAL-REFERENCE.md).

## 1. The overlay

The add-ons are compiled into an `overlay/` folder that sits ahead of `server.jar` on the classpath.
Java loads the first class it finds under a name, so the two `core.game.ge` classes in the overlay
replace the server's own, and everything else is new classes the server's `ClassScanner` picks up
because they implement `StartupListener`. The jar itself is untouched.

```mermaid
flowchart LR
  subgraph HOST["Your game host"]
    direction TB
    JVM["java -cp overlay:server.jar core.Server"]
    OV["overlay/<br/>content/custom/*.class (new)<br/>core/game/ge/*.class (replacements)"]
    SRV["server.jar<br/>stock 2009scape"]
    DATA["data/<br/>smartbots.json, eco/, quests/, survey/"]
    JVM --> OV
    JVM --> SRV
    OV -. "same class name wins" .-> SRV
    SRV <--> DATA
  end
  SEED["deployment/seed/data"] -- copied once --> DATA
  PL["Players"] --> JVM
```

## 2. Ticks, pulses and threads

The server runs one game tick every 600 ms on a single game thread. The add-ons never change a
bank, a pack or the world from another thread: slow work (scoring 470 recipes, planning a route,
writing survey files) runs on worker threads and hands its result back by queueing a `Pulse(1)`,
which the game thread runs next tick.

```mermaid
flowchart TD
  GT["Game thread<br/>one tick = 600 ms"]
  PWA["PrivateWorldAddons<br/>StartupListener that loads every add-on"]
  BR["Brain.tick every 3 ticks<br/>one per adventurer"]
  RUN["AfkTasks.Runner: Pulse(1)<br/>one per trip"]
  QR["QuestRunner: Pulse(1)<br/>one per questing bot"]
  AR["Artisans, traders, locals<br/>timers, one per bot"]
  WT["Worker threads<br/>recipe scoring, route planning,<br/>survey writes, hourly ledger jobs"]
  Q["queue a Pulse(1)"]
  GT --> PWA
  GT --> BR --> RUN
  GT --> QR
  GT --> AR
  WT -- "never touch a bank or pack directly" --> Q --> GT
  BR -- "every tick is wrapped in try/catch:<br/>one bot's failure never stops the loop" --> BR
```

## 3. A bot's day

Each smart adventurer is a `Brain`. Between trips it decides what to do next; during a trip a
`Runner` drives it; every few trips it takes a break at the Exchange or a town hangout; after a
shift of two to five hours it says goodnight and logs out for a while, and comes back under the same
name with the same bank.

```mermaid
stateDiagram-v2
  [*] --> Spawn: restored from smartbots.json, or minted fresh
  Spawn --> ChooseJob: gear checked, crew joined, persona set from the name
  ChooseJob --> ShopTrip: missing a tool or food
  ShopTrip --> ChooseJob
  ChooseJob --> Quest: a quest offer beat the best job
  Quest --> ChooseJob: finished, parked or abandoned
  ChooseJob --> Trip: start a Runner
  state Trip {
    [*] --> TO_SPOT
    TO_SPOT --> WORK: within 2 tiles
    WORK --> TO_BANK: pack full or target reached
    WORK --> TO_FURNACE: cook or smelt the haul first
    TO_FURNACE --> TO_BANK
    TO_BANK --> BANKING: at the booth, 8 to 16 ticks of "sorting"
    BANKING --> TO_SPOT: more to do
    WORK --> RETREAT: hp low with food, or attackers too strong
    RETREAT --> TO_SPOT: hp back
  }
  Trip --> TripEnd: banked, target reached, or gave up
  TripEnd --> Break: every 2 to 4 trips
  Break --> ChooseJob: 1 to 3 min of shuffling, emotes, chat, direct deals
  TripEnd --> ChooseJob
  TripEnd --> Goodnight: shift over
  Goodnight --> [*]: logs out 1 to 4 h, profile saved
  Trip --> Dead: a fight went wrong
  Dead --> GraveRun: respawn, walk back for the gravestone
  GraveRun --> ChooseJob
```

## 4. Choosing a job

Every few seconds an idle bot scores every job it knows about. The score comes from the market
(what is short and worth a bounty), scaled by distance, its own trade, where it lives and what it
holds a grudge against. Jobs it cannot reach because of a skill, quest or item gate score nothing.

```mermaid
flowchart TD
  S["idle, every 4 s"] --> C["candidates: gathering spots the bot knows<br/>+ every monster family in HuntMap<br/>+ a quest offer from QuestBook"]
  C --> W["score = bounty weight of the products<br/>divided by (1 + distance / 150)"]
  W --> M["x1.6 in its own trade<br/>x1.5 near home for fresh bots<br/>x persona and grudge"]
  M --> G["Gates: skill, quest and item rules<br/>an unreachable job scores 0"]
  G --> H["Heat: recent deaths and danger raise the cost"]
  H --> P{"best candidate"}
  P -- "needs a tool or 8 food" --> SH["shop trip first:<br/>nearest real shop that stocks it"]
  P -- "the quest wins" --> QR["hand the bot to the QuestRunner"]
  P -- "a job" --> R["prepare: 14 to 56 units,<br/>teleport only over 320 tiles,<br/>then start the Runner"]
```

## 5. Where the money goes

Coins only enter the game by selling something. A haul is offered to an artisan nearby first, then
to any bot that wants it, then to a haggling player, and only then goes on the Exchange as bot
stock where the Ledger remembers who supplied it and pays them when it sells. Coins leave through
food, tools, kit, teleports and tolls. `CoinWatch` raises an alarm if the total drops too fast.

```mermaid
flowchart LR
  G[Gatherer banks a haul] -->|Deals.arrive| D{buyer within reach?}
  D -- artisan within 35 tiles,<br/>recipe uses it --> S[Split: artisan takes it,<br/>owes a share of the product]
  D -- a bot wants it within 15 tiles --> C[Callout at 92 percent of guide,<br/>bot buyer pays]
  D -- player haggles --> H[Deal at 96 percent or more,<br/>counter, or final offer, then a trade window]
  D -- nobody --> L[Ledger.supply: Exchange bot stock]
  L -->|player or bot buys| CR[Wallet.credit to the supplier, FIFO]
  L -->|lot older than 24 h,<br/>supplier under 5 k gp| CF[Clerk fund buys at 60 percent,<br/>2 M per day cap]
  L -->|newcomer under 1 k gp| SB[Starter bailout]
  L -->|stock above 3x target| FG[Flood guard trims 1 percent an hour]
  A[Artisan] -->|buys inputs at guide| L
  A -->|sells products| L
  B[Bots buy food, tools, kit,<br/>teleports, tolls, planks] -->|coins leave the game| X((sink))
  M[Mage alchs surplus bows] -->|capped per day| CR
  CW[CoinWatch, hourly] -. alarm on a fast drain .-> L
```

## 6. Chat and haggling

Every public line a human types near a bot goes through one funnel. Listeners with an open deal
get first pick (a trader mid-haggle, a callout, a buy window). Otherwise the nearest bot answers
from a fixed order of understanding, and one time in four another idle bot chimes in. Prices are
read out of anything a 2009 player might type: "can you do 500", "500 each", "half", "deal".

```mermaid
sequenceDiagram
  participant P as Player
  participant R as BotChat.respond
  participant L as Listeners
  participant B as Brain.answer
  participant H as Bargain and Haggle
  P->>R: "can you do 500 for the ore"
  R->>L: hear(speaker, text)
  alt a listener has an open deal with this player
    L->>H: Haggle.read(text) gives a Bid
    H-->>P: accept at 96 percent or more, counter once, or final offer
  else nobody has a deal open
    R->>B: nearest bot within 8 tiles
    B->>B: directions, then trade talk, then prices,<br/>then quest help, then about me, then small talk
    B-->>P: 1 to 3 lines, 3 ticks apart, facing the player
    R-->>P: 1 in 4: another idle bot within 6 tiles chimes in
  end
```

## 7. Finding the way, and getting rescued

Bots walk on a learned route map: every walker reports the cells it crossed and whether it got
through, so the map fills in from real trips (a seed map is included). `Gates` holds the
"you need X to be here" rules, `Heat` adds cost where bots have died. An optional grid planner
(`NavPlanner`) can take over hop by hop. When all of that fails, the `Watchdog` notices a bot that
has not moved or gained XP in 20 minutes and teleports it to the nearest bank, and the failure is
written down so the exact trip can be replayed against a fix.

```mermaid
flowchart LR
  EV["every walker, every 2 ticks:<br/>cell crossed, ok or failed, ticks taken"] --> RM["RouteMap<br/>8x8 cells with success, failure, time, min Agility"]
  SV["Surveyors and Explorers (optional)<br/>legs, tiles, doors, stairs, shortcuts"] --> RM
  GA["Gates: 25 rule trees<br/>quests, levels, items, coins"] --> PLAN
  HE["Heat: danger and recent deaths"] --> PLAN
  RM --> PLAN["A* per bot:<br/>failed gates are walls,<br/>heat adds cost"]
  PLAN --> HOP["next hop for the Runner,<br/>a shop walk, or a quest step"]
  NV["NavGrid + NavPlanner (-Dnav)<br/>walkability grid, doors clicked on the way"] -. alternative .-> HOP
  HOP --> WD{"Watchdog, every 30 s:<br/>moved 8 tiles or gained XP?"}
  WD -- yes --> HOP
  WD -- "no, for 20 min" --> RS["rescue: teleport animation,<br/>nearest bank, trip ended"]
  RS --> FL["Failures + Scenarios:<br/>the trip is recorded for replay"]
  FL -. "2 rescues in one cell = a trap,<br/>5-minute rule there" .-> WD
```

## 8. The quest runner

A quest is a list of steps (walk, talk, use, gather, kill, buy, climb) in `QuestScript`. The
`QuestRunner` re-reads the server's real quest stage every tick and skips steps whose checkpoint
already holds, so a bot can resume a half-done quest after a restart. Dialogue is clicked through
the way a client would (`DialogueDriver`), so the server grants the real reward.

```mermaid
flowchart TD
  QB["QuestBook: eligible scripts,<br/>weight = reward value / time,<br/>caps per quest and per day"] --> CH["Brain.chooseJob:<br/>quest offer vs the best job"]
  CH --> QR["QuestRunner: Pulse(1) per bot"]
  QR --> ST{"next step whose<br/>checkpoint does not hold"}
  ST --> WK["Walk<br/>(QuestFloors for ladders and stairs)"]
  ST --> TK["Talk: DialogueDriver clicks<br/>options and continues"]
  ST --> GT["Gather: a Runner trip<br/>with the quest on hold"]
  ST --> KL["Kill, UseOn, Buy, Collect"]
  WK & TK & GT & KL --> QR
  ST -- "all steps done" --> DN["server's own reward:<br/>XP, items, quest points"]
  ST -- "a step failed twice" --> PK["parked for this bot;<br/>a script over 30 percent failures<br/>switches itself off until midnight"]
```
