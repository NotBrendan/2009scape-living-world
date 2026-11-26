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
        String tag;             // slayer8: null, or the place name of an extra spot of the same family (its own label, crowd count and blacklist)
    }
    /** The job label of a hunt ("Hill Giants"); an extra spot (slayer8) carries its place: "Grizzly bears (Ardougne east)". */
    /** W8 (-Dbots.weaponByFoe): the weakness column, W8Polish.CRUSH or SLASH by the hunt's npc name (read only with the flag on). */
    static int weakness(Hunt h) { return h == null ? W8Polish.SLASH : W8Polish.weakness(h.name); }
    static String label(Hunt h) { return h.tag == null ? h.name + "s" : h.name + "s (" + h.tag + ")"; }

    static final List<Hunt> HUNTS = new ArrayList<>();
    /** Dungeon monsters the high tier hunts (E27); the spawn scan only covers the surface, so these spots are given. */
    static final Map<String, Location> CURATED = new HashMap<>();
    static {
        CURATED.put("Blue dragon", Location.create(2904, 9803, 0));   // Taverley dungeon
        CURATED.put("Black demon", Location.create(2866, 9780, 0));   // Taverley dungeon
        CURATED.put("Lesser demon", Location.create(2840, 9565, 0));  // Karamja volcano
        CURATED.put("Fire giant", Location.create(2570, 9890, 0));    // Waterfall dungeon
        CURATED.put("Hill Giant", Location.create(3115, 9850, 0));    // Edgeville dungeon
        if (W6Herbs.SUPPLY) CURATED.put(W6Herbs.DRUID_NAME, Location.create(W6Herbs.DRUID[0], W6Herbs.DRUID[1], 0)); // W6 (-Dartisans.herbSupply): Taverley dungeon; the 4 surface druids are in the locked tower
    }
    /** Drops that come from quests, random events or are otherwise not a real supply. */
    static final Set<Integer> DENY = new HashSet<>();
    static {
        for (int id : new int[]{995, 12158, 12159, 12160, 12163, 0, 1, 2}) DENY.add(id); // coins, charms, placeholder ids
    }
    /** P93 (-Dnav.p93Fix=true, default off): hunts whose densest spawn cluster sits in a walled pocket the planner cannot walk into
     *  or out of (offline p38-navport BFS, seed nav-p0.bin 11-16) are not built. Zogre 2468,3046 (Jiggig burial ground): a 246-tile
     *  pocket, cut off from the mainland and from 2451,3048; no zogre surface spawn is reachable; live train-44: Dunjins295 and a
     *  second bot stood 5 min at 2451,3048 "path loop", paid teleport. Giant wasp 2891,3002 (south Karamja): a 41-tile pocket, no
     *  wasp spawn reachable from the boat landing 2956,3146; train-43: "won't walk to Giant wasps from [2956,3146] (rescued, loop)",
     *  stuck 2883,3139, and a bot put inside "won't walk to Gold from [2891,3002] (rescued, loop)". Name -> the pocket spot. */
    static final boolean P93_FIX = Boolean.getBoolean("nav.p93Fix");
    static final Map<String, Location> P93_POCKETS = new HashMap<>();
    static {
        P93_POCKETS.put("Zogre", Location.create(2468, 3046, 0));
        P93_POCKETS.put("Giant wasp", Location.create(2891, 3002, 0));
    }
    static volatile int p93Dropped;
    /** P93: true when the hunt named name with its spot at spot is one of the walled pockets (within 20 tiles of the recorded spot). */
    static boolean p93Pocket(String name, Location spot) {
        Location l = name == null || spot == null ? null : P93_POCKETS.get(name);
        return l != null && spot.getZ() == 0 && l.getDistance(spot) <= 20;
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
                W6Slayer.Spot w6 = W6Slayer.ON && d0 != null ? W6Slayer.spotFor(true, d0.getName(), curated != null, all.size(), W6Slayer.keyWalkOk()) : null; // W6 (-Dbots.slayer.vannakaHunts): a Vannaka task's hunt
                if (w6 != null && w6.at != null) curated = w6.at;
                if (all.size() < 3 && curated == null) continue;
                int hill7 = 0; // slayer7 (-Dbots.slayer.fix7): Hill Giants hunt at the surface spawns north of the Observatory, not behind the brass key door
                if (Slayer.FIX7 && curated != null) { for (Location d : all) if (d.getDistance(Slayer.HILL7) <= 15) hill7++; Location c7 = Slayer.hillSpot(true, d0.getName(), curated, hill7); if (c7 == curated) hill7 = 0; else { System.out.println("[HuntMap] slayer7: " + d0.getName() + " hunt at " + c7 + " (" + hill7 + " surface spawns), was " + curated); curated = c7; } }
                Location best = curated; int bestCount = curated != null ? Math.max(4, hill7) : 0;
                if (curated == null) for (Location c : all) {
                    int n = 0;
                    for (Location d : all) if (d.getDistance(c) <= 15) n++;
                    if (n > bestCount) { bestCount = n; best = c; }
                }
                if (best == null) continue;
                if (Slayer.FIX8 && curated == null && d0 != null && Slayer.BEAR8_NAME.equals(d0.getName())) { // slayer8: the Black bear spot moves 14 tiles so 3 of the 4 bears by Goblin Village are in range, not 2
                    int n8 = 0; for (Location d : all) if (d.getDistance(Slayer.BEAR8) <= 15) n8++;
                    Location s8 = Slayer.spot8(true, d0.getName(), best, bestCount, n8);
                    if (s8 != best) { System.out.println("[HuntMap] slayer8: " + d0.getName() + " hunt at " + s8 + " (" + n8 + " spawns), was " + best + " (" + bestCount + ")"); best = s8; bestCount = n8; }
                }
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
                if (d0 == null || d0.getName() == null || !d0.hasAction("attack") && w6 == null) continue; // W6: the Vampire family's first id (1023) has no Attack; the spot's members do
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
                if (P93_FIX && (p93Pocket(h.name, best) || p93Pocket(d0.getName(), best))) { p93Dropped++; System.out.println("[P93] hunt " + h.name + " at " + best + " dropped: walled pocket, no walking route"); continue; }
                if (W6Slayer.ICE_FIX && (W6Slayer.icePocket(h.name, best) || W6Slayer.icePocket(d0.getName(), best))) { W6Slayer.iceDropped.incrementAndGet(); System.out.println("[W6] hunt " + h.name + " at " + best + " dropped: White Wolf Mountain walled pocket (274 tiles), no walking route"); continue; } // W6 (-Dbots.slayer.iceGiantFix)
                h.spot = best; h.spawnsAtSpot = bestCount;
                if (w6 != null) W6Slayer.built(h, w6);
                h.products = new int[prods.size()]; h.chance = new double[prods.size()];
                for (int k = 0; k < prods.size(); k++) { h.products[k] = prods.get(k)[0]; h.chance[k] = chances.get(k); }
                HUNTS.add(h);
                if (Slayer.FIX8 && curated == null) for (Slayer.Alt8 a : Slayer.ALT8) { // slayer8: extra spots for a task's holders, each checked against the spawn file here
                    if (!a.name.equals(d0.getName())) continue;
                    int n8 = 0; boolean same = true;
                    for (int k = 0; k < all.size(); k++) if (all.get(k).getDistance(a.at) <= 15) { n8++; if (!atSpot.contains(allIds.get(k))) same = false; }
                    if (!Slayer.altOk8(true, n8, same, a.at.getDistance(best))) { System.out.println("[HuntMap] slayer8: no extra " + h.name + " spot at " + a.at + " (" + a.tag + "): spawns " + n8 + ", same monsters " + same + ", from the main spot " + (int) a.at.getDistance(best)); continue; }
                    Hunt x = new Hunt();
                    x.name = h.name; x.npcLevel = h.npcLevel; x.names = h.names; x.products = h.products; x.chance = h.chance;
                    x.spot = a.at; x.spawnsAtSpot = n8; x.tag = a.tag;
                    HUNTS.add(x);
                    System.out.println("[HuntMap] slayer8: extra spot " + label(x) + " at " + a.at + " (" + n8 + " spawns)");
                }
            }
            System.out.println("[HuntMap] " + HUNTS.size() + " monster families with surface spawns and tradeable drops");
        } catch (Throwable t) {
            System.out.println("[HuntMap] build failed: " + t);
        }
    }

    static boolean tradeable(int id) {
        return MarketBrain.tradeable(id);
    }

    /** P91: highest Slayer levelReq of any Slayer task whose NPC name matches one of the hunt's names (0 = none). Cached per hunt. */
    static final Map<Hunt, Integer> SLAYER_NEED = new java.util.concurrent.ConcurrentHashMap<>();
    static int slayerNeed(Hunt h) {
        if (h == null || h.names == null) return 0;
        Integer c = SLAYER_NEED.get(h);
        if (c != null) return c;
        int need = 0;
        try {
            for (content.global.skill.slayer.Tasks t : content.global.skill.slayer.Tasks.values()) {
                if (t.levelReq <= need || t.ids == null) continue;
                outer:
                for (int id : t.ids) {
                    NPCDefinition d = NPCDefinition.forId(id);
                    if (d == null || d.getName() == null) continue;
                    for (String nm : h.names) if (nm != null && nm.equalsIgnoreCase(d.getName())) { need = Math.max(need, t.levelReq); break outer; }
                }
            }
        } catch (Throwable t) { return 0; }
        SLAYER_NEED.put(h, need);
        return need;
    }
}
