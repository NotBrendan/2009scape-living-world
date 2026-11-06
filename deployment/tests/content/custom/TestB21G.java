package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.bots.ScriptAPI;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.*;

/**
 * Trial only (-Dtest.b21g=true; flags: -Dnav=true -Dsmartbots.count=0): batch 21 B1, how under-level bots get into the
 * Warriors' Guild (inside its walls: x 2838-2876, y 3533-3555 less the box tiles reachable from the walkway without a door;
 * attack + strength 130).
 *  BOX     B1b: the gate box (Gates notches) agrees tile by tile with a clipping flood fill from the walkway (2870,3556).
 *          Review fix 3: every guild box is flooded from 4 tiles outside; no street tile may strand an under-level bot
 *          (Preflight.boxStranded), and a walled box (Gate.walled) must match the flood.
 *  PICK    Preflight refuses the Warriors' Guild Food Shop (2840,3554) for a 40/40 bot and allows it for a 70/70 bot.
 *  DOOR    "Open" on the east door 15653 (2877,3546) and the west door 1530 (2837,3549): a 40/40 bot outside stays outside, a
 *          70/70 bot outside gets in (the check sees an entry), a 40/40 bot inside stays inside (the engine checks both ways).
 *  WALK    40/40 bots walk with NavWalker from Burthorpe (2895,3546) to west of the guild and back, up to 4 minutes; FAIL if
 *          one ever stands in the box.
 *  TELE    Spectacle.teleport of a 40/40 bot to the food shop is logged ([Gates] line, gatedLandings + 1), a 70/70 bot's is not;
 *          then Preflight.out on a walk to Burthorpe pays its teleport out and it lands outside the box.
 * Ends with RESULT lines and DONE.
 */
public final class TestB21G implements StartupListener {
    static void log(String s) { System.out.println("[TestB21G] " + s); }

    static final Location SHOP = Location.create(2840, 3554, 0), BURTHORPE = Location.create(2895, 3546, 0);
    static final Location EAST_DOOR = Location.create(2877, 3546, 0), WEST_DOOR = Location.create(2837, 3549, 0);

    /** Box tiles a walker reaches from the walkway (2870,3556) or the ring 4 tiles out without a door, by clipping flags at spawn (before any door opens): outside the walls (batch 21 B1b). */
    static final Set<Integer> OUTSIDE = new HashSet<>();
    static boolean inGuild(Location l) { return l.getZ() == 0 && l.getX() >= 2838 && l.getX() <= 2876 && l.getY() >= 3533 && l.getY() <= 3555 && !OUTSIDE.contains((l.getX() << 16) | l.getY()); }

    static void fillOutside() {
        int bx0 = 2834, by0 = 3529, bx1 = 2880, by1 = 3559; Set<Integer> seen = new HashSet<>(); Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{2870, 3556}); seen.add((2870 << 16) | 3556);
        for (int x = bx0; x <= bx1; x++) for (int y = by0; y <= by1; y++) if ((x == bx0 || x == bx1 || y == by0 || y == by1) && seen.add((x << 16) | y)) q.add(new int[]{x, y}); // review fix 3: from the whole ring too (the south side is reached from outside, not from the walkway)
        int[][] dirs = {{1, 0, 0x12c0180}, {-1, 0, 0x12c0108}, {0, 1, 0x12c0120}, {0, -1, 0x12c0102}};
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int[] d : dirs) {
                int nx = p[0] + d[0], ny = p[1] + d[1];
                if (nx < bx0 || nx > bx1 || ny < by0 || ny > by1 || (RegionManager.getClippingFlag(0, nx, ny) & d[2]) != 0) continue;
                if (seen.add((nx << 16) | ny)) q.add(new int[]{nx, ny});
            }
        }
        OUTSIDE.clear(); OUTSIDE.addAll(seen);
    }

    static AIPlayer bot(Location at, int lvl) { return TestCR1.bot(at, 40, lvl, lvl, 40); }

    static final class D { String name; AIPlayer bot; Location door; boolean expectIn; int opened; }
    static final class W { String name; AIPlayer bot; Location to; boolean everIn; long arrivedT; int noRoute; String inAt = "-"; final LinkedHashSet<String> trail = new LinkedHashSet<>(); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b21g")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, fails, ph; long t0; final List<D> ds = new ArrayList<>(); final List<W> ws = new ArrayList<>();
            AIPlayer tLow, tHigh, sWalk, sIn; int tele0; Location west;
            @Override public boolean pulse() {
                try {
                    t++;
                    long now = System.currentTimeMillis();
                    if (phase == 0 && t == 1200) { log("RESULT planner never came up"); log("DONE"); return true; }
                    if (phase == 0 && t >= 100 && NavPlanner.built) { phase = 1; ph = t; pick(); spawn(); return false; }
                    if (phase == 1 && t == ph + 6) { // landed: open the doors, start the walks, teleport the trace bots
                        for (D d : ds) open(d);
                        tele0 = Spectacle.gatedLandings;
                        Spectacle.teleport(tLow, SHOP); Spectacle.teleport(tHigh, SHOP);
                        phase = 2; ph = t; t0 = now;
                        log("doors opened, walks start, trace bots teleported");
                    }
                    if (phase < 2) return false;
                    if (t == ph + 1) { // Preflight.stranded: the walkway north of the guild is outside, the food shop inside
                        String a = Preflight.stranded(sWalk, BURTHORPE), b = Preflight.stranded(sIn, BURTHORPE);
                        boolean ok = a == null && b != null;
                        log("RESULT STRANDED walkway " + sWalk.getLocation() + ": '" + a + "', by the food shop " + sIn.getLocation() + ": '" + b + "' (expect null, then inside) -> " + (ok ? "PASS" : "FAIL"));
                        if (!ok) fails++;
                    }
                    if (t == ph + 3) log("RESULT TELE trace: gatedLandings +" + (Spectacle.gatedLandings - tele0) + " (expect +1: the 40/40 bot only) -> " + (Spectacle.gatedLandings - tele0 == 1 ? "PASS" : "FAIL"));
                    if (t == ph + 3 && Spectacle.gatedLandings - tele0 != 1) fails++;
                    if (t >= ph + 4 && t < ph + 40 && tLow != null) { // stranded: walk time pre-flight pays the teleport out
                        Preflight.Go g = Preflight.out(tLow, BURTHORPE);
                        if (g != Preflight.Go.BUSY && t > ph + 6) {
                            Location a = tLow.getLocation();
                            log("RESULT TELE out: Preflight.out " + g + ", at " + a + " (expect CLEAR outside the guild) -> " + (g == Preflight.Go.CLEAR && !inGuild(a) ? "PASS" : "FAIL"));
                            if (g != Preflight.Go.CLEAR || inGuild(a)) fails++;
                            tLow = null;
                        }
                    }
                    if (t == ph + 40 && tLow != null) { log("RESULT TELE out: still busy at " + tLow.getLocation() + " -> FAIL"); fails++; tLow = null; }
                    if (t == ph + 15) for (D d : ds) {
                        boolean in = inGuild(d.bot.getLocation());
                        log("RESULT DOOR " + d.name + ": at " + d.bot.getLocation() + ", inside " + in + " (expect " + d.expectIn + ", opened " + d.opened + ") -> " + (in == d.expectIn ? "PASS" : "FAIL"));
                        if (in != d.expectIn) fails++;
                    }
                    boolean all = true;
                    for (W w : ws) {
                        Location a = w.bot.getLocation();
                        if (inGuild(a) && w.trail.size() < 40) w.trail.add(a.getX() + "," + a.getY());
                        if (inGuild(a) && !w.everIn) { w.everIn = true; w.inAt = a.getX() + "," + a.getY(); log(w.name + " entered the guild at " + w.inAt); }
                        if (w.arrivedT == 0 && a.getDistance(w.to) <= 4) { w.arrivedT = now; log(w.name + " arrived after " + (now - t0) / 1000 + " s"); }
                        if (w.arrivedT != 0) continue;
                        all = false;
                        if (t % 3 != 0 || w.bot.getWalkingQueue().isMoving()) continue;
                        Location h = NavWalker.step(w.bot, w.to);
                        if (h != null) NavWalker.walkTo(w.bot, h); else w.noRoute++;
                    }
                    if (t > ph + 40 && (all || now - t0 > 240_000L)) { finish(); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            void pick() {
                AIPlayer lo = bot(null, 40), hi = bot(null, 70);
                String rl = Preflight.refuse(lo, SHOP), rh = Preflight.refuse(hi, SHOP);
                boolean ok = rl != null && rh == null;
                log("RESULT PICK food shop: 40/40 refused '" + rl + "', 70/70 refused '" + rh + "' (expect 40/40 refused, 70/70 allowed) -> " + (ok ? "PASS" : "FAIL"));
                if (!ok) fails++;
            }

            void spawn() {
                d("east-out-40", EAST_DOOR, EAST_DOOR.transform(1, 0, 0), 40, false);
                d("east-out-70", EAST_DOOR, EAST_DOOR.transform(1, 0, 0), 70, true);
                d("east-in-40", EAST_DOOR, EAST_DOOR.transform(-1, 0, 0), 40, true);
                d("west-out-40", WEST_DOOR, WEST_DOOR.transform(-1, 0, 0), 40, false);
                d("west-in-40", WEST_DOOR, WEST_DOOR.transform(1, 0, 0), 40, true);
                fillOutside(); // B1b: the gate box against the walls, every tile
                Gates.Gate gg = null; for (Gates.Gate g : Gates.GATES) if ("warriors-guild".equals(g.id)) gg = g;
                int boxIn = 0, bad = 0; StringBuilder bt = new StringBuilder();
                for (int x = 2838; x <= 2876; x++) for (int y = 3533; y <= 3555; y++) {
                    Location l = Location.create(x, y, 0); boolean in = inGuild(l); if (in) boxIn++;
                    if (gg == null || gg.contains(l) != in) { bad++; if (bad <= 10) bt.append(' ').append(x).append(',').append(y); }
                }
                log("RESULT BOX gate box vs walls: " + boxIn + " tiles inside, " + (39 * 23 - boxIn) + " outside the walls, " + bad + " disagree" + bt + " (expect 0) -> " + (bad == 0 ? "PASS" : "FAIL"));
                if (bad != 0) fails++;
                allBoxes();
                west =NavPlanner.standNear(Location.create(2826, 3549, 0));
                Location bs = NavPlanner.standNear(BURTHORPE);
                log("west target " + west + " region " + (west == null ? 0 : NavPlanner.regionAt(west)) + ", Burthorpe " + bs + " region " + (bs == null ? 0 : NavPlanner.regionAt(bs)));
                if (west != null) {
                    w("W1 burthorpe->west", BURTHORPE, west); w("W2 west->burthorpe", west, BURTHORPE);
                    w("W3 burthorpe->west2", BURTHORPE.transform(0, 6, 0), west); w("W4 north->south-west", Location.create(2860, 3575, 0), Location.create(2830, 3520, 0));
                }
                StringBuilder cf = new StringBuilder("clipping (hex):");
                int[][] probes = {{2874, 3546, 2879, 3546}, {2876, 3552, 2876, 3558}, {2860, 3552, 2860, 3558}, {2860, 3530, 2860, 3536}};
                for (int[] pr : probes) { cf.append(" |"); for (int x = pr[0]; x <= pr[2]; x++) for (int y = pr[1]; y <= pr[3]; y++) cf.append(' ').append(x).append(',').append(y).append('=').append(Integer.toHexString(RegionManager.getClippingFlag(0, x, y))); }
                log(cf.toString());
                sWalk = bot(Location.create(2870, 3556, 0), 40); sIn = bot(Location.create(2841, 3554, 0), 40);
                tLow = bot(BURTHORPE, 40); tHigh = bot(BURTHORPE, 70);
                Ledger.Wallet.give(tLow, 10_000);
                log("bots: doors " + ds.size() + ", walkers " + ws.size() + ", trace 2");
            }

            /**
             * review fix 3: every guild box. Flood by clipping flags from the ring 4 tiles outside the box (no doors opened yet):
             * box tiles it reaches are street, not inside. FAIL when an under-level bot on any street tile would be "stranded"
             * (Preflight.boxStranded, i.e. pay a teleport out), or when a walled box (Gate.walled) disagrees with the flood.
             * Unwalled boxes only report how many street tiles they cover (the stranded rule skips them).
             */
            void allBoxes() {
                AIPlayer low = bot(null, 1); Location far = Location.create(3222, 3218, 0);
                int[][] dirs = {{1, 0, 0x12c0180}, {-1, 0, 0x12c0108}, {0, 1, 0x12c0120}, {0, -1, 0x12c0102}};
                int boxes = 0, strandedAll = 0, badWalled = 0;
                for (Gates.Gate g : Gates.GATES) {
                    if (!g.id.endsWith("-guild")) continue;
                    boxes++;
                    int m = 4, bx0 = g.x0 - m, by0 = g.y0 - m, bx1 = g.x1 + m, by1 = g.y1 + m;
                    Set<Integer> seen = new HashSet<>(); Deque<int[]> q = new ArrayDeque<>();
                    for (int x = bx0; x <= bx1; x++) for (int y = by0; y <= by1; y++) {
                        if (x != bx0 && x != bx1 && y != by0 && y != by1) continue;
                        if (seen.add((x << 16) | y)) q.add(new int[]{x, y});
                    }
                    while (!q.isEmpty()) {
                        int[] p = q.poll();
                        for (int[] d : dirs) {
                            int nx = p[0] + d[0], ny = p[1] + d[1];
                            if (nx < bx0 || nx > bx1 || ny < by0 || ny > by1 || (RegionManager.getClippingFlag(0, nx, ny) & d[2]) != 0) continue;
                            if (seen.add((nx << 16) | ny)) q.add(new int[]{nx, ny});
                        }
                    }
                    int street = 0, inside = 0, disagree = 0, stranded = 0; StringBuilder st = new StringBuilder();
                    for (int x = g.x0; x <= g.x1; x++) for (int y = g.y0; y <= g.y1; y++) {
                        Location l = Location.create(x, y, 0); boolean out = seen.contains((x << 16) | y);
                        if (out) street++; else inside++;
                        if (g.contains(l) == out) disagree++;
                        if (out && Preflight.boxStranded(low, l, far) != null) { stranded++; if (stranded <= (g.walled ? 80 : 5)) st.append(' ').append(x).append(',').append(y); }
                    }
                    boolean ok = stranded == 0 && (!g.walled || disagree == 0);
                    if (stranded > 0) strandedAll += stranded;
                    if (g.walled && disagree > 0) badWalled++;
                    log("RESULT BOX " + g.id + (g.walled ? " (walled)" : " (unwalled: not a stranding box)") + ": " + street + " street tiles, " + inside + " walled-in, box vs flood disagree " + disagree
                            + (g.walled ? " (expect 0)" : " (info)") + ", under-level bot stranded on a street tile " + stranded + st + " (expect 0); rule ok for the 1/1 bot " + g.rule.ok(low) + " -> " + (ok ? "PASS" : "FAIL"));
                    if (!ok) fails++;
                }
                log("RESULT BOX all guild boxes: " + boxes + " boxes, " + strandedAll + " street tiles that would strand, " + badWalled + " walled boxes off the walls (expect 0, 0) -> " + (strandedAll == 0 && badWalled == 0 ? "PASS" : "FAIL"));
            }

            void d(String name, Location door, Location at, int lvl, boolean expectIn) {
                D d = new D(); d.name = name; d.door = door; d.expectIn = expectIn; d.bot = bot(at, lvl); ds.add(d);
            }

            void w(String name, Location from, Location to) {
                W w = new W(); w.name = name; w.to = to; w.bot = bot(from, 40); ws.add(w);
            }

            void open(D d) {
                Scenery o = RegionManager.getObject(0, d.door.getX(), d.door.getY());
                if (o == null || o.getDefinition() == null || !o.getDefinition().hasAction("Open")) { log(d.name + ": no closed door at " + d.door + " (" + (o == null ? "none" : o.getId() + " " + o.getName()) + ")"); return; }
                new ScriptAPI(d.bot).interact(d.bot, o, "Open"); d.opened = o.getId();
            }

            void finish() {
                int in = 0, arrived = 0;
                for (W w : ws) {
                    if (w.everIn) in++;
                    if (w.arrivedT != 0) arrived++;
                    log("RESULT WALK " + w.name + ": " + (w.arrivedT != 0 ? "arrived in " + (w.arrivedT - t0) / 1000 + " s" : "not arrived, at " + w.bot.getLocation()) + ", entered the guild " + w.everIn + " at " + w.inAt + ", no-route steps " + w.noRoute + ", box tiles " + w.trail + (w.everIn ? " -> FAIL" : " -> PASS"));
                }
                if (in > 0) fails++;
                log("RESULT summary: walkers " + arrived + "/" + ws.size() + " arrived, " + in + " entered the guild; gatedLandings " + Spectacle.gatedLandings + "; " + (fails == 0 ? "PASS" : "FAIL"));
                log("DONE");
            }
        });
    }
}
