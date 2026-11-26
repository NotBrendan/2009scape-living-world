package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * W32 (coverage-strict 2026-11-22): -Dbots.fletch.ownNormal (needs bots.fletch.own) and -Dbots.fm.moreSpots (needs
 * bots.fire.line), both default off. Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW32Strict <addons-src>/content/custom
 * Run it bare (flags off) and with the four -D flags set. The pure rules (W7cSkills, W5aSkills) are called; the wiring is read
 * from the source (SmartAdventurers cannot class-init outside a world), including that the flag-off path is train-75's code.
 */
public final class TestW32Strict {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW32Strict] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW32Strict]   missing: " + p); return false; } return true; }
    static boolean order(String src, String first, String second) { int a = src.indexOf(first), b = src.indexOf(second, Math.max(0, a)); return a >= 0 && b > a; }
    static String body(String src, String head, String end) { int a = src.indexOf(head), b = src.indexOf(end, Math.max(0, a) + head.length()); return a < 0 || b < 0 ? "" : src.substring(a, b); }
    static int count(String src, String what) { return src.split(Pattern.quote(what), -1).length - 1; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = new String(Files.readAllBytes(Paths.get(dir, "SmartAdventurers.java")), StandardCharsets.UTF_8).replace("\r\n", "\n");
        boolean own = Boolean.getBoolean("bots.fletch.ownNormal"), fo = Boolean.getBoolean("bots.fletch.own"), more = Boolean.getBoolean("bots.fm.moreSpots"), fl = Boolean.getBoolean("bots.fire.line");
        System.out.println("[TestW32Strict] flags: fletch.own=" + fo + " ownNormal=" + own + " fire.line=" + fl + " fm.moreSpots=" + more);

        // ---------------------------------------------------------------- flags
        check("F the two statics follow their properties (default off)", W7cSkills.OWN_NORMAL == own && W5aSkills.MORE_SPOTS == more);

        // ---------------------------------------------------------------- 1. bots.fletch.ownNormal
        check("1a fletchNeed: normal 1, oak 20, willow 35, maple 50, yew 65, magic 80, unknown log 0",
                W7cSkills.fletchNeed(1511) == 1 && W7cSkills.fletchNeed(1521) == 20 && W7cSkills.fletchNeed(1519) == 35 && W7cSkills.fletchNeed(1517) == 50
                        && W7cSkills.fletchNeed(1515) == 65 && W7cSkills.fletchNeed(1513) == 80 && W7cSkills.fletchNeed(999) == 0);
        boolean seq = true; for (int n = 1; n <= 13; n++) seq &= W7cSkills.ownNormal(true, 1521, 1, n, 4) == (n == 1 || n == 5 || n == 9 || n == 13);
        check("1b ownNormal: a Fletching 1 oak chopper's 1st, 5th, 9th, 13th tree job (1 in 4), no other", seq);
        check("1c ownNormal: never with the flag off, at the log's level, for normal logs, for an unknown log or job 0",
                !W7cSkills.ownNormal(false, 1521, 1, 1, 4) && !W7cSkills.ownNormal(true, 1521, 20, 1, 4) && W7cSkills.ownNormal(true, 1521, 19, 1, 4)
                        && !W7cSkills.ownNormal(true, 1511, 1, 1, 4) && !W7cSkills.ownNormal(true, 999, 1, 1, 4) && !W7cSkills.ownNormal(true, 1515, 1, 0, 4)
                        && W7cSkills.ownNormal(true, 1515, 64, 1, 4) && !W7cSkills.ownNormal(true, 1515, 65, 1, 4) && W7cSkills.ownNormal(true, 1519, 34, 3, 1));
        check("1d a swapped pack has work: normal logs carve at Fletching 1 (arrow shafts), oak does not", W7cSkills.fletchPick(1511, 1, id -> 0, 300) == 0 && W7cSkills.fletchPick(1521, 1, id -> 0, 300) == -1);
        check("1j w32 review reach gate: within 120 tiles only (default), none enterable -> no swap",
                W7cSkills.OWN_REACH == Integer.getInteger("bots.fletch.ownNormal.reach", 120) && W7cSkills.ownNear(0, 120) && W7cSkills.ownNear(120, 120) && !W7cSkills.ownNear(120.5, 120) && !W7cSkills.ownNear(500, 120) && !W7cSkills.ownNear(-1, 120));
        String hook = "if (W7cSkills.FLETCH_OWN && W7cSkills.OWN_NORMAL) { Job nj = w32Normal(job); if (nj != job) { start(nj); return; } }";
        String sw = body(sa, "        Job w32Normal(Job job) {", "\n        }\n");
        check("1e wiring: start() swaps only behind both flags, right after the rune-run row leaves; the hook is the one caller",
                all(sa, "            " + hook) && order(sa, "void start(Job job) {\n            if (job != null && job.rcRow) { RcRuns.start(this, job); return; }", hook)
                        && order(sa, hook, "if (shopRetal != null) endShopGuard();") && count(sa, "w32Normal(") == 2 && count(sa, "w32TreeJobs") == 3);
        check("1k the reach gate sits before the counter (a far chopper's job is not counted) and uses the enterable normal-tree spots",
                order(sw, "AfkTasks.SPOTS.get(AfkTasks.Task.TREE)", "if (!W7cSkills.ownNear(near, W7cSkills.OWN_REACH)) return job;") && order(sw, "W7cSkills.ownNear(near, W7cSkills.OWN_REACH)", "++w32TreeJobs") && sw.contains("Gates.canEnter(bot, s.at)"));
        check("1f the swap draws no random number and leaves errands, deliveries, crews, hunts and chains alone",
                !sw.isEmpty() && !sw.contains("RNG") && !sw.contains("Random") && all(sw, "job.hunt != null", "job.chain != 0", "errandFor != null", "deliverTo != null", "crew != null",
                        "job.task.kind != AfkTasks.Kind.TREE", "job.task == AfkTasks.Task.TREE", "W7cSkills.ownNormal(true, log, lvl, ++w32TreeJobs, W7cSkills.OWN_EVERY)", "blacklist.getOrDefault(j.label(), 0L)"));
        check("1g the fletch kit line is train-75's (the knife still comes from fletchKit, on the swapped job too)",
                all(sa, "if (W7cSkills.FLETCH_OWN && job.hunt == null && job.chain == 0 && errandFor == null && !w5Power && job.task.kind == AfkTasks.Kind.TREE) W7cHooks.fletchKit(p, r);"));
        check("1h [W7c] line: ownNormalSwaps= only with both flags on (off: train-75's line)",
                W7cSkills.line().contains(" ownNormalSwaps=") == (fo && own)
                        && W7cSkills.line().contains(" fletchKnives=" + W7cSkills.fletchKnives + (fo && own ? " ownNormalSwaps=" : " masterStarts=")));
        Pattern r1 = Pattern.compile("^\\[W7c\\] ownNormal (?<bot>.+?) cuts normal logs instead of ");
        check("1i fire regex matches the swap line and not the fletch line",
                r1.matcher("[W7c] ownNormal Bob the 2nd cuts normal logs instead of oaks (Fletching 3 under 20, tree job 5)").find()
                        && !r1.matcher("[W7c] fletch Bob made 135 x arrow shaft from 9 of 9 logs before banking (Fletching 3)").find()
                        && all(sw, "\"[W7c] ownNormal \" + bot.getUsername() + \" cuts normal logs instead of \""));

        // ---------------------------------------------------------------- 2. bots.fm.moreSpots
        check("2a lineNear: nothing within 40 -> -1; the nearest wins; exactly 40 counts",
                W5aSkills.lineNear(new double[]{50, 41, 300, 99, 40.5}, 40) == -1 && W5aSkills.lineNear(new double[]{50, 30, 10, 99, 99}, 40) == 2
                        && W5aSkills.lineNear(new double[]{40, 99}, 40) == 0 && W5aSkills.LINE_RADIUS == 40);
        boolean expl = System.getProperty("bots.fire.line.perHour") != null || System.getProperty("bots.fire.line.perBank") != null;
        check("2b caps: 2 a bank and 270 logs an hour with the flag off; 3 and 405 with it on (explicit properties win)",
                expl || (W5aSkills.LINE_BANK_CAP == (more ? 3 : 2) && W5aSkills.LINE_HOUR == (more ? 405 : 270) && W5aSkills.lineSeat(2) == more && !W5aSkills.lineSeat(3) && W5aSkills.lineSeat(1)
                        && W5aSkills.lineQty(270, 1_000_000, 10) == (more ? 27 : 0) && W5aSkills.lineQty(405, 1_000_000, 10) == 0));
        check("2c spots: the two old ones unchanged and first; Falador east, Edgeville, Varrock east added",
                all(sa, "static final AfkTasks.Spot[] W5_LINES = {AfkTasks.sp(\"the Varrock west bank fire line\", 3187, 3430), AfkTasks.sp(\"the Draynor bank fire line\", 3099, 3251)};",
                        "static final AfkTasks.Spot[] W32_LINES = {W5_LINES[0], W5_LINES[1], AfkTasks.sp(\"the Falador east bank fire line\", 3016, 3362),",
                        "AfkTasks.sp(\"the Edgeville bank fire line\", 3105, 3486), AfkTasks.sp(\"the Varrock east bank fire line\", 3254, 3429)};"));
        String pf = body(sa, "        boolean planFireLine(long now) {", "\n        }\n");
        check("2d wiring: the 1-in-4 roll is still the first thing (same RNG order); flag off runs train-75's loop",
                all(pf, "if (errandFor != null || now < w5LineNext || RNG.nextInt(4) != 0) return false;", "            if (W5aSkills.MORE_SPOTS) {",
                        "            } else\n            for (AfkTasks.Spot s : W5_LINES) if (s.at.getDistance(p.getLocation()) <= 40) at = s;\n            if (at == null) { W5aSkills.lineNoSpot++; return false; }")
                        && order(pf, "RNG.nextInt(4) != 0) return false;", "if (W5aSkills.MORE_SPOTS)") && count(pf, "RNG.") == 1 && count(sa, "W32_LINES") == 4);
        check("2e [W5a] line: lineNewSpot= only with fire.line and the flag on",
                W5aSkills.line().contains(" lineNewSpot=") == (fl && more));
        Pattern r2 = Pattern.compile("^\\[Fire\\] line (?<bot>.+?) burns \\d+ x .+ at the (?:Falador east|Edgeville|Varrock east) bank fire line ");
        check("2f fire regex matches a line at a new spot only",
                r2.matcher("[Fire] line Ann burns 27 x Willow logs at the Edgeville bank fire line (Firemaking 31, stock now 400, 27/405 this hour, 1 at this bank)").find()
                        && r2.matcher("[Fire] line Ann burns 27 x Logs at the Falador east bank fire line (Firemaking 1, stock now 90, 54/405 this hour, 3 at this bank)").find()
                        && !r2.matcher("[Fire] line Ann burns 27 x Logs at the Varrock west bank fire line (Firemaking 1, stock now 90, 54/270 this hour, 2 at this bank)").find()
                        && !r2.matcher("[Fire] line Ann refused at the Edgeville bank fire line: x (27 x Logs stay in its bank)").find());

        System.out.println("[TestW32Strict] SUMMARY " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
