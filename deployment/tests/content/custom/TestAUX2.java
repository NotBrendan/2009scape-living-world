package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aux2=true): audit 10-23 X-2. A pile of exactly 100. The GE worker is kept busy (1,500 one-unit
 * supplies of another item queued first), a fake human's buy of 100 is queued behind them, and in the same tick a bot
 * take of 100 is reserved and queued after it. Right: 100 units leave in total (the bot's take; the player's buy stays open).
 */
public final class TestAUX2 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUX2] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aux2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, c, d, took, waitT; long short0; AIPlayer h;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 200) {
                        c = TestAUX1.pick(-1, 1617, 1619, 1621, 1623, 2357); d = TestAUX1.pick(c, 1925, 1931, 1935, 1937);
                        TestAUX1.setRow(c, 100); TestAUX1.setRow(d, 0);
                        h = TestA1.human(Location.create(3166, 3487, 0), 900401);
                        for (int i = 0; i < 1500; i++) GrandExchange.Companion.getPendingOffers().addLast(GrandExchangeOffer.Companion.createBotOffer(d, 1, true));
                        short0 = MarketBrain.takeShortfall;
                        TestA1.offer(h, 0, c, 100, GrandExchange.Companion.getRecommendedPrice(c, true) + 10, false);
                        took = MarketBrain.takeFromStock(c, 100);
                        log("pile 100 " + MarketBrain.name(c) + "; queue length " + GrandExchange.Companion.getPendingOffers().size() + "; bot take reserved " + took);
                    }
                    if (t > 200 && waitT == 0 && GrandExchange.Companion.getPendingOffers().isEmpty()) waitT = t;
                    if (waitT > 0 && t == waitT + 10) {
                        int player = TestA1.done(900401, 0);
                        log("RESULT worker done after " + (waitT - 200) + " ticks: player got " + player + ", bot take got " + took + ", row now " + TestAUX1.row(c) + ", shortfall " + (MarketBrain.takeShortfall - short0) + "; units out of a pile of 100: " + (player + took));
                        log("DONE");
                        return true;
                    }
                    if (t > 1200) { log("timeout"); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
