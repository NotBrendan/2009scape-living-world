package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * W5 skills wave 2 lane A (the design notes section 2), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW5aSkills <addons-src>/content/custom
 * Run it twice: bare (every flag off) and with all five -D flags set (the F case checks the statics follow the properties).
 * Per fix: the pure rule (W5aSkills), and the wiring read from the source (SmartAdventurers, AfkTasks, Batch29 and
 * MarketBrain cannot class-init outside a world): each hook sits behind its own flag and train-60's line is still there
 * for the flag-off path. The in-world checks are in the design notes.
 */
public final class TestW5aSkills {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW5aSkills] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW5aSkills]   missing: " + p); return false; } return true; }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), at = read(dir, "AfkTasks.java"), b29 = read(dir, "Batch29.java"), mb = read(dir, "MarketBrain.java");

        // F: the five statics follow their properties, default off
        String[] keys = {"bots.fish.shark", "bots.cook.burn", "bots.cook.fire", "bots.fire.line", "bots.mine.power"};
        boolean[] st = {W5aSkills.SHARK, W5aSkills.COOK_BURN, W5aSkills.COOK_FIRE, W5aSkills.FIRE_LINE, W5aSkills.MINE_POWER};
        boolean follow = true; int on = 0; for (int i = 0; i < keys.length; i++) { if (st[i] != Boolean.getBoolean(keys[i])) follow = false; if (st[i]) on++; }
        check("F flags: 5 statics follow their -D properties (" + on + " on), anyOn " + W5aSkills.anyOn() + ", line: " + W5aSkills.flags(),
                follow && W5aSkills.anyOn() == (on > 0) && W5aSkills.flags().split(" ").length == (on == 0 ? 1 : on) && W5aSkills.line().startsWith("[W5a] on=["));

        // 1 Fishing. AfkTasks.Task.HARPOON products {359 tuna, 371 swordfish, 383 shark}; CAGE {377}. s59-like board: no shark in stock
        // (big bounty), swordfish wanted a little, tuna in surplus, lobster wanted: flag off the 35 harpoon row carries the shark bounty
        // it can never catch and always beats cage; flag on it weighs tuna/swordfish only and cage wins.
        int[] harpoon = {359, 371, 383}, cage = {377};
        Map<Integer, Double> bounty = new HashMap<>(); bounty.put(383, 2.0); bounty.put(371, 0.5); bounty.put(359, 0.9); bounty.put(377, 0.6);
        Set<Integer> surplus = new HashSet<>(); surplus.add(359);
        java.util.function.IntToDoubleFunction b = id -> bounty.getOrDefault(id, 0.0); java.util.function.IntPredicate s = surplus::contains;
        double offH = W5aSkills.fishWeight(W5aSkills.harpoonFish(false, false, harpoon), b, s), onH = W5aSkills.fishWeight(W5aSkills.harpoonFish(true, false, harpoon), b, s);
        double onS = W5aSkills.fishWeight(W5aSkills.harpoonFish(true, true, harpoon), b, s), cg = W5aSkills.fishWeight(cage, b, s);
        check("1 fish weights: off harpoon " + offH + " > cage " + cg + "; on harpoon " + onH + " < cage, shark row " + onS + "; lobster weight > 0",
                near(offH, 2.0) && near(onH, 0.5) && near(onS, 2.0) && near(cg, 0.6) && cg > 0 && offH > cg && onH < cg);
        check("1 fish rows: one row per fish group with its level (harpoon 35: tuna+swordfish, shark 76: shark), spot rules, cap",
                java.util.Arrays.equals(W5aSkills.harpoonFish(true, false, harpoon), new int[]{359, 371}) && java.util.Arrays.equals(W5aSkills.harpoonFish(true, true, harpoon), new int[]{383})
                        && W5aSkills.harpoonFish(false, true, harpoon) == harpoon && W5aSkills.SHARK_LEVEL == 76 && W5aSkills.HARPOON_LEVEL == 35 && W5aSkills.SHARK_CAP >= 1 && W5aSkills.SHARK_CAP <= 4
                        && W5aSkills.spotRule(false, true) == 0 && W5aSkills.spotOk(0, true) && W5aSkills.spotOk(0, false) && W5aSkills.spotOk(1, true) && !W5aSkills.spotOk(1, false) && !W5aSkills.spotOk(2, true) && W5aSkills.spotOk(2, false));
        check("1 fish wiring: JOBS adds the shark row behind SHARK, jobWeight/capacity/crowd/start/nearestNpc/variant hooks, old lines kept",
                all(sa, "if (W5aSkills.SHARK && task == AfkTasks.Task.HARPOON) {", "Job s = new Job(task, skill, W5aSkills.SHARK_LEVEL);",
                        "if (W5aSkills.SHARK && j.prodOv != null) return W5aSkills.fishWeight(", "for (int id : j.task.products) if (id != 995 && !MarketBrain.isSurplus(id)) w = Math.max(w, MarketBrain.bounty(id));",
                        "if (W5aSkills.SHARK && j.sharkRow) return W5aSkills.SHARK_CAP;", "int busy = j.hunt != null ? crowdHunt(j.hunt) : W5aSkills.SHARK ? crowdJob(j) : crowd(j.task);",
                        "if (W5aSkills.SHARK && job.sharkRow) { spotAt = AfkTasks.CATH_BIGNET.at;", "int[] products() { return hunt != null ? hunt.products : prodOv != null ? prodOv : task.products; }")
                        && all(at, "if (w5SpotRule != 0 && !W5aSkills.spotOk(w5SpotRule, has(BIGNET_SPOTS, n.getId()))) continue;", "if (task == Task.BIG_NET && !has(BIGNET_SPOTS, n.getId())) continue;")
                        && all(b29, "if (W5aSkills.SHARK) { c.prodOv = k.prodOv;"));

        // 2 Cooking burn rate. Engine (server.jar RandomFunction.getSkillSuccessChance on CookableItems, run 11-19):
        // shrimp L1 range 50.390625, trout L30 fire 73.828125, lobster L40 range 60.546875, lobster L74 range 100,
        // swordfish L45 range 60.9375, shark L80 range 73.4375, shark L90 fire 71.875, tuna L60 96.484375, swordfish L45 fire 55.078125.
        boolean eng = near(W5aSkills.cookChance(317, 1, false), 50.390625) && near(W5aSkills.cookChance(335, 30, true), 73.828125) && near(W5aSkills.cookChance(377, 40, false), 60.546875)
                && near(W5aSkills.cookChance(377, 74, false), 100.0) && near(W5aSkills.cookChance(371, 45, false), 60.9375) && near(W5aSkills.cookChance(383, 80, false), 73.4375)
                && near(W5aSkills.cookChance(383, 90, true), 71.875) && near(W5aSkills.cookChance(359, 60, false), 96.484375) && near(W5aSkills.cookChance(371, 45, true), 55.078125)
                && W5aSkills.cookChance(9999, 50, false) == -1;
        check("2 burn table: 9 engine values (5+ levels, fire and range) match, unknown fish -1", eng);
        // the rule: flag off = train-60 (35 - (lvl - req)), flag on = engine roll; lobster at 40: old 35% burn, engine ~39.5%; at 74 old 1%, engine 0%
        boolean br = W5aSkills.burnt(false, 377, 40, 40, false, 99.9, 34) && !W5aSkills.burnt(false, 377, 40, 40, false, 0, 35) && !W5aSkills.burnt(false, 377, 75, 40, false, 99.9, 0)
                && W5aSkills.burnt(true, 377, 40, 40, false, 61.0, 99) && !W5aSkills.burnt(true, 377, 40, 40, false, 60.0, 0) && !W5aSkills.burnt(true, 377, 74, 40, false, 99.99, 0)
                && W5aSkills.burnt(true, 9999, 10, 10, false, 0, 34) && !W5aSkills.burnt(true, 9999, 10, 10, false, 99, 35);
        int burns = 0; java.util.Random rng = new java.util.Random(7); for (int i = 0; i < 20000; i++) if (W5aSkills.burnt(true, 383, 80, 80, false, rng.nextDouble() * 100, rng.nextInt(100))) burns++;
        check("2 burn rule: off = train-60, on = engine roll, unknown fish keeps the old rule; shark at 80 burns " + burns / 200.0 + "% (engine 26.6%)", br && burns > 5000 && burns < 5650);
        check("2 burn wiring: cook() takes the rule behind COOK_BURN for bots, old roll line kept for flag off, burnt = 0 xp only with the flag",
                all(at, "boolean w5 = W5aSkills.COOK_BURN && p.isArtificial();", "boolean burnt = w5 ? W5aSkills.burnt(true, it.getId(), lvl, c[1], w5Fire, RNG.nextDouble() * 100, RNG.nextInt(100)) : RNG.nextInt(100) < Math.max(0, 35 - (lvl - c[1]));",
                        "p.isArtificial() ? (w5 && burnt ? 0 : 30) : burnt ? 0 : cookXp(it.getId())", "if (W5aSkills.COOK_BURN && (w5Burnt > 0 || cooked > 0)) System.out.println(\"[W5a] cook "));

        // 3 Cooking on a fire: Barbarian Village lure spot 3104,3424 to the nearest range (3211,3215) is 235 tiles: fire;
        // Catherby 2837,3432 to 2817,3443 is 23: range.
        double barb = Math.hypot(3104 - 3211, 3424 - 3215), cath = Math.hypot(2837 - 2817, 3432 - 3443);
        boolean fr = W5aSkills.fireOrRange(true, barb, true, true, true) && !W5aSkills.fireOrRange(true, cath, true, true, true) && !W5aSkills.fireOrRange(false, barb, true, true, true)
                && !W5aSkills.fireOrRange(true, barb, false, true, true) && !W5aSkills.fireOrRange(true, barb, true, false, true) && !W5aSkills.fireOrRange(true, barb, true, true, false)
                && W5aSkills.fireOrRange(true, 999, true, true, true) && W5aSkills.wantKit(true, true, barb) && !W5aSkills.wantKit(true, true, cath) && !W5aSkills.wantKit(true, false, barb) && !W5aSkills.wantKit(false, true, barb);
        check("3 fire or range: far range (" + (int) barb + ") -> fire, near (" + (int) cath + ") -> range; no tinderbox/log/tile or flag off -> range; kit only for far cook trips", fr);
        check("3 fire wiring: the full-pack cook branch tries fireCook behind COOK_FIRE (bots), the range line kept; the kit at the fish job start",
                all(at, "if (W5aSkills.COOK_FIRE && p.isArtificial() && fireCook(r)) return;", "if (r != null) { processing = \"cook\"; goTo(State.TO_FURNACE, r); return; }", "new content.global.skill.firemaking.FireMakingPulse(p, log, null)", "processing = \"cook\"; w5Fire = true; state = State.SMELTING;")
                        && all(sa, "if (W5aSkills.COOK_FIRE && r.cookHaul) w5FireKit(p, spotAt);", "Ledger.consume(p, 1511, 1, \"firemaking\")"));

        // 4 Fire line: best surplus log the bot can light, stock kept over 50; quantity capped by pack, hour and purse; 2 a bank
        // a61 seed board: yew 214 (target 200), maple 50, willow 50, oak 116, logs 389 (logs/oak/willow/maple target 150). train-61 needed
        // 3x the target (isSurplus): nothing qualified, 0 lines. Now: what sits over the target, a pack of it.
        int[] stock = {214, 50, 50, 116, 389}, target = {200, 150, 150, 150, 150};
        boolean ll = W5aSkills.lineLog(60, stock, target) == 1511 && W5aSkills.lineLog(1, stock, target) == 1511 && W5aSkills.lineOver(389, 150) == 239
                && W5aSkills.lineLog(60, new int[]{227, 0, 0, 0, 0}, target) == 1515 && W5aSkills.lineLog(60, new int[]{226, 0, 0, 0, 0}, target) == 0
                && W5aSkills.lineLog(59, new int[]{999, 0, 0, 0, 0}, target) == 0 && W5aSkills.lineOver(60, 10) == 10 && W5aSkills.lineOver(40, 150) == 0;
        boolean lq = W5aSkills.lineQty(0, 1_000_000, 10) == 27 && W5aSkills.lineQty(W5aSkills.LINE_HOUR - 10, 1_000_000, 10) == 10 && W5aSkills.lineQty(W5aSkills.LINE_HOUR, 1_000_000, 10) == 0
                && W5aSkills.lineQty(0, 400, 10) == 10 && W5aSkills.lineQty(0, 100, 10) == 0 && W5aSkills.lineSeat(0) && W5aSkills.lineSeat(W5aSkills.LINE_BANK_CAP - 1) && !W5aSkills.lineSeat(W5aSkills.LINE_BANK_CAP);
        check("4 fire line rule: log pick by level/over-target pack/floor 50 " + ll + ", quantity pack/hour/purse and bank seats " + lq + " (hour " + W5aSkills.LINE_HOUR + ", per bank " + W5aSkills.LINE_BANK_CAP + ")", ll && lq);
        check("4 fire line wiring: trip end calls planFireLine behind FIRE_LINE after the plank run; logs bought with Ledger.consume; the players' BANK_LOOP BURN runner",
                all(sa, "if (W5aSkills.FIRE_LINE && planFireLine(now)) return;", "int took = Ledger.consume(p, log, n, \"firemaking\");", "AfkTasks.begin(p, AfkTasks.Task.BANK_LOOP, true, false, false, false, false, 0, \"keep\", false, false, 0, at, AfkTasks.Process.BURN, \"\", true);", "System.out.println(\"[Fire] line ")
                        && sa.indexOf("if (planPlankRun(now)) return;") < sa.indexOf("if (W5aSkills.FIRE_LINE && planFireLine(now)) return;"));

        // 5 Mining power: only with iron in glut and Mining under 60; the iron job's zero weight becomes 0.3
        // a61: iron ore stock 8-18 in the seed against target 150; train-61 needed isSurplus (450): 0 power trips. Now glut = stock >= target.
        boolean gl = W5aSkills.ironGlut(150, 150) && !W5aSkills.ironGlut(149, 150) && !W5aSkills.ironGlut(10, 0);
        check("5 power glut: stock at the target counts (bounty 0), under it not", gl);
        boolean pw = W5aSkills.power(true, true, 59) && !W5aSkills.power(true, true, 60) && !W5aSkills.power(true, false, 30) && !W5aSkills.power(false, true, 30)
                && W5aSkills.powerWeight(true, true, 0, true, 40) == W5aSkills.POWER_WEIGHT && W5aSkills.powerWeight(true, true, 0.7, true, 40) == 0.7 && W5aSkills.powerWeight(true, false, 0, true, 40) == 0
                && W5aSkills.powerWeight(false, true, 0, true, 40) == 0 && W5aSkills.powerWeight(true, true, 0, true, 65) == 0;
        check("5 power rule: glut and < 60 only; weight 0 -> " + W5aSkills.POWER_WEIGHT + " for the iron job alone", pw);
        check("5 power wiring: chooseJob lifts the iron weight behind MINE_POWER, the runner is built in drop mode, its drops vanish, its beast stores none; bank mode line kept",
                all(sa, "if (W5aSkills.MINE_POWER && j.hunt == null && j.task == AfkTasks.Task.IRON && weight <= 0", "W5aSkills.ironGlut(MarketBrain.stockOf(W5aSkills.IRON_ORE), MarketBrain.target(W5aSkills.IRON_ORE))", "AfkTasks.Runner r = new AfkTasks.Runner(p, job.task, !w5Power, w5Power ? tripMax : Math.min(tripMax, Math.max(Math.min(14, tripMax), want)), buries, true);",
                        "final boolean w5Power = W5aSkills.MINE_POWER &&", "System.out.println(\"[W5a] power ")
                        && all(at, "if (w5Vanish) { W5aSkills.powerDropped += n; continue; }", "if (Bob.ON && bob != null && bob.getTicks() > 400 && !(W5aSkills.MINE_POWER && w5Vanish) && Bob.store(this) > 0) return;", "GroundItem g = GroundItemManager.create(new Item(it.getId(), n), p.getLocation(), p);"));

        // G: every hook is behind its own static; the hourly line only prints with a flag on
        int hooks = 0; for (String src : new String[]{sa, at, b29, mb}) { java.util.regex.Matcher h = java.util.regex.Pattern.compile("W5aSkills\\.(SHARK|COOK_BURN|COOK_FIRE|FIRE_LINE|MINE_POWER|anyOn\\(\\))").matcher(src); while (h.find()) hooks++; }
        check("G guard: " + hooks + " flag tests in the four callers; the hourly [W5a] line is behind anyOn()", hooks >= 16 && all(mb, "if (W5aSkills.anyOn()) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(W5aSkills.line()); } catch (Throwable ignored) { } } }") && !mb.contains("try { if (W5aSkills.anyOn()) System.out.println(W5aSkills.line()); }"));

        System.out.println("[TestW5aSkills] SUMMARY " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
