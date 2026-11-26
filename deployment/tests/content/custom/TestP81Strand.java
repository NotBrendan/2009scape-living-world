package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * P81 unit: a broke bot on the Grand Exchange booth island (3164,3488, a 2-tile nav pocket) heading for Wydin's in Port Sarim.
 * -Dp81.expect=on: Preflight.out() gives BUSY and the bot lands at the Lumbridge home tile; a second try inside 30 min fails
 * (cooldown). -Dp81.expect=off: FAILED and the bot stays put (today's code). Enable with -Dtest.p81=true.
 */
public final class TestP81Strand implements StartupListener {
    static void log(String s) { System.out.println("[TestP81Strand] " + s); }

    @Override public void startup() {
        if (!"true".equalsIgnoreCase(System.getProperty("test.p81", ""))) return;
        GameWorld.getPulser().submit(new Run());
    }

    static final class Run extends Pulse {
        Run() { super(1); }
        final boolean wantOn = "on".equals(System.getProperty("p81.expect", "off"));
        final Location pocket = Location.create(3164, 3488, 0), wydin = Location.create(3013, 3204, 0), home = Location.create(3222, 3218, 0);
        int ticks, phase, passes, fails, wait; AIPlayer b; Preflight.Go first;

        void mark(String id, boolean ok, String why) { log((ok ? "PASS " : "FAIL ") + id + " | " + why); if (ok) passes++; else fails++; }

        void clearCoins() {
            try { int n = b.getInventory().getAmount(995); if (n > 0) b.getInventory().remove(new Item(995, n)); } catch (Throwable ignored) { }
            try { int n = b.getBank().getAmount(995); if (n > 0) b.getBank().remove(new Item(995, n)); } catch (Throwable ignored) { }
        }

        @Override public boolean pulse() {
            ticks++;
            try {
                switch (phase) {
                    case 0: // wait for the nav planner
                        if (!NavPlanner.built) { if (ticks > 1500) { mark("planner", false, "NavPlanner not built after 1500 ticks"); return finish(); } return false; }
                        b = TestCR1.bot(pocket, 30, 20, 20, 20); phase = 1; wait = 0; return false;
                    case 1: // landed in the pocket
                        if (b.getLocation().getDistance(pocket) > 1) { if (++wait > 20) { mark("placed", false, "bot at " + b.getLocation()); return finish(); } return false; }
                        clearCoins();
                        mark("flag", Preflight.STRAND_HOME == wantOn, "STRAND_HOME=" + Preflight.STRAND_HOME + " expect " + wantOn);
                        mark("broke", Ledger.Wallet.coins(b) == 0, "coins " + Ledger.Wallet.coins(b));
                        String why = Preflight.stranded(b, wydin);
                        mark("stranded", why != null, "stranded() = " + why);
                        first = Preflight.out(b, wydin);
                        mark("first out", first == (wantOn ? Preflight.Go.BUSY : Preflight.Go.FAILED), "out() = " + first);
                        phase = 2; wait = 0; return false;
                    case 2: // where it ends up
                        if (++wait < 8) return false;
                        Location at = b.getLocation();
                        if (wantOn) mark("landed home", at.getDistance(home) <= 3, "at " + at);
                        else mark("stayed", at.getDistance(pocket) <= 1, "at " + at);
                        if (wantOn) { // back into the pocket: the 30-min cooldown means no second free teleport
                            b.getProperties().setTeleportLocation(pocket); phase = 3; wait = 0; return false;
                        }
                        return finish();
                    case 3:
                        if (b.getLocation().getDistance(pocket) > 1) { if (++wait > 20) { mark("re-placed", false, "bot at " + b.getLocation()); return finish(); } return false; }
                        Preflight.Go again = Preflight.out(b, wydin);
                        mark("cooldown", again == Preflight.Go.FAILED, "second out() = " + again);
                        return finish();
                    default: return finish();
                }
            } catch (Throwable t) { mark("exception", false, String.valueOf(t)); return finish(); }
        }

        boolean finish() {
            log("RESULT " + (fails == 0 ? "PASS" : "FAIL") + " " + passes + "/" + (passes + fails) + " expect=" + (wantOn ? "on" : "off"));
            try { if (b != null) b.clear(); } catch (Throwable ignored) { }
            log("DONE");
            return true;
        }
    }
}
