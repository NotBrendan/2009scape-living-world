package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr9=true): combat review 10-23 #9. The desert past the Shantay Pass:
 *  1. Gates.canEnter at [3171, 2912] for a 10 gp bot with no waterskin, and for a 2,000 gp bot with none;
 *  2. the trip packing (Brain.packWater, new) for the 2,000 gp bot: waterskins in the pack;
 *  3. a bot mining at [3171, 2912] with no water: before it keeps working in the heat, after "Out of water." ends the trip.
 */
public final class TestCR9 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR9] " + s); }
    static final Location DESERT = Location.create(3171, 2912, 0);
    static int skins(AIPlayer p) { int n = 0; for (int id : new int[]{1823, 1825, 1827, 1829}) n += p.getInventory().getAmount(id); return n; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr9")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 280; int t; AIPlayer c; AfkTasks.Runner r;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) {
                        AIPlayer a = TestCR1.bot(null, 40, 40, 40, 40), b = TestCR1.bot(null, 40, 40, 40, 40);
                        Ledger.Wallet.give(a, 10); Ledger.Wallet.give(b, 2_000);
                        log("RESULT 1 canEnter desert: 10 gp, no waterskin -> " + Gates.canEnter(a, DESERT) + "; 2,000 gp, no waterskin -> " + Gates.canEnter(b, DESERT) + " (needs: " + Gates.missing(a, DESERT) + ")");
                        SmartAdventurers.Brain bb = TestCR1.rig(b, false);
                        String packed;
                        try { java.lang.reflect.Method m = SmartAdventurers.Brain.class.getDeclaredMethod("packWater"); m.setAccessible(true); m.invoke(bb); packed = "packWater ran"; } catch (NoSuchMethodException e) { packed = "no water packing in this build"; }
                        log("RESULT 2 trip packing for a desert job (" + packed + "): waterskins in the pack " + skins(b) + ", coins " + Ledger.Wallet.coins(b) + "; with them canEnter -> " + Gates.canEnter(b, DESERT));
                        c = TestCR1.bot(DESERT, 40, 40, 40, 40); c.getInventory().add(new Item(1265)); // bronze pickaxe
                        log("bot " + c.getUsername() + " sent to mine at " + DESERT + " with no water");
                    }
                    if (t == T0 + 4) { r = new AfkTasks.Runner(c, AfkTasks.Task.COPPER, true, 0, false, false); r.setDelay(3); AfkTasks.RUNNING.put(c.getUsername().toLowerCase(java.util.Locale.ROOT), r); GameWorld.getPulser().submit(r); }
                    if (t == T0 + 40) {
                        log("RESULT 3 after 36 ticks in the desert with no water: runner " + (r.isRunning() && !r.halted ? "still working (" + r.state + ")" : "ended") + ", at " + c.getLocation() + ", hp " + c.getSkills().getLifepoints());
                        if (r.isRunning()) r.halt();
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
