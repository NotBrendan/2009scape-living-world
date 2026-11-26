package content.custom;

import core.game.world.map.Location;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * P90 (-Dnav.p90Fix, default off; off = the train-40 code): two stuck spots (the design notes).
 *  1. Rune essence mine 2924,4848: Task.ESSENCE is declared with no tool list, so SmartAdventurers.start never gave the bot
 *     a pickaxe and the shop trip never bought one; at the rock the bot re-clicked "Mine" for ever (681 "idle 20 min" lines).
 *     Fix: the job's tools read PICKAXES for the essence mine (bank pick, buy, shop trip); a bot still without one drops the
 *     job; and Runner.work() ends an essence trip with no pickaxe at once (crew, rally and replay runners too).
 *  2. Karamja volcano dungeon 2839,9549: a trip that ended underground left a broke bot walking at the south wall towards a
 *     surface goal ("walking (no teleport money)"); entrance() was only used downwards. Fix: a free climb back up the
 *     entrance the bot came down (Runner.upDest), and no Grand Exchange detour planned from underground.
 */
final class P90 {
    private P90() { }
    static final boolean ON = Boolean.getBoolean("nav.p90Fix");
    static final AtomicInteger PICK = new AtomicInteger(), NOPICK = new AtomicInteger(), CLIMB = new AtomicInteger(), STOP = new AtomicInteger();

    static void count(String what, AtomicInteger c, String detail) {
        int n = c.incrementAndGet();
        if (n <= 5 || n % 25 == 0) System.out.println("[P90] " + what + " " + n + (detail == null ? "" : " (" + detail + ")") + " (P90)");
    }

    /** The tool list a job needs: the task's own, or PICKAXES for the essence mine (declared with none). */
    static int[] tools(AfkTasks.Task t) {
        if (t == null) return null;
        return ON && t == AfkTasks.Task.ESSENCE ? AfkTasks.PICKAXES : t.tools;
    }

    /** Underground inside a known entrance box with a surface goal: {foot underground, top on the surface}; else null. Pure. */
    static Location[] climbOut(Location here, Location where) {
        if (!ON || here == null || where == null || here.getZ() != 0 || here.getY() <= 6400) return null;
        if (where.getY() > 6400 || AfkTasks.Runner.essenceMine(here)) return null;
        Location[] e = AfkTasks.Runner.entrance(here); // {landing, top, foot}
        return e == null ? null : new Location[]{e[2], e[1]};
    }
}
