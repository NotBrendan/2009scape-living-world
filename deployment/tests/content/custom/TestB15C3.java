package content.custom;

import core.api.ContentAPIKt;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b15c3=true): batch 15 item3, the runner side. A: a poisoned bot resting (RECOVER, out of combat)
 * at 33/60 hp (55%) with trout: does it eat? B: a poisoned bot at full health carrying an antipoison(3): does it drink
 * it, and is the poison gone? (The buying side is in TestB15C4.)
 */
public final class TestB15C3 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15C3] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15c3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, b; AfkTasks.Runner ra, rb; NPC n; String resA;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 190) {
                        n = NPC.create(100, Location.create(3240, 3240, 0)); n.init();
                        a = TestCR1.bot(Location.create(3236, 3238, 0), 60, 40, 40, 40); a.getInventory().add(new Item(333, 5));
                        b = TestCR1.bot(Location.create(3238, 3238, 0), 60, 40, 40, 40); b.getInventory().add(new Item(175, 1)); b.getInventory().add(new Item(333, 5));
                        ra = new AfkTasks.Runner(a, AfkTasks.Task.COW, true, 0, false, false);
                        rb = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false);
                    }
                    if (t == 194) {
                        ContentAPIKt.applyPoison(a, n, 4); ContentAPIKt.applyPoison(b, n, 4);
                        a.getSkills().setLifepoints(33); ra.lastHp = 33; ra.state = AfkTasks.State.RECOVER;
                    }
                    if (t == 196) {
                        boolean pa = ContentAPIKt.isPoisoned(a), pb = ContentAPIKt.isPoisoned(b);
                        boolean ea = ra.eatIfNeeded(); int foodA = a.getInventory().getAmount(333);
                        resA = "A poisoned=" + pa + " inCombat=" + a.inCombat() + " acted=" + ea + " trout 5->" + foodA + " hp " + a.getSkills().getLifepoints() + "/60";
                        boolean eb = rb.eatIfNeeded();
                        log("RESULT " + resA + "; B poisoned=" + pb + " acted=" + eb + " antipoison(3) " + b.getInventory().getAmount(175) + " antipoison(2) " + b.getInventory().getAmount(177) + " trout " + b.getInventory().getAmount(333));
                    }
                    if (t == 200) {
                        log("RESULT 4 ticks later: A poisoned=" + ContentAPIKt.isPoisoned(a) + ", B poisoned=" + ContentAPIKt.isPoisoned(b));
                        ra.halt(); rb.halt(); n.clear();
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
