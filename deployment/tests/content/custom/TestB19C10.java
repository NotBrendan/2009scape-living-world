package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trial only (-Dtest.b19c10=true -Dsmartbots.count=4 -Dtraders.fast=true): batch 19c builder B, B3 = the Watchdog-tick health, lifecycle
 * and save checks plus tick-overrun (design 8.1, 8.2, 10, 4.4 deal-deadlock):
 *   H1 80 test keys in AIRepository's pulse map (re-added every 5 s: PlayerTrades sweeps the map every 30 s) -> one script-starvation
 *      (ctx.scripts >= 80); removed, three clean Watchdog ticks -> a live "pass" replay on b-script-starvation-scripts (A's periodic)
 *   H2 SmartAdventurers.profilesWrittenAt forced 40 min old (re-forced until seen; -Dtraders.fast saves every minute) -> save-stale for
 *      smartbots.json (bug b-save-stale-smartbots-json)
 *   H3 an adventurer's bot cleared from the world without logOut -> vanished after two Watchdog ticks, ctx.lastSeen
 *   H4 a test pulse that sleeps 700 ms on five ticks in a minute and reports its cost -> tick-overrun with ctx.topPulse naming it, and
 *      (A's rule) long-pulse for its source
 *   H5 an adventurer kept in Deals.OFFERS with one claimed offer (the buyer locked in place, the claim refreshed) for 6 min -> deal-deadlock
 * DONE after ~9 minutes. Trial data only.
 */
public final class TestB19C10 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C10] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c10")) return;
        new Thread(TestB19C10::run, "TestB19C10").start();
    }

    static volatile boolean stuffing, forcing, sleeping;

    static void run() {
        List<Player> made = new ArrayList<>();
        Deals.Offer[] offer = new Deals.Offer[1];
        try {
            for (int i = 0; i < 600 && Watchdog.Daily.day == null; i++) Thread.sleep(500);
            for (int i = 0; i < 240 && SmartAdventurers.BRAINS.size() < 3; i++) Thread.sleep(500);
            Thread.sleep(150_000); // past the 120 s boot window of tick-overrun and long-pulse
            log("start: failures " + (Watchdog.FAIL_ON ? "on" : "off") + ", health " + Failures.on("health") + ", save " + Failures.on("save") + ", lifecycle " + Failures.on("lifecycle") + "; adventurers " + SmartAdventurers.BRAINS.size());
            final List<SmartAdventurers.Brain> brains = new ArrayList<>(SmartAdventurers.BRAINS.values());
            int ss0 = TestB19C8.daily("script-starvation"), st0 = TestB19C8.daily("save-stale"), va0 = TestB19C8.daily("vanished"), to0 = TestB19C8.daily("tick-overrun"), lp0 = TestB19C8.daily("long-pulse"), dd0 = TestB19C8.daily("deal-deadlock");
            // ---- H5 first (it needs 6 minutes): a claimed offer that never settles
            final Player[] seller = new Player[1], buyer = new Player[1];
            TestB19C8.onGame(() -> {
                for (SmartAdventurers.Brain b : brains) if (b.bot != null && b.bot.isActive() && seller[0] == null) seller[0] = b.bot;
                AIPlayer by = TestB19C8.spawn(Location.create(3222, 3240, 0)); made.add(by); by.getLocks().lockMovement(1_200); buyer[0] = by;
                Deals.Offer o = new Deals.Offer(seller[0], 1511, 1, 10); o.buyer = by; o.claimedAt = System.currentTimeMillis(); offer[0] = o; Deals.OFFERS.add(o);
            });
            log("H5 offer by " + seller[0].getUsername() + ", buyer " + buyer[0].getUsername() + " locked in place");
            final Thread keep = new Thread(() -> { try { while (offer[0] != null) { GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { Deals.Offer o = offer[0]; if (o != null) { o.claimedAt = System.currentTimeMillis(); o.done = false; if (!Deals.OFFERS.contains(o)) Deals.OFFERS.add(o); } return true; } }); Thread.sleep(5_000); } } catch (Throwable ignored) { } }, "TestB19C10-keep");
            keep.setDaemon(true); keep.start();
            // ---- H1 script-starvation
            stuffing = true;
            Thread stuff = new Thread(() -> { try { while (stuffing) { GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { Map<String, core.game.bots.GeneralBotCreator.BotScriptPulse> m = core.game.bots.AIRepository.getPulseRepository(); for (int i = 0; i < 80; i++) m.put("t19c10-" + i, null); return true; } }); Thread.sleep(5_000); } } catch (Throwable ignored) { } }, "TestB19C10-stuff");
            stuff.setDaemon(true); stuff.start();
            // ---- H2 save-stale
            forcing = true;
            Thread force = new Thread(() -> { try { while (forcing) { SmartAdventurers.profilesWrittenAt = System.nanoTime() - 40 * 60_000_000_000L; Thread.sleep(2_000); } } catch (Throwable ignored) { } }, "TestB19C10-force");
            force.setDaemon(true); force.start();
            // ---- H3 vanished
            final Player[] gone = new Player[1];
            TestB19C8.onGame(() -> { for (SmartAdventurers.Brain b : brains) if (b.bot != null && b.bot.isActive() && b.bot != seller[0] && gone[0] == null) gone[0] = b.bot; if (gone[0] != null) ((AIPlayer) gone[0]).clear(); });
            log("H3 cleared " + (gone[0] == null ? "nobody (no second adventurer)" : gone[0].getUsername()) + " without logOut");
            // ---- H4 a slow test pulse: 700 ms on five ticks
            GameWorld.getPulser().submit(new Pulse(2) { int n; @Override public boolean pulse() { long t0 = System.nanoTime(); try { Thread.sleep(700); } catch (Throwable ignored) { } Watchdog.fpulse("TestB19C10", null, System.nanoTime() - t0); return ++n >= 5; } });
            Thread.sleep(100_000);
            stuffing = false; forcing = false;
            TestB19C8.onGame(() -> { Map<String, core.game.bots.GeneralBotCreator.BotScriptPulse> m = core.game.bots.AIRepository.getPulseRepository(); for (int i = 0; i < 80; i++) m.remove("t19c10-" + i); });
            List<JSONObject> rs = TestB19C8.records();
            List<JSONObject> ss = TestB19C8.of(rs, "-", "script-starvation");
            JSONObject sc = TestB19C8.first(ss) == null ? null : (JSONObject) TestB19C8.first(ss).get("ctx");
            log("RESULT H1 script-starvation: " + ss.size() + " (expect 1 in the hour), ctx scripts " + (sc == null ? "?" : sc.get("scripts")) + " (expect >= 80), limit " + (sc == null ? "?" : sc.get("limit")) + ", roles " + (sc == null ? "?" : sc.get("roles")) + ", bug " + (TestB19C8.first(ss) == null ? "?" : TestB19C8.first(ss).get("bug")) + "; Daily +" + (TestB19C8.daily("script-starvation") - ss0));
            List<JSONObject> st = TestB19C8.of(rs, "-", "save-stale");
            log("RESULT H2 save-stale: " + st.size() + " (expect 1 for smartbots.json): " + TestB19C8.brief(TestB19C8.first(st)) + "; Daily +" + (TestB19C8.daily("save-stale") - st0));
            List<JSONObject> va = gone[0] == null ? new ArrayList<>() : TestB19C8.of(rs, gone[0].getUsername(), "vanished");
            log("RESULT H3 vanished: " + va.size() + " (expect 1): " + TestB19C8.brief(TestB19C8.first(va)) + "; Daily +" + (TestB19C8.daily("vanished") - va0));
            List<JSONObject> to = TestB19C8.of(rs, "-", "tick-overrun"); List<JSONObject> lp = TestB19C8.of(rs, null, "long-pulse");
            log("RESULT H4 tick-overrun: " + to.size() + " (expect 1): " + TestB19C8.brief(TestB19C8.first(to)) + "; Daily +" + (TestB19C8.daily("tick-overrun") - to0));
            String lps = ""; for (JSONObject x : lp) lps += TestB19C8.brief(x) + " ; ";
            log("RESULT H4 long-pulse (A's rule on Watchdog.fpulse -> Failures.pulseCost): " + lp.size() + " records: " + lps + "Daily +" + (TestB19C8.daily("long-pulse") - lp0));
            Thread.sleep(150_000); // three clean Watchdog ticks for H1 and H2, and H5 past 5.5 minutes
            Failures.flushNow(8_000);
            String sb = "b-script-starvation-scripts", pr = "?";
            synchronized (Failures.Bugs.LOCK) { Failures.Bug b = Failures.Bugs.VIEW.get(sb); if (b != null) { pr = b.status + ", replays " + b.replays.size(); for (Map<String, Object> x : b.replays) pr += " [" + x.get("world") + " " + x.get("bot") + " " + x.get("result") + " '" + x.get("why") + "']"; } }
            log("RESULT H1 self-clear after 3 clean checks: " + sb + " " + pr + " (expect one live pass, bot -, why 'clear for 3 checks')");
            for (int i = 0; i < 40 && TestB19C8.daily("deal-deadlock") == dd0; i++) Thread.sleep(5_000);
            rs = TestB19C8.records();
            List<JSONObject> dd = TestB19C8.of(rs, seller[0].getUsername(), "deal-deadlock");
            log("RESULT H5 deal-deadlock: " + dd.size() + " (expect 1): " + TestB19C8.brief(TestB19C8.first(dd)) + "; Daily +" + (TestB19C8.daily("deal-deadlock") - dd0));
            log("RESULT records well formed: health " + TestB19C8.wellFormed(rs, "health") + ", save " + TestB19C8.wellFormed(rs, "save") + ", lifecycle " + TestB19C8.wellFormed(rs, "lifecycle") + ", economy " + TestB19C8.wellFormed(rs, "economy"));
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        finally {
            stuffing = false; forcing = false;
            final Deals.Offer o = offer[0]; offer[0] = null;
            try { TestB19C8.onGame(() -> { if (o != null) { Deals.OFFERS.remove(o); o.done = true; } Map<String, core.game.bots.GeneralBotCreator.BotScriptPulse> m = core.game.bots.AIRepository.getPulseRepository(); for (int i = 0; i < 80; i++) m.remove("t19c10-" + i); for (Player p : made) try { ((AIPlayer) p).clear(); } catch (Throwable ignored) { } }); } catch (Throwable ignored) { }
            log("DONE");
        }
    }
}
