package content.custom;

import core.ServerConstants;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where things are (2026-10-20: "the surveyors should be collecting collection spot data"). As surveyors walk
 * they note rocks (by ore), trees, fishing spots (by option), monsters (by name and level) and useful scenery (banks,
 * furnaces, anvils, ranges, altars, spinning wheels) within ~10 tiles, counted per 8x8 cell. Saved to
 * data/survey/resources.json as "kind:name" -> {cell -> count}. Anyone can ask for the nearest cluster.
 */
final class Resources {
    static final Map<String, Map<Integer, Integer>> SEEN = new ConcurrentHashMap<>();
    static final Map<String, Long> SCANNED = new ConcurrentHashMap<>(); // cell -> last scan, so a cell isn't counted twice a minute
    static volatile boolean loaded;

    static File file() { return new File(new File(ServerConstants.DATA_PATH, "survey"), "resources.json"); }

    static void note(String key, Location at) {
        SEEN.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).merge(RouteMap.cell(at.getX(), at.getY()), 1, Integer::sum);
    }

    /** A surveyor looks around (every ~10 s): everything worth knowing within 10 tiles. */
    static void scan(Player bot) {
        load();
        Location c = bot.getLocation();
        if (c.getZ() != 0) return;
        String ck = String.valueOf(RouteMap.cell(c.getX(), c.getY()));
        long now = System.currentTimeMillis();
        if (now - SCANNED.getOrDefault(ck, 0L) < 60_000) return;
        SCANNED.put(ck, now);
        for (int dx = -10; dx <= 10; dx++) for (int dy = -10; dy <= 10; dy++) {
            Scenery s = RegionManager.getObject(0, c.getX() + dx, c.getY() + dy);
            if (s == null || s.getName() == null || !s.isActive()) continue;
            String n = s.getName().toLowerCase(Locale.ROOT);
            try {
                content.global.skill.gather.mining.MiningNode m = content.global.skill.gather.mining.MiningNode.forId(s.getId());
                if (m != null) { note("rock:" + MarketBrain.name(m.getReward()).toLowerCase(Locale.ROOT), s.getLocation()); continue; }
            } catch (Throwable ignored) { }
            if (n.equals("tree") || n.endsWith(" tree") || n.equals("oak") || n.equals("willow") || n.equals("yew") || n.equals("evergreen") || n.equals("dead tree")) { note("tree:" + n, s.getLocation()); continue; }
            if (n.contains("bank booth") || n.contains("bank chest")) { note("place:bank", s.getLocation()); continue; }
            if (n.equals("furnace") || n.equals("anvil") || n.equals("range") || n.equals("cooking range") || n.equals("altar") || n.equals("spinning wheel") || n.equals("pottery oven") || n.equals("potter's wheel") || n.equals("sawmill"))
                note("place:" + n, s.getLocation());
            if (n.equals("flax") || n.equals("wheat") || n.equals("cabbage") || n.equals("potato") || n.equals("onion")) note("pick:" + n, s.getLocation());
        }
        for (NPC npc : RegionManager.getLocalNpcs(bot, 10)) {
            if (npc == null || !npc.isActive() || npc.getName() == null) continue;
            String n = npc.getName();
            if (n.equalsIgnoreCase("Fishing spot")) {
                for (String o : npc.getDefinition().getOptions()) if (o != null && !o.equalsIgnoreCase("examine")) note("fish:" + o.toLowerCase(Locale.ROOT), npc.getLocation());
                continue;
            }
            if (npc.getDefinition().hasAction("attack")) note("npc:" + n.toLowerCase(Locale.ROOT) + " (level " + npc.getDefinition().getCombatLevel() + ")", npc.getLocation());
            else if (npc.getDefinition().hasAction("pickpocket")) note("pickpocket:" + n.toLowerCase(Locale.ROOT), npc.getLocation());
            else if (npc.getDefinition().hasAction("trade")) note("shopkeeper:" + n.toLowerCase(Locale.ROOT), npc.getLocation());
        }
    }

    /** The nearest busy cluster of something (e.g. "rock:coal", "fish:cage", key prefix "npc:chaos druid"), or null. */
    static Location nearest(String keyPrefix, Location from) {
        load();
        Location best = null; double bd = Double.MAX_VALUE;
        for (Map.Entry<String, Map<Integer, Integer>> e : SEEN.entrySet()) {
            if (!e.getKey().startsWith(keyPrefix)) continue;
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) {
                if (c.getValue() < 2) continue; // seen at least twice: a real spot, not a wanderer
                int[] b = RouteMap.BEST.get(c.getKey());
                Location l = b != null ? Location.create(b[0], b[1], 0) : Location.create(RouteMap.cx(c.getKey()) * RouteMap.CELL + 4, RouteMap.cy(c.getKey()) * RouteMap.CELL + 4, 0);
                double d = l.getDistance(from);
                if (d < bd) { bd = d; best = l; }
            }
        }
        return best;
    }

    /** Like nearest, but the key must start with {\code kind} ("rock:") and contain {\code word} ("coal"). */
    static Location nearestMatching(String kind, String word, Location from) {
        load();
        if (word == null) return null;
        word = word.trim().toLowerCase(Locale.ROOT);
        if (word.length() < 2) return null;
        Location best = null; double bd = Double.MAX_VALUE;
        for (Map.Entry<String, Map<Integer, Integer>> e : SEEN.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(kind) || !k.substring(kind.length()).contains(word)) continue;
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) {
                if (c.getValue() < 2) continue;
                int[] b = RouteMap.BEST.get(c.getKey());
                Location l = b != null ? Location.create(b[0], b[1], 0) : Location.create(RouteMap.cx(c.getKey()) * RouteMap.CELL + 4, RouteMap.cy(c.getKey()) * RouteMap.CELL + 4, 0);
                double d = l.getDistance(from);
                if (d < bd) { bd = d; best = l; }
            }
        }
        return best;
    }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file(); if (!f.exists()) return;
            JSONObject root = (JSONObject) new JSONParser().parse(new FileReader(f));
            for (Object k : root.keySet()) {
                Map<Integer, Integer> m = new ConcurrentHashMap<>();
                JSONObject cells = (JSONObject) root.get(k);
                for (Object c : cells.keySet()) m.put(Integer.parseInt(String.valueOf(c)), ((Number) cells.get(c)).intValue());
                SEEN.put(String.valueOf(k), m);
            }
            System.out.println("[Resources] " + SEEN.size() + " kinds of resource remembered");
        } catch (Throwable t) { System.out.println("[Resources] could not read: " + t); Ledger.quarantine(file(), "Resources", t, false, true); } // audit 10-23 batch14 item1
    }

    @SuppressWarnings("unchecked")
    static synchronized void save() {
        if (SEEN.isEmpty()) return;
        JSONObject root = new JSONObject();
        for (Map.Entry<String, Map<Integer, Integer>> e : SEEN.entrySet()) {
            JSONObject cells = new JSONObject();
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) cells.put(String.valueOf(c.getKey()), c.getValue());
            root.put(e.getKey(), cells);
        }
        try { Ledger.writeAtomic(file(), root.toJSONString()); } catch (Throwable t) { System.out.println("[Resources] could not save: " + t); }
    }
}
