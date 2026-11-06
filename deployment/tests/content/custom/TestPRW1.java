package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.prw1=true -Dwatchdog.min=1): pathing review 10-23 W-1. A travelling bot (registered as the herb gatherer)
 * stands still in a learned trap cell (2 rescues; the Draynor fishing spots, 3087,3227): phase 1 with nothing to do, phase 2
 * with a fresh route plan (trying to leave).
 */
public final class TestPRW1 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRW1] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prw1")) return;
        GameWorld.getPulser().submit(new Pulse(5) {
            int t, phase, rescued0; long t0; AIPlayer bot; final Location at = Location.create(3087, 3227, 0); final int cell = RouteMap.cell(3087, 3227);
            @Override public boolean pulse() {
                try {
                    if (++t < 10) return false;
                    if (bot == null) {
                        bot = new AIPlayer(at);
                        Watchdog.load(); Watchdog.TRAPS.put(cell, 2);
                        phase = 1; t0 = System.currentTimeMillis(); rescued0 = Watchdog.rescued;
                        log("phase 1: " + bot.getUsername() + " idle at " + at + " (no task, no plan), trap count " + Watchdog.TRAPS.get(cell) + ", trap " + Watchdog.trap(at) + ", stuck after " + Watchdog.STUCK_MS / 60_000 + " min");
                    }
                    ServiceBots.LIVE.put("herbs", bot); // "ours": a travelling bot
                    if (phase == 2) RouteMap.PLANS.put(bot.getUsername().toLowerCase(), new Object[]{RouteMap.cell(3200, 3200), new java.util.ArrayList<Location>(), 0, System.currentTimeMillis()}); // trying to get somewhere
                    boolean rescuedNow = Watchdog.rescued > rescued0 && bot.getLocation().getDistance(at) > 8; // this bot teleported out (another bot's rescue also counts in Watchdog.rescued)
                    long secs = (System.currentTimeMillis() - t0) / 1000;
                    if (rescuedNow || secs > 150) {
                        log("RESULT phase " + phase + " (" + (phase == 1 ? "idle" : "trying") + "): " + (rescuedNow ? "RESCUED (teleported to " + bot.getLocation() + ")" : "not rescued (an 'idle at' line above = the job given up)") + " after " + secs + " s; trap count now " + Watchdog.TRAPS.get(cell));
                        if (phase == 2) { Watchdog.TRAPS.remove(cell); Watchdog.save(); ServiceBots.LIVE.remove("herbs", bot); RouteMap.PLANS.remove(bot.getUsername().toLowerCase()); log("DONE"); return true; }
                        phase = 2; t0 = System.currentTimeMillis(); rescued0 = Watchdog.rescued; Watchdog.TRAPS.put(cell, 2);
                        bot.getPulseManager().clear(); bot.getProperties().setTeleportLocation(at); Watchdog.SEEN.remove(bot.getUsername().toLowerCase());
                        log("phase 2: back at " + at + " with a fresh route plan (trying), trap count set to 2");
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
