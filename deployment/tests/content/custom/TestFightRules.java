package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W2 lane M (2026-11-19): FightRules (combat steer + loot after) and its wiring. Plain java, no server or world needed.
 * SmartAdventurers and AfkTasks cannot be loaded outside a world, so the decisions live in FightRules (the class this test
 * calls is the class they call) and their side is checked in the source (W checks).
 *   javac --release 11 -cp server.jar -d out deployment/addons-src/content/custom/*.java deployment/addons-src/core/game/ge/*.java deployment/tests/content/custom/TestFightRules.java
 *   java -cp "out;server.jar" content.custom.TestFightRules off <addons-src/content/custom> [train-59 AfkTasks.java] [train-59 SmartAdventurers.java]
 *   java -Dbots.summoning=true -Dbots.summoning.combat=true -Dbots.summoning.combatSteer=true -Dbots.fight.lootAfter=true -cp "out;server.jar" content.custom.TestFightRules on <dir>
 *   java -Dbots.summoning.combatSteer=true -cp "out;server.jar" content.custom.TestFightRules alone <dir>   (steer without its parents stays off)
 * Checks:
 *   D: flag state for the mode (off: both off, x 3.0, cap 6; on: both on; alone: steer off).
 *   S: steerFactor: all 32 input rows, x only for on + fight job + multi spot + not Wilderness + pouch owner; steerX parsing.
 *   P: steerSpot: off / no pouch / no multi spot -> -1 (old pick kept); nearest enterable multi spot otherwise.
 *   L: lootOk: off or a player -> always true; on -> false before the first attack order, true after, false at the cap.
 *   M: a step model of fight()'s order on a littered field, driven by the real FightRules.lootOk: off = pack fills with 0 attack
 *      orders (the W1 sc-v2 failure); on = the first action is an attack and never more than cap loot actions between orders.
 *   W: wiring in the source: fight() asks lootOk before both pickUpLoot calls and before nearestNpc, an attack order resets
 *      lootSince; chooseJob multiplies by steerFactor behind FightRules.STEER; start() asks steerSpot; flags read in FightRules only.
 *   B (with the train-59 files): putting the old text back for each W2 edit gives the train-59 file (line ends aside: the checkout is CRLF, the git blob LF).
 * Prints one PASS/FAIL line per check and "[TestFightRules] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestFightRules {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestFightRules] " + (ok ? "PASS " : "FAIL ") + what); }

    /** fight()'s order on a field with `ground` loot items always in reach and a target always free. Returns {attack orders, loot actions, max loot actions between two orders, 1 if the first action was an attack}. */
    static int[] model(boolean on, boolean bot, int steps, int packFree, int cap) {
        int attacks = 0, lootSince = 0, loots = 0, maxRun = 0, run = 0, first = -1;
        for (int s = 0; s < steps && packFree > 0; s++) {
            boolean lootOk = !on || FightRules.lootOk(on, bot, attacks, lootSince, cap); // AfkTasks.Runner.fight(): boolean lootOk = !FightRules.LOOT_AFTER || lootOk();
            if (lootOk) { loots++; packFree--; run++; maxRun = Math.max(maxRun, run); if (on) lootSince++; if (first < 0) first = 0; continue; } // if (loot && lootOk && pickUpLoot()) return;
            attacks++; lootSince = 0; run = 0; if (first < 0) first = 1; // kills++; lootSince = 0; p.attack(n);
        }
        return new int[]{attacks, loots, maxRun, first};
    }

    static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }
    static String body(String src, String from, String to) { int a = src.indexOf(from); int b = a < 0 ? -1 : src.indexOf(to, a); return a < 0 || b < 0 ? "" : src.substring(a, b); }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "off";
        if (mode.equals("off")) check("D off by default: STEER " + FightRules.STEER + ", LOOT_AFTER " + FightRules.LOOT_AFTER + ", x " + FightRules.STEER_X + ", cap " + FightRules.LOOT_CAP,
                !FightRules.STEER && !FightRules.LOOT_AFTER && FightRules.STEER_X == 3.0 && FightRules.LOOT_CAP == 6);
        else if (mode.equals("on")) check("D on with all flags: STEER " + FightRules.STEER + ", LOOT_AFTER " + FightRules.LOOT_AFTER, FightRules.STEER && FightRules.LOOT_AFTER);
        else check("D combatSteer alone (no bots.summoning / .combat): STEER " + FightRules.STEER + ", LOOT_AFTER " + FightRules.LOOT_AFTER, !FightRules.STEER && !FightRules.LOOT_AFTER);

        // S: all 32 rows
        int xs = 0, ones = 0; boolean onlyRight = true;
        for (int m = 0; m < 32; m++) {
            boolean on = (m & 1) != 0, fight = (m & 2) != 0, multi = (m & 4) != 0, wild = (m & 8) != 0, owns = (m & 16) != 0;
            double f = FightRules.steerFactor(on, fight, multi, wild, owns, 3.0);
            boolean want = on && fight && multi && !wild && owns;
            if (f == 3.0) xs++; else if (f == 1.0) ones++;
            if ((f == 3.0) != want || (f != 3.0 && f != 1.0)) onlyRight = false;
        }
        check("S steerFactor over 32 rows: x on " + xs + " row, 1.0 on " + ones + " rows", onlyRight && xs == 1 && ones == 31);
        check("S steerFactor off is 1.0 even for an owner at a multi fight spot", FightRules.steerFactor(false, true, true, false, true, 3.0) == 1.0);
        check("S steerFactor: Wilderness spot 1.0, no pouch 1.0, not a fight job 1.0, single-combat 1.0",
                FightRules.steerFactor(true, true, true, true, true, 3.0) == 1.0 && FightRules.steerFactor(true, true, true, false, false, 3.0) == 1.0
                        && FightRules.steerFactor(true, false, true, false, true, 3.0) == 1.0 && FightRules.steerFactor(true, true, false, false, true, 3.0) == 1.0);
        check("S steerX: null 3.0, '2.5' 2.5, junk 3.0, 0.2 -> 1.0, 99 -> 20.0, NaN 3.0",
                FightRules.steerX(null) == 3.0 && FightRules.steerX("2.5") == 2.5 && FightRules.steerX("abc") == 3.0 && FightRules.steerX("0.2") == 1.0 && FightRules.steerX("99") == 20.0 && FightRules.steerX("NaN") == 3.0);
        // a worked score: the same job weight, the multi spot 300 tiles off against a single spot 60 tiles off (score = weight / (1 + dist / 150))
        double single = 1.0 / (1 + 60 / 150.0), multiOff = 1.0 / (1 + 300 / 150.0), multiOn = multiOff * FightRules.steerFactor(true, true, true, false, true, 3.0);
        check(String.format(java.util.Locale.ROOT, "S worked score: single 60 tiles %.3f; multi 300 tiles %.3f without, %.3f with the steer", single, multiOff, multiOn), multiOff < single && multiOn > single);

        // P
        boolean[] en = {true, true, true, false}, mu = {false, true, true, true}; double[] di = {5, 80, 40, 1};
        check("P steerSpot off -> -1", FightRules.steerSpot(false, true, en, mu, di) == -1);
        check("P steerSpot no pouch -> -1", FightRules.steerSpot(true, false, en, mu, di) == -1);
        check("P steerSpot nearest enterable multi spot: index " + FightRules.steerSpot(true, true, en, mu, di) + " (40 tiles; the 1-tile one cannot be entered, the 5-tile one is single)", FightRules.steerSpot(true, true, en, mu, di) == 2);
        check("P steerSpot no multi spot -> -1", FightRules.steerSpot(true, true, en, new boolean[]{false, false, false, false}, di) == -1);
        check("P steerSpot null arrays -> -1, short arrays safe", FightRules.steerSpot(true, true, null, mu, di) == -1 && FightRules.steerSpot(true, true, new boolean[]{true}, mu, di) == -1);

        // L
        boolean offAll = true;
        for (int a = 0; a < 3; a++) for (int l = 0; l < 20; l++) if (!FightRules.lootOk(false, true, a, l, 6) || !FightRules.lootOk(false, false, a, l, 6) || !FightRules.lootOk(true, false, a, l, 6)) offAll = false;
        check("L lootOk off (bot or player), and on for a player: always true", offAll);
        check("L lootOk on, bot: 0 attack orders -> false", !FightRules.lootOk(true, true, 0, 0, 6));
        check("L lootOk on, bot: after an order true for 0..5 loot actions, false at 6 (the cap) and beyond",
                FightRules.lootOk(true, true, 1, 0, 6) && FightRules.lootOk(true, true, 1, 5, 6) && !FightRules.lootOk(true, true, 1, 6, 6) && !FightRules.lootOk(true, true, 9, 7, 6));

        // M
        int[] off = model(false, true, 700, 26, 6), on = model(true, true, 700, 26, 6), pl = model(true, false, 700, 26, 6);
        check("M model off, littered field: attacks " + off[0] + ", loot actions " + off[1] + " (pack full), first action " + (off[3] == 1 ? "attack" : "loot"), off[0] == 0 && off[1] == 26 && off[3] == 0);
        check("M model on: attacks " + on[0] + ", loot actions " + on[1] + ", most loot between two orders " + on[2] + ", first action " + (on[3] == 1 ? "attack" : "loot"), on[3] == 1 && on[0] >= 5 && on[2] <= 6 && on[1] == 26);
        check("M model on, a player (::afk): as off, attacks " + pl[0] + ", loot actions " + pl[1], pl[0] == 0 && pl[1] == 26);

        // W
        String dir = args.length > 1 ? args[1] : "deployment/addons-src/content/custom";
        String afk = read(dir + "/AfkTasks.java"), sa = read(dir + "/SmartAdventurers.java"), fr = read(dir + "/FightRules.java"), bob = read(dir + "/Bob.java");
        String fight = body(afk, "        void fight() {", "        boolean styleNoXp() {");
        int iOk = fight.indexOf("boolean lootOk = !FightRules.LOOT_AFTER || lootOk();"), iL1 = fight.indexOf("if (lootTicks == 0 && lootOk && pickUpLoot())"), iL2 = fight.indexOf("if (loot && lootOk && pickUpLoot())"),
                iNpc = fight.indexOf("NPC n = nearestNpc();"), iAtk = fight.indexOf("lootSince = 0;"), iP = fight.indexOf("p.attack(n);");
        check("W fight(): lootOk asked (" + iOk + ") before both pickUpLoot calls (" + iL1 + ", " + iL2 + ") and nearestNpc (" + iNpc + ")", iOk > 0 && iOk < iL1 && iL1 < iL2 && iL2 < iNpc);
        check("W fight(): no pickUpLoot() call left without lootOk (calls " + (fight.split("pickUpLoot\\(\\)", -1).length - 1) + ", guarded 2)", fight.split("pickUpLoot\\(\\)", -1).length - 1 == 2);
        check("W fight(): the attack order resets lootSince just before p.attack(n)", iAtk > iNpc && iAtk < iP);
        check("W Runner.lootOk() is FightRules.lootOk with the live flag, attack orders, lootSince and cap",
                afk.contains("boolean ok = FightRules.lootOk(FightRules.LOOT_AFTER, p.isArtificial(), kills, lootSince, FightRules.LOOT_CAP);"));
        check("W lootSince only moves under the flag (2 guarded increments)", afk.split("if \\(FightRules\\.LOOT_AFTER\\) lootSince\\+\\+;", -1).length - 1 == 2 && afk.split("lootSince\\+\\+", -1).length - 1 == 2);
        String choose = body(sa, "        Job chooseJob(boolean crewFilter) {", "            try { questPick = QuestBook.pick(");
        int iHub = choose.indexOf("if (Hubs.on()) score *= Hubs.jobFactor("), iSt = choose.indexOf("if (FightRules.STEER) {"), iSf = choose.indexOf("double sf = FightRules.steerFactor(true, fightJob(j), steerOwn > 0 && fightJob(j) && steerMultiJob(j), j.hunt != null && Bob.wild(j.hunt.spot), steerOwn > 0, FightRules.STEER_X);"),
                iMul = choose.indexOf("if (sf != 1.0) { score *= sf;"), iBest = choose.indexOf("if (score > bestScore) { bestScore = score; best = pickJ; }");
        check("W chooseJob: steerFactor behind FightRules.STEER, multiplied into the score before the best pick (" + iHub + " < " + iSt + " < " + iSf + " < " + iMul + " < " + iBest + ")", iHub > 0 && iHub < iSt && iSt < iSf && iSf < iMul && iMul < iBest);
        check("W chooseJob: owner asked from Bob.steerOwner once per choice", choose.contains("if (steerOwn == 0) steerOwn = Bob.steerOwner(bot) ? 1 : -1;"));
        check("W start(): steerSpot behind FightRules.STEER for a fight task's spots", sa.contains("if (FightRules.STEER && job.task.kind == AfkTasks.Kind.FIGHT && spots.length > 1) {") && sa.contains("int si = FightRules.steerSpot(true, Bob.steerOwner(p), en, mu, di);"));
        int steerUses = sa.split("FightRules\\.", -1).length - 1, steerGuards = sa.split("if \\(FightRules\\.STEER", -1).length - 1;
        check("W SmartAdventurers: " + steerGuards + " FightRules.STEER guards (chooseJob factor, chooseJob log, start spot), " + steerUses + " FightRules mentions in all (10 code, 2 comments)", steerGuards == 3 && steerUses == 12);
        check("W Bob.steerOwner uses combatPlan's own row test and Summon.unlocked; steerMulti is multiAt remembered",
                bob.contains("return p != null && p.isArtificial() && Summon.unlocked(p)") && bob.contains("if (b == null) { b = multiAt(l); STEER_MULTI.put(l, b); }"));
        check("W the two flags are read in FightRules only", fr.contains("Boolean.getBoolean(\"bots.summoning.combatSteer\")") && fr.contains("Boolean.getBoolean(\"bots.fight.lootAfter\")")
                && !afk.contains("(\"bots.fight.lootAfter") && !sa.contains("(\"bots.fight.lootAfter") && !afk.contains("combatSteer\"") && !sa.contains("combatSteer\"") && !bob.contains("combatSteer\""));

        // B
        if (args.length > 3) {
            String a0 = read(args[2]).replace("\r\n", "\n"), s0 = read(args[3]).replace("\r\n", "\n"); // line ends aside: the checkout is CRLF, the git blob LF
            String a = afk.replace("\r\n", "\n");
            a = cut(a, "            boolean lootOk = !FightRules.LOOT_AFTER || lootOk(); //", "\n", "");
            a = a.replace("if (lootTicks == 0 && lootOk && pickUpLoot()) { lootTicks = 2; if (FightRules.LOOT_AFTER) lootSince++; }", "if (lootTicks == 0 && pickUpLoot()) { lootTicks = 2; }");
            a = a.replace("if (loot && lootOk && pickUpLoot()) { if (FightRules.LOOT_AFTER) lootSince++; wait = 1; return; }", "if (loot && pickUpLoot()) { wait = 1; return; }");
            a = cut(a, "            lootSince = 0; // W2 lane M", "\n", "");
            a = cut(a, "\n        /**\n         * W2 lane M (-Dbots.fight.lootAfter, only called with the flag on)", "int lootSince, lootHeld, lootHeldFirst;\n", "");
            a = cut(a, " if (FightRules.LOOT_AFTER && loot) System.out.println(\"[LootAfter] \"", "famUsed);", "");
            a = a.replace("on or off; W2 lane M: the loot-after line (flag on only)", "on or off");
            check("B AfkTasks with the W2 edits taken out equals train-59 (" + a.length() + " vs " + a0.length() + " chars)", a.equals(a0));
            String s = sa.replace("\r\n", "\n");
            s = cut(s, "            int steerOwn = 0; java.util.Set<Job> steeredJ = null;", "\n", "");
            s = cut(s, "                if (FightRules.STEER) { // W2 lane M (-Dbots.summoning.combatSteer)", "steeredJ.add(j); }\n                }\n", "");
            s = cut(s, "            if (FightRules.STEER && best != null && steeredJ != null", "\n", "");
            s = cut(s, "        /** W2 lane M combat steer (FightRules.STEER only): is this fight job's spot multi-combat?", "            return false;\n        }\n", "");
            s = cut(s, "                if (FightRules.STEER && job.task.kind == AfkTasks.Kind.FIGHT && spots.length > 1) {", "FightRules.STEER_SPOTS++; }\n                }\n", "");
            check("B SmartAdventurers with the W2 edits taken out equals train-59 (" + s.length() + " vs " + s0.length() + " chars)", s.equals(s0));
        }

        System.out.println("[TestFightRules] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    /** Removes the first stretch from `from` to the end of the next `to` (inclusive). No match: unchanged. */
    static String cut(String s, String from, String to, String with) {
        int a = s.indexOf(from); if (a < 0) return s;
        int b = s.indexOf(to, a + from.length()); if (b < 0) return s;
        return s.substring(0, a) + with + s.substring(b + to.length());
    }
}
