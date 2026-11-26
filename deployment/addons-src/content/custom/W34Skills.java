package content.custom;

/**
 * W34 gaps (coverage-strict 2026-11-22, targets 7-9). Three flags, all default off; with a flag off its hook is not
 * reached, so behaviour, logs and RNG order are train-75's. None draws from an RNG: every share is a counter.
 *   -Dbots.style.defShare2      Defence: a ranger fights on Longrange when Defence is 5+ under Ranged (was 10+), and on every
 *                               2nd ranged trip while Defence is under Ranged at all (-Dbots.style.defShare2.every=2).
 *                               Works with or without bots.style.defShare.
 *   -Dbots.chains.smeltNudge    Smithing: the mine-smelt chain's trip weight is 1.0 instead of 0.9 when bars are in demand, and
 *                               every 4th not-in-demand look passes at 1.05 (-Dbots.chains.smeltNudge.every=4). Needs chains on.
 *   -Dbots.chains.smeltFix2     Smithing: a bot's mine-smelt trip that reaches its trip target while still mining (target 14,
 *                               bag plan 15 ore) walks to the furnace before the bank; it used to bank the ore unsmelted.
 *   -Dartisans.smithSmelt       Smithing: a smith artisan may plan a smelting recipe when the ore on the Exchange (in bars'
 *                               worth) is above the bar stock and the bar is under its stock target. Inputs are bought and
 *                               the bars sold through the Ledger like any batch (no coins made); the 50-ore reserve stays.
 */
final class W34Skills {
    private W34Skills() { }
    static boolean flag(String k) { return Boolean.getBoolean(k); }

    // ------------------------------------------------------------------ 7 Defence: bots.style.defShare2
    static final boolean DEF_SHARE2 = flag("bots.style.defShare2");
    static final int DEF_GAP = 5;
    static final int DEF_EVERY = Math.max(1, Integer.getInteger("bots.style.defShare2.every", 2));
    static volatile int longTrips, longByGap, longByShare;

    /** 0 = accurate, 1 = longrange by the gap (Defence 5+ under Ranged), 2 = longrange by the share (Defence under Ranged, this bot's every-th ranged trip). */
    static int stanceWhy(int def, int ranged, int trip, int every) {
        if (def < ranged - DEF_GAP) return 1;
        return def < ranged && every > 0 && trip % every == 0 ? 2 : 0;
    }
    static String rangerStance2(int def, int ranged, int trip, int every) { return stanceWhy(def, ranged, trip, every) != 0 ? "longrange" : "rangeaccurate"; }

    /** One line per ranged trip that starts on Longrange (flag on only). */
    static void longTrip(String bot, int def, int ranged, int why) {
        longTrips++; if (why == 1) longByGap++; else longByShare++;
        System.out.println("[W34] defShare2 " + bot + " ranged trip on longrange (Defence " + def + ", Ranged " + ranged + ", " + (why == 1 ? "gap" : "share") + ")");
    }

    // ------------------------------------------------------------------ 8 Smithing: bots.chains.smeltNudge
    static final boolean SMELT_NUDGE = flag("bots.chains.smeltNudge");
    static final int NUDGE_EVERY = Math.max(1, Integer.getInteger("bots.chains.smeltNudge.every", 4));
    static volatile int nudgeLooks, nudgeLifts, nudgeStarts;

    /** The smelt chain's factor on the raw job's score. Flag off: 0.9 x demand (train-75). On: demand above 1 at full weight; else the look-th not-in-demand look passes at 1.05. */
    static double smeltFactor(boolean on, double demand, int look, int every) {
        if (!on) return 0.9 * demand;
        if (demand > 1) return demand;
        return every > 0 && look > 0 && look % every == 0 ? 1.05 : 0.9 * demand;
    }
    /** The caller's counter step: a not-in-demand look gets the next number, an in-demand one 0. */
    static synchronized int nextLook(double demand) { return demand > 1 ? 0 : ++nudgeLooks; }
    static void lifted(double demand, int look) { if (demand <= 1 && look % NUDGE_EVERY == 0) nudgeLifts++; }
    static void smeltStart(String bot, String task) {
        nudgeStarts++;
        System.out.println("[W34] smeltNudge " + bot + " starts a smelt chain (" + task + "), start " + nudgeStarts + ", lifts " + nudgeLifts);
    }

    // ------------------------------------------------------------------ 8b Smithing: bots.chains.smeltFix2
    static final boolean SMELT_FIX2 = flag("bots.chains.smeltFix2");
    static volatile int smeltFirsts;
    /** One line per smelt trip sent to the furnace at its trip target (flag on only). */
    static void smeltFirst(String bot, int collected, int target) {
        smeltFirsts++;
        System.out.println("[W34] smeltFix2 " + bot + " takes " + collected + " ore to the furnace before the bank (trip target " + target + ")");
    }

    // ------------------------------------------------------------------ 9 Smithing: artisans.smithSmelt
    static final boolean SMITH_SMELT = flag("artisans.smithSmelt");
    static final int SMITH_EVERY = Math.max(1, Integer.getInteger("artisans.smithSmelt.every", 3));
    static volatile int smithTurns, smithSmeltPlans, smithSmeltBars;

    /** A smith's planning turn: true on every SMITH_EVERY-th (the smelt batch goes first on that turn). */
    static synchronized boolean smithTurn() { return ++smithTurns % SMITH_EVERY == 0; }

    /**
     * How many bars a smith may smelt: 0 unless the bar is under its target and the ore (in bars' worth, whole stock) is above
     * the bar stock. Capped by the gap to the target, by 28 and by the ore left above the reserve (oreFree, in bars' worth).
     */
    static int smeltTimes(int barStock, int barTarget, int oreSets, int oreFree) {
        if (barStock < 0) barStock = 0;
        if (barTarget <= 0 || barStock >= barTarget || oreSets <= barStock) return 0;
        return Math.max(0, Math.min(Math.min(28, barTarget - barStock), oreFree));
    }
    /** The plan's score: the artisans' own gap rule without the jitter (no RNG draw), plus 5 on the smith's smelt turn (under a player's order at +10 and a wanted potion at +8). */
    static double smeltScore(int barStock, int barTarget, int value, boolean turn) {
        double gap = (barTarget - barStock) / (double) barTarget;
        return gap * gap * Math.log10(10 + value) * (barStock == 0 ? 2 : 1) + (turn ? 5 : 0);
    }
    static void smithSmelts(String bot, int bars, String name) {
        smithSmeltPlans++; smithSmeltBars += bars;
        System.out.println("[W34] smithSmelt " + bot + " (smith) smelts " + bars + " x " + name);
    }

    // ------------------------------------------------------------------ one line for the hourly log (flags on only)
    static boolean anyOn() { return DEF_SHARE2 || SMELT_NUDGE || SMITH_SMELT || SMELT_FIX2; }
    static String flags() {
        StringBuilder b = new StringBuilder();
        if (DEF_SHARE2) b.append("defShare2 "); if (SMELT_NUDGE) b.append("smeltNudge "); if (SMITH_SMELT) b.append("smithSmelt "); if (SMELT_FIX2) b.append("smeltFix2 ");
        return b.toString().trim();
    }
    static String line() {
        return "[W34] on=[" + flags() + "] longTrips=" + longTrips + " longByGap=" + longByGap + " longByShare=" + longByShare
                + " nudgeLooks=" + nudgeLooks + " nudgeLifts=" + nudgeLifts + " nudgeStarts=" + nudgeStarts
                + " smithTurns=" + smithTurns + " smithSmeltPlans=" + smithSmeltPlans + " smithSmeltBars=" + smithSmeltBars + " smeltFirsts=" + smeltFirsts;
    }
}
