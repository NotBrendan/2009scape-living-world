package content.custom;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P38 L7: CoinWatch baseline rule (a) and log-only RISE rule (b), fed synthetic samples. Plain java, no server or world needed
 * (CoinWatch.baselineReady / riseSample are pure apart from CoinWatch's own static state). Run with NO -Dcoinwatch.* flags:
 *   javac --release 11 -cp server.jar -d out deployment/addons-src/content/custom/*.java deployment/addons-src/core/game/ge/*.java deployment/tests/content/custom/TestP38CoinWatch.java
 *   java -cp "out;server.jar" content.custom.TestP38CoinWatch deployment/addons-src/content/custom/CoinWatch.java
 * Checks:
 *   D:  defaults unchanged (drop 25%/3 h, single 40%, maxPaid 25, maxKind 60) and the rise flag is off by default.
 *   A1: ramping count 10..54 of a 60-bot fleet: no baseline while ramping, baseline at the 54-bot reading (90%).
 *   A2: the 45-bot login plateau of a 102-bot fleet: no baseline at minute 1 (the old one-minute rule), baseline only once the
 *       count held 3 samples HOLD_MS apart, not a minute earlier.
 *   A3: a plateau broken by a login wave (45 -> 70) starts the hold again; 0 bots never baseline.
 *   A4: fleet 0 (-Dsmartbots.count=0 trials, TestB22CoinWatch): the hold path still takes a baseline.
 *   S:  6 h of a steady 100-bot fleet (sawtooth +-30k, clerk rounds up to 194k and one full 2,000,000 day-cap round), flag on: no RISE.
 *   F:  flag on, +3,000,000 gp in 30 min with the count steady: exactly one "[CoinWatch] RISE " line (rise keeps going 50 min: still one).
 *   O:  flag off, the same rise: no RISE line and nothing recorded.
 *   L:  flag on, the same rise while the count jumps 50 -> 100 (logins): no RISE.
 *   G:  source keeps the existing "[CoinWatch] baseline ", "total ", "ALARM coin-drain" formats.
 * Prints one PASS/FAIL line per check and "[TestP38CoinWatch] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestP38CoinWatch {
    static int pass, fail;
    static final long T0 = 1_793_923_200_000L; // 2026-11-06 00:00 UTC
    static final long MIN = 60_000L;

    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestP38CoinWatch] " + (ok ? "PASS " : "FAIL ") + what); }

    static void reset() {
        CoinWatch.baseline = -1; CoinWatch.warmBots = -1; CoinWatch.holdN = 0; CoinWatch.holdAt = 0;
        CoinWatch.RISE_HIST.clear(); CoinWatch.rises = 0; CoinWatch.riseAt = -1;
    }

    /** Minute (from T0) at which baselineReady first says yes for counts[i] read at minute i, or -1. */
    static int firstReady(int[] counts, int fleet) {
        reset();
        for (int i = 0; i < counts.length; i++) if (CoinWatch.baselineReady(T0 + i * MIN, counts[i], fleet) != null) return i;
        return -1;
    }
    static int[] fill(int n, int v) { int[] a = new int[n]; java.util.Arrays.fill(a, v); return a; }

    /** Feeds {minute, total, bots} status samples to riseSample with stdout captured; returns the "[CoinWatch] RISE " line count. */
    static int riseLines(long[][] samples, boolean on) {
        reset();
        PrintStream old = System.out; ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try { for (long[] s : samples) CoinWatch.riseSample(T0 + s[0] * MIN, s[1], (int) s[2], on); }
        finally { System.setOut(old); }
        int n = 0;
        for (String line : new String(buf.toByteArray(), StandardCharsets.UTF_8).split("\\R")) if (line.startsWith("[CoinWatch] RISE ")) { n++; System.out.println("  " + line); }
        return n;
    }

    public static void main(String[] args) throws Exception {
        long hold = CoinWatch.HOLD_MS / MIN;
        check("D defaults: drop " + CoinWatch.DROP_PCT + "%/" + CoinWatch.DROP_HOURS + " h, single " + CoinWatch.SINGLE_PCT + "%, maxPaid " + CoinWatch.MAX_PAID + ", maxKind " + CoinWatch.MAX_KIND + ", rise flag " + CoinWatch.RISE_ON + ", fleetPct " + CoinWatch.FLEET_PCT + ", hold " + CoinWatch.HOLD_SAMPLES + " x " + hold + " min, riseGp " + CoinWatch.RISE_GP,
                CoinWatch.DROP_PCT == 25 && CoinWatch.DROP_HOURS == 3 && CoinWatch.SINGLE_PCT == 40 && CoinWatch.MAX_PAID == 25 && CoinWatch.MAX_KIND == 60
                        && !CoinWatch.RISE_ON && CoinWatch.FLEET_PCT == 90 && CoinWatch.HOLD_SAMPLES == 3 && CoinWatch.RISE_GP == 2_500_000L && Ledger.CLERK_DAILY_CAP == 2_000_000L);

        // A1: ramp 10,20,30,40,50 (one reading each), then 54 = 90% of 60
        int a1 = firstReady(new int[]{10, 20, 30, 40, 50, 54, 58, 60}, 60);
        check("A1 ramping 10..60 of a 60 fleet: baseline at minute " + a1 + " (54 bots, expect 5)", a1 == 5);

        // A2: 102 fleet, 45 bots for 60 minutes (the live 11-05 plateau)
        int a2 = firstReady(fill(61, 45), 102);
        reset(); boolean minuteOne = false; for (int i = 0; i < 2; i++) minuteOne |= CoinWatch.baselineReady(T0 + i * MIN, 45, 102) != null;
        check("A2 45-bot plateau of a 102 fleet: none at minute 1 (old rule) " + !minuteOne + ", baseline at minute " + a2 + " (expect " + (CoinWatch.HOLD_SAMPLES - 1) * hold + ")",
                !minuteOne && a2 == (CoinWatch.HOLD_SAMPLES - 1) * hold);

        // A3: 45 until just before the hold completes, then a login wave to 70 (still under 90% of 102): the hold restarts at 70
        int brk = (int) ((CoinWatch.HOLD_SAMPLES - 1) * hold) - 1;
        int[] c3 = new int[brk + 61]; for (int i = 0; i < c3.length; i++) c3[i] = i < brk ? 45 : 70;
        int a3 = firstReady(c3, 102), a3z = firstReady(fill(60, 0), 102);
        check("A3 plateau broken at minute " + brk + " (45 -> 70): baseline at minute " + a3 + " (expect " + (brk + (CoinWatch.HOLD_SAMPLES - 1) * hold) + "), 0 bots never " + (a3z < 0),
                a3 == brk + (CoinWatch.HOLD_SAMPLES - 1) * hold && a3z < 0);

        // A4: fleet 0 -> the hold path alone
        int a4 = firstReady(fill(61, 12), 0);
        check("A4 fleet 0 (smartbots.count=0 trials): hold path baseline at minute " + a4, a4 == (CoinWatch.HOLD_SAMPLES - 1) * hold);

        // S: 6 h, a status sample every 10 min, 100 bots; :47 low / :17 high sawtooth 30k, clerk rounds; one full-cap round
        long[][] s = new long[37][];
        long[] rounds = {97_700, 15_000, 21_600, 34_800, 194_100, 2_000_000};
        long base = 270_000;
        for (int i = 0; i < s.length; i++) {
            int hour = i / 6, slot = i % 6;
            long saw = slot == 2 ? 30_000 : slot == 5 ? -30_000 : 0; // +30k after the round, -30k before the next
            long clerk = 0; for (int h = 0; h <= hour && h < rounds.length; h++) if (h < hour || slot >= 2) clerk += rounds[h];
            long paid = 40_000L * i / 6; // ~40k/h [Paid] sink
            s[i] = new long[]{i * 10L, base + saw + clerk - paid, 100 + (i % 3)};
        }
        int sOn = riseLines(s, true);
        check("S steady 100-bot fleet 6 h incl. a 2,000,000 gp clerk round, flag on: RISE lines " + sOn + " (expect 0)", sOn == 0);

        // F: flag on, +3,000,000 in 30 min, keeps rising 50 more min
        long[][] f = { {0, 270_000, 100}, {10, 275_000, 100}, {20, 1_270_000, 100}, {30, 3_275_000, 101}, {40, 4_000_000, 100},
                {50, 5_000_000, 100}, {60, 6_000_000, 100}, {70, 7_000_000, 100}, {80, 8_000_000, 100} };
        int fOn = riseLines(f, true);
        check("F fast rise (+3,000,000 gp in 30 min, still rising 50 min more), flag on: RISE lines " + fOn + " (expect 1), rises counter " + CoinWatch.rises, fOn == 1 && CoinWatch.rises == 1);

        // O: flag off
        int fOff = riseLines(f, false);
        check("O same rise, flag off: RISE lines " + fOff + " (expect 0), samples kept " + CoinWatch.RISE_HIST.size(), fOff == 0 && CoinWatch.RISE_HIST.isEmpty() && CoinWatch.rises == 0);

        // L: same rise during a login wave 50 -> 100 bots
        long[][] l = { {0, 270_000, 50}, {10, 280_000, 50}, {20, 3_300_000, 100}, {30, 3_310_000, 100} };
        int lOn = riseLines(l, true);
        check("L same rise while bots 50 -> 100 (logins), flag on: RISE lines " + lOn + " (expect 0)", lOn == 0);

        // G: existing line formats kept
        String src = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "deployment/addons-src/content/custom/CoinWatch.java")), StandardCharsets.UTF_8);
        check("G existing formats kept: baseline/total/hour/ALARM",
                src.contains("log(\"baseline \" + t[0] + \" gp, \" + t[1] + \" bots\");") && src.contains("new StringBuilder(\"total \").append(cur).append(\" gp, \").append(bots).append(\" bots, baseline \")")
                        && src.contains("log(\"ALARM coin-drain (bug 296): \" + lastAlarm);") && src.contains("line.append(\"; hour: was \")"));

        reset();
        System.out.println("[TestP38CoinWatch] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
