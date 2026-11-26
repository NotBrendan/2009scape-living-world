package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.HashMap;
import java.util.Map;

/**
 * Trial only (-Dtest.p40broke=true, with -Dsmartbots.count=0 -Dquests=false): P40 B4 broke-bot recovery (-Dbots.brokeJob).
 * One bot at Lumbridge with 80 gp, combat 40, Woodcutting 15 and a bronze axe, no other kit. Each section prints RESULT PASS/FAIL, DONE at the end.
 *  S1  flag on: 20 job choices, every one a free earning job (no hunt, no fight, no bait, the tool held, within the broke radius).
 *  S2  flag off: the old choice holds: the free-job override never applies (at least one of 20 choices is not a free job,
 *      the money-first branch the bot took in p39s4) and no [Broke] override happens.
 *  S4  brain2 rule 2 (needs -Dbrain.fix1=true): with the bot's nearest money job as its last job, 100 choices repeat it more
 *      often with Brain2.ON than without (the x0.5 is undone only there), and the rule fired.
 *  S5  brain2 rule 1 (needs -Dbrain.fix1=true): the Desert Wolf and Jackal hunts are "noBank" for chooseJob / Slayer.openGate with
 *      Brain2.ON and open without; with -Dbots.slayer=true a WOLVES task trip (dice forced) never returns Desert Wolfs with it on.
 * Offline (no world): main() runs the pure Brain2 checks: java -cp "server.jar;<build>" content.custom.TestP40Broke
 *  S3  shop sale: AfkTasks.Runner.brokeShopSale pays 40% of the guide price for 20 yew logs into the bot's bank at once.
 */
public final class TestP40Broke implements StartupListener {
    static void log(String s) { System.out.println("[TestP40Broke] " + s); }
    static final Location LUMBRIDGE = Location.create(3222, 3218, 0);

    static boolean free(SmartAdventurers.Brain br, SmartAdventurers.Job j) {
        if (j == null || j.hunt != null) return false;
        if (j.task.kind == AfkTasks.Kind.FIGHT || j.task.kind == AfkTasks.Kind.SMELT || j.task.kind == AfkTasks.Kind.BANK) return false;
        if (j.task == AfkTasks.Task.BAIT || j.task == AfkTasks.Task.LURE) return false;
        if (j.task.tools != null && j.task.tools.length > 0 && !AfkTasks.hasAny(br.bot, j.task.tools)) {
            boolean banked = false; for (int t : j.task.tools) if (br.bot.getBank().getAmount(t) > 0) banked = true;
            if (!banked) return false;
        }
        double d = 9999; for (AfkTasks.Spot s : AfkTasks.SPOTS.get(j.task)) d = Math.min(d, s.at.getDistance(br.bot.getLocation()));
        return d <= SmartAdventurers.Brain.BROKE_RADIUS;
    }

    // ---- brain2 pure checks (offline main, and at the end of the in-world run) ----
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }
    static Location at(int x, int y) { return Location.create(x, y, 0); }
    /** A copy of AfkTasks.BANKS (that class cannot load without a world); the in-world run checks it against the real array. */
    static final Location[] BANKS = {at(3092, 3245), at(3185, 3438), at(3253, 3421), at(2946, 3370), at(3012, 3356), at(3094, 3492), at(3270, 3167), at(2809, 3441),
            at(2726, 3492), at(2617, 3332), at(2654, 3284), at(2612, 3092), at(2586, 3420), at(3512, 3480), at(2852, 2955)};
    static final Location DESERT_WOLF = at(3258, 3078), JACKAL = at(3212, 2863), UGTHANKI = at(3268, 3052), WHITE_WOLF = at(2848, 3487), GRIZZLY = at(2387, 3376);

    public static void main(String[] a) { brain2Checks(); log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail); System.exit(fail == 0 ? 0 : 1); }

    static void brain2Checks() {
        java.util.function.Predicate<Location> all = b -> true, noGuild = b -> b.getX() != 2586;
        // rule 1: the trip's bank test, asked from the spot
        check("desert wolfs: no bank (Al Kharid's is behind the toll line, Draynor 233 away)", Brain2.bankIndex(DESERT_WOLF, BANKS, all) == -1);
        check("jackals: no bank", Brain2.bankIndex(JACKAL, BANKS, all) == -1);
        check("desert wolfs -> noBank", "noBank".equals(Brain2.huntGate(true, DESERT_WOLF, BANKS, all, bi -> 100)));
        check("jackals -> noBank, the planner is not asked", "noBank".equals(Brain2.huntGate(true, JACKAL, BANKS, all, bi -> { throw new IllegalStateException(); })));
        check("ugthankis (x 3268): Al Kharid bank counts", Brain2.bankIndex(UGTHANKI, BANKS, all) == 6);
        check("ugthankis, bank leg has no route -> noBankRoute", "noBankRoute".equals(Brain2.huntGate(true, UGTHANKI, BANKS, all, bi -> -1)));
        check("ugthankis, bank leg planned -> open", Brain2.huntGate(true, UGTHANKI, BANKS, all, bi -> 140) == null);
        check("ugthankis, bank leg not known (-2) -> open", Brain2.huntGate(true, UGTHANKI, BANKS, all, bi -> -2) == null);
        check("white wolfs: Catherby", Brain2.bankIndex(WHITE_WOLF, BANKS, all) == 7 && Brain2.huntGate(true, WHITE_WOLF, BANKS, all, bi -> 80) == null);
        check("grizzly bears: only the Fishing Guild bank is in range", Brain2.bankIndex(GRIZZLY, BANKS, all) == 12 && Brain2.bankIndex(GRIZZLY, BANKS, noGuild) == -1);
        check("grizzly bears, guild shut for this bot -> noBank", "noBank".equals(Brain2.huntGate(true, GRIZZLY, BANKS, noGuild, bi -> 100)));
        check("a spot at 219 tiles from a bank is in range, at 220 not (dist < 220)", Brain2.bankIndex(at(3092, 3245 + 219), new Location[] {BANKS[0]}, all) == 0 && Brain2.bankIndex(at(3092, 3245 + 220), new Location[] {BANKS[0]}, all) == -1);
        check("toll line: x 3267 no Al Kharid, x 3268 yes", Brain2.bankIndex(at(3267, 3167), new Location[] {BANKS[6]}, all) == -1 && Brain2.bankIndex(at(3268, 3167), new Location[] {BANKS[6]}, all) == 0);
        check("underground spot: not judged", Brain2.huntGate(true, Location.create(3258, 3078 + 6400, 0), BANKS, all, bi -> -1) == null);
        // slayer9 (-Dbots.slayer.fix9): Slayer.tollShut9 is the rule AfkTasks.Runner.nearestBank0 asks per bank; the desert test here is Gates' box (Runner.desertSide's fallback with no planner)
        java.util.function.Predicate<Location> desert = l -> l.getX() >= 3140 && l.getX() <= 3520 && l.getY() >= 2830 && l.getY() <= 3114;
        check("s9 desert wolfs [3258, 3078], nearestBank0's rule: Al Kharid's bank is skipped with fix9 off, kept with it on", Slayer.tollShut9(false, BANKS[6], DESERT_WOLF, desert) && !Slayer.tollShut9(true, BANKS[6], DESERT_WOLF, desert));
        check("s9 desert wolfs, the whole lookup: no bank with fix9 off, Al Kharid (89 tiles) with it on", Brain2.bankIndex(false, desert, DESERT_WOLF, BANKS, all) == -1 && Brain2.bankIndex(true, desert, DESERT_WOLF, BANKS, all) == 6 && (int) BANKS[6].getDistance(DESERT_WOLF) == 89);
        check("s9 west of the gate outside the desert (3267,3167; the swamp 3200,3135; no desert test): skipped on and off", Slayer.tollShut9(true, BANKS[6], at(3267, 3167), desert) && Slayer.tollShut9(true, BANKS[6], at(3200, 3135), desert) && Slayer.tollShut9(true, BANKS[6], DESERT_WOLF, null) && Slayer.tollShut9(false, BANKS[6], at(3267, 3167), desert));
        check("s9 other banks and Al Kharid's own side: never skipped; jackals still have no bank; ugthankis unchanged", !Slayer.tollShut9(true, BANKS[0], DESERT_WOLF, desert) && !Slayer.tollShut9(false, BANKS[6], UGTHANKI, desert) && Brain2.bankIndex(true, desert, JACKAL, BANKS, all) == -1 && Brain2.bankIndex(true, desert, UGTHANKI, BANKS, all) == 6);
        check("s9 hunt gate: desert wolfs open with fix9 and a planned bank leg, noBankRoute without a route, noBank with fix9 off", Brain2.huntGate(true, true, desert, DESERT_WOLF, BANKS, all, bi -> 170) == null && "noBankRoute".equals(Brain2.huntGate(true, true, desert, DESERT_WOLF, BANKS, all, bi -> -1)) && "noBank".equals(Brain2.huntGate(true, false, desert, DESERT_WOLF, BANKS, all, bi -> 170)));
        check("flag off: every hunt open", Brain2.huntGate(false, DESERT_WOLF, BANKS, all, bi -> -1) == null && Brain2.huntGate(false, JACKAL, BANKS, all, null) == null);
        // rule 2
        check("broke, last job is the nearest money job -> full score", Brain2.keepRepeat(true, true, true, 30, 30));
        check("broke, another money job is nearer -> x0.5 stays", !Brain2.keepRepeat(true, true, true, 80, 30));
        check("broke, last job sells for nothing -> x0.5 stays", !Brain2.keepRepeat(true, true, false, 10, 30));
        check("bot with coins -> x0.5 stays", !Brain2.keepRepeat(true, false, true, 30, 30));
        check("flag off -> x0.5 stays", !Brain2.keepRepeat(false, true, true, 30, 30));
        check("full score is the halved score x2", Brain2.fullScore(1.75) == 3.5);
    }

    static SmartAdventurers.Job hunt(String name) { for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(name)) return j; return null; }

    /** S4 + S5, through Brain.chooseJob (the pick think() calls). The bot is still broke here (S3's sale comes after). */
    static void brain2World(SmartAdventurers.Brain br) {
        if (!SmartAdventurers.BRAIN_FIX1) { log("RESULT SKIP S4 S5: need -Dbrain.fix1=true"); return; }
        boolean was = Brain2.ON;
        try {
            boolean same = BANKS.length == AfkTasks.BANKS.length; for (int i = 0; same && i < BANKS.length; i++) same = BANKS[i].equals(AfkTasks.BANKS[i]);
            log("RESULT " + (same ? "PASS" : "FAIL") + " S5a the test's bank list is AfkTasks.BANKS");
            // S4: find the nearest money job = the last job for which the rule fires
            SmartAdventurers.Brain.BROKE_JOB = true;
            java.util.Set<SmartAdventurers.Job> seen = new java.util.LinkedHashSet<>();
            Brain2.ON = false; br.lastJob = null;
            for (int i = 0; i < 60; i++) { SmartAdventurers.Job j = br.chooseJob(false); if (j != null) seen.add(j); }
            SmartAdventurers.Job near = null; Brain2.ON = true;
            for (SmartAdventurers.Job j : seen) { br.lastJob = j; int k = Brain2.repeatKept; br.chooseJob(false); if (Brain2.repeatKept > k) { near = j; break; } }
            if (near == null) log("RESULT FAIL S4 no pick of 60 is the nearest money job (broke " + br.broke() + ", picks " + seen.size() + ")");
            else {
                int[] reps = new int[2]; int kept = Brain2.repeatKept;
                for (int on = 0; on < 2; on++) { Brain2.ON = on == 1; for (int i = 0; i < 100; i++) { br.lastJob = near; if (br.chooseJob(false) == near) reps[on]++; } }
                kept = Brain2.repeatKept - kept;
                log("RESULT " + (br.broke() && reps[1] > reps[0] && kept == 100 ? "PASS" : "FAIL") + " S4 last job " + near.label() + " (nearest money job): repeats " + reps[0] + "/100 with fix2 off, " + reps[1] + "/100 on; rule fired " + kept + "/100");
            }
            br.lastJob = null;
            // S5: the hunt gate, then a forced task trip
            int ok = 0, n = 0; StringBuilder sb = new StringBuilder();
            for (String nm : new String[] {"Desert Wolf", "Jackal"}) {
                SmartAdventurers.Job j = hunt(nm); if (j == null) { sb.append(nm).append("=no job "); continue; }
                n++;
                Brain2.ON = true; String g1 = SmartAdventurers.brain2Gate(br.bot, j, new int[] {0}, false);
                Brain2.ON = false; String g0 = SmartAdventurers.brain2Gate(br.bot, j, new int[] {0}, false);
                if ((Slayer.FIX9 && nm.equals("Desert Wolf") ? !"noBank".equals(g1) : "noBank".equals(g1)) && g0 == null) ok++; // slayer9: with fix9 the Desert Wolf spot has Al Kharid's bank (open, or noBankRoute from the planner)
                sb.append(nm).append(" on=").append(g1).append(" off=").append(g0).append(' ');
            }
            log("RESULT " + (n > 0 && ok == n ? "PASS" : "FAIL") + " S5b hunt gate: " + sb.toString().trim());
            SmartAdventurers.Job dw = hunt("Desert Wolf");
            if (!Slayer.ON || dw == null) log("RESULT SKIP S5c task trip: needs -Dbots.slayer=true and the Desert Wolf hunt");
            else {
                Ledger.Wallet.give(br.bot, 20_000);
                br.slaySt.had = content.global.skill.slayer.Tasks.valueOf("WOLVES"); br.slaySt.names = new java.util.HashSet<>(java.util.Arrays.asList("desert wolf"));
                int[] got = new int[2]; String[] gate = new String[2];
                for (int on = 0; on < 2; on++) {
                    Brain2.ON = on == 1;
                    gate[on] = Slayer.openGate(br, dw, br.effectiveCombat(), System.currentTimeMillis(), new int[] {0});
                    for (int i = 0; i < 20; i++) { br.slaySt.streak = true; br.slaySt.parkedUntil = 0; if (br.chooseJob(false) == dw) got[on]++; }
                }
                br.slaySt.had = null; br.slaySt.names = java.util.Collections.emptySet(); br.slaySt.streak = false; br.slaySt.trip = null;
                log("RESULT " + ((Slayer.FIX9 ? !"noBank".equals(gate[1]) : got[1] == 0 && "noBank".equals(gate[1])) && (got[0] > 0 || gate[0] != null) ? "PASS" : "FAIL") + " S5c WOLVES task trip to Desert Wolfs: " + got[0] + "/20 with fix2 off (openGate " + gate[0] + "), " + got[1] + "/20 on (openGate " + gate[1] + ")");
            }
        } finally { Brain2.ON = was; }
        brain2Checks();
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " S6 pure Brain2 checks pass=" + pass + " fail=" + fail);
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p40broke")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; SmartAdventurers.Brain br;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t < 150) return false;
                    if (br == null) {
                        if (SmartAdventurers.JOBS.isEmpty()) { if (t > 1500) { log("RESULT FAIL no JOBS loaded"); log("DONE"); return true; } return false; }
                        AIPlayer b = new AIPlayer(LUMBRIDGE);
                        SmartAdventurers.finishTutorial(b);
                        b.getInventory().clear(); for (Item it : b.getBank().toArray()) if (it != null) b.getBank().remove(it);
                        for (int s = 0; s < 14; s++) { Item w = b.getEquipment().get(s); if (w != null) b.getEquipment().remove(w); }
                        int[][] lv = {{Skills.HITPOINTS, 40}, {Skills.ATTACK, 40}, {Skills.STRENGTH, 40}, {Skills.DEFENCE, 40}, {Skills.WOODCUTTING, 15}};
                        for (int[] l : lv) { b.getSkills().setStaticLevel(l[0], l[1]); b.getSkills().setLevel(l[0], l[1]); }
                        try { b.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
                        b.getInventory().add(new Item(1351)); // bronze axe
                        Ledger.Wallet.give(b, 80);
                        br = new SmartAdventurers.Brain();
                        br.bot = b; br.scriptAPI = new core.game.bots.ScriptAPI(b); br.nextThink = Long.MAX_VALUE;
                        log("bot " + b.getUsername() + " at " + Ledger.Wallet.coins(b) + " gp, " + SmartAdventurers.JOBS.size() + " jobs");
                        return false;
                    }
                    if (t < 200) return false;
                    // S1 flag on
                    SmartAdventurers.Brain.BROKE_JOB = true;
                    int okOn = 0, nullOn = 0; StringBuilder on = new StringBuilder();
                    for (int i = 0; i < 20; i++) { SmartAdventurers.Job j = br.chooseJob(false); if (j == null) nullOn++; else if (free(br, j)) okOn++; else on.append(j.label()).append(' '); if (i < 3 && j != null) on.append('[').append(j.label()).append("] "); }
                    log("RESULT " + (okOn == 20 ? "PASS" : "FAIL") + " S1 flag on: " + okOn + "/20 free picks, " + nullOn + " none; " + on.toString().trim());
                    // S2 flag off
                    SmartAdventurers.Brain.BROKE_JOB = false;
                    int freeOff = 0, nonFree = 0; StringBuilder off = new StringBuilder();
                    for (int i = 0; i < 20; i++) { SmartAdventurers.Job j = br.chooseJob(false); if (j != null && free(br, j)) freeOff++; else if (j != null) { nonFree++; if (nonFree <= 3) off.append(j.label()).append(' '); } }
                    log("RESULT " + (nonFree > 0 ? "PASS" : "FAIL") + " S2 flag off: " + nonFree + "/20 not free (old money-first picks: " + off.toString().trim() + "), " + freeOff + " free");
                    brain2World(br); // S4, S5 (brain2)
                    // S3 shop sale
                    SmartAdventurers.Brain.BROKE_JOB = true;
                    long before = Ledger.Wallet.coins(br.bot);
                    int price = AfkTasks.Runner.guidePrice(1515);
                    Map<Integer, Integer> haul = new HashMap<>(); haul.put(1515, 20);
                    long got = AfkTasks.Runner.brokeShopSale(br.bot, haul, null);
                    long after = Ledger.Wallet.coins(br.bot);
                    long want = (long) price * 20 * SmartAdventurers.Brain.BROKE_SHOP_PCT / 100;
                    log("RESULT " + (got > 0 && got == want && after - before == got ? "PASS" : "FAIL") + " S3 shop sale: 20 yew logs at guide " + price + " -> " + got + " gp (want " + want + "), wallet " + before + " -> " + after);
                    SmartAdventurers.Brain.BROKE_JOB = Boolean.getBoolean("bots.brokeJob");
                    log("DONE");
                    return true;
                } catch (Throwable e) { log("RESULT FAIL error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
            }
        });
    }
}
