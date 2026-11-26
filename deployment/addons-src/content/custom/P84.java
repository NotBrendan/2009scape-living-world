package content.custom;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * P84 (-Dnav.p84Fix, default off; off = the train-33 + P82 code): three live stuck tiles of train-33 (11-15).
 *  1. Taverley dungeon 2881,9794, "off getting black demons" (HuntMap 2866,9780, behind the dusty-key gate 2623 at 2924,9803):
 *     the bot climbs down the ladder (foot 2884,9798) and walks straight at the demons; there is no walk from the ladder foot
 *     to them (tile BFS: no path), and P82's KEY_WAY starts on the y 9818 ledge, more than 14 tiles off, so keyGateVia gave
 *     nothing. Fix: from the ladder foot the walk takes LADDER_LEAD (the BFS way north up x 2884, east along y 9848, down to
 *     2937,9829) and joins KEY_WAY at 2939,9823; and once a bot has been through the gate the walk never turns back to it
 *     (the demons' way, 2893,9791 -> 2894,9770 -> 2866,9780, leaves P82's insideKeyGate box).
 *  2. Karamja 2908,3140, "off getting jogres / monkeys": the jogre (2926,3056) and monkey (2920,3027) spots lie in a walled
 *     pocket (nav-p0 flood fill: 6081 tiles, box 2889-3004 x 2950-3119, nothing of Karamja's north reaches it), so the
 *     route found no step and the bot stood until the Watchdog. Fix: those spots are skipped like P49's pocket boxes
 *     (Resources.skipHunt), and Brain.start drops any hunt skipHunt refuses (a crew follower's or a rally's pick never
 *     passed the skip). Tribesmen (2730,3161) are on Karamja's west coast, west of AfkTasks.karamjaBox's x 2740 edge, so
 *     a bot on Karamja boarded the boat to Port Sarim for them; fix: the box reaches x 2700 there (mainland check kept).
 *  3. 3140,3509 north of the Grand Exchange, "off getting troll spectators" (2923,3612, P49 pocket B, Death Plateau):
 *     skipHunt already refuses the spot in the brain's own pick, but other picks (crew lead, rally) started the hunt anyway;
 *     the walk then looped on the hidden trapdoor there. Fix: the Brain.start drop of item 2.
 */
final class P84 {
    private P84() { }
    static final boolean ON = Boolean.getBoolean("nav.p84Fix");
    static final AtomicInteger LEAD = new AtomicInteger(), THROUGH = new AtomicInteger(), SKIP = new AtomicInteger(), DROP = new AtomicInteger(), KARAMJA = new AtomicInteger();

    static void count(String what, AtomicInteger c, String detail) {
        int n = c.incrementAndGet();
        if (n <= 5 || n % 50 == 0) System.out.println("[P84] " + what + " " + n + (detail == null ? "" : " (" + detail + ")") + " (P84)");
    }

    /** Fix 1: the ladder foot's part of the dungeon (the ladder lands at 2884,9798; live stuck at 2881,9794). */
    static boolean ladderFoot(int x, int y) { return x >= 2878 && x <= 2892 && y >= 9788 && y <= 9806; }

    /** Fix 1: ladder foot -> 2937,9829 (TestP84Stuck/TestP82Nav tile BFS, every 8th tile), then P82.KEY_WAY from 2939,9823 to the gate. */
    static final int[][] LADDER_LEAD = {{2884, 9802}, {2884, 9810}, {2884, 9818}, {2884, 9826}, {2885, 9833}, {2884, 9840}, {2886, 9846}, {2892, 9848},
            {2900, 9848}, {2908, 9848}, {2916, 9848}, {2924, 9848}, {2928, 9844}, {2932, 9840}, {2937, 9837}, {2937, 9829}};
    static final int[][] LADDER_WAY = join();

    private static int[][] join() {
        int from = 0;
        for (int i = 0; i < P82.KEY_WAY.length; i++) if (P82.KEY_WAY[i][0] == 2939 && P82.KEY_WAY[i][1] == 9823) { from = i; break; }
        int[][] w = new int[LADDER_LEAD.length + P82.KEY_WAY.length - from][];
        System.arraycopy(LADDER_LEAD, 0, w, 0, LADDER_LEAD.length);
        System.arraycopy(P82.KEY_WAY, from, w, LADDER_LEAD.length, P82.KEY_WAY.length - from);
        return w;
    }

    /** Fix 2: walled pockets no surface walk reaches (nav-p0 flood fill), boxes x0,y0,x1,y1: Karamja's jogres and monkeys. */
    static final int[][] POCKETS = {{2917, 3005, 2968, 3088}}; // the largest box round both spots with no tile reached from 2908,3140 (nav-p0 BFS)

    static boolean inPocket(int x, int y) {
        for (int[] b : POCKETS) if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) return true;
        return false;
    }

    /** Fix 2: Karamja's west coast (tribesmen 2730,3161), west of karamjaBox's x 2740 edge. */
    static boolean karamjaWest(int x, int y) { return ON && x >= 2700 && x < 2740 && y >= 2880 && y <= 3200; }
}
