package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.RegionManager;
import core.game.world.repository.Repository;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Trial only (-Dtest.rc3b1=true -Dsurvey.offground=1 -Dsurvey.tunnels=0 -Dsurvey.stairs=0 -Dsurvey.doors=0 -Dsurvey.count=0
 * -Dtraders.fast=true -Dsmartbots.count=2 -Dsmartbots.high=0 -Dafk.debug=true; SEED=live): batch 18 rc3 change 1, off-ground
 * explorers and dangerous landings. Forces the Taverley dungeon ladder 2884,3397 (id 20987, recorded landing 2884,9796: black
 * demons, blue dragons, a hellhound nearby) to the top of the list, uncovered.
 *  A: the explorer should skip it before climbing (recorded landing + engine NPCs) and write a "dangerFor" offgrid.jsonl line.
 *  B: the test turns the before-climbing check off (OffGround.PRECHECK) and frees it again: the explorer climbs, sees aggressive
 *     NPCs above its level in the flood fill, teleports back at once and records "dangerFor"; then it is not claimed again (90 s).
 * Phase B lowers the explorer to combat ~3 (the Taverley demons/dragons are not reachable from the landing, so nothing above 80 is in the fill).
 * BEFORE (rc2): the explorer climbs down and explores the fill (no check); phase B is not possible (no PRECHECK field).
 */
public final class TestRC3B1 implements StartupListener {
    static void log(String s) { System.out.println("[TestRC3B1] " + s); }
    static final String KEY = "c:2884,3397,0#20987";

    static Object field(Object o, Class<?> c, String f) { try { java.lang.reflect.Field x = c.getDeclaredField(f); x.setAccessible(true); return x.get(o); } catch (Throwable t) { return null; } }
    static boolean setField(Object o, Class<?> c, String f, Object v) { try { java.lang.reflect.Field x = c.getDeclaredField(f); x.setAccessible(true); x.set(o, v); return true; } catch (Throwable t) { return false; } }

    static List<JSONObject> records(long t0) {
        List<JSONObject> out = new ArrayList<>();
        File f = new File(new File(ServerConstants.DATA_PATH, "survey"), "offgrid.jsonl");
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            JSONParser p = new JSONParser();
            for (String line; (line = r.readLine()) != null; ) {
                try { JSONObject j = (JSONObject) p.parse(line); if (KEY.equals(j.get("key")) && ((Number) j.get("t")).longValue() >= t0) out.add(j); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void force(OffGround.Cand c) {
        synchronized (OffGround.CANDS) {
            c.covered = false; c.done = false; c.taken = null; c.deferUntil = 0; c.demand = 1e6;
            setField(c, OffGround.Cand.class, "dangerFor", -1);
            OffGround.CANDS.sort((a, b) -> Double.compare(b.demand, a.demand));
        }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.rc3b1")) return;
        final long t0 = System.currentTimeMillis();
        GameWorld.getPulser().submit(new Pulse(5) {
            int stage; long stageAt, end = t0 + 15 * 60_000L; OffGround.Cand c; String aRes, bRes, botName = ""; Object aDanger, bDanger; int bCount; boolean bSurface;
            @Override public boolean pulse() {
                try {
                    long now = System.currentTimeMillis();
                    if (now > end) { log("RESULT timed out at stage " + stage + " (A " + aRes + ", B " + bRes + ")"); log("RESULT FAIL"); log("DONE"); return true; }
                    if (stage == 0) {
                        if (!OffGround.ready) return false;
                        c = OffGround.BY_KEY.get(KEY);
                        if (c == null) {
                            Scenery s = RegionManager.getObject(0, 2884, 3397);
                            if (s == null) { log("RESULT setup failed: no ladder at 2884,3397"); log("DONE"); return true; }
                            c = OffGround.connector(s, OffGround.connectorOption(s) != null ? OffGround.connectorOption(s) : "Climb-down", null, 0); OffGround.add(c);
                        }
                        Object landing = ((java.util.Map<?, ?>) (field(null, OffGround.class, "LANDING") != null ? field(null, OffGround.class, "LANDING") : new java.util.HashMap<>())).get("2884,3397,0#20987");
                        log("setup: " + c + " covered " + c.covered + ", recorded landing " + landing + "; npcs in the repository " + Repository.getNpcs().size() + "; precheck field " + (field(null, OffGround.class, "PRECHECK") != null));
                        force(c); stage = 1; stageAt = now;
                        return false;
                    }
                    List<JSONObject> rs = records(t0);
                    if (stage == 1) {
                        if (rs.isEmpty()) return false;
                        JSONObject a = rs.get(0); aRes = String.valueOf(a.get("result")); aDanger = a.get("dangerFor");
                        log("A: offgrid record result \"" + aRes + "\", success " + a.get("success") + ", dangerFor " + aDanger + ", danger " + a.get("danger") + ", landing " + a.get("landing"));
                        if (!setField(null, OffGround.class, "PRECHECK", false)) { log("RESULT BEFORE behaviour: no danger check; A result \"" + aRes + "\""); log("RESULT " + (aDanger != null ? "PASS?" : "FAIL (rc2: climbed)")); log("DONE"); return true; }
                        Object dm = field(null, OffGround.class, "DANGER"); if (dm instanceof java.util.Map) ((java.util.Map<?, ?>) dm).remove(KEY);
                        // from the Taverley landing the demons and dragons are behind walls (key gate, pipe): nothing above 80 is reachable, so phase B
                        // drops the explorer to combat 3 (all combat skills 1): the aggressive skeletons, ghosts and spiders in the fill are then above it
                        String an = String.valueOf(a.get("bot"));
                        for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && an.equals(p.getUsername())) {
                            for (int sk : new int[]{0, 1, 2, 3, 4, 5, 6}) { p.getSkills().setStaticLevel(sk, sk == 3 ? 10 : 1); p.getSkills().setLevel(sk, sk == 3 ? 10 : 1); }
                            p.getSkills().updateCombatLevel(); log("phase B: " + an + " now combat " + p.getProperties().getCombatLevel());
                        }
                        force(c); stage = 2; stageAt = now;
                        return false;
                    }
                    if (stage == 2) {
                        if (rs.size() < 2) return false;
                        JSONObject b = rs.get(1); bRes = String.valueOf(b.get("result")); bDanger = b.get("dangerFor");
                        log("B: offgrid record result \"" + bRes + "\", success " + b.get("success") + ", dangerFor " + bDanger + ", danger " + b.get("danger") + ", landing " + b.get("landing"));
                        botName = String.valueOf(b.get("bot"));
                        for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && botName.equals(p.getUsername())) { bSurface = p.getLocation().getY() < 6400; log("explorer " + p.getUsername() + " (combat " + p.getProperties().getCombatLevel() + ") at " + p.getLocation() + " " + (System.currentTimeMillis() - ((Number) b.get("t")).longValue()) + " ms after the record"); }
                        core.game.world.map.Location land = core.game.world.map.Location.create(2884, 9796, 0);
                        OffGround.Fill fl = OffGround.fill(land, 2500, 48);
                        java.util.Set<?> seen = (java.util.Set<?>) field(fl, OffGround.Fill.class, "seen");
                        int shown = 0;
                        for (core.game.node.entity.npc.NPC n : Repository.getNpcs()) {
                            if (n == null || n.getLocation().getZ() != 0 || n.getLocation().getDistance(land) > 30 || n.getDefinition().getCombatLevel() <= 80) continue;
                            if (shown++ >= 15) break;
                            log("  npc near the landing: " + n.getName() + " lvl " + n.getDefinition().getCombatLevel() + " at " + n.getLocation() + " aggressive " + n.isAggressive() + " handler " + (n.getAggressiveHandler() != null) + " active " + n.isActive() + " in fill " + (seen != null && seen.contains(((long) n.getLocation().getX() << 32) | n.getLocation().getY())));
                        }
                        log("  fill from the landing: " + fl.tiles + " tiles, box " + fl.x0 + "," + fl.y0 + "-" + fl.x1 + "," + fl.y1);
                        stage = 3; stageAt = now;
                        return false;
                    }
                    if (stage == 3) {
                        if (now - stageAt < 90_000) return false;
                        bCount = rs.size();
                        log("RESULT A (before climbing) \"" + aRes + "\" dangerFor " + aDanger + "; B (after landing) \"" + bRes + "\" dangerFor " + bDanger + "; back on the surface " + bSurface + "; records for the key after 90 s more " + bCount + " (2 = not retried); cand dangerFor " + field(c, OffGround.Cand.class, "dangerFor") + "; " + OffGround.counts());
                        boolean pass = aDanger != null && aRes.startsWith("skipped before climbing") && bDanger != null && bRes.contains("left at once") && bSurface && bCount == 2;
                        log("RESULT " + (pass ? "PASS: skipped before climbing, left at once after landing, recorded and not retried" : "FAIL"));
                        log("DONE");
                        return true;
                    }
                    return false;
                } catch (Throwable t) { log("error " + t); t.printStackTrace(); log("DONE"); return true; }
            }
        });
    }
}
