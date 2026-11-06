package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.driverstate=true): a dialogue that closed after its first screen with picks left. What does the failure say? */
public final class TestDriverState implements StartupListener {
    @Override public void startup() {
        if (!Boolean.getBoolean("test.driverstate")) return;
        GameWorld.getPulser().submit(new Pulse(80) {
            @Override public boolean pulse() {
                try {
                    AIPlayer bot = new AIPlayer(Location.create(3222, 3218, 0));
                    DialogueDriver d = new DialogueDriver(bot, 1, 1);
                    d.started = true; // the first screen was shown, then something closed it
                    DialogueDriver.Status s = d.tick();
                    System.out.println("[TestDriverState] " + s + ": " + d.failure());
                } catch (Throwable t) { System.out.println("[TestDriverState] error " + t); t.printStackTrace(); }
                System.out.println("[TestDriverState] DONE");
                return true;
            }
        });
    }
}
