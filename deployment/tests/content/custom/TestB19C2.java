package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;

import java.util.List;

/**
 * batch 19c A2, trial only (-Dtest.b19c2=true -Dsmartbots.count=6 -Dtraders.fast=true -Dsmartbots.shiftSec=240): the §4.1 coin windows.
 * The real hooks open and close windows (Brain.start, trip end, logOut, restore: counted). Then on a live bot, on the game thread:
 * a window, 3,000 gp taken out of its bank outside the Wallet, the close -> one coin-vanished with unexplained -3000 and no DEATH flag;
 * 20,000 gp put in a pack by inventory.add -> coin-appeared; the Al Kharid toll and food paid through Wallet.pay -> no record; then
 * 200,000 gp added to five bots' banks between two profile saves -> one coin-drift-world whose ctx deltas match. Bots only.
 * Prints [TestB19C2] RESULT ... and DONE.
 */
public final class TestB19C2 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C2] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 30) return false;
                if (Failures.started && Watchdog.Daily.day != null) { new Thread(TestB19C2::run, "TestB19C2").start(); return true; }
                if (t > 600) { log("RESULT Failures never started"); log("DONE"); return true; }
                return false;
            }
        });
    }

    static void run() {
        try { go(); } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        log("DONE");
    }

    static String flags(JSONObject j) { Object c = j.get("ctx"); return c instanceof JSONObject ? String.valueOf(((JSONObject) c).get("flags")) : "?"; }
    static Object cx(JSONObject j, String k) { Object c = j.get("ctx"); return c instanceof JSONObject ? ((JSONObject) c).get(k) : null; }

    /** A window by hand on the game thread: open at the bot's coins, act, close; returns the record count of that code for the bot. */
    static void window(Player p, Runnable act) {
        TestB19CUtil.game(() -> { Failures.tripCoins(p, Ledger.Wallet.coins(p), true, null, "test", -1); act.run(); Failures.tripCoins(p, Ledger.Wallet.coins(p), false, "trip_end", "test", -1); });
    }

    static void go() {
        List<SmartAdventurers.Brain> bs = TestB19CUtil.waitBrains(4, 12 * 60_000L);
        log("adventurers online " + bs.size());
        if (bs.size() < 3) { log("RESULT not enough bots (" + bs.size() + ")"); return; }
        // the real hooks: wait for a trip end or a logout by the code itself
        long t0 = System.currentTimeMillis();
        while (Failures.winTripEnd + Failures.winLogout < 2 && System.currentTimeMillis() - t0 < 10 * 60_000L) TestB19CUtil.sleep(5000);
        log("RESULT hooks: windows opened " + Failures.winOpened + ", closed at trip end " + Failures.winTripEnd + ", at logout " + Failures.winLogout + " (expect all > 0 after " + (System.currentTimeMillis() - t0) / 1000 + " s)");
        bs = TestB19CUtil.brains();
        Player a = bs.get(0).bot, b = bs.get(1).bot, c = bs.get(2).bot;
        // vanish
        TestB19CUtil.setCoins(a, 9_000);
        window(a, () -> { a.getBank().remove(new Item(995, 3_000)); });
        List<JSONObject> v = TestB19CUtil.recs("coin-vanished", a.getUsername());
        JSONObject v0 = v.isEmpty() ? null : v.get(v.size() - 1);
        log("RESULT coin-vanished: " + v.size() + " record(s) for " + a.getUsername() + (v0 == null ? "" : ", unexplained " + cx(v0, "unexplained") + ", start " + cx(v0, "start") + ", flags " + flags(v0) + ", src " + v0.get("src") + ", bug " + v0.get("bug")) + " (expect 1, -3000, no DEATH)");
        // appear
        TestB19CUtil.setCoins(b, 9_000);
        window(b, () -> { if (!b.getInventory().add(new Item(995, 20_000))) b.getBank().add(new Item(995, 20_000)); });
        List<JSONObject> ap = TestB19CUtil.recs("coin-appeared", b.getUsername());
        JSONObject a0 = ap.isEmpty() ? null : ap.get(ap.size() - 1);
        log("RESULT coin-appeared: " + ap.size() + " record(s) for " + b.getUsername() + (a0 == null ? "" : ", unexplained " + cx(a0, "unexplained") + ", flags " + flags(a0) + ", bug " + a0.get("bug")) + " (expect 1, +20000)");
        // toll and food through the Wallet: no record
        TestB19CUtil.setCoins(c, 9_000);
        int cv0 = TestB19CUtil.recs("coin-vanished", c.getUsername()).size(), ca0 = TestB19CUtil.recs("coin-appeared", c.getUsername()).size();
        window(c, () -> { Ledger.Wallet.pay(c, 10); Ledger.Wallet.pay(c, 5); for (int i = 0; i < 10; i++) Ledger.Wallet.pay(c, 120); });
        int cv1 = TestB19CUtil.recs("coin-vanished", c.getUsername()).size(), ca1 = TestB19CUtil.recs("coin-appeared", c.getUsername()).size();
        log("RESULT wallet-paid toll+food: coin records for " + c.getUsername() + " " + (cv1 - cv0 + ca1 - ca0) + " (expect 0), coins now " + TestB19CUtil.coins(c));
        // the world drift: two profile saves with 5 x 200,000 gp added in between
        TestB19CUtil.game(SmartAdventurers::saveProfilesSoon);
        TestB19CUtil.sleep(8_000);
        log("baseline save: " + Failures.Econ.lastDrift + "; wSum " + Failures.Econ.wSum);
        final List<SmartAdventurers.Brain> five = TestB19CUtil.brains();
        int n = TestB19CUtil.onGame(() -> { int k = 0; for (SmartAdventurers.Brain x : five) { if (k >= 5) break; if (x.bot.getBank().add(new Item(995, 200_000))) k++; } return k; });
        int d0 = TestB19CUtil.recs("coin-drift-world", "-").size();
        TestB19CUtil.game(SmartAdventurers::saveProfilesSoon);
        TestB19CUtil.sleep(8_000);
        List<JSONObject> dw = TestB19CUtil.recs("coin-drift-world", "-");
        JSONObject w = dw.isEmpty() ? null : dw.get(dw.size() - 1);
        log("RESULT coin-drift-world: added 200,000 to " + n + " banks; records " + d0 + " -> " + dw.size() + (w == null ? "" : ", dSum " + cx(w, "dSum") + ", dGiven " + cx(w, "dGiven") + ", dPaid " + cx(w, "dPaid") + ", unexplained " + cx(w, "unexplained") + ", bots " + cx(w, "bots") + "/" + cx(w, "botsBefore") + ", bug " + w.get("bug")) + " (expect +1, unexplained ~ " + n * 200_000 + "); last " + Failures.Econ.lastDrift);
        log("RESULT privacy: " + TestB19CUtil.privacy());
    }
}
