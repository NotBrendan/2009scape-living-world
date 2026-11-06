package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trial only (-Dtest.rc3a2=true, with -Dtraders.fast=true -Dsurvey.replay=0 -Dsurvey.replay.prove=false -Dscenarios.results.dir=data/rc3a-results):
 * batch 18 rc3 part A, changes 2-4 (new members by reflection, so it compiles and runs on the rc2 tree too = BEFORE).
 *   2  three daily gave_ups = fail: fake scenarios A..H (put in Scenarios.INDEX, CLAIMED so nothing replays them) and fake result lines written to
 *      Scenarios.resultsFile() (the -Dscenarios.results.dir folder; C's lines out of time order), then the load path (loadResults; rc2: noteResult per line);
 *      A gave_up -2d,-1d,today: proven fail | B gave_up -2d,-2d,today (2 days): open | C gave_up -4d,-3d, skipped -2d, gave_up -1d: proven fail |
 *      D gave_up -5d,-4d, pass -3d, re-recorded -2.5d, gave_up -2d,-1d: open (the pass ended the run) | E closed at -2d, re-recorded -1.5d: open |
 *      H gave_up -2d,-1d loaded, then a live gave_up now through noteResult: closes (returns true), proven.
 *   3  fair queue: rescue N1 (newest), rescue N2, prove O1 (t=1000, oldest) eligible; picks reset to 0; choose() 8 times: N1 N1 N1 O1 N1 N1 N1 O1.
 *   4  Watchdog.Daily.partial() from a non-game thread: "Failed replays (named bugs)" lists A (gave up 3 days running) and F (fail today, failAt,
 *      source/walker from scenarios.jsonl, F is not in INDEX) and not G (fail then pass today) or C; "Replays" counts come from the results dir.
 */
public final class TestRc3A2 implements StartupListener {
    static void log(String s) { System.out.println("[TestRc3A2] " + s); }
    static final long DAY = 86_400_000L;

    @Override public void startup() {
        if (!Boolean.getBoolean("test.rc3a2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, reportAt = -1; long now, d0; volatile boolean reportDone;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 40) { part2and3(); reportAt = t + 20; return false; } // 12 s: the 5 s flush has written F's scenario line
                    if (t == reportAt) {
                        new Thread(() -> {
                            try {
                                File f = Watchdog.Daily.partial();
                                log("RESULT 4 partial report: " + (f == null ? "none" : f.getPath()));
                                if (f != null) {
                                    boolean on = false; List<String> lines = Files.readAllLines(f.toPath());
                                    for (String l : lines) { if (l.startsWith("## Replays")) on = true; if (on) log("  | " + l); }
                                    String all = String.join("\n", lines);
                                    boolean sec = all.contains("## Failed replays (named bugs)");
                                    log("RESULT 4 section present " + sec + "; A listed " + has(lines, "rc3a/A|") + " (gave up 3 days running " + has(lines, "rc3a/A|", "gave up 3 days running") + "); F listed " + has(lines, "rc3a/F|")
                                        + " (failAt 3100,3240,0 " + has(lines, "rc3a/F|", "3100,3240,0") + ", source from scenarios.jsonl " + has(lines, "rc3a/F|", "| seed | other |") + "); G listed " + has(lines, "rc3a/G|") + " (expect false); C listed " + has(lines, "rc3a/C|") + " (expect false)");
                                }
                            } catch (Throwable e) { log("report error " + e); e.printStackTrace(System.out); }
                            reportDone = true;
                        }, "TestRc3A2-report").start();
                        return false;
                    }
                    if (reportDone) { log("DONE"); return true; }
                    if (t > 400) { log("RESULT timeout waiting for the report"); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            long today(int k) { return d0 + (now - d0) * k / 10; } // always today, before now
            long ago(double days) { return d0 + 43_200_000L - (long) (days * DAY); } // noon UTC, days back

            String row(String id, long t, String result, String failAt, String why) {
                return "{\"v\":1,\"t\":" + t + ",\"id\":\"" + id + "\",\"bot\":\"Fake\",\"result\":\"" + result + "\",\"ticks\":10,\"failAt\":" + (failAt == null ? "null" : "[" + failAt + "]")
                    + ",\"why\":\"" + why + "\",\"nav\":\"other walker; nav x3, straight x2; closest 12 tiles\",\"lag\":60}";
            }

            Scenarios.Sc sc(String id, String source, long t) {
                Scenarios.Sc s = new Scenarios.Sc(); s.id = id; s.source = source; s.walker = "other"; s.activity = "test"; s.reason = "rc3a test";
                s.from = new int[]{3222, 3218, 0}; s.to = new int[]{3092, 3245, 0}; s.t = t; return s;
            }

            boolean proven(String id) throws Exception { Method m = Scenarios.class.getDeclaredMethod("proven", Scenarios.Sc.class); m.setAccessible(true); return (Boolean) m.invoke(null, Scenarios.INDEX.get(id)); }

            void part2and3() throws Exception {
                now = System.currentTimeMillis(); d0 = Math.floorDiv(now, DAY) * DAY;
                File rf = Scenarios.resultsFile();
                log("loaded " + Scenarios.loaded + "; results file " + rf.getAbsolutePath() + " (scenarios.results.dir " + System.getProperty("scenarios.results.dir") + ")");
                String p = "seed|other|rc3a/";
                String A = p + "A|", B = p + "B|", C = p + "C|", D = p + "D|", E = p + "E|", F = p + "F|", G = p + "G|", H = p + "H|";
                Scenarios.INDEX.put(A, sc(A, "seed", ago(5))); Scenarios.INDEX.put(B, sc(B, "seed", ago(5))); Scenarios.INDEX.put(C, sc(C, "seed", ago(6)));
                Scenarios.INDEX.put(D, sc(D, "seed", ago(2.5))); Scenarios.INDEX.put(E, sc(E, "seed", ago(1.5))); Scenarios.INDEX.put(G, sc(G, "seed", ago(1))); Scenarios.INDEX.put(H, sc(H, "seed", ago(5)));
                for (String id : new String[]{A, B, C, D, E, F, G, H}) Scenarios.CLAIMED.add(id);
                // F only in scenarios.jsonl (through the normal flush queue), so the report has to read the file for it
                Scenarios.OUT_S.add("{\"v\":1,\"t\":" + ago(1) + ",\"id\":\"" + F + "\",\"source\":\"seed\",\"walker\":\"other\",\"activity\":\"test\",\"from\":[3222,3218,0],\"to\":[3092,3245,0],\"stuckAt\":null,\"reason\":\"rc3a test\",\"profile\":{\"combat\":-1,\"agility\":-1,\"coins\":-1}}");
                List<String> rows = new ArrayList<>();
                rows.add(row(A, ago(2), "gave_up", null, "not there after 10 minutes, 40 tiles short")); rows.add(row(A, ago(1), "gave_up", null, "not there after 10 minutes, 41 tiles short")); rows.add(row(A, today(3), "gave_up", null, "not there after 10 minutes, 39 tiles short"));
                rows.add(row(B, ago(2), "gave_up", null, "x")); rows.add(row(B, ago(2) + 3_600_000L, "gave_up", null, "x")); rows.add(row(B, today(4), "gave_up", null, "x"));
                rows.add(row(C, ago(1), "gave_up", null, "x")); rows.add(row(C, ago(3), "gave_up", null, "x")); rows.add(row(C, ago(2), "skipped", null, "a real player came within 20 tiles")); rows.add(row(C, ago(4), "gave_up", null, "x")); // out of time order
                rows.add(row(D, ago(5), "gave_up", null, "x")); rows.add(row(D, ago(4), "gave_up", null, "x")); rows.add(row(D, ago(3), "pass", null, "arrived")); rows.add(row(D, ago(2), "gave_up", null, "x")); rows.add(row(D, ago(1), "gave_up", null, "x"));
                rows.add(row(E, ago(4), "gave_up", null, "x")); rows.add(row(E, ago(3), "gave_up", null, "x")); rows.add(row(E, ago(2), "gave_up", null, "x"));
                rows.add(row(F, today(5), "fail", "3100,3240,0", "no progress for 60 s, 9 tiles short"));
                rows.add(row(G, today(2), "fail", "3101,3241,0", "no progress for 60 s, 8 tiles short")); rows.add(row(G, today(6), "pass", null, "arrived"));
                rows.add(row(H, ago(2), "gave_up", null, "x")); rows.add(row(H, ago(1), "gave_up", null, "x"));
                rf.getParentFile().mkdirs(); Files.write(rf.toPath(), (String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8)); // a fresh file for this run
                // the load path
                List<org.json.simple.JSONObject> parsed = Scenarios.readResults();
                Method lr = null; try { lr = Scenarios.class.getDeclaredMethod("loadResults", List.class); lr.setAccessible(true); } catch (NoSuchMethodException e) { }
                Scenarios.RESULTS.clear();
                Method nr = Scenarios.class.getDeclaredMethod("noteResult", String.class, String.class, long.class); nr.setAccessible(true);
                if (lr != null) { @SuppressWarnings("unchecked") Map<String, Scenarios.Res> m = (Map<String, Scenarios.Res>) lr.invoke(null, parsed); Scenarios.RESULTS.putAll(m); log("load path: loadResults over " + parsed.size() + " lines"); }
                else { for (org.json.simple.JSONObject j : parsed) nr.invoke(null, String.valueOf(j.get("id")), String.valueOf(j.get("result")), Scenarios.num(j.get("t"), 0)); log("load path (rc2): noteResult over " + parsed.size() + " lines in file order"); }
                String[][] exp = {{A, "true"}, {B, "false"}, {C, "true"}, {D, "false"}, {E, "false"}, {G, "true"}, {H, "false"}};
                int ok = 0;
                for (String[] x : exp) { boolean pv = proven(x[0]); Scenarios.Res r = Scenarios.RESULTS.get(x[0]); if (String.valueOf(pv).equals(x[1])) ok++;
                    log("RESULT 2 " + x[0] + " proven " + pv + " (expect " + x[1] + "), done " + (r == null ? "-" : r.done) + ", last " + (r == null ? "-" : r.last)); }
                Object closed = nr.invoke(null, H, "gave_up", now);
                boolean hp = proven(H); if (hp) ok++;
                log("RESULT 2 live: H gave_up now -> noteResult returned " + closed + ", proven " + hp + " (expect true), toReplay after release " + releasedToReplay(H) + " (expect false)");
                log("RESULT 2 summary " + ok + "/8 as expected; " + Scenarios.counts());
                // 3: fair queue
                String N1 = "rescue|other|rc3a/N1|", N2 = "rescue|other|rc3a/N2|", O1 = "prove|afk|rc3a/O1|";
                Scenarios.INDEX.put(N1, sc(N1, "rescue", now - 600_000L)); Scenarios.INDEX.put(N2, sc(N2, "rescue", now - 1_200_000L)); Scenarios.INDEX.put(O1, sc(O1, "prove", 1000L));
                Method ch = null; try { ch = Scenarios.class.getDeclaredMethod("choose"); ch.setAccessible(true); Field pf = Scenarios.class.getDeclaredField("picks"); pf.setAccessible(true); pf.setInt(null, 0); } catch (NoSuchMethodException e) { }
                Method nx = Scenarios.class.getDeclaredMethod("next"); nx.setAccessible(true);
                StringBuilder seq = new StringBuilder(); int other = 0;
                for (int i = 0; i < 8; i++) { Scenarios.Sc s = (Scenarios.Sc) (ch != null ? ch.invoke(null) : nx.invoke(null)); String n = s == null ? "null" : s.id.equals(N1) ? "N1" : s.id.equals(N2) ? "N2" : s.id.equals(O1) ? "O1" : "other(" + s.id + ")"; seq.append(n).append(' '); }
                log("RESULT 3 " + (ch != null ? "choose()" : "next() (rc2, no choose)") + " x8: " + seq.toString().trim() + " (expect N1 N1 N1 O1 N1 N1 N1 O1)");
                Scenarios.INDEX.remove(N1); Scenarios.INDEX.remove(N2); Scenarios.INDEX.remove(O1);
            }

            boolean releasedToReplay(String id) { Scenarios.CLAIMED.remove(id); boolean b = Scenarios.toReplay(id); Scenarios.CLAIMED.add(id); return b; }
        });
    }

    static boolean has(List<String> lines, String id, String... also) {
        String key = id.replace("|", "/");
        for (String l : lines) if (l.contains(key)) { boolean all = true; for (String a : also) if (!l.contains(a)) all = false; if (all) return true; }
        return false;
    }
}
