package content.custom;

import core.ServerConstants;
import core.game.bots.AIPlayer;
import core.game.bots.GeneralBotCreator;
import core.game.bots.Script;
import core.game.bots.SkillingBotAssembler;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.Region;
import core.game.world.map.RegionManager;
import core.game.world.repository.Repository;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * batch 18 item2: off-ground explorers (2026-10-25: "explore everything not reachable from the plane-0 surface";
 * replaces the stair explorers). Flag -Dsurvey.offground (default 6, batch 18 rc3; was 19); Explorers.startup calls start().
 * batch 18 rc3 (danger): explorers are combat ~80 melee in poor kit. Before climbing, a connector whose landing is known (stairs.jsonl
 * "to" / offgrid.jsonl "landing"; else estimated: y + 6400 below ground, the next plane up or down, -Doffground.estimate=false
 * to turn that off) is skipped when the engine's NPCs within 20 tiles of it (16 for an estimate) include an attackable one above
 * the bot's combat level. After landing, aggressive NPCs above its level inside the flood fill (or within 8 tiles): it teleports
 * straight back. Both write an offgrid.jsonl line with "dangerFor": level and "danger":{...}; that key is then not retried by an
 * explorer of that level or lower (a stronger one may). -Doffground.precheck=false (trials) skips the before-climbing check.
 *
 * The list (built once the nav grid's components are up, refreshed every 30 minutes):
 *  - connectors: every plane-0 object with a Climb-up / Climb-down / Climb / Climb-into / Enter / Go-down / Go-up option (or a
 *    closed trapdoor/manhole/hatch to Open) in the regions the surveyors walked plus the regions of every destination the bots
 *    ask for. Their far side is off the plane-0 surface (another floor, or underground at y > 6400).
 *  - demand: destinations from the code (static Location / Spot / shop fields of AfkTasks, HuntMap, BotChat.WorldData shops,
 *    Quest* step tiles, SmartAdventurers), and the known stuck cell 3096,9864. A destination is "outside the surface grid" by
 *    the same test NavWalker.step counts ("outside": z != 0 or not NavGrid.inBounds), or when its stand tile is not in the main
 *    surface component (NavPlanner components, flood from Lumbridge). Connectors are ranked by the outside destinations near
 *    the land above them.
 *  - covered: connectors already recorded in stairs.jsonl with a landing (QuestFloors' known climbs: result up/down) and keys
 *    already in offgrid.jsonl are subtracted.
 *  - surface problem tasks (never blanket surface surveying): the top stuck cells (a fixed list plus Watchdog.TRAPS cells
 *    rescued twice or more), outside-grid surface destinations (islands), and doors/gates that stayed shut for bots
 *    (Gates.SHUT, the Al Kharid toll gate). Each names its issue and drops off once proven either way. F's scenario replays
 *    (Scenarios.java) cover trips and destinations (never-reached shops, no-route); these cover places, doors and gates.
 * Explorers take the top unvisited candidate (a connector found on the floor they just reached first), teleport next to it
 * (Spectacle.teleport), use it, and record the landing and a flood fill of what is reachable from it. Never the Wilderness
 * (either side), never instanced boss areas, never a toll/pay option, never with a real player within 6 tiles of it. A bot
 * whose round finds nothing left retires; a refresh that finds a new hot spot with no explorer left sends one out.
 *
 * data/survey/offgrid.jsonl, one JSON object per line (phase 5 "floors and dungeons as links" reads it):
 *   {"v":1,"t":ms,"bot":name,"key":"c:x,y,z#id" | "s:<issue kind>:x,y,z","kind":"connector"|"surface","issue":text,
 *    "entrance":{"id":n,"name":s,"option":s,"at":[x,y,z]} (connector; for a door task the door) or null,
 *    "from":[x,y,z],"landing":[x,y,z] or null,"plane":z,"under":bool,"success":bool,"result":text,"demand":score,
 *    "fill":{"tiles":n,"box":[x0,y0,x1,y1],"capped":bool,"mainSurface":bool,"doors":n,
 *            "connectors":[{"id":n,"name":s,"option":s,"at":[x,y,z]}...]},
 *    batch 18 rc3, only on a dangerous place: "dangerFor":level,"danger":{"when":"before climbing"|"after landing","basis":s,
 *            "near":[x,y,z],"npc":name,"npcLevel":n,"count":n,"aggressiveOnly":bool}}
 * A connector visit also writes the usual stairs.jsonl line (same fields as the stair explorers) so QuestFloors keeps working.
 */
final class OffGround {
    private OffGround() { }

    static final boolean FAST = Boolean.getBoolean("traders.fast");
    static final String[] CLIMB = {"Climb-up", "Climb-down", "Climb", "Climb-into", "Enter", "Go-down", "Go-up"};
    static final Pattern CONNECTOR = Pattern.compile(".*\\b(ladder|stair|stairs|staircase|trapdoor|trap door|manhole|hatch|cave|cave entrance|entrance|dungeon|hole|cellar|passage|crevice|steps|tunnel|mine|crack|rope)\\b.*");
    static final Pattern NEVER = Pattern.compile(".*\\b(portal|barrier|ditch|door|gate|tent|boat|ship|gangplank|cart|coffin|bed)\\b.*");
    /** Instanced or boss areas (surface box of the entrance): God Wars, the Kalphite lair, the Barrows mounds, the TzHaar caves, Waterbirth. */
    static final int[][] BOSS = {{2860, 3700, 2940, 3770}, {3220, 3100, 3240, 3120}, {3545, 3265, 3585, 3310}, {2470, 5100, 2560, 5180}, {2510, 3730, 2560, 3770}};
    static final int[][] STUCK = {{3264, 3224}, {2872, 3544}, {3448, 3456}, {3416, 3544}, {2608, 3392}, {2832, 3536}}; // the owner 10-25: Al Kharid toll, Burthorpe, Canifis, ...
    static final int[] TOLL = {3268, 3227}; // the Al Kharid toll gate
    static final Location HOME = Location.create(3222, 3218, 0);

    static final class Cand {
        String key, kind, issue, name, option, via; int x, y, z, id; double demand; boolean covered, done; String taken; long deferUntil; int count;
        int dangerFor = -1; // batch 18 rc3: dangerous for explorers of this combat level or lower (-1: not known dangerous)
        Location at() { return Location.create(x, y, z); }
        public String toString() { return key + " " + (name != null ? name + " (" + option + ")" : issue) + " demand " + String.format(Locale.ROOT, "%.1f", demand) + (covered ? " [covered]" : done ? " [done]" : ""); }
    }

    static final List<Cand> CANDS = new ArrayList<>();
    static final Map<String, Cand> BY_KEY = new HashMap<>();
    static final Set<String> COVERED = ConcurrentHashMap.newKeySet();    // "x,y,z#id" climbs recorded with a landing
    static final Map<String, Integer> DONE = new ConcurrentHashMap<>();  // offgrid.jsonl keys -> count at proof (stuck cells come back when rescues climb by 2)
    static final Map<String, Integer> DANGER = new ConcurrentHashMap<>(); // batch 18 rc3: offgrid.jsonl keys -> the highest explorer level they were dangerous for
    static final Map<String, Location> LANDING = new ConcurrentHashMap<>(); // batch 18 rc3: "x,y,z#id" -> recorded landing (stairs.jsonl "to", offgrid.jsonl "landing")
    static volatile boolean PRECHECK = !"false".equalsIgnoreCase(System.getProperty("offground.precheck", "true")); // batch 18 rc3
    static final boolean ESTIMATE = !"false".equalsIgnoreCase(System.getProperty("offground.estimate", "true")); // batch 18 rc3
    static final List<Object[]> DEMAND = new ArrayList<>();              // {Location, tag, weight, outside?}
    static volatile boolean ready;
    static boolean[] main;
    static final AtomicInteger LIVE = new AtomicInteger(), SPAWNED = new AtomicInteger(), VISITS = new AtomicInteger();
    static int outsideDemand, unlinked;

    static String ckey(int x, int y, int z, int id) { return "c:" + x + "," + y + "," + z + "#" + id; }
    static boolean wild(int x, int y) { return Heat.wildLevel(x, y) > 0 || (y > 6400 && Heat.wildLevel(x, y - 6400) > 0); }
    static boolean boss(int x, int y) { for (int[] b : BOSS) if (x >= b[0] && x <= b[2] && ((y >= b[1] && y <= b[3]) || (y > 6400 && y - 6400 >= b[1] && y - 6400 <= b[3]))) return true; return false; }

    // ------------------------------------------------------------------ start, seed, list

    static volatile int[] SEED_LINES; static volatile int SEED_KNOWN; // batch 19b: the seed files are read in start() (startup thread), not in the game-thread prepare pulse

    static void start(int count) {
        new File(ServerConstants.DATA_PATH, "survey").mkdirs();
        try { int known = 0; try { known = QuestFloors.known().size(); } catch (Throwable ignored) { } SEED_KNOWN = known; readCovered(); readDone(); SEED_LINES = new int[]{lines("stairs.jsonl"), lines("doors.jsonl"), lines("shortcuts.jsonl"), lines("offgrid.jsonl")}; } catch (Throwable t) { System.out.println("[OffGround] seed: " + t); } // batch 19b: startup readers run before any write (design 11)
        GameWorld.getPulser().submit(new Pulse(10) { // wait for the nav graph (built off-thread after the grid loads), at most ~5 minutes
            int waited, stage, tries; // batch 19b: prepare in 3 stages, one per pulse (it was one ~60 ms pulse)
            @Override public boolean pulse() {
                if (!NavPlanner.built && ++waited < 50) return false;
                try { boolean done = prepare(stage); if (stage < 2 || ++tries > 30) stage++; return done; } catch (Throwable t) { System.out.println("[OffGround] prepare failed: " + t); t.printStackTrace(); }
                return true;
            }
        });
        for (int i = 0; i < count; i++) schedule(i, FAST ? 45_000 + i * 3_000L : 330_000 + i * 20_000L);
        Explorers.TIMER.schedule(new java.util.TimerTask() { @Override public void run() { GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { try { refresh(true); } catch (Throwable t) { System.out.println("[OffGround] refresh: " + t); } return true; } }); } },
                30 * 60_000L, 30 * 60_000L); // the owner 10-25: issues found quickly: the problem list is re-read every 30 minutes
    }

    static void schedule(int idx, long delay) {
        Explorers.TIMER.schedule(new java.util.TimerTask() {
            @Override public void run() {
                GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { try { spawn(idx); } catch (Throwable t) { System.out.println("[OffGround] spawn failed: " + t); try { Failures.Life.loginFailed("offground", null, t); } catch (Throwable ignored) { } } return true; } });
            }
        }, delay);
    }

    static int lines(String f) {
        File file = new File(new File(ServerConstants.DATA_PATH, "survey"), f);
        if (!file.exists()) return 0;
        int n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) { while (r.readLine() != null) n++; } catch (Throwable ignored) { }
        return n;
    }
    static long kb(String f) { return new File(new File(ServerConstants.DATA_PATH, "survey"), f).length() / 1024; }

    /** Game thread: seed, demand, main component, then the region scan (two regions a tick), then the list. */
    static long prepT0; static final long[] PREP_MS = new long[3];

    /** batch 19b: stage 0 seed line + main component, 1 demand, 2 regions + the scan pulse; true when done (one stage per pulse, timings in the "list ready" line). */
    static boolean prepare(int stage) {
        long a = System.nanoTime();
        try {
            if (stage == 0) { prepT0 = System.currentTimeMillis(); prepareSeed(); Thread th = new Thread(() -> { try { TILE_REGIONS = tileRegions(); } catch (Throwable t) { TILE_REGIONS = new TreeSet<>(); } }, "OffGround-regions"); th.setDaemon(true); th.start(); mainComponent(); return false; } // batch 19b: the tile census walk (~70k keys) off the game thread
            if (stage == 1) { demand(); return false; }
            if (stage == 2 && TILE_REGIONS == null) return false; // wait for it (at most 30 pulses, then inline)
            prepareScan(prepT0); return true;
        } finally { if (stage >= 0 && stage < 3) PREP_MS[stage] = (System.nanoTime() - a) / 1_000_000; }
    }

    static void prepareSeed() {
        int known = SEED_KNOWN; int[] sl = SEED_LINES; if (sl == null) { readCovered(); readDone(); sl = new int[]{lines("stairs.jsonl"), lines("doors.jsonl"), lines("shortcuts.jsonl"), lines("offgrid.jsonl")}; } // batch 19b: read in start(); here only if that failed
        System.out.println("[OffGround] seed: stairs.jsonl " + sl[0] + " records (" + known + " known climbs for the floor walker, " + COVERED.size() + " climbs with a landing), doors.jsonl " + sl[1]
                + ", shortcuts.jsonl " + sl[2] + ", tile census " + Surveyors.TILES.size() + " tiles, routemap.json " + kb("routemap.json") + " KB, heat.json " + kb("heat.json") + " KB, offgrid.jsonl " + sl[3] + " records at boot (" + DONE.size() + " places proven, " + DANGER.size() + " dangerous for some level)");
    }

    static volatile Set<Integer> TILE_REGIONS; // batch 19b: plane-0 surface regions of the tile census (Surveyors.TILES is a ConcurrentHashMap), built on a helper thread

    static Set<Integer> tileRegions() {
        Set<Integer> regions = new TreeSet<>();
        for (String k : Surveyors.TILES.keySet()) { try { String[] p = k.split(","); int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]); if (y > 6400 || !"0".equals(p[2])) continue; regions.add(((x >> 6) << 8) | (y >> 6)); } catch (Throwable ignored) { } }
        return regions;
    }

    static void prepareScan(final long t0) {
        Set<Integer> tr = TILE_REGIONS; Set<Integer> regions = new TreeSet<>(tr != null ? tr : tileRegions()); // batch 19b
        for (Object[] d : DEMAND) { Location l = (Location) d[0]; int y = l.getY() > 6400 ? l.getY() - 6400 : l.getY(); if (y < 6400 && y > 0) regions.add(((l.getX() >> 6) << 8) | (y >> 6)); }
        for (int[] s : Explorers.SITES) regions.add(((s[0] >> 6) << 8) | (s[1] >> 6));
        for (int[] s : STUCK) regions.add(((s[0] >> 6) << 8) | (s[1] >> 6));
        final ArrayDeque<Integer> todo = new ArrayDeque<>(regions);
        final int total = todo.size();
        final List<Cand> found = new ArrayList<>();
        System.out.println("[OffGround] scanning " + total + " surface regions for ladders, stairs, trapdoors and entrances");
        GameWorld.getPulser().submit(new Pulse(1) {
            @Override public boolean pulse() {
                for (int k = 0; k < 2 && !todo.isEmpty(); k++) { try { scanRegion(todo.poll(), found); } catch (Throwable t) { System.out.println("[OffGround] region: " + t); } }
                if (!todo.isEmpty()) return false;
                synchronized (CANDS) { for (Cand c : found) add(c); }
                rank();
                refresh(false);
                ready = true;
                report(total, System.currentTimeMillis() - t0);
                return true;
            }
        });
    }

    static void readCovered() {
        try { for (QuestFloors.Rec r : QuestFloors.known()) COVERED.add(r.x + "," + r.y + "," + r.z + "#" + r.id); } catch (Throwable t) { System.out.println("[OffGround] known climbs: " + t); }
        File f = new File(new File(ServerConstants.DATA_PATH, "survey"), "stairs.jsonl"); // batch 18 rc3: recorded landings, for the danger check before climbing
        if (f.exists()) try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            JSONParser p = new JSONParser();
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    JSONObject j = (JSONObject) p.parse(line); String res = String.valueOf(j.get("result"));
                    if (!res.startsWith("up") && !res.startsWith("down")) continue;
                    JSONArray at = (JSONArray) j.get("at"), to = (JSONArray) j.get("to");
                    if (to == null || to.size() < 3) continue;
                    LANDING.put(at.get(0) + "," + at.get(1) + "," + at.get(2) + "#" + j.get("id"), Location.create(((Number) to.get(0)).intValue(), ((Number) to.get(1)).intValue(), ((Number) to.get(2)).intValue()));
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) { System.out.println("[OffGround] stairs.jsonl landings: " + t); }
    }

    static void readDone() {
        File f = new File(new File(ServerConstants.DATA_PATH, "survey"), "offgrid.jsonl");
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            JSONParser p = new JSONParser();
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    JSONObject j = (JSONObject) p.parse(line); Object c = j.get("count"); String k = String.valueOf(j.get("key"));
                    JSONArray land = (JSONArray) j.get("landing");
                    if (land != null && land.size() >= 3 && k.startsWith("c:")) LANDING.put(k.substring(2), Location.create(((Number) land.get(0)).intValue(), ((Number) land.get(1)).intValue(), ((Number) land.get(2)).intValue())); // batch 18 rc3
                    Object dz = j.get("dangerFor");
                    if (dz instanceof Number) { DANGER.merge(k, ((Number) dz).intValue(), Math::max); continue; } // batch 18 rc3: dangerous for that level, not proven
                    DONE.put(k, c instanceof Number ? ((Number) c).intValue() : 0);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) { System.out.println("[OffGround] offgrid.jsonl: " + t); }
    }

    /** The main surface component: everything the planner can reach from Lumbridge. */
    static void mainComponent() {
        if (!NavPlanner.built) { System.out.println("[OffGround] no nav graph: outside = off the grid box or z != 0 only"); return; }
        Location s = NavPlanner.standNear(HOME);
        int c0 = s == null ? 0 : NavPlanner.compAt(s.getX(), s.getY());
        if (c0 == 0) return;
        int[][] adj = NavPlanner.adj;
        boolean[] m = new boolean[adj.length];
        ArrayDeque<Integer> q = new ArrayDeque<>(); q.add(c0); m[c0] = true; int n = 1;
        while (!q.isEmpty()) { int a = q.poll(); for (int b : adj[a]) if (!m[b]) { m[b] = true; n++; q.add(b); } }
        main = m;
        System.out.println("[OffGround] main surface component: " + n + " of " + NavPlanner.comps + " cell components");
    }

    /** NavWalker.step's "outside the surface grid" test, plus: its stand tile is not on the main surface. */
    static boolean outside(Location l) {
        if (l.getZ() != 0 || !NavGrid.inBounds(l.getX(), l.getY())) return true;
        if (main == null) return false;
        Location s = NavPlanner.standNear(l);
        if (s == null) return true;
        int c = NavPlanner.compAt(s.getX(), s.getY());
        return c <= 0 || c >= main.length || !main[c];
    }

    // ------------------------------------------------------------------ demand from the code

    static void demand() {
        DEMAND.clear();
        String[][] src = {{"AfkTasks", "afk"}, {"HuntMap", "afk"}, {"BotChat", "shop"}, {"SmartAdventurers", "afk"}, {"QuestSteps", "quest"}, {"ServiceBots", "afk"}, {"Resources", "afk"}};
        Set<String> seen = new HashSet<>();
        List<String> classes = new ArrayList<>();
        for (String[] s : src) classes.add(s[0] + "|" + s[1]);
        for (String q : new String[]{"QuestBlackKnightsFortress", "QuestCooksAssistant", "QuestDoricsQuest", "QuestDruidicRitual", "QuestErnestTheChicken", "QuestGoblinDiplomacy", "QuestImpCatcher", "QuestKnightsSword",
                "QuestPiratesTreasure", "QuestPrinceAliRescue", "QuestRestlessGhost", "QuestRomeoAndJuliet", "QuestRuneMysteries", "QuestSheepShearer", "QuestShieldOfArrav", "QuestVampireSlayer", "QuestWitchsPotion"}) classes.add(q + "|quest");
        for (String cl : classes) {
            String[] p = cl.split("\\|");
            try {
                Class<?> c = Class.forName("content.custom." + p[0]);
                List<Class<?>> all = new ArrayList<>(); all.add(c); for (Class<?> n : c.getDeclaredClasses()) all.add(n);
                for (Class<?> k : all) for (Field f : k.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    if (p[0].equals("BotChat") && !f.getName().toLowerCase(Locale.ROOT).contains("shop")) continue; // the website map's labels, features and NPCs are not places bots ask for
                    String tag = f.getName().contains("BANK") ? "bank" : f.getName().toLowerCase(Locale.ROOT).contains("shop") ? "shop" : p[1];
                    try { f.setAccessible(true); collect(f.get(null), tag, 0, seen); } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
        }
        addDemand(Location.create(3096, 9864, 0), "stuck", seen); // the Edgeville dungeon stuck cell
        outsideDemand = 0;
        for (Object[] d : DEMAND) if ((Boolean) d[3]) outsideDemand++;
        Map<String, Integer> by = new HashMap<>(); for (Object[] d : DEMAND) by.merge((String) d[1], 1, Integer::sum);
        System.out.println("[OffGround] demand: " + DEMAND.size() + " destinations from the code " + by + ", " + outsideDemand + " outside the surface grid (z != 0, off the grid, or not on the main surface)");
    }

    static void collect(Object v, String tag, int depth, Set<String> seen) {
        if (v == null || depth > 3) return;
        if (v instanceof Location) { addDemand((Location) v, tag, seen); return; }
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            if (a.length >= 3 && a[1] instanceof Number && a[2] instanceof Number) { int x = ((Number) a[1]).intValue(), y = ((Number) a[2]).intValue(); if (x > 1000 && x < 4000 && y > 2000 && y < 11000) addDemand(Location.create(x, y, 0), tag, seen); return; } // BotChat.WorldData rows: name, x, y, ...
            for (Object o : a) collect(o, tag, depth + 1, seen);
            return;
        }
        if (v instanceof Map) { for (Object o : ((Map<?, ?>) v).values()) collect(o, tag, depth + 1, seen); return; }
        if (v instanceof Collection) { int n = 0; for (Object o : (Collection<?>) v) { if (++n > 5000) break; collect(o, tag, depth + 1, seen); } return; }
        if (v.getClass().getName().startsWith("content.custom.")) {
            for (Field f : v.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != Location.class) continue;
                try { f.setAccessible(true); collect(f.get(v), tag, depth + 1, seen); } catch (Throwable ignored) { }
            }
        }
    }

    static void addDemand(Location l, String tag, Set<String> seen) {
        if (l == null || l.getX() < 1000 || wild(l.getX(), l.getY())) return;
        if (!seen.add(l.getX() + "," + l.getY() + "," + l.getZ())) return;
        int w = tag.equals("stuck") ? 10 : tag.equals("quest") || tag.equals("shop") || tag.equals("bank") ? 3 : 2;
        DEMAND.add(new Object[]{l, tag, w, outside(l)});
    }

    // ------------------------------------------------------------------ connectors

    static String connectorOption(Scenery s) {
        if (s == null || s.getDefinition() == null) return null;
        String n = s.getName() == null ? "" : s.getName().toLowerCase(Locale.ROOT);
        if (!Explorers.hatch(s) && (NEVER.matcher(n).matches() || !CONNECTOR.matcher(n).matches())) return null;
        String[] opts = s.getDefinition().getOptions();
        if (opts == null) return null;
        for (String o : opts) if (o != null && (o.toLowerCase(Locale.ROOT).contains("pay") || o.toLowerCase(Locale.ROOT).contains("toll"))) return null; // costs coins: not without the bots' affordability rules
        for (String want : CLIMB) for (String o : opts) if (o != null && o.equalsIgnoreCase(want)) return o;
        if (Explorers.hatch(s)) for (String o : opts) if (o != null && o.equalsIgnoreCase("Open")) return o;
        return null;
    }

    static void scanRegion(int region, List<Cand> out) {
        Region r = null; boolean wasLoaded = true;
        try { r = RegionManager.forId(region); wasLoaded = r != null && r.isLoaded(); } catch (Throwable ignored) { }
        try { if (r != null && !r.isLoaded()) Region.load(r); } catch (Throwable ignored) { }
        int bx = (region >> 8) << 6, by = (region & 0xff) << 6;
        Set<Integer> seenIds = new HashSet<>();
        for (int dx = 0; dx < 64; dx++) for (int dy = 0; dy < 64; dy++) {
            Scenery s;
            try { s = RegionManager.getObject(0, bx + dx, by + dy); } catch (Throwable t) { continue; }
            if (s == null || s.getLocation() == null) continue;
            String o = connectorOption(s);
            if (o == null) continue;
            Location l = s.getLocation();
            if (wild(l.getX(), l.getY()) || boss(l.getX(), l.getY())) continue;
            if (!seenIds.add((l.getX() << 16) ^ l.getY())) continue;
            out.add(connector(s, o, null, 0));
        }
        if (!wasLoaded && r != null) { try { Region.unload(r); } catch (Throwable ignored) { } }
    }

    static Cand connector(Scenery s, String option, String via, double demand) {
        Location l = s.getLocation();
        Cand c = new Cand();
        c.kind = "connector"; c.x = l.getX(); c.y = l.getY(); c.z = l.getZ(); c.id = s.getId(); c.name = s.getName(); c.option = option; c.via = via; c.demand = demand;
        c.key = ckey(c.x, c.y, c.z, c.id);
        c.covered = COVERED.contains(c.x + "," + c.y + "," + c.z + "#" + c.id);
        c.done = DONE.containsKey(c.key);
        c.dangerFor = DANGER.getOrDefault(c.key, -1); // batch 18 rc3
        c.issue = (l.getZ() == 0 && l.getY() < 6400 ? (option.toLowerCase(Locale.ROOT).contains("up") ? "upper floor" : "below ground") : "off-ground floor") + (via != null ? " (seen from " + via + ")" : "");
        return c;
    }

    static boolean add(Cand c) { synchronized (CANDS) { if (BY_KEY.containsKey(c.key)) return false; BY_KEY.put(c.key, c); CANDS.add(c); return true; } }

    /** Demand: outside destinations near the land above each connector; ties go to the nearest to Lumbridge. */
    static void rank() {
        synchronized (CANDS) {
            for (Cand c : CANDS) {
                if (!"connector".equals(c.kind) || c.via != null) continue;
                double d0 = 0.1;
                for (Object[] d : DEMAND) {
                    if (!(Boolean) d[3]) continue;
                    Location l = (Location) d[0];
                    int ly = l.getY() > 6400 ? l.getY() - 6400 : l.getY();
                    double dist = Math.max(Math.abs(l.getX() - c.x), Math.abs(ly - c.y));
                    if (dist <= 48) d0 += (Integer) d[2] * (1 - dist / 64.0);
                }
                c.demand = d0;
            }
            unlinked = 0;
            for (Object[] d : DEMAND) { if (!(Boolean) d[3]) continue; Location l = (Location) d[0]; int ly = l.getY() > 6400 ? l.getY() - 6400 : l.getY(); boolean any = false; for (Cand c : CANDS) if ("connector".equals(c.kind) && Math.max(Math.abs(l.getX() - c.x), Math.abs(ly - c.y)) <= 48) { any = true; break; } if (!any) unlinked++; }
            CANDS.sort((a, b) -> a.demand != b.demand ? Double.compare(b.demand, a.demand) : Double.compare(a.at().getDistance(HOME), b.at().getDistance(HOME)));
        }
    }

    // ------------------------------------------------------------------ surface problem list (refreshed every 30 minutes)

    static Cand surface(String kind, int x, int y, String issue, int count, double demand) {
        Cand c = new Cand();
        c.kind = "surface"; c.x = x; c.y = y; c.z = 0; c.issue = issue; c.count = count; c.demand = demand;
        c.key = "s:" + kind + ":" + x + "," + y + ",0";
        Integer proven = DONE.get(c.key);
        c.done = proven != null && count < proven + 2; // a stuck cell proven once comes back when bots get stuck there twice more
        return c;
    }

    static void refresh(boolean log) {
        int added = 0, revived = 0;
        List<Cand> fresh = new ArrayList<>();
        for (int[] s : STUCK) fresh.add(surface("stuck", s[0], s[1], "stuck cell (top rescue spots 10-25)", Watchdog.TRAPS.getOrDefault(RouteMap.cell(s[0], s[1]), 0), 50));
        List<Map.Entry<Integer, Integer>> traps = new ArrayList<>(Watchdog.TRAPS.entrySet());
        traps.sort((a, b) -> b.getValue() - a.getValue());
        int n = 0;
        for (Map.Entry<Integer, Integer> e : traps) {
            if (e.getValue() < 2 || n >= 12) break;
            int x = RouteMap.cx(e.getKey()) * RouteMap.CELL + 4, y = RouteMap.cy(e.getKey()) * RouteMap.CELL + 4;
            if (wild(x, y)) continue;
            n++; fresh.add(surface("stuck", x, y, "rescue spot (Watchdog: " + e.getValue() + " rescues)", e.getValue(), 20 + e.getValue()));
        }
        Map<Integer, Object[]> islands = new HashMap<>(); // one task per 8x8 cell, the most wanted 40
        for (Object[] d : DEMAND) {
            Location l = (Location) d[0];
            if (!(Boolean) d[3] || l.getZ() != 0 || l.getY() > 6400) continue;
            int cell = RouteMap.cell(l.getX(), l.getY());
            Object[] o = islands.get(cell);
            if (o == null) islands.put(cell, new Object[]{l, d[1], (Integer) d[2]}); else o[2] = (Integer) o[2] + (Integer) d[2];
        }
        List<Object[]> isl = new ArrayList<>(islands.values());
        isl.sort((a, b) -> (Integer) b[2] - (Integer) a[2]);
        for (int i = 0; i < Math.min(40, isl.size()); i++) { Location l = (Location) isl.get(i)[0]; fresh.add(surface("island", l.getX(), l.getY(), "outside-grid " + isl.get(i)[1] + " destination (not on the main surface)", 0, 10 + (Integer) isl.get(i)[2])); }
        fresh.add(surface("door", TOLL[0], TOLL[1], "the Al Kharid toll gate", 0, 30));
        for (String k : Gates.SHUT.keySet()) {
            try { String[] p = k.split(","); int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]); if (!"0".equals(p[2]) || y > 6400 || wild(x, y)) continue; fresh.add(surface("door", x, y, "a door or gate that stayed shut for a bot", 0, 10)); } catch (Throwable ignored) { }
        }
        synchronized (CANDS) {
            for (Cand c : fresh) {
                Cand old = BY_KEY.get(c.key);
                if (old == null) { add(c); if (!c.done) added++; }
                else if (old.done && !c.done) { old.done = false; old.count = c.count; old.issue = c.issue; old.taken = null; revived++; }
            }
            CANDS.sort((a, b) -> Double.compare(b.demand, a.demand));
        }
        if (log) System.out.println("[OffGround] problem list refreshed: " + added + " new and " + revived + " returning surface tasks; " + counts());
        if (ready && (added + revived) > 0 && LIVE.get() == 0 && SPAWNED.get() < Explorers.OFFGROUND + 6) { schedule(SPAWNED.get(), 5_000); System.out.println("[OffGround] a new hot spot and no explorer out: sending one"); } // within the hour, not at the next boot
    }

    static String counts() {
        int conn = 0, surf = 0, cov = 0, done = 0, todo = 0, tSurf = 0, danger = 0;
        synchronized (CANDS) {
            for (Cand c : CANDS) {
                if ("connector".equals(c.kind)) conn++; else surf++;
                if (c.covered) cov++; else if (c.done) done++; else if (c.dangerFor >= 0) danger++; else { todo++; if ("surface".equals(c.kind)) tSurf++; }
            }
        }
        return "candidates " + (conn + surf) + " (" + conn + " connectors, " + surf + " surface tasks): " + cov + " covered by recorded climbs, " + done + " already proven in offgrid.jsonl, " + danger + " dangerous for the explorers' level, " + todo + " to visit (" + tSurf + " surface)"; // batch 18 rc3: dangerous ones counted apart
    }

    static void report(int regions, long ms) {
        System.out.println("[OffGround] list ready from " + regions + " regions in " + ms / 1000 + " s (prepare pulses " + PREP_MS[0] + "/" + PREP_MS[1] + "/" + PREP_MS[2] + " ms): " + counts() + "; " + outsideDemand + " outside-grid destinations, " + unlinked + " of them with no connector within 48 tiles");
        int shown = 0;
        synchronized (CANDS) {
            for (Cand c : CANDS) if (!c.covered && !c.done && shown++ < 8) System.out.println("[OffGround]   to visit: " + c);
            int lum = 0; for (Cand c : CANDS) if ("connector".equals(c.kind) && c.x >= 3200 && c.x <= 3215 && c.y >= 3205 && c.y <= 3232 && lum++ < 6) System.out.println("[OffGround]   Lumbridge castle: " + c);
            int dun = 0; for (Cand c : CANDS) if ("connector".equals(c.kind) && "below ground".equals(c.issue) && dun++ < 4) System.out.println("[OffGround]   below ground: " + c);
        }
    }

    // ------------------------------------------------------------------ claiming

    static Cand claim(Player bot, String lastKey) {
        long now = System.currentTimeMillis();
        String me = bot.getUsername();
        synchronized (CANDS) {
            Cand best = null;
            if (lastKey != null) for (Cand c : CANDS) if (lastKey.equals(c.via) && free(c, bot, now)) { best = c; break; } // the next floor up/down first
            if (best == null) for (Cand c : CANDS) if (free(c, bot, now)) { best = c; break; }
            if (best != null) best.taken = me;
            return best;
        }
    }

    static boolean free(Cand c, Player bot, long now) { return !c.covered && !c.done && c.taken == null && c.deferUntil <= now && c.dangerFor < level(bot) && Gates.canEnter(bot, c.at()); } // batch 18 rc3: not where it was dangerous for this level
    static int level(Player p) { try { return p.getProperties().getCombatLevel(); } catch (Throwable t) { return 3; } } // batch 18 rc3
    static void markDanger(Cand c, int level) { synchronized (CANDS) { c.dangerFor = Math.max(c.dangerFor, level); c.taken = null; DANGER.merge(c.key, level, Math::max); } } // batch 18 rc3

    // ------------------------------------------------------------------ batch 18 rc3: danger (engine NPCs)

    /** Where a connector is expected to land: the recorded landing, else (ESTIMATE) y + 6400 below ground or the next plane. {Location, basis}. */
    static Object[] expectedLanding(Cand c) {
        Location rec = LANDING.get(c.x + "," + c.y + "," + c.z + "#" + c.id);
        if (rec != null) return new Object[]{rec, "recorded landing"};
        if (!ESTIMATE || c.option == null) return null;
        String o = c.option.toLowerCase(Locale.ROOT); boolean up = o.contains("up");
        if (c.y < 6400 && !up) return c.z == 0 ? new Object[]{Location.create(c.x, c.y + 6400, 0), "estimated landing (6400 north)"} : new Object[]{Location.create(c.x, c.y, c.z - 1), "estimated landing (plane below)"};
        if (up && c.z < 3 && c.y < 6400) return new Object[]{Location.create(c.x, c.y, c.z + 1), "estimated landing (plane above)"};
        return null; // up out of a dungeon: the surface
    }

    /** Attackable NPCs above level within r tiles of at (and, with tiles, inside that set or within 8 tiles). {name, level, count} of the strongest, or null. */
    static Object[] danger(Location at, int level, int r, Set<Long> tiles, boolean aggressiveOnly) {
        String worst = null; int wl = 0, n = 0;
        for (core.game.node.entity.npc.NPC npc : Repository.getNpcs()) {
            if (npc == null) continue;
            Location l = npc.getLocation();
            if (l == null || l.getZ() != at.getZ()) continue;
            int dx = Math.abs(l.getX() - at.getX()), dy = Math.abs(l.getY() - at.getY());
            if (dx > r || dy > r) continue;
            if (tiles != null && Math.max(dx, dy) > 8 && !tiles.contains(((long) l.getX() << 32) | l.getY())) continue;
            core.cache.def.impl.NPCDefinition d = npc.getDefinition();
            if (d == null) continue;
            int cl = d.getCombatLevel();
            if (cl <= 0) { try { cl = npc.getProperties().getCombatLevel(); } catch (Throwable ignored) { } }
            if (cl <= level || !(d.hasAction("attack") || d.hasAction("Attack"))) continue;
            if (aggressiveOnly && !npc.isAggressive()) continue;
            n++; if (cl > wl) { wl = cl; worst = d.getName(); }
        }
        return n == 0 ? null : new Object[]{worst, wl, n};
    }

    @SuppressWarnings("unchecked")
    static JSONObject dangerJson(String when, String basis, Location near, Object[] dz, boolean aggressiveOnly) {
        JSONObject j = new JSONObject();
        j.put("when", when); j.put("basis", basis); j.put("near", Explorers.loc(near)); j.put("npc", dz[0]); j.put("npcLevel", dz[1]); j.put("count", dz[2]); j.put("aggressiveOnly", aggressiveOnly);
        return j;
    }

    static void finish(Cand c) { synchronized (CANDS) { c.done = true; c.taken = null; DONE.put(c.key, c.count); } }
    static void release(Cand c, long deferMs) { synchronized (CANDS) { c.taken = null; c.deferUntil = System.currentTimeMillis() + deferMs; } }

    static boolean humanNear(Location l, int r) {
        for (Player h : Repository.getPlayers()) if (h != null && h.isActive() && !h.isArtificial() && h.getLocation().getZ() == l.getZ() && h.getLocation().getDistance(l) <= r) return true;
        return false;
    }

    // ------------------------------------------------------------------ flood fill

    static final class Fill { int tiles, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, doors; boolean capped, mainSurface; List<Scenery> connectors = new ArrayList<>(); Set<Long> seen = new HashSet<>(); } // batch 18 rc3: seen kept for the danger check

    /** What a player could walk to from l on its floor (walls, objects and the engine's clipping; doors count as passable), up to max tiles within r. */
    static Fill fill(Location l, int max, int r) {
        Fill f = new Fill();
        int z = l.getZ();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = f.seen; // batch 18 rc3
        q.add(new int[]{l.getX(), l.getY()}); seen.add(((long) l.getX() << 32) | l.getY());
        Set<String> conn = new HashSet<>();
        while (!q.isEmpty()) {
            int[] t = q.poll();
            int x = t[0], y = t[1];
            f.tiles++; f.x0 = Math.min(f.x0, x); f.y0 = Math.min(f.y0, y); f.x1 = Math.max(f.x1, x); f.y1 = Math.max(f.y1, y);
            if (!f.mainSurface && z == 0 && main != null && NavGrid.inBounds(x, y)) { int c = NavPlanner.compAt(x, y); if (c > 0 && c < main.length && main[c]) f.mainSurface = true; }
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) { // objects beside the walkable tiles (a ladder is a solid tile)
                Scenery s; try { s = RegionManager.getObject(z, x + dx, y + dy); } catch (Throwable e) { continue; }
                if (s == null || s.getLocation() == null || conn.contains(s.getLocation().toString() + s.getId())) continue;
                if (connectorOption(s) != null && f.connectors.size() < 20) { conn.add(s.getLocation().toString() + s.getId()); f.connectors.add(s); }
            }
            if (f.tiles >= max) { f.capped = true; break; }
            int a = flag(z, x, y);
            for (int d = 0; d < 4; d++) {
                int nx = x + NavGrid.DX[d], ny = y + NavGrid.DY[d];
                if (Math.abs(nx - l.getX()) > r || Math.abs(ny - l.getY()) > r) continue;
                long k = ((long) nx << 32) | ny;
                if (seen.contains(k)) continue;
                int b = flag(z, nx, ny);
                if ((b & (NavGrid.SOLID | NavGrid.UNREAD)) != 0) continue;
                boolean wall = (a & NavGrid.WALL_FROM[d]) != 0 || (b & NavGrid.WALL_INTO[d]) != 0;
                if (wall && !door(z, x, y) && !door(z, nx, ny)) continue;
                if (wall) f.doors++;
                seen.add(k); q.add(new int[]{nx, ny});
            }
        }
        return f;
    }

    static int flag(int z, int x, int y) { try { return NavGrid.pack(RegionManager.getClippingFlag(z, x, y)); } catch (Throwable t) { return NavGrid.UNREAD; } }
    static boolean door(int z, int x, int y) {
        try { Scenery o = RegionManager.getObject(z, x, y); return o != null && o.getDefinition() != null && o.getDefinition().hasAction("Open") && NavGrid.isGateName(o.getName()); } catch (Throwable t) { return false; }
    }

    /** A standable tile next to l on its floor (a ladder or a stair is a solid tile). */
    static Location beside(Location l) {
        for (int rr = 1; rr <= 3; rr++) for (int dx = -rr; dx <= rr; dx++) for (int dy = -rr; dy <= rr; dy++) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) != rr) continue;
            int b = flag(l.getZ(), l.getX() + dx, l.getY() + dy);
            if ((b & (NavGrid.SOLID | NavGrid.UNREAD)) == 0) return Location.create(l.getX() + dx, l.getY() + dy, l.getZ());
        }
        Location w = Surveyors.Surveyor.walkable(l);
        return w != null ? w : l;
    }

    // ------------------------------------------------------------------ records

    @SuppressWarnings("unchecked")
    static JSONObject fillJson(Fill f) {
        JSONObject j = new JSONObject();
        j.put("tiles", f.tiles); JSONArray box = new JSONArray(); if (f.tiles > 0) { box.add(f.x0); box.add(f.y0); box.add(f.x1); box.add(f.y1); } j.put("box", box);
        j.put("capped", f.capped); j.put("mainSurface", f.mainSurface); j.put("doors", f.doors);
        JSONArray cs = new JSONArray();
        for (Scenery s : f.connectors) { JSONObject c = new JSONObject(); c.put("id", s.getId()); c.put("name", s.getName()); c.put("option", connectorOption(s)); c.put("at", Explorers.loc(s.getLocation())); cs.add(c); }
        j.put("connectors", cs);
        return j;
    }

    @SuppressWarnings("unchecked")
    static void record(Player bot, Cand c, Scenery obj, String option, Location from, Location landing, boolean success, String result, Fill f) { record(bot, c, obj, option, from, landing, success, result, f, -1, null); }
    @SuppressWarnings("unchecked")
    static void record(Player bot, Cand c, Scenery obj, String option, Location from, Location landing, boolean success, String result, Fill f, int dangerFor, JSONObject danger) { // batch 18 rc3: dangerFor/danger on a dangerous place
        JSONObject j = new JSONObject();
        if (dangerFor >= 0) { j.put("dangerFor", dangerFor); j.put("danger", danger); }
        j.put("v", 1); j.put("t", System.currentTimeMillis()); j.put("bot", bot.getUsername()); j.put("key", c.key); j.put("kind", c.kind); j.put("issue", c.issue); j.put("count", c.count);
        if (obj != null) { JSONObject e = new JSONObject(); e.put("id", obj.getId()); e.put("name", obj.getName()); e.put("option", option); e.put("at", Explorers.loc(obj.getLocation())); j.put("entrance", e); } else j.put("entrance", null);
        j.put("from", Explorers.loc(from)); j.put("landing", landing == null ? null : Explorers.loc(landing));
        j.put("plane", landing == null ? null : landing.getZ()); j.put("under", landing != null && landing.getY() > 6400);
        j.put("success", success); j.put("result", result); j.put("demand", Math.round(c.demand * 10) / 10.0);
        j.put("fill", f == null ? null : fillJson(f));
        if (dangerFor >= 0) markDanger(c, dangerFor); else finish(c); // batch 19b: every record site updates DONE/DANGER (in memory) before the queued append; callers no longer call finish/markDanger
        Explorers.write("offgrid.jsonl", j);
        VISITS.incrementAndGet();
    }

    @SuppressWarnings("unchecked")
    static void stairs(Player bot, Scenery obj, String option, Location before, Location after, String result) { // the stair explorers' format (QuestFloors reads up/down results)
        JSONObject j = new JSONObject();
        j.put("t", System.currentTimeMillis()); j.put("bot", bot.getUsername());
        j.put("object", obj.getName()); j.put("id", obj.getId()); j.put("option", option); j.put("at", Explorers.loc(obj.getLocation()));
        j.put("from", Explorers.loc(before)); j.put("to", Explorers.loc(after));
        JSONArray opts = new JSONArray(); for (String o : obj.getDefinition().getOptions()) if (o != null) opts.add(o); j.put("options", opts);
        j.put("result", result);
        Explorers.TESTED.add(Explorers.key(obj.getLocation()) + "#" + obj.getId()); // batch 19b: in memory before the queued append
        if ((result.startsWith("up") || result.startsWith("down")) && after != null) LANDING.put(Explorers.key(obj.getLocation()) + "#" + obj.getId(), after); // batch 19b: what readCovered would read back at the next boot
        Explorers.write("stairs.jsonl", j);
    }

    // ------------------------------------------------------------------ the explorer

    static void spawn(int idx) {
        SPAWNED.incrementAndGet();
        int[] home = Explorers.TOWNS[idx % Explorers.TOWNS.length];
        AIPlayer bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(home[0], home[1], 0));
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, 70); bot.getSkills().setLevel(sk, 70); }
        bot.getSkills().updateCombatLevel();
        Explorer e = new Explorer();
        e.level = level(bot); // batch 18 rc3: 70 attack/strength/defence/hitpoints, the rest 1
        new GeneralBotCreator(e, bot);
        LIVE.incrementAndGet();
        GameWorld.getPulser().submit(new Pulse(3) {
            @Override public boolean pulse() {
                if (!bot.isActive() && !e.retired) { e.retired = true; LIVE.decrementAndGet(); if (e.cand != null) { release(e.cand, 0); e.cand = null; } return true; } // batch 18 review 3: a lost explorer no longer blocks the 30-minute respawn
                if (e.retired) return true;
                try { e.tick(); } catch (Throwable t) { System.out.println("[OffGround] " + bot.getUsername() + ": " + t); } return false; }
        });
        BotChat.activity(bot, () -> e.cand == null ? "looking for floors and dungeons nobody has mapped" : "exploring " + (e.cand.name != null ? e.cand.name : e.cand.issue));
        System.out.println("[OffGround] " + bot.getUsername() + " set out as an off-ground explorer (combat " + e.level + ")"); // batch 18 rc3
    }

    static final class Explorer extends Script {
        Cand cand; Scenery obj; String option, lastKey; Location before; long nextThink, waitUntil; int phase; boolean retired; int visits, idleRounds;
        int level = 3, skipped, fled; // batch 18 rc3: combat level; dangerous places skipped before climbing / left after landing
        @Override public Script newInstance() { return null; }

        void retire(String why) {
            retired = true; LIVE.decrementAndGet();
            System.out.println("[OffGround] " + bot.getUsername() + " retired: " + why + " (" + visits + " places recorded this time; " + counts() + ")");
            BotChat.clearActivity(bot);
            try { ((AIPlayer) bot).clear(); } catch (Throwable t) { System.out.println("[OffGround] could not retire " + bot.getUsername() + ": " + t); }
        }

        @Override
        public void tick() {
            if (bot == null || !bot.isActive() || retired) return;
            long now = System.currentTimeMillis();
            if (now < nextThink) return;
            nextThink = now + 1_800;
            if (!ready) return;
            Location here = bot.getLocation();
            if (cand == null) {
                cand = claim(bot, lastKey);
                if (cand == null) { if (++idleRounds >= 2) retire("a full round with nothing new to visit"); else nextThink = now + 60_000; return; }
                idleRounds = 0; phase = 0; level = level(bot); // batch 18 rc3: its real combat level at each claim
                if (PRECHECK && "connector".equals(cand.kind)) { // batch 18 rc3: what is known before climbing: the landing (recorded or estimated) and the engine's NPCs near it
                    Object[] el = expectedLanding(cand);
                    boolean recorded = el != null && "recorded landing".equals(el[1]);
                    Object[] dz = el == null ? null : danger((Location) el[0], level, recorded ? 20 : 16, null, false);
                    if (dz != null) {
                        String res = "skipped before climbing: " + el[1] + " " + el[0] + " has " + dz[2] + " " + dz[0] + " (level " + dz[1] + ") above level " + level + " within " + (recorded ? 20 : 16) + " tiles; dangerous for level " + level;
                        record(bot, cand, null, cand.option, here, null, false, res, null, level, dangerJson("before climbing", (String) el[1], (Location) el[0], dz, false)); // batch 19b: record() marks the danger first
                        System.out.println("[OffGround] " + bot.getUsername() + " (combat " + level + ") " + res + " [" + cand.key + " " + cand.name + "]");
                        skipped++; cand = null; nextThink = now + 600; return;
                    }
                }
                if (humanNear(cand.at(), 6)) { release(cand, 10 * 60_000L); cand = null; nextThink = now + 30_000; return; } // batch 18 review note: check before the teleport, so a player never sees one pop in and out
                Location stand = "surface".equals(cand.kind) ? Surveyors.Surveyor.walkable(cand.at()) : beside(cand.at());
                try { Spectacle.teleport(bot, stand != null ? stand : cand.at()); } catch (Throwable ignored) { } // batch 15 item1 rules
                nextThink = now + 4_000;
                return;
            }
            if (phase == 0) { // arrived next to it
                if (humanNear(cand.at(), 6)) { release(cand, 10 * 60_000L); cand = null; return; } // never interrupt a real player
                if ("surface".equals(cand.kind)) { surfaceTask(here, now); return; }
                obj = null; try { obj = RegionManager.getObject(cand.z, cand.x, cand.y); } catch (Throwable ignored) { }
                if (obj == null || obj.getId() != cand.id) { record(bot, cand, null, cand.option, here, null, false, "object gone", null); cand = null; return; }
                option = cand.option; before = here;
                try { scriptAPI.interact(bot, obj, option); } catch (Throwable t) { record(bot, cand, obj, option, here, null, false, "could not use: " + t, null); cand = null; return; }
                phase = option.equalsIgnoreCase("Open") ? 1 : 2; waitUntil = now + (phase == 1 ? 3_000 : 10_000);
                return;
            }
            if (phase == 1) { // a hatch was opened: climb the open one
                if (now < waitUntil) return;
                Scenery open = null;
                for (Scenery s : Explorers.scan(cand.at(), 1, "Climb-down", "Climb-into", "Enter")) { open = s; break; }
                if (open == null) { record(bot, cand, obj, option, before, here, false, "would not open", null); cand = null; return; }
                obj = open; option = Explorers.firstOption(open, "Climb-down", "Climb-into", "Enter");
                try { scriptAPI.interact(bot, obj, option); } catch (Throwable t) { record(bot, cand, obj, option, here, null, false, "could not use: " + t, null); cand = null; return; }
                phase = 2; waitUntil = now + 10_000;
                return;
            }
            if (phase == 2) { // waiting to see where it put us
                boolean moved = here.getZ() != before.getZ() || (here.getY() > 6400) != (before.getY() > 6400) || here.getDistance(before) > 4;
                if (!(moved && !bot.getWalkingQueue().isMoving()) && now < waitUntil) return;
                boolean down = here.getY() > 6000 && before.getY() < 6000, up = before.getY() > 6000 && here.getY() < 6000;
                String res = down ? "down (underground)" : up ? "up (to surface)" : moved ? (here.getZ() > before.getZ() ? "up" : here.getZ() < before.getZ() ? "down" : "moved") : "nothing (dialog or locked?)";
                try { stairs(bot, obj, option, before, here, res); } catch (Throwable t) { System.out.println("[OffGround] stairs.jsonl: " + t); }
                Fill f = null;
                if (moved && (wild(here.getX(), here.getY()) || boss(here.getX(), here.getY()))) res += "; Wilderness or boss area: left at once";
                else if (moved) {
                    f = fill(here, 2500, 48);
                    Object[] dz = danger(here, level, 48, f.seen, true); // batch 18 rc3: aggressive NPCs above its level in what it can reach: leave at once
                    if (dz != null) {
                        res += "; dangerous for level " + level + ": " + dz[2] + " aggressive " + dz[0] + " (level " + dz[1] + ") in the reachable area: left at once";
                        try { Spectacle.teleport(bot, before); } catch (Throwable ignored) { }
                        record(bot, cand, obj, option, before, here, true, res, f, level, dangerJson("after landing", "flood fill " + f.tiles + " tiles", here, dz, true));
                        System.out.println("[OffGround] " + bot.getUsername() + " (combat " + level + ") via " + obj.getName() + " at " + obj.getLocation() + " to " + here + ": " + res + " [" + cand.key + "]");
                        fled++; visits++; lastKey = null; cand = null; nextThink = now + 3_000;
                        return;
                    }
                    int added = 0;
                    for (Scenery s : f.connectors) {
                        String o = connectorOption(s);
                        if (o == null || s.getLocation().getZ() == 0 && s.getLocation().getY() < 6400) continue; // plane-0 surface ones are on the list already
                        if (wild(s.getLocation().getX(), s.getLocation().getY()) || boss(s.getLocation().getX(), s.getLocation().getY())) continue;
                        Cand n = connector(s, o, cand.key, cand.demand * 0.8 + 0.1);
                        if (add(n) && !n.covered && !n.done) added++;
                    }
                    if (added > 0) synchronized (CANDS) { CANDS.sort((a, b) -> Double.compare(b.demand, a.demand)); }
                    if (Boolean.getBoolean("afk.debug")) System.out.println("[OffGround] " + bot.getUsername() + " " + res + " via " + obj.getName() + " at " + obj.getLocation() + " to " + here + ": " + f.tiles + " tiles reachable, " + f.connectors.size() + " connectors (" + added + " new)");
                }
                record(bot, cand, obj, option, before, moved ? here : null, moved, res, f);
                lastKey = moved ? cand.key : null; visits++; cand = null;
                if (!moved) { try { bot.getInterfaceManager().close(); } catch (Throwable ignored) { } }
                nextThink = now + 2_000;
                return;
            }
            if (phase == 3) { // a door test on a surface task
                if (now < waitUntil) return;
                Scenery nowObj = null; try { nowObj = RegionManager.getObject(obj.getLocation().getZ(), obj.getLocation().getX(), obj.getLocation().getY()); } catch (Throwable ignored) { }
                boolean changed = nowObj == null || nowObj.getId() != obj.getId() || nowObj.getRotation() != obj.getRotation() || !Explorers.scan(obj.getLocation(), 1, "Close").isEmpty();
                Fill f = fill(here, 1500, 40);
                record(bot, cand, obj, option, before, here, changed, changed ? "opened" : "stayed shut (locked, quest, key or toll)", f);
                visits++; cand = null; lastKey = null;
            }
        }

        /** A surface problem: is it on the main surface, what walls and doors are there; a door task tries the door ("Open" only, never a toll). */
        void surfaceTask(Location here, long now) {
            before = here;
            if (cand.key.startsWith("s:door:")) {
                Scenery door = null;
                for (Scenery s : Explorers.scan(cand.at(), 3, "Open", "Pay-toll", "Pay-toll(10gp)", "Unlock", "Knock-on", "Pick-lock")) { door = s; break; }
                if (door == null) { record(bot, cand, null, null, here, here, false, "no door or gate found within 3 tiles", fill(here, 1500, 40)); cand = null; return; }
                String open = Explorers.firstOption(door, "Open");
                if (open == null) {
                    long coins = 0; try { coins = Ledger.Wallet.coins(bot); } catch (Throwable ignored) { }
                    record(bot, cand, door, null, here, here, false, "toll, key or quest gate: not used (no Open; costs coins or a key; this bot has " + coins + " gp)", fill(here, 1500, 40));
                    visits++; cand = null; return;
                }
                obj = door; option = open;
                try { scriptAPI.interact(bot, door, open); } catch (Throwable t) { record(bot, cand, door, open, here, here, false, "could not use: " + t, null); cand = null; return; }
                phase = 3; waitUntil = now + 9_000;
                return;
            }
            Fill f = fill(here, 1500, 40);
            boolean stand = NavGrid.standable(cand.x, cand.y);
            String res = (f.mainSurface ? "on the main surface" : "a pocket off the main surface") + " (" + f.tiles + " tiles" + (f.capped ? "+" : "") + " reachable, " + f.doors + " door steps, target tile " + (stand ? "standable" : "blocked") + ")";
            record(bot, cand, null, null, here, here, f.mainSurface, res, f);
            visits++; cand = null; lastKey = null;
        }
    }
}
