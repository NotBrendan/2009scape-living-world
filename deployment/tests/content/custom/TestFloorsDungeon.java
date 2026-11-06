package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.floorsDungeon=true): the quest Mover walks between floors and into and out of a basement. */
public final class TestFloorsDungeon implements StartupListener {
    static void log(String s) { System.out.println("[TestFloorsDungeon] " + s); }

    // {name, startX, startY, startZ, goalX, goalY, goalZ}
    static final Object[][] LEGS = {
            {"deep in a dungeon (3211,9825) -> Romeo in Varrock square", 3211, 9825, 0, 3211, 3425, 0},
    };

    @Override public void startup() {
        if (!Boolean.getBoolean("test.floorsDungeon")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg = -1, ticks; AIPlayer bot; final QuestSteps.Mover m = new QuestSteps.Mover(); String lastSaid = "";
            @Override public boolean pulse() {
                t++;
                try {
                    if (t < 60) return false;
                    if (leg >= LEGS.length) return true;
                    if (bot == null) { bot = new AIPlayer(Location.create(3222, 3218, 0)); Ledger.Wallet.give(bot, 20_000); SmartAdventurers.finishTutorial(bot); }
                    if (leg < 0 || ticks > 700) {
                        if (leg >= 0) log("LEG " + leg + " TIMEOUT at " + bot.getLocation() + " (" + m.last + ")");
                        if (++leg >= LEGS.length) { log("DONE"); return true; }
                        Object[] L = LEGS[leg];
                        if ((Integer) L[1] > 0) bot.getProperties().setTeleportLocation(Location.create((Integer) L[1], (Integer) L[2], (Integer) L[3]));
                        m.reset(Location.create((Integer) L[4], (Integer) L[5], (Integer) L[6]), 3);
                        ticks = 0; lastSaid = "";
                        log("LEG " + leg + ": " + L[0]);
                        return false;
                    }
                    if (ticks++ < 3) return false; // let the start teleport land
                    QuestSteps.St s = m.tick(bot);
                    if (!m.last.equals(lastSaid)) { lastSaid = m.last; log("   " + bot.getLocation() + ": " + m.last); }
                    if (s == QuestSteps.St.DONE) { log("LEG " + leg + " DONE in " + ticks + " ticks at " + bot.getLocation()); nextLeg(); }
                    else if (s == QuestSteps.St.FAILED) { log("LEG " + leg + " FAILED at " + bot.getLocation() + ": " + m.last); nextLeg(); }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); return true; }
                return false;
            }
            void nextLeg() {
                if (++leg >= LEGS.length) { log("DONE"); leg = LEGS.length; ticks = -1_000_000; return; }
                Object[] L = LEGS[leg];
                if ((Integer) L[1] > 0) bot.getProperties().setTeleportLocation(Location.create((Integer) L[1], (Integer) L[2], (Integer) L[3]));
                m.reset(Location.create((Integer) L[4], (Integer) L[5], (Integer) L[6]), 3);
                ticks = 0; lastSaid = "";
                log("LEG " + leg + ": " + L[0]);
            }
        });
    }
}
