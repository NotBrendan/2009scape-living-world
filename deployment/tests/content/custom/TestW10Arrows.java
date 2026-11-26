package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * W10 lane L: rangers tip their own arrows (-Dbots.fletch.arrows, W10Skills / W10Hooks), plain java (no world, no cache):
 *   java -cp "<build>;server.jar" content.custom.TestW10Arrows <addons-src>/content/custom
 * Run it bare (flag off) and with -Dbots.fletch.arrows=true. The pure rules run in every case; the wiring is read from the
 * source (SmartAdventurers cannot class-init outside a world): the hook sits behind W10Skills.ARROWS in kitFromExchange, the
 * train-64 lines around it are unchanged, and the W10 code draws no RNG, uses no shop and mints nothing.
 */
public final class TestW10Arrows {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW10Arrows] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), mb = read(dir, "MarketBrain.java"), hk = read(dir, "W10Hooks.java"), sk = read(dir, "W10Skills.java");

        // F: the flag follows its property only; the hourly line names arrows only with it on
        check("F flag: ARROWS " + W10Skills.ARROWS + " = -Dbots.fletch.arrows (" + Boolean.getBoolean("bots.fletch.arrows") + ")", W10Skills.ARROWS == Boolean.getBoolean("bots.fletch.arrows"));
        check("F anyOn: true only with a W10 flag on", W10Skills.anyOn() == (W10Skills.ARROWS || W10Skills.SUPERHEAT));
        String line = W10Skills.line(1_000L);
        check("F [W10] line: arrows(...) only with the flag on (" + line + ")", line.startsWith("[W10] hour:") && line.contains(" arrows(runs=") == W10Skills.ARROWS);

        // 0: flag off = no-op (source text)
        int kit = sa.indexOf("void kitFromExchange() {"), above = sa.indexOf("if (W8Polish.KIT_ABOVE_RUNE && styleOf(bot) == MELEE) try { got += w8KitAboveRune(); }", kit);
        int hook = sa.indexOf("if (W10Skills.ARROWS && styleOf(bot) == RANGED) try { W10Hooks.arrows(this); } catch (Throwable t) {", kit);
        int gotLine = sa.indexOf("if (got > 0) { try { bot.getEquipment().refresh(); }", kit);
        check("0 hook: in kitFromExchange after the W8 kit line, before the got>0 chat line, behind W10Skills.ARROWS && RANGED, in try/catch Throwable",
                kit > 0 && above > kit && hook > above && gotLine > hook && gotLine - hook < 900);
        check("0 hook: exactly one arrows call site", sa.split("W10Hooks.arrows\\(", -1).length == 2);
        check("0 timer: the [W10] line is scheduled only behind W10Skills.anyOn()", mb.contains("if (W10Skills.anyOn()) TIMER.schedule(") && mb.split("W10Skills.line\\(", -1).length == 2);
        check("0 no RNG: W10Hooks and W10Skills draw none", !hk.contains("RNG.") && !hk.contains("Random") && !hk.contains("nextInt") && !sk.contains("RNG.") && !sk.contains("Random") && !sk.contains("nextInt"));
        check("0 no shop, no mint: W10Hooks never pays a shop or gives coins (inputs via Ledger.consume only)", !hk.contains("Wallet.pay(") && !hk.contains("Wallet.give(") && hk.contains("Ledger.consume(b.bot, id, n, why)"));
        check("0 kitFromExchange call sites unchanged (2, both at the Exchange)", sa.split("kitFromExchange\\(\\);", -1).length == 3);

        // A: the arrow table (2009 levels and XP)
        int[][] R = W10Skills.ARROW_ROWS;
        check("A table: bronze..rune arrows 882..892, tips 39..44, levels 1/15/30/45/60/75, XP 1.3..12.5",
                R.length == 6 && R[0][0] == 882 && R[5][0] == 892 && R[0][1] == 39 && R[5][1] == 44 && R[1][2] == 15 && R[3][2] == 45 && R[5][2] == 75 && R[0][3] == 13 && R[3][3] == 75 && R[5][3] == 125);
        check("A index: mithril arrow is row 3, an unknown arrow is -1", W10Skills.arrowIndex(888) == 3 && W10Skills.arrowIndex(11212) == -1);

        // B: where the headless arrows come from
        check("B source: a set in its bank first", W10Skills.headSource(15, 999, 999, 999) == W10Skills.SRC_OWN);
        check("B source: else bot stock with a set over the floor", W10Skills.headSource(14, 30, 999, 999) == W10Skills.SRC_STOCK && W10Skills.headSource(0, 29, 0, 0) == W10Skills.SRC_NONE);
        check("B source: else shafts AND feathers in stock", W10Skills.headSource(0, 0, 30, 30) == W10Skills.SRC_SHAFTS && W10Skills.headSource(0, 0, 30, 29) == W10Skills.SRC_NONE);
        check("B left: own = held, stock less the floor, shafts = min(shafts, feathers) less the floor",
                W10Skills.headLeft(W10Skills.SRC_OWN, 40, 0, 0, 0) == 40 && W10Skills.headLeft(W10Skills.SRC_STOCK, 0, 100, 0, 0) == 85 && W10Skills.headLeft(W10Skills.SRC_SHAFTS, 0, 0, 200, 60) == 45 && W10Skills.headLeft(W10Skills.SRC_NONE, 99, 99, 99, 99) == 0);
        check("B cost: own 0, stock its price, shafts shaft + feather", W10Skills.headCost(W10Skills.SRC_OWN, 9, 9, 9) == 0 && W10Skills.headCost(W10Skills.SRC_STOCK, 4, 1, 2) == 4 && W10Skills.headCost(W10Skills.SRC_SHAFTS, 4, 1, 2) == 3);

        // C: which arrow
        int[] stock = {500, 500, 500, 500, 500, 500}, tip = {1, 3, 6, 12, 30, 90}, arrow = {3, 6, 12, 25, 60, 150};
        check("C pick: Fletching 50, bow fires adamant -> mithril (adamant needs 60)", W10Skills.arrowRow(50, 890, stock, tip, arrow, 2) == 3);
        check("C pick: Fletching 99, bow fires mithril -> mithril (never above what it fires)", W10Skills.arrowRow(99, 888, stock, tip, arrow, 2) == 3);
        check("C pick: Fletching 1 -> bronze", W10Skills.arrowRow(1, 892, stock, tip, arrow, 2) == 0);
        int[] noMith = {500, 500, 500, 29, 500, 500};
        check("C pick: mithril tips under a set over the floor -> steel", W10Skills.arrowRow(50, 888, noMith, tip, arrow, 2) == 2);
        int[] dear = {1, 3, 6, 40, 30, 90};
        check("C pick: tip + headless over CEIL_X x the arrow price -> the tier below", W10Skills.arrowRow(50, 888, stock, dear, arrow, 2) == 2);
        check("C pick: an arrow not in the table -> -1; nothing in stock -> -1", W10Skills.arrowRow(99, 11212, stock, tip, arrow, 2) == -1 && W10Skills.arrowRow(99, 892, new int[6], tip, arrow, 0) == -1);

        // D: how many
        check("D qty: whole sets, capped by max", W10Skills.arrowQty(1000, 1000, 3000, 1_000_000, 10, 150) == 150 && W10Skills.arrowQty(1000, 1000, 3000, 1_000_000, 10, 100) == 90);
        check("D qty: by tips, headless and the fleet hour", W10Skills.arrowQty(44, 1000, 3000, 1_000_000, 10, 150) == 30 && W10Skills.arrowQty(1000, 31, 3000, 1_000_000, 10, 150) == 30 && W10Skills.arrowQty(1000, 1000, 20, 1_000_000, 10, 150) == 15);
        check("D qty: a third of the purse over 500 gp (2,000 gp, 10 gp an arrow -> 45)", W10Skills.arrowQty(1000, 1000, 3000, 2_000, 10, 150) == 45);
        check("D qty: under a set -> 0 (broke, hour spent, no tips)", W10Skills.arrowQty(1000, 1000, 3000, 600, 10, 150) == 0 && W10Skills.arrowQty(1000, 1000, 14, 1_000_000, 10, 150) == 0 && W10Skills.arrowQty(0, 1000, 3000, 1_000_000, 10, 150) == 0);
        check("D qty: own headless and free tips cost nothing (unit 0 -> not purse-bound)", W10Skills.arrowQty(60, 60, 3000, 0, 0, 150) == 60);

        // E: one try per bot per gap, the fleet hour, the log cap
        Map<String, Long> next = new HashMap<>();
        check("E gap: first try yes, again inside the gap no, after it yes", W10Skills.take(next, "a", 0, 1_000) && !W10Skills.take(next, "a", 999, 1_000) && W10Skills.take(next, "a", 1_000, 1_000) && W10Skills.take(next, "b", 999, 1_000) && !W10Skills.take(next, null, 0, 1));
        W10Skills.Hour h = new W10Skills.Hour(100);
        h.use(0, 60); int l1 = h.left(10); h.use(20, 60); int l2 = h.left(30); int l3 = h.left(3_600_000L);
        check("E hour: 100 cap, 60 used -> 40 left, 120 used -> 0, a new hour -> 100 (" + l1 + "/" + l2 + "/" + l3 + ")", l1 == 40 && l2 == 0 && l3 == 100);
        W10Skills.Lines ln = new W10Skills.Lines(); int ok = 0; for (int i = 0; i < W10Skills.LOG_CAP + 5; i++) if (ln.ok(0)) ok++;
        check("E lines: at most LOG_CAP lines a ten-minute window, then they open again", ok == W10Skills.LOG_CAP && ln.dropped == 5 && ln.ok(600_000L));

        System.out.println("[TestW10Arrows] DONE " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
