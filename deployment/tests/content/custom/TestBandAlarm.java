package content.custom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * W9 band alarm (-Dcoinwatch.bandAware, default off), plain java (no world):
 *   OFF: java -cp <build>;server.jar content.custom.TestBandAlarm <addons-src>/content/custom
 *   ON : java -Dcoinwatch.bandAware=true -cp <build>;server.jar content.custom.TestBandAlarm <addons-src>/content/custom
 * The rule is pure (bandFloor / bandReason / bandVerdict / bandLine / hourDrop) and is fed the flag, so both runs check both
 * sides; the flag constant follows the run. Its wiring in CoinWatch.tick and startup is checked by source text (tick needs a world).
 *   A  flag off = old behaviour: hourDrop(false, ...) is dropReason with the old limits, old log lines and Failures context unchanged
 *   B  in band, no alarm (the live 11-19 case: 267k against a 604k boot total)
 *   C  under the floor: ALARM
 *   D  over coinwatch.bandAware.dropPct (15) down in one hour: ALARM wherever the total sits
 *   E  the boot sea-crossing exemption (-Dcoinwatch.bootSeaExempt) is still respected
 */
public final class TestBandAlarm {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestBandAlarm] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static final long MIN = 60_000L;
    static long[] L(long... v) { return v; }
    static int kind(String k) { AtomicInteger v = CoinWatch.KINDS.get(k); return v == null ? 0 : v.get(); }

    public static void main(String[] a) throws Exception {
        boolean on = Boolean.getBoolean("coinwatch.bandAware");
        boolean dflt = System.getProperty("clerk.bandMid") == null && System.getProperty("clerk.bandTop") == null && System.getProperty("clerk.bandFleet") == null;
        check("F1 flag constant follows -Dcoinwatch.bandAware (" + on + "), default off", CoinWatch.BAND_AWARE == on);
        check("F2 drop limit default 15%", CoinWatch.BAND_DROP_PCT == 15 || System.getProperty("coinwatch.bandAware.dropPct") != null);
        check("F3 floor from the clerk band constants: mid - (top - mid) at Ledger.BAND_FLEET",
                CoinWatch.bandFloor(Ledger.BAND_FLEET) == Ledger.BAND_MID - (Ledger.BAND_TOP - Ledger.BAND_MID));
        if (dflt) check("F4 defaults: floor 250,000 at 102 bots, 245,098 at 100", CoinWatch.bandFloor(102) == 250_000L && CoinWatch.bandFloor(100) == 245_098L);
        check("F5 pure floor: scaled, 0 bots = 0, top under mid = mid, top over 2 x mid = 0",
                CoinWatch.bandFloor(51, 300_000, 350_000, 102) == 125_000L && CoinWatch.bandFloor(0, 300_000, 350_000, 102) == 0
                        && CoinWatch.bandFloor(102, 300_000, 280_000, 102) == 300_000L && CoinWatch.bandFloor(102, 300_000, 700_000, 102) == 0);

        // A: flag off = the old drop rule, the same answer for every history
        long[][][] cases = {
                {L(604_000, 290_000, 278_000, 267_000), L(100, 100, 100, 100)}, // live 11-19 shape: stale boot total first
                {L(300_000, 170_000), L(100, 100)},                              // one hour over 40%
                {L(400_000, 380_000, 330_000, 290_000), L(100, 100, 100, 100)}, // three falling hours over 25%
                {L(270_000, 268_000), L(100, 100)},                              // flat
                {L(300_000, 150_000), L(50, 100)},                               // bot count changed
                {L(240_000), L(100)},                                            // baseline only
                {L(400_000, 300_000, 250_000, 200_000, 150_000), L(100, 100, 100, 100, 100)}};
        boolean same = true;
        for (long[][] c : cases) {
            long[] t = c[0], b = c[1]; int n = t.length;
            long prev = n > 1 ? t[n - 2] : t[0], pb = n > 1 ? b[n - 2] : b[0];
            same &= Objects.equals(CoinWatch.hourDrop(false, t, b, prev, pb, 245_098L, 15), CoinWatch.dropReason(t, b, CoinWatch.DROP_PCT, CoinWatch.DROP_HOURS, CoinWatch.SINGLE_PCT));
        }
        check("A1 flag off: hourDrop is dropReason with the old limits for every history", same);
        long[] stale = L(604_000, 290_000, 278_000, 267_000), sb = L(100, 100, 100, 100);
        if (CoinWatch.DROP_PCT == 25 && CoinWatch.DROP_HOURS == 3)
            check("A2 flag off: the stale-baseline case still alarms, same text",
                    "bot coins fell -55.8% over 3 hours, every hour down (604000 -> 267000 gp; over 25%)".equals(CoinWatch.hourDrop(false, stale, sb, 278_000, 100, 245_098L, 15)));
        if (a.length > 0) {
            String cw = new String(Files.readAllBytes(new File(a[0], "CoinWatch.java").toPath()), StandardCharsets.UTF_8);
            check("A3 status, baseline and ALARM lines unchanged", cw.contains("StringBuilder line = new StringBuilder(\"total \").append(cur).append(\" gp, \").append(bots).append(\" bots, baseline \").append(baseline).append(\" gp (\").append(pct(baseline, cur)).append(\")\");")
                    && cw.contains("log(\"baseline \" + t[0] + \" gp, \" + t[1] + \" bots\");") && cw.contains("log(\"ALARM coin-drain (bug 296): \" + lastAlarm);") && cw.contains("why.add(dropWhy + \", \" + bots + \" bots\");"));
            check("A4 tick: one decision call, flag fed in; off path is the old dropReason call", cw.contains("String dropWhy = hourDrop(BAND_AWARE, ht, hb, prev, pb, bandFloor, BAND_DROP_PCT);")
                    && cw.contains("if (!band) return dropReason(tot, bots, DROP_PCT, DROP_HOURS, SINGLE_PCT);") && !cw.contains("String dropWhy = dropReason("));
            check("A5 band line and band startup line only behind BAND_AWARE, once each", cw.contains("if (BAND_AWARE) { bandChecks++; if (drop) bandAlarms++; log(bandLine(cur, prev, pb, bots, bandFloor, bandRef(bots), BAND_DROP_PCT)); }")
                    && cw.split("log\\(bandLine\\(", -1).length == 2 && cw.contains("if (BAND_AWARE) log(\"band-aware drop rule on: "));
            check("A6 flag off: the old Failures context, unchanged, on the off branch", cw.contains(": Watchdog.ctx(\"kind\", \"total\", \"total\", cur, \"prev\", prev, \"baseline\", baseline, \"bots\", bots, \"botsBefore\", pb, \"limitPct\", DROP_PCT, \"singlePct\", SINGLE_PCT, \"hours\", DROP_HOURS, \"why\", dropWhy, \"paidActions\", paidN, \"worst\", worst, \"worstBot\", worstBot, \"bug\", 296, \"key\", \"296-total\"));")
                    && cw.contains(" in an hour\", null, BAND_AWARE"));
            int s0 = cw.indexOf("W9 band alarm (-Dcoinwatch.bandAware"), s1 = cw.indexOf("static String hourDrop(");
            String blk = s0 >= 0 && s1 > s0 ? cw.substring(s0, s1) : "";
            check("E3 the band block never touches the paid-action counts", !blk.isEmpty() && !blk.contains("KINDS") && !blk.contains("PAID.") && !blk.contains("countKind"));
            check("E4 countKind's boot exemption line unchanged", cw.contains("if (bootSeaExempt(bootSea, kind, sinceBootMs)) { if (bootSeaSkipped++ == 0) log(\"boot exemption: sea-crossing left out of the per-kind paid alarm until \" + BOOT_SEA_MS / 60_000L + \" min after boot\"); return; }"));
        }

        // B: in band, no alarm
        final long fl = 245_098L, mid = 294_117L; final int d = 15;
        check("B1 live 11-19 shape (267k, 604k boot total) is no alarm with the band rule", CoinWatch.hourDrop(true, stale, sb, 278_000, 100, fl, d) == null);
        check("B2 band line says why", "band total 267000 gp, floor 245098 gp (+8.9%), mid 294117 gp, 100 bots; hour -4.0% (278000 -> 267000 gp), limit -15%; verdict ok (at or over the floor, hour within -15%)"
                .equals(CoinWatch.bandLine(267_000, 278_000, 100, 100, fl, mid, d)));
        check("B3 at the floor exactly, a rise, a drop of exactly 15%: no alarm", CoinWatch.bandReason(fl, fl + 1, 100, 100, fl, d) == null
                && CoinWatch.bandReason(300_000, 260_000, 100, 100, fl, d) == null && CoinWatch.bandReason(255_000, 300_000, 100, 100, fl, d) == null);
        check("B4 over the band top, 10% down in an hour: no alarm", CoinWatch.bandReason(378_000, 420_000, 100, 100, fl, d) == null);
        check("B5 bot count changed over 10%: hour not judged, said so", CoinWatch.bandReason(250_000, 400_000, 80, 100, fl, d) == null
                && CoinWatch.bandVerdict(250_000, 400_000, 80, 100, fl, d).equals("ok (at or over the floor; hour not judged, bot count changed 80 -> 100)"));
        check("B6 no earlier reading: hour n/a, not judged", CoinWatch.bandReason(260_000, -1, 0, 100, fl, d) == null
                && CoinWatch.bandLine(260_000, -1, 0, 100, fl, mid, d).contains("; hour n/a, limit -15%; verdict ok (at or over the floor; hour not judged, no earlier reading)"));

        // C: under the floor
        String c1 = CoinWatch.bandReason(240_000, 246_000, 100, 100, fl, d);
        check("C1 under the floor (slow hour): ALARM with the reason", "bot coins fell under the band floor (240000 gp < floor 245098 gp, -2.1%)".equals(c1)
                && CoinWatch.bandVerdict(240_000, 246_000, 100, 100, fl, d).equals("ALARM (under the floor)"));
        check("C2 under the floor alarms even when the bot count changed", CoinWatch.bandReason(200_000, 230_000, 80, 100, fl, d) != null);
        check("C3 reason starts 'bot coins fell' (Bargain's drop trip)", c1 != null && c1.startsWith("bot coins fell"));
        check("C4 hourDrop(true) reads the newest total", CoinWatch.hourDrop(true, L(300_000, 246_000, 240_000), L(100, 100, 100), 246_000, 100, fl, d) != null
                && Objects.equals(CoinWatch.hourDrop(true, L(300_000, 246_000, 240_000), L(100, 100, 100), 246_000, 100, fl, d), c1));

        // D: fast drop, wherever the total sits
        check("D1 over the top, 16.7% down: ALARM", "bot coins fell -16.7% in an hour (420000 -> 350000 gp; over 15%, band rule)".equals(CoinWatch.bandReason(350_000, 420_000, 100, 100, fl, d))
                && CoinWatch.bandVerdict(350_000, 420_000, 100, 100, fl, d).equals("ALARM (fell over 15% in an hour)"));
        String both = CoinWatch.bandReason(200_000, 300_000, 100, 100, fl, d);
        check("D2 under the floor and fast: both reasons, both in the verdict", both != null && both.startsWith("bot coins fell under the band floor") && both.contains("; bot coins fell -33.3% in an hour")
                && CoinWatch.bandVerdict(200_000, 300_000, 100, 100, fl, d).equals("ALARM (under the floor; fell over 15% in an hour)"));
        check("D3 a wider limit (30%) lets 16.7% pass", CoinWatch.bandReason(350_000, 420_000, 100, 100, fl, 30) == null);
        check("D4 a bot count change over 10% is not a fast drop (as dropReason)", CoinWatch.bandReason(300_000, 420_000, 80, 100, fl, d) == null);
        check("D5 fast drop in band (330k from 400k) alarms with the band rule", CoinWatch.hourDrop(true, L(400_000, 330_000), L(100, 100), 400_000, 100, fl, d) != null);

        // E: boot exemption
        check("E1 bootSeaExempt unchanged: sea-crossing 0-29 min with the flag only", CoinWatch.bootSeaExempt(true, "sea-crossing", 10 * MIN) && !CoinWatch.bootSeaExempt(true, "sea-crossing", 30 * MIN)
                && !CoinWatch.bootSeaExempt(false, "sea-crossing", 10 * MIN) && !CoinWatch.bootSeaExempt(true, "boat", 10 * MIN));
        if (CoinWatch.ON) {
            CoinWatch.KINDS.clear();
            CoinWatch.countKind("sea-crossing", true, 10 * MIN);
            int early = kind("sea-crossing");
            CoinWatch.countKind("boat", true, 10 * MIN);
            CoinWatch.countKind("sea-crossing", true, 31 * MIN);
            check("E2 countKind: sea-crossing at minute 10 not counted, at 31 counted, other kinds counted", early == 0 && kind("sea-crossing") == 1 && kind("boat") == 1);
            CoinWatch.KINDS.clear();
        } else check("E2 needs CoinWatch on (no -Dcoinwatch=false)", false);

        System.out.println("[TestBandAlarm] flag " + (on ? "ON" : "OFF") + ": DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
