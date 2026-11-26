package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.p42navguard=true -Dsmartbots.count=0, data/survey/nav-p0.bin present): P42, the guard leaves
 * no-routes to another connected region out of its distinct no-route count when -Dnav.guardIgnoreUnreachable is on.
 *   R1 crossRegion: Draynor bank -> Karamja dock and -> Canifis are other regions; Lumbridge -> Draynor bank is not; the
 *      planner itself has no route Draynor -> Karamja.
 *   R2 switch on: 30 distinct cross-region no-routes + 2 same-region -> SHADOW_FAILS 2, 30 left out; the window
 *      (baseline 5 no-route -> limit 10, 20 trips, steady bots, warm-up done) is not a bad one.
 *   R3 switch off (as train-7): the same 32 -> SHADOW_FAILS 32, the window is a bad one (badWindows 1).
 *   R4 the window cleared the left-out counter.
 * Waits up to ~3 minutes for the graph, runs on the game thread, then restores NavWalker's state. Expect "summary: PASS".
 */
public final class TestP42NavGuard implements StartupListener {
    static void log(String s) { System.out.println("[TestP42NavGuard] " + s); }
    static int fails;
    static void check(String name, boolean ok, String what) { if (!ok) fails++; log("RESULT " + name + " " + (ok ? "PASS" : "FAIL") + ": " + what); }

    static final Location DRAYNOR = Location.create(3092, 3245, 0), LUMBRIDGE = Location.create(3222, 3218, 0),
            KARAMJA = Location.create(2790, 3189, 0), CANIFIS = Location.create(3489, 3490, 0);

    /** 30 cross-region no-routes (distinct bots) and 2 same-region ones, as shadow() would note them. */
    static void feed() {
        for (int i = 0; i < 30; i++) NavWalker.noteNoRoute("p42bot" + i + "|" + (KARAMJA.getX() >> 3) + "," + (KARAMJA.getY() >> 3), DRAYNOR, i % 2 == 0 ? KARAMJA : CANIFIS);
        for (int i = 0; i < 2; i++) NavWalker.noteNoRoute("p42same" + i + "|" + (DRAYNOR.getX() >> 3) + "," + (DRAYNOR.getY() >> 3), LUMBRIDGE, DRAYNOR);
    }
    static void win() { NavWalker.plannerRescues = 0; NavWalker.WIN[0][0] = 20; NavWalker.botsOverride = 100; NavWalker.window(); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p42navguard")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 5) return false;
                if (!NavPlanner.built && t < 300) return false;
                boolean on0 = NavWalker.ON, ig0 = NavWalker.guardIgnoreUnreachable; double br = NavWalker.baseRescues, bf = NavWalker.baseFails;
                try {
                    check("graph built", NavPlanner.built, "NavPlanner.built " + NavPlanner.built + " after " + t + " ticks (needs data/survey/nav-p0.bin)");
                    boolean dk = NavPlanner.crossRegion(DRAYNOR, KARAMJA), dc = NavPlanner.crossRegion(DRAYNOR, CANIFIS), ld = NavPlanner.crossRegion(LUMBRIDGE, DRAYNOR);
                    check("R1 crossRegion", dk && dc && !ld, "Draynor->Karamja " + dk + ", Draynor->Canifis " + dc + ", Lumbridge->Draynor " + ld + " (want true, true, false)");
                    check("R1 planner agrees", NavPlanner.plan(DRAYNOR, KARAMJA, 1) == null && NavPlanner.plan(LUMBRIDGE, DRAYNOR, 1) != null, "no route Draynor->Karamja, a route Lumbridge->Draynor");

                    NavWalker.ON = true; NavWalker.baseRescues = 2; NavWalker.baseFails = 5; NavWalker.rescues0 = Watchdog.rescued; NavWalker.prevBots = -1;
                    NavWalker.SHADOW_FAILS.clear(); NavWalker.UNREACHABLE.set(0); NavWalker.arm();
                    for (int i = 0; i < 3; i++) win(); // F2 warm-up
                    NavWalker.guardIgnoreUnreachable = true; feed();
                    int kept = NavWalker.SHADOW_FAILS.size(), out = NavWalker.UNREACHABLE.get();
                    win();
                    check("R2 switch on", kept == 2 && out == 30 && NavWalker.badWindows == 0 && NavWalker.ON, "counted " + kept + ", left out " + out + ", badWindows " + NavWalker.badWindows + " (want 2, 30, 0)");
                    check("R4 counter cleared", NavWalker.UNREACHABLE.get() == 0 && NavWalker.SHADOW_FAILS.isEmpty(), "left out " + NavWalker.UNREACHABLE.get() + ", SHADOW_FAILS " + NavWalker.SHADOW_FAILS.size());
                    NavWalker.guardIgnoreUnreachable = false; feed();
                    kept = NavWalker.SHADOW_FAILS.size();
                    win();
                    check("R3 switch off", kept == 32 && NavWalker.badWindows == 1 && NavWalker.ON, "counted " + kept + ", badWindows " + NavWalker.badWindows + " (want 32, 1: over limit 10)");
                } catch (Throwable e) { fails++; log("error " + e); e.printStackTrace(System.out); }
                NavWalker.ON = on0; NavWalker.guardIgnoreUnreachable = ig0; NavWalker.baseRescues = br; NavWalker.baseFails = bf; NavWalker.botsOverride = -1; NavWalker.plannerRescues = 0;
                NavWalker.revertedAt = 0; NavWalker.prevBots = -1; NavWalker.SHADOW_FAILS.clear(); NavWalker.UNREACHABLE.set(0); NavWalker.arm();
                log("summary: " + (fails == 0 ? "PASS" : fails + " FAIL"));
                log("DONE");
                return true;
            }
        });
    }
}
