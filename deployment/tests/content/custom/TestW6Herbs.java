package content.custom;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * W6 skills wave 3 herb lane (the design notes section 3): artisans.herbSupply, bots.farming.herbs2.
 * Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW6Herbs <addons-src>/content/custom
 * Run it twice: bare (both flags off) and with -Dbots.farming=true -Dbots.farming.herbs2=true -Dartisans.herbSupply=true.
 * Pure rules (W6Herbs), the recipe-file source check, and the wiring read from the source (the callers cannot class-init
 * outside a world), including that the flag-off path still holds train-61's code.
 */
public final class TestW6Herbs {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW6Herbs] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW6Herbs]   missing: " + p); return false; } return true; }

    static final Map<Integer, int[]> MADE = new HashMap<>();
    /** Raw inputs of a product: the herblore recipes followed down to items no herblore recipe makes. */
    static void raw(int id, Set<Integer> out, int depth) {
        int[] in = MADE.get(id);
        if (in == null || depth > 6) { out.add(id); return; }
        for (int i : in) raw(i, out, depth + 1);
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), at = read(dir, "AfkTasks.java"), mb = read(dir, "MarketBrain.java"), bl = read(dir, "BotLife.java"),
                hm = read(dir, "HuntMap.java"), fa = read(dir, "Farming.java"), p84 = read(dir, "P84.java"), fs = read(dir, "FarmSave.java");

        // F: flags follow their properties, default off
        boolean s = Boolean.getBoolean("artisans.herbSupply"), h2 = Boolean.getBoolean("bots.farming") && Boolean.getBoolean("bots.farming.herbs2");
        check("F flags: SUPPLY " + W6Herbs.SUPPLY + " HERBS2 " + W6Herbs.HERBS2 + " follow their -D properties (herbs2 needs bots.farming); line: " + W6Herbs.line(null),
                W6Herbs.SUPPLY == s && W6Herbs.HERBS2 == h2 && W6Herbs.anyOn() == (s || h2) && W6Herbs.line(null).startsWith("[W6h] on=[" + W6Herbs.flags() + "]")
                        && W6Herbs.line(id -> id == 225 ? 7 : 1).endsWith("stockGrimy=14 stockLimpwurt=7 stockSnape=1 stockEggs=1"));

        // ---------------------------------------------------------------- herbSupply (a): the lead
        StringBuilder lead = new StringBuilder();
        for (int i = 0; i < W6Herbs.LEAD.length; i++) lead.append(i == 0 ? "" : ", ").append('{').append(W6Herbs.LEAD[i][0]).append(", ").append(W6Herbs.LEAD[i][1]).append('}');
        String p84flat = p84.replaceAll("\\s+", " ");
        check("a1 lead: the 12 points are the start of P84.LADDER_LEAD (tile BFS in TestP84Stuck); each step is 10 tiles or less; the last is within 10 of the druids",
                p84flat.contains("LADDER_LEAD = {" + lead) && stepsOk() && W6Herbs.dist(2924, 9848, W6Herbs.DRUID[0], W6Herbs.DRUID[1]) <= W6Herbs.DRUID_NEAR);
        // walk the lead forward from the ladder foot: every via is a later point, and it ends in reach of the druids
        int x = 2884, y = 9798, steps = 0, lastIdx = -1; boolean mono = true;
        while (steps++ < 200) {
            int[] v = W6Herbs.via(true, x, y, 0, W6Herbs.DRUID[0], W6Herbs.DRUID[1]);
            if (v == null) break;
            int idx = idx(v); if (idx < lastIdx) mono = false; lastIdx = idx;
            x += Integer.signum(v[0] - x); y += Integer.signum(v[1] - y);
        }
        boolean fwd = mono && W6Herbs.dist(x, y, W6Herbs.DRUID[0], W6Herbs.DRUID[1]) <= 12 && steps < 200;
        int fx = x, fy = y;
        x = 2930; y = 9848; steps = 0; lastIdx = 99; mono = true;
        while (steps++ < 200) {
            int[] v = W6Herbs.via(true, x, y, 0, W6Herbs.FOOT[0], W6Herbs.FOOT[1]);
            if (v == null) break;
            int idx = idx(v); if (idx > lastIdx) mono = false; lastIdx = idx;
            x += Integer.signum(v[0] - x); y += Integer.signum(v[1] - y);
        }
        boolean back = mono && W6Herbs.dist(x, y, W6Herbs.FOOT[0], W6Herbs.FOOT[1]) <= 9 && steps < 200;
        check("a2 via: from the ladder foot the walk only moves forward along the lead and ends at " + fx + "," + fy + "; from the druids back it ends at " + x + "," + y + " by the foot", fwd && back);
        check("a3 via is nothing: flag off, another goal (blue dragons 2904,9803), the surface, another floor, off the corridor, already at the druids",
                W6Herbs.via(false, 2884, 9810, 0, 2932, 9848) == null && W6Herbs.via(true, 2884, 9810, 0, 2904, 9803) == null && W6Herbs.via(true, 2884, 3400, 0, 2932, 9848) == null
                        && W6Herbs.via(true, 2884, 9810, 1, 2932, 9848) == null && W6Herbs.via(true, 2910, 9805, 0, 2932, 9848) == null && W6Herbs.via(true, 2930, 9846, 0, 2932, 9848) == null
                        && W6Herbs.via(true, 2884, 9810, 0, 2932, 9848) != null);
        check("a4 wiring: BotLife uses the W6 spot and questSpots only under the flag; the Runner via is bots only, under the flag, after the key gate's; ensureHerbSpots untouched",
                all(bl, "if (W6Herbs.SUPPLY) spots = new AfkTasks.Spot[]{AfkTasks.sp(W6Herbs.DRUID_SPOT_NAME, W6Herbs.DRUID[0], W6Herbs.DRUID[1])};", "if (W6Herbs.SUPPLY) { r.questSpots = spots; W6Herbs.gathererTrips++; }",
                        "} else if (W3Skills.HERB_FIX) {")
                        && all(at, "if (via == null && W6Herbs.SUPPLY && p.isArtificial() && dest != null) { int[] w6 = W6Herbs.via(true,",
                        "if (h.name != null && h.name.equalsIgnoreCase(\"Chaos druid\") && h.spot.getY() < 6400) { SPOTS.put(Task.HERB_DRUIDS")
                        && at.indexOf("via = keyGateVia();") < at.indexOf("W6Herbs.via(true,"));

        // ---------------------------------------------------------------- herbSupply (b), (c)
        check("b1 wiring: the Chaos druid hunt spot is curated only under the flag; the druid spot is outside the Taverley key-gate boxes (y 9785-9815) and inside entrance()'s Taverley box",
                all(hm, "if (W6Herbs.SUPPLY) CURATED.put(W6Herbs.DRUID_NAME, Location.create(W6Herbs.DRUID[0], W6Herbs.DRUID[1], 0));") && W6Herbs.DRUID[1] > 9815
                        && W6Herbs.DRUID[0] >= 2800 && W6Herbs.DRUID[0] <= 2970 && W6Herbs.DRUID[1] >= 9660 && W6Herbs.DRUID[1] <= 9900
                        && all(at, "if (x >= 2800 && x <= 2970 && y >= 9660 && y <= 9900)", "Location.create(2884, 9798, 0)}"));
        check("b2 farming harvests already go to the Exchange: farmOffload supplies every Farming.allCrops() id through the Ledger, and allCrops covers the herb table",
                all(sa, "int[] cr = Farming.allCrops();", "try { Ledger.supply(bot, id, n); } catch (Throwable ignored) { }") && all(fa, "for (int[] r : HERB_SEEDS) a[i++] = r[2]; return a; }"));
        check("c1 wiring: the limpwurt job is added after the hunts, only under the flag, at the Hill Giant hunt's spot with the task's own fight level; counters only under the flag",
                all(sa, "if (W6Herbs.SUPPLY) for (HuntMap.Hunt h : HuntMap.HUNTS) if (h.tag == null && W6Herbs.LIMP_HUNT.equals(h.name)) {", "final Job limp = new Job(AfkTasks.Task.LIMPWURT, -1, fightLevel(AfkTasks.Task.LIMPWURT));",
                        "if (W6Herbs.SUPPLY) W6Herbs.started(true,", "case HILL_GIANT: case BIGBONES: case LIMPWURT: return 35;")
                        && all(at, "LIMPWURT(\"c_limpwurt\", \"Limpwurt roots (hill giants)\", Kind.FIGHT, names(\"Hill Giant\"), null, ids(225, BIG_BONES), null),")
                        && sa.indexOf("W6Herbs.LIMP_HUNT.equals") > sa.indexOf("JOBS = all; // swap the whole list") && sa.indexOf("HuntMap.build();") < sa.indexOf("W6Herbs.LIMP_HUNT.equals"));
        int d0 = W6Herbs.druidStarts, l0 = W6Herbs.limpStarts, s0 = W6Herbs.snapeStarts;
        W6Herbs.started(false, "Chaos druid", true, true); boolean offNo = W6Herbs.druidStarts == d0 && W6Herbs.limpStarts == l0 && W6Herbs.snapeStarts == s0;
        W6Herbs.started(true, "Chaos druid", false, false); W6Herbs.started(true, null, true, false); W6Herbs.started(true, "Hill Giant", false, true);
        check("c2 started(): nothing counted with the flag off; one each for a druid hunt, a limpwurt job, a snape job", offNo && W6Herbs.druidStarts == d0 + 1 && W6Herbs.limpStarts == l0 + 1 && W6Herbs.snapeStarts == s0 + 1);

        // ---------------------------------------------------------------- recipe file: every first potion's inputs
        Map<Integer, String> names = new HashMap<>();
        for (Object o : (JSONArray) new JSONParser().parse(read(dir, "artisan-recipes.json"))) {
            JSONObject r = (JSONObject) o;
            if (!"herblore".equals(r.get("trade"))) continue;
            JSONArray in = (JSONArray) r.get("inputs"); int[] ids = new int[in.size()];
            for (int i = 0; i < ids.length; i++) { JSONObject j = (JSONObject) in.get(i); ids[i] = ((Number) j.get("id")).intValue(); names.put(ids[i], String.valueOf(j.get("name"))); }
            MADE.put(((Number) r.get("product")).intValue(), ids); names.put(((Number) r.get("product")).intValue(), String.valueOf(r.get("productName")));
        }
        boolean must = true, known = true; Set<Integer> gaps = new TreeSet<>(); StringBuilder gapNames = new StringBuilder();
        for (int p : W6Herbs.FIRST_POTIONS) {
            if (!MADE.containsKey(p)) { known = false; continue; }
            Set<Integer> rawIn = new TreeSet<>(); raw(p, rawIn, 0);
            for (int id : rawIn) if (W6Herbs.source(id) == null) {
                if (gaps.add(id)) gapNames.append(names.get(id)).append("; ");
                if (W6Herbs.has(W6Herbs.MUST_POTIONS, p)) { must = false; System.out.println("[TestW6Herbs]   no source: " + names.get(id) + " for " + names.get(p)); }
            }
        }
        check("r1 recipe file: the 7 first potions are in artisan-recipes.json; attack, strength and prayer potion have a source for every raw input; without a source (4, the note's gaps): " + gapNames,
                known && must && gaps.equals(new TreeSet<>(java.util.Arrays.asList(W6Herbs.SPIDER_EGGS, W6Herbs.UNICORN_DUST, W6Herbs.WHITE_BERRIES, W6Herbs.CHOC_DUST))));
        Set<Integer> cleaned = new TreeSet<>(); for (int[] in : MADE.values()) if (in.length == 1) cleaned.add(in[0]);
        boolean grimyAll = true; for (int g : W6Herbs.GRIMY) if (!cleaned.contains(g) || !String.valueOf(names.get(g)).startsWith("Grimy ")) grimyAll = false;
        check("r2 each of the 14 W6Herbs.GRIMY herbs has a cleaning recipe in the file, and every herbs2 crop is one of them", grimyAll && W6Herbs.GRIMY.length == 14 && cropsGrimy());

        // ---------------------------------------------------------------- herbs2
        int[][] base = {{9, 5291, 199, 0}, {14, 5292, 201, 0}, {19, 5293, 203, 0}, {26, 5294, 205, 0}, {32, 5295, 207, 0}};
        int[][] up = W6Herbs.herbSeeds(true, base);
        boolean sorted = true; for (int i = 1; i < up.length; i++) if (up[i][0] <= up[i - 1][0] || up[i][1] == up[i - 1][1]) sorted = false;
        check("h1 table: off = the same five-row array; on = 14 rows, levels rising (best last), ending torstol 85; engine levels toadflax 38, irit 44, avantoe 50, kwuarm 56, snapdragon 62, cadantine 67, lantadyme 73, dwarf weed 79",
                W6Herbs.herbSeeds(false, base) == base && up.length == 14 && sorted && up[4][1] == 5295 && up[13][0] == 85 && up[13][1] == 5304 && up[13][2] == 219
                        && up[5][0] == 38 && up[5][1] == 5296 && up[5][2] == 3049 && up[6][0] == 44 && up[6][2] == 209 && up[7][0] == 50 && up[7][2] == 211 && up[8][0] == 56 && up[8][2] == 213
                        && up[9][0] == 62 && up[9][2] == 3051 && up[10][0] == 67 && up[10][2] == 215 && up[11][0] == 73 && up[11][2] == 2485 && up[12][0] == 79 && up[12][2] == 217
                        && W6Herbs.cropOf(up, 5300) == 3051 && W6Herbs.cropOf(up, 1) == -1);
        check("h2 Farming's live table has " + Farming.HERB_SEEDS.length + " rows (5 off, 14 on); pickSeed at 32 takes ranarr over guam when held, at 99 torstol only with the flag, and falls back to what is held",
                Farming.HERB_SEEDS.length == (W6Herbs.HERBS2 ? 14 : 5) && Farming.pickSeed(32, true, id -> id == 5291 || id == 5295) == 5295
                        && Farming.pickSeed(99, true, id -> true) == (W6Herbs.HERBS2 ? 5304 : 5295) && Farming.pickSeed(99, true, id -> id == 5297 || id == 5291) == (W6Herbs.HERBS2 ? 5297 : 5291)
                        && Farming.pickSeed(43, true, id -> id == 5297 || id == 5291) == 5291 && Farming.isCrop(3051) == W6Herbs.HERBS2 && Farming.isHerbSeed(5304) == W6Herbs.HERBS2
                        && Farming.allCrops().length == Farming.ALLOT_SEEDS.length + Farming.HERB_SEEDS.length);
        check("h3 budget: off = unchanged; on = 10% of coins, 3,000 at most, never under the run's budget; afterwards never more than before the herb step",
                W6Herbs.herbBudget(false, 1_000_000, 600) == 600 && W6Herbs.herbBudget(true, 10_000, 500) == 1_000 && W6Herbs.herbBudget(true, 1_000_000, 600) == Math.max(600, W6Herbs.HERB_BUDGET_MAX)
                        && W6Herbs.herbBudget(true, 2_000, 600) == 600 && W6Herbs.herbBudget(true, -5, 0) == 0
                        && W6Herbs.budgetAfter(600, 3_000) == 600 && W6Herbs.budgetAfter(600, 250) == 250 && W6Herbs.budgetAfter(600, -1) == 0);
        int dear0 = W6Herbs.herbsTooDear;
        boolean worth = W6Herbs.seedWorth(false, 0, 1, 99_999, 1) && W6Herbs.seedWorth(true, 1, 1, 99_999, 1) && W6Herbs.seedWorth(true, 0, 1, 400, 100) && !W6Herbs.seedWorth(true, 0, 1, 401, 100) && !W6Herbs.seedWorth(true, 0, 1, 5, 0);
        int up0 = W6Herbs.herbsUp, b0 = W6Herbs.herbsBought; long sp0 = W6Herbs.herbSeedSpend;
        W6Herbs.picked(false, 5300, 50); W6Herbs.picked(true, -1, 50); boolean none = W6Herbs.herbsUp == up0 && W6Herbs.herbsBought == b0;
        W6Herbs.picked(true, 5300, 50); W6Herbs.picked(true, 5291, 0);
        check("h4 seedWorth: an owned seed always; a bought one only at 4 herbs' price or less (2 refusals counted); picked() counts a seed above ranarr and the coins spent",
                worth && W6Herbs.herbsTooDear == dear0 + 2 && none && W6Herbs.herbsUp == up0 + 1 && W6Herbs.herbsBought == b0 + 1 && W6Herbs.herbSeedSpend == sp0 + 50);
        int[][] full = W6Herbs.herbSeeds(true, base); int k0 = W6Herbs.seedsKept;
        boolean keepOff = !W6Herbs.keepSeed(false, full, 5295, 99, 0) && W6Herbs.seedsKept == k0;
        boolean keepOn = W6Herbs.keepSeed(true, full, 5295, 32, 0) && W6Herbs.keepSeed(true, full, 5304, 85, W6Herbs.SEED_KEEP - 1) && !W6Herbs.keepSeed(true, full, 5295, 31, 0) && !W6Herbs.keepSeed(true, full, 5295, 99, W6Herbs.SEED_KEEP)
                && !W6Herbs.keepSeed(true, full, 5318, 99, 0) && !W6Herbs.keepSeed(true, full, 199, 99, 0) && !W6Herbs.keepSeed(true, base, 5304, 99, 0) && !W6Herbs.keepSeed(true, null, 5295, 99, 0);
        check("h6 keepSeed: off never (nothing counted); on = a herb seed of the table at its plant level with under " + W6Herbs.SEED_KEEP + " banked (2 counted); not below the level, at the cap, an allotment seed or a herb",
                keepOff && keepOn && W6Herbs.seedsKept == k0 + 2 && W6Herbs.SEED_KEEP == Integer.getInteger("bots.farming.herbs2.keep", 10));
        check("h7 wiring: the bank sale keeps a seed only under the flag, for a smart bot, after the charm rule; the sale code below it is train-61's",
                all(at, "&& !(Summon.ON && Summon.isCharm(it.getId())) // P62", "&& !(W6Herbs.HERBS2 && p.isArtificial() && SmartAdventurers.brainOf(p) != null && W6Herbs.keepSeed(true, Farming.HERB_SEEDS, it.getId(),", "if (sell && sellable) {",
                        "if (bankC.add(part)) { p.getInventory().remove(part); moved++;"));
        check("h5 wiring: Farming's table goes through herbSeeds with the old five rows; the seed step raises and restores the budget only under the flag and still buys with farmGet (Ledger.consume, no shop); FarmSave has no W6 code",
                all(fa, "static final int[][] HERB_SEEDS = W6Herbs.herbSeeds(W6Herbs.HERBS2, new int[][]{{9, 5291, 199, 0}, {14, 5292, 201, 0}, {19, 5293, 203, 0}, {26, 5294, 205, 0}, {32, 5295, 207, 0}});")
                        && all(sa, "if (W6Herbs.HERBS2) left[0] = W6Herbs.herbBudget(true, coins0, w6Left);", "id -> farmCanSource(id, q, left[0]) && (!W6Herbs.HERBS2 || W6Herbs.seedWorth(true,",
                        "if (W10Skills.HERB_RUN && hSeed > 0) w10Seeds = w10SeedLegs(hSeed, q, left[0], w10Cand.length);", "int w10Seeds = 0;", // train-65: W10 herbRun adds seeds only under its flag (0 off)
                        "if (hSeed > 0 && !farmGet(hSeed, q + w10Seeds, false, left, took)) hSeed = -1;", "if (W6Herbs.HERBS2) { left[0] = W6Herbs.budgetAfter(w6Left, left[0]);")
                        && !fs.contains("W6Herbs"));
        check("L hourly line: MarketBrain prints it on its own timer only when a W6h flag is on", all(mb, "if (W6Herbs.anyOn()) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(W6Herbs.line(MarketBrain::stockOf)); }"));

        // t62 review fix 1 (results/t62-audit.md item 5)
        boolean bo = W6Herbs.botOnlySpot(true, true, false) && !W6Herbs.botOnlySpot(true, true, true) && !W6Herbs.botOnlySpot(false, true, false) && !W6Herbs.botOnlySpot(true, false, false);
        check("t62 fix 1 rule: the limpwurt table spot is hidden from players only under herbSupply " + bo, bo);
        int putAt = sa.indexOf("AfkTasks.SPOTS.put(AfkTasks.Task.LIMPWURT, limpAt);"), pulseAt = sa.indexOf("GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() { // t62 fix 1");
        check("t62 fix 1 wiring: SPOTS.put(LIMPWURT) and the job's JOBS swap run in a game-thread pulse after the timer's JOBS = all",
                putAt > pulseAt && pulseAt > 0 && order(sa, "JOBS = all; // swap the whole list", "if (W6Herbs.SUPPLY) for (HuntMap.Hunt h : HuntMap.HUNTS) if (h.tag == null && W6Herbs.LIMP_HUNT.equals(h.name)) {")
                        && all(sa, "List<Job> nj = new ArrayList<>(JOBS); nj.add(limp); JOBS = nj;") && sa.indexOf("SPOTS.put(AfkTasks.Task.LIMPWURT") == putAt + "AfkTasks.".length());
        check("t62 fix 1 players: ::afk limpwurt reads spotsFor(t, false) (no table spot), bots' relocate spotsFor(task, p.isArtificial())",
                all(at, "static Spot[] spotsFor(Task t, boolean bot) { return W6Herbs.botOnlySpot(W6Herbs.SUPPLY, t == Task.LIMPWURT, bot) ? null : SPOTS.get(t); }",
                        "Spot[] list = questSpots != null ? questSpots : spotsFor(task, p.isArtificial());") && count(at, "spotsFor(t, false)") == 3);

        // w9 s62fire: [W6h] lane lines naming the bot (s62: herbSupply fired, counters only, lanecall saw 0 touched bots)
        long t9 = 1_000_000L;
        boolean dueOk = W6Herbs.due("w9bot", "gatherer", t9) && !W6Herbs.due("w9bot", "gatherer", t9 + 1) && W6Herbs.due("w9bot", "via", t9 + 1)
                && !W6Herbs.due("w9bot", "gatherer", t9 + W6Herbs.SAY_EVERY - 1) && W6Herbs.due("w9bot", "gatherer", t9 + W6Herbs.SAY_EVERY) && !W6Herbs.due(null, "gatherer", t9) && !W6Herbs.due("w9bot", null, t9);
        check("w9 due(): one line per bot and kind per " + W6Herbs.SAY_EVERY / 1000 + " s, kinds apart, null never", dueOk);
        check("w9 botLine: '[W6h] <bot> <lane>: <what>' (the lane key after the name)", "[W6h] Gerald59 herbSupply: x".equals(W6Herbs.botLine("Gerald59", "herbSupply", "x")));
        int k9 = W6Herbs.seedsKept; boolean keep9 = W6Herbs.keepSeed(true, W6Herbs.herbSeeds(true, new int[][]{{9, 5291, 199, 0}}), 5291, 9, 0, "w9keep", t9) && !W6Herbs.keepSeed(false, W6Herbs.herbSeeds(true, new int[][]{{9, 5291, 199, 0}}), 5291, 9, 0, "w9keep", t9);
        check("w9 keepSeed with a name: the same answer and count as keepSeed (one kept)", keep9 && W6Herbs.seedsKept == k9 + 1);
        check("w9 wiring: every lane line is behind its flag (BotLife and the job start under SUPPLY, the via inside SUPPLY's branch, the seed lines inside HERBS2's)",
                all(bl, "if (W6Herbs.SUPPLY) W6Herbs.say(bot.getUsername(), \"herbSupply\", \"gatherer\",")
                        && all(sa, "if (W6Herbs.SUPPLY) W6Herbs.startedSay(p.getUsername(),", "W6Herbs.picked(true, hSeed, w6Spent); W6Herbs.pickedSay(name, hSeed, w6Spent, now); }")
                        && all(at, "W6Herbs.druidVias++; W6Herbs.say(p.getUsername(), \"herbSupply\", \"via\",", "&& !(W6Herbs.HERBS2 && p.isArtificial() && SmartAdventurers.brainOf(p) != null && W6Herbs.keepSeed(true, Farming.HERB_SEEDS, it.getId(), p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.FARMING), bankC.getAmount(it.getId()), p.getUsername(), System.currentTimeMillis()));"));

        System.out.println("[TestW6Herbs] " + pass + " passed, " + fail + " failed (herbSupply " + W6Herbs.SUPPLY + ", herbs2 " + W6Herbs.HERBS2 + ")");
        if (fail > 0) System.exit(1);
    }

    static boolean order(String src, String a, String b) { int i = src.indexOf(a), j = src.indexOf(b); return i >= 0 && j > i; }
    static int count(String src, String part) { int n = 0; for (int i = src.indexOf(part); i >= 0; i = src.indexOf(part, i + 1)) n++; return n; }
    static int idx(int[] v) { for (int i = 0; i < W6Herbs.LEAD.length; i++) if (W6Herbs.LEAD[i] == v) return i; return -1; }
    static boolean stepsOk() {
        for (int i = 1; i < W6Herbs.LEAD.length; i++) if (W6Herbs.dist(W6Herbs.LEAD[i][0], W6Herbs.LEAD[i][1], W6Herbs.LEAD[i - 1][0], W6Herbs.LEAD[i - 1][1]) > 10) return false;
        return W6Herbs.dist(W6Herbs.FOOT[0], W6Herbs.FOOT[1], W6Herbs.LEAD[0][0], W6Herbs.LEAD[0][1]) <= 6;
    }
    static boolean cropsGrimy() { for (int[] r : W6Herbs.HERBS_UP) if (!W6Herbs.has(W6Herbs.GRIMY, r[2])) return false; return true; }
}
