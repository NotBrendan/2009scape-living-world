package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.aux16=true): audit 10-23 X-16. Ledger.supply of 10 noted coal (454): under which id is the lot
 * booked (the stock goes in as 453)? Ledger.supply of an item the Exchange refuses: is a lot booked?
 */
public final class TestAUX16 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUX16] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aux16")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t != 150) return false;
                try {
                    int a0 = TestAUX1.lots(453), n0 = TestAUX1.lots(454);
                    Ledger.supply(null, 454, 10);
                    log("RESULT noted coal x10: lots under 453 +" + (TestAUX1.lots(453) - a0) + ", under 454 +" + (TestAUX1.lots(454) - n0) + " (right: under 453)");
                    int bad = -1; for (int id = 1; id < 3000 && bad < 0; id++) { try { core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id); if (d != null && d.getName() != null && !d.getName().equals("null") && !core.game.ge.PriceIndex.canTrade(id)) bad = id; } catch (Throwable ignored) { } }
                    int b0 = TestAUX1.lots(bad);
                    Ledger.supply(null, bad, 5);
                    log("RESULT untradeable " + bad + " (" + MarketBrain.name(bad) + ") x5: lot booked +" + (TestAUX1.lots(bad) - b0) + " (right: 0)");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
