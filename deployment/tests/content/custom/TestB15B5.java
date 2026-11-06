package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b15b5=true): batch 15 item5, 99 capes. The brain's cape check (Brain.checkCape, called at trip end;
 * reflection, so the before build compiles: there it does not exist) on:
 *  A 99 Mining, 120k, cape slot empty -> mining cape bought (99k through the Wallet), worn, hood banked, news line.
 *  B 99 Mining and 99 Smithing, 250k, a cape already worn -> trimmed cape kept in the pack (slot taken).
 *  C 99 Fishing, 50k -> nothing (too poor).
 *  D 98 Mining, 200k -> nothing.
 *  A again -> nothing (owns it).
 */
public final class TestB15B5 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15B5] " + s); }
    static AIPlayer make(long coins, int... ninetyNines) {
        AIPlayer b = TestCR1.bot(Location.create(3160 + (int) (Math.random() * 6), 3485, 0), 60, 60, 60, 60);
        for (int sk : ninetyNines) { int lvl = sk < 0 ? 98 : 99; int s = Math.abs(sk); b.getSkills().setStaticLevel(s, lvl); b.getSkills().setLevel(s, lvl); }
        for (Item it : b.getBank().toArray()) if (it != null) b.getBank().remove(it);
        b.getInventory().remove(new Item(995, b.getInventory().getAmount(995)));
        Ledger.Wallet.give(b, coins);
        return b;
    }
    static String check(String tag, SmartAdventurers.Brain br) {
        AIPlayer b = (AIPlayer) br.bot;
        long before = Ledger.Wallet.coins(b), paid = Ledger.PAID;
        Object r;
        try {
            java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField("nextCape"); f.setAccessible(true); f.set(null, 0L); // the server-wide spacing, for the trial
            java.lang.reflect.Method m = SmartAdventurers.Brain.class.getDeclaredMethod("checkCape"); m.setAccessible(true); r = m.invoke(br);
        } catch (NoSuchMethodException | NoSuchFieldException e) { r = "no cape check in this build"; } catch (Throwable t) { r = "error " + t; }
        Item cape = b.getEquipment().get(1);
        StringBuilder held = new StringBuilder();
        for (int id = 9747; id <= 9812; id++) { int p = b.getInventory().getAmount(id), k = b.getBank().getAmount(id); if (p + k > 0) held.append(MarketBrain.name(id)).append(" pack ").append(p).append(" bank ").append(k).append("; "); }
        return tag + " " + b.getUsername() + ": checkCape -> " + r + ", coins " + before + " -> " + Ledger.Wallet.coins(b) + " (Ledger.PAID +" + (Ledger.PAID - paid) + "), cape slot " + (cape == null ? "-" : cape.getName()) + ", capes held: " + (held.length() == 0 ? "none" : held.toString());
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15b5")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        AIPlayer a = make(120_000, Skills.MINING), b = make(250_000, Skills.MINING, Skills.SMITHING), c = make(50_000, Skills.FISHING), d = make(200_000, -Skills.MINING);
                        b.getInventory().add(new Item(1019)); SmartAdventurers.wear(b, 1019); // a black cape on
                        SmartAdventurers.Brain ba = TestCR1.rig(a, true), bb = TestCR1.rig(b, true), bc = TestCR1.rig(c, true), bd = TestCR1.rig(d, true);
                        log("RESULT " + check("A", ba));
                        log("RESULT " + check("B", bb));
                        log("RESULT " + check("C", bc));
                        log("RESULT " + check("D", bd));
                        log("RESULT " + check("A2", ba));
                    }
                    if (t == 160) { log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
