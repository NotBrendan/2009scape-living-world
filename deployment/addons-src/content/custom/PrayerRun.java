package content.custom;

import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.interaction.Option;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;

/**
 * P53 (the design notes section 6): prayer bone-bury runs. At trip end, standing at a bank or the Exchange, a bot
 * takes up to 27 bones (its own banked bones first, else surplus bones bought through Ledger) and buries them one at a time.
 * Off by default: -Dbots.prayerRun=true turns it on. With the flag off nothing here is called (SmartAdventurers guards every
 * call with ON, and its new state field stays 0).
 *
 * The pick and the fleet cap live here, not in SmartAdventurers, so TestP53Prayer can run them without a world
 * (SmartAdventurers cannot class-init outside one).
 */
final class PrayerRun {
    private PrayerRun() { }

    static final boolean ON = Boolean.getBoolean("bots.prayerRun");
    /** Trial only: the next trip end near a bank runs once, skipping the 1-in-4 roll and the hour wait. */
    static final boolean TEST = Boolean.getBoolean("smartbots.testPrayer");

    static final int BONES = 526, BIG_BONES = 532;
    static final double XP_NORM = 4.5, XP_BIG = 15.0;
    static final int MAX_PRAYER = 70, MAX_N = 27, MIN_N = 5, MIN_STOCK = 40, FLEET_CAP = 300;

    // fleet cap: bones bought per clock hour, all bots together (think runs on one queue thread; synchronized anyway)
    private static long capHour = -1;
    private static int capUsed;
    static volatile long RUNS, BURIED, BOUGHT, SPENT;

    static synchronized int hourLeft(long now) {
        long h = now / 3_600_000L;
        if (h != capHour) { capHour = h; capUsed = 0; }
        return Math.max(0, FLEET_CAP - capUsed);
    }

    static synchronized void bought(long now, int n) {
        hourLeft(now);
        capUsed += Math.max(0, n);
    }

    /**
     * The pick. Returns {id, n, buy (1 = buy n through Ledger, 0 = take n from the bank)} or null for no run.
     * Own banked big bones first, then own normal bones; else buy big then normal bones when the market has a surplus of them,
     * holds at least 40, the cost stays within 10% of the purse and the fleet has bones left this hour. Under 5 bones: no run.
     * Pass sur=false / stock=0 when the bot is not at the Exchange (it can only buy there).
     */
    static int[] boneChoice(int prayer, long coins, int bankBig, int bankNorm, boolean surBig, boolean surNorm,
                            int stockBig, int stockNorm, int valBig, int valNorm, int hourLeft) {
        if (prayer >= MAX_PRAYER) return null;
        if (bankBig >= MIN_N) return new int[]{BIG_BONES, Math.min(MAX_N, bankBig), 0};
        if (bankNorm >= MIN_N) return new int[]{BONES, Math.min(MAX_N, bankNorm), 0};
        int[] ids = {BIG_BONES, BONES};
        boolean[] sur = {surBig, surNorm};
        int[] stock = {stockBig, stockNorm};
        int[] val = {valBig, valNorm};
        for (int i = 0; i < 2; i++) {
            if (!sur[i] || stock[i] < MIN_STOCK || hourLeft <= 0) continue;
            long afford = Math.max(0, coins) / 10 / Math.max(1, val[i]);
            int n = (int) Math.min(Math.min(MAX_N, afford), Math.min(hourLeft, stock[i]));
            if (n < MIN_N) continue;
            return new int[]{ids[i], n, 1};
        }
        return null;
    }

    // Why a trip end did not start a run (and how many did): one [Prayer] tally line every 15 min (2 min under -Dtraders.fast).
    static final int R_BUSY = 0, R_COMBAT = 1, R_WAIT = 2, R_NOT_BANK = 3, R_SLOTS = 4, R_PRAYER70 = 5, R_NO_BONES_AWAY = 6,
            R_NO_SURPLUS = 7, R_LOW_STOCK_OR_PURSE = 8, R_BUY_FAIL = 9, R_STARTED = 10;
    static final String[] REASONS = {"busy", "combat", "wait", "notBank", "slots", "prayer70", "noBonesAwayFromGE", "noSurplus", "lowStockOrPurse", "buyFail", "started"};
    static final long[] TALLY = new long[REASONS.length];
    private static long tallyAt;

    /** Counts one trip-end outcome; returns false so a refusing caller can `return skip(...)`. */
    static synchronized boolean skip(int reason, long now) {
        TALLY[reason]++;
        if (tallyAt == 0) tallyAt = now;
        else if (now - tallyAt >= (Boolean.getBoolean("traders.fast") ? 120_000 : 15 * 60_000)) { tallyAt = now; System.out.println("[Prayer] tally " + tallyLine() + " runs=" + RUNS + " buried=" + BURIED + " bought=" + BOUGHT + " spent=" + SPENT); }
        return false;
    }

    static synchronized String tallyLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < REASONS.length; i++) sb.append(i == 0 ? "" : " ").append(REASONS[i]).append('=').append(TALLY[i]);
        return sb.toString();
    }

    /** Why boneChoice said no (called only after it returned null). */
    static int why(int prayer, boolean atGE, boolean anySurplus) {
        if (prayer >= MAX_PRAYER) return R_PRAYER70;
        if (!atGE) return R_NO_BONES_AWAY;
        if (!anySurplus) return R_NO_SURPLUS;
        return R_LOW_STOCK_OR_PURSE;
    }

    /**
     * P53b: the trigger. The pair showed every trip end by a bank refused with noBonesAwayFromGE: a fighter's bones were sold
     * into the Ledger at the booth, so its bank never held any. With the flag on, a smart bot under prayer 70 keeps its own
     * bones in the bank instead of selling them (up to KEEP_MAX), so the next trip end can bury them. Nothing is minted or
     * bought: the bot just is not paid for those bones. Flag off: always false (AfkTasks sells them as before).
     */
    static final int KEEP_MAX = 2 * MAX_N;
    static boolean keepBone(boolean on, int id, int prayer, int banked) {
        return on && (id == BONES || id == BIG_BONES) && prayer < MAX_PRAYER && banked < KEEP_MAX;
    }

    static double xpOf(int id) { return id == BIG_BONES ? XP_BIG : XP_NORM; }

    /** Bones of this id in the pack. */
    static int inPack(Player p, int id) { return p.getInventory().getAmount(id); }

    /**
     * One bone through its own "Bury" option (the engine's BoneBuryListener: its 2-tick clock, xp and animation), the same lookup
     * AfkTasks.Runner.buryBones uses. Returns false when there is no such bone or it has no bury option.
     */
    static boolean buryOne(Player p, int id) {
        for (Item it : p.getInventory().toArray()) {
            if (it == null || it.getId() != id) continue;
            Option bury = null;
            for (Option o : it.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase("bury")) bury = o;
            if (bury == null) return false;
            if (!InteractionListeners.run(it.getId(), IntType.ITEM, "bury", p, it)) it.getInteraction().handleItemOption(p, bury, p.getInventory());
            return true;
        }
        return false;
    }
}
