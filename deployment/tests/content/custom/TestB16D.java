package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b16d=true; best with -Dsmartbots.count=0): batch 16 item4, the extras' cooldown. Six bots on a
 * break at six hangouts far apart, each with 5 logs and a tinderbox banked (so the fire extra is always possible), each
 * with its own stand-in human 3 tiles away. think() every 7 ticks throughout.
 * Pass 1: the human stands by its bot for 3 minutes (one break). Then the humans go away for 2 minutes. Pass 2: a new
 * break, the humans back for 3 minutes. Each extra's start is recorded (extraKind 0 -> non-zero): per bot, extras in
 * pass 1 (expect at most one), in pass 2 (expect none: 5-10 min cooldown), the seconds from the human's arrival to the
 * first extra, and the gaps between extras.
 * -Dtest.b16d.force=true: the once-a-break 1-in-3 roll is forced on (reflection), so every bot is offered an extra and the
 * one-a-break limit and the 5-10 min cooldown are what stop them; each pass-2 extra is checked against the bot's cooldown.
 */
public final class TestB16D implements StartupListener {
    static void log(String s) { System.out.println("[TestB16D] " + s); }
    static void set(Object o, String n, Object v) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); f.set(o, v); } catch (Throwable t) { } }
    static Object get(Object o, String n) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (Throwable t) { return "n/a"; } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b16d")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; final int N = 6; AIPlayer[] bot = new AIPlayer[N], human = new AIPlayer[N]; SmartAdventurers.Brain[] br = new SmartAdventurers.Brain[N];
            Location[] spot = {Location.create(3222, 3219, 0), Location.create(3212, 3429, 0), Location.create(2995, 3378, 0), Location.create(3082, 3249, 0), Location.create(3085, 3500, 0), Location.create(2662, 3306, 0)};
            Location away = Location.create(3300, 3500, 0);
            java.util.List<java.util.List<Long>> starts = new java.util.ArrayList<>(); int[] p1 = new int[N], p2 = new int[N]; boolean[] was = new boolean[N]; long pass1At, pass2At; int pass, early; final boolean FORCE = Boolean.getBoolean("test.b16d.force");
            @Override public boolean pulse() {
                t++;
                try {
                    long now = System.currentTimeMillis();
                    if (t == 150) {
                        for (int i = 0; i < N; i++) {
                            bot[i] = TestCR1.bot(spot[i], 40, 20, 20, 20); br[i] = TestCR1.rig(bot[i], false);
                            bot[i].getInventory().clear(); for (Item it : bot[i].getBank().toArray()) if (it != null) bot[i].getBank().remove(it);
                            bot[i].getBank().add(new Item(1511, 5)); bot[i].getBank().add(new Item(590, 1));
                            human[i] = TestA1.human(away.transform(i * 3, 0, 0), 900600 + i);
                            starts.add(new java.util.ArrayList<>());
                        }
                    }
                    if (t == 156) { pass = 1; pass1At = now; for (int i = 0; i < N; i++) { br[i].idleUntil = now + 400_000; br[i].loiterAt = spot[i]; br[i].loiterName = "a test spot"; human[i].getProperties().setTeleportLocation(spot[i].transform(3, 0, 0)); } log("pass 1: a human by each bot for 3 minutes"); }
                    if (t > 160 && t % 7 == 0) for (int i = 0; i < N; i++) {
                        if (FORCE && pass != 0) { set(br[i], "extraRollBreak", br[i].idleUntil); set(br[i], "extraRollOk", true); }
                        br[i].nextThink = 0; br[i].think();
                        boolean on = !Integer.valueOf(0).equals(get(br[i], "extraKind"));
                        if (on && !was[i]) { starts.get(i).add(now); if (pass == 1) p1[i]++; else if (pass == 2) p2[i]++; long gap = starts.get(i).size() > 1 ? now - starts.get(i).get(starts.get(i).size() - 2) : -1; Object cool = get(br[i], "extraCool");
                            if (pass == 2 && gap >= 0 && cool instanceof Long && gap < (Long) cool) early++;
                            log(bot[i].getUsername() + " extra " + get(br[i], "extraKind") + " in pass " + pass + " at +" + (now - (pass == 2 ? pass2At : pass1At)) / 1000 + " s" + (gap >= 0 ? ", " + gap / 1000 + " s after its last" : "") + ", cooldown " + (cool instanceof Long ? (Long) cool / 1000 + " s" : cool)); }
                        was[i] = on;
                    }
                    if (t == 456) { pass = 0; for (int i = 0; i < N; i++) human[i].getProperties().setTeleportLocation(away.transform(i * 3, 0, 0)); log("humans away for 2 minutes"); }
                    if (t == 656) { pass = 2; pass2At = now; for (int i = 0; i < N; i++) { br[i].idleUntil = now + 400_000; human[i].getProperties().setTeleportLocation(spot[i].transform(3, 0, 0)); } log("pass 2: a new break, the humans back for 3 minutes"); }
                    if (t == 956) {
                        int tot1 = 0, tot2 = 0, over = 0;
                        for (int i = 0; i < N; i++) {
                            java.util.List<Long> s = starts.get(i); StringBuilder g = new StringBuilder();
                            for (int k = 1; k < s.size(); k++) g.append(g.length() == 0 ? "" : ", ").append((s.get(k) - s.get(k - 1)) / 1000).append(" s");
                            String first = s.isEmpty() ? "-" : ((s.get(0) - pass1At) / 1000) + " s";
                            log("RESULT " + bot[i].getUsername() + ": pass 1 extras " + p1[i] + ", pass 2 extras " + p2[i] + ", first at +" + first + ", gaps " + (g.length() == 0 ? "-" : g) + ", cooldown " + (get(br[i], "extraCool") instanceof Long ? (Long) get(br[i], "extraCool") / 1000 + " s" : get(br[i], "extraCool")));
                            tot1 += p1[i]; tot2 += p2[i]; if (p1[i] > 1) over++;
                        }
                        log("RESULT total" + (FORCE ? " (roll forced on)" : "") + ": pass 1 " + tot1 + " extras over " + N + " bots (" + over + " bots with more than one), pass 2 " + tot2 + " (" + early + " sooner than that bot's cooldown after its last)");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
