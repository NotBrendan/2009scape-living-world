package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.pra8=true): pathing review 10-23 A-8. (1) Runner.stepTo on a 200-tile walk (west of Varrock towards
 * Falador), 20 times from the same tile: which leg it took and the time per call. (2) A runner with a RouteMap plan
 * finishes: is the plan still there afterwards?
 */
public final class TestPRA8 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRA8] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.pra8")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, n; AIPlayer b; AfkTasks.Runner r; final Location start = Location.create(3165, 3400, 0), dest = Location.create(2965, 3380, 0); long nanos; final java.util.Map<String, Integer> legs = new java.util.TreeMap<>();
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 90) { b = TestCR1.bot(start, 40, 20, 20, 20); return false; }
                    if (t == 95) { r = new AfkTasks.Runner(b, AfkTasks.Task.COW, false, 0, false, false); r.dest = dest; }
                    if (r == null || t < 95) return false;
                    if (n < 20) {
                        if (!b.getLocation().equals(start)) { b.getWalkingQueue().reset(); b.getProperties().setTeleportLocation(start); return false; }
                        long t0 = System.nanoTime();
                        r.stepTo(b.getLocation().getDistance(dest));
                        nanos += System.nanoTime() - t0;
                        String leg = r.last.replaceAll("[0-9]+,[0-9]+", "").replaceAll(" -?[0-9]+ ", " ").trim();
                        legs.merge(leg + " (" + r.last.replaceAll(".* ", "") + ")", 1, Integer::sum);
                        b.getWalkingQueue().reset();
                        n++;
                        if (n == 20) log("RESULT stepTo x20 on a " + (int) start.getDistance(dest) + "-tile walk: " + nanos / 20 / 1000 + " us a call; legs " + legs);
                        return false;
                    }
                    // (2) the plan left behind by finish()
                    String k = b.getUsername().toLowerCase();
                    RouteMap.Route rt = new RouteMap.Route(); rt.add(Location.create(3150, 3400, 0)); rt.add(dest);
                    RouteMap.PLANS.put(k, new Object[]{RouteMap.cell(dest.getX(), dest.getY()), rt, 0, System.currentTimeMillis()});
                    r.finish("Test over.");
                    log("RESULT after finish(): RouteMap plan " + (RouteMap.PLANS.containsKey(k) ? "STILL THERE" : "gone") + " (runner halted " + r.halted + ")");
                    log("DONE"); return true;
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
            }
        });
    }
}
