package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.prt2a=true): pathing review 10-23 T-2, the AfkTasks side. The spots listed for net and bait fishing,
 * and the nearest one to a Catherby home (2839,3434). Then a net runner and a bait runner (3-tick pulse, drop when full) at
 * that nearest spot for 3 minutes: fish caught.
 */
public final class TestPRT2A implements StartupListener {
    static void log(String s) { System.out.println("[TestPRT2A] " + s); }
    static AfkTasks.Spot nearest(AfkTasks.Task t, Location home) {
        AfkTasks.Spot best = null; for (AfkTasks.Spot s : AfkTasks.SPOTS.get(t)) if (best == null || s.at.getDistance(home) < best.at.getDistance(home)) best = s; return best;
    }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prt2a")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, b; AfkTasks.Runner ra, rb; long t0; final Location home = Location.create(2839, 3434, 0);
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 130) {
                        for (AfkTasks.Task tk : new AfkTasks.Task[]{AfkTasks.Task.NET, AfkTasks.Task.BAIT}) {
                            StringBuilder s = new StringBuilder(); for (AfkTasks.Spot sp : AfkTasks.SPOTS.get(tk)) s.append(sp.name).append(" ").append(sp.at).append(" (").append((int) sp.at.getDistance(home)).append(" from home); ");
                            log(tk + " spots: " + s);
                        }
                        AfkTasks.Spot n = nearest(AfkTasks.Task.NET, home), bt = nearest(AfkTasks.Task.BAIT, home);
                        a = TestCR1.bot(n.at, 30, 10, 10, 10); a.getInventory().add(new Item(303, 1));
                        b = TestCR1.bot(bt.at, 30, 10, 10, 10); b.getInventory().add(new Item(307, 1)); b.getInventory().add(new Item(313, 500));
                        b.getSkills().setStaticLevel(Skills.FISHING, 10); b.getSkills().setLevel(Skills.FISHING, 10);
                        log("RESULT nearest net spot to the Catherby home: " + n.name + " " + (int) n.at.getDistance(home) + " tiles; bait: " + bt.name + " " + (int) bt.at.getDistance(home) + " tiles");
                        return false;
                    }
                    if (t == 136) {
                        ra = new AfkTasks.Runner(a, AfkTasks.Task.NET, false, 0, false, false); ra.setDelay(3); AfkTasks.RUNNING.put(a.getUsername().toLowerCase(), ra); GameWorld.getPulser().submit(ra);
                        rb = new AfkTasks.Runner(b, AfkTasks.Task.BAIT, false, 0, false, false); rb.setDelay(3); AfkTasks.RUNNING.put(b.getUsername().toLowerCase(), rb); GameWorld.getPulser().submit(rb);
                        t0 = System.currentTimeMillis();
                        return false;
                    }
                    if (ra == null || t < 136) return false;
                    long secs = (System.currentTimeMillis() - t0) / 1000;
                    if (t % 100 == 0) log("   " + secs + " s: net " + ra.collected + " at " + a.getLocation() + " '" + ra.last + "'; bait " + rb.collected + " at " + b.getLocation() + " '" + rb.last + "'");
                    if (secs >= 180) {
                        log("RESULT 3 min fishing: net runner " + ra.collected + " fish (halted " + ra.halted + "), bait runner " + rb.collected + " fish (halted " + rb.halted + ")");
                        ra.halt(); rb.halt(); log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
