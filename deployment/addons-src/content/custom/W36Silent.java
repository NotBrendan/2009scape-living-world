package content.custom;

/**
 * w36-silent2 (results/w36-silent2.md, 2026-11-22): four default-OFF switches for live switches that never fire on live.
 * Pure rules and counters only (no engine class at class init) for TestW36Silent. With every flag off nothing here is
 * called past a constant-false test: no line, no counter, no extra RNG draw.
 *
 *   -Dbots.farming.trees2fire  tree runs (bots.farming.trees.run) had no seed source on live (never a shop; the pairs seeded
 *                              bot stock) and, without trees.save, no check that fits a shift (the next-day rule is 20 h).
 *                              (a) every NEST_EVERY-th banked log haul of a bot woodcutter finds a bird's nest: one tree seed
 *                              goes into the finder's own bank for its own tree runs (review fix 2: never bot stock, so the
 *                              clerk can never buy it), while the finder owns under NEST_OWN_CAP of it. NEST_EVERY 30 is about
 *                              a player's rate (1 nest in 256 logs, a third of them one of these seeds). (b) with trees.save off the check is due when the tree is grown (+ the margin).
 *   -Dbots.mine.guild2         bots.mine.guild only moved a coal/mithril job that was already picked, and coal is never picked
 *                              (0 of 4,443 picks in 24 h: the market has enough). A Mining 60+ bot within GUILD2_R of the
 *                              ladder gets a floor weight for coal/mithril while the guild has a seat, MINE_PER_HOUR an hour.
 *   -Dbots.fish.guild2         the same for bots.fish.guild: lobster/harpoon jobs were picked 0 times in 24 h. A Fishing 68+
 *                              bot gets the floor weight for them while a guild seat is free, FISH_PER_HOUR an hour.
 *   -Dbots.wc.share2           a log job gets the floor weight while under WC_WANT_PCT % of the job picks since boot were
 *                              log jobs (live: 62 of 4,443). The woodcutting half of bots.jobShare, without its thieving damp.
 */
final class W36Silent {
    private W36Silent() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }

    static final boolean TREES2 = flag("bots.farming.trees2fire");
    static final boolean MINE2 = flag("bots.mine.guild2");
    static final boolean FISH2 = flag("bots.fish.guild2");
    static final boolean WC2 = flag("bots.wc.share2");

    /** The floor weight: bots.jobShare's log floor and bots.thieve.ardy's weight (0.6). */
    static final double FLOOR = 0.6;

    // ------------------------------------------------------------------ 1 tree runs: nests and the in-shift check
    static final int NEST_EVERY = Math.max(1, Integer.getInteger("bots.farming.trees2fire.nestEvery", 30));
    static final int NEST_OWN_CAP = Math.max(1, Integer.getInteger("bots.farming.trees2fire.ownCap", 3));
    static final int ACORN = 5312, WILLOW_SEED = 5313, APPLE_SEED = 5283;
    static volatile int logHauls, nests, nestFull, shortDue;

    /** The seed in the nest of log haul number haulNo (1-based, counted over all bots): 0 = no nest. Of the nests every 5th
     *  holds an apple tree seed, every 3rd of the rest a willow seed, the others an acorn. */
    static int nestSeed(boolean on, int haulNo, int every) {
        if (!on || every < 1 || haulNo <= 0 || haulNo % every != 0) return 0;
        int n = haulNo / every;
        return n % 5 == 0 ? APPLE_SEED : n % 3 == 0 ? WILLOW_SEED : ACORN;
    }
    /** Count one banked log haul; the seed for the finder's bank, 0 none (no nest, or the finder owns that seed up to the cap). */
    static synchronized int nestFor(boolean on, java.util.function.IntUnaryOperator ownedOf) {
        if (!on) return 0;
        int seed = nestSeed(true, ++logHauls, NEST_EVERY);
        if (seed == 0) return 0;
        if (ownedOf.applyAsInt(seed) >= NEST_OWN_CAP) { nestFull++; return 0; }
        nests++;
        return seed;
    }
    static String seedName(int seed) { return seed == ACORN ? "an acorn" : seed == WILLOW_SEED ? "a willow seed" : seed == APPLE_SEED ? "an apple tree seed" : "a seed"; }

    /** The health check of a tree planted now. Off, or with trees.save on: W8Polish.checkDue (the next day). On with the save
     *  off: when the tree is grown plus the margin, so an oak (200 min) can be planted early in a long shift. */
    static long checkDue(boolean on, boolean saveOn, long now, int growMin) {
        return on && !saveOn ? now + Math.max(1, growMin) * 60_000L + W8Polish.TREE_MARGIN_MS : W8Polish.checkDue(now, growMin);
    }

    // ------------------------------------------------------------------ 2 + 3 guild floors
    static final int GUILD2_R = Math.max(1, Integer.getInteger("bots.mine.guild2.radius", 250));
    static final int MINE_PER_HOUR = Math.max(1, Integer.getInteger("bots.mine.guild2.perHour", 6));
    static final int FISH_PER_HOUR = Math.max(1, Integer.getInteger("bots.fish.guild2.perHour", 4));
    static volatile int mineFloored, mineStarts, fishFloored, fishStarts, fishOutside;
    /** {hour start ms, starts this hour}: mine, fish. */
    private static final long[] MINE_HOUR = new long[2], FISH_HOUR = new long[2];

    /** Starts left this hour (peek: nothing is taken). */
    static synchronized boolean hourOpen(long[] st, long now, int cap) {
        if (now - st[0] >= 3_600_000L) { st[0] = now; st[1] = 0; }
        return st[1] < cap;
    }
    /** Take one start of this hour's cap. */
    static synchronized void hourTake(long[] st, long now) {
        if (now - st[0] >= 3_600_000L) { st[0] = now; st[1] = 0; }
        st[1]++;
    }
    static boolean mineOpen(long now) { return hourOpen(MINE_HOUR, now, MINE_PER_HOUR); }
    static boolean fishOpen(long now) { return hourOpen(FISH_HOUR, now, FISH_PER_HOUR); }
    static void mineTaken(long now) { hourTake(MINE_HOUR, now); mineStarts++; }
    static void fishTaken(long now) { hourTake(FISH_HOUR, now); fishStarts++; }
    /** Review fix 1: does this start use up one of the hour's floors? A guild start always; a floored cage/harpoon job that did not
     *  get to the guild too (its cook-chain variant, an errand, a seat gone since the choice), or the hour would never fill. */
    static boolean fishCounts(boolean on, boolean guildStart, boolean floored, boolean cageOrHarpoon) { return on && (guildStart || floored && cageOrHarpoon); }

    /** chooseJob: a guild job the market does not want (weight under the floor) gets the floor for a bot the guild takes now. */
    static double guildWeight(boolean on, double weight, boolean guildJob, int level, int need, int seatsUsed, int seats, boolean hourOpen, boolean near) {
        return on && guildJob && weight < FLOOR && level >= need && seatsUsed < seats && hourOpen && near ? FLOOR : weight;
    }
    /** Brain.start: the Mining Guild for a served job whatever spot was picked, when the ladder is within the radius. */
    static boolean mineTakes(boolean on, int mining, boolean serves, double dLadder, int crowd, int cap) {
        return on && serves && mining >= MineGuild.LEVEL && dLadder <= GUILD2_R && crowd < cap;
    }

    // ------------------------------------------------------------------ 4 woodcutting share
    static final int WC_WANT_PCT = Math.max(1, Integer.getInteger("bots.wc.share2.wantPct", 8));
    static final int WC_MIN_PICKS = 25;
    static volatile int picks, treePicks, wcFloored;

    /** One started job (Brain.start). */
    static synchronized void picked(boolean treeJob) { picks++; if (treeJob) treePicks++; }
    /** Log jobs are under their share of the picks since boot (never before WC_MIN_PICKS picks: no rush at boot). */
    static boolean wcUnder(int treePicks, int picks, int wantPct) { return picks >= WC_MIN_PICKS && treePicks * 100L < (long) picks * wantPct; }
    static double wcWeight(boolean on, double weight, boolean treeJob, boolean under) { return on && treeJob && under && weight < FLOOR ? FLOOR : weight; }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return TREES2 || MINE2 || FISH2 || WC2; }
    static String line() {
        return "[W36] on=[" + ((TREES2 ? "trees2fire " : "") + (MINE2 ? "mineGuild2 " : "") + (FISH2 ? "fishGuild2 " : "") + (WC2 ? "wcShare2 " : "")).trim() + "]"
                + " logHauls=" + logHauls + " nests=" + nests + " nestFull=" + nestFull + " shortDue=" + shortDue
                + " mineFloored=" + mineFloored + " mineStarts=" + mineStarts + " fishFloored=" + fishFloored + " fishStarts=" + fishStarts + " fishOutside=" + fishOutside
                + " wcPicks=" + picks + " wcTreePicks=" + treePicks + " wcFloored=" + wcFloored;
    }
}
