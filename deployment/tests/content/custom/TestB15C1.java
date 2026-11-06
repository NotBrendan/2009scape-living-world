package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.combat.CombatStyle;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.link.prayer.PrayerType;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b15c1=true): batch 15 item1. A praying bot's runner is told a goblin attacks it in melee, then with
 * missiles, then with magic (the goblin's combat style is set each time) and prayerTick runs: the protection prayer that
 * goes up should follow the attacker's style. Then a 38-Prayer bot (no Protect from Missiles or Melee) against missiles
 * and magic, and out of the fight (all off).
 */
public final class TestB15C1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15C1] " + s); }

    static String active(AIPlayer b) {
        StringBuilder s = new StringBuilder();
        for (PrayerType t : new PrayerType[]{PrayerType.PROTECT_FROM_MELEE, PrayerType.PROTECT_FROM_MISSILES, PrayerType.PROTECT_FROM_MAGIC}) if (b.getPrayer().get(t)) s.append(s.length() > 0 ? "+" : "").append(t.name().replace("PROTECT_FROM_", "").toLowerCase());
        return s.length() == 0 ? "none" : s.toString();
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15c1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer b; NPC n; AfkTasks.Runner r; StringBuilder out = new StringBuilder();
            void attackedBy(CombatStyle st) {
                n.getProperties().getCombatPulse().setStyle(st);
                b.setAttribute("combat-attacker", n);
                b.setAttribute("combat-time", System.currentTimeMillis() + 10000);
                r.prayerTick();
            }
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 150) {
                        b = TestCR1.bot(Location.create(3230, 3232, 0), 70, 70, 70, 70);
                        b.getSkills().setStaticLevel(Skills.PRAYER, 70); b.getSkills().setLevel(Skills.PRAYER, 70); b.getSkills().setPrayerPoints(70);
                        n = NPC.create(100, Location.create(3232, 3232, 0)); n.init();
                        r = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false); r.usePrayer = true;
                        log("bot " + b.getUsername() + " prayer 70, a goblin beside it");
                    }
                    if (t == 154) { attackedBy(CombatStyle.MELEE); out.append("melee->").append(active(b)); }
                    if (t == 156) { attackedBy(CombatStyle.RANGE); out.append(", missiles->").append(active(b)); }
                    if (t == 158) { attackedBy(CombatStyle.MAGIC); out.append(", magic->").append(active(b)); }
                    if (t == 160) { b.removeAttribute("combat-time"); b.removeAttribute("combat-attacker"); r.prayerTick(); out.append(", out of fight->").append(active(b)); log("RESULT prayer 70: " + out); out.setLength(0); }
                    if (t == 162) { b.getSkills().setStaticLevel(Skills.PRAYER, 38); b.getSkills().setLevel(Skills.PRAYER, 38); b.getSkills().setPrayerPoints(38); attackedBy(CombatStyle.RANGE); out.append("missiles->").append(active(b)); }
                    if (t == 164) { attackedBy(CombatStyle.MAGIC); out.append(", magic->").append(active(b)); }
                    if (t == 166) { attackedBy(CombatStyle.MELEE); out.append(", melee->").append(active(b)); log("RESULT prayer 38: " + out); r.halt(); n.clear(); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
