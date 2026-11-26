package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W8 Runecrafting wave 5 (-Dartisans.altarLoop2, -Dbots.rc.runs; the design notes). Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW8Rc <addons-src>/content/custom
 * Run it bare (flags off) and with -Dartisans.altarLoop=true -Dartisans.altarLoop2=true -Dbots.rc.runs=true. Checks the pure
 * rules in RcRun/RcRuns, the new route rows against the engine (server.jar), and the wiring read from the source
 * (SmartAdventurers, Gates and MarketBrain cannot class-init outside a world), including that flags off keep train-62's paths.
 */
public final class TestW8Rc {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW8Rc] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean order(String src, String a, String b) { int i = src.indexOf(a), j = src.indexOf(b, Math.max(0, i)); return i >= 0 && j > i; }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW8Rc]   missing: " + p); return false; } return true; }
    static int count(String src, String s) { int n = 0, i = 0; while ((i = src.indexOf(s, i)) >= 0) { n++; i += s.length(); } return n; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), ga = read(dir, "Gates.java"), mb = read(dir, "MarketBrain.java"), rc = read(dir, "RcRun.java"), rr = read(dir, "RcRuns.java");

        // F: flags follow their properties, default off; altarLoop2 off keeps the W6 list object itself
        check("F flags: altarLoop2=" + RcRun.LOOP2 + " rc.runs=" + RcRuns.ON + " follow their properties",
                RcRun.LOOP2 == Boolean.getBoolean("artisans.altarLoop2") && RcRuns.ON == Boolean.getBoolean("bots.rc.runs"));
        check("F list: altarLoop2 off -> LIST is ALTARS (same string); on -> ALTARS plus " + RcRun.SECOND + " (" + RcRun.LIST + ")",
                RcRun.LOOP2 ? RcRun.listed(RcRun.LIST, RcRun.SECOND) && RcRun.LIST.startsWith(RcRun.ALTARS) : RcRun.LIST == RcRun.ALTARS);

        // L: the second route is added to the list
        check("L withSecond: off -> same object; on -> 'earth,body'; listed already -> same; empty -> 'body'",
                RcRun.withSecond(false, "earth", "body") == "earth" && "earth,body".equals(RcRun.withSecond(true, "earth", "body"))
                        && "earth,BODY".equals(RcRun.withSecond(true, "earth,BODY", "body")) && "body".equals(RcRun.withSecond(true, "", "body")));
        int[] ess = {7936}, rune = {1436};
        check("L body rune with 'earth,body' -> body row (Varrock West bank 3185,3436); with 'earth' -> null",
                RcRun.route(559, ess, "earth,body") != null && RcRun.route(559, rune, "earth,body").bankX == 3185 && RcRun.route(559, ess, "earth") == null);
        check("L earth still -> earth with the second route listed", RcRun.route(557, ess, "earth,body") != null && "earth".equals(RcRun.route(557, ess, "earth,body").name));

        // E: new rows match the engine; RcRuns.level matches the engine's Rune levels for every row
        boolean eng = true;
        for (String n : new String[]{"mind", "body"}) {
            RcRun.Route r = RcRun.byName(n); String u = n.toUpperCase();
            content.global.skill.runecrafting.MysteriousRuin ruin = content.global.skill.runecrafting.MysteriousRuin.valueOf(u);
            content.global.skill.runecrafting.Altar alt = content.global.skill.runecrafting.Altar.valueOf(u);
            content.global.skill.runecrafting.Rune ru = content.global.skill.runecrafting.Rune.valueOf(u);
            eng &= r != null && !r.w6 && ruin.getBase().getX() == r.ruinX && ruin.getBase().getY() == r.ruinY && ruin.getEnd().getX() == r.endX && ruin.getEnd().getY() == r.endY
                    && alt.getObject() == r.altarObj && alt.getPortal() == r.portalObj && ru.getRune().getId() == r.rune && ru.getExperience() == r.xp
                    && ruin.getTalisman().getTalisman().getId() == r.talisman && ruin.getTiara().getTiara().getId() == r.tiara;
        }
        boolean lv = true; for (RcRun.Route r : RcRun.ROUTES) lv &= RcRuns.level(r.name) == content.global.skill.runecrafting.Rune.valueOf(r.name.toUpperCase()).getLevel();
        check("E mind/body rows = engine (ruins, landing, altar/portal, rune, xp, talisman, tiara); levels = engine for all rows", eng && lv);
        boolean w6 = true; for (RcRun.Route r : RcRun.ROUTES) w6 &= r.w6 == (r.name.equals("air") || r.name.equals("earth") || r.name.equals("fire"));
        check("E only air/earth/fire are W6 rows (the W6 Gates line and its tests see exactly those)", w6 && RcRun.ROUTES.length == 5);

        // N: offline routes recorded (p38-navport.py): mind 216/212, body 187/195, rooms clear of the overworld and of each other
        RcRun.Route mind = RcRun.byName("mind"), body = RcRun.byName("body");
        boolean rooms = true;
        for (RcRun.Route a : RcRun.ROUTES) { int[] b = a.room(); rooms &= b[1] >= 4800 && b[0] <= a.endX && a.endX <= b[2]; for (RcRun.Route c : RcRun.ROUTES) if (a != c) rooms &= !(a.room()[0] == c.room()[0] && a.room()[1] == c.room()[1]); }
        check("N mind Falador West 216/212, body Varrock West 187/195; altar room boxes distinct, y 4800+",
                mind.bankX == 2946 && mind.navOut == 216 && mind.navBack == 212 && body.bankX == 3185 && body.navOut == 187 && body.navBack == 195 && rooms);

        // G: gates for the new rows only when used
        RcRun.Route air = RcRun.byName("air"), earth = RcRun.byName("earth");
        check("G gateNew: all off -> none; W6 rows never twice under altarLoop; body only with altarLoop+altarLoop2; air/mind with rc.runs",
                !RcRun.gateNew(body, false, false, "body", false, "air,mind") && !RcRun.gateNew(mind, false, false, "body", false, "air,mind")
                        && !RcRun.gateNew(air, true, true, "body", true, "air,mind") && RcRun.gateNew(air, false, false, "body", true, "air,mind")
                        && !RcRun.gateNew(earth, false, false, "body", true, "air,mind")
                        && RcRun.gateNew(body, true, true, "body", false, "") && !RcRun.gateNew(body, false, true, "body", false, "") && !RcRun.gateNew(body, true, false, "body", false, "")
                        && RcRun.gateNew(mind, false, false, "body", true, "air,mind") && !RcRun.gateNew(mind, true, true, "body", false, "air,mind"));

        // J: who may start a run
        check("J gate: low-level with the quest, a seat and the hour free -> OK; each refusal in order",
                RcRuns.gate(30, 10, true, 0, 3, 0, 6, 50, 44) == RcRuns.OK && RcRuns.gate(51, 10, true, 0, 3, 0, 6, 50, 44) == RcRuns.NO_COMBAT
                        && RcRuns.gate(30, 44, true, 0, 3, 0, 6, 50, 44) == RcRuns.NO_RC && RcRuns.gate(30, 10, false, 0, 3, 0, 6, 50, 44) == RcRuns.NO_QUEST
                        && RcRuns.gate(30, 10, true, 3, 3, 0, 6, 50, 44) == RcRuns.NO_SEAT && RcRuns.gate(30, 10, true, 2, 3, 6, 6, 50, 44) == RcRuns.NO_HOUR);

        // P: which altar
        String[] nm = {"air", "earth", "fire", "mind", "body"}; boolean[] none = new boolean[5];
        check("P pick: listed only, level allows, highest weight; mind needs 2; surplus out; nothing -> -1",
                RcRuns.pick(nm, none, new double[]{0, 9, 9, 0, 9}, 1, "air,mind", 0.6) == 0 && RcRuns.pick(nm, none, new double[]{0.2, 0, 0, 1.5, 0}, 2, "air,mind", 0.6) == 3
                        && RcRuns.pick(nm, none, new double[]{0.2, 0, 0, 1.5, 0}, 1, "air,mind", 0.6) == 0 && RcRuns.pick(nm, new boolean[]{true, false, false, false, false}, new double[]{5, 0, 0, 0, 0}, 5, "air,mind", 0.6) == 3
                        && RcRuns.pick(nm, new boolean[]{true, false, false, true, false}, new double[5], 5, "air,mind", 0.6) == -1 && RcRuns.pick(nm, none, new double[5], 5, "", 0.6) == -1);
        check("W weight: surplus 0, else bounty floored at the lane weight", RcRuns.weight(true, 5, 0.6) == 0 && RcRuns.weight(false, 0.1, 0.6) == 0.6 && RcRuns.weight(false, 2.5, 0.6) == 2.5);

        // Q: essence per run
        check("Q essFor: free slots less the talisman's, up to the trip; the shortfall (min 5) when wanted; under 5 -> 0",
                RcRuns.essFor(28, 26, 0, false) == 26 && RcRuns.essFor(20, 26, 0, false) == 19 && RcRuns.essFor(28, 26, 12, true) == 12
                        && RcRuns.essFor(28, 26, 2, true) == 5 && RcRuns.essFor(5, 26, 0, false) == 0 && RcRuns.essFor(4, 26, 0, true) == 0);

        // S: seats and the hour cap
        long t = 50_000_000L;
        java.util.Map<String, Long> seats = new java.util.HashMap<>(); seats.put("a", t + 10); seats.put("b", t - 1); seats.put("c", t + 99);
        java.util.Deque<Long> st = new java.util.ArrayDeque<>(); st.add(t - RcRuns.HOUR_MS - 1); st.add(t - 5); st.add(t);
        check("S seatsUsed counts live seats; hourUsed counts starts inside the hour", RcRuns.seatsUsed(seats, t) == 2 && RcRuns.hourUsed(st, t) == 2);

        // C: economy: nothing minted, essence/talisman from the bank or bot stock, runes only for essence removed, one per essence
        check("C essence from the own bank, then Ledger.consume; talisman bank/Ledger.consume; unplaced -> Ledger.supply; no tiara, no multiplier",
                all(rr, "p.getBank().remove(new Item(id, k))", "Ledger.consume(p, id, k, \"rc-ess\")", "Ledger.consume(p, r.talisman, 1, \"rc-key\")",
                        "if (placed < took) Ledger.supply(p, id, took - placed);", "{ Ledger.supply(p, r.talisman, 1); return false; }", "RcRun.keySource(RcRun.own(p, r), inBank, afford ? tal : 0, 0, false, packSlot)")
                        && order(rr, "if (k > 0 && bot.getInventory().remove(new Item(id, k))) n += k;", "bot.getInventory().add(new Item(r.rune, n))")
                        && count(rr, "new Item(r.rune") == 2 && !rr.contains("getMultiplier"));
        check("C runes sold at the end (Ledger.supply under the bot's name), talisman and leftover essence banked",
                all(rr, "Ledger.supply(p, r.rune, k)", "RcRun.bankKey(p, run.r);", "bankEssence(p);"));

        // R: RNG: RcRuns draws only the passed-in rng, once, after every filter (SmartAdventurers.RNG, flag on only)
        int sc = rr.indexOf("static double score(");
        check("R RcRuns uses no RNG field; one rng draw in score, after the filters", !rr.contains("RNG.") && count(rr, "rng.") == 1 && order(rr.substring(sc), "if (w <= 0) return -1;", "rng.nextDouble()"));

        // W: wiring, flags off = train-62
        check("W SA: rcRow field; JOBS row only in its own static block under RcRuns.ON",
                all(sa, "boolean rcRow; // W8", "static { if (RcRuns.ON) JOBS.add(RcRuns.row()); }") && count(sa, "RcRuns.row()") == 1);
        check("W SA chooseJob: the rcRow check is first in the loop and continues (other jobs untouched)",
                all(sa, "for (Job j : JOBS) {\r\n                if (j.rcRow) { double rs = RcRuns.score(this, j, combat, broke, RNG, nowMs); if (rs > bestScore) { bestScore = rs; best = j; } continue; }",
                        "\r\n                if (!reachable(j)) continue; // behind a quest"));
        check("W SA start/think hooks only on rcRow / rcRun (both unreachable with the flag off)",
                all(sa, "void start(Job job) {\r\n            if (job != null && job.rcRow) { RcRuns.start(this, job); return; }", "RcRuns.Run rcRun;",
                        "if (agiStep >= 0) { agilityTick(now); return; }\r\n            if (rcRun != null) { RcRuns.tick(this, now); return; }") && count(sa, "RcRuns.") == 6);
        check("W RcRuns: the quest rule is built lazily (reading RcRuns.ON never class-inits Gates)", !rr.contains("static final Gates.Rule") && rr.contains("if (quest == null) quest = Gates.quest(\"RUNE_MYSTERIES\""));
        check("W Gates: the W6 line still gates only W6 rows; the W8 line only under altarLoop2 or rc.runs",
                all(ga, "if (RcRun.ON) for (RcRun.Route r : RcRun.ROUTES) { if (!r.w6) continue; int[] b = r.room();",
                        "if (RcRun.LOOP2 || RcRuns.ON) for (RcRun.Route r : RcRun.ROUTES) if (RcRun.gateNew(r, RcRun.ON, RcRun.LOOP2, RcRun.SECOND, RcRuns.ON, RcRuns.ALTARS))"));
        check("W MarketBrain: [RC2] hourly line on its own timer only with altarLoop2 or rc.runs",
                all(mb, "if (RcRun.LOOP2 || RcRuns.ON) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(RcRuns.line()); }"));
        check("W RcRun: the runecrafter's batches use LIST (== ALTARS with altarLoop2 off)", all(rc, "Route r = route(plan.r.product, plan.r.inIds, LIST);") && !rc.contains("route(plan.r.product, plan.r.inIds, ALTARS)"));
        check("W log lines: [RC2] crafted/stall/finished/hourly", all(rr, "\"[RC2] crafted \" + n + \" at \" + r.name + \" altar by \"", "\"[RC2] stall at \"", "finished the ", "[RC2] hourly on=[")
                && RcRuns.line().startsWith("[RC2] hourly on=[") && RcRuns.line().contains(" runs: altars=" + RcRuns.ALTARS + " picks="));

        RcRun.Route mindR = RcRun.byName("mind"), bodyR = RcRun.byName("body"), earthR = RcRun.byName("earth");
        check("t63 fix 2 loopRow: W6 rows always; mind/body only as altarLoop2's named second route; the start hook drops the rest",
                RcRun.loopRow(earthR, false, "body") && !RcRun.loopRow(mindR, false, "body") && !RcRun.loopRow(bodyR, false, "body") && RcRun.loopRow(bodyR, true, "body")
                        && !RcRun.loopRow(mindR, true, "body") && RcRun.loopRow(mindR, true, "MIND") && !RcRun.loopRow(null, true, "body")
                        && order(rc, "Route r = route(plan.r.product, plan.r.inIds, LIST);", "if (!loopRow(r, LOOP2, SECOND)) return null;") && order(rc, "if (!loopRow(r, LOOP2, SECOND)) return null;", "if (parked(r, now)) { parkedSkips++; return null; }"));

        // w20 NOFIRE fix: the second rune is planned (q21/q21b: 46 batches, none of body)
        int[] ess20 = {RcRun.ESSENCE}, mixed = {RcRun.ESSENCE, 995};
        check("w20 secondRecipe: body from essence with both flags; not earth, not mind, not a mixed recipe, not with either flag off",
                RcRun.secondRecipe(true, true, "body", 559, ess20) && RcRun.secondRecipe(true, true, "body", 559, new int[]{RcRun.RUNE_ESSENCE}) && !RcRun.secondRecipe(true, true, "body", 557, ess20)
                        && !RcRun.secondRecipe(true, true, "body", 558, ess20) && !RcRun.secondRecipe(true, true, "body", 559, mixed) && !RcRun.secondRecipe(true, false, "body", 559, ess20)
                        && !RcRun.secondRecipe(false, true, "body", 559, ess20) && !RcRun.secondRecipe(true, true, "body", 559, null));
        check("w20 secondWant/secondLift: twice the target and +6 for the second rune only; under a player's order (+10) and a wanted potion (+8), over the value weighting of a 500 gp rune at zero stock",
                RcRun.secondWant(true, 150, 150) == 300 && RcRun.secondWant(false, 150, 150) == 150 && RcRun.secondWant(true, 0, 0) == 0 && RcRun.secondWant(true, 300, 150) == 300 /* F1: after the glut rule still 2x, not 4x */ && RcRun.secondWant(true, 340, 150) == 340 && RcRun.secondLift(true) == 6.0 && RcRun.secondLift(false) == 0.0
                        && RcRun.SECOND_LIFT < 8 && RcRun.SECOND_LIFT > 2 * Math.log10(10 + 500) + 0.2);
        String ar = read(dir, "Artisans.java");
        check("w20 Artisans.choose: the flag is read first, the want is raised before the stock test and the lift is added after the RNG draw",
                all(ar, "boolean rc2 = RcRun.LOOP2 && RcRun.secondRecipe(RcRun.ON, RcRun.LOOP2, RcRun.SECOND, r.product, r.inIds);", "if (rc2) want = RcRun.secondWant(true, want, target(r.product));", "if (rc2) score += RcRun.secondLift(true);")
                        && order(ar, "int want = target(r.product);", "boolean rc2 = RcRun.LOOP2 &&") && order(ar, "if (rc2) want = RcRun.secondWant(true, want, target(r.product));", "if (have >= want && owedQ == 0) continue;")
                        && order(ar, "+ RNG.nextDouble() * 0.2;", "if (rc2) score += RcRun.secondLift(true);"));

        int tf0 = RcRuns.tickFails; boolean noThrow = true; try { RcRuns.tick(null, 0L); } catch (Throwable ex) { noThrow = false; }
        check("t63 fix 4: RcRuns.tick is wrapped (a throw counts, logs once per bot and drops the run); errandNoStar counts an altar runner",
                noThrow && RcRuns.tickFails == tf0 + 1 && all(rr, "try { tick0(b, now); } catch (Throwable t) { fail(b, now, t); }", "if (FAILED.add(k)) System.out.println(\"[RC2] tick failed for \"", "try { end(b, now, \"error: \" + t); } catch (Throwable e) { SEAT.remove(k); b.rcRun = null;")
                        && all(sa, "|| Scenes.Greet.escorting(bot) || rcRun != null; }"));

        System.out.println("[TestW8Rc] " + pass + " passed, " + fail + " failed (altarLoop2 " + (RcRun.LOOP2 ? "ON" : "off") + ", rc.runs " + (RcRuns.ON ? "ON" : "off") + ")");
        if (fail > 0) System.exit(1);
    }
}
