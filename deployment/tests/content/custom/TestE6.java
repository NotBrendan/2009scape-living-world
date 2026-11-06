package content.custom;

import core.api.StartupListener;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/** Trial only (-Dtest.e6=true): bot stock never goes below zero, and reservations are released when the worker applies a take. */
public final class TestE6 implements StartupListener {
    static void log(String s) { System.out.println("[TestE6] " + s); }
    static int stock() { return GrandExchange.Companion.getBotstockForId(1515); } // yew logs
    @Override public void startup() {
        if (!Boolean.getBoolean("test.e6")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, s0;
            @Override public boolean pulse() {
                t++;
                if (t == 60) { s0 = stock(); log("yew stock " + s0 + "; queueing a raw take of " + (s0 + 25) + " (more than exists)"); GrandExchange.Companion.getPendingOffers().addLast(GrandExchangeOffer.Companion.createBotOffer(1515, -(s0 + 25), true)); }
                if (t == 65) { log("after raw take: stock " + stock() + " (expect 0, never negative)"); Ledger.supply(null, 1515, 40); }
                if (t == 70) { int a = MarketBrain.takeFromStock(1515, 30); int b = MarketBrain.takeFromStock(1515, 30); log("stock 40: took " + a + " then " + b + " (expect 30 then 10); reserved now " + MarketBrain.RESERVED_TAKES.get(1515)); }
                if (t == 75) { log("RESULT stock " + stock() + " (expect 0), reserved " + MarketBrain.RESERVED_TAKES.get(1515) + " (expect null: released by the worker)"); return true; }
                return false;
            }
        });
    }
}
