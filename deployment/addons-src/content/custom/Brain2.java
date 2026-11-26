package content.custom;

import core.game.world.map.Location;

import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * brain2 (-Dbrain.fix2, default off; acts only with -Dbrain.fix1): two job-choice rules, kept pure here so they have a plain
 * main() test (TestP40Broke) and one caller each in SmartAdventurers.Brain.chooseJob (rule 1 also in Slayer.openGate).
 *
 * Rule 1, hunt bank gate. A hunt trip that fills its pack asks AfkTasks.Runner.nearestBank0 for a bank and ends with
 * "No bank close enough to walk to" when there is none: a bank counts within 220 tiles of where the bot stands, Al Kharid's
 * (x 3270) only from x >= 3268 (the toll gate), and only one this bot may enter (Gates). The Desert Wolf spot [3258, 3078] is
 * 12 tiles west of that line and 233 from Draynor, the Jackal spot has no bank at all, so every such trip (a Slayer WOLVES / DOG
 * task trip: chooseJob -> Slayer.taskTrip, 185 gp teleport and toll) ended at its first full pack. bankIndex() is that same
 * test asked from the hunt spot before the job is a candidate; with a bank in range the planner is asked for the bank -> spot
 * leg and "no route" (-1) refuses the hunt too (a bank beyond the Shantay Pass: Ugthankis). Surface spots only: underground
 * the trip measures from the dungeon tile and that case is left as it is.
 *
 * Rule 2, broke repeat. brain.fix1 (B-M2) gave the broke score the jitter and the last job's x0.5. The x0.5 sends a broke bot
 * (most of the fleet) away from the cheap job at its feet every second choice. With fix2 the last job keeps its full score
 * when it is the bot's nearest money job (the smallest distance among this choice's candidates that sell for something or pay
 * coins); the jitter stays, and bots with coins keep the x0.5 "vary the day".
 */
final class Brain2 {
    static boolean ON = Boolean.getBoolean("brain.fix1") && Boolean.getBoolean("brain.fix2");
    static final boolean ROUTE = !"false".equals(System.getProperty("brain.fix2.route")); // the bank -> spot planner half of rule 1 (default on with fix2; =false leaves only "noBank")
    static final int BANK_RANGE = 220; // AfkTasks.Runner.nearestBank0: "double d = 220"
    static final int AL_KHARID_X = 3270, TOLL_X = 3268; // nearestBank0: "b.getX() == 3270 && p.getLocation().getX() < 3268"
    static volatile int noBank, noBankRoute, repeatKept;

    /** nearestBank0's choice for a bot standing at `at` (no hub swap: that only picks between banks that pass): index in banks, -1 = none. */
    static int bankIndex(Location at, Location[] banks, Predicate<Location> mayEnter) { return bankIndex(false, null, at, banks, mayEnter); }
    /** slayer9 (-Dbots.slayer.fix9): with fix9 a spot on the desert side of the Shantay Pass keeps Al Kharid's bank (Slayer.tollShut9, nearestBank0's rule). */
    static int bankIndex(boolean fix9, Predicate<Location> desertSide, Location at, Location[] banks, Predicate<Location> mayEnter) {
        if (at == null || banks == null) return -1;
        int best = -1; double d = BANK_RANGE;
        for (int i = 0; i < banks.length; i++) {
            Location b = banks[i];
            if (fix9 ? Slayer.tollShut9(true, b, at, desertSide) : b.getX() == AL_KHARID_X && at.getX() < TOLL_X) continue;
            if (mayEnter != null && !mayEnter.test(b)) continue;
            double dist = b.getDistance(at);
            if (dist < d) { d = dist; best = i; }
        }
        return best;
    }

    /** Rule 1 applies to this spot: a surface tile (the trip's bank test from a dungeon tile is not modelled). */
    static boolean surface(Location spot) { return spot != null && spot.getZ() == 0 && spot.getY() <= 6400; }

    /**
     * Rule 1: null = the hunt stays a candidate, "noBank" = no bank the trip could use from the spot, "noBankRoute" = the planner
     * has no route from that bank to the spot. route: bank index -> planned tiles (-1 no route, anything else = fine or not known).
     */
    static String huntGate(boolean on, Location spot, Location[] banks, Predicate<Location> mayEnter, IntUnaryOperator route) { return huntGate(on, false, null, spot, banks, mayEnter, route); }
    static String huntGate(boolean on, boolean fix9, Predicate<Location> desertSide, Location spot, Location[] banks, Predicate<Location> mayEnter, IntUnaryOperator route) {
        if (!on || !surface(spot)) return null;
        int bi = bankIndex(fix9, desertSide, spot, banks, mayEnter);
        if (bi < 0) return "noBank";
        if (route != null && route.applyAsInt(bi) == -1) return "noBankRoute";
        return null;
    }

    /** Rule 2: the broke bot's last job gets its x0.5 back (score x2) when it is a money job and no other money job is nearer. */
    static boolean keepRepeat(boolean on, boolean broke, boolean lastIsMoney, double lastDist, double nearestMoneyDist) {
        return on && broke && lastIsMoney && lastDist <= nearestMoneyDist;
    }
    static double fullScore(double halved) { return halved * 2.0; }
}
