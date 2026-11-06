package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Trial only (-Dtest.b22s10=true -Dsmartbots.count=0 -Dsmartbots.high=0 -Dsmartbots.restoreTries=3 -Dsmartbots.retrySec=0):
 * batch 22 S-10, a profile whose restore or enlist throws is kept (SmartAdventurers.RETRY), saved, retried a bounded number of
 * times, and the half-made bot is discarded. Fault injection through SmartAdventurers.S10_FAULT_* (no other code path sets them).
 * Profile names have no digit tail, so namedBot refuses them and no bot data file is written (a fresh bot, renamed back).
 *   A: end of a break (comeBack) with a throw in restore (after the coins): the profile leaves OFFLINE, is in RETRY (try 1, not
 *      given up) and in buildProfiles with its coins; BOTS and BRAINS do not grow. Then retryRestores (fault used up): it is up,
 *      out of RETRY, BOTS +1, coins exactly as saved (nothing doubled by the discarded bot).
 *   B: restoreSafe with a throw in enlist every time (before BOTS.add, after BRAINS.put): tries 1, 2, 3 fail, the third gives
 *      up; a 4th retry pass does nothing; BRAINS back to its size before; the profile still in buildProfiles (the shutdown save
 *      writes what buildProfiles returns).
 * O6: Surveyors.rotateWeekly (QuestRunner failures.jsonl, batch 22 O-6) on data/b22s10-trial: a file last written 8 days ago
 *      becomes failures-<its week>.jsonl.gz (2 lines), 5 old archives are pruned to 4, a second call does nothing.
 * O4b: Ledger.writeAtomic on data/b22s10-fsync.json: FSYNCS +1 or more, FSYNC_FAILED unchanged, content whole, no .tmp left.
 * Expected log lines: "[S-10] restore of S-ten Alpha failed (comeBack, try 1/3)", "[S-10] S-ten Alpha restored on try 2/3",
 * "[S-10] restore of S-ten Bravo failed" three times, the last with "no more tries this boot". Prints RESULT ..., summary, DONE.
 */
public final class TestB22S10 implements StartupListener {
    static void log(String s) { System.out.println("[TestB22S10] " + s); }
    static int fails;
    static void result(String name, boolean pass, String what) { if (!pass) fails++; log("RESULT " + name + " " + (pass ? "PASS" : "FAIL") + ": " + what); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b22s10")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 60) return false; // SmartAdventurers.startup has loaded the book by now
                new Thread(TestB22S10::run, "TestB22S10").start();
                return true;
            }
        });
    }

    static void run() {
        try { go(); } catch (Throwable e) { fails++; log("error " + e); e.printStackTrace(System.out); }
        finally { SmartAdventurers.S10_FAULT_NAME = null; SmartAdventurers.S10_FAULT_LEFT = 0; }
        log("summary: " + (fails == 0 ? "PASS" : fails + " FAIL") + " (restoreTries " + SmartAdventurers.RESTORE_TRIES + ", retrySec " + SmartAdventurers.RETRY_MS / 1000 + ")");
        log("DONE");
    }

    static SmartAdventurers.Profile profile(String name, long coins) {
        SmartAdventurers.Profile p = new SmartAdventurers.Profile();
        p.name = name; p.coins = coins; p.home = 0; p.fav = -1;
        for (int i = 0; i < 24; i++) p.levels[i] = 1;
        p.levels[0] = p.levels[1] = p.levels[2] = 20; p.levels[3] = 20; // attack, defence, strength, hitpoints
        p.gear.add(1277); // a bronze sword: worn, nothing bought, so the coins stay exactly as saved
        return p;
    }
    static SmartAdventurers.Retry retryOf(SmartAdventurers.Profile p) { for (SmartAdventurers.Retry r : SmartAdventurers.RETRY) if (r.p == p) return r; return null; }
    static Long savedCoins(String name) {
        JSONArray arr = TestB19CUtil.onGame(SmartAdventurers::buildProfiles);
        if (arr == null) return null;
        for (Object o : arr) if (o instanceof JSONObject && name.equalsIgnoreCase(String.valueOf(((JSONObject) o).get("name")))) { Object c = ((JSONObject) o).get("coins"); return c instanceof Number ? ((Number) c).longValue() : -1L; }
        return null;
    }
    static void fault(String at, String name, int times) { SmartAdventurers.S10_FAULT_AT = at; SmartAdventurers.S10_FAULT_LEFT = times; SmartAdventurers.S10_FAULT_NAME = name; }

    static void go() {
        // ---- A: comeBack, one throw in restore, then a retry that works
        SmartAdventurers.Profile a = profile("S-ten Alpha", 1_234);
        SmartAdventurers.OFFLINE.add(a);
        int bots0 = SmartAdventurers.BOTS.size(), brains0 = SmartAdventurers.BRAINS.size(), f0 = SmartAdventurers.s10Failed, r0 = SmartAdventurers.s10Recovered;
        fault("restore", a.name, 1);
        TestB19CUtil.game(() -> SmartAdventurers.comeBack(a));
        SmartAdventurers.Retry ra = retryOf(a); Long sa = savedCoins(a.name);
        result("A1", !SmartAdventurers.OFFLINE.contains(a) && ra != null && ra.attempts == 1 && !ra.gaveUp && sa != null && sa == 1_234
                        && SmartAdventurers.BOTS.size() == bots0 && SmartAdventurers.BRAINS.size() == brains0 && SmartAdventurers.s10Failed == f0 + 1,
                "in OFFLINE " + SmartAdventurers.OFFLINE.contains(a) + ", retry " + (ra == null ? "none" : "try " + ra.attempts + " gaveUp " + ra.gaveUp) + ", saved coins " + sa
                        + " (expect 1234), BOTS " + bots0 + " -> " + SmartAdventurers.BOTS.size() + ", BRAINS " + brains0 + " -> " + SmartAdventurers.BRAINS.size() + ", failed +" + (SmartAdventurers.s10Failed - f0));
        TestB19CUtil.game(() -> SmartAdventurers.retryRestores(System.currentTimeMillis() + 1_000));
        Player up = SmartAdventurers.BOTS.size() > bots0 ? SmartAdventurers.BOTS.get(SmartAdventurers.BOTS.size() - 1) : null;
        long coins = up == null ? -1 : TestB19CUtil.coins(up);
        result("A2", retryOf(a) == null && up != null && coins == 1_234 && SmartAdventurers.s10Recovered == r0 + 1,
                "retry left " + (retryOf(a) != null) + ", BOTS " + bots0 + " -> " + SmartAdventurers.BOTS.size() + ", up as " + (up == null ? "-" : up.getUsername()) + " with " + coins + " gp (expect 1234), recovered +" + (SmartAdventurers.s10Recovered - r0));

        // ---- B: a throw in enlist every time: bounded tries, kept in the book
        SmartAdventurers.Profile b = profile("S-ten Bravo", 777);
        int bots1 = SmartAdventurers.BOTS.size(), brains1 = SmartAdventurers.BRAINS.size(), f1 = SmartAdventurers.s10Failed, g1 = SmartAdventurers.s10GaveUp;
        fault("enlist", b.name, 99);
        Boolean first = TestB19CUtil.onGame(() -> SmartAdventurers.restoreSafe(b, SmartAdventurers.LUMBRIDGE, "test", 0));
        for (int i = 0; i < 3; i++) TestB19CUtil.game(() -> SmartAdventurers.retryRestores(System.currentTimeMillis() + 1_000)); // tries 2 and 3, then nothing
        SmartAdventurers.Retry rb = retryOf(b); Long sb = savedCoins(b.name);
        result("B1", Boolean.FALSE.equals(first) && rb != null && rb.attempts == 3 && rb.gaveUp && SmartAdventurers.s10Failed == f1 + 3 && SmartAdventurers.s10GaveUp == g1 + 1,
                "first try " + first + ", retry " + (rb == null ? "none" : "try " + rb.attempts + " gaveUp " + rb.gaveUp) + ", failed +" + (SmartAdventurers.s10Failed - f1) + " (expect 3), gave up +" + (SmartAdventurers.s10GaveUp - g1));
        result("B2", sb != null && sb == 777 && SmartAdventurers.BOTS.size() == bots1 && SmartAdventurers.BRAINS.size() == brains1,
                "saved coins " + sb + " (expect 777: the shutdown save keeps it), BOTS " + bots1 + " -> " + SmartAdventurers.BOTS.size() + ", BRAINS " + brains1 + " -> " + SmartAdventurers.BRAINS.size() + " (half-made bots discarded)");
        SmartAdventurers.RETRY.removeIf(r -> r.p == a || r.p == b); // the trial's own profiles out of later saves
        rotation();
        fsync();
    }

    /** O-6: Surveyors.rotateWeekly on a scratch folder (as QuestRunner's failures.jsonl uses it), on the SurveyWriter thread. */
    static void rotation() {
        java.io.File dir = new java.io.File(core.ServerConstants.DATA_PATH, "b22s10-trial");
        try {
            dir.mkdirs();
            java.io.File[] old = dir.listFiles(); if (old != null) for (java.io.File f : old) f.delete();
            for (int w = 1; w <= 5; w++) java.nio.file.Files.write(new java.io.File(dir, "failures-2000-W0" + w + ".jsonl.gz").toPath(), new byte[0]); // five old archives
            java.io.File f = new java.io.File(dir, "failures.jsonl");
            java.nio.file.Files.write(f.toPath(), "{\"trial\":1}\n{\"trial\":2}\n".getBytes());
            long lastWeek = System.currentTimeMillis() - 8L * 24 * 3_600_000L;
            f.setLastModified(lastWeek);
            String week = Surveyors.isoWeek(lastWeek);
            final boolean[] rotated = new boolean[1];
            SurveyWriter.task(() -> rotated[0] = Surveyors.rotateWeekly(dir, "failures.jsonl", "[TestB22S10]"));
            SurveyWriter.flushNow(5_000);
            java.io.File gz = new java.io.File(dir, "failures-" + week + ".jsonl.gz");
            int lines = 0;
            if (gz.exists()) try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(gz))))) { while (r.readLine() != null) lines++; }
            java.io.File[] arch = Surveyors.weeklyArchives(dir, "failures.jsonl");
            boolean again = Surveyors.rotateWeekly(dir, "failures.jsonl", "[TestB22S10]"); // nothing left to rotate this week
            result("O6", rotated[0] && !f.exists() && !new java.io.File(dir, "failures.jsonl.rotating").exists() && lines == 2 && arch.length == Surveyors.KEEP_ARCHIVES
                            && arch[arch.length - 1].getName().equals(gz.getName()) && !again,
                    "rotated " + rotated[0] + ", failures.jsonl gone " + !f.exists() + ", " + gz.getName() + " lines " + lines + " (expect 2), archives " + arch.length + " (expect " + Surveyors.KEEP_ARCHIVES + ", newest last), second call rotated " + again);
        } catch (Throwable t) { result("O6", false, "error " + t); }
        finally { java.io.File[] fs = dir.listFiles(); if (fs != null) for (java.io.File x : fs) x.delete(); dir.delete(); }
    }

    /** O-4b: writeAtomic fsyncs the temp file (FSYNCS +1, no failure) and the content lands whole. */
    static void fsync() {
        java.io.File f = new java.io.File(core.ServerConstants.DATA_PATH, "b22s10-fsync.json");
        try {
            long s0 = Ledger.FSYNCS.get(), x0 = Ledger.FSYNC_FAILED.get();
            Ledger.writeAtomic(f, "{\"trial\":\"o4b\"}");
            String back = new String(java.nio.file.Files.readAllBytes(f.toPath()));
            result("O4b", Ledger.FSYNCS.get() >= s0 + 1 && Ledger.FSYNC_FAILED.get() == x0 && back.equals("{\"trial\":\"o4b\"}") && !new java.io.File(f.getPath() + ".tmp").exists(),
                    "fsyncs +" + (Ledger.FSYNCS.get() - s0) + " (expect 1, others' saves may add more on a busy world), failed +" + (Ledger.FSYNC_FAILED.get() - x0) + ", content back " + back.equals("{\"trial\":\"o4b\"}"));
        } catch (Throwable t) { result("O4b", false, "error " + t); }
        finally { f.delete(); }
    }
}
