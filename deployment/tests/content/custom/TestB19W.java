package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Trial only (-Dtest.b19w=true -Dsurvey.offground=1 -Dsurvey.tunnels=0 -Dsurvey.stairs=0 -Dsurvey.doors=0 -Dsurvey.count=0
 * -Dtraders.fast=true -Dsmartbots.count=2 -Dsmartbots.high=0; SEED=live): batch 19b part 1, survey writes off the game thread.
 *  W1 static: the compiled Explorers*.class / OffGround*.class reference no java/io/FileWriter, FileOutputStream, java/nio/file/Files
 *     (their only writes are Explorers.write -> SurveyWriter.append).
 *  W2 cost: 200 Explorers.write calls in one game pulse (expect < 2 ms total), SurveyWriter.onGameThreadIO unchanged (0).
 *  W3 an off-ground explorer run: wait for 3 visits (OffGround.VISITS), then flushNow(3000) from a test thread: offgrid.jsonl lines
 *     since the start == visits, times in order; stairs.jsonl new lines in time order; every new offgrid key is in OffGround.DONE or
 *     DANGER (the in-memory truth, updated before the append); onGameThreadIO still 0; the 200 W2 lines on disk in order.
 * BEFORE (19a): W1 finds FileWriter in Explorers; W2 writes on the game thread (SurveyWriter.written does not move).
 */
public final class TestB19W implements StartupListener {
    static void log(String s) { System.out.println("[TestB19W] " + s); }
    static volatile int W1BAD = -1;
    static File survey(String n) { return new File(new File(ServerConstants.DATA_PATH, "survey"), n); }

    static int refs(String cls, String what) {
        try (InputStream in = TestB19W.class.getClassLoader().getResourceAsStream(cls.replace('.', '/') + ".class")) {
            if (in == null) return -1;
            String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            int n = 0; for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
            return n;
        } catch (Throwable t) { return -2; }
    }

    static List<String> classes() {
        List<String> out = new ArrayList<>();
        for (String base : new String[]{"content.custom.Explorers", "content.custom.OffGround"}) {
            try { Class<?> c = Class.forName(base); out.add(c.getName()); for (Class<?> n : c.getDeclaredClasses()) out.add(n.getName()); } catch (Throwable ignored) { }
            for (int i = 1; i < 30; i++) { try { Class.forName(base + "$" + i); out.add(base + "$" + i); } catch (Throwable ignored) { } } // anonymous classes
        }
        return out;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19w")) return;
        final long t0 = System.currentTimeMillis();
        final File tf = survey("test-b19w.jsonl"); tf.delete();
        int bad = 0; StringBuilder where = new StringBuilder(); List<String> cl = classes();
        for (String c : cl) for (String w : new String[]{"java/io/FileWriter", "java/io/FileOutputStream", "java/nio/file/Files"}) { int n = refs(c, w); if (n != 0) { bad++; where.append(" ").append(c).append(":").append(w).append("=").append(n); } }
        W1BAD = bad;
        log("RESULT W1 static: " + cl.size() + " classes scanned, " + bad + " with file-writing references (expect 0)" + where);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, io0, w0, v0; boolean w2; long end = t0 + 20 * 60_000L;
            @SuppressWarnings("unchecked")
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t < 40) return false;
                    if (!w2) {
                        w2 = true; io0 = SurveyWriter.onGameThreadIO.get(); w0 = SurveyWriter.written.get(); v0 = OffGround.VISITS.get();
                        JSONObject[] js = new JSONObject[200]; for (int i = 0; i < 200; i++) { js[i] = new JSONObject(); js[i].put("n", i); }
                        String thread = Thread.currentThread().getName();
                        long a = System.nanoTime();
                        for (int i = 0; i < 200; i++) Explorers.write("test-b19w.jsonl", js[i]);
                        long dt = System.nanoTime() - a;
                        log("RESULT W2 cost: 200 Explorers.write on thread '" + thread + "' took " + String.format(java.util.Locale.ROOT, "%.3f", dt / 1e6) + " ms (expect < 2); onGameThreadIO " + io0 + " -> " + SurveyWriter.onGameThreadIO.get() + "; queued now " + SurveyWriter.queued());
                        return false;
                    }
                    int v = OffGround.VISITS.get();
                    if (v - v0 < 3 && System.currentTimeMillis() < end) return false;
                    final int visits = v - v0;
                    new Thread(() -> check(t0, tf, visits, w0), "TestB19W").start();
                    return true;
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
            }
        });
    }

    static void check(long t0, File tf, int visits, int w0) {
        try {
            int vA = OffGround.VISITS.get();
            boolean ok = SurveyWriter.flushNow(3000);
            int vB = OffGround.VISITS.get();
            JSONParser p = new JSONParser();
            int og = 0, ogBad = 0, notMem = 0; long last = 0;
            for (String l : Files.readAllLines(survey("offgrid.jsonl").toPath())) {
                JSONObject j; try { j = (JSONObject) p.parse(l); } catch (Throwable e) { continue; }
                long t = ((Number) j.get("t")).longValue(); if (t < t0) continue;
                og++; if (t < last) ogBad++; last = t;
                String k = String.valueOf(j.get("key")); if (!OffGround.DONE.containsKey(k) && !OffGround.DANGER.containsKey(k)) notMem++;
            }
            int st = 0, stBad = 0; last = 0;
            File sf = survey("stairs.jsonl");
            if (sf.exists()) for (String l : Files.readAllLines(sf.toPath())) {
                JSONObject j; try { j = (JSONObject) p.parse(l); } catch (Throwable e) { continue; }
                long t = ((Number) j.get("t")).longValue(); if (t < t0) continue;
                st++; if (t < last) stBad++; last = t;
            }
            List<String> tl = tf.exists() ? Files.readAllLines(tf.toPath()) : new ArrayList<>(); int tBad = 0;
            for (int i = 0; i < tl.size(); i++) if (!tl.get(i).equals("{\"n\":" + i + "}")) tBad++;
            int io = SurveyWriter.onGameThreadIO.get();
            log("RESULT W3 explorer run: flushNow " + ok + "; visits " + visits + ", offgrid.jsonl new lines " + og + " (expect " + vA + ".." + vB + ", all since boot) " + ogBad + " out of time order, " + notMem + " keys not in DONE/DANGER (expect 0); stairs.jsonl new lines " + st + ", " + stBad + " out of order");
            log("RESULT W4 test file: " + tl.size() + " lines (expect 200), " + tBad + " out of order; onGameThreadIO " + io + " (expect 0); SurveyWriter written " + w0 + " -> " + SurveyWriter.written.get() + ", dropped " + SurveyWriter.dropped.get() + ", failedWrites " + SurveyWriter.failedWrites.get());
            boolean pass = W1BAD == 0 && ok && visits >= 3 && og >= vA && og <= vB && ogBad == 0 && notMem == 0 && stBad == 0 && tl.size() == 200 && tBad == 0 && io == 0 && SurveyWriter.dropped.get() == 0 && SurveyWriter.failedWrites.get() == 0;
            log("RESULT " + (pass ? "PASS" : "FAIL"));
            tf.delete();
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        log("DONE");
    }
}
