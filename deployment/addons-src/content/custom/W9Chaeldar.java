package content.custom;

import content.global.skill.slayer.Master;
import content.global.skill.slayer.Tasks;
import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * W9 lane D: Chaeldar, the Slayer master above Vannaka. Switch: Slayer.CHAELDAR = -Dbots.slayer.chaeldar (default off) AND the
 * Vannaka ladder (Slayer.VANNAKA: bots.slayer.vannaka + park + openCheck + fight.foodGate, as the live start line runs them).
 * Off: every Slayer / SmartAdventurers / MarketBrain hook tests the switch first, so this class is never initialised, no RNG
 * draw is added or moved, no line, no timer.
 *
 * 2009 rules (engine Master.CHAELDAR, npc 1598, read 11-20): combat 70, no Slayer level, 110-170 kills by default, streak points
 * 10/50/150 (Vannaka 4/20/60). She stands in Zanaris (npc_spawns.json 2447,4429). A player gets in through the Lumbridge swamp
 * shed (door 2406) wielding a dramen staff with Lost City past stage 20 (LostCityListeners); ZanarisPlugin's way out lands on
 * 3201,3169, the tile west of that door. Zanaris (y 4352+) is off the nav grid.
 *
 * Bot rules:
 *  - who: combat 70+ with no top (Vannaka and the errand gate stop at 99, so the high tier, combat 105-112 on the farm rosters,
 *    had no Slayer master at all), effectiveCombat 70+, core hasRequirements, Lost City done (Gates' own rule: the seeded quest
 *    history or the quest log), food for an npc level NPC_LEVEL fight (the P87Fight gate) and OPEN_MIN+ open Chaeldar tasks
 *    measured from the shed (where the bot stands after the errand). No finished-task minimum: 2009 has none, and a combat 100+
 *    bot could never earn one (Turael stops at 70, Vannaka at 99). A bot that qualifies always takes Chaeldar (no RNG draw).
 *  - list: Master.CHAELDAR.tasks with Slayer level 1 and no quest requirement, minus Slayer.VANNAKA_DENY; Slayer.served() keeps
 *    the ones a hunt serves (printed once as "[Slayer] Chaeldar list n: [...]"). The engine's generate() rolls her weighted table,
 *    the errand re-rolls into that set as at Vannaka (no size re-roll: her tasks are 110-170).
 *  - the way in: far from the shed, Slayer.go (one paid teleport near it, the errand's cap and purse rules, else the planner
 *    walk); within ENTER_R of it, a free scripted move onto her tile (the shed's shimmer plus the walk inside Zanaris, off the
 *    grid); errand end and brain start: a free move back out to the shed tile. The dramen staff is not modelled: a seeded Lost
 *    City stands for the quest and the staff it gives.
 *  - points: the engine pays her streak points on completion; a cancel (30 points) goes through Slayer's item 6 code.
 * Lines: "[Slayer] chaeldar <bot> ladder ..." (at most once per bot per LOG_GAP_MS), "... enters Zanaris", "... leaves Zanaris",
 * "... assigned", and the hourly "[Slayer] chaeldar hourly ..." (MarketBrain timer, only with the switch on).
 */
final class W9Chaeldar {
    private W9Chaeldar() { }

    static final boolean ON = Slayer.CHAELDAR;
    /** npc_spawns.json 1598. */
    static final Location AT = Location.create(2447, 4429, 0);
    /** ZanarisPlugin's exit tile, west of the Lumbridge swamp shed door (2406 at 3202,3169): the side a player enters from. */
    static final Location SHED_HINT = Location.create(3201, 3169, 0);
    static final int MIN_COMBAT = 70, MAX_COMBAT = 138, OPEN_MIN = 2, NPC_LEVEL = 90, ENTER_R = 2, NEAR_HER = 3;
    /** Zanaris and its side rooms (map squares 37-38 x 68-69). */
    static final int ZX0 = 2368, ZX1 = 2495, ZY0 = 4352, ZY1 = 4479;
    static final long LOG_GAP_MS = 10 * 60_000L;

    static final AtomicInteger LADDER = new AtomicInteger(), PICKED = new AtomicInteger(), ENTERS = new AtomicInteger(),
            LEAVES = new AtomicInteger(), ASSIGNED = new AtomicInteger();
    static final Map<String, Long> NEXT_LOG = new ConcurrentHashMap<>();

    // ---------------- pure rules (TestW9Chaeldar) ----------------

    /** The level and count gates (no world reads): combat 70+, no top, effectiveCombat 70+. */
    static boolean pre(int combat, int eff) { return combat >= MIN_COMBAT && combat <= MAX_COMBAT && eff >= MIN_COMBAT; }

    /** Chaeldar for this bot: pre, core requirements, Lost City, food, and OPEN_MIN+ open tasks. */
    static boolean can(int combat, int eff, boolean coreReq, boolean lostCity, boolean food, int open) {
        return pre(combat, eff) && coreReq && lostCity && food && open >= OPEN_MIN;
    }

    /** chooseMaster's last step: Chaeldar when on and the bot can, else the ladder's own answer, untouched. */
    static Master pick(boolean on, Master ladder, boolean can) { return on && can ? Master.CHAELDAR : ladder; }

    /** The errand gate's extra term (Slayer.eligibleLadder stops at 99): a bot that had a job, combat 70..MAX_COMBAT. */
    static boolean eligible(boolean on, boolean hadJob, int combat) { return on && hadJob && combat >= MIN_COMBAT && combat <= MAX_COMBAT; }

    /** Lost City as Gates' quest rule reads it: in the seeded history, or complete in the quest log. */
    static boolean lostCityOk(boolean seeded, boolean complete) { return seeded || complete; }

    /** A task on her bot list: Slayer level 1, no quest requirement, not on Vannaka's deny list (special-item tasks). */
    static boolean listed(int levelReq, boolean questReq, String name) { return levelReq <= 1 && !questReq && name != null && !Slayer.VANNAKA_DENY.contains(name); }

    static boolean inZanaris(Location l) { return l != null && l.getZ() == 0 && l.getX() >= ZX0 && l.getX() <= ZX1 && l.getY() >= ZY0 && l.getY() <= ZY1; }

    /** The distance the errand's walk gates use: 0 inside Zanaris, else to the shed tile. */
    static double dist(Location here, Location shed) {
        if (here == null) return Double.MAX_VALUE;
        if (inZanaris(here)) return 0;
        return here.getDistance(shed != null ? shed : SHED_HINT);
    }

    /** One ladder line per bot per LOG_GAP_MS. */
    static boolean logDue(Map<String, Long> next, String name, long now) {
        Long n = next.get(name);
        if (n != null && now < n) return false;
        next.put(name, now + LOG_GAP_MS);
        return true;
    }

    static String yn(boolean b) { return b ? "y" : "n"; }

    /** The hourly line from counts: {qualify, combat70, ladder, picked, enters, leaves, assigned, holding}. */
    static String line(int[] c) {
        return "[Slayer] chaeldar hourly on=" + ON + " qualify=" + c[0] + "/" + c[1] + " ladder=" + c[2] + " picked=" + c[3]
                + " enters=" + c[4] + " leaves=" + c[5] + " assigned=" + c[6] + " holding=" + c[7];
    }

    // ---------------- task list ----------------

    private static volatile Set<Tasks> allow;

    /** Master.CHAELDAR.tasks filtered by listed(); Slayer.served(CHAELDAR) then keeps the ones a hunt serves. */
    static Set<Tasks> allow() {
        Set<Tasks> a = allow;
        if (a != null) return a;
        EnumSet<Tasks> out = EnumSet.noneOf(Tasks.class);
        for (Master.Task mt : Master.CHAELDAR.tasks) if (mt != null && mt.task != null && listed(mt.task.levelReq, mt.task.questReq != null, mt.task.name())) out.add(mt.task);
        allow = a = Collections.unmodifiableSet(out);
        return a;
    }

    // ---------------- world glue (every caller is behind Slayer.CHAELDAR) ----------------

    private static volatile Location shedAt;
    private static volatile long shedTry;

    /** The shed tile resolved with Surveyor.walkable (map data, so at runtime; retried at most once a minute), else SHED_HINT. */
    static Location out() {
        Location d = shedAt;
        if (d != null) return d;
        long now = System.currentTimeMillis();
        if (now - shedTry >= 60_000) {
            shedTry = now;
            try { d = Surveyors.Surveyor.walkable(SHED_HINT); } catch (Throwable t) { d = null; }
            if (d != null && d.getZ() == 0 && !inZanaris(d) && d.getDistance(SHED_HINT) <= 4) { shedAt = d; System.out.println("[Slayer] chaeldar shed at " + d.getX() + "," + d.getY()); }
        }
        return shedAt != null ? shedAt : SHED_HINT;
    }

    static double dist(Location here) { return inZanaris(here) ? 0 : dist(here, out()); }

    static boolean lostCity(Player p) {
        if (p == null) return false;
        boolean seeded = false, complete = false;
        try { Set<String> s = Gates.SEEDED.get(p.getUsername().toLowerCase(Locale.ROOT)); seeded = s != null && s.contains("LOST_CITY"); } catch (Throwable ignored) { }
        if (!seeded) try { complete = p.getQuestRepository().isComplete(content.data.Quests.LOST_CITY); } catch (Throwable ignored) { }
        return lostCityOk(seeded, complete);
    }

    /** Food (pack + bank, heals 7+) for an npc level NPC_LEVEL fight passes the P87Fight gate (Slayer.ladderFood with her level). */
    static boolean food(SmartAdventurers.Brain b, int combat) {
        int need = P87Fight.foodNeed(NPC_LEVEL, combat), have = 0;
        try { for (int[] f : P87Fight.HEAL) if (f[1] >= 7) have += b.bot.getBank().getAmount(f[0]) + b.bot.getInventory().getAmount(f[0]); } catch (Throwable ignored) { }
        return P87Fight.gate(have, need, 0, Ledger.Wallet.coins(b.bot));
    }

    /** Slayer.chooseMaster hook, after ladder7 (its RNG draw already made): Chaeldar when the bot qualifies, else `ladder`. */
    static Master choose(SmartAdventurers.Brain b, Master ladder, int combat, int eff, int done) {
        Player bot = b.bot;
        if (!pre(combat, eff)) return ladder;
        boolean core = false;
        try { core = Master.CHAELDAR.hasRequirements(bot); } catch (Throwable ignored) { }
        boolean lc = lostCity(bot), fd = food(b, combat);
        int open = -1, listed = -1; String closed = "";
        if (core && lc && fd) {
            Set<Tasks> served = Slayer.served(Master.CHAELDAR);
            StringBuilder w = new StringBuilder();
            boolean pays = Slayer.FIX6 && dist(bot.getLocation()) > AfkTasks.Runner.TELEPORT_MIN && !Slayer.WALK_ONLY && Slayer.fareOk(bot);
            Set<Tasks> os = Slayer.FIX6 ? Slayer.openSet(b, served, w, out(), Slayer.afterFare(Ledger.Wallet.coins(bot), pays, Preflight.fare())) : Slayer.openSet(b, served, w);
            open = os.size(); listed = served.size(); closed = w.toString().trim();
        }
        boolean ok = can(combat, eff, core, lc, fd, Math.max(0, open));
        Master m = pick(true, ladder, ok);
        LADDER.incrementAndGet();
        if (m == Master.CHAELDAR) PICKED.incrementAndGet();
        if (logDue(NEXT_LOG, bot.getUsername(), System.currentTimeMillis()))
            System.out.println("[Slayer] chaeldar " + bot.getUsername() + " ladder combat " + combat + "/" + eff + " done " + done + " core " + yn(core) + " lostCity " + yn(lc)
                    + " food " + yn(fd) + " open " + (open < 0 ? "-" : open + "/" + listed) + (ok || closed.isEmpty() ? "" : " closed: " + Slayer.cut(closed, 300)) + " -> " + Slayer.masterName(m) + (m == null ? " (none)" : ""));
        return m;
    }

    /** A free scripted move (the shed's shimmer, the walk inside Zanaris): the nav grid has no Zanaris. */
    static void move(Player bot, Location to) {
        Bob.tele(bot).getProperties().setTeleportLocation(to);
        try { NavWalker.forget(bot); } catch (Throwable ignored) { }
    }

    /** Slayer.tick hook, more than NEAR_HER tiles from her: true = handled this tick (walk or pay to the shed, enter, step to her). */
    static boolean go(Player bot, Slayer.St s) {
        Location here = bot.getLocation();
        if (here == null) return false;
        if (inZanaris(here)) { if (here.getDistance(AT) > NEAR_HER) move(bot, AT); return true; }
        Location shed = out();
        if (here.getDistance(shed) <= ENTER_R) {
            move(bot, AT);
            int n = ENTERS.incrementAndGet();
            System.out.println("[Slayer] chaeldar " + bot.getUsername() + " enters Zanaris by the shed from " + here.getX() + "," + here.getY() + " (" + n + ")");
            return true;
        }
        Slayer.go(bot, shed, s); // TELEPORT_MIN+ away: one paid teleport near the shed (cap and purse rules); else the planner walk, free
        return true;
    }

    /** Errand end and brain start: a bot inside Zanaris goes back out to the shed tile, free. True = moved. */
    static boolean leave(Player bot, String why) {
        if (bot == null) return false;
        Location here = bot.getLocation();
        if (!inZanaris(here)) return false;
        Location to = out();
        move(bot, to);
        int n = LEAVES.incrementAndGet();
        System.out.println("[Slayer] chaeldar " + bot.getUsername() + " leaves Zanaris to " + to.getX() + "," + to.getY() + " from " + here.getX() + "," + here.getY() + " (" + why + ", " + n + ")");
        return true;
    }

    /** Slayer.tick hook after an assignment at her. */
    static void assigned(Player bot, String task, int amount) {
        int n = ASSIGNED.incrementAndGet();
        System.out.println("[Slayer] chaeldar " + bot.getUsername() + " assigned " + task + " x" + amount + " (" + n + ")");
    }

    /** The hourly line: the fleet's qualifiers now (combat 70+ with Lost City / combat 70+), the counters, Chaeldar tasks held. */
    static String line() {
        int q = 0, c70 = 0, hold = 0;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
            try {
                if (b == null || b.bot == null) continue;
                int c = b.bot.getProperties().getCurrentCombatLevel();
                if (c >= MIN_COMBAT && c <= MAX_COMBAT) { c70++; if (lostCity(b.bot)) q++; }
                if (b.slaySt != null && b.slaySt.had != null && b.slaySt.master == Master.CHAELDAR) hold++;
            } catch (Throwable ignored) { }
        }
        return line(new int[] {q, c70, LADDER.get(), PICKED.get(), ENTERS.get(), LEAVES.get(), ASSIGNED.get(), hold});
    }
}
