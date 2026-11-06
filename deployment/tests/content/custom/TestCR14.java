package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.cr14=true; run with the default and with -Dbots.deathLoss=false): combat review 10-23 #14. A bot
 * with known kit (a worn iron scimitar, rune/adamant/mithril/2 iron bars, 3 trout) and 20k on a barbarian trip is killed
 * by a barbarian. Before, and with the switch off: no grave, kit intact. Default (on): kept 3, the rest in a gravestone, and the
 * grave run (a paid teleport) brings it back.
 */
public final class TestCR14 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR14] " + s); }
    static String liveSwitch() { try { return String.valueOf(SmartAdventurers.class.getDeclaredField("DEATH_LOSS").get(null)); } catch (NoSuchFieldException e) { return "(none in this build)"; } catch (Throwable t) { return "?" + t; } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr14")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 250; int t, died; AIPlayer a; SmartAdventurers.Brain ba; String before; int items0; boolean graveSeen;
            int kit(AIPlayer p) { int n = 0; for (int id : new int[]{2363, 2361, 2359, 2351, 333, 1323}) n += p.getInventory().getAmount(id) + p.getEquipment().getAmount(id) + p.getBank().getAmount(id); return n; }
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) {
                        a = TestCR1.bot(Location.create(3082, 3420, 0), 30, 20, 20, 20); Ledger.Wallet.give(a, 20_000);
                        a.getEquipment().add(new Item(1323), true, false); // iron scimitar, worn
                        for (int id : new int[]{2363, 2361, 2359, 2351, 2351, 333, 333, 333}) a.getInventory().add(new Item(id));
                        before = TestCR1.pack(a); items0 = kit(a);
                        log("bots.deathLoss=" + System.getProperty("bots.deathLoss", "(unset)") + ", live switch " + liveSwitch() + "; bot " + a.getUsername() + " with " + before + " (" + items0 + " kit items), " + Ledger.Wallet.coins(a) + " gp");
                    }
                    if (t == T0 + 4) { ba = TestCR1.rig(a, false); TestCR1.trip(ba, AfkTasks.Task.F_MAN); } // a label of its own: two deaths at one label close the grave run
                    if (t == T0 + 15) { NPC g = TestCR1.npcAt(a); log("killed by " + g.getName() + " at " + a.getLocation()); TestCR1.killBy(a, g); died = t; }
                    if (died > 0 && t == died + 20) { String gr = TestCR1.grave(a); graveSeen = !gr.equals("none"); log("after respawn: at " + a.getLocation() + ", hp " + a.getSkills().getLifepoints() + ", carrying " + TestCR1.pack(a) + "; grave " + gr + "; grave run " + (ba.graveAt != null ? "ON" : "off")); }
                    if (died > 0 && t > died + 20 && t < died + 200 && !graveSeen && !TestCR1.grave(a).equals("none")) graveSeen = true;
                    if (died > 0 && (t == died + 300 || t > died + 25 && graveSeen && ba.graveAt == null && TestCR1.grave(a).equals("none") && t > died + 40)) {
                        log("RESULT grave made: " + graveSeen + "; kit items before " + items0 + ", after (pack, worn and bank) " + kit(a) + "; now at " + a.getLocation() + " carrying " + TestCR1.pack(a) + "; grave " + TestCR1.grave(a));
                        if (ba.runner != null) ba.runner.halt(); log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
