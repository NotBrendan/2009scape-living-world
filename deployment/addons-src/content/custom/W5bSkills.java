package content.custom;

/**
 * W5 skills, wave 2 lane B (the design notes section 2): six economy and combat-style fixes. Every fix
 * has its own -D flag, default OFF; with a flag off its caller runs train-60's code unchanged.
 *
 * Like W3Skills this class holds only the flags, the pure decision rules and the counters, so plain java can test it
 * without a world (TestW5bSkills). The callers (Artisans, AfkTasks.Task, SmartAdventurers) pass plain numbers in.
 *
 *   -Dartisans.crafter2         Crafting: a second crafter; the two split the recipes by branch (leather/hide vs gems,
 *                               jewellery, silver, glass); half-made crafting goods are not held back; raw uncut gems and
 *                               dragonhide keep a reserve of 10, not 50
 *   -Dbots.thieve.stalls        Thieving: smart bots may run the baker's and silk stalls in Ardougne (3 seats in all, combat 20+)
 *   -Dbots.prayerRun.market     Prayer: the bone run buys bones from bot stock at any bank, and dragon bones for rich bots, under
 *                               a price ceiling (needs bots.prayerRun)
 *   -Dbots.stancePlan           Attack: melee stance aimed at the next weapon tier (Attack 5/20/30/40/60 first)
 *   -Dbots.buildBias            Strength: per-bot build from the name: strength-first 30%, balanced 50%, tank 20%
 *   -Dbots.style.defShare       Defence: a ranger whose Defence lags Ranged by 10+ fights on Longrange (Ranged + Defence XP)
 */
final class W5bSkills {
    private W5bSkills() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------ Crafting: artisans.crafter2
    static final boolean CRAFTER2 = flag("artisans.crafter2");
    /** Reserve left on the Exchange of uncut gems and dragonhide (raw goods players also buy) when a crafter plans. */
    static final int CRAFT_RAW_KEEP = clamp(Integer.getInteger("artisans.crafter2.rawKeep", 10), 0, 50);
    static final int BRANCH_ALL = -1, BRANCH_LEATHER = 0, BRANCH_GEMS = 1;
    static final String[] BRANCH_NAMES = {"leather", "gems"};
    static volatile int craftKeepCut, craftIdleLogs;
    static final long[] craftIdleAt = new long[2];

    /** Inputs of the leather branch: cowhide, leathers, snake hides, dragonhide and d'hide leathers, flax (bow string). */
    static final int[] LEATHER_INPUTS = {1739, 1741, 1743, 6287, 7801, 6289, 1753, 1751, 1749, 1747, 1745, 2505, 2507, 2509, 1779};
    /** Half-made crafting goods (made by a crafting recipe, used by another): tanned leathers, cut gems, unstrung amulets, molten glass. */
    static final int[] CRAFT_HALF = {1741, 1743, 1745, 2505, 2507, 2509, 1607, 1605, 1603, 1601, 1615, 6573,
            1673, 1675, 1677, 1679, 1681, 1683, 6579, 1775};
    /** Raw crafting goods that came in too few to pass the 50 reserve: uncut gems and dragonhide. */
    static final int[] CRAFT_RAW = {1617, 1619, 1621, 1623, 1625, 1627, 1629, 1631, 6571, 1747, 1749, 1751, 1753};

    static boolean has(int[] a, int id) { for (int x : a) if (x == id) return true; return false; }

    /** The branch a crafting recipe belongs to: leather when any input is a hide, leather or flax; gems otherwise. */
    static int craftBranch(int[] inIds) {
        if (inIds != null) for (int id : inIds) if (has(LEATHER_INPUTS, id)) return BRANCH_LEATHER;
        return BRANCH_GEMS;
    }

    /** choose(): a crafter with a branch (>= 0) plans only that branch's recipes; branch -1 (every artisan with the flag off) plans all. */
    static boolean branchSkips(int branch, String trade, int[] inIds) {
        return branch >= 0 && "crafting".equalsIgnoreCase(trade) && craftBranch(inIds) != branch;
    }

    /**
     * The reserve for a crafting input (after W3Skills.keep): 0 for half-made crafting goods (they come 1 per hide or gem and
     * never pass 50 + a batch), CRAFT_RAW_KEEP for uncut gems and dragonhide. Any other trade or input: unchanged.
     */
    static int keep(boolean on, String trade, int inputId, int keepIn, int rawKeep) {
        if (!on || !"crafting".equalsIgnoreCase(trade)) return keepIn;
        if (has(CRAFT_HALF, inputId)) return 0;
        if (has(CRAFT_RAW, inputId)) return Math.min(keepIn, rawKeep);
        return keepIn;
    }
    static int keep(String trade, int inputId, int keepIn) { int k = keep(CRAFTER2, trade, inputId, keepIn, CRAFT_RAW_KEEP); if (k != keepIn) craftKeepCut++; return k; }

    /** Coins the first crafter keeps when it funds the second crafter's first boot. */
    static final long CRAFT2_FLOOR = Math.max(0L, Long.getLong("artisans.crafter2.floor", 20_000L));

    /** Coins moved to the second crafter: half the first crafter's purse, never leaving it under the floor (0 at or under the floor). */
    static long purseShare(long purse, long floor) { return Math.max(0, Math.min(purse / 2, purse - floor)); }

    /**
     * The transfer split: {from the bank, from the pack, total moved}. Bank coins first, then pack coins. Nothing is minted:
     * (bank - b) + (pack - i) + moved == bank + pack for every input (TestW5bSkills).
     */
    static long[] purseMove(long bank, long pack, long floor) {
        bank = Math.max(0, bank); pack = Math.max(0, pack);
        long n = purseShare(bank + pack, floor);
        long b = Math.min(bank, n), i = Math.min(pack, n - b);
        return new long[]{b, i, b + i};
    }

    /** The crew with one more crafter appended (flag on), else the crew itself. */
    static <T> T[] crew(boolean on, T[] crew, T crafting) {
        if (!on) return crew;
        T[] out = java.util.Arrays.copyOf(crew, crew.length + 1);
        out[crew.length] = crafting;
        return out;
    }
    /** Branch for the n-th crafter spawned (0-based): the first leather, the second gems; -1 with the flag off. */
    static int branchFor(boolean on, int nth) { return on ? Math.floorMod(nth, 2) : BRANCH_ALL; }

    /** At most one idle line per branch every 30 minutes. */
    static synchronized boolean craftIdleDue(int branch, long now) {
        int b = branch == BRANCH_LEATHER ? 0 : 1;
        if (now - craftIdleAt[b] < 30 * 60_000L) return false;
        craftIdleAt[b] = now; craftIdleLogs++; return true;
    }

    // ------------------------------------------------------------------ Thieving: bots.thieve.stalls
    static final boolean STALLS = flag("bots.thieve.stalls");
    /** Bots on stalls at once, all Ardougne market stalls together; the plan's cap is 3, never more. */
    static final int STALL_SEATS = clamp(Integer.getInteger("bots.thieve.stalls.seats", 3), 1, 3);
    /** Combat level a bot needs before it steals in the Ardougne market (the guards and the stall owner hit back). */
    static final int STALL_MIN_COMBAT = clamp(Integer.getInteger("bots.thieve.stalls.minCombat", 20), 3, 126);
    static final int[] BAKER_LOOT = {1891, 2309, 1901}, SILK_LOOT = {950};
    static volatile int stallFull, stallLowCombat;

    /**
     * Task products for a stall when bots may run it: the engine's own stall loot (AfkTasks.STALL_LOOT, the same the players'
     * runs count). Only the baker's and silk stalls, the low ones; null otherwise, which keeps every other stall players-only.
     */
    static int[] stallProducts(boolean on, String key) {
        if (!on || key == null) return null;
        if (key.equals("stall_baker")) return BAKER_LOOT.clone();
        if (key.equals("stall_silk")) return SILK_LOOT.clone();
        return null;
    }
    /** A seat is free while fewer than the cap of bots steal from the market stalls. */
    static boolean stallSeat(int botsOnStalls, int seats) { return botsOnStalls < Math.min(3, seats); }

    // ------------------------------------------------------------------ Prayer: bots.prayerRun.market
    static final boolean PRAYER_MARKET = flag("bots.prayerRun.market");
    static final int DRAGON_BONES = 536;
    static final double XP_DRAGON = 72.0;
    /** Price ceilings (gp per bone, MarketBrain.value): above them bots leave the bones to players. */
    /** Fallback price ceilings (gp per bone), used only when the item has no reference price (PriceDrift.BASE). */
    static final int CEIL_NORM = Integer.getInteger("bots.prayerRun.market.ceilNorm", 150), CEIL_BIG = Integer.getInteger("bots.prayerRun.market.ceilBig", 500),
            CEIL_DRAGON = Integer.getInteger("bots.prayerRun.market.ceilDragon", 3_000);
    /** Ceiling = this times the item's reference price (its PriceDrift base, the price players' trades drift away from). */
    static final double CEIL_X = Math.max(1.0, W3Skills.dbl("bots.prayerRun.market.ceilX", 1.5));

    /** The ceiling for one bone: CEIL_X x the reference when there is one (> 0), else the fixed fallback. */
    static int boneCeil(Integer ref, double x, int fallback) { return ref != null && ref > 0 ? (int) Math.max(1, Math.round(ref * x)) : fallback; }
    /** Purse a bot needs before it buys dragon bones. */
    static final long DRAGON_PURSE = Long.getLong("bots.prayerRun.market.dragonPurse", 200_000L);
    static volatile int marketRuns, marketDragon, marketCeil;

    /**
     * The market pick, asked only after PrayerRun.boneChoice said no. Returns {id, n, 1 (buy)} or null. Dragon bones first
     * for a purse of 200k+, then big, then normal bones; each only under its price ceiling, with 40 left on the Exchange
     * for players after the buy, within 10% of the purse, and within the fleet's 300 an hour (PrayerRun.hourLeft). No
     * surplus and no Exchange visit needed: the Ledger buys from bot stock at any bank.
     */
    static int[] boneMarket(boolean on, int prayer, long coins, int stockDragon, int stockBig, int stockNorm, int valDragon, int valBig, int valNorm,
                            int ceilDragon, int ceilBig, int ceilNorm, int hourLeft) {
        if (!on || prayer >= PrayerRun.MAX_PRAYER || hourLeft <= 0) return null;
        int[] ids = {DRAGON_BONES, PrayerRun.BIG_BONES, PrayerRun.BONES};
        int[] stock = {stockDragon, stockBig, stockNorm}, val = {valDragon, valBig, valNorm}, ceil = {ceilDragon, ceilBig, ceilNorm};
        for (int i = 0; i < 3; i++) {
            if (i == 0 && coins < DRAGON_PURSE) continue;
            if (val[i] <= 0) continue;
            if (val[i] > ceil[i]) { marketCeil++; continue; }
            long afford = Math.max(0, coins) / 10 / val[i];
            int n = (int) Math.min(Math.min(PrayerRun.MAX_N, afford), Math.min(hourLeft, (long) stock[i] - PrayerRun.MIN_STOCK));
            if (n < PrayerRun.MIN_N) continue;
            return new int[]{ids[i], n, 1};
        }
        return null;
    }

    // ------------------------------------------------------------------ Attack / Strength: bots.stancePlan, bots.buildBias
    static final boolean STANCE_PLAN = flag("bots.stancePlan");
    static final boolean BUILD_BIAS = flag("bots.buildBias");
    static final int[] TIERS = {5, 20, 30, 40, 60}; // bronze-iron, mithril, adamant, rune, dragon
    static final int BUILD_BALANCED = 0, BUILD_STRENGTH = 1, BUILD_TANK = 2;
    static final String[] BUILD_NAMES = {"balanced", "strength", "tank"};
    static final int[] stanceCount = new int[4]; // attack, strength, defence, shared (unused)
    static final int[] buildCount = new int[3];

    static int nextTier(int att) { for (int t : TIERS) if (att < t) return t; return 0; }
    static int lastTier(int att) { int p = 1; for (int t : TIERS) if (att >= t) p = t; return p; }

    /** A stable build from the name: strength-first 30%, balanced 50%, tank 20% (salted apart from styleFor's ranged/magic hash). */
    static int buildOf(String name) {
        if (name == null) return BUILD_BALANCED;
        int h = 11; for (char c : name.toLowerCase(java.util.Locale.ROOT).toCharArray()) h = h * 37 + c;
        int m = Math.floorMod(h ^ 0x5bd1e995, 100);
        return m < 30 ? BUILD_STRENGTH : m < 80 ? BUILD_BALANCED : BUILD_TANK;
    }

    /** train-60's melee stance (SmartAdventurers trip start): defence when it lags attack by 10+, else strength up to attack, else a coin flip. */
    static String oldStance(int att, int str, int def, boolean coin) {
        return def < att - 10 ? "defence" : str < att ? "strength" : coin ? "attack" : "strength";
    }

    /**
     * The stance plan: Defence still first when it lags Attack by 10+; then Attack to the next weapon tier (5, 20, 30, 40, 60)
     * once Strength has reached the tier Attack is on; Strength up to Attack; past 60 the old coin flip.
     */
    static String planStance(int att, int str, int def, boolean coin) {
        if (def < att - 10) return "defence";
        if (nextTier(att) > 0 && str >= lastTier(att)) return "attack";
        if (str < att) return "strength";
        return coin ? "attack" : "strength";
    }

    /**
     * The stance for a melee trip. plan/bias are the two flags; with both off this is oldStance exactly. Strength-first:
     * Strength up to Attack + 10 (Defence only when 15+ behind). Tank: Defence up to Attack. Balanced: the plan (or the old rule).
     */
    static String stance(boolean plan, boolean bias, int build, int att, int str, int def, boolean coin) {
        if (bias && build == BUILD_STRENGTH) {
            if (def < att - 15) return "defence";
            if (str < att + 10) return "strength";
            return plan ? planStance(att, str - 10, def, coin) : "attack";
        }
        if (bias && build == BUILD_TANK) {
            if (def < att) return "defence";
            return plan ? planStance(att, str, def, coin) : oldStance(att, str, def, coin);
        }
        return plan ? planStance(att, str, def, coin) : oldStance(att, str, def, coin);
    }
    static void countStance(String st, int build, boolean bias) {
        int i = "attack".equals(st) ? 0 : "strength".equals(st) ? 1 : "defence".equals(st) ? 2 : 3;
        synchronized (stanceCount) { stanceCount[i]++; if (bias) buildCount[clamp(build, 0, 2)]++; }
    }

    // ------------------------------------------------------------------ Defence: bots.style.defShare
    static final boolean DEF_SHARE = flag("bots.style.defShare");
    static volatile int defLong, defBack;

    /** A ranger's stance: "longrange" (Ranged + Defence XP, the engine's RangeSwingHandler) while Defence is 10+ under Ranged, else "rangeaccurate". */
    static String rangerStance(int def, int ranged) { return def < ranged - 10 ? "longrange" : "rangeaccurate"; }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return CRAFTER2 || STALLS || PRAYER_MARKET || STANCE_PLAN || BUILD_BIAS || DEF_SHARE; }
    static String flags() {
        StringBuilder b = new StringBuilder();
        if (CRAFTER2) b.append("crafter2 "); if (STALLS) b.append("stalls "); if (PRAYER_MARKET) b.append("boneMarket ");
        if (STANCE_PLAN) b.append("stancePlan "); if (BUILD_BIAS) b.append("buildBias "); if (DEF_SHARE) b.append("defShare ");
        return b.toString().trim();
    }
    static String line() {
        int[] s, bc; synchronized (stanceCount) { s = stanceCount.clone(); bc = buildCount.clone(); }
        return "[W5b] on=[" + flags() + "] craftKeepCut=" + craftKeepCut + " craftIdle=" + craftIdleLogs + " stallFull=" + stallFull + " stallLowCombat=" + stallLowCombat
                + " boneMarket=" + marketRuns + " boneDragon=" + marketDragon + " boneCeil=" + marketCeil
                + " stanceAtt=" + s[0] + " stanceStr=" + s[1] + " stanceDef=" + s[2] + " buildStr=" + bc[1] + " buildBal=" + bc[0] + " buildTank=" + bc[2]
                + " defLong=" + defLong + " defBack=" + defBack;
    }
}
