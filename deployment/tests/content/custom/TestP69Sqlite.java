package core.integrations.sqlite;

import kotlin.Unit;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P69 (flaw hunt 11-14 #7) SQLiteProvider unit test, no world (plain main; lives in the provider's package for its counters).
 *   java -cp "out;server.jar" [-Dsqlite.wal=true] -Dsqlite.lockWarnMs=300 core.integrations.sqlite.TestP69Sqlite <tmpdir>
 * WAL on:  R a closure that throws mid-transaction is rolled back and the shared connection is back in autocommit;
 *          L a run() that waits on a slow closure logs the wait (lockWaits) and still runs, after the holder;
 *          S a long run's statement list is trimmed of closed statements; N a nested run() inside a transaction is not rolled back early.
 * WAL off: R the jar path is untouched (no rollback counted, no lock-wait counted), L still serialises.
 * Prints "[TestP69Sqlite] RESULT PASS|FAIL n/m wal=..." and DONE; exit 1 on a fail.
 */
public final class TestP69Sqlite {
    static int pass, fail;
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestP69Sqlite] " + (ok ? "PASS " : "FAIL ") + what); }

    interface Body { void run(Connection c) throws Exception; }
    static void run(SQLiteProvider p, Body b) {
        p.run(c -> { try { b.run(c); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); } return Unit.INSTANCE; });
    }

    static long rows(SQLiteProvider p) {
        AtomicLong n = new AtomicLong(-1);
        run(p, c -> { try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) { rs.next(); n.set(rs.getLong(1)); } });
        return n.get();
    }

    public static void main(String[] args) throws Exception {
        boolean wal = Boolean.getBoolean("sqlite.wal");
        File dir = new File(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"), "p69sqlite-" + System.nanoTime());
        dir.mkdirs();
        SQLiteProvider p = new SQLiteProvider(new File(dir, "t.db").getAbsolutePath(), null);
        p.initTables(null);
        run(p, c -> { try (Statement st = c.createStatement()) { st.execute("CREATE TABLE IF NOT EXISTS t (v INTEGER)"); } });

        // R: throw inside a transaction
        boolean threw = false;
        try {
            run(p, c -> { c.setAutoCommit(false); try (Statement st = c.createStatement()) { st.execute("INSERT INTO t VALUES (1)"); } throw new IllegalStateException("closure failed"); });
        } catch (IllegalStateException e) { threw = true; }
        AtomicBoolean auto = new AtomicBoolean(false);
        run(p, c -> auto.set(c.getAutoCommit()));
        long n = rows(p);
        if (wal) check("R throw mid-transaction: rethrown " + threw + ", rolled back (rows " + n + "), autocommit back " + auto.get() + ", rollbacks " + SQLiteProvider.rollbacks,
                threw && n == 0 && auto.get() && SQLiteProvider.rollbacks == 1);
        else check("R flag off: rethrown " + threw + ", jar path (rollbacks " + SQLiteProvider.rollbacks + ", fresh connection autocommit " + auto.get() + ")", threw && SQLiteProvider.rollbacks == 0 && auto.get());

        // N: a nested run() that throws inside an outer transaction is not rolled back by the inner call (outer decides)
        if (wal) {
            long r0 = SQLiteProvider.rollbacks;
            run(p, c -> {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) { st.execute("INSERT INTO t VALUES (2)"); }
                try { run(p, c2 -> { throw new IllegalStateException("inner"); }); } catch (IllegalStateException ignored) { }
                c.commit(); c.setAutoCommit(true);
            });
            check("N nested throw: no early rollback (rollbacks " + r0 + "->" + SQLiteProvider.rollbacks + "), outer commit kept (rows " + rows(p) + ")", SQLiteProvider.rollbacks == r0 && rows(p) == 1);
        }

        // S: many closed statements in one run (WAL: the list is trimmed)
        if (wal) {
            AtomicLong size = new AtomicLong(-1);
            java.lang.reflect.Field f = SQLiteProvider.class.getDeclaredField("runStatements"); f.setAccessible(true);
            run(p, c -> { for (int i = 0; i < 600; i++) { Statement st = c.createStatement(); st.close(); } size.set(((List<?>) f.get(p)).size()); });
            check("S 600 closed statements in one run: list " + size.get() + " <= " + (SQLiteProvider.RUN_STATEMENTS_TRIM + 1), size.get() >= 0 && size.get() <= SQLiteProvider.RUN_STATEMENTS_TRIM + 1);
        }

        // L: a slow closure holds the lock; another thread's run() waits, logs, then runs after it
        CountDownLatch in = new CountDownLatch(1);
        AtomicLong slowEnd = new AtomicLong(), fastStart = new AtomicLong();
        Thread slow = new Thread(() -> run(p, c -> { in.countDown(); Thread.sleep(1_000); slowEnd.set(System.nanoTime()); }), "p69-slow");
        slow.start(); in.await();
        long w0 = SQLiteProvider.lockWaits;
        run(p, c -> fastStart.set(System.nanoTime()));
        slow.join();
        long waits = SQLiteProvider.lockWaits - w0;
        boolean after = fastStart.get() >= slowEnd.get() && slowEnd.get() > 0;
        if (wal) check("L timed lock (warn " + SQLiteProvider.LOCK_WARN_MS + " ms): waited and logged " + waits + "x, ran after the holder " + after, after && waits >= 1);
        else check("L flag off: jar tryLock path unchanged (lockWaits " + waits + "), ran after the holder " + after, after && waits == 0);

        System.out.println("[TestP69Sqlite] RESULT " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail) + " wal=" + wal);
        System.out.println("[TestP69Sqlite] DONE");
        System.exit(fail == 0 ? 0 : 1);
    }
}
