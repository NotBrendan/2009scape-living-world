package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.prr2=true): pathing review 10-23 R-2. A learned route whose next step is a shortcut. Leg A: Falador's
 * crumbling wall (2938,3357 -> 2934,3355) driven the way the AFK runner drives a walk (Runner.walk every tick). Leg B: the
 * Lumbridge stile (3198,3277 -> 3197,3275) with the quest Mover.
 */
public final class TestPRR2 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRR2] " + s); }
    static RouteMap.Portal find(String name, int fx, int fy) {
        for (java.util.List<RouteMap.Portal> l : RouteMap.PORTALS.values()) for (RouteMap.Portal p : l) if (p.sc.name.equals(name) && p.fx == fx && p.fy == fy) return p;
        return null;
    }
    static void plan(AIPlayer bot, RouteMap.Portal pt, Location dest) {
        RouteMap.Route rt = new RouteMap.Route();
        rt.add(Location.create(pt.fx, pt.fy, 0)); rt.add(Location.create(pt.tx, pt.ty, 0)); rt.add(dest);
        rt.portals.put(0, pt);
        RouteMap.PLANS.put(bot.getUsername().toLowerCase(), new Object[]{RouteMap.cell(dest.getX(), dest.getY()), rt, 0, System.currentTimeMillis()});
    }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prr2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg, ticks; AIPlayer bot; RouteMap.Portal pt; Location dest; AfkTasks.Runner r; final QuestSteps.Mover m = new QuestSteps.Mover(); String said = ""; int ok0, fail0; boolean crossedSeen;
            @Override public boolean pulse() {
                try {
                    if (++t < 40) return false;
                    if (leg == 0) {
                        pt = find("Crumbling wall", 2938, 3357);
                        if (pt == null) { log("no crumbling wall portal"); log("DONE"); return true; }
                        bot = new AIPlayer(Location.create(pt.fx, pt.fy, 0));
                        bot.getSkills().setStaticLevel(Skills.AGILITY, 99); bot.getSkills().setLevel(Skills.AGILITY, 99);
                        dest = Location.create(2908, 3357, 0);
                        log("leg A (AFK runner): bot at " + bot.getLocation() + ", dest " + dest + " walkable " + Surveyors.Surveyor.walkable(dest) + "; portal ok " + pt.ok + " fail " + pt.fail);
                        ok0 = pt.ok; fail0 = pt.fail;
                        leg = 1; ticks = 0; return false;
                    }
                    ticks++;
                    if (ticks == 3) {
                        plan(bot, pt, dest);
                        if (leg == 1) { r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false); r.dest = dest; }
                        else m.reset(dest, 3);
                    }
                    if (ticks < 4) return false;
                    String s;
                    if (leg == 1) { r.walk(AfkTasks.State.WORK); s = "last '" + r.last + "', using " + RouteMap.USING.containsKey(bot.getUsername().toLowerCase()) + ", pulse " + bot.getPulseManager().hasPulseRunning(); }
                    else { QuestSteps.St st = m.tick(bot); s = "mover " + st + " last '" + m.last + "', using " + RouteMap.USING.containsKey(bot.getUsername().toLowerCase()); }
                    boolean crossed = leg == 1 ? bot.getLocation().getX() <= 2935 : bot.getLocation().getY() <= 3275;
                    if (crossed && !crossedSeen) { crossedSeen = true; log("   tick " + ticks + ": through the shortcut at " + bot.getLocation()); }
                    if (!s.equals(said) || ticks % 25 == 0) { said = s; log("   tick " + ticks + " at " + bot.getLocation() + ": " + s); }
                    if (ticks >= 70 || bot.getLocation().getDistance(dest) <= 3) {
                        log("RESULT leg " + (leg == 1 ? "A (AFK runner)" : "B (quest Mover)") + ": " + (crossedSeen ? "went through the " + pt.sc.name : "did NOT go through the " + pt.sc.name) + " in " + ticks + " ticks, at " + bot.getLocation() + (bot.getLocation().getDistance(dest) <= 3 ? " (arrived)" : "") + "; portal ok " + ok0 + "->" + pt.ok + ", fail " + fail0 + "->" + pt.fail + ", still marked using " + RouteMap.USING.containsKey(bot.getUsername().toLowerCase()));
                        if (leg == 3) { log("DONE"); return true; }
                        RouteMap.PLANS.remove(bot.getUsername().toLowerCase()); RouteMap.USING.remove(bot.getUsername().toLowerCase());
                        pt = find("Stile", 3198, 3277);
                        if (pt == null) { log("no stile portal"); log("DONE"); return true; }
                        bot.getPulseManager().clear(); bot.getProperties().setTeleportLocation(Location.create(pt.fx, pt.fy, 0));
                        dest = Location.create(3197, 3250, 0); ok0 = pt.ok; fail0 = pt.fail; crossedSeen = false; said = "";
                        log("leg B (quest Mover): stile " + pt.fx + "," + pt.fy + " -> " + pt.tx + "," + pt.ty + ", dest " + dest + " walkable " + Surveyors.Surveyor.walkable(dest));
                        leg = 3; ticks = 0;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
