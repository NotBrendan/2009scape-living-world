package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W5 skills wave 2 lane B (the design notes section 2): crafter2, stalls, bone market, stance plan,
 * build bias, defShare. Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW5bSkills <addons-src>/content/custom
 * Run it twice: bare (every flag off) and with all six -D flags set. Per fix: the pure rule (W5bSkills), and the wiring read
 * from the source (SmartAdventurers, Artisans and AfkTasks cannot class-init outside a world), including that the flag-off
 * path still holds train-60's code. The in-world checks are in the design notes.
 */
public final class TestW5bSkills {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW5bSkills] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW5bSkills]   missing: " + p); return false; } return true; }
    static boolean order(String src, String first, String second) { int a = src.indexOf(first), b = src.indexOf(second, Math.max(0, a)); return a >= 0 && b > a; }

    /** boneMarket with the fallback ceilings (no reference price). */
    static int[] bm(boolean on, int prayer, long coins, int sD, int sB, int sN, int vD, int vB, int vN, int hl) {
        return W5bSkills.boneMarket(on, prayer, coins, sD, sB, sN, vD, vB, vN, W5bSkills.CEIL_DRAGON, W5bSkills.CEIL_BIG, W5bSkills.CEIL_NORM, hl);
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), ar = read(dir, "Artisans.java"), at = read(dir, "AfkTasks.java"), mb = read(dir, "MarketBrain.java"), rec = read(dir, "artisan-recipes.json");

        // F: the six statics follow their properties, default off
        String[] keys = {"artisans.crafter2", "bots.thieve.stalls", "bots.prayerRun.market", "bots.stancePlan", "bots.buildBias", "bots.style.defShare"};
        boolean[] st = {W5bSkills.CRAFTER2, W5bSkills.STALLS, W5bSkills.PRAYER_MARKET, W5bSkills.STANCE_PLAN, W5bSkills.BUILD_BIAS, W5bSkills.DEF_SHARE};
        boolean follow = true; int on = 0; for (int i = 0; i < keys.length; i++) { if (st[i] != Boolean.getBoolean(keys[i])) follow = false; if (st[i]) on++; }
        check("F flags: 6 statics follow their -D properties (" + on + " on), anyOn " + W5bSkills.anyOn() + ", line names them: " + W5bSkills.flags(),
                follow && W5bSkills.anyOn() == (on > 0) && W5bSkills.flags().split(" ").length == (on == 0 ? 1 : on) && W5bSkills.line().startsWith("[W5b] on=["));

        // ---------------------------------------------------------------- Crafting: crafter2
        Integer[] crew = {1, 2, 3};
        boolean crewOk = W5bSkills.crew(false, crew, 9) == crew && W5bSkills.crew(true, crew, 9).length == 4 && W5bSkills.crew(true, crew, 9)[3] == 9
                && W5bSkills.branchFor(false, 0) == -1 && W5bSkills.branchFor(false, 1) == -1 && W5bSkills.branchFor(true, 0) == 0 && W5bSkills.branchFor(true, 1) == 1 && W5bSkills.branchFor(true, 2) == 0;
        boolean br = W5bSkills.craftBranch(new int[]{1739}) == 0 && W5bSkills.craftBranch(new int[]{1753}) == 0 && W5bSkills.craftBranch(new int[]{2505}) == 0 && W5bSkills.craftBranch(new int[]{1779}) == 0
                && W5bSkills.craftBranch(new int[]{1623}) == 1 && W5bSkills.craftBranch(new int[]{1607, 2357}) == 1 && W5bSkills.craftBranch(new int[]{1781, 1783}) == 1 && W5bSkills.craftBranch(new int[]{2355}) == 1
                && !W5bSkills.branchSkips(-1, "CRAFTING", new int[]{1739}) && W5bSkills.branchSkips(1, "CRAFTING", new int[]{1739}) && !W5bSkills.branchSkips(0, "CRAFTING", new int[]{1739})
                && !W5bSkills.branchSkips(1, "SMITHING", new int[]{1739});
        boolean kp = W5bSkills.keep(false, "CRAFTING", 1741, 50, 10) == 50 && W5bSkills.keep(false, "CRAFTING", 1623, 50, 10) == 50
                && W5bSkills.keep(true, "CRAFTING", 1741, 50, 10) == 0 && W5bSkills.keep(true, "CRAFTING", 1607, 50, 10) == 0 && W5bSkills.keep(true, "crafting", 1775, 50, 10) == 0
                && W5bSkills.keep(true, "CRAFTING", 1623, 50, 10) == 10 && W5bSkills.keep(true, "CRAFTING", 1753, 50, 10) == 10
                && W5bSkills.keep(true, "CRAFTING", 1739, 50, 10) == 50 && W5bSkills.keep(true, "CRAFTING", 2357, 50, 10) == 50   // cowhide, gold bar: the old reserve
                && W5bSkills.keep(true, "FLETCHING", 1741, 50, 10) == 50 && W5bSkills.keep(true, "HERBLORE", 1623, 0, 10) == 0;
        // replay of choose() for "Sapphire" (uncut sapphire -> sapphire) with 20 uncut sapphires on the Exchange: 0 batches -> 10
        int tOld = Math.min(28, (20 - 50) / 1), tNew = Math.min(28, (20 - W5bSkills.keep(true, "CRAFTING", 1623, 50, 10)) / 1);
        check("C crafter2 rule: crew + branch per crafter " + crewOk + ", branch by input " + br + ", reserve 0 half-made / 10 raw / 50 else " + kp + "; replay 20 uncut sapphires: batches " + tOld + " -> " + tNew,
                crewOk && br && kp && tOld <= 0 && tNew == 10);

        // recipe file: every crafting input is a staple, made by a recipe, or listed here with its bot source (else listed as unsourced)
        Set<Integer> products = new HashSet<>(), craftIn = new TreeSet<>(), craftProd = new HashSet<>();
        Matcher r = Pattern.compile("\\{\"trade\":\"(\\w+)\",\"product\":(\\d+)[^\\[]*\"inputs\":\\[(.*?)\\]\\}").matcher(rec);
        while (r.find()) {
            products.add(Integer.parseInt(r.group(2)));
            if (!r.group(1).equalsIgnoreCase("crafting")) continue;
            craftProd.add(Integer.parseInt(r.group(2)));
            Matcher i = Pattern.compile("\"id\":(\\d+)").matcher(r.group(3)); while (i.find()) craftIn.add(Integer.parseInt(i.group(1)));
        }
        // sourced raw inputs, with the file that holds the source: cowhide (cow hunts), flax (pick task), uncut gems (gem stall,
        // travelling traders, drops), dragonhide and snakeskin (hunt drops the market buys), ball of wool (spinning)
        int[][] sourced = {{1739, 0}, {1779, 0}, {1759, 0}, {1623, 0}, {1617, 1}, {1619, 1}, {1621, 1}, {1631, 1}, {1747, 2}, {1749, 2}, {1751, 2}, {1753, 2}, {6287, 2}};
        String[] srcFile = {at, read(dir, "TravellingTraders.java"), mb};
        int[] unsourced = {1625, 1627, 1629, 6571, 7801, 1781, 1783}; // opal/jade/red topaz/onyx uncut, snake hide, soda ash, bucket of sand: no bot gathers them
        int bad = 0, viaSrc = 0, half = 0, halfMissing = 0; StringBuilder un = new StringBuilder();
        for (int id : craftIn) {
            boolean ok = false;
            if (products.contains(id)) { ok = true; if (craftProd.contains(id)) { half++; if (!W5bSkills.has(W5bSkills.CRAFT_HALF, id)) halfMissing++; } }
            for (int[] s : sourced) if (s[0] == id && srcFile[s[1]].matches("(?s).*\\b" + id + "\\b.*")) { ok = true; viaSrc++; }
            for (int u : unsourced) if (u == id) { ok = true; un.append(id).append(' '); }
            if (!ok) { bad++; System.out.println("[TestW5bSkills]   crafting input " + id + " has no source and is not listed"); }
        }
        int halfExtra = 0; for (int id : W5bSkills.CRAFT_HALF) if (!craftProd.contains(id) || !craftIn.contains(id)) halfExtra++;
        int rawBad = 0; for (int id : W5bSkills.CRAFT_RAW) if (!craftIn.contains(id) || products.contains(id)) rawBad++;
        check("C crafter2 recipe file: " + craftIn.size() + " crafting inputs, " + viaSrc + " with a bot source, " + half + " half-made (all in CRAFT_HALF: " + (halfMissing == 0) + ", extras " + halfExtra
                        + "), raw set clean " + (rawBad == 0) + ", unsourced (idle): " + un.toString().trim() + ", unlisted " + bad,
                craftIn.size() >= 40 && bad == 0 && halfMissing == 0 && halfExtra == 0 && rawBad == 0 && viaSrc == sourced.length);
        check("C crafter2 wiring: CREW kept, CREW_RUN appends one crafter behind the flag, branch per spawn, choose(trade, lvl) = branch -1, reserve lines after W3's, idle line",
                all(ar, "Trade.HERBLORE, Trade.CRAFTING, Trade.RUNECRAFTING, Trade.COOKING, Trade.FLETCHING}; // audit E4",
                        "static final Trade[] CREW_RUN = W5bSkills.crew(W5bSkills.CRAFTER2, CREW, Trade.CRAFTING);", "for (Trade trade : CREW_RUN) {",
                        "W5bSkills.branchFor(W5bSkills.CRAFTER2, crafters++) : W5bSkills.BRANCH_ALL;", "Artisan script = new Artisan(trade, home); script.branch = branch;",
                        "static synchronized Plan choose(Trade trade, int skillLevel) { return choose(trade, skillLevel, W5bSkills.BRANCH_ALL); }",
                        "if (W5bSkills.branchSkips(branch, r.trade, r.inIds)) continue;", "f.complete(choose(trade, lvl, branch));",
                        "int avail = stock.getOrDefault(r.inIds[k], 0) - W3Skills.keep(r.trade, r.inIds[k], KEEP_INPUT);",
                        "if (W5bSkills.CRAFTER2) avail += KEEP_INPUT - W5bSkills.keep(r.trade, r.inIds[k], KEEP_INPUT);",
                        "int keepK = W3Skills.keep(plan.r.trade, plan.r.inIds[k], KEEP_INPUT);", "if (W5bSkills.CRAFTER2) keepK = W5bSkills.keep(plan.r.trade, plan.r.inIds[k], keepK);",
                        "int branch = W5bSkills.BRANCH_ALL;", "if (best == null && W5bSkills.CRAFTER2 && \"crafting\".equalsIgnoreCase(trade.name())")
                        && order(ar, "int avail = stock.getOrDefault(r.inIds[k], 0) - W3Skills.keep(", "if (W5bSkills.CRAFTER2) avail +=")
                        && order(ar, "int keepK = W3Skills.keep(", "if (W5bSkills.CRAFTER2) keepK ="));
        // crafter2 purse: a transfer from the first crafter, never a mint; total coins before == after for every split
        boolean conserve = true, floorOk = true;
        long[][] purses = {{0, 0}, {10_000, 0}, {20_000, 0}, {30_000, 0}, {39_999, 1}, {40_000, 0}, {60_000, 0}, {0, 60_000}, {25_000, 35_000}, {1_234_567, 89}, {5, 50_000}};
        for (long[] pu : purses) {
            long[] mv = W5bSkills.purseMove(pu[0], pu[1], 20_000);
            long before = pu[0] + pu[1] + 0, after = (pu[0] - mv[0]) + (pu[1] - mv[1]) + mv[2];
            if (before != after || mv[0] < 0 || mv[1] < 0 || mv[0] > pu[0] || mv[1] > pu[1] || mv[2] != mv[0] + mv[1]) conserve = false;
            long total = pu[0] + pu[1], left = total - mv[2];
            if (total >= 40_000 ? mv[2] != total / 2 : (total > 20_000 ? left != 20_000 : mv[2] != 0)) floorOk = false;
        }
        long[] m60 = W5bSkills.purseMove(60_000, 0, 20_000), mSplit = W5bSkills.purseMove(25_000, 35_000, 20_000);
        boolean shape = m60[2] == 30_000 && mSplit[0] == 25_000 && mSplit[1] == 5_000 && W5bSkills.purseMove(30_000, 0, 20_000)[2] == 10_000 && W5bSkills.purseMove(15_000, 0, 20_000)[2] == 0;
        check("C crafter2 purse: total coins before == after for " + purses.length + " splits " + conserve + ", half / 20k floor " + floorOk + ", bank first then pack " + shape + ", floor " + W5bSkills.CRAFT2_FLOOR,
                conserve && floorOk && shape);
        check("C crafter2 purse wiring: the 2nd crafter's first boot calls fundCrafter2 instead of the float; coins move bank-to-bank, no Wallet.give in it",
                all(ar, "else if (branch == W5bSkills.BRANCH_GEMS) fundCrafter2(bot);", "else Ledger.Wallet.give(bot, 40_000 + RNG.nextInt(40_000)); // a new craftsman's float",
                        "long[] mv = W5bSkills.purseMove(bankA, invA, W5bSkills.CRAFT2_FLOOR);", "(before == after ? \"\" : \" MISMATCH\")")
                        && order(ar, "else if (branch == W5bSkills.BRANCH_GEMS) fundCrafter2(bot);", "else Ledger.Wallet.give(bot, 40_000")
                        && !ar.substring(ar.indexOf("static void fundCrafter2("), ar.indexOf("static void restoreArtisan(")).contains("Wallet.give")
                        && !ar.substring(ar.indexOf("static void fundCrafter2("), ar.indexOf("static void restoreArtisan(")).contains("Wallet.pay"));
        long t0 = 50_000_000L;
        check("C crafter2 idle line: once per 30 min per branch", W5bSkills.craftIdleDue(0, t0) && !W5bSkills.craftIdleDue(0, t0 + 60_000) && W5bSkills.craftIdleDue(1, t0 + 60_000) && W5bSkills.craftIdleDue(0, t0 + 30 * 60_000L));

        // ---------------------------------------------------------------- Thieving: stalls
        boolean sp = W5bSkills.stallProducts(false, "stall_baker") == null && W5bSkills.stallProducts(false, "stall_silk") == null
                && java.util.Arrays.equals(W5bSkills.stallProducts(true, "stall_baker"), new int[]{1891, 2309, 1901}) && java.util.Arrays.equals(W5bSkills.stallProducts(true, "stall_silk"), new int[]{950})
                && W5bSkills.stallProducts(true, "stall_gem") == null && W5bSkills.stallProducts(true, "stall_fur") == null && W5bSkills.stallProducts(true, "stall_silver") == null
                && W5bSkills.stallProducts(true, "stall_seed") == null && W5bSkills.stallProducts(true, null) == null;
        boolean seats = W5bSkills.stallSeat(0, 3) && W5bSkills.stallSeat(2, 3) && !W5bSkills.stallSeat(3, 3) && !W5bSkills.stallSeat(3, 9) && W5bSkills.STALL_SEATS <= 3 && W5bSkills.STALL_SEATS >= 1;
        boolean lootMatch = all(at, "STALL_LOOT.put(Task.ST_BAKER, ids(1891, 2309, 1901));", "STALL_LOOT.put(Task.ST_SILK, ids(950));", "{Task.ST_BAKER, Skills.THIEVING, 5}, {Task.ST_SILK, Skills.THIEVING, 20}");
        check("T stalls rule: products only baker/silk and only with the flag " + sp + ", 3 seats at most " + seats + ", loot = the engine's STALL_LOOT and levels 5/20 " + lootMatch + ", min combat " + W5bSkills.STALL_MIN_COMBAT,
                sp && seats && lootMatch && W5bSkills.STALL_MIN_COMBAT >= 20 || sp && seats && lootMatch && Integer.getInteger("bots.thieve.stalls.minCombat") != null);
        check("T stalls wiring: Task gets products only for STALL with null products; JOBS level from NEEDS; seats + combat gate before the crowd cap; food bought like THIEVE; caught rule unchanged",
                all(at, "this.products = products == null && kind == Kind.STALL ? W5bSkills.stallProducts(W5bSkills.STALLS, key) : products;",
                        "if (task.kind == Kind.STALL && stallCaught()) return;", "if (!p.isArtificial()) productsOverride = playerProducts(task);",
                        "if (task.kind != Kind.FIGHT && task.kind != Kind.THIEVE && task.kind != Kind.STALL && hitting.isEmpty()) return false;")
                        && all(sa, "case STALL: { int[] nd = AfkTasks.NEEDS.get(task); skill = Skills.THIEVING; level = nd != null ? nd[1] : 99; break; }",
                        "if (task.kind == AfkTasks.Kind.SMELT || task.products == null) continue;",
                        "if (W5bSkills.STALLS && j.hunt == null && j.task.kind == AfkTasks.Kind.STALL) {", "if (!W5bSkills.stallSeat(stallCrowd(), W5bSkills.STALL_SEATS)) { W5bSkills.stallFull++; continue; }",
                        "if (combat < W5bSkills.STALL_MIN_COMBAT) { W5bSkills.stallLowCombat++; continue; }",
                        "if (W5bSkills.STALLS && stallBlocked(j, combat)) continue;", "if (W5bSkills.STALLS && stallBlocked(j, combat)) return false;",
                        "|| W5bSkills.STALLS && job.task.kind == AfkTasks.Kind.STALL) && food < 8;", "|| W5bSkills.STALLS && job.task.kind == AfkTasks.Kind.STALL) { buyFood(job.hunt != null ? job.hunt.npcLevel : 20); packFood(); }")
                        && order(sa, "if (W5bSkills.STALLS && j.hunt == null && j.task.kind == AfkTasks.Kind.STALL) {", "if (busy + party > ("));

        // ---------------------------------------------------------------- Prayer: bone market (s59: 0 bought, 47 noBonesAwayFromGE)
        int hl = 300;
        boolean bmOff = bm(false, 30, 1_000_000, 500, 500, 500, 2_000, 300, 100, hl) == null;
        int[] n1 = bm(true, 30, 50_000, 0, 0, 200, 0, 0, 100, hl);          // normal bones at 100 gp, 50k purse: 50 affordable -> 27
        int[] n2 = bm(true, 30, 300_000, 200, 200, 200, 2_000, 300, 100, hl); // rich: dragon bones
        int[] n3 = bm(true, 30, 150_000, 200, 200, 200, 2_000, 300, 100, hl); // under the dragon purse: big bones
        boolean bm = n1 != null && n1[0] == 526 && n1[1] == 27 && n1[2] == 1 && n2 != null && n2[0] == 536 && n2[1] == 15 && n3 != null && n3[0] == 532 && n3[1] == 27;
        boolean ceil = bm(true, 30, 1_000_000, 0, 0, 500, 0, 0, W5bSkills.CEIL_NORM + 1, hl) == null
                && bm(true, 30, 1_000_000, 500, 0, 0, W5bSkills.CEIL_DRAGON + 1, 0, 0, hl) == null;
        boolean reserve = bm(true, 30, 1_000_000, 0, 0, 44, 0, 0, 100, hl) == null && bm(true, 30, 1_000_000, 0, 0, 45, 0, 0, 100, hl)[1] == 5;
        boolean caps = bm(true, 70, 1_000_000, 0, 0, 500, 0, 0, 100, hl) == null && bm(true, 30, 1_000_000, 0, 0, 500, 0, 0, 100, 0) == null
                && bm(true, 30, 1_000_000, 0, 0, 500, 0, 0, 100, 6)[1] == 6 && bm(true, 30, 4_000, 0, 0, 500, 0, 0, 100, hl) == null; // 10% of 4k = 4 bones < 5
        check("P bone market: off null " + bmOff + ", picks dragon (200k+) / big / normal " + bm + ", price ceiling " + ceil + ", 40 left for players " + reserve + ", prayer 70 / fleet cap / 10% purse " + caps,
                bmOff && bm && ceil && reserve && caps);
        // ceilings from the reference price (PriceDrift base) x 1.5, fixed numbers only without a reference
        boolean rc = W5bSkills.boneCeil(100, 1.5, 150) == 150 && W5bSkills.boneCeil(80, 1.5, 150) == 120 && W5bSkills.boneCeil(2_400, 1.5, 3_000) == 3_600
                && W5bSkills.boneCeil(null, 1.5, 150) == 150 && W5bSkills.boneCeil(0, 1.5, 500) == 500 && W5bSkills.boneCeil(1, 1.5, 150) == 2 && W5bSkills.CEIL_X >= 1.0;
        int cN = W5bSkills.boneCeil(80, 1.5, 150); // reference 80: ceiling 120, so a market price of 130 is refused although under the 150 fallback
        boolean rcUse = W5bSkills.boneMarket(true, 30, 1_000_000, 0, 0, 500, 0, 0, 130, 0, 0, cN, hl) == null && W5bSkills.boneMarket(true, 30, 1_000_000, 0, 0, 500, 0, 0, 120, 0, 0, cN, hl) != null
                && W5bSkills.boneMarket(true, 30, 1_000_000, 500, 0, 0, 3_500, 0, 0, W5bSkills.boneCeil(2_400, 1.5, 3_000), 0, 0, hl) != null; // dragon bones ref 2,400: 3,500 allowed (fallback would refuse)
        check("P bone ceiling: 1.5 x reference, fallback only without one " + rc + "; used by the pick " + rcUse, rc && rcUse);
        check("P bone market wiring: asked only after boneChoice said no, behind the flag, before the skip; buys through the same Ledger path; fleet cap kept",
                all(sa, "if (c == null && W5bSkills.PRAYER_MARKET) {", "W5bSkills.boneCeil(boneRef(dr), W5bSkills.CEIL_X, W5bSkills.CEIL_DRAGON)", "W5bSkills.boneCeil(boneRef(norm), W5bSkills.CEIL_X, W5bSkills.CEIL_NORM)",
                        "static Integer boneRef(int id) { try { return PriceDrift.LOADED ? PriceDrift.BASE.get(id) : null; }", "c = W5bSkills.boneMarket(true, bot.getSkills().getStaticLevel(Skills.PRAYER), coins,", "PrayerRun.hourLeft(now));",
                        "int got = Ledger.consume(bot, id, n, \"prayer\"); // pays sellers at guide, no mint", "PrayerRun.bought(now, got);")
                        && order(sa, "int[] c = PrayerRun.boneChoice(", "if (c == null && W5bSkills.PRAYER_MARKET) {")
                        && order(sa, "if (c == null && W5bSkills.PRAYER_MARKET) {", "if (c == null) return PrayerRun.skip(PrayerRun.why("));

        // ---------------------------------------------------------------- Attack / Strength: stance plan, build bias
        boolean same = true; // both flags off = train-60's expression, for every triple and both coin sides
        for (int a = 1; a <= 99; a += 7) for (int s = 1; s <= 99; s += 7) for (int d = 1; d <= 99; d += 7) for (int c = 0; c < 2; c++)
            for (int b = 0; b < 3; b++) if (!W5bSkills.stance(false, false, b, a, s, d, c == 1).equals(d < a - 10 ? "defence" : s < a ? "strength" : c == 1 ? "attack" : "strength")) same = false;
        boolean plan = W5bSkills.planStance(1, 1, 1, false).equals("attack")        // toward 5
                && W5bSkills.planStance(5, 3, 5, false).equals("strength")              // at tier 5, strength not there yet
                && W5bSkills.planStance(5, 5, 5, false).equals("attack")                // both at 5: attack toward 20 (mithril)
                && W5bSkills.planStance(19, 6, 12, false).equals("attack")              // still pushing to 20
                && W5bSkills.planStance(20, 12, 15, false).equals("strength")           // reached 20: strength catches up to 20
                && W5bSkills.planStance(20, 20, 15, false).equals("attack")             // then attack toward 30
                && W5bSkills.planStance(30, 20, 10, false).equals("defence")            // defence 10+ behind still first
                && W5bSkills.planStance(60, 60, 60, true).equals("attack") && W5bSkills.planStance(60, 60, 60, false).equals("strength") // past 60: old coin flip
                && W5bSkills.planStance(70, 61, 65, true).equals("strength");
        int[] bc = new int[3]; for (int i = 0; i < 10_000; i++) bc[W5bSkills.buildOf("bot" + i + "x")]++;
        boolean shares = Math.abs(bc[1] - 3000) < 400 && Math.abs(bc[0] - 5000) < 400 && Math.abs(bc[2] - 2000) < 400 && W5bSkills.buildOf("Zezima") == W5bSkills.buildOf("zezima") && W5bSkills.buildOf(null) == 0;
        boolean bias = W5bSkills.stance(false, true, W5bSkills.BUILD_STRENGTH, 30, 35, 25, false).equals("strength")   // strength-first: str to att + 10
                && W5bSkills.stance(false, true, W5bSkills.BUILD_STRENGTH, 30, 40, 25, false).equals("attack")
                && W5bSkills.stance(false, true, W5bSkills.BUILD_STRENGTH, 30, 30, 14, false).equals("defence")           // 16 behind
                && W5bSkills.stance(false, true, W5bSkills.BUILD_STRENGTH, 30, 30, 19, false).equals("strength")          // 11 behind: tolerated
                && W5bSkills.stance(false, true, W5bSkills.BUILD_TANK, 30, 30, 29, false).equals("defence")
                && W5bSkills.stance(false, true, W5bSkills.BUILD_TANK, 30, 25, 30, false).equals("strength")
                && W5bSkills.stance(false, true, W5bSkills.BUILD_BALANCED, 30, 25, 30, false).equals("strength")
                && W5bSkills.stance(true, true, W5bSkills.BUILD_BALANCED, 20, 20, 15, false).equals("attack")
                && W5bSkills.stance(true, false, W5bSkills.BUILD_TANK, 30, 30, 29, false).equals("attack");               // bias off: the plan only
        check("A/S stance: both off = train-60 for every triple " + same + ", plan to the next tier " + plan + ", build shares 30/50/20 " + bc[1] + "/" + bc[0] + "/" + bc[2] + " " + shares + ", bias rules " + bias,
                same && plan && shares && bias);
        check("A/S stance wiring: the old expression kept on the flag-off branch (RNG drawn the same way), the new rule only behind either flag",
                all(sa, "if (W5bSkills.STANCE_PLAN || W5bSkills.BUILD_BIAS) {", "st = W5bSkills.stance(W5bSkills.STANCE_PLAN, W5bSkills.BUILD_BIAS, build, att, str, def, RNG.nextBoolean());",
                        "} else st = def < att - 10 ? \"defence\" : str < att ? \"strength\" : RNG.nextBoolean() ? \"attack\" : \"strength\";", "try { AfkTasks.setStyle(bot, st); } catch (Throwable ignored) { }")
                        && order(sa, "} else st = def < att - 10 ?", "try { AfkTasks.setStyle(bot, st); }"));

        // ---------------------------------------------------------------- Defence: defShare
        boolean ds = W5bSkills.rangerStance(20, 31).equals("longrange") && W5bSkills.rangerStance(21, 31).equals("rangeaccurate") && W5bSkills.rangerStance(40, 31).equals("rangeaccurate");
        check("D defShare rule: Longrange while Defence < Ranged - 10 " + ds, ds);
        check("D defShare wiring: setStyle knows longrange / rangeaccurate (no other caller passes them), called for rangers only, behind the flag, before the melee fallback",
                all(at, "case \"longrange\": want = WeaponInterface.STYLE_LONG_RANGE; break;", "case \"rangeaccurate\": want = WeaponInterface.STYLE_RANGE_ACCURATE; break;",
                        "case \"attack\": want = WeaponInterface.STYLE_ACCURATE; break;")
                        && all(sa, "if (W5bSkills.DEF_SHARE && styleOf(bot) == RANGED) try { w5bDefShare(); }", "if (\"longrange\".equals(want) == isLong) return;")
                        && order(sa, "if (W5bSkills.DEF_SHARE && styleOf(bot) == RANGED) try { w5bDefShare(); }", "if (style5()) try { styleFallback(); }")
                        && sa.split("\"longrange\"", -1).length - 1 == 2 && sa.split("\"rangeaccurate\"", -1).length - 1 == 0);

        // G: every hook is behind its own static; the hourly line prints only with a flag on
        int hooks = 0; for (String src : new String[]{sa, ar, at, mb}) { Matcher h = Pattern.compile("W5bSkills\\.(CRAFTER2|STALLS|PRAYER_MARKET|STANCE_PLAN|BUILD_BIAS|DEF_SHARE)").matcher(src); while (h.find()) hooks++; }
        check("G guard: " + hooks + " flag tests in the four callers; the hourly [W5b] line is behind anyOn()",
                hooks >= 14 && all(mb, "if (W5bSkills.anyOn()) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(W5bSkills.line()); } catch (Throwable ignored) { } } }") && !mb.contains("try { if (W5bSkills.anyOn()) System.out.println(W5bSkills.line()); }"));

        System.out.println("[TestW5bSkills] SUMMARY " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
