package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W10 lane L: bots superheat bot-stock ore into bars for the Exchange (-Dbots.magic.superheat, W10Skills / W10Hooks), plain
 * java (no world, no cache):
 *   java -cp "<build>;server.jar" content.custom.TestW10Superheat <addons-src>/content/custom
 * Run it bare (flag off) and with -Dbots.magic.superheat=true. The pure rules run in every case; the wiring is read from the
 * source: the hook sits behind W10Skills.SUPERHEAT in kitFromExchange, inputs come only from bot stock (Ledger.consume), the
 * bars go back through Ledger.supply, no coins are given, no RNG is drawn.
 */
public final class TestW10Superheat {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW10Superheat] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), hk = read(dir, "W10Hooks.java");

        // F: the flag follows its property only; the hourly line names superheat only with it on
        check("F flag: SUPERHEAT " + W10Skills.SUPERHEAT + " = -Dbots.magic.superheat (" + Boolean.getBoolean("bots.magic.superheat") + ")", W10Skills.SUPERHEAT == Boolean.getBoolean("bots.magic.superheat"));
        String line = W10Skills.line(1_000L);
        check("F [W10] line: superheat(...) only with the flag on (" + line + ")", line.contains(" superheat(runs=") == W10Skills.SUPERHEAT && (W10Skills.anyOn() || line.endsWith(" off")));

        // 0: flag off = no-op (source text)
        int kit = sa.indexOf("void kitFromExchange() {"), arrows = sa.indexOf("W10Hooks.arrows(this)", kit);
        int hook = sa.indexOf("if (W10Skills.SUPERHEAT) try { W10Hooks.superheat(this); } catch (Throwable t) {", kit);
        int gotLine = sa.indexOf("if (got > 0) { try { bot.getEquipment().refresh(); }", kit);
        check("0 hook: in kitFromExchange right after the arrows hook, before the got>0 chat line, behind W10Skills.SUPERHEAT, in try/catch Throwable",
                kit > 0 && arrows > kit && hook > arrows && gotLine > hook && gotLine - hook < 500 && sa.split("W10Hooks.superheat\\(", -1).length == 2);
        int sh = hk.indexOf("static void superheat(");
        String body = sh < 0 ? "" : hk.substring(sh);
        check("0 money: inputs only through buy() (Ledger.consume), the bars through Ledger.supply, never Wallet.pay/give",
                sh > 0 && body.contains("Ledger.supply(p, r[0], k);") && !hk.contains("Wallet.pay(") && !hk.contains("Wallet.give(") && body.contains("buy(b, W10Skills.NATURE, n, \"superheat\")"));
        check("0 own goods: the casts count only units bought for them (k = min of what buy() placed)",
                body.contains("int k = Math.min(n, buy(b, W10Skills.NATURE, n, \"superheat\"));") && body.contains("k = Math.min(k, buy(b, r[2], k, \"superheat\"));"));
        check("0 Magic first: under 43 returns before booking the per-bot gap", sh > 0 && body.indexOf("if (m < W10Skills.HEAT_LEVEL) return;") < body.indexOf("W10Skills.take(W10Skills.HEAT_NEXT"));

        // A: the table
        int[][] R = W10Skills.HEAT_ROWS;
        check("A table: iron 15, silver 20, steel 30 (2 coal), gold 40, mithril 50 (4), adamant 70 (6), rune 85 (8); XP 12.5..50",
                R.length == 7 && R[0][0] == 2351 && R[0][2] == 440 && R[2][3] == 2 && R[3][1] == 40 && R[4][3] == 4 && R[5][3] == 6 && R[6][1] == 85 && R[6][3] == 8 && R[0][4] == 125 && R[6][4] == 500);
        check("A staves: staff of fire and the lava staves cover the fire runes; a plain staff does not", W10Skills.fireStaff(1387) && W10Skills.fireStaff(3054) && !W10Skills.fireStaff(1381) && !W10Skills.fireStaff(-1));
        check("A cast cost: ore + coal + nature + 4 fire; a fire staff drops the fire", W10Skills.castCost(100, 50, 2, 200, 5, false) == 100 + 100 + 200 + 20 && W10Skills.castCost(100, 50, 2, 200, 5, true) == 400);

        // B: which bar
        int L = R.length;
        int[] ore = new int[L], price = new int[L]; boolean[] glut = new boolean[L]; long[] cost = new long[L];
        for (int i = 0; i < L; i++) { ore[i] = 500; price[i] = 1_000; cost[i] = 900; }
        check("B pick: Magic 42 -> none", W10Skills.heatRow(42, 99, ore, 9_999, glut, cost, price) == -1);
        check("B pick: Smithing 55 -> mithril (adamant needs 70)", W10Skills.heatRow(43, 55, ore, 9_999, glut, cost, price) == 4);
        check("B pick: Smithing 99 -> rune", W10Skills.heatRow(99, 99, ore, 9_999, glut, cost, price) == 6);
        check("B pick: Smithing 14 -> none (iron needs 15)", W10Skills.heatRow(99, 14, ore, 9_999, glut, cost, price) == -1);
        boolean[] g2 = glut.clone(); g2[4] = true;
        check("B pick: mithril bars in glut -> gold", W10Skills.heatRow(60, 55, ore, 9_999, g2, cost, price) == 3);
        int[] o2 = ore.clone(); o2[4] = 24;
        check("B pick: mithril ore under 5 casts over the floor -> gold", W10Skills.heatRow(60, 55, o2, 9_999, glut, cost, price) == 3);
        check("B pick: coal short for 5 mithril casts (4 x 5 + 20) -> gold (no coal)", W10Skills.heatRow(60, 55, ore, 39, glut, cost, price) == 3 && W10Skills.heatRow(60, 55, ore, 40, glut, cost, price) == 4);
        long[] c2 = cost.clone(); c2[4] = 1_151;
        check("B pick: a cast over CEIL_X (1.15) x the bar price -> the bar below", W10Skills.heatRow(60, 55, ore, 9_999, glut, c2, price) == 3);

        // C: how many
        check("C qty: capped by max", W10Skills.heatQty(500, 500, 0, 500, 500, false, 400, 10_000_000, 400, 27) == 27);
        check("C qty: by ore, natures, fires (4 a cast) and coal (per bar)", W10Skills.heatQty(10, 500, 0, 500, 500, false, 400, 10_000_000, 400, 27) == 10
                && W10Skills.heatQty(500, 500, 0, 8, 500, false, 400, 10_000_000, 400, 27) == 8 && W10Skills.heatQty(500, 500, 0, 500, 39, false, 400, 10_000_000, 400, 27) == 9
                && W10Skills.heatQty(500, 47, 4, 500, 500, false, 400, 10_000_000, 400, 27) == 11);
        check("C qty: a fire staff ignores the fire stock", W10Skills.heatQty(500, 500, 0, 500, 0, true, 400, 10_000_000, 400, 27) == 27);
        check("C qty: the fleet hour and half the purse over 1,000 gp (9,000 gp, 400 a cast -> 10)", W10Skills.heatQty(500, 500, 0, 500, 500, false, 6, 10_000_000, 400, 27) == 6 && W10Skills.heatQty(500, 500, 0, 500, 500, false, 400, 9_000, 400, 27) == 10);
        check("C qty: under HEAT_MIN casts -> 0", W10Skills.heatQty(4, 500, 0, 500, 500, false, 400, 10_000_000, 400, 27) == 0 && W10Skills.heatQty(500, 500, 0, 500, 500, false, 400, 3_000, 400, 27) == 0);

        // W (w24): the reasons behind capped / noPick; they agree with heatQty / heatRow and only add to the hourly line
        int[] vals = {0, 4, 5, 19, 20, 39, 40, 500}; boolean agree = true; int grid = 0;
        for (int o : vals) for (int cl : vals) for (int cp : new int[]{0, 4, 8}) for (int na : vals) for (int fi : vals) for (boolean st : new boolean[]{false, true}) for (int hr : new int[]{0, 4, 5, 400}) for (long co : new long[]{0, 1_000, 4_999, 5_000, 24_299, 24_300, 30_000}) for (long un : new long[]{0, 400, 2_330}) {
            grid++; if ((W10Skills.heatQty(o, cl, cp, na, fi, st, hr, co, un, 27) > 0) != (W10Skills.heatShort(o, cl, cp, na, fi, st, hr, co, un, 27) == -1)) agree = false; }
        check("W why: heatShort is -1 exactly when heatQty gives casts (" + grid + " cases)", agree);
        check("W why: each limit named (ore 0, coal 1, nature 2, fire 3, hour 4, purse 5)", W10Skills.heatShort(4, 500, 8, 500, 500, false, 400, 99_999, 400, 27) == 0 && W10Skills.heatShort(500, 39, 8, 500, 500, false, 400, 99_999, 400, 27) == 1
                && W10Skills.heatShort(500, 500, 8, 4, 500, false, 400, 99_999, 400, 27) == 2 && W10Skills.heatShort(500, 500, 8, 500, 19, false, 400, 99_999, 400, 27) == 3 && W10Skills.heatShort(500, 500, 8, 500, 19, true, 400, 99_999, 400, 27) == -1
                && W10Skills.heatShort(500, 500, 8, 500, 500, false, 4, 99_999, 400, 27) == 4 && W10Skills.heatShort(500, 500, 8, 500, 500, false, 400, 24_299, 2_330, 27) == 5);
        check("W why: the seeded purse in q29 - 30,000 gp at 2,330 a rune cast gives 6 casts, 24,299 gp gives none", W10Skills.heatQty(40, 192, 8, 186, 500, false, 400, 30_000, 2_330, 27) == 6 && W10Skills.heatQty(40, 192, 8, 186, 500, false, 400, 24_299, 2_330, 27) == 0);
        int[] oreQ = new int[W10Skills.HEAT_ROWS.length]; boolean[] glutQ = new boolean[oreQ.length]; long[] costQ = new long[oreQ.length]; int[] priceQ = new int[oreQ.length];
        for (int i = 0; i < oreQ.length; i++) { costQ[i] = 9_999; priceQ[i] = 1; } oreQ[6] = 50; costQ[6] = 2_330; priceQ[6] = 3_000; // q29: 50 rune ore, only the rune bar under the ceiling
        check("W why: the coal in q29 - 52 on the Exchange is under the 60 a rune pick needs (noPick coal 1), 60 picks rune", W10Skills.heatRow(43, 85, oreQ, 52, glutQ, costQ, priceQ) == -1 && W10Skills.heatRowShort(43, 85, oreQ, 52, glutQ, costQ, priceQ) == 1 && W10Skills.heatRow(43, 85, oreQ, 60, glutQ, costQ, priceQ) == 6 && W10Skills.heatRowShort(43, 85, oreQ, 60, glutQ, costQ, priceQ) == -1);
        glutQ[6] = true; boolean g9 = W10Skills.heatRowShort(43, 85, oreQ, 60, glutQ, costQ, priceQ) == 9; glutQ[6] = false; oreQ[6] = 24; boolean o0 = W10Skills.heatRowShort(43, 85, oreQ, 60, glutQ, costQ, priceQ) == 0; oreQ[6] = 50; costQ[6] = 3_451;
        check("W why: glut 9, ore 0, ceiling 10 (3,451 over 1.15 x 3,000), Smithing 14 -> 8, Magic 42 -> -1", g9 && o0 && W10Skills.heatRowShort(43, 85, oreQ, 60, glutQ, costQ, priceQ) == 10 && W10Skills.heatRowShort(99, 14, oreQ, 60, glutQ, costQ, priceQ) == 8 && W10Skills.heatRowShort(42, 85, oreQ, 60, glutQ, costQ, priceQ) == -1);
        check("W line: no why= before a failed try (the base line)", W10Skills.heatWhyText().isEmpty() && !W10Skills.line(1_000L).contains("why="));
        W10Skills.heatWhy(5); W10Skills.heatWhy(5); W10Skills.heatWhy(3); W10Skills.heatWhy(-1); W10Skills.heatWhy(99);
        String wl = W10Skills.line(1_000L);
        check("W line: why=fire:1,purse:2 inside superheat(...) with the flag on, absent with it off (" + wl + ")", W10Skills.heatWhyText().equals(" why=fire:1,purse:2") && wl.contains(" why=fire:1,purse:2)") == W10Skills.SUPERHEAT
                && (!W10Skills.SUPERHEAT || java.util.regex.Pattern.compile("^\\[W10\\] hour:.*\\bsuperheat\\(runs=(\\d+)").matcher(wl).find()));
        check("W hook: the three counted exits (noPick, capped before the buys, bought-but-none) each note a reason; no other new call", hk.split("W10Skills\\.heatWhy\\(", -1).length == 4 && hk.contains("W10Skills.heatNoPick++; W10Skills.heatWhy(") && hk.contains("W10Skills.heatCapped++; W10Skills.heatWhy(n <= 0 ?") && hk.contains("W10Skills.heatCapped++; W10Skills.heatWhy(W10Skills.WHY_BUY)"));

        System.out.println("[TestW10Superheat] DONE " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
