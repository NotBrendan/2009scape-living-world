package content.custom;

import content.global.skill.summoning.SummoningPouch;
import content.global.skill.summoning.familiar.Familiar;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Summoning in combat unit (the design notes). Two parts:
 *  - pure (no world): main() -> combatRow / inUse / fightsALot / combatPick / choose / the row table against the engine's SummoningPouch.
 *    Offline: java -cp <build>;server.jar content.custom.TestSummonCombat (with and without -Dbots.summoning=true -Dbots.summoning.combat=true)
 *  - world (-Dtest.summonCombat=true, a farm slot): one bot at Al Kharid bank, a fight trip to the palace warriors (multi-combat).
 *    OFF (-Dbots.summoning=true only): combatPlan takes nothing, combatSummon refuses even when forced, a real runner never summons.
 *    ON (+ -Dbots.summoning.combat=true): combatPlan takes 2 wolf pouches; the real runner walks there and its work() hook summons;
 *    the engine makes the wolf join a fight; a second summon is refused while it is out; finish() dismisses it; no pouch is lost.
 * Pass lines: "[TestSummonCombat] PASS ...", "[TestSummonCombat] RESULT PASS pass=N fail=M", then "[TestSummonCombat] DONE".
 */
public final class TestSummonCombat implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestSummonCombat] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }

    static final int WOLF = 12047, FOWL = 12043, CRAB = 12009, MOUND = 12091, IRON = 12075, BEAVER = 12021, SNAIL = 12019, SHARK = 385, WYRM = 12049, STEEL = 12077, FOWL_SCROLL = 12445, CRAB_SCROLL = 12533, IRON_SCROLL = 12462;
    static final Location BANK = Location.create(3269, 3167, 0), SPOT = Location.create(3293, 3170, 0), COWS = Location.create(3255, 3270, 0);

    public static void main(String[] a) {
        try { pure(); } catch (Throwable t) { fail++; log("error " + t); t.printStackTrace(); }
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
        log("DONE");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void pure() {
        boolean want = Boolean.getBoolean("bots.summoning") && Boolean.getBoolean("bots.summoning.combat");
        check("flag Bob.COMBAT == bots.summoning && bots.summoning.combat (" + want + ")", Bob.COMBAT == want);
        check("ANY covers COMBAT", !Bob.COMBAT || Bob.ANY);
        // the trip pick: Bob.combatPlan calls combatRow(static level, current points, owned in bank + pack)
        check("pick: live level 5, wolf + dreadfowl owned -> dreadfowl", Bob.combatRow(5, 5, id -> id == WOLF || id == FOWL ? 3 : 0)[0] == FOWL);
        check("pick: level 3 -> wolf", Bob.combatRow(3, 3, id -> id == WOLF || id == FOWL ? 3 : 0)[0] == WOLF);
        check("pick: nothing owned -> none", Bob.combatRow(99, 99, id -> 0) == null);
        check("pick: no points -> none", Bob.combatRow(5, 0, id -> 5) == null);
        check("pick: highest owned row at 46", Bob.combatRow(46, 46, id -> id == IRON || id == WOLF ? 1 : 0)[0] == IRON);
        check("pick: points too low for the iron minotaur -> the crab", Bob.combatRow(46, 5, id -> id == IRON || id == CRAB ? 1 : 0)[0] == CRAB);
        check("pick: a pouch above the level is not used", Bob.combatRow(15, 15, id -> id == CRAB ? 2 : 0) == null);
        check("pick: beaver and thorny snail are not combat pouches", !Bob.isCombatPouch(BEAVER) && !Bob.isCombatPouch(SNAIL) && Bob.combatRow(99, 99, id -> id == BEAVER || id == SNAIL ? 5 : 0) == null);
        boolean rows = true, engine = true;
        for (int[] r : Bob.COMBAT_ROWS) {
            boolean in = false;
            for (int[] q : Summon.POUCHES) if (q[0] == r[0] && q[1] == r[1]) in = true;
            if (Bob.COMBAT2) for (int[] q : Summon.COMBAT2_POUCHES) if (q[0] == r[0] && q[1] == r[1]) in = true;
            rows &= in;
            SummoningPouch sp = SummoningPouch.get(r[0]);
            engine &= sp != null && sp.getLevelRequired() == r[1] && sp.getSummonCost() == r[2];
        }
        check("rows: every combat row is a pouch bots make, same level", rows);
        check("rows: level and points equal the engine's SummoningPouch", engine);
        // the one-familiar rule
        check("one familiar: engine has one -> in use", Bob.inUse(true, false));
        check("one familiar: tracked by Bob -> in use", Bob.inUse(false, true));
        check("one familiar: none -> free", !Bob.inUse(false, false));
        check("one familiar: no player -> in use", Bob.inUse(null));
        // "fights a lot": SA passes Brain.fav, fightTripsN, tripsN
        check("fighter by taste (fav -1) fights a lot from trip 0", Bob.fightsALot(-1, 0, 0));
        check("a miner after 1 fight in 3 trips does not", !Bob.fightsALot(Skills.MINING, 1, 3));
        check("half of 4 trips were fights -> does", Bob.fightsALot(Skills.MINING, 2, 4));
        check("1 fight in 4 trips -> does not", !Bob.fightsALot(Skills.FISHING, 1, 4));
        // the pouch planner: choose() calls combatPick(level, room, budget, lo, charmBank, secBank (also pouches), secBuyable, secValue)
        int[] c = Summon.combatPick(5, 12, 10_000, Summon.MIN_N, id -> 5, id -> 0, id -> 30, id -> 1);
        check("planner: level 5 fighter with no combat pouch -> dreadfowl", c != null && Summon.POUCHES[c[0]][0] == FOWL && c[1] == 5 && c[2] == 5);
        check("planner: owns 2 usable combat pouches -> normal pick", Summon.combatPick(5, 12, 10_000, Summon.MIN_N, id -> 5, id -> id == WOLF ? 2 : id == 12445 ? 10 : 0, id -> 30, id -> 1) == null);
        check("planner: pouches above the level do not count as owned", Summon.combatPick(5, 12, 10_000, Summon.MIN_N, id -> 5, id -> id == CRAB ? 2 : 0, id -> 30, id -> 1) != null);
        c = Summon.combatPick(33, 12, 1_000_000, Summon.MIN_N, id -> 5, id -> 0, id -> 30, id -> 1);
        check("planner: level 33 skips the beaver -> " + (Bob.COMBAT2 ? "desert wyrm (v2: rows with a special first)" : "compost mound"), c != null && Summon.POUCHES[c[0]][0] == (Bob.COMBAT2 ? WYRM : MOUND));
        int[] lo = Summon.combatPick(4, 12, 605, Summon.MIN_N, id -> 5, id -> 0, id -> 30, id -> 1), hi = Summon.combatPick(4, 12, 606, Summon.MIN_N, id -> 5, id -> 0, id -> 30, id -> 1);
        check("planner: same budget rule (3 dreadfowl cost 606: 605 falls to the wolf, 606 buys 3 dreadfowl)", lo != null && Summon.POUCHES[lo[0]][0] == WOLF && hi != null && Summon.POUCHES[hi[0]][0] == FOWL && hi[1] == 3);
        check("planner: no charms -> none", Summon.combatPick(33, 12, 1_000_000, Summon.MIN_N, id -> 0, id -> 0, id -> 30, id -> 1) == null);
        // the real entry (SA planSummonRun): choose(level, coins, freeSlots, bank, bank, stock, value, fighter)
        int[] plain = Summon.choose(33, 100_000_000L, 28, id -> 5, id -> 0, id -> 30, id -> 1, false);
        int[] fighter = Summon.choose(33, 100_000_000L, 28, id -> 5, id -> 0, id -> 30, id -> 1, true);
        int[] old = Summon.choose(33, 100_000_000L, 28, id -> 5, id -> 0, id -> 30, id -> 1);
        check("choose: the 7-argument call is the non-fighter pick", plain != null && old != null && plain[0] == old[0] && plain[1] == old[1]);
        if (Bob.COMBAT) check("choose ON: a fighter gets the compost mound, not the " + (Bob.ON ? "beast of burden row" : "beaver"), fighter != null && Summon.row(fighter[0])[0] == (Bob.COMBAT2 ? WYRM : MOUND) && plain[0] != fighter[0]);
        else check("choose OFF: fighter flag changes nothing", fighter != null && fighter[0] == plain[0] && fighter[1] == plain[1] && fighter[2] == plain[2] && Summon.COMBAT_PICKS == 0);
        pure2();
        // hooks with nothing to do
        Bob.combatPlan(null); Bob.combatGuard(null);
        check("hooks: no runner -> no-ops", !Bob.combatSummon(null) && Bob.combatField().startsWith(" combat: out=0"));
    }

    /** v2 (-Dbots.summoning.combat2): the picks, the scroll plans and the special-fire rule, with the values the real callers pass. */
    static void pure2() {
        check("v2 flag: Bob.COMBAT2 == COMBAT && bots.summoning.combat2", Bob.COMBAT2 == (Bob.COMBAT && Boolean.getBoolean("bots.summoning.combat2")));
        check("v2 rows: the steel minotaur row exists only with the flag", Bob.isCombatPouch(STEEL) == Bob.COMBAT2 && Bob.COMBAT_ROWS.length == (Bob.COMBAT2 ? 9 : 8));
        boolean eng = true;
        for (int[] s : Bob.SPECIALS) {
            content.global.skill.summoning.SummoningScroll sc = content.global.skill.summoning.SummoningScroll.forPouch(s[0]);
            SummoningPouch sp = SummoningPouch.get(s[0]);
            eng &= sc != null && sp != null && sc.getItemId() == s[1] && sc.getLevel() == sp.getLevelRequired() && Bob.isScroll(s[1]) && Bob.specOf(s[0]) == s;
        }
        check("v2 specials: every scroll id is the engine's SummoningScroll.forPouch, same level as the pouch", eng);
        check("v2 specials: wolf (Howl scares the monster away), rat, mound have none", Bob.specOf(WOLF) == null && Bob.specOf(12067) == null && Bob.specOf(MOUND) == null && !Bob.isScroll(12425));
        // combatPlan calls combatRow2(multiAt(spot), static level, current points, id -> bank + pack)
        check("v2 pick: multi-combat spot = v1's pick", Bob.combatRow2(true, 5, 5, id -> id == WOLF || id == FOWL ? 3 : 0)[0] == FOWL && Bob.combatRow2(true, 3, 3, id -> id == WOLF ? 3 : 0)[0] == WOLF);
        int[] one = Bob.combatRow2(false, 5, 5, id -> id == WOLF ? 3 : id == FOWL ? 2 : id == FOWL_SCROLL ? 10 : 0);
        check("v2 pick: single-combat, level 5, dreadfowl + 10 scrolls -> dreadfowl", one != null && one[0] == FOWL);
        check("v2 pick: single-combat, 2 scrolls only -> none", Bob.combatRow2(false, 5, 5, id -> id == FOWL ? 2 : id == FOWL_SCROLL ? 2 : 0) == null);
        check("v2 pick: single-combat, wolf only (no special) -> none", Bob.combatRow2(false, 5, 5, id -> id == WOLF ? 4 : id == 12425 ? 10 : 0) == null);
        check("v2 pick: single-combat, scrolls of a pouch it does not own -> none", Bob.combatRow2(false, 5, 5, id -> id == WOLF ? 4 : id == FOWL_SCROLL ? 10 : 0) == null);
        int[] hi = Bob.combatRow2(false, 46, 46, id -> id == IRON || id == FOWL ? 1 : id == IRON_SCROLL || id == FOWL_SCROLL ? 20 : 0);
        check("v2 pick: single-combat at 46 -> iron minotaur over dreadfowl", hi != null && hi[0] == IRON);
        int[] lowPts = Bob.combatRow2(false, 46, 5, id -> id == IRON || id == CRAB ? 1 : id == IRON_SCROLL || id == CRAB_SCROLL ? 20 : 0);
        check("v2 pick: 5 points left -> the crab", lowPts != null && lowPts[0] == CRAB);
        // combatPlan: scrollTake(pack, bank); combatSummon: summonHere(isMultiZone, COMBAT2, scrolls in pack)
        check("v2 take: 20 of 30 banked, top-up to 20, none when 20 carried, all of 7", Bob.scrollTake(0, 30) == 20 && Bob.scrollTake(5, 30) == 15 && Bob.scrollTake(20, 30) == 0 && Bob.scrollTake(0, 7) == 7 && Bob.scrollTake(0, 0) == 0);
        check("v2 summon: multi always; single only with the flag and a scroll", Bob.summonHere(true, false, 0) && Bob.summonHere(true, true, 0) && !Bob.summonHere(false, false, 20) && Bob.summonHere(false, true, 20) && !Bob.summonHere(false, true, 0));
        // the errand: scrollBatch(pouches made this run, pouches owned bank + pack, scrolls owned bank + pack)
        check("v2 scrolls: batch of 5, none owned -> 2 pouches (20 scrolls), 3 stay", Bob.scrollBatch(5, 5, 0) == 2);
        check("v2 scrolls: smallest batch of 3 -> 1 pouch", Bob.scrollBatch(3, 3, 0) == 1);
        check("v2 scrolls: 40 scrolls owned -> none; 35 owned -> 1; nothing made -> none", Bob.scrollBatch(3, 3, 40) == 0 && Bob.scrollBatch(12, 14, 35) == 1 && Bob.scrollBatch(0, 4, 0) == 0);
        check("v2 scrolls: never below 2 pouches kept", Bob.scrollBatch(12, 12, 0) == 4 && Bob.scrollBatch(4, 2, 0) == 0 && Bob.scrollBatch(4, 3, 0) == 1);
        // combatSpecial: specialWhy(spec, familiar points, scrolls in pack, ms since the last, fighting, target kind, target hp, fam->target, fam->owner, Defence boosted, familiar already on it)
        int[] fowl = Bob.specOf(FOWL), crab = Bob.specOf(CRAB), steel = Bob.specOf(STEEL);
        long first = System.currentTimeMillis();
        check("v2 fire: dreadfowl, fresh familiar (60 points), cow at 8 hp next to it -> fire", Bob.specialWhy(fowl, 60, 20, first, true, 1, 8, 1.5, 1.0, false, false) == 0);
        check("v2 fire: 2 points left -> wait", Bob.specialWhy(fowl, 2, 20, first, true, 1, 8, 1.5, 1.0, false, false) == 3);
        check("v2 fire: no scroll -> no", Bob.specialWhy(fowl, 60, 0, first, true, 1, 8, 1.5, 1.0, false, false) == 2);
        check("v2 fire: 2 s after the last -> cooldown; 6 s -> fire", Bob.specialWhy(fowl, 57, 19, 2_000, true, 1, 8, 1.5, 1.0, false, false) == 4 && Bob.specialWhy(fowl, 57, 19, 6_000, true, 1, 8, 1.5, 1.0, false, false) == 0);
        check("v2 fire: never on a player", Bob.specialWhy(fowl, 60, 20, first, true, 2, 99, 1.5, 1.0, false, false) == 7);
        check("v2 fire: no target / not fighting -> no", Bob.specialWhy(fowl, 60, 20, first, true, 0, 0, 99, 1.0, false, false) == 6 && Bob.specialWhy(fowl, 60, 20, first, false, 0, 0, 99, 1.0, false, false) == 5);
        check("v2 fire: nearly dead monster (2 hp) -> no", Bob.specialWhy(fowl, 60, 20, first, true, 1, 2, 1.5, 1.0, false, false) == 8);
        check("v2 fire: target 9 tiles from the familiar, or familiar 16 from the bot -> no", Bob.specialWhy(fowl, 60, 20, first, true, 1, 8, 9.0, 1.0, false, false) == 9 && Bob.specialWhy(fowl, 60, 20, first, true, 1, 8, 1.5, 16.0, false, false) == 9);
        check("v2 fire: dreadfowl already attacking that monster -> no second scroll", Bob.specialWhy(fowl, 60, 20, first, true, 1, 8, 1.5, 1.0, false, true) == 11);
        check("v2 fire: pouch without a special -> no", Bob.specialWhy(Bob.specOf(WOLF), 60, 20, first, true, 1, 8, 1.5, 1.0, false, false) == 1);
        check("v2 fire: crab shell needs no target, once a minute, not while boosted", Bob.specialWhy(crab, 60, 5, first, true, 0, 0, 99, 1.0, false, false) == 0 && Bob.specialWhy(crab, 48, 4, 30_000, true, 0, 0, 99, 1.0, false, false) == 4
                && Bob.specialWhy(crab, 48, 4, 61_000, true, 1, 8, 1.5, 1.0, true, false) == 10 && Bob.specialWhy(crab, 11, 4, 61_000, true, 1, 8, 1.5, 1.0, false, false) == 3);
        check("v2 fire: a 9-max Bull Rush is not spent on an 8 hp cow, is on a 22 hp guard", Bob.specialWhy(steel, 60, 20, first, true, 1, 8, 1.5, 1.0, false, false) == 8 && Bob.specialWhy(steel, 60, 20, first, true, 1, 22, 1.5, 1.0, false, false) == 0);
        // the planner under v2: combatPick2(level, room, budget, lo, charmBank, secBank (pouches and scrolls too), secBuyable, secValue)
        int[] low = Summon.combatPick2(5, 12, 10_000, Summon.MIN_N, id -> 5, id -> id == FOWL ? 2 : 0, id -> 30, id -> 1);
        check("v2 planner: 2 dreadfowl pouches but no scrolls -> one more dreadfowl batch", low != null && Summon.row(low[0])[0] == FOWL);
        check("v2 planner: 2 pouches and 10 scrolls -> normal pick", Summon.combatPick2(5, 12, 10_000, Summon.MIN_N, id -> 5, id -> id == FOWL ? 2 : id == FOWL_SCROLL ? 10 : 0, id -> 30, id -> 1) == null);
        check("v2 planner: level 3 (wolf only, no special) with 2 wolves -> normal pick", Summon.combatPick2(3, 12, 10_000, Summon.MIN_N, id -> 5, id -> id == WOLF ? 2 : 0, id -> 30, id -> 1) == null);
        int[] st = Summon.combatPick(56, 12, 1_000_000, Summon.MIN_N, id -> 5, id -> 0, id -> 30, id -> 1);
        if (Bob.COMBAT2) {
            check("v2 planner ON: level 56 -> steel minotaur (row " + (st == null ? -1 : st[0]) + "), 141 shards", st != null && Summon.row(st[0])[0] == STEEL && Summon.row(st[0])[4] == 141 && Summon.shopBill(st[0], 1) == 141 * 25 + 1);
            check("v2 tally: combat2 fields are the end of the combat field", Bob.combatField().endsWith(" combat2: trips=" + Bob.C2_TRIPS + " specials=" + Bob.C2_SPECIALS + " scrollsMade=" + Bob.C2_SCROLLS_MADE + " scrollsUsed=" + Bob.C2_SCROLLS_USED));
        } else {
            check("v2 OFF: level 56 pick is v1's iron minotaur, no combat2 field, hooks are no-ops", st != null && Summon.row(st[0])[0] == IRON && !Bob.combatField().contains("combat2") && !Bob.combatSpecial(null));
        }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.summonCombat")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, step, waited; AIPlayer bot; AfkTasks.Runner r;
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    return Bob.COMBAT ? on() : off();
                } catch (Throwable e) { fail++; log("error " + e); e.printStackTrace(); return done(); }
            }
            boolean done() {
                if (r != null) { // W1 lane M: the [KillRate] line has the same shape with combat familiars on or off
                    String kr = r.killRateLine("test end"); log(kr);
                    check("kill-rate line: same format both ways (fam=" + (Bob.COMBAT ? "on" : "off") + ")", kr.matches("\\[KillRate\\] .+ task=\\w+ fam=(on|off) pouches=\\d+ attacks=\\d+ kills=\\d+ famKills=\\d+ fightMin=\\d+\\.\\d perMin=\\d+\\.\\d\\d killsPerMin=\\d+\\.\\d\\d loot=\\d+ combat=\\d+ why=test end")
                            && kr.contains(" fam=" + (Bob.COMBAT ? "on" : "off") + " ") && kr.contains(" attacks=" + r.kills + " kills=" + r.npcKills + " famKills=" + r.famKills + " ") && r.famKills <= r.npcKills);
                }
                try { if (r != null) r.halt(); } catch (Throwable ignored) { }
                if (bot != null) { try { Bob.leave(bot, "clear"); bot.clear(); } catch (Throwable ignored) { } }
                log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail + " |" + Bob.combatField());
                log("DONE");
                return true;
            }
            void mk() {
                bot = new AIPlayer(BANK);
                SmartAdventurers.finishTutorial(bot);
                for (int s : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(s, 60); bot.getSkills().setLevel(s, 60); }
                bot.getInventory().add(new Item(SHARK, 6));
                // The farm flag -Dsmartbots.testCombatFam seeds each bot once in combatPlan; this bot is seeded here, so mark it: 4 pouches, not 8.
                Bob.COMBAT_SEEDED.add(Bob.key(bot));
                Bob.seedCombat(bot, WOLF);
                r = new AfkTasks.Runner(bot, AfkTasks.Task.AL_KHARID, true, 28, false, true);
                r.spot = SPOT; r.sell = true;
            }
            Familiar gone; long resummons;
            int wolves() { return bot.getInventory().getAmount(WOLF) + bot.getBank().getAmount(WOLF); }

            /** v2 in-world: a dreadfowl with scrolls on a single-combat cow trip; one special must fire through the engine. */
            boolean v2() {
                int scrolls = bot.getInventory().getAmount(FOWL_SCROLL) + bot.getBank().getAmount(FOWL_SCROLL);
                switch (step) {
                    case 10: {
                        if (++waited < 4) return false;
                        // summon4: a familiar drains Summoning points while it is out (Familiar.handleTickActions); the seed's level 4 leaves none for the
                        // trip's second pouch. Level 20 still picks the dreadfowl in single-combat: the only pouch with scrolls this bot owns.
                        bot.getSkills().setStaticLevel(Skills.SUMMONING, 20); bot.getSkills().setLevel(Skills.SUMMONING, 20);
                        Bob.seedCombat(bot, FOWL);
                        // W1 lane M: loot off. fight() picks up ground loot before it looks for a target, and other bots' kills litter the cow field: on train-58 the bot
                        // filled its pack from the ground, walked to a bank and back twice and gave no attack order in 700 ticks (u58 sc-v2 80/1). The checks need a fight, not loot.
                        r = new AfkTasks.Runner(bot, AfkTasks.Task.COW, true, 28, false, false);
                        r.spot = COWS; r.sell = true;
                        Bob.combatPlan(r);
                        check("v2 ON: a single-combat cow trip plans the dreadfowl and 20 of 30 scrolls (pouch " + r.famPouch + ", scroll " + r.famScroll + ", pack " + bot.getInventory().getAmount(FOWL_SCROLL) + ")",
                                r.famPouch == FOWL && r.famScroll == FOWL_SCROLL && bot.getInventory().getAmount(FOWL_SCROLL) == 20 && bot.getBank().getAmount(FOWL_SCROLL) == 10);
                        if (r.famPouch != FOWL) return done();
                        bot.getProperties().setTeleportLocation(COWS);
                        step = 11; waited = 0; return false;
                    }
                    case 11: {
                        if (++waited < 4) return false;
                        check("v2 ON: the cow field is single-combat for the bot", !bot.getProperties().isMultiZone());
                        check("v2 ON: runner launched", AfkTasks.launch(r, AfkTasks.State.TO_SPOT, COWS));
                        step = 12; waited = 0; return false;
                    }
                    case 12: {
                        if (Bob.C2_SPECIALS == 0 && ++waited < 700) return false;
                        Familiar f = bot.getFamiliarManager().getFamiliar();
                        check("v2 ON: the dreadfowl was summoned in single-combat and one special fired through executeSpecialMove (after " + waited + " ticks, specials " + Bob.C2_SPECIALS + ", refused " + Bob.C2_REFUSED + ", decisions " + java.util.Arrays.toString(Bob.C2_WHY).replace(" ", "") + ", summoned " + Bob.C_SUMMONED + ", attacks " + r.kills + ", last " + r.last + ")",
                                Bob.C2_SPECIALS >= 1 && f != null && f.getPouchId() == FOWL && !bot.getProperties().isMultiZone());
                        check("v2 ON: each special took exactly one scroll (" + scrolls + " left of 30, used " + Bob.C2_SCROLLS_USED + ")", scrolls == 30 - Bob.C2_SCROLLS_USED && Bob.C2_SCROLLS_USED == Bob.C2_SPECIALS);
                        if (f == null) { r.finish("test end"); return done(); }
                        if (r.famUsed >= 2) { // the first dreadfowl's own 4 minute timer already ran out on this trip: the re-summon has happened for real
                            check("v2 ON: the runner's own work() summoned the trip's second pouch after the first ended (its timer; pouch " + r.famUsed + " of the trip, reSummons " + Bob.C_RESUMMONS + ", pack " + bot.getInventory().getAmount(FOWL) + ")", Bob.C_RESUMMONS >= 1 && Bob.OUT.get(Bob.key(bot)) == f && bot.getInventory().getAmount(FOWL) == 0);
                            r.finish("test end");
                            check("v2 ON: dismissed at trip end, trip counted", !bot.getFamiliarManager().hasFamiliar() && !Bob.OUT.containsKey(Bob.key(bot)) && !Bob.CSTAT.containsKey(Bob.key(bot)) && Bob.C2_TRIPS >= 1);
                            return done();
                        }
                        // summon4: end the familiar the way the engine's timer does (Familiar.handleTickActions -> dismiss()); Bob is not told.
                        gone = f; resummons = Bob.C_RESUMMONS; f.dismiss();
                        check("v2 ON: the engine ended the dreadfowl, Bob still tracks it", !bot.getFamiliarManager().hasFamiliar() && !gone.isActive() && Bob.OUT.get(Bob.key(bot)) == gone);
                        step = 13; waited = 0; return false;
                    }
                    case 13: {
                        Familiar f = bot.getFamiliarManager().getFamiliar();
                        if (f == null && ++waited < 600) return false;
                        check("v2 ON: the runner's own work() summoned the trip's second pouch after the first ended (after " + waited + " ticks, reSummons " + (Bob.C_RESUMMONS - resummons) + ", points " + bot.getSkills().getLevel(Skills.SUMMONING) + ", fails " + Bob.C_FAILS + ", pack " + bot.getInventory().getAmount(FOWL) + ", at " + bot.getLocation() + ", last " + r.last + ")",
                                f != null && f != gone && f.getPouchId() == FOWL && Bob.OUT.get(Bob.key(bot)) == f && Bob.C_RESUMMONS == resummons + 1 && bot.getInventory().getAmount(FOWL) == 0);
                        r.finish("test end");
                        check("v2 ON: dismissed at trip end, trip counted", !bot.getFamiliarManager().hasFamiliar() && !Bob.OUT.containsKey(Bob.key(bot)) && !Bob.CSTAT.containsKey(Bob.key(bot)) && Bob.C2_TRIPS >= 1); // summon4: this bot's entries (another bot's fun familiar may be out by now)
                        return done();
                    }
                    default: return done();
                }
            }

            boolean off() {
                step++;
                if (step == 1) { pure(); mk(); return false; }
                if (step == 2) return false; // W1 lane M: step 2 fell through to the last check and done(): the OFF trip (and both step 3 checks) never ran
                if (step == 3) {
                    if (Bob.ON) Bob.plan(r);
                    Bob.combatPlan(r);
                    check("OFF: plan takes no pouch", r.famPouch == 0 && bot.getBank().getAmount(WOLF) == 4 && bot.getInventory().getAmount(WOLF) == 0);
                    r.famPouch = WOLF;
                    check("OFF: summon refuses even when forced", !Bob.combatSummon(r) && !bot.getFamiliarManager().hasFamiliar());
                    r.famPouch = 0;
                    if (!AfkTasks.launch(r, AfkTasks.State.TO_SPOT, SPOT)) log("note: runner did not launch: " + r.last);
                    return false;
                }
                if (step > 3 && step < 150) { if (bot.getFamiliarManager().hasFamiliar()) { check("OFF: no familiar during a real fight trip (tick " + step + ")", false); return done(); } return false; }
                check("OFF: no familiar after 146 ticks of a fight trip, pouches untouched", !bot.getFamiliarManager().hasFamiliar() && Bob.OUT.isEmpty() && wolves() == 4);
                return done();
            }

            boolean on() {
                switch (step) {
                    case 0: pure(); mk(); step = 1; return false;
                    case 1: {
                        check("ON: the palace is multi-combat, the cow field is not (engine list)", Bob.multiAt(SPOT) && !Bob.multiAt(COWS));
                        if (Bob.ON) Bob.plan(r);
                        Bob.combatPlan(r);
                        check("ON: plan takes 2 wolf pouches (pack " + bot.getInventory().getAmount(WOLF) + ", bank " + bot.getBank().getAmount(WOLF) + ")", r.famPouch == WOLF && bot.getInventory().getAmount(WOLF) == 2 && bot.getBank().getAmount(WOLF) == 2);
                        AfkTasks.Runner cows = new AfkTasks.Runner(bot, AfkTasks.Task.COW, true, 28, false, true);
                        cows.spot = COWS; Bob.combatPlan(cows);
                        check("ON: a single-combat hunt plans no familiar", cows.famPouch == 0 && bot.getInventory().getAmount(WOLF) == 2);
                        AfkTasks.Runner trees = new AfkTasks.Runner(bot, AfkTasks.Task.WILLOW, true, 28, false, false);
                        trees.spot = SPOT; Bob.combatPlan(trees);
                        check("ON: a gathering trip plans no combat familiar", trees.famPouch == 0);
                        check("ON: runner launched", AfkTasks.launch(r, AfkTasks.State.TO_SPOT, SPOT));
                        step = 2; waited = 0; return false;
                    }
                    case 2: {
                        Familiar f = bot.getFamiliarManager().getFamiliar();
                        if (f == null && ++waited < 500) return false;
                        check("ON: the runner's own work() summoned the wolf at the spot (after " + waited + " ticks, at " + bot.getLocation() + ", last " + r.last + ")", f != null && f.getPouchId() == WOLF && Bob.COMBAT_KEYS.contains(Bob.key(bot)));
                        if (f == null) return done();
                        check("ON: the engine calls it a combat familiar, the bot stands in multi-combat", f.isCombatFamiliar() && bot.getProperties().isMultiZone());
                        check("ON: one pouch used, one left in the pack", bot.getInventory().getAmount(WOLF) == 1 && wolves() == 3);
                        r.famNext = 0;
                        check("ON: one familiar at a time: a second summon is refused", Bob.inUse(bot) && !Bob.combatSummon(r) && bot.getInventory().getAmount(WOLF) == 1);
                        step = 3; waited = 0; return false;
                    }
                    case 3: {
                        long[] st = Bob.CSTAT.get(Bob.key(bot));
                        if ((st == null || st[1] == 0) && bot.getFamiliarManager().hasFamiliar() && ++waited < 400) return false;
                        check("ON: the wolf joined a fight by itself (engine auto-attack; " + waited + " ticks, runner attacks " + r.kills + ")", st != null && st[1] >= 1);
                        r.finish("test end");
                        check("ON: dismissed at trip end", !bot.getFamiliarManager().hasFamiliar() && Bob.OUT.isEmpty() && Bob.COMBAT_KEYS.isEmpty() && Bob.CSTAT.isEmpty());
                        check("ON: no pouch lost (3 left of 4, 1 summoned)", wolves() == 3 && Bob.C_SUMMONED == 1 && Bob.C_ENDED == 1);
                        Bob.leave(bot, "again"); Bob.leave(null, "x");
                        step = 4; waited = 0; return false;
                    }
                    default:
                        if (step >= 10) return v2();
                        if (++waited < 5) return false;
                        check("ON: no familiar NPC left", !bot.getFamiliarManager().hasFamiliar() && Bob.OUT.isEmpty());
                        if (Bob.COMBAT2) { try { r.halt(); } catch (Throwable ignored) { } bot.getPulseManager().clear(); bot.getProperties().setTeleportLocation(BANK); step = 10; waited = 0; return false; }
                        return done();
                }
            }
        });
    }
}
