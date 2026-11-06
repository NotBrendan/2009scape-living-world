package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b15b6=true): batch 15 item6, the Heroes' Quest partner. Two stand-in players (Companions.TEST_HUMANS),
 * one Black Arm, one Phoenix, each ask an idle adventurer for a Heroes' Quest partner (Companions.partner). In the engine
 * (HeroesQuestListener, GripBehavior) a Black Arm member needs Grip's key ring (1588) to open the treasure room and
 * search the chest; a Phoenix member needs a candlestick (1577). What each partner holds out (pack or the ground) is logged.
 */
public final class TestB15B6 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15B6] " + s); }
    static String offered(AIPlayer bot, AIPlayer h) {
        StringBuilder s = new StringBuilder();
        for (int id : new int[]{1577, 1588, 1586}) {
            int p = bot.getInventory().getAmount(id);
            boolean ground = false;
            try { for (core.game.node.item.GroundItem gi : core.game.node.item.GroundItemManager.getItems()) if (gi != null && gi.isActive() && gi.getId() == id && gi.getLocation().getDistance(bot.getLocation()) <= 2) ground = true; } catch (Throwable ignored) { }
            if (p > 0 || ground) s.append(MarketBrain.name(id)).append(" (").append(id).append(p > 0 ? ", in the partner's pack" : ", on the ground").append(") ");
        }
        return s.length() == 0 ? "nothing" : s.toString().trim();
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15b6")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        log("tradeable: candlestick 1577 " + new Item(1577).getDefinition().isTradeable() + ", Grip's key ring 1588 " + new Item(1588).getDefinition().isTradeable());
                        AIPlayer ha = TestCR1.bot(Location.create(3165, 3485, 0), 50, 40, 40, 40), pa = TestCR1.bot(Location.create(3166, 3485, 0), 50, 40, 40, 40);
                        AIPlayer hp = TestCR1.bot(Location.create(3165, 3490, 0), 50, 40, 40, 40), pp = TestCR1.bot(Location.create(3166, 3490, 0), 50, 40, 40, 40);
                        ha.setAttribute("/save:black-arm-gang", true); hp.setAttribute("/save:phoenix-gang", true);
                        Companions.TEST_HUMANS.add(ha.getUsername().toLowerCase(java.util.Locale.ROOT)); Companions.TEST_HUMANS.add(hp.getUsername().toLowerCase(java.util.Locale.ROOT));
                        SmartAdventurers.Brain ba = TestCR1.rig(pa, false), bp = TestCR1.rig(pp, false);
                        String ra = Companions.partner(pa, ha, "i need a phoenix partner for heroes quest");
                        String rp = Companions.partner(pp, hp, "anyone want to be my black arm partner for heroes quest?");
                        log("RESULT Black Arm asker " + ha.getUsername() + ": partner says \"" + ra + "\", holds out " + offered(pa, ha));
                        log("RESULT Phoenix asker " + hp.getUsername() + ": partner says \"" + rp + "\", holds out " + offered(pp, hp));
                    }
                    if (t == 155) { log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
