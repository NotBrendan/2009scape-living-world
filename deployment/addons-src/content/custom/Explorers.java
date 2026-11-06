package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.bots.GeneralBotCreator;
import core.game.bots.Script;
import core.game.bots.SkillingBotAssembler;
import core.game.node.entity.skill.Skills;
import core.game.node.scenery.Scenery;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * More surveyors (2026-10-20 09:49): the pathfinder never climbs, and doors/gates are guesswork.
 *  - STAIRS: goes round buildings with stairs and ladders, uses each one, and records which object, option and tile
 *    lead to which floor (data/survey/stairs.jsonl). On each new floor it looks for the next staircase, up to a few
 *    climbs per building, then moves on.
 *  - DOORS: walks between towns and tries every door and gate it meets ("Open"), recording whether it opened, and
 *    lists gates with other options (toll, quest) without using them (data/survey/doors.jsonl).
 * Flags: -Dsurvey.stairs (default 0: superseded by the off-ground explorers), -Dsurvey.doors (default 6),
 * -Dsurvey.tunnels (default 1: shortcut seekers; the first at Agility 99 with the seeded quests, more alternate 1 / 99),
 * -Dsurvey.tunnels.agility (trials: every seeker at this Agility), -Dsurvey.offground (default 6: OffGround.java),
 * -Dsurvey.doors.round (towns in a door explorer's round, default all 14: a round with nothing new and it retires).
 * batch 18 rc3: doors.jsonl is settled data: a door with a recorded result for its tile/id/option is not retried unless its
 * game data/build stamp (server.jar size, door_configs.json size, the object's cache name and options) changes.
 * batch 18 (the owner 10-25): no blanket surface surveying; seekers test only shortcuts they could pass and that are not settled.
 */
public final class Explorers implements StartupListener {
    static final Random RNG = new Random();
    static final Timer TIMER = new Timer("Explorers", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");
    static final int STAIRS = Integer.getInteger("survey.stairs", 0), DOORS = Integer.getInteger("survey.doors", 6); // batch 18 item2: stairs 6 -> 0 (the off-ground explorers replace them)
    static final int TUNNELS = Integer.getInteger("survey.tunnels", 1); // shortcut testers; batch 18 item1: 4 -> 2; batch 18 rc3: 2 -> 1, the one seeker is the Agility 99 + seeded quests one
    static final Integer TUNNEL_AGILITY = Integer.getInteger("survey.tunnels.agility"); // batch 18 rc3: trials only (TestB18E1 needs an Agility-1 seeker): every seeker at this Agility
    static final int OFFGROUND = Integer.getInteger("survey.offground", 6); // batch 18 item2: off-ground explorers (OffGround.java); batch 18 rc3: 19 -> 6
    static final java.util.concurrent.atomic.AtomicInteger TUNNELS_RETIRED = new java.util.concurrent.atomic.AtomicInteger(); // batch 18 item1: for the trial
    static final java.util.concurrent.atomic.AtomicInteger DOORS_RETIRED = new java.util.concurrent.atomic.AtomicInteger(); // batch 18 rc3: for the trial

    /** Buildings and holes with stairs, ladders or trapdoors (ground-floor spots next to them). */
    static final int[][] SITES = {
            {3206, 3210}, // Lumbridge castle stairs
            {3209, 3216}, // Lumbridge castle cellar trapdoor
            {3212, 3474}, // Varrock palace
            {3237, 3458}, // Varrock sewer manhole
            {2964, 3338}, // Falador castle
            {3109, 3164}, // Wizards' Tower
            {3020, 3339}, // Mining Guild ladder
            {3019, 3450}, // Dwarven Mine ladder (Ice Mountain)
            {3097, 3468}, // Edgeville dungeon trapdoor
            {3293, 3168}, // Al Kharid palace
            {2758, 3495}, // Camelot castle
            {2573, 3296}, // Ardougne castle
            {3090, 3251}, // Draynor bank area / houses
            {2914, 3539}, // Burthorpe castle
            {3161, 3488}, // Grand Exchange
            {3016, 3215}, // Port Sarim
            {2957, 3214}, // Rimmington
            {2810, 3440}, // Catherby
            {2708, 3484}, // Seers' Village
            {2606, 3093}, // Yanille
            {2884, 3395}, // Taverley
            {3081, 3420}, // Barbarian Village
            {3108, 3350}, // Draynor Manor
            {3253, 3420}, // Varrock east bank
            {3225, 3240}, // Lumbridge church and farm
            {2946, 3368}, // Falador east
            {3290, 3176}, // Al Kharid bank
    };
    /** Towns the door surveyors walk between. */
    static final int[][] TOWNS = {{3222, 3218}, {3093, 3247}, {3212, 3428}, {2965, 3380}, {3093, 3491}, {3293, 3180}, {3016, 3215}, {2952, 3216}, {2757, 3478}, {2662, 3305}, {2808, 3438}, {2917, 3500}, {3080, 3420}, {3190, 3360}};
    static final int DOOR_ROUND = Math.max(1, Integer.getInteger("survey.doors.round", TOWNS.length)); // batch 18 rc3: a round = every town once (was two rounds); nothing new in it and the door explorer retires

    /** batch 18 rc3: doors.jsonl as settled data: "x,y,z#id#option" -> the stamp its last result was recorded under. */
    static final java.util.Map<String, String> DOOR_STAMP = new java.util.concurrent.ConcurrentHashMap<>();
    static String doorKey(Location l, int id, String option) { return key(l) + "#" + id + "#" + (option == null ? "special" : option); }
    static volatile String DOOR_BUILD;
    /** batch 18 rc3: the door's game data/build stamp (Gates.stamp's idea): server.jar size + door_configs.json size + the object's cache name and options. */
    static String doorStamp(int id) {
        String b = DOOR_BUILD;
        if (b == null) {
            long jar = 0, cfg = 0;
            try { jar = new File(core.Server.class.getProtectionDomain().getCodeSource().getLocation().toURI()).length(); } catch (Throwable ignored) { }
            try { cfg = new File(new File(ServerConstants.DATA_PATH, "configs"), "door_configs.json").length(); } catch (Throwable ignored) { }
            DOOR_BUILD = b = Long.toHexString(jar) + "-" + Long.toHexString(cfg);
        }
        String def = "";
        try { core.cache.def.impl.SceneryDefinition d = core.cache.def.impl.SceneryDefinition.forId(id); if (d != null) def = d.getName() + java.util.Arrays.toString(d.getOptions()); } catch (Throwable ignored) { }
        return b + "-" + Integer.toHexString(def.hashCode());
    }
    static boolean doorSettled(String k, int id) { String st = DOOR_STAMP.get(k); return st != null && st.equals(doorStamp(id)); } // batch 18 rc3

    /** Objects already tested (stairs: "x,y,z#id", doors: "x,y,z"), rebuilt from stairs.jsonl and doors.jsonl at startup. */
    static final Set<String> TESTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final Set<String> RETRIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static String key(Location l) { return l.getX() + "," + l.getY() + "," + l.getZ(); }

    static void remember() {
        java.io.File dir = new java.io.File(ServerConstants.DATA_PATH, "survey");
        int doorLines = 0, adopted = 0; java.util.Map<String, String> last = new java.util.HashMap<>(); java.util.Map<String, Integer> ids = new java.util.HashMap<>(); // batch 18 rc3
        for (String f : new String[]{"stairs.jsonl", "doors.jsonl"}) {
            java.io.File file = new java.io.File(dir, f);
            if (!file.exists()) continue;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        JSONObject j = (JSONObject) new org.json.simple.parser.JSONParser().parse(line);
                        JSONArray at = (JSONArray) j.get("at");
                        String k = at.get(0) + "," + at.get(1) + "," + at.get(2);
                        if (f.startsWith("stairs")) { TESTED.add(k + "#" + j.get("id")); continue; }
                        doorLines++; // batch 18 rc3: doors keyed by tile/id/option with the stamp of the result; a record from before stamps is adopted under today's (the seed is not retraced)
                        int id = ((Number) j.get("id")).intValue();
                        String res = String.valueOf(j.get("result")), opt = j.get("option") != null ? String.valueOf(j.get("option")) : null;
                        String dk = k + "#" + id + "#" + (opt == null ? "special" : opt);
                        Object st = j.get("stamp");
                        if (st == null && !DOOR_STAMP.containsKey(dk)) adopted++;
                        DOOR_STAMP.put(dk, st != null ? String.valueOf(st) : DOOR_STAMP.getOrDefault(dk, doorStamp(id)));
                        last.put(dk, res); ids.put(dk, id);
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable t) { System.out.println("[Explore] could not read " + f + ": " + t); }
        }
        System.out.println("[Explore] " + TESTED.size() + " stairs already tested; picking up where we left off");
        int settled = 0, opened = 0, shut = 0, special = 0, retest = 0;
        for (java.util.Map.Entry<String, String> e : last.entrySet()) {
            if (!doorSettled(e.getKey(), ids.get(e.getKey()))) { retest++; continue; }
            settled++; String res = e.getValue();
            if (res.startsWith("opened")) opened++; else if (res.startsWith("special")) special++; else shut++;
        }
        System.out.println("[Explore] doors: doors.jsonl " + doorLines + " records, " + last.size() + " doors/gates inherited (tile/id/option): " + settled + " settled (" + opened + " opened, " + shut + " stayed shut, " + special + " toll/key/quest not used; "
                + adopted + " adopted under today's stamp), " + retest + " to retest (game data changed); new ones are found on the walks; a door explorer retires after a round of " + DOOR_ROUND + " towns with nothing new (door stamp " + DOOR_BUILD + ")"); // batch 18 rc3: inherited/settled/to-do like the seekers
    }

    static final String[] CLIMB = {"Climb-up", "Climb-down", "Climb", "Climb-into", "Enter", "Go-down", "Go-up", "Open"};

    /** A closed trapdoor, manhole or hatch: it has to be opened before it offers Climb-down. */
    static boolean hatch(Scenery s) {
        String n = s.getName() == null ? "" : s.getName().toLowerCase(Locale.ROOT);
        return n.contains("trapdoor") || n.contains("trap door") || n.contains("manhole") || n.contains("hatch");
    }

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        new File(ServerConstants.DATA_PATH, "survey").mkdirs();
        remember();
        int n = 0;
        for (int i = 0; i < STAIRS + DOORS; i++) {
            final boolean stairs = i < STAIRS;
            final int idx = i;
            TIMER.schedule(new TimerTask() {
                @Override public void run() {
                    GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
                        @Override public boolean pulse() { try { spawn(stairs, idx); } catch (Throwable t) { System.out.println("[Explore] spawn failed: " + t); try { Failures.Life.loginFailed("explorer", null, t); } catch (Throwable ignored) { } } return true; }
                    });
                }
            }, FAST ? 40_000 + i * 3_000L : 300_000 + i * 20_000L);
            n++;
        }
        for (int i = 0; i < TUNNELS; i++) {
            final int idx = i;
            TIMER.schedule(new TimerTask() {
                @Override public void run() {
                    GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
                        @Override public boolean pulse() { try { spawnTunnel(idx); } catch (Throwable t) { System.out.println("[Explore] tunnel spawn failed: " + t); try { Failures.Life.loginFailed("explorer", null, t); } catch (Throwable ignored) { } } return true; }
                    });
                }
            }, FAST ? 60_000 + i * 3_000L : 420_000 + i * 20_000L);
        }
        try { OffGround.start(OFFGROUND); } catch (Throwable t) { System.out.println("[Explore] off-ground explorers: " + t); } // batch 18 item2
        System.out.println("[Explore] " + STAIRS + " stair surveyors (survey.stairs), " + DOORS + " door surveyors (survey.doors), " + TUNNELS + " shortcut seekers (survey.tunnels" + (TUNNELS == 0 ? "" : TUNNEL_AGILITY != null ? ", all at agility " + TUNNEL_AGILITY : TUNNELS == 1 ? ", agility 99 with the seeded quests" : ", agility 99 / 1 alternating") + "), " + OFFGROUND + " off-ground explorers (survey.offground) scheduled; "
                + Integer.getInteger("survey.count", 0) + " pathing surveyors (survey.count) and " + Math.min(3, Integer.getInteger("survey.replay", 3)) + " rescue replayers (survey.replay) are Surveyors/Scenarios'; data in data/survey/"); // batch 18 item3: every role count in the startup line; batch 18 rc3: replayers clamped like Scenarios.REPLAY (review note)
    }

    static void spawn(boolean stairs, int idx) {
        int[] home = stairs ? SITES[idx % SITES.length] : TOWNS[idx % TOWNS.length];
        AIPlayer bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(home[0], home[1], 0));
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, 70); bot.getSkills().setLevel(sk, 70); }
        bot.getSkills().updateCombatLevel();
        Explorer e = new Explorer(stairs, idx);
        new GeneralBotCreator(e, bot);
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(3) {
            @Override public boolean pulse() { if (!bot.isActive()) return true; try { e.tick(); } catch (Throwable t) { System.out.println("[Explore] " + bot.getUsername() + ": " + t); } return false; }
        });
        BotChat.activity(bot, () -> stairs ? "mapping the stairs and ladders" : "checking which doors and gates open");
        System.out.println("[Explore] " + bot.getUsername() + " set out as a " + (stairs ? "stairs" : "doors") + " surveyor");
    }

    // ------------------------------------------------------------------ tunnel surveyors (2026-10-21)

    static void spawnTunnel(int idx) {
        int[] home = TOWNS[(idx * 3) % TOWNS.length];
        AIPlayer bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(home[0], home[1], 0));
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, 70); bot.getSkills().setLevel(sk, 70); }
        int agi = TUNNEL_AGILITY != null ? TUNNEL_AGILITY : idx % 2 == 0 ? 99 : 1; // batch 18 rc3: the first (and by default only) seeker is the Agility 99 + seeded quests one
        bot.getSkills().setStaticLevel(Skills.AGILITY, agi); bot.getSkills().setLevel(Skills.AGILITY, agi);
        bot.getSkills().updateCombatLevel();
        if (agi >= 50) { // the high band also tests the shortcuts behind quest gates and tolls: every gated quest done, toll money in the bank (a data collector, not an economy bot)
            int q = 0; try { q = Gates.seedAll(bot); } catch (Throwable e) { System.out.println("[Explore] seedAll: " + e); }
            try { bot.getBank().add(new core.game.node.item.Item(995, 500)); } catch (Throwable ignored) { }
            System.out.println("[Explore] " + bot.getUsername() + " carries " + q + " gated quests and 500 gp for tolls");
        }
        Tunneller t = new Tunneller(agi);
        new GeneralBotCreator(t, bot);
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(3) {
            @Override public boolean pulse() { if (!bot.isActive()) return true; try { t.tick(); } catch (Throwable e) { System.out.println("[Explore] " + bot.getUsername() + ": " + e); } return false; }
        });
        BotChat.activity(bot, () -> "testing tunnels and shortcuts");
        System.out.println("[Explore] " + bot.getUsername() + " set out as a tunnel surveyor (agility " + agi + ")");
    }

    /** Walks to the nearest shortcut it (its Agility band) hasn't tried, uses it, and records where it came out. */
    static final class Tunneller extends Script {
        final int agility;
        Gates.Shortcut target; Location before; long nextThink, walkUntil, waitUntil; boolean used; int done;
        Tunneller(int agility) { this.agility = agility; }
        @Override public Script newInstance() { return null; }

        boolean untried(Gates.Shortcut s) { return agility >= 50 ? s.highOk + s.highFail == 0 : s.lowOk + s.lowFail == 0; }
        static final java.util.Map<String, Long> SKIP = new java.util.concurrent.ConcurrentHashMap<>(); // couldn't get there: try again in an hour
        static final int[][] SIDES = {{0, -2}, {0, 2}, {-2, 0}, {2, 0}, {-2, -2}, {2, 2}}; // where to stand for a retest: shortcuts only work from one side
        int gated;

        boolean high() { return agility >= 50; }
        static final long RETEST_MS = 7L * 24 * 3600_000L; // batch 18 item1: an unsettled result (one or two fails) is retried from another side after a week

        /** batch 18 item1: due for this band: never tested, tested under another game data/build stamp, or unsettled and a week old. Settled results are never retested. */
        boolean due(Gates.Shortcut s, long now) {
            boolean h = high();
            int tests = h ? s.highOk + s.highFail : s.lowOk + s.lowFail;
            if (tests == 0) return true;
            String st = h ? s.highStamp : s.lowStamp;
            if (st == null || !st.equals(Gates.stamp(s))) return true;
            if (s.settled(h)) return false;
            return now - (h ? s.highT : s.lowT) >= RETEST_MS;
        }

        /** batch 18 item1: could this bot pass it? the engine's level, items, the learned gate, the quest areas. */
        boolean qualifies(Gates.Shortcut s) {
            if (!Gates.couldPass(s, agility, high())) { Gates.learnGate(s); return false; }
            return Gates.canEnter(bot, s.at());
        }

        void pick() {
            Location here = bot.getLocation();
            long now = System.currentTimeMillis();
            Gates.Shortcut best = null; double bd = Double.MAX_VALUE; gated = 0; int settled = 0, skipped = 0;
            for (Gates.Shortcut s : Gates.SHORTCUTS.values()) {
                if (s.z != 0 || s.y > 6400 || Heat.wildLevel(s.x, s.y) > 0) continue;
                if (!due(s, now)) { settled++; continue; }
                if (!qualifies(s)) { gated++; continue; } // batch 18 item1: level, quest or item gate this bot can't pass: never tried by it
                if (SKIP.getOrDefault(s.key() + "#" + high(), 0L) > now) { skipped++; continue; }
                double d = s.at().getDistance(here);
                if (d < bd) { bd = d; best = s; }
            }
            target = best; used = false;
            if (best == null) { // batch 18 item1: nothing this bot could pass is left to test: retire (was: wait ten minutes and retest from other sides, 355 of 358 tests "nothing happened")
                System.out.println("[Explore] " + bot.getUsername() + " (agility " + agility + ") retired: nothing left to test for me (" + settled + " settled or tested this week, " + gated + " behind levels, quests or items for me, " + skipped + " unreachable for now; " + done + " tested this time)");
                TUNNELS_RETIRED.incrementAndGet();
                BotChat.clearActivity(bot);
                try { ((AIPlayer) bot).clear(); } catch (Throwable t) { System.out.println("[Explore] could not retire " + bot.getUsername() + ": " + t); }
                nextThink = Long.MAX_VALUE;
                return;
            }
            int fails = high() ? best.highFail : best.lowFail;
            if (fails > 0) { // a retest goes from another side (most shortcuts only work from one tile)
                int[] o = SIDES[fails % SIDES.length];
                Location stand = Surveyors.Surveyor.walkable(best.at().transform(o[0], o[1], 0));
                try { Spectacle.teleport(bot, stand != null ? stand : nearTo(best.at())); } catch (Throwable ignored) { } // batch 15 item1
                walkUntil = now + 30_000;
                System.out.println("[Explore] " + bot.getUsername() + " retests " + best.name + " at " + best.at() + " from side " + (fails % SIDES.length + 1) + " (agility " + agility + ")");
                return;
            }
            if (bd > 120) { try { Spectacle.teleport(bot, nearTo(best.at())); } catch (Throwable ignored) { } bd = 6; } // batch 15 item1
            walkUntil = now + 30_000 + (long) (bd * 1_500);
        }

        /** A walkable tile a few steps from the shortcut, to arrive by teleport like a player on a long trip. */
        Location nearTo(Location l) {
            Location w = null;
            try { w = RouteMap.walkedNear(l, 4); } catch (Throwable ignored) { } // where people stand at it
            if (w == null) for (int[] d : new int[][]{{1, 1}, {-1, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}}) { w = Surveyors.Surveyor.walkable(l.transform(d[0], d[1], 0)); if (w != null) break; }
            return w != null ? w : l;
        }

        @SuppressWarnings("unchecked")
        void record(boolean ok, Location after, String result) {
            JSONObject j = new JSONObject();
            j.put("t", System.currentTimeMillis()); j.put("bot", bot.getUsername()); j.put("agility", agility);
            j.put("object", target.name); j.put("id", target.id); j.put("option", target.option); j.put("level", target.level);
            j.put("at", loc(target.at())); j.put("from", loc(before)); j.put("to", loc(after)); j.put("result", result);
            boolean never = result.startsWith("never reached");
            if (never) target.tested = System.currentTimeMillis(); // so the 6-hour retest clock sees it (review 10-22)
            else Gates.tested(target, agility, ok, before, after); // batch 19b: the in-memory truth before the (queued) append
            write("shortcuts.jsonl", j);
            if (never) { // not a test of the shortcut: once more by teleport, then leave it for an hour
                String rk = target.key() + "#" + (agility >= 50);
                if (RETRIED.add(rk)) { try { Spectacle.teleport(bot, nearTo(target.at())); } catch (Throwable ignored) { } walkUntil = System.currentTimeMillis() + 30_000; used = false; return; } // batch 15 item1
                SKIP.put(rk, System.currentTimeMillis() + 3600_000L);
            }
            done++;
            if (done % 5 == 0) System.out.println("[Explore] " + bot.getUsername() + " has tested " + done + " shortcuts (agility " + agility + ")");
            target = null;
        }

        @Override
        public void tick() {
            if (bot == null || !bot.isActive()) return;
            long now = System.currentTimeMillis();
            if (now < nextThink) return;
            nextThink = now + 1_800;
            if (target == null) { pick(); return; }
            Location here = bot.getLocation();
            if (used) { // waiting to see where the shortcut put us
                Location ot = target.at();
                boolean farSide = before != null && ((here.getX() - ot.getX()) * (before.getX() - ot.getX()) + (here.getY() - ot.getY()) * (before.getY() - ot.getY())) < 0; // the object is between where it stood and where it is now
                boolean moved = before != null && (here.getZ() != before.getZ() || (here.getY() > 6400) != (before.getY() > 6400) || (farSide && here.getDistance(before) >= 2)); // walking up to the object is not crossing it (review 10-22: 877 of 1,199 records were "never reached", some "crossed" were approaches)
                if (moved && !bot.getWalkingQueue().isMoving()) { record(true, here, "crossed"); return; }
                if (now > waitUntil) { record(moved, here, moved ? "crossed (slow)" : "nothing happened (level, quest or blocked)"); return; }
                return;
            }
            if (here.getDistance(target.at()) > 5) { // within 5 the interaction walks the last tiles itself (consultant check Q3: 52 of 52 "never reached" at 2)
                if (now > walkUntil) { before = here; record(false, here, "never reached"); return; }
                if (!bot.getWalkingQueue().isMoving()) {
                    Location hop = null; try { hop = RouteMap.nextHop(bot, target.at()); } catch (Throwable ignored) { }
                    if (RouteMap.usingShortcut(bot)) return; // pathing review 10-23 R-2: the route's own shortcut is being used; walking to "here" reset the walk its click had started
                    double d = here.getDistance(target.at());
                    Location step = hop != null ? hop : d <= 20 ? target.at() : Location.create(here.getX() + (int) Math.round((target.x - here.getX()) * 20 / d), here.getY() + (int) Math.round((target.y - here.getY()) * 20 / d), 0);
                    try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
                }
                return;
            }
            Scenery obj = null;
            try { obj = RegionManager.getObject(target.z, target.x, target.y); } catch (Throwable ignored) { }
            if (obj == null || obj.getId() != target.id) { before = here; record(false, here, "object gone"); return; }
            before = here;
            try { scriptAPI.interact(bot, obj, target.option); } catch (Throwable t) { record(false, here, "could not use: " + t); return; }
            used = true; waitUntil = now + 12_000;
        }
    }

    /** Every scenery object within r tiles on the bot's floor that offers one of the options. */
    static List<Scenery> scan(Location c, int r, String... options) {
        List<Scenery> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
            Scenery s;
            try { s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy); } catch (Throwable t) { continue; }
            if (s == null || s.getDefinition() == null) continue;
            String[] opts = s.getDefinition().getOptions();
            if (opts == null) continue;
            for (String o : opts) if (o != null) for (String want : options) if (o.equalsIgnoreCase(want)) { out.add(s); break; }
        }
        out.sort((a, b) -> Double.compare(a.getLocation().getDistance(c), b.getLocation().getDistance(c)));
        return out;
    }

    static String firstOption(Scenery s, String... options) {
        for (String want : options) for (String o : s.getDefinition().getOptions()) if (o != null && o.equalsIgnoreCase(want)) return o;
        return null;
    }

    static JSONArray loc(Location l) { JSONArray a = new JSONArray(); if (l != null) { a.add(l.getX()); a.add(l.getY()); a.add(l.getZ()); } return a; }

    /** batch 19b (design 11): queued for the SurveyWriter thread (no file I/O here); the JSON is serialised on the caller's thread; callers update their in-memory sets first. */
    static void write(String file, JSONObject j) {
        try { SurveyWriter.append(new File(new File(ServerConstants.DATA_PATH, "survey"), file).getPath(), j.toJSONString()); }
        catch (Throwable t) { System.out.println("[Explore] could not queue " + file + ": " + t); }
    }

    static final class Explorer extends Script {
        final boolean stairs;
        int site, climbsHere, records;
        long nextThink, waitUntil;
        Location target, before;
        Scenery pending; String pendingOption; int pendingId, pendingRot;
        final Set<String> tried = TESTED; // shared by every explorer and carried across restarts
        int sitesSinceRecord, recordsAtLastSite; boolean retired;
        double bestD = Double.MAX_VALUE; int stall;

        /** Walking to the site but not getting closer for ~70 s (a door, a fence, a river): data collectors just teleport there. */
        boolean stalled(Location to) {
            double d = bot.getLocation().getDistance(to);
            if (d < bestD - 2) { bestD = d; stall = 0; return false; }
            if (++stall < 40) return false;
            stall = 0; bestD = Double.MAX_VALUE;
            Location w = Surveyors.Surveyor.walkable(to);
            try { Spectacle.teleport(bot, w != null ? w : to); } catch (Throwable ignored) { } // batch 15 item1
            if (Boolean.getBoolean("afk.debug")) System.out.println("[Explore] " + bot.getUsername() + " stalled on the way to " + to + ": teleported");
            return true;
        }

        /** Nothing new in a whole round of sites: this surveyor's work is done for now. */
        void retire() {
            retired = true;
            System.out.println("[Explore] " + bot.getUsername() + " retired: nothing new to record at " + sitesSinceRecord + " " + (stairs ? "stair" : "door") + " sites (" + records + " recorded this time" + (stairs ? "" : "; " + DOOR_STAMP.size() + " doors/gates settled or recorded") + ")");
            if (!stairs) DOORS_RETIRED.incrementAndGet(); // batch 18 rc3: for the trial
            BotChat.clearActivity(bot);
            try { ((AIPlayer) bot).clear(); } catch (Throwable t) { System.out.println("[Explore] could not retire " + bot.getUsername() + ": " + t); }
        }

        Explorer(boolean stairs, int idx) { this.stairs = stairs; this.site = idx; }
        @Override public Script newInstance() { return null; }

        void walk(Location to) {
            Location here = bot.getLocation();
            if (NavWalker.ON) { Location nav = null; try { nav = NavWalker.step(bot, to); } catch (Throwable ignored) { } if (nav != null) { try { ((AIPlayer) bot).walkToPosSmart(nav); } catch (Throwable ignored) { } return; } }
            double d = here.getDistance(to);
            Location step = d <= 25 ? to : Location.create(here.getX() + (int) Math.round((to.getX() - here.getX()) * 25 / d), here.getY() + (int) Math.round((to.getY() - here.getY()) * 25 / d), here.getZ());
            try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
        }

        void nextSite() {
            int[][] list = stairs ? SITES : TOWNS;
            bestD = Double.MAX_VALUE; stall = 0;
            if (records == recordsAtLastSite) sitesSinceRecord++; else sitesSinceRecord = 0;
            recordsAtLastSite = records;
            if (sitesSinceRecord >= (stairs ? list.length : DOOR_ROUND)) { retire(); return; } // batch 18 rc3: doors retire after one round with nothing new (was two)
            site = (site + 1 + (stairs ? 0 : RNG.nextInt(3))) % list.length;
            target = Location.create(list[site][0], list[site][1], 0);
            climbsHere = 0;
            if (bot.getLocation().getZ() != 0 || bot.getLocation().getDistance(target) > 400) { try { Spectacle.teleport(bot, target); } catch (Throwable ignored) { } } // batch 15 item1
        }

        @SuppressWarnings("unchecked")
        void finishPending() {
            Location after = bot.getLocation();
            JSONObject j = new JSONObject();
            j.put("t", System.currentTimeMillis()); j.put("bot", bot.getUsername());
            j.put("object", pending.getName()); j.put("id", pendingId); j.put("option", pendingOption); j.put("at", loc(pending.getLocation()));
            j.put("from", loc(before)); j.put("to", loc(after));
            JSONArray opts = new JSONArray(); for (String o : pending.getDefinition().getOptions()) if (o != null) opts.add(o); j.put("options", opts);
            if (stairs) {
                if (pendingOption.equalsIgnoreCase("Open")) {
                    Scenery nowObj = null; try { nowObj = RegionManager.getObject(pending.getLocation().getZ(), pending.getLocation().getX(), pending.getLocation().getY()); } catch (Throwable ignored) { }
                    boolean climbable = !scan(pending.getLocation(), 1, "Climb-down", "Climb-into", "Enter").isEmpty(); // the open version may sit on a neighbouring tile
                    j.put("result", (nowObj != null && nowObj.getId() != pendingId) || climbable ? "opened" : "would not open");
                    write("stairs.jsonl", j); records++; pending = null; nextThink = System.currentTimeMillis() + 600; return;
                }
                boolean unreached = after.getZ() == before.getZ() && after.getY() < 6000 && after.getDistance(pending.getLocation()) > 2;
                if (unreached) {
                    j.put("result", "never reached (door or wall in the way)");
                    write("stairs.jsonl", j); records++;
                    String rk = key(pending.getLocation()) + "#" + pendingId;
                    if (RETRIED.add(rk)) { // open the nearest closed door on the way and try once more
                        for (Scenery d : scan(pending.getLocation(), 8, "Open")) { try { scriptAPI.interact(bot, d, "Open"); } catch (Throwable ignored) { } break; }
                        tried.remove(rk);
                    }
                    pending = null; nextThink = System.currentTimeMillis() + 4_000; return;
                }
                boolean moved = after.getZ() != before.getZ() || after.getDistance(before) > 4;
                boolean underground = after.getY() > 6000 && before.getY() < 6000, upground = before.getY() > 6000 && after.getY() < 6000; // dungeons sit 6400 tiles north
                j.put("result", underground ? "down (underground)" : upground ? "up (to surface)" : moved ? (after.getZ() > before.getZ() ? "up" : after.getZ() < before.getZ() ? "down" : "moved") : "nothing (dialog or locked?)");
                write("stairs.jsonl", j);
                if (moved || underground || upground) climbsHere++;
            } else {
                Scenery now = null; try { now = RegionManager.getObject(pending.getLocation().getZ(), pending.getLocation().getX(), pending.getLocation().getY()); } catch (Throwable ignored) { }
                boolean changed = now == null || now.getId() != pendingId || now.getRotation() != pendingRot;
                if (!changed) for (Scenery n : scan(pending.getLocation(), 1, "Close")) { changed = true; break; } // opened doors swing onto the next tile and offer "Close"
                j.put("result", changed ? "opened" : "stayed shut (locked, quest or key?)");
                String st = doorStamp(pendingId); j.put("stamp", st); DOOR_STAMP.put(doorKey(pending.getLocation(), pendingId, pendingOption), st); // batch 18 rc3: settled under this stamp
                if (!changed) try { Gates.shutDoor(j, pending.getLocation()); } catch (Throwable ignored) { } // a quest, key or toll door
                write("doors.jsonl", j); // batch 19b: after DOOR_STAMP/Gates.SHUT (in memory first)
            }
            records++;
            if (records % 10 == 0) System.out.println("[Explore] " + bot.getUsername() + " has recorded " + records + " " + (stairs ? "stairs/ladders" : "doors/gates"));
            pending = null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void tick() {
            if (bot == null || !bot.isActive() || retired) return;
            long now = System.currentTimeMillis();
            if (now < nextThink) return;
            nextThink = now + 1_800;
            if (pending != null) { if (now >= waitUntil) finishPending(); return; }
            if (target == null) { nextSite(); if (retired) return; }
            Location here = bot.getLocation();
            if (stairs) {
                if (here.getZ() == 0 && here.getY() < 6400 && here.getDistance(target) > 6) { if (stalled(target)) return; if (!bot.getWalkingQueue().isMoving()) walk(target); return; } // pathing review 10-23 E-1: underground (y > 6400) is another floor too: it walked ~6,400 tiles at the surface site until stalled() teleported it, and never recorded the way up
                if (climbsHere >= 6) { nextSite(); return; }
                for (Scenery s : scan(here, 10, CLIMB)) {
                    String key = key(s.getLocation()) + "#" + s.getId();
                    if (tried.contains(key)) continue;
                    tried.add(key);
                    String opt = firstOption(s, here.getZ() == 0 ? new String[]{"Climb-up", "Climb-down", "Climb", "Climb-into", "Enter", "Go-down", "Go-up"} : new String[]{"Climb-down", "Climb-up", "Climb", "Go-down", "Go-up"});
                    if (opt == null && hatch(s)) opt = firstOption(s, "Open"); // open it first; the opened one (new id) is found on the next scan
                    if (opt == null) continue;
                    before = here; pending = s; pendingOption = opt; pendingId = s.getId();
                    try { scriptAPI.interact(bot, s, opt); } catch (Throwable t) { pending = null; continue; }
                    waitUntil = now + (pendingOption.equalsIgnoreCase("Open") ? 3_000 : 9_000);
                    return;
                }
                nextSite(); // nothing new to climb here
                return;
            }
            // Doors: try any untested door or gate within 6 tiles, otherwise keep walking between towns.
            for (Scenery s : scan(here, 6, "Open", "Pay-toll", "Pay-toll(10gp)", "Unlock", "Knock-on", "Pick-lock")) {
                String open = firstOption(s, "Open");
                String key = doorKey(s.getLocation(), s.getId(), open); // batch 18 rc3: tile/id/option, settled ones are not retried (was the tile, this boot only after the seed)
                if (tried.contains(key) || doorSettled(key, s.getId())) continue;
                tried.add(key);
                if (open == null) { // toll, key or quest gate: note it, don't pay
                    JSONObject j = new JSONObject(); j.put("t", now); j.put("bot", bot.getUsername()); j.put("object", s.getName()); j.put("id", s.getId()); j.put("at", loc(s.getLocation()));
                    JSONArray opts = new JSONArray(); for (String o : s.getDefinition().getOptions()) if (o != null) opts.add(o); j.put("options", opts); j.put("result", "special (not used)");
                    String st = doorStamp(s.getId()); j.put("stamp", st); DOOR_STAMP.put(key, st); // batch 18 rc3
                    try { Gates.shutDoor(j, s.getLocation()); } catch (Throwable ignored) { }
                    write("doors.jsonl", j); records++; // batch 19b: after DOOR_STAMP/Gates.SHUT (in memory first)
                    continue;
                }
                before = here; pending = s; pendingOption = open; pendingId = s.getId(); pendingRot = s.getRotation();
                try { scriptAPI.interact(bot, s, open); } catch (Throwable t) { pending = null; continue; }
                waitUntil = now + 9_000; // the bot has to walk up to the door first; 5 s was often too short
                return;
            }
            if (here.getDistance(target) <= 5) { nextSite(); return; }
            if (stalled(target)) return;
            if (!bot.getWalkingQueue().isMoving()) walk(target);
        }
    }
}
