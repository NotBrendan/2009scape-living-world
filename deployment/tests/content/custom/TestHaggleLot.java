package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.hagglelot=true): part bids and "the lot" in both orders; what does the seller agree to? */
public final class TestHaggleLot implements StartupListener {
    static final class Human extends AIPlayer { Human(Location l) { super(l); } @Override public void sendMessage(String m) { } }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.hagglelot")) return;
        GameWorld.getPulser().submit(new Pulse(80) {
            @Override public boolean pulse() {
                try {
                    String[][] seqs = {{"i'll take 10", "how much for the lot"}, {"i'll take 10", "1500 for the lot"}, {"how much for the lot", "i'll take 10"}, {"1500 for the lot", "i'll take 10"}, {"i'll take 10", "deal"}, {"i'll take 10", "actually the lot"}};
                    for (String[] seq : seqs) {
                        AIPlayer seller = new AIPlayer(Location.create(3185, 3436, 0)); Human h = new Human(Location.create(3186, 3436, 0));
                        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(h, false);
                        seller.getBank().add(new Item(453, 100));
                        Deals.Offer o = new Deals.Offer(seller, 453, 100, 25); o.holdUntil = System.currentTimeMillis() + 600_000; Deals.OFFERS.add(o);
                        StringBuilder sb = new StringBuilder();
                        for (String line : seq) { Deals.haggle(o, h, line, true); long[] st = o.hag.get(h.getUsername().toLowerCase()); sb.append("'").append(line).append("' -> ").append(st == null ? "none" : "due " + o.dueFrom(h) + " for " + o.qtyFor(h) + (st[3] > 0 ? " (counter " + st[3] + " for " + st[4] + ")" : "")).append("; "); }
                        Deals.OFFERS.remove(o);
                        System.out.println("[TestHaggleLot] " + sb);
                    }
                } catch (Throwable t) { System.out.println("[TestHaggleLot] error " + t); t.printStackTrace(); }
                System.out.println("[TestHaggleLot] DONE");
                return true;
            }
        });
    }
}
