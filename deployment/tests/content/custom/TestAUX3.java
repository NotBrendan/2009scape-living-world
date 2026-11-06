package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.ge.GEDB;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.ge.OfferState;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aux3=true): audit 10-23 X-3 (E-7). A fake human's sell offer S of 100 at 90% of botRef (aged past
 * the bot buyer's 5 minutes) and a fake human's buy B of 100 at the same price.
 *  A. The audit's interleaving, step by step on the game thread: the worker's read of S (a copy, completed 0), then the bot
 *     buyer runs, then the worker's exchange of B with its copy of S. Right: 100 units delivered in total.
 *  B. (with -Dtest.ge.matchDelayMs=3000, the overlay's hook) the real worker: B2 is dispatched, the worker holds its matches
 *     for 3 s, and the bot buyer runs in that time on the game thread. Right: 100 units delivered in total.
 */
public final class TestAUX3 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUX3] " + s); }
    static int[] row(long uid) {
        final int[] r = {-1, -1, -1};
        GEDB.run(conn -> { try { java.sql.ResultSet rs = conn.createStatement().executeQuery("SELECT amount_complete, offer_state, total_coin_xc FROM player_offers WHERE uid = " + uid); if (rs.next()) { r[0] = rs.getInt(1); r[1] = rs.getInt(2); r[2] = rs.getInt(3); } } catch (Throwable e) { log("query " + e); } return kotlin.Unit.INSTANCE; });
        return r;
    }
    static long lastUid(int playerUid, int slot) {
        final long[] r = {-1};
        GEDB.run(conn -> { try { java.sql.ResultSet rs = conn.createStatement().executeQuery("SELECT uid FROM player_offers WHERE player_uid = " + playerUid + " AND slot_index = " + slot + " ORDER BY uid DESC LIMIT 1"); if (rs.next()) r[0] = rs.getLong(1); } catch (Throwable e) { log("query " + e); } return kotlin.Unit.INSTANCE; });
        return r[0];
    }
    static void age() { TestAUX1.sql("UPDATE player_offers SET time_stamp = time_stamp - 600000 WHERE is_sale = 1"); }
    static void buyer() throws Exception { java.lang.reflect.Method bp = PrivateWorldAddons.class.getDeclaredMethod("buyPlayerOffers"); bp.setAccessible(true); bp.invoke(null); }
    static GrandExchangeOffer copyOf(long uid) { for (GrandExchangeOffer o : GrandExchange.Companion.getValidOffers()) if (o.getUid() == uid) return o; return null; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aux3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, e, e2, price, price2, s0, s2; long sUid, bUid, s2Uid, b2Uid; AIPlayer seller, buyerP;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        e = TestAUX1.pick(-1, 453, 440, 1515, 444); e2 = TestAUX1.pick(e, 1517, 1513, 447, 449, 451);
                        seller = TestA1.human(Location.create(3165, 3487, 0), 900501); buyerP = TestA1.human(Location.create(3166, 3487, 0), 900502);
                        TestAUX1.setRow(e, 0); TestAUX1.setRow(e2, 0); TestAUX1.sql("DELETE FROM player_offers WHERE item_id IN (" + e + "," + e2 + ")"); // nothing else to match
                        price = Math.max(1, MarketBrain.botRef(e) * 90 / 100); price2 = Math.max(1, MarketBrain.botRef(e2) * 90 / 100);
                        seller.getInventory().add(new core.game.node.item.Item(e, 100)); seller.getInventory().add(new core.game.node.item.Item(e2, 100));
                        TestA1.offer(seller, 0, e, 100, price, true);
                        TestA1.offer(seller, 1, e2, 100, price2, true);
                    }
                    if (t == 160) {
                        age(); sUid = lastUid(900501, 0); s2Uid = lastUid(900501, 1);
                        // A: the worker's steps for B, done here in the audit's order around the bot buyer
                        GrandExchangeOffer b = new GrandExchangeOffer();
                        b.setItemID(e); b.setAmount(100); b.setOfferedValue(price); b.setSell(false); b.setIndex(0); b.setPlayer(buyerP);
                        b.setPlayerUID(buyerP.getDetails().getUid()); b.setOfferState(OfferState.REGISTERED);
                        b.writeNew(); bUid = b.getUid();
                        GrandExchangeOffer sCopy = copyOf(sUid);                  // the worker reads S (completed 0)
                        s0 = TestAUX1.row(e);
                        int d = TestAUX1.pick(e, 1925, 1931, 1935, 1937); // the worker is busy with B: queue 1,500 one-unit supplies of another item so nothing queued now runs before the exchange below
                        for (int i = 0; i < 1500; i++) GrandExchange.Companion.getPendingOffers().addLast(GrandExchangeOffer.Companion.createBotOffer(d, 1, true));
                        buyer();                                                   // the bot buyer runs on the game thread
                        GrandExchangeOffer bRead = copyOf(bUid);
                        GrandExchange.Companion.exchange(bRead != null ? bRead : b, sCopy); // the worker writes its copy of S and fills B
                        log("A: S uid " + sUid + " (" + MarketBrain.name(e) + " at " + price + "), B uid " + bUid + "; worker copy of S read with completed " + (sCopy == null ? "?" : String.valueOf(sCopy.getCompletedAmount())));
                    }
                    if (t == 175) {
                        int[] s = row(sUid), bb = row(bUid); int stock = TestAUX1.row(e) - Math.max(0, s0);
                        log("RESULT A: S completed " + s[0] + " (coins to the seller " + s[2] + "), B got " + bb[0] + ", bot stock +" + stock + "; units delivered for S's 100: " + (bb[0] + stock));
                        // B: the real worker with the hook
                        s2 = TestAUX1.row(e2);
                        TestA1.offer(buyerP, 1, e2, 100, price2, false);
                    }
                    if (t == 177) buyer(); // inside the worker's 3 s hold (when the hook is on)
                    if (t == 195) {
                        b2Uid = lastUid(900502, 1);
                        int[] s = row(s2Uid), bb = row(b2Uid); int stock = TestAUX1.row(e2) - Math.max(0, s2);
                        log("RESULT B (hook " + Long.getLong("test.ge.matchDelayMs", 0L) + " ms): S2 completed " + s[0] + " (coins to the seller " + s[2] + "), B2 got " + bb[0] + ", bot stock +" + stock + "; units delivered for S2's 100: " + (bb[0] + stock));
                        log("DONE");
                        return true;
                    }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
