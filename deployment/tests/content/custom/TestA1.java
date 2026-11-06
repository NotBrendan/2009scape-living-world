package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.ge.GEDB;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

/**
 * Trial only (-Dtest.a1=true): the bot buyer and the bounty board (morning review 10-23, A1). Two fake "human" accounts.
 *  1. ordinary item: player sells at 100% and at 90% of the bot reference -> what does the bot buyer take?
 *  2. bounty item (iron ore, injected as wanted): player sells at 100% of the reference -> bought? bounty paid?
 *  3. the loop: a player buys the bounty item from GE bot stock at guide -> filled? and what would the round trip pay?
 *  4. Deals: a bot's iron ore haul at the bank -> called out? A player asks a bot for iron ore -> sold?
 *  5. a travelling trader with iron ore in its bank -> does it go into the haul for sale?
 */
public final class TestA1 implements StartupListener {
    static void log(String s) { System.out.println("[TestA1] " + s); }
    static final int WANTED = 440;
    static final boolean NEW = hasField("BUYER_WANTED_PERCENT");
    static boolean hasField(String n) { try { Bounty.class.getDeclaredField(n); return true; } catch (Throwable e) { return false; } }

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
    static void inject() {
        java.util.Map<Integer, Double> m = new java.util.HashMap<>(MarketBrain.bounties); m.put(WANTED, 50.0); MarketBrain.bounties = m;
        try { java.lang.reflect.Field f = Bounty.class.getDeclaredField("wantedAt"); f.setAccessible(true); f.setLong(null, 0L); } catch (NoSuchFieldException e) { } catch (Throwable e) { log("cache " + e); }
    }
    static int done(int uid, int slot) {
        final int[] r = {-1};
        GEDB.run(conn -> { try { java.sql.ResultSet rs = conn.createStatement().executeQuery("SELECT amount_complete FROM player_offers WHERE player_uid = " + uid + " AND slot_index = " + slot + " ORDER BY uid DESC LIMIT 1"); if (rs.next()) r[0] = rs.getInt(1); } catch (Throwable e) { log("query " + e); } return kotlin.Unit.INSTANCE; });
        return r[0];
    }
    static void runBuyer() throws Exception {
        GEDB.run(conn -> { try { conn.createStatement().executeUpdate("UPDATE player_offers SET time_stamp = time_stamp - 600000 WHERE is_sale = 1"); } catch (Throwable e) { log("age " + e); } return kotlin.Unit.INSTANCE; });
        java.lang.reflect.Method bp = PrivateWorldAddons.class.getDeclaredMethod("buyPlayerOffers"); bp.setAccessible(true); bp.invoke(null);
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.a1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 200; int t; AIPlayer a, b; int plain; long bountyBefore; int refW, refP;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) {
                        Location at = Location.create(3165, 3487, 0);
                        a = human(at, 900101); b = human(at.transform(1, 0, 0), 900102);
                        inject();
                        for (int id : new int[]{453, 1511, 436, 438, 1519}) if (!Bounty.wanted(id)) { plain = id; break; }
                        refP = MarketBrain.botRef(plain); refW = MarketBrain.botRef(WANTED);
                        log("plain item " + MarketBrain.name(plain) + " ref " + refP + " (guide " + MarketBrain.value(plain) + "); wanted " + MarketBrain.name(WANTED) + " on board " + Bounty.wanted(WANTED) + " ref " + refW + " guide " + MarketBrain.value(WANTED) + ", bot stock " + GrandExchange.Companion.getBotstockForId(WANTED));
                        a.getInventory().add(new Item(plain, 20)); a.getInventory().add(new Item(WANTED, 10));
                        offer(a, 0, plain, 10, refP, true);              // 100% of the reference
                        offer(a, 1, plain, 10, refP * 90 / 100, true);   // 90%
                        offer(a, 2, WANTED, 10, refW, true);             // the bounty item at 100%
                    }
                    if (t == T0 + 10) { inject(); bountyBefore = Bounty.paidToday; runBuyer(); }
                    if (t == T0 + 20) {
                        log("RESULT 1 plain at 100%: bought " + done(900101, 0) + " of 10; plain at 90%: bought " + done(900101, 1) + " of 10");
                        log("RESULT 2 bounty item at 100%: bought " + done(900101, 2) + " of 10; bounty paid " + (Bounty.paidToday - bountyBefore) + " gp");
                        inject();
                        offer(b, 0, WANTED, 5, MarketBrain.value(WANTED), false); // buy the bounty item from bot stock at guide
                    }
                    if (t == T0 + 35) {
                        int got = done(900102, 0);
                        long buy = (long) MarketBrain.value(WANTED), sellBefore = (long) refW * 85 / 100 + (long) MarketBrain.value(WANTED) * 25 / 100, sellAfter = (long) refW + (long) MarketBrain.value(WANTED) * 25 / 100;
                        log("RESULT 3 buy " + MarketBrain.name(WANTED) + " from bot stock at guide: filled " + got + " of 5" + (got > 0 ? "; round trip per unit: pay " + buy + ", sell back at the buyer's top price + bounty " + (NEW ? sellAfter : sellBefore) + " (gain " + ((NEW ? sellAfter : sellBefore) - buy) + ")" : " (no loop possible)"));
                        inject();
                        AIPlayer seller = null;
                        for (Player p : SmartAdventurers.BOTS) if (p instanceof AIPlayer && p.isActive() && SmartAdventurers.BRAINS.containsKey(p.getUsername().toLowerCase())) { seller = (AIPlayer) p; break; }
                        if (seller != null) {
                            seller.getBank().add(new Item(WANTED, 60));
                            boolean called = Deals.arrive(seller, WANTED, 60);
                            boolean toPlayers = false; for (Deals.Offer o : Deals.OFFERS) if (o.seller == seller && o.id == WANTED) toPlayers = true;
                            log("RESULT 4a Deals: " + seller.getUsername() + "'s 60 iron ore at the bank -> " + (toPlayers ? "CALLED OUT to players" : called ? "split to a bot buyer (bot to bot, not offered to players)" : "not offered to players (goes to the Exchange)"));
                            seller.getBank().add(new Item(WANTED, 60));
                            for (Deals.Offer o : new java.util.ArrayList<>(Deals.OFFERS)) if (o.seller == seller) Deals.OFFERS.remove(o);
                            String ans = Deals.sellOnRequest(seller, a, WANTED);
                            log("RESULT 4b a player asks " + seller.getUsername() + " for iron ore -> " + ans);
                        } else log("RESULT 4 no smart bot to test with");
                        TravellingTraders.Trader tr = TravellingTraders.ALL.isEmpty() ? null : TravellingTraders.ALL.get(0);
                        if (tr != null && tr.bot != null) {
                            tr.bot.getBank().add(new Item(WANTED, 30));
                            tr.packFromBank();
                            log("RESULT 5 trader " + tr.bot.getUsername() + " packs its bank for sale: iron ore in the haul " + tr.haul.getOrDefault(WANTED, 0) + ", still banked " + tr.bot.getBank().getAmount(WANTED));
                        } else log("RESULT 5 no trader to test with");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
