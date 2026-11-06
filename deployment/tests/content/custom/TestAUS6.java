package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.aus6=true): audit 10-23 S-6. A veteran's modern profile (items saved) with no gear and 0 coins, as a
 * player-rules death can leave it, is restored. New coins handed out (Ledger GIVEN)? Right: none.
 */
public final class TestAUS6 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUS6] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aus6")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t != 150) return false;
                try {
                    SmartAdventurers.Profile p = new SmartAdventurers.Profile();
                    p.name = "Sixvet" + (100 + new java.util.Random().nextInt(800)); p.veteran = true; p.items = true;
                    for (int i = 0; i < 24; i++) p.levels[i] = 30;
                    p.coins = 0;
                    p.pack.add(new int[]{1511, 1});
                    long g0 = Ledger.GIVEN;
                    SmartAdventurers.restore(p, TestCR1.LUMBRIDGE);
                    log("RESULT veteran profile with items, no gear, 0 coins restored: new coins given " + (Ledger.GIVEN - g0) + " (right: 0)");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
