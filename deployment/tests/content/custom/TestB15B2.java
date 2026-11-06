package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b15b2=true): batch 15 item2, quest rewards worn. QuestRunner.complete (test mode) after the reward
 * appears between the snapshot and the finish, as the last step's reward does:
 *  A amulet of accuracy (1478) in the pack, amulet slot empty -> worn, no copy left in pack or bank.
 *  B the same with an amulet of strength (1725) already worn -> stays in the pack, the old amulet stays on.
 *  C gold ring (1635) put in the bank (a full pack), ring slot empty -> worn, none left in the bank.
 */
public final class TestB15B2 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15B2] " + s); }
    static String where(AIPlayer b, int id) {
        Item n = b.getEquipment().get(id == 1635 ? 12 : 2);
        return "pack " + b.getInventory().getAmount(id) + ", bank " + b.getBank().getAmount(id) + ", worn " + (n == null ? "-" : n.getName() + " (" + n.getId() + ")");
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15b2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, b, c;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        QuestBook.registerWave1();
                        QuestScript imp = QuestBook.byQuest(content.data.Quests.IMP_CATCHER), pir = QuestBook.byQuest(content.data.Quests.PIRATES_TREASURE);
                        a = TestCR1.bot(Location.create(3212, 3240, 0), 30, 20, 20, 20); b = TestCR1.bot(Location.create(3213, 3240, 0), 30, 20, 20, 20); c = TestCR1.bot(Location.create(3214, 3240, 0), 30, 20, 20, 20);
                        for (AIPlayer x : new AIPlayer[]{a, b, c}) { TestCR1.rig(x, false); for (int s : new int[]{2, 12}) { Item w = x.getEquipment().get(s); if (w != null) x.getEquipment().remove(w); } }
                        b.getInventory().add(new Item(1725)); SmartAdventurers.wear(b, 1725);
                        QuestRunner ra = new QuestRunner(a, imp, 0, true); ra.snapshot(); a.getInventory().add(new Item(1478)); ra.complete();
                        QuestRunner rb = new QuestRunner(b, imp, 0, true); rb.snapshot(); b.getInventory().add(new Item(1478)); rb.complete();
                        while (c.getInventory().freeSlots() > 0) c.getInventory().add(new Item(1511)); // logs: a full pack
                        QuestRunner rc = new QuestRunner(c, pir, 0, true); rc.snapshot(); c.getBank().add(new Item(1635)); c.getInventory().remove(new Item(1511, 1)); rc.complete();
                        log("RESULT A amulet of accuracy, slot empty: " + where(a, 1478));
                        log("RESULT B amulet of accuracy, amulet of strength worn: " + where(b, 1478));
                        log("RESULT C gold ring in the bank, slot empty: " + where(c, 1635));
                    }
                    if (t == 160) { log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
