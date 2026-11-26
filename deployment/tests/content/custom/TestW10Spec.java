package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W10 lane M: spec weapons for 60+ melee bots (-Dbots.attack.spec, W10Skills / W10Hooks), plain java (no world, no cache):
 *   java -cp "<build>;server.jar" content.custom.TestW10Spec <addons-src>/content/custom
 * Run it bare (flag off) and with -Dbots.attack.spec=true. The pure rules run in every case; the wiring is read from the source
 * (SmartAdventurers cannot class-init outside a world): the hook sits behind W10Skills.SPEC && MELEE in kitFromExchange, buys only
 * from bot stock, keeps the old weapon in the bank, draws no RNG; the specials are AfkTasks.specTick's (unchanged).
 */
public final class TestW10Spec {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW10Spec] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static int count(String s, String sub) { return s.split(java.util.regex.Pattern.quote(sub), -1).length - 1; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), hk = read(dir, "W10Hooks.java"), sk = read(dir, "W10Skills.java"), afk = read(dir, "AfkTasks.java");

        // F: the flag and the hourly line
        check("F flag: SPEC " + W10Skills.SPEC + " = -Dbots.attack.spec", W10Skills.SPEC == Boolean.getBoolean("bots.attack.spec"));
        check("F anyOn: includes SPEC", W10Skills.anyOn() == (W10Skills.ARROWS || W10Skills.SUPERHEAT || W10Skills.HERB_RUN || W10Skills.SPEC));
        String line = W10Skills.line(1_000L);
        check("F [W10] line: spec(...) only with the flag on (" + line + ")", line.contains(" spec(buys=") == W10Skills.SPEC);
        check("F defaults: 30% of (coins - 300) (W8 KIT_SHARE), one buy try an hour", W10Skills.SPEC_SHARE == 0.3 && W10Skills.SPEC_GAP_MS == 3_600_000L && W10Skills.SPEC_SHARE == W8Polish.KIT_SHARE);

        // A: the table
        int[][] R = W10Skills.SPEC_ROWS;
        check("A table: dragon scimitar, longsword, dagger at Attack 60", R.length == 3 && R[0][0] == 4587 && R[1][0] == 1305 && R[2][0] == 1215 && R[0][1] == 60 && R[1][1] == 60 && R[2][1] == 60);
        check("A index", W10Skills.specIndex(1305) == 1 && W10Skills.specIndex(1333) == -1);
        check("A the engine specials exist for all three (AfkTasks.specCost 55/25/25)", afk.contains("case 1215: case 1231: case 5680: case 5698: case 1305: return 25;") && afk.contains("case 4587: case 861: return 55;"));
        check("A specTick fires in every fight (unchanged)", afk.contains("if (p.inCombat()) specTick();"));

        // B: what to do
        check("B Attack 59: nothing", W10Skills.specAction(1333, false, true, -1, 59) == W10Skills.SP_NONE);
        check("B already wields one: nothing", W10Skills.specAction(4587, false, false, 0, 70) == W10Skills.SP_NONE && W10Skills.specAction(1215, false, false, -1, 60) == W10Skills.SP_NONE);
        check("B a mace on (weaponByFoe's crush pick): nothing", W10Skills.specAction(1432, true, false, 1, 70) == W10Skills.SP_NONE);
        check("B a non-table weapon (whip): nothing", W10Skills.specAction(4151, false, false, -1, 75) == W10Skills.SP_NONE);
        check("B a table scimitar and one owned: wield it", W10Skills.specAction(1333, false, true, 1, 60) == W10Skills.SP_WIELD);
        check("B a table scimitar, none owned: buy", W10Skills.specAction(1333, false, true, -1, 60) == W10Skills.SP_BUY);
        check("B empty hand: wield or buy", W10Skills.specAction(-1, false, false, 2, 60) == W10Skills.SP_WIELD && W10Skills.specAction(-1, false, false, -1, 60) == W10Skills.SP_BUY);

        // C: which one it buys
        boolean[] all = {true, true, true}, lostCity = {false, true, true}, none = {false, false, false};
        int[] stock = {3, 3, 3}, price = {100_000, 100_000, 30_000};
        check("C rich: the dragon scimitar", W10Skills.specPick(60, all, stock, price, 400_000, Long.MAX_VALUE) == 0);
        check("C 30% of (coins - 300) caps it: 200k coins -> the dagger", W10Skills.specPick(60, all, stock, price, 200_000, Long.MAX_VALUE) == 2);
        check("C no Monkey Madness -> the longsword", W10Skills.specPick(60, lostCity, stock, price, 400_000, Long.MAX_VALUE) == 1);
        check("C no quests -> none", W10Skills.specPick(60, none, stock, price, 9_000_000, Long.MAX_VALUE) == -1);
        check("C no bot stock -> the next row, else none", W10Skills.specPick(60, all, new int[]{0, 2, 0}, price, 400_000, Long.MAX_VALUE) == 1 && W10Skills.specPick(60, all, new int[]{0, 0, 0}, price, 9_000_000, Long.MAX_VALUE) == -1);
        check("C kitAboveRune's cap left limits it", W10Skills.specPick(60, all, stock, price, 400_000, 50_000) == 2 && W10Skills.specPick(60, all, stock, price, 400_000, 0) == -1);
        check("C Attack 59 -> none", W10Skills.specPick(59, all, stock, price, 9_000_000, Long.MAX_VALUE) == -1);

        // 0: flag off = no-op (source text)
        int kit = sa.indexOf("void kitFromExchange() {"), above = sa.indexOf("if (W8Polish.KIT_ABOVE_RUNE && styleOf(bot) == MELEE) try { got += w8KitAboveRune(); }", kit);
        int hook = sa.indexOf("if (W10Skills.SPEC && styleOf(bot) == MELEE) try { W10Hooks.spec(this); } catch (Throwable t) {", kit);
        int arrows = sa.indexOf("if (W10Skills.ARROWS && styleOf(bot) == RANGED) try { W10Hooks.arrows(this); }", kit);
        check("0 hook: in kitFromExchange after kitAboveRune, before arrows, behind SPEC && MELEE, in try/catch, got unchanged", kit > 0 && above > kit && hook > above && arrows > hook && !sa.substring(hook, arrows).contains("got +=") && !sa.substring(hook, arrows).contains("got = "));
        check("0 hook: exactly one spec call site", count(sa, "W10Hooks.spec(") == 1);
        int hs = hk.indexOf("// ------------------------------------------------------------------ bots.attack.spec");
        String spec = hs > 0 ? hk.substring(hs) : "";
        check("0 buy: bot stock only (buy -> Ledger.consume), no shop, no coins given", spec.contains("int got = buy(b, id, 1, \"kit\");") && !spec.contains("Wallet.pay(") && !spec.contains("Wallet.give(") && !spec.contains("Ledger.supply("));
        check("0 wield: the old weapon to the bank first (stowWeapon), never unarmed", spec.contains("b.toPack(id) && b.stowWeapon() && SmartAdventurers.wear(b.bot, id)") && spec.contains("b.wearMeleeWeapon()"));
        check("0 kitAboveRune: one budget when it is on", spec.contains("W8Polish.KIT_ABOVE_RUNE ? Math.max(0L, W8Polish.KIT_CAP - b.w8KitSpent) : Long.MAX_VALUE") && spec.contains("if (W8Polish.KIT_ABOVE_RUNE) b.w8KitSpent += paid;"));
        check("0 quests: questOk for owned and bought", count(spec, "SmartAdventurers.questOk(p, id)") == 2);
        check("0 no RNG: W10Hooks and W10Skills draw none", !hk.contains("RNG.") && !hk.contains("nextInt") && !sk.contains("RNG.") && !sk.contains("nextInt") && !sk.contains("Random"));

        System.out.println("[TestW10Spec] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
