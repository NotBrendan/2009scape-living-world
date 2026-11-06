package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.*;

/**
 * Trial only (-Dtest.b21s=true; flags: -Dnav=true -Dsmartbots.count=0; SEED=live for live's grid): batch 21 B9, the single
 * surface failures. For each trip: the regions of both ends, Preflight's answer at pick time, a plan or none; then a
 * NavWalker walk of up to 5 minutes for the trips that should be walkable. Probes print the objects with an option on
 * the tiles the plan crosses at each stall. "seed": Scenarios.gatedStart keeps the prove seeder from starting there. "info":
 * walked and reported, not counted (none now: B9-8 walks since batch 21 item 4). Ends with RESULT lines and DONE.
 */
public final class TestB21S implements StartupListener {
    static void log(String s) { System.out.println("[TestB21S] " + s); }

    /** name, from x y, to x y, expect: "refuse" (Preflight refuses at pick), "walk" (walks there), "seed" (prove seed not made). */
    static final Object[][] TRIPS = {
            {"B9-1 Grud's stall (fishing platform)", 2654, 3284, 2778, 3291, "refuse"},
            {"B9-2 Ava's (Draynor Manor room)", 3012, 3356, 3093, 3357, "refuse"},
            {"B9-3 manor front door -> Lumbridge", 3108, 3354, 3222, 3218, "walk"},
            {"B9-4 Rasolo from the Fishing Guild bank", 2586, 3420, 2536, 3426, "seed"},
            {"B9-4b Rasolo from Seers' bank", 2725, 3491, 2536, 3426, "walk"},
            {"B9-5 moss giants island", 2654, 3284, 2201, 3812, "refuse"},
            {"B9-6 Falador castle stairs (BKF)", 3092, 3245, 2954, 3338, "walk"},
            {"B9-7 Frincos (Entrana)", 2809, 3441, 2807, 3342, "refuse"},
            {"B9-8 Dal's (Gu'Tanoth) from Yanille", 2612, 3092, 2518, 3035, "walk"}, // batch 21 item 4: fixed (NavWalker opens a door toward the step through it)
            {"B9-9 Karamja store from Shilo", 2852, 2955, 2906, 3145, "seed"},
            {"B6-4 2336,3636 (north of the Gnome Stronghold) from its gate", 2461, 3380, 2336, 3636, "walk"},
    };

    static final class W { boolean info; String name; AIPlayer bot; Location to; long arrivedT; int noRoute; Location best; double bestD = 1e9; final List<String> trail = new ArrayList<>(); }

    static int reg(Location l) { Location s = NavPlanner.standNear(l); return s == null ? 0 : NavPlanner.regionAt(s); }

    static String objs(Location c, int r) {
        StringBuilder b = new StringBuilder();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
            Scenery o = RegionManager.getObject(0, c.getX() + dx, c.getY() + dy);
            if (o == null || o.getDefinition() == null) continue;
            String[] opts = o.getDefinition().getOptions(); if (opts == null) continue;
            StringBuilder ob = new StringBuilder(); for (String op : opts) if (op != null && !op.equalsIgnoreCase("examine") && !op.equalsIgnoreCase("null")) ob.append(op).append('/');
            if (ob.length() == 0) continue;
            b.append(' ').append(o.getId()).append(' ').append(o.getName()).append(" @").append(o.getLocation().getX()).append(',').append(o.getLocation().getY()).append(" [").append(ob).append(']');
        }
        return b.toString();
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b21s")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, fails; long t0; final List<W> ws = new ArrayList<>();
            @Override public boolean pulse() {
                try {
                    t++;
                    long now = System.currentTimeMillis();
                    if (phase == 0 && t == 1200) { log("RESULT planner never came up"); log("DONE"); return true; }
                    if (phase == 0 && t >= 100 && NavPlanner.built) { phase = 1; unit(); t0 = now; return false; }
                    if (phase == 1 && now - t0 > 3_000) { phase = 2; t0 = now; log("walks start"); }
                    if (phase != 2) return false;
                    boolean all = true;
                    for (W w : ws) {
                        Location a = w.bot.getLocation();
                        double d = a.getDistance(w.to); if (d < w.bestD) { w.bestD = d; w.best = a; }
                        if (w.arrivedT == 0 && d <= 4) { w.arrivedT = now; log(w.name + " arrived after " + (now - t0) / 1000 + " s"); }
                        if (w.arrivedT != 0) continue;
                        all = false;
                        if (t % 3 != 0 || w.bot.getWalkingQueue().isMoving() || w.bot.getProperties().getTeleportLocation() != null) continue;
                        if (t % 50 == 0 && w.trail.size() < 24) w.trail.add(a.getX() + "," + a.getY());
                        Location h = NavWalker.step(w.bot, w.to, true);
                        if (h != null) NavWalker.walkTo(w.bot, h); else w.noRoute++;
                    }
                    if (all || now - t0 > 300_000L) { finish(); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            void unit() {
                for (Object[] tr : TRIPS) {
                    String name = (String) tr[0]; Location from = Location.create((Integer) tr[1], (Integer) tr[2], 0), to = Location.create((Integer) tr[3], (Integer) tr[4], 0); String exp = (String) tr[5];
                    AIPlayer probe = TestCR1.bot(from, 60, 60, 60, 60); Ledger.Wallet.give(probe, 2_000);
                    String why = Preflight.refuse(probe, to);
                    int rf = reg(from), rt = reg(to);
                    NavPlanner.Route r = NavPlanner.plan(from, to, 1, 0, true);
                    StringBuilder gates = new StringBuilder(); for (Gates.Gate g : Gates.at(from)) gates.append(g.id).append(' ');
                    log("trip " + name + ": regions " + rf + " (" + NavPlanner.regionSize(rf) + ") -> " + rt + " (" + NavPlanner.regionSize(rt) + "), plan " + (r == null ? "none" : r.corridor.length + " comps"));
                    log("   refuse '" + why + "', start in gate boxes [" + gates.toString().trim() + "], stranded '" + Preflight.stranded(probe, to) + "'");
                    boolean ok;
                    if ("refuse".equals(exp)) ok = why != null;
                    else if ("seed".equals(exp)) ok = Scenarios.gatedStart(from, to); // the prove seeder no longer starts here (a bank in a gate box the goal is not in)
                    else { ok = why == null; W w = new W(); w.name = name; w.to = to; w.bot = probe; w.info = "info".equals(exp); ws.add(w); }
                    log("RESULT PICK " + name + ": expect " + exp + " -> " + (ok ? "PASS" : "FAIL"));
                    if (!ok) fails++;
                }
                int[][] probes = {{3108, 3353}, {3120, 3356}, {3123, 3361}, {3109, 3358}, {3107, 3367}, {2953, 3337}, {2965, 3338}, {2611, 3396}, {2539, 3077}, {2536, 3091}, {2592, 3396}, {2539, 3091}, {2533, 3091}, {2504, 3062}};
                for (int[] p : probes) log("objects near " + p[0] + "," + p[1] + ":" + objs(Location.create(p[0], p[1], 0), 2) + " | " + NavGrid.describe(p[0], p[1]));
                StringBuilder cf = new StringBuilder("clipping (hex) by Yanille's south-west (B9-8 stall 2539,3077):");
                for (int y = 3080; y >= 3075; y--) { cf.append(" |").append(y).append(':'); for (int x = 2537; x <= 2541; x++) cf.append(' ').append(Integer.toHexString(RegionManager.getClippingFlag(0, x, y))); }
                log(cf.toString());
            }

            void finish() {
                for (W w : ws) {
                    boolean ok = w.arrivedT != 0;
                    if (!ok && !w.info) fails++;
                    if (!ok) { NavWalker.State st = NavWalker.STATES.get(w.bot.getUsername().toLowerCase(Locale.ROOT)); log(w.name + " planner: " + (st == null || st.route == null ? "no route" : "hop " + st.hop + "/" + st.route.hops.size() + " next " + (st.hop < st.route.hops.size() ? st.route.hops.get(st.hop) : null) + ", hops " + st.route.hops + ", doors " + st.route.doorTiles)); }
                    log("RESULT WALK " + w.name + ": " + (ok ? "arrived in " + (w.arrivedT - t0) / 1000 + " s" : "not arrived, at " + w.bot.getLocation() + ", closest " + w.best + " (" + Math.round(w.bestD) + " tiles)") + ", no-route steps " + w.noRoute + ", trail " + w.trail + (ok ? " -> PASS" : w.info ? " -> INFO (known, not fixed: see the B9 commit)" : " -> FAIL"));
                }
                log("RESULT summary: " + (fails == 0 ? "PASS" : "FAIL (" + fails + ")"));
                log("DONE");
            }
        });
    }
}
