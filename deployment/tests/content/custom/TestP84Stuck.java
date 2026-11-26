package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.p84=true; needs TestCR1.java; run with -Dwatchdog.min=5 like live): P84, the live stuck tiles of train-33
 * (11-15): Taverley dungeon 2881,9794 (black demons), Karamja 2908,3140 (jogres / tribesmen / monkeys), 3140,3509 north of the
 * Grand Exchange (troll spectators). Each leg puts one smart bot on the stuck tile and starts the hunt the live bots were on
 * (Brain.start(job), shop and armour stops skipped). A leg is OK when the bot reaches the hunt spot (within 9) without a
 * teleport or Watchdog rescue, or when the trip is dropped cleanly (runner gone, no rescue, not still on the stuck tile) for a
 * spot it cannot walk to. -Dp84.expect=on: SUMMARY PASS when every leg is OK; off: PASS when at least one leg reproduces the
 * old stuck (the old behaviour). -Dp84.legs=T1,K1,... picks legs; -Dp84.dump=true logs the hunt spots, plans and BFS probes.
 */
public final class TestP84Stuck implements StartupListener {
    static void log(String s) { System.out.println("[TestP84Stuck] " + s); }

    static SmartAdventurers.Job hunt(String name) {
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(name)) return j;
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.name.toLowerCase().contains(name.toLowerCase())) return j;
        return null;
    }

    static final class Leg {
        final String name, what; final Location from; final int lvl; Location to; AIPlayer bot; SmartAdventurers.Brain br;
        boolean done, ok, paid, started; int startTick, jumps, rescued0; double best = 9999; Location prev; String track = "";
        Leg(String name, String what, int x, int y, int lvl) { this.name = name; this.what = what; this.from = Location.create(x, y, 0); this.lvl = lvl; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p84")) return;
        final String pick = System.getProperty("p84.legs", "");
        final int cap = Integer.getInteger("p84.cap", 1500);
        final boolean expectOn = "on".equalsIgnoreCase(System.getProperty("p84.expect", "on"));
        final boolean fix = Boolean.getBoolean("nav.p84Fix");
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; java.util.List<Leg> legs = new java.util.ArrayList<>();
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 200) {
                        Leg[] all = {
                            new Leg("T1", "Black demon", 2881, 9794, 90),
                            new Leg("K1", "Jogre", 2908, 3140, 70),
                            new Leg("K2", "Tribesman", 2908, 3140, 70),
                            new Leg("K3", "Monkey", 2908, 3140, 70),
                            new Leg("G1", "Troll spectator", 3140, 3509, 80),
                        };
                        if (Boolean.getBoolean("p84.dump")) dump();
                        for (Leg l : all) if (pick.isEmpty() || ("," + pick + ",").contains("," + l.name + ",")) legs.add(l);
                        for (Leg l : legs) {
                            l.bot = TestCR1.bot(l.from, l.lvl, l.lvl, l.lvl, l.lvl);
                            l.bot.getInventory().remove(new Item(995, l.bot.getInventory().getAmount(995))); l.bot.getBank().remove(new Item(995, l.bot.getBank().getAmount(995)));
                        }
                    }
                    if (t == 206) for (Leg l : legs) {
                        SmartAdventurers.enlist(l.bot, false, false, -1, 0, 0, "test bot (p84 leg " + l.name + ")");
                        l.br = SmartAdventurers.BRAINS.get(l.bot.getUsername().toLowerCase(java.util.Locale.ROOT));
                        l.br.nextThink = Long.MAX_VALUE;
                        l.bot.getInventory().remove(new Item(995, l.bot.getInventory().getAmount(995))); l.bot.getBank().remove(new Item(995, l.bot.getBank().getAmount(995)));
                        l.bot.getInventory().add(new Item(995, 300));
                        for (int f = 0; f < 6; f++) l.bot.getInventory().add(new Item(379, 1)); // lobsters: no food stop
                        if (l.name.equals("T1")) l.bot.getBank().add(new Item(1590, 1)); // a live high fighter: the dusty key in the bank (Gates seeds it there)
                        try { l.bot.getProperties().setTeleportLocation(l.from); } catch (Throwable ignored) { }
                    }
                    if (t == 212) for (Leg l : legs) {
                        SmartAdventurers.Job j = hunt(l.what);
                        if (j == null) { log("leg " + l.name + ": no hunt " + l.what); l.done = true; l.ok = true; continue; }
                        l.to = j.hunt.spot; l.startTick = t; l.rescued0 = Watchdog.rescued;
                        l.br.skipShopOnce = true; l.br.armourChecked = true; l.br.exchangeChecked = true;
                        log("leg " + l.name + " (p84Fix=" + fix + "): at " + l.bot.getLocation() + " hunt " + j.label() + " (" + j.hunt.name + ") spot " + l.to + " skipHunt " + Resources.skipHunt(l.bot, l.to) + " canEnter " + Gates.canEnter(l.bot, l.to));
                        l.br.start(j); l.started = true;
                        l.br.nextThink = Long.MAX_VALUE;
                    }
                    if (t <= 212) return false;
                    boolean all = true;
                    for (Leg l : legs) {
                        if (l.done) continue;
                        Location at = l.bot.getLocation();
                        if (l.prev != null && (l.prev.getY() < 6400) == (at.getY() < 6400) && l.prev.getZ() == at.getZ() && l.prev.getDistance(at) > 12) l.jumps++;
                        l.prev = at;
                        if (l.br.runner != null && (l.br.runner.stallTeles > 0 || String.valueOf(l.br.runner.last).contains("teleported"))) l.paid = true;
                        double dd = at.getZ() == l.to.getZ() ? at.getDistance(l.to) : 9999;
                        l.best = Math.min(l.best, dd);
                        int rescues = Watchdog.rescued - l.rescued0;
                        boolean ended = l.br.runner == null || dd <= 8;
                        int age = t - l.startTick;
                        if (age % 25 == 0 && l.track.length() < 900) l.track += at.getX() + "," + at.getY() + (at.getZ() != 0 ? "," + at.getZ() : "") + " ";
                        if (ended || age >= cap) {
                            l.done = true;
                            boolean arrived = dd <= 9 && l.jumps == 0 && !l.paid;
                            boolean cleanDrop = ended && dd > 9 && l.jumps == 0 && !l.paid && rescues == 0 && (age <= 5 || at.getDistance(l.from) > 3); // dropped at the start (no runner), or walked off and gave up
                            l.ok = ended && (arrived || cleanDrop);
                            String res = ended ? (dd <= 9 ? "ARRIVED" : "ENDED " + Math.round(dd) + " off") : "STILL OUT after " + age + " ticks";
                            log("RESULT leg " + l.name + " (" + l.what + " from " + l.from.getX() + "," + l.from.getY() + "): " + res + " at " + at + " in " + age + " ticks, closest " + Math.round(l.best) + ", teleports " + l.jumps + (l.paid ? " +paid" : "") + ", rescues " + rescues + ", " + (l.ok ? "OK" : "STUCK") + ", runner " + (l.br.runner == null ? "-" : l.br.runner.last) + ", activity " + BotChat.activityOf(l.bot));
                            log("TRACK " + l.name + ": " + l.track.trim());
                            l.br.nextThink = Long.MAX_VALUE; try { l.bot.getPulseManager().clear(); l.bot.getWalkingQueue().reset(); } catch (Throwable ignored) { }
                        } else {
                            all = false;
                            if (age % 100 == 0) log("leg " + l.name + " tick " + age + ": at " + at + " (" + Math.round(dd) + " off), " + BotChat.activityOf(l.bot) + (l.br.runner != null ? " | " + l.br.runner.last : ""));
                        }
                    }
                    if (all) {
                        StringBuilder sb = new StringBuilder(); int n = 0;
                        for (Leg l : legs) { sb.append(l.name).append(l.ok ? " OK " : " STUCK "); if (l.ok) n++; }
                        boolean pass = expectOn ? n == legs.size() : n < legs.size();
                        log("SUMMARY " + (pass ? "PASS" : "FAIL") + " p84Fix=" + fix + " expect=" + (expectOn ? "on" : "off") + " ok " + n + "/" + legs.size() + ": " + sb.toString().trim());
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }

    static void dump() {
        for (HuntMap.Hunt h : HuntMap.HUNTS) {
            String n = h.name.toLowerCase();
            if (n.contains("jogre") || n.contains("tribes") || n.contains("monkey") || n.contains("troll") || n.contains("black demon"))
                log("HUNT " + h.name + " lvl " + h.npcLevel + " spot " + h.spot + " spawns " + h.spawnsAtSpot);
        }
        int[][] pr = {{2908, 3140}, {3140, 3509}};
        for (HuntMap.Hunt h : HuntMap.HUNTS) {
            String n = h.name.toLowerCase();
            if (h.spot == null || h.spot.getY() > 6400 || !(n.contains("jogre") || n.contains("tribes") || n.contains("monkey") || n.contains("troll spect"))) continue;
            for (int[] q : pr) try {
                NavPlanner.Route a = NavPlanner.plan(Location.create(q[0], q[1], 0), h.spot, 1, 0, false);
                log("PLAN " + q[0] + "," + q[1] + " -> " + h.name + " " + h.spot.getX() + "," + h.spot.getY() + ": " + (a == null ? "none" : a.tiles + " tiles hops " + a.hops.subList(0, Math.min(6, a.hops.size()))));
            } catch (Throwable e) { log("PLAN error " + e); }
        }
        log("BFS 2881,9794 -> 2884,9802: " + TestP82Nav.bfs(2881, 9794, 2884, 9802));
        log("BFS 2881,9794 -> 2866,9780: " + TestP82Nav.bfs(2881, 9794, 2866, 9780));
        log("BFS 2922,9803 -> 2866,9780: " + TestP82Nav.bfs(2922, 9803, 2866, 9780));
        log("BFS 2884,9798 -> 2939,9823: " + TestP82Nav.bfs(2884, 9798, 2939, 9823));
    }
}
