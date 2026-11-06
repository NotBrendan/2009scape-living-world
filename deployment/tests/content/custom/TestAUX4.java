package content.custom;

import core.api.StartupListener;
import core.game.ge.GrandExchange;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.aux4=true): audit 10-23 X-4. An item's guide is pushed to 50% of its base price (as alts trading at
 * 1 gp would). What does bot stock sell it for (getRecommendedPrice(id, true)), and what does the bot buyer pay at most
 * (90% of botRef)? Right: bot stock never under what the buyer pays.
 */
public final class TestAUX4 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUX4] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aux4")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t != 150) return false;
                try {
                    int id = TestAUX1.pick(-1, 453, 440, 1513, 1515, 1333);
                    int base = MarketBrain.value(id);
                    PriceDrift.BASE.put(id, base);
                    TestAUX1.sql("UPDATE price_index SET value = " + (base / 2) + " WHERE item_id = " + id);
                    MarketBrain.priceCacheClear();
                    int sell = GrandExchange.Companion.getRecommendedPrice(id, true), ref = MarketBrain.botRef(id), buyer = ref * 90 / 100;
                    log("drift loaded " + PriceDrift.LOADED + "; " + MarketBrain.name(id) + " base " + base + ", guide now " + MarketBrain.value(id));
                    log("RESULT bot stock sells at " + sell + " (" + (100 * sell / Math.max(1, base)) + "% of base); the bot buyer pays up to " + buyer + " (" + (100 * buyer / Math.max(1, base)) + "%); a loop buy-from-bots, sell-to-bots " + (buyer > sell ? "PAYS " + (100 * (buyer - sell) / Math.max(1, sell)) + "% a round" : "does not pay"));
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
