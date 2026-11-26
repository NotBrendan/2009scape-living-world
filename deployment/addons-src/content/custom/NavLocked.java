package content.custom;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * w2 slayer bats (-Dbots.slayer.batsFix, default off = the grid exactly as loaded). Soaks s58/s58b/s59: every Bats task trip
 * between Varrock and the bats by Paterdomus (3350,3493) stood at 3295-3296,3427-3430 until the stall teleport, both ways.
 * The "Gate" there (24560/24561 at 3296,3428 and 3296,3429, on the tiles' west edge) is the Dig Site workmen's gate: the
 * engine's TheDigSiteListeners answers "open" with "it's for Dig Site workmen only" and never opens it, and it has no
 * door_configs entry. The surface grid marks any object with an Open action as a door, so the planner routed through it.
 * On: the two tiles lose DOOR in memory (their west wall stays), like NavGrid.closeOneWay; the file on disk keeps them
 * (restore, called from NavGrid.save). Pure: no engine classes (TestNavLocked, plain java).
 */
final class NavLocked {
    static final boolean ON = Boolean.getBoolean("bots.slayer.batsFix");
    static final int DOOR = 64; // NavGrid.DOOR
    /** Door tiles the engine never opens for anyone: {x, y}. */
    static final int[][] TILES = {{3296, 3428}, {3296, 3429}};
    /** Tile index -> its byte before close (for save). Empty with the flag off. */
    static final Map<Integer, Integer> RAW = new ConcurrentHashMap<>();

    private NavLocked() { }

    /** Clears DOOR on the locked tiles of a W*H grid with origin x0,y0; the old bytes go to raw. Returns the tiles changed (0 when off). */
    static int close(byte[] grid, int x0, int y0, int w, int h, boolean on, Map<Integer, Integer> raw) {
        if (!on || grid == null) return 0;
        int n = 0;
        for (int[] t : TILES) {
            int x = t[0], y = t[1];
            if (x < x0 || x >= x0 + w || y < y0 || y >= y0 + h) continue;
            int i = (y - y0) * w + (x - x0);
            if (i < 0 || i >= grid.length || (grid[i] & DOOR) == 0) continue;
            raw.put(i, grid[i] & 0xFF);
            grid[i] = (byte) (grid[i] & ~DOOR);
            n++;
        }
        return n;
    }

    /** Puts the recorded bytes back into g (a copy of the grid about to be written to disk). */
    static void restore(byte[] g, Map<Integer, Integer> raw) {
        if (g == null) return;
        for (Map.Entry<Integer, Integer> e : raw.entrySet()) if (e.getKey() >= 0 && e.getKey() < g.length) g[e.getKey()] = (byte) (int) e.getValue();
    }
}
