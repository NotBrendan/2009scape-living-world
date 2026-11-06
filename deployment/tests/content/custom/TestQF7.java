package content.custom;

import core.game.interaction.IntType;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (quest fix 10-23). -Dtest.qf7=true: another bot opens the graveyard coffin and leaves it open
 * (the server never shuts it), the ghost hides again after 100 ticks, then a bot at stage 20 wearing the amulet of
 * ghostspeak runs The Restless Ghost from "open the coffin". Live: "talk to the ghost: dialogue: dialogue never opened".
 */
public final class TestQF7 implements StartupListener {
    @Override public void startup() {
        if (!Boolean.getBoolean("test.qf7")) return;
        System.out.println("[TestQF7] startup");
        GameWorld.getPulser().submit(new Pulse(40) {
            @Override public boolean pulse() {
                try { TestQF7.run(); } catch (Throwable t) { System.out.println("[TestQF7] failed: " + t); t.printStackTrace(System.out); }
                return true;
            }
        });
    }

    static void run() {
        final QuestScript s = QuestBook.SCRIPTS.get("restlessghost");
        int idx = -1;
        for (int i = 0; i < s.steps.size(); i++) if (s.steps.get(i).name.equals("open the coffin")) idx = i;
        final int coffinIdx = idx;
        final Location shed = Location.create(3249, 3194, 0);
        final AIPlayer other = new AIPlayer(shed), bot = new AIPlayer(Location.create(3247, 3195, 0));
        other.getProperties().setTeleportLocation(shed);
        bot.getProperties().setTeleportLocation(Location.create(3247, 3195, 0));
        try { bot.getInventory().clear(); bot.getBank().clear(); bot.getEquipment().clear(); } catch (Throwable ignored) { }
        SmartAdventurers.finishTutorial(bot);
        QuestBook.q(bot).test = true;
        bot.getQuestRepository().setStageNonmonotonic(bot.getQuestRepository().getQuest(content.data.Quests.THE_RESTLESS_GHOST), 20);
        bot.getEquipment().replace(new Item(552), 2);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; boolean started;
            @Override public boolean pulse() {
                t++;
                if (t == 4) {
                    Scenery c = core.game.world.map.RegionManager.getObject(0, 3249, 3192, 2145);
                    System.out.println("[TestQF7] " + other.getUsername() + " opens the coffin " + c + ": " + (c != null && QuestSteps.click(other, c, "Open", IntType.SCENERY)));
                }
                if (t == 120) {
                    System.out.println("[TestQF7] ghost showing now " + QuestRestlessGhost.ghostShowing(bot) + ", coffin open " + QuestRestlessGhost.coffinOpen(bot) + "; " + bot.getUsername() + " (stage 20, amulet worn " + bot.getEquipment().contains(552, 1) + ") starts at step " + coffinIdx);
                    try { other.clear(); } catch (Throwable ignored) { }
                    QuestRunner.start(bot, s, coffinIdx, true);
                    started = true;
                    return false;
                }
                if (!started) return false;
                QuestRunner r = QuestRunner.of(bot);
                if (r != null && s.stage(bot) < 30 && t < 900) return false;
                System.out.println("[TestQF7] RESULT: stage " + s.stage(bot) + ", runner " + (r == null ? "gone (abandoned)" : "on step " + r.idx) + ", " + t + " ticks");
                if (r != null) r.stopHere(true);
                return true;
            }
        });
    }
}
