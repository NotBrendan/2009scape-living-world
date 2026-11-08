package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Trial only (-Dtest.p39=true; needs TestCR1 on the path; -Dquests=false -Dsmartbots.count=0 so the Watchdog count is this
 * bot's): P39 reachability (the design notes section 6). Run once with -Dreach=true and once without.
 * Startup checks (no walk): P2 (R1, the Mort Myre hunts), P3 (R4, the built-in multi-tile gate record), P4 (R2, Shilo).
 * Walk legs: E1/E3/E4 (R3, the Lumbridge bridge, planner OFF) and WG1W/WG1S (the Warriors' Guild probe, att+str 60 / 140).
 * A leg passes when the bot is within 3 tiles of the destination inside the leg's time, with no Watchdog rescue, no teleport
 * jump, not halted, and (E legs) fewer than 5 "to the bridge" vias. -Dtest.p39.reps (default 2); -Dtest.p39.legs=E1,WG1 runs
 * only those (WG1 = both WG1 bots). Lines: "[TestP39Reach] RESULT leg <id> ... PASS|FAIL ...", then "SUMMARY ..." and "DONE".
 */
public final class TestP39Reach implements StartupListener {
    static void log(String s) { System.out.println("[TestP39Reach] " + s); }
    static Location at(int x, int y) { return Location.create(x, y, 0); }

    static final class Leg {
        final String id; final Location from, to; final int secs; final AfkTasks.Task task;
        int att = 60, str = 60; boolean bridge; // bridge: count "to the bridge" vias (< 5 to pass)
        boolean natureSpirit, myre, expectHalt; // M legs: Nature Spirit started (and Priest in Peril done); expectHalt: pass = halted at the Myre gate rule
        Leg(String id, Location from, Location to, int secs, AfkTasks.Task task) { this.id = id; this.from = from; this.to = to; this.secs = secs; this.task = task; }
    }

    static List<Leg> legs() {
        List<Leg> l = new ArrayList<>();
        for (Leg e : new Leg[]{new Leg("E1", at(3246, 3237), at(3222, 3218), 120, AfkTasks.Task.NET), new Leg("E3", at(3248, 3226), at(3222, 3218), 120, AfkTasks.Task.NET),
                new Leg("E4", at(3252, 3227), at(3222, 3218), 120, AfkTasks.Task.NET)}) { e.bridge = true; l.add(e); }
        Location sw = myreSpot();
        Leg m1 = new Leg("M1", at(3443, 3465), sw, 300, AfkTasks.Task.NET); m1.natureSpirit = true; m1.myre = true; l.add(m1); // R1: Nature Spirit started: through the north gate
        Leg m0 = new Leg("M0", at(3443, 3465), sw, 60, AfkTasks.Task.NET); m0.myre = true; m0.expectHalt = Gates.REACH_MYRE; l.add(m0); // R1: not started: the Runner refuses (flag on)
        Leg w = new Leg("WG1W", at(2837, 3551), at(2884, 3396), 400, AfkTasks.Task.NET); w.att = 30; w.str = 30; l.add(w);
        Leg s = new Leg("WG1S", at(2837, 3551), at(2884, 3396), 400, AfkTasks.Task.NET); s.att = 70; s.str = 70; l.add(s);
        return l;
    }

    /** A Mort Myre hunt spot (the swamp south of the fence), else a swamp tile. */
    static Location myreSpot() {
        for (HuntMap.Hunt hu : new ArrayList<>(HuntMap.HUNTS)) if (hu != null && hu.spot != null && inMyre(hu.spot)) return hu.spot;
        return at(3437, 3410);
    }
    static boolean inMyre(Location s) { int x = s.getX(), y = s.getY(); return s.getZ() == 0 && x >= 3400 && x <= 3520 && y >= 3320 && y <= 3455 && !(x >= 3490 && y >= 3430) && !(x >= 3480 && y >= 3452); }
    static void seedQuests(AIPlayer b, boolean ns) {
        try { var q = b.getQuestRepository().getQuest(content.data.Quests.PRIEST_IN_PERIL); if (q != null) b.getQuestRepository().setStage(q, 100); } catch (Throwable e) { log("Priest in Peril seed " + e); }
        if (ns) try { var q = b.getQuestRepository().getQuest(content.data.Quests.NATURE_SPIRIT); if (q != null) b.getQuestRepository().setStage(q, 10); else log("no Nature Spirit quest on this server"); } catch (Throwable e) { log("Nature Spirit seed " + e); }
    }

    static boolean wanted(Set<String> want, String id) { return want.isEmpty() || want.contains(id) || want.contains(id.replaceAll("[WS]$", "")); }

    static String flags() {
        return "reach " + AfkTasks.Runner.REACH + " (myre " + Gates.REACH_MYRE + ", bridge " + AfkTasks.Runner.REACH_BRIDGE + ", shilo " + Gates.REACH_SHILO
                + ", gates " + NavGrid.REACH_GATES + ", sea " + AfkTasks.Runner.REACH_SEA + "), nav " + NavWalker.ON;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p39")) return;
        final int reps = Integer.getInteger("test.p39.reps", 2);
        String only = System.getProperty("test.p39.legs", "");
        final Set<String> want = new HashSet<>(only.isEmpty() ? new ArrayList<>() : Arrays.asList(only.toUpperCase(Locale.ROOT).split(",")));
        log("flags: " + flags());
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, idx = -1, rep, ticks, rescued0, jumps, pass, fail, bridgeHops; boolean started, wasBridge;
            List<Leg> list; Leg leg; AIPlayer bot; AfkTasks.Runner r; Location prev;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (!started) {
                        if (t < 110) return false;
                        boolean ready = (!NavWalker.ON || NavPlanner.built) && !HuntMap.HUNTS.isEmpty();
                        if (!ready && t < 600) return false; // the grid graph (planner on) and the hunt map (60 s after boot)
                        started = true; list = new ArrayList<>();
                        for (Leg l : legs()) if (wanted(want, l.id)) list.add(l);
                        log("start: " + flags() + ", graph " + NavPlanner.built + ", hunts " + HuntMap.HUNTS.size() + ", legs " + list.size() + " x " + reps + ", watchdog rescued so far " + Watchdog.rescued);
                        if (want.isEmpty() || want.contains("P2") || want.contains("P3") || want.contains("P4")) startupChecks();
                        return false;
                    }
                    if (bot == null) {
                        if (rep == 0 || rep >= reps) { idx++; rep = 0; }
                        if (idx >= list.size()) { log("SUMMARY pass " + pass + " fail " + fail + " (" + flags() + "), NavWalker " + NavWalker.stats()); log("DONE"); return true; }
                        rep++; leg = list.get(idx);
                        bot = TestCR1.bot(leg.from, 60, leg.att, leg.str, 60);
                        try { bot.getInventory().add(new Item(995, 5000)); } catch (Throwable ignored) { }
                        if (leg.myre) seedQuests(bot, leg.natureSpirit);
                        ticks = -3; jumps = 0; prev = null; bridgeHops = 0; wasBridge = false;
                        rescued0 = Watchdog.rescued;
                        return false;
                    }
                    ticks++;
                    if (ticks == 3) {
                        r = new AfkTasks.Runner(bot, leg.task, false, 0, false, false); r.walker = false;
                        boolean ok = r.goTo(AfkTasks.State.TO_SPOT, leg.to);
                        log("leg " + leg.id + " rep " + rep + " " + bot.getUsername() + " at " + bot.getLocation() + " goTo " + leg.to + " " + ok + ", last '" + r.last + "'");
                        prev = bot.getLocation();
                        return false;
                    }
                    if (ticks < 3) return false;
                    Location h = bot.getLocation();
                    if (prev != null && h.getDistance(prev) > 15) { jumps++; log("leg " + leg.id + " rep " + rep + " jump " + prev + " -> " + h + " at tick " + ticks + ", last '" + r.last + "'"); }
                    prev = h;
                    boolean isBridge = r.last != null && r.last.startsWith("to the bridge");
                    if (isBridge && !wasBridge) bridgeHops++;
                    wasBridge = isBridge;
                    if (h.getDistance(leg.to) <= 3) return verdict(true, "ARRIVED at tick " + ticks + " (" + (ticks * 6 / 10) + " s)");
                    if (r.halted) return verdict(leg.expectHalt && r.last != null && r.last.contains("Nature Spirit"), "halted '" + r.last + "' at tick " + ticks);
                    if (ticks >= leg.secs * 10 / 6) return verdict(false, "OVER: not there after " + leg.secs + " s");
                    if (r.state != AfkTasks.State.WORK) r.walk(AfkTasks.State.WORK);
                    if (ticks % 50 == 0) log("leg " + leg.id + " rep " + rep + " tick " + ticks + ": at " + h + " (" + (int) h.getDistance(leg.to) + " off), last '" + r.last + "'");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            void startupChecks() {
                AIPlayer probe = TestCR1.bot(at(3222, 3218), 60, 60, 60, 60);
                AIPlayer probeNs = TestCR1.bot(at(3222, 3218), 60, 60, 60, 60); seedQuests(probe, false); seedQuests(probeNs, true);
                try {
                    if (want.isEmpty() || want.contains("P2")) { // R1: hunts with their spot in the Mort Myre box, as SmartAdventurers offers them (Gates.canEnter on the spot)
                        int n = 0, offered = 0, offeredNs = 0; StringBuilder names = new StringBuilder();
                        for (HuntMap.Hunt hu : new ArrayList<>(HuntMap.HUNTS)) {
                            if (hu == null || hu.spot == null) continue;
                            int x = hu.spot.getX(), y = hu.spot.getY();
                            if (!inMyre(hu.spot)) continue;
                            n++; boolean ok = Gates.canEnter(probe, hu.spot); if (ok) offered++; if (Gates.canEnter(probeNs, hu.spot)) offeredNs++;
                            if (names.length() < 200) names.append(names.length() > 0 ? ", " : "").append(hu.name).append(" ").append(x).append(",").append(y).append(ok ? " offered" : " closed");
                        }
                        String nsImpl = "?"; try { nsImpl = String.valueOf(Gates.implemented(probe, content.data.Quests.NATURE_SPIRIT)); } catch (Throwable ignored) { }
                        boolean ok = (Gates.REACH_MYRE ? n > 0 && offered == 0 : offered == n) && offeredNs == n;
                        log("RESULT leg P2 (startup): Mort Myre box hunts " + n + ", offered to a bot without Nature Spirit " + offered + ", with it started " + offeredNs + " [" + names + "], Nature Spirit implemented " + nsImpl + " -> " + (ok ? "PASS" : "FAIL") + " (want " + (Gates.REACH_MYRE ? "0 / all, flag on" : "all / all, flag off") + ")");
                        if (ok) pass++; else fail++;
                    }
                    if (want.isEmpty() || want.contains("P3")) { // R4: the stronghold gate from the built-in record (live seed: no record in the file)
                        Location o = NavGrid.bigGateOrigin(2460, 3383);
                        int in = 0, out = 0; try { in = NavPlanner.regionAt(at(2461, 3388)); out = NavPlanner.regionAt(at(2461, 3378)); } catch (Throwable ignored) { }
                        boolean ok = NavGrid.REACH_GATES ? o != null : true;
                        log("RESULT leg P3 (startup): bigGateOrigin(2460,3383) " + o + ", gate tiles on record " + NavGrid.BIG_FOUND.size() + ", planner region inside 2461,3388 " + in + " / outside 2461,3378 " + out + (in > 0 && in == out ? " (joined)" : " (not joined)")
                                + " -> " + (ok ? "PASS" : "FAIL") + " (want " + (NavGrid.REACH_GATES ? "an origin, flag on" : "info only, flag off") + ")");
                        if (ok) pass++; else fail++;
                    }
                    if (want.isEmpty() || want.contains("P4")) { // R2: Shilo's bank closed to bots
                        boolean in = Gates.canEnter(probe, at(2852, 2955));
                        boolean ok = Gates.REACH_SHILO ? !in : true;
                        log("RESULT leg P4 (startup): Gates.canEnter(bot, Shilo bank 2852,2955) " + in + " (" + Gates.missing(probe, at(2852, 2955)) + ") -> " + (ok ? "PASS" : "FAIL") + " (want " + (Gates.REACH_SHILO ? "false, flag on" : "info only, flag off") + ")");
                        if (ok) pass++; else fail++;
                    }
                } finally { try { probe.getProperties().setTeleportLocation(at(3210, 3420)); probeNs.getProperties().setTeleportLocation(at(3210, 3420)); } catch (Throwable ignored) { } }
            }

            boolean verdict(boolean arrived, String how) {
                Location h = bot.getLocation();
                int resc = Watchdog.rescued - rescued0;
                List<String> why = new ArrayList<>();
                if (!arrived && !leg.expectHalt) why.add("not within 3");
                if (leg.expectHalt && !arrived) why.add("not halted at the Nature Spirit rule");
                if (leg.expectHalt && arrived && h.getDistance(leg.to) <= 3) why.add("walked in without Nature Spirit");
                if (resc > 0) why.add(resc + " watchdog rescue(s)");
                if (jumps > 0) why.add(jumps + " teleport jump(s)");
                if (leg.bridge && bridgeHops >= 5) why.add(bridgeHops + " 'to the bridge' vias");
                boolean ok = why.isEmpty();
                if (ok) pass++; else fail++;
                log("RESULT leg " + leg.id + " rep " + rep + " " + leg.from.getX() + "," + leg.from.getY() + " -> " + leg.to.getX() + "," + leg.to.getY() + " att+str " + (leg.att + leg.str) + ": " + (ok ? "PASS" : "FAIL " + why) + "; " + how
                        + ", at " + h + " (" + (int) h.getDistance(leg.to) + " off), bridge vias " + bridgeHops + ", limit " + leg.secs + " s, " + flags());
                try { bot.getPulseManager().clear(); bot.getWalkingQueue().reset(); bot.getProperties().setTeleportLocation(at(3210, 3420)); } catch (Throwable ignored) { }
                bot = null; r = null;
                return false;
            }
        });
    }
}
