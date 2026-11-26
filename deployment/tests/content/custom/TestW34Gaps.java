package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * W34 gaps (coverage-strict 2026-11-22, targets 7-9): -Dbots.style.defShare2, -Dbots.chains.smeltNudge, -Dartisans.smithSmelt,
 * all default off. Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW34Gaps <addons-src>/content/custom
 * Run it bare (flags off) and with the three -D flags set. The pure rules (W34Skills) are called; the wiring is read from the
 * source (SmartAdventurers, Batch29 and Artisans cannot class-init outside a world), including that each hook sits behind its flag.
 */
public final class TestW34Gaps {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW34Gaps] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8).replace("\r\n", "\n"); }
    static int count(String src, String what) { return src.split(Pattern.quote(what), -1).length - 1; }
    static boolean order(String src, String first, String second) { int a = src.indexOf(first), b = src.indexOf(second, Math.max(0, a)); return a >= 0 && b > a; }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), b29 = read(dir, "Batch29.java"), art = read(dir, "Artisans.java"), mb = read(dir, "MarketBrain.java");
        boolean d2 = Boolean.getBoolean("bots.style.defShare2"), nu = Boolean.getBoolean("bots.chains.smeltNudge"), ss = Boolean.getBoolean("artisans.smithSmelt");
        boolean f2 = Boolean.getBoolean("bots.chains.smeltFix2"); String at = read(dir, "AfkTasks.java");
        System.out.println("[TestW34Gaps] flags: smeltFix2=" + f2 + " | defShare2=" + d2 + " smeltNudge=" + nu + " smithSmelt=" + ss);

        // ---------------------------------------------------------------- flags
        check("F the three statics follow their properties (default off)", W34Skills.DEF_SHARE2 == d2 && W34Skills.SMELT_NUDGE == nu && W34Skills.SMITH_SMELT == ss);
        check("F anyOn and the hourly line name only the flags that are on", W34Skills.anyOn() == (d2 || nu || ss || f2) && W34Skills.SMELT_FIX2 == f2 && W34Skills.flags().contains("smeltFix2") == f2
                && W34Skills.flags().contains("defShare2") == d2 && W34Skills.flags().contains("smeltNudge") == nu && W34Skills.flags().contains("smithSmelt") == ss
                && W34Skills.line().startsWith("[W34] on=[" + W34Skills.flags() + "] longTrips="));
        check("F the hourly timer is scheduled only behind anyOn", count(mb, "W34Skills.line()") == 1 && mb.contains("if (W34Skills.anyOn()) TIMER.schedule("));

        // ---------------------------------------------------------------- 7 defShare2
        check("7 gap 5: Defence 25 under Ranged 31 is longrange on any trip (base rule wants 10+)",
                W34Skills.stanceWhy(25, 31, 1, 2) == 1 && W34Skills.stanceWhy(25, 31, 2, 2) == 1 && W5bSkills.rangerStance(25, 31).equals("rangeaccurate"));
        check("7 share: Defence 1-5 under Ranged is longrange on every 2nd trip only", W34Skills.stanceWhy(26, 31, 1, 2) == 0 && W34Skills.stanceWhy(26, 31, 2, 2) == 2
                && W34Skills.stanceWhy(30, 31, 3, 2) == 0 && W34Skills.stanceWhy(30, 31, 4, 2) == 2 && W34Skills.stanceWhy(30, 31, 3, 3) == 2);
        check("7 Defence at or above Ranged stays accurate", W34Skills.stanceWhy(31, 31, 2, 2) == 0 && W34Skills.stanceWhy(60, 31, 4, 2) == 0 && W34Skills.rangerStance2(31, 31, 2, 2).equals("rangeaccurate"));
        check("7 rangerStance2 names the engine styles", W34Skills.rangerStance2(1, 40, 1, 2).equals("longrange") && W34Skills.rangerStance2(38, 40, 1, 2).equals("rangeaccurate"));
        int share = 0; for (int t = 1; t <= 20; t++) if (W34Skills.stanceWhy(38, 40, t, W34Skills.DEF_EVERY) == 2) share++;
        check("7 the share over 20 trips is 20 / every (default 2 -> 10)", share == 20 / W34Skills.DEF_EVERY);
        check("7 wiring: the call site takes either flag; the new rule and its line sit behind DEF_SHARE2; the base rule line is kept",
                sa.contains("if (W5bSkills.DEF_SHARE && styleOf(bot) == RANGED) try { w5bDefShare(); }") && sa.contains("if (W34Skills.DEF_SHARE2 && !W5bSkills.DEF_SHARE && styleOf(bot) == RANGED) try { w5bDefShare(); }")
                && order(sa, "String want = W5bSkills.rangerStance(bot.getSkills().getStaticLevel(Skills.DEFENCE), bot.getSkills().getStaticLevel(Skills.RANGE));", "if (W34Skills.DEF_SHARE2) {")
                && order(sa, "if (W34Skills.DEF_SHARE2) {", "if (\"longrange\".equals(want) == isLong) return;")
                && count(sa, "W34Skills.") == 7 && count(sa, "\"longrange\"") == 2 && count(sa, "w34RangedTrips") == 3);
        int lt = W34Skills.longTrips; W34Skills.longTrip("TestBot", 20, 31, 1); W34Skills.longTrip("TestBot", 30, 31, 2);
        check("7 longTrip counts by reason", W34Skills.longTrips == lt + 2 && W34Skills.longByGap >= 1 && W34Skills.longByShare >= 1);

        // ---------------------------------------------------------------- 8 smeltNudge
        check("8 flag off: 0.9 x demand (train-75), whatever the counter says", near(W34Skills.smeltFactor(false, 1.25, 4, 4), 1.125) && near(W34Skills.smeltFactor(false, 0.8, 4, 4), 0.72));
        check("8 on, bars in demand: full weight 1.25 (was 1.125)", near(W34Skills.smeltFactor(true, 1.25, 0, 4), 1.25));
        check("8 on, not in demand: every 4th look passes at 1.05, the rest stay at 0.72", near(W34Skills.smeltFactor(true, 0.8, 4, 4), 1.05) && near(W34Skills.smeltFactor(true, 0.8, 8, 4), 1.05)
                && near(W34Skills.smeltFactor(true, 0.8, 1, 4), 0.72) && near(W34Skills.smeltFactor(true, 0.8, 3, 4), 0.72) && near(W34Skills.smeltFactor(true, 0.8, 0, 4), 0.72));
        int l0 = W34Skills.nudgeLooks; int a = W34Skills.nextLook(0.8), b = W34Skills.nextLook(1.25), c = W34Skills.nextLook(0.8);
        check("8 the look counter steps only on not-in-demand looks", a == l0 + 1 && b == 0 && c == l0 + 2 && W34Skills.nudgeLooks == l0 + 2);
        check("8 wiring: one demand() call; flag off keeps score * 0.9 * dm; the nudge and the start line sit behind SMELT_NUDGE and the smelt chain",
                b29.contains("double dm = demand(c);\n            double s = score * 0.9 * dm;") && count(b29, "demand(c)") == 2
                && b29.contains("if (W34Skills.SMELT_NUDGE && c.chain == SMELT) {") && b29.contains("if (W34Skills.SMELT_NUDGE && job.chain == SMELT) try { W34Skills.smeltStart(")
                && order(b29, "if (W34Skills.SMELT_NUDGE && c.chain == SMELT) {", "if (s <= score) return null;"));

        // ---------------------------------------------------------------- 8b smeltFix2
        String stop = "            if (!finishing && stopAfter > 0 && !countFinal && collected >= stopAfter) {\n                finishing = true;\n";
        String hook = "                if (W34Skills.SMELT_FIX2 && p.isArtificial() && smeltHaul && task.kind == Kind.ROCK && bank && state == State.WORK && bestBar(false) != null) {";
        String old = "                if (bank && state == State.WORK && nearestBank() != null) { goTo(State.TO_BANK, nearestBank()); return; }\n                if (!bank || nearestBank() == null) { finish(\"Target reached.\"); return; }";
        check("8b wiring: the furnace-first hook is the first thing in the trip-target branch, behind the flag, bots and smelt hauls only; the old bank lines follow unchanged",
                at.contains(stop + hook) && order(at, hook, old) && count(at, "W34Skills.") == 2 && count(at, old) == 1);
        check("8b wiring: it needs a furnace, clears the mining plan, and goes TO_FURNACE (smelt() then banks; finishing ends the trip at the bank)",
                at.contains("if (f34 != null) { W34Skills.smeltFirst(p.getUsername(), collected, stopAfter); mineBar = null; mineOre = -1; goTo(State.TO_FURNACE, f34); return; }")
                && at.contains("if (finishing) { finish(goalMsg != null ? goalMsg + \" Haul banked.\" : \"Target reached and banked.\"); return; }"));
        int sf = W34Skills.smeltFirsts; W34Skills.smeltFirst("TestBot", 14, 14);
        check("8b smeltFirst counts and its line matches the fire regex", W34Skills.smeltFirsts == sf + 1
                && Pattern.compile("^\\[W34\\] smeltFix2 .+ takes \\d+ ore to the furnace before the bank \\(").matcher("[W34] smeltFix2 Bob 7 takes 14 ore to the furnace before the bank (trip target 14)").find());

        // ---------------------------------------------------------------- 9 smithSmelt
        check("9 no smelt at or over the bar target", W34Skills.smeltTimes(200, 200, 900, 850) == 0 && W34Skills.smeltTimes(250, 200, 900, 850) == 0 && W34Skills.smeltTimes(0, 0, 900, 850) == 0);
        check("9 no smelt unless ore (bars' worth) is above the bar stock", W34Skills.smeltTimes(100, 200, 100, 50) == 0 && W34Skills.smeltTimes(100, 200, 99, 49) == 0 && W34Skills.smeltTimes(100, 200, 101, 51) == 28);
        check("9 capped by the gap to target, by 28 and by ore above the 50 reserve", W34Skills.smeltTimes(190, 200, 500, 450) == 10 && W34Skills.smeltTimes(0, 200, 500, 450) == 28
                && W34Skills.smeltTimes(0, 200, 60, 10) == 10 && W34Skills.smeltTimes(0, 200, 40, -10) == 0);
        check("9 score: the gap rule without jitter, +5 only on the smith's turn", near(W34Skills.smeltScore(0, 200, 90, false), 4.0) && near(W34Skills.smeltScore(100, 200, 90, false), 0.5)
                && near(W34Skills.smeltScore(100, 200, 90, true), 5.5) && W34Skills.smeltScore(0, 200, 90, true) < 10);
        int t0 = W34Skills.smithTurns, turns = 0; for (int i = 0; i < 3 * W34Skills.SMITH_EVERY; i++) if (W34Skills.smithTurn()) turns++;
        check("9 the smith's smelt turn is every SMITH_EVERY-th plan (default 3)", turns == 3 && W34Skills.smithTurns == t0 + 3 * W34Skills.SMITH_EVERY);
        String loop = "        for (Recipe r : RECIPES) {\n            if (W34Skills.SMITH_SMELT && trade == Trade.SMITHING && r.trade.equals(Trade.SMELTING.name())) {";
        int la = art.indexOf(loop), lb = art.indexOf("            if (!r.trade.equals(trade.name()) || r.level > skillLevel || !tradeable(r.product)) continue;", Math.max(0, la));
        String blk = la >= 0 && lb > la ? art.substring(la, lb) : "";
        check("9 wiring: the smith branch is first in the loop, behind the flag and the SMITHING trade, and ends in continue", !blk.isEmpty() && blk.trim().endsWith("continue;\n            }".trim()) && blk.contains("W34Skills.smeltTimes(") && blk.contains("- KEEP_INPUT"));
        check("9 wiring: the branch draws no RNG, marks no SHORT input, pays nothing", !blk.contains("RNG.") && !blk.contains("SHORT.") && !blk.contains("Wallet") && !blk.contains("Ledger"));
        check("9 wiring: the turn counter is asked only with the flag on and for a smith", art.contains("final boolean turn34 = W34Skills.SMITH_SMELT && trade == Trade.SMITHING && W34Skills.smithTurn();") && count(art, "W34Skills.smithTurn()") == 1);
        check("9 wiring: the [W34] line follows the bought line and sits before the home RNG draw, behind the flag",
                order(art, "\" to make \" + plan.r.amount * plan.times + \" x \" + plan.r.name);", "if (W34Skills.SMITH_SMELT && trade == Trade.SMITHING && plan.r.trade.equals(Trade.SMELTING.name())) W34Skills.smithSmelts(")
                && order(art, "W34Skills.smithSmelts(", "home = trade.spots[RNG.nextInt(trade.spots.length)];") && count(art, "W34Skills.smithSmelts(") == 1);
        check("9 the batch is bought and sold by the unchanged buyInputs / Ledger.supply (no coin made)", count(art, "Ledger.supply(bot, plan.r.product, forSale)") == 1 && count(art, "int took = Ledger.consume(who, plan.r.inIds[k], need[k], \"craft\");") == 1);

        // ---------------------------------------------------------------- fire regexes (results/w34-gaps.md)
        check("R the three fire regexes match the lines the code prints",
                Pattern.compile("^\\[W34\\] defShare2 .+ ranged trip on longrange \\(").matcher("[W34] defShare2 Bob 7 ranged trip on longrange (Defence 20, Ranged 31, gap)").find()
                && sa.contains("W34Skills.longTrip(bot.getUsername(), d34, r34, why34)")
                && Pattern.compile("^\\[W34\\] smeltNudge .+ starts a smelt chain \\(").matcher("[W34] smeltNudge Bob starts a smelt chain (iron), start 1, lifts 0").find()
                && Pattern.compile("^\\[W34\\] smithSmelt .+ \\(smith\\) smelts \\d+ x .+ bar$").matcher("[W34] smithSmelt Yuoni7 (smith) smelts 28 x Iron bar").find());

        System.out.println("[TestW34Gaps] " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
