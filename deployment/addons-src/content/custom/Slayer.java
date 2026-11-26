package content.custom;

import content.global.skill.slayer.Master;
import content.global.skill.slayer.SlayerManager;
import content.global.skill.slayer.Tasks;
import core.cache.def.impl.NPCDefinition;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import java.util.*;

/**
 * P54 slayer v1 (Turael), flag -Dbots.slayer (default OFF: no login, no hook, no errand, no boost = live code).
 * design: the design notes (not published). SmartAdventurers only holds the Brain field `slay` (0 off, 1 walk, 2 talk) and
 * a few hook lines; every rule lives here.
 *  - errand at trip end (combat job last trip, coins >= 5k, not mid-quest, no other errand, not in combat, 1 in 3, 45 min cooldown)
 *  - walk/teleport to Turael, guarded SlayerManager login (attribute once-guard), generate + re-roll (max 3) into ALLOW
 *  - v1.1 task trip: in chooseJob, after every gate, a bot with a task takes the NEAREST matching hunt directly,
 *    1 trip in 2 (not when broke), max 3 slayer bots per spot (F7). The v1 x2.5 boost never won (gonogo 11-13).
 *    After a task trip that made kills it goes straight back (streak) until the task is done or stalls.
 *  - abandon after 15 min of play with no task progress; completion chat; cooldown
 */
final class Slayer {
    private Slayer() { }

    static final boolean ON = flag(System.getProperty("bots.slayer"));
    /** Trials only: no RNG gate, short cooldowns. */
    static final boolean TRIAL = Boolean.getBoolean("slayer.trial");
    /** Trials only: never teleport on the errand (the F8 real-walker trial). */
    static final boolean WALK_ONLY = Boolean.getBoolean("slayer.walkOnly");

    static final Location TURAEL = Location.create(2931, 3536, 0);
    static final int MAX_REROLLS = 3;
    static final long ABANDON_MS = 15 * 60_000L, COOLDOWN_MS = 45 * 60_000L, SKIP_MS = 60 * 60_000L, WALK_GIVEUP_MS = 8 * 60_000L;
    static final long MIN_COINS = 5_000;
    /** econ3 L6 (-Dclerk.fix2): no paid teleport under MIN_COINS, as every SmartAdventurers trip; the bot walks instead. Flag off = always true. */
    static boolean fareOk(long coins, boolean fix) { return !fix || coins >= MIN_COINS; }
    static boolean fareOk(Player bot) { return !Ledger.CLERK_FIX2 || fareOk(Ledger.Wallet.coins(bot), true); }
    static final int TRIP_ODDS = 2, SPOT_CAP = 3;
    /** Turael is the beginners' master: a bot at combat 70+ picks dragons and demons over a x2.5 cow task, so it would only
     *  churn errands and 15-minute abandons (4-bot trial, combat 107/111 bots). */
    static final int MAX_COMBAT = 70;
    /** v1.2 (v1.1 pair: 3 errands in 2 h, 0 completions): the errand start / completion cooldown drops 45 -> 20 min (an abandon
     *  keeps COOLDOWN_MS); after a combat trip a bot always goes (was 1 in 3), a combat 20+ skiller goes 1 in 3. */
    static final long ERRAND_COOL_MS = 20 * 60_000L;
    static final int SKILLER_ODDS = 2, MIN_SKILLER_COMBAT = 20; // v1.3: 1 in 2 (v1.2: 1 in 3; pair: assigned 7 < 10)
    /** v1.3: after a completion, or a first abandon in a row, the bot goes back to Turael at once (no dice, no cooldown; every
     *  other guard kept) for REVISIT_MS; a second abandon in a row keeps COOLDOWN_MS. */
    static final long REVISIT_MS = 30 * 60_000L;
    /** v1.3 (v1.2 pair: Bats trips paid 5 stall teleports at the Varrock east gate 3295,3428, a door the planner routes through):
     *  a task-trip hunt whose walk paid a stall teleport is off the trip list for BAD_MS, for every bot. */
    static final long BAD_MS = 2 * 3600_000L;
    static final Map<String, Long> BAD = new java.util.concurrent.ConcurrentHashMap<>();
    /** P80 (live 24 h: 39 assigned, 4 finished, 22 abandoned, 16 "no open hunt"): -Dbots.slayer.openCheck (default off = v1.3 code).
     *  At Turael only the tasks this bot has an open hunt for (openGate: the chooseJob gates, from where it stands) count as allowed,
     *  so a task with no open hunt is re-rolled like a size re-roll (OPEN_REROLLS rolls); an abandon logs why. */
    static final boolean OPEN_CHECK = flag(System.getProperty("bots.slayer.openCheck"));
    static final int OPEN_REROLLS = Math.max(1, Integer.getInteger("bots.slayer.openRerolls", 6)), OPEN_PLANS = 6; /* bughunt-a knob, default unchanged: live 20 h had 40 of 71 errands end "no allowed task after re-rolls" */
    /** P80 round 2: the per-think open-spot watch and the early abandon after EARLY_MS with no open spot. */
    static final long WATCH_MS = 60_000L, EARLY_MS = 5 * 60_000L;
    /** v1.2: Turael re-rolls a task above MAX_AMOUNT kills, AMOUNT_ROLLS times at most (the last roll is kept whatever its size). */
    static final int MAX_AMOUNT = 25, AMOUNT_ROLLS = 3;
    /** B43 audit item 3 (live 24 h: 22 abandons a day, a free act no player has): -Dbots.slayer.park (default off = P80 code; needs
     *  openCheck, whose watch is the way back). Both abandon sites (watch early, tripEnd 15 min) park instead: the core task stays,
     *  no task trip for PARK_MS, other work as normal, watch unparks the moment openSpot finds a spot. Last resort (parkStep EXPIRE):
     *  PARK_EXPIRE_PARKS parks AND PARK_EXPIRE_AGE_MS old AND still closed = silent clear(). */
    static final boolean PARK_FLAG = flag(System.getProperty("bots.slayer.park"));
    static final boolean PARK = parkOn(PARK_FLAG, OPEN_CHECK);
    static final long PARK_MS = 30 * 60_000L, PARK_EXPIRE_AGE_MS = 6 * 3600_000L;
    static final int PARK_EXPIRE_PARKS = 3, WATCH_PLANS = 2, WATCH_PLANS_PARKED = 4;
    enum ParkStep { TRIP, PARK, EXPIRE }

    /** B43 audit item 5, master ladder C1 (-Dbots.slayer.vannaka, default off = Turael only; needs items 1 foodGate and 3 park,
     *  so the effective switch is VANNAKA_FLAG && PARK && foodGate). masterFor picks Turael or Vannaka at the errand start;
     *  Vannaka tasks get no MAX_AMOUNT re-roll (players take 60-120 there), food gate + park instead. */
    static final boolean VANNAKA_FLAG = Boolean.getBoolean("bots.slayer.vannaka");
    static final boolean VANNAKA = ladderOn(VANNAKA_FLAG, PARK, P87Fight.FOOD);
    /** Item 6 (-Dbots.slayer.points, needs item 5: Turael pays 0). Engine facts, read in upstream source 2026-11-16
     *  (content/global/skill/slayer): the streak is SlayerFlags.taskStreak, a plain int saved as "taskStreak", +1 per finished
     *  task in SlayerManager.process (the NPCKilled hook); nothing in the engine ever resets it (Turael's reroll in
     *  SlayerMasterDialogue stage 701 only calls generate()), so our swap resets it by hand as the 2009 game does.
     *  isCanEarnPoints = SlayerFlags bit 20, set when completedTasks reaches 4 (or >= 4 at load); no Smoking Kills check. Points
     *  are paid only by that hook and only for a non-Turael master; we never add points. Cancel = SlayerRewardPlugin's
     *  purchase(30): setSlayerPoints(points - 30) then clear(), which is what we do. */
    static final boolean POINTS = Boolean.getBoolean("bots.slayer.points") && VANNAKA;
    /** Item 7 (-Dbots.slayer.bankFirst): the runner's normal trip end already banks the haul (tripEnd runs after it), so this is
     *  the ordering: on completion, a full-ish pack or too little food restocks (buyFood + packFood) before the master errand. */
    static final boolean BANK_FIRST = Boolean.getBoolean("bots.slayer.bankFirst");
    /** Item 8 (-Dbots.slayer.poorOk, needs item 1): no MIN_COINS rule on the errand; the far + under 5k walk gate stays. */
    static final boolean POOR_OK = Boolean.getBoolean("bots.slayer.poorOk") && P87Fight.FOOD;
    static final Location VANNAKA_AT = Location.create(3147, 9914, 0); // npc_spawns.json 1597, Edgeville Dungeon
    static final int LADDER_TURAEL_TOP = 45, VANNAKA_MIN = 40, VANNAKA_MAX = 99, VANNAKA_DONE = 2, VANNAKA_OPEN_MIN = Math.max(1, Integer.getInteger("bots.slayer.vannakaOpenMin", 5)), VANNAKA_NPC_LEVEL = 60; /* bughunt-a knob, default unchanged: farm soaks never saw 5 open (24 ladder lines, open 0-2 of 13, 0 Vannaka errands) */
    static final int CANCEL_POINTS = 30, BLOCK_PARKS = 2, HALF_PACK = 14;
    /** slayer4 (-Dbots.slayer.vannakaDoor, needs the Vannaka ladder): Vannaka is measured from VANNAKA_DOOR, a surface tile by the
     *  Edgeville trapdoor hut (the dungeon is 6400 tiles north, so every surface bot read "far"). Near the door: walk there and climb
     *  down free; far: one paid teleport onto VANNAKA_AT itself (audit L3); the errand end climbs back up free. Off: the old numbers. */
    static final boolean DOOR = Boolean.getBoolean("bots.slayer.vannakaDoor") && VANNAKA;
    /** w9 lane D (-Dbots.slayer.chaeldar, default off; needs the Vannaka ladder, so the switch is the flag && VANNAKA): Chaeldar in
     *  Zanaris for combat 70+ bots with Lost City done. Every rule lives in W9Chaeldar; each hook here tests CHAELDAR first. */
    static final boolean CHAELDAR = Boolean.getBoolean("bots.slayer.chaeldar") && VANNAKA;
    /** w13 lane B (-Dbots.slayer.duradel, default off; needs the Chaeldar switch): Duradel in Shilo Village for combat 100+ bots with
     *  Slayer 50 and Shilo Village seeded. Every rule lives in W13Duradel; each hook here tests DURADEL first. */
    static final boolean DURADEL = Boolean.getBoolean("bots.slayer.duradel") && CHAELDAR;
    /** slayer6 (-Dbots.slayer.fix6, default off = the code above exactly). A: the errand is decided before leaving (the open set as it
     *  will be at the master, after the fare) and the roll loop runs to FIX6_ROLLS; B: the ladder needs VANNAKA_OPEN_MIN6 open tasks
     *  (2: Ghouls 59 + Moss giants 67 is the first pair the hunt map offers) and a 70-99 bot without Vannaka keeps Turael. */
    static final boolean FIX6 = Boolean.getBoolean("bots.slayer.fix6");
    static final int FIX6_ROLLS = Math.max(1, Integer.getInteger("bots.slayer.fix6Rolls", 40)), VANNAKA_OPEN_MIN6 = Math.max(1, Integer.getInteger("bots.slayer.vannakaOpenMin", 2));
    static final long FIX6_SHORT_MS = 10 * 60_000L;
    /** slayer7 (-Dbots.slayer.fix7, default off, needs fix6; off = the code above exactly). The 40-58 band had 0 open Vannaka tasks:
     *  the only Hill Giant hunt sat behind the brass key door in Edgeville Dungeon ("unreachable"). On: the Hill Giant hunt moves to
     *  the 6 surface spawns north of the Observatory (HILL7; p38-navport BFS on nav-p0.bin: every spawn tile walkable from the
     *  Edgeville door, Lumbridge, Ardougne; no Gates box, no pocket, only harmless salamanders near), its job level is VANNAKA_MIN
     *  (the engine hands Hill Giants out from combat 25, the general 1.3x+5 rule said 41), and under combat LOW7_TOP (59, where
     *  Ghouls open) one open task is enough for Vannaka, on a coin against Turael so the bot keeps its Turael variety. */
    static final boolean FIX7 = Boolean.getBoolean("bots.slayer.fix7") && FIX6;
    static final Location HILL7 = Location.create(2440, 3211, 0);
    static final String HILL7_NAME = "Hill Giant";
    static final int LOW7_TOP = 59, OPEN_MIN7 = 1;
    /** slayer8 (-Dbots.slayer.fix8, default off, needs fix6; off = the code above exactly). Soaks s55/s56 (54 tasks, 25 finished,
     *  23 parks): the open set at the master and the task trip did not use the same rules. On:
     *  a) seats: a task's holder counts 2 spawns per bot at a surface spot (cap8; the general rule stays 3), and the open set
     *     at the master also counts the holders still waiting for that spot (waiting8), so a 1-seat spot is not handed to 4 bots;
     *  b) the Black bear spot covers 3 bears, not 2 (BEAR8), and ALT8 adds extra spots for a task's holders only, each checked
     *     against the spawn file at build time (HuntMap) and walkable on nav-p0.bin (p38-navport BFS from Turael and Ardougne);
     *  c) the trip comes first: a bot under BROKE8 gp gets no errand (tripDue never sends a broke bot), a held task whose spot is
     *     open goes before a player's errand or the crew leader's call (tripFirst), and among the open spots one that holds only
     *     the task's monsters within walking range wins over a mixed one (better8: "Bones (goblins, men, rats)" made no kills);
     *  d) a task dropped after parks (expired, cancelled, swapped) is not taken again for SHUT8_MS (minus8). */
    static final boolean FIX8 = Boolean.getBoolean("bots.slayer.fix8") && FIX6;
    /**
     * slayer9 (-Dbots.slayer.fix9, default off): a WOLVES / DOG task trip to the Desert Wolf spot [3258, 3078] ended "No bank close
     * enough to walk to" at its first full pack: AfkTasks.Runner.nearestBank0 leaves Al Kharid's bank (x 3270) out for anyone west
     * of the toll gate line (x under 3268), and the spot is 10 tiles west of that line, yet in the desert, where the way to Al
     * Kharid is the Shantay Pass (Runner.passVia / passHop, free going north), not the toll gate. With the flag a bot on the
     * desert side of the pass (y under 3140 and Runner.desertSide) keeps that bank; everywhere else the old rule holds.
     * tollShut9 is nearestBank0's rule itself (and Brain2.bankIndex's with the flag): true = this bank is skipped from here.
     */
    static final boolean FIX9 = Boolean.getBoolean("bots.slayer.fix9");
    static boolean tollShut9(boolean fix9, Location bank, Location from, java.util.function.Predicate<Location> desertSide) {
        if (bank.getX() != 3270 || from.getX() >= 3268) return false; // not Al Kharid's bank, or asked from its side of the line
        return !(fix9 && from.getY() < 3140 && desertSide != null && desertSide.test(from));
    }
    /**
     * slayer9b (-Dbots.slayer.fix9b with fix9, default off): fix9's bank pick (Runner.nearestBank0) was reached, but the next task
     * trip's planner was not: Slayer.openGate and Brain.chooseJob ask SmartAdventurers.plannedTiles for ONE planned leg, and the
     * desert is a nav region of its own, so from Al Kharid's bank (where fix9 leaves the bot) the leg to the Desert Wolf spot is
     * "no route" -> noRoute(p73) -> no open hunt -> the task parks. fix9's two legs across the pass were only in bankLegTiles,
     * behind brain.fix2. With fix9b plannedTiles plans the two legs the runner walks (passVia, passHop) for any walk across the pass.
     */
    static final boolean FIX9B = FIX9 && Boolean.getBoolean("bots.slayer.fix9b");
    /**
     * W1 capFix (-Dbots.slayer.capFix, default off; needs clerk.fix2 as the cap does). The teleport cap's rule before:
     *   count = errand teleports + at each trip end EVERY CoinWatch paid action of the bot during the trip (launch teleport, sea
     *   crossing, boat, stall teleport, emergency teleport, the underground bank-above teleport and the teleport back down);
     *   a trip is refused (parks) when count >= TELE_CAP and the 2D distance bot -> spot (underground folded up by 6400) is over
     *   TELEPORT_MIN; a stall teleport is refused on a trip at count >= TELE_CAP; an errand walks at count >= TELE_CAP.
     * So legs that are never refused (emergency, underground bank runs) used the cap up in one trip (6/3, 8/3 in s58), and "far"
     * was not the runner's test (it walks to a known dungeon entrance, teleports to a dungeon without one whatever the 2D distance).
     * With capFix:
     *   counted: errand teleports, the trip's travel legs (launch, sea, boat), stall teleports;
     *   exempt (never refused, never counted): emergency teleports (the runner's own 2 a trip) and, after the trip's first paid
     *   leg, the underground legs (bank-above teleport for food, the teleport back down to a dungeon spot);
     *   far = the runner's launch test (capFar): across the sea or the Salve = far; surface -> underground = the distance to the
     *   known entrance over TELEPORT_MIN, no known entrance = far; underground -> surface = the distance from the climb-out's top
     *   over TELEPORT_MIN, no climb-out = far; else the straight distance over TELEPORT_MIN.
     */
    static final boolean CAP_FIX = Boolean.getBoolean("bots.slayer.capFix");
    static volatile int EXEMPT;
    /** capFix far test (pure). viaEntrance / viaClimb: -1 = none known. Flag off = the old 2D test (flat). */
    static boolean capFar(boolean capFix, double flat, boolean fromUnder, boolean toUnder, double direct, double viaEntrance, double viaClimb, boolean water, int min) {
        if (!capFix) return flat > min;
        if (water) return true;
        if (!fromUnder && toUnder) return viaEntrance < 0 || viaEntrance > min;
        if (fromUnder && !toUnder) return viaClimb < 0 || viaClimb > min;
        return direct > min;
    }
    /** capFix: is this paid leg exempt from the cap? (pure) kind: "emergency", "bank-above", "return" (a goTo teleport; legsPaid = the runner's paid goTo teleports before it). */
    static boolean capExempt(boolean capFix, boolean onTrip, String kind, boolean underground, int legsPaid) {
        if (!capFix || !onTrip || kind == null) return false;
        return kind.equals("emergency") || kind.equals("bank-above") && underground || kind.equals("return") && underground && legsPaid > 0;
    }
    /** capFix: the trip's counted legs = its paid actions minus the exempt ones (pure). Flag off = all of them. */
    static int capCounted(boolean capFix, int paid, int exempt) { return capFix ? Math.max(0, paid - Math.max(0, exempt)) : paid; }
    /** capFix: the running trip's counted legs from the CoinWatch count (pure). CoinWatch clears PAID each hour: after a reset (nowCount under snap)
     *  paidDelta counts only the legs since it, so the exempt legs (most of them before it) are not taken off: never under-counted. */
    static int tripCounted(boolean capFix, int snap, int nowCount, int exempt) { return capCounted(capFix, paidDelta(snap, nowCount), nowCount >= snap ? exempt : 0); }
    /** capFix hook (AfkTasks.Runner, right after a paid emergency / bank-above / goTo teleport): notes an exempt leg of the running task trip. Never throws. */
    static void exemptLeg(Player p, String kind, boolean underground, int legsPaid) {
        if (!ON || !CAP_FIX || !Ledger.CLERK_FIX2 || p == null) return;
        try {
            SmartAdventurers.Brain b = SmartAdventurers.BRAINS.get(p.getUsername().toLowerCase(Locale.ROOT));
            if (b == null) return;
            St s = b.slaySt;
            if (!capExempt(true, s.trip != null && s.paidSnap >= 0, kind, underground, legsPaid)) return;
            s.exempt++; EXEMPT++;
            System.out.println("[Slayer] " + p.getUsername() + " exempt leg (" + kind + ", trip " + s.exempt + ", task total stays " + s.teles + "/" + TELE_CAP + ")");
        } catch (Throwable ignored) { }
    }
    static final int BROKE8 = 400; // SmartAdventurers.Brain.broke()'s line
    /** W2 lane S (-Dbots.slayer.brokeTrip, default off; off = the code below exactly). A bot under the broke line (400 gp) that holds
     *  a task never got a task trip: taskTrip hands chooseJob's broke flag to tripDue, whose `!broke` refuses it every time, and
     *  tripFirst refuses it the same way, so the task sat until the 15-minute abandon or a park. On: a broke holder still takes the
     *  trip when it is free = the spot is a walk away (tripFar false: no teleport fare; chooseJob and openGate already drop a spot
     *  past TELEPORT_MIN for a bot under 5,000 gp). Dice, spot cap, park and teleport cap are unchanged. */
    static final boolean BROKE_TRIP = Boolean.getBoolean("bots.slayer.brokeTrip");
    static int BROKE_TRIPS;
    /** brokeTrip: is this trip free for a broke bot (flag on, broke, the spot within walking range). */
    static boolean freeTrip(boolean flag, boolean broke, boolean far) { return flag && broke && !far; }
    /** brokeTrip: what tripDue / tripFirst8 are told: a broke bot on a free trip is not stopped as broke. */
    static boolean brokeStops(boolean broke, boolean free) { return broke && !free; }
    static final long SHUT8_MS = 60 * 60_000L;
    static final Location BEAR8 = Location.create(2972, 3494, 0);
    static final String BEAR8_NAME = "Black bear";
    static final class Alt8 { final String name, tag; final Location at; Alt8(String name, String tag, int x, int y) { this.name = name; this.tag = tag; this.at = Location.create(x, y, 0); } }
    /** npc_spawns.json: Grizzly bear 105 at 2696,3329 + 2708,3336 and at 2412,3378 + 2419,3372; Giant bat 78 at 2756,3400 + 2759,3402. */
    static final Alt8[] ALT8 = { new Alt8("Grizzly bear", "Ardougne east", 2696, 3329), new Alt8("Grizzly bear", "Outpost", 2412, 3378), new Alt8("Giant bat", "Keep Le Faye", 2756, 3400) };
    /** slayer6 C (-Dclerk.fix2): at most TELE_CAP paid teleports per Slayer task (the errand that fetched it + its task trips). */
    static final int TELE_CAP = 3;
    enum Tele { PAY, WALK, CAPPED }
    static final Location VANNAKA_DOOR_HINT = Location.create(3094, 3470, 0);
    static final int VANNAKA_NEAR = 60;
    /** slayer4 rider (brain audit M1, -Dbots.slayer.settleFirst): the trip-end bookkeeping runs before the plank/farm/bone/summon errands. */
    static final boolean SETTLE_FIRST = Boolean.getBoolean("bots.slayer.settleFirst");
    private static volatile Location doorAt;
    private static volatile long doorTry;
    static volatile int DOOR_DOWN, DOOR_UP, DOOR_PAID;

    /** VANNAKA_DOOR resolved with Surveyor.walkable (map data, so at runtime; retried at most once a minute), or null (flag off or no tile: old behaviour). */
    static Location door() {
        if (!DOOR) return null;
        Location d = doorAt;
        if (d != null) return d;
        long now = System.currentTimeMillis();
        if (now - doorTry < 60_000) return null;
        doorTry = now;
        try { d = Surveyors.Surveyor.walkable(VANNAKA_DOOR_HINT); } catch (Throwable t) { d = null; }
        if (d != null && d.getZ() == 0 && d.getY() < 6400) { doorAt = d; System.out.println("[Slayer] Vannaka door at " + d.getX() + "," + d.getY()); }
        return doorAt;
    }
    /** Underground in Edgeville Dungeon by Vannaka. Pure. */
    static boolean underVannaka(Location l) { return l != null && l.getZ() == 0 && l.getY() > 6400 && l.getDistance(VANNAKA_AT) <= VANNAKA_NEAR; }
    /** slayer4: the distance the walk gates use. Vannaka with a door: from the door, 0 underground by him; else (or no door) the old getDistance. Pure. */
    static double masterDist(Location here, Master m, Location door) {
        if (DURADEL && m == Master.DURADEL) return W13Duradel.dist(here); // w13 lane B: 0 inside Shilo, else to the cart
        if (CHAELDAR && m == Master.CHAELDAR) return W9Chaeldar.dist(here); // w9 lane D: 0 inside Zanaris, else to the shed
        if (m != Master.VANNAKA || door == null) return here.getDistance(masterAt(m));
        if (underVannaka(here)) return 0;
        return here.getDistance(door);
    }
    static double masterDist(Player bot, Master m) { return masterDist(bot.getLocation(), m, door()); }
    enum Blocked { PARK, CANCEL, SWAP }
    /** Vannaka v1 deny list (spec 1.1): special-item or awkward tasks; level 2+ tasks are out by levelReq anyway. */
    static final Set<String> VANNAKA_DENY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("BANSHEE", "BANSHEES", "CAVE_CRAWLERS",
            "ROCK_SLUGS", "DESERT_LIZARDS", "LIZARDS", "BASILISKS", "COCKATRICE", "COCKATRICES", "HARPIE_BUG_SWARMS", "KILLERWATTS",
            "MOGRES", "WALL_BEASTS", "CAVE_BUG", "CAVE_BUGS", "CAVE_SLIME", "CAVE_SLIMES")));

    /** Turael tasks a bot can do: level 1 Slayer, no gear, a JOBS hunt matches by name (spec section 4). */
    static final Set<Tasks> ALLOW = Collections.unmodifiableSet(EnumSet.of(Tasks.BATS, Tasks.BEARS, Tasks.BIRDS, Tasks.COWS, Tasks.DOG,
            Tasks.DWARF, Tasks.GHOSTS, Tasks.GOBLINS, Tasks.MONKEYS, Tasks.SKELETONS, Tasks.SPIDERS, Tasks.WOLVES, Tasks.ZOMBIES));
    /** ALLOW minus the tasks no JOBS entry serves (checked once, lazily, when the first errand starts). */
    private static volatile Set<Tasks> servedD; // w13 lane B (Duradel)
    private static volatile Set<Tasks> served, servedV, servedC; // servedC: w9 lane D (Chaeldar)

    /** Per-bot state (the Brain keeps `slay` itself for the busy lists). */
    static final class St {
        long start, lastProgress, nextErrand, skipUntil;
        int lastAmount;
        Tasks had; // the task it holds, null when none
        Set<String> names = Collections.emptySet(); // lower-case npc names of the task, for the task trip match
        SmartAdventurers.Job trip; // v1.1: the task-trip hunt it is on (spot cap), null otherwise
        long noMatchLog; // v1.1: rate limit for the no-matching-hunt line
        boolean streak; // v1.1: the last trip was a task trip that made kills: go back (no dice) until done, like a player
        long revisitUntil; // v1.3: straight back to Turael (no dice, no cooldown) until then
        int abandonRun; // v1.3: abandons in a row (a completion resets it)
        long nextWatch, closedSince; // P80 round 2: watch rate, first minute with no open spot (0 = open)
        int trips; String lastTrip; // P80: task trips taken on this task and the last spot (abandon reason)
        long assignedAt, parkedUntil; int parks; boolean parkFresh; // B43 park: task age, park window (0 = not parked), parks on this task, next watch is the first after a park
        Master master; // items 5/6: master of the task held (null = Turael)
        Master errandTo; // item 5: the master this errand walks to (null = Turael)
        int done; // item 5: tasks this bot finished this session (the ladder also reads core completedTasks)
        Blocked pending; // item 6: CANCEL or SWAP planned after BLOCK_PARKS parks; started at the next trip end
        long settledAt; int settled; // slayer4 rider M1: settleFirst result for this think (settle() code), 0 = none
        int exempt; // capFix: exempt paid legs of the running trip
        int teles, paidSnap = -1; long capLog; // slayer6 C: paid teleports on this task, the CoinWatch count at the task trip start, log rate
        Set<Tasks> pre; String preWhy; // slayer6 A: chooseMaster's predicted Vannaka open set, reused by the errand's pre-travel check
        Tasks shut; long shutUntil; // slayer8 d: the task dropped after parks, not taken again until then
    }

    // ---------------- pure functions (TestP54SlayerPick) ----------------

    static boolean flag(String v) { return v != null && v.equalsIgnoreCase("true"); }

    static boolean allowed(Tasks t, Set<Tasks> allow) { return t != null && allow.contains(t); }

    /** Re-roll rule: the first roll plus at most MAX_REROLLS more; returns the allowed task or null (skip window). */
    static Tasks pick(java.util.function.Supplier<Tasks> roll, Runnable clear, Set<Tasks> allow) { return pick(roll, clear, allow, MAX_REROLLS); }
    static Tasks pick(java.util.function.Supplier<Tasks> roll, Runnable clear, Set<Tasks> allow, int rerolls) {
        for (int i = 0; i <= rerolls; i++) {
            Tasks t = roll.get();
            if (allowed(t, allow)) return t;
            clear.run();
        }
        return null;
    }

    static boolean levelOk(int combat) { return combat > 0 && combat < MAX_COMBAT; }

    /** v1.2 errand eligibility: under combat 70, and either the last job was combat or the bot is a combat 20+ skiller. */
    static boolean eligible(boolean lastWasCombat, boolean hadJob, int combat) {
        return hadJob && levelOk(combat) && (lastWasCombat || combat >= MIN_SKILLER_COMBAT);
    }
    /** v1.2 errand dice: 1 in 1 after a combat trip, 1 in SKILLER_ODDS after a skilling one. */
    static int errandOdds(boolean lastWasCombat) { return lastWasCombat ? 1 : SKILLER_ODDS; }

    /** v1.2 size re-roll: pick, then while the amount is over MAX_AMOUNT clear and pick again, AMOUNT_ROLLS times at most;
     *  the last roll is kept whatever its size, null only when a pick finds no allowed task. sizeRolls[0] counts the re-rolls. */
    static Tasks pickSmall(java.util.function.Supplier<Tasks> roll, java.util.function.IntSupplier amount, Runnable clear, Set<Tasks> allow, int[] sizeRolls) {
        return pickSmall(roll, amount, clear, allow, sizeRolls, MAX_REROLLS);
    }
    static Tasks pickSmall(java.util.function.Supplier<Tasks> roll, java.util.function.IntSupplier amount, Runnable clear, Set<Tasks> allow, int[] sizeRolls, int rerolls) {
        Tasks t = pick(roll, clear, allow, rerolls);
        for (int i = 0; t != null && i < AMOUNT_ROLLS && amount.getAsInt() > MAX_AMOUNT; i++) {
            clear.run();
            if (sizeRolls != null) sizeRolls[0]++;
            t = pick(roll, clear, allow, rerolls);
        }
        return t;
    }

    /** v1.3: the revisit window is open. */
    static boolean revisitOn(long revisitUntil, long now) { return revisitUntil > 0 && now < revisitUntil; }
    /** v1.3: after an abandon: the first in a row revisits (cooldown 0, window REVISIT_MS), the next ones keep COOLDOWN_MS. Returns {nextErrand, revisitUntil}. */
    static long[] afterAbandon(int abandonRun, long now) { return abandonRun <= 1 ? new long[] { now, now + REVISIT_MS } : new long[] { now + COOLDOWN_MS, 0 }; }
    /** v1.3: a trip spot is open unless a stall marked it under BAD_MS ago. */
    static boolean spotOk(Map<String, Long> bad, String label, long now) { Long t = label == null ? null : bad.get(label); return t == null || now >= t; }
    /** v1.3 tool-loop guard (pure): the same tool bought on foot under 10 minutes ago and still not held = it can't be held. */
    static boolean toolLoop(int boughtId, long boughtAt, int[] tools, boolean held, long now) {
        if (held || tools == null || boughtId <= 0 || now - boughtAt >= 10 * 60_000L) return false;
        for (int t : tools) if (t == boughtId) return true;
        return false;
    }

    /** B43 park (pure): an open spot = go (TRIP); closed with PARK_EXPIRE_PARKS+ parks behind it AND PARK_EXPIRE_AGE_MS+ old = EXPIRE;
     *  else PARK. */
    static ParkStep parkStep(int parks, long ageMs, boolean open) {
        if (open) return ParkStep.TRIP;
        return parks >= PARK_EXPIRE_PARKS && ageMs >= PARK_EXPIRE_AGE_MS ? ParkStep.EXPIRE : ParkStep.PARK;
    }
    /** B43: park needs its flag and openCheck (the watch is the only way back from a park). */
    static boolean parkOn(boolean park, boolean openCheck) { return park && openCheck; }
    /** B43: inside the park window (no task trip, no new park or expire). */
    static boolean parkedNow(boolean on, long parkedUntil, long now) { return on && parkedUntil > 0 && now < parkedUntil; }
    /** B43: watch's openSpot plan budget, 4 on the first check after a park (a far spot may be the open one), else 2 as before. */
    static int watchPlans(boolean on, boolean parkFresh) { return on && parkFresh ? WATCH_PLANS_PARKED : WATCH_PLANS; }

    static boolean abandonDue(boolean hasTask, long lastProgress, long now) { return hasTask && lastProgress > 0 && now - lastProgress >= ABANDON_MS; }

    static boolean errandDue(boolean on, boolean hasTask, long now, long skipUntil, long nextErrand, long coins, boolean combatJob,
                             boolean questBusy, boolean otherErrand, boolean inCombat, boolean dice) {
        return errandDue(on, hasTask, now, skipUntil, nextErrand, coins, combatJob, questBusy, otherErrand, inCombat, dice, false);
    }
    /** Item 8: poorOk drops the MIN_COINS rule (the food gate in openGate keeps a poor bot on foodNeed-0 tasks). */
    static boolean errandDue(boolean on, boolean hasTask, long now, long skipUntil, long nextErrand, long coins, boolean combatJob,
                             boolean questBusy, boolean otherErrand, boolean inCombat, boolean dice, boolean poorOk) {
        return on && !hasTask && now >= skipUntil && now >= nextErrand && (poorOk || coins >= MIN_COINS) && combatJob && !questBusy && !otherErrand && !inCombat && dice;
    }

    // ---------------- items 5-8 pure functions (TestSlayerLadder) ----------------

    static boolean ladderOn(boolean flag, boolean park, boolean foodGate) { return flag && park && foodGate; }

    /** Item 5: Vannaka when canVannaka, done >= 2 and combat 40-99; then Turael only under 45 (overlap 40-44: the coin). Without
     *  Vannaka, Turael under MAX_COMBAT as today; null = no Slayer (combat 100+, or 70+ with no Vannaka). */
    static Master masterFor(int combat, int done, boolean canVannaka, boolean coin) {
        if (combat <= 0) return null;
        boolean v = canVannaka && done >= VANNAKA_DONE && combat >= VANNAKA_MIN && combat <= VANNAKA_MAX;
        boolean t = combat < (v ? LADDER_TURAEL_TOP : MAX_COMBAT) || (done < VANNAKA_DONE && combat >= MAX_COMBAT && combat <= VANNAKA_MAX); /* slayer3 (audit MED): 70-99 with under 2 done earn them at Turael, else they could never reach Vannaka */
        if (v && t) return coin ? Master.VANNAKA : Master.TURAEL;
        return v ? Master.VANNAKA : t ? Master.TURAEL : null;
    }

    /** bughunt-a: the canVannaka value chooseMaster hands masterFor (pure), so the unit goes through the caller's own rule and cannot pass a value no caller makes. */
    static boolean canVannaka(int combat, int eff, int done, boolean coreReq, boolean food, int open) {
        return done >= VANNAKA_DONE && combat >= VANNAKA_MIN && combat <= VANNAKA_MAX && eff >= VANNAKA_MIN && coreReq && food && open >= VANNAKA_OPEN_MIN;
    }
    /** slayer6 B: canVannaka with the open minimum given (fix6: VANNAKA_OPEN_MIN6). */
    static boolean canVannaka(int combat, int eff, int done, boolean coreReq, boolean food, int open, int min) {
        return done >= VANNAKA_DONE && combat >= VANNAKA_MIN && combat <= VANNAKA_MAX && eff >= VANNAKA_MIN && coreReq && food && open >= min;
    }
    /** slayer6 B: chooseMaster's decision with the flag as an argument. Off = ladder(). On: the minimum is VANNAKA_OPEN_MIN6, and a
     *  combat 70-99 bot that cannot have Vannaka keeps Turael (safe tasks, the streak and the count go on) instead of no master. */
    static Master ladder6(int combat, int eff, int done, boolean coreReq, boolean food, int open, boolean coin, boolean fix6) {
        if (!fix6) return ladder(combat, eff, done, coreReq, food, open, coin);
        Master m = masterFor(combat, done, canVannaka(combat, eff, done, coreReq, food, open, VANNAKA_OPEN_MIN6), coin);
        if (m == null && combat >= MAX_COMBAT && combat <= VANNAKA_MAX) m = Master.TURAEL;
        return m;
    }
    /** slayer7: the open minimum chooseMaster uses for this bot. Off (or combat LOW7_TOP+) = VANNAKA_OPEN_MIN6. Pure. */
    static int openMin7(boolean fix7, int combat) { return fix7 && combat < LOW7_TOP ? Math.min(OPEN_MIN7, VANNAKA_OPEN_MIN6) : VANNAKA_OPEN_MIN6; }
    /** slayer7: chooseMaster's decision. Off = ladder6. On: where ladder6 says Vannaka nothing changes; a bot under LOW7_TOP that
     *  only fails the open minimum (1 open task, in practice Hill Giants) goes to Vannaka on the coin, else keeps ladder6's answer. */
    static Master ladder7(int combat, int eff, int done, boolean coreReq, boolean food, int open, boolean coin, boolean fix6, boolean fix7) {
        Master m = ladder6(combat, eff, done, coreReq, food, open, coin, fix6);
        if (!fix7 || !fix6 || m == Master.VANNAKA || combat >= LOW7_TOP) return m;
        if (canVannaka(combat, eff, done, coreReq, food, open, openMin7(true, combat)) && coin) return Master.VANNAKA;
        return m;
    }
    /** slayer7: the Hill Giant hunt spot HuntMap builds (on: the surface cluster when the spawn file has 3+ spawns there). Pure. */
    static Location hillSpot(boolean fix7, String name, Location curated, int surfaceSpawns) { return fix7 && HILL7_NAME.equals(name) && surfaceSpawns >= 3 ? HILL7 : curated; }
    /** slayer7: the level of a hunt job (SmartAdventurers' JOBS build): Hill Giants at VANNAKA_MIN when on. Pure. */
    static int huntLevel7(boolean fix7, String name, int level) { return fix7 && HILL7_NAME.equalsIgnoreCase(name) ? Math.min(level, VANNAKA_MIN) : level; }
    /** openGate's level rule (pure, unchanged): the job level plus 10 per recent death there; a magic user needs 2x its level + 10. */
    static int needLevel(int jobLevel, int deaths, boolean magic, int npcLevel) { int need = jobLevel + 10 * deaths; return magic ? Math.max(need, npcLevel * 2 + 10) : need; }
    /** slayer7 b: paid teleports on the task so far, the running trip's included (CoinWatch count since the trip start). Pure. */
    static int tripTeles(int teles, int snap, int nowCount) { return teles + paidDelta(snap, nowCount); }
    /** slayer7 b: may a stalled task trip pay a stall teleport? (pure) Off or not on a task trip = yes, as before; on = only under TELE_CAP. */
    static boolean stallStep(boolean fix2, boolean onTrip, int used) { return !fix2 || !onTrip || teleStep(true, used, true) == Tele.PAY; }
    /** slayer7 b (AfkTasks stall teleport hook, -Dclerk.fix2): false = the cap is reached, no stall teleport; the runner then ends
     *  the trip as it does for a bot with no fare ("Couldn't find a path"), and the spot goes off the trip list as after a paid stall.
     *  The no-food / one-hit escape, the underground bank teleport and a mid-trip long leg are never refused here. */
    static boolean stallOk(Player p) {
        if (!ON || !Ledger.CLERK_FIX2 || p == null) return true;
        try {
            SmartAdventurers.Brain b = SmartAdventurers.BRAINS.get(p.getUsername().toLowerCase(Locale.ROOT));
            if (b == null) return true;
            St s = b.slaySt; int used = s.teles + tripCounted(CAP_FIX, s.paidSnap, paidCount(p), s.exempt);
            if (stallStep(true, s.trip != null, used)) return true;
            CAPPED++; STALL_CAPPED++;
            System.out.println("[Slayer] " + p.getUsername() + " teleport cap " + used + "/" + TELE_CAP + " on " + (s.had != null ? s.had.name() : "?") + ": no stall teleport at " + p.getLocation());
            stalled(p, p.getLocation());
            return false;
        } catch (Throwable t) { return true; }
    }
    // ---------------- slayer8 pure functions (TestSlayerLadder) ----------------
    /** slayer8 a: the seats of a hunt spot for a holder of its task: 2 spawns per bot (4 at most), never under the general capacity.
     *  spawns 0 = not counted from the spawn file (a dungeon spot): the general capacity. */
    static int cap8(boolean fix8, boolean holder, int cap, int spawns) { return fix8 && holder && spawns > 0 ? Math.max(cap, Math.min(4, (spawns + 1) / 2)) : cap; }
    /** slayer8 a: openGate's crowd gate. null = a seat is free; "full" = taken now; "full+Nw" = free now, but N holders wait for it. */
    static String full8(boolean fix8, int busy, int party, int cap, int waiting) {
        if (busy + party > cap) return "full";
        return fix8 && waiting > 0 && busy + party + waiting > cap ? "full+" + waiting + "w" : null;
    }
    /** slayer8 a: the spot a waiting holder is counted at: the nearest of the ones it could go to (-1 = none). */
    static int nearest8(double[] dist, boolean[] ok) { int best = -1; for (int i = 0; i < dist.length; i++) if (ok[i] && (best < 0 || dist[i] < dist[best])) best = i; return best; }
    /** slayer8 a: could this holder take a seat there? Its level allows it and it is within walking range or holds the fare line. */
    static boolean canSeat8(int combat, int jobLevel, double dist, long coins) { return combat >= jobLevel && (dist <= AfkTasks.Runner.TELEPORT_MIN || coins >= MIN_COINS); }
    /** slayer8 b: the Black bear spot HuntMap builds: BEAR8 when the spawn file has more bears in range there than at the densest spawn tile. */
    static Location spot8(boolean fix8, String name, Location best, int bestCount, int at8) { return fix8 && BEAR8_NAME.equals(name) && at8 >= 3 && at8 > bestCount ? BEAR8 : best; }
    /** slayer8 b: an extra spot is built when the spawn file has 2+ of the main spot's own monsters there and the two fight ranges (18) do not share a spawn tile. */
    static boolean altOk8(boolean fix8, int spawns, boolean sameMonsters, double fromMain) { return fix8 && spawns >= 2 && sameMonsters && fromMain > 18; }
    /** slayer8 c: no errand for a bot the task trip would refuse (tripDue: not when broke). */
    static boolean brokeStop8(boolean fix8, long coins) { return fix8 && coins < BROKE8; }
    /** slayer8 c: the task trip goes before a player's errand or the crew leader's call: a task, its spot seen open (streak), not parked, not broke. */
    static boolean tripFirst8(boolean fix8, boolean hasTask, boolean streak, boolean parked, boolean broke) { return fix8 && hasTask && streak && !parked && !broke; }
    /** slayer8 c: every target of the job is one of the task's monsters. */
    static boolean pure8(Set<String> taskNames, String[] jobNames) {
        if (taskNames == null || taskNames.isEmpty() || jobNames == null || jobNames.length == 0) return false;
        for (String n : jobNames) if (n == null || !taskNames.contains(n.toLowerCase(Locale.ROOT))) return false;
        return true;
    }
    /** slayer8 c: chooseJob's choice among the task's open spots. Off: the nearer. On: a pure spot within walking range first, then the nearer. */
    static boolean better8(boolean fix8, boolean pure, double dist, boolean bestPure, double bestDist) {
        if (!fix8) return dist < bestDist;
        int a = pure && dist <= AfkTasks.Runner.TELEPORT_MIN ? 0 : 1, b = bestPure && bestDist <= AfkTasks.Runner.TELEPORT_MIN ? 0 : 1;
        return a != b ? a < b : dist < bestDist;
    }
    /** slayer8 d: the open set without the task this bot dropped after parks, until its window ends. */
    static Set<Tasks> minus8(boolean fix8, Set<Tasks> open, Tasks shut, long until, long now) {
        if (!fix8 || shut == null || now >= until || !open.contains(shut)) return open;
        EnumSet<Tasks> out = EnumSet.noneOf(Tasks.class); out.addAll(open); out.remove(shut);
        return out;
    }

    /** slayer6 A: a closed list whose cause can pass soon (far and poor, a full spot, a rest, food, a fare) = the short cooldown; levels and gates = SKIP_MS. */
    static long skipCool(String closed) {
        if (closed == null) return SKIP_MS;
        for (String k : new String[] { "under5k", "=full", "=fleeRest", "=noFood", "=moryFood", "Fare", "=stalled", "=blacklist" }) if (closed.contains(k)) return FIX6_SHORT_MS;
        return SKIP_MS;
    }
    /** slayer6 A: the pre-travel decision. 0 = go; above 0 = stay and skip the errand for that long (no open task at the master). */
    static long preTravel(boolean fix6, boolean openCheck, boolean has, int openN, String closed) {
        if (!fix6 || !openCheck || has || openN > 0) return 0;
        return skipCool(closed);
    }
    /** slayer6 A: the coins the bot will hold at the master (a paid teleport costs the fare). */
    static long afterFare(long coins, boolean pays, long fare) { return pays ? Math.max(0, coins - fare) : coins; }
    /** slayer6 A: the roll loop. Rolls until an allowed task, cap rolls at most; with sizeRule a task over MAX_AMOUNT is re-rolled
     *  AMOUNT_ROLLS times, and once one allowed task was seen the loop may run to 2 x cap so a size re-roll cannot end with none
     *  (D: pickSmall could throw an allowed task away and then find no other). Never returns a task outside allow. */
    static Tasks pick6(java.util.function.Supplier<Tasks> roll, java.util.function.IntSupplier amount, Runnable clear, Set<Tasks> allow, int cap, boolean sizeRule, int[] sizeRolls) {
        boolean seen = false; int size = 0;
        for (int used = 1; used <= cap * 2; used++) {
            if (used > cap && !seen) break;
            Tasks t = roll.get();
            if (allowed(t, allow)) {
                boolean big = sizeRule && amount.getAsInt() > MAX_AMOUNT;
                if (!big || size >= AMOUNT_ROLLS || used >= cap) return t;
                size++; seen = true; if (sizeRolls != null) sizeRolls[0]++;
            }
            clear.run();
        }
        return null;
    }
    /** slayer6 C: a trip or errand leg. Near = walked anyway; far = a paid teleport until TELE_CAP on this task, then CAPPED
     *  (a task trip parks, an errand walks). Flag off = PAY as before. */
    static Tele teleStep(boolean fix2, int teles, boolean far) { return !far ? Tele.WALK : !fix2 || teles < TELE_CAP ? Tele.PAY : Tele.CAPPED; }
    /** slayer6 C: paid actions during a task trip from the CoinWatch count (it restarts each hour: a lower count = counted from 0). */
    static int paidDelta(int snap, int nowCount) { return snap < 0 || nowCount < 0 ? 0 : nowCount >= snap ? nowCount - snap : nowCount; }
    static int paidCount(Player bot) {
        try { if (!CoinWatch.ON) return -1; java.util.concurrent.atomic.AtomicInteger a = CoinWatch.PAID.get(bot.getUsername().toLowerCase(Locale.ROOT)); return a == null ? 0 : a.get(); } catch (Throwable t) { return -1; }
    }
    /** slayer6 A: where the bot will stand when it talks to this master (Vannaka: the door on the surface when the door is on). */
    static Location stand(Master m) { if (DURADEL && m == Master.DURADEL) return W13Duradel.out(); if (CHAELDAR && m == Master.CHAELDAR) return W9Chaeldar.out(); if (m != Master.VANNAKA) return TURAEL; Location d = DOOR ? door() : null; return d != null ? d : VANNAKA_AT; }
    static String cut(String x, int n) { return x == null ? "" : x.length() <= n ? x : x.substring(0, n) + "..."; }

    /** bughunt-a: chooseMaster's whole decision, pure: canVannaka then masterFor. */
    static Master ladder(int combat, int eff, int done, boolean coreReq, boolean food, int open, boolean coin) { return masterFor(combat, done, canVannaka(combat, eff, done, coreReq, food, open), coin); }

    /** Item 5 errand eligibility with the ladder on: as eligible() but up to combat VANNAKA_MAX. */
    static boolean eligibleLadder(boolean lastWasCombat, boolean hadJob, int combat) {
        return hadJob && combat > 0 && combat <= VANNAKA_MAX && (lastWasCombat || combat >= MIN_SKILLER_COMBAT);
    }

    /** Item 6: what a player does with a task blocked BLOCK_PARKS parks in a row. Turael tasks always park (0 points, nothing to
     *  swap to); 30+ points cancel at the master; short of points and not a Turael task, Turael swaps it. */
    static Blocked blockedPlan(Master m, int points, int parks, boolean onTuraelList) {
        if (parks < BLOCK_PARKS || m == null || m == Master.TURAEL) return Blocked.PARK;
        if (points >= CANCEL_POINTS) return Blocked.CANCEL;
        return onTuraelList ? Blocked.PARK : Blocked.SWAP;
    }

    /** Item 7: restock before the master errand when the pack is over half full or holds less food than the next task needs. */
    static boolean bankFirst(int freeSlots, int food, int need) { return freeSlots < HALF_PACK || food < need; }

    static String masterName(Master m) {
        if (m == null || m == Master.TURAEL) return "Turael";
        String n = m.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
    static Location masterAt(Master m) { return DURADEL && m == Master.DURADEL ? W13Duradel.AT : m == Master.VANNAKA ? VANNAKA_AT : CHAELDAR && m == Master.CHAELDAR ? W9Chaeldar.AT : TURAEL; }
    static String masterPlace(Master m) { return DURADEL && m == Master.DURADEL ? "Shilo Village" : m == Master.VANNAKA ? "Edgeville Dungeon" : CHAELDAR && m == Master.CHAELDAR ? "Zanaris" : "Burthorpe"; }

    /** Turael would hand out this task (so a Turael swap cannot replace it). */
    static boolean onTuraelList(Tasks t) {
        if (t == null) return false;
        for (Master.Task mt : Master.TURAEL.tasks) if (mt != null && mt.task == t) return true;
        return false;
    }

    /** Vannaka v1 allow list: Master.VANNAKA.tasks with slayer level 1, minus VANNAKA_DENY (served() then keeps those a hunt serves). */
    static Set<Tasks> vannakaAllow() {
        EnumSet<Tasks> out = EnumSet.noneOf(Tasks.class);
        for (Master.Task mt : Master.VANNAKA.tasks) if (mt != null && mt.task != null && mt.task.levelReq <= 1 && !VANNAKA_DENY.contains(mt.task.name())) out.add(mt.task);
        return out;
    }
    static Set<Tasks> allowFor(Master m) { return DURADEL && m == Master.DURADEL ? W13Duradel.allow() : m == Master.VANNAKA ? vannakaAllow() : CHAELDAR && m == Master.CHAELDAR ? W9Chaeldar.allow() : ALLOW; }

    static boolean matches(Set<String> taskNames, String[] jobNames) {
        if (taskNames == null || taskNames.isEmpty() || jobNames == null) return false;
        for (String n : jobNames) if (n != null && taskNames.contains(n.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** v1.1 task trip gate: a task, not broke, the spot under SPOT_CAP slayer bots, and the dice (1 in TRIP_ODDS). */
    static boolean tripDue(boolean on, boolean hasTask, boolean broke, int spotSlayers, boolean dice) {
        return on && hasTask && !broke && spotSlayers < SPOT_CAP && dice;
    }

    // ---------------- server glue ----------------

    /** Once-guard (B0 2a/2c): login only when the attribute is missing; a second login would add a second hook and wipe the task. */
    static SlayerManager ensure(Player p) {
        if (p.getAttribute("slayer-manager", null) == null) new SlayerManager().login(p);
        return SlayerManager.getInstance(p);
    }

    /** Never logs in: a bot with no attribute simply has no task. */
    static boolean hasTask(Player p) {
        if (p == null || p.getAttribute("slayer-manager", null) == null) return false;
        try { return SlayerManager.getInstance(p).hasTask(); } catch (Throwable t) { return false; }
    }
    // --- player touches (-Dbots.slayer.touches, default off)
    static final boolean TOUCHES = flag(System.getProperty("bots.slayer.touches"));
    static final long TOUCH_GAP_MS = 10 * 60_000L;
    static final Map<String, Long> TOUCH_AT = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, Integer> TOTALS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Pure: a short progress line. Never empty, plain ASCII. */
    static String progressLine(String task, int left, int total, String master) {
        String t = task == null || task.trim().isEmpty() ? "these" : task.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        String m = master == null || master.trim().isEmpty() ? "the master" : master.trim();
        if (total > 0 && left * 2 == total) return "Halfway through " + m + "'s " + t + ".";
        if (left <= 3) return "Last few " + t + ", then " + m + " is off my back.";
        return left + " " + t + " to go.";
    }
    /** Pure: "<Master> has me on <task>, N left." or "No task right now." */
    static String taskReply(String task, int left, String master) {
        if (task == null || left <= 0) return "No task right now.";
        String m = master == null || master.trim().isEmpty() ? "The master" : master.trim();
        return m + " has me on " + task.trim().toLowerCase(Locale.ROOT).replace('_', ' ') + ", " + left + " left.";
    }
    /** BotChat hook: reply for a "task"/"slayer" question; null when the flag is off. */
    static String taskReplyFor(Player bot) {
        if (!TOUCHES) return null;
        try {
            if (!hasTask(bot)) return taskReply(null, 0, "Turael");
            SmartAdventurers.Brain b = SmartAdventurers.brainOf(bot);
            String task = b != null && b.slaySt != null && b.slaySt.had != null ? b.slaySt.had.name() : null;
            return task == null ? null : taskReply(task, amount(bot), masterName(b.slaySt.master));
        } catch (Throwable t) { return null; }
    }
    static void touch(SmartAdventurers.Brain b, St s, long now) {
        if (!TOUCHES) return;
        try {
            String n = b.bot.getUsername(); int left = amount(b.bot);
            Integer tot = TOTALS.get(n); if (tot == null || left > tot) { tot = left; TOTALS.put(n, tot); }
            if (SmartAdventurers.RNG.nextInt(4) != 0) return;
            Long last = TOUCH_AT.get(n); if (last != null && now - last < TOUCH_GAP_MS) return;
            TOUCH_AT.put(n, now);
            b.say(progressLine(s.had.name(), left, tot, masterName(s.master)));
        } catch (Throwable ignored) { }
    }
    static int amount(Player p) {
        if (p == null || p.getAttribute("slayer-manager", null) == null) return 0;
        try { return SlayerManager.getInstance(p).getAmount(); } catch (Throwable t) { return 0; }
    }

    static String[] jobNames(SmartAdventurers.Job j) {
        if (j == null) return null;
        if (j.hunt != null) return j.hunt.names != null ? j.hunt.names : new String[] { j.hunt.name };
        if (j.task != null && j.task.kind == AfkTasks.Kind.FIGHT) return j.task.targets;
        return null;
    }

    static Set<String> npcNames(Tasks t) {
        Set<String> s = new HashSet<>();
        for (int id : t.getNpcs()) try { NPCDefinition d = NPCDefinition.forId(id); if (d != null && d.getName() != null) s.add(d.getName().toLowerCase(Locale.ROOT)); } catch (Throwable ignored) { }
        return s;
    }

    static Set<Tasks> served() { return served(Master.TURAEL); }
    /** Per master (item 5): Turael as before; Vannaka from vannakaAllow(), printed once when built. */
    static final Set<String> NO_HUNT_SAID = java.util.concurrent.ConcurrentHashMap.newKeySet(); // brain1 (live audit L5): each "no hunt for" line once
    static Set<Tasks> served(Master master) {
        boolean v = master == Master.VANNAKA, c = CHAELDAR && master == Master.CHAELDAR; // c: w9 lane D (off: always false)
        boolean d = DURADEL && master == Master.DURADEL; // w13 lane B (off: always false)
        Set<Tasks> s = d ? servedD : c ? servedC : v ? servedV : served;
        if (s != null) return s;
        Set<String> jn = new HashSet<>();
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) { String[] n = jobNames(j); if (n != null) for (String x : n) if (x != null) jn.add(x.toLowerCase(Locale.ROOT)); }
        EnumSet<Tasks> out = EnumSet.noneOf(Tasks.class);
        for (Tasks t : allowFor(master)) { boolean m = false; for (String n : npcNames(t)) if (jn.contains(n)) { m = true; break; } if (m) out.add(t); else if (NO_HUNT_SAID.add(d ? t.name() + "D" : c ? t.name() + "C" : t.name() + v)) System.out.println("[Slayer] no hunt for " + t.name() + (c ? " (Chaeldar)" : v ? " (Vannaka)" : "")); }
        boolean hunts = false; for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null) { hunts = true; break; }
        if (jn.isEmpty() || !hunts) return out; // JOBS not built yet, or HuntMap hunts not merged yet: do not cache a partial answer
        if (d) { servedD = out; System.out.println("[Slayer] Duradel list " + out.size() + ": " + out); } else if (c) { servedC = out; System.out.println("[Slayer] Chaeldar list " + out.size() + ": " + out); } else if (v) { servedV = out; System.out.println("[Slayer] Vannaka list " + out.size() + ": " + out); } else served = out;
        return out;
    }

    /** chooseJob loop hook, after every gate (reachable, deaths, blacklist, capacity, crowd): a hunt or fight task for the task. */
    static boolean match(SmartAdventurers.Brain b, SmartAdventurers.Job j) {
        if (!ON || b.slaySt.had == null || j == null) return false; // a HuntMap hunt or an AfkTasks FIGHT task (goblins, cows): jobNames decides
        return matches(b.slaySt.names, jobNames(j)) && spotOk(BAD, j.label(), System.currentTimeMillis()); // v1.3: not a spot that stalled a trip
    }

    /** v1.3 hook (AfkTasks paid stall teleport): a bot on a task trip marks the trip hunt bad for BAD_MS. */
    static void stalled(Player p, Location at) {
        if (!ON || p == null) return;
        try {
            SmartAdventurers.Brain b = SmartAdventurers.BRAINS.get(p.getUsername().toLowerCase(Locale.ROOT));
            if (b == null || b.slaySt.trip == null) return;
            String label = b.slaySt.trip.label();
            Long was = BAD.put(label, System.currentTimeMillis() + BAD_MS); STALLS++;
            if (was == null || was < System.currentTimeMillis()) System.out.println("[Slayer] trip spot " + label + " stalled " + p.getUsername() + " at " + at + "; off the task-trip list for 2 h");
        } catch (Throwable ignored) { }
    }

    /** Slayer bots (other than me) on a task trip to this hunt right now. */
    static int spotSlayers(SmartAdventurers.Brain me, SmartAdventurers.Job j) {
        int n = 0;
        for (SmartAdventurers.Brain o : SmartAdventurers.BRAINS.values()) if (o != me && o.slaySt.trip == j && o.runner != null) n++;
        return n;
    }

    /** chooseJob end hook when no matching hunt passed the gates: one line per bot per 10 minutes (trial evidence). */
    static void noMatch(SmartAdventurers.Brain b, long now) {
        St s = b.slaySt;
        if (!ON || s.had == null || now < s.noMatchLog) return;
        s.noMatchLog = now + 10 * 60_000L; NOMATCH++;
        System.out.println("[Slayer] " + b.bot.getUsername() + " no open hunt for " + s.had.name() + " (gates/crowd), " + amount(b.bot) + " left: " + why(b));
    }

    /** Trial evidence: the matching jobs and the first gates they fail (read-only, mirrors chooseJob's checks). */
    static String why(SmartAdventurers.Brain b) {
        StringBuilder sb = new StringBuilder();
        int n = 0, combat = b.effectiveCombat();
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) {
            if (!match(b, j) || n >= 4) continue;
            n++;
            String r;
            try {
                Location sp = j.hunt != null ? j.hunt.spot : null;
                if (OPEN_CHECK) { String g = openGate(b, j, combat, System.currentTimeMillis(), new int[] { 0 }); r = g == null ? "open" : g; } // P80: the exact chooseJob gate (cached plans only)
                else if (!b.reachable(j)) r = "unreachable";
                else if (j.hunt != null && combat < 70 && SmartAdventurers.Brain.poisonous(j.hunt.name)) r = "poison";
                else if (j.hunt != null && b.deadlyFor(j.hunt, combat)) r = "deadly";
                else if (sp != null && Resources.skipHunt(b.bot, sp)) r = "skipHunt";
                else if (SmartAdventurers.recentDeaths(j.label()) >= 2) r = "deaths";
                else if (combat < j.level) r = "level " + j.level + ">" + combat;
                else if (b.blacklist.getOrDefault(j.label(), 0L) > System.currentTimeMillis()) r = "blacklist";
                else if (sp != null && sp.getDistance(b.bot.getLocation()) > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.coins(b.bot) < 5_000) r = "far+under5k";
                else if ((j.hunt != null ? SmartAdventurers.Brain.crowdHunt(j.hunt) : SmartAdventurers.Brain.crowd(j.task)) + 1 > SmartAdventurers.Brain.capacity(j)) r = "full";
                else r = "plan/fare/crowd";
                if (sp != null) r += " d" + (int) sp.getDistance(b.bot.getLocation());
            } catch (Throwable t) { r = "?" + t.getClass().getSimpleName(); }
            sb.append(j.label()).append('=').append(r).append(' ');
        }
        return n == 0 ? "no job matches" : sb.toString().trim();
    }

    /** P80 openCheck: null when job j is open for this bot from where it stands, else the first chooseJob gate it fails, in
     *  chooseJob's order, including the round-2 plan/fare/crowd gates (p80c: "plan/fare/crowd" closed every no-trip abandon):
     *  stuck leg, Morytania food, crew home, planner no-route (P73), Salve/Morytania fare, planned far+under5k, crowd + party, big.
     *  budget = new planner runs this check may make (cached plans are free); an unknown plan counts the straight line, as chooseJob. */
    static String openGate(SmartAdventurers.Brain b, SmartAdventurers.Job j, int combat, long now, int[] budget) { return openGate(b, j, combat, now, budget, null, -1); }
    /** slayer6 A: the same gates as seen from `at` with `purse` coins (null / -1 = where the bot stands, what it holds); the route
     *  planner only runs from the bot's own tile, so a stand-in more than 30 tiles away uses the straight distance. */
    static String openGate(SmartAdventurers.Brain b, SmartAdventurers.Job j, int combat, long now, int[] budget, Location at, long purse) { return openGate(b, j, combat, now, budget, at, purse, null); }
    /** slayer8 a: wait = the holders waiting per spot (waiting8; the open set at the master), null = not counted (the watch, the trip). */
    static String openGate(SmartAdventurers.Brain b, SmartAdventurers.Job j, int combat, long now, int[] budget, Location at, long purse, Map<SmartAdventurers.Job, Integer> wait) {
        if (!b.reachable(j)) return "unreachable";
        Player bot = b.bot; Location me = at != null ? at : bot.getLocation();
        boolean planHere = at == null || flat(at).getDistance(flat(bot.getLocation())) <= 30;
        Location sp = j.hunt != null ? j.hunt.spot : null;
        if (j.hunt != null) {
            String nm = j.hunt.name.toLowerCase(Locale.ROOT);
            if (nm.contains("dragon") && !b.hasAntifire()) return "dragon";
            if (combat < 70 && SmartAdventurers.Brain.poisonous(j.hunt.name)) return "poison";
            if (b.deadlyFor(j.hunt, combat)) return "deadly";
            if (Resources.skipHunt(bot, sp)) return "skipHunt";
        }
        int deaths = SmartAdventurers.recentDeaths(j.label());
        if (deaths >= 2) return "deaths";
        if (j.hunt != null) {
            int need = needLevel(j.level, deaths, SmartAdventurers.magicUser(j.hunt.name), j.hunt.npcLevel);
            if (combat < need) return "level " + need + ">" + combat;
        }
        if (b.blacklist.getOrDefault(j.label(), 0L) > now) return "blacklist";
        if (!b.stuckLegs.isEmpty() && b.stuckLegs.getOrDefault(SmartAdventurers.legKey(j.label(), me), 0L) > now) return "stuckLeg";
        if (j.hunt != null && SmartAdventurers.Brain.MORY_FOOD_MIN > 0 && Preflight.ON && Preflight.morytania(sp) && b.foodFor(j.hunt) < SmartAdventurers.Brain.MORY_FOOD_MIN) return "moryFood";
        if (j.hunt != null && P87Fight.resting(b)) return "fleeRest";
        if (j.hunt != null && P87Fight.closedByFood(b, j.hunt, combat)) return "noFood";
        if (j.skill < 0 && combat < j.level) return "level " + j.level + ">" + combat;
        boolean crewF = false; try { crewF = Scenes.Crews.filter(b); } catch (Throwable ignored) { }
        if (crewF && !Scenes.Crews.nearHome(b.crew, j)) return "crewHome";
        double dist = 400;
        if (sp != null) {
            Location a1 = sp.getY() > 6400 ? Location.create(sp.getX(), sp.getY() - 6400, 0) : sp, b1 = me.getY() > 6400 ? Location.create(me.getX(), me.getY() - 6400, 0) : me;
            dist = a1.getDistance(b1);
        } else for (AfkTasks.Spot s : AfkTasks.SPOTS.get(j.task)) dist = Math.min(dist, s.at.getDistance(me));
        if (j.hunt != null) { String g2 = SmartAdventurers.brain2Gate(bot, j, budget, false); if (g2 != null) return g2; } // brain2 rule 1, as in chooseJob (flag off: null)
        long coins = purse >= 0 ? purse : Ledger.Wallet.coins(bot);
        if (planHere && SmartAdventurers.PLAN_JOBS && dist > SmartAdventurers.PLAN_FROM) {
            int pl = SmartAdventurers.plannedTiles(bot, sp != null ? sp : b.nearestSpot(j), budget, j.label());
            if (pl == -1 && Seed.ON) Scen.noRoute(bot, sp != null ? sp : b.nearestSpot(j)); // W8 fix9b scenario (seed flags on only)
            if (pl == -1 && SmartAdventurers.PLAN_NO_ROUTE) return "noRoute";
            if (pl == -1 && SmartAdventurers.P73_FIX && j.hunt != null && (coins < 5_000 || dist <= AfkTasks.Runner.TELEPORT_MIN)) return "noRoute(p73)";
            if (pl > 0) dist = Math.max(dist, pl);
        }
        if (sp != null && sp.getX() >= 3400 && sp.getY() < 6400 && me.getX() < 3400 && coins < Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556)) + 200) return "salveFare";
        if (Preflight.ON && coins < Preflight.fare() + 200 && Preflight.leavingMorytania(me, sp != null ? sp : b.nearestSpot(j))) return "moryFare";
        if (dist > AfkTasks.Runner.TELEPORT_MIN && coins < 5_000) return "far" + (int) dist + "+under5k";
        int busy = j.hunt != null ? SmartAdventurers.Brain.crowdHunt(j.hunt) : SmartAdventurers.Brain.crowd(j.task);
        int party = b.crew != null && b.crew.leader == b ? b.crew.members.size() : 1;
        if (!FIX8) { if (busy + party > SmartAdventurers.Brain.capacity(j)) return "full"; }
        else { String f = full8(true, busy, party, seats(j, true), wait == null ? 0 : wait.getOrDefault(j, 0)); if (f != null) return f; }
        if (j.hunt != null && j.hunt.npcLevel >= 80 && (bot.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.PRAYER) < 43 || !b.hasAntifire())) return "big";
        if (W6Slayer.ON && j.hunt != null) { String g6 = W6Slayer.closed(b, j.hunt, combat); if (g6 != null) return g6; } // W6: as chooseJob
        return null;
    }

    /** P80: the first open spot of these monsters for this bot (null = none), the closed reasons into why (max 3). */
    static Location flat(Location l) { return l.getY() > 6400 ? Location.create(l.getX(), l.getY() - 6400, 0) : l; }
    static SmartAdventurers.Job openSpot(SmartAdventurers.Brain b, Set<String> names, int[] budget, StringBuilder why) { return openSpot(b, names, budget, why, null, -1); }
    static SmartAdventurers.Job openSpot(SmartAdventurers.Brain b, Set<String> names, int[] budget, StringBuilder why, Location at, long purse) { return openSpot(b, names, budget, why, at, purse, null); }
    static SmartAdventurers.Job openSpot(SmartAdventurers.Brain b, Set<String> names, int[] budget, StringBuilder why, Location at, long purse, Map<SmartAdventurers.Job, Integer> wait) {
        int combat = b.effectiveCombat(); long now = System.currentTimeMillis(); int n = 0;
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) {
            if (!matches(names, jobNames(j))) continue;
            String r;
            try { r = !spotOk(BAD, j.label(), now) ? "stalled" : openGate(b, j, combat, now, budget, at, purse, wait); } catch (Throwable e) { r = "?" + e.getClass().getSimpleName(); }
            if (r == null) return j;
            if (why != null && n++ < 3) why.append(j.label()).append('=').append(r).append(' ');
        }
        return null;
    }

    /** P80 openCheck: the tasks of `allow` this bot has at least one open hunt for (same name match as the task trip, minus
     *  spots BAD after a stall). `why` collects the reasons per closed task for the log. */
    static Set<Tasks> openSet(SmartAdventurers.Brain b, Set<Tasks> allow, StringBuilder why) { return openSet(b, allow, why, null, -1); }
    static Set<Tasks> openSet(SmartAdventurers.Brain b, Set<Tasks> allow, StringBuilder why, Location at, long purse) {
        EnumSet<Tasks> out = EnumSet.noneOf(Tasks.class);
        int[] budget = { OPEN_PLANS };
        Map<SmartAdventurers.Job, Integer> wait = FIX8 ? waiting8(b) : null; // slayer8 a: the seats other holders are waiting for
        for (Tasks t : allow) {
            StringBuilder w = new StringBuilder();
            if (openSpot(b, npcNames(t), budget, w, at, purse, wait) != null) out.add(t);
            else if (why != null) why.append(t.name()).append('(').append(w.length() == 0 ? "no job" : w.toString().trim()).append(") ");
        }
        return out;
    }

    /** slayer8 a: the seats of job j: for a holder of its task cap8 on the spawns of a surface hunt spot, else the general capacity.
     *  Real callers: openGate (always a holder's view) and chooseJob's "the patch is full" line (holder = Slayer.match). */
    static int seats(SmartAdventurers.Job j, boolean holder) {
        int cap = SmartAdventurers.Brain.capacity(j);
        return j.hunt == null || W6Slayer.ON && W6Slayer.seats(j.hunt) > 0 ? cap : cap8(FIX8, holder, cap, j.hunt.spot != null && j.hunt.spot.getY() < 6400 ? j.hunt.spawnsAtSpot : 0);
    }
    /** slayer8 a: per spot, the other bots that hold a task for it and are not on their task trip now (on other work, waiting,
     *  parked): each counted once, at the nearest matching spot it could go to (canSeat8). Bots on a task trip are in the crowd count. */
    static Map<SmartAdventurers.Job, Integer> waiting8(SmartAdventurers.Brain me) {
        Map<SmartAdventurers.Job, Integer> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (SmartAdventurers.Brain o : SmartAdventurers.BRAINS.values()) {
            try {
                St s = o.slaySt;
                if (o == me || o.bot == null || !o.bot.isActive() || s.had == null || s.names.isEmpty() || (s.trip != null && o.runner != null) || !hasTask(o.bot)) continue;
                int combat = o.effectiveCombat(); long coins = Ledger.Wallet.coins(o.bot); Location at = flat(o.bot.getLocation());
                List<SmartAdventurers.Job> js = new ArrayList<>();
                for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (matches(s.names, jobNames(j)) && spotOk(BAD, j.label(), now) && o.reachable(j)) js.add(j);
                double[] dist = new double[js.size()]; boolean[] ok = new boolean[js.size()];
                for (int i = 0; i < dist.length; i++) {
                    SmartAdventurers.Job j = js.get(i); Location sp = j.hunt != null ? j.hunt.spot : o.nearestSpot(j);
                    dist[i] = sp == null ? 1e9 : flat(sp).getDistance(at); ok[i] = sp != null && canSeat8(combat, j.level, dist[i], coins);
                }
                int k = nearest8(dist, ok);
                if (k >= 0) out.merge(js.get(k), 1, Integer::sum);
            } catch (Throwable ignored) { }
        }
        return out;
    }
    /** slayer8 c (Brain.think, before a player's errand and before the crew leader's call): this bot's task trip is due first. */
    static boolean tripFirst(SmartAdventurers.Brain b) {
        if (!ON || !FIX8 || b == null) return false;
        St s = b.slaySt;
        return tripFirst8(true, s.had != null, s.streak, parkedNow(PARK, s.parkedUntil, System.currentTimeMillis()), !BROKE_TRIP && b.broke()); // brokeTrip: the walk test is taskTrip's (the streak came from a spot openGate saw within walking range)
    }
    /** slayer8 c (chooseJob's task-trip choice): is open spot j at dist a better trip than best at bestDist (null = none yet)? */
    static boolean better(SmartAdventurers.Brain b, SmartAdventurers.Job j, double dist, SmartAdventurers.Job best, double bestDist) {
        Set<String> n = b.slaySt.names;
        return better8(FIX8, FIX8 && pure8(n, jobNames(j)), dist, FIX8 && best != null && pure8(n, jobNames(best)), bestDist);
    }
    /** slayer8 d: the task was dropped after parks: not taken again by this bot for SHUT8_MS. */
    static void shut8(St s, Tasks t, long now) { if (FIX8 && t != null) { s.shut = t; s.shutUntil = now + SHUT8_MS; } }

    /** P80 round 2, every think while a task is held (rate WATCH_MS): an open spot of the task's monsters now = the next trip is
     *  the task (streak; chooseJob takes the nearest open one, so a closed spot gives way to another); none open for EARLY_MS =
     *  abandon early with the reasons instead of idling to the 15-minute abandon. */
    static void watch(SmartAdventurers.Brain b, long now) {
        St s = b.slaySt; Player bot = b.bot;
        if (!ON || !OPEN_CHECK || s.had == null || now < s.nextWatch) return;
        s.nextWatch = now + WATCH_MS;
        try {
            if (!hasTask(bot)) return; // done: tripEnd counts it
            if (s.trip != null && b.runner != null) { s.closedSince = 0; return; } // on a task trip (it counts in its own spot's crowd): tripEnd judges it
            StringBuilder w = new StringBuilder();
            int plans = watchPlans(PARK, s.parkFresh); s.parkFresh = false;
            SmartAdventurers.Job j = openSpot(b, s.names, new int[] { plans }, w);
            if (j != null) {
                s.closedSince = 0; if (s.trip == null) s.streak = true;
                if (PARK && s.parkedUntil > 0) { s.parkedUntil = 0; s.streak = true; UNPARKED++; if (POINTS) s.pending = null; System.out.println("[Slayer] " + bot.getUsername() + " unparked " + s.had.name() + " -> " + j.label() + " (parks " + s.parks + ", plans " + plans + ")"); }
                return;
            }
            if (parkedNow(PARK, s.parkedUntil, now)) { s.closedSince = 0; return; } // B43: parked, other work; the closed count restarts after the window
            if (s.closedSince == 0) { s.closedSince = now; return; }
            if (now - s.closedSince < EARLY_MS) return;
            int amt = amount(bot);
            if (PARK) { park(b, now, amt, "early: no open spot " + (now - s.closedSince) / 60_000 + " min from " + bot.getLocation() + ", trips " + s.trips + "; " + w.toString().trim()); return; }
            try { SlayerManager.getInstance(bot).clear(); } catch (Throwable ignored) { }
            b.say(BotChat.pick("Giving up on Turael's task.", "Can't get at any of Turael's lot. Forget it.", "Turael can do his own task."));
            System.out.println("[Slayer] " + bot.getUsername() + " abandoned " + s.had.name() + " at " + amt + " left (early: no open spot " + (now - s.closedSince) / 60_000 + " min from " + bot.getLocation() + ", trips " + s.trips + "; " + w.toString().trim() + ")");
            ABANDONED++; EARLY++;
            if (s.lastAmount > amt) KILLS += s.lastAmount - amt;
            s.abandonRun++; long[] nx = afterAbandon(s.abandonRun, now);
            s.had = null; s.names = Collections.emptySet(); s.streak = false; s.closedSince = 0; s.nextErrand = nx[0]; s.revisitUntil = nx[1];
        } catch (Throwable t) { System.out.println("[Slayer] watch " + (bot != null ? bot.getUsername() : "?") + ": " + t); }
    }

    /** chooseJob end hook: true = take `nearest` (the nearest matching hunt that passed every gate) as this trip. */
    static boolean taskTrip(SmartAdventurers.Brain b, SmartAdventurers.Job nearest, boolean broke) {
        if (!ON || nearest == null) return false;
        St s = b.slaySt;
        try {
            if (parkedNow(PARK, s.parkedUntil, System.currentTimeMillis())) return false; // B43: parked, other work
            final boolean free = BROKE_TRIP && broke && freeTrip(true, true, tripFar(b, nearest)); // brokeTrip (off: false, tripFar not asked)
            if (!tripDue(true, s.had != null, BROKE_TRIP ? brokeStops(broke, free) : broke, spotSlayers(b, nearest), s.streak || SmartAdventurers.RNG.nextInt(TRIP_ODDS) == 0)) return false;
            if (Ledger.CLERK_FIX2 && capTrip(b, nearest)) return false; // slayer6 C: far and TELE_CAP teleports paid on this task
            s.trip = nearest; TRIPS++; s.trips++; s.lastTrip = nearest.label(); s.parkedUntil = 0; s.exempt = 0; if (free) BROKE_TRIPS++;
            if (Ledger.CLERK_FIX2) { s.paidSnap = paidCount(b.bot); if (s.paidSnap < 0 && tripFar(b, nearest) && Ledger.Wallet.coins(b.bot) >= MIN_COINS) capCount(b.bot, s, 1, "trip, predicted"); }
            if (SmartAdventurers.RNG.nextInt(3) == 0) { String mn = masterName(s.master); b.say(BotChat.pick(mn + "'s task. " + nearest.label() + " it is.", "Got a slayer task to get on with.", "Off to work on " + mn + "'s list.")); }
            touch(b, s, System.currentTimeMillis());
            System.out.println("[Slayer] " + b.bot.getUsername() + " task trip " + s.had.name() + " -> " + nearest.label() + " (" + amount(b.bot) + " left" + (free ? ", broke: free trip on foot" : "") + ")");
            if (Seed.ON) Scen.slayTrip(b.bot, s.had, nearest); // w9 vannakaHunts scenario (seed flags on only)
            return true;
        } catch (Throwable t) { System.out.println("[Slayer] task trip " + (b.bot != null ? b.bot.getUsername() : "?") + ": " + t); return false; }
    }

    /** W2 lane S (Seed.task, scenario worlds only; the caller is behind Seed.ON): the bot holds task t x n from Turael as if the
     *  errand had just assigned it (the same state tick() sets), so a scenario reaches the task trip without the walk to a master. */
    static boolean hold(SmartAdventurers.Brain b, Player bot, Tasks t, int n, long now) { return hold(b, bot, t, n, now, Master.TURAEL); }
    /** w9 scen2 (Seed.MASTER): the same, held from the given master (VANNAKA for the vannakaHunts scenario); null = Turael. */
    static boolean hold(SmartAdventurers.Brain b, Player bot, Tasks t, int n, long now, Master from) {
        if (!ON || b == null || bot == null || t == null || n <= 0) return false;
        St s = b.slaySt;
        SlayerManager m = ensure(bot);
        if (from == null) from = Master.TURAEL;
        m.clear(); m.setMaster(from); m.setTask(t); m.setAmount(n);
        if (!m.hasTask()) return false;
        s.trips = 0; s.lastTrip = null; s.closedSince = 0; s.nextWatch = now + WATCH_MS;
        s.assignedAt = now; s.parkedUntil = 0; s.parks = 0; s.parkFresh = false;
        if (OPEN_CHECK) s.streak = true;
        s.had = t; s.lastAmount = m.getAmount(); s.lastProgress = now; s.names = npcNames(t);
        s.master = from; s.pending = null;
        System.out.println("[Slayer] " + bot.getUsername() + " assigned " + t.name() + " x" + m.getAmount() + " names " + s.names + (from == Master.TURAEL ? " (seeded)" : " (seeded, " + masterName(from) + ")"));
        ASSIGNED++;
        return true;
    }

    /** slayer6 C: is the task-trip spot a teleport away (the runner teleports past TELEPORT_MIN). */
    static boolean tripFar(SmartAdventurers.Brain b, SmartAdventurers.Job j) {
        Location sp = j.hunt != null ? j.hunt.spot : b.nearestSpot(j);
        if (sp == null) return false;
        Location from = b.bot.getLocation(); double flat = flat(sp).getDistance(flat(from));
        if (!CAP_FIX) return flat > AfkTasks.Runner.TELEPORT_MIN;
        boolean fu = from.getY() > 6400, tu = sp.getY() > 6400, water = false; double ve = -1, vc = -1; // capFix: the runner's launch test (goTo)
        try {
            if (!fu && tu) { Location[] e = AfkTasks.Runner.entrance(sp); if (e != null) ve = from.getDistance(e[1]); }
            if (fu && !tu) { Location[] up = P90.climbOut(from, sp); if (up != null) vc = sp.getDistance(up[1]); }
            water = AfkTasks.Runner.acrossSea(from, sp) || !tu && !fu && sp.getX() >= 3400 && from.getX() < 3400;
        } catch (Throwable ignored) { }
        return capFar(true, flat, fu, tu, from.getDistance(sp), ve, vc, water, AfkTasks.Runner.TELEPORT_MIN);
    }
    /** slayer6 C: true = the trip is off (the cap is reached and the spot is too far to walk): the task parks, as a crowded task does. */
    static boolean capTrip(SmartAdventurers.Brain b, SmartAdventurers.Job j) {
        St s = b.slaySt; long now = System.currentTimeMillis();
        if (teleStep(true, s.teles, tripFar(b, j)) != Tele.CAPPED) return false;
        CAPPED++;
        if (now >= s.capLog) { s.capLog = now + 10 * 60_000L; System.out.println("[Slayer] " + b.bot.getUsername() + " teleport cap " + s.teles + "/" + TELE_CAP + " on " + (s.had != null ? s.had.name() : "?") + ": " + j.label() + " is too far to walk" + (PARK ? ", parks" : "")); }
        if (PARK && s.had != null) park(b, now, amount(b.bot), "teleport cap");
        return true;
    }
    static void capCount(Player bot, St s, int n, String what) {
        if (n <= 0) return;
        s.teles += n; TELES += n;
        System.out.println("[Slayer] " + (bot != null ? bot.getUsername() : "?") + " paid teleport +" + n + " (" + what + ", task total " + s.teles + "/" + TELE_CAP + ")");
    }

    /** Trip end hook: progress, completion, abandon, then maybe start the errand. True = the errand started (caller returns). */
    static boolean tripEnd(SmartAdventurers.Brain b, long now) {
        if (!ON) return false;
        St s = b.slaySt; Player bot = b.bot;
        try {
            boolean has;
            if (SETTLE_FIRST && s.settledAt == now && s.settledAt != 0) { // slayer4 rider M1: settleFirst ran the bookkeeping this think already
                s.settledAt = 0;
                if (s.settled < 0) return false;
                has = s.settled == 1;
            } else {
                int r = settle(b, now);
                if (r < 0) return false;
                has = r == 1;
            }
            if (s.had != null) {
                if (POINTS && s.pending != null && has && blockedErrand(b, now)) return true; // item 6: cancel or swap
                return false;
            }
            return errand(b, now, has);
        } catch (Throwable t) { b.slay = 0; System.out.println("[Slayer] trip end " + (bot != null ? bot.getUsername() : "?") + ": " + t); return false; }
    }

    /** slayer4 rider M1 (-Dbots.slayer.settleFirst): the trip-end bookkeeping before the plank/farm/bone/summon errands; the errand
     *  decision stays in tripEnd, which reuses this result in the same think. */
    static void settleFirst(SmartAdventurers.Brain b, long now) {
        if (!ON || !SETTLE_FIRST) return;
        St s = b.slaySt;
        try { int r = settle(b, now); s.settled = r; s.settledAt = now; }
        catch (Throwable t) { s.settledAt = 0; System.out.println("[Slayer] settle " + (b.bot != null ? b.bot.getUsername() : "?") + ": " + t); }
    }

    /** Trip-end bookkeeping (progress, completion, abandon/park). -1 = stop here (parked or kept: tripEnd returns false);
     *  else 1/0 = the bot holds a task (the has value the errand part used before the split). */
    static int settle(SmartAdventurers.Brain b, long now) {
        St s = b.slaySt; Player bot = b.bot;
        {
            boolean wasTrip = s.trip != null; s.trip = null; s.streak = false;
            if (Ledger.CLERK_FIX2 && wasTrip && s.paidSnap >= 0 && Seed.ON) { int now0 = paidCount(bot); Scen.capTrip(bot, s.teles, tripCounted(CAP_FIX, s.paidSnap, now0, s.exempt), s.exempt, paidDelta(s.paidSnap, now0), now0 < s.paidSnap); } // W8 capFix scenario
            if (Ledger.CLERK_FIX2 && wasTrip && s.paidSnap >= 0) { capCount(bot, s, tripCounted(CAP_FIX, s.paidSnap, paidCount(bot), s.exempt), "trip"); s.paidSnap = -1; s.exempt = 0; } // slayer6 C; capFix: exempt legs out
            boolean has = hasTask(bot);
            if (s.had != null) {
                int amt = amount(bot);
                if (!has) { // done (kills counted by the server's own hook)
                    if (s.lastAmount > 0) { KILLS += s.lastAmount; System.out.println("[Slayer] " + bot.getUsername() + " task kills +" + s.lastAmount + " " + s.had.name() + " (0 left)"); if (Seed.ON) Scen.slayKills(bot, s.had, s.lastAmount); } // w9 scenario
                    String mn = masterName(s.master);
                    b.say(BotChat.pick("Done " + mn + "'s task, " + s.had.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " sorted.", "That's " + mn + "'s lot done.", "Task done. " + mn + " can find someone else to bother."));
                    System.out.println("[Slayer] " + bot.getUsername() + " completed " + s.had.name());
                    COMPLETED++;
                    s.done++; s.pending = null; // items 5/6
                    if (POINTS && TOUCHES) brag(b);
                    if (BANK_FIRST) bankBefore(b, s.master);
                    s.had = null; s.names = Collections.emptySet(); s.nextErrand = now; s.revisitUntil = now + REVISIT_MS; s.abandonRun = 0; // v1.3: straight back to Turael
                } else {
                    if (amt < s.lastAmount) { KILLS += s.lastAmount - amt; System.out.println("[Slayer] " + bot.getUsername() + " task kills +" + (s.lastAmount - amt) + " " + s.had.name() + " (" + amt + " left)"); if (Seed.ON) Scen.slayKills(bot, s.had, s.lastAmount - amt); s.lastProgress = now; s.lastAmount = amt; s.streak = wasTrip; }
                    if (!parkedNow(PARK, s.parkedUntil, now) && abandonDue(true, s.lastProgress, now)) { // B43: no abandon/park inside a park window
                        if (OPEN_CHECK) { // P80: why, before the task is cleared (why() needs s.had)
                            String r; try { r = why(b); } catch (Throwable e) { r = "?" + e.getClass().getSimpleName(); }
                            System.out.println("[Slayer] " + bot.getUsername() + " abandon why " + s.had.name() + ": " + (now - s.lastProgress) / 60_000 + " min no kills, trips " + s.trips
                                    + (s.lastTrip != null ? " last " + s.lastTrip : "") + (wasTrip ? " (this trip was a task trip)" : "") + ", last job " + (b.lastJob != null ? b.lastJob.label() : "none") + "; hunts now: " + r);
                        }
                        if (PARK) { // B43: an open spot right after a task trip with no kills is not trusted (it just failed): park
                            boolean open = false;
                            if (!wasTrip) try { open = openSpot(b, s.names, new int[] { WATCH_PLANS }, null) != null; } catch (Throwable ignored) { }
                            if (open) { s.streak = true; s.lastProgress = now; System.out.println("[Slayer] " + bot.getUsername() + " keeps " + s.had.name() + " (15 min no kills, a spot is open: trip)"); }
                            else park(b, now, amt, "15 min no kills, trips " + s.trips + (s.lastTrip != null ? " last " + s.lastTrip : ""));
                            return -1; // still held, or expired just now (the next tripEnd may start the errand)
                        }
                        try { SlayerManager.getInstance(bot).clear(); } catch (Throwable ignored) { }
                        b.say(BotChat.pick("Giving up on Turael's task.", "Can't find any of Turael's lot. Forget it.", "Turael can do his own task."));
                        System.out.println("[Slayer] " + bot.getUsername() + " abandoned " + s.had.name() + " at " + amt + " left");
                        ABANDONED++;
                        s.abandonRun++; long[] nx = afterAbandon(s.abandonRun, now); // v1.3: the first abandon in a row revisits
                        s.had = null; s.names = Collections.emptySet(); s.nextErrand = TRIAL ? now + 60_000 : nx[0]; s.revisitUntil = nx[1];
                    }
                }
                if (s.had != null) return has ? 1 : 0; // item 6 (blockedErrand) and the held return stay in tripEnd
                has = hasTask(bot); // v1.3: completed or abandoned just now: maybe straight back to Turael below
            }
            return has ? 1 : 0;
        }
    }

    /** The master errand decision (the second half of the old tripEnd). True = the errand started. */
    static boolean errand(SmartAdventurers.Brain b, long now, boolean has) {
        St s = b.slaySt; Player bot = b.bot;
        {
            boolean lastCombat = b.lastJob != null && b.lastJob.skill < 0;
            boolean combatJob = VANNAKA ? eligibleLadder(lastCombat, b.lastJob != null, bot.getProperties().getCurrentCombatLevel()) // item 5: up to combat 99
                    : eligible(lastCombat, b.lastJob != null, bot.getProperties().getCurrentCombatLevel()); // v1.2: + combat 20+ skillers
            if (CHAELDAR && !combatJob) combatJob = W9Chaeldar.eligible(true, b.lastJob != null, bot.getProperties().getCurrentCombatLevel()); // w9 lane D: Chaeldar has no top (the ladder stops at 99)
            boolean questBusy = QuestRunner.active(bot) || QuestBook.midQuest(bot);
            boolean other = b.runner != null || b.errand() || b.errandFor != null || b.companion != null; // train-20 busy states (P53 bury run, greetGuard escort), P65: + P62 summoning errand
            boolean fighting = false; try { fighting = bot.inCombat() || bot.getProperties().getCombatPulse().isAttacking(); } catch (Throwable ignored) { }
            int odds = errandOdds(lastCombat);
            boolean revisit = revisitOn(s.revisitUntil, now); // v1.3
            boolean dice = TRIAL || revisit || odds <= 1 || SmartAdventurers.RNG.nextInt(odds) == 0; // v1.2: was 1 in 3 for everyone; v1.3: 1 in 2
            long coins = Ledger.Wallet.coins(bot);
            if (!errandDue(true, has, now, s.skipUntil, revisit ? 0 : s.nextErrand, coins, combatJob, questBusy, other, fighting, dice, POOR_OK)) return false;
            if (brokeStop8(FIX8, coins)) { s.nextErrand = now + FIX6_SHORT_MS; BROKE_SKIPS++; skipped(bot, "broke (" + coins + " gp): the task trip needs " + BROKE8 + " gp"); return false; } // slayer8 c
            Master to = VANNAKA ? chooseMaster(b) : Master.TURAEL; // item 5 (off: Turael, as before)
            if (to == null) { s.nextErrand = now + ERRAND_COOL_MS; if (FIX6) skipped(bot, "no master for combat " + bot.getProperties().getCurrentCombatLevel()); return false; } // F1: no master now: cool down, no openSet every trip end
            if (served(to).isEmpty()) { if (DOOR) s.nextErrand = now + ERRAND_COOL_MS; return false; } // slayer4 (audit M3): refusals after chooseMaster cool down
            double md = masterDist(bot, to); // slayer4: flag off = getDistance(masterAt(to)), as before
            // no planner (nav off or AUTO-REVERTed): the route-map walk stalls north of Falador (2987,3423, trial walk-nonav), so only a
            // bot that will teleport (TELEPORT_MIN+ away) goes
            if (!NavWalker.ON && md <= AfkTasks.Runner.TELEPORT_MIN) { if (DOOR) s.nextErrand = now + ERRAND_COOL_MS; return false; }
            if (POOR_OK && coins < MIN_COINS && md > AfkTasks.Runner.TELEPORT_MIN) { if (DOOR) s.nextErrand = now + ERRAND_COOL_MS; return false; } // item 8: far + under 5k stays a no
            if (FIX6 && OPEN_CHECK && !has) { // slayer6 A: is there a task to be had at the master? decided here, before the walk or the fare
                int openN; String closed;
                if (to == Master.VANNAKA && s.pre != null) { openN = minus8(FIX8, s.pre, s.shut, s.shutUntil, now).size(); closed = s.preWhy; }
                else {
                    boolean pays = md > AfkTasks.Runner.TELEPORT_MIN && !WALK_ONLY && fareOk(bot);
                    StringBuilder w = new StringBuilder();
                    openN = minus8(FIX8, openSet(b, served(to), w, stand(to), afterFare(coins, pays, Preflight.fare())), s.shut, s.shutUntil, now).size(); closed = w.toString().trim();
                }
                s.pre = null;
                long cool = preTravel(true, true, has, openN, closed);
                if (cool > 0) { s.skipUntil = now + cool; PRE_SKIPS++; skipped(bot, "no open task at " + masterName(to) + " (" + cool / 60_000 + " min; closed: " + cut(closed, 400) + ")"); return false; }
            }
            if (revisit) REVISITS++;
            if (!has) s.teles = 0; // slayer6 C: a new task's count starts with the errand that fetches it
            Bob.leave(b.bot, "busy"); b.slay = 1; s.start = now; s.nextErrand = now + (TRIAL ? 60_000 : ERRAND_COOL_MS); s.revisitUntil = 0; s.errandTo = to;
            final String mn = masterName(to);
            BotChat.activity(bot, () -> "off to see " + mn);
            if (SmartAdventurers.RNG.nextInt(2) == 0) b.say(BotChat.pick("Off to " + masterPlace(to) + ". " + mn + " owes me a task.", "Slayer task time.", "Going to see " + mn + "."));
            System.out.println("[Slayer] " + bot.getUsername() + " -> " + mn + " from " + bot.getLocation() + (revisit ? " (revisit)" : ""));
            ERRANDS++;
            return true;
        }
    }

    static void skipped(Player bot, String why) { System.out.println("[Slayer] " + bot.getUsername() + " errand skipped: " + why); }
    /** slayer6: one line per errand that left for a master: "assigned" or "no task (<reason>)". */
    static void outcome(Player bot, String what) { if (FIX6) System.out.println("[Slayer] " + bot.getUsername() + " errand end: " + what); }

    /** Errand tick (Brain.think dispatch while slay != 0). */
    static void tick(SmartAdventurers.Brain b, long now) {
        St s = b.slaySt; Player bot = b.bot;
        try {
            if (!ON) { b.slay = 0; return; }
            if (now - s.start > (TRIAL ? 20 * 60_000L : WALK_GIVEUP_MS)) { System.out.println("[Slayer] " + bot.getUsername() + " gave up the walk at " + bot.getLocation()); GAVEUP++; s.pending = null; outcome(bot, "no task (gave up the walk)"); end(b); return; }
            Master to = s.errandTo != null ? s.errandTo : Master.TURAEL; // item 5 (off: always Turael)
            boolean fighting = false; try { fighting = bot.inCombat() || bot.getProperties().getCombatPulse().isAttacking(); } catch (Throwable ignored) { }
            if (fighting) return; // let the fight finish before walking or talking
            if (bot.getLocation().getDistance(masterAt(to)) > 3) { if (DOOR && to == Master.VANNAKA && doorGo(bot, s)) return; if (DURADEL && to == Master.DURADEL && W13Duradel.go(bot, s)) return; if (CHAELDAR && to == Master.CHAELDAR && W9Chaeldar.go(bot, s)) return; go(bot, masterAt(to), s); return; }
            b.slay = 2;
            SlayerManager m = ensure(bot);
            if (POINTS && s.pending != null && m.hasTask() && !resolveBlocked(b, m, to)) { outcome(bot, "no task (kept the old one)"); end(b); return; } // item 6: cancel / swap, then a new task below
            int[] sizeRolls = {0};
            Set<Tasks> allow = served(to);
            if (SlayerOnly.ONLY1 && to == Master.TURAEL) allow = SlayerOnly.add(b, allow); // W8 (-Dbots.slayer.only1): crawling hands, banshees for a bot that qualifies
            StringBuilder closed = OPEN_CHECK ? new StringBuilder() : null;
            if (OPEN_CHECK && !m.hasTask()) { // P80: only tasks with an open hunt for this bot; the rest re-roll
                allow = openSet(b, allow, closed);
                if (FIX8) { Set<Tasks> a8 = minus8(true, allow, s.shut, s.shutUntil, now); if (a8 != allow) { closed.append(s.shut.name()).append("(dropped after parks) "); allow = a8; } } // slayer8 d
                System.out.println("[Slayer] " + bot.getUsername() + " open " + allow.size() + "/" + served(to).size() + " " + allow + (closed.length() > 0 ? " closed: " + closed.toString().trim() : ""));
                if (allow.isEmpty()) { s.skipUntil = now + (FIX6 ? skipCool(closed.toString()) : SKIP_MS); System.out.println("[Slayer] " + bot.getUsername() + " no open task, skip"); SKIPPED++; OPEN_NONE++; outcome(bot, "no task (none open at the master)"); end(b); return; }
            }
            final Set<Tasks> al = allow; final int[] rolls = {0};
            final Master gm = to; final boolean held = m.hasTask();
            java.util.function.Supplier<Tasks> roll = () -> { m.generate(gm); rolls[0]++; return m.hasTask() ? m.getTask() : null; };
            Tasks t = held ? m.getTask() : FIX6 && OPEN_CHECK ? pick6(roll, m::getAmount, m::clear, al, FIX6_ROLLS, gm != Master.VANNAKA && !(CHAELDAR && gm == Master.CHAELDAR) && !(DURADEL && gm == Master.DURADEL), sizeRolls) : gm == Master.VANNAKA || CHAELDAR && gm == Master.CHAELDAR || DURADEL && gm == Master.DURADEL ? pick(roll, m::clear, al, OPEN_CHECK ? OPEN_REROLLS : MAX_REROLLS) // item 5: no size re-roll at Vannaka
                    : pickSmall(roll, m::getAmount, m::clear, al, sizeRolls, OPEN_CHECK ? OPEN_REROLLS : MAX_REROLLS);
            if (sizeRolls[0] > 0) { RESIZED += sizeRolls[0]; System.out.println("[Slayer] " + bot.getUsername() + " size re-rolls " + sizeRolls[0]); }
            if (OPEN_CHECK && rolls[0] > 1) { OPEN_ROLLS += rolls[0] - 1 - sizeRolls[0]; System.out.println("[Slayer] " + bot.getUsername() + " rolls " + rolls[0] + " (size " + sizeRolls[0] + ")"); }
            if (t == null) { s.skipUntil = now + (FIX6 ? ERRAND_COOL_MS : SKIP_MS); System.out.println("[Slayer] " + bot.getUsername() + " no allowed task after re-rolls"); SKIPPED++; outcome(bot, "no task (" + rolls[0] + " rolls, none of " + al + ")"); end(b); return; }
            s.trips = 0; s.lastTrip = null; s.closedSince = 0; s.nextWatch = now + WATCH_MS;
            s.assignedAt = now; s.parkedUntil = 0; s.parks = 0; s.parkFresh = false; // B43
            if (OPEN_CHECK) s.streak = true; // P80 pair 1: 8 of 9 abandons had 0 task trips (the 1-in-2 dice then a long other job): the first trip after Turael is the task
            s.had = t; s.lastAmount = m.getAmount(); s.lastProgress = now; s.names = npcNames(t);
            s.master = VANNAKA && held ? coreMaster(m, to) : to; s.pending = null; // items 5/6
            String what = t.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            b.say(masterName(s.master) + " wants " + m.getAmount() + " " + what + ".");
            System.out.println("[Slayer] " + bot.getUsername() + " assigned " + t.name() + " x" + m.getAmount() + " names " + s.names);
            ASSIGNED++;
            if (CHAELDAR && s.master == Master.CHAELDAR) W9Chaeldar.assigned(bot, t.name(), m.getAmount()); if (DURADEL && s.master == Master.DURADEL) W13Duradel.assigned(bot, t.name(), m.getAmount()); // w9 lane D
            outcome(bot, "assigned " + t.name() + " x" + m.getAmount() + " (" + masterName(s.master) + ", rolls " + rolls[0] + ")");
            end(b);
        } catch (Throwable ex) { System.out.println("[Slayer] tick " + (bot != null ? bot.getUsername() : "?") + ": " + ex); outcome(bot, "no task (error)"); end(b); }
    }

    // ---------------- items 5-8 server glue (every caller is behind its flag) ----------------

    /** Item 5: the master for this errand, from where the bot stands. Vannaka needs core hasRequirements (combat 40), our
     *  effectiveCombat 40+, 2+ finished tasks, food for npc level 60, and VANNAKA_OPEN_MIN+ open Vannaka tasks (openSet). */
    static Master chooseMaster(SmartAdventurers.Brain b) {
        Player bot = b.bot; St s = b.slaySt; s.pre = null;
        int combat = bot.getProperties().getCurrentCombatLevel(), eff = b.effectiveCombat();
        int done = Math.max(s.done, completedTasks(bot));
        boolean can = false; String why; int openN = -1;
        if (done < VANNAKA_DONE || combat < VANNAKA_MIN || combat > VANNAKA_MAX || eff < VANNAKA_MIN) why = "pre";
        else if (!Master.VANNAKA.hasRequirements(bot)) why = "core req";
        else if (!ladderFood(b, combat)) why = "food";
        else {
            StringBuilder w = new StringBuilder();
            boolean pays = FIX6 && masterDist(bot, Master.VANNAKA) > AfkTasks.Runner.TELEPORT_MIN && !WALK_ONLY && fareOk(bot);
            Set<Tasks> open = FIX6 ? openSet(b, served(Master.VANNAKA), w, stand(Master.VANNAKA), afterFare(Ledger.Wallet.coins(bot), pays, Preflight.fare())) : openSet(b, served(Master.VANNAKA), w); // slayer6 A/B: as it will be at Vannaka
            if (FIX6) { s.pre = open; s.preWhy = w.toString().trim(); }
            openN = open.size(); can = FIX6 ? canVannaka(combat, eff, done, true, true, openN, openMin7(FIX7, combat)) : canVannaka(combat, eff, done, true, true, openN);
            why = "open " + open.size() + "/" + served(Master.VANNAKA).size() + (can ? "" : " closed: " + w.toString().trim());
        }
        Master m = ladder7(combat, eff, done, openN >= 0, openN >= 0, Math.max(0, openN), SmartAdventurers.RNG.nextBoolean(), FIX6, FIX7);
        if (CHAELDAR) m = W9Chaeldar.choose(b, m, combat, eff, done); if (DURADEL) m = W13Duradel.choose(b, m, combat, eff, done); // w9 lane D: after the ladder's coin (no draw added), Chaeldar when the bot qualifies
        if (!"pre".equals(why)) System.out.println("[Slayer] " + bot.getUsername() + " ladder combat " + combat + "/" + eff + " done " + done + " vannaka " + (can ? "yes" : "no") + " (" + why + ") -> " + (m == null ? "none" : masterName(m)));
        if (m == Master.VANNAKA) LADDER_V++;
        return m;
    }

    /** Item 5: food (pack + bank, heals 7+) for a level-60 task passes the item 1 gate. */
    static boolean ladderFood(SmartAdventurers.Brain b, int combat) {
        int need = P87Fight.foodNeed(VANNAKA_NPC_LEVEL, combat), have = 0;
        try { for (int[] f : P87Fight.HEAL) if (f[1] >= 7) have += b.bot.getBank().getAmount(f[0]) + b.bot.getInventory().getAmount(f[0]); } catch (Throwable ignored) { }
        return P87Fight.gate(have, need, 0, Ledger.Wallet.coins(b.bot));
    }

    /** Core flags (never logs in): finished tasks, points, the held task's master. */
    static int completedTasks(Player p) {
        if (p == null || p.getAttribute("slayer-manager", null) == null) return 0;
        try { return SlayerManager.getInstance(p).flags.getCompletedTasks(); } catch (Throwable t) { return 0; }
    }
    static int points(Player p) {
        if (p == null || p.getAttribute("slayer-manager", null) == null) return 0;
        try { return SlayerManager.getInstance(p).getSlayerPoints(); } catch (Throwable t) { return 0; }
    }
    static Master coreMaster(SlayerManager m, Master dflt) {
        try { Master x = m.getMaster(); return x != null ? x : dflt; } catch (Throwable t) { return dflt; }
    }

    /** Item 6 + 4: a streak brag every 5th task in a row (the core hook already counted this one). */
    static void brag(SmartAdventurers.Brain b) {
        try {
            int st = SlayerManager.getInstance(b.bot).flags.getTaskStreak();
            if (st >= 5 && st % 5 == 0) b.say("That's " + st + " in a row.");
        } catch (Throwable ignored) { }
    }

    /** Item 7: restock before the master errand (the runner's trip end has banked the haul already). */
    static void bankBefore(SmartAdventurers.Brain b, Master next) {
        try {
            Player bot = b.bot;
            int level = DURADEL && next == Master.DURADEL ? W13Duradel.NPC_LEVEL : next == Master.VANNAKA ? VANNAKA_NPC_LEVEL : CHAELDAR && next == Master.CHAELDAR ? W9Chaeldar.NPC_LEVEL : 20, food = 0;
            for (int f : AfkTasks.Runner.FOOD) food += bot.getInventory().getAmount(f);
            int need = P87Fight.foodNeed(level, bot.getProperties().getCurrentCombatLevel()), free = bot.getInventory().freeSlots();
            if (!bankFirst(free, food, need)) return;
            b.buyFood(level); b.packFood();
            BANKED++;
            if (SmartAdventurers.RNG.nextInt(3) == 0) b.say("Banking this lot, then back to " + masterName(next) + ".");
            System.out.println("[Slayer] " + bot.getUsername() + " banks first (free " + free + ", food " + food + ", need " + need + ") -> pack food " + countFood(bot));
        } catch (Throwable t) { System.out.println("[Slayer] bank first " + (b.bot != null ? b.bot.getUsername() : "?") + ": " + t); }
    }
    static int countFood(Player p) { int n = 0; for (int f : AfkTasks.Runner.FOOD) n += p.getInventory().getAmount(f); return n; }

    /** Item 6, trip end with a pending plan: walk to the master (CANCEL) or to Turael (SWAP), if nothing else holds the bot. */
    static boolean blockedErrand(SmartAdventurers.Brain b, long now) {
        St s = b.slaySt; Player bot = b.bot;
        if (QuestRunner.active(bot) || QuestBook.midQuest(bot)) return false;
        if (b.runner != null || b.errand() || b.errandFor != null || b.companion != null) return false;
        try { if (bot.inCombat() || bot.getProperties().getCombatPulse().isAttacking()) return false; } catch (Throwable ignored) { }
        Master to = s.pending == Blocked.CANCEL ? (s.master != null ? s.master : Master.TURAEL) : Master.TURAEL;
        double d = masterDist(bot, to); // F2: the same walk gates as the task errand (pending kept); slayer4: flag off = getDistance(masterAt(to))
        if (!NavWalker.ON && d <= AfkTasks.Runner.TELEPORT_MIN) return false;
        if (d > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.coins(bot) < MIN_COINS) return false;
        Bob.leave(bot, "busy"); b.slay = 1; s.start = now; s.errandTo = to;
        final String mn = masterName(to);
        BotChat.activity(bot, () -> "off to see " + mn);
        System.out.println("[Slayer] " + bot.getUsername() + " -> " + mn + " to " + (s.pending == Blocked.CANCEL ? "cancel " : "swap ") + s.had.name() + " (points " + points(bot) + ", parks " + s.parks + ") from " + bot.getLocation());
        ERRANDS++;
        return true;
    }

    /** Item 6, at the master with the blocked task: CANCEL pays 30 points and clears (as SlayerRewardPlugin does), SWAP at Turael
     *  clears and resets the streak (the engine has no path that does). True = the task is gone, assign a new one; false = kept. */
    static boolean resolveBlocked(SmartAdventurers.Brain b, SlayerManager m, Master at) {
        St s = b.slaySt; Player bot = b.bot; Blocked plan = s.pending; s.pending = null;
        Tasks task = m.getTask();
        if (plan == Blocked.CANCEL && at != Master.TURAEL) {
            int pts = m.getSlayerPoints();
            if (pts < CANCEL_POINTS) { System.out.println("[Slayer] " + bot.getUsername() + " cancel off: points " + pts); return false; } // never negative
            m.setSlayerPoints(pts - CANCEL_POINTS);
            m.clear();
            b.say("Cost me 30 points to drop that.");
            shut8(s, task, System.currentTimeMillis()); // slayer8 d
            System.out.println("[Slayer] " + bot.getUsername() + " cancelled " + task + " at " + masterName(at) + " (points " + pts + " -> " + m.getSlayerPoints() + ")");
            CANCELS++;
        } else if (plan == Blocked.SWAP && at == Master.TURAEL && !onTuraelList(task)) {
            m.clear();
            try { m.flags.setTaskStreak(0); } catch (Throwable ignored) { }
            b.say("Had to get Turael to swap it. Streak's gone.");
            shut8(s, task, System.currentTimeMillis()); // slayer8 d
            System.out.println("[Slayer] " + bot.getUsername() + " swapped " + task + " at Turael (streak reset, points " + m.getSlayerPoints() + ")");
            SWAPS++;
        } else { System.out.println("[Slayer] " + bot.getUsername() + " " + plan + " not possible for " + task + " at " + masterName(at) + ", kept"); return false; }
        s.had = null; s.names = Collections.emptySet(); s.streak = false; s.closedSince = 0; s.parkedUntil = 0; s.parks = 0;
        return true;
    }

    /** B43 park, at both abandon sites when PARK: parkStep PARK keeps the core task (no Turael errand: errandDue needs !hasTask),
     *  no task trip for PARK_MS, streak off; EXPIRE (parks + age) is a silent clear(), the old abandon minus the chat. */
    static void park(SmartAdventurers.Brain b, long now, int amt, String why) {
        St s = b.slaySt; Player bot = b.bot;
        ParkStep step = parkStep(s.parks, s.assignedAt > 0 ? now - s.assignedAt : 0, false);
        if (step == ParkStep.EXPIRE) {
            try { SlayerManager.getInstance(bot).clear(); } catch (Throwable ignored) { }
            if (VANNAKA && s.master != null && s.master != Master.TURAEL) try { SlayerManager.getInstance(bot).flags.setTaskStreak(0); } catch (Throwable ignored) { } // F3: a dropped points-master task costs the streak
            System.out.println("[Slayer] " + bot.getUsername() + " expired " + s.had.name() + " at " + amt + " left (parks " + s.parks + ", age " + (now - s.assignedAt) / 60_000 + " min; " + why + ")");
            EXPIRED++; shut8(s, s.had, now); // slayer8 d
            if (s.lastAmount > amt) KILLS += s.lastAmount - amt;
            s.abandonRun++; long[] nx = afterAbandon(s.abandonRun, now);
            s.had = null; s.names = Collections.emptySet(); s.streak = false; s.closedSince = 0; s.parkedUntil = 0; s.parks = 0; s.nextErrand = nx[0]; s.revisitUntil = nx[1];
            return;
        }
        s.parks++; s.parkedUntil = now + PARK_MS; s.parkFresh = true; s.streak = false; s.closedSince = 0; s.lastProgress = now;
        String spot = s.lastTrip != null ? s.lastTrip : s.had.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (SmartAdventurers.RNG.nextInt(2) == 0) b.say(BotChat.pick("Too busy at the " + spot + ". I'll come back to it.", "Task can wait."));
        System.out.println("[Slayer] " + bot.getUsername() + " parked " + s.had.name() + " at " + amt + " left (park " + s.parks + ", age " + (s.assignedAt > 0 ? (now - s.assignedAt) / 60_000 : -1) + " min; " + why + ")");
        PARKED++;
        if (POINTS && s.master != null && s.master != Master.TURAEL) { // item 6: 2 parks in a row on a non-Turael task
            int pts = points(bot);
            Blocked bp = blockedPlan(s.master, pts, s.parks, onTuraelList(s.had));
            if (bp != Blocked.PARK) { s.pending = bp; System.out.println("[Slayer] " + bot.getUsername() + " plans " + bp + " " + s.had.name() + " (points " + pts + ", parks " + s.parks + ")"); }
        }
    }

    static void end(SmartAdventurers.Brain b) { if (DOOR) surface(b.bot, "errand end"); if (CHAELDAR) try { W9Chaeldar.leave(b.bot, "errand end"); } catch (Throwable t) { System.out.println("[Slayer] chaeldar leave " + (b.bot != null ? b.bot.getUsername() : "?") + ": " + t); } if (DURADEL) try { W13Duradel.leave(b.bot, "errand end"); } catch (Throwable t) { System.out.println("[Slayer] duradel leave " + (b.bot != null ? b.bot.getUsername() : "?") + ": " + t); } b.slay = 0; b.slaySt.errandTo = null; try { BotChat.clearActivity(b.bot); } catch (Throwable ignored) { } b.nextThink = System.currentTimeMillis() + 5_000; }

    /** slayer4: the way to Vannaka by the door. True = handled this tick (paid teleport, walk to the door, or the climb down);
     *  false = no door or already underground by him: the old go() walks the last tiles. */
    static boolean doorGo(Player bot, St s) {
        Location door = door(), here = bot.getLocation();
        if (door == null || here == null || underVannaka(here)) return false;
        double d = here.getDistance(door);
        if (d > AfkTasks.Runner.TELEPORT_MIN) {
            if (!WALK_ONLY && fareOk(bot) && capOk(s) && Ledger.Wallet.payTeleport(bot, "Slayer.go")) { if (Ledger.CLERK_FIX2 && s != null) capCount(bot, s, 1, "errand"); // audit L3: onto Vannaka's own tile, no 16-tile walkable search underground
                try { Spectacle.teleport(bot, VANNAKA_AT); } catch (Throwable ignored) { }
                DOOR_PAID++;
                return true;
            }
            go(bot, door, s);
            return true;
        }
        if (d <= 2) {
            climb(bot, VANNAKA_AT, 827);
            DOOR_DOWN++;
            System.out.println("[Slayer] " + bot.getUsername() + " climbs down to Vannaka from " + here.getX() + "," + here.getY() + " (door " + DOOR_DOWN + ")");
            return true;
        }
        go(bot, door, s); // under TELEPORT_MIN: go() walks it (planner, then route map), free
        return true;
    }

    /** slayer4: a free scripted move with a ladder animation (827 down, 828 up), as the runner's own trapdoor climb (the dungeon is not on the nav grid). */
    static void climb(Player bot, Location to, int anim) {
        try { bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(anim)); } catch (Throwable ignored) { }
        Bob.tele(bot).getProperties().setTeleportLocation(to);
        try { NavWalker.forget(bot); } catch (Throwable ignored) { }
    }

    /** slayer4: a bot underground by Vannaka climbs back up to the door, free (errand end, brain start). True = moved. */
    static boolean surface(Player bot, String why) {
        if (!DOOR || bot == null) return false;
        Location here = bot.getLocation();
        if (!underVannaka(here)) return false;
        Location door = door();
        if (door == null) return false;
        climb(bot, door, 828);
        DOOR_UP++;
        System.out.println("[Slayer] " + bot.getUsername() + " climbs up to " + door.getX() + "," + door.getY() + " from " + here.getX() + "," + here.getY() + " (" + why + ", " + DOOR_UP + ")");
        return true;
    }

    /** Like millGo plus the planner/route-map hops; WALK_ONLY (trials) never teleports. */
    static boolean capOk(St s) { return s == null || teleStep(Ledger.CLERK_FIX2, s.teles, true) == Tele.PAY; }
    static void go(Player bot, Location to) { go(bot, to, null); }
    static void go(Player bot, Location to, St s) {
        Location here = bot.getLocation();
        double d = here.getDistance(to);
        if (!WALK_ONLY && d > AfkTasks.Runner.TELEPORT_MIN && fareOk(bot) && capOk(s) && Ledger.Wallet.payTeleport(bot, "Slayer.go")) {
            if (Ledger.CLERK_FIX2 && s != null) capCount(bot, s, 1, "errand");
            Location near = Surveyors.Surveyor.walkable(Location.create(to.getX() + SmartAdventurers.RNG.nextInt(5) - 2, to.getY() + SmartAdventurers.RNG.nextInt(5) - 2, 0));
            try { Spectacle.teleport(bot, near != null ? near : to); } catch (Throwable ignored) { }
            return;
        }
        if (bot.getWalkingQueue().isMoving()) return;
        // the planner first (live nav.on), then the route map (as Artisans' bank walk): a straight 25-tile step from Falador
        // stalls at 2983,3474 north of the wall (F8 trial, P54)
        Location hop = null;
        if (NavWalker.ON) try { hop = NavWalker.step(bot, to); } catch (Throwable ignored) { }
        if (hop == null) try { hop = RouteMap.nextHop(bot, to); } catch (Throwable ignored) { }
        if (hop != null) { try { ((AIPlayer) bot).walkToPosSmart(hop); } catch (Throwable ignored) { } return; }
        Location step = d <= 25 ? to : Location.create(here.getX() + (int) Math.round((to.getX() - here.getX()) * 25 / d), here.getY() + (int) Math.round((to.getY() - here.getY()) * 25 / d), 0);
        Location w = d <= 25 ? null : Surveyors.Surveyor.walkable(step);
        try { ((AIPlayer) bot).walkToPosSmart(w != null ? w : step); } catch (Throwable ignored) { }
    }

    static volatile int STALLS, REVISITS, TOOL_LOOPS; // v1.3
    static volatile int PRE_SKIPS, CAPPED, TELES; // slayer6
    static volatile int STALL_CAPPED; // slayer7 b
    static volatile int BROKE_SKIPS; // slayer8 c
    static volatile int OPEN_NONE, OPEN_ROLLS, EARLY; // P80
    static volatile int PARKED, UNPARKED, EXPIRED; // B43 park (EXPIRED apart from ABANDONED)
    static volatile int LADDER_V, CANCELS, SWAPS, BANKED; // B43 items 5-7
    static volatile int RESIZED, ERRANDS, ASSIGNED, COMPLETED, ABANDONED, SKIPPED, GAVEUP, TRIPS, KILLS, NOMATCH;
}
