package content.custom;

import core.api.StartupListener;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.Region;
import core.game.world.map.RegionManager;

/**
 * Trial only (-Dtest.prn1=true -Dnav=true): pathing review 10-23 N-1. Every door tile of the surface grid with walls on two or
 * more sides: which of its walls does the grid let a route through, and do routes go through the wall beside the door?
 */
public final class TestPRN1 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRN1] " + s); }
    static int edges(Scenery o) { // the edges the door object stands on (as ChunkFlags.flagDoorObject), -1 unknown
        if (o == null) return -1; int r = o.getRotation() & 3;
        if (o.getType() == 0) return new int[]{NavGrid.WW, NavGrid.WN, NavGrid.WE, NavGrid.WS}[r];
        if (o.getType() == 2) return new int[]{NavGrid.WW | NavGrid.WN, NavGrid.WN | NavGrid.WE, NavGrid.WE | NavGrid.WS, NavGrid.WS | NavGrid.WW}[r];
        return -1;
    }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prn1")) return;
        GameWorld.getPulser().submit(new Pulse(5) {
            int t;
            @Override public boolean pulse() {
                if (!NavPlanner.built) return ++t > 200;
                try {
                    int tiles = 0, walls = 0, open = 0, routed = 0;
                    for (int i = 0; i < NavGrid.grid.length; i++) {
                        int b = NavGrid.grid[i];
                        if ((b & NavGrid.DOOR) == 0 || Integer.bitCount(b & (NavGrid.WN | NavGrid.WE | NavGrid.WS | NavGrid.WW)) < 2) continue;
                        int x = NavGrid.X0 + i % NavGrid.W, y = NavGrid.Y0 + i / NavGrid.W; tiles++;
                        Region r = RegionManager.forId(((x >> 6) << 8) | (y >> 6)); if (r != null && !r.isLoaded()) Region.load(r);
                        Scenery o = RegionManager.getObject(0, x, y);
                        int de = edges(o);
                        for (int d = 0; d < 4; d++) {
                            int nx = x + NavGrid.DX[d], ny = y + NavGrid.DY[d];
                            if ((b & NavGrid.WALL_FROM[d]) == 0 || de < 0 || (de & NavGrid.WALL_FROM[d]) != 0) continue; // no wall there, unknown door shape, or the door's own edge
                            if (!NavGrid.standable(nx, ny)) continue;
                            walls++;
                            boolean out = NavGrid.canStep(x, y, d), in = NavGrid.canStep(nx, ny, (d + 2) % 4);
                            if (out || in) open++;
                            NavPlanner.Route rt = NavPlanner.plan(Location.create(x, y, 0), Location.create(nx, ny, 0), 99);
                            boolean through = rt != null && rt.tiles <= 2;
                            if (through) routed++;
                            log("door tile " + x + "," + y + " (" + (o == null ? "?" : o.getName() + " type " + o.getType() + " rot " + o.getRotation()) + "): wall " + "NESW".charAt(d) + " (not the door's edge) passable out " + out + " / in " + in + "; route to " + nx + "," + ny + ": " + (rt == null ? "none" : rt.tiles + " tiles, " + rt.doors + " doors") + (through ? " THROUGH THE WALL" : ""));
                        }
                    }
                    log("RESULT " + tiles + " door tiles with walls on 2+ sides; " + walls + " walls beside a door; " + open + " of them passable on the grid; " + routed + " routes go through the wall");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
