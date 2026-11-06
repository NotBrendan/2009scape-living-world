package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Trial only (-Dtest.auo1=true): audit 10-23 O-1. Run after prep (smartbots.json entry 5 without "levels", garbage in
 * artisans.json, servicebots.json and grave-recovery.json, the original book copied to data/smartbots.orig.json).
 * Forces every save once the artisans are up, then reports what is on disk.
 */
public final class TestAUO1 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUO1] " + s); }
    static String read(File f) { try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Throwable t) { return null; } }
    static String bads(File dir, String name) {
        StringBuilder b = new StringBuilder(); File[] l = dir.listFiles(); if (l != null) for (File x : l) if (x.getName().startsWith(name + ".bad-")) b.append(x.getName()).append(' ');
        return b.length() == 0 ? "none" : b.toString().trim();
    }
    static String describe(File f) {
        String s = read(f); if (s == null) return "missing";
        if (s.startsWith("GARBAGE")) return "still the original garbage (" + s.length() + " bytes)";
        try { Object o = new org.json.simple.parser.JSONParser().parse(s); return "rewritten: " + (o instanceof java.util.List ? ((java.util.List<?>) o).size() + " entries" : ((java.util.Map<?, ?>) o).keySet().toString()); } catch (Throwable t) { return "unparseable " + t; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.auo1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t != 150) return false;
                try {
                    log("artisans live " + Artisans.ARTISAN_BOTS.size() + ", service bots live " + ServiceBots.LIVE.size() + ", profiles loaded " + SmartAdventurers.SAVED.size() + " + high " + SmartAdventurers.SAVED_HIGH.size());
                    SmartAdventurers.GRAVE_RECOVERY.put("au_o1_player", new java.util.ArrayList<>(java.util.Collections.singletonList(new int[]{1205, 1})));
                    SmartAdventurers.saveProfiles(); Artisans.saveAll(); ServiceBots.saveAll(); SmartAdventurers.saveGraveRecovery();
                    File data = new File(core.ServerConstants.DATA_PATH), eco = new File(core.ServerConstants.GRAND_EXCHANGE_DATA_PATH);
                    org.json.simple.JSONArray orig = (org.json.simple.JSONArray) new org.json.simple.parser.JSONParser().parse(read(new File(data, "smartbots.orig.json")));
                    String now = read(new File(data, "smartbots.json")); int kept = 0; StringBuilder lost = new StringBuilder();
                    for (Object o : orig) { String n = String.valueOf(((org.json.simple.JSONObject) o).get("name")); if (now != null && now.contains("\"" + n + "\"")) kept++; else lost.append(n).append(' '); }
                    log("RESULT smartbots.json after a save: " + kept + " of " + orig.size() + " original bots still in it" + (lost.length() > 0 ? "; lost: " + lost.toString().trim() : "") + "; copies: " + bads(data, "smartbots.json"));
                    log("RESULT artisans.json: " + describe(new File(eco, "artisans.json")) + "; copies: " + bads(eco, "artisans.json"));
                    log("RESULT servicebots.json: " + describe(new File(eco, "servicebots.json")) + "; copies: " + bads(eco, "servicebots.json"));
                    log("RESULT grave-recovery.json: " + describe(new File(eco, "grave-recovery.json")) + "; copies: " + bads(eco, "grave-recovery.json"));
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
