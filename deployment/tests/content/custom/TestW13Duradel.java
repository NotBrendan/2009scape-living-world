package content.custom;

import content.global.skill.slayer.Master;
import content.global.skill.slayer.Tasks;
import core.game.world.map.Location;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;

/**
 * W13 lane B: Duradel (-Dbots.slayer.duradel), Warriors' Guild defenders (-Dbots.str.defenders), Summoning scrolls
 * (-Dbots.summoning.scrolls), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW13Duradel <addons-src>/content/custom
 * Bare run: every flag off. Pure rules checked directly; hooks read from the source (each tests its switch first).
 */
public final class TestW13Duradel {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW13Duradel] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW13Duradel]   missing: " + p); return false; } return true; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "deployment/addons-src/content/custom";
        String sl = read(dir, "Slayer.java"), sa = read(dir, "SmartAdventurers.java"), mb = read(dir, "MarketBrain.java"), w = read(dir, "W13Duradel.java");
        boolean flag = Boolean.getBoolean("bots.slayer.duradel");

        // 1 switch
        check("D1 switch = flag && Chaeldar switch; off when bare", Slayer.DURADEL == (flag && Slayer.CHAELDAR) && W13Duradel.ON == Slayer.DURADEL);
        // 2 pure rules
        check("D2 pre: combat 100..138 and eff 100+", W13Duradel.pre(100, 100) && !W13Duradel.pre(99, 120) && !W13Duradel.pre(110, 99) && !W13Duradel.pre(139, 139));
        check("D3 can: every term needed (core, shilo, 2 fares, food, open 2+)", W13Duradel.can(110, 110, true, true, 20, true, 2)
                && !W13Duradel.can(110, 110, false, true, 20, true, 2) && !W13Duradel.can(110, 110, true, false, 20, true, 2)
                && !W13Duradel.can(110, 110, true, true, 19, true, 2) && !W13Duradel.can(110, 110, true, true, 20, false, 2) && !W13Duradel.can(110, 110, true, true, 20, true, 1));
        check("D4 pick: Duradel only when on and can; else the earlier answer untouched", W13Duradel.pick(true, Master.CHAELDAR, true) == Master.DURADEL
                && W13Duradel.pick(false, Master.CHAELDAR, true) == Master.CHAELDAR && W13Duradel.pick(true, Master.VANNAKA, false) == Master.VANNAKA && W13Duradel.pick(true, null, false) == null);
        check("D5 shilo: seeded or complete", W13Duradel.shiloOk(true, false) && W13Duradel.shiloOk(false, true) && !W13Duradel.shiloOk(false, false));
        check("D6 inShilo: village box any plane; Brimhaven cart outside", W13Duradel.inShilo(W13Duradel.AT) && W13Duradel.inShilo(Location.create(2834, 2951, 0))
                && !W13Duradel.inShilo(W13Duradel.CART_HINT) && !W13Duradel.inShilo(null));
        check("D7 dist: 0 inside, else to the cart", W13Duradel.dist(W13Duradel.AT, W13Duradel.CART_HINT) == 0 && W13Duradel.dist(Location.create(2790, 3212, 0), W13Duradel.CART_HINT) == 10);
        Set<Tasks> al = W13Duradel.allow();
        boolean ok = !al.isEmpty();
        for (Tasks t : al) ok &= t.levelReq <= 1 && t.questReq == null && !Slayer.VANNAKA_DENY.contains(t.name());
        check("D8 list: Duradel tasks, Slayer level 1, no quest, not denied (" + al.size() + ": " + al + ")", ok);
        String hl = W13Duradel.line(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
        check("D9 hourly line", hl.equals("[Slayer] duradel hourly on=" + W13Duradel.ON + " qualify=1/2 ladder=3 picked=4 enters=5 leaves=6 assigned=7 holding=8 fares=9"));
        // 3 wiring
        check("D10 Slayer hooks each test DURADEL first", all(sl,
                "static final boolean DURADEL = Boolean.getBoolean(\"bots.slayer.duradel\") && CHAELDAR;",
                "if (DURADEL && m == Master.DURADEL) return W13Duradel.dist(here);",
                "if (DURADEL && m == Master.DURADEL) return W13Duradel.out();",
                "DURADEL && m == Master.DURADEL ? W13Duradel.AT : ",
                "DURADEL && m == Master.DURADEL ? W13Duradel.allow() : ",
                "boolean d = DURADEL && master == Master.DURADEL;",
                "if (DURADEL && to == Master.DURADEL && W13Duradel.go(bot, s)) return;",
                "!(DURADEL && gm == Master.DURADEL)",
                "if (DURADEL && s.master == Master.DURADEL) W13Duradel.assigned(",
                "if (CHAELDAR) m = W9Chaeldar.choose(b, m, combat, eff, done); if (DURADEL) m = W13Duradel.choose(b, m, combat, eff, done);",
                "if (DURADEL) try { W13Duradel.leave(b.bot, \"errand end\"); }"));
        check("D11 brain start and hourly timer behind the switch", all(sa, "if (Slayer.DURADEL) try { W13Duradel.leave(bot, \"brain start\"); }")
                && all(mb, "if (Boolean.getBoolean(\"bots.slayer.duradel\") && Slayer.DURADEL) TIMER.schedule("));
        String wc = w.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        check("D12 W13Duradel draws no RNG, never touches points or the task, pays only the cart fare and (w33) one sea-crossing call", !wc.contains("RNG") && !wc.contains("Random") && !wc.contains("SlayerPoints")
                && !wc.contains("generate(") && !wc.contains("setTask") && wc.split("payTeleport", -1).length == 2 && wc.contains("Ledger.Wallet.payTeleport(bot, \"sea-crossing\")") && wc.contains("payFee(bot, FARE, \"shilo cart\")"));
        // 4 w33: the crossing to the cart (results/w33-duradel.md)
        check("D13 east of the Brimhaven gate: Musa Point, the gate's east tile; not the cart, the gate's west tile, Tai Bwo Wannai, a dungeon, a floor",
                W13Duradel.eastOfGate(2956, 3146, 0) && W13Duradel.eastOfGate(2816, 3182, 0) && !W13Duradel.eastOfGate(2780, 3212, 0) && !W13Duradel.eastOfGate(2815, 3182, 0)
                && !W13Duradel.eastOfGate(2790, 3065, 0) && !W13Duradel.eastOfGate(2850, 3137, 0) && !W13Duradel.eastOfGate(2956, 3146, 1));
        check("D14 cut: the sea or the gate, never in Shilo or at the cart", W13Duradel.cut(false, false, true, false) && W13Duradel.cut(false, false, false, true)
                && !W13Duradel.cut(false, false, false, false) && !W13Duradel.cut(true, false, true, true) && !W13Duradel.cut(false, true, true, true));
        long gap = W13Duradel.CROSS_GAP_MS;
        check("D15 crossNow: cut, not walk-only, purse floor, cap, no teleport pending, the gap", W13Duradel.crossNow(true, false, true, true, false, gap, 0)
                && !W13Duradel.crossNow(false, false, true, true, false, gap, 0) && !W13Duradel.crossNow(true, true, true, true, false, gap, 0)
                && !W13Duradel.crossNow(true, false, false, true, false, gap, 0) && !W13Duradel.crossNow(true, false, true, false, false, gap, 0)
                && !W13Duradel.crossNow(true, false, true, true, true, gap, 0) && !W13Duradel.crossNow(true, false, true, true, false, gap - 1, 0));
        check("D16 need: two cart fares, plus the crossing when cut; can() asks for it", W13Duradel.need(false, 180) == 20 && W13Duradel.need(true, 180) == 200
                && W13Duradel.can(105, 105, true, true, 200, true, 2, 200) && !W13Duradel.can(105, 105, true, true, 199, true, 2, 200)
                && W13Duradel.can(105, 105, true, true, 20, true, 2) == W13Duradel.can(105, 105, true, true, 20, true, 2, 20) && !W13Duradel.can(105, 105, true, true, 19, true, 2));
        check("D17 hourly line with the crossing count", W13Duradel.line(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}).endsWith(" fares=9 crossed=10"));
        check("D18 go(): the crossing before Slayer.go, the standard sea-crossing fare, the errand's cap count, no boat",
                all(w, "if (cross(bot, here, cart, s)) return true;\n        Slayer.go(bot, cart, s);".replace("\n", w.contains("\r\n") ? "\r\n" : "\n"),
                        "if (!Ledger.Wallet.payTeleport(bot, \"sea-crossing\")) return false;", "if (Ledger.CLERK_FIX2 && s != null) Slayer.capCount(bot, s, 1, \"errand\");",
                        "Spectacle.teleport(bot, land);") && !wc.contains(".crossSea(") && !wc.contains("Boat"));

        check("D19 review: the gap is above the errand's walk give-up, so one errand pays once", W13Duradel.CROSS_GAP_MS > Slayer.WALK_GIVEUP_MS
                && !W13Duradel.crossNow(true, false, true, true, false, Slayer.WALK_GIVEUP_MS, 0));
        check("D20 review: a cut-off landing is never paid for (cross() tests it before payTeleport, choose() declines)", W13Duradel.landOk(false) && !W13Duradel.landOk(true)
                && wc.indexOf("if (!landOk(cut(land))) return false;") > 0 && wc.indexOf("if (!landOk(cut(land))) return false;") < wc.indexOf("payTeleport(bot, \"sea-crossing\")")
                && all(w, "&& landOk(cut(landing())));", "try { Spectacle.teleport(bot, land); } catch (Throwable ignored) { }"));
        check("D21 review: the Musa Point boat landing (a trap cell's landing) is east of the gate, the cart tile is not", W13Duradel.eastOfGate(2956, 3146, 0)
                && W13Duradel.cut(false, false, false, W13Duradel.eastOfGate(2956, 3146, 0)) && !W13Duradel.cut(false, true, false, W13Duradel.eastOfGate(2780, 3212, 0)));
        if (flag && Boolean.getBoolean("bots.slayer.chaeldar")) check("D22 on run: the switch is really on (needs slayer, vannaka, chaeldar and their park / openCheck / foodGate flags)", Slayer.DURADEL && W13Duradel.ON);

        System.out.println("[TestW13Duradel] " + pass + " pass, " + fail + " fail (switch " + (Slayer.DURADEL ? "on" : "off") + ")");
        if (fail > 0) System.exit(1);
    }
}
