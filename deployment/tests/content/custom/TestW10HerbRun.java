package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * W10 lane M: the multi-site herb run (-Dbots.farming.herbRun, W10Skills + SmartAdventurers.Brain), plain java (no world, no cache):
 *   java -cp "<build>;server.jar" content.custom.TestW10HerbRun <addons-src>/content/custom
 * Run it bare (flag off) and with -Dbots.farming=true -Dbots.farming.herbRun=true. The pure rules run in every case; the wiring is
 * read from the source (SmartAdventurers cannot class-init outside a world): every hook sits behind W10Skills.HERB_RUN or behind
 * w10Leg (which only the flag-on code moves off -1), FarmSave is not touched, and the herb-run code draws no RNG.
 */
public final class TestW10HerbRun {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW10HerbRun] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static int count(String s, String sub) { return s.split(java.util.regex.Pattern.quote(sub), -1).length - 1; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), fm = read(dir, "Farming.java"), fs = read(dir, "FarmSave.java");

        // F: the flag needs bots.farming too; the hourly line names herbRun only with it on
        boolean want = Boolean.getBoolean("bots.farming") && Boolean.getBoolean("bots.farming.herbRun");
        check("F flag: HERB_RUN " + W10Skills.HERB_RUN + " = -Dbots.farming && -Dbots.farming.herbRun (" + want + ")", W10Skills.HERB_RUN == want);
        check("F anyOn: includes HERB_RUN", W10Skills.anyOn() == (W10Skills.ARROWS || W10Skills.SUPERHEAT || W10Skills.HERB_RUN));
        String line = W10Skills.line(1_000L);
        check("F [W10] line: herbRun(...) only with the flag on (" + line + ")", line.startsWith("[W10] hour:") && line.contains(" herbRun(runs=") == W10Skills.HERB_RUN);
        check("F defaults: 3 legs at most, 200 tiles a hop", W10Skills.MAX_LEGS == 3 && W10Skills.LEG_R == 200);

        // A: which other sites a run can add (Farming.SITES anchors: Falador, Catherby, Ardougne)
        check("A anchors: the test's tiles are Farming.SITES' (Falador 3056,3307; Catherby 2809,3463; Ardougne 2666,3374)",
                fm.contains("new Site(\"FALADOR\", Location.create(3056, 3307, 0)") && fm.contains("new Site(\"CATHERBY\", Location.create(2809, 3463, 0)") && fm.contains("new Site(\"ARDOUGNE\", Location.create(2666, 3374, 0)"));
        int[][] at = {{3056, 3307, 0}, {2809, 3463, 0}, {2666, 3374, 0}};
        check("A Falador: no partner on its side of White Wolf Mountain", W10Skills.reach(0, at, 3, 200).length == 0);
        check("A Catherby -> Ardougne (~168 tiles)", Arrays.equals(W10Skills.reach(1, at, 3, 200), new int[]{2}));
        check("A Ardougne -> Catherby", Arrays.equals(W10Skills.reach(2, at, 3, 200), new int[]{1}));
        check("A a 150-tile hop cap drops Ardougne", W10Skills.reach(1, at, 3, 150).length == 0);
        check("A never across x 2880 even when close", W10Skills.reach(0, new int[][]{{2870, 3400, 0}, {2890, 3400, 0}}, 3, 200).length == 0);
        check("A never another plane", W10Skills.reach(0, new int[][]{{2700, 3400, 0}, {2710, 3400, 1}}, 3, 200).length == 0);
        int[][] line4 = {{2500, 3000, 0}, {2700, 3000, 0}, {2600, 3000, 0}, {2800, 3000, 0}, {2550, 3000, 0}};
        check("A chain: nearest from the last stop, at most max (" + Arrays.toString(W10Skills.reach(0, line4, 3, 120)) + ")", Arrays.equals(W10Skills.reach(0, line4, 3, 120), new int[]{4, 2, 1}));
        check("A chain: max 1", Arrays.equals(W10Skills.reach(0, line4, 1, 120), new int[]{4}));
        check("A bad input: no legs", W10Skills.reach(-1, at, 3, 200).length == 0 && W10Skills.reach(5, at, 3, 200).length == 0 && W10Skills.reach(1, null, 3, 200).length == 0 && W10Skills.reach(1, at, 0, 200).length == 0);

        // B: which legs this run walks
        check("B no seeds, no crops: none", W10Skills.visit(new int[]{1, 2}, new boolean[]{false, false}, 0).length == 0);
        check("B crops are always harvested", Arrays.equals(W10Skills.visit(new int[]{1, 2}, new boolean[]{false, true}, 0), new int[]{2}));
        check("B empty legs while seeds last, a crop leg replants first", Arrays.equals(W10Skills.visit(new int[]{1, 2, 3}, new boolean[]{true, false, false}, 2), new int[]{1, 2})
                && Arrays.equals(W10Skills.visit(new int[]{1, 2, 3}, new boolean[]{false, true, false}, 1), new int[]{1, 2}));
        check("B all seeded", Arrays.equals(W10Skills.visit(new int[]{1, 2}, new boolean[]{false, false}, 5), new int[]{1, 2}));
        check("B null and empty", W10Skills.visit(null, null, 3).length == 0 && W10Skills.visit(new int[0], new boolean[0], 3).length == 0);
        check("B names", "catherby + ardougne".equals(W10Skills.legNames(new String[]{"FALADOR", "CATHERBY", "ARDOUGNE"}, 1, new int[]{2})));

        // 0: flag off = no-op (source text)
        int pf = sa.indexOf("boolean planFarmRun(long now) {"), pfEnd = sa.indexOf("private int farmFlowerIn(", pf);
        String plan = sa.substring(pf, pfEnd);
        check("0 plan: candidates only behind HERB_RUN, seeds 0 unless HERB_RUN", plan.contains("final int[] w10Cand = W10Skills.HERB_RUN ? w10Reach(site) : W10Skills.NO_LEGS; int w10Seeds = 0;")
                && plan.contains("if (W10Skills.HERB_RUN && hSeed > 0) w10Seeds = w10SeedLegs(hSeed, q, left[0], w10Cand.length);") && count(plan, "w10Seeds = ") == 2); // the 0 declaration and the one flag-on set
        check("0 plan: the seed fetch is q + w10Seeds (q with the flag off), compost counts nH + w10Seeds",
                plan.contains("if (hSeed > 0 && !farmGet(hSeed, q + w10Seeds, false, left, took)) hSeed = -1;") && plan.contains("farmPrepV2(pts, lvl, aSeed, hSeed, nA, nH + w10Seeds, nF, check, left, took)") && plan.contains("(hSeed > 0 ? nH + w10Seeds : 0)"));
        int st = plan.indexOf("if (W10Skills.HERB_RUN) try { w10Start(");
        check("0 plan: w10Start behind HERB_RUN in try/catch, after the [Farm] start line, before return true",
                st > plan.indexOf("System.out.println(\"[Farm] \" + bot.getUsername() + \" -> \"") && plan.indexOf("return true;", st) > st && plan.substring(st, plan.indexOf('\n', st)).contains("catch (Throwable t)"));
        check("0 work: a leg's patch list only when w10Leg >= 0", sa.contains("List<core.game.node.scenery.Scenery> pts = w10Leg >= 0 ? w10HerbPts() : Farming.find(farmSite);"));
        check("0 finish: the next leg only behind HERB_RUN in try/catch; FARM_BACK to w10At() (farmSite while w10Leg is -1)",
                sa.contains("if (W10Skills.HERB_RUN) try { if (w10NextLeg(now)) return; } catch (Throwable t) {") && sa.contains("farm = FARM_BACK; farmMove.reset(Farming.SITES[w10At()].bank, 4);")
                && sa.contains("private int w10At() { return w10Leg >= 0 && w10Leg < w10Legs.length ? w10Legs[w10Leg] : farmSite; }"));
        check("0 due: the other sites' crops only behind HERB_RUN", sa.contains("if (W10Skills.HERB_RUN) for (int leg : w10Reach(farmSite)) {"));
        check("0 end: legs reset behind HERB_RUN in endFarm, before the RNG retry draw", sa.indexOf("w10Legs = W10Skills.NO_LEGS; w10Leg = -1; } // W10 lane M") > 0
                && sa.indexOf("farm = 0; farmAct = 0; farmNext = now + (gaveUp && farmDue == 0") > sa.indexOf("w10Legs = W10Skills.NO_LEGS; w10Leg = -1; } // W10 lane M"));
        // w10Leg moves off -1 only in w10NextLeg (reached only behind HERB_RUN)
        int moves = count(sa, "w10Leg++");
        check("0 w10Leg: one increment (w10NextLeg), set to -1 elsewhere only", moves == 1 && sa.indexOf("w10Leg++") > sa.indexOf("private boolean w10NextLeg(long now) {"));
        int hs = sa.indexOf("// ---- W10 lane M (-Dbots.farming.herbRun"), he = sa.indexOf("/** farm49: this run's crops out of the pack", hs);
        String helpers = hs > 0 && he > hs ? sa.substring(hs, he) : "";
        check("0 no RNG, no shop, no mint in the herb-run helpers", !helpers.isEmpty() && !helpers.contains("RNG.") && !helpers.contains("nextInt") && !helpers.contains("Wallet.pay(") && !helpers.contains("Wallet.give(") && !helpers.contains("Ledger.consume("));
        check("0 FarmSave untouched: no herb-run key (legs come back from the crop rows)", !fs.contains("W10") && !fs.contains("herbRun"));
        check("0 call sites: w10Start 1, w10NextLeg 1, w10SeedLegs 1", count(sa, "w10Start(site,") == 1 && count(sa, "w10NextLeg(now)") == 1 && count(sa, "w10SeedLegs(hSeed,") == 1);

        System.out.println("[TestW10HerbRun] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
