package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.player.Player;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Batch 24: server glue for {@link Bargain}: data dir (log, kill marker), bargain-lines.json, the AFK-runner check, trust. */
public final class BargainHooks implements StartupListener {
    @Override public void startup() {
        try { Bargain.init(new File(ServerConstants.DATA_PATH)); } catch (Throwable t) { System.out.println("[Bargain] init failed: " + t); }
        try { loadLines(); } catch (Throwable t) { System.out.println("[Bargain] bargain-lines.json not loaded, using built-in lines: " + t); }
        System.out.println("[Bargain] " + (Bargain.on() ? "ON" : "off") + (Bargain.tripped ? " (tripped: " + Bargain.tripReason + ")" : ""));
    }

    static void loadLines() throws Exception {
        InputStream in = BargainHooks.class.getResourceAsStream("bargain-lines.json");
        if (in == null) throw new IllegalStateException("bargain-lines.json missing");
        JSONObject j = (JSONObject) new JSONParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        int n = 0;
        for (Object o : j.entrySet()) {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            if (!(e.getValue() instanceof JSONArray)) continue;
            JSONArray a = (JSONArray) e.getValue();
            String[] v = new String[a.size()];
            for (int i = 0; i < v.length; i++) v[i] = String.valueOf(a.get(i));
            if (v.length > 0) { Bargain.LINES.put(String.valueOf(e.getKey()), v); n++; }
        }
        System.out.println("[Bargain] " + n + " line sets loaded");
    }

    /** True while this player's own ::afk runner is running: its purchases pay list price. */
    static boolean afk(Player p) {
        return p != null && AfkTasks.RUNNING.containsKey(p.getUsername().toLowerCase(Locale.ROOT));
    }

    static String style(Player bot) {
        try { return BotChat.Persona.of(bot).style.name(); } catch (Throwable t) { return null; }
    }

    static String key(Player bot) { return bot.getUsername().toLowerCase(Locale.ROOT); }

    /**
     * Trust for a haggle, after amendment 1's recovery: points a walk-away strike took come back +1 per 24 h
     * (Memory.Met.owed). Use this, not Trust.of, when opening a Bargain session.
     */
    static int trust(Player h, Player bot) {
        if (h == null || bot == null) return 0;
        try {
            if (!h.isArtificial()) {
                BotChat.Memory.Met m = BotChat.Memory.get(h);
                long[] o = m == null ? null : m.owed.get(key(bot));
                if (o != null) {
                    int n; synchronized (o) { n = Bargain.recoverDue(o, System.currentTimeMillis()); }
                    if (n > 0) { BotChat.Trust.add(h, bot, n); System.out.println("[Bargain] trust +" + n + " back for " + h.getUsername() + " with " + bot.getUsername() + " (24 h recovery)"); }
                    if (o[0] <= 0) m.owed.remove(key(bot), o);
                }
            }
        } catch (Throwable t) { System.out.println("[Bargain] trust recovery: " + t); }
        return BotChat.Trust.of(h, bot);
    }

    /** A walk-away strike: trust -1, never below 0 (decision 1); a point really taken is owed back (amendment 1). */
    static void strike(Player h, Player bot) {
        if (h == null || bot == null || h.isArtificial()) return;
        long now = System.currentTimeMillis();
        BotChat.Memory.Met m = BotChat.Memory.met(h);
        m.walkedAway = now;
        if (BotChat.Trust.of(h, bot) > 0) {
            BotChat.Trust.add(h, bot, -1);
            m.owed.compute(key(bot), (k, o) -> { if (o == null) return Bargain.struck(null, now); synchronized (o) { return Bargain.struck(o, now); } });
        }
    }

    /** A lowball bid (Bargain.Result.lowball): counted in Memory.Met. */
    static void lowball(Player h) {
        if (h == null || h.isArtificial()) return;
        BotChat.Memory.Met m = BotChat.Memory.met(h);
        m.lowballs++; m.lowballAt = System.currentTimeMillis();
    }

    /** A finished haggled sale: kept in Memory.Met.deals (last 5). */
    static void dealt(Player h, Player bot, String item, int qty, long total) {
        if (h == null || bot == null || h.isArtificial()) return;
        Bargain.remember(BotChat.Memory.met(h).deals, new Bargain.Deal(bot.getUsername(), item, qty, total, System.currentTimeMillis()));
    }
}
