package content.custom;

/**
 * P86 S5 units, plain java (no world): javac --release 11 -cp server.jar -sourcepath addons-src, then
 *   java -Dbots.summoning=true -Dbots.summoning.fun=<true|false> [-Dbots.summoning.out=N] content.custom.TestP86FunUnit <on|off>
 * Only Bob's statics are class-initialised (Bob reads its flags without touching Summon/SmartAdventurers).
 */
public final class TestP86FunUnit {
    static int pass, fail;
    static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }

    public static void main(String[] a) {
        boolean want = a.length > 0 && a[0].equals("on");
        check("flag " + want, Bob.FUN == want);
        // U1-like: pouch pick
        check("pick none", Bob.funPouch(5, 5, 0, 0, 0) == 0);
        check("pick wolf only", Bob.funPouch(5, 5, 3, 0, 0) == Bob.WOLF);
        check("pick fowl more", Bob.funPouch(5, 5, 1, 4, 0) == Bob.FOWL);
        check("pick tie -> wolf", Bob.funPouch(5, 5, 2, 2, 0) == Bob.WOLF);
        check("fowl needs 4", Bob.funPouch(3, 3, 0, 5, 0) == 0);
        check("fowl lvl3 falls to wolf", Bob.funPouch(3, 3, 1, 5, 0) == Bob.WOLF);
        check("no points", Bob.funPouch(5, 0, 3, 3, 0) == 0);
        check("keep S4 points", Bob.funPouch(20, 2, 3, 3, 2) == 0);
        check("keep S4 points ok", Bob.funPouch(20, 3, 3, 3, 2) == Bob.WOLF);
        check("bestBob none <13", Bob.bestBobPoints(12, id -> 5) == 0);
        check("bestBob snail", Bob.bestBobPoints(13, id -> id == 12019 ? 1 : 0) == 2);
        check("bestBob owned only", Bob.bestBobPoints(60, id -> id == 12087 ? 1 : 0) == 5);
        // U4 cap
        check("cap value", Bob.CAP == Math.max(1, Math.min(20, Integer.getInteger("bots.summoning.out", 20))));
        check("seats", Bob.FUN_SEATS == Math.min(6, Bob.CAP));
        check("seat free 0", Bob.seatFree(0, 0));
        check("S5 refused at 6", !Bob.seatFree(6, 6) && (Bob.CAP < 6 || Bob.seatFree(5, 5)));
        check("total refused at cap", !Bob.seatFree(Bob.CAP, 0));
        // walked / chance / gap / place
        check("walked hub", Bob.walked(false, 900, 320));
        check("walked GE near", Bob.walked(true, 300, 320));
        check("GE teleport", !Bob.walked(true, 321, 320));
        check("chance range", Math.abs(Bob.chance(0.2) - 0.10) < 1e-9 && Math.abs(Bob.chance(1.0) - 0.5) < 1e-9 && Bob.chance(5) <= 0.5);
        check("gap 2-4 h", Bob.gapMs(false, 0) == 7_200_000L && Bob.gapMs(false, 1) == 14_400_000L && Bob.gapMs(true, 0.5) == 180_000L);
        check("wild bad", Bob.badPlace(core.game.world.map.Location.create(3100, 3600, 0)));
        check("plane bad", Bob.badPlace(core.game.world.map.Location.create(3200, 3200, 1)));
        check("hub ok", !Bob.badPlace(core.game.world.map.Location.create(3164, 3487, 0)));
        // desire +0.20 (design 2.3)
        check("desire low pts", Math.abs(Bob.desirePlus(0.3, 5, 1, 2) - 0.5) < 1e-9);
        check("desire 1 pouch", Bob.desirePlus(0.3, 5, 1, 1) == 0.3);
        check("desire pts ok", Bob.desirePlus(0.3, 5, 2, 9) == 0.3);
        check("desire 30% of 40", Bob.desirePlus(0.3, 40, 11, 3) > 0.3 && Bob.desirePlus(0.3, 40, 12, 3) == 0.3);
        check("desire cap", Bob.desirePlus(0.9, 5, 0, 3) == 0.95);
        // U5/U8: leave safe with no player, no familiar, twice; flag off touches nothing
        Bob.leave(null, "x"); Bob.leave(null, "x");
        Bob.leaveAll("x"); Bob.sweep();
        check("tele null", Bob.tele(null) == null);
        check("no counters moved", Bob.SUMMONED + Bob.DISMISSED + Bob.ORPHANS + Bob.EXPIRED == 0 && Bob.OUT.isEmpty());
        check("desire off/null unchanged", Bob.desire(0.4, null) == 0.4);
        Bob.funSummon(null, 0, null, false); Bob.think(null, 0);
        System.out.println("TestP86FunUnit RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail + " FUN=" + Bob.FUN + " CAP=" + Bob.CAP);
        System.exit(fail == 0 ? 0 : 1);
    }
}
