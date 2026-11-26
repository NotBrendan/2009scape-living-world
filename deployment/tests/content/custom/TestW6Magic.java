package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * W6 Magic lane (the design notes section 3, Magic row), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW6Magic <addons-src>/content/custom
 * Run it twice: bare (every flag off) and with -Dbots.magic.tele -Dbots.magic.alchAll -Dbots.magic.anyStaff.
 * Per flag: the pure rule (W6Magic), and the wiring read from the source: each hook behind its own flag, train-61's
 * line still there for the flag-off path. In-world checks: the design notes.
 */
public final class TestW6Magic {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW6Magic] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW6Magic]   missing: " + p); return false; } return true; }
    static boolean order(String src, String a, String b) { int i = src.indexOf(a), j = src.indexOf(b); return i >= 0 && j > i; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), led = read(dir, "Ledger.java"), mb = read(dir, "MarketBrain.java"), cast = read(dir, "W6MagicCast.java");

        // F: statics follow their properties, default off
        String[] keys = {"bots.magic.tele", "bots.magic.alchAll", "bots.magic.anyStaff"};
        boolean[] st = {W6Magic.TELE, W6Magic.ALCH_ALL, W6Magic.ANY_STAFF};
        boolean follow = true; int on = 0; for (int i = 0; i < keys.length; i++) { if (st[i] != Boolean.getBoolean(keys[i])) follow = false; if (st[i]) on++; }
        check("F flags: 3 statics follow their -D properties (" + on + " on), anyOn " + W6Magic.anyOn() + ", line: " + W6Magic.line(0, 150_000),
                follow && W6Magic.anyOn() == (on > 0) && W6Magic.line(0, 150_000).startsWith("[W6Magic] on=[") && W6Magic.line(12, 150_000).contains("alchDay=12/150000"));

        // 1 teleport: level gate, rune counts, staff rune free, prefer a row it holds the runes for
        Map<Integer, Integer> bag = new HashMap<>();
        java.util.function.IntUnaryOperator h = id -> bag.getOrDefault(id, 0);
        boolean lvl = W6Magic.pickTele(24, 0, h) == -1 && W6Magic.pickTele(25, 0, h) == 0 && W6Magic.pickTele(36, 0, h) == 1 && W6Magic.pickTele(99, 0, h) == 3;
        int[][] varrock = W6Magic.teleRunes(0, 0), varrockFire = W6Magic.teleRunes(0, 1387), camelotAir = W6Magic.teleRunes(3, 1381);
        boolean runes = varrock.length == 3 && varrock[0][0] == 563 && varrock[0][1] == 1 && varrock[1][0] == 556 && varrock[1][1] == 3 && varrock[2][0] == 554 && varrock[2][1] == 1
                && varrockFire.length == 2 && camelotAir.length == 1 && camelotAir[0][0] == 563;
        bag.put(563, 1); bag.put(556, 3); bag.put(554, 1); // holds Varrock's runes, not Camelot's 5 air
        boolean prefer = W6Magic.pickTele(50, 0, h) == 0 && W6Magic.pickTele(50, 1381, h) == 3; // a staff of air makes Camelot law-only
        boolean shortR = W6Magic.shortOf(3, 1) == 2 && W6Magic.shortOf(1, 5) == 0 && W6Magic.shortOf(1, -3) == 1;
        boolean buy = W6Magic.canBuy(new int[]{1, 0}, new int[]{11, 0}, new long[]{300, 5}, 300) && !W6Magic.canBuy(new int[]{1, 0}, new int[]{10, 0}, new long[]{300, 5}, 300)
                && !W6Magic.canBuy(new int[]{1, 2}, new int[]{50, 50}, new long[]{300, 5}, 309) && W6Magic.canBuy(new int[]{0, 0}, new int[]{0, 0}, new long[]{0, 0}, 0);
        check("1 tele rule: level 25 gate " + lvl + ", rune rows and staff rune free " + runes + ", held row first " + prefer + ", shortfall " + shortR + ", stock floor 10 and purse " + buy,
                lvl && runes && prefer && shortR && buy);
        check("1 tele wiring: payTeleport casts first behind TELE, the old fare lines unchanged; the cast buys with Ledger.consume and gives Magic xp",
                all(led, "if (W6Magic.TELE && W6MagicCast.castTele(p, why)) return true;", "final long lawP = price(563), airP = price(556);", "if (CLERK_FIX2) return payTeleport2(p, why, cost, lawP, airP);")
                        && order(led, "if (W6Magic.TELE && W6MagicCast.castTele(p, why)) return true;", "final long lawP = price(563), airP = price(556);")
                        && all(cast, "Ledger.consume(p, runes[i][0], shortN[i], \"teleport-cast\")", "addExperience(Skills.MAGIC", "if (p == null || !p.isArtificial()) return false;", "W6Magic.teleShort++; return false;"));

        // 2 alch: hourly fleet room on minted coins; wiring keeps the profit test and the day cap
        boolean room = W6Magic.hourRoom(0, 7_680, 20_000) && W6Magic.hourRoom(12_320, 7_680, 20_000) && !W6Magic.hourRoom(12_321, 7_680, 20_000) && !W6Magic.hourRoom(0, 1, 0);
        check("2 alch rule: fleet hour room on gross minted coins " + room + " (cap " + W6Magic.ALCH_HOUR + ")", room && W6Magic.ALCH_HOUR >= 0);
        check("2 alch wiring: highAlch(now) is the old break call; the routine call drops only nearGE; hour cap before alchRoom; profit test and day cap kept; trip-end hook behind ALCH_ALL before the plank run",
                all(sa, "boolean highAlch(long now) { return highAlch(now, false); }", "!(routine || nearGE(bot))) return false;", "if (alch <= cost) return false;",
                        "if (!Ledger.CLERK_FIX2 && !Ledger.alchRoom((long) (alch - cost) * n)) return false;", "if (RNG.nextInt(6) == 0 && highAlch(now)) return;")
                        && order(sa, "if (W6Magic.ALCH_ALL && !W6Magic.alchHourOk(now, (long) alch * n))", "if (!Ledger.CLERK_FIX2 && !Ledger.alchRoom(")
                        && order(sa, "if (W6Magic.ALCH_ALL && bot.getSkills().getStaticLevel(Skills.MAGIC) >= W6Magic.ALCH_LEVEL && now >= nextAlch) try { W6Magic.alchRoutine++; highAlch(now, true); }", "if (planPlankRun(now)) return;")
                        && all(sa, "if (W6Magic.ALCH_ALL) { W6Magic.alchBook("));

        // 3 basic staff: kit keeps it, casting rules, the last-chance plan
        boolean keep = W6Magic.keepBasic(true, 1379, 1000, 1000) && !W6Magic.keepBasic(true, 1379, 1400, 1000) && !W6Magic.keepBasic(false, 1379, 0, 1000) && !W6Magic.keepBasic(true, -1, 0, 1000);
        boolean isSt = W6Magic.isStaff(false, 1387) && !W6Magic.isStaff(false, 1379) && W6Magic.isStaff(true, 1379) && !W6Magic.isStaff(true, 1381);
        long[] p1 = W6Magic.basicPlan(500, false, 15, 7, 0), p2 = W6Magic.basicPlan(500, true, 15, 7, 30), p3 = W6Magic.basicPlan(200, false, 15, 7, 0), p4 = W6Magic.basicPlan(110, false, 15, 7, 0);
        long[] p5 = W6Magic.basicPlan(300, false, 15, 7, 0);
        boolean plan = p1 != null && p1[0] == 15 && p1[1] == 55 && p2 != null && p2[0] == 0 && p2[1] == 0 && p3 == null && p4 == null && p5 != null && p5[1] == 26;
        check("3 staff rule: kit keeps a basic staff while the fire staff would leave < 300 " + keep + ", isStaff " + isSt + ", plan (staff + 20..100 casts, keep 100) " + plan
                + ", wind strike row " + java.util.Arrays.toString(W6Magic.WIND_STRIKE), keep && isSt && plan && W6Magic.WIND_STRIKE[0] == 1 && W6Magic.WIND_STRIKE[1] == 558 && W6Magic.WIND_STRIKE[3] == 556);
        check("3 staff wiring: styleKit, pickTripSpell, equipSupplies, styleWeaponBack, styleFallback behind ANY_STAFF; the last chance between defShare and styleFallback; w3Restock untouched",
                all(sa, "&& !(W6Magic.ANY_STAFF && W6Magic.keepBasic(true,", "if (W6Magic.ANY_STAFF && wornBasicStaff()) return W6Magic.WIND_STRIKE;", "if (W6Magic.ANY_STAFF && wornBasicStaff()) s = W6Magic.WIND_STRIKE;",
                        "(staff.getId() == 1387 || W6Magic.ANY_STAFF && staff.getId() == W6Magic.BASIC_STAFF)", "!W6Magic.isStaff(W6Magic.ANY_STAFF, w.getId())) why = \"mage without a staff\";",
                        "if (w == null || w.getId() != 1387) { W3Skills.restockNone++; return false; }", "boolean w6BasicStaff() {")
                        && order(sa, "if (W5bSkills.DEF_SHARE && styleOf(bot) == RANGED) try { w5bDefShare(); }", "if (W6Magic.ANY_STAFF && style5() && styleOf(bot) == MAGIC) try { final long[] w8 = Scen.staffBefore(bot); boolean w8r = w6BasicStaff(); Scen.staffAfter(bot, w8); if (w8r) equipSupplies(); }")
                        && order(sa, "if (W6Magic.ANY_STAFF && style5() && styleOf(bot) == MAGIC) try { final long[] w8 = Scen.staffBefore(bot); boolean w8r = w6BasicStaff(); Scen.staffAfter(bot, w8); if (w8r) equipSupplies(); }", "if (style5()) try { styleFallback(); }"));

        check("H hourly: MarketBrain prints W6Magic.line on its own timer only with a flag on",
                all(mb, "if (W6Magic.anyOn()) TIMER.schedule(", "System.out.println(W6Magic.line(Ledger.alchedToday, Ledger.ALCH_DAILY_CAP));"));

        // t62 review fixes (results/t62-audit.md item 5)
        int[][] rr = {{563, 1}, {556, 3}};
        boolean ah = W6Magic.allHeld(rr, id -> id == 563 ? 1 : 3) && !W6Magic.allHeld(rr, id -> id == 563 ? 1 : 2) && !W6Magic.allHeld(rr, id -> 0);
        check("t62 fix 3 rule: allHeld needs every rune in full " + ah, ah);
        check("t62 fix 3 wiring: castTele checks allHeld before any rune leaves (else the fare), a short remove puts back what it took and returns false; no cast-anyway",
                all(cast, "if (!W6Magic.allHeld(runes, id -> held(p, id))) { W6Magic.teleMissing++;", "took[ri][0] = b;", "took[ri][1] = k;", "runes put back, the fare instead\");", "W6Magic.teleMissing++;")
                        && order(cast, "if (!W6Magic.allHeld(runes, id -> held(p, id)))", "int fromBank = 0, fromPack = 0;") && !cast.contains("(cast anyway)"));
        boolean sroom = W6Magic.staffRoom(true, false) && W6Magic.staffRoom(false, true) && !W6Magic.staffRoom(false, false);
        check("t62 fix 4 rule: the staff is paid for only with room in bank or pack " + sroom, sroom);
        check("t62 fix 4 wiring: w6BasicStaff checks room before Wallet.pay, places in bank then pack, a refused add gives the coins back",
                all(sa, "if (!W6Magic.staffRoom(bot.getBank().hasSpaceFor(st), bot.getInventory().hasSpaceFor(st))) { W6Magic.staffNoRoom++; return false; }",
                        "if (!bot.getBank().add(st) && !bot.getInventory().add(st)) { Ledger.Wallet.give(bot, plan[0]); W6Magic.staffRefund++;")
                        && order(sa, "W6Magic.staffRoom(bot.getBank().hasSpaceFor(st)", "if (!Ledger.Wallet.pay(bot, plan[0])) { W6Magic.staffNoCoins++; return false; }")
                        && !sa.contains("bot.getBank().add(new Item(W6Magic.BASIC_STAFF)); W6Magic.staffBought++;"));

        boolean rf = W6Magic.runeRefund(3, 4, 20) == 32 && W6Magic.runeRefund(0, 4, 0) == 0 && W6Magic.runeRefund(-1, 4, 5) == 5;
        check("t62 fix 4b: Wind Strike runes bought only with room (else skipped), a refused add gives back the stock part and the coins paid " + rf,
                rf && all(sa, "if (!W6Magic.staffRoom(bot.getBank().hasSpaceFor(ru), bot.getInventory().hasSpaceFor(ru))) { W6Magic.staffRuneNoRoom++; continue; }",
                        "if (g > 0 && !bot.getBank().add(new Item(ids[i], g)) && !bot.getInventory().add(new Item(ids[i], g))) {", "long back = W6Magic.runeRefund(gc, Ledger.price(ids[i]), paid);", "W6Magic.staffRuneRefund++;")
                        && order(sa, "W6Magic.staffRuneNoRoom++; continue; }", "Ledger.consume(bot, ids[i], need[i], \"ammo\")")
                        && !sa.contains("if (g > 0) bot.getBank().add(new Item(ids[i], g));") && W6Magic.line(0, 1).contains(" staffRuneRefund="));

        System.out.println("[TestW6Magic] " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
