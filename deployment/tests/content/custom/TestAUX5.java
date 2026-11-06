package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aux5=true): audit 10-23 X-5. A fake human places "buy 100,000 coal at 1 gp". After a market refresh,
 * how much player demand does MarketBrain count for coal, and is coal on the bounty board? Right: 0 counted.
 */
public final class TestAUX5 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUX5] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aux5")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer h;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        MarketBrain.refresh();
                        log("before the order: coal wants " + MarketBrain.playerWants.getOrDefault(453, 0) + ", bounty weight " + MarketBrain.bounty(453) + ", stock " + MarketBrain.stockOf(453) + ", target " + MarketBrain.target(453));
                        h = TestA1.human(Location.create(3165, 3487, 0), 900901);
                        TestA1.offer(h, 0, 453, 100_000, 1, false);
                    }
                    if (t == 160) {
                        MarketBrain.refresh();
                        log("RESULT after a 1-gp buy order for 100,000 coal: coal wants counted " + MarketBrain.playerWants.getOrDefault(453, 0) + ", bounty weight " + MarketBrain.bounty(453) + " (target " + MarketBrain.target(453) + ")");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
