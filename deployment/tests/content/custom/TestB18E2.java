package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Trial only (-Dtest.b18e2=true -Dsurvey.offground=1 -Dsurvey.tunnels=0 -Dsurvey.stairs=0 -Dsurvey.doors=0 -Dsurvey.count=0
 * -Dtraders.fast=true -Dafk.debug=true): batch 18 item2, off-ground explorers. All by reflection (the base has no OffGround).
 * A: the list from the nav grid and the object cache: counts, Lumbridge castle connectors, a dungeon entrance.
 * B: the first uncovered ladder is put at the top of the list; the one explorer must use it and write an offgrid.jsonl record
 * with the landing plane and tiles and a flood fill, and a stairs.jsonl line. Up to 20 minutes.
 * BEFORE: "no OffGround class". AFTER: A and B.
 */
public final class TestB18E2 implements StartupListener {
    static void log(String s) { System.out.println("[TestB18E2] " + s); }
    static Object get(Class<?> c, Object o, String f) throws Exception { Field x = c.getDeclaredField(f); x.setAccessible(true); return x.get(o); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b18e2")) return;
        final long t0 = System.currentTimeMillis();
        GameWorld.getPulser().submit(new Pulse(5) {
            Class<?> og, cand; boolean listed; String ladderKey; long end;
            @Override public boolean pulse() {
                try {
                    long now = System.currentTimeMillis();
                    if (og == null) {
                        try { og = Class.forName("content.custom.OffGround"); cand = Class.forName("content.custom.OffGround$Cand"); }
                        catch (ClassNotFoundException e) { log("RESULT no OffGround class: no off-ground list, no off-ground explorers"); log("DONE"); return true; }
                    }
                    if (!(Boolean) get(og, null, "ready")) { if (now - t0 > 15 * 60_000L) { log("RESULT list never ready"); log("DONE"); return true; } return false; }
                    @SuppressWarnings("unchecked") List<Object> cands = (List<Object>) get(og, null, "CANDS");
                    if (!listed) {
                        listed = true; end = now + 20 * 60_000L;
                        java.lang.reflect.Method m = og.getDeclaredMethod("counts"); m.setAccessible(true);
                        log("RESULT list: " + m.invoke(null));
                        int lum = 0, dun = 0, upFloor = 0;
                        synchronized (cands) {
                            for (Object c : cands) {
                                if (!"connector".equals(get(cand, c, "kind"))) continue;
                                int x = (Integer) get(cand, c, "x"), y = (Integer) get(cand, c, "y");
                                String issue = String.valueOf(get(cand, c, "issue")), name = String.valueOf(get(cand, c, "name"));
                                if (x >= 3200 && x <= 3215 && y >= 3205 && y <= 3232) { lum++; if (issue.startsWith("upper")) upFloor++; if (lum <= 4) log("RESULT Lumbridge castle: " + c); }
                                if (issue.startsWith("below") && dun++ < 3) log("RESULT below ground: " + c);
                                boolean covered = (Boolean) get(cand, c, "covered"), done = (Boolean) get(cand, c, "done");
                                if (ladderKey == null && !covered && !done && name.toLowerCase().contains("ladder") && (Integer) get(cand, c, "z") == 0) ladderKey = String.valueOf(get(cand, c, "key"));
                            }
                            if (ladderKey != null) for (int i = 0; i < cands.size(); i++) if (ladderKey.equals(get(cand, cands.get(i), "key"))) { Object c = cands.remove(i); Field d = cand.getDeclaredField("demand"); d.setAccessible(true); d.set(c, 1000.0); cands.add(0, c); log("forced to the top: " + c); break; }
                        }
                        log("RESULT Lumbridge castle connectors " + lum + " (" + upFloor + " to upper floors), dungeon entrances " + dun + (dun > 3 ? "+" : ""));
                        if (ladderKey == null) { log("RESULT no uncovered ladder to test"); log("DONE"); return true; }
                    }
                    File f = new File(new File(ServerConstants.DATA_PATH, "survey"), "offgrid.jsonl");
                    if (f.exists()) try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        JSONParser p = new JSONParser();
                        for (String line; (line = r.readLine()) != null; ) {
                            JSONObject j; try { j = (JSONObject) p.parse(line); } catch (Throwable e) { continue; }
                            if (((Number) j.get("t")).longValue() < t0 || !ladderKey.equals(j.get("key"))) continue;
                            log("RESULT ladder record: " + line);
                            int st = 0;
                            File sf = new File(new File(ServerConstants.DATA_PATH, "survey"), "stairs.jsonl");
                            try (BufferedReader r2 = new BufferedReader(new FileReader(sf))) { for (String l2; (l2 = r2.readLine()) != null; ) { try { JSONObject s2 = (JSONObject) p.parse(l2); if (((Number) s2.get("t")).longValue() >= t0) { st++; log("RESULT stairs.jsonl: " + l2); } } catch (Throwable ignored) { } } }
                            JSONObject fill = (JSONObject) j.get("fill");
                            log("RESULT " + (Boolean.TRUE.equals(j.get("success")) && j.get("landing") != null && fill != null && ((Number) fill.get("tiles")).intValue() > 0 && st > 0 ? "PASS" : "FAIL") + ": success " + j.get("success") + ", landing " + j.get("landing") + " plane " + j.get("plane") + ", fill " + (fill == null ? null : fill.get("tiles") + " tiles box " + fill.get("box") + " connectors " + ((List<?>) fill.get("connectors")).size()) + ", stairs.jsonl lines " + st);
                            log("DONE");
                            return true;
                        }
                    }
                    if (now > end) { log("RESULT FAIL: no record for the ladder in 20 minutes"); log("DONE"); return true; }
                    return false;
                } catch (Throwable t) { log("error " + t); t.printStackTrace(); log("DONE"); return true; }
            }
        });
    }
}
