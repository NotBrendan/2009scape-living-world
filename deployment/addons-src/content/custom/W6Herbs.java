package content.custom;

import java.util.function.IntUnaryOperator;

/**
 * W6 skills, wave 3 herb lane (the design notes section 3, Herblore and Farming rows). Two -D flags,
 * default OFF; with a flag off every caller runs train-61's code unchanged (each hook is `if (W6Herbs.X ...)`).
 *
 * Like W3Skills / W5aSkills this class holds only the flags, the pure rules, the tables and the counters, so plain java
 * can test it without a world (TestW6Herbs). It reads system properties only: no engine class, no Farming, no AfkTasks.
 *
 *   -Dartisans.herbSupply     Herblore: herbs and secondaries reach the herbalist.
 *                             (a) the herb gatherer hunts the Taverley dungeon chaos druids (its "no spot": the only surface
 *                                 druids stand in the tower north of Ardougne behind a Thieving 46 door, so the hunt map
 *                                 either dropped them as a trap or sent bots at a locked door);
 *                             (b) adventurers get a "Chaos druids" hunt at the same spot (their herbs sell at the bank like
 *                                 any loot), with a waypoint lead from the ladder foot (the underground walk has no planner);
 *                             (c) a limpwurt job (hill giants) so errands and bounties can ask for limpwurt roots.
 *   -Dbots.farming.herbs2     Farming: the herb seed table runs past ranarr up to torstol, and a herb seed gets its own
 *                             budget (10% of coins, 3,000 gp at most, and never more than 4 herbs' worth). Needs bots.farming.
 */
final class W6Herbs {
    private W6Herbs() { }

    static boolean has(int[] a, int id) { for (int x : a) if (x == id) return true; return false; }

    // ------------------------------------------------------------------ Herblore: artisans.herbSupply
    static final boolean SUPPLY = Boolean.getBoolean("artisans.herbSupply");

    /** The six Taverley dungeon chaos druids spawn at 2929-2937 x 9846-9850 (npc_spawns.json id 181); no door or key on the way. */
    static final int[] DRUID = {2932, 9848};
    static final String DRUID_NAME = "Chaos druid", DRUID_SPOT_NAME = "the Taverley dungeon chaos druids";
    /** The foot of the Taverley dungeon ladder (AfkTasks.Runner.entrance). */
    static final int[] FOOT = {2884, 9798};
    /** Ladder foot to the druids: the first 12 points of P84.LADDER_LEAD (TestP84Stuck tile BFS, every 8th tile). */
    static final int[][] LEAD = {{2884, 9802}, {2884, 9810}, {2884, 9818}, {2884, 9826}, {2885, 9833}, {2884, 9840}, {2886, 9846}, {2892, 9848},
            {2900, 9848}, {2908, 9848}, {2916, 9848}, {2924, 9848}};
    static final int LEAD_NEAR = 8, DRUID_NEAR = 10, FOOT_NEAR = 6;
    static volatile int gathererTrips, druidVias, druidStarts, limpStarts, snapeStarts;

    static double dist(int ax, int ay, int bx, int by) { return Math.hypot(ax - bx, ay - by); }

    /**
     * The next point to walk to in the Taverley dungeon, or null for the normal walk. Stateless: the lead point nearest the
     * walker, then the one after it (towards the druids) or before it (towards the ladder), so it only ever moves one way.
     * Only for a goal within 12 tiles of the druid spot or 4 of the ladder foot, a walker on z 0 within 8 tiles of the lead,
     * and not once the goal is close (10 tiles of the druids, 6 of the foot: the direct walk does the rest).
     */
    static int[] via(boolean on, int x, int y, int z, int dx, int dy) {
        if (!on || z != 0 || y < 9780 || y > 9860 || x < 2870 || x > 2945) return null;
        boolean toDruids = dist(dx, dy, DRUID[0], DRUID[1]) <= 12, toFoot = dist(dx, dy, FOOT[0], FOOT[1]) <= 4;
        if (!toDruids && !toFoot) return null;
        if (toDruids && dist(x, y, DRUID[0], DRUID[1]) <= DRUID_NEAR) return null;
        if (toFoot && dist(x, y, FOOT[0], FOOT[1]) <= FOOT_NEAR) return null;
        int best = -1; double bd = Double.MAX_VALUE;
        for (int i = 0; i < LEAD.length; i++) { double d = dist(x, y, LEAD[i][0], LEAD[i][1]); if (d < bd) { bd = d; best = i; } }
        if (bd > LEAD_NEAR && !(toDruids && dist(x, y, FOOT[0], FOOT[1]) <= 12)) return null; // off the corridor: not ours
        int next = toDruids ? best + 1 : best - 1;
        if (next < 0 || next >= LEAD.length) return null; // past the last point: straight on to the goal
        return LEAD[next];
    }

    /** A job start, counted for the hourly line. */
    static void started(boolean on, String huntName, boolean limpwurt, boolean snape) {
        if (!on) return;
        if (DRUID_NAME.equals(huntName)) druidStarts++;
        if (limpwurt) limpStarts++;
        if (snape) snapeStarts++;
    }

    /** w9 s62fire: a [W6h] line naming the bot, so the lane reader (farm/lanecall.sh) sees which bots a W6h switch touched
     *  (s62: herbSupply fired in both ON runs but wrote counters only). One line per bot and kind per SAY_EVERY; every caller
     *  is behind its flag. The word after the name ("herbSupply:" / "herbs2:") is the lane key. No RNG, no engine class. */
    static final long SAY_EVERY = 600_000L;
    static final java.util.Map<String, Long> SAID = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean due(String bot, String kind, long now) {
        if (bot == null || kind == null) return false;
        String k = bot + "|" + kind;
        Long t = SAID.get(k);
        if (t != null && now - t < SAY_EVERY) return false;
        SAID.put(k, now);
        return true;
    }
    static String botLine(String bot, String lane, String what) { return "[W6h] " + bot + " " + lane + ": " + what; }
    static void say(String bot, String lane, String kind, String what, long now) { if (due(bot, kind, now)) System.out.println(botLine(bot, lane, what)); }
    /** herbSupply job start (SmartAdventurers, beside started()): the Taverley druid hunt or the limpwurt job. The snape job is older than the flag: no line. */
    static void startedSay(String bot, String huntName, boolean limpwurt, long now) {
        if (DRUID_NAME.equals(huntName)) say(bot, "herbSupply", "druids", "hunts the Taverley dungeon chaos druids", now);
        if (limpwurt) say(bot, "herbSupply", "limpwurt", "limpwurt job at the hill giants", now);
    }

    /** The hunt whose spot the limpwurt job borrows (8.6% a kill, drop_tables.json; big bones every kill). */
    static final String LIMP_HUNT = "Hill Giant";
    /** t62 fix 1: the hill-giant limpwurt spot is for bots only: players' ::afk limpwurt keeps its old finding (no table spot). */
    static boolean botOnlySpot(boolean supply, boolean limpwurt, boolean bot) { return supply && limpwurt && !bot; }

    // ---- recipe-file check: where every input of a "first potion" comes from
    static final int VIAL_OF_WATER = 227, EYE_OF_NEWT = 221, LIMPWURT = 225, SNAPE = 231, SPIDER_EGGS = 223, UNICORN_DUST = 235, CHOC_DUST = 1975, WHITE_BERRIES = 239;
    /** Attack, antipoison, strength, restore, energy, defence, prayer potion (3): the potions a herbalist under 40 makes. */
    static final int[] FIRST_POTIONS = {121, 175, 115, 127, 3010, 133, 139};
    /** The three the flag is for. */
    static final int[] MUST_POTIONS = {121, 115, 139};
    /** Grimy herbs a chaos druid drops or a herb patch grows. */
    static final int[] GRIMY = {199, 201, 203, 205, 207, 3049, 209, 211, 213, 3051, 215, 2485, 217, 219};
    /** Where a raw herblore input comes from, or null when no bot gathers it and no shop stand-in sells it. */
    static String source(int id) {
        if (has(GRIMY, id)) return "chaos druid drops (gatherer, hunts) and herb patches";
        if (id == VIAL_OF_WATER) return "shop staple (Artisans.STAPLES)";
        if (id == EYE_OF_NEWT) return "shop staple for the herbalist (-Dartisans.herbFix)";
        if (id == LIMPWURT) return "limpwurt job at the hill giants (-Dartisans.herbSupply), hill giant and hobgoblin hunts";
        if (id == SNAPE) return "snape grass job at the hobgoblin peninsula";
        return null; // red spiders' eggs, unicorn horn dust, chocolate dust, white berries: see the note's gaps
    }

    // ------------------------------------------------------------------ Farming: bots.farming.herbs2
    static final boolean HERBS2 = Boolean.getBoolean("bots.farming") && Boolean.getBoolean("bots.farming.herbs2");
    /** {level, seed, crop (grimy), shopStocked 0}: the engine's Plantable herb rows above ranarr, best last. */
    static final int[][] HERBS_UP = {{38, 5296, 3049, 0}, {44, 5297, 209, 0}, {50, 5298, 211, 0}, {56, 5299, 213, 0}, {62, 5300, 3051, 0},
            {67, 5301, 215, 0}, {73, 5302, 2485, 0}, {79, 5303, 217, 0}, {85, 5304, 219, 0}};
    static final long HERB_BUDGET_MAX = Math.max(0, Long.getLong("bots.farming.herbs2.budget", 3_000L));
    /** A bought seed must cost no more than this many of its herbs at guide price (a patch gives 3 or more). */
    static final int HERB_WORTH = 4;
    static volatile int herbsUp, herbsBought, herbsTooDear;
    static volatile long herbSeedSpend;

    /** Farming.HERB_SEEDS: the base table (guam to ranarr), or with the flag the base rows followed by HERBS_UP. */
    static int[][] herbSeeds(boolean on, int[][] base) {
        if (!on) return base;
        int[][] t = new int[base.length + HERBS_UP.length][];
        System.arraycopy(base, 0, t, 0, base.length); System.arraycopy(HERBS_UP, 0, t, base.length, HERBS_UP.length);
        return t;
    }
    /** The herb a seed grows (from the given table), -1 unknown. */
    static int cropOf(int[][] table, int seed) { for (int[] r : table) if (r[1] == seed) return r[2]; return -1; }

    /** What may be spent on the herb seed: the run's budget, raised with the flag to 10% of the coins, HERB_BUDGET_MAX at most. */
    static long herbBudget(boolean on, long coins, long left) { return on ? Math.max(left, Math.min(Math.max(0, coins) / 10, HERB_BUDGET_MAX)) : left; }
    /** The run's budget after the herb step: what was left before it, or less when the seed ate into it. Never more. */
    static long budgetAfter(long leftBefore, long leftNow) { return Math.max(0, Math.min(leftBefore, leftNow)); }
    /** A seed the bot owns is always worth planting; one it must buy only when qty seeds cost no more than HERB_WORTH herbs each. */
    static boolean seedWorth(boolean on, int own, int qty, long seedValue, long cropValue) {
        if (!on || own >= qty) return true;
        boolean ok = seedValue <= HERB_WORTH * Math.max(0, cropValue);
        if (!ok) herbsTooDear++;
        return ok;
    }
    /** Herb seeds of one kind a bot keeps in its bank before the rest is sold as before. */
    static final int SEED_KEEP = Math.max(0, Integer.getInteger("bots.farming.herbs2.keep", 10));
    static volatile int seedsKept;
    /** Bank sale: true = bank this looted item instead of selling it: a herb seed of the table the bot's Farming level can plant, while it holds under SEED_KEEP in the bank. Counts a keep. */
    static boolean keepSeed(boolean on, int[][] table, int id, int farming, int inBank) {
        if (!on || table == null || inBank >= SEED_KEEP) return false;
        for (int[] r : table) if (r[1] == id) { if (farming < r[0]) return false; seedsKept++; return true; }
        return false;
    }
    /** w9 s62fire: keepSeed plus a [W6h] line naming the bot when the seed is kept (AfkTasks bank sale, behind the flag). */
    static boolean keepSeed(boolean on, int[][] table, int id, int farming, int inBank, String bot, long now) {
        boolean k = keepSeed(on, table, id, farming, inBank);
        if (k) say(bot, "herbs2", "keep", "banks herb seed " + id + " to plant (" + inBank + " banked before)", now);
        return k;
    }
    /** After the herb step: counts a seed above ranarr's row and coins spent on herb seeds. */
    static void picked(boolean on, int seed, long spent) {
        if (!on || seed <= 0) return;
        for (int[] r : HERBS_UP) if (r[1] == seed) herbsUp++;
        if (spent > 0) { herbsBought++; herbSeedSpend += spent; }
    }
    /** w9 s62fire: after picked(), a [W6h] line naming the bot for a seed above ranarr or one bought on the herb allowance. */
    static void pickedSay(String bot, int seed, long spent, long now) {
        if (seed <= 0) return;
        boolean up = false; for (int[] r : HERBS_UP) if (r[1] == seed) up = true;
        if (up || spent > 0) say(bot, "herbs2", "seed", "herb seed " + seed + (up ? " (above ranarr)" : "") + (spent > 0 ? " bought for " + spent + " gp" : " from the bank"), now);
    }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return SUPPLY || HERBS2; }
    static String flags() { return ((SUPPLY ? "herbSupply " : "") + (HERBS2 ? "herbs2 " : "")).trim(); }
    /** stock: Exchange stock by item id (MarketBrain::stockOf); null = no stock part (unit test). */
    static String line(IntUnaryOperator stock) {
        String s = "[W6h] on=[" + flags() + "] gathererTrips=" + gathererTrips + " druidStarts=" + druidStarts + " druidVias=" + druidVias + " limpStarts=" + limpStarts
                + " snapeStarts=" + snapeStarts + " herbsUp=" + herbsUp + " herbsBought=" + herbsBought + " herbsTooDear=" + herbsTooDear + " herbSeedSpend=" + herbSeedSpend + " seedsKept=" + seedsKept;
        if (stock == null) return s;
        long grimy = 0; for (int id : GRIMY) grimy += stock.applyAsInt(id);
        return s + " stockGrimy=" + grimy + " stockLimpwurt=" + stock.applyAsInt(LIMPWURT) + " stockSnape=" + stock.applyAsInt(SNAPE) + " stockEggs=" + stock.applyAsInt(SPIDER_EGGS);
    }
}
