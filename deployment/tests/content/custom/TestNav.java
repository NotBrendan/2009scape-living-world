package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.File;

/**
 * Trial only (-Dtest.nav=true -Dnav.shadow=true -Dnav.window=8): the C2 shadow and guard (morning review 10-23).
 *  1. a shadow plan to the essence mine (off the surface grid)      -> logged as NO ROUTE, or skipped?
 *  2. a shadow plan for a short walk, then the bot gets there        -> is the outcome logged?
 *  3. data/survey/nav.on appears while the server runs               -> does the planner switch on without a restart?
 *  4. planner on, then a burst of stuck rescues in one window        -> does the guard switch it off and rename nav.on?
 */
public final class TestNav implements StartupListener {
    static void log(String s) { System.out.println("[TestNav] " + s); }
    static File survey(String n) { return new File(new File(core.ServerConstants.DATA_PATH, "survey"), n); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.nav")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer bot;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t < 20) return false;
                    if (t == 20) { if (!NavPlanner.built) { t = 19; return false; } log("planner built, ON " + NavWalker.ON + ", SHADOW " + NavWalker.SHADOW); }
                    if (t == 21) {
                        bot = new AIPlayer(Location.create(3222, 3218, 0));
                        NavWalker.shadow(bot, Location.create(2911, 4832, 0), "TEST essence mine");
                        log("RESULT 1 shadow to the essence mine done (a NO ROUTE line above = logged; none = skipped)");
                        NavWalker.shadow(bot, Location.create(3232, 3220, 0), "TEST short walk");
                    }
                    if (t == 25) bot.getProperties().setTeleportLocation(Location.create(3231, 3220, 0));
                    if (t == 60) { log("RESULT 2 (an 'outcome ... arrived' line above = the walk's result is logged)"); new File(survey("x").getParent()).mkdirs(); survey("nav.on").createNewFile(); log("created data/survey/nav.on"); }
                    if (t == 180) {
                        log("RESULT 3 two minutes after nav.on appeared: planner ON " + NavWalker.ON);
                        Watchdog.rescued += 50; log("50 stuck rescues injected");
                    }
                    if (t == 360) {
                        String[] rev = survey("x").getParentFile().list((d, n) -> n.startsWith("nav.on.reverted"));
                        log("RESULT 4 after a window with 50 rescues: planner ON " + NavWalker.ON + ", nav.on exists " + survey("nav.on").exists() + ", reverted files " + (rev == null ? 0 : rev.length));
                        survey("nav.on").delete(); if (rev != null) for (String r : rev) new File(survey("x").getParentFile(), r).delete(); survey("nav-baseline.txt").delete();
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
