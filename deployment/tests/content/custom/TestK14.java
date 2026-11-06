package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.k14=true): batch 14 Karamja crossing. An AFK runner's goTo() then walk() every 3 ticks, 240 ticks a leg.
 * A: Port Sarim docks to Musa Point with coins (must cross, not stand at the shore). B: the same with no coins (must finish
 * with a reason, not walk into the sea). C: Port Sarim to Rimmington (mainland: walked, no teleport). D: Brimhaven to the
 * Karamja mine (on the island: walked, no teleport).
 */
public final class TestK14 implements StartupListener {
    static void log(String s) { System.out.println("[TestK14] " + s); }
    static final Location[][] LEGS = {
        {Location.create(3041, 3202, 0), Location.create(2924, 3178, 0)},
        {Location.create(3041, 3202, 0), Location.create(2924, 3178, 0)},
        {Location.create(3041, 3202, 0), Location.create(2957, 3214, 0)},
        {Location.create(2780, 3180, 0), Location.create(2846, 3034, 0)},
    };
    @Override public void startup() {
        if (!Boolean.getBoolean("test.k14")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg = -1, ticks; AIPlayer bot; AfkTasks.Runner r; boolean sent, tele; double best;
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    if (bot == null || ticks >= 240 || r != null && (r.state == AfkTasks.State.WORK || r.halted)) {
                        if (r != null) {
                            boolean arrived = r.state == AfkTasks.State.WORK;
                            log("RESULT leg " + "ABCD".charAt(leg) + " " + LEGS[leg][0] + " -> " + LEGS[leg][1] + ": " + (arrived ? "ARRIVED at tick " + ticks : r.halted ? "finished: '" + r.last + "'" : "NOT arrived after 240 ticks")
                                + ", teleported " + tele + ", now at " + bot.getLocation() + " (" + Math.round(bot.getLocation().getDistance(LEGS[leg][1])) + " tiles off, closest " + Math.round(best) + ")");
                            bot.getPulseManager().clear(); bot.getWalkingQueue().reset();
                        }
                        if (++leg >= LEGS.length) { log("DONE"); return true; }
                        bot = TestCR1.bot(LEGS[leg][0], 40, 20, 20, 20);
                        bot.getInventory().remove(new Item(995, bot.getInventory().getAmount(995)));
                        bot.getBank().remove(new Item(995, bot.getBank().getAmount(995)));
                        if (leg != 1) bot.getInventory().add(new Item(995, 20000));
                        r = null; sent = false; tele = false; ticks = 0; best = Double.MAX_VALUE;
                        return false;
                    }
                    ticks++;
                    if (ticks == 3 && !sent) {
                        r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false);
                        r.walker = false;
                        boolean ok = r.goTo(AfkTasks.State.TO_SPOT, LEGS[leg][1]);
                        sent = true;
                        log("leg " + "ABCD".charAt(leg) + " goTo returned " + ok + ", last '" + r.last + "', coins " + bot.getInventory().getAmount(995));
                        return false;
                    }
                    if (r == null) return false;
                    Location before = bot.getLocation();
                    if (ticks % 3 == 0 && !r.halted) r.walk(AfkTasks.State.WORK);
                    if (ticks > 3 && bot.getLocation().getDistance(before) > 30) tele = true;
                    best = Math.min(best, bot.getLocation().getDistance(LEGS[leg][1]));
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
