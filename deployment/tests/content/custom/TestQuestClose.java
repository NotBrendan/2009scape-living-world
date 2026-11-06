package content.custom;

import core.api.StartupListener;
import core.game.interaction.Interaction;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.RegionManager;

/**
 * Trial only (-Dtest.questclose=true, with -Dquests.test=...): the live early closes (Rain293, Mrnl274) all had the talk-to
 * interaction still registered when the NPC's first screen showed. Once the quest bot's first dialogue is open, give it a
 * persistent interaction script (so scripts.getActiveScript() stays non-null) and report whether the dialogue survives.
 */
public final class TestQuestClose implements StartupListener {
    @Override public void startup() {
        if (!Boolean.getBoolean("test.questclose")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            boolean injected, wired; int watch;
            @Override public boolean pulse() {
                try {
                    if (QuestRunner.ACTIVE.isEmpty()) return false;
                    QuestRunner r = QuestRunner.ACTIVE.values().iterator().next();
                    Player bot = r.bot;
                    if (!wired) { // live quest bots run inside the server's bot pulse with their Brain; the trial bot doesn't
                        SmartAdventurers.Brain b = new SmartAdventurers.Brain();
                        b.nextThink = Long.MAX_VALUE;
                        SmartAdventurers.BRAINS.put(bot.getUsername().toLowerCase(java.util.Locale.ROOT), b);
                        new core.game.bots.GeneralBotCreator(b, (core.game.bots.AIPlayer) bot);
                        QuestRunner.class.getName();
                        try { QuestRunner.class.getDeclaredMethod("holdDialogues", Player.class).invoke(null, bot); System.out.println("[TestQuestClose] (this build holds dialogues for questers)"); } catch (NoSuchMethodException e) { System.out.println("[TestQuestClose] (old build: no dialogue hold)"); }
                        wired = true;
                        System.out.println("[TestQuestClose] bot wired to a Brain in the server bot pulse, endDialogue " + b.endDialogue);
                    }
                    if (!injected) {
                        if (bot.getDialogueInterpreter().getDialogue() == null) return false;
                        NPC n = null; double best = 99;
                        for (NPC x : RegionManager.getLocalNpcs(bot, 3)) { double d = x.getLocation().getDistance(bot.getLocation()); if (d < best) { best = d; n = x; } }
                        if (n == null) { System.out.println("[TestQuestClose] no npc next to the bot"); return true; }
                        bot.scripts.setInteractionScript(n, new Interaction((p, node, state) -> false, -1, true));
                        injected = true;
                        System.out.println("[TestQuestClose] dialogue open (" + bot.getDialogueInterpreter().getDialogue().getClass().getSimpleName() + "), lingering interaction on " + n.getName() + ": active script " + (bot.scripts.getActiveScript() != null));
                        return false;
                    }
                    if (++watch == 6) {
                        boolean open = bot.getDialogueInterpreter().getDialogue() != null;
                        System.out.println("[TestQuestClose] 6 ticks later: dialogue " + (open ? "still open or moved on" : "CLOSED") + ", step " + r.idx + ", active script " + (bot.scripts.getActiveScript() != null));
                    }
                    if (watch >= 40) { bot.scripts.reset(); System.out.println("[TestQuestClose] DONE, step " + r.idx); return true; }
                } catch (Throwable t) { System.out.println("[TestQuestClose] error " + t); t.printStackTrace(); return true; }
                return false;
            }
        });
    }
}
