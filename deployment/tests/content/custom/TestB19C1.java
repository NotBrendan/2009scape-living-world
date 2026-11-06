package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * batch 19c A1, trial only (-Dtest.b19c1=true -Dfailures.debug=true; second run adds -Dfailures.domains=path): Failures.event without
 * the game. 200 synthetic server-level event() calls across the 7 non-path domains in one tick -> per-domain caps hold (economy <= 30,
 * save <= 5 ...), global <= 120, Daily "fail" counters +200, one fixture key per domain matches §3.3; a record with a 1 KB ctx and a
 * 12-line log trims ctx before snap.inv and stays <= 4,096 B; with -Dfailures.domains=path all non-path events write 0 lines and the
 * counters still move. Server-level records only (bot null): no player at all. Prints [TestB19C1] RESULT ... and DONE.
 */
public final class TestB19C1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C1] " + s); }
    static final String RUN = Long.toString(System.currentTimeMillis() % 100000, 36);

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; boolean go;
            @Override public boolean pulse() {
                t++;
                if (go) return true;
                if (t >= 30 && Failures.started && Watchdog.Daily.day != null) { go = true; new Thread(TestB19C1::run, "TestB19C1").start(); return true; }
                if (t > 600) { log("RESULT Failures never started (started " + Failures.started + ", Daily day " + Watchdog.Daily.day + ")"); log("DONE"); return true; }
                return false;
            }
        });
    }

    static List<String> lines() {
        try { File f = new File(Failures.failDir(), Watchdog.Daily.utc(System.currentTimeMillis()) + ".jsonl"); return f.exists() ? Files.readAllLines(f.toPath()) : new ArrayList<>(); } catch (Throwable e) { return new ArrayList<>(); }
    }
    static int fail(String code) { Map<String, Integer> m = Watchdog.Daily.TODAY.get("fail"); return m == null ? 0 : m.getOrDefault(code, 0); }
    static boolean hasBug(String id) { synchronized (Failures.Bugs.LOCK) { return Failures.Bugs.VIEW.containsKey(id); } }

    static void run() {
        try {
            boolean onlyPath = !Failures.on("economy");
            if (onlyPath) domainsOff(); else { caps(); trim(); }
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        log("DONE");
    }

    static final String[] DOMS = {"economy", "quest", "combat", "skilling", "lifecycle", "health", "save"};
    static final int[] N = {60, 30, 30, 30, 15, 15, 20}; // 200

    static Map<String, Object> ctxFor(String d, int i) {
        LinkedHashMap<String, Object> c = new LinkedHashMap<>();
        switch (d) {
            case "economy": c.put("key", "shop-" + RUN + "-" + i); c.put("item", 1351); break;
            case "quest": c.put("quest", "cooks-" + RUN); c.put("step", i); break;
            case "combat": c.put("killer", "Dark wizard " + RUN + i); break;
            case "skilling": c.put("task", "TREE_" + RUN + "_" + i); break;
            case "lifecycle": c.put("role", "adventurer-" + RUN + i); break;
            case "health": c.put("source", "Test" + RUN + i); break;
            default: c.put("file", "smartbots-" + RUN + "-" + i + ".json"); break;
        }
        return c;
    }

    static void caps() throws Exception {
        final int before = lines().size();
        Map<String, Integer> pre = new LinkedHashMap<>(); for (String d : DOMS) pre.put(d, fail("t19c1-" + d));
        int[] h0 = new int[DOMS.length]; synchronized (Failures.CAPS) { for (int i = 0; i < DOMS.length; i++) { int[] v = Failures.DOMAIN_N.get(DOMS[i]); h0[i] = v == null || Failures.capHour != System.currentTimeMillis() / 3_600_000L ? 0 : v[0]; } }
        final int[] queued = new int[DOMS.length]; final boolean[] done = {false};
        GameWorld.getPulser().submit(new Pulse(1) { // one tick
            @Override public boolean pulse() {
                for (int k = 0; k < DOMS.length; k++) for (int i = 0; i < N[k]; i++) {
                    Location at = Location.create(3200 + i * 16, 3200 + k * 16, 0);
                    if (Failures.event(DOMS[k], null, "test", "t19c1-" + DOMS[k], "synthetic " + i, at, ctxFor(DOMS[k], i)) != null) queued[k]++;
                }
                done[0] = true; return true;
            }
        });
        for (int i = 0; i < 100 && !done[0]; i++) Thread.sleep(100);
        Failures.flushNow(5000);
        List<String> all = lines();
        int[] written = new int[DOMS.length]; int moved = 0;
        for (String l : all) for (int k = 0; k < DOMS.length; k++) if (l.contains("\"reason\":\"t19c1-" + DOMS[k] + "\"")) written[k]++;
        StringBuilder b = new StringBuilder(); int tot = 0;
        for (int k = 0; k < DOMS.length; k++) {
            int[] cap = Failures.DOMAIN_CAP.get(DOMS[k]); int mv = fail("t19c1-" + DOMS[k]) - pre.get(DOMS[k]); moved += mv; tot += written[k];
            b.append(DOMS[k]).append(' ').append(written[k]).append('/').append(N[k]).append(" (cap ").append(cap[0]).append(", room ").append(cap[0] - h0[k]).append(written[k] <= cap[0] - h0[k] ? " ok" : " OVER").append("); ");
        }
        log("RESULT caps: " + b + "total written " + tot + " (global cap " + Failures.CAP_HOUR + ", this hour " + Failures.hourN + "), Daily fail counters +" + moved + " (expect 200), file lines " + before + " -> " + all.size());
        // §3.3 keys, one fixture per domain (i = 0)
        String[] expect = {
                "b-t19c1-economy-shop-" + RUN + "-0",
                "b-t19c1-quest-cooks-" + RUN + "-0",
                "b-t19c1-combat-" + (3200 >> 4) + "-" + ((3200 + 2 * 16) >> 4) + "-0-dark-wizard-" + RUN + "0",
                "b-t19c1-skilling-tree-" + RUN + "-0-" + (3200 >> 4) + "-" + ((3200 + 3 * 16) >> 4) + "-0",
                "b-t19c1-lifecycle-adventurer-" + RUN + "0",
                "b-t19c1-health-test" + RUN + "0",
                "b-t19c1-save-smartbots-" + RUN + "-0-json"};
        int ok = 0; StringBuilder kb = new StringBuilder();
        for (String id : expect) { boolean h = hasBug(id); if (h) ok++; kb.append(h ? "ok " : "MISSING ").append(id).append("; "); }
        log("RESULT keys: " + ok + "/7 " + kb);
        // a line from a written record: domain, bot "-", ctx, no snap
        for (String l : all) if (l.contains("\"reason\":\"t19c1-economy\"")) { JSONObject j = (JSONObject) new JSONParser().parse(l); log("RESULT server record: domain " + j.get("domain") + ", bot " + j.get("bot") + ", snap " + j.get("snap") + ", ctx " + j.get("ctx") + ", bug " + j.get("bug") + ", stall " + j.get("stall")); break; }
    }

    static void trim() throws Exception {
        Failures.Rec r = new Failures.Rec();
        r.domain = "economy"; r.src = "test"; r.bot = "-"; r.t = System.currentTimeMillis(); r.reason = "t19c1-trim"; r.rsub = "trim"; r.why = new String(new char[160]).replace('\0', 'w'); r.key = "trim"; r.bug = "b-t19c1-trim-trim";
        String l160 = new String(new char[150]).replace('\0', 'x');
        String[] lg = new String[12]; for (int i = 0; i < 12; i++) lg[i] = "line " + i + " " + l160; r.log = lg;
        LinkedHashMap<String, Object> c = new LinkedHashMap<>(); for (int i = 0; i < 10; i++) c.put("k" + i, new String(new char[95]).replace('\0', (char) ('a' + i))); r.ctx = c; // ~1 KB, bypassing event's 700 B fit
        LinkedHashMap<String, Object> snap = new LinkedHashMap<>(); snap.put("role", "adventurer"); snap.put("coins", 830);
        LinkedHashMap<String, Object> inv = new LinkedHashMap<>(); List<Object> nl = new ArrayList<>(); for (int i = 0; i < 6; i++) nl.add("Notable item number " + i + " x4"); inv.put("n", 28); inv.put("notable", nl); snap.put("inv", inv);
        LinkedHashMap<String, Object> q = new LinkedHashMap<>(); for (int i = 0; i < 22; i++) q.put("A quest with a fairly long name number " + i + " and more text to pad the snapshot out", i % 2 == 0); snap.put("quests", q);
        snap.put("goal", new String(new char[80]).replace('\0', 'g'));
        r.snap = snap;
        String out = Failures.line(r);
        int bytes = out.getBytes("UTF-8").length;
        JSONObject j = (JSONObject) new JSONParser().parse(out);
        List<?> tr = (List<?>) j.get("trim");
        int ic = tr == null ? -1 : tr.indexOf("ctx"), ii = tr == null ? -1 : tr.indexOf("inv");
        String untrimmed = Failures.Json.write(Failures.toMap(r, new int[]{12, 12, 8, 32, 6, 6, 1}, new ArrayList<>(), false, false, false));
        log("RESULT trim: untrimmed " + untrimmed.length() + " B -> " + bytes + " B (limit 4096), trim " + tr + ", ctx " + (ic >= 0 ? "trimmed at step " + ic : "kept") + ", inv " + (ii >= 0 ? "trimmed at step " + ii : "kept") + " -> " + (bytes <= 4096 && ic >= 0 && (ii < 0 || ic < ii) ? "ctx before inv, fits" : "WRONG"));
        LinkedHashMap<String, Object> big = new LinkedHashMap<>(); for (int i = 0; i < 10; i++) big.put("k" + i, new String(new char[95]).replace('\0', 'z'));
        Map<String, Object> fit = Failures.fitCtx(big, 700);
        log("RESULT fitCtx: 1 KB ctx -> " + Failures.Json.write(fit).length() + " B (limit 700), keys " + fit.size() + ", cut " + fit.get("cut"));
    }

    static void domainsOff() throws Exception {
        final int before = lines().size();
        int pre = 0; for (String d : DOMS) pre += fail("t19c1-off-" + d);
        final int[] q = {0}; final boolean[] done = {false};
        GameWorld.getPulser().submit(new Pulse(1) {
            @Override public boolean pulse() {
                for (String d : DOMS) for (int i = 0; i < 5; i++) if (Failures.event(d, null, "test", "t19c1-off-" + d, "off " + i, Location.create(3000 + i * 16, 3000, 0), ctxFor(d, i)) != null) q[0]++;
                done[0] = true; return true;
            }
        });
        for (int i = 0; i < 100 && !done[0]; i++) Thread.sleep(100);
        Failures.flushNow(5000);
        int w = 0; for (String l : lines()) if (l.contains("\"reason\":\"t19c1-off-")) w++;
        int post = 0; for (String d : DOMS) post += fail("t19c1-off-" + d);
        log("RESULT domains=path: on(path) " + Failures.on("path") + ", on(economy) " + Failures.on("economy") + "; 35 non-path events -> queued " + q[0] + ", lines " + w + " (expect 0), Daily fail +" + (post - pre) + " (expect 35), offN " + Failures.offN + ", file lines " + before + " -> " + lines().size());
    }
}
