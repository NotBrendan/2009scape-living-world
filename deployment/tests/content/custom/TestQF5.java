package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (quest fix 10-23). -Dtest.qf5=true: two reproductions of live failures without a clear cause.
 * (a) Pirate's Treasure from "through Wydin's back door": a bot in Wydin's shop wearing the white apron (stage 10).
 * (b) Vampire Slayer from "back over to Harlow": a bot at the Blue Moon Inn bar with a beer (stage 20).
 * Prints each bot's step and stage once it passes the step or the run ends.
 */
public final class TestQF5 implements StartupListener {
    @Override public void startup() {
        if (!Boolean.getBoolean("test.qf5")) return;
        System.out.println("[TestQF5] startup");
        GameWorld.getPulser().submit(new Pulse(40) {
            @Override public boolean pulse() {
                try {
                    go("piratestreasure", "through Wydin's back door", content.data.Quests.PIRATES_TREASURE, 10, Location.create(3014, 3206, 0), 1005, true);
                    go("vampireslayer", "back over to Harlow", content.data.Quests.VAMPIRE_SLAYER, 20, Location.create(3224, 3401, 0), 1917, false);
                } catch (Throwable t) { System.out.println("[TestQF5] failed: " + t); t.printStackTrace(System.out); }
                return true;
            }
        });
    }

    static void go(String slug, String stepName, content.data.Quests q, int stage, Location at, int item, boolean wear) {
        final QuestScript s = QuestBook.SCRIPTS.get(slug);
        int idx = -1;
        for (int i = 0; i < s.steps.size(); i++) if (s.steps.get(i).name.equals(stepName)) idx = i;
        final int from = idx;
        final AIPlayer bot = new AIPlayer(at);
        try { bot.getInventory().clear(); bot.getBank().clear(); bot.getEquipment().clear(); } catch (Throwable ignored) { }
        SmartAdventurers.finishTutorial(bot);
        QuestBook.q(bot).test = true;
        Ledger.Wallet.give(bot, 5_000);
        bot.getQuestRepository().setStageNonmonotonic(bot.getQuestRepository().getQuest(q), stage);
        if (wear) bot.getEquipment().replace(new Item(item), 4); else bot.getInventory().add(new Item(item));
        bot.getProperties().setTeleportLocation(at);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t == 4) { System.out.println("[TestQF5] " + slug + " " + bot.getUsername() + " at " + bot.getLocation() + " starts at step " + from + " (" + stepName + ")"); QuestRunner.start(bot, s, from, true); return false; }
                if (t < 4) return false;
                QuestRunner r = QuestRunner.of(bot);
                if (r != null && r.idx <= from + 3 && t < 1200) return false;
                System.out.println("[TestQF5] RESULT " + slug + ": runner " + (r == null ? "gone (abandoned)" : "on step " + r.idx) + ", stage " + s.stage(bot) + ", at " + bot.getLocation() + ", " + t + " ticks");
                if (r != null) r.stopHere(true);
                return true;
            }
        });
    }
}
