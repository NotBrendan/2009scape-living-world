package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W19 (2026-11-21): the White Wolf Mountain gate on Hunter runs (q22b+q22 and q25b+q25 came back WORSE on deaths and stuck).
 * Plain java, no world needed (Hunter's pure rules; server.jar on the classpath for its imports).
 *   java -cp "out;server.jar" content.custom.TestW19 off <addons-src/content/custom>
 *   java -Dbots.hunter=true -Dbots.hunter.chins=true -cp "out;server.jar" content.custom.TestW19 on <dir>
 * Checks: D flag state; G the gate rule on the tiles and combat levels from the farm logs; H the hook in the source
 * (behind bots.hunter, before the RNG draw, no cooldown booked, the old rules untouched).
 * Prints one PASS/FAIL line per check and "[TestW19] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestW19 {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestW19] " + (ok ? "PASS " : "FAIL ") + what); }

    public static void main(String[] a) throws Exception {
        boolean on = a.length > 0 && a[0].equals("on");
        check("D1 flag " + (on ? "on" : "off") + ": Hunter.ON " + Hunter.ON + ", CHINS " + Hunter.CHINS, Hunter.ON == on && (on || !Hunter.CHINS));
        check("D2 defaults: west of x " + Hunter.WOLF_X + ", combat " + Hunter.WOLF_COMBAT,
                Hunter.WOLF_X == 2840 && Hunter.WOLF_COMBAT == (Integer.getInteger("bots.hunter.wolfCombat") == null ? 77 : Hunter.WOLF_COMBAT));

        final int W = 77;
        // G: the bots that died or stuck on the mountain in the ON arms all started east of it under combat 77
        int[][] died = {{3222, 43}, {3222, 50}, {3165, 54}, {2965, 44}, {2895, 35}, {2880, 41}}; // {start x (east side), combat}
        boolean all = true; for (int[] d : died) all &= !Hunter.wolfSafe(d[0], d[1], W);
        check("G1 east-side starts at combat 35-54 are refused", all);
        check("G2 the mountain itself counts as east (death tiles x 2847-2866, stuck x 2843)", !Hunter.wolfSafe(2843, 60, W) && !Hunter.wolfSafe(2866, 60, W) && !Hunter.wolfSafe(2840, 76, W));
        check("G3 west-side starts keep the train-70 rule (Catherby 2809, Seers' 2725, Ardougne 2655, Yanille 2612)",
                Hunter.wolfSafe(2839, 40, W) && Hunter.wolfSafe(2809, 40, W) && Hunter.wolfSafe(2725, 3, W) && Hunter.wolfSafe(2655, 40, W) && Hunter.wolfSafe(2612, 40, W));
        check("G4 combat 77+ (white wolves level 38 stop attacking at 77) may start anywhere", Hunter.wolfSafe(3222, 77, W) && Hunter.wolfSafe(2850, 126, W) && !Hunter.wolfSafe(3222, 76, W));
        check("G5 wolfCombat 0 is the train-70 rule (no gate)", Hunter.wolfSafe(3222, 3, 0) && Hunter.wolfSafe(2850, 3, 0));
        check("G6 the old gates are as they were (combat 40, 10 free slots, cooldown, chins at 63)",
                Hunter.fit(5, 40, 10, 10_000, 0, 1000) && !Hunter.fit(5, 39, 10, 10_000, 0, 1000) && !Hunter.fit(5, 40, 9, 10_000, 0, 1000) && !Hunter.fit(5, 40, 10, 10_000, 9_500, 1000)
                        && Hunter.kind(63, true) == Hunter.KIND_CHIN && Hunter.kind(63, false) == Hunter.KIND_SWIFT && Hunter.kind(62, true) == Hunter.KIND_SWIFT);

        if (a.length > 1) {
            String h = new String(Files.readAllBytes(Paths.get(a[1], "Hunter.java")), StandardCharsets.UTF_8).replace("\r", "");
            int plan = h.indexOf("static boolean plan("), flag = h.indexOf("if (!ON || br == null) return false;", plan), fit = h.indexOf("if (!fit(", plan),
                    gate = h.indexOf("if (!wolfSafe(", plan), draw = h.indexOf("RNG.nextInt(hunter < 20 ? 4 : 8)", plan), kit = h.indexOf("if (!kit(", plan);
            check("H1 the gate is in plan, after the flag and fit, before the RNG draw and the kit buy", plan > 0 && flag > plan && fit > flag && gate > fit && draw > gate && kit > draw);
            String line = ""; for (String l : h.split("\n")) if (l.contains("if (!wolfSafe(")) line = l;
            check("H2 a refusal books no cooldown and spends nothing", line.contains("refusedWolf++") && line.contains("return false") && !line.contains("LAST.put") && !line.contains("Ledger") && !line.contains("RNG"));
            int n = 0; for (int i = h.indexOf("wolfSafe("); i >= 0; i = h.indexOf("wolfSafe(", i + 1)) n++;
            check("H3 one call site (plan) and the rule", n == 2);
            check("H4 the [Hunter] line keeps its train-70 fields; refusedWolf is appended only when counted",
                    h.contains("\" walkFail=\" + walkFail + \" ended=\" + ended + (tickFails > 0 ? \" tickFails=\" + tickFails : \"\") + (refusedWolf > 0 ? \" refusedWolf=\" + refusedWolf : \"\")"));
            String sa = new String(Files.readAllBytes(Paths.get(a[1], "SmartAdventurers.java")), StandardCharsets.UTF_8);
            check("H5 SmartAdventurers knows nothing of the gate", !sa.contains("wolfSafe") && !sa.contains("Hunter.WOLF"));
        }
        System.out.println("[TestW19] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
