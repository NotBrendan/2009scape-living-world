package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.b145=true): batch 14 item 5. Injected counts into a private Rates (synthetic minutes), then the real
 * counters bumped and data/metrics.json written and read back.
 */
public final class TestB145 implements StartupListener {
    static void log(String s) { System.out.println("[TestB145] " + s); }
    static final long[] C = new long[2];
    static void hour(Economy.Rates r, long start, int i, long events) { // spread events over 60 synthetic minutes
        for (int m = 0; m < 60; m++) { C[i] += events / 60 + (m < events % 60 ? 1 : 0); r.sample(start + m); }
    }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b145")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t != 100) return false;
                try {
                    Economy.Rates r = new Economy.Rates(new String[]{"deaths", "stuck rescues"}, new long[]{5, 5}, new java.util.function.LongSupplier[]{() -> C[0], () -> C[1]});
                    long m0 = 1_000_000; r.sample(m0);
                    hour(r, m0 + 1, 0, 3); log("hour 1: deaths 3 (no alert: the hour before was not watched)");
                    hour(r, m0 + 61, 0, 8); log("hour 2: deaths 8 vs 3 -> expect: deaths doubled 3/h -> 8/h (at the end of hour 2)");
                    hour(r, m0 + 121, 0, 30); log("hour 3: deaths 30 vs 8 -> expect one more alert, no sooner than 60 min after the first");
                    for (int m = 0; m < 30; m++) { C[0] += 2; r.sample(m0 + 181 + m); } log("half hour 4: deaths still climbing; expect at most one alert per 60 min");
                    hour(r, m0 + 211, 1, 4); log("rescues 4 in an hour after 0 -> under the floor of 5, expect none");
                    hour(r, m0 + 271, 1, 6); log("rescues 6 vs 4 -> 6 < 8, expect none");
                    hour(r, m0 + 331, 1, 12); log("rescues 12 vs 6 -> expect: stuck rescues doubled 6/h -> 12/h");
                    Economy.DEATHS.addAndGet(2); Economy.GRAVE_RECOVERIES.incrementAndGet(); Economy.QUEST_STARTS.addAndGet(3); Economy.QUEST_DONE.incrementAndGet();
                    Economy.writeMetrics();
                    log("RESULT metrics.json: " + new String(java.nio.file.Files.readAllBytes(Economy.metricsFile().toPath()), java.nio.charset.StandardCharsets.UTF_8));
                    log("RESULT economy.json carries no metrics: " + (!Economy.file().exists() || !new String(java.nio.file.Files.readAllBytes(Economy.file().toPath())).contains("deaths_last_hour")));
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
