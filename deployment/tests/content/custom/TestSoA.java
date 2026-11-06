package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped. -Dtest.soa=pair: two fresh bots, one of each gang (name parity), start Shield of Arrav at once
 * and should meet at the museum. -Dtest.soa=pool: one fresh Phoenix bot alone, with one Black Arm half-certificate put in
 * the Arrav pool first (run with -Dquests.soaWaitMin=1): it should deposit its spare half and finish from the pool.
 */
public final class TestSoA implements StartupListener {
    @Override public void startup() {
        final String mode = System.getProperty("test.soa");
        if (mode == null) return;
        GameWorld.getPulser().submit(new Pulse(40) {
            @Override public boolean pulse() {
                try { TestSoA.run(mode); } catch (Throwable t) { System.out.println("[TestSoA] failed: " + t); t.printStackTrace(System.out); }
                return true;
            }
        });
    }

    static void run(String mode) {
        QuestScript s = QuestBook.SCRIPTS.get("shieldofarrav");
        if (s == null) { System.out.println("[TestSoA] no script"); return; }
        AIPlayer ph = null, bl = null;
        for (int i = 0; i < 12 && (ph == null || bl == null && !"pool".equals(mode)); i++) {
            AIPlayer b = new AIPlayer(Location.create(3222, 3218, 0));
            boolean even = Math.floorMod(b.getUsername().toLowerCase(java.util.Locale.ROOT).hashCode(), 2) == 0;
            if (even && ph == null) ph = b;
            else if (!even && bl == null && !"pool".equals(mode)) bl = b;
            else { try { b.clear(); } catch (Throwable ignored) { } }
        }
        if ("pool".equals(mode)) {
            QuestShieldOfArrav.Pool.deposit(null, QuestShieldOfArrav.BLACKARM_HALF);
            System.out.println("[TestSoA] pool seeded: " + QuestShieldOfArrav.Pool.count(QuestShieldOfArrav.BLACKARM_HALF) + " x 11174, wait " + QuestShieldOfArrav.WAIT_MS / 60_000 + " min");
        }
        if (ph != null) go(ph, s, QuestShieldOfArrav.RELDO_AT);
        if (bl != null) go(bl, s, QuestShieldOfArrav.CHARLIE_AT);
    }

    static void go(AIPlayer bot, QuestScript s, Location at) {
        try { bot.getInventory().clear(); bot.getBank().clear(); bot.getEquipment().clear(); } catch (Throwable ignored) { }
        int lvl = Integer.getInteger("quests.testLevel", 20);
        for (int sk : new int[]{Skills.MINING, Skills.WOODCUTTING, Skills.FISHING, Skills.COOKING, Skills.CRAFTING, Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, lvl); bot.getSkills().setLevel(sk, lvl); }
        bot.getSkills().updateCombatLevel();
        SmartAdventurers.finishTutorial(bot);
        QuestBook.q(bot).test = true;
        Ledger.Wallet.give(bot, Integer.getInteger("quests.testCoins", 5_000));
        bot.getProperties().setTeleportLocation(at);
        System.out.println("[TestSoA] " + bot.getUsername() + " (" + (QuestShieldOfArrav.phoenix(bot) ? "Phoenix" : "Black Arm") + ") starts at " + at);
        QuestRunner.start(bot, s, 0, true);
        QuestBook.resultLine(bot, s);
        final AIPlayer tb = bot;
        GameWorld.getPulser().submit(new Pulse(5) {
            int after;
            @Override public boolean pulse() {
                if (QuestRunner.active(tb) || after++ < 3) return false;
                System.out.println("[TestSoA] RESULT " + tb.getUsername() + " (" + (QuestShieldOfArrav.phoenix(tb) ? "Phoenix" : "Black Arm") + "): complete=" + s.complete(tb) + " stage " + s.stage(tb) + ", pack 11173 x" + tb.getInventory().getAmount(11173) + " 11174 x" + tb.getInventory().getAmount(11174) + " 769 x" + tb.getInventory().getAmount(769) + ", pool 11173 " + QuestShieldOfArrav.Pool.count(11173) + " 11174 " + QuestShieldOfArrav.Pool.count(11174));
                return true;
            }
        });
    }
}
