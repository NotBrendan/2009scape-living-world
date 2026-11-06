package content.custom;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batch 24: the one haggle engine (the design notes sections 3 and 6, amendments 1-9).
 *
 * Callers keep their own parsing and their own trade-window settlement; they only ask {@link #decide} for a verdict
 * and report a finished sale with {@link #sold}. This class never moves coins or items (no Ledger, no Wallet, no
 * inventory): money guard 1, "no new coin source". Plain java.* only, so TestBargain runs without server.jar.
 *
 * Money guards, all in {@link #clamp}:
 *  - selling: floor <= total <= the current quote <= the list price (a bot never raises its price on a rich player);
 *  - the per-trader daily discount budget (amendment 8): min(3% of that trader's list-price sales today, 2,000 gp);
 *    once spent the trader holds list price;
 *  - buying (PlayerTrades, a later stage): total <= guide.
 * Exit rule: {@link #on} is false unless -Dbargain.on=true, and it turns itself off for good (until the marker file is
 * removed and the server restarted) the moment a trader's discounts pass its daily budget, a final lands outside
 * [floor, list], or {@link #coinAlarm} is called by a coin monitor.
 * The AFK runner's own purchases never haggle: a session opened with afk=true is list price, take it or leave it.
 */
public final class Bargain {
    private Bargain() { }

    public enum Verdict { ACCEPT, COUNTER, FINAL, REFUSE, WALK }

    static final double[] ACCEPT_AT = {0.96, 0.93, 0.90};  // of the quote, by trust tier: stranger / regular / mate
    static final double COUNTER_AT = 0.55;                 // under this (or "free") is a lowball
    static final double BUDGET_PCT = 0.03;                 // amendment 8
    static final long BUDGET_CAP = 2_000;
    static final long STRIKE_GAP = 10 * 60_000L;           // at most one trust strike per pair per 10 min
    static final long HOLD_FIRST = 15 * 60_000L, HOLD_REPEAT = 60 * 60_000L, HOLD_ABUSE = 6 * 3_600_000L; // amendment 2
    static final long MOOD_WINDOW = 30 * 60_000L;
    static final String MARKER = "bargain-off";            // in the data dir: present = engine stays off after restart
    static final String LOG = "botchat-deals.log";
    static final long LOG_MAX = 2L << 20;                  // 2 MB, then rotate; keep 4 (amendment 5)
    static final Random RNG = new Random();

    static volatile boolean tripped;
    static volatile String tripReason = "";
    static volatile File dataDir;                          // set by BargainHooks at startup; null = no log, no marker

    static volatile long offCheckAt;                       // next runtime look for the marker (ms); 0 = look on the next on()

    /**
     * Kill switch: -Dbargain.on=true and not tripped. Default OFF. With the flag on, the marker file is also looked for at
     * runtime, at most once per -Dbargain.offCheckSec (default 60) s, so "touch data/bargain-off" stops haggling without a
     * restart. Deleting it does not re-arm a tripped engine (delete it and restart). The flag-off path does no file I/O.
     */
    public static boolean on() {
        if (!Boolean.getBoolean("bargain.on") || tripped) return false;
        long now = System.currentTimeMillis();
        if (now >= offCheckAt) {
            offCheckAt = now + Math.max(1L, Long.getLong("bargain.offCheckSec", 60L)) * 1000L;
            File d = dataDir;
            if (d != null && new File(d, MARKER).exists()) offFound();
        }
        return !tripped;
    }

    /** The marker appeared on a running server: off for good (the file is already there, so it is not rewritten). */
    static synchronized void offFound() {
        if (tripped) return;
        tripped = true; tripReason = "marker file found at runtime";
        System.out.println("[Bargain] OFF: data/" + MARKER + " found at runtime (traders hold list price; remove " + MARKER + " and restart to re-arm)");
    }

    /** Per (bot, player) haggle. */
    public static final class Session {
        final String bot, player;
        final boolean buying, afk;
        final long list, floor;  // selling: list = the pitch, never exceeded; buying: list = guide (the cap), floor = 0
        final int tier;
        public int units;        // batch 24b stage 2: the qty this session prices (Deals part-lots); a sale of another qty is not booked
        long quote;              // the bot's current price; only ever moves toward the player
        int tries, strikes;
        boolean countered;
        Session(String bot, String player, boolean buying, long list, long floor, int trust, boolean afk) {
            this.bot = bot; this.player = player; this.buying = buying; this.afk = afk;
            this.list = Math.max(0, list);
            this.floor = buying ? 0 : Math.max(0, Math.min(floor, this.list));
            this.tier = trust >= 6 ? 2 : trust >= 3 ? 1 : 0;
            this.quote = this.list;
        }
        public long quote() { return quote; }
    }

    public static final class Result {
        public final Verdict verdict; public final long total; public final String lineKey; public final boolean trustStrike; public final long holdUntil;
        public boolean lowball; // batch 24b item 2: this bid was a lowball (the caller counts it in Memory.Met.lowballs)
        Result(Verdict v, long total, String lineKey, boolean trustStrike, long holdUntil) { this.verdict = v; this.total = total; this.lineKey = lineKey; this.trustStrike = trustStrike; this.holdUntil = holdUntil; }
        Result low() { lowball = true; return this; }
        @Override public String toString() { return verdict + " " + total + " (" + lineKey + ")"; }
    }

    // ---- sessions, walk-aways, daily books, mood
    static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    static final Map<String, long[]> WALKS = new ConcurrentHashMap<>();   // pair -> {walk count, hold until, last strike}
    static final Map<String, long[]> DAY = new ConcurrentHashMap<>();     // bot -> {epoch day, list-price sales, discount given}
    static final Map<String, long[]> MOOD = new ConcurrentHashMap<>();    // bot -> {last event time, mood}

    static String pair(String bot, String player) { return (player + "|" + bot).toLowerCase(); }

    /** Opens (or replaces) a selling session: list = the pitch total, floor = max(cost, 92% ref, low alch) summed by the caller. */
    public static Session sell(String bot, String player, long list, long floor, int trust, boolean afk) {
        Session s = new Session(bot, player, false, list, floor, trust, afk); SESSIONS.put(pair(bot, player), s); return s;
    }
    /** Opens a buying session (the bot buys from a player): the total never exceeds guide. */
    public static Session buy(String bot, String player, long guide, int trust, boolean afk) {
        Session s = new Session(bot, player, true, guide, 0, trust, afk); SESSIONS.put(pair(bot, player), s); return s;
    }
    public static Session session(String bot, String player) { return SESSIONS.get(pair(bot, player)); }
    public static void close(String bot, String player) { SESSIONS.remove(pair(bot, player)); }

    /** Walk-away hold: the trader will not deal with this player before this time (0 = none). */
    public static long holdUntil(String bot, String player) { long[] w = WALKS.get(pair(bot, player)); return w == null ? 0 : w[1]; }

    static long day(long now) { return Math.floorDiv(now, 86_400_000L); } // UTC day
    static long[] book(String bot, long now) {
        long d = day(now);
        return DAY.compute(bot.toLowerCase(), (k, b) -> b == null || b[0] != d ? new long[]{d, 0, 0} : b);
    }
    /** Discount this trader may still give on a sale listed at {@code list}: min(3% of today's sales incl. this one, 2,000) minus what it gave today. */
    public static long budgetLeft(String bot, long list, long now) {
        long[] b = book(bot, now);
        long cap = Math.min(BUDGET_CAP, (long) Math.floor(BUDGET_PCT * (b[1] + Math.max(0, list))));
        return Math.max(0, cap - b[2]);
    }

    public static int mood(String bot, long now) {
        long[] m = MOOD.get(bot.toLowerCase());
        if (m == null) return 0;
        long steps = (now - m[0]) / MOOD_WINDOW;  // decays one step toward 0 per 30 min
        long v = m[1];
        if (steps > 0) v = v > 0 ? Math.max(0, v - steps) : Math.min(0, v + steps);
        return (int) v;
    }
    static void moodBy(String bot, int d, long now) {
        int v = Math.max(-2, Math.min(2, mood(bot, now) + d));
        MOOD.put(bot.toLowerCase(), new long[]{now, v});
    }

    /** The only place a total is finalised (guard 2). */
    public static long clamp(Session s, long total, long now) {
        if (s.buying) return Math.max(0, Math.min(total, s.list));
        long low = Math.max(s.floor, s.list - budgetLeft(s.bot, s.list, now));
        long t = Math.max(total, low);
        return Math.min(t, Math.min(s.quote, s.list));
    }

    /**
     * The verdict on a player's bid. {@code bid} is the total the player named (already turned into a lot total by
     * Haggle.Bid.total); {@code free} for "free"/"for nothing". Selling only; buying sessions go through {@link #clamp}.
     */
    public static Result decide(Session s, long bid, boolean free, long now) {
        if (s.afk) // no haggling for the AFK runner's own purchases
            return bid >= s.quote && !free ? new Result(Verdict.ACCEPT, s.quote, "accept", false, 0) : new Result(Verdict.FINAL, s.quote, "afk", false, 0);
        String pk = pair(s.bot, s.player);
        long[] w = WALKS.get(pk);
        boolean held = w != null && w[1] > now;
        if (bid >= s.quote && !free) return new Result(Verdict.ACCEPT, s.quote, "accept", false, 0);
        long budget = budgetLeft(s.bot, s.list, now);
        boolean broke = budget <= 0;
        boolean lowball = free || bid <= 0 || bid < s.quote * COUNTER_AT;
        if (lowball) {
            s.strikes++;
            int walkAt = s.tier == 0 ? 2 : 3;
            if (held || s.strikes >= walkAt) return walk(s, pk, w, held, now).low();
            if (s.strikes == 1) return new Result(Verdict.REFUSE, s.quote, "lowball", false, 0).low();
            return new Result(Verdict.FINAL, s.quote, "final", false, 0).low();
        }
        if (broke) return new Result(Verdict.FINAL, s.quote, "budget", false, 0);
        if (s.tries >= (s.tier == 2 ? 3 : 2)) return new Result(Verdict.FINAL, s.quote, "final", false, 0);
        s.tries++;
        double a = ACCEPT_AT[s.tier] - (mood(s.bot, now) > 0 ? 0.02 : 0);
        long low = clamp(s, 0, now);  // the lowest price allowed right now (floor or budget)
        if (bid >= s.quote * a && bid >= low) { long t = clamp(s, bid, now); s.quote = t; return new Result(Verdict.ACCEPT, t, "accept", false, 0); }
        long mid = Haggle_round((s.quote + bid) / 2);
        if (s.tries >= 2) mid -= (long) ((s.quote - bid) * 0.05 * s.tier); // a step lower per tier on the second try
        long t = clamp(s, mid, now);
        if (t >= s.quote) return new Result(Verdict.FINAL, s.quote, "final", false, 0);
        s.quote = t; s.countered = true;
        return new Result(Verdict.COUNTER, t, "counter", false, 0);
    }

    static Result walk(Session s, String pk, long[] w, boolean abuse, long now) {
        long count = w == null ? 0 : w[0], lastStrike = w == null ? 0 : w[2];
        long hold = now + (abuse ? HOLD_ABUSE : count == 0 ? HOLD_FIRST : HOLD_REPEAT);
        boolean strike = now - lastStrike >= STRIKE_GAP;
        WALKS.put(pk, new long[]{count + 1, Math.max(hold, w == null ? 0 : w[1]), strike ? now : lastStrike});
        SESSIONS.remove(pk);
        moodBy(s.bot, -1, now);
        log(now, s, "WALK", s.quote, 0);
        return new Result(Verdict.WALK, s.quote, "walk", strike, hold);
    }

    /** A finished sale at {@code total} (the coins the trade window took). Books the discount, applies the exit rule, logs. */
    public static void sold(Session s, long total, long now) {
        if (s == null) return;
        SESSIONS.remove(pair(s.bot, s.player));
        if (s.buying) { if (total > s.list) trip("bought over guide: " + s.bot + " " + total + " > " + s.list); log(now, s, "BUY", total, 0); return; }
        long[] b = book(s.bot, now);
        long disc = Math.max(0, s.list - total);
        synchronized (b) { b[1] += s.list; b[2] += disc; }
        moodBy(s.bot, 1, now);
        log(now, s, "SALE", total, disc);
        long cap = Math.min(BUDGET_CAP, (long) Math.floor(BUDGET_PCT * b[1]));
        if (b[2] > cap) trip("discount budget passed: " + s.bot + " gave " + b[2] + " of " + cap);
        if (total < s.floor || total > s.list) trip("final outside [floor, list]: " + s.bot + " " + total + " not in [" + s.floor + ", " + s.list + "]");
    }

    /**
     * Hook for the coin monitor: CoinWatch.tick calls this on an alarm (batch 24b item 1) and the engine turns off for good
     * (marker written). A no-op while the engine is off, so a flag-off server never writes the marker on an unrelated alarm.
     */
    public static void coinAlarm(String reason) { if (on()) trip("coin alarm: " + reason); }

    static synchronized void trip(String reason) {
        if (tripped) return;
        tripped = true; tripReason = reason;
        System.out.println("[Bargain] OFF: " + reason + " (traders hold list price; remove " + MARKER + " and restart to re-arm)");
        File d = dataDir;
        if (d != null) try (Writer out = new FileWriter(new File(d, MARKER))) { out.write(Instant.now() + " " + reason + "\n"); } catch (IOException e) { System.out.println("[Bargain] marker write failed: " + e); }
    }

    /** Startup: the data dir for the log and marker; a marker left by an earlier trip keeps the engine off. */
    static void init(File dir) {
        dataDir = dir;
        if (dir != null && new File(dir, MARKER).exists()) { tripped = true; tripReason = "marker file present"; System.out.println("[Bargain] OFF: " + MARKER + " present in " + dir); }
    }

    static synchronized void log(long now, Session s, String what, long total, long disc) {
        File d = dataDir; if (d == null) return;
        try {
            File f = new File(d, LOG);
            if (f.length() > LOG_MAX) { // size cap, keep 4 (amendment 5; weekly gzip stays with the live server log rotation)
                for (int i = 3; i >= 1; i--) { File a = new File(d, LOG + "." + i); if (a.exists()) a.renameTo(new File(d, LOG + "." + (i + 1))); }
                f.renameTo(new File(d, LOG + ".1"));
            }
            try (Writer out = new FileWriter(f, true)) {
                out.write(Instant.ofEpochMilli(now) + " " + what + " bot=" + s.bot + " player=" + s.player + " list=" + s.list + " floor=" + s.floor
                        + " final=" + total + " disc=" + disc + " tries=" + s.tries + " strikes=" + s.strikes + " tier=" + s.tier + "\n");
            }
        } catch (IOException e) { System.out.println("[Bargain] log failed: " + e); }
    }

    // ---- memory and trust recovery (batch 24b item 2: design section 5, amendment 1). Plain data: BotChat.Memory.Met holds
    // and saves it, BargainHooks applies it. Nothing here touches trust itself.
    static final long DAY_MS = 86_400_000L;
    static final int DEALS_KEPT = 5;

    /** One remembered deal (Memory.Met.deals, last 5): saved as "bot|item|qty|total|when". */
    public static final class Deal {
        public final String bot, item; public final int qty; public final long total, when;
        public Deal(String bot, String item, int qty, long total, long when) {
            this.bot = clean(bot); this.item = clean(item); this.qty = Math.max(0, qty); this.total = Math.max(0, total); this.when = when;
        }
        static String clean(String v) { return v == null ? "" : v.replace('|', '/').trim(); }
        public String encode() { return bot + "|" + item + "|" + qty + "|" + total + "|" + when; }
        /** null for anything malformed (a hand-edited or truncated save never breaks the load). */
        public static Deal decode(String v) {
            if (v == null) return null;
            String[] f = v.split("\\|", -1);
            if (f.length != 5 || f[0].isEmpty()) return null;
            try { return new Deal(f[0], f[1], Integer.parseInt(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4])); } catch (NumberFormatException e) { return null; }
        }
        @Override public String toString() { return encode(); }
    }

    /** Adds a deal and keeps the last {@link #DEALS_KEPT}, newest last. */
    public static void remember(List<Deal> deals, Deal d) {
        if (deals == null || d == null) return;
        synchronized (deals) { deals.add(d); while (deals.size() > DEALS_KEPT) deals.remove(0); }
    }

    /** The newest deal with this bot for this item inside 7 days, or null ("same as last week"). */
    public static Deal lastDeal(List<Deal> deals, String bot, String item, long now) {
        if (deals == null) return null;
        synchronized (deals) {
            for (int i = deals.size() - 1; i >= 0; i--) {
                Deal d = deals.get(i);
                if (d.bot.equalsIgnoreCase(bot) && d.item.equalsIgnoreCase(item) && now - d.when <= 7 * DAY_MS) return d;
            }
        }
        return null;
    }

    /**
     * Amendment 1: a walk-away strike that really took a trust point is owed back, +1 per 24 h. {@code owed} = {points, since};
     * a new strike adds a point and restarts the clock. Returns the (possibly new) array.
     */
    public static long[] struck(long[] owed, long now) {
        long[] o = owed == null ? new long[2] : owed;
        o[0]++; o[1] = now;
        return o;
    }

    /** Points due back now: one per full 24 h since {@code owed[1]}, never more than owed. Updates owed (points and clock). */
    public static int recoverDue(long[] owed, long now) {
        if (owed == null || owed.length < 2 || owed[0] <= 0 || now <= owed[1]) return 0;
        long n = Math.min(owed[0], (now - owed[1]) / DAY_MS);
        if (n <= 0) return 0;
        owed[0] -= n; owed[1] += n * DAY_MS;
        return (int) n;
    }

    /** Same rounding as Haggle.round, kept here so this class stays server-free. */
    static long Haggle_round(long n) {
        if (n >= 10_000) return n / 100 * 100;
        if (n >= 1_000) return n / 10 * 10;
        return n;
    }

    // ---- lines (bargain-lines.json, loaded by BargainHooks; these are the fallbacks)
    static final Map<String, String[]> LINES = new HashMap<>();
    static {
        LINES.put("accept", new String[]{"Done. {price} gp, trade me, {name}."});
        LINES.put("counter", new String[]{"Meet me halfway: {price} gp."});
        LINES.put("final", new String[]{"{price} gp is my final price, {name}."});
        LINES.put("lowball", new String[]{"Ha! Nice try. {price} gp."});
        LINES.put("walk", new String[]{"I'm done here, {name}. Good day."});
        LINES.put("budget", new String[]{"Given enough away today. {price} gp."});
        LINES.put("afk", new String[]{"List price, {price} gp, no haggling."});
    }
    /** A line for {@code key}, preferring "key.STYLE" when the JSON has one. */
    public static String line(String key, String style, String name, String price) {
        String[] v = style == null ? null : LINES.get(key + "." + style);
        if (v == null || v.length == 0) v = LINES.get(key);
        if (v == null || v.length == 0) v = new String[]{"{price} gp."};
        return v[RNG.nextInt(v.length)].replace("{name}", name == null ? "friend" : name).replace("{price}", price);
    }

}
