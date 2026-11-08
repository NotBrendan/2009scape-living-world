package content.custom;

import core.api.StartupListener;
import core.game.ge.GEDB;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.List;

/**
 * Trial only (-Dtest.b17d2=true; needs TestA1 compiled in): batch 17 item2, audit X-6, the bounty premium loop. Once the
 * board has an item (tick 300 on), two stand-in humans: A (uid 917001) has a completed GE buy of 10 of it an hour ago (a
 * player_offers row, is_sale 0, as a fill from bot stock leaves), B (uid 917002) has none. Bounty.playerSold(A, item, 10),
 * then (B, 10), then A again (10 more: the 10 bought are used up). Bank coins before/after each. Before: premium on all
 * three; after: A 0, B the premium, A's second sale the premium.
 */
public final class TestB17D2 implements StartupListener {
    static void log(String s) { System.out.println("[TestB17D2] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17d2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            long sale(Player p, int id, int n) { int b0 = p.getBank().getAmount(995) + p.getInventory().getAmount(995); Bounty.playerSold(p, id, n); return p.getBank().getAmount(995) + p.getInventory().getAmount(995) - b0; }
            @Override public boolean pulse() {
                t++;
                if (t < 300 || t % 50 != 0) return false;
                try {
                    List<Object[]> board = Bounty.board();
                    if (board.isEmpty()) { if (t > 2500) { log("RESULT SKIP board still empty at tick 2500 (no item to test)"); log("DONE"); return true; } log("board empty at tick " + t); return false; }
                    int id = (int) board.get(0)[0]; String nm = String.valueOf(board.get(0)[1]);
                    Player a = TestA1.human(Location.create(3165, 3485, 0), 917001), b = TestA1.human(Location.create(3166, 3485, 0), 917002);
                    long ts = System.currentTimeMillis() - 3_600_000L;
                    GEDB.run(conn -> {
                        try (java.sql.PreparedStatement st = conn.prepareStatement("INSERT INTO player_offers(player_uid, item_id, amount_total, amount_complete, offered_value, time_stamp, offer_state, is_sale, slot_index, total_coin_xc) VALUES (?,?,10,10,?,?,6,0,0,0)")) {
                            st.setInt(1, 917001); st.setInt(2, id); st.setInt(3, MarketBrain.value(id) * 11 / 10); st.setLong(4, ts); st.executeUpdate();
                        } catch (Throwable e) { log("insert failed " + e); }
                        return kotlin.Unit.INSTANCE;
                    });
                    log("item " + nm + " (" + id + ") guide " + MarketBrain.value(id) + ", 25% on 10 = " + (long) MarketBrain.value(id) * 10 * 25 / 100 + "; A uid " + a.getDetails().getUid() + " bought 10 an hour ago, B uid " + b.getDetails().getUid() + " bought none");
                    long pa = sale(a, id, 10), pb = sale(b, id, 10), pa2 = sale(a, id, 10);
                    log("RESULT A (bought 10, sells 10): premium " + pa + " gp; B (bought none, sells 10): premium " + pb + " gp; A again (10 more): premium " + pa2 + " gp -> " + (pa == 0 && pb > 0 && pa2 > 0 ? "PASS" : "FAIL"));
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); }
                log("DONE"); return true;
            }
        });
    }
}
