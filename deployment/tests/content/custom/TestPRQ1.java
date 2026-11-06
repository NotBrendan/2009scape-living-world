package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * Trial only (-Dtest.prq1=true): pathing review 10-23 Q-1. The quest Mover on a learned route that first leads away from the
 * goal (the route the map hands it goes 30 tiles north and 25 east before coming back south past the goal's row). Each stretch of the route is
 * checked open with the planner first, so any stall is the Mover's own.
 */
public final class TestPRQ1 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRQ1] " + s); }
    static final int[][] BASES = {{3285, 3245}, {3283, 3240}, {3288, 3250}, {3290, 3240}, {3280, 3235}, {3285, 3230}, {3020, 3300}, {3060, 3300}};
    static boolean open(Location a, Location b) {
        NavPlanner.Route r = NavPlanner.plan(a, b, 1);
        int man = Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
        return r != null && r.doors == 0 && r.tiles <= man * 1.15 + 2 && NavGrid.standable(a.getX(), a.getY()) && NavGrid.standable(b.getX(), b.getY());
    }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prq1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, ticks = -1, sidesteps; AIPlayer bot; final QuestSteps.Mover m = new QuestSteps.Mover(); String said = ""; long f0; Location dest, turn; List<Location> route; String detour;
            @Override public boolean pulse() {
                try {
                    if (++t < 50 || !NavPlanner.built) return t > 900;
                    if (ticks < 0) {
                        for (int[] b : BASES) {
                            Location s = Location.create(b[0], b[1], 0), w1 = Location.create(b[0], b[1] + 30, 0), w2 = Location.create(b[0] + 25, b[1] + 30, 0), w3 = Location.create(b[0] + 25, b[1] - 28, 0), d = Location.create(b[0], b[1] - 28, 0);
                            if (!open(s, w1) || !open(w1, w2) || !open(w2, w3) || !open(w3, d)) { log("base " + s + ": a stretch isn't open, next"); continue; }
                            dest = d; turn = w3; route = new ArrayList<>(); route.add(Location.create(b[0], b[1] + 15, 0)); route.add(w1); route.add(Location.create(b[0] + 12, b[1] + 30, 0)); route.add(w2); for (int yy = b[1] + 12; yy > b[1] - 28; yy -= 18) route.add(Location.create(b[0] + 25, yy, 0)); route.add(w3); route.add(d);
                            bot = new AIPlayer(s); break;
                        }
                        if (bot == null) { log("no open place found"); log("DONE"); return true; }
                        m.reset(dest, 2); ticks = 0; f0 = RouteMap.failures;
                        log("bot at " + bot.getLocation() + " -> " + dest + " (straight " + (int) bot.getLocation().getDistance(dest) + "), learned route " + route);
                        return false;
                    }
                    ticks++;
                    String k = bot.getUsername().toLowerCase();
                    if (ticks <= 3) { RouteMap.PLANS.put(k, new Object[]{RouteMap.cell(dest.getX(), dest.getY()), new ArrayList<>(route), 0, System.currentTimeMillis()}); return false; } // the map's plan for this trip
                    QuestSteps.St s = m.tick(bot);
                    if (!m.last.equals(said)) { said = m.last; if ("sidestep".equals(m.last) || "stalled".equals(m.last)) sidesteps++; log("   tick " + ticks + " at " + bot.getLocation() + " (" + (int) bot.getLocation().getDistance(dest) + " from the goal): " + m.last); }
                    if (detour == null && bot.getLocation().getDistance(turn) <= 4) detour = "the detour (out and round to " + turn + ") walked in " + ticks + " ticks with " + (RouteMap.failures - f0) + " RouteMap.stuck failures and " + sidesteps + " sidesteps";
                    if (s == QuestSteps.St.RUNNING && ticks < 400) return false;
                    log("RESULT " + (detour != null ? detour : "never got round the detour to " + turn));
                    log("RESULT " + (s == QuestSteps.St.DONE ? "DONE" : s == QuestSteps.St.FAILED ? "FAILED " + m.last : "TIMEOUT") + " in " + ticks + " ticks at " + bot.getLocation() + ": RouteMap.stuck failures learned " + (RouteMap.failures - f0) + ", sidesteps " + sidesteps + ", off-route " + (RouteMap.OFF_ROUTE.getOrDefault(k, 0L) > System.currentTimeMillis()));
                    log("DONE"); return true;
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
            }
        });
    }
}
