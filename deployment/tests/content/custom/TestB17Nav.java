package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trial only (-Dtest.b17nav=true, with -Dnav=true so NavWalker.ON is live; smartbots walking as usual): batch 17 item1
 * (audit N-14, review P-10), the planner during a grid/graph rebuild. Once the graph is up, a set of known trips is planned
 * (the ones that route are kept). Then two rebuilds, each probed by the game thread every tick and by a side thread every 20 ms:
 *   G: NavPlanner.buildGraph() alone on a thread (the graph rebuild path, ~1-2 s);
 *   B: NavGrid.build(null) (the ::navbuild path, ~7 min) until the planner is back on.
 * Every probe is one of: "fallback" (built false: NavWalker.step returns null, the caller walks the old way), "ok", "null while
 * built" (a failed step), "exception", "live on a half-read grid" (built true while NavGrid.building). Expected before: nulls or
 * exceptions in G, thousands of half-read probes in B; after: none of those, only fallback then ok, and the planner resumes.
 * -Dtest.b17nav.skipBuild=true skips B (G only).
 */
public final class TestB17Nav implements StartupListener {
    static void log(String s) { System.out.println("[TestB17Nav] " + s); }
    static final int[][] TRIPS = {{3222, 3218, 3093, 3244}, {3212, 3428, 2965, 3380}, {3200, 3227, 3014, 3204}, {3232, 3248, 3165, 3487}, {3192, 3292, 3049, 3234}, {3093, 3244, 3222, 3218}, {2965, 3380, 3212, 3428}, {3254, 3420, 3080, 3250}};
    static final List<int[]> GOOD = new ArrayList<>();
    static final AtomicInteger ok = new AtomicInteger(), nul = new AtomicInteger(), exc = new AtomicInteger(), fallback = new AtomicInteger(), halfRead = new AtomicInteger();
    static volatile String firstExc;

    static void probe() {
        for (int[] t : GOOD) {
            boolean b = NavPlanner.built, building = NavGrid.building;
            if (!b) { fallback.incrementAndGet(); continue; }
            if (building) halfRead.incrementAndGet();
            try {
                NavPlanner.Route r = NavPlanner.plan(Location.create(t[0], t[1], 0), Location.create(t[2], t[3], 0), 1);
                if (r != null) ok.incrementAndGet();
                else if (NavPlanner.built) nul.incrementAndGet(); else fallback.incrementAndGet(); // built went off during the call: a fallback
            } catch (Throwable e) { exc.incrementAndGet(); if (firstExc == null) { firstExc = e.toString(); e.printStackTrace(System.out); } }
        }
    }
    static void reset() { ok.set(0); nul.set(0); exc.set(0); fallback.set(0); halfRead.set(0); firstExc = null; }
    static String counts() { return "ok " + ok + ", null while built " + nul + ", exceptions " + exc + (firstExc == null ? "" : " (first: " + firstExc + ")") + ", live on a half-read grid " + halfRead + ", fallback to the old walker " + fallback; }

    static volatile boolean probing;
    static void sideProbe() { probing = true; Thread t = new Thread(() -> { while (probing) { probe(); try { Thread.sleep(20); } catch (InterruptedException e) { return; } } }, "TestB17NavProbe"); t.setDaemon(true); t.start(); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17nav")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, ticks; long t0; boolean sawOff;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (phase == 0) {
                        if (!NavPlanner.built) { if (t > 600) { log("RESULT graph never came up"); log("DONE"); return true; } return false; }
                        for (int[] tr : TRIPS) { NavPlanner.Route r = NavPlanner.plan(Location.create(tr[0], tr[1], 0), Location.create(tr[2], tr[3], 0), 1); if (r != null) GOOD.add(tr); }
                        log("warm-up: " + GOOD.size() + " of " + TRIPS.length + " trips route; NavWalker.ON " + NavWalker.ON + ", components " + NavPlanner.comps);
                        reset(); phase = 1; ticks = 0; sideProbe();
                        Thread g = new Thread(NavPlanner::buildGraph, "TestB17NavGraph"); g.setDaemon(true); g.start(); t0 = System.currentTimeMillis();
                        log("G: NavPlanner.buildGraph() started");
                        return false;
                    }
                    probe(); ticks++;
                    if (phase == 1) {
                        if (ticks < 15) return false; // ~9 s: the graph takes 1-2 s
                        probing = false;
                        log("RESULT G (graph rebuild, " + ticks + " ticks probed): " + counts() + "; planner on after: " + NavPlanner.built);
                        if (Boolean.getBoolean("test.b17nav.skipBuild")) { log("DONE"); return true; }
                        reset(); phase = 2; ticks = 0; sideProbe(); t0 = System.currentTimeMillis();
                        NavGrid.build(null);
                        log("B: NavGrid.build(null) started (the ::navbuild path); planner on right after the call: " + NavPlanner.built + ", grid building " + NavGrid.building);
                        return false;
                    }
                    if (phase == 2) {
                        if (!NavPlanner.built) sawOff = true;
                        if (ticks % 100 == 0) log("B tick " + ticks + ": building " + NavGrid.building + ", planner on " + NavPlanner.built + "; " + counts());
                        boolean done = !NavGrid.building && NavPlanner.built && ticks > 20;
                        if (!done && ticks < 1500) return false;
                        if (done) { for (int k = 0; k < 15; k++) probe(); } // a few probes with the new graph
                        probing = false;
                        int resumed = 0; for (int[] tr : GOOD) if (NavPlanner.plan(Location.create(tr[0], tr[1], 0), Location.create(tr[2], tr[3], 0), 1) != null) resumed++;
                        log("RESULT B (::navbuild path, " + ticks + " ticks, " + (System.currentTimeMillis() - t0) / 1000 + " s): " + counts() + "; planner was off during the build " + sawOff + ", finished " + done + ", trips routing after: " + resumed + "/" + GOOD.size());
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
