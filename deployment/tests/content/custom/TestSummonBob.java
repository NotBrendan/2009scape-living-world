package content.custom;

import content.global.skill.summoning.familiar.BurdenBeast;
import content.global.skill.summoning.familiar.Familiar;
import core.api.StartupListener;
import content.global.skill.summoning.familiar.FamiliarManager;
import core.game.bots.AIPlayer;
import core.game.node.Node;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * P92 Summoning S4 unit (the design notes section 1). Two parts:
 *  - pure (no world): main() -> pickRow / storableId / kindOk / placeOk / flag. Offline: java -cp <build>;server.jar content.custom.TestSummonBob
 *  - world (-Dtest.summonBob=true, a farm slot): one bot at Draynor bank with a bronze axe, willow trip to DRAY (3087,3236).
 *    OFF (-Dbots.summoning=true, no -Dbots.summoning.bob): Summoning 13 + 2 snail pouches, plan/summon/store/bankPass and a 60-tick
 *    real runner never summon anything; pouches untouched.
 *    ON (-Dbots.summoning=true -Dbots.summoning.bob=true): Summoning 5 -> no plan (harmless below level); seeded to 13 -> plan takes 1
 *    pouch; summon at the spot -> BurdenBeast out; full pack -> 3 logs into the snail, axe/food/coins stay; at the bank the real
 *    deposit() empties it (Withdraw-All then a second pass); loaded again then Spectacle.teleport -> dismissed first, every log kept.
 * Pass lines: "[TestSummonBob] PASS ..." and "[TestSummonBob] RESULT PASS".
 */
public final class TestSummonBob implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestSummonBob] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }

    static final int SNAIL = 12019, LOGS = 1519, AXE = 1351, SHARK = 385;
    static final Location BANK = Location.create(3092, 3243, 0), SPOT = Location.create(3087, 3236, 0);

    public static void main(String[] a) { pure(); log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail); System.exit(fail == 0 ? 0 : 1); }

    static void pure() {
        boolean want = Boolean.getBoolean("bots.summoning") && Boolean.getBoolean("bots.summoning.bob");
        check("flag Bob.ON == bots.summoning && bots.summoning.bob (" + want + ")", Bob.ON == want);
        // w29 (-Dbots.summoning.bob.why): log-only split of the notEligible count
        check("w29 flag Bob.WHY2 == Bob.ON && bots.summoning.bob.why", Bob.WHY2 == (want && Boolean.getBoolean("bots.summoning.bob.why")));
        check("w29 eligCode: all pass -> -1", Bob.eligCode(true, true, true, true, true, true, true) == -1);
        check("w29 eligCode: first failing test wins, in eligible()'s order", Bob.eligCode(false, false, false, false, false, false, false) == 0 && Bob.eligCode(true, false, false, true, true, true, true) == 1
                && Bob.eligCode(true, true, false, false, true, true, true) == 2 && Bob.eligCode(true, true, true, false, false, true, true) == 3 && Bob.eligCode(true, true, true, true, false, false, true) == 4
                && Bob.eligCode(true, true, true, true, true, false, false) == 5 && Bob.eligCode(true, true, true, true, true, true, false) == 6);
        check("w29 bob line: elig=[...] only under the flag", Bob.bobLine().contains(" elig=[") == Bob.WHY2 && Bob.EWHY.length == 7);
        check("ANY covers ON", !Bob.ON || Bob.ANY);
        check("pick none below 13", Bob.pickRow(12, 99, id -> 5) == null);
        check("pick none at live levels 4-5", Bob.pickRow(5, 5, id -> 5) == null);
        check("pick snail at 13", Bob.pickRow(13, 13, id -> id == SNAIL ? 1 : 0)[0] == SNAIL);
        check("pick needs a pouch", Bob.pickRow(13, 13, id -> 0) == null);
        check("pick needs points", Bob.pickRow(13, 1, id -> 1) == null);
        check("pick highest owned", Bob.pickRow(70, 70, id -> id == 12087 || id == SNAIL ? 1 : 0)[0] == 12087);
        check("store: product log", Bob.storableId(LOGS, true, false, false, true, 30));
        check("store: never a non-product", !Bob.storableId(LOGS, false, false, false, true, 30));
        check("store: never a kept tool", !Bob.storableId(AXE, true, true, false, true, 30));
        check("store: never stackables", !Bob.storableId(LOGS, true, false, true, true, 30));
        check("store: never untradeables", !Bob.storableId(LOGS, true, false, false, false, 30));
        check("store: never > 50k", !Bob.storableId(LOGS, true, false, false, true, 50_001));
        check("store: never food", !Bob.storableId(SHARK, true, false, false, true, 300));
        check("store: never charms", !Bob.storableId(Summon.GOLD, true, false, false, true, 1));
        check("store: never pouches", !Bob.storableId(SNAIL, true, false, false, true, 1));
        check("kinds", Bob.kindOk(AfkTasks.Kind.TREE) && Bob.kindOk(AfkTasks.Kind.FISH) && !Bob.kindOk(AfkTasks.Kind.FIGHT) && !Bob.kindOk(AfkTasks.Kind.THIEVE) && !Bob.kindOk(AfkTasks.Kind.GROUND));
        check("place: Draynor ok", Bob.placeOk(SPOT));
        check("place: Wilderness, upstairs, essence mine, underground refused", !Bob.placeOk(Location.create(3100, 3600, 0)) && !Bob.placeOk(Location.create(3087, 3236, 1))
                && !Bob.placeOk(Location.create(2910, 4830, 0)) && !Bob.placeOk(Location.create(3000, 9800, 0)));
        check("cap 1..20", Bob.CAP >= 1 && Bob.CAP <= 20);
        check("summon rows after POUCHES", Summon.row(Summon.POUCHES.length)[0] == SNAIL && Summon.row(0) == Summon.POUCHES[0]);
        check("bob pick off: plan/summon/store no-ops without a runner", Bob.store(null) == 0 && !Bob.summon(null) && !Bob.bankPass(null));
        firstPouch();
        fix5();
    }

    static Object alloc(Class<?> c) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = u.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), c);
    }
    static void set(Object o, Class<?> c, String name, Object v) throws Exception { java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(o, v); }

    /** train-57 fix5 (-Dbots.summoning.fix5, needs bots.summoning.fun), no world: a player, its FamiliarManager and a dreadfowl are bare engine objects
     *  (no constructor run), the entry is tracked the way funSummon tracks it, and the call is the bot loop's own: Bob.think (SmartAdventurers think,
     *  "if (Bob.FUN) Bob.think(this, now)"). Same number of lines in every flag set; the expectation follows the flags. */
    static void fix5() {
        boolean want = Boolean.getBoolean("bots.summoning") && Boolean.getBoolean("bots.summoning.fun") && Boolean.getBoolean("bots.summoning.fix5");
        check("f5 flag Bob.FIX5 == bots.summoning && bots.summoning.fun && bots.summoning.fix5 (" + want + ")", Bob.FIX5 == want);
        final String k = "f5unit";
        try {
            Player p = (Player) alloc(Player.class); set(p, Node.class, "name", k);
            FamiliarManager fm = (FamiliarManager) alloc(FamiliarManager.class); set(p, Player.class, "familiarManager", fm);
            Familiar f = (Familiar) alloc(Class.forName("content.global.skill.summoning.familiar.DreadfowlNPC"));
            SmartAdventurers.Brain b = (SmartAdventurers.Brain) alloc(SmartAdventurers.Brain.class);
            long now = System.currentTimeMillis();
            b.bot = p; b.idleUntil = now + 600_000L; // the visit goes on: think() has no trip end to act on
            set(fm, FamiliarManager.class, "familiar", f); set(f, Node.class, "active", true);
            Bob.OUT.put(k, f); Bob.FUN_KEYS.add(k); Bob.OUT_AT.put(k, now - 240_000L);
            check("f5 funGone: false while the engine has it, false with no entry or no player", !Bob.funGone(p, f) && !Bob.funGone(p, null) && !Bob.funGone(null, f));
            Bob.think(b, now);
            check("f5 think(): a living fun familiar stays tracked (every flag set)", Bob.OUT.get(k) == f && Bob.FUN_KEYS.contains(k));
            set(fm, FamiliarManager.class, "familiar", null); set(f, Node.class, "active", false); // its own timer ended it in the engine
            check("f5 funGone: true once the engine dropped it", Bob.funGone(p, f));
            long e0 = Bob.EXPIRED;
            Bob.think(b, now);
            boolean gone = !Bob.OUT.containsKey(k) && !Bob.FUN_KEYS.contains(k) && !Bob.OUT_AT.containsKey(k), stale = Bob.OUT.get(k) == f && Bob.FUN_KEYS.contains(k);
            check("f5 think() after the timer, fun=" + Bob.FUN + " fix5=" + Bob.FIX5 + ": " + (Bob.FIX5 ? "entry dropped and counted expired" : "stale entry stays (old path)"),
                    Bob.FIX5 ? gone && Bob.EXPIRED == e0 + 1 : stale && Bob.EXPIRED == e0);
            check("f5 inUse after the timer is " + !Bob.FIX5, Bob.inUse(p) == !Bob.FIX5);
            Bob.think(b, now);
            check("f5 think() a second time changes nothing", Bob.EXPIRED == e0 + (Bob.FIX5 ? 1 : 0) && Bob.OUT.containsKey(k) == !Bob.FIX5);
            Bob.sweep();
            check("f5 sweep() clears it on the old path too (any summoning half on: " + Bob.ANY + ")", Bob.OUT.containsKey(k) == !Bob.ANY && Bob.EXPIRED == e0 + (Bob.ANY ? 1 : 0));
        } catch (Throwable t) { check("f5 bare engine objects (" + t + ")", false); }
        finally { Bob.OUT.remove(k); Bob.FUN_KEYS.remove(k); Bob.OUT_AT.remove(k); Bob.FROM.remove(k); }
    }

    /** s4b M1: the first beast of burden pouch. bobPick(level, room, budget, charmBank, secBank (also pouches), secBuyable, secValue). */
    static void firstPouch() {
        final int P = Summon.POUCHES.length, SN = Summon.THIN_SNAIL, CACTUS = 3138, KALPHITE = 12063;
        int[] c = Summon.bobPick(13, 12, 10_000, id -> 5, id -> 0, id -> 0, id -> 1);
        check("first pouch: 13, no snails anywhere -> 2 thorny snail, 2 to buy", c != null && c[0] == P && c[1] == 2 && c[2] == 2);
        check("first pouch: thin snail has a paid source, potato cactus none", Summon.bobShop(SN) == Summon.SNAIL_PRICE && Summon.SNAIL_PRICE >= 1 && Summon.bobShop(CACTUS) == 0);
        c = Summon.bobPick(13, 12, 10_000, id -> 5, id -> id == SN ? 2 : 0, id -> 0, id -> 1);
        check("first pouch: own snails are used first", c != null && c[0] == P && c[2] == 0);
        c = Summon.bobPick(13, 12, 10_000, id -> 5, id -> id == SN ? 1 : 0, id -> 30, id -> 1);
        check("first pouch: Exchange stock >= 20 counts", c != null && c[0] == P && c[1] == 2 && c[2] == 1);
        long two = 2 * (9L * Summon.SHARD_PRICE + Summon.POUCH_PRICE + Summon.SNAIL_PRICE);
        check("first pouch: budget covers shards, pouches and snails", Summon.bobPick(13, 12, two, id -> 5, id -> 0, id -> 0, id -> 1) != null && Summon.bobPick(13, 12, two - 1, id -> 5, id -> 0, id -> 0, id -> 1) == null);
        check("first pouch: none without charms, room or level", Summon.bobPick(13, 12, 10_000, id -> 1, id -> 0, id -> 0, id -> 1) == null && Summon.bobPick(13, 1, 10_000, id -> 5, id -> 0, id -> 0, id -> 1) == null && Summon.bobPick(12, 12, 10_000, id -> 5, id -> 0, id -> 0, id -> 1) == null);
        check("first pouch: 2 owned -> normal pick", Summon.bobPick(13, 12, 10_000, id -> 5, id -> id == SNAIL ? 2 : 0, id -> 0, id -> 1) == null);
        c = Summon.bobPick(25, 12, 100_000, id -> id == Summon.GOLD ? 5 : 0, id -> 0, id -> 0, id -> 1);
        check("fall through: 25 with no blue charms -> snail row", c != null && c[0] == P);
        c = Summon.bobPick(25, 12, 100_000, id -> 5, id -> 0, id -> 0, id -> 1);
        check("fall through: 25 with no potato cactus -> snail row", c != null && c[0] == P);
        c = Summon.bobPick(25, 12, 2_000, id -> 5, id -> 0, id -> id == CACTUS ? 50 : 0, id -> 1);
        check("fall through: 25 short of the kalphite budget -> snail row", c != null && c[0] == P);
        c = Summon.bobPick(25, 12, 100_000, id -> 5, id -> 0, id -> id == CACTUS ? 50 : 0, id -> 1);
        check("best row still first: 25 with cactus on the Exchange -> kalphite", c != null && c[0] == P + 1 && c[2] == 2);
        check("owns 2 of its best row -> normal pick, no lower row", Summon.bobPick(25, 12, 100_000, id -> 5, id -> id == KALPHITE ? 2 : 0, id -> 0, id -> 1) == null);
        check("P92 hand-out cap defaults to 2", System.getProperty("quest.p92Fix.cap") != null || P92.CAP == 2);
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.summonBob")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, step, f5; AIPlayer bot; AfkTasks.Runner r; long logs0; Familiar fun; SmartAdventurers.Brain brain; long exp0;
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    if (f5 > 0) return fix5World();
                    return Bob.ON ? on() : off();
                } catch (Throwable e) { fail++; log("error " + e); e.printStackTrace(); return done(); }
            }
            boolean done() {
                if (bot != null) { try { Bob.leave(bot, "clear"); bot.clear(); } catch (Throwable ignored) { } }
                log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail + " | " + Bob.bobLine());
                log("DONE"); // run-test.sh waits for "[TestSummonBob] DONE"
                return true;
            }
            AIPlayer mk() {
                AIPlayer b = new AIPlayer(BANK);
                SmartAdventurers.finishTutorial(b);
                b.getSkills().setStaticLevel(Skills.WOODCUTTING, 30); b.getSkills().setLevel(Skills.WOODCUTTING, 30);
                b.getInventory().add(new Item(AXE));
                return b;
            }
            AfkTasks.Runner runner() {
                AfkTasks.Runner x = new AfkTasks.Runner(bot, AfkTasks.Task.WILLOW, true, 28, false, false);
                x.spot = SPOT; x.sell = true;
                return x;
            }
            long logsHeld() {
                long n = bot.getInventory().getAmount(LOGS) + bot.getBank().getAmount(LOGS);
                Familiar f = bot.getFamiliarManager().getFamiliar();
                if (f instanceof BurdenBeast) n += ((BurdenBeast) f).getContainer().getAmount(LOGS);
                return n;
            }
            /** fix5 in a world (only with bots.summoning.fun): a real dreadfowl, tracked as funSummon tracks it, ended by the engine's own
             *  FamiliarManager.dismiss() (what its timer calls), then the bot loop's Bob.think. Runs after the beast of burden part. */
            boolean fin() { if (!Bob.FUN || bot == null) return done(); f5 = 1; return false; }
            boolean fix5World() {
                switch (f5++) {
                    case 1: {
                        Bob.leave(bot, "f5");
                        if (bot.getSkills().getStaticLevel(Skills.SUMMONING) < Bob.FOWL_LVL) bot.getSkills().setStaticLevel(Skills.SUMMONING, Bob.FOWL_LVL);
                        bot.getSkills().setLevel(Skills.SUMMONING, bot.getSkills().getStaticLevel(Skills.SUMMONING));
                        bot.getInventory().remove(new Item(LOGS, 1)); bot.getInventory().add(new Item(Bob.FOWL, 1));
                        bot.getFamiliarManager().summon(new Item(Bob.FOWL), false);
                        fun = bot.getFamiliarManager().getFamiliar();
                        check("f5 world: a dreadfowl is out", fun != null && fun.getPouchId() == Bob.FOWL && fun.isActive());
                        if (fun == null) return done();
                        String k = Bob.key(bot); long now = System.currentTimeMillis();
                        Bob.OUT.put(k, fun); Bob.FUN_KEYS.add(k); Bob.OUT_AT.put(k, now); Bob.FROM.put(k, bot.getLocation());
                        brain = new SmartAdventurers.Brain(); brain.bot = bot; brain.idleUntil = now + 600_000L;
                        Bob.think(brain, now);
                        check("f5 world: think() keeps a living fun familiar", Bob.OUT.get(k) == fun && bot.getFamiliarManager().getFamiliar() == fun);
                        return false;
                    }
                    case 4: {
                        bot.getFamiliarManager().dismiss(); // the engine's end of the familiar; Bob is not told
                        exp0 = Bob.EXPIRED;
                        return false;
                    }
                    case 8: {
                        String k = Bob.key(bot);
                        check("f5 world: the engine ended it, Bob still tracks it", !bot.getFamiliarManager().hasFamiliar() && !fun.isActive() && Bob.OUT.get(k) == fun);
                        Bob.think(brain, System.currentTimeMillis());
                        check("f5 world: think() after the timer, fix5=" + Bob.FIX5 + ": " + (Bob.FIX5 ? "dropped, expired +1" : "stale entry stays (old path)"),
                                Bob.FIX5 ? !Bob.OUT.containsKey(k) && !Bob.FUN_KEYS.contains(k) && Bob.EXPIRED == exp0 + 1 : Bob.OUT.get(k) == fun && Bob.FUN_KEYS.contains(k) && Bob.EXPIRED == exp0);
                        check("f5 world: inUse is " + !Bob.FIX5, Bob.inUse(bot) == !Bob.FIX5);
                        Bob.sweep();
                        check("f5 world: sweep() leaves nothing tracked", Bob.OUT.isEmpty() && Bob.FUN_KEYS.isEmpty() && Bob.EXPIRED == exp0 + 1);
                        return done();
                    }
                    default: return false;
                }
            }
            void fillLogs() { while (bot.getInventory().freeSlots() > 0) bot.getInventory().add(new Item(LOGS)); }

            boolean off() {
                step++;
                if (step == 1) {
                    pure();
                    bot = mk();
                    Bob.seedBob(bot, SNAIL); // level 13, points, Wolf Whistle, 2 pouches: everything S4 would need
                    return false;
                }
                if (step == 3) {
                    r = runner();
                    Bob.plan(r);
                    check("OFF: plan takes no pouch", r.bobPouch == 0 && bot.getBank().getAmount(SNAIL) == 2 && bot.getInventory().getAmount(SNAIL) == 0);
                    r.bobPouch = SNAIL; // even forced, the hooks do nothing
                    check("OFF: summon refuses", !Bob.summon(r) && !bot.getFamiliarManager().hasFamiliar());
                    r.bobPouch = 0;
                    check("OFF: store/bankPass no-ops", Bob.store(r) == 0 && !Bob.bankPass(r));
                    if (!AfkTasks.launch(r, AfkTasks.State.TO_SPOT, SPOT)) log("note: runner did not launch: " + r.last);
                    return false;
                }
                if (step > 3 && step < 70) { if (bot.getFamiliarManager().hasFamiliar()) { check("OFF: no familiar during a real trip (tick " + step + ")", false); return done(); } return false; }
                if (step == 70) {
                    check("OFF: no familiar after 66 ticks of a willow trip", !bot.getFamiliarManager().hasFamiliar() && Bob.OUT.isEmpty());
                    check("OFF: pouches untouched", bot.getBank().getAmount(SNAIL) == 2);
                    try { r.halt(); } catch (Throwable ignored) { }
                    return fin();
                }
                return false;
            }

            boolean on() {
                step++;
                switch (step) {
                    case 1: {
                        pure();
                        bot = mk();
                        try { core.game.node.entity.player.link.quest.Quest q = bot.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE); if (q != null) bot.getQuestRepository().setStage(q, 100); } catch (Throwable ignored) { }
                        bot.getSkills().setStaticLevel(Skills.SUMMONING, 5); bot.getSkills().setLevel(Skills.SUMMONING, 5);
                        bot.getBank().add(new Item(SNAIL, 1));
                        return false;
                    }
                    case 3: {
                        r = runner(); Bob.plan(r);
                        check("ON below level (5): no pouch planned", r.bobPouch == 0 && bot.getBank().getAmount(SNAIL) == 1);
                        Bob.seedBob(bot, SNAIL); // forced Summoning 13, points 13, +2 pouches
                        r = runner(); Bob.plan(r);
                        check("ON level 13: plan takes one snail pouch", r.bobPouch == SNAIL && bot.getInventory().getAmount(SNAIL) == 1 && bot.getBank().getAmount(SNAIL) == 2);
                        check("ON: pouch is kept (never banked/sold)", r.keep(new Item(SNAIL)));
                        bot.getProperties().setTeleportLocation(SPOT);
                        return false;
                    }
                    case 6: {
                        boolean ok = Bob.summon(r);
                        Familiar f = bot.getFamiliarManager().getFamiliar();
                        check("ON: summoned a beast of burden at the spot", ok && f instanceof BurdenBeast && r.bob == f && Bob.OUT.size() == 1 && bot.getInventory().getAmount(SNAIL) == 0);
                        if (!ok) return done();
                        return false;
                    }
                    case 9: {
                        bot.getInventory().add(new Item(SHARK)); bot.getInventory().add(new Item(995, 500));
                        fillLogs();
                        int packLogs = bot.getInventory().getAmount(LOGS);
                        logs0 = logsHeld();
                        int moved = Bob.store(r);
                        BurdenBeast b = r.bob;
                        check("ON: products moved into the beast (" + moved + ")", moved == 3 && b.getContainer().getAmount(LOGS) == 3 && bot.getInventory().getAmount(LOGS) == packLogs - 3);
                        check("ON: tools, food, coins stay in the pack", bot.getInventory().getAmount(AXE) == 1 && bot.getInventory().getAmount(SHARK) == 1 && bot.getInventory().getAmount(995) == 500);
                        check("ON: store again with a full beast moves 0", Bob.store(r) == 0);
                        bot.getProperties().setTeleportLocation(BANK); // the familiar is re-called to its owner by the server (>12 tiles)
                        r.state = AfkTasks.State.BANKING;
                        return false;
                    }
                    case 13: case 14: case 15: case 16: case 17: case 18: {
                        r.deposit(); // pause, pass 1 (pack), Withdraw-All, pass 2 (the beast's load)
                        if (step == 18) {
                            check("ON: beast emptied at the bank", r.bob != null && r.bob.getContainer().itemCount() == 0);
                            check("ON: no logs left in the pack", bot.getInventory().getAmount(LOGS) == 0);
                            check("ON: bob trip counted", Bob.BOB_TRIPS >= 1 && Bob.ITEMS_CARRIED >= 3);
                            check("ON: still out after the bank (timer > 6 min)", bot.getFamiliarManager().hasFamiliar());
                        }
                        return false;
                    }
                    case 20: {
                        fillLogs(); Bob.store(r);
                        logs0 = logsHeld();
                        check("ON: loaded again before the teleport", r.bob != null && r.bob.getContainer().itemCount() == 3);
                        Spectacle.teleport(bot, Location.create(3222, 3218, 0));
                        check("ON: dismissed before the teleport", !bot.getFamiliarManager().hasFamiliar() && Bob.OUT.isEmpty());
                        check("ON: nothing spilt (logs " + logs0 + " -> " + logsHeld() + ")", logsHeld() == logs0 && Bob.SPILLS == 0);
                        Bob.guard(r);
                        check("ON: guard drops the runner's beast after leave, nothing lost", r.bob == null && Bob.LOST == 0); // r is not in RUNNING here (never launched), so leave's clearRunner cannot reach it: guard does
                        Bob.leave(bot, "again"); Bob.leave(null, "x"); // safe twice, safe with nothing
                        return false;
                    }
                    case 25: {
                        check("ON: no familiar NPC left", !bot.getFamiliarManager().hasFamiliar() && Bob.OUT.isEmpty());
                        java.util.List<int[]> prof = new java.util.ArrayList<>(); Bob.addCarried(bot, prof);
                        check("ON: profile carries nothing once dismissed", prof.isEmpty());
                        return fin();
                    }
                    default: return false;
                }
            }
        });
    }
}
