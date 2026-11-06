package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr2=true): combat review 10-23 #2. Bots on goblin trips with 50k for teleports. Each time one is back
 * at work the test empties its pack of food and drops it to 12/50 hp (up to 3 times); each rest is fast-forwarded.
 *  A: no food on it and none in the bank. Before: teleport, rest, back with no food, teleport again. After: one teleport,
 *     then "Out of food." ends the trip.
 *  B: 30 trout in the bank (so it restocks and goes back each time). Before: a teleport per drop, the trip goes on. After:
 *     the trip ends after the 2nd emergency teleport (the cap).
 */
public final class TestCR2 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR2] " + s); }

    static void scenario(final String tag, final int T0, final Location SPOT, final int bankTrout) {
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, drops, last = -1, teleports; AIPlayer b; AfkTasks.Runner r; String lastState = ""; Location prev;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) { b = TestCR1.bot(SPOT, 50, 30, 30, 30); b.getBank().clear(); Ledger.Wallet.give(b, 50_000); if (bankTrout > 0) b.getBank().add(new Item(333, bankTrout)); log(tag + " bot " + b.getUsername() + " made: no food on it, " + bankTrout + " trout in the bank, " + Ledger.Wallet.coins(b) + " gp"); }
                    if (t == T0 + 4) {
                        r = new AfkTasks.Runner(b, AfkTasks.Task.GOBLIN, true, 0, false, false);
                        r.setDelay(3); AfkTasks.RUNNING.put(b.getUsername().toLowerCase(java.util.Locale.ROOT), r); GameWorld.getPulser().submit(r);
                        prev = b.getLocation();
                    }
                    if (r == null || t <= T0 + 4) return false;
                    if (b.getLocation().getDistance(prev) > 60 && r.state == AfkTasks.State.RETREAT) teleports++;
                    prev = b.getLocation();
                    String st = r.state + (r.isRunning() ? "" : " (ended)");
                    if (!st.equals(lastState)) { log(tag + " state " + st + " at " + b.getLocation() + " hp " + b.getSkills().getLifepoints() + ", food on it " + r.foodCount()); lastState = st; }
                    if (r.state == AfkTasks.State.WORK && r.isRunning() && b.getLocation().getDistance(SPOT) < 20 && t - last > 20 && drops < 3) {
                        for (Item it : b.getInventory().toArray()) if (it != null && it.getId() == 333) b.getInventory().remove(it);
                        b.getSkills().setLifepoints(12); r.lastHp = 12; drops++; last = t; // lastHp: a set, not a 38-damage hit
                        log(tag + " drop " + drops + ": pack food gone, hp set to 12/50 at the goblins");
                    }
                    if (r.state == AfkTasks.State.RECOVER && b.getSkills().getLifepoints() < 46) { b.getSkills().setLifepoints(46); r.lastHp = 46; } // the rest, fast-forwarded
                    if (r.state == AfkTasks.State.TO_SPOT && b.getLocation().getDistance(SPOT) > 30 && b.getLocation().getDistance(SPOT) < 400 && t - last > 10) { b.getProperties().setTeleportLocation(SPOT); last = t; log(tag + " (walk back fast-forwarded)"); }
                    if (!r.isRunning() || r.halted || t > T0 + 1500 || drops >= 3 && t - last > 80) {
                        log("RESULT " + tag + ": hp drops " + drops + ", emergency teleports seen " + teleports + ", runner " + (r.isRunning() && !r.halted ? "still running (" + r.state + ")" : "ended") + ", food on it " + r.foodCount() + ", trout in the bank " + b.getBank().getAmount(333));
                        if (r.isRunning()) r.halt();
                        log(tag + " DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log(tag + " DONE"); return true; }
                return false;
            }
        });
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr2")) return;
        scenario("A", 160, Location.create(3247, 3239, 0), 0);
        scenario("B", 175, Location.create(3250, 3245, 0), 30);
    }
}
