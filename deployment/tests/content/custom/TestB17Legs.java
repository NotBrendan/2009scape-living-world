package content.custom;

import core.ServerConstants;
import core.api.StartupListener;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;

/**
 * Trial only (-Dtest.b17legs=true): batch 17 item2, the weekly legs.jsonl rotation. The harness side prepares data/survey before
 * boot: legs.jsonl last modified three weeks ago and five old archives legs-2026-W30..W34.jsonl.gz. 60 s after boot (the boot
 * rotation has run) the folder is listed: RESULT boot. Then the save-cycle path: one line is appended to legs.jsonl, its time set
 * two weeks back (test-side, File.setLastModified) and Surveyors.flush() called on a thread: RESULT flush. Expected before: no
 * archive made, legs.jsonl untouched; after: boot makes legs-2026-W40 and prunes to 4, flush makes legs-2026-W41 and prunes to 4.
 * RouteMap training over the archives is checked by a second boot with routemap.json removed (the "[RouteMap] trained on" line).
 */
public final class TestB17Legs implements StartupListener {
    static void log(String s) { System.out.println("[TestB17Legs] " + s); }
    static File dir() { return new File(ServerConstants.DATA_PATH, "survey"); }
    static String listing() {
        File[] fs = dir().listFiles((d, n) -> n.startsWith("legs")); if (fs == null) return "none";
        Arrays.sort(fs); StringBuilder b = new StringBuilder();
        for (File f : fs) b.append(f.getName()).append(" ").append(f.length() / 1024).append(" KB").append(f.getName().equals("legs.jsonl") ? " (week " + week(f.lastModified()) + ")" : "").append("; ");
        return b.toString();
    }
    static String week(long ms) {
        java.time.LocalDate d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return d.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17legs")) return;
        log("at boot: " + listing());
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(60_000);
                log("RESULT boot: " + listing());
                File legs = new File(dir(), "legs.jsonl");
                try (FileWriter w = new FileWriter(legs, true)) { w.write("{\"t\":0,\"bot\":\"TestB17Legs\",\"path\":[]}\n"); }
                legs.setLastModified(System.currentTimeMillis() - 14L * 86_400_000L);
                log("legs.jsonl set to week " + week(legs.lastModified()) + "; calling Surveyors.flush()");
                Surveyors.flush();
                log("RESULT flush: " + listing());
            } catch (Throwable e) { log("error " + e); }
            log("DONE");
        }, "TestB17Legs"); t.setDaemon(true); t.start();
    }
}
