package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aus2=true): audit 10-23 S-2. An idle smart bot (no trip, no errand) in iron kit with a pack of items
 * is killed by an NPC 30 tiles from Lumbridge. Is the death handled (DEATH line, grave run) or only the player-rules part?
 */
public final class TestAUS2 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUS2] " + s); }
    static int owned(AIPlayer b) { int n = 0; for (Item it : b.getBank().toArray()) if (it != null) n++; for (Item it : b.getInventory().toArray()) if (it != null) n++; for (Item it : b.getEquipment().toArray()) if (it != null) n++; return n; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aus2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer b; SmartAdventurers.Brain br; boolean graveSeen;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 200) {
                        b = TestCR1.bot(Location.create(3238, 3240, 0), 20, 20, 20, 20);
                        br = TestCR1.rig(b, false);
                        for (int id : new int[]{1153, 1115, 1067, 1323}) { b.getInventory().add(new Item(id)); SmartAdventurers.wear(b, id); }
                        for (int id : new int[]{1351, 1265, 590, 1381, 1205}) b.getInventory().add(new Item(id));
                        log("bot " + b.getUsername() + " idle (runner " + br.runner + "), " + owned(b) + " item stacks, death loss " + SmartAdventurers.DEATH_LOSS);
                    }
                    if (t == 210) TestCR1.killBy(b, TestCR1.npcAt(b));
                    if (t > 210 && t % 10 == 0 && t < 400 && br.graveAt != null) graveSeen = true;
                    if (t == 400) {
                        log("RESULT idle death: brain deaths " + br.deaths + ", grave run started " + graveSeen + ", graveAt now " + br.graveAt + ", item stacks owned now " + owned(b) + ", grave " + TestCR1.grave(b) + ", bot at " + b.getLocation());
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
