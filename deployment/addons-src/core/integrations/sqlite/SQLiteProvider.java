package core.integrations.sqlite;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * P38 overlay of the server.jar Kotlin class core.integrations.sqlite.SQLiteProvider (same name, same ABI:
 * GEDB, PlayerStatsCounter, PlayerMonitor and Grafana call it). Changes vs the jar (the design notes):
 * Kill switch -Dsqlite.wal (default false), read once at class load:
 *   false = the jar's behaviour: bare SQLiteDataSource (no pragmas), open per run() and close when the refcount hits 0,
 *           dbRunLock.tryLock(10 s) and run anyway on timeout.
 *   true  = journal_mode=WAL + synchronous=NORMAL + busy_timeout=5000, one connection kept open for the process
 *           lifetime (blocking re-entrant dbRunLock), a shutdown hook that checkpoints (TRUNCATE) and closes it.
 *           Fix 2 (design section "P38 L6 fix 2"), WAL only: F1 closures get a Connection proxy that records every
 *           Statement they create; the outermost run() closes the ones left open (a leaked stepped SELECT keeps a read
 *           transaction open on the shared connection and blocks every checkpoint). F2 the hook waits for the game's
 *           SystemShutdownHook thread, takes the lock or gives up, and closes in a finally. F3 a provider created
 *           during shutdown (no hook possible) checkpoints and closes after each outermost run().
 * The one always-on change (both modes): run() releases dbRunLock, and in jar mode also drops the refcount and
 * closes, in a finally block, so a throwing closure no longer leaves the lock held forever (the jar's latent bug).
 */
public final class SQLiteProvider {
    public static final String TABLE_CHECK = "SELECT name FROM sqlite_master WHERE type='table' AND name=?;";
    // The jar's empty Kotlin "Companion" field is left out on purpose: javap -c of every class in server.jar shows no
    // reference to it, and declaring it in Java would need a nested class that shadows SQLiteProvider$Companion too.

    private final String path;
    private final HashMap<String, String> expectedTables;
    /** P38 kill switch, default OFF: -Dsqlite.wal=true turns on WAL + NORMAL + one shared connection. */
    private static final boolean WAL = Boolean.getBoolean("sqlite.wal");

    private Connection connection;                          // WAL: one per instance; jar mode: per outermost run()
    private int connectionRefs;                             // jar mode only
    private final ReentrantLock obtainConnectionLock = new ReentrantLock(); // jar mode only
    private final ReentrantLock dbRunLock = new ReentrantLock();
    private final CoroutineScope coroutineScope = CoroutineScopeKt.CoroutineScope(Dispatchers.getIO());
    private String sqlitePath;
    // P38 L6 fix 2, WAL mode only (used under dbRunLock, except closePerRun's write in the constructor):
    private volatile boolean closePerRun;                   // F3: no shutdown hook, so checkpoint + close after each outermost run()
    private Connection connProxy;                           // F1: what closures get, a recording proxy over `connection`
    private final ArrayList<Statement> runStatements = new ArrayList<>(); // F1: statements created during the outermost run()
    private long leakEvents, leakTotal;                     // F1: runs that left statements open, statements closed for them
    private static final String GAME_HOOK = "core.game.system.SystemShutdownHook";

    public SQLiteProvider(String path, HashMap<String, String> expectedTables) {
        if (path == null) throw new NullPointerException("Parameter specified as non-null is null: parameter path");
        this.path = path;
        this.expectedTables = expectedTables;
        if (WAL) registerShutdownHook();
    }

    /** Kotlin default-argument constructor (expectedTables = null). */
    public SQLiteProvider(String path, HashMap expectedTables, int mask, DefaultConstructorMarker marker) {
        this(path, (mask & 2) != 0 ? null : castTables(expectedTables));
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, String> castTables(HashMap m) { return (HashMap<String, String>) m; }

    public final void initTables(Function1<? super String, Unit> tableCreatedCallback) {
        String abs = new File(path).getAbsolutePath();
        int cut = abs.lastIndexOf(File.separator);
        String dir = cut < 0 ? "" : abs.substring(0, cut);
        String name = abs.substring(cut + 1);
        if (!new File(dir).exists()) new File(dir).mkdirs();
        sqlitePath = dir + File.separator + name;
        if (expectedTables == null || expectedTables.isEmpty()) return;
        run(conn -> {
            try (PreparedStatement check = conn.prepareStatement(TABLE_CHECK)) {
                for (Map.Entry<String, String> e : expectedTables.entrySet()) {
                    String table = e.getKey();
                    check.setString(1, table);
                    boolean exists;
                    try (ResultSet res = check.executeQuery()) { exists = res.next(); }
                    if (exists) continue;
                    try (Statement st = conn.createStatement()) { st.execute(e.getValue()); }
                    if (tableCreatedCallback != null) tableCreatedCallback.invoke(table);
                }
            } catch (SQLException ex) {
                throw SQLiteProvider.<RuntimeException>sneaky(ex);
            }
            return Unit.INSTANCE;
        });
    }

    public static void initTables$default(SQLiteProvider self, Function1 cb, int mask, Object unused) {
        self.initTables((mask & 1) != 0 ? null : castCb(cb));
    }

    @SuppressWarnings("unchecked")
    private static Function1<? super String, Unit> castCb(Function1 f) { return (Function1<? super String, Unit>) f; }

    public final void pruneOldData(int daysToKeep, String timestampFieldName) {
        if (timestampFieldName == null) throw new NullPointerException("Parameter specified as non-null is null: parameter timestampFieldName");
        if (expectedTables == null || expectedTables.isEmpty()) return;
        int timeDiff = daysToKeep * 24 * 60 * 60;
        long nowTime = System.currentTimeMillis() / 1000L;
        run(conn -> {
            try (Statement st = conn.createStatement()) {
                for (String table : expectedTables.keySet())
                    st.execute("DELETE FROM " + table + " WHERE " + timestampFieldName + " <= " + (nowTime - (long) timeDiff) + ";");
            } catch (SQLException ex) {
                throw SQLiteProvider.<RuntimeException>sneaky(ex);
            }
            return Unit.INSTANCE;
        });
    }

    public static void pruneOldData$default(SQLiteProvider self, int daysToKeep, String field, int mask, Object unused) {
        self.pruneOldData(daysToKeep, (mask & 2) != 0 ? "ts" : field);
    }

    public final Job runAsync(Function1<? super Connection, Unit> closure) {
        if (closure == null) throw new NullPointerException("Parameter specified as non-null is null: parameter closure");
        return BuildersKt.launch(coroutineScope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
                (scope, cont) -> { run(closure); return Unit.INSTANCE; });
    }

    public final void run(Function1<? super Connection, Unit> closure) {
        if (closure == null) throw new NullPointerException("Parameter specified as non-null is null: parameter closure");
        if (!WAL) { runJar(closure); return; }
        lockTimed();                                        // blocking and re-entrant: a closure may call run() again
        boolean ok = false;
        try {
            closure.invoke(connect());
            ok = true;
        } finally {
            try {
                if (dbRunLock.getHoldCount() == 1) { // nested run() calls share the outer list
                    if (!ok) rollbackAfterThrow();
                    endOutermostRun();
                }
            } finally {
                dbRunLock.unlock();
            }
        }
    }

    // P69 (flaw hunt 11-14 #7), WAL mode only: a timed wait that says so, a rollback after a throw, a bounded statement list.
    static final long LOCK_WARN_MS = Long.getLong("sqlite.lockWarnMs", 10_000L);
    static volatile long lockWaits, rollbacks;               // tests + status

    /**
     * dbRunLock, re-entrant. Never runs without it (the one connection is shared), but a wait longer than LOCK_WARN_MS
     * (a slow closure holding it, the game thread waiting) is logged once per LOCK_WARN_MS with the holder's name.
     */
    private void lockTimed() {
        if (dbRunLock.isHeldByCurrentThread()) { dbRunLock.lock(); return; }
        long t0 = System.currentTimeMillis();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (dbRunLock.tryLock(LOCK_WARN_MS, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException ie) {
                    interrupted = true; dbRunLock.lock(); return;
                }
                lockWaits++;
                System.out.println("[SQLite] " + sqlitePath + ": " + Thread.currentThread().getName() + " waited "
                        + (System.currentTimeMillis() - t0) + " ms for the db lock (a slow closure holds it); still waiting");
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Outermost run() only, under dbRunLock: a closure threw mid-transaction; leave the shared connection in autocommit. Never throws. */
    private void rollbackAfterThrow() {
        try {
            if (connection != null && !connection.isClosed() && !connection.getAutoCommit()) {
                try { connection.rollback(); } finally { connection.setAutoCommit(true); }
                rollbacks++;
                System.out.println("[SQLite] " + sqlitePath + ": a closure threw inside a transaction: rolled back, autocommit back on (total " + rollbacks + ")");
            }
        } catch (Exception ex) {
            System.out.println("[SQLite] " + sqlitePath + ": rollback after a throw failed: " + ex);
        }
    }

    /** Outermost-run statement list cap: past this, closed statements are dropped from the list (a long run made it grow). */
    static final int RUN_STATEMENTS_TRIM = 256;

    /** WAL mode, outermost run() only, under dbRunLock: F1 close what the closure left open; F3 close per run. Never throws. */
    private void endOutermostRun() {
        int n = 0;
        for (Statement st : runStatements) {
            try {
                if (!st.isClosed()) { st.close(); n++; }
            } catch (Exception ignored) {
                // a statement that cannot be closed is finalized by connection.close() at the latest
            }
        }
        runStatements.clear();
        if (n > 0) {
            leakTotal += n;
            if (++leakEvents == 1 || leakEvents % 1000 == 0)
                System.out.println("[SQLite] " + sqlitePath + ": closed " + n + " statement(s) a closure left open (total " + leakTotal + ")");
        }
        if (closePerRun) checkpointAndClose();
    }

    /** Jar mode: the Kotlin run() (tryLock 10 s, run anyway on timeout, refcounted open/close) plus the finally fix. */
    private void runJar(Function1<? super Connection, Unit> closure) {
        boolean locked = false;
        try {
            locked = dbRunLock.tryLock(10_000L, TimeUnit.MILLISECONDS);
            connectionRefs++;
            Connection c = connectJar();
            try {
                closure.invoke(c);
            } finally {
                connectionRefs--;
                if (connectionRefs == 0) c.close();
            }
        } catch (InterruptedException | SQLException ex) {
            throw SQLiteProvider.<RuntimeException>sneaky(ex);
        } finally {
            if (locked) dbRunLock.unlock();
        }
    }

    /** Jar mode: the Kotlin connect(), a bare SQLiteDataSource (sqlite-jdbc defaults: journal DELETE, synchronous FULL). */
    private Connection connectJar() throws SQLException, InterruptedException {
        boolean locked = obtainConnectionLock.tryLock(10_000L, TimeUnit.MILLISECONDS);
        try {
            if (connection == null || connection.isClosed()) {
                if (sqlitePath == null) throw new kotlin.UninitializedPropertyAccessException("lateinit property sqlitePath has not been initialized");
                SQLiteDataSource ds = new SQLiteDataSource();
                ds.setUrl("jdbc:sqlite:" + sqlitePath);
                connection = ds.getConnection();
            }
            return connection;
        } finally {
            if (locked) obtainConnectionLock.unlock();
        }
    }

    /** WAL mode, called only under dbRunLock. Opens the one connection on first use (or after it was closed). Returns the proxy. */
    private Connection connect() {
        try {
            if (connection == null || connection.isClosed()) {
                if (sqlitePath == null) throw new kotlin.UninitializedPropertyAccessException("lateinit property sqlitePath has not been initialized");
                SQLiteConfig cfg = new SQLiteConfig();
                cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
                cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
                cfg.setBusyTimeout(5000);
                SQLiteDataSource ds = new SQLiteDataSource(cfg);
                ds.setUrl("jdbc:sqlite:" + sqlitePath);
                connection = ds.getConnection();
                connProxy = recordingProxy(connection);
                System.out.println("[SQLite] " + sqlitePath + " journal_mode=" + pragma(connection, "journal_mode")
                        + " synchronous=" + pragma(connection, "synchronous"));
            }
            return connProxy;
        } catch (SQLException ex) {
            throw SQLiteProvider.<RuntimeException>sneaky(ex);
        }
    }

    /**
     * F1: a java.sql.Connection that delegates every call to {@code real} and records the Statement returned by
     * createStatement / prepareStatement / prepareCall. The jar's closures use the connection only through the
     * java.sql.Connection interface (invokeinterface), so this is transparent. Exceptions are rethrown unchanged.
     */
    private Connection recordingProxy(Connection real) {
        return (Connection) Proxy.newProxyInstance(SQLiteProvider.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    }
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException ite) {
                        throw ite.getCause() != null ? ite.getCause() : ite;
                    }
                    if (result instanceof Statement) {
                        String name = method.getName();
                        if ("createStatement".equals(name) || "prepareStatement".equals(name) || "prepareCall".equals(name)) {
                            if (runStatements.size() >= RUN_STATEMENTS_TRIM) runStatements.removeIf(SQLiteProvider::closedQuietly); // P69 #7
                            runStatements.add((Statement) result);
                        }
                    }
                    return result;
                });
    }

    private static boolean closedQuietly(Statement st) { try { return st.isClosed(); } catch (Exception e) { return true; } }

    private static String pragma(Connection c, String name) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + name + ";")) {
            return rs.next() ? rs.getString(1) : "?";
        } catch (SQLException ex) {
            return "error:" + ex.getMessage();
        }
    }

    private void registerShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownClose, "sqlite-close"));
        } catch (IllegalStateException ex) {              // F3: "Shutdown in progress" (PlayerMonitor's lazy init at termination)
            closePerRun = true;
            System.out.println("[SQLite] " + path + ": opened during shutdown, no hook: closing after each run");
        }
    }

    /** F2, the sqlite-close hook: after the game's SystemShutdownHook, under dbRunLock or not at all, close in a finally. */
    private void shutdownClose() {
        Thread game = awaitGameHook(2_000L);                // JVM hooks start together; the game's may appear a few ms after ours
        if (game != null) {
            try { game.join(90_000L); } catch (InterruptedException ignored) { /* carry on */ }
        }
        boolean locked;
        try {
            locked = dbRunLock.tryLock(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            locked = false;
        }
        if (!locked) {
            System.out.println("[SQLite] close " + sqlitePath + ": skipped, a closure held the lock for 10 s");
            return;
        }
        try {
            checkpointAndClose();
            closePerRun = true;                             // a run() after this reopens: close it again at its end
        } finally {
            dbRunLock.unlock();
        }
    }

    /**
     * The thread running core.game.system.SystemShutdownHook, polled for up to waitMs. The jar registers it as
     * new Thread(new SystemShutdownHook()) (ServerConstants.SHUTDOWN_HOOK), so that Thread's own class is java.lang.Thread:
     * match its class name or a stack frame of that class. Null when not found (another stop path, or already done).
     */
    private static Thread awaitGameHook(long waitMs) {
        long end = System.nanoTime() + waitMs * 1_000_000L;
        while (true) {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (t == Thread.currentThread() || !t.isAlive()) continue;
                if (GAME_HOOK.equals(t.getClass().getName())) return t;
                for (StackTraceElement f : e.getValue())
                    if (GAME_HOOK.equals(f.getClassName())) return t;
            }
            if (System.nanoTime() >= end) return null;
            try { Thread.sleep(20L); } catch (InterruptedException ex) { return null; }
        }
    }

    /** WAL mode, under dbRunLock: PRAGMA wal_checkpoint(TRUNCATE) (one retry after 250 ms if busy), then close in a finally. */
    private void checkpointAndClose() {
        try {
            if (connection == null || connection.isClosed()) return;
        } catch (SQLException ex) {
            return;
        }
        long[] r = null;
        try {
            r = checkpointTruncate();
            if (r[0] != 0) {
                Thread.sleep(250L);
                r = checkpointTruncate();
            }
        } catch (Exception ex) {
            System.out.println("[SQLite] close " + sqlitePath + ": " + ex);
        } finally {
            try {
                connection.close();
            } catch (Exception ex) {
                System.out.println("[SQLite] close " + sqlitePath + ": " + ex);
            }
        }
        System.out.println("[SQLite] closed " + sqlitePath + " (checkpoint TRUNCATE "
                + (r == null ? "failed" : "busy=" + r[0] + " log=" + r[1] + " ckpt=" + r[2]) + ")");
    }

    private long[] checkpointTruncate() throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE);")) {
            return rs.next() ? new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) } : new long[] { -1L, -1L, -1L };
        }
    }

    /** Rethrows a checked SQLException unchanged (as the Kotlin class did), without a throws clause. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T { throw (T) t; }

    /** Jar ABI: the Kotlin lambda classes left in the jar call this; kept so the shape matches. */
    public static final HashMap access$getExpectedTables$p(SQLiteProvider self) { return self.expectedTables; }
}
