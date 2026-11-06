package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Trial only (-Dtest.b141=true): batch 14 items 1 and 2. Run after prep: GARBAGE in death-memory.json, gates.json,
 * resources.json, tiles.json, botchat-memory.json, botchat-trust.json and arrav-pool.json; smartbots.json cut to half its
 * bots (valid JSON) with data/eco/last-save.json saying the full count. Forces every save, then reports what is on disk.
 * Compiles against batch 13 and 14 (no new API used).
 */
public final class TestB141 implements StartupListener {
    static void log(String s) { System.out.println("[TestB141] " + s); }
    static String read(File f) { try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Throwable t) { return null; } }
    static String bads(File f) {
        StringBuilder b = new StringBuilder(); File[] l = f.getParentFile().listFiles();
        if (l != null) for (File x : l) if (x.getName().startsWith(f.getName() + ".bad-")) { String c = read(x); b.append(x.getName()).append(c != null && c.startsWith("GARBAGE") ? "(garbage)" : "").append(' '); }
        return b.length() == 0 ? "none" : b.toString().trim();
    }
    static String describe(File f) {
        String s = read(f); if (s == null) return "missing";
        if (s.startsWith("GARBAGE")) return "ORIGINAL KEPT (still the garbage, " + s.length() + " bytes)";
        try { Object o = new org.json.simple.parser.JSONParser().parse(s); return "REWRITTEN: " + (o instanceof java.util.List ? ((java.util.List<?>) o).size() + " entries" : ((java.util.Map<?, ?>) o).size() + " keys"); } catch (Throwable t) { return "unparseable " + t; }
    }
    static void report(String what, File f) { log("RESULT " + what + ": " + describe(f) + "; copies: " + bads(f)); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b141")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t != 200) return false;
                File data = new File(core.ServerConstants.DATA_PATH), eco = new File(core.ServerConstants.GRAND_EXCHANGE_DATA_PATH), survey = new File(data, "survey");
                File book = new File(data, "smartbots.json"); long bookBefore = book.lastModified(); String bookText = read(book);
                try {
                    Resources.load(); Resources.SEEN.computeIfAbsent("b141-test", k -> new java.util.concurrent.ConcurrentHashMap<>()).put(1, 1); // so Resources.save has something to write
                    BotChat.Memory.MET.put("b141_player", new BotChat.Memory.Met()); BotChat.Trust.T.put("b141_player|b141_bot", 1);
                    QuestShieldOfArrav.Pool.count(1); synchronized (QuestShieldOfArrav.Pool.class) { QuestShieldOfArrav.Pool.N.put(1, 1); }
                    try { SmartAdventurers.saveDeathMemory(); } catch (Throwable e) { log("death memory save threw " + e); }
                    try { Gates.save(); } catch (Throwable e) { log("gates save threw " + e); }
                    try { Resources.save(); } catch (Throwable e) { log("resources save threw " + e); }
                    try { BotChat.Memory.save(); BotChat.Trust.save(); } catch (Throwable e) { log("botchat save threw " + e); }
                    try { QuestShieldOfArrav.Pool.save(); } catch (Throwable e) { log("pool save threw " + e); }
                    try { Surveyors.flush(); } catch (Throwable e) { log("survey flush threw " + e); }
                    try { SmartAdventurers.saveProfiles(); } catch (Throwable e) { log("profiles save threw " + e); }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                report("death-memory.json", new File(eco, "death-memory.json"));
                report("gates.json", new File(survey, "gates.json"));
                report("resources.json", new File(survey, "resources.json"));
                report("tiles.json", new File(survey, "tiles.json"));
                report("botchat-memory.json", new File(data, "botchat-memory.json"));
                report("botchat-trust.json", new File(data, "botchat-trust.json"));
                report("arrav-pool.json", new File(QuestBook.dir(), "arrav-pool.json"));
                String now = read(book); int n = -1;
                try { n = ((java.util.List<?>) new org.json.simple.parser.JSONParser().parse(now)).size(); } catch (Throwable ignored) { }
                log("RESULT smartbots.json (item 2): loaded " + SmartAdventurers.SAVED.size() + " + high " + SmartAdventurers.SAVED_HIGH.size() + "; after a forced save it has " + n + " entries, "
                        + (now != null && now.equals(bookText) && book.lastModified() == bookBefore ? "NOT REWRITTEN (byte-identical, same mtime)" : "REWRITTEN (mtime " + bookBefore + " -> " + book.lastModified() + ")")
                        + "; copies: " + bads(book) + "; sidecar: " + read(new File(eco, "last-save.json")));
                log("DONE");
                return true;
            }
        });
    }
}
