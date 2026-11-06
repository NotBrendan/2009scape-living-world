package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.aus5=true, with -Dsmartbots.awaySec=900): audit 10-23 S-5. Counts the engine's BotScriptPulses that
 * drive smart-adventurer brains before and after three bots log out for a break, and how many of them drive a cleared bot.
 */
public final class TestAUS5 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUS5] " + s); }
    static int[] count() {
        int brains = 0, dead = 0;
        for (Pulse p : GameWorld.getPulser().getCurrentPulses()) {
            if (p == null || !p.getClass().getName().endsWith("BotScriptPulse")) continue;
            try {
                Object s = p.getClass().getMethod("getBotScript").invoke(p);
                if (!(s instanceof SmartAdventurers.Brain)) continue;
                brains++;
                Player b = ((SmartAdventurers.Brain) s).bot;
                if (b == null || !b.isActive()) dead++;
            } catch (Throwable t) { log("reflect " + t); }
        }
        return new int[]{brains, dead};
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aus5")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, at = -1; int[] before;
            @Override public boolean pulse() {
                t++;
                try {
                    if (at < 0 && t % 20 == 0 && t > 200) {
                        java.util.List<AIPlayer> pick = new java.util.ArrayList<>();
                        for (Player b : SmartAdventurers.BOTS) if (b instanceof AIPlayer && b.isActive() && SmartAdventurers.BRAINS.containsKey(b.getUsername().toLowerCase(java.util.Locale.ROOT)) && pick.size() < 3) pick.add((AIPlayer) b);
                        if (pick.size() == 3) {
                            before = count();
                            log("before: " + before[0] + " brain pulses, " + before[1] + " on cleared bots; logging out " + pick.get(0).getUsername() + ", " + pick.get(1).getUsername() + ", " + pick.get(2).getUsername());
                            for (AIPlayer b : pick) SmartAdventurers.logOut(b, SmartAdventurers.BRAINS.get(b.getUsername().toLowerCase(java.util.Locale.ROOT)));
                            at = t;
                        } else if (t > 3000) { log("not enough bots"); log("DONE"); return true; }
                    }
                    if (at > 0 && t == at + 80) {
                        int[] c = count();
                        log("RESULT 80 ticks after 3 logouts (the engine pulse sits out random 20-50 tick idles first): " + c[0] + " brain pulses (was " + before[0] + "), " + c[1] + " of them on cleared bots");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
