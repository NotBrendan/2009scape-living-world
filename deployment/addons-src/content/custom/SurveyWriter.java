package content.custom;

import core.ServerConstants;
import core.api.StartupListener;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * batch 19a (the design notes sec. 11): survey file writes off the game thread. One daemon thread "SurveyWriter" drains a
 * ConcurrentLinkedQueue every 2 s, or at once when 64 items are queued: one FileWriter(file, true) per file per drain (not per line),
 * lines for the same file in queue order; atomic items (a whole file, Ledger.writeAtomic) and tasks run on the writer thread in their
 * queue position. A failed write is printed at most once a minute and the item dropped. flushNow(ms) waits for everything queued
 * before it (tests and operators; never from the game thread except in a trial). A JVM shutdown hook drains with a 5 s bound.
 * The thread starts on first use, so with nothing queued (-Dfailures=false) there is no thread at all.
 * In 19a only Failures (failures/*.jsonl, bugs.json) uses it; Explorers/OffGround move here in 19b.
 * {@code file} is a path as java.io.File takes it (relative paths resolve against the server's working directory, like File).
 */
public final class SurveyWriter implements StartupListener {
    static final int BATCH = 64, MAX_QUEUED = 2_000; // batch 19a review S2: about a day of capped records, so a wedged disk cannot fill the heap
    static final long DRAIN_MS = 2_000;

    static final class Item {
        final String file, line, text; final Runnable task; final CountDownLatch marker;
        Item(String file, String line, String text, Runnable task, CountDownLatch marker) { this.file = file; this.line = line; this.text = text; this.task = task; this.marker = marker; }
    }

    static final ConcurrentLinkedQueue<Item> Q = new ConcurrentLinkedQueue<>();
    static final AtomicInteger SIZE = new AtomicInteger();
    static volatile Thread writer;
    static volatile boolean hooked;
    static volatile long lastErrPrint;
    // trials / counters
    static final AtomicInteger written = new AtomicInteger(), dropped = new AtomicInteger(), failedWrites = new AtomicInteger(), drains = new AtomicInteger();
    /** Test probe (TestB19C): file writes done on a thread that is not the writer (or its shutdown hook). Must stay 0. */
    static final AtomicInteger onGameThreadIO = new AtomicInteger();

    // ------------------------------------------------------------------ the API (any thread, never throws, returns at once)

    /** Append one line (no newline) to {@code file}. */
    static void append(String file, String line) {
        if (file == null || line == null) return;
        put(new Item(file, line, null, null, null));
    }

    /** Replace {@code file} with {@code text} atomically (Ledger.writeAtomic) on the writer thread, in queue order. */
    static void atomic(String file, String text) {
        if (file == null || text == null) return;
        put(new Item(file, null, text, null, null));
    }

    /** Run {@code r} on the writer thread in queue order (e.g. a load-merge-save). Exceptions are caught and printed (throttled). */
    static void task(Runnable r) {
        if (r == null) return;
        put(new Item(null, null, null, r, null));
    }

    /** Waits up to maxWaitMs until everything queued before this call is on disk. Tests and operators only (never the game thread outside a trial). */
    static boolean flushNow(long maxWaitMs) {
        try {
            CountDownLatch l = new CountDownLatch(1);
            MARKERS.incrementAndGet(); Q.add(new Item(null, null, null, null, l)); SIZE.incrementAndGet();
            start(); LockSupport.unpark(writer);
            return l.await(Math.max(0, maxWaitMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) { return false; }
    }

    static int queued() { return SIZE.get(); }

    static void put(Item it) {
        try {
            if (SIZE.get() >= MAX_QUEUED) { dropped.incrementAndGet(); return; } // the writer is wedged: never grow without bound
            Q.add(it);
            int n = SIZE.incrementAndGet();
            Thread w = writer;
            if (w == null) { start(); w = writer; }
            if (n % BATCH == 0 && w != null) LockSupport.unpark(w); // 64 queued: drain now
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ the writer thread

    static synchronized void start() {
        if (writer != null) return;
        Thread t = new Thread(SurveyWriter::loop, "SurveyWriter");
        t.setDaemon(true);
        writer = t;
        t.start();
        if (!hooked) {
            hooked = true;
            try { Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { if (SIZE.get() > 0 && !flushNow(5_000)) System.out.println("[SurveyWriter] shutdown: " + SIZE.get() + " items not written in 5 s"); } catch (Throwable ignored) { } }, "SurveyWriter-shutdown")); } catch (Throwable ignored) { }
        }
    }

    static void loop() {
        long next = System.currentTimeMillis() + DRAIN_MS;
        while (true) {
            try {
                long wait = next - System.currentTimeMillis();
                if (wait > 0 && SIZE.get() < BATCH && !markerQueued()) LockSupport.parkNanos(wait * 1_000_000L);
                if (SIZE.get() >= BATCH || markerQueued() || System.currentTimeMillis() >= next) { drain(); next = System.currentTimeMillis() + DRAIN_MS; }
            } catch (Throwable t) { err("drain", t); }
        }
    }

    static final AtomicInteger MARKERS = new AtomicInteger(); // flushNow markers waiting
    static boolean markerQueued() { return MARKERS.get() > 0; }

    /** Everything queued now, in order: appends batched per file until an atomic/task/marker (which run in their place). */
    static void drain() {
        drains.incrementAndGet();
        Map<String, List<String>> pending = new LinkedHashMap<>();
        for (Item it; (it = Q.poll()) != null; ) {
            SIZE.decrementAndGet();
            if (it.line != null) { pending.computeIfAbsent(it.file, k -> new ArrayList<>()).add(it.line); continue; }
            writePending(pending);
            if (it.text != null) { try { checkThread(); File f = new File(it.file); File p = f.getAbsoluteFile().getParentFile(); if (p != null) p.mkdirs(); Ledger.writeAtomic(f, it.text); written.incrementAndGet(); } catch (Throwable t) { failedWrites.incrementAndGet(); err(it.file, t); } }
            else if (it.task != null) { try { it.task.run(); } catch (Throwable t) { err("task", t); } }
            else if (it.marker != null) { writePending(pending); MARKERS.decrementAndGet(); it.marker.countDown(); }
        }
        writePending(pending);
    }

    static void writePending(Map<String, List<String>> pending) {
        if (pending.isEmpty()) return;
        for (Map.Entry<String, List<String>> e : pending.entrySet()) {
            try {
                checkThread();
                File f = new File(e.getKey()); File p = f.getAbsoluteFile().getParentFile(); if (p != null) p.mkdirs();
                StringBuilder sb = new StringBuilder();
                for (String l : e.getValue()) sb.append(l).append('\n');
                try (FileWriter w = new FileWriter(f, true)) { w.write(sb.toString()); }
                written.addAndGet(e.getValue().size());
            } catch (Throwable t) { failedWrites.addAndGet(e.getValue().size()); err(e.getKey(), t); }
        }
        pending.clear();
    }

    static void checkThread() { String n = Thread.currentThread().getName(); if (!n.startsWith("SurveyWriter")) onGameThreadIO.incrementAndGet(); }

    static void err(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrPrint < 60_000) return;
        lastErrPrint = now;
        System.out.println("[SurveyWriter] could not write " + what + ": " + t + " (dropped; this message at most once a minute)");
    }

    // ------------------------------------------------------------------ batch 19a B7: the startup line

    static File survey() { return new File(ServerConstants.DATA_PATH, "survey"); }

    @Override
    public void startup() {
        try {
            if (!Watchdog.FAIL_ON) { System.out.println("[Failures] off (-Dfailures=false): no failure records, no bugs.json, hooks are no-ops"); return; }
            task(() -> {
                int open = 0, notRep = 0, total = 0; String note = "";
                File bf = new File(Scenarios.rDir(), "bugs.json"); if (!bf.exists()) bf = new File(survey(), "bugs.json"); // batch 19a review S4: rDir(), where Failures writes it
                if (bf.exists()) try (java.io.FileReader rd = new java.io.FileReader(bf)) {
                    Object o = new org.json.simple.parser.JSONParser().parse(rd);
                    Object bugs = o instanceof org.json.simple.JSONObject ? ((org.json.simple.JSONObject) o).get("bugs") : null;
                    if (bugs instanceof org.json.simple.JSONObject) for (Object v : ((org.json.simple.JSONObject) bugs).values()) {
                        total++; Object st = v instanceof org.json.simple.JSONObject ? ((org.json.simple.JSONObject) v).get("status") : null;
                        if ("open".equals(st)) open++; else if ("not-reproduced".equals(st)) notRep++;
                    }
                } catch (Throwable t) { note = " (bugs.json unreadable: " + t + ")"; }
                else note = " (no bugs.json yet)";
                System.out.println("[Failures] on: " + open + " bugs open, " + notRep + " not-reproduced (" + total + " in bugs.json)" + note + ", failures dir " + new File(survey(), "failures").getPath());
            });
        } catch (Throwable t) { System.out.println("[Failures] startup line: " + t); }
    }
}
