package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.p40boot=true): P40 B5 boot spread. 60 ticks after startup it queues a 20-stack haul through
 * SmartAdventurers.haulLater (supplier "world", 1 log each) and checks the drain pulse: at most bots.bootSpread.haulPerTick
 * stacks reach Ledger.supply in one tick, and the queue is empty within ceil(20/K) + 3 ticks.
 * Lines: "[TestP40Boot] RESULT drain ... PASS|FAIL ..." then "DONE".
 */
public final class TestP40Boot implements StartupListener {
    static void log(String s) { System.out.println("[TestP40Boot] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p40boot")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t = 0, queuedAt = -1, before = 0, maxPerTick = 0, lastDrained = 0;
            @Override public boolean pulse() {
                t++;
                if (t < 60) return false;
                if (queuedAt < 0) {
                    before = SmartAdventurers.haulDrained; lastDrained = before;
                    for (int i = 0; i < 20; i++) SmartAdventurers.haulLater(null, 1511, 1);
                    queuedAt = t; return false;
                }
                int d = SmartAdventurers.haulDrained - lastDrained; lastDrained = SmartAdventurers.haulDrained;
                if (d > maxPerTick) maxPerTick = d;
                int k = SmartAdventurers.HAUL_PER_TICK, limit = (20 + k - 1) / k + 3, took = t - queuedAt;
                boolean empty = SmartAdventurers.HAUL_Q.isEmpty();
                if (!empty && took <= limit) return false;
                boolean pass = empty && maxPerTick <= k && SmartAdventurers.haulDrained - before >= 20;
                log("RESULT drain k=" + k + " maxPerTick=" + maxPerTick + " drained=" + (SmartAdventurers.haulDrained - before) + " ticks=" + took + " limit=" + limit + " " + (pass ? "PASS" : "FAIL"));
                log("DONE");
                return true;
            }
        });
        log("on");
    }
}
