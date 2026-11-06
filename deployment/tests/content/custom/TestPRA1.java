package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.pra1=true): pathing review 10-23 A-1. A bot's essence runner (3-tick pulse, drop when full) in Aubury's
 * rune essence mine, starting at the spot (the mine's middle, 2911,4832). For 6 minutes: how often the runner went back
 * to TO_SPOT (the live loop: every ~30 s), how much essence it mined, and where the essence rocks are.
 */
public final class TestPRA1 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRA1] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.pra1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, toSpot, ess; AIPlayer b; AfkTasks.Runner r; long t0; AfkTasks.State prev; int last1436;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 45) {
                        b = TestCR1.bot(Location.create(2911, 4832, 0), 30, 20, 20, 20);
                        b.getSkills().setStaticLevel(Skills.MINING, 30); b.getSkills().setLevel(Skills.MINING, 30);
                        b.getInventory().add(new Item(1265, 1)); // bronze pickaxe
                        return false;
                    }
                    if (t == 50) {
                        java.util.Set<String> rocks = new java.util.TreeSet<>();
                        for (int dx = -30; dx <= 30; dx++) for (int dy = -30; dy <= 30; dy++) {
                            core.game.node.scenery.Scenery s = core.game.world.map.RegionManager.getObject(0, 2911 + dx, 4832 + dy);
                            if (s != null && s.getName() != null && s.getName().toLowerCase().contains("essence")) rocks.add(s.getName() + " " + s.getId() + " at " + s.getLocation().getX() + "," + s.getLocation().getY() + " (" + (int) Math.round(s.getLocation().getDistance(Location.create(2911, 4832, 0))) + " from the spot)");
                        }
                        log("essence objects: " + rocks);
                        r = new AfkTasks.Runner(b, AfkTasks.Task.ESSENCE, false, 0, false, false);
                        r.spot = Location.create(2911, 4832, 0); r.setDelay(3);
                        AfkTasks.RUNNING.put(b.getUsername().toLowerCase(), r); GameWorld.getPulser().submit(r);
                        t0 = System.currentTimeMillis(); prev = r.state;
                        log("essence runner for " + b.getUsername() + " at " + b.getLocation());
                        return false;
                    }
                    if (r == null) return false;
                    if (r.state == AfkTasks.State.TO_SPOT && prev != AfkTasks.State.TO_SPOT) { toSpot++; if (toSpot <= 8) log("   " + (System.currentTimeMillis() - t0) / 1000 + " s: back to TO_SPOT from " + b.getLocation() + " (" + (int) Math.round(b.getLocation().getDistance(r.spot)) + " tiles from the spot)"); }
                    prev = r.state;
                    int n = b.getInventory().getAmount(1436); if (n > last1436) ess += n - last1436; last1436 = n;
                    long secs = (System.currentTimeMillis() - t0) / 1000;
                    if (t % 100 == 0) log("   " + secs + " s at " + b.getLocation() + ", state " + r.state + ", collected " + r.collected + ", last '" + r.last + "'");
                    if (secs >= 360 || r.halted) {
                        log("RESULT 6 min in the essence mine: " + toSpot + " walks back to the spot (TO_SPOT), " + r.collected + " essence collected, halted " + r.halted + ", at " + b.getLocation());
                        r.halt(); log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
