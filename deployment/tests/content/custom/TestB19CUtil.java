package content.custom;

import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** batch 19c Builder A, trial only: shared helpers for TestB19C1-C7 (no listener; nothing runs unless a test calls it). */
final class TestB19CUtil {
    private TestB19CUtil() { }

    /** Runs c on the game thread and waits (up to 20 s) for its result. */
    static <T> T onGame(Callable<T> c) {
        AtomicReference<Object> out = new AtomicReference<>(); final boolean[] done = {false};
        GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { try { out.set(c.call()); } catch (Throwable t) { out.set(t); } done[0] = true; return true; } });
        for (int i = 0; i < 200 && !done[0]; i++) sleep(100);
        Object o = out.get();
        if (o instanceof Throwable) { System.out.println("[TestB19CUtil] game-thread call threw " + o); ((Throwable) o).printStackTrace(System.out); return null; }
        @SuppressWarnings("unchecked") T t = (T) o; return t;
    }
    static void game(Runnable r) { onGame(() -> { r.run(); return Boolean.TRUE; }); }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static List<String> lines() {
        try { File f = new File(Failures.failDir(), Watchdog.Daily.utc(System.currentTimeMillis()) + ".jsonl"); return f.exists() ? Files.readAllLines(f.toPath()) : new ArrayList<>(); } catch (Throwable e) { return new ArrayList<>(); }
    }
    /** Today's written records of this reason (optionally for one bot), parsed. */
    static List<JSONObject> recs(String reason, String bot) {
        Failures.flushNow(5000);
        List<JSONObject> out = new ArrayList<>();
        for (String l : lines()) {
            if (!l.contains("\"reason\":\"" + reason + "\"")) continue;
            try { JSONObject j = (JSONObject) new JSONParser().parse(l); if (bot == null || bot.equals(j.get("bot"))) out.add(j); } catch (Throwable ignored) { }
        }
        return out;
    }
    static int fail(String code) { java.util.Map<String, Integer> m = Watchdog.Daily.TODAY.get("fail"); return m == null ? 0 : m.getOrDefault(code, 0); }
    static int bugCount(String id) { synchronized (Failures.Bugs.LOCK) { Failures.Bug b = Failures.Bugs.VIEW.get(id); return b == null ? 0 : b.count; } }

    /** The adventurers online now (game-thread read of BRAINS). */
    static List<SmartAdventurers.Brain> brains() {
        List<SmartAdventurers.Brain> l = onGame(() -> { List<SmartAdventurers.Brain> o = new ArrayList<>(); for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) if (b.bot != null && b.bot.isActive()) o.add(b); return o; });
        return l == null ? new ArrayList<>() : l;
    }
    static List<SmartAdventurers.Brain> waitBrains(int n, long maxMs) {
        long t0 = System.currentTimeMillis(); List<SmartAdventurers.Brain> l = brains();
        while (l.size() < n && System.currentTimeMillis() - t0 < maxMs) { sleep(5000); l = brains(); }
        return l;
    }
    static long coins(Player p) { Long c = onGame(() -> Ledger.Wallet.coins(p)); return c == null ? -1 : c; }
    /** Sets a bot's coins to exactly n through the Wallet (game thread). */
    static void setCoins(Player p, long n) { game(() -> { long c = Ledger.Wallet.coins(p); if (c > n) Ledger.Wallet.pay(p, c - n); else if (c < n) Ledger.Wallet.give(p, n - c); }); }
    /** No record line names a non-artificial player online now, and none has an '@' (an e-mail) in it. */
    static String privacy() {
        List<String> humans = onGame(() -> { List<String> h = new ArrayList<>(); for (Player p : core.game.world.repository.Repository.getPlayers()) if (p != null && !p.isArtificial()) h.add(p.getUsername().toLowerCase()); return h; });
        int at = 0, mail = 0, named = 0, uid = 0;
        for (String l : lines()) {
            if (l.matches(".*[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+[.][A-Za-z]{2,}.*")) mail++;
            try { Object c = ((JSONObject) new JSONParser().parse(l)).get("ctx"); if (c != null && String.valueOf(c).contains("@")) at++; if (c != null && String.valueOf(c).toLowerCase().contains("uid")) uid++; } catch (Throwable ignored) { }
            if (humans != null) for (String h : humans) if (l.toLowerCase().contains("\"" + h + "\"")) named++;
        }
        return "lines " + lines().size() + ", ctx with '@' " + at + ", ctx with 'uid' " + uid + ", e-mail-like " + mail + ", naming an online human " + named + " (humans online " + (humans == null ? "?" : humans.size()) + ")";
    }
}
