package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.cr7=true): combat review 10-23 #7. What Brain.buyFood buys: a 50k bot with 85 hp for black demons
 * (level 172), hill giants (28) and goblins (5); and a 400 gp bot for black demons. Before: trout every time.
 */
public final class TestCR7 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR7] " + s); }
    static final int[] FOODS = {333, 329, 361, 379, 373, 7946, 385};
    static String bankFood(AIPlayer p) { StringBuilder s = new StringBuilder(); for (int f : FOODS) if (p.getBank().getAmount(f) > 0) s.append(p.getBank().getAmount(f)).append(" x ").append(MarketBrain.name(f)).append(' '); return s.length() == 0 ? "none" : s.toString().trim(); }
    static void clearFood(AIPlayer p) { for (int f : FOODS) { int n = p.getBank().getAmount(f); if (n > 0) p.getBank().remove(new Item(f, n)); } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr7")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 260) return false;
                try {
                    AIPlayer rich = TestCR1.bot(null, 85, 80, 80, 80), poor = TestCR1.bot(null, 85, 80, 80, 80);
                    Ledger.Wallet.give(rich, 50_000); Ledger.Wallet.give(poor, 400);
                    SmartAdventurers.Brain br = TestCR1.rig(rich, true), bp = TestCR1.rig(poor, true);
                    log("prices: trout " + MarketBrain.value(333) + ", salmon " + MarketBrain.value(329) + ", lobster " + MarketBrain.value(379) + ", swordfish " + MarketBrain.value(373) + ", shark " + MarketBrain.value(385));
                    for (int lvl : new int[]{172, 28, 5}) {
                        clearFood(rich); long c0 = Ledger.Wallet.coins(rich);
                        br.buyFood(lvl);
                        log("RESULT 50k bot, 85 hp, target level " + lvl + ": bought " + bankFood(rich) + " for " + (c0 - Ledger.Wallet.coins(rich)) + " gp");
                    }
                    long c0 = Ledger.Wallet.coins(poor); bp.buyFood(172);
                    log("RESULT 400 gp bot, 85 hp, target level 172: bought " + bankFood(poor) + " for " + (c0 - Ledger.Wallet.coins(poor)) + " gp");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
