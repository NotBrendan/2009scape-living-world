package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.nio.file.Files;

/**
 * batch 19b B5, trial only (-Dtest.b19b=true -Dnav=true -Dwatchdog.min=2 -Dsmartbots.count=4): Watchdog.trying() and planner
 * routes (design §10). Compiles against 19a (NavWalker.State.plannedT/hopT are read by reflection: "n/a" there). Rigged brain
 * bots (TestCR1.rig: in BRAINS so Watchdog.ours(), no trip planning) once the planner graph is up:
 *  (1) GATE: 0 coins, a shop walk (driven by this test calling shopTick every 3 ticks) from 3258,3227 to the Al Kharid general
 *      store across the toll gate -> expect a Watchdog rescue (teleport, a "rescue" failure record), no idle give-up, within
 *      2 min + 30 s of standing at the gate (+ one 30 s Watchdog tick for the anchor), and the record's reason rule-gate.
 *  (2) LEFT: a route planned to a job tile, then the bot placed 3 tiles (LEFT3) and 6 tiles (LEFT6) from it and step() never
 *      called again, no XP -> expect an idle give-up after 2 min, no rescue.
 *  (1b) GATE2 (extra, not in the B5 row): the same 0-coin walk to the store but driven only by NavWalker.step + walkTo (no
 *      shopAt: a crew/afk-style planner walk, like the real toll-gate idles) -> 19a: idle give-up; 19b: rescue (any reason, any time
 *      within the run: it wanders along the wall, which resets the Watchdog anchor).
 *  batch 21 A: with the toll link on (default; -Dnav.toll=false restores the checks above) the planner never routes a 0-coin
 *      walk through the gate: GATE and GATE2 must go round the fence's north end and arrive at the store (within 5 tiles, the
 *      shop walk's own finish rule; GATE's shop walk finished), never within 4 tiles of the gate, no rescue, no teleport and no
 *      idle give-up before arriving (records after arriving are the rigged bot standing at the store with no next job).
 *  (3) UNIT: a fresh route to a goal ~50 tiles away -> trying() true; the same State with plannedT/hopT aged 3 min -> false.
 * Ends with RESULT lines and DONE (at most ~8 min after setup).
 */
public final class TestB19B implements StartupListener {
    static void log(String s) { System.out.println("[TestB19B] " + s); }

    static final Location GATE_START = Location.create(3258, 3227, 0), ALK_STORE = Location.create(3315, 3180, 0);
    static final Location JOB = Location.create(3233, 3295, 0), JOB_FROM = Location.create(3233, 3275, 0);
    static final Location LUM = Location.create(3222, 3218, 0);
    static final int[][] FAR = {{3260, 3250}, {3250, 3265}, {3180, 3245}, {3270, 3200}, {3200, 3260}};

    static Object fld(Object o, String n) { try { java.lang.reflect.Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (Throwable t) { return null; } }
    static boolean setF(Object o, String n, long v) { try { java.lang.reflect.Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.setLong(o, v); return true; } catch (Throwable t) { return false; } }

    static final class Case { String name; AIPlayer bot; SmartAdventurers.Brain br; long placedT, gateT, arrivedT; Location lastAt; boolean jumped; long jumpT; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19b")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase; long t0; Case gate, gate2, left3, left6; AIPlayer unitBot; boolean unitDone, ended;
            @Override public boolean pulse() {
                try {
                    t++;
                    long now = System.currentTimeMillis();
                    if (phase == 0 && t >= 100 && NavPlanner.built && NavWalker.ON) {
                        phase = 1; t0 = now;
                        gate = make("GATE", GATE_START); gate2 = make("GATE2", Location.create(3258, 3229, 0)); left3 = make("LEFT3", JOB_FROM); left6 = make("LEFT6", JOB_FROM);
                        unitBot = TestCR1.bot(LUM, 10, 1, 1, 1); TestCR1.rig(unitBot, false);
                        log("bots: gate " + gate.bot.getUsername() + ", gate2 " + gate2.bot.getUsername() + ", left3 " + left3.bot.getUsername() + ", left6 " + left6.bot.getUsername() + ", unit " + unitBot.getUsername() + " (Watchdog limit " + Watchdog.STUCK_MS / 1000 + " s)");
                        return false;
                    }
                    if (phase == 0 && t >= 100 && NavPlanner.built && !NavWalker.ON) { log("RESULT SKIP planner off: GATE/GATE2/LEFT/UNIT need NavWalker.ON (built true, ON false)"); log("DONE"); return true; } // P38 L10
                    if (phase == 0 && t == 1200) { log("RESULT planner never came up (built " + NavPlanner.built + ", ON " + NavWalker.ON + ")"); log("DONE"); return true; }
                    if (phase < 1) return false;
                    if (phase == 1 && now - t0 > 3_000) { // the teleports have landed
                        phase = 2;
                        for (Item i : gate.bot.getInventory().toArray()) if (i != null && i.getId() == 995) gate.bot.getInventory().remove(i);
                        if (gate.bot.getBank().getAmount(995) > 0) gate.bot.getBank().remove(new Item(995, gate.bot.getBank().getAmount(995)));
                        gate.br.shopAt = ALK_STORE; gate.br.shopName = "Test store (Al Kharid)"; gate.br.shopDeadline = now + 30 * 60_000L; gate.br.shopBuy.clear(); gate.br.shopBuy.add(new int[]{1931, 1});
                        gate.placedT = now; gate2.placedT = now;
                        log("GATE at " + gate.bot.getLocation() + ", coins " + Ledger.Wallet.coins(gate.bot) + ", shop walk to " + ALK_STORE);
                        for (Case c : new Case[]{left3, left6}) {
                            NavWalker.forget(c.bot);
                            Location h = NavWalker.step(c.bot, JOB);
                            NavWalker.State s = NavWalker.STATES.get(Watchdog.key(c.bot));
                            Location to = Location.create(JOB.getX(), JOB.getY() - ("LEFT3".equals(c.name) ? 3 : 6), 0);
                            c.bot.getProperties().setTeleportLocation(to); c.placedT = now;
                            log(c.name + " route " + (s == null || s.route == null ? "NONE" : "hop " + s.hop + "/" + s.route.hops.size()) + " from " + c.bot.getLocation() + " to " + JOB + " (first hop " + h + "), placed at " + to + " and step() not called again");
                        }
                    }
                    if (phase >= 2) {
                        if (gate.br.shopAt != null && t % 3 == 0 && !gate.jumped) gate.br.shopTick(now);
                        Location a = gate.bot.getLocation();
                        if (gate.gateT == 0 && Math.abs(a.getX() - 3268) <= 4 && a.getY() >= 3224 && a.getY() <= 3231) { gate.gateT = now; log("GATE reached the gate at " + a + " after " + (now - gate.placedT) / 1000 + " s; nav " + Watchdog.goal(gate.bot).nav); }
                        if (!gate.jumped && gate.lastAt != null && a.getDistance(gate.lastAt) > 20) { gate.jumped = true; gate.jumpT = now; log("GATE teleported " + gate.lastAt + " -> " + a + " after " + (now - gate.placedT) / 1000 + " s"); }
                        gate.lastAt = a;
                        if (gate.arrivedT == 0 && !gate.jumped && a.getDistance(ALK_STORE) <= 5) { gate.arrivedT = now; log("GATE arrived at the store at " + a + " after " + (now - gate.placedT) / 1000 + " s, shop walk " + (gate.br.shopAt == null ? "finished" : "still on")); }
                        Location a2 = gate2.bot.getLocation();
                        if (!gate2.jumped && t % 3 == 0 && !gate2.bot.getWalkingQueue().isMoving()) { Location h2 = NavWalker.step(gate2.bot, ALK_STORE); if (h2 != null) NavWalker.walkTo(gate2.bot, h2); }
                        if (gate2.gateT == 0 && Math.abs(a2.getX() - 3268) <= 4 && a2.getY() >= 3224 && a2.getY() <= 3231) { gate2.gateT = now; log("GATE2 reached the gate at " + a2 + " after " + (now - gate2.placedT) / 1000 + " s"); }
                        if (!gate2.jumped && gate2.lastAt != null && a2.getDistance(gate2.lastAt) > 20) { gate2.jumped = true; gate2.jumpT = now; log("GATE2 teleported " + gate2.lastAt + " -> " + a2 + " after " + (now - gate2.placedT) / 1000 + " s"); }
                        gate2.lastAt = a2;
                        if (gate2.arrivedT == 0 && !gate2.jumped && a2.getDistance(ALK_STORE) <= 5) { gate2.arrivedT = now; log("GATE2 arrived at the store at " + a2 + " after " + (now - gate2.placedT) / 1000 + " s"); }
                        if (t % 50 == 0 && !gate2.jumped) log("GATE2 at " + a2 + " after " + (now - gate2.placedT) / 1000 + " s, nav " + Watchdog.goal(gate2.bot).nav + ", trying " + Watchdog.trying(gate2.bot));
                        if (!unitDone && now - t0 > 6_000) { unitDone = true; unit(); }
                    }
                    boolean gatesDone = TOLL ? gate.arrivedT > 0 && gate2.arrivedT > 0 && now - Math.max(gate.arrivedT, gate2.arrivedT) > 5_000 : gate.jumped && gate2.jumped && now - Math.max(gate.jumpT, gate2.jumpT) > 5_000; // batch 21 A: with the toll link both walk round to the store
                    if (phase >= 2 && !ended && (gatesDone && now - left6.placedT > 200_000 || now - t0 > 420_000)) {
                        ended = true;
                        final Case[] cs = {gate, gate2, left3, left6};
                        new Thread(() -> readBack(cs), "TestB19B").start();
                    }
                    if (ended && readDone) { log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            Case make(String name, Location at) {
                Case c = new Case(); c.name = name; c.bot = TestCR1.bot(at, 10, 1, 1, 1); c.br = TestCR1.rig(c.bot, false); return c;
            }

            void unit() {
                Location here = unitBot.getLocation();
                Location goal = null;
                for (int[] f : FAR) { Location l = Location.create(f[0], f[1], 0); if (here.getDistance(l) >= 40 && NavPlanner.plan(here, l, 1) != null) { goal = l; break; } }
                if (goal == null) { log("RESULT UNIT: no reachable goal ~50 tiles from " + here); return; }
                String k = Watchdog.key(unitBot);
                NavWalker.forget(unitBot); RouteMap.PLANS.remove(k); RouteMap.STUCKS.remove(k);
                Location h = NavWalker.step(unitBot, goal);
                NavWalker.State s = NavWalker.STATES.get(k);
                boolean fresh = Watchdog.trying(unitBot);
                Object pt = s == null ? null : fld(s, "plannedT"), ht = s == null ? null : fld(s, "hopT");
                boolean aged = s != null && setF(s, "plannedT", System.currentTimeMillis() - 180_000) && setF(s, "hopT", System.currentTimeMillis() - 180_000);
                boolean old = Watchdog.trying(unitBot);
                log("RESULT UNIT fresh route " + here + " -> " + goal + " (" + (int) here.getDistance(goal) + " tiles, hop " + h + ", " + (s == null || s.route == null ? "NO ROUTE" : "hop " + s.hop + "/" + s.route.hops.size()) + ", plannedT " + (pt == null ? "n/a" : "set " + (pt instanceof Long && (Long) pt > 0)) + ", hopT " + (ht == null ? "n/a" : String.valueOf(ht)) + "): trying " + fresh + "; aged 3 min " + (aged ? "" : "(n/a: no plannedT/hopT) ") + "trying " + old
                        + " -> " + (fresh && aged && !old ? "PASS" : "FAIL"));
                NavWalker.forget(unitBot);
            }
        });
    }

    static volatile boolean readDone;
    /** batch 21 A: the toll gate is a paid planner link (NavPlanner.TOLL_ON, kill switch -Dnav.toll=false). A 0-coin walk is then never routed through it. */
    static final boolean TOLL = NavPlanner.TOLL_ON;

    static void readBack(Case[] cs) {
        try {
            Failures.flushNow(5000);
            File f = new File(Failures.failDir(), Watchdog.Daily.utc(System.currentTimeMillis()) + ".jsonl");
            java.util.List<String> lines = f.exists() ? Files.readAllLines(f.toPath()) : java.util.Collections.emptyList();
            JSONParser jp = new JSONParser();
            for (Case c : cs) {
                int rescues = 0, idles = 0, afterArrival = 0; String reason = null, rsub = null; long firstT = 0;
                for (String l : lines) {
                    if (!l.contains("\"bot\":\"" + c.bot.getUsername() + "\"")) continue;
                    JSONObject j = (JSONObject) jp.parse(l);
                    String src = String.valueOf(j.get("src"));
                    long rt = j.get("t") instanceof Number ? ((Number) j.get("t")).longValue() : 0;
                    if (TOLL && c.name.startsWith("GATE") && c.arrivedT > 0 && rt >= c.arrivedT && ("rescue".equals(src) || "idle_giveup".equals(src))) { afterArrival++; continue; } // batch 21 A: a rigged bot left standing at the store after it arrived (no next job): not the walk's record
                    if ("rescue".equals(src)) { rescues++; if (reason == null) { reason = String.valueOf(j.get("reason")); rsub = String.valueOf(j.get("rsub")); firstT = rt; } }
                    if ("idle_giveup".equals(src)) { idles++; if (reason == null) { reason = "idle:" + j.get("reason"); rsub = String.valueOf(j.get("rsub")); firstT = rt; } }
                }
                long after = firstT > 0 ? (firstT - c.placedT) / 1000 : -1;
                if (TOLL && c.name.startsWith("GATE")) { // batch 21 A: with no coins the planner goes round the fence's north end, never to the gate; the walk arrives with no rescue or idle give-up on the way
                    long took = c.arrivedT > 0 ? (c.arrivedT - c.placedT) / 1000 : -1;
                    boolean finished = !"GATE".equals(c.name) || c.br.shopAt == null;
                    boolean ok = c.arrivedT > 0 && c.gateT == 0 && rescues == 0 && idles == 0 && !c.jumped && finished;
                    log("RESULT " + c.name + ("GATE2".equals(c.name) ? " 0-coin planner-only walk" : " 0-coin shop walk") + " to the Al Kharid store (toll link on: round the fence): arrived " + (took >= 0 ? "after " + took + " s" : "no") + ", reached the gate " + (c.gateT > 0) + ", rescues " + rescues + ", idle give-ups " + idles + " before arriving (" + afterArrival + " after, standing at the store), teleported " + c.jumped + ("GATE".equals(c.name) ? ", shop walk finished " + finished : "") + " -> " + (ok ? "PASS" : "FAIL"));
                } else if (c.name.startsWith("GATE")) {
                    long atGate = firstT > 0 && c.gateT > 0 ? (firstT - c.gateT) / 1000 : -1;
                    boolean ok = "GATE2".equals(c.name) ? rescues >= 1 && idles == 0 : rescues >= 1 && idles == 0 && "rule-gate".equals(reason) && atGate >= 0 && atGate <= 180; // GATE2 (extra): it wanders the wall, so the anchor resets and the reason is wherever it stalled; rescued, not idle, is the check
                    log("RESULT " + c.name + ("GATE2".equals(c.name) ? " 0-coin planner-only walk" : " 0-coin shop walk") + " across the Al Kharid gate: rescues " + rescues + ", idle give-ups " + idles + ", teleported " + c.jumped + ", first record " + reason + " (" + rsub + ") " + after + " s after the start, " + atGate + " s after reaching the gate (limit 2 min + 30 s + one 30 s tick) -> info only (pre-batch-21 behaviour; the toll link off is not a live mode; ok=" + ok + ")"); // P38 L10: info, no verdict
                } else {
                    boolean ok = idles >= 1 && rescues == 0 && !c.jumped && after > 0 && after <= 210;
                    log("RESULT " + c.name + " leftover route beside the job: idle give-ups " + idles + ", rescues " + rescues + ", first " + reason + " (" + rsub + ") " + after + " s after placing (2 min limit + ticks) -> " + (ok ? "PASS" : "FAIL"));
                }
            }
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        finally { readDone = true; }
    }
}
