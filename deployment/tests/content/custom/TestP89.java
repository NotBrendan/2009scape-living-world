package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.p89=true; needs TestCR1.java; run with -Dnav=true -Dwatchdog.min=5 like live): P89, the Falador wall nook
 * 2937,3385 (the design notes). A NavWalker route kept across a climb and an emergency teleport sends the
 * bot back at the stale last hop (2884,3396) from the Falador west bank; the engine walk ends in the wall column.
 * Pure: P89.jumped edges. Leg N (NavWalker only): plan 2896,3408 -> 2884,3396, teleport to 2946,3370, step again.
 * Leg R (runner): blue-dragon hunt from the Falador west bank 2946,3370, climb down, teleport to 2946,3370, goTo(TO_SPOT), 450 ticks.
 * Leg G (regression): fresh blue-dragon hunt from 2965,3380 to the ladder: no "[P89] stale-route" on a plain walk.
 * -Dp89.expect=on: SUMMARY PASS when every check is OK; off: PASS when N and R reproduce the old bug.
 */
public final class TestP89 implements StartupListener {
    static void log(String s) { System.out.println("[TestP89] " + s); }
    static final Location LADDER = Location.create(2884, 3396, 0);
    static Location bank() { return Location.create(2946, 3370, 0); } // a fresh object each time: a teleport may keep and move the Location it was given

    static SmartAdventurers.Job hunt(String name) {
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(name)) return j;
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.name.toLowerCase().contains(name.toLowerCase())) return j;
        return null;
    }

    static AIPlayer smart(Location from, String leg) {
        AIPlayer b = TestCR1.bot(from, 85, 85, 85, 85);
        SmartAdventurers.enlist(b, false, false, -1, 0, 0, "test bot (p89 leg " + leg + ")");
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(b.getUsername().toLowerCase(java.util.Locale.ROOT));
        br.nextThink = Long.MAX_VALUE;
        b.getInventory().remove(new Item(995, b.getInventory().getAmount(995))); b.getBank().remove(new Item(995, b.getBank().getAmount(995)));
        b.getInventory().add(new Item(995, 300));
        for (int f = 0; f < 6; f++) b.getInventory().add(new Item(379, 1)); // lobsters: no food stop
        b.getBank().add(new Item(1590, 1)); b.getEquipment().replace(new Item(1540, 1), 5); // dusty key in the bank, anti-dragon shield (live blue-dragon hunters)
        try { b.getProperties().setTeleportLocation(from); } catch (Throwable ignored) { }
        return b;
    }

    static boolean startHunt(AIPlayer b) {
        SmartAdventurers.Brain br = br(b); SmartAdventurers.Job j = hunt("Blue dragon");
        if (br == null || j == null) { log("no brain or no blue-dragon hunt"); return false; }
        br.skipShopOnce = true; br.armourChecked = true; br.exchangeChecked = true;
        log(b.getUsername() + " at " + b.getLocation() + " hunt " + j.label() + " spot " + j.hunt.spot + " canEnter " + Gates.canEnter(b, j.hunt.spot));
        br.start(j); br.nextThink = Long.MAX_VALUE;
        return br.runner != null;
    }

    static SmartAdventurers.Brain br(AIPlayer b) { return SmartAdventurers.BRAINS.get(b.getUsername().toLowerCase(java.util.Locale.ROOT)); }
    static boolean nook(Location a) { return a.getZ() == 0 && a.getX() == 2937 && a.getY() >= 3378 && a.getY() <= 3385; }
    static boolean under(Location a) { return a.getY() > 6400; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p89")) return;
        final boolean expectOn = "on".equalsIgnoreCase(System.getProperty("p89.expect", "on"));
        log("start: nav.p89Fix=" + P89.ON + " expect=" + (expectOn ? "on" : "off"));
        // pure part
        final boolean pure = P89.jumped(2886, 3398, 2946, 3370) && !P89.jumped(2946, 3370, 2952, 3384)
            && !P89.jumped(3000, 3300, 3025, 3300) && P89.jumped(3000, 3300, 3026, 3300)
            && !P89.jumped(3000, 3300, 3000, 3325) && P89.jumped(3000, 3300, 3000, 3326);
        log("pure jumped checks " + (pure ? "OK" : "FAIL"));
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, mark, stale0, rescued0; AIPlayer nb, rb, gb; Location hop1, rTo;
            boolean nOk, nBug, rOk, rBug, gOk, rNook, rNorth, rSent; int nookTicks; String rTrack = "";
            @Override public boolean pulse() {
                try {
                    t++;
                    if (!NavPlanner.built) { if (t % 100 == 0) log("waiting for the planner (tick " + t + ")"); if (t > 3000) { log("SUMMARY FAIL planner never built"); log("DONE"); return true; } return false; }
                    switch (phase) {
                        case 0: // leg N: NavWalker only
                            if (t < 200) return false;
                            nb = TestCR1.bot(Location.create(2896, 3408, 0), 85, 85, 85, 85); mark = t; phase = 1; return false;
                        case 1:
                            if (t - mark < 4) return false;
                            phase = 2;
                            hop1 = NavWalker.step(nb, Location.create(2884, 3396, 0));
                            log("leg N: at " + nb.getLocation() + " first hop " + hop1);
                            nb.getProperties().setTeleportLocation(bank()); mark = t; return false;
                        case 2: {
                            if (t - mark < 3) return false;
                            phase = 3;
                            stale0 = P89.STALE.get();
                            Location here = nb.getLocation(), hop = NavWalker.step(nb, Location.create(2884, 3396, 0));
                            int stale = P89.STALE.get() - stale0;
                            double fromBot = hop == null ? -1 : hop.getDistance(here), toLadder = hop == null ? -1 : hop.getDistance(Location.create(2884, 3396, 0));
                            nBug = hop != null && toLadder <= 3;
                            nOk = hop != null && fromBot <= 25 && stale == 1 && (hop.getX() >= here.getX() - 2 || hop.getY() >= here.getY());
                            log("RESULT leg N: at " + here + " hop " + hop + " (" + Math.round(fromBot) + " from bot, " + Math.round(toLadder) + " from ladder), stale-route +" + stale + " -> " + (nOk ? "FRESH" : nBug ? "STALE HOP (bug)" : "other"));
                            NavWalker.forget(nb); nb.getProperties().setTeleportLocation(Location.create(3222, 3218, 0));
                            rb = smart(bank(), "R"); // like live: the planner walk from the Falador west bank to the ladder, so NavWalker holds a route at the climb mark = t; phase = 3; return false;
                        }
                        case 3: // leg R: the runner
                            if (t - mark < 6) return false;
                            if (!startHunt(rb)) { log("RESULT leg R: no runner"); phase = 6; return false; }
                            rTo = hunt("Blue dragon").hunt.spot; mark = t; phase = 4; return false;
                        case 4: {
                            SmartAdventurers.Brain br = br(rb);
                            if (br.runner == null) { log("RESULT leg R: runner ended before the climb (" + rb.getLocation() + ")"); phase = 6; return false; }
                            if (under(rb.getLocation())) {
                                log("leg R: climbed down at tick " + (t - mark) + ", now " + rb.getLocation() + " | " + br.runner.last + "; teleport to " + bank());
                                rb.getProperties().setTeleportLocation(bank()); mark = t; phase = 5; return false;
                            }
                            if (t - mark > 900) { log("RESULT leg R: never climbed down, at " + rb.getLocation() + " | " + br.runner.last); phase = 6; }
                            return false;
                        }
                        case 5: {
                            SmartAdventurers.Brain br = br(rb);
                            if (!rSent) {
                                if (br.runner == null) { log("RESULT leg R: runner gone after the teleport"); phase = 6; return false; }
                                Location lt = rb.getLocation();
                                boolean onTile = lt.getX() == 2946 && lt.getY() == 3370 && lt.getZ() == 0;
                                if (!onTile && t - mark <= 40) { // land on the bank tile itself (like the live bots) and stand still there before the walk
                                    if (!under(lt) && t % 4 == 0) { try { rb.getPulseManager().clear(); rb.getWalkingQueue().reset(); rb.getProperties().setTeleportLocation(bank()); } catch (Throwable ignored) { } }
                                    return false;
                                }
                                rSent = true; mark = t;
                                stale0 = P89.STALE.get(); rescued0 = Watchdog.rescued;
                                NavWalker.State ks = NavWalker.STATES.get(NavWalker.key(rb));
                                log("leg R: kept NavWalker state " + (ks == null ? "none" : "dest " + ks.dest + " hop " + ks.hop + "/" + (ks.route == null ? "-" : String.valueOf(ks.route.hops.size())) + " last " + ks.last));
                                br.runner.goTo(AfkTasks.State.TO_SPOT, rTo);
                                log("leg R: at " + rb.getLocation() + " goTo(TO_SPOT, " + rTo + "), runner dest " + br.runner.dest + " underDest " + br.runner.underDest);
                                return false;
                            }
                            Location at = rb.getLocation(); int age = t - mark;
                            if (nook(at)) { rNook = true; nookTicks++; }
                            if (at.getZ() == 0 && at.getY() > 3396 && !under(at)) rNorth = true;
                            if ((age < 30 || age % 25 == 0) && rTrack.length() < 3000) { NavWalker.State ks = NavWalker.STATES.get(NavWalker.key(rb)); rTrack += at.getX() + "," + at.getY() + (age < 30 ? "[" + (br.runner != null ? br.runner.last : "-") + (ks == null ? " ns" : " h" + ks.hop + (ks.route == null || ks.hop >= ks.route.hops.size() ? "" : "->" + ks.route.hops.get(ks.hop).getX() + "," + ks.route.hops.get(ks.hop).getY())) + "]" : "") + " "; }
                            if (age % 100 == 0) log("leg R tick " + age + ": at " + at + (br.runner != null ? " | " + br.runner.last : " | no runner"));
                            if (rNorth || under(at) || age >= 450 || br.runner == null) {
                                int rescues = Watchdog.rescued - rescued0, stale = P89.STALE.get() - stale0;
                                rOk = (rNorth || under(at)) && !rNook && rescues == 0;
                                rBug = rNook && !rNorth && !under(at);
                                log("RESULT leg R: " + (rOk ? "OUT (north gate or climbed)" : rBug ? "STUCK in the wall nook (bug)" : "other") + " at " + at + " in " + age + " ticks, nook ticks " + nookTicks + ", rescues " + rescues + ", stale-route +" + stale + ", arrive-forget " + P89.ARRIVE.get());
                                log("TRACK R: " + rTrack.trim());
                                if (br.runner != null) br.nextThink = Long.MAX_VALUE;
                                try { rb.getPulseManager().clear(); rb.getWalkingQueue().reset(); rb.getProperties().setTeleportLocation(Location.create(3222, 3218, 0)); } catch (Throwable ignored) { }
                                phase = 6;
                            }
                            return false;
                        }
                        case 6: // leg G: a plain walk, no false hits
                            gb = smart(Location.create(2965, 3380, 0), "G"); mark = t; phase = 7; return false;
                        case 7:
                            if (t - mark < 6) return false;
                            stale0 = P89.STALE.get();
                            if (!startHunt(gb)) { log("RESULT leg G: no runner"); phase = 9; return false; }
                            mark = t; phase = 8; return false;
                        case 8: {
                            SmartAdventurers.Brain br = br(gb); Location at = gb.getLocation(); int age = t - mark;
                            if (age % 100 == 0) log("leg G tick " + age + ": at " + at + (br.runner != null ? " | " + br.runner.last : ""));
                            if (under(at) || br.runner == null || age >= 900) {
                                int stale = P89.STALE.get() - stale0;
                                gOk = under(at) && stale == 0;
                                log("RESULT leg G: " + (under(at) ? "climbed down" : "did not climb") + " at " + at + " in " + age + " ticks, stale-route +" + stale + " -> " + (gOk ? "OK" : "FAIL"));
                                phase = 9;
                            }
                            return false;
                        }
                        default: {
                            boolean pass = expectOn ? pure && nOk && rOk && gOk : pure && nBug && rBug;
                            log("SUMMARY " + (pass ? "PASS" : "FAIL") + " p89Fix=" + P89.ON + " expect=" + (expectOn ? "on" : "off") + ": pure " + pure + ", N " + (nOk ? "fresh" : nBug ? "stale" : "other") + ", R " + (rOk ? "out" : rBug ? "nook" : "other") + ", G " + gOk + " (stale-route total " + P89.STALE.get() + ", arrive-forget " + P89.ARRIVE.get() + ")");
                            log("DONE"); return true;
                        }
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("SUMMARY FAIL error"); log("DONE"); return true; }
            }
        });
    }
}
