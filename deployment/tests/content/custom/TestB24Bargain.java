package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Trial only (-Dtest.b24bargain=true -Dbargain.on=true -Dtraders.fast=true): batch 24 step 5 on the spare PC. Haggles with a
 * real travelling trader through its own chat listener ({@code Trader.haggler}) with the engine ON: a lowball (REFUSE), an 80%
 * bid (COUNTER to the floor), "deal", then a trade settled the way the trade window settles it (the trader's TRADING phase sees
 * its coins rise by the price). Then a second stranger lowballs twice (REFUSE, WALK). Reads data/botchat-deals.log for the SALE
 * and WALK lines. The kill-switch restart (data/bargain-off present -> "[Bargain] OFF") is a separate run by the harness.
 */
public final class TestB24Bargain implements StartupListener {
    static final class Human extends AIPlayer {
        final List<String> got = new ArrayList<>();
        Human(Location l) { super(l); }
        @Override public void sendMessage(String m) { got.add(m); log("  " + getUsername() + " <- " + m); }
    }
    static int pass, fail;
    static void log(String s) { System.out.println("[TestB24Bargain] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log("B24B " + (ok ? "PASS " : "FAIL ") + what); }

    static Human human(Location at) throws Exception {
        Human p = new Human(at);
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(p, false);
        return p;
    }

    static List<String> deals(String bot) {
        try {
            File f = new File(ServerConstants.DATA_PATH, "botchat-deals.log");
            if (!f.exists()) return new ArrayList<>();
            List<String> out = new ArrayList<>();
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) if (l.contains("bot=" + bot + " ")) out.add(l);
            return out;
        } catch (Exception e) { log("deals log read failed: " + e); return new ArrayList<>(); }
    }
    static long field(String line, String key) {
        int i = line.indexOf(" " + key + "="); if (i < 0) return -1;
        int j = line.indexOf(' ', i + key.length() + 2); if (j < 0) j = line.length();
        try { return Long.parseLong(line.substring(i + key.length() + 2, j)); } catch (Exception e) { return -1; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b24bargain")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, stage, since; TravellingTraders.Trader tr; Human h, h2; String bot; long list, floor, quote; int dealsBefore;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (stage == 0) {
                        if (t < 100) return false;
                        for (TravellingTraders.Trader x : TravellingTraders.ALL) if (x.bot != null && x.bot.isActive()) { tr = x; break; }
                        if (tr == null) { if (t > 1500) { log("RESULT no trader spawned within 1500 ticks"); return done(); } return false; }
                        bot = tr.bot.getUsername();
                        log("start at tick " + t + ": bargain.on " + Bargain.on() + " tripped " + Bargain.tripped + ", trader " + bot + " (" + tr.kind + ") at " + tr.bot.getLocation() + " phase " + tr.phase);
                        check("flag: Bargain.on() true in this trial (-Dbargain.on=true, not tripped)", Bargain.on());
                        dealsBefore = deals(bot).size();
                        // A. a stranger haggles for a lot of 20 coal
                        h = human(tr.bot.getLocation().transform(1, 0, 0));
                        tr.haul.clear(); tr.haul.put(453, 20);
                        list = Math.max(100, (long) Deals.ref(453) * 20);
                        tr.price = (int) list; tr.target = h; tr.haggles = 0; tr.lastBid = -1; tr.countered = false; tr.floorPrice = 0; tr.bs = null;
                        floor = tr.bargainFloor();
                        log("A lot: 20 coal, Deals.ref " + Deals.ref(453) + ", list " + list + ", bargainFloor " + floor + " (" + (floor * 100 / list) + "% of list), trust " + BotChat.Trust.of(h, tr.bot) + ", player " + h.getUsername());
                        check("A floor: 92% of list <= floor <= list", floor >= list * 92 / 100 && floor <= list);
                        long lb = list * 40 / 100;
                        boolean heard = tr.haggler.hear(h, lb + " for the lot");
                        Bargain.Session s = Bargain.session(bot, h.getUsername());
                        log("A1 lowball " + lb + " (40%): heard " + heard + ", session " + (s == null ? "none" : "quote " + s.quote + " strikes " + s.strikes + " tries " + s.tries) + ", trader price " + tr.price);
                        check("A1 lowball: REFUSE (session open, 1 strike, quote still list, price unchanged)", s != null && s.strikes == 1 && s.quote == list && tr.price == list);
                        long b80 = list * 80 / 100;
                        heard = tr.haggler.hear(h, b80 + " for the lot");
                        s = Bargain.session(bot, h.getUsername());
                        quote = s == null ? -1 : s.quote;
                        log("A2 bid " + b80 + " (80%): heard " + heard + ", session " + (s == null ? "none" : "quote " + s.quote + " strikes " + s.strikes + " tries " + s.tries + " countered " + s.countered) + ", trader price " + tr.price);
                        check("A2 80% bid: COUNTER (quote moved down, floor <= quote < list, trader price = quote)", s != null && s.countered && quote >= floor && quote < list && tr.price == quote);
                        heard = tr.haggler.hear(h, "deal"); // the agree branch: "Trade me and offer exactly <price>" goes by packet dispatch (not capturable here)
                        log("A3 'deal': heard " + heard + " (agree branch), trader price " + tr.price);
                        check("A3 'deal': heard by the haggler and the price holds at the counter", heard && tr.price == quote);
                        // settle like the trade window does: TRADING phase, the trader's coins rise by the price
                        tr.coinsBeforeTrade = tr.bot.getInventory().getAmount(995);
                        tr.enter(TravellingTraders.Phase.TRADING);
                        tr.bot.getInventory().add(new Item(995, (int) quote));
                        stage = 1; since = t; return false;
                    }
                    if (stage == 1) {
                        List<String> d = deals(bot); boolean sale = false; for (String l : d) if (l.contains(" SALE ") && l.contains("player=" + h.getUsername() + " ")) sale = true;
                        if (!sale && tr.phase == TravellingTraders.Phase.TRADING && t - since < 120) return false;
                        String saleLine = null; for (String l : d) if (l.contains(" SALE ") && l.contains("player=" + h.getUsername() + " ")) saleLine = l;
                        log("A4 trade settled after " + (t - since) + " ticks: trader phase " + tr.phase + ", deals log lines for this trader " + d.size() + " (before " + dealsBefore + ")");
                        for (String l : d) log("  deals.log: " + l);
                        long fin = saleLine == null ? -1 : field(saleLine, "final"), fl = saleLine == null ? -1 : field(saleLine, "floor"), li = saleLine == null ? -1 : field(saleLine, "list");
                        check("A4 SALE line in data/botchat-deals.log with final in [floor, list]", saleLine != null && fin >= fl && fin <= li && fl >= 0);
                        check("A4 SALE final = the agreed counter, discount = list - final", saleLine != null && fin == quote && field(saleLine, "disc") == list - quote);
                        check("A4 engine still on after the sale (budget/floor exit rules not tripped)", Bargain.on() && !Bargain.tripped);
                        // B. a second stranger lowballs twice: REFUSE then WALK (15 min hold, no trust strike below 0)
                        h2 = human(tr.bot.getLocation().transform(-1, 0, 0));
                        tr.haul.clear(); tr.haul.put(453, 20); tr.price = (int) list; tr.target = h2; tr.haggles = 0; tr.lastBid = -1; tr.countered = false; tr.floorPrice = 0; tr.bs = null;
                        tr.phase = TravellingTraders.Phase.PITCH; // listener bookkeeping only; the engine is fed directly below
                        long lb = list * 30 / 100;
                        tr.haggler.hear(h2, lb + " for the lot");
                        Bargain.Session s = Bargain.session(bot, h2.getUsername());
                        log("B1 lowball " + lb + ": session " + (s == null ? "none" : "strikes " + s.strikes + " quote " + s.quote));
                        check("B1 first lowball: REFUSE, session open with 1 strike", s != null && s.strikes == 1);
                        long now = System.currentTimeMillis();
                        tr.haggler.hear(h2, lb + " for the lot");
                        s = Bargain.session(bot, h2.getUsername());
                        long hold = Bargain.holdUntil(bot, h2.getUsername());
                        boolean walk = false; for (String l : deals(bot)) if (l.contains(" WALK ") && l.contains("player=" + h2.getUsername() + " ")) walk = true;
                        log("B2 second lowball: session " + (s == null ? "closed" : "still open") + ", hold " + (hold > 0 ? ((hold - now) / 60_000) + " min" : "none") + ", trader target " + (tr.target == null ? "none" : tr.target.getUsername()) + " phase " + tr.phase + ", WALK logged " + walk + ", trust " + BotChat.Trust.of(h2, tr.bot));
                        check("B2 second lowball: WALK (session closed, ~15 min hold, trader drops the target, WALK logged)", s == null && hold - now > 14 * 60_000L && hold - now <= 16 * 60_000L && tr.target == null && walk);
                        check("B2 no trust below 0 for a stranger", BotChat.Trust.of(h2, tr.bot) == 0);
                        check("B3 engine still on", Bargain.on() && !Bargain.tripped);
                        long b = Bargain.budgetLeft(bot, list, System.currentTimeMillis());
                        log("C budget left for " + bot + " after one sale at list " + list + ": " + b + " gp (cap min(2000, 3% of booked list))");
                        stageTwo();
                        return done();
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); return done(); }
                return false;
            }
            /** D. batch 24b stage 2: a Deals offer (20 coal at 99% of guide) haggled through Deals.haggle with the engine on. */
            void stageTwo() throws Exception {
                AIPlayer seller = new AIPlayer(tr.bot.getLocation().transform(0, 1, 0));
                Human h3 = human(tr.bot.getLocation().transform(0, -1, 0));
                int guide = Math.max(1, Deals.ref(453)), unit = Math.max(1, guide * 99 / 100);
                Deals.Offer o = new Deals.Offer(seller, 453, 20, unit);
                long ask = (long) unit * 20, dfloor = Math.min((long) guide * 20 * 92 / 100, ask), bid = ask * 90 / 100;
                boolean took = Deals.haggle(o, h3, bid + " for the lot", true);
                long[] st = o.hag.get(h3.getUsername().toLowerCase());
                Bargain.Session ds = Bargain.session(seller.getUsername(), h3.getUsername());
                log("D1 Deals bid " + bid + " of ask " + ask + " (floor " + dfloor + "): took " + took + ", session " + (ds == null ? "none" : "units " + ds.units + " list " + ds.list + " floor " + ds.floor + " quote " + ds.quote)
                        + ", hag " + (st == null ? "none" : java.util.Arrays.toString(st)));
                check("D1 stage 2: Deals.haggle hands the bid to the engine (session for 20, list = ask, floor = min(92% guide, ask))", took && ds != null && ds.units == 20 && ds.list == ask && ds.floor == dfloor);
                long counter = st == null ? -1 : st[3] > 0 ? st[3] : st[0];
                check("D1 the engine's price (counter or accept) is in [floor, ask]", counter >= dfloor && counter <= ask);
                Deals.haggle(o, h3, "deal", true);
                check("D2 'deal' takes the engine's price as the agreed total for 20", st != null && st[0] == counter && st[1] == 20);
                o.humanBuyer = h3; o.lastDue = o.dueFrom(h3);
                Deals.bargainSold(o, 20, o.lastDue); // what humanTrade does once the window closes with the goods gone
                String sale = null; for (String l : deals(seller.getUsername())) if (l.contains(" SALE ") && l.contains("player=" + h3.getUsername() + " ")) sale = l;
                log("D3 deals log: " + sale);
                check("D3 SALE logged at the agreed price, final in [floor, list], engine still on", sale != null && field(sale, "final") == counter && field(sale, "final") >= field(sale, "floor") && field(sale, "final") <= field(sale, "list") && Bargain.on());
            }
            boolean done() { log("B24B TOTAL pass=" + pass + " fail=" + fail); log("DONE"); return true; }
        });
    }
}
