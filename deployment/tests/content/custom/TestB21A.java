package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.*;

/**
 * Trial only (-Dtest.b21a=true; flags: -Dnav=true -Dsmartbots.count=0): batch 21 Workstream A, the Lumbridge east bank and
 * the Al Kharid toll gate.
 *  UNIT  toll links exist; Lumbridge castle -> south of Varrock never uses one (same side) even for a payer; the east bank ->
 *        Al Kharid store uses one for a payer and none for a non-payer; both corridor lengths are logged and the gate must save
 *        more than nav.tollCost there (else the payer check itself fails).
 *  WALK  12 bots: starts 3258,3229 and 3264,3241 (the dead-tree goblin patch), 0 gp (planner-only walker, never pays) and
 *        10 gp (pays at the gate like the trip runner), to south of Varrock (3226,3370), Draynor (3093,3245) and the Al Kharid
 *        store (3315,3180), up to 8 minutes. FAIL when a bot enters one tile 5 or more times in 5 minutes (revisits > 3) or
 *        ends idle inside the box x 3238-3267, y 3226-3260 without arriving, or when a 10 gp bot bound for the Al Kharid store
 *        never pays at the gate (it went round the fence instead). Review fix 2: 2 more bots with 5 gp standing at the gate
 *        (3266,3227 -> Al Kharid store, 3270,3227 -> south of Varrock) run tollHop like the walkers; FAIL if one gives up,
 *        pays or does not arrive (they must walk round the fence's north end).
 * Ends with RESULT lines and DONE.
 */
public final class TestB21A implements StartupListener {
    static void log(String s) { System.out.println("[TestB21A] " + s); }

    static final Location[] STARTS = {Location.create(3258, 3229, 0), Location.create(3264, 3241, 0)};
    static final Location VARROCK_S = Location.create(3226, 3370, 0), DRAYNOR = Location.create(3093, 3245, 0), ALK = Location.create(3315, 3180, 0);
    static final Location[] DESTS = {VARROCK_S, DRAYNOR, ALK};
    static final String[] DEST_N = {"varrock-s", "draynor", "alkharid"};

    static final class W {
        String name; AIPlayer bot; Location from, to; boolean payer; long arrivedT; int maxVisits; String maxAt = "-";
        Map<Integer, ArrayDeque<Long>> visits = new HashMap<>(); int lastTile = -1; long lastMove; int noRoute, paid; Location lastAt; boolean broke, gaveUp; // review fix 2: broke = 5 gp, starts at the gate
    }

    static boolean usesToll(NavPlanner.Route r) {
        if (r == null || r.corridor == null) return false;
        for (int i = 0; i + 1 < r.corridor.length; i++) if (NavPlanner.isToll(r.corridor[i], r.corridor[i + 1])) return true;
        return false;
    }

    static boolean inBox(Location l) { return l.getZ() == 0 && l.getX() >= 3238 && l.getX() <= 3267 && l.getY() >= 3226 && l.getY() <= 3260; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b21a")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase; long t0; final List<W> ws = new ArrayList<>(); int fails;
            @Override public boolean pulse() {
                try {
                    t++;
                    long now = System.currentTimeMillis();
                    if (phase == 0 && t == 1200) { log("RESULT planner never came up"); log("DONE"); return true; }
                    if (phase == 0 && t >= 100 && NavPlanner.built) { phase = 1; unit(); if (!NavWalker.ON) { offCheck(); finishUnitOnly(); return true; } spawn(); t0 = now; return false; } // P38 L10: planner off = kill-switch check + UNIT only (the 14 walks are NavWalker-only)
                    if (phase == 1 && now - t0 > 3_000) { phase = 2; t0 = now; for (W w : ws) { w.lastMove = now; w.lastAt = w.bot.getLocation(); } log("walks start"); }
                    if (phase != 2) return false;
                    boolean all = true;
                    for (W w : ws) {
                        Location a = w.bot.getLocation();
                        if (w.arrivedT == 0 && a.getDistance(w.to) <= 4) { w.arrivedT = now; log(w.name + " arrived after " + (now - t0) / 1000 + " s"); }
                        if (w.arrivedT != 0) continue;
                        all = false;
                        int tile = (a.getX() << 15) | a.getY();
                        if (tile != w.lastTile) {
                            w.lastTile = tile; w.lastMove = now;
                            ArrayDeque<Long> q = w.visits.computeIfAbsent(tile, k -> new ArrayDeque<>());
                            q.addLast(now); while (!q.isEmpty() && now - q.peekFirst() > 300_000L) q.pollFirst();
                            if (q.size() > w.maxVisits) { w.maxVisits = q.size(); w.maxAt = a.getX() + "," + a.getY(); }
                        }
                        if (t % 3 != 0 || w.bot.getWalkingQueue().isMoving()) continue;
                        if (w.gaveUp) continue;
                        if (w.payer || w.broke) { long c0 = Ledger.Wallet.coins(w.bot); int r = AfkTasks.Runner.tollHop(w.bot, w.to); if (r > 0) { w.paid += (int) (c0 - Ledger.Wallet.coins(w.bot)); continue; }
                            if (r < 0 && AfkTasks.Runner.tollStuck(w.bot, w.to)) { w.gaveUp = true; log(w.name + " gave up at the gate (no toll-free route)"); continue; } } // review fix 2: the walkers' rule
                        Location h = NavWalker.step(w.bot, w.to, w.payer || w.broke);
                        if (h != null) NavWalker.walkTo(w.bot, h); else w.noRoute++;
                    }
                    if (all || now - t0 > 480_000L) { finish(now); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            void unit() {
                log("RESULT UNIT toll links " + NavPlanner.tollA.length + " (expect >= 1)");
                NavPlanner.Route a = NavPlanner.plan(Location.create(3224, 3217, 0), VARROCK_S, 1, 0, true);
                log("RESULT UNIT lumbridge castle -> varrock-s, payer: route " + (a != null) + ", through the toll " + usesToll(a) + " (expect route true, toll false)");
                NavPlanner.Route b = NavPlanner.plan(STARTS[0], ALK, 1, 0, true), c = NavPlanner.plan(STARTS[0], ALK, 1, 0, false);
                log("RESULT UNIT east bank -> alkharid: payer route " + (b != null) + " toll " + usesToll(b) + "; non-payer route " + (c != null) + " toll " + usesToll(c) + " (expect payer toll true, non-payer toll false); needsToll " + NavPlanner.needsToll(STARTS[0], ALK));
                // batch 21 A fix: both corridor lengths, east bank -> Al Kharid store (component steps, the toll link without its extra cost)
                Location[] e = NavPlanner.ends(STARTS[0], ALK);
                int ca = e == null ? 0 : NavPlanner.compAt(e[0].getX(), e[0].getY()), cb = e == null ? 0 : NavPlanner.compAt(e[1].getX(), e[1].getY());
                int[] withGate = NavPlanner.corridor(ca, cb, 0, true), round = NavPlanner.corridor(ca, cb, 0, false);
                int lw = corLen(withGate), lr = corLen(round);
                log("RESULT UNIT corridor lengths east bank -> alkharid: through the gate " + lw + " tiles (+" + NavPlanner.TOLL_COST + " toll cost), round the fence " + lr + " tiles; saving " + (lw < 0 || lr < 0 ? "?" : String.valueOf(lr - lw))
                        + "; regionFree start " + NavPlanner.regionFreeAt(e == null ? null : e[0]) + ", goal " + NavPlanner.regionFreeAt(e == null ? null : e[1]) + "; payer route tiles " + (b == null ? -1 : b.tiles) + ", non-payer " + (c == null ? -1 : c.tiles));
                boolean payerToll = lw >= 0 && lr >= 0 && lr - lw > NavPlanner.TOLL_COST; // the gate saves more than its cost: a payer must take it
                if (a == null || usesToll(a) || usesToll(c) || b == null || c == null || !payerToll || !usesToll(b)) fails++;
                log("RESULT UNIT toll choice: payer through the gate " + usesToll(b) + " (expect " + payerToll + ", and true here), non-payer " + usesToll(c) + " (expect false) -> " + (fails == 0 ? "PASS" : "FAIL"));
                loopUnit();
            }

            /** review fix 1: Failures.loopStat/loopRescue on made-up tile sequences (2 s a tile), no bots. */
            void loopUnit() {
                int rounds = Failures.LOOP_VISITS + 1; // the loop count the detector needs, plus one (the first entry of a tile is visit 1)
                List<int[]> dodge1 = new ArrayList<>(), dodge2 = new ArrayList<>(), bank = new ArrayList<>(), sq4 = new ArrayList<>(), sq3 = new ArrayList<>();
                for (int i = 0; i < 30; i++) { dodge1.add(new int[]{3200 + i, 3300}); dodge2.add(new int[]{3200 + i, 3300}); }
                for (int r = 0; r < 6; r++) { dodge1.add(new int[]{3230, 3300}); dodge1.add(new int[]{3230, 3301}); } // A,B,A,B... sidestep (span 1)
                for (int r = 0; r < 6; r++) for (int d : new int[]{0, 1, 2, 1}) dodge2.add(new int[]{3230 + d, 3300}); // 2-tile dodge (span 2)
                for (int i = 0; i < 30; i++) { dodge1.add(new int[]{3231 + i, 3300}); dodge2.add(new int[]{3233 + i, 3300}); }
                for (int r = 0; r < rounds; r++) { for (int y = 3230; y < 3242; y++) bank.add(new int[]{y < 3236 ? 3263 : 3265, y}); for (int y = 3242; y > 3230; y--) bank.add(new int[]{y < 3236 ? 3263 : 3265, y}); } // the east-bank loop (span 12)
                for (int r = 0; r < rounds; r++) { square(sq4, 3200, 3300, Failures.LOOP_SPAN); square(sq3, 3200, 3300, Failures.LOOP_SPAN - 1); } // a square of side n has span n: one at the configured span must fire, one tile less must not
                boolean d1 = fires(dodge1), d2 = fires(dodge2), bk = fires(bank), s4 = fires(sq4), s3 = fires(sq3);
                boolean ok = !d1 && !d2 && bk && s4 && !s3;
                if (!ok) fails++;
                log("RESULT UNIT loop span (min " + Failures.LOOP_SPAN + "): sidestep " + d1 + ", 2-tile dodge " + d2 + ", " + (Failures.LOOP_SPAN - 1) + "-tile square " + s3 + " (expect false); east-bank loop " + bk + ", " + Failures.LOOP_SPAN + "-tile square " + s4 + " (expect true) -> " + (ok ? "PASS" : "FAIL"));
                tripUnit();
            }

            /**
             * batch 21 D (live loop review): visits count within one trip only (Failures.loopSince). A {x, y, 1} entry starts a
             * new trip there (40 s of fighting before it, no ring entries, as the sampler only records travelling states).
             *  fight returns: LOOP_VISITS (6, B26 fix 5) short TO_SPOT trips from kills 7-8 tiles out (y 3225-3227; live kills at the edge y 3218-3220 take too long for 6 in 5 minutes) back to the Lumbridge goblins spot
             *    (3250,3235), all ending up x 3250 to 3250,3233: the old rule fires (the live false positive), per trip it must not.
             *  east-bank loop and an exit-rule ping-pong (3245,3228 <-> 3245,3240, span 12), each one trip: must still fire.
             *  bank commute: 3 round trips on one road, a trip each way: must not fire per trip.
             */
            void tripUnit() {
                int rounds = Failures.LOOP_VISITS + 1;
                List<int[]> fightLegs = new ArrayList<>(), bank1 = new ArrayList<>(), exit1 = new ArrayList<>(), commute = new ArrayList<>();
                int[][] kills = {{3244, 3226}, {3256, 3226}, {3250, 3225}, {3247, 3227}, {3253, 3225}}; // 7-8 tiles out: a trip is 40 s + 2 s a tile, so LOOP_VISITS of them still fit the 5-minute window
                for (int i = 0; i < Failures.LOOP_VISITS; i++) leg(fightLegs, kills[i % kills.length][0], kills[i % kills.length][1]); // LOOP_VISITS returns to the spot
                bank1.add(new int[]{3263, 3229, 1}); // one trip (to Varrock), then the east-bank loop
                for (int r = 0; r < rounds; r++) { for (int y = 3230; y < 3242; y++) bank1.add(new int[]{y < 3236 ? 3263 : 3265, y}); for (int y = 3242; y > 3230; y--) bank1.add(new int[]{y < 3236 ? 3263 : 3265, y}); }
                exit1.add(new int[]{3245, 3227, 1});
                for (int r = 0; r < rounds; r++) { for (int y = 3228; y < 3240; y++) exit1.add(new int[]{3245, y}); for (int y = 3240; y > 3228; y--) exit1.add(new int[]{3245, y}); }
                for (int r = 0; r < 3; r++) { commute.add(new int[]{3240, 3230, 1}); for (int x = 3241; x <= 3260; x++) commute.add(new int[]{x, 3230}); commute.add(new int[]{3260, 3231, 1}); for (int x = 3259; x >= 3240; x--) commute.add(new int[]{x, 3230}); }
                boolean fOld = firesTrips(fightLegs, false), fNew = firesTrips(fightLegs, true), bNew = firesTrips(bank1, true), eNew = firesTrips(exit1, true), cOld = firesTrips(commute, false), cNew = firesTrips(commute, true);
                Failures.Trace tt = new Failures.Trace(null, "b21d-unit"); int noTrip = Failures.loopSince(tt); tt.tripT = Failures.BOOT + 61_000L; int since = Failures.loopSince(tt);
                boolean wired = noTrip == Integer.MIN_VALUE && since == (Failures.LOOP_TRIP ? 61 : Integer.MIN_VALUE); // the kill switch reaches loopCheck's window
                boolean ok = fOld && !fNew && bNew && eNew && !cNew && wired;
                if (!ok) fails++;
                log("RESULT UNIT loopSince: no trip " + (noTrip == Integer.MIN_VALUE ? "none" : String.valueOf(noTrip)) + ", trip at boot+61 s " + (since == Integer.MIN_VALUE ? "none" : String.valueOf(since)) + " (expect none, " + (Failures.LOOP_TRIP ? "61" : "none") + ") -> " + (wired ? "PASS" : "FAIL"));
                log("RESULT UNIT loop per trip (loop.trip " + Failures.LOOP_TRIP + "): fight returns old rule " + fOld + " (expect true: the live false positive), per trip " + fNew + " (expect false); east-bank loop " + bNew + ", exit-rule ping-pong " + eNew + " (expect true); bank commute old " + cOld + ", per trip " + cNew + " (expect false) -> " + (ok ? "PASS" : "FAIL"));
            }

            /** One fight-return trip: from the kill (kx, ky) diagonally to x 3250, then up to 3250,3233 (2 from the spot). */
            void leg(List<int[]> out, int kx, int ky) {
                out.add(new int[]{kx, ky, 1});
                int x = kx, y = ky;
                while (x != 3250 || y < 3233) { if (x != 3250) x += Integer.signum(3250 - x); if (y < 3233) y++; out.add(new int[]{x, y}); }
            }

            /** As fires(), with trip starts ({x, y, 1}): perTrip passes the trip's start second to loopStat as loopCheck does. */
            boolean firesTrips(List<int[]> seq, boolean perTrip) {
                int[] vt = new int[128], vs = new int[128]; int vh = 0, s = 1000, last = 0, since = Integer.MIN_VALUE; boolean hit = false;
                for (int[] p : seq) {
                    if (p.length > 2 && p[2] == 1) { s += 40; if (perTrip) since = s + 1; }
                    int pk = Failures.pack(Location.create(p[0], p[1], 0));
                    if (pk == last) continue;
                    last = pk; s += 2;
                    long st = Failures.loopStat(vt, vs, vh, pk, s, since);
                    vt[vh] = pk; vs[vh] = s; vh = (vh + 1) & 127;
                    if (Failures.loopRescue((int) (st >>> 32), (int) st)) hit = true;
                }
                return hit;
            }

            void square(List<int[]> out, int x0, int y0, int n) {
                for (int i = 0; i < n; i++) out.add(new int[]{x0 + i, y0});
                for (int i = 0; i < n; i++) out.add(new int[]{x0 + n, y0 + i});
                for (int i = 0; i < n; i++) out.add(new int[]{x0 + n - i, y0 + n});
                for (int i = 0; i < n; i++) out.add(new int[]{x0, y0 + n - i});
            }

            /** Replays the detector's ring exactly as loopCheck does; true if any step would rescue. */
            boolean fires(List<int[]> seq) {
                int[] vt = new int[128], vs = new int[128]; int vh = 0, s = 1000, last = 0; boolean hit = false;
                for (int[] p : seq) {
                    int pk = Failures.pack(Location.create(p[0], p[1], 0));
                    if (pk == last) continue;
                    last = pk; s += 2;
                    long st = Failures.loopStat(vt, vs, vh, pk, s);
                    vt[vh] = pk; vs[vh] = s; vh = (vh + 1) & 127;
                    if (Failures.loopRescue((int) (st >>> 32), (int) st)) hit = true;
                }
                return hit;
            }

            int corLen(int[] c) {
                if (c == null) return -1;
                int s = 0;
                for (int i = 0; i + 1 < c.length; i++) s += Math.max(1, Math.abs(NavPlanner.cx[c[i]] - NavPlanner.cx[c[i + 1]]) + Math.abs(NavPlanner.cy[c[i]] - NavPlanner.cy[c[i + 1]]));
                return s;
            }

            void spawn() {
                int i = 0;
                for (Location s : STARTS) for (int d = 0; d < DESTS.length; d++) for (int coins : new int[]{0, 10}) {
                    W w = new W(); w.from = s; w.to = DESTS[d]; w.payer = coins > 0;
                    w.bot = TestCR1.bot(s, 10, 1, 1, 1);
                    if (coins > 0) Ledger.Wallet.give(w.bot, coins);
                    w.name = "W" + (++i) + "[" + s.getX() + "," + s.getY() + "->" + DEST_N[d] + "," + coins + "gp]";
                    ws.add(w);
                }
                // review fix 2: broke (5 gp) payer-style walkers standing at the gate, each side: they must walk round the fence's north end, not give up
                Location[][] gate = {{Location.create(3266, 3227, 0), ALK}, {Location.create(3270, 3227, 0), VARROCK_S}};
                for (Location[] g : gate) {
                    W w = new W(); w.from = g[0]; w.to = g[1]; w.broke = true;
                    w.bot = TestCR1.bot(g[0], 10, 1, 1, 1); Ledger.Wallet.give(w.bot, 5);
                    w.name = "W" + (++i) + "[" + g[0].getX() + "," + g[0].getY() + "->" + (g[1] == ALK ? "alkharid" : "varrock-s") + ",5gp broke]";
                    ws.add(w);
                }
                log("bots: " + ws.size());
            }

            /** P38 L10: planner off (-Dnav=false): NavWalker.step must hand back no hop and tollStuck must give up at once. */
            void offCheck() {
                AIPlayer ob = TestCR1.bot(STARTS[0], 10, 1, 1, 1);
                Location h = NavWalker.step(ob, ALK, true);
                boolean stuck = AfkTasks.Runner.tollStuck(ob, ALK);
                if (h != null || !stuck) fails++;
                log("RESULT OFF kill switch: no planner hop, tollStuck true (expect null, true): hop " + h + ", tollStuck " + stuck + " -> " + (h == null && stuck ? "PASS" : "FAIL"));
            }

            void finishUnitOnly() {
                log("RESULT WALK skipped: planner off (the 14 walks are NavWalker-only)");
                log("RESULT summary: UNIT only, planner off; " + (fails == 0 ? "PASS" : "FAIL"));
                log("DONE");
            }

            void finish(long now) {
                int arrived = 0, looped = 0, idleBox = 0, tollSkipped = 0;
                for (W w : ws) {
                    Location a = w.bot.getLocation();
                    boolean loop = w.maxVisits >= 5, idle = w.arrivedT == 0 && inBox(a);
                    boolean unpaid = w.payer && w.to == ALK && w.paid < 10; // batch 21 A fix: a 10 gp bot bound for Al Kharid goes through the gate (pays), not round
                    if (unpaid) { fails++; tollSkipped++; }
                    boolean brokeBad = w.broke && (w.gaveUp || w.paid > 0 || w.arrivedT == 0); // review fix 2: round the fence and there, never a give-up
                    if (brokeBad) fails++;
                    if (w.arrivedT != 0) arrived++;
                    if (loop) looped++;
                    if (idle) idleBox++;
                    log("RESULT WALK " + w.name + ": " + (w.arrivedT != 0 ? "arrived in " + (w.arrivedT - t0) / 1000 + " s" : "not arrived, at " + a.getX() + "," + a.getY() + (idle ? " (idle in the box)" : ""))
                            + ", max visits to one tile in 5 min " + w.maxVisits + " at " + w.maxAt + ", paid " + w.paid + " gp" + (unpaid ? " (expected 10: a payer to Al Kharid takes the gate)" : "") + (w.broke ? ", gave up " + w.gaveUp + " (expect false, and arrived)" : "") + ", no-route steps " + w.noRoute + (loop || idle || unpaid || brokeBad ? " -> FAIL" : " -> PASS"));
                }
                if (looped > 0 || idleBox > 0) fails++;
                log("RESULT summary: " + arrived + "/" + ws.size() + " arrived, " + looped + " looped, " + idleBox + " idle in the box, " + tollSkipped + " payers to Al Kharid that did not pay the gate; loop detector records " + Watchdog.loops + "; " + (fails == 0 ? "PASS" : "FAIL"));
                log("DONE");
            }
        });
    }
}
