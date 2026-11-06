package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.node.scenery.SceneryBuilder;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (quest fix 10-23). -Dtest.qf3=true: Goblin Diplomacy's "get 3 redberries" with one berry in the
 * pack (as from bot stock) and both redberry bushes south-east of Varrock picked bare and not growing back. Live: "get 3
 * redberries: short of Redberries (1/3)" x3. Prints the redberry count when the step ends.
 */
public final class TestQF3 implements StartupListener {
    static final Location BUSHES_AT = Location.create(3274, 3373, 0);

    @Override public void startup() {
        if (!Boolean.getBoolean("test.qf3")) return;
        System.out.println("[TestQF3] startup");
        GameWorld.getPulser().submit(new Pulse(40) {
            @Override public boolean pulse() {
                try { TestQF3.run(); } catch (Throwable t) { System.out.println("[TestQF3] failed: " + t); t.printStackTrace(System.out); }
                return true;
            }
        });
    }

    static void run() {
        final QuestScript s = QuestBook.SCRIPTS.get("goblindiplomacy");
        int idx = -1;
        for (int i = 0; i < s.steps.size(); i++) if (s.steps.get(i).name.equals("get 3 redberries")) idx = i;
        final int redIdx = idx;
        final AIPlayer bot = new AIPlayer(Location.create(3270, 3375, 0));
        try { bot.getInventory().clear(); bot.getBank().clear(); } catch (Throwable ignored) { }
        SmartAdventurers.finishTutorial(bot);
        QuestBook.q(bot).test = true;
        Ledger.Wallet.give(bot, 5_000);
        bot.getInventory().add(new Item(1951, 1));
        bot.getProperties().setTeleportLocation(Location.create(3270, 3375, 0));
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; boolean started;
            @Override public boolean pulse() {
                t++;
                if (t == 5) {
                    int n = 0;
                    for (int dx = -8; dx <= 8; dx++) for (int dy = -8; dy <= 8; dy++) for (int id : new int[]{23628, 23629}) {
                        Scenery b = core.game.world.map.RegionManager.getObject(0, BUSHES_AT.getX() + dx, BUSHES_AT.getY() + dy, id);
                        if (b != null && b.isActive() && b.getId() == id) { SceneryBuilder.replace(b, new Scenery(23630, b.getLocation(), b.getType(), b.getRotation()), true, true); n++; }
                    }
                    System.out.println("[TestQF3] " + bot.getUsername() + ": " + n + " bushes picked bare for good; 1 redberry in the pack; step " + redIdx);
                    QuestRunner.start(bot, s, redIdx, true);
                    started = true;
                    return false;
                }
                if (!started) return false;
                QuestRunner r = QuestRunner.of(bot);
                if (r != null && r.idx <= redIdx && t < 3000) return false;
                System.out.println("[TestQF3] RESULT: redberries in pack " + bot.getInventory().getAmount(1951) + ", runner " + (r == null ? "gone (abandoned)" : "on step " + r.idx) + ", at " + bot.getLocation() + ", " + t + " ticks");
                if (r != null) r.stopHere(true);
                return true;
            }
        });
    }
}
