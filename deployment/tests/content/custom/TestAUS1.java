package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aus1=true): audit 10-23 S-1. A ranger bot with an anti-dragon shield worn and a full pack buys a
 * shortbow (two-handed, so the shield can't come off into the full pack) through kitFromExchange. Counts the bows the bot
 * owns afterwards (bank + pack + worn): 1 is right, 2 is the duplicate.
 */
public final class TestAUS1 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUS1] " + s); }
    static int owned(AIPlayer b, int id) { int n = b.getBank().getAmount(id) + b.getInventory().getAmount(id); Item w = b.getEquipment().get(3); if (w != null && w.getId() == id) n++; return n; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aus1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer b; SmartAdventurers.Brain br; boolean shield;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        String name = null;
                        for (int i = 11; i < 400 && name == null; i++) { String n = "Aus" + (char) ('a' + i % 26) + (char) ('a' + (i / 26) % 26) + (100 + i % 800); if (SmartAdventurers.styleFor(n, -1) == SmartAdventurers.RANGED) name = n; }
                        b = name == null ? null : SmartAdventurers.namedBot(name, TestCR1.LUMBRIDGE);
                        if (b == null) { log("could not make a ranger bot (name " + name + ")"); log("DONE"); return true; }
                        SmartAdventurers.finishTutorial(b);
                        String key = b.getUsername().toLowerCase(java.util.Locale.ROOT);
                        SmartAdventurers.FAV.put(key, -1);
                        b.getSkills().setStaticLevel(Skills.RANGE, 1); b.getSkills().setLevel(Skills.RANGE, 1);
                        br = TestCR1.rig(b, false);
                        log("bot " + b.getUsername() + " style " + SmartAdventurers.styleOf(b) + " (RANGED=" + SmartAdventurers.RANGED + ")");
                    }
                    if (t == 155) {
                        b.getInventory().clear(); for (Item it : b.getBank().toArray()) if (it != null) b.getBank().remove(it);
                        for (int s = 0; s < 14; s++) { Item w = b.getEquipment().get(s); if (w != null) b.getEquipment().remove(w); }
                        b.getInventory().add(new Item(1540)); shield = SmartAdventurers.wear(b, 1540);
                        for (int i = 0; i < 28; i++) b.getInventory().add(new Item(1511)); // a full pack of logs
                        Ledger.Wallet.give(b, 20_000);
                        Ledger.supply(null, 841, 1);
                    }
                    if (t == 165) {
                        java.util.Map<Integer, Integer> m = new java.util.HashMap<>(MarketBrain.stock); m.put(841, core.game.ge.GrandExchange.Companion.getBotstockForId(841)); MarketBrain.stock = m; // the 5-minute refresh, now
                        log("shield worn " + shield + ", pack free slots " + b.getInventory().freeSlots() + ", shortbows in bot stock " + MarketBrain.stockOf(841) + ", bows owned before " + owned(b, 841) + ", wants " + describe(br.armourNeeds()));
                        br.kitFromExchange();
                        Item w = b.getEquipment().get(3);
                        log("RESULT after kitFromExchange: shortbows owned " + owned(b, 841) + " (bank " + b.getBank().getAmount(841) + ", pack " + b.getInventory().getAmount(841) + ", worn " + (w != null && w.getId() == 841) + "), shield still worn " + (b.getEquipment().get(5) != null) + ", stock left " + MarketBrain.stockOf(841));
                        // the restore path: a bow in the saved gear list that can't be worn is kept once, not lost
                        for (Item it : b.getBank().toArray()) if (it != null && it.getId() == 841) b.getBank().remove(it);
                        boolean ok = SmartAdventurers.wear(b, 841);
                        log("RESULT wear() of a new bow it can't put on: returned " + ok + ", shortbows owned " + owned(b, 841));
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
    static String describe(java.util.List<int[]> l) { StringBuilder s = new StringBuilder(); for (int[] x : l) s.append(x[0]).append(' '); return s.toString().trim(); }
}
