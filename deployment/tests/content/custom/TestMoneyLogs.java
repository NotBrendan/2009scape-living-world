package content.custom;

/** money-logs: pure helpers of the CoinWatch REF/DRIFT lines. Plain java, no world. */
public final class TestMoneyLogs {
    static int pass, fail;
    static void check(String w, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestMoneyLogs] " + (ok ? "PASS " : "FAIL ") + w); }
    public static void main(String[] a) {
        check("ref line", CoinWatch.refLine(1100, 1000).equals("REF total 1100 vs ref 1000 (+10.0%)"));
        check("pct neg", Math.abs(CoinWatch.refPct(1000, 650) + 35.0) < 1e-9);
        check("drift low", CoinWatch.refDrift(-30.1) && !CoinWatch.refDrift(-30));
        check("drift high", CoinWatch.refDrift(60.1) && !CoinWatch.refDrift(60));
        check("drift line", CoinWatch.driftLine(600, 1000).startsWith("DRIFT total 600 vs ref 1000 (-40.0%)"));
        check("ref off by default", CoinWatch.REF_GP == 0 && CoinWatch.refPct(0, 5) == 0);
        System.out.println("[TestMoneyLogs] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
