package content.custom;

import core.api.StartupListener;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.prt1=true -Dtraders.fast=true): pathing review 10-23 T-1. A fishmonger of our own whose gathering trip
 * time runs out while it carries fish (the live case: a 30-60 minute trip ending mid-work). Its AFK runner then walks the
 * fish to a bank. When does the trader take over (pack the haul, go selling), and is the runner still driving the bot then?
 */
public final class TestPRT1 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRT1] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prt1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; long t0, tCut; TravellingTraders.Trader mine; AfkTasks.Runner r; boolean handed, second; final Location home = Location.create(2839, 3434, 0); String said = "";
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 20) {
                        TravellingTraders.spawnNow(TravellingTraders.Kind.FOOD, home);
                        for (TravellingTraders.Trader x : TravellingTraders.ALL) if (x.home.equals(home)) mine = x;
                        if (mine == null) { log("no trader"); log("DONE"); return true; }
                        mine.bot.getSkills().setStaticLevel(Skills.FISHING, 1); mine.bot.getSkills().setLevel(Skills.FISHING, 1); mine.bot.getInventory().add(new Item(303, 1)); mine.gatherSecs = 100_000; // only the test ends this trip
                        t0 = System.currentTimeMillis(); log("own fishmonger " + mine.bot.getUsername() + " at " + home);
                    }
                    if (mine == null) return false;
                    if (r == null && t % 5 == 0 && !mine.bot.getPulseManager().hasPulseRunning()) mine.tick(); // its script tick while idle (late-spawned bots are starved by the engine's 75-scripts-a-tick cap)
                    if (r == null && t % 100 == 0) log("   " + (System.currentTimeMillis() - t0) / 1000 + " s: runner " + (mine.runner == null ? "none" : mine.runner.state + " with " + mine.runner.countProducts() + " fish") + ", bot at " + mine.bot.getLocation());
                    if (r == null && mine.runner != null && mine.runner.state == AfkTasks.State.WORK && mine.runner.countProducts() < 2 && System.currentTimeMillis() - t0 > 30_000) { mine.bot.getInventory().add(new Item(317, 2)); log("   two raw shrimps put in the pack at the spot " + mine.bot.getLocation() + " (as if caught)"); }
                    if (r == null) {
                        if (mine.runner != null && mine.runner.countProducts() >= 2 && mine.runner.state == AfkTasks.State.WORK) {
                            r = mine.runner; mine.gatherSecs = 0; tCut = System.currentTimeMillis();
                            log(mine.bot.getUsername() + " has " + r.countProducts() + " fish in the pack at " + mine.bot.getLocation() + ": trip time up, and the trader gets a tick (as in a gap between its pulses)");
                            mine.tick();
                        } else if (System.currentTimeMillis() - t0 > 400_000) { log("never had 2 fish"); log("DONE"); return true; }
                        if (r == null) return false;
                    }
                    long secs = (System.currentTimeMillis() - tCut) / 1000;
                    if (t % 5 == 0) mine.tick(); // the trader's script tick, as its heartbeat gives it (late-spawned bots are starved by the engine's 75-scripts-a-tick cap)
                    String s = "trader " + mine.phase + ", trader's runner " + (mine.runner == r ? "the same" : mine.runner == null ? "none" : "A NEW ONE") + "; old runner halted " + r.halted + ", state " + r.state + ", haul " + mine.haul;
                    if (!s.equals(said)) { said = s; log("   " + secs + " s: " + s + ", bot at " + mine.bot.getLocation()); }
                    if (!handed && mine.runner != r) {
                        handed = true;
                        log("RESULT the trader took over " + secs + " s after the trip time ran out: old runner " + (r.halted ? "halted (fish banked)" : "STILL RUNNING (" + r.state + ")") + "; haul packed " + mine.haul + "; trader " + mine.phase);
                    }
                    if (handed && mine.runner != null && mine.runner != r && !r.halted && !second) { second = true; log("RESULT a second runner started while the first still runs"); }
                    if (handed && (r.halted || secs > 120)) {
                        log("RESULT " + (r.halted ? "old runner halted " + secs + " s after the cut" : "old runner still running after " + secs + " s") + "; trader " + mine.phase + ", haul " + mine.haul + (second ? ", two runners at once" : ""));
                        log("DONE"); return true;
                    }
                    if (secs > 200) { log("RESULT timeout: " + s); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
