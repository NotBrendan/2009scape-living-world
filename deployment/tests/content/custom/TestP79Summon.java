package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only, never shipped (P79, summoning v2 S1-S3). -Dtest.p79unit=true: Summon.MOOD follows -Dbots.summoning.mood (and
 * needs bots.summoning), spend rule B numbers, desire score bounds, interest range, cooldown, and flag off = the old gate.
 * Expected: -Dtest.p79unit.want=on|off.
 */
public final class TestP79Summon implements StartupListener {
    static final String T = "[TestP79Summon] ";
    static int pass, fail;

    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println(T + (ok ? "PASS " : "FAIL ") + name); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p79unit")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { runChecks(); } catch (Throwable x) { System.out.println(T + "FAIL setup " + x); x.printStackTrace(System.out); fail++; }
                System.out.println(T + "RESULT " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail) + " summoning=" + System.getProperty("bots.summoning") + " mood=" + System.getProperty("bots.summoning.mood"));
                System.out.println(T + "DONE");
                return true;
            }
        });
    }

    static java.util.function.IntUnaryOperator map(int... kv) {
        return id -> { for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i] == id) return kv[i + 1]; return 0; };
    }

    static void runChecks() {
        boolean want = "on".equals(System.getProperty("test.p79unit.want", "off"));
        check("MOOD matches the flags (" + Summon.MOOD + ", ON " + Summon.ON + ")", Summon.MOOD == want && (!Summon.MOOD || Summon.ON));
        // S2 spend rule
        check("rule A: 10% (2,000 -> 200, 10,000 -> 1,000, -5 -> 0)", Summon.budget(2_000, false) == 200 && Summon.budget(10_000, false) == 1_000 && Summon.budget(-5, false) == 0);
        check("rule B: 2,000 -> 800 (40%)", Summon.budget(2_000, true) == 800);
        check("rule B: 500 -> 200, 0 -> 0", Summon.budget(500, true) == 200 && Summon.budget(0, true) == 0);
        check("rule B: 5,000 -> 1,500 (cap)", Summon.budget(5_000, true) == 1_500);
        check("rule B: 20,000 -> 2,000 (10% beats the cap)", Summon.budget(20_000, true) == 2_000);
        check("rule B: 100,000 -> 10,000", Summon.budget(100_000, true) == 10_000);
        java.util.function.IntUnaryOperator none = id -> 0, val = id -> 50;
        int[] c = Summon.choose(1, 2_000, 28, map(Summon.GOLD, 30), map(2859, 30), none, val);
        if (want) check("choose under mood: 2,000 gp -> 4 spirit wolf pouches (800 gp, 176 each)", c != null && c[1] == 4);
        else check("choose flag off: 2,000 gp -> no errand (200 gp < 3 x 176)", c == null);
        c = Summon.choose(1, 10_000, 28, map(Summon.GOLD, 30), map(2859, 30), none, val);
        check("choose 10,000 gp -> 5 or 8 pouches (" + (c == null ? "null" : c[1]) + ")", c != null && c[1] == (want ? 8 : 5));
        c = Summon.choose(1, 1_000, 28, map(Summon.GOLD, 30), map(2859, 30), none, val);
        if (want) check("option B: 1,000 gp (budget 400) -> 2 spirit wolf pouches", c != null && c[1] == 2);
        else check("flag off: 1,000 gp -> no errand (MIN_N 3)", c == null);
        check("affordable: 1,000 gp own bones yes; 800 gp no (320 < 352); 2,000 gp buying bones at 50 yes; level 0 / 2 charms no",
                Summon.affordable(1, 1_000, map(Summon.GOLD, 30), map(2859, 30), val) && !Summon.affordable(1, 800, map(Summon.GOLD, 30), map(2859, 30), val)
                && Summon.affordable(1, 2_000, map(Summon.GOLD, 30), none, val) && !Summon.affordable(0, 100_000, map(Summon.GOLD, 30), none, val)
                && !Summon.affordable(40, 100_000, map(Summon.GOLD, 2), none, val));
        // S1 desire
        double lo = 1, hi = 0; java.util.Random r = new java.util.Random(79);
        boolean inRange = true;
        for (int i = 0; i < 20_000; i++) {
            double s = Summon.desire(r.nextDouble() * 1.2, r.nextInt(400) - 10, 1 + r.nextInt(99), 3 + r.nextInt(135), (long) (r.nextDouble() * 10 * 3_600_000) - 1000, r.nextInt(4) == 0);
            inRange &= s >= 0 && s <= 0.95; lo = Math.min(lo, s); hi = Math.max(hi, s);
        }
        check(String.format("desire in [0, 0.95] over 20,000 draws (%.3f..%.3f)", lo, hi), inRange);
        double top = Summon.desire(1.0, 300, 1, 126, 10 * 3_600_000L, false), bare = Summon.desire(0.2, 0, 50, 40, 0, false);
        check(String.format("desire max case %.3f = 0.95 cap, bare low-interest case %.3f = 0.03", top, bare), Math.abs(top - 0.95) < 1e-9 && Math.abs(bare - 0.03) < 1e-9);
        check("desire rises with charms, level lag and session", Summon.desire(0.6, 80, 5, 40, 0, false) > Summon.desire(0.6, 0, 5, 40, 0, false)
                && Summon.desire(0.6, 0, 5, 45, 0, false) > Summon.desire(0.6, 0, 40, 45, 0, false)
                && Summon.desire(0.6, 0, 5, 5, 3 * 3_600_000L, false) > Summon.desire(0.6, 0, 5, 5, 0, false));
        double cool = Summon.desire(1.0, 300, 1, 126, 10 * 3_600_000L, true);
        check(String.format("cooldown: near zero (%.4f <= 0.03)", cool), cool <= 0.03 + 1e-9);
        check("cooldown 1-3 h (fast 2-6 min)", Summon.coolMs(false, 0) == 3_600_000L && Summon.coolMs(false, 1) == 10_800_000L && Summon.coolMs(false, 0.5) == 7_200_000L
                && Summon.coolMs(true, 0) == 120_000L && Summon.coolMs(true, 1) == 360_000L && Summon.coolMs(false, 7) == 10_800_000L);
        check("roll gap 15 min, noPick recheck 30 min", Summon.rollGapMs(false) == 900_000L && Summon.noPickGapMs(false) == 1_800_000L);
        double imin = 1, imax = 0, sum = 0; boolean det = true;
        for (int i = 0; i < 2_000; i++) { double v = Summon.interest("bot" + i); imin = Math.min(imin, v); imax = Math.max(imax, v); sum += v; det &= v == Summon.interest("BOT" + i); }
        check(String.format("interest in [0.2,1.0], spread (%.3f..%.3f mean %.3f), fixed per name", imin, imax, sum / 2000), imin >= 0.2 && imax <= 1.0 && imax - imin > 0.7 && Math.abs(sum / 2000 - 0.6) < 0.05 && det);
        // flag off = the old gate, untouched (source)
        try {
            String dir = System.getProperty("test.p69.src", "deployment/addons-src/content/custom");
            String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, "SmartAdventurers.java")), java.nio.charset.StandardCharsets.UTF_8);
            String qb = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, "QuestBook.java")), java.nio.charset.StandardCharsets.UTF_8);
            boolean gate = src.contains("(Summon.MOOD ? !wantsSummon(now) : (now < summonNext || RNG.nextInt(3) != 0))")
                    && src.contains("summonNext = now + (Summon.MOOD ? Summon.noPickGapMs(FAST) : FAST ? 120_000 : 60 * 60_000);")
                    && src.contains("if (Summon.MOOD) { summonBuy = bought;") && src.contains("if (!Summon.affordable(");
            boolean s3 = qb.contains("if (Summon.MOOD && s.quest == content.data.Quests.WOLF_WHISTLE) w *= wolfWhistleBoost(bot);") && qb.contains("? 3.0 : 1.0");
            check("source: old gate kept behind MOOD " + gate + ", S3 weight behind MOOD " + s3, gate && s3);
        } catch (java.io.IOException x) { check("source readable (-Dtest.p69.src): " + x, false); }
        if (!want) check("flag off: tally has no desire fields", !Summon.tallyLine().contains("desire"));
        else check("mood on: tally carries desireStarts and band", Summon.tallyLine().contains("desireStarts=") && Summon.tallyLine().contains("band="));
    }
}
