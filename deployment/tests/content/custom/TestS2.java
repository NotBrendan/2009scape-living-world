package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.ge.PriceIndex;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.s2=true): scripted checks of the S2 audit fixes that can be driven directly. */
public final class TestS2 implements StartupListener {
    static void log(String s) { System.out.println("[TestS2] " + s); }

    static AIPlayer human(Location at) throws Exception {
        AIPlayer p = new AIPlayer(at);
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(p, false);
        return p;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.s2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 200) e4();
                    if (t == 205) e14();
                    if (t == 210) b2b11();
                    if (t == 215) e16();
                    if (t == 220) e17();
                    if (t == 225) b18();
                    if (t == 235) b7();
                    if (t == 240) { log("DONE"); return true; }
                } catch (Throwable e) { log("error at " + t + ": " + e); e.printStackTrace(); }
                return false;
            }
        });
    }

    static void e4() {
        int id = 1739; // cowhide
        int before = MarketBrain.botRef(id), guide0 = MarketBrain.value(id);
        Integer base = PriceDrift.BASE.get(id);
        for (int i = 0; i < 20; i++) PriceIndex.INSTANCE.addTrade(id, 100, guide0 * 5); // two alts trading cowhides at 5x guide
        MarketBrain.priceCacheClear();
        int guide1 = MarketBrain.value(id), after = MarketBrain.botRef(id);
        log("E-4 cowhide base " + base + ", guide " + guide0 + " -> " + guide1 + " after alt trades; bots pay " + before + " -> " + after + " (cap 150% of base = " + (base == null ? "?" : Math.round(base * 1.5)) + ")");
    }

    static void e14() throws Exception {
        AIPlayer seller = new AIPlayer(Location.create(3185, 3436, 0)); AIPlayer h = human(Location.create(3186, 3436, 0));
        seller.getBank().add(new Item(453, 100));
        int guide = MarketBrain.value(453);
        Deals.Offer o = new Deals.Offer(seller, 453, 100, Math.max(1, guide * 92 / 100));
        o.holdUntil = System.currentTimeMillis() + 600_000; Deals.OFFERS.add(o);
        long ask = (long) o.unit * 100;
        StringBuilder sb = new StringBuilder();
        for (int pct : new int[]{70, 78, 80, 84}) {
            o.hag.clear();
            Deals.haggle(o, h, "i'll give you " + (guide * 100L * pct / 100) + " for the lot", true);
            Deals.haggle(o, h, "i'll give you " + (guide * 100L * pct / 100) + " for the lot", true);
            long[] st = o.hag.get(h.getUsername().toLowerCase());
            sb.append(pct).append("% of guide -> agreed ").append(st == null ? 0 : st[0]).append(", counter ").append(st == null ? 0 : st[3]).append("; ");
        }
        Deals.OFFERS.remove(o);
        log("E-14 coal guide " + guide + ", ask " + ask + ", floor now 86% = " + (guide * 100L * 86 / 100) + ": " + sb);
    }

    static void b2b11() {
        SmartAdventurers.Brain br = null;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) if (b.bot != null && b.bot.isActive()) { br = b; break; }
        if (br == null) { log("B-2 no brain"); return; }
        Player bot = br.bot;
        int pack0 = 0; for (int f : AfkTasks.Runner.FOOD) pack0 += bot.getInventory().getAmount(f);
        bot.getBank().add(new Item(379, 20));
        br.goodFoodOnly = false;
        br.packFood();
        int pack1 = 0; for (int f : AfkTasks.Runner.FOOD) pack1 += bot.getInventory().getAmount(f);
        log("B-2 " + bot.getUsername() + ": food in pack " + pack0 + " -> " + pack1 + " (bank lobsters left " + bot.getBank().getAmount(379) + ", free slots " + bot.getInventory().freeSlots() + ")");
        AIPlayer full = new AIPlayer(Location.create(3222, 3218, 0));
        full.getInventory().clear(); for (int i = 0; i < 28; i++) full.getInventory().add(new Item(1511));
        SmartAdventurers.Brain.packOrBank(full, new Item(1351));
        log("B-11 full pack: bronze axe in pack " + full.getInventory().getAmount(1351) + ", in bank " + full.getBank().getAmount(1351) + " (was lost)");
    }

    static void e16() {
        Artisans.Recipe r = null;
        for (Artisans.Recipe x : Artisans.RECIPES) if (x.inIds.length >= 2) { r = x; break; }
        if (r == null) { log("E-16 no two-input recipe"); return; }
        AIPlayer a = new AIPlayer(Location.create(3185, 3436, 0));
        a.getBank().clear(); Ledger.Wallet.give(a, 1_000_000);
        a.getBank().add(new Item(r.inIds[0], r.inAmts[0] * 5)); // the first input in its bank, the second nowhere (stock emptied below)
        int stock2 = core.game.ge.GrandExchange.Companion.getBotstockForId(r.inIds[1]);
        if (stock2 > 0) MarketBrain.takeFromStock(r.inIds[1], stock2);
        Artisans.Plan pl = new Artisans.Plan(); pl.r = r; pl.times = 5;
        boolean ok = Artisans.buyInputs(pl, a);
        log("E-16 " + r.name + ": buyInputs=" + ok + " with " + new Item(r.inIds[1]).getName() + " out of stock; first input " + new Item(r.inIds[0]).getName() + " in bank afterwards " + a.getBank().getAmount(r.inIds[0]) + " (expect " + r.inAmts[0] * 5 + "; staples may be shop-bought)");
    }

    static void e17() {
        Artisans.Artisan a = null;
        for (Artisans.Artisan x : Artisans.SCRIPTS) if (x.bot != null) { a = x; break; }
        if (a == null) { log("E-17 no artisan script"); return; }
        Artisans.Recipe r = Artisans.RECIPES.get(0);
        for (Artisans.Recipe x : Artisans.RECIPES) if (x.trade.equals(Artisans.tradeOf(a.bot).name())) { r = x; break; }
        int before = a.bot.getBank().getAmount(r.inIds[0]);
        Artisans.Plan pl = new Artisans.Plan(); pl.r = r; pl.times = 3;
        a.plan = pl; a.step = Artisans.Step.WORK;
        Artisans.settleForShutdown();
        log("E-17 " + a.bot.getUsername() + " mid-batch of " + r.name + ": " + new Item(r.inIds[0]).getName() + " in bank " + before + " -> " + a.bot.getBank().getAmount(r.inIds[0]) + " (expect +" + r.inAmts[0] * 3 + "), step now " + a.step);
    }

    static void b18() {
        AIPlayer bot = new AIPlayer(Location.create(3305, 3125, 0)); // outside the Shantay Pass
        bot.getInventory().clear(); bot.getBank().clear(); Ledger.Wallet.give(bot, 1_000);
        AfkTasks.Runner r = new AfkTasks.Runner(bot, AfkTasks.Task.COW, true, 0, false, true);
        long c0 = Ledger.Wallet.coins(bot);
        Location desert = Location.create(3300, 3060, 0);
        r.goTo(AfkTasks.State.TO_SPOT, desert); r.goTo(AfkTasks.State.TO_SPOT, desert); r.goTo(AfkTasks.State.TO_SPOT, desert);
        log("B-18 three goTo calls into the desert: paid " + (c0 - Ledger.Wallet.coins(bot)) + " gp (expect 5, was 15)");
        AIPlayer broke = new AIPlayer(Location.create(3305, 3125, 0)); broke.getInventory().clear(); broke.getBank().clear();
        AfkTasks.Runner r2 = new AfkTasks.Runner(broke, AfkTasks.Task.COW, true, 0, false, true);
        AfkTasks.RUNNING.put(broke.getUsername().toLowerCase(), r2);
        r2.goTo(AfkTasks.State.TO_SPOT, desert);
        log("B-18 broke bot: still running " + AfkTasks.RUNNING.containsKey(broke.getUsername().toLowerCase()) + " (expect false: can't pay)");
    }

    static void b7() {
        AIPlayer bot = new AIPlayer(Location.create(3222, 3218, 0));
        Location island = Location.create(2834, 3335, 0); // Entrana: no walking route
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 3; k++) { long t0 = System.nanoTime(); Location h = NavWalker.step(bot, island); sb.append("call ").append(k + 1).append(": ").append((System.nanoTime() - t0) / 1000).append(" us hop ").append(h).append("; "); }
        log("B-7 NavWalker (on=" + NavWalker.ON + ", built=" + NavPlanner.built + ") to Entrana: " + sb + "memo " + NavWalker.NO_ROUTE.containsKey(NavWalker.noRouteKey(bot, island)) + ", noRoute count " + NavWalker.noRoute);
    }
}
