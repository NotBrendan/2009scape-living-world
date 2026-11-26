package content.custom;

import content.global.skill.slayer.Master;
import content.global.skill.slayer.SlayerManager;
import content.global.skill.slayer.Tasks;
import core.api.StartupListener;
import core.cache.def.impl.NPCDefinition;
import core.game.bots.AIPlayer;
import core.game.node.entity.Entity;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.*;

/**
 * Trial only (-Dtest.p54=true): slayer v1 B0. No game code changed; the server's own SlayerManager on an AIPlayer.
 * Part 0: Turael's task table (name, levelReq, npc names, weight) and which a JOBS hunt can serve (names match).
 * Part 1: bot A, guarded login once, generate(TURAEL), 3 real kills (NPC.finalizeDeath(bot) -> NPCKillEvent): countdown, xp.
 * Part 2a: guarded login again on the same A (skipped by the attribute), 1 kill: expect -1.
 * Part 2b: UNGUARDED second login on A (the hazard): task kept?, hooks count, re-generate, 1 kill: expect -2 if doubled.
 * Part 2c: the real logout/login: A.clear(), a new AIPlayer B (what restoreBot does), guarded login, generate, 2 kills: expect -1 each.
 */
public final class TestP54Slayer implements StartupListener {
    static void log(String s) { System.out.println("[TestP54Slayer] " + s); }
    static final Location AT = Location.create(3150, 3300, 0);

    static boolean guardedLogin(Player p) {
        if (p.getAttribute("slayer-manager", null) != null) return false;
        new SlayerManager().login(p);
        return true;
    }
    static int hooks(Entity e) {
        try {
            java.lang.reflect.Field f = Entity.class.getDeclaredField("hooks"); f.setAccessible(true);
            Map<?, ?> m = (Map<?, ?>) f.get(e);
            Object l = m.get(core.api.Event.getNPCKilled());
            return l == null ? 0 : ((List<?>) l).size();
        } catch (Throwable t) { return -1; }
    }
    static String state(Player p) {
        SlayerManager m = SlayerManager.getInstance(p);
        return "task " + (m.getTask() == null ? "none" : m.getTask().name()) + " amount " + m.getAmount() + " hasTask " + m.hasTask()
            + " slayerXp " + (int) p.getSkills().getExperience(Skills.SLAYER) + " hooks " + hooks(p);
    }
    static String names(Tasks t) {
        Set<String> s = new TreeSet<>();
        for (int id : t.getNpcs()) { try { NPCDefinition d = NPCDefinition.forId(id); if (d != null && d.getName() != null) s.add(d.getName()); } catch (Throwable ignored) { } }
        return String.join("/", s);
    }
    static Set<String> jobNames() {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) {
            if (j.hunt != null && j.hunt.names != null) Collections.addAll(s, j.hunt.names);
            else if (j.task != null && j.task.kind == AfkTasks.Kind.FIGHT && j.task.targets != null) Collections.addAll(s, j.task.targets);
        }
        return s;
    }
    /** kill n of the task's own npc ids through the engine's death path; returns the amount drop per kill. */
    static String kill(Player p, int n) {
        StringBuilder sb = new StringBuilder();
        SlayerManager m = SlayerManager.getInstance(p);
        if (m.getTask() == null) return "no task";
        int[] ids = m.getTask().getNpcs();
        for (int i = 0; i < n; i++) {
            int before = m.getAmount(); double xp0 = p.getSkills().getExperience(Skills.SLAYER);
            NPC npc = NPC.create(ids[i % ids.length], p.getLocation().transform(1, 0, 0));
            npc.init();
            npc.finalizeDeath(p);
            m = SlayerManager.getInstance(p);
            sb.append(" [kill ").append(i + 1).append(" npc ").append(npc.getId()).append(" ").append(npc.getName())
              .append(": amount ").append(before).append("->").append(m.getAmount())
              .append(" xp +").append((int) (p.getSkills().getExperience(Skills.SLAYER) - xp0)).append("]");
        }
        return sb.toString();
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p54")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, b;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        if (HuntMap.HUNTS.isEmpty()) { try { HuntMap.build(); List<SmartAdventurers.Job> all = new ArrayList<>(SmartAdventurers.JOBS); for (HuntMap.Hunt h : HuntMap.HUNTS) { SmartAdventurers.Job j = new SmartAdventurers.Job(AfkTasks.Task.AUTO, -1, 1); j.hunt = h; all.add(j); } SmartAdventurers.JOBS = all; } catch (Throwable ex) { log("huntmap build " + ex); } }
                        Set<String> jn = jobNames();
                        log("PART0 jobs " + SmartAdventurers.JOBS.size() + ", hunts " + HuntMap.HUNTS.size() + ", fight names " + jn.size());
                        for (Master.Task mt : Master.TURAEL.tasks) {
                            Tasks tk = mt.task; String nm = names(tk); boolean match = false;
                            for (String x : nm.split("/")) if (jn.contains(x)) { match = true; break; }
                            log("PART0 TURAEL " + tk.name() + " levelReq " + tk.levelReq + " combatCheck " + tk.combatCheck + " weight " + mt.weight
                                + " quest " + tk.questReq + " ids " + Arrays.toString(tk.getNpcs()) + " names " + nm + " JOBMATCH " + match);
                        }
                    }
                    if (t == 160) {
                        a = TestCR1.bot(AT, 40, 30, 30, 30);
                        log("PART1 bot A " + a.getUsername() + " before login: " + state(a));
                    }
                    if (t == 170) {
                        log("PART1 guarded login -> " + guardedLogin(a) + "; " + state(a));
                        SlayerManager.getInstance(a).generate(Master.TURAEL);
                        log("PART1 after generate(TURAEL): " + state(a) + " varp2502 " + core.api.ContentAPIKt.getVarp(a, 2502));
                    }
                    if (t == 175) { log("PART1 kills:" + kill(a, 3)); log("PART1 after: " + state(a)); }
                    if (t == 180) {
                        log("PART2a guarded login again -> " + guardedLogin(a) + "; " + state(a));
                        log("PART2a kill:" + kill(a, 1));
                    }
                    if (t == 185) {
                        new SlayerManager().login(a);
                        log("PART2b unguarded 2nd login on the same player: " + state(a));
                        if (!SlayerManager.getInstance(a).hasTask()) SlayerManager.getInstance(a).generate(Master.TURAEL);
                        log("PART2b after (re)generate: " + state(a));
                        log("PART2b kill:" + kill(a, 1));
                    }
                    if (t == 190) {
                        a.clear();
                        b = TestCR1.bot(AT, 40, 30, 30, 30);
                        log("PART2c A cleared (logout); new AIPlayer B " + b.getUsername() + ": " + state(b));
                        log("PART2c guarded login -> " + guardedLogin(b) + "; " + state(b));
                        SlayerManager.getInstance(b).generate(Master.TURAEL);
                        log("PART2c after generate: " + state(b));
                    }
                    if (t == 195) { log("PART2c kills:" + kill(b, 2)); log("PART2c after: " + state(b)); }
                    if (t == 200) {
                        SlayerManager m = SlayerManager.getInstance(b); int left = m.getAmount();
                        log("PART3 completion: killing the remaining " + left);
                        String r = kill(b, left); log("PART3 last kills tail:" + (r.length() > 300 ? r.substring(r.length() - 300) : r));
                        log("PART3 after: " + state(b) + " completed " + m.isCompleted());
                        m.clear(); log("PART3 clear(): " + state(b));
                    }
                    if (t == 205) { log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
