package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * W17a (2026-11-21): the Warriors' Guild defenders switch (bots.str.defenders). Plain java, no server or world needed
 * (the run itself needs a world; the decisions live in W17a's pure rules).
 *   java -cp "out;server.jar" content.custom.TestW17a off <addons-src/content/custom>
 *   java -Dbots.str.defenders=true -cp "out;server.jar" content.custom.TestW17a on <dir>
 * Checks: D flag state; E eligibility (door rule, start decision); O defender order; R room and leave rules; L the line
 * convention; H the hooks in the source (each behind W17a.ON, read first; no RNG in W17a; the draft class is gone).
 * Prints one PASS/FAIL line per check and "[TestW17a] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestW17a {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestW17a] " + (ok ? "PASS " : "FAIL ") + what); }

    static Set<Integer> set(int... ids) { Set<Integer> s = new HashSet<>(); for (int i : ids) s.add(i); return s; }

    public static void main(String[] a) throws Exception {
        boolean on = a.length > 0 && a[0].equals("on");
        check("D flag " + (on ? "on" : "off"), W17a.ON == on);

        // E: the door rule and the start decision
        check("E1 65 + 65 passes the door", W17a.entry(65, 65));
        check("E2 64 + 65 does not", !W17a.entry(64, 65));
        check("E3 99 Attack alone passes", W17a.entry(99, 1) && W17a.entry(1, 99));
        check("E4 98 + 1 does not", !W17a.entry(98, 1));
        long cool = W17a.COOLDOWN_MS;
        // args: on, attack, strength, best, setTier, tokens, food, packFits, sinceLast, botsThere
        check("E5 flag off: never, whatever the bot has", W17a.decide(false, 99, 99, -1, 6, 5000, 20, true, cool, 0) == W17a.OFF);
        check("E6 eligible bot with a set and food goes", W17a.decide(true, 70, 70, -1, 0, 0, 6, true, cool, 0) == W17a.GO);
        check("E7 eligible bot with 100 tokens and no set goes", W17a.decide(true, 70, 70, 2, -1, 100, 6, true, cool, 0) == W17a.GO);
        check("E8 under the door rule: no", W17a.decide(true, 60, 60, -1, 6, 5000, 20, true, cool, 0) == W17a.WEAK);
        check("E9 rune defender held: done", W17a.decide(true, 70, 70, 6, 6, 5000, 20, true, cool, 0) == W17a.DONE);
        check("E10 inside the cooldown: no", W17a.decide(true, 70, 70, -1, 0, 0, 6, true, cool - 1, 0) == W17a.COOL);
        check("E11 guild full: no", W17a.decide(true, 70, 70, -1, 0, 0, 6, true, cool, W17a.MAX_BOTS) == W17a.CROWDED);
        check("E12 99 tokens and no set: no (nothing is granted)", W17a.decide(true, 70, 70, -1, -1, 99, 6, true, cool, 0) == W17a.NO_TOKENS);
        check("E13 under " + W17a.MIN_FOOD + " food: no", W17a.decide(true, 70, 70, -1, 0, 0, W17a.MIN_FOOD - 1, true, cool, 0) == W17a.NO_FOOD);
        check("E14 no pack room for the kit: no", W17a.decide(true, 70, 70, -1, 0, 0, 6, false, cool, 0) == W17a.PACK);
        check("E15 every decision has a reason", W17a.WHY.length == W17a.PACK + 1);

        // O: defender order bronze -> iron -> steel -> black -> mithril -> adamant -> rune
        check("O1 ids in the engine's order", java.util.Arrays.equals(W17a.DEFENDERS, new int[]{8844, 8845, 8846, 8847, 8848, 8849, 8850}));
        check("O2 names in order", String.join(",", W17a.METAL).equals("bronze,iron,steel,black,mithril,adamant,rune"));
        check("O3 none held: bronze first", W17a.best(id -> false) == -1 && W17a.next(-1) == 8844);
        boolean chain = true; for (int i = 0; i < 6; i++) chain &= W17a.next(i) == W17a.DEFENDERS[i + 1] && W17a.dropIndex(i) == i + 1;
        check("O4 each defender leads to the next", chain);
        check("O5 rune held: nothing wanted, the engine drops rune again", W17a.next(6) == -1 && W17a.dropIndex(6) == 6);
        Set<Integer> h = set(8844, 8846);
        check("O6 best is the highest held, not the count", W17a.best(h::contains) == 2 && W17a.next(2) == 8847);
        check("O7 a spare lower one does not move the order back", W17a.best(set(8848, 8844)::contains) == 4);
        check("O8 wield levels 1,1,5,10,20,30,40", java.util.Arrays.equals(W17a.DEF_LEVEL, new int[]{1, 1, 5, 10, 20, 30, 40}));
        check("O9 Defence 39 with rune held wears adamant", W17a.wearable(6, 39) == 5 && W17a.wearable(6, 40) == 6);
        check("O10 Defence 1 wears iron at most, none held wears none", W17a.wearable(6, 1) == 1 && W17a.wearable(-1, 99) == -1);
        check("O11 names", W17a.name(1).equals("iron defender") && W17a.name(-1).equals("no defender") && W17a.indexOf(8850) == 6 && W17a.indexOf(1) == -1);
        check("O12 full set only: helm + body + legs of one metal", W17a.setTier(set(1155, 1117, 1075)::contains) == 0 && W17a.setTier(set(1155, 1117, 1067)::contains) == -1 && W17a.setTier(set(1163, 1127, 1079, 1155, 1117, 1075)::contains) == 6);

        // R: rooms and leaving
        check("R1 a set and under the goal: animators", W17a.room(0, 0).equals("animator") && W17a.room(W17a.TOKENS_GOAL - 1, 3).equals("animator"));
        check("R2 the goal met: cyclopes", W17a.room(W17a.TOKENS_GOAL, 3).equals("cyclopes"));
        check("R3 no set, 100 tokens: cyclopes", W17a.room(100, -1).equals("cyclopes"));
        check("R4 no set, 99 tokens: leave", W17a.room(99, -1).equals("leave"));
        check("R5 in the room with tokens and food: stay", W17a.leaveWhy(50, 3, 80, 2, 1000) == null);
        check("R6 under 10 tokens: out", "out of tokens".equals(W17a.leaveWhy(9, 3, 80, 2, 1000)));
        check("R7 no food and hurt: out; no food but healthy: stay", "out of food".equals(W17a.leaveWhy(50, 0, 49, 2, 1000)) && W17a.leaveWhy(50, 0, 50, 2, 1000) == null);
        check("R10 w18 fuller load: bank food up to the free slots less 2", W17a.SPARE == 2 && W17a.foodMore(14, 40) == 12 && W17a.foodMore(14, 5) == 5 && W17a.foodMore(2, 40) == 0 && W17a.foodMore(0, 40) == 0 && W17a.foodMore(9, 0) == 0);
        check("R11 w18 out of food: one bank trip with 100+ tokens, 4+ food banked, inside the run length", W17a.refill(false, 100, 4, 1000) && !W17a.refill(true, 400, 40, 1000) && !W17a.refill(false, 99, 40, 1000) && !W17a.refill(false, 400, 3, 1000) && !W17a.refill(false, 400, 40, W17a.RUN_MS));
        check("R8 rune held: out", "has the rune defender".equals(W17a.leaveWhy(500, 9, 99, 6, 1000)));
        check("R9 past the run length: out", "long enough".equals(W17a.leaveWhy(500, 9, 99, 2, W17a.RUN_MS + 1)));
        check("R10 guild and room bounds", W17a.inGuild(2847, 3541) && !W17a.inGuild(2877, 3546) && W17a.inCyclopes(2847, 3541, 2) && !W17a.inCyclopes(2846, 3541, 2) && !W17a.inCyclopes(2840, 3538, 2) && W17a.inCyclopes(2844, 3552, 2) && W17a.inCyclopes(2838, 3554, 2) && !W17a.inCyclopes(2850, 3541, 0));

        // L: the line convention
        check("L1 tag then the bot name", W17a.line("Bob12", "got iron defender").equals("[Defenders] Bob12 got iron defender"));
        check("L2 fire regex matches", W17a.line("Bob12", "got " + W17a.name(0)).matches("^\\[Defenders\\] \\S+ got (bronze|iron|steel|black|mithril|adamant|rune) defender.*"));
        check("L3 enter regex matches", W17a.line("Bob12", "enters the guild (Attack 70").matches("^\\[Defenders\\] \\S+ enters the guild .*"));

        // H: hooks in the source
        if (a.length > 1) {
            String sa = new String(Files.readAllBytes(Paths.get(a[1], "SmartAdventurers.java")), StandardCharsets.UTF_8);
            String w = new String(Files.readAllBytes(Paths.get(a[1], "W17a.java")), StandardCharsets.UTF_8);
            int calls = 0, guarded = 0;
            for (String ln : sa.split("\n")) {
                int i = ln.indexOf("W17a.");
                if (i < 0) continue;
                String code = ln.contains("//") ? ln.substring(0, ln.indexOf("//", i) < 0 ? ln.length() : ln.indexOf("//", i)) : ln;
                for (String m : new String[]{"W17a.tick(", "W17a.plan(", "W17a.on(", "W17a.rescue("}) {
                    int at = code.indexOf(m);
                    if (at < 0) continue;
                    calls++;
                    int g = code.lastIndexOf("W17a.ON", at);
                    if (g >= 0 && (code.substring(g, at).matches("W17a\\.ON && ") || code.substring(g, at).matches("W17a\\.ON\\) try \\{ "))) guarded++;
                }
            }
            check("H1 four hooks in SmartAdventurers (brain start, errandNoStar, tick, plan)", calls == 4);
            check("H2 each hook reads W17a.ON first", guarded == calls && calls > 0);
            check("H3 tick hook follows the Hunter tick, plan hook the Hunter plan", sa.indexOf("Hunter.tick(this, now)") < sa.indexOf("W17a.tick(this, now)") && sa.indexOf("W17a.tick(this, now)") < sa.indexOf("if (slay != 0) { Slayer.tick(this, now)")
                    && sa.indexOf("Hunter.plan(this, now)") < sa.indexOf("W17a.plan(this, now)") && sa.indexOf("W17a.plan(this, now)") < sa.indexOf("planFarmRun(now)) return;"));
            check("H4 no RNG in W17a", !w.contains("RNG.next") && !w.contains("nextInt") && !w.contains("Random") && !w.contains("random("));
            check("H5 no coins or granted items in W17a", !w.contains("Wallet") && !w.contains("Ledger") && !w.contains("MarketBrain"));
            check("H6 the flag is default off and read once", w.contains("static final boolean ON = Boolean.getBoolean(\"bots.str.defenders\");") && w.split("getBoolean\\(", -1).length == 2);
            check("H9 w18 the food trip: once a run, not counted as a leave, the kit kept, food only from the bot's own bank", w.contains("if (NO_FOOD_WHY.equals(why) && refill(r.refilled, tokens(p), bankFood(p), now - r.start)) { r.refilled = true; why = REFILL; }")
                    && w.contains("if (!back) leaves++;") && w.contains("if (REFILL.equals(r.why) && restock(r, p, now)) return true; bank(r, p); end(r, \"done\");") && w.contains("while (more > 0 && p.getBank().getAmount(f) > 0 && pull(p, f, 1)) more--;"));
            check("H7 the draft class is gone", !Files.exists(Paths.get(a[1], "W13Defenders.java")));
            check("H8 plan, tick and rescue stop first when off", w.contains("static boolean plan(SmartAdventurers.Brain br, long now) {\n        if (!ON".replace("\n", w.contains("\r\n") ? "\r\n" : "\n"))
                    && w.contains("if (!ON || br == null || br.bot == null) return false;") && w.contains("if (!ON || p == null) return false;"));
        }
        System.out.println("[TestW17a] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
