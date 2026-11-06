package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr10=true): combat review 10-23 #10. A high-tier bot with 5 trout on a barbarian trip at Barbarian
 * Village (far from the respawn, so both builds see the death) is killed by a barbarian. The DEATH line it prints is the
 * result: before, name/place/job/count only; after, also tier, combat, killer and food left.
 */
public final class TestCR10 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR10] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr10")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 210; int t; AIPlayer a; SmartAdventurers.Brain ba;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) { a = TestCR1.bot(Location.create(3082, 3420, 0), 70, 60, 60, 60); a.getInventory().add(new Item(333, 5)); log("bot " + a.getUsername() + " (high tier) to Barbarian Village with 5 trout"); }
                    if (t == T0 + 4) { ba = TestCR1.rig(a, true); TestCR1.trip(ba, AfkTasks.Task.BARBARIAN); }
                    if (t == T0 + 20) { NPC g = TestCR1.npcAt(a); log("killed by " + g.getName() + " (level " + g.getDefinition().getCombatLevel() + ") at " + a.getLocation() + ", trout in the pack " + a.getInventory().getAmount(333)); TestCR1.killBy(a, g); }
                    if (t == T0 + 70) { log("RESULT deaths counted " + ba.deaths + " (the DEATH line above is the result)"); if (ba.runner != null) ba.runner.halt(); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
