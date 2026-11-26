package content.custom;

/**
 * W3 skills, wave 1 (the design notes): nine fixes for bot skills that are built but broken or never
 * chosen. Every fix has its own -D flag, default OFF; with a flag off its caller runs train-59's code unchanged.
 *
 * Review 11-19: Artisans holds a recipe's trade in upper case ("HERBLORE"), so the trade checks here ignore case.
 *
 * This class holds only the flags, the pure decision rules and the counters, so plain java can test it without a world
 * (TestW3Skills). Nothing here touches a server class: the callers (Batch29, Artisans, SmartAdventurers, P87Fight) pass
 * plain numbers in and act on the answer.
 *
 *   -Dbots.chains.smeltFix      1 miner smelt chain: a smelt chain is worth it when bars are scarce or ore is in glut
 *   -Dartisans.herbFix          2 herbalist inputs: shop staples no longer block a plan; herb chain goods are not held back
 *   -Dbots.summoning.wwSeed     3 Wolf Whistle done + first charms for a share of veterans (needs bots.summoning)
 *   -Dbots.jobShare             4 thieving capped near 15% of bots; log jobs get a floor weight until 8% cut wood
 *   -Dartisans.arrowFix         5 fletcher arrows: arrow chain goods are not held back; arrow stock target raised
 *   -Dbots.fight.cheapFood      6 a poor bot may take a low hunt on the cheapest food its whole purse buys
 *   -Dbots.style.restock        7 ranger/mage: last-chance arrows, runes or a shortbow before the melee fallback
 *   -Dbots.plankFix             8 plank run gates lowered (9k coins, 1 in 3, 27 oak in stock)
 *   -Dbots.agility.laps         9 Gnome course laps by level (10 to 30) so a visit can gain a level
 */
final class W3Skills {
    private W3Skills() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }
    static double dbl(String k, double d) { try { String v = System.getProperty(k); return v == null || v.trim().isEmpty() ? d : Double.parseDouble(v.trim()); } catch (Throwable t) { return d; } }

    // ------------------------------------------------------------------ 1 miner smelt chain
    static final boolean SMELT_FIX = flag("bots.chains.smeltFix");
    static volatile int smeltScarce;

    /**
     * Batch29.Chains.demand for a SMELT chain. train-59 asks for the bars to be worth 20% more than the ore, which copper,
     * tin and iron never are (s59: demand 0.8 for all three, 0 smelt chains in 613 trips). With the fix a chain is also
     * worth it when the smiths are waiting on the bar, the bar stock is under half its target, or the ore is in glut,
     * as long as smelting keeps at least half the ore's value (iron smelts at 50%).
     * prod = value of the bars one ore set makes, raw = value of that ore set.
     */
    static double smeltDemand(boolean fix, double prod, double raw, int smithsShort, int barStock, int barTarget, boolean oreGlut) {
        if (raw <= 0) return 0.8;
        if (prod >= raw * 1.2) return 1.25;
        if (!fix) return 0.8;
        boolean scarce = smithsShort > 0 || barTarget > 0 && barStock * 2 < barTarget || oreGlut;
        return scarce && prod * 2 >= raw ? 1.25 : 0.8;
    }

    // ------------------------------------------------------------------ 2 herbalist inputs, 5 fletcher arrows
    static final boolean HERB_FIX = flag("artisans.herbFix");
    static final boolean ARROW_FIX = flag("artisans.arrowFix");
    static final int ARROW_TARGET = Integer.getInteger("artisans.arrowFix.target", 400);
    static final int EYE_OF_NEWT = 221;
    static volatile int stapleSkips, keepZero, herbIdleLogs;
    static volatile long herbIdleAt;

    static boolean grimy(int id) { return id >= 199 && id <= 219 && (id & 1) == 1 || id == 2485 || id == 3049 || id == 3051 || id == 12174; }
    static boolean cleanHerb(int id) { return id >= 249 && id <= 269 && (id & 1) == 1 || id == 2481 || id == 2998 || id == 3000 || id == 12172; }
    static boolean unfPotion(int id) { return id >= 91 && id <= 111 && (id & 1) == 1 || id == 2483 || id == 3002 || id == 3004 || id == 12181; }
    /** Goods only the herbalist uses on the way to a potion. */
    static boolean herbChain(int id) { return grimy(id) || cleanHerb(id) || unfPotion(id); }
    /** Goods only the fletcher uses on the way to an arrow: shafts, headless arrows, arrowtips bronze to rune. */
    static boolean arrowChain(int id) { return id == 52 || id == 53 || id >= 39 && id <= 44; }

    /**
     * How much of an input the planner leaves on the Exchange (Artisans.KEEP_INPUT, 50). The reserve is there so players
     * can still buy raw goods, but half-made goods come in batches of 15 to 30 and never pass 50 + one batch, so the
     * next step never starts (s59: headless arrows and mithril arrowtips made, 0 arrows; herbalist 0 batches).
     */
    static int keep(boolean herbFix, boolean arrowFix, String trade, int inputId, int keepInput) {
        if (herbFix && "herblore".equalsIgnoreCase(trade) && herbChain(inputId)) return 0;
        if (arrowFix && "fletching".equalsIgnoreCase(trade) && arrowChain(inputId)) return 0;
        return keepInput;
    }
    static int keep(String trade, int inputId, int keepInput) { int k = keep(HERB_FIX, ARROW_FIX, trade, inputId, keepInput); if (k != keepInput) keepZero++; return k; }

    /**
     * A shop staple (vial of water; eye of newt with the fix) never limits a herblore plan: buyInputs buys what the
     * Exchange lacks from a shop and the coins leave the game. train-59's planner counted Exchange stock only, so a
     * potion with no vials on the Exchange was never planned.
     */
    static boolean stapleFree(boolean herbFix, String trade, int inputId, boolean staple) {
        return herbFix && "herblore".equalsIgnoreCase(trade) && (staple || inputId == EYE_OF_NEWT);
    }
    static boolean staple(String trade, int inputId, boolean staple) { return staple || HERB_FIX && "herblore".equalsIgnoreCase(trade) && inputId == EYE_OF_NEWT; }

    /** Arrows bronze to adamant: a ranger takes up to 200 a trip, so the stock target is two trips, not 150. */
    static int arrowTarget(boolean fix, int id, int base, int floor) {
        return fix && id >= 882 && id <= 890 && (id & 1) == 0 ? Math.max(base, floor) : base;
    }

    /** At most one "herbalist idle" line every 30 minutes. */
    static boolean herbIdleDue(long now) { if (now - herbIdleAt < 30 * 60_000L) return false; herbIdleAt = now; herbIdleLogs++; return true; }

    // ------------------------------------------------------------------ 3 Wolf Whistle seed
    static final boolean WW_SEED = flag("bots.summoning.wwSeed");
    static final int WW_SHARE = Math.max(0, Math.min(100, Integer.getInteger("bots.summoning.wwSeed.share", 34)));
    static final int WW_GOLD_VET = 25, WW_GOLD_HIGH = 40;
    /** Review 11-19: the seed hands out no items unless this is set too (-Dbots.summoning.wwSeed.charms, default off): charms made from nothing, untradeable. Off: a seeded bot keeps the charms its kills drop. */
    static final boolean WW_CHARMS = flag("bots.summoning.wwSeed.charms");
    static final double WW_XP = 276; // the quest's own Summoning reward
    static volatile int wwSeeded, wwAlready, wwSkipped;

    /** A stable pick by name, so the same bots are seeded after every restart: share percent of veterans and high tier. */
    static boolean wwPick(String name, boolean veteran, boolean high, int sharePct) {
        if (name == null || !(veteran || high) || sharePct <= 0) return false;
        int h = 7; for (char c : name.toLowerCase(java.util.Locale.ROOT).toCharArray()) h = h * 31 + c;
        return Math.floorMod(h, 100) < sharePct;
    }
    static int wwCharms(boolean high) { return wwCharms(WW_CHARMS, high); }
    static int wwCharms(boolean charms, boolean high) { return !charms ? 0 : high ? WW_GOLD_HIGH : WW_GOLD_VET; }

    // ------------------------------------------------------------------ 4 job share
    static final boolean SHARE_ON = flag("bots.jobShare");
    static final double THIEVE_CAP = dbl("bots.jobShare.thieveCap", 0.15), LOG_WANT = dbl("bots.jobShare.logWant", 0.08), LOG_FLOOR = dbl("bots.jobShare.logFloor", 0.6);
    static volatile int thieveDamped, logFloored;
    static volatile long shareAt; static volatile double thieveNow, logNow;
    // w4-jobshare: the shares are of the last WINDOW smart-bot job starts (the plan's own measure: 255 of ~520 starts thieving),
    // not of bots on a runner right now: a pickpocket trip lasts about a minute, so 2-7 of 60 bots thieve at once and 15% never came.
    static final int SHARE_WINDOW = Math.max(20, Integer.getInteger("bots.jobShare.window", 100)), SHARE_MIN = 20;
    static final byte[] START_RING = new byte[SHARE_WINDOW]; static int startCount;
    static volatile int sharePicks; static volatile long shareLogAt; static final long SHARE_LOG_MS = 30_000;

    /** One smart-bot job start: 1 pickpocketing, 2 woodcutting, 0 anything else. */
    static synchronized void noteStart(int kind) { START_RING[startCount % START_RING.length] = (byte) kind; startCount++; }
    static synchronized double[] startShares() { return startShares(START_RING, startCount, SHARE_MIN); }
    /** {thieving share, woodcutting share} of the last ring.length starts; {0, 0} until min starts are in (nothing damped at boot). */
    static double[] startShares(byte[] ring, int count, int min) {
        int n = Math.min(count, ring.length); if (n < min) return new double[]{0, 0};
        int th = 0, tr = 0; for (int i = 0; i < n; i++) { if (ring[i] == 1) th++; else if (ring[i] == 2) tr++; }
        return new double[]{th / (double) n, tr / (double) n};
    }
    /** Why the rule changed this pick, or null: the pick is a log job the floor lifted, or the undamped best was thieving and the damp moved it off thieving. */
    static String shareWhy(boolean bestFloored, boolean rawThieveElse, boolean bestThieve, double thieveShare, double cap) {
        if (bestFloored) return "log floor";
        if (rawThieveElse && !bestThieve && thieveShare >= cap) return "thieving damped";
        return null;
    }
    /** Counts every changed pick; prints at most one line per 30 s, with the running count so a pair can read the total from the last line. */
    static void sharePick(String bot, String took, String over, String why) {
        sharePicks++; long now = System.currentTimeMillis(); if (now < shareLogAt) return; shareLogAt = now + SHARE_LOG_MS;
        System.out.println("[JobShare] " + bot + " takes " + took + " over " + over + " (" + why + ", thieveShare=" + String.format(java.util.Locale.ROOT, "%.2f", thieveNow) + " logShare=" + String.format(java.util.Locale.ROOT, "%.2f", logNow) + ") picks=" + sharePicks);
    }

    /** x0.1 on a pickpocket job while the cap share of bots already thieve (s59: 255 of about 520 job starts). Not a skip: a bot with nothing else still earns. */
    static double thieveFactor(boolean on, double thieveShare, double cap) { return on && thieveShare >= cap ? 0.1 : 1.0; }
    /**
     * W9 guards2 (-Dbots.jobShare.brokeCoins, default true; read only under -Dbots.jobShare): a broke bot's coin pickpocket job (men, farmers,
     * warriors, guards, knights) is not damped. g61/g61b: the damp sent broke bots from farmers (9 gp a pocket) to onions, potatoes and
     * cabbages; thieving trips 157 vs 322 (OFF), pockets 2850 vs 5733, about 32k gp less coin income for the broke bots over the pair.
     * Wool, seeds and every non-broke bot's thieving are damped as before. False = the g61 rule (broke bots damped too).
     */
    static final boolean BROKE_COINS = !"false".equals(System.getProperty("bots.jobShare.brokeCoins"));
    static volatile int brokeKept;
    /** True: leave this pickpocket job undamped (the rule on, the bot broke and the job pays coins). */
    static boolean brokeCoinKeep(boolean on, boolean broke, boolean coinJob) { return on && broke && coinJob; }

    /** A log job's weight: at least the floor while fewer than the wanted share cut wood (s59: 4 woodcutting starts in 3 h; logs in glut score 0). */
    static double logWeight(boolean on, double weight, boolean treeJob, double logShare, double want, double floor) {
        return on && treeJob && logShare < want ? Math.max(weight, floor) : weight;
    }

    // ------------------------------------------------------------------ 6 cheap food
    static final boolean CHEAP_FOOD = flag("bots.fight.cheapFood");
    static final int CHEAP_MAX_LEVEL = Integer.getInteger("bots.fight.cheapFood.maxLevel", 45), CHEAP_RESERVE = 20;
    static volatile int cheapOpened, cheapBought;

    /**
     * P87Fight.gate closes a hunt unless a quarter of the purse buys the missing food at 25 gp each (400 gp for 4), while
     * buyFood would spend the whole purse. With the fix a hunt of a low monster opens when the purse, less 20 gp, buys
     * the missing food at the cheapest shop price (s59: 53 hunts closed, most "held 0, need 6" at level 21-42 monsters).
     */
    static boolean cheapGate(boolean on, int npcLevel, int maxLevel, int held, int need, long coins, int cheapPrice) {
        if (!on || need <= 0 || npcLevel > maxLevel) return false;
        int missing = need - 2 - held;
        if (missing <= 0) return true;
        return coins - CHEAP_RESERVE >= (long) missing * Math.max(1, cheapPrice);
    }

    /** How many of the cheapest food a poor bot buys when the normal buyer could not pay for the full carry: what the purse less 20 gp allows, 0 under the gate's need. */
    static int cheapQty(int want, int minNeed, long coins, int cheapPrice) {
        long n = Math.min(want, (coins - CHEAP_RESERVE) / Math.max(1, cheapPrice));
        return n >= Math.max(1, minNeed) ? (int) n : 0;
    }

    // ------------------------------------------------------------------ 7 ranger / mage restock
    static final boolean RESTOCK = flag("bots.style.restock");
    static final int RESTOCK_RESERVE = 100, RESTOCK_MIN = 40, RESTOCK_ARROWS = 200, RESTOCK_CASTS = 150;
    static volatile int restockArrows, restockRunes, restockBows, restockNone;

    /** Units (arrows or casts) a last-chance restock buys: what the purse less 100 gp pays for, capped; 0 under 40 (a trip on less is a melee trip anyway). */
    static int restockQty(long coins, long unitPrice, int cap) {
        long n = Math.min(cap, (coins - RESTOCK_RESERVE) / Math.max(1, unitPrice));
        return n >= RESTOCK_MIN ? (int) n : 0;
    }

    /** The best tier (index into prices, low to high, from top down) whose 40 units the purse pays for; -1 when not even the lowest. */
    static int restockTier(long coins, long[] unitPrices, int top) {
        for (int i = Math.min(top, unitPrices.length - 1); i >= 0; i--) if (restockQty(coins, unitPrices[i], RESTOCK_MIN) > 0) return i;
        return -1;
    }

    // ------------------------------------------------------------------ 8 plank run
    static final boolean PLANK_FIX = flag("bots.plankFix");
    static final int PLANK_COINS = Integer.getInteger("bots.plankFix.coins", 9_000);
    static volatile int plankRolls, plankNoLogs;

    /** builder(): 15,000 coins on train-59; 9,000 with the fix (27 oak planks cost about 27 x (log + 250) = 7,500 and the run keeps 1,500 over that, not 5,000). */
    static long plankCoins(boolean fix, long old) { return fix ? Math.min(old, PLANK_COINS) : old; }
    /** The trip-end roll: 1 in 5, 1 in 3 with the fix. */
    static int plankRoll(boolean fix) { return fix ? 3 : 5; }
    /** Oak logs the Exchange must hold before a builder buys 27: 40, or 27 with the fix. */
    static int plankStock(boolean fix) { return fix ? 27 : 40; }

    // ------------------------------------------------------------------ 9 agility laps
    static final boolean AGI_LAPS = flag("bots.agility.laps");
    static volatile int agiVisits, agiLapsRun;

    /** Laps a Gnome course visit runs. A lap is 86.5 XP: ten laps (865 XP) are under one level from level 28 up (s59: 31, 32 and 33 gained nothing). */
    static int lapsFor(boolean on, int level) {
        if (!on) return 10;
        return level < 10 ? 10 : level < 20 ? 15 : level < 28 ? 20 : 30;
    }
    /** Minutes a visit may take: 45 for ten laps, 2.5 more per extra lap, 95 at most. */
    static int agiMinutes(int laps) { return Math.min(95, 45 + (int) Math.ceil(Math.max(0, laps - 10) * 2.5)); }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return SMELT_FIX || HERB_FIX || ARROW_FIX || WW_SEED || SHARE_ON || CHEAP_FOOD || RESTOCK || PLANK_FIX || AGI_LAPS; }
    static String flags() {
        StringBuilder b = new StringBuilder();
        if (SMELT_FIX) b.append("smeltFix "); if (HERB_FIX) b.append("herbFix "); if (WW_SEED) b.append("wwSeed "); if (SHARE_ON) b.append("jobShare ");
        if (ARROW_FIX) b.append("arrowFix "); if (CHEAP_FOOD) b.append("cheapFood "); if (RESTOCK) b.append("restock "); if (PLANK_FIX) b.append("plankFix "); if (AGI_LAPS) b.append("agiLaps ");
        return b.toString().trim();
    }
    static String line() {
        return "[W3] on=[" + flags() + "] smeltScarce=" + smeltScarce + " stapleSkips=" + stapleSkips + " keepZero=" + keepZero + " herbIdle=" + herbIdleLogs
                + " wwSeeded=" + wwSeeded + " wwAlready=" + wwAlready + " thieveDamped=" + thieveDamped + " logFloored=" + logFloored + " sharePicks=" + sharePicks + (SHARE_ON ? " brokeKept=" + brokeKept : "")
                + " thieveShare=" + String.format(java.util.Locale.ROOT, "%.2f", thieveNow) + " logShare=" + String.format(java.util.Locale.ROOT, "%.2f", logNow)
                + " cheapOpened=" + cheapOpened + " cheapBought=" + cheapBought + " restockArrows=" + restockArrows + " restockRunes=" + restockRunes + " restockBows=" + restockBows + " restockNone=" + restockNone
                + " plankRolls=" + plankRolls + " plankNoLogs=" + plankNoLogs + " agiVisits=" + agiVisits + " agiLaps=" + agiLapsRun;
    }
}
