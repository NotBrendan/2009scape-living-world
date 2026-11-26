package content.custom;

/**
 * P91 unit (pure, no world): the Slayer-level rule that keeps bots off NPCs whose hits core NPC.checkImpact would zero.
 * Run offline: java -cp <build>;server.jar content.custom.TestP91   (add -Dnav.p91Fix=true to also check the live flag reads on).
 * Checks: rule off = never blocks; rule on blocks need > slayer only; need 0 never blocks; the P91 flag mirrors -Dnav.p91Fix;
 * crawling hands (1648, 1655) need Slayer 5 in the server's Tasks table (skipped with a note if Tasks cannot load offline).
 */
public final class TestP91 {
    static int passes, fails;
    static void mark(String id, boolean ok, String why) { System.out.println("[TestP91] " + (ok ? "PASS " : "FAIL ") + id + " | " + why); if (ok) passes++; else fails++; }

    public static void main(String[] a) {
        // flag off: exactly the old behaviour, nothing is ever blocked
        mark("off-1", !AfkTasks.Runner.p91Blocks(false, 5, 1), "off, need 5, slayer 1 -> allowed");
        mark("off-2", !AfkTasks.Runner.p91Blocks(false, 99, 1), "off, need 99, slayer 1 -> allowed");
        // flag on: the level rule
        mark("on-1", AfkTasks.Runner.p91Blocks(true, 5, 1), "on, need 5, slayer 1 -> blocked");
        mark("on-4", AfkTasks.Runner.p91Blocks(true, 5, 4), "on, need 5, slayer 4 -> blocked");
        mark("on-5", !AfkTasks.Runner.p91Blocks(true, 5, 5), "on, need 5, slayer 5 -> allowed");
        mark("on-99", !AfkTasks.Runner.p91Blocks(true, 5, 99), "on, need 5, slayer 99 -> allowed");
        mark("on-none", !AfkTasks.Runner.p91Blocks(true, 0, 1), "on, no slayer need -> allowed");
        mark("on-banshee", AfkTasks.Runner.p91Blocks(true, 15, 14) && !AfkTasks.Runner.p91Blocks(true, 15, 15), "general: need 15 blocks 14, allows 15");
        boolean want = Boolean.parseBoolean(System.getProperty("nav.p91Fix", "false"));
        mark("flag", AfkTasks.Runner.P91 == want, "P91=" + AfkTasks.Runner.P91 + " for -Dnav.p91Fix=" + want);
        mark("null-npc", AfkTasks.Runner.slayerNeed(null) == 0, "slayerNeed(null) == 0");
        try {
            content.global.skill.slayer.Tasks t1 = content.global.skill.slayer.Tasks.forId(1648), t2 = content.global.skill.slayer.Tasks.forId(1655);
            mark("tasks", t1 != null && t2 != null && t1.levelReq == 5 && t2.levelReq == 5, "crawling hand 1648/1655 levelReq 5");
        } catch (Throwable t) { System.out.println("[TestP91] note: Tasks not loadable offline (" + t + "), tasks check skipped"); }
        System.out.println("[TestP91] RESULT " + (fails == 0 ? "PASS" : "FAIL") + " passes=" + passes + " fails=" + fails);
        if (fails > 0) System.exit(1);
    }
}
