package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/**
 * Trial only (-Dtest.b14h2=true): rec-review 10-23 batch14 item2, loiter extras only with a real player near.
 * Phase 1 (no human anywhere near): three bots on a break (idleUntil set, loiterAt = their spot), think() driven every
 * 4 s for 70 s; their bank keeps 5 logs and a tinderbox. Extras must be 0, no Firemaking XP, logs untouched. Also the
 * no-human call itself: RNG.setSeed(7) -> the next long with and without 200 loiterExtra calls in between must match.
 * Phase 2: the same bots, each with a fake human (artificial=false) 3 tiles away, for 100 s: extras happen (log lines
 * "break extra"), and the fire bot gains Firemaking XP / burns a log.
 * Spots: the first hangout (or the GE) with a church altar within 16 tiles, one with a bench within 10, and a plain one.
 */
public final class TestB14H2 implements StartupListener {
    static void log(String s) { System.out.println("[TestB14H2] " + s); }

    static int[] scan(Location here) { // {altars within 24, benches within 10}
        int al = 0, be = 0;
        for (int dx = -24; dx <= 24; dx++) for (int dy = -24; dy <= 24; dy++) {
            core.game.node.scenery.Scenery o;
            try { o = RegionManager.getObject(here.getZ(), here.getX() + dx, here.getY() + dy); } catch (Throwable t) { continue; }
            if (o == null || o.getDefinition() == null || o.getDefinition().getName() == null) continue;
            String n = o.getDefinition().getName().toLowerCase();
            if (n.equals("altar") && SmartAdventurers.Brain.prayable(o)) al++;
            else if (n.contains("bench") && o.getLocation().getDistance(here) <= 10) be++;
        }
        return new int[]{al, be};
    }
    static Object get(Object o, String n) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (Throwable t) { return null; } }
    static Object call(Object o, String n, long now) { try { java.lang.reflect.Method m = SmartAdventurers.Brain.class.getDeclaredMethod(n, long.class); m.setAccessible(true); return m.invoke(o, now); } catch (NoSuchMethodException e) { return "no method"; } catch (Throwable t) { return "error " + t; } }
    static int extras() { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField("loiterExtras"); f.setAccessible(true); return f.getInt(null); } catch (Throwable t) { return -1; } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b14h2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; Location[] spot = new Location[3]; String[] name = new String[3]; AIPlayer[] bot = new AIPlayer[3]; SmartAdventurers.Brain[] br = new SmartAdventurers.Brain[3]; AIPlayer[] human = new AIPlayer[3];
            double[] xp0 = new double[3]; int[] logs0 = new int[3]; int ex0; int P2;
            void setBreak(int i, long now, long len) { br[i].idleUntil = now + len; br[i].loiterAt = spot[i]; br[i].loiterName = name[i]; br[i].nextThink = 0; }
            String state(int i) { return bot[i].getUsername() + " at " + bot[i].getLocation() + " (spot " + name[i] + ", " + (int) bot[i].getLocation().getDistance(spot[i]) + " away), FM xp +" + (int) (bot[i].getSkills().getExperience(Skills.FIREMAKING) - xp0[i]) + ", logs banked " + bot[i].getBank().getAmount(1511) + "/" + logs0[i] + ", pack logs " + bot[i].getInventory().getAmount(1511) + ", tinderbox bank/pack " + bot[i].getBank().getAmount(590) + "/" + bot[i].getInventory().getAmount(590) + ", extraKind " + get(br[i], "extraKind"); }
            @Override public boolean pulse() {
                t++;
                try {
                    long now = System.currentTimeMillis();
                    if (t == 150) {
                        java.util.List<Object[]> spots = new java.util.ArrayList<>();
                        spots.add(new Object[]{"the Grand Exchange", 3165, 3487});
                        for (Object[] h : SmartAdventurers.Brain.HANGOUTS) spots.add(h);
                        spots.add(new Object[]{"near the Lumbridge church (test only, not a hangout)", 3238, 3212}); // proves the altar detector and the prayer
                        for (Object[] h : spots) {
                            Location l = Location.create((Integer) h[1], (Integer) h[2], 0); int[] sc = scan(l);
                            log("spot " + h[0] + " " + l + ": altars " + sc[0] + ", benches " + sc[1]);
                            if (sc[0] > 0 && spot[0] == null) { spot[0] = l; name[0] = (String) h[0]; }
                            else if (sc[1] > 0 && sc[0] == 0 && spot[1] == null) { spot[1] = l; name[1] = (String) h[0]; }
                            else if (sc[0] == 0 && sc[1] == 0 && spot[2] == null && !((String) h[0]).contains("Exchange") && !((String) h[0]).contains("bank")) { spot[2] = l; name[2] = (String) h[0]; }
                        }
                        for (int i = 0; i < 3; i++) {
                            if (spot[i] == null) { spot[i] = Location.create(3222, 3219, 0); name[i] = "the Lumbridge castle courtyard (fallback)"; }
                            bot[i] = TestCR1.bot(spot[i], 40, 20, 20, 20);
                            br[i] = TestCR1.rig(bot[i], false);
                            bot[i].getInventory().clear(); for (Item it : bot[i].getBank().toArray()) if (it != null) bot[i].getBank().remove(it);
                            bot[i].getBank().add(new Item(1511, 5)); bot[i].getBank().add(new Item(590, 1));
                            xp0[i] = bot[i].getSkills().getExperience(Skills.FIREMAKING); logs0[i] = 5;
                            log("bot " + i + " " + bot[i].getUsername() + " -> " + name[i] + " " + spot[i]);
                        }
                    }
                    if (t == 160) {
                        for (int i = 0; i < 3; i++) setBreak(i, now, 150_000);
                        log("phase 1: no human near; watcher " + Spectacle.watcher(bot[0]) + "/" + Spectacle.watcher(bot[1]) + "/" + Spectacle.watcher(bot[2]));
                        ex0 = extras();
                        // the no-human call is a no-op: same RNG draw with and without it, no state set
                        SmartAdventurers.RNG.setSeed(7); long r1 = SmartAdventurers.RNG.nextLong();
                        SmartAdventurers.RNG.setSeed(7); Object ret = null; for (int k = 0; k < 200; k++) ret = call(br[0], "loiterExtra", now);
                        long r2 = SmartAdventurers.RNG.nextLong();
                        log("RESULT 1a no-human loiterExtra x200: returned " + ret + ", RNG draw unchanged " + (r1 == r2) + ", extraKind " + get(br[0], "extraKind") + ", nextExtra " + get(br[0], "nextExtra") + ", extrasThisBreak " + get(br[0], "extrasThisBreak"));
                    }
                    if (t > 160 && t < 280 && t % 7 == 0) for (int i = 0; i < 3; i++) { br[i].nextThink = 0; br[i].think(); }
                    if (t == 280) {
                        log("RESULT 1b phase 1 (no human, 70 s of break): extras started " + (extras() < 0 ? "n/a (before)" : String.valueOf(extras() - ex0)));
                        for (int i = 0; i < 3; i++) log("RESULT 1b " + state(i));
                        for (int i = 0; i < 3; i++) {
                            bot[i].getProperties().setTeleportLocation(spot[i]);
                            human[i] = TestA1.human(spot[i].transform(3, 0, 0), 900200 + i);
                        }
                        ex0 = extras();
                    }
                    if (t == 284) { for (int i = 0; i < 3; i++) setBreak(i, now, 150_000); log("phase 2: a human 3 tiles from each; watcher " + Spectacle.watcher(bot[0]) + "/" + Spectacle.watcher(bot[1]) + "/" + Spectacle.watcher(bot[2])); }
                    if (t > 284 && t < 520 && t % 7 == 0) for (int i = 0; i < 3; i++) { br[i].nextThink = 0; br[i].think(); }
                    if (t == 520) {
                        log("RESULT 2 phase 2 (human near, 140 s of break): extras started " + (extras() < 0 ? "n/a (before)" : String.valueOf(extras() - ex0)));
                        for (int i = 0; i < 3; i++) log("RESULT 2 " + state(i));
                        log("DONE");
                        return true;
                    }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
