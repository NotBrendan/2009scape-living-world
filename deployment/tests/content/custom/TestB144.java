package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/** Trial only (-Dtest.b144=true): batch 14 item 4. Ledger mutations from a thread that owns nothing: one [ThreadViolation] per call site. */
public final class TestB144 implements StartupListener {
    static void log(String s) { System.out.println("[TestB144] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b144")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t == 80) { log("on the game thread \"" + Thread.currentThread().getName() + "\": trim(1, 0) twice (allowed, expect nothing)"); Ledger.trim(1, 0); Ledger.trim(1, 0); return false; }
                if (t != 90) return false;
                Thread w = new Thread(() -> {
                    log("on \"" + Thread.currentThread().getName() + "\": trim(1, 0) three times from one call site, then rename(x, x) once");
                    for (int i = 0; i < 3; i++) Ledger.trim(1, 0);
                    Ledger.rename("b144", "b144");
                    log("RESULT violations counted " + Ledger.threadViolations + " (lines logged: one per call site)");
                    log("DONE");
                }, "TestB144-wrong-thread");
                w.start();
                return true;
            }
        });
    }
}
