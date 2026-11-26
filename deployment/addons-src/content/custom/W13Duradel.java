package content.custom;

import content.global.skill.slayer.Master;
import content.global.skill.slayer.Tasks;
import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * W13 lane B item 1: Duradel, the Slayer master above Chaeldar. Switch: Slayer.DURADEL = -Dbots.slayer.duradel (default off) AND
 * the Chaeldar switch (Slayer.CHAELDAR, which itself needs the Vannaka ladder). Off: every Slayer / SmartAdventurers / MarketBrain
 * hook tests the switch first, so this class is never initialised, no RNG draw is added or moved, no line, no timer.
 *
 * 2009 rules (engine Master.DURADEL, npc 8275, read 11-20): combat 100, Slayer 50, 130-200 kills, streak points 15/75/225. He
 * stands upstairs in Shilo Village (npc_spawns.json 2869,2982,1). A player gets in with Shilo Village done (ShiloCart and
 * BrokenCartBypass both test hasRequirement(SHILO_VILLAGE)); the server has no Shilo Village quest class, so no player or bot can
 * finish it in game: only Gates' seeded history (SHILO_VILLAGE, combat 60+ and total 700+, 50%) qualifies a bot. The usual way in
 * is Hajedy's cart from Brimhaven (2780,3212, 10 gp each way, lands on 2834,2951). Shilo has no walk out on the nav grid (P39 R2).
 *
 * Bot rules:
 *  - who: combat 100+ (no top below MAX_COMBAT), effectiveCombat 100+, core hasRequirements (combat 100, Slayer 50), Shilo Village
 *    seeded (or complete, if a server ever has the quest), FARE*2+ coins, food for an npc level NPC_LEVEL fight and OPEN_MIN+ open
 *    Duradel tasks measured from the cart. A bot that qualifies always takes Duradel over Chaeldar (no RNG draw).
 *  - list: Master.DURADEL.tasks with Slayer level 1 and no quest requirement, minus Slayer.VANNAKA_DENY; Slayer.served() keeps the
 *    ones a hunt serves. The errand re-rolls into that set as at Chaeldar (no size re-roll).
 *  - the way in: far from the cart, Slayer.go (one paid teleport near it, the errand's cap and purse rules, else the planner walk);
 *    within ENTER_R of it, the 10 gp fare (Ledger.Wallet.payFee, a coin sink) and a scripted move onto his tile (the ride plus the
 *    walk upstairs, off the grid). w33: the cart is on Karamja and the nav grid has no way over (w26: 2 of 2 errands "no corridor",
 *    gave up at the Ardougne dock; Slayer.go only pays past 320 tiles). A bot the sea or the Brimhaven gate (2816,3182-3183, shut
 *    for bots) cuts off pays the standard "sea-crossing" teleport straight to the cart (cross()); choose() asks for that fare too.
 *    Errand end and brain start: the fare back when the bot has it, and a move out to the cart tile.
 * Lines: "[Slayer] duradel <bot> ladder ...", "... enters Shilo", "... leaves Shilo", "... assigned", and the hourly
 * "[Slayer] duradel hourly ..." (MarketBrain timer, only with the switch on).
 */
final class W13Duradel {
    private W13Duradel() { }

    static final boolean ON = Slayer.DURADEL;
    /** npc_spawns.json 8275 (upstairs). */
    static final Location AT = Location.create(2869, 2982, 1);
    /** ShiloCart's Brimhaven end (Hajedy): where the ride starts and where the way back lands. */
    static final Location CART_HINT = Location.create(2780, 3212, 0);
    static final int MIN_COMBAT = 100, MAX_COMBAT = 138, OPEN_MIN = 2, NPC_LEVEL = 120, ENTER_R = 3, NEAR_HIM = 3, FARE = 10;
    /** Shilo Village (Gates "shilo"), any plane. */
    static final int SX0 = 2817, SX1 = 2880, SY0 = 2944, SY1 = 3005;
    static final long LOG_GAP_MS = 10 * 60_000L, CROSS_GAP_MS = 15 * 60_000L; // w33 review: above Slayer.WALK_GIVEUP_MS (8 min), one errand cannot pay twice

    static final AtomicInteger LADDER = new AtomicInteger(), PICKED = new AtomicInteger(), ENTERS = new AtomicInteger(),
            LEAVES = new AtomicInteger(), ASSIGNED = new AtomicInteger(), FARES = new AtomicInteger(), CROSSED = new AtomicInteger();
    static final Map<String, Long> CROSS_AT = new ConcurrentHashMap<>();
    static final Map<String, Long> NEXT_LOG = new ConcurrentHashMap<>();

    // ---------------- pure rules (TestW13Duradel) ----------------

    static boolean pre(int combat, int eff) { return combat >= MIN_COMBAT && combat <= MAX_COMBAT && eff >= MIN_COMBAT; }

    /** Duradel for this bot: pre, core requirements (combat 100, Slayer 50), Shilo Village, both fares, food, OPEN_MIN+ open tasks. */
    static boolean can(int combat, int eff, boolean coreReq, boolean shilo, long coins, boolean food, int open) { return can(combat, eff, coreReq, shilo, coins, food, open, 2L * FARE); }
    /** w33: need = need(cut, crossFare): a bot the sea or the gate cuts off must hold the crossing as well. */
    static boolean can(int combat, int eff, boolean coreReq, boolean shilo, long coins, boolean food, int open, long need) {
        return pre(combat, eff) && coreReq && shilo && coins >= need && food && open >= OPEN_MIN;
    }

    /** After Chaeldar's pick: Duradel when on and the bot can, else the earlier answer, untouched. */
    static Master pick(boolean on, Master before, boolean can) { return on && can ? Master.DURADEL : before; }

    static boolean shiloOk(boolean seeded, boolean complete) { return seeded || complete; }

    static boolean listed(int levelReq, boolean questReq, String name) { return levelReq <= 1 && !questReq && name != null && !Slayer.VANNAKA_DENY.contains(name); }

    static boolean inShilo(Location l) { return l != null && l.getX() >= SX0 && l.getX() <= SX1 && l.getY() >= SY0 && l.getY() <= SY1; }

    /** The distance the errand's walk gates use: 0 inside Shilo, else to the cart tile. */
    static double dist(Location here, Location cart) {
        if (here == null) return Double.MAX_VALUE;
        if (inShilo(here)) return 0;
        return here.getDistance(cart != null ? cart : CART_HINT);
    }

    static boolean logDue(Map<String, Long> next, String name, long now) {
        Long n = next.get(name);
        if (n != null && now < n) return false;
        next.put(name, now + LOG_GAP_MS);
        return true;
    }

    static String yn(boolean b) { return b ? "y" : "n"; }

    /** w33: Karamja east of the Brimhaven fence (Musa Point side). Offline (p38-navport.py, nav-p0.bin, edge 2815/2816,3182-3183
     *  sealed): the 4376 tiles the gate cuts off from the cart all lie in this box, and no tile of the cart's side does. Bots do
     *  not get through that gate (results/w30-nature.md), so from here the cart is as far as from the mainland. */
    static boolean eastOfGate(int x, int y, int z) { return z == 0 && x >= 2816 && x <= 2960 && y >= 3138 && y <= 3206; }

    /** w33: the errand walk has no way to the cart: the sea lies between (w26: both errands ended "no corridor" and gave up at the
     *  Ardougne dock; under Slayer.go's 320 tiles no fare was tried), or the Brimhaven gate does. Never inside Shilo or at the cart. */
    static boolean cut(boolean inShilo, boolean nearCart, boolean acrossSea, boolean eastOfGate) { return !inShilo && !nearCart && (acrossSea || eastOfGate); }

    /** w33: pay the crossing now? Only when cut off, with Slayer.go's own rules (not walk-only, the purse floor, the errand's
     *  teleport cap), no teleport already under way and none paid in the last CROSS_GAP_MS. */
    static boolean crossNow(boolean cut, boolean walkOnly, boolean fareOk, boolean capOk, boolean pending, long now, long last) {
        return cut && !walkOnly && fareOk && capOk && !pending && now - last >= CROSS_GAP_MS;
    }

    /** w33 review: a crossing is worth paying only when its landing is not itself cut off from the cart (a trap cell at the cart
     *  sends Watchdog.landing to the Musa Point boat landing, east of the gate: pay, still cut off, pay again). */
    static boolean landOk(boolean landCut) { return !landCut; }

    /** w33: the coins Duradel needs: both cart fares, and the crossing when the bot is cut off from the cart. */
    static long need(boolean cut, long crossFare) { return 2L * FARE + (cut ? crossFare : 0); }

    /** {qualify, combat100, ladder, picked, enters, leaves, assigned, holding, fares}. */
    static String line(int[] c) {
        return "[Slayer] duradel hourly on=" + ON + " qualify=" + c[0] + "/" + c[1] + " ladder=" + c[2] + " picked=" + c[3]
                + " enters=" + c[4] + " leaves=" + c[5] + " assigned=" + c[6] + " holding=" + c[7] + " fares=" + c[8] + (c.length > 9 ? " crossed=" + c[9] : "");
    }

    // ---------------- task list ----------------

    private static volatile Set<Tasks> allow;

    static Set<Tasks> allow() {
        Set<Tasks> a = allow;
        if (a != null) return a;
        EnumSet<Tasks> out = EnumSet.noneOf(Tasks.class);
        for (Master.Task mt : Master.DURADEL.tasks) if (mt != null && mt.task != null && listed(mt.task.levelReq, mt.task.questReq != null, mt.task.name())) out.add(mt.task);
        allow = a = Collections.unmodifiableSet(out);
        return a;
    }

    // ---------------- world glue (every caller is behind Slayer.DURADEL) ----------------

    private static volatile Location cartAt;
    private static volatile long cartTry;

    /** The cart tile resolved with Surveyor.walkable (retried at most once a minute), else CART_HINT. */
    static Location out() {
        Location d = cartAt;
        if (d != null) return d;
        long now = System.currentTimeMillis();
        if (now - cartTry >= 60_000) {
            cartTry = now;
            try { d = Surveyors.Surveyor.walkable(CART_HINT); } catch (Throwable t) { d = null; }
            if (d != null && d.getZ() == 0 && !inShilo(d) && d.getDistance(CART_HINT) <= 4) { cartAt = d; System.out.println("[Slayer] duradel cart at " + d.getX() + "," + d.getY()); }
        }
        return cartAt != null ? cartAt : CART_HINT;
    }

    static double dist(Location here) { return inShilo(here) ? 0 : dist(here, out()); }

    /** Shilo Village as Gates' quest rule reads it; the quest log is asked only when the server has the quest (a missing one logs). */
    static boolean shilo(Player p) {
        if (p == null) return false;
        boolean seeded = false, complete = false;
        try { Set<String> s = Gates.SEEDED.get(p.getUsername().toLowerCase(Locale.ROOT)); seeded = s != null && s.contains("SHILO_VILLAGE"); } catch (Throwable ignored) { }
        if (!seeded) try { if (Gates.implemented(p, content.data.Quests.SHILO_VILLAGE)) complete = p.getQuestRepository().isComplete(content.data.Quests.SHILO_VILLAGE); } catch (Throwable ignored) { }
        return shiloOk(seeded, complete);
    }

    static boolean food(SmartAdventurers.Brain b, int combat) {
        int need = P87Fight.foodNeed(NPC_LEVEL, combat), have = 0;
        try { for (int[] f : P87Fight.HEAL) if (f[1] >= 7) have += b.bot.getBank().getAmount(f[0]) + b.bot.getInventory().getAmount(f[0]); } catch (Throwable ignored) { }
        return P87Fight.gate(have, need, 0, Ledger.Wallet.coins(b.bot));
    }

    /** Slayer.chooseMaster hook, after W9Chaeldar.choose (no draw): Duradel when the bot qualifies, else `before`. */
    static Master choose(SmartAdventurers.Brain b, Master before, int combat, int eff, int done) {
        Player bot = b.bot;
        if (!pre(combat, eff)) return before;
        boolean core = false;
        try { core = Master.DURADEL.hasRequirements(bot); } catch (Throwable ignored) { }
        boolean sh = shilo(bot), fd = food(b, combat);
        long coins = Ledger.Wallet.coins(bot);
        boolean cut = cut(bot.getLocation()); // w33: the sea or the Brimhaven gate between the bot and the cart
        long need = need(cut, cut ? crossFare() : 0);
        int open = -1, listed = -1; String closed = "";
        if (core && sh && fd && coins >= need) {
            Set<Tasks> served = Slayer.served(Master.DURADEL);
            StringBuilder w = new StringBuilder();
            boolean pays = Slayer.FIX6 && (cut || dist(bot.getLocation()) > AfkTasks.Runner.TELEPORT_MIN) && !Slayer.WALK_ONLY && Slayer.fareOk(bot);
            Set<Tasks> os = Slayer.FIX6 ? Slayer.openSet(b, served, w, out(), Slayer.afterFare(coins, pays, Preflight.fare())) : Slayer.openSet(b, served, w);
            open = os.size(); listed = served.size(); closed = w.toString().trim();
        }
        boolean ok = can(combat, eff, core, sh, coins, fd, Math.max(0, open), need) && (!cut || !Slayer.WALK_ONLY && Slayer.fareOk(bot) && landOk(cut(landing())));
        Master m = pick(true, before, ok);
        LADDER.incrementAndGet();
        if (m == Master.DURADEL) PICKED.incrementAndGet();
        if (logDue(NEXT_LOG, bot.getUsername(), System.currentTimeMillis()))
            System.out.println("[Slayer] duradel " + bot.getUsername() + " ladder combat " + combat + "/" + eff + " done " + done + " core " + yn(core) + " shilo " + yn(sh)
                    + " food " + yn(fd) + " coins " + coins + (cut ? " cut y need " + need : "") + " open " + (open < 0 ? "-" : open + "/" + listed) + (ok || closed.isEmpty() ? "" : " closed: " + Slayer.cut(closed, 300)) + " -> " + Slayer.masterName(m) + (m == null ? " (none)" : ""));
        return m;
    }

    static void move(Player bot, Location to) {
        Bob.tele(bot).getProperties().setTeleportLocation(to);
        try { NavWalker.forget(bot); } catch (Throwable ignored) { }
    }

    /** Slayer.tick hook, more than NEAR_HIM tiles from him: true = handled this tick. */
    static boolean go(Player bot, Slayer.St s) {
        Location here = bot.getLocation();
        if (here == null) return false;
        if (inShilo(here)) { if (here.getZ() != AT.getZ() || here.getDistance(AT) > NEAR_HIM) move(bot, AT); return true; }
        Location cart = out();
        if (here.getDistance(cart) <= ENTER_R) {
            boolean paid = false;
            try { paid = Ledger.Wallet.payFee(bot, FARE, "shilo cart"); } catch (Throwable ignored) { }
            if (paid) FARES.incrementAndGet();
            move(bot, AT);
            int n = ENTERS.incrementAndGet();
            System.out.println("[Slayer] duradel " + bot.getUsername() + " enters Shilo by the cart from " + here.getX() + "," + here.getY() + (paid ? "" : " (fare short)") + " (" + n + ")");
            return true;
        }
        if (cross(bot, here, cart, s)) return true;
        Slayer.go(bot, cart, s);
        return true;
    }

    static long crossFare() { try { return Math.max(100, Ledger.price(563) + 3L * Ledger.price(556)); } catch (Throwable t) { return 180; } }

    /** Where the crossing lands: Watchdog.landing of the cart tile (the cart itself unless its cell is a trap). */
    static Location landing() { Location cart = out(); try { Location l = Watchdog.landing(cart); return l != null ? l : cart; } catch (Throwable t) { return cart; } }

    static boolean cut(Location here) {
        if (here == null) return false;
        Location cart = out();
        boolean sea = false;
        try { sea = AfkTasks.Runner.acrossSea(here, cart); } catch (Throwable ignored) { }
        return cut(inShilo(here), here.getDistance(cart) <= ENTER_R, sea, eastOfGate(here.getX(), here.getY(), here.getZ()));
    }

    /** w33: the way over when the walk has none: the paid crossing every other bot walk across the sea uses (AfkTasks.Runner.goTo:
     *  Ledger.Wallet.payTeleport "sea-crossing", Spectacle.teleport to Watchdog.landing), straight to the cart; no boat (the 30 gp
     *  Port Sarim boat lands east of the gate). Counted on the errand's teleport cap as Slayer.go's. False = not cut off, or the
     *  rules or the purse say no: Slayer.go walks as before. */
    static boolean cross(Player bot, Location here, Location cart, Slayer.St s) {
        if (!cut(here)) return false;
        long now = System.currentTimeMillis();
        String key = bot.getUsername();
        if (!crossNow(true, Slayer.WALK_ONLY, Slayer.fareOk(bot), Slayer.capOk(s), bot.getProperties().getTeleportLocation() != null, now, CROSS_AT.getOrDefault(key, 0L))) return false;
        Location land = landing();
        if (!landOk(cut(land))) return false; // w33 review: no fare for a landing that is cut off too; Slayer.go walks and the errand gives up
        if (!Ledger.Wallet.payTeleport(bot, "sea-crossing")) return false;
        CROSS_AT.put(key, now);
        if (Ledger.CLERK_FIX2 && s != null) Slayer.capCount(bot, s, 1, "errand");
        try { NavWalker.forget(bot); } catch (Throwable ignored) { }
        try { Spectacle.teleport(bot, land); } catch (Throwable ignored) { }
        int n = CROSSED.incrementAndGet();
        System.out.println("[Slayer] duradel " + bot.getUsername() + " crosses to the cart from " + here.getX() + "," + here.getY() + " (paid teleport, " + n + ")");
        return true;
    }

    /** Errand end and brain start: a bot inside Shilo rides back to the cart tile (the fare when it has it). True = moved. */
    static boolean leave(Player bot, String why) {
        if (bot == null) return false;
        Location here = bot.getLocation();
        if (!inShilo(here)) return false;
        boolean paid = false;
        try { paid = Ledger.Wallet.coins(bot) >= FARE && Ledger.Wallet.payFee(bot, FARE, "shilo cart back"); } catch (Throwable ignored) { }
        if (paid) FARES.incrementAndGet();
        Location to = out();
        move(bot, to);
        int n = LEAVES.incrementAndGet();
        System.out.println("[Slayer] duradel " + bot.getUsername() + " leaves Shilo to " + to.getX() + "," + to.getY() + " from " + here.getX() + "," + here.getY() + " (" + why + (paid ? "" : ", no fare") + ", " + n + ")");
        return true;
    }

    static void assigned(Player bot, String task, int amount) {
        int n = ASSIGNED.incrementAndGet();
        System.out.println("[Slayer] duradel " + bot.getUsername() + " assigned " + task + " x" + amount + " (" + n + ")");
    }

    static String line() {
        int q = 0, c100 = 0, hold = 0;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
            try {
                if (b == null || b.bot == null) continue;
                int c = b.bot.getProperties().getCurrentCombatLevel();
                if (c >= MIN_COMBAT && c <= MAX_COMBAT) { c100++; if (shilo(b.bot) && b.bot.getSkills().getLevel(core.game.node.entity.skill.Skills.SLAYER) >= 50) q++; }
                if (b.slaySt != null && b.slaySt.had != null && b.slaySt.master == Master.DURADEL) hold++;
            } catch (Throwable ignored) { }
        }
        return line(new int[] {q, c100, LADDER.get(), PICKED.get(), ENTERS.get(), LEAVES.get(), ASSIGNED.get(), hold, FARES.get(), CROSSED.get()});
    }
}
