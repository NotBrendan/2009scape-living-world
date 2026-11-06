package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Trial only (-Dtest.b18e1=true -Dsurvey.tunnels=1 -Dsurvey.stairs=0 -Dsurvey.doors=0 -Dsurvey.offground=0 -Dsurvey.count=0
 * -Dtraders.fast=true): batch 18 item1, shortcut seekers. Gates' shortcut table is cut to two real shortcuts before the
 * Agility-1 seeker spawns: G, the Falador underwall tunnel 2948,3312 (engine level 26), set to "failed once for the low band,
 * 7 hours ago, no exit known" (the old 6-hour retest rule takes it again); U, the Lumbridge farm stile 3197,3276, all results
 * cleared (untested). Watches shortcuts.jsonl for 20 minutes after the seeker spawns.
 * BEFORE: the seeker tests U, then retests G ("nothing happened"). AFTER: it tests U, never G, then retires.
 */
public final class TestB18E1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB18E1] " + s); }
    static final String G = "2948,3312,0", U = "3197,3276,0";

    static void set(Object o, String f, Object v) { try { java.lang.reflect.Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v); } catch (NoSuchFieldException ignored) { } catch (Throwable t) { log("set " + f + ": " + t); } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b18e1")) return;
        final long t0 = System.currentTimeMillis();
        GameWorld.getPulser().submit(new Pulse(5) {
            boolean setUp; long end; int gNothing, gAll, uAll; boolean doneU; long retiredAt;
            @Override public boolean pulse() {
                try {
                    long now = System.currentTimeMillis();
                    if (!setUp) {
                        if (Gates.SHORTCUTS.isEmpty() || Gates.SHORTCUTS.get(G) == null || Gates.SHORTCUTS.get(U) == null) { if (now - t0 > 50_000) { log("RESULT setup failed: G or U not in gates.json (" + Gates.SHORTCUTS.size() + " shortcuts)"); log("DONE"); return true; } return false; }
                        Gates.Shortcut g = Gates.SHORTCUTS.get(G), u = Gates.SHORTCUTS.get(U);
                        g.lowOk = 0; g.lowFail = 1; g.to = null; g.tested = now - 7 * 3600_000L; set(g, "lowT", now - 7 * 3600_000L);
                        u.lowOk = 0; u.lowFail = 0; u.highOk = 0; u.highFail = 0; u.to = null; u.tested = 0; set(u, "lowT", 0L); set(u, "lowStamp", null); set(u, "highStamp", null);
                        setUp = true; end = now + 21 * 60_000L;
                        log("setup: G " + g.name + " at " + G + " level " + g.level + " (low band failed once, 7 h ago); U " + u.name + " at " + U + " level " + u.level + " untested; " + Gates.SHORTCUTS.size() + " shortcuts cut to these two");
                    }
                    Gates.SHORTCUTS.keySet().removeIf(k -> !k.equals(G) && !k.equals(U)); // the region scan may add more
                    gNothing = 0; gAll = 0; uAll = 0;
                    File f = new File(new File(ServerConstants.DATA_PATH, "survey"), "shortcuts.jsonl");
                    if (f.exists()) try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        JSONParser p = new JSONParser();
                        for (String line; (line = r.readLine()) != null; ) {
                            JSONObject j; try { j = (JSONObject) p.parse(line); } catch (Throwable e) { continue; }
                            if (((Number) j.get("t")).longValue() < t0) continue;
                            JSONArray at = (JSONArray) j.get("at"); String k = at.get(0) + "," + at.get(1) + "," + at.get(2);
                            String res = String.valueOf(j.get("result"));
                            if (k.equals(G)) { gAll++; if (res.startsWith("nothing")) gNothing++; }
                            if (k.equals(U)) { uAll++; if (!res.startsWith("never")) doneU = true; }
                        }
                    }
                    int retired = 0;
                    try { java.lang.reflect.Field rf = Explorers.class.getDeclaredField("TUNNELS_RETIRED"); rf.setAccessible(true); retired = ((java.util.concurrent.atomic.AtomicInteger) rf.get(null)).get(); } catch (NoSuchFieldException ignored) { }
                    if (retired > 0 && retiredAt == 0) { retiredAt = now; log("seeker retired"); }
                    boolean finish = gNothing > 0 || now > end || (retiredAt > 0 && now - retiredAt > 60_000);
                    if (!finish) return false;
                    log("RESULT gated shortcut G records " + gAll + " (\"nothing happened\" " + gNothing + "), untested U records " + uAll + " (tested " + doneU + "), seeker retired " + (retired > 0) + ", " + (now - t0) / 1000 + " s");
                    log("RESULT " + (gNothing == 0 && doneU && retired > 0 ? "PASS: skipped the gated one, tested the untested one, retired" : "seeker retried the gated shortcut or did not finish"));
                    log("DONE");
                    return true;
                } catch (Throwable t) { log("error " + t); t.printStackTrace(); log("DONE"); return true; }
            }
        });
    }
}
