package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * batch 19a (the design notes §2-6, §14; owner 2026-10-25 15:22 "log why it's happening", 16:26 logging only, 16:33 generic domains):
 * failure records, bugs and the "Top open bugs" report. LOGGING ONLY: nothing here changes what a bot does.
 *
 * Generic format (docs/failure-format.md): every record and every bug carries {@code domain}, one of path, economy, quest, combat,
 * skilling, lifecycle, health, save. Reason codes belong to a domain ({@link #CODES}; path keeps the 14 of §4 and they are the only
 * codes the geometric classify() derives; health/lifecycle have "exception"; every other code is set by its domain's detector at the
 * hook and, per batch19c-design §2, is unique across domains, so a code alone names its domain; "unknown" exists in every domain and
 * never groups into a bug). Core keys (v t fid domain src sid bot snap reason rsub bug why log exc ctx) mean the same in every domain;
 * a domain adds its own keys (path: walker dtype from to drv plan path stall near combat mv gate stuckS tripS; others: the optional
 * ctx object) and readers skip keys they do not know, so a new domain needs no format change. Bug id = "b-" + reason + "-" + key:
 * path's key is the §5.1 cluster (dest- for no-route/outside-grid/timeout, + gate id for rule-gate); another domain's key is its
 * detector's slug (Rec.key, batch19c §3.3; the stall cluster when none is given); the reserved code "exception" keeps
 * b-exception-<where>-<Class>. The domain is a field of the bug entry, never part of its id. Caps (§6) are global (120/h, 1,500/day)
 * with per-domain sub-caps inside them (batch19c §3.4 figures; path keeps the whole 120/1,500 in 19a; exceptions are held by their own
 * throttle and 200/day instead of health's share); -Dfailures.cap.<domain>=perHour,perDay for trials. bugs.json holds at most 2,000
 * entries, per-domain sub-caps the same way, and keeps any entry key it does not know (a newer build's fields survive a rewrite).
 *
 * Threads: the sampler (Pulse(2), game thread) and the hooks (game thread) only touch fixed rings and build one Rec at a failure; JSON,
 * trimming to 4,096 bytes, file appends and bugs.json load-merge-save run on Builder B's SurveyWriter thread (SurveyWriter.task /
 * append: 2 s drain or 64 queued, shutdown flush with a 5 s bound); bugs.json is read and rewritten inside one task (Ledger.writeAtomic).
 * Counters (Watchdog.Daily "fail" = reason, "failcap" = src, "bug" = id, "exc" = where TAB class TAB first frame; the keys B's report
 * reads) and bug counts move for every failure, written or
 * capped. Hooks never throw. -Dfailures=false: every hook is a no-op, no sampler, no files. Real players are never recorded: the hooks
 * return at once unless the player is artificial, and an exception involving a real player is recorded without any bot data.
 */
public final class Failures implements StartupListener {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("failures", "true"));
    static final boolean DEBUG = Boolean.getBoolean("failures.debug");
    static final int BATCH = 19; // batch 19a: the running build's batch (the deploy bumps it): fixed-in-batch-N reopens only when BATCH >= N
    static final String HOST = System.getProperty("failures.host", "live"); // batch 19a: "live", or "farm-<world>" on a farm world
    static final boolean FARM = HOST.startsWith("farm");
    static final long BOOT = System.currentTimeMillis();
    static volatile boolean started;

    // ------------------------------------------------------------ domains and reason codes (batch 19a: generic, owner 16:33)
    static final String[] DOMAINS = {"path", "economy", "quest", "combat", "skilling", "lifecycle", "health", "save"};
    static final String[] PATH_CODES = {"death", "combat-interrupt", "timeout", "outside-grid", "rule-gate", "door-closed", "npc-blocking", "no-route",
            "handoff", "wrong-side-hop", "oscillation", "loop", "blocked-object", "engine-pathfinder-giveup", "trap-cell", "unknown"}; // batch 21 A: loop; E: trap-cell
    static final Map<String, List<String>> CODES = new LinkedHashMap<>();
    static {
        for (String d : DOMAINS) CODES.put(d, new ArrayList<>(Collections.singletonList("unknown")));
        CODES.put("path", new ArrayList<>(java.util.Arrays.asList(PATH_CODES)));
        CODES.get("health").add(0, "exception"); CODES.get("lifecycle").add(0, "exception");
    }
    static boolean domain(String d) { return CODES.containsKey(d); }

    // ------------------------------------------------------------ drivers (§3.1)
    static final byte DRV_NONE = 0, DRV_PLANNER = 1, DRV_ROUTEMAP = 2, DRV_OLD = 3, DRV_CLIMBER = 4, DRV_WATCHDOG = 5, DRV_FLEE = 6;
    static final String[] DRV_NAME = {"none", "planner", "routemap", "old", "climber", "watchdog", "flee"};

    // ------------------------------------------------------------ caps (§6), global with per-domain sub-caps
    static final int CAP_HOUR = Integer.getInteger("failures.capHour", 120), CAP_DAY = Integer.getInteger("failures.capDay", 1500);
    static final int SKIP_HOUR = 10, EXC_DAY = 200, LINE_MAX = 4096, BUG_CAP = 2000;
    static final long BOT_GAP_MS = 60_000L, EXC_GAP_MS = 5 * 60_000L;
    static final Map<String, int[]> DOMAIN_CAP = new HashMap<>();   // domain -> {per hour, per day} records
    static final Map<String, Integer> DOMAIN_BUGS = new HashMap<>(); // domain -> max bug entries
    static {
        for (String d : DOMAINS) DOMAIN_BUGS.put(d, 300);
        DOMAIN_BUGS.put("path", BUG_CAP);
        DOMAIN_CAP.put("path", new int[]{60, 600}); // batch 19c §3.4: path 60/h, 600/day (19a had the whole 120/1,500)
        DOMAIN_CAP.put("economy", new int[]{30, 300}); DOMAIN_CAP.put("quest", new int[]{15, 150}); DOMAIN_CAP.put("combat", new int[]{15, 150}); DOMAIN_CAP.put("skilling", new int[]{15, 150});
        DOMAIN_CAP.put("lifecycle", new int[]{10, 80}); DOMAIN_CAP.put("health", new int[]{5, 40}); DOMAIN_CAP.put("save", new int[]{5, 30});
        for (String d : DOMAINS) {
            String v = System.getProperty("failures.cap." + d);
            if (v != null) try { String[] a = v.split(","); DOMAIN_CAP.put(d, new int[]{Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim())}); } catch (Throwable ignored) { }
        }
    }
    // guarded by CAPS
    static final Object CAPS = new Object();
    static long capHour = -1, capDay = -1; static int hourN, dayN, skipHourN, excDayN;
    static final Map<String, int[]> DOMAIN_N = new HashMap<>(); // domain -> {this hour, today}
    static final Map<String, Long> LAST_BY = new HashMap<>();   // bot|src -> last written; where|class -> last exception written
    static final Map<String, Integer> BUG_DAY = new HashMap<>(); // batch 19c §3.2: server-level records written today per bug (≤ 6)
    static volatile int capped, throttled, written, recorded, dropped;

    // ------------------------------------------------------------ the per-bot trace (§3.1)
    static final byte TAG_RAW = 0, TAG_WALK = 1, TAG_REPLAY = 2;
    static final String[] TAG_PREFIX = {"", "walk: ", "replay: "};

    static final class Trace {
        final Player p; final String name;
        boolean traced; String role; Object rep; int seen;
        int[] tiles, at; byte[] drv; int head, count;      // packed x<<17|y<<2|z, the sample second it was entered, the driver then
        String[] log; byte[] logTag; int logHead, logCount;
        byte driver; long driverT;
        int hits, lastLp = -1, hitHead, lastDeaths = -1; long[] hitT; boolean died, relog, inCombat; long combatT;
        long tripT; int tripFrom = -1; Object tripRef;
        String lastLabel; long lastMoveT; int npcRun;
        int[] vt, vs; int vh; long loopT; // batch 21 A: tiles entered on a trip (packed) and the second each was entered, for the loop detector
        Trace(Player p, String name) { this.p = p; this.name = name; }
        void arm() { if (tiles != null) return; tiles = new int[32]; at = new int[32]; drv = new byte[32]; log = new String[12]; logTag = new byte[12]; hitT = new long[8]; }
        void putLog(String line, byte tag) { if (log == null || line == null) return; log[logHead] = line; logTag[logHead] = tag; logHead = (logHead + 1) % 12; if (logCount < 12) logCount++; }
        int newest() { return count == 0 ? -1 : tiles[(head + 31) & 31]; }
    }

    static final Map<Player, Trace> BY_BOT = Collections.synchronizedMap(new IdentityHashMap<>()); // the sampler's lookup (no name string per sample)
    static final Map<String, Trace> TRACES = new ConcurrentHashMap<>();                              // traced bots by lower-case name (§3.1)
    static final Map<Player, String> WATCHED = Collections.synchronizedMap(new IdentityHashMap<>()); // extra bots a hook asked for (explorers), role
    static final Map<String, Long> GONE = new ConcurrentHashMap<>();                                 // name -> when its trace was dropped (relog)
    static volatile long sampleNs, samples, sampleMaxNs, warmNs, warmSamples, npcNs, npcCalls; static int gen, npcBudget; static long lastCostPrint; // warm*: after the first 25 samples (class loading, JIT)

    static int pack(Location l) { return (l.getX() & 0x3FFF) << 17 | (l.getY() & 0x7FFF) << 2 | (l.getZ() & 3); }
    static int px(int t) { return t >>> 17; }
    static int py(int t) { return (t >>> 2) & 0x7FFF; }
    static int pz(int t) { return t & 3; }
    static int sec(long now) { return (int) ((now - BOOT) / 1000); }

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (!ON) return; // batch 19a: SurveyWriter.startup prints the on/off line (B7)
        if (settings == null || !settings.getEnable_bots()) return;
        try { Bugs.load(); } catch (Throwable t) { System.out.println("[Failures] could not read bugs.json: " + t); }
        fidBase = (int) ((System.currentTimeMillis() % 86_400_000L) / 1000) * 10; // batch 19a [Fable]: ids stay unique across restarts on one day
        Writer.start();
        GameWorld.getPulser().submit(new Pulse(2) {
            @Override public boolean pulse() { try { sample(); } catch (Throwable t) { if (DEBUG) System.out.println("[Failures] sampler: " + t); } return false; }
        });
        started = true;
        int[] st = Bugs.statusCounts();
        System.out.println("[Failures] tracing travelling bots; " + st[0] + " bugs open, " + st[1] + " not-reproduced, " + st[2] + " other; failures dir " + failDir().getPath() + ", bugs " + Bugs.file().getPath() + " (host " + HOST + ")");
    }

    static File failDir() { return new File(Scenarios.rDir(), "failures"); }

    // ------------------------------------------------------------ the sampler (game thread, every 1.2 s)
    static void sample() {
        long t0 = System.nanoTime(), now = System.currentTimeMillis(); int s = sec(now);
        gen++; npcBudget = 1;
        // batch 19a: only the bots that can travel (A1 measured 209 us a sample walking every player in the Repository; these are ~30)
        for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) visit(br.bot, "adventurer", null, now, s);
        for (TravellingTraders.Trader t : TravellingTraders.ALL) visit(t.bot, "trader", null, now, s);
        visit(ServiceBots.LIVE.get("herbs"), "herb", null, now, s);
        for (Scenarios.Replayer x : Scenarios.ACTIVE) visit(x.bot, "replayer", x, now, s);
        if (!WATCHED.isEmpty()) { Object[][] w; synchronized (WATCHED) { w = new Object[WATCHED.size()][]; int i = 0; for (Map.Entry<Player, String> e : WATCHED.entrySet()) w[i++] = new Object[]{e.getKey(), e.getValue()}; } for (Object[] e : w) visit((Player) e[0], (String) e[1], null, now, s); }
        if (gen % 25 == 0) { // every 30 s: forget bots that logged out or stopped being travellers (the retainAll of §3.1)
            synchronized (BY_BOT) {
                for (java.util.Iterator<Map.Entry<Player, Trace>> it = BY_BOT.entrySet().iterator(); it.hasNext(); ) {
                    Trace tr = it.next().getValue();
                    if (gen - tr.seen > 3) { it.remove(); if (TRACES.remove(tr.name, tr)) GONE.put(tr.name, now); if (!tr.p.isActive()) WATCHED.remove(tr.p); }
                }
            }
            if (GONE.size() > 500) GONE.values().removeIf(v -> now - v > 3_600_000L);
            synchronized (WATCHED) { WATCHED.keySet().removeIf(p -> !p.isActive()); }
        }
        Writer.maybeSave(now, false);
        long ns = System.nanoTime() - t0; sampleNs += ns; samples++; if (ns > sampleMaxNs) sampleMaxNs = ns; if (gen > 25) { warmNs += ns; warmSamples++; } RECENT[(int) (samples % 100)] = ns;
        if (DEBUG && now - lastCostPrint > 600_000L) { lastCostPrint = now; System.out.println("[Failures] sampler: " + TRACES.size() + " traced, mean " + meanUs() + " us (after warm-up " + warmUs() + " us, last 100 samples " + recentUs() + " us), max " + sampleMaxNs / 1000 + " us over " + samples + " samples; NPC checks " + npcCalls + " at " + (npcCalls == 0 ? 0 : npcNs / npcCalls / 1000) + " us"); }
    }

    static long meanUs() { return samples == 0 ? 0 : sampleNs / samples / 1000; }
    static final long[] RECENT = new long[100]; // the last 100 samples' cost (2 minutes)
    static long recentUs() { long t = 0; int n = (int) Math.min(100, samples); for (int i = 0; i < n; i++) t += RECENT[i]; return n == 0 ? 0 : t / n / 1000; }
    static long warmUs() { return warmSamples == 0 ? 0 : warmNs / warmSamples / 1000; }

    /** batch 19a: one travelling bot this sample (artificial and active only; first sight creates its Trace and name once). */
    static void visit(Player p, String role, Object rep, long now, int s) {
        if (p == null || !p.isActive() || !p.isArtificial()) return;
        Trace tr = BY_BOT.get(p);
        if (tr == null) {
            tr = new Trace(p, p.getUsername().toLowerCase(Locale.ROOT)); tr.arm(); BY_BOT.put(p, tr); TRACES.put(tr.name, tr);
            Long g = GONE.remove(tr.name); if (g != null && now - g < 30 * 60_000L) tr.relog = true;
        }
        if (tr.seen == gen) return; // listed twice (a replayer that is also watched)
        tr.seen = gen; tr.role = role; tr.rep = rep; tr.traced = true;
        one(tr, now, s);
    }

    static void one(Trace tr, long now, int s) {
        Player p = tr.p;
        Location l = p.getLocation();
        if (l == null) return;
        int pk = pack(l);
        AfkTasks.Runner r = AfkTasks.RUNNING.get(tr.name);
        SmartAdventurers.Brain br = "adventurer".equals(tr.role) ? SmartAdventurers.BRAINS.get(tr.name) : null;
        if (pk != tr.newest() && LOOP_ON && travelling(r, br) && !(tr.rep instanceof Scenarios.Replayer)) loopCheck(tr, l, pk, now, s); // batch 21 A
        if (pk != tr.newest()) { tr.tiles[tr.head] = pk; tr.at[tr.head] = s; tr.drv[tr.head] = tr.driver; tr.head = (tr.head + 1) & 31; if (tr.count < 32) tr.count++; tr.lastMoveT = now; tr.npcRun = 0; }
        else if (now - tr.lastMoveT >= 30_000L && ((gen + tr.name.hashCode()) & 3) == 0 && onTrip(tr, r, br) && npcBudget-- > 0) { long n0 = System.nanoTime(); List<NPC> n = RegionManager.getLocalNpcs(l, 1); tr.npcRun = n == null || n.isEmpty() ? 0 : tr.npcRun + 1; npcNs += System.nanoTime() - n0; npcCalls++; } // batch 19a: bots standing 30 s+ on a trip (a stall worth a record is 60 s+), each every 4th sample (~5 s), at most 1 a sample: A1 measured 24-68 us a check
        int lp = p.getSkills().getLifepoints();
        if (tr.lastLp >= 0 && lp < tr.lastLp) { tr.hits++; tr.hitT[tr.hitHead] = now; tr.hitHead = (tr.hitHead + 1) & 7; }
        if (lp <= 0 && tr.lastLp > 0) tr.died = true;
        tr.lastLp = lp;
        boolean c = p.inCombat(); tr.inCombat = c; if (c) tr.combatT = now;
        String lab = r != null ? r.last : tr.rep instanceof Scenarios.Replayer ? ((Scenarios.Replayer) tr.rep).label : null;
        if (lab != null && lab != tr.lastLabel) { String was = tr.lastLabel; tr.lastLabel = lab; if (!lab.isEmpty() && !lab.equals(was)) tr.putLog(lab, r != null ? TAG_WALK : TAG_REPLAY); } // batch 19a: same text rebuilt each tick is not a new line
        Object from = br != null && br.graveAt != null ? br.graveFrom : br != null && br.shopAt != null ? br.shopFrom : r != null ? r.walkFrom : null;
        if (from != tr.tripRef) { tr.tripRef = from; if (from != null) { tr.tripT = now; tr.tripFrom = pack((Location) from); tr.hits = 0; tr.died = false; } }
        if (br != null) { if (tr.lastDeaths >= 0 && br.deaths > tr.lastDeaths) tr.died = true; tr.lastDeaths = br.deaths; }
    }

    static final boolean LOOP_ON = !"false".equals(System.getProperty("loop.detect")); // batch 21 A kill switch
    static final int LOOP_VISITS = Integer.getInteger("loop.visits", 6), LOOP_SECS = 300; // batch 26 fix 5: was 4; the old walker's fan and shake-loose revisit tiles by design, so 4 fired before its own paid stall teleport could

    /**
     * batch 21 A: a bot on a trip that enters the same tile LOOP_VISITS times within 5 minutes is going round in circles,
     * however wide the circle (the Watchdog's anchor only notices loops inside 8 tiles: the Lumbridge east-bank loop ran
     * 3263,3230 to 3265,3242 and back for hours). It gets a "loop" record and a rescue, at most once in 10 minutes, but only
     * when the loop spans LOOP_SPAN tiles or more (review fix 1: the east-bank loop spanned 12; a sidestep spans 1-2).
     * Replayers are left to their own stall rule (they never teleport).
     */
    static void loopCheck(Trace tr, Location l, int pk, long now, int s) {
        if (tr.vt == null) { tr.vt = new int[128]; tr.vs = new int[128]; }
        long st = loopStat(tr.vt, tr.vs, tr.vh, pk, s, loopSince(tr));
        tr.vt[tr.vh] = pk; tr.vs[tr.vh] = s; tr.vh = (tr.vh + 1) & 127;
        int n = (int) (st >>> 32), span = (int) st;
        if (!loopRescue(n, span) || now - tr.loopT < 600_000L) return;
        tr.loopT = now;
        try { Watchdog.loopFound(tr.p, l, n, span); } catch (Throwable t) { if (DEBUG) System.out.println("[Failures] loop: " + t); }
    }

    /** review fix 1: the smallest loop (Chebyshev tiles) that counts. A 1-2 tile dodge round an NPC or a door never does. */
    static final int LOOP_SPAN = Integer.getInteger("loop.span", 6); // batch 26 fix 5: was 4

    static boolean loopRescue(int visits, int span) { return visits >= LOOP_VISITS && span >= LOOP_SPAN; }

    /** batch 21 D: visits count within one trip only (-Dloop.trip=false: across trips, as in batch 21 A). */
    static final boolean LOOP_TRIP = !"false".equals(System.getProperty("loop.trip"));

    /** batch 21 D: the second the current trip started (sec of Trace.tripT), or Integer.MIN_VALUE (no trip yet, or loop.trip off). */
    static int loopSince(Trace tr) { return LOOP_TRIP && tr != null && tr.tripT > 0 ? sec(tr.tripT) : Integer.MIN_VALUE; }

    /**
     * review fix 1: visits to pk (counting this one) inside the last LOOP_SECS, packed high, and the loop's span packed low:
     * the widest x or y extent of the tiles entered since the first of those visits, oldest first from the ring head vh.
     * The span is the loop itself, not the whole 5-minute path, so a dodge on a long walk keeps a span of 1-2.
     */
    static long loopStat(int[] vt, int[] vs, int vh, int pk, int s) { return loopStat(vt, vs, vh, pk, s, Integer.MIN_VALUE); }

    /**
     * batch 21 D (live loop review): only visits entered at or after the second {@code since} count (the current trip's
     * start, loopSince). The first 2 h on live gave 95 loop rescues; the leading cells (3249,3230, 3252,3227, 3249,3234)
     * sit on the way back to the Lumbridge goblins spot (3250,3235): a fight job attacks targets up to 18 tiles from its
     * spot, walks back (a new goTo, a new trip) once it is over 16 away, and every such short trip ends on the same few
     * tiles, so 4 kills at the edge in 5 minutes read as a loop and the working job was teleported out and ended. A real
     * loop (the east-bank loop, an exit-rule or door ping-pong, a shop walk going round) never arrives, so it stays one trip.
     */
    static long loopStat(int[] vt, int[] vs, int vh, int pk, int s, int since) {
        int n = 1; boolean in = false;
        int lo = px(pk), hi = lo, lo2 = py(pk), hi2 = lo2;
        for (int k = 0; k < 128; k++) {
            int i = (vh + k) & 127;
            if (vt[i] == 0 || s - vs[i] > LOOP_SECS || vs[i] < since) continue;
            if (vt[i] == pk) { n++; in = true; }
            if (!in) continue;
            int x = px(vt[i]), y = py(vt[i]); lo = Math.min(lo, x); hi = Math.max(hi, x); lo2 = Math.min(lo2, y); hi2 = Math.max(hi2, y);
        }
        return (long) n << 32 | Math.max(hi - lo, hi2 - lo2);
    }

    /** batch 21 A: walking somewhere on purpose (not fighting or skilling round a spot, not fleeing): the loop detector's scope. */
    static boolean travelling(AfkTasks.Runner r, SmartAdventurers.Brain br) {
        if (r != null && !r.halted && (r.state == AfkTasks.State.TO_BANK || r.state == AfkTasks.State.TO_SPOT || r.state == AfkTasks.State.TO_FURNACE)) return true;
        return br != null && br.shopAt != null;
    }

    static boolean onTrip(Trace tr, AfkTasks.Runner r, SmartAdventurers.Brain br) {
        if (r != null && (r.state == AfkTasks.State.TO_BANK || r.state == AfkTasks.State.TO_SPOT || r.state == AfkTasks.State.TO_FURNACE || r.state == AfkTasks.State.RETREAT)) return true;
        if (br != null && (br.shopAt != null || br.graveAt != null)) return true;
        return tr.rep != null || NavWalker.STATES.containsKey(tr.name);
    }

    static Trace trace(Player bot) { return bot == null ? null : BY_BOT.get(bot); }

    // ------------------------------------------------------------ hooks (§14 contract)

    /** batch 19a: the walker that is driving now (game thread; one map get, two stores). */
    static void driver(Player bot, byte drv) {
        if (!ON || bot == null) return;
        Trace t = BY_BOT.get(bot);
        if (t != null && t.traced) { t.driver = drv; t.driverT = System.currentTimeMillis(); }
    }

    /** batch 19a: one line into the bot's log ring (a reference; cut to 160 chars only when written out). */
    static void log(Player bot, String line) {
        if (!ON || bot == null || line == null) return;
        Trace t = BY_BOT.get(bot);
        if (t != null && t.traced) t.putLog(line, TAG_RAW);
    }

    /** batch 19a: a trip starts (walkers with *From fields call it; the sampler also notices those fields changing). */
    static void tripStart(Player bot, Location from) {
        if (!ON || bot == null || from == null) return;
        Trace t = BY_BOT.get(bot);
        if (t != null && t.traced) { t.tripT = System.currentTimeMillis(); t.tripFrom = pack(from); t.tripRef = from; t.hits = 0; t.died = false; }
    }

    /** batch 19a: trace a bot the sampler would not pick up itself (explorers: "explorer-offground", "explorer-door", "seeker"). Artificial only. */
    static void watch(Player bot, String role) {
        if (!ON || bot == null || !bot.isArtificial()) return;
        WATCHED.put(bot, role == null ? "other" : role);
    }

    /** §14: a path failure (domain "path"). Returns the fid when the record will be written, null when capped or off. Never throws. */
    static String failure(Player bot, String src, String sid, Watchdog.Goal g, Location stall, String why) { return failure(bot, "path", src, sid, g, stall, why); }

    /** batch 19a: the general form; domain is one of {@link #DOMAINS} (anything else is recorded as "health"). Never throws. */
    static String failure(Player bot, String domain, String src, String sid, Watchdog.Goal g, Location stall, String why) {
        if (!ON) return null;
        try {
            if (bot == null || !bot.isArtificial()) return null; // never a real player
            String dom = domain(domain) ? domain : "health";
            if (!on(dom)) { offN++; return null; } // batch 19c §1.6: -Dfailures.domains without this domain: nothing built or written (the reason is decided by the build, so only offN moves)
            if (!mayWrite(bot.getUsername(), dom, src)) { capped++; try { Watchdog.Daily.note("failcap", src); } catch (Throwable ignored) { } return null; } // batch 19a review S1: a capped or throttled record is not built at all
            Rec r = build(bot, dom, src, sid, g, stall, why);
            classify(r);
            r.bug = bugId(r);
            return accept(r);
        } catch (Throwable t) { complain("failure", t); return null; }
    }

    /** §14: an exception at an add-on catch site. Returns the fid or null (throttled: 1 per where+class per 5 min, 200 a day). Never throws. */
    static String exception(String where, Player bot, Throwable t) {
        if (!ON || t == null) return null;
        try {
            long now = System.currentTimeMillis();
            Rec r = new Rec();
            String w = where == null ? "unknown" : where;
            String wl = w.toLowerCase(Locale.ROOT);
            r.domain = wl.startsWith("startup") || wl.startsWith("shutdown") || wl.startsWith("login") || wl.startsWith("logout") ? "lifecycle" : "health";
            r.src = "exception"; r.t = now; r.why = cut(String.valueOf(t), 160);
            if (bot != null && bot.isArtificial()) { // a real player's exception is recorded without any data about them
                r.bot = bot.getUsername();
                Trace tr = trace(bot);
                boolean game = gameThread();
                Location here = bot.getLocation();
                if (here != null) r.stall = xyz(here);
                Watchdog.Goal g = game ? Watchdog.goal(bot) : null;
                r.snap = snapshot(bot, g, tr, here, game);
                if (tr != null && tr.traced) { r.drv = DRV_NAME[tr.driver]; r.log = logOf(tr); }
            } else r.bot = "-"; // batch19c §2: a server-level record (or a real player's, whose name is never written)
            r.excWhere = w; r.excCls = t.getClass().getName(); r.excMsg = cut(String.valueOf(t.getMessage()), 200);
            StackTraceElement[] st = t.getStackTrace(); int n = Math.min(6, st.length);
            r.frames = new String[n]; for (int i = 0; i < n; i++) r.frames[i] = cut(st[i].toString(), 160);
            r.reason = "exception"; r.rsub = "exception";
            r.bug = bugId(r);
            return accept(r);
        } catch (Throwable e) { complain("exception", e); return null; }
    }

    // ------------------------------------------------------------ batch 19c: the domain detector API (batch19c-design §3.1-3.4; Builder B's hooks are one-liners against it)

    static final java.util.Set<String> DOMAINS_ON = domainsOn();
    static java.util.Set<String> domainsOn() { // batch 19c: -Dfailures.domains=path,economy,... (absent, empty or "all": every domain)
        java.util.Set<String> s = new java.util.HashSet<>(); String v = System.getProperty("failures.domains");
        if (v == null || v.trim().isEmpty() || "all".equalsIgnoreCase(v.trim())) { s.addAll(java.util.Arrays.asList(DOMAINS)); return s; }
        for (String x : v.split(",")) { String d = x.trim().toLowerCase(Locale.ROOT); if (CODES.containsKey(d)) s.add(d); }
        return s;
    }
    /** batch 19c §3.1: does this domain emit records (-Dfailures=false: none; -Dfailures.domains: the list). Counters move for an off domain. */
    static boolean on(String domain) { return ON && domain != null && DOMAINS_ON.contains(domain); }

    // batch 19c §4.1: legitimate unaccounted coin sources/sinks in a trip window (econNote's flag argument; a bit index)
    static final byte THIEVE = 0, QUEST = 1, ALCH = 2, PLAYER_TRADE = 3, SPECTACLE = 4, GRAVE = 5, DEATH = 6, COLLECT = 7, SHOP = 8, DEAL = 9, ERRAND = 10, DEAL_BUY = 11, DEAL_SELL = 12;
    static final String[] FLAG_NAME = {"THIEVE", "QUEST", "ALCH", "PLAYER_TRADE", "SPECTACLE", "GRAVE", "DEATH", "COLLECT", "SHOP", "DEAL", "ERRAND", "DEAL_BUY", "DEAL_SELL"};
    static final int VANISH_OK = 1 << DEATH | 1 << PLAYER_TRADE | 1 << SPECTACLE | 1 << DEAL | 1 << DEAL_BUY | 1 << QUEST;                                       // §4.1 coin-vanished exclusions (DEAL without a side counts as both)
    static final int APPEAR_OK = 1 << THIEVE | 1 << QUEST | 1 << ALCH | 1 << PLAYER_TRADE | 1 << SPECTACLE | 1 << GRAVE | 1 << COLLECT | 1 << DEAL | 1 << DEAL_SELL | 1 << ERRAND; // §4.1 coin-appeared exclusions

    /** batch 19c §4.1: one bot's coin window (trip start to trip end / logout). Game thread (Wallet is game-thread only). */
    static final class Win { long start, expected, t; int flags; boolean open; String job; int trips; }
    static final Map<Player, Win> WINS = Collections.synchronizedMap(new IdentityHashMap<>());
    static volatile int offN; // batch 19c: events of a domain that -Dfailures.domains switched off (counted, not written)
    static volatile int winOpened, winTripEnd, winLogout; // batch 19c §4.1: windows opened / closed by the hooks (tests read them)

    /**
     * batch 19c §3.1: a discrete failure of any domain. bot null = server-level ("bot":"-", no snapshot); a real player = nothing at all.
     * reason: the detector's code ([a-z0-9-], ≤ 24). ctx: the domain's fields (copied; trimmed to ~700 B); three keys are taken out of it:
     * "key" (the §3.3 bug-key slug; when absent it is derived per domain: quest = quest[-step], combat = cluster16-killer, skilling =
     * task-cluster16, health = source, save = file, economy/lifecycle = role), "replay" (the §9 spec, into the bug entry), "scan" (true:
     * the 7x7 object/NPC scan, game thread). Returns the fid when the record will be written, else null. Any thread; never throws.
     */
    @SuppressWarnings("unchecked")
    static String event(String domain, Player bot, String src, String reason, String rsub, Location at, Map<String, Object> ctx) {
        if (!ON) return null;
        try {
            if (bot != null && !bot.isArtificial()) return null; // §1.3: never a real player
            String dom = domain(domain) ? domain : "health";
            long now = System.currentTimeMillis();
            Rec r = new Rec();
            r.domain = dom; r.src = src == null ? "other" : cut(src, 40); r.t = now; r.bot = bot == null ? "-" : bot.getUsername();
            r.reason = reason != null && reason.matches("[a-z0-9-]{1,24}") ? reason : "unknown"; r.rsub = cut(rsub == null ? "" : rsub, 40);
            r.why = cut(rsub, 160);
            Map<String, Object> c = ctx == null ? null : new LinkedHashMap<>(ctx);
            boolean scan = false;
            if (c != null) {
                Object k = c.remove("key"); if (k != null) r.key = slugs(String.valueOf(k));
                Object sp = c.remove("replay"); if (sp instanceof Map) r.replay = new LinkedHashMap<>((Map<String, Object>) sp);
                scan = Boolean.TRUE.equals(c.remove("scan"));
            }
            if (r.key == null || r.key.isEmpty()) r.key = keyFor(dom, bot, at, c);
            r.bug = "unknown".equals(r.reason) ? null : "b-" + r.reason + "-" + r.key;
            if (at != null) r.stall = xyz(at);
            r.place = at != null ? at.getX() + "," + at.getY() + "," + at.getZ() : r.key;
            if (!on(dom)) { offN++; try { Watchdog.Daily.note("fail", r.reason); } catch (Throwable ignored) { } return null; } // §1.6: an off domain writes nothing, its counter moves
            boolean write = allow(r, now);
            if (write) {
                if (c != null) r.ctx = fitCtx(c, 700);
                if (bot != null) enrich(r, bot, at, scan);
            }
            return finish(r, write);
        } catch (Throwable t) { complain("event", t); return null; }
    }

    /** batch 19c: what event() adds to a record it will write: the snapshot, the trip fields from the Trace, the bot's tile. */
    static void enrich(Rec r, Player bot, Location at, boolean scan) {
        boolean game = gameThread();
        Trace tr = trace(bot);
        Location here = at != null ? at : bot.getLocation();
        if (r.stall == null && here != null) r.stall = xyz(here);
        Watchdog.Goal g = null;
        if (game) try { g = Watchdog.goal(bot); } catch (Throwable ignored) { }
        if (tr != null && tr.traced) {
            r.drv = DRV_NAME[tr.driver]; r.log = logOf(tr);
            r.inCombat = tr.inCombat; r.died = tr.died; r.hits = tr.hits;
            if (tr.tripT > 0 && tr.tripRef != null) { r.tripS = (int) ((r.t - tr.tripT) / 1000); if (tr.tripFrom >= 0) r.from = new int[]{px(tr.tripFrom), py(tr.tripFrom), pz(tr.tripFrom)}; r.walker = g != null ? walkerWord(g.walker) : "other"; if (g != null && g.to != null) r.to = xyz(g.to); } // §2: trip keys only with a trip in flight
        }
        if (scan && game && here != null) try { scan(r, bot, here, false); } catch (Throwable ignored) { }
        try { r.snap = snapshot(bot, g, tr, here, game); } catch (Throwable ignored) { }
    }

    /** batch 19c §3.3: the bug key when the detector gave none. */
    static String keyFor(String dom, Player bot, Location at, Map<String, Object> c) {
        Object q = c == null ? null : c.get("quest"), st = c == null ? null : c.get("step"), kl = c == null ? null : c.get("killer"), tk = c == null ? null : c.get("task");
        switch (dom) {
            case "quest": return q == null ? "x" : slug(String.valueOf(q)) + (st != null ? "-" + slug(String.valueOf(st)) : "");
            case "combat": return cluster16(at) + "-" + (kl == null ? "x" : slug(String.valueOf(kl)));
            case "skilling": return (tk == null ? "x" : slug(String.valueOf(tk))) + "-" + cluster16(at);
            case "health": return c != null && c.get("source") != null ? slug(String.valueOf(c.get("source"))) : "x";
            case "save": return c != null && c.get("file") != null ? slug(String.valueOf(c.get("file"))) : "x";
            case "path": return cluster16(at);
            default: return c != null && c.get("role") != null ? slug(String.valueOf(c.get("role"))) : roleOf(bot); // economy, lifecycle
        }
    }

    static String cluster16(Location l) { return l == null ? "x" : (l.getX() >> 4) + "-" + (l.getY() >> 4) + "-" + l.getZ(); }

    /** batch 19c §3.3: "adventurer", "trader", "artisan", "herb", "replayer", "explorer-...", "server" (no bot), else "other". */
    static String roleOf(Player bot) {
        if (bot == null) return "server";
        try {
            Trace tr = trace(bot); if (tr != null && tr.role != null) return slug(tr.role);
            String k = bot.getUsername().toLowerCase(Locale.ROOT);
            if (SmartAdventurers.BRAINS.containsKey(k)) return "adventurer";
            for (TravellingTraders.Trader t : TravellingTraders.ALL) if (t.bot == bot) return "trader";
            if (Artisans.ARTISAN_BOTS.contains(bot)) return "artisan";
        } catch (Throwable ignored) { }
        return "other";
    }

    /** batch 19c §3.3: lower case, [a-z0-9-], ≤ 24 chars. */
    static String slug(String s) {
        if (s == null) return "x";
        StringBuilder b = new StringBuilder(); boolean dash = false;
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) { boolean ok = ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9'; if (ok) { b.append(ch); dash = false; } else if (!dash && b.length() > 0) { b.append('-'); dash = true; } if (b.length() >= 24) break; }
        while (b.length() > 0 && b.charAt(b.length() - 1) == '-') b.setLength(b.length() - 1);
        return b.length() == 0 ? "x" : b.toString();
    }
    /** batch 19c: a detector's composite key ("task-cluster", "op@site"): each '-'-part slugged; '@' kept as "-at-" would be noise, so it becomes '-'. */
    static String slugs(String s) {
        if (s == null) return "x";
        StringBuilder b = new StringBuilder();
        for (String part : s.split("[-@ ]+")) { if (part.isEmpty()) continue; if (b.length() > 0) b.append('-'); b.append(slug(part)); }
        return b.length() == 0 ? "x" : cut(b.toString(), 72);
    }

    /** batch 19c §2: ctx ≤ ~700 B: long strings cut to 60, lists to 5, then keys dropped from the end ("cut":true marks it). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> fitCtx(Map<String, Object> c, int max) {
        if (Json.write(c).length() <= max) return c;
        LinkedHashMap<String, Object> o = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : c.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) v = cut((String) v, 60);
            else if (v instanceof List && ((List<?>) v).size() > 5) v = new ArrayList<>(((List<Object>) v).subList(0, 5));
            o.put(e.getKey(), v);
        }
        List<String> keys = new ArrayList<>(o.keySet());
        for (int i = keys.size() - 1; i > 0 && Json.write(o).length() > max - 12; i--) o.remove(keys.get(i));
        o.put("cut", true);
        return o;
    }

    /** batch 19c §4.1: a legitimate unaccounted source/sink seen in this bot's open window (a bit; no record). */
    static void econNote(Player bot, byte flag) {
        if (!ON || bot == null || flag < 0 || flag > 30) return;
        try { Win w = WINS.get(bot); if (w != null && w.open) w.flags |= 1 << flag; } catch (Throwable ignored) { }
    }

    /** batch 19c §4.1: the expected delta of a Wallet move (pay negative; give/restore/collect positive). Inside Ledger.Wallet only. */
    static void walletMove(Player bot, long signedAmount) {
        if (!ON || bot == null) return;
        try { Win w = WINS.get(bot); if (w != null && w.open) w.expected += signedAmount; } catch (Throwable ignored) { }
    }

    /** batch 19c §4.1: open (start=true) or close a bot's coin window; a close evaluates the two rules ("trip_end"). */
    static void tripCoins(Player bot, long coins, boolean start) { tripCoins(bot, coins, start, start ? null : "trip_end", null, -1); }

    /** batch 19c §4.1: the full form: src "trip_end" | "logout" at a close; job and trips for ctx (null / -1: unknown). */
    static void tripCoins(Player bot, long coins, boolean start, String src, String job, int trips) {
        if (!ON || bot == null || !bot.isArtificial()) return;
        try {
            if (start) { winOpened++; Win w = WINS.get(bot); if (w == null) { w = new Win(); WINS.put(bot, w); } w.open = true; w.start = coins; w.expected = 0; w.flags = 0; w.t = System.currentTimeMillis(); w.job = job; w.trips = trips; return; }
            Win w = WINS.get(bot);
            if (w == null || !w.open) return;
            w.open = false;
            if ("logout".equals(src)) { WINS.remove(bot); winLogout++; } else winTripEnd++;
            long actual = coins - w.start, unexplained = actual - w.expected;
            String code = null;
            if (unexplained < -Math.max(2_000L, w.start / 5) && (w.flags & VANISH_OK) == 0) code = "coin-vanished";
            else if (unexplained > Math.max(5_000L, w.start / 2) && (w.flags & APPEAR_OK) == 0) code = "coin-appeared";
            if (code == null) return; // §4.1: no counter for the normal case
            LinkedHashMap<String, Object> c = new LinkedHashMap<>();
            c.put("start", w.start); c.put("end", coins); c.put("expected", w.expected); c.put("unexplained", unexplained);
            List<Object> fl = new ArrayList<>(); for (int i = 0; i < FLAG_NAME.length; i++) if ((w.flags & 1 << i) != 0) fl.add(FLAG_NAME[i]);
            c.put("flags", fl); if (job != null || w.job != null) c.put("job", job != null ? job : w.job); if (trips >= 0 || w.trips >= 0) c.put("trips", trips >= 0 ? trips : w.trips);
            c.put("windowS", (System.currentTimeMillis() - w.t) / 1000);
            event("economy", bot, src == null ? "trip_end" : src, code, code + " " + unexplained + " gp", null, c);
        } catch (Throwable t) { complain("tripCoins", t); }
    }

    /** batch 19c: is this bot's coin window open (tests, and the Brain's "open once per trip" check). */
    static boolean windowOpen(Player bot) { Win w = bot == null ? null : WINS.get(bot); return w != null && w.open; }

    // batch 19c §7: AfkTasks.Runner.finish's why prefixes -> skilling codes (unknown prefixes: no record; path/combat/exception ones: not again)
    static final String[][] JOB_END = {
            {"Inventory is full of things I won't drop.", "inventory-full"},
            {"Bank is full or nothing to deposit.", "bank-full"},
            {"Nothing for ", "spot-depleted"}, {"Couldn't find any ", "spot-depleted"},
            {"No ores left in your bank and no pickaxe", "wrong-tool"}, {"No ores in your bank", "wrong-tool"},
            {"Out of food.", "out-of-supplies"}, {"Out of water.", "out-of-supplies"},
            {"Retreated too many times", "spot-too-dangerous"}, {"Everything here is too strong", "spot-too-dangerous"}, {"Teleported out twice", "spot-too-dangerous"},
            {"No bank close enough", "no-facility"}, {"Couldn't find the bank booth", "no-facility"}, {"No furnace close enough", "no-facility"}};

    /** batch 19c §7: a job finished (Runner.finish, game thread). The prefix table picks the code; bank-full is the economy record of §4.5. */
    static void jobEnd(Player bot, AfkTasks.Runner r, String why) {
        if (!ON || bot == null || r == null || why == null || !bot.isArtificial()) return;
        try {
            String code = null;
            for (String[] e : JOB_END) if (why.startsWith(e[0])) { code = e[1]; break; }
            if (code == null && why.startsWith("No ") && why.contains(" axe")) code = "wrong-tool";
            if (code == null) return;
            String task = r.task != null ? r.task.name() : "unknown";
            if ("bank-full".equals(code)) {
                int free = -1; try { free = bot.getBank().freeSlots(); } catch (Throwable ignored) { }
                if (free != 0) return; // "nothing to deposit" with room in the bank is not a bank-full
                bankFull(bot, "runner", -1);
                return;
            }
            LinkedHashMap<String, Object> c = new LinkedHashMap<>();
            c.put("task", task); c.put("state", String.valueOf(r.state)); c.put("last", cut(r.last, 80)); c.put("collected", r.collected);
            Location spot = r.spot != null ? r.spot : bot.getLocation();
            if (spot != null) c.put("spot", list(spot.getX(), spot.getY(), spot.getZ()));
            try { c.put("inv", list(bot.getInventory().itemCount(), bot.getInventory().freeSlots())); } catch (Throwable ignored) { }
            c.put("key", slug(task) + "-" + cluster16(spot));
            event("skilling", bot, "job_end", code, cut(why, 40), spot, c);
        } catch (Throwable t) { complain("jobEnd", t); }
    }

    /** batch 19c §4.5: bank-full (economy), one per bot per hour (the 10-minute economy throttle plus an hourly gate here). */
    static final Map<String, Long> BANK_FULL_T = new ConcurrentHashMap<>();
    static void bankFull(Player bot, String where, int item) {
        if (!ON || bot == null || !bot.isArtificial()) return;
        try {
            long now = System.currentTimeMillis(); String k = bot.getUsername();
            Long last = BANK_FULL_T.get(k); if (last != null && now - last < 3_600_000L) { Watchdog.Daily.note("fail", "bank-full"); return; }
            BANK_FULL_T.put(k, now);
            LinkedHashMap<String, Object> c = new LinkedHashMap<>();
            int free = -1, pack = -1; try { free = bot.getBank().freeSlots(); pack = bot.getInventory().freeSlots(); } catch (Throwable ignored) { }
            c.put("bankSlots", free); c.put("packFree", pack); if (item > 0) c.put("item", item); c.put("where", where);
            event("economy", bot, "bank", "bank-full", where, null, c);
        } catch (Throwable t) { complain("bankFull", t); }
    }

    /** batch 19c §6: a bot died (SmartAdventurers.checkDeath / idleDeath). Ring, repeat-death, idle-death, Rates series. Game thread. */
    static void death(Player bot, Location where, String killer, String job, int kept, java.util.List<Item> lost, boolean idle) {
        if (!ON || bot == null || !bot.isArtificial()) return;
        try { Deaths.note(bot, where, killer, job, kept, lost, idle); } catch (Throwable t) { complain("death", t); }
    }

    /** batch 19c §8.2: one add-on pulse's cost at its catch site (any thread). > 100 ms: long-pulse (one per source per 10 min). */
    static void pulseCost(String source, Player bot, long nanos) {
        if (!ON || source == null) return;
        try { Pulses.cost(source, bot, nanos); } catch (Throwable t) { complain("pulseCost", t); }
    }

    /** batch 19c §8.2: the heaviest pulseCost source of the last minute (Builder B's tick-overrun ctx.topPulse), or null. */
    static String topPulse() { try { return Pulses.top(); } catch (Throwable t) { return null; } }

    /**
     * batch 19c §9.3 [Fable helper beyond §3.1]: a periodic detector's check result for one bug id. failing=true resets the clean run;
     * the 3rd, 6th and 9th consecutive clean check append a {"world":HOST,"bot":"-","result":"pass","why":"clear for 3 checks"} replay to
     * the bug when it is open (three passes close it as not-reproduced by the batch 19 rule). Any thread.
     */
    static final Map<String, Integer> CLEAN = new ConcurrentHashMap<>();
    static void periodic(String bugId, boolean failing) {
        if (!ON || bugId == null) return;
        try {
            if (failing) { CLEAN.put(bugId, 0); return; }
            Integer n0 = CLEAN.get(bugId); if (n0 == null) return; // never failed since boot: nothing to clear
            int n = n0 + 1; CLEAN.put(bugId, n);
            if (n % 3 == 0 && n <= 9) passReplay(bugId, "clear for 3 checks");
        } catch (Throwable t) { complain("periodic", t); }
    }

    /** batch 19c §9.3: a live pass entry into an open bug's replays (not counted against the path replayer's 5 a day). */
    static void passReplay(String id, String why) {
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("t", System.currentTimeMillis()); r.put("world", HOST); r.put("bot", "-"); r.put("sid", null); r.put("result", "pass"); r.put("fid", null); r.put("why", why);
        synchronized (Bugs.LOCK) {
            Bug v = Bugs.VIEW.get(id); if (v == null || !"open".equals(v.status)) return;
            Bugs.replays(v, Collections.singletonList(r));
            Bug d = Bugs.DELTA.get(id); if (d == null) { d = v.copy(); d.count = 0; d.days.clear(); d.sources.clear(); d.first = 0; d.last = 0; d.examples.clear(); d.fids.clear(); d.bots.clear(); d.replays.clear(); d.snap = null; Bugs.DELTA.put(id, d); }
            Bugs.replays(d, Collections.singletonList(r));
            Bugs.dirty = true;
        }
    }

    static LinkedHashMap<String, Object> ctx(Object... kv) { LinkedHashMap<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]); return m; } // batch 19c: ctx("k", v, ...) (null values left out)

    /** batch 19c: true once per key per window (a detector's own rate gate on top of the §3.2 throttles). */
    static final Map<String, Long> GATE = new ConcurrentHashMap<>();
    static boolean gate(String key, long windowMs) { long now = System.currentTimeMillis(); Long l = GATE.get(key); if (l != null && now - l < windowMs) return false; if (GATE.size() > 20_000) GATE.clear(); GATE.put(key, now); return true; }
    static void counted(String code) { try { Watchdog.Daily.note("fail", code); } catch (Throwable ignored) { } } // batch 19c: a gated-out event still moves its counter (§3.2)

    // ------------------------------------------------------------ batch 19c §4: economy detectors (one-liner hooks in Ledger, MarketBrain, Deals, PlayerTrades, BotSwap, TravellingTraders, SmartAdventurers)
    static final class Econ {
        /** §4.2: a hand-over that lost the goods. where = wallet.give|packOrBank|botswap|deal.settle|trade.end|grave (also the key). */
        static void itemLost(Player bot, int id, long n, String where, String peer) {
            if (!ON || bot == null || !bot.isArtificial()) return;
            try { event("economy", bot, "handover", "item-lost-on-handover", where + ": " + n + " x " + id, null, ctx("item", id, "n", n, "where", where, "peer", peer == null ? "-" : peer, "key", where)); } catch (Throwable t) { complain("itemLost", t); }
        }
        /** §4.2: Ledger.settle consumed units beyond a queue that existed and ran empty (not pre-ledger stock). Server-level, key item id. */
        static void unpaidStock(int id, int units, String reason) {
            if (!ON) return;
            try { event("economy", null, "settle", "item-unpaid-stock", units + " x " + id + " unpaid (" + reason + ")", null, ctx("item", id, "unpaid", units, "reason", cut(reason, 24), "key", String.valueOf(id))); } catch (Throwable t) { complain("unpaidStock", t); }
        }
        /** §4.3: Ledger.supply refused by the GE; one per item per hour. */
        static void supplyRefused(int id, int n, Player who) {
            if (!ON) return;
            try {
                if (!gate("supply|" + id, 3_600_000L)) { counted("ge-supply-refused"); return; }
                String sup = who == null ? "world" : who.isArtificial() ? "bot" : "player";
                event("economy", null, "ge", "ge-supply-refused", n + " x " + id + " refused", null, ctx("item", id, "n", n, "supplier", sup, "key", String.valueOf(id)));
            } catch (Throwable t) { complain("supplyRefused", t); }
        }
        /** §4.6: a catalogued item's value or bot reference outside the rule; one per item per day. */
        static void priceOutlier(int id, long value, long base, long botRef, String src) {
            if (!ON) return;
            try {
                if (!gate("price|" + id, 86_400_000L)) { counted("price-outlier"); return; }
                event("economy", null, src, "price-outlier", "item " + id + " value " + value + " base " + base + " botRef " + botRef, null, ctx("item", id, "value", value, "base", base, "botRef", botRef, "key", String.valueOf(id)));
            } catch (Throwable t) { complain("priceOutlier", t); }
        }
        /** §4.6: a settled/asked unit price under 50% or over 300% of Deals.ref (the bot's side only). */
        static void dealPrice(Player bot, int id, long unit, String peer, String where) {
            if (!ON || bot == null || !bot.isArtificial() || unit <= 0) return;
            try {
                long ref = Deals.ref(id);
                if (ref <= 0 || unit * 2 >= ref && unit <= ref * 3) return;
                event("economy", bot, where, "deal-price-outlier", "unit " + unit + " ref " + ref, null, ctx("item", id, "unit", unit, "ref", ref, "peer", peer, "key", String.valueOf(id)));
            } catch (Throwable t) { complain("dealPrice", t); }
        }
        /** §4.7: Wallet.pay failed while the bot had the coins (the remove threw or returned false). ctx.site: the caller frame. */
        static void payFailed(Player p, long amount, long coins, String why) {
            if (!ON || p == null || !p.isArtificial()) return;
            try {
                String site = "?", key = "pay";
                for (StackTraceElement e : new Throwable().getStackTrace()) { String c = e.getClassName(); if (c.startsWith("content.custom.Failures") || c.startsWith("content.custom.Ledger")) continue; String cls = c.substring(c.lastIndexOf('.') + 1); site = cls + "." + e.getMethodName() + ":" + e.getLineNumber(); key = "pay@" + slug(cls.replace('$', '-')) + "-" + slug(e.getMethodName()) + "-" + e.getLineNumber(); break; }
                event("economy", p, "wallet", "pay-failed-with-coins", cut(why, 60), null, ctx("amount", amount, "coins", coins, "site", site, "why", cut(why, 80), "key", key));
            } catch (Throwable t) { complain("payFailed", t); }
        }
        /** §4.7: a toll the bot could not pay (gate = alkharid|shantay|boat-karamja|salve); one per bot per gate per hour. */
        static void toll(Player bot, String gate, long need) {
            if (!ON || bot == null || !bot.isArtificial()) return;
            try {
                if (!gate("toll|" + bot.getUsername() + "|" + gate, 3_600_000L)) { counted("toll-unaffordable"); return; }
                event("economy", bot, "toll", "toll-unaffordable", gate + " " + need + " gp", null, ctx("gate", gate, "need", need, "coins", Ledger.Wallet.coins(bot), "key", gate));
            } catch (Throwable t) { complain("toll", t); }
        }
        /** §4.7: a fee the bot could not pay (fee = sawmill|teleport|quest-shop). */
        static void fee(Player bot, String fee, long need) {
            if (!ON || bot == null || !bot.isArtificial()) return;
            try { event("economy", bot, "fee", "fee-unaffordable", fee + " " + need + " gp", null, ctx("fee", fee, "need", need, "coins", Ledger.Wallet.coins(bot), "key", fee)); } catch (Throwable t) { complain("fee", t); }
        }

        // §4.5 shops: a per-bot ring of the last 4 shop arrivals {slug, t, ok}
        static final Map<Player, Object[][]> SHOPS = Collections.synchronizedMap(new java.util.WeakHashMap<>());
        /**
         * §4.5: a bot arrived at a shop and bought (or not). wanted = shopBuy lines {id, n}; stock = the shop's listed stock (null:
         * unknown); paid = the lines Wallet.pay accepted. shop-out-of-stock: no wanted id in the listed stock; shop-cant-afford: every
         * pay refused; shop-loop: the third arrival at this shop within 2 h with nothing usable bought (ok = in stock and paid).
         */
        static void shopArrive(Player bot, String shop, List<int[]> wanted, java.util.Set<Integer> stock, boolean[] paid, long coinsBefore) {
            if (!ON || bot == null || !bot.isArtificial() || wanted == null || wanted.isEmpty()) return;
            try {
                String sl = slug(shop); long now = System.currentTimeMillis();
                int listed = 0, payN = 0, ok = 0; List<Object> want = new ArrayList<>(), in = new ArrayList<>();
                for (int i = 0; i < wanted.size(); i++) {
                    int[] b = wanted.get(i); boolean l = stock == null || stock.contains(b[0]); boolean p = paid != null && i < paid.length && paid[i];
                    if (l) { listed++; in.add(b[0]); } if (p) payN++; if (l && p) ok++;
                    if (want.size() < 6) want.add(list(b[0], b[1]));
                }
                if (listed == 0) event("economy", bot, "shop", "shop-out-of-stock", shop + ": nothing wanted is stocked", null, ctx("shop", shop, "wanted", want, "got", payN, "inStock", in, "key", sl));
                else if (payN == 0) event("economy", bot, "shop", "shop-cant-afford", shop + ": " + coinsBefore + " gp", null, ctx("shop", shop, "wanted", want, "got", 0, "inStock", in, "coins", coinsBefore, "key", sl));
                Object[][] ring = SHOPS.computeIfAbsent(bot, k -> new Object[4][]);
                synchronized (ring) {
                    System.arraycopy(ring, 0, ring, 1, 3); ring[0] = new Object[]{sl, now, ok};
                    if (ok > 0) return;
                    int n = 0; long firstT = now;
                    for (Object[] e : ring) if (e != null && sl.equals(e[0]) && (Integer) e[2] == 0 && now - (Long) e[1] <= 2 * 3_600_000L) { n++; firstT = Math.min(firstT, (Long) e[1]); }
                    if (n >= 3) {
                        for (int i = 0; i < 4; i++) if (ring[i] != null && sl.equals(ring[i][0])) ring[i] = null; // one record per loop: three more walks for the next
                        event("economy", bot, "shop", "shop-loop", shop + ": " + n + " walks, nothing bought", null, ctx("shop", shop, "walks", n, "span", (now - firstT) / 1000, "key", sl));
                    }
                }
            } catch (Throwable t) { complain("shopArrive", t); }
        }

        // §4.4 deals and trades
        static final Map<Player, long[]> STOOD = Collections.synchronizedMap(new java.util.WeakHashMap<>()); // buyer -> its last stood-up times
        /** §4.4: Deals.meet "stood up": a record when the same buyer is stood up 3 times in an hour, or it never moved in the window. */
        static void stoodUp(Player buyer, Player seller, int id, int qty, long claimedAt) {
            if (!ON || buyer == null || !buyer.isArtificial()) return;
            try {
                long now = System.currentTimeMillis();
                long[] r = STOOD.computeIfAbsent(buyer, k -> new long[3]); int n;
                synchronized (r) { System.arraycopy(r, 0, r, 1, 2); r[0] = now; n = 0; for (long t : r) if (t > 0 && now - t <= 3_600_000L) n++; }
                Trace tr = trace(buyer); Boolean moved = tr == null || !tr.traced ? null : tr.lastMoveT > claimedAt;
                int dist = -1; try { dist = (int) buyer.getLocation().getDistance(seller.getLocation()); } catch (Throwable ignored) { }
                if (n < 3 && !Boolean.FALSE.equals(moved)) { counted("deal-stood-up"); return; } // normal traffic once
                event("economy", buyer, "deal", "deal-stood-up", (n >= 3 ? n + " times in an hour" : "never moved"), null, ctx("item", id, "qty", qty, "buyer", buyer.getUsername(), "buyerMoved", moved, "dist", dist, "stoodUp1h", n, "key", roleOf(buyer)));
            } catch (Throwable t) { complain("stoodUp", t); }
        }
        /** §4.4: Deals.settle: pay false (step pay) or the seller's bank.remove false (step remove). */
        static void settleFailed(Player buyer, Player seller, String step, int id, int qty, long total) {
            if (!ON) return;
            try { Player who = buyer != null && buyer.isArtificial() ? buyer : seller; event("economy", who, "deal", "deal-settle-failed", step + ": " + qty + " x " + id, null, ctx("step", step, "item", id, "qty", qty, "total", total, "buyerCoins", buyer != null && buyer.isArtificial() ? Ledger.Wallet.coins(buyer) : null, "key", step)); } catch (Throwable t) { complain("settleFailed", t); }
        }
        /** §4.4: an artisan split cancelled (it could not pay); the raw goods went back. */
        static void splitCancelled(Player artisan, int product, int raw, int back) {
            if (!ON || artisan == null) return;
            try { event("economy", artisan, "deal", "split-cancelled", back + " x " + raw + " back", null, ctx("artisan", artisan.getUsername(), "product", product, "raw", raw, "back", back, "key", "artisan")); } catch (Throwable t) { complain("splitCancelled", t); }
        }
        /** §4.4: a human walked away from a bot's trade window (peer player: nothing about the human). */
        static void tradeTimeout(Player bot, int item, long want, long offered) {
            if (!ON || bot == null || !bot.isArtificial()) return;
            try { event("economy", bot, "trade", "trade-timeout", "open 180 s", null, ctx("item", item, "want", want, "offered", offered, "peer", "player")); } catch (Throwable t) { complain("tradeTimeout", t); }
        }
        /** §4.4: BotSwap's "no room" branch (both pre-checks passed). */
        static void botswapFailed(Player a, Player b, int aGives, int bGives, String what) {
            if (!ON || a == null || !a.isArtificial()) return;
            try { event("economy", a, "botswap", "botswap-failed", cut(what, 40), null, ctx("a", a.getUsername(), "b", b == null ? "-" : b.isArtificial() ? b.getUsername() : "player", "aGives", aGives, "bGives", bGives, "what", cut(what, 40), "key", "botswap")); } catch (Throwable t) { complain("botswapFailed", t); }
        }
        /** §4.4: a player's errand dropped (why = death|expired). */
        static void errandDropped(Player bot, int item, int qty, String why) {
            if (!ON || bot == null || !bot.isArtificial()) return;
            try { event("economy", bot, "errand", "errand-dropped", why, null, ctx("item", item, "qty", qty, "why", why, "peer", "player", "key", why)); } catch (Throwable t) { complain("errandDropped", t); }
        }

        // §4.1 per world: the 10-minute profile save's coin sum against the Ledger counters
        static long wSum = -1; static int wCount; static long[] wPrev; static volatile String lastDrift;
        static final String WORLD_BUG = "b-coin-drift-world-world";
        /** §4.1 coin-drift-world: called from writeLastSave (the SmartAdventurers timer thread) with the book's coin sum and size. */
        static synchronized void worldSave(long sum, int count) {
            if (!ON) return;
            try {
                long off = 0; for (Long v : Ledger.Wallet.OFFLINE.values()) if (v != null) off += v;
                long[] cur = {Ledger.GIVEN, Ledger.PAID, Ledger.Wallet.RESTORED, Ledger.GIVEN_CREDITS, off, Ledger.QUEST_PAID, Ledger.ALCHED, PrivateWorldAddons.BUYER_FILLS};
                if (wPrev != null && wSum >= 0) {
                    long ds = sum - wSum; long[] d = new long[8]; for (int i = 0; i < 8; i++) d[i] = cur[i] - wPrev[i];
                    long expect = d[0] - d[1]; // [Fable] every add-on coin move is GIVEN or PAID; owed credits (OFFLINE) are not GIVEN until collected, so ΔOFFLINE is shown, not summed
                    long un = ds - expect;
                    boolean stable = wCount > 0 && Math.abs(count - wCount) * 10L < wCount;
                    boolean bad = stable && Math.abs(un) > Math.max(50_000L, Math.abs(sum) / 20);
                    lastDrift = "sum " + wSum + " -> " + sum + ", expected " + expect + ", unexplained " + un + (stable ? "" : " (bot count changed)");
                    if (bad) event("economy", null, "profile_save", "coin-drift-world", "unexplained " + un + " gp", null,
                            ctx("dSum", ds, "dGiven", d[0], "dPaid", d[1], "dRestored", d[2], "dGivenCredits", d[3], "dOffline", d[4], "dQuestPaid", d[5], "dAlched", d[6], "dBuyerFills", d[7], "unexplained", un, "bots", count, "botsBefore", wCount, "key", "world"));
                    periodic(WORLD_BUG, bad);
                }
                wPrev = cur; wSum = sum; wCount = count;
            } catch (Throwable t) { complain("worldSave", t); }
        }
    }

    // ------------------------------------------------------------ batch 19c §6: deaths (the ring, repeat-death, idle-death, Rates series)
    static final class Deaths {
        static final String[] ROLES = {"fresh", "veteran", "high", "trader", "replayer"};
        static final java.util.concurrent.atomic.AtomicLong[] N = new java.util.concurrent.atomic.AtomicLong[5];
        static { for (int i = 0; i < 5; i++) N[i] = new java.util.concurrent.atomic.AtomicLong(); }
        static final String[] CELL = new String[64], KILL = new String[64], ROLE = new String[64]; static final long[] T = new long[64]; static int head; // guarded by CELL

        static String tier(Player bot) {
            String r = roleOf(bot);
            if ("trader".equals(r)) return "trader";
            if ("replayer".equals(r)) return "replayer";
            String k = bot.getUsername().toLowerCase(Locale.ROOT);
            return SmartAdventurers.HIGH_TIER.getOrDefault(k, false) ? "high" : SmartAdventurers.VETERAN.getOrDefault(k, false) ? "veteran" : "fresh";
        }
        static String killerSlug(String killer) { if (killer == null) return "unknown"; int i = killer.indexOf(" ("); return slug(i > 0 ? killer.substring(0, i) : killer); }

        static void note(Player bot, Location where, String killer, String job, int kept, java.util.List<Item> lost, boolean idle) {
            String role = tier(bot);
            for (int i = 0; i < ROLES.length; i++) if (ROLES[i].equals(role)) N[i].incrementAndGet();
            String cell = cluster16(where), ks = killerSlug(killer), quest = null;
            try { QuestRunner qr = QuestRunner.ACTIVE.get(bot.getUsername().toLowerCase(Locale.ROOT)); if (qr != null && qr.script != null) { quest = qr.script.slug; ks = slug(quest); } } catch (Throwable ignored) { } // §6: a quest boss groups with the quest, not the area
            try { Watchdog.Daily.note("death", cell + "|" + ks); } catch (Throwable ignored) { }
            long now = System.currentTimeMillis(); int n = 0; LinkedHashMap<String, Object> roles = new LinkedHashMap<>();
            synchronized (CELL) {
                CELL[head] = cell; KILL[head] = ks; ROLE[head] = role; T[head] = now; head = (head + 1) & 63;
                for (int i = 0; i < 64; i++) if (CELL[i] != null && cell.equals(CELL[i]) && ks.equals(KILL[i]) && now - T[i] <= 6 * 3_600_000L) { n++; roles.merge(ROLE[i], 1, (a, b) -> (Integer) a + (Integer) b); }
            }
            int cb = -1; try { cb = bot.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
            int food = -1; try { SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(bot.getUsername().toLowerCase(Locale.ROOT)); if (br != null) food = br.diedFood; } catch (Throwable ignored) { }
            if (n >= 3 && (n - 3) % 5 == 0) event("combat", bot, "death", "repeat-death", n + " deaths here in 6 h (" + cut(killer, 30) + ")", where,
                    ctx("killer", cut(killer, 40), "job", cut(job, 40), "deathsHere6h", n, "roles", roles, "food", food, "cb", cb, "quest", quest, "key", cell + "-" + ks));
            if (idle) { String doing = null; try { doing = cut(BotChat.activityOf(bot), 60); } catch (Throwable ignored) { }
                event("combat", bot, "death", "idle-death", "died while idle (" + cut(killer, 30) + ")", where, ctx("killer", cut(killer, 40), "where", cell, "doing", doing, "cb", cb, "key", cell + "-" + ks)); }
        }

        /** §6 kept-under-rule (player-rules death): fewer than 3 kept while items were lost and the bot was not skulled. */
        static void keptUnderRule(Player bot, int kept, java.util.List<Item> lost) {
            if (!ON || bot == null || !bot.isArtificial() || lost == null || lost.isEmpty() || kept >= 3) return;
            try {
                boolean skull = false; try { skull = bot.getSkullManager().isSkulled(); } catch (Throwable ignored) { }
                if (skull) return;
                long val = 0; List<Object> top = new ArrayList<>();
                for (Item it : lost) { if (it == null) continue; val += (long) Math.max(0, MarketBrain.value(it.getId())) * it.getAmount(); if (top.size() < 6) top.add(cut(it.getName() + " x" + it.getAmount(), 24)); }
                Location l = bot.getLocation();
                event("combat", bot, "death", "kept-under-rule", "kept " + kept + ", lost " + lost.size(), l, ctx("kept", kept, "lost", lost.size(), "lostValue", val, "lostTop", top, "key", cluster16(l) + "-rule"));
            } catch (Throwable t) { complain("keptUnderRule", t); }
        }
        /** §6 grave-short: fewer stacks picked up than the grave held. */
        static void graveShort(Player bot, int lostN, int got, List<Integer> missing) {
            if (!ON || bot == null || !bot.isArtificial() || lostN <= 0 || got >= lostN) return;
            try { Location l = bot.getLocation(); event("combat", bot, "grave", "grave-short", got + " of " + lostN + " stacks", l, ctx("lost", lostN, "recovered", got, "missing", missing, "key", cluster16(l) + "-grave")); } catch (Throwable t) { complain("graveShort", t); }
        }
        /** §6 grave-lost (why = timeout|crumbled|too-far): the items of a grave run that failed. */
        static void graveLost(Player bot, String why, int lostN, long value, int dist, Location grave) {
            if (!ON || bot == null || !bot.isArtificial()) return;
            try { event("combat", bot, "grave", "grave-lost", why + ", " + dist + " tiles", grave, ctx("why", why, "lost", lostN, "value", value, "dist", dist, "key", cluster16(grave) + "-" + why)); } catch (Throwable t) { complain("graveLost", t); }
        }
        /** §6 death-spike: the per-role Rates series alerted (Economy's minute timer). Server-level, one per role per hour. */
        static void spike(String series, long prev, long cur) {
            if (!ON) return;
            try {
                String role = series.startsWith("deaths-") ? series.substring(7) : series;
                List<Object> cells = new ArrayList<>(); Map<String, Integer> c = new HashMap<>(); long now = System.currentTimeMillis();
                synchronized (CELL) { for (int i = 0; i < 64; i++) if (CELL[i] != null && role.equals(ROLE[i]) && now - T[i] <= 3_600_000L) c.merge(CELL[i], 1, Integer::sum); }
                c.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(e -> cells.add(list(e.getKey(), e.getValue())));
                event("combat", null, "death_rate", "death-spike", role + ": " + prev + "/h -> " + cur + "/h", null, ctx("role", role, "prev", prev, "cur", cur, "topCells", cells, "key", role));
            } catch (Throwable t) { complain("spike", t); }
        }
    }

    // ------------------------------------------------------------ batch 19c §8.2: add-on pulse costs (EWMA per source, long-pulse)
    static final class Pulses {
        static final Map<String, double[]> EWMA = new ConcurrentHashMap<>(); // source -> {ewma ms, calls}
        static long minute = -1, topNs; static String topSrc, prevTop; // guarded by EWMA
        static final long LONG_NS = Long.getLong("failures.longPulseMs", 100L) * 1_000_000L;
        static void cost(String source, Player bot, long nanos) {
            long now = System.currentTimeMillis();
            double ms = nanos / 1e6;
            double[] e = EWMA.computeIfAbsent(source, k -> new double[2]);
            synchronized (e) { e[0] = e[1] == 0 ? ms : e[0] * 0.9 + ms * 0.1; e[1]++; }
            synchronized (EWMA) { long m = now / 60_000L; if (m != minute) { prevTop = minute == m - 1 ? topSrc : null; minute = m; topNs = 0; topSrc = null; } if (nanos > topNs) { topNs = nanos; topSrc = source; } }
            if (nanos <= LONG_NS || now - BOOT < 120_000L) return; // §11.2: not the first 120 s after startup
            if (!gate("pulse|" + source, 600_000L)) { counted("long-pulse"); return; }
            String last = null;
            try { if (bot != null) { AfkTasks.Runner r = gameThread() ? AfkTasks.RUNNING.get(bot.getUsername().toLowerCase(Locale.ROOT)) : null; last = r != null ? r.last : null; } } catch (Throwable ignored) { }
            event("health", bot != null && bot.isArtificial() ? bot : null, "pulse", "long-pulse", source + " " + Math.round(ms) + " ms", null, ctx("source", source, "ms", Math.round(ms), "ewmaMs", Math.round(e[0] * 10) / 10.0, "last", cut(last, 60), "key", source));
        }
        static String top() { synchronized (EWMA) { long m = System.currentTimeMillis() / 60_000L; return m == minute && topSrc != null ? topSrc : m == minute ? prevTop : m == minute + 1 ? topSrc : null; } }
    }

    // ------------------------------------------------------------ batch 19c §8.1 / §8.2 / §10: lifecycle, health and save hooks (bots' own files only)
    static final class Life {
        /** §8.1 login-failed (server-level: the bot does not exist yet). role: adventurer|high|artisan|trader|...; profile: a bot profile name. */
        static void loginFailed(String role, String profile, Throwable t) {
            if (!ON || t == null) return;
            try { String cls = simple(t.getClass().getName()); event("lifecycle", null, "login", "login-failed", cut(role + ": " + t, 60), null, ctx("role", role, "profile", profile, "exc", cls, "msg", cut(String.valueOf(t.getMessage()), 120), "key", slug(role) + "-" + slug(cls))); } catch (Throwable e) { complain("loginFailed", e); }
        }
        /** §10 save-failed: a bots' file could not be written; one per file per 10 min. */
        static void saveFailed(String file, Throwable t) {
            if (!ON || file == null) return;
            try { if (!gate("savefail|" + file, 600_000L)) { counted("save-failed"); return; } event("save", null, "save", "save-failed", cut(file + ": " + t, 60), null, ctx("file", file, "exc", t == null ? null : simple(t.getClass().getName()), "msg", t == null ? null : cut(String.valueOf(t.getMessage()), 120))); } catch (Throwable e) { complain("saveFailed", e); }
        }
        /** §10 file-quarantined (Ledger.quarantine). grave-recovery.json (real players' graves): only the exception class. */
        static void quarantined(java.io.File f, String who, Object why, java.io.File kept, boolean block) {
            if (!ON || f == null) return;
            try {
                String w = why instanceof Throwable ? (f.getName().startsWith("grave-recovery") ? simple(why.getClass().getName()) : cut(String.valueOf(why), 120)) : f.getName().startsWith("grave-recovery") ? "load failed" : cut(String.valueOf(why), 120);
                event("save", null, "load", "file-quarantined", f.getName(), null, ctx("file", f.getName(), "who", cut(who, 24), "why", w, "kept", kept == null ? null : kept.getName(), "blocked", block));
            } catch (Throwable e) { complain("quarantined", e); }
        }
        /** §10 save-refused (writeAtomic's blocked branch): once per boot per file. */
        static void saveRefused(java.io.File f) {
            if (!ON || f == null) return;
            try { if (!gate("refused|" + f.getName(), Long.MAX_VALUE / 4)) return; event("save", null, "save", "save-refused", f.getName(), null, ctx("file", f.getName())); } catch (Throwable e) { complain("saveRefused", e); }
        }
        /** §8.2 thread-violation (Ledger.assertThread's once-an-hour branch). */
        static void threadViolation(String op, String thread, String site) {
            if (!ON) return;
            try { event("health", null, "thread", "thread-violation", cut(op + " on " + thread, 60), null, ctx("op", op, "thread", cut(thread, 40), "site", cut(site, 120), "key", slugs(op.replace('.', '-')) + "-at-" + slugs(site.replaceAll("^.*\\.([A-Za-z0-9$]+)\\.([A-Za-z0-9_]+)\\(.*:(\\d+)\\)$", "$1-$2-$3").replace('$', '-')))); } catch (Throwable e) { complain("threadViolation", e); }
        }
    }

    static boolean gameThread() { return Ledger.GAME.equals(Thread.currentThread().getName()); }

    static long complainT;
    static void complain(String what, Throwable t) { long now = System.currentTimeMillis(); if (now - complainT > 60_000L) { complainT = now; System.out.println("[Failures] " + what + " hook: " + t); } }

    // ------------------------------------------------------------ the record (§2)

    static final class Obj { int id, ty; String n, st, rule; int[] at; }
    static final class Npc { int id, lvl; String n; int[] at; boolean agg; }

    /** One failure. Built on the game thread at the hook; read-only once queued (the writer thread serialises it). */
    static final class Rec {
        String domain = "path", src, sid, bot, walker, dtype, drv = "none", why, reason, rsub, bug, fid;
        long t;
        int[] from, to, stall, next;
        String planAns = "none", planWhy; boolean split; int planLen = -1, hops = -1, hop = -1, planAge = -1, replans = -1;
        int[] tiles = new int[0];                 // packed, oldest first
        List<Obj> objs = new ArrayList<>(); List<Npc> npcs = new ArrayList<>(); int npcRun;
        boolean inCombat, died, poison; int hits, h60, combatSince = -1;
        int d24, d29, m29, still; boolean px16, near3, wall, redir2;   // mv: what the path rules read
        String gateId, gateNeed; boolean gateUnmet, gateBox;
        int stuckS = -1, tripS = -1;
        String[] log = new String[0];
        Map<String, Object> snap;
        String excWhere, excCls, excMsg; String[] frames;
        String place; int[] cell;               // bug place text and cluster, set by bugId's caller side
        String key;                             // batch19c §3.3: a non-path detector's bug key slug (null: the stall cluster)
        Map<String, Object> ctx;                // batch19c §2: a domain's own fields (optional, trimmed after log)
        Map<String, Object> replay;             // batch 19c §9: the replay spec for the bug entry (event's ctx "replay")
        String hintSub;                         // batch 19c B4: a replayer hint's RSUB, used when classify ends "unknown"
    }

    static Rec build(Player bot, String domain, String src, String sid, Watchdog.Goal g, Location stall, String why) {
        long now = System.currentTimeMillis();
        boolean game = gameThread();
        Rec r = new Rec();
        r.domain = domain; r.src = src == null ? "other" : src; r.sid = cut(sid, 80); r.bot = bot.getUsername(); r.t = now; r.why = cut(why, 160);
        Trace tr = trace(bot);
        if (g == null && game) g = Watchdog.goal(bot);
        Location here = stall != null ? stall : bot.getLocation();
        boolean replayer = tr != null && "replayer".equals(tr.role);
        r.walker = replayer ? "replay" : g != null ? walkerWord(g.walker) : "other";
        Location from = g != null ? g.from : null, to = g != null ? g.to : null;
        r.from = from == null ? null : xyz(from); r.to = to == null ? null : xyz(to); r.stall = here == null ? null : xyz(here);
        if (game) try { r.dtype = Watchdog.destType(bot, to); } catch (Throwable ignored) { }
        if (r.dtype == null) r.dtype = "other";
        String k = Watchdog.key(bot);
        if (tr != null && tr.traced) {
            r.drv = DRV_NAME[tr.driver];
            r.tiles = tilesOf(tr);
            r.log = logOf(tr);
            r.inCombat = tr.inCombat; r.died = tr.died; r.hits = tr.hits; r.npcRun = tr.npcRun;
            for (long ht : tr.hitT) if (ht > 0 && now - ht <= 60_000L) r.h60++;
            r.combatSince = tr.combatT > 0 ? (int) ((now - tr.combatT) / 1000) : -1;
            r.tripS = tr.tripT > 0 ? (int) ((now - tr.tripT) / 1000) : -1;
            if (r.from == null && tr.tripFrom >= 0) r.from = new int[]{px(tr.tripFrom), py(tr.tripFrom), pz(tr.tripFrom)};
            if (tr.driverT > 0) r.planAge = (int) ((now - tr.driverT) / 1000);
        } else {
            try { r.inCombat = bot.inCombat(); } catch (Throwable ignored) { }
        }
        try { r.poison = core.api.ContentAPIKt.isPoisoned(bot); } catch (Throwable ignored) { }
        // the planner's answer
        Location hopAt = null;
        try {
            NavWalker.State s = NavWalker.STATES.get(k);
            boolean off = !NavWalker.ON;
            boolean outside = here != null && NavWalker.offGrid(here) || to != null && NavWalker.offGrid(to);
            if (outside) { r.planAns = "outside"; r.planWhy = to != null && NavWalker.offGrid(to) ? (to.getZ() != 0 ? "goal on floor " + to.getZ() : to.getY() > 6400 ? "goal underground" : "goal off the grid") : "bot off the surface grid"; }
            else if (off) r.planAns = "off";
            else if (s != null && s.route != null) {
                r.planAns = "route"; r.planLen = s.route.tiles; r.hops = s.route.hops.size(); r.hop = s.hop;
                if (s.hop < s.route.hops.size()) hopAt = s.route.hops.get(s.hop);
            } else if (s != null && s.dest != null || to != null && game && noRouteNow(bot, to)) {
                r.planAns = "no-route";
                if (game && here != null) try { Location d = s != null && s.dest != null ? s.dest : to; r.planWhy = NavWalker.why(bot, here, d, false); } catch (Throwable ignored) { }
            }
            Object[] pl = RouteMap.PLANS.get(k);
            Location mapHop = null;
            if (pl != null && pl.length > 2 && pl[1] instanceof List) { List<?> w = (List<?>) pl[1]; int i = (Integer) pl[2]; if (i >= 0 && i < w.size() && w.get(i) instanceof Location) mapHop = (Location) w.get(i); }
            if ("routemap".equals(r.drv) && mapHop != null) hopAt = mapHop;
            else if (hopAt == null && !"planner".equals(r.drv)) hopAt = mapHop;
        } catch (Throwable ignored) { }
        r.next = hopAt == null ? null : xyz(hopAt);
        try { // batch 19a [Fable]: a route whose hop is in another grid component than the bot (a stale route after a teleport, the unicorn pen) cannot be walked
            if ("route".equals(r.planAns) && hopAt != null && here != null && NavPlanner.built) { int a = NavPlanner.compAt(here.getX(), here.getY()), b = NavPlanner.compAt(hopAt.getX(), hopAt.getY()); if (a != 0 && b != 0 && a != b) { r.split = true; if (r.planWhy == null) r.planWhy = "route hop in another grid component than the bot"; } }
        } catch (Throwable ignored) { }
        if (here != null && game) scan(r, bot, here, replayer);
        if (here != null) gate(r, bot, here, to, replayer);
        mv(r, tr, now, here, hopAt);
        try { Watchdog.Seen seen = Watchdog.SEEN.get(k); r.stuckS = seen != null && seen.anchor != null ? (int) ((now - seen.since) / 1000) : r.still; } catch (Throwable ignored) { }
        r.snap = snapshot(bot, g, tr, here, game);
        return r;
    }

    static boolean noRouteNow(Player bot, Location to) { Long t = NavWalker.NO_ROUTE.get(NavWalker.noRouteKey(bot, to)); return t != null && t > System.currentTimeMillis(); }

    static String walkerWord(String w) {
        if (w == null) return "other";
        switch (w) { case "afk": case "shop": case "quest": case "grave": case "trader": case "crew": case "party": case "replay": return w; default: return "other"; }
    }

    static int[] tilesOf(Trace tr) {
        int n = tr.count; int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = tr.tiles[(tr.head - n + i + 32) & 31];
        return out;
    }

    static String[] logOf(Trace tr) {
        int n = tr.logCount; String[] out = new String[n];
        for (int i = 0; i < n; i++) { int j = (tr.logHead - n + i + 12) % 12; String l = tr.log[j]; out[i] = l == null ? "" : TAG_PREFIX[tr.logTag[j]] + cut(l, 160); }
        return out;
    }

    /** The 7x7 objects and the NPCs within 3 tiles, same plane (game thread: RegionManager is not safe elsewhere). Nearest first. */
    static void scan(Rec r, Player bot, Location c, boolean replayer) {
        int z = c.getZ();
        List<Obj> objs = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) {
            try {
                Scenery o = RegionManager.getObject(z, c.getX() + dx, c.getY() + dy);
                if (o == null || o.getDefinition() == null) continue;
                Obj b = new Obj(); b.id = o.getId(); b.ty = o.getType(); b.n = cut(o.getName(), 24);
                Location ol = o.getLocation(); b.at = ol != null ? xyz(ol) : new int[]{c.getX() + dx, c.getY() + dy, z};
                core.cache.def.impl.SceneryDefinition d = o.getDefinition();
                b.st = d.hasAction("Open") ? "closed" : d.hasAction("Close") ? "open" : d.getClipType() != 0 && !d.notClipped && (b.ty < 4 || b.ty >= 9 && b.ty <= 11) ? "solid" : "walk";
                if ("walk".equals(b.st) && (b.n == null || b.n.isEmpty() || "null".equals(b.n))) continue; // nameless decoration says nothing
                if (alKharid(b.id, b.at)) b.rule = "10 gp toll";
                else { Location at = Location.create(b.at[0], b.at[1], b.at[2]); for (Gates.Gate gt : Gates.at(at)) { b.rule = cut(gt.rule.need(), 40); break; } }
                objs.add(b);
            } catch (Throwable ignored) { }
        }
        final int[] s = xyz(c);
        objs.sort((a, b) -> { int wa = "walk".equals(a.st) ? 1 : 0, wb = "walk".equals(b.st) ? 1 : 0; return wa != wb ? wa - wb : cheb(a.at, s) - cheb(b.at, s); });
        r.objs = objs.size() > 12 ? new ArrayList<>(objs.subList(0, 12)) : objs;
        try {
            List<NPC> ns = RegionManager.getLocalNpcs(c, 3);
            if (ns != null) for (NPC n : ns) {
                if (n == null || n.getLocation() == null || n.getLocation().getZ() != z) continue;
                Npc x = new Npc(); x.id = n.getId(); x.n = cut(n.getName(), 24); x.at = xyz(n.getLocation()); x.agg = n.isAggressive();
                try { x.lvl = n.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
                r.npcs.add(x);
            }
            r.npcs.sort((a, b) -> cheb(a.at, s) - cheb(b.at, s));
            if (r.npcs.size() > 8) r.npcs = new ArrayList<>(r.npcs.subList(0, 8));
        } catch (Throwable ignored) { }
    }

    static boolean alKharid(int id, int[] at) { return (id == 2882 || id == 2883 || id == 35549 || id == 35551) && at != null && at[0] == 3268 && at[1] >= 3227 && at[1] <= 3228 && at[2] == 0; } // batch 19b: this cache's toll gate is 35549/35551 (19a matched only 2882/2883, so live toll records came out unknown)

    /** Rule 5's facts: a Gates rule (or the Al Kharid toll objects) at the stall, a near object or the destination, and whether this bot meets it. */
    static void gate(Rec r, Player bot, Location stall, Location to, boolean replayer) {
        try {
            for (Gates.Gate gt : Gates.at(stall)) { r.gateBox = true; break; }
            for (Obj o : r.objs) if (alKharid(o.id, o.at)) {
                long coins = Ledger.Wallet.coins(bot);
                r.gateId = "alkharid-toll"; r.gateNeed = "10 gp toll, coins " + coins; r.gateUnmet = replayer || coins < 10; return;
            }
            List<Gates.Gate> cand = new ArrayList<>(Gates.at(stall));
            for (Obj o : r.objs) if (cheb(o.at, xyz(stall)) <= 3) cand.addAll(Gates.at(Location.create(o.at[0], o.at[1], o.at[2])));
            if (to != null) cand.addAll(Gates.at(to));
            for (Gates.Gate gt : cand) {
                boolean ok; try { ok = gt.rule.ok(bot) && !(replayer && "toll".equals(gt.kind)); } catch (Throwable t) { ok = true; }
                if (r.gateId == null || !ok && !r.gateUnmet) { r.gateId = gt.id; r.gateNeed = cut(gt.rule.need(), 40) + ("toll".equals(gt.kind) ? ", coins " + Ledger.Wallet.coins(bot) : ""); r.gateUnmet = !ok; }
                if (!ok) return;
            }
        } catch (Throwable ignored) { }
    }

    /** The movement facts the rules read (mv): distinct tiles and moves in recent windows, plane changes, the hop window, walls to the hop. */
    static void mv(Rec r, Trace tr, long now, Location here, Location hop) {
        int s = sec(now);
        int[] t = r.tiles;
        if (tr != null && tr.traced && tr.count > 0) {
            java.util.HashSet<Integer> d24 = new java.util.HashSet<>(), d29 = new java.util.HashSet<>();
            int n = tr.count;
            for (int i = 0; i < n; i++) {
                int j = (tr.head - 1 - i + 64) & 31; int age = s - tr.at[j];
                if (age <= 24) d24.add(tr.tiles[j]);
                if (age <= 29) { d29.add(tr.tiles[j]); r.m29++; }
            }
            r.d24 = d24.size(); r.d29 = d29.size();
            r.still = s - tr.at[(tr.head + 31) & 31];
            for (int i = Math.max(1, n - 15); i < n; i++) if (pz(t[i]) != pz(t[i - 1])) r.px16 = true;
            if (hop != null && here != null) {
                boolean in = cheb(xyz(here), xyz(hop)) <= 3;
                for (int i = 0; i < n && in; i++) { int j = (tr.head - 1 - i + 64) & 31; if (s - tr.at[j] > 60) break; if (Math.max(Math.abs(px(tr.tiles[j]) - hop.getX()), Math.abs(py(tr.tiles[j]) - hop.getY())) > 3) in = false; }
                r.near3 = in;
            }
            int redirects = 0; Map<String, Integer> pairs = new HashMap<>();
            for (String l : r.log) { if (l == null || !l.contains("redirect")) continue; int a = l.indexOf("old goal "), b = l.indexOf("new goal "); if (a < 0 || b < 0) continue; String g1 = word(l, a + 9), g2 = word(l, b + 9); String key = g1.compareTo(g2) < 0 ? g1 + "|" + g2 : g2 + "|" + g1; if (pairs.merge(key, 1, Integer::sum) >= 2) redirects = 2; }
            r.redir2 = redirects >= 2;
        } else r.still = 0;
        if (hop != null && here != null && here.getZ() == 0 && hop.getZ() == 0 && cheb(xyz(here), xyz(hop)) <= 3 && NavGrid.ready()) r.wall = !lineClear(here.getX(), here.getY(), hop.getX(), hop.getY());
    }

    static String word(String l, int from) { int e = from; while (e < l.length() && l.charAt(e) != ' ' && l.charAt(e) != ';' && l.charAt(e) != ')') e++; return l.substring(from, e); }

    /** A straight cardinal walk from a to b through NavGrid edges, x first or y first; false when both orders hit a wall. */
    static boolean lineClear(int x0, int y0, int x1, int y1) { return walk(x0, y0, x1, y1, true) || walk(x0, y0, x1, y1, false); }
    static boolean walk(int x, int y, int x1, int y1, boolean xFirst) {
        for (int guard = 0; guard < 12 && (x != x1 || y != y1); guard++) {
            int d; boolean mx = xFirst ? x != x1 : y == y1;
            if (mx) d = x1 > x ? 1 : 3; else d = y1 > y ? 0 : 2;
            if (!NavGrid.canStep(x, y, d)) return false;
            x += NavGrid.DX[d]; y += NavGrid.DY[d];
        }
        return true;
    }

    // ------------------------------------------------------------ the bot snapshot (§2.1)

    static Map<String, Object> snapshot(Player bot, Watchdog.Goal g, Trace tr, Location here, boolean game) {
        LinkedHashMap<String, Object> s = new LinkedHashMap<>();
        String k = Watchdog.key(bot);
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(k);
        s.put("role", tr != null && tr.role != null ? tr.role : br != null ? "adventurer" : "other");
        s.put("shift", br != null ? (System.currentTimeMillis() > br.shiftEnd ? "over" : "on") : "-");
        try { s.put("cb", bot.getProperties().getCurrentCombatLevel()); } catch (Throwable ignored) { }
        try { s.put("agi", bot.getSkills().getStaticLevel(Skills.AGILITY)); } catch (Throwable ignored) { }
        AfkTasks.Runner r = game ? AfkTasks.RUNNING.get(k) : null; // a plain HashMap: game thread only
        try {
            if (r != null && r.task != null) {
                int sk = skillOf(r.task.kind);
                if (sk >= 0) { s.put("job", Skills.SKILL_NAME[sk].toLowerCase(Locale.ROOT)); s.put("jobLvl", bot.getSkills().getStaticLevel(sk)); }
                else s.put("job", cut(r.label(), 24));
            }
        } catch (Throwable ignored) { }
        try { s.put("hp", list(bot.getSkills().getLifepoints(), bot.getSkills().getMaximumLifepoints())); } catch (Throwable ignored) { }
        try { s.put("run", (int) Math.round(bot.getSettings().getRunEnergy())); } catch (Throwable ignored) { }
        String w = g != null ? g.walker : "other";
        if ("grave".equals(w) || "quest".equals(w)) try { s.put("pray", list(bot.getSkills().getLevel(Skills.PRAYER), bot.getSkills().getStaticLevel(Skills.PRAYER))); } catch (Throwable ignored) { }
        try { s.put("qp", bot.getQuestRepository().getPoints()); } catch (Throwable ignored) { }
        try {
            LinkedHashMap<String, Object> q = new LinkedHashMap<>();
            Location[] spots = {g != null ? g.from : null, g != null ? g.to : null, here};
            for (Location l : spots) if (l != null) for (Gates.Gate gt : Gates.at(l)) if ("quest".equals(gt.kind) && q.size() < 6) { boolean ok; try { ok = gt.rule.ok(bot); } catch (Throwable t) { ok = false; } q.put(gt.rule.need(), ok); }
            s.put("quests", q);
        } catch (Throwable ignored) { }
        try { s.put("coins", Ledger.Wallet.coins(bot)); } catch (Throwable ignored) { }
        try {
            LinkedHashMap<String, Object> inv = new LinkedHashMap<>();
            int n = 0, food = 0, tele = 0; LinkedHashMap<String, Integer> notable = new LinkedHashMap<>();
            int[] tools = r != null && r.task != null ? r.task.tools : null;
            for (Item it : bot.getInventory().toArray()) {
                if (it == null) continue;
                n++;
                String nm = it.getName(); if (nm == null) nm = "item " + it.getId();
                boolean f = false, t = false;
                try { f = it.getDefinition() != null && it.getDefinition().hasAction("Eat"); } catch (Throwable ignored) { }
                String low = nm.toLowerCase(Locale.ROOT);
                t = low.contains("teleport") || low.endsWith(" tab") || it.getId() == 563; // tabs, teleport items, law runes
                if (f) food += it.getAmount(); if (t) tele += it.getAmount();
                if (f || t || it.getId() == 995 || AfkTasks.has(tools, it.getId())) notable.merge(nm, it.getAmount(), Integer::sum);
            }
            inv.put("n", n); inv.put("food", food); inv.put("tele", tele);
            List<Object> nl = new ArrayList<>();
            for (Map.Entry<String, Integer> e : notable.entrySet()) { if (nl.size() >= 6) break; nl.add(cut(e.getKey() + " x" + e.getValue(), 24)); }
            inv.put("notable", nl);
            s.put("inv", inv);
        } catch (Throwable ignored) { }
        try {
            LinkedHashMap<String, Object> eq = new LinkedHashMap<>();
            Item wpn = bot.getEquipment().get(3);
            eq.put("wpn", wpn == null ? "-" : cut(wpn.getName(), 24)); eq.put("n", bot.getEquipment().itemCount());
            s.put("eq", eq);
        } catch (Throwable ignored) { }
        try { s.put("combat", bot.inCombat()); } catch (Throwable ignored) { }
        try { s.put("poison", core.api.ContentAPIKt.isPoisoned(bot)); } catch (Throwable ignored) { }
        if (g != null) { s.put("goal", cut(g.walker + " " + g.kind + (g.last != null && !g.last.isEmpty() ? " (" + g.last + ")" : ""), 80)); s.put("step", cut(g.nav, 60)); }
        s.put("tripS", tr != null && tr.tripT > 0 ? (int) ((System.currentTimeMillis() - tr.tripT) / 1000) : -1);
        s.put("deaths", br != null ? br.deaths : 0);
        s.put("relog", tr != null && tr.relog);
        return s;
    }

    static int skillOf(AfkTasks.Kind k) {
        if (k == null) return -1;
        switch (k) { case TREE: return Skills.WOODCUTTING; case ROCK: return Skills.MINING; case FISH: return Skills.FISHING; case THIEVE: return Skills.THIEVING; case SMELT: return Skills.SMITHING; default: return -1; }
    }

    // ------------------------------------------------------------ classification (§4): pure, on the Rec; tools/bugs.py --reclassify mirrors it

    static int cheb(int[] a, int[] b) { return a == null || b == null ? 99 : Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1])); }
    static int[] toward(int[] s, int[] t) { return new int[]{s[0] + Integer.signum(t[0] - s[0]), s[1] + Integer.signum(t[1] - s[1]), s[2]}; }
    static boolean same(int[] a, int[] b) { return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }
    static String xy(int[] a) { return a == null ? "?" : a[0] + "," + a[1]; }

    /** The tiles a stall toward target crosses, up to 3 (the scan radius): the stall's neighbour toward it first. */
    static List<int[]> line(int[] s, int[] t) {
        List<int[]> out = new ArrayList<>(); int[] c = s;
        for (int i = 0; i < 3 && !(c[0] == t[0] && c[1] == t[1]); i++) { c = toward(c, t); out.add(c); }
        return out;
    }

    static void classify(Rec r) {
        if (!"path".equals(r.domain)) { if (r.reason == null || !r.reason.matches("[a-z0-9-]{1,24}")) { r.reason = "unknown"; if (r.rsub == null) r.rsub = cut(r.why, 40); } return; } // batch19c §2: the detector decided
        if (r.src != null && r.src.startsWith("replay_") && r.why != null && r.why.startsWith("{replay:")) { int e = r.why.indexOf("} "), sl = r.why.indexOf('/'); // batch 19c B4: the replayer's reason hint (Scenarios.Replayer.REPLAY_HINTS)
            if (e > 8 && sl > 8 && sl < e) { String code = r.why.substring(8, sl), sub = r.why.substring(sl + 1, e); if (!"-".equals(code)) { set(r, code, sub); return; } r.hintSub = sub; } }
        String why = r.why == null ? "" : r.why.toLowerCase(Locale.ROOT), src = r.src == null ? "" : r.src;
        int[] s = r.stall;
        int[] tgt = r.next != null ? r.next : r.to;
        if (tgt != null && s != null && tgt[2] != s[2]) tgt = null; // another plane: no "toward"
        // 1 death
        if (r.died || why.contains("died") || why.contains("crumbled")) { set(r, "death", r.died ? "died on the trip" : r.why); return; }
        // 2 combat-interrupt
        if (r.inCombat || r.h60 >= 3 || "replay_skip".equals(src) && why.contains("attacked")) {
            String who = ""; for (Npc n : r.npcs) if (n.agg) { who = ", " + n.n + " " + n.id; break; }
            set(r, "combat-interrupt", r.h60 + " hits in 60 s" + (r.inCombat ? ", in combat" : "") + who); return;
        }
        // 3 timeout
        boolean deadline = "replay_timeout".equals(src) || "never_reached".equals(src) && why.contains("deadline") || "failed_trip".equals(src) && why.contains("timed out") || "quest_fail".equals(src) && why.contains("time limit");
        if (deadline && r.d24 >= 10) { set(r, "timeout", "still moving, " + (r.to != null && s != null ? cheb(s, r.to) + " tiles short" : "goal unknown") + (r.tripS > 0 ? " at " + r.tripS / 60 + " min" : "")); return; }
        // 4 outside-grid
        if ("outside".equals(r.planAns) && !r.gateBox) { set(r, "outside-grid", r.planWhy != null ? r.planWhy : "off the surface grid"); return; }
        // 5 rule-gate
        if (r.gateId != null && r.gateUnmet) { set(r, "rule-gate", r.gateNeed); return; }
        if (s == null) { if (trapWhy(why)) { set(r, "trap-cell", cut(r.why, 40)); return; } set(r, "unknown", r.hintSub != null ? r.hintSub : r.why); return; } // batch 19c B4: hint rsub; batch 21 E: trap-cell
        // 6 door-closed
        if (tgt != null) for (Obj o : r.objs) {
            if (cheb(o.at, s) > 2 || !"closed".equals(o.st) || o.rule != null || !(o.ty < 4 || NavGrid.isGateName(o.n))) continue;
            int dot = (o.at[0] - s[0]) * (tgt[0] - s[0]) + (o.at[1] - s[1]) * (tgt[1] - s[1]);
            if (dot >= 0 && cheb(o.at, tgt) <= cheb(s, tgt)) { set(r, "door-closed", o.n + " " + o.id + " closed at " + xy(o.at)); return; }
        }
        // 7 npc-blocking
        if (tgt != null && r.npcRun >= 3) {
            int[] step = toward(s, tgt);
            for (Npc n : r.npcs) if (same(n.at, step) || same(n.at, r.next)) { set(r, "npc-blocking", n.n + " " + n.id + " on the next tile for " + (int) (r.npcRun * 4.8) + " s"); return; }
        }
        // 8 no-route
        if ("no-route".equals(r.planAns) || r.split) { set(r, "no-route", r.planWhy != null ? r.planWhy : "no route"); return; }
        // 9 handoff
        if (r.px16 || "climber".equals(r.drv) || r.to != null && r.to[2] != s[2]) { set(r, "handoff", "plane " + s[2] + (r.to != null ? " -> " + r.to[2] : "") + (r.px16 ? ", the path changed plane" : "") + ("climber".equals(r.drv) ? ", climber driving" : "")); return; }
        // 10 wrong-side-hop
        if ("planner".equals(r.drv) && r.next != null && cheb(r.next, s) <= 3 && r.near3) {
            Obj across = null;
            for (Obj o : r.objs) if ("solid".equals(o.st) && !same(o.at, s) && cheb(o.at, s) <= cheb(r.next, s) && cheb(o.at, r.next) <= cheb(r.next, s)) { across = o; break; }
            if (r.wall || across != null) { set(r, "wrong-side-hop", "hop " + xy(r.next) + " " + cheb(r.next, s) + " tiles off across " + (across != null ? across.n + " " + across.id : "a wall")); return; }
        }
        // 11a loop (batch 21 A): the loop detector's own record
        if (why.startsWith("loop:")) { set(r, "loop", cut(r.why.substring(5).trim(), 40)); return; }
        // 11 oscillation
        if (r.d29 <= 4 && r.m29 >= 16 || r.redir2) { set(r, "oscillation", r.redir2 ? "redirects between the same two goals" : r.d29 + " tiles, " + r.m29 + " moves"); return; }
        // 12 blocked-object
        List<int[]> cells = new ArrayList<>(); cells.add(s);
        if (tgt != null) cells.addAll(line(s, tgt)); else for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) cells.add(new int[]{s[0] + dx, s[1] + dy, s[2]});
        if (r.next != null) cells.add(r.next);
        boolean door = false; for (Obj o : r.objs) if ("closed".equals(o.st) && cheb(o.at, s) <= 2) door = true;
        if (!door) for (Obj o : r.objs) {
            if (!"solid".equals(o.st)) continue;
            boolean on = false; for (int[] c : cells) if (same(o.at, c)) on = true;
            if (!on) continue;
            boolean npc = false; for (Npc n : r.npcs) if (same(n.at, o.at)) npc = true;
            if (!npc) { set(r, "blocked-object", o.n + " " + o.id + (same(o.at, s) ? " on the stall tile" : " on the way") + (tgt == null ? " (no goal known)" : "")); return; }
        }
        // 13 engine-pathfinder-giveup (batch 22: not when the Watchdog calls the stall a learned trap cell or enclosure: that is 14, TestB19A1 planner off)
        if (("old".equals(r.drv) || "routemap".equals(r.drv) && r.next != null && cheb(r.next, s) > 2) && r.still >= 60 && !trapWhy(why)) { set(r, "engine-pathfinder-giveup", "no movement for " + r.still + " s on the " + r.drv + " walker" + ("off".equals(r.planAns) ? " (planner off)" : "")); return; }
        // 14 trap-cell (batch 21 E): idle in a cell the Watchdog learned as a trap, or (batch 21 review2) in one of its hard-coded
        // enclosures (Watchdog.rescue's "in an enclosure": the pen fence is often past the 7x7 scan, so near.objs has no fence), with nothing more specific found
        if (trapWhy(why)) { set(r, "trap-cell", cut(r.why, 40)); return; }
        // 15 unknown
        set(r, "unknown", r.hintSub != null ? r.hintSub : r.why); // batch 19c B4: hint rsub
    }

    /** Rule 14's text facts (lower-cased why): Watchdog's learned trap cell (idle/rescue) or hard-coded enclosure (rescue, Watchdog.enclosure). */
    static boolean trapWhy(String why) { return why.contains("learned trap cell") || why.contains("in an enclosure"); }

    static void set(Rec r, String reason, String rsub) { r.reason = reason; r.rsub = cut(rsub == null ? "" : rsub, 40); }

    /** §5.1 grouping key; null for "unknown" (never grouped). */
    static String bugId(Rec r) {
        // batch 21 E: an unclassified path record still gets a bug, by its stall cell (261 of 1,876 records on Oct 25-26 had none and
        // never reached the bug list); other domains keep "no bug" for unknown
        if ("unknown".equals(r.reason) && "path".equals(r.domain) && r.stall != null) { r.cell = new int[]{r.stall[0] >> 4, r.stall[1] >> 4, r.stall[2]}; return "b-unknown-" + r.cell[0] + "," + r.cell[1] + "," + r.cell[2]; }
        if (r.reason == null || "unknown".equals(r.reason)) return null;
        if ("exception".equals(r.reason)) return "b-exception-" + r.excWhere + "-" + simple(r.excCls);
        String q = r.reason; // batch19c §2: codes are unique across domains, so the id needs no domain part
        if (!"path".equals(r.domain) && r.key != null && !r.key.isEmpty()) return "b-" + q + "-" + r.key;
        boolean dest = "path".equals(r.domain) && ("no-route".equals(r.reason) || "outside-grid".equals(r.reason) || "timeout".equals(r.reason)) && r.to != null;
        int[] at = dest ? r.to : r.stall;
        if (at == null) return "b-" + q + "-x";
        String id = "b-" + q + "-" + (dest ? "dest-" : "") + (at[0] >> 4) + "," + (at[1] >> 4) + "," + at[2];
        if ("rule-gate".equals(r.reason) && r.gateId != null) id += "-" + r.gateId;
        r.cell = new int[]{at[0] >> 4, at[1] >> 4, at[2]};
        return id;
    }

    static String simple(String cls) { if (cls == null) return "Throwable"; int i = cls.lastIndexOf('.'); return i >= 0 ? cls.substring(i + 1) : cls; }

    /** "3264-3279, 3216-3231, 0" plus a landmark: a Gate whose box meets the cluster, or the Al Kharid gate. */
    static String place(int[] cell, String gateId) {
        if (cell == null) return "";
        int x = cell[0] << 4, y = cell[1] << 4;
        String t = x + "-" + (x + 15) + ", " + y + "-" + (y + 15) + ", " + cell[2];
        if ("alkharid-toll".equals(gateId) || x <= 3268 && x + 15 >= 3268 && y <= 3228 && y + 15 >= 3227 && cell[2] == 0) return t + " (Al Kharid gate)";
        try { for (Gates.Gate gt : Gates.GATES) if (gt.x0 <= x + 15 && gt.x1 >= x && gt.y0 <= y + 15 && gt.y1 >= y && (gt.x1 - gt.x0) * (gt.y1 - gt.y0) < 400 * 400) return t + " (" + gt.name + ")"; } catch (Throwable ignored) { }
        return t;
    }

    // ------------------------------------------------------------ counting, caps, the queue

    static int fidBase; static final java.util.concurrent.atomic.AtomicInteger FID = new java.util.concurrent.atomic.AtomicInteger();
    static String newFid(long t) { return "f-" + Watchdog.Daily.utc(t).replace("-", "") + "-" + (fidBase + FID.incrementAndGet()); }

    /** Counts it (Daily and the bug, always), then writes it if the caps allow. Returns the fid when queued. Any thread. */
    static String accept(Rec r) {
        long now = r.t > 0 ? r.t : System.currentTimeMillis();
        return finish(r, allow(r, now));
    }

    /** batch 19c: the counting half of accept (allow already decided): Daily and the bug always; the queue when write. */
    static String finish(Rec r, boolean write) {
        long now = r.t > 0 ? r.t : System.currentTimeMillis();
        recorded++;
        if (write) r.fid = newFid(now);
        // batch 19a: the Daily keys Builder B's report (Watchdog.Daily.failures) reads; codes are unique across domains (batch19c §2), so the bare code is enough
        try { Watchdog.Daily.note("fail", r.reason); } catch (Throwable ignored) { }
        if (r.excCls != null) try { Watchdog.Daily.note("exc", r.excWhere + "\t" + simple(r.excCls) + "\t" + (r.frames != null && r.frames.length > 0 ? r.frames[0] : "-")); } catch (Throwable ignored) { }
        if (r.bug != null) { try { Watchdog.Daily.note("bug", r.bug); } catch (Throwable ignored) { } Bugs.count(r); }
        if (!write) { try { Watchdog.Daily.note("failcap", r.src); } catch (Throwable ignored) { } return null; }
        Writer.queue(r);
        return r.fid;
    }

    /** batch 19a review S1: allow()'s cap and per-bot gap test without counting anything, so a record that would be dropped is never built (its reason is lost; failcap counts it). */
    static boolean mayWrite(String bot, String domain, String src) {
        long now = System.currentTimeMillis();
        synchronized (CAPS) {
            long h = now / 3_600_000L, d = Math.floorDiv(now, 86_400_000L);
            if (h == capHour && hourN >= CAP_HOUR || d == capDay && dayN >= CAP_DAY) return false;
            if (h == capHour && "replay_skip".equals(src) && skipHourN >= SKIP_HOUR) return false;
            int[] dn = DOMAIN_N.get(domain); int[] dc = DOMAIN_CAP.getOrDefault(domain, new int[]{15, 150});
            if (dn != null && (h == capHour && dn[0] >= dc[0] || d == capDay && dn[1] >= dc[1])) return false;
            if ("path".equals(domain)) { Long last = LAST_BY.get(bot + "|" + src); if (last != null && now - last < BOT_GAP_MS) return false; }
            return true;
        }
    }

    static boolean allow(Rec r, long now) {
        synchronized (CAPS) {
            long h = now / 3_600_000L, d = Math.floorDiv(now, 86_400_000L);
            if (h != capHour) { capHour = h; hourN = 0; skipHourN = 0; for (int[] v : DOMAIN_N.values()) v[0] = 0; }
            if (d != capDay) { capDay = d; dayN = 0; excDayN = 0; for (int[] v : DOMAIN_N.values()) v[1] = 0; LAST_BY.clear(); BUG_DAY.clear(); }
            boolean exc = "exception".equals(r.src);
            boolean path = "path".equals(r.domain), slow = "economy".equals(r.domain) || "skilling".equals(r.domain) || "lifecycle".equals(r.domain);
            boolean server = !exc && !path && "-".equals(r.bot); // batch 19c §3.2: a server-level record: per (reason, key) = per bug, 60 s apart (the detector owns its window), ≤ 6 a day
            String by = exc ? "x|" + r.excWhere + "|" + r.excCls : path ? r.bot + "|" + r.src : server ? "s|" + (r.bug != null ? r.bug : r.reason) : r.bot + "|" + r.reason + "|" + r.domain; // batch19c §3.2: per (bot, reason) outside path
            Long last = LAST_BY.get(by);
            if (last != null && now - last < (exc ? EXC_GAP_MS : server ? BOT_GAP_MS : slow ? 10 * 60_000L : BOT_GAP_MS)) { throttled++; return false; }
            if (server) { Integer bd = BUG_DAY.get(by); if (bd != null && bd >= 6) { throttled++; return false; } }
            int[] dn = DOMAIN_N.computeIfAbsent(r.domain, x -> new int[2]); int[] dc = DOMAIN_CAP.getOrDefault(r.domain, new int[]{15, 150});
            boolean overDomain = !exc && (dn[0] >= dc[0] || dn[1] >= dc[1]); // exceptions: their own throttle and 200/day, not health's share
            if (hourN >= CAP_HOUR || dayN >= CAP_DAY || overDomain || "replay_skip".equals(r.src) && skipHourN >= SKIP_HOUR || exc && excDayN >= EXC_DAY) { capped++; return false; }
            hourN++; dayN++; dn[0]++; dn[1]++; if ("replay_skip".equals(r.src)) skipHourN++; if (exc) excDayN++;
            if (server) BUG_DAY.merge(by, 1, Integer::sum);
            if (LAST_BY.size() > 20_000) LAST_BY.clear();
            LAST_BY.put(by, now);
            return true;
        }
    }

    // ------------------------------------------------------------ serialisation (writer thread)

    static String cut(String s, int n) { if (s == null) return null; return s.length() <= n ? s : s.substring(0, n); }
    static int[] xyz(Location l) { return new int[]{l.getX(), l.getY(), l.getZ()}; }
    static List<Object> list(Object... a) { return new ArrayList<>(java.util.Arrays.asList(a)); }

    static String pathText(int[] t, int max) {
        StringBuilder b = new StringBuilder();
        int from = Math.max(0, t.length - max);
        for (int i = from; i < t.length; i++) {
            int x = px(t[i]), y = py(t[i]), z = pz(t[i]);
            if (i > from) { int jx = Math.abs(x - px(t[i - 1])), jy = Math.abs(y - py(t[i - 1])); b.append(Math.max(jx, jy) > 20 ? "~>" : ">"); }
            b.append(x).append(',').append(y);
            if (i == from ? z != 0 : z != pz(t[i - 1])) b.append(',').append(z);
        }
        return b.toString();
    }

    /** The record as one JSON line, trimmed in the §2 order until it fits LINE_MAX; reason, bug, stall, snap core and sid are never dropped. */
    static String line(Rec r) {
        int[] lim = {12, 12, 8, 32, 6, 6, 1}; // log lines, objs, npcs, path tiles, inv notable, exc frames, ctx kept
        List<String> trim = new ArrayList<>();
        String out = null;
        for (int step = 0; step <= 9; step++) {
            out = Json.write(toMap(r, lim, trim, step >= 7, step >= 8, step >= 9));
            if (out.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= LINE_MAX) return out;
            switch (step) {
                case 0: lim[0] = 6; trim.add("log"); break;
                case 1: if (r.ctx != null) { lim[6] = 0; trim.add("ctx"); } break; // batch19c §2: ctx after log, before near/inv
                case 2: lim[1] = 6; lim[2] = 4; trim.add("near"); break;
                case 3: lim[3] = 16; trim.add("path"); break;
                case 4: lim[4] = 4; trim.add("inv"); break;
                case 5: lim[5] = 3; trim.add("frames"); break;
                case 6: trim.add("long-strings"); break; // step 7: log lines to 60 chars, why to 80
                case 7: lim[0] = 0; trim.add("log-all"); break;
                case 8: lim[1] = 0; lim[2] = 0; lim[3] = 4; trim.add("near-all"); break;
                default: break;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(Rec r, int[] lim, List<String> trim, boolean shortStrings, boolean dropSnapExtras, boolean minimal) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1); m.put("t", r.t); m.put("fid", r.fid); m.put("domain", r.domain); m.put("src", r.src); m.put("sid", r.sid); m.put("bot", r.bot);
        Map<String, Object> snap = r.snap;
        if (snap != null) {
            snap = new LinkedHashMap<>(snap);
            Object inv = snap.get("inv");
            if (inv instanceof Map) { Map<String, Object> iv = new LinkedHashMap<>((Map<String, Object>) inv); Object nl = iv.get("notable"); if (nl instanceof List && ((List<?>) nl).size() > lim[4]) iv.put("notable", new ArrayList<>(((List<?>) nl).subList(0, lim[4]))); snap.put("inv", iv); }
            if (dropSnapExtras) { snap.remove("quests"); snap.remove("goal"); snap.remove("step"); }
        }
        m.put("snap", snap);
        boolean path = "path".equals(r.domain) && !"exception".equals(r.src);
        if (path) {
            m.put("walker", r.walker); m.put("dtype", r.dtype); m.put("from", arr(r.from)); m.put("to", arr(r.to)); m.put("drv", r.drv);
            LinkedHashMap<String, Object> plan = new LinkedHashMap<>();
            plan.put("ans", r.planAns); if (r.planLen >= 0) plan.put("len", r.planLen); if (r.hops >= 0) { plan.put("hops", r.hops); plan.put("hop", r.hop); }
            if (r.planAge >= 0) plan.put("age", r.planAge); if (r.replans >= 0) plan.put("replans", r.replans); if (r.planWhy != null) plan.put("why", r.planWhy); if (r.next != null) plan.put("next", arr(r.next)); if (r.split) plan.put("split", true);
            m.put("plan", plan);
            m.put("path", pathText(r.tiles, lim[3]));
            m.put("stall", arr(r.stall));
            LinkedHashMap<String, Object> near = new LinkedHashMap<>();
            List<Object> objs = new ArrayList<>();
            for (int i = 0; i < r.objs.size() && i < lim[1]; i++) { Obj o = r.objs.get(i); LinkedHashMap<String, Object> j = new LinkedHashMap<>(); j.put("id", o.id); j.put("n", o.n); j.put("at", arr(o.at)); j.put("st", o.st); j.put("ty", o.ty); if (o.rule != null) j.put("rule", o.rule); objs.add(j); }
            List<Object> npcs = new ArrayList<>();
            for (int i = 0; i < r.npcs.size() && i < lim[2]; i++) { Npc n = r.npcs.get(i); LinkedHashMap<String, Object> j = new LinkedHashMap<>(); j.put("id", n.id); j.put("n", n.n); j.put("at", arr(n.at)); j.put("agg", n.agg); j.put("lvl", n.lvl); npcs.add(j); }
            near.put("objs", objs); near.put("npcs", npcs); near.put("run", r.npcRun);
            m.put("near", near);
            LinkedHashMap<String, Object> cb = new LinkedHashMap<>();
            cb.put("in", r.inCombat); cb.put("hits", r.hits); cb.put("h60", r.h60); cb.put("died", r.died); cb.put("poison", r.poison); cb.put("since", r.combatSince);
            m.put("combat", cb);
            LinkedHashMap<String, Object> mv = new LinkedHashMap<>();
            mv.put("d24", r.d24); mv.put("d29", r.d29); mv.put("m29", r.m29); mv.put("still", r.still); mv.put("px16", r.px16); mv.put("near3", r.near3); mv.put("wall", r.wall); mv.put("redir2", r.redir2);
            m.put("mv", mv);
            if (r.gateId != null || r.gateBox) { LinkedHashMap<String, Object> g = new LinkedHashMap<>(); g.put("id", r.gateId); g.put("need", r.gateNeed); g.put("unmet", r.gateUnmet); g.put("box", r.gateBox); m.put("gate", g); }
            m.put("stuckS", r.stuckS); m.put("tripS", r.tripS);
        } else {
            if (r.stall != null) { m.put("stall", arr(r.stall)); if (r.drv != null) m.put("drv", r.drv); }
            if (r.from != null || r.tripS >= 0) { m.put("walker", r.walker); m.put("from", arr(r.from)); m.put("to", arr(r.to)); m.put("tripS", r.tripS); } // batch 19c §2: trip keys when a trip was in flight
            if (!r.objs.isEmpty() || !r.npcs.isEmpty()) { // batch 19c §2: near only when the detector asked for the scan
                LinkedHashMap<String, Object> near = new LinkedHashMap<>(); List<Object> objs = new ArrayList<>(), npcs = new ArrayList<>();
                for (int i = 0; i < r.objs.size() && i < lim[1]; i++) { Obj o = r.objs.get(i); LinkedHashMap<String, Object> j = new LinkedHashMap<>(); j.put("id", o.id); j.put("n", o.n); j.put("at", arr(o.at)); j.put("st", o.st); objs.add(j); }
                for (int i = 0; i < r.npcs.size() && i < lim[2]; i++) { Npc n = r.npcs.get(i); LinkedHashMap<String, Object> j = new LinkedHashMap<>(); j.put("id", n.id); j.put("n", n.n); j.put("at", arr(n.at)); j.put("lvl", n.lvl); npcs.add(j); }
                near.put("objs", objs); near.put("npcs", npcs); m.put("near", near);
            }
        }
        List<Object> log = new ArrayList<>();
        int from = Math.max(0, r.log.length - lim[0]);
        for (int i = from; i < r.log.length; i++) log.add(cut(r.log[i], shortStrings ? 60 : 160));
        if (!minimal || !log.isEmpty()) m.put("log", log);
        if (r.ctx != null) m.put("ctx", lim[6] > 0 ? r.ctx : "trimmed");
        m.put("reason", r.reason); m.put("rsub", r.rsub);
        if (r.bug != null) m.put("bug", r.bug);
        m.put("why", cut(r.why, shortStrings ? 80 : 160));
        if (r.excCls != null) {
            LinkedHashMap<String, Object> e = new LinkedHashMap<>();
            e.put("cls", r.excCls); e.put("msg", cut(r.excMsg, shortStrings ? 60 : 200)); e.put("where", r.excWhere);
            List<Object> fr = new ArrayList<>(); if (r.frames != null) for (int i = 0; i < r.frames.length && i < lim[5]; i++) fr.add(r.frames[i]);
            e.put("frames", fr); m.put("exc", e);
        }
        if (!trim.isEmpty()) m.put("trim", new ArrayList<>(trim));
        return m;
    }

    static List<Object> arr(int[] a) { if (a == null) return null; List<Object> l = new ArrayList<>(); for (int v : a) l.add(v); return l; }

    /** batch 19a: a tiny JSON writer with stable key order (LinkedHashMap) and ASCII output; json-simple's maps are unordered. */
    static final class Json {
        static String write(Object o) { StringBuilder b = new StringBuilder(512); val(b, o); return b.toString(); }
        @SuppressWarnings("unchecked")
        static void val(StringBuilder b, Object o) {
            if (o == null) b.append("null");
            else if (o instanceof String) str(b, (String) o);
            else if (o instanceof Boolean || o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) b.append(o);
            else if (o instanceof Number) { double d = ((Number) o).doubleValue(); if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null"); else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d); }
            else if (o instanceof Map) { b.append('{'); boolean f = true; for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) { if (!f) b.append(','); f = false; str(b, String.valueOf(e.getKey())); b.append(':'); val(b, e.getValue()); } b.append('}'); }
            else if (o instanceof Iterable) { b.append('['); boolean f = true; for (Object x : (Iterable<Object>) o) { if (!f) b.append(','); f = false; val(b, x); } b.append(']'); }
            else if (o instanceof int[]) val(b, arr((int[]) o));
            else str(b, String.valueOf(o));
        }
        static void str(StringBuilder b, String s) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c == '\n') b.append("\\n"); else if (c == '\r') b.append("\\r"); else if (c == '\t') b.append("\\t");
                else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            b.append('"');
        }
    }

    // ------------------------------------------------------------ writing: Builder B's SurveyWriter thread (one writer for the survey files)

    static final class Writer {
        static volatile boolean hooked, saveQueued; static volatile long lastBugSave; static String lastDay;

        /** Startup: the shutdown hook that queues a last bugs.json save and waits for SurveyWriter (5 s bound). */
        static void start() {
            if (hooked) return;
            hooked = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { SurveyWriter.flushNow(2_500); SurveyWriter.task(Bugs::save); SurveyWriter.flushNow(2_500); } catch (Throwable ignored) { } }, "Failures-shutdown"));
        }

        /** Game thread (or any): the record is serialised, trimmed and appended on the SurveyWriter thread, in queue order. */
        static void queue(Rec r) { SurveyWriter.task(() -> write(r)); }

        /** SurveyWriter thread: one record -> one line in failures/<its UTC day>.jsonl; the 14-file retention at a new day. */
        static void write(Rec r) {
            try {
                String day = Watchdog.Daily.utc(r.t);
                if (!day.equals(lastDay)) { lastDay = day; housekeeping(); }
                SurveyWriter.append(new File(failDir(), day + ".jsonl").getPath(), line(r));
                written++;
            } catch (Throwable t) { dropped++; complain("serialise", t); }
        }

        /** bugs.json load-merge-save on the SurveyWriter thread, at most once a minute when dirty (the sampler asks; flushNow forces). */
        static void maybeSave(long now, boolean force) {
            if (saveQueued || !force && (!Bugs.dirty || now - lastBugSave < 60_000L)) return;
            saveQueued = true; lastBugSave = now;
            SurveyWriter.task(() -> { saveQueued = false; Bugs.save(); });
        }

        /** 14 daily files kept (names sort by date). */
        static void housekeeping() {
            File[] fs = failDir().listFiles((d, n) -> n.length() == 16 && n.endsWith(".jsonl") && n.charAt(4) == '-' && n.charAt(7) == '-');
            if (fs == null || fs.length <= 14) return;
            java.util.Arrays.sort(fs);
            for (int i = 0; i < fs.length - 14; i++) if (!fs[i].delete()) System.out.println("[Failures] could not delete old " + fs[i].getName());
        }
    }

    /** Waits for every queued record and a bugs.json save. Tests and operators; never from the game thread except in a trial. */
    static boolean flushNow(long maxWaitMs) {
        try { long t0 = System.currentTimeMillis(); SurveyWriter.task(Bugs::save); boolean a = SurveyWriter.flushNow(maxWaitMs); return SurveyWriter.flushNow(Math.max(0, maxWaitMs - (System.currentTimeMillis() - t0))) && a; } catch (Throwable t) { return false; } // twice: a record task appends its line behind the first marker
    }

    // ------------------------------------------------------------ bugs (§5)

    static final class Bug {
        String id, domain = "path", reason, rsub = "", place = ""; int[] cell; Map<String, Object> exc;
        long first, last; int count;
        TreeMap<String, Integer> days = new TreeMap<>(); Map<String, Integer> sources = new LinkedHashMap<>();
        List<String> examples = new ArrayList<>(), fids = new ArrayList<>(), bots = new ArrayList<>();
        Map<String, Object> snap;
        String status = "open", statusBy = "live", note = ""; long statusT;
        List<Map<String, Object>> replays = new ArrayList<>();
        boolean reopen; // delta only: a live record arrived for a not-reproduced / fixed bug
        Map<String, Object> extra = new LinkedHashMap<>(); // batch 19a: entry keys this build does not know (19c "replay", hand-added fields), written back as read

        Bug copy() {
            Bug b = new Bug(); b.id = id; b.extra.putAll(extra); b.domain = domain; b.reason = reason; b.rsub = rsub; b.place = place; b.cell = cell; b.exc = exc; b.first = first; b.last = last; b.count = count;
            b.days.putAll(days); b.sources.putAll(sources); b.examples.addAll(examples); b.fids.addAll(fids); b.bots.addAll(bots); b.snap = snap;
            b.status = status; b.statusBy = statusBy; b.note = note; b.statusT = statusT; for (Map<String, Object> r : replays) b.replays.add(new LinkedHashMap<>(r));
            return b;
        }
    }

    static final class Bugs {
        static final Object LOCK = new Object();
        static Map<String, Bug> VIEW = new LinkedHashMap<>();   // the current truth (file as last saved + every delta since); guarded by LOCK
        static Map<String, Bug> SAVED = new LinkedHashMap<>();  // what the last save wrote; guarded by LOCK
        static Map<String, Bug> DELTA = new LinkedHashMap<>();  // increments since the last save; guarded by LOCK
        static final Map<String, String> BY_SID = new ConcurrentHashMap<>(); // example scenario id -> bug id
        static volatile boolean dirty; static volatile int bugCapped, saves, loadFailed;
        static final Map<String, Integer> REPLAYS_TODAY = new ConcurrentHashMap<>(); static volatile long replayDay = -1;

        static File file() { return new File(Scenarios.rDir(), "bugs.json"); }

        static int[] statusCounts() {
            int[] n = new int[3];
            synchronized (LOCK) { for (Bug b : VIEW.values()) n["open".equals(b.status) ? 0 : "not-reproduced".equals(b.status) ? 1 : 2]++; }
            return n;
        }

        /** Startup: our bugs.json, or on a farm world the shared scenarios folder's copy as the seed. */
        static void load() {
            File f = file();
            if (!f.exists()) { File seed = new File(Scenarios.sDir(), "bugs.json"); if (seed.exists()) f = seed; }
            Map<String, Bug> m = read(f);
            if (m == null) { loadFailed++; return; }
            synchronized (LOCK) { SAVED = m; VIEW = new LinkedHashMap<>(); for (Bug b : m.values()) VIEW.put(b.id, b.copy()); index(); }
        }

        /** The file's entries, an empty map when there is no file, null when it does not parse (then nothing is saved over it). */
        static Map<String, Bug> read(File f) {
            Map<String, Bug> out = new LinkedHashMap<>();
            if (f == null || !f.exists()) return out;
            try (java.io.Reader rd = new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                JSONObject o = (JSONObject) new JSONParser().parse(rd);
                Object bugs = o.get("bugs");
                if (bugs instanceof JSONObject) for (Object k : ((JSONObject) bugs).keySet()) { Object e = ((JSONObject) bugs).get(k); if (e instanceof JSONObject) { Bug b = fromJson(String.valueOf(k), (JSONObject) e); out.put(b.id, b); } }
                return out;
            } catch (Throwable t) { System.out.println("[Failures] bugs.json does not parse, not saving over it until it does: " + t); return null; }
        }

        @SuppressWarnings("unchecked")
        static Bug fromJson(String id, JSONObject e) {
            Bug b = new Bug(); b.id = id;
            b.domain = str(e.get("domain"), "path"); b.reason = str(e.get("reason"), "unknown"); b.rsub = str(e.get("rsub"), ""); b.place = str(e.get("place"), "");
            Object c = e.get("cell"); if (c instanceof JSONArray && ((JSONArray) c).size() >= 3) { JSONArray a = (JSONArray) c; b.cell = new int[]{num(a.get(0)), num(a.get(1)), num(a.get(2))}; }
            if (e.get("exc") instanceof JSONObject) b.exc = new LinkedHashMap<>((JSONObject) e.get("exc"));
            b.first = lnum(e.get("first")); b.last = lnum(e.get("last")); b.count = num(e.get("count"));
            if (e.get("days") instanceof JSONObject) for (Object k : ((JSONObject) e.get("days")).keySet()) b.days.put(String.valueOf(k), num(((JSONObject) e.get("days")).get(k)));
            if (e.get("sources") instanceof JSONObject) for (Object k : ((JSONObject) e.get("sources")).keySet()) b.sources.put(String.valueOf(k), num(((JSONObject) e.get("sources")).get(k)));
            strs(e.get("examples"), b.examples); strs(e.get("fids"), b.fids); strs(e.get("bots"), b.bots);
            if (e.get("snap") instanceof JSONObject) b.snap = new LinkedHashMap<>((JSONObject) e.get("snap"));
            b.status = str(e.get("status"), "open"); b.statusBy = str(e.get("statusBy"), "live"); b.note = str(e.get("note"), ""); b.statusT = lnum(e.get("statusT"));
            if (e.get("replays") instanceof JSONArray) for (Object r : (JSONArray) e.get("replays")) if (r instanceof JSONObject) b.replays.add(new LinkedHashMap<>((JSONObject) r));
            for (Object k : e.keySet()) if (!KNOWN.contains(String.valueOf(k))) b.extra.put(String.valueOf(k), e.get(k));
            return b;
        }

        static Map<String, Object> toJson(Bug b) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id); m.put("domain", b.domain); m.put("reason", b.reason); m.put("rsub", b.rsub); m.put("cell", arr(b.cell)); m.put("place", b.place);
            if (b.exc != null) m.put("exc", b.exc);
            m.put("first", b.first); m.put("last", b.last); m.put("count", b.count); m.put("days", b.days); m.put("sources", b.sources);
            m.put("examples", b.examples); m.put("fids", b.fids); m.put("bots", b.bots); m.put("snap", b.snap);
            m.put("status", b.status); m.put("statusT", b.statusT); m.put("statusBy", b.statusBy); m.put("note", b.note); m.put("replays", b.replays);
            for (Map.Entry<String, Object> x : b.extra.entrySet()) m.putIfAbsent(x.getKey(), x.getValue());
            return m;
        }
        static final java.util.Set<String> KNOWN = new java.util.HashSet<>(java.util.Arrays.asList("id", "domain", "reason", "rsub", "cell", "place", "exc", "first", "last", "count", "days", "sources", "examples", "fids", "bots", "snap", "status", "statusT", "statusBy", "note", "replays"));

        static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }
        static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
        static long lnum(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }
        static void strs(Object o, List<String> to) { if (o instanceof JSONArray) for (Object x : (JSONArray) o) if (x != null) to.add(String.valueOf(x)); }

        static void newest(List<String> l, String v, int cap) { if (v == null) return; l.remove(v); l.add(v); while (l.size() > cap) l.remove(0); }

        /** A record's counts into the view and the delta (game thread or any: short, under LOCK, no I/O). */
        static void count(Rec r) {
            String day = Watchdog.Daily.utc(r.t);
            synchronized (LOCK) {
                Bug v = VIEW.get(r.bug);
                if (v == null) {
                    int inDomain = 0; for (Bug b : VIEW.values()) if (r.domain.equals(b.domain)) inDomain++;
                    if (VIEW.size() >= BUG_CAP || inDomain >= DOMAIN_BUGS.getOrDefault(r.domain, 200)) { bugCapped++; return; }
                    v = blank(r); VIEW.put(r.bug, v);
                }
                Bug d = DELTA.get(r.bug); if (d == null) { d = blank(r); d.status = null; DELTA.put(r.bug, d); }
                for (Bug b : new Bug[]{v, d}) {
                    b.count++; b.days.merge(day, 1, Integer::sum); b.sources.merge(r.src, 1, Integer::sum);
                    if (b.first == 0 || r.t < b.first) b.first = r.t; if (r.t > b.last) b.last = r.t;
                    newest(b.examples, r.sid, 5); newest(b.fids, r.fid, 5); newest(b.bots, r.bot, 5);
                    if (r.snap != null) b.snap = r.snap;
                    b.rsub = r.rsub == null ? b.rsub : r.rsub;
                }
                if (!FARM && reopenable(v.status)) { v.status = "open"; v.statusBy = "live"; v.statusT = r.t; v.note = "reopened " + day + ": " + v.note; d.reopen = true; }
                if (r.sid != null) BY_SID.put(r.sid, r.bug);
                if (r.replay != null) { v.extra.put("replay", r.replay); d.extra.put("replay", r.replay); } // batch 19c §2: the newest spec wins
                dirty = true;
            }
        }

        static boolean reopenable(String status) {
            if ("not-reproduced".equals(status)) return true;
            if (status != null && status.startsWith("fixed-in-batch-")) { try { return BATCH >= Integer.parseInt(status.substring(15)); } catch (Throwable t) { return false; } }
            return false;
        }

        static Bug blank(Rec r) {
            Bug b = new Bug(); b.id = r.bug; b.domain = r.domain; b.reason = r.reason; b.rsub = r.rsub == null ? "" : r.rsub; b.cell = r.cell; b.statusBy = FARM ? "farm" : "live"; b.statusT = r.t;
            b.place = r.excWhere != null ? r.excWhere : r.place != null ? r.place : place(r.cell, r.gateId);
            if (r.excCls != null) { LinkedHashMap<String, Object> e = new LinkedHashMap<>(); e.put("where", r.excWhere); e.put("cls", r.excCls); e.put("frame", r.frames != null && r.frames.length > 0 ? r.frames[0] : ""); b.exc = e; }
            return b;
        }

        /** §5.3 load-merge-save, writer thread only. The file's status/note (owner edits) win; counts are max(file, last save) + delta. */
        static void save() {
            Map<String, Bug> d;
            synchronized (LOCK) { if (!dirty && DELTA.isEmpty()) return; d = DELTA; DELTA = new LinkedHashMap<>(); dirty = false; }
            Map<String, Bug> file = read(file());
            if (file == null) { putBack(d); return; }
            Map<String, Bug> saved; synchronized (LOCK) { saved = new LinkedHashMap<>(SAVED); }
            long now = System.currentTimeMillis();
            Map<String, Bug> m = new LinkedHashMap<>(file);
            for (Bug s : saved.values()) {
                Bug e = m.get(s.id);
                if (e == null) { m.put(s.id, s.copy()); continue; } // nothing live is lost: a merge package without it gets it back
                e.count = Math.max(e.count, s.count); e.first = e.first == 0 ? s.first : s.first == 0 ? e.first : Math.min(e.first, s.first); e.last = Math.max(e.last, s.last);
                for (Map.Entry<String, Integer> x : s.days.entrySet()) e.days.merge(x.getKey(), x.getValue(), Math::max);
                for (Map.Entry<String, Integer> x : s.sources.entrySet()) e.sources.merge(x.getKey(), x.getValue(), Math::max);
                replays(e, s.replays);
            }
            for (Bug x : d.values()) {
                Bug e = m.get(x.id);
                if (e == null) { e = x.copy(); e.count = 0; e.days.clear(); e.sources.clear(); e.first = 0; e.last = 0; e.examples.clear(); e.fids.clear(); e.bots.clear(); e.replays.clear(); e.status = "open"; e.note = ""; m.put(x.id, e); }
                e.count += x.count;
                for (Map.Entry<String, Integer> y : x.days.entrySet()) e.days.merge(y.getKey(), y.getValue(), Integer::sum);
                while (e.days.size() > 14) e.days.pollFirstEntry();
                for (Map.Entry<String, Integer> y : x.sources.entrySet()) e.sources.merge(y.getKey(), y.getValue(), Integer::sum);
                if (x.first > 0 && (e.first == 0 || x.first < e.first)) e.first = x.first;
                if (x.last > e.last) { e.last = x.last; if (x.snap != null) e.snap = x.snap; if (x.rsub != null && !x.rsub.isEmpty()) e.rsub = x.rsub; }
                for (String s : x.examples) newest(e.examples, s, 5); for (String s : x.fids) newest(e.fids, s, 5); for (String s : x.bots) newest(e.bots, s, 5);
                replays(e, x.replays);
                Object rp = x.extra.get("replay"); if (rp != null) e.extra.put("replay", rp); // batch 19c
                if (x.reopen && reopenable(e.status)) { e.status = "open"; e.statusBy = "live"; e.statusT = now; e.note = "reopened " + Watchdog.Daily.utc(now) + ": " + (e.note == null ? "" : e.note); }
            }
            for (Bug e : m.values()) {
                while (e.days.size() > 14) e.days.pollFirstEntry();
                if (e.note != null && e.note.length() > 500) e.note = e.note.substring(0, 500);
                if ("open".equals(e.status) && !"owner".equals(e.statusBy) && notReproduced(e, now)) { e.status = "not-reproduced"; e.statusBy = FARM ? "farm" : "live"; e.statusT = now; }
            }
            prune(m, now);
            LinkedHashMap<String, Object> out = new LinkedHashMap<>(); LinkedHashMap<String, Object> bugs = new LinkedHashMap<>();
            for (Bug e : m.values()) bugs.put(e.id, toJson(e));
            out.put("v", 1); out.put("updated", now); out.put("host", HOST); out.put("bugs", bugs);
            try { file().getParentFile().mkdirs(); Ledger.writeAtomic(file(), Json.write(out)); saves++; }
            catch (Throwable t) { System.out.println("[Failures] could not save bugs.json: " + t); putBack(d); return; }
            synchronized (LOCK) {
                SAVED = m;
                Map<String, Bug> v = new LinkedHashMap<>(); for (Bug e : m.values()) v.put(e.id, e.copy());
                for (Bug x : DELTA.values()) { Bug e = v.get(x.id); if (e == null) { e = x.copy(); e.status = "open"; v.put(x.id, e); continue; } e.count += x.count; for (Map.Entry<String, Integer> y : x.days.entrySet()) e.days.merge(y.getKey(), y.getValue(), Integer::sum); for (Map.Entry<String, Integer> y : x.sources.entrySet()) e.sources.merge(y.getKey(), y.getValue(), Integer::sum); if (x.last > e.last) e.last = x.last; for (String s : x.examples) newest(e.examples, s, 5); replays(e, x.replays); }
                VIEW = v; index();
            }
        }

        static void putBack(Map<String, Bug> d) {
            synchronized (LOCK) {
                for (Bug x : d.values()) {
                    Bug cur = DELTA.get(x.id);
                    if (cur == null) { DELTA.put(x.id, x); continue; }
                    cur.count += x.count; for (Map.Entry<String, Integer> y : x.days.entrySet()) cur.days.merge(y.getKey(), y.getValue(), Integer::sum); for (Map.Entry<String, Integer> y : x.sources.entrySet()) cur.sources.merge(y.getKey(), y.getValue(), Integer::sum);
                    if (cur.first == 0 || x.first > 0 && x.first < cur.first) cur.first = x.first; cur.last = Math.max(cur.last, x.last);
                    for (String s : x.examples) newest(cur.examples, s, 5); for (String s : x.fids) newest(cur.fids, s, 5); for (String s : x.bots) newest(cur.bots, s, 5); replays(cur, x.replays); cur.reopen |= x.reopen;
                }
                dirty = true;
            }
        }

        /** Union by (t, world, bot), newest 10 kept, oldest first. */
        static void replays(Bug e, List<Map<String, Object>> add) {
            if (add == null || add.isEmpty()) return;
            Map<String, Map<String, Object>> u = new LinkedHashMap<>();
            for (Map<String, Object> r : e.replays) u.put(rkey(r), r);
            for (Map<String, Object> r : add) u.putIfAbsent(rkey(r), r);
            List<Map<String, Object>> l = new ArrayList<>(u.values());
            l.sort((a, b) -> Long.compare(lnum(a.get("t")), lnum(b.get("t"))));
            while (l.size() > 10) l.remove(0);
            e.replays = l;
        }
        static String rkey(Map<String, Object> r) { return lnum(r.get("t")) + "|" + r.get("world") + "|" + r.get("bot"); }

        /** open -> not-reproduced: the newest 3 replays within 7 days all pass, and no live record since the oldest of them ([Fable] reading of §5.3). */
        static boolean notReproduced(Bug e, long now) {
            List<Map<String, Object>> l = new ArrayList<>();
            for (Map<String, Object> r : e.replays) if (now - lnum(r.get("t")) <= 7 * 86_400_000L && !"proposal".equals(r.get("result"))) l.add(r);
            if (l.size() < 3) return false;
            l.sort((a, b) -> Long.compare(lnum(b.get("t")), lnum(a.get("t"))));
            long oldest = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) { if (!"pass".equals(l.get(i).get("result"))) return false; oldest = Math.min(oldest, lnum(l.get(i).get("t"))); }
            return e.last <= oldest;
        }

        /** Over 2,000: non-open entries untouched for 90 days and without a note go, oldest first. Open entries are never pruned. */
        static void prune(Map<String, Bug> m, long now) {
            if (m.size() <= BUG_CAP) return;
            List<Bug> c = new ArrayList<>();
            for (Bug e : m.values()) if (!"open".equals(e.status) && (e.note == null || e.note.isEmpty()) && now - Math.max(e.last, e.statusT) > 90 * 86_400_000L) c.add(e);
            c.sort((a, b) -> Long.compare(Math.max(a.last, a.statusT), Math.max(b.last, b.statusT)));
            for (Bug e : c) { if (m.size() <= BUG_CAP) break; m.remove(e.id); }
        }

        static void index() { BY_SID.clear(); for (Bug b : VIEW.values()) for (String s : b.examples) BY_SID.put(s, b.id); } // under LOCK
    }

    /** §5.4: a proven scenario that is an example of an open bug is due again every 3 days, at most 5 such replays a day on this world. */
    static boolean bugReplayDue(String sid) {
        if (!ON || sid == null) return false;
        try {
            String id = Bugs.BY_SID.get(sid); if (id == null) return false;
            long now = System.currentTimeMillis(), day = Math.floorDiv(now, 86_400_000L);
            if (day != Bugs.replayDay) { Bugs.replayDay = day; Bugs.REPLAYS_TODAY.clear(); }
            if (Bugs.REPLAYS_TODAY.getOrDefault(HOST, 0) >= 5) return false;
            synchronized (Bugs.LOCK) {
                Bug b = Bugs.VIEW.get(id); if (b == null || !"open".equals(b.status)) return false;
                long last = 0; for (Map<String, Object> r : b.replays) if (sid.equals(r.get("sid"))) last = Math.max(last, Bugs.lnum(r.get("t")));
                return now - last >= 3 * 86_400_000L;
            }
        } catch (Throwable t) { return false; }
    }

    /** §5.4: the open bug this scenario is an example of (trace mode), or null. */
    static String openBugFor(String sid) {
        if (!ON || sid == null) return null;
        String id = Bugs.BY_SID.get(sid); if (id == null) return null;
        synchronized (Bugs.LOCK) { Bug b = Bugs.VIEW.get(id); return b != null && "open".equals(b.status) ? id : null; }
    }

    /** §5.4: a replay's result (pass too) into the bug's replays (newest 10). bugId null: the bug the scenario belongs to. */
    static void replayResult(String bugId, String sid, String bot, String result, String fid, String why) {
        if (!ON) return;
        try {
            String id = bugId != null ? bugId : sid != null ? Bugs.BY_SID.get(sid) : null;
            if (id == null) return;
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("t", System.currentTimeMillis()); r.put("world", HOST); r.put("bot", bot); r.put("sid", sid); r.put("result", result); r.put("fid", fid); r.put("why", cut(why, 160));
            synchronized (Bugs.LOCK) {
                Bug v = Bugs.VIEW.get(id); if (v == null) return;
                Bugs.replays(v, Collections.singletonList(r));
                Bug d = Bugs.DELTA.get(id); if (d == null) { d = v.copy(); d.count = 0; d.days.clear(); d.sources.clear(); d.first = 0; d.last = 0; d.examples.clear(); d.fids.clear(); d.bots.clear(); d.replays.clear(); d.snap = null; Bugs.DELTA.put(id, d); }
                Bugs.replays(d, Collections.singletonList(r));
                if ("open".equals(v.status)) Bugs.REPLAYS_TODAY.merge(HOST, 1, Integer::sum);
                Bugs.dirty = true;
            }
        } catch (Throwable t) { complain("replayResult", t); }
    }

    /** §7: the bug's example snapshot (the newest), for the replayer's profile; a copy, or null. */
    static Map<String, Object> snapshotFor(String sid) {
        if (!ON || sid == null) return null;
        String id = Bugs.BY_SID.get(sid); if (id == null) return null;
        synchronized (Bugs.LOCK) { Bug b = Bugs.VIEW.get(id); return b == null || b.snap == null ? null : new LinkedHashMap<>(b.snap); }
    }

    // ------------------------------------------------------------ the daily report sections (§8; Watchdog.Daily.write appends this)

    /** TEST VIEW ONLY (batch 19a review S3): TestB19A2/A3 read it. The live report is Watchdog.Daily.failures (called by Daily.write): 19c and later edit THAT one. The §8 sections for day d (UTC); call off the game thread. */
    static String report(String d) {
        StringBuilder sb = new StringBuilder();
        try {
            String prev = Watchdog.Daily.minusDays(d, 1), from7 = Watchdog.Daily.minusDays(d, 6);
            Map<String, int[]> fail = Watchdog.Daily.counts(d, "fail"), cap = Watchdog.Daily.counts(d, "failcap");
            sb.append("\n## Failures by reason (data/survey/failures/)\n\n| domain | reason | 24 h | 7 days |\n|---|---|---|---|\n");
            for (String code : PATH_CODES) { int[] v = fail.getOrDefault(code, new int[2]); sb.append("| path | ").append(code).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" |\n"); }
            int[] exc = fail.getOrDefault("exception", new int[2]); // batch 19a: "fail" keys are bare codes (unique across domains, batch19c §2)
            List<String> other = new ArrayList<>(fail.keySet()); Collections.sort(other);
            for (String k : other) {
                if (CODES.get("path").contains(k) || "exception".equals(k)) continue;
                int[] v = fail.get(k); String dm = "?"; for (Map.Entry<String, List<String>> e : CODES.entrySet()) if (e.getValue().contains(k)) { dm = e.getKey(); break; }
                sb.append("| ").append(dm).append(" | ").append(md(k)).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" |\n");
            }
            int[] cp = new int[2]; for (int[] v : cap.values()) { cp[0] += v[0]; cp[1] += v[1]; }
            sb.append("| all | exceptions | ").append(exc[0]).append(" | ").append(exc[1]).append(" |\n");
            sb.append("| all | capped (counted, not written) | ").append(cp[0]).append(" | ").append(cp[1]).append(" |\n");
            sb.append("\nfailures capped: ").append(cp[0]).append("\n");
            List<Bug> bugs = new ArrayList<>();
            synchronized (Bugs.LOCK) { for (Bug b : Bugs.VIEW.values()) bugs.add(b.copy()); }
            Map<String, int[]> n = new HashMap<>();
            for (Bug b : bugs) { int[] v = new int[3]; v[0] = b.days.getOrDefault(d, 0); v[2] = b.days.getOrDefault(prev, 0); for (Map.Entry<String, Integer> e : b.days.subMap(from7, true, d, true).entrySet()) v[1] += e.getValue(); n.put(b.id, v); }
            List<Bug> open = new ArrayList<>(); for (Bug b : bugs) if ("open".equals(b.status) && !"exception".equals(b.reason) && n.get(b.id)[1] > 0) open.add(b);
            open.sort((a, b) -> { int[] x = n.get(a.id), y = n.get(b.id); return x[0] != y[0] ? y[0] - x[0] : x[1] != y[1] ? y[1] - x[1] : a.id.compareTo(b.id); });
            sb.append("\n## Top open bugs (data/survey/bugs.json)\n\n| bug | domain | 24 h | 7 d | place | reason / detail | example scenario | status | change |\n|---|---|---|---|---|---|---|---|---|\n");
            int k = 0;
            for (Bug b : open) {
                if (k++ >= 20) break;
                int[] v = n.get(b.id);
                String change = Watchdog.Daily.utc(b.first).equals(d) ? "new" : (v[0] - v[2] >= 0 ? "+" : "") + (v[0] - v[2]);
                sb.append("| ").append(md(b.id)).append(" | ").append(b.domain).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" | ").append(md(b.place)).append(" | ").append(md(b.reason + " / " + b.rsub))
                  .append(" | ").append(md(b.examples.isEmpty() ? "-" : b.examples.get(b.examples.size() - 1))).append(" | ").append(b.status).append(" | ").append(change).append(" |\n");
            }
            if (open.isEmpty()) sb.append("| none | | | | | | | | |\n");
            sb.append("\n## New bugs since yesterday\n\n| bug | domain | place | reason | count | example |\n|---|---|---|---|---|---|\n");
            k = 0;
            for (Bug b : bugs) {
                if (!Watchdog.Daily.utc(b.first).equals(d)) continue;
                if (k++ >= 20) break;
                sb.append("| ").append(md(b.id)).append(" | ").append(b.domain).append(" | ").append(md(b.place)).append(" | ").append(md(b.reason + " / " + b.rsub)).append(" | ").append(b.count).append(" | ").append(md(b.examples.isEmpty() ? "-" : b.examples.get(b.examples.size() - 1))).append(" |\n");
            }
            if (k == 0) sb.append("| none | | | | | |\n");
            sb.append("\n## Exceptions\n\n| where | class | 24 h | 7 d | first frame |\n|---|---|---|---|---|\n");
            List<Bug> ex = new ArrayList<>(); for (Bug b : bugs) if ("exception".equals(b.reason) && n.get(b.id)[1] > 0) ex.add(b);
            ex.sort((a, b) -> { int[] x = n.get(a.id), y = n.get(b.id); return x[0] != y[0] ? y[0] - x[0] : y[1] - x[1]; });
            k = 0;
            for (Bug b : ex) {
                if (k++ >= 10) break;
                int[] v = n.get(b.id); Map<String, Object> e = b.exc != null ? b.exc : new HashMap<>();
                sb.append("| ").append(md(String.valueOf(e.getOrDefault("where", b.place)))).append(" | ").append(md(String.valueOf(e.getOrDefault("cls", "?")))).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" | ").append(md(String.valueOf(e.getOrDefault("frame", "")))).append(" |\n");
            }
            if (ex.isEmpty()) sb.append("| none | | | | |\n");
        } catch (Throwable t) { sb.append("\n(failure sections: ").append(t).append(")\n"); }
        return sb.toString();
    }

    static String md(String s) { return s == null ? "" : s.replace("|", "/").replace("\n", " ").replace("\t", " "); }
}
