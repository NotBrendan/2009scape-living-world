package content.custom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * W4 guards: BrokeSkip, plain java (no world, no server.jar needed at run time):
 *   ON : java -Dbots.brokeJob.zeroSkip=true -cp <build> content.custom.TestBrokeSkip <addons-src>/content/custom
 *   OFF: java -cp <build> content.custom.TestBrokeSkip <addons-src>/content/custom
 * The rule (note / skipping / resolve / logDue) runs for real; its wiring in SmartAdventurers and AfkTasks is checked by source text
 * (Brain needs the loaded world).
 */
public final class TestBrokeSkip {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestBrokeSkip] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }

    public static void main(String[] a) throws Exception {
        boolean on = Boolean.getBoolean("bots.brokeJob.zeroSkip");
        check("flag constant follows -Dbots.brokeJob.zeroSkip (" + on + ")", BrokeSkip.ON == on);
        check("default cooldown is 2000 ticks (20 min)", BrokeSkip.COOLDOWN_TICKS == 2000 || System.getProperty("bots.brokeJob.zeroSkipTicks") != null);
        final int cd = BrokeSkip.COOLDOWN_TICKS;
        final String nothing = "Nothing for guards here, and I've tried every spot I know nearby.";
        Object guards = "Guards", men = "Men", other = "Chickens";

        check("zero: 0 collected + Nothing for", BrokeSkip.zero(0, nothing));
        check("zero: not with a haul", !BrokeSkip.zero(3, nothing));
        check("zero: not another reason", !BrokeSkip.zero(0, "Spot ran dry.") && !BrokeSkip.zero(0, null));

        BrokeSkip s = new BrokeSkip();
        check("fresh state skips nothing", !s.skipping(guards, 100));
        check("a job the broke picker did not choose is not noted", !s.note(guards, 0, nothing, 100) && !s.skipping(guards, 101));
        s.picked = guards;
        check("a haul is not noted", !s.note(guards, 5, nothing, 100) && !s.skipping(guards, 101));
        check("another finish reason is not noted", !s.note(guards, 0, "Inventory full.", 100) && !s.skipping(guards, 101));
        check("null job is not noted", !s.note(null, 0, nothing, 100));
        check("zero-yield free job is noted", s.note(guards, 0, nothing, 100));
        check("skipped inside the cooldown", s.skipping(guards, 101) && s.skipping(guards, 100 + cd - 1));
        check("only that job is skipped", !s.skipping(men, 101) && !s.skipping(other, 101));
        check("picker: next candidate taken", s.resolve(men, guards) == men);
        check("picker: no other candidate -> the old pick", s.resolve(null, guards) == guards);
        check("picker: nothing skipped -> unchanged", s.resolve(men, null) == men && s.resolve((Object) null, null) == null);
        // W9 guards2 (g61b: guards was the only free job inside the radius, so every zero trip fell back to it without a line)
        int sk0 = BrokeSkip.SKIPS, far0 = BrokeSkip.FAR, fb0 = BrokeSkip.FALLBACKS;
        check("W9 picker: a free job inside the radius beats the far one", s.resolve(men, guards, other) == men && BrokeSkip.SKIPS == sk0 + 1 && BrokeSkip.FAR == far0);
        check("W9 picker: nothing free inside the radius -> the far free job, not the resting one", s.resolve(null, guards, other) == other && BrokeSkip.FAR == far0 + 1 && BrokeSkip.FALLBACKS == fb0);
        check("W9 picker: no free job at all -> the resting job, counted", s.resolve(null, guards, null) == guards && BrokeSkip.FALLBACKS == fb0 + 1);
        check("W9 picker: nothing skipped -> far ignored", s.resolve(men, null, other) == men && s.resolve(null, null, other) == null);
        check("W9 counts line names all four counters", BrokeSkip.counts().startsWith("noted=") && BrokeSkip.counts().contains(" skips=") && BrokeSkip.counts().contains(" far=") && BrokeSkip.counts().contains(" fallbacks=") && BrokeSkip.minutes() == BrokeSkip.COOLDOWN_TICKS * 6 / 10 / 60);
        check("log line once per cooldown", s.logDue(101) && !s.logDue(102) && !s.logDue(100 + cd) && s.logDue(101 + cd) && !s.logDue(102 + cd));
        check("cooldown ends", !s.skipping(guards, 100 + cd) && !s.skipping(guards, 100 + cd + 1));
        check("a second zero trip starts a new cooldown", s.note(guards, 0, nothing, 5000) && s.skipping(guards, 5001));
        BrokeSkip t = new BrokeSkip();
        check("state is per bot", !t.skipping(guards, 5001));

        if (a.length > 0) {
            String sa = new String(Files.readAllBytes(new File(a[0], "SmartAdventurers.java").toPath()), StandardCharsets.UTF_8);
            String afk = new String(Files.readAllBytes(new File(a[0], "AfkTasks.java").toPath()), StandardCharsets.UTF_8);
            check("H1 Brain state is null with the flag off", sa.contains("final BrokeSkip zeroSkip = BrokeSkip.ON ? new BrokeSkip() : null;"));
            check("H2 picker reads it only when broke-free", sa.contains("final BrokeSkip zs = brokeFree ? zeroSkip : null;"));
            check("H3 every picker use is behind zs != null", sa.contains("zs != null && zs.skipping(j, zsTick)") && sa.contains("if (zs != null && zsJob != null) {") && sa.contains("if (zs != null) zs.picked = bestFree;"));
            check("H4 trip end notes behind zeroSkip != null", sa.contains("if (zeroSkip != null) try { if (zeroSkip.note(lastJob, runner.collected, runner.endWhy, GameWorld.getTicks())) System.out.println(\"[Broke] \" + bot.getUsername() + \" rests \""));
            check("H5 runner keeps the reason only with the flag on", afk.contains("if (BrokeSkip.ON) endWhy = why;"));
            check("H6 skip and fallback lines have the [Broke] prefix and the rate limit", sa.contains("if (zs.logDue(zsTick)) System.out.println(\"[Broke] \" + bot.getUsername() + \" skips \"") && sa.contains("if (zs.logDue(zsTick)) System.out.println(\"[Broke] \" + bot.getUsername() + \" keeps \""));
            check("H7 W9 far candidate: tracked only behind zs != null, outside a cooldown, past the radius; resolve takes it before the resting job",
                    sa.contains("if (zs != null && !zsSkip && dist > BROKE_RADIUS && score > zsFarScore && freeJob(j, BROKE_RADIUS)) { zsFarScore = score; zsFar = j; }")
                    && sa.contains("Job take = zs.resolve(bestFree, zsJob, zsFar);") && sa.contains("if (far) { bestFree = zsFar; bestFreeScore = zsFarScore; }"));
        }
        System.out.println("[TestBrokeSkip] flag " + (on ? "ON" : "OFF") + ": " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
