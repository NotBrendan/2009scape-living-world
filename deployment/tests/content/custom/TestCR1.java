package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.combat.ImpactHandler;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/**
 * Trial only (-Dtest.cr1=true): combat review 10-23 #1. A bot on a cow trip (its runner pulsed every 3 ticks, as smart
 * bots are) takes a scripted 2 damage every 2 ticks from a cow while it fights. Logs every bite: before, the eat check
 * sat behind the action wait (6-9 ticks blind after an attack or a bite); after, a bite every 3 ticks while low.
 * Also holds the shared helpers the other TestCR tests use.
 */
public final class TestCR1 implements StartupListener {
    static void log(String s) { System.out.println("[TestCR1] " + s); }
    static final Location LUMBRIDGE = Location.create(3222, 3218, 0);

    /** A bot created at the Lumbridge respawn (AIPlayer respawns where it was made, like the smart bots), then moved. */
    static AIPlayer bot(Location to, int hp, int att, int str, int def) {
        AIPlayer b = new AIPlayer(LUMBRIDGE);
        SmartAdventurers.finishTutorial(b);
        Skills s = b.getSkills();
        int[][] lv = {{Skills.HITPOINTS, hp}, {Skills.ATTACK, att}, {Skills.STRENGTH, str}, {Skills.DEFENCE, def}};
        for (int[] l : lv) { s.setStaticLevel(l[0], l[1]); s.setLevel(l[0], l[1]); }
        s.setLifepoints(hp);
        try { b.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
        if (to != null) b.getProperties().setTeleportLocation(to);
        return b;
    }
    static int count(Player p, int id) { return p.getInventory().getAmount(id); }

    /** A smart-adventurer brain for a test bot, with only its death and grave code ticking (no trip planning). */
    static SmartAdventurers.Brain rig(AIPlayer b, boolean high) {
        final SmartAdventurers.Brain br = new SmartAdventurers.Brain();
        br.bot = b; br.scriptAPI = new core.game.bots.ScriptAPI(b); br.nextThink = Long.MAX_VALUE;
        String key = b.getUsername().toLowerCase(java.util.Locale.ROOT);
        SmartAdventurers.BRAINS.put(key, br); SmartAdventurers.HIGH_TIER.put(key, high);
        GameWorld.getPulser().submit(new Pulse(1) {
            int k;
            @Override public boolean pulse() {
                if (!b.isActive()) return true;
                k++;
                try {
                    if (k % 3 == 0) br.sampleJump();          // the brain heartbeat's rate
                    if (k % 7 == 0) { br.checkDeath(); if (br.graveAt != null) br.graveTick(System.currentTimeMillis()); }
                } catch (Throwable t) { System.out.println("[TestCR] rig " + t); }
                return false;
            }
        });
        return br;
    }
    static SmartAdventurers.Job job(AfkTasks.Task task) {
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt == null && j.task == task) return j;
        return null;
    }
    /** Starts a runner the way Brain.start does (3-tick pulse, in RUNNING) and makes it the brain's trip. */
    static AfkTasks.Runner trip(SmartAdventurers.Brain br, AfkTasks.Task task) {
        AfkTasks.Runner r = new AfkTasks.Runner(br.bot, task, true, 0, false, false);
        r.setDelay(3); AfkTasks.RUNNING.put(br.bot.getUsername().toLowerCase(java.util.Locale.ROOT), r); GameWorld.getPulser().submit(r);
        br.runner = r; br.lastJob = job(task);
        return r;
    }
    static NPC npcAt(Player p) {
        NPC n = npcNear(p, null);
        if (n != null) return n;
        n = NPC.create(100, p.getLocation().transform(2, 0, 0)); n.init(); // a goblin
        return n;
    }
    static void killBy(Player p, core.game.node.entity.Entity src) { p.getImpactHandler().manualHit(src, p.getSkills().getLifepoints() + 5, ImpactHandler.HitsplatType.NORMAL); }
    static String grave(Player p) {
        try {
            java.lang.reflect.Field f = core.game.node.entity.combat.graves.GraveController.class.getDeclaredField("activeGraves"); f.setAccessible(true);
            for (Object g : ((java.util.Map<?, ?>) f.get(null)).values()) { core.game.node.entity.combat.graves.Grave gr = (core.game.node.entity.combat.graves.Grave) g; if (p.getUsername().equalsIgnoreCase(gr.getOwnerUsername())) return gr.getItems().length + " stacks at " + gr.getLocation() + ", " + gr.getTicksRemaining() + " ticks left"; }
        } catch (Throwable t) { return "lookup " + t; }
        return "none";
    }
    static String pack(Player p) {
        StringBuilder s = new StringBuilder();
        for (Item it : p.getInventory().toArray()) if (it != null) s.append(it.getName()).append(it.getAmount() > 1 ? " x" + it.getAmount() : "").append(", ");
        for (Item it : p.getEquipment().toArray()) if (it != null) s.append("(worn) ").append(it.getName()).append(", ");
        return s.length() == 0 ? "nothing" : s.substring(0, s.length() - 2);
    }
    static NPC npcNear(Player p, String name) {
        NPC best = null; double d = 99;
        for (NPC n : RegionManager.getLocalNpcs(p, 15)) if (n != null && n.isActive() && (name == null || name.equalsIgnoreCase(n.getName()))) { double x = n.getLocation().getDistance(p.getLocation()); if (x < d) { d = x; best = n; } }
        return best;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.cr1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            final int T0 = 150; int t; AIPlayer b; AfkTasks.Runner r; int lastFood, lastBiteTick = -1, bites, lowNoBite, lowest = 999, lowTicks; StringBuilder gaps = new StringBuilder(); int minGap = 999, sumGap, nGap;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == T0) { b = bot(Location.create(3259, 3268, 0), 60, 1, 1, 1); b.getInventory().add(new Item(333, 22)); log("bot " + b.getUsername() + " made, 22 trout"); }
                    if (t == T0 + 4) {
                        r = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false);
                        b.getSkills().setLifepoints(40); r.lastHp = 40; // a set, not a hit
                        lastFood = count(b, 333);
                        log("runner on cows at " + b.getLocation() + ", hp 40/60 (eats at 60% = 36 or below)");
                    }
                    if (r == null || t <= T0 + 4) return false;
                    int k = t - (T0 + 4);
                    if (k % 3 == 0) r.pulse(); // the smart bots' runner rate (setDelay(3))
                    if (k % 2 == 0) { NPC src = npcNear(b, "Cow"); if (src == null) src = npcNear(b, null); b.getImpactHandler().manualHit(src != null ? src : b, 2, ImpactHandler.HitsplatType.NORMAL); }
                    int hp = b.getSkills().getLifepoints(), food = count(b, 333);
                    lowest = Math.min(lowest, hp);
                    if (food < lastFood) {
                        bites++;
                        if (lastBiteTick >= 0) { int g = t - lastBiteTick; minGap = Math.min(minGap, g); sumGap += g; nGap++; if (gaps.length() < 200) gaps.append(g).append(' '); }
                        lastBiteTick = t;
                    }
                    lastFood = food;
                    if (hp <= 36) { lowTicks++; if (lastBiteTick < 0 || t - lastBiteTick > 3) lowNoBite++; }
                    if (k == 120) {
                        log("RESULT 120 ticks under 1 damage/tick: bites " + bites + ", gaps between bites (ticks) " + gaps.toString().trim());
                        log("RESULT min gap " + (minGap == 999 ? "-" : String.valueOf(minGap)) + ", average gap " + (nGap > 0 ? String.format("%.1f", sumGap / (double) nGap) : "-") + "; ticks at or under 36 hp " + lowTicks + ", of those with no bite in the last 3 ticks " + lowNoBite + "; lowest hp " + lowest + ", hp now " + hp + ", attacks made " + r.kills + ", state " + r.state);
                        r.halt();
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
