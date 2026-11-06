package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b16e=true): batch 16 item5, the fire extra never loses a log or the tinderbox. A bot whose bank
 * is full (every slot a different item) ends a fire extra holding an unlit log and the borrowed tinderbox in its pack
 * (extraKind 3, extraLog 1511, extraTinder true; endExtra()). Before: both are taken from the pack and the bank refuses
 * them (gone). After: they stay in the pack. Then the start of a fire extra with a full pack (bank -> pack refused):
 * the bank keeps its log and tinderbox.
 */
public final class TestB16E implements StartupListener {
    static void log(String s) { System.out.println("[TestB16E] " + s); }
    static void set(Object o, String n, Object v) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); f.set(o, v); } catch (Throwable e) { log("no field " + n); } }
    static Object call(Object o, String n) { try { java.lang.reflect.Method m = SmartAdventurers.Brain.class.getDeclaredMethod(n); m.setAccessible(true); return m.invoke(o); } catch (Throwable e) { return "error " + e; } }
    static Object call(Object o, String n, long now) { try { java.lang.reflect.Method m = SmartAdventurers.Brain.class.getDeclaredMethod(n, long.class); m.setAccessible(true); return m.invoke(o, now); } catch (Throwable e) { return "error " + e; } }
    static String has(AIPlayer b) { return "pack logs " + b.getInventory().getAmount(1511) + ", pack tinderbox " + b.getInventory().getAmount(590) + ", bank logs " + b.getBank().getAmount(1511) + ", bank tinderbox " + b.getBank().getAmount(590) + ", bank free " + b.getBank().freeSlots() + ", pack free " + b.getInventory().freeSlots(); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b16e")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        Location at = Location.create(3222, 3219, 0);
                        AIPlayer b = TestCR1.bot(at, 40, 20, 20, 20); SmartAdventurers.Brain br = TestCR1.rig(b, false);
                        b.getInventory().clear(); for (Item it : b.getBank().toArray()) if (it != null) b.getBank().remove(it);
                        for (int id = 1100; b.getBank().freeSlots() > 0 && id < 9000; id++) { core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id); if (d == null || !d.isUnnoted() || id == 1511 || id == 590) continue; b.getBank().add(new Item(id, 1)); }
                        b.getInventory().add(new Item(1511, 1)); b.getInventory().add(new Item(590, 1));
                        log("setup: " + has(b));
                        set(br, "extraKind", 3); set(br, "extraLog", 1511); set(br, "extraTinder", true);
                        call(br, "endExtra");
                        log("RESULT 1 endExtra with a full bank: " + has(b) + " (lost: logs " + (1 - b.getInventory().getAmount(1511) - b.getBank().getAmount(1511)) + ", tinderbox " + (1 - b.getInventory().getAmount(590) - b.getBank().getAmount(590)) + ")");
                        // the other way: a full pack at the start of a fire extra
                        AIPlayer c = TestCR1.bot(at.transform(2, 0, 0), 40, 20, 20, 20); SmartAdventurers.Brain bc = TestCR1.rig(c, false);
                        c.getInventory().clear(); for (Item it : c.getBank().toArray()) if (it != null) c.getBank().remove(it);
                        c.getBank().add(new Item(1511, 1)); c.getBank().add(new Item(590, 1));
                        for (int k = 0; k < 28; k++) c.getInventory().add(new Item(1205, 1)); // daggers: a full pack
                        set(bc, "extraKind", 3); set(bc, "extraArrived", false); set(bc, "extraUntil", System.currentTimeMillis() + 30_000); bc.idleUntil = System.currentTimeMillis() + 60_000;
                        Object r = call(bc, "extraTick", System.currentTimeMillis());
                        call(bc, "endExtra");
                        log("RESULT 2 fire extra start with a full pack: extraTick " + r + "; " + has(c) + " (lost: logs " + (1 - c.getInventory().getAmount(1511) - c.getBank().getAmount(1511)) + ", tinderbox " + (1 - c.getInventory().getAmount(590) - c.getBank().getAmount(590)) + ")");
                    }
                    if (t == 155) { log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
