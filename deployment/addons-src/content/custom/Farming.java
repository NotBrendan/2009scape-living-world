package content.custom;

import content.global.skill.farming.FarmingPatch;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * C8 (the design notes): bots plant and harvest allotments + herb patches at South Falador, Catherby and Ardougne.
 * Off by default: -Dbots.farming=true turns it on (-Dbots.farming.max=<n> caps bots holding crops at once, default 8).
 * With the flag off nothing here is called: SmartAdventurers guards its four hooks with ON and the Brain field `farm` stays 0.
 *
 * Crops are never saved: a bot plants only if the crop is due 30 min before its shift ends; a logout with crops in the ground
 * counts as abandoned (the restored bot starts with weedy patches, as the engine's varbits are per session for bots).
 * W2: with -Dbots.farming.trees.save=true FarmSave keeps the patches over a logout and a restart instead (off = the line above).
 * Pure helpers (seed pick, time rule, budget) live here so TestFarming can check them without a world.
 */
final class Farming {
    private Farming() { }

    static final boolean ON = Boolean.getBoolean("bots.farming");
    /** farm2 (the design notes): flower patch, bought compost, plant cure. Needs ON; off = v1 exactly. */
    static final boolean V2 = ON && Boolean.getBoolean("bots.farming.v2");
    /** farm fix1 (-Dbots.farming.fix1, default off): the 10-step budget per patch counts only steps that got nothing done. Live (f56/f56b pairs):
     *  a full pack makes one grown allotment 6 to 9 harvest batches (farm49 offload), each one step; with v2's compost step the seed was step 11 and
     *  the patch was left composted and empty ("too many steps"). */
    static final boolean FIX1 = ON && Boolean.getBoolean("bots.farming.fix1");
    static final int STEP_CAP = 10;
    /** The step count after an action was seen to succeed: with fix1 a step that did something is given back (a harvest only when crops came in). */
    static int stepsAfterOk(boolean fix1, int steps, boolean harvest, int got) { return fix1 && steps > 0 && (!harvest || got > 0) ? steps - 1 : steps; }
    /** Bots holding crops (or on a run) at once, all bots together. */
    static final int MAX = Math.max(1, Integer.getInteger("bots.farming.max", 8));
    /** Trial only: the next trip end by a bank plants at once (no 1-in-4 roll, no wait). */
    static final boolean TEST = Boolean.getBoolean("smartbots.testFarming");

    static final long MIN = 60_000L;
    static final long GROW_ALLOT = 45 * MIN, GROW_HERB = 85 * MIN, MARGIN = 30 * MIN;

    // tools
    static final int RAKE = 5341, DIBBER = 5343, SPADE = 952, CAN_FULL = 5340, CAN_EMPTY = 5331, COMPOST = 6032, WEEDS = 6055, BUCKET = 1925;
    static boolean isCan(int id) { return id >= 5333 && id <= 5340; }
    // farm2
    static final int SUPERCOMPOST = 6034, PLANT_CURE = 6036, MARIGOLD_SEED = 5096, ROSEMARY_SEED = 5097;
    /** Never pay more than this for one bucket of compost / one plant cure (guide price), whatever the budget. */
    static final long COMPOST_MAX = 60;

    /** The flower that protects an allotment crop from disease once it is fully grown (engine: Plantable.protectionFlower); -1 none. */
    static int flowerFor(int allotSeed) { return allotSeed == 5318 || allotSeed == 5319 || allotSeed == 5322 ? MARIGOLD_SEED : allotSeed == 5324 ? ROSEMARY_SEED : -1; }
    /** Farming level to plant a flower seed (marigold 2, rosemary 11); 999 for anything else. */
    static int flowerLevel(int flowerSeed) { return flowerSeed == MARIGOLD_SEED ? 2 : flowerSeed == ROSEMARY_SEED ? 11 : 999; }
    /** farm3: the flower seed to carry, -1 none. f52 planted no flower in 3 h: every bot was Farming 1 at prep and marigold needs 2. A run that
     *  harvests (a CHECK) may carry the seed one level short: the harvest xp comes before the flower patch is worked again (flowerStep). */
    static int flowerSeedToBuy(int level, int allotSeed, int flowerInGround, boolean harvestRun) {
        int fs = flowerFor(allotSeed);
        if (fs < 0 || flowerInGround > 0) return -1;
        return level >= flowerLevel(fs) || harvestRun && level + 1 >= flowerLevel(fs) ? fs : -1;
    }
    static final int FL_SKIP = 0, FL_PLANT = 1, FL_LATER = 2;
    /** farm3: what to do at the flower patch, decided before walking there: nothing without a seed in the pack or an empty patch; plant at the
     *  level; a level short, come back once after the other patches (their harvest may give the level), then give up. */
    static int flowerStep(int level, int flowerSeed, boolean seedHeld, boolean patchEmpty, boolean lastPass) {
        if (flowerSeed <= 0 || !seedHeld || !patchEmpty) return FL_SKIP;
        if (level >= flowerLevel(flowerSeed)) return FL_PLANT;
        return lastPass ? FL_SKIP : FL_LATER;
    }
    /** Allotment seed at a farm with a flower patch: the best crop the flower already in the ground protects; with no flower there, the best crop
     *  whose flower the level can plant (so 7-10 Farming grows onions under marigolds, not unprotected cabbages); else the v1 pick. */
    static int pickAllotV2(int level, int flowerInGround, IntPredicate avail) {
        int[][] t = ALLOT_SEEDS;
        for (int i = t.length - 1; i >= 0; i--) {
            int fl = flowerFor(t[i][1]);
            if (level >= t[i][0] && (flowerInGround > 0 ? fl == flowerInGround : level >= flowerLevel(fl)) && avail.test(t[i][1])) return t[i][1];
        }
        return pickSeed(level, false, avail);
    }
    /** Buckets wanted for this planting: one per allotment unless a grown flower already guards them, one per herb patch. The flower patch gets none. */
    static int compostNeed(int nAllot, boolean allotGuarded, int nHerb) { return Math.max(0, allotGuarded ? 0 : nAllot) + Math.max(0, nHerb); }
    /** Buckets to buy: the shortfall, as many as the rest of the budget covers; none above COMPOST_MAX each. */
    static int compostToBuy(int shortBy, long unit, long left) { return shortBy <= 0 || unit <= 0 || unit > COMPOST_MAX ? 0 : (int) Math.min(shortBy, Math.max(0, left) / unit); }
    /** farm3: a plant cure is never bought (the engine kills a diseased crop at the next growth tick, so f52 cured 0); one already owned is still carried. */
    static boolean takeCure(int inPack, int inBank, int freeSlots) { return inPack <= 0 && inBank > 0 && freeSlots > 3; }

    /** {level, seed, crop, shopStocked(1/0)} best last. */
    static final int[][] ALLOT_SEEDS = {{1, 5318, 1942, 1}, {5, 5319, 1957, 1}, {7, 5324, 1965, 1}, {12, 5322, 1982, 0}};
    static final int[][] HERB_SEEDS = W6Herbs.herbSeeds(W6Herbs.HERBS2, new int[][]{{9, 5291, 199, 0}, {14, 5292, 201, 0}, {19, 5293, 203, 0}, {26, 5294, 205, 0}, {32, 5295, 207, 0}}); // W6 (-Dbots.farming.herbs2): toadflax to torstol follow; off = these five rows
    static final int SEEDS_PER_ALLOT = 3, SEEDS_PER_HERB = 1;

    /** Shop stand-in allowed (tools and potato/onion/cabbage seeds); herb and tomato seeds come only from a bank or the Exchange. */
    static boolean shopStocked(int id) {
        if (id == RAKE || id == DIBBER || id == SPADE || id == CAN_EMPTY || id == CAN_FULL) return true;
        if (V2 && (id == COMPOST || id == PLANT_CURE || id == MARIGOLD_SEED || id == ROSEMARY_SEED)) return true;
        for (int[] r : ALLOT_SEEDS) if (r[1] == id) return r[3] == 1;
        return false;
    }

    /** Best seed the level allows that can be sourced (avail.test(seed)); -1 when none. Herb below 9 Farming: none. */
    static int pickSeed(int level, boolean herb, IntPredicate avail) {
        int[][] t = herb ? HERB_SEEDS : ALLOT_SEEDS;
        for (int i = t.length - 1; i >= 0; i--) if (level >= t[i][0] && avail.test(t[i][1])) return t[i][1];
        return -1;
    }

    static boolean isSeed(int id) { for (int[] r : ALLOT_SEEDS) if (r[1] == id) return true; for (int[] r : HERB_SEEDS) if (r[1] == id) return true; return false; }
    static boolean isHerbSeed(int id) { for (int[] r : HERB_SEEDS) if (r[1] == id) return true; return false; }
    static boolean isCrop(int id) { for (int[] r : ALLOT_SEEDS) if (r[2] == id) return true; for (int[] r : HERB_SEEDS) if (r[2] == id) return true; return false; }
    static int[] allCrops() { int[] a = new int[ALLOT_SEEDS.length + HERB_SEEDS.length]; int i = 0; for (int[] r : ALLOT_SEEDS) a[i++] = r[2]; for (int[] r : HERB_SEEDS) a[i++] = r[2]; return a; }
    static int[] allSeeds() { int[] a = new int[ALLOT_SEEDS.length + HERB_SEEDS.length]; int i = 0; for (int[] r : ALLOT_SEEDS) a[i++] = r[1]; for (int[] r : HERB_SEEDS) a[i++] = r[1]; return a; }

    /** Section 3 time rule: 2 = herb + allotments fit, 1 = allotments only, 0 = nothing fits before the shift ends. */
    static int fit(long now, long shiftEnd) {
        if (now + GROW_HERB + MARGIN < shiftEnd) return 2;
        if (now + GROW_ALLOT + MARGIN < shiftEnd) return 1;
        return 0;
    }
    static long growMs(boolean herb) { return herb ? GROW_HERB : GROW_ALLOT; }

    /** Spend cap for one prep: 5% of the bot's coins and never more than 600 gp. */
    static long budget(long coins) { return Math.max(0, Math.min(coins, Math.min(600L, Math.max(150L, coins / 20)))); } // farm49: 150 gp floor (never more than owned) so a 47 gp first run fits

    // ---- sites ----
    static final class Site {
        final String key; final Location anchor, bank; final FarmingPatch[] patches;
        Site(String key, Location anchor, Location bank, FarmingPatch... patches) { this.key = key; this.anchor = anchor; this.bank = bank; this.patches = patches; }
    }
    // Anchors: the patch farmer's spawn (data/configs/npc_spawns.json: Elstan 2323 at 3056,3306; Dantaera 2324 at 2807,3464;
    // Kragen 2325 at 2664,3374); the farms sit in regions 12083 / 10548 (FarmingPatchZone). Banks: the AfkTasks.BANKS tiles
    // (atBank() is true there). The patches are found by scan (find), never by a hard-coded tile.
    /** farm2: with V2 the site's flower patch goes first in the work order (so the protecting flower is in before the allotments). */
    private static FarmingPatch[] pv(FarmingPatch flower, FarmingPatch... v1) {
        if (!V2) return v1;
        FarmingPatch[] a = new FarmingPatch[v1.length + 1]; a[0] = flower; System.arraycopy(v1, 0, a, 1, v1.length); return a;
    }
    static final Site[] SITES = {
            new Site("FALADOR", Location.create(3056, 3307, 0), Location.create(3092, 3245, 0),
                    pv(FarmingPatch.S_FALADOR_FLOWER_C, FarmingPatch.S_FALADOR_ALLOTMENT_NW, FarmingPatch.S_FALADOR_ALLOTMENT_SE, FarmingPatch.S_FALADOR_HERB_NE)),
            new Site("CATHERBY", Location.create(2809, 3463, 0), Location.create(2809, 3441, 0),
                    pv(FarmingPatch.CATHERBY_FLOWER_C, FarmingPatch.CATHERBY_ALLOTMENT_N, FarmingPatch.CATHERBY_ALLOTMENT_S, FarmingPatch.CATHERBY_HERB_CE)),
            new Site("ARDOUGNE", Location.create(2666, 3374, 0), Location.create(2617, 3332, 0),
                    pv(FarmingPatch.ARDOUGNE_FLOWER_C, FarmingPatch.ARDOUGNE_ALLOTMENT_N, FarmingPatch.ARDOUGNE_ALLOTMENT_S, FarmingPatch.ARDOUGNE_HERB_CE))};
    static final int SCAN_R = 14, SITE_R = 120;
    /** Review S1: failed CHECK runs (gave up on the way or at the patches) before the patch set is abandoned. */
    static final int CHECK_FAILS = 3;
    /** Pull home: a due CHECK multiplies the score of jobs whose spot is nearest the farm site (chooseJob), so the trip ends at a bank in that city. */
    static final double PULL = 3.0;
    /** Overdue fallback: a CHECK 20+ min past due starts from any bank within 200 tiles of the anchor. 200 covers the next city over
     *  (Falador farm from Varrock west bank ~182, Draynor, Edgeville ~188; Catherby from Seers and the Ardougne banks) but not the far
     *  side of the map (Falador to Catherby ~290): a ~200-tile walk is ~2-3 min each way, well inside the 12 min give-up. */
    static final long OVERDUE_MS = Long.getLong("farming.overdueMin", 0L) * MIN; static final int OVERDUE_R = 240; /* farm48: Varrock east bank is ~228 from the Falador anchor */ // farm47: 0 = a due CHECK starts at once from any bank in range (farm46 pair: 20 min wait + 120-tile SITE_R gave 0 harvests; Falador west bank is 126 tiles out)

    /** Nearest site within 120 tiles straight-line, -1 if none. */
    static int nearestSite(Location l) {
        int best = -1; double bd = Double.MAX_VALUE;
        for (int i = 0; i < SITES.length; i++) {
            Location a = SITES[i].anchor;
            if (a.getZ() != l.getZ()) continue;
            double d = a.getDistance(l);
            if (d <= SITE_R && d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static final List<Scenery>[] CACHE = newCache();
    @SuppressWarnings("unchecked") private static List<Scenery>[] newCache() { return (List<Scenery>[]) new List[3]; }

    /** The site's patch wrappers in the order of Site.patches (allotments first, herb last); missing ones are left out. Cached. */
    static synchronized List<Scenery> find(int site) {
        if (CACHE[site] != null) return CACHE[site];
        Site s = SITES[site];
        Scenery[] got = new Scenery[s.patches.length];
        java.util.List<Integer> nodes = FarmingPatch.Companion.getPatchNodes();
        Location a = s.anchor;
        for (int x = a.getX() - SCAN_R; x <= a.getX() + SCAN_R; x++) for (int y = a.getY() - SCAN_R; y <= a.getY() + SCAN_R; y++) {
            Scenery o;
            try { o = RegionManager.getObject(a.getZ(), x, y); } catch (Throwable t) { o = null; }
            if (o == null || !nodes.contains(o.getId())) continue;
            FarmingPatch fp;
            try { fp = FarmingPatch.forObject(o); } catch (Throwable t) { fp = null; }
            if (fp == null) continue;
            for (int i = 0; i < s.patches.length; i++) if (s.patches[i] == fp && got[i] == null) got[i] = o;
        }
        List<Scenery> out = new ArrayList<>();
        for (Scenery o : got) if (o != null) out.add(o);
        if (out.size() < s.patches.length) System.out.println("[Farm] site " + s.key + " found " + out.size() + "/" + s.patches.length);
        CACHE[site] = Collections.unmodifiableList(out);
        return CACHE[site];
    }

    // fleet cap: bots on a run or holding crops (a set, so a double end never counts twice)
    static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    static boolean seat(String name) { if (ACTIVE.contains(name.toLowerCase(Locale.ROOT))) return true; if (ACTIVE.size() >= MAX) return false; return ACTIVE.add(name.toLowerCase(Locale.ROOT)); }
    static void free(String name) { ACTIVE.remove(name.toLowerCase(Locale.ROOT)); }
    static int sweep(java.util.function.Predicate<String> holds) {
        int n = 0;
        for (String k : ACTIVE) if (!holds.test(k) && ACTIVE.remove(k)) n++;
        if (n > 0) System.out.println("[Farm] freed " + n + " stale seat(s) (no crops behind them)");
        return n;
    }
    static boolean seat(String name, java.util.function.Predicate<String> holds) { if (ACTIVE.size() >= MAX) sweep(holds); return seat(name); }

    static volatile long PULLS, OVERDUES, RUNS, PLANTED, HARVESTED, DIED, ABANDONED, STALLS, SEED_SPEND;
    /** farm2: buckets used, diseased patches cured, flowers planted, flowers lost, gp on flower seed + compost + cure (already inside SEED_SPEND). */
    static volatile long COMPOSTED, CURED, FLOWERS, FLOWER_DIED, EXTRA_SPEND;

    // Why a trip end did not start a run: one [Farm] tally line every 15 min (2 min under -Dtraders.fast).
    static final int R_BUSY = 0, R_COMBAT = 1, R_WAIT = 2, R_NOT_BANK = 3, R_FAR = 4, R_SLOTS = 5, R_FIT = 6, R_CAP = 7, R_BUDGET = 8, R_BUY_FAIL = 9, R_NO_SITE = 10, R_STARTED = 11, R_DROPPED = 12;
    static final String[] REASONS = {"busy", "combat", "wait", "notBank", "far", "slots", "noTime", "fleetCap", "budget", "buyFail", "noSite", "started", "checkDropped"};
    static final long[] TALLY = new long[REASONS.length];
    private static long tallyAt;

    static synchronized boolean skip(int reason, long now) {
        TALLY[reason]++;
        if (tallyAt == 0) tallyAt = now;
        else if (now - tallyAt >= (Boolean.getBoolean("traders.fast") ? 120_000 : 15 * 60_000)) { tallyAt = now; System.out.println("[Farm] tally " + tallyLine() + " " + totals()); }
        return false;
    }

    static synchronized String tallyLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < REASONS.length; i++) sb.append(i == 0 ? "" : " ").append(REASONS[i]).append('=').append(TALLY[i]);
        return sb.toString();
    }

    static String totals() { return totals(V2) + (W9Trees.RUN ? W9Trees.totals() : "") + (FarmSave.ON ? FarmSave.totals() : ""); } // W2: save counters at the very end, only with the save flag; w9 trees: the tree counters just before them, only with -Dbots.farming.trees.run
    /** W2 FarmSave: a site's index by its key (the key is what a crop file stores), -1 if unknown. */
    static int siteIndex(String key) { for (int i = 0; i < SITES.length; i++) if (SITES[i].key.equals(key)) return i; return -1; }
    /** v2 fields are added at the END only (the v1 part is parsed by scripts). */
    static String totals(boolean v2) { return v2 ? totals1() + " composted=" + COMPOSTED + " cured=" + CURED + " flowers=" + FLOWERS + " flowerDied=" + FLOWER_DIED + " extraSpend=" + EXTRA_SPEND : totals1(); }
    private static String totals1() {
        return "[Farm] planted=" + PLANTED + " harvested=" + HARVESTED + " died=" + DIED + " seedSpend=" + SEED_SPEND + " runs=" + RUNS + " abandoned=" + ABANDONED + " stalls=" + STALLS + " pulls=" + PULLS + " overdue=" + OVERDUES;
    }
}
