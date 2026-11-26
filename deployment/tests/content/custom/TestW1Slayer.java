package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * W1 lane S (2026-11-18): slayer9b (-Dbots.slayer.fix9b), the teleport cap rule (-Dbots.slayer.capFix), the boot sea-crossing
 * alarm exemption (-Dcoinwatch.bootSeaExempt). Every flag is read once at class load, so each in-world check asserts the value
 * for the flags this run has: run the unit with the flags on and again with them off.
 * Offline (no world): java -cp "server.jar;<build>" content.custom.TestW1Slayer [path to addons-src/content/custom]
 *  P   pure cap rule: capFar (off = the old 2D test), capExempt, capCounted.
 *  B   bootSeaExempt: sea-crossing only, minutes 0 to 29 only, flag on only; through CoinWatch.countKind (the real count
 *      CoinWatch.paid feeds): 100 crossings at minute 10 leave no kind count, at minute 31 they count (the alarm is armed again);
 *      other kinds always count; CoinWatch.paid's own call follows the run's flag.
 *  G   (with the source path) AfkTasks calls Slayer.exemptLeg at its three paid legs; plannedTiles asks FIX9B.
 * In-world (-Dtest.w1slayer=true -Dsmartbots.count=0 -Dquests=false, the live flags, -Dclerk.fix2=true for the cap):
 *  F1  a bot in Al Kharid 15 tiles north of the bank tile (105 from the spot, so the gate plans; origin = the bank) with 3,000 gp: SmartAdventurers.plannedTiles to the Desert Wolf spot (the planner the trip asks):
 *      a route with fix9b (two legs through the pass), -1 without.
 *  F2  Slayer.openGate with a plan budget (the task trip's gate): open with fix9b, "noRoute(p73)" without.
 *  F3  Brain.chooseJob -> Slayer.taskTrip (WOLVES task, dice forced): Desert Wolfs taken with fix9b, never without.
 *  C1  cap reached (3/3), bot at a known dungeon entrance, hunt under it: Slayer.taskTrip takes the trip with capFix (the runner
 *      walks in); without, the old 2D test decides.
 *  C2  cap reached, bot on the surface right above a dungeon hunt with no known entrance (the runner always teleports there):
 *      capFix refuses the trip (far); without it the trip is taken (2D distance 0) and the teleport paid over the cap.
 *  C5  CoinWatch's hourly PAID reset mid-trip: the legs since the reset all count (exempt legs from before it are not taken off).
 *  C3  a running trip at 2/3 with one paid emergency teleport: Slayer.stallOk (the runner's stall hook) still allows a stall
 *      teleport with capFix and the trip counts 0 legs; without, the cap is reached and the trip counts 1.
 */
public final class TestW1Slayer implements StartupListener {
    static void log(String s) { System.out.println("[TestW1Slayer] " + s); }
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log("RESULT " + (ok ? "PASS " : "FAIL ") + what); }
    static final Location AL_KHARID_BANK = Location.create(3272, 3182, 0) /* 15 tiles north of the bank tile: 105 from the spot, over jobs.planFrom 100 (the s58 bot stood at d104); the bank tile itself (89) is not planned */, LUMBRIDGE = Location.create(3222, 3218, 0);
    static final long MIN = 60_000L;

    public static void main(String[] a) throws Exception {
        pure();
        if (a.length > 0) {
            String afk = new String(Files.readAllBytes(Paths.get(a[0], "AfkTasks.java")), StandardCharsets.UTF_8), sa = new String(Files.readAllBytes(Paths.get(a[0], "SmartAdventurers.java")), StandardCharsets.UTF_8);
            int n = afk.split("Slayer\\.exemptLeg\\(p, \"", -1).length - 1;
            check("G AfkTasks calls Slayer.exemptLeg at " + n + " paid legs (expect 3: emergency, bank-above, return); plannedTiles asks Slayer.FIX9B",
                    n == 3 && afk.contains("exemptLeg(p, \"emergency\"") && afk.contains("exemptLeg(p, \"bank-above\"") && afk.contains("exemptLeg(p, \"return\"") && sa.contains("Slayer.FIX9B ? AfkTasks.Runner.passVia(start, to)"));
        }
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " offline pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static int kind(String k) { AtomicInteger v = CoinWatch.KINDS.get(k); return v == null ? 0 : v.get(); }

    static void pure() {
        int m = 320;
        check("P1 capFar flag off = the old 2D test whatever the rest", Slayer.capFar(false, 321, false, true, 6500, 10, -1, true, m) && !Slayer.capFar(false, 320, false, true, 6500, -1, -1, true, m));
        check("P2 capFar surface -> dungeon: entrance 40 tiles = walk, 400 = far, no entrance = far (2D 0)", !Slayer.capFar(true, 500, false, true, 6500, 40, -1, false, m) && Slayer.capFar(true, 10, false, true, 6500, 400, -1, false, m) && Slayer.capFar(true, 0, false, true, 6400, -1, -1, false, m));
        check("P3 capFar dungeon -> surface: climb-out 100 from the spot = walk, 500 = far, none = far", !Slayer.capFar(true, 90, true, false, 6500, -1, 100, false, m) && Slayer.capFar(true, 90, true, false, 6500, -1, 500, false, m) && Slayer.capFar(true, 90, true, false, 6500, -1, -1, false, m));
        check("P4 capFar same level: the straight distance; the sea or the Salve = far", !Slayer.capFar(true, 320, false, false, 320, -1, -1, false, m) && Slayer.capFar(true, 321, false, false, 321, -1, -1, false, m) && Slayer.capFar(true, 50, false, false, 50, -1, -1, true, m) && !Slayer.capFar(true, 100, true, true, 100, -1, -1, false, m));
        check("P5 capExempt: emergency always; bank-above underground; a return leg underground after the first paid leg; nothing off the trip or with the flag off",
                Slayer.capExempt(true, true, "emergency", false, 0) && Slayer.capExempt(true, true, "bank-above", true, 0) && Slayer.capExempt(true, true, "return", true, 1)
                        && !Slayer.capExempt(true, true, "return", true, 0) && !Slayer.capExempt(true, true, "return", false, 2) && !Slayer.capExempt(true, true, "stall", true, 2)
                        && !Slayer.capExempt(true, false, "emergency", false, 0) && !Slayer.capExempt(false, true, "emergency", false, 0) && !Slayer.capExempt(true, true, null, true, 1));
        check("P6 capCounted: paid - exempt, never under 0; flag off = paid", Slayer.capCounted(true, 5, 3) == 2 && Slayer.capCounted(true, 1, 3) == 0 && Slayer.capCounted(false, 5, 3) == 5 && Slayer.capCounted(true, 4, 0) == 4);
        check("P8 tripCounted across CoinWatch's hourly PAID reset (snap 5, count now 1, 2 exempt legs before it): counts 1, not 0; no reset: 5-2=3, 3-3=0; flag off: paidDelta alone",
                Slayer.tripCounted(true, 5, 1, 2) == 1 && Slayer.tripCounted(true, 5, 0, 2) == 0 && Slayer.tripCounted(true, 2, 7, 2) == 3 && Slayer.tripCounted(true, 2, 5, 3) == 0 && Slayer.tripCounted(false, 5, 1, 2) == 1 && Slayer.tripCounted(false, 2, 7, 2) == 5 && Slayer.tripCounted(true, -1, 4, 1) == 0);
        check("P7 the cap itself is unchanged: 3 a task, far legs only", Slayer.TELE_CAP == 3 && Slayer.teleStep(true, 2, true) == Slayer.Tele.PAY && Slayer.teleStep(true, 3, true) == Slayer.Tele.CAPPED && Slayer.teleStep(true, 9, false) == Slayer.Tele.WALK);
        // boot sea-crossing exemption
        check("B1 bootSeaExempt: sea-crossing at minute 0, 10, 29:59 with the flag", CoinWatch.bootSeaExempt(true, "sea-crossing", 0) && CoinWatch.bootSeaExempt(true, "sea-crossing", 10 * MIN) && CoinWatch.bootSeaExempt(true, "sea-crossing", 30 * MIN - 1));
        check("B2 not from minute 30 on, not with the flag off, not another kind, not a negative clock", !CoinWatch.bootSeaExempt(true, "sea-crossing", 30 * MIN) && !CoinWatch.bootSeaExempt(true, "sea-crossing", 300 * MIN)
                && !CoinWatch.bootSeaExempt(false, "sea-crossing", 10 * MIN) && !CoinWatch.bootSeaExempt(true, "long-teleport", 10 * MIN) && !CoinWatch.bootSeaExempt(true, "boat", 0) && !CoinWatch.bootSeaExempt(true, null, 0) && !CoinWatch.bootSeaExempt(true, "sea-crossing", -1));
        check("B3 the window is 30 min and the flag is " + CoinWatch.BOOT_SEA + " (default off)", CoinWatch.BOOT_SEA_MS == 30 * MIN && CoinWatch.BOOT_SEA == Boolean.getBoolean("coinwatch.bootSeaExempt"));
        if (!CoinWatch.ON) { check("B4 needs CoinWatch on (no -Dcoinwatch=false)", false); return; }
        CoinWatch.KINDS.clear();
        for (int i = 0; i < 100; i++) CoinWatch.countKind("sea-crossing", true, 10 * MIN);
        int early = kind("sea-crossing");
        for (int i = 0; i < 100; i++) CoinWatch.countKind("sea-crossing", true, 31 * MIN);
        int late = kind("sea-crossing");
        check("B4 countKind with the flag: 100 crossings at minute 10 count " + early + " (expect 0), 100 at minute 31 count " + late + " (expect 100, over maxKind " + CoinWatch.MAX_KIND + ": the alarm is armed)", early == 0 && late == 100 && late > CoinWatch.MAX_KIND);
        CoinWatch.KINDS.clear();
        for (int i = 0; i < 100; i++) { CoinWatch.countKind("sea-crossing", false, 10 * MIN); CoinWatch.countKind("long-teleport", true, 10 * MIN); CoinWatch.countKind("boat", true, 0); }
        check("B5 flag off at minute 10: " + kind("sea-crossing") + " crossings counted (expect 100); other kinds with the flag: long-teleport " + kind("long-teleport") + ", boat " + kind("boat") + " (expect 100, 100)", kind("sea-crossing") == 100 && kind("long-teleport") == 100 && kind("boat") == 100);
        CoinWatch.KINDS.clear();
        long boot = Ledger.bootAt, since = System.currentTimeMillis() - boot; // Ledger loaded first (its bootAt is its class-load time)
        for (int i = 0; i < 5; i++) CoinWatch.countKind("sea-crossing");
        int want = CoinWatch.bootSeaExempt(CoinWatch.BOOT_SEA, "sea-crossing", since) ? 0 : 5;
        check("B6 CoinWatch.paid's own call (this run: flag " + CoinWatch.BOOT_SEA + ", " + since / MIN + " min after boot): 5 crossings count " + kind("sea-crossing") + " (expect " + want + ")", kind("sea-crossing") == want);
        CoinWatch.KINDS.clear();
    }

    static SmartAdventurers.Job hunt(String name) { for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(name)) return j; return null; }

    static SmartAdventurers.Brain bot(Location at, int coins) {
        AIPlayer b = new AIPlayer(at);
        SmartAdventurers.finishTutorial(b);
        b.getInventory().clear(); for (Item it : b.getBank().toArray()) if (it != null) b.getBank().remove(it);
        int[][] lv = {{Skills.HITPOINTS, 40}, {Skills.ATTACK, 40}, {Skills.STRENGTH, 40}, {Skills.DEFENCE, 40}};
        for (int[] l : lv) { b.getSkills().setStaticLevel(l[0], l[1]); b.getSkills().setLevel(l[0], l[1]); }
        try { b.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
        long have = Ledger.Wallet.coins(b); if (have < coins) Ledger.Wallet.give(b, coins - have);
        SmartAdventurers.Brain br = new SmartAdventurers.Brain();
        br.bot = b; br.scriptAPI = new core.game.bots.ScriptAPI(b); br.nextThink = Long.MAX_VALUE;
        return br;
    }
    static void arm(SmartAdventurers.Brain br, String task, String name, int teles) {
        Slayer.St s = br.slaySt;
        s.had = content.global.skill.slayer.Tasks.valueOf(task); s.names = new java.util.HashSet<>(java.util.Arrays.asList(name.toLowerCase()));
        s.streak = true; s.parkedUntil = 0; s.trip = null; s.teles = teles; s.paidSnap = -1; s.exempt = 0; s.capLog = 0;
    }

    static void fix9(SmartAdventurers.Brain br) {
        SmartAdventurers.Job dw = hunt("Desert Wolf");
        if (!Slayer.ON || dw == null) { log("RESULT SKIP F: needs -Dbots.slayer=true and the Desert Wolf hunt"); return; }
        boolean on = Slayer.FIX9B; String fl = "fix9 " + Slayer.FIX9 + " fix9b " + on;
        Location sp = dw.hunt.spot, here = br.bot.getLocation();
        log("F bot " + br.bot.getUsername() + " at " + here + " with " + Ledger.Wallet.coins(br.bot) + " gp, spot " + sp + ", desertSide(spot) " + AfkTasks.Runner.desertSide(sp) + " desertSide(bank) " + AfkTasks.Runner.desertSide(here) + ", passVia " + AfkTasks.Runner.passVia(here, sp));
        int pl = SmartAdventurers.plannedTiles(br.bot, sp, new int[] {6}, dw.label());
        check("F1 plannedTiles Al Kharid bank -> Desert Wolfs (" + fl + "): " + pl + " (expect " + (on ? "a route" : "-1") + ")", on ? pl > 0 : pl == -1);
        arm(br, "WOLVES", "desert wolf", 0);
        String g = Slayer.openGate(br, dw, br.effectiveCombat(), System.currentTimeMillis(), new int[] {6});
        check("F2 Slayer.openGate with a plan budget (" + fl + "): " + g + " (expect " + (on ? "open" : "noRoute(p73)") + ")", on ? g == null : "noRoute(p73)".equals(g));
        int got = 0;
        for (int i = 0; i < 20; i++) { arm(br, "WOLVES", "desert wolf", 0); if (br.chooseJob(false) == dw) got++; }
        check("F3 chooseJob -> Slayer.taskTrip, WOLVES task from the bank (" + fl + "): Desert Wolfs " + got + "/20 (expect " + (on ? "some" : "0") + ")", on ? got > 0 : got == 0);
        br.slaySt.had = null; br.slaySt.names = java.util.Collections.emptySet(); br.slaySt.streak = false; br.slaySt.trip = null;
    }

    static void cap(SmartAdventurers.Brain br) {
        if (!Slayer.ON || !Ledger.CLERK_FIX2) { log("RESULT SKIP C: needs -Dbots.slayer=true -Dclerk.fix2=true"); return; }
        boolean on = Slayer.CAP_FIX; int min = AfkTasks.Runner.TELEPORT_MIN;
        SmartAdventurers.Job known = null, none = null; double kd = -1;
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) {
            if (j.hunt == null || j.hunt.spot == null || j.hunt.spot.getY() <= 6400) continue;
            Location[] e = AfkTasks.Runner.entrance(j.hunt.spot);
            if (e == null) { if (none == null) none = j; continue; }
            double d = Slayer.flat(j.hunt.spot).getDistance(e[1]);
            if (d > kd) { kd = d; known = j; }
        }
        if (known == null) log("RESULT SKIP C1: no dungeon hunt with a known entrance");
        else {
            Location ent = AfkTasks.Runner.entrance(known.hunt.spot)[1];
            br.bot.setLocation(ent);
            boolean oldFar = kd > min;
            arm(br, "WOLVES", known.hunt.name, 3);
            boolean far = Slayer.tripFar(br, known), took = Slayer.taskTrip(br, known, false);
            check("C1 cap 3/3, bot at the entrance " + ent + " of " + known.label() + " " + known.hunt.spot + " (2D " + (int) kd + " tiles; capFix " + on + "): far " + far + ", trip taken " + took + " (expect " + (on ? "walk, taken" : oldFar ? "far, refused" : "walk, taken") + ")",
                    on ? !far && took : far == oldFar && took == !oldFar);
        }
        if (none == null) log("RESULT SKIP C2: no dungeon hunt without a known entrance");
        else {
            Location above = Slayer.flat(none.hunt.spot);
            br.bot.setLocation(above);
            arm(br, "WOLVES", none.hunt.name, 3);
            boolean far = Slayer.tripFar(br, none), took = Slayer.taskTrip(br, none, false);
            check("C2 cap 3/3, bot right above " + none.label() + " " + none.hunt.spot + " (no known entrance: the runner teleports; capFix " + on + "): far " + far + ", trip taken " + took + " (expect " + (on ? "far, refused" : "walk, taken") + ")",
                    on ? far && !took : !far && took);
        }
        // C3: the runner's hooks on a running trip
        SmartAdventurers.Job any = known != null ? known : none;
        String key = br.bot.getUsername().toLowerCase(java.util.Locale.ROOT);
        if (any == null || !CoinWatch.ON) { log("RESULT SKIP C3: needs a dungeon hunt and CoinWatch"); return; }
        SmartAdventurers.BRAINS.put(key, br);
        try {
            arm(br, "WOLVES", any.hunt.name, 2);
            Slayer.St s = br.slaySt; s.trip = any; s.paidSnap = Slayer.paidCount(br.bot);
            CoinWatch.paid(br.bot, "test-emergency");            // the paid action payTeleport counts
            Slayer.exemptLeg(br.bot, "emergency", false, 0);     // the call AfkTasks.Runner.flee makes right after it
            int counted = Slayer.capCounted(Slayer.CAP_FIX, Slayer.paidDelta(s.paidSnap, Slayer.paidCount(br.bot)), s.exempt);
            boolean stall = Slayer.stallOk(br.bot);
            check("C3 trip at 2/3 + one paid emergency teleport (capFix " + on + "): exempt " + s.exempt + ", trip counts " + counted + ", stall teleport allowed " + stall + " (expect " + (on ? "1, 0, true" : "0, 1, false") + ")",
                    on ? s.exempt == 1 && counted == 0 && stall : s.exempt == 0 && counted == 1 && !stall);
            Slayer.exemptLeg(br.bot, "return", true, 0); int e1 = s.exempt; Slayer.exemptLeg(br.bot, "return", true, 1); Slayer.exemptLeg(br.bot, "bank-above", true, 1);
            check("C4 the trip's first paid leg to a dungeon counts, later underground legs are exempt (capFix " + on + "): exempt " + e1 + " -> " + s.exempt + " (expect " + (on ? "1 -> 3" : "0 -> 0") + ")", on ? e1 == 1 && s.exempt == 3 : e1 == 0 && s.exempt == 0);
            // C5: the hourly PAID reset in the middle of the trip (the count now is under the snapshot), 2 exempt legs noted before it
            arm(br, "WOLVES", any.hunt.name, 2); s.trip = any; int nowC = Slayer.paidCount(br.bot); s.paidSnap = nowC + 5; s.exempt = 2;
            boolean stall5 = Slayer.stallOk(br.bot); int c5 = Slayer.tripCounted(Slayer.CAP_FIX, s.paidSnap, nowC, s.exempt);
            check("C5 PAID reset mid-trip (snapshot " + s.paidSnap + ", count now " + nowC + ", 2 exempt legs before it; capFix " + on + "): trip counts " + c5 + " (expect " + nowC + "), stall teleport at 2/3 allowed " + stall5 + " (expect false)", nowC >= 1 && c5 == nowC && !stall5);
        } finally { SmartAdventurers.BRAINS.remove(key); br.slaySt.trip = null; br.slaySt.had = null; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.w1slayer")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; SmartAdventurers.Brain a, c;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t < 150) return false;
                    if (SmartAdventurers.JOBS.isEmpty() || !NavPlanner.built) { if (t > 3000) { log("RESULT FAIL no JOBS (" + SmartAdventurers.JOBS.size() + ") or no planner (" + NavPlanner.built + ")"); log("DONE"); return true; } return false; }
                    if (a == null) { a = bot(AL_KHARID_BANK, 3_000); c = bot(LUMBRIDGE, 20_000); log("bots in, " + SmartAdventurers.JOBS.size() + " jobs, planner built; flags fix9 " + Slayer.FIX9 + " fix9b " + Slayer.FIX9B + " capFix " + Slayer.CAP_FIX + " clerk.fix2 " + Ledger.CLERK_FIX2 + " bootSea " + CoinWatch.BOOT_SEA); t = 150; return false; }
                    if (t < 200) return false;
                    fix9(a);
                    cap(c);
                    pure();
                    log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " SUMMARY pass=" + pass + " fail=" + fail);
                    log("DONE");
                    return true;
                } catch (Throwable e) { log("RESULT FAIL error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
            }
        });
    }
}
