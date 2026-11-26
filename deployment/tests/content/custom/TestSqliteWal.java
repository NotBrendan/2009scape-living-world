package content.custom;

import core.api.StartupListener;
import core.game.ge.GEDB;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import kotlin.Unit;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trial only (-Dtest.sqlitewal=true; the WAL side also needs -Dsqlite.wal=true, the overlay's kill switch): P38 L6 SQLite WAL overlay (the design notes section 6 step 3).
 * Runs T1-T5 and T7 on a worker thread (not the game thread) through GEDB.run, against data/eco/grandexchange.db.
 * T7 (P38 L6 fix 2): a closure leaks a stepped SELECT, then a checkpoint in the next run() must not be blocked by it.
 * T6 is checked outside: boot log has "[SQLite] ... journal_mode=wal" per DB, and the -wal is 0 bytes or gone after stop.
 * Run the same class on the control build: T1 prints delete/2, T2 gives the FULL-sync baseline, T4 is expected to FAIL there.
 */
public final class TestSqliteWal implements StartupListener {
    static void log(String s) { System.out.println("[TestSqliteWal] " + s); }

    static void exec(Connection c, String sql) {
        try (Statement st = c.createStatement()) { st.execute(sql); } catch (SQLException e) { throw new RuntimeException(e); }
    }

    static String pragma(Connection c, String name) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + name + ";")) {
            return rs.next() ? rs.getString(1) : "?";
        } catch (SQLException e) { return "error:" + e.getMessage(); }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.sqlitewal")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t != 50) return false;
                log("sqlite.wal=" + System.getProperty("sqlite.wal", "(unset)"));
                new Thread(TestSqliteWal::runAll, "TestSqliteWal-worker").start();
                return true;
            }
        });
    }

    static void runAll() {
        int pass = 0, fail = 0;
        try {
            // T1: journal mode and sync level on the shared GE connection
            AtomicReference<String> jm = new AtomicReference<>(), sy = new AtomicReference<>();
            GEDB.run(c -> { jm.set(pragma(c, "journal_mode")); sy.set(pragma(c, "synchronous")); return Unit.INSTANCE; });
            boolean t1 = "wal".equalsIgnoreCase(jm.get()) && "1".equals(sy.get());
            log("T1 journal_mode=" + jm.get() + " synchronous=" + sy.get() + " -> " + (t1 ? "PASS" : "FAIL"));
            if (t1) pass++; else fail++;

            // T2: 1000 single-row INSERTs, each its own run() call and its own autocommit
            GEDB.run(c -> { exec(c, "CREATE TABLE IF NOT EXISTS p38_wal_test (id INTEGER PRIMARY KEY, v TEXT, ts INTEGER);"); return Unit.INSTANCE; });
            long t0 = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                final int n = i;
                GEDB.run(c -> { exec(c, "INSERT INTO p38_wal_test (v, ts) VALUES ('row" + n + "', " + System.currentTimeMillis() + ");"); return Unit.INSTANCE; });
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            AtomicReference<String> rows = new AtomicReference<>();
            GEDB.run(c -> { rows.set(pragma(c, "page_count") + " pages, rows=" + count(c)); return Unit.INSTANCE; });
            log("T2 1000 commits in " + ms + " ms (" + rows.get() + ") -> INFO (compare with control)");

            // T3: nested run() from inside a closure
            AtomicBoolean inner = new AtomicBoolean();
            GEDB.run(c -> { GEDB.run(c2 -> { inner.set(c2 != null); return Unit.INSTANCE; }); return Unit.INSTANCE; });
            log("T3 nested run completed=" + inner.get() + " -> " + (inner.get() ? "PASS" : "FAIL"));
            if (inner.get()) pass++; else fail++;

            // T5: the -wal file exists next to the DB while the server runs
            File wal = new File("data/eco/grandexchange.db-wal");
            boolean t5 = wal.exists();
            log("T5 " + wal.getPath() + " exists=" + t5 + " bytes=" + (t5 ? wal.length() : -1) + " -> " + (t5 ? "PASS" : "FAIL"));
            if (t5) pass++; else fail++;

            GEDB.run(c -> { exec(c, "DROP TABLE IF EXISTS p38_wal_test;"); return Unit.INSTANCE; });
            log("scratch table p38_wal_test dropped");

            // T7 (P38 L6 fix 2, F1): a closure leaks a stepped SELECT on purpose (nothing closed), as the jar's
            // PriceIndex/getOfferByUid/getKills closures do; then PRAGMA wal_checkpoint(PASSIVE) in a second run().
            // On fd964c9 (no statement tracking) the leaked read transaction makes this throw SQLITE_LOCKED.
            AtomicReference<long[]> ck = new AtomicReference<>();
            String t7err = null;
            try {
                GEDB.run(c -> {
                    try { c.prepareStatement("SELECT name FROM sqlite_master WHERE type='table'").executeQuery().next(); }
                    catch (SQLException e) { throw new RuntimeException(e); }
                    return Unit.INSTANCE;
                });
                GEDB.run(c -> {
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(PASSIVE);")) {
                        if (rs.next()) ck.set(new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) });
                    } catch (SQLException e) { throw new RuntimeException(e); }
                    return Unit.INSTANCE;
                });
            } catch (Throwable e) { t7err = String.valueOf(e); }
            long[] r7 = ck.get();
            boolean t7 = t7err == null && r7 != null && r7[0] == 0;
            log("T7 leaked SELECT then checkpoint(PASSIVE) " + (r7 == null ? "no row" : "busy=" + r7[0] + " log=" + r7[1] + " ckpt=" + r7[2])
                    + " error=" + t7err + " -> " + (t7 ? "PASS" : "FAIL"));
            if (t7) pass++; else fail++;

            // T4 (last: on the control build the lock stays held by the dead thread afterwards):
            // a closure that throws on another thread, then a run() here must not wait or throw
            Thread thrower = new Thread(() -> {
                try { GEDB.run(c -> { throw new IllegalStateException("p38 T4 deliberate"); }); }
                catch (Throwable e) { log("T4 thrower caught " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
            }, "TestSqliteWal-thrower");
            thrower.start();
            thrower.join(15_000);
            long a = System.nanoTime();
            AtomicBoolean ran = new AtomicBoolean();
            String err = null;
            try { GEDB.run(c -> { ran.set(true); return Unit.INSTANCE; }); }
            catch (Throwable e) { err = e.getClass().getSimpleName(); }
            long waitMs = (System.nanoTime() - a) / 1_000_000L;
            boolean t4 = ran.get() && err == null && waitMs < 2000;
            log("T4 next run ran=" + ran.get() + " waited " + waitMs + " ms error=" + err + " -> " + (t4 ? "PASS" : "FAIL"));
            if (t4) pass++; else fail++;
        } catch (Throwable e) {
            fail++;
            log("ERROR " + e);
        }
        log("RESULT pass=" + pass + " fail=" + fail + " (of 5 checked; T2 is timing, T6 is checked after stop)");
        log("DONE");
    }

    static long count(Connection c) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM p38_wal_test;")) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException e) { return -1; }
    }
}
