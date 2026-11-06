package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

/**
 * Trial only (-Dtest.b18f=true, with -Dtraders.fast=true -Dsurvey.replay=3): batch 18 items 1-4, scenarios and rescue replayers.
 * Seeds (from a non-game thread, through Scenarios.record, by reflection so the class compiles against the base tree too):
 *   FAIL  "seed|other": Rimmington 2957,3214 -> Crandor 2850,3238 (across water, no crossing): expect fail with the stall tile;
 *   GOOD  "seed|afk":   Lumbridge 3222,3218 -> Draynor bank 3092,3245: expect pass.
 * Each seed recorded 5 times (dedupe: one line each). Waits (max 1500 ticks) for both seeds' results, prints them with the lag, re-records
 * GOOD (a re-record after a pass is accepted, back on the to-do list, but not replayed again today), then 4 threads x 50 distinct
 * "captest" rows at once (thread safety) and 400 more (daily cap: 500 non-prove rows today; "captest" is never replayed). BEFORE (base): no Scenarios
 * class, no scenarios file after 200 ticks.
 */
public final class TestB18F implements StartupListener {
    static void log(String s) { System.out.println("[TestB18F] " + s); }
    static final Location F_FROM = Location.create(2957, 3214, 0), F_TO = Location.create(2850, 3238, 0);
    static final Location G_FROM = Location.create(3222, 3218, 0), G_TO = Location.create(3092, 3245, 0);
    static File survey(String n) { return new File(new File(ServerConstants.DATA_PATH, "survey"), n); }
    static String cell(Location l) { return (l.getX() >> 3) + "," + (l.getY() >> 3) + "," + l.getZ(); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b18f")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; Class<?> sc; Method record, toReplay, counts; AIPlayer bot; String failId, goodId; boolean seeded, reported; volatile boolean threadsDone; int waitFrom, capFrom, capWait;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 20) {
                        try { sc = Class.forName("content.custom.Scenarios"); } catch (Throwable e) { sc = null; }
                        log("Scenarios class " + (sc != null ? "present" : "absent") + "; scenarios.jsonl exists " + survey("scenarios.jsonl").exists());
                        if (sc == null) return false;
                        record = sc.getMethod("record", core.game.node.entity.player.Player.class, String.class, String.class, String.class, Location.class, Location.class, Location.class, String.class);
                        toReplay = sc.getMethod("toReplay", String.class); counts = sc.getMethod("counts");
                        bot = TestCR1.bot(null, 40, 20, 20, 20);
                        bot.getSkills().setStaticLevel(Skills.AGILITY, 30); bot.getSkills().setLevel(Skills.AGILITY, 30);
                        failId = "seed|other|" + cell(F_FROM) + "|" + cell(F_TO); goodId = "seed|afk|" + cell(G_FROM) + "|" + cell(G_TO);
                        return false;
                    }
                    if (sc == null) {
                        if (t >= 200) { log("RESULT BEFORE: no Scenarios class, no replayers; scenarios.jsonl exists " + survey("scenarios.jsonl").exists() + ", scenario-results.jsonl exists " + survey("scenario-results.jsonl").exists()); log("DONE"); return true; }
                        return false;
                    }
                    if (t == 30) {
                        Thread th = new Thread(() -> {
                            try {
                                for (int i = 0; i < 5; i++) {
                                    record.invoke(null, bot, "seed", "other", "test: walking to Crandor", F_FROM, F_TO, null, "seeded failing trip (water, no crossing)");
                                    record.invoke(null, bot, "seed", "afk", "test: walking to Draynor bank", G_FROM, G_TO, null, "seeded good trip");
                                }
                                log("records from non-game threads done, no exception");
                            } catch (Throwable e) { log("record threw " + e); }
                            threadsDone = true;
                        }, "TestB18F-recorder");
                        th.start();
                        return false;
                    }
                    if (threadsDone && !seeded) { seeded = true; waitFrom = t; return false; }
                    if (seeded && t == waitFrom + 20) { // flushed by now (5 s timer)
                        List<String> lines = Files.readAllLines(survey("scenarios.jsonl").toPath());
                        int nonProve = 0, f = 0, g = 0, cap = 0;
                        for (String l : lines) { if (!l.contains("\"source\":\"prove\"") && !l.contains("\"source\":\"never_reached\"")) nonProve++; if (l.contains("\"id\":\"" + failId + "\"")) f++; if (l.contains("\"id\":\"" + goodId + "\"")) g++; if (l.contains("captest")) cap++; }
                        log("RESULT dedupe: " + f + " line(s) for the failing seed, " + g + " for the good seed (recorded 5 times each; expect 1 and 1)");
                        log("counts: " + counts.invoke(null));
                        return false;
                    }
                    if (seeded && t > waitFrom + 20 && !reported) {
                        String fr = result(failId), gr = result(goodId);
                        if (t % 200 == 0) log("waiting at tick " + t + ": fail seed " + (fr == null ? "-" : fr) + ", good seed " + (gr == null ? "-" : gr));
                        if (fr != null && gr != null || t > waitFrom + 1500) {
                            reported = true;
                            log("RESULT failing seed: " + fr);
                            log("RESULT good seed: " + gr);
                            log("RESULT toReplay after results: fail " + toReplay.invoke(null, failId) + ", good " + toReplay.invoke(null, goodId) + " (expect false, false); " + counts.invoke(null));
                            record.invoke(null, bot, "seed", "afk", "test: walking to Draynor bank", G_FROM, G_TO, null, "seeded good trip, failed again on live");
                            log("RESULT re-record after the pass: toReplay " + toReplay.invoke(null, goodId) + " (expect false: today's attempt used); " + counts.invoke(null) + " (to-do +1)");
                            threadsDone = false;
                            new Thread(() -> {
                                try {
                                Thread[] ws = new Thread[4];
                                for (int k = 0; k < 4; k++) { final int kk = k; ws[k] = new Thread(() -> { for (int i = 0; i < 50; i++) try { record.invoke(null, null, "captest", "other", "x", Location.create(1000 + kk * 400 + i * 8, 1000, 0), Location.create(1000, 2000, 0), null, "thread"); } catch (Throwable e) { log("thread error " + e); } }); ws[k].start(); }
                                for (Thread w : ws) w.join();
                                for (int i = 0; i < 400; i++) record.invoke(null, null, "captest", "other", "x", Location.create(1000 + i * 8, 4000, 0), Location.create(1000, 5000, 0), null, "cap");
                                    log("cap/thread records from non-game threads done, no exception");
                                } catch (Throwable e) { log("record threw " + e); }
                                threadsDone = true;
                            }, "TestB18F-cap").start();
                            capFrom = t;
                        }
                    }
                    if (reported && capFrom > 0 && threadsDone && ++capWait == 20) {
                        List<String> lines = Files.readAllLines(survey("scenarios.jsonl").toPath());
                        int nonProve = 0, cap = 0;
                        for (String l : lines) { if (!l.contains("\"source\":\"prove\"") && !l.contains("\"source\":\"never_reached\"")) nonProve++; if (l.contains("captest")) cap++; }
                        log("RESULT cap/threads: " + nonProve + " non-prove rows today (3 seed rows + 600 captest attempts; expect 500), captest rows " + cap + " (expect 497), dupes " + sc.getDeclaredField("dupes").get(null) + ", capped " + sc.getDeclaredField("capped").get(null));
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            String result(String id) throws Exception {
                File f = survey("scenario-results.jsonl");
                if (!f.exists()) return null;
                String found = null;
                for (String l : Files.readAllLines(f.toPath())) if (l.contains("\"id\":\"" + id + "\"") && !l.contains("\"result\":\"skipped\"")) found = l;
                return found;
            }
        });
    }
}
