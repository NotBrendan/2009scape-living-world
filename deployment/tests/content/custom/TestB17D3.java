package content.custom;

import core.game.bots.AIPlayer;
import core.api.StartupListener;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.List;
import java.util.Locale;

/**
 * Trial only (-Dtest.b17d3=true -Dafk.debug=true; needs TestCR1; run once with data/survey/nav.on and once without): batch 17
 * item3, trace of the westbound stall out of Lumbridge. Broke bots (no teleports) from Lumbridge 3222,3218:
 * A AFK runner to Port Sarim 3041,3202; B AFK runner to Draynor bank 3093,3244; C a brain's shop walk (shopTick) to
 * Port Sarim 3041,3202. 1200 ticks a leg. Every 25 ticks the position; when a bot stands within 2 tiles for 12 ticks, a dump
 * (once per spot): the runner's last, bridgeVia, the RouteMap plan hop, the NavWalker hop, doors and gates within 6 tiles,
 * and the clipping round it (hex of the wall bits; # blocked).
 */
public final class TestB17D3 implements StartupListener {
    static void log(String s) { System.out.println("[TestB17D3] " + s); }
    static final Location LUM = Location.create(3222, 3218, 0), SARIM = Location.create(3041, 3202, 0), DRAYNOR = Location.create(3093, 3244, 0);
    static final int LIMIT = 1200;

    static String dump(AIPlayer bot, Location dest, String last) {
        Location h = bot.getLocation(); String k = bot.getUsername().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("stall dump at " + h + " -> " + dest + ": last '" + last + "'");
        try { sb.append(", bridgeVia ").append(AfkTasks.Runner.bridgeVia(bot, h, dest)); } catch (Throwable t) { sb.append(", bridgeVia ").append(t); }
        try { Object[] pl = RouteMap.PLANS.get(k); if (pl == null) sb.append(", RouteMap plan none"); else { @SuppressWarnings("unchecked") List<Location> r = (List<Location>) pl[1]; int i = (Integer) pl[2]; sb.append(", RouteMap plan i=").append(i).append("/").append(r.size()); for (int j = Math.max(0, i - 1); j < Math.min(r.size(), i + 3); j++) sb.append(" ").append(r.get(j)); } } catch (Throwable t) { sb.append(", plan ").append(t); }
        try { NavWalker.State s = NavWalker.STATES.get(k); if (s == null || s.route == null) sb.append(", Nav none"); else { sb.append(", Nav hop ").append(s.hop).append("/").append(s.route.hops.size()); for (int j = Math.max(0, s.hop - 1); j < Math.min(s.route.hops.size(), s.hop + 3); j++) sb.append(" ").append(s.route.hops.get(j)).append(s.route.doorTiles.contains(s.route.hops.get(j)) ? "(door)" : ""); } } catch (Throwable t) { sb.append(", nav ").append(t); }
        sb.append(", stuck at the River Lum? watchdog rescued ").append(Watchdog.rescued);
        log(sb.toString());
        for (int dx = -6; dx <= 6; dx++) for (int dy = -6; dy <= 6; dy++) {
            Scenery o = RegionManager.getObject(0, h.getX() + dx, h.getY() + dy);
            if (o == null || o.getName() == null) continue;
            String n = o.getName().toLowerCase(Locale.ROOT);
            if (n.contains("door") || n.contains("gate") || n.contains("fence") || n.contains("bridge") || n.contains("stile")) log("  object " + o.getName() + " " + o.getId() + " at " + o.getLocation() + " type " + o.getType() + " rot " + o.getRotation() + " open " + QuestSteps.hasOption(o, "Open") + " close " + QuestSteps.hasOption(o, "Close"));
        }
        for (int y = h.getY() + 6; y >= h.getY() - 6; y--) {
            StringBuilder m = new StringBuilder();
            for (int x = h.getX() - 8; x <= h.getX() + 8; x++) {
                int f = RegionManager.getClippingFlag(0, x, y);
                String c = f == -1 ? "??" : (f & 0x200000) != 0 || (f & 0x100) != 0 ? "##" : (f & 0xFF) == 0 ? ".." : String.format("%02x", f & 0xFF);
                m.append(x == h.getX() && y == h.getY() ? "@@" : c).append(' ');
            }
            log("  map " + y + " (x " + (h.getX() - 8) + "..) " + m);
        }
        return sb.toString();
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17d3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, leg = -1, ticks, still; AIPlayer bot; AfkTasks.Runner r; SmartAdventurers.Brain br; Location dest, anchor; double best; java.util.Set<String> dumped = new java.util.HashSet<>(); boolean inCell; int cellTicks;
            void broke(AIPlayer b) { b.getInventory().remove(new Item(995, b.getInventory().getAmount(995))); b.getBank().remove(new Item(995, b.getBank().getAmount(995))); }
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    long now = System.currentTimeMillis();
                    if (leg >= 0 && bot != null && ticks > 3) {
                        boolean arrived = leg < 2 ? r != null && r.state == AfkTasks.State.WORK : bot.getLocation().getDistance(dest) <= 5;
                        boolean over = ticks >= LIMIT || (r != null && r.halted);
                        if (arrived || over) {
                            log("RESULT leg " + "ABC".charAt(leg) + " " + LUM + " -> " + dest + ": " + (arrived ? "ARRIVED at tick " + ticks : r != null && r.halted ? "halted '" + r.last + "' at tick " + ticks : "NOT there after " + LIMIT) + ", at " + bot.getLocation() + " (" + (int) bot.getLocation().getDistance(dest) + " off, closest " + (int) best + "), ticks in the cell 3144-3151 x 3232-3239: " + cellTicks + ", nav " + NavWalker.ON + ", stall dumps " + dumped.size());
                            bot.getPulseManager().clear(); bot.getWalkingQueue().reset();
                            if (br != null) br.shopAt = null;
                            bot.getProperties().setTeleportLocation(Location.create(3210, 3420, 0));
                            bot = null; r = null; br = null;
                            if (leg >= 2) { log("NavWalker " + NavWalker.stats()); log("DONE"); return true; }
                            return false;
                        }
                    }
                    if (bot == null) {
                        leg++;
                        dest = leg == 1 ? DRAYNOR : SARIM;
                        bot = TestCR1.bot(LUM, 40, 20, 20, 20); broke(bot);
                        if (leg == 2) { br = TestCR1.rig(bot, false); }
                        ticks = -3; still = 0; anchor = null; best = 9999; dumped.clear(); cellTicks = 0;
                        return false;
                    }
                    ticks++;
                    if (ticks == 3) {
                        if (leg < 2) { r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false); r.walker = false; boolean ok = r.goTo(AfkTasks.State.TO_SPOT, dest); log("leg " + "ABC".charAt(leg) + " " + bot.getUsername() + " goTo " + ok + ", last '" + r.last + "', nav " + NavWalker.ON); }
                        else { br.shopAt = dest; br.shopName = "the test shop"; br.shopDeadline = now + 20 * 60_000L; br.shopBest = Double.MAX_VALUE; br.shopStalls = 0; br.idleUntil = 0; log("leg C " + bot.getUsername() + " shop walk, nav " + NavWalker.ON); }
                        return false;
                    }
                    if (ticks < 3) return false;
                    if (leg < 2) { if (!r.halted && r.state != AfkTasks.State.WORK) r.walk(AfkTasks.State.WORK); }
                    else if (ticks % 2 == 0 && br.shopAt != null) br.shopTick(now);
                    Location h = bot.getLocation();
                    best = Math.min(best, h.getDistance(dest));
                    if (h.getX() >= 3144 && h.getX() <= 3151 && h.getY() >= 3232 && h.getY() <= 3239) { cellTicks++; if (!inCell) log("leg " + "ABC".charAt(leg) + " enters the cell at " + h + " tick " + ticks); inCell = true; } else inCell = false;
                    if (anchor == null || h.getDistance(anchor) > 2) { anchor = h; still = 0; } else if (++still == 12) { String key = (h.getX() >> 2) + "," + (h.getY() >> 2); if (dumped.add(key)) dump(bot, dest, r != null ? r.last : "shopStalls " + br.shopStalls); }
                    if (ticks % 25 == 0) log("leg " + "ABC".charAt(leg) + " tick " + ticks + ": at " + h + ", last '" + (r != null ? r.last : "shopStalls " + br.shopStalls) + "'");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
