package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.bots.GeneralBotCreator;
import core.game.bots.Script;
import core.game.bots.SkillingBotAssembler;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
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
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * batch 18 item2-3 (2026-10-25): scenarios, the walks that went wrong on live, and the rescue replayers that walk them again.
 *
 * FORMAT (the spare-PC runner reads the same two files; one JSON object per line, appended, never rewritten):
 *   data/survey/scenarios.jsonl
 *     {"v":1,"t":<ms>,"id":"<source|walker|fromCell|toCell>","source":"rescue|never_reached|failed_trip|idle_giveup|quest_fail|no_route|redirect|seed|prove",
 *      "walker":"afk|shop|quest|grave|trader|crew|party|other","activity":"<the bot's activity line>","from":[x,y,z],"to":[x,y,z],
 *      "stuckAt":[x,y,z] or null,"reason":"<short reason: the issue it proves>","profile":{"combat":n,"agility":n,"coins":n}}
 *     a cell is "x>>3,y>>3,z" (8x8 tiles); the same id is written at most once per UTC day (again the same day only after a pass/fail result
 *     for it), at most 500 records a UTC day (record()); "prove" rows are one-off seeds (destinations with no result yet), outside that cap.
 *   data/survey/scenario-results.jsonl
 *     {"v":1,"t":<ms>,"id":"<scenario id>","bot":"<name>","result":"pass|fail|gave_up|skipped","ticks":n,"failAt":[x,y,z] or null,
 *      "why":"<short>","nav":"<planner/walker trace summary, profile match>","lag":<seconds from the scenario's record to this result>}
 *
 * The to-do list: a scenario is proven once it has a pass, or a fail with its failing tile; it comes back only when the same id is recorded again
 * after that result (it failed again on live). A single gave_up (10 minutes) and skipped do not prove it; gave_up on 3 different UTC days with
 * no pass/fail between closes it as a fail ("gave up 3 days running", no failAt; derived from scenario-results.jsonl at load, no extra line;
 * batch 18 rc3). One attempt per scenario per UTC day; a skip
 * (a real player came near, attacked, a crossing that costs coins) is not an attempt and is retried later. Live sources rank first
 * (rescue, never_reached, failed_trip, idle_giveup, quest_fail), then the rest (no_route, redirect, seed), then "prove"; newest first in each;
 * every 4th pick (one counter for all replayers) takes the oldest eligible scenario instead (fair queue, batch 18 rc3).
 *
 * Replayers (-Dsurvey.replay, default 3): ephemeral bots (never saved, not Watchdog's: Watchdog.ours() only knows smart adventurers,
 * traders and the herb bot) that arrive at the scenario's from tile by Spectacle.teleport, then walk to its destination with the same walker
 * (afk: the AfkTasks.Runner walk without its teleports; shop: the shop walk without teleports or buying; otherwise NavWalker / RouteMap
 * stepping), never teleporting during the replay. pass: arrived; fail: no progress for 60 s (failAt = where it stalled); gave_up: 10 minutes.
 * Only while no real player is within 20 tiles of the replayer or of the scenario's from/to tiles (checked every tick: skipped if one comes near).
 * Coin and item neutral: no buying, no tolls, no boats; NPC aggression skips a replayer (the engine's "ignore_aggression" attribute, set
 * from its teleport to the start until the result, batch 18 rc3); attacked anyway = skipped. Agility and melee levels are set to the scenario's profile
 * (the replayer is our own throwaway bot); coins are never given, the mismatch goes in "nav".
 * Flags: -Dsurvey.replay=3, -Dsurvey.replay.prove=true (seed the "prove" destinations), -Dsurvey.replay.debug (per-bot trace lines; also afk.debug),
 * -Dscenarios.dir (folder of scenarios.jsonl, default data/survey), -Dscenarios.results.dir (folder of scenario-results.jsonl, default scenarios.dir).
 * Full spec with examples: docs/scenario-format.md.
 */
public final class Scenarios implements StartupListener {
    static final Timer TIMER = new Timer("Scenarios", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");
    static final int REPLAY = Math.min(Integer.getInteger("survey.replay.max", 3), Integer.getInteger("survey.replay", 3)); // batch 18: at most 3 replayers; batch 21 G: -Dsurvey.replay.max raises the cap on the farm (the cloud run used 40)
    static final boolean PROVE = !"false".equalsIgnoreCase(System.getProperty("survey.replay.prove", "true"));
    static final boolean DEBUG = Boolean.getBoolean("survey.replay.debug") || Boolean.getBoolean("afk.debug");
    static final int CAP = 500, NEAR = 20, STALL_TICKS = 100; // 60 s without progress
    // batch 21 item 4 (B6 2336,3636 from Edgeville, ~1,400 tiles): -Dsurvey.replay.limit=<ticks> lets a long trip replay past
    // 10 minutes (1000 ticks, the default and the floor; at most 6000 = 1 hour). Farm/trial flag only; live keeps the default.
    static final int LIMIT_TICKS = Math.max(1000, Math.min(6000, Integer.getInteger("survey.replay.limit", 1000)));
    static String limitText() { return LIMIT_TICKS == 1000 ? "not there after 10 minutes" : "not there after the " + LIMIT_TICKS + "-tick limit"; }
    static final String[] LIVE = {"rescue", "never_reached", "failed_trip", "idle_giveup", "quest_fail"};
    static final String[] OTHER = {"no_route", "redirect", "seed"};

    /** One scenario (the newest record of its id). */
    static final class Sc {
        String id, source, walker, activity, reason; int[] from, to, stuck; long t; int combat = -1, agility = -1; long coins = -1;
    }
    /** What the results say about an id. */
    static final class Res { long doneT; String done; long attemptDay = -1; String last; int attempts; long gaveDay = -1; int gaveRun; long gave3T, gaveT; } // batch 18 rc3: gaveDay/gaveRun = UTC days of gave_up with no pass/fail between; gave3T = the gave_up that closed it as a fail
    static final int GAVE_DAYS = 3; static final String GAVE_WHY = "gave up 3 days running"; // batch 18 rc3: 3 daily timeouts = fail
    static final String CALM = "ignore_aggression"; // batch 18 rc3: AggressiveHandler.selectTarget skips a Player with this attribute true (only the replayer bot, removed at its result)
    static int picks; // batch 18 rc3: fair queue, every 4th pick the oldest (game thread only: Replayer.pick)

    static final Map<String, Sc> INDEX = new ConcurrentHashMap<>();
    static final Map<String, Res> RESULTS = new ConcurrentHashMap<>();
    static final Map<String, Long> TODAY = new ConcurrentHashMap<>(); // id -> t of today's record (dedupe)
    static final Object DAY = new Object();
    static long curDay = -1; static int dayCount; // guarded by DAY
    static volatile int dupes, capped, queued;
    static final ConcurrentLinkedQueue<String> OUT_S = new ConcurrentLinkedQueue<>(), OUT_R = new ConcurrentLinkedQueue<>();
    static final Set<String> CLAIMED = ConcurrentHashMap.newKeySet();
    static final Map<String, Long> SKIP_UNTIL = new ConcurrentHashMap<>();
    static final List<Replayer> ACTIVE = new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile boolean loaded;

    static File dir() { return new File(ServerConstants.DATA_PATH, "survey"); }
    /** batch 18: -Dscenarios.dir points a farm world at the shared scenarios folder; -Dscenarios.results.dir its results (default: the scenarios dir). */
    static File sDir() { String d = System.getProperty("scenarios.dir"); return d != null && !d.isEmpty() ? new File(d) : dir(); }
    static File rDir() { String d = System.getProperty("scenarios.results.dir"); return d != null && !d.isEmpty() ? new File(d) : sDir(); }
    static File scenariosFile() { return new File(sDir(), "scenarios.jsonl"); }
    static File resultsFile() { return new File(rDir(), "scenario-results.jsonl"); }
    static long day(long ms) { return Math.floorDiv(ms, 86_400_000L); }

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        try { dir().mkdirs(); load(); } catch (Throwable t) { System.out.println("[Replay] could not load: " + t); }
        TIMER.scheduleAtFixedRate(new TimerTask() { @Override public void run() { flush(); } }, 5_000, 5_000);
        if (PROVE) TIMER.schedule(new TimerTask() { @Override public void run() { // batch 18 review 1: on the game thread (walkable() can load regions and write the clipping map)
            GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { try { seedProve(); } catch (Throwable t) { System.out.println("[Replay] prove seeding failed: " + t); Watchdog.fexc("Scenarios.seedProve", null, t); } return true; } });
        } }, FAST ? 20_000 : 90_000);
        if (REPLAY > 0) TIMER.schedule(new TimerTask() { @Override public void run() {
            GameWorld.getPulser().submit(new Pulse(50) { @Override public boolean pulse() { try { schedule(); } catch (Throwable t) { System.out.println("[Replay] scheduler: " + t); Watchdog.fexc("Scenarios.schedule", null, t); } return false; } });
        } }, FAST ? 40_000 : 120_000);
        System.out.println("[Replay] " + REPLAY + " rescue replayers (survey.replay), prove seeding " + (PROVE ? "on" : "off") + "; " + counts() + "; data in data/survey/scenarios.jsonl, scenario-results.jsonl");
    }

    // ------------------------------------------------------------------ recording (any thread, never throws)

    static String cell(Location l) { return (l.getX() >> 3) + "," + (l.getY() >> 3) + "," + l.getZ(); }
    static String id(String source, String walker, Location from, Location to) { return source + "|" + walker + "|" + cell(from) + "|" + cell(to); }

    /** batch 18 item2: the shared API (b18-shared.md). Queue + background append; dedupe by id per UTC day; 500 a day. */
    public static void record(Player bot, String source, String walker, String activity, Location from, Location to, Location stuckAt, String reason) {
        record(bot, source, walker, activity, from, to, stuckAt, reason, null);
    }

    /** batch 19a B2: the same, with the failure record's id (the line gets "fid"; null = the batch 18 line). */
    public static void record(Player bot, String source, String walker, String activity, Location from, Location to, Location stuckAt, String reason, String fid) {
        try { put(bot, source, walker, activity, from, to, stuckAt, reason, -1, -1, -1, false, fid); } catch (Throwable ignored) { }
    }

    static boolean put(Player bot, String source, String walker, String activity, Location from, Location to, Location stuckAt, String reason, int combat, int agility, long coins, boolean seed) { return put(bot, source, walker, activity, from, to, stuckAt, reason, combat, agility, coins, seed, null); } // batch 19a

    @SuppressWarnings("unchecked")
    static boolean put(Player bot, String source, String walker, String activity, Location from, Location to, Location stuckAt, String reason, int combat, int agility, long coins, boolean seed, String fid) {
        if (from == null || to == null) return false;
        source = source == null ? "other" : source.toLowerCase(Locale.ROOT); walker = walker == null ? "other" : walker.toLowerCase(Locale.ROOT);
        String id = id(source, walker, from, to);
        long now = System.currentTimeMillis();
        synchronized (DAY) {
            long d = day(now);
            if (d != curDay) { curDay = d; TODAY.clear(); dayCount = 0; }
            Long prev = TODAY.get(id); Res r = RESULTS.get(id);
            if (prev != null && (r == null || r.doneT <= prev)) { dupes++; return false; }
            if (seed && INDEX.containsKey(id)) return false;
            if (!seed && dayCount >= CAP) { capped++; return false; }
            TODAY.put(id, now); if (!seed) dayCount++;
        }
        if (bot != null) {
            try { agility = bot.getSkills().getStaticLevel(Skills.AGILITY); } catch (Throwable ignored) { }
            try { combat = bot.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
            try { coins = Ledger.Wallet.coins(bot); } catch (Throwable ignored) { }
        }
        JSONObject j = new JSONObject();
        j.put("v", 1); j.put("t", now); j.put("id", id); j.put("source", source); j.put("walker", walker); j.put("activity", activity == null ? "" : activity);
        j.put("from", arr(from)); j.put("to", arr(to)); j.put("stuckAt", stuckAt == null ? null : arr(stuckAt)); j.put("reason", reason == null ? "" : reason);
        JSONObject pr = new JSONObject(); pr.put("combat", combat); pr.put("agility", agility); pr.put("coins", coins); j.put("profile", pr);
        if (fid != null) j.put("fid", fid); // batch 19a: additive (batch 18 readers take known keys only)
        Sc s = new Sc(); s.id = id; s.source = source; s.walker = walker; s.activity = activity; s.reason = reason; s.t = now;
        s.from = xyz(from); s.to = xyz(to); s.stuck = stuckAt == null ? null : xyz(stuckAt); s.combat = combat; s.agility = agility; s.coins = coins;
        INDEX.put(id, s);
        OUT_S.add(j.toJSONString());
        queued++;
        if (!seed || DEBUG) System.out.println("[Replay] queued " + id + " " + source + " from " + tile(s.from) + " to " + tile(s.to));
        return true;
    }

    @SuppressWarnings("unchecked")
    static JSONArray arr(Location l) { JSONArray a = new JSONArray(); a.add(l.getX()); a.add(l.getY()); a.add(l.getZ()); return a; }
    @SuppressWarnings("unchecked")
    static JSONArray arr(int[] l) { JSONArray a = new JSONArray(); for (int v : l) a.add(v); return a; }
    static int[] xyz(Location l) { return new int[]{l.getX(), l.getY(), l.getZ()}; }
    static String tile(int[] a) { return a == null ? "null" : a[0] + "," + a[1] + "," + a[2]; }
    static Location loc(int[] a) { return Location.create(a[0], a[1], a[2]); }

    /** Background append (the Scenarios timer), like Surveyors.flush. */
    static synchronized void flush() {
        try {
            if (OUT_S.isEmpty() && OUT_R.isEmpty()) return;
            sDir().mkdirs(); rDir().mkdirs();
            append(scenariosFile(), OUT_S); append(resultsFile(), OUT_R);
        } catch (Throwable t) { System.out.println("[Replay] could not write: " + t); }
    }

    static void append(File f, ConcurrentLinkedQueue<String> q) throws Exception {
        if (q.isEmpty()) return;
        try (FileWriter w = new FileWriter(f, true)) { for (String s; (s = q.poll()) != null; ) { w.write(s); w.write('\n'); } }
    }

    // ------------------------------------------------------------------ reading

    /** batch 18 item2: every well-formed line of a .jsonl file (bad lines skipped), oldest first. */
    public static List<JSONObject> readLines(File f) {
        List<JSONObject> out = new ArrayList<>();
        if (f == null || !f.exists()) return out;
        JSONParser p = new JSONParser();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) { line = line.trim(); if (line.isEmpty()) continue; try { out.add((JSONObject) p.parse(line)); } catch (Throwable ignored) { } }
        } catch (Throwable t) { System.out.println("[Replay] could not read " + f.getName() + ": " + t); }
        return out;
    }
    public static List<JSONObject> readScenarios() { return readLines(scenariosFile()); }
    public static List<JSONObject> readResults() { return readLines(resultsFile()); }

    static int[] ints(Object o) {
        if (!(o instanceof JSONArray)) return null;
        JSONArray a = (JSONArray) o; if (a.size() < 3) return null;
        return new int[]{((Number) a.get(0)).intValue(), ((Number) a.get(1)).intValue(), ((Number) a.get(2)).intValue()};
    }
    static long num(Object o, long d) { return o instanceof Number ? ((Number) o).longValue() : d; }

    static void load() {
        long today = day(System.currentTimeMillis());
        int rows = 0;
        for (JSONObject j : readScenarios()) {
            try {
                Sc s = new Sc(); s.id = String.valueOf(j.get("id")); s.t = num(j.get("t"), 0); s.source = String.valueOf(j.get("source")); s.walker = String.valueOf(j.get("walker"));
                s.activity = String.valueOf(j.get("activity")); s.reason = String.valueOf(j.get("reason")); s.from = ints(j.get("from")); s.to = ints(j.get("to")); s.stuck = ints(j.get("stuckAt"));
                Object pr = j.get("profile");
                if (pr instanceof JSONObject) { s.combat = (int) num(((JSONObject) pr).get("combat"), -1); s.agility = (int) num(((JSONObject) pr).get("agility"), -1); s.coins = num(((JSONObject) pr).get("coins"), -1); }
                if (s.from == null || s.to == null) continue;
                Sc old = INDEX.get(s.id); if (old == null || old.t <= s.t) INDEX.put(s.id, s);
                if (day(s.t) == today) { TODAY.put(s.id, Math.max(s.t, TODAY.getOrDefault(s.id, 0L))); if (!"prove".equals(s.source)) dayCount++; }
                rows++;
            } catch (Throwable ignored) { }
        }
        curDay = today;
        RESULTS.putAll(loadResults(readResults())); // batch 18 rc3: in time order (the 3-day gave_up rule depends on it)
        int closed3 = 0; for (Res r : RESULTS.values()) if (r.gave3T > 0 && r.doneT == r.gave3T) closed3++;
        loaded = true;
        System.out.println("[Replay] read " + rows + " scenario rows (" + INDEX.size() + " ids, " + dayCount + " today) and " + RESULTS.size() + " ids with results (" + closed3 + " closed as fail: " + GAVE_WHY + ")");
    }

    /** batch 18 rc3: results rows sorted by time into a fresh map (a reload never counts twice); load() and tests. */
    static Map<String, Res> loadResults(List<JSONObject> rows) {
        List<JSONObject> l = new ArrayList<>(rows); l.sort(java.util.Comparator.comparingLong(j -> num(j.get("t"), 0)));
        Map<String, Res> m = new ConcurrentHashMap<>();
        for (JSONObject j : l) { try { noteResult(m, String.valueOf(j.get("id")), String.valueOf(j.get("result")), num(j.get("t"), 0)); } catch (Throwable ignored) { } }
        return m;
    }

    static boolean noteResult(String id, String result, long t) { return noteResult(RESULTS, id, result, t); }

    /** batch 18 rc3: true when this gave_up closed the id as a fail (gave_up on GAVE_DAYS different UTC days, no pass/fail between); Watchdog.Daily uses its own map. */
    static boolean noteResult(Map<String, Res> m, String id, String result, long t) {
        Res r = m.computeIfAbsent(id, k -> new Res());
        r.last = result; boolean closed = false;
        if ("pass".equals(result) || "fail".equals(result)) { if (t >= r.doneT) { r.doneT = t; r.done = result; } r.gaveRun = 0; r.gaveDay = -1; } // batch 18 rc3: a pass/fail ends a gave_up run
        else if ("gave_up".equals(result)) { Sc rec = INDEX.get(id); if (rec != null && r.gaveT > 0 && rec.t > r.gaveT) { r.gaveRun = 0; r.gaveDay = -1; } r.gaveT = Math.max(r.gaveT, t); } // batch 18 rc3 (owner's call 15:40): a live re-record after the last gave_up starts the 3-day run again
        if ("gave_up".equals(result) && day(t) > r.gaveDay) { r.gaveDay = day(t); if (++r.gaveRun >= GAVE_DAYS && t >= r.doneT) { r.doneT = t; r.done = "fail"; r.gave3T = t; closed = true; } } // batch 18 rc3: the 3rd day closes it (proven fail)
        if (!"skipped".equals(result)) { r.attemptDay = Math.max(r.attemptDay, day(t)); r.attempts++; }
        return closed;
    }

    /** Proven (pass, or fail with its tile) and not recorded again since. */
    static boolean proven(Sc s) { Res r = RESULTS.get(s.id); return r != null && r.doneT > 0 && r.doneT >= s.t; }

    /** batch 18 item3: still on the to-do list and allowed now (not tried today, not cooling down after a skip, not being replayed). */
    public static boolean toReplay(String id) {
        Sc s = INDEX.get(id);
        if (s == null || proven(s) || CLAIMED.contains(id)) return false;
        if (rank(s.source) < 0) return false;
        Res r = RESULTS.get(id);
        if (r != null && r.attemptDay == day(System.currentTimeMillis())) return false; // one attempt per scenario per UTC day
        Long until = SKIP_UNTIL.get(id);
        return until == null || until <= System.currentTimeMillis();
    }

    static int rank(String source) {
        for (String l : LIVE) if (l.equals(source)) return 0;
        for (String l : OTHER) if (l.equals(source)) return 1;
        return "prove".equals(source) ? 2 : -1; // anything else (test fillers) is never replayed
    }

    static Sc next() { return next(false); }

    /** batch 18 rc3: oldest = the oldest eligible scenario of any rank (fair queue); else live first, newest first. */
    static Sc next(boolean oldest) {
        Sc best = null;
        for (Sc s : INDEX.values()) {
            if (!toReplay(s.id)) continue;
            if (best == null || (oldest ? s.t < best.t : rank(s.source) < rank(best.source) || rank(s.source) == rank(best.source) && s.t > best.t)) best = s;
        }
        return best;
    }

    /** batch 18 rc3: the replayers' pick: every 4th (one counter for all of them, game thread) takes the oldest. */
    static Sc choose() { return next(++picks % 4 == 0); }

    public static String counts() {
        int passed = 0, failed = 0, todo = 0;
        for (Sc s : INDEX.values()) {
            if (proven(s)) { if ("pass".equals(RESULTS.get(s.id).done)) passed++; else failed++; }
            else if (rank(s.source) >= 0) todo++;
        }
        return INDEX.size() + " scenarios total, " + passed + " passed, " + failed + " failed (proven), " + todo + " still to replay";
    }

    // ------------------------------------------------------------------ "prove" seeds: destinations bots ask for, no result yet

    /** batch 18 (the owner 02:38): surface shops, banks and job spots with no passing result, plus the known never-reached shops. */
    static void seedProve() {
        int n = 0;
        String[][] known = {{"Wydin's", "known never-reached shop (Wydin's Food Store)"}, {"Gerrant's", "known never-reached shop (Gerrant's Fishy Business)"}, {"Warriors' Guild Food", "known never-reached shop (Warriors' Guild food shop)"}};
        for (Object[] sh : new ArrayList<>(BotChat.WorldData.shops)) {
            Location to = Location.create((Integer) sh[1], (Integer) sh[2], 0);
            String name = String.valueOf(sh[0]);
            String why = null; for (String[] k : known) if (name.startsWith(k[0])) why = k[1];
            if (why != null) { if (seedOne("never_reached", "shop", name, to, why)) n++; continue; }
            if (!Gates.at(to).isEmpty()) continue; // gated places: builder E's
            if (seedOne("prove", "shop", name, to, "prove: walk to the shop " + name)) n++;
        }
        for (Location b : AfkTasks.BANKS) if (seedOne("prove", "afk", "bank", b, "prove: walk to the bank at " + b.getX() + "," + b.getY())) n++;
        for (AfkTasks.Spot[] arr : new ArrayList<>(AfkTasks.SPOTS.values())) for (AfkTasks.Spot sp : arr)
            if (sp != null && sp.at.getZ() == 0 && sp.at.getY() < 6400 && Gates.at(sp.at).isEmpty() && seedOne("prove", "afk", sp.name, sp.at, "prove: walk to the job spot " + sp.name)) n++;
        System.out.println("[Replay] " + n + " prove/never-reached destinations seeded; " + counts());
    }

    /** A realistic start: the nearest bank (other than the destination) when within 200 tiles, else a walked tile 150 tiles towards it. */
    /**
     * batch 21 B9: is bank b inside a gate box (a guild, a quest town) that the goal is not in? The prove walks to Rasolo
     * (2536,3426) started in the Fishing Guild and the Karamja General Store walk started in Shilo Village, so they
     * measured the gate, not the walk.
     */
    static boolean gatedStart(Location b, Location to) {
        for (Gates.Gate g : Gates.at(b)) if (!g.contains(to)) return true;
        return false;
    }

    static boolean seedOne(String source, String walker, String what, Location to, String reason) {
        if (to.getZ() != 0 || to.getY() >= 6400) return false;
        Location bank = null; double bd = Double.MAX_VALUE;
        for (Location b : AfkTasks.BANKS) { double d = b.getDistance(to); if (d > 8 && d < bd && !gatedStart(b, to)) { bd = d; bank = b; } } // batch 21 B9: not a bank inside a gate box the goal is not in
        if (bank == null) return false;
        Location from = bank;
        if (bd > 200) {
            Location c = Location.create(to.getX() + (int) Math.round((bank.getX() - to.getX()) * 150 / bd), to.getY() + (int) Math.round((bank.getY() - to.getY()) * 150 / bd), 0);
            Location w = null; try { w = RouteMap.walkedNear(c, 10); } catch (Throwable ignored) { }
            if (w == null) w = Surveyors.Surveyor.walkable(c);
            if (w == null) return false;
            from = w;
        }
        if (AfkTasks.Runner.acrossSea(from, to)) return false;
        if (Preflight.island(from, to) != null) return false; // batch 21: no prove walk to an island the walker can't reach (the Void Knight shops were seeded from Ape Atoll)
        if (Preflight.wilderness(from, to) != null) return false; // batch 21 B6: nor past the Wilderness ditch (Battle Runes and the cape shops were seeded from Edgeville)
        return put(null, source, walker, "walking to " + what, from, to, null, reason, -1, -1, -1, true);
    }

    // ------------------------------------------------------------------ replayers

    static void schedule() {
        ACTIVE.removeIf(r -> r.bot == null || !r.bot.isActive() || r.retired);
        if (ACTIVE.size() >= REPLAY) return;
        int free = 0; for (Sc s : INDEX.values()) if (toReplay(s.id)) { free++; break; }
        if (free == 0 && !EconReplayer.shopReachDue()) return; // batch 19c B4: a shop-reach bug also wants a replayer
        spawn();
    }

    static void spawn() {
        AIPlayer bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(3222, 3218, 0));
        SmartAdventurers.finishTutorial(bot);
        Replayer r = new Replayer();
        new GeneralBotCreator(r, bot);
        r.bot = bot; ACTIVE.add(r);
        GameWorld.getPulser().submit(new Pulse(1) {
            @Override public boolean pulse() {
                if (!bot.isActive() && !r.retired) { r.retired = true; ACTIVE.remove(r); if (r.sc != null) r.end("skipped", null, "the replayer was gone (logged out or cleared)"); return true; } // batch 18 review 2: frees its CLAIMED id
                if (r.retired) return true;
                long t0 = System.nanoTime(); // batch 19c B3: this pulse's cost
                try { r.step(); } catch (Throwable t) { System.out.println("[Replay] " + bot.getUsername() + ": " + t); Watchdog.fexc("Scenarios.Replayer.step", bot, t); r.end("skipped", null, "error " + t); } Watchdog.fpulse("Scenarios.Replayer", bot, System.nanoTime() - t0); return false; } // batch 19a B3 // batch 19c B3
        });
        BotChat.activity(bot, () -> r.sc == null ? "checking a road" : "walking a road that gave someone trouble");
        System.out.println("[Replay] " + bot.getUsername() + " set out as a rescue replayer; " + counts());
    }

    /** A real player within NEAR tiles of a spot (same floor). */
    static boolean playerNear(Location at) {
        if (at == null) return false;
        for (Player h : Repository.getPlayers())
            if (h != null && h.isActive() && !h.isArtificial() && h.getLocation().getZ() == at.getZ() && h.getLocation().getDistance(at) <= NEAR) return true;
        return false;
    }

    /** The Al Kharid toll gate would charge here (AfkTasks.Runner.tollHop's test, without paying). */
    static boolean atToll(Location here, Location dest) {
        if (dest == null || here.getZ() != 0 || Math.abs(here.getX() - 3268) > 4 || here.getY() < 3224 || here.getY() > 3231) return false;
        return dest.getX() >= 3268 && here.getX() <= 3267 || dest.getX() <= 3267 && here.getX() >= 3268; // batch 21 B2: the sides as tollHop has them (x 3268 is east)
    }

    static final class Replayer extends Script {
        Sc sc; boolean retired;
        int phase, ticks, waitTicks, bestTick, idleTicks; // phase 0 pick, 1 travel to the start, 2 landing, 3 walking
        Location from, to, aim, lastHere; double best; long coins0; int items0, lp0;
        long loan, spent; // batch 21 D: coins lent for this replay, and what the walk itself paid (toll, teleports)
        boolean paying; // review fix 5: replay.pay on and the walk's coins in hand (lent in begin(), before any pre-flight): the planner may route through the toll gate, as the live walkers' do
        String label = "", profileNote = "";
        final Map<String, Integer> labels = new LinkedHashMap<>();
        final Set<Integer> blocks = new java.util.HashSet<>();
        AfkTasks.Runner r; Location[] ent; int sinceProgress, retries; long shortcutSince;
        int shopStalls; double shopBest; Location shopLast, viaLast; int viaTurns;
        String plannerNote = "";

        @Override public void tick() { }
        @Override public Script newInstance() { return null; }

        void step() {
            if (bot == null || !bot.isActive()) return;
            if (phase == 0) { if (++idleTicks < 5) return; idleTicks = 0; pick(); return; }
            Location here = bot.getLocation();
            if (phase < 3 && bot.inCombat()) { end("skipped", null, "attacked before the replay began (no fights)"); retire("attacked"); return; }
            if (phase == 1) {
                if (here.getDistance(from) <= 1 && here.getZ() == from.getZ()) { begin(); return; }
                if (playerNear(here) || playerNear(from) || playerNear(to)) { if (++waitTicks > 500) end("skipped", null, "real players near the start for 5 minutes"); return; }
                bot.getPulseManager().clear(); bot.getWalkingQueue().reset();
                bot.setAttribute(CALM, true); // batch 18 rc3: aggressive NPCs skip the replayer from the landing to its result (end() removes it)
                try { Spectacle.teleport(bot, from); } catch (Throwable t) { end("skipped", null, "could not relocate: " + t); return; }
                phase = 2; waitTicks = 0; return;
            }
            if (phase == 2) {
                if (here.getDistance(from) <= 1 && here.getZ() == from.getZ()) { begin(); return; }
                if (++waitTicks > 10) end("skipped", null, "did not land at the start (at " + here + ")");
                return;
            }
            walkTick(here);
        }

        void pick() {
            boolean fair = false; Sc s = EconReplayer.takeShopReach(); // batch 19c B4: a shop-reach first (coin and item neutral), else the scenario list
            if (s == null) { fair = (picks + 1) % 4 == 0; s = choose(); } // batch 18 rc3: fair queue
            if (s == null) { retire("nothing left to replay"); return; }
            if (!CLAIMED.add(s.id)) return;
            if (fair && DEBUG) System.out.println("[Replay] " + bot.getUsername() + " fair queue: the oldest eligible, " + s.id);
            sc = s; from = loc(s.from); to = loc(s.to);
            if (!PAY && AfkTasks.Runner.acrossSea(from, to)) { SKIP_UNTIL.put(s.id, System.currentTimeMillis() + 86_400_000L); end("skipped", null, "across the sea: the crossing costs coins (boat or teleport)"); return; } // batch 21 D: a paying replay crosses
            phase = 1; waitTicks = 0;
            if (DEBUG) System.out.println("[Replay] " + bot.getUsername() + " takes " + s.id + " (" + s.reason + ")");
        }

        void retire(String why) {
            retired = true; ACTIVE.remove(this);
            try { bot.removeAttribute(CALM); } catch (Throwable ignored) { } // batch 18 rc3: reversible
            System.out.println("[Replay] " + bot.getUsername() + " retired: " + why + " (" + counts() + ")");
            try { ((AIPlayer) bot).clear(); } catch (Throwable t) { System.out.println("[Replay] could not retire " + bot.getUsername() + ": " + t); }
        }

        /** At the start: the scenario's profile where it is safe (our own throwaway bot), then the walk. */
        void begin() {
            profile();
            bot.getPulseManager().clear(); bot.getWalkingQueue().reset();
            bot.setAttribute(CALM, true); // batch 18 rc3: also when it was already at the start (no teleport)
            String k = bot.getUsername().toLowerCase(Locale.ROOT);
            try { NavWalker.forget(bot); RouteMap.PLANS.remove(k); RouteMap.USING.remove(k); } catch (Throwable ignored) { }
            loan = 0; spent = 0; paying = false;
            if (PAY) { long want = Preflight.fare() * 2 + 50, have = Ledger.Wallet.coins(bot); if (have < want && Ledger.Wallet.give(bot, want - have)) loan = want - have; paying = loan > 0 || have >= want; } // batch 21 D: lent, taken back in end(); review fix 5: first, so every pre-flight (payTick) and plan sees the coins
            coins0 = Ledger.Wallet.coins(bot); items0 = items(); lp0 = bot.getSkills().getLifepoints();
            ticks = 0; bestTick = 0; best = Double.MAX_VALUE; labels.clear(); blocks.clear(); label = ""; plannerNote = "";
            sinceProgress = 0; retries = 0; shortcutSince = 0; shopStalls = 0; shopBest = Double.MAX_VALUE; shopLast = null; viaLast = null; viaTurns = 0;
            aim = to; ent = null;
            if ("afk".equals(sc.walker)) {
                r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false);
                r.crossOnly = true; r.walker = true; // finish() then only halts it: no bank walk, no selling (coin/item neutral)
                if (to.getY() > 6400 && from.getY() < 6400) { ent = AfkTasks.Runner.entrance(to); if (ent != null) aim = ent[1]; } // in through the town, like goTo()
                if (PAY && AfkTasks.Runner.essenceMine(to) && !AfkTasks.Runner.essenceMine(from)) { ent = new Location[]{null, Location.create(3253, 3401, 0), to}; aim = ent[1]; } // batch 21 D: Aubury teleports you in
                r.dest = aim;
            } else r = null;
            phase = 3; lastHere = bot.getLocation();
            if (DEBUG) System.out.println("[Replay] " + bot.getUsername() + " replays " + sc.id + ": " + from + " -> " + to + " (" + sc.walker + ", " + profileNote + ")");
        }

        int[] base; // the replayer's own levels at its first scenario: put back before each profile (reversible)
        static final int[] PROFILE_SKILLS = {Skills.AGILITY, Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS};

        void profile() {
            StringBuilder n = new StringBuilder();
            Skills sk = bot.getSkills();
            if (base == null) { base = new int[PROFILE_SKILLS.length]; for (int i = 0; i < base.length; i++) base[i] = sk.getStaticLevel(PROFILE_SKILLS[i]); }
            for (int i = 0; i < base.length; i++) { sk.setStaticLevel(PROFILE_SKILLS[i], base[i]); sk.setLevel(PROFILE_SKILLS[i], base[i]); }
            if (sc.agility > 0) { int a = Math.max(1, Math.min(99, sc.agility)); sk.setStaticLevel(Skills.AGILITY, a); sk.setLevel(Skills.AGILITY, a); }
            if (sc.combat > 0) {
                int l = Math.max(1, Math.min(99, (int) Math.round(sc.combat / 1.15)));
                for (int s : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE}) { sk.setStaticLevel(s, l); sk.setLevel(s, l); }
                int hp = Math.max(10, l); sk.setStaticLevel(Skills.HITPOINTS, hp); sk.setLevel(Skills.HITPOINTS, hp);
            }
            try { sk.updateCombatLevel(); } catch (Throwable ignored) { }
            try { sk.setLifepoints(sk.getMaximumLifepoints()); } catch (Throwable ignored) { }
            int cb = -1; try { cb = bot.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
            n.append("profile agility ").append(sk.getStaticLevel(Skills.AGILITY)).append(sc.agility > 0 ? " (scenario " + sc.agility + ")" : " (own; scenario has none)").append(", combat ").append(cb).append(sc.combat > 0 ? " (scenario " + sc.combat + ")" : " (own)");
            long c = Ledger.Wallet.coins(bot);
            if (sc.coins >= 0 && sc.coins != c) n.append(", coins ").append(c).append(" (scenario had ").append(sc.coins).append(", never given)");
            profileNote = n.toString();
        }

        int items() { int h = 0; try { for (Item it : bot.getInventory().toArray()) if (it != null) h = h * 31 + it.getId() * 7 + it.getAmount(); } catch (Throwable ignored) { } return h; }

        void note(String what, Location here) {
            if (what == null || what.isEmpty()) return;
            String k = what.replaceAll("[-0-9,]+", "#").trim(); // "step 25 to 3100,3240" -> "step # to #"
            labels.merge(k, 1, Integer::sum);
            if (!k.equals(label)) { label = k; Watchdog.flog(bot, what); if (DEBUG) System.out.println("[Replay] " + bot.getUsername() + " tick " + ticks + " at " + here.getX() + "," + here.getY() + "," + here.getZ() + ": " + what); } // batch 19a design 3.2: label changes into the log ring
        }

        void walkTick(Location here) {
            ticks++;
            if (playerNear(here) || playerNear(from) || playerNear(to)) { SKIP_UNTIL.put(sc.id, System.currentTimeMillis() + 15 * 60_000L); end("skipped", null, "a real player came within " + NEAR + " tiles"); return; }
            if (bot.inCombat() || bot.getSkills().getLifepoints() < lp0) { SKIP_UNTIL.put(sc.id, System.currentTimeMillis() + 30 * 60_000L); end("skipped", null, "attacked on the way (no fights)"); retire("attacked"); return; } // batch 18: logs out rather than fight (a new replayer comes next round; staying in combat skipped scenario after scenario)
            if (Ledger.Wallet.coins(bot) + spent != coins0 || items() != items0) { end("skipped", null, "coins or items changed"); return; } // batch 21 D: less what the walk paid itself
            if (PAY && payTick(here)) return; // batch 21 D: the toll, a sea crossing, a teleport out (Morytania west, off the grid, stranded)
            if (lastHere != null && lastHere.getDistance(here) > 20) note("jumped " + lastHere + " -> " + here, here); // a ladder (ours) or something else moved it
            lastHere = here;
            if (ent != null && aim == ent[1] && here.getDistance(aim) <= 2) { // at the ladder or trapdoor: climb down like a player (AfkTasks.Runner.walk)
                try { bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(827)); } catch (Throwable ignored) { }
                bot.getProperties().setTeleportLocation(ent[2]); aim = to; if (r != null) r.dest = to; best = Double.MAX_VALUE; bestTick = ticks; blocks.clear(); note("climbed down", here); lastHere = ent[2]; return;
            }
            double dist = here.getZ() == aim.getZ() ? here.getDistance(aim) : 9_999;
            boolean shop = "shop".equals(sc.walker);
            if (aim == to && (dist <= 2 || shop && (dist <= 5 || dist <= 9 && shopStalls >= 2))) { end("pass", null, "arrived"); return; }
            int block = ((here.getX() >> 2) << 16) ^ (here.getY() >> 2) ^ (here.getZ() << 30);
            if (dist < best - 1) { best = dist; bestTick = ticks; }
            if (blocks.add(block)) bestTick = Math.max(bestTick, ticks); // new ground counts as progress (a detour round a river)
            if (ticks - bestTick >= STALL_TICKS) { end("fail", here, "no progress for 60 s, " + Math.round(here.getDistance(to)) + " tiles short"); return; }
            if (ticks >= LIMIT_TICKS) { end("gave_up", null, limitText() + ", " + Math.round(here.getDistance(to)) + " tiles short"); return; }
            if (atToll(here, aim)) { end("skipped", null, "at the Al Kharid toll gate (coin neutral; builder E's)"); return; }
            if (ticks % 25 == 0) planner();
            if (r != null) afkWalk(here, dist);
            else if (shop) { if (ticks % 4 == 0) shopWalk(here); }
            else otherWalk(here, dist);
        }

        /** What the planners hold for this bot right now (the "nav" summary). */
        void planner() {
            String k = bot.getUsername().toLowerCase(Locale.ROOT);
            String p = "";
            try { NavWalker.State s = NavWalker.STATES.get(k); if (s != null && s.route != null) p += "planner route " + s.route.hops.size() + " hops; "; } catch (Throwable ignored) { }
            try { Long nr = NavWalker.NO_ROUTE.get(NavWalker.noRouteKey(bot, aim)); if (nr != null && nr > System.currentTimeMillis()) p += "planner: no route; "; } catch (Throwable ignored) { }
            try { if (RouteMap.PLANS.containsKey(k)) p += "learned route; "; } catch (Throwable ignored) { }
            if (!p.isEmpty() && !plannerNote.contains(p)) plannerNote = (plannerNote + p).length() > 200 ? plannerNote : plannerNote + p;
        }

        /** AfkTasks.Runner.walk without its teleports, finish() or bank walk: shortcuts, the stuck/shake-loose rule, then Runner.step. */
        void afkWalk(Location here, double dist) {
            if (RouteMap.usingShortcut(bot)) {
                if (shortcutSince == 0) shortcutSince = System.currentTimeMillis();
                try { RouteMap.nextHop(bot, aim); } catch (Throwable ignored) { }
                if (RouteMap.usingShortcut(bot) && System.currentTimeMillis() - shortcutSince < 15_000) { sinceProgress = 0; bestTick = ticks; note("using a shortcut", here); return; }
            }
            shortcutSince = 0;
            if (dist < r.best - 0.5) { r.best = dist; sinceProgress = 0; }
            else if (++sinceProgress > 40) {
                try { RouteMap.stuck(bot, aim); } catch (Throwable ignored) { }
                sinceProgress = 0; retries++;
                try { r.api.randomWalkTo(here, 6); } catch (Throwable ignored) { }
                note("shake loose", here); return;
            }
            if (!bot.getWalkingQueue().isMoving() && !bot.getPulseManager().hasPulseRunning()) { r.dest = aim; r.step(dist); note(r.last, here); }
        }

        /** SmartAdventurers.Brain.shopTick's walk without its teleports, food or buying. */
        void shopWalk(Location here) {
            if (bot.getWalkingQueue().isMoving()) return;
            Location target = to;
            try { Location w = RouteMap.walkedNear(target, 3); if (w != null) target = w; } catch (Throwable ignored) { }
            Location via = viaTurns > 6 ? null : AfkTasks.Runner.bridgeVia(bot, here, target); if (via != null && here.getDistance(via) > 3) target = via;
            if (via != null && !via.equals(viaLast)) { viaLast = via; viaTurns++; note("via " + via, here); }
            Location pv = AfkTasks.Runner.passVia(here, to); if (pv != null) target = pv; // batch 21 B2: across the Shantay Pass: to the pass first (SmartAdventurers' shop walk does the same)
            double d = here.getDistance(target);
            Location hop = null; if (NavWalker.ON) { try { hop = NavWalker.step(bot, target, paying); } catch (Throwable ignored) { } } // review fix 5: a paying replay plans through the toll gate like SmartAdventurers' shop walk (payTick pays)
            boolean mapHop = hop == null;
            if (hop != null) note("nav", here);
            if (hop == null) try { hop = RouteMap.nextHop(bot, target); if (hop != null) note("route", here); } catch (Throwable ignored) { }
            if (mapHop && hop != null && (hop.equals(here) || RouteMap.usingShortcut(bot))) { shopStalls = 0; shopLast = here; bestTick = ticks; note("using a shortcut", here); return; }
            if (hop != null) { if (shopLast == null || here.getDistance(shopLast) >= 2) { shopStalls = 0; shopLast = here; } else shopStalls++; shopBest = Math.min(shopBest, d); }
            else if (d < shopBest - 2) { shopBest = d; shopStalls = 0; } else shopStalls++;
            if (shopStalls >= 3 && hop != null) { try { RouteMap.stuck(bot, target); } catch (Throwable ignored) { } hop = null; note("route stuck", here); }
            if (shopStalls >= 2 && hop == null) {
                double ang = Math.atan2(target.getY() - here.getY(), target.getX() - here.getX()) + (AfkTasks.RNG.nextBoolean() ? 1 : -1) * (Math.PI / 2 + AfkTasks.RNG.nextDouble() * 0.6);
                Location side = Surveyors.Surveyor.walkable(Location.create(here.getX() + (int) Math.round(Math.cos(ang) * 18), here.getY() + (int) Math.round(Math.sin(ang) * 18), 0));
                if (side != null) { try { ((AIPlayer) bot).walkToPosSmart(side); } catch (Throwable ignored) { } shopStalls = 0; note("side step", here); return; }
            }
            Location step = hop != null ? hop : d <= 25 ? target : Location.create(here.getX() + (int) Math.round((target.getX() - here.getX()) * 25 / d), here.getY() + (int) Math.round((target.getY() - here.getY()) * 25 / d), 0);
            Location w = d <= 25 || hop != null ? null : Surveyors.Surveyor.walkable(step);
            if (hop == null) { note("straight", here); Watchdog.fdrv(bot, Failures.DRV_OLD); } // batch 19a design 3.1
            try { ((AIPlayer) bot).walkToPosSmart(w != null ? w : step); } catch (Throwable ignored) { }
        }

        /** Quest, grave, trader, crew, party and other walks: NavWalker, then the learned routes, then a straight 25-tile hop. */
        void otherWalk(Location here, double dist) {
            if (RouteMap.usingShortcut(bot)) { try { RouteMap.nextHop(bot, aim); } catch (Throwable ignored) { } bestTick = ticks; note("using a shortcut", here); return; }
            if (bot.getWalkingQueue().isMoving() || bot.getPulseManager().hasPulseRunning()) return;
            Location hop = null;
            if (NavWalker.ON) { try { hop = NavWalker.step(bot, aim, paying); } catch (Throwable ignored) { } if (hop != null) note("nav", here); } // review fix 5: as the quest Mover (payer when paying)
            if (hop == null) { try { hop = RouteMap.nextHop(bot, aim); } catch (Throwable ignored) { } if (hop != null) { if (hop.equals(here)) { note("using a shortcut", here); return; } note("route", here); } }
            if (hop == null) {
                hop = dist <= 25 ? aim : Location.create(here.getX() + (int) Math.round((aim.getX() - here.getX()) * 25 / dist), here.getY() + (int) Math.round((aim.getY() - here.getY()) * 25 / dist), here.getZ());
                Location w = dist <= 25 ? null : Surveyors.Surveyor.walkable(hop); if (w != null) hop = w;
                note("straight", here); Watchdog.fdrv(bot, Failures.DRV_OLD); // batch 19a design 3.1
            }
            try { ((AIPlayer) bot).walkToPosSmart(hop); } catch (Throwable ignored) { }
        }

        /**
         * batch 19c (coordinator, live 19a data): the replayer knows why it skipped or failed; the path classifier does not. A hint at the
         * front of the record's why, "{replay:CODE/RSUB} ": CODE a 19a/19c reason the classifier takes as decided (rsub RSUB), or "-" = keep
         * the classifier's reason and use RSUB only when it comes out "unknown". Read by Failures.classify (Builder A's side of the contract;
         * a build without it just shows the hint in why). "" when no rule fits. Rules on the replayer's own texts, first match.
         */
        static final String[][] REPLAY_HINTS = {
                {"attacked", "combat-interrupt", "attacked"},
                {"at the Al Kharid toll gate", "rule-gate", "alkharid-toll"},
                {"across the sea", "rule-gate", "sea-crossing"},
                {"could not relocate", "handoff", "relocate-failed"},
                {"did not land at the start", "handoff", "did-not-land"},
                {"real player came within", "-", "player-near"},
                {"real players near the start", "-", "player-near"},
                {"coins or items changed", "-", "coin-neutral-abort"},
                {"the replayer was gone", "-", "replayer-gone"},
                {"error ", "-", "replay-error"},
                {"no progress for 60 s", "-", "no-progress"},
                {"not there after 10 minutes", "-", "not-there-10min"},
                {"-tick limit", "-", "not-there-limit"}}; // batch 21 item 4: a raised -Dsurvey.replay.limit
        static String replayTag(String why) {
            if (why == null) return "";
            for (String[] h : REPLAY_HINTS) if ("error ".equals(h[0]) ? why.startsWith(h[0]) : why.contains(h[0])) return "{replay:" + h[1] + "/" + h[2] + "} ";
            return "";
        }

        /** batch 19a B4 (logging half): {fid, bug}. A failure record for fail/gave_up/skipped (src replay_fail/replay_timeout/replay_skip); replayResult for the scenario's open bug (pass too). Never throws; -Dfailures=false: {null, null}. */
        String[] failureFor(String result, Location failAt, String why, String nav) {
            String[] out = new String[2];
            if (!Watchdog.FAIL_ON || sc == null || bot == null) return out;
            if (EconReplayer.SHOP_REACH.equals(sc.source)) return out; // batch 20 (19b+c follow-up 2): a shop-reach's outcome is its econ bug's replay (shopReachEnd); no replay_skip/replay_fail record or second replayResult
            try {
                String src = "fail".equals(result) ? "replay_fail" : "gave_up".equals(result) ? "replay_timeout" : "skipped".equals(result) ? "replay_skip" : null;
                if (src != null) {
                    Watchdog.Goal g = new Watchdog.Goal(); g.walker = "replay"; g.kind = sc.walker + " replay"; g.from = from; g.to = to; g.last = label; g.nav = nav; g.navShort = plannerNote.isEmpty() ? "none" : "route";
                    out[0] = Watchdog.fail(bot, src, sc.id, g, failAt != null ? failAt : bot.getLocation(), replayTag(why) + why); // batch 19c (live 19a: 6 of 13 records "unknown"): the hook's reason hint
                }
                out[1] = Failures.openBugFor(sc.id);
                if (out[1] != null) Failures.replayResult(out[1], sc.id, bot.getUsername(), result, out[0], why);
            } catch (Throwable ignored) { }
            return out;
        }

        @SuppressWarnings("unchecked")
        static final boolean PAY = !"false".equals(System.getProperty("replay.pay")); // batch 21 D kill switch

        /**
         * batch 21 D: the replay walker pays what the live walker pays, with coins lent at the start and taken back at the end
         * (coin neutral), so the trips Batch 20 handles by paying (the reverse Salve teleport, off-grid starts, stranded bots)
         * and the toll and sea trips are replayed instead of failing on foot or being skipped. True = this tick is used.
         */
        boolean payTick(Location here) {
            if (bot.getProperties().getTeleportLocation() != null) return true; // a paid teleport lands next tick
            if (atToll(here, aim)) {
                long c = Ledger.Wallet.coins(bot); int t = AfkTasks.Runner.tollHop(bot, aim); spent += c - Ledger.Wallet.coins(bot);
                if (t > 0) { note("paid the toll", here); return true; }
                if (t < 0 && AfkTasks.Runner.tollStuck(bot, aim)) { end("skipped", null, "at the Al Kharid toll gate without the 10 gp"); return true; } // review fix 2: else round the fence as a non-payer, like the live walkers
            }
            { // batch 21 B2: the Shantay Pass, as the live walkers cross it (5 gp south)
                long c = Ledger.Wallet.coins(bot); int t = AfkTasks.Runner.passHop(bot, aim, false); spent += c - Ledger.Wallet.coins(bot);
                if (t > 0) { note("through the Shantay Pass", here); bestTick = ticks; return true; }
                if (t < 0) { end("skipped", null, "at the Shantay Pass without the 5 gp"); return true; }
            }
            if (AfkTasks.Runner.acrossSea(here, aim)) {
                long c = Ledger.Wallet.coins(bot); boolean paid = Ledger.Wallet.payTeleport(bot); spent += c - Ledger.Wallet.coins(bot);
                if (!paid) { end("skipped", null, "across the sea without the fare"); return true; }
                Spectacle.teleport(bot, Watchdog.landing(aim)); note("teleported across the sea", here); return true;
            }
            if (ent == null || aim != ent[1]) {
                long c = Ledger.Wallet.coins(bot); Preflight.Go g = Preflight.out(bot, aim); spent += c - Ledger.Wallet.coins(bot);
                if (g == Preflight.Go.BUSY) { note("pre-flight: climbing or teleporting out", here); bestTick = ticks; return true; }
                if (g == Preflight.Go.FAILED) { end("fail", here, "pre-flight: can't get out (no stair and no fare)"); return true; }
            }
            return false;
        }

        /** batch 21 D: the loan back (what the walk did not spend); the bot's own coins are untouched. */
        void repay() {
            if (loan <= 0) return;
            long back = Math.min(Math.max(0, loan - spent), Ledger.Wallet.coins(bot));
            if (back > 0) Ledger.Wallet.pay(bot, back);
            loan = 0; spent = 0;
        }

        void end(String result, Location failAt, String why) {
            try { repay(); } catch (Throwable ignored) { } // batch 21 D
            Sc s = sc;
            if (s != null && EconReplayer.SHOP_REACH.equals(s.source)) EconReplayer.shopReachEnd(this, result, why); // batch 19c B4: the stock read and the bug's replay
            if (s != null) {
                long now = System.currentTimeMillis();
                long lag = Math.max(0, (now - s.t) / 1000);
                StringBuilder nav = new StringBuilder(sc.walker).append(" walker; ");
                int shown = 0; for (Map.Entry<String, Integer> e : labels.entrySet()) { if (shown++ >= 12) { nav.append(", ..."); break; } nav.append(shown > 1 ? ", " : "").append(e.getKey()).append(" x").append(e.getValue()); }
                if (!plannerNote.isEmpty()) nav.append("; ").append(plannerNote.replaceAll("; $", ""));
                if (!profileNote.isEmpty()) nav.append("; ").append(profileNote);
                if (best < Double.MAX_VALUE) nav.append("; closest ").append(Math.round(best)).append(" tiles");
                JSONObject j = new JSONObject();
                j.put("v", 1); j.put("t", now); j.put("id", s.id); j.put("bot", bot.getUsername()); j.put("result", result); j.put("ticks", ticks);
                j.put("failAt", failAt == null ? null : arr(failAt)); j.put("why", why); j.put("nav", nav.toString()); j.put("lag", lag);
                String[] fb = failureFor(result, failAt, why, nav.toString()); // batch 19a B4: replay_fail / replay_timeout / replay_skip record, before the state below is cleared
                if (fb[0] != null) j.put("fid", fb[0]); if (fb[1] != null) j.put("bug", fb[1]); // batch 19a: additive fields
                OUT_R.add(j.toJSONString());
                if (noteResult(s.id, result, now)) System.out.println("[Replay] " + s.id + " closed as fail: " + GAVE_WHY + " (not replayed again unless recorded again on live)"); // batch 18 rc3
                if ("skipped".equals(result)) SKIP_UNTIL.merge(s.id, now + 15 * 60_000L, Math::max); // retried later the same day, never in a loop
                System.out.println("[Replay] result " + s.id + " " + result + " after " + ticks + ", lag " + lag + " s" + (failAt != null ? " (stalled at " + failAt.getX() + "," + failAt.getY() + "," + failAt.getZ() + ")" : "") + ": " + why);
                CLAIMED.remove(s.id);
            }
            try { bot.getPulseManager().clear(); bot.getWalkingQueue().reset(); NavWalker.forget(bot); RouteMap.PLANS.remove(bot.getUsername().toLowerCase(Locale.ROOT)); } catch (Throwable ignored) { }
            try { bot.removeAttribute(CALM); } catch (Throwable ignored) { } // batch 18 rc3: only while replaying
            sc = null; r = null; phase = 0; idleTicks = 0;
        }
    }
}
