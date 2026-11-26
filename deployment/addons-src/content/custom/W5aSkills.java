package content.custom;

/**
 * W5 skills, wave 2 lane A (the design notes section 2): five fixes that close economy loops for
 * Fishing, Cooking, Firemaking and Mining. Every fix has its own -D flag, default OFF; with a flag off its caller runs
 * train-60's code unchanged.
 *
 *   -Dbots.fish.shark    sharks at Fishing 76 (own job row, the Catherby Net/Harpoon spots, cap 2); the harpoon row stops
 *                        counting the shark bounty it could never catch (that bounty is why cage lost every pick)
 *   -Dbots.cook.burn     a bot's simulated cooking burns at the engine's rate (CookableItems low/high,
 *                        RandomFunction.getSkillSuccessChance), and a burnt fish gives no xp
 *   -Dbots.cook.fire     a fisher far from a range lights a fire from a log in its pack and cooks there
 *   -Dbots.fire.line     a bot at Varrock west / Draynor bank buys a pack of a surplus log from bot stock (paid) and
 *                        burns it in a line with the players' bank-loop BURN runner (2 bots a bank, logs an hour capped)
 *   -Dbots.mine.power    an under-60 miner mines iron in drop mode while iron ore is in glut (xp only; the ore vanishes)
 *
 * This class holds only the flags, the pure rules and the counters, so plain java can test it without a world
 * (TestW5aSkills). Nothing here touches a server class.
 */
final class W5aSkills {
    private W5aSkills() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }

    // ------------------------------------------------------------------ Fishing: sharks, harpoon and cage
    static final boolean SHARK = flag("bots.fish.shark");
    static final int SHARK_LEVEL = 76, HARPOON_LEVEL = 35, RAW_SHARK = 383;
    static final int SHARK_CAP = Math.max(1, Integer.getInteger("bots.fish.shark.cap", 2));
    /** What each harpoon row catches with the flag on: tuna and swordfish on the Cage/Harpoon spots; shark on the Net/Harpoon ones. */
    static final int[] HARPOON_FISH = {359, 371}, SHARK_FISH = {RAW_SHARK};
    /** Runner spot rule: 0 any spot with the option (train-60), 1 only the Net/Harpoon (big net) spots, 2 never those. */
    static final int SPOT_ANY = 0, SPOT_BIGNET = 1, SPOT_NOT_BIGNET = 2;
    static volatile int sharkStarts, harpoonStarts, cageStarts;

    /** The fish a harpoon job row weighs and carries: flag off the task's whole list; on, per row. */
    static int[] harpoonFish(boolean on, boolean sharkRow, int[] taskFish) { return !on ? taskFish : sharkRow ? SHARK_FISH : HARPOON_FISH; }
    /** Which fishing spots a harpoon runner may use. */
    static int spotRule(boolean on, boolean sharkRow) { return !on ? SPOT_ANY : sharkRow ? SPOT_BIGNET : SPOT_NOT_BIGNET; }
    /** Brain.jobWeight's gathering rule: the best bounty over the fish, surplus ones count 0. */
    static double fishWeight(int[] fish, java.util.function.IntToDoubleFunction bounty, java.util.function.IntPredicate surplus) {
        double w = 0;
        for (int id : fish) if (id != 995 && !surplus.test(id)) w = Math.max(w, bounty.applyAsDouble(id));
        return w;
    }
    /** Runner.nearestNpc: may this runner fish at a spot npc that is (or is not) a Net/Harpoon spot. */
    static boolean spotOk(int rule, boolean bignetSpot) { return rule == SPOT_ANY || (rule == SPOT_BIGNET) == bignetSpot; }

    // ------------------------------------------------------------------ Cooking: the engine's burn rate
    static final boolean COOK_BURN = flag("bots.cook.burn");
    static volatile int cookedN, burntN;
    /** raw -> {low, high, lowRange, highRange} from the engine's CookableItems (server.jar, dumped 11-19). low/high on a fire, *Range on a range. */
    static final java.util.Map<Integer, int[]> BURN = new java.util.HashMap<>();
    static {
        int[][] t = {
            {317, 128, 512, 128, 512}, {321, 128, 512, 128, 512}, {327, 118, 492, 118, 492}, {345, 108, 472, 108, 472},
            {353, 98, 452, 98, 452}, {335, 88, 432, 88, 432}, {341, 83, 422, 88, 432}, {349, 78, 412, 78, 412},
            {331, 68, 392, 68, 392}, {359, 58, 372, 58, 372}, {377, 38, 332, 38, 332}, {363, 33, 312, 33, 312},
            {371, 18, 292, 30, 310}, {7944, 11, 275, 13, 280}, {383, 1, 202, 1, 232}};
        for (int[] x : t) BURN.put(x[0], new int[]{x[1], x[2], x[3], x[4]});
    }
    /** The engine's RandomFunction.getSkillSuccessChance (integer steps, capped 0..100). */
    static double successChance(int low, int high, int level) {
        double v = (low * (99 - level) / 98 + high * (level - 1) / 98 + 1) * 100.0 / 256.0;
        return Math.max(0, Math.min(100, v));
    }
    /** Percent chance a raw fish cooks (not burns) at this Cooking level, on a fire or a range; -1 when the engine table has no row. */
    static double cookChance(int raw, int level, boolean fire) {
        int[] c = BURN.get(raw);
        if (c == null) return -1;
        return fire ? successChance(c[0], c[1], level) : successChance(c[2], c[3], level);
    }
    /** train-60's bot rule (35% less one per level over the requirement); the flag on uses the engine row where there is one. roll100 in [0,100), roll100int in [0,100). */
    static boolean burnt(boolean on, int raw, int level, int req, boolean fire, double roll100, int roll100int) {
        if (on) { double ch = cookChance(raw, level, fire); if (ch >= 0) return roll100 > ch; }
        return roll100int < Math.max(0, 35 - (level - req));
    }

    // ------------------------------------------------------------------ Cooking + Firemaking: a fire by a remote spot
    static final boolean COOK_FIRE = flag("bots.cook.fire");
    /** A range closer than this (tiles) is used as before; farther, the fisher lights a fire. */
    static final int FIRE_MIN = Integer.getInteger("bots.cook.fire.minDist", 45);
    static volatile int firesLit, fireKits, fireNoKit;
    /** The cook stop: a fire when the range is far (or none), the pack holds a tinderbox and a log it can light, and the tile takes a fire. */
    static boolean fireOrRange(boolean on, double rangeDist, boolean tinder, boolean log, boolean tileOk) {
        return on && tinder && log && tileOk && rangeDist > FIRE_MIN;
    }
    /** At the fish job start: carry a kit only for a cooking trip whose spot is far from every range. */
    static boolean wantKit(boolean on, boolean cookTrip, double spotToRange) { return on && cookTrip && spotToRange > FIRE_MIN; }

    // ------------------------------------------------------------------ Firemaking: a pack of surplus logs burnt in a line
    static final boolean FIRE_LINE = flag("bots.fire.line");
    /** W32 (-Dbots.fm.moreSpots, default off; needs bots.fire.line): three more line spots (Falador east, Edgeville, Varrock east) and
     *  roomier default caps: 3 bots a bank (was 2), 405 logs an hour (was 270). An explicit perHour / perBank property still wins. */
    static final boolean MORE_SPOTS = flag("bots.fm.moreSpots");
    static final int LINE_RADIUS = 40;
    static volatile int lineNewSpot;
    /** The nearest line spot within the radius (index into dist), -1 when none. */
    static int lineNear(double[] dist, int radius) { int b = -1; for (int i = 0; i < dist.length; i++) if (dist[i] <= radius && (b < 0 || dist[i] < dist[b])) b = i; return b; }
    static final int LINE_HOUR = Math.max(0, Integer.getInteger("bots.fire.line.perHour", MORE_SPOTS ? 405 : 270)); // logs the whole server burns an hour (ten packs)
    static final int LINE_BANK_CAP = Math.max(1, Integer.getInteger("bots.fire.line.perBank", MORE_SPOTS ? 3 : 2)); // bots burning at one bank at once
    static final int LINE_PACK = 27, LINE_STOCK_LEFT = 50; // one pack (and the tinderbox); never take the Exchange below 50
    static final long LINE_GAP_MS = 90 * 60_000L; // per bot
    static volatile int lineStarts, lineLogs, lineRefused, lineCapped, lineNoSpot, lineNoLog;
    static volatile long lineHourAt; static volatile int lineHourLogs;
    /** {log id, Firemaking level}, best first (the bots' FIRE_LOGS). */
    static final int[][] LINE_LOGS = {{1515, 60}, {1517, 45}, {1519, 30}, {1521, 15}, {1511, 1}};
    /** a61 review: stock a line may burn: what sits over the market's target (MarketBrain.target), less a floor of 50, never below it.
     *  (train-61 asked for MarketBrain.isSurplus, 3x the target; the flood guard trims there and no log reached it in a61: 0 lines.) */
    static int lineOver(int stock, int target) { return Math.max(0, stock - Math.max(target, LINE_STOCK_LEFT)); }
    /** The best log this bot can light with a pack over its target; 0 when none. */
    static int lineLog(int fm, int[] stock, int[] target) {
        for (int i = 0; i < LINE_LOGS.length; i++) if (fm >= LINE_LOGS[i][1] && lineOver(stock[i], target[i]) >= LINE_PACK) return LINE_LOGS[i][0];
        return 0;
    }
    /** How many to buy: a pack, cut to the hour's room and to a quarter of the purse; 0 = no trip (under 5 is not a line). */
    static int lineQty(int hourUsed, long coins, int unit) {
        int n = Math.min(LINE_PACK, LINE_HOUR - hourUsed);
        n = (int) Math.min(n, coins / 4 / Math.max(1, unit));
        return n < 5 ? 0 : n;
    }
    /** A seat at this bank: fewer than LINE_BANK_CAP bots are burning there. */
    static boolean lineSeat(int burningHere) { return burningHere < LINE_BANK_CAP; }
    /** The server-wide hour: logs used so far this hour (resets an hour after its first line). */
    static synchronized void lineUse(int n) { lineHourLogs += n; }
    static synchronized int lineHourUsed(long now) { if (now - lineHourAt > 3_600_000L) { lineHourAt = now; lineHourLogs = 0; } return lineHourLogs; }

    // ------------------------------------------------------------------ Mining: power-mine iron in a glut
    static final boolean MINE_POWER = flag("bots.mine.power");
    static final int IRON_ORE = 440, POWER_MAX_LEVEL = 60;
    static final double POWER_WEIGHT = 0.3; // under a typical wanted item's bounty: a power trip only wins when little else pays
    static volatile int powerStarts, powerDropped;
    static volatile int powerNoGlut;
    /** a61 review: iron is "in glut" for this rule once its stock reaches the market's target (its bounty is then 0, so the job
     *  had weight 0). train-61 asked for MarketBrain.isSurplus (3x the target), which iron never reached in a61: 0 power trips. */
    static boolean ironGlut(int stock, int target) { return target > 0 && stock >= target; }
    /** Power mode: iron ore in glut and the miner under 60. */
    static boolean power(boolean on, boolean ironSurplus, int miningLevel) { return on && ironSurplus && miningLevel < POWER_MAX_LEVEL; }
    /** chooseJob: an iron job whose weight the glut zeroed gets the power weight, for a miner it suits. */
    static double powerWeight(boolean on, boolean ironJob, double weight, boolean ironSurplus, int miningLevel) {
        return ironJob && weight <= 0 && power(on, ironSurplus, miningLevel) ? POWER_WEIGHT : weight;
    }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return SHARK || COOK_BURN || COOK_FIRE || FIRE_LINE || MINE_POWER; }
    static String flags() {
        StringBuilder b = new StringBuilder();
        if (SHARK) b.append("fishShark "); if (COOK_BURN) b.append("cookBurn "); if (COOK_FIRE) b.append("cookFire "); if (FIRE_LINE) b.append("fireLine "); if (MINE_POWER) b.append("minePower ");
        return b.toString().trim();
    }
    static String line() {
        return "[W5a] on=[" + flags() + "] sharkStarts=" + sharkStarts + " harpoonStarts=" + harpoonStarts + " cageStarts=" + cageStarts
                + " cooked=" + cookedN + " burnt=" + burntN + " firesLit=" + firesLit + " fireKits=" + fireKits + " fireNoKit=" + fireNoKit
                + " lineStarts=" + lineStarts + " lineLogs=" + lineLogs + " lineRefused=" + lineRefused + " lineCapped=" + lineCapped + " lineNoSpot=" + lineNoSpot + " lineNoLog=" + lineNoLog + (FIRE_LINE && MORE_SPOTS ? " lineNewSpot=" + lineNewSpot : "")
                + " powerStarts=" + powerStarts + " powerDropped=" + powerDropped + " powerNoGlut=" + powerNoGlut;
    }
}
