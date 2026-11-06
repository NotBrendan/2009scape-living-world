package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trial only (-Dtest.rc3a1=true, with -Dtraders.fast=true -Dsurvey.replay=3 -Dsurvey.replay.prove=false -Dsurvey.replay.debug=true):
 * batch 18 rc3 part A, change 1 (replayers are not picked by NPC aggression). Compiles against rc2 too (BEFORE).
 *   Finds the surface clusters of aggressive NPCs (outside the Wilderness, combat 8-60, 16x16 blocks, the 4 densest).
 *   Control (engine mechanism): two level-3 test bots stand 150 ticks in cluster 0, A with the "ignore_aggression" attribute, B without:
 *     expect B attacked, A never in combat.
 *   Replays: 3 "seed|other" scenarios from the centre of clusters 1-3 to a tile 25 east; the 3 replayers walk them. Expect (AFTER) no
 *     "attacked" skips; BEFORE (rc2) some "skipped: attacked ...". Every 5 ticks: who holds the attribute (only replayers in a replay, plus
 *     control A; never another bot), and whether each replayer in a replay holds it / still holds it with no scenario.
 */
public final class TestRc3A1 implements StartupListener {
    static void log(String s) { System.out.println("[TestRc3A1] " + s); }
    static final String CALM = "ignore_aggression";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.rc3a1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, allAt = -1, ctlEnd = -1, aCombat, bCombat, aHit, bHit, aLp, bLp, stray, walkWith, walkWithout, idleWith;
            AIPlayer a, b; final List<Location> centres = new ArrayList<>(); final List<String> ids = new ArrayList<>();
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 30) { setup(); return false; }
                    if (t < 30) return false;
                    if (a != null && t <= ctlEnd) {
                        if (a.inCombat()) aCombat++; if (b.inCombat()) bCombat++;
                        if (a.getSkills().getLifepoints() < aLp) aHit++; if (b.getSkills().getLifepoints() < bLp) bHit++;
                        aLp = a.getSkills().getLifepoints(); bLp = b.getSkills().getLifepoints();
                        if (t == ctlEnd) {
                            log("RESULT 1 control at " + centres.get(0) + ": A (attribute) in combat " + aCombat + " ticks, hit " + aHit + "; B (none) in combat " + bCombat + " ticks, hit " + bHit + " (expect A 0/0, B > 0)");
                            a.removeAttribute(CALM); log("control A attribute removed: " + a.getAttribute(CALM, false));
                            try { a.clear(); b.clear(); } catch (Throwable ignored) { }
                            a = null; b = null;
                        }
                    }
                    if (t % 5 == 0) watch();
                    int have = 0; for (String id : ids) { Scenarios.Res r = Scenarios.RESULTS.get(id); if (r != null && r.last != null) have++; }
                    if (have == ids.size() && a == null && t > ctlEnd && allAt < 0) allAt = t; // then 15 ticks for the 5 s flush
                    if (allAt > 0 && t >= allAt + 15 || t >= 2600) finish(have);
                    else return false;
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
                log("DONE"); return true;
            }

            void setup() {
                Map<Long, List<NPC>> cl = new HashMap<>();
                for (NPC n : Repository.getNpcs()) {
                    if (n == null || !n.isActive() || !n.isAggressive() || n.getAggressiveHandler() == null) continue;
                    Location l = n.getLocation(); int cb = n.getProperties().getCurrentCombatLevel();
                    if (l.getZ() != 0 || l.getX() < 2560 || l.getX() > 3400 || l.getY() < 3100 || l.getY() >= 3520 || cb < 8 || cb > 60) continue;
                    cl.computeIfAbsent(((long) (l.getX() >> 4) << 20) | (l.getY() >> 4), k -> new ArrayList<>()).add(n);
                }
                List<List<NPC>> top = new ArrayList<>(cl.values()); top.sort((x, y) -> y.size() - x.size());
                for (int i = 0; i < Math.min(4, top.size()); i++) {
                    List<NPC> g = top.get(i); int sx = 0, sy = 0; for (NPC n : g) { sx += n.getLocation().getX(); sy += n.getLocation().getY(); }
                    Location c = Location.create(sx / g.size(), sy / g.size(), 0); Location w = Surveyors.Surveyor.walkable(c); if (w == null) w = g.get(0).getLocation();
                    centres.add(w);
                    log("cluster " + i + ": " + g.size() + " aggressive NPCs (" + g.get(0).getName() + " cb " + g.get(0).getProperties().getCurrentCombatLevel() + ") around " + w);
                }
                if (centres.isEmpty()) { log("RESULT no aggressive clusters found"); return; }
                a = TestCR1.bot(centres.get(0), 10, 1, 1, 1); b = TestCR1.bot(centres.get(0).transform(1, 0, 0), 10, 1, 1, 1);
                a.setAttribute(CALM, true); aLp = 10; bLp = 10; ctlEnd = t + 150;
                log("control bots " + a.getUsername() + " (attribute) and " + b.getUsername() + " (none), combat " + a.getProperties().getCurrentCombatLevel());
                for (int i = 1; i < centres.size() || i == 1 && centres.size() == 1; i++) {
                    Location from = centres.get(Math.min(i, centres.size() - 1)); Location to = Surveyors.Surveyor.walkable(from.transform(25, 0, 0)); if (to == null) to = from.transform(25, 0, 0);
                    boolean ok = Scenarios.put(null, "seed", "other", "test: walk through aggressive NPCs", from, to, null, "rc3a1 aggression test " + i, -1, -1, -1, true);
                    String id = Scenarios.id("seed", "other", from, to); if (ok && !ids.contains(id)) ids.add(id);
                    log("seeded " + id + " " + from + " -> " + to + " (" + ok + ")");
                }
            }

            void watch() {
                Map<Player, Scenarios.Replayer> rep = new HashMap<>();
                for (Scenarios.Replayer r : Scenarios.ACTIVE) if (r.bot != null) rep.put(r.bot, r);
                for (Player p : Repository.getPlayers()) {
                    if (p == null || !p.isActive()) continue;
                    boolean has = Boolean.TRUE.equals(p.getAttribute(CALM, false));
                    Scenarios.Replayer r = rep.get(p);
                    if (r != null) { if (r.sc != null && r.phase == 3) { if (has) walkWith++; else walkWithout++; } else if (r.sc == null && has) idleWith++; }
                    else if (has && p != a) { stray++; if (stray <= 3) log("attribute on a non-replayer: " + p.getUsername()); }
                }
            }

            void finish(int have) {
                log("results for " + have + "/" + ids.size() + " seeds at tick " + t);
                int attacked = 0;
                for (org.json.simple.JSONObject j : Scenarios.readResults()) {
                    String id = String.valueOf(j.get("id")); if (!ids.contains(id)) continue;
                    String why = String.valueOf(j.get("why")); if (why.contains("attacked")) attacked++;
                    log("RESULT 1 replay " + id + " " + j.get("result") + " after " + j.get("ticks") + ": " + why);
                }
                for (String id : ids) { Scenarios.Res r = Scenarios.RESULTS.get(id); if (r != null && r.last != null) log("  in memory " + id + ": last " + r.last); }
                log("RESULT 1 attacked skips " + attacked + " (AFTER expect 0); walk ticks with the attribute " + walkWith + ", without " + walkWithout + " (AFTER expect 0 without); replayers holding it with no scenario " + idleWith + " (expect 0); non-replayers holding it " + stray + " (expect 0)");
            }
        });
    }
}
