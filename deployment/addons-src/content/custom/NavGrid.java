package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.command.Command;
import core.game.system.command.CommandMapping;
import core.game.system.command.Privilege;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.Region;
import core.game.world.map.RegionManager;
import kotlin.Unit;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The walkability grid the real pathfinder runs on (the design notes §3 step 1).
 *
 * One byte per tile of the surface (plane 0, x 2048-4095, y 2496-4159): bit 0 the tile cannot be stood on (solid, object,
 * decoration); bits 1-4 a wall on the tile's north / east / south / west side (the engine's 0x2 / 0x8 / 0x20 / 0x80 edge flags,
 * which is how fences, shop walls and the Port Sarim shop partition are stored: both tiles walkable, the edge between them not);
 * bit 5 the tile was never read (region not built). 3.4 MB in memory, gzip on disk at data/survey/nav-p0.bin.
 *
 * Built by `::navbuild` one region a game tick: a region the server had not loaded is read and unloaded again at once, so
 * the heap never holds the whole map (review note A). `::navcheck x y` prints a tile and its edges; `::navroute x y x2 y2`
 * runs the tile search once (for trials; the real planner is NavPlanner).
 */
public final class NavGrid implements StartupListener {
    static final int X0 = 2048, Y0 = 2496, W = 2048, H = 1664;
    static final int SOLID = 1, WN = 2, WE = 4, WS = 8, WW = 16, UNREAD = 32, DOOR = 64; // DOOR: an openable door/gate object stands here; its walls can be passed with an "Open" (review note D)
    static final int[] DX = {0, 1, 0, -1}, DY = {1, 0, -1, 0};          // N, E, S, W
    static final int[] WALL_FROM = {WN, WE, WS, WW}, WALL_INTO = {WS, WW, WN, WE}; // leaving a tile northward needs no north wall here and no south wall there
    static byte[] grid;                 // null until built or loaded
    static volatile boolean building;
    static int builtRegions, standable, walls, doors;
    /**
     * batch 21 B2/B6: an UNREAD tile is a map square with no map data (getClippingFlag -1: sea and void past the map's edge;
     * the engine walks nobody onto it). It used to count as standable, and the planner joined the desert, the Wilderness,
     * Morytania and the islands to the mainland through those squares: bots bound for the Ugthanki (3268,3052) walked to
     * Yanille's void edge, and walks to the Wilderness shops went to the void west of the Gnome Stronghold. Now a wall;
     * -Dnav.voidOpen=true puts the old rule back.
     */
    static final boolean VOID_OPEN = Boolean.getBoolean("nav.voidOpen");
    static final int BLOCK = VOID_OPEN ? SOLID : SOLID | UNREAD;

    static int idx(int x, int y) { return (y - Y0) * W + (x - X0); }
    static boolean inBounds(int x, int y) { return x >= X0 && x < X0 + W && y >= Y0 && y < Y0 + H; }
    static boolean ready() { return grid != null; }

    /** Can something stand here (surface)? batch 21 B2/B6: unread (void) tiles no longer count, see {@link #BLOCK}. */
    static boolean standable(int x, int y) {
        if (grid == null || !inBounds(x, y)) return false;
        int b = grid[idx(x, y)];
        return (b & BLOCK) == 0;
    }

    /** One cardinal step from (x,y) in direction d (0 N, 1 E, 2 S, 3 W): no wall on either side of the edge, destination standable. */
    static boolean canStep(int x, int y, int d) {
        if (grid == null || !inBounds(x, y)) return false;
        int nx = x + DX[d], ny = y + DY[d];
        if (!inBounds(nx, ny)) return false;
        int a = grid[idx(x, y)], b = grid[idx(nx, ny)];
        if ((b & BLOCK) != 0) return false; // batch 21 B2/B6: and void
        if ((a & WALL_FROM[d]) == 0 && (b & WALL_INTO[d]) == 0) return true;
        // Pathing review 10-23 N-1: a door tile used to make every one of its edges passable, so a door in a corner, or a gate
        // beside a fence, let routes through the walls next to it. Only the edge the door itself stands on opens.
        return doorEdge(x, y, a, WALL_FROM[d]) || doorEdge(nx, ny, b, WALL_INTO[d]);
    }

    /**
     * Pathing review 10-23 N-1: the edges a door object occupies (WN/WE/WS/WW bits), by tile index, for door tiles whose own
     * wall bits don't say it (walls on two or more sides): read from the object at build time, and at load for those few.
     */
    static final java.util.Map<Integer, Integer> DOOR_EDGES = new java.util.concurrent.ConcurrentHashMap<>();
    static final int ALL_EDGES = WN | WE | WS | WW;

    /** The edges of a door tile the door opens: from its object when known; else the tile's one wall (a shut door flags its own edge); else (a big gate on a solid tile, no walls) all of them. */
    static int doorMask(int x, int y, int t) {
        Integer m = DOOR_EDGES.get(idx(x, y));
        if (m != null) return m;
        int w = t & ALL_EDGES;
        return w != 0 ? w : ALL_EDGES;
    }

    static boolean doorEdge(int x, int y, int t, int edgeBit) { return (t & DOOR) != 0 && (doorMask(x, y, t) & edgeBit) != 0; }

    /** The edges a door object stands on, from its type and rotation (ChunkFlags.flagDoorObject), or -1 when the shape doesn't say. */
    static int edgesOf(core.game.node.scenery.Scenery o) {
        if (o == null) return -1;
        int r = o.getRotation() & 3;
        if (o.getType() == 0) return new int[]{WW, WN, WE, WS}[r];
        if (o.getType() == 2) return new int[]{WW | WN, WN | WE, WE | WS, WS | WW}[r];
        return -1;
    }

    /** Door tiles with walls on two or more sides: which edge is the door is read from the object (a loaded grid has no record of it). */
    static void readDoorEdges() {
        if (grid == null) return;
        int n = 0, read = 0;
        java.util.Map<Integer, java.util.List<int[]>> byRegion = new java.util.HashMap<>();
        for (int i = 0; i < grid.length; i++) {
            int t = grid[i];
            if ((t & DOOR) == 0 || Integer.bitCount(t & ALL_EDGES) < 2) continue;
            int x = X0 + i % W, y = Y0 + i / W; n++;
            byRegion.computeIfAbsent(((x >> 6) << 8) | (y >> 6), k -> new java.util.ArrayList<>()).add(new int[]{x, y});
        }
        for (java.util.Map.Entry<Integer, java.util.List<int[]>> e : byRegion.entrySet()) {
            Region r = null; boolean wasLoaded = true;
            try { r = RegionManager.forId(e.getKey()); wasLoaded = r != null && r.isLoaded(); if (r != null && !wasLoaded) Region.load(r); } catch (Throwable ignored) { }
            for (int[] xy : e.getValue()) {
                try { int m = edgesOf(RegionManager.getObject(0, xy[0], xy[1])); if (m > 0) { DOOR_EDGES.put(idx(xy[0], xy[1]), m); read++; } } catch (Throwable ignored) { }
            }
            if (!wasLoaded && r != null) { try { Region.unload(r); } catch (Throwable ignored) { } }
        }
        System.out.println("[Nav] " + n + " door tiles with walls on two or more sides; the door's own edge read for " + read);
    }

    /**
     * batch 21 C (map verification): a gate wider than one tile (the Tree Gnome Stronghold's "Gate" 190 at 2459,3383 is 5x2)
     * only had DOOR on its origin tile; the other covered tiles stayed SOLID, so the stronghold (11,083 tiles) had no link to the
     * mainland. Every tile an openable multi-tile gate covers is a door tile: tile index -> origin tile index (for the click).
     */
    static final java.util.Map<Integer, Integer> BIG_GATE = new java.util.concurrent.ConcurrentHashMap<>();
    static final boolean BIG_GATES_ON = !"false".equals(System.getProperty("nav.biggates"));
    /**
     * review fix 4: every multi-tile gate tile ::navbuild found, whatever -Dnav.biggates says: tile index -> {origin tile
     * index, the tile's own byte without the gate (-1 until read)}. Saved after the grid (the trailer), so a load needs no
     * region reads and the switch works at runtime: on, the tiles become door tiles; off, they keep their own byte.
     */
    static final java.util.Map<Integer, int[]> BIG_FOUND = new java.util.concurrent.ConcurrentHashMap<>();

    static void markFootprint(core.game.node.scenery.Scenery o) {
        if (o == null || o.getDefinition() == null || o.getLocation() == null) return;
        int sx = o.getDefinition().getSizeX(), sy = o.getDefinition().getSizeY();
        if ((o.getRotation() & 1) == 1) { int t = sx; sx = sy; sy = t; }
        if (sx * sy <= 1 || sx > 6 || sy > 6) return;
        int ox = o.getLocation().getX(), oy = o.getLocation().getY(), oi = idx(ox, oy);
        for (int i = 0; i < sx; i++) for (int j = 0; j < sy; j++) {
            int x = ox + i, y = oy + j;
            if (!inBounds(x, y)) continue;
            int k = idx(x, y);
            BIG_FOUND.put(k, new int[]{oi, -1}); // its own byte is read when buildRegion reaches it (the origin is the footprint's lowest corner, so never before)
            if (BIG_GATES_ON) BIG_GATE.put(k, oi);
        }
    }

    /** review fix 4: a multi-tile gate tile with -Dnav.biggates on: a door tile, passable once the gate opens. */
    static byte bigGateByte(int raw) { return (byte) ((raw | DOOR) & ~SOLID & ~UNREAD); }

    /** batch 21 C: the origin of the multi-tile gate covering (x,y), or null. */
    static Location bigGateOrigin(int x, int y) {
        if (!inBounds(x, y)) return null;
        Integer o = BIG_GATE.get(idx(x, y));
        return o == null ? null : Location.create(X0 + o % W, Y0 + o / W, 0);
    }

    static boolean isGateName(String n) { if (n == null) return false; String l = n.toLowerCase(java.util.Locale.ROOT); return !l.contains("trapdoor") && (l.contains("gate") || l.contains("door") || l.contains("portcullis")); }

    /** Does the step from (x,y) in direction d go through a door (either tile carries one)? */
    static boolean doorStep(int x, int y, int d) {
        if (grid == null || !inBounds(x, y)) return false;
        int nx = x + DX[d], ny = y + DY[d];
        if (!inBounds(nx, ny)) return false;
        int a = grid[idx(x, y)], b = grid[idx(nx, ny)];
        if (((a | b) & DOOR) == 0) return false;
        return doorEdge(x, y, a, WALL_FROM[d]) || doorEdge(nx, ny, b, WALL_INTO[d]); // pathing review 10-23 N-1: only a step through the door's own edge (any step touching a door tile used to count, and got a click where no door was crossed)
    }

    /** The engine's flag word for one tile turned into our byte. */
    static byte pack(int f) {
        if (f == -1) return (byte) UNREAD;
        int b = 0;
        if ((f & (0x1000000 | 0x200000 | 0x80000 | 0x40000 | 0x100)) != 0) b |= SOLID; // exactly the engine's PREVENT_* blocking bits (0x12C0100): solid, object, decoration, floor; 0x20000 is projectile-only and walkable
        if ((f & 0x2) != 0) b |= WN;
        if ((f & 0x8) != 0) b |= WE;
        if ((f & 0x20) != 0) b |= WS;
        if ((f & 0x80) != 0) b |= WW;
        return (byte) b;
    }

    /** Reads one 64x64 region into the grid; a region the server had not loaded is unloaded again afterwards. */
    static void buildRegion(int rx, int ry) {
        int regionId = (rx << 8) | ry;
        Region r = null; boolean wasLoaded = true;
        try { r = RegionManager.forId(regionId); wasLoaded = r != null && r.isLoaded(); } catch (Throwable ignored) { }
        int bx = rx << 6, by = ry << 6;
        try { if (r != null && !r.isLoaded()) Region.load(r); } catch (Throwable ignored) { } // read after an explicit load: on the live server a lazy load inside getClippingFlag left whole regions unread
        for (int x = bx; x < bx + 64; x++) for (int y = by; y < by + 64; y++) {
            if (!inBounds(x, y)) continue;
            int f;
            try { f = RegionManager.getClippingFlag(0, x, y); } catch (Throwable t) { f = -1; }
            byte b = pack(f);
            if ((b & UNREAD) == 0) { try { // an openable door or gate on this tile: a link, not a wall (shut/locked ones recorded by the surveyors stay walls)
                core.game.node.scenery.Scenery o = RegionManager.getObject(0, x, y);
                if (o != null && o.getDefinition() != null && o.getDefinition().hasAction("Open") && (o.getType() < 4 || isGateName(o.getName())) && !SHUT_DOORS.contains(x + "," + y + ",0")) { b |= DOOR; b &= ~SOLID; doors++; if (o.getType() >= 10) markFootprint(o); /* batch 21 C */ int m = edgesOf(o); if (m > 0) DOOR_EDGES.put(idx(x, y), m); } // a big gate (type 10) sits on a solid tile: passable once open; pathing review 10-23 N-1: which edge is the door
            } catch (Throwable ignored) { } }
            int[] bf = BIG_FOUND.get(idx(x, y)); // batch 21 C: covered by a multi-tile gate read earlier
            if (bf != null) { bf[1] = b & 0xFF; if (BIG_GATES_ON) b = bigGateByte(b); } // review fix 4: its own byte kept for the trailer
            grid[idx(x, y)] = b;
            if ((b & UNREAD) == 0 && (b & SOLID) == 0) standable++;
            if ((b & (WN | WE | WS | WW)) != 0) walls++;
        }
        builtRegions++;
        if (!wasLoaded && r != null) { try { Region.unload(r); } catch (Throwable ignored) { } } // review note A: the map never sits in the heap
    }

    /**
     * Doors that stay walls: the gate rules' confirmed shut doors (quest, key, toll). The surveyors' "stayed shut" records are
     * not used: Gerrant's shop door in Port Sarim has eight of them and opens for anyone (the door test misreads double doors),
     * and the walled shop was exactly the "NO ROUTE" the first shadow run showed. A door that really won't open costs the walker
     * one failed click and a replan.
     */
    static final java.util.Set<String> SHUT_DOORS = new java.util.HashSet<>();
    /**
     * batch 21 B9: doors the engine opens from one side only, kept as walls (both ways: the grid has no one-way edge).
     * Draynor Manor's front doors (134/135): DraynorManorPlugin answers "The doors won't open." to anyone at y >= 3354, so a
     * bot inside (live failed_trip from 3108,3354) planned out through them for ever; the east door (35146, 3123,3361) is
     * the way out. -Dnav.oneway=false keeps them doors.
     */
    static final String[] ONE_WAY = {"3108,3353", "3109,3353"};
    static final boolean ONE_WAY_ON = !"false".equals(System.getProperty("nav.oneway"));
    /**
     * review fix 4: the one-way door tiles as ::navbuild read them (door tiles: the build no longer treats them as shut):
     * tile index -> {byte, door edge mask or -1}. Saved in the trailer; the grid on disk keeps them doors, and closeOneWay
     * turns them into walls in memory only when -Dnav.oneway is on, so the switch works without a rebuild.
     */
    static final java.util.Map<Integer, int[]> ONE_RAW = new java.util.concurrent.ConcurrentHashMap<>();

    /** review fix 4: after a build, before closeOneWay: what the one-way tiles look like as plain doors. */
    static void recordOneWay() {
        ONE_RAW.clear();
        if (grid == null) return;
        for (String k : ONE_WAY) {
            String[] p = k.split(","); int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
            if (!inBounds(x, y)) continue;
            int i = idx(x, y); Integer m = DOOR_EDGES.get(i);
            ONE_RAW.put(i, new int[]{grid[i] & 0xFF, m == null ? -1 : m});
        }
    }

    static void closeOneWay() {
        if (grid == null || !ONE_WAY_ON) return;
        int n = 0;
        for (String k : ONE_WAY) {
            String[] p = k.split(","); int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
            if (!inBounds(x, y)) continue;
            int i = idx(x, y);
            if ((grid[i] & DOOR) != 0) { grid[i] = (byte) (grid[i] & ~DOOR); DOOR_EDGES.remove(i); n++; }
        }
        if (n > 0) System.out.println("[Nav] " + n + " one-way door tiles kept as walls (Draynor Manor front doors)");
    }
    static void loadShutDoors() {
        SHUT_DOORS.clear(); // review fix 4: the one-way doors are built as doors (recordOneWay), then closed in memory (closeOneWay)
        // Audit B-10: the comment above was the intent, but every surveyor "stayed shut" record was copied. Keep only toll,
        // key and quest doors ("special") and doors inside a gate rule's box (within 6 tiles, as Gates.shutDoor confirms).
        for (java.util.Map.Entry<String, org.json.simple.JSONObject> e : Gates.SHUT.entrySet()) {
            Object res = e.getValue() == null ? null : e.getValue().get("result");
            boolean keep = res != null && String.valueOf(res).startsWith("special");
            if (!keep) try {
                String[] p = e.getKey().split(","); int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
                for (Gates.Gate gt : Gates.GATES) if (x >= gt.x0 - 6 && x <= gt.x1 + 6 && y >= gt.y0 - 6 && y <= gt.y1 + 6) { keep = true; break; }
            } catch (Throwable ignored) { }
            if (keep) SHUT_DOORS.add(e.getKey());
        }
        System.out.println("[Nav] " + SHUT_DOORS.size() + " of " + Gates.SHUT.size() + " shut doors (toll, key, quest or gate rule) stay walls");
    }

    /** `::navbuild`: every region in the surface box, two a tick (~7 min), then save. */
    static void build(Player who) {
        synchronized (NavGrid.class) { // batch 17 item1: check-and-set (the command and the boot build could race)
            if (building) { if (who != null) who.sendMessage("Nav build already running."); return; }
            building = true;
        }
        // batch 17 item1: building is set first (above), so a graph build already running drops its result instead of publishing
        NavPlanner.invalidate("::navbuild"); // batch 17 item1 (audit N-14, review P-10): planner off before the grid goes all-UNREAD; buildGraph turns it back on with the new components
        try { // batch 17 review C3: a failure before the region pulse starts must not leave building set (the planner would stay off until a restart)
            loadShutDoors();
            byte[] g = new byte[W * H];
            java.util.Arrays.fill(g, (byte) UNREAD);
            builtRegions = 0; standable = 0; walls = 0; doors = 0; DOOR_EDGES.clear(); BIG_FOUND.clear(); BIG_GATE.clear(); ONE_RAW.clear(); // review fix 4
            grid = g;
        } catch (Throwable t) {
            System.out.println("[Nav] ::navbuild could not start (" + t + "): keeping the old grid, planner back on it");
            building = false; graphAsync();
            return;
        }
        final int rx0 = X0 >> 6, rx1 = (X0 + W - 1) >> 6, ry0 = Y0 >> 6, ry1 = (Y0 + H - 1) >> 6;
        final int total = (rx1 - rx0 + 1) * (ry1 - ry0 + 1);
        System.out.println("[Nav] building the surface grid: " + total + " regions, one a tick");
        GameWorld.getPulser().submit(new Pulse(1) {
            int rx = rx0, ry = ry0; long t0 = System.currentTimeMillis();
            @Override public boolean pulse() {
                for (int k = 0; k < 2 && rx <= rx1; k++) { // two regions a tick: ~7 minutes for the surface
                    try { buildRegion(rx, ry); } catch (Throwable t) { System.out.println("[Nav] region " + rx + "," + ry + ": " + t); }
                    if (builtRegions % 100 == 0) System.out.println("[Nav] " + builtRegions + "/" + total + " regions, " + standable + " standable tiles so far");
                    if (++ry > ry1) { ry = ry0; rx++; }
                }
                if (rx > rx1) {
                    building = false;
                    try { recordOneWay(); closeOneWay(); } catch (Throwable t) { System.out.println("[Nav] one-way doors: " + t); } // review fix 4: closed in memory only
                    try { save(); } catch (Throwable t) { System.out.println("[Nav] save failed: " + t); }
                    graphAsync();
                    String msg = "[Nav] surface grid built: " + builtRegions + " regions, " + standable + " standable tiles, " + walls + " tiles with a wall, " + doors + " doors, " + (System.currentTimeMillis() - t0) / 1000 + " s";
                    System.out.println(msg); if (who != null && who.isActive()) who.sendMessage(msg);
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * batch 22: a `::navbuild` without an admin client. Once a minute (NavWalker.watch, game thread) data/survey/navbuild.request
     * is looked for; when found and no build is running it is renamed to navbuild.done-<stamp> FIRST (deleted if the rename
     * fails, and no build if neither works), so one request runs one build even if the build throws. While a build is running
     * the request is left for a later minute (logged once). -Dnav.buildfile=false turns the check off.
     */
    static final boolean BUILD_FILE_ON = !"false".equalsIgnoreCase(System.getProperty("nav.buildfile", "true"));
    static boolean buildFileWaitLogged;
    static void buildFileCheck() {
        if (!BUILD_FILE_ON) return;
        try {
            File f = NavWalker.survey("navbuild.request");
            if (!f.exists()) { buildFileWaitLogged = false; return; }
            if (building) {
                if (!buildFileWaitLogged) { buildFileWaitLogged = true; System.out.println("[Nav] navbuild.request found while a build is running: left for a later minute"); }
                return;
            }
            buildFileWaitLogged = false;
            String to = "navbuild.done-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            if (!f.renameTo(NavWalker.survey(to)) && !f.delete()) { System.out.println("[Nav] navbuild.request could not be renamed or deleted: no build (it would run every minute)"); return; }
            System.out.println("[Nav] navbuild.request found: starting NavGrid.build(null)");
            build(null);
        } catch (Throwable t) { System.out.println("[Nav] navbuild.request check: " + t); }
    }

    static File file() { return new File(new File(ServerConstants.DATA_PATH, "survey"), "nav-p0.bin"); }

    /** The coarse graph is built off the game thread (a second or two); planning waits for it. */
    static void graphAsync() {
        Thread t = new Thread(() -> { try { NavPlanner.buildGraph(); } catch (Throwable e) { System.out.println("[Nav] graph build failed: " + e); } }, "NavGraph");
        t.setDaemon(true); t.start();
    }

    static final int TRAILER = 0x4E415632; // "NAV2": review fix 4's record after the grid bytes

    static synchronized void save() throws IOException {
        File f = file(); f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            DataOutputStream d = new DataOutputStream(o);
            d.writeInt(1); d.writeInt(X0); d.writeInt(Y0); d.writeInt(W); d.writeInt(H);
            byte[] g = grid.clone(); // review fix 4: the grid as read, without the multi-tile gates or the one-way walls (older code reads it as batch 20 did)
            for (java.util.Map.Entry<Integer, int[]> e : BIG_FOUND.entrySet()) if (e.getValue()[1] >= 0) g[e.getKey()] = (byte) e.getValue()[1];
            for (java.util.Map.Entry<Integer, int[]> e : ONE_RAW.entrySet()) g[e.getKey()] = (byte) e.getValue()[0];
            d.write(g);
            // review fix 4: the trailer (older code stops reading before it): multi-tile gate tiles, then the one-way door tiles
            d.writeInt(TRAILER); d.writeInt(1);
            d.writeInt(BIG_FOUND.size()); for (java.util.Map.Entry<Integer, int[]> e : BIG_FOUND.entrySet()) { d.writeInt(e.getKey()); d.writeInt(e.getValue()[0]); d.writeInt(e.getValue()[1]); }
            d.writeInt(ONE_RAW.size()); for (java.util.Map.Entry<Integer, int[]> e : ONE_RAW.entrySet()) { d.writeInt(e.getKey()); d.writeInt(e.getValue()[0]); d.writeInt(e.getValue()[1]); }
            d.flush();
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    static boolean load() {
        File f = file();
        if (!f.exists()) return false;
        try (InputStream i = new GZIPInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            DataInputStream d = new DataInputStream(i);
            int v = d.readInt(), x0 = d.readInt(), y0 = d.readInt(), w = d.readInt(), h = d.readInt();
            if (v != 1 || x0 != X0 || y0 != Y0 || w != W || h != H) { System.out.println("[Nav] nav-p0.bin has another layout; rebuild with ::navbuild"); return false; }
            byte[] g = new byte[W * H]; d.readFully(g); grid = g;
            int st = 0; for (byte b : g) if ((b & (SOLID | UNREAD)) == 0) st++;
            standable = st;
            System.out.println("[Nav] surface grid loaded: " + st + " standable tiles");
            boolean trailer = false;
            try { trailer = readTrailer(d); } catch (Throwable t) { System.out.println("[Nav] multi-tile gates and one-way doors: " + t); } // review fix 4 (batch 21 C, B9): from the file, no region reads
            try { readDoorEdges(); } catch (Throwable t) { System.out.println("[Nav] door edges: " + t); } // pathing review 10-23 N-1
            if (trailer) applyTrailer();
            else System.out.println("[Nav] nav-p0.bin has no multi-tile gate record (saved before review fix 4): those gates stay walls until ::navbuild");
            try { closeOneWay(); } catch (Throwable t) { System.out.println("[Nav] one-way doors: " + t); } // batch 21 B9 (the static list: needs no record)
            return true;
        } catch (Throwable t) { System.out.println("[Nav] load failed: " + t); return false; }
    }

    /** review fix 4: the record after the grid bytes, if the file has one (false: an older file, nothing read). */
    static boolean readTrailer(DataInputStream d) throws IOException {
        BIG_FOUND.clear(); BIG_GATE.clear(); ONE_RAW.clear();
        int magic;
        try { magic = d.readInt(); } catch (java.io.EOFException e) { return false; }
        if (magic != TRAILER) return false;
        int v = d.readInt(); if (v != 1) { System.out.println("[Nav] nav-p0.bin record version " + v + " unknown; rebuild with ::navbuild"); return false; }
        int n = d.readInt(); if (n < 0 || n > 100_000) return false;
        java.util.Map<Integer, int[]> big = new java.util.HashMap<>(), one = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) { int k = d.readInt(), o = d.readInt(), raw = d.readInt(); if (k >= 0 && k < W * H && o >= 0 && o < W * H) big.put(k, new int[]{o, raw}); }
        int m = d.readInt(); if (m < 0 || m > 1_000) return false;
        for (int i = 0; i < m; i++) { int k = d.readInt(), raw = d.readInt(), edges = d.readInt(); if (k >= 0 && k < W * H) one.put(k, new int[]{raw, edges}); }
        BIG_FOUND.putAll(big); ONE_RAW.putAll(one);
        return true;
    }

    /** review fix 4: the multi-tile gates onto the loaded grid (-Dnav.biggates on), and the one-way doors' own edges back (closeOneWay then shuts them when -Dnav.oneway is on). */
    static void applyTrailer() {
        int g = 0;
        if (BIG_GATES_ON) for (java.util.Map.Entry<Integer, int[]> e : BIG_FOUND.entrySet()) { int k = e.getKey(); BIG_GATE.put(k, e.getValue()[0]); grid[k] = bigGateByte(grid[k]); g++; }
        for (java.util.Map.Entry<Integer, int[]> e : ONE_RAW.entrySet()) if (e.getValue()[1] > 0) DOOR_EDGES.put(e.getKey(), e.getValue()[1]);
        System.out.println("[Nav] multi-tile gates: " + BIG_FOUND.size() + " covered tiles on record, " + g + " made door tiles" + (BIG_GATES_ON ? "" : " (-Dnav.biggates=false)") + "; one-way door tiles on record " + ONE_RAW.size() + (ONE_WAY_ON ? "" : " (-Dnav.oneway=false: left as doors)"));
    }

    static String describe(int x, int y) {
        if (grid == null) return "no grid (::navbuild)";
        if (!inBounds(x, y)) return x + "," + y + " is outside the surface box";
        int b = grid[idx(x, y)];
        return x + "," + y + ": " + ((b & UNREAD) != 0 ? "unread" : (b & SOLID) != 0 ? "blocked" : "standable") + ((b & DOOR) != 0 ? " door" : "") + " walls[" + ((b & WN) != 0 ? "N" : "") + ((b & WE) != 0 ? "E" : "") + ((b & WS) != 0 ? "S" : "") + ((b & WW) != 0 ? "W" : "") + "] steps[" + (canStep(x, y, 0) ? "N" : "") + (canStep(x, y, 1) ? "E" : "") + (canStep(x, y, 2) ? "S" : "") + (canStep(x, y, 3) ? "W" : "") + "]";
    }

    @Override
    public void startup() {
        if (load()) graphAsync();
        else { System.out.println("[Nav] no surface grid yet: building it two regions a tick from one minute after boot (unload-after-read keeps the heap flat)"); GameWorld.getPulser().submit(new Pulse(Boolean.getBoolean("nav.build") ? 100 : 100) { @Override public boolean pulse() { build(null); return true; } }); }
        NavWalker.watch(); // shadow outcomes, nav.on re-read, the auto-revert guard (morning review 10-23, C2)
        GameWorld.getPulser().submit(new Pulse(500) { @Override public boolean pulse() { if (NavPlanner.built && (NavWalker.ON || NavWalker.SHADOW)) System.out.println("[Nav] walker: " + NavWalker.stats() + (NavWalker.ON ? "" : " (shadow only)")); return false; } });
        String tests = System.getProperty("nav.test"); // trials: "x,y,x2,y2;x,y,x2,y2" routes run once the graph is up
        if (tests != null) GameWorld.getPulser().submit(new Pulse(50) { int waited; @Override public boolean pulse() {
            if (!NavPlanner.built) return ++waited > 60;
            for (String t : tests.split(";")) { try { String[] a = t.split(","); long t0 = System.nanoTime(); NavPlanner.Route r = NavPlanner.plan(Location.create(Integer.parseInt(a[0]), Integer.parseInt(a[1]), 0), Location.create(Integer.parseInt(a[2]), Integer.parseInt(a[3]), 0), 99); System.out.println("[NavTest] " + t + " -> " + (r == null ? "no route" : r + ", first hops " + r.hops.subList(0, Math.min(3, r.hops.size()))) + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms"); } catch (Throwable e) { System.out.println("[NavTest] " + t + ": " + e); } }
            return true; } });
        try {
            CommandMapping.INSTANCE.register(new Command("navbuild", Privilege.ADMIN, "", "", (p, a) -> { build(p); return Unit.INSTANCE; }));
            CommandMapping.INSTANCE.register(new Command("navcheck", Privilege.ADMIN, "", "", (p, a) -> {
                Location l = p.getLocation(); int x = l.getX(), y = l.getY();
                try { if (a.length >= 3) { x = Integer.parseInt(a[1]); y = Integer.parseInt(a[2]); } } catch (NumberFormatException ignored) { }
                p.sendMessage(describe(x, y)); return Unit.INSTANCE;
            }));
            CommandMapping.INSTANCE.register(new Command("navroute", Privilege.ADMIN, "", "", (p, a) -> {
                try {
                    Location from = p.getLocation(); int tx, ty;
                    if (a.length >= 5) { from = Location.create(Integer.parseInt(a[1]), Integer.parseInt(a[2]), 0); tx = Integer.parseInt(a[3]); ty = Integer.parseInt(a[4]); }
                    else { tx = Integer.parseInt(a[1]); ty = Integer.parseInt(a[2]); }
                    long t0 = System.nanoTime();
                    NavPlanner.Route r = NavPlanner.plan(from, Location.create(tx, ty, 0), 99);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    p.sendMessage(r == null ? "No route (" + ms + " ms)." : "Route: " + r.tiles + " tiles, " + r.hops.size() + " hops, " + r.doors + " doors, " + ms + " ms; first hop " + (r.hops.isEmpty() ? "-" : r.hops.get(0)));
                } catch (Throwable t) { p.sendMessage("navroute x y [x2 y2]: " + t); }
                return Unit.INSTANCE;
            }));
        } catch (Throwable t) { System.out.println("[Nav] commands: " + t); }
    }
}
