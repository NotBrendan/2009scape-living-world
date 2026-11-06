package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/** Trial only (-Dtest.decay=true): a learned wall (10 failures, no success) through three 10-minute saves' worth of decay. */
public final class TestDecay implements StartupListener {
    @Override public void startup() {
        if (!Boolean.getBoolean("test.decay")) return;
        GameWorld.getPulser().submit(new Pulse(60) {
            @Override public boolean pulse() {
                RouteMap.Edge e = null;
                for (RouteMap.Edge[] es : RouteMap.EDGES.values()) { for (RouteMap.Edge x : es) if (x != null) { e = x; break; } if (e != null) break; }
                if (e == null) { System.out.println("[TestDecay] no edges loaded"); return true; }
                e.ok = 0; e.fail = 10;
                StringBuilder sb = new StringBuilder("10");
                for (int k = 0; k < 3; k++) { RouteMap.decay(); sb.append(" -> ").append(e.fail); }
                e.fail = 6; StringBuilder sb2 = new StringBuilder("6");
                for (int k = 0; k < 3; k++) { RouteMap.decay(); sb2.append(" -> ").append(e.fail); }
                System.out.println("[TestDecay] wall: " + sb + " | ordinary failures: " + sb2);
                System.out.println("[TestDecay] DONE");
                return true;
            }
        });
    }
}
