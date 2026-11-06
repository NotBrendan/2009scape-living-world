package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr3=true): combat review 10-23 #3. A bot fighting at [2325, 3671] (the Skeleton Mage cluster, 362
 * tiles from the nearest known bank) at low health with no food and 50k. Before: no emergency teleport (nearestBank is
 * null past 220 tiles), it runs ~15 tiles. After: "emergency teleport ... to" the nearest bank.
 */
public final class TestCR3 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR3] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 170; int t; AIPlayer b; AfkTasks.Runner r; final Location SPOT = Location.create(2325, 3671, 0); Location start;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) { b = TestCR1.bot(SPOT, 50, 30, 30, 30); Ledger.Wallet.give(b, 50_000); log("bot " + b.getUsername() + " made, no food, " + Ledger.Wallet.coins(b) + " gp"); }
                    if (t == T0 + 4) {
                        start = b.getLocation();
                        r = new AfkTasks.Runner(b, AfkTasks.Task.AUTO, true, 0, false, false);
                        b.getSkills().setLifepoints(12); r.lastHp = 12;
                        log("at " + start + ", hp 12/50; nearest bank the runner knows: " + r.nearestBank() + ", Watchdog's: " + Watchdog.nearestBank(start) + " (" + (int) Watchdog.nearestBank(start).getDistance(start) + " tiles)");
                        r.setDelay(3); AfkTasks.RUNNING.put(b.getUsername().toLowerCase(java.util.Locale.ROOT), r); GameWorld.getPulser().submit(r);
                    }
                    if (t == T0 + 40) {
                        log("RESULT after 36 ticks: state " + r.state + ", flee reason " + r.fleeReason + ", at " + b.getLocation() + " (" + (int) b.getLocation().getDistance(start) + " tiles from where it was), coins " + Ledger.Wallet.coins(b));
                        r.halt();
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
