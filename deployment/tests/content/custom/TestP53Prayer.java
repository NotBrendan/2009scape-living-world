package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P53 prayer bone-bury runs (the design notes s6): PrayerRun's pure pick, the fleet cap and the SmartAdventurers wiring
 * (read from source: SmartAdventurers cannot load outside a world). Plain java, no world. Run twice:
 *   java -cp "out;server.jar" content.custom.TestP53Prayer <SmartAdventurers.java>                      (flag off: expects ON false)
 *   java -Dbots.prayerRun=true -cp "out;server.jar" content.custom.TestP53Prayer <SmartAdventurers.java> (flag on: expects ON true)
 * Prints one PASS/FAIL line per check and "[TestP53Prayer] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestP53Prayer {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestP53Prayer] " + (ok ? "PASS " : "FAIL ") + what); }

    static String s(int[] c) { return c == null ? "null" : "{" + c[0] + "," + c[1] + "," + c[2] + "}"; }
    static boolean is(int[] c, int id, int n, int buy) { return c != null && c[0] == id && c[1] == n && c[2] == buy; }

    public static void main(String[] args) throws Exception {
        boolean want = Boolean.getBoolean("bots.prayerRun");
        check("F flag ON=" + PrayerRun.ON + " matches -Dbots.prayerRun=" + want + ", TEST off, counters 0",
                PrayerRun.ON == want && !PrayerRun.TEST && PrayerRun.RUNS == 0 && PrayerRun.BOUGHT == 0 && PrayerRun.BURIED == 0);

        // value: big 300, normal 100; stock 500 each; purse 1m; hour 300
        int[] c;
        c = PrayerRun.boneChoice(40, 1_000_000, 50, 80, true, true, 500, 500, 300, 100, 300);
        check("A own big bones first " + s(c), is(c, 532, 27, 0));
        c = PrayerRun.boneChoice(40, 1_000_000, 3, 12, true, true, 500, 500, 300, 100, 300);
        check("B own normal bones when under 5 big " + s(c), is(c, 526, 12, 0));
        c = PrayerRun.boneChoice(40, 1_000_000, 0, 0, false, false, 500, 500, 300, 100, 300);
        check("C no own bones, no surplus -> null " + s(c), c == null);
        c = PrayerRun.boneChoice(40, 1_000_000, 0, 0, true, true, 500, 500, 300, 100, 300);
        check("D buys surplus big bones " + s(c), is(c, 532, 27, 1));
        c = PrayerRun.boneChoice(40, 30_000, 0, 0, true, false, 500, 500, 300, 100, 300); // 3000/300 = 10
        check("E 10% purse cap: 30k at 300 gp -> 10 " + s(c), is(c, 532, 10, 1));
        c = PrayerRun.boneChoice(40, 12_000, 0, 0, true, true, 500, 500, 300, 100, 300); // big 1200/300=4 <5, normal 1200/100=12
        check("G big too dear (4) falls to normal (12) " + s(c), is(c, 526, 12, 1));
        c = PrayerRun.boneChoice(40, 4_000, 0, 0, true, true, 500, 500, 300, 100, 300); // 400/300=1, 400/100=4
        check("H n < 5 -> null " + s(c), c == null);
        c = PrayerRun.boneChoice(40, 1_000_000, 0, 0, true, true, 500, 500, 300, 100, 0);
        check("I fleet cap 0 -> null " + s(c), c == null);
        c = PrayerRun.boneChoice(40, 1_000_000, 0, 0, true, true, 500, 500, 300, 100, 7);
        check("J fleet cap 7 left -> 7 " + s(c), is(c, 532, 7, 1));
        c = PrayerRun.boneChoice(70, 1_000_000, 50, 80, true, true, 500, 500, 300, 100, 300);
        check("K prayer 70 -> null " + s(c), c == null);
        c = PrayerRun.boneChoice(40, 1_000_000, 0, 0, true, true, 39, 39, 300, 100, 300);
        check("L stock under 40 -> null " + s(c), c == null);
        c = PrayerRun.boneChoice(40, 1_000_000, 4, 4, false, false, 0, 0, 300, 100, 300);
        check("M 4+4 own bones, not at the Exchange -> null " + s(c), c == null);

        long t = 7 * 3_600_000L + 5;
        int h0 = PrayerRun.hourLeft(t); PrayerRun.bought(t, 290); int h1 = PrayerRun.hourLeft(t + 1000); PrayerRun.bought(t, 20);
        int h2 = PrayerRun.hourLeft(t + 2000), h3 = PrayerRun.hourLeft(t + 3_600_000L);
        check("N fleet cap 300/h: " + h0 + " -> " + h1 + " -> " + h2 + ", next hour " + h3, h0 == 300 && h1 == 10 && h2 == 0 && h3 == 300);

        String src = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "deployment/addons-src/content/custom/SmartAdventurers.java")), StandardCharsets.UTF_8);
        int plank = src.indexOf("if (planPlankRun(now)) return;");
        boolean w1 = plank > 0 && src.indexOf("if (PrayerRun.ON && planBoneRun(now)) return;") > plank;
        boolean w2 = src.contains("if (mill != 0) { millTick(now); return; }\n            if (bone != 0) { boneTick(now); return; }")
                || src.contains("if (mill != 0) { millTick(now); return; }\r\n            if (bone != 0) { boneTick(now); return; }");
        int plan = src.indexOf("boolean planBoneRun(long now) {");
        boolean w3 = plan > 0 && src.indexOf("if (!PrayerRun.ON ||", plan) == src.indexOf("if (", plan);
        int setters = 0, from = 0; while ((from = src.indexOf("bone = 1;", from)) >= 0) { setters++; from++; }
        boolean w4 = setters == 1 && src.indexOf("bone = 1;") > plan;
        boolean w5 = src.contains("mill = 0; bone = 0;") && src.contains("brain.bone = 0;");
        check("W wiring: trip-end hook after plank run " + w1 + ", dispatch next to mill " + w2 + ", planBoneRun guarded by ON first " + w3
                + ", bone set only in planBoneRun (" + setters + ") " + w4 + ", death + logOut reset " + w5, w1 && w2 && w3 && w4 && w5);

        // F1: every busy-state test that names mill also names bone (all addon sources; the mill dispatch line has its own bone line)
        java.nio.file.Path dir = Paths.get(args.length > 0 ? args[0] : "deployment/addons-src/content/custom/SmartAdventurers.java").getParent();
        java.util.regex.Pattern millTest = java.util.regex.Pattern.compile("\\bmill\\s*[!=]=\\s*0");
        int tests = 0; StringBuilder miss = new StringBuilder();
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(dir)) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                int ln = 0;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    ln++;
                    if (!millTest.matcher(line).find() || line.contains("{ millTick(now); return; }")) continue;
                    tests++;
                    if (!line.contains("bone")) miss.append(' ').append(f.getFileName()).append(':').append(ln);
                }
            }
        }
        check("F1 busy states naming mill also name bone: " + tests + " lines, missing [" + miss.toString().trim() + "]", tests >= 10 && miss.length() == 0);

        // F3/F4 wiring: boneTick waits on the bury lock, stops when attacked, has no half-keep; planBoneRun refuses in combat
        int tick = src.indexOf("void boneTick(long now) {"), tickEnd = src.indexOf("void endBone(", tick);
        String body = tick > 0 && tickEnd > tick ? src.substring(tick, tickEnd) : "";
        boolean f3 = body.contains("isInteractionLocked()") && !body.contains("bonesKept") && !body.contains("boneFlip") && body.contains("PrayerRun.buryOne(");
        boolean f4 = body.contains("bot.inCombat()") && src.indexOf("if (bot.inCombat()) return PrayerRun.skip(PrayerRun.R_COMBAT", plan) > plan;
        check("F3 lock wait, no half-keep " + f3 + "; F4 combat stop in boneTick + refuse in planBoneRun " + f4, f3 && f4);

        // tally reasons
        boolean t1 = PrayerRun.why(70, true, true) == PrayerRun.R_PRAYER70 && PrayerRun.why(40, false, true) == PrayerRun.R_NO_BONES_AWAY
                && PrayerRun.why(40, true, false) == PrayerRun.R_NO_SURPLUS && PrayerRun.why(40, true, true) == PrayerRun.R_LOW_STOCK_OR_PURSE;
        boolean t2 = !PrayerRun.skip(PrayerRun.R_NOT_BANK, 1000) && PrayerRun.TALLY[PrayerRun.R_NOT_BANK] == 1 && PrayerRun.REASONS.length == PrayerRun.TALLY.length;
        check("T skip reasons: why() maps " + t1 + ", skip counts and returns false " + t2 + " (" + PrayerRun.tallyLine() + ")", t1 && t2);

        // P53b trigger: a smart bot keeps its own bones in the bank (flag on only), never past prayer 70 or KEEP_MAX
        boolean k1 = PrayerRun.keepBone(true, 526, 40, 0) && PrayerRun.keepBone(true, 532, 1, 53) && !PrayerRun.keepBone(true, 526, 40, PrayerRun.KEEP_MAX)
                && !PrayerRun.keepBone(true, 526, 70, 0) && !PrayerRun.keepBone(true, 1739, 40, 0) && !PrayerRun.keepBone(false, 526, 40, 0);
        String afk = new String(Files.readAllBytes(dir.resolve("AfkTasks.java")), StandardCharsets.UTF_8);
        boolean k2 = afk.contains("!(PrayerRun.ON && p.isArtificial() && PrayerRun.keepBone(true,") && (afk.contains("SmartAdventurers.brainOf(p) != null); // P53b") || afk.contains("SmartAdventurers.brainOf(p) != null) // P53b")); // P69: the gate became one clause of a longer condition
        check("K2 keepBone rules " + k1 + ", AfkTasks sell gate guarded by ON + artificial + smart brain " + k2, k1 && k2);

        // P69 (flaw hunt 11-14): #11 busy checks, #14 a failed add after paying banks the bones, #10 Watchdog rescue ends the bone run
        int planEnd = src.indexOf("void boneTick(long now) {", plan);
        String pb = plan > 0 && planEnd > plan ? src.substring(plan, planEnd) : "";
        boolean p11 = pb.contains("runner != null || companion != null || QuestRunner.active(bot)) return PrayerRun.skip(PrayerRun.R_BUSY, now);");
        boolean p14 = pb.contains("if (!bot.getInventory().add(new Item(id, got))) { bot.getBank().add(new Item(id, got));") && !pb.contains("bot.getInventory().add(new Item(id, got)); // free slots");
        String wd = new String(Files.readAllBytes(dir.resolve("Watchdog.java")), StandardCharsets.UTF_8);
        int resc = wd.indexOf("static void rescue(Player p,"), tele = wd.indexOf("Location bank = nearestBank(at, p);", resc);
        int hook = wd.indexOf("if (PrayerRun.ON && p.isArtificial()) try { SmartAdventurers.Brain sb = SmartAdventurers.brainOf(p); if (sb != null) sb.boneRescued(); }");
        boolean p10 = resc > 0 && hook > resc && hook < tele && src.contains("void boneRescued() { if (bone != 0) {") && src.contains("endBone(true); } }");
        check("P69 #11 planBoneRun busy checks (runner/companion/quest) " + p11 + ", #14 failed add banks the paid bones " + p14 + ", #10 rescue ends the bone run (flag-guarded, before the teleport) " + p10, p11 && p14 && p10);

        System.out.println("[TestP53Prayer] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
