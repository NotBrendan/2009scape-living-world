package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.prr1=true): pathing review 10-23 R-1, RouteMap's "most-walked tile" of a cell. */
public final class TestPRR1 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRR1] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prr1")) return;
        GameWorld.getPulser().submit(new Pulse(20) {
            @Override public boolean pulse() {
                try {
                    // 1. a fresh cell: one visit on a verge tile, then 100 on the road tile
                    int c = RouteMap.cell(2100, 2600); RouteMap.BEST.remove(c);
                    RouteMap.visit(2100, 2600, 1);
                    for (int i = 0; i < 100; i++) RouteMap.visit(2102, 2602, 1);
                    int[] b = RouteMap.BEST.get(c);
                    log("RESULT fresh cell: visit (2100,2600) once, then (2102,2602) 100 times -> BEST " + b[0] + "," + b[1] + " x" + b[2] + (b[0] == 2102 ? " (the road tile)" : " (still the first tile)"));
                    log("RESULT walkedNear(2101,2601, 3) = " + RouteMap.walkedNear(Location.create(2101, 2601, 0), 3));
                    // 2. a cell as loaded from routemap.json ({x, y, visits}: 5 visits), then 20 visits on another tile
                    int c2 = RouteMap.cell(2120, 2600); RouteMap.BEST.put(c2, new int[]{2120, 2600, 5});
                    for (int i = 0; i < 20; i++) RouteMap.visit(2125, 2605, 1);
                    int[] b2 = RouteMap.BEST.get(c2);
                    log("RESULT loaded cell (2120,2600 x5), then (2125,2605) 20 times -> BEST " + b2[0] + "," + b2[1] + " x" + b2[2]);
                    // 3. interleaved: three verge tiles once each in turn (one first), the road tile three times as often
                    int c3 = RouteMap.cell(2140, 2600); RouteMap.BEST.remove(c3);
                    for (int i = 0; i < 30; i++) { RouteMap.visit(2140 + (i % 3), 2600, 1); for (int k = 0; k < 3; k++) RouteMap.visit(2143, 2603, 1); }
                    int[] b3 = RouteMap.BEST.get(c3);
                    log("RESULT interleaved (verge tiles 2140-2142,2600 once each in turn, road 2143,2603 3x as often) -> BEST " + b3[0] + "," + b3[1] + " x" + b3[2]);
                    RouteMap.BEST.remove(c); RouteMap.BEST.remove(c2); RouteMap.BEST.remove(c3);
                } catch (Throwable t) { log("error " + t); t.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
