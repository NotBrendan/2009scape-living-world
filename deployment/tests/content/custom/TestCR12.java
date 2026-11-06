package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr12=true): combat review 10-23 #12. A bot with 20k on a trip at Barbarian Village whose haul (2
 * rune bars, set as its products) stays in the pack over a self-hit death (they are its 3 most valuable things), and a
 * grave holds the rest. Before: the dead trip's runner walks it to a bank while the grave run runs too. After: the trip
 * ends at once ("finished: Died."), the grave run goes alone.
 */
public final class TestCR12 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR12] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr12")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 600; // after TestCR11's grave has gone: engine graves of bots share account uid 0
            int t, died; AIPlayer a; SmartAdventurers.Brain ba; AfkTasks.Runner r; int bankWalkTicks, bothTicks;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) { a = TestCR1.bot(Location.create(3082, 3420, 0), 30, 20, 20, 20); Ledger.Wallet.give(a, 20_000); for (int id : new int[]{2363, 2363, 2351, 2351, 2351, 1205}) a.getInventory().add(new Item(id)); log("bot " + a.getUsername() + " with " + TestCR1.pack(a)); }
                    if (t == T0 + 4) { ba = TestCR1.rig(a, false); r = TestCR1.trip(ba, AfkTasks.Task.BARBARIAN); r.productsOverride = new int[]{2363}; }
                    if (t == T0 + 15) { log("self-hit kill at " + a.getLocation() + ", products in the pack " + r.countProducts()); TestCR1.killBy(a, a); died = t; }
                    if (died > 0 && t > died + 10) {
                        boolean walking = r.isRunning() && !r.halted && r.state == AfkTasks.State.TO_BANK;
                        if (walking) bankWalkTicks++;
                        if (walking && ba.graveAt != null) bothTicks++;
                    }
                    if (died > 0 && t == died + 40) log("t+40: runner " + (r.isRunning() && !r.halted ? "RUNNING, state " + r.state : "ended") + ", grave run " + (ba.graveAt != null ? "ON" : "off") + ", grave " + TestCR1.grave(a) + ", at " + a.getLocation());
                    if (died > 0 && t == died + 300) {
                        log("RESULT ticks the dead trip's runner was walking to a bank: " + bankWalkTicks + ", of those while the grave run was on: " + bothTicks + "; runner now " + (r.isRunning() && !r.halted ? "RUNNING " + r.state : "ended") + ", grave run " + (ba.graveAt != null ? "ON" : "off") + ", pack " + TestCR1.pack(a));
                        if (r.isRunning()) r.halt(); log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
