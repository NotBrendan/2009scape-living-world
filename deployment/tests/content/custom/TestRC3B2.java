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
import java.util.HashSet;
import java.util.Set;

/**
 * Trial only (-Dtest.rc3b2=true -Dsurvey.doors=1 -Dsurvey.doors.round=2 -Dsurvey.tunnels=0 -Dsurvey.stairs=0 -Dsurvey.offground=0
 * -Dsurvey.count=0 -Dtraders.fast=true -Dsmartbots.count=2 -Dsmartbots.high=0 -Dafk.debug=true; SEED=live): batch 18 rc3 change 2,
 * door explorers. Reads the seed doors.jsonl at boot (tile/id/option keys), then watches new doors.jsonl lines: none may be a door
 * already in the seed (settled), new lines carry a "stamp", and the explorer retires after a round with nothing new
 * (survey.doors.round=2 towns here to keep the trial short; live default is all 14 towns). Cap 25 min (12 min if no DOORS_RETIRED).
 * BEFORE (rc2): keyed by tile only, retires after 28 empty sites, so it does not retire within the cap.
 */
public final class TestRC3B2 implements StartupListener {
    static void log(String s) { System.out.println("[TestRC3B2] " + s); }

    static String key(JSONObject j) {
        JSONArray at = (JSONArray) j.get("at");
        return at.get(0) + "," + at.get(1) + "," + at.get(2) + "#" + j.get("id") + "#" + (j.get("option") != null ? j.get("option") : "special");
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.rc3b2")) return;
        final long t0 = System.currentTimeMillis();
        final Set<String> seed = new HashSet<>(), seedTiles = new HashSet<>();
        File f = new File(new File(ServerConstants.DATA_PATH, "survey"), "doors.jsonl");
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            JSONParser p = new JSONParser();
            for (String line; (line = r.readLine()) != null; ) { try { JSONObject j = (JSONObject) p.parse(line); seed.add(key(j)); seedTiles.add(key(j).split("#")[0]); } catch (Throwable ignored) { } }
        } catch (Throwable t) { log("seed: " + t); }
        log("seed doors.jsonl: " + seed.size() + " doors/gates (tile/id/option), " + seedTiles.size() + " tiles");
        GameWorld.getPulser().submit(new Pulse(10) {
            long retiredAt; boolean field = true;
            @Override public boolean pulse() {
                try {
                    long now = System.currentTimeMillis();
                    int retired = 0;
                    try { java.lang.reflect.Field rf = Explorers.class.getDeclaredField("DOORS_RETIRED"); rf.setAccessible(true); retired = ((java.util.concurrent.atomic.AtomicInteger) rf.get(null)).get(); } catch (NoSuchFieldException e) { field = false; }
                    if (retired > 0 && retiredAt == 0) { retiredAt = now; log("door explorer retired after " + (now - t0) / 1000 + " s"); }
                    boolean finish = (retiredAt > 0 && now - retiredAt > 30_000) || now - t0 > (field ? 25 : 12) * 60_000L;
                    if (!finish) return false;
                    int fresh = 0, again = 0, sameTile = 0, stamped = 0;
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        JSONParser p = new JSONParser();
                        for (String line; (line = r.readLine()) != null; ) {
                            JSONObject j; try { j = (JSONObject) p.parse(line); } catch (Throwable e) { continue; }
                            if (((Number) j.get("t")).longValue() < t0) continue;
                            fresh++; String k = key(j);
                            if (seed.contains(k)) { again++; log("retried a settled door: " + line); }
                            else if (seedTiles.contains(k.split("#")[0])) sameTile++;
                            if (j.get("stamp") != null) stamped++;
                        }
                    }
                    log("RESULT new doors.jsonl lines " + fresh + " (" + stamped + " with a stamp), settled doors retried " + again + ", new id/option on a seed tile " + sameTile + ", door explorer retired " + (retired > 0) + ", " + (now - t0) / 1000 + " s");
                    log("RESULT " + (again == 0 && retired > 0 && stamped == fresh ? "PASS: no settled door retried, new results stamped, retired after a round with nothing new" : "FAIL"));
                    log("DONE");
                    return true;
                } catch (Throwable t) { log("error " + t); t.printStackTrace(); log("DONE"); return true; }
            }
        });
    }
}
