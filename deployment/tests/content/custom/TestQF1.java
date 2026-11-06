package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (quest fix 10-23). -Dtest.qf1=x,y,z>x,y,z[;x,y,z>x,y,z...]: one fresh bot (no coins, so no
 * teleport fare, as the live stuck bots) per walk, put on the start tile, then a QuestSteps.Walk (radius 2) to the end, the
 * quest walker exactly as a quest step drives it. Prints where it is every 25 ticks and the result.
 */
public final class TestQF1 implements StartupListener {
    @Override public void startup() {
        if (System.getProperty("test.qf1door") != null) { door(); return; }
        final String spec = System.getProperty("test.qf1");
        if (spec == null) return;
        GameWorld.getPulser().submit(new Pulse(40) {
            @Override public boolean pulse() {
                int i = 0;
                for (String leg : spec.split(";")) {
                    String[] ab = leg.split(">");
                    try { walk(i++, loc(ab[0]), loc(ab[1])); } catch (Throwable t) { System.out.println("[TestQF1] failed: " + t); t.printStackTrace(System.out); }
                }
                return true;
            }
        });
    }

    /** -Dtest.qf1door=x,y: a bot at 3244,3382 clicks Open on the door at x,y,0 (or walks to 3242,3382 when x is 0). */
    static void door() {
        GameWorld.getPulser().submit(new Pulse(40) {
            @Override public boolean pulse() {
                final String[] xy = System.getProperty("test.qf1door").split(",");
                final AIPlayer bot = new AIPlayer(Location.create(3244, 3382, 0));
                bot.getProperties().setTeleportLocation(Location.create(3244, 3382, 0));
                GameWorld.getPulser().submit(new Pulse(1) {
                    int t;
                    @Override public boolean pulse() {
                        t++;
                        if (t == 4) {
                            if (xy[0].equals("0")) { bot.walkToPosSmart(Location.create(3242, 3382, 0)); System.out.println("[TestQF1] door: walkToPosSmart 3242,3382 from " + bot.getLocation()); }
                            else {
                                core.game.node.scenery.Scenery sc = core.game.world.map.RegionManager.getObject(0, Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
                                System.out.println("[TestQF1] door: click " + sc + " -> " + QuestSteps.click(bot, sc, "Open", core.game.interaction.IntType.SCENERY));
                            }
                        }
                        if (t >= 4 && t % 2 == 0) {
                            core.game.node.scenery.Scenery sc = core.game.world.map.RegionManager.getObject(0, 3241, 3382);
                            System.out.println("[TestQF1] door: tick " + t + " at " + bot.getLocation() + " moving " + bot.getWalkingQueue().isMoving() + " pulse " + bot.getPulseManager().hasPulseRunning() + " door tile " + (sc == null ? "none" : sc.getId()));
                        }
                        return t >= 30;
                    }
                });
                return true;
            }
        });
    }

    static Location loc(String s) { String[] p = s.trim().split(","); return Location.create(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])); }

    static void walk(int n, Location from, Location to) {
        final AIPlayer bot = new AIPlayer(from);
        try { bot.getInventory().clear(); bot.getBank().clear(); } catch (Throwable ignored) { }
        SmartAdventurers.finishTutorial(bot);
        bot.getProperties().setTeleportLocation(from);
        final QuestSteps.Walk w = new QuestSteps.Walk("probe walk " + n, to, 2);
        final QuestRunner fr = new QuestRunner(bot, QuestBook.SCRIPTS.get("shieldofarrav"), 0, true); // never submitted: Walk.tick only uses r.bot and r.log
        System.out.println("[TestQF1] walk " + n + " " + bot.getUsername() + ": " + from + " -> " + to + ", coins " + Ledger.Wallet.coins(bot));
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; boolean begun;
            @Override public boolean pulse() {
                if (++t < 4) return false; // the teleport onto the start tile lands
                if (!begun) { w.begin(fr); begun = true; }
                QuestSteps.St s;
                try { s = w.tick(fr); } catch (Throwable e) { System.out.println("[TestQF1] walk " + n + " threw " + e); e.printStackTrace(System.out); return true; }
                if (t == 30 || t == 31) { // what the door rule sees round the bot
                    Location h = bot.getLocation();
                    for (int dx = -6; dx <= 6; dx++) for (int dy = -6; dy <= 6; dy++) {
                        core.game.node.scenery.Scenery sc = core.game.world.map.RegionManager.getObject(h.getZ(), h.getX() + dx, h.getY() + dy);
                        if (sc != null && sc.getName() != null && sc.getName().toLowerCase().contains("door")) System.out.println("[TestQF1] walk " + n + " tick " + t + " door " + sc.getId() + " " + sc.getName() + "@" + sc.getLocation() + " open-option " + QuestSteps.hasOption(sc, "Open") + " opts " + java.util.Arrays.toString(sc.getInteraction().getOptions()));
                    }
                }
                if (t <= 40 || t % 25 == 0) System.out.println("[TestQF1] walk " + n + " tick " + t + " at " + bot.getLocation() + " last " + w.m.last + " stall " + w.m.stall + " retries " + w.m.retries);
                if (s == QuestSteps.St.RUNNING && t < 900) return false;
                System.out.println("[TestQF1] RESULT walk " + n + ": " + s + " in " + t + " ticks at " + bot.getLocation() + (w.failure != null ? ", " + w.failure : "") + " (last " + w.m.last + ")");
                try { bot.clear(); } catch (Throwable ignored) { }
                return true;
            }
        });
    }

}
