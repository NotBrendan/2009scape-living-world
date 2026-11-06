package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.request.trade.TradeModule;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.e1=true): a 300-coal callout, a "human" who haggled to 1, and a real trade window. */
public final class TestE1 implements StartupListener {
    static void log(String s) { System.out.println("[TestE1] " + s); }
    static int win(TradeModule m) { return m.getContainer().getAmount(453) + m.getContainer().getAmount(454); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.e1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer seller, human; Deals.Offer o; int unit;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 60) {
                        Location at = Location.create(3185, 3436, 0); // Varrock west bank
                        seller = new AIPlayer(at); human = new AIPlayer(at.transform(1, 0, 0));
                        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(human, false);
                        seller.getBank().add(new Item(453, 300));
                        human.getInventory().add(new Item(995, 100_000));
                        unit = 150;
                        o = new Deals.Offer(seller, 453, 300, unit);
                        o.holdUntil = System.currentTimeMillis() + 600_000;
                        Deals.OFFERS.add(o);
                        log("seller " + seller.getUsername() + " bank coal " + seller.getBank().getAmount(453) + ", human " + human.getUsername() + " artificial=" + human.isArtificial());
                        TradeModule.Companion.extend(human, seller);
                        TradeModule.getExtension(human).open(human, seller);
                        log("window open: seller ext " + (TradeModule.getExtension(seller) != null));
                    }
                    if (t == 66) { // the bot has put the lot up for the whole callout; now the deal shrinks to 1 (what a haggle did before the freeze)
                        TradeModule mine = TradeModule.getExtension(seller);
                        log("in window before haggle: " + win(mine));
                        boolean frozen = Deals.haggle(o, human, "i'll take 1 coal", true);
                        long[] h = o.hag.get(human.getUsername().toLowerCase());
                        log("haggle during window consumed=" + frozen + ", hag state " + (h == null ? "none" : h[0] + " gp / " + h[1]));
                        long[] s = o.hag(human); s[0] = unit; s[1] = 1; // force the old exploit state directly
                        TradeModule hm = TradeModule.getExtension(human);
                        int slot = human.getInventory().getSlot(new Item(995));
                        hm.getContainer().offer(slot, unit);
                        log("human puts up " + hm.getContainer().getAmount(995) + " coins for 1 coal");
                    }
                    if (t == 72) {
                        TradeModule mine = TradeModule.getExtension(seller);
                        log("after fix tick: in window " + (mine == null ? "closed" : win(mine) + ", bot accepted " + mine.isAccepted() + ", stage " + mine.getStage()));
                        if (mine != null && mine.isAccepted()) TradeModule.getExtension(human).setAccepted(true, true);
                    }
                    if (t == 78) {
                        TradeModule mine = TradeModule.getExtension(seller);
                        log("second screen: " + (mine == null ? "closed" : "stage " + mine.getStage() + ", bot accepted " + mine.isAccepted()));
                        if (mine != null && mine.isAccepted()) TradeModule.getExtension(human).setAccepted(true, true);
                    }
                    if (t == 86) {
                        log("RESULT human coal(noted 454)=" + human.getInventory().getAmount(454) + " coal(453)=" + human.getInventory().getAmount(453) + ", human coins " + human.getInventory().getAmount(995)
                                + "; seller bank coal " + seller.getBank().getAmount(453) + ", seller inv notes " + seller.getInventory().getAmount(454) + ", seller coins " + seller.getInventory().getAmount(995) + ", offer qty " + o.qty + " done " + o.done);
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); return true; }
                return false;
            }
        });
    }
}
