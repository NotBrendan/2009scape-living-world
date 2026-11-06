package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b15c5=true): batch 15 item5. A bot wielding a dragon dagger fights a goblin (made tough) with its
 * runner's fight() called every 3 ticks for 60 ticks, like a smart bot's runner. Logs special energy, the special
 * toggle and every drop in energy (a special swing fired).
 */
public final class TestB15C5 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15C5] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15c5")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, lastE = 100, drops, minE = 100; AIPlayer b; NPC n; AfkTasks.Runner r; StringBuilder seq = new StringBuilder();
            NPC goblin() {
                NPC g = NPC.create(100, b.getLocation().transform(1, 0, 0)); g.init();
                g.getSkills().setStaticLevel(Skills.HITPOINTS, 99); g.getSkills().setLevel(Skills.HITPOINTS, 99); g.getSkills().setLifepoints(99);
                return g;
            }
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 300) {
                        b = TestCR1.bot(Location.create(3244, 3242, 0), 99, 60, 60, 99);
                        b.getEquipment().replace(new Item(1215), 3); b.getEquipment().refresh();
                        b.getSettings().setSpecialEnergy(100);
                        r = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false);
                    }
                    if (t == 304) { n = goblin(); b.attack(n); log("bot " + b.getUsername() + " wields " + b.getEquipment().get(3).getName() + ", energy " + b.getSettings().getSpecialEnergy()); }
                    if (t > 304 && t <= 364) {
                        if (n == null || !n.isActive() || n.getSkills().getLifepoints() <= 0) { n = goblin(); b.attack(n); }
                        if ((t - 304) % 3 == 0) r.fight();
                        int e = b.getSettings().getSpecialEnergy();
                        if (e < lastE) { drops++; seq.append(lastE).append("->").append(e).append("@").append(t - 304).append(' '); }
                        lastE = e; minE = Math.min(minE, e);
                    }
                    if (t == 365) {
                        log("RESULT 60 ticks: special swings (energy drops) " + drops + " [" + seq.toString().trim() + "], lowest energy " + minE + ", energy now " + b.getSettings().getSpecialEnergy() + ", toggled " + b.getSettings().isSpecialToggled());
                        r.halt(); if (n != null) n.clear();
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
