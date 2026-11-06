package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aus7=true): audit 10-23 S-7. A smart bot on a trip ~100 tiles from Lumbridge teleports to Lumbridge
 * (as a crewmate catching up with a human who home-teleported, or a runner's paid teleport). Is it booked as a death?
 */
public final class TestAUS7 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUS7] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aus7")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer b; SmartAdventurers.Brain br;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 200) {
                        b = TestCR1.bot(Location.create(3222, 3318, 0), 30, 30, 30, 30); // the Lumbridge-Draynor road, ~100 tiles north
                        br = TestCR1.rig(b, false);
                        TestCR1.trip(br, AfkTasks.Task.COW);
                        log("bot " + b.getUsername() + " on a cow trip at " + b.getLocation());
                    }
                    if (t == 230) { log("at " + b.getLocation() + " before the teleport; deaths " + br.deaths); b.getProperties().setTeleportLocation(TestCR1.LUMBRIDGE); }
                    if (t == 280) {
                        log("RESULT after a teleport to Lumbridge: brain deaths " + br.deaths + ", blacklist " + br.blacklist.keySet() + ", spot deaths for cows " + SmartAdventurers.recentDeaths(br.lastJob != null ? br.lastJob.label() : "?") + ", bot at " + b.getLocation());
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
