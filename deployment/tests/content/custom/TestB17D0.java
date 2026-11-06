package content.custom;

import core.api.StartupListener;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/** Trial only (-Dtest.b17d0=true): probe, lists doors and gates within 16 tiles of the Falador party room floor and their clipping. */
public final class TestB17D0 implements StartupListener {
    static void log(String s) { System.out.println("[TestB17D0] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17d0")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 120) return false;
                try {
                    Location c = Location.create(Integer.getInteger("probe.x", 3045), Integer.getInteger("probe.y", 3378), 0);
                    for (int dx = -16; dx <= 16; dx++) for (int dy = -16; dy <= 16; dy++) {
                        Scenery s = RegionManager.getObject(0, c.getX() + dx, c.getY() + dy);
                        if (s == null || s.getName() == null) continue;
                        String n = s.getName().toLowerCase();
                        if (!n.contains("door") && !n.contains("gate")) continue;
                        log("RESULT door " + s.getName() + " id " + s.getId() + " at " + s.getLocation() + " type " + s.getType() + " rot " + s.getRotation() + " open " + QuestSteps.hasOption(s, "Open") + " close " + QuestSteps.hasOption(s, "Close"));
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int y = c.getY() + 12; y >= c.getY() - 12; y--) { sb.setLength(0); for (int x = c.getX() - 16; x <= c.getX() + 16; x++) { int f = RegionManager.getClippingFlag(0, x, y); sb.append(f == -1 ? '?' : (f & 0x200000) != 0 || (f & 0x100) != 0 ? '#' : (f & 0xFF) != 0 ? Integer.toHexString(f & 0xF).charAt(0) : '.'); } log("map " + y + " " + sb); }
                } catch (Throwable e) { log("error " + e); }
                log("DONE"); return true;
            }
        });
    }
}
