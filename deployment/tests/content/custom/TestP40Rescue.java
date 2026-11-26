package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.p40rescue=true; -Dsmartbots.count=0 -Dquests=false): P40 B3 (the design notes section 3).
 * A bot whose AfkTasks.Runner is in TO_SPOT toward Seers (5000+ gp) is rescued with Watchdog.rescue(bot, at, 5), shape of
 * TestB15A. Case 1: flag off, planner off = paid rescue-trip (today). Case 2: flag on, planner off = free bank rescue
 * (coins and tripRescues unchanged, bot at the nearest bank, Runner finished). Case 3: flag on, planner on = paid as before.
 * Lines: "[TestP40Rescue] RESULT case <n> ... PASS|FAIL", then "[TestP40Rescue] PASS 3/3" (or "FAIL k/3") and "DONE".
 */
public final class TestP40Rescue implements StartupListener {
    static void log(String s) { System.out.println("[TestP40Rescue] " + s); }
    static final Location AT = Location.create(3236, 3226, 0), SEERS = Location.create(2725, 3491, 0);

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p40rescue")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, k, c, pass; AIPlayer bot; AfkTasks.Runner r; long c0; int tr0; Location bank; boolean navWas, flagWas;
            final String[] NAME = {"flag off, planner off (paid as today)", "flag on, planner off (free bank rescue)", "flag on, planner on (paid as before)"};
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    if (t == 110) { navWas = NavWalker.ON; flagWas = Watchdog.TRIP_NAV_ONLY; }
                    k++;
                    if (k == 1) {
                        bot = new AIPlayer(AT); SmartAdventurers.finishTutorial(bot);
                        bot.getInventory().add(new Item(995, 20000));
                        return false;
                    }
                    if (k == 4) {
                        Watchdog.TRIP_NAV_ONLY = c != 0;
                        NavWalker.ON = c == 2;
                        r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false);
                        r.state = AfkTasks.State.TO_SPOT; r.dest = SEERS;
                        AfkTasks.RUNNING.put(Watchdog.key(bot), r);
                        Location at = bot.getLocation();
                        bank = Watchdog.nearestBank(at, bot);
                        c0 = Ledger.Wallet.coins(bot); tr0 = Watchdog.tripRescues;
                        Watchdog.rescue(bot, at, 5);
                        NavWalker.ON = navWas; // the paid/free choice is made inside rescue(); the watcher owns ON again
                        return false;
                    }
                    if (k == 8) {
                        long paid = c0 - Ledger.Wallet.coins(bot); int trips = Watchdog.tripRescues - tr0;
                        boolean finished = AfkTasks.RUNNING.get(Watchdog.key(bot)) != r;
                        double dBank = bank == null ? -1 : bot.getLocation().getDistance(bank);
                        boolean ok = c == 1 ? paid == 0 && trips == 0 && bank != null && dBank <= 3 && finished
                                : paid > 0 && trips == 1 && bot.getLocation().getDistance(SEERS) < 60;
                        if (ok) pass++;
                        log("RESULT case " + (c + 1) + " " + NAME[c] + ": paid " + paid + " gp, tripRescues +" + trips + ", " + Math.round(dBank) + " tiles from the bank, runner finished " + finished + ", at " + bot.getLocation() + " " + (ok ? "PASS" : "FAIL"));
                        try { AfkTasks.RUNNING.remove(Watchdog.key(bot)); bot.clear(); } catch (Throwable ignored) { }
                        bot = null; k = 0; c++;
                        if (c == 3) { Watchdog.TRIP_NAV_ONLY = flagWas; log((pass == 3 ? "PASS " : "FAIL ") + pass + "/3"); log("DONE"); return true; }
                    }
                    return false;
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("FAIL error"); log("DONE"); return true; }
            }
        });
    }
}
