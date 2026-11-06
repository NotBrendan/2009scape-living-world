package content.custom;

import core.api.StartupListener;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/** Trial only (-Dtest.stairs=x,y,z): every stair, ladder and trapdoor within 15 tiles, with its options, by both lookups. */
public final class TestStairs implements StartupListener {
    @Override public void startup() {
        String at = System.getProperty("test.stairs");
        if (at == null) return;
        String[] p = at.split(",");
        Location c = Location.create(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        GameWorld.getPulser().submit(new Pulse(100) {
            @Override public boolean pulse() {
                for (int dx = -15; dx <= 15; dx++) for (int dy = -15; dy <= 15; dy++) {
                    Scenery s = null;
                    try { s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy); } catch (Throwable ignored) { }
                    if (s == null || s.getName() == null) continue;
                    String n = s.getName().toLowerCase();
                    if (!n.contains("stair") && !n.contains("ladder") && !n.contains("trapdoor")) continue;
                    StringBuilder o = new StringBuilder();
                    try { for (core.game.interaction.Option op : s.getInteraction().getOptions()) if (op != null) o.append(op.getName()).append('|'); } catch (Throwable ignored) { }
                    System.out.println("[TestStairs] tile " + s.getName() + " " + s.getId() + " @" + s.getLocation() + " active=" + s.isActive() + " opts=" + o + " floorsOpt(up)=" + QuestFloors.optionOf(s, true));
                }
                try { for (Scenery s : RegionManager.getRegionPlane(c).getObjectList()) { if (s == null || s.getName() == null || s.getLocation() == null) continue; String n = s.getName().toLowerCase(); if ((n.contains("stair") || n.contains("ladder")) && s.getLocation().getDistance(c) <= 15) System.out.println("[TestStairs] list " + s.getName() + " " + s.getId() + " @" + s.getLocation() + " up=" + QuestFloors.optionOf(s, true)); } } catch (Throwable t) { System.out.println("[TestStairs] list: " + t); }
                System.out.println("[TestStairs] DONE");
                return true;
            }
        });
    }
}
