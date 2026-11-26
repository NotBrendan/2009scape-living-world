package content.custom;

/**
 * W13a gap lane A (the design notes): the last skills-plan gaps for Woodcutting, Crafting and Cooking. Each item
 * has its own -D flag, default OFF; with a flag off its caller is never reached: no branch taken, no RNG draw, no line. The
 * W13a code draws no RNG at all and creates no coins or items.
 *
 *   -Dbots.wc.trees2      Woodcutting 3: more willow and yew spots the walker reaches on the plane-0 nav grid (offline
 *                         reach check, p38-navport from Lumbridge): Seers' and Catherby yews, the Lumbridge yews, Seers' and
 *                         Taverley willows. They join AfkTasks.SPOTS for WILLOW / YEW, so the existing woodcutting job rows
 *                         get more places to go (the nearest spot wins as before). Teak and mahogany: NO row. Every teak /
 *                         mahogany tree on the server's map is gated: Ape Atoll (Monkey Madness), Mos Le'Harmless (Cabin
 *                         Fever), the Kharazi jungle (Legends' Quest); all four probed tiles are off the nav grid.
 *   -Dbots.craft.spin     Crafting a: DRAFT. Pure rules only (bowstrings from flax at a ground-floor wheel); with the flag on
 *                         the one ground-floor wheel the map lists outside Lletya (2732,3276, reachable on the nav grid) joins
 *                         AfkTasks.stationNear(WHEEL), so a ::afk spin bank loop can find it. The bot job is a TODO.
 *   -Dbots.craft.vials    Crafting c: DRAFT, flag only. No molten glass, soda ash or sand supplier exists in bot stock; a
 *                         vial job has no input. TODO.
 *   -Dbots.cook.twoStep   Cooking c: DRAFT. Pure rules for a meat pie (pie shell + cooked meat -> uncooked pie -> range);
 *                         wine needs grapes and nothing makes grapes (item 1987) for bots. Engine job is a TODO.
 *
 * Plain java can test this class without a world (TestW13a); the engine side is the guarded hooks in AfkTasks.
 */
final class W13aSkills {
    private W13aSkills() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }

    static final boolean TREES2 = flag("bots.wc.trees2");
    static final boolean SPIN = flag("bots.craft.spin");
    static final boolean VIALS = flag("bots.craft.vials");
    static final boolean TWO_STEP = flag("bots.cook.twoStep");

    static boolean anyOn() { return TREES2 || SPIN || VIALS || TWO_STEP; }

    // ------------------------------------------------------------------ Woodcutting: bots.wc.trees2
    static final int KIND_WILLOW = 0, KIND_YEW = 1;
    /** {kind, x, y}: a reachable plane-0 tile beside the trees (p38-navport --reach from Lumbridge, 2026-11-20). */
    static final int[][] TREE_SPOTS = {
        {KIND_YEW, 2711, 3463},     // Seers' Village yews (by the church), reach len 988
        {KIND_YEW, 2766, 3446},     // Catherby yews (north edge), reach len 918
        {KIND_YEW, 3150, 3230},     // Lumbridge / Draynor road yews, reach len 110
        {KIND_WILLOW, 2711, 3510},  // Seers' Village willows by the river, reach len 1029
        {KIND_WILLOW, 2928, 3400},  // Taverley willows (east bank), reach len 608
    };
    static final String[] TREE_NAMES = {"the Seers' Village yews", "the Catherby yews", "the Lumbridge yews", "the Seers' Village willows", "the Taverley willows"};
    /** Teak / mahogany trees on the map ({x, y}): all gated (quest or island). Kept for the note and the test; no row uses them. */
    static final int[][] HARDWOOD_GATED = {{2772, 2700}, {2780, 2700}, {2836, 2908}, {3812, 3060}, {3836, 3068}, {2948, 2908}, {2948, 2924}, {3828, 3052}};

    /** How many extra spots a kind gets (0 with the flag off). */
    static int extraSpots(boolean on, int kind) {
        if (!on) return 0;
        int n = 0;
        for (int[] s : TREE_SPOTS) if (s[0] == kind) n++;
        return n;
    }

    // ------------------------------------------------------------------ Crafting: bots.craft.spin (draft)
    static final int FLAX = 1779, BOWSTRING = 1777, SPIN_LEVEL = 10;
    static final double SPIN_XP = 15.0;
    /** The ground-floor wheel the world map lists (Lletya's is elf-gated); [UNVERIFIED] in game: check the door before a bot job. */
    static final int[] WHEEL_AT = {2732, 3276};
    static final int SPIN_FLOOR = 28;         // flax always left on the Exchange
    /**
     * How many flax a spinner takes: at most a pack (28), what stock holds over the floor, and only while a bowstring is worth
     * more than the flax it uses (at most CEIL_X = 1.0 of the bowstring price). 0 = no trip.
     */
    static int spinQty(int crafting, int flaxStock, int flaxPrice, int bowPrice) {
        if (crafting < SPIN_LEVEL) return 0;
        if (Math.max(1, flaxPrice) > Math.max(1, bowPrice)) return 0;
        return Math.max(0, Math.min(28, flaxStock - SPIN_FLOOR));
    }
    // TODO(w13a spin): bot job: take spinQty flax from bot stock (Ledger.consume), walk to WHEEL_AT, run the engine's
    //  SpinningPulse (AfkTasks.Runner.spin does it for players), supply the bowstrings (Ledger.supply). Needs an in-game probe
    //  of the wheel's building first.

    // ------------------------------------------------------------------ Crafting: bots.craft.vials (draft)
    static final int MOLTEN_GLASS = 1775, VIAL = 229, VIAL_LEVEL = 33;
    // TODO(w13a vials): no glass input in bot stock (no soda ash / sand / molten glass producer). Needs a supplier first.

    // ------------------------------------------------------------------ Cooking: bots.cook.twoStep (draft)
    static final int PIE_SHELL = 2315, COOKED_MEAT = 2142, UNCOOKED_MEAT_PIE = 2319, MEAT_PIE = 2327, MEAT_PIE_LEVEL = 20;
    static final double MEAT_PIE_XP = 110.0;
    static final int GRAPES = 1987, JUG_OF_WATER = 1937, JUG_OF_WINE = 1993, WINE_LEVEL = 35;
    /** The two-step product a cook makes: MEAT_PIE when it can, -1 for none (wine has no grape supplier, never picked). */
    static int twoStepProduct(int cooking, int shellStock, int meatStock) {
        if (cooking < MEAT_PIE_LEVEL) return -1;
        if (shellStock <= 0 || meatStock <= 0) return -1;
        return MEAT_PIE;
    }
    // TODO(w13a twoStep): pie shells have no producer in bot stock either (pie dish + pastry dough); a shop buy path for pie
    //  dishes plus a pastry-dough step is the next piece, then the cook job (use shell on meat, cook at a range).

    static String line() {
        StringBuilder b = new StringBuilder("[W13a] on:");
        if (TREES2) b.append(" trees2"); if (SPIN) b.append(" spin"); if (VIALS) b.append(" vials"); if (TWO_STEP) b.append(" twoStep");
        return b.toString();
    }
}
