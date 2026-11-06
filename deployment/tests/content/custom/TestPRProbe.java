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
public final class TestPRProbe implements StartupListener {
    static void log(String s) { System.out.println("[TestPRProbe] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prprobe")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 30) return false;
                if (!NavPlanner.built) { if (t < 300) return false; }
                try {
                    for (List<RouteMap.Portal> l : RouteMap.PORTALS.values()) for (RouteMap.Portal p : l)
                        log("portal " + p.sc.name + " '" + p.sc.option + "' id " + p.sc.id + " at " + p.sc.key() + " from " + p.fx + "," + p.fy + " -> " + p.tx + "," + p.ty + " ok " + p.ok + " fail " + p.fail + " needs " + p.needs());
                } catch (Throwable e) { log("portals " + e); }
                try {
                    List<Location> r = RouteMap.plan(Location.create(3250, 3180, 0), Location.create(3225, 3180, 0), 1);
                    log("Q1 plan (3250,3180)->(3225,3180): " + r);
                    log("Q1 walkable 3250,3180: " + Surveyors.Surveyor.walkable(Location.create(3250, 3180, 0)) + "; 3225,3180: " + Surveyors.Surveyor.walkable(Location.create(3225, 3180, 0)) + "; nav reachable " + NavPlanner.reachable(Location.create(3250, 3180, 0), Location.create(3225, 3180, 0)));
                    NavPlanner.Route nr = NavPlanner.plan(Location.create(3250, 3180, 0), Location.create(3225, 3180, 0), 1);
                    log("Q1 navplan: " + (nr == null ? "none" : nr.tiles + " tiles hops " + nr.hops));
                } catch (Throwable e) { log("q1 " + e); }
                try {
                    Location c = Location.create(2903, 9799, 0);
                    for (int dx = -30; dx <= 30; dx++) for (int dy = -30; dy <= 30; dy++) {
                        Scenery s = RegionManager.getObject(0, c.getX() + dx, c.getY() + dy);
                        if (s == null || s.getName() == null) continue;
                        String n = s.getName().toLowerCase();
                        if (n.contains("ladder") || n.contains("stair") || n.contains("trapdoor")) log("Q2 " + s.getName() + " id " + s.getId() + " at " + s.getLocation() + " opts " + java.util.Arrays.toString(s.getDefinition().getOptions()));
                    }
                } catch (Throwable e) { log("q2 " + e); }
                try {
                    for (NPC n : RegionManager.getLocalNpcs(Location.create(2840, 3432, 0), 20)) {
                        if (n == null || n.getName() == null || !n.getName().toLowerCase().contains("fishing")) continue;
                        StringBuilder o = new StringBuilder();
                        try { for (core.game.interaction.Option op : n.getInteraction().getOptions()) if (op != null) o.append(op.getName()).append('/'); } catch (Throwable ignored) { }
                        log("T2 fishing spot id " + n.getId() + " at " + n.getLocation() + " options " + o);
                    }
                } catch (Throwable e) { log("t2 " + e); }
                try {
                    int bad = 0, shops = 0;
                    for (Object[] s : BotChat.WorldData.shops) {
                        shops++;
                        int x = (Integer) s[1], y = (Integer) s[2];
                        int f = RegionManager.getClippingFlag(0, x, y);
                        if (f == -1 || (f & 0x200000) != 0 || (f & 0x100) != 0 || (f & 0x40000) != 0) { bad++; if (bad <= 12) log("T4 shop " + s[0] + " at " + x + "," + y + " flag 0x" + Integer.toHexString(f) + " walkable() " + Surveyors.Surveyor.walkable(Location.create(x, y, 0))); }
                    }
                    log("T4 " + bad + " of " + shops + " shop tiles are not standable");
                } catch (Throwable e) { log("t4 " + e); }
                try {
                    int found = 0, doors = 0;
                    for (int x = 3080; x <= 3330; x++) for (int y = 3140; y <= 3360; y++) {
                        int b = NavGrid.grid[NavGrid.idx(x, y)];
                        if ((b & NavGrid.DOOR) == 0) continue;
                        doors++;
                        Scenery o = RegionManager.getObject(0, x, y);
                        for (int d = 0; d < 4; d++) {
                            int nx = x + NavGrid.DX[d], ny = y + NavGrid.DY[d];
                            int a2 = NavGrid.grid[NavGrid.idx(nx, ny)];
                            boolean wall = (b & NavGrid.WALL_FROM[d]) != 0 || (a2 & NavGrid.WALL_INTO[d]) != 0;
                            if (!wall || !NavGrid.canStep(x, y, d)) continue;
                            int doorEdge = o == null ? -1 : o.getType() == 0 ? new int[]{3, 0, 1, 2}[o.getRotation() & 3] : -2; // rot 0 W, 1 N, 2 E, 3 S
                            if (doorEdge == d) continue;
                            found++;
                            if (found <= 15) log("N1 door tile " + x + "," + y + " " + (o == null ? "?" : o.getName() + " type " + o.getType() + " rot " + o.getRotation()) + ": step " + "NESW".charAt(d) + " crosses a wall (" + NavGrid.describe(x, y) + " | next " + NavGrid.describe(nx, ny) + ")");
                        }
                    }
                    log("N1 " + doors + " door tiles in the box, " + found + " wall crossings off the door's own edge");
                } catch (Throwable e) { log("n1 " + e); }
                log("DONE");
                return true;
            }
        });
    }
}
