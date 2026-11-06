package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr8=true): combat review 10-23 #8. In Draynor's multi-combat square three hill giants attack a bot
 * with no food. Its health is set just above the old no-food flee line (2 x the strongest single max hit) but under
 * 2 x their total. Before: no flee (it planned for one giant). After: it flees ("low on health with no food left").
 */
public final class TestCR8 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR8] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr8")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 270; int t; AIPlayer b; AfkTasks.Runner r; NPC[] g = new NPC[3];
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) { b = TestCR1.bot(Location.create(3120, 3245, 0), 40, 70, 70, 70); log("bot " + b.getUsername() + " (40 hp, no food) to Draynor's multi-combat square"); }
                    if (t == T0 + 4) {
                        r = new AfkTasks.Runner(b, AfkTasks.Task.AUTO, true, 0, false, false);
                        for (int i = 0; i < 3; i++) { g[i] = NPC.create(117, b.getLocation().transform(i - 1, 2, 0)); g[i].init(); }
                        log("multi-combat here: " + b.getProperties().isMultiZone());
                    }
                    if (t == T0 + 6) for (NPC n : g) n.attack(b);
                    if (t == T0 + 9) {
                        int single = 0, sum = 0, on = 0;
                        for (NPC n : g) { int h = r.maxHitOf(n); single = Math.max(single, h); if (n.getProperties().getCombatPulse().getVictim() == b) { sum += h; on++; } }
                        int max = b.getSkills().getMaximumLifepoints(), cb = b.getProperties().getCurrentCombatLevel();
                        int oldLine = Math.max(single * 2, cb < 60 ? max / 2 : max * 2 / 5);
                        int hp = oldLine + 1;
                        b.getSkills().setLifepoints(hp);
                        log(on + " giants on the bot, each max hit " + single + " (total " + sum + "); combat " + cb + ", old flee line " + oldLine + " hp, 2 x total " + (2 * sum) + "; hp set to " + hp + "/" + max);
                        boolean acted = r.eatIfNeeded();
                        log("RESULT expectedHit " + r.expectedHit() + "; eatIfNeeded acted " + acted + ", flee reason " + r.fleeReason + ", state " + r.state);
                        r.halt(); for (NPC n : g) n.clear();
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
