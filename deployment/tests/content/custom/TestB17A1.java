package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.combat.ImpactHandler;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b17a1=true; needs TestCR1.java in the build): batch 17 item1, a unique account uid per bot. Lists the
 * uids of the smart bots that logged in, then two new bots (made like TestCR1's and put through SmartAdventurers.enlist, the
 * brain attach; their thinking held off) at Barbarian Village each die by a hit from themselves (no NPC: the engine's own
 * gravestone), 30 ticks apart. Before: uid 0 for all, the second stone collapses the first (its items gone). After: unique
 * non-zero uids, both stones there with their items.
 */
public final class TestB17A1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB17A1] " + s); }
    static final Location A = Location.create(3076, 3424, 0), B = Location.create(3079, 3428, 0);
    static String grave(AIPlayer p) {
        try {
            java.lang.reflect.Field f = core.game.node.entity.combat.graves.GraveController.class.getDeclaredField("activeGraves"); f.setAccessible(true);
            for (Object g : ((java.util.Map<?, ?>) f.get(null)).values()) {
                core.game.node.entity.combat.graves.Grave gr = (core.game.node.entity.combat.graves.Grave) g;
                if (!p.getUsername().equalsIgnoreCase(gr.getOwnerUsername())) continue;
                int live = 0; for (core.game.node.item.GroundItem gi : gr.getItems()) if (gi != null && gi.isActive()) live++;
                return "stone (owner uid " + gr.getOwnerUid() + ", active " + gr.isActive() + ") with " + live + " of " + gr.getItems().length + " item stacks still there";
            }
        } catch (Throwable t) { return "lookup " + t; }
        return "NO STONE";
    }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17a1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, b;
            AIPlayer make(Location at) {
                return TestCR1.bot(at, 30, 20, 20, 20);
            }
            void enlist(AIPlayer p) {
                SmartAdventurers.enlist(p, false, false, -1, 0, 0, "test bot (item1)");
                SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(p.getUsername().toLowerCase(java.util.Locale.ROOT));
                if (br != null) br.nextThink = Long.MAX_VALUE; // no trips: only the heartbeat's death bookkeeping
                for (int id : new int[]{1205, 1277, 1171, 1139, 2351, 2349, 333, 333}) p.getInventory().add(new Item(id)); // after enlist (it starts the bot with an empty pack)
            }
            void uids(String when) {
                StringBuilder s = new StringBuilder(); java.util.Set<Integer> seen = new java.util.HashSet<>(); int zero = 0, dup = 0, n = 0;
                for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) { if (br.bot == null || !br.bot.isActive()) continue; n++; int u = br.bot.getDetails().getUid(); if (u == 0) zero++; if (!seen.add(u)) dup++; if (s.length() < 400) s.append(br.bot.getUsername()).append('=').append(Integer.toHexString(u)).append(' '); }
                log("RESULT online smart bots " + when + ": " + n + ", uid 0: " + zero + ", duplicate uids: " + dup + " (" + s.toString().trim() + ")");
            }
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 200) { uids("at login"); a = make(A); b = make(B); }
                    if (t == 204) { enlist(a); enlist(b); log("RESULT new bots after enlist: " + a.getUsername() + " uid " + Integer.toHexString(a.getDetails().getUid()) + ", " + b.getUsername() + " uid " + Integer.toHexString(b.getDetails().getUid())); }
                    if (t == 215) { log("killing " + a.getUsername() + " at " + a.getLocation() + " carrying " + TestCR1.pack(a)); a.getImpactHandler().manualHit(a, a.getSkills().getLifepoints() + 5, ImpactHandler.HitsplatType.NORMAL); }
                    if (t == 235) log("first stone: " + grave(a));
                    if (t == 245) { log("killing " + b.getUsername() + " at " + b.getLocation() + " carrying " + TestCR1.pack(b)); b.getImpactHandler().manualHit(b, b.getSkills().getLifepoints() + 5, ImpactHandler.HitsplatType.NORMAL); }
                    if (t == 270) {
                        log("RESULT after both deaths (" + (270 - 215) * 0.6 + " s after the first death, 18 s after the second): " + a.getUsername() + ": " + grave(a) + "; " + b.getUsername() + ": " + grave(b));
                    }
                    if (t == 450) { uids("after the 100 s shifts (some bots logged out and back in)"); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
