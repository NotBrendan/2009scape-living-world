package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;

import java.util.List;

/**
 * batch 19c A4, trial only (-Dtest.b19c4=true -Dsmartbots.count=8 -Dtraders.fast=true -Ddeals.min=1 -Dsmartbots.shiftSec=300):
 * §4.4/§4.5/§4.7. A buyer that never moved is stood up by Deals.meet 3 times -> one deal-stood-up with buyerMoved:false (the bug counts
 * 3); finishShop three times at a real shop for an item it never stocks -> shop-out-of-stock (bug count 3; the 10-minute economy
 * throttle keeps one record per bot) and one shop-loop with walks:3; a bot with 3 gp on a shop walk across the Al Kharid gate ->
 * toll-unaffordable keyed alkharid; a 0 gp bot with no job for 31 min of (simulated) shift time -> one bot-broke; Wallet.pay's
 * failure branch (a remove that returns false: stood in for by the helper the branch calls) -> pay-failed-with-coins whose ctx.site
 * names the caller. Bots only. Prints [TestB19C4] RESULT ... and DONE.
 */
public final class TestB19C4 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C4] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c4")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 30) return false;
                if (Failures.started && Watchdog.Daily.day != null) { new Thread(TestB19C4::run, "TestB19C4").start(); return true; }
                if (t > 600) { log("RESULT Failures never started"); log("DONE"); return true; }
                return false;
            }
        });
    }

    static void run() {
        try { go(); } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        log("DONE");
    }
    static Object cx(JSONObject j, String k) { Object c = j.get("ctx"); return c instanceof JSONObject ? ((JSONObject) c).get(k) : null; }

    static void go() {
        List<SmartAdventurers.Brain> bs = TestB19CUtil.waitBrains(5, 12 * 60_000L);
        if (bs.size() < 4) { log("RESULT not enough bots (" + bs.size() + ")"); return; }
        SmartAdventurers.Brain sb = bs.get(0), bb = bs.get(1), shop = bs.get(2), toll = pick(bs, 3), broke = bs.size() > 4 ? bs.get(4) : bs.get(0);
        // --- deal-stood-up: three offers, the buyer claimed "now" (it has not moved since), each past MEET_MS by meet()
        Player seller = sb.bot, buyer = bb.bot;
        String bugSU = "b-deal-stood-up-adventurer"; int su0 = TestB19CUtil.bugCount(bugSU);
        for (int i = 0; i < 3; i++) {
            TestB19CUtil.game(() -> { Deals.Offer o = new Deals.Offer(seller, 1511, 1, 1); o.buyer = buyer; o.claimedAt = System.currentTimeMillis(); long t = System.currentTimeMillis() + Deals.MEET_MS + 1; Deals.meet(o, t); });
            TestB19CUtil.sleep(1500);
        }
        List<JSONObject> su = TestB19CUtil.recs("deal-stood-up", buyer.getUsername());
        log("RESULT deal-stood-up: records " + su.size() + (su.isEmpty() ? "" : ", buyerMoved " + cx(su.get(0), "buyerMoved") + ", stoodUp1h " + cx(su.get(su.size() - 1), "stoodUp1h")) + ", bug " + bugSU + " count +" + (TestB19CUtil.bugCount(bugSU) - su0) + " (expect 1 record, buyerMoved false; count = records under the throttle)");
        // --- shops: three arrivals at a real shop wanting an item it never stocks
        for (int w = 0; w < 60 && BotChat.WorldData.shops.isEmpty(); w++) TestB19CUtil.sleep(1000); // batch 21 F: the shop list loads after boot
        Object[] real = null; for (Object[] s : new java.util.ArrayList<>(BotChat.WorldData.shops)) { String n = String.valueOf(s[0]); if (!n.toLowerCase().contains("general store")) { real = s; break; } }
        final Object[] rs = real; final Player sp = shop.bot;
        if (rs == null) log("RESULT shop: no shop other than a general store in the list (" + BotChat.WorldData.shops.size() + " shops); section skipped"); // batch 21 F: was a NullPointerException
        else {
        int stockId = 1351; java.util.Set<Integer> st = new java.util.HashSet<>(); for (int id : (int[]) rs[3]) st.add(id);
        int never = 4151; while (st.contains(never)) never++;
        final int nv = never;
        String slug = Failures.slug(String.valueOf(rs[0]));
        int oos0 = TestB19CUtil.bugCount("b-shop-out-of-stock-" + slug);
        TestB19CUtil.setCoins(sp, 500_000);
        for (int i = 0; i < 3; i++) {
            TestB19CUtil.game(() -> { shop.shopName = String.valueOf(rs[0]); shop.shopAt = Location.create((Integer) rs[1], (Integer) rs[2], 0); shop.shopBuy.clear(); shop.shopBuy.add(new int[]{nv, 1}); shop.pendingJob = null; shop.armourTrip = false; shop.goalTrip = false; shop.finishShop(); });
            TestB19CUtil.sleep(1000);
        }
        List<JSONObject> oos = TestB19CUtil.recs("shop-out-of-stock", sp.getUsername()), loop = TestB19CUtil.recs("shop-loop", sp.getUsername());
        log("RESULT shop: " + rs[0] + " (wanted " + nv + "): shop-out-of-stock records " + oos.size() + ", bug count +" + (TestB19CUtil.bugCount("b-shop-out-of-stock-" + slug) - oos0) + " (expect 3 counted, 1 written: §3.2 10-min per bot); shop-loop records " + loop.size() + (loop.isEmpty() ? "" : " walks " + cx(loop.get(0), "walks") + " bug " + loop.get(0).get("bug")) + " (expect 1, walks 3)");
        }
        // --- toll: 3 gp, a shop walk from just west of the Al Kharid gate to a shop east of it
        Object[] ak = null; for (Object[] s : BotChat.WorldData.shops) { int x = (Integer) s[1], y = (Integer) s[2]; if (x > 3272 && x < 3330 && y > 3150 && y < 3230) { ak = s; break; } }
        final Object[] aks = ak; final Player tp = toll.bot;
        if (aks == null) log("RESULT toll: no Al Kharid shop in the list");
        else {
            for (int i = 0; i < 3 && TestB19CUtil.onGame(() -> tp.getLocation().getDistance(Location.create(3265, 3227, 0))) > 5; i++) { TestB19CUtil.game(() -> { if (QuestRunner.active(tp)) try { QuestRunner.ACTIVE.remove(tp.getUsername().toLowerCase()); } catch (Throwable ignored) { } tp.getProperties().setTeleportLocation(Location.create(3265, 3227, 0)); }); TestB19CUtil.sleep(3000); }
            log("toll bot " + tp.getUsername() + " at " + TestB19CUtil.onGame(() -> String.valueOf(tp.getLocation())) + ", quest " + QuestRunner.active(tp));
            TestB19CUtil.setCoins(tp, 3);
            TestB19CUtil.game(() -> { if (toll.runner != null) { try { toll.runner.stop(); } catch (Throwable ignored) { } toll.runner = null; AfkTasks.RUNNING.remove(tp.getUsername().toLowerCase()); } toll.shopName = String.valueOf(aks[0]); toll.shopAt = Location.create((Integer) aks[1], (Integer) aks[2], 0); toll.shopBuy.clear(); toll.shopBuy.add(new int[]{1351, 1}); toll.pendingJob = null; toll.shopDeadline = System.currentTimeMillis() + 180_000; toll.nextThink = 0; });
            List<JSONObject> tr = null; long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 150_000) { TestB19CUtil.sleep(5000); tr = TestB19CUtil.recs("toll-unaffordable", tp.getUsername()); if (!tr.isEmpty()) break; }
            List<JSONObject> gate = TestB19CUtil.recs("rule-gate", tp.getUsername());
            log("RESULT toll-unaffordable: " + (tr == null ? 0 : tr.size()) + " record(s)" + (tr == null || tr.isEmpty() ? "" : ", gate " + cx(tr.get(0), "gate") + ", coins " + cx(tr.get(0), "coins") + ", bug " + tr.get(0).get("bug")) + " (expect 1, b-toll-unaffordable-alkharid); path rule-gate records for the same bot " + gate.size() + "; bot at " + TestB19CUtil.onGame(() -> String.valueOf(tp.getLocation())));
            TestB19CUtil.game(() -> { toll.shopAt = null; toll.shopBuy.clear(); });
        }
        // --- bot-broke: 0 gp, no job for 31 min of shift time (simulated clock through Brain.noJob)
        final Player bp = broke.bot;
        TestB19CUtil.setCoins(bp, 0);
        String br = TestB19CUtil.onGame(() -> {
            long now = System.currentTimeMillis(), end = broke.shiftEnd; broke.shiftEnd = now + 3_600_000L; broke.noJobSince = 0; broke.noJobShift = 0;
            for (int m = 0; m <= 31; m += 3) broke.noJob(now + m * 60_000L);
            broke.noJob(now + 31 * 60_000L);
            broke.shiftEnd = end; return "ok";
        });
        List<JSONObject> bk = TestB19CUtil.recs("bot-broke", bp.getUsername());
        log("RESULT bot-broke: " + bk.size() + " record(s)" + (bk.isEmpty() ? "" : ", coins " + cx(bk.get(0), "coins") + ", sinceMin " + cx(bk.get(0), "sinceMin") + ", bug " + bk.get(0).get("bug")) + " (expect 1, b-bot-broke-broke-adventurer)");
        // --- pay-failed-with-coins: the branch's helper, called the way Wallet.pay calls it
        final Player pp = bs.get(1).bot;
        TestB19CUtil.game(() -> payFromHere(pp));
        List<JSONObject> pf = TestB19CUtil.recs("pay-failed-with-coins", pp.getUsername());
        log("RESULT pay-failed-with-coins: " + pf.size() + " record(s)" + (pf.isEmpty() ? "" : ", site " + cx(pf.get(0), "site") + ", bug " + pf.get(0).get("bug")) + " (expect 1, site TestB19C4.payFromHere)");
        log("RESULT hooks: windows opened " + Failures.winOpened + ", closed at trip end " + Failures.winTripEnd + ", at logout " + Failures.winLogout);
        log("RESULT privacy: " + TestB19CUtil.privacy());
    }
    /** Not a quest bot (its quest walk would win), idle if possible; else bs.get(dflt). */
    static SmartAdventurers.Brain pick(List<SmartAdventurers.Brain> bs, int dflt) { for (int i = 3; i < bs.size(); i++) { SmartAdventurers.Brain x = bs.get(i); if (!QuestRunner.active(x.bot) && x.runner == null) return x; } for (int i = 3; i < bs.size(); i++) if (!QuestRunner.active(bs.get(i).bot)) return bs.get(i); return bs.get(dflt); }
    static void payFromHere(Player p) { Failures.Econ.payFailed(p, 100, Ledger.Wallet.coins(p), "test: bank remove false"); }
}
