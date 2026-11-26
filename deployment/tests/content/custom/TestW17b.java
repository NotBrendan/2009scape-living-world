package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W17b (2026-11-21): nature rune runs behind -Dbots.rc.natureLaw. Plain java, no server or world needed (AfkTasks cannot be
 * loaded outside a world, so the decisions live in W17b's pure rules).
 *   java -cp "out;server.jar" content.custom.TestW17b off <addons-src/content/custom>
 *   java -Dbots.rc.natureLaw=true -cp "out;server.jar" content.custom.TestW17b on <dir>
 * Checks: D flag state; E eligibility; S essence count and coins; L route legs; R the route against the engine's own
 * runecrafting enums (server.jar); H the hooks in the source (each behind the flag, the flag read first, one RNG draw).
 * Prints one PASS/FAIL line per check and "[TestW17b] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestW17b {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestW17b] " + (ok ? "PASS " : "FAIL ") + what); }

    static Object field(Object o, String name) throws Exception { java.lang.reflect.Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    static int count(String s, String sub) { int n = 0; for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++; return n; }
    static String lineWith(String s, String sub) { for (String l : s.split("\n")) if (l.contains(sub)) return l; return ""; }

    public static void main(String[] a) throws Exception {
        boolean on = a.length > 0 && a[0].equals("on");
        check("D1 flag " + (on ? "on" : "off"), W17b.ON == on && W13c.NATURE_LAW == on);
        check("D2 W13c eligibility agrees with the flag", on ? "nature".equals(W13c.natureLaw(44, true, false, false)) : W13c.natureLaw(99, true, true, true) == null);
        check("D3 law is not run (no Entrana travel)", !"law".equals(W13c.natureLaw(99, true, true, false)));

        // E: gate(on, rc, key, quest, coins, need, pure, minEss, mainland, seatsUsed, seats)
        check("E1 Runecrafting 44 with a key, the quest, 60 gp, 20 essence: go", W17b.gate(true, 44, true, true, 60, 60, 20, 20, true, 0, 2) == W17b.OK);
        check("E2 flag off: never", W17b.gate(false, 99, true, true, 99999, 60, 500, 20, true, 0, 2) == W17b.OFF);
        check("E3 Runecrafting 43: no", W17b.gate(true, 43, true, true, 60, 60, 20, 20, true, 0, 2) == W17b.NO_RC);
        check("E4 no talisman or tiara to be had: no", W17b.gate(true, 60, false, true, 5000, 60, 100, 20, true, 0, 2) == W17b.NO_KEY);
        check("E5 no Rune Mysteries: no", W17b.gate(true, 60, true, false, 5000, 60, 100, 20, true, 0, 2) == W17b.NO_QUEST);
        check("E6 59 gp: no (two fares)", W17b.gate(true, 60, true, true, 59, 60, 100, 20, true, 0, 2) == W17b.NO_COINS);
        check("E7 19 pure essence: no", W17b.gate(true, 60, true, true, 5000, 60, 19, 20, true, 0, 2) == W17b.NO_ESS);
        check("E8 already on Karamja or underground: no", W17b.gate(true, 60, true, true, 5000, 60, 100, 20, false, 0, 2) == W17b.NOT_MAINLAND);
        check("E9 seats full: no", W17b.gate(true, 60, true, true, 5000, 60, 100, 20, true, 2, 2) == W17b.NO_SEAT);
        check("E10 the gate with the real flag", (W17b.gate(W17b.ON, 44, true, true, 60, 60, 20, 20, true, 0, 2) == W17b.OK) == on);

        // S: coins and essence
        check("S1 two fares", W17b.need(false, 400) == 60 && W17b.FARE == 30);
        check("S9 w30 review: both paid crossings (and a bought talisman); the boat walk is off", W17b.need(false, 400, 180) == 360 && W17b.need(true, 400, 180) == 760 && W17b.need(false, 0, 0) == 2 && !W17b.boatWalks());
        check("S10 w30 review: a finished run rests the row 30 min by default, a stall 60 min", W17b.RUN_REST == 30 * 60_000L && W17b.restUntil(1000, false) == 1000 + W17b.RUN_REST && W17b.restUntil(1000, true) == 1000 + W17b.STALL_REST);
        check("S2 two fares and a bought talisman", W17b.need(true, 400) == 460 && W17b.need(true, 0) == 61);
        check("S3 27 free slots with coins in the pack: 27", W17b.essFor(27, true, 500) == 27);
        check("S4 no coins in the pack: one slot kept for the fare", W17b.essFor(27, false, 500) == 26);
        check("S5 no more than the bot owns", W17b.essFor(27, true, 12) == 12 && W17b.essFor(0, false, 12) == 0);
        check("S6 defaults", W17b.MIN_ESS == 20 && W17b.SEATS == 2 && W17b.LEVEL == 44 && W17b.WEIGHT == 2.0 && W17b.WEIGHT_DEF == 2.0 && W17b.weightProp("0.6") == 0.6 && W17b.weightProp("x") == 2.0);
        check("S7 w18 the bank leg stows everything but coins, talisman and pure essence", !W17b.stow(995) && !W17b.stow(W17b.TALISMAN) && !W17b.stow(RcRun.ESSENCE) && W17b.stow(379) && W17b.stow(1265) && W17b.stow(W17b.RUNE));
        check("S8 w18 a pack emptied to talisman and coins takes 26 essence, over MIN_ESS (q09: 13 free slots gave 13)", W17b.essFor(26, true, 100) == 26 && W17b.essFor(13, true, 100) < W17b.MIN_ESS);
        check("S9 w18 a broke bot's score is one trip of runes in coins, cut by the walk and the seats", W17b.brokeScore(200, 100, 0, 0, 1.0) == 200.0 * 27 && W17b.brokeScore(200, 20, 150, 1, 1.0) == 200.0 * 20 / 2 / 2 && W17b.brokeScore(0, 100, 0, 0, 1.0) == 27.0 && W17b.brokeScore(200, 0, 0, 0, 1.0) == 0);

        // L: legs
        W17b.Leg[] want = {W17b.Leg.BANK, W17b.Leg.DOCK_OUT, W17b.Leg.SEA_OUT, W17b.Leg.RUINS, W17b.Leg.ALTAR, W17b.Leg.CRAFT, W17b.Leg.DOCK_BACK, W17b.Leg.SEA_BACK, W17b.Leg.HOME};
        boolean order = W17b.Leg.values().length == want.length; W17b.Leg l = W17b.Leg.BANK;
        for (int i = 0; order && i < want.length; i++) { order = l == want[i]; l = W17b.after(l); }
        check("L1 bank, dock, sea, ruins, altar, craft, dock, sea, home, then the end", order && l == null);
        check("L2 the run starts and ends at the Draynor bank", W17b.target(W17b.Leg.BANK)[0] == 3092 && W17b.target(W17b.Leg.BANK)[1] == 3243 && java.util.Arrays.equals(W17b.target(W17b.Leg.BANK), W17b.target(W17b.Leg.HOME)));
        check("L3 out by the Port Sarim seamen, back by the Musa Point customs dock", W17b.target(W17b.Leg.DOCK_OUT)[0] == 3028 && W17b.target(W17b.Leg.DOCK_OUT)[1] == 3220 && W17b.target(W17b.Leg.DOCK_BACK)[0] == 2953 && W17b.target(W17b.Leg.DOCK_BACK)[1] == 3149);
        check("L4 the crossings and the altar room are not surface walks", W17b.target(W17b.Leg.SEA_OUT) == null && W17b.target(W17b.Leg.SEA_BACK) == null && W17b.target(W17b.Leg.ALTAR) == null && W17b.target(W17b.Leg.CRAFT) == null);
        check("L5 island legs: the ruins walk and the dock walk back only", W17b.onIsland(W17b.Leg.RUINS) && W17b.onIsland(W17b.Leg.DOCK_BACK) && !W17b.onIsland(W17b.Leg.BANK) && !W17b.onIsland(W17b.Leg.DOCK_OUT) && !W17b.onIsland(W17b.Leg.HOME) && !W17b.onIsland(W17b.Leg.SEA_OUT));
        check("L6 the boat is asked for at the dock only (inside the 120-tile rule)", W17b.atDock(8) && !W17b.atDock(9) && !W17b.atDock(119) && W17b.DOCK_RULE == 120);
        check("L7 the bank is within the boat rule of the Port Sarim dock", Math.hypot(W17b.BANK_X - W17b.SEAMEN_X, W17b.BANK_Y - W17b.SEAMEN_Y) < W17b.DOCK_RULE);

        // R: the engine's own data
        content.global.skill.runecrafting.MysteriousRuin ruin = content.global.skill.runecrafting.MysteriousRuin.valueOf("NATURE");
        content.global.skill.runecrafting.Altar altar = content.global.skill.runecrafting.Altar.valueOf("NATURE");
        content.global.skill.runecrafting.Rune rune = content.global.skill.runecrafting.Rune.valueOf("NATURE");
        core.game.world.map.Location base = (core.game.world.map.Location) field(ruin, "base"), end = (core.game.world.map.Location) field(ruin, "end");
        check("R1 the ruins tile is beside the engine's ruin (base " + base + ")", Math.abs(base.getX() - W17b.RUIN_X) <= 3 && Math.abs(base.getY() - W17b.RUIN_Y) <= 3 && base.getZ() == 0);
        check("R2 the altar room landing is the engine's (" + end + ")", end.getX() == W17b.END_X && end.getY() == W17b.END_Y);
        check("R3 altar and portal objects", ((Integer) field(altar, "object")) == W17b.ALTAR_OBJ && ((Integer) field(altar, "portal")) == W17b.PORTAL_OBJ);
        check("R4 rune id, level and xp", ((core.game.node.item.Item) field(rune, "rune")).getId() == W17b.RUNE && ((Integer) field(rune, "level")) == W17b.LEVEL && ((Double) field(rune, "experience")) == W17b.XP);
        check("R5 talisman and tiara ids", ((core.game.node.item.Item) field(content.global.skill.runecrafting.Talisman.valueOf("NATURE"), "talisman")).getId() == W17b.TALISMAN
                && ((core.game.node.item.Item) field(content.global.skill.runecrafting.Tiara.valueOf("NATURE"), "tiara")).getId() == W17b.TIARA && W17b.TALISMAN == W13c.NATURE_TALI && W17b.TIARA == W13c.NATURE_TIARA);
        check("R6 the run's route row", W17b.R.NATURE.rune == 561 && W17b.R.NATURE.talisman == 1462 && W17b.R.NATURE.xp == 9.0 && W17b.R.NATURE.bankX == 3092 && W17b.R.NATURE.endY == 4835 && RcRun.xp(W17b.R.NATURE, 27) == 243.0);
        boolean inTable = false; for (RcRun.Route r : RcRun.ROUTES) if (r.name.equals("nature") || r.rune == 561) inTable = true;
        check("R7 RcRun.ROUTES is untouched (5 rows, no nature)", !inTable && RcRun.ROUTES.length == 5);

        // H: hooks in the source
        if (a.length > 1) {
            String sa = new String(Files.readAllBytes(Paths.get(a[1], "SmartAdventurers.java")), StandardCharsets.UTF_8);
            String rc = new String(Files.readAllBytes(Paths.get(a[1], "RcRuns.java")), StandardCharsets.UTF_8);
            String w = new String(Files.readAllBytes(Paths.get(a[1], "W17b.java")), StandardCharsets.UTF_8);
            String af = new String(Files.readAllBytes(Paths.get(a[1], "AfkTasks.java")), StandardCharsets.UTF_8);
            check("H1 SmartAdventurers: the row added only with the flag, the broke hook reads the flag first", count(sa, "W17b.") == 4 && count(sa, "if (W17b.ON && brokeFree && best != null && best.rcRow && W17b.mine(best) && bestScore > bestFreeScore) bestFree = null;") == 1 && sa.indexOf("if (W17b.ON && brokeFree && best != null && best.rcRow && W17b.mine(best) && bestScore > bestFreeScore) bestFree = null;") < sa.indexOf("if (brokeFree && bestFree != null) {") && sa.contains("static { if (W17b.ON) JOBS.add(W17b.row()); }"));
            check("H2 the nature row comes after the rune-run row", sa.indexOf("if (RcRuns.ON) JOBS.add(RcRuns.row())") > 0 && sa.indexOf("if (RcRuns.ON) JOBS.add(RcRuns.row())") < sa.indexOf("if (W17b.ON) JOBS.add(W17b.row())"));
            check("H3 RcRuns: three hooks, each with the flag read first", count(rc, "W17b.ON && ") == 3 && lineWith(rc, "W17b.score(").trim().startsWith("if (W17b.ON && W17b.mine(j))") && lineWith(rc, "W17b.start(").trim().startsWith("if (W17b.ON && W17b.mine(job))") && lineWith(rc, "W17b.tick(").trim().startsWith("if (W17b.ON && b != null && b.rcRun instanceof W17b.NRun)"));
            check("H4 one RNG draw in W17b (the score's jitter), no Math.random", count(w, "rng.next") == 1 && !w.contains("Math.random") && !w.contains("new Random") && !w.contains("RandomFunction"));
            check("H5 w30 the crossing is the paid teleport (one pay call), not the boat; AfkTasks itself untouched by W17b", count(w, "AfkTasks.Runner.crossSea(") == 0 && count(w, "Ledger.Wallet.payTeleport(bot, \"sea-crossing\")") == 1 && count(w, "Spectacle.teleport(bot, Watchdog.landing(to))") == 1 && !af.contains("W17b"));
            check("H13 w30 review: the gate and the bank leg ask for two fares; both ways a run finishes rest the row", w.contains("need(buy, MarketBrain.value(TALISMAN), crossFare())") && w.contains("Ledger.Wallet.coins(bot) < 2L * crossFare()") && count(w, "b.blacklist.put(LABEL, restUntil(now, false));") == 2 && count(w, "b.blacklist.put(LABEL, restUntil(now, true))") == 2 && !w.contains("now + STALL_REST)"));
            check("H12 w30 no leg walks to a dock; the way back is paid or the run ends at the ruins with its runes sold", !w.contains("arrive(b, run, now, R.SEAMEN") && !w.contains("arrive(b, run, now, R.CUSTOMS") && w.contains("if (fly(b, run, now, r.bank(), Leg.SEA_BACK)) return;") && w.contains("done at the ruins, no coins for the way back") && w.contains("(run.sea == null || !run.sea.crossing)"));
            check("H6 nothing granted: essence only moves bank <-> pack, runes only from essence", !w.contains("Ledger.consume(") && count(w, "new Item(RUNE, n)") == 1 && w.contains("getInventory().remove(new Item(RcRun.ESSENCE, n))"));
            check("H7 every line starts [NatureRun] then the bot", count(w, "System.out.println(\"[NatureRun] \" + ") == count(w, "System.out.println(") && w.contains("\" crafted \" + n + \" nature runes\""));
            check("H9 w18 the pack is stowed before the key and the essence come out, into the bot's own bank", w.indexOf("!stow(it.getId())") > 0 && w.indexOf("!stow(it.getId())") < w.indexOf("RcRuns.fetchKey(bot, r)") && w.contains("bot.getInventory().remove(copy) && !bot.getBank().add(copy)) bot.getInventory().add(copy)"));
            check("H10 w18 broke is scored, not refused; the tally line is the only line without a bot", !w.contains("if (broke || b.crew != null) return -1") && w.contains("if (broke) { brokeScored++;") && count(w, "\"tally scored=\"") == 1);
            check("H11 w30 a stall away from the bank banks the talisman and the essence (no jammed pack), runes untouched", w.contains("unload(p); else stowRun(p);") && w.contains("static void stowRun(Player p)") && !lineWith(w, "p.getInventory().getAmount(RcRun.ESSENCE); if (k > 0").isEmpty() && count(w, "stowRun(") == 4 && !w.substring(w.indexOf("static void stowRun"), w.indexOf("static void stall(")).contains("sellRunes"));
            check("H8 W17b's own statics load no other custom class", !lineWith(w, "static final int SEATS").contains("RcRuns") && !lineWith(w, "static final double WEIGHT").contains("RcRuns"));
        }
        System.out.println("[TestW17b] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
