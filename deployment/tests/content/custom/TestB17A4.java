package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.b17a4=true): batch 17 item4 (audit O-3). From tick 300 every game tick is made 2.5 s long (a pulse
 * that waits on the Major Update Worker, ignoring the interrupt the stop sends, as a slow real tick would); it prints
 * "SLOW ON" for the harness to send stop. Each slow tick says on its way out whether SmartAdventurers.shutdown() had
 * already begun its snapshot (Ledger.Wallet.SHUTTING_DOWN). Before: the snapshot starts while the tick still runs
 * (true); after: shutdown() waits for the tick first (false), then saves. The saves are checked from outside.
 */
public final class TestB17A4 implements StartupListener {
    static void log(String s) { System.out.println("[TestB17A4] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17a4")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, slow;
            @Override public boolean pulse() {
                t++;
                if (t < 300) return false;
                if (t == 300) { log("HARNESS: stop"); } // batch 21 F: run-test.sh sends stop once on this line (the spare PC's farm runner did it by hand)
                if (t == 300) { Thread w = GameWorld.getMajorUpdateWorker().getWorker(); log("SLOW ON: online smart bots " + SmartAdventurers.BRAINS.size() + "; tick thread '" + Thread.currentThread().getName() + "' is the worker's Thread object " + (w == Thread.currentThread()) + " (worker '" + (w == null ? "null" : w.getName() + "' alive " + w.isAlive()) + ")"); }
                slow++;
                long t0 = System.currentTimeMillis(); boolean irq = false;
                log("slow tick " + slow + " start");
                while (System.currentTimeMillis() - t0 < 2_500) { try { Thread.sleep(50); } catch (InterruptedException e) { irq = true; } }
                if (irq) Thread.currentThread().interrupt(); // hand the stop's interrupt back to the worker
                boolean snap = Ledger.Wallet.SHUTTING_DOWN;
                log((snap || irq ? "RESULT " : "") + "slow tick " + slow + " end after " + (System.currentTimeMillis() - t0) + " ms; stop arrived during it " + irq + "; SmartAdventurers.shutdown snapshot already begun " + snap);
                if (irq) log("DONE");
                return false;
            }
        });
    }
}
