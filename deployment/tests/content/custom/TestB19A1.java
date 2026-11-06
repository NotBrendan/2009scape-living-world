package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * batch 19a, trial only (-Dtest.b19a1=true -Dnav=true -Dsmartbots.count=8 -Dtraders.fast=true -Dfailures.debug=true
 * -Dwatchdog.test=true -Dwatchdog.min=2): A1 traces and the sampler's cost, A2 a forced rescue's failure line.
 * Waits for 8 adventurers, then 3 minutes: every adventurer should have a Trace with >= 10 tiles; the sampler's steady-state (last 100) mean < limit (default 100 us; -Dtest.b19a1.limitUs=300 on cloud).
 * The Watchdog puts one adventurer in the Ardougne unicorn pen; 90 s later (before the Watchdog's 2-minute rescue) this test calls
 * Failures.failure(bot, "rescue", ...) itself, standing in for Builder B's Watchdog.rescue hook (B2), then reads the line from
 * failures/<today>.jsonl: the reason (logged, see below), snap.role adventurer, path >= 5 tiles, near.objs
 * non-empty, <= 4,096 bytes. With -Dfailures=false: no Trace objects and no failures dir after 3 minutes (B7's kill switch).
 * The reason is informational since batch 21: Preflight.stranded (7d606bf) sees the pen as a 49-tile region and pays a walking
 * bot's teleport out on its next step, so at the call the bot is usually elsewhere and the reason is that spot's (unknown,
 * no-route, oscillation, npc-blocking seen on 54cb0c3 and 499065f alike). The why says "in an enclosure" only when the bot is in
 * the pen, as Watchdog.rescue's does. A bot still in the pen is checked: reason blocked-object, door-closed or trap-cell (the
 * pen fence is past the 7x7 scan, so an idle bot there has no fence in near.objs; batch 21 review2 classifies Watchdog's
 * "in an enclosure" as trap-cell instead of unknown) and objs > 0. -Dpreflight.islands=false keeps the bot in the pen.
 */
public final class TestB19A1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19A1] " + s); }

    static final long LIMIT_US = Long.getLong("test.b19a1.limitUs", 100L);

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19a1")) return;
        final boolean dirBefore = Failures.failDir().exists();
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, ready = -1, penAt = -1; boolean rescued, traced, costed; String fid; Player pen;
            @Override public boolean pulse() {
                t++;
                try {
                    if (!Failures.ON) {
                        if (t == 400) { log("RESULT off: Failures.ON false, started " + Failures.started + ", traces " + Failures.TRACES.size() + ", samples " + Failures.samples + ", failures dir existed before " + dirBefore + ", exists now " + Failures.failDir().exists()); log("DONE"); return true; }
                        return false;
                    }
                    if (ready < 0 && (SmartAdventurers.BRAINS.size() >= 8 || t > 1200)) { ready = t; log("adventurers out: " + SmartAdventurers.BRAINS.size() + " at tick " + t); }
                    if (penAt < 0 && Watchdog.testBot != null) {
                        for (Player p : Repository.getPlayers()) if (p != null && Watchdog.testBot.equals(Watchdog.key(p))) pen = p;
                        if (pen != null) { penAt = t; log("pen bot " + pen.getUsername() + " at tick " + t); }
                    }
                    if (penAt > 0 && !rescued && t == penAt + 150) {
                        rescued = true;
                        Location at = pen.getLocation();
                        Watchdog.Goal g = Watchdog.goal(pen);
                        String sid = "rescue|" + g.walker + "|" + (at.getX() >> 3) + "," + (at.getY() >> 3) + "," + at.getZ() + "|" + (g.to != null ? (g.to.getX() >> 3) + "," + (g.to.getY() >> 3) + "," + g.to.getZ() : "none");
                        fid = Failures.failure(pen, "rescue", sid, g, at, "stuck 2 min" + (Watchdog.enclosure(at) ? " in an enclosure" : "") + ", " + g.walker + " " + g.kind + ", planner " + g.navShort + " (TestB19A1 stands in for the rescue hook)");
                        log("failure() at " + at + " in the pen " + Watchdog.enclosure(at) + ": fid " + fid + ", goal " + g.walker + "/" + g.kind + " to " + g.to + ", nav " + g.nav);
                        final String f = fid; final boolean inPen = Watchdog.enclosure(at);
                        new Thread(() -> readBack(f, inPen), "TestB19A1").start();
                    }
                    if (ready > 0 && !traced && t == ready + 300) {
                        traced = true;
                        int ok = 0, n = 0; StringBuilder low = new StringBuilder();
                        for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) {
                            if (br.bot == null || !br.bot.isActive()) continue;
                            n++;
                            Failures.Trace tr = Failures.TRACES.get(Watchdog.key(br.bot));
                            int c = tr == null ? -1 : tr.count;
                            if (c >= 10) ok++; else low.append(br.bot.getUsername()).append('=').append(c).append(' ');
                        }
                        log("RESULT traces: " + ok + "/" + n + " adventurers with >= 10 tiles after 3 min" + (low.length() > 0 ? " (below: " + low.toString().trim() + ")" : "") + "; traced bots " + Failures.TRACES.size());
                    }
                    if (ready > 0 && !costed && t == ready + 1000) { costed = true; // 10 minutes: the code is compiled by then (a 3-minute mean is mostly interpreted code)
                        log("RESULT sampler at 10 min: mean " + Failures.meanUs() + " us, last 100 samples " + Failures.recentUs() + " us, after the first 25 samples " + Failures.warmUs() + " us, NPC checks " + Failures.npcCalls + " at " + (Failures.npcCalls == 0 ? 0 : Failures.npcNs / Failures.npcCalls / 1000) + " us each, max " + Failures.sampleMaxNs / 1000 + " us over " + Failures.samples + " samples (limit: steady-state mean < " + LIMIT_US + " us, set -Dtest.b19a1.limitUs=N; cloud runs are slower, use 300)");
                        log("RESULT sampler verdict: " + (Failures.samples > 0 && Failures.recentUs() < LIMIT_US ? "PASS" : "FAIL") + " (last-100 mean " + Failures.recentUs() + " us vs limit " + LIMIT_US + " us; whole-run mean " + Failures.meanUs() + " us is informational, it includes interpreted warm-up)");
                    }
                    if (ready > 0 && traced && t == ready + 301) log("sampler at 3 min: mean " + Failures.meanUs() + " us, last 100 samples " + Failures.recentUs() + " us");
                    if (traced && costed && (rescued && done || penAt < 0 && t > ready + 1500)) { if (!rescued) log("RESULT rescue: no bot was put in the pen (-Dwatchdog.test missing?)"); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }

    static volatile boolean done;

    static void readBack(String fid, boolean inPen) {
        try {
            if (fid == null) { log("RESULT rescue: failure() returned null (capped or off); Failures.ON " + Failures.ON + ", capped " + Failures.capped + ", throttled " + Failures.throttled + ", offN " + Failures.offN); return; }
            Failures.flushNow(5000);
            File f = new File(Failures.failDir(), Watchdog.Daily.utc(System.currentTimeMillis()) + ".jsonl");
            String line = null;
            for (String l : Files.readAllLines(f.toPath())) if (l.contains("\"fid\":\"" + fid + "\"")) line = l;
            if (line == null) { log("RESULT rescue: line not found for " + fid); return; }
            JSONObject j = (JSONObject) new JSONParser().parse(line);
            JSONObject snap = (JSONObject) j.get("snap"), near = (JSONObject) j.get("near");
            String path = String.valueOf(j.get("path"));
            int tiles = path.isEmpty() ? 0 : path.split("~?>").length;
            List<?> objs = (List<?>) near.get("objs");
            String reason = String.valueOf(j.get("reason"));
            log("RESULT rescue line: reason " + reason + " (" + j.get("rsub") + "), bug " + j.get("bug") + ", src " + j.get("src") + ", snap.role " + snap.get("role") + ", path " + tiles + " tiles, near.objs " + objs.size() + ", npcs " + ((List<?>) near.get("npcs")).size() + ", " + line.length() + " bytes");
            boolean rok = "blocked-object".equals(reason) || "door-closed".equals(reason) || "trap-cell".equals(reason);
            if (inPen) log("RESULT rescue reason (in the pen, a check): " + reason + "; blocked-object/door-closed/trap-cell " + rok + " -> " + (rok ? "PASS" : "FAIL"));
            else log("RESULT rescue reason (informational, not a check): " + reason + " at the bot's new spot (Preflight's stranded-region teleport had taken it out of the pen); blocked-object/door-closed/trap-cell " + rok + ", unknown " + "unknown".equals(reason));
            log("RESULT rescue checks: role adventurer " + "adventurer".equals(snap.get("role")) + ", path>=5 " + (tiles >= 5) + ", objs>0 " + (inPen ? String.valueOf(!objs.isEmpty()) : "n/a (out of the pen)") + ", <=4096 " + (line.length() <= 4096) + (inPen ? ", pen reason " + rok : ""));
            log("line: " + line);
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        finally { done = true; }
    }
}
