package content.custom;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * P82 (-Dnav.p82Fix, default off; off = the train-33 code): three live stuck spots of 11-14..11-15.
 *  1. Al Kharid toll gate (3267,3228 / 3263,3237), "off getting runes (dark wizards)": a bot from the Al Kharid bank paid
 *     and hopped west (AfkTasks.tollHop decides by the side the destination is on), then planned again from 3266,3227 with
 *     the toll allowed; that plan went back east through the gate (192 tiles vs 229 round by Lumbridge), and tollHop never
 *     hops a bot east to a goal on the west side, so it stood at the gate tile until the Watchdog took it. Fix: a plan made
 *     next to the gate (tollBox) toward a goal on the bot's own side of it is made without the toll, the same rule
 *     tollHop already applies. Only plans that start within ~12 tiles of the gate are touched (P73 B1 touched every plan
 *     west of x 3268).
 *  2. Falador west wall (2937,3385), "off getting blue dragons / black demons": AfkTasks.taverleyVia's "inside Falador"
 *     box started at x 2938, but x 2937 is the walkable column against the wall (x 2935-2936), so a bot that reached it
 *     got no via and walked at the wall. Fix: the box starts at x 2937 (fallInside).
 *  3. Taverley dungeon blue dragons (2896,9821 / 2901,9818 / 2927,9819 / 2937,9822): Gates lets a bot in with the dusty key
 *     in its bank (Gates.item counts the bank; a high fighter is given the key there), but the gate 2623 opens only with the
 *     key in the pack (TaverleyDungeonListeners), so the walk stood at the shut gate. Fix: a bot whose trip ends behind
 *     that gate takes its own key from the bank into the pack (keyToPack; no item made or lost).
 */
final class P82 {
    private P82() { }
    static final boolean ON = Boolean.getBoolean("nav.p82Fix");
    static final AtomicInteger TOLL = new AtomicInteger(), FALADOR = new AtomicInteger(), KEY = new AtomicInteger(), GATE = new AtomicInteger(), TRAP = new AtomicInteger();

    static void count(String what, AtomicInteger c) { int n = c.incrementAndGet(); if (n <= 5 || n % 50 == 0) System.out.println("[P82] " + what + " " + n); }

    /** Fix 1: a plan from next to the Al Kharid toll gate toward a goal on the bot's own side: no toll (tollHop would not hop it). */
    static boolean tollSameSide(int hx, int hy, int hz, int dx, int dz) {
        if (hz != 0 || dz != 0 || hy < 3218 || hy > 3242) return false;
        boolean west = hx >= 3256 && hx <= 3267, east = hx >= 3268 && hx <= 3279;
        return west && dx <= 3267 || east && dx >= 3268;
    }

    static final core.game.world.map.Location KEY_GATE = core.game.world.map.Location.create(2924, 9803, 0); // gate 2623, west edge of this tile

    /**
     * Fix 3: the walkable way from the y 9818 ledge north of the dragons round to the east side of the key gate (a BFS over the
     * server's clipping flags in TestP82Nav, every 8th tile; the gate front is only reached from the south, through the
     * corridor at x 2923-2951, y 9756-9801). Pathfinder's 104-tile window never finds it from the ledge.
     */
    static final int[][] KEY_WAY = {{2917, 9819}, {2925, 9819}, {2930, 9822}, {2934, 9826}, {2939, 9823}, {2939, 9815}, {2941, 9809}, {2943, 9803}, {2946, 9798}, {2951, 9795},
            {2951, 9787}, {2951, 9779}, {2948, 9774}, {2943, 9777}, {2936, 9776}, {2935, 9769}, {2935, 9761}, {2932, 9756}, {2924, 9756}, {2923, 9761}, {2924, 9768},
            {2928, 9772}, {2930, 9778}, {2932, 9784}, {2935, 9789}, {2935, 9797}, {2931, 9801}, {2925, 9803}};

    /** Fix 3: the index of the KEY_WAY point nearest to x,y (within 14 tiles), else -1. */
    static int nearestWay(int x, int y) {
        int best = -1; double bd = 14.01;
        for (int i = 0; i < KEY_WAY.length; i++) { double d = Math.hypot(KEY_WAY[i][0] - x, KEY_WAY[i][1] - y); if (d < bd) { bd = d; best = i; } }
        return best;
    }

    /** Fix 3: west of the key gate, in the dragons' part (the gate's far side; the ladder foot 2884,9798 and the pipe west of x 2893 are not). */
    static boolean insideKeyGate(int x, int y) { return x <= 2923 && x >= 2893 && y >= 9785 && y <= 9815; }

    /** Fix 3: a goal behind the Taverley dungeon's key gate (the blue dragons' box and the black demons west of it). */
    static boolean behindKeyGate(int x, int y, int z) { return z == 0 && x >= 2850 && x <= 2945 && y >= 9765 && y <= 9815 || W6Slayer.ON && W6Slayer.hellBox(x, y, z); } // W6: and the hellhounds north of the black demons (reached through the same gate)

    /** Fix 3: the bot's own dusty key from its bank into its pack, once it is on a trip behind the gate. True when moved. */
    static boolean keyToPack(core.game.node.entity.player.Player p, core.game.world.map.Location dest) {
        if (!ON || p == null || dest == null || !p.isArtificial() || !behindKeyGate(dest.getX(), dest.getY(), dest.getZ())) return false;
        core.game.node.item.Item key = new core.game.node.item.Item(1590);
        try {
            if (p.getInventory().containsItem(key) || p.getEquipment().containsItem(key) || !p.getBank().containsItem(key) || p.getInventory().freeSlots() < 1) return false;
            if (!p.getBank().remove(key)) return false;
            if (!p.getInventory().add(key)) { p.getBank().add(key); return false; }
        } catch (Throwable t) { return false; }
        count("dusty-key-to-pack", KEY);
        return true;
    }

    /** Fix 2: inside Falador for the walk to the Taverley-dungeon ladder (old box x 2938-2975; 2937 is the wall-side column). */
    static boolean fallInside(int x, int y, boolean fix) { return x >= (fix ? 2937 : 2938) && x <= 2975 && y >= 3330 && y <= 3395; }
}
