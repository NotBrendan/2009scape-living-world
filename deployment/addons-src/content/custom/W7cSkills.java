package content.custom;

import java.util.function.IntUnaryOperator;

/**
 * W7 skills, wave 4 lane "chains" (the design notes section 4): Construction plank types, woodcutters
 * who fletch, and the two Ardougne pickpocket jobs. Every item has its own -D flag, default OFF; with a flag off its caller
 * runs train-61's code unchanged.
 *
 * Like W3Skills / W5bSkills this class holds only the flags, the pure decision rules and the counters, so plain java can
 * test it without a world (TestW7cSkills). The engine side (packs, banks, the Ledger, the fletching script) is W7cHooks.
 *
 *   -Dbots.plankTypes      Construction: XP and a required level per plank type (plain 1, oak 15, teak 35, mahogany 50);
 *                          plain planks as a new, cheapest run, and they need nails (1 per plank) bought from bot stock
 *   -Dbots.fletch.own      Fletching: a woodcutter with a knife fletches a third of a full pack of logs into arrow shafts
 *                          or unstrung bows (the engine's own CraftItemWithLogScript) before it banks
 *   -Dbots.thieve.ardy     Thieving: Master farmer (38) and Knight of Ardougne (55) pickpocket jobs for smart bots
 */
final class W7cSkills {
    private W7cSkills() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------ Construction: bots.plankTypes
    static final boolean PLANK_TYPES = flag("bots.plankTypes");
    static final int PLAIN_LOG = 1511, PLAIN_PLANK = 960;
    /** The plain plank run in SmartAdventurers.MILL's shape {log, plank, sawmill fee}: the engine's sawmill takes 100 gp a plank. */
    static final int[] PLAIN_ROW = {PLAIN_LOG, PLAIN_PLANK, 100};
    /** {plank, Construction level of its first furniture, XP per plank x 10}: plank 29, oak 60, teak 90, mahogany 140 (the engine's per-plank build XP). */
    static final int[][] PLANKS = {{960, 1, 290}, {8778, 15, 600}, {8780, 35, 900}, {8782, 50, 1400}};
    /** Nails the smiths make (artisan-recipes.json, 15 a bar): bronze, iron, steel, mithril, adamantite, rune. */
    static final int[] NAILS = {4819, 4820, 1539, 4822, 4823, 4824};
    /** Nails per plain plank (crude wooden chair: 2 planks, 2 nails). Oak and better furniture takes no nails. */
    static final int NAILS_PER = clamp(Integer.getInteger("bots.plankTypes.nails", 1), 1, 3);
    /** The fewest planks worth a walk to the sawmill (the old run's own floor for logs in the bank). */
    static final int PLANK_MIN = 10, PLANK_PACK = 27;
    static volatile int plainRuns, nailsBought, nailsUsed, noNails, plankLevelSkips, typedBuilds;
    static volatile int geTrips; // w22: walks to the Exchange for a plain plank run

    static int[] plankRow(int plank) { for (int[] p : PLANKS) if (p[0] == plank) return p; return null; }

    /** Construction level a plank type needs; an unknown plank needs nothing new. */
    static int plankLevel(int plank) { int[] p = plankRow(plank); return p == null ? 1 : p[1]; }

    static final int OAK_PLANK = 8778;
    /**
     * May this builder use that plank? Flag off: always (train-61 has no level rule). Oak is never refused: a builder under
     * 15 tries plain planks first (plainFirst), and when that run cannot be made (no nails for sale, away from the GE, no
     * logs) it keeps the old oak run, so nobody is left without a plank run because of nails.
     */
    static boolean plankOk(boolean on, int plank, int construction) { return !on || plank == OAK_PLANK || construction >= plankLevel(plank); }
    /** A builder under the oak level tries the plain plank run (with nails) before anything else. */
    static boolean plainFirst(boolean on, int construction) { return on && construction < plankLevel(OAK_PLANK); }
    static boolean plankOk(int plank, int construction) { boolean ok = plankOk(PLANK_TYPES, plank, construction); if (!ok) plankLevelSkips++; return ok; }

    /** XP per plank built: 30.0 with the flag off (train-61's flat rate), the type's own rate with it on. */
    static double plankXp(boolean on, int plank) {
        if (!on) return 30.0;
        int[] p = plankRow(plank);
        return p == null ? 30.0 : p[2] / 10.0;
    }

    /** Nails one plank of this type takes when it is built. */
    static int nailsPer(boolean on, int plank) { return on && plank == PLAIN_PLANK ? NAILS_PER : 0; }

    /** Planks that can be built with the nails held (all of them for a plank that takes none). */
    static int buildable(int planks, int nailsHeld, int per) { return per <= 0 ? Math.max(0, planks) : Math.max(0, Math.min(planks, nailsHeld / per)); }

    /** The cheapest nail type with at least `need` in stock; -1 when none. stock/price are per NAILS index. */
    static int nailPick(int[] stock, int[] price, int need) {
        int best = -1;
        for (int i = 0; i < stock.length && i < price.length; i++) {
            if (need <= 0 || stock[i] < need || price[i] <= 0) continue;
            if (best < 0 || price[i] < price[best]) best = i;
        }
        return best;
    }

    /**
     * The plain plank run, decided before anything is bought: {logs to take to the mill, logs to buy, nail type index or -1,
     * nails to buy}; null = no run. Logs: 10+ in the bank, else 27 from the Exchange (at the GE, 27+ in stock). Nails: the
     * bank's first, the rest of one type from bot stock (at the GE). The purse must cover the logs, the nails, the sawmill
     * fee and keep `keep` gp over.
     */
    static int[] plainPlan(int bankLogs, int bankNails, boolean atGE, int logStock, int logPrice, int[] nailStock, int[] nailPrice, long coins, long keep) {
        int logs, buyLogs = 0;
        if (bankLogs >= PLANK_MIN) logs = Math.min(PLANK_PACK, bankLogs);
        else if (atGE && logStock >= PLANK_PACK && logPrice > 0) { logs = PLANK_PACK; buyLogs = PLANK_PACK; }
        else return null;
        int needNails = Math.max(0, logs * NAILS_PER - Math.max(0, bankNails)), type = -1;
        if (needNails > 0) {
            type = atGE ? nailPick(nailStock, nailPrice, needNails) : -1;
            if (type < 0) { // not enough of any one type for sale: build what the bank's own nails cover, if that is still a run
                logs = Math.min(logs, Math.max(0, bankNails) / NAILS_PER); needNails = 0;
                if (logs < PLANK_MIN) return null;
                if (buyLogs > 0) buyLogs = logs;
            }
        }
        long cost = (long) buyLogs * logPrice + (type >= 0 ? (long) needNails * nailPrice[type] : 0) + (long) logs * PLAIN_ROW[2];
        if (coins < cost + keep) return null;
        return new int[]{logs, buyLogs, type, needNails};
    }

    /**
     * w22: the smiths' stock target for the cheap nails (bronze, iron, steel). Nobody bought nails before this flag, so
     * Artisans.target kept a shelf of 10 (one bar = 15), and nailPick needs a whole pack (27) of one type: with the shelf
     * full the plain plank run could never buy its nails. Flag on: two packs. Flag off, and every other item: base.
     */
    static final int NAIL_TARGET = 2 * PLANK_PACK * NAILS_PER;
    static int nailTarget(boolean on, int id, int base) { return on && (id == NAILS[0] || id == NAILS[1] || id == NAILS[2]) ? Math.max(base, NAIL_TARGET) : base; }

    /**
     * w22: is a walk to the Exchange worth it for a plain plank run? Only when the run cannot be made where the bot stands
     * (away from the Exchange) and could be made there with the stock and purse as they are now.
     */
    static boolean tripWorth(int bankLogs, int bankNails, int logStock, int logPrice, int[] nailStock, int[] nailPrice, long coins, long keep) {
        return plainPlan(bankLogs, bankNails, false, logStock, logPrice, nailStock, nailPrice, coins, keep) == null
                && plainPlan(bankLogs, bankNails, true, logStock, logPrice, nailStock, nailPrice, coins, keep) != null;
    }

    /** The tail of the "built with" line with the flag on. */
    static String builtNote(int plank, int built, int nails, int level) {
        return " (" + String.format(java.util.Locale.ROOT, "%.0f", built * plankXp(true, plank)) + " xp, Construction " + level + (nails > 0 ? ", " + nails + " nails" : "") + ")";
    }

    // ------------------------------------------------------------------ Fletching: bots.fletch.own
    static final boolean FLETCH_OWN = flag("bots.fletch.own");
    static final int KNIFE = 946;
    /** Share of a full pack's logs that is fletched (percent); the rest is banked as logs. */
    static final int FLETCH_SHARE = clamp(Integer.getInteger("bots.fletch.own.share", 34), 5, 100);
    /** Above this many on the Exchange (x15 for arrow shafts) a product is left alone: the logs are worth more as logs. */
    static final int FLETCH_MAX_STOCK = Math.max(0, Integer.getInteger("bots.fletch.own.maxStock", 300));
    /** {log, product, Fletching level, made per log}; the name is the engine's LogCraftInfo constant. Stocks and ogre goods are left out. */
    static final int[][] FLETCH = {
            {1511, 52, 1, 15}, {1511, 50, 5, 1}, {1511, 48, 10, 1},
            {1521, 54, 20, 1}, {1521, 56, 25, 1},
            {1519, 60, 35, 1}, {1519, 58, 40, 1},
            {1517, 64, 50, 1}, {1517, 62, 55, 1},
            {1515, 68, 65, 1}, {1515, 66, 70, 1},
            {1513, 72, 80, 1}, {1513, 70, 85, 1}};
    static final String[] FLETCH_INFO = {"ARROW_SHAFT", "SHORT_BOW", "LONG_BOW", "OAK_SHORTBOW", "OAK_LONGBOW", "WILLOW_SHORTBOW", "WILLOW_LONGBOW",
            "MAPLE_SHORTBOW", "MAPLE_LONGBOW", "YEW_SHORTBOW", "YEW_LONGBOW", "MAGIC_SHORTBOW", "MAGIC_LONGBOW"};
    static final int[] FLETCH_LOGS = {1513, 1515, 1517, 1519, 1521, 1511};
    static volatile int fletchRuns, fletchLogs, fletchMade, fletchNoPick, fletchKnives;

    /** W32 (-Dbots.fletch.ownNormal, default off; needs bots.fletch.own): a chopper whose Fletching is under every row of its job's log
     *  (oak 20, willow 35, maple 50, yew 65, magic 80) cuts normal logs instead, 1 such job in OWN_EVERY, so its knife has work. */
    static final boolean OWN_NORMAL = flag("bots.fletch.ownNormal");
    static final int OWN_EVERY = clamp(Integer.getInteger("bots.fletch.ownNormal.every", 4), 1, 100);
    static volatile int ownNormalSwaps;
    /** w32 review: the swap only when a normal-tree spot the bot may enter is this near (tiles); a far chopper keeps its job. */
    static final int OWN_REACH = clamp(Integer.getInteger("bots.fletch.ownNormal.reach", 120), 1, 2000);
    static boolean ownNear(double nearestSpot, int reach) { return nearestSpot >= 0 && nearestSpot <= reach; }
    /** The lowest Fletching level that makes anything from this log; 0 = no row (never swapped). */
    static int fletchNeed(int log) { int m = 0; for (int[] f : FLETCH) if (f[0] == log && (m == 0 || f[2] < m)) m = f[2]; return m; }
    /** The swap rule: flag on, a log other than normal with a row, the level under it, and this is job 1, 1+every, 1+2*every, ... (nth counts
     *  the bot's own too-low tree jobs from 1; no RNG). */
    static boolean ownNormal(boolean on, int log, int fletchLevel, int nth, int every) {
        int need = fletchNeed(log);
        return on && log != 1511 && need > 0 && fletchLevel < need && nth >= 1 && (nth - 1) % Math.max(1, every) == 0;
    }

    /** Logs to fletch out of a pack's worth: the share, at least 1, never more than a pack; 0 under 3 logs. */
    static int fletchCount(int logs, int sharePct) { return logs < 3 ? 0 : clamp(logs * sharePct / 100, 1, 27); }

    /**
     * What to fletch from this log: the row (index into FLETCH) the level allows whose product is scarcest on the Exchange
     * (stock per log's worth; a tie goes to the higher level, which is more XP), skipping products over the stock cap.
     * -1 = bank the logs as they are.
     */
    static int fletchPick(int log, int level, IntUnaryOperator stock, int maxStock) {
        int best = -1; double bestScarce = 0;
        for (int i = 0; i < FLETCH.length; i++) {
            int[] f = FLETCH[i];
            if (f[0] != log || level < f[2]) continue;
            double perLog = stock.applyAsInt(f[1]) / (double) f[3];
            if (perLog >= maxStock) continue;
            if (best < 0 || perLog < bestScarce || perLog == bestScarce && f[2] > FLETCH[best][2]) { best = i; bestScarce = perLog; }
        }
        return best;
    }

    // ------------------------------------------------------------------ Thieving: bots.thieve.ardy
    static final boolean THIEVE_ARDY = flag("bots.thieve.ardy");
    static final String KEY_MASTER = "pp_master", KEY_KNIGHT = "pp_knight";
    static final int MASTER_LEVEL = 38, KNIGHT_LEVEL = 55, KNIGHT_COINS = 50;
    /** Bots on one of the two jobs at once (each job has its own seats); the cap is 4, never more. */
    static final int ARDY_SEATS = clamp(Integer.getInteger("bots.thieve.ardy.seats", 3), 1, 4);
    /** Combat level before a bot picks these pockets: a failed try stuns and hits for 3. */
    static final int ARDY_MIN_COMBAT = clamp(Integer.getInteger("bots.thieve.ardy.minCombat", 20), 3, 126);
    /** Job starts an hour, per job, for the whole server: the cap on coins and seeds that come from nothing (as they do for players). */
    static final int ARDY_HOUR = clamp(Integer.getInteger("bots.thieve.ardy.perHour", 6), 0, 60);
    /** A trip: this many knight pockets (50 gp each), or this many seeds from a master farmer. */
    static final int KNIGHT_PICKS = clamp(Integer.getInteger("bots.thieve.ardy.picks", 30), 1, 100), MASTER_SEEDS = clamp(Integer.getInteger("bots.thieve.ardy.seeds", 40), 5, 200);
    /** The job weight floor (the size of bots.jobShare's log floor): coins and most seeds carry no bounty, so the jobs would never be picked. */
    static final double ARDY_WEIGHT = Math.max(0, Double.parseDouble(System.getProperty("bots.thieve.ardy.weight", "0.6")));
    /** The engine's Master Farmer table (Pickpockets.MASTER_FARMER): allotment, hop, flower, bush and herb seeds, cactus, belladonna, mushroom. */
    static final int[] MASTER_LOOT = {5318, 5319, 5324, 5322, 5320, 5323, 5321, 5305, 5307, 5308, 5306, 5309, 5310, 5311, 5096, 5098, 5097, 5099, 5100,
            5101, 5102, 5103, 5104, 5105, 5106, 5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304, 5280, 5281, 5282};
    /** Herb seeds, guam to torstol: what Farming's herb patch takes from a bank or the Exchange. */
    static final int HERB_SEED_LO = 5291, HERB_SEED_HI = 5304;
    static volatile int marketFullStalls, masterStarts, knightStarts, ardyFull, ardyHourCap, ardyLowCombat;
    static final long[] ardyHourAt = new long[2];
    static final int[] ardyHourN = new int[2];

    static boolean ardyKey(String key) { return KEY_MASTER.equals(key) || KEY_KNIGHT.equals(key); }
    static boolean herbSeed(int id) { return id >= HERB_SEED_LO && id <= HERB_SEED_HI; }

    /**
     * AfkTasks.Task products for the two jobs: null with the flag off, which keeps them out of the bots' JOBS table and
     * the market's catalogue (the same switch W5b uses for the stalls). Every other task: unchanged.
     */
    static int[] ardyProducts(boolean on, String key, int[] products) { return ardyKey(key) && !on ? null : products; }

    /** Thieving level of a job row: the engine's 38 / 55 for the two jobs, the caller's value for every other task. */
    static int ardyLevel(String key, int other) { return KEY_MASTER.equals(key) ? MASTER_LEVEL : KEY_KNIGHT.equals(key) ? KNIGHT_LEVEL : other; }

    /** The trip's stop count (what AfkTasks counts: coins for a knight, seeds for a master farmer); 0 for any other task. */
    static int ardyTrip(String key) { return KEY_KNIGHT.equals(key) ? KNIGHT_PICKS * KNIGHT_COINS : KEY_MASTER.equals(key) ? MASTER_SEEDS : 0; }

    /** A seat is free while fewer than the cap of bots are on that job. */
    static boolean ardySeat(int botsOnJob, int seats) { return botsOnJob < Math.min(4, seats); }

    static int ardyIdx(String key) { return KEY_KNIGHT.equals(key) ? 1 : 0; }
    /** Starts of that job in the running hour. */
    static synchronized int ardyHourUsed(String key, long now) { int i = ardyIdx(key); if (now - ardyHourAt[i] > 3_600_000L) { ardyHourAt[i] = now; ardyHourN[i] = 0; } return ardyHourN[i]; }
    /** A job start: counted against the hour and in the hourly line. */
    static synchronized void ardyStart(String key, long now) { ardyHourUsed(key, now); ardyHourN[ardyIdx(key)]++; if (KEY_KNIGHT.equals(key)) knightStarts++; else masterStarts++; }

    /** Bots in the Ardougne market at once, knights and the W5b stall thieves (bots.thieve.stalls) together. */
    static final int MARKET_CAP = 4, KNIGHTS_ALONE = 3;
    /**
     * The shared Ardougne market cap: with the stalls flag on, knights + stall bots together at most 4; with it off (no
     * bot on a stall) knights alone at most 3. True = no room for one more of either.
     */
    static boolean marketFull(int knights, int stallBots, boolean stallsOn) { return stallsOn ? knights + stallBots >= MARKET_CAP : knights >= KNIGHTS_ALONE; }

    /** The pure gate: 0 free, 1 combat too low, 2 seats full (a knight: or the market), 3 the hour's starts are used up. */
    static int ardyGate(int combat, int minCombat, int botsOnJob, int seats, int hourUsed, int perHour) { return ardyGate(false, combat, minCombat, botsOnJob, seats, 0, false, hourUsed, perHour); }
    static int ardyGate(boolean knight, int combat, int minCombat, int botsOnJob, int seats, int stallBots, boolean stallsOn, int hourUsed, int perHour) {
        if (combat < minCombat) return 1;
        if (!ardySeat(botsOnJob, seats)) return 2;
        if (knight && marketFull(botsOnJob, stallBots, stallsOn)) return 2;
        if (hourUsed >= perHour) return 3;
        return 0;
    }
    /** May this bot not take the job now? count = add the refusal to the hourly line (the job pick only, not crews and errands). */
    static boolean ardyBlocked(String key, int combat, int botsOnJob, int stallBots, boolean stallsOn, long now, boolean count) {
        int g = ardyGate(KEY_KNIGHT.equals(key), combat, ARDY_MIN_COMBAT, botsOnJob, ARDY_SEATS, stallBots, stallsOn, ardyHourUsed(key, now), ARDY_HOUR);
        if (count) { if (g == 1) ardyLowCombat++; else if (g == 2) ardyFull++; else if (g == 3) ardyHourCap++; }
        return g != 0;
    }

    // ------------------------------------------------------------------ the hourly counter line
    static boolean anyOn() { return PLANK_TYPES || FLETCH_OWN || THIEVE_ARDY; }
    static String flags() {
        StringBuilder b = new StringBuilder();
        if (PLANK_TYPES) b.append("plankTypes "); if (FLETCH_OWN) b.append("fletchOwn "); if (THIEVE_ARDY) b.append("thieveArdy ");
        return b.toString().trim();
    }
    static String line() {
        return "[W7c] on=[" + flags() + "] typedBuilds=" + typedBuilds + " plainRuns=" + plainRuns + " nailsBought=" + nailsBought + " nailsUsed=" + nailsUsed + " noNails=" + noNails
                + " plankLevelSkips=" + plankLevelSkips + (PLANK_TYPES ? " geTrips=" + geTrips : "") + " fletchRuns=" + fletchRuns + " fletchLogs=" + fletchLogs + " fletchMade=" + fletchMade + " fletchNoPick=" + fletchNoPick
                + " fletchKnives=" + fletchKnives + (FLETCH_OWN && OWN_NORMAL ? " ownNormalSwaps=" + ownNormalSwaps : "") + " masterStarts=" + masterStarts + " knightStarts=" + knightStarts + " ardyFull=" + ardyFull + " ardyHourCap=" + ardyHourCap
                + " ardyLowCombat=" + ardyLowCombat + " marketFullStalls=" + marketFullStalls;
    }
}
