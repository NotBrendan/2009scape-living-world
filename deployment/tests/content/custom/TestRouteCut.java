package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.List;

/** Trial only (-Dtest.routecut=true): an L-shaped plan; the bot has cut the corner past the bend. Which hop does nextHop give? */
public final class TestRouteCut implements StartupListener {
    @Override public void startup() {
        if (!Boolean.getBoolean("test.routecut")) return;
        GameWorld.getPulser().submit(new Pulse(80) {
            @Override public boolean pulse() {
                try {
                    Location w0 = Location.create(3240, 3260, 0), w1 = Location.create(3260, 3260, 0), w2 = Location.create(3260, 3300, 0), to = Location.create(3260, 3330, 0);
                    AIPlayer bot = new AIPlayer(Location.create(3257, 3268, 0)); // 8.5 tiles past the bend at w1, 3 tiles off the stretch w1 -> w2
                    List<Location> route = new ArrayList<>(); route.add(w0); route.add(w1); route.add(w2); route.add(to);
                    RouteMap.PLANS.put(bot.getUsername().toLowerCase(), new Object[]{RouteMap.cell(to.getX(), to.getY()), route, 1, System.currentTimeMillis()});
                    Location hop = RouteMap.nextHop(bot, to);
                    System.out.println("[TestRouteCut] bot at " + bot.getLocation() + ", plan at waypoint 1 " + w1 + ": next hop " + hop + (hop != null && hop.getY() > bot.getLocation().getY() ? " (forward)" : " (BACK to the bend)"));
                } catch (Throwable t) { System.out.println("[TestRouteCut] error " + t); t.printStackTrace(); }
                System.out.println("[TestRouteCut] DONE");
                return true;
            }
        });
    }
}
