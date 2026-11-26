package content.custom;

import core.api.StartupListener;

/**
 * Slayer touches unit: pure progressLine / taskReply cases, no world needed. Enable with -Dtest.slayerlines=true, or run main().
 */
public final class TestSlayerLines implements StartupListener {
    static int passes, fails;
    static void mark(String id, boolean ok, String why) { System.out.println("[TestSlayerLines] " + (ok ? "PASS " : "FAIL ") + id + " | " + why); if (ok) passes++; else fails++; }
    static boolean ascii(String s) { for (char c : s.toCharArray()) if (c < 32 || c > 126) return false; return true; }

    @Override public void startup() {
        if (!"true".equalsIgnoreCase(System.getProperty("test.slayerlines", ""))) return;
        run();
    }

    public static void main(String[] a) { run(); System.exit(fails == 0 ? 0 : 1); }

    static void run() {
        String h = Slayer.progressLine("CRAWLING_HAND", 10, 20, "Turael");
        mark("half", h.startsWith("Halfway"), h);
        String l = Slayer.progressLine("CRAWLING_HAND", 3, 20, "Turael");
        mark("last", l.startsWith("Last few"), l);
        String z = Slayer.progressLine("CRAWLING_HAND", 0, 20, "Turael");
        mark("zero", z.startsWith("Last few"), z);
        String n = Slayer.progressLine("CRAWLING_HAND", 7, 20, "Turael");
        mark("plain", n.equals("7 crawling hand to go."), n);
        String e = Slayer.progressLine(null, 7, 0, null);
        mark("nulls", !e.isEmpty() && ascii(e), e);
        mark("ascii", ascii(h) && ascii(l) && ascii(n), "all ascii");
        mark("reply", Slayer.taskReply("BANSHEE", 12, "Turael").equals("Turael has me on banshee, 12 left."), Slayer.taskReply("BANSHEE", 12, "Turael"));
        mark("none", Slayer.taskReply(null, 0, "Turael").equals("No task right now."), "no task");
        System.out.println("[TestSlayerLines] done passes=" + passes + " fails=" + fails);
    }
}
