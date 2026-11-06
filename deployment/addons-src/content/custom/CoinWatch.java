package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Batch 22 task 4: the coin-drain alarm (bug 296: bots paid the sea-crossing teleport again and again, 490 paid crossings by
 * 30 bots in 6 h, one bot 94; bot coins fell from 204,068 to 75,104 and nothing said so). Logging only: it never moves coins.
 *
 * Total bot coins = online smart adventurers' wallets (Ledger.Wallet.coins: bank + pack) + the coins in the offline profiles
 * (SmartAdventurers.OFFLINE, as saved in smartbots.json) + the credits owed to logged-out bots (Ledger.Wallet.OFFLINE).
 * A baseline is taken once the world has warmed up, then a check runs every hour:
 *   - batch 22-1: the total fell more than coinwatch.dropPct (default 25) percent over coinwatch.dropHours (default 3) hourly checks
 *     with every hour falling, or more than coinwatch.singlePct (default 40) in one hour (bot count stable; dropReason), or
 *   - one kind of paid action (the why of Wallet.payTeleport, "boat") over coinwatch.maxKind (default 60) an hour across all bots, or
 *   - a bot made more than coinwatch.maxPaid (default 25) paid actions (Ledger.Wallet.payTeleport) in the current hour
 *     (looked at every minute, so a runaway bot is flagged within the hour it starts)
 * -> ONE "[CoinWatch] ALARM ..." line for that check and a Failures record per kind (domain economy, reason coin-drain,
 *    bug ids "b-coin-drain-296-total" / "b-coin-drain-296-paid"; cleared by Failures.periodic after clean hourly checks).
 * A "[CoinWatch] total ..." status line every coinwatch.logMin (default 10) minutes and at each hourly check (soak.sh reads it).
 * Switches: -Dcoinwatch=false (kill switch: no pulse, no counting), -Dcoinwatch.dropPct, -Dcoinwatch.maxPaid,
 * -Dcoinwatch.periodMin (default 60), -Dcoinwatch.warmMin (default 5), -Dcoinwatch.logMin (default 10).
 * P38 L7: baseline only at FLEET_PCT of the fleet or after the count held HOLD_SAMPLES samples (-Dcoinwatch.fleet, .fleetPct,
 * .holdSamples, .holdMin); log-only "[CoinWatch] RISE ..." line behind -Dcoinwatch.rise=true (.riseGp, .riseMin), see rise().
 * design: the design notes (not published). Game thread (its own 100-tick pulse); paid() may be called from any thread.
 */
public final class CoinWatch implements StartupListener {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("coinwatch", "true"));
    static final int DROP_PCT = Math.max(1, Integer.getInteger("coinwatch.dropPct", 25)); // batch 22-1: over DROP_HOURS hourly checks, every hour falling
    static final int DROP_HOURS = Math.max(1, Integer.getInteger("coinwatch.dropHours", 3));
    static final int SINGLE_PCT = Math.max(1, Integer.getInteger("coinwatch.singlePct", 40)); // one hour alone
    static final int MAX_KIND = Math.max(1, Integer.getInteger("coinwatch.maxKind", 60)); // one paid action, whole fleet, an hour
    static final int MAX_PAID = Math.max(1, Integer.getInteger("coinwatch.maxPaid", 25));
    static final long PERIOD_MS = Math.max(1L, Long.getLong("coinwatch.periodMin", 60L)) * 60_000L;
    static final long WARM_MS = Math.max(0L, Long.getLong("coinwatch.warmMin", 5L)) * 60_000L;
    static final boolean TRIP_ON_PAID = !"false".equalsIgnoreCase(System.getProperty("bargain.tripOnPaid", "true")); // batch 24b item 1
    static final long LOG_MS = Math.max(1L, Long.getLong("coinwatch.logMin", 10L)) * 60_000L;
    static final String BUG_TOTAL = "b-coin-drain-296-total", BUG_PAID = "b-coin-drain-296-paid", BUG_KIND = "b-coin-drain-296-kind";
    // P38 L7 (a): the baseline waits until the bot count reaches FLEET_PCT of the configured fleet (-Dcoinwatch.fleet, default
    // smartbots.count) or holds within 10% for HOLD_SAMPLES samples HOLD_MS apart (live 11-05: the baseline locked on the 45-bot
    // login plateau 10-40 min before the 102-bot fleet, so every later percentage read +7..+28% high; the design notes).
    static final int FLEET_PCT = Math.max(1, Math.min(100, Integer.getInteger("coinwatch.fleetPct", 90)));
    static final int HOLD_SAMPLES = Math.max(2, Integer.getInteger("coinwatch.holdSamples", 3));
    static final long HOLD_MS = Math.max(1L, Long.getLong("coinwatch.holdMin", LOG_MS / 60_000L)) * 60_000L;
    // P38 L7 (b): log-only rise rule, default off. The clerk fund (Ledger.clerkRound) is the only coin mint and pays at most
    // Ledger.CLERK_DAILY_CAP a day, so a rise of more than that cap + 25% inside RISE_MS (bot count stable) cannot be the clerk.
    static final boolean RISE_ON = Boolean.getBoolean("coinwatch.rise");
    static final long RISE_GP = Math.max(1L, Long.getLong("coinwatch.riseGp", Ledger.CLERK_DAILY_CAP * 125L / 100L));
    static final long RISE_MS = Math.max(1L, Long.getLong("coinwatch.riseMin", 60L)) * 60_000L;

    /** Paid actions per bot (lowercase name) in the current hourly window; reset at each hourly check. */
    static final Map<String, AtomicInteger> PAID = new ConcurrentHashMap<>();
    /** Bots already named in a paid alarm this window (one alarm per bot per window). */
    static final Set<String> FLAGGED = ConcurrentHashMap.newKeySet();
    /** Paid actions per kind (the why given to Wallet.payTeleport, "boat", ...) over all bots in the current window. */
    static final Map<String, AtomicInteger> KINDS = new ConcurrentHashMap<>();
    static final Set<String> KFLAGGED = ConcurrentHashMap.newKeySet();
    /** Hourly totals and bot counts, newest last, at most DROP_HOURS + 1 (the baseline is the first). */
    static final List<long[]> HIST = new ArrayList<>();

    static volatile long baseline = -1, baselineAt, prevTotal = -1, lastCheckAt, lastLogAt;
    static volatile int baselineBots, prevBots, warmBots = -1; // warmBots: the last count seen before the baseline (batch 22 fix)
    static volatile int dropAlarms, paidAlarms, kindAlarms, checks; // trials read these
    static volatile int holdN; static volatile long holdAt; // P38 L7 (a): samples the count has held for, time of the last one
    /** P38 L7 (b): {time, total, bots} samples within the last RISE_MS (status lines), oldest first; RISE lines logged; last RISE time. */
    static final List<long[]> RISE_HIST = new ArrayList<>();
    static volatile int rises; static volatile long riseAt = -1;
    static volatile String lastAlarm;
    /** Trials only (TestB22CoinWatch): gp added to every total, so taking it away later looks like a drop. */
    static volatile long testExtra;
    static long started;

    static void log(String s) { System.out.println("[CoinWatch] " + s); }

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (!ON || settings == null || !settings.getEnable_bots()) { if (!ON) log("off (-Dcoinwatch=false)"); return; }
        started = System.currentTimeMillis();
        GameWorld.getPulser().submit(new Pulse(100) { // ~60 s
            @Override public boolean pulse() {
                long t0 = System.nanoTime();
                try { tick(System.currentTimeMillis()); } catch (Throwable t) { log("" + t); Watchdog.fexc("CoinWatch.tick", null, t); }
                Watchdog.fpulse("CoinWatch.tick", null, System.nanoTime() - t0);
                return false;
            }
        });
        log("on: drop alarm over " + DROP_PCT + "% in " + DROP_HOURS + " falling hours or " + SINGLE_PCT + "% in one, paid alarm over " + MAX_PAID + " per bot and " + MAX_KIND + " per kind an hour (period " + PERIOD_MS / 60_000 + " min, warm-up " + WARM_MS / 60_000 + " min)");
        if (RISE_ON) log("rise rule on (log only): RISE line over " + RISE_GP + " gp in " + RISE_MS / 60_000 + " min");
    }

    /** The batch 22 hook: one call per paid action (Ledger.Wallet.payTeleport). Cheap, any thread, never throws. */
    static void paid(Player p) { paid(p, "teleport"); }
    /** Batch 22-1: with the kind of paid action (Wallet.payTeleport's why, "boat"), for the fleet-wide limit. */
    static void paid(Player p, String kind) {
        if (!ON || p == null) return;
        try { if (p.isArtificial()) { count(p.getUsername()); countKind(kind); } } catch (Throwable ignored) { }
    }
    static void countKind(String kind) {
        if (!ON) return;
        KINDS.computeIfAbsent(kind == null ? "teleport" : kind, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Batch 22-1 drop rule (pure, so a trial can feed it hourly totals). tot/bots: the hourly totals and bot counts, oldest first,
     * the last one now. A drop is (a) the newest hour down more than singlePct, or (b) the last DROP_HOURS hours all falling and
     * down more than dropPct in all. Either way only while the bot count held within 10% across the hours looked at (logins and
     * logouts are not a drain). Returns the reason text, or null.
     */
    static String dropReason(long[] tot, long[] bots, int dropPct, int hours, int singlePct) {
        int n = tot.length;
        if (n < 2 || bots.length != n) return null;
        long cur = tot[n - 1], prev = tot[n - 2];
        if (prev > 0 && stable(bots[n - 2], bots[n - 1]) && (prev - cur) * 100L > (long) singlePct * prev)
            return "bot coins fell " + pct(prev, cur) + " in an hour (" + prev + " -> " + cur + " gp; over " + singlePct + "%)";
        if (n <= hours) return null;
        long first = tot[n - 1 - hours];
        if (first <= 0) return null;
        for (int i = n - hours; i < n; i++) if (tot[i] >= tot[i - 1] || !stable(bots[i - 1], bots[i])) return null;
        if ((first - cur) * 100L <= (long) dropPct * first) return null;
        return "bot coins fell " + pct(first, cur) + " over " + hours + " hours, every hour down (" + first + " -> " + cur + " gp; over " + dropPct + "%)";
    }
    static boolean stable(long a, long b) { return Math.abs(b - a) * 10L <= a; } // the same rule as Failures.Econ.worldSave
    static void count(String name) {
        if (!ON || name == null) return;
        PAID.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new AtomicInteger()).incrementAndGet();
    }

    /** {total gp, bots}: online adventurers' wallets + offline profiles + coins owed to logged-out bots. Game thread. */
    static long[] total() {
        long sum = 0; int n = 0; Set<String> seen = new HashSet<>();
        for (Map.Entry<String, SmartAdventurers.Brain> e : SmartAdventurers.BRAINS.entrySet()) {
            SmartAdventurers.Brain br = e.getValue();
            if (br == null || br.bot == null || !br.bot.isActive()) continue;
            sum += Ledger.Wallet.coins(br.bot); n++; seen.add(e.getKey().toLowerCase(Locale.ROOT));
        }
        for (SmartAdventurers.Profile pr : SmartAdventurers.OFFLINE) {
            if (pr == null || pr.name != null && seen.contains(pr.name.toLowerCase(Locale.ROOT))) continue;
            sum += pr.coins; n++;
        }
        for (Long v : Ledger.Wallet.OFFLINE.values()) if (v != null) sum += v;
        return new long[]{sum + testExtra, n};
    }

    /** The configured fleet the baseline waits for (-Dcoinwatch.fleet, default smartbots.count). Game thread. */
    static int fleet() { return Integer.getInteger("coinwatch.fleet", SmartAdventurers.COUNT); }

    /**
     * P38 L7 (a), one bot-count reading per call (every minute until the baseline). Returns why the baseline may be taken now,
     * or null: "fleet" once bots >= FLEET_PCT% of fleet (fleet <= 0: this path is off), "hold" once the count has stayed within
     * 10% of the first reading of the run for HOLD_SAMPLES samples HOLD_MS apart. A reading outside 10% starts a new run.
     */
    static String baselineReady(long now, int bots, int fleet) {
        if (bots <= 0) { warmBots = bots; holdN = 0; return null; }
        if (fleet > 0 && (long) bots * 100L >= (long) FLEET_PCT * fleet) return "fleet " + bots + " of " + fleet + " (" + FLEET_PCT + "%)";
        if (warmBots <= 0 || !stable(warmBots, bots)) { warmBots = bots; holdN = 1; holdAt = now; return null; }
        if (now - holdAt >= HOLD_MS) { holdN++; holdAt = now; }
        return holdN >= HOLD_SAMPLES ? "count held " + holdN + " samples " + HOLD_MS / 60_000 + " min apart (" + warmBots + " -> " + bots + ", fleet " + fleet + ")" : null;
    }

    /**
     * P38 L7 (b), one status-line sample (total gp, bot count). With on=false it records nothing and returns null. Otherwise
     * the reason text when the total rose more than RISE_GP above any sample of the last RISE_MS that had a bot count within
     * 10% of now; at most one per RISE_MS (the caller logs it as one "[CoinWatch] RISE ..." line). Log-only: no alarm, no trip.
     */
    static String rise(long now, long cur, int bots, boolean on) {
        if (!on) return null;
        while (!RISE_HIST.isEmpty() && now - RISE_HIST.get(0)[0] > RISE_MS) RISE_HIST.remove(0);
        long low = -1, lowAt = 0;
        for (long[] s : RISE_HIST) if (stable(s[2], bots) && (low < 0 || s[1] < low)) { low = s[1]; lowAt = s[0]; }
        RISE_HIST.add(new long[]{now, cur, bots});
        if (low < 0 || cur - low <= RISE_GP || riseAt >= 0 && now - riseAt < RISE_MS) return null;
        riseAt = now; rises++;
        return "bot coins rose " + (cur - low) + " gp in " + (now - lowAt) / 60_000 + " min (" + low + " -> " + cur + " gp, " + bots
                + " bots; over " + RISE_GP + " = clerk fund cap " + Ledger.CLERK_DAILY_CAP + " + 25%), log only";
    }

    /** P38 L7 (b): rise() plus its one "[CoinWatch] RISE ..." line (new line; existing formats untouched). */
    static void riseSample(long now, long cur, int bots, boolean on) {
        String why = rise(now, cur, bots, on);
        if (why != null) log("RISE " + why);
    }

    static String pct(long from, long to) { return from <= 0 ? "n/a" : String.format(Locale.ROOT, "%+.1f%%", (to - from) * 100.0 / from); }

    static void tick(long now) {
        if (baseline < 0) {
            if (now - started < WARM_MS) return;
            long[] t = total();
            if (t[1] == 0 && t[0] == 0) return; // no bots loaded yet: try again next minute
            String ready = baselineReady(now, (int) t[1], fleet()); // P38 L7 (a): fleet reached or the count held 3 samples (was: held for one minute)
            if (ready == null) return;
            baseline = t[0]; baselineBots = (int) t[1]; baselineAt = now; prevTotal = t[0]; prevBots = (int) t[1]; lastCheckAt = now; lastLogAt = now;
            PAID.clear(); FLAGGED.clear(); KINDS.clear(); KFLAGGED.clear(); HIST.clear(); HIST.add(new long[]{t[0], t[1]});
            log("baseline " + t[0] + " gp, " + t[1] + " bots");
            log("start rule: " + ready); // new line; not "baseline ..." (soak.sh and TestB22CoinWatch grep '^[CoinWatch] baseline ')
            if (RISE_ON) { RISE_HIST.clear(); rise(now, t[0], (int) t[1], true); }
            return;
        }
        List<String> why = new ArrayList<>();
        // paid actions: every minute, so a runaway bot is flagged within the hour
        List<String> over = new ArrayList<>(); int worst = 0; String worstBot = null;
        for (Map.Entry<String, AtomicInteger> e : PAID.entrySet()) {
            int c = e.getValue().get();
            if (c > worst) { worst = c; worstBot = e.getKey(); }
            if (c > MAX_PAID && FLAGGED.add(e.getKey())) over.add(e.getKey() + " " + c);
        }
        if (!over.isEmpty()) {
            paidAlarms++;
            why.add("paid actions over " + MAX_PAID + " this hour: " + String.join(", ", over.subList(0, Math.min(5, over.size()))) + (over.size() > 5 ? " (+" + (over.size() - 5) + " more)" : ""));
            Watchdog.fev("economy", null, "coinwatch", "coin-drain", over.size() + " bots over " + MAX_PAID + " paid actions an hour", null,
                    Watchdog.ctx("kind", "paid", "limit", MAX_PAID, "bots", new ArrayList<>(over.subList(0, Math.min(10, over.size()))), "worst", worst, "worstBot", worstBot, "bug", 296, "key", "296-paid"));
            Watchdog.clean(BUG_PAID, false);
        }
        // one kind of paid action across the whole fleet (a crossing loop spread over many bots): every minute too
        List<String> kover = new ArrayList<>(); int kworst = 0; String kworstName = null;
        for (Map.Entry<String, AtomicInteger> e : KINDS.entrySet()) {
            int c = e.getValue().get();
            if (c > kworst) { kworst = c; kworstName = e.getKey(); }
            if (c > MAX_KIND && KFLAGGED.add(e.getKey())) kover.add(e.getKey() + " " + c);
        }
        if (!kover.isEmpty()) {
            kindAlarms++;
            why.add("one paid action over " + MAX_KIND + " an hour fleet-wide: " + String.join(", ", kover));
            Watchdog.fev("economy", null, "coinwatch", "coin-drain", String.join(", ", kover) + " paid actions an hour fleet-wide", null,
                    Watchdog.ctx("kind", "fleet", "limit", MAX_KIND, "actions", new ArrayList<>(kover), "bug", 296, "key", "296-kind"));
            Watchdog.clean(BUG_KIND, false);
        }
        boolean hourly = now - lastCheckAt >= PERIOD_MS, status = hourly || now - lastLogAt >= LOG_MS;
        if (status) {
            long[] t = total(); long cur = t[0]; int bots = (int) t[1];
            StringBuilder line = new StringBuilder("total ").append(cur).append(" gp, ").append(bots).append(" bots, baseline ").append(baseline).append(" gp (").append(pct(baseline, cur)).append(")");
            if (hourly) {
                checks++;
                long prev = prevTotal; int pb = prevBots;
                boolean stable = stable(pb, bots); // a bot count change of over 10% is not a drain
                HIST.add(new long[]{cur, bots}); while (HIST.size() > DROP_HOURS + 1) HIST.remove(0);
                long[] ht = new long[HIST.size()], hb = new long[HIST.size()];
                for (int i = 0; i < ht.length; i++) { ht[i] = HIST.get(i)[0]; hb[i] = HIST.get(i)[1]; }
                String dropWhy = dropReason(ht, hb, DROP_PCT, DROP_HOURS, SINGLE_PCT);
                boolean drop = dropWhy != null;
                int paidN = 0; for (AtomicInteger a : PAID.values()) paidN += a.get();
                line.append("; hour: was ").append(prev).append(" gp (").append(pct(prev, cur)).append(")").append(stable ? "" : " (bot count changed " + pb + " -> " + bots + ")")
                        .append(", paid actions ").append(paidN).append(" by ").append(PAID.size()).append(" bots").append(worstBot != null ? ", top " + worstBot + " " + worst : "").append(kworstName != null ? ", top kind " + kworstName + " " + kworst : "");
                if (drop) {
                    dropAlarms++;
                    why.add(dropWhy + ", " + bots + " bots");
                    Watchdog.fev("economy", null, "coinwatch", "coin-drain", "bot coins " + pct(prev, cur) + " in an hour", null,
                            Watchdog.ctx("kind", "total", "total", cur, "prev", prev, "baseline", baseline, "bots", bots, "botsBefore", pb, "limitPct", DROP_PCT, "singlePct", SINGLE_PCT, "hours", DROP_HOURS, "why", dropWhy, "paidActions", paidN, "worst", worst, "worstBot", worstBot, "bug", 296, "key", "296-total"));
                }
                Watchdog.clean(BUG_TOTAL, !drop);
                if (over.isEmpty() && worst <= MAX_PAID) Watchdog.clean(BUG_PAID, true);
                if (kover.isEmpty() && kworst <= MAX_KIND) Watchdog.clean(BUG_KIND, true);
                prevTotal = cur; prevBots = bots; lastCheckAt = now;
                PAID.clear(); FLAGGED.clear(); KINDS.clear(); KFLAGGED.clear();
            }
            lastLogAt = now;
            log(line.toString());
            riseSample(now, cur, bots, RISE_ON); // P38 L7 (b): off by default; never an ALARM, never Failures/Bargain
        }
        if (!why.isEmpty()) {
            lastAlarm = String.join("; ", why); log("ALARM coin-drain (bug 296): " + lastAlarm);
            // batch 24b item 1: a coin alarm trips the Bargain engine off (its exit rule). Only while the engine is on: with the
            // flag off nothing is written, so a later flag-on start is not blocked by an old alarm. The paid-actions alarm (teleport
            // fares, not haggling) trips it too unless -Dbargain.tripOnPaid=false; the drop alarm always does.
            boolean dropHit = why.stream().anyMatch(w -> w.startsWith("bot coins fell"));
            if (dropHit || TRIP_ON_PAID) try { Bargain.coinAlarm("CoinWatch " + lastAlarm); } catch (Throwable t) { log("bargain trip: " + t); }
        }
    }
}
