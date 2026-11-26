package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W16 (2026-11-21): the three W16 switches (bots.fight.karaRetreat, bots.kara.dockWalk, bots.craft.spinJob). Plain java, no
 * server or world needed (AfkTasks cannot be loaded outside a world, so the decisions live in W16's pure rules).
 *   java -cp "out;server.jar" content.custom.TestW16 off <addons-src/content/custom>
 *   java -Dbots.fight.karaRetreat=true -Dbots.kara.dockWalk=true -Dbots.craft.spinJob=true -cp "out;server.jar" content.custom.TestW16 on <dir>
 * Checks: D flag state; K the Karamja retreat rule; W the dock-walk rule for other legs; S the spin rule; H the hooks in
 * the source (each behind its flag, the flag read first, no RNG in W16).
 * Prints one PASS/FAIL line per check and "[TestW16] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestW16 {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestW16] " + (ok ? "PASS " : "FAIL ") + what); }

    public static void main(String[] a) throws Exception {
        boolean on = a.length > 0 && a[0].equals("on");
        check("D flags " + (on ? "on" : "off"), W16.KARA_RETREAT == on && W16.DOCK_WALK == on && W16.SPIN_JOB == on && W16.KARA_ANY == on);

        // K: item 1, the flee off Karamja. Args: retreatOn, walkOn, retreat, out, onKaramja, coins, fare, fareFits, farOn
        check("K1 flee on Karamja with the fare walks to the dock", W16.dockWalk(true, false, true, false, true, 30, 30, true, false));
        check("K2 flag off: never", !W16.dockWalk(false, false, true, false, true, 5000, 30, true, false));
        check("K3 no fare: the old fallbacks", !W16.dockWalk(true, true, true, false, true, 29, 30, true, false));
        check("K4 full pack and no coins in it: no", !W16.dockWalk(true, true, true, false, true, 5000, 30, false, false));
        check("K5 not on Karamja: no", !W16.dockWalk(true, true, true, false, false, 5000, 30, true, false));
        check("K6 the way out to Karamja keeps the 120-tile rule", !W16.dockWalk(true, true, true, true, true, 5000, 30, true, false));
        check("K7 bound far past the landing: teleport as before", !W16.dockWalk(true, true, true, false, true, 5000, 30, true, true));
        check("K8 karaRetreat alone leaves bank trips alone", !W16.dockWalk(true, false, false, false, true, 5000, 30, true, false));

        // W: item 3, the other legs
        check("W1 bank trip off Karamja with dockWalk", W16.dockWalk(false, true, false, false, true, 30, 30, true, false));
        check("W2 dockWalk alone leaves the flee alone", !W16.dockWalk(false, true, true, false, true, 5000, 30, true, false));
        check("W3 no fare", !W16.dockWalk(false, true, false, false, true, 0, 30, true, false));

        // B (w20 NOFIRE fix): the boat test. Args: out, inMusaBox, lifted
        check("B1 a lifted dock walk outside the Musa Point box takes the boat (was refused: the box is all within 120 of the dock)", W16.boatHere(false, false, true));
        check("B2 no lift outside the box: no boat, as before", !W16.boatHere(false, false, false));
        check("B3 inside the box and the way out: as before", W16.boatHere(false, true, false) && W16.boatHere(true, false, false));
        // R (w20 NOFIRE fix): the flee's bank. Args: on, onKaramja, dockDist, near, coins, fare, bankNone, bankOnKaramja, bankFarPastLanding
        check("R1 Brimhaven flee, nearest bank Yanille (far past the landing): bound for the landing's bank", W16.retreatToSarim(true, true, 226, 120, 30, 30, false, false, true));
        check("R2 no bank in reach: the same", W16.retreatToSarim(true, true, 400, 120, 5000, 30, true, false, false));
        check("R3 flag off: never", !W16.retreatToSarim(false, true, 226, 120, 5000, 30, false, false, true) && !W16.retreatToSarim(false, true, 400, 120, 5000, 30, true, false, false));
        check("R4 within 120 of the dock: the old boat rule", !W16.retreatToSarim(true, true, 120, 120, 5000, 30, false, false, true));
        check("R5 no fare: the old fallbacks", !W16.retreatToSarim(true, true, 226, 120, 29, 30, false, false, true));
        check("R6 not on Karamja: no", !W16.retreatToSarim(true, false, 226, 120, 5000, 30, true, false, false));
        check("R7 a bank on the island, or a mainland one near the landing: left alone", !W16.retreatToSarim(true, true, 226, 120, 5000, 30, false, true, true) && !W16.retreatToSarim(true, true, 226, 120, 5000, 30, false, false, false));

        // S: item 2, flax then spin. Args: on, flaxJob, crafting, wheel, chainOk
        check("S1 Crafting 10 with a wheel: go", W16.spinDecision(true, true, 10, true, true) == W16.SPIN_GO);
        check("S2 flag off", W16.spinDecision(false, true, 99, true, true) == W16.SPIN_OFF);
        check("S3 not a flax job", W16.spinDecision(true, false, 99, true, true) == W16.SPIN_OFF);
        check("S4 Crafting 9", W16.spinDecision(true, true, 9, true, true) == W16.SPIN_LOW);
        check("S5 no wheel", W16.spinDecision(true, true, 50, false, true) == W16.SPIN_NO_WHEEL);
        check("S6 the chain check refused", W16.spinDecision(true, true, 50, true, false) == W16.SPIN_PROBLEM);
        check("S7 a reason for every code", W16.SPIN_WHY.length == 5 && W16.SPIN_LEVEL == 10);

        // H: hooks in the source
        if (a.length > 1) {
            String afk = new String(Files.readAllBytes(Paths.get(a[1], "AfkTasks.java")), StandardCharsets.UTF_8);
            String sa = new String(Files.readAllBytes(Paths.get(a[1], "SmartAdventurers.java")), StandardCharsets.UTF_8);
            String w = new String(Files.readAllBytes(Paths.get(a[1], "W16.java")), StandardCharsets.UTF_8);
            check("H1 goTo keeps the 120-tile test first and reads the flag before the rule", afk.contains("boolean w20Lift = W16.KARA_ANY && W16.dockWalk(W16.KARA_RETREAT, W16.DOCK_WALK, s == State.RETREAT, out, !crossOnly && karamja(p.getLocation()),")
                    && afk.contains("if (!farOn && fareFits && (board.getDistance(p.getLocation()) <= BOAT_NEAR || w20Lift) && W16.boatHere(out, QuestPiratesTreasure.onKaramja(p), w20Lift)) {"));
            check("H1b the flee's bank hook is behind its flag, right after nearestBank()", afk.contains("if (W16.KARA_RETREAT) { Location kb = W16.retreatBank(this, b); if (kb != null) b = kb; }")
                    && afk.indexOf("Location b = nearestBank(); // pathing review 10-23 A-3") < afk.indexOf("if (W16.KARA_RETREAT) { Location kb") && afk.indexOf("if (W16.KARA_RETREAT) { Location kb") < afk.indexOf("b = escapePoint();"));
            check("H2 the dock line is behind the flag", afk.contains("if (W16.KARA_ANY && board.getDistance(p.getLocation()) > BOAT_NEAR) { W16.dockLine("));
            check("H3 AfkTasks names W16 on one statement pair only", afk.split("W16\\.", -1).length - 1 == 9);
            check("H4 the spin hook is behind its flag and skips hunts, chains and errands", sa.contains("if (W16.SPIN_JOB && job.hunt == null && job.chain == 0 && errandFor == null && job.task == AfkTasks.Task.FLAX) W16.spinJob(p, r, spotAt);"));
            check("H5 SmartAdventurers names W16 twice (flag, call)", sa.split("W16\\.", -1).length - 1 == 2);
            String code = w.substring(w.indexOf("final class W16"));
            check("H6 W16 draws no RNG", !code.contains("RNG") && !code.contains("Random") && !code.contains("random("));
            check("H7 W16 gives nothing (no add, no coins, no teleport)", !code.contains(".add(") && !code.contains("teleport(") && !code.contains("Wallet.give") && !code.contains("Ledger.supply"));
        }
        System.out.println("[TestW16] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
