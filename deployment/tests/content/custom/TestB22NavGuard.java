package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.b22navguard=true -Dsmartbots.count=0): planner-guard-fix F1-F3 (the design notes G-5).
 * Calls NavWalker.window() with synthetic counters (limits: baseline 2 rescues / 5 no-route -> 4 and 10; 100 bots):
 *   W1-W3 F2 warm-up: 20 planner rescues and 20 trips, no bad window, no revert.
 *   W4    F1: 30 rescues in the world, none by the planner -> not bad.
 *   W5    F1: 20 planner rescues, 20 trips -> bad (badWindows 1).
 *   W6    F3: 20 planner rescues, 3 trips -> badWindows stays 1.
 *   W7    F2: bot count 100 -> 50 (moving) -> warm, badWindows stays 1.
 *   W8    two in a row: 20 planner rescues, 20 trips -> revert, the line names "planner-walked stuck rescues".
 *   arm() resets the warm-up and badWindows.
 * Runs on the game thread, then restores NavWalker's flags. Expect "summary: PASS".
 */
public final class TestB22NavGuard implements StartupListener {
    static void log(String s) { System.out.println("[TestB22NavGuard] " + s); }
    static int fails;
    static void check(String name, boolean ok, String what) { if (!ok) fails++; log("RESULT " + name + " " + (ok ? "PASS" : "FAIL") + ": " + what); }

    /** One synthetic window: allRescues in the world, plannerRescues by planner-walked bots, trips planner-routed trips, bots online. */
    static void win(int allRescues, int plannerRescues, int trips, int bots) {
        Watchdog.rescued += allRescues;
        NavWalker.plannerRescues = plannerRescues;
        NavWalker.WIN[0][0] = trips;
        NavWalker.botsOverride = bots;
        NavWalker.window();
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b22navguard")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 5) return false;
                boolean on0 = NavWalker.ON; double br = NavWalker.baseRescues, bf = NavWalker.baseFails;
                try {
                    NavWalker.ON = true; NavWalker.baseRescues = 2; NavWalker.baseFails = 5;
                    NavWalker.rescues0 = Watchdog.rescued; NavWalker.prevBots = -1; NavWalker.arm();
                    for (int i = 1; i <= 3; i++) win(20, 20, 20, 100);
                    check("F2 warm-up", NavWalker.badWindows == 0 && NavWalker.ON && NavWalker.windowsSinceArm == 3, "3 windows of 20 planner rescues: badWindows " + NavWalker.badWindows + ", ON " + NavWalker.ON + ", windowsSinceArm " + NavWalker.windowsSinceArm);
                    win(30, 0, 20, 100);
                    check("F1 not the planner's", NavWalker.badWindows == 0 && NavWalker.ON, "30 world rescues, 0 planner: badWindows " + NavWalker.badWindows);
                    win(20, 20, 20, 100);
                    check("F1 planner rescues", NavWalker.badWindows == 1 && NavWalker.ON, "20 planner rescues over limit 4: badWindows " + NavWalker.badWindows);
                    win(20, 20, 3, 100);
                    check("F3 trip floor", NavWalker.badWindows == 1 && NavWalker.ON, "3 trips: badWindows " + NavWalker.badWindows + " (stays 1)");
                    win(20, 20, 20, 50);
                    check("F2 bot count moving", NavWalker.badWindows == 1 && NavWalker.ON, "bots 100 -> 50: badWindows " + NavWalker.badWindows + " (stays 1)");
                    win(20, 20, 20, 50);
                    check("revert after two in a row", !NavWalker.ON && NavWalker.badWindows == 0, "ON " + NavWalker.ON + ", badWindows " + NavWalker.badWindows);
                    NavWalker.ON = true; NavWalker.badWindows = 1; NavWalker.arm();
                    check("arm()", NavWalker.windowsSinceArm == 0 && NavWalker.badWindows == 0, "windowsSinceArm " + NavWalker.windowsSinceArm + ", badWindows " + NavWalker.badWindows);
                } catch (Throwable e) { fails++; log("error " + e); e.printStackTrace(System.out); }
                NavWalker.ON = on0; NavWalker.baseRescues = br; NavWalker.baseFails = bf; NavWalker.botsOverride = -1; NavWalker.plannerRescues = 0; NavWalker.revertedAt = 0; NavWalker.prevBots = -1; NavWalker.arm();
                log("summary: " + (fails == 0 ? "PASS" : fails + " FAIL"));
                log("DONE");
                return true;
            }
        });
    }
}
