package content.custom;

import core.ServerConstants;
import core.cache.def.impl.NPCDefinition;
import core.game.ge.PriceIndex;
import core.game.world.map.Location;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where to hunt for what: built once from the server's own drop_tables.json and npc_spawns.json.
 * Each hunt is one monster family (all ids that share a drop table), its best spawn cluster on the
 * surface, the tradeable drops with their rough chance per kill, and the combat level it needs.
 */
public final class HuntMap {
    static final class Hunt {
        String name;            // NPC name, e.g. "Hill Giant"
        int npcLevel;           // its combat level
        String[] names;         // every name in the family (targets for the runner)
        Location spot;          // the densest spawn cluster
        int spawnsAtSpot;
        int[] products;         // tradeable drop ids
        double[] chance;        // chance per kill, capped at 1
    }

    static final List<Hunt> HUNTS = new ArrayList<>();
    /** Dungeon monsters the high tier hunts (E27); the spawn scan only covers the surface, so these spots are given. */
    static final Map<String, Location> CURATED = new HashMap<>();
    static {
        CURATED.put("Blue dragon", Location.create(2904, 9803, 0));   // Taverley dungeon
        CURATED.put("Black demon", Location.create(2866, 9780, 0));   // Taverley dungeon
        CURATED.put("Lesser demon", Location.create(2840, 9565, 0));  // Karamja volcano
        CURATED.put("Fire giant", Location.create(2570, 9890, 0));    // Waterfall dungeon
        CURATED.put("Hill Giant", Location.create(3115, 9850, 0));    // Edgeville dungeon
    }
    /** Drops that come from quests, random events or are otherwise not a real supply. */
    static final Set<Integer> DENY = new HashSet<>();
    static {
        for (int id : new int[]{995, 12158, 12159, 12160, 12163, 0, 1, 2}) DENY.add(id); // coins, charms, placeholder ids
    }

    static Location parseFirst(String locData, List<Location> out) {
        // "{3118,9845,0,1,4}-{3111,9844,0,1,6}-..."
        int i = 0;
        while ((i = locData.indexOf('{', i)) >= 0) {
            int j = locData.indexOf('}', i);
            if (j < 0) break;
            String[] parts = locData.substring(i + 1, j).split(",");
            try {
                int x = Integer.parseInt(parts[0].trim()), y = Integer.parseInt(parts[1].trim()), z = Integer.parseInt(parts[2].trim());
                boolean wilderness = y >= 3520 && y < 4000 && x >= 2944 && x < 3392; // bots don't hunt past the ditch
                if (z == 0 && !wilderness && y < 4200) out.add(Location.create(x, y, 0));
            } catch (Exception ignored) { }
            i = j + 1;
        }
        return out.isEmpty() ? null : out.get(0);
    }

    static void build() {
        String cfg = ServerConstants.Companion.getCONFIG_PATH();
        if (cfg == null) { System.out.println("[HuntMap] no config path"); return; }
        try {
            JSONParser parser = new JSONParser();
            JSONArray spawnsJson = (JSONArray) parser.parse(new FileReader(new File(cfg, "npc_spawns.json")));
            Map<Integer, List<Location>> spawns = new HashMap<>();
            for (Object o : spawnsJson) {
                JSONObject j = (JSONObject) o;
                int id = Integer.parseInt(String.valueOf(j.get("npc_id")).trim());
                List<Location> list = new ArrayList<>();
                parseFirst(String.valueOf(j.get("loc_data")), list);
                if (!list.isEmpty()) spawns.put(id, list);
            }
            JSONArray tables = (JSONArray) parser.parse(new FileReader(new File(cfg, "drop_tables.json")));
            for (Object o : tables) {
                JSONObject t = (JSONObject) o;
                String ids = String.valueOf(t.get("ids"));
                if (ids.isEmpty()) continue;
                List<Integer> family = new ArrayList<>();
                for (String s : ids.split(",")) { try { family.add(Integer.parseInt(s.trim())); } catch (Exception ignored) { } }
                // Spawn locations of the whole family, and the densest cluster.
                List<Location> all = new ArrayList<>();
                List<Integer> allIds = new ArrayList<>(); // combat review 10-23 #4: which family member spawns at each of those
                for (int id : family) { List<Location> l = spawns.get(id); if (l != null) for (Location x : l) if (!Watchdog.trap(x)) { all.add(x); allIds.add(id); } } // no zoo cages or remembered traps
                NPCDefinition d0 = NPCDefinition.forId(family.get(0));
                Location curated = d0 != null && d0.getName() != null ? CURATED.get(d0.getName()) : null;
                if (all.size() < 3 && curated == null) continue;
                Location best = curated; int bestCount = curated != null ? 4 : 0;
                if (curated == null) for (Location c : all) {
                    int n = 0;
                    for (Location d : all) if (d.getDistance(c) <= 15) n++;
                    if (n > bestCount) { bestCount = n; best = c; }
                }
                if (best == null) continue;
                // Drops: "default" (always) + "main" (weighted); chance = weight / total main weight.
                List<int[]> prods = new ArrayList<>();
                List<Double> chances = new ArrayList<>();
                JSONArray def = (JSONArray) t.get("default"), main = (JSONArray) t.get("main");
                if (def != null) for (Object d : def) {
                    int id = Integer.parseInt(String.valueOf(((JSONObject) d).get("id")).trim());
                    if (!DENY.contains(id) && tradeable(id)) { prods.add(new int[]{id}); chances.add(1.0); }
                }
                if (main != null) {
                    double total = 0;
                    for (Object d : main) total += Double.parseDouble(String.valueOf(((JSONObject) d).get("weight")));
                    if (total > 0) for (Object d : main) {
                        JSONObject dj = (JSONObject) d;
                        int id = Integer.parseInt(String.valueOf(dj.get("id")).trim());
                        double ch = Double.parseDouble(String.valueOf(dj.get("weight"))) / total;
                        if (DENY.contains(id) || !tradeable(id) || ch < 0.01) continue;
                        prods.add(new int[]{id}); chances.add(Math.min(1.0, ch));
                    }
                }
                if (prods.isEmpty()) continue;
                if (d0 == null || d0.getName() == null || !d0.hasAction("attack")) continue;
                Hunt h = new Hunt();
                h.name = d0.getName();
                h.npcLevel = d0.getCombatLevel();
                Set<String> names = new HashSet<>();
                // combat review 10-23 #4: the level (and the targets) come from the members that spawn at the chosen spot, not the
                // family's first id: the Skeleton Mage cluster is id 3851, the level was id 94's. Curated dungeon spots keep the old rule.
                Set<Integer> atSpot = new HashSet<>();
                if (curated == null) for (int k = 0; k < all.size(); k++) if (all.get(k).getDistance(best) <= 15) atSpot.add(allIds.get(k));
                int spotLevel = -1; String strongest = null;
                for (int id : atSpot) { NPCDefinition d = NPCDefinition.forId(id); if (d != null && d.getName() != null && d.hasAction("attack")) { names.add(d.getName()); if (d.getCombatLevel() > spotLevel) { spotLevel = d.getCombatLevel(); strongest = d.getName(); } } }
                if (spotLevel > 0) { h.npcLevel = spotLevel; if (!names.contains(h.name)) h.name = strongest; } // the danger rules (mage, poison, dragon) read the name
                else names.clear();
                if (names.isEmpty()) for (int id : family) { NPCDefinition d = NPCDefinition.forId(id); if (d != null && d.getName() != null) names.add(d.getName()); }
                h.names = names.toArray(new String[0]);
                h.spot = best; h.spawnsAtSpot = bestCount;
                h.products = new int[prods.size()]; h.chance = new double[prods.size()];
                for (int k = 0; k < prods.size(); k++) { h.products[k] = prods.get(k)[0]; h.chance[k] = chances.get(k); }
                HUNTS.add(h);
            }
            System.out.println("[HuntMap] " + HUNTS.size() + " monster families with surface spawns and tradeable drops");
        } catch (Throwable t) {
            System.out.println("[HuntMap] build failed: " + t);
        }
    }

    static boolean tradeable(int id) {
        return MarketBrain.tradeable(id);
    }
}
