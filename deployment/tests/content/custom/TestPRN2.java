package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.Region;
import core.game.world.map.RegionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Trial only (-Dtest.prn2=true -Dnav=true -Dnav.debug=true): pathing review 10-23 N-2. Shut doors near Lumbridge and Draynor:
 * the planner's route goes through each; walk it with the AFK runner's walk (doors 1-2) and the quest Mover (doors 3-4).
 */
public final class TestPRN2 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRN2] " + s); }
    static final int[][] DIR = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}}; // N E S W
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prn2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg = -1, ticks, rep0, opened0, missing0; AIPlayer bot; final List<Location[]> legs = new ArrayList<>(); AfkTasks.Runner r; final QuestSteps.Mover m = new QuestSteps.Mover(); Location goal; String said = "";
            @Override public boolean pulse() {
                try {
                    if (++t < 30 || !NavPlanner.built) return t > 600;
                    if (leg < 0) {
                        for (int x = 3080; x <= 3260 && legs.size() < 4; x++) for (int y = 3150; y <= 3300 && legs.size() < 4; y++) {
                            int b = NavGrid.grid[NavGrid.idx(x, y)];
                            if ((b & NavGrid.DOOR) == 0 || Integer.bitCount(b & (NavGrid.WN | NavGrid.WE | NavGrid.WS | NavGrid.WW)) != 1) continue;
                            Region rg = RegionManager.forId(((x >> 6) << 8) | (y >> 6)); if (rg != null && !rg.isLoaded()) Region.load(rg);
                            Scenery o = RegionManager.getObject(0, x, y);
                            if (o == null || o.getType() != 0 || o.getDefinition() == null || !o.getDefinition().hasAction("Open")) continue;
                            int e = new int[]{3, 0, 1, 2}[o.getRotation() & 3]; // rot 0 W, 1 N, 2 E, 3 S
                            boolean far = true; for (Location[] l : legs) if (l[2].getDistance(Location.create(x, y, 0)) < 30) far = false;
                            if (!far) continue;
                            for (int side = 0; side < 2; side++) { // the door tile's side out, then in, or the other way
                                int dx = DIR[e][0] * (side == 0 ? 1 : -1), dy = DIR[e][1] * (side == 0 ? 1 : -1);
                                Location s = Location.create(x - dx * (side == 0 ? 5 : 4), y - dy * (side == 0 ? 5 : 4), 0), g = Location.create(x + dx * (side == 0 ? 4 : 5), y + dy * (side == 0 ? 4 : 5), 0);
                                if (!NavGrid.standable(s.getX(), s.getY()) || !NavGrid.standable(g.getX(), g.getY())) continue;
                                NavPlanner.Route rt = NavPlanner.plan(s, g, 99);
                                if (rt == null || rt.doors < 1 || rt.tiles > 14) continue;
                                legs.add(new Location[]{s, g, Location.create(x, y, 0)});
                                log("door " + o.getName() + " at " + x + "," + y + ": " + s + " -> " + g + ", planner " + rt.tiles + " tiles, " + rt.doors + " doors");
                                break;
                            }
                        }
                        if (legs.isEmpty()) { log("no doors found"); log("DONE"); return true; }
                        bot = new AIPlayer(legs.get(0)[0]);
                        leg = 0; begin(); return false;
                    }
                    ticks++;
                    if (ticks < 4) return false;
                    String s;
                    if (leg < 2) { r.walk(AfkTasks.State.WORK); s = "runner '" + r.last + "'"; }
                    else { m.tick(bot); s = "mover '" + m.last + "'"; }
                    if (!s.equals(said)) { said = s; log("   tick " + ticks + " at " + bot.getLocation() + ": " + s); }
                    boolean arrived = bot.getLocation().getDistance(goal) <= 2;
                    if (arrived || ticks >= 150) {
                        log("RESULT door " + (leg + 1) + " (" + (leg < 2 ? "AFK runner" : "quest Mover") + ", door at " + legs.get(leg)[2] + "): " + (arrived ? "through and arrived in " + ticks + " ticks" : "NOT through after " + ticks + " ticks, at " + bot.getLocation()) + "; replans " + (NavWalker.replans - rep0) + ", door clicks counted " + (NavWalker.doorsOpened - opened0) + ", door not found " + (NavWalker.doorsMissing - missing0));
                        if (++leg >= legs.size()) { log("DONE"); return true; }
                        begin();
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
            void begin() {
                Location[] L = legs.get(leg);
                bot.getPulseManager().clear(); bot.getWalkingQueue().reset(); NavWalker.forget(bot);
                bot.getProperties().setTeleportLocation(L[0]); goal = L[1]; ticks = 0; said = "";
                rep0 = NavWalker.replans; opened0 = NavWalker.doorsOpened; missing0 = NavWalker.doorsMissing;
                if (leg < 2) { r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false); r.dest = goal; } else m.reset(goal, 2);
                log("LEG " + (leg + 1) + ": " + L[0] + " -> " + L[1] + " through the door at " + L[2]);
            }
        });
    }
}
