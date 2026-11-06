package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.Map;

/**
 * Trial only (-Dtest.b18g=true, with -Dnav=true -Dscenario.echo=true -Dwatchdog.test=true -Dwatchdog.min=2 and the usual smartbots
 * flags, 8 bots): batch 18 builder G, items 1-4. Compiles against batch 17 too (new members are read by reflection: "n/a" there).
 * Once the planner graph is up, in ONE pulse (nothing else interleaves) on a rigged brain bot at Lumbridge (TestCR1.rig: in
 * BRAINS, so Watchdog.ours(), no trip planning):
 *   NR: NavWalker.step to an unreachable in-grid tile -> expect one "[Nav] no route" line and one no_route record; then the same
 *       again after clearing the 60 s retry gate -> no new line or record (the 10-minute limit).
 *   RD: a route to Draynor bank, then step toward Varrock -> one "[Nav] redirect" line + one redirect record; the same pair again
 *       from a fresh state -> none (limit).
 *   NV: a shop walk to Varrock with its deadline passed, shopTick() -> one never_reached record.
 *   ID: Watchdog.idle() on it -> one idle_giveup record and the extended idle line.
 * RS: -Dwatchdog.test puts a real adventurer in the unicorn pen; with -Dwatchdog.min=2 it is rescued: one rescue record.
 * At 1500 ticks (15 min): Watchdog.Daily.partial() writes data/reports/stuck-<today>.md (printed), the counters, DONE.
 */
public final class TestB18G implements StartupListener {
    static void log(String s) { System.out.println("[TestB18G] " + s); }

    static Object stat(Class<?> c, String n) { try { java.lang.reflect.Field f = c.getDeclaredField(n); f.setAccessible(true); return f.get(null); } catch (Throwable t) { return null; } }
    @SuppressWarnings("unchecked")
    static String scen(String src) { Object m = stat(Watchdog.class, "SCEN"); if (m == null) return "n/a"; Object v = ((Map<String, Object>) m).get(src); return v == null ? "0" : v.toString(); }
    static String said(String n) { Object v = stat(NavWalker.class, n); return v == null ? "n/a" : v.toString(); }

    static final Location LUM = Location.create(3222, 3218, 0), DRAYNOR = Location.create(3093, 3244, 0), VARROCK = Location.create(3212, 3428, 0);
    static final int[][] SEA = {{3050, 3150}, {2990, 3170}, {3000, 3120}, {3100, 3120}, {2960, 3240}, {3140, 3180}, {3260, 3150}, {3180, 3140}, {2980, 3300}, {3330, 3200}};

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b18g")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer bot; SmartAdventurers.Brain br; boolean forced, rescueSaid;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (bot == null && t >= 60) { bot = TestCR1.bot(LUM, 40, 20, 20, 20); br = TestCR1.rig(bot, false); log("rig bot " + bot.getUsername() + " at Lumbridge"); return false; }
                    if (bot != null && !forced && t >= 120 && NavPlanner.built && NavWalker.ON) { forced = true; force(); }
                    if (!rescueSaid && Watchdog.rescued > 0) { rescueSaid = true; log("RESULT RS rescue: Watchdog.rescued " + Watchdog.rescued + ", rescue records " + scen("rescue") + " at tick " + t); }
                    if (t == 900 && !forced) log("RESULT planner never came up (built " + NavPlanner.built + ", ON " + NavWalker.ON + ")");
                    if (t >= 1500) {
                        if (!rescueSaid) log("RESULT RS rescue: none in 15 min (Watchdog.rescued " + Watchdog.rescued + ", records " + scen("rescue") + ")");
                        log("RESULT counters: records rescue " + scen("rescue") + ", idle_giveup " + scen("idle_giveup") + ", never_reached " + scen("never_reached") + ", failed_trip " + scen("failed_trip") + ", quest_fail " + scen("quest_fail") + ", no_route " + scen("no_route") + ", redirect " + scen("redirect") + "; nav lines no-route " + said("saidNoRoute") + ", redirect " + said("saidRedirect") + "; " + NavWalker.stats());
                        try {
                            Class<?> d = Class.forName("content.custom.Watchdog$Daily");
                            java.lang.reflect.Method m = d.getDeclaredMethod("partial"); m.setAccessible(true);
                            java.io.File f = (java.io.File) m.invoke(null);
                            log("RESULT partial report: " + (f == null ? "none" : f.getPath() + " (" + f.length() + " bytes)"));
                            if (f != null) for (String l : java.nio.file.Files.readAllLines(f.toPath())) log("  | " + l);
                        } catch (ClassNotFoundException e) { log("RESULT partial report: n/a (no Watchdog.Daily)"); }
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            void force() {
                Location here = bot.getLocation();
                log("forcing at " + here + " (nav ON " + NavWalker.ON + ")");
                // NR
                Location sea = null;
                for (int[] c : SEA) { Location l = Location.create(c[0], c[1], 0); if (NavGrid.inBounds(c[0], c[1]) && NavPlanner.plan(here, l, 1) == null) { sea = l; break; } }
                if (sea == null) log("RESULT NR: no unreachable in-grid candidate found");
                else {
                    String n0 = said("saidNoRoute"), r0 = scen("no_route");
                    NavWalker.forget(bot); Location a = NavWalker.step(bot, sea);
                    String n1 = said("saidNoRoute"), r1 = scen("no_route");
                    NavWalker.NO_ROUTE.clear(); NavWalker.forget(bot); Location b = NavWalker.step(bot, sea); // the 60 s retry gate cleared: a second real failure
                    String n2 = said("saidNoRoute"), r2 = scen("no_route");
                    log("RESULT NR no route to " + sea + ": step returned " + a + "/" + b + "; lines " + n0 + " -> " + n1 + " -> (repeat) " + n2 + ", records " + r0 + " -> " + r1 + " -> (repeat) " + r2);
                }
                // RD
                NavWalker.forget(bot); NavWalker.NO_ROUTE.clear();
                String l0 = said("saidRedirect"), q0 = scen("redirect");
                Location h1 = NavWalker.step(bot, DRAYNOR); Location h2 = NavWalker.step(bot, VARROCK);
                String l1 = said("saidRedirect"), q1 = scen("redirect");
                NavWalker.forget(bot);
                Location h3 = NavWalker.step(bot, DRAYNOR); Location h4 = NavWalker.step(bot, VARROCK);
                String l2 = said("saidRedirect"), q2 = scen("redirect");
                log("RESULT RD Draynor then Varrock: hops " + h1 + ", " + h2 + " / " + h3 + ", " + h4 + "; lines " + l0 + " -> " + l1 + " -> (repeat) " + l2 + ", records " + q0 + " -> " + q1 + " -> (repeat) " + q2);
                NavWalker.forget(bot);
                // NV
                String v0 = scen("never_reached");
                br.shopAt = VARROCK; br.shopName = "Test shop"; br.shopDeadline = System.currentTimeMillis() - 1;
                br.shopTick(System.currentTimeMillis());
                log("RESULT NV never reached: records " + v0 + " -> " + scen("never_reached") + ", shopAt now " + br.shopAt);
                // ID
                String i0 = scen("idle_giveup");
                Watchdog.idle(bot, bot.getLocation(), 20);
                log("RESULT ID idle give-up: records " + i0 + " -> " + scen("idle_giveup"));
            }
        });
    }
}
