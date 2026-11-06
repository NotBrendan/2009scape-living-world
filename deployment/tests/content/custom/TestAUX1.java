package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.ge.GEDB;
import core.game.ge.GrandExchange;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aux1=true): audit 10-23 X-1. botstock_limit (default 5000; the trial config doesn't set it).
 *  1. a pile of 12,000 in bot_offers, a fake human buys 10 from it: what is left in the row? (right: 11,990)
 *  2. Ledger.supply of 8,000 to an empty row: what is in the row, and what lots does the ledger hold? (right: 8,000 both)
 */
public final class TestAUX1 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUX1] " + s); }
    static void sql(String q) { GEDB.run(conn -> { try { conn.createStatement().executeUpdate(q); } catch (Throwable e) { log("sql " + e); } return kotlin.Unit.INSTANCE; }); }
    static int row(int id) {
        final int[] r = {-1};
        GEDB.run(conn -> { try { java.sql.ResultSet rs = conn.createStatement().executeQuery("SELECT sum(amount), count(*) FROM bot_offers WHERE item_id = " + id); if (rs.next()) r[0] = rs.getInt(2) == 0 ? -1 : rs.getInt(1); } catch (Throwable e) { log("query " + e); } return kotlin.Unit.INSTANCE; });
        return r[0];
    }
    static void setRow(int id, int n) { sql("DELETE FROM bot_offers WHERE item_id = " + id); if (n >= 0) sql("INSERT INTO bot_offers(item_id,amount) values(" + id + "," + n + ")"); }
    static int pick(int skip, int... ids) { for (int id : ids) if (id != skip && !Bounty.wantedNow(id) && core.game.ge.PriceIndex.canTrade(id)) return id; return -1; }
    static int lots(int id) { synchronized (Ledger.class) { int n = 0; java.util.Deque<Ledger.Entry> q = Ledger.QUEUES.get(id); if (q != null) for (Ledger.Entry e : q) n += e.qty; return n; } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aux1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, a, b, done0; AIPlayer h;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        a = pick(-1, 453, 440, 444, 1515, 1517); b = pick(a, 1519, 1521, 447, 449, 451);
                        log("botstock_limit " + core.ServerConstants.BOTSTOCK_LIMIT + "; pile item " + MarketBrain.name(a) + ", supply item " + MarketBrain.name(b));
                        setRow(a, 12_000);
                        h = TestA1.human(Location.create(3165, 3487, 0), 900301);
                        int price = GrandExchange.Companion.getRecommendedPrice(a, true) + 10;
                        TestA1.offer(h, 0, a, 10, price, false);
                        setRow(b, -1); synchronized (Ledger.class) { Ledger.QUEUES.remove(b); }
                        Ledger.supply(null, b, 8_000);
                    }
                    if (t == 170) {
                        log("RESULT 1 pile of 12000 " + MarketBrain.name(a) + ", a player bought " + TestA1.done(900301, 0) + ": row now " + row(a) + " (right: 11990)");
                        log("RESULT 2 supply of 8000 " + MarketBrain.name(b) + " to an empty row: row now " + row(b) + ", ledger lots " + lots(b) + " (right: 8000 and 8000)");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
