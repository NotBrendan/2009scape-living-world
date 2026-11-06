package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heat layers over the route map's 8x8 cells (design: the design notes (not published) §3): traffic, danger (the
 * strongest attackable monster the surveyors saw there), recent smart-bot deaths, Wilderness level, gates and resources.
 * Bots read danger, deaths and Wilderness when they plan a route; everything is written to data/survey/heat.json every
 * 10 min for the website map.
 */
public final class Heat implements StartupListener {
    static final Map<Integer, Integer> DANGER = new ConcurrentHashMap<>();           // cell -> highest monster level seen (2+ sightings)
    static final Map<Integer, List<Long>> DEATHS = new ConcurrentHashMap<>();       // cell -> when smart bots died there
    static final Pattern NPC = Pattern.compile("^npc:(.+) \\(level (\\d+)\\)$");
    static volatile long built;

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        new java.util.Timer("Heat", true).schedule(new java.util.TimerTask() {
            public void run() { try { rebuild(); save(); } catch (Throwable t) { System.out.println("[Heat] " + t); } }
        }, 120_000, 600_000);
    }

    static void death(Location at) {
        if (at == null || at.getZ() != 0) return;
        DEATHS.computeIfAbsent(RouteMap.cell(at.getX(), at.getY()), k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(System.currentTimeMillis());
    }

    /** Danger from what the surveyors have seen (Resources): the strongest monster per cell. */
    static void rebuild() {
        Map<Integer, Integer> d = new HashMap<>();
        for (Map.Entry<String, Map<Integer, Integer>> e : Resources.SEEN.entrySet()) {
            Matcher m = NPC.matcher(e.getKey());
            if (!m.matches()) continue;
            int lvl; try { lvl = Integer.parseInt(m.group(2)); } catch (NumberFormatException x) { continue; }
            if (lvl <= 0) continue;
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) if (c.getValue() >= 2) d.merge(c.getKey(), lvl, Math::max);
        }
        DANGER.clear(); DANGER.putAll(d);
        long cut = System.currentTimeMillis() - 24 * 3600_000L;
        for (List<Long> l : DEATHS.values()) synchronized (l) { l.removeIf(t -> t < cut); }
        built = System.currentTimeMillis();
    }

    static int wildLevel(int x, int y) { return x >= 2944 && x <= 3392 && y >= 3520 && y <= 3970 ? (y - 3520) / 8 + 1 : 0; }

    static int deathsAt(int cell) { List<Long> l = DEATHS.get(cell); return l == null ? 0 : l.size(); }

    /** Extra tile-cost for the real planner (NavPlanner) at a surface tile: deaths and the Wilderness steer routes away; walker-independent. */
    static int navCost(int x, int y) { return navCost(x, y, 0); }

    /** batch 20 B1: the combat band of a walker for the planner: 1 under combat 30, 2 under 60, else 0 (no extra cost). Bots only. */
    static int band(core.game.node.entity.player.Player p) {
        if (!BAND_ON || p == null || !p.isArtificial()) return 0;
        int cb = 99; try { cb = p.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
        return cb < 30 ? 1 : cb < 60 ? 2 : 0;
    }
    static final boolean BAND_ON = !"false".equals(System.getProperty("heat.band"));

    /** navCost plus, for a low band, a cost for cells where a monster that would attack it and outclass it was seen. */
    static int navCost(int x, int y, int band) {
        int c = RouteMap.cell(x, y), add = 0;
        if (band > 0) { // batch 20 B1: roadside aggro (ghouls by the Salve, Trollheim, the white wolves) stopped low-level walkers
            Integer dl = DANGER.get(c); int rep = band == 1 ? 20 : 45; // a representative combat level for the band
            if (dl != null && dl * 2 >= rep && dl > rep * 0.7) add += dl > rep * 1.2 ? 24 : 10;
        }
        int deaths = deathsAt(c); if (deaths > 0) add += 12 * Math.min(deaths, 4);
        Integer d = DANGER.get(c); if (d != null && d >= 40) add += 6; // somewhere strong things live: not forbidden, just dearer
        if (wildLevel(x, y) > 0) add += 40;
        return add;
    }

    /**
     * Extra route cost for a walker going into this cell: a monster that would attack it and is too strong, recent
     * deaths, the Wilderness. 0 for a human walker or a cell with nothing to fear.
     */
    static double extra(int cell, RouteMap.Walker w) {
        if (w == null || w.p == null || !w.p.isArtificial()) return 0;
        double add = 0;
        Integer d = DANGER.get(cell);
        if (d != null && w.combat > 0 && d * 2 >= w.combat) { // aggressive to it (combat at most twice the monster's)
            if (d > w.combat * 1.2) add += 6; else if (d > w.combat * 0.7) add += 2.5;
        }
        int deaths = deathsAt(cell);
        if (deaths > 0) add += 1.5 * Math.min(deaths, 4);
        int wild = wildLevel(RouteMap.cx(cell) * RouteMap.CELL + 4, RouteMap.cy(cell) * RouteMap.CELL + 4);
        if (wild > 0 && !w.wildOk) add += 4 + wild * 0.3;
        return add;
    }

    @SuppressWarnings("unchecked")
    static void save() throws Exception {
        java.util.Set<Integer> cells = new java.util.HashSet<>(RouteMap.BEST.keySet());
        cells.addAll(DANGER.keySet()); cells.addAll(DEATHS.keySet()); cells.addAll(Gates.BY_CELL.keySet());
        Map<Integer, List<String>> res = new HashMap<>();
        for (Map.Entry<String, Map<Integer, Integer>> e : Resources.SEEN.entrySet())
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) if (c.getValue() >= 2) res.computeIfAbsent(c.getKey(), k -> new ArrayList<>()).add(e.getKey());
        JSONObject out = new JSONObject();
        out.put("cell", RouteMap.CELL);
        out.put("built", System.currentTimeMillis());
        JSONObject layers = new JSONObject();
        for (int c : cells) {
            JSONObject j = new JSONObject();
            int[] b = RouteMap.BEST.get(c);
            if (b != null) j.put("t", b[2]);
            Integer d = DANGER.get(c); if (d != null) j.put("d", d);
            int dd = deathsAt(c); if (dd > 0) j.put("k", dd);
            int w = wildLevel(RouteMap.cx(c) * RouteMap.CELL + 4, RouteMap.cy(c) * RouteMap.CELL + 4); if (w > 0) j.put("w", w);
            List<Gates.Gate> gs = Gates.BY_CELL.get(c);
            if (gs != null) { JSONArray a = new JSONArray(); for (Gates.Gate g : gs) a.add(g.id); j.put("g", a); }
            List<String> r = res.get(c);
            if (r != null) { JSONArray a = new JSONArray(); a.addAll(r); j.put("r", a); }
            if (!j.isEmpty()) layers.put(RouteMap.cx(c) + "," + RouteMap.cy(c), j);
        }
        out.put("cells", layers);
        JSONArray sc = new JSONArray();
        for (Gates.Shortcut s : Gates.SHORTCUTS.values()) { JSONArray a = new JSONArray(); a.add(s.x); a.add(s.y); a.add(s.z); a.add(s.name); a.add(s.needs()); a.add(s.lowOk + s.highOk > 0); sc.add(a); }
        out.put("shortcuts", sc);
        Ledger.writeAtomic(new File(new File(ServerConstants.DATA_PATH, "survey"), "heat.json"), out.toJSONString());
    }
}
