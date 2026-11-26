package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only, never shipped (P62). -Dtest.p62unit=true: the Summoning training pick, budget, fleet cap and charm rule, and the
 * flag follows -Dbots.summoning exactly (unset / =false: ON false, no brain has an errand, planSummonRun false for all;
 * =true: ON true). Expected: -Dtest.p62unit.want=on|off.
 */
public final class TestP62Summon implements StartupListener {
    static final String T = "[TestP62Summon] ";
    static int pass, fail;

    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println(T + (ok ? "PASS " : "FAIL ") + name); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p62unit")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100 || t < 900 && SmartAdventurers.BRAINS.size() < 4) return false; // bots log in spread out (-Dbots.bootSpread)
                try { runChecks(); } catch (Throwable x) { System.out.println(T + "FAIL setup " + x); x.printStackTrace(System.out); fail++; }
                System.out.println(T + "RESULT " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail) + " flag=" + System.getProperty("bots.summoning"));
                System.out.println(T + "DONE");
                return true;
            }
        });
    }

    static java.util.function.IntUnaryOperator map(int... kv) {
        return id -> { for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i] == id) return kv[i + 1]; return 0; };
    }

    static void runChecks() {
        boolean want = "on".equals(System.getProperty("test.p62unit.want", "off"));
        check("ON matches the flag (" + Summon.ON + ")", Summon.ON == want);
        check("fleet cap default 20 (" + Summon.MAX + ")", Summon.MAX == Integer.getInteger("bots.summoning.max", 20));
        java.util.function.IntUnaryOperator none = id -> 0, val = id -> 50;
        // pick
        int[] c = Summon.choose(4, 100_000, 28, map(Summon.GOLD, 30), map(2859, 30, 2138, 30), none, val);
        check("level 4, wolf bones + raw chicken banked: dreadfowl x12 from the bank", c != null && Summon.POUCHES[c[0]][0] == 12043 && c[1] == 12 && c[2] == 0);
        c = Summon.choose(3, 100_000, 28, map(Summon.GOLD, 30), map(2859, 30, 2138, 30), none, val);
        check("level 3: spirit wolf (dreadfowl needs 4)", c != null && Summon.POUCHES[c[0]][0] == 12047);
        c = Summon.choose(20, 100_000, 28, map(Summon.GOLD, 5, Summon.GREEN, 9), map(1783, 2, 440, 9), none, val);
        check("level 20: green charms but 2 sand -> granite crab x5 (gold charms 5)", c != null && Summon.POUCHES[c[0]][0] == 12009 && c[1] == 5);
        check("no charms: no errand", Summon.choose(40, 100_000, 28, none, map(440, 30), none, val) == null);
        check("2 charms (< 3): no errand", Summon.choose(40, 100_000, 28, map(Summon.GOLD, 2), map(440, 30), none, val) == null);
        check("no secondary and nothing buyable: no errand", Summon.choose(40, 100_000, 28, map(Summon.GOLD, 30), none, none, val) == null);
        c = Summon.choose(16, 100_000, 28, map(Summon.GOLD, 30), none, map(440, 500), val);
        check("at the Exchange: buys the iron ore (12, all bought)", c != null && Summon.POUCHES[c[0]][0] == 12009 && c[1] == 12 && c[2] == 12);
        check("Ledger stock under 20: not bought", Summon.choose(16, 100_000, 28, map(Summon.GOLD, 30), none, map(440, 19), val) == null);
        c = Summon.choose(1, 10_000, 28, map(Summon.GOLD, 30), map(2859, 30), none, val);
        check("budget 10% of 10,000 = 1000 gp -> 5 spirit wolf pouches (176 gp each)", c != null && c[1] == 5 && Summon.cost(7L * 25 + 1, 50, c[1], 30) <= 1000);
        check("purse 1000: under 3 pouches -> no errand", Summon.choose(1, 1_000, 28, map(Summon.GOLD, 30), map(2859, 30), none, val) == null);
        c = Summon.choose(1, 100_000, 10, map(Summon.GOLD, 30), map(2859, 30), none, val);
        check("10 free slots -> 4 pouches (2 + 2n)", c != null && c[1] == 4);
        check("7 free slots: no errand", Summon.choose(1, 100_000, 7, map(Summon.GOLD, 30), map(2859, 30), none, val) == null);
        check("shop bill spirit wolf x10 = 1760 gp", Summon.shopBill(0, 10) == 1760);
        // every row is a real pouch with that recipe
        boolean rows = true;
        for (int[] r : Summon.POUCHES) {
            content.global.skill.summoning.SummoningPouch sp = content.global.skill.summoning.SummoningPouch.get(r[0]);
            if (sp == null || sp.getLevelRequired() != r[1]) { rows = false; System.out.println(T + "  row " + r[0] + " level mismatch"); continue; }
            boolean charm = false, sec = false, sh = false, em = false;
            for (core.game.node.item.Item it : sp.getItems()) { if (it.getId() == r[2]) charm = true; if (it.getId() == r[3]) sec = true; if (it.getId() == Summon.SHARDS && it.getAmount() == r[4]) sh = true; if (it.getId() == Summon.EMPTY) em = true; }
            if (!(charm && sec && sh && em)) { rows = false; System.out.println(T + "  row " + r[0] + " recipe mismatch"); }
        }
        check("POUCHES rows match SummoningPouch (level, charm, secondary, shards, pouch)", rows);
        // charm rule
        check("keepCharm: flag off -> false", !Summon.keepCharm(false, Summon.GOLD, true, true));
        check("keepCharm: human -> false", !Summon.keepCharm(true, Summon.GOLD, false, true));
        check("keepCharm: no Wolf Whistle -> false", !Summon.keepCharm(true, Summon.GOLD, true, false));
        check("keepCharm: bot, quest done, charm -> true", Summon.keepCharm(true, Summon.BLUE, true, true) && !Summon.keepCharm(true, 995, true, true));
        // fleet cap
        Summon.ACTIVE.clear();
        boolean capOk = true;
        for (int i = 0; i < Summon.MAX; i++) capOk &= Summon.seat("capbot" + i);
        capOk &= !Summon.seat("capbot-extra") && Summon.ACTIVE.size() == Summon.MAX;
        Summon.free("capbot0"); Summon.free("capbot0");
        capOk &= Summon.ACTIVE.size() == Summon.MAX - 1 && Summon.seat("capbot-extra");
        Summon.ACTIVE.clear();
        check("fleet cap: MAX seats, a double free counts once", capOk);
        // P69 #13: a full fleet with leaked seats (no errand behind them) is swept when the next bot asks; held seats stay
        for (int i = 0; i < Summon.MAX; i++) Summon.seat("leak" + i);
        java.util.function.Predicate<String> holds = k -> k.equals("leak0") || k.equals("leak1");
        boolean sw = !Summon.seat("leaknew") && Summon.seat("leaknew", holds) && Summon.ACTIVE.size() == 3
                && Summon.ACTIVE.contains("leak0") && Summon.ACTIVE.contains("leak1") && Summon.ACTIVE.contains("leaknew");
        Summon.ACTIVE.clear();
        for (int i = 0; i < 5; i++) Summon.seat("few" + i);
        sw &= Summon.seat("few5", k -> false) && Summon.ACTIVE.size() == 6; // not full: no sweep
        Summon.ACTIVE.clear();
        check("P69 #13 sweep: full fleet of leaked seats frees all but the 2 held, not run below the cap", sw);
        // P69 #9 / #12 / #13 wiring (source; the errand itself is TestP62Trial)
        try {
            String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("test.p69.src", "deployment/addons-src/content/custom"), "SmartAdventurers.java")), java.nio.charset.StandardCharsets.UTF_8);
            boolean b9 = src.contains("summonPouch0 = bot.getInventory().getAmount(r[0]);") && src.split("Math.max\\(0, bot.getInventory\\(\\).getAmount\\(r\\[0\\]\\) - summonPouch0\\)", -1).length == 3;
            boolean b12 = src.contains("boolean dead = \"death\".equals(why);") && src.contains("if (!dead && Summon.inCellar(bot.getLocation()))") && src.contains("if (!dead) for (int id : ids) putBack(");
            boolean b13 = src.contains("Summon.seat(bot.getUsername(), k -> { Brain b = BRAINS.get(k); return b != null && b.summon != 0; })");
            check("P69 source: #9 pouch baseline (plan + infuse + end) " + b9 + ", #12 death skips cellar teleport + bank " + b12 + ", #13 seat sweeps by errand " + b13, b9 && b12 && b13);
        } catch (java.io.IOException x) { check("P69 source readable (-Dtest.p69.src): " + x, false); }
        // flag off: nothing starts
        int brains = 0, busy = 0, planned = 0;
        long now = System.currentTimeMillis();
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
            if (b.bot == null) continue;
            brains++;
            if (b.summon != 0) busy++;
            if (!want && b.planSummonRun(now)) planned++;
        }
        check("brains (" + brains + "): none on a summoning errand at boot", brains > 0 && busy == 0);
        if (!want) check("flag off: planSummonRun false for every brain (" + planned + ")", planned == 0 && Summon.RUNS == 0);
        if (!want) check("flag off: tally untouched", Summon.tallyLine().replaceAll("[a-zA-Z]+=0", "").trim().isEmpty());
    }
}
