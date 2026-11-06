package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * batch 19a, trial only (-Dtest.b19a2=true): Failures without the game. A3 classify (20 synthetic Recs, one per §4 rule plus 6 edge
 * cases), A4 line trimming (a record far over 4,096 bytes) and caps (40 economy records against its 30/h sub-cap, then 300 path
 * records in one tick against the 120/h cap: counters and the bug count still move by 300), A5 bugs (the Al Kharid toll tiles group
 * into one -alkharid-toll bug; an owner edit by hand survives a record; 3 passes -> not-reproduced; a record reopens it; a bug the file
 * lost comes back). Synthetic bot names start "b19a2-" (no real player). Prints [TestB19A2] RESULT ... and DONE.
 */
public final class TestB19A2 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19A2] " + s); }
    static final String RUN = Long.toString(System.currentTimeMillis() % 100000, 36);

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19a2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; boolean go;
            @Override public boolean pulse() {
                t++;
                if (go) return true;
                if (t >= 30 && Failures.started && Watchdog.Daily.day != null) { go = true; new Thread(TestB19A2::run, "TestB19A2").start(); return true; }
                if (t > 600) { log("RESULT Failures never started (started " + Failures.started + ", Daily day " + Watchdog.Daily.day + ")"); log("DONE"); return true; }
                return false;
            }
        });
    }

    static Failures.Rec base(String name, int x, int y) {
        Failures.Rec r = new Failures.Rec();
        r.domain = "path"; r.src = "rescue"; r.sid = "rescue|afk|" + (x >> 3) + "," + (y >> 3) + ",0|" + ((x + 20) >> 3) + "," + (y >> 3) + ",0"; r.bot = "b19a2-" + RUN + "-" + name; r.t = System.currentTimeMillis();
        r.walker = "afk"; r.dtype = "job spot"; r.drv = "none"; r.planAns = "none"; r.why = "synthetic " + name;
        r.from = new int[]{x - 30, y, 0}; r.to = new int[]{x + 20, y, 0}; r.stall = new int[]{x, y, 0};
        r.tiles = new int[]{pk(x - 2, y, 0), pk(x - 1, y, 0), pk(x, y, 0)}; r.d24 = 1; r.d29 = 1; r.m29 = 1; r.still = 30;
        LinkedHashMap<String, Object> snap = new LinkedHashMap<>(); snap.put("role", "adventurer"); snap.put("cb", 41); snap.put("coins", 830); r.snap = snap;
        return r;
    }
    static int pk(int x, int y, int z) { return x << 17 | y << 2 | z; }
    static Failures.Obj obj(int id, String n, int x, int y, String st, int ty, String rule) { Failures.Obj o = new Failures.Obj(); o.id = id; o.n = n; o.at = new int[]{x, y, 0}; o.st = st; o.ty = ty; o.rule = rule; return o; }
    static Failures.Npc npc(int id, String n, int x, int y, boolean agg) { Failures.Npc o = new Failures.Npc(); o.id = id; o.n = n; o.at = new int[]{x, y, 0}; o.agg = agg; o.lvl = 2; return o; }

    static List<String> lines() {
        try { File f = new File(Failures.failDir(), Watchdog.Daily.utc(System.currentTimeMillis()) + ".jsonl"); return f.exists() ? Files.readAllLines(f.toPath()) : new ArrayList<>(); } catch (Throwable e) { return new ArrayList<>(); }
    }
    static JSONObject bugsFile() throws Exception { return (JSONObject) new JSONParser().parse(new String(Files.readAllBytes(Failures.Bugs.file().toPath()), "UTF-8")); }
    static JSONObject bug(String id) throws Exception { Object b = ((JSONObject) bugsFile().get("bugs")).get(id); return (JSONObject) b; }
    static int fileCount(String id) throws Exception { JSONObject b = bug(id); return b == null ? -1 : ((Number) b.get("count")).intValue(); }

    static void run() {
        try {
            classify();
            size();
            caps();
            bugs();
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        log("DONE");
    }

    // ---------------------------------------------------------------- A3
    static void classify() {
        List<Object[]> cases = new ArrayList<>();
        Failures.Rec r;
        r = base("death", 3200, 3200); r.died = true; cases.add(new Object[]{"1 death", r, "death"});
        r = base("combat", 3200, 3200); r.h60 = 4; r.npcs.add(npc(174, "Dark wizard", 3201, 3201, true)); cases.add(new Object[]{"2 combat-interrupt", r, "combat-interrupt"});
        r = base("timeout", 3200, 3200); r.src = "replay_timeout"; r.d24 = 12; r.d29 = 14; r.m29 = 22; r.still = 0; cases.add(new Object[]{"3 timeout", r, "timeout"});
        r = base("outside", 3200, 3200); r.planAns = "outside"; r.planWhy = "goal off the grid"; r.to = new int[]{2924, 4848, 0}; cases.add(new Object[]{"4 outside-grid", r, "outside-grid"});
        r = base("gate", 3268, 3228); r.objs.add(obj(2882, "Gate", 3268, 3227, "closed", 0, "10 gp toll")); r.gateId = "alkharid-toll"; r.gateNeed = "10 gp toll, coins 3"; r.gateUnmet = true; cases.add(new Object[]{"5 rule-gate", r, "rule-gate"});
        r = base("door", 3210, 3214); r.objs.add(obj(1530, "Door", 3211, 3214, "closed", 0, null)); cases.add(new Object[]{"6 door-closed", r, "door-closed"});
        r = base("npc", 3145, 3233); r.npcRun = 4; r.npcs.add(npc(59, "Giant spider", 3146, 3233, true)); cases.add(new Object[]{"7 npc-blocking", r, "npc-blocking"});
        r = base("noroute", 3014, 3224); r.planAns = "no-route"; r.planWhy = "no corridor between the two components"; cases.add(new Object[]{"8 no-route", r, "no-route"});
        r = base("handoff", 3205, 3209); r.to = new int[]{3205, 3209, 1}; r.drv = "climber"; cases.add(new Object[]{"9 handoff", r, "handoff"});
        r = base("wrongside", 3091, 3251); r.drv = "planner"; r.planAns = "route"; r.next = new int[]{3091, 3253, 0}; r.near3 = true; r.objs.add(obj(1480, "Fence", 3091, 3252, "solid", 0, null)); cases.add(new Object[]{"10 wrong-side-hop", r, "wrong-side-hop"});
        r = base("osc", 3245, 3240); r.drv = "planner"; r.planAns = "route"; r.d29 = 3; r.m29 = 24; r.d24 = 3; r.still = 1; cases.add(new Object[]{"11 oscillation", r, "oscillation"});
        r = base("blocked", 3109, 3356); r.objs.add(obj(1276, "Tree", 3110, 3356, "solid", 10, null)); cases.add(new Object[]{"12 blocked-object", r, "blocked-object"});
        r = base("engine", 3200, 3300); r.drv = "old"; r.still = 90; cases.add(new Object[]{"13 engine-pathfinder-giveup", r, "engine-pathfinder-giveup"});
        r = base("unknown", 3200, 3400); r.src = "replay_skip"; r.why = "a real player came within 20 tiles"; cases.add(new Object[]{"14 unknown", r, "unknown"});
        // edge cases
        r = base("stalltimeout", 3200, 3500); r.src = "replay_timeout"; r.d24 = 2; r.drv = "old"; r.still = 90; cases.add(new Object[]{"15 timeout source but stalled -> engine", r, "engine-pathfinder-giveup"});
        r = base("movingtimeout", 3200, 3600); r.src = "replay_timeout"; r.d24 = 11; r.objs.add(obj(1530, "Door", 3201, 3600, "closed", 0, null)); cases.add(new Object[]{"16 moving at the deadline beats a door", r, "timeout"});
        r = base("gatebox", 3420, 3480); r.objs.add(obj(3506, "Gate", 3421, 3480, "closed", 0, "Priest in Peril")); r.gateId = "morytania"; r.gateNeed = "Priest in Peril"; r.gateUnmet = true; r.gateBox = true; cases.add(new Object[]{"17 gate rule unmet beats the door in its box", r, "rule-gate"});
        r = base("gatemet", 3420, 3500); r.objs.add(obj(1530, "Door", 3421, 3500, "closed", 0, null)); r.gateId = "morytania"; r.gateNeed = "Priest in Peril"; r.gateUnmet = false; r.gateBox = true; r.planAns = "outside"; cases.add(new Object[]{"18 gate met, in a gate box, plain door -> door", r, "door-closed"});
        r = base("planefresh", 3200, 3700); r.px16 = true; r.objs.add(obj(1276, "Tree", 3201, 3700, "solid", 10, null)); cases.add(new Object[]{"19 plane change in the last 16 tiles -> handoff", r, "handoff"});
        r = base("planeold", 3200, 3800); r.px16 = false; r.tiles = new int[]{pk(3200, 3800, 1), pk(3200, 3800, 0), pk(3201, 3800, 0)}; r.objs.add(obj(1276, "Tree", 3201, 3800, "solid", 10, null)); cases.add(new Object[]{"20 leftover plane change -> blocked", r, "blocked-object"});
        int ok = 0;
        for (Object[] c : cases) {
            Failures.Rec x = (Failures.Rec) c[1];
            Failures.classify(x); x.bug = Failures.bugId(x);
            boolean good = c[2].equals(x.reason);
            if (good) ok++;
            log((good ? "ok   " : "FAIL ") + c[0] + ": " + x.reason + " (" + x.rsub + "), bug " + x.bug);
            Failures.accept(x);
        }
        log("RESULT classify: " + ok + "/" + cases.size());
    }

    // ---------------------------------------------------------------- A4 size
    static void size() throws Exception {
        Failures.Rec r = base("big", 2900, 3300);
        String l1000 = new String(new char[1000]).replace('\0', 'x');
        String[] log = new String[12]; for (int i = 0; i < 12; i++) log[i] = "line " + i + " " + l1000; r.log = log; // 12 KB in the ring
        r.why = l1000;
        for (int i = 0; i < 12; i++) r.objs.add(obj(1000 + i, "Some long object nameXX" + i, 2900 + (i % 7) - 3, 3300 + i / 7, "solid", 10, null));
        for (int i = 0; i < 8; i++) r.npcs.add(npc(100 + i, "A rather long npc name" + i, 2901, 3300 + i - 3, false));
        int[] t = new int[32]; for (int i = 0; i < 32; i++) t[i] = pk(2870 + i, 3300 + (i % 3), 0); r.tiles = t;
        LinkedHashMap<String, Object> inv = new LinkedHashMap<>(); List<Object> nl = new ArrayList<>(); for (int i = 0; i < 6; i++) nl.add("Notable item number " + i + " x4"); inv.put("n", 28); inv.put("notable", nl); r.snap.put("inv", inv);
        LinkedHashMap<String, Object> q = new LinkedHashMap<>(); for (int i = 0; i < 6; i++) q.put("A quest with a long name " + i, i % 2 == 0); r.snap.put("quests", q);
        Failures.classify(r); r.bug = Failures.bugId(r);
        String fid = Failures.accept(r);
        if (!Failures.flushNow(5000)) log("flush timed out");
        String found = null; for (String l : lines()) if (fid != null && l.contains("\"fid\":\"" + fid + "\"")) found = l;
        if (found == null) { log("RESULT size: line not found (fid " + fid + ")"); return; }
        JSONObject j = (JSONObject) new JSONParser().parse(found);
        int bytes = found.getBytes("UTF-8").length;
        log("RESULT size: " + bytes + " bytes (limit 4096), reason " + j.get("reason") + ", bug " + j.get("bug") + ", stall " + j.get("stall") + ", sid " + (j.get("sid") != null) + ", snap " + (j.get("snap") != null) + ", trimmed " + j.get("trim") + ", log lines " + ((List<?>) j.get("log")).size());
    }

    // ---------------------------------------------------------------- A4 caps
    static void caps() throws Exception {
        final int before = lines().size();
        final String bugId = "b-blocked-object-" + (3000 >> 4) + "," + (3000 >> 4) + ",0";
        Map<String, Integer> f0 = Watchdog.Daily.TODAY.get("fail") == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Watchdog.Daily.TODAY.get("fail"));
        final int bug0 = count(bugId), eco0 = 0;
        final int[] fids = new int[2];
        final boolean[] done = {false};
        GameWorld.getPulser().submit(new Pulse(1) { // one tick
            @Override public boolean pulse() {
                for (int i = 0; i < 40; i++) { Failures.Rec r = base("eco" + i, 3000, 3100); r.domain = "economy"; r.src = "price"; r.reason = "unknown"; r.why = "synthetic economy record"; Failures.classify(r); r.bug = Failures.bugId(r); if (Failures.accept(r) != null) fids[0]++; }
                for (int i = 0; i < 300; i++) { Failures.Rec r = base("cap" + i, 3000, 3000); r.objs.add(obj(1276, "Tree", 3001, 3000, "solid", 10, null)); Failures.classify(r); r.bug = Failures.bugId(r); if (Failures.accept(r) != null) fids[1]++; }
                done[0] = true; return true;
            }
        });
        for (int i = 0; i < 100 && !done[0]; i++) Thread.sleep(100);
        Failures.flushNow(5000);
        List<String> all = lines(); int eco = 0, cap = 0;
        for (String l : all) { if (l.contains("\"bot\":\"b19a2-" + RUN + "-eco")) eco++; if (l.contains("\"bot\":\"b19a2-" + RUN + "-cap")) cap++; }
        Map<String, Integer> f1 = Watchdog.Daily.TODAY.get("fail");
        int d = f1.getOrDefault("blocked-object", 0) - f0.getOrDefault("blocked-object", 0), de = f1.getOrDefault("unknown", 0) - f0.getOrDefault("unknown", 0);
        log("RESULT caps: economy " + eco + " of 40 written (sub-cap 30/h), path " + cap + " of 300 written; this hour's lines by this run " + (Failures.hourN) + " (cap 120); Daily path/blocked-object +" + d + " (expect 300, plus the classify case), fail/unknown +" + de + " (expect 40 economy); bug " + bugId + " count +" + (count(bugId) - bug0) + " (expect 300); capped " + Failures.capped + ", throttled " + Failures.throttled + "; file lines " + before + " -> " + all.size());
    }

    static int count(String id) { synchronized (Failures.Bugs.LOCK) { Failures.Bug b = Failures.Bugs.VIEW.get(id); return b == null ? 0 : b.count; } }

    // ---------------------------------------------------------------- A5
    @SuppressWarnings("unchecked")
    static void bugs() throws Exception {
        Failures.Rec a = base("toll1", 3268, 3227), b = base("toll2", 3268, 3228);
        for (Failures.Rec r : new Failures.Rec[]{a, b}) { r.objs.add(obj(2882, "Gate", 3268, 3227, "closed", 0, "10 gp toll")); r.objs.add(obj(2883, "Gate", 3268, 3228, "closed", 0, "10 gp toll")); r.gateId = "alkharid-toll"; r.gateNeed = "10 gp toll, coins 3"; r.gateUnmet = true; Failures.classify(r); r.bug = Failures.bugId(r); Failures.accept(r); }
        log("RESULT toll grouping: " + a.bug + " and " + b.bug + " -> " + (a.bug != null && a.bug.equals(b.bug) && a.bug.endsWith("-alkharid-toll") ? "one bug ending -alkharid-toll" : "DIFFERENT"));
        Failures.flushNow(5000);
        String id = a.bug;
        int c0 = fileCount(id);
        // owner edit by hand
        JSONObject f = bugsFile(); JSONObject e = (JSONObject) ((JSONObject) f.get("bugs")).get(id);
        e.put("status", "wont-fix"); e.put("statusBy", "owner"); e.put("note", "owner test note: pay the toll first");
        Ledger.writeAtomic(Failures.Bugs.file(), f.toJSONString());
        Failures.Rec c = base("toll3", 3268, 3228); c.objs.add(obj(2882, "Gate", 3268, 3227, "closed", 0, "10 gp toll")); c.gateId = "alkharid-toll"; c.gateNeed = "10 gp toll, coins 0"; c.gateUnmet = true; Failures.classify(c); c.bug = Failures.bugId(c); Failures.accept(c);
        Failures.flushNow(5000);
        JSONObject e2 = bug(id);
        log("RESULT owner edit: status " + e2.get("status") + " by " + e2.get("statusBy") + ", note '" + e2.get("note") + "', count " + c0 + " -> " + e2.get("count") + " (expect wont-fix, the note, +1)");
        // 3 passes -> not-reproduced
        Failures.Rec d = base("door2", 3150, 3150); d.objs.add(obj(1530, "Door", 3151, 3150, "closed", 0, null)); Failures.classify(d); d.bug = Failures.bugId(d); Failures.accept(d);
        Thread.sleep(20);
        log("openBugFor(" + d.sid + ") = " + Failures.openBugFor(d.sid) + ", bugReplayDue " + Failures.bugReplayDue(d.sid) + ", snapshotFor " + Failures.snapshotFor(d.sid));
        for (int i = 1; i <= 3; i++) { Failures.replayResult(null, d.sid, "Replayer" + i, "pass", null, "arrived"); Thread.sleep(5); }
        Failures.flushNow(5000);
        JSONObject e3 = bug(d.bug);
        log("RESULT 3 passes: status " + e3.get("status") + " by " + e3.get("statusBy") + ", replays " + ((List<?>) e3.get("replays")).size() + " (expect not-reproduced, 3); openBugFor now " + Failures.openBugFor(d.sid));
        // the file loses the bug (a merge package without it): the next save puts it back; then a live record reopens it
        JSONObject f2 = bugsFile(); ((JSONObject) f2.get("bugs")).remove(d.bug); Ledger.writeAtomic(Failures.Bugs.file(), f2.toJSONString());
        Thread.sleep(20);
        Failures.Rec d2 = base("door3", 3150, 3150); d2.objs.add(obj(1530, "Door", 3151, 3150, "closed", 0, null)); Failures.classify(d2); d2.bug = Failures.bugId(d2); Failures.accept(d2);
        Failures.flushNow(5000);
        JSONObject e4 = bug(d.bug);
        log("RESULT reopen: " + (e4 == null ? "MISSING from the file" : "status " + e4.get("status") + ", note '" + e4.get("note") + "', count " + e4.get("count")) + " (expect back in the file, open, note starts 'reopened', count 2)");
        log("openBugFor after reopen " + Failures.openBugFor(d.sid) + "; bugs.json entries " + ((JSONObject) bugsFile().get("bugs")).size() + ", saves " + Failures.Bugs.saves);
        String rep = Failures.report(Watchdog.Daily.utc(System.currentTimeMillis()));
        int top = rep.indexOf("## Top open bugs");
        log("RESULT report: has 'Failures by reason' " + rep.contains("## Failures by reason") + ", 'Top open bugs' " + (top >= 0) + ", door row " + rep.contains(d.bug) + ", 'New bugs since yesterday' " + rep.contains("## New bugs since yesterday") + ", 'Exceptions' " + rep.contains("## Exceptions"));
        for (String l : rep.split("\n")) if (l.contains(d.bug) || l.contains(id) || l.startsWith("| bug") || l.startsWith("| path | blocked-object")) log("report: " + l);
    }
}
