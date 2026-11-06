package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (quest fix 10-23). -Dtest.qf6=true: a Black Arm bot that already joined (stage 70,
 * black-arm-gang, the mission) comes back to Shield of Arrav from Varrock square, as a bot that gave up after Katrine
 * does. Live: "through the Black Arm door: Open on [2399] didn't do it (5 clicks)". Prints the result when the run ends
 * or it reaches the museum.
 */
public final class TestQF6 implements StartupListener {
    @Override public void startup() {
        if (!Boolean.getBoolean("test.qf6")) return;
        System.out.println("[TestQF6] startup");
        GameWorld.getPulser().submit(new Pulse(40) {
            @Override public boolean pulse() {
                try { TestQF6.run(); } catch (Throwable t) { System.out.println("[TestQF6] failed: " + t); t.printStackTrace(System.out); }
                return true;
            }
        });
    }

    static void run() {
        final QuestScript s = QuestBook.SCRIPTS.get("shieldofarrav");
        AIPlayer bot = null;
        for (int i = 0; i < 12 && bot == null; i++) {
            AIPlayer b = new AIPlayer(Location.create(3212, 3428, 0));
            if (Math.floorMod(b.getUsername().toLowerCase(java.util.Locale.ROOT).hashCode(), 2) == 1) bot = b; else { try { b.clear(); } catch (Throwable ignored) { } }
        }
        final AIPlayer tb = bot;
        try { tb.getInventory().clear(); tb.getBank().clear(); } catch (Throwable ignored) { }
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { tb.getSkills().setStaticLevel(sk, 50); tb.getSkills().setLevel(sk, 50); }
        tb.getSkills().updateCombatLevel();
        SmartAdventurers.finishTutorial(tb);
        QuestBook.q(tb).test = true;
        Ledger.Wallet.give(tb, 5_000);
        tb.getQuestRepository().setStageNonmonotonic(tb.getQuestRepository().getQuest(content.data.Quests.SHIELD_OF_ARRAV), 70);
        tb.setAttribute("/save:black-arm-gang", true);
        tb.setAttribute("/save:blackarm-mission", true);
        tb.getProperties().setTeleportLocation(Location.create(3212, 3428, 0));
        System.out.println("[TestQF6] " + tb.getUsername() + " stage " + s.stage(tb) + ", Black Arm " + !QuestShieldOfArrav.phoenix(tb) + ", starts at Varrock square");
        QuestRunner.start(tb, s, 0, true);
        GameWorld.getPulser().submit(new Pulse(5) {
            int after;
            @Override public boolean pulse() {
                boolean half = tb.getInventory().getAmount(QuestShieldOfArrav.BLACKARM_SHIELD) > 0;
                if (QuestRunner.active(tb) && !half && after++ < 400) return false;
                System.out.println("[TestQF6] RESULT " + tb.getUsername() + ": active " + QuestRunner.active(tb) + ", Black Arm shield half in pack " + half + ", at " + tb.getLocation() + ", stage " + s.stage(tb));
                QuestRunner r = QuestRunner.of(tb); if (r != null) r.stopHere(true);
                return true;
            }
        });
    }
}
