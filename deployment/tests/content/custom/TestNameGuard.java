package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Train-3 audit finding 2: NameGuard's pure helpers and its wiring into SmartAdventurers. Plain java, no server or world needed
 * (SmartAdventurers itself cannot be loaded outside a world, so its side is checked in the source; the behaviour is TestHumanLook
 * U6/U7). Run with NO -Dbots.* flags:
 *   javac --release 11 -cp server.jar -d out deployment/addons-src/content/custom/*.java deployment/addons-src/core/game/ge/*.java deployment/tests/content/custom/TestNameGuard.java
 *   java -cp "out;server.jar" content.custom.TestNameGuard deployment/addons-src/content/custom/SmartAdventurers.java
 * Checks:
 *   D: the guard is off by default, counters 0, clashWith(null) null.
 *   R: blocksRestore only when on and a player save exists.
 *   C: clash needs all five: on, something indexed, not the bot itself, active, a real player (or stand-in).
 *   W: SmartAdventurers wiring: think asks clashWith before any other return, directBot asks blocksRestore with isPlayerSave,
 *      logOut returns the Profile, nameClash renames the profile and moves lots/owed coins and the quest book to the new name.
 * Prints one PASS/FAIL line per check and "[TestNameGuard] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestNameGuard {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestNameGuard] " + (ok ? "PASS " : "FAIL ") + what); }

    public static void main(String[] args) throws Exception {
        check("D guard off by default " + !NameGuard.ON + ", clashes " + NameGuard.clashes + ", blocked restores " + NameGuard.blockedRestores + ", clashWith(null) null",
                !NameGuard.ON && NameGuard.clashes == 0 && NameGuard.blockedRestores == 0 && NameGuard.clashWith(null) == null);

        check("R blocksRestore: off/save false, on/no save false, on/save true",
                !NameGuard.blocksRestore(false, true) && !NameGuard.blocksRestore(true, false) && !NameGuard.blocksRestore(false, false) && NameGuard.blocksRestore(true, true));

        boolean all = NameGuard.clash(true, true, false, true, true);
        boolean each = !NameGuard.clash(false, true, false, true, true) && !NameGuard.clash(true, false, false, true, true) && !NameGuard.clash(true, true, true, true, true)
                && !NameGuard.clash(true, true, false, false, true) && !NameGuard.clash(true, true, false, true, false);
        check("C clash: all five true -> " + all + "; any one false -> never", all && each);

        String src = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "deployment/addons-src/content/custom/SmartAdventurers.java")), StandardCharsets.UTF_8);
        int think = src.indexOf("void think() {"), hook = src.indexOf("NameGuard.clashWith(bot)", think), firstReturn = src.indexOf("return;", src.indexOf("checkDeath();", think));
        boolean w1 = think > 0 && hook > think && hook < firstReturn; // the hook comes before any other return after checkDeath
        boolean w2 = src.contains("NameGuard.blocksRestore(NameGuard.ON, Ledger.isPlayerSave(wanted.toLowerCase(Locale.ROOT)))") && src.indexOf("NameGuard.blocksRestore") > src.indexOf("static AIPlayer directBot(");
        boolean w3 = src.contains("static Profile logOut(AIPlayer bot, Brain brain)");
        int nc = src.indexOf("static void nameClash(AIPlayer bot, Brain brain, Player holder)");
        boolean w4 = nc > 0 && src.indexOf("Profile p = logOut(bot, brain);", nc) > nc && src.indexOf("p.name = fresh; p.direct = true;", nc) > nc
                && src.indexOf("Ledger.rename(old, fresh)", nc) > nc && src.indexOf("QuestBook.rename(old, fresh)", nc) > nc && src.indexOf("k.equalsIgnoreCase(old) || nameTaken(k)", nc) > nc;
        check("W wiring: think hook before any return " + w1 + ", directBot guard " + w2 + ", logOut returns the profile " + w3 + ", nameClash renames and moves lots/owed/quest book " + w4, w1 && w2 && w3 && w4);

        System.out.println("[TestNameGuard] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
