package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b16k=true): batch 16 item6, the Karamja boat. An AFK runner's goTo() then walk() every tick (as the
 * runner's own pulse does), 600 ticks a leg. A: Port Sarim to Musa Point, 20,000 gp in the BANK (boat: 30 gp, no teleport).
 * B: Falador to Musa Point (too far from the docks: teleport). C: Port Sarim to Musa Point broke (finishes, as batch 14).
 * D: Port Sarim to Rimmington (mainland walk). E: Brimhaven to the Karamja mine (island walk). F: Musa Point to Port Sarim,
 * 20,000 gp in the pack (the customs officer's boat home).
 * G and H: Port Sarim to Musa Point, 20,000 gp in the pack, the 60 s fallback forced (reflection: the Boat's boardTick set
 * 200 ticks back) - G as soon as the bot boards, H once the fare has been paid but before the landing. Expect the paid
 * teleport ("no boat (not across after 60 s): the teleport instead"). H is watched 30 ticks past its arrival (does the
 * ship, already paid for, still carry it off?).
 * Batch 16 review: I: Musa Point to East Ardougne (2662,3305), 20,000 gp in the pack: bound more than TELEPORT_MIN past
 * the Port Sarim landing, so the paid teleport straight away (no 30 gp boat as well). J: Port Sarim to Musa Point, the
 * 20,000 gp in the bank and a full pack (28 logs): the fare can't reach the pack, so the teleport, not a dead walk to the
 * seamen. K: the same with a full pack that holds the coins (27 logs + 20,000 gp): the boat as in A.
 */
public final class TestB16K implements StartupListener {
    static void log(String s) { System.out.println("[TestB16K] " + s); }
    static final Location[][] LEGS = {
        {Location.create(3041, 3202, 0), Location.create(2924, 3178, 0)},
        {Location.create(2965, 3380, 0), Location.create(2924, 3178, 0)},
        {Location.create(3041, 3202, 0), Location.create(2924, 3178, 0)},
        {Location.create(3041, 3202, 0), Location.create(2957, 3214, 0)},
        {Location.create(2780, 3180, 0), Location.create(2846, 3034, 0)},
        {Location.create(2924, 3178, 0), Location.create(3041, 3202, 0)},
        {Location.create(3041, 3202, 0), Location.create(2924, 3178, 0)},
        {Location.create(3041, 3202, 0), Location.create(2924, 3178, 0)},
        {Location.create(2924, 3178, 0), Location.create(2662, 3305, 0)},
        {Location.create(3041, 3202, 0), Location.create(2924, 3178, 0)},
        {Location.create(3041, 3202, 0), Location.create(2924, 3178, 0)},
    };
    static final int LIMIT = 600;
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b16k")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg = -1, ticks; AIPlayer bot; AfkTasks.Runner r; boolean sent, tele, deck, quay; double best; long coins0; String forced; int post; String after;
            String L() { return String.valueOf("ABCDEFGHIJK".charAt(leg)); }
            long coins() { return Ledger.Wallet.coins(bot); }
            /** G/H: wind the boat's clock back 200 ticks so the 60 s check fires on the next walk(). */
            void force() {
                try {
                    java.lang.reflect.Field bf = AfkTasks.Runner.class.getDeclaredField("boat"); bf.setAccessible(true);
                    Object b = bf.get(r); if (b == null) return;
                    java.lang.reflect.Field idx = b.getClass().getDeclaredField("idx"), bt = b.getClass().getDeclaredField("boardTick"); idx.setAccessible(true); bt.setAccessible(true);
                    if (idx.getInt(b) < 0) return; // still walking to the boarding spot
                    if (leg == 7 && coins() >= coins0) return; // H: wait for the fare
                    bt.setInt(b, GameWorld.getTicks() - 200);
                    forced = "at tick " + ticks + " (step " + idx.getInt(b) + ", spent " + (coins0 - coins()) + " gp, at " + bot.getLocation() + ")";
                    log("leg " + L() + " boat clock forced back " + forced);
                } catch (NoSuchFieldException e) { forced = "n/a (no Boat in this build)"; }
                catch (Throwable e) { forced = "error " + e; }
            }
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    if (leg == 7 && r != null && r.state == AfkTasks.State.WORK && post < 30) { // H: watch on past the arrival
                        if (post++ == 0) after = "none"; Location a = bot.getLocation();
                        if (a.getZ() != 0 || a.getDistance(LEGS[leg][1]) > 10) after = "moved to " + a + " at +" + post + " ticks";
                        return false;
                    }
                    if (bot == null || ticks >= LIMIT || r != null && (r.state == AfkTasks.State.WORK || r.halted)) {
                        if (r != null) {
                            boolean arrived = r.state == AfkTasks.State.WORK;
                            log("RESULT leg " + L() + " " + LEGS[leg][0] + " -> " + LEGS[leg][1] + ": " + (arrived ? "ARRIVED at tick " + ticks : r.halted ? "finished: '" + r.last + "'" : "NOT arrived after " + LIMIT + " ticks")
                                + ", spent " + (coins0 - coins()) + " gp, teleported " + tele + ", on deck " + deck + ", on the Port Sarim quay " + quay + ", last '" + r.last + "', now at " + bot.getLocation()
                                + " (" + Math.round(bot.getLocation().getDistance(LEGS[leg][1])) + " tiles off, closest " + Math.round(best) + ")" + (leg == 6 || leg == 7 ? ", fallback forced: " + forced : "") + (leg == 7 ? ", 30 ticks after arriving: " + after + " (now at " + bot.getLocation() + ")" : ""));
                            bot.getPulseManager().clear(); bot.getWalkingQueue().reset();
                            QuestSteps.clearPopups(bot);
                        }
                        if (++leg >= LEGS.length) { log("DONE"); return true; }
                        bot = TestCR1.bot(LEGS[leg][0], 40, 20, 20, 20);
                        bot.getInventory().remove(new Item(995, bot.getInventory().getAmount(995)));
                        bot.getBank().remove(new Item(995, bot.getBank().getAmount(995)));
                        if (leg >= 9) bot.getInventory().clear();
                        if (leg == 0 || leg == 9) bot.getBank().add(new Item(995, 20000));
                        else if (leg != 2) bot.getInventory().add(new Item(995, 20000));
                        if (leg == 9) for (int k = 0; k < 28; k++) bot.getInventory().add(new Item(1511, 1));
                        if (leg == 10) for (int k = 0; k < 27; k++) bot.getInventory().add(new Item(1511, 1));
                        if (leg >= 9) log("leg " + L() + " pack: " + bot.getInventory().freeSlots() + " free slots, " + bot.getInventory().getAmount(995) + " gp in it, " + bot.getBank().getAmount(995) + " gp in the bank");
                        r = null; sent = false; tele = false; deck = false; quay = false; ticks = 0; best = Double.MAX_VALUE; forced = leg == 6 || leg == 7 ? "no" : null; post = 0; after = null;
                        return false;
                    }
                    ticks++;
                    if (ticks == 3 && !sent) {
                        coins0 = coins();
                        r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false);
                        r.walker = false;
                        boolean ok = r.goTo(AfkTasks.State.TO_SPOT, LEGS[leg][1]);
                        sent = true;
                        log("leg " + L() + " goTo returned " + ok + ", last '" + r.last + "', coins " + coins0 + " (pack " + bot.getInventory().getAmount(995) + ")");
                        return false;
                    }
                    if (r == null) return false;
                    Location before = bot.getLocation();
                    if (!r.halted && r.state != AfkTasks.State.WORK) r.walk(AfkTasks.State.WORK);
                    if ((leg == 6 || leg == 7) && "no".equals(forced)) force();
                    Location now = bot.getLocation();
                    if (ticks > 3 && now.getDistance(before) > 30) { tele = true; log("leg " + L() + " jumped " + before + " -> " + now + " at tick " + ticks + " (last '" + r.last + "')"); }
                    if (now.getZ() == 1 && QuestPiratesTreasure.onKaramja(bot)) deck = true;
                    if (leg == 5 && now.getZ() == 0 && now.getDistance(Location.create(3029, 3217, 0)) <= 3) quay = true;
                    best = Math.min(best, now.getDistance(LEGS[leg][1]));
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
