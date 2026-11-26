package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W10 lane M: darts and knives for low-level rangers (-Dbots.ranged.thrown, W10Skills / W10Hooks), plain java (no world, no cache):
 *   java -cp "<build>;server.jar" content.custom.TestW10Thrown <addons-src>/content/custom
 * Run it bare (flag off) and with -Dbots.ranged.thrown=true. The pure rules run in every case; the wiring is read from the source:
 * the decision runs first in kitFromExchange behind W10Skills.THROWN && RANGED, and each bow/arrow path it must leave alone has one
 * guard "W10Skills.THROWN && w10Thrown()" (false with the flag off before anything else is read); no RNG, bot stock only.
 */
public final class TestW10Thrown {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW10Thrown] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static int count(String s, String sub) { return s.split(java.util.regex.Pattern.quote(sub), -1).length - 1; }
    static String body(String s, String head, String next) { int a = s.indexOf(head), b = a < 0 ? -1 : s.indexOf(next, a + head.length()); return a < 0 || b < 0 ? "" : s.substring(a, b); }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), hk = read(dir, "W10Hooks.java"), sk = read(dir, "W10Skills.java");

        // F: the flag and the hourly line
        check("F flag: THROWN " + W10Skills.THROWN + " = -Dbots.ranged.thrown", W10Skills.THROWN == Boolean.getBoolean("bots.ranged.thrown"));
        check("F anyOn: includes THROWN", W10Skills.anyOn() == (W10Skills.ARROWS || W10Skills.SUPERHEAT || W10Skills.HERB_RUN || W10Skills.SPEC || W10Skills.THROWN));
        String line = W10Skills.line(1_000L);
        check("F [W10] line: thrown(...) only with the flag on (" + line + ")", line.contains(" thrown(trips=") == W10Skills.THROWN);
        check("F defaults: under Ranged 40, 150 a trip", W10Skills.THROWN_MAX == 40 && W10Skills.THROWN_TRIP == 150);

        // A: the table (item_configs: ids, Ranged requirement, ranged strength)
        int[][] T = W10Skills.THROWN_ROWS;
        boolean asc = true; for (int i = 1; i < T.length; i++) if (T[i][2] < T[i - 1][2]) asc = false;
        check("A table: 12 darts/knives bronze..adamant, weakest first", T.length == 12 && asc && T[0][0] == 806 && T[3][0] == 863 && T[11][0] == 867 && T[11][1] == 30 && T[11][2] == 14);
        check("A index", W10Skills.thrownIndex(864) == 2 && W10Skills.thrownIndex(882) == -1);
        check("A arrow cost: the missing arrows, plus a bow when it owns none", W10Skills.arrowCost(150, 50, 3, true, 400) == 300 && W10Skills.arrowCost(150, 50, 3, false, 400) == 700 && W10Skills.arrowCost(150, 200, 3, true, 400) == 0);

        // B: which one
        int L = T.length; int[] held = new int[L], stock = new int[L], price = new int[L];
        for (int i = 0; i < L; i++) { stock[i] = 1_000; price[i] = 2 + i; }   // bronze dart 2, iron dart 3, bronze knife 4, iron knife 5, ...
        long rich = 1_000_000;
        check("B Ranged 1, owns a bow, arrows 450: only the bronze dart (300) is cheaper", W10Skills.thrownRow(1, held, stock, price, 150, 450, rich, -1) == 0);
        check("B Ranged 1, no bow: arrows + bow 850 -> the iron knife (750)", W10Skills.thrownRow(1, held, stock, price, 150, 850, rich, -1) == 3);
        check("B equal cost is not cheaper (arrows stay)", W10Skills.thrownRow(1, held, stock, price, 150, 300, rich, -1) == -1);
        check("B Ranged 40: never", W10Skills.thrownRow(40, held, stock, price, 150, 1_000_000, rich, -1) == -1);
        check("B Ranged 20, arrows dear: the mithril knife", W10Skills.thrownRow(20, held, stock, price, 150, 1_000_000, rich, -1) == 9);
        int[] low = stock.clone(); low[3] = 159;
        check("B bot stock under need + floor: the next one down", W10Skills.thrownRow(1, held, low, price, 150, 850, rich, -1) == 2);
        check("B half the purse over 300 gp caps it", W10Skills.thrownRow(1, held, stock, price, 150, 850, 500, -1) == -1 && W10Skills.thrownRow(1, held, stock, price, 150, 850, 1_200, -1) == 1);
        int[] h2 = new int[L]; h2[3] = 150;
        check("B worn and fully held: kept even when arrows cost nothing", W10Skills.thrownRow(1, h2, stock, price, 150, 0, rich, 3) == 3);
        check("B held but not worn: arrows that cost nothing win", W10Skills.thrownRow(1, h2, stock, price, 150, 0, rich, -1) == -1);
        int[] h3 = new int[L]; h3[0] = 100;
        check("B units held count: 50 bronze darts to buy (100 gp) beat 120 gp of arrows", W10Skills.thrownRow(1, h3, new int[L], price, 150, 120, rich, -1) == -1
                && W10Skills.thrownRow(1, h3, stockOnly(L, 0, 60), price, 150, 120, rich, -1) == 0);

        // 0: flag off = no-op (source text)
        int kit = sa.indexOf("void kitFromExchange() {"), hook = sa.indexOf("if (W10Skills.THROWN && styleOf(bot) == RANGED) try { W10Hooks.thrown(this); } catch (Throwable t) {", kit);
        int needs = sa.indexOf("for (int[] pc : armourNeeds()) {", kit);
        check("0 hook: first in kitFromExchange (before armourNeeds/styleKit), behind THROWN && RANGED, in try/catch", kit > 0 && hook > kit && needs > hook && hook - kit < 80);
        check("0 hook: one call site", count(sa, "W10Hooks.thrown(") == 1);
        String G = "W10Skills.THROWN && w10Thrown()";
        check("0 guards: exactly five, all of the form THROWN && w10Thrown()", count(sa, G) == 5 && count(sa, "w10Thrown()") == 6);
        check("0 guard styleKit: the bow slot only", body(sa, "List<int[]> styleKit(int st) {", "return out;").contains("if (k[0] == 3 && st == RANGED && " + G + ") continue;"));
        check("0 guard supplyNeeds: first after st", sa.contains("int st = styleOf(bot);\r\n            if (st == RANGED && " + G + ") return out;") || sa.contains("int st = styleOf(bot);\n            if (st == RANGED && " + G + ") return out;"));
        check("0 guard w3Restock / styleWeaponBack / styleFallback", body(sa, "boolean w3Restock() {", "if (st == MAGIC) {").contains("if (st == RANGED && " + G + ") return false;")
                && body(sa, "void styleWeaponBack() {", "int want = -1").contains("if (st == RANGED && " + G + ") return;")
                && body(sa, "void styleFallback() {", "if (why == null) return;").contains("if (st == RANGED && " + G + ") return;"));
        check("0 w10Thrown: a thrown weapon worn, RANGED, under the cap", sa.contains("boolean w10Thrown() { Item w = bot.getEquipment().get(3); return w != null && W10Skills.thrownIndex(w.getId()) >= 0 && styleOf(bot) == RANGED && bot.getSkills().getStaticLevel(Skills.RANGE) < W10Skills.THROWN_MAX; }"));
        int hs = hk.indexOf("// ------------------------------------------------------------------ bots.ranged.thrown");
        String th = hs > 0 ? hk.substring(hs) : "";
        check("0 buy: bot stock only (buy -> Ledger.consume), no shop, no coins given", th.contains("got = buy(b, id, need, \"ammo\");") && !th.contains("Wallet.pay(") && !th.contains("Wallet.give(") && !th.contains("Ledger.supply("));
        check("0 the bow goes to the bank (stowWeapon), never the Exchange", th.contains("b.stowWeapon()") && !th.contains("SmartAdventurers.wear("));
        check("0 no RNG: W10Hooks and W10Skills draw none", !hk.contains("RNG.") && !hk.contains("nextInt") && !sk.contains("RNG.") && !sk.contains("nextInt") && !sk.contains("Random"));

        System.out.println("[TestW10Thrown] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static int[] stockOnly(int n, int i, int v) { int[] a = new int[n]; a[i] = v; return a; }
}
