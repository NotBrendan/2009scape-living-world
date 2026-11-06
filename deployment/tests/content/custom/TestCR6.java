package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr6=true): combat review 10-23 #6. Bot A on a goblin trip east of the river (33 tiles from the
 * Lumbridge respawn) is killed by a goblin. Bot B on a men trip 20 tiles out is moved to the respawn by a paid teleport (the control:
 * that is not a death). Before: no DEATH line for A (an NPC kill leaves no grave, the jump rule wanted one). After: a DEATH
 * line for A; none for B either way.
 */
public final class TestCR6 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR6] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr6")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 200; int t; AIPlayer a, b; SmartAdventurers.Brain ba, bb; int deathsA0, deathsB0;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) {
                        a = TestCR1.bot(Location.create(3247, 3239, 0), 30, 20, 20, 20);
                        b = TestCR1.bot(Location.create(3232, 3232, 0), 30, 20, 20, 20);
                        log("A " + a.getUsername() + " to the goblins, B " + b.getUsername() + " to the men by the castle (within 40 tiles)");
                    }
                    if (t == T0 + 4) {
                        ba = TestCR1.rig(a, false); bb = TestCR1.rig(b, false);
                        TestCR1.trip(ba, AfkTasks.Task.GOBLIN); TestCR1.trip(bb, AfkTasks.Task.F_MAN);
                        log("A at " + a.getLocation() + " (" + (int) a.getLocation().getDistance(TestCR1.LUMBRIDGE) + " tiles from the respawn), B at " + b.getLocation());
                    }
                    if (t == T0 + 30) {
                        NPC g = TestCR1.npcAt(a);
                        deathsA0 = ba.deaths; deathsB0 = bb.deaths;
                        log("A killed by " + g.getName() + " at " + a.getLocation());
                        TestCR1.killBy(a, g);
                        b.getProperties().setTeleportLocation(TestCR1.LUMBRIDGE.transform(1, 1, 0));
                        log("B teleported to the respawn (paid teleport)");
                    }
                    if (t == T0 + 80) {
                        log("RESULT A: at " + a.getLocation() + ", deaths counted " + (ba.deaths - deathsA0) + ", runner " + (ba.runner == null ? "ended" : "still on"));
                        log("RESULT B (control): at " + b.getLocation() + ", deaths counted " + (bb.deaths - deathsB0) + ", runner " + (bb.runner == null ? "ended" : "still on"));
                        if (ba.runner != null) ba.runner.halt(); if (bb.runner != null) bb.runner.halt();
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
