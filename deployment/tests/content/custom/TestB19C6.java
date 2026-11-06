package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;

import java.io.File;
import java.util.List;

/**
 * batch 19c A6, trial only (-Dtest.b19c6=true -Dsmartbots.count=4 -Dtraders.fast=true -Dsmartbots.shiftSec=120 -Dsmartbots.awaySec=60
 * -Dfailures.overdueSec=60): §8.1/§10/§8.2. A profile whose restore throws (levels null) through the end-of-break path -> login-failed;
 * a profile left on the break list 70 s past backAt -> return-overdue; a bot logged out while shopAt != null -> logout-mid-task
 * task:"shop"; a corrupt book (a fixture file, not smartbots.json) -> file-quarantined, the sanity check against a sidecar that says
 * more -> sanity-failed, a write over the blocked file twice -> one save-refused; Wallet.pay from a Timer thread -> one thread-violation
 * keyed op-at-site; no line names an online human or has an '@'. Prints [TestB19C6] RESULT ... and DONE.
 */
public final class TestB19C6 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C6] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c6")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 30) return false;
                if (Failures.started && Watchdog.Daily.day != null) { new Thread(TestB19C6::run, "TestB19C6").start(); return true; }
                if (t > 600) { log("RESULT Failures never started"); log("DONE"); return true; }
                return false;
            }
        });
    }
    static void run() { try { go(); } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); } log("DONE"); }
    static Object cx(JSONObject j, String k) { Object c = j.get("ctx"); return c instanceof JSONObject ? ((JSONObject) c).get(k) : null; }

    static void go() throws Exception {
        List<SmartAdventurers.Brain> bs = TestB19CUtil.waitBrains(2, 10 * 60_000L);
        if (bs.isEmpty()) { log("RESULT no bots"); return; }
        // --- login-failed
        SmartAdventurers.Profile bad = new SmartAdventurers.Profile(); bad.name = "Bnineteenc"; bad.levels = null;
        int lf0 = TestB19CUtil.recs("login-failed", "-").size();
        TestB19CUtil.game(() -> SmartAdventurers.comeBack(bad));
        List<JSONObject> lf = TestB19CUtil.recs("login-failed", "-");
        JSONObject l = lf.size() > lf0 ? lf.get(lf.size() - 1) : null;
        log("RESULT login-failed: +" + (lf.size() - lf0) + (l == null ? "" : ", exc " + cx(l, "exc") + ", profile " + cx(l, "profile") + ", bug " + l.get("bug")) + " (expect +1, b-login-failed-adventurer-nullpointerexception)");
        // --- return-overdue
        SmartAdventurers.Profile late = new SmartAdventurers.Profile(); late.name = "Bnineteenl"; late.backAt = System.currentTimeMillis() - 70_000L;
        SmartAdventurers.OFFLINE.add(late);
        int ro0 = TestB19CUtil.recs("return-overdue", "-").size();
        TestB19CUtil.game(() -> SmartAdventurers.overdue(System.currentTimeMillis()));
        SmartAdventurers.OFFLINE.remove(late);
        List<JSONObject> ro = TestB19CUtil.recs("return-overdue", "-");
        log("RESULT return-overdue: +" + (ro.size() - ro0) + (ro.size() > ro0 ? ", lateMin " + cx(ro.get(ro.size() - 1), "lateMin") + ", bug " + ro.get(ro.size() - 1).get("bug") : "") + " (expect +1, overdueSec " + SmartAdventurers.OVERDUE_MS / 1000 + ")");
        // --- logout-mid-task
        SmartAdventurers.Brain sb = bs.get(0); Player sp = sb.bot;
        TestB19CUtil.game(() -> {
            if (sb.runner != null) { try { sb.runner.stop(); } catch (Throwable ignored) { } sb.runner = null; AfkTasks.RUNNING.remove(sp.getUsername().toLowerCase()); }
            sb.shopAt = Location.create(3210, 3415, 0); sb.shopName = "test shop";
            SmartAdventurers.logOut((AIPlayer) sp, sb);
        });
        List<JSONObject> lm = TestB19CUtil.recs("logout-mid-task", sp.getUsername());
        log("RESULT logout-mid-task: " + lm.size() + (lm.isEmpty() ? "" : ", task " + cx(lm.get(0), "task") + ", bug " + lm.get(0).get("bug")) + " (expect 1, shop)");
        // --- the save domain on a fixture book
        File book = new File(ServerConstants.DATA_PATH, "b19c6-fixture-smartbots.json");
        java.nio.file.Files.write(book.toPath(), "[{\"name\":\"x\", broken".getBytes("UTF-8"));
        int fq0 = TestB19CUtil.recs("file-quarantined", "-").size(), sf0 = TestB19CUtil.recs("sanity-failed", "-").size(), sr0 = TestB19CUtil.recs("save-refused", "-").size();
        try { new org.json.simple.parser.JSONParser().parse(new java.io.FileReader(book)); } catch (Throwable t) { Ledger.quarantine(book, "SmartAdv", t, false, true); } // the loader's own catch
        TestB19CUtil.game(SmartAdventurers::saveProfilesSoon); TestB19CUtil.sleep(6000); // a real sidecar (count and coins of the live book)
        File sc = SmartAdventurers.lastSaveFile();
        String side = sc.exists() ? new String(java.nio.file.Files.readAllBytes(sc.toPath()), "UTF-8") : "none";
        try { SmartAdventurers.sanityCheck(book, 0, 0); } catch (Throwable t) { log("sanity threw " + t); }
        Ledger.writeAtomic(book, "[]"); Ledger.writeAtomic(book, "[]");
        List<JSONObject> fq = TestB19CUtil.recs("file-quarantined", "-"), sf = TestB19CUtil.recs("sanity-failed", "-"), sr = TestB19CUtil.recs("save-refused", "-");
        log("RESULT save domain: file-quarantined +" + (fq.size() - fq0) + (fq.size() > fq0 ? " (" + cx(fq.get(fq.size() - 1), "file") + ", blocked " + cx(fq.get(fq.size() - 1), "blocked") + ")" : "") + ", sanity-failed +" + (sf.size() - sf0) + (sf.size() > sf0 ? " (lastCount " + cx(sf.get(sf.size() - 1), "lastCount") + ")" : "") + ", save-refused +" + (sr.size() - sr0) + " (expect +2 [the load and the sanity check each quarantine], +1, +1); sidecar " + side);
        Ledger.loadedClean(book);
        File[] left = book.getParentFile().listFiles((d, n) -> n.startsWith("b19c6-fixture-smartbots.json")); if (left != null) for (File f : left) f.delete();
        // --- thread-violation
        Player tp = bs.size() > 1 ? bs.get(1).bot : bs.get(0).bot;
        int tv0 = TestB19CUtil.recs("thread-violation", "-").size();
        java.util.Timer timer = new java.util.Timer("TestB19C6-timer", true); final boolean[] done = {false};
        timer.schedule(new java.util.TimerTask() { public void run() { Ledger.Wallet.pay(tp, 1); done[0] = true; } }, 0);
        for (int i = 0; i < 50 && !done[0]; i++) TestB19CUtil.sleep(100);
        timer.cancel();
        List<JSONObject> tv = TestB19CUtil.recs("thread-violation", "-");
        JSONObject v = tv.size() > tv0 ? tv.get(tv.size() - 1) : null;
        log("RESULT thread-violation: +" + (tv.size() - tv0) + (v == null ? "" : ", op " + cx(v, "op") + ", thread " + cx(v, "thread") + ", site " + cx(v, "site") + ", bug " + v.get("bug")) + " (expect +1, key op-at-site)");
        log("RESULT privacy: " + TestB19CUtil.privacy());
    }
}
