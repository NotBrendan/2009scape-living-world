package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.*;

/**
 * Trial only (-Dtest.b21b=true; flags: -Dnav=true -Dsmartbots.count=0; run with SEED=live for live's grid): batch 21 B2 and
 * B6, the desert past the Shantay Pass and the void squares.
 *  REGIONS  the mainland, the desert (3268,3052) and the Wilderness (3105,3554) are three nav regions (void tiles no longer
 *           join them; on the harness grid the Wilderness is unread and has no region at all).
 *  VOID     no plan from Ardougne (2654,3284) to the Ugthanki (3268,3052), none from Edgeville (3094,3492) to Battle Runes
 *           (3105,3554): the old plans went through void squares (Yanille, west of the Gnome Stronghold).
 *  PASS     passVia: Al Kharid -> desert = the pass's north tile, desert -> Lumbridge = its south tile, Lumbridge -> Al Kharid
 *           none; passJoins(mainland, desert).
 *  EXITS    bridgeVia from Al Kharid (3290,3170) to 3268,3052 is not the toll gate (3270,3227).
 *  ISLAND   Preflight.island from Lumbridge to 3268,3052 is null (the pass is the way in).
 *  WILD     B6: Preflight refuses the Wilderness shops (Battle Runes, Sam's, Simon's) from Edgeville, not a goal south of the
 *           ditch, and Preflight.wilderness is null for a bot already past it.
 *  WALK     bots walk with NavWalker plus passVia/passHop (the shop walk's helpers): Al Kharid -> Ugthanki, Ugthanki -> Al
 *           Kharid bank, Lumbridge -> Ugthanki; 100 gp each, the south crossing costs 5 gp. Up to 8 minutes.
 * Ends with RESULT lines and DONE.
 */
public final class TestB21B implements StartupListener {
    static void log(String s) { System.out.println("[TestB21B] " + s); }

    static final Location MAIN = Location.create(3222, 3218, 0), UGTH = Location.create(3268, 3052, 0), BATTLE_RUNES = Location.create(3105, 3554, 0);
    static final Location ARDY = Location.create(2654, 3284, 0), EDGE = Location.create(3094, 3492, 0), ALK = Location.create(3290, 3170, 0), ALK_BANK = Location.create(3270, 3167, 0);

    static final class W { String name; AIPlayer bot; Location to; long arrivedT, coins0; int noRoute, hops; final List<String> trail = new ArrayList<>(); }

    static int reg(Location l) { Location s = NavPlanner.standNear(l); return s == null ? 0 : NavPlanner.regionAt(s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b21b")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, fails; long t0; final List<W> ws = new ArrayList<>();
            @Override public boolean pulse() {
                try {
                    t++;
                    long now = System.currentTimeMillis();
                    if (phase == 0 && t == 1200) { log("RESULT planner never came up"); log("DONE"); return true; }
                    if (phase == 0 && t >= 100 && NavPlanner.built) { phase = 1; unit(); if (!NavWalker.ON) { log("RESULT WALK skipped: planner off (NavWalker-only walks)"); log("RESULT summary: " + (fails == 0 ? "PASS" : "FAIL (" + fails + ")") + " (checks only, planner off)"); log("DONE"); return true; } spawn(); t0 = now; return false; } // P38 L10
                    if (phase == 1 && now - t0 > 3_000) { phase = 2; t0 = now; log("walks start"); }
                    if (phase != 2) return false;
                    boolean all = true;
                    for (W w : ws) {
                        Location a = w.bot.getLocation();
                        if (w.arrivedT == 0 && a.getDistance(w.to) <= 4) { w.arrivedT = now; log(w.name + " arrived after " + (now - t0) / 1000 + " s"); }
                        if (w.arrivedT != 0) continue;
                        all = false;
                        if (t % 3 != 0 || w.bot.getWalkingQueue().isMoving() || w.bot.getProperties().getTeleportLocation() != null) continue;
                        if (t % 60 == 0 && w.trail.size() < 30) w.trail.add(a.getX() + "," + a.getY());
                        int ph = AfkTasks.Runner.passHop(w.bot, w.to, false);
                        if (ph != 0) { w.hops++; log(w.name + " pass hop " + ph + " at " + a); continue; }
                        Location pv = AfkTasks.Runner.passVia(a, w.to), target = pv != null ? pv : w.to;
                        if (AfkTasks.Runner.tollHop(w.bot, target) != 0) { log(w.name + " toll hop at " + a); continue; } // the shop walk's order: the toll gate, then the pass
                        Location h = NavWalker.step(w.bot, target, true);
                        if (h != null) NavWalker.walkTo(w.bot, h); else w.noRoute++;
                    }
                    if (all || now - t0 > 480_000L) { finish(); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            void check(String name, boolean ok, String detail) { log("RESULT " + name + ": " + detail + " -> " + (ok ? "PASS" : "FAIL")); if (!ok) fails++; }

            void unit() {
                int rm = reg(MAIN), rd = reg(UGTH), rw = reg(BATTLE_RUNES);
                check("REGIONS", rm != 0 && rd != 0 && rm != rd && rw != rm, "mainland " + rm + " (" + NavPlanner.regionSize(rm) + " tiles), desert " + rd + " (" + NavPlanner.regionSize(rd) + "), Wilderness " + rw + " (" + NavPlanner.regionSize(rw) + "); void open " + NavGrid.VOID_OPEN);
                NavPlanner.Route a = NavPlanner.plan(ARDY, UGTH, 1, 0, true), b = NavPlanner.plan(EDGE, BATTLE_RUNES, 1, 0, true);
                check("VOID", a == null && b == null, "Ardougne -> Ugthanki " + (a == null ? "no plan" : a.corridor.length + " components, first hop " + a.hops.get(0)) + "; Edgeville -> Battle Runes " + (b == null ? "no plan" : b.corridor.length + " components, first hop " + b.hops.get(0)) + " (expect no plan for both)");
                Location v1 = AfkTasks.Runner.passVia(ALK, UGTH), v2 = AfkTasks.Runner.passVia(UGTH, MAIN), v3 = AfkTasks.Runner.passVia(MAIN, ALK_BANK);
                boolean j = AfkTasks.Runner.passJoins(rm, rd);
                check("PASS", AfkTasks.Runner.PASS_N.equals(v1) && AfkTasks.Runner.PASS_S.equals(v2) && v3 == null && j, "Al Kharid -> desert " + v1 + ", desert -> Lumbridge " + v2 + ", Lumbridge -> Al Kharid " + v3 + ", passJoins " + j + " (pass regions N " + NavPlanner.regionAt(AfkTasks.Runner.PASS_N) + ", S " + NavPlanner.regionAt(AfkTasks.Runner.PASS_S) + ")");
                Location bv = AfkTasks.Runner.bridgeVia(ALK, UGTH);
                check("EXITS", bv == null || bv.getDistance(Location.create(3270, 3227, 0)) > 3, "bridgeVia Al Kharid -> Ugthanki " + bv + " (expect not the toll gate)");
                String isl = Preflight.island(MAIN, UGTH);
                check("ISLAND", isl == null, "Lumbridge -> Ugthanki '" + isl + "' (expect null)");
                // B6: the Wilderness shops (Battle Runes, Sam's and Simon's cape shops) are refused for a bot south of the ditch, not for one past it
                AIPlayer e = TestCR1.bot(EDGE, 60, 60, 60, 60); Location in = Location.create(3090, 3560, 0); // a bot made this tick still stands at its spawn; the rule reads the start tile
                String r1 = Preflight.refuse(e, BATTLE_RUNES), r2 = Preflight.refuse(e, Location.create(3074, 3609, 0)), r3 = Preflight.refuse(e, Location.create(3234, 3633, 0)), r4 = Preflight.wilderness(in, BATTLE_RUNES), r5 = Preflight.refuse(e, Location.create(3094, 3470, 0));
                check("WILD", r1 != null && r2 != null && r3 != null && r4 == null && r5 == null, "from Edgeville: Battle Runes '" + r1 + "', Sam's '" + r2 + "', Simon's '" + r3 + "'; from inside the Wilderness '" + r4 + "'; Edgeville south of the ditch '" + r5 + "' (expect three refusals, then null, null)");
            }

            void spawn() {
                w("W1 alkharid->ugthanki", ALK, UGTH);
                w("W2 ugthanki->alkharid-bank", UGTH, ALK_BANK);
                w("W3 lumbridge->ugthanki", MAIN, UGTH);
                log("bots: walkers " + ws.size());
            }

            void w(String name, Location from, Location to) {
                W w = new W(); w.name = name; w.to = to; w.bot = TestCR1.bot(from, 40, 40, 40, 40); Ledger.Wallet.give(w.bot, 100); w.coins0 = Ledger.Wallet.coins(w.bot); ws.add(w);
            }

            void finish() {
                int arrived = 0;
                for (W w : ws) {
                    long paid = w.coins0 - Ledger.Wallet.coins(w.bot);
                    boolean ok = w.arrivedT != 0 && w.hops == 1 && paid <= 15; // at most the toll (10) and the pass (5) once each
                    if (w.arrivedT != 0) arrived++;
                    if (!ok) fails++;
                    log("RESULT WALK " + w.name + ": " + (w.arrivedT != 0 ? "arrived in " + (w.arrivedT - t0) / 1000 + " s" : "not arrived, at " + w.bot.getLocation()) + ", pass hops " + w.hops + ", paid " + paid + " gp (expect at most 15), no-route steps " + w.noRoute + ", trail " + w.trail + (ok ? " -> PASS" : " -> FAIL"));
                }
                log("RESULT summary: walkers " + arrived + "/" + ws.size() + " arrived; " + (fails == 0 ? "PASS" : "FAIL"));
                log("DONE");
            }
        });
    }
}
