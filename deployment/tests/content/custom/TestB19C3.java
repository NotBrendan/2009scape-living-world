package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * batch 19c A3, trial only (-Dtest.b19c3=true -Dtraders.fast=true -Ddeals.min=1): §4.2/§4.3/§4.6. A reservation the GE worker never
 * applies (the test puts one in RESERVED_TAKES with an old oldestReservedT, standing in for a stubbed takeApplied) -> ge-take-stalled at
 * the next check, then a "pass" self-clear on the bug after it is removed and 3 checks pass; Ledger.supply of an untradeable id ->
 * ge-supply-refused; one price base edited to 10x -> one price-outlier for that item (others counted, they are the seed's own); a
 * BotSwap into a full pack -> botswap-failed with both items still with their owners. Prints [TestB19C3] RESULT ... and DONE.
 */
public final class TestB19C3 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C3] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 30) return false;
                if (Failures.started && Watchdog.Daily.day != null) { new Thread(TestB19C3::run, "TestB19C3").start(); return true; }
                if (t > 600) { log("RESULT Failures never started"); log("DONE"); return true; }
                return false;
            }
        });
    }

    static void run() {
        try { takes(); supply(); price(); swap(); } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        log("DONE");
    }

    static Object cx(JSONObject j, String k) { Object c = j.get("ctx"); return c instanceof JSONObject ? ((JSONObject) c).get(k) : null; }

    static void takes() {
        int fake = 4151;
        synchronized (MarketBrain.TAKE_LOCK) { MarketBrain.RESERVED_TAKES.merge(fake, 1, Integer::sum); MarketBrain.oldestReservedT = System.currentTimeMillis() - 200_000L; }
        MarketBrain.geChecks(System.currentTimeMillis());
        List<JSONObject> r = TestB19CUtil.recs("ge-take-stalled", "-");
        log("RESULT ge-take-stalled: " + r.size() + " record(s)" + (r.isEmpty() ? "" : ", oldestS " + cx(r.get(r.size() - 1), "oldestS") + ", bug " + r.get(r.size() - 1).get("bug")) + " (expect 1, >= 200)");
        synchronized (MarketBrain.TAKE_LOCK) { MarketBrain.RESERVED_TAKES.computeIfPresent(fake, (k, v) -> v - 1 > 0 ? v - 1 : null); if (MarketBrain.RESERVED_TAKES.isEmpty()) MarketBrain.oldestReservedT = 0; }
        for (int i = 0; i < 3; i++) MarketBrain.geChecks(System.currentTimeMillis());
        Failures.flushNow(5000);
        String st; int passes = 0;
        synchronized (Failures.Bugs.LOCK) { Failures.Bug b = Failures.Bugs.VIEW.get(MarketBrain.B_TAKES); st = b == null ? "missing" : b.status; if (b != null) for (Map<String, Object> x : b.replays) if ("pass".equals(x.get("result"))) passes++; }
        log("RESULT self-clear: bug " + MarketBrain.B_TAKES + " status " + st + ", pass replays " + passes + " (expect >= 1 after 3 clean checks), reserved now " + MarketBrain.RESERVED_TAKES.size());
    }

    static void supply() {
        int id = -1;
        for (int i = 1; i < 12000 && id < 0; i++) { try { if (!core.game.ge.PriceIndex.canTrade(i) && core.cache.def.impl.ItemDefinition.forId(i) != null && core.cache.def.impl.ItemDefinition.forId(i).getName() != null && !"null".equals(core.cache.def.impl.ItemDefinition.forId(i).getName())) id = i; } catch (Throwable ignored) { } }
        final int u = id;
        TestB19CUtil.game(() -> Ledger.supply(null, u, 1));
        List<JSONObject> r = TestB19CUtil.recs("ge-supply-refused", "-");
        JSONObject j = null; for (JSONObject x : r) if (String.valueOf(u).equals(String.valueOf(cx(x, "item")))) j = x;
        log("RESULT ge-supply-refused: untradeable id " + u + " -> " + (j == null ? "NO record" : "record, supplier " + cx(j, "supplier") + ", bug " + j.get("bug")));
    }

    static void price() {
        PriceDrift.outliers("test-baseline"); // the seed's own outliers, once a day each
        int before = TestB19CUtil.recs("price-outlier", "-").size();
        int id = -1; Integer old = null;
        for (int c : MarketBrain.CATALOGUE) { if (!MarketBrain.tradeable(c) || MarketBrain.value(c) < 20) continue; Integer b = PriceDrift.BASE.get(c); if (b == null) continue; boolean seen = false; for (JSONObject x : TestB19CUtil.recs("price-outlier", "-")) if (String.valueOf(c).equals(String.valueOf(cx(x, "item")))) seen = true; if (seen) continue; id = c; old = b; break; }
        if (id < 0) { log("RESULT price-outlier: no catalogue item with a base"); return; }
        PriceDrift.BASE.put(id, old * 10);
        try { PriceDrift.outliers("test"); } finally { PriceDrift.BASE.put(id, old); }
        List<JSONObject> all = TestB19CUtil.recs("price-outlier", "-");
        int mine = 0, other = 0; for (int i = before; i < all.size(); i++) { if (String.valueOf(id).equals(String.valueOf(cx(all.get(i), "item")))) mine++; else other++; }
        log("RESULT price-outlier: base of " + id + " " + old + " -> " + old * 10 + " (value " + MarketBrain.value(id) + "): records for it " + mine + " (expect 1), for others after the edit " + other + " (expect 0); the seed's own before it " + before);
    }

    static void swap() {
        List<SmartAdventurers.Brain> bs = TestB19CUtil.waitBrains(2, 8 * 60_000L);
        if (bs.size() < 2) { log("RESULT botswap: not enough bots"); return; }
        Player a = bs.get(0).bot, b = bs.get(1).bot;
        final SmartAdventurers.Brain ba = bs.get(0), bb = bs.get(1); final long na = ba.nextThink, nb = bb.nextThink;
        String r = TestB19CUtil.onGame(() -> { // batch 21 F: fixed, distinct tiles and both brains paused (they used to walk off during the wait)
            ba.nextThink = Long.MAX_VALUE; bb.nextThink = Long.MAX_VALUE;
            for (Player p : new Player[]{a, b}) { try { p.getPulseManager().clear(); p.getWalkingQueue().reset(); } catch (Throwable ignored) { } }
            a.getProperties().setTeleportLocation(core.game.world.map.Location.create(3222, 3219, 0));
            b.getProperties().setTeleportLocation(core.game.world.map.Location.create(3223, 3219, 0));
            return "ok";
        });
        TestB19CUtil.sleep(3000);
        String res = TestB19CUtil.onGame(() -> {
            if (a.getInventory().getAmount(1351) == 0 && !a.getInventory().add(new Item(1351))) return "a has no room for the axe";
            int added = 0; while (b.getInventory().freeSlots() > 0 && added < 28) { if (!b.getInventory().add(new Item(1511))) break; added++; }
            int aAxe = a.getInventory().getAmount(1351), bLogs = b.getInventory().getAmount(1511);
            boolean ok = BotSwap.swap(a, 1351, b, 0, "test swap");
            String out = "swap returned " + ok + ", a axe " + aAxe + " -> " + a.getInventory().getAmount(1351) + ", b logs " + bLogs + " -> " + b.getInventory().getAmount(1511) + ", b free " + b.getInventory().freeSlots() + ", dist " + (int) a.getLocation().getDistance(b.getLocation());
            if (added > 0) b.getInventory().remove(new Item(1511, added));
            ba.nextThink = na; bb.nextThink = nb; // batch 21 F: brains back on
            return out;
        });
        List<JSONObject> rec = TestB19CUtil.recs("botswap-failed", a.getUsername());
        log("RESULT botswap-failed: " + res + "; records " + rec.size() + (rec.isEmpty() ? "" : ", ctx b " + cx(rec.get(0), "b") + ", bug " + rec.get(0).get("bug")) + " (expect false, both kept, 1 record); item-lost " + TestB19CUtil.recs("item-lost-on-handover", null).size());
    }
}
