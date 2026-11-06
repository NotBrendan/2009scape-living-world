package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/** Trial only (-Dtest.coinsweep=true, with -Dquests.test=...): coins left in a quester's pack when its quest is abandoned or stopped. */
public final class TestCoinSweep implements StartupListener {
    @Override public void startup() {
        if (!Boolean.getBoolean("test.coinsweep")) return;
        GameWorld.getPulser().submit(new Pulse(5) {
            int phase;
            @Override public boolean pulse() {
                try {
                    if (QuestRunner.ACTIVE.isEmpty()) return false;
                    QuestRunner r = QuestRunner.ACTIVE.values().iterator().next();
                    Player bot = r.bot;
                    int bank0 = bot.getBank().getAmount(995);
                    bot.getInventory().add(new Item(995, 300)); // as pocketCoins or a coin reward leaves them
                    if (phase == 0) { r.abandon("test abandon"); System.out.println("[TestCoinSweep] abandon: pack " + bot.getInventory().getAmount(995) + " coins, bank " + bank0 + " -> " + bot.getBank().getAmount(995)); }
                    else { r.stopHere(true); System.out.println("[TestCoinSweep] stopHere: pack " + bot.getInventory().getAmount(995) + " coins, bank " + bank0 + " -> " + bot.getBank().getAmount(995)); }
                    bot.getInventory().remove(new Item(995, bot.getInventory().getAmount(995)));
                    if (++phase == 1) { QuestRunner.start(bot, r.script, 0, true); return false; }
                } catch (Throwable t) { System.out.println("[TestCoinSweep] error " + t); t.printStackTrace(); }
                System.out.println("[TestCoinSweep] DONE");
                return true;
            }
        });
    }
}
