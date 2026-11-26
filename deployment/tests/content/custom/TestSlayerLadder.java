package content.custom;

import content.global.skill.slayer.Master;
import content.global.skill.slayer.Tasks;
import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

import java.util.Set;

/**
 * B43 audit items 5-8 unit (pure, no bots): Slayer.masterFor (item 5 ladder), blockedPlan (item 6 points), bankFirst (item 7),
 * errandDue poorOk (item 8), plus the Vannaka allow list and the flag wiring. Offline with main() (java -cp server.jar;out); in a
 * world with -Dtest.slayerLadder=true it runs the same checks after JOBS exist, adds the served Vannaka list, prints
 * "[TestSlayerLadder] RESULT PASS/FAIL pass=N fail=M" and then "[TestSlayerLadder] DONE".
 * -Dslayerladder.expect=on|off (optional) also checks Slayer.VANNAKA against it.
 */
public final class TestSlayerLadder implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestSlayerLadder] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.slayerLadder")) return;
        GameWorld.getPulser().submit(new Pulse(20) {
            int waits;
            @Override public boolean pulse() {
                boolean hunts = false; for (content.custom.SmartAdventurers.Job j : content.custom.SmartAdventurers.JOBS) if (j.hunt != null) { hunts = true; break; }
                if (!hunts && ++waits < 60) return false; // wait (up to ~12 min) for HuntMap hunts to join JOBS
                log("hunts in JOBS=" + hunts + " after " + waits + " waits");
                try { checks(); world(); } catch (Throwable ex) { fail++; log("error " + ex); }
                log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
                log("DONE");
                return true;
            }
        });
    }

    public static void main(String[] a) {
        try { checks(); } catch (Throwable ex) { fail++; log("error " + ex); }
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
        log("DONE");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void checks() {
        Master T = Master.TURAEL, V = Master.VANNAKA;
        // item 5: masterFor (spec L155-157); the 4th arg is the overlap coin (seeded dice)
        check("masterFor(39,5,true)=TURAEL", Slayer.masterFor(39, 5, true, true) == T);
        check("masterFor(40,1,true)=TURAEL (needs 2 done)", Slayer.masterFor(40, 1, true, true) == T);
        check("masterFor(44,2,true,heads)=VANNAKA", Slayer.masterFor(44, 2, true, true) == V);
        check("masterFor(44,2,true,tails)=TURAEL", Slayer.masterFor(44, 2, true, false) == T);
        check("masterFor(45,2,true,tails)=VANNAKA (Turael only under 45)", Slayer.masterFor(45, 2, true, false) == V);
        check("masterFor(60,2,true)=VANNAKA", Slayer.masterFor(60, 2, true, false) == V);
        check("masterFor(60,2,false)=TURAEL", Slayer.masterFor(60, 2, false, true) == T);
        check("masterFor(70,2,false)=null (Turael max 69)", Slayer.masterFor(70, 2, false, true) == null);
        check("masterFor(80,0,true)=TURAEL (slayer3: earn the 2 tasks)", Slayer.masterFor(80, 0, true, true) == T);
        check("masterFor(80,0,false)=TURAEL (slayer5: chooseMaster passes can=false under 2 done)", Slayer.masterFor(80, 0, false, true) == T); check("masterFor(80,1,false)=TURAEL", Slayer.masterFor(80, 1, false, false) == T); check("masterFor(99,0,false)=TURAEL", Slayer.masterFor(99, 0, false, true) == T); check("masterFor(100,0,false)=null", Slayer.masterFor(100, 0, false, true) == null);
        check("masterFor(99,2,true)=VANNAKA", Slayer.masterFor(99, 2, true, false) == V);
        check("masterFor(100,9,true)=null", Slayer.masterFor(100, 9, true, true) == null);
        final int om = Slayer.VANNAKA_OPEN_MIN;
        check("ladder: 80, 0 done, every Vannaka input good = TURAEL (the caller rule makes can=false under 2 done)", Slayer.ladder(80, 80, 0, true, true, 13, true) == T && !Slayer.canVannaka(80, 80, 0, true, true, 13));
        check("ladder: 80, 1 done = TURAEL", Slayer.ladder(80, 80, 1, true, true, 13, false) == T);
        check("ladder: 80, 2 done, open under the minimum = null (the farm soak case: open 0-2 of 13)", Slayer.ladder(80, 80, 2, true, true, om - 1, true) == null);
        check("ladder: 60, 2 done, open under the minimum = TURAEL", Slayer.ladder(60, 60, 2, true, true, om - 1, true) == T);
        check("ladder: 60, 2 done, open at the minimum = VANNAKA", Slayer.ladder(60, 60, 2, true, true, om, false) == V);
        check("ladder: 80, 2 done, open at the minimum = VANNAKA", Slayer.ladder(80, 80, 2, true, true, om, false) == V);
        check("ladder: 80, 2 done, no food = null", Slayer.ladder(80, 80, 2, true, false, 13, true) == null);
        check("ladder: 80, 2 done, core req fails = null", Slayer.ladder(80, 80, 2, false, true, 13, true) == null);
        check("ladder: 60/39 effective, 2 done = TURAEL", Slayer.ladder(60, 39, 2, true, true, 13, true) == T);
        check("ladder: 100 = null", Slayer.ladder(100, 100, 9, true, true, 13, true) == null);
        check("masterFor(0,9,true)=null", Slayer.masterFor(0, 9, true, true) == null);
        check("eligibleLadder combat 85 after combat", Slayer.eligibleLadder(true, true, 85));
        check("!eligibleLadder combat 100", !Slayer.eligibleLadder(true, true, 100));
        check("!eligible combat 85 (ladder off as before)", !Slayer.eligible(true, true, 85));
        check("ladderOn needs flag+park+food", Slayer.ladderOn(true, true, true) && !Slayer.ladderOn(true, false, true) && !Slayer.ladderOn(true, true, false) && !Slayer.ladderOn(false, true, true));
        check("masterName Turael/Vannaka/null", "Turael".equals(Slayer.masterName(T)) && "Vannaka".equals(Slayer.masterName(V)) && "Turael".equals(Slayer.masterName(null)));
        check("masterAt Turael unchanged", Slayer.masterAt(null).equals(Slayer.TURAEL) && Slayer.masterAt(T).equals(Slayer.TURAEL));
        check("masterAt Vannaka 3147,9914", Slayer.masterAt(V).getX() == 3147 && Slayer.masterAt(V).getY() == 9914);
        // slayer4: masterDist with the door model (pure; the door here is the hint tile, the world resolves it with walkable)
        core.game.world.map.Location door = Slayer.VANNAKA_DOOR_HINT, edgeBank = core.game.world.map.Location.create(3094, 3493, 0);
        core.game.world.map.Location fally = core.game.world.map.Location.create(2965, 3380, 0), ardy = core.game.world.map.Location.create(2662, 3305, 0);
        core.game.world.map.Location underV = core.game.world.map.Location.create(3145, 9910, 0), underFar = core.game.world.map.Location.create(2884, 9798, 0);
        int tm = AfkTasks.Runner.TELEPORT_MIN;
        check("door hint on the surface by the trapdoor hut", door.getY() < 6400 && door.getDistance(core.game.world.map.Location.create(3097, 3468, 0)) <= 5);
        check("masterDist Edgeville bank < TELEPORT_MIN", Slayer.masterDist(edgeBank, V, door) < tm);
        check("masterDist Falador < TELEPORT_MIN (157 tiles: walks to the door)", Slayer.masterDist(fally, V, door) < tm);
        check("masterDist Ardougne > TELEPORT_MIN (pays one teleport)", Slayer.masterDist(ardy, V, door) > tm);
        check("masterDist underground by Vannaka = 0", Slayer.masterDist(underV, V, door) == 0);
        check("masterDist underground far from Vannaka (Taverley dungeon) > TELEPORT_MIN", Slayer.masterDist(underFar, V, door) > tm);
        check("masterDist no door (flag off) = old getDistance", Slayer.masterDist(edgeBank, V, null) == edgeBank.getDistance(Slayer.VANNAKA_AT) && Slayer.masterDist(edgeBank, V, null) > tm);
        check("masterDist Turael unchanged with a door", Slayer.masterDist(fally, T, door) == fally.getDistance(Slayer.TURAEL) && Slayer.masterDist(fally, null, door) == fally.getDistance(Slayer.TURAEL));
        check("underVannaka by him, not on the surface", Slayer.underVannaka(underV) && !Slayer.underVannaka(edgeBank) && !Slayer.underVannaka(underFar));
        // Vannaka allow list
        Set<Tasks> va = Slayer.vannakaAllow();
        boolean denyOk = true, lvlOk = true;
        for (Tasks t : va) { if (Slayer.VANNAKA_DENY.contains(t.name())) denyOk = false; if (t.levelReq > 1) lvlOk = false; }
        check("vannakaAllow size " + va.size() + " >= 8", va.size() >= 8);
        check("vannakaAllow has no deny-list task", denyOk);
        check("vannakaAllow levelReq <= 1 only", lvlOk);
        check("vannakaAllow has HILL_GIANTS", va.contains(Tasks.HILL_GIANTS));
        check("vannakaAllow has no BASILISKS/CAVE_CRAWLERS", !va.contains(Tasks.BASILISKS) && !va.contains(Tasks.CAVE_CRAWLERS));
        check("allowFor(TURAEL) is ALLOW", Slayer.allowFor(T) == Slayer.ALLOW && Slayer.allowFor(null) == Slayer.ALLOW);
        log("vannakaAllow " + va);
        // item 6: blockedPlan (spec L172-174)
        check("blockedPlan(VANNAKA,30,2,x)=CANCEL", Slayer.blockedPlan(V, 30, 2, false) == Slayer.Blocked.CANCEL && Slayer.blockedPlan(V, 30, 2, true) == Slayer.Blocked.CANCEL);
        check("blockedPlan(VANNAKA,29,2,false)=SWAP", Slayer.blockedPlan(V, 29, 2, false) == Slayer.Blocked.SWAP);
        check("blockedPlan(VANNAKA,29,2,true)=PARK (Turael would give it again)", Slayer.blockedPlan(V, 29, 2, true) == Slayer.Blocked.PARK);
        check("blockedPlan(TURAEL,99,2,x)=PARK", Slayer.blockedPlan(T, 99, 2, false) == Slayer.Blocked.PARK && Slayer.blockedPlan(T, 99, 2, true) == Slayer.Blocked.PARK);
        check("blockedPlan parks<2 = PARK", Slayer.blockedPlan(V, 500, 1, false) == Slayer.Blocked.PARK && Slayer.blockedPlan(V, 0, 0, false) == Slayer.Blocked.PARK);
        check("onTuraelList COWS", Slayer.onTuraelList(Tasks.COWS));
        check("!onTuraelList HILL_GIANTS", !Slayer.onTuraelList(Tasks.HILL_GIANTS));
        // item 7: bankFirst (spec L185)
        check("bankFirst(10,0,8)=true", Slayer.bankFirst(10, 0, 8));
        check("bankFirst(20,12,8)=false", !Slayer.bankFirst(20, 12, 8));
        check("bankFirst(13,12,8)=true (pack over half full)", Slayer.bankFirst(13, 12, 8));
        check("bankFirst(20,0,0)=false (cows need no food)", !Slayer.bankFirst(20, 0, 0));
        slayer6();
        slayer7();
        if (Slayer.FIX8) slayer8(); // flag off: the 148 lines above, nothing else
        // item 8: errandDue poorOk (spec L193)
        long now = 1_000_000_000L;
        check("errandDue(coins 0, poorOk)=true", Slayer.errandDue(true, false, now, 0, 0, 0, true, false, false, false, true, true));
        check("errandDue(coins 0, no poorOk)=false", !Slayer.errandDue(true, false, now, 0, 0, 0, true, false, false, false, true, false));
        check("errandDue(coins 0) 11-arg = old rule", !Slayer.errandDue(true, false, now, 0, 0, 0, true, false, false, false, true));
        check("errandDue(coins 5000) 11-arg", Slayer.errandDue(true, false, now, 0, 0, 5_000, true, false, false, false, true));
        check("errandDue poorOk still needs !hasTask", !Slayer.errandDue(true, true, now, 0, 0, 0, true, false, false, false, true, true));
        // flag wiring: each effective switch needs its flag and its dependencies
        boolean vFlag = Boolean.getBoolean("bots.slayer.vannaka"), food = Boolean.getBoolean("bots.fight.foodGate");
        check("Slayer.VANNAKA == flag && park && foodGate", Slayer.VANNAKA == (vFlag && Slayer.PARK && food));
        check("Slayer.POINTS == flag && VANNAKA", Slayer.POINTS == (Boolean.getBoolean("bots.slayer.points") && Slayer.VANNAKA));
        check("Slayer.BANK_FIRST == flag", Slayer.BANK_FIRST == Boolean.getBoolean("bots.slayer.bankFirst"));
        check("Slayer.POOR_OK == flag && foodGate", Slayer.POOR_OK == (Boolean.getBoolean("bots.slayer.poorOk") && food));
        check("Slayer.DOOR == flag && VANNAKA", Slayer.DOOR == (Boolean.getBoolean("bots.slayer.vannakaDoor") && Slayer.VANNAKA));
        check("Slayer.SETTLE_FIRST == flag", Slayer.SETTLE_FIRST == Boolean.getBoolean("bots.slayer.settleFirst"));
        if (!Slayer.DOOR) check("door() null with the flag off", Slayer.door() == null);
        String want = System.getProperty("slayerladder.expect", "");
        if (!want.isEmpty()) check("Slayer.VANNAKA == expect " + want, Slayer.VANNAKA == "on".equals(want));
        log("flags vannaka=" + Slayer.VANNAKA + " points=" + Slayer.POINTS + " bankFirst=" + Slayer.BANK_FIRST + " poorOk=" + Slayer.POOR_OK);
    }

    /** slayer6: the pure deciders errand(), tick(), chooseMaster() and taskTrip() call, with the argument shapes those callers make:
     *  chooseMaster hands ladder6 (combat, eff, done, false, false, 0) on a pre/core/food refusal, else (.., true, true, openN);
     *  errand hands preTravel the openSet size and its closed text; tick hands pick6 the engine roll, m::getAmount and m::clear. */
    static void slayer6() {
        Master T = Master.TURAEL, V = Master.VANNAKA; final boolean ON = true, OFF = false;
        boolean knob = System.getProperty("bots.slayer.vannakaOpenMin") != null;
        if (!knob) { check("s6 defaults: minimum 5 with the flag off, 2 with it on", Slayer.VANNAKA_OPEN_MIN == 5 && Slayer.VANNAKA_OPEN_MIN6 == 2); check("s6 rolls 40 on, openRerolls 6 off", Slayer.FIX6_ROLLS == Math.max(1, Integer.getInteger("bots.slayer.fix6Rolls", 40)) && (System.getProperty("bots.slayer.openRerolls") != null || Slayer.OPEN_REROLLS == 6)); }
        check("s6 FIX6 == flag", Slayer.FIX6 == Boolean.getBoolean("bots.slayer.fix6"));
        boolean same = true;
        for (int c : new int[] { 3, 39, 40, 44, 45, 63, 69, 70, 86, 99, 100, 126 }) for (int d = 0; d <= 3; d++) for (int o = -1; o <= 6; o++) for (int coin = 0; coin < 2; coin++)
            same &= Slayer.ladder6(c, c, d, o >= 0, o >= 0, Math.max(0, o), coin == 1, OFF) == Slayer.ladder(c, c, d, o >= 0, o >= 0, Math.max(0, o), coin == 1);
        check("s6 ladder6 off == ladder on the whole caller grid", same);
        if (!knob) {
            check("s6 off: 80, 2 done, open 2 = no master (today)", Slayer.ladder6(80, 80, 2, true, true, 2, false, OFF) == null);
            check("s6 on: 67, 2 done, open 2 (Ghouls + Moss giants) = VANNAKA", Slayer.ladder6(67, 67, 2, true, true, 2, false, ON) == V);
            check("s6 on: 75, open 3 = VANNAKA", Slayer.ladder6(75, 75, 2, true, true, 3, false, ON) == V);
            check("s6 on: 88, open 4 = VANNAKA", Slayer.ladder6(88, 88, 2, true, true, 4, true, ON) == V);
            check("s6 on: 63, open 1 (Ghouls only) = TURAEL", Slayer.ladder6(63, 63, 2, true, true, 1, true, ON) == T);
            check("s6 on: 52, open 0 = TURAEL", Slayer.ladder6(52, 52, 2, true, true, 0, true, ON) == T);
            check("s6 on: 80, open 1 = TURAEL (70-99 fallback, was none)", Slayer.ladder6(80, 80, 2, true, true, 1, false, ON) == T);
            check("s6 on: 99, open 0 = TURAEL", Slayer.ladder6(99, 99, 7, true, true, 0, false, ON) == T);
            check("s6 on: 44 open 2 heads = VANNAKA, tails = TURAEL", Slayer.ladder6(44, 44, 2, true, true, 2, true, ON) == V && Slayer.ladder6(44, 44, 2, true, true, 2, false, ON) == T);
        }
        check("s6 on: 85, food or core refusal (false,false,0) = TURAEL", Slayer.ladder6(85, 85, 4, false, false, 0, true, ON) == T);
        check("s6 on: 80, 0 done (pre) = TURAEL", Slayer.ladder6(80, 80, 0, false, false, 0, true, ON) == T);
        check("s6 on: 100 = no master, 126 = no master", Slayer.ladder6(100, 100, 9, false, false, 0, true, ON) == null && Slayer.ladder6(126, 126, 0, false, false, 0, true, ON) == null);
        check("s6 on: 90 with open 13 = VANNAKA", Slayer.ladder6(90, 90, 2, true, true, 13, false, ON) == V);
        check("s6 on: eff 35 under a 60 combat (pre) = TURAEL", Slayer.ladder6(60, 35, 5, false, false, 0, true, ON) == T);
        // pre-travel: closed texts as openSet writes them (live shapes)
        String farPoor = "BEARS(Black bears=far452+under5k) WOLVES(White wolves=far607+under5k)", hard = "GHOULS(Ghouls=level 59>52) HILL_GIANTS(Hill Giants=unreachable)";
        check("s6 preTravel: open 0, far + poor = stay, short cooldown", Slayer.preTravel(ON, true, false, 0, farPoor) == Slayer.FIX6_SHORT_MS && Slayer.FIX6_SHORT_MS < Slayer.ERRAND_COOL_MS);
        check("s6 preTravel: open 0, levels and gates = stay, 60 min", Slayer.preTravel(ON, true, false, 0, hard) == Slayer.SKIP_MS);
        check("s6 preTravel: open 0, full spot = short", Slayer.preTravel(ON, true, false, 0, "COWS(Cows=full)") == Slayer.FIX6_SHORT_MS);
        check("s6 preTravel: open 1 = go", Slayer.preTravel(ON, true, false, 1, farPoor) == 0);
        check("s6 preTravel: flag off = go (old path)", Slayer.preTravel(OFF, true, false, 0, farPoor) == 0);
        check("s6 preTravel: openCheck off = go, task held = go", Slayer.preTravel(ON, false, false, 0, hard) == 0 && Slayer.preTravel(ON, true, true, 0, hard) == 0);
        check("s6 preTravel: empty closed text = 60 min", Slayer.preTravel(ON, true, false, 0, "") == Slayer.SKIP_MS);
        check("s6 afterFare: 5,200 less a 400 fare = 4,800 (under the 5k walk gate); no fare = same", Slayer.afterFare(5_200, true, 400) == 4_800 && Slayer.afterFare(5_200, false, 400) == 5_200 && Slayer.afterFare(50, true, 400) == 0);
        // the roll loop, driven like tick(): roll = generate + getTask, amount = getAmount, clear = clear
        final Tasks W = Tasks.valueOf("WOLVES"), B = Tasks.valueOf("BEARS");
        java.util.List<Tasks> others = new java.util.ArrayList<>(); for (Tasks x : Tasks.values()) if (x != W && x != B) others.add(x);
        final Set<Tasks> allow = java.util.EnumSet.of(W);
        final int[] n = {0}, clears = {0}, amt = {20}; final Tasks[] cur = {null};
        java.util.function.Supplier<Tasks> at25 = () -> { n[0]++; cur[0] = n[0] == 25 ? W : others.get(n[0] % others.size()); return cur[0]; };
        Runnable clear = () -> { clears[0]++; cur[0] = null; };
        Tasks got = Slayer.pick6(at25, () -> amt[0], clear, allow, 40, true, new int[1]);
        check("s6 pick6: the allowed task on roll 25 is taken (rolls " + n[0] + ", clears " + clears[0] + ")", got == W && n[0] == 25 && clears[0] == 24 && cur[0] == W);
        n[0] = 0; clears[0] = 0;
        check("s6 old pick with 6 re-rolls misses the same sequence (F2)", Slayer.pick(at25, clear, allow, 6) == null && n[0] == 7);
        n[0] = 0; clears[0] = 0;
        java.util.function.Supplier<Tasks> never = () -> { n[0]++; cur[0] = others.get(n[0] % others.size()); return cur[0]; };
        check("s6 pick6: none allowed = null after exactly 40 rolls, every roll cleared", Slayer.pick6(never, () -> amt[0], clear, allow, 40, true, new int[1]) == null && n[0] == 40 && clears[0] == 40 && cur[0] == null);
        n[0] = 0; amt[0] = 40; int[] sz = {0};
        java.util.function.Supplier<Tasks> always = () -> { n[0]++; cur[0] = W; return W; };
        check("s6 pick6: a 40-kill task is re-rolled 3 times, the 4th is kept", Slayer.pick6(always, () -> amt[0], clear, allow, 40, true, sz) == W && n[0] == 4 && sz[0] == 3);
        n[0] = 0; sz[0] = 0;
        check("s6 pick6: Vannaka (no size rule) keeps the first allowed task whatever its size", Slayer.pick6(always, () -> amt[0], clear, allow, 40, false, sz) == W && n[0] == 1 && sz[0] == 0);
        n[0] = 0; sz[0] = 0;
        java.util.function.Supplier<Tasks> bigThenLate = () -> { n[0]++; cur[0] = n[0] == 1 || n[0] == 47 ? W : others.get(n[0] % others.size()); return cur[0]; };
        check("s6 pick6: a size re-roll cannot end with none (allowed again on roll 47 of at most 80)", Slayer.pick6(bigThenLate, () -> amt[0], clear, allow, 40, true, sz) == W && n[0] == 47);
        amt[0] = 20; int ok6 = 0, ok0 = 0, outside = 0; java.util.Random rnd = new java.util.Random(6);
        java.util.function.Supplier<Tasks> dice = () -> { cur[0] = rnd.nextInt(13) == 0 ? W : others.get(rnd.nextInt(others.size())); return cur[0]; };
        for (int i = 0; i < 2000; i++) { Tasks r = Slayer.pick6(dice, () -> 15 + rnd.nextInt(36), clear, allow, 40, true, new int[1]); if (r != null) ok6++; if (r != null && !allow.contains(r)) outside++; if (Slayer.pickSmall(dice, () -> 15 + rnd.nextInt(36), clear, allow, new int[1], 6) != null) ok0++; }
        log("s6 one open task in 13, 2000 errands: fix6 loop " + ok6 + " assigned, old loop " + ok0);
        check("s6 pick6: 1 open task of 13 = a task on 9 errands in 10, at least double the old loop, never outside the open set", ok6 >= 1800 && ok6 >= 2 * ok0 && outside == 0);
        // teleport cap
        check("s6 teleStep: far, 0-2 paid = PAY", Slayer.teleStep(true, 0, true) == Slayer.Tele.PAY && Slayer.teleStep(true, 2, true) == Slayer.Tele.PAY);
        check("s6 teleStep: far, 3 paid = CAPPED; near = WALK", Slayer.teleStep(true, 3, true) == Slayer.Tele.CAPPED && Slayer.teleStep(true, 3, false) == Slayer.Tele.WALK && Slayer.teleStep(true, 0, false) == Slayer.Tele.WALK);
        check("s6 teleStep: clerk.fix2 off = PAY as before", Slayer.teleStep(false, 9, true) == Slayer.Tele.PAY);
        Slayer.St st = new Slayer.St();
        boolean each = true; for (int i = 0; i < Slayer.TELE_CAP; i++) { each &= Slayer.capOk(st); Slayer.capCount(null, st, 1, "test"); }
        check("s6 counter: 3 paid errand teleports allowed, then capOk follows clerk.fix2 (" + Ledger.CLERK_FIX2 + ")", each && st.teles == 3 && Slayer.capOk(st) == !Ledger.CLERK_FIX2 && Slayer.teleStep(true, st.teles, true) == Slayer.Tele.CAPPED);
        check("s6 capOk: no state (old callers) = pay", Slayer.capOk(null));
        check("s6 paidDelta: 5 -> 7 = 2, hourly restart 5 -> 2 = 2, no CoinWatch = 0", Slayer.paidDelta(5, 7) == 2 && Slayer.paidDelta(5, 2) == 2 && Slayer.paidDelta(-1, 4) == 0 && Slayer.paidDelta(3, -1) == 0);
    }

    /** slayer7, offline, with the values the callers make. The chain is the caller's own: HuntMap.build asks Slayer.hillSpot for the
     *  Hill Giant spot, SmartAdventurers' JOBS build asks Slayer.huntLevel7 for the job level, openGate reads Gates (reachable) and
     *  Slayer.needLevel, chooseMaster hands ladder7 (combat, eff, done, true, true, openN, coin, FIX6, FIX7) or (.., false, false, 0, ..)
     *  on a pre / core / food refusal, errand hands preTravel the predicted open count, tick rolls with pick6 (no size rule at
     *  Vannaka), and the AfkTasks stall teleport asks stallOk -> stallStep(fix2, on a task trip, tripTeles). */
    static void slayer7() {
        Master T = Master.TURAEL, V = Master.VANNAKA; final boolean ON = true, OFF = false;
        boolean knob = System.getProperty("bots.slayer.vannakaOpenMin") != null;
        check("s7 FIX7 == flag and fix6", Slayer.FIX7 == (Boolean.getBoolean("bots.slayer.fix7") && Slayer.FIX6));
        boolean same = true;
        for (int c : new int[] { 3, 39, 40, 44, 45, 50, 58, 59, 63, 69, 70, 86, 99, 100, 126 }) for (int e : new int[] { c, 35 }) for (int d = 0; d <= 3; d++) for (int o = -1; o <= 6; o++) for (int coin = 0; coin < 2; coin++) for (int f6 = 0; f6 < 2; f6++) {
            same &= Slayer.ladder7(c, e, d, o >= 0, o >= 0, Math.max(0, o), coin == 1, f6 == 1, OFF) == Slayer.ladder6(c, e, d, o >= 0, o >= 0, Math.max(0, o), coin == 1, f6 == 1);
            same &= Slayer.ladder7(c, e, d, o >= 0, o >= 0, Math.max(0, o), coin == 1, OFF, ON) == Slayer.ladder(c, e, d, o >= 0, o >= 0, Math.max(0, o), coin == 1); // fix7 without fix6 = today's code
        }
        check("s7 ladder7 off == ladder6 (fix6 on and off), and fix7 without fix6 == ladder, on the whole caller grid", same);
        check("s7 off: hunt level, hunt spot and needLevel are the old values", Slayer.huntLevel7(OFF, "Hill Giant", 41) == 41 && Slayer.huntLevel7(OFF, "Cow", 7) == 7 && Slayer.needLevel(41, 0, false, 28) == 41 && Slayer.needLevel(41, 1, false, 28) == 51 && Slayer.needLevel(20, 0, true, 30) == 70);
        check("s7 on: only the Hill Giant job changes (40), nothing is raised", Slayer.huntLevel7(ON, "Hill Giant", 41) == 40 && Slayer.huntLevel7(ON, "Moss giant", 59) == 59 && Slayer.huntLevel7(ON, "Hill Giant", 30) == 30);
        // the Hill Giant hunt as HuntMap.build + the JOBS build make it: npc level 28, the general level rule, the curated spot
        core.game.world.map.Location cur = HuntMap.CURATED.get("Hill Giant");
        int rule = 28 >= 80 ? 90 : Math.max(3, (int) (28 * 1.3) + 5);
        core.game.world.map.Location offSpot = Slayer.hillSpot(OFF, "Hill Giant", cur, 6), onSpot = Slayer.hillSpot(ON, "Hill Giant", cur, 6);
        check("s7 hillSpot: off = the curated dungeon spot, on = the surface spot 2440,3211; other names and a spawn file with under 3 surface spawns keep the curated spot",
                cur != null && offSpot == cur && onSpot == Slayer.HILL7 && onSpot.getY() < 6400 && Slayer.hillSpot(ON, "Blue dragon", cur, 6) == cur && Slayer.hillSpot(ON, "Hill Giant", cur, 2) == cur);
        boolean offKey = keyGate(offSpot), onKey = keyGate(onSpot);
        check("s7 reach: the dungeon spot is inside the brass key gate box (unreachable without the key), the surface spot is in no gate", offKey && !onKey);
        if (!knob) check("s7 openMin7: off 2 everywhere; on 1 under 59, 2 from 59", Slayer.openMin7(OFF, 40) == 2 && Slayer.openMin7(ON, 40) == 1 && Slayer.openMin7(ON, 58) == 1 && Slayer.openMin7(ON, 59) == 2 && Slayer.openMin7(ON, 99) == 2);
        for (int c : new int[] { 40, 50, 58 }) {
            // open Vannaka tasks for a keyless bot of this combat, as openSet would count them: Hill Giants only (Ghouls need 59)
            int openOff = !offKey && c >= Slayer.needLevel(Slayer.huntLevel7(OFF, "Hill Giant", rule), 0, false, 28) ? 1 : 0;
            int openOn = !onKey && c >= Slayer.needLevel(Slayer.huntLevel7(ON, "Hill Giant", rule), 0, false, 28) ? 1 : 0;
            check("s7 combat " + c + ": open Vannaka tasks " + openOff + " with fix7 off, " + openOn + " with it on (Hill Giants)", openOff == 0 && openOn == 1);
            if (knob) continue;
            check("s7 combat " + c + " off: chooseMaster shape (c,c,2,true,true," + openOff + ") = TURAEL on both coins, can = false", Slayer.ladder7(c, c, 2, true, true, openOff, true, ON, OFF) == T && Slayer.ladder7(c, c, 2, true, true, openOff, false, ON, OFF) == T && !Slayer.canVannaka(c, c, 2, true, true, openOff, Slayer.openMin7(OFF, c)));
            check("s7 combat " + c + " on: (c,c,2,true,true," + openOn + ") = VANNAKA on heads, TURAEL on tails, can = true", Slayer.ladder7(c, c, 2, true, true, openOn, true, ON, ON) == V && Slayer.ladder7(c, c, 2, true, true, openOn, false, ON, ON) == T && Slayer.canVannaka(c, c, 2, true, true, openOn, Slayer.openMin7(ON, c)));
            check("s7 combat " + c + " on: the same bot with fix7's hunt full or closed (open 0) = TURAEL", Slayer.ladder7(c, c, 2, true, true, 0, true, ON, ON) == T);
            check("s7 combat " + c + " on: 1 task done (pre), weak defence (eff 35, pre), no food (false,false,0) = TURAEL", Slayer.ladder7(c, c, 1, false, false, 0, true, ON, ON) == T && Slayer.ladder7(c, 35, 5, false, false, 0, true, ON, ON) == T && Slayer.ladder7(c, c, 5, false, false, 0, true, ON, ON) == T);
            check("s7 combat " + c + " on: errand pre-travel with the 1 predicted open task = go; with 0 and a full spot = stay 10 min", Slayer.preTravel(ON, true, false, openOn, "") == 0 && Slayer.preTravel(ON, true, false, 0, "HILL_GIANTS(Hill Giants=full)") == Slayer.FIX6_SHORT_MS);
        }
        if (!knob) {
            check("s7 on: 59 and 63 with 1 open (Ghouls only) stay TURAEL, as slayer6", Slayer.ladder7(59, 59, 2, true, true, 1, true, ON, ON) == T && Slayer.ladder7(63, 63, 2, true, true, 1, true, ON, ON) == T);
            check("s7 on: 67 open 2 = VANNAKA on both coins, 80 open 1 = TURAEL, 44 open 2 heads/tails as slayer6", Slayer.ladder7(67, 67, 2, true, true, 2, false, ON, ON) == V && Slayer.ladder7(67, 67, 2, true, true, 2, true, ON, ON) == V && Slayer.ladder7(80, 80, 2, true, true, 1, true, ON, ON) == T
                    && Slayer.ladder7(44, 44, 2, true, true, 2, true, ON, ON) == V && Slayer.ladder7(44, 44, 2, true, true, 2, false, ON, ON) == T);
            check("s7 on: 39 with 1 open = TURAEL (Vannaka needs combat 40)", Slayer.ladder7(39, 39, 5, false, false, 0, true, ON, ON) == T);
        }
        // the roll at Vannaka: one allowed task (Hill Giants, weight 7 of about 105 for a low Slayer level), no size rule
        final Tasks H = Tasks.valueOf("HILL_GIANTS");
        java.util.List<Tasks> others = new java.util.ArrayList<>(); for (Tasks x : Tasks.values()) if (x != H) others.add(x);
        final java.util.Random rnd = new java.util.Random(7); final Tasks[] cur7 = {null}; int got = 0, outside = 0;
        java.util.function.Supplier<Tasks> dice = () -> { cur7[0] = rnd.nextInt(15) == 0 ? H : others.get(rnd.nextInt(others.size())); return cur7[0]; };
        for (int i = 0; i < 2000; i++) { Tasks r = Slayer.pick6(dice, () -> 60 + rnd.nextInt(61), () -> cur7[0] = null, java.util.EnumSet.of(H), 40, false, new int[1]); if (r == H) got++; else if (r != null) outside++; }
        log("s7 Hill Giants 1 roll in 15, 2000 Vannaka errands: " + got + " assigned");
        check("s7 pick6 at Vannaka: Hill Giants on at least 85 errands in 100, never another task, sizes 60-120 kept", got >= 1700 && outside == 0);
        check("s7 the engine can roll it: HILL_GIANTS is on Vannaka's list, Slayer level 1, on our allow list", Slayer.vannakaAllow().contains(H) && H.levelReq <= 1);
        // b: the stall teleport hook
        check("s7 stallStep: clerk.fix2 off = pay as before; not on a task trip = pay", Slayer.stallStep(OFF, true, 9) && Slayer.stallStep(ON, false, 9));
        check("s7 stallStep: on a task trip 0-2 used = pay, 3 used = no", Slayer.stallStep(ON, true, 0) && Slayer.stallStep(ON, true, 2) && !Slayer.stallStep(ON, true, 3) && !Slayer.stallStep(ON, true, 4));
        check("s7 tripTeles: 2 on the task + CoinWatch 5 -> 6 this trip = 3; no CoinWatch = the task count", Slayer.tripTeles(2, 5, 6) == 3 && Slayer.tripTeles(2, -1, -1) == 2 && Slayer.tripTeles(0, 5, 5) == 0);
        // the slayer6 gap, replayed: errand 1 + trip 1 paid, then a trip that pays its long teleport (CoinWatch 5 -> 6) and stalls
        Slayer.St st = new Slayer.St(); Slayer.capCount(null, st, 1, "test errand"); Slayer.capCount(null, st, 1, "test trip");
        check("s7 cap replay: the third trip may start at 2/3", Slayer.teleStep(true, st.teles, true) == Slayer.Tele.PAY);
        st.paidSnap = 5; int count = 6; // taskTrip took the snapshot, the runner paid the long teleport
        boolean stallPaid = Slayer.stallStep(ON, true, Slayer.tripTeles(st.teles, st.paidSnap, count));
        if (stallPaid) count++;
        Slayer.capCount(null, st, Slayer.paidDelta(st.paidSnap, count), "test trip end"); // settle()
        check("s7 cap replay: the stall teleport is refused and the task ends at exactly 3/3 (slayer6 alone: 4)", !stallPaid && st.teles == Slayer.TELE_CAP);
        Slayer.St old = new Slayer.St(); old.teles = 2; int oc = 6; if (Slayer.stallStep(OFF, true, Slayer.tripTeles(old.teles, 5, oc))) oc++;
        check("s7 cap replay with clerk.fix2 off: the stall teleport is paid as before", oc == 7);
        Slayer.St early = new Slayer.St(); early.paidSnap = 5;
        check("s7 cap replay: a first trip (0 on the task, long teleport paid) still gets its stall teleport, the next stall too, the third not", Slayer.stallStep(ON, true, Slayer.tripTeles(early.teles, early.paidSnap, 6)) && Slayer.stallStep(ON, true, Slayer.tripTeles(early.teles, early.paidSnap, 7)) && !Slayer.stallStep(ON, true, Slayer.tripTeles(early.teles, early.paidSnap, 8)));
        check("s7 stallOk(null) = pay (the hook never blocks without a bot)", Slayer.stallOk(null));
    }

    /** slayer8, offline, with the values the callers make: openGate hands full8 (crowd, party, seats(j, true), waiting8's count),
     *  seats hands cap8 (flag, holder, Brain.capacity = max(1, min(4, spawns / 3)), the spot's spawns or 0 underground), waiting8
     *  hands canSeat8 / nearest8 one row per matching job, HuntMap.build hands spot8 and altOk8 its spawn counts, errand hands
     *  brokeStop8 the wallet, Brain.think asks tripFirst -> tripFirst8, chooseJob asks better -> pure8 / better8 per open spot,
     *  errand and tick hand minus8 the open set. Spawn tiles below are npc_spawns.json rows 78, 105 and 106. */
    static void slayer8() {
        final boolean ON = true, OFF = false; final int TELE = AfkTasks.Runner.TELEPORT_MIN;
        check("s8 FIX8 == flag and fix6", Slayer.FIX8 == (Boolean.getBoolean("bots.slayer.fix8") && Slayer.FIX6));
        // a: seats. capacity(j) for a hunt, as Brain.capacity computes it
        java.util.function.IntUnaryOperator cap = sp -> Math.max(1, Math.min(4, sp / 3));
        boolean offSame = true;
        for (int sp = 0; sp <= 30; sp++) for (int h = 0; h < 2; h++) offSame &= Slayer.cap8(OFF, h == 1, cap.applyAsInt(sp), sp) == cap.applyAsInt(sp) && Slayer.cap8(ON, false, cap.applyAsInt(sp), sp) == cap.applyAsInt(sp) && Slayer.cap8(ON, true, cap.applyAsInt(sp), sp) >= cap.applyAsInt(sp) && Slayer.cap8(ON, true, cap.applyAsInt(sp), sp) <= 4;
        check("s8 cap8: flag off or not a holder = Brain.capacity for 0-30 spawns; a holder never gets fewer seats, never over 4", offSame);
        check("s8 cap8 holder: White wolfs 5 spawns 1 -> 3, Desert Wolfs 5 -> 3, Hill Giants 6: 2 -> 3, Bats 6: 2 -> 3, Giant bats 7: 2 -> 4, Goblins 14: 4 -> 4",
                Slayer.cap8(ON, true, cap.applyAsInt(5), 5) == 3 && cap.applyAsInt(5) == 1 && Slayer.cap8(ON, true, cap.applyAsInt(6), 6) == 3 && cap.applyAsInt(6) == 2 && Slayer.cap8(ON, true, cap.applyAsInt(7), 7) == 4 && Slayer.cap8(ON, true, cap.applyAsInt(14), 14) == 4);
        check("s8 cap8 holder: Black bears 2 spawns stay 1 seat, 3 spawns (BEAR8) = 2; a dungeon spot (spawns not counted: 0) keeps its capacity", Slayer.cap8(ON, true, cap.applyAsInt(2), 2) == 1 && Slayer.cap8(ON, true, cap.applyAsInt(3), 3) == 2 && Slayer.cap8(ON, true, 1, 0) == 1 && Slayer.cap8(ON, true, 2, 0) == 2);
        boolean fullSame = true;
        for (int busy = 0; busy <= 5; busy++) for (int party = 1; party <= 3; party++) for (int c = 1; c <= 4; c++) for (int w = 0; w <= 3; w++) {
            String old = busy + party > c ? "full" : null, off = Slayer.full8(OFF, busy, party, c, w), on0 = Slayer.full8(ON, busy, party, c, 0);
            fullSame &= java.util.Objects.equals(old, off) && java.util.Objects.equals(old, on0);
        }
        check("s8 full8: flag off, or nobody waiting (the watch and the trip pass 0) = the old crowd gate on the whole grid", fullSame);
        check("s8 full8 on: 1 seat, free now, 1 holder waiting = full+1w; 2 seats, 1 waiting = open; 2 seats, 2 waiting = full+2w; taken now = full",
                "full+1w".equals(Slayer.full8(ON, 0, 1, 1, 1)) && Slayer.full8(ON, 0, 1, 2, 1) == null && "full+2w".equals(Slayer.full8(ON, 0, 1, 2, 2)) && "full".equals(Slayer.full8(ON, 1, 1, 1, 0)) && "full".equals(Slayer.full8(ON, 2, 1, 2, 5)));
        check("s8 a queued spot cools the errand down 10 min, as a full one (skipCool reads openSet's text)", Slayer.skipCool("BEARS(Grizzly bears=far957+under5k Black bears=full+2w)") == Slayer.FIX6_SHORT_MS && Slayer.preTravel(ON, true, false, 0, "BEARS(Black bears=full+1w)") == Slayer.FIX6_SHORT_MS);
        // s55 replay: 4 bots were handed BEARS at Turael inside 20 min with Black bears (1 seat) the only spot in reach
        int given0 = 0, given8 = 0;
        for (int waiting = 0; waiting < 4; waiting++) { if (Slayer.full8(OFF, 0, 1, cap.applyAsInt(2), waiting) == null) given0++; if (Slayer.full8(ON, 0, 1, Slayer.cap8(ON, true, cap.applyAsInt(3), 3), waiting) == null) given8++; }
        check("s8 s55 replay: BEARS handed to " + given0 + " bots in a row with the flag off, to " + given8 + " (the 2 seats) with it on", given0 == 4 && given8 == 2);
        // waiting8's rows: a holder by Turael with 300 gp: Black bears 112 tiles, Grizzly bears 567; job levels 29 / 32 as the JOBS build makes them (npc 19 and 21; the s55 log's "level 29>28")
        int lvBlack = Math.max(3, (int) (19 * 1.3) + 5), lvGrizzly = Math.max(3, (int) (21 * 1.3) + 5);
        check("s8 canSeat8: level first (" + lvBlack + ", " + lvGrizzly + "), then walking range or the 5k fare line", lvBlack == 29 && lvGrizzly == 32 && !Slayer.canSeat8(28, lvBlack, 112, 300) && Slayer.canSeat8(29, lvBlack, 112, 300) && !Slayer.canSeat8(45, lvGrizzly, 567, 300) && Slayer.canSeat8(45, lvGrizzly, 567, 5_000) && Slayer.canSeat8(45, lvGrizzly, TELE, 0) && !Slayer.canSeat8(45, lvGrizzly, TELE + 1, 4_999));
        double[] dPoor = { 567, 112 }; boolean[] okPoor = { Slayer.canSeat8(45, lvGrizzly, 567, 300), Slayer.canSeat8(45, lvBlack, 112, 300) };
        double[] dRich = { 60, 480, 300 }; boolean[] okRich = { Slayer.canSeat8(60, lvGrizzly, 60, 9_000), Slayer.canSeat8(60, lvBlack, 480, 9_000), Slayer.canSeat8(60, lvGrizzly, 300, 9_000) };
        check("s8 nearest8: a poor holder by Turael is counted at Black bears (row 1), a 9k holder in Ardougne at the nearest Grizzly spot (row 0); no row it can use = not counted",
                Slayer.nearest8(dPoor, okPoor) == 1 && Slayer.nearest8(dRich, okRich) == 0 && Slayer.nearest8(new double[] { 567 }, new boolean[] { false }) == -1 && Slayer.nearest8(new double[0], new boolean[0]) == -1);
        // b: spots. Black bear 106 and Grizzly bear 105 surface spawns outside the Wilderness, Giant bat 78 by Keep Le Faye
        int[][] black = { { 2959, 3445 }, { 2968, 3480 }, { 2968, 3490 }, { 2978, 3507 }, { 2807, 3377 }, { 3289, 3353 } };
        int[][] grizzly = { { 2632, 3280 }, { 2633, 3274 }, { 2387, 3376 }, { 2398, 3366 }, { 2412, 3378 }, { 2419, 3372 }, { 2696, 3329 }, { 2708, 3336 }, { 3230, 3500 }, { 2497, 3164 } };
        int[][] gbat = { { 2585, 3479 }, { 2589, 3480 }, { 2582, 3476 }, { 2588, 3485 }, { 2580, 3490 }, { 2577, 3482 }, { 2583, 3484 }, { 2759, 3402 }, { 2756, 3400 } };
        core.game.world.map.Location oldBlack = core.game.world.map.Location.create(2968, 3480, 0);
        int atOld = near(black, oldBlack, 15), at8 = near(black, Slayer.BEAR8, 15);
        check("s8 spot8: the densest Black bear spawn tile has " + atOld + " bears in 15 tiles, BEAR8 " + at8 + ", all inside the runner's 18", atOld == 2 && at8 == 3 && near(black, Slayer.BEAR8, 18) == 3);
        check("s8 spot8: off = the old spot; on = BEAR8; another monster, or a spawn file without the third bear, keeps the old spot", Slayer.spot8(OFF, "Black bear", oldBlack, atOld, at8) == oldBlack && Slayer.spot8(ON, "Black bear", oldBlack, atOld, at8) == Slayer.BEAR8
                && Slayer.spot8(ON, "Grizzly bear", oldBlack, atOld, at8) == oldBlack && Slayer.spot8(ON, "Black bear", oldBlack, 2, 2) == oldBlack && Slayer.spot8(ON, "Black bear", oldBlack, 3, 3) == oldBlack);
        core.game.world.map.Location mainGrizzly = core.game.world.map.Location.create(2387, 3376, 0), mainGbat = core.game.world.map.Location.create(2585, 3479, 0);
        boolean alts = Slayer.ALT8.length == 3, zoo = false;
        for (Slayer.Alt8 a : Slayer.ALT8) {
            int[][] rows = a.name.equals("Grizzly bear") ? grizzly : a.name.equals("Giant bat") ? gbat : new int[0][];
            core.game.world.map.Location main = a.name.equals("Grizzly bear") ? mainGrizzly : mainGbat;
            int n = near(rows, a.at, 15);
            boolean ok = Slayer.altOk8(ON, n, true, a.at.getDistance(main)) && !Slayer.altOk8(OFF, n, true, a.at.getDistance(main));
            log("s8 extra spot " + a.name + " (" + a.tag + ") at " + a.at + ": " + n + " spawns in the file, " + (int) a.at.getDistance(main) + " tiles from the main spot -> " + (ok ? "built" : "NOT built"));
            alts &= ok && n == 2 && near(rows, a.at, 0) == 1; // the spot is a spawn tile of the file
            if (a.at.getX() >= 2590 && a.at.getX() <= 2642 && a.at.getY() >= 3262 && a.at.getY() <= 3289) zoo = true; // Watchdog.ENCLOSURES: the Ardougne zoo bears are no hunt
        }
        check("s8 altOk8: the 3 extra spots are spawn tiles with 2 spawns each, clear of the main spot's range, none in the Ardougne zoo; flag off builds none", alts && !zoo);
        check("s8 altOk8: 1 spawn, other monsters at the spot, or inside the main spot's 18 tiles = not built", !Slayer.altOk8(ON, 1, true, 300) && !Slayer.altOk8(ON, 2, false, 300) && !Slayer.altOk8(ON, 2, true, 18));
        HuntMap.Hunt h = new HuntMap.Hunt(); h.name = "Grizzly bear";
        String l0 = HuntMap.label(h); h.tag = Slayer.ALT8[0].tag; String l1 = HuntMap.label(h);
        check("s8 label: a hunt without a tag keeps its old label (" + l0 + "), an extra spot has its own (" + l1 + "): its own crowd count, blacklist and stall mark", "Grizzly bears".equals(l0) && "Grizzly bears (Ardougne east)".equals(l1));
        // c: the trip first
        check("s8 brokeStop8: off = go; on = no errand under 400 gp (the line tripDue's broke bot is under), 400 = go", !Slayer.brokeStop8(OFF, 0) && Slayer.brokeStop8(ON, 0) && Slayer.brokeStop8(ON, 399) && !Slayer.brokeStop8(ON, 400) && Slayer.BROKE8 == 400);
        check("s8 the mismatch it closes: tripDue never sends a broke holder, whatever the streak", !Slayer.tripDue(true, true, true, 0, true) && Slayer.tripDue(true, true, false, 0, true));
        boolean tf = true;
        for (int m = 0; m < 16; m++) { boolean has = (m & 1) != 0, streak = (m & 2) != 0, parked = (m & 4) != 0, broke = (m & 8) != 0; tf &= !Slayer.tripFirst8(OFF, has, streak, parked, broke) && Slayer.tripFirst8(ON, has, streak, parked, broke) == (has && streak && !parked && !broke); }
        check("s8 tripFirst8: off = never; on = only a held task with its spot seen open, not parked, not broke (16 cases)", tf);
        check("s8 tripFirst(null) = false (the hook never blocks without a bot)", !Slayer.tripFirst(null));
        Set<String> gob = new java.util.HashSet<>(java.util.Arrays.asList("goblin fish", "goblin cook", "goblin scribe", "goblin guard", "goblin", "goblin champion")); // the names line of a GOBLINS task (s55 log)
        String[] bones = { "Goblin", "Man", "Woman", "Giant rat" }, goblins = { "Goblin" }; // AfkTasks.Task.BONES_ANY and the Goblin hunt
        try { bones = AfkTasks.Task.BONES_ANY.targets; } catch (Throwable t) { log("s8 note: AfkTasks.Task not loaded offline, using the BONES_ANY targets as written"); }
        check("s8 pure8: the Goblins job is pure for a GOBLINS task, Bones (goblins, men, rats) is mixed but still matches; no names = not pure", Slayer.pure8(gob, goblins) && !Slayer.pure8(gob, bones) && Slayer.matches(gob, bones) && !Slayer.pure8(gob, new String[0]) && !Slayer.pure8(java.util.Collections.emptySet(), goblins));
        boolean b8 = true; double[] ds = { 0, 40, 156, TELE, TELE + 1, 700 };
        for (double d1 : ds) for (double d2 : ds) for (int p = 0; p < 4; p++) b8 &= Slayer.better8(OFF, (p & 1) != 0, d1, (p & 2) != 0, d2) == (d1 < d2);
        check("s8 better8 off = the nearer spot, as chooseJob's old line", b8 && Slayer.better8(OFF, false, 40, false, 1e9));
        check("s8 better8 on, s55 Quit Cry: Goblins (pure, 156 tiles) beats Bones (mixed, 40 tiles) in either order of the JOBS list", Slayer.better8(ON, true, 156, false, 40) && !Slayer.better8(ON, false, 40, true, 156));
        check("s8 better8 on: the first open spot is taken (best none: 1e9); two pure or two mixed = the nearer; a pure spot a teleport away does not beat a mixed one in walking range",
                Slayer.better8(ON, false, 40, false, 1e9) && Slayer.better8(ON, true, 700, false, 1e9) && Slayer.better8(ON, true, 40, true, 156) && !Slayer.better8(ON, true, 156, true, 40) && Slayer.better8(ON, false, 40, false, 156) && !Slayer.better8(ON, true, 700, false, 40) && Slayer.better8(ON, false, 40, true, 700) && Slayer.better8(ON, true, 500, false, 700));
        // d: the dropped task
        final Tasks W = Tasks.valueOf("WOLVES"), B = Tasks.valueOf("BEARS");
        Set<Tasks> open = java.util.EnumSet.of(W, B);
        check("s8 minus8: off, no dropped task, window over, or a task not in the set = the same set object", Slayer.minus8(OFF, open, B, 2_000, 1_000) == open && Slayer.minus8(ON, open, null, 2_000, 1_000) == open && Slayer.minus8(ON, open, B, 2_000, 2_000) == open && Slayer.minus8(ON, java.util.EnumSet.of(W), B, 2_000, 1_000).equals(java.util.EnumSet.of(W)));
        Set<Tasks> m8 = Slayer.minus8(ON, open, B, 2_000, 1_000);
        check("s8 minus8 on: BEARS dropped after parks is out of the open set inside the window, the caller's set is untouched; only task = none open = errand skipped", m8.equals(java.util.EnumSet.of(W)) && open.size() == 2 && Slayer.minus8(ON, java.util.EnumSet.of(B), B, 2_000, 1_000).isEmpty() && Slayer.preTravel(ON, true, false, Slayer.minus8(ON, java.util.EnumSet.of(B), B, 2_000, 1_000).size(), "") > 0);
        Slayer.St st = new Slayer.St(); Slayer.shut8(st, B, 1_000);
        check("s8 shut8 (park's expire, cancel, swap): the window is 60 min when the flag is on, nothing is written when off", Slayer.FIX8 ? st.shut == B && st.shutUntil == 1_000 + Slayer.SHUT8_MS && Slayer.SHUT8_MS == 60 * 60_000L : st.shut == null);
    }
    static int near(int[][] rows, core.game.world.map.Location at, double r) { int n = 0; for (int[] x : rows) if (core.game.world.map.Location.create(x[0], x[1], 0).getDistance(at) <= r) n++; return n; }

    /** slayer8 in a world: the real hunts HuntMap built, their seats, gates and labels, the real GOBLINS jobs, and the waiting count. */
    static void world8() {
        SmartAdventurers.Job black = null; int tagged = 0; java.util.Set<String> labels = new java.util.HashSet<>(); boolean uniq = true, altsOk = true;
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) {
            if (j.hunt == null) continue;
            if (Slayer.BEAR8_NAME.equals(j.hunt.name) && j.hunt.tag == null) black = j;
            if (j.hunt.tag != null) {
                tagged++; uniq &= labels.add(j.label());
                boolean ok = Gates.missing(null, j.hunt.spot) == null && Surveyors.Surveyor.walkable(j.hunt.spot) != null && !Resources.skipHunt(null, j.hunt.spot) && !Watchdog.trap(j.hunt.spot) && j.hunt.spawnsAtSpot >= 2;
                log("s8 world: extra spot " + j.label() + " at " + j.hunt.spot + " spawns " + j.hunt.spawnsAtSpot + " level " + j.level + " seats holder " + Slayer.seats(j, true) + " other " + Slayer.seats(j, false) + " -> " + (ok ? "ok" : "BAD"));
                altsOk &= ok;
            }
        }
        check("s8 world: the 3 extra spots were built from the spawn file, each with its own label, no gate, a walkable tile, no trap, not a skipped hunt", tagged == Slayer.ALT8.length && uniq && altsOk);
        check("s8 world: a Black bear hunt exists", black != null);
        if (black != null) {
            log("s8 world: Black bear hunt at " + black.hunt.spot + " spawns " + black.hunt.spawnsAtSpot + " capacity " + SmartAdventurers.Brain.capacity(black) + " seats holder " + Slayer.seats(black, true));
            check("s8 world: the Black bear hunt is BEAR8 with 3 spawns: 1 seat for others, 2 for a BEARS holder; walkable, no gate", black.hunt.spot.equals(Slayer.BEAR8) && black.hunt.spawnsAtSpot == 3 && Slayer.seats(black, false) == 1 && Slayer.seats(black, true) == 2 && Surveyors.Surveyor.walkable(black.hunt.spot) != null && Gates.missing(null, black.hunt.spot) == null);
        }
        Set<String> gob = Slayer.npcNames(Tasks.valueOf("GOBLINS")); int pure = 0, mixed = 0;
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (Slayer.matches(gob, Slayer.jobNames(j))) { boolean p = Slayer.pure8(gob, Slayer.jobNames(j)); if (p) pure++; else mixed++; log("s8 world: GOBLINS job " + j.label() + " " + (p ? "pure" : "mixed")); }
        check("s8 world: GOBLINS has a pure job and the mixed Bones job", pure >= 1 && mixed >= 1);
        java.util.Map<SmartAdventurers.Job, Integer> wait = Slayer.waiting8(null); StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<SmartAdventurers.Job, Integer> e : wait.entrySet()) sb.append(e.getKey().label()).append('=').append(e.getValue()).append(' ');
        log("s8 world: holders waiting per spot: " + (sb.length() == 0 ? "none" : sb.toString().trim()));
        int seen = 0;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
            if (b.bot == null || seen >= 8) continue;
            seen++;
            StringBuilder w = new StringBuilder();
            Set<Tasks> o = Slayer.openSet(b, Slayer.served(Master.TURAEL), w, Slayer.stand(Master.TURAEL), Ledger.Wallet.coins(b.bot));
            log("s8 world: " + b.bot.getUsername() + " combat " + b.effectiveCombat() + " coins " + Ledger.Wallet.coins(b.bot) + " broke stop " + Slayer.brokeStop8(true, Ledger.Wallet.coins(b.bot)) + " trip first " + Slayer.tripFirst(b) + " open at Turael " + o + " closed: " + Slayer.cut(w.toString().trim(), 260));
        }
    }

    /** The "edgeville-hill-giants" brass key gate: Gates' own table when it loads, and the box from Gates.java line "edgeville-hill-giants" as the offline stand-in. */
    static boolean keyGate(core.game.world.map.Location l) {
        boolean box = l.getX() >= 3096 && l.getX() <= 3125 && l.getY() >= 9824 && l.getY() <= 9852;
        try { boolean g = Gates.missing(null, l) != null; if (g != box) log("keyGate note: Gates says " + g + ", the box says " + box + " at " + l); return Gates.ON ? g : box; } catch (Throwable t) { return box; }
    }

    /** slayer7 in a world: the real hunt job, the real gates, the real served list, and openSet for the 40-58 bots that are in. */
    static void world7() {
        SmartAdventurers.Job hill = null;
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && "Hill Giant".equals(j.hunt.name)) hill = j;
        check("s7 world: a Hill Giant hunt job exists", hill != null);
        if (hill == null) return;
        log("s7 world: Hill Giant hunt at " + hill.hunt.spot + " level " + hill.level + " spawns " + hill.hunt.spawnsAtSpot + " capacity " + SmartAdventurers.Brain.capacity(hill) + " fix7 " + Slayer.FIX7);
        if (!Slayer.FIX7) { check("s7 world off: the hunt is the curated dungeon spot, level 41", hill.hunt.spot.getY() > 6400 && hill.level == 41); return; }
        check("s7 world on: the hunt is the surface spot, level 40, 6 spawns, room for 2", hill.hunt.spot.equals(Slayer.HILL7) && hill.level == 40 && hill.hunt.spawnsAtSpot == 6 && SmartAdventurers.Brain.capacity(hill) == 2);
        check("s7 world on: no gate at the spot, a walkable tile there, not a skipped hunt", Gates.missing(null, hill.hunt.spot) == null && Surveyors.Surveyor.walkable(hill.hunt.spot) != null && !Resources.skipHunt(null, hill.hunt.spot));
        check("s7 world on: served(VANNAKA) has HILL_GIANTS", Slayer.served(Master.VANNAKA).contains(Tasks.valueOf("HILL_GIANTS")));
        int seen = 0, open = 0;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
            if (b.bot == null || seen >= 12) continue;
            int c = b.bot.getProperties().getCurrentCombatLevel();
            if (c < 40 || c > 58) continue;
            seen++;
            StringBuilder w = new StringBuilder();
            Set<Tasks> o = Slayer.openSet(b, Slayer.served(Master.VANNAKA), w, Slayer.stand(Master.VANNAKA), Ledger.Wallet.coins(b.bot));
            if (!o.isEmpty()) open++;
            log("s7 world: " + b.bot.getUsername() + " combat " + c + "/" + b.effectiveCombat() + " open at Vannaka " + o + (o.isEmpty() ? " closed: " + Slayer.cut(w.toString().trim(), 300) : "") + " -> " + Slayer.chooseMaster(b));
        }
        log("s7 world: combat 40-58 bots seen " + seen + ", with an open Vannaka task " + open);
    }

    /** In-world only: JOBS exist, so served(VANNAKA) shows what a bot could really be given. */
    static void world() {
        Set<Tasks> sv = Slayer.served(Master.VANNAKA);
        log("served Vannaka " + sv.size() + ": " + sv);
        check("served(VANNAKA) subset of vannakaAllow", Slayer.vannakaAllow().containsAll(sv));
        check("served(VANNAKA) not empty (JOBS hunts match)", !sv.isEmpty());
        check("served() Turael unchanged = served(TURAEL)", Slayer.served().equals(Slayer.served(Master.TURAEL)));
        world7();
        if (Slayer.FIX8) world8();
        if (Slayer.DOOR) { // slayer4: the door resolves to a walkable surface tile near the hint
            core.game.world.map.Location d = Slayer.door();
            log("Vannaka door " + d);
            check("door() walkable on the surface near 3094,3470", d != null && d.getZ() == 0 && d.getY() < 6400 && d.getDistance(Slayer.VANNAKA_DOOR_HINT) <= 16);
        }
    }
}
