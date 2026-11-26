package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Batch 27: hub census and bands (the design notes sections 3 and 6).
 *
 * Every hubs.ticks game ticks (default 1000 = 10 min) one pass over the online players counts the bots within each hub's
 * radius (same plane) and prints ONE "[Hubs] t=... day Lumbridge=9/8-12 GE=11/8-12 ... under=X over=Y boost=... avoid=..." line.
 * Hubs and bands are data: data/eco/hubs.json (first boot copies the shipped default content/custom/hubs.json there);
 * a missing or bad file leaves the feature inert with one "[Hubs] off: ..." line.
 *
 * Band rule (bots only; chooseJob and loiter are SmartAdventurers.Brain, which only bots have): a hub under its band for
 * underWindows (2) censuses in a row raises the score of jobs whose spot is within jobRange (60) tiles of it by boost (x1.3) and
 * takes the next breaks of bots within breakRange (160) tiles; a hub over its band in the latest census lowers those jobs by
 * damp (x0.8) and breaks choose another hangout. With no hub flagged, on() is false and both callers run the old code untouched.
 *
 * Batch 27 fix (the design notes section 4; each part's own switch, false = as above): -Dhubs.bank bank choice skips a
 * bank inside an over hub for one within +60 tiles (Watchdog.rescue, Runner.nearestBank); -Dhubs.census=work (or all) skips bots
 * within 8 tiles of a bank unless on a break; -Dhubs.breaks breaks every 1-2 trips while a hub is under, per-hub breakRange, walk
 * time added to the break; -Dhubs.scaled factor grows with the distance from the band (0.3-2.5); a warning per banded hub no job serves.
 *
 * Train-4 thermostat (the design notes, "[Fable] Why the boost never ran in the rerun"; off by default, -Dhubs.thermostat=true):
 * the rule above arms the boost only on censuses strictly below the band floor, underWindows in a row, and one reading at the floor
 * resets the run, so a hub that rests ON its floor (Lumbridge at scale 0.5 in the day band 4-6) never arms it, while one reading
 * over the ceiling arms the damp at once. With the flag: a count at or under the floor advances the under run and a count at or
 * over the ceiling the over run (thermostatWindows censuses, default 1, hubs.json); an armed flag holds (latched) until the count
 * reaches the band's middle; the first census after boot (the spawn, every bot at Lumbridge) counts and logs (" spawn") but arms
 * nothing. under= and over= on the line are then the armed counts.
 *
 * Off: -Dhubs=false (no census, no rule) or the file data/hubs.off (checked at boot and before every census: creating it
 * stops the census and clears the rule without a restart). -Dhubs.scale=0.5 scales every band (soaks with fewer bots).
 */
public final class Hubs implements StartupListener {
    static final boolean FLAG = !"false".equalsIgnoreCase(System.getProperty("hubs", "true"));
    static final int TICKS = Math.max(10, Integer.getInteger("hubs.ticks", 1000)); // 1000 ticks = 10 min at 600 ms
    static final double SCALE = scale();
    static final String OFF_FILE = "hubs.off", RESOURCE = "hubs.json";

    static final class Hub {
        final String name; final Location centre; final int radius; final boolean banded; final int[] band; // dayMin, dayMax, eveMin, eveMax
        Location anchor; String anchorName; // where a break at this hub goes: the nearest hangout (or the Exchange, or the town spot)
        int underRun; // censuses in a row under the band (census pulse only)
        int overRun; // train-4 thermostat: censuses in a row at or over the band's ceiling (census pulse only)
        double breakRange = -1; // batch 27 fix 3: this hub's own break range from hubs.json ("breakRange"), -1 = the global one
        volatile int count = -1, lo, hi; // batch 27 fix 4: the latest census's count and band for the scaled factor (-1 = unknown: the plain factor)
        Hub(String name, int x, int y, int radius, boolean banded, int[] band) { this.name = name; this.centre = Location.create(x, y, 0); this.radius = radius; this.banded = banded; this.band = band; }
        boolean near(Location l, double range) { return l != null && l.getZ() == centre.getZ() && centre.getDistance(l) <= range; }
        double breakRange() { return BREAKS && breakRange > 0 ? breakRange : BREAK_RANGE; } // batch 27 fix 3: -Dhubs.breaks=false: the global range for every hub
        int min(boolean eve) { return band[eve ? 2 : 0]; }
        int max(boolean eve) { return band[eve ? 3 : 1]; }
    }

    static volatile Hub[] HUBS = new Hub[0];
    static volatile double BOOST = 1.3, DAMP = 0.8, JOB_RANGE = 60, BREAK_RANGE = 160;
    static volatile int UNDER_WINDOWS = 2;
    static volatile int THERMO_WINDOWS = 1; // train-4 thermostat: censuses at or under the floor that arm the boost (hubs.json thermostatWindows)
    /** The latest census's flagged hubs: under the band for UNDER_WINDOWS windows / over it now. Replaced whole, never changed. */
    static volatile Hub[] UNDER = new Hub[0], OVER = new Hub[0];
    static volatile boolean stopped;
    /** The latest census for economy.json (built new each census, never changed after), null before the first or when off. */
    static volatile JSONObject LAST;
    static volatile String lastLine; // trials
    static volatile int censuses;    // trials
    static volatile boolean testPause; // trials: the pulse skips its census while a test holds hubs marked
    // batch 27 fix (the design notes section 4): each switch false = that part's pre-fix rule (volatile so the trials can flip them)
    // Lumbridge-only rescope (the design notes section 4): fixes 1 and 3 default OFF (opt in with -Dhubs.bank=true / -Dhubs.breaks=true)
    static volatile boolean BANK = opt("hubs.bank");     // fix 1: a bank inside an over hub gives way to another reachable one within +BANK_SLACK tiles
    static volatile boolean CENSUS_WORK = !"all".equalsIgnoreCase(System.getProperty("hubs.census", "work")); // fix 2: "all" = count bank users too (the old count)
    static volatile boolean BREAKS = opt("hubs.breaks"); // fix 3: while a hub is under: breaks every 1-2 trips, per-hub breakRange, walk time added to the break
    static volatile boolean SCALED = sw("hubs.scaled");  // fix 4: the factor grows with the distance from the band (false = flat x BOOST / x DAMP)
    static volatile boolean THERMOSTAT = opt("hubs.thermostat"); // train-4: arm at the band's edge, hold to its middle, spawn warm-up (default off: the rule above, unchanged)
    static final double BANK_SLACK = 60, BANKER_RANGE = 8, WALK_TPS = 1.5, FACTOR_MIN = 0.3, FACTOR_MAX = 2.5;
    static final long WALK_CAP_MS = 240_000;
    static volatile int bankSwaps; // trials and the soak: bankFor gave another bank
    static volatile int jobsCheckedAt = -1; // fix 5: the JOBS size the no-job warning last looked at (hunts join JOBS after boot)
    static boolean sw(String k) { return !"false".equalsIgnoreCase(System.getProperty(k, "true")); }
    static boolean opt(String k) { return "true".equalsIgnoreCase(System.getProperty(k, "false")); } // default off

    static void log(String s) { System.out.println("[Hubs] " + s); }

    static double scale() {
        try { double s = Double.parseDouble(System.getProperty("hubs.scale", "1")); return s > 0 && s <= 10 ? s : 1; } catch (Throwable t) { return 1; }
    }

    static File dataFile() { return new File(ServerConstants.DATA_PATH, "eco/hubs.json"); }
    static File offFile() { return new File(ServerConstants.DATA_PATH, OFF_FILE); }

    @Override
    public void startup() {
        if (!FLAG) { log("off (-Dhubs=false)"); return; }
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        if (offFile().exists()) { stopped = true; log("off: " + OFF_FILE + " present in the data folder"); return; }
        String why = load();
        if (why != null) { stopped = true; log("off: " + why); return; }
        GameWorld.getPulser().submit(new Pulse(TICKS) {
            @Override public boolean pulse() {
                if (Hubs.stopped) return true; // qualified: Pulse has its own stop()
                long t0 = System.nanoTime();
                try {
                    if (Hubs.offFile().exists()) { Hubs.stop(OFF_FILE + " present"); return true; }
                    if (!Hubs.testPause) Hubs.census(System.currentTimeMillis());
                } catch (Throwable t) { Hubs.log("" + t); Watchdog.fexc("Hubs.census", null, t); }
                Watchdog.fpulse("Hubs.census", null, System.nanoTime() - t0);
                return false;
            }
        });
        log("on: " + HUBS.length + " hubs, census every " + TICKS + " ticks, jobs within " + (int) JOB_RANGE + " tiles x" + BOOST + " under (" + UNDER_WINDOWS + " windows) / x" + DAMP + " over, breaks within " + (int) BREAK_RANGE + (SCALE != 1 ? ", bands x" + SCALE : "")
                + ", bank " + BANK + ", count=" + (CENSUS_WORK ? "work" : "all") + ", breaks " + BREAKS + ", scaled " + SCALED); // batch 27 fix
    }

    static void stop(String why) {
        stopped = true; UNDER = new Hub[0]; OVER = new Hub[0]; LAST = null;
        log("off: " + why + " (jobs and breaks back to the old rule)");
    }

    /** True while the band rule has something to do; false = chooseJob and loiter run exactly the old code. */
    static boolean on() { return FLAG && !stopped && (UNDER.length > 0 || OVER.length > 0); }

    // ---- loading ----

    /** Reads data/eco/hubs.json (on first boot copies the shipped default there first). Null when loaded, else why it stays off. */
    static String load() {
        String why = read();
        if (why == null) warnNoJobs(); // batch 27 fix 5
        return why;
    }

    /** Batch 27 fix 5: true when a job's spot (a task spot of a SmartAdventurers job, or a hunt's spot) is within JOB_RANGE of the hub. */
    static boolean hasJob(Hub h) {
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) {
            if (j.hunt != null) { if (h.near(j.hunt.spot, JOB_RANGE)) return true; continue; }
            AfkTasks.Spot[] sp = AfkTasks.SPOTS.get(j.task);
            if (sp != null) for (AfkTasks.Spot s : sp) if (h.near(s.at, JOB_RANGE)) return true;
        }
        return false;
    }
    /** Batch 27 fix 5: one warning per banded hub no job can serve (jobs cannot boost it; only breaks can). Again at a census once the hunts join JOBS. */
    static void warnNoJobs() {
        List<SmartAdventurers.Job> jobs = SmartAdventurers.JOBS;
        jobsCheckedAt = jobs.size();
        for (Hub h : HUBS) if (h.banded && !hasJob(h)) log("warning " + h.name + " has no job spot within " + (int) JOB_RANGE + " (" + jobs.size() + " jobs known)");
    }

    static String read() {
        File f = dataFile();
        String text;
        try {
            if (!f.exists()) {
                InputStream in = Hubs.class.getResourceAsStream(RESOURCE);
                if (in == null) return "no " + f.getPath() + " and no shipped default";
                try (InputStream i = in) { text = new String(i.readAllBytes(), StandardCharsets.UTF_8); }
                String why = parse(text); // never write a default that would not load
                if (why != null) return "shipped default: " + why;
                f.getParentFile().mkdirs();
                Ledger.writeAtomic(f, text);
                log("wrote the default bands to " + f.getPath());
                return null;
            }
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) { return "could not read " + f.getPath() + ": " + t; }
        String why = parse(text);
        return why == null ? null : f.getPath() + ": " + why;
    }

    /** A number field: def when absent, NaN when present but not a number. */
    static double num(JSONObject o, String k, double def) {
        if (!o.containsKey(k)) return def;
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }
    /** A whole-number field: def when absent, -1 when present but not a whole number of 0 or more. */
    static int whole(JSONObject o, String k, int def) {
        double d = num(o, k, def);
        return Double.isNaN(d) || d < 0 || d != Math.rint(d) || d > 1_000_000 ? -1 : (int) d;
    }

    /** Parses the hubs file; sets the hubs and numbers only when all of it is good. Null when good, else what is wrong. */
    static String parse(String text) {
        Object root;
        try { root = new JSONParser().parse(text); } catch (Throwable t) { return "bad JSON (" + t + ")"; }
        if (!(root instanceof JSONObject)) return "not a JSON object";
        JSONObject r = (JSONObject) root;
        double boost = num(r, "boost", 1.3), damp = num(r, "damp", 0.8), jobRange = num(r, "jobRange", 60), breakRange = num(r, "breakRange", 160);
        int windows = whole(r, "underWindows", 2);
        int tw = whole(r, "thermostatWindows", 1); // train-4 thermostat
        if (!(boost >= 1 && boost <= 3)) return "boost must be 1-3";
        if (!(damp > 0 && damp <= 1)) return "damp must be over 0 and at most 1";
        if (!(jobRange >= 1 && jobRange <= 400)) return "jobRange must be 1-400";
        if (!(breakRange >= 1 && breakRange <= 400)) return "breakRange must be 1-400";
        if (windows < 1 || windows > 12) return "underWindows must be 1-12";
        if (tw < 1 || tw > 12) return "thermostatWindows must be 1-12";
        Object hs = r.get("hubs");
        if (!(hs instanceof JSONArray) || ((JSONArray) hs).isEmpty()) return "no hubs list";
        List<Hub> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        String[] keys = {"dayMin", "dayMax", "eveMin", "eveMax"};
        for (Object o : (JSONArray) hs) {
            if (!(o instanceof JSONObject)) return "a hub is not an object";
            JSONObject h = (JSONObject) o;
            Object n = h.get("name");
            if (!(n instanceof String) || ((String) n).trim().isEmpty()) return "a hub has no name";
            String name = ((String) n).trim().replace(' ', '_');
            if (!names.add(name.toLowerCase(Locale.ROOT))) return "hub " + name + " is listed twice";
            int x = whole(h, "x", -1), y = whole(h, "y", -1), rad = whole(h, "radius", 20);
            if (x <= 0 || y <= 0 || x > 16_000 || y > 16_000) return "hub " + name + ": bad x/y";
            if (rad < 1 || rad > 100) return "hub " + name + ": radius must be 1-100";
            int[] b = new int[4]; int present = 0;
            for (int i = 0; i < 4; i++) {
                if (!h.containsKey(keys[i])) continue;
                present++;
                b[i] = whole(h, keys[i], -1);
                if (b[i] < 0) return "hub " + name + ": bad " + keys[i];
            }
            if (present != 0 && present != 4) return "hub " + name + ": give all four band numbers or none";
            if (present == 4 && (b[0] > b[1] || b[2] > b[3])) return "hub " + name + ": a band minimum is over its maximum";
            if (present == 4 && SCALE != 1) for (int i = 0; i < 4; i++) b[i] = (int) Math.round(b[i] * SCALE);
            double hbr = num(h, "breakRange", -1); // batch 27 fix 3: optional, the global breakRange when absent
            if (h.containsKey("breakRange") && !(hbr >= 1 && hbr <= 400)) return "hub " + name + ": breakRange must be 1-400";
            Hub hub = new Hub(name, x, y, rad, present == 4, b);
            hub.breakRange = hbr;
            out.add(hub);
        }
        anchors(out);
        BOOST = boost; DAMP = damp; JOB_RANGE = jobRange; BREAK_RANGE = breakRange; UNDER_WINDOWS = windows; THERMO_WINDOWS = tw;
        UNDER = new Hub[0]; OVER = new Hub[0];
        HUBS = out.toArray(new Hub[0]);
        return null;
    }

    /** Each hub's break spot: the nearest hangout or the Exchange inside its radius, else the nearest town spot, else its centre. */
    static void anchors(List<Hub> hubs) {
        for (Hub h : hubs) {
            Location best = null; String bn = null; double bd = h.radius + 0.001;
            for (Object[] g : SmartAdventurers.Brain.HANGOUTS) {
                Location l = Location.create((Integer) g[1], (Integer) g[2], 0);
                double d = h.centre.getDistance(l);
                if (d < bd) { bd = d; best = l; bn = (String) g[0]; }
            }
            double gd = h.centre.getDistance(SmartAdventurers.GRAND_EXCHANGE);
            if (gd < bd) { bd = gd; best = SmartAdventurers.GRAND_EXCHANGE; bn = "the Grand Exchange"; } // the same object, so loiter's Exchange rules apply
            if (best == null) for (BotLife.Town t : BotLife.Town.values()) { double d = h.centre.getDistance(t.spot); if (d < bd) { bd = d; best = t.spot; bn = t.name; } }
            if (best == null) { best = h.centre; bn = h.name; }
            h.anchor = best; h.anchorName = bn;
        }
    }

    // ---- census ----

    static boolean evening(long now) { int h = (int) ((now / 3_600_000L) % 24); return h >= 23 || h < 4; } // 23:00-04:00 UTC

    /** One pass over the online players: bots inside each hub's radius (same plane), then the band state and the log line. */
    static int[] census(long now) {
        Hub[] hubs = HUBS;
        int[] n = new int[hubs.length];
        if (hubs.length == 0) return n;
        if (jobsCheckedAt != SmartAdventurers.JOBS.size()) warnNoJobs(); // batch 27 fix 5: the hunts joined JOBS after boot
        boolean work = CENSUS_WORK;
        for (Player p : Repository.getPlayers()) {
            if (p == null || !p.isActive() || !p.isArtificial()) continue;
            Location l = p.getLocation();
            if (l == null) continue;
            for (int i = 0; i < hubs.length; i++) if (hubs[i].near(l, hubs[i].radius)) { if (!work || counted(l, SmartAdventurers.BRAINS.get(Watchdog.key(p)), now)) n[i]++; break; }
        }
        apply(hubs, n, now);
        return n;
    }

    /** Batch 27 fix 2 (-Dhubs.census=work): a bot within BANKER_RANGE tiles of a bank is banking, not working there; not counted unless it is on a break (its Brain's idleUntil). */
    static boolean counted(Location l, SmartAdventurers.Brain b, long now) {
        if (b != null && b.idleUntil > now) return true;
        for (Location bk : AfkTasks.BANKS) if (bk.getZ() == l.getZ() && bk.getDistance(l) <= BANKER_RANGE) return false;
        return true;
    }

    /** Counts -> each hub's run under its band, the flagged hubs, the "[Hubs]" line and the economy.json field. */
    @SuppressWarnings("unchecked")
    static void apply(Hub[] hubs, int[] n, long now) {
        boolean eve = evening(now), spawn = THERMOSTAT && censuses == 0; // train-4 thermostat: the first census after boot counts and logs, arms nothing
        List<Hub> u = new ArrayList<>(), o = new ArrayList<>();
        int under = 0, over = 0;
        String at = java.time.Instant.ofEpochMilli(now).truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString();
        StringBuilder sb = new StringBuilder("t=").append(at).append(eve ? " evening" : " day").append(spawn ? " spawn" : "");
        JSONObject counts = new JSONObject(), bands = new JSONObject();
        for (int i = 0; i < hubs.length; i++) {
            Hub h = hubs[i]; int c = n[i];
            sb.append(' ').append(h.name).append('=').append(c);
            counts.put(h.name, c);
            h.count = c;
            if (!h.banded) { h.underRun = 0; h.overRun = 0; continue; }
            int lo = h.min(eve), hi = h.max(eve);
            h.lo = lo; h.hi = hi; // batch 27 fix 4: for the scaled factor
            sb.append('/').append(lo).append('-').append(hi);
            JSONArray band = new JSONArray(); band.add(lo); band.add(hi); bands.put(h.name, band);
            if (THERMOSTAT) { // train-4 thermostat: arm at the band's edge, hold until the count reaches its middle; under=/over= count the armed hubs
                int mid = (lo + hi) / 2;
                if (!spawn) {
                    if (c <= lo) h.underRun++; else if (c >= mid) h.underRun = 0;
                    if (c >= hi) h.overRun++; else if (c <= mid) h.overRun = 0;
                }
                if (!spawn && h.underRun >= THERMO_WINDOWS) { under++; u.add(h); }
                if (!spawn && h.overRun >= 1) { over++; o.add(h); }
            } else {
                if (c < lo) { under++; h.underRun++; } else h.underRun = 0;
                if (h.underRun >= UNDER_WINDOWS) u.add(h);
                if (c > hi) { over++; o.add(h); }
            }
        }
        sb.append(" under=").append(under).append(" over=").append(over).append(" boost=").append(names(u)).append(" avoid=").append(names(o)).append(" count=").append(CENSUS_WORK ? "work" : "all"); // batch 27 fix 2
        UNDER = u.toArray(new Hub[0]); OVER = o.toArray(new Hub[0]);
        JSONObject j = new JSONObject();
        j.put("at", at); j.put("window", eve ? "evening" : "day"); j.put("counts", counts); j.put("bands", bands);
        JSONArray bu = new JSONArray(); for (Hub h : u) bu.add(h.name); j.put("boosted", bu);
        JSONArray bo = new JSONArray(); for (Hub h : o) bo.add(h.name); j.put("avoided", bo);
        j.put("boost", BOOST); j.put("damp", DAMP);
        LAST = j;
        lastLine = sb.toString();
        censuses++;
        log(lastLine);
    }

    static String names(List<Hub> hs) {
        if (hs.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (Hub h : hs) { if (sb.length() > 0) sb.append(','); sb.append(h.name); }
        return sb.toString();
    }

    // ---- the band rule (callers check on() first) ----

    /** chooseJob: x BOOST when the job's spot (a hunt's spot, or the task spot nearest the bot) is near an under hub, x DAMP near an over one. */
    static double jobFactor(SmartAdventurers.Job j, Location from) {
        Hub[] u = UNDER, o = OVER;
        if (j == null || (u.length == 0 && o.length == 0)) return 1.0;
        Location at = null;
        if (j.hunt != null) at = j.hunt.spot;
        else {
            AfkTasks.Spot[] sp = AfkTasks.SPOTS.get(j.task);
            if (sp != null && from != null) { double bd = Double.MAX_VALUE; for (AfkTasks.Spot s : sp) { double d = s.at.getDistance(from); if (d < bd) { bd = d; at = s.at; } } }
        }
        return factorAt(at, u, o);
    }

    static double factorAt(Location at, Hub[] u, Hub[] o) {
        if (at == null) return 1.0;
        double f = 1.0, r = JOB_RANGE;
        for (Hub h : u) if (h.near(at, r)) { f *= scaled(h, true); break; }
        for (Hub h : o) if (h.near(at, r)) { f *= scaled(h, false); break; }
        return SCALED ? Math.max(FACTOR_MIN, Math.min(FACTOR_MAX, f)) : f;
    }

    /** Batch 27 fix 4: BOOST^(1+(lo-c)/lo) under, DAMP^(1+(c-hi)/hi) over (c = the hub's latest census count), capped at FACTOR_MAX / FACTOR_MIN; the flat factor when -Dhubs.scaled=false or the count is unknown. */
    static double scaled(Hub h, boolean under) {
        double base = under ? BOOST : DAMP;
        int c = h.count;
        if (!SCALED || c < 0 || !h.banded) return base;
        double x = under ? 1 + (h.lo - c) / (double) Math.max(1, h.lo) : 1 + (c - h.hi) / (double) Math.max(1, h.hi);
        double f = Math.pow(base, Math.max(1, x)); // a count inside the band (a trial mark): the flat factor
        return under ? Math.min(FACTOR_MAX, f) : Math.max(FACTOR_MIN, f);
    }

    /**
     * Batch 27 fix 1 (-Dhubs.bank): the bank to use instead of {@code bank} for a bot at {@code from}. When bank is inside an over hub,
     * the nearest other BANKS entry that is not, that ok (the caller's own reach rules: toll, gates, sea) allows and that is at most
     * BANK_SLACK tiles further than bank; else bank. Never touches coins: the caller's free or paid teleport stays as it was.
     */
    static Location bankFor(Location from, Location bank, java.util.function.Predicate<Location> ok) {
        Hub[] o = OVER;
        if (!BANK || !FLAG || stopped || o.length == 0 || bank == null || from == null || !inAny(bank, o)) return bank;
        double lim = bank.getDistance(from) + BANK_SLACK, bd = Double.MAX_VALUE;
        Location best = null;
        for (Location b : AfkTasks.BANKS) {
            if (b.getX() == bank.getX() && b.getY() == bank.getY() && b.getZ() == bank.getZ()) continue;
            double d = b.getDistance(from);
            if (d > lim || d >= bd || inAny(b, o) || !ok.test(b)) continue;
            bd = d; best = b;
        }
        if (best == null) return bank;
        bankSwaps++;
        return best;
    }

    // ---- batch 27 fix 3: breaks while a hub is under (loiter checks on() first) ----

    /** The under hub this break goes to (breakSpot's choice): the nearest whose anchor is within its own break range of from; null for none. */
    static Hub underBreakHub(Location from) {
        if (from == null) return null;
        Hub best = null; double bd = Double.MAX_VALUE;
        for (Hub h : UNDER) { if (h.anchor == null || h.anchor.getZ() != from.getZ()) continue; double d = h.anchor.getDistance(from); if (d <= h.breakRange() && d <= bd) { bd = d; best = h; } }
        return best;
    }
    /** True while a hub is under and -Dhubs.breaks is on: loiter fires every 1-2 trips instead of 2-4. */
    static boolean pushBreaks() { return BREAKS && on() && UNDER.length > 0; }
    /** The walk to a break spot, in ms at WALK_TPS tiles a second, capped at WALK_CAP_MS; 0 when -Dhubs.breaks=false. */
    static long walkMs(Location from, Location to) {
        if (!BREAKS || from == null || to == null) return 0;
        return (long) Math.min(WALK_CAP_MS, from.getDistance(to) / WALK_TPS * 1000);
    }

    static boolean inAny(Location l, Hub[] hs) { for (Hub h : hs) if (h.near(l, h.radius)) return true; return false; }

    /**
     * loiter: {Location, name} for this break, or null to keep the old choice. The nearest under hub within BREAK_RANGE takes the
     * break; else, when the old choice is inside an over hub, the nearest hangout or the Exchange within BREAK_RANGE that is not.
     */
    static Object[] breakSpot(Location from, Location chosen) {
        Hub[] u = UNDER, o = OVER;
        if (from == null) return null;
        Hub best = underBreakHub(from); // batch 27 fix 3: each hub's own breakRange (the GE's 400); -Dhubs.breaks=false: the global one, as before
        if (best != null) return new Object[]{best.anchor, best.anchorName};
        if (chosen == null || !inAny(chosen, o)) return null;
        Object[] alt = null; double ad = BREAK_RANGE;
        for (Object[] g : SmartAdventurers.Brain.HANGOUTS) {
            Location l = Location.create((Integer) g[1], (Integer) g[2], 0);
            double d = l.getDistance(from);
            if (d <= ad && !inAny(l, o)) { ad = d; alt = new Object[]{l, g[0]}; }
        }
        Location ge = SmartAdventurers.GRAND_EXCHANGE;
        if (ge.getDistance(from) <= ad && !inAny(ge, o)) alt = new Object[]{ge, "the Grand Exchange"};
        return alt;
    }

    // ---- trials ----

    static Hub hub(String name) { for (Hub h : HUBS) if (h.name.equalsIgnoreCase(name)) return h; return null; }

    /** Trials only: flag one hub under and/or one over (by name; null for none), as a census would. Ignored when off. */
    static void testMark(String underName, String overName) { testMark(underName, overName, -1, -1); }

    /** Trials only (batch 27 fix 4): as testMark, with each marked hub's census count (-1 = unknown: the flat factor); the band is today's. */
    static void testMark(String underName, String overName, int underCount, int overCount) {
        if (!FLAG || stopped) return;
        Hub u = underName == null ? null : hub(underName), o = overName == null ? null : hub(overName);
        boolean eve = evening(System.currentTimeMillis());
        for (Hub h : HUBS) h.count = -1;
        if (u != null) { u.count = underCount; u.lo = u.min(eve); u.hi = u.max(eve); }
        if (o != null) { o.count = overCount; o.lo = o.min(eve); o.hi = o.max(eve); }
        UNDER = u == null ? new Hub[0] : new Hub[]{u};
        OVER = o == null ? new Hub[0] : new Hub[]{o};
    }
}
