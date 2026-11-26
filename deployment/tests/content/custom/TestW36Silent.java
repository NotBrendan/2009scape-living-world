package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * w36-silent2 (results/w36-silent2.md): -Dbots.farming.trees2fire, -Dbots.mine.guild2, -Dbots.fish.guild2, -Dbots.wc.share2,
 * all default off. Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW36Silent <addons-src>/content/custom
 * Run it bare (flags off) and with the four -D flags set. The pure rules (W36Silent) are called; the wiring is read from the
 * source (SmartAdventurers and AfkTasks cannot class-init outside a world), including that each hook sits behind its flag.
 */
public final class TestW36Silent {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW36Silent] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8).replace("\r\n", "\n"); }
    static int count(String src, String what) { return src.split(Pattern.quote(what), -1).length - 1; }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }
    /** Every line of src that names W36Silent starts its statement behind a W36Silent flag (or is a comment / the helper itself). */
    static boolean gated(String src, String... okStarts) {
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (!t.contains("W36Silent.") || t.startsWith("*") || t.startsWith("/**") || t.startsWith("//")) continue;
            boolean ok = false;
            for (String s : okStarts) if (t.startsWith(s)) ok = true;
            if (!ok) { System.out.println("[TestW36Silent] ungated: " + t.substring(0, Math.min(140, t.length()))); return false; }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), at = read(dir, "AfkTasks.java"), mg = read(dir, "MineGuild.java"), mb = read(dir, "MarketBrain.java");
        boolean t2 = Boolean.getBoolean("bots.farming.trees2fire"), m2 = Boolean.getBoolean("bots.mine.guild2"), f2 = Boolean.getBoolean("bots.fish.guild2"), w2 = Boolean.getBoolean("bots.wc.share2");
        System.out.println("[TestW36Silent] flags: trees2fire=" + t2 + " mineGuild2=" + m2 + " fishGuild2=" + f2 + " wcShare2=" + w2);

        // ---------------------------------------------------------------- flags
        check("F1 the four constants follow their properties (default off)", W36Silent.TREES2 == t2 && W36Silent.MINE2 == m2 && W36Silent.FISH2 == f2 && W36Silent.WC2 == w2);
        check("F2 anyOn and the hourly line follow the flags", W36Silent.anyOn() == (t2 || m2 || f2 || w2)
                && W36Silent.line().startsWith("[W36] on=[") && W36Silent.line().contains("trees2fire") == t2 && W36Silent.line().contains("mineGuild2") == m2
                && W36Silent.line().contains("fishGuild2") == f2 && W36Silent.line().contains("wcShare2") == w2);
        check("F3 the hourly line is scheduled only with a flag on", count(mb, "if (W36Silent.anyOn()) TIMER.schedule(") == 1 && count(mb, "W36Silent.") == 2);

        // ---------------------------------------------------------------- 1 trees: nests
        check("T1 off: no nest at any haul number", W36Silent.nestSeed(false, 5, 5) == 0 && W36Silent.nestSeed(false, 10, 5) == 0);
        check("T2 on: a nest on every Nth haul only", W36Silent.nestSeed(true, 5, 5) == W36Silent.ACORN && W36Silent.nestSeed(true, 4, 5) == 0 && W36Silent.nestSeed(true, 6, 5) == 0
                && W36Silent.nestSeed(true, 0, 5) == 0 && W36Silent.nestSeed(true, -5, 5) == 0 && W36Silent.nestSeed(true, 5, 0) == 0);
        check("T3 seed cycle: 3rd nest willow, 5th and 15th apple, the rest acorns", W36Silent.nestSeed(true, 10, 5) == W36Silent.ACORN && W36Silent.nestSeed(true, 15, 5) == W36Silent.WILLOW_SEED
                && W36Silent.nestSeed(true, 25, 5) == W36Silent.APPLE_SEED && W36Silent.nestSeed(true, 30, 5) == W36Silent.WILLOW_SEED && W36Silent.nestSeed(true, 75, 5) == W36Silent.APPLE_SEED);
        check("T4 the seeds are W8Polish's oak, willow and apple rows", W36Silent.ACORN == W8Polish.TREE_ROWS[0][1] && W36Silent.WILLOW_SEED == W8Polish.TREE_ROWS[1][1] && W36Silent.APPLE_SEED == W8Polish.FRUIT_ROWS[0][1]);
        int h0 = W36Silent.logHauls, n0 = W36Silent.nests;
        check("T5 nestFor off: no count, no seed", W36Silent.nestFor(false, id -> 0) == 0 && W36Silent.logHauls == h0 && W36Silent.nests == n0);
        int got = 0, seeds = 0; for (int i = 0; i < 2 * W36Silent.NEST_EVERY; i++) { int s = W36Silent.nestFor(true, id -> 0); if (s > 0) { got++; seeds += s == W36Silent.ACORN ? 1 : 0; } }
        check("T6 nestFor on: 2 nests in 2 x NEST_EVERY hauls, counted", got == 2 && seeds >= 1 && W36Silent.logHauls == h0 + 2 * W36Silent.NEST_EVERY && W36Silent.nests == n0 + 2);
        int full0 = W36Silent.nestFull; int capped = 0; for (int i = 0; i < W36Silent.NEST_EVERY; i++) capped += W36Silent.nestFor(true, id -> W36Silent.NEST_OWN_CAP);
        check("T7 the finder owns the cap already: the nest is skipped and counted", capped == 0 && W36Silent.nestFull == full0 + 1 && W36Silent.nests == n0 + 2);
        check("T8 seed names", W36Silent.seedName(5312).equals("an acorn") && W36Silent.seedName(5313).equals("a willow seed") && W36Silent.seedName(5283).equals("an apple tree seed"));

        // ---------------------------------------------------------------- 1 trees: the check
        long now = 1_000_000_000L;
        check("T9 off (and on with the save on): the next-day rule, W8Polish.checkDue", W36Silent.checkDue(false, false, now, 200) == W8Polish.checkDue(now, 200)
                && W36Silent.checkDue(false, true, now, 200) == W8Polish.checkDue(now, 200) && W36Silent.checkDue(true, true, now, 200) == W8Polish.checkDue(now, 200));
        check("T10 on, no save: due when grown plus the margin (oak 230 min)", W36Silent.checkDue(true, false, now, 200) == now + 230 * 60_000L);
        check("T11 an oak then fits a 4 h shift, and never did before", W9Trees.plantOk(false, now, W36Silent.checkDue(true, false, now, 200), now + 4 * 3_600_000L)
                && !W9Trees.plantOk(false, now, W36Silent.checkDue(false, false, now, 200), now + 5 * 3_600_000L)
                && !W9Trees.plantOk(false, now, W36Silent.checkDue(true, false, now, 200), now + 3 * 3_600_000L));
        check("T12 wiring: the base plantOk test is kept; the in-shift check is a second chance behind the flag with the save off",
                count(sa, "!W9Trees.plantOk(FarmSave.ON, now, W8Polish.checkDue(now, rows[row][3]), shiftEnd) && !(W36Silent.TREES2 && !FarmSave.ON && W9Trees.plantOk(false, now, W36Silent.checkDue(true, false, now, rows[row][3]), shiftEnd))) { why = W9Trees.R_FIT; row = -1; }") == 1
                && count(sa, "if (W36Silent.TREES2 && !FarmSave.ON && treePlanted && row >= 0) return W36Silent.checkDue(true, false, now, rows[row][3]);") == 1
                && count(sa, "if (W36Silent.TREES2 && !FarmSave.ON) W36Silent.shortDue++;") == 1);
        check("T13 wiring: the nest sits behind the flag, a bot and a log job, before the haul is sold",
                count(at, "if (W36Silent.TREES2 && p.isArtificial() && task != null && task.kind == Kind.TREE && !haulById.isEmpty()) w36Nest(p);") == 1
                && at.indexOf("w36Nest(p); //") < at.indexOf("brokeShopSale(p, haulById, priceById) > 0) haulById.clear()") && count(at, "Ledger.supply(p, seed") == 0 && count(at, "if (seed > 0 && b36.add(new Item(seed, 1)))") == 1);
        String nestFn = at.substring(at.indexOf("private void w36Nest(Player p) {"), at.indexOf("void deposit() {"));
        check("T14 review fix 2: the nest seed never reaches bot stock or the clerk (no Ledger, Deals or GrandExchange call), and the cap reads the finder's own bank and pack",
                count(nestFn, "Ledger.") == 0 && count(nestFn, "Deals.") == 0 && count(nestFn, "GrandExchange") == 0 && count(nestFn, "MarketBrain") == 0
                && nestFn.contains("W36Silent.nestFor(true, id -> b36.getAmount(id) + p.getInventory().getAmount(id))") && count(read(dir, "W36Silent.java"), "Ledger.supply(") == 0);
        check("T15 review fix 3: the default rate is one nest in 30 hauls (a player's rate), own cap 3", Integer.getInteger("bots.farming.trees2fire.nestEvery") != null || W36Silent.NEST_EVERY == 30 && W36Silent.NEST_OWN_CAP == 3);

        // ---------------------------------------------------------------- 2 + 3 guild floors
        check("G1 off: the weight is untouched", near(W36Silent.guildWeight(false, 0, true, 99, 60, 0, 6, true, true), 0));
        check("G2 on: an unwanted guild job gets the floor", near(W36Silent.guildWeight(true, 0, true, 60, 60, 5, 6, true, true), W36Silent.FLOOR) && near(W36Silent.guildWeight(true, 0.3, true, 70, 60, 0, 6, true, true), W36Silent.FLOOR));
        check("G3 on: a wanted job keeps its weight", near(W36Silent.guildWeight(true, 0.9, true, 70, 60, 0, 6, true, true), 0.9) && near(W36Silent.guildWeight(true, W36Silent.FLOOR, true, 70, 60, 0, 6, true, true), W36Silent.FLOOR));
        check("G4 on: level, seat, hour cap, distance and job kind each refuse", near(W36Silent.guildWeight(true, 0, true, 59, 60, 0, 6, true, true), 0) && near(W36Silent.guildWeight(true, 0, true, 60, 60, 6, 6, true, true), 0)
                && near(W36Silent.guildWeight(true, 0, true, 60, 60, 0, 6, false, true), 0) && near(W36Silent.guildWeight(true, 0, true, 60, 60, 0, 6, true, false), 0) && near(W36Silent.guildWeight(true, 0, false, 60, 60, 0, 6, true, true), 0));
        long[] st = new long[2];
        boolean open = true; for (int i = 0; i < 3; i++) { open &= W36Silent.hourOpen(st, now, 3); W36Silent.hourTake(st, now + i); }
        check("G5 the hour cap: 3 starts, then closed, open again an hour on", open && !W36Silent.hourOpen(st, now + 10, 3) && !W36Silent.hourOpen(st, now + 3_599_999L, 3) && W36Silent.hourOpen(st, now + 3_600_000L, 3) && st[1] == 0);
        check("G6 mineTakes: Mining 60, a served job, the ladder in the radius, a seat", W36Silent.mineTakes(true, 60, true, W36Silent.GUILD2_R, 5, 6) && !W36Silent.mineTakes(false, 99, true, 10, 0, 6)
                && !W36Silent.mineTakes(true, 59, true, 10, 0, 6) && !W36Silent.mineTakes(true, 60, false, 10, 0, 6) && !W36Silent.mineTakes(true, 60, true, W36Silent.GUILD2_R + 1, 0, 6) && !W36Silent.mineTakes(true, 60, true, 10, 6, 6));
        int ms = W36Silent.mineStarts, fs = W36Silent.fishStarts; W36Silent.mineTaken(now); W36Silent.fishTaken(now);
        check("G7 the start counters", W36Silent.mineStarts == ms + 1 && W36Silent.fishStarts == fs + 1 && W36Silent.mineOpen(now) && W36Silent.fishOpen(now));
        check("G8 base rule unchanged: MineGuild.wants still needs the nearer ladder", !MineGuild.wants(true, 60, "COAL", 50, 40, 0, 6) && MineGuild.wants(true, 60, "COAL", 30, 40, 0, 6)
                && count(mg, "static Location spotFor2(") == 1 && count(mg, "W36Silent.") == 2);
        check("G9 wiring: mine start behind both flags, never an errand or a chain", count(sa, "if (W36Silent.MINE2 && MineGuild.ON) {") == 1
                && count(sa, "errandFor == null && job.chain == 0 ? MineGuild.spotFor2(p, job.task) : null;") == 1
                && count(sa, "if (W36Silent.MINE2 && MineGuild.ON && MineGuild.serves(j.task.name()) && weight < W36Silent.FLOOR) {") == 1);
        check("G10 wiring: fish floor behind both flags, cage and harpoon only, never the shark row", count(sa, "if (W36Silent.FISH2 && W7Places.FISH_GUILD && !j.sharkRow && (j.task == AfkTasks.Task.CAGE || j.task == AfkTasks.Task.HARPOON) && weight < W36Silent.FLOOR) {") == 1
                && count(sa, "if (W36Silent.FISH2 && W36Silent.fishCounts(true, w7Guild, w36Fish, !job.sharkRow && (job.task == AfkTasks.Task.CAGE || job.task == AfkTasks.Task.HARPOON))) { W36Silent.fishTaken(System.currentTimeMillis()); if (!w7Guild) W36Silent.fishOutside++;") == 1);
        check("G11 review fix 1: a floored cage/harpoon start outside the guild (cook chain) counts against the hour; an unfloored one and other jobs do not",
                W36Silent.fishCounts(true, false, true, true) && W36Silent.fishCounts(true, true, false, false) && !W36Silent.fishCounts(true, false, false, true)
                && !W36Silent.fishCounts(true, false, true, false) && !W36Silent.fishCounts(false, true, true, true));
        long[] fh = new long[2]; int floors = 0; for (int i = 0; i < 20; i++) if (near(W36Silent.guildWeight(true, 0, true, 70, 68, 0, 3, W36Silent.hourOpen(fh, now + i, W36Silent.FISH_PER_HOUR), true), W36Silent.FLOOR)) { floors++; if (W36Silent.fishCounts(true, false, true, true)) W36Silent.hourTake(fh, now + i); }
        check("G12 review fix 1: 20 chain-variant picks in one hour with a seat free get FISH_PER_HOUR floors, not 20", floors == W36Silent.FISH_PER_HOUR);

        // ---------------------------------------------------------------- 4 woodcutting share
        check("W1 off: the weight is untouched", near(W36Silent.wcWeight(false, 0, true, true), 0));
        check("W2 on: a log job under its share gets the floor; other jobs and a met share do not", near(W36Silent.wcWeight(true, 0, true, true), W36Silent.FLOOR) && near(W36Silent.wcWeight(true, 0, false, true), 0)
                && near(W36Silent.wcWeight(true, 0, true, false), 0) && near(W36Silent.wcWeight(true, 1.2, true, true), 1.2));
        check("W3 the share: never before 25 picks, under 8 % after (live 62 of 4,443)", !W36Silent.wcUnder(0, 0, 8) && !W36Silent.wcUnder(0, 24, 8) && W36Silent.wcUnder(1, 25, 8) && W36Silent.wcUnder(62, 4443, 8)
                && !W36Silent.wcUnder(2, 25, 8) && !W36Silent.wcUnder(8, 100, 8) && W36Silent.wcUnder(7, 100, 8));
        int p0 = W36Silent.picks, tp0 = W36Silent.treePicks; W36Silent.picked(true); W36Silent.picked(false);
        check("W4 the pick counters", W36Silent.picks == p0 + 2 && W36Silent.treePicks == tp0 + 1);
        check("W5 wiring: the floor and the count sit behind the flag", count(sa, "if (W36Silent.WC2 && j.task.kind == AfkTasks.Kind.TREE) {") == 1 && count(sa, "if (W36Silent.WC2) { boolean tree36 =") == 1);

        // ---------------------------------------------------------------- flags off = base
        check("O1 chooseJob: one call, behind the flags, before the weight <= 0 skip and before the score's RNG draw",
                count(sa, "if ((W36Silent.MINE2 || W36Silent.FISH2 || W36Silent.WC2) && j.hunt == null) weight = w36Weight(j, weight, nowMs);") == 1
                && sa.indexOf("weight = w36Weight(j, weight, nowMs);") < sa.indexOf("if (weight <= 0 && !(broke && coinJob)")
                && sa.indexOf("weight = w36Weight(j, weight, nowMs);") < sa.indexOf("double score = weight / (1 + dist / 150.0) * (0.8 + RNG.nextDouble() * 0.4);"));
        check("O2 every W36Silent use in SmartAdventurers is behind a flag", gated(sa, "if (W36Silent.", "if ((W36Silent.", "double w = W36Silent.", "boolean near = top.getDistance(bot.getLocation()) <= W36Silent.GUILD2_R",
                "if (row >= 0 && !W9Trees.plantOk(FarmSave.ON, now, W8Polish.checkDue(now, rows[row][3]), shiftEnd) && !(W36Silent.TREES2 && !FarmSave.ON && ", "if (w != weight) { W36Silent.", "if (g36 != null) {"));
        check("O3 every W36Silent use in AfkTasks is behind the flag", gated(at, "if (W36Silent.TREES2 && ", "try { final core.game.container.Container b36 = p.getBank(); int seed = b36 == null ? 0 : W36Silent.nestFor(true,") && count(at, "w36Nest(p)") == 1);
        check("O4 no RNG in the new rules", count(read(dir, "W36Silent.java"), "RNG.") == 0 && count(read(dir, "W36Silent.java"), "Random") == 0);

        // ---------------------------------------------------------------- fire regexes (results/w36-silent2.md)
        check("R the fire regexes match the lines the code prints",
                Pattern.compile("^\\[W36\\] nest .+ found an? .+ in a bird's nest \\(log haul \\d+").matcher("[W36] nest Bob found an acorn in a bird's nest (log haul 30, kept in the bank)").find()
                && at.contains("\"[W36] nest \" + p.getUsername() + \" found \" + W36Silent.seedName(seed) + \" in a bird's nest (log haul \"")
                && Pattern.compile("^\\[W36\\] mine2 .+ takes a floored (coal|mithril) job to the Mining Guild").matcher("[W36] mine2 Bob takes a floored coal job to the Mining Guild (starts 1)").find()
                && sa.contains("\"[W36] mine2 \" + p.getUsername() + \" takes a floored \" + job.task.key + \" job to the Mining Guild (starts \"")
                && Pattern.compile("^\\[W36\\] fish2 .+ takes a floored (cage|harpoon) job to the Fishing Guild").matcher("[W36] fish2 Bob takes a floored cage job to the Fishing Guild (starts 1)").find()
                && sa.contains("\"[W36] fish2 \" + p.getUsername() + \" takes a floored \" + job.task.key + (w7Guild ? \" job to the Fishing Guild (starts \" : \" job, not to the guild (")
                && !Pattern.compile("^\\[W36\\] fish2 .+ takes a floored (cage|harpoon) job to the Fishing Guild").matcher("[W36] fish2 Bob takes a floored cage job, not to the guild (chain, errand or no seat; starts 2)").find()
                && Pattern.compile("^\\[W36\\] wc2 .+ takes a floored \\S+ job \\(log picks \\d+/\\d+\\)").matcher("[W36] wc2 Bob takes a floored willow job (log picks 3/60)").find()
                && sa.contains("\"[W36] wc2 \" + p.getUsername() + \" takes a floored \" + job.task.key + \" job (log picks \""));

        System.out.println("[TestW36Silent] " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
