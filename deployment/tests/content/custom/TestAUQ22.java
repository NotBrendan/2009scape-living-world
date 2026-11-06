package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Trial only (-Dtest.auq22=true, without traders.fast so the 10-minute timer save stays out of the way): audit 10-23 Q-22.
 * Two quest finishes 5 s apart; quests.json read 40 s later: are both in it? Then (when write(json, builtAt) exists) a newer
 * snapshot written, then an older one: which one is on disk?
 */
public final class TestAUQ22 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUQ22] " + s); }
    static String file() { try { return new String(Files.readAllBytes(QuestBook.file().toPath()), StandardCharsets.UTF_8); } catch (Throwable t) { return ""; } }
    static String has(String json, AIPlayer b) {
        try {
            org.json.simple.JSONObject bots = (org.json.simple.JSONObject) ((org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(json)).get("bots");
            Object j = bots == null ? null : bots.get(b.getUsername().toLowerCase(java.util.Locale.ROOT));
            return j == null ? "missing" : "completions " + ((org.json.simple.JSONObject) j).get("completions");
        } catch (Throwable t) { return "unreadable " + t; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.auq22")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, b; QuestScript s;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 200) {
                        s = QuestBook.SCRIPTS.get("sheep");
                        a = TestCR1.bot(null, 10, 1, 1, 1); b = TestCR1.bot(null, 10, 1, 1, 1);
                        QuestBook.finished(a, s, 0);
                        log("finished #1 by " + a.getUsername());
                    }
                    if (t == 208) { QuestBook.finished(b, s, 0); log("finished #2 by " + b.getUsername() + " (5 s later)"); }
                    if (t == 275) {
                        String j = file();
                        log("RESULT 40 s after the second finish, quests.json: " + a.getUsername() + " " + has(j, a) + "; " + b.getUsername() + " " + has(j, b));
                        java.lang.reflect.Method w = null;
                        try { w = QuestBook.class.getDeclaredMethod("write", String.class, long.class); } catch (NoSuchMethodException e) { }
                        if (w == null) log("RESULT ordering: write(json, builtAt) not present (no snapshot stamps)");
                        else {
                            w.setAccessible(true);
                            long newer = System.nanoTime(), older = newer - 1_000_000_000L;
                            w.invoke(null, "{\"bots\":{},\"marker\":\"newer\"}", newer);
                            w.invoke(null, "{\"bots\":{},\"marker\":\"older\"}", older);
                            log("RESULT ordering: newer written, then an older snapshot: on disk now " + (file().contains("\"newer\"") ? "the NEWER" : "the OLDER") + " one");
                        }
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
