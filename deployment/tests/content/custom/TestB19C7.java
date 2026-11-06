package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;

import java.util.List;

/**
 * batch 19c A7, trial only (-Dtest.b19c7=true -Xmx512m): §8.2. The memory rule fed 3 simulated hours of minutes (Economy.Mem.sample:
 * after-GC old-gen use climbing in 2 MB steps, 200 MB in all, against a 400 MB pool) -> one memory-growth at the first minute where the
 * 60-minute rise of the 10-minute minimum passes 150 MB above 75% of the pool, none before; one real MX read printed; then
 * pulseCost("Test", null, 150 ms) -> one long-pulse-test, a second within 10 min throttled (counted). [Fable] the 200 MB are fed as
 * numbers, not allocated: a real 200 MB retained on a 512 MB trial heap risks the trial world itself. Prints [TestB19C7] RESULT ... and DONE.
 */
public final class TestB19C7 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C7] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c7")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 30) return false;
                if (Failures.started && Watchdog.Daily.day != null) { new Thread(TestB19C7::run, "TestB19C7").start(); return true; }
                if (t > 600) { log("RESULT Failures never started"); log("DONE"); return true; }
                return false;
            }
        });
    }
    static void run() { try { mem(); pulse(); } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); } log("DONE"); }
    static Object cx(JSONObject j, String k) { Object c = j.get("ctx"); return c instanceof JSONObject ? ((JSONObject) c).get(k) : null; }

    static void mem() {
        long MB = 1024L * 1024L, max = 400 * MB, base = 150 * MB, m0 = 60_000_000L;
        Economy.Mem.reset();
        int r0 = TestB19CUtil.recs("memory-growth", "-").size(); int firedAt = -1, firedN = 0, ruleFirst = -1;
        long[] v = new long[180];
        for (int m = 0; m < 180; m++) { long steps = m < 60 ? 0 : Math.min(100, (m - 60) * 2); v[m] = base + steps * 2 * MB; } // two 2 MB steps a minute from minute 60, 200 MB in all
        for (int m = 0; m < 180; m++) {
            if (ruleFirst < 0 && m >= 69) { long now10 = Long.MAX_VALUE, old10 = Long.MAX_VALUE; for (int k = m - 9; k <= m; k++) now10 = Math.min(now10, v[k]); for (int k = m - 69; k <= m - 60; k++) old10 = Math.min(old10, v[k]); if (now10 - old10 > 150 * MB && now10 > max * 3 / 4) ruleFirst = m; }
            String c = Economy.Mem.sample(m0 + m, v[m], max, m * 50L);
            if ("memory-growth".equals(c)) { firedN++; if (firedAt < 0) firedAt = m; }
        }
        Economy.Mem.reset();
        List<JSONObject> r = TestB19CUtil.recs("memory-growth", "-");
        JSONObject j = r.size() > r0 ? r.get(r.size() - 1) : null;
        log("RESULT memory-growth: fired at simulated minute " + firedAt + " (the rule first holds at " + ruleFirst + "), " + firedN + " time(s), records +" + (r.size() - r0) + (j == null ? "" : ", afterGcMb " + cx(j, "afterGcMb") + ", rise60mMb " + cx(j, "rise60mMb") + ", maxMb " + cx(j, "maxMb") + ", bug " + j.get("bug")) + " (expect the same minute, 1, +1)");
        String real = "none";
        for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) { java.lang.management.MemoryUsage u = p.getCollectionUsage(); if (u != null && p.getType() == java.lang.management.MemoryType.HEAP) real = real + "; " + p.getName() + " after GC " + u.getUsed() / MB + " MB of " + (u.getMax() / MB) + " MB"; }
        log("RESULT real MX read: " + real + ", heap max " + Runtime.getRuntime().maxMemory() / MB + " MB");
    }

    static void pulse() {
        long wait = 125_000L - (System.currentTimeMillis() - Failures.BOOT); if (wait > 0) TestB19CUtil.sleep(wait); // §11.2: pulseCost ignores the first 120 s
        int c0 = TestB19CUtil.fail("long-pulse"), r0 = TestB19CUtil.recs("long-pulse", "-").size();
        Failures.pulseCost("Test", null, 150_000_000L);
        Failures.pulseCost("Test", null, 160_000_000L);
        Failures.pulseCost("Test", null, 5_000_000L);
        List<JSONObject> r = TestB19CUtil.recs("long-pulse", "-");
        JSONObject j = r.size() > r0 ? r.get(r.size() - 1) : null;
        log("RESULT long-pulse: records +" + (r.size() - r0) + (j == null ? "" : ", ms " + cx(j, "ms") + ", ewmaMs " + cx(j, "ewmaMs") + ", bug " + j.get("bug")) + ", Daily long-pulse +" + (TestB19CUtil.fail("long-pulse") - c0) + ", topPulse " + Failures.topPulse() + " (expect +1, b-long-pulse-test, +2, Test)");
    }
}
