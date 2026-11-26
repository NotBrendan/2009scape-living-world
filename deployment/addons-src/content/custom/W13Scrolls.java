package content.custom;

/**
 * W13 lane B item 3 (DRAFT, no hooks yet): Summoning special-move scrolls. Switch: -Dbots.summoning.scrolls (default off).
 * Nothing calls this class yet, so flag on or off the server behaves as before: no RNG draw, no line, no timer, no coins.
 *
 * What the server has (upstream summoning, read 11-20): SummoningScroll (per pouch: scroll item, level, xp, ingredients = the pouch),
 * SummoningCreator.create(player, amount, node) makes 10 scrolls per pouch at an obelisk (ObeliskOptionPlugin "infuse"/transform),
 * Familiar.executeSpecialMove(FamiliarSpecial) spends specialCost of 60 special points and one scroll from the inventory.
 *
 * Loop (TODO, each step behind ON and the existing bots.summoning.combat switch):
 *  1. who: a bot that already summons a combat familiar (Bob/W8Polish summoning path) and has a spare pouch of that kind.
 *  2. make: at the obelisk it already uses for pouches, SummoningCreator.create(bot, n, scroll) (uses the engine's own pulse,
 *     so level, ingredients and xp stay engine rules). Keep KEEP scrolls in the pack.
 *  3. use: in a fight, when special points >= cost and the target is alive, familiar.executeSpecialMove(new FamiliarSpecial(target))
 *     at most once per USE_GAP_MS.
 * Hook sites to add: the summoning restock step (make) and the fight tick (use); hourly "[Summoning] scrolls hourly".
 */
final class W13Scrolls {
    private W13Scrolls() { }

    static final boolean ON = Boolean.getBoolean("bots.summoning.scrolls");
    static final int KEEP = 10, POINTS_MAX = 60;
    static final long USE_GAP_MS = 30_000L;

    /** Make more when the pack holds fewer than KEEP and a pouch of that familiar is spare. */
    static boolean makeDue(int held, int sparePouches) { return held < KEEP && sparePouches > 0; }

    /** Use the special: on, scroll held, points cover the cost, gap since the last use passed, in combat. */
    static boolean useDue(boolean on, int scrolls, int points, int cost, long sinceLast, boolean fighting) {
        return on && scrolls > 0 && cost > 0 && points >= cost && sinceLast >= USE_GAP_MS && fighting;
    }
}
