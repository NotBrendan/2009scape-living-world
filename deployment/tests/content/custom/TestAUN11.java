package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.aun11=true -Dnav=true): audit 10-23 N-10/N-11/N-12, the nav.on auto-revert guard.
 *  1. learned baseline 2.22 rescues: one window with 5 rescues (normal noise), then a quiet one. Reverted?
 *  2. a window with 12 distinct no-routes from quest/explorer walks (WINDOW_FAILS) and none from trip starts (SHADOW_FAILS). Reverted?
 *  3. two windows in a row with 9 rescues: reverted? (it must be)
 *  4. after that revert, with -Dnav=true: is the planner back on after the minute re-read? (it must stay off) Then a new
 *     data/survey/nav.on is created: on again within a minute? (removed again at the end)
 */
public final class TestAUN11 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUN11] " + s); }
    static void win(int rescues, int allFails, int tripFails) {
        Watchdog.rescued += rescues;
        for (int i = 0; i < allFails; i++) NavWalker.WINDOW_FAILS.add("testbot" + i + "|1,1");
        for (int i = 0; i < tripFails; i++) NavWalker.SHADOW_FAILS.add("testbot" + i + "|1,1");
        NavWalker.window();
    }
    static void base() { NavWalker.baseRescues = 2.22; NavWalker.baseFails = 1; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aun11")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, t4; java.io.File on = NavWalker.survey("nav.on");
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 120) {
                        on.delete();
                        NavWalker.rescues0 = Watchdog.rescued;
                        NavWalker.ON = true; base(); win(0, 0, 0);
                        win(5, 0, 0); boolean a = NavWalker.ON; win(1, 0, 0);
                        log("RESULT 1 one window with 5 rescues (limit 4.44), then 1: planner still on " + NavWalker.ON + " (on after the noisy window " + a + ")");
                        NavWalker.ON = true; base(); win(0, 12, 0);
                        // 2b. today's live windows (16:39 false trip), replayed as trip-start no-routes against the live limits (4 rescues, 10 no-routes)
                        int[] lr = {0, 0, 2, 2, 1, 1, 1, 3, 3, 7}, lf = {6, 7, 9, 9, 5, 8, 10, 8, 10, 14};
                        NavWalker.ON = true; NavWalker.baseRescues = 2; NavWalker.baseFails = 2; win(0, 0, 0); int revertedAt = -1;
                        for (int i = 0; i < lr.length; i++) { NavWalker.baseRescues = 2; NavWalker.baseFails = 2; win(lr[i], lf[i], lf[i]); if (!NavWalker.ON && revertedAt < 0) revertedAt = i + 1; }
                        log("RESULT 2b live windows 16:39 replayed (rescues 0,0,2,2,1,1,1,3,3,7; no-route 6,7,9,9,5,8,10,8,10,14): " + (revertedAt < 0 ? "no revert" : "REVERTED at window " + revertedAt));
                        NavWalker.ON = true; revertedAt = -1;
                        for (int i = 0; i < 3; i++) { NavWalker.baseRescues = 2; NavWalker.baseFails = 2; win(9, 15, 15); if (!NavWalker.ON && revertedAt < 0) revertedAt = i + 1; }
                        log("RESULT 2c a sustained bad run (9 rescues, 15 no-routes a window, 3 windows): " + (revertedAt < 0 ? "NO revert" : "reverted at window " + revertedAt));
                        try { java.lang.reflect.Field rf = NavWalker.class.getDeclaredField("revertedAt"); rf.setAccessible(true); rf.setLong(null, 0L); java.lang.reflect.Field bw = NavWalker.class.getDeclaredField("badWindows"); bw.setAccessible(true); bw.setInt(null, 0); } catch (NoSuchFieldException e) { } // the next parts start clean
                        log("RESULT 2 a window with 12 no-routes from all walks, 0 from trip starts: planner still on " + NavWalker.ON);
                        NavWalker.ON = true; base(); win(9, 0, 0); boolean b = NavWalker.ON; win(9, 0, 0);
                        log("RESULT 3 two windows in a row with 9 rescues: on after the first " + b + ", after the second " + NavWalker.ON + " (must be false)");
                        if (NavWalker.ON) { NavWalker.revert("test"); }
                        t4 = t;
                    }
                    if (t4 > 0 && t == t4 + 130) { // two minute re-reads later
                        log("RESULT 4a -Dnav=true, " + (t - t4) + " ticks after the revert: planner " + (NavWalker.ON ? "BACK ON" : "still off"));
                        java.nio.file.Files.write(on.toPath(), new byte[0]);
                    }
                    if (t4 > 0 && t == t4 + 260) {
                        log("RESULT 4b a new nav.on created: planner " + (NavWalker.ON ? "on" : "off"));
                        on.delete();
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
