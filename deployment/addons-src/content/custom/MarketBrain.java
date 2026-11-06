package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.ge.OfferState;
import core.game.ge.PriceIndex;
import core.game.node.item.Item;
import core.game.world.GameSettings;
import core.game.world.GameWorld;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The market brain (phases 0 and 1 of the smarter-bot-economy plan).
 *
 *  - Demand board: every 5 minutes, for every item any bot can produce, how far bot stock is below
 *    its value-based target (plus a boost while players have unfilled buy offers). The deficits are
 *    the "bounties" the smart adventurers and artisans work from.
 *  - Flood guard: items above 5x their target are "surplus". Bots stop gathering them, and once an
 *    hour the surplus is trimmed by 1% so the piles shrink slowly instead of forever.
 *  - Ledger: every hour, the change in bot stock per item is written to the journal and a stock
 *    snapshot is saved under data/eco/stock-history/ so the economy can be watched over time.
 */
public final class MarketBrain implements StartupListener {
    static final Timer TIMER = new Timer("MarketBrain", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");
    static final int SURPLUS_MULT = 3; // audit E4: 5x let 14k raw fish pile up
    static final double TRIM_PER_HOUR = 0.01;

    static volatile Map<Integer, Integer> stock = new HashMap<>();
    static volatile Map<Integer, Integer> playerWants = new HashMap<>();
    static volatile Map<Integer, Double> bounties = new HashMap<>();
    static volatile Set<Integer> surplus = new HashSet<>();
    /** Units players bought on the GE in the last 7 days, per item. */
    static volatile Map<Integer, Integer> playerBought = new HashMap<>();
    /** E29: units players bought recently, decaying by 1% an hour (half-life about three days); skews bounties toward what sells. */
    static final Map<Integer, Double> DEMAND = new java.util.concurrent.ConcurrentHashMap<>();
    static Map<Integer, Integer> lastSnapshot;

    /**
     * Review C (2026-10-20): every hunt drop was in the catalogue with a full stock target, so drops nobody uses
     * (zogre bones, crawling-hand claws, snail shells) sat at 0 stock and got the full gap-squared and x2 "none at all"
     * weight, as if the server were desperate for them. High-tier bots then ground zogres and werewolves. Only items
     * something actually consumes (artisan inputs and products, gathered goods, gear bots wear, food and potions,
     * runes, bones for burying, dragonhides) or a player has asked for get the urgent weighting; the rest get a small
     * flat weight so they still sell, but never pull a job by themselves.
     */
    static boolean consumed(int id) {
        if (USED.contains(id)) return true;
        try {
            core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id);
            if (d == null) return false;
            if (d.hasAction("drink")) return true;                            // potions bots carry
            if (d.getEquipId() > 0) {                                         // gear a bot would buy: shop stock or a goal (not snelms)
                if (SHOP_GEAR.isEmpty() && !BotChat.WorldData.shops.isEmpty()) {
                    for (Object[] shop : BotChat.WorldData.shops) for (int sid : (int[]) shop[3]) SHOP_GEAR.add(sid);
                    for (int[] g : SmartAdventurers.GOALS) SHOP_GEAR.add(g[0]);
                }
                if (SHOP_GEAR.contains(id)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Recipe inputs/products and gathered goods, plus the consumables a 2009 player bought: runes, bones, dragonhides. */
    static final Set<Integer> USED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final Set<Integer> SHOP_GEAR = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static {
        for (int id = 554; id <= 566; id++) USED.add(id);                                  // runes
        for (int id : new int[]{526, 532, 534, 536, 1739, 1747, 1749, 1751, 1753, 6287, 9978}) USED.add(id); // bones, big/baby/dragon bones, hides
        for (int id : PlayerTrades.FOODS) USED.add(id);                                   // the cooked food players and bots eat
    }

    /** Everything a bot can produce: task products plus artisan recipe products. */
    static final Set<Integer> CATALOGUE = java.util.concurrent.ConcurrentHashMap.newKeySet(); // hunt drops are added after HuntMap builds

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        for (AfkTasks.Task t : AfkTasks.Task.values()) if (t.products != null) for (int id : t.products) if (id != 995) CATALOGUE.add(id); // coins are not stock
        Ledger.load();
        TIMER.schedule(new TimerTask() { public void run() { Ledger.save(); } }, 600_000, 600_000);
        TIMER.schedule(new TimerTask() { public void run() { try { drainFills(); } catch (Throwable t) { System.out.println("[MarketBrain] fills: " + t); } } }, 20_000, 20_000); // audit E-2
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                try { refresh(); } catch (Throwable t) { System.out.println("[MarketBrain] refresh failed: " + t); }
            }
        }, FAST ? 15_000 : 90_000, FAST ? 60_000 : 300_000);
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                try { hourly(); } catch (Throwable t) { System.out.println("[MarketBrain] hourly failed: " + t); }
            }
        }, FAST ? 30_000 : 120_000, FAST ? 120_000 : 3_600_000);
        System.out.println("[MarketBrain] watching " + CATALOGUE.size() + " task items; board every 5 min, ledger hourly");
    }

    // Price lookups hit SQLite under the GE lock; cache them so bot ticks don't stall the game thread.
    static final Map<Integer, Boolean> TRADE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<Integer, long[]> VALUE_CACHE = new java.util.concurrent.ConcurrentHashMap<>(); // id -> {value, time}

    static boolean tradeable(int id) {
        Boolean b = TRADE_CACHE.get(id);
        if (b != null) return b;
        try { b = PriceIndex.canTrade(id); } catch (Throwable t) { return false; }
        TRADE_CACHE.put(id, b);
        return b;
    }

    /** PriceDrift just rewrote the index: forget cached guide prices so the next lookups see the new values. */
    static void priceCacheClear() { VALUE_CACHE.clear(); }

    static int value(int id) {
        long[] c = VALUE_CACHE.get(id);
        long now = System.currentTimeMillis();
        if (c != null && now - c[1] < 600_000) return (int) c[0];
        int v;
        try { v = Math.max(1, PriceIndex.INSTANCE.getValue(id)); } catch (Throwable t) { return c != null ? (int) c[0] : 1; }
        VALUE_CACHE.put(id, new long[]{v, now});
        return v;
    }

    /**
     * Audit E-4: what a bot pays a player for an item. The guide moves on any player-to-player trade, so two alts could
     * push a thin item up and sell it to the bots for new coins; this is the guide kept within 70-150% of the item's
     * base price (PriceDrift.BASE, recorded the first time a bot prices the item if drift never saw it).
     */
    /** audit 10-23 X-4: botRef for the GE overlay (BotPrices): bot stock is never priced under it. */
    public static int botRefFor(int id) { return botRef(id); }

    static int botRef(int id) {
        int v = value(id);
        if (!PriceDrift.LOADED) return v; // drift off or its file unreadable: never write a partial base file
        Integer base = PriceDrift.BASE.get(id);
        if (base == null) { PriceDrift.BASE.put(id, v); PriceDrift.saveSoon(); return v; }
        long lo = Math.max(1, Math.round(base * PriceDrift.FLOOR)), hi = Math.max(lo, Math.round(base * PriceDrift.CEILING));
        return (int) Math.max(lo, Math.min(hi, v));
    }

    /**
     * Takes n of an item out of bot stock through the GE worker queue, never below zero.
     * Returns how many were actually taken (0 when the pile is empty).
     */
    /**
     * Own lock, not the class monitor: Ledger.consume (holding the Ledger lock) calls this, while the hourly round and the
     * refresh (holding the MarketBrain lock) call into the Ledger. Two class locks taken in opposite orders froze the
     * whole game thread in a trial on 2026-10-20 (a deadlock waiting to happen on the live server too).
     */
    static final Object TAKE_LOCK = new Object();

    public static int takeFromStock(int id, int n) {
        // audit 10-23 X-2: read the pile and reserve under the GE database lock (always taken before TAKE_LOCK), so the GE worker's
        // player fills, which hold that lock, always see every reservation made against the pile they read.
        final int[] r = {0};
        try { core.game.ge.GEDB.run(conn -> { synchronized (TAKE_LOCK) { r[0] = takeLocked(id, n); } return kotlin.Unit.INSTANCE; }); }
        catch (Throwable t) { System.out.println("[MarketBrain] stock take: " + t); }
        return r[0];
    }
    /** audit 10-23 X-2: units of this item reserved for bot takes the GE worker has not applied yet. */
    public static int reservedTakes(int id) { Integer v = RESERVED_TAKES.get(id); return v == null ? 0 : v; }

    static int takeLocked(int id, int n) {
        Ledger.assertThread("MarketBrain.reserve", Ledger.QUEUE_THREADS); // audit 10-23 batch14 item4
        int have;
        try { have = GrandExchange.Companion.getBotstockForId(id); } catch (Throwable t) { return 0; }
        // Audit E-6: a reservation now lasts until the GE worker has applied the take (takeApplied), not a 20 s window
        // that could expire while the take still sat in the queue; the 5-minute wipe is only a safety net.
        if (System.currentTimeMillis() - reservedSince > 300_000) { if (!RESERVED_TAKES.isEmpty()) try { geTakeStalled("safety-wipe"); } catch (Throwable ignored) { } RESERVED_TAKES.clear(); reservedSince = System.currentTimeMillis(); oldestReservedT = 0; } // batch 19c §4.3: a wipe with takes still reserved = the worker did not apply them
        int reserved = RESERVED_TAKES.getOrDefault(id, 0);
        n = Math.min(n, have - reserved);
        if (n <= 0) return 0;
        if (RESERVED_TAKES.isEmpty()) oldestReservedT = System.currentTimeMillis(); // batch 19c §4.3: empty -> non-empty
        RESERVED_TAKES.merge(id, n, Integer::sum);
        GrandExchange.Companion.getPendingOffers().addLast(GrandExchangeOffer.Companion.createBotOffer(id, -n, true));
        return n;
    }

    static final Map<Integer, Integer> RESERVED_TAKES = new java.util.concurrent.ConcurrentHashMap<>(); // audit 10-23 X-2: read by the GE worker. Takes queued but not yet applied by the GE worker
    static long reservedSince = System.currentTimeMillis();
    static volatile long oldestReservedT; // batch 19c §4.3: when the reservation map last went from empty to non-empty (0: empty)
    static final Map<Integer, long[]> SHORTFALL = new java.util.concurrent.ConcurrentHashMap<>(); // batch 19c §4.2: id -> {units, events, window start, last wanted, last took}
    static volatile long oldestFillT; // batch 19c §4.3: when FILLS last went from empty to non-empty (0: empty)
    static volatile long takeShortfall; // units a take wanted that were no longer there (a player bought them first)

    /** Audit E-6, called by the GE worker (overlay GrandExchange): a queued take was applied, floored at zero. */
    public static void takeApplied(int id, int wanted, int took) {
        Ledger.assertThread("MarketBrain.release", Ledger.GE_WORKER); // audit 10-23 batch14 item4
        synchronized (TAKE_LOCK) { RESERVED_TAKES.computeIfPresent(id, (k, v) -> v - wanted > 0 ? v - wanted : null); if (RESERVED_TAKES.isEmpty()) oldestReservedT = 0; } // batch 19c §4.3
        if (took < wanted) { takeShortfall += wanted - took; System.out.println("[MarketBrain] stock take of " + wanted + " x " + name(id) + " found only " + took + " (floored at zero, audit E-6)");
            try { long[] s = SHORTFALL.computeIfAbsent(id, k -> new long[]{0, 0, System.currentTimeMillis(), 0, 0}); synchronized (s) { s[0] += wanted - took; s[1]++; s[3] = wanted; s[4] = took; } } catch (Throwable ignored) { } } // batch 19c §4.2 stock-shortfall (read by refresh)
    }

    /** Audit E-2: player buys filled from bot stock, reported by the GE worker; paid out by drainFills off that thread. */
    static final java.util.concurrent.ConcurrentLinkedQueue<int[]> FILLS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Called by the GE worker (overlay GrandExchange) when a player's buy took units out of bot stock. Never blocks. */
    public static void botStockFilled(int id, int qty, int unitPrice, int playerUid) {
        if (qty > 0) { if (oldestFillT == 0) oldestFillT = System.currentTimeMillis(); FILLS.add(new int[]{id, qty, unitPrice, playerUid}); } // batch 19c §4.3: the queue's age (no player data kept)
    }

    /** Pays the suppliers (FIFO) for the units players really bought from bot stock (audit E-2). */
    static void drainFills() {
        int[] f;
        while ((f = FILLS.poll()) != null) {
            Ledger.sold(f[0], f[1], value(f[0]), "player");
            DEMAND.merge(f[0], (double) f[1], Double::sum);
            if (Boolean.getBoolean("afk.debug") || Boolean.getBoolean("test.e2")) System.out.println("[MarketBrain] bot stock sold " + f[1] + " x " + name(f[0]) + " to player uid " + f[3] + " at " + f[2] + " each");
        }
        if (FILLS.isEmpty()) oldestFillT = 0; // batch 19c §4.3
    }

    /** How much of an item the market should hold (same curve the artisans use). */
    static int target(int id) {
        return Artisans.target(id);
    }

    static Map<Integer, Integer> readStock() {
        Map<Integer, Integer> m = new HashMap<>();
        for (GrandExchangeOffer o : GrandExchange.Companion.getBotOffers()) {
            int amt = o.getAmount();
            // Bot offers report at most botstock_limit (5000); ask the database for the real size of big piles.
            if (amt >= 5000) { try { amt = GrandExchange.Companion.getBotstockForId(o.getItemID()); } catch (Throwable ignored) { } }
            m.merge(o.getItemID(), amt, Integer::sum);
        }
        return m;
    }

    /** Open player buy offers that nobody has filled: real demand. */
    static Map<Integer, Integer> readPlayerWants() {
        Map<Integer, Integer> m = new HashMap<>();
        for (GrandExchangeOffer o : GrandExchange.Companion.getValidOffers()) {
            if (o.getSell() || o.getAmountLeft() <= 0) continue;
            OfferState st = o.getOfferState();
            if (st == OfferState.COMPLETED || st == OfferState.ABORTED || st == OfferState.REMOVED) continue;
            if ((long) o.getOfferedValue() * 100 < (long) value(o.getItemID()) * 90) continue; // audit 10-23 X-5: a lowball order (100,000 coal at 1 gp, escrow refundable) put an item on the bounty board for its owner
            m.merge(o.getItemID(), o.getAmountLeft(), Integer::sum);
        }
        for (Map.Entry<Integer, Integer> e : m.entrySet()) { int cap = 2 * Math.max(1, target(e.getKey())); if (e.getValue() > cap) e.setValue(cap); } // audit 10-23 X-5: and no more than twice the target
        return m;
    }

    /** Completed player SALES in the last day, per (player uid, item): the bounty board pays premiums on these. */
    static Map<String, Integer> readPlayerSales() {
        Map<String, Integer> m = new HashMap<>();
        long since = System.currentTimeMillis() - 24L * 3600 * 1000;
        try {
            core.game.ge.GEDB.run(conn -> {
                try {
                    java.sql.PreparedStatement st = conn.prepareStatement("SELECT player_uid, item_id, SUM(amount_complete) FROM player_offers WHERE is_sale = 1 AND amount_complete > 0 AND time_stamp > ? GROUP BY player_uid, item_id");
                    st.setLong(1, since);
                    java.sql.ResultSet rs = st.executeQuery();
                    while (rs.next()) m.put(rs.getInt(1) + ":" + rs.getInt(2), rs.getInt(3));
                    st.close();
                } catch (Throwable t) { System.out.println("[MarketBrain] player sales: " + t); }
                return kotlin.Unit.INSTANCE;
            });
        } catch (Throwable ignored) { }
        return m;
    }

    static Map<String, Integer> playerSales = new HashMap<>();
    static boolean salesPrimed;

    static Map<Integer, Integer> readPlayerBought() {
        Map<Integer, Integer> m = new HashMap<>();
        long since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        try {
            core.game.ge.GEDB.run(conn -> {
                try {
                    java.sql.PreparedStatement st = conn.prepareStatement("SELECT item_id, SUM(amount_complete) FROM player_offers WHERE is_sale = 0 AND amount_complete > 0 AND time_stamp > ? GROUP BY item_id");
                    st.setLong(1, since);
                    java.sql.ResultSet rs = st.executeQuery();
                    while (rs.next()) m.put(rs.getInt(1), rs.getInt(2));
                    st.close();
                } catch (Throwable t) { System.out.println("[MarketBrain] player purchases: " + t); }
                return kotlin.Unit.INSTANCE;
            });
        } catch (Throwable ignored) { }
        return m;
    }

    /** Extra stock to hold because players actually buy this: twice last week's purchases, up to 4x the base. */
    public static int demandBoost(int id, int base) {
        return Math.min(base * 4, 2 * playerBought.getOrDefault(id, 0));
    }

    static synchronized void refresh() {
        playerBought = readPlayerBought(); // the 7-day level still sizes demandBoost
        try { geChecks(System.currentTimeMillis()); } catch (Throwable ignored) { } // batch 19c §4.2/§4.3: before drainFills (logging only)
        // Audit E-2: suppliers are paid from the GE worker's bot-stock fills (drainFills), no longer from a diff of every
        // completed player buy (alt-to-alt trades paid bots; old buys leaving the 7-day window cancelled new ones).
        drainFills();
        // Audit E-3: bounties are paid by the bot buyer when a player's sale goes into bot stock (PrivateWorldAddons),
        // no longer on any completed player sale (two alts selling to each other drained the artisans' funds).
        Map<Integer, Integer> s = readStock();
        Map<Integer, Integer> wants = readPlayerWants();
        for (Artisans.Recipe r : Artisans.RECIPES) { CATALOGUE.add(r.product); USED.add(r.product); if (r.inIds != null) for (int in : r.inIds) USED.add(in); }
        for (AfkTasks.Task t : AfkTasks.Task.values()) if (t.products != null) for (int id : t.products) USED.add(id);
        Map<Integer, Double> b = new HashMap<>();
        Set<Integer> sur = new HashSet<>();
        for (int id : CATALOGUE) {
            if (!tradeable(id)) continue;
            int have = s.getOrDefault(id, 0);
            int want = target(id) + wants.getOrDefault(id, 0);
            if (have >= want * SURPLUS_MULT) { sur.add(id); continue; }
            if (have >= want) continue;
            double gap = (want - have) / (double) want;
            double weight = consumed(id) || wants.containsKey(id)
                    ? gap * gap * Math.log10(10 + value(id)) * (have == 0 ? 2 : 1) * (wants.containsKey(id) ? 1.5 : 1) * (1 + Math.min(1.0, DEMAND.getOrDefault(id, 0.0) / 50.0))
                    : 0.25 * gap * Math.log10(10 + value(id)) * (1 + Math.min(1.0, DEMAND.getOrDefault(id, 0.0) / 50.0)); // nobody uses it: sells, never urgent
            b.put(id, weight);
        }
        for (Map.Entry<Integer, Integer> e : s.entrySet()) {
            if (e.getValue() >= target(e.getKey()) * SURPLUS_MULT) sur.add(e.getKey());
        }
        stock = s; playerWants = wants; bounties = b; surplus = sur;
        try { staleOffers(s); PriceDrift.outliers("refresh"); } catch (Throwable ignored) { } // batch 19c §4.3 / §4.6
    }

    // ------------------------------------------------------------ batch 19c §4.2/§4.3: the GE pipeline checks (MarketBrain thread, every refresh; logging only)
    static final String B_TAKES = "b-ge-take-stalled-takes", B_FILLS = "b-ge-fills-unpaid-fills", B_PENDING = "b-ge-pending-backlog-pending", B_PLAYERS = "b-ge-player-offers-stale-players";
    static void geTakeStalled(String how) {
        long age = oldestReservedT > 0 ? (System.currentTimeMillis() - oldestReservedT) / 1000 : -1; int units = 0; for (int v : RESERVED_TAKES.values()) units += v;
        Failures.event("economy", null, "ge", "ge-take-stalled", how + ": " + RESERVED_TAKES.size() + " items reserved, oldest " + age + " s", null, Failures.ctx("items", RESERVED_TAKES.size(), "units", units, "oldestS", age, "how", how, "key", "takes"));
    }
    static void geChecks(long now) {
        boolean stalled = !RESERVED_TAKES.isEmpty() && oldestReservedT > 0 && now - oldestReservedT > 120_000L;
        if (stalled) geTakeStalled("refresh");
        Failures.periodic(B_TAKES, stalled);
        int fills = FILLS.size(); long fillAge = oldestFillT > 0 ? now - oldestFillT : 0;
        boolean unpaid = fills > 200 || fillAge > 15 * 60_000L;
        if (unpaid) Failures.event("economy", null, "ge", "ge-fills-unpaid", fills + " fills queued, oldest " + fillAge / 1000 + " s", null, Failures.ctx("fills", fills, "oldestS", fillAge / 1000, "key", "fills"));
        Failures.periodic(B_FILLS, unpaid);
        int pending = -1; try { pending = GrandExchange.Companion.getPendingOffers().size(); } catch (Throwable ignored) { }
        boolean backlog = pending > 500;
        if (backlog) Failures.event("economy", null, "ge", "ge-pending-backlog", pending + " pending offers", null, Failures.ctx("pending", pending, "key", "pending"));
        Failures.periodic(B_PENDING, backlog);
        for (Map.Entry<Integer, long[]> e : SHORTFALL.entrySet()) {
            long[] s = e.getValue(); long u, n, w, t;
            synchronized (s) { if (now - s[2] < 10 * 60_000L) continue; u = s[0]; n = s[1]; w = s[3]; t = s[4]; s[0] = 0; s[1] = 0; s[2] = now; }
            if (u > 100 || n > 5) Failures.event("economy", null, "ge", "stock-shortfall", u + " units short in " + n + " takes", null, Failures.ctx("item", e.getKey(), "wanted", w, "took", t, "shortfall10m", u, "events", n, "key", String.valueOf(e.getKey())));
            if (u == 0 && n == 0) SHORTFALL.remove(e.getKey());
        }
    }
    /** batch 19c §4.3 ge-player-offers-stale: aggregate only (count and top item ids; no uid, no name, no per-offer amounts). */
    static void staleOffers(Map<Integer, Integer> s) {
        long now = System.currentTimeMillis(); int n = 0; Map<Integer, Integer> by = new HashMap<>();
        for (GrandExchangeOffer o : GrandExchange.Companion.getValidOffers()) {
            if (o.getSell() || o.getAmountLeft() <= 0) continue;
            OfferState st = o.getOfferState();
            if (st == OfferState.COMPLETED || st == OfferState.ABORTED || st == OfferState.REMOVED) continue;
            if (now - o.getTimeStamp() < 24 * 3_600_000L || (long) o.getOfferedValue() * 100 < (long) value(o.getItemID()) * 90) continue;
            if (s.getOrDefault(o.getItemID(), 0) < o.getAmountLeft()) continue;
            n++; by.merge(o.getItemID(), 1, Integer::sum);
        }
        boolean stale = n >= 10;
        if (stale) { java.util.List<Object> top = new java.util.ArrayList<>(); by.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(5).forEach(e -> top.add(e.getKey()));
            Failures.event("economy", null, "ge", "ge-player-offers-stale", n + " player buy offers open > 24 h that bot stock could fill", null, Failures.ctx("offers", n, "items", top, "key", "players")); }
        Failures.periodic(B_PLAYERS, stale);
    }

    /** True when the market already has far too much of this item: gatherers should leave it alone. */
    public static boolean isSurplus(int id) {
        return surplus.contains(id);
    }

    /** Bounty weight for an item, 0 when the market has enough. */
    public static double bounty(int id) {
        return bounties.getOrDefault(id, 0.0);
    }

    public static int stockOf(int id) {
        return stock.getOrDefault(id, 0);
    }

    static final class Entry implements Comparable<Entry> {
        final int id; final double weight;
        Entry(int id, double weight) { this.id = id; this.weight = weight; }
        public int compareTo(Entry o) { return Double.compare(o.weight, weight); }
    }

    public static List<Entry> topBounties(int n) {
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : bounties.entrySet()) list.add(new Entry(e.getKey(), e.getValue()));
        Collections.sort(list);
        return list.size() > n ? list.subList(0, n) : list;
    }

    static String name(int id) {
        try { return new Item(id).getName(); } catch (Throwable t) { return "item " + id; }
    }

    /**
     * Ledger + trim, once an hour.
     * P18: the stock read, the hourly-change line, the snapshot and the trim plan run under the MarketBrain monitor only
     * (no tick code takes it). The paid steps run after it, each taking the GE database / Ledger lock for its own call, so
     * the game tick is never held behind the whole pass. Same numbers, same order, same log lines as before.
     */
    /** P18: pause between the hourly pass's lock-taking steps so the game tick (600 ms) gets the GE / Ledger locks in between. */
    static final long HOURLY_GAP_MS = 40;
    static void hourlyGap() { try { Thread.sleep(HOURLY_GAP_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }

    static void hourly() {
        final List<int[]> trimPlan = new ArrayList<>(); // {item id, planned cut}, in the old iteration order of the stock map
        synchronized (MarketBrain.class) {
        for (Map.Entry<Integer, Double> e : DEMAND.entrySet()) { double v = e.getValue() * 0.99; if (v < 0.5) DEMAND.remove(e.getKey()); else e.setValue(v); }
        Map<Integer, Integer> now = readStock();
        if (lastSnapshot != null) {
            List<Entry> deltas = new ArrayList<>();
            Set<Integer> ids = new HashSet<>(now.keySet());
            ids.addAll(lastSnapshot.keySet());
            for (int id : ids) {
                int d = now.getOrDefault(id, 0) - lastSnapshot.getOrDefault(id, 0);
                if (d != 0) deltas.add(new Entry(id, d));
            }
            deltas.sort((a, b2) -> Double.compare(Math.abs(b2.weight), Math.abs(a.weight)));
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Entry e : deltas) {
                if (shown++ >= 15) break;
                if (sb.length() > 0) sb.append(", ");
                sb.append(name(e.id)).append(' ').append(e.weight > 0 ? "+" : "").append((int) e.weight);
            }
            System.out.println("[Economy] hourly change (" + deltas.size() + " items moved): " + sb);
            if (!deltas.isEmpty()) {
                Entry top = deltas.get(0);
                String n = name(top.id).toLowerCase();
                BotChat.news(top.weight > 0
                        ? String.format(BotChat.pick("Everyone and their dog is bringing in %s today. Don't bother, the price will tank.", "%s are piling up at the Exchange. Somebody's been busy.", "You can't give %s away at the moment. The clerks have stacks."), n)
                        : String.format(BotChat.pick("%s are flying off the shelves today. Blink and they're gone.", "Can't get %s for love nor money at the moment.", "Somebody's buying up every last one of the %s. Wonder what they know."), n));
            }
        }
        lastSnapshot = now;

        // Snapshot to disk for the long view.
        try {
            File dir = new File(ServerConstants.GRAND_EXCHANGE_DATA_PATH, "stock-history");
            dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HH").format(new Date());
            try (FileWriter w = new FileWriter(new File(dir, "stock-" + stamp + ".json"))) {
                w.write("{\"time\":\"" + stamp + "\",\"stock\":{");
                boolean first = true;
                for (Map.Entry<Integer, Integer> e : now.entrySet()) {
                    if (!first) w.write(",");
                    first = false;
                    w.write("\"" + e.getKey() + "\":" + e.getValue());
                }
                w.write("}}");
            }
            // audit E-13: one file an hour for ever; keep 30 days (names sort by time: stock-yyyyMMdd-HH.json)
            String cutoff = "stock-" + new SimpleDateFormat("yyyyMMdd-HH").format(new Date(System.currentTimeMillis() - 30L * 86_400_000L)) + ".json";
            File[] old = dir.listFiles((d, n) -> n.startsWith("stock-") && n.endsWith(".json") && n.compareTo(cutoff) < 0);
            int pruned = 0; if (old != null) for (File o : old) if (o.delete()) pruned++;
            if (pruned > 0) System.out.println("[Economy] stock history: removed " + pruned + " snapshots older than 30 days");
        } catch (Throwable t) {
            System.out.println("[Economy] snapshot failed: " + t);
        }

        // Flood guard: trim 1% an hour off anything far above target. P18: plan here (no GE / Ledger lock), trim below.
        for (Map.Entry<Integer, Integer> e : now.entrySet()) {
            int cap = target(e.getKey()) * SURPLUS_MULT;
            if (e.getValue() <= cap) continue;
            // 1% an hour normally; 3% when a pile is more than ten times its cap, so it beats the old bots' inflow.
            double rate = e.getValue() > cap * 10 ? 0.03 : TRIM_PER_HOUR;
            int cut = Math.max(1, (int) ((e.getValue() - cap) * rate));
            trimPlan.add(new int[]{e.getKey(), cut});
        }
        } // end of the MarketBrain-only section (P18)
        int trimmedItems = 0;
        long trimmedUnits = 0;
        for (int[] t : trimPlan) { // P18: each item takes the GE database lock (takeFromStock) and then the Ledger lock (trim) for itself only
            hourlyGap(); // ~52 items x 40 ms: the trims spread over about 2 s instead of running back to back
            int cut = takeFromStock(t[0], t[1]);
            if (cut <= 0) continue;
            try { Ledger.trim(t[0], cut); } catch (Throwable ignored) { } // the sellers' lots shrink with the pile (consultant check Q2)
            trimmedItems++;
            trimmedUnits += cut;
        }
        if (trimmedItems > 0) System.out.println("[Economy] flood guard trimmed " + trimmedUnits + " units across " + trimmedItems + " oversupplied items");
        // P18: same calls, same order, same lines; each is its own short Ledger-lock hold, no outer lock, with a tick-sized gap between them.
        try { hourlyGap(); String c = Ledger.clerkRound(); if (c != null) System.out.println("[Economy] " + c); hourlyGap(); System.out.println("[Economy] ledger: " + Ledger.summary()); hourlyGap(); Ledger.save(); } catch (Throwable t) { System.out.println("[Economy] ledger failed: " + t); }
        List<Entry> top = new ArrayList<>();
        int unfillable = 0;
        // P18: no lock held here. topBounties/stockOf read the volatile maps refresh() swaps whole; capable() only reads live bots.
        for (Entry e : topBounties(200)) { // only things some bot alive right now has the levels to go and get, or make
            boolean reachable = SmartAdventurers.capable(e.id);
            if (!reachable) unfillable++;
            if (!reachable) for (Artisans.Recipe r : Artisans.RECIPES) if (r.product == e.id) { boolean ok = true; for (int k = 0; k < r.inIds.length; k++) if (stockOf(r.inIds[k]) <= Artisans.KEEP_INPUT) ok = false; if (ok) reachable = true; }
            if (reachable) { boolean dup = false; for (Entry x : top) if (name(x.id).equals(name(e.id))) dup = true; if (!dup) top.add(e); } // one "Gloves", not five colours of them (audit §13)
            if (top.size() >= 8) break;
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : top) { if (sb.length() > 0) sb.append(", "); sb.append(name(e.id)).append(" (").append(stockOf(e.id)).append(")"); }
        System.out.println("[Economy] most wanted: " + sb + (unfillable > 0 ? " (" + unfillable + " other bounties nobody has the levels for)" : ""));
        try { // the Varrock local calls the bounty board like a town crier
            String[] lines = Bounty.crierLines();
            core.game.node.entity.player.Player crier = BotLife.VARROCK_LOCAL;
            if (lines != null && crier != null && crier.isActive()) { SmartAdventurers.crierRally(crier); BotChat.speak(crier, null, java.util.Arrays.asList(lines), 1); if (Boolean.getBoolean("afk.debug")) System.out.println("[Economy] crier " + crier.getUsername() + ": " + lines[0]); }
        } catch (Throwable t) { System.out.println("[Economy] crier failed: " + t); }
        if (!top.isEmpty()) {
            String n1 = name(top.get(0).id).toLowerCase();
            BotChat.news(String.format(BotChat.pick("Nobody's selling %s. First one to bring some in makes a killing.", "%s are going for silly money right now, if you can get hold of any.", "The clerks keep asking for %s. Shelf's been bare for hours.", "Want easy coin? %s. The Exchange has none and everyone wants them."), n1));
        }
    }
}
