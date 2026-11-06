package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.floors=true): the quest Mover walks between floors and into and out of a basement. */
public final class TestFloors implements StartupListener {
    static void log(String s) { System.out.println("[TestFloors] " + s); }

    // {name, startX, startY, startZ, goalX, goalY, goalZ}
    static final Object[][] LEGS = {
            {"Lumbridge courtyard -> Duke Horacio (1st floor)", 3222, 3218, 0, 3210, 3220, 1},
            {"Duke Horacio -> courtyard", -1, 0, 0, 3222, 3218, 0},
            {"Wizards' Tower door -> Sedridor (basement)", 3109, 3166, 0, 3103, 9571, 0},
            {"basement -> outside the tower", -1, 0, 0, 3109, 3166, 0},
            {"Varrock square -> Blue Moon Inn upstairs", 3212, 3424, 0, 3226, 3395, 1},
            {"Blue Moon upstairs -> Varrock square", -1, 0, 0, 3212, 3424, 0},
            {"Draynor -> Draynor Manor top floor", 3108, 3330, 0, 3105, 3367, 2},
            {"Falador square -> Falador castle 2nd floor", 2965, 3380, 0, 2983, 3340, 2},
    };

    @Override public void startup() {
        if (!Boolean.getBoolean("test.floors")) return;
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
