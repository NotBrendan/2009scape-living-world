package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * B43 audit item 3 unit (pure, no bots): Slayer.parkStep {TRIP, PARK, EXPIRE}, and the ON/OFF behaviour of the park helpers
 * (parkOn, parkedNow, watchPlans). In a world with -Dtest.slayerPark=true; offline with main() (java -cp server.jar;out).
 * -Dslayerpark.expect=on|off (optional) also checks Slayer.PARK against the run's flags.
 */
public final class TestSlayerPark implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestSlayerPark] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.slayerPark")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            @Override public boolean pulse() {
                try { checks(); } catch (Throwable ex) { fail++; log("error " + ex); }
                return true;
            }
        });
    }

    public static void main(String[] a) { checks(); System.exit(fail == 0 ? 0 : 1); }

    static void checks() {
        final long H = 3600_000L, MIN = 60_000L;
        Slayer.ParkStep TRIP = Slayer.ParkStep.TRIP, PARK = Slayer.ParkStep.PARK, EXPIRE = Slayer.ParkStep.EXPIRE;
        // parkStep: open always TRIP
        check("open, 0 parks, new -> TRIP", Slayer.parkStep(0, 0, true) == TRIP);
        check("open, 3 parks, 6 h -> TRIP", Slayer.parkStep(3, 6 * H, true) == TRIP);
        check("open, 9 parks, 24 h -> TRIP", Slayer.parkStep(9, 24 * H, true) == TRIP);
        // closed
        check("closed, 0 parks -> PARK", Slayer.parkStep(0, 10 * MIN, false) == PARK);
        check("closed, 2 parks, 7 h -> PARK", Slayer.parkStep(2, 7 * H, false) == PARK);
        check("closed, 3 parks, 6 h - 1 ms -> PARK", Slayer.parkStep(3, 6 * H - 1, false) == PARK);
        check("closed, 3 parks, 6 h -> EXPIRE", Slayer.parkStep(3, 6 * H, false) == EXPIRE);
        check("closed, 5 parks, 8 h -> EXPIRE", Slayer.parkStep(5, 8 * H, false) == EXPIRE);
        check("closed, 10 parks, 1 h -> PARK", Slayer.parkStep(10, H, false) == PARK);
        // parkOn: flag AND openCheck
        check("parkOn(true,true)", Slayer.parkOn(true, true));
        check("!parkOn(true,false) (no watch, no way back)", !Slayer.parkOn(true, false));
        check("!parkOn(false,true)", !Slayer.parkOn(false, true));
        check("!parkOn(false,false)", !Slayer.parkOn(false, false));
        // parkedNow: ON inside the window only; OFF never
        long now = 1_000_000_000L;
        check("ON parked inside window", Slayer.parkedNow(true, now + 30 * MIN, now));
        check("ON window over", !Slayer.parkedNow(true, now, now));
        check("ON not parked (0)", !Slayer.parkedNow(true, 0, now));
        check("OFF never parked", !Slayer.parkedNow(false, now + 30 * MIN, now));
        // watchPlans: 4 only ON + first check after a park; OFF = 2 as before
        check("ON fresh park -> 4", Slayer.watchPlans(true, true) == 4);
        check("ON no fresh park -> 2", Slayer.watchPlans(true, false) == 2);
        check("OFF fresh -> 2", Slayer.watchPlans(false, true) == 2);
        check("OFF -> 2", Slayer.watchPlans(false, false) == 2);
        // constants per spec
        check("PARK_MS 30 min", Slayer.PARK_MS == 30 * MIN);
        // the run's flags
        String want = System.getProperty("slayerpark.expect", "");
        boolean flags = Slayer.flag(System.getProperty("bots.slayer.park")) && Slayer.flag(System.getProperty("bots.slayer.openCheck"));
        check("Slayer.PARK matches the run's flags", Slayer.PARK == flags);
        if (!want.isEmpty()) check("Slayer.PARK == expect " + want, Slayer.PARK == "on".equals(want));
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
    }
}
