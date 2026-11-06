package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.prc1=true): pathing review 10-23 C-1 and C-2 (Companions' 2-tick crew loop).
 * C-1: a fake human stands inside Al Kharid (3280,3227); its crewmate (100k coins) is outside the toll gate at 3258,3227,
 * 22 tiles off and walled off. For 120 s: walk attempts, distance, and whether it ever caught up.
 * C-2: another fake human stands inside Shilo Village (2852,2985, a quest area); its crewmate (no Shilo Village, 100k
 * coins) is at Lumbridge, far behind. Does the catch-up teleport put it inside Shilo?
 */
public final class TestPRC1 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRC1] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prc1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, k, moves; AIPlayer h1, b1, h2, b2; SmartAdventurers.Brain br1, br2; long t0; double minD = 999; Location last1; String said2 = "";
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 120) {
                        h1 = TestCR13.human(Location.create(3280, 3227, 0), 900141); h2 = TestCR13.human(Location.create(2852, 2985, 0), 900142);
                        b1 = TestCR1.bot(Location.create(3258, 3227, 0), 40, 30, 30, 30); b1.getBank().add(new Item(995, 100_000));
                        b2 = TestCR1.bot(Location.create(3222, 3218, 0), 40, 30, 30, 30); b2.getBank().add(new Item(995, 100_000)); Gates.SEEDED.put(b2.getUsername().toLowerCase(java.util.Locale.ROOT), new java.util.HashSet<>());
                        return false;
                    }
                    if (t == 126) {
                        br1 = TestCR1.rig(b1, false); br2 = TestCR1.rig(b2, false);
                        Companions.Crew c1 = Companions.CREWS.computeIfAbsent(Companions.key(h1), x -> new Companions.Crew()); c1.human = h1; Companions.join(c1, br1, h1);
                        Companions.Crew c2 = Companions.CREWS.computeIfAbsent(Companions.key(h2), x -> new Companions.Crew()); c2.human = h2; Companions.join(c2, br2, h2);
                        t0 = System.currentTimeMillis();
                        log("C-1: " + b1.getUsername() + " at " + b1.getLocation() + ", " + h1.getUsername() + " at " + h1.getLocation() + " (" + (int) b1.getLocation().getDistance(h1.getLocation()) + " tiles)");
                        log("C-2: " + b2.getUsername() + " at " + b2.getLocation() + ", may enter Shilo " + Gates.canEnter(b2, h2.getLocation()) + "; " + h2.getUsername() + " at " + h2.getLocation());
                        return false;
                    }
                    if (br1 == null || t < 126) return false;
                    k++;
                    long secs = (System.currentTimeMillis() - t0) / 1000;
                    double d = b1.getLocation().getDistance(h1.getLocation()); minD = Math.min(minD, d);
                    if (last1 != null && !last1.equals(b1.getLocation())) moves++;
                    last1 = b1.getLocation();
                    if (k % 25 == 0) log("   C-1 " + secs + " s: crewmate at " + b1.getLocation() + ", " + (int) d + " tiles off, in crew " + (br1.companion == h1) + ", ticks moved " + moves);
                    String s2 = "crewmate at " + b2.getLocation() + (Gates.canEnter(b2, b2.getLocation()) ? "" : " (INSIDE Shilo Village ground)") + ", in crew " + (br2.companion == h2);
                    if (!s2.equals(said2)) { said2 = s2; log("   C-2 " + secs + " s: " + s2); }
                    if (secs >= 120) {
                        log("RESULT C-1 after 120 s: crewmate " + (d <= 6 ? "CAUGHT UP" : "still " + (int) d + " tiles off") + " at " + b1.getLocation() + " (closest " + (int) minD + "), ticks it moved " + moves + ", in crew " + (br1.companion == h1));
                        log("RESULT C-2 after 120 s: " + s2);
                        Companions.disband(h1, null); Companions.disband(h2, null);
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
