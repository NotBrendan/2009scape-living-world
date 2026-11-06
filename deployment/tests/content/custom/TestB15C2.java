package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * Trial only (-Dtest.b15c2=true): batch 15 item2. Trip A sets out with 2 prayer potions(4) and an attack potion(3),
 * sips a prayer dose (prayerTick, points low) and an attack dose (swapped by hand), then ends; trip B carries no
 * potions. Every line the trips print about potions is captured: one line for trip A (taken and used), none for B.
 */
public final class TestB15C2 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15C2] " + s); }

    static String capture(Runnable r) {
        PrintStream was = System.out; ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try { r.run(); } finally { System.setOut(was); }
        StringBuilder s = new StringBuilder(); int n = 0;
        for (String line : buf.toString().split("\\R")) if (line.toLowerCase().contains("potion")) { s.append(" | ").append(line.trim()); n++; }
        System.out.print(buf);
        return n + " potion lines" + s;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15c2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 170) return false;
                try {
                    AIPlayer b = TestCR1.bot(Location.create(3226, 3236, 0), 60, 60, 60, 60);
                    b.getSkills().setStaticLevel(Skills.PRAYER, 60); b.getSkills().setLevel(Skills.PRAYER, 60); b.getSkills().setPrayerPoints(5);
                    b.getInventory().add(new Item(2434, 2)); b.getInventory().add(new Item(121, 1));
                    final AfkTasks.Runner a = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false); a.usePrayer = true;
                    a.prayerTick(); // points 5 < 8: a prayer dose
                    b.getInventory().remove(new Item(121, 1)); b.getInventory().add(new Item(123, 1)); // an attack dose
                    log("RESULT trip A (2 prayer(4) + attack(3), 1 prayer and 1 attack dose drunk): " + capture(a::halt));
                    AIPlayer c = TestCR1.bot(Location.create(3227, 3236, 0), 60, 60, 60, 60);
                    final AfkTasks.Runner bb = new AfkTasks.Runner(c, AfkTasks.Task.COW, true, 0, false, false);
                    log("RESULT trip B (no potions): " + capture(bb::halt));
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
