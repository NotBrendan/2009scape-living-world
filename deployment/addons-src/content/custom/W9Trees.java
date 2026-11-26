package content.custom;

/**
 * W9 lane I (the design notes): bot tree and fruit-tree runs, wired on W8Polish's tree rules.
 * Off by default: -Dbots.farming.trees.run=true, and it needs -Dbots.farming and -Dbots.farming.trees too (W8Polish.TREES).
 * -Dbots.farming.trees alone stays rules only (night pairs pass it): nothing below is called unless RUN is true.
 *
 * At a trip end by a bank (Brain.planFarmRun, before the allotment chooser) a bot with Farming 15+ (oak) or 27+ (apple) takes a
 * sapling to the nearest enabled site: its own sapling, else the seed (W8Polish.seedSource: owned, else bot stock through
 * Ledger.consume, never a shop) grown in a plant pot (the pot's growing time is skipped; planting gives the pot back), rakes,
 * plants, pays the farmer (W8Polish.payFarmer, from its own items or bot stock under the run's budget), and comes back when
 * W8Polish.checkDue says: check-health, then a tree is dug out (and a new sapling planted when the bot brought one), a fruit tree is
 * picked and left standing (back when the fruit has grown again). A planting is made only when the check fits: inside
 * FarmSave's file age with -Dbots.farming.trees.save (W8Polish.saveFits), else before the shift ends.
 *
 * Pure part only (flags, the site table, the rules, the counters), so plain java can test it without a world (TestW9Trees).
 * Nothing here touches a server class or class-inits Farming / FarmSave.
 */
final class W9Trees {
    private W9Trees() { }

    static final boolean RUN = W8Polish.TREES && Boolean.getBoolean("bots.farming.trees.run");
    /** Spend cap for one tree run (seed from bot stock, plant pot, payment items): a tenth of the coins, never above this. */
    static final long SPEND_CAP = Math.max(0L, Long.getLong("bots.farming.trees.run.cap", 5000L));
    /** A planting site at most this far (straight line) from the bank the trip ended at; a CHECK starts from any bank within CHECK_R
     *  (Farming.OVERDUE_R, 240: Varrock east bank to Falador). Never across White Wolf Mountain (x 2880, as farm47). */
    static final int PLANT_R = 120, CHECK_R = 240, MOUNTAIN_X = 2880;
    static final long REFUSE_MS = 10 * 60_000L, RETRY_MS = 30 * 60_000L, RUN_GAP_MS = 60 * 60_000L;
    /** A grown fruit tree grows a fruit every 40 min (engine Patch.getStageGrowthMinutes), six at most. */
    static final long FRUIT_REGROW_MS = 6 * 40 * 60_000L;

    static final int PLANT_POT = 5350, PLANT_POT_FILLED = 5354;
    static final int TREE = 0, FRUIT = 1;

    // ---- sites ----
    // Patch tiles: the patch wrapper scenery read from the game cache with the server's own region loader (11-20, read only):
    // RegionManager.getObject at each farmer's spawn +-10, FarmingPatch.forObject / def varbit. Anchors: the patch farmer's spawn
    // (data/configs/npc_spawns.json, Farmers.kt ids). Banks: the AfkTasks.BANKS tile nearest the patch (the walk back).
    /** Site keys (what a FarmSave file stores; never one of Farming.SITES' keys). */
    static final String[] KEYS = {"TREE_FALADOR", "TREE_TAVERLEY", "TREE_VARROCK", "TREE_LUMBRIDGE", "FRUIT_CATHERBY",
            "TREE_GNOME_STRONGHOLD", "FRUIT_GNOME_STRONGHOLD", "FRUIT_GNOME_VILLAGE", "FRUIT_BRIMHAVEN"};
    /** The engine's FarmingPatch constant of each site (by name, so this class never loads the enum). */
    static final String[] PATCHES = {"N_FALADOR_TREE", "TAVERLY_TREE", "VARROCK_TREE", "LUMBRIDGE_TREE", "CATHERBY_FRUIT_TREE",
            "GNOME_STRONGHOLD_TREE", "GNOME_STRONGHOLD_FRUIT_TREE", "TREE_GNOME_VILLAGE_FRUIT_TREE", "BRIMHAVEN_FRUIT_TREE"};
    static final String[] NAMES = {"falador park", "taverley", "varrock castle", "lumbridge", "catherby",
            "gnome stronghold", "gnome stronghold", "tree gnome village", "brimhaven"};
    /** {patch x, y, z, wrapper object id, size, kind, anchor x, y, bank x, y, enabled}. Off: the walker cannot get there (see the note). */
    static final int[][] SITES = {
            {3003, 3372, 0, 8389, 3, TREE, 3007, 3373, 3012, 3356, 1},   // Heskel 2340; Falador east bank
            {2935, 3437, 0, 8388, 3, TREE, 2934, 3438, 2946, 3370, 1},   // Alain 2339; Falador west bank (summon runs walk to Taverley)
            {3228, 3458, 0, 8390, 3, TREE, 3229, 3456, 3253, 3421, 1},   // Treznor 2341; Varrock east bank
            {3192, 3230, 0, 8391, 3, TREE, 3196, 3231, 3092, 3245, 1},   // Fayeth 2342; Draynor bank (Lumbridge's is upstairs)
            {2860, 3433, 0, 7965, 2, FRUIT, 2860, 3430, 2809, 3441, 1},  // Ellena 2331; Catherby bank
            {2435, 3414, 0, 19147, 3, TREE, 2435, 3412, 2586, 3420, 0},  // Prissy Scilla 1037; no AfkTasks bank in the Stronghold
            {2475, 3445, 0, 7962, 2, FRUIT, 2473, 3446, 2586, 3420, 0},  // Bolongo 2343; same
            {2489, 3179, 0, 7963, 2, FRUIT, 2490, 3183, 2612, 3092, 0},  // Gileth 2344; behind the village maze
            {2764, 3212, 0, 7964, 2, FRUIT, 2766, 3211, 2654, 3284, 0}}; // Garth 2330; Karamja (a boat)
    static final int SX = 0, SY = 1, SZ = 2, SOBJ = 3, SSIZE = 4, SKIND = 5, SAX = 6, SAY = 7, SBX = 8, SBY = 9, SON = 10;

    /** A site's index by its key, -1 if unknown (the FarmSave allow-list with the flag on). */
    static int siteIndex(String key) { if (key != null) for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i; return -1; }
    /** A site's index by its engine FarmingPatch name, -1 if none. */
    static int siteOfPatch(String patch) { if (patch != null) for (int i = 0; i < PATCHES.length; i++) if (PATCHES[i].equals(patch)) return i; return -1; }
    static boolean fruit(int site) { return SITES[site][SKIND] == FRUIT; }
    static int[][] rows(int site) { return fruit(site) ? W8Polish.FRUIT_ROWS : W8Polish.TREE_ROWS; }

    static double dist(int site, int x, int y) { double dx = SITES[site][SX] - x, dy = SITES[site][SY] - y; return Math.sqrt(dx * dx + dy * dy); }
    /** Same plane, same side of White Wolf Mountain, within r of the patch. */
    static boolean inRange(int site, int x, int y, int z, int r) {
        int[] s = SITES[site];
        return s[SZ] == z && (s[SX] < MOUNTAIN_X) == (x < MOUNTAIN_X) && dist(site, x, y) <= r;
    }
    /** The nearest enabled site in range whose kind the bot can plant (treeOk: a tree row is sourced, fruitOk: a fruit row); -1 none. */
    static int chooseSite(int x, int y, int z, boolean treeOk, boolean fruitOk, int r) {
        int best = -1; double bd = Double.MAX_VALUE;
        for (int i = 0; i < SITES.length; i++) {
            if (SITES[i][SON] == 0 || !(fruit(i) ? fruitOk : treeOk) || !inRange(i, x, y, z, r)) continue;
            double d = dist(i, x, y);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    // ---- saplings, money, time ----
    /** The row of a seed in a W8Polish table, -1 if none. */
    static int rowOfSeed(int[][] rows, int seed) { for (int i = 0; i < rows.length; i++) if (rows[i][1] == seed) return i; return -1; }
    /** Where the sapling comes from: 3 = an owned sapling, else W8Polish.seedSource (1 owned seed, 2 bot stock, 0 none); a stock seed
     *  only when its price fits the budget. Growing needs a plant pot: owned, or bought at the shop price under the budget. */
    static int saplingSource(int ownedSapling, int ownedSeed, int stockSeed, long seedPrice, boolean potOwned, long potPrice, long left) {
        if (ownedSapling > 0) return 3;
        int src = W8Polish.seedSource(ownedSeed, stockSeed);
        if (src == 0 || !potOwned && potPrice <= 0) return 0; // a pot with no shop price is never bought (no free pot)
        long cost = (src == 2 ? Math.max(0L, seedPrice) : 0L) + (potOwned ? 0L : Math.max(0L, potPrice));
        return cost <= left ? src : 0;
    }
    /** Row of a planted sapling in its table (by item id), -1 if none. */
    static int rowOfSapling(int[][] rows, int sapling) { for (int i = 0; i < rows.length; i++) if (rows[i][2] == sapling) return i; return -1; }
    /** The budget of one tree run: a tenth of the coins, at most SPEND_CAP. */
    static long budget(long coins) { return Math.max(0L, Math.min(coins / 10, SPEND_CAP)); }
    /** Pay the farmer: W8Polish.payFarmer, and the part not owned must fit the budget left. */
    static boolean payOk(int[] row, int owned, int stock, long unitPrice, long left) {
        if (!W8Polish.payFarmer(row, owned, stock)) return false;
        int need = Math.max(0, row[5] - owned);
        return need == 0 || (long) need * Math.max(0L, unitPrice) <= left;
    }
    /** A planting is made only when its check can be kept: inside FarmSave's file age with the save on (W8Polish.saveFits), else before the shift ends. */
    static boolean plantOk(boolean saveOn, long now, long due, long shiftEnd) { return saveOn ? W8Polish.saveFits(now, due) : due > now && due < shiftEnd; }
    /** The next visit after a run: nothing planted 0; planted this run W8Polish.checkDue; still growing its stages left (+ the margin);
     *  a grown fruit tree when its fruit is back; anything else (a grown tree left standing) in RETRY_MS. */
    static long dueAfter(boolean planted, boolean plantedNow, boolean grown, boolean fruitKind, int growMin, int stagesLeft, int stageMin, long now) {
        if (!planted) return 0;
        if (plantedNow) return W8Polish.checkDue(now, growMin);
        if (!grown) return now + Math.max(1, stagesLeft) * Math.max(1, stageMin) * 60_000L + W8Polish.TREE_MARGIN_MS;
        return now + (fruitKind ? FRUIT_REGROW_MS : RETRY_MS);
    }

    // ---- products ----
    /** The fruit of FRUIT_ROWS (apple .. palm: engine Plantable.harvestItem), row for row. A grown tree gives no logs here: the engine's
     *  farmed-tree objects (oak 8467 .. magic 8409) carry varbit 2953, the Gnome Stronghold patch, so its woodcutting would change that
     *  patch, not the one chopped; the bot checks the tree's health and digs it out instead (see the note). */
    static final int[] FRUITS = {1955, 1963, 2108, 5970, 2114, 5972, 5974};
    static int[] products() { return FRUITS.clone(); }
    static int[] saplings() { int[] a = new int[W8Polish.TREE_ROWS.length + W8Polish.FRUIT_ROWS.length]; int i = 0; for (int[] r : W8Polish.TREE_ROWS) a[i++] = r[2]; for (int[] r : W8Polish.FRUIT_ROWS) a[i++] = r[2]; return a; }
    static int[] seeds() { int[] a = new int[W8Polish.TREE_ROWS.length + W8Polish.FRUIT_ROWS.length]; int i = 0; for (int[] r : W8Polish.TREE_ROWS) a[i++] = r[1]; for (int[] r : W8Polish.FRUIT_ROWS) a[i++] = r[1]; return a; }

    // ---- counters and lines ----
    static volatile long RUNS, CHECKS, PLANTED, PAID, CHECKED, FRUIT_PICKED, DIED, DUG, LOST, GROWN, SPENT, RESTORED, ABANDONED;
    static final int R_FAR = 0, R_LEVEL = 1, R_SAPLING = 2, R_FIT = 3, R_SLOTS = 4, R_CAP = 5, R_NO_SITE = 6, R_SPADE = 7;
    static final String[] REASONS = {"far", "noRow", "noSapling", "noFit", "slots", "fleetCap", "noSite", "noSpade"};
    static final long[] SKIPS = new long[REASONS.length];
    static synchronized boolean skip(int reason) { SKIPS[reason]++; return false; }

    /** Tree lines: at most LINE_CAP per 10 minutes (the rest are counted in cut=). */
    static final int LINE_CAP = Math.max(1, Integer.getInteger("bots.farming.trees.run.lines", 30));
    private static long lineAt; private static int lineN; static volatile long linesCut;
    static synchronized boolean lineOk(long now) {
        if (now - lineAt >= 10 * 60_000L) { lineAt = now; lineN = 0; }
        if (lineN < LINE_CAP) { lineN++; return true; }
        linesCut++; return false;
    }
    static void say(long now, String text) { if (lineOk(now)) System.out.println(text); }

    /** The tree part of the [Farm] tally line (Farming.totals: after the v1/v2 fields, before FarmSave's, which stay at the very end), only with
     *  RUN on. Keys are camel case with a "tree" prefix so no grep for a Farming or FarmSave key (planted=, restored=, ...) can hit them. */
    static synchronized String totals() {
        StringBuilder b = new StringBuilder(" trees=[treeRuns=").append(RUNS).append(" treeChecks=").append(CHECKS).append(" treePlanted=").append(PLANTED).append(" treePaid=").append(PAID)
                .append(" treeChecked=").append(CHECKED).append(" treeFruit=").append(FRUIT_PICKED).append(" treeDied=").append(DIED).append(" treeDug=").append(DUG)
                .append(" treeLost=").append(LOST).append(" treeGrown=").append(GROWN).append(" treeSpent=").append(SPENT).append(" treeRestored=").append(RESTORED)
                .append(" treeAbandoned=").append(ABANDONED).append(" treeSkip=");
        for (int i = 0; i < REASONS.length; i++) b.append(i == 0 ? "" : ",").append(REASONS[i]).append(':').append(SKIPS[i]);
        return b.append(" treeCut=").append(linesCut).append(']').toString();
    }
}
