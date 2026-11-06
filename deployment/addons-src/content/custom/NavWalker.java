package content.custom;

import core.game.bots.AIPlayer;
import core.game.bots.ScriptAPI;
import core.game.node.entity.player.Player;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives a bot along a NavPlanner route (the design notes §3 step 4): one hop at a time, clicking doors on the way.
 * Behind {@code -Dnav=true}; with {@code -Dnav.shadow=true} it only logs what it would have done next to what the old walk did.
 *
 * Usage from a walker each tick: {@code Location step = NavWalker.step(bot, dest)}; null means "no route / not ready: use the old
 * way", otherwise walk to {@code step} (it is at most ~20 tiles away and reachable in a straight walk). The walker keeps calling
 * every tick; the hop advances as the bot arrives, doors are opened when the next hop is a door tile, and a stall of 30 ticks
 * replans from where the bot stands.
 */
public final class NavWalker {
    /** On with -Dnav=true, or when data/survey/nav.on exists (the live server start script takes no new flags); shadow logging likewise or with -Dnav.shadow. */
    static volatile boolean ON = Boolean.getBoolean("nav") || survey("nav.on").exists(); // re-read every minute (watch()): flipped or reverted without a restart
    static final boolean SHADOW = Boolean.getBoolean("nav.shadow") || new java.io.File(new java.io.File(core.ServerConstants.DATA_PATH, "survey"), "nav.shadow").exists();

    static final class State { NavPlanner.Route route; Location dest; int hop; Location last; int still; long doorTried; Location from; long plannedT, hopT; boolean tollOk; } // batch 18 item3: from = where the route to dest was first planned // batch 19b: plannedT = last successful plan (first, stall replan, next stretch), hopT = last hop advance; Watchdog.trying() counts a route touched in the last 2 min
    static final Map<String, State> STATES = new ConcurrentHashMap<>();
    static volatile int routed, noRoute, doorsOpened, doorsMissing, replans, noStandFrom, noStandTo, noCorridor, outside;

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }
    static java.io.File survey(String name) { return new java.io.File(new java.io.File(core.ServerConstants.DATA_PATH, "survey"), name); }

    /** Audit B-7: "bot|destination 8x8 cell" -> when to try again. A failed plan was re-run every step (up to 200k expansions). */
    static final Map<String, Long> NO_ROUTE = new ConcurrentHashMap<>();
    static String noRouteKey(Player bot, Location dest) { return key(bot) + "|" + (dest.getX() >> 3) + "," + (dest.getY() >> 3); }
    static void failed(Player bot, Location dest) {
        long now = System.currentTimeMillis();
        if (bot.isArtificial()) WINDOW_FAILS.add(noRouteKey(bot, dest)); // the auto-revert guard counts distinct bot/destination failures (batch 23 T2: bots only; a player's ::afk walk on the planner is not counted against nav.on)
        if (NO_ROUTE.size() > 5_000) NO_ROUTE.values().removeIf(t -> t < now);
        NO_ROUTE.put(noRouteKey(bot, dest), now + 60_000);
    }

    /** The next tile to walk to on the way to dest, or null when the planner has nothing (caller falls back to its old walk). */
    public static Location step(Player bot, Location dest) { return step(bot, dest, false); }

    /**
     * batch 21 A: payer = this walker pays the Al Kharid toll at the gate (AfkTasks.Runner.tollGate, the shop walk's and the
     * quest Mover's tollHop). Only a payer with 10 gp gets a route through the gate; every other walk goes round or has none.
     */
    public static Location step(Player bot, Location dest, boolean payer) {
        boolean tollOk = payer && bot != null && Ledger.Wallet.coins(bot) >= 10;
        Location hop = stepInner(bot, dest, tollOk);
        if (hop != null) Watchdog.fdrv(bot, Failures.DRV_PLANNER); // batch 19a design 3.1: the planner gave this hop
        return hop;
    }

    static int pocketNudges;
    static final boolean POCKET_ON = !"false".equals(System.getProperty("nav.pocket")); // batch 20 review: kill switch

    /**
     * batch 20 B2: a bot standing in a pocket (a region under NavPlanner.SMALL tiles, or a tile with no component) with its
     * destination in another region is moved onto the nearest big-region tile, when that is at most 2 tiles away: the replay
     * of 2026-10-26 showed bots inside the Exchange's counter ring (3163-3166, 3488-3490) can't walk out even by the engine's
     * steps, so a plan that starts beside them still stalls. Bots only; no charge (a step, not a teleport spell).
     */
    static boolean pocketNudge(Player bot, Location here, Location dest) {
        if (!POCKET_ON || !bot.isArtificial() || bot.getProperties().getTeleportLocation() != null) return false;
        int rh = NavPlanner.regionAt(here);
        if (rh != 0 && NavPlanner.regionSize(rh) >= NavPlanner.SMALL) return false;
        Location d = NavPlanner.standNear(dest);
        if (d != null && rh != 0 && NavPlanner.regionAt(d) == rh) return false; // the destination is in the same pocket
        Location out = NavPlanner.nearBig(here);
        if (out == null || out.getDistance(here) > 2) return false;
        bot.getProperties().setTeleportLocation(out); pocketNudges++;
        if (pocketNudges <= 20 || pocketNudges % 100 == 0) System.out.println("[Nav] " + bot.getUsername() + " stepped out of a pocket at " + here + " to " + out + " (" + pocketNudges + " so far)");
        return true;
    }

    static Location stepInner(Player bot, Location dest, boolean tollOk) { // batch 19a: the batch 18 step(); batch 21 A: tollOk
        if (!ON || bot == null || dest == null || !NavPlanner.built) return null;
        Location here = bot.getLocation();
        if (here.getZ() != 0 || dest.getZ() != 0 || !NavGrid.inBounds(here.getX(), here.getY()) || !NavGrid.inBounds(dest.getX(), dest.getY())) { outside++; outsideSeen(bot, here, dest); return null; } // dungeons and other floors: the old walk (phase 2 of the plan)
        if (here.getDistance(dest) <= 2) { STATES.remove(key(bot)); return null; }
        if (pocketNudge(bot, here, dest)) return null; // batch 20 B2: out of a closed pocket first (lands next tick)
        State s = STATES.computeIfAbsent(key(bot), k -> new State());
        if (s.route == null || s.dest == null || s.dest.getDistance(dest) > 2 || s.tollOk != tollOk) { // a new destination (batch 21 A: or a change in toll permission)
            Long retry = NO_ROUTE.get(noRouteKey(bot, dest));
            if (retry != null && retry > System.currentTimeMillis()) return null; // audit B-7: failed a moment ago, don't search again yet
            if (s.route != null && s.dest != null && here.getDistance(s.dest) > 4) redirected(bot, s, here, dest); // batch 18 item3: a route given up for another goal (not one that just arrived)
            s.route = NavPlanner.plan(here, dest, agility(bot), Heat.band(bot), tollOk); s.tollOk = tollOk; s.dest = dest; s.hop = 0; s.still = 0; s.last = here; s.from = here;
            if (s.route == null) { noRoute++; noRouteSeen(bot, here, dest, "first plan", true); failed(bot, dest); return null; } // batch 18 item3: reason always logged (rate-limited)
            routed++; s.plannedT = System.currentTimeMillis(); // batch 19b
        }
        // stall: no movement for 30 ticks of calls -> replan from here (a monster in a doorway, a door that didn't open)
        if (s.last != null && s.last.getX() == here.getX() && s.last.getY() == here.getY()) { if (++s.still >= 30) { s.still = 0; replans++; s.route = NavPlanner.plan(here, dest, agility(bot), Heat.band(bot), tollOk); s.hop = 0; if (s.route == null) { noRoute++; noRouteSeen(bot, here, dest, "stall replan", false); failed(bot, dest); return null; } s.plannedT = System.currentTimeMillis(); } } // batch 18 item3 // batch 19b: plannedT on a successful stall replan
        else s.still = 0;
        s.last = here;
        int hop0 = s.hop; // batch 19b: for hopT
        // advance past hops already reached (a door hop is never skipped: it has to be clicked); the stretch is only the first ~100 tiles: when it runs out, plan again from here
        while (s.hop < s.route.hops.size() && here.getDistance(s.route.hops.get(s.hop)) <= 2 && !s.route.doorTiles.contains(s.route.hops.get(s.hop))) s.hop++;
        if (s.hop > hop0) s.hopT = System.currentTimeMillis(); // batch 19b: the hop advanced
        if (s.hop >= s.route.hops.size()) {
            if (here.getDistance(dest) <= 4) return dest;
            s.route = NavPlanner.plan(here, dest, agility(bot), Heat.band(bot), tollOk); s.hop = 0;
            if (s.route == null) { noRoute++; noRouteSeen(bot, here, dest, "next stretch", false); failed(bot, dest); return null; } // batch 18 item3
            s.plannedT = System.currentTimeMillis(); // batch 19b: next stretch planned
            while (s.hop < s.route.hops.size() && here.getDistance(s.route.hops.get(s.hop)) <= 2) s.hop++;
            if (s.hop >= s.route.hops.size()) return dest;
        }
        Location next = s.route.hops.get(s.hop);
        // standing on a door tile stop: open the door toward the following hop, then carry on
        if (s.route.doorTiles.contains(next)) {
            if (here.getDistance(next) > 1) return next; // walk right up to the door first
            Location after = s.hop + 1 < s.route.hops.size() ? s.route.hops.get(s.hop + 1) : dest;
            long now = System.currentTimeMillis();
            // batch 21 item 4 (B9-8, Yanille's west gatehouse): the door is on the door tile or the tile across it, so look from the
            // door tile toward the step the path takes through it. Looking from here toward the next hop missed a door when that hop
            // turned a corner (2533,3091 -> 2535,3077: south-east, the door west), and the engine walk went round inside the wall.
            int di = s.route.doorTiles.indexOf(next);
            Location across = di >= 0 && di < s.route.doorAcross.size() ? s.route.doorAcross.get(di) : null;
            if (now - s.doorTried > 1_500) { s.doorTried = now; if (across != null ? openDoorToward(bot, next, across) : openDoorToward(bot, here, after)) doorsOpened++; else doorsMissing++; }
            s.hop++; s.hopT = now; // batch 19b: past the door hop
            return s.hop < s.route.hops.size() ? s.route.hops.get(s.hop) : dest;
        }
        return next;
    }

    /** Finds the door object between here and the direction of {@code toward} (on this tile or the next) and clicks Open. */
    static boolean openDoorToward(Player bot, Location here, Location toward) {
        int dx = Integer.signum(toward.getX() - here.getX()), dy = Integer.signum(toward.getY() - here.getY());
        Location[] cands = {here, here.transform(dx, 0, 0), here.transform(0, dy, 0), here.transform(dx, dy, 0)};
        for (Location c : cands) {
            try {
                Location og = NavGrid.bigGateOrigin(c.getX(), c.getY()); // batch 21 C: a tile of a multi-tile gate: the gate's own tile
                Scenery o = RegionManager.getObject(0, og != null ? og.getX() : c.getX(), og != null ? og.getY() : c.getY());
                if (o != null && o.getDefinition() != null && o.getDefinition().hasAction("Close") && (o.getType() < 4 || NavGrid.isGateName(o.getName()))) return true; // already open
                if (o != null && o.getDefinition() != null && o.getDefinition().hasAction("Open") && (o.getType() < 4 || NavGrid.isGateName(o.getName()))) {
                    new ScriptAPI(bot).interact(bot, o, "Open");
                    if (Boolean.getBoolean("nav.debug")) System.out.println("[Nav] " + bot.getUsername() + " opens " + o.getName() + " at " + c);
                    if (Watchdog.FAIL_ON && Watchdog.traced(bot)) Watchdog.flog(bot, "nav: opens " + o.getName() + " " + o.getId() + " at " + c.getX() + "," + c.getY()); // batch 19a design 3.2
                    return true;
                }
            } catch (Throwable ignored) { }
        }
        return false;
    }

    static int agility(Player p) { try { return p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.AGILITY); } catch (Throwable t) { return 1; } }

    /**
     * Shadow mode: say what the planner thinks of a walk that is starting (route or not, how long), and later what the old walker
     * actually did (morning review 10-23, C2): arrived, redirected, teleported, or not there after 10 minutes. Trips off the
     * surface grid (other floors, dungeons, the essence mine) are counted and skipped: the planner never handles them.
     */
    public static void shadow(Player bot, Location dest, String what) {
        if (!SHADOW || bot == null || dest == null || !NavPlanner.built) return;
        try {
            Location here = bot.getLocation();
            if (offGrid(here) || offGrid(dest)) { offGridSkipped++; return; }
            NavPlanner.Route r = NavPlanner.plan(here, dest, agility(bot));
            System.out.println("[NavShadow] " + bot.getUsername() + " " + what + " " + here + " -> " + dest + ": " + (r == null ? "NO ROUTE" : r.tiles + " tiles, " + r.doors + " doors"));
            if (r == null && bot.isArtificial()) SHADOW_FAILS.add(noRouteKey(bot, dest)); // batch 23 pre-review 2.2: bots only (a player's no-route walk must not feed the planner guard)
            Trip t = new Trip(); t.bot = bot; t.dest = dest; t.from = here; t.plan = r == null ? -1 : r.tiles; t.t0 = System.currentTimeMillis(); t.lx = here.getX(); t.ly = here.getY();
            Trip old = TRIPS.put(key(bot), t);
            if (old != null) outcome(old, "redirected", old.dest.getDistance(here));
        } catch (Throwable t) { System.out.println("[NavShadow] " + t); }
    }

    static boolean offGrid(Location l) { return l.getZ() != 0 || l.getY() > 6400 || !NavGrid.inBounds(l.getX(), l.getY()); }

    static final class Trip { Player bot; Location dest, from; int plan, lx, ly; long t0; }
    static final Map<String, Trip> TRIPS = new ConcurrentHashMap<>();
    static volatile int offGridSkipped;
    /** [planner had a route, planner had none] x [arrived, not arrived in 10 min, teleported, redirected], this window and since boot. */
    static final int[][] WIN = new int[2][4], ALL = new int[2][4];
    static final java.util.Set<String> WINDOW_FAILS = ConcurrentHashMap.newKeySet(), SHADOW_FAILS = ConcurrentHashMap.newKeySet();
    static int rescues0;
    /** planner-guard-fix F1: rescues of bots the planner was walking (a route in STATES), this window; the guard judges these, not every stuck bot in the world. */
    static volatile int plannerRescues;
    /** Called from Watchdog.rescue right after rescued++ (the bot's State is still there: forget() comes later). */
    static void rescued(Player bot) {
        try { State s = bot == null ? null : STATES.get(key(bot)); if (ON && s != null && s.route != null) plannerRescues++; } catch (Throwable ignored) { }
    }
    static double baseRescues = -1, baseFails = -1; // per 10 minutes with the planner off (data/survey/nav-baseline.txt)

    static void outcome(Trip t, String what, double left) {
        int a = t.plan >= 0 ? 0 : 1, b = "arrived".equals(what) ? 0 : "not arrived".equals(what) ? 1 : "teleported".equals(what) ? 2 : 3;
        WIN[a][b]++; ALL[a][b]++;
        System.out.println("[NavShadow] outcome " + t.bot.getUsername() + " " + t.from + " -> " + t.dest + ": " + what + " after " + (System.currentTimeMillis() - t.t0) / 1000 + " s" + (b == 0 ? "" : ", " + (int) left + " tiles left") + " (planner: " + (t.plan >= 0 ? t.plan + " tiles" : "NO ROUTE") + ")");
    }

    /** Every ~5 s: settle shadow trips. Every minute: re-read nav.on. Every 10 minutes: a summary, the baseline, and the auto-revert guard. */
    static void watch() {
        core.game.world.GameWorld.getPulser().submit(new core.game.system.task.Pulse(8) {
            int n;
            @Override public boolean pulse() {
                try {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, Trip> e : TRIPS.entrySet()) {
                        Trip t = e.getValue(); Location l = t.bot.getLocation();
                        if (!t.bot.isActive()) { TRIPS.remove(e.getKey(), t); continue; }
                        double left = l.getDistance(t.dest), jump = Math.hypot(l.getX() - t.lx, l.getY() - t.ly);
                        if (left <= 4 && l.getZ() == t.dest.getZ()) { TRIPS.remove(e.getKey(), t); outcome(t, "arrived", 0); }
                        else if (jump > 40) { TRIPS.remove(e.getKey(), t); outcome(t, "teleported", left); }
                        else if (now - t.t0 > 600_000) { TRIPS.remove(e.getKey(), t); outcome(t, "not arrived", left); }
                        else { t.lx = l.getX(); t.ly = l.getY(); }
                    }
                    n++;
                    if (n % 12 == 0) { boolean f = navWanted(); if (f != ON) { ON = f; STATES.clear(); if (f) arm(); System.out.println("[Nav] data/survey/nav.on " + (f ? "found: the planner now walks the bots" : "gone: back to the old walker")); } NavGrid.buildFileCheck(); } // batch 22: data/survey/navbuild.request
                    if (n % WINDOW_PULSES == 0) window();
                } catch (Throwable t) { System.out.println("[Nav] watch: " + t); Watchdog.fexc("NavWalker.watch", null, t); } // batch 19a B3
                return false;
            }
        });
    }
    /**
     * audit 10-23 N-12: after an auto-revert the planner stays off until a nav.on newer than the revert exists (the minute
     * re-read used to switch it straight back on with -Dnav=true, or when the rename failed). 0 = never reverted this boot.
     */
    static volatile long revertedAt;
    static boolean navWanted() {
        java.io.File f = survey("nav.on");
        boolean want = Boolean.getBoolean("nav") || f.exists();
        if (want && revertedAt > 0) {
            if (f.exists() && f.lastModified() > revertedAt) { revertedAt = 0; arm(); System.out.println("[Nav] a new data/survey/nav.on: the auto-revert is lifted"); }
            else return false;
        }
        return want;
    }
    /** audit 10-23 N-10: windows in a row over the limit; a revert needs two (one window over it happens by chance about every 2 h). */
    static int badWindows;

    /** planner-guard-fix F2: the guard (and the baseline learning) wait this many windows after boot, a switch-on or a lifted revert (-Dnav.warmWindows). */
    static final int WARM_WINDOWS = Integer.getInteger("nav.warmWindows", 3);
    /** planner-guard-fix F3: a window with fewer planner-routed trips than this cannot count as a bad one. */
    static final int MIN_TRIPS = 10;
    static int windowsSinceArm, prevBots = -1;
    static int botsOverride = -1; // trial/unit test only (TestB22NavGuard): a synthetic bot count
    static void arm() { windowsSinceArm = 0; badWindows = 0; }
    static int botCount() {
        if (botsOverride >= 0) return botsOverride;
        int n = 0; try { for (Player p : core.game.world.repository.Repository.getPlayers()) if (p != null && p.isActive() && p.isArtificial()) n++; } catch (Throwable ignored) { }
        return n;
    }

    /** Pulses of 8 ticks in a guard window: 120 = 10 minutes (-Dnav.window for trials). */
    static final int WINDOW_PULSES = Integer.getInteger("nav.window", 120);

    static void window() {
        int allRescues = Watchdog.rescued - rescues0; rescues0 = Watchdog.rescued;
        int rescues = ON ? plannerRescues : allRescues; plannerRescues = 0; // planner-guard-fix F1: on = the planner's own population; off = everything (the baseline)
        int trips = WIN[0][0] + WIN[0][1] + WIN[0][2] + WIN[0][3]; // F3: planner-routed trips this window
        int bots = botCount(); boolean steady = prevBots > 0 && Math.abs(bots - prevBots) * 10 <= prevBots; prevBots = bots; // F2: the CoinWatch.stable rule
        boolean warm = ++windowsSinceArm <= WARM_WINDOWS || !steady;
        int allFails = WINDOW_FAILS.size();
        int fails = SHADOW_FAILS.size(); // audit 10-23 N-11: the guard compares like with like, the trip-start plans the baseline learned from (all step() callers counted another population)
        WINDOW_FAILS.clear(); SHADOW_FAILS.clear();
        if (SHADOW || ON) System.out.println("[NavShadow] window" + (ON ? " (planner ON)" : "") + ": planner had a route -> arrived " + WIN[0][0] + ", not arrived " + WIN[0][1] + ", teleported " + WIN[0][2] + ", redirected " + WIN[0][3]
                + "; no route -> arrived " + WIN[1][0] + ", not arrived " + WIN[1][1] + ", teleported " + WIN[1][2] + ", redirected " + WIN[1][3] + "; off-grid skipped " + offGridSkipped + "; stuck rescues " + rescues + ", distinct no-route " + fails + (ON ? " (all walks " + allFails + ", all rescues " + allRescues + ")" : "") + (warm ? " (warm-up, guard off)" : "")
                + " (since boot: " + java.util.Arrays.deepToString(ALL) + ")");
        for (int[] r : WIN) java.util.Arrays.fill(r, 0);
        if (baseRescues < 0) loadBaseline();
        if (warm) { System.out.println("[Nav] guard warm-up (" + Math.min(windowsSinceArm, WARM_WINDOWS) + "/" + WARM_WINDOWS + ", bots " + bots + (steady ? "" : ", bot count moving") + ")"); return; }
        if (!ON) { // learn what normal looks like with the planner off
            baseRescues = baseRescues < 0 ? rescues : baseRescues * 0.8 + rescues * 0.2;
            baseFails = baseFails < 0 ? fails : baseFails * 0.8 + fails * 0.2;
            try { java.nio.file.Files.write(survey("nav-baseline.txt").toPath(), String.format(Locale.ROOT, "%.2f %.2f%n", baseRescues, baseFails).getBytes()); } catch (Throwable ignored) { }
            return;
        }
        double limR = 2 * Math.max(baseRescues, 2), limF = 2 * Math.max(baseFails, 5); // the guard: twice the planner-off baseline (with floors for a quiet world)
        if (rescues > limR || fails > limF) {
            if (trips < MIN_TRIPS) { System.out.println("[Nav] guard: over a limit but only " + trips + " planner-routed trips this window, not counted"); return; } // F3: badWindows unchanged
            String what = (ON ? "planner-walked stuck rescues " : "stuck rescues ") + rescues + " (limit " + (int) limR + "), distinct no-route " + fails + " (limit " + (int) limF + ")";
            if (++badWindows >= 2) { badWindows = 0; revert(what + " in two windows in a row"); } // audit 10-23 N-10
            else System.out.println("[Nav] guard: " + what + " in this window (1 of 2 before a revert)");
        } else badWindows = 0;
    }

    static void loadBaseline() {
        try { String[] a = new String(java.nio.file.Files.readAllBytes(survey("nav-baseline.txt").toPath())).trim().split(" "); baseRescues = Double.parseDouble(a[0]); baseFails = Double.parseDouble(a[1]); } catch (Throwable ignored) { }
    }

    /** The planner made things worse: back to the old walker at once, and nav.on is renamed so a restart keeps it off. */
    static void revert(String why) {
        ON = false; STATES.clear();
        revertedAt = System.currentTimeMillis(); // audit 10-23 N-12: off until a new nav.on (not just until the next minute)
        java.io.File f = survey("nav.on");
        String to = "nav.on.reverted-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        String how;
        if (!f.exists()) how = "no nav.on to rename (on by -Dnav)";
        else if (f.renameTo(survey(to))) how = "nav.on renamed to " + to;
        else if (f.delete()) how = "nav.on could not be renamed and was deleted";
        else how = "nav.on could not be renamed or deleted";
        System.out.println("[Nav] AUTO-REVERT: " + why + "; planner off until a new data/survey/nav.on is created, " + how);
    }

    static void forget(Player bot) { if (bot != null) { STATES.remove(key(bot)); try { AfkTasks.Runner.tollForget(bot); } catch (Throwable ignored) { } } } // audit B21-6: tollStuck's kept answer too

    public static String stats() { return "routed " + routed + ", outside the surface grid " + outside + ", no route " + noRoute + " (no stand tile at start " + noStandFrom + ", at goal " + noStandTo + ", no corridor " + noCorridor + "), doors opened " + doorsOpened + ", door not found " + doorsMissing + ", replans " + replans; }

    /** Why a plan came back empty: counted (first plans only, as before), and printed under -Dnav.debug. batch 18 item3: returned for the always-on line. */
    static String why(Player bot, Location here, Location dest, boolean count) {
        String r;
        if (NavPlanner.standNear(here) == null) { if (count) noStandFrom++; r = "no standable tile near the start"; }
        else if (NavPlanner.standNear(dest) == null) { if (count) noStandTo++; r = "no standable tile near the goal"; }
        else { if (count) noCorridor++; r = "no corridor between the two components"; }
        if (Boolean.getBoolean("nav.debug")) System.out.println("[Nav] no route for " + bot.getUsername() + " " + here + " -> " + dest + ": " + r);
        return r;
    }

    // ------------------------------------------------------------ batch 18 item3 (pathing plan phase 0): reasons always logged
    /** One line per bot + kind + destination cell per this many minutes (-Dnav.sayMin, default 10); the daily report and scenario records follow the same limit. */
    static final long SAY_MS = Long.getLong("nav.sayMin", 10) * 60_000L;
    static final Map<String, Long> SAID = new ConcurrentHashMap<>();
    static final Map<String, Integer> OUTSIDE_LAST = new ConcurrentHashMap<>(); // bot name -> the off-grid destination cell last noted (step() is the hot path: one map put per off-grid step)
    static final int SCEN_PER_HOUR = Integer.getInteger("nav.scenPerHour", 60); // no_route + redirect records, so they can't crowd rescues out of the 500 a day
    static int scenUsed; static long scenHourOf;
    static volatile int saidNoRoute, saidRedirect; // trials

    static String cell(Location l) { return (l.getX() >> 3) + "," + (l.getY() >> 3) + "," + l.getZ(); }

    static boolean fresh(String k) {
        long now = System.currentTimeMillis();
        Long t = SAID.get(k);
        if (t != null && t > now) return false;
        if (SAID.size() > 5_000) SAID.values().removeIf(v -> v < now);
        SAID.put(k, now + SAY_MS);
        return true;
    }

    static synchronized boolean scenBudget() {
        long h = System.currentTimeMillis() / 3_600_000L;
        if (h != scenHourOf) { scenHourOf = h; scenUsed = 0; }
        return ++scenUsed <= SCEN_PER_HOUR;
    }

    static void noRouteSeen(Player bot, Location here, Location dest, String when, boolean count) {
        try {
            String r = why(bot, here, dest, count);
            if (!Watchdog.ours(bot) || !fresh(key(bot) + "|n|" + cell(dest))) return; // adventurers, traders, the herb gatherer: survey and replay bots look for dead ends on purpose
            String type = Watchdog.destType(bot, dest);
            saidNoRoute++;
            String line = "[Nav] no route for " + bot.getUsername() + " (" + type + ", " + when + ") " + here + " -> " + dest + ": " + r;
            System.out.println(line); Watchdog.flog(bot, line); // batch 19a design 3.2
            Watchdog.Daily.note("noroute", type + " @ " + Watchdog.Daily.cellText(cell(dest)) + " (" + r + ")");
            if (scenBudget()) Watchdog.scenario(bot, "no_route", Watchdog.goal(bot).walker, here, dest, null, r + " (" + type + ", " + when + ")");
        } catch (Throwable ignored) { }
    }

    static void redirected(Player bot, State s, Location here, Location dest) {
        try {
            if (!Watchdog.ours(bot) || !fresh(key(bot) + "|r|" + cell(s.dest))) return;
            double moved = s.dest.getDistance(dest);
            String bucket = moved <= 10 ? "goal moved 3-10 tiles" : moved <= 50 ? "goal moved 10-50 tiles" : "a new goal 50+ tiles away";
            String type = Watchdog.destType(bot, s.dest);
            String why = bucket + " (" + type + ")";
            saidRedirect++;
            String line = "[Nav] redirect " + bot.getUsername() + " (" + type + "): " + bucket + "; old goal " + s.dest + " " + (int) here.getDistance(s.dest) + " tiles short at hop " + s.hop + "/" + s.route.hops.size() + ", new goal " + dest + " (" + (int) moved + " tiles from the old)";
            System.out.println(line); Watchdog.flog(bot, line); // batch 19a design 3.2
            Watchdog.Daily.note("redirect", why);
            if (scenBudget()) Watchdog.scenario(bot, "redirect", Watchdog.goal(bot).walker, s.from != null ? s.from : here, s.dest, here, why + ", new goal " + dest);
        } catch (Throwable ignored) { }
    }

    /** Off the surface grid: noted once per bot per destination cell in a row (the daily report's outside-grid section). */
    static void outsideSeen(Player bot, Location here, Location dest) {
        try {
            int c = (dest.getX() >> 3) << 14 | (dest.getY() >> 3) << 2 | (dest.getZ() & 3);
            Integer old = OUTSIDE_LAST.put(bot.getUsername(), c);
            if (old != null && old == c || !Watchdog.ours(bot)) return;
            String what = dest.getZ() != 0 ? "goal on floor " + dest.getZ() : dest.getY() > 6400 ? "goal underground" : !NavGrid.inBounds(dest.getX(), dest.getY()) ? "goal off the grid" : here.getZ() != 0 ? "bot on floor " + here.getZ() : here.getY() > 6400 ? "bot underground" : "bot off the grid";
            Watchdog.Daily.note("outside", Watchdog.Daily.cellText(cell(dest)) + " (" + what + ")");
        } catch (Throwable ignored) { }
    }

    /** Walk the bot to the step (the server's own pathfinder handles the few tiles). */
    static void walkTo(Player bot, Location step) {
        try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
    }
}
