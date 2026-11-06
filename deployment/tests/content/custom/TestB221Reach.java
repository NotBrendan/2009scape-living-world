package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b221reach=true -Dsmartbots.count=0): batch 22-1 tasks 2 and 3, no player needed. Pure checks of
 *   R1: Watchdog.nearestBank from the Lumbridge General Store (3217,3241) is not Al Kharid's bank (behind the toll gate); the
 *       same bank is still reachable from the desert (3170,3030), whose way is the Shantay Pass.
 *   R2: from the Karamja General Store (2906,3145) the nearest bank is not inside a gate box (Shilo's quest box).
 *   R3: from the Fishing Guild shop (2597,3401) the nearest bank is not inside a gate box (the guild's).
 *   R4: bankReachable(sea): Ardougne south's bank is skipped from the Shrimp and Parrot (2790,3189) when noSea, kept without;
 *       a rescue (noSea) there still gets a bank (the nearest across the water, never null).
 *   R5: -Dbank.reach=false run: nearestBank gives the plain nearest (Al Kharid from Lumbridge) = the old behaviour.
 *   A: Artisans.teleDue with the walkStart reset after each paid teleport: a 20-minute walk that never arrives pays at most 14
 *      teleports (one per 90 s); without the reset (the batch 22 code) it paid one per tick.
 * Prints RESULT ..., summary, DONE.
 */
public final class TestB221Reach implements StartupListener {
    static void log(String s) { System.out.println("[TestB221Reach] " + s); }
    static int fails;
    static void result(String name, boolean pass, String what) { if (!pass) fails++; log("RESULT " + name + " " + (pass ? "PASS" : "FAIL") + ": " + what); }
    static Location at(int x, int y) { return Location.create(x, y, 0); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b221reach")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 60) return false;
                try { TestB221Reach.run(); } catch (Throwable e) { fails++; log("error " + e); e.printStackTrace(System.out); }
                log("summary: " + (fails == 0 ? "PASS" : fails + " FAIL")); log("DONE");
                return true;
            }
        });
    }

    static void run() {
        boolean reach = Watchdog.BANK_REACH;
        Location lum = Watchdog.nearestBank(at(3217, 3241), null, true);
        if (!reach) { result("R5", lum != null && lum.getX() == 3270, "reach off: nearest from Lumbridge " + lum); }
        else {
            result("R1", lum != null && lum.getX() != 3270 && Watchdog.bankReachable(at(3170, 3030), at(3270, 3167), null, true), "Lumbridge store -> " + lum + "; Al Kharid bank from the desert reachable " + Watchdog.bankReachable(at(3170, 3030), at(3270, 3167), null, true));
            Location kar = Watchdog.nearestBank(at(2906, 3145), null, true), fg = Watchdog.nearestBank(at(2597, 3401), null, true);
            result("R2", kar != null && Gates.at(kar).isEmpty(), "Karamja General Store -> " + kar);
            result("R3", fg != null && Gates.at(fg).isEmpty(), "Fishing Guild shop -> " + fg);
            Location sp = at(2790, 3189), ardS = at(2654, 3284);
            Location resc = Watchdog.nearestBank(sp, null, true);
            result("R4", !Watchdog.bankReachable(sp, ardS, null, true) && Watchdog.bankReachable(sp, ardS, null, false) && resc != null, "sea skip " + !Watchdog.bankReachable(sp, ardS, null, true) + ", kept without " + Watchdog.bankReachable(sp, ardS, null, false) + ", rescue gets " + resc);
        }
        long walkStart = 0; int pays = 0, naive = 0;
        for (long now = 1_000; now <= 20 * 60_000L; now += 1_000) {
            if (Artisans.teleDue(now, walkStart)) { pays++; walkStart = now; }
            if (Artisans.teleDue(now, 0)) naive++;
        }
        result("A", pays >= 1 && pays <= 14 && naive > 1000, "20 min walk: " + pays + " paid teleports with the reset (limit 14), " + naive + " without it");
    }
}
