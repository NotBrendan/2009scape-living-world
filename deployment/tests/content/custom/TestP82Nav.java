package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.p82=true; needs TestCR1.java; run with -Dwatchdog.min=5 like live): P82, the live stuck tiles of 11-14..11-15 (train-30..33)
 * replayed (toll gate on the dark-wizard runes walk, Falador west wall and Taverley dungeon on the blue-dragon walk). Each leg puts one smart bot on a live stuck tile and gives it the trip the live bots were on: a shop
 * walk (the brain's own shopTick, 8 min deadline) or a hunt (Brain.start(job) with the shop and armour stops skipped).
 * Logged every 100 ticks; RESULT per leg when the walk/trip ends or at the leg's tick cap: arrived / gave up / where it is,
 * closest distance, Watchdog rescues. -Dp82.legs=S1,S2,... picks legs (default all).
 */
public final class TestP82Nav implements StartupListener {
    static void log(String s) { System.out.println("[TestP82Nav] " + s); }
    static Object[] shop(String part) { for (Object[] s : BotChat.WorldData.shops) if (String.valueOf(s[0]).toLowerCase().contains(part)) return s; return null; }
    static SmartAdventurers.Job hunt(String name) { for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(name)) return j; return null; }

    static final class Leg {
        final String name, what; final Location from; final long coins; final int lvl; final boolean isShop;
        AIPlayer bot; SmartAdventurers.Brain br; Location to; boolean done, ok; int jumps; boolean paid; Location prev; int startTick, rescued0; double best = Double.MAX_VALUE; String track = "";
        Leg(String name, String what, int x, int y, int z, long coins, int lvl, boolean isShop) { this.name = name; this.what = what; this.from = Location.create(x, y, z); this.coins = coins; this.lvl = lvl; this.isShop = isShop; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p82")) return;
        final String pick = System.getProperty("p82.legs", "");
        final int cap = Integer.getInteger("p82.cap", 1000);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; java.util.List<Leg> legs = new java.util.ArrayList<>();
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 200) {
                        Leg[] all = {
                            new Leg("A1", "Dark wizard", 3270, 3167, 0, 300, 40, false),
                            new Leg("A2", "Dark wizard", 3267, 3228, 0, 300, 40, false),
                            new Leg("A3", "Dark wizard", 3263, 3237, 0, 300, 40, false),
                            new Leg("D1", "Blue dragon", 2946, 3370, 0, 3000, 85, false),
                            new Leg("D2", "Blue dragon", 2937, 3385, 0, 3000, 85, false),
                            new Leg("D3", "Blue dragon", 2896, 9821, 0, 3000, 85, false),
                            new Leg("D4", "Blue dragon", 2901, 9818, 0, 3000, 85, false),
                            new Leg("D5", "Blue dragon", 2927, 9819, 0, 3000, 85, false),
                            new Leg("D6", "Blue dragon", 2937, 9822, 0, 3000, 85, false),
                            new Leg("D7", "Blue dragon", 2837, 9599, 0, 3000, 85, false),
                            new Leg("F1", "Fire giant", 2838, 3549, 0, 3000, 70, false),
                        };
                        StringBuilder tv = new StringBuilder();
                        for (HuntMap.Hunt h : HuntMap.HUNTS) if (h.spot != null && h.spot.getX() >= 2830 && h.spot.getX() <= 2960 && h.spot.getY() >= 9740 && h.spot.getY() <= 9860) tv.append(h.name).append(' ').append(h.spot.getX()).append(',').append(h.spot.getY()).append(" canEnter(H4 kit)=").append("?").append("; ");
                        log("Taverley dungeon hunts: " + tv);
                        StringBuilder gs = new StringBuilder(); for (int gx = 2860; gx <= 2960; gx++) for (int gy = 9760; gy <= 9850; gy++) { core.game.node.scenery.Scenery sc = core.game.world.map.RegionManager.getObject(0, gx, gy); if (sc != null && sc.getName() != null && (sc.getName().toLowerCase().contains("gate") || sc.getId() == 2623)) gs.append(sc.getId()).append("@").append(gx).append(",").append(gy).append(" r").append(sc.getRotation()).append(" t").append(sc.getType()).append("; "); }
                        log("Taverley dungeon gates: " + gs);
                        if (Boolean.getBoolean("p82.dump")) { for (int gy = 9830; gy >= 9790; gy--) { StringBuilder row = new StringBuilder(); for (int gx = 2910; gx <= 2950; gx++) { int f = core.game.world.map.RegionManager.getClippingFlag(0, gx, gy); row.append((f & 0x200100) != 0 || f == -1 ? '#' : (f & 0xFF) != 0 ? '+' : '.'); } log("CLIP " + gy + " " + row); }
                            for (int[] q : new int[][]{{2884, 9798}, {2933, 9813}, {2901, 9818}}) log("BFS " + q[0] + "," + q[1] + " -> 2925,9803: " + bfs(q[0], q[1], 2925, 9803));
                            int[][] pr = {{2933, 9813, 2924, 9803}, {2933, 9813, 2925, 9803}, {2884, 9798, 2924, 9803}, {2884, 9798, 2925, 9803}, {2937, 9822, 2924, 9803}, {2884, 9798, 2923, 9803}};
                            for (int[] q : pr) { core.game.world.map.path.Path pa = core.game.world.map.path.Pathfinder.find(Location.create(q[0], q[1], 0), Location.create(q[2], q[3], 0)); core.game.world.map.Point last = pa.getPoints().isEmpty() ? null : pa.getPoints().peekLast(); log("PATH " + q[0] + "," + q[1] + " -> " + q[2] + "," + q[3] + ": ok " + pa.isSuccessful() + " near " + pa.isMoveNear() + " steps " + pa.getPoints().size() + " end " + (last == null ? "-" : last.getX() + "," + last.getY())); } }
                        try { StringBuilder os = new StringBuilder(); for (core.game.node.scenery.Scenery sc : core.game.world.map.RegionManager.getRegionPlane(Location.create(2930, 9810, 0)).getObjectList()) { if (sc == null) continue; Location sl = sc.getLocation(); if (sl.getX() < 2918 || sl.getX() > 2942 || sl.getY() < 9798 || sl.getY() > 9824) continue; String nm = sc.getName(); if (nm == null || nm.equals("null")) continue; boolean opt = false; try { for (core.game.interaction.Option o : sc.getInteraction().getOptions()) if (o != null) opt = true; } catch (Throwable ignored) { } if (!opt) continue; os.append(sc.getId()).append(' ').append(nm).append('@').append(sl.getX()).append(',').append(sl.getY()).append(" r").append(sc.getRotation()).append(" t").append(sc.getType()).append("; "); } log("objects with options near the dragon gate: " + os); } catch (Throwable e) { log("object dump error " + e); }
                        for (Leg l : all) if (pick.isEmpty() || (("," + pick + ",").contains("," + l.name + ","))) legs.add(l);
                        for (Leg l : legs) {
                            l.bot = TestCR1.bot(l.from, l.lvl, l.lvl, l.lvl, l.lvl);
                            l.bot.getInventory().remove(new Item(995, l.bot.getInventory().getAmount(995))); l.bot.getBank().remove(new Item(995, l.bot.getBank().getAmount(995)));
                        }
                    }
                    if (t == 206) for (Leg l : legs) {
                        SmartAdventurers.enlist(l.bot, false, false, -1, 0, 0, "test bot (p82 leg " + l.name + ")");
                        l.br = SmartAdventurers.BRAINS.get(l.bot.getUsername().toLowerCase(java.util.Locale.ROOT));
                        l.br.nextThink = Long.MAX_VALUE;
                        l.bot.getInventory().remove(new Item(995, l.bot.getInventory().getAmount(995))); l.bot.getBank().remove(new Item(995, l.bot.getBank().getAmount(995)));
                        if (l.coins > 0) l.bot.getInventory().add(new Item(995, (int) l.coins));
                        for (int f = 0; f < 6; f++) l.bot.getInventory().add(new Item(379, 1)); // lobsters: no food stop
                        if (l.what.equals("Blue dragon")) { l.bot.getBank().add(new Item(1590, 1)); l.bot.getEquipment().replace(new Item(1540, 1), 5); } // live blue-dragon hunters: the dusty key in the bank (Gates seeds it there) and an anti-dragon shield
                        try { l.bot.getProperties().setTeleportLocation(l.from); } catch (Throwable ignored) { }
                    }
                    if (t == 212) for (Leg l : legs) {
                        SmartAdventurers.Brain br = l.br; l.startTick = t; l.rescued0 = Watchdog.rescued;
                        if (l.isShop) {
                            Object[] sh = shop(l.what); int[] stock = (int[]) sh[3];
                            br.shopAt = l.to = Location.create((Integer) sh[1], (Integer) sh[2], 0); br.shopName = (String) sh[0];
                            br.shopBuy.clear(); br.shopBuy.add(new int[]{stock.length > 0 ? stock[0] : 1925, 1});
                            br.pendingJob = null; br.shopDeadline = System.currentTimeMillis() + 480_000; br.shopBest = Double.MAX_VALUE; br.shopStalls = 0;
                            br.nextThink = 0;
                            log("leg " + l.name + " (p82Fix=" + Boolean.getBoolean("nav.p82Fix") + "): at " + l.bot.getLocation() + " walking to " + br.shopName + " " + l.to + " (" + Math.round(l.bot.getLocation().getDistance(l.to)) + " tiles), " + Ledger.Wallet.coins(l.bot) + " gp");
                        } else {
                            SmartAdventurers.Job j = hunt(l.what);
                            if (j == null) { log("leg " + l.name + ": no hunt " + l.what); l.done = true; continue; }
                            l.to = j.hunt.spot;
                            br.skipShopOnce = true; br.armourChecked = true; br.exchangeChecked = true;
                            boolean skip = Resources.skipHunt(l.bot, j.hunt.spot);
                            log("leg " + l.name + " (p82Fix=" + Boolean.getBoolean("nav.p82Fix") + "): at " + l.bot.getLocation() + " hunt " + j.label() + " spot " + l.to + " skipHunt " + skip + " canEnter " + Gates.canEnter(l.bot, l.to) + ", " + Ledger.Wallet.coins(l.bot) + " gp");
                            br.start(j);
                            br.nextThink = Long.MAX_VALUE;
                        }
                    }
                    if (t <= 212) return false;
                    boolean all = true;
                    for (Leg l : legs) {
                        if (l.done) continue;
                        Location at = l.bot.getLocation();
                        if (l.prev != null && (l.prev.getY() < 6400) == (at.getY() < 6400) && l.prev.getZ() == at.getZ() && l.prev.getDistance(at) > 12) l.jumps++; // a teleport on the same level (a rescue or a paid stall teleport), not a ladder
                        l.prev = at;
                        if (l.br.runner != null && (l.br.runner.stallTeles > 0 || String.valueOf(l.br.runner.last).contains("teleported"))) l.paid = true; // a paid stall/rescue teleport, also into a dungeon
                        double dd = at.getZ() == l.to.getZ() ? at.getDistance(l.to) : 9999;
                        l.best = Math.min(l.best, dd);
                        boolean ended = l.isShop ? (l.br.shopAt == null || l.br.shopAt != l.to) : (l.br.runner == null || dd <= 8);
                        int age = t - l.startTick;
                        if (age % 25 == 0 && l.track.length() < 900) l.track += at.getX() + "," + at.getY() + (at.getZ() != 0 ? "," + at.getZ() : "") + " ";
                        if (ended || age >= cap) {
                            l.done = true;
                            l.ok = ended && dd <= 9 && l.jumps == 0 && !l.paid;
                            String res = ended ? (dd <= 9 ? "ARRIVED" : "ENDED " + Math.round(dd) + " off") : "STILL OUT after " + age + " ticks";
                            log("RESULT leg " + l.name + " (" + l.what + " from " + l.from.getX() + "," + l.from.getY() + "): " + res + " at " + at + " in " + age + " ticks, closest " + Math.round(l.best) + ", teleports " + l.jumps + (l.paid ? " +paid" : "") + ", rescues(all legs) " + (Watchdog.rescued - l.rescued0) + ", runner " + (l.br.runner == null ? "-" : l.br.runner.last));
                            log("TRACK " + l.name + ": " + l.track.trim());
                            l.br.nextThink = Long.MAX_VALUE; try { l.bot.getPulseManager().clear(); l.bot.getWalkingQueue().reset(); } catch (Throwable ignored) { }
                        } else {
                            all = false;
                            if (age % 100 == 0) log("leg " + l.name + " tick " + age + ": at " + at + " (" + Math.round(dd) + " off), " + BotChat.activityOf(l.bot) + (l.br.runner != null ? " | " + l.br.runner.last : ""));
                            if ((age % 50 == 0 || l.isShop && age % 10 == 0 && age <= 400) && at.getZ() == 0 && at.getY() < 6400 && l.to.getY() < 6400) try {
                                NavPlanner.Route a = NavPlanner.plan(at, l.to, NavWalker.agility(l.bot), 0, true), b = NavPlanner.plan(at, l.to, NavWalker.agility(l.bot), 0, false);
                                log("PLAN " + l.name + " at " + at.getX() + "," + at.getY() + ": toll " + (a == null ? "none" : a.tiles + " " + a.hops.subList(0, Math.min(3, a.hops.size()))) + " | free " + (b == null ? "none" : b.tiles + " " + b.hops.subList(0, Math.min(3, b.hops.size()))) + " | doors " + (a == null ? "-" : a.doorTiles + "->" + a.doorAcross) + " | walker " + l.bot.getWalkingQueue().isMoving());
                            } catch (Throwable e) { log("PLAN error " + e); }
                        }
                    }
                    if (all) { StringBuilder sb = new StringBuilder(); int n = 0; for (Leg l : legs) { sb.append(l.name).append(l.ok ? " PASS " : " FAIL "); if (l.ok) n++; } log("SUMMARY p82Fix=" + Boolean.getBoolean("nav.p82Fix") + " through " + n + "/" + legs.size() + ": " + sb.toString().trim()); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }

    /** Tile BFS over the server's clipping flags (cardinal steps, RS masks), box 2800-3000 x 9700-9900: the waypoints every 8 steps. */
    static String bfs(int sx, int sy, int tx, int ty) {
        final int X0 = 2800, Y0 = 9700, W = 201, H = 201; int[] prev = new int[W * H]; java.util.Arrays.fill(prev, -2);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>(); int s0 = (sy - Y0) * W + (sx - X0); prev[s0] = -1; q.add(s0);
        int[][] mv = {{0, 1, 0x1280120}, {1, 0, 0x1280180}, {0, -1, 0x1280102}, {-1, 0, 0x1280108}};
        int goal = (ty - Y0) * W + (tx - X0);
        while (!q.isEmpty()) { int u = q.poll(); if (u == goal) break; int ux = u % W + X0, uy = u / W + Y0;
            for (int[] m : mv) { int vx = ux + m[0], vy = uy + m[1]; if (vx < X0 || vy < Y0 || vx >= X0 + W || vy >= Y0 + H) continue; int v = (vy - Y0) * W + (vx - X0); if (prev[v] != -2) continue;
                int f = core.game.world.map.RegionManager.getClippingFlag(0, vx, vy); if (f == -1 || (f & m[2]) != 0) continue; prev[v] = u; q.add(v); } }
        if (prev[goal] == -2) return "no path";
        java.util.List<String> pts = new java.util.ArrayList<>(); int n = 0; for (int v = goal; v != -1; v = prev[v]) { if (n++ % 8 == 0) pts.add((v % W + X0) + "," + (v / W + Y0)); }
        java.util.Collections.reverse(pts); return n + " steps: " + String.join(" ", pts);
    }
}
