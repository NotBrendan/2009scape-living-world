package content.custom;

import core.ServerConstants;
import core.game.ge.GrandExchange;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.world.repository.Repository;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Who supplied what, and who gets paid when it sells (plan A5: "bots pay bots", FIFO).
 *
 * Every unit a bot puts into bot stock is a queue entry (supplier, quantity). When a buyer takes units out
 * (another bot's purchase, a player's GE purchase, the flood guard, the clerk fund) the oldest entries go
 * first and their suppliers are paid the guide price in coins, into their wallet. Coins are real items in the
 * bot's bank (see {@link Wallet}); nothing is created except where a player's coins, an NPC shop or the
 * capped clerk fund pays. Units that were in stock before the ledger existed belong to "world" and pay nobody.
 *
 * Persisted to data/eco/ledger.json every 10 minutes and on the hourly tick.
 */
public final class Ledger {
    static final class Entry { String supplier; int qty; long time; Entry(String s, int q, long t) { supplier = s; qty = q; time = t; } }

    static final Map<Integer, Deque<Entry>> QUEUES = new HashMap<>();
    static final Map<String, Long> EARNED = new HashMap<>();   // lifetime coins earned per supplier (for the ledger line)
    static final Map<String, Long> SPENT = new HashMap<>();
    static long clerkPaidToday;
    /** Lifetime totals for the economy page: coins bots paid out, coins handed to bots (endowments, clerk fund), coins credited to suppliers. */
    static volatile long PAID, GIVEN, CREDITED;
    /** Audit E-12: the part of GIVEN that was sale credits and owed coins handed over (not new money). GIVEN keeps its old meaning for the page. */
    static volatile long GIVEN_CREDITS;
    /** Quest rewards (QuestRunner): coins the server paid bots for finishing quests, and the guide value of the items it handed them. Not "given": the quest paid. */
    static volatile long QUEST_PAID, QUEST_ITEMS;

    static synchronized void questPaid(long coins, long itemValue) { QUEST_PAID += Math.max(0, coins); QUEST_ITEMS += Math.max(0, itemValue); }
    static String clerkDay = "";
    static final long CLERK_DAILY_CAP = 2_000_000;
    static final int CLERK_DISCOUNT_PERCENT = 60;
    static final long CLERK_AFTER_MS = 24L * 3600 * 1000;
    static final long STARTER_AFTER_MS = 6L * 3600 * 1000;

    static File file() { return new File(ServerConstants.GRAND_EXCHANGE_DATA_PATH, "ledger.json"); }

    /** A bot (or "world") put units into bot stock. Call this instead of GrandExchange.addBotOffer. */
    public static synchronized void supply(Player who, int id, int n) {
        assertThread("Ledger.supply", QUEUE_THREADS); // audit 10-23 batch14 item4
        if (n <= 0) return;
        try { if (!GrandExchange.Companion.addBotOffer(id, n)) { System.out.println("[Ledger] bot stock refused " + n + " x " + id + ": no lot booked (audit 10-23 X-16)"); try { Failures.Econ.supplyRefused(id, n, who); } catch (Throwable ignored) { } return; } } catch (Throwable t) { return; } // audit 10-23 X-16: a refused supply booked a lot nobody could ever sell
        try { core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id); if (d != null && !d.isUnnoted() && d.getNoteId() > 0) id = d.getNoteId(); } catch (Throwable ignored) { } // audit 10-23 X-16: noted goods are stocked under the unnoted id (addBotOffer); the lot must be keyed the same or it is never paid
        String s = who == null ? "world" : who.getUsername();
        Deque<Entry> q = QUEUES.computeIfAbsent(id, k -> new ArrayDeque<>());
        Entry last = q.peekLast();
        if (last != null && last.supplier.equals(s) && System.currentTimeMillis() - last.time < 60_000) last.qty += n;
        else q.addLast(new Entry(s, n, System.currentTimeMillis()));
    }

    /** The flood guard threw n units of this item away: the oldest lots lose them too, unpaid (consultant check Q2). */
    public static synchronized int trim(int id, int n) {
        assertThread("Ledger.trim", QUEUE_THREADS); // audit 10-23 batch14 item4
        Deque<Entry> q = QUEUES.get(id);
        int left = n;
        while (q != null && left > 0 && !q.isEmpty()) { // newest first: whoever kept piling on takes the loss (second consultant check C)
            Entry e = q.peekLast();
            if (e.qty <= left) { left -= e.qty; q.pollLast(); } else { e.qty -= left; left = 0; }
        }
        return n - left;
    }

    /** A bot came back under a different name (its old one couldn't be recreated): its lots and money owed follow it. */
    public static synchronized void rename(String from, String to) {
        assertThread("Ledger.rename", QUEUE_THREADS); // audit 10-23 batch14 item4
        if (from == null || to == null || from.equalsIgnoreCase(to)) return;
        int n = 0;
        for (Deque<Entry> q : QUEUES.values()) for (Entry e : q) if (e.supplier.equalsIgnoreCase(from)) { e.supplier = to; n++; }
        Long owed; synchronized (Wallet.OFFLINE) { owed = Wallet.OFFLINE.remove(from.toLowerCase(Locale.ROOT)); }
        if (owed != null && owed > 0) Wallet.credit(to, owed);
        if (n > 0 || owed != null) System.out.println("[Ledger] " + from + " is now " + to + ": " + n + " lots" + (owed != null ? ", " + owed + " gp owed" : "") + " moved");
    }

    /**
     * audit 10-23 O-1: files whose load failed (or partly failed) this boot. A copy is kept as <name>.bad-<ms>, as E-9 does for
     * the ledger. With block set, writeAtomic then refuses to write over the original until a clean load (loadedClean), so the
     * next boot reads the same file again. move renames the file aside instead of copying it (saves then start a fresh file:
     * for files that must keep taking new data, such as real players' grave items). Neither: a copy only (a partial load whose
     * bad entries are written back unchanged by the saver).
     */
    static final java.util.Set<String> LOAD_FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static String fileKey(File f) { return f.getAbsoluteFile().toPath().normalize().toString(); }
    public static File quarantine(File f, String who, Object why, boolean move, boolean block) {
        File bad = new File(f.getParentFile(), f.getName() + ".bad-" + System.currentTimeMillis());
        boolean kept;
        try {
            if (move) kept = f.renameTo(bad);
            else { java.nio.file.Files.copy(f.toPath(), bad.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); kept = true; }
        } catch (Throwable t) { kept = false; }
        if (block) LOAD_FAILED.add(fileKey(f));
        System.out.println("[" + who + "] load of " + f.getName() + " failed: " + why + (kept ? "; the file is kept as " + bad.getName() : "; could not keep a copy")
                + (block ? "; " + f.getName() + " will not be saved this boot" : move ? "; saving to a fresh file" : "") + " (audit 10-23 O-1)");
        try { Failures.Life.quarantined(f, who, why, kept ? bad : null, block); } catch (Throwable ignored) { } // batch 19c §10 file-quarantined
        return kept ? bad : null;
    }
    public static void loadedClean(File f) { LOAD_FAILED.remove(fileKey(f)); }
    /** audit 10-23 batch14 item2: true while writeAtomic refuses this file (its load failed or a sanity check blocked it). */
    public static boolean blocked(File f) { return f != null && LOAD_FAILED.contains(fileKey(f)); }
    static final Map<String, Long> NOT_SAVING_LOGGED = new java.util.concurrent.ConcurrentHashMap<>(); // audit 10-23 batch14 item1: the refusal line once an hour per file (tiles.json flushes every 20 legs)

    /** batch 22 O-4b: writeAtomic fsyncs (trials read these); an fsync failure is logged once an hour per file and the save goes on. */
    static final java.util.concurrent.atomic.AtomicLong FSYNCS = new java.util.concurrent.atomic.AtomicLong(), FSYNC_FAILED = new java.util.concurrent.atomic.AtomicLong();
    static final Map<String, Long> FSYNC_LOGGED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Writes a file all at once: a crash mid-save can no longer leave half a file that fails to load (audit #3). */
    public static void writeAtomic(File f, String text) throws java.io.IOException {
        if (f != null && LOAD_FAILED.contains(fileKey(f))) { Long last = NOT_SAVING_LOGGED.get(fileKey(f)); if (last == null || System.currentTimeMillis() - last > 3_600_000L) { NOT_SAVING_LOGGED.put(fileKey(f), System.currentTimeMillis()); System.out.println("[Ledger] not saving " + f.getName() + ": its load failed this boot, the file on disk is kept as it is (audit 10-23 O-1)"); } try { Failures.Life.saveRefused(f); } catch (Throwable ignored) { } return; } // batch 19c §10 save-refused (once per boot per file)
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp); java.io.Writer w = new java.io.OutputStreamWriter(fo)) { // FileWriter's charset (the default), as before
            w.write(text); w.flush();
            try { fo.getFD().sync(); FSYNCS.incrementAndGet(); } // batch 22 O-4b: the temp file is on disk before the rename, so a power cut cannot leave a renamed empty file
            catch (java.io.IOException e) { FSYNC_FAILED.incrementAndGet(); Long last = FSYNC_LOGGED.get(fileKey(f)); if (last == null || System.currentTimeMillis() - last > 3_600_000L) { FSYNC_LOGGED.put(fileKey(f), System.currentTimeMillis()); System.out.println("[O-4b] fsync of " + tmp.getName() + " failed: " + e + "; saved without it, as before batch 22"); } }
        }
        try { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.AccessDeniedException e) { // some filesystems: plain replace, or write in place as a last resort
            try { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.io.IOException e2) { try (FileWriter w = new FileWriter(f)) { w.write(text); } tmp.delete(); }
        }
    }

    /**
     * audit 10-23 batch14 item4: log-only thread-ownership check. Never throws; one line and one stack trace per operation and call
     * site per hour when the calling thread is not one of the allowed names. Silent once shutdown has begun (the saves then run on the
     * console or shutdown-hook thread with the world stopped). Names are the engine's and the add-ons' own thread names.
     */
    static final String GAME = "Major Update Worker", GE_WORKER = "GE Update Worker"; // core.worker.MajorUpdateWorker, core.game.ge.GrandExchange (overlay)
    static String[] QUEUE_THREADS = {GAME, "MarketBrain"}, SOLD_THREADS = {GAME, "MarketBrain", "Economy"}, CLERK_THREADS = {"MarketBrain"}, BANK_THREADS = {GAME};
    static final Map<String, Long> THREAD_LOGGED = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile int threadViolations;
    public static void assertThread(String op, String... allowed) {
        try {
            if (Wallet.SHUTTING_DOWN) return;
            String n = Thread.currentThread().getName();
            for (String a : allowed) if (a.equals(n)) return;
            threadViolations++;
            Throwable here = new Throwable("[ThreadViolation] " + op + " stack");
            StackTraceElement[] st = here.getStackTrace(); String site = st.length > 2 ? st[2].toString() : "?"; // [0] here, [1] the checked method, [2] its caller
            String key = op + "@" + site;
            long now = System.currentTimeMillis(); Long last = THREAD_LOGGED.get(key);
            if (last != null && now - last < 3_600_000L) return;
            THREAD_LOGGED.put(key, now);
            System.out.println("[ThreadViolation] " + op + " called on thread \"" + n + "\" from " + site + ", expected " + String.join(" / ", allowed) + " (audit 10-23 batch14; logged once an hour per call site)");
            try { Failures.Life.threadViolation(op, n, site); } catch (Throwable ignored) { } // batch 19c §8.2 thread-violation (same once-an-hour key)
            here.printStackTrace(System.out);
        } catch (Throwable ignored) { }
    }

    /** The bot whose lot is next in line for this item, or null. */
    public static synchronized String headSupplier(int id) {
        Deque<Entry> q = QUEUES.get(id);
        Entry e = q == null ? null : q.peekFirst();
        return e == null ? null : e.supplier;
    }

    /**
     * A buyer takes n units out of stock, paying suppliers oldest-first at the guide price. A bot buyer must be able
     * to afford it; a null buyer is a player purchase, the flood guard or the clerks (coins come from outside).
     * Returns the number of units actually taken.
     */
    public static synchronized int consume(Player buyer, int id, int n, String reason) {
        assertThread("Ledger.consume", QUEUE_THREADS); // audit 10-23 batch14 item4
        if (n <= 0) return 0;
        int unit = MarketBrain.value(id);
        if (buyer != null) {
            long cost = (long) unit * n;
            long have = Wallet.coins(buyer);
            if (have < cost) { n = (int) Math.max(0, have / Math.max(1, unit)); if (n <= 0) return 0; cost = (long) unit * n; }
            int taken = MarketBrain.takeFromStock(id, n);
            if (taken <= 0) return 0;
            int own = settle(id, taken, unit, reason, buyer.getUsername()); // audit E-11: its own units come back free (it paid for them and nobody was credited)
            Wallet.pay(buyer, (long) unit * (taken - own));
            if (taken > own) SPENT.merge(buyer.getUsername(), (long) unit * (taken - own), Long::sum);
            return taken;
        }
        int taken = MarketBrain.takeFromStock(id, n);
        if (taken > 0) settle(id, taken, unit, reason, "outside");
        return taken;
    }

    /**
     * A face-to-face deal: the buyer takes n units out of one particular supplier's lots (not the head of the queue),
     * paying that supplier at guide. Returns the number of units taken.
     */
    public static synchronized int consumeFrom(Player buyer, String supplier, int id, int n) {
        assertThread("Ledger.consumeFrom", QUEUE_THREADS); // audit 10-23 batch14 item4
        Deque<Entry> q = QUEUES.get(id);
        if (q == null || n <= 0) return 0;
        n = Math.min(n, waiting(supplier, id));
        int unit = MarketBrain.value(id);
        if (n <= 0 || Wallet.coins(buyer) < (long) unit * n) return 0;
        int taken = MarketBrain.takeFromStock(id, n);
        if (taken <= 0) return 0;
        Wallet.pay(buyer, (long) unit * taken);
        SPENT.merge(buyer.getUsername(), (long) unit * taken, Long::sum);
        int left = taken;
        for (java.util.Iterator<Entry> it = q.iterator(); it.hasNext() && left > 0; ) {
            Entry e = it.next();
            if (!e.supplier.equals(supplier)) continue;
            int take = Math.min(left, e.qty); e.qty -= take; left -= take;
            if (e.qty <= 0) it.remove();
        }
        long coins = (long) unit * taken;
        if (coins > 0 && Wallet.credit(supplier, coins)) { EARNED.merge(supplier, coins, Long::sum); SmartAdventurers.FIRST_PAID.putIfAbsent(supplier.toLowerCase(java.util.Locale.ROOT), System.currentTimeMillis()); }
        return taken;
    }

    /** Units already gone from stock (a player bought them on the GE): pay the suppliers for them. */
    public static synchronized void sold(int id, int n, int unit, String reason) {
        assertThread("Ledger.sold", SOLD_THREADS); // audit 10-23 batch14 item4
        if (n > 0) settle(id, n, unit, reason, "player");
    }

    /** Returns how many of the n units were the buyer's own lots (audit E-11). */
    static int settle(int id, int n, int unit, String reason, String buyer) {
        Deque<Entry> q = QUEUES.get(id);
        int left = n, own = 0;
        boolean had = q != null && !q.isEmpty(); // batch 19c §4.2: the queue existed before this settle
        while (left > 0 && q != null && !q.isEmpty()) {
            Entry e = q.peekFirst();
            int take = Math.min(left, e.qty);
            e.qty -= take; left -= take;
            if (e.qty <= 0) q.pollFirst();
            if (buyer.equals(e.supplier)) own += take;
            else if (!"world".equals(e.supplier)) {
                long coins = (long) unit * take;
                if (coins > 0 && Wallet.credit(e.supplier, coins)) { EARNED.merge(e.supplier, coins, Long::sum); SmartAdventurers.FIRST_PAID.putIfAbsent(e.supplier.toLowerCase(java.util.Locale.ROOT), System.currentTimeMillis()); }
            }
        }
        // units beyond the queue were pre-ledger "world" stock: nobody to pay
        if (left > 0 && had && q.isEmpty()) try { Failures.Econ.unpaidStock(id, left, reason); } catch (Throwable ignored) { } // batch 19c §4.2 item-unpaid-stock
        return own;
    }

    /** How many units of an item a supplier still has waiting in stock. */
    public static synchronized int waiting(String supplier, int id) {
        Deque<Entry> q = QUEUES.get(id);
        if (q == null) return 0;
        int n = 0;
        for (Entry e : q) if (e.supplier.equals(supplier)) n += e.qty;
        return n;
    }

    /**
     * The clerk liquidity fund: once an hour, entries that have sat unsold for a day whose supplier is broke are
     * bought by the clerks at a discount, up to a daily cap. The only coins that do not come from a player, a bot,
     * an NPC shop or alchemy, and they are logged.
     */
    static volatile long WRITTEN_OFF; static long bootAt = System.currentTimeMillis();
    /** High Alchemy coins (the game pays them, like the clerk fund): capped per day so a bow glut can't become a mint. */
    static volatile long ALCHED, alchedToday; static String alchDay = "";
    static final long ALCH_DAILY_CAP = Long.getLong("alch.cap", 150_000);
    /** batch 25 R10: coins players' AFK alchs made (AfkMagic, its own per-player daily cap); never in ALCHED or alchRoom, which stay the bots'. */
    static volatile long PLAYER_ALCHED;
    static synchronized void playerAlched(long coins) { if (coins > 0) PLAYER_ALCHED += coins; }
    static synchronized boolean alchRoom(long coins) {
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        if (!today.equals(alchDay)) { alchDay = today; alchedToday = 0; }
        if (alchedToday + coins > ALCH_DAILY_CAP) return false;
        alchedToday += coins; ALCHED += coins; return true;
    }

    /**
     * audit 10-23 batch14 item3: the other daily caps (Bounty's 500k a day, the bot buyer's global and per-account spend) are
     * kept in ledger.json under "caps" as {day, value}, like clerkDay/clerkPaidToday and alchDay/alchedToday: restored at load
     * when the day matches, zero on the first use after midnight. Each keeps its own zone (Bounty: UTC calendar day, was 24 h
     * from boot; the bot buyer: America/New_York). A restart used to hand out a fresh day's allowance.
     */
    static String utcDay() { return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(); }
    @SuppressWarnings("unchecked")
    static JSONObject capsJson() {
        JSONObject c = new JSONObject();
        try { c.put("bountyDay", Bounty.paidDay); c.put("bountyPaidToday", Bounty.paidToday); } catch (Throwable ignored) { }
        try { PrivateWorldAddons.capsTo(c); } catch (Throwable ignored) { }
        return c;
    }
    static void restoreCaps(JSONObject c) {
        if (c == null) return;
        String today = utcDay(); StringBuilder b = new StringBuilder();
        try { if (today.equals(c.get("bountyDay")) && c.get("bountyPaidToday") instanceof Number) { Bounty.paidDay = today; Bounty.paidToday = ((Number) c.get("bountyPaidToday")).longValue(); b.append("bounty ").append(Bounty.paidToday).append(" gp"); } } catch (Throwable t) { System.out.println("[Ledger] bounty cap restore: " + t); }
        try { String r = PrivateWorldAddons.capsFrom(c); if (r != null) b.append(b.length() > 0 ? ", " : "").append(r); } catch (Throwable t) { System.out.println("[Ledger] buyer cap restore: " + t); }
        System.out.println("[Ledger] daily caps (bounty day " + today + " UTC): " + (b.length() > 0 ? b + " already used today" : "nothing used yet today") + (alchedToday > 0 ? ", alch " + alchedToday + " gp (" + alchDay + ")" : "") + " (audit 10-23 batch14 item3)");
    }

    /** A real player's account (a save file exists): their credits are never written off (consultant check Q4). */
    static boolean isPlayerSave(String lower) {
        try {
            File dir = new File(core.ServerConstants.PLAYER_SAVE_PATH);
            if (!dir.isDirectory()) return false;
            String[] names = dir.list();
            if (names == null) return false;
            for (String f : names) { String base = f.endsWith(".json") ? f.substring(0, f.length() - 5) : f; if (base.equalsIgnoreCase(lower) || base.equalsIgnoreCase(lower.replace(' ', '_'))) return true; }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Credits owed to names nobody will ever log in as (bots from before names were kept) are written off (audit 10-21 E1). */
    static void writeOffDeadCredits() {
        if (System.currentTimeMillis() - bootAt < 15 * 60_000L) return; // everyone has spawned by then
        long total = 0; int n = 0;
        for (Map.Entry<String, Long> e : Wallet.OFFLINE.entrySet()) {
            String k = e.getKey();
            if (Wallet.findOnline(k) != null || SmartAdventurers.KNOWN_NAMES.contains(k) || Artisans.TRADE_OF.containsKey(k) || ServiceBots.knows(k) || isPlayerSave(k)) continue;
            if (Wallet.OFFLINE.remove(k, e.getValue())) { total += e.getValue(); n++; }
        }
        if (n > 0) { WRITTEN_OFF += total; System.out.println("[Ledger] wrote off " + String.format("%,d", total) + " gp owed to " + n + " names that no longer exist"); }
    }

    /** P37: one lot bought under its own short Ledger hold, re-checked there (still queued, still eligible), so the game thread's supply/consume wait one lot, not a whole round. Returns the coins credited, 0 when skipped. */
    private static long clerkBuy(int id, Entry e, Deque<Entry> q, int pct, int max, boolean head) {
        synchronized (Ledger.class) {
            if (e.qty <= 0 || "world".equals(e.supplier) || QUEUES.get(id) != q || (head ? q.peekFirst() != e : !q.contains(e))) return 0;
            int unit = MarketBrain.value(id) * pct / 100;
            int take = Math.min(e.qty, max);
            long cost = (long) unit * take;
            if (cost <= 0 || clerkPaidToday + cost > CLERK_DAILY_CAP) return 0;
            int taken = MarketBrain.takeFromStock(id, take);
            if (taken <= 0) return 0;
            e.qty -= taken; if (e.qty <= 0) q.remove(e);
            long coins = (long) unit * taken;
            if (!Wallet.credit(e.supplier, coins)) return 0;
            EARNED.merge(e.supplier, coins, Long::sum); clerkPaidToday += coins; return coins;
        }
    }
    /** P37: the hourly round runs on MarketBrain's Timer: a 5 ms breather every 50 lots, never on the game thread. */
    private static void clerkBreather(int lots) {
        if (lots % 50 != 0 || !"MarketBrain".equals(Thread.currentThread().getName())) return;
        try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    public static String clerkRound() { // P37: no longer synchronized for the whole round; the Ledger monitor is held per lot (fable-pulse-1634)
        assertThread("Ledger.clerkRound", CLERK_THREADS); // audit 10-23 batch14 item4
        long now = System.currentTimeMillis();
        int items = 0, starters = 0, lots = 0; long paid = 0;
        final Map<String, Long> wealth = new HashMap<>(); // audit E-8: one player scan per supplier per round, not one per lot and per sort comparison
        java.util.function.ToLongFunction<String> coinsOf = n -> wealth.computeIfAbsent(n.toLowerCase(Locale.ROOT), k -> Wallet.coinsOf(n));
        List<Object[]> heads = new ArrayList<>(); // {itemId, entry, deque}
        synchronized (Ledger.class) {
            String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
            if (!today.equals(clerkDay)) { clerkDay = today; clerkPaidToday = 0; }
            try { writeOffDeadCredits(); } catch (Throwable t) { System.out.println("[Ledger] write-off: " + t); }
            for (Map.Entry<Integer, Deque<Entry>> qe : QUEUES.entrySet()) {
                Entry e = qe.getValue().peekFirst();
                if (e != null && !"world".equals(e.supplier) && now - e.time >= CLERK_AFTER_MS) heads.add(new Object[]{qe.getKey(), e, qe.getValue()});
            }
        }
        for (Object[] o : heads) {
            Entry e = (Entry) o[1]; @SuppressWarnings("unchecked") Deque<Entry> q = (Deque<Entry>) o[2];
            if (coinsOf.applyAsLong(e.supplier) > 5_000) continue; // not broke: wait for a real buyer
            long coins = clerkBuy((Integer) o[0], e, q, CLERK_DISCOUNT_PERCENT, 100, true);
            if (coins > 0) { paid += coins; items++; clerkBreather(++lots); }
        }
        // Starter bailout: a newcomer's lot waits behind the old suppliers' queues for ever. Lots of suppliers with
        // under 1,000 gp, anywhere in the queue, are bought after 6 h (same 60% price, same daily cap, poorest first).
        List<Object[]> poor = new ArrayList<>(); // {itemId, entry, deque}
        synchronized (Ledger.class) {
            for (Map.Entry<Integer, Deque<Entry>> qe : QUEUES.entrySet())
                for (Entry e : qe.getValue())
                    if (!"world".equals(e.supplier) && now - e.time >= STARTER_AFTER_MS && e.qty > 0) poor.add(new Object[]{qe.getKey(), e, qe.getValue(), e.supplier});
        }
        poor.removeIf(o -> coinsOf.applyAsLong((String) o[3]) >= 1_000);
        poor.sort((a, b) -> Long.compare(coinsOf.applyAsLong((String) a[3]), coinsOf.applyAsLong((String) b[3])));
        for (Object[] o : poor) {
            @SuppressWarnings("unchecked") Deque<Entry> q = (Deque<Entry>) o[2];
            long coins = clerkBuy((Integer) o[0], (Entry) o[1], q, CLERK_DISCOUNT_PERCENT, 100, false);
            if (coins > 0) { paid += coins; items++; starters++; clerkBreather(++lots); }
        }
        if (starters > 0) System.out.println("[Economy] starter bailout: bought " + starters + " lots from newcomers under 1,000 gp");
        // Wholesale export (owner notes #2): an artisan's finished goods unsold after 12 h are shipped to the other
        // kingdoms at 90% of guide, so the artisans get their working capital back and keep buying from gatherers.
        int exported = 0;
        List<Object[]> stale = new ArrayList<>(); // {itemId, entry, deque}
        synchronized (Ledger.class) {
            for (Map.Entry<Integer, Deque<Entry>> qe : QUEUES.entrySet())
                for (Entry e : qe.getValue())
                    if (e.qty > 0 && now - e.time >= 12L * 3600 * 1000 && Artisans.TRADE_OF.containsKey(e.supplier.toLowerCase(Locale.ROOT))) stale.add(new Object[]{qe.getKey(), e, qe.getValue()});
        }
        for (Object[] o : stale) {
            Entry e = (Entry) o[1]; @SuppressWarnings("unchecked") Deque<Entry> q = (Deque<Entry>) o[2];
            if (!Artisans.TRADE_OF.containsKey(e.supplier.toLowerCase(Locale.ROOT))) continue; // renamed since the scan
            long coins = clerkBuy((Integer) o[0], e, q, 90, 200, false);
            if (coins > 0) { paid += coins; items++; exported++; clerkBreather(++lots); }
        }
        synchronized (Ledger.class) { for (Deque<Entry> q : QUEUES.values()) q.removeIf(x -> x.qty <= 0); }
        if (exported > 0) System.out.println("[Economy] export: shipped " + exported + " stale artisan lots abroad at 90% of guide");
        long today; synchronized (Ledger.class) { today = clerkPaidToday; }
        return items == 0 ? null : "clerk fund bought " + items + " stale lots for " + String.format("%,d", paid) + " gp (today " + String.format("%,d", today) + " of " + String.format("%,d", CLERK_DAILY_CAP) + ")";
    }

    public static synchronized String summary() {
        int lots = 0; long units = 0;
        for (Deque<Entry> q : QUEUES.values()) for (Entry e : q) { lots++; units += e.qty; }
        long earned = 0, spent = 0;
        for (long v : EARNED.values()) earned += v;
        for (long v : SPENT.values()) spent += v;
        return lots + " lots / " + String.format("%,d", units) + " attributed units; bots earned " + String.format("%,d", earned) + " gp and spent " + String.format("%,d", spent) + " gp so far";
    }

    @SuppressWarnings("unchecked")
    public static synchronized void save() {
        try { MarketBrain.drainFills(); } catch (Throwable ignored) { } // audit E-2: pay for bot-stock fills still queued (shutdown save)
        try {
            JSONObject root = new JSONObject();
            JSONObject items = new JSONObject();
            for (Map.Entry<Integer, Deque<Entry>> qe : QUEUES.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Entry e : qe.getValue()) { JSONObject j = new JSONObject(); j.put("s", e.supplier); j.put("q", e.qty); j.put("t", e.time); arr.add(j); }
                if (!arr.isEmpty()) items.put(String.valueOf(qe.getKey()), arr);
            }
            root.put("items", items);
            JSONObject earned = new JSONObject(); earned.putAll(EARNED); root.put("earned", earned);
            JSONObject spent = new JSONObject(); spent.putAll(SPENT); root.put("spent", spent);
            root.put("clerkDay", clerkDay); root.put("clerkPaidToday", clerkPaidToday);
            root.put("caps", capsJson()); // audit 10-23 batch14 item3
            root.put("questPaid", QUEST_PAID); root.put("questItems", QUEST_ITEMS); root.put("paid", PAID); root.put("given", GIVEN); root.put("givenCredits", GIVEN_CREDITS); root.put("credited", CREDITED); root.put("restored", Wallet.RESTORED); root.put("writtenOff", WRITTEN_OFF); root.put("alched", ALCHED); root.put("playerAlched", PLAYER_ALCHED); root.put("alchDay", alchDay); root.put("alchedToday", alchedToday);
            JSONObject off = new JSONObject(); synchronized (Wallet.OFFLINE) { off.putAll(Wallet.OFFLINE); } root.put("offline", off); // coins owed to bots that are logged out
            file().getParentFile().mkdirs();
            writeAtomic(file(), root.toJSONString()); // a crash mid-write used to truncate the ledger (review 10-22)
        } catch (Throwable t) { System.out.println("[Ledger] save failed: " + t); try { Failures.Life.saveFailed("ledger.json", t); } catch (Throwable ignored) { } } // batch 19c §10 save-failed
    }

    public static synchronized void load() {
        File f = file();
        if (!f.exists()) return;
        try {
            JSONObject root = (JSONObject) new JSONParser().parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)); // read and closed, so a bad file can be moved aside (E-9)
            JSONObject items = (JSONObject) root.get("items");
            if (items != null) for (Object k : items.keySet()) {
                Deque<Entry> q = new ArrayDeque<>();
                for (Object o : (JSONArray) items.get(k)) { JSONObject j = (JSONObject) o; q.addLast(new Entry(String.valueOf(j.get("s")), ((Number) j.get("q")).intValue(), ((Number) j.get("t")).longValue())); }
                QUEUES.put(Integer.parseInt(String.valueOf(k)), q);
            }
            JSONObject earned = (JSONObject) root.get("earned");
            if (earned != null) for (Object k : earned.keySet()) EARNED.put(String.valueOf(k), ((Number) earned.get(k)).longValue());
            JSONObject spent = (JSONObject) root.get("spent");
            if (spent != null) for (Object k : spent.keySet()) SPENT.put(String.valueOf(k), ((Number) spent.get(k)).longValue());
            if (root.get("clerkDay") != null) clerkDay = String.valueOf(root.get("clerkDay"));
            if (root.get("clerkPaidToday") != null) clerkPaidToday = ((Number) root.get("clerkPaidToday")).longValue();
            if (root.get("paid") != null) PAID = ((Number) root.get("paid")).longValue();
            if (root.get("given") != null) GIVEN = ((Number) root.get("given")).longValue();
            if (root.get("givenCredits") != null) GIVEN_CREDITS = ((Number) root.get("givenCredits")).longValue(); // audit E-12 (absent in older files)
            if (root.get("restored") != null) Wallet.RESTORED = ((Number) root.get("restored")).longValue();
            if (root.get("writtenOff") != null) WRITTEN_OFF = ((Number) root.get("writtenOff")).longValue();
            if (root.get("alched") != null) ALCHED = ((Number) root.get("alched")).longValue();
            if (root.get("playerAlched") != null) PLAYER_ALCHED = ((Number) root.get("playerAlched")).longValue(); // batch 25 R10 (absent in older files)
            if (root.get("alchDay") != null) alchDay = String.valueOf(root.get("alchDay"));
            if (root.get("alchedToday") != null) alchedToday = ((Number) root.get("alchedToday")).longValue();
            if (root.get("questPaid") != null) QUEST_PAID = ((Number) root.get("questPaid")).longValue();
            if (root.get("questItems") != null) QUEST_ITEMS = ((Number) root.get("questItems")).longValue();
            if (root.get("credited") != null) CREDITED = ((Number) root.get("credited")).longValue();
            restoreCaps(root.get("caps") instanceof JSONObject ? (JSONObject) root.get("caps") : null); // audit 10-23 batch14 item3
            JSONObject off = (JSONObject) root.get("offline");
            if (off != null) synchronized (Wallet.OFFLINE) { for (Object k : off.keySet()) Wallet.OFFLINE.merge(String.valueOf(k), ((Number) off.get(k)).longValue(), Long::sum); }
            System.out.println("[Ledger] loaded: " + summary());
        } catch (Throwable t) { // audit E-9: the next 10-minute save would have written an empty ledger over the only copy
            File bad = new File(f.getParentFile(), f.getName() + ".bad-" + System.currentTimeMillis());
            boolean moved = f.renameTo(bad);
            System.out.println("[Ledger] load failed: " + t + (moved ? "; the unreadable file is kept as " + bad.getName() : "; could not move the unreadable file aside"));
        }
    }

    /** Coins as real items in a bot's bank; a bot that is offline keeps its balance in the ledger until it is back. */
    public static final class Wallet {
        static final int COINS = 995;
        static final Map<String, Long> OFFLINE = new java.util.concurrent.ConcurrentHashMap<>(); // credits for bots not currently online (audit: was a plain HashMap written from several threads)

        public static long coins(Player p) {
            try { return p.getBank().getAmount(COINS) + (long) p.getInventory().getAmount(COINS); } catch (Throwable t) { return 0; }
        }

        /** The server's name index doesn't know bots (AIPlayer skips it), so look through the players (audit 10-21 B1). */
        public static Player findOnline(String name) {
            if (name == null) return null;
            Player p = null;
            try { p = Repository.getPlayerByName(name); } catch (Throwable ignored) { }
            if (p != null && p.isActive()) return p;
            for (Player q : Repository.getPlayers()) if (q != null && q.isActive() && q.getUsername().equalsIgnoreCase(name)) return q;
            return null;
        }

        /** Coins put back from a saved profile at a restart: not new money, so not counted as "given" (audit 10-21 E2). */
        public static boolean restore(Player p, long amount) {
            long before = GIVEN;
            boolean ok = give(p, amount);
            GIVEN = before; RESTORED += amount;
            return ok;
        }
        public static volatile long RESTORED;

        public static long coinsOf(String name) {
            Player p = findOnline(name);
            return p != null ? coins(p) : OFFLINE.getOrDefault(name.toLowerCase(Locale.ROOT), 0L);
        }

        /**
         * A teleport: a law rune and three airs, like a player casting it. Runes the Exchange has are bought from it
         * (the runecrafter and the essence miners get paid through the ledger); only the part it can't supply is paid
         * in coins that leave the game, as before. the owner's notes 2026-10-20 (#5).
         */
        public static boolean payTeleport(Player p) { return payTeleport(p, callerKind()); }

        /** Batch 22-1: the calling method as the paid-action kind ("Preflight.out"), for the callers that give no reason. Rare calls only. */
        static String callerKind() {
            try { return StackWalker.getInstance().walk(f -> f.skip(2).findFirst()).map(fr -> fr.getClassName().replaceFirst("^.*\\.", "") + "." + fr.getMethodName()).orElse("teleport"); }
            catch (Throwable t) { return "teleport"; }
        }

        /** Batch 22-1: a toll or fare paid in coins, with the gp log line every paid path has (see payTeleport). Same result as pay(). */
        public static boolean payFee(Player p, long amount, String why) {
            boolean ok = pay(p, amount);
            if (ok && p != null && p.isArtificial()) System.out.println("[Paid] " + p.getUsername() + " " + why + " " + amount + " gp");
            return ok;
        }

        /** Batch 22-1: with the reason (the paid-action kind CoinWatch counts fleet-wide, and the gp log line's text). */
        public static boolean payTeleport(Player p, String why) {
            long cost = Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556));
            if (p == null || coins(p) < cost) return false;
            CoinWatch.paid(p, why); // batch 22 task 4: the coin-drain alarm's paid-action count (bug 296); never throws
            int law = MarketBrain.stockOf(563) >= 1 ? consume(p, 563, 1, "teleport") : 0;
            int air = MarketBrain.stockOf(556) >= 3 ? consume(p, 556, 3, "teleport") : 0;
            long rest = (law >= 1 ? 0 : MarketBrain.value(563)) + (3L - air) * MarketBrain.value(556);
            if (p.isArtificial()) System.out.println("[Paid] " + p.getUsername() + " " + why + " " + cost + " gp (runes " + (law >= 1 && air >= 3 ? "from the Exchange" : "partly in coins " + Math.max(1, rest)) + ")"); // batch 22-1: every paid teleport has a gp line
            if (law >= 1 && air >= 3) return true;
            return pay(p, Math.max(1, rest));
        }

        /** Takes coins from the bank first, then the inventory. Returns false (and takes nothing) if short. */
        public static boolean pay(Player p, long amount) {
            assertThread("Wallet.pay", BANK_THREADS); // audit 10-23 batch14 item4
            if (amount <= 0) return true;
            if (coins(p) < amount) return false;
            PAID += amount;
            try {
                long fromBank = Math.min(amount, p.getBank().getAmount(COINS));
                boolean okB = true, okI = true; // batch 19c §4.7: the removes' results, read for pay-failed-with-coins only (pay still returns as before)
                if (fromBank > 0) okB = p.getBank().remove(new Item(COINS, (int) fromBank));
                long rest = amount - fromBank;
                if (rest > 0) okI = p.getInventory().remove(new Item(COINS, (int) rest));
                try { Failures.walletMove(p, -amount); if (!okB || !okI) Failures.Econ.payFailed(p, amount, amount, (okB ? "" : "bank remove false ") + (okI ? "" : "pack remove false")); } catch (Throwable ignored) { } // batch 19c §4.1 / §4.7
                return true;
            } catch (Throwable t) { try { Failures.Econ.payFailed(p, amount, coins(p), String.valueOf(t)); } catch (Throwable ignored) { } return false; } // batch 19c §4.7: had the coins, the remove threw
        }

        public static boolean credit(String name, long amount) {
            if (amount <= 0) return false;
            CREDITED += amount;
            Player p = findOnline(name);
            if (p == null || !p.isActive() || SHUTTING_DOWN) { OFFLINE.merge(name.toLowerCase(Locale.ROOT), amount, Long::sum); return true; } // audit 10-23 X-7: at shutdown a queued pulse never runs; owed coins are saved and collected at the next login
            if (!p.isArtificial()) { final Player hp = p; final long amt = amount; core.game.world.GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() { try { hp.sendMessage("<col=0b5e1f>Your Exchange lot sold for " + String.format("%,d", amt) + " gp; it's in your bank."); } catch (Throwable ignored) { } return true; } }); }
            // Banks are only touched on the game thread (audit #1): a credit from a timer thread is queued there.
            if (!Thread.currentThread().getName().contains("Major Update")) {
                final Player who = p; final long amt = amount;
                core.game.world.GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() { if (!who.isActive() || !give(who, amt, true)) OFFLINE.merge(who.getUsername().toLowerCase(Locale.ROOT), amt, Long::sum); return true; } }); // audit 10-23 S-9: a bot that logged out meanwhile is owed it, not its cleared bank
                return true;
            }
            return give(p, amount, true);
        }

        public static boolean give(Player p, long amount) { return give(p, amount, false); }

        /** audit 10-23 X-7: set first thing at shutdown; credits then go to OFFLINE (saved with the ledger) instead of pulses that never run. */
        public static volatile boolean SHUTTING_DOWN;

        /** sale: a sale credit or owed coins being handed over, counted in GIVEN_CREDITS as well (audit E-12). */
        static boolean give(Player p, long amount, boolean sale) {
            assertThread("Wallet.give", BANK_THREADS); // audit 10-23 batch14 item4
            if (amount <= 0) return false;
            GIVEN += amount;
            if (sale) GIVEN_CREDITS += amount;
            try {
                int cur = p.getBank().getAmount(COINS);
                long over = Math.max(0, (long) cur + amount - Integer.MAX_VALUE); // audit E-12: what doesn't fit the stack was silently lost; now kept as owed
                long total = Math.min(Integer.MAX_VALUE, cur + amount);
                if (cur > 0) p.getBank().remove(new Item(COINS, cur));
                if (p.getBank().add(new Item(COINS, (int) total))) { try { Failures.walletMove(p, amount - over); } catch (Throwable ignored) { } return owe(p, over); } // batch 19c §4.1
                // bank full (no coin stack to add to): the pack, and failing that put the old stack back (audit #14)
                if (cur > 0) p.getBank().add(new Item(COINS, cur));
                try { Failures.bankFull(p, "wallet.give", COINS); } catch (Throwable ignored) { } // batch 19c §4.5 bank-full
                if (p.getInventory().add(new Item(COINS, (int) Math.min(Integer.MAX_VALUE, amount - over)))) { try { Failures.walletMove(p, amount - over); } catch (Throwable ignored) { } return owe(p, over); } // batch 19c §4.1
                GIVEN -= amount; if (sale) GIVEN_CREDITS -= amount; // audit 10-23 X-8: nothing was handed over
                try { Failures.Econ.itemLost(p, COINS, amount, "wallet.give", "-"); } catch (Throwable ignored) { } // batch 19c §4.2 item-lost-on-handover
                return false;
            } catch (Throwable t) { return false; }
        }

        /** Audit E-12: coins beyond a full stack wait in OFFLINE (collected at the next login or return), logged. Always true. */
        static boolean owe(Player p, long over) {
            if (over <= 0) return true;
            OFFLINE.merge(p.getUsername().toLowerCase(Locale.ROOT), over, Long::sum);
            System.out.println("[Ledger] " + p.getUsername() + "'s coin stack is full: " + String.format("%,d", over) + " gp kept as owed");
            return true;
        }

        /** When a bot comes back (same name), hand over what it earned while away. */
        public static long collect(Player p) {
            Long due = OFFLINE.remove(p.getUsername().toLowerCase(Locale.ROOT));
            if (due == null || due <= 0) return 0;
            if (!give(p, due, true)) { OFFLINE.merge(p.getUsername().toLowerCase(Locale.ROOT), due, Long::sum); return 0; } // audit 10-23 X-8: full bank and pack: still owed, not lost
            try { Failures.econNote(p, Failures.COLLECT); } catch (Throwable ignored) { } // batch 19c §4.1
            return due;
        }
    }
}
