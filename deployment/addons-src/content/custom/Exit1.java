package content.custom;

import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * exit1 (-Dnav.freeExit, default off; off = the train-50 code): a bot in the rune essence mine whose goal is on the surface
 * within TELEPORT_MIN of Aubury leaves by the portal, free; any other goal pays the flat fare as before (the design notes).
 * Before: AfkTasks.Runner.goTo measured a surface goal from the underground tile (~1,400+ tiles off in coordinates), so a bot
 * with coins in the rune essence mine paid a 248 gp "long-teleport" to reach the bank 20 tiles from Aubury (the portal out is
 * free in the real game). Ladder dungeons (Runner.entrance) already climb out free with -Dnav.p90Fix when the goal is within
 * TELEPORT_MIN of the ladder's top; this adds the essence mine, which P90.climbOut leaves out, and the log line and counter.
 * The free move is only mine <-> Aubury and ladder foot <-> ladder top: no other free travel. Emergency flee is untouched.
 */
final class Exit1 {
    private Exit1() { }
    static final boolean ON = Boolean.getBoolean("nav.freeExit");
    static final AtomicInteger FREE = new AtomicInteger();
    /** Aubury's shop: the surface tile of the essence mine's entrance, and where the portal out lands. */
    static final Location AUBURY = Location.create(3253, 3401, 0), PORTAL_OUT = Location.create(3253, 3398, 0);

    static boolean surface(Location l) { return l != null && l.getY() < 6400 && !AfkTasks.Runner.essenceMine(l); }

    /** True when the bot is in the essence mine, the goal is on the surface and within min tiles of Aubury: out by the portal, no fare. Flag off: never. Pure. */
    static boolean free(boolean on, Location here, Location where, int min) {
        return on && here != null && AfkTasks.Runner.essenceMine(here) && surface(where) && where.getDistance(AUBURY) <= min;
    }

    static void left(Player p, String place) {
        int n = FREE.incrementAndGet();
        System.out.println("[Exit] " + (p == null ? "?" : p.getUsername()) + " left " + place + " by the entrance (free)" + (n % 25 == 0 ? " (free exits " + n + ")" : ""));
    }
}
