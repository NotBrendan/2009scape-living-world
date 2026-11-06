package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.pra4=true): pathing review 10-23 A-4. Two non-walker bots in Taverley dungeon (2884,9800), 100k coins
 * each. Bot A flees "low on health with no food left"; bot B is resting (RECOVER) with no food on it and trout in the
 * bank. Each pays for a teleport out; then goTo(RETREAT) runs. Teleports paid = coins spent / one teleport's price (or law
 * runes taken from stock), and where each landed.
 */
public final class TestPRA4 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRA4] " + s); }
    static long price() { return Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556)); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.pra4")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, b; AfkTasks.Runner ra, rb; long ca, cb; int law0;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 80) {
                        a = TestCR1.bot(Location.create(2884, 9800, 0), 60, 20, 20, 20); a.getBank().add(new Item(995, 100_000));
                        b = TestCR1.bot(Location.create(2885, 9801, 0), 60, 20, 20, 20); b.getBank().add(new Item(995, 100_000)); b.getBank().add(new Item(333, 10));
                        return false;
                    }
                    if (t == 84) {
                        law0 = MarketBrain.stockOf(563);
                        ra = new AfkTasks.Runner(a, AfkTasks.Task.COW, true, 0, false, false); ra.walker = false;
                        rb = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false); rb.walker = false;
                        ca = Ledger.Wallet.coins(a); cb = Ledger.Wallet.coins(b);
                        log("one teleport costs about " + price() + " gp; law runes in stock " + law0);
                        a.getSkills().setLifepoints(8);
                        ra.flee("low on health with no food left");
                        int law1 = MarketBrain.stockOf(563);
                        log("A (flee) at " + a.getLocation() + ": coins spent " + (ca - Ledger.Wallet.coins(a)) + " (" + String.format("%.1f", (ca - Ledger.Wallet.coins(a)) / (double) price()) + " teleports' worth), law runes from stock " + (law0 - law1) + ", landing " + a.getProperties().getTeleportLocation() + ", emergencies " + ra.emergencies);
                        rb.state = AfkTasks.State.RECOVER;
                        rb.recover();
                        log("B (recover, food trip) at " + b.getLocation() + ": coins spent " + (cb - Ledger.Wallet.coins(b)) + " (" + String.format("%.1f", (cb - Ledger.Wallet.coins(b)) / (double) price()) + " teleports' worth), law runes from stock " + (law1 - MarketBrain.stockOf(563)) + ", landing " + b.getProperties().getTeleportLocation() + ", state " + rb.state + " to " + rb.dest);
                        return false;
                    }
                    if (t == 90) {
                        log("RESULT A landed at " + a.getLocation() + ", paid " + (ca - Ledger.Wallet.coins(a)) + " gp; B landed at " + b.getLocation() + ", paid " + (cb - Ledger.Wallet.coins(b)) + " gp (one teleport ~" + price() + ")");
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
