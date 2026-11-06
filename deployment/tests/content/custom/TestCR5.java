package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr5=true): combat review 10-23 #5. Phase 1 (default): two bots on goblin trips at Draynor (126 tiles
 * from the respawn) are killed by goblins: "Goblins" now has 2 deaths today, so chooseJob skips it. Phase 2
 * (-Dtest.cr5.phase=2, started with KEEP=1 so data/eco survives): after the restart, how many deaths does "Goblins" have,
 * and are the per-bot blacklists back? Before: 0 (in memory only). After: 2, and the spot stays closed.
 */
public final class TestCR5 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR5] " + s); }
    static final String LABEL = "Goblins";

    static int savedBlacklists() {
        try { java.lang.reflect.Field f = SmartAdventurers.class.getDeclaredField("SAVED_BLACKLISTS"); return ((java.util.Map<?, ?>) f.get(null)).size(); } catch (NoSuchFieldException e) { return -1; } catch (Throwable t) { return -2; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr5")) return;
        final boolean phase2 = "2".equals(System.getProperty("test.cr5.phase"));
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 220; int t; AIPlayer a, b; SmartAdventurers.Brain ba, bb;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (phase2) {
                        if (t == T0) {
                            java.io.File f = new java.io.File(core.ServerConstants.GRAND_EXCHANGE_DATA_PATH, "death-memory.json");
                            log("PHASE 2 (after a restart): death-memory.json " + (f.exists() ? "exists (" + f.length() + " bytes)" : "absent"));
                            log("RESULT after restart: recentDeaths(\"" + LABEL + "\") = " + SmartAdventurers.recentDeaths(LABEL) + " -> " + (SmartAdventurers.recentDeaths(LABEL) >= 2 ? "spot CLOSED for the day" : "spot open again") + "; saved per-bot blacklists loaded " + (savedBlacklists() == -1 ? "(no such store in this build)" : String.valueOf(savedBlacklists())));
                            log("DONE");
                            return true;
                        }
                        return false;
                    }
                    if (t == T0) { a = TestCR1.bot(Location.create(3105, 3260, 0), 30, 20, 20, 20); b = TestCR1.bot(Location.create(3106, 3261, 0), 30, 20, 20, 20); }
                    if (t == T0 + 4) { ba = TestCR1.rig(a, false); bb = TestCR1.rig(b, false); TestCR1.trip(ba, AfkTasks.Task.GOBLIN); TestCR1.trip(bb, AfkTasks.Task.GOBLIN); log("label of the job: " + ba.lastJob.label()); }
                    if (t == T0 + 20) { NPC g = TestCR1.npcAt(a); TestCR1.killBy(a, g); TestCR1.killBy(b, g); log("both killed by " + g.getName() + " at " + a.getLocation()); }
                    if (t == T0 + 70) {
                        log("RESULT before restart: recentDeaths(\"" + LABEL + "\") = " + SmartAdventurers.recentDeaths(LABEL) + ", " + a.getUsername() + " blacklist " + ba.blacklist.keySet() + ", " + b.getUsername() + " blacklist " + bb.blacklist.keySet());
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
