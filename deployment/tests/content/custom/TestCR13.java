package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

/**
 * Trial only (-Dtest.cr13=true): combat review 10-23 #13. A crewmate (in a fake human's crew, driven by Companions'
 * 2-tick crew loop) is held at 40% health with 20 trout. Logs the ticks between its bites: before every 2 ticks (faster
 * than a player can eat), after every 3 or more.
 */
public final class TestCR13 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR13] " + s); }

    static AIPlayer human(Location at, int uid) throws Exception {
        AIPlayer p = new AIPlayer(at);
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(p, false);
        p.getDetails().accountInfo.setUid(uid);
        Repository.INSTANCE.getUid_map().put(uid, p);
        return p;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr13")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 290; int t, start, last = -1, bites, food0, minGap = 999; AIPlayer h, b; SmartAdventurers.Brain br; StringBuilder gaps = new StringBuilder();
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) {
                        Location at = Location.create(3222, 3240, 0);
                        h = human(at, 900131); h.getSkills().setStaticLevel(core.game.node.entity.skill.Skills.HITPOINTS, 30);
                        b = TestCR1.bot(at.transform(1, 0, 0), 50, 40, 40, 40); b.getInventory().add(new Item(333, 20));
                        br = TestCR1.rig(b, false);
                        Companions.Crew c = Companions.CREWS.computeIfAbsent(Companions.key(h), k -> new Companions.Crew()); c.human = h;
                        Companions.join(c, br, h);
                        food0 = b.getInventory().getAmount(333);
                        log("crewmate " + b.getUsername() + " in " + h.getUsername() + "'s crew with " + food0 + " trout");
                    }
                    if (br == null || t < T0 + 6) return false;
                    if (start == 0) start = t;
                    int food = b.getInventory().getAmount(333);
                    if (food < food0) { bites += food0 - food; if (last >= 0) { int g = t - last; minGap = Math.min(minGap, g); if (gaps.length() < 200) gaps.append(g).append(' '); } last = t; }
                    food0 = food;
                    if (b.getSkills().getLifepoints() > 20) b.getSkills().setLifepoints(20); // held at 40%: hungry the whole time
                    if (t - start == 40) {
                        log("RESULT 40 ticks at 40% health: bites " + bites + ", gaps (ticks) " + gaps.toString().trim() + ", min gap " + (minGap == 999 ? "-" : String.valueOf(minGap)));
                        Companions.disband(h, null);
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
