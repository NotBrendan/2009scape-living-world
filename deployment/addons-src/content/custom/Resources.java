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

    /** P43: -Dresources.skipPockets=true (default off) drops remembered spots inside the two mountain pockets that surveyors were teleported into (the design notes). */
    static final boolean SKIP_POCKETS = Boolean.getBoolean("resources.skipPockets");
    /** Pocket boxes x0,y0,x1,y1: A = cliff pocket 2811,3506; B = Death Plateau pocket 2923,3612 (climbing boots only). */
    static final int[][] POCKETS = {{2800, 3500, 2828, 3518}, {2880, 3598, 2924, 3643}, {2810, 3228, 2868, 3314}}; // p60: Crandor (skeleton spot 2840,3240, no route)
    static boolean inPocket(int x, int y) {
        for (int[] b : POCKETS) if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) return true;
        return false;
    }
    /** True when the flag is on and l lies in a pocket box: callers skip the spot. */
    static boolean skipSpot(Location l) { return SKIP_POCKETS && l != null && l.getZ() == 0 && inPocket(l.getX(), l.getY()); }

    /** P49: -Dhunt.skipUnreachable=true (default off) drops hunt goals no walk reaches: a pocket box (ice giants 2811,3506: 31 Watchdog
     *  rescues in 16 h at 2838,3549 / 3007,3514) and the swamp south of the mort-myre gate box (blamish snails 3421-3437 x 3299-3314: 53
     *  at the gate, 3442/3433,3458) for a bot that has not started Nature Spirit. Flood fill on nav-p0 with the gate and the box shut: no
     *  tile of MYRE_SOUTH is reached from Canifis. Counts go to the log every 50 skips ([HuntSkip]). */
    static final boolean SKIP_UNREACHABLE_HUNTS = Boolean.getBoolean("hunt.skipUnreachable");
    static final int[] MYRE_SOUTH = {3400, 3260, 3520, 3319};
    static boolean inMyreSouth(int x, int y) { return x >= MYRE_SOUTH[0] && x <= MYRE_SOUTH[2] && y >= MYRE_SOUTH[1] && y <= MYRE_SOUTH[3]; }
    /** Pure test: "pocket", "myre" or null. */
    static String huntBlock(Location spot, boolean natureSpirit) {
        if (spot == null || spot.getZ() != 0) return null;
        if (inPocket(spot.getX(), spot.getY())) return "pocket";
        if (!natureSpirit && inMyreSouth(spot.getX(), spot.getY())) return "myre";
        return null;
    }
    static final java.util.concurrent.atomic.AtomicInteger HUNT_SKIP_POCKET = new java.util.concurrent.atomic.AtomicInteger(), HUNT_SKIP_MYRE = new java.util.concurrent.atomic.AtomicInteger();
    /** True when the flag is on and this bot can't walk to the hunt spot: callers skip the job. */
    static boolean skipHunt(core.game.node.entity.player.Player bot, Location spot) {
        if (!SKIP_UNREACHABLE_HUNTS || spot == null || spot.getZ() != 0) return false;
        if (P84.ON && P84.inPocket(spot.getX(), spot.getY())) { P84.count("hunt-skip", P84.SKIP, (bot == null ? "?" : bot.getUsername()) + " at " + spot.getX() + "," + spot.getY()); return true; } // P84 fix 2: Karamja's jogre/monkey pocket
        boolean ns = false;
        if (inMyreSouth(spot.getX(), spot.getY())) try { ns = bot.getQuestRepository().hasStarted(content.data.Quests.NATURE_SPIRIT); } catch (Throwable t) { ns = false; }
        String why = huntBlock(spot, ns);
        if (why == null) return false;
        int n = ("pocket".equals(why) ? HUNT_SKIP_POCKET : HUNT_SKIP_MYRE).incrementAndGet();
        if (n % 50 == 1) System.out.println("[HuntSkip] " + why + " " + n + " (" + (bot == null ? "?" : bot.getUsername()) + " at " + spot.getX() + "," + spot.getY() + "), pocket " + HUNT_SKIP_POCKET.get() + " myre " + HUNT_SKIP_MYRE.get());
        return true;
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
                if (skipSpot(l)) continue;
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
                if (skipSpot(l)) continue;
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
