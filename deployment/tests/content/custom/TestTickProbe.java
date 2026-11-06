package content.custom;

import core.api.StartupListener;
import core.api.TickListener;
import core.game.bots.GeneralBotCreator;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.repository.Repository;

/**
 * Trial only (-Dtest.tickprobe=true, optional -Dtickprobe.sec=60): tick time and bot-script starvation for soaks and the survey
 * farm (spare PC, batch 18). A Pulse submitted at startup runs early in PulseRunner.updateAll; a TickListener (put first, ahead of the engine's GBCTick that zeroes the bot counter) runs after every
 * pulse of the tick (MajorUpdateWorker.handleTickActions: packets, pulses, tick listeners, update sequence). Per window it prints
 * "[TickProbe] ..." with: tick interval avg/max (600 ms is on time), pulse-phase ms avg/max (early pulse -> listener, the bulk of a
 * tick's work), bot scripts triggered per tick avg/max (GeneralBotCreator's counter, read after every pulse ran) and the ticks
 * where it reached the engine's limit of 75 (later bot scripts skipped that tick), online players and how many are bots, bot deaths (also one "[TickProbe] death <name> at x,y,z" line each).
 * Never DONE: it runs until the world stops.
 */
public final class TestTickProbe implements StartupListener {
    static final int WINDOW = Integer.getInteger("tickprobe.sec", 60);
    static volatile long pulseAt;
    static long last, winStart; static int n, at75; static long sumIv, maxIv, sumPh, maxPh, sumBp, maxBp;
    static final java.util.Set<String> DOWN = new java.util.HashSet<>(); static int deaths; // bots at 0 lifepoints (game thread only)

    @Override public void startup() {
        if (!Boolean.getBoolean("test.tickprobe")) return;
        GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { pulseAt = System.nanoTime(); return false; } });
        GameWorld.getTickListeners().add(0, new TickListener() { @Override public void tick() { sample(); } }); // first: GBCTick (an engine listener) zeroes the bot-script counter
        System.out.println("[TickProbe] on, window " + WINDOW + " s");
    }

    static void sample() {
        long now = System.nanoTime();
        int bp = GeneralBotCreator.Companion.getBotPulsesTriggeredThisTick();
        try { for (Player p : Repository.getPlayers()) { if (p == null || !p.isActive() || !p.isArtificial()) continue; String k = p.getUsername(); boolean dn = p.getSkills().getLifepoints() <= 0;
            if (dn && DOWN.add(k)) { deaths++; System.out.println("[TickProbe] death " + k + " at " + p.getLocation().getX() + "," + p.getLocation().getY() + "," + p.getLocation().getZ()); } else if (!dn) DOWN.remove(k); } } catch (Throwable ignored) { }
        if (last != 0) {
            long iv = (now - last) / 1_000_000L, ph = pulseAt > 0 ? (now - pulseAt) / 1_000_000L : 0;
            n++; sumIv += iv; maxIv = Math.max(maxIv, iv); sumPh += ph; maxPh = Math.max(maxPh, ph); sumBp += bp; maxBp = Math.max(maxBp, bp);
            if (bp >= 75) at75++;
        } else winStart = now;
        last = now;
        if (n > 0 && now - winStart >= WINDOW * 1_000_000_000L) {
            int online = 0, bots = 0;
            try { for (Player p : Repository.getPlayers()) if (p != null && p.isActive()) { online++; if (p.isArtificial()) bots++; } } catch (Throwable ignored) { }
            System.out.println("[TickProbe] ticks " + n + ", interval avg " + sumIv / n + " ms max " + maxIv + " ms, pulse phase avg " + sumPh / n + " ms max " + maxPh
                    + " ms, bot scripts/tick avg " + sumBp / n + " max " + maxBp + ", ticks at the 75 limit " + at75 + ", bot deaths " + deaths + ", online " + online + " (" + bots + " bots)");
            n = 0; at75 = 0; deaths = 0; sumIv = maxIv = sumPh = maxPh = sumBp = maxBp = 0; winStart = now;
        }
    }
}
