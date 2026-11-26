package content.custom;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * W10 lane L (the design notes): two skills-plan gaps still open after train-64. Each item has its own -D
 * flag, default OFF; with a flag off its caller (SmartAdventurers.kitFromExchange, MarketBrain's timer) is never reached:
 * no branch taken, no RNG draw, no line. The W10 code draws no RNG at all, flag on or off.
 *
 *   -Dbots.fletch.arrows    Fletching (audit artisan, Fletching c): a ranger at the Exchange tips its own arrows (headless arrow
 *                           + arrowtips -> arrows, the 2009 level and XP per arrow), feathering arrow shafts first (shaft +
 *                           feather -> headless arrow, 1 XP) when neither its bank nor bot stock holds headless arrows. Tips,
 *                           headless arrows, shafts and feathers come out of bot stock only (Ledger.consume: the fletchers,
 *                           smiths, woodcutters and hunters who made them are paid; never a shop, nothing created); the arrows
 *                           go to its bank for its next trips. One try per bot per gap, a fleet cap of arrows an hour.
 *   -Dbots.magic.superheat  Smithing + Magic (audit artisan, Smithing c): a Magic 43+ bot at the Exchange superheats ore from bot
 *                           stock into the best bar its Smithing allows (iron to rune; coal for steel and up), a nature and 4
 *                           fire runes a cast from bot stock (a worn fire staff covers the fire), 53 Magic XP a cast and the
 *                           bar's Smithing XP; the bars go on the Exchange as its stock (Ledger.supply: it is paid when they
 *                           sell). Never a bar in glut, never more than CEIL_X times the bar's price a cast. One try per bot per
 *                           gap, a fleet cap of casts an hour.
 *
 * This class holds only the flags, the pure rules and the counters, so plain java can test it without a world
 * (TestW10Arrows, TestW10Superheat). Nothing here touches a server class; the engine side is W10Hooks.
 */
final class W10Skills {
    private W10Skills() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static double dbl(String k, double d) { try { String v = System.getProperty(k); return v == null ? d : Double.parseDouble(v.trim()); } catch (Throwable t) { return d; } }

    /** A fleet window of one hour: how many units are left under the cap, and booking them. */
    static final class Hour {
        final int cap; long start = Long.MIN_VALUE; int used;
        Hour(int cap) { this.cap = Math.max(0, cap); }
        private void roll(long now) { if (start == Long.MIN_VALUE || now - start >= 3_600_000L || now < start) { start = now; used = 0; } }
        synchronized int left(long now) { roll(now); return Math.max(0, cap - used); }
        synchronized void use(long now, int n) { roll(now); used += Math.max(0, n); }
        synchronized int used(long now) { roll(now); return used; }
    }

    /** One try per bot per gap: true (and the next try booked) when this bot's gap is over. */
    static boolean take(Map<String, Long> next, String who, long now, long gapMs) {
        if (who == null) return false;
        Long t = next.get(who);
        if (t != null && now < t) return false;
        next.put(who, now + Math.max(0, gapMs));
        return true;
    }

    /** At most LOG_CAP W10 lines a ten-minute window per item (the counters still count everything). */
    static final int LOG_CAP = clamp(Integer.getInteger("bots.w10.logCap", 40), 1, 10_000);
    static final class Lines { long start = Long.MIN_VALUE; int n; int dropped;
        synchronized boolean ok(long now) { if (start == Long.MIN_VALUE || now - start >= 600_000L || now < start) { start = now; n = 0; } if (n >= LOG_CAP) { dropped++; return false; } n++; return true; } }

    // ------------------------------------------------------------------ Fletching: bots.fletch.arrows
    static final boolean ARROWS = flag("bots.fletch.arrows");
    static final int SET = 15;                 // the engine's arrows a set: quantities are whole sets
    static final int FLOOR = 15;               // units always left on the Exchange for players and the fletcher
    static final int ARROW_MAX = clamp(Integer.getInteger("bots.fletch.arrows.max", 150), SET, 1_500);
    static final Hour ARROW_HOUR = new Hour(Math.max(0, Integer.getInteger("bots.fletch.arrows.perHour", 3_000)));
    static final long ARROW_GAP_MS = Math.max(0L, Long.getLong("bots.fletch.arrows.gapMin", 30L)) * 60_000L;
    static final double ARROW_CEIL_X = Math.max(0.1, dbl("bots.fletch.arrows.ceilX", 1.2));
    static final int HEADLESS = 53, SHAFT = 52, FEATHER = 314;
    static final int HEADLESS_XP10 = 10;       // 1 XP a headless arrow
    /** {arrow, arrowtips, Fletching level, XP an arrow x 10}: bronze to rune, the 2009 table. */
    static final int[][] ARROW_ROWS = {{882, 39, 1, 13}, {884, 40, 15, 25}, {886, 41, 30, 50}, {888, 42, 45, 75}, {890, 43, 60, 100}, {892, 44, 75, 125}};
    static final int SRC_NONE = -1, SRC_OWN = 0, SRC_STOCK = 1, SRC_SHAFTS = 2;
    static final String[] SRC_NAMES = {"own", "stock", "shafts"};
    static final Map<String, Long> ARROW_NEXT = new ConcurrentHashMap<>();
    static final Lines ARROW_LINES = new Lines();
    static volatile int arrowRuns, arrowsMade, arrowsFeathered, arrowNoPick, arrowCapped;
    static volatile long arrowSpent;

    static int arrowIndex(int arrowId) { for (int i = 0; i < ARROW_ROWS.length; i++) if (ARROW_ROWS[i][0] == arrowId) return i; return -1; }

    /** Where the headless arrows come from: its own bank (a set or more), else bot stock, else shafts and feathers in bot stock. */
    static int headSource(int heldHeadless, int headStock, int shaftStock, int featherStock) {
        if (heldHeadless >= SET) return SRC_OWN;
        if (headStock >= SET + FLOOR) return SRC_STOCK;
        if (shaftStock >= SET + FLOOR && featherStock >= SET + FLOOR) return SRC_SHAFTS;
        return SRC_NONE;
    }
    /** Headless arrows that source can give (stock leaves FLOOR behind). */
    static int headLeft(int src, int heldHeadless, int headStock, int shaftStock, int featherStock) {
        switch (src) {
            case SRC_OWN: return Math.max(0, heldHeadless);
            case SRC_STOCK: return Math.max(0, headStock - FLOOR);
            case SRC_SHAFTS: return Math.max(0, Math.min(shaftStock, featherStock) - FLOOR);
            default: return 0;
        }
    }
    /** What one headless arrow costs the bot from that source (its own: nothing). */
    static long headCost(int src, int headPrice, int shaftPrice, int featherPrice) {
        return src == SRC_OWN ? 0 : src == SRC_STOCK ? Math.max(1, headPrice) : src == SRC_SHAFTS ? (long) Math.max(1, shaftPrice) + Math.max(1, featherPrice) : Long.MAX_VALUE / 4;
    }

    /**
     * The arrow it tips: from the arrow its bow and Ranged fire (maxArrowId) down, the first its Fletching allows whose tips bot
     * stock holds (a set over the floor) and whose tip + headless cost is at most CEIL_X times the arrow's price. -1 = none.
     */
    static int arrowRow(int fletching, int maxArrowId, int[] tipStock, int[] tipPrice, int[] arrowPrice, long headCost) {
        int top = arrowIndex(maxArrowId);
        for (int i = top; i >= 0; i--) {
            int[] r = ARROW_ROWS[i];
            if (fletching < r[2] || tipStock[i] < SET + FLOOR) continue;
            if (Math.max(1, tipPrice[i]) + headCost > Math.max(1, arrowPrice[i]) * ARROW_CEIL_X) continue;
            return i;
        }
        return -1;
    }

    /** How many: whole sets, at most max, the tips and headless arrows on offer, the fleet hour, and a third of the purse over 500 gp. 0 = under a set. */
    static int arrowQty(int tipLeft, int headLeft, int hourLeft, long coins, long unit, int max) {
        long n = Math.min(Math.min(max, tipLeft), Math.min(headLeft, hourLeft));
        if (unit > 0) n = Math.min(n, Math.max(0, coins - 500) / 3 / unit);
        n = n / SET * SET;
        return n >= SET ? (int) n : 0;
    }

    // ------------------------------------------------------------------ Smithing + Magic: bots.magic.superheat
    static final boolean SUPERHEAT = flag("bots.magic.superheat");
    static final int HEAT_LEVEL = 43, HEAT_XP10 = 530, NATURE = 561, FIRE = 554, FIRES = 4, COAL = 453;
    static final int HEAT_MIN = 5;             // fewer casts than this is not worth the trip to the bank
    static final int HEAT_FLOOR = 20;          // ore, coal and runes always left on the Exchange
    static final int HEAT_MAX = clamp(Integer.getInteger("bots.magic.superheat.max", 27), HEAT_MIN, 200);
    static final Hour HEAT_HOUR = new Hour(Math.max(0, Integer.getInteger("bots.magic.superheat.perHour", 400)));
    static final long HEAT_GAP_MS = Math.max(0L, Long.getLong("bots.magic.superheat.gapMin", 40L)) * 60_000L;
    static final double HEAT_CEIL_X = Math.max(0.1, dbl("bots.magic.superheat.ceilX", 1.15));
    /** {bar, Smithing level, ore, coal a bar, Smithing XP x 10}: single-ore bars iron to rune (no bronze: two ores). */
    static final int[][] HEAT_ROWS = {{2351, 15, 440, 0, 125}, {2355, 20, 442, 0, 137}, {2353, 30, 440, 2, 175}, {2357, 40, 444, 0, 225}, {2359, 50, 447, 4, 300}, {2361, 70, 449, 6, 375}, {2363, 85, 451, 8, 500}};
    /** Worn staves that stand in for the 4 fire runes: staff of fire, fire battlestaff, mystic fire staff, lava battlestaff, mystic lava staff. */
    static final int[] FIRE_STAVES = {1387, 1393, 1401, 3053, 3054};
    static final Map<String, Long> HEAT_NEXT = new ConcurrentHashMap<>();
    static final Lines HEAT_LINES = new Lines();
    static volatile int heatRuns, heatBars, heatNoPick, heatCapped;
    static volatile long heatSpent;
    // w24 (NOFIRE q29): why a try made nothing, counted for the [W10] hour line only (nothing a bot does reads these).
    static final String[] HEAT_WHY = {"ore", "coal", "nature", "fire", "hour", "purse", "room", "buy", "smith", "glut", "ceil"};
    static final int WHY_ROOM = 6, WHY_BUY = 7;
    static final java.util.concurrent.atomic.AtomicIntegerArray heatWhy = new java.util.concurrent.atomic.AtomicIntegerArray(HEAT_WHY.length);
    static void heatWhy(int i) { if (i >= 0 && i < HEAT_WHY.length) heatWhy.incrementAndGet(i); }
    /** The first limit that keeps heatQty (same arguments) under HEAT_MIN: 0 ore, 1 coal, 2 nature, 3 fire, 4 hour, 5 purse; -1 = none is short. Pure. */
    static int heatShort(int oreLeft, int coalLeft, int coalPer, int natureLeft, int fireLeft, boolean staff, int hourLeft, long coins, long unit, int max) {
        if (Math.min(max, oreLeft) < HEAT_MIN) return 0;
        if (coalPer > 0 && Math.max(0, coalLeft) / coalPer < HEAT_MIN) return 1;
        if (natureLeft < HEAT_MIN) return 2;
        if (!staff && Math.max(0, fireLeft) / FIRES < HEAT_MIN) return 3;
        if (hourLeft < HEAT_MIN) return 4;
        if (unit > 0 && Math.max(0, coins - 1_000) / 2 / unit < HEAT_MIN) return 5;
        return -1;
    }
    /** Why heatRow (same arguments) picked nothing: what stopped the best bar this Smithing level makes (9 glut, 0 ore, 1 coal, 10 ceil); 8 = no bar at this level; -1 = Magic under 43 or a row is picked. Pure. */
    static int heatRowShort(int magic, int smithing, int[] oreStock, int coalStock, boolean[] barGlut, long[] castCost, int[] barPrice) {
        if (magic < HEAT_LEVEL || heatRow(magic, smithing, oreStock, coalStock, barGlut, castCost, barPrice) >= 0) return -1;
        for (int i = HEAT_ROWS.length - 1; i >= 0; i--) {
            int[] r = HEAT_ROWS[i];
            if (smithing < r[1]) continue;
            if (barGlut[i]) return 9;
            if (oreStock[i] < HEAT_MIN + HEAT_FLOOR) return 0;
            if (r[3] > 0 && coalStock < r[3] * HEAT_MIN + HEAT_FLOOR) return 1;
            return 10;
        }
        return 8;
    }
    /** " why=purse:3,fire:5" (non-zero counts, HEAT_WHY order); "" while every count is 0, so the line is the old one until a try fails. */
    static String heatWhyText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < HEAT_WHY.length; i++) { int c = heatWhy.get(i); if (c > 0) b.append(b.length() == 0 ? " why=" : ",").append(HEAT_WHY[i]).append(':').append(c); }
        return b.toString();
    }

    static boolean fireStaff(int weaponId) { for (int s : FIRE_STAVES) if (s == weaponId) return true; return false; }

    /** One cast's inputs at the guide prices: the ore, its coal, a nature and (no fire staff) 4 fire runes. */
    static long castCost(int orePrice, int coalPrice, int coalPer, int naturePrice, int firePrice, boolean staff) {
        return (long) Math.max(1, orePrice) + (long) Math.max(1, coalPrice) * coalPer + Math.max(1, naturePrice) + (staff ? 0L : (long) FIRES * Math.max(1, firePrice));
    }

    /**
     * The bar it makes: best first, a bar its Smithing allows, not in glut, whose ore (and coal) bot stock holds for HEAT_MIN
     * casts over the floor, at a cast cost of at most CEIL_X times the bar's price. Magic under 43: -1. -1 = none.
     */
    static int heatRow(int magic, int smithing, int[] oreStock, int coalStock, boolean[] barGlut, long[] castCost, int[] barPrice) {
        if (magic < HEAT_LEVEL) return -1;
        for (int i = HEAT_ROWS.length - 1; i >= 0; i--) {
            int[] r = HEAT_ROWS[i];
            if (smithing < r[1] || barGlut[i]) continue;
            if (oreStock[i] < HEAT_MIN + HEAT_FLOOR) continue;
            if (r[3] > 0 && coalStock < r[3] * HEAT_MIN + HEAT_FLOOR) continue;
            if (castCost[i] > Math.max(1, barPrice[i]) * HEAT_CEIL_X) continue;
            return i;
        }
        return -1;
    }

    /** Casts: at most max, the ore, coal, natures and fires on offer (stock less the floor), the fleet hour, half the purse over 1,000 gp. 0 = under HEAT_MIN. */
    static int heatQty(int oreLeft, int coalLeft, int coalPer, int natureLeft, int fireLeft, boolean staff, int hourLeft, long coins, long unit, int max) {
        long n = Math.min(Math.min(max, oreLeft), Math.min(natureLeft, hourLeft));
        if (coalPer > 0) n = Math.min(n, Math.max(0, coalLeft) / coalPer);
        if (!staff) n = Math.min(n, Math.max(0, fireLeft) / FIRES);
        if (unit > 0) n = Math.min(n, Math.max(0, coins - 1_000) / 2 / unit);
        return n >= HEAT_MIN ? (int) n : 0;
    }

    // ------------------------------------------------------------------ Farming: bots.farming.herbRun (W10 lane M)
    /**
     * -Dbots.farming.herbRun (needs -Dbots.farming, like Farming.ON): a farm run at a site goes on to the herb patch of the other
     * sites it can walk to (same plane, same side of White Wolf Mountain as the farm47 check rule, a chain of at most LEG_R tiles
     * a hop), harvesting what grows there and planting the run's herb seed. In practice: Catherby and Ardougne together (~168
     * tiles); Falador has no partner on its side; Port Phasmatys is behind the Priest in Peril gate (Gates "morytania") and
     * Trollheim behind its quests, so neither is a site. No new FarmSave key: the legs are found again from the bot's own crop rows.
     */
    static final boolean HERB_RUN = flag("bots.farming") && flag("bots.farming.herbRun");
    static final int MOUNTAIN_X = 2880;        // Farming's "never cross White Wolf Mountain" split
    static final int LEG_R = clamp(Integer.getInteger("bots.farming.herbRun.legR", 200), 20, 400);
    static final int MAX_LEGS = clamp(Integer.getInteger("bots.farming.herbRun.legs", 3), 1, 3); // 2-4 herb patches a trip
    static final int[] NO_LEGS = new int[0];
    static final Lines HERB_LINES = new Lines();
    static volatile int herbRuns, herbLegs, herbLegsDone, herbLegHarvest, herbLegPlanted, herbSeeds, herbNoLeg;

    /**
     * The other sites a run from home can add, in walking order: from the last stop, the nearest site not yet taken on home's
     * plane and side of the mountain within r tiles (straight line), at most max. at[i] = {x, y, z} of site i's anchor.
     */
    static int[] reach(int home, int[][] at, int max, int r) {
        if (at == null || home < 0 || home >= at.length || max <= 0) return NO_LEGS;
        boolean[] used = new boolean[at.length]; used[home] = true;
        int[] out = new int[Math.min(max, at.length - 1)]; int n = 0, cur = home;
        final boolean west = at[home][0] < MOUNTAIN_X;
        while (n < out.length) {
            int best = -1; double bd = Double.MAX_VALUE;
            for (int i = 0; i < at.length; i++) {
                if (used[i] || at[i][2] != at[home][2] || (at[i][0] < MOUNTAIN_X) != west) continue;
                double dx = at[i][0] - at[cur][0], dy = at[i][1] - at[cur][1], d = Math.sqrt(dx * dx + dy * dy);
                if (d <= r && d < bd) { bd = d; best = i; }
            }
            if (best < 0) break;
            used[best] = true; out[n++] = best; cur = best;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** The legs this run walks: in order, every one with a crop in its herb patch (to harvest), and empty ones while seeds last (a crop leg replants first). */
    static int[] visit(int[] cand, boolean[] crop, int seeds) {
        if (cand == null || cand.length == 0) return NO_LEGS;
        int[] out = new int[cand.length]; int n = 0, s = Math.max(0, seeds);
        for (int i = 0; i < cand.length; i++) {
            boolean c = crop != null && i < crop.length && crop[i];
            if (!c && s <= 0) continue;
            if (s > 0) s--;
            out[n++] = cand[i];
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    static String legNames(String[] keys, int home, int[] legs) {
        StringBuilder b = new StringBuilder(home >= 0 && home < keys.length ? keys[home] : "?");
        for (int l : legs) b.append(" + ").append(l >= 0 && l < keys.length ? keys[l] : "?");
        return b.toString().toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ Attack: bots.attack.spec (W10 lane M)
    /**
     * -Dbots.attack.spec: a melee bot with Attack 60+ that wields a table scimitar (ARMOUR_TIERS) or nothing buys a 2009 spec weapon
     * from bot stock at the Exchange (Ledger.consume: the seller is paid; never a shop, nothing minted) and wields it, the old
     * weapon going to its bank (stowWeapon, as weaponByFoe does). Dragon scimitar first, then longsword, then dagger, only where
     * SmartAdventurers.questOk allows (Monkey Madness / Lost City: the high-tier bots). One owned is wielded again at the next
     * Exchange visit. Never over a mace (weaponByFoe's crush pick) or a non-table weapon (whip, goal items). The specials
     * themselves are AfkTasks.specTick (batch 15 item5): any bot wielding one of these uses the special when the bar allows.
     */
    static final boolean SPEC = flag("bots.attack.spec");
    static final int SPEC_LEVEL = 60;
    static final double SPEC_SHARE = Math.max(0.01, Math.min(1.0, dbl("bots.attack.spec.share", 0.3))); // W8 KIT_SHARE: one piece <= 30% of (coins - 300)
    static final long SPEC_GAP_MS = Math.max(0L, Long.getLong("bots.attack.spec.gapMin", 60L)) * 60_000L;
    /** {weapon, Attack level}: dragon scimitar, dragon longsword, dragon dagger (best main hand first). */
    static final int[][] SPEC_ROWS = {{4587, 60}, {1305, 60}, {1215, 60}};
    static final int SP_NONE = 0, SP_WIELD = 1, SP_BUY = 2;
    static final Map<String, Long> SPEC_NEXT = new ConcurrentHashMap<>();
    static final Lines SPEC_LINES = new Lines();
    static volatile int specBuys, specWields, specNoPick, specMace, specOther;
    static volatile long specSpent;

    static int specIndex(int id) { for (int i = 0; i < SPEC_ROWS.length; i++) if (SPEC_ROWS[i][0] == id) return i; return -1; }

    /** What to do: worn -1 = nothing in the hand; ownedRow = a spec weapon it owns and may wield (-1 none). */
    static int specAction(int worn, boolean wornMace, boolean wornTable, int ownedRow, int attack) {
        if (attack < SPEC_LEVEL || worn >= 0 && specIndex(worn) >= 0) return SP_NONE;
        if (wornMace) { specMace++; return SP_NONE; }
        if (worn >= 0 && !wornTable) { specOther++; return SP_NONE; }
        return ownedRow >= 0 ? SP_WIELD : SP_BUY;
    }

    /** The weapon it buys: best first, its Attack, quest-allowed, in bot stock, at most SPEC_SHARE of (coins - 300) and within capLeft. -1 = none. */
    static int specPick(int attack, boolean[] questOk, int[] stock, int[] price, long coins, long capLeft) {
        for (int i = 0; i < SPEC_ROWS.length; i++) {
            if (attack < SPEC_ROWS[i][1] || !questOk[i] || stock[i] <= 0) continue;
            if (price[i] <= 0 || price[i] > (coins - 300) * SPEC_SHARE || price[i] > capLeft) continue;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ Ranged: bots.ranged.thrown (W10 lane M)
    /**
     * -Dbots.ranged.thrown: a ranger under Ranged THROWN_MAX, at the Exchange, takes darts or knives for the trip when they cost it
     * less than the arrows (and a bow, if it owns none) would: the best its Ranged allows, units it holds first, the rest from
     * bot stock (Ledger.consume: the smith and fletcher who made them are paid; never a shop, nothing minted). Thrown weapons are
     * worn in the weapon slot (the engine's ranged_weapon_configs: the weapon is the ammo); the bow goes to its bank. While one is
     * worn the bow/arrow paths (styleKit's bow, supplyNeeds, w3Restock, styleWeaponBack, styleFallback) leave it alone; when
     * arrows are cheaper again, or it reaches THROWN_MAX, the stack goes to the bank and its own bow comes back on.
     */
    static final boolean THROWN = flag("bots.ranged.thrown");
    static final int THROWN_MAX = clamp(Integer.getInteger("bots.ranged.thrown.maxLevel", 40), 2, 99);
    static final int THROWN_TRIP = clamp(Integer.getInteger("bots.ranged.thrown.trip", 150), 20, 1_000);
    static final int THROWN_FLOOR = 10;        // units always left on the Exchange
    /** {item, Ranged level, ranged strength}: weakest first (the engine's item_configs bonuses). */
    static final int[][] THROWN_ROWS = {{806, 1, 1}, {807, 1, 3}, {864, 1, 3}, {863, 1, 4}, {808, 5, 4}, {3093, 10, 6}, {865, 5, 7}, {809, 20, 7},
            {869, 10, 8}, {866, 20, 10}, {810, 30, 10}, {867, 30, 14}};
    static final Lines THROWN_LINES = new Lines();
    static volatile int thrownTrips, thrownBought, thrownBack, thrownNoPick;
    static volatile long thrownSpent;

    static int thrownIndex(int id) { for (int i = 0; i < THROWN_ROWS.length; i++) if (THROWN_ROWS[i][0] == id) return i; return -1; }

    /** What the arrows would cost this trip: the ones it lacks of n, and its level's bow when it owns none. */
    static long arrowCost(int n, int heldArrows, int arrowPrice, boolean ownsBow, int bowPrice) {
        return (long) Math.max(1, arrowPrice) * Math.max(0, n - heldArrows) + (ownsBow ? 0L : Math.max(1, bowPrice));
    }

    /**
     * The thrown weapon it takes for a trip of n, or -1 (bow and arrows): Ranged under THROWN_MAX; best first; its Ranged; the units
     * it lacks in bot stock over the floor; their cost under half its purse over 300 gp and strictly under arrowCost (the one it
     * already wields, fully held, may also tie).
     */
    static int thrownRow(int ranged, int[] held, int[] stock, int[] price, int n, long arrowCost, long coins, int wornRow) {
        if (ranged >= THROWN_MAX) return -1;
        for (int i = THROWN_ROWS.length - 1; i >= 0; i--) {
            if (ranged < THROWN_ROWS[i][1]) continue;
            int buy = Math.max(0, n - held[i]);
            if (buy > 0 && stock[i] < buy + THROWN_FLOOR) continue;
            long cost = (long) Math.max(1, price[i]) * buy;
            if (cost > Math.max(0L, coins - 300) / 2) continue;
            if (cost < arrowCost || buy == 0 && i == wornRow && cost <= arrowCost) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return ARROWS || SUPERHEAT || HERB_RUN || SPEC || THROWN; }
    static String line() { return line(System.currentTimeMillis()); }
    static String line(long now) {
        StringBuilder b = new StringBuilder("[W10] hour:");
        if (ARROWS) b.append(" arrows(runs=").append(arrowRuns).append(" made=").append(arrowsMade).append(" feathered=").append(arrowsFeathered)
                .append(" spent=").append(arrowSpent).append("gp noPick=").append(arrowNoPick).append(" capped=").append(arrowCapped)
                .append(" hour=").append(ARROW_HOUR.used(now)).append('/').append(ARROW_HOUR.cap).append(')');
        if (SUPERHEAT) b.append(" superheat(runs=").append(heatRuns).append(" bars=").append(heatBars).append(" spent=").append(heatSpent)
                .append("gp noPick=").append(heatNoPick).append(" capped=").append(heatCapped)
                .append(" hour=").append(HEAT_HOUR.used(now)).append('/').append(HEAT_HOUR.cap).append(heatWhyText()).append(')'); // w24: + why= once a try has failed
        if (HERB_RUN) b.append(" herbRun(runs=").append(herbRuns).append(" legs=").append(herbLegs).append(" done=").append(herbLegsDone)
                .append(" harvested=").append(herbLegHarvest).append(" planted=").append(herbLegPlanted).append(" seeds=").append(herbSeeds)
                .append(" noLeg=").append(herbNoLeg).append(')');
        if (SPEC) b.append(" spec(buys=").append(specBuys).append(" spent=").append(specSpent).append("gp wields=").append(specWields)
                .append(" noPick=").append(specNoPick).append(" mace=").append(specMace).append(" other=").append(specOther).append(')');
        if (THROWN) b.append(" thrown(trips=").append(thrownTrips).append(" bought=").append(thrownBought).append(" spent=").append(thrownSpent)
                .append("gp back=").append(thrownBack).append(" noPick=").append(thrownNoPick).append(')');
        if (!anyOn()) b.append(" off");
        return b.toString();
    }
}
