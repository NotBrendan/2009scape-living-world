package content.custom;

import core.api.StartupListener;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.prt2=true -Dtraders.fast=true): pathing review 10-23 T-2, a fishmonger from Catherby with Fishing 1 (net fishing). Where does it gather? */
public final class TestPRT2 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRT2] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prt2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; long t0; TravellingTraders.Trader tr; final Location home = Location.create(2837, 3434, 0); double xp0; String said = "";
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t < 20) return false;
                    if (tr == null) {
                        TravellingTraders.spawnNow(TravellingTraders.Kind.FOOD, home);
                        for (TravellingTraders.Trader x : TravellingTraders.ALL) if (x.home.equals(home)) tr = x;
                        if (tr == null || tr.bot == null) { log("no trader"); log("DONE"); return true; }
                        tr.bot.getSkills().setStaticLevel(Skills.FISHING, 1); tr.bot.getSkills().setLevel(Skills.FISHING, 1);
                        tr.bot.getInventory().add(new Item(303, 1)); // a small net
                        xp0 = tr.bot.getSkills().getExperience(Skills.FISHING); t0 = System.currentTimeMillis();
                        log("fishmonger " + tr.bot.getUsername() + " at " + tr.bot.getLocation() + ", home " + home + ", Fishing 1 (task " + tr.task() + ")");
                        return false;
                    }
                    long secs = (System.currentTimeMillis() - t0) / 1000;
                    String s = tr.runner == null ? "no runner" : "runner spot " + tr.runner.spot + " (" + (int) tr.runner.spot.getDistance(home) + " tiles from home), state " + tr.runner.state;
                    if (!s.equals(said)) { said = s; log("   " + secs + " s: " + s + ", bot at " + tr.bot.getLocation()); }
                    if (t % 50 == 0) log("   " + secs + " s: bot at " + tr.bot.getLocation() + " (" + (int) tr.bot.getLocation().getDistance(home) + " from home), fishing xp +" + (int) (tr.bot.getSkills().getExperience(Skills.FISHING) - xp0) + ", phase " + tr.phase);
                    if (secs >= 150) {
                        log("RESULT after " + secs + " s: " + (tr.runner != null ? "gathering at " + tr.runner.spot + " (" + (int) tr.runner.spot.getDistance(home) + " tiles from home)" : "phase " + tr.phase) + ", bot at " + tr.bot.getLocation() + " (" + (int) tr.bot.getLocation().getDistance(home) + " from home), fishing xp +" + (int) (tr.bot.getSkills().getExperience(Skills.FISHING) - xp0) + ", watchdog rescues so far " + Watchdog.rescued);
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
