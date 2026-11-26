package content.custom;

/**
 * W2 lane M (2026-11-19): two flag-gated fight rules, kept pure (no engine class) so plain-java units can call the very
 * methods SmartAdventurers and AfkTasks call (those two cannot be loaded outside a world).
 *
 * 1. Combat steer, -Dbots.summoning.combatSteer=true (default off; needs -Dbots.summoning and -Dbots.summoning.combat):
 *    a bot that owns a usable combat pouch scores fight jobs at a multi-combat spot STEER_X times higher in
 *    SmartAdventurers.Brain.chooseJob, and for a fight task with several spots takes the nearest multi-combat one.
 *    A familiar only fights by itself in multi-combat (Familiar.handleTickActions), and few bots went there by themselves.
 *    -Dbots.summoning.combatSteer.x (default 3.0, clamped 1..20).
 *
 * 2. Loot after, -Dbots.fight.lootAfter=true (default off): AfkTasks.Runner.fight() picked up ground loot before it looked
 *    for a target, so a loot-on bot on a littered field filled its pack and banked without one attack order (and its
 *    summoned familiar's pouch was spent for nothing). With the flag on a bot loots only after an attack order of this trip,
 *    and at most LOOT_CAP loot actions per attack order; then it has to give the next order.
 *    -Dbots.fight.lootAfter.cap (default 6, floor 1). A loot action is one pickUpLoot() that did something (a walk to an
 *    item or a pick-up), so 6 is about three items: one kill's drop.
 *
 * Flags off: every caller is behind `if (FightRules.STEER ...)` / `lootOk` returns true, so nothing changes.
 */
final class FightRules {
    private FightRules() { }

    static final boolean STEER = Boolean.getBoolean("bots.summoning") && Boolean.getBoolean("bots.summoning.combat") && Boolean.getBoolean("bots.summoning.combatSteer");
    static final double STEER_X = steerX(System.getProperty("bots.summoning.combatSteer.x"));
    static final boolean LOOT_AFTER = Boolean.getBoolean("bots.fight.lootAfter");
    static final int LOOT_CAP = Math.max(1, Integer.getInteger("bots.fight.lootAfter.cap", 6));

    /** Counters for the tally line (written by the callers, only with a flag on). */
    static volatile long STEER_SEEN, STEER_PICKS, STEER_SPOTS, LOOT_HELD, LOOT_HELD_FIRST;

    static double steerX(String prop) {
        double x = 3.0;
        if (prop != null) try { x = Double.parseDouble(prop.trim()); } catch (NumberFormatException ignored) { }
        if (Double.isNaN(x)) x = 3.0;
        return Math.max(1.0, Math.min(20.0, x));
    }

    /**
     * Score factor for one job in chooseJob. x only when the steer is on, the job is a fight job (a hunt or a Kind.FIGHT task),
     * its spot is multi-combat and not in the Wilderness (Bob.combatEligible never plans a pouch there), and the bot owns a
     * combat pouch it can use now. Otherwise exactly 1.0.
     */
    static double steerFactor(boolean on, boolean fightJob, boolean multiSpot, boolean wildSpot, boolean ownsPouch, double x) {
        return on && fightJob && multiSpot && !wildSpot && ownsPouch ? x : 1.0;
    }

    /**
     * Spot pick for a fight task with several spots (SmartAdventurers.start). Returns the index of the nearest spot that can
     * be entered and is multi-combat, or -1 = keep the old pick (steer off, no pouch, or no such spot).
     */
    static int steerSpot(boolean on, boolean ownsPouch, boolean[] canEnter, boolean[] multi, double[] dist) {
        if (!on || !ownsPouch || canEnter == null || multi == null || dist == null) return -1;
        int best = -1;
        for (int i = 0; i < dist.length && i < multi.length && i < canEnter.length; i++)
            if (canEnter[i] && multi[i] && (best < 0 || dist[i] < dist[best])) best = i;
        return best;
    }

    /**
     * May fight() pick up ground loot now? Off, or not a bot: always (the old behaviour). On: only after an attack order of this
     * trip (attacks > 0), and fewer than cap loot actions since the last order.
     */
    static boolean lootOk(boolean on, boolean bot, int attacks, int lootSinceAttack, int cap) {
        if (!on || !bot) return true;
        return attacks > 0 && lootSinceAttack < cap;
    }

    /**
     * W15 safe retreat, -Dbots.fight.safeRetreat=true (default off): AfkTasks.Runner.recover() asks this before it may end a
     * trip or rest. -Dbots.fight.safeRetreat.max (default 200, floor 1) caps the recover() calls one hold may last.
     */
    static final boolean SAFE_RETREAT = Boolean.getBoolean("bots.fight.safeRetreat");
    static final int SAFE_MAX = Math.max(1, Integer.getInteger("bots.fight.safeRetreat.max", 200));
    static final int SAFE_GO = 0, SAFE_HOLD = 1, SAFE_GIVE_UP = 3;

    /**
     * Off, or not a bot: SAFE_GO (the old behaviour). On: SAFE_GO when not poisoned and at half health or more; SAFE_GIVE_UP
     * once ticks reaches cap, or when poisoned with no help (no antipoison and no food on it after trying its bank); else
     * SAFE_HOLD. Player rules: there is no free cure or heal, a give-up hands the bot back to the old code.
     */
    static int safeHold(boolean on, boolean bot, int hp, int max, boolean poisoned, boolean help, int ticks, int cap) {
        if (!on || !bot) return SAFE_GO;
        if (!poisoned && hp * 2 >= max) return SAFE_GO;
        if (ticks >= cap) return SAFE_GIVE_UP; // the cap always ends a hold
        if (poisoned && !help) return SAFE_GIVE_UP;
        return SAFE_HOLD;
    }

    static String line() {
        return " steer: on=" + STEER + " x=" + STEER_X + " seen=" + STEER_SEEN + " picks=" + STEER_PICKS + " spots=" + STEER_SPOTS
                + " lootAfter: on=" + LOOT_AFTER + " cap=" + LOOT_CAP + " held=" + LOOT_HELD + " heldBeforeFirstAttack=" + LOOT_HELD_FIRST;
    }
}
