package content.custom;

import content.global.skill.farming.FarmingPatch;
import content.global.skill.farming.Patch;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.List;

/**
 * Trial only, never shipped (C8 farming, the design notes section 6). Runs with the harness:
 *   ON : run-test.sh TestFarming -Dtest.farming=true -Dbots.farming=true -Dsmartbots.testFarming=true -Dsmartbots.count=4 -Dquests.off=true
 *   OFF: run-test.sh TestFarming -Dtest.farming=true -Dsmartbots.count=4 -Dquests.off=true
 *   V2 : the ON line plus -Dbots.farming.v2=true (flower patch first: U2 finds 4 per site; U5 passes on a cure or a clear).
 * Do NOT add -Dtraders.fast (the farm run's give-up drops to 3 min and growth is real-clock anyway; U4/U5 force the stages).
 * U1 pure (seed pick, time rule, budget). U2 Farming.find: 3 patches per site, enums match (the in-world check of the anchors).
 * U3 one bot at Catherby bank: planFarmRun true, PLANTED >= 2, farmDue != 0, wallet drop == SEED_SPEND, no mint.
 * U7 the engine's "farming:crops" timer is on the bot. U4 forced growth -> CHECK harvests, crops reach Exchange stock.
 * U5 forced disease -> CHECK clears it (DIED +1). U6 logOut with crops due: ABANDONED +1, farmDue 0, seat freed.
 * (The restored bot's weedy patch is not checked here: restore builds a new AIPlayer after a break; varbits start at 0.)
 * OFF: planFarmRun false 200 times, farm 0, PLANTED 0, no tally (no [Farm] line possible).
 * -Dtest.farming.coins=<gp> (default 2000; the design's 500 gives a 25 gp budget, which may not cover the tools at guide price).
 */
public final class TestFarming implements StartupListener {
    static final String T = "[TestFarming] ";
    static int pass, fail;
    /** farm fix1: -Dtest.farming.pack=N fills the pack to N free slots once the U4 CHECK is at the patches (the live full-pack harvest). */
    static final int PACK = Integer.getInteger("test.farming.pack", -1), FILL = 1511;
    static long stalls0; static boolean packed;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println(T + (ok ? "PASS " : "FAIL ") + what); }
    static void log(String s) { System.out.println(T + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.farming")) return;
        log("armed farming=" + Farming.ON + " testFarming=" + Farming.TEST);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 150 || t < 900 && SmartAdventurers.BRAINS.size() < 2) return false; // bots log in spread out
                try { go(); } catch (Throwable x) { fail++; log("RESULT FAIL setup " + x); x.printStackTrace(System.out); log("DONE"); }
                return true;
            }
        });
    }

    public static void main(String[] a) { pure(); System.out.println(T + "RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail); System.exit(fail == 0 ? 0 : 1); }

    /** farm fix1: one grown allotment as the live logs show it: the walk, the harvest batches of a full pack, v2's compost, the seed. True = "too many steps". */
    static boolean replay(boolean fix1, boolean v2, int batches, int got) {
        int c = 0;
        if (++c > Farming.STEP_CAP) return true; // the walk to the patch
        for (int i = 0; i < batches; i++) { if (++c > Farming.STEP_CAP) return true; c = Farming.stepsAfterOk(fix1, c, true, got); }
        if (v2) { if (++c > Farming.STEP_CAP) return true; c = Farming.stepsAfterOk(fix1, c, false, 0); }
        if (++c > Farming.STEP_CAP) return true; // the seed
        return false;
    }
    static void pure() {
        check("fix1 replay OFF v2, 8 batches (model; the live stalls had 6 to 9): stalls before the seed", replay(false, true, 8, 5));
        check("fix1 replay OFF v1, 8 batches: fits (10 steps)", !replay(false, false, 8, 5));
        check("fix1 replay OFF v1, 9 batches: stalls (the 1 OFF stall)", replay(false, false, 9, 5));
        check("fix1 replay ON v2, 8 and 30 batches: no stall", !replay(true, true, 8, 5) && !replay(true, true, 30, 5));
        check("fix1 replay ON, harvests that bring nothing still hit the cap", replay(true, true, 30, 0));
        check("fix1 stepsAfterOk: off keeps, on gives back, never below 0", Farming.stepsAfterOk(false, 4, false, 0) == 4 && Farming.stepsAfterOk(true, 4, false, 0) == 3 && Farming.stepsAfterOk(true, 0, false, 0) == 0 && Farming.stepsAfterOk(true, 4, true, 0) == 4);
        java.util.function.IntPredicate all = id -> true, none = id -> false;
        check("U1 level 1 allotment -> potato 5318", Farming.pickSeed(1, false, all) == 5318);
        check("U1 level 6 allotment -> onion 5319", Farming.pickSeed(6, false, all) == 5319);
        check("U1 level 40 allotment -> tomato 5322", Farming.pickSeed(40, false, all) == 5322);
        check("U1 level 40 allotment, no tomato source -> cabbage 5324", Farming.pickSeed(40, false, id -> id != 5322) == 5324);
        check("U1 level 8 herb -> none", Farming.pickSeed(8, true, all) == -1);
        check("U1 level 9 herb -> guam 5291", Farming.pickSeed(9, true, all) == 5291);
        check("U1 level 32 herb -> ranarr 5295", Farming.pickSeed(32, true, all) == 5295);
        check("U1 level 99 herb, nothing sourced -> none", Farming.pickSeed(99, true, none) == -1);
        check("U1 herb/tomato seeds are not shop goods", !Farming.shopStocked(5291) && !Farming.shopStocked(5322) && Farming.shopStocked(5318) && Farming.shopStocked(Farming.RAKE));
        long now = 1_000_000_000L, M = 60_000L;
        check("U1 fit: 3 h left -> herb + allotments", Farming.fit(now, now + 180 * M) == 2);
        check("U1 fit: 100 min left -> allotments only", Farming.fit(now, now + 100 * M) == 1);
        check("U1 fit: 115 min left -> herb + allotments (85+30 < 115.x)", Farming.fit(now, now + 116 * M) == 2);
        check("U1 fit: 75 min left -> nothing", Farming.fit(now, now + 75 * M) == 0);
        check("U1 budget 500 gp -> floor 150", Farming.budget(500) == 150); check("U1 budget 100 gp -> all 100", Farming.budget(100) == 100); check("U1 budget 10k -> 500", Farming.budget(10_000) == 500);
        check("U1 budget 100k -> capped 600", Farming.budget(100_000) == 600);
        check("U1 budget 0 -> 0", Farming.budget(0) == 0);
        final String V1FMT = "\\[Farm\\] planted=\\d+ harvested=\\d+ died=\\d+ seedSpend=\\d+ runs=\\d+ abandoned=\\d+ stalls=\\d+ pulls=\\d+ overdue=\\d+";
        check("U1 totals format", Farming.totals(false).matches(V1FMT) && (Farming.V2 || Farming.totals().matches(V1FMT)));
        pureV2(V1FMT);
    }

    /** farm2 (-Dbots.farming.v2): the new picks are pure, so they are checked with the flag on or off. */
    static void pureV2(String v1fmt) {
        java.util.function.IntPredicate all = id -> true;
        check("V2 totals: v1 part first, new fields at the end only", Farming.totals(true).matches(v1fmt + " composted=\\d+ cured=\\d+ flowers=\\d+ flowerDied=\\d+ extraSpend=\\d+"));
        check("V2 flower: potato/onion/tomato -> marigold, cabbage -> rosemary, herb -> none", Farming.flowerFor(5318) == 5096 && Farming.flowerFor(5319) == 5096 && Farming.flowerFor(5322) == 5096 && Farming.flowerFor(5324) == 5097 && Farming.flowerFor(5291) == -1);
        check("V2 flower levels: marigold 2, rosemary 11", Farming.flowerLevel(5096) == 2 && Farming.flowerLevel(5097) == 11 && Farming.flowerLevel(-1) > 99);
        check("V2 pick level 1 (no flower yet) -> potato", Farming.pickAllotV2(1, -1, all) == 5318);
        check("V2 pick level 5 -> onion", Farming.pickAllotV2(5, -1, all) == 5319);
        check("V2 pick level 8 -> onion under marigold, not cabbage", Farming.pickAllotV2(8, -1, all) == 5319 && Farming.pickSeed(8, false, all) == 5324);
        check("V2 pick level 11 -> cabbage (rosemary plantable)", Farming.pickAllotV2(11, -1, all) == 5324);
        check("V2 pick level 40 -> tomato; no tomato source -> cabbage", Farming.pickAllotV2(40, -1, all) == 5322 && Farming.pickAllotV2(40, -1, id -> id != 5322) == 5324);
        check("V2 pick level 40, marigold in the ground, no tomato source -> onion", Farming.pickAllotV2(40, 5096, id -> id != 5322) == 5319);
        check("V2 pick level 11, rosemary in the ground -> cabbage", Farming.pickAllotV2(11, 5097, all) == 5324);
        check("V2 pick level 6, rosemary in the ground -> v1 pick onion", Farming.pickAllotV2(6, 5097, all) == 5319);
        check("V2 pick nothing sourced -> none", Farming.pickAllotV2(40, -1, id -> false) == -1);
        check("V2 compost need: 2 allotments + herb = 3; guarded allotments = 1; allotments only = 2; nothing = 0", Farming.compostNeed(2, false, 1) == 3 && Farming.compostNeed(2, true, 1) == 1 && Farming.compostNeed(2, false, 0) == 2 && Farming.compostNeed(0, true, 0) == 0);
        check("V2 compost buy: short 3 at 20 gp with 100 left -> 3", Farming.compostToBuy(3, 20, 100) == 3);
        check("V2 compost buy: short 3 at 20 gp with 45 left -> 2", Farming.compostToBuy(3, 20, 45) == 2);
        check("V2 compost buy: nothing left / not short / too dear -> 0", Farming.compostToBuy(3, 20, 0) == 0 && Farming.compostToBuy(0, 20, 100) == 0 && Farming.compostToBuy(3, Farming.COMPOST_MAX + 1, 600) == 0);
        long b = Farming.budget(500), seeds = 47;
        check("V2 budget with compost: 500 gp bot, 47 gp of seed, 3 buckets at 20 gp stays inside the 150 gp floor", seeds + 20L * Farming.compostToBuy(3, 20, b - seeds) <= b && Farming.compostToBuy(3, 20, b - seeds) == 3);
        check("V2 budget with compost: a 100 gp bot never spends past its 100", seeds + 20L * Farming.compostToBuy(3, 20, Farming.budget(100) - seeds) <= 100 && Farming.compostToBuy(3, 20, Farming.budget(100) - seeds) == 2);
        check("farm3 cure: never bought (none owned -> not carried); an owned one is carried once", !Farming.takeCure(0, 0, 20) && Farming.takeCure(0, 1, 20) && !Farming.takeCure(1, 1, 20) && !Farming.takeCure(0, 1, 3));
        // farm3: the flower path with the values planFarmRun passes: lvl = static Farming level, aSeed = pickAllotV2(lvl, farmFlowerIn, sourced), check = a CHECK run.
        int f52 = Farming.pickAllotV2(1, -1, all);
        check("farm3 f52 case: level 1 PLANT run, potato picked, no flower seed (marigold needs 2)", f52 == 5318 && Farming.flowerSeedToBuy(1, f52, -1, false) == -1);
        check("farm3 level 1 CHECK run (harvest ahead): marigold seed carried", Farming.flowerSeedToBuy(1, f52, -1, true) == 5096);
        check("farm3 level 1 at the flower patch: come back after the harvest; still 1 then -> skip; 2 then -> plant", Farming.flowerStep(1, 5096, true, true, false) == Farming.FL_LATER && Farming.flowerStep(1, 5096, true, true, true) == Farming.FL_SKIP && Farming.flowerStep(2, 5096, true, true, true) == Farming.FL_PLANT);
        check("farm3 level 2 PLANT run: potato + marigold bought and planted on the first pass", Farming.flowerSeedToBuy(2, Farming.pickAllotV2(2, -1, all), -1, false) == 5096 && Farming.flowerStep(2, 5096, true, true, false) == Farming.FL_PLANT);
        check("farm3 level 8: onion + marigold; level 11: cabbage + rosemary; level 9 with cabbage forced: no rosemary even on a CHECK", Farming.flowerSeedToBuy(8, Farming.pickAllotV2(8, -1, all), -1, false) == 5096 && Farming.flowerSeedToBuy(11, Farming.pickAllotV2(11, -1, all), -1, false) == 5097 && Farming.flowerSeedToBuy(9, 5324, -1, true) == -1);
        check("farm3 flower already in the ground / herb-only run: no seed", Farming.flowerSeedToBuy(40, 5319, 5096, true) == -1 && Farming.flowerSeedToBuy(40, -1, -1, true) == -1);
        check("farm3 no walk to the flower patch: no seed held, patch taken, or no seed picked -> skip", Farming.flowerStep(40, 5096, false, true, false) == Farming.FL_SKIP && Farming.flowerStep(40, 5096, true, false, false) == Farming.FL_SKIP && Farming.flowerStep(40, -1, false, true, false) == Farming.FL_SKIP);
        check("V2 shop goods follow the flag (compost, cure, flower seeds): " + Farming.V2, Farming.shopStocked(Farming.COMPOST) == Farming.V2 && Farming.shopStocked(Farming.PLANT_CURE) == Farming.V2 && Farming.shopStocked(5096) == Farming.V2 && Farming.shopStocked(5097) == Farming.V2);
        check("V2 sites: " + (Farming.V2 ? "flower patch first, 4 patches" : "3 patches, as v1"), Farming.SITES[0].patches.length == (Farming.V2 ? 4 : 3) && (Farming.SITES[1].patches[0] == FarmingPatch.CATHERBY_FLOWER_C) == Farming.V2);
    }

    static final Location CATH_BANK = Location.create(2809, 3440, 0);
    static final int[] TOOLS = {Farming.RAKE, Farming.DIBBER, Farming.SPADE, Farming.CAN_EMPTY, 5333, 5334, 5335, 5336, 5337, 5338, 5339, 5340};

    static void idle(SmartAdventurers.Brain b, long far) {
        Player p = b.bot;
        b.nextThink = far;
        AfkTasks.stop(p, null); b.runner = null; b.shopAt = null; b.pendingJob = null; b.idleUntil = 0; b.crew = null;
        try { p.getPulseManager().clear(); p.getWalkingQueue().reset(); } catch (Throwable ignored) { }
        p.getProperties().setTeleportLocation(CATH_BANK);
    }

    static long waiting(Player p, int id) {
        long n = Ledger.waiting(p.getUsername(), id);
        try { core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id); if (d != null && d.getNoteId() > 0 && d.getNoteId() != id) n += Ledger.waiting(p.getUsername(), d.getNoteId()); } catch (Throwable ignored) { }
        return n;
    }

    static int total(Player p, int id) { return p.getBank().getAmount(id) + p.getInventory().getAmount(id); }

    static void go() {
        pure();
        SmartAdventurers.Brain pick = null;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) if (b.bot != null && b.bot.isActive() && b.companion == null && !QuestRunner.active(b.bot) && b.bot instanceof AIPlayer) { pick = b; break; }
        if (pick == null) { check("a free bot", false); finish(); return; }
        final SmartAdventurers.Brain b = pick; final Player p = b.bot;
        final long far = System.currentTimeMillis() + 6 * 3_600_000L;
        idle(b, far);
        b.shiftEnd = far;
        log("bot = " + p.getUsername() + " farming " + p.getSkills().getStaticLevel(Skills.FARMING));
        if (!Farming.ON) { off(b); return; }
        // U2: the sites
        for (int i = 0; i < Farming.SITES.length; i++) {
            List<Scenery> got = Farming.find(i);
            StringBuilder sb = new StringBuilder();
            boolean match = true;
            for (Scenery s : got) {
                FarmingPatch fp = FarmingPatch.forObject(s);
                boolean in = false; for (FarmingPatch f : Farming.SITES[i].patches) in |= f == fp;
                match &= in;
                sb.append(' ').append(fp).append('@').append(s.getLocation().getX()).append(',').append(s.getLocation().getY()).append(" id ").append(s.getId()).append(" size ").append(s.getSizeX()).append('x').append(s.getSizeY());
            }
            check("U2 " + Farming.SITES[i].key + " found " + got.size() + "/" + Farming.SITES[i].patches.length + ", enums match:" + sb, got.size() == Farming.SITES[i].patches.length && match);
            Location a = Farming.SITES[i].anchor;
            check("U2 " + Farming.SITES[i].key + " bank " + Farming.SITES[i].bank + " is an AfkTasks bank", java.util.Arrays.asList(AfkTasks.BANKS).contains(Farming.SITES[i].bank));
            log("U2 " + Farming.SITES[i].key + " anchor " + a + " to bank " + (int) a.getDistance(Farming.SITES[i].bank) + " tiles");
        }
        // U3 setup: Farming 1, no tools, coins as given
        p.getSkills().setStaticLevel(Skills.FARMING, 1); p.getSkills().setLevel(Skills.FARMING, 1);
        for (int id : TOOLS) { p.getBank().remove(new Item(id, p.getBank().getAmount(id))); p.getInventory().remove(new Item(id, p.getInventory().getAmount(id))); }
        for (int id : Farming.allSeeds()) { p.getBank().remove(new Item(id, p.getBank().getAmount(id))); p.getInventory().remove(new Item(id, p.getInventory().getAmount(id))); }
        final long COINS = Long.getLong("test.farming.coins", 2000L);
        long have = Ledger.Wallet.coins(p);
        if (have < COINS) p.getBank().add(new Item(995, (int) (COINS - have)));
        else if (have > COINS) { long cut = have - COINS; int fromBank = (int) Math.min(cut, p.getBank().getAmount(995)); p.getBank().remove(new Item(995, fromBank)); if (cut > fromBank) p.getInventory().remove(new Item(995, (int) (cut - fromBank))); }
        log("prices rake " + MarketBrain.value(Farming.RAKE) + " dibber " + MarketBrain.value(Farming.DIBBER) + " spade " + MarketBrain.value(Farming.SPADE) + " can " + MarketBrain.value(Farming.CAN_EMPTY) + " potato seed " + MarketBrain.value(5318) + " coins " + Ledger.Wallet.coins(p) + " budget " + Farming.budget(Ledger.Wallet.coins(p)));
        b.farmDue = 0; b.farmNext = 0; b.farmTested = false;
        check("U3 armed: -Dsmartbots.testFarming=true (else planFarmRun rolls the 1-in-2 plant dice)", Farming.TEST);
        GameWorld.getPulser().submit(new Pulse(1) {
            int phase = 0, ticks; long coins0, spend0, stock0, bank0, died0, harv0, aband0; boolean started;
            @Override public boolean pulse() {
                ticks++;
                long now = System.currentTimeMillis();
                try {
                    switch (phase) {
                        case 0: { // U3 plan
                            if (ticks < 5) return false;
                            coins0 = Ledger.Wallet.coins(p); spend0 = Farming.SEED_SPEND;
                            boolean r = b.planFarmRun(now);
                            check("U3 planFarmRun true (tally " + Farming.tallyLine() + ")", r);
                            if (!r) return finish();
                            phase = 1; ticks = 0; return false;
                        }
                        case 1: { // U3 run
                            if (ticks % 50 == 0) log("U3 t" + ticks + " farm=" + b.farm + " idx=" + b.farmIdx + " act=" + b.farmAct + " at " + p.getLocation() + " planted=" + Farming.PLANTED);
                            if (b.farm != 0 && ticks < 700) return false;
                            idle(b, far);
                            long coins1 = Ledger.Wallet.coins(p), spent = Farming.SEED_SPEND - spend0;
                            check("U3 run ended in " + ticks + " ticks (<= 400 wanted)", b.farm == 0 && ticks <= 400);
                            check("U3 PLANTED >= 2 (" + Farming.PLANTED + ", stalls " + Farming.STALLS + ")", Farming.PLANTED >= 2);
                            check("U3 farmDue != 0 (" + (b.farmDue == 0 ? 0 : (b.farmDue - now) / 60_000) + " min)", b.farmDue != 0);
                            check("U3 wallet drop " + (coins0 - coins1) + " == seedSpend " + spent + ", no mint", coins0 - coins1 == spent && coins1 <= coins0);
                            check("U7 timer farming:crops present", p.timers.getTimer("farming:crops") != null);
                            // U4: force growth on every planted patch
                            int forced = 0;
                            for (Scenery s : Farming.find(b.farmSite)) {
                                Patch pt = FarmingPatch.forObject(s).getPatchFor(p, true);
                                if (pt.getPlantable() == null) continue;
                                for (int k = 0; k < 8 && !pt.isGrown(); k++) { pt.setWatered(true); pt.setNextGrowth(0); pt.update(); }
                                if (pt.isGrown()) forced++;
                            }
                            log("U4 forced " + forced + " patches grown");
                            stalls0 = Farming.STALLS; packed = false; harv0 = Farming.HARVESTED; stock0 = waiting(p, 1942); bank0 = p.getBank().getAmount(1942) + p.getInventory().getAmount(1942);
                            b.farmDue = now;
                            phase = 2; ticks = 0; return false;
                        }
                        case 2: { // U4 CHECK
                            if (ticks < 5) return false;
                            boolean r = b.planFarmRun(now);
                            check("U4 CHECK planFarmRun true (tally " + Farming.tallyLine() + ")", r);
                            if (!r) return finish();
                            phase = 3; ticks = 0; return false;
                        }
                        case 3: {
                            if (PACK >= 0 && !packed && b.farm == SmartAdventurers.Brain.FARM_WORK) { packed = true; int n = 0; while (p.getInventory().freeSlots() > PACK && p.getInventory().add(new core.game.node.item.Item(FILL))) n++; log("fix1 pack filled with " + n + " logs, " + p.getInventory().freeSlots() + " free"); }
                            if (b.farm != 0 && ticks < (PACK >= 0 ? 2400 : 900)) return false;
                            if (PACK >= 0) {
                                int f = p.getInventory().getAmount(FILL); if (f > 0) p.getInventory().remove(new core.game.node.item.Item(FILL, f));
                                log("fix1 U4 full pack (" + PACK + " free): stalls +" + (Farming.STALLS - stalls0) + " harvested " + (Farming.HARVESTED - harv0) + " in " + ticks + " ticks, fix1=" + Farming.FIX1 + " v2=" + Farming.V2);
                                if (Farming.FIX1) check("fix1 U4 full pack: no stall (" + (Farming.STALLS - stalls0) + "), run ended", packed && Farming.STALLS == stalls0 && b.farm == 0);
                                else log("fix1 OFF: " + (Farming.STALLS > stalls0 ? "REPRODUCED" : "not reproduced"));
                            }
                            idle(b, far);
                            check("U4 HARVESTED > 0 (" + (Farming.HARVESTED - harv0) + ")", Farming.HARVESTED > harv0);
                            long got = Farming.HARVESTED - harv0, w1 = waiting(p, 1942), own1 = p.getBank().getAmount(1942) + p.getInventory().getAmount(1942);
                            // MarketBrain.stockOf is the fleet snapshot (refreshed on its own clock, other bots buy from it): the bot's own lots in the Ledger are the check
                            check("U4 this bot's potato lots in Exchange stock " + stock0 + " -> " + w1 + " (+" + (w1 - stock0) + " of " + got + " harvested; a buyer may already have taken some)", w1 > stock0 && w1 - stock0 <= got);
                            check("U4 no potato lost: bank+pack " + bank0 + " -> " + own1 + " (unchanged)", own1 == bank0);
                            check("U4 tools back in the bank", p.getInventory().getAmount(Farming.SPADE) == 0 && total(p, Farming.SPADE) >= 1);
                            // U5: disease one planted patch
                            int sick = 0;
                            for (Scenery s : Farming.find(b.farmSite)) {
                                Patch pt = FarmingPatch.forObject(s).getPatchFor(p, true);
                                if (pt.getPlantable() == null || sick > 0 || FarmingPatch.forObject(s).getType() == content.global.skill.farming.PatchType.FLOWER_PATCH) continue;
                                pt.setDiseased(true); pt.update(); sick++;
                            }
                            check("U5 a planted patch to disease (replanted by the CHECK)", sick == 1);
                            if (sick == 0) { phase = 6; ticks = 0; return false; }
                            died0 = Farming.DIED; b.farmDue = now;
                            phase = 4; ticks = 0; return false;
                        }
                        case 4: {
                            if (ticks < 5) return false;
                            boolean r = b.planFarmRun(now);
                            check("U5 CHECK planFarmRun true", r);
                            if (!r) { phase = 6; ticks = 0; return false; }
                            phase = 5; ticks = 0; return false;
                        }
                        case 5: {
                            if (b.farm != 0 && ticks < 900) return false;
                            idle(b, far);
                            check("U5 DIED +1 (" + (Farming.DIED - died0) + ")", Farming.DIED - died0 == 1 || Farming.V2 && Farming.CURED >= 1);
                            phase = 6; ticks = 0; return false;
                        }
                        case 6: { // U6 logOut with crops due
                            if (b.farmDue == 0) b.farmDue = now + 30 * 60_000L;
                            aband0 = Farming.ABANDONED;
                            SmartAdventurers.logOut((AIPlayer) p, b);
                            check("U6 ABANDONED +1", Farming.ABANDONED == aband0 + 1);
                            check("U6 farmDue 0 and seat freed", b.farmDue == 0 && !Farming.ACTIVE.contains(p.getUsername().toLowerCase(java.util.Locale.ROOT)));
                            return finish();
                        }
                        default: return finish();
                    }
                } catch (Throwable x) { fail++; log("error in phase " + phase + ": " + x); x.printStackTrace(System.out); return finish(); }
            }
        });
    }

    static void off(SmartAdventurers.Brain b) {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (int i = 0; i < 200; i++) { b.farmNext = 0; b.farmTested = false; any |= b.planFarmRun(now + i); }
        long tallied = 0; for (long v : Farming.TALLY) tallied += v;
        check("OFF planFarmRun false x200", !any);
        check("OFF farm == 0, PLANTED == 0, RUNS == 0", b.farm == 0 && Farming.PLANTED == 0 && Farming.RUNS == 0);
        check("OFF no tally (no [Farm] line can print)", tallied == 0);
        finish();
    }

    static boolean finish() {
        log("totals " + Farming.totals() + " tally " + Farming.tallyLine());
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail + " (farming=" + Farming.ON + ")");
        log("DONE");
        return true;
    }
}
