package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * boot1: the offline main() also drives the real econRoundStart / trimPaid / clerkRound / MarketBrain.fairTick (wired()).
 * econ1 unit (pure, no bots): Ledger.bandBudget / exportBudget / bandDailyCap / unitsToLine / isPoor and the flag wiring
 * (the design notes). Offline with main() (java -cp server.jar;out); in a world with -Dtest.econ1=true it runs the
 * same checks 20 ticks after startup, prints "[TestEcon1] RESULT PASS/FAIL pass=N fail=M" and then "[TestEcon1] DONE".
 */
public final class TestEcon1 implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestEcon1] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }
    static void eq(String what, long got, long want) { check(what + " = " + want + (got == want ? "" : " (got " + got + ")"), got == want); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.econ1")) return;
        GameWorld.getPulser().submit(new Pulse(20) {
            @Override public boolean pulse() {
                try { checks(); } catch (Throwable ex) { fail++; log("error " + ex); }
                log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
                log("DONE");
                return true;
            }
        });
    }

    public static void main(String[] a) throws Exception {
        try { checks(); } catch (Throwable ex) { fail++; log("error " + ex); }
        Thread th = new Thread(() -> { try { wired(); } catch (Throwable ex) { fail++; log("error wired " + ex); ex.printStackTrace(System.out); } }, "MarketBrain"); // the thread name Ledger.assertThread allows for the hourly
        th.start(); th.join();
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
        log("DONE");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---- boot1 (bug hunt C F3): the REAL econRoundStart / trimPaid / clerkRound / fairTick against a small fake ledger. Offline main() only: it rewrites Ledger state, so it never runs in a world.
    static void lot(int id, String who, int qty, long time) { synchronized (Ledger.class) { Ledger.QUEUES.computeIfAbsent(id, k -> new java.util.ArrayDeque<>()).addLast(new Ledger.Entry(who, qty, time)); } }
    static int qty(int id) { synchronized (Ledger.class) { java.util.Deque<Ledger.Entry> q = Ledger.QUEUES.get(id); int n = 0; if (q != null) for (Ledger.Entry e : q) n += e.qty; return n; } }
    static long owed(String who) { return Ledger.Wallet.OFFLINE.getOrDefault(who, 0L); }
    static void reset(String day, long paidToday) {
        synchronized (Ledger.class) { Ledger.QUEUES.clear(); Ledger.clerkDay = day; Ledger.clerkPaidToday = paidToday; }
        Ledger.Wallet.OFFLINE.clear(); Ledger.TEST_ONLINE = null;
        Ledger.CLERK_BOOT_GRACE = false; Ledger.CLERK_WEALTH_LIVE = false; Ledger.CLERK_BAND_BUDGET = false; Ledger.EXPORT_PAY = false;
    }
    static void wired() {
        final long H = 3_600_000L, MIN = 60_000L, real = System.currentTimeMillis(), CAP = Ledger.CLERK_DAILY_CAP;
        final String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        if (Ledger.BAND_TOP != 350_000 || Ledger.BAND_MID != 300_000 || Ledger.EXPORT_ROUND_MAX != 30_000 || Ledger.BAND_ROUND_MAX != 60_000 || Ledger.EXPORT_PCT != 50 || Ledger.BAND_FLEET != 102 || Ledger.CLERK_FIX2 || !Ledger.QUEUES.isEmpty()) { log("wired checks skipped: non-default band settings or a loaded ledger"); return; }
        final boolean g0 = Ledger.CLERK_BOOT_GRACE, w0 = Ledger.CLERK_WEALTH_LIVE, b0 = Ledger.CLERK_BAND_BUDGET, x0 = Ledger.EXPORT_PAY;
        final long boot0 = Ledger.bootAt, last0 = Ledger.fairLastRound, paid0 = Ledger.clerkPaidToday; final String day0 = Ledger.clerkDay;
        final int A = 990_001, B = 990_002, C = 990_003;
        for (int id = A; id <= A + 8; id++) MarketBrain.VALUE_CACHE.put(id, new long[]{100, real}); // guide 100 gp: export pays 50 a unit, the clerk 60
        Ledger.TEST_STOCK = (id, n) -> n;
        try {
            // 1. export pay inside the round budget and the day cap
            reset(today, 0); Ledger.EXPORT_PAY = true; Ledger.bootAt = real;
            lot(A, "ta", 1000, real - H);
            Ledger.econRoundStart(new long[]{250_000, 102});
            eq("wired: export budget at 250k, 102 bots", Ledger.econTrimBudget, 30_000);
            eq("wired: trimPaid 400 units", Ledger.trimPaid(A, 400), 20_000);
            eq("wired: the supplier is credited", owed("ta"), 20_000); eq("wired: lot left", qty(A), 600); eq("wired: clerkPaidToday", Ledger.clerkPaidToday, 20_000);
            eq("wired: second trim cut to the budget", Ledger.trimPaid(A, 400), 10_000); eq("wired: units leave all the same", qty(A), 200);
            eq("wired: budget spent, third trim pays 0", Ledger.trimPaid(A, 100), 0);
            synchronized (Ledger.class) { Ledger.clerkPaidToday = CAP - 120; }
            Ledger.econRoundStart(new long[]{250_000, 102});
            eq("wired: day cap leaves 120 gp = 2 units", Ledger.trimPaid(A, 50), 100); eq("wired: clerkPaidToday under the cap", Ledger.clerkPaidToday, CAP - 20);
            synchronized (Ledger.class) { Ledger.clerkPaidToday = 0; }
            Ledger.econRoundStart(new long[]{0, 0});
            eq("wired: empty fleet = budget 0 (the boot hourly today)", Ledger.econTrimBudget, 0);
            eq("wired: empty fleet trim pays 0", Ledger.trimPaid(A, 50), 0); eq("wired: and its units are gone unpaid", qty(A), 0);
            // 2. day rollover before the first trim of a day (bug hunt C F1)
            reset("20000101", CAP); Ledger.EXPORT_PAY = true; lot(A, "ta", 100, real - H);
            Ledger.econRoundStart(new long[]{250_000, 102});
            check("wired: econRoundStart rolls the day over", today.equals(Ledger.clerkDay) && Ledger.clerkPaidToday == 0);
            eq("wired: first trim of the day is paid", Ledger.trimPaid(A, 10), 500);
            reset("20000101", 77);
            Ledger.econRoundStart(new long[]{250_000, 102});
            check("wired: both flags off, econRoundStart is a no-op", "20000101".equals(Ledger.clerkDay) && Ledger.clerkPaidToday == 77 && Ledger.econTotal == -1);
            Ledger.clerkRound(real);
            check("wired: clerkRound rolls the day over itself", today.equals(Ledger.clerkDay) && Ledger.clerkPaidToday == 0);
            // 3. boot grace, the first round after it, the persisted day cap
            reset(today, 0); Ledger.CLERK_BOOT_GRACE = true; Ledger.bootAt = real; lot(B, "tb", 50, real - 25 * H);
            String r = Ledger.clerkRound(real + 2 * MIN);
            check("wired: round 2 min after boot is skipped (grace)", r != null && r.contains("boot grace")); eq("wired: grace buys nothing", qty(B) * 1000L + owed("tb"), 50_000);
            r = Ledger.clerkRound(real + 31 * MIN);
            check("wired: round 31 min after boot runs: " + r, r != null && r.contains("bought 1 stale lots")); eq("wired: 50 units at 60% of 100", owed("tb"), 3_000); eq("wired: booked against the day", Ledger.clerkPaidToday, 3_000);
            lot(B, "tb2", 50, real - 25 * H); synchronized (Ledger.class) { Ledger.clerkPaidToday = CAP; }
            check("wired: day cap reached (as loaded from ledger.json), nothing bought", Ledger.clerkRound(real + 31 * MIN) == null && qty(B) == 50 && owed("tb2") == 0);
            // 4. boot fairness: MarketBrain.fairTick drives the real trim + round on the slot clock
            reset(today, 0); Ledger.CLERK_BOOT_GRACE = true; Ledger.EXPORT_PAY = true;
            final long S = Ledger.slotAt(real, Ledger.FAIR_MINUTE) + H, boot = S - 5 * MIN; // a restart 5 min before a slot; the ledger was last saved 20 min before boot
            Ledger.bootAt = boot; Ledger.fairLastRound = boot - 20 * MIN;
            lot(A, "ta", 1000, real - H); lot(B, "tb", 50, real - 30 * H);
            final long[] clock = {0}; final int[] runs = {0}; final String[] res = {null};
            final Runnable hourly = () -> { runs[0]++; Ledger.econRoundStart(new long[]{250_000, 102}); Ledger.trimPaid(A, 10); res[0] = Ledger.clerkRound(clock[0]); };
            java.util.function.BiFunction<Long, Integer, String> tick = (t, in) -> { clock[0] = t; return MarketBrain.fairTick(t, in, 102, hourly); };
            check("fair: boot + 2 min, no hourly (its slot ran before the restart)", "served".equals(tick.apply(boot + 2 * MIN, 0)) && runs[0] == 0);
            check("fair: slot inside the grace is held", "grace".equals(tick.apply(S + MIN, 40)) && runs[0] == 0); eq("fair: nothing trimmed while held", qty(A) * 1000L + owed("ta"), 1_000_000);
            check("fair: grace over, 80 of 102 in: held", "fleet".equals(tick.apply(boot + 31 * MIN, 80)) && runs[0] == 0);
            check("fair: 95 of 102 in: the late hourly runs", tick.apply(boot + 32 * MIN, 95) == null && runs[0] == 1);
            eq("fair: the late trim is paid", owed("ta"), 500); eq("fair: 10 units trimmed", qty(A), 990);
            check("fair: its clerk round is not a grace skip: " + res[0], res[0] != null && !res[0].contains("boot grace")); eq("fair: the round paid", owed("tb"), 3_000);
            check("fair: 30 s later nothing runs again", "served".equals(tick.apply(boot + 32 * MIN + 30_000, 95)) && runs[0] == 1);
            check("fair: still served 59 min into the slot", "served".equals(tick.apply(S + 59 * MIN, 102)) && runs[0] == 1);
            check("fair: the next slot runs on time", tick.apply(S + H, 102) == null && runs[0] == 2);
            Ledger.fairLastRound = S + H + MIN; Ledger.bootAt = S + H + 5 * MIN; // a restart 5 min after a round
            check("fair: restart after a round, no hourly at boot + 2", "served".equals(tick.apply(S + H + 7 * MIN, 0)) && runs[0] == 2);
            check("fair: restart after a round, no second round after the grace", "served".equals(tick.apply(S + H + 36 * MIN, 102)) && runs[0] == 2);
            check("fair: restart after a round, next round at its slot", tick.apply(S + 2 * H, 102) == null && runs[0] == 3);
            Ledger.fairLastRound = S + 3 * H - 10 * MIN; Ledger.bootAt = S + 3 * H + 5 * MIN; // the slot passed while the server was down
            check("fair: slot missed in the downtime is owed, held for the grace", "grace".equals(tick.apply(S + 3 * H + 7 * MIN, 0)) && runs[0] == 3);
            check("fair: and runs when the grace ends", tick.apply(S + 3 * H + 35 * MIN, 102) == null && runs[0] == 4);
            check("fair: once only", "served".equals(tick.apply(S + 3 * H + 36 * MIN, 102)) && runs[0] == 4);
            // 5. band budget through clerkRound
            reset(today, 0); Ledger.CLERK_BAND_BUDGET = true; Ledger.EXPORT_PAY = true; Ledger.bootAt = real;
            lot(A, "ta", 1000, real - H); for (int i = 0; i < 5; i++) lot(C + i, "tc" + i, 100, real - 25 * H);
            Ledger.econRoundStart(new long[]{255_000, 102});
            eq("band: export pay first", Ledger.trimPaid(A, 50), 2_500);
            Ledger.clerkRound(real);
            long paid = 0; for (int i = 0; i < 5; i++) paid += owed("tc" + i);
            eq("band: budget 22,500 less 2,500 export, lots cut to fit", paid, 19_980); eq("band: day total", Ledger.clerkPaidToday, 22_480); eq("band: daily cap follows the fleet", Ledger.dailyCap(), 720_000);
            reset(today, 0); Ledger.CLERK_BAND_BUDGET = true; lot(C, "tc0", 100, real - 25 * H);
            Ledger.econRoundStart(new long[]{300_000, 102});
            check("band: fleet at the middle, nothing bought", Ledger.clerkRound(real) == null && qty(C) == 100 && owed("tc0") == 0);
            // 6. wealthLive re-test through clerkRound (tw1 on line with 400 gp and two 7 h lots; tw2 off line with a 25 h lot)
            for (boolean live : new boolean[]{true, false}) {
                reset(today, 0); Ledger.CLERK_WEALTH_LIVE = live; Ledger.TEST_ONLINE = java.util.Set.of("tw1"); Ledger.Wallet.OFFLINE.put("tw1", 400L);
                lot(A + 7, "tw1", 100, real - 7 * H); lot(A + 8, "tw1", 100, real - 7 * H); lot(B, "tw2", 50, real - 25 * H);
                Ledger.clerkRound(real);
                if (live) { eq("live: topped up to the 1,000 line, second lot not bought", owed("tw1"), 1_000); eq("live: 10 units sold", qty(A + 7) + qty(A + 8), 190); eq("live: off-line supplier left alone", qty(B) * 1000L + owed("tw2"), 50_000); }
                else { eq("live off: both lots bought on the stale 400 gp", owed("tw1"), 12_400); eq("live off: off-line supplier bought out", owed("tw2"), 3_000); }
            }
        } finally {
            reset(day0, paid0);
            Ledger.CLERK_BOOT_GRACE = g0; Ledger.CLERK_WEALTH_LIVE = w0; Ledger.CLERK_BAND_BUDGET = b0; Ledger.EXPORT_PAY = x0; Ledger.bootAt = boot0; Ledger.fairLastRound = last0; Ledger.TEST_STOCK = null;
            for (int id = A; id <= A + 8; id++) MarketBrain.VALUE_CACHE.remove(id);
            Ledger.econRoundStart(new long[]{0, 0});
        }
    }

    static void checks() {
        // band budget: clamp((mid - total) / div, 0, roundMax), mid and roundMax scaled by bots / fleet
        eq("bandBudget(255k, 102 bots)", Ledger.bandBudget(255_000, 102, 300_000, 60_000, 102, 2), 22_500);
        eq("bandBudget(299,999)", Ledger.bandBudget(299_999, 102, 300_000, 60_000, 102, 2), 0);
        eq("bandBudget(300k) at mid", Ledger.bandBudget(300_000, 102, 300_000, 60_000, 102, 2), 0);
        eq("bandBudget(350k) above mid", Ledger.bandBudget(350_000, 102, 300_000, 60_000, 102, 2), 0);
        eq("bandBudget(100k) clamps to roundMax", Ledger.bandBudget(100_000, 102, 300_000, 60_000, 102, 2), 60_000);
        eq("bandBudget(180k) just at the clamp", Ledger.bandBudget(180_000, 102, 300_000, 60_000, 102, 2), 60_000);
        eq("bandBudget(120k, 51 bots) half fleet", Ledger.bandBudget(120_000, 51, 300_000, 60_000, 102, 2), 15_000);
        eq("bandBudget(0, 51 bots) half roundMax", Ledger.bandBudget(0, 51, 300_000, 60_000, 102, 2), 30_000);
        eq("bandBudget no bots", Ledger.bandBudget(10_000, 0, 300_000, 60_000, 102, 2), 0);
        eq("bandBudget unreadable total", Ledger.bandBudget(-1, 102, 300_000, 60_000, 102, 2), 0);
        eq("bandBudget audit rule (div 6, max 30k) at 255k", Ledger.bandBudget(255_000, 102, 300_000, 30_000, 102, 6), 7_500);
        // export budget: min(exportMax, top - total), never under 0
        eq("exportBudget(255k)", Ledger.exportBudget(255_000, 102, 350_000, 30_000, 102), 30_000);
        eq("exportBudget(340k) only the room to the top", Ledger.exportBudget(340_000, 102, 350_000, 30_000, 102), 10_000);
        eq("exportBudget(350k) at the top", Ledger.exportBudget(350_000, 102, 350_000, 30_000, 102), 0);
        eq("exportBudget(400k) above the top", Ledger.exportBudget(400_000, 102, 350_000, 30_000, 102), 0);
        eq("exportBudget no bots", Ledger.exportBudget(0, 0, 350_000, 30_000, 102), 0);
        eq("bandDailyCap(102 bots)", Ledger.bandDailyCap(102, 60_000, 102, 12), 720_000);
        eq("bandDailyCap(51 bots)", Ledger.bandDailyCap(51, 60_000, 102, 12), 360_000);
        // wealth re-test and the top-up size
        check("isPoor(999, 1000)", Ledger.isPoor(999, 1_000));
        check("!isPoor(1000, 1000)", !Ledger.isPoor(1_000, 1_000));
        eq("unitsToLine(900 -> 1000 at 30 gp)", Ledger.unitsToLine(900, 1_000, 30, 100), 4);
        eq("unitsToLine(999 -> 1000 at 500 gp) at least 1", Ledger.unitsToLine(999, 1_000, 500, 100), 1);
        eq("unitsToLine(0 -> 1000 at 7 gp) capped at the lot", Ledger.unitsToLine(0, 1_000, 7, 100), 100);
        eq("unitsToLine at the line", Ledger.unitsToLine(1_000, 1_000, 30, 100), 0);
        eq("unitsToLine unit 0 keeps the lot max", Ledger.unitsToLine(10, 1_000, 0, 100), 100);
        // H1 in small: a bot at 900 gp with five 100-unit lots at 30 gp a unit
        long oldPaid = 0, livePaid = 0, w = 900; int liveLots = 0;
        for (int i = 0; i < 5; i++) oldPaid += 30L * 100; // today: wealth read once, every lot bought
        for (int i = 0; i < 5; i++) {
            if (!Ledger.isPoor(w, 1_000)) continue;
            long coins = 30L * Ledger.unitsToLine(w, 1_000, 30, 100);
            w += coins; livePaid += coins; liveLots++;
        }
        eq("H1 old rule pays every lot", oldPaid, 15_000);
        eq("H1 live rule pays to the line", livePaid, 120);
        eq("H1 live rule lots", liveLots, 1);
        check("H1 live wealth ends at or just over the line", w >= 1_000 && w < 1_000 + 30);
        // econ2 (-Dclerk.fix2): M2 floor, M4 band drift, L3 teleport rest, L7 mint kinds
        eq("floorPick flag off keeps botRef", Ledger.floorPick(70, 100, false), 70);
        eq("floorPick lifts to the base", Ledger.floorPick(70, 100, true), 100);
        eq("floorPick keeps a ref above the base", Ledger.floorPick(150, 100, true), 150);
        eq("floorPick no base", Ledger.floorPick(70, null, true), 70);
        eq("bandRef(102 bots)", CoinWatch.bandRef(102), Ledger.BAND_MID * 102 / Ledger.BAND_FLEET);
        eq("bandRef(0 bots)", CoinWatch.bandRef(0), 0);
        check("bandDrift: 255k of 300k (-15%) inside", !CoinWatch.bandDrift(CoinWatch.refPct(300_000, 255_000)));
        check("bandDrift: 248k of 300k outside", CoinWatch.bandDrift(CoinWatch.refPct(300_000, 248_000)));
        check("bandDrift: 352k of 300k outside", CoinWatch.bandDrift(CoinWatch.refPct(300_000, 352_000)));
        check("old refDrift misses 213k of 250k (the audit's case)", !CoinWatch.refDrift(CoinWatch.refPct(250_000, 213_000)));
        check("bandDriftLine text", CoinWatch.bandDriftLine(248_000, 300_000).startsWith("DRIFT total 248000 vs band middle 300000 (-17.3%)"));
        eq("teleRest all runes from the Exchange", Ledger.teleRest(1, 3, 200, 16), 0);
        eq("teleRest no runes", Ledger.teleRest(0, 0, 200, 16), 248);
        eq("teleRest law only", Ledger.teleRest(1, 0, 200, 16), 48);
        eq("teleRest partial air", Ledger.teleRest(0, 2, 200, 16), 216);
        eq("mintKind brokeShopSale", Ledger.mintKind("brokeShopSale"), 1);
        eq("mintKind sellOld in a lambda", Ledger.mintKind("lambda$sellOld$3"), 2);
        eq("mintKind other", Ledger.mintKind("restore"), 0);
        eq("mintKind null", Ledger.mintKind(null), 0);
        Ledger.mintLine(); Ledger.noteMint("brokeShopSale", 120); Ledger.noteMint("sellOld", 40); Ledger.noteMint("alch", 999);
        check("mintLine sums by kind", "[Clerk] mint brokeShopSale=120 sellOld=40".equals(Ledger.mintLine()));
        check("mintLine resets", Ledger.mintLine() == null);
        check("CLERK_FIX2 == flag", Ledger.CLERK_FIX2 == Boolean.getBoolean("clerk.fix2"));
        // econ3: M2 buy side, M3, L6, L4 and the two log lines
        eq("ceilPick flag off keeps botRef", Ledger.ceilPick(150, 100, false), 150);
        eq("ceilPick caps at the base", Ledger.ceilPick(150, 100, true), 100);
        eq("ceilPick keeps a ref under the base", Ledger.ceilPick(70, 100, true), 70);
        eq("ceilPick no base", Ledger.ceilPick(150, null, true), 150);
        for (int ref : new int[]{70, 100, 150}) check("M2 loop closed at ref " + ref, 100L * Ledger.ceilPick(ref, 100, true) < 110L * Ledger.floorPick(ref, 100, true));
        check("M2 loop open with the flag off", 90L * Ledger.ceilPick(150, 100, false) > 110L * Ledger.floorPick(70, 100, false));
        check("alchReady all there", Ledger.alchReady(10, 20, 60, 100, false, 10_000, 300, 200, 5));
        check("alchReady short of fire runes", !Ledger.alchReady(10, 20, 60, 49, false, 10_000, 300, 200, 5));
        check("alchReady fire staff needs no fire runes", Ledger.alchReady(10, 20, 60, 0, true, 5_000, 300, 200, 5));
        check("alchReady short of natures", !Ledger.alchReady(10, 20, 9, 100, true, 10_000, 300, 200, 5));
        check("alchReady short of bows", !Ledger.alchReady(10, 9, 60, 100, true, 10_000, 300, 200, 5));
        check("alchReady short of coins", !Ledger.alchReady(10, 20, 60, 100, false, 5_249, 300, 200, 5));
        check("alchReady coins exact", Ledger.alchReady(10, 20, 60, 100, false, 5_250, 300, 200, 5));
        check("fareOk flag off", Slayer.fareOk(0, false));
        check("fareOk under 5k", !Slayer.fareOk(4_999, true));
        check("fareOk at 5k", Slayer.fareOk(5_000, true));
        { long p0 = Ledger.PAID; Ledger.addPaid(7, true); Ledger.addPaid(-7, false); eq("addPaid both paths", Ledger.PAID, p0); }
        check("kinds line on a zero round", Ledger.kindsDue(true, 0, false));
        check("kinds line needs kindLog", !Ledger.kindsDue(false, 5, true));
        { java.util.List<Object[]> pw = new java.util.ArrayList<>();
          for (long[] r : new long[][]{{1, 0}, {2, 399}, {3, 400}, {4, 999}, {5, 4_999}, {6, 90_000}}) pw.add(new Object[]{"b" + r[0], Long.valueOf(r[1])});
          String pl = CoinWatch.poorLine(pw);
          check("poorLine: " + pl, "poor under400=2 under1000=4 under5000=5 richest=b6:90000 median=699".equals(pl));
          pw.remove(5);
          check("poorLine odd median", "poor under400=2 under1000=4 under5000=5 richest=b5:4999 median=400".equals(CoinWatch.poorLine(pw)));
          check("poorLine empty", "poor under400=0 under1000=0 under5000=0 richest=-:0 median=0".equals(CoinWatch.poorLine(new java.util.ArrayList<>()))); }
        check("poorDue first round", CoinWatch.poorDue(1_000_000L, 0, 1_800_000L));
        check("poorDue not within 30 min", !CoinWatch.poorDue(2_000_000L, 1_000_000L, 1_800_000L));
        check("poorDue after 30 min", CoinWatch.poorDue(2_800_000L, 1_000_000L, 1_800_000L));
        // boot1: the slot clock (pure)
        { final long H = 3_600_000L, M = 60_000L, boot = 100 * H;
          eq("slotAt just after :35", Ledger.slotAt(5 * H + 35 * M + 1, 35), 5 * H + 35 * M);
          eq("slotAt on :35", Ledger.slotAt(5 * H + 35 * M, 35), 5 * H + 35 * M);
          eq("slotAt at :34 is the hour before", Ledger.slotAt(5 * H + 34 * M, 35), 4 * H + 35 * M);
          check("fairHold: slot already served", "served".equals(Ledger.fairHold(boot + 50 * M, boot, boot + 36 * M, 35, 102, 102)));
          check("fairHold: boot grace", "grace".equals(Ledger.fairHold(boot + 29 * M, boot, boot - H, 35, 102, 102)));
          check("fairHold: fleet under 90%", "fleet".equals(Ledger.fairHold(boot + 44 * M, boot, boot - H, 35, 91, 102)));
          check("fairHold: fleet at 90%", Ledger.fairHold(boot + 31 * M, boot, boot - H, 35, 92, 102) == null);
          check("fairHold: fleet hold ends 45 min after boot", Ledger.fairHold(boot + 45 * M, boot, boot - H, 35, 0, 102) == null);
          check("fairHold: no roster, no hold", Ledger.fairHold(boot + 30 * M, boot, boot - H, 35, 0, 0) == null); }
        check("BOOT_FAIR == flag", Ledger.BOOT_FAIR == Boolean.getBoolean("clerk.bootFair"));
        // flag wiring: all three default off, and off means today's cap
        check("CLERK_WEALTH_LIVE == flag", Ledger.CLERK_WEALTH_LIVE == Boolean.getBoolean("clerk.wealthLive"));
        check("CLERK_BAND_BUDGET == flag", Ledger.CLERK_BAND_BUDGET == Boolean.getBoolean("clerk.bandBudget"));
        check("EXPORT_PAY == flag", Ledger.EXPORT_PAY == Boolean.getBoolean("market.exportPay"));
        if (!Ledger.CLERK_BAND_BUDGET) eq("dailyCap() with bandBudget off", Ledger.dailyCap(), Ledger.CLERK_DAILY_CAP);
        log("flags wealthLive=" + Ledger.CLERK_WEALTH_LIVE + " bandBudget=" + Ledger.CLERK_BAND_BUDGET + " exportPay=" + Ledger.EXPORT_PAY
            + " mid=" + Ledger.BAND_MID + " top=" + Ledger.BAND_TOP + " roundMax=" + Ledger.BAND_ROUND_MAX + " div=" + Ledger.BAND_DIV + " exportMax=" + Ledger.EXPORT_ROUND_MAX + " exportPct=" + Ledger.EXPORT_PCT);
    }
}
