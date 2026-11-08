package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (P38 L9, audit rank 6 / Q-23). -Dtest.arravq23=true: one fresh Black Arm bot (name parity),
 * combat ~40, key 759 in the pack, no crossbows, -Dquests.noStock=true so it cannot buy them: it must take the key path,
 * rob the weapon store itself (solo Weaponsmaster fight), reach stage 70 with Katrine, take the Black Arm shield half and
 * trade it at the museum. One Phoenix half-certificate is put in the Arrav pool first (run with -Dquests.soaWaitMin=1) so
 * the bot can finish without a partner. Prints stage changes, then RESULT PASS/FAIL. Cap: -Dtest.arravq23.min (25).
 */
public final class TestArravQ23 implements StartupListener {
    static final String T = "[TestArravQ23] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.arravq23")) return;
        System.out.println(T + "armed");
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                // not run(): inside a Pulse that resolves to Pulse.run (endless recursion)
                try { TestArravQ23.go(); } catch (Throwable t) { System.out.println(T + "failed: " + t); t.printStackTrace(System.out); }
                return true;
            }
        });
    }

    static void go() {
        QuestScript s = QuestBook.SCRIPTS.get("shieldofarrav");
        if (s == null) { System.out.println(T + "RESULT FAIL no script"); return; }
        AIPlayer bl = null;
        for (int i = 0; i < 12 && bl == null; i++) {
            AIPlayer b = new AIPlayer(Location.create(3222, 3218, 0));
            if (Math.floorMod(b.getUsername().toLowerCase(java.util.Locale.ROOT).hashCode(), 2) != 0) bl = b;
            else { try { b.clear(); } catch (Throwable ignored) { } }
        }
        if (bl == null) { System.out.println(T + "RESULT FAIL no Black Arm bot"); return; }
        final AIPlayer bot = bl;
        try { bot.getInventory().clear(); bot.getBank().clear(); bot.getEquipment().clear(); } catch (Throwable ignored) { }
        int lvl = Integer.getInteger("test.arravq23.level", 40);
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, lvl); bot.getSkills().setLevel(sk, lvl); }
        bot.getSkills().updateCombatLevel();
        SmartAdventurers.finishTutorial(bot);
        QuestBook.q(bot).test = true;
        Ledger.Wallet.give(bot, 5_000);
        bot.getInventory().add(new Item(QuestShieldOfArrav.KEY, 1));
        bot.getInventory().add(new Item(1333, 1)); // rune scimitar: the fight is the point, not the gear
        QuestShieldOfArrav.Pool.deposit(null, QuestShieldOfArrav.PHOENIX_HALF);
        System.out.println(T + bot.getUsername() + " gang=" + (QuestShieldOfArrav.phoenix(bot) ? "Phoenix" : "Black Arm") + " cb=" + bot.getProperties().getCurrentCombatLevel()
                + " key759=" + bot.getInventory().getAmount(759) + " noStock=" + Boolean.getBoolean("quests.noStock") + " whyNot=" + QuestShieldOfArrav.whyNot(bot)
                + " pool11173=" + QuestShieldOfArrav.Pool.count(QuestShieldOfArrav.PHOENIX_HALF));
        bot.getProperties().setTeleportLocation(QuestShieldOfArrav.CHARLIE_AT);
        QuestRunner.start(bot, s, 0, true);
        final long capTicks = Integer.getInteger("test.arravq23.min", 25) * 100L;
        GameWorld.getPulser().submit(new Pulse(5) {
            int after, last = -1; long ticks; boolean bows, fought;
            @Override public boolean pulse() {
                ticks += 5;
                int st = s.stage(bot);
                if (st != last) { System.out.println(T + "stage " + last + " -> " + st + " at tick " + ticks + " " + bot.getLocation()); last = st; }
                if (!bows && bot.getInventory().getAmount(QuestShieldOfArrav.CROSSBOW) >= 2) { bows = true; System.out.println(T + "2 crossbows in pack at tick " + ticks); }
                if (!fought && bot.inCombat()) { fought = true; System.out.println(T + "first combat at tick " + ticks + " " + bot.getLocation()); }
                boolean active = QuestRunner.active(bot);
                if (active && ticks < capTicks) return false;
                if (active) System.out.println(T + "cap reached (" + capTicks + " ticks), quest still running");
                else if (after++ < 3) return false;
                boolean complete = s.complete(bot);
                String info = "complete=" + complete + " stage " + s.stage(bot) + " gang=" + (QuestShieldOfArrav.phoenix(bot) ? "Phoenix" : "Black Arm")
                        + " key759=" + QuestShieldOfArrav.count(bot, 759) + " 767=" + QuestShieldOfArrav.count(bot, 767) + " 765=" + QuestShieldOfArrav.count(bot, 765)
                        + " 11174=" + QuestShieldOfArrav.count(bot, 11174) + " 11173=" + QuestShieldOfArrav.count(bot, 11173) + " 769=" + QuestShieldOfArrav.count(bot, 769)
                        + " crossbowsSeen=" + bows + " fought=" + fought + " ticks=" + ticks;
                System.out.println(T + "RESULT " + (complete || s.stage(bot) >= 100 ? "PASS " : "FAIL ") + info);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
