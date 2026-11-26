package content.custom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * P66 -Dscenes.rescueEar unit test, no world (plain main). Run three times: flag unset, -Dscenes.rescueEar=false, =true.
 *   java -cp <tests build> [-Dscenes.rescueEar=...] -Dp66.src=<addons-src/content/custom> content.custom.TestScenesRescueEar
 * Checks: EAR matches the flag; the ear lines carry no meta words (stuck/path/wall/teleport/fence/fix); the old lines are
 * kept for the flag off; the per-human cap (2 per 30 min, window slides); forget() resets; busy(null) is busy; held()
 * logs at most once a minute; busy-list grep (every `mill != 0` / `mill == 0` line in SmartAdventurers, Companions,
 * PlayerTrades, Spectacle and Scenes names escorting, bone and summon, except the dispatcher and the Companions self-test).
 */
public final class TestScenesRescueEar {
    static int pass, fail;

    static void check(String name, boolean ok, String why) {
        if (ok) { pass++; System.out.println("[TestScenesRescueEar] " + name + " RESULT PASS"); }
        else { fail++; System.out.println("[TestScenesRescueEar] " + name + " RESULT FAIL " + why); }
    }

    public static void main(String[] args) throws Exception {
        String prop = System.getProperty("scenes.rescueEar");
        boolean want = "true".equalsIgnoreCase(prop), on = Scenes.RescueTalk.EAR;
        System.out.println("[TestScenesRescueEar] flag=" + prop + " EAR=" + on);
        check("1 flag read", on == want, "prop=" + prop + " EAR=" + on);

        String meta = "(?i).*(stuck|path|wall|teleport|fence|fix|bug|road).*";
        String bad = "";
        for (String s : Scenes.STUCK_EAR) if (s.matches(meta) || !s.contains("%s")) bad += s + " | ";
        for (String s : Scenes.LANDING_EAR) if (s.matches(meta) || !s.contains("%s")) bad += s + " | ";
        check("2 ear lines in-world", bad.isEmpty() && Scenes.STUCK_EAR.length >= 6 && Scenes.LANDING_EAR.length >= 6, bad);
        check("3 old lines kept for flag off", Scenes.STUCK.length == 6 && Scenes.LANDING.length == 6 && Scenes.STUCK[2].contains("Teleport tax"), "changed");

        long t0 = 1_000_000_000L, min = 60_000L;
        String k = "hearer-a";
        check("4 count empty", Scenes.RescueTalk.count(k, t0) == 0, "n=" + Scenes.RescueTalk.count(k, t0));
        Scenes.RescueTalk.note(k, t0); Scenes.RescueTalk.note(k, t0 + 5 * min);
        check("5 two heard = at cap", Scenes.RescueTalk.count(k, t0 + 6 * min) == Scenes.RescueTalk.HEAR_CAP, "n=" + Scenes.RescueTalk.count(k, t0 + 6 * min));
        check("6 window slides", Scenes.RescueTalk.count(k, t0 + 31 * min) == 1 && Scenes.RescueTalk.count(k, t0 + 36 * min) == 0, "n31=" + Scenes.RescueTalk.count(k, t0 + 31 * min));
        Scenes.RescueTalk.note(k, t0 + 40 * min);
        check("7 oldest replaced", Scenes.RescueTalk.count(k, t0 + 41 * min) == 1, "n=" + Scenes.RescueTalk.count(k, t0 + 41 * min));
        Scenes.RescueTalk.HEARD.remove(k);
        check("8 reset forgets", Scenes.RescueTalk.count(k, t0 + 41 * min) == 0 && Scenes.RescueTalk.HEARD.isEmpty(), "left");
        Scenes.RescueTalk.forget(null);
        check("9 busy(null) busy", Scenes.RescueTalk.busy(null, t0), "false");

        Scenes.RescueTalk.held("busy", t0); String l1 = Scenes.lastLine;
        Scenes.RescueTalk.held("cap", t0 + 10_000L); String l2 = Scenes.lastLine;
        Scenes.RescueTalk.held("cap", t0 + 61_000L); String l3 = Scenes.lastLine;
        check("10 held log rate-limited", l1 != null && l1 == l2 && l3 != l2 && Scenes.RescueTalk.heldBusy == 1 && Scenes.RescueTalk.heldCap == 2 && l3.startsWith("rescue-ear held cap"), "l1=" + l1 + " l3=" + l3);

        String src = System.getProperty("p66.src");
        if (src == null) check("11 busy-list grep", false, "-Dp66.src not set");
        else {
            int sites = 0, badN = 0; StringBuilder miss = new StringBuilder();
            for (String f : new String[] { "SmartAdventurers.java", "Companions.java", "PlayerTrades.java", "Spectacle.java", "Scenes.java" }) {
                List<String> lines = Files.readAllLines(new File(src, f).toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i);
                    if (!l.matches(".*\\bmill (!=|==) 0.*")) continue;
                    if (l.contains("millTick(now); return;")) continue;                       // exempt: the think dispatcher (the mill job itself)
                    if (f.equals("Companions.java") && l.contains("AfkTasks.RUNNING.containsKey(key(br.bot)) || PlayerTrades")) continue; // exempt: self-test pick
                    sites++;
                    if (!l.contains("Scenes.Greet.escorting(") || !l.matches(".*\\bbone (!=|==) 0.*") || !l.matches(".*\\bsummon (!=|==) 0.*")) { badN++; miss.append(f).append(':').append(i + 1).append(' '); }
                }
            }
            check("11 busy-list grep", badN == 0 && sites >= 11, "sites=" + sites + " missing=" + miss);
        }
        // P69 #15: a human who logs out is never forget()-ed; stale entries are pruned (they count 0 anyway), live ones kept
        Scenes.RescueTalk.HEARD.clear(); Scenes.RescueTalk.nextPrune = 0;
        long t1 = 2_000_000_000L;
        Scenes.RescueTalk.note("gone-a", t1); Scenes.RescueTalk.note("gone-b", t1 + min); Scenes.RescueTalk.note("here-c", t1 + 20 * min);
        int c0 = Scenes.RescueTalk.HEARD.size();
        int pruned = Scenes.RescueTalk.prune(t1 + 35 * min); // a: 35 min old, b: 34, c: 15
        boolean p12 = c0 == 3 && pruned == 2 && Scenes.RescueTalk.HEARD.size() == 1 && Scenes.RescueTalk.count("here-c", t1 + 35 * min) == 1;
        Scenes.RescueTalk.note("late-d", t1 + 60 * min); // note() prunes (rate-limited): c is stale by then
        p12 &= Scenes.RescueTalk.HEARD.size() == 1 && Scenes.RescueTalk.HEARD.containsKey("late-d");
        Scenes.RescueTalk.HEARD.clear();
        check("12 stale HEARD entries pruned (no human logout hook)", p12, "c0=" + c0 + " pruned=" + pruned + " left=" + Scenes.RescueTalk.HEARD.keySet());

        System.out.println("[TestScenesRescueEar] " + (fail == 0 ? "ALL PASS" : "FAILED") + " pass=" + pass + " fail=" + fail);
        System.out.println("[TestScenesRescueEar] DONE");
        System.exit(fail == 0 ? 0 : 1);
    }
}
