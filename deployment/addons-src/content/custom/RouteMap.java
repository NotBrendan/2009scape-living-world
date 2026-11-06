package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The learning route map (design: the design notes (not published)). Surface cells of 8x8 tiles; edges between neighbouring
 * cells learn from every crossing anyone makes (bots and players), every failure, and the Agility of who made it.
 * Plans routes with A* for a walker's real Agility and hands out the next waypoint. Saved to data/survey/routemap.json.
 */
public final class RouteMap implements StartupListener {
    static final int CELL = 8;
    static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("routemap", "true"));

    static final class Edge { int ok, fail; long ticks; int minAgiOk = 999, maxAgiFail = -1; }

    /** cell -> its 8 edges (index into DIRS); cell -> best tile {x, y, visits}. */
    static final Map<Integer, Edge[]> EDGES = new ConcurrentHashMap<>();
    static final Map<Integer, int[]> BEST = new ConcurrentHashMap<>();
    static final Map<Integer, Integer> LOCKED = new ConcurrentHashMap<>(); // cell -> locked doors seen in it
    static final Map<String, int[]> LAST = new ConcurrentHashMap<>();       // walker -> {cell, tick-ish time, x, y}
    static final Map<String, Object[]> PLANS = new ConcurrentHashMap<>();   // walker -> {destination cell, List<Location> waypoints, index}
    static volatile long crossings, failures, plans;
    static final Map<String, long[]> STUCKS = new ConcurrentHashMap<>();   // walker -> {count, last}
    static final Map<String, Long> OFF_ROUTE = new ConcurrentHashMap<>();  // walker -> back on routes after this

    static int cell(int x, int y) { return ((x / CELL) << 16) | (y / CELL); }

    // ------------------------------------------------------------------ part 2: walkers, portals (the design notes)

    /** Who is planning: their Agility, combat, gates passed (memoised), whether the trip is into the Wilderness. */
    static final class Walker {
        final Player p; final int agility, combat; boolean wildOk;
        final Map<Gates.Gate, Boolean> memo = new HashMap<>();
        Walker(Player p, int agility) { this.p = p; this.agility = agility; int c = 0; try { if (p != null) c = p.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { } this.combat = c; }
    }

    /** A tunnel or shortcut: stand at (fx,fy), use it, come out at (tx,ty). */
    static final class Portal {
        final Gates.Shortcut sc; final int fx, fy, tx, ty, fromCell, toCell; int ok, fail, minOk = 999;
        Portal(Gates.Shortcut sc, int fx, int fy, int tx, int ty) { this.sc = sc; this.fx = fx; this.fy = fy; this.tx = tx; this.ty = ty; fromCell = cell(fx, fy); toCell = cell(tx, ty); }
        int needs() { return sc.level >= 0 ? sc.level : Math.min(minOk, Math.max(1, sc.needs())); }
    }

    static final Map<Integer, List<Portal>> PORTALS = new ConcurrentHashMap<>();
    static final Map<String, Object[]> USING = new ConcurrentHashMap<>(); // walker -> {Portal, until, route index}

    /** A route: waypoints, and which of them are the start of a shortcut. */
    static final class Route extends ArrayList<Location> { final Map<Integer, Portal> portals = new HashMap<>(); }

    /** Learn (or confirm) a shortcut as a portal from where the walker stood to where it came out. */
    static synchronized void portal(Gates.Shortcut sc, Location from, Location to, int agility) {
        if (from == null || to == null || from.getDistance(to) < 2) return;
        List<Portal> l = PORTALS.computeIfAbsent(cell(from.getX(), from.getY()), k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        for (Portal p : l) if (p.sc == sc || (p.tx == to.getX() && p.ty == to.getY())) { p.ok++; p.minOk = Math.min(p.minOk, agility); return; }
        Portal p = new Portal(sc, from.getX(), from.getY(), to.getX(), to.getY());
        p.ok = 1; p.minOk = agility;
        restorePortal(p);
        l.add(p);
        if (Boolean.getBoolean("gates.debug")) System.out.println("[RouteMap] portal learned: " + sc.name + " " + from + " -> " + to + " (Agility " + p.needs() + ")");
    }

    /** The most-walked tile within r tiles of l (where people actually stand at a shop or a spot), or null. */
    static Location walkedNear(Location l, int r) {
        if (l == null) return null;
        int[] best = null;
        for (int cx = (l.getX() - r) / CELL; cx <= (l.getX() + r) / CELL; cx++) for (int cy = (l.getY() - r) / CELL; cy <= (l.getY() + r) / CELL; cy++) {
            int[] b = BEST.get((cx << 16) | cy);
            if (b == null || Math.max(Math.abs(b[0] - l.getX()), Math.abs(b[1] - l.getY())) > r) continue;
            if (best == null || b[2] > best[2]) best = b;
        }
        return best != null && best[2] >= 3 ? Location.create(best[0], best[1], 0) : null;
    }

    /** Audit B-20: portal ok/fail/minOk from routemap.json ("portals", absent in older files), by shortcut tile and exit; applied when the portal is made. */
    static final Map<String, int[]> SAVED_PORTALS = new ConcurrentHashMap<>();
    static String portalKey(Portal p) { return (p.sc == null ? "?" : p.sc.key()) + ">" + p.tx + "," + p.ty; }
    static void restorePortal(Portal p) {
        int[] v = SAVED_PORTALS.remove(portalKey(p));
        if (v != null) { p.ok = Math.max(p.ok, v[0]); p.fail = v[1]; p.minOk = Math.min(p.minOk, v[2]); }
    }

    static int portalCount() { int n = 0; for (List<Portal> l : PORTALS.values()) n += l.size(); return n; }

    /**
     * True while a walker is using a shortcut (callers shouldn't count it as stuck, or walk it anywhere). Pathing review 10-23
     * R-2: asking also settles the shortcut (out at the far side, or the 12 s are up). The AFK runner stops calling nextHop
     * while this is true, so only nextHop settling it left the runner "using" the shortcut for ever.
     */
    static boolean usingShortcut(Player p) {
        if (p == null) return false;
        String k = p.getUsername().toLowerCase();
        Object[] use = USING.get(k);
        return use != null && settle(p, k, use) == 0;
    }

    /** Pathing review 10-23 R-2: 0 still using the shortcut, 1 out at the far side (the plan goes on past it), 2 it didn't work (plan dropped). */
    static int settle(Player p, String k, Object[] use) {
        Portal pt = (Portal) use[0];
        double toExit = p.getLocation().getDistance(Location.create(pt.tx, pt.ty, 0));
        if (toExit <= 3 && toExit < p.getLocation().getDistance(Location.create(pt.fx, pt.fy, 0))) { // out, nearer the far side than the entrance (a stile's two sides are 2 tiles apart: "within 3 of the exit" alone was true before it climbed)
            if (USING.remove(k, use)) {
                pt.ok++; pt.minOk = Math.min(pt.minOk, agility(p));
                Object[] pl = PLANS.get(k); int next = (Integer) use[2] + 1;
                if (pl != null && (Integer) pl[2] < next && next < ((List<?>) pl[1]).size()) pl[2] = next; // on past the shortcut, not back to its entrance
            }
            return 1;
        }
        if (System.currentTimeMillis() < (Long) use[1]) return 0;
        if (USING.remove(k, use)) { pt.fail++; PLANS.remove(k); if (Boolean.getBoolean("gates.debug")) System.out.println("[RouteMap] " + p.getUsername() + " couldn't use " + pt.sc.name + " at " + pt.sc.key()); }
        return 2;
    }
    static int cx(int c) { return c >>> 16; }
    static int cy(int c) { return c & 0xffff; }
    static int dirIndex(int dx, int dy) { for (int i = 0; i < 8; i++) if (DIRS[i][0] == dx && DIRS[i][1] == dy) return i; return -1; }

    static Edge edge(int c, int dir, boolean make) {
        Edge[] es = EDGES.get(c);
        if (es == null) { if (!make) return null; es = new Edge[8]; Edge[] prev = EDGES.putIfAbsent(c, es); if (prev != null) es = prev; }
        if (es[dir] == null && make) es[dir] = new Edge();
        return es[dir];
    }

    /**
     * Pathing review 10-23 R-1: sample() visits one tile at a time (n = 1), so "replace when n > visits" meant the first tile
     * seen in a cell stayed its tile for ever. A challenger tile and count ride along (indices 3-5, not saved): the challenger
     * gains on its own visits and fades on other tiles' (Misra-Gries), and takes over once it is walked more than the incumbent.
     */
    static void visit(int x, int y, int n) {
        int c = cell(x, y);
        BEST.compute(c, (k, v) -> {
            if (v == null) return new int[]{x, y, n, 0, 0, 0};
            if (v[0] == x && v[1] == y) { v[2] += n; return v; }
            int[] w = v.length >= 6 ? v : new int[]{v[0], v[1], v[2], 0, 0, 0}; // loaded from the file: no challenger yet
            if (w[5] > 0 && w[3] == x && w[4] == y) w[5] += n;
            else if (w[5] <= n) { w[3] = x; w[4] = y; w[5] = n; } // a new challenger (the last one had faded)
            else w[5] -= n;                                         // some other tile: the challenger fades
            if (w[5] > w[2]) return new int[]{w[3], w[4], w[5], w[0], w[1], w[2]}; // walked more than the incumbent: the cell's tile now
            return w;
        });
    }

    /** A walker moved from (ax,ay) to (bx,by): every cell boundary crossed on the way is a success. */
    static void crossed(int ax, int ay, int bx, int by, int agility, long ticks) {
        int ca = cell(ax, ay), cb = cell(bx, by);
        if (ca == cb) return;
        int dx = Integer.signum(cx(cb) - cx(ca)), dy = Integer.signum(cy(cb) - cy(ca));
        if (Math.abs(cx(cb) - cx(ca)) > 1 || Math.abs(cy(cb) - cy(ca)) > 1) return; // too far in one sample (a teleport or a long gap)
        int d = dirIndex(dx, dy);
        if (d < 0) return;
        Edge e = edge(ca, d, true);
        synchronized (e) { e.ok++; e.ticks += ticks; e.minAgiOk = Math.min(e.minAgiOk, agility); }
        Edge back = edge(cb, dirIndex(-dx, -dy), true); // walking is two-way unless failures say otherwise
        synchronized (back) { if (back.ok == 0) back.minAgiOk = Math.min(back.minAgiOk, agility); }
        crossings++;
    }

    /** A walker at (x,y) failed to make progress towards (tx,ty). */
    static void failedAt(int x, int y, int tx, int ty, int agility) {
        int c = cell(x, y);
        int dx = Integer.signum(tx - x), dy = Integer.signum(ty - y);
        if (Math.abs(tx - x) * 2 < Math.abs(ty - y)) dx = 0;
        if (Math.abs(ty - y) * 2 < Math.abs(tx - x)) dy = 0;
        int d = dirIndex(dx, dy);
        if (d < 0) return;
        Edge e = edge(c, d, true);
        synchronized (e) { e.fail++; e.maxAgiFail = Math.max(e.maxAgiFail, agility); }
        failures++;
    }

    /** Edge cost for a walker: gates it fails are walls (unless it's already inside), danger/deaths/Wilderness cost extra. */
    static double cost(int c, int dir, Walker w) {
        double r = cost(c, dir, w.agility);
        if (r < 0 || w.p == null) return r;
        int n = (cx(c) + DIRS[dir][0]) << 16 | (cy(c) + DIRS[dir][1]);
        if (Gates.cellBlocked(n, w.p, w.memo) && !Gates.cellBlocked(c, w.p, w.memo)) return -1;
        return r + Heat.extra(n, w);
    }

    /** Edge cost multiplier for a walker with this Agility; < 0 means impassable for them. */
    static double cost(int c, int dir, int agility) {
        int n = (cx(c) + DIRS[dir][0]) << 16 | (cy(c) + DIRS[dir][1]);
        if (LOCKED.getOrDefault(n, 0) >= 2 && !BEST.containsKey(n)) return -1;
        Edge e = edge(c, dir, false);
        double base = dir >= 4 ? 1.414 : 1.0;
        if (e == null || e.ok == 0) {
            if (e != null && e.fail >= 10) return -1;                        // tried ten times, never made it: a wall
            if (e != null && e.fail >= 3) return base * 25;                  // tried and failed: very dear, but a success can still heal it (third consultant check)
            return base * (BEST.containsKey(c) && BEST.containsKey(n) ? 2.5 : 5.0); // unknown: allowed, but explore carefully
        }
        // learned Agility gate: lower-Agility walkers failed, higher ones made it
        if (e.fail >= 2 && e.maxAgiFail >= 0 && e.maxAgiFail < e.minAgiOk && agility < e.minAgiOk) return -1;
        return base * (1.0 + 3.0 * e.fail / (double) (e.ok + e.fail));
    }

    /** A* over cells. Returns waypoints (each cell's most-walked tile) or null. */
    static List<Location> plan(Location from, Location to, int agility) { return plan(from, to, new Walker(null, agility)); }

    static List<Location> plan(Location from, Location to, Walker w) {
        int agility = w.agility;
        if (to != null) w.wildOk = Heat.wildLevel(to.getX(), to.getY()) > 0;
        Map<Integer, Portal> via = new HashMap<>();
        if (from == null || to == null || from.getZ() != 0 || to.getZ() != 0 || from.getY() > 6400 || to.getY() > 6400) return null;
        int s = cell(from.getX(), from.getY()), g = cell(to.getX(), to.getY());
        if (s == g) return new Route();
        int minX = Math.min(cx(s), cx(g)) - 40, maxX = Math.max(cx(s), cx(g)) + 40, minY = Math.min(cy(s), cy(g)) - 40, maxY = Math.max(cy(s), cy(g)) + 40;
        Map<Integer, Double> gs = new HashMap<>(); Map<Integer, Integer> came = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        gs.put(s, 0.0); open.add(new double[]{h(s, g), s});
        int expanded = 0;
        while (!open.isEmpty() && expanded < 25_000) {
            double[] top = open.poll(); int c = (int) top[1];
            if (c == g) break;
            expanded++;
            double gc = gs.get(c);
            if (top[0] > gc + h(c, g) + 1e-6) continue;
            for (int d = 0; d < 8; d++) {
                int nx = cx(c) + DIRS[d][0], ny = cy(c) + DIRS[d][1];
                if (nx < minX || nx > maxX || ny < minY || ny > maxY || nx < 0 || ny < 0) continue;
                double wc = cost(c, d, w);
                if (wc < 0) continue;
                int n = (nx << 16) | ny;
                double ng = gc + wc;
                Double old = gs.get(n);
                if (old != null && old <= ng) continue;
                gs.put(n, ng); came.put(n, c); via.remove(n); open.add(new double[]{ng + h(n, g), n});
            }
            List<Portal> ps = PORTALS.get(c); // tunnels and shortcuts out of this cell
            if (ps != null) for (Portal pt : ps) {
                if (agility < pt.needs() || (pt.fail >= 3 && pt.fail > pt.ok * 2)) continue;
                int n = pt.toCell, nx = cx(n), ny = cy(n);
                if (nx < minX || nx > maxX || ny < minY || ny > maxY) continue;
                if (w.p != null && Gates.cellBlocked(n, w.p, w.memo) && !Gates.cellBlocked(c, w.p, w.memo)) continue;
                double ng = gc + 3.0 + Heat.extra(n, w);
                Double old = gs.get(n);
                if (old != null && old <= ng) continue;
                gs.put(n, ng); came.put(n, c); via.put(n, pt); open.add(new double[]{ng + h(n, g), n});
            }
        }
        if (!came.containsKey(g)) return null;
        List<Integer> cells = new ArrayList<>();
        for (int c = g; c != s; c = came.get(c)) cells.add(0, c);
        Route out = new Route();
        for (int c : cells) {
            Portal pt = via.get(c);
            if (pt != null) { out.portals.put(out.size(), pt); out.add(Location.create(pt.fx, pt.fy, 0)); out.add(Location.create(pt.tx, pt.ty, 0)); continue; }
            int[] b = BEST.get(c);
            out.add(b != null ? Location.create(b[0], b[1], 0) : Location.create(cx(c) * CELL + CELL / 2, cy(c) * CELL + CELL / 2, 0));
        }
        if (via.containsKey(g)) out.add(to); else out.set(out.size() - 1, to); // the real destination at the end
        plans++;
        return out;
    }

    static double lineDist(Location a, Location b, Location p) {
        double dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), len = dx * dx + dy * dy;
        if (len == 0) return p.getDistance(a);
        double t = Math.max(0, Math.min(1, ((p.getX() - a.getX()) * dx + (p.getY() - a.getY()) * dy) / len));
        return Math.hypot(p.getX() - (a.getX() + t * dx), p.getY() - (a.getY() + t * dy));
    }

    static double h(int a, int b) { double dx = cx(a) - cx(b), dy = cy(a) - cy(b); return Math.sqrt(dx * dx + dy * dy); }

    static final java.util.Map<String, long[]> NO_ROUTE = new java.util.concurrent.ConcurrentHashMap<>(); // audit B-7: bot -> {destination cell, retry after}

    /**
     * Audit 10-21 "route-plan backtracking": the bot went past waypoint i without coming within 6 tiles (cut the corner)
     * and is already on its way along the next stretch: past i in the direction of i+1, within 4 tiles of the route's own
     * (walkable) line from i to i+1. The next hop used to send it back to i. Near i only (15 tiles).
     */
    static boolean cutCorner(List<Location> route, int i, Location here) {
        Location a = route.get(i), b = route.get(i + 1);
        if (a.getDistance(here) >= 15) return false;
        double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
        double along = (here.getX() - a.getX()) * dx + (here.getY() - a.getY()) * dy; // > 0: past a, towards b
        return along > 0 && lineDist(a, b, here) <= 4;
    }

    /** The next waypoint, about 20-30 tiles ahead on the learned route, or null (no route known: walk straight). */
    static Location nextHop(Player p, Location to) {
        Location hop = nextHopInner(p, to);
        if (hop != null) Watchdog.fdrv(p, Failures.DRV_ROUTEMAP); // batch 19a design 3.1: the learned route gave this hop (or a shortcut in use)
        return hop;
    }

    static Location nextHopInner(Player p, Location to) { // batch 19a: the batch 18 nextHop(), unchanged
        if (!ON || p == null || to == null) return null;
        Location here = p.getLocation();
        String k = p.getUsername().toLowerCase();
        if (here.getZ() != 0 || to.getZ() != 0 || here.getDistance(to) < 20) { PLANS.remove(k); return null; } // arrived (or no route needed): the plan is done (audit 10-21 B3)
        if (OFF_ROUTE.getOrDefault(k, 0L) > System.currentTimeMillis()) return null; // the route kept failing here: old way for a bit
        int g = cell(to.getX(), to.getY());
        Object[] pl = PLANS.get(k);
        if (pl == null || (Integer) pl[0] != g) {
            long[] nf = NO_ROUTE.get(k);
            if (nf != null && nf[0] == g && nf[1] > System.currentTimeMillis()) return null; // audit B-7: no route there a moment ago
            List<Location> route = plan(here, to, new Walker(p, agility(p)));
            if (route == null || route.isEmpty()) { PLANS.remove(k); NO_ROUTE.put(k, new long[]{g, System.currentTimeMillis() + 60_000}); return null; }
            pl = new Object[]{g, route, 0, System.currentTimeMillis()}; PLANS.put(k, pl); // [3]: when it was made (the watchdog ignores plans older than 2 min)
        }
        @SuppressWarnings("unchecked") List<Location> route = (List<Location>) pl[1];
        int i = (Integer) pl[2];
        Route rt = route instanceof Route ? (Route) route : null;
        long now = System.currentTimeMillis();
        Object[] use = USING.get(k);
        if (use != null) { // using a shortcut: wait for it, then carry on from the far side
            int st = settle(p, k, use); // pathing review 10-23 R-2 (the same rules, shared with usingShortcut)
            if (st == 0) return here;
            if (st == 2) return null;
            i = (Integer) use[2] + 1; pl[2] = i;
        }
        while (i < route.size() - 1 && (route.get(i).getDistance(here) < 6 || cutCorner(route, i, here)) && (rt == null || !rt.portals.containsKey(i))) i++; // passed it (or cut the corner past it: it used to send the bot back)
        if (rt != null && rt.portals.containsKey(i)) { // the next step is a shortcut: walk to it, then use it like a player
            Portal pt = rt.portals.get(i);
            pl[2] = i;
            if (here.getDistance(route.get(i)) <= 3) {
                core.game.node.scenery.Scenery obj = null;
                try { obj = core.game.world.map.RegionManager.getObject(pt.sc.z, pt.sc.x, pt.sc.y); } catch (Throwable ignored) { }
                if (obj == null || !p.isArtificial()) { if (obj == null) pt.fail++; PLANS.remove(k); return null; } // a real player's character is never walked through a shortcut by the map (review 10-22)
                // Pathing review 10-23 R-2: the click goes in on the next tick. Every caller walks to (or "steps towards") the hop
                // returned here in this same call, and a walk through the pulse manager (the AFK runner's stepTo fallbacks: a door
                // click, randomWalkTo) replaced the shortcut's interaction, so good shortcuts were learned as failing. A tick later
                // the click replaces whatever the caller started instead.
                final Object[] use2 = new Object[]{pt, now + 12_000L, i};
                final Player who = p; final core.game.node.scenery.Scenery o = obj;
                USING.put(k, use2);
                GameWorld.getPulser().submit(new Pulse(1) {
                    @Override public boolean pulse() {
                        try { if (who.isActive() && USING.get(k) == use2) new core.game.bots.ScriptAPI(who).interact(who, o, pt.sc.option); }
                        catch (Throwable t) { if (USING.remove(k, use2)) { pt.fail++; PLANS.remove(k); } }
                        return true;
                    }
                });
                if (Boolean.getBoolean("gates.debug")) System.out.println("[RouteMap] " + p.getUsername() + " uses " + pt.sc.name + " (" + pt.sc.option + ") at " + pt.sc.key());
                return here;
            }
            return route.get(i);
        }
        // look ahead: the furthest waypoint within ~25 tiles keeps the walk smooth
        // Look ahead as far as the route runs straight (every waypoint in between within 4 tiles of the line), up to
        // 28 tiles: long hops on roads, short ones round bends, so the walk is smooth and not stop-start.
        int j = i;
        while (j + 1 < route.size() && route.get(j + 1).getDistance(here) <= 28 && (rt == null || !rt.portals.containsKey(j))) {
            Location cand = route.get(j + 1); boolean straight = true;
            for (int m = i; m <= j && straight; m++) if (lineDist(here, cand, route.get(m)) > 4) straight = false;
            if (!straight && route.get(j + 1).getDistance(here) > 14) break;
            j++;
        }
        if (route.get(j).getDistance(here) < 2 && (rt == null || !rt.portals.containsKey(j))) { if (j + 1 >= route.size()) { pl[2] = i; return to; } j++; i = Math.max(i, j); } // batch 17 item9: never hand back the bot's own tile (callers read that as a shortcut click and froze, 3167,3254)
        pl[2] = i;
        return route.get(j);
    }

    /** The walker is stuck heading for `to`: the edge it was crossing learns a failure, and the plan is redone. */
    static void stuck(Player p, Location to) {
        if (p == null || to == null) return;
        Location here = p.getLocation();
        Location aim = to;
        Object[] pl = PLANS.get(p.getUsername().toLowerCase());
        if (pl != null) { @SuppressWarnings("unchecked") List<Location> r = (List<Location>) pl[1]; int i = (Integer) pl[2]; if (i < r.size()) aim = r.get(i); }
        if (here.getZ() == 0) failedAt(here.getX(), here.getY(), aim.getX(), aim.getY(), agility(p));
        String k = p.getUsername().toLowerCase();
        PLANS.remove(k);
        long now = System.currentTimeMillis();
        long[] st = STUCKS.computeIfAbsent(k, x -> new long[2]);
        if (now - st[1] > 120_000) st[0] = 0;
        st[0]++; st[1] = now;
        if (st[0] >= 2) { OFF_ROUTE.put(k, now + 180_000); st[0] = 0; } // twice in two minutes: walk the old way for three minutes
    }

    static int agility(Player p) { try { return p.getSkills().getStaticLevel(Skills.AGILITY); } catch (Throwable t) { return 1; } }

    // ------------------------------------------------------------------ learning from everyone, all the time

    static void sample() {
        long now = System.currentTimeMillis();
        for (Player p : Repository.getPlayers()) {
            if (p == null || !p.isActive()) continue;
            Location l = p.getLocation();
            if (l == null || l.getZ() != 0 || l.getY() > 6400) { LAST.remove(p.getUsername().toLowerCase()); continue; }
            String k = p.getUsername().toLowerCase();
            int c = cell(l.getX(), l.getY());
            int[] prev = LAST.get(k);
            if (prev != null && prev[0] != c) {
                double moved = Math.hypot(l.getX() - prev[2], l.getY() - prev[3]);
                if (moved <= 12) crossed(prev[2], prev[3], l.getX(), l.getY(), agility(p), Math.max(1, (now - ((long) prev[1] << 10)) / 600));
            }
            if (prev == null || prev[0] != c || prev[2] != l.getX() || prev[3] != l.getY()) visit(l.getX(), l.getY(), 1);
            LAST.put(k, new int[]{c, (int) (now >> 10), l.getX(), l.getY()});
        }
    }

    // ------------------------------------------------------------------ startup, training, saving

    static File dir() { return new File(ServerConstants.DATA_PATH, "survey"); }
    static File file() { return new File(dir(), "routemap.json"); }

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !ON) return;
        try { if (!load()) train(); } catch (Throwable t) { SAVE_OK = false; System.out.println("[RouteMap] start failed: " + t + "; saving disabled so the file isn't overwritten with an empty map"); }
        GameWorld.getPulser().submit(new Pulse(2) { @Override public boolean pulse() { try { sample(); } catch (Throwable ignored) { } return false; } });
        new java.util.Timer("RouteMap", true).schedule(new java.util.TimerTask() { public void run() { try { save(); } catch (Throwable t) { System.out.println("[RouteMap] save failed: " + t); } } }, 600_000, 600_000);
        System.out.println("[RouteMap] " + EDGES.size() + " cells with edges, " + BEST.size() + " cells walked, " + portalCount() + " tunnels/shortcuts; learning from every walker");
        if (Boolean.getBoolean("routemap.test")) { // trials: a few known trips, low and high Agility
            int[][] trips = {{3200, 3227, 3014, 3204}, {3232, 3248, 3165, 3487}, {3192, 3292, 3049, 3234}};
            for (int[] t : trips) for (int agi : new int[]{1}) {
                long t0 = System.nanoTime();
                List<Location> r = plan(Location.create(t[0], t[1], 0), Location.create(t[2], t[3], 0), agi);
                System.out.println("[RouteMap] test " + t[0] + "," + t[1] + " -> " + t[2] + "," + t[3] + " agility " + agi + ": " + (r == null ? "no route" : r.size() + " waypoints " + r) + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            }
        }
    }

    /** First start: learn from the surveyors' legs, the tile census and the door survey. */
    static void train() throws Exception {
        File legs = new File(dir(), "legs.jsonl");
        int n = 0;
        // batch 17 item2: the weekly archives (legs-YYYY-Www.jsonl.gz, newest 4, oldest first) and then the current legs.jsonl
        List<File> sources = new ArrayList<>();
        File[] arch = dir().listFiles((d, nm) -> nm.startsWith("legs-") && nm.endsWith(".jsonl.gz")); if (arch == null) arch = new File[0]; // the same listing as Surveyors.legArchives (not called: that would start the Surveyors timer class)
        java.util.Arrays.sort(arch, java.util.Comparator.comparing(File::getName));
        for (int i = Math.max(0, arch.length - 4); i < arch.length; i++) sources.add(arch[i]); // Surveyors.KEEP_ARCHIVES
        if (legs.exists()) sources.add(legs);
        for (File src : sources) try (BufferedReader r = new BufferedReader(src.getName().endsWith(".gz") ? new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(src), 1 << 16)) : new FileReader(src))) {
            JSONParser jp = new JSONParser(); String line;
            while ((line = r.readLine()) != null) {
                JSONObject j; try { j = (JSONObject) jp.parse(line); } catch (Exception e) { continue; }
                int agi = j.get("agility") instanceof Number ? ((Number) j.get("agility")).intValue() : (Boolean.TRUE.equals(j.get("high")) ? 80 : 1);
                JSONArray path = (JSONArray) j.get("path");
                if (path == null) continue;
                int px = -1, py = -1;
                for (Object o : path) {
                    JSONArray pt = (JSONArray) o; int x = ((Number) pt.get(0)).intValue(), y = ((Number) pt.get(1)).intValue();
                    if (px >= 0 && Math.hypot(x - px, y - py) <= 40) {
                        // interpolate so every boundary crossed between samples counts
                        int steps = (int) Math.max(1, Math.ceil(Math.hypot(x - px, y - py) / 4.0));
                        int lx = px, ly = py;
                        for (int s2 = 1; s2 <= steps; s2++) { int ix = px + (x - px) * s2 / steps, iy = py + (y - py) * s2 / steps; crossed(lx, ly, ix, iy, agi, 4); lx = ix; ly = iy; }
                    }
                    px = x; py = y;
                }
                String res = String.valueOf(j.get("result"));
                if (("blocked".equals(res) || "timeout".equals(res)) && j.get("at") instanceof JSONArray && j.get("to") instanceof JSONArray) {
                    JSONArray at = (JSONArray) j.get("at"), to = (JSONArray) j.get("to");
                    failedAt(((Number) at.get(0)).intValue(), ((Number) at.get(1)).intValue(), ((Number) to.get(0)).intValue(), ((Number) to.get(1)).intValue(), agi);
                }
                n++;
            }
        }
        File tiles = new File(dir(), "tiles.json");
        if (tiles.exists()) {
            JSONObject t = (JSONObject) new JSONParser().parse(new FileReader(tiles));
            for (Object k : t.keySet()) { String[] a = String.valueOf(k).split(","); if (a.length < 3 || !"0".equals(a[2])) continue; visit(Integer.parseInt(a[0]), Integer.parseInt(a[1]), ((Number) t.get(k)).intValue()); }
        }
        File doors = new File(dir(), "doors.jsonl");
        if (doors.exists()) try (BufferedReader r = new BufferedReader(new FileReader(doors))) {
            JSONParser jp = new JSONParser(); String line;
            while ((line = r.readLine()) != null) {
                try { JSONObject j = (JSONObject) jp.parse(line); if (!String.valueOf(j.get("result")).startsWith("stayed shut")) continue; JSONArray at = (JSONArray) j.get("at"); LOCKED.merge(cell(((Number) at.get(0)).intValue(), ((Number) at.get(1)).intValue()), 1, Integer::sum); } catch (Exception ignored) { }
            }
        }
        System.out.println("[RouteMap] trained on " + n + " surveyor legs (" + sources.size() + " file(s): legs.jsonl and its weekly archives): " + crossings + " crossings, " + failures + " failures, " + BEST.size() + " cells");
        save();
    }

    @SuppressWarnings("unchecked")
    /** Failures on edges nobody has crossed fade by one each save, so a fence learned before a bridge or a detour heals. */
    static int decayRounds = new java.util.Random().nextInt(144); // a random start: the server restarts at least daily, so a count from 0 would never reach 144
    /**
     * Failures fade one a save (10 min). A wall (10+ failures, see cost) fades only once a day (every 144th save): at one a
     * save a wall dropped to 9, was tried again, failed back to 10, and flapped like that every ten minutes (audit 10-21 "wall
     * decay flapping"). A wall that was a mistake still heals in a day or two, or at once on a success.
     */
    static void decay() {
        boolean walls = ++decayRounds % 144 == 0;
        for (Edge[] es : EDGES.values()) for (Edge e : es) if (e != null && e.ok == 0 && e.fail > 0 && (e.fail < 10 || walls)) synchronized (e) { e.fail--; }
    }

    static volatile boolean SAVE_OK = true;
    /** A walker that logged out: nothing of it stays in the per-walker maps. */
    static void forget(String k) { LAST.remove(k); PLANS.remove(k); STUCKS.remove(k); OFF_ROUTE.remove(k); USING.remove(k); }

    static synchronized void save() throws Exception {
        if (!SAVE_OK) return;
        decay();
        JSONObject root = new JSONObject();
        JSONObject es = new JSONObject();
        for (Map.Entry<Integer, Edge[]> e : EDGES.entrySet()) {
            JSONArray arr = new JSONArray();
            for (Edge x : e.getValue()) { if (x == null) { arr.add(null); continue; } JSONArray v = new JSONArray(); v.add(x.ok); v.add(x.fail); v.add(x.ticks); v.add(x.minAgiOk); v.add(x.maxAgiFail); arr.add(v); }
            es.put(String.valueOf(e.getKey()), arr);
        }
        JSONObject best = new JSONObject();
        for (Map.Entry<Integer, int[]> e : BEST.entrySet()) { JSONArray v = new JSONArray(); v.add(e.getValue()[0]); v.add(e.getValue()[1]); v.add(e.getValue()[2]); best.put(String.valueOf(e.getKey()), v); }
        JSONObject locked = new JSONObject(); for (Map.Entry<Integer, Integer> e : LOCKED.entrySet()) locked.put(String.valueOf(e.getKey()), e.getValue());
        JSONObject ps = new JSONObject(); // audit B-20: failed shortcuts no longer come back after every restart
        for (List<Portal> l : PORTALS.values()) for (Portal p : l) { JSONArray v = new JSONArray(); v.add(p.ok); v.add(p.fail); v.add(p.minOk); ps.put(portalKey(p), v); }
        for (Map.Entry<String, int[]> e : SAVED_PORTALS.entrySet()) if (!ps.containsKey(e.getKey())) { JSONArray v = new JSONArray(); v.add(e.getValue()[0]); v.add(e.getValue()[1]); v.add(e.getValue()[2]); ps.put(e.getKey(), v); } // not made yet this boot: kept
        root.put("edges", es); root.put("best", best); root.put("locked", locked); root.put("portals", ps);
        root.put("crossings", crossings); root.put("failures", failures);
        dir().mkdirs();
        Ledger.writeAtomic(file(), root.toJSONString());
    }

    static boolean load() throws Exception {
        File f = file();
        if (!f.exists()) return false;
        JSONObject root = (JSONObject) new JSONParser().parse(new FileReader(f));
        JSONObject es = (JSONObject) root.get("edges");
        for (Object k : es.keySet()) {
            JSONArray arr = (JSONArray) es.get(k); Edge[] edges = new Edge[8];
            for (int i = 0; i < 8 && i < arr.size(); i++) {
                Object o = arr.get(i); if (!(o instanceof JSONArray)) continue;
                JSONArray v = (JSONArray) o; Edge x = new Edge();
                x.ok = ((Number) v.get(0)).intValue(); x.fail = ((Number) v.get(1)).intValue(); x.ticks = ((Number) v.get(2)).longValue(); x.minAgiOk = ((Number) v.get(3)).intValue(); x.maxAgiFail = ((Number) v.get(4)).intValue();
                edges[i] = x;
            }
            EDGES.put(Integer.parseInt(String.valueOf(k)), edges);
        }
        JSONObject best = (JSONObject) root.get("best");
        for (Object k : best.keySet()) { JSONArray v = (JSONArray) best.get(k); BEST.put(Integer.parseInt(String.valueOf(k)), new int[]{((Number) v.get(0)).intValue(), ((Number) v.get(1)).intValue(), ((Number) v.get(2)).intValue()}); }
        JSONObject locked = (JSONObject) root.get("locked");
        if (locked != null) for (Object k : locked.keySet()) LOCKED.put(Integer.parseInt(String.valueOf(k)), ((Number) locked.get(k)).intValue());
        if (root.get("crossings") instanceof Number) crossings = ((Number) root.get("crossings")).longValue();
        if (root.get("failures") instanceof Number) failures = ((Number) root.get("failures")).longValue();
        if (root.get("portals") instanceof JSONObject) { JSONObject ps = (JSONObject) root.get("portals"); for (Object k : ps.keySet()) { JSONArray v = (JSONArray) ps.get(k); SAVED_PORTALS.put(String.valueOf(k), new int[]{((Number) v.get(0)).intValue(), ((Number) v.get(1)).intValue(), ((Number) v.get(2)).intValue()}); } } // audit B-20
        for (List<Portal> l : PORTALS.values()) for (Portal p : l) restorePortal(p); // portals the Gates load made before this
        System.out.println("[RouteMap] loaded: " + EDGES.size() + " cells with edges, " + crossings + " crossings, " + failures + " failures learned so far");
        return true;
    }
}
