package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.pra3=true): pathing review 10-23 A-3. A bot without Shilo Village fighting on Karamja (2873,3103),
 * no food, 100k coins, flees "low on health with no food left". Which bank does it pick, where does it land, and does the
 * runner live through the flight? Also the bank a full pack would walk to.
 */
public final class TestPRA3 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRA3] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.pra3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, k; AIPlayer b; AfkTasks.Runner r;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 70) { b = TestCR1.bot(Location.create(2873, 3103, 0), 60, 20, 20, 20); b.getBank().add(new Item(995, 100_000)); Gates.SEEDED.put(b.getUsername().toLowerCase(java.util.Locale.ROOT), new java.util.HashSet<>()); return false; } // a bot with a quest history and no Shilo Village in it
                    if (t == 74) {
                        r = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false);
                        Location nb = r.nearestBank();
                        log("bot " + b.getUsername() + " at " + b.getLocation() + ", Shilo Village allowed " + Gates.canEnter(b, Location.create(2852, 2955, 0)) + "; nearestBank() (also the full-pack bank) " + nb + (nb != null && !Gates.canEnter(b, nb) ? " (GATED: needs " + Gates.missing(b, nb) + ")" : ""));
                        b.getSkills().setLifepoints(8);
                        r.flee("low on health with no food left");
                        log("   flee: state " + r.state + ", dest " + r.dest + ", teleport pending to " + b.getProperties().getTeleportLocation() + ", halted " + r.halted + ", last '" + r.last + "'");
                        r.setDelay(3); AfkTasks.RUNNING.put(b.getUsername().toLowerCase(), r); GameWorld.getPulser().submit(r);
                        return false;
                    }
                    if (r == null || t < 74) return false;
                    k++;
                    if (b.getSkills().getLifepoints() < 8) b.getSkills().setLifepoints(8);
                    if (k == 10 || r.halted || k == 120) {
                        log("RESULT " + k + " ticks after the flee: at " + b.getLocation() + (Gates.canEnter(b, b.getLocation()) ? "" : " (INSIDE " + Gates.missing(b, b.getLocation()) + " ground)") + ", runner " + (r.halted ? "FINISHED" : "running, " + r.state + " to " + r.dest));
                        if (r.halted || k == 120) { if (!r.halted) r.halt(); log("DONE"); return true; }
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
