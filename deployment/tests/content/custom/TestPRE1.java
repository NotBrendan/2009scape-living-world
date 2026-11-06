package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.bots.GeneralBotCreator;
import core.game.bots.SkillingBotAssembler;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.pre1=true -Dsurvey.stairs=0): pathing review 10-23 E-1, a stairs surveyor that finds itself underground (Taverley dungeon, by the ladder up). */
public final class TestPRE1 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRE1] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.pre1")) return;
        GameWorld.getPulser().submit(new Pulse(3) {
            int t; long t0; AIPlayer bot; Explorers.Explorer e; String said = ""; boolean surfaced;
            @Override public boolean pulse() {
                try {
                    if (++t < 15) return false;
                    if (bot == null) {
                        bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(2884, 3395, 0));
                        e = new Explorers.Explorer(true, 20);
                        new GeneralBotCreator(e, bot);
                        e.target = Location.create(2884, 3395, 0); // its site: Taverley
                        bot.getProperties().setTeleportLocation(Location.create(2886, 9799, 0)); // a climb took it down: in the dungeon beside the ladder up (2884,9797)
                        t0 = System.currentTimeMillis();
                        log("stairs surveyor " + bot.getUsername() + " put in Taverley dungeon at 2886,9799; its site " + e.target);
                        return false;
                    }
                    if (t < 18) return false;
                    e.tick();
                    long secs = (System.currentTimeMillis() - t0) / 1000;
                    String s = "at " + bot.getLocation() + (e.pending != null ? ", using " + e.pending.getName() + " (" + e.pendingOption + ")" : "") + ", records " + e.records;
                    if (!s.equals(said) && (t % 5 == 0 || e.pending != null)) { said = s; log("   " + secs + " s " + s); }
                    if (!surfaced && bot.getLocation().getY() < 6400 && secs > 2) { surfaced = true; log("   " + secs + " s: on the surface at " + bot.getLocation() + " (records " + e.records + ")"); }
                    if (secs >= 110 || (surfaced && e.pending == null && e.records > 0)) {
                        log("RESULT after " + secs + " s: " + (surfaced ? "on the surface at " + bot.getLocation() : "still underground at " + bot.getLocation()) + "; climbs recorded " + e.records + (e.records > 0 ? " (the way up is in stairs.jsonl)" : " (the way up never recorded)"));
                        try { bot.clear(); } catch (Throwable ignored) { }
                        log("DONE"); return true;
                    }
                } catch (Throwable x) { log("error " + x); x.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
