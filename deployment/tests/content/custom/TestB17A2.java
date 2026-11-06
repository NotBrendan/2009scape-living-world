package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b17a2=true; needs TestCR1.java in the build): batch 17 item2 (review S-8). A bot wearing a rune
 * scimitar, rune full helm and rune kiteshield (its 3 most valuable items) with cheap pack items and no coins, on a trip
 * at Barbarian Village (TestCR1.rig, as TestCR14), is killed by an NPC: the player-rules death keeps the 3. 20 and 40 ticks
 * after: before, the kept rune items sit in the pack; after, they are worn again (the grave run still to come).
 */
public final class TestB17A2 implements StartupListener {
    static void log(String s) { System.out.println("[TestB17A2] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17a2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 250; int t, died; AIPlayer a; SmartAdventurers.Brain ba;
            int worn(AIPlayer p) { int n = 0; for (int id : new int[]{1333, 1163, 1201}) n += p.getEquipment().getAmount(id); return n; }
            int packed(AIPlayer p) { int n = 0; for (int id : new int[]{1333, 1163, 1201}) n += p.getInventory().getAmount(id); return n; }
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) {
                        a = TestCR1.bot(Location.create(3082, 3420, 0), 30, 40, 40, 40);
                        a.getInventory().remove(new Item(995, a.getInventory().getAmount(995))); a.getBank().remove(new Item(995, a.getBank().getAmount(995)));
                        for (int id : new int[]{1333, 1163, 1201}) a.getEquipment().add(new Item(id), true, false);
                        for (int id : new int[]{2351, 2349, 2349, 333, 333, 1205}) a.getInventory().add(new Item(id));
                        log("bot " + a.getUsername() + " with " + TestCR1.pack(a));
                    }
                    if (t == T0 + 4) { ba = TestCR1.rig(a, false); TestCR1.trip(ba, AfkTasks.Task.F_MAN); }
                    if (t == T0 + 15) { NPC g = TestCR1.npcAt(a); log("killed by " + g.getName() + " at " + a.getLocation()); TestCR1.killBy(a, g); died = t; }
                    if (died > 0 && (t == died + 20 || t == died + 40)) {
                        log("RESULT " + (t - died) + " ticks after the death: at " + a.getLocation() + ", rune items worn " + worn(a) + "/3, in the pack " + packed(a) + "; carrying " + TestCR1.pack(a) + "; grave " + TestCR1.grave(a) + "; grave run " + (ba.graveAt != null ? "ON" : "off"));
                        if (t == died + 40) { if (ba.runner != null) ba.runner.halt(); log("DONE"); return true; }
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
