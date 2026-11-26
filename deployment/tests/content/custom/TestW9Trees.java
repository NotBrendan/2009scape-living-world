package content.custom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W9 lane I: tree and fruit-tree runs (-Dbots.farming.trees.run, W9Trees), plain java (no world, no cache):
 *   java -cp "<build>;server.jar" content.custom.TestW9Trees <addons-src>/content/custom
 * Run it three ways: bare (all off), -Dbots.farming=true -Dbots.farming.trees=true (rules only: RUN stays off), and with
 * -Dbots.farming.trees.run=true added (RUN on). The pure rules run in every case; the wiring is read from the source
 * (SmartAdventurers cannot class-init outside a world): every hook sits behind W9Trees.RUN / farmTree, the flag-off lines are
 * still there, and the tree code draws no RNG.
 */
public final class TestW9Trees {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW9Trees] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW9Trees]   missing: " + p); return false; } return true; }
    static int count(String s, String part) { int n = 0; for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) n++; return n; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), fs = read(dir, "FarmSave.java"), fm = read(dir, "Farming.java"), w8 = read(dir, "W8Polish.java"), at = read(dir, "AfkTasks.java");

        // F: the flag needs bots.farming and bots.farming.trees; trees alone stays rules only
        boolean want = Boolean.getBoolean("bots.farming") && Boolean.getBoolean("bots.farming.trees") && Boolean.getBoolean("bots.farming.trees.run");
        check("F flag: RUN " + W9Trees.RUN + " = farming && trees && trees.run (" + want + "); trees alone (W8Polish.TREES " + W8Polish.TREES + ") never turns it on",
                W9Trees.RUN == want && (!W9Trees.RUN || W8Polish.TREES));
        String tail = Farming.totals();
        check("F tally: the tree counters ride on the [Farm] totals only with RUN on, before FarmSave's (still at the very end)", W9Trees.RUN == tail.contains(" trees=[treeRuns=") && tail.startsWith("[Farm] planted=") && (!FarmSave.ON || tail.matches(".* saved=\\d+ restored=\\d+ bad=\\d+ writeFailed=\\d+$")));
        check("F [W8] line: farmingTrees(run) only with RUN on, rulesOnly with trees alone",
                !W8Polish.TREES || W8Polish.line().contains(W9Trees.RUN ? "farmingTrees(run)" : "farmingTrees(rulesOnly)"));

        // 0: flag off = no-op (source text): every hook behind RUN / farmTree, the train-64 lines kept, no RNG and no allotment tally in the tree code
        int pf = sa.indexOf("boolean planFarmRun(long now) {"), hook = sa.indexOf("if (W9Trees.RUN && !(farmDue != 0 && now >= farmDue) && planTreeRun(now)) return true;", pf);
        int chk = sa.indexOf("final boolean check = farmDue != 0 && now >= farmDue;", pf);
        check("0 chooser: the tree hook is the first step after busy/combat, behind W9Trees.RUN, then the allotment chooser unchanged",
                pf > 0 && hook > pf && chk > hook && chk - hook < 400 && sa.indexOf("if (bot.inCombat()) return Farming.skip(Farming.R_COMBAT, now);", pf) < hook);
        int tb = sa.indexOf("// ---- w9 trees (-Dbots.farming.trees.run"), te = sa.indexOf("/** P69 #10: a Watchdog rescue ends the bone run too", tb);
        String tree = tb > 0 && te > tb ? sa.substring(tb, te) : "";
        check("0 tree code: one block, no RNG draw, no allotment tally (Farming.skip), farmTree set true only in planTreeRun",
                tree.length() > 1000 && !tree.contains("RNG.") && !tree.contains("Farming.skip(") && count(sa, "farmTree = true") == 1 && tree.indexOf("farmTree = true") > tree.indexOf("boolean planTreeRun(long now)")
                        && tree.contains("String treeSaveKey() { return W9Trees.RUN && farmDue == 0 && treeDue != 0"));
        check("0 dispatch: farmWork and endFarm hand a tree run over only when farmTree is set; farmStalled names the tree site only then",
                all(sa, "if (farmTree) { treeWork(now); return; } // w9 trees", "if (farmTree) { endTreeRun(gaveUp, why); return; } // w9 trees",
                        "(farmTree ? W9Trees.KEYS[treeSite] : Farming.SITES[farmSite].key)", "private int farmOffload() { return farmOffload(Farming.allCrops(), farmCrops0); }", "if (farmWalkTo(s)) return;"));
        check("0 saves: endFarm, farmLogout, saveCrops and enlist keep their flag-off calls; the tree key only behind RUN",
                all(sa, "if (W9Trees.RUN) farmSaveKeyed(\"run end\"); else FarmSave.save(bot, bot.getUsername(), Farming.SITES[farmSite].key, farmDue, \"run end\");",
                        "kept = FarmSave.save(bot, bot.getUsername(), Farming.SITES[farmSite].key, farmDue, \"logout\");",
                        "} else if (W9Trees.RUN && treeDue != 0) treeLogout();", "if (W9Trees.RUN && treeDue != 0) { if (!FarmSave.ON)",
                        "long due = tk != null ? b.treeDue : FarmSave.pulseDue(b.farmDue, b.farm, now);",
                        "brain.farmSite = fr.siteIdx; brain.farmDue = fr.due; Farming.ACTIVE.add(key); } if (fr != null && W9Trees.RUN) brain.treeRestore(fr, System.currentTimeMillis());")
                        && all(fs, "k -> siteOk(k, W9Trees.RUN)") && all(fm, "totals(V2) + (W9Trees.RUN ? W9Trees.totals() : \"\") + (FarmSave.ON ? FarmSave.totals() : \"\")") && all(w8, "W9Trees.RUN ? \"farmingTrees(run) \" : \"farmingTrees(rulesOnly) \""));
        check("0 money: seed and payment from the bot's own items or bot stock (Ledger.consume), only the plant pot may be a shop buy; every buy under the run budget",
                all(tree, "farmGet(seed, 1, false, left, took)", "farmGet(sap, 1, false, left, took)", "farmGet(W9Trees.PLANT_POT, 1, true, left, took)", "Ledger.consume(bot, id, need, \"farming\")",
                        "final long[] left = {W9Trees.budget(coins0)};", "(long) MarketBrain.value(id) * need <= left[0]")
                        && !tree.contains("Ledger.Wallet.pay(") && count(tree, ", true, left, took)") == 3); // the spade and rake (shop tools, as the allotment run) and the plant pot

        // 1: sapling rule (W8Polish.seedSource under it): owned sapling > owned seed > bot stock (budget) > none; growing needs a pot
        boolean s1 = W9Trees.saplingSource(1, 0, 0, 0, false, 0, 0) == 3, s2 = W9Trees.saplingSource(0, 2, 0, 500, true, 10, 0) == 1;
        boolean s3 = W9Trees.saplingSource(0, 0, 4, 100, true, 10, 100) == 2 && W9Trees.saplingSource(0, 0, 4, 100, true, 10, 99) == 0;
        boolean s4 = W9Trees.saplingSource(0, 0, 4, 100, false, 5, 105) == 2 && W9Trees.saplingSource(0, 0, 4, 100, false, 5, 104) == 0 && W9Trees.saplingSource(0, 1, 0, 0, false, 5, 4) == 0 && W9Trees.saplingSource(0, 1, 0, 0, false, 0, 100) == 0;
        boolean s5 = W9Trees.saplingSource(0, 0, 0, 1, true, 1, 1_000_000) == 0; // nothing owned, no stock: never a shop
        int[][] tr = W8Polish.TREE_ROWS;
        int pick = W8Polish.treePick(tr, 62, seed -> W9Trees.saplingSource(seed == 5313 ? 1 : 0, 0, seed == 5315 ? 3 : 0, 400, true, 5, 300) > 0); // yew stock too dear, willow sapling owned
        check("1 sapling: owned sapling 3, owned seed 1, stock seed 2 inside the budget (seed + pot), else 0; treePick takes the best sourced row (" + pick + ")",
                s1 && s2 && s3 && s4 && s5 && pick == 1 && W9Trees.rowOfSeed(tr, 5316) == 4 && W9Trees.rowOfSapling(W8Polish.FRUIT_ROWS, 5502) == 6 && W9Trees.rowOfSeed(tr, 1) == -1);
        check("1 budget: a tenth of the coins, at most SPEND_CAP (" + W9Trees.SPEND_CAP + ")", W9Trees.budget(1000) == 100 && W9Trees.budget(-5) == 0 && W9Trees.budget(Long.MAX_VALUE / 2) == W9Trees.SPEND_CAP);

        // 2: site choice: nearest enabled site of a kind the bot can plant, inside PLANT_R, same side of White Wolf Mountain
        int falE = W9Trees.chooseSite(3012, 3356, 0, true, true, W9Trees.PLANT_R), falW = W9Trees.chooseSite(2946, 3370, 0, true, false, W9Trees.PLANT_R);
        int dray = W9Trees.chooseSite(3092, 3245, 0, true, true, W9Trees.PLANT_R), varW = W9Trees.chooseSite(3185, 3438, 0, true, true, W9Trees.PLANT_R);
        int cathF = W9Trees.chooseSite(2809, 3441, 0, false, true, W9Trees.PLANT_R), cathT = W9Trees.chooseSite(2809, 3441, 0, true, false, W9Trees.PLANT_R);
        int ard = W9Trees.chooseSite(2617, 3332, 0, true, true, W9Trees.PLANT_R), gnome = W9Trees.chooseSite(2475, 3440, 0, true, true, W9Trees.PLANT_R);
        int edge = W9Trees.chooseSite(3094, 3492, 0, true, true, W9Trees.PLANT_R), up = W9Trees.chooseSite(3012, 3356, 1, true, true, W9Trees.PLANT_R), none = W9Trees.chooseSite(3012, 3356, 0, false, false, W9Trees.PLANT_R);
        check("2 site: Falador east/west -> falador park, Draynor -> lumbridge, Varrock west -> varrock, Catherby fruit -> catherby, Catherby tree-only none (mountain), "
                        + "Ardougne / the Stronghold / Edgeville / upstairs / no kind -> none (" + falE + " " + falW + " " + dray + " " + varW + " " + cathF + " " + cathT + " " + ard + " " + gnome + " " + edge + " " + up + " " + none + ")",
                falE == 0 && falW == 0 && dray == 3 && varW == 2 && cathF == 4 && cathT == -1 && ard == -1 && gnome == -1 && edge == -1 && up == -1 && none == -1);
        check("2 check range: a CHECK starts from a bank within CHECK_R (Edgeville -> varrock yes, Catherby -> falador park no: the mountain)",
                W9Trees.inRange(2, 3094, 3492, 0, W9Trees.CHECK_R) && !W9Trees.inRange(0, 2809, 3441, 0, W9Trees.CHECK_R) && !W9Trees.inRange(2, 3094, 3492, 0, W9Trees.PLANT_R));
        int on = 0; java.util.Set<String> keys = new java.util.HashSet<>(); boolean banks = true, shape = true;
        for (int i = 0; i < W9Trees.SITES.length; i++) {
            int[] s = W9Trees.SITES[i]; on += s[W9Trees.SON]; keys.add(W9Trees.KEYS[i]);
            shape &= s.length == 11 && (s[W9Trees.SSIZE] == (W9Trees.fruit(i) ? 2 : 3)) && Math.abs(s[W9Trees.SAX] - s[W9Trees.SX]) <= 6 && Math.abs(s[W9Trees.SAY] - s[W9Trees.SY]) <= 6 && W9Trees.KEYS[i].startsWith(W9Trees.fruit(i) ? "FRUIT_" : "TREE_");
            if (s[W9Trees.SON] == 1) banks &= at.contains("Location.create(" + s[W9Trees.SBX] + ", " + s[W9Trees.SBY] + ", 0)");
        }
        boolean enumOk = true;
        for (String p : W9Trees.PATCHES) try { content.global.skill.farming.PatchType t = content.global.skill.farming.FarmingPatch.valueOf(p).getType(); enumOk &= t == content.global.skill.farming.PatchType.TREE_PATCH || t == content.global.skill.farming.PatchType.FRUIT_TREE_PATCH; } catch (Throwable e) { enumOk = false; }
        boolean kinds = true; for (int i = 0; i < W9Trees.SITES.length; i++) kinds &= content.global.skill.farming.FarmingPatch.valueOf(W9Trees.PATCHES[i]).getType() == (W9Trees.fruit(i) ? content.global.skill.farming.PatchType.FRUIT_TREE_PATCH : content.global.skill.farming.PatchType.TREE_PATCH);
        check("2 table: 9 sites, 5 enabled (falador park, taverley, varrock, lumbridge, catherby), keys unique and never an allotment key, engine patch names and kinds, banks in AfkTasks.BANKS, farmer next to the patch",
                W9Trees.SITES.length == 9 && W9Trees.KEYS.length == 9 && W9Trees.PATCHES.length == 9 && W9Trees.NAMES.length == 9 && on == 5 && keys.size() == 9 && shape && banks && enumOk && kinds
                        && W9Trees.SITES[0][W9Trees.SON] + W9Trees.SITES[1][W9Trees.SON] + W9Trees.SITES[2][W9Trees.SON] + W9Trees.SITES[3][W9Trees.SON] + W9Trees.SITES[4][W9Trees.SON] == 5
                        && Farming.siteIndex(W9Trees.KEYS[0]) == -1 && W9Trees.siteIndex("FALADOR") == -1 && W9Trees.siteIndex("FRUIT_CATHERBY") == 4 && W9Trees.siteIndex(null) == -1
                        && W9Trees.siteOfPatch("LUMBRIDGE_TREE") == 3 && W9Trees.siteOfPatch("S_FALADOR_HERB_NE") == -1);
        check("2 cache tiles: the patch wrappers read from the cache (falador park 8389 at 3003,3372; catherby 7965 at 2860,3433; brimhaven 7964 at 2764,3212)",
                W9Trees.SITES[0][0] == 3003 && W9Trees.SITES[0][1] == 3372 && W9Trees.SITES[0][W9Trees.SOBJ] == 8389 && W9Trees.SITES[4][0] == 2860 && W9Trees.SITES[4][1] == 3433 && W9Trees.SITES[4][W9Trees.SOBJ] == 7965
                        && W9Trees.SITES[8][0] == 2764 && W9Trees.SITES[8][1] == 3212 && W9Trees.SITES[8][W9Trees.SOBJ] == 7964);

        // 3: pay / no pay (W8Polish.payFarmer under it, plus the budget for the part not owned)
        int[] oak = tr[0], yew = tr[3], banana = W8Polish.FRUIT_ROWS[1];
        check("3 pay: owned in full -> pay; the rest from stock only inside the budget; no known payment -> unprotected",
                W9Trees.payOk(oak, 1, 0, 999, 0) && !W9Trees.payOk(oak, 0, 1, 50, 49) && W9Trees.payOk(oak, 0, 1, 50, 50) && !W9Trees.payOk(oak, 0, 0, 1, 1000)
                        && !W9Trees.payOk(yew, 4, 5, 1, 1000) && W9Trees.payOk(yew, 4, 6, 10, 60) && !W9Trees.payOk(yew, 4, 6, 10, 59) && !W9Trees.payOk(banana, 99, 99, 1, 1_000_000));
        check("3 wiring: the payment is planned at the bank (payOk, treePayGet), carried noted, paid at the patch once (Patch.setProtectionPaid), left-overs banked",
                all(tree, "W9Trees.payOk(r, owned, MarketBrain.stockOf(r[4]), MarketBrain.value(r[4]), left[0])", "if (paid) p.setProtectionPaid(true);", "if (!p.getProtectionPaid() && treePayId > 0) {", "if (treePayId > 0) treeBankPay(treePayId);"));

        // 4: check due vs saveFits
        long t0 = 1_000_000_000L, h = 3_600_000L;
        long magic = W8Polish.checkDue(t0, 480), fruitDue = W8Polish.checkDue(t0, 960);
        check("4 plant only when the check fits: inside FarmSave's file age with the save on, else before the shift ends",
                W9Trees.plantOk(true, t0, magic, 0) && W9Trees.plantOk(true, t0, fruitDue, 0) && !W9Trees.plantOk(true, t0, t0 + 49 * h, Long.MAX_VALUE)
                        && !W9Trees.plantOk(false, t0, W8Polish.checkDue(t0, 200), t0 + 8 * h) && W9Trees.plantOk(false, t0, W8Polish.checkDue(t0, 200), t0 + 21 * h) && !W9Trees.plantOk(false, t0, t0, t0 + h));
        check("4 next visit: none planted 0; planted now = checkDue; growing = stages left + margin; grown fruit = fruit back (4 h); grown tree = retry",
                W9Trees.dueAfter(false, true, false, false, 480, 5, 40, t0) == 0 && W9Trees.dueAfter(true, true, false, false, 480, 5, 40, t0) == magic
                        && W9Trees.dueAfter(true, false, false, false, 0, 3, 40, t0) == t0 + 120 * 60_000L + W8Polish.TREE_MARGIN_MS
                        && W9Trees.dueAfter(true, false, true, true, 0, 0, 40, t0) == t0 + W9Trees.FRUIT_REGROW_MS && W9Trees.dueAfter(true, false, true, false, 0, 0, 40, t0) == t0 + W9Trees.RETRY_MS
                        && W8Polish.saveFits(t0, W9Trees.dueAfter(true, false, true, true, 0, 0, 40, t0)));
        check("4 wiring: the plant decision uses checkDue + plantOk(FarmSave.ON, ...); the run end recomputes the due from the patch",
                all(tree, "!W9Trees.plantOk(FarmSave.ON, now, W8Polish.checkDue(now, rows[row][3]), shiftEnd)", "treeDue = treeDueNow(now);", "return W9Trees.dueAfter(true, treePlanted, p.isGrown()"));

        // 5: FarmSave round trip of a tree site key (allowed only with the flag on) through a real file
        org.json.simple.JSONObject crops = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse("{\"patches\":[{\"patch-ordinal\":40,\"patch-plantable-ordinal\":38}]}");
        long now = System.currentTimeMillis(), due = W8Polish.checkDue(now, 200);
        File f = File.createTempFile("w9trees", ".json"); f.deleteOnExit();
        FarmSave.writeAtomic(f, FarmSave.wrap("Unit Tree", now, "TREE_FALADOR", due, crops));
        String back = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        FarmSave.Rec on1 = FarmSave.read(back, "unit tree", now + 60_000, 50, k -> FarmSave.siteOk(k, true));
        FarmSave.Rec off1 = FarmSave.read(back, "unit tree", now + 60_000, 50, k -> FarmSave.siteOk(k, false));
        FarmSave.Rec allot = FarmSave.read(FarmSave.wrap("Unit Tree", now, "CATHERBY", due, crops), "unit tree", now, 50, k -> FarmSave.siteOk(k, false));
        FarmSave.Rec junk = FarmSave.read(FarmSave.wrap("Unit Tree", now, "TREE_NOWHERE", due, crops), "unit tree", now, 50, k -> FarmSave.siteOk(k, true));
        check("5 FarmSave: a TREE_FALADOR file round-trips with trees on (site, due, rows); refused as \"site\" with trees off; allotment keys either way; junk keys never",
                on1.bad == null && "TREE_FALADOR".equals(on1.site) && on1.due == due && on1.crops != null && off1.bad != null && off1.bad.startsWith("site")
                        && allot.bad == null && junk.bad != null && FarmSave.siteOk("FALADOR", false) && FarmSave.siteOk("FRUIT_CATHERBY", true) && !FarmSave.siteOk("FRUIT_CATHERBY", false) && !FarmSave.siteOk(null, true)
                        && back.contains("\"v\":1") && FarmSave.V == 1);
        check("5 wiring: the format is untouched (no new wrapper field); restore takes tree keys only through siteOk(k, W9Trees.RUN); a tree-only bot files the tree key, crops + tree file the allotment key",
                all(fs, "o.put(\"v\", V); o.put(\"name\", name); o.put(\"savedAt\", savedAt); o.put(\"site\", site); o.put(\"due\", due); o.put(\"crops\", crops);")
                        && count(fs, "o.put(") == 7 /* wrap's six + rename's name */ && all(tree, "return FarmSave.save(bot, bot.getUsername(), tk != null ? tk : Farming.SITES[farmSite].key, tk != null ? treeDue : farmDue, why);",
                        "treeDue = W9Trees.KEYS[si].equals(fr.site) ? fr.due"));

        // 6: products, lines, counters
        W9Trees.lineOk(0); int ok = 1; for (int i = 0; i < W9Trees.LINE_CAP + 5; i++) if (W9Trees.lineOk(1000)) ok++;
        long cut = W9Trees.linesCut; boolean fresh = W9Trees.lineOk(11 * 60_000L);
        check("6 lines: at most LINE_CAP (" + W9Trees.LINE_CAP + ") per 10 minutes, the rest counted in treeCut=; a new window starts fresh",
                ok == W9Trees.LINE_CAP && cut == 6 && fresh && W9Trees.totals().contains(" treeCut=6]"));
        String tt = W9Trees.totals(); boolean reasons = true; for (String r : W9Trees.REASONS) reasons &= tt.contains(r + ":");
        check("6 tally + products: totals lists every counter and skip reason; fruit row for row with FRUIT_ROWS; lines are [Farm] tree <bot> plant|pay|check|fruit|dig ... at <site>",
                tt.startsWith(" trees=[treeRuns=") && reasons && W9Trees.FRUITS.length == W8Polish.FRUIT_ROWS.length && W9Trees.saplings().length == 12 && W9Trees.seeds()[0] == 5312
                        && all(tree, "\" plant \" + MarketBrain.name(treeSapling)", "\" pay \" + q + \" x \"", "\" check \" + (pl != null", "\" fruit \" + g + \" x \"", "\" dig \" + treeDigWhat + \" \"", "\" at \" + treeAt()"));

        System.out.println("[TestW9Trees] DONE pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
