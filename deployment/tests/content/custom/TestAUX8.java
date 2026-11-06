package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aux8=true -Dsmartbots.awaySec=900): audit 10-23 X-8, S-9, X-7 (coins owed through Ledger.Wallet).
 *  X-8: a fake human with a full bank (no coin stack) and a full pack collects 1,000 gp owed. Still owed afterwards, or gone?
 *  S-9: a credit from another thread to an online smart bot, and the bot logs out for a break in the same tick. Owed, or gone?
 *  X-7: with the shutdown flag set (when the build has it), a credit from another thread to an online bot: owed or queued?
 */
public final class TestAUX8 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUX8] " + s); }
    static long owed(String n) { Long v = Ledger.Wallet.OFFLINE.get(n.toLowerCase(java.util.Locale.ROOT)); return v == null ? 0 : v; }
    static void offThread(Runnable r) { Thread th = new Thread(r, "TestAUX8-timer"); th.start(); try { th.join(); } catch (InterruptedException ignored) { } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aux8")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, s9 = -1; AIPlayer h, bot; long before9;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        h = TestA1.human(Location.create(3165, 3487, 0), 900801);
                        int slots = 0; for (int id = 1; id < 12000 && h.getBank().freeSlots() > 0; id++) { if (id == 995) continue; try { if (h.getBank().add(new Item(id, 1))) slots++; } catch (Throwable e) { } }
                        for (int i = 0; i < 28; i++) h.getInventory().add(new Item(1511));
                        Ledger.Wallet.OFFLINE.put(h.getUsername().toLowerCase(java.util.Locale.ROOT), 1000L);
                        long got = Ledger.Wallet.collect(h);
                        log("RESULT X-8 bank free " + h.getBank().freeSlots() + " (" + slots + " stacks), pack free " + h.getInventory().freeSlots() + ": collect returned " + got + ", coins held " + (h.getBank().getAmount(995) + h.getInventory().getAmount(995)) + ", still owed " + owed(h.getUsername()) + " (right: 1000 owed)");
                    }
                    if (t >= 200 && s9 < 0 && t % 20 == 0) {
                        for (Player b : SmartAdventurers.BOTS) if (b instanceof AIPlayer && b.isActive() && SmartAdventurers.BRAINS.containsKey(b.getUsername().toLowerCase(java.util.Locale.ROOT))) { bot = (AIPlayer) b; break; }
                        if (bot != null) {
                            before9 = owed(bot.getUsername());
                            final AIPlayer fb = bot;
                            offThread(() -> Ledger.Wallet.credit(fb.getUsername(), 777)); // a timer-thread credit: queued to the game thread
                            SmartAdventurers.logOut(bot, SmartAdventurers.BRAINS.get(bot.getUsername().toLowerCase(java.util.Locale.ROOT)));
                            s9 = t;
                        } else if (t > 3000) { log("no smart bot"); s9 = t; }
                    }
                    if (s9 > 0 && t == s9 + 5 && bot != null) {
                        log("RESULT S-9 credit of 777 queued, then " + bot.getUsername() + " logged out in the same tick: owed now " + (owed(bot.getUsername()) - before9) + " (right: 777), cleared bot's bank coins " + bot.getBank().getAmount(995));
                        AIPlayer other = null;
                        for (Player b : SmartAdventurers.BOTS) if (b instanceof AIPlayer && b.isActive() && b != bot) { other = (AIPlayer) b; break; }
                        java.lang.reflect.Field f = null; try { f = Ledger.Wallet.class.getDeclaredField("SHUTTING_DOWN"); } catch (NoSuchFieldException e) { }
                        if (other == null) log("RESULT X-7 no other bot");
                        else {
                            if (f != null) f.setBoolean(null, true);
                            long o0 = owed(other.getUsername()); int pulses0 = GameWorld.getPulser().getCurrentPulses().length;
                            final AIPlayer fo = other;
                            offThread(() -> Ledger.Wallet.credit(fo.getUsername(), 555)); // as the shutdown hook thread does in Deals.settleForShutdown
                            long o1 = owed(other.getUsername());
                            if (f != null) f.setBoolean(null, false);
                            log("RESULT X-7 shutdown flag " + (f != null ? "set" : "not in this build") + ": a 555 credit from the shutdown thread is " + (o1 - o0 == 555 ? "OWED (saved with the ledger, collected at the next login)" : "QUEUED to a game-thread pulse (lost once the game loop has stopped)") + "; pulses " + pulses0 + " -> " + GameWorld.getPulser().getCurrentPulses().length);
                        }
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
