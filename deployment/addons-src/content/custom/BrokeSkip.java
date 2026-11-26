package content.custom;

import java.util.HashMap;
import java.util.Map;

/**
 * W4 guards (-Dbots.brokeJob.zeroSkip, default off): a broke bot whose FREE job ended with nothing collected and a
 * "Nothing for ..." result does not get that same job again for COOLDOWN_TICKS (game ticks; 2000 = 20 min). The broke
 * picker (SmartAdventurers.Brain.chooseJob) takes the next free candidate; W9 guards2: with none inside the broke radius it takes the best
 * free job past the radius (the walk limit still applies); with no free job at all it picks as before, and says so.
 * One instance per bot, in memory only (nothing is saved). Plain java, no world: see tests TestBrokeSkip.
 */
final class BrokeSkip {
    static final boolean ON = Boolean.getBoolean("bots.brokeJob.zeroSkip");
    static final int COOLDOWN_TICKS = Math.max(1, Integer.getInteger("bots.brokeJob.zeroSkipTicks", 2000));
    static int NOTED, SKIPS, FAR, FALLBACKS; // counters (tests, reads; printed on the [Broke] lines)
    static int minutes() { return COOLDOWN_TICKS * 6 / 10 / 60; }

    private final Map<Object, Integer> until = new HashMap<>(); // job -> the tick its skip ends
    private int nextLog = Integer.MIN_VALUE;
    /** The free job the broke picker chose last (only a job picked as the broke bot's free job can be noted). */
    Object picked;

    /** A zero-yield ending: nothing collected and the runner's "Nothing for ..." result. */
    static boolean zero(int collected, String why) { return collected == 0 && why != null && why.startsWith("Nothing for "); }

    /** Trip end. True when the job starts its cooldown. */
    boolean note(Object job, int collected, String why, int tick) {
        if (job == null || job != picked || !zero(collected, why)) return false;
        until.put(job, tick + COOLDOWN_TICKS); NOTED++;
        return true;
    }

    /** Is this job inside its cooldown? (An ended cooldown is dropped.) */
    boolean skipping(Object job, int tick) {
        if (until.isEmpty()) return false;
        Integer u = until.get(job);
        if (u == null) return false;
        if (tick >= u) { until.remove(job); return false; }
        return true;
    }

    /**
     * The picker's result: best = the best free job outside a cooldown (or null), skipped = the best free job inside one (or null).
     * No other candidate: the skipped one, as before. Returns the job to take.
     */
    <J> J resolve(J best, J skipped) { return resolve(best, skipped, null); }

    /**
     * W9 guards2: far = the best free job past the broke radius and outside a cooldown (or null). g61b: the resting job was the only free
     * job inside the 200-tile radius at Varrock square (the Lumbridge men are 206 tiles off, the farmers full), so every zero-yield guard
     * trip fell back to guards without a line. Order: best, then far, then the resting job.
     */
    <J> J resolve(J best, J skipped, J far) {
        if (skipped == null) return best;
        if (best != null) { SKIPS++; return best; }
        if (far != null) { FAR++; return far; }
        FALLBACKS++;
        return skipped;
    }

    /** The counters for the [Broke] lines. */
    static String counts() { return "noted=" + NOTED + " skips=" + SKIPS + " far=" + FAR + " fallbacks=" + FALLBACKS; }

    /** Rate limit for the log line: once per bot per cooldown. */
    boolean logDue(int tick) {
        if (nextLog != Integer.MIN_VALUE && tick < nextLog) return false;
        nextLog = tick + COOLDOWN_TICKS;
        return true;
    }
}
