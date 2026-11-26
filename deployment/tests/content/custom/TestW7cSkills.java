package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W7 skills wave 4 lane "chains" (the design notes section 4): plank types + nails, woodcutters who
 * fletch, the Master farmer and Knight of Ardougne pickpocket jobs. Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW7cSkills <addons-src>/content/custom
 * Run it twice: bare (every flag off) and with all three -D flags set. Per item: the pure rule (W7cSkills), and the wiring
 * read from the source (SmartAdventurers and AfkTasks cannot class-init outside a world), including that the flag-off path
 * still holds train-61's code. The in-world checks are in the design notes.
 */
public final class TestW7cSkills {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW7cSkills] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW7cSkills]   missing: " + p); return false; } return true; }
    static boolean order(String src, String first, String second) { int a = src.indexOf(first), b = src.indexOf(second, Math.max(0, a)); return a >= 0 && b > a; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), at = read(dir, "AfkTasks.java"), mb = read(dir, "MarketBrain.java"), hk = read(dir, "W7cHooks.java"), rec = read(dir, "artisan-recipes.json");

        // F: the three statics follow their properties, default off
        String[] keys = {"bots.plankTypes", "bots.fletch.own", "bots.thieve.ardy"};
        boolean[] st = {W7cSkills.PLANK_TYPES, W7cSkills.FLETCH_OWN, W7cSkills.THIEVE_ARDY};
        boolean follow = true; int on = 0; for (int i = 0; i < keys.length; i++) { if (st[i] != Boolean.getBoolean(keys[i])) follow = false; if (st[i]) on++; }
        check("F flags: 3 statics follow their -D properties (" + on + " on), anyOn " + W7cSkills.anyOn() + ", line names them: [" + W7cSkills.flags() + "]",
                follow && W7cSkills.anyOn() == (on > 0) && W7cSkills.line().startsWith("[W7c] on=[" + W7cSkills.flags() + "]") && W7cSkills.flags().split(" ").length == Math.max(1, on));

        // ---------------------------------------------------------------- Construction: plankTypes
        boolean lv = W7cSkills.plankLevel(960) == 1 && W7cSkills.plankLevel(8778) == 15 && W7cSkills.plankLevel(8780) == 35 && W7cSkills.plankLevel(8782) == 50
                && W7cSkills.plankOk(true, 8778, 15) && W7cSkills.plankOk(true, 8778, 1) && !W7cSkills.plankOk(true, 8780, 34) && !W7cSkills.plankOk(true, 8782, 49) && W7cSkills.plankOk(true, 960, 1)
                && W7cSkills.plankOk(false, 8782, 1)
                && W7cSkills.plainFirst(true, 1) && W7cSkills.plainFirst(true, 14) && !W7cSkills.plainFirst(true, 15) && !W7cSkills.plainFirst(false, 1);
        check("C plank levels: teak 35, mahogany 50; under 15 plain planks are tried first and oak is never refused (the fallback when nails cannot be had); flag off: no level rule, no plain-first", lv);
        boolean xp = W7cSkills.plankXp(false, 960) == 30.0 && W7cSkills.plankXp(false, 8782) == 30.0 && 27 * W7cSkills.plankXp(false, 8778) == 27 * 30.0
                && W7cSkills.plankXp(true, 960) == 29.0 && W7cSkills.plankXp(true, 8778) == 60.0 && W7cSkills.plankXp(true, 8780) == 90.0 && W7cSkills.plankXp(true, 8782) == 140.0
                && W7cSkills.plankXp(true, 1) == 30.0;
        check("C plank XP: 29 / 60 / 90 / 140 a plank by type; flag off: exactly the old 30.0 for every type", xp);
        boolean nails = W7cSkills.nailsPer(true, 960) == W7cSkills.NAILS_PER && W7cSkills.nailsPer(true, 8778) == 0 && W7cSkills.nailsPer(false, 960) == 0
                && W7cSkills.buildable(27, 27, 1) == 27 && W7cSkills.buildable(27, 20, 1) == 20 && W7cSkills.buildable(27, 0, 1) == 0 && W7cSkills.buildable(27, 0, 0) == 27 && W7cSkills.buildable(10, 9, 2) == 4
                && W7cSkills.nailPick(new int[]{100, 500, 30, 0, 0, 0}, new int[]{4, 9, 2, 50, 90, 200}, 27) == 2
                && W7cSkills.nailPick(new int[]{100, 500, 20, 0, 0, 0}, new int[]{4, 9, 2, 50, 90, 200}, 27) == 0
                && W7cSkills.nailPick(new int[]{10, 10, 10, 0, 0, 0}, new int[]{4, 9, 2, 50, 90, 200}, 27) == -1;
        check("C nails: 1 a plain plank, none for oak and better (or flag off); the build is capped by nails held; the cheapest type with enough stock is bought", nails);
        int[] st0 = {0, 0, 0, 0, 0, 0}, st1 = {200, 0, 0, 0, 0, 0}, pr = {5, 9, 20, 50, 90, 200};
        int[] p1 = W7cSkills.plainPlan(40, 0, true, 0, 30, st1, pr, 10_000, 1_500);   // bank logs, nails bought
        int[] p2 = W7cSkills.plainPlan(0, 30, true, 500, 30, st0, pr, 10_000, 1_500);  // logs bought, bank nails
        int[] p3 = W7cSkills.plainPlan(40, 0, true, 500, 30, st0, pr, 10_000, 1_500);  // no nails anywhere
        int[] p4 = W7cSkills.plainPlan(40, 12, false, 500, 30, st1, pr, 10_000, 1_500); // away from the GE: the bank's 12 nails
        int[] p5 = W7cSkills.plainPlan(5, 100, false, 500, 30, st1, pr, 10_000, 1_500); // too few logs, not at the GE
        int[] p6 = W7cSkills.plainPlan(40, 0, true, 0, 30, st1, pr, 4_000, 1_500);     // 27 x 100 fee + 27 x 5 nails + 1,500 > 4,000
        int[] p7 = W7cSkills.plainPlan(0, 0, true, 26, 30, st1, pr, 10_000, 1_500);    // 26 logs in stock: under a pack
        boolean plan = p1 != null && p1[0] == 27 && p1[1] == 0 && p1[2] == 0 && p1[3] == 27
                && p2 != null && p2[0] == 27 && p2[1] == 27 && p2[2] == -1 && p2[3] == 0
                && p3 == null && p4 != null && p4[0] == 12 && p4[1] == 0 && p4[3] == 0 && p5 == null && p6 == null && p7 == null;
        check("C plain plank plan: bank logs or 27 bought at the GE; nails from the bank, else one type from stock; no nails = no run; the purse covers logs, nails, the fee and 1,500 over", plan);
        boolean smith = true; for (int id : W7cSkills.NAILS) if (!Pattern.compile("\"trade\":\"smithing\",\"product\":" + id + ",").matcher(rec).find()) { smith = false; System.out.println("[TestW7cSkills]   no smithing recipe for nail " + id); }
        check("C recipe file: every nail type the builders buy is made by the smiths (" + W7cSkills.NAILS.length + " types)", smith);
        check("C wiring: both MILL loops gain the level rule behind the flag; the plain run is the last resort before the old 'no logs' line; the mill and the build step know the plain row",
                all(sa, "(m[0] == 1521 || coins >= 40_000) && (!W7cSkills.PLANK_TYPES || W7cSkills.plankOk(m[1], bot.getSkills().getStaticLevel(Skills.CONSTRUCTION)))) { have = n; pick = m; } }",
                        "if (W7cSkills.PLANK_TYPES && !W7cSkills.plankOk(m[1], bot.getSkills().getStaticLevel(Skills.CONSTRUCTION))) continue;",
                        "if (pick == null && W7cSkills.PLANK_TYPES && !w7First && W7cHooks.plainRun(this, bot, now, coins)) return true;",
                        "final boolean w7First = W7cSkills.plainFirst(W7cSkills.PLANK_TYPES, bot.getSkills().getStaticLevel(Skills.CONSTRUCTION));",
                        "if (W7cSkills.PLANK_TYPES && w7First && W7cHooks.plainRun(this, bot, now, coins)) return true;",
                        "if (m == null && W7cSkills.PLANK_TYPES && millLog == W7cSkills.PLAIN_LOG) m = W7cSkills.PLAIN_ROW;",
                        "if (W7cSkills.PLANK_TYPES) build = W7cHooks.useNails(bot, millPlank, build);",
                        "bot.getSkills().addExperience(Skills.CONSTRUCTION, build * W7cSkills.plankXp(W7cSkills.PLANK_TYPES, millPlank), true);")
                        && order(sa, "!w7First && W7cHooks.plainRun(this, bot, now, coins)) return true;", "if (pick == null) { if (W3Skills.PLANK_FIX) { W3Skills.plankNoLogs++;")
                        && order(sa, "if (shopAt != null || !builder() || now < millNext || RNG.nextInt(W3Skills.plankRoll(W3Skills.PLANK_FIX)) != 0) return false;", "if (W7cSkills.PLANK_TYPES && w7First && W7cHooks.plainRun(this, bot, now, coins)) return true;")
                        && order(sa, "if (W7cSkills.PLANK_TYPES && w7First && W7cHooks.plainRun(this, bot, now, coins)) return true;", "for (int[] m : MILL) { int n = bot.getBank().getAmount(m[0]);")
                        && order(sa, "int build = have - sell;", "if (W7cSkills.PLANK_TYPES) build = W7cHooks.useNails(bot, millPlank, build);"));
        check("C flags-off code kept: the RNG draws of the plank run are where they were (the 1-in-N roll first, the 1-in-3 spare sale before the build), MILL has its three rows, the W3 gates are untouched",
                all(sa, "if (shopAt != null || !builder() || now < millNext || RNG.nextInt(W3Skills.plankRoll(W3Skills.PLANK_FIX)) != 0) return false;",
                        "static final int[][] MILL = {{1521, 8778, 250}, {6333, 8780, 500}, {6332, 8782, 1500}};",
                        "int sell = RNG.nextInt(3) == 0 ? Math.min(have / 5, 10) : 0;",
                        "int got = Ledger.consume(bot, pick[0], 27, \"planks\");")
                        && order(sa, "int sell = RNG.nextInt(3) == 0 ? Math.min(have / 5, 10) : 0;", "W7cHooks.useNails(bot, millPlank, build)")
                        && all(hk, "Ledger.consume(bot, id, plan[3], \"nails\")", "Ledger.consume(bot, W7cSkills.PLAIN_LOG, Math.min(plan[1], logs), \"planks\")") && !hk.contains("RNG"));

        // w22: the walk to the Exchange for a plain plank run (live 11-21/22: 8 of 9 plank rolls were away from the GE, 0 plain runs)
        boolean trip = W7cSkills.tripWorth(0, 0, 500, 30, st1, pr, 10_000, 1_500)      // nothing banked, logs and nails for sale, purse covers it
                && W7cSkills.tripWorth(40, 0, 0, 30, st1, pr, 10_000, 1_500)          // logs banked, nails only at the GE
                && !W7cSkills.tripWorth(40, 30, 500, 30, st1, pr, 10_000, 1_500)       // the run can be made from the bank: no walk
                && !W7cSkills.tripWorth(0, 0, 500, 30, st0, pr, 10_000, 1_500)         // no nails for sale
                && !W7cSkills.tripWorth(0, 0, 26, 30, st1, pr, 10_000, 1_500)          // under a pack of logs for sale
                && !W7cSkills.tripWorth(0, 0, 500, 30, st1, pr, 4_000, 1_500);         // purse too thin
        check("w22 tripWorth: a walk to the Exchange only when the plain run fails here and would work there (stock and purse)", trip);
        String ar = read(dir, "Artisans.java");
        check("w22 nail target: bronze, iron and steel nails at two packs with the flag on; base with it off, for dearer nails and for any other item; wired after the arrow target, before the demand boost",
                W7cSkills.nailTarget(true, 4819, 10) == 2 * 27 * W7cSkills.NAILS_PER && W7cSkills.nailTarget(true, 4820, 10) == W7cSkills.NAIL_TARGET && W7cSkills.nailTarget(true, 1539, 150) == Math.max(150, W7cSkills.NAIL_TARGET)
                        && W7cSkills.nailTarget(false, 4819, 10) == 10 && W7cSkills.nailTarget(true, 4822, 10) == 10 && W7cSkills.nailTarget(true, 4824, 10) == 10 && W7cSkills.nailTarget(true, 960, 10) == 10
                        && W7cSkills.NAIL_TARGET >= W7cSkills.PLANK_PACK * W7cSkills.NAILS_PER
                        && order(ar, "base = W3Skills.arrowTarget(W3Skills.ARROW_FIX, id, base, W3Skills.ARROW_TARGET);", "base = W7cSkills.nailTarget(W7cSkills.PLANK_TYPES, id, base);")
                        && order(ar, "base = W7cSkills.nailTarget(W7cSkills.PLANK_TYPES, id, base);", "return base + MarketBrain.demandBoost(id, base);"));
        check("w22 [W7c] line: geTrips= is printed only with plankTypes on (off: the line is train-71's)", W7cSkills.line().contains(" geTrips=") == W7cSkills.PLANK_TYPES && W7cSkills.line().contains(" plankLevelSkips=" + W7cSkills.plankLevelSkips + (W7cSkills.PLANK_TYPES ? " geTrips=" : " fletchRuns=")));
        check("w22 wiring: geTrip is planPlankRun's last step before 'no logs', atExchange is finishShop's first; both behind the flag, no random draw, the pinned lines untouched",
                all(sa, "if (pick == null && W7cSkills.PLANK_TYPES && W7cHooks.geTrip(this, bot, now, coins)) return true;",
                        "if (W7cSkills.PLANK_TYPES && w7PlankTrip != 0 && W7cHooks.atExchange(this, bot, System.currentTimeMillis())) return;")
                        && order(sa, "!w7First && W7cHooks.plainRun(this, bot, now, coins)) return true;", "W7cHooks.geTrip(this, bot, now, coins)) return true;")
                        && order(sa, "W7cHooks.geTrip(this, bot, now, coins)) return true;", "if (pick == null) { if (W3Skills.PLANK_FIX) { W3Skills.plankNoLogs++;")
                        && order(sa, "void finishShop() {", "W7cHooks.atExchange(this, bot, System.currentTimeMillis())) return;") && order(sa, "W7cHooks.atExchange(this, bot, System.currentTimeMillis())) return;", "if (exchangeTrip) { // at the Grand Exchange")
                        && sa.split("w7PlankTrip", -1).length - 1 == 2 && sa.split("W7cHooks.geTrip", -1).length - 1 == 1
                        && all(hk, "if (b.shopAt != null || b.w7PlankTrip > now || SmartAdventurers.nearGE(bot) || bot.getLocation().getZ() != 0) return false;", "if (!W7cSkills.tripWorth(", "long until = b.w7PlankTrip; b.w7PlankTrip = 0;",
                                "if (!plainRun(b, bot, now, Ledger.Wallet.coins(bot)))") && !hk.contains("RNG"));

        // ---------------------------------------------------------------- Fletching: fletch.own
        Map<Integer, Integer> stock = new HashMap<>();
        boolean cnt = W7cSkills.fletchCount(27, 34) == 9 && W7cSkills.fletchCount(2, 34) == 0 && W7cSkills.fletchCount(3, 34) == 1 && W7cSkills.fletchCount(27, 100) == 27 && W7cSkills.fletchCount(200, 100) == 27;
        boolean pk = W7cSkills.fletchPick(1511, 1, id -> 0, 300) == 0            // level 1: arrow shafts only
                && W7cSkills.fletchPick(1511, 10, id -> 0, 300) == 2             // all scarce: the highest level (longbow)
                && W7cSkills.fletchPick(1521, 19, id -> 0, 300) == -1            // oak under 20: bank the logs
                && W7cSkills.fletchPick(1521, 25, id -> 0, 300) == 4 && W7cSkills.fletchPick(1513, 80, id -> 0, 300) == 11
                && W7cSkills.fletchPick(6333, 99, id -> 0, 300) == -1;           // teak: nothing to fletch
        stock.put(48, 500); stock.put(50, 40); stock.put(52, 15 * 20);
        boolean pk2 = W7cSkills.fletchPick(1511, 10, id -> stock.getOrDefault(id, 0), 300) == 0; // shafts: 20 logs' worth, scarcer than 40 shortbows; longbows over the cap
        stock.put(52, 15 * 300); stock.put(50, 300);
        boolean pk3 = W7cSkills.fletchPick(1511, 10, id -> stock.getOrDefault(id, 0), 300) == -1; // everything over the cap
        boolean tab = W7cSkills.FLETCH.length == W7cSkills.FLETCH_INFO.length;
        for (int[] f : W7cSkills.FLETCH) if (!Pattern.compile("\"trade\":\"fletching\",\"product\":" + f[1] + ",[^}]*\"amount\":" + f[3] + ",\"level\":" + f[2] + ",\"inputs\":\\[\\{\"id\":" + f[0] + ",").matcher(rec).find()) { tab = false; System.out.println("[TestW7cSkills]   fletch row " + f[1] + " does not match artisan-recipes.json"); }
        check("L fletch rule: a third of the logs (9 of 27), never under 3 logs; the product is the scarcest the level allows, none over the stock cap " + cnt + " " + pk + " " + pk2 + " " + pk3, cnt && pk && pk2 && pk3);
        check("L fletch table: " + W7cSkills.FLETCH.length + " rows, each one a knife-on-log recipe of artisan-recipes.json (log, product, level, amount)", tab);
        check("L wiring: only a runner SmartAdventurers armed (w7Fletch != 0) fletches, before the old process line; deposit re-arms it; the kit is for woodcutting jobs that are not errands, chains or hunts; the engine script does the carving",
                all(at, "if (W7cSkills.FLETCH_OWN && w7Fletch != 0 && W7cHooks.fletch(this)) return;", "if (W7cSkills.FLETCH_OWN && w7Fletch > 1) { W7cHooks.fletchDone(this); w7Fletch = 1; }",
                        "if (!smeltHaul && !cookHaul && process != Process.NONE && startProcess()) return; // batch 23 T4")
                        && order(at, "W7cHooks.fletch(this)) return;", "if (!smeltHaul && !cookHaul && process != Process.NONE && startProcess()) return;")
                        && all(sa, "if (W7cSkills.FLETCH_OWN && job.hunt == null && job.chain == 0 && errandFor == null && !w5Power && job.task.kind == AfkTasks.Kind.TREE) W7cHooks.fletchKit(p, r);")
                        && all(hk, "new CraftItemWithLogScript(p, LogCraftInfo.valueOf(W7cSkills.FLETCH_INFO[row]), n).invoke();", "Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(W7cSkills.KNIFE)))", "r.baseline.merge(W7cSkills.KNIFE, 1, Integer::sum);")
                        && at.split("w7Fletch = ", -1).length - 1 == 1 && sa.split("w7Fletch", -1).length - 1 == 0);

        // ---------------------------------------------------------------- Thieving: thieve.ardy
        boolean prod = W7cSkills.ardyProducts(false, "pp_master", W7cSkills.MASTER_LOOT) == null && W7cSkills.ardyProducts(false, "pp_knight", new int[]{995}) == null
                && W7cSkills.ardyProducts(true, "pp_master", W7cSkills.MASTER_LOOT) == W7cSkills.MASTER_LOOT && W7cSkills.ardyProducts(true, "pp_knight", new int[]{995}).length == 1;
        int[] guard = {995};
        boolean other = W7cSkills.ardyProducts(false, "pp_guard", guard) == guard && W7cSkills.ardyProducts(true, "pp_guard", guard) == guard && W7cSkills.ardyProducts(false, "tree", null) == null
                && !W7cSkills.ardyKey("pp_guard") && !W7cSkills.ardyKey(null) && W7cSkills.ardyKey("pp_master") && W7cSkills.ardyKey("pp_knight");
        check("T products: the two jobs have products only with the flag on (off: null = no bot job, no market entry); every other task is passed through untouched", prod && other);
        boolean lvl = W7cSkills.ardyLevel("pp_master", 40) == 38 && W7cSkills.ardyLevel("pp_knight", 40) == 55 && W7cSkills.ardyLevel("pp_guard", 40) == 40
                && W7cSkills.ardyTrip("pp_knight") == W7cSkills.KNIGHT_PICKS * 50 && W7cSkills.ardyTrip("pp_master") == W7cSkills.MASTER_SEEDS && W7cSkills.ardyTrip("pp_guard") == 0 && W7cSkills.ardyTrip("tree") == 0;
        check("T levels and trips: master farmer 38, knight 55 (the engine's Pickpockets), guards still 40; a trip is " + W7cSkills.KNIGHT_PICKS + " knight pockets or " + W7cSkills.MASTER_SEEDS + " seeds; 0 for other jobs", lvl);
        boolean gate = W7cSkills.ardyGate(19, 20, 0, 3, 0, 12) == 1 && W7cSkills.ardyGate(20, 20, 3, 3, 0, 12) == 2 && W7cSkills.ardyGate(20, 20, 2, 3, 12, 12) == 3 && W7cSkills.ardyGate(20, 20, 2, 3, 11, 12) == 0
                && !W7cSkills.ardySeat(4, 99) && W7cSkills.ardySeat(3, 99) && W7cSkills.ARDY_SEATS <= 4;
        boolean market = !W7cSkills.marketFull(3, 0, true) && W7cSkills.marketFull(4, 0, true) && W7cSkills.marketFull(1, 3, true) && !W7cSkills.marketFull(1, 2, true) && W7cSkills.marketFull(0, 4, true)
                && !W7cSkills.marketFull(2, 0, false) && W7cSkills.marketFull(3, 0, false) && !W7cSkills.marketFull(2, 9, false)
                && W7cSkills.ardyGate(true, 50, 20, 1, 3, 3, true, 0, 6) == 2 && W7cSkills.ardyGate(true, 50, 20, 1, 3, 2, true, 0, 6) == 0   // knight: 1 + 3 stall bots = full
                && W7cSkills.ardyGate(true, 50, 20, 2, 3, 9, false, 0, 6) == 0 && W7cSkills.ardyGate(true, 50, 20, 3, 4, 0, false, 0, 6) == 2 // stalls flag off: knights alone, at most 3
                && W7cSkills.ardyGate(false, 50, 20, 2, 3, 9, true, 0, 6) == 0;                                                         // the master farmer is not in the market
        check("T market cap: knights + W5b stall bots together at most 4; with the stalls flag off knights alone at most 3; the master farmer job is outside it", market && W7cSkills.MARKET_CAP == 4 && W7cSkills.KNIGHTS_ALONE == 3);
        check("T hour cap default: 6 starts an hour per job unless -Dbots.thieve.ardy.perHour is set (" + W7cSkills.ARDY_HOUR + "): at most " + 6 * W7cSkills.KNIGHT_PICKS * W7cSkills.KNIGHT_COINS + " gp an hour from knights by default",
                Integer.getInteger("bots.thieve.ardy.perHour") != null || W7cSkills.ARDY_HOUR == 6);
        long t0 = 10_000_000L; int before = W7cSkills.ardyHourUsed("pp_knight", t0);
        W7cSkills.ardyStart("pp_knight", t0); W7cSkills.ardyStart("pp_knight", t0 + 1000);
        boolean hour = W7cSkills.ardyHourUsed("pp_knight", t0 + 2000) == before + 2 && W7cSkills.ardyHourUsed("pp_master", t0 + 2000) == 0 && W7cSkills.ardyHourUsed("pp_knight", t0 + 3_700_000L) == 0;
        check("T gate: combat 20+, " + W7cSkills.ARDY_SEATS + " seats a job (never over 4), " + W7cSkills.ARDY_HOUR + " starts an hour per job, counted per job and reset after the hour " + gate + " " + hour, gate && hour);
        boolean seeds = W7cSkills.MASTER_LOOT.length == 42 && W7cSkills.herbSeed(5291) && W7cSkills.herbSeed(5304) && !W7cSkills.herbSeed(5318);
        int herbs = 0; for (int id : W7cSkills.MASTER_LOOT) { if (W7cSkills.herbSeed(id)) herbs++; if (id == 995) seeds = false; }
        check("T seeds: the master farmer's table is seeds only, " + herbs + " herb seeds (guam to torstol) among " + W7cSkills.MASTER_LOOT.length, seeds && herbs == 14);
        check("T wiring (AfkTasks): two tasks after the stalls, hidden from the players' words, key lookup and lists; products through ardyProducts; spots and levels only with the flag",
                all(at, "MASTER_FARMER(W7cSkills.KEY_MASTER, \"Master farmers\", Kind.THIEVE, names(\"Master Farmer\"), \"Pickpocket\", W7cSkills.ardyProducts(W7cSkills.THIEVE_ARDY, W7cSkills.KEY_MASTER, W7cSkills.MASTER_LOOT), null),",
                        "KNIGHT(W7cSkills.KEY_KNIGHT, \"Knights of Ardougne\", Kind.THIEVE, names(\"Knight of Ardougne\"), \"Pickpocket\", W7cSkills.ardyProducts(W7cSkills.THIEVE_ARDY, W7cSkills.KEY_KNIGHT, ids(COINS)), null);",
                        "this.products = products == null && kind == Kind.STALL ? W5bSkills.stallProducts(W5bSkills.STALLS, key) : products;",
                        "if (t.key.equalsIgnoreCase(key) && !W7cSkills.ardyKey(t.key)) return t;",
                        "for (Task t : Task.values()) if (!W7cSkills.ardyKey(t.key)) WORDS.put(t.key, t);",
                        "if (inGroup(t, g) && !W7cSkills.ardyKey(t.key)) n++;", "if (!inGroup(t, g) || W7cSkills.ardyKey(t.key)) continue;",
                        "if (W7cSkills.THIEVE_ARDY) { NEEDS.put(Task.MASTER_FARMER,", "SPOTS.put(Task.MASTER_FARMER, new Spot[]{sp(\"the north Ardougne farmer\", 2636, 3362)});",
                        "SPOTS.put(Task.KNIGHT, new Spot[]{sp(\"the Ardougne market\", 2662, 3305)});")
                        && order(at, "ST_SEED(\"stall_seed\"", "MASTER_FARMER(W7cSkills.KEY_MASTER") && order(at, "if (W7cSkills.THIEVE_ARDY) { // W7c", "SPOTS.put(Task.MASTER_FARMER") && !at.contains("the Draynor market")
                        && at.split("Task\\.values\\(\\)", -1).length - 1 == 4 && at.split("Task\\.MASTER_FARMER|Task\\.KNIGHT", -1).length - 1 == 4);
        check("T wiring (SmartAdventurers): the level, the weight floor, the gate on the job pick / crews / errands, the trip size and the start count are all behind the flag; the food rule and jobShare's thieving share already cover every THIEVE job",
                all(sa, "task == AfkTasks.Task.WARRIOR ? 25 : W7cSkills.ardyLevel(task.key, 40); break;",
                        "if (W7cSkills.THIEVE_ARDY && j.hunt == null && W7cSkills.ardyKey(j.task.key)) weight = Math.max(weight, W7cSkills.ARDY_WEIGHT);",
                        "if (W7cSkills.THIEVE_ARDY && w7ArdyBlocked(j, combat, true)) continue;", "if (W7cSkills.THIEVE_ARDY && w7ArdyBlocked(j, combat, false)) return false;",
                        "if (W7cSkills.THIEVE_ARDY && w7ArdyBlocked(j, combat, false)) continue;",
                        "boolean full = W5bSkills.STALLS && W7cSkills.marketFull(crowd(AfkTasks.Task.KNIGHT), stallCrowd(), true);",
                        "return W7cSkills.ardyBlocked(j.task.key, combat, crowd(j.task), W5bSkills.STALLS ? stallCrowd() : 0, W5bSkills.STALLS, System.currentTimeMillis(), count);",
                        "final boolean w7Trip = W7cSkills.THIEVE_ARDY && job.hunt == null && errandFor == null && W7cSkills.ardyTrip(job.task.key) > 0;", "if (w7Trip) { tripMax = W7cSkills.ardyTrip(job.task.key); want = tripMax; }",
                        "AfkTasks.Runner r = new AfkTasks.Runner(p, job.task, !w5Power, w5Power ? tripMax : Math.min(tripMax, Math.max(Math.min(14, tripMax), want)), buries, true);",
                        "if (w7Trip) W7cSkills.ardyStart(job.task.key, System.currentTimeMillis());",
                        // the rules these jobs lean on, unchanged: food for every THIEVE job, the thieving share of job starts, the x0.1 damp
                        "boolean needFood = (job.task.kind == AfkTasks.Kind.FIGHT || job.task.kind == AfkTasks.Kind.THIEVE ||",
                        "if (W3Skills.SHARE_ON) W3Skills.noteStart(job.hunt == null && job.task.kind == AfkTasks.Kind.THIEVE ? 1 :",
                        "if (W3Skills.SHARE_ON && j.hunt == null && j.task.kind == AfkTasks.Kind.THIEVE) { w3Shares(); double tf = W3Skills.thieveFactor(true, W3Skills.thieveNow, W3Skills.THIEVE_CAP);",
                        "if (task.kind == AfkTasks.Kind.SMELT || task.products == null) continue;")
                        && order(sa, "weight = Math.max(weight, W7cSkills.ARDY_WEIGHT);", "if (weight <= 0 && !(broke && coinJob)")
                        && order(sa, "w7ArdyBlocked(j, combat, true)) continue;", "double score = weight / (1 + dist / 150.0) * (0.8 + RNG.nextDouble() * 0.4);"));

        // G: every hook is behind its own static; the hourly line prints only with a flag on
        int hooks = 0; for (String src : new String[]{sa, at, mb}) { Matcher h = Pattern.compile("W7cSkills\\.(PLANK_TYPES|FLETCH_OWN|THIEVE_ARDY)").matcher(src); while (h.find()) hooks++; }
        int calls = 0, guarded = 0;
        for (String src : new String[]{sa, at}) for (String ln : src.split("\n")) if (ln.contains("W7cHooks.")) { calls++; if (Pattern.compile("W7cSkills\\.(PLANK_TYPES|FLETCH_OWN)\\b.*W7cHooks\\.").matcher(ln).find()) guarded++; }
        check("G guard: " + hooks + " flag tests in the three callers; all " + calls + " W7cHooks calls sit on a line that tests the flag first; the hourly [W7c] line is behind anyOn()",
                hooks >= 18 && calls == 8 && guarded == calls /* w22: + geTrip, atExchange */
                        && all(mb, "if (W7cSkills.anyOn()) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(W7cSkills.line()); } catch (Throwable ignored) { } } }"));

        System.out.println("[TestW7cSkills] SUMMARY " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
