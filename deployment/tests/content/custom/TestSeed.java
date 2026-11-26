package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * W0 lane A0: Seed's pure helpers and wiring, plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestSeed <addons-src>/content/custom
 * Run it twice: bare (flags off -> Seed.ON false) and with -Dsmartbots.seed.coins=5 and NO harness markers (-> "[Seed] refused (live)").
 */
public final class TestSeed {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestSeed] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }

    public static void main(String[] args) throws Exception {
        boolean asked = Seed.asked(k -> System.getProperty(Seed.P + k));
        check("D this jvm (no harness markers): asked " + Seed.ASKED + ", allowed " + Seed.ALLOWED + ", on " + Seed.ON + ", coins " + Seed.COINS + ", levels 0, no items, no split",
                Seed.ASKED == asked && !Seed.ALLOWED && !Seed.ON && Seed.COINS == -1 && Seed.SUMMONING == 0 && Seed.FARMING == 0 && Seed.SLAYER == 0
                        && Seed.POUCHES.length == 0 && Seed.SEEDS.length == 0 && Seed.SPLIT.isEmpty() && !Seed.off(null, "combat"));

        check("G guard: needs tickprobe + farm port + 127.0.0.1; any one missing refuses",
                Seed.allowed(true, "43601", "127.0.0.1") && !Seed.allowed(false, "43601", "127.0.0.1") && !Seed.allowed(true, null, "127.0.0.1")
                        && !Seed.allowed(true, " ", "127.0.0.1") && !Seed.allowed(true, "43601", null) && !Seed.allowed(true, "43601", "0.0.0.0"));

        Map<String, String> m = new HashMap<>();
        boolean none = !Seed.asked(m::get); m.put("farming", "  "); boolean blank = !Seed.asked(m::get); m.put("coins", "0"); boolean zero = Seed.asked(m::get);
        check("A asked: none " + none + ", blank value " + blank + ", coins=0 counts " + zero, none && blank && zero);

        int[][] p = Seed.pairs("12087:2, 12007:3,12047");
        boolean good = p.length == 3 && p[0][0] == 12087 && p[0][1] == 2 && p[1][0] == 12007 && p[1][1] == 3 && p[2][0] == 12047 && p[2][1] == 1;
        boolean bad = Seed.pairs(null).length == 0 && Seed.pairs("").length == 0 && Seed.pairs("x:1,5:-2,0:4,7:y,1:2:3,99999:1").length == 0 && Seed.pairs("abc,5318:10").length == 1;
        check("P pairs: id:n list " + good + ", junk dropped " + bad, good && bad);

        check("L level: clamp 1..99, junk 0", Seed.level("13") == 13 && Seed.level("150") == 99 && Seed.level("0") == 0 && Seed.level("-4") == 0 && Seed.level("x") == 0 && Seed.level(null) == 0);

        boolean h = !Seed.offHalf("combat", "combat", 0) && Seed.offHalf("combat", "combat", 1) && !Seed.offHalf("combat", "combat", 2) && !Seed.offHalf("combat", "bob", 1)
                && !Seed.offHalf("", "combat", 1) && !Seed.offHalf(null, "combat", 1) && !Seed.offHalf("combat", "combat", -1);
        int on = 0, off = 0; for (int i = 0; i < 24; i++) { if (Seed.offHalf("combat", "combat", i)) off++; else on++; }
        boolean n = "ON".equals(Seed.halfName("combat", 4)) && "OFF".equals(Seed.halfName("combat", 5)) && "-".equals(Seed.halfName("", 5)) && "-".equals(Seed.halfName("combat", -1));
        check("H split: even ON, odd OFF, only the named feature, 24 bots -> " + on + "/" + off + ", names " + n, h && on == 12 && off == 12 && n);

        int[] t = Seed.tile("3092, 3245,0"), t2 = Seed.tile("3013,3355");
        check("T tile: x,y,z and x,y; junk null; this jvm has none", t != null && t[0] == 3092 && t[1] == 3245 && t[2] == 0 && t2 != null && t2[2] == 0 && Seed.tile(null) == null
                && Seed.tile("1") == null && Seed.tile("a,b") == null && Seed.tile("1,2,9") == null && Seed.tile("1,2,3,4") == null && Seed.AT == null && !Seed.dead(null));

        String[] hs = Seed.names(" Cow, Chicken ,,Goblin");
        check("N hunt: list of 3, pairs share a spot (0,1 -> Cow; 2,3 -> Chicken; 6,7 -> Cow), none -> null; word() joins a spaced name",
                hs.length == 3 && "Cow".equals(Seed.huntAt(hs, 0)) && "Cow".equals(Seed.huntAt(hs, 1)) && "Chicken".equals(Seed.huntAt(hs, 2)) && "Chicken".equals(Seed.huntAt(hs, 3))
                        && "Cow".equals(Seed.huntAt(hs, 7)) && Seed.huntAt(new String[0], 3) == null && Seed.huntAt(hs, -1) == null && Seed.names(null).length == 0
                        && "Old_Roy".equals(Seed.word("Old Roy")) && Seed.HUNTS.length == 0 && Seed.hunt(null, false) == null
                        && Seed.huntApplies(false, true) && Seed.huntApplies(true, false) && !Seed.huntApplies(true, true));

        boolean colon = Seed.pairs(":").length == 0 && Seed.pairs("::,:,5318:3,:4, : ").length == 1 && Seed.pairs(",").length == 0 && Seed.pairs("5318:").length == 1;
        int[] fl = Seed.fill("1511:5");
        check("F review fix 1: ':' and other empty entries are skipped, never thrown " + colon + "; fill id:free parses, junk null; grow/fill off here",
                colon && fl != null && fl[0] == 1511 && fl[1] == 5 && Seed.fill(":") == null && Seed.fill("1511") == null && Seed.fill("1511:28") == null && Seed.fill(null) == null
                        && Seed.FILL == null && !Seed.GROW && Seed.grown(null, 0, 77L, 5L) == 77L);

        String dir = args.length > 0 ? args[0] : "deployment/addons-src/content/custom";
        String sa = read(dir + "/SmartAdventurers.java"), bob = read(dir + "/Bob.java"), afk = read(dir + "/AfkTasks.java"), seed = read(dir + "/Seed.java");
        int en = sa.indexOf("static void enlist("), hook = sa.indexOf("Seed.apply(bot);", en), brain = sa.indexOf("Brain brain = new Brain();", en);
        boolean wf = sa.contains("farmDue = Seed.grown(bot, farmSite, farmDueFrom(now), now);") && sa.contains("{ Seed.fillPack(bot, farmDue != 0); farm = FARM_WORK;");
        boolean w1 = wf && en > 0 && hook > en && hook < brain && sa.indexOf("String sh = Seed.hunt(bot, slaySt.had != null);") > sa.indexOf("Job chooseJob(boolean crewFilter) {");
        int cp = bob.indexOf("static void combatPlan1("), offAt = bob.indexOf("if (Seed.off(p, \"combat\")) return;", cp), seedAt = bob.indexOf("seedCombat(p, TEST_COMBAT)", cp), elig = bob.indexOf("combatEligible(r)", cp);
        boolean w2 = cp > 0 && seedAt > cp && offAt > seedAt && offAt < elig; // both halves get the same pouches; only the ON half may plan one
        boolean w3 = bob.contains("BOB_PLANS++; Seed.note(p, \"beast plan pouch=\"") && bob.contains("C_PLANS++; Seed.note(p, \"combat plan pouch=\"") && afk.contains("Seed.trip(p, kills, startedAt, label());") && afk.contains("kills++; Seed.attack(p, n);");
        boolean w4 = seed.contains("if (!ON || p == null || !p.isArtificial()) return;") && seed.contains("static final boolean ON = ASKED && ALLOWED;")
                && seed.contains("if (ASKED && !ALLOWED) System.out.println(\"[Seed] refused (live)\");");
        check("W wiring: enlist hook before the brain " + w1 + ", OFF half stops combatPlan1 after the seed " + w2 + ", scenario lines " + w3 + ", every entry gated on ON " + w4, w1 && w2 && w3 && w4);

        int[][] sl = Seed.skillLevels(FakeSkills.class, " runecrafting:44, Strength:70 ,ATTACK:150,bogus:5,num_skills:9,name:3,magic,:7,magic:x,,:");
        boolean slOk = sl.length == 3 && sl[0][0] == 20 && sl[0][1] == 44 && sl[1][0] == 2 && sl[1][1] == 70 && sl[2][0] == 0 && sl[2][1] == 99;
        boolean idOk = Seed.skillId(FakeSkills.class, "rUnEcRaFtInG") == 20 && Seed.skillId(FakeSkills.class, "bogus") == -1 && Seed.skillId(FakeSkills.class, "NUM_SKILLS") == -1
                && Seed.skillId(FakeSkills.class, "NAME") == -1 && Seed.skillId(FakeSkills.class, null) == -1;
        boolean real = Seed.skillId(core.game.node.entity.skill.Skills.class, "runecrafting") == core.game.node.entity.skill.Skills.RUNECRAFTING
                && Seed.skillId(core.game.node.entity.skill.Skills.class, "attack") == core.game.node.entity.skill.Skills.ATTACK;
        boolean wired = java.util.Arrays.asList(Seed.KEYS).contains("levels") && Seed.LEVELS.length == 0 && Seed.skillLevels(FakeSkills.class, null).length == 0
                && seed.contains("LEVELS = ON ? skillLevels(") && seed.contains("for (int[] sl : LEVELS) raise(p, sl[0], sl[1]);");
        check("V levels: skill:lvl list by constant name, any case, clamped; unknown/junk skipped " + slOk + ", ids " + idOk + ", engine Skills " + real + ", gated on ON and off here " + wired, slOk && idOk && real && wired);

        System.out.println("[TestSeed] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    public static final class FakeSkills { public static final int ATTACK = 0, STRENGTH = 2, MAGIC = 6, RUNECRAFTING = 20, NUM_SKILLS = 24; public static final String NAME = "x"; }

    static String read(String f) throws Exception { return new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8); }
}
