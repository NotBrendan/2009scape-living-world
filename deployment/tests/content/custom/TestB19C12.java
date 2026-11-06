package content.custom;

import core.api.StartupListener;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.json.simple.JSONObject;

/**
 * Trial only (-Dtest.b19c12=true): batch 19c builder B, B5 = the daily report's 19c layout (Watchdog.Daily.failures via partial()):
 * Daily notes for one code of every domain (fail), failcap notes for three domains' sources, bug notes for an economy fixture bug
 * (b-toll-unaffordable-alkharid, first = now) and the 19a path fixture bug (b-rule-gate-204,201,0-alkharid-toll); a bugs.json with both
 * (the economy one with "domain":"economy", the path one without a domain, as a 19a file has it) when the world has none (removed after).
 *   P1 "## Failures by reason" holds eight domain blocks (path first, then economy quest combat skilling lifecycle health save), each
 *      with its codes and a capped row; the path block still has the 14 codes + capped + exceptions (TestB19R's 16 rows)
 *   P2 per-domain capped: path 1 (rescue), economy 1 (toll), quest 1 (quest_stall)
 *   P3 the Top open bugs header has "domain" after "bug"; every row's domain is one of the eight
 *   P4 the economy fixture bug row: domain economy, change "new"; the path fixture bug keeps its 19a id and shows domain path
 *   P5 the "Economy today" line: records, the top economy bugs (the fixture), coin-drift-world status
 */
public final class TestB19C12 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C12] " + s); }
    static final String ECON = "b-toll-unaffordable-alkharid", TOLL = "b-rule-gate-204,201,0-alkharid-toll";
    static final String[] DOMAINS = {"path", "economy", "quest", "combat", "skilling", "lifecycle", "health", "save"};

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c12")) return;
        new Thread(TestB19C12::run, "TestB19C12").start();
    }

    static void run() {
        File bugs = null; boolean made = false;
        try {
            for (int i = 0; i < 600 && Watchdog.Daily.day == null; i++) Thread.sleep(500);
            Thread.sleep(20_000);
            String[] codes = {"rule-gate", "toll-unaffordable", "step-stalled", "repeat-death", "no-gain", "vanished", "tick-overrun", "save-stale"};
            for (String c : codes) Watchdog.Daily.note("fail", c);
            Watchdog.Daily.note("fail", "toll-unaffordable"); Watchdog.Daily.note("fail", "toll-unaffordable");
            Watchdog.Daily.note("failcap", "rescue"); Watchdog.Daily.note("failcap", "toll"); Watchdog.Daily.note("failcap", "quest_stall");
            for (int i = 0; i < 3; i++) Watchdog.Daily.note("bug", ECON);
            for (int i = 0; i < 2; i++) Watchdog.Daily.note("bug", TOLL);
            bugs = new File(Scenarios.rDir(), "bugs.json");
            File was = new File(bugs.getPath() + ".t19c12-was"); if (bugs.exists()) { Files.copy(bugs.toPath(), was.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); restore = was; }
            {
                long now = System.currentTimeMillis();
                String j = "{\"v\":1,\"updated\":" + now + ",\"host\":\"test\",\"bugs\":{"
                        + "\"" + ECON + "\":{\"id\":\"" + ECON + "\",\"domain\":\"economy\",\"reason\":\"toll-unaffordable\",\"rsub\":\"alkharid 10 gp, coins 3\",\"cell\":null,\"place\":\"3268,3227,0\",\"first\":" + now + ",\"last\":" + now + ",\"count\":3,\"days\":{},\"sources\":{\"toll\":3},\"examples\":[],\"fids\":[],\"bots\":[],\"status\":\"open\",\"statusT\":" + now + ",\"statusBy\":\"live\",\"note\":\"\",\"replays\":[]},"
                        + "\"" + TOLL + "\":{\"id\":\"" + TOLL + "\",\"reason\":\"rule-gate\",\"rsub\":\"10 gp toll\",\"cell\":[204,201,0],\"place\":\"3264-3279, 3216-3231, 0 (Al Kharid gate)\",\"first\":" + (now - 3 * 86_400_000L) + ",\"last\":" + now + ",\"count\":9,\"days\":{},\"sources\":{\"idle_giveup\":9},\"examples\":[\"idle_giveup|shop|408,403,0|405,401,0\"],\"fids\":[],\"bots\":[],\"status\":\"open\",\"statusT\":" + now + ",\"statusBy\":\"live\",\"note\":\"\",\"replays\":[]}}}";
                Files.write(bugs.toPath(), j.getBytes("UTF-8")); made = true;
            }
            log("bugs.json written by the test" + (restore != null ? " (the world's own kept aside and put back after)" : "") + ": " + bugs.getPath());
            File f = Watchdog.Daily.partial();
            if (f == null) { log("RESULT partial: none"); return; }
            List<String> l = Files.readAllLines(f.toPath());
            int fr = l.indexOf("## Failures by reason (data/survey/failures/)");
            int blocks = 0, pathRows = 0; StringBuilder order = new StringBuilder(); String capPath = "?", capEcon = "?", capQuest = "?", cur = null; boolean allCapped = true; int lastHeader = -1;
            for (int i = fr + 1; fr >= 0 && i < l.size() && !l.get(i).startsWith("## "); i++) {
                String x = l.get(i);
                for (String d : DOMAINS) if (x.equals("| " + d + " | 24 h | 7 days |")) { if (cur != null && !cappedSeen) allCapped = false; blocks++; order.append(d).append(' '); cur = d; cappedSeen = false; lastHeader = i; }
                if (x.startsWith("| capped |")) { cappedSeen = true; if ("path".equals(cur)) capPath = x; if ("economy".equals(cur)) capEcon = x; if ("quest".equals(cur)) capQuest = x; }
                if ("path".equals(cur) && x.startsWith("| ") && !x.startsWith("|---") && !x.startsWith("| path |")) pathRows++;
            }
            if (cur != null && !cappedSeen) allCapped = false;
            int rows19a = 0; if (fr >= 0) for (int i = fr + 4; i < l.size() && l.get(i).startsWith("| "); i++) rows19a++;
            log("RESULT P1 domain blocks: " + blocks + " (expect 8) in order '" + order.toString().trim() + "', every block has a capped row " + allCapped + "; path block rows " + pathRows + " / TestB19R's count from the heading " + rows19a + " (expect 16)");
            log("RESULT P2 capped per domain: path '" + capPath + "' (expect 1), economy '" + capEcon + "' (expect 1), quest '" + capQuest + "' (expect 1)");
            int top = l.indexOf("## Top open bugs (data/survey/bugs.json)"); String head = top >= 0 && top + 2 < l.size() ? l.get(top + 2) : "?";
            String econRow = null, tollRow = null; boolean domOk = true; int rows = 0;
            if (top >= 0) for (int i = top + 4; i < l.size() && l.get(i).startsWith("| "); i++) {
                String x = l.get(i); rows++;
                String[] c = x.split("\\|"); String dom = c.length > 2 ? c[2].trim() : "";
                boolean known = false; for (String d : DOMAINS) if (d.equals(dom)) known = true; if (!known && !x.startsWith("| none ")) domOk = false;
                if (x.startsWith("| " + ECON + " ")) econRow = x; if (x.startsWith("| " + TOLL + " ")) tollRow = x;
            }
            log("RESULT P3 Top open bugs header: '" + head + "' (expect | bug | domain | ...); " + rows + " rows, every domain one of the eight " + domOk);
            log("RESULT P4 economy fixture row: " + econRow + " (expect domain economy, change new)");
            log("RESULT P4 path fixture row: " + tollRow + " (expect the 19a id, domain path)");
            String econLine = null; for (String x : l) if (x.startsWith("Economy today: ")) econLine = x;
            log("RESULT P5 " + econLine + " (expect 3 records; top economy bugs " + ECON + " (3); coin-drift-world clear)");
            String[] b18 = {"## Top stuck cells", "## Never-reached shops", "## No-route destinations", "## Outside-grid destinations", "## Redirects by reason", "## Idle give-ups by reason", "## Replays", "## New bugs since yesterday", "## Exceptions"};
            int have = 0; for (String h : b18) for (String x : l) if (x.startsWith(h)) { have++; break; }
            log("RESULT P6 other sections still there: " + have + "/" + b18.length);
            for (String x : l) log("  | " + x);
            // ---- R7 (coordinator, live 19a): the replayer's skip/fail reason hint at the hook
            String[][] cases = {{"attacked on the way (no fights)", "{replay:combat-interrupt/attacked} "}, {"a real player came within 20 tiles", "{replay:-/player-near} "},
                    {"coins or items changed", "{replay:-/coin-neutral-abort} "}, {"at the Al Kharid toll gate (coin neutral; builder E's)", "{replay:rule-gate/alkharid-toll} "},
                    {"across the sea: the crossing costs coins (boat or teleport)", "{replay:rule-gate/sea-crossing} "}, {"could not relocate: x", "{replay:handoff/relocate-failed} "},
                    {"did not land at the start (at here)", "{replay:handoff/did-not-land} "}, {"no progress for 60 s, 12 tiles short", "{replay:-/no-progress} "}, {"something new", ""}};
            int ok = 0; StringBuilder bad = new StringBuilder();
            for (String[] c : cases) { String t = Scenarios.Replayer.replayTag(c[0]); if (t.equals(c[1])) ok++; else bad.append(" [").append(c[0]).append(" -> '").append(t).append("']"); }
            log("RESULT R7 replay hints: " + ok + "/" + cases.length + " as the coordinator's table" + bad);
            final Scenarios.Replayer rp = new Scenarios.Replayer(); final core.game.bots.AIPlayer[] rb = new core.game.bots.AIPlayer[1];
            TestB19C8.onGame(() -> {
                rb[0] = TestB19C8.spawn(core.game.world.map.Location.create(3092, 3245, 0)); rp.bot = rb[0];
                Scenarios.Sc s = new Scenarios.Sc(); s.id = "test19c12|shop|386,405,0|380,400,0"; s.source = "never_reached"; s.walker = "shop"; s.from = new int[]{3092, 3245, 0}; s.to = new int[]{3040, 3200, 0}; s.t = System.currentTimeMillis();
                rp.sc = s; rp.from = core.game.world.map.Location.create(3092, 3245, 0); rp.to = core.game.world.map.Location.create(3040, 3200, 0);
                rp.end("skipped", null, "across the sea: the crossing costs coins (boat or teleport)");
            });
            JSONObject rec = null; for (JSONObject x : TestB19C8.records()) if (rb[0] != null && rb[0].getUsername().equals(x.get("bot")) && "replay_skip".equals(x.get("src"))) rec = x;
            log("RESULT R7 record through Replayer.end: " + (rec == null ? "none" : "reason " + rec.get("reason") + " rsub '" + rec.get("rsub") + "' bug " + rec.get("bug") + " why '" + rec.get("why") + "'") + " (expect why starting {replay:rule-gate/sea-crossing}; reason rule-gate once A's classify reads the hint, unknown before)");
            TestB19C8.onGame(() -> { try { rb[0].clear(); } catch (Throwable ignored) { } });
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        finally { try { if (made && bugs != null) { bugs.delete(); if (restore != null) Files.move(restore.toPath(), bugs.toPath()); } } catch (Throwable ignored) { } log("DONE"); }
    }
    static boolean cappedSeen; static File restore;
}
