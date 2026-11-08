package content.custom;

import core.api.StartupListener;
import core.game.ge.GEDB;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.List;

/**
 * Trial only (-Dtest.p38bounty=true; needs TestA1 compiled in): P38 L8, audit X-6 Deals/traders part, flag bounty.exemptDeals.
 * Once the board has an item (tick 300 on; TestA1.inject() from tick 1200 if it is still empty), stand-in humans sell 10 of
 * it through Bounty.playerSold and the bank coins gained are the premium. The test switches Bounty.exemptDeals itself and
 * puts it back at the end.
 *  A  flag on : boughtFromBots(A, 10); sell 10 -> 0 (log says "bought from bots"); sell 10 again -> full premium.
 *  B  flag on : a bot buy of 10 recorded 49 h ago (outside the 48 h window); sell 10 -> full premium.
 *  C  flag off: boughtFromBots(C, 10) records nothing; sell 10 -> full premium (today's behaviour).
 *  D  GE path : a completed GE buy of 10 an hour ago; sell 10 -> 0 with the flag on (D1) and off (D2).
 *  E  flag on, GE lookup forced to throw: GE buy 10 + bot buy 5; sell 10 -> premium on 5 (GE part 0 = premium as before,
 *     bot part still counts).
 */
public final class TestP38Bounty implements StartupListener {
    static void log(String s) { System.out.println("[TestP38Bounty] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.p38bounty")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            long sale(Player p, int id, int n) { int b0 = p.getBank().getAmount(995) + p.getInventory().getAmount(995); Bounty.playerSold(p, id, n); return p.getBank().getAmount(995) + p.getInventory().getAmount(995) - b0; }
            void geBuy(int uid, int id, int n) {
                long ts = System.currentTimeMillis() - 3_600_000L;
                GEDB.run(conn -> {
                    try (java.sql.PreparedStatement st = conn.prepareStatement("INSERT INTO player_offers(player_uid, item_id, amount_total, amount_complete, offered_value, time_stamp, offer_state, is_sale, slot_index, total_coin_xc) VALUES (?,?,?,?,?,?,6,0,0,0)")) {
                        st.setInt(1, uid); st.setInt(2, id); st.setInt(3, n); st.setInt(4, n); st.setInt(5, MarketBrain.value(id) * 11 / 10); st.setLong(6, ts); st.executeUpdate();
                    } catch (Throwable e) { log("insert failed " + e); }
                    return kotlin.Unit.INSTANCE;
                });
            }
            @Override public boolean pulse() {
                t++;
                if (t < 300 || t % 50 != 0) return false;
                boolean was = Bounty.exemptDeals;
                try {
                    List<Object[]> board = Bounty.board();
                    if (board.isEmpty() && t >= 1200) { TestA1.inject(); board = Bounty.board(); }
                    if (board.isEmpty()) { if (t > 2500) { log("RESULT board still empty"); log("DONE"); return true; } log("board empty at tick " + t); return false; }
                    int id = (int) board.get(0)[0]; String nm = String.valueOf(board.get(0)[1]);
                    long full = (long) MarketBrain.value(id) * 10 * 25 / 100, half = (long) MarketBrain.value(id) * 5 * 25 / 100;
                    log("item " + nm + " (" + id + ") guide " + MarketBrain.value(id) + ", 25% on 10 = " + full + ", on 5 = " + half + "; flag at start " + was);
                    Location at = Location.create(3165, 3485, 0);
                    Player a = TestA1.human(at, 938001), b = TestA1.human(at.transform(1, 0, 0), 938002), c = TestA1.human(at.transform(2, 0, 0), 938003),
                           d1 = TestA1.human(at.transform(3, 0, 0), 938004), d2 = TestA1.human(at.transform(4, 0, 0), 938005), e = TestA1.human(at.transform(5, 0, 0), 938006);

                    Bounty.exemptDeals = true;
                    Bounty.boughtFromBots(a, id, 10);
                    long pa = sale(a, id, 10), pa2 = sale(a, id, 10);
                    log("A flag on, bought 10 from bots: sell 10 -> " + pa + " gp (want 0); sell 10 again -> " + pa2 + " gp (want > 0)");

                    java.util.Deque<long[]> old = Bounty.BOT_BUYS.computeIfAbsent(938002 + ":" + id, k -> new java.util.ArrayDeque<>());
                    synchronized (old) { old.addLast(new long[]{System.currentTimeMillis() - 49L * 3_600_000, 10}); }
                    long pb = sale(b, id, 10);
                    log("B flag on, bot buy 49 h ago: sell 10 -> " + pb + " gp (want > 0)");

                    Bounty.exemptDeals = false;
                    Bounty.boughtFromBots(c, id, 10);
                    boolean cRecorded = Bounty.BOT_BUYS.containsKey(938003 + ":" + id);
                    long pc = sale(c, id, 10);
                    log("C flag off, boughtFromBots called: recorded=" + cRecorded + " (want false); sell 10 -> " + pc + " gp (want > 0)");

                    geBuy(938004, id, 10); geBuy(938005, id, 10);
                    Bounty.exemptDeals = true; long pd1 = sale(d1, id, 10);
                    Bounty.exemptDeals = false; long pd2 = sale(d2, id, 10);
                    log("D GE buy 10: flag on sell 10 -> " + pd1 + " gp, flag off sell 10 -> " + pd2 + " gp (want 0, 0)");

                    geBuy(938006, id, 10);
                    Bounty.exemptDeals = true; Bounty.boughtFromBots(e, id, 5);
                    Bounty.failGeForTest = true; long pe;
                    try { pe = sale(e, id, 10); } finally { Bounty.failGeForTest = false; }
                    log("E flag on, GE lookup throws, GE 10 + bots 5: sell 10 -> " + pe + " gp (want > 0 and <= " + half + ")");

                    boolean ok = pa == 0 && pa2 > 0 && pb > 0 && !cRecorded && pc > 0 && pd1 == 0 && pd2 == 0 && pe > 0 && pe <= half + 1;
                    log("RESULT " + (ok ? "PASS" : "FAIL") + " A " + pa + "/" + pa2 + " B " + pb + " C " + pc + " D " + pd1 + "/" + pd2 + " E " + pe);
                } catch (Throwable ex) { log("RESULT FAIL error " + ex); ex.printStackTrace(System.out); }
                finally { Bounty.exemptDeals = was; Bounty.failGeForTest = false; }
                log("DONE"); return true;
            }
        });
    }
}
