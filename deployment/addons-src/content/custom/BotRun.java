package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.repository.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * batch 15 item2: run energy for bots, but only where a real player can see them (Spectacle.SEE tiles, as the shows).
 * Bots are made with run on, and once the engine turns it off at 0 energy they walked for good. A watched bot on the
 * move turns run on again once its energy is back up, runs it down like a player (the engine's own drain while running,
 * its own restore while walking or standing), and walks again when it gets low, until it is back up.
 * Unwatched bots are left exactly as they were: the run button goes back to how it was when this took over, and with no real
 * player online a pulse is one look through the player list. A flee (AfkTasks RETREAT/RECOVER) owns run and is left alone.
 * Flag: -Dbotrun=false turns it off.
 */
public final class BotRun implements StartupListener {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("botrun", "true"));
    static final Random RNG = new Random();
    static final Map<String, int[]> MINE = new HashMap<>(); // watched bots this looks after -> {walk below, run again at, run button before (1 on), pass last seen}
    static volatile long switchedOn, switchedOff, restored; // trials and the soak
    static int pass;

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots() || !ON) return;
        GameWorld.getPulser().submit(new Pulse(2) {
            @Override public boolean pulse() { try { tick(); } catch (Throwable t) { System.out.println("[BotRun] " + t); } return false; }
        });
    }

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    static void tick() {
        List<Player> humans = null;
        for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && !p.isArtificial()) { if (humans == null) humans = new ArrayList<>(2); humans.add(p); }
        if (humans == null && MINE.isEmpty()) return; // nobody to run for: nothing changes, nothing more to do
        final int now = ++pass;
        for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && p.isArtificial()) step(p, humans);
        if (!MINE.isEmpty()) MINE.values().removeIf(v -> v[3] != now); // logged out (not stepped this pass)
    }

    static boolean seen(Player b, List<Player> humans) {
        if (humans == null) return false;
        for (Player h : humans) if (h.getLocation().getZ() == b.getLocation().getZ() && h.getLocation().getDistance(b.getLocation()) <= Spectacle.SEE) return true;
        return false;
    }

    static void step(Player b, List<Player> humans) {
        String k = key(b);
        int[] mine = MINE.get(k);
        AfkTasks.Runner r = AfkTasks.RUNNING.get(k);
        if (r != null && (r.state == AfkTasks.State.RETREAT || r.state == AfkTasks.State.RECOVER)) { if (mine != null) MINE.remove(k); return; } // the flee's run, not ours (it turns it off when safe)
        boolean watched = seen(b, humans);
        if (!watched) { // as before: the run button back as it was when this took over (bots are made with it on; the engine turns it off at 0)
            if (mine != null) { MINE.remove(k); boolean was = mine[2] == 1; if (b.getSettings().isRunToggled() != was) b.getSettings().setRunToggled(was); restored++; }
            return;
        }
        if (mine == null) {
            if (!b.getWalkingQueue().isMoving()) return; // standing about: nothing to decide yet
            mine = new int[]{5 + RNG.nextInt(16), 40 + RNG.nextInt(51), b.getSettings().isRunToggled() ? 1 : 0, 0}; // walk below 5-20%, run again at 40-90% (a player's habits), the button as it was
            MINE.put(k, mine);
        }
        mine[3] = pass;
        double e = b.getSettings().getRunEnergy();
        boolean run = b.getSettings().isRunToggled();
        if (run && e < mine[0]) { b.getSettings().setRunToggled(false); switchedOff++; } // low: walk and get it back
        else if (!run && e >= mine[1] && b.getWalkingQueue().isMoving()) { b.getSettings().setRunToggled(true); switchedOn++; mine[1] = 40 + RNG.nextInt(51); }
    }
}
