package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only, never shipped (P45). -Dtest.p45ali=true: one bot at Leela at stage 30 with the disguise (blonde wig in slot 0,
 * paste, skirt), 3 beers and a rope, the rest of the pack filled with logs and its coins in the bank, as the soak bots that
 * failed "give the prince the disguise". The 15-coin Collect calls makeRoom(p, 15). Before the fix the wig is banked and the
 * prince says the disguise is incomplete; with -Dquest.princeAliFix=true it must reach stage 60. RESULT PASS = stage >= 60.
 * Cap: -Dtest.p45ali.min (20).
 */
public final class TestP45AliTrial implements StartupListener {
    static final String T = "[TestP45AliTrial] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p45ali")) return;
        System.out.println(T + "armed fix=" + QuestPrinceAliRescue.FIX);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { TestP45AliTrial.go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static void go() {
        QuestScript s = QuestBook.SCRIPTS.get("princeali");
        if (s == null) { System.out.println(T + "RESULT FAIL no script"); System.out.println(T + "DONE"); return; }
        final AIPlayer bot = new AIPlayer(QuestPrinceAliRescue.LEELA_AT);
        try { bot.getInventory().clear(); bot.getBank().clear(); bot.getEquipment().clear(); } catch (Throwable ignored) { }
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, 50); bot.getSkills().setLevel(sk, 50); }
        bot.getSkills().updateCombatLevel();
        SmartAdventurers.finishTutorial(bot);
        QuestBook.q(bot).test = true;
        bot.getQuestRepository().setStageNonmonotonic(bot.getQuestRepository().getQuest(QuestPrinceAliRescue.Q), 30);
        Ledger.Wallet.give(bot, 500);
        bot.getInventory().add(new Item(QuestPrinceAliRescue.YELLOW_WIG, 1));
        bot.getInventory().add(new Item(QuestPrinceAliRescue.PASTE, 1));
        bot.getInventory().add(new Item(QuestPrinceAliRescue.SKIRT, 1));
        bot.getInventory().add(new Item(QuestPrinceAliRescue.BEER, 3));
        bot.getInventory().add(new Item(QuestPrinceAliRescue.ROPE, 1));
        while (bot.getInventory().freeSlots() > 0) bot.getInventory().add(new Item(1511, 1)); // logs: not a quest item
        System.out.println(T + bot.getUsername() + " stage " + s.stage(bot) + " free " + bot.getInventory().freeSlots() + " bankCoins " + bot.getBank().getAmount(995));
        QuestRunner.start(bot, s, 0, true);
        final long capTicks = Integer.getInteger("test.p45ali.min", 20) * 100L;
        GameWorld.getPulser().submit(new Pulse(5) {
            int after, last = -1; long ticks; boolean wigBanked;
            @Override public boolean pulse() {
                ticks += 5;
                int st = s.stage(bot);
                if (st != last) { System.out.println(T + "stage " + last + " -> " + st + " at tick " + ticks + " " + bot.getLocation()); last = st; }
                if (!wigBanked && bot.getBank().getAmount(QuestPrinceAliRescue.YELLOW_WIG) > 0) { wigBanked = true; System.out.println(T + "wig banked at tick " + ticks); }
                boolean active = QuestRunner.active(bot);
                if (st >= 60) active = false;
                if (active && ticks < capTicks) return false;
                if (active) System.out.println(T + "cap reached (" + capTicks + " ticks), quest still running");
                else if (st < 60 && after++ < 3) return false;
                System.out.println(T + "RESULT " + (st >= 60 ? "PASS " : "FAIL ") + "fix=" + QuestPrinceAliRescue.FIX + " stage " + st + " wigBankedEver=" + wigBanked
                        + " wigPack=" + bot.getInventory().getAmount(QuestPrinceAliRescue.YELLOW_WIG) + " ticks=" + ticks);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
