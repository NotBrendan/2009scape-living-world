package content.custom;

/**
 * W6 skills, wave 3 combat lane (the design notes section 3: Prayer, Ranged, Summoning rows). Three
 * -D flags, default OFF; with a flag off its caller runs train-61's code unchanged (same random draws, same tables).
 *
 *   -Dbots.prayer.stat         a bot with Prayer 13+ keeps the best stat prayers of its combat style on while it fights
 *                              (strength 13/31, attack 16/34, ranged 13/26/44, magic 13/27/45), inside a drain budget, and
 *                              prays at a church altar that lies on its walk back to the bank (W6Fight)
 *   -Dbots.ranged.kit2         rune arrows, the magic shortbow and blue/red/black dragonhide in the ranger tables; the top
 *                              arrow only from bot stock, capped per trip; the quiver's own arrows off the ground before a
 *                              full pack walks to the bank
 *   -Dbots.summoning.wwQuest   Wolf Whistle's quest weight x4 for combat bots (combat 20+), so the quest is the way into
 *                              Summoning (bots.summoning.wwSeed / .charms are not touched)
 *
 * This class holds only the flags, the pure rules and the counters, so plain java can test it without a world
 * (TestW6Combat). Nothing here touches a server class; the engine calls are in W6Fight.
 */
final class W6Combat {
    private W6Combat() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }

    // ------------------------------------------------------------------ Prayer: stat prayers in fights
    static final boolean PRAYER_STAT = flag("bots.prayer.stat");
    static final int MELEE = 0, RANGED = 1, MAGIC = 2;
    /** Under this Prayer level a bot uses no stat prayer (the plan's 13/31/34+ bots). */
    static final int STAT_MIN = 13;
    /** Ticks the prayers stay on after the last blow, so they are not toggled between two kills. */
    static final int LINGER = Math.max(0, Integer.getInteger("bots.prayer.stat.linger", 8));
    /** {engine PrayerType name, Prayer level}, best first, per row: melee strength, melee attack, ranged, magic. */
    static final String[][] STAT_NAMES = {
        {"ULTIMATE_STRENGTH", "SUPERHUMAN_STRENGTH"}, {"INCREDIBLE_REFLEXES", "IMPROVED_REFLEXES"},
        {"EAGLE_EYE", "HAWK_EYE", "SHARP_EYE"}, {"MYSTIC_MIGHT", "MYSTIC_LORE", "MYSTIC_WILL"}};
    static final int[][] STAT_LEVELS = {{31, 13}, {34, 16}, {44, 26, 8}, {45, 27, 9}};
    /** Every name above, for switching off. */
    static final String[] ALL_STAT = {"ULTIMATE_STRENGTH", "SUPERHUMAN_STRENGTH", "INCREDIBLE_REFLEXES", "IMPROVED_REFLEXES", "EAGLE_EYE", "HAWK_EYE", "SHARP_EYE", "MYSTIC_MIGHT", "MYSTIC_LORE", "MYSTIC_WILL"};
    static volatile int prayTrips, prayFights, altarVisits, altarMiss, altarGaveUp, altarFallback;
    static volatile long prayUsedTenths;

    static String best(int row, int prayer) { for (int i = 0; i < STAT_NAMES[row].length; i++) if (prayer >= STAT_LEVELS[row][i]) return STAT_NAMES[row][i]; return null; }

    /** The stat prayers for a style at a Prayer level: melee one strength + one attack prayer, ranged or magic one; none under 13. */
    static String[] statPrayers(int style, int prayer) {
        if (prayer < STAT_MIN) return new String[0];
        String a = best(style == RANGED ? 2 : style == MAGIC ? 3 : 0, prayer), b = style == MELEE ? best(1, prayer) : null;
        return a == null ? new String[0] : b == null ? new String[]{a} : new String[]{a, b};
    }

    /** Points the stat prayers never spend: half the level for a bot that also needs its protection prayer, else none. */
    static double reserve(int prayer, boolean protects) { return protects ? prayer / 2.0 : 0; }

    /**
     * The drain budget: stat prayers are on only in a fight (or LINGER ticks after), only while the points are above the
     * reserve, and once they went off for lack of points they come back only 3 points above it (no flicker at the line).
     */
    static boolean prayNow(boolean on, boolean fighting, double points, int prayer, boolean protects) {
        if (prayer < STAT_MIN || !fighting) return false;
        double floor = reserve(prayer, protects);
        return on ? points >= floor + 1 : points >= floor + 3;
    }

    // ------------------------------------------------------------------ Prayer: altars on the way back
    /** Church altars {x, y} (a tile in the nave; the altar itself is found in the world within 8 tiles): Lumbridge, Varrock (routes checked offline, p38-navport), Port Sarim, East Ardougne (cell from the survey's place:altar). -Dbots.prayer.stat.altars=x,y;x,y replaces the list. */
    static final int[][] ALTARS = altars(System.getProperty("bots.prayer.stat.altars"), new int[][]{{3243, 3209}, {3254, 3482}, {2996, 3178}, {2612, 3308}});
    /** A bot prays at an altar when its points are under this share of its level. */
    static final double ALTAR_BELOW = 0.5;
    /** Longest walk to the altar, and the most extra tiles over the straight walk to the bank. */
    static final int ALTAR_LEG = Math.max(1, Integer.getInteger("bots.prayer.stat.altarLeg", 60)), ALTAR_DETOUR = Math.max(0, Integer.getInteger("bots.prayer.stat.altarDetour", 40));
    /** Runner ticks before an altar walk is given up, and the misses after which an altar row is left alone until the restart. */
    static final int ALTAR_TICKS = 120, ALTAR_BAD = 3;
    static final int[] ALTAR_MISSES = new int[ALTARS.length];

    static int[][] altars(String prop, int[][] def) {
        if (prop == null || prop.trim().isEmpty()) return def;
        try {
            String[] rows = prop.split(";"); int[][] out = new int[rows.length][];
            for (int i = 0; i < rows.length; i++) { String[] xy = rows[i].trim().split(","); out[i] = new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())}; }
            return out;
        } catch (RuntimeException e) { return def; }
    }

    static double dist(int ax, int ay, int bx, int by) { return Math.sqrt((double) (ax - bx) * (ax - bx) + (double) (ay - by) * (ay - by)); }
    static boolean needsAltar(double points, int prayer) { return prayer >= STAT_MIN && points < prayer * ALTAR_BELOW; }

    /** The altar on the way from (x,y) to the bank (bx,by): within ALTAR_LEG tiles and at most ALTAR_DETOUR extra; the smallest detour wins. -1 none. */
    static int altarPick(int x, int y, int bx, int by, int[][] table, int[] misses, int maxLeg, int maxDetour) {
        int best = -1; double bd = Double.MAX_VALUE, direct = dist(x, y, bx, by);
        for (int i = 0; i < table.length; i++) {
            if (misses != null && misses[i] >= ALTAR_BAD) continue;
            double leg = dist(x, y, table[i][0], table[i][1]), extra = leg + dist(table[i][0], table[i][1], bx, by) - direct;
            if (leg > maxLeg || extra > maxDetour) continue;
            if (extra < bd) { bd = extra; best = i; }
        }
        return best;
    }

    static void addUsed(double points) { if (points > 0) prayUsedTenths += Math.round(points * 10); }

    // ------------------------------------------------------------------ Ranged: kit tier 2
    static final boolean KIT2 = flag("bots.ranged.kit2");
    static final int RUNE_ARROW = 892, MAGIC_SHORTBOW = 861;
    /** The ranger tables with the flag on (SmartAdventurers.Brain BOWS/ARROWS take these; off they keep train-61's arrays). */
    static final int[] BOWS2 = {841, 843, 849, 853, 857, MAGIC_SHORTBOW}, BOW_LEVEL2 = {1, 5, 20, 30, 40, 50};
    static final int[] ARROWS2 = {882, 884, 886, 888, 890, RUNE_ARROW}, ARROW_LEVEL2 = {1, 1, 5, 20, 30, 40}, ARROW_MAX_BOW2 = {0, 0, 1, 2, 3, 4}; // rune arrows: yew or magic bows
    /** Bows a bot may pick with data/style5.off: the first four, as before (never the yew or the magic shortbow). */
    static final int NO_STYLE5_BOWS = 4;
    /** Dragonhide {body, chaps, vambraces} by tier: green, blue, red, black; Ranged 40/50/60/70 (the body also needs Defence 40, as the old rule). */
    static final int[][] HIDE = {{1135, 1099, 1065}, {2499, 2493, 2487}, {2501, 2495, 2489}, {2503, 2497, 2491}};
    static final int[] HIDE_LEVEL = {40, 50, 60, 70}, LEATHER = {1129, 1095, 1063};
    /** The top arrow per trip, and what the Exchange keeps of it: a ranger never buys the fletcher's rune arrows out. */
    static final int TOP_CAP = Math.max(40, Integer.getInteger("bots.ranged.kit2.topCap", 100)), TOP_FLOOR = Math.max(0, Integer.getInteger("bots.ranged.kit2.topFloor", 50));
    static volatile int topArrows, topStepDown, kitBought, sweepBack, arrowsBack;

    /** The hide piece (col 0 body, 1 chaps, 2 vambraces) for a Ranged level: the best tier on sale somewhere, else green (the old piece, on sale or not). */
    static int hidePick(int col, int range, java.util.function.IntPredicate onSale) {
        for (int t = HIDE.length - 1; t >= 1; t--) if (range >= HIDE_LEVEL[t] && onSale.test(HIDE[t][col])) return HIDE[t][col];
        return HIDE[0][col];
    }
    /** 0 leather, 1-4 the hide tiers, -1 anything else (never swapped out by this rule). */
    static int hideRank(int id) {
        for (int l : LEATHER) if (l == id) return 0;
        for (int t = 0; t < HIDE.length; t++) for (int h : HIDE[t]) if (h == id) return t + 1;
        return -1;
    }
    /** A worn leather or hide piece gives way to a better hide tier (train-61 swapped only metal). */
    static boolean hideUpgrade(int wornId, int wantId) { int a = hideRank(wornId), b = hideRank(wantId); return a >= 0 && b > a; }
    static boolean isKit2(int id) { return id == MAGIC_SHORTBOW || id == RUNE_ARROW || hideRank(id) >= 2; }

    /** The trip-arrow tier: the top arrow only when bot stock covers a trip's worth over the floor, else one tier down. */
    static int arrowTier(int tier, int[] arrows, int topStock) {
        if (tier > 0 && arrows[tier] == RUNE_ARROW && topStock < 40 + TOP_FLOOR) { topStepDown++; return tier - 1; }
        return tier;
    }
    /** Most arrows of one kind bought for a trip (train-61: 200). */
    static int tripCap(int arrow, int old) { return arrow == RUNE_ARROW ? Math.min(old, TOP_CAP) : old; }
    /** What one buy may take from Exchange stock: the top arrow leaves the floor. */
    static int takeCap(int arrow, int want, int stock) { return arrow == RUNE_ARROW ? Math.max(0, Math.min(Math.min(want, TOP_CAP), stock - TOP_FLOOR)) : want; }

    // ------------------------------------------------------------------ Summoning: Wolf Whistle by the quest
    static final boolean WW_QUEST = flag("bots.summoning.wwQuest");
    static final double WW_MULT = Math.max(1.0, num(System.getProperty("bots.summoning.wwQuest.mult"), 4.0));
    static double num(String v, double def) { try { return v == null ? def : Double.parseDouble(v.trim()); } catch (RuntimeException e) { return def; } }
    static final int WW_COMBAT = Math.max(3, Integer.getInteger("bots.summoning.wwQuest.combat", 20));
    static volatile int wwBoosted;

    /** Wolf Whistle's weight factor for a combat bot. mood is what -Dbots.summoning.mood already multiplied by (1 when off): the two never stack past WW_MULT. */
    static double wwFactor(int combat, double mood) { return combat < WW_COMBAT ? 1.0 : Math.max(1.0, WW_MULT / Math.max(1.0, mood)); }

    // ------------------------------------------------------------------ the hourly line
    static boolean anyOn() { return PRAYER_STAT || KIT2 || WW_QUEST; }
    static String flags() {
        StringBuilder b = new StringBuilder();
        if (PRAYER_STAT) b.append("prayerStat "); if (KIT2) b.append("rangedKit2 "); if (WW_QUEST) b.append("wwQuest ");
        return b.toString().trim();
    }
    static String line() {
        return "[W6c] on=[" + flags() + "] prayTrips=" + prayTrips + " prayFights=" + prayFights + " prayUsed=" + prayUsedTenths / 10
                + " altarVisits=" + altarVisits + " altarFallback=" + altarFallback + " altarMiss=" + altarMiss + " altarGaveUp=" + altarGaveUp
                + " topArrows=" + topArrows + " topStepDown=" + topStepDown + " kitBought=" + kitBought + " arrowsBack=" + arrowsBack + " sweepBack=" + sweepBack
                + " wwBoosted=" + wwBoosted;
    }
}
