package content.custom;

import core.api.StartupListener;
import core.game.node.entity.npc.NPC;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.List;

/** Trial only (-Dtest.prprobe=true): facts for the pathing review 10-23 tests. */
public final class TestPRProbe2 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRProbe2] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prprobe2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 30) return false;
                if (!NavPlanner.built) { if (t < 300) return false; }
                try { // Q-1: pairs whose learned route first leads away from the goal
                    int shown = 0;
                    for (int x = 3150; x <= 3290 && shown < 8; x += 7) for (int y = 3160; y <= 3320 && shown < 8; y += 7) for (int[] dd : new int[][]{{28, 0}, {-28, 0}, {0, 28}, {0, -28}, {22, 22}, {-22, 22}}) {
                        Location a = Surveyors.Surveyor.walkable(Location.create(x, y, 0)), b = Surveyors.Surveyor.walkable(Location.create(x + dd[0], y + dd[1], 0));
                        if (a == null || b == null || a.getDistance(b) < 22) continue;
                        List<Location> r = RouteMap.plan(a, b, 1);
                        if (r == null || r.size() < 3) continue;
                        double d0 = a.getDistance(b), worst = 0, len = 0; Location prev = a;
                        for (Location w : r) { worst = Math.max(worst, w.getDistance(b) - d0); len += prev.getDistance(w); prev = w; }
                        if (worst >= 10 && len < 4 * d0) { shown++; log("Q1 " + a + " -> " + b + " straight " + (int) d0 + ", route " + (int) len + ", farthest waypoint +" + (int) worst + ": " + r); }
                    }
                } catch (Throwable e) { log("q1 " + e); }
                try {
                    int bad = 0, shops = 0;
                    for (Object[] s : BotChat.WorldData.shops) {
                        shops++;
                        int x = (Integer) s[1], y = (Integer) s[2];
                        try { core.game.world.map.Region r = RegionManager.forId((x >> 6) << 8 | (y >> 6)); if (r != null && !r.isLoaded()) core.game.world.map.Region.load(r); } catch (Throwable ignored) { }
                        int f = RegionManager.getClippingFlag(0, x, y);
                        if (f == -1 || (f & 0x200000) != 0 || (f & 0x100) != 0 || (f & 0x40000) != 0) { bad++; if (bad <= 12) log("T4 shop " + s[0] + " at " + x + "," + y + " flag 0x" + Integer.toHexString(f) + " walkable() " + Surveyors.Surveyor.walkable(Location.create(x, y, 0))); }
                    }
                    log("T4 " + bad + " of " + shops + " shop tiles are not standable (regions loaded)");
                } catch (Throwable e) { log("t4 " + e); }
                try {
                    int found = 0, doors = 0, multi = 0;
                    for (int x = NavGrid.X0 + 1; x < NavGrid.X0 + NavGrid.W - 1; x++) for (int y = NavGrid.Y0 + 1; y < NavGrid.Y0 + NavGrid.H - 1; y++) {
                        int b = NavGrid.grid[NavGrid.idx(x, y)];
                        if ((b & NavGrid.DOOR) == 0) continue;
                        doors++;
                        int wb = b & (NavGrid.WN | NavGrid.WE | NavGrid.WS | NavGrid.WW);
                        if (Integer.bitCount(wb) >= 2) { multi++; if (multi <= 10) log("N1 door tile with several walls: " + NavGrid.describe(x, y)); }
                    }
                    log("N1 " + doors + " door tiles on the surface grid, " + multi + " of them with walls on 2+ sides");
                } catch (Throwable e) { log("n1 " + e); }
                log("DONE");
                return true;
            }
        });
    }
}
