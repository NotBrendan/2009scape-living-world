package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.prt1a=true): pathing review 10-23 T-1, the AfkTasks side. What a trader does when its gathering time
 * is up: runner A (net fishing at Draynor, 5 shrimp in the pack) is told finish("Enough for a haul.") and taken out of
 * RUNNING, so it walks the shrimp to the bank without halting; 3 ticks later runner B (the next trip, cows at Lumbridge)
 * is put in RUNNING and starts walking. Who drives the bot after that?
 */
public final class TestPRT1A implements StartupListener {
    static void log(String s) { System.out.println("[TestPRT1A] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prt1a")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, k, both, aStopped = -1; AIPlayer b; AfkTasks.Runner ra, rb; String said = "";
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 100) { b = TestCR1.bot(Location.create(3087, 3229, 0), 40, 20, 20, 20); b.getInventory().add(new Item(303, 1)); return false; }
                    if (t == 104) {
                        ra = new AfkTasks.Runner(b, AfkTasks.Task.NET, true, 0, false, false); ra.setDelay(3); ra.walker = true;
                        String key = b.getUsername().toLowerCase();
                        AfkTasks.RUNNING.put(key, ra); GameWorld.getPulser().submit(ra);
                        b.getInventory().add(new Item(317, 5)); // caught since the trip began
                        ra.finish("Enough for a haul.");
                        AfkTasks.RUNNING.remove(key); // as TravellingTraders.gather does
                        log("A finished with 5 shrimp: state " + ra.state + " to " + ra.dest + ", halted " + ra.halted + "; out of RUNNING");
                        return false;
                    }
                    if (t == 107) {
                        rb = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false); rb.setDelay(3); rb.walker = true;
                        rb.spot = Location.create(3257, 3270, 0); rb.goTo(AfkTasks.State.TO_SPOT, rb.spot);
                        AfkTasks.RUNNING.put(b.getUsername().toLowerCase(), rb); GameWorld.getPulser().submit(rb);
                        log("B started: TO_SPOT " + rb.spot);
                    }
                    if (rb == null) return false;
                    k++;
                    if (!ra.halted) both++; else if (aStopped < 0) aStopped = k;
                    String s = "A " + (ra.halted ? "halted" : ra.state.name()) + ", B " + (rb.halted ? "halted" : rb.state.name() + " '" + rb.last + "'");
                    if (!s.equals(said) || k % 15 == 0) { said = s; log("   tick " + k + " at " + b.getLocation() + ": " + s + ", shrimp in pack " + b.getInventory().getAmount(317) + ", walking " + b.getWalkingQueue().isMoving()); }
                    if (k == 90) {
                        log("RESULT 90 ticks after B started: A " + (aStopped < 0 ? "STILL RUNNING" : "stopped at tick " + aStopped) + " (both runners active for " + both + " ticks); B " + (rb.halted ? "halted" : rb.state) + ", bot at " + b.getLocation() + " (" + (int) b.getLocation().getDistance(rb.spot) + " tiles from B's spot, " + (int) b.getLocation().getDistance(Location.create(3092, 3245, 0)) + " from the bank); shrimp in pack " + b.getInventory().getAmount(317) + ", in bank " + b.getBank().getAmount(317));
                        if (!rb.halted) rb.halt(); log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
