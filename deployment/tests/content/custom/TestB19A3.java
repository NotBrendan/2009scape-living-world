package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * batch 19a, trial only (-Dtest.b19a3=true): A6 Failures.exception. A pulse throws a real NullPointerException 50 times in a minute
 * (one per tick) through its own catch, which prints the exception as the add-on catch sites do and calls
 * Failures.exception("TestB19A3.pulse", bot, t) (Builder B wires the real catch sites; this stands in for them). Expect 1 record
 * (where+class throttle, 5 min), exc.frames[0] naming TestB19A3.npeHere, bug b-exception-TestB19A3.pulse-NullPointerException, the
 * record's bot = the test bot with a snap, the bug count and Daily fail/exception +50; a second site gives a second record; a null
 * bot gives a record with no bot data.
 */
public final class TestB19A3 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19A3] " + s); }

    static void npeHere(Object o) { o.hashCode(); } // throws: o is null

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19a3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, thrown, fids, f2; AIPlayer bot; String fid, fidOther, fidNull; int daily0;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 30) {
                        if (!Failures.started || Watchdog.Daily.day == null) { t = 20; return false; }
                        bot = TestCR1.bot(Location.create(3222, 3218, 0), 30, 20, 20, 20);
                        Map<String, Integer> m = Watchdog.Daily.TODAY.get("fail"); daily0 = m == null ? 0 : m.getOrDefault("exception", 0);
                        log("test bot " + bot.getUsername() + " artificial " + bot.isArtificial());
                        return false;
                    }
                    if (t > 40 && thrown < 50) {
                        try { npeHere(null); }
                        catch (Throwable e) {
                            thrown++;
                            if (thrown == 1 || thrown == 50) System.out.println("[TestB19A3] caught " + e + " (the site's own print, " + thrown + " of 50)");
                            String f = Failures.exception("TestB19A3.pulse", bot, e);
                            if (f != null) { fids++; fid = f; }
                        }
                        return false;
                    }
                    if (thrown == 50 && fidOther == null) {
                        try { npeHere(null); } catch (Throwable e) { fidOther = String.valueOf(Failures.exception("TestB19A3.other", bot, e)); fidNull = String.valueOf(Failures.exception("TestB19A3.nobot", null, e)); }
                        final String a = fid, b = fidOther, c = fidNull; final int n = fids, d0 = daily0; final String name = bot.getUsername();
                        new Thread(() -> check(a, b, c, n, d0, name), "TestB19A3").start();
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }

    static void check(String fid, String fidOther, String fidNull, int fids, int daily0, String botName) {
        try {
            Failures.flushNow(5000);
            File f = new File(Failures.failDir(), Watchdog.Daily.utc(System.currentTimeMillis()) + ".jsonl");
            List<String> lines = Files.readAllLines(f.toPath());
            JSONObject rec = null, other = null, nobot = null; int forSite = 0;
            JSONParser p = new JSONParser();
            for (String l : lines) {
                if (l.contains("\"where\":\"TestB19A3.pulse\"") && fid != null && l.contains("\"fid\":\"" + fid + "\"")) rec = (JSONObject) p.parse(l);
                if (fid != null && l.contains("\"where\":\"TestB19A3.pulse\"")) forSite++;
                if (l.contains("\"fid\":\"" + fidOther + "\"")) other = (JSONObject) p.parse(l);
                if (l.contains("\"fid\":\"" + fidNull + "\"")) nobot = (JSONObject) p.parse(l);
            }
            log("RESULT throttle: 50 thrown, " + fids + " fid(s) returned (expect 1), lines for the site in today's file " + forSite + " (1 per test run)");
            if (rec == null) { log("RESULT record: NOT FOUND (fid " + fid + ")"); }
            else {
                JSONObject exc = (JSONObject) rec.get("exc"); List<?> fr = (List<?>) exc.get("frames");
                log("RESULT record: domain " + rec.get("domain") + ", reason " + rec.get("reason") + ", bug " + rec.get("bug") + ", cls " + exc.get("cls") + ", where " + exc.get("where") + ", frames " + fr.size() + ", frames[0] " + fr.get(0));
                log("RESULT frames[0] names the test method: " + String.valueOf(fr.get(0)).contains("TestB19A3.npeHere"));
                log("RESULT bot: " + rec.get("bot") + " (expect " + botName + "), snap " + (rec.get("snap") != null ? "present, role " + ((JSONObject) rec.get("snap")).get("role") + ", hp " + ((JSONObject) rec.get("snap")).get("hp") : "MISSING") + ", bytes " + lines.stream().filter(l -> l.contains(fid)).findFirst().map(String::length).orElse(-1));
            }
            String id = "b-exception-TestB19A3.pulse-NullPointerException";
            int cnt; String place; synchronized (Failures.Bugs.LOCK) { Failures.Bug b = Failures.Bugs.VIEW.get(id); cnt = b == null ? -1 : b.count; place = b == null ? "" : String.valueOf(b.exc); }
            Map<String, Integer> m = Watchdog.Daily.TODAY.get("fail");
            log("RESULT counters: bug " + id + " count " + cnt + " (expect 50 per run on a fresh file, more on a reused one), exc " + place + "; Daily fail/exception +" + ((m == null ? 0 : m.getOrDefault("exception", 0)) - daily0) + " (expect 52: 50 + the two other sites)");
            log("RESULT second site: " + (other != null ? "recorded, bug " + other.get("bug") : "NOT FOUND (" + fidOther + ")") + "; null bot: " + (nobot != null ? "recorded, bot " + nobot.get("bot") + ", snap " + nobot.get("snap") : "NOT FOUND (" + fidNull + ")"));
            String rep = Failures.report(Watchdog.Daily.utc(System.currentTimeMillis()));
            for (String l : rep.split("\n")) if (l.contains("TestB19A3")) log("report: " + l);
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        log("DONE");
    }
}
