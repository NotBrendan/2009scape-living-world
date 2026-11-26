package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * w14 scen (the design notes): the tele, prayerStat, kit2, wwQuest, weaponByFoe and heal scenarios and the
 * scenStock seed key, plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestScen3 <addons-src>/content/custom
 * Run bare: Seed.ON is false, so every new hook returns at once and counts nothing.
 */
public final class TestScen3 {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestScen3] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }

    static Map<String, Integer> m(Object... kv) { Map<String, Integer> r = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], (Integer) kv[i + 1]); return r; }
    static String v(String n, Map<String, Integer> c, long... x) { return Scen.verdict(n, k -> c.getOrDefault(k, 0), x); }

    public static void main(String[] args) throws Exception {
        // A off (no harness markers): hooks count nothing, read nothing, start nothing
        Scen.teleAfter(null, Scen.teleBefore(null), true, "t"); Scen.prayTrip(null, 3, 10); Scen.kit2(null, 892, Scen.coins0(null));
        Scen.foeAfter(null, Scen.foeBefore(null)); Scen.healAfter(null, Scen.healBefore(null), true); Scen.healSummon(null);
        if (!Seed.ON) check("A off: on " + Seed.ON + ", stock " + Seed.STOCK.length + ", counters " + Scen.C.size() + ", before values null/0",
                !Seed.ON && Seed.STOCK.length == 0 && Scen.C.isEmpty() && !Scen.started && Scen.teleBefore(null) == null && Scen.foeBefore(null) == null && Scen.healBefore(null) == null && Scen.coins0(null) == 0);
        Map<String, String> k = new HashMap<>(); k.put("scenStock", "892:500"); boolean a1 = Seed.asked(k::get); k.put("scenStock", " "); boolean a2 = !Seed.asked(k::get);
        check("A asked: scenStock counts as a seed flag " + a1 + " (so live refuses it), blank does not " + a2, a1 && a2);
        String[] n = Scen.names("tele, PRAYERSTAT, kit2, wwquest, weaponByFoe, heal, bogus, tele");
        check("N names: the six new scenarios, any case, once each: " + String.join("/", n),
                String.join("/", n).equals("tele/prayerStat/kit2/wwQuest/weaponByFoe/heal"));

        // R pure rules
        check("R teleBad: own-rune cast free, bought cast paid, fare paid, refused free are fine",
                Scen.teleBad(true, false, true, 0) == null && Scen.teleBad(true, true, true, 240) == null && Scen.teleBad(false, false, true, 100) == null
                        && Scen.teleBad(false, false, false, 0) == null && Scen.teleBad(false, true, false, 60) == null && Scen.teleBad(false, true, true, 160) == null);
        check("R teleBad: coins up = minted, own-rune cast that cost coins = castPaid, refused with coins out and no runes = paidNothing",
                "minted".equals(Scen.teleBad(true, false, true, -1)) && "castPaid".equals(Scen.teleBad(true, false, true, 5)) && "paidNothing".equals(Scen.teleBad(false, false, false, 100))
                        && "minted".equals(Scen.teleBad(false, false, false, -3)));
        check("R kitBad: 0 (its own unit) up to the unit price is fine; above = overpaid; below 0 = minted; a 0 price counts as 1",
                Scen.kitBad(0, 400) == null && Scen.kitBad(400, 400) == null && "overpaid".equals(Scen.kitBad(401, 400)) && "minted".equals(Scen.kitBad(-1, 400))
                        && Scen.kitBad(1, 0) == null && "overpaid".equals(Scen.kitBad(2, 0)));
        check("R buyKind (w21): no coins out is no buy, suppliers credited exactly the coins out is toBots, anything else sunk",
                Scen.buyKind(0, 0) == null && Scen.buyKind(-5, 0) == null && "toBots".equals(Scen.buyKind(1904, 1904)) && "sunk".equals(Scen.buyKind(1904, 0)) && "sunk".equals(Scen.buyKind(1904, 1000)) && !Seed.STOCK_BOT);
        check("R healStep: only +2 in one tick is a bunyip heal", Scen.healStep(40, 42) && !Scen.healStep(40, 41) && !Scen.healStep(40, 52) && !Scen.healStep(40, 40) && !Scen.healStep(42, 40));

        // V verdicts
        check("V tele: PASS at 3 casts; 2 casts, minted, castPaid, paidNothing each FAIL",
                v("tele", m(), 3, 1, 200) == null && v("tele", m(), 2, 0, 0) != null && v("tele", m("tele.minted", 1), 5, 0, 0) != null
                        && v("tele", m("tele.castPaid", 1), 5, 0, 0) != null && v("tele", m("tele.paidNothing", 1), 5, 0, 0) != null);
        check("V prayerStat: PASS at 3 trips; 2 trips, points under zero, a coin-drain alarm each FAIL",
                v("prayerStat", m("pray.trips", 3), 0) == null && v("prayerStat", m("pray.trips", 2), 0) != null && v("prayerStat", m("pray.trips", 4, "pray.neg", 1), 0) != null
                        && v("prayerStat", m("pray.trips", 4), 1) != null);
        check("V kit2: PASS at 1 buy; none, minted, overpaid each FAIL",
                v("kit2", m("kit2.bought", 1), 1) == null && v("kit2", m(), 0) != null && v("kit2", m("kit2.bought", 2, "kit2.minted", 1), 2) != null
                        && v("kit2", m("kit2.bought", 2, "kit2.overpaid", 1), 2) != null);
        check("V wwQuest: PASS at 1 boost; none, a coin-drain alarm each FAIL",
                v("wwQuest", m(), 1, 0) == null && v("wwQuest", m(), 0, 0) != null && v("wwQuest", m(), 4, 1) != null);
        check("V weaponByFoe: PASS at 1 pick with 1 swap; no call, no swap, minted, paidNothing each FAIL",
                v("weaponByFoe", m("foe.calls", 2), 2, 1, 1) == null && v("weaponByFoe", m(), 0, 0, 0) != null && v("weaponByFoe", m("foe.calls", 2), 2, 0, 0) != null
                        && v("weaponByFoe", m("foe.calls", 2, "foe.minted", 1), 2, 1, 1) != null && v("weaponByFoe", m("foe.calls", 2, "foe.paidNothing", 1), 2, 1, 1) != null);
        check("V heal: PASS at a plan, a summon and a heal; no plan, no summon, no heal, minted, paidNothing each FAIL",
                v("heal", m("heal.heals", 1), 1, 1, 0) == null && v("heal", m("heal.heals", 1), 0, 0, 0) != null && v("heal", m("heal.heals", 1), 1, 0, 0) != null
                        && v("heal", m(), 1, 1, 0) != null && v("heal", m("heal.heals", 2, "heal.minted", 1), 1, 1, 1) != null && v("heal", m("heal.heals", 2, "heal.paidNothing", 1), 1, 1, 1) != null);
        check("V the nine older scenarios still judge (unknown stays unknown)", "unknown scenario".equals(v("nope", m())) && v("anyStaff", m("staff.calls", 1), 1) == null && v("capFix", m("cap.trips", 1, "fare.paid", 1)) == null);

        // W wiring: every call site sits behind its switch and every hook behind Seed.ON
        String dir = args.length > 0 ? args[0] : "deployment/addons-src/content/custom";
        String sc = read(dir + "/Scen.java"), le = read(dir + "/Ledger.java"), sa = read(dir + "/SmartAdventurers.java"), bo = read(dir + "/Bob.java"), wf = read(dir + "/W6Fight.java"), se = read(dir + "/Seed.java");
        boolean w1 = le.contains("if (!Seed.ON || p == null || !p.isArtificial()) return payTeleport0(p, why);") && le.contains("if (W6Magic.TELE) { long[] tb = Scen.teleBefore(p); boolean tok = payTeleport0(p, why); Scen.teleAfter(p, tb, tok, why); return tok; }");
        boolean w2 = sa.contains("W6Combat.kitBought++; Scen.kit2(bot, pc[0], sc0);") && sa.contains("{ long[] sfb = Scen.foeBefore(bot); try { w8WeaponByFoe0(h); } finally { Scen.foeAfter(bot, sfb); } }");
        boolean w3 = bo.contains("{ long[] shb = Scen.healBefore(r.p); boolean shp = w8HealPlan0(r); Scen.healAfter(r.p, shb, shp); return shp; }") && bo.contains("W8Polish.healSummonOk++; Scen.healSummon(p);");
        boolean w4 = wf.contains("Scen.prayTrip(r.p, s.fights, sk.getPrayerPoints());") && se.contains("\"scenStock\", \"levels\", \"scenStockBot\"}") && se.contains("STOCK_BOT = ON && ") && se.contains("STOCK = ON ? pairs(");
        boolean w5 = count(sc, "on(p) ? new long[]") >= 4 && count(sc, "if (!on(p)") >= 13 && !sc.contains("nextInt") && !sc.contains("RNG.");
        check("W wiring: Ledger tele " + w1 + ", SmartAdventurers kit2/weaponByFoe " + w2 + ", Bob heal " + w3 + ", W6Fight + seed key " + w4 + ", hooks gated and no RNG in Scen " + w5, w1 && w2 && w3 && w4 && w5);

        System.out.println("[TestScen3] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static int count(String s, String a) { int n = 0, i = 0; while ((i = s.indexOf(a, i)) >= 0) { n++; i += a.length(); } return n; }
    static String read(String f) throws Exception { return new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8); }
}
