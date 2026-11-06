package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.ge.GEDB;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

/**
 * Trial only (-Dtest.e2=true): two fake "human" accounts (AIPlayers flagged non-artificial with their own uids).
 *  1. alt-to-alt: A sells 50 coal to B on the GE        -> the coal supplier must NOT be paid (E-2)
 *  2. B buys 30 coal with no player seller left          -> filled from bot stock, the supplier IS paid for 30 (E-2)
 *  3. A sells 20 of a wanted item to B                    -> no bounty (E-3)
 *  4. A sells 10 of that item, aged, to the bot buyer     -> bounty paid (E-3)
 */
public final class TestE2 implements StartupListener {
    static void log(String s) { System.out.println("[TestE2] " + s); }

    static AIPlayer human(Location at, int uid) throws Exception {
        AIPlayer p = new AIPlayer(at);
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(p, false);
        p.getDetails().accountInfo.setUid(uid);
        Repository.INSTANCE.getUid_map().put(uid, p);
        return p;
    }

    static void offer(Player p, int slot, int id, int qty, int price, boolean sell) {
        GrandExchangeOffer o = new GrandExchangeOffer();
        o.setItemID(id); o.setAmount(qty); o.setOfferedValue(price); o.setSell(sell); o.setIndex(slot); o.setPlayer(p);
        boolean ok = GrandExchange.Companion.dispatch(p, o);
        log((sell ? "SELL " : "BUY ") + qty + " x " + MarketBrain.name(id) + " at " + price + " by " + p.getUsername() + " dispatched=" + ok);
    }

    static void drain() { try { java.lang.reflect.Method d = MarketBrain.class.getDeclaredMethod("drainFills"); d.setAccessible(true); d.invoke(null); } catch (NoSuchMethodException e) { } catch (Throwable e) { log("drain " + e); } }
    static void inject() { java.util.Map<Integer, Double> m = new java.util.HashMap<>(MarketBrain.bounties); m.put(440, 50.0); MarketBrain.bounties = m; }
    static long earned(String s) { synchronized (Ledger.class) { return Ledger.EARNED.getOrDefault(s, 0L); } }
    static long owed(String s) { return Ledger.Wallet.OFFLINE.getOrDefault(s.toLowerCase(), 0L); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.e2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 180; int t; AIPlayer a, b; final String sup = "TestSupplierE2"; int wanted; int guideCoal;
            void state(String when) {
                log(when + ": supplier earned " + earned(sup) + " (credited offline " + owed(sup) + "), coal lot waiting " + Ledger.waiting(sup, 453) + ", bot coal stock " + GrandExchange.Companion.getBotstockForId(453) + ", bounty paidToday " + Bounty.paidToday);
            }
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0 + 0) { // after MarketBrain's first refresh (90 s) has primed its maps
                        Location at = Location.create(3165, 3487, 0);
                        a = human(at, 900001); b = human(at.transform(1, 0, 0), 900002);
                        guideCoal = MarketBrain.value(453);
                        synchronized (Ledger.class) { Ledger.QUEUES.remove(453); } // the test supplier's lot is the only one
                        // a supplier lot of 100 coal in bot stock, owned by a name that is offline (credits land in OFFLINE)
                        AIPlayer s = new AIPlayer(at.transform(2, 0, 0));
                        Ledger.supply(s, 453, 100);
                        synchronized (Ledger.class) { for (Object e : Ledger.QUEUES.get(453)) { java.lang.reflect.Field sf = e.getClass().getDeclaredField("supplier"); sf.setAccessible(true); if (s.getUsername().equals(sf.get(e))) sf.set(e, sup); } }
                        s.clear();
                        wanted = 440; inject(); // iron ore, forced onto the wanted board
                        log("humans " + a.getUsername() + "/" + b.getUsername() + ", coal guide " + guideCoal + ", wanted item " + (wanted > 0 ? MarketBrain.name(wanted) + " guide " + MarketBrain.value(wanted) : "none") + ", artisans " + Artisans.ARTISAN_BOTS.size() + ", wanted(iron) " + Bounty.wanted(440));
                    }
                    if (t == T0 + 10) { state("before"); offer(a, 0, 453, 50, guideCoal, true); }
                    if (t == T0 + 15) offer(b, 0, 453, 50, guideCoal, false);
                    if (t == T0 + 25) offer(a, 1, wanted, 20, MarketBrain.value(wanted), true);
                    if (t == T0 + 30) offer(b, 1, wanted, 20, MarketBrain.value(wanted), false);
                    if (t == T0 + 40) { MarketBrain.refresh(); inject(); drain(); state("after round 1 (primes the old 7-day diff)"); }
                    if (t == T0 + 45) offer(a, 2, 453, 50, guideCoal, true);
                    if (t == T0 + 48) offer(b, 2, 453, 50, guideCoal, false);
                    if (t == T0 + 50) offer(a, 3, wanted, 20, MarketBrain.value(wanted), true);
                    if (t == T0 + 53) offer(b, 3, wanted, 20, MarketBrain.value(wanted), false);
                    if (t == T0 + 60) { MarketBrain.refresh(); inject(); drain(); }
                    if (t == T0 + 70) { state("after alt-to-alt round 2 (expect earned 0, lot 100, bounty 0)"); offer(b, 4, 453, 30, guideCoal * 2, false); }
                    if (t == T0 + 85) { MarketBrain.refresh(); inject(); drain(); }
                    if (t == T0 + 95) { state("after buying 30 from bot stock (expect lot 70, paid 30 x guide)"); offer(a, 4, wanted, 10, MarketBrain.value(wanted), true); }
                    if (t == T0 + 105) { // age the offer past the bot buyer's 5 minutes and run the buyer now
                        GEDB.run(conn -> { try { conn.createStatement().executeUpdate("UPDATE player_offers SET time_stamp = time_stamp - 600000 WHERE player_uid = 900001 AND is_sale = 1"); } catch (Throwable e) { log("age: " + e); } return kotlin.Unit.INSTANCE; });
                        inject();
                        java.lang.reflect.Method bp = PrivateWorldAddons.class.getDeclaredMethod("buyPlayerOffers"); bp.setAccessible(true); bp.invoke(null);
                    }
                    if (t == T0 + 110) { MarketBrain.refresh(); inject(); }
                    if (t == T0 + 120) { state("RESULT after the bot buyer (expect a bounty only now)"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); return true; }
                return false;
            }
        });
    }
}
