package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b16h=true): batch 16 item7, the free home teleport off Karamja. A broke bot (0 coins, bank and pack)
 * at Brimhaven sent to Port Sarim (3041,3202): goTo() then walk() every tick, 1500 ticks a leg, as TestB16K does.
 * A: the first try (before: finishes "Can't afford the trip across the sea." on Karamja; after: home teleport to
 * Lumbridge 3222,3218 and the walk carries on). B: the same bot put back at Brimhaven and sent again at once (inside the
 * 30 minute cooldown): expect it to finish as before. C (control, no item7 involved): a new broke bot at Lumbridge
 * 3222,3218 walking to the same spot, to tell the home teleport apart from the ordinary walk. Position every 100 ticks.
 */
public final class TestB16H implements StartupListener {
    static void log(String s) { System.out.println("[TestB16H] " + s); }
    static final Location FROM = Location.create(2780, 3180, 0), TO = Location.create(3041, 3202, 0), LUM = Location.create(3222, 3218, 0);
    static final int LIMIT = 1500;
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b16h")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg = -1, ticks; AIPlayer bot; AfkTasks.Runner r; boolean sent, lum, kar; String jump; double best; String goLast; Location prev;
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    if (leg >= 0 && (ticks >= LIMIT || r != null && (r.state == AfkTasks.State.WORK || r.halted))) {
                        Location at = bot.getLocation();
                        log("RESULT leg " + "ABC".charAt(leg) + " " + (leg == 2 ? LUM : FROM) + " -> " + TO + " broke: " + (r.state == AfkTasks.State.WORK ? "ARRIVED at tick " + ticks : r.halted ? "finished (see the [AfkTasks] finished line), at tick " + ticks : "still walking after " + LIMIT + " ticks")
                            + ", goTo last '" + goLast + "', jump " + jump + ", at Lumbridge " + lum + ", still on Karamja " + AfkTasks.Runner.karamja(at) + ", last '" + r.last + "', now at " + at
                            + " (" + Math.round(at.getDistance(TO)) + " tiles off, closest " + Math.round(best) + "), coins " + Ledger.Wallet.coins(bot));
                        bot.getPulseManager().clear(); bot.getWalkingQueue().reset();
                        if (++leg >= 3) { log("DONE"); return true; }
                        if (leg == 2) { bot = TestCR1.bot(LUM, 40, 20, 20, 20); bot.getInventory().remove(new Item(995, bot.getInventory().getAmount(995))); bot.getBank().remove(new Item(995, bot.getBank().getAmount(995))); }
                        else bot.getProperties().setTeleportLocation(FROM); // B: the same bot (same name: the cooldown is per bot), back on Karamja
                        r = null; prev = null; sent = false; lum = false; jump = "none"; ticks = -3; best = Double.MAX_VALUE;
                        return false;
                    }
                    if (bot == null) {
                        bot = TestCR1.bot(FROM, 40, 20, 20, 20);
                        bot.getInventory().remove(new Item(995, bot.getInventory().getAmount(995)));
                        bot.getBank().remove(new Item(995, bot.getBank().getAmount(995)));
                        leg = 0; jump = "none"; best = Double.MAX_VALUE;
                        return false;
                    }
                    ticks++;
                    if (ticks == 3 && !sent) {
                        r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false);
                        r.walker = false;
                        boolean ok = r.goTo(AfkTasks.State.TO_SPOT, TO);
                        sent = true; goLast = r.last;
                        log("leg " + "ABC".charAt(leg) + " goTo returned " + ok + ", halted " + r.halted + ", last '" + r.last + "', at " + bot.getLocation() + ", coins " + Ledger.Wallet.coins(bot));
                        return false;
                    }
                    if (r == null) return false;
                    Location before = prev != null ? prev : bot.getLocation(); // last pulse's tile: a teleport lands between pulses
                    if (!r.halted && r.state != AfkTasks.State.WORK) r.walk(AfkTasks.State.WORK);
                    Location now = bot.getLocation();
                    if (now.getDistance(before) > 30) { jump = before + " -> " + now + " at tick " + ticks; log("leg " + "ABC".charAt(leg) + " jumped " + jump); }
                    if (now.getDistance(LUM) <= 3) lum = true;
                    best = Math.min(best, now.getDistance(TO)); prev = now;
                    if (ticks % 100 == 0) log("leg " + "ABC".charAt(leg) + " tick " + ticks + ": at " + now + ", last '" + r.last + "'");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
