package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3 skills wave 1 (the design notes): the nine fixes, plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW3Skills <addons-src>/content/custom
 * Run it twice: bare (every flag off) and with all nine -D flags set (the F case checks the statics follow the properties).
 * Per fix: the rule itself (W3Skills, fed the s59 soak's numbers), the real caller's own pure rule where it loads without a
 * world (P87Fight.foodNeed / gate), and the wiring: the real caller holds the hook behind its flag and still holds train-59's
 * code for the flag-off path. SmartAdventurers, Artisans and Batch29 cannot class-init outside a world, so their wiring is
 * read from the source. The in-world checks are in the design notes.
 */
public final class TestW3Skills {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW3Skills] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW3Skills]   missing: " + p); return false; } return true; }
    static boolean order(String src, String first, String second) { int a = src.indexOf(first), b = src.indexOf(second); return a >= 0 && b > a; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), ar = read(dir, "Artisans.java"), b29 = read(dir, "Batch29.java"), p87 = read(dir, "P87Fight.java"), bl = read(dir, "BotLife.java"), mb = read(dir, "MarketBrain.java"), rec = read(dir, "artisan-recipes.json");

        // F: the nine statics follow their properties, default off
        String[] keys = {"bots.chains.smeltFix", "artisans.herbFix", "bots.summoning.wwSeed", "bots.jobShare", "artisans.arrowFix", "bots.fight.cheapFood", "bots.style.restock", "bots.plankFix", "bots.agility.laps"};
        boolean[] st = {W3Skills.SMELT_FIX, W3Skills.HERB_FIX, W3Skills.WW_SEED, W3Skills.SHARE_ON, W3Skills.ARROW_FIX, W3Skills.CHEAP_FOOD, W3Skills.RESTOCK, W3Skills.PLANK_FIX, W3Skills.AGI_LAPS};
        boolean follow = true; int on = 0; for (int i = 0; i < keys.length; i++) { if (st[i] != Boolean.getBoolean(keys[i])) follow = false; if (st[i]) on++; }
        check("F flags: 9 statics follow their -D properties (" + on + " on), anyOn " + W3Skills.anyOn() + ", line names them: " + W3Skills.flags(), follow && W3Skills.anyOn() == (on > 0) && W3Skills.flags().split(" ").length == (on == 0 ? 1 : on) && W3Skills.line().startsWith("[W3] on=["));

        // 1 smelt chain. s59: bronze bar about level with copper + tin, iron bar x 0.5 under iron ore: demand 0.8 for all, 0 smelt chains.
        boolean off1 = W3Skills.smeltDemand(false, 100, 100, 3, 0, 150, true) == 0.8 && W3Skills.smeltDemand(false, 130, 100, 0, 999, 150, false) == 1.25;
        boolean on1 = W3Skills.smeltDemand(true, 100, 100, 1, 500, 150, false) == 1.25      // smiths waiting
                && W3Skills.smeltDemand(true, 100, 100, 0, 60, 150, false) == 1.25          // stock under half the target
                && W3Skills.smeltDemand(true, 100, 100, 0, 500, 150, true) == 1.25          // ore glut
                && W3Skills.smeltDemand(true, 100, 100, 0, 500, 150, false) == 0.8          // nothing scarce: old rule
                && W3Skills.smeltDemand(true, 100, 100, 0, 75, 150, false) == 0.8           // exactly half is not under half
                && W3Skills.smeltDemand(true, 40, 100, 5, 0, 150, true) == 0.8              // would destroy over half the value
                && W3Skills.smeltDemand(true, 50, 100, 5, 0, 150, true) == 1.25             // iron at 50%: allowed
                && W3Skills.smeltDemand(true, 100, 0, 5, 0, 150, true) == 0.8;              // no ore price
        check("1 smelt rule: flag off = train-59 (0.8 unless 20% dearer) " + off1 + ", flag on scarce/glut/half-value " + on1, off1 && on1);
        check("1 smelt pick: 0.9 x 1.25 beats the raw job, 0.9 x 0.8 does not (Chains.pick)", 0.9 * W3Skills.smeltDemand(true, 100, 100, 1, 0, 150, false) > 1 && 0.9 * W3Skills.smeltDemand(false, 100, 100, 1, 0, 150, false) < 1);
        check("1 smelt wiring: Batch29.Chains.demand calls the rule behind SMELT_FIX inside the SMELT branch, old rule line kept",
                all(b29, "if (W3Skills.SMELT_FIX) {", "W3Skills.smeltDemand(true, prod, raw, Artisans.SHORT.getOrDefault(b[0], 0), MarketBrain.stockOf(b[0]), Artisans.target(b[0]), glut)", "return raw > 0 && prod >= raw * 1.2 ? 1.25 : 0.8;")
                        && order(b29, "if (c.chain == SMELT) {", "if (W3Skills.SMELT_FIX) {") && order(b29, "if (W3Skills.SMELT_FIX) {", "int r = c.task.products[0], k = cookedOf(r)"));

        // 2 herbalist. choose(): times = min(times, (stock - keep) / inAmt) per input; a staple with 0 stock made it 0.
        boolean ids = W3Skills.grimy(199) && W3Skills.grimy(219) && W3Skills.grimy(2485) && W3Skills.grimy(3051) && !W3Skills.grimy(200) && !W3Skills.grimy(221)
                && W3Skills.cleanHerb(249) && W3Skills.cleanHerb(269) && W3Skills.cleanHerb(2998) && !W3Skills.cleanHerb(250)
                && W3Skills.unfPotion(91) && W3Skills.unfPotion(111) && W3Skills.unfPotion(3004) && !W3Skills.unfPotion(113) && !W3Skills.herbChain(227) && !W3Skills.herbChain(436);
        int named = 0, bad = 0;
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(rec);
        while (m.find()) { int id = Integer.parseInt(m.group(1)); String n = m.group(2);
            boolean std = id < 300 || id == 2485 || id == 3049 || id == 3051 || id == 12174 || id == 2481 || id == 2998 || id == 3000 || id == 12172;
            if (n.startsWith("Grimy ") && std) { named++; if (!W3Skills.grimy(id)) bad++; }
            else if (n.startsWith("Clean ") && std) { named++; if (!W3Skills.cleanHerb(id)) bad++; }
            else if (n.endsWith(" potion(unf)")) { named++; if (!W3Skills.unfPotion(id)) bad++; } }
        check("2 herb ids: grimy / clean / unf sets " + ids + "; recipe file inputs named so: " + named + ", outside the sets: " + bad, ids && named >= 50 && bad == 0);
        boolean keep = W3Skills.keep(true, false, "herblore", 199, 50) == 0 && W3Skills.keep(true, false, "herblore", 91, 50) == 0 && W3Skills.keep(true, false, "herblore", 221, 50) == 50
                && W3Skills.keep(false, false, "herblore", 199, 50) == 50 && W3Skills.keep(true, true, "smithing", 199, 50) == 50 && W3Skills.keep(true, true, "cooking", 227, 50) == 50;
        boolean staple = W3Skills.stapleFree(true, "herblore", 227, true) && W3Skills.stapleFree(true, "herblore", 221, false) && !W3Skills.stapleFree(true, "herblore", 225, false)
                && !W3Skills.stapleFree(false, "herblore", 227, true) && !W3Skills.stapleFree(true, "crafting", 1734, true);
        // replay of choose() for "guam potion(unf)" = vial of water + clean guam with 0 vials and 24 clean guam on the Exchange
        int tOld = Math.min(28, Math.min((0 - 50) / 1, (24 - 50) / 1)), tNew = 28;
        for (int[] in : new int[][]{{227, 0}, {249, 24}}) { if (W3Skills.stapleFree(true, "herblore", in[0], in[0] == 227)) continue; tNew = Math.min(tNew, (in[1] - W3Skills.keep(true, false, "herblore", in[0], 50)) / 1); }
        check("2 herb rule: reserve 0 for herb chain goods only " + keep + ", staples free for herblore only " + staple + ", replay unf guam with 0 vials + 24 clean: batches " + tOld + " -> " + tNew, keep && staple && tOld <= 0 && tNew == 24);
        check("2 herb wiring: Artisans.choose (staple skip, reserve), buyInputs (reserve, eye of newt staple), idle line, BotLife herb gatherer lines",
                all(ar, "if (W3Skills.stapleFree(W3Skills.HERB_FIX, r.trade, r.inIds[k], STAPLES.contains(r.inIds[k]))) { W3Skills.stapleSkips++; continue; }", "- W3Skills.keep(r.trade, r.inIds[k], KEEP_INPUT);",
                        "int keepK = W3Skills.keep(plan.r.trade, plan.r.inIds[k], KEEP_INPUT);", "if (!W3Skills.staple(plan.r.trade, plan.r.inIds[k], STAPLES.contains(plan.r.inIds[k]))) return false;", "herbalist idle: grimy=", "static final int KEEP_INPUT = 50;")
                        && order(ar, "static synchronized Plan choose(", "W3Skills.stapleFree(") && order(ar, "static synchronized boolean buyInputs(", "int keepK = W3Skills.keep(")
                        && all(bl, "} else if (W3Skills.HERB_FIX) {", "has no chaos druid spot", "if (W3Skills.HERB_FIX) System.out.println(\"[BotLife] herb gatherer \" + bot.getUsername() + \" ticking at \""));
        long t0 = 10_000_000L; W3Skills.herbIdleAt = 0;
        check("2 herb idle line: once per 30 min", W3Skills.herbIdleDue(t0) && !W3Skills.herbIdleDue(t0 + 29 * 60_000L) && W3Skills.herbIdleDue(t0 + 30 * 60_000L));

        // Review 11-19: the real caller passes Recipe.trade, which Artisans stores in upper case, and Trade.name() (an enum constant).
        boolean upper = all(ar, "r.trade = ((String) j.get(\"trade\")).toUpperCase();", "W3Skills.keep(r.trade, r.inIds[k], KEEP_INPUT)", "W3Skills.keep(plan.r.trade, plan.r.inIds[k], KEEP_INPUT)", "\"herblore\".equalsIgnoreCase(trade.name())", "HERBLORE(", "FLETCHING(");
        boolean real = W3Skills.keep(true, false, "HERBLORE", 199, 50) == 0 && W3Skills.keep(false, true, "FLETCHING", 53, 50) == 0 && W3Skills.keep(true, true, "SMITHING", 199, 50) == 50
                && W3Skills.stapleFree(true, "HERBLORE", 227, true) && W3Skills.stapleFree(true, "HERBLORE", 221, false) && !W3Skills.stapleFree(true, "CRAFTING", 1734, true);
        check("2/5 trade case: Artisans passes the trade in upper case " + upper + ", the rules answer for HERBLORE / FLETCHING as the caller spells them " + real, upper && real);

        // 3 Wolf Whistle seed
        int picked = 0; for (int i = 0; i < 1000; i++) if (W3Skills.wwPick("Vet" + i * 7919, true, false, 34)) picked++;
        boolean ww = !W3Skills.wwPick("Anyone", false, false, 100) && W3Skills.wwPick("Anyone", true, false, 100) && W3Skills.wwPick("Anyone", false, true, 100) && !W3Skills.wwPick("Anyone", true, true, 0) && !W3Skills.wwPick(null, true, true, 100)
                && W3Skills.wwPick("Shopferix", true, false, 34) == W3Skills.wwPick("shopFERIX", true, false, 34) && W3Skills.wwCharms(true, false) == 25 && W3Skills.wwCharms(true, true) == 40 && W3Skills.wwCharms(false, false) == 0 && W3Skills.wwCharms(false, true) == 0 && W3Skills.wwCharms(true) == (Boolean.getBoolean("bots.summoning.wwSeed.charms") ? 40 : 0);
        check("3 wwSeed rule: only veterans / high tier, stable by name, share 34% -> " + picked + " of 1000, charms 25 / 40 only with wwSeed.charms, else 0", ww && picked > 280 && picked < 400);
        check("3 wwSeed wiring: enlist calls seedWolfWhistle after seedAgility; needs WW_SEED and Summon.ON; a finished quest returns before the charms",
                all(sa, "seedAgility(bot, veteran, high);\r\n        seedWolfWhistle(bot, veteran, high);", "if (!W3Skills.WW_SEED || !Summon.ON || bot == null) return;", "if (q.isCompleted(bot)) { W3Skills.wwAlready++; return; }", "if (n > 0) bot.getBank().add(new Item(Summon.GOLD, n));")
                        && order(sa, "if (q.isCompleted(bot)) { W3Skills.wwAlready++; return; }", "if (n > 0) bot.getBank().add(new Item(Summon.GOLD, n));") && order(sa, "W3Skills.wwPick(bot.getUsername(), veteran, high, W3Skills.WW_SHARE)", "bot.getQuestRepository().setStage(q, 100);\r\n            if (bot.getSkills().getStaticLevel(Skills.SUMMONING) <= 1)"));

        // 4 job share. s59: thieving about 49% of job starts, woodcutting under 1%.
        boolean th = W3Skills.thieveFactor(true, 0.49, 0.15) == 0.1 && W3Skills.thieveFactor(true, 0.15, 0.15) == 0.1 && W3Skills.thieveFactor(true, 0.14, 0.15) == 1.0 && W3Skills.thieveFactor(false, 0.49, 0.15) == 1.0;
        boolean lg = W3Skills.logWeight(true, 0, true, 0.01, 0.08, 0.6) == 0.6 && W3Skills.logWeight(true, 1.3, true, 0.01, 0.08, 0.6) == 1.3 && W3Skills.logWeight(true, 0, true, 0.08, 0.08, 0.6) == 0
                && W3Skills.logWeight(true, 0, false, 0.0, 0.08, 0.6) == 0 && W3Skills.logWeight(false, 0, true, 0.0, 0.08, 0.6) == 0;
        check("4 jobShare rule: thieving x0.1 at the cap, x1 under it or flag off " + th + "; log floor only for tree jobs under the wanted share " + lg, th && lg);
        check("4 jobShare wiring: chooseJob floors the weight before the 'weight <= 0' skip and damps thieving before the best-free and best picks; shares from job starts (w4-jobshare)",
                all(sa, "if (W3Skills.SHARE_ON && j.hunt == null) { w3Shares(); double w0 = weight; weight = W3Skills.logWeight(true, weight, j.task.kind == AfkTasks.Kind.TREE, W3Skills.logNow, W3Skills.LOG_WANT, W3Skills.LOG_FLOOR);",
                        "if (W3Skills.SHARE_ON && j.hunt == null && j.task.kind == AfkTasks.Kind.THIEVE) { w3Shares(); double tf = W3Skills.thieveFactor(true, W3Skills.thieveNow, W3Skills.THIEVE_CAP); if (tf < 1 && W3Skills.brokeCoinKeep(W3Skills.BROKE_COINS, broke, coinJob)) { tf = 1; W3Skills.brokeKept++; } if (tf < 1) { score *= tf;", "static void w3Shares() {", "double[] sh = W3Skills.startShares(); W3Skills.thieveNow = sh[0]; W3Skills.logNow = sh[1];")
                        && !sa.contains("AfkTasks.RUNNING.values()) { if (r == null || r.task == null || r.p == null || !r.p.isArtificial()) continue; if (r.task.kind == AfkTasks.Kind.THIEVE) th++;")
                        && order(sa, "double weight = jobWeight(j);\r\n                if (W3Skills.SHARE_ON", "if (weight <= 0 && !(broke && coinJob)") && order(sa, "W3Skills.thieveFactor(true", "if (brokeFree && score > bestFreeScore && freeJob(j, dist))")
                        && order(sa, "score = v / (1 + dist / 150.0) / (1 + busy);", "W3Skills.thieveFactor(true"));
        // 4c W9 guards2 (g61/g61b): a broke bot's coin pickpocket job is not damped (-Dbots.jobShare.brokeCoins, default true)
        boolean bk = W3Skills.brokeCoinKeep(true, true, true) && !W3Skills.brokeCoinKeep(false, true, true) && !W3Skills.brokeCoinKeep(true, false, true) && !W3Skills.brokeCoinKeep(true, true, false)
                && W3Skills.BROKE_COINS == !"false".equals(System.getProperty("bots.jobShare.brokeCoins")) && W3Skills.line().contains(" brokeKept=") == W3Skills.SHARE_ON;
        check("4c jobShare brokeCoins: kept only for a broke bot's coin job with the rule on; sub-flag follows -Dbots.jobShare.brokeCoins (" + W3Skills.BROKE_COINS + "); [W3] line counts it with jobShare on " + bk, bk
                && order(sa, "double tf = W3Skills.thieveFactor(true, W3Skills.thieveNow, W3Skills.THIEVE_CAP); if (tf < 1 && W3Skills.brokeCoinKeep(", "if (tf < 1) { score *= tf; W3Skills.thieveDamped++; }")
                && order(sa, "boolean coinJob = j.hunt == null && j.task.kind == AfkTasks.Kind.THIEVE", "W3Skills.brokeCoinKeep(W3Skills.BROKE_COINS, broke, coinJob)"));
        // 4b w4-jobshare (k60 / k60b): the share is of job starts. Pickpocket trips last about a minute, so 2-7 of 60 bots thieved at any
        // one time (under the 15% cap, never damped) while 215 of 490 starts were pickpocketing. Fed the k60 ON arm's ratio: 44 of 100.
        byte[] ring = new byte[100]; for (int i = 0; i < 44; i++) ring[i] = 1; ring[44] = 2;
        double[] k60 = W3Skills.startShares(ring, 100, 20), boot = W3Skills.startShares(ring, 19, 20), wrap = W3Skills.startShares(ring, 250, 20), few = W3Skills.startShares(new byte[100], 30, 20);
        boolean ss = Math.abs(k60[0] - 0.44) < 1e-9 && Math.abs(k60[1] - 0.01) < 1e-9 && boot[0] == 0 && boot[1] == 0 && Math.abs(wrap[0] - 0.44) < 1e-9 && few[0] == 0 && few[1] == 0
                && W3Skills.thieveFactor(true, k60[0], 0.15) == 0.1 && W3Skills.thieveFactor(true, 6 / 60.0, 0.15) == 1.0     // the old measure (6 of 60 now) never closed; the new one does
                && W3Skills.logWeight(true, 0, true, k60[1], 0.08, 0.6) == 0.6;
        boolean wy = "log floor".equals(W3Skills.shareWhy(true, false, false, 0, 0.15)) && "thieving damped".equals(W3Skills.shareWhy(false, true, false, 0.44, 0.15))
                && W3Skills.shareWhy(false, true, true, 0.44, 0.15) == null && W3Skills.shareWhy(false, true, false, 0.10, 0.15) == null && W3Skills.shareWhy(false, false, false, 0.44, 0.15) == null;
        java.io.PrintStream out0 = System.out; java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(); int picks0 = W3Skills.sharePicks;
        System.setOut(new java.io.PrintStream(buf, true, "UTF-8")); try { W3Skills.sharePick("Comex", "Trout/salmon", "Warrior women/Al-Kharid warriors", "thieving damped"); W3Skills.sharePick("Zerodignity", "Trees", "Farmers", "log floor"); } finally { System.setOut(out0); }
        String said = new String(buf.toByteArray(), "UTF-8"); int lines = said.isEmpty() ? 0 : said.split("\n").length;
        boolean rl = W3Skills.sharePicks == picks0 + 2 && lines == 1 && said.startsWith("[JobShare] Comex takes Trout/salmon over Warrior women/Al-Kharid warriors (thieving damped, ") && said.trim().endsWith("picks=" + (picks0 + 1)) && W3Skills.line().contains(" sharePicks=");
        check("4b jobShare start share: k60's 44 of 100 starts -> " + k60[0] + " damps (6 of 60 bots on a runner did not), 0 under 20 starts, ring wraps " + ss + "; shareWhy " + wy + "; [JobShare] counts both picks, prints one per 30 s " + rl, ss && wy && rl);
        check("4b jobShare wiring: starts noted after lastJob behind SHARE_ON; undamped best / best-free tracked; the changed-pick line after the broke override and before the steer line; floored jobs remembered",
                all(sa, "lastJob = job;\r\n            if (W3Skills.SHARE_ON) W3Skills.noteStart(job.hunt == null && job.task.kind == AfkTasks.Kind.THIEVE ? 1 : job.hunt == null && job.task.kind == AfkTasks.Kind.TREE ? 2 : 0);",
                        "if (weight != w0) { W3Skills.logFloored++; if (flooredJ == null) flooredJ = new java.util.HashSet<>(); flooredJ.add(j); } }", "double shareSc = score;",
                        "if (W3Skills.SHARE_ON) { if (shareSc > shareRawScore) { shareRawScore = shareSc; shareRaw = j; } if (brokeFree && shareSc > shareRawFreeScore && freeJob(j, dist)) { shareRawFreeScore = shareSc; shareRawFree = j; } }",
                        "if (W3Skills.SHARE_ON && best != null) { Job raw = brokeFree && shareRawFree != null ? shareRawFree : shareRaw;", "if (why != null) W3Skills.sharePick(bot.getUsername(), best.label(), raw == null ? \"nothing\" : raw.label(), why);")
                        && order(sa, "double shareSc = score;", "W3Skills.thieveFactor(true") && order(sa, "if (brokeFree && bestFree != null) {", "if (W3Skills.SHARE_ON && best != null) { Job raw")
                        && order(sa, "if (W3Skills.SHARE_ON && best != null) { Job raw", "if (FightRules.STEER && best != null && steeredJ != null"));

        // 5 arrows. s59: 60 headless arrows and 30 mithril arrowtips on the Exchange, 0 arrows made.
        boolean ak = W3Skills.keep(false, true, "fletching", 53, 50) == 0 && W3Skills.keep(false, true, "fletching", 42, 50) == 0 && W3Skills.keep(false, true, "fletching", 52, 50) == 0 && W3Skills.keep(false, true, "fletching", 314, 50) == 50
                && W3Skills.keep(false, true, "fletching", 1511, 50) == 50 && W3Skills.keep(false, true, "smithing", 2359, 50) == 50 && W3Skills.keep(false, false, "fletching", 53, 50) == 50 && W3Skills.keep(true, false, "fletching", 53, 50) == 50;
        int aOld = Math.min(28, Math.min((60 - 50) / 15, (30 - 50) / 15)), aNew = Math.min(28, Math.min((60 - W3Skills.keep(false, true, "fletching", 53, 50)) / 15, (30 - W3Skills.keep(false, true, "fletching", 42, 50)) / 15));
        boolean at = W3Skills.arrowTarget(true, 882, 150, 400) == 400 && W3Skills.arrowTarget(true, 890, 200, 400) == 400 && W3Skills.arrowTarget(true, 892, 100, 400) == 100 && W3Skills.arrowTarget(true, 883, 150, 400) == 150
                && W3Skills.arrowTarget(true, 884, 900, 400) == 900 && W3Skills.arrowTarget(false, 882, 150, 400) == 150 && W3Skills.arrowTarget(true, 53, 150, 400) == 150;
        check("5 arrow rule: reserve 0 for shafts, headless, tips in fletching only " + ak + "; replay 60 headless + 30 mithril tips: batches " + aOld + " -> " + aNew + "; target 400 for bronze-adamant only " + at, ak && at && aOld <= 0 && aNew == 2);
        check("5 arrow wiring: Artisans.target raises the arrow target after the 'nobody buys it' cap; choose and buyInputs use W3Skills.keep",
                all(ar, "base = W3Skills.arrowTarget(W3Skills.ARROW_FIX, id, base, W3Skills.ARROW_TARGET);") && order(ar, "if (!active(id)) base = Math.min(base, 10);", "base = W3Skills.arrowTarget(") && order(ar, "base = W3Skills.arrowTarget(", "return base + MarketBrain.demandBoost(id, base);"));

        // 6 cheap food, through P87Fight's own rules. s59: Gnome guard (level 23), Guard (21), Black Knight (33): "held 0, need 6".
        int need = P87Fight.foodNeed(33, 40);
        boolean oldClosed = !P87Fight.gate(0, need, 0, 399) && P87Fight.gate(0, need, 0, 400);
        boolean cg = W3Skills.cheapGate(true, 33, 45, 0, need, 120, 25) && !W3Skills.cheapGate(true, 33, 45, 0, need, 119, 25) && W3Skills.cheapGate(true, 33, 45, 2, need, 70, 25) && W3Skills.cheapGate(true, 33, 45, 4, need, 0, 25)
                && !W3Skills.cheapGate(true, 46, 45, 0, need, 5000, 25) && !W3Skills.cheapGate(false, 33, 45, 0, need, 5000, 25) && !W3Skills.cheapGate(true, 10, 45, 0, 0, 5000, 25)
                && !W3Skills.cheapGate(true, 82, 45, 13, P87Fight.foodNeed(172, 110), 90000, 25);
        boolean cq = W3Skills.cheapQty(10, 4, 120, 25) == 4 && W3Skills.cheapQty(10, 4, 119, 25) == 0 && W3Skills.cheapQty(10, 4, 5000, 25) == 10 && W3Skills.cheapQty(10, -3, 45, 25) == 1 && W3Skills.cheapQty(10, 4, 10, 25) == 0 && W3Skills.cheapQty(10, 4, 120, 0) == 10;
        check("6 cheapFood: P87Fight need " + need + " for a level 33 hunt, old gate shut under 400 gp " + oldClosed + "; cheap gate opens at 120 gp, never over level 45 or flag off " + cg + "; buys what the purse less 20 allows, never under the need " + cq,
                need == 6 && oldClosed && cg && cq);
        check("6 cheapFood wiring: P87Fight.closedByFood reopens only a closed hunt behind CHEAP_FOOD; buyFood buys the partial carry only after the normal buyer bought nothing",
                all(p87, "if (closed && W3Skills.CHEAP_FOOD && W3Skills.cheapGate(true, h.npcLevel, W3Skills.CHEAP_MAX_LEVEL, foodForFight(b, h), need, Ledger.Wallet.coins(b.bot), cheapPrice())) {", "static int cheapPrice() {", "if (!FOOD || h == null) return false;")
                        && order(p87, "boolean closed = !gate(foodForFight(b, h), need, 0, Ledger.Wallet.coins(b.bot));", "if (closed && W3Skills.CHEAP_FOOD")
                        && all(sa, "if (W3Skills.CHEAP_FOOD && npcLevel <= W3Skills.CHEAP_MAX_LEVEL) {", "int nb = W3Skills.cheapQty(want, P87Fight.foodNeed(npcLevel, effectiveCombat()) - 2 - have, Ledger.Wallet.coins(bot), cp);", "if (nb > 0 && Ledger.Wallet.pay(bot, (long) cp * nb))")
                        && sa.contains("for the trip\"); return; }\r\n            }\r\n            if (W3Skills.CHEAP_FOOD && npcLevel <= W3Skills.CHEAP_MAX_LEVEL) {") && order(sa, "void buyFood(int npcLevel) {", "if (W3Skills.CHEAP_FOOD && npcLevel <= W3Skills.CHEAP_MAX_LEVEL) {"));

        // 7 restock. s59: "ranger without arrows (9072 gp)", "(415 gp)", "(277 gp)"; "mage without runes (6090 gp)", "(921 gp)".
        long[] arrows = {7, 20, 46, 76, 172}; // bronze to adamant, guide-like prices
        boolean rq = W3Skills.restockQty(9072, 172, 200) == 52 && W3Skills.restockQty(9072, 7, 200) == 200 && W3Skills.restockQty(415, 7, 200) == 45 && W3Skills.restockQty(277, 7, 200) == 0 && W3Skills.restockQty(99, 1, 200) == 0 && W3Skills.restockQty(140, 0, 200) == 40;
        boolean rt = W3Skills.restockTier(9072, arrows, 4) == 4 && W3Skills.restockTier(9072, arrows, 2) == 2 && W3Skills.restockTier(1000, arrows, 4) == 1 && W3Skills.restockTier(415, arrows, 4) == 0 && W3Skills.restockTier(277, arrows, 4) == -1 && W3Skills.restockTier(9072, arrows, 9) == 4;
        check("7 restock rule: quantity = purse less 100 over the price, 40 at least, capped " + rq + "; best affordable tier from the top down " + rt, rq && rt);
        check("7 restock wiring: start() runs w3Restock between equipSupplies and styleFallback behind RESTOCK and style5; no-staff mages and worn-and-loaded rangers are left alone",
                all(sa, "equipSupplies();\r\n                if (W3Skills.RESTOCK && style5()) try { if (w3Restock()) equipSupplies(); }", "boolean w3Restock() {", "if (bow && q != null && bowFires(w.getId(), q.getId())) return false;", "if (w == null || w.getId() != 1387) { W3Skills.restockNone++; return false; }",
                        "if (bot.getProperties().getAutocastSpell() != null) return false;", "while (top > 0 && !bowFires(bowId, ARROWS[top])) top--;")
                        && order(sa, "if (W3Skills.RESTOCK && style5()) try { if (w3Restock()) equipSupplies(); }", "if (style5()) try { styleFallback(); }"));

        // 8 plank run
        boolean pk = W3Skills.plankCoins(false, 15_000) == 15_000 && W3Skills.plankCoins(true, 15_000) == W3Skills.PLANK_COINS && W3Skills.PLANK_COINS <= 15_000 && W3Skills.plankRoll(false) == 5 && W3Skills.plankRoll(true) == 3 && W3Skills.plankStock(false) == 40 && W3Skills.plankStock(true) == 27;
        check("8 plank rule: flag off 15,000 / 1 in 5 / 40 logs, flag on " + W3Skills.PLANK_COINS + " / 1 in 3 / 27 logs", pk && 27L * (30 + 250) + 1_500 <= W3Skills.PLANK_COINS + 100);
        check("8 plank wiring: builder(), the roll, the stock test and the oak margin take the rule; veteran / high tier and the 90 min gap are untouched",
                all(sa, "Ledger.Wallet.coins(bot) >= W3Skills.plankCoins(W3Skills.PLANK_FIX, 15_000) && (VETERAN.getOrDefault(key, false) || HIGH_TIER.getOrDefault(key, false));", "RNG.nextInt(W3Skills.plankRoll(W3Skills.PLANK_FIX)) != 0) return false;",
                        "MarketBrain.stockOf(m[0]) >= W3Skills.plankStock(W3Skills.PLANK_FIX) &&", "long gate = m[0] == 1521 ? (W3Skills.PLANK_FIX ? 1_500 : 5_000) : 20_000;", "millNext = now + (FAST ? 120_000 : 90 * 60_000);", "wants a plank run but has no logs"));

        // 9 agility laps. A Gnome lap is 86.5 XP; level 31 -> 32 needs 1,654 XP: ten laps cannot do it, thirty can.
        boolean lp = W3Skills.lapsFor(false, 33) == 10 && W3Skills.lapsFor(true, 1) == 10 && W3Skills.lapsFor(true, 9) == 10 && W3Skills.lapsFor(true, 10) == 15 && W3Skills.lapsFor(true, 20) == 20 && W3Skills.lapsFor(true, 28) == 30 && W3Skills.lapsFor(true, 34) == 30;
        boolean mn = W3Skills.agiMinutes(10) == 45 && W3Skills.agiMinutes(15) == 58 && W3Skills.agiMinutes(20) == 70 && W3Skills.agiMinutes(30) == 95 && W3Skills.agiMinutes(60) == 95 && W3Skills.agiMinutes(5) == 45;
        check("9 agility rule: laps by level " + lp + ", minutes " + mn + "; 10 laps = 865 XP < 1,654 (31 -> 32) <= 30 laps = 2,595", lp && mn && 10 * 86.5 < 1654 && W3Skills.lapsFor(true, 31) * 86.5 >= 1654);
        check("9 agility wiring: startAgility sets the goal and the time, agilityTick ends at the goal, the default goal is 10",
                all(sa, "agiTries, agiStall, agiGoal = 10;", "agiGoal = W3Skills.lapsFor(W3Skills.AGI_LAPS, bot.getSkills().getStaticLevel(Skills.AGILITY));", "agiUntil = now + (FAST ? 8 * 60_000 : W3Skills.agiMinutes(agiGoal) * 60_000L);", "if (agiLaps >= agiGoal) {")
                        && !sa.contains("if (agiLaps >= 10)"));

        // every flag off = train-59: each hook is behind its own static, and the hourly line prints only with a flag on
        int hooks = 0; for (String src : new String[]{sa, ar, b29, p87, bl, mb}) { Matcher h = Pattern.compile("W3Skills\\.(SMELT_FIX|HERB_FIX|WW_SEED|SHARE_ON|ARROW_FIX|CHEAP_FOOD|RESTOCK|PLANK_FIX|AGI_LAPS|anyOn\\(\\))").matcher(src); while (h.find()) hooks++; }
        check("G guard: " + hooks + " flag tests in the six callers; the hourly [W3] line is behind anyOn()", hooks >= 20 && all(mb, "if (W3Skills.anyOn()) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(W3Skills.line()); } catch (Throwable ignored) { } } }")
                && !mb.contains("try { if (W3Skills.anyOn()) System.out.println(W3Skills.line()); }"));  // w4-jobshare: its own timer, so clerk.bootFair runs print it

        System.out.println("[TestW3Skills] SUMMARY " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
