package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.prr2c=true): pathing review 10-23 R-2, the callers in AfkTasks and SmartAdventurers (RouteMap as in
 * this tree). A learned route whose first step is Falador's crumbling wall (2938,3357 -> 2934,3355), bound for 2908,3357.
 * Leg A: an AFK runner's walk (Runner.walk every 3 ticks). Leg B: a smart adventurer's shop walk (Brain.shopTick every 3
 * ticks). Logs what the caller did in the call that clicked the shortcut, then whether the bot went through and arrived.
 */
public final class TestPRR2C implements StartupListener {
    static void log(String s) { System.out.println("[TestPRR2C] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prr2c")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg, ticks, ok0, fail0, crossedAt; AIPlayer bot; RouteMap.Portal pt; final Location dest = Location.create(2908, 3357, 0); AfkTasks.Runner r; SmartAdventurers.Brain br; boolean usingSeen; String said = "";
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    if (leg == 0) {
                        pt = TestPRR2.find("Crumbling wall", 2938, 3357);
                        if (pt == null) { log("no crumbling wall portal"); log("DONE"); return true; }
                        bot = TestCR1.bot(Location.create(pt.fx, pt.fy, 0), 40, 20, 20, 20);
                        bot.getSkills().setStaticLevel(Skills.AGILITY, 99); bot.getSkills().setLevel(Skills.AGILITY, 99);
                        leg = 1; ticks = 0; return false;
                    }
                    ticks++;
                    if (ticks == 5) {
                        ok0 = pt.ok; fail0 = pt.fail; usingSeen = false; crossedAt = 0; said = "";
                        TestPRR2.plan(bot, pt, dest);
                        if (leg == 1) { r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false); r.dest = dest; r.state = AfkTasks.State.TO_SPOT; }
                        else { br = TestCR1.rig(bot, false); br.shopAt = dest; br.shopName = "the test shop"; br.shopBuy.clear(); br.pendingJob = null; br.shopDeadline = System.currentTimeMillis() + 600_000; br.shopBest = Double.MAX_VALUE; br.shopStalls = 0; br.shopLast = null; }
                        log("leg " + (leg == 1 ? "A (AFK runner)" : "B (shop walk)") + " at " + bot.getLocation() + ", portal ok " + ok0 + " fail " + fail0);
                    }
                    if (ticks < 6) return false;
                    String k = bot.getUsername().toLowerCase();
                    if (ticks % 3 == 0) {
                        boolean before = RouteMap.USING.containsKey(k);
                        if (leg == 1) r.walk(AfkTasks.State.WORK); else br.shopTick(System.currentTimeMillis());
                        if (!before && RouteMap.USING.containsKey(k) && !usingSeen) {
                            usingSeen = true;
                            log("   tick " + ticks + ": the call clicked the shortcut; the caller then did: " + (leg == 1 ? "last '" + r.last + "'" : "shopStalls " + br.shopStalls) + ", walking queue moving " + bot.getWalkingQueue().isMoving() + ", pulse " + bot.getPulseManager().getCurrent());
                        }
                    }
                    if (crossedAt == 0 && bot.getLocation().getX() <= 2935) { crossedAt = ticks; log("   tick " + ticks + ": through the wall at " + bot.getLocation()); }
                    String s = (leg == 1 ? "state " + r.state + ", last '" + r.last + "'" : "shopAt " + (br.shopAt != null) + ", stalls " + br.shopStalls) + ", using " + RouteMap.USING.containsKey(k) + ", plan " + RouteMap.PLANS.containsKey(k);
                    if (!s.equals(said)) { said = s; log("   tick " + ticks + " at " + bot.getLocation() + ": " + s); }
                    boolean arrived = leg == 1 ? r.state == AfkTasks.State.WORK : br.shopAt == null;
                    if (arrived || ticks >= 150) {
                        log("RESULT leg " + (leg == 1 ? "A (AFK runner)" : "B (shop walk)") + ": " + (crossedAt > 0 ? "through the wall at tick " + crossedAt : "NOT through the wall") + ", " + (arrived ? "ARRIVED at tick " + ticks : "not arrived after 150 ticks") + " at " + bot.getLocation() + "; portal ok " + ok0 + "->" + pt.ok + ", fail " + fail0 + "->" + pt.fail + ", still marked using " + RouteMap.USING.containsKey(k));
                        if (leg == 2) { log("DONE"); return true; }
                        RouteMap.PLANS.remove(k); RouteMap.USING.remove(k); RouteMap.OFF_ROUTE.remove(k);
                        bot.getPulseManager().clear(); bot.getWalkingQueue().reset(); bot.getProperties().setTeleportLocation(Location.create(pt.fx, pt.fy, 0));
                        leg = 2; ticks = 0;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
