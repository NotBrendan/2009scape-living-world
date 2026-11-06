package content.custom;

import core.api.StartupListener;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Trial only (-Dtest.b19r=true): batch 19a builder B, B6 = the stuck report's failure sections (design 8), via Watchdog.Daily.partial().
 * From a test thread once Daily has read its counts: the counters A's Failures would note (one "fail" per reason code, rule-gate x3, two
 * "failcap", three "bug" for the Al Kharid toll bug, two for a door bug only known from the counters, one "exc"), and a bugs.json with
 * the toll bug (status open, first = now, place, example) when the trial world has none (removed again after). Then partial() and:
 *   R1 "## Failures by reason" with the 14 code rows plus capped and exceptions, rule-gate 3, capped 2, and the "failures capped: 2" line;
 *   R2 "## Top open bugs" with the toll row (24 h 3, its place and example from bugs.json, status open, change "new") and the door row
 *      (place from the id, status "?");
 *   R3 "## New bugs since yesterday" lists the toll bug;
 *   R4 "## Exceptions" with AfkTasks.Runner.step / NullPointerException and its frame;
 *   R5 the sections sit after "Idle give-ups by reason" and before "Replays"; the batch 18 sections are all still there.
 */
public final class TestB19R implements StartupListener {
    static void log(String s) { System.out.println("[TestB19R] " + s); }
    static final String TOLL = "b-rule-gate-204,201,0-alkharid-toll", DOOR = "b-door-closed-200,200,0";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19r")) return;
        new Thread(TestB19R::run, "TestB19R").start();
    }

    static void run() {
        File bugs = null; boolean made = false;
        try {
            for (int i = 0; i < 600 && Watchdog.Daily.day == null; i++) Thread.sleep(500);
            Thread.sleep(20_000); // the world up
            log("Daily day " + Watchdog.Daily.day + ", failures " + (Watchdog.FAIL_ON ? "on" : "off"));
            for (String r : Watchdog.Daily.REASONS) Watchdog.Daily.note("fail", r);
            Watchdog.Daily.note("fail", "rule-gate"); Watchdog.Daily.note("fail", "rule-gate");
            Watchdog.Daily.note("failcap", "rescue"); Watchdog.Daily.note("failcap", "idle_giveup");
            for (int i = 0; i < 3; i++) Watchdog.Daily.note("bug", TOLL);
            for (int i = 0; i < 2; i++) Watchdog.Daily.note("bug", DOOR);
            Watchdog.Daily.note("exc", "AfkTasks.Runner.step\tNullPointerException\tcontent.custom.AfkTasks$Runner.step(AfkTasks.java:1405)");
            bugs = new File(Scenarios.sDir(), "bugs.json");
            if (!bugs.exists()) {
                long now = System.currentTimeMillis();
                String j = "{\"v\":1,\"updated\":" + now + ",\"host\":\"test\",\"bugs\":{\"" + TOLL + "\":{\"id\":\"" + TOLL + "\",\"reason\":\"rule-gate\",\"rsub\":\"10 gp toll\",\"cell\":[204,201,0],"
                        + "\"place\":\"3264-3279, 3216-3231, 0 (Al Kharid gate)\",\"first\":" + now + ",\"last\":" + now + ",\"count\":3,\"days\":{},\"sources\":{\"idle_giveup\":3},"
                        + "\"examples\":[\"idle_giveup|shop|408,403,0|405,401,0\"],\"fids\":[],\"bots\":[],\"status\":\"open\",\"statusT\":" + now + ",\"statusBy\":\"live\",\"note\":\"\",\"replays\":[]}}}";
                Files.write(bugs.toPath(), j.getBytes("UTF-8")); made = true;
            }
            log("bugs.json " + (made ? "written by the test" : "already there (left as it is)") + ": " + bugs.getPath());
            File f = Watchdog.Daily.partial();
            if (f == null) { log("RESULT partial: none"); return; }
            List<String> l = Files.readAllLines(f.toPath());
            String all = String.join("\n", l);
            int fr = l.indexOf("## Failures by reason (data/survey/failures/)"), rows = 0; String rg = "?", capRow = "?";
            if (fr >= 0) for (int i = fr + 4; i < l.size() && l.get(i).startsWith("| "); i++) {
                rows++;
                if (l.get(i).startsWith("| rule-gate |")) rg = l.get(i);
                if (l.get(i).startsWith("| capped |")) capRow = l.get(i);
            }
            log("RESULT R1 Failures by reason: " + (fr >= 0 ? "present" : "MISSING") + ", " + rows + " rows (expect 16 = 14 codes + capped + exceptions); " + rg + " (expect 3); " + capRow + " (expect 2); 'failures capped: 2' line " + all.contains("failures capped: 2"));
            String toll = null, door = null;
            int top = l.indexOf("## Top open bugs (data/survey/bugs.json)");
            if (top >= 0) for (int i = top + 4; i < l.size() && l.get(i).startsWith("| "); i++) { if (l.get(i).startsWith("| " + TOLL + " ")) toll = l.get(i); if (l.get(i).startsWith("| " + DOOR + " ")) door = l.get(i); }
            log("RESULT R2 Top open bugs: " + (top >= 0 ? "present" : "MISSING") + "; toll row: " + toll + " (expect 24 h 3, place 'Al Kharid gate', example, open, new)");
            log("RESULT R2 door row (counters only): " + door + " (expect place 3200-3215, 3200-3215, 0, status ?, new)");
            int nw = l.indexOf("## New bugs since yesterday"); boolean nTol = false;
            if (nw >= 0) for (int i = nw + 4; i < l.size() && l.get(i).startsWith("| "); i++) if (l.get(i).startsWith("| " + TOLL + " ")) nTol = true;
            log("RESULT R3 New bugs since yesterday: " + (nw >= 0 ? "present" : "MISSING") + ", lists the toll bug " + nTol);
            int ex = l.indexOf("## Exceptions"); String exRow = null;
            if (ex >= 0) for (int i = ex + 4; i < l.size() && l.get(i).startsWith("| "); i++) if (l.get(i).contains("AfkTasks.Runner.step")) exRow = l.get(i);
            log("RESULT R4 Exceptions: " + (ex >= 0 ? "present" : "MISSING") + "; row " + exRow);
            int idle = l.indexOf("## Idle give-ups by reason"), rep = -1; for (int i = 0; i < l.size(); i++) if (l.get(i).startsWith("## Replays")) rep = i;
            String[] b18 = {"## Top stuck cells", "## Never-reached shops", "## No-route destinations", "## Outside-grid destinations", "## Redirects by reason", "## Idle give-ups by reason", "## Replays"};
            int have = 0; for (String h : b18) for (String x : l) if (x.startsWith(h)) { have++; break; }
            log("RESULT R5 order: idle " + idle + " < failures " + fr + " < top " + top + " < new " + nw + " < exceptions " + ex + " < replays " + rep + ": " + (idle < fr && fr < top && top < nw && nw < ex && ex < rep) + "; batch 18 sections " + have + "/" + b18.length);
            for (String x : l) log("  | " + x);
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        finally { if (made && bugs != null) bugs.delete(); log("DONE"); }
    }
}
