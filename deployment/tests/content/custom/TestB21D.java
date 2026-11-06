package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.*;

/**
 * Trial only (-Dtest.b21d=true; flags: -Dnav=true -Dsmartbots.count=0): audit B21-6, the cost of
 * AfkTasks.Runner.tollStuck for broke bots standing at the Al Kharid toll gate, with and without its per-bot cache
 * (-Dnav.tollcache, default on; this test switches AfkTasks.Runner.TOLL_CACHE itself and puts it back at the end).
 * Three bots with 5 gp stand still in the gate box: 3266,3227 -> Al Kharid store (round the fence), 3270,3227 -> south of
 * Varrock (round the fence) and 3270,3227 -> Musa Point on Karamja (no walk: the no-route case). Each tick, for
 * -Dtest.b21d.ticks ticks (default 100) per mode, every bot runs tollHop (expect -1) and tollStuck, as the walkers do on an
 * idle tick at the gate. Modes: OFF-WARM (cache off), ON-WARM (cache on), OFF-COLD (cache off, NavPlanner's corridor caches
 * cleared before each call: the live case when its 400-entry LRU has churned), ON-COLD (cache on, corridor caches cleared each
 * tick). Per mode: tollStuck calls, plans, cache hits, total and max ms. Then FORGET: a kept answer, NavWalker.forget, the
 * next call plans again.
 * FAIL when: OFF modes plan less than every call; ON modes plan more than bots x (ticks x 600 ms / TTL + 2); a bot's
 * answer differs between modes; a gate bot that should walk round is called stuck; tollHop is not -1; FORGET does not
 * plan again. Ends with RESULT lines and DONE.
 */
public final class TestB21D implements StartupListener {
    static void log(String s) { System.out.println("[TestB21D] " + s); }

    static final Location ALK = Location.create(3315, 3180, 0), VARROCK_S = Location.create(3226, 3370, 0), MUSA = Location.create(2918, 3160, 0);
    static final String[] MODES = {"OFF-WARM", "ON-WARM", "OFF-COLD", "ON-COLD"};
    static final int TICKS = Math.max(10, Integer.getInteger("test.b21d.ticks", 100));

    static final class B {
        String name; AIPlayer bot; Location at, to; boolean expectStuck, knownExpect;
        Boolean[] answer = new Boolean[MODES.length]; boolean mixed; int tollNot1; boolean moved;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b21d")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, mode = -1, modeTick; long t0; final List<B> bs = new ArrayList<>(); int fails;
            final boolean cacheWas = AfkTasks.Runner.TOLL_CACHE;
            long c0, p0, h0, ns, maxNs, modeT0;

            @Override public boolean pulse() {
                try {
                    t++;
                    long now = System.currentTimeMillis();
                    if (phase == 0 && t == 1200) { log("RESULT planner never came up"); log("DONE"); return true; }
                    if (phase == 0 && t >= 100 && NavPlanner.built) { phase = 1; spawn(); t0 = now; return false; }
                    if (phase == 1 && now - t0 > 3_000) { phase = 2; nextMode(now); }
                    if (phase != 2) return false;
                    boolean cold = mode >= 2, on = mode == 1 || mode == 3;
                    if (cold && on) NavPlanner.clearCaches(); // ON-COLD: the corridor caches gone every tick; only the tollStuck cache can save the plan
                    for (B b : bs) {
                        Location here = b.bot.getLocation();
                        if (here.getX() != b.at.getX() || here.getY() != b.at.getY()) b.moved = true;
                        if (AfkTasks.Runner.tollHop(b.bot, b.to) != -1) b.tollNot1++;
                        if (cold && !on) NavPlanner.clearCaches(); // OFF-COLD: before each call
                        long s = System.nanoTime();
                        boolean stuck = AfkTasks.Runner.tollStuck(b.bot, b.to);
                        long d = System.nanoTime() - s; ns += d; if (d > maxNs) maxNs = d;
                        if (b.answer[mode] == null) b.answer[mode] = stuck; else if (b.answer[mode] != stuck) b.mixed = true;
                    }
                    if (++modeTick >= TICKS) {
                        endMode(now);
                        if (mode + 1 < MODES.length) nextMode(now);
                        else { forgetCheck(); finish(); return true; }
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); AfkTasks.Runner.TOLL_CACHE = cacheWas; log("DONE"); return true; }
                return false;
            }

            void spawn() {
                Location w = Location.create(3266, 3227, 0), e = Location.create(3270, 3227, 0);
                Object[][] rows = {{w, ALK, "west->alkharid", false, true}, {e, VARROCK_S, "east->varrock-s", false, true}, {e, MUSA, "east->musa-point", true, false}};
                int i = 0;
                for (Object[] r : rows) {
                    B b = new B(); b.at = (Location) r[0]; b.to = (Location) r[1]; b.expectStuck = (Boolean) r[3]; b.knownExpect = (Boolean) r[4];
                    b.name = "G" + (++i) + "[" + r[2] + ",5gp]";
                    b.bot = TestCR1.bot(b.at, 10, 1, 1, 1); Ledger.Wallet.give(b.bot, 5);
                    bs.add(b);
                }
                log("bots: " + bs.size() + ", ticks per mode " + TICKS + ", cache TTL " + AfkTasks.Runner.TOLL_TTL + " ms, cache at startup " + cacheWas);
            }

            void nextMode(long now) {
                mode++; modeTick = 0; modeT0 = now; ns = 0; maxNs = 0;
                AfkTasks.Runner.TOLL_CACHE = mode == 1 || mode == 3;
                AfkTasks.Runner.TOLL_MEMO.clear();
                if (mode == 2) NavPlanner.clearCaches();
                c0 = AfkTasks.Runner.TOLL_CALLS.get(); p0 = AfkTasks.Runner.TOLL_PLANS.get(); h0 = AfkTasks.Runner.TOLL_HITS.get();
                log(MODES[mode] + " start");
            }

            void endMode(long now) {
                long calls = AfkTasks.Runner.TOLL_CALLS.get() - c0, plans = AfkTasks.Runner.TOLL_PLANS.get() - p0, hits = AfkTasks.Runner.TOLL_HITS.get() - h0;
                long span = Math.max(1, now - modeT0);
                boolean on = mode == 1 || mode == 3;
                long cap = on ? (long) bs.size() * (span / Math.max(1, AfkTasks.Runner.TOLL_TTL) + 2) : calls;
                boolean ok = calls == (long) bs.size() * TICKS && (on ? plans <= cap && plans + hits == calls : plans == calls && hits == 0);
                if (!ok) fails++;
                log("RESULT " + MODES[mode] + ": calls " + calls + ", plans " + plans + ", hits " + hits + (on ? " (cap " + cap + ")" : " (expect plans = calls)")
                        + ", total " + String.format(Locale.ROOT, "%.1f", ns / 1e6) + " ms, max " + String.format(Locale.ROOT, "%.2f", maxNs / 1e6) + " ms, mean "
                        + String.format(Locale.ROOT, "%.3f", calls == 0 ? 0 : ns / 1e6 / calls) + " ms/call, " + span / 1000 + " s -> " + (ok ? "PASS" : "FAIL"));
            }

            void forgetCheck() {
                AfkTasks.Runner.TOLL_CACHE = true; AfkTasks.Runner.TOLL_MEMO.clear();
                B b = bs.get(0);
                AfkTasks.Runner.tollStuck(b.bot, b.to);
                long p1 = AfkTasks.Runner.TOLL_PLANS.get();
                AfkTasks.Runner.tollStuck(b.bot, b.to); // kept
                long p2 = AfkTasks.Runner.TOLL_PLANS.get();
                NavWalker.forget(b.bot);
                AfkTasks.Runner.tollStuck(b.bot, b.to); // planned again
                long p3 = AfkTasks.Runner.TOLL_PLANS.get();
                boolean ok = p2 == p1 && p3 == p2 + 1;
                if (!ok) fails++;
                log("RESULT FORGET: second call planned " + (p2 - p1) + " (expect 0), after NavWalker.forget planned " + (p3 - p2) + " (expect 1) -> " + (ok ? "PASS" : "FAIL"));
                AfkTasks.Runner.TOLL_CACHE = cacheWas;
            }

            void finish() {
                for (B b : bs) {
                    Boolean first = b.answer[0]; boolean same = !b.mixed;
                    for (Boolean a : b.answer) if (a == null || !a.equals(first)) same = false;
                    boolean expectOk = !b.knownExpect || first != null && first == b.expectStuck;
                    boolean ok = same && expectOk && b.tollNot1 == 0;
                    if (!ok) fails++;
                    log("RESULT BOT " + b.name + ": stuck " + Arrays.toString(b.answer) + " by mode" + (b.knownExpect ? " (expect " + b.expectStuck + ")" : " (info: no-route case, expect the same in every mode)")
                            + ", tollHop not -1 on " + b.tollNot1 + " calls, moved " + b.moved + " -> " + (ok ? "PASS" : "FAIL"));
                }
                log("RESULT summary: " + (fails == 0 ? "PASS" : "FAIL (" + fails + ")"));
                log("DONE");
            }
        });
    }
}
