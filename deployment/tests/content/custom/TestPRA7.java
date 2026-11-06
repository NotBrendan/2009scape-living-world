package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.pra7=true): pathing review 10-23 A-7. A bot's AFK runner (3-tick pulse, as smart bots run it) bound
 * for a Taverley-dungeon spot, starting where the Falador teleport lands (2965,3380), walks to the dungeon ladder
 * (2884,3396) and climbs down. Logs where it is every 15 s; RESULT: climbed down after N s, or where it stuck (300 s).
 */
public final class TestPRA7 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRA7] " + s); }
    static void map() {
        for (int x = 2870; x <= 2975; x += 8) for (int y = 3320; y <= 3450; y += 8) TestPRA5.load(x, y);
        for (int y = Integer.getInteger("test.pra7.y1", 3405); y >= Integer.getInteger("test.pra7.y0", 3360); y--) {
            StringBuilder s = new StringBuilder(String.valueOf(y)).append(' ');
            for (int x = 2875; x <= 2970; x++) {
                int f = core.game.world.map.RegionManager.getClippingFlag(0, x, y);
                s.append(f == -1 ? '?' : (f & 0x200000) != 0 || (f & 0x100) != 0 ? '#' : (f & 0xff) != 0 ? '+' : '.');
            }
            log("map " + s);
        }
        log("map x from 2875 (left) to 2970");
        for (int dx = -45; dx <= 10; dx++) for (int dy = -25; dy <= 25; dy++) {
            core.game.node.scenery.Scenery sc = core.game.world.map.RegionManager.getObject(0, 2940 + dx, 3380 + dy);
            if (sc == null || sc.getName() == null) continue;
            String n = sc.getName().toLowerCase();
            if (n.contains("door") || n.contains("gate") || n.contains("ladder")) log("obj " + sc.getName() + " id " + sc.getId() + " at " + sc.getLocation() + " opts " + java.util.Arrays.toString(sc.getDefinition().getOptions()));
        }
    }

    static final int[][] LEGS = {{2946, 3429, 2940, 3440}, {2940, 3440, 2920, 3440}, {2946, 3429, 2936, 3423}, {2936, 3418, 2910, 3430}, {2945, 3415, 2936, 3418}, {2964, 3398, 2964, 3430}, {2964, 3430, 2935, 3445}, {2935, 3445, 2915, 3435}, {2915, 3435, 2890, 3405}, {2920, 3440, 2900, 3420}, {2945, 3400, 2938, 3412}};
    @Override public void startup() {
        if (Boolean.getBoolean("test.pra7.probe")) GameWorld.getPulser().submit(new Pulse(1) {
            int t, i; AIPlayer b;
            @Override public boolean pulse() {
                t++;
                if (t == 40) b = TestCR1.bot(Location.create(LEGS[0][0], LEGS[0][1], 0), 40, 20, 20, 20);
                if (t < 44 || t % 4 != 0) return false;
                if (i >= LEGS.length) { log("PROBE DONE"); return true; }
                int[] l = LEGS[i];
                if (b.getLocation().getX() != l[0] || b.getLocation().getY() != l[1]) { b.getProperties().setTeleportLocation(Location.create(l[0], l[1], 0)); return false; }
                core.game.world.map.path.Path pa = core.game.world.map.path.Pathfinder.find(b, Location.create(l[2], l[3], 0));
                StringBuilder ps = new StringBuilder(); int n = 0; if (pa != null) for (core.game.world.map.Point pt : pa.getPoints()) { if (n++ % 5 == 0) ps.append(pt.getX()).append(',').append(pt.getY()).append(' '); }
                Location end = pa != null && !pa.getPoints().isEmpty() ? Location.create(pa.getPoints().peekLast().getX(), pa.getPoints().peekLast().getY(), 0) : null;
                log("probe leg " + l[0] + "," + l[1] + " -> " + l[2] + "," + l[3] + ": " + (pa == null ? "null" : (pa.isSuccessful() ? "ok" : "FAIL") + (pa.isMoveNear() ? " near" : "") + " " + pa.getPoints().size() + " steps, ends " + end + " (" + (end == null ? -1 : (int) end.getDistance(Location.create(l[2], l[3], 0))) + " short): " + ps));
                i++; return false;
            }
        });
        if (!Boolean.getBoolean("test.pra7")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer b; AfkTasks.Runner r; long t0; String lastLast = "";
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 40 && Boolean.getBoolean("test.pra7.map")) map();
                    if (t == 45) { b = TestCR1.bot(Location.create(2965, 3380, 0), 60, 40, 40, 40); return false; }
                    if (t == 48 && Boolean.getBoolean("test.pra7.map")) {
                        for (int[] q : new int[][]{{2925, 3383}, {2930, 3350}, {2930, 3400}, {2935, 3389}, {2920, 3395}}) {
                            core.game.world.map.path.Path pa = core.game.world.map.path.Pathfinder.find(b, Location.create(q[0], q[1], 0));
                            StringBuilder ps = new StringBuilder(); int n = 0; if (pa != null) for (core.game.world.map.Point pt : pa.getPoints()) { if (n++ % 4 == 0) ps.append(pt.getX()).append(',').append(pt.getY()).append(' '); }
                            log("path from " + b.getLocation() + " to " + q[0] + "," + q[1] + ": " + (pa == null ? "null" : (pa.isSuccessful() ? "ok" : "FAIL") + (pa.isMoveNear() ? " (near)" : "") + " " + pa.getPoints().size() + " steps: " + ps));
                        }
                    }
                    if (t == 50) {
                        r = new AfkTasks.Runner(b, AfkTasks.Task.COW, false, 0, false, false);
                        r.walker = true; r.spot = Location.create(2887, 9800, 0);
                        r.goTo(AfkTasks.State.TO_SPOT, r.spot); r.setDelay(3);
                        AfkTasks.RUNNING.put(b.getUsername().toLowerCase(), r); GameWorld.getPulser().submit(r);
                        t0 = System.currentTimeMillis();
                        log("walker " + b.getUsername() + " at " + b.getLocation() + " bound for " + r.spot + ": dest " + r.dest + ", underDest " + r.underDest);
                        return false;
                    }
                    if (r == null) return false;
                    long secs = (System.currentTimeMillis() - t0) / 1000;
                    if (t % 100 == 0) { Object[] pl = RouteMap.PLANS.get(b.getUsername().toLowerCase()); log("   plan " + (pl == null ? "none" : "at " + pl[2] + " of " + pl[1])); }
                    if (t % 25 == 0) log("   " + secs + " s at " + b.getLocation() + ", state " + r.state + ", last '" + r.last + "'");
                    if (b.getLocation().getY() > 6400) { log("RESULT climbed down after " + secs + " s, now at " + b.getLocation()); r.halt(); log("DONE"); return true; }
                    if (r.halted) { log("RESULT runner ended after " + secs + " s at " + b.getLocation() + ", last '" + r.last + "'"); log("DONE"); return true; }
                    if (secs > 300) { log("RESULT NOT down after 300 s: at " + b.getLocation() + " (" + (int) b.getLocation().getDistance(Location.create(2884, 3396, 0)) + " tiles from the ladder), last '" + r.last + "'"); r.halt(); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
