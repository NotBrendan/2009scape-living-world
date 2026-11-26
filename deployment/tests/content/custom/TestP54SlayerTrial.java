package content.custom;

import content.global.skill.slayer.SlayerManager;
import content.global.skill.slayer.Tasks;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.*;

/**
 * Trial only. Needs -Dbots.slayer=true.
 * -Dtest.p54walk=true (with -Dslayer.walkOnly=true -Dsmartbots.count=0): two bare bots, Lumbridge 3222,3218 and Falador 2965,3380,
 *   driven by Slayer.go (the errand's own walker step) to Turael 2931,3536. Logs the track every 30 s, a stall (same tile 60 s),
 *   ARRIVED or STUCK (15 min cap); at arrival the guarded login + generate + re-roll.
 * -Dtest.p54bots=true (with -Dsmartbots.count=4 -Dslayer.trial=true): the four smart bots get 20k coins; each is sent on the real
 *   errand (slay = 1) when it is between trips; then 40 min of watching: task, amount, last job, completion, back to jobs.
 *   Bot 4 gets its task swapped to SCORPIONS (no hunt) to show the 15-minute abandon.
 */
public final class TestP54SlayerTrial implements StartupListener {
    static void log(String s) { System.out.println("[TestP54SlayerTrial] " + s); }

    @Override public void startup() {
        if (Boolean.getBoolean("test.p54walk")) walk();
        if (Boolean.getBoolean("test.p54bots")) bots();
    }

    static void walk() {
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer[] b; String[] from = { "Lumbridge", "Falador" }; Location[] start = { Location.create(3222, 3218, 0), Location.create(2965, 3380, 0) };
            boolean[] done = new boolean[2]; Location[] last = new Location[2]; int[] still = new int[2]; int[] stalls = new int[2]; int[] arrivedAt = new int[2];
            @Override public boolean pulse() {
                t++;
                try {
                    if (t < 120) return false;
                    if (t == 120) { b = new AIPlayer[] { TestCR1.bot(start[0], 40, 30, 30, 30), TestCR1.bot(start[1], 40, 30, 30, 30) }; log("WALK start Lumbridge " + b[0].getUsername() + ", Falador " + b[1].getUsername() + " walkOnly " + Slayer.WALK_ONLY); return false; }
                    int k = t - 120;
                    for (int i = 0; i < 2; i++) {
                        if (done[i]) continue;
                        Location l = b[i].getLocation();
                        if (l.getDistance(Slayer.TURAEL) <= 3) {
                            done[i] = true; arrivedAt[i] = k;
                            SlayerManager m = Slayer.ensure(b[i]);
                            Tasks tk = Slayer.pick(() -> { m.generate(content.global.skill.slayer.Master.TURAEL); return m.hasTask() ? m.getTask() : null; }, m::clear, Slayer.served());
                            log("WALK ARRIVED " + from[i] + " after " + (k * 0.6) + " s at " + l + " stalls " + stalls[i] + "; task " + (tk == null ? "none" : tk.name() + " x" + m.getAmount()) + " hooks " + TestP54Slayer.hooks(b[i]));
                            continue;
                        }
                        if (k % 3 == 0) Slayer.go(b[i], Slayer.TURAEL);
                        if (last[i] != null && l.equals(last[i])) { if (++still[i] == 100) { stalls[i]++; log("WALK STALL " + from[i] + " at " + l + " dist " + (int) l.getDistance(Slayer.TURAEL)); } }
                        else still[i] = 0;
                        last[i] = l;
                        if (k % 50 == 0) log("WALK track " + from[i] + " t=" + (int) (k * 0.6) + "s " + l + " dist " + (int) l.getDistance(Slayer.TURAEL));
                    }
                    if (done[0] && done[1] || k >= 1500) {
                        for (int i = 0; i < 2; i++) if (!done[i]) log("WALK STUCK " + from[i] + " at " + b[i].getLocation() + " dist " + (int) b[i].getLocation().getDistance(Slayer.TURAEL));
                        log((done[0] && done[1] ? "PASS" : "FAIL") + " walk: Lumbridge " + (done[0] ? "arrived" : "stuck") + ", Falador " + (done[1] ? "arrived" : "stuck"));
                        log("DONE"); return true;
                    }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }

    static void bots() {
        GameWorld.getPulser().submit(new Pulse(5) { // 3 s
            int t; long t0; final Map<String, String> state = new TreeMap<>(); final Set<String> sent = new HashSet<>(), assigned = new HashSet<>(), completed = new HashSet<>(), back = new HashSet<>(), boosted = new HashSet<>();
            final Map<String, Integer> start = new HashMap<>(), lastAmt = new HashMap<>(); String swapped; boolean swappedDone;
            @Override public boolean pulse() {
                t++;
                try {
                    if (SmartAdventurers.BRAINS.size() < 4 && t < 400) return false;
                    if (t0 == 0) { t0 = System.currentTimeMillis(); for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) { Ledger.Wallet.give(br.bot, 20_000); } log("BOTS " + SmartAdventurers.BRAINS.keySet() + " given 20k each; slayer ON " + Slayer.ON + " trial " + Slayer.TRIAL); }
                    long el = System.currentTimeMillis() - t0;
                    for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) {
                        String n = br.bot.getUsername();
                        boolean between = br.runner == null && !AfkTasks.RUNNING.containsKey(n.toLowerCase(Locale.ROOT)) && br.shopAt == null && br.mill == 0 && !br.delivering && br.agiStep < 0 && br.starUntil == 0 && br.companion == null && !QuestRunner.active(br.bot);
                        if (!sent.contains(n) && br.slay == 0 && br.slaySt.had == null && between && Slayer.levelOk(br.bot.getProperties().getCurrentCombatLevel())) { if (Ledger.Wallet.coins(br.bot) < 20_000) Ledger.Wallet.give(br.bot, 20_000); br.slay = 1; br.slaySt.start = System.currentTimeMillis(); sent.add(n); log("BOTS sent " + n + " (combat " + br.bot.getProperties().getCurrentCombatLevel() + ") from " + br.bot.getLocation()); }
                        if (br.slaySt.had != null && assigned.add(n)) { start.put(n, Slayer.amount(br.bot)); log("BOTS assigned " + n + " " + br.slaySt.had.name() + " x" + Slayer.amount(br.bot) + " at " + (el / 1000) + " s");
                            if (swapped == null && assigned.size() == 2) { swapped = n; SlayerManager m = SlayerManager.getInstance(br.bot); m.setTask(Tasks.SCORPIONS); br.slaySt.had = Tasks.SCORPIONS; br.slaySt.names = Slayer.npcNames(Tasks.SCORPIONS); br.slaySt.lastAmount = m.getAmount(); br.slaySt.lastProgress = System.currentTimeMillis(); log("BOTS abandon probe: " + n + " task swapped to SCORPIONS x" + m.getAmount()); } }
                        int a = Slayer.amount(br.bot); Integer pa = lastAmt.put(n, a);
                        if (pa != null && a < pa) log("BOTS kill " + n + " " + pa + " -> " + a + " job " + (br.lastJob != null ? br.lastJob.label() : "?"));
                        if (br.slaySt.had != null && br.lastJob != null && Slayer.match(br, br.lastJob) && boosted.add(n)) log("BOTS " + n + " picked matching hunt " + br.lastJob.label());
                        if (assigned.contains(n) && br.slaySt.had == null && !completed.contains(n)) { completed.add(n); log("BOTS task over for " + n + " (" + (n.equals(swapped) ? "abandon probe" : "complete or abandoned") + ") at " + (el / 1000) + " s"); }
                        if (completed.contains(n) && br.runner != null && back.add(n)) log("BOTS " + n + " back on jobs: " + (br.lastJob != null ? br.lastJob.label() : "?"));
                        String s = "slay " + br.slay + " task " + (br.slaySt.had == null ? "-" : br.slaySt.had.name() + " " + a) + " job " + (br.lastJob != null ? br.lastJob.label() : "?") + " at " + br.bot.getLocation();
                        if (t % 100 == 0) log("BOTS " + n + " " + s);
                    }
                    if (el > 100 * 60_000L || Slayer.COMPLETED >= 2 || Slayer.COMPLETED >= 1 && completed.size() >= 4 && back.size() >= 4) { // P71: abandons alone no longer end the trial early (p65 rerun stopped at 34 min, 0 completions)
                        log("BOTS summary sent " + sent.size() + " assigned " + assigned.size() + " boosted " + boosted.size() + " over " + completed.size() + " back " + back.size()
                            + " counters errands " + Slayer.ERRANDS + " assigned " + Slayer.ASSIGNED + " completed " + Slayer.COMPLETED + " abandoned " + Slayer.ABANDONED + " skipped " + Slayer.SKIPPED + " gaveup " + Slayer.GAVEUP + " trips " + Slayer.TRIPS + " taskKills " + Slayer.KILLS + " noMatch " + Slayer.NOMATCH);
                        boolean ok = assigned.size() >= 3 && Slayer.COMPLETED >= 1 && Slayer.KILLS > 0 && Slayer.TRIPS >= 1 && boosted.size() >= 1; // v1.1: real completions + task kills
                        log((ok ? "PASS" : "FAIL") + " bots"); log("DONE"); return true;
                    }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
