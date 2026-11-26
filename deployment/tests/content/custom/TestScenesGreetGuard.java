package content.custom;

import core.game.world.map.Location;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * P57 -Dscenes.greetGuard unit test, no world (plain main). Run three times: flag unset, -Dscenes.greetGuard=false, =true.
 *   java -cp <tests build> [-Dscenes.greetGuard=...] -Dp57.src=<addons-src/content/custom> content.custom.TestScenesGreetGuard
 * Checks: GUARD matches the flag; redact() stable, never the name, player#xxxxxx; walk-back table (far/z/death/busy or
 * over 20 tiles -> skipped, guard on only); escorting/drop no-ops; busy-list grep (every `mill != 0` / `mill == 0`
 * line names escorting (10 sites at train-15), except the exempt dispatcher and the Companions self-test).
 */
public final class TestScenesGreetGuard {
    static int pass, fail;

    static void check(String name, boolean ok, String why) {
        if (ok) { pass++; System.out.println("[TestScenesGreetGuard] " + name + " RESULT PASS"); }
        else { fail++; System.out.println("[TestScenesGreetGuard] " + name + " RESULT FAIL " + why); }
    }

    public static void main(String[] args) throws Exception {
        String prop = System.getProperty("scenes.greetGuard");
        boolean want = "true".equalsIgnoreCase(prop);
        boolean on = Scenes.Greet.GUARD;
        System.out.println("[TestScenesGreetGuard] flag=" + prop + " GUARD=" + on);
        check("1 flag read", on == want, "prop=" + prop + " GUARD=" + on);

        String a = Scenes.Greet.redact("newbie42"), b = Scenes.Greet.redact("newbie42"), c = Scenes.Greet.redact("other");
        check("2 redact stable", a.equals(b), a + " vs " + b);
        check("3 redact hides name", !a.contains("newbie42") && a.matches("player#[0-9a-f]{6}"), a);
        check("4 redact differs", !a.equals(c), a + " == " + c);
        check("5 redact null", Scenes.Greet.redact((core.game.node.entity.player.Player) null).equals("player#null"), "null");

        Location g = Location.create(3222, 3218, 0), near = Location.create(3228, 3220, 0), far = Location.create(3260, 3250, 0);
        boolean[] skips = {
            Scenes.Greet.skipWalkBack("far", near, g), Scenes.Greet.skipWalkBack("z", near, g),
            Scenes.Greet.skipWalkBack("death", near, g), Scenes.Greet.skipWalkBack("busy", near, g),
            Scenes.Greet.skipWalkBack("timeout", far, g) };
        boolean allSkip = true, noneSkip = true;
        for (boolean s : skips) { allSkip &= s; noneSkip &= !s; }
        boolean keep = !Scenes.Greet.skipWalkBack("timeout", near, g) && !Scenes.Greet.skipWalkBack("dist10", near, g);
        if (on) check("6 walk-back skipped (far/z/death/busy/>20)", allSkip && keep, "skips=" + java.util.Arrays.toString(skips) + " keep=" + keep);
        else check("6 walk-back unchanged (flag off)", noneSkip && keep, "skips=" + java.util.Arrays.toString(skips));

        check("7 escorting(null) false", !Scenes.Greet.escorting(null), "true");
        int ends = Scenes.escortEnds;
        Scenes.Greet.drop(null, "death");
        check("8 drop(null) no-op", Scenes.escortEnds == ends && Scenes.Greet.drops == 0, "ends=" + Scenes.escortEnds);

        String src = System.getProperty("p57.src");
        if (src == null) check("9 busy-list grep", false, "-Dp57.src not set");
        else {
            int sites = 0, bad = 0; StringBuilder miss = new StringBuilder();
            for (String f : new String[] { "SmartAdventurers.java", "Companions.java", "PlayerTrades.java", "Spectacle.java" }) {
                List<String> lines = Files.readAllLines(new File(src, f).toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i);
                    if (!l.matches(".*\\bmill (!=|==) 0.*")) continue;
                    if (l.contains("millTick(now); return;")) continue;                       // exempt: the think dispatcher (the mill job itself)
                    if (f.equals("Companions.java") && l.contains("AfkTasks.RUNNING.containsKey(key(br.bot)) || PlayerTrades")) continue; // exempt: self-test pick
                    sites++;
                    if (!l.contains("Scenes.Greet.escorting(")) { bad++; miss.append(f).append(':').append(i + 1).append(' '); }
                }
            }
            check("9 busy-list grep", bad == 0 && sites >= 10, "sites=" + sites + " missing=" + miss);
        }
        System.out.println("[TestScenesGreetGuard] " + (fail == 0 ? "ALL PASS" : "FAILED") + " pass=" + pass + " fail=" + fail);
        System.out.println("[TestScenesGreetGuard] DONE");
        System.exit(fail == 0 ? 0 : 1);
    }
}
