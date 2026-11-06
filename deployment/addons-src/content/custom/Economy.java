package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.ServerConstants;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

/**
 * Economy snapshot for the website (plan E30): written to data/eco/economy.json every ten minutes and picked up by
 * the price push. Ledger totals, money supply, population and moods, the bounty board, what every bot is doing,
 * wealth distribution and the last deaths. Nothing secret: bot names, coins and activities only.
 */
public final class Economy implements StartupListener {
    static final Timer TIMER = new Timer("Economy", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() { try { write(); } catch (Throwable t) { System.out.println("[Economy] snapshot failed: " + t); } try { Ledger.save(); } catch (Throwable t) { System.out.println("[Economy] ledger save: " + t); } }
        }, FAST ? 60_000 : 300_000, FAST ? 60_000 : 600_000);
        TIMER.schedule(new TimerTask() { // audit 10-23 batch14 item5: private ops metrics (data/metrics.json, not the public economy.json) and the "doubled" alerts
            int n; long lastRun;
            @Override public void run() { try { long t = System.currentTimeMillis(); if (lastRun > 0 && t - lastRun > 62_000) System.out.println("[Stall] 60 s timer ran " + (t - lastRun - 60_000) + " ms late (whole JVM or host paused; compare with the long-pulse lines)"); lastRun = t; } catch (Throwable ignored) { } // b23: tells a JVM/host-wide pause from a game-thread-only one
                try { RATES.sample(System.currentTimeMillis() / 60_000L); if (++n % (FAST ? 1 : 10) == 0) writeMetrics(); } catch (Throwable t) { System.out.println("[Metrics] " + t); }
                try { long m = System.currentTimeMillis() / 60_000L; deathSpikes(ROLE_DEATHS, m); Mem.tick(m); } catch (Throwable ignored) { } } // batch 19c §6 death-spike, §8.2 memory-growth / gc-pressure (logging only, numbers only)
        }, 60_000, 60_000);
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(100) { // real players: coins from Exchange lots that sold while they were away (second consultant check B)
            @Override public boolean pulse() {
                try {
                    for (core.game.node.entity.player.Player p : core.game.world.repository.Repository.getPlayers()) {
                        if (p == null || p.isArtificial() || !p.isActive()) continue;
                        long due = Ledger.Wallet.collect(p);
                        if (due > 0) p.sendMessage("<col=0b5e1f>Your Exchange lots sold for " + String.format("%,d", due) + " gp while you were away; it's in your bank.");
                        try { SmartAdventurers.recoverGraveFor(p); } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) { }
                return false;
            }
        });
        System.out.println("[Economy] snapshot for the website every 10 min -> data/eco/economy.json");
    }

    static File file() { return new File(ServerConstants.DATA_PATH, "eco/economy.json"); }

    // ------------------------------------------------------------ audit 10-23 batch14 item5: private ops metrics
    /** Counted at the log lines: SmartAdventurers "DEATH", "recovered N item stacks", QuestBook started/finished. Per boot. */
    static final java.util.concurrent.atomic.AtomicLong DEATHS = new java.util.concurrent.atomic.AtomicLong(), GRAVE_RECOVERIES = new java.util.concurrent.atomic.AtomicLong(),
            QUEST_STARTS = new java.util.concurrent.atomic.AtomicLong(), QUEST_DONE = new java.util.concurrent.atomic.AtomicLong();
    static File metricsFile() { return new File(ServerConstants.DATA_PATH, "metrics.json"); }

    /**
     * Per-minute buckets fed from cumulative counters (sampled once a minute), two hours deep. "[ALERT] <metric> doubled" when
     * the trailing hour is at least twice the hour before and at least the floor, at most once per metric per hour, and only
     * once both hours were watched in full. Deaths use the counter above (SmartAdventurers.DEATHS keeps only the last 30), stuck
     * rescues Watchdog.rescued. Long pulses and exceptions are not counted here: the engine prints them to stdout only.
     */
    static final class Rates {
        final String[] names; final long[] floors; final java.util.function.LongSupplier[] cum;
        final long[][] buckets; final long[] lastCum, lastAlert; long first = -1, last = -1;
        boolean quiet; // batch 19c: no [ALERT] print (the per-role death series report through Failures instead)
        Rates(String[] names, long[] floors, java.util.function.LongSupplier[] cum) {
            this.names = names; this.floors = floors; this.cum = cum;
            buckets = new long[names.length][120]; lastCum = new long[names.length]; lastAlert = new long[names.length]; java.util.Arrays.fill(lastAlert, Long.MIN_VALUE / 2);
        }
        synchronized java.util.List<String> sample(long minute) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (first < 0) { first = last = minute; for (int i = 0; i < names.length; i++) lastCum[i] = cum[i].getAsLong(); return out; }
            if (minute <= last) return out;
            for (long m = Math.max(last + 1, minute - 119); m <= minute; m++) for (long[] b : buckets) b[(int) (m % 120)] = 0;
            last = minute;
            for (int i = 0; i < names.length; i++) { long c = cum[i].getAsLong(); buckets[i][(int) (minute % 120)] += Math.max(0, c - lastCum[i]); lastCum[i] = c; }
            if (minute - first < 120) return out; // the hour before must have been watched in full
            for (int i = 0; i < names.length; i++) {
                long cur = hour(i, minute), prev = hour(i, minute - 60);
                if (cur >= 2 * prev && cur >= floors[i] && minute - lastAlert[i] >= 60) {
                    lastAlert[i] = minute;
                    String line = "[ALERT] " + names[i] + " doubled: " + prev + "/h -> " + cur + "/h";
                    if (!quiet) System.out.println(line + " (audit 10-23 batch14)"); out.add(line);
                }
            }
            return out;
        }
        /** Events in the 60 minutes ending at this minute (0 for minutes not watched). */
        synchronized long hour(int i, long end) { long s = 0; for (long m = end - 59; m <= end; m++) if (m > first && m <= last && last - m < 120) s += buckets[i][(int) (m % 120)]; return s; }
    }
    static final Rates RATES = new Rates(new String[]{"deaths", "stuck rescues"}, new long[]{5, 5},
            new java.util.function.LongSupplier[]{DEATHS::get, () -> Watchdog.rescued});

    // ------------------------------------------------------------ batch 19c §6: per-role death series (Failures.death counts them) -> death-spike records
    /** A fresh per-role Rates over Failures.Deaths.N (deaths-fresh, -veteran, -high, -trader, -replayer; floor 5), quiet (no [ALERT] print). Tests build their own. */
    static Rates roleRates() {
        String[] n = new String[Failures.Deaths.ROLES.length]; long[] f = new long[n.length]; java.util.function.LongSupplier[] c = new java.util.function.LongSupplier[n.length];
        for (int i = 0; i < n.length; i++) { final int k = i; n[i] = "deaths-" + Failures.Deaths.ROLES[i]; f[i] = 5; c[i] = () -> Failures.Deaths.N[k].get(); }
        Rates r = new Rates(n, f, c); r.quiet = true; return r;
    }
    static final Rates ROLE_DEATHS = roleRates();
    /** batch 19c §6: one minute of the per-role series; each "doubled" becomes one death-spike record (the Rates rule: hour >= 2x the one before, >= 5, once an hour). */
    static int deathSpikes(Rates r, long minute) {
        int n = 0;
        for (String line : r.sample(minute)) {
            try { String s = line.substring(8, line.indexOf(" doubled")); int a = line.indexOf(": ") + 2, b = line.indexOf("/h ->"); long prev = Long.parseLong(line.substring(a, b)); long cur = Long.parseLong(line.substring(line.indexOf("-> ") + 3, line.lastIndexOf("/h"))); Failures.Deaths.spike(s, prev, cur); n++; } catch (Throwable ignored) { }
        }
        return n;
    }

    // ------------------------------------------------------------ batch 19c §8.2: memory-growth / gc-pressure (this timer thread; MX beans only)
    static final class Mem {
        static final long[] AFTER = new long[70]; static final long[] GC = new long[70]; static long first = -1, last = -1; static final long MB = 1024L * 1024L; // per-minute after-GC old-gen use and cumulative GC ms
        static String pool = "old-gen";
        static { java.util.Arrays.fill(AFTER, -1); java.util.Arrays.fill(GC, -1); }
        static void tick(long minute) {
            if (!Failures.ON) return;
            long used = -1, max = -1, gc = 0;
            for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                String n = p.getName().toLowerCase(java.util.Locale.ROOT);
                if (p.getType() != java.lang.management.MemoryType.HEAP || !(n.contains("old") || n.contains("tenured"))) continue;
                java.lang.management.MemoryUsage u = p.getCollectionUsage(); if (u == null) continue;
                used = u.getUsed(); max = u.getMax() > 0 ? u.getMax() : Runtime.getRuntime().maxMemory(); pool = p.getName();
            }
            for (java.lang.management.GarbageCollectorMXBean g : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) if (g.getCollectionTime() > 0) gc += g.getCollectionTime();
            if (used >= 0) sample(minute, used, max, gc);
        }
        /** One minute's numbers (tests feed it simulated minutes). Returns the code recorded, or null. */
        static synchronized String sample(long minute, long afterGc, long max, long gcMsCum) {
            if (first < 0) first = minute;
            if (last >= 0 && minute <= last) return null;
            for (long m = last < 0 ? minute : last + 1; m < minute; m++) { AFTER[(int) (m % 70)] = -1; GC[(int) (m % 70)] = -1; } // unwatched minutes
            last = minute; AFTER[(int) (minute % 70)] = afterGc; GC[(int) (minute % 70)] = gcMsCum;
            String code = null; java.util.LinkedHashMap<String, Object> c = null;
            if (minute - first >= 69) {
                long now10 = min(minute - 9, minute), old10 = min(minute - 69, minute - 60);
                long gcNow = GC[(int) (minute % 70)], gc10 = GC[(int) ((minute - 10) % 70)];
                double gcPct = gcNow >= 0 && gc10 >= 0 ? (gcNow - gc10) * 100.0 / 600_000.0 : 0;
                long rise = now10 >= 0 && old10 >= 0 ? now10 - old10 : 0;
                boolean grow = now10 >= 0 && old10 >= 0 && rise > 150 * MB && now10 > max * 3 / 4, press = gcPct > 10;
                if (grow || press) {
                    code = grow ? "memory-growth" : "gc-pressure";
                    c = Failures.ctx("afterGcMb", now10 / MB, "maxMb", max / MB, "rise60mMb", rise / MB, "gcPct10m", Math.round(gcPct * 10) / 10.0, "bots", SmartAdventurers.BRAINS.size(), "source", pool, "key", Failures.slug(pool));
                }
                Failures.periodic("b-memory-growth-" + Failures.slug(pool), grow); Failures.periodic("b-gc-pressure-" + Failures.slug(pool), press);
            }
            if (code == null) return null;
            if (!Failures.gate("mem|" + code, 3_600_000L)) { Failures.counted(code); return null; } // one per hour
            Failures.event("health", null, "memory", code, code + ": " + c.get("afterGcMb") + " MB after GC, +" + c.get("rise60mMb") + " MB in 60 min, GC " + c.get("gcPct10m") + "%", null, c);
            return code;
        }
        static long min(long from, long to) { long v = Long.MAX_VALUE; for (long m = from; m <= to; m++) { long x = AFTER[(int) (((m % 70) + 70) % 70)]; if (x >= 0) v = Math.min(v, x); } return v == Long.MAX_VALUE ? -1 : v; }
        static synchronized void reset() { first = -1; last = -1; java.util.Arrays.fill(AFTER, -1); java.util.Arrays.fill(GC, -1); } // tests
    }

    @SuppressWarnings("unchecked")
    static synchronized void writeMetrics() {
        JSONObject m = new JSONObject();
        m.put("updated", java.time.Instant.now().toString());
        long minute = System.currentTimeMillis() / 60_000L;
        m.put("deaths", DEATHS.get()); m.put("deaths_last_hour", RATES.hour(0, minute));
        m.put("grave_recoveries", GRAVE_RECOVERIES.get());
        m.put("quest_starts", QUEST_STARTS.get()); m.put("quest_completions", QUEST_DONE.get());
        JSONObject qc = new JSONObject(); String qd = QuestBook.today(); qc.put("day", qd); qc.put("coins", qd.equals(QuestBook.coinDay) ? QuestBook.coinsToday : 0); qc.put("cap", QuestBook.COIN_CAP); m.put("quest_coins_today", qc); // the quest cap rolls over lazily: an old day is 0 today
        m.put("stuck_rescues", Watchdog.rescued); m.put("stuck_rescues_last_hour", RATES.hour(1, minute));
        JSONObject pl = new JSONObject(); // NavWalker shadow outcomes since boot: [planner had a route, had none] x [arrived, not arrived, teleported, redirected]
        int[][] a = NavWalker.ALL; JSONArray rows = new JSONArray();
        for (int[] r : a) { JSONArray row = new JSONArray(); for (int v : r) row.add(v); rows.add(row); }
        int agree = a[0][0] + a[1][1], judged = a[0][0] + a[0][1] + a[1][0] + a[1][1];
        pl.put("shadow", NavWalker.SHADOW); pl.put("outcomes", rows); pl.put("agreement", judged == 0 ? null : Math.round(1000.0 * agree / judged) / 10.0); pl.put("judged", judged);
        m.put("planner", pl);
        m.put("thread_violations", Ledger.threadViolations);
        JSONObject f = new JSONObject(); // coin flows as the ledger names them; it does not tag mints and sinks, so no minted/destroyed total
        f.put("clerk_paid_today", Ledger.clerkPaidToday); f.put("clerk_day", Ledger.clerkDay);
        f.put("alched", Ledger.ALCHED); f.put("alched_today", Ledger.alchedToday);
        f.put("quest_paid", Ledger.QUEST_PAID); f.put("quest_items", Ledger.QUEST_ITEMS);
        f.put("given_minus_credits", Ledger.GIVEN - Ledger.GIVEN_CREDITS); f.put("written_off", Ledger.WRITTEN_OFF);
        f.put("bounty_paid_today", Bounty.paidToday); f.put("bot_buyer_fills", PrivateWorldAddons.BUYER_FILLS);
        m.put("flows", f);
        m.put("not_counted", "long pulses and exceptions (engine stdout only; counted from the journal)");
        try { Ledger.writeAtomic(metricsFile(), m.toJSONString()); } catch (Throwable t) { System.out.println("[Metrics] could not write: " + t); }
    }

    @SuppressWarnings("unchecked")
    static synchronized void write() {
        JSONObject root = new JSONObject();
        root.put("updated", java.time.Instant.now().toString());

        // Ledger and money supply.
        JSONObject ledger = new JSONObject();
        int lots = 0, units = 0;
        synchronized (Ledger.class) { for (java.util.Deque<Ledger.Entry> q : Ledger.QUEUES.values()) for (Ledger.Entry e : q) { lots++; units += e.qty; } }
        long earned = 0, spent = 0;
        synchronized (Ledger.class) { for (long v : Ledger.EARNED.values()) earned += v; for (long v : Ledger.SPENT.values()) spent += v; } // under the ledger lock (audit #29)
        ledger.put("lots", lots); ledger.put("units", units); ledger.put("earned", earned); ledger.put("spent", spent);
        ledger.put("clerk_paid_today", Ledger.clerkPaidToday); ledger.put("paid", Ledger.PAID); ledger.put("given", Ledger.GIVEN); ledger.put("given_credits", Ledger.GIVEN_CREDITS); /* audit E-12: the part of "given" that was sale credits */ ledger.put("restored", Ledger.Wallet.RESTORED); ledger.put("callouts", Deals.calloutsMade); ledger.put("alched", Ledger.ALCHED); ledger.put("alched_today", Ledger.alchedToday); ledger.put("written_off", Ledger.WRITTEN_OFF); ledger.put("credited", Ledger.CREDITED);
        root.put("ledger", ledger);

        JSONArray bots = new JSONArray();
        long coinsOnline = 0, offlineCredits = 0;
        Map<String, Integer> moods = new TreeMap<>();
        int working = 0, high = 0;
        int[] wealth = new int[4];
        for (Player b : SmartAdventurers.BOTS) {
            if (b == null || !b.isActive()) continue;
            String key = b.getUsername().toLowerCase(Locale.ROOT);
            long coins = Ledger.Wallet.coins(b);
            coinsOnline += coins;
            wealth[coins < 1_000 ? 0 : coins < 10_000 ? 1 : coins < 100_000 ? 2 : 3]++;
            SmartAdventurers.Brain brain = SmartAdventurers.BRAINS.get(key);
            String mood = brain != null ? brain.mood().name().toLowerCase(Locale.ROOT) : "fine";
            moods.merge(mood, 1, Integer::sum);
            boolean busy = AfkTasks.RUNNING.containsKey(key);
            if (busy) working++;
            boolean hi = SmartAdventurers.HIGH_TIER.getOrDefault(key, false);
            if (hi) high++;
            JSONObject j = new JSONObject();
            j.put("name", b.getUsername()); j.put("coins", coins); j.put("combat", b.getProperties().getCurrentCombatLevel());
            int home = SmartAdventurers.HOME.getOrDefault(key, -1);
            j.put("home", home >= 0 ? BotLife.Town.values()[home % BotLife.Town.values().length].name : null);
            j.put("trade", SmartAdventurers.skillName(SmartAdventurers.FAV.getOrDefault(key, -2)));
            j.put("mood", mood); j.put("high", hi);
            String act = BotChat.activityOf(b);
            j.put("doing", act != null ? act : busy ? "working" : "at a loose end");
            int goal = SmartAdventurers.GOAL.getOrDefault(key, 0);
            if (goal > 0) j.put("saving_for", MarketBrain.name(goal));
            if (brain != null && brain.crew != null && brain.crew.leader != null && brain.crew.leader.bot != null) j.put("crew", brain.crew.leader.bot.getUsername());
            bots.add(j);
        }
        synchronized (Ledger.Wallet.OFFLINE) { for (long v : Ledger.Wallet.OFFLINE.values()) offlineCredits += v; }
        long offlineCoins = 0;
        for (SmartAdventurers.Profile p : SmartAdventurers.OFFLINE) offlineCoins += p.coins;
        JSONObject money = new JSONObject();
        money.put("bots_online", coinsOnline); money.put("bots_on_break", offlineCoins); money.put("owed_to_offline", offlineCredits);
        money.put("artisans", Bounty.artisanFunds());
        root.put("money", money);

        JSONObject pop = new JSONObject();
        pop.put("adventurers", bots.size()); pop.put("on_break", SmartAdventurers.OFFLINE.size()); pop.put("working", working); pop.put("high_tier", high);
        pop.put("artisans", Artisans.ARTISAN_BOTS.size()); pop.put("crews", SmartAdventurers.Crew.CREWS.size());
        pop.put("sawmill_fees", SmartAdventurers.Brain.FEES_PAID); pop.put("planks_built", SmartAdventurers.Brain.PLANKS_BUILT); pop.put("planks_sold", SmartAdventurers.Brain.PLANKS_SOLD);
        // Fresh bots' money: how many are skint, and how long a newcomer waits for its first payment this boot.
        int fresh = 0, freshPoor = 0, waiting = 0; long waitSum = 0; int paidN = 0;
        for (Player b : SmartAdventurers.BOTS) {
            if (b == null || !b.isActive()) continue;
            String k = b.getUsername().toLowerCase(Locale.ROOT);
            if (SmartAdventurers.VETERAN.getOrDefault(k, false) || SmartAdventurers.HIGH_TIER.getOrDefault(k, false)) continue;
            fresh++;
            if (Ledger.Wallet.coins(b) < 100) freshPoor++;
            Long start = SmartAdventurers.ENLISTED.get(k), paid = SmartAdventurers.FIRST_PAID.get(k);
            if (start == null) continue;
            if (paid == null) waiting++; else { waitSum += Math.max(0, paid - start); paidN++; }
        }
        pop.put("fresh", fresh); pop.put("fresh_under_100", freshPoor); pop.put("fresh_unpaid", waiting);
        pop.put("fresh_first_sale_hours", paidN == 0 ? null : Math.round(waitSum / (double) paidN / 360_000.0) / 10.0);
        pop.put("hub_trades", Deals.tradesToday); pop.put("meets", Deals.meetsToday); pop.put("splits", Deals.splitsToday); pop.put("player_buys_at_bank", Deals.humanBuys);
        root.put("population", pop);
        JSONObject md = new JSONObject(); md.putAll(moods); root.put("moods", md);
        JSONObject w = new JSONObject(); w.put("under_1k", wealth[0]); w.put("1k_10k", wealth[1]); w.put("10k_100k", wealth[2]); w.put("over_100k", wealth[3]); root.put("wealth", w);
        root.put("bots", bots);

        JSONArray wanted = new JSONArray();
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (MarketBrain.Entry e : MarketBrain.topBounties(30)) {
            if (!seenNames.add(MarketBrain.name(e.id)) || wanted.size() >= 10) continue; // one row per name (audit)
            JSONArray row = new JSONArray(); row.add(MarketBrain.name(e.id)); row.add(MarketBrain.stockOf(e.id)); row.add(SmartAdventurers.capable(e.id)); wanted.add(row); }
        root.put("wanted", wanted);
        JSONArray board = new JSONArray();
        for (Object[] r : Bounty.board()) { JSONArray row = new JSONArray(); row.add(r[1]); row.add(r[2]); row.add(r[3]); row.add(r[4]); board.add(row); }
        root.put("board", board);

        JSONArray deaths = new JSONArray();
        for (String[] d : SmartAdventurers.DEATHS) { JSONArray row = new JSONArray(); row.add(d[0]); row.add(d[1]); row.add(Long.parseLong(d[2])); deaths.add(row); }
        root.put("deaths", deaths);

        JSONArray crews = new JSONArray();
        for (SmartAdventurers.Crew c : SmartAdventurers.Crew.CREWS) {
            JSONObject j = new JSONObject();
            j.put("leader", c.leader != null && c.leader.bot != null ? c.leader.bot.getUsername() : "?");
            JSONArray m = new JSONArray(); for (SmartAdventurers.Brain b : c.members) if (b.bot != null) m.add(b.bot.getUsername()); j.put("members", m);
            j.put("job", c.job != null ? c.job.label() : null);
            crews.add(j);
        }
        root.put("crews", crews);

        try {
            file().getParentFile().mkdirs();
            Ledger.writeAtomic(file(), root.toJSONString());
        } catch (Throwable t) { System.out.println("[Economy] could not write snapshot: " + t); }
    }
}
