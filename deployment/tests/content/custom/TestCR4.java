package content.custom;

import core.api.StartupListener;
import core.cache.def.impl.NPCDefinition;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.cr4=true, needs -Dtraders.fast=true so the hunt map builds after 10 s): combat review 10-23 #4.
 * The Skeleton Mage hunt's level: before, id 94's (the family's first id); after, the level of what spawns at the spot.
 * Also counts how many hunts changed level against the old first-id rule.
 */
public final class TestCR4 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR4] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr4")) return;
        GameWorld.getPulser().submit(new Pulse(10) {
            int t;
            @Override public boolean pulse() {
                try {
                    if (++t > 60) { log("no hunts built"); log("DONE"); return true; }
                    boolean any = false; for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null) any = true;
                    if (!any) return false;
                    log("ids: 94 = " + NPCDefinition.forId(94).getName() + " level " + NPCDefinition.forId(94).getCombatLevel() + "; 3851 = " + NPCDefinition.forId(3851).getName() + " level " + NPCDefinition.forId(3851).getCombatLevel());
                    int hunts = 0;
                    for (SmartAdventurers.Job j : SmartAdventurers.JOBS) {
                        if (j.hunt == null) continue; hunts++;
                        HuntMap.Hunt h = j.hunt;
                        if (h.name.toLowerCase().contains("skeleton mage") || h.name.toLowerCase().contains("black demon") || h.name.toLowerCase().contains("hill giant"))
                            log("RESULT hunt " + h.name + " at " + h.spot + ": npcLevel " + h.npcLevel + ", targets " + java.util.Arrays.toString(h.names) + ", job needs combat " + j.level + (SmartAdventurers.magicUser(h.name) ? " (mage rule: combat " + (h.npcLevel * 2 + 10) + ")" : "") + "; a combat-60 bot may go: " + (j.level <= 60 && (!SmartAdventurers.magicUser(h.name) || 60 >= h.npcLevel * 2 + 10)));
                    }
                    log("hunts: " + hunts);
                    log("DONE");
                    return true;
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
            }
        });
    }
}
