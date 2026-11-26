package content.custom;

import content.global.skill.slayer.Tasks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * w9 scen2 (the design notes): the herbs2 and vannakaHunts scenarios and the three new seed keys
 * (combat, quest, master), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestScen2 <addons-src>/content/custom
 * Run bare (no seed flags, no harness markers) and again with the new seed flags but no harness markers: either way Seed.ON is
 * false, so the new keys read as off and every new hook counts nothing ("refused (live)" is printed once in the second run).
 */
public final class TestScen2 {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestScen2] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }

    static Map<String, Integer> m(Object... kv) { Map<String, Integer> r = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], (Integer) kv[i + 1]); return r; }
    static String v(String n, Map<String, Integer> c, long... x) { return Scen.verdict(n, k -> c.getOrDefault(k, 0), x); }

    public static void main(String[] args) throws Exception {
        // A off here (no harness markers): the new keys read as off and the new hooks count nothing, asked or not
        Scen.herbPlant(null, 5300); Scen.herbHarvest(null, 3051, 3); Scen.slayTrip(null, Tasks.VAMPIRES, null); Scen.slayKills(null, Tasks.VAMPIRES, 4); Scen.death(null, null);
        check("A off: asked " + Seed.ASKED + ", allowed " + Seed.ALLOWED + ", on " + Seed.ON + ", combat " + Seed.COMBAT + ", quests " + Seed.QUESTS.length + ", master " + Seed.MASTER + ", scen " + Seed.SCEN.length + ", counters " + Scen.C.size(),
                !Seed.ALLOWED && !Seed.ON && Seed.COMBAT == 0 && Seed.QUESTS.length == 0 && "TURAEL".equals(Seed.MASTER) && Seed.SCEN.length == 0 && Seed.TASK == null && Scen.C.isEmpty() && !Scen.started);
        Map<String, String> k = new HashMap<>(); boolean a0 = !Seed.asked(k::get);
        k.put("combat", "88"); boolean a1 = Seed.asked(k::get); k.clear(); k.put("quest", "PRIEST_IN_PERIL"); boolean a2 = Seed.asked(k::get); k.clear(); k.put("master", "vannaka"); boolean a3 = Seed.asked(k::get);
        k.clear(); k.put("combat", " "); boolean a4 = !Seed.asked(k::get);
        check("A asked: combat " + a1 + ", quest " + a2 + ", master " + a3 + " each count (so the live guard refuses them); none " + a0 + ", blank " + a4, a0 && a1 && a2 && a3 && a4);

        // P parsing: junk never throws and reads as off
        check("P combat target: 4..126, under 4 / junk / blank = 0",
                Seed.combatTarget("88") == 88 && Seed.combatTarget(" 84 ") == 84 && Seed.combatTarget("200") == 126 && Seed.combatTarget("3") == 0 && Seed.combatTarget("-5") == 0
                        && Seed.combatTarget("x") == 0 && Seed.combatTarget("") == 0 && Seed.combatTarget(null) == 0 && Seed.combatTarget("88.5") == 0);
        String[] q = Seed.quests(" priest in peril, PRIEST_IN_PERIL ,, 1abc, x-y, Dragon_Slayer, " + "A".repeat(41));
        check("P quests: upper case, spaces as _, once each, enum-like names only " + String.join("/", q),
                q.length == 2 && "PRIEST_IN_PERIL".equals(q[0]) && "DRAGON_SLAYER".equals(q[1]) && Seed.quests(null).length == 0 && Seed.quests(" , ,").length == 0 && Seed.quests(":").length == 0);
        check("P master: vannaka (any case) = VANNAKA, else TURAEL",
                "VANNAKA".equals(Seed.master("vannaka")) && "VANNAKA".equals(Seed.master(" Vannaka ")) && "TURAEL".equals(Seed.master("turael")) && "TURAEL".equals(Seed.master(null))
                        && "TURAEL".equals(Seed.master("")) && "TURAEL".equals(Seed.master("mazchna")));

        // C combat level (the engine's formula) and the least common melee level
        check("C combatOf: fresh 3, maxed 126, 77s 88, rangers count",
                Seed.combatOf(1, 1, 1, 10, 1, 1, 1) == 3 && Seed.combatOf(99, 99, 99, 99, 99, 99, 99) == 126 && Seed.combatOf(77, 77, 77, 77, 1, 1, 1) == 88
                        && Seed.combatOf(1, 1, 1, 10, 1, 99, 1) == 50 && Seed.combatOf(1, 1, 1, 10, 1, 1, 99) == 50);
        int f = Seed.meleeFor(88, new int[]{1, 1, 1, 10, 1, 1, 1}), hi = Seed.meleeFor(88, new int[]{99, 1, 1, 10, 1, 1, 1}), r84 = Seed.meleeFor(84, new int[]{60, 60, 60, 60, 1, 1, 1});
        check("C meleeFor: fresh -> 77 (" + f + "), attack 99 kept -> 68 (" + hi + "), 60s to 84 -> 74 (" + r84 + "), already there / off / short array -> 0, out of reach -> 99",
                f == 77 && hi == 68 && r84 == 74 && Seed.meleeFor(88, new int[]{99, 99, 99, 99, 1, 1, 1}) == 0 && Seed.meleeFor(0, new int[]{1, 1, 1, 10, 1, 1, 1}) == 0
                        && Seed.meleeFor(88, null) == 0 && Seed.meleeFor(88, new int[]{1, 1}) == 0 && Seed.meleeFor(126, new int[]{1, 1, 1, 10, 1, 1, 1}) == 99
                        && Seed.combatOf(76, 76, 76, 76, 1, 1, 1) < 88 && Seed.combatOf(99, 67, 67, 67, 1, 1, 1) < 88);
        int d = Seed.defenceFloor(88), l2 = Seed.meleeFor(88, new int[]{99, 99, d, 10, 1, 1, 1});
        check("C defenceFloor: target-25 in 0..99 (88 -> " + d + "); a 99/99 attacker with Defence 1 gets Defence 63 then L " + l2 + ": combat "
                        + Seed.combatOf(99, 99, Math.max(d, l2), Math.max(10, l2), 1, 1, 1) + ", effective min(combat, Defence+25) >= 88",
                d == 63 && Seed.defenceFloor(0) == 0 && Seed.defenceFloor(20) == 0 && Seed.defenceFloor(126) == 99 && l2 == 32
                        && Math.min(Seed.combatOf(99, 99, Math.max(d, l2), Math.max(10, l2), 1, 1, 1), Math.max(d, l2) + 25) >= 88);

        // R pure rules
        check("R herbUp: snapdragon/torstol above ranarr, ranarr/guam not; grimy herbs; W6 tasks; death loop",
                Scen.herbUp(5300) && Scen.herbUp(5304) && Scen.herbUp(5296) && !Scen.herbUp(5295) && !Scen.herbUp(5291) && !Scen.herbUp(-1)
                        && Scen.grimy(3051) && Scen.grimy(199) && !Scen.grimy(1942) && !Scen.grimy(-1)
                        && Scen.w6Task("VAMPIRES") && Scen.w6Task("HELLHOUNDS") && !Scen.w6Task("WOLVES") && !Scen.w6Task(null)
                        && Scen.deathLoop(3, 50) && Scen.deathLoop(2, 1) && !Scen.deathLoop(2, 2) && !Scen.deathLoop(1, 0) && !Scen.deathLoop(0, 0));
        String[] n = Scen.names("HERBS2, vannakahunts,herbs2, fix9b");
        check("R names: the two new scenarios known, canonical case " + String.join("/", n), n.length == 3 && "herbs2".equals(n[0]) && "vannakaHunts".equals(n[1]) && "fix9b".equals(n[2]));

        // V verdicts
        check("V herbs2 {herbsUp}: plant above ranarr + counted pick = PASS (harvest not required); each failure named",
                v("herbs2", m("h2.plant", 1, "h2.plantUp", 1), 1) == null && v("herbs2", m("h2.plant", 2, "h2.plantUp", 1, "h2.harvest", 6, "h2.harvests", 1), 1) == null
                        && v("herbs2", m(), 0).equals("no herb planted") && v("herbs2", m("h2.plant", 2), 0).contains("none above ranarr")
                        && v("herbs2", m("h2.plant", 1, "h2.plantUp", 1), 0).contains("not counted"));
        check("V vannakaHunts {built}: trip + kill = PASS; no hunt / no trip / no kill / death loop named",
                v("vannakaHunts", m("vh.trips", 1, "vh.kills", 3), 2) == null && v("vannakaHunts", m("vh.trips", 2, "vh.kills", 4, "vh.deaths", 1), 2) == null
                        && v("vannakaHunts", m("vh.trips", 1, "vh.kills", 3), 0).contains("built") && v("vannakaHunts", m("vh.kills", 3), 2).contains("trip")
                        && v("vannakaHunts", m("vh.trips", 2), 2).equals("no task kill") && v("vannakaHunts", m("vh.trips", 1, "vh.deaths", 1), 2).equals("no task kill")
                        && v("vannakaHunts", m("vh.trips", 2, "vh.kills", 10, "vh.deaths", 3), 2).equals("death loop") && v("vannakaHunts", m("vh.trips", 2, "vh.kills", 1, "vh.deaths", 2), 2).equals("death loop"));
        String lh = Scen.line("herbs2"), lv = Scen.line("vannakaHunts");
        boolean vl = "FAIL herbs2 x=[0] why=no_herb_planted".equals(lh) && "FAIL vannakaHunts x=[0] why=no_Vannaka_hunt_built".equals(lv);
        check("V lines with nothing counted: herbs2 no_herb_planted, vannakaHunts no_Vannaka_hunt_built" + (vl ? "" : " got [" + lh + "] [" + lv + "]"), vl);

        // W wiring, read from source
        String dir = args.length > 0 ? args[0] : "deployment/addons-src/content/custom";
        String seed = read(dir + "/Seed.java"), sl = read(dir + "/Slayer.java"), sa = read(dir + "/SmartAdventurers.java"), sc = read(dir + "/Scen.java");
        boolean w1 = seed.contains("\"task\", \"combat\", \"quest\", \"master\", \"magic\", \"job\", \"rcstick\", \"scen\", \"ticks\"") && seed.contains("static final int COMBAT = ON ? combatTarget(System.getProperty(P + \"combat\")) : 0;")
                && seed.contains("static final String[] QUESTS = ON ? quests(System.getProperty(P + \"quest\")) : new String[0];") && seed.contains("static final String MASTER = ON ? master(System.getProperty(P + \"master\")) : \"TURAEL\";")
                && seed.indexOf("if (COMBAT > 0) {") > seed.indexOf("if (!ON || p == null || !p.isArtificial()) return;") && seed.contains("Slayer.hold(b, p, t, Integer.parseInt(TASK[1]), System.currentTimeMillis(), from);");
        boolean w2 = sl.contains("static boolean hold(SmartAdventurers.Brain b, Player bot, Tasks t, int n, long now) { return hold(b, bot, t, n, now, Master.TURAEL); }")
                && sl.contains("if (Seed.ON) Scen.slayTrip(b.bot, s.had, nearest);") && count(sl, "if (Seed.ON) Scen.slayKills(bot, s.had, ") == 2;
        boolean w3 = sa.contains("if (!allot && Seed.ON) Scen.herbPlant(bot, p.getPlantable() == null ? -1 : p.getPlantable().getItemID());")
                && sa.contains("if (!allot && !flower && Seed.ON) Scen.herbHarvest(bot, farmCrop, got);") && sa.contains("if (Seed.ON) Scen.death(bot, lastJob);");
        boolean w4 = count(sc, "if (!on(p)") >= 5 && sc.contains("\"herbs2\", \"vannakaHunts\"");
        check("W wiring: seed keys " + w1 + ", Slayer hold/trip/kills " + w2 + ", SmartAdventurers plant/harvest/death " + w3 + ", Scen hooks gated " + w4, w1 && w2 && w3 && w4);

        System.out.println("[TestScen2] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static int count(String s, String a) { int n = 0, i = 0; while ((i = s.indexOf(a, i)) >= 0) { n++; i += a.length(); } return n; }
    static String read(String f) throws Exception { return new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8); }
}
