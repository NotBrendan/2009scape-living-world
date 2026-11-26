package content.custom;

import content.global.skill.slayer.Tasks;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.*;

/**
 * Trial only (-Dtest.p54pick=true): slayer v1 pure functions (allow-list, re-roll max 3, abandon 15 min, errand gate, boost),
 * the flag (expects -Dp54.expectOn=true|false to match Slayer.ON), the flag-off no-login rule, and the busy-list grep over the
 * source given in -Dp54.src (every line that names `mill != 0` / `mill == 0` outside millTick also names `slay`).
 */
public final class TestP54SlayerPick implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestP54SlayerPick] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "ok   " : "FAIL ") + what); }

    static java.util.function.Supplier<Tasks> seq(Tasks... ts) { int[] i = {0}; return () -> ts[Math.min(i[0]++, ts.length - 1)]; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p54pick")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 120) return false;
                try { checks(); } catch (Throwable ex) { fail++; log("error " + ex); ex.printStackTrace(System.out); }
                log((fail == 0 ? "PASS" : "FAIL") + " " + pass + " ok, " + fail + " failed");
                log("DONE");
                return true;
            }
        });
    }

    static void checks() throws Exception {
        // flag
        check("flag(null) false", !Slayer.flag(null));
        check("flag(\"false\") false", !Slayer.flag("false"));
        check("flag(\"true\") true", Slayer.flag("true"));
        String exp = System.getProperty("p54.expectOn");
        if (exp != null) check("Slayer.ON == " + exp + " (bots.slayer=" + System.getProperty("bots.slayer") + ")", Slayer.ON == Boolean.parseBoolean(exp));
        // allow-list
        check("ALLOW size 13", Slayer.ALLOW.size() == 13);
        for (Tasks d : new Tasks[] { Tasks.BANSHEE, Tasks.CAVE_BUG, Tasks.CAVE_CRAWLERS, Tasks.CAVE_SLIMES, Tasks.CRAWLING_HAND, Tasks.DESERT_LIZARDS, Tasks.ICE_FIENDS, Tasks.KALPHITES, Tasks.MINOTAURS, Tasks.SCORPIONS })
            check("deny " + d.name(), !Slayer.allowed(d, Slayer.ALLOW));
        check("allow COWS", Slayer.allowed(Tasks.COWS, Slayer.ALLOW));
        check("allowed(null) false", !Slayer.allowed(null, Slayer.ALLOW));
        // re-roll
        int[] clears = {0};
        Tasks r = Slayer.pick(seq(Tasks.SCORPIONS, Tasks.BANSHEE, Tasks.COWS), () -> clears[0]++, Slayer.ALLOW);
        check("re-roll: 2 bad then COWS -> COWS, 2 clears", r == Tasks.COWS && clears[0] == 2);
        clears[0] = 0; int[] rolls = {0};
        r = Slayer.pick(() -> { rolls[0]++; return Tasks.SCORPIONS; }, () -> clears[0]++, Slayer.ALLOW);
        check("re-roll: never allowed -> null after 1+3 rolls (" + rolls[0] + "), 4 clears", r == null && rolls[0] == 1 + Slayer.MAX_REROLLS && clears[0] == 4);
        clears[0] = 0; rolls[0] = 0;
        r = Slayer.pick(() -> { rolls[0]++; return null; }, () -> clears[0]++, Slayer.ALLOW);
        check("re-roll: generate gives nothing -> null, 4 rolls", r == null && rolls[0] == 4);
        r = Slayer.pick(seq(Tasks.WOLVES), () -> { }, Slayer.ALLOW);
        check("re-roll: first allowed kept", r == Tasks.WOLVES);
        // P80 openCheck: flag default off, OPEN_REROLLS extra rolls into a narrowed allow set
        String oc = System.getProperty("p80.expectOpen");
        if (oc != null) check("OPEN_CHECK == " + oc, Slayer.OPEN_CHECK == Boolean.parseBoolean(oc));
        clears[0] = 0; rolls[0] = 0;
        r = Slayer.pick(() -> { rolls[0]++; return Tasks.SCORPIONS; }, () -> clears[0]++, java.util.EnumSet.of(Tasks.COWS), Slayer.OPEN_REROLLS);
        check("open re-roll: never open -> null after 1+" + Slayer.OPEN_REROLLS + " rolls (" + rolls[0] + ")", r == null && rolls[0] == 1 + Slayer.OPEN_REROLLS);
        r = Slayer.pick(seq(Tasks.BATS, Tasks.GHOSTS, Tasks.DOG, Tasks.BATS, Tasks.COWS), () -> { }, java.util.EnumSet.of(Tasks.COWS, Tasks.GOBLINS), Slayer.OPEN_REROLLS);
        check("open re-roll: closed BATS/GHOSTS/DOG re-rolled to COWS", r == Tasks.COWS);
        int[] sz8 = {0}; int[] amt8 = {30};
        r = Slayer.pickSmall(seq(Tasks.COWS, Tasks.GOBLINS), () -> amt8[0]--, () -> { }, java.util.EnumSet.of(Tasks.COWS, Tasks.GOBLINS), sz8, Slayer.OPEN_REROLLS);
        check("pickSmall overload keeps size re-roll", r != null && sz8[0] >= 1);
        // abandon
        long now = 100_000_000L;
        check("abandon 14:59 no", !Slayer.abandonDue(true, now - Slayer.ABANDON_MS + 1000, now));
        check("abandon 15:00 yes", Slayer.abandonDue(true, now - Slayer.ABANDON_MS, now));
        check("abandon no task no", !Slayer.abandonDue(false, now - 2 * Slayer.ABANDON_MS, now));
        check("abandon unset no", !Slayer.abandonDue(true, 0, now));
        check("level 69 ok, 70 no, 0 no", Slayer.levelOk(69) && !Slayer.levelOk(70) && !Slayer.levelOk(0));
        // errand gate
        check("errand: all good", Slayer.errandDue(true, false, now, 0, 0, 5_000, true, false, false, false, true));
        check("errand: flag off", !Slayer.errandDue(false, false, now, 0, 0, 5_000, true, false, false, false, true));
        check("errand: has task", !Slayer.errandDue(true, true, now, 0, 0, 5_000, true, false, false, false, true));
        check("errand: skip window", !Slayer.errandDue(true, false, now, now + 1, 0, 5_000, true, false, false, false, true));
        check("errand: cooldown", !Slayer.errandDue(true, false, now, 0, now + 1, 5_000, true, false, false, false, true));
        check("errand: 4,999 coins", !Slayer.errandDue(true, false, now, 0, 0, 4_999, true, false, false, false, true));
        check("errand: skilling job", !Slayer.errandDue(true, false, now, 0, 0, 5_000, false, false, false, false, true));
        check("errand: mid-quest", !Slayer.errandDue(true, false, now, 0, 0, 5_000, true, true, false, false, true));
        check("errand: other errand", !Slayer.errandDue(true, false, now, 0, 0, 5_000, true, false, true, false, true));
        check("errand: in combat", !Slayer.errandDue(true, false, now, 0, 0, 5_000, true, false, false, true, true));
        check("errand: dice", !Slayer.errandDue(true, false, now, 0, 0, 5_000, true, false, false, false, false));
        // boost
        Set<String> cows = Slayer.npcNames(Tasks.COWS);
        check("COWS names hold 'cow' " + cows, cows.contains("cow"));
        check("match Cow hunt", Slayer.matches(cows, new String[] { "Cow" }));
        check("no match Goblin hunt", !Slayer.matches(cows, new String[] { "Goblin" }));
        check("no match without task", !Slayer.matches(Collections.emptySet(), new String[] { "Cow" }));
        check("no match null names", !Slayer.matches(cows, null));
        // v1.1 task trip gate
        check("trip: all good", Slayer.tripDue(true, true, false, 0, true));
        check("trip: flag off", !Slayer.tripDue(false, true, false, 0, true));
        check("trip: no task", !Slayer.tripDue(true, false, false, 0, true));
        check("trip: broke", !Slayer.tripDue(true, true, true, 0, true));
        check("trip: 2 slayers at spot ok", Slayer.tripDue(true, true, false, Slayer.SPOT_CAP - 1, true));
        check("trip: spot cap 3 full", !Slayer.tripDue(true, true, false, Slayer.SPOT_CAP, true));
        check("trip: dice", !Slayer.tripDue(true, true, false, 0, false));
        check("trip odds 1 in 2", Slayer.TRIP_ODDS == 2 && Slayer.SPOT_CAP == 3);
        // v1.2 errand rate
        check("v12 eligible: combat trip cb 40", Slayer.eligible(true, true, 40));
        check("v12 eligible: combat trip cb 3", Slayer.eligible(true, true, 3));
        check("v12 eligible: skiller cb 20 yes, cb 19 no", Slayer.eligible(false, true, 20) && !Slayer.eligible(false, true, 19));
        check("v12 eligible: cb 70 no (either way)", !Slayer.eligible(true, true, 70) && !Slayer.eligible(false, true, 70));
        check("v12 eligible: no job yet no", !Slayer.eligible(true, false, 40));
        check("v13 odds: combat 1, skiller 2", Slayer.errandOdds(true) == 1 && Slayer.errandOdds(false) == 2);
        check("v12 cooldowns: errand 20 min, abandon 45 min, abandon after 15", Slayer.ERRAND_COOL_MS == 20 * 60_000L && Slayer.COOLDOWN_MS == 45 * 60_000L && Slayer.ABANDON_MS == 15 * 60_000L);
        // v1.2 size re-roll (fake manager: task + amount)
        Tasks[] cur = {null}; int[] amt = {0};
        Tasks[] ts = { Tasks.COWS, Tasks.GOBLINS, Tasks.BATS, Tasks.WOLVES, Tasks.BIRDS }; int[] as = { 50, 40, 20, 10, 10 }; int[] k = {0};
        java.util.function.Supplier<Tasks> gen = () -> { int i = Math.min(k[0]++, ts.length - 1); cur[0] = ts[i]; amt[0] = as[i]; return cur[0]; };
        Runnable clr = () -> { cur[0] = null; amt[0] = 0; };
        int[] sz = {0};
        r = Slayer.pickSmall(gen, () -> amt[0], clr, Slayer.ALLOW, sz);
        check("v12 size: 50, 40 re-rolled, BATS x20 kept, 2 re-rolls", r == Tasks.BATS && amt[0] == 20 && sz[0] == 2);
        k[0] = 0; sz[0] = 0; as[0] = 50; as[1] = 45; as[2] = 40; as[3] = 35; as[4] = 30;
        r = Slayer.pickSmall(gen, () -> amt[0], clr, Slayer.ALLOW, sz);
        check("v12 size: all big -> last roll kept (WOLVES x35), " + Slayer.AMOUNT_ROLLS + " re-rolls", r == Tasks.WOLVES && amt[0] == 35 && sz[0] == Slayer.AMOUNT_ROLLS);
        k[0] = 0; sz[0] = 0; as[0] = 15;
        r = Slayer.pickSmall(gen, () -> amt[0], clr, Slayer.ALLOW, sz);
        check("v12 size: small first roll kept, 0 re-rolls", r == Tasks.COWS && sz[0] == 0);
        int[] calls = {0};
        r = Slayer.pickSmall(() -> { calls[0]++; return calls[0] == 1 ? Tasks.COWS : Tasks.SCORPIONS; }, () -> calls[0] == 1 ? 50 : 10, () -> { }, Slayer.ALLOW, null);
        check("v12 size: re-roll finds nothing allowed -> null (skip window)", r == null);
        // v1.3 revisit, abandon run, bad spots, tool-loop guard
        check("v13 revisit: open inside the window", Slayer.revisitOn(now + 1, now) && !Slayer.revisitOn(now, now) && !Slayer.revisitOn(0, now));
        long[] a1 = Slayer.afterAbandon(1, now), a2 = Slayer.afterAbandon(2, now);
        check("v13 abandon 1 in a row: revisit now", a1[0] == now && a1[1] == now + Slayer.REVISIT_MS);
        check("v13 abandon 2 in a row: 45 min cooldown, no revisit", a2[0] == now + Slayer.COOLDOWN_MS && a2[1] == 0);
        check("v13 revisit still needs every other guard (coins, quest, busy, combat, skip)", !Slayer.errandDue(true, false, now, 0, 0, 4_999, true, false, false, false, true)
                && !Slayer.errandDue(true, false, now, 0, 0, 5_000, true, true, false, false, true) && !Slayer.errandDue(true, false, now, 0, 0, 5_000, true, false, true, false, true)
                && !Slayer.errandDue(true, false, now, now + 1, 0, 5_000, true, false, false, false, true) && !Slayer.errandDue(true, false, now, 0, 0, 5_000, false, false, false, false, true));
        Map<String, Long> badSpots = new HashMap<>(); badSpots.put("Bats", now + Slayer.BAD_MS);
        check("v13 bad spot: Bats off, Cows on, Bats back after 2 h", !Slayer.spotOk(badSpots, "Bats", now) && Slayer.spotOk(badSpots, "Cows", now) && Slayer.spotOk(badSpots, "Bats", now + Slayer.BAD_MS) && Slayer.spotOk(badSpots, null, now));
        int[] harpoon = { 311 };
        check("v13 tool loop: bought 1 min ago, not held -> loop", Slayer.toolLoop(311, now - 60_000, harpoon, false, now));
        check("v13 tool loop: held, old, other tool, none bought -> no", !Slayer.toolLoop(311, now - 60_000, harpoon, true, now) && !Slayer.toolLoop(311, now - 600_000, harpoon, false, now)
                && !Slayer.toolLoop(303, now - 60_000, harpoon, false, now) && !Slayer.toolLoop(0, now, harpoon, false, now) && !Slayer.toolLoop(311, now, null, false, now));
        // served: every ALLOW task has a JOBS hunt (logs "[Slayer] no hunt for X" otherwise)
        if (HuntMap.HUNTS.isEmpty()) { HuntMap.build(); List<SmartAdventurers.Job> all = new ArrayList<>(SmartAdventurers.JOBS); for (HuntMap.Hunt h : HuntMap.HUNTS) { SmartAdventurers.Job j = new SmartAdventurers.Job(AfkTasks.Task.AUTO, -1, 1); j.hunt = h; all.add(j); } SmartAdventurers.JOBS = all; }
        Set<Tasks> sv = Slayer.served();
        check("served " + sv.size() + "/13 " + sv, sv.size() >= 12);
        // flag off: no login, no attribute, trip-end hook is a no-op
        AIPlayer a = TestCR1.bot(Location.create(3222, 3218, 0), 40, 30, 30, 30);
        check("fresh bot has no slayer-manager", a.getAttribute("slayer-manager", null) == null && !Slayer.hasTask(a) && Slayer.amount(a) == 0);
        if (!Slayer.ON) check("flag off: still no attribute after hasTask/amount", a.getAttribute("slayer-manager", null) == null);
        Slayer.ensure(a); Slayer.ensure(a);
        check("ensure twice -> one hook", TestP54Slayer.hooks(a) == 1);
        a.clear();
        // P80 round 2: the full open gate on a real bot (Lumbridge, combat 40, no coins)
        AIPlayer o = TestCR1.bot(Location.create(3222, 3218, 0), 40, 30, 30, 30);
        SmartAdventurers.Brain ob = new SmartAdventurers.Brain(); ob.bot = o;
        check("P80 WATCH < EARLY < ABANDON", Slayer.WATCH_MS < Slayer.EARLY_MS && Slayer.EARLY_MS < Slayer.ABANDON_MS);
        StringBuilder ow = new StringBuilder();
        SmartAdventurers.Job gob = Slayer.openSpot(ob, Slayer.npcNames(Tasks.GOBLINS), new int[] { 6 }, ow);
        check("P80 goblins open from Lumbridge (" + (gob != null ? gob.label() : ow) + ")", gob != null);
        SmartAdventurers.Job far = null;
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.spot.getY() < 6400 && j.hunt.spot.getX() < 3400 && j.hunt.spot.getDistance(o.getLocation()) > AfkTasks.Runner.TELEPORT_MIN + 50 && j.level <= 20) { far = j; break; }
        String fr = far == null ? null : Slayer.openGate(ob, far, 40, System.currentTimeMillis(), new int[] { 2 });
        check("P80 far hunt closed with no coins: " + (far != null ? far.label() : "none") + "=" + fr, far == null || fr != null);
        Slayer.watch(ob, System.currentTimeMillis());
        check("P80 watch with no task is a no-op", ob.slaySt.had == null && ob.slaySt.closedSince == 0);
        o.clear();
        // busy-list grep
        String src = System.getProperty("p54.src");
        if (src != null) {
            int lines = 0, bad = 0, escLines = 0, boneLines = 0, sumLines = 0;
            for (java.io.File f : new java.io.File(src).listFiles()) {
                if (!f.getName().endsWith(".java") || f.getName().equals("Slayer.java")) continue;
                int n = 0;
                for (String l : java.nio.file.Files.readAllLines(f.toPath())) {
                    n++;
                    boolean mill = l.matches(".*\\bmill [!=]= 0.*"), esc = l.contains("Scenes.Greet.escorting(") && !f.getName().equals("Scenes.java"), bone = l.matches(".*\\bbone [!=]= 0.*");
                    boolean sum = l.matches(".*\\bsummon [!=]= 0.*") && !l.contains("summonTick(now); return;") && !l.contains("if (summon == 0) return;") && !l.contains("endSummon(true, \"logout\")") && !l.contains("endSummon(true, \"rescued\")"); // P65: summoning errand state
                    if (sum) { sumLines++; if (!l.contains("slay")) { bad++; log("summon busy list without slay: " + f.getName() + ":" + n); } }
                    if (!mill && !esc && !bone) continue;
                    if (l.contains("millTick(now); return;")) continue; // the dispatch line; slay has its own next to it
                    if (l.contains("boneTick(now); return;") || l.contains("brain.bone = 0;")) continue; // v1.2: bury-run dispatch and logout reset; slay has its own lines
                    if (esc) escLines++;
                    if (bone) boneLines++;
                    lines++;
                    if (!l.contains("slay")) { bad++; log("busy list without slay: " + f.getName() + ":" + n); }
                }
            }
            // P71: flag-off proof. Outside Slayer.java every call into Slayer and every write of the tool-loop fields sits on a line that tests Slayer.ON
            int gCalls = 0, ungated = 0; boolean prevOpen = false;
            for (java.io.File f : new java.io.File(src).listFiles()) {
                if (!f.getName().endsWith(".java") || f.getName().equals("Slayer.java")) continue;
                int n = 0; prevOpen = false;
                for (String l : java.nio.file.Files.readAllLines(f.toPath())) {
                    n++;
                    String code = l.replaceAll("//.*$", "");
                    boolean wasOpen = prevOpen; prevOpen = code.contains("Slayer.ON") && code.trim().endsWith("{");
                    boolean call = code.matches(".*\\bSlayer\\.[a-z][A-Za-z]*\\(.*") || code.matches(".*\\btoolBought(Id|At) = .*") || code.contains("Slayer.TOOL_LOOPS");
                    if (!call) continue;
                    gCalls++;
                    boolean dispatch = code.contains("if (slay != 0) { Slayer.tick(this, now); return; }"); // slay is set only by Slayer (flag on); tick() itself returns on !ON
                    boolean inBlock = wasOpen; // the body line of a block opened by a Slayer.ON test
                    if (!code.contains("Slayer.ON") && !dispatch && !inBlock) { ungated++; log("ungated Slayer call: " + f.getName() + ":" + n); }
                }
            }
            check("flag gate: " + gCalls + " Slayer calls/tool-loop writes outside Slayer.java, all on a Slayer.ON line", gCalls >= 8 && ungated == 0);
            check("busy lists: " + lines + " lines name mill/escorting/bone (" + escLines + " escorting, " + boneLines + " bone), all name slay; " + sumLines + " summon lines name slay", lines >= 9 && escLines >= 9 && boneLines >= 9 && sumLines >= 9 && bad == 0);
        }
    }
}
