package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.b22coinwatch=true): batch 22 task 4, the coin-drain alarm (CoinWatch, bug 296). Short windows:
 *   -Dcoinwatch.warmMin=1 -Dcoinwatch.periodMin=3 -Dcoinwatch.logMin=1 -Dcoinwatch.maxPaid=5 -Dsmartbots.count=0
 * Fault injection: CoinWatch.testExtra = 10,000,000 gp (BIG) is counted in the baseline, then taken away (over the single-hour 40% limit), and one made-up bot gets 6 paid actions (over the limit of 5).
 *   B: the baseline is taken after the warm-up and includes BIG.
 *   P: the paid alarm fires at the next minute check (paidAlarms + 1, the bot named in the alarm).
 *   O: 3 more paid actions for the same bot in the same window: no second paid alarm.
 *   D: the hourly check (3 min here) fires the drop alarm (dropAlarms + 1, "fell" in the alarm).
 * With -Dcoinwatch=false (kill switch): K: no baseline, no alarm, count() records nothing after ~5 minute checks.
 *   batch 22-1 pure checks of CoinWatch.dropReason (run first, also with the kill switch):
 *   N: a normal shopping swing (-26% in one hour, then back up) gives no drop alarm at any hour.
 *   L: the bug 296 loop (about -9% an hour, 3 hours, 30 bots) gives the alarm at the third falling hour and not before;
 *      one hour down over 40% alarms alone; a bot count change over 10% (logins) does not alarm.
 *   KP: 61 paid actions of one kind across several bots give one fleet-wide alarm in the minute check (kindAlarms + 1);
 *      14 of another kind give none (normal).
 * Expected log lines: "[CoinWatch] baseline ", "[CoinWatch] ALARM coin-drain (bug 296): paid actions over 5 ...",
 * "[CoinWatch] ALARM coin-drain (bug 296): bot coins fell ..." (each exactly 1 normal, 0 kill switch; "[CoinWatch] off" 1 with it).
 */
public final class TestB22CoinWatch implements StartupListener {
    static final String FAKE = "b22-coinwatch-testbot";
    /** The fault: well over any trial's bot coins, so taking it away is over the single-hour 40% limit. */
    static final String KIND = "b22-test-crossing", KIND_OK = "b22-test-normal";
    static final long BIG = 10_000_000L;
    static void log(String s) { System.out.println("[TestB22CoinWatch] " + s); }

    static long[] a(long... v) { return v; }
    static boolean dropOf(long[] tot, long[] bots) { return CoinWatch.dropReason(tot, bots, CoinWatch.DROP_PCT, CoinWatch.DROP_HOURS, CoinWatch.SINGLE_PCT) != null; }
    /** N and L: dropReason only (defaults: 25% over 3 falling hours, 40% in one). */
    static int logic() {
        int fails = 0;
        long[] b4 = a(100, 100, 100, 100);
        boolean n1 = !dropOf(a(200_000, 147_000), a(100, 100)), n2 = !dropOf(a(200_000, 147_000, 176_000), a(100, 100, 100)), n3 = !dropOf(a(200_000, 147_000, 176_000, 170_000), b4), n4 = !dropOf(a(200_000, 190_000, 150_000, 150_000), b4);
        fails += report("N", n1 && n2 && n3 && n4, "shopping swing: -26% hour " + n1 + ", then up " + n2 + ", then flat " + n3 + ", 25%+ over 3 hours but one hour flat/rising " + n4);
        boolean l1 = !dropOf(a(204_068, 185_568), a(100, 100)), l2 = !dropOf(a(204_068, 185_568, 167_068), a(100, 100, 100)), l3 = dropOf(a(204_068, 185_568, 167_068, 148_568), b4);
        boolean l4 = dropOf(a(200_000, 110_000), a(100, 100)), l5 = !dropOf(a(200_000, 150_000, 100_000, 50_000), a(100, 100, 60, 30));
        fails += report("L", l1 && l2 && l3 && l4 && l5, "crossing loop: hour 1 quiet " + l1 + ", hour 2 quiet " + l2 + ", hour 3 alarm " + l3 + ", one hour -45% alarm " + l4 + ", bot count change quiet " + l5);
        return fails;
    }
    static int report(String name, boolean pass, String what) { log("RESULT " + name + " " + (pass ? "PASS" : "FAIL") + ": " + what); return pass ? 0 : 1; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b22coinwatch")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, ticks, d0, p0, k0, fails;
            void result(String name, boolean pass, String what) { if (!pass) fails++; log("RESULT " + name + " " + (pass ? "PASS" : "FAIL") + ": " + what); }
            boolean finish() { CoinWatch.testExtra = 0; log("summary: " + (fails == 0 ? "PASS" : fails + " FAIL") + " (coinwatch " + (CoinWatch.ON ? "on" : "off") + ")"); log("DONE"); return true; }
            @Override public boolean pulse() {
                try {
                    t++; ticks++;
                    if (t == 1) fails += logic();
                    if (phase == 0) {
                        if (t < 5) { if (t == 1 && CoinWatch.ON) CoinWatch.testExtra = BIG; return false; }
                        if (!CoinWatch.ON) { log("kill switch run: watching for ~300 ticks"); phase = 9; ticks = 0; return false; }
                        if (CoinWatch.started == 0) { result("setup", false, "CoinWatch did not start (bots off in this world?)"); return finish(); }
                        log("fault: +" + BIG + " gp in the totals until the baseline (drop " + CoinWatch.DROP_PCT + "%, maxPaid " + CoinWatch.MAX_PAID + ", period " + CoinWatch.PERIOD_MS / 60_000 + " min)");
                        phase = 1; ticks = 0; return false;
                    }
                    if (phase == 9) {
                        CoinWatch.count(FAKE);
                        if (ticks < 300) return false;
                        result("K", CoinWatch.baseline < 0 && CoinWatch.dropAlarms == 0 && CoinWatch.paidAlarms == 0 && CoinWatch.PAID.isEmpty(),
                                "baseline " + CoinWatch.baseline + ", drop alarms " + CoinWatch.dropAlarms + ", paid alarms " + CoinWatch.paidAlarms + ", counted bots " + CoinWatch.PAID.size());
                        return finish();
                    }
                    if (phase == 1) {
                        if (CoinWatch.baseline < 0) { if (ticks > 700) { result("B", false, "no baseline after " + ticks + " ticks"); return finish(); } return false; }
                        long nb = CoinWatch.total()[1]; if (Math.abs(nb - CoinWatch.baselineBots) * 10L > CoinWatch.baselineBots && ticks < 700) return false; // batch 22 fix: bots still logging in after the baseline; fault only once the count holds
                        result("B", CoinWatch.baseline >= BIG && CoinWatch.baselineBots > 0, "baseline " + CoinWatch.baseline + " gp, " + CoinWatch.baselineBots + " bots, after " + ticks + " ticks");
                        CoinWatch.testExtra = 0; // the fault: 100,000 gp gone before the hourly check
                        d0 = CoinWatch.dropAlarms; p0 = CoinWatch.paidAlarms; k0 = CoinWatch.kindAlarms;
                        for (int i = 0; i < 61; i++) { CoinWatch.countKind(KIND); CoinWatch.count(FAKE + (i % 4)); } // 61 crossings spread over 4 bots: each under the per-bot limit
                        for (int i = 0; i < 14; i++) CoinWatch.countKind(KIND_OK);
                        int limit = CoinWatch.MAX_PAID + 1; for (int i = 0; i < limit; i++) CoinWatch.count(FAKE);
                        log("fault: the " + BIG + " gp taken away, " + limit + " paid actions for " + FAKE);
                        phase = 2; ticks = 0; return false;
                    }
                    if (phase == 2) {
                        if (CoinWatch.paidAlarms == p0 && ticks < 250) return false;
                        String a = CoinWatch.lastAlarm;
                        result("P", CoinWatch.paidAlarms == p0 + 1 && a != null && a.contains(FAKE), "after " + ticks + " ticks: paid alarms " + p0 + " -> " + CoinWatch.paidAlarms + ", last alarm: " + a);
                        result("KP", CoinWatch.kindAlarms == k0 + 1 && a != null && a.contains(KIND) && !a.contains(KIND_OK), "kind alarms " + k0 + " -> " + CoinWatch.kindAlarms + ", last alarm: " + a);
                        for (int i = 0; i < 3; i++) CoinWatch.count(FAKE);
                        phase = 3; ticks = 0; return false;
                    }
                    if (phase == 3) {
                        if (CoinWatch.dropAlarms == d0 && ticks < 700) return false;
                        String a = CoinWatch.lastAlarm;
                        result("D", CoinWatch.dropAlarms == d0 + 1 && a != null && a.contains("fell"), "after " + ticks + " ticks: drop alarms " + d0 + " -> " + CoinWatch.dropAlarms + ", checks " + CoinWatch.checks + ", last alarm: " + a);
                        result("O", CoinWatch.paidAlarms == p0 + 1, "paid alarms " + p0 + " -> " + CoinWatch.paidAlarms + " (one per bot per window)");
                        return finish();
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); return finish(); }
                return false;
            }
        });
    }
}
