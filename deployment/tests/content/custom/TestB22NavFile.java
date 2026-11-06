package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

import java.io.File;

/**
 * Trial only (-Dtest.b22navfile=true -Dsmartbots.count=0): batch 22, the data/survey/navbuild.request trigger
 * (NavGrid.buildFileCheck, called once a minute from NavWalker.watch). The harness copies survey-pristine per run, so the
 * grid this build saves never outlives the run.
 *   A: request written -> within ~2 minutes it is gone, one navbuild.done-* exists, NavGrid.building is true.
 *   B: request written again while that build runs -> it stays the whole build and no second done file appears.
 *   C: after the build ends -> the next minute check takes it (done files 2, building again). DONE right away; the second
 *      build is cut off by the harness stop (nothing saved).
 * With -Dnav.buildfile=false (kill switch): K: the request stays for ~2.5 minute checks, no build starts, no done file.
 * Expected log lines: "[Nav] navbuild.request found: starting NavGrid.build(null)" (2 normal, 0 kill switch) and
 * "[Nav] navbuild.request found while a build is running: left for a later minute" (exactly 1 normal, 0 kill switch).
 */
public final class TestB22NavFile implements StartupListener {
    static void log(String s) { System.out.println("[TestB22NavFile] " + s); }
    static File req() { return NavWalker.survey("navbuild.request"); }
    static int doneFiles() {
        File[] fs = req().getParentFile().listFiles(); int c = 0;
        if (fs != null) for (File f : fs) if (f.getName().startsWith("navbuild.done-")) c++;
        return c;
    }
    static boolean write() {
        try { File f = req(); f.getParentFile().mkdirs(); java.nio.file.Files.write(f.toPath(), "trial\n".getBytes()); return true; }
        catch (Throwable e) { log("could not write the request: " + e); return false; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b22navfile")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, ticks, done0; boolean stayed = true, sawBuild; int fails;
            void result(String name, boolean pass, String what) { if (!pass) fails++; log("RESULT " + name + " " + (pass ? "PASS" : "FAIL") + ": " + what); }
            boolean finish() { log("summary: " + (fails == 0 ? "PASS" : fails + " FAIL") + " (buildfile switch " + (NavGrid.BUILD_FILE_ON ? "on" : "off") + ")"); req().delete(); log("DONE"); return true; }
            @Override public boolean pulse() {
                try {
                    t++; ticks++;
                    if (phase == 0) {
                        if (t < 150 || NavGrid.building) { if (t > 1200) { log("RESULT setup FAIL: a build still running at tick 1200"); return finish(); } return false; } // past the boot build window
                        req().delete(); done0 = doneFiles();
                        if (!write()) return finish();
                        log("request written at tick " + t + " (done files before: " + done0 + ", switch " + (NavGrid.BUILD_FILE_ON ? "on" : "off") + ")");
                        phase = NavGrid.BUILD_FILE_ON ? 1 : 9; ticks = 0; return false;
                    }
                    if (phase == 9) { // kill switch
                        if (NavGrid.building) sawBuild = true;
                        if (ticks < 250) return false; // ~150 s: at least two minute checks
                        result("K", req().exists() && !sawBuild && doneFiles() == done0, "request still there " + req().exists() + ", a build started " + sawBuild + ", done files " + done0 + " -> " + doneFiles());
                        return finish();
                    }
                    if (phase == 1) {
                        if (!NavGrid.building && ticks < 220) return false;
                        result("A", NavGrid.building && !req().exists() && doneFiles() == done0 + 1, "after " + ticks + " ticks: building " + NavGrid.building + ", request gone " + !req().exists() + ", done files " + done0 + " -> " + doneFiles());
                        if (!NavGrid.building || !write()) return finish();
                        log("B: request written again during the build");
                        phase = 2; ticks = 0; return false;
                    }
                    if (phase == 2) {
                        if (NavGrid.building && doneFiles() == done0 + 2) { // the build ended and the minute check took the request before this pulse ran
                            result("B", stayed, "build ended and the request was taken in the same tick (" + ticks + " ticks), request stayed the whole build " + stayed);
                            phase = 3; ticks = 0; return false;
                        }
                        if (NavGrid.building) {
                            if (!req().exists() || doneFiles() != done0 + 1) stayed = false;
                            if (ticks % 200 == 0) log("B tick " + ticks + ": building, request there " + req().exists() + ", done files " + doneFiles());
                            if (ticks < 1500) return false;
                        }
                        result("B", stayed && !NavGrid.building && req().exists(), "build ended " + !NavGrid.building + " after " + ticks + " ticks, request stayed the whole build " + stayed + ", done files " + doneFiles());
                        if (NavGrid.building || !req().exists()) return finish();
                        phase = 3; ticks = 0; return false;
                    }
                    if (phase == 3) {
                        if (!NavGrid.building && ticks < 220) return false;
                        result("C", NavGrid.building && !req().exists() && doneFiles() == done0 + 2, "after " + ticks + " ticks: building again " + NavGrid.building + ", request gone " + !req().exists() + ", done files " + doneFiles());
                        return finish();
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); return finish(); }
                return false;
            }
        });
    }
}
