package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W15 (2026-11-20): FightRules.safeHold (-Dbots.fight.safeRetreat) and its wiring in AfkTasks.Runner.recover(). Plain java,
 * no server or world needed (AfkTasks cannot be loaded outside a world, so the decision lives in FightRules).
 *   java -cp "out;server.jar" content.custom.TestSafeRetreat off <addons-src/content/custom>
 *   java -Dbots.fight.safeRetreat=true -cp "out;server.jar" content.custom.TestSafeRetreat on <addons-src/content/custom>
 * Checks: D flag state; R decision rows; M a step model of a poisoned, foodless bot at the bank (off: dies, on: lives);
 * W wiring in the source (one guarded call before the first finish in recover(), no RNG in safeHold).
 * Prints one PASS/FAIL line per check and "[TestSafeRetreat] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestSafeRetreat {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestSafeRetreat] " + (ok ? "PASS " : "FAIL ") + what); }

    /**
     * The bank-tile model: poison hits 2 every 6 calls, recover() asks safeHold each call. bankAnti = antipoisons in its bank
     * (taken at the booth, drunk on the next call, by the player rules); none = no help. Returns the hp after `calls`, 0 = dead.
     */
    static int model(boolean on, int hp, int max, int calls, int bankAnti) {
        boolean poisoned = true, carried = false; int ticks = 0;
        for (int i = 0; i < calls && hp > 0; i++) {
            if (poisoned && i % 6 == 5) hp = Math.max(0, hp - 2);
            if (on && poisoned && carried) { carried = false; poisoned = false; } // eatIfNeeded drinks it
            if (on && poisoned && !carried && bankAnti > 0) { bankAnti--; carried = true; }
            int d = FightRules.safeHold(on, true, hp, max, poisoned, carried, ticks, 200);
            if (d != FightRules.SAFE_HOLD) { if (d == FightRules.SAFE_GIVE_UP) on = false; continue; } // trip over: nothing looks after the bot
            ticks++;
        }
        return hp;
    }

    public static void main(String[] a) throws Exception {
        boolean on = a.length > 0 && a[0].equals("on");
        check("D flag " + (on ? "on" : "off"), FightRules.SAFE_RETREAT == on && FightRules.SAFE_MAX == 200);

        boolean offOk = true;
        for (int hp = 1; hp <= 40; hp++) for (int p = 0; p < 2; p++) for (int t = 0; t < 400; t += 199)
            offOk &= FightRules.safeHold(false, true, hp, 40, p == 1, false, t, 200) == FightRules.SAFE_GO && FightRules.safeHold(true, false, hp, 40, p == 1, false, t, 200) == FightRules.SAFE_GO;
        check("R off or a player: always go", offOk);
        check("R healthy and clean: go", FightRules.safeHold(true, true, 20, 40, false, false, 0, 200) == FightRules.SAFE_GO);
        check("R under half health: hold", FightRules.safeHold(true, true, 19, 40, false, false, 0, 200) == FightRules.SAFE_HOLD);
        check("R poisoned at full health, help carried: hold", FightRules.safeHold(true, true, 40, 40, true, true, 0, 200) == FightRules.SAFE_HOLD);
        check("R poisoned, no help: give up at once (no free cure)", FightRules.safeHold(true, true, 10, 40, true, false, 0, 200) == FightRules.SAFE_GIVE_UP);
        check("R poisoned, quarter health, help carried: hold (it drinks or eats)", FightRules.safeHold(true, true, 10, 40, true, true, 0, 200) == FightRules.SAFE_HOLD);
        check("R cap reached: give up", FightRules.safeHold(true, true, 15, 40, false, false, 200, 200) == FightRules.SAFE_GIVE_UP);
        check("R cap reached, poisoned, help carried: still give up (the cap always ends a hold)", FightRules.safeHold(true, true, 5, 40, true, true, 200, 200) == FightRules.SAFE_GIVE_UP);
        boolean ends = true;
        for (int hp = 1; hp <= 40; hp++) for (int f = 0; f < 4; f++) ends &= FightRules.safeHold(true, true, hp, 40, (f & 1) == 1, (f & 2) == 2, 200, 200) != FightRules.SAFE_HOLD;
        check("R no row holds at the cap", ends);

        check("M off: the poisoned bot dies on the bank tile", model(false, 12, 40, 400, 1) == 0);
        check("M on, antipoison in the bank: the poisoned bot lives", model(true, 12, 40, 400, 1) > 0);
        check("M on, nothing in the bank: no free cure, it dies as before", model(true, 12, 40, 400, 0) == 0);

        if (a.length > 1) {
            String src = new String(Files.readAllBytes(Paths.get(a[1], "AfkTasks.java")), StandardCharsets.UTF_8);
            int rec = src.indexOf("void recover() {"), call = src.indexOf("if (FightRules.SAFE_RETREAT && safeHold()) return;", rec), fin = src.indexOf("finish(", rec);
            check("W recover() asks safeHold behind the flag before its first finish", rec > 0 && call > rec && call < fin);
            check("W one call site", src.indexOf("safeHold()", call + 40) < 0 || src.indexOf("boolean safeHold()") > 0 && src.split("safeHold\\(\\)").length == 3);
            int sh = src.indexOf("boolean safeHold() {");
            String body = sh < 0 ? "RNG" : src.substring(sh, rec);
            check("W safeHold uses no RNG", !body.contains("RNG") && !body.contains("Random") && !body.contains("BotChat.pick"));
            String fr = new String(Files.readAllBytes(Paths.get(a[1], "FightRules.java")), StandardCharsets.UTF_8);
            check("W safeHold gives nothing for free", !body.contains("curePoison") && !body.contains("setLifepoints") && !body.contains("heal(") && !src.substring(src.indexOf("int takeBankAntipoison()"), sh).contains("curePoison"));
            check("W flag read in FightRules only", fr.contains("Boolean.getBoolean(\"bots.fight.safeRetreat\")") && !src.contains("\"bots.fight.safeRetreat\""));
        }
        System.out.println("[TestSafeRetreat] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
