package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b15b4=true): batch 15 item4, quest gz for a real player. H stands in for a real player
 * (Companions.TEST_HUMANS, as the Companions self-test does) with a bot 3 tiles away. H's quest points go up twice, 40 ticks
 * apart (a quest each): one gz in local chat for the first (the "[BotChat] quest gz" line), none for the second (rate limit).
 * Then H2, far from every bot and in the clan chat: a clan bot says it there.
 */
public final class TestB15B4 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15B4] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15b4")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer h, g, h2;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        h = TestCR1.bot(Location.create(3165, 3485, 0), 30, 20, 20, 20); g = TestCR1.bot(Location.create(3168, 3485, 0), 30, 20, 20, 20);
                        Companions.TEST_HUMANS.add(h.getUsername().toLowerCase(java.util.Locale.ROOT));
                        log(h.getUsername() + " (test human) at " + h.getLocation() + " with " + h.getQuestRepository().getPoints() + " qp; " + g.getUsername() + " 3 tiles away");
                    }
                    if (t == 190) { h.getQuestRepository().incrementPoints(1); log("first quest: " + h.getUsername() + " now " + h.getQuestRepository().getPoints() + " qp"); }
                    if (t == 230) { h.getQuestRepository().incrementPoints(2); log("second quest: " + h.getUsername() + " now " + h.getQuestRepository().getPoints() + " qp"); }
                    if (t == 260) {
                        h2 = TestCR1.bot(Location.create(2600, 3100, 0), 30, 20, 20, 20);
                        Companions.TEST_HUMANS.add(h2.getUsername().toLowerCase(java.util.Locale.ROOT));
                        boolean in = false;
                        try { core.game.system.communication.ClanRepository c = Companions.clan(); if (c != null) in = c.enter(h2); } catch (Throwable ex) { log("clan enter " + ex); }
                        log(h2.getUsername() + " far away at " + h2.getLocation() + ", in the clan chat " + in + " (" + Companions.CLAN_BOTS.size() + " clan bots)");
                    }
                    if (t == 290) { h2.getQuestRepository().incrementPoints(1); log("H2 quest: now " + h2.getQuestRepository().getPoints() + " qp"); }
                    if (t == 330) { log("RESULT see the [BotChat] quest gz lines: expected one for " + h.getUsername() + " (local) and one for " + h2.getUsername() + " (clan)"); log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
