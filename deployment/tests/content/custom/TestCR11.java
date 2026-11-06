package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr11=true): combat review 10-23 #11. A bot with no coins on a trip at Edgeville (~295 tiles
 * from the respawn) dies to a self-hit (not an NPC, so the engine makes its grave, a 2-minute plaque). Before: it walks
 * for the grave for minutes and gives up silently. After: "grave run skipped: too far" at once, logged.
 */
public final class TestCR11 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR11] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr11")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 230; int t, died; AIPlayer a; SmartAdventurers.Brain ba; boolean sawRun;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) { a = TestCR1.bot(Location.create(3087, 3480, 0), 30, 20, 20, 20); for (int id : new int[]{2351, 2351, 2353, 2353, 1205, 1277}) a.getInventory().add(new Item(id)); log("bot " + a.getUsername() + " with " + TestCR1.pack(a) + ", " + Ledger.Wallet.coins(a) + " gp"); }
                    if (t == T0 + 4) { ba = TestCR1.rig(a, false); TestCR1.trip(ba, AfkTasks.Task.F_GUARD); }
                    if (t == T0 + 15) { log("self-hit kill at " + a.getLocation() + " (" + (int) a.getLocation().getDistance(TestCR1.LUMBRIDGE) + " tiles from the respawn)"); log("(rights " + a.getDetails().getRights() + ", safe zone " + a.getProperties().isSafeZone() + ", zone type " + a.getZoneMonitor().getType() + ", graves allowed " + core.game.node.entity.combat.graves.GraveController.allowGenerate(a) + ")"); TestCR1.killBy(a, a); died = t; }
                    if (died > 0 && t - died <= 12 && t - died >= 1) log("  t+" + (t - died) + ": at " + a.getLocation() + ", hp " + a.getSkills().getLifepoints() + ", dying " + a.getAttribute("state:death", false) + ", grave " + TestCR1.grave(a));
                    if (died > 0 && t == died + 30) log("after respawn: at " + a.getLocation() + ", grave " + TestCR1.grave(a) + ", grave run " + (ba.graveAt != null ? "ON" : "off"));
                    if (died > 0 && t > died + 30 && ba.graveAt != null) sawRun = true;
                    if (died > 0 && (t - died) % 100 == 0 && t - died <= 500) log("t+" + (t - died) + " ticks: at " + a.getLocation() + " (" + (int) a.getLocation().getDistance(Location.create(3087, 3480, 0)) + " tiles from the grave), grave run " + (ba.graveAt != null ? "ON" : "off") + ", grave " + TestCR1.grave(a));
                    if (died > 0 && t == died + 520) { log("RESULT grave run was on after the respawn: " + sawRun + "; now " + (ba.graveAt != null ? "still running" : "over") + "; grave " + TestCR1.grave(a)); if (ba.runner != null) ba.runner.halt(); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
