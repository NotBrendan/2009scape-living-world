package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

/**
 * Trial only (-Dtest.b15a=true): batch 15 items 1 and 2.
 * Teleports: an AFK runner's paid long-trip teleport (goTo, Lumbridge to Seers' bank) and a Watchdog rescue, each with
 * no human anywhere, with a fake human 3 tiles away, and with a fake human only at the landing. Logs the landing tile set
 * in the same tick, where the bot is a tick later, and the animation/graphic on the bot.
 * Run: a bot walking to and fro for 150 ticks with nobody near, then 700 ticks with a fake human following it 2 tiles off,
 * then 120 ticks with the human gone. Logs run toggles, energy and tiles moved per phase.
 */
public final class TestB15A implements StartupListener {
    static void log(String s) { System.out.println("[TestB15A] " + s); }
    static final Location LUM = Location.create(3222, 3218, 0), SEERS = Location.create(2725, 3491, 0);
    static int uid = 915000;

    static AIPlayer human(Location at) throws Exception {
        AIPlayer p = new AIPlayer(at);
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(p, false);
        p.getDetails().accountInfo.setUid(++uid);
        Repository.INSTANCE.getUid_map().put(uid, p);
        return p;
    }
    static String botRun() { // the after build's counters (reflection: the before build has no BotRun)
        try { Class<?> c = Class.forName("content.custom.BotRun"); StringBuilder b = new StringBuilder("BotRun"); for (String f : new String[]{"switchedOn", "switchedOff", "restored", "MINE"}) { java.lang.reflect.Field x = c.getDeclaredField(f); x.setAccessible(true); Object v = x.get(null); b.append(' ').append(f).append('=').append(v instanceof java.util.Map ? String.valueOf(((java.util.Map<?, ?>) v).size()) : String.valueOf(v)); } return b.toString(); }
        catch (Throwable t) { return "BotRun absent"; }
    }
    static AIPlayer mk(Location at) { AIPlayer b = new AIPlayer(at); SmartAdventurers.finishTutorial(b); return b; }
    static String anim(Player p) {
        core.game.world.update.flag.context.Animation a = p.getAnimator().getAnimation();
        core.game.world.update.flag.context.Graphics g = p.getAnimator().getGraphics();
        return "anim " + (a == null ? "none" : a.getId()) + ", gfx " + (g == null ? "none" : g.getId());
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15a")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, k; AIPlayer bot, hum; Location set, from; String what;
            // run phase state
            int ons, offs; boolean wasRun; double eMin, eMax, moved; Location last; int leg; String samples = "";
            final Location[] WAY = {Location.create(3222, 3218, 0), Location.create(3236, 3219, 0), Location.create(3236, 3260, 0), Location.create(3222, 3240, 0)};
            @Override public boolean pulse() {
                try {
                    if (++t < 110) return false;
                    k++;
                    if (phase < 6) return tele();
                    return runPhase();
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
            }
            void clear() { if (bot != null) { try { bot.clear(); } catch (Throwable ignored) { } bot = null; } if (hum != null) { try { hum.clear(); } catch (Throwable ignored) { } hum = null; } }
            boolean tele() throws Exception {
                // phases 0-2: goTo with {no human, human by the bot, human at the landing}; 3-5: rescue the same way
                if (k == 1) {
                    clear();
                    bot = mk(LUM);
                    bot.getInventory().add(new Item(995, 20000));
                    return false;
                }
                if (k == 4) {
                    int c = phase % 3;
                    from = bot.getLocation();
                    Location land = phase < 3 ? Watchdog.landing(SEERS) : Watchdog.nearestBank(from);
                    if (c == 1) hum = human(from.transform(3, 0, 0));
                    if (c == 2) hum = human(land.transform(3, 0, 0));
                    return false;
                }
                if (k == 6) {
                    int c = phase % 3;
                    what = (phase < 3 ? "goTo Seers" : "Watchdog.rescue") + ", " + (c == 0 ? "no human" : c == 1 ? "human 3 tiles from the bot" : "human only at the landing");
                    if (phase < 3) {
                        AfkTasks.Runner r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false);
                        r.walker = false;
                        r.goTo(AfkTasks.State.TO_SPOT, SEERS);
                    } else Watchdog.rescue(bot, bot.getLocation(), 5);
                    set = bot.getProperties().getTeleportLocation();
                    return false;
                }
                if (k == 7) {
                    log("RESULT tele " + phase + " " + what + ": from " + from + ", set same tick " + set + ", a tick later at " + bot.getLocation() + ", " + anim(bot));
                    k = 0; phase++;
                    if (phase == 6) { clear(); log("tele phases done"); }
                }
                return false;
            }
            boolean runPhase() throws Exception {
                if (k == 1) {
                    bot = mk(WAY[0]);
                    return false;
                }
                if (k < 5) return false;
                int pk = k - 5; // ticks into the run phases
                int ph = pk < 150 ? 0 : pk < 850 ? 1 : pk < 970 ? 2 : 3;
                if (pk == 0 || pk == 150 || pk == 850 || pk == 970) {
                    if (pk > 0) log("RESULT run " + new String[]{"no human (150 ticks)", "human following (700 ticks)", "human gone (120 ticks)"}[ph - 1] + ": run turned on " + ons + ", off " + offs + ", energy min " + Math.round(eMin) + " max " + Math.round(eMax) + " end " + Math.round(bot.getSettings().getRunEnergy()) + ", run now " + bot.getSettings().isRunToggled() + ", tiles moved " + Math.round(moved) + (samples.isEmpty() ? "" : ", energy every 50: " + samples) + ", " + botRun());
                    if (ph == 1 && pk == 150) hum = human(bot.getLocation().transform(2, 0, 0));
                    if (ph == 2) { try { hum.clear(); } catch (Throwable ignored) { } hum = null; }
                    if (ph == 3) { clear(); log("DONE"); return true; }
                    ons = 0; offs = 0; eMin = 999; eMax = -1; moved = 0; samples = ""; wasRun = bot.getSettings().isRunToggled(); last = bot.getLocation();
                }
                if (hum != null && pk % 4 == 0) hum.getProperties().setTeleportLocation(bot.getLocation().transform(2, 0, 0)); // keeps up
                boolean run = bot.getSettings().isRunToggled();
                if (run && !wasRun) ons++; if (!run && wasRun) offs++; wasRun = run;
                double e = bot.getSettings().getRunEnergy(); eMin = Math.min(eMin, e); eMax = Math.max(eMax, e);
                if (pk % 50 == 0) samples += (samples.isEmpty() ? "" : " ") + Math.round(e) + (run ? "r" : "w");
                moved += bot.getLocation().getDistance(last); last = bot.getLocation();
                if (!bot.getWalkingQueue().isMoving() && pk % 2 == 0) { leg = (leg + 1) % WAY.length; bot.walkToPosSmart(WAY[leg]); }
                return false;
            }
        });
    }
}
