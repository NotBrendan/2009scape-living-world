package content.custom;

import core.game.world.map.Location;

import java.util.*;

/**
 * Two-level search over the NavGrid (the design notes §3 steps 2-3, review note B).
 *
 * Coarse level: every 8x8 cell is split into its connected components (a cell cut by a wall is two nodes); components of
 * neighbouring cells that share a passable edge are linked. A* over those (~50-60 k nodes for the surface) finds the
 * corridor for a whole trip in a few milliseconds. Fine level: tile A* restricted to the first few components of the
 * corridor gives the exact steps for the next hop. Nothing here touches the game thread's data except NavGrid.grid (read-only).
 */
public final class NavPlanner {
    static final int CELL = 8, CW = NavGrid.W / CELL, CH = NavGrid.H / CELL;
    /** component id of every tile (0 = none/blocked), ids are 1-based and global. */
    static int[] comp;
    static int comps;
    /** adjacency: component -> neighbouring components (deduplicated). */
    static int[][] adj;
    /** a representative tile per component (for the heuristic): x, y. */
    static int[] cx, cy;
    /** batch 20 B2: the connected region of each component (graph-connected set) and each region's tile count. */
    static int[] region, regionTiles;
    /** batch 20 B2: a region under this many tiles is a pocket (the grid closes it off: 2-tile pockets at the Exchange's centre); a plan's end in one is moved to a big region nearby. */
    static final int SMALL = Integer.getInteger("nav.small", 64), SNAP_R = 12;
    /**
     * batch 21 A: the Al Kharid toll gate (3268,3227-3228; the step between x 3267 and 3268) is a paid link, not a door. It
     * is cut out of the components and kept as flagged links (tollA[i] - tollB[i]); a corridor may use one only for a walker
     * that pays at the gate (tollOk), at TOLL_COST extra (so a same-side trip never goes through it and back round). regionFree (the
     * regions without the toll links) only tells needsToll() when the gate is the sole way. Walkers that never pay used to be
     * routed through it and looped along the fence.
     * Kill switch -Dnav.toll=false (the gate is an ordinary door again).
     */
    static final boolean TOLL_ON = !"false".equals(System.getProperty("nav.toll"));
    static final int TOLL_COST = Integer.getInteger("nav.tollCost", 30); // tiles: a payer takes the gate when it saves more than this (10 gp, as a player would)
    static int[] tollA = new int[0], tollB = new int[0], regionFree;

    /** batch 21 A: does the step from (x,y) in direction d cross the toll gate? */
    static boolean tollStep(int x, int y, int d) {
        if (!TOLL_ON || (y != 3227 && y != 3228)) return false;
        return d == 1 && x == 3267 || d == 3 && x == 3268;
    }

    static boolean isToll(int u, int v) {
        int[] a = tollA, b = tollB;
        for (int i = 0; i < a.length; i++) if (a[i] == u && b[i] == v || a[i] == v && b[i] == u) return true;
        return false;
    }

    static int regionFreeAt(Location l) {
        if (l == null) return 0;
        int c = compAt(l.getX(), l.getY()); int[] r = regionFree;
        return c == 0 || r == null || c >= r.length ? 0 : r[c];
    }

    /** batch 21 A: are from and to connected only through the toll gate? */
    static boolean needsToll(Location from, Location to) {
        if (!built || !TOLL_ON || from == null || to == null || from.getZ() != 0 || to.getZ() != 0) return false;
        Location f = standNear(from), t = standNear(to);
        if (f == null || t == null) return false;
        int r1 = regionAt(f), r2 = regionAt(t);
        return r1 != 0 && r1 == r2 && regionFreeAt(f) != regionFreeAt(t);
    }
    static volatile boolean built;
    /** batch 17 item1 (audit N-14): bumped every time a graph is published; a corridor searched over an older graph is not cached. */
    static volatile int gen;

    /**
     * batch 17 item1 (audit N-14, review P-10): planner off before the grid or the components are replaced (::navbuild, a graph
     * rebuild): every caller treats !built as "use the old walker"; corridor caches and walker routes of the old graph are dropped.
     * built comes back only in buildGraph once the new components are complete.
     */
    static void invalidate(String why) {
        boolean was = built;
        built = false; gen++;
        clearCaches();
        NavWalker.STATES.clear();
        if (was) System.out.println("[Nav] planner off during the " + why + ": the old walker walks until the new graph is up");
    }
    static void clearCaches() { synchronized (CACHE) { CACHE.clear(); } FAILED.clear(); } // batch 17 item1
    static final Map<Long, int[]> CACHE = new LinkedHashMap<Long, int[]>(512, 0.75f, true) { protected boolean removeEldestEntry(Map.Entry<Long, int[]> e) { return size() > 400; } };

    static final class Route {
        final List<Location> hops = new ArrayList<>(); int tiles, doors; int[] corridor; Location to;
        final List<Location> doorTiles = new ArrayList<>(); // the tile just before each door on the first stretch: the walker stops there and clicks Open
        final List<Location> doorAcross = new ArrayList<>(); // batch 21 item 4 (B9-8): for each door tile, the tile on the far side of the door (the step the path takes through it)
        public String toString() { return tiles + " tiles / " + hops.size() + " hops"; }
    }

    // ------------------------------------------------------------------ building the coarse graph

    /** Flood-fills every cell into components and links them across cell borders. ~1-2 s for the surface; call off the game thread. */
    static synchronized void buildGraph() {
        if (NavGrid.grid == null) return;
        invalidate("graph rebuild"); // batch 17 item1 (audit N-14): the old graph is off (callers use the old walker) while the new one is built into locals and published whole
        long t0 = System.currentTimeMillis();
        int W = NavGrid.W, H = NavGrid.H;
        int[] comp = new int[W * H]; int comps = 0; // batch 17 item1: locals shadow the fields until the graph is complete
        List<Integer> rx = new ArrayList<>(), ry = new ArrayList<>(); rx.add(0); ry.add(0);
        List<Integer> tc = new ArrayList<>(); tc.add(0); // batch 20 B2: tiles per component
        int[] stack = new int[CELL * CELL];
        for (int cyy = 0; cyy < CH; cyy++) for (int cxx = 0; cxx < CW; cxx++) {
            int bx = cxx * CELL, by = cyy * CELL;
            for (int ly = 0; ly < CELL; ly++) for (int lx = 0; lx < CELL; lx++) {
                int x = bx + lx, y = by + ly, i = y * W + x;
                if (comp[i] != 0 || (NavGrid.grid[i] & NavGrid.BLOCK) != 0) continue; // batch 21 B2/B6: void tiles are not a component
                int id = ++comps; comp[i] = id; rx.add(x + NavGrid.X0); ry.add(y + NavGrid.Y0);
                int sp = 0; stack[sp++] = i; int nt = 0;
                while (sp > 0) {
                    int j = stack[--sp]; int jx = j % W, jy = j / W; nt++;
                    for (int d = 0; d < 4; d++) {
                        int nx = jx + NavGrid.DX[d], ny = jy + NavGrid.DY[d];
                        if (nx < bx || nx >= bx + CELL || ny < by || ny >= by + CELL) continue; // stay inside the cell
                        int k = ny * W + nx;
                        if (comp[k] != 0) continue;
                        if (!NavGrid.canStep(jx + NavGrid.X0, jy + NavGrid.Y0, d)) continue;
                        if (tollStep(jx + NavGrid.X0, jy + NavGrid.Y0, d)) continue; // batch 21 A: the toll crossing joins components only as a flagged link
                        comp[k] = id; stack[sp++] = k;
                    }
                }
                tc.add(nt);
            }
        }
        int[] cx = new int[comps + 1], cy = new int[comps + 1]; // batch 17 item1: locals
        for (int i = 1; i <= comps; i++) { cx[i] = rx.get(i); cy[i] = ry.get(i); }
        // links across cell borders (north and east edges of every tile suffice)
        List<Integer> ta = new ArrayList<>(), tb = new ArrayList<>(); // batch 21 A: the toll links
        List<Set<Integer>> links = new ArrayList<>(comps + 1); for (int i = 0; i <= comps; i++) links.add(null);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int a = comp[y * W + x]; if (a == 0) continue;
            for (int d = 0; d < 2; d++) { // 0 N, 1 E
                int nx = x + NavGrid.DX[d], ny = y + NavGrid.DY[d];
                if (nx >= W || ny >= H) continue;
                int b = comp[ny * W + nx]; if (b == 0 || b == a) continue;
                if (!NavGrid.canStep(x + NavGrid.X0, y + NavGrid.Y0, d)) continue;
                if (tollStep(x + NavGrid.X0, y + NavGrid.Y0, d)) { boolean seen = false; for (int i = 0; i < ta.size(); i++) if (ta.get(i) == a && tb.get(i) == b) seen = true; if (!seen) { ta.add(a); tb.add(b); } } // batch 21 A
                if (links.get(a) == null) links.set(a, new HashSet<>()); links.get(a).add(b);
                if (links.get(b) == null) links.set(b, new HashSet<>()); links.get(b).add(a);
            }
        }
        int[][] adj = new int[comps + 1][]; // batch 17 item1: local
        for (int i = 1; i <= comps; i++) { Set<Integer> s = links.get(i); adj[i] = s == null ? new int[0] : s.stream().mapToInt(Integer::intValue).toArray(); }
        int[] reg = new int[comps + 1]; List<Integer> rt = new ArrayList<>(); rt.add(0); // batch 20 B2: regions over the graph
        int[] q = new int[comps + 1];
        for (int i = 1; i <= comps; i++) {
            if (reg[i] != 0) continue;
            int id = rt.size(); long tiles = 0; int qh = 0, qt = 0; q[qt++] = i; reg[i] = id;
            while (qh < qt) { int u = q[qh++]; tiles += tc.get(u); for (int v : adj[u]) if (reg[v] == 0) { reg[v] = id; q[qt++] = v; } }
            rt.add((int) Math.min(tiles, Integer.MAX_VALUE));
        }
        int[] rtiles = rt.stream().mapToInt(Integer::intValue).toArray();
        int[] tA = ta.stream().mapToInt(Integer::intValue).toArray(), tB = tb.stream().mapToInt(Integer::intValue).toArray();
        int[] regF = new int[comps + 1]; int nf = 0; // batch 21 A: the same regions with the toll links cut
        for (int i = 1; i <= comps; i++) {
            if (regF[i] != 0) continue;
            int id = ++nf, qh = 0, qt = 0; q[qt++] = i; regF[i] = id;
            while (qh < qt) { int u = q[qh++]; for (int v : adj[u]) { if (regF[v] != 0) continue; boolean toll = false; for (int k = 0; k < tA.length; k++) if (tA[k] == u && tB[k] == v || tA[k] == v && tB[k] == u) toll = true; if (toll) continue; regF[v] = id; q[qt++] = v; } }
        }
        if (NavGrid.building) { System.out.println("[Nav] coarse graph dropped: a ::navbuild started while it was built; the planner stays off until that build's graph"); return; } // batch 17 item1: a half-read grid is never published
        NavPlanner.region = reg; NavPlanner.regionTiles = rtiles; NavPlanner.regionFree = regF; NavPlanner.tollA = tA; NavPlanner.tollB = tB; // batch 21 A
        System.out.println("[Nav] toll links: " + tA.length); // batch 20 B2 (before comp: a reader that sees the new comp sees these)
        NavPlanner.comp = comp; NavPlanner.comps = comps; NavPlanner.cx = cx; NavPlanner.cy = cy; NavPlanner.adj = adj; // batch 17 item1: published whole, then on
        clearCaches(); gen++;
        built = true;
        System.out.println("[Nav] coarse graph: " + comps + " components over " + (CW * CH) + " cells in " + (System.currentTimeMillis() - t0) + " ms");
    }

    static int compAt(int x, int y) { if (!built || !NavGrid.inBounds(x, y)) return 0; return comp[(y - NavGrid.Y0) * NavGrid.W + (x - NavGrid.X0)]; }

    // ------------------------------------------------------------------ coarse A*

    /** The chain of components from a to b (inclusive), or null. Cached by (a,b). */
    static int[] corridor(int a, int b) { return corridor(a, b, 0); }

    /** batch 20 B1: the corridor for a combat band (0 = any walker; see {@link Heat#band}): low bands pay for danger cells. Cached by (a,b,band). */
    static int[] corridor(int a, int b, int band) { return corridor(a, b, band, true); }

    /** batch 21 A: tollOk = the corridor may use the toll links (at TOLL_COST). Cached by (a, b, band, tollOk). */
    static int[] corridor(int a, int b, int band, boolean tollOk) {
        if (a == 0 || b == 0) return null;
        if (b >= (1 << 29)) { band = 0; tollOk = true; } // the band lives in bits 30-31 of the key, tollOk in bit 29
        if (a == b) return new int[]{a};
        final int g0 = gen; final int[][] adj = NavPlanner.adj; final int[] cx = NavPlanner.cx, cy = NavPlanner.cy; // batch 17 item1: one graph for the whole search
        if (!built || adj == null || a >= adj.length || b >= adj.length) return null; // batch 17 item1: ids of another graph
        long key = ((long) a << 32) | ((long) band << 30) | ((long) (tollOk ? 0 : 1) << 29) | b; // batch 20 B1: and the band; batch 21 A: and the toll
        synchronized (CACHE) { int[] c = CACHE.get(key); if (c != null) return c; }
        Long failedAt = FAILED.get(key); // audit B-7: the components don't change, so a failed search stays failed for a minute
        if (failedAt != null && System.currentTimeMillis() - failedAt < 60_000) return null;
        int n = adj.length; // batch 17 item1: the snapshot's size (was comps + 1, read while a rebuild could change it)
        int[] g = new int[n]; Arrays.fill(g, Integer.MAX_VALUE); g[a] = 0;
        int[] prev = new int[n];
        boolean[] closed = new boolean[n];
        PriorityQueue<long[]> open = new PriorityQueue<>((p, q) -> Long.compare(p[0], q[0]));
        open.add(new long[]{Math.abs(cx[a] - cx[b]) + Math.abs(cy[a] - cy[b]), a}); // batch 17 item1: the snapshot's tiles (h() reads the fields)
        int expanded = 0;
        while (!open.isEmpty()) {
            long[] cur = open.poll(); int u = (int) cur[1];
            if (closed[u]) continue; closed[u] = true;
            if (u == b) break;
            if (++expanded > 200_000) { if (g0 == gen) failed(key); return null; } // batch 17 item1: only for the current graph
            for (int v : adj[u]) {
                if (closed[v]) continue;
                int w = Math.max(1, Math.abs(cx[u] - cx[v]) + Math.abs(cy[u] - cy[v])) + Heat.navCost(cx[v], cy[v], band); // batch 20 B1: the band's danger cost
                if (tollA.length > 0 && isToll(u, v)) { if (!tollOk) continue; w += TOLL_COST; } // batch 21 A
                int ng = g[u] + w;
                if (ng < g[v]) { g[v] = ng; prev[v] = u; open.add(new long[]{ng + Math.abs(cx[v] - cx[b]) + Math.abs(cy[v] - cy[b]), v}); }
            }
        }
        if (g[b] == Integer.MAX_VALUE) { if (g0 == gen) failed(key); return null; } // batch 17 item1
        List<Integer> path = new ArrayList<>(); for (int v = b; v != a; v = prev[v]) path.add(v); path.add(a);
        Collections.reverse(path);
        int[] c = path.stream().mapToInt(Integer::intValue).toArray();
        synchronized (CACHE) { if (g0 == gen) CACHE.put(key, c); } // batch 17 item1: a corridor of a replaced graph is never cached
        return c;
    }

    static int h(int a, int b) { return Math.abs(cx[a] - cx[b]) + Math.abs(cy[a] - cy[b]); }

    static final java.util.Map<Long, Long> FAILED = new java.util.concurrent.ConcurrentHashMap<>(); // audit B-7: (a,b) -> when the search failed
    static void failed(long key) {
        long now = System.currentTimeMillis();
        if (FAILED.size() > 20_000) FAILED.values().removeIf(t -> now - t > 60_000);
        FAILED.put(key, now);
    }

    // ------------------------------------------------------------------ fine level: the next hop

    /** Tile A* from (x,y) to (tx,ty) restricted to the given components (a set), or null. Bounded to a few thousand expansions. */
    static List<int[]> tilePath(int x, int y, int tx, int ty, Set<Integer> allowed) { return tilePath(x, y, tx, ty, allowed, true); }

    static List<int[]> tilePath(int x, int y, int tx, int ty, Set<Integer> allowed, boolean tollOk) {
        int W = NavGrid.W;
        int s = (y - NavGrid.Y0) * W + (x - NavGrid.X0), t = (ty - NavGrid.Y0) * W + (tx - NavGrid.X0);
        Map<Integer, Integer> g = new HashMap<>(), prev = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((p, q) -> Long.compare(p[0], q[0]));
        g.put(s, 0); open.add(new long[]{Math.abs(x - tx) + Math.abs(y - ty), s});
        Set<Integer> closed = new HashSet<>();
        int expanded = 0;
        while (!open.isEmpty()) {
            long[] cur = open.poll(); int u = (int) cur[1];
            if (!closed.add(u)) continue;
            if (u == t) break;
            if (++expanded > 20_000) return null;
            int ux = u % W + NavGrid.X0, uy = u / W + NavGrid.Y0;
            for (int d = 0; d < 4; d++) {
                if (!NavGrid.canStep(ux, uy, d)) continue;
                if (!tollOk && tollStep(ux, uy, d)) continue; // batch 21 A
                int nx = ux + NavGrid.DX[d], ny = uy + NavGrid.DY[d];
                int v = (ny - NavGrid.Y0) * W + (nx - NavGrid.X0);
                if (closed.contains(v) || !allowed.contains(comp[v])) continue;
                int ng = g.get(u) + 1;
                if (ng < g.getOrDefault(v, Integer.MAX_VALUE)) { g.put(v, ng); prev.put(v, u); open.add(new long[]{ng + Math.abs(nx - tx) + Math.abs(ny - ty), v}); }
            }
        }
        if (!g.containsKey(t)) return null;
        LinkedList<int[]> path = new LinkedList<>();
        for (int v = t; v != s; v = prev.get(v)) path.addFirst(new int[]{v % W + NavGrid.X0, v / W + NavGrid.Y0});
        return path;
    }

    /** A standable tile at or next to l (shops and spots are often the object's own tile; fishing spots sit in the water): the nearest within 6. */
    /** batch 20 B2: the region of the component at a tile (0 = none). */
    static int regionAt(Location l) {
        if (l == null) return 0;
        int c = compAt(l.getX(), l.getY()); int[] r = region;
        return c == 0 || r == null || c >= r.length ? 0 : r[c];
    }

    static int regionSize(int reg) { int[] t = regionTiles; return t == null || reg <= 0 || reg >= t.length ? 0 : t[reg]; }

    /** batch 20 B2: the nearest standable tile within SNAP_R whose region is not a pocket, or null. */
    static Location nearBig(Location l) {
        for (int r = 1; r <= SNAP_R; r++) for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
            Location c = Location.create(l.getX() + dx, l.getY() + dy, 0);
            if (NavGrid.standable(c.getX(), c.getY()) && regionSize(regionAt(c)) >= SMALL) return c;
        }
        return null;
    }

    /**
     * batch 20 B2: a plan's two ends. When they are in different regions, a start in a pocket (under SMALL tiles: the
     * grid closes it off though a player walks out) moves to the nearest tile of a big region within SNAP_R; the walker
     * reaches it by the engine's own steps. The destination is never moved (review: a real small destination region would
     * leave the walk stalled at the snapped tile, replanning until its deadline; it keeps the old walk instead). Bots at the Exchange's centre (3163-3166, 3488-3490: 2-tile pockets) had
     * "no route" to everywhere (36 records, among them the cells named in the plan: 3216,3360 / 3104,3424 / 3184,3424).
     */
    static Location[] ends(Location from, Location to) {
        Location f = standNear(from), t = standNear(to);
        if (f == null || t == null) return null;
        int rf = regionAt(f), rt = regionAt(t);
        if (rf != rt && regionTiles != null) {
            if (regionSize(rf) < SMALL) { Location f2 = nearBig(from); if (f2 != null) f = f2; }
        }
        return new Location[]{f, t};
    }

    static Location standNear(Location l) {
        if (NavGrid.standable(l.getX(), l.getY()) && compAt(l.getX(), l.getY()) != 0) return l;
        for (int r = 1; r <= 6; r++) {
            Location first = null, big = null; int bigSize = 0;
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                int x = l.getX() + dx, y = l.getY() + dy;
                if (!NavGrid.standable(x, y) || compAt(x, y) == 0) continue;
                if (first == null) first = Location.create(x, y, 0);
                int sz = regionSize(regionAt(Location.create(x, y, 0)));
                if (sz > bigSize) { bigSize = sz; big = Location.create(x, y, 0); }
            }
            // batch 21 B9: an object's tile (a staircase, a stall) touching two regions resolves to the bigger one: Falador
            // castle's staircase (2954,3338, Black Knights' Fortress) snapped to the 69-tile strip behind it, so every quest
            // walk up to Sir Amik had "no route" and Preflight called it an island
            if (first != null) return SNAP_BIG && big != null ? big : first;
        }
        return null;
    }
    static final boolean SNAP_BIG = !"false".equals(System.getProperty("nav.snapBig"));

    /**
     * A route from {@code from} to {@code to}: the component corridor plus the tile steps through its first stretch, with hops
     * every ~20 tiles. Null when there is no walkable connection (water, walls, an unbuilt area). Agility is for shortcuts (later).
     */
    public static Route plan(Location from, Location to, int agility) { return plan(from, to, agility, 0); }

    /** batch 20 B1: a plan for a combat band (Heat.band), so a low-level bot's corridor goes round known danger. */
    public static Route plan(Location from, Location to, int agility, int band) { return plan(from, to, agility, band, true); }

    /** batch 21 A: tollOk = this walker pays at the Al Kharid gate (and has the coins); otherwise the toll links are cut. */
    public static Route plan(Location from, Location to, int agility, int band, boolean tollOk) {
        final int g0 = gen; Route r; // batch 17 item1 (audit N-14): a plan that overlapped a graph swap is dropped (the caller walks the old way this step)
        try { r = plan0(from, to, agility, band, tollOk); } catch (RuntimeException e) { if (g0 != gen) return null; throw e; }
        return g0 == gen && built ? r : null;
    }

    static Route plan0(Location from, Location to, int agility, int band, boolean tollOk) { // batch 17 item1: the body of plan()
        if (!built || from == null || to == null || from.getZ() != 0 || to.getZ() != 0) return null;
        Location[] e = ends(from, to); // batch 20 B2: out of pockets
        if (e == null) return null;
        Location f = e[0], t = e[1];
        int a = compAt(f.getX(), f.getY()), b = compAt(t.getX(), t.getY());
        // batch 21 A fix (b21-gate-toll): no regionFree "same side" cut here. The fence can be walked round at its north end, so
        // every tile on both sides shares one regionFree and that cut refused the gate to every payer (TestB21A UNIT: east bank
        // -> Al Kharid store, payer toll false). The corridor search decides: the gate costs TOLL_COST extra, so a payer takes it
        // only when it saves more than that; a same-side trip never does (through the gate and back round the fence's north end
        // is never shorter than staying on its own side).
        final boolean useToll = tollOk;
        int[] cor = corridor(a, b, band, useToll);
        if (cor == null) return null;
        Route r = new Route(); r.corridor = cor; r.to = t;
        // the first stretch: up to 12 components of the corridor (~100 tiles) for the exact tiles
        int take = Math.min(cor.length, 12);
        Set<Integer> allowed = new HashSet<>(); for (int i = 0; i < take; i++) allowed.add(cor[i]);
        int gx, gy;
        if (take == cor.length) { gx = t.getX(); gy = t.getY(); }
        else { int last = cor[take - 1]; int[] g = anyTileOf(last, cor[take]); gx = g[0]; gy = g[1]; }
        List<int[]> steps = tilePath(f.getX(), f.getY(), gx, gy, allowed, useToll);
        if (steps == null) { // the fine search failed inside the corridor (rare: a component pair linked but the tile path bounded out): one straight hop toward the next component
            r.hops.add(Location.create(gx, gy, 0)); r.tiles = Math.abs(gx - f.getX()) + Math.abs(gy - f.getY()); return r;
        }
        r.tiles = steps.size() + (take == cor.length ? 0 : estimate(cor, take));
        int n = 0, px = f.getX(), py = f.getY();
        for (int[] s : steps) {
            n++;
            int d = s[1] > py ? 0 : s[0] > px ? 1 : s[1] < py ? 2 : 3;
            if (NavGrid.doorStep(px, py, d)) { r.doors++; r.doorTiles.add(Location.create(px, py, 0)); r.doorAcross.add(Location.create(s[0], s[1], 0)); r.hops.add(Location.create(px, py, 0)); } // stop before the door
            else if (n % 20 == 0 || n == steps.size()) r.hops.add(Location.create(s[0], s[1], 0));
            px = s[0]; py = s[1];
        }
        if (r.hops.isEmpty()) r.hops.add(t);
        return r;
    }

    /** A tile of component a that touches component b (the portal), else a's representative. */
    static int[] anyTileOf(int a, int b) {
        int W = NavGrid.W;
        int rxa = cx[a] - NavGrid.X0, rya = cy[a] - NavGrid.Y0;
        int bx = (rxa / CELL) * CELL, by = (rya / CELL) * CELL;
        for (int y = by; y < by + CELL; y++) for (int x = bx; x < bx + CELL; x++) {
            if (comp[y * W + x] != a) continue;
            for (int d = 0; d < 4; d++) {
                int nx = x + NavGrid.DX[d], ny = y + NavGrid.DY[d];
                if (nx < 0 || ny < 0 || nx >= W || ny >= NavGrid.H) continue;
                if (comp[ny * W + nx] == b && NavGrid.canStep(x + NavGrid.X0, y + NavGrid.Y0, d)) return new int[]{x + NavGrid.X0, y + NavGrid.Y0};
            }
        }
        return new int[]{cx[a], cy[a]};
    }

    static int estimate(int[] cor, int fromIdx) { int t = 0; for (int i = fromIdx; i < cor.length - 1; i++) t += Math.abs(cx[cor[i]] - cx[cor[i + 1]]) + Math.abs(cy[cor[i]] - cy[cor[i + 1]]); return t; }

    /** Is there any walkable connection at all (for pre-flight)? */
    public static boolean reachable(Location from, Location to) {
        if (!built || from == null || to == null || from.getZ() != 0 || to.getZ() != 0) return false;
        Location[] e = ends(from, to); // batch 20 B2
        if (e == null) return false;
        Location f = e[0], t = e[1];
        return corridor(compAt(f.getX(), f.getY()), compAt(t.getX(), t.getY())) != null;
    }
}
