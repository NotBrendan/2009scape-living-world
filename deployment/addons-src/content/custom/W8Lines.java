package content.custom;

import java.util.Timer;
import java.util.TimerTask;

/**
 * W8 wave 4 lane B: one hourly counter line per new class, each on its own timer task, scheduled only when that class's
 * flag is on (the [W5a] pattern). MarketBrain calls schedule() once at start; with every W8 flag off nothing is scheduled.
 */
final class W8Lines {
    private W8Lines() { }

    static boolean anyOn() { return SmithOwn.ON || MineGuild.ON || Hunter.ON || SlayerOnly.ONLY1; }

    static void schedule(Timer t, boolean fast) {
        long first = fast ? 30_000 : 120_000, every = fast ? 120_000 : 3_600_000;
        if (SmithOwn.ON) t.schedule(task(SmithOwn::line), first, every);
        if (MineGuild.ON) t.schedule(task(MineGuild::line), first, every);
        if (Hunter.ON) t.schedule(task(Hunter::line), first, every);
        if (SlayerOnly.ONLY1) t.schedule(task(SlayerOnly::line), first, every);
    }

    static TimerTask task(java.util.function.Supplier<String> line) {
        return new TimerTask() { @Override public void run() { try { System.out.println(line.get()); } catch (Throwable ignored) { } } };
    }
}
