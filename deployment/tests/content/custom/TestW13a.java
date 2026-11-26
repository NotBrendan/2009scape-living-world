package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W13a lane A (-Dbots.wc.trees2, -Dbots.craft.spin, -Dbots.craft.vials, -Dbots.cook.twoStep), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW13a <addons-src>/content/custom
 * Run it bare (flags off) and with the flags on. Pure rules run in every case; the AfkTasks wiring is read from the source.
 */
public final class TestW13a {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW13a] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String af = read(dir, "AfkTasks.java"), sk = read(dir, "W13aSkills.java");

        check("F flags follow their properties", W13aSkills.TREES2 == Boolean.getBoolean("bots.wc.trees2") && W13aSkills.SPIN == Boolean.getBoolean("bots.craft.spin")
            && W13aSkills.VIALS == Boolean.getBoolean("bots.craft.vials") && W13aSkills.TWO_STEP == Boolean.getBoolean("bots.cook.twoStep"));
        check("F anyOn only with a flag", W13aSkills.anyOn() == (W13aSkills.TREES2 || W13aSkills.SPIN || W13aSkills.VIALS || W13aSkills.TWO_STEP));
        check("F line names only flags on: " + W13aSkills.line(), W13aSkills.line().contains(" trees2") == W13aSkills.TREES2 && W13aSkills.line().contains(" spin") == W13aSkills.SPIN);

        // T: trees2 rows
        check("T off: no extra spots", W13aSkills.extraSpots(false, W13aSkills.KIND_YEW) == 0 && W13aSkills.extraSpots(false, W13aSkills.KIND_WILLOW) == 0);
        check("T on: 3 yew + 2 willow spots", W13aSkills.extraSpots(true, W13aSkills.KIND_YEW) == 3 && W13aSkills.extraSpots(true, W13aSkills.KIND_WILLOW) == 2);
        check("T names match spots", W13aSkills.TREE_NAMES.length == W13aSkills.TREE_SPOTS.length);
        boolean far = true; for (int[] s : W13aSkills.TREE_SPOTS) for (int[] h : W13aSkills.HARDWOOD_GATED) if (Math.abs(s[1] - h[0]) < 50 && Math.abs(s[2] - h[1]) < 50) far = false;
        check("T no spot near a gated hardwood tree", far);

        // S: spin rule
        check("S under Crafting 10: none", W13aSkills.spinQty(9, 500, 50, 100) == 0);
        check("S flax dearer than a bowstring: none", W13aSkills.spinQty(40, 500, 120, 100) == 0);
        check("S a pack at most", W13aSkills.spinQty(40, 500, 50, 100) == 28);
        check("S floor kept", W13aSkills.spinQty(40, 40, 50, 100) == 12 && W13aSkills.spinQty(40, 20, 50, 100) == 0);

        // C: two-step cook rule
        check("C meat pie at 20 with inputs", W13aSkills.twoStepProduct(20, 1, 1) == W13aSkills.MEAT_PIE);
        check("C none under 20 or without inputs", W13aSkills.twoStepProduct(19, 5, 5) == -1 && W13aSkills.twoStepProduct(50, 0, 5) == -1 && W13aSkills.twoStepProduct(50, 5, 0) == -1);

        // W: wiring guarded, no RNG / shop / coins in W13a
        check("W trees2 hook behind its flag in a try", af.contains("if (W13aSkills.TREES2) try { W13aHooks.trees2(SPOTS); }"));
        check("W spin wheel behind its flag in a try", af.contains("if (W13aSkills.SPIN) try { c.add(W13aHooks.wheel()); }"));
        check("W trees2 hook right after the MAGIC row", af.indexOf("SPOTS.put(Task.MAGIC") < af.indexOf("W13aHooks.trees2") && af.indexOf("W13aHooks.trees2") < af.indexOf("SPOTS.put(Task.BEST_TREE"));
        check("W no RNG, shop or coins in W13aSkills", !sk.contains("RandomFunction") && !sk.contains("Random(") && !sk.contains("addItem") && !sk.contains("995"));

        System.out.println("[TestW13a] " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
