package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only, never shipped (P47, copied from TestP46GoblinTrial). -Dtest.p47gob=true: the same bot as P46 (Goblin Diplomacy
 * stage 10 set with setStageNonmonotonic, so varp 62 stays 0; orange and blue mail in the pack, logs, one plain mail banked),
 * run to the end. Without -Dquest.goblinFix the generals read varp 0 as NORMAL, the first showing takes the plain mail and the
 * brown cutscene step stalls at stage 30; with it the varp is set from the stage before each showing.
 * RESULT PASS = stage 100 and neither dyed mail ever banked. Cap: -Dtest.p47gob.min (30).
 */
public final class TestP47GoblinTrial implements StartupListener {
    static final String T = "[TestP47GoblinTrial] ";
    static final int G = QuestGoblinDiplomacy.ORANGE_MAIL, B = QuestGoblinDiplomacy.BLUE_MAIL;

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p47gob")) return;
        System.out.println(T + "armed keep=" + QuestRunner.KEEP_QUEST_ITEMS + " goblinFix=" + QuestGoblinDiplomacy.FIX);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { TestP47GoblinTrial.go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static void go() {
        QuestScript s = QuestBook.SCRIPTS.get("goblindiplomacy");
        if (s == null) { System.out.println(T + "RESULT FAIL no script"); System.out.println(T + "DONE"); return; }
        int idx = -1;
        for (int i = 0; i < s.steps.size(); i++) if (s.steps.get(i).name.equals("get one more goblin mail")) { idx = i; break; }
        if (idx < 0) { System.out.println(T + "RESULT FAIL no step"); System.out.println(T + "DONE"); return; }
        final AIPlayer bot = new AIPlayer(QuestGoblinDiplomacy.GENERALS_AT);
        try { bot.getInventory().clear(); bot.getBank().clear(); bot.getEquipment().clear(); } catch (Throwable ignored) { }
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, 50); bot.getSkills().setLevel(sk, 50); }
        bot.getSkills().updateCombatLevel();
        SmartAdventurers.finishTutorial(bot);
        QuestBook.q(bot).test = true;
        bot.getQuestRepository().setStageNonmonotonic(bot.getQuestRepository().getQuest(QuestGoblinDiplomacy.Q), 10);
        Ledger.Wallet.give(bot, 500);
        bot.getInventory().add(new Item(G, 1));
        bot.getInventory().add(new Item(B, 1));
        while (bot.getInventory().freeSlots() > 0) bot.getInventory().add(new Item(1511, 1)); // logs: not a quest item
        bot.getBank().add(new Item(QuestGoblinDiplomacy.MAIL, 1));
        System.out.println(T + bot.getUsername() + " stage " + s.stage(bot) + " step " + idx + " free " + bot.getInventory().freeSlots() + " bankMail " + bot.getBank().getAmount(QuestGoblinDiplomacy.MAIL));
        QuestRunner.start(bot, s, idx, true);
        final long capTicks = Integer.getInteger("test.p47gob.min", 30) * 100L;
        GameWorld.getPulser().submit(new Pulse(5) {
            int after, last = -1; long ticks; boolean orangeBanked, blueBanked;
            @Override public boolean pulse() {
                ticks += 5;
                int st = s.stage(bot);
                if (st != last) { System.out.println(T + "stage " + last + " -> " + st + " at tick " + ticks + " " + bot.getLocation() + " varp62 " + core.api.ContentAPIKt.getVarp(bot, 62)); last = st; }
                if (!orangeBanked && bot.getBank().getAmount(G) > 0) { orangeBanked = true; System.out.println(T + "orange mail banked at tick " + ticks); }
                if (!blueBanked && bot.getBank().getAmount(B) > 0) { blueBanked = true; System.out.println(T + "blue mail banked at tick " + ticks); }
                boolean active = QuestRunner.active(bot);
                if (st >= 100) active = false;
                if (active && ticks < capTicks) return false;
                if (active) System.out.println(T + "cap reached (" + capTicks + " ticks), quest still running");
                else if (st < 30 && after++ < 3) return false;
                boolean pass = st >= 100 && !orangeBanked && !blueBanked;
                System.out.println(T + "RESULT " + (pass ? "PASS " : "FAIL ") + "fix=" + QuestGoblinDiplomacy.FIX + " keep=" + QuestRunner.KEEP_QUEST_ITEMS + " stage " + st + " orangeBankedEver=" + orangeBanked
                        + " blueBankedEver=" + blueBanked + " mailPack=" + bot.getInventory().getAmount(QuestGoblinDiplomacy.MAIL) + " ticks=" + ticks);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
