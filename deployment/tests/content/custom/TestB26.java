package content.custom;

import core.api.StartupListener;
import core.game.node.entity.npc.NPC;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.util.*;

/**
 * Trial only (-Dtest.b26=true; -Dsmartbots.count=0): batch 26, the stuck-review fixes (the design notes).
 *  LOOP   fix 5: the loop detector's defaults are 6 visits / 6 tiles; 4/4 no longer rescues, 6/6 does.
 *  LEG    fix 2: the leg key is the nearest bank (a bot a few tiles off the bank counts as at it), different per job.
 *  PLAN   fix 3: the review's far legs plan over 320 tiles (a bot under 5k skips them); a short leg stays short.
 *  SPOTS  fix 4: the planned route and NPCs round each data spot (old and new), so the new tiles are checked, not guessed.
 * Ends with RESULT lines and DONE.
 */
public final class TestB26 implements StartupListener {
    static void log(String s) { System.out.println("[TestB26] " + s); }

    static Location L(int x, int y) { return Location.create(x, y, 0); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b26")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            long t0; boolean done;
            @Override public boolean pulse() {
                if (done) return true;
                long now = System.currentTimeMillis();
                if (t0 == 0) t0 = now;
                if (now - t0 > 600_000) { done = true; log("RESULT planner never came up"); log("DONE"); return true; }
                if (now - t0 < 60_000 || !NavPlanner.built) return false;
                done = true;
                try { checks(); } catch (Throwable e) { log("RESULT exception " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }

    static String plan(Location a, Location b) {
        NavPlanner.Route r = NavPlanner.plan(a, b, 1);
        return r == null ? "NO ROUTE" : r.tiles + " tiles, " + r.doors + " doors";
    }
    static int tiles(Location a, Location b) { NavPlanner.Route r = NavPlanner.plan(a, b, 1); return r == null ? -1 : r.tiles; }

    static void npcs(String what, Location c, int rad, String... names) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (NPC npc : Repository.getNpcs()) {
            if (npc == null || npc.getName() == null) continue;
            Location l = npc.getLocation();
            if (l == null || l.getZ() != c.getZ() || l.getDistance(c) > rad) continue;
            boolean hit = false; for (String s : names) if (npc.getName().toLowerCase(Locale.ROOT).contains(s)) hit = true;
            if (!hit) continue;
            n++; if (n <= 14) sb.append(' ').append(npc.getName()).append('@').append(l.getX()).append(',').append(l.getY());
        }
        log("NPCS " + what + " within " + rad + " of " + c.getX() + "," + c.getY() + ": " + n + sb);
    }

    /** The reachable standable tiles within r of c, shortest planned from {@code from} first. */
    static void ring(String what, Location from, Location c, int r) {
        List<int[]> ok = new ArrayList<>();
        int stand = 0;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
            int x = c.getX() + dx, y = c.getY() + dy;
            if (!NavGrid.standable(x, y)) continue;
            stand++;
            int tl = tiles(from, L(x, y));
            if (tl >= 0) ok.add(new int[]{tl, x, y});
        }
        ok.sort(Comparator.comparingInt(a -> a[0]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, ok.size()); i++) sb.append(' ').append(ok.get(i)[1]).append(',').append(ok.get(i)[2]).append('=').append(ok.get(i)[0]);
        log("RING " + what + " r" + r + " round " + c.getX() + "," + c.getY() + " from " + from.getX() + "," + from.getY() + ": standable " + stand + ", with a route " + ok.size() + "; nearest:" + sb);
    }

    static void spot(String what, Location from, Location to) {
        log("SPOT " + what + " " + from.getX() + "," + from.getY() + " -> " + to.getX() + "," + to.getY() + ": " + plan(from, to) + ", standable " + NavGrid.standable(to.getX(), to.getY()) + ", straight " + (int) from.getDistance(to));
    }

    static void checks() {
        int fails = 0;
        // LOOP (fix 5)
        boolean l1 = Failures.LOOP_VISITS == 6 && Failures.LOOP_SPAN == 6 && !Failures.loopRescue(4, 4) && !Failures.loopRescue(5, 12) && !Failures.loopRescue(8, 5) && Failures.loopRescue(6, 6);
        log("RESULT LOOP visits " + Failures.LOOP_VISITS + " span " + Failures.LOOP_SPAN + ": 4/4 " + Failures.loopRescue(4, 4) + ", 5/12 " + Failures.loopRescue(5, 12) + ", 8/5 " + Failures.loopRescue(8, 5) + " (expect false), 6/6 " + Failures.loopRescue(6, 6) + " (expect true) -> " + (l1 ? "PASS" : "FAIL"));
        if (!l1) fails++;
        // LEG (fix 2)
        Location dray = AfkTasks.BANKS[0];
        String k1 = SmartAdventurers.legKey("Farmers", dray), k2 = SmartAdventurers.legKey("Farmers", L(dray.getX() + 4, dray.getY() - 3)), k3 = SmartAdventurers.legKey("Chickens", dray);
        String k4 = SmartAdventurers.legKey("Farmers", L(3015, 3200)), k5 = SmartAdventurers.legKey("Farmers", L(3096, 9867));
        boolean l2 = k1.equals(k2) && !k1.equals(k3) && !k4.equals(k1) && k4.startsWith("Farmers@c") && k5.equals(SmartAdventurers.legKey("Farmers", AfkTasks.BANKS[5]));
        log("RESULT LEG " + k1 + " / " + k2 + " / " + k3 + " / " + k4 + " / dungeon " + k5 + " -> " + (l2 ? "PASS" : "FAIL"));
        if (!l2) fails++;
        // PLAN (fix 3): the review's far legs (planned length from the review's T5 / clusters)
        Object[][] legs = {
            {"dark wizards from Port Sarim", L(3014, 3200), L(3225, 3370), true},
            {"Varrock SE mine from Al Kharid (Glock48)", AfkTasks.BANKS[6], L(3287, 3366), false},
            {"trolls from Edgeville", AfkTasks.BANKS[5], L(2869, 3594), true},
            {"Barbarian river from Catherby", AfkTasks.BANKS[7], L(3104, 3424), true},
            {"Al Kharid warriors from Port Sarim", L(3014, 3200), L(3293, 3172), true},
            {"Draynor fishing from Draynor (short)", dray, L(3087, 3228), false},
        };
        boolean l3 = true;
        for (Object[] g : legs) {
            Location a = (Location) g[1], b = (Location) g[2];
            int tl = tiles(a, b);
            boolean far = tl > AfkTasks.Runner.TELEPORT_MIN;
            boolean want = (Boolean) g[3];
            String v = tl < 0 ? "no route" : (far == want ? "ok" : "unexpected");
            if (want && tl >= 0 && !far) l3 = false;
            if (!want && tl > 0 && far && ((String) g[0]).contains("short")) l3 = false;
            log("PLAN " + g[0] + ": straight " + (int) a.getDistance(b) + ", planned " + tl + " (" + (far ? "over" : "under") + " " + AfkTasks.Runner.TELEPORT_MIN + ") " + v);
        }
        log("RESULT PLAN far legs plan over " + AfkTasks.Runner.TELEPORT_MIN + " tiles -> " + (l3 ? "PASS" : "FAIL"));
        if (!l3) fails++;
        // SPOTS (fix 4): old and new tiles, and what stands round them
        Location lumBank = L(3208, 3220);
        spot("farmers Draynor wheat field (old)", dray, L(3124, 3276));
        spot("farmers Lumbridge farm (old)", dray, L(3156, 3300));
        spot("farmers Lumbridge farm (old) from Lumbridge", lumBank, L(3156, 3300));
        npcs("farmers", L(3140, 3290), 30, "farmer");
        ring("farmers Lumbridge farm", dray, L(3156, 3300), 6);
        ring("farmers Draynor wheat field", dray, L(3124, 3276), 6);
        spot("farmer NPC Draynor field", dray, L(3121, 3274));
        spot("farmer NPC Lumbridge farm", dray, L(3163, 3299));
        spot("west fence -> Lumbridge farmer", L(3150, 3296), L(3163, 3299));
        spot("outside Draynor field -> its farmer", L(3118, 3273), L(3121, 3274));
        ring("Lumbridge farmer NPC", dray, L(3163, 3299), 4);
        ring("Draynor field farmer NPC", dray, L(3121, 3274), 3);
        spot("Harry's shop tile (old)", AfkTasks.BANKS[7], L(2836, 3447));
        npcs("Harry", L(2836, 3447), 12, "harry");
        ring("Harry's shop", AfkTasks.BANKS[7], L(2836, 3447), 5);
        npcs("hill giants", L(3110, 9850), 30, "hill giant");
        log("GATE hill giants spot 3115,9850 has a gate: " + !Gates.at(L(3115, 9850)).isEmpty() + "; landing 3096,9867 has one: " + !Gates.at(L(3096, 9867)).isEmpty());
        for (HuntMap.Hunt h : HuntMap.HUNTS) {
            String n = h.name.toLowerCase(Locale.ROOT);
            if (!n.contains("snail")) continue;
            spot("hunt " + h.name + " (" + h.spawnsAtSpot + " spawns)", AfkTasks.BANKS[13], h.spot);
            ring("hunt " + h.name, AfkTasks.BANKS[13], h.spot, 6);
        }
        npcs("snails", L(3450, 3430), 60, "snail");
        log("RESULT UNITS " + (fails == 0 ? "PASS" : fails + " FAIL"));
    }
}
