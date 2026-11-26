package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * exit1 unit (pure, no bots): Exit1.surface / free (the essence-mine rule; top and goalDist had no caller and were removed, boot1) and the flag-off equivalence.
 * Offline with main() (java -cp server.jar;out content.custom.TestExit1); in a world with -Dtest.exit1=true it runs the same
 * checks once the world is up, prints "[TestExit1] RESULT PASS/FAIL pass=N fail=M" and then "[TestExit1] DONE".
 * -Dexit1.expect=on|off (optional) also checks Exit1.ON (-Dnav.freeExit) against it. No forced in-world phase: the farm
 * pair gate reads the "[Exit]" and "[Paid] ... long-teleport" lines instead (the design notes).
 */
public final class TestExit1 implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestExit1] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }
    static Location at(int x, int y) { return Location.create(x, y, 0); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.exit1")) return;
        GameWorld.getPulser().submit(new Pulse(20) {
            @Override public boolean pulse() {
                try { checks(); } catch (Throwable ex) { fail++; log("error " + ex); }
                log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
                log("DONE");
                return true;
            }
        });
    }

    public static void main(String[] a) {
        try { checks(); } catch (Throwable ex) { fail++; log("error " + ex); }
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
        log("DONE");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void checks() {
        final int MIN = 320;
        Location mine = at(2924, 4848), rock = at(2935, 4815), aubury = Exit1.AUBURY, bank = at(3253, 3421);
        Location tav = at(2884, 9798), edge = at(3096, 9867), falador = at(2946, 3368), ardougne = at(2655, 3283), hundred = at(3353, 3401);
        Location[] heres = {mine, rock, tav, edge, aubury, falador, at(2000, 9000)}, goals = {bank, falador, ardougne, hundred, tav, mine};

        // surface goals only
        check("surface(): not the mine, not underground", Exit1.surface(bank) && !Exit1.surface(mine) && !Exit1.surface(tav) && !Exit1.surface(null));

        // why the rule exists
        check("plain distance mine->bank is over TELEPORT_MIN (the bug)", bank.getDistance(mine) > MIN);

        // the free exit: only the mine, only a surface goal within TELEPORT_MIN of Aubury
        check("free(on, mine, bank)", Exit1.free(true, mine, bank, MIN) && Exit1.free(true, rock, bank, MIN));
        check("free(on, mine, 100 tiles from Aubury)", Exit1.free(true, mine, hundred, MIN));
        check("free(on, mine, exactly 320 away)", Exit1.free(true, mine, at(3573, 3401), MIN));
        check("!free(on, mine, 321 away): the teleport is paid as before", !Exit1.free(true, mine, at(3574, 3401), MIN));
        check("!free(on, mine, Ardougne)", !Exit1.free(true, mine, ardougne, MIN));
        check("!free(on, mine, goal in the mine)", !Exit1.free(true, mine, rock, MIN));
        check("!free(on, mine, underground goal)", !Exit1.free(true, mine, tav, MIN));
        check("!free(on, surface, bank): no free travel elsewhere", !Exit1.free(true, falador, bank, MIN) && !Exit1.free(true, aubury, bank, MIN));
        check("!free(on, ladder dungeon, ...): ladders stay with P90.climbOut", !Exit1.free(true, tav, falador, MIN) && !Exit1.free(true, edge, bank, MIN));
        check("!free(on, null)", !Exit1.free(true, null, bank, MIN) && !Exit1.free(true, mine, null, MIN));
        check("P90.climbOut leaves the mine out (why exit1 handles it)", P90.climbOut(mine, bank) == null);

        // flag off = today's behaviour: never free, the plain distance for every pair
        boolean neverFree = true;
        for (Location h : heres) for (Location g : goals) {
            if (Exit1.free(false, h, g, MIN)) neverFree = false;
        }
        check("flag off: free() false for all " + heres.length * goals.length + " pairs", neverFree);

        // wiring
        check("Exit1.ON follows -Dnav.freeExit", Exit1.ON == Boolean.getBoolean("nav.freeExit"));
        String expect = System.getProperty("exit1.expect");
        if (expect != null) check("Exit1.ON matches -Dexit1.expect=" + expect, Exit1.ON == "on".equals(expect));
        int before = Exit1.FREE.get();
        Exit1.left(null, "the unit test");
        check("left() counts one free exit", Exit1.FREE.get() == before + 1);
        Exit1.FREE.set(before);
        log("freeExit=" + Exit1.ON + " p90Fix=" + P90.ON + " teleportMin=" + AfkTasks.Runner.TELEPORT_MIN + " freeExits=" + Exit1.FREE.get());
    }
}
