package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

/**
 * Trial only (-Dtest.b19c=true): batch 19a builder B, B1 = SurveyWriter itself (Explorers/OffGround move to it in 19b, not here).
 * Needs SurveyWriter (19a tree); C1 calls it directly so the timing is append() alone.
 *   C1 append cost: 500 append() calls in ONE game pulse, total time (expect < 2 ms), and nothing written on the game thread
 *      (SurveyWriter.onGameThreadIO stays 0).
 *   C2 flushNow(3000) from a test thread: true, and data/survey/test-b19c.jsonl holds the 500 lines in order.
 *   C3 order across files and an atomic: a, b, a interleaved + atomic(test-b19c.json) then flushNow: each file in its own order, the atomic file whole.
 *   C4 the 2 s timer drain: 3 lines, no flushNow, on disk within 4 s; the 64-item drain: 64 lines appear well before 2 s.
 *   C5 onGameThreadIO still 0, dropped 0, failed writes 0; the writer thread is named SurveyWriter and is a daemon.
 */
public final class TestB19C implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C] " + s); }
    static File survey(String n) { return new File(new File(ServerConstants.DATA_PATH, "survey"), n); }

    static Class<?> W; static Method append, atomic, flushNow;
    static int stat(String n) { try { Field f = W.getDeclaredField(n); f.setAccessible(true); return ((java.util.concurrent.atomic.AtomicInteger) f.get(null)).get(); } catch (Throwable t) { return -1; } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c")) return;
        try {
            W = Class.forName("content.custom.SurveyWriter");
            append = W.getDeclaredMethod("append", String.class, String.class); append.setAccessible(true);
            atomic = W.getDeclaredMethod("atomic", String.class, String.class); atomic.setAccessible(true);
            flushNow = W.getDeclaredMethod("flushNow", long.class); flushNow.setAccessible(true);
        } catch (Throwable t) { W = null; }
        final File f = survey("test-b19c.jsonl"), fa = survey("test-b19c-a.jsonl"), fb = survey("test-b19c-b.jsonl"), fj = survey("test-b19c.json"), ft = survey("test-b19c-timer.jsonl"), f64 = survey("test-b19c-64.jsonl");
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t < 50) return false;
                if (W == null) { log("RESULT BEFORE: no SurveyWriter class (n/a)"); log("DONE"); return true; }
                try {
                    for (File x : new File[]{f, fa, fb, fj, ft, f64}) x.delete(); // trial data only, before the timed part
                    int io0 = stat("onGameThreadIO");
                    String path = f.getPath(); String[] ls = new String[500]; for (int i = 0; i < 500; i++) ls[i] = "{\"n\":" + i + "}";
                    long t0 = System.nanoTime();
                    for (int i = 0; i < 500; i++) SurveyWriter.append(path, ls[i]); // direct call: the cost measured is append() only
                    long dt = System.nanoTime() - t0;
                    log("RESULT C1 append cost: 500 append() in one pulse took " + String.format(java.util.Locale.ROOT, "%.3f", dt / 1e6) + " ms (expect < 2), onGameThreadIO " + io0 + " -> " + stat("onGameThreadIO") + " (expect 0 -> 0), lines on disk right after: " + lines(f) + " (expect < 500 unless the 64-batch drain already ran)");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                new Thread(() -> TestB19C.run(f, fa, fb, fj, ft, f64), "TestB19C").start();
                return true;
            }
        });
    }

    static int lines(File f) { try { return f.exists() ? Files.readAllLines(f.toPath()).size() : 0; } catch (Throwable t) { return -1; } }

    static void run(File f, File fa, File fb, File fj, File ft, File f64) {
        try {
            long t0 = System.currentTimeMillis();
            boolean ok = (Boolean) flushNow.invoke(null, 3000L);
            List<String> l = Files.readAllLines(f.toPath()); int bad = 0;
            for (int i = 0; i < l.size(); i++) if (!l.get(i).equals("{\"n\":" + i + "}")) bad++;
            log("RESULT C2 flushNow(3000): " + ok + " after " + (System.currentTimeMillis() - t0) + " ms; " + l.size() + " lines (expect 500), " + bad + " out of order (expect 0)");

            for (int i = 0; i < 10; i++) { append.invoke(null, fa.getPath(), "a" + i); append.invoke(null, fb.getPath(), "b" + i); }
            atomic.invoke(null, fj.getPath(), "{\"whole\":1}");
            atomic.invoke(null, fj.getPath(), "{\"whole\":2}");
            for (int i = 10; i < 20; i++) append.invoke(null, fa.getPath(), "a" + i);
            ok = (Boolean) flushNow.invoke(null, 3000L);
            List<String> la = Files.readAllLines(fa.toPath()), lb = Files.readAllLines(fb.toPath());
            int ba = 0, bb = 0; for (int i = 0; i < la.size(); i++) if (!la.get(i).equals("a" + i)) ba++; for (int i = 0; i < lb.size(); i++) if (!lb.get(i).equals("b" + i)) bb++;
            String j = new String(Files.readAllBytes(fj.toPath())).trim();
            log("RESULT C3 order: flushNow " + ok + "; file a " + la.size() + " lines (expect 20) " + ba + " out of order, file b " + lb.size() + " (expect 10) " + bb + " out of order; atomic file '" + j + "' (expect {\"whole\":2})");

            long s = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) append.invoke(null, ft.getPath(), "t" + i);
            long seen = -1; while (System.currentTimeMillis() - s < 4000) { if (lines(ft) == 3) { seen = System.currentTimeMillis() - s; break; } Thread.sleep(50); }
            log("RESULT C4a timer drain: 3 lines on disk after " + seen + " ms without flushNow (expect 0-2100; -1 = not within 4 s)");
            Thread.sleep(2500); // let a timer drain pass, then a full batch: the 64th append wakes the writer at once
            s = System.currentTimeMillis();
            for (int i = 0; i < 64; i++) append.invoke(null, f64.getPath(), "x" + i);
            seen = -1; while (System.currentTimeMillis() - s < 4000) { if (lines(f64) == 64) { seen = System.currentTimeMillis() - s; break; } Thread.sleep(10); }
            log("RESULT C4b 64-item drain: 64 lines on disk after " + seen + " ms (expect well under 2000)");

            Thread w = null; for (Thread th : Thread.getAllStackTraces().keySet()) if ("SurveyWriter".equals(th.getName())) w = th;
            log("RESULT C5 probes: onGameThreadIO " + stat("onGameThreadIO") + " (expect 0), dropped " + stat("dropped") + ", failedWrites " + stat("failedWrites") + " (expect 0, 0), written " + stat("written") + ", drains " + stat("drains") + "; writer thread " + (w == null ? "none" : w.getName() + " daemon " + w.isDaemon()));
            for (File x : new File[]{f, fa, fb, fj, ft, f64}) x.delete();
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        log("DONE");
    }
}
