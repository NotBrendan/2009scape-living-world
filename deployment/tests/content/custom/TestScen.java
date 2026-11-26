package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * W8 scenarios: Scen's pure rules, the flags-off no-ops and the hook wiring, plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestScen <addons-src>/content/custom
 * Run bare (no seed flags, no harness markers): Seed.ON is false, so every hook must count nothing.
 */
public final class TestScen {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestScen] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }

    static Map<String, Integer> m(Object... kv) { Map<String, Integer> r = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], (Integer) kv[i + 1]); return r; }
    static String v(String n, Map<String, Integer> c, long... x) { return Scen.verdict(n, k -> c.getOrDefault(k, 0), x); }

    public static void main(String[] args) throws Exception {
        // A flags off: nothing seeded, nothing judged, hooks count nothing
        Scen.pass(null, true, true, 5); Scen.passCantPay(null); Scen.finish(null, "Stuck at 1,1"); Scen.stuck(null, null); Scen.attack(null, null);
        Scen.tooManySteps(null); Scen.noRoute(null, null); Scen.fare(null, "x", true, -5, 10); Scen.capTrip(null, 9, 9, 9, 0, false); Scen.limpPut();
        Scen.rcStart(null); Scen.rcEnd(null); Scen.staffAfter(null, Scen.staffBefore(null));
        check("A flags off: ON " + Seed.ON + ", scen " + Seed.SCEN.length + ", ticks " + Seed.TICKS + ", job '" + Seed.JOB + "', rcstick " + Seed.RC_STICK + ", counters " + Scen.C.size() + ", limpThread " + Scen.limpThread,
                !Seed.ON && Seed.SCEN.length == 0 && Seed.TICKS == 0 && Seed.JOB.isEmpty() && !Seed.RC_STICK && Seed.MAGIC == 0 && Seed.job(null) == null
                        && Scen.C.isEmpty() && Scen.RC_OPEN.isEmpty() && Scen.limpThread == null && !Scen.started && Scen.staffBefore(null) == null);

        // B names and the boxes
        String[] n = Scen.names(" FIX9B, capfix,nope,fix9b ,iceGiantFix");
        check("B names: known only, once, canonical case " + String.join("/", n), n.length == 3 && n[0].equals("fix9b") && n[1].equals("capFix") && n[2].equals("iceGiantFix") && Scen.names(null).length == 0 && Scen.names(" ").length == 0
                && "Ice giant".equals(Seed.names("Ice_giant, Cow")[0]) && "Cow".equals(Seed.names("Ice_giant, Cow")[1]));
        check("B boxes: pass box holds Al Kharid bank, the pass and the Desert Wolf spot, not Lumbridge; ice pocket rim",
                Scen.passBox(3270, 3167, 0) && Scen.passBox(3304, 3116, 0) && Scen.passBox(3258, 3078, 0) && !Scen.passBox(3222, 3218, 0) && !Scen.passBox(3270, 3167, 1)
                        && Scen.icePocket(2814, 3510, 0) && Scen.icePocket(2798, 3500, 0) && !Scen.icePocket(2797, 3510, 0) && !Scen.icePocket(2895, 3445, 0));
        check("B stall reasons", Scen.stuckWhy("Stuck at 3300,3120: try walking") && Scen.stuckWhy("Got stuck, teleported out.") && Scen.stuckWhy("Couldn't find a path to x") && !Scen.stuckWhy("Out of food.") && !Scen.stuckWhy(null));

        // C money rules
        check("C fare: paid = coins out within the fare, refused = none moved, never up",
                Scen.fareOk(true, 120, 120) && Scen.fareOk(true, 100, 60) && !Scen.fareOk(true, 0, 120) && !Scen.fareOk(true, -5, 120) && !Scen.fareOk(true, 121, 120)
                        && Scen.fareOk(false, 0, 120) && !Scen.fareOk(false, 7, 120));
        check("C cap: a trip begun at the cap adds no counted leg; exempt never over paid (unless the hour reset)",
                Scen.capOver(3, 1, 3) && !Scen.capOver(3, 0, 3) && !Scen.capOver(2, 2, 3) && Scen.exemptOver(3, 2, false) && !Scen.exemptOver(3, 2, true) && !Scen.exemptOver(2, 2, false));
        check("C staff: coins up = minted, coins out with nothing in = paidNothing, refund to zero = ok",
                "minted".equals(Scen.staffBad(-1, 1, 0)) && "paidNothing".equals(Scen.staffBad(15, 0, 0)) && Scen.staffBad(15, 1, 0) == null && Scen.staffBad(40, 0, 40) == null && Scen.staffBad(0, 0, 0) == null);

        // D verdicts
        Map<String, Integer> ok9 = m("pass.south", 2, "pass.north", 1);
        check("D fix9b: both ways = PASS; each failure named", v("fix9b", ok9) == null && v("fix9b", m("pass.south", 1)) != null
                && v("fix9b", m("pass.south", 1, "pass.north", 1, "pass.stuck", 1)).contains("stuck") && v("fix9b", m("pass.south", 1, "pass.north", 1, "too_many_steps", 1)).contains("steps")
                && v("fix9b", m("pass.south", 1, "pass.north", 1, "pass.fareBad", 1)).contains("fare") && v("fix9b", m("pass.south", 1, "pass.north", 1, "pass.noRoute", 1)).contains("route")
                && v("fix9b", m("pass.south", 1, "pass.north", 1, "pass.cantPay", 1)).contains("pay"));
        check("D capFix", v("capFix", m("cap.trips", 3, "fare.paid", 2)) == null && v("capFix", m("cap.trips", 3, "fare.paid", 2, "cap.over", 1)) != null
                && v("capFix", m("cap.trips", 3, "fare.paid", 2, "fare.bad", 1)) != null && v("capFix", m("fare.paid", 2)) != null && v("capFix", m("cap.trips", 1, "fare.paid", 1, "cap.exemptOver", 1)) != null);
        check("D alchAll {done, maxHour, cap, capped, needCap}", v("alchAll", m(), 3, 2000, 2000, 1, 1) == null && v("alchAll", m(), 3, 2001, 2000, 1, 1) != null
                && v("alchAll", m(), 0, 0, 2000, 0, 1) != null && v("alchAll", m(), 3, 900, 2000, 0, 1) != null && v("alchAll", m(), 3, 900, 20000, 0, 0) == null);
        check("D anyStaff", v("anyStaff", m("staff.calls", 4), 1) == null && v("anyStaff", m("staff.calls", 4), 0) != null && v("anyStaff", m("staff.calls", 4, "staff.minted", 1), 1) != null
                && v("anyStaff", m("staff.calls", 4, "staff.paidNothing", 1), 1) != null && v("anyStaff", m(), 1) != null);
        check("D herbSupply {limpStarts, playerSpot, botSpot, sameThread}", v("herbSupply", m(), 1, 0, 1, 1) == null && v("herbSupply", m(), 1, 1, 1, 1) != null
                && v("herbSupply", m(), 1, 0, 1, 0) != null && v("herbSupply", m(), 0, 0, 1, 1) != null && v("herbSupply", m(), 1, 0, 0, 1) != null);
        check("D altarLoop {loops, crafted, giveUps, stick, oldestOpen}", v("altarLoop", m("rc.runs", 2), 2, 40, 0, 0, 60_000) == null && v("altarLoop", m("rc.runs", 2), 2, 40, 0, 0, Scen.RC_MAX_MS + 1) != null
                && v("altarLoop", m("rc.runs", 2), 2, 0, 1, 1, 0) == null && v("altarLoop", m("rc.runs", 2), 2, 0, 0, 1, 0) != null && v("altarLoop", m("rc.runs", 2), 2, 0, 0, 0, 0) != null && v("altarLoop", m(), 0, 0, 0, 0, 0) != null);
        check("D iceGiantFix", v("iceGiantFix", m(), 1) == null && v("iceGiantFix", m(), 0) != null && v("iceGiantFix", m("ice.pocket", 1), 1) != null && v("x", m()) != null);

        // W wiring, read from source
        String dir = args.length > 0 ? args[0] : "deployment/addons-src/content/custom";
        String afk = read(dir + "/AfkTasks.java"), wd = read(dir + "/Watchdog.java"), sl = read(dir + "/Slayer.java"), led = read(dir + "/Ledger.java"), sa = read(dir + "/SmartAdventurers.java"),
                art = read(dir + "/Artisans.java"), rc = read(dir + "/RcRun.java"), seed = read(dir + "/Seed.java");
        boolean w1 = afk.contains("final long c0 = Seed.ON ? Ledger.Wallet.coins(p) : 0;") && afk.contains("if (Seed.ON) Scen.pass(p, south, south && !paid, c0 - Ledger.Wallet.coins(p));")
                && afk.contains("Scen.passCantPay(p); return -1;") && afk.contains("Scen.finish(p, why);") && afk.contains("Scen.attack(p, n.getLocation());");
        boolean w2 = count(wd, "Scen.stuck(p, at);") == 2 && sl.contains("if (pl == -1 && Seed.ON) Scen.noRoute(") && sl.contains("s.paidSnap >= 0 && Seed.ON) { int now0 = paidCount(bot); Scen.capTrip(");
        int pt = led.indexOf("public static boolean payTeleport(Player p, String why) {");
        boolean w3 = pt > 0 && led.indexOf("if (!Seed.ON || p == null || !p.isArtificial() || W6Magic.TELE) return payTeleport0(p, why);", pt) - pt < 100 && led.indexOf("return payTeleport0(p, why);", pt) > pt && led.contains("static boolean payTeleport0(Player p, String why) {");
        boolean w4 = sa.contains("Scen.tooManySteps(bot); farmStalled(\"too many steps\")") && sa.contains("final long[] w8 = Scen.staffBefore(bot); boolean w8r = w6BasicStaff(); Scen.staffAfter(bot, w8); if (w8r) equipSupplies();")
                && sa.contains("AfkTasks.SPOTS.put(AfkTasks.Task.LIMPWURT, limpAt); Scen.limpPut();") && sa.indexOf("String sj = Seed.job(bot);") > sa.indexOf("String sh = Seed.hunt(bot, slaySt.had != null);");
        boolean w5 = art.contains("rc = null; Scen.rcEnd(bot);") && art.contains("if (rc != null) Scen.rcStart(bot);") && count(rc, "if (!Seed.RC_STICK) bot.getProperties().setTeleportLocation(r.ruin());") == 2
                && seed.contains("if (SCEN.length > 0) Scen.start(SCEN, TICKS);") && seed.contains("\"magic\", \"job\", \"rcstick\", \"scen\", \"ticks\"");
        check("W wiring: pass/finish/attack " + w1 + ", watchdog/openGate/settle " + w2 + ", payTeleport wrapper first " + w3 + ", SmartAdventurers " + w4 + ", altar loop + seed " + w5, w1 && w2 && w3 && w4 && w5);

        System.out.println("[TestScen] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static int count(String s, String a) { int n = 0, i = 0; while ((i = s.indexOf(a, i)) >= 0) { n++; i += a.length(); } return n; }
    static String read(String f) throws Exception { return new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8); }
}
