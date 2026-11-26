package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * W8 lane I: SeedFire's pure rules, plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestSeedFire <addons-src>/content/custom
 * Run it twice: bare (flag off -> no-op) and with -Dsmartbots.seed.fire=fish.shark and NO harness markers (-> refused, still off).
 */
public final class TestSeedFire {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestSeedFire] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }

    /** A fake bot. */
    static final class Fake implements SeedFire.Target {
        final Map<Integer, Integer> lv = new HashMap<>(), bk = new HashMap<>(); long coins; final List<String> quests = new ArrayList<>(); int[] at;
        public int level(int s) { return lv.getOrDefault(s, 1); }
        public void raise(int s, int l) { lv.put(s, l); }
        public int bank(int id) { return bk.getOrDefault(id, 0); }
        public void bankAdd(int id, int n) { bk.merge(id, n, Integer::sum); }
        public long coins() { return coins; }
        public void setCoins(long n) { coins = n; }
        public void addCoins(long n) { coins += n; }
        public void quest(String q) { quests.add(q); }
        public void at(int x, int y, int z) { at = new int[]{x, y, z}; }
    }

    public static void main(String[] args) throws Exception {
        String raw = System.getProperty(SeedFire.FLAG);
        boolean asked = raw != null && !raw.trim().isEmpty();
        boolean on = asked && Seed.ALLOWED;
        if (!on) {
            // Off / live: no flag, or no harness markers (live): ON false, nothing parsed, share 0.
            check("O this jvm: asked " + SeedFire.ASKED + " (expected " + asked + "), on " + SeedFire.ON + ", active " + SeedFire.ACTIVE.length + ", share " + SeedFire.SHARE,
                    SeedFire.ASKED == asked && !SeedFire.ON && SeedFire.ACTIVE.length == 0 && SeedFire.SHARE == 0 && SeedFire.UNKNOWN.isEmpty() && SeedFire.seeded == 0);
            SeedFire.apply(null); SeedFire.task(null, null);
            check("O apply/task with the flag off or refused: no-op", SeedFire.seeded == 0 && SeedFire.DONE.isEmpty() && SeedFire.TASKED.isEmpty());
        } else {
            // On (harness markers given): the list is parsed once, unknown names kept for the single warning line.
            List<String> u = new ArrayList<>();
            int known = SeedFire.parse(raw, u).length;
            check("N on: active " + SeedFire.ACTIVE.length + " = " + known + ", unknown " + SeedFire.UNKNOWN + ", share " + SeedFire.SHARE,
                    SeedFire.ON && SeedFire.ACTIVE.length == known && SeedFire.UNKNOWN.equals(u) && SeedFire.SHARE == SeedFire.share(System.getProperty(SeedFire.SHARE_FLAG)));
            SeedFire.apply(null);
            check("N apply(null) still safe", SeedFire.seeded == 0);
        }

        // Parse: full or short names, any case, duplicates once, unknown names collected for the one warning line.
        List<String> unk = new ArrayList<>();
        SeedFire.Recipe[] r = SeedFire.parse(" bots.fish.shark, FIRE.LINE ,fish.shark,,nope.zzz,summoning.fix5, bogus ", unk);
        check("P parse: 3 known in order, dupes once, 2 unknown -> " + r.length + " " + unk,
                r.length == 3 && r[0].name.equals("bots.fish.shark") && r[1].name.equals("bots.fire.line") && r[2].name.equals("bots.summoning.fix5")
                        && unk.size() == 2 && unk.get(0).equals("nope.zzz") && unk.get(1).equals("bogus"));
        check("P parse: null/blank empty, unknown-only gives no recipe", SeedFire.parse(null, unk).length == 0 && SeedFire.parse(" , ", null).length == 0
                && SeedFire.parse("x.y", null).length == 0 && SeedFire.find(null) == null);

        check("S share: default 20, clamp 0..100, junk 20", SeedFire.share(null) == 20 && SeedFire.share(" ") == 20 && SeedFire.share("35") == 35
                && SeedFire.share("-5") == 0 && SeedFire.share("150") == 100 && SeedFire.share("x") == 20);

        // Pick: stable, no RNG, about share% of names, 0 = none, 100 = all, different switches pick different bots.
        int n = 2000, a = 0, b = 0, both = 0; boolean stable = true;
        for (int i = 0; i < n; i++) {
            String bot = "Bot" + i * 7919;
            boolean x = SeedFire.picked(bot, "bots.fish.shark", 20), y = SeedFire.picked(bot, "bots.fire.line", 20);
            stable &= x == SeedFire.picked(bot.toUpperCase(), "bots.fish.shark", 20);
            if (x) a++; if (y) b++; if (x && y) both++;
        }
        check("K pick: 20% share ~ " + a + "/" + n + " and " + b + "/" + n + ", overlap " + both + " (not the same bots), case-stable " + stable,
                stable && a > n * 0.15 && a < n * 0.25 && b > n * 0.15 && b < n * 0.25 && both < a / 2);
        check("K pick: 0 none, 100 all, null none", !SeedFire.picked("Abc", "s", 0) && SeedFire.picked("Abc", "s", 100) && !SeedFire.picked(null, "s", 50));

        // Give: the picked bot gets the state; levels never lowered; items/coins topped up; broke recipes set coins.
        Fake f = new Fake(); f.raise(core.game.node.entity.skill.Skills.FISHING, 80); f.bankAdd(311, 3);
        SeedFire.give(f, SeedFire.find("fish.shark"));
        check("G shark: Fishing 80 kept (not lowered), harpoon 3 kept", f.level(core.game.node.entity.skill.Skills.FISHING) == 80 && f.bank(311) == 3);
        Fake g = new Fake(); g.coins = 1200;
        String line = SeedFire.give(g, SeedFire.find("bots.fire.line"));
        check("G fire.line: Firemaking 45, tinderbox, coins 5000, start tile -> " + line.trim(), g.level(core.game.node.entity.skill.Skills.FIREMAKING) == 45
                && g.bank(590) == 1 && g.coins == 5_000 && g.at != null && g.at[0] == 3185);
        Fake h = new Fake();
        SeedFire.give(h, SeedFire.find("summoning.fix5"));
        check("G fix5: Wolf Whistle, Summoning 4, 2 wolf + 2 dreadfowl", h.quests.contains("WOLF_WHISTLE") && h.level(core.game.node.entity.skill.Skills.SUMMONING) == 4
                && h.bank(12047) == 2 && h.bank(12043) == 2);
        Fake k = new Fake(); k.coins = 90_000;
        SeedFire.give(k, SeedFire.find("brokeJob.zeroSkip"));
        Fake m = new Fake(); m.coins = 90_000;
        SeedFire.give(m, SeedFire.find("slayer.brokeTrip"));
        check("G broke recipes set coins: zeroSkip 0, brokeTrip 40", k.coins == 0 && m.coins == 40);
        Fake v = new Fake(); SeedFire.give(v, SeedFire.find("slayer.vannakaHunts"));
        check("G vannakaHunts: combat stats 85, Prayer 43, Priest in Peril, task VAMPIRES:10", v.level(core.game.node.entity.skill.Skills.ATTACK) == 85
                && v.level(core.game.node.entity.skill.Skills.PRAYER) == 43 && v.quests.contains("PRIEST_IN_PERIL") && "VAMPIRES:10".equals(SeedFire.find("slayer.vannakaHunts").task));

        // Source: every recipe names a real switch; the hooks start with the ON guard; the enlist calls are in place.
        if (args.length > 0) {
            String dir = args[0];
            StringBuilder all = new StringBuilder();
            try (java.util.stream.Stream<java.nio.file.Path> s = Files.list(Paths.get(dir))) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) s::iterator)
                    if (p.toString().endsWith(".java") && !p.getFileName().toString().equals("SeedFire.java")) all.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
            SeedFire.Recipe c2 = SeedFire.find("summoning.combat2"); // w20 NOFIRE fix
            check("R summoning.combat2: Wolf Whistle, a combat pouch with a special and SCROLL_MIN+ of its scrolls", c2 != null && c2 == SeedFire.find("bots.summoning.combat2") && c2.quests.length == 1
                    && c2.bank.length == 2 && Bob.specOf(c2.bank[0][0]) != null && Bob.specOf(c2.bank[0][0])[1] == c2.bank[1][0] && c2.bank[1][1] >= Bob.SCROLL_MIN && c2.coinsSet < 0);
            List<String> missing = new ArrayList<>();
            for (SeedFire.Recipe x : SeedFire.RECIPES) if (all.indexOf("\"" + x.name + "\"") < 0) missing.add(x.name);
            check("R every recipe is a real -D switch in addons-src " + missing, missing.isEmpty());
            String sf = new String(Files.readAllBytes(Paths.get(dir, "SeedFire.java")), StandardCharsets.UTF_8);
            check("R apply/task start with the ON guard; no Random in SeedFire", sf.contains("static void apply(Player p) {\r\n        if (!ON ") && sf.contains("Player p) {\r\n        if (!ON || b == null")
                    && !sf.contains("Random") && !sf.contains("RNG."));
            String sa = new String(Files.readAllBytes(Paths.get(dir, "SmartAdventurers.java")), StandardCharsets.UTF_8);
            check("R enlist hooks: SeedFire.apply after Seed.apply, SeedFire.task after Seed.task",
                    sa.indexOf("SeedFire.apply(bot);") > sa.indexOf("Seed.apply(bot);") && sa.indexOf("SeedFire.task(brain, bot);") > sa.indexOf("Seed.task(brain, bot);"));
        }
        System.out.println("[TestSeedFire] " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
