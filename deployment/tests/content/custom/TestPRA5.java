package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/**
 * Trial only (-Dtest.pra5=true): pathing review 10-23 A-5. Smart-adventurer shop walks (Brain.shopTick, driven at the
 * brain's 3-tick rate) to Bob's Brilliant Axes (3228,3203 in map-data.json) from the tiles the live "never reached" lines
 * named, with no coins (so no teleport way out). Each walk gets 150 s. Logs the bridgeVia waypoint whenever it changes,
 * then arrived / never reached. -Dtest.pra5.map=true also prints the clipping round the castle.
 * SIM lines iterate bridgeVia plus 25-tile straight hops (what shopTick does with no learned route) and report a repeat.
 */
public final class TestPRA5 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRA5] " + s); }
    static final Location BOB = Location.create(3228, 3203, 0);
    static final Location[] STARTS = {Location.create(3255, 3226, 0), Location.create(3250, 3226, 0), Location.create(3240, 3216, 0), Location.create(3232, 3216, 0), Location.create(3248, 3232, 0)};

    static void load(int x, int y) {
        try { core.game.world.map.Region r = RegionManager.forId(((x >> 6) << 8) | (y >> 6)); if (r != null && !r.isLoaded()) core.game.world.map.Region.load(r); } catch (Throwable ignored) { }
    }

    static void map() {
        for (int x = 3190; x <= 3264; x += 8) for (int y = 3190; y <= 3250; y += 8) load(x, y);
        for (int y = 3246; y >= 3195; y--) {
            StringBuilder s = new StringBuilder(String.valueOf(y)).append(' ');
            for (int x = 3195; x <= 3260; x++) {
                int f = RegionManager.getClippingFlag(0, x, y);
                char c = f == -1 ? '?' : (f & 0x200000) != 0 || (f & 0x100) != 0 ? '#' : (f & 0xff) != 0 ? '+' : '.';
                if (x == BOB.getX() && y == BOB.getY()) c = 'B';
                s.append(c);
            }
            log("map " + s);
        }
        log("map x from 3195 (left) to 3260; # blocked, + wall on an edge, . open");
    }

    static String simulate(Location from) {
        Location p = from; StringBuilder seq = new StringBuilder(p.getX() + "," + p.getY());
        java.util.Set<String> seen = new java.util.HashSet<>(); seen.add(seq.toString());
        for (int i = 0; i < 12; i++) {
            Location v = AfkTasks.Runner.bridgeVia(p, BOB);
            Location tgt = v != null && p.getDistance(v) > 3 ? v : BOB;
            double d = p.getDistance(tgt);
            p = d <= 25 ? tgt : Location.create(p.getX() + (int) Math.round((tgt.getX() - p.getX()) * 25 / d), p.getY() + (int) Math.round((tgt.getY() - p.getY()) * 25 / d), 0);
            String k = p.getX() + "," + p.getY();
            seq.append(" -> ").append(k);
            if (p.getDistance(BOB) <= 5) return seq + " (at Bob's)";
            if (!seen.add(k)) return seq + " (REPEATS)";
        }
        return seq + " (gave up)";
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.pra5")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg = -1, wait; AIPlayer b; SmartAdventurers.Brain br; long t0; String lastVia; boolean on;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 30) {
                        if (Boolean.getBoolean("test.pra5.map")) map();
                        for (Location s : STARTS) log("SIM bridgeVia + 25-tile hops from " + s.getX() + "," + s.getY() + ": " + simulate(s));
                    }
                    if (t < 40) return false;
                    if (b == null) { b = TestCR1.bot(STARTS[0], 40, 30, 30, 30); br = TestCR1.rig(b, false); return false; }
                    if (on && br.shopAt == null) { // finishShop ran: there
                        log("RESULT from " + STARTS[leg] + ": ARRIVED at " + b.getLocation() + " after " + (System.currentTimeMillis() - t0) / 1000 + " s");
                        on = false;
                    }
                    if (on && System.currentTimeMillis() - t0 > 150_000) {
                        log("RESULT from " + STARTS[leg] + ": NEVER REACHED Bob's in 150 s, at " + b.getLocation() + " (" + (int) b.getLocation().getDistance(BOB) + " tiles off)");
                        br.shopAt = null; on = false;
                    }
                    if (!on) {
                        if (wait > 0) { if (--wait == 0) { br.shopAt = BOB; br.shopName = "Bob's Brilliant Axes"; br.shopBuy.clear(); br.pendingJob = null; br.shopDeadline = System.currentTimeMillis() + 600_000; br.shopBest = Double.MAX_VALUE; br.shopStalls = 0; br.shopLast = null; t0 = System.currentTimeMillis(); lastVia = null; on = true; log("leg " + leg + ": shop walk from " + b.getLocation()); } return false; }
                        if (++leg >= STARTS.length) { log("DONE"); return true; }
                        b.getPulseManager().clear(); b.getWalkingQueue().reset(); RouteMap.PLANS.remove(b.getUsername().toLowerCase());
                        b.getProperties().setTeleportLocation(STARTS[leg]);
                        wait = 3; return false;
                    }
                    if (t % 3 == 0) {
                        Location via = AfkTasks.Runner.bridgeVia(b, b.getLocation(), BOB);
                        String v = via == null ? "none" : via.getX() + "," + via.getY();
                        if (!v.equals(lastVia)) { lastVia = v; log("   " + (System.currentTimeMillis() - t0) / 1000 + " s at " + b.getLocation().getX() + "," + b.getLocation().getY() + ": via " + v + ", stalls " + br.shopStalls); }
                        br.shopTick(System.currentTimeMillis());
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
