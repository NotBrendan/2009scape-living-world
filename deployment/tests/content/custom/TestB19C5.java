package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * batch 19c A5, trial only (-Dtest.b19c5=true -Dsmartbots.count=6 -Dsmartbots.deathLoss=true): §6. Three deaths of two bots at one
 * spot by the same NPC (reported through Failures.death, as checkDeath/idleDeath do) -> one repeat-death with deathsHere6h:3 and two
 * Daily death notes before it; a real gravestone of 5 stacks of which 2 are taken away before the bot's own graveTick picks it up ->
 * grave-short with 2 missing ids; 6 deaths of fresh bots in an hour after a quiet hour on a Rates fixture (Economy.roleRates, minutes
 * advanced by the test) -> one death-spike-fresh. Prints [TestB19C5] RESULT ... and DONE.
 */
public final class TestB19C5 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C5] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c5")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 30) return false;
                if (Failures.started && Watchdog.Daily.day != null) { new Thread(TestB19C5::run, "TestB19C5").start(); return true; }
                if (t > 600) { log("RESULT Failures never started"); log("DONE"); return true; }
                return false;
            }
        });
    }
    static void run() { try { go(); } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); } log("DONE"); }
    static Object cx(JSONObject j, String k) { Object c = j.get("ctx"); return c instanceof JSONObject ? ((JSONObject) c).get(k) : null; }
    static int deathNote(String k) { Map<String, Integer> m = Watchdog.Daily.TODAY.get("death"); return m == null ? 0 : m.getOrDefault(k, 0); }

    static void go() {
        List<SmartAdventurers.Brain> bs = TestB19CUtil.waitBrains(3, 12 * 60_000L);
        if (bs.size() < 2) { log("RESULT not enough bots"); return; }
        Player a = bs.get(0).bot, b = bs.get(1).bot;
        // --- repeat-death
        final Location spot = Location.create(3104, 3367, 0); final String killer = "Test wizard (level 7)";
        String key = Failures.cluster16(spot) + "|test-wizard";
        int[] notes = new int[3]; int r0 = TestB19CUtil.recs("repeat-death", null).size();
        Player[] who = {a, b, a};
        for (int i = 0; i < 3; i++) { final Player p = who[i]; TestB19CUtil.game(() -> Failures.death(p, spot, killer, "test fight", -1, null, false)); notes[i] = deathNote(key); TestB19CUtil.sleep(300); }
        List<JSONObject> rd = TestB19CUtil.recs("repeat-death", null);
        JSONObject j = rd.size() > r0 ? rd.get(rd.size() - 1) : null;
        log("RESULT repeat-death: Daily death notes " + notes[0] + "," + notes[1] + "," + notes[2] + "; records +" + (rd.size() - r0) + (j == null ? "" : ", deathsHere6h " + cx(j, "deathsHere6h") + ", roles " + cx(j, "roles") + ", bug " + j.get("bug")) + " (expect 1,2,3; +1; 3)");
        // --- grave-short: a real stone with 5 stacks, 2 taken before the pickup
        SmartAdventurers.Brain gb = bs.get(bs.size() > 2 ? 2 : 1); Player g = gb.bot;
        String made = TestB19CUtil.onGame(() -> {
            if (gb.runner != null) { try { gb.runner.stop(); } catch (Throwable ignored) { } gb.runner = null; AfkTasks.RUNNING.remove(g.getUsername().toLowerCase()); }
            if (g.getDetails().getUid() == 0) g.getDetails().accountInfo.setUid(0x40000000 + ((core.game.bots.AIPlayer) g).getUid());
            Location at = g.getLocation();
            Item[] items = {new Item(1351), new Item(1265), new Item(1205)}; // the stone holds 3 of the 5 stacks the death lost (2 went missing before the pickup)
            core.game.node.entity.combat.graves.Grave gr = core.game.node.entity.combat.graves.GraveController.produceGrave(core.game.node.entity.combat.graves.GraveController.getGraveType(g));
            gr.initialize(g, at, items);
            int n = 2;
            gb.diedLost = 5; gb.diedValue = 1000; gb.diedIds = new java.util.ArrayList<>(java.util.Arrays.asList(1351, 1265, 1205, 1171, 1139));
            gb.graveAt = at; gb.graveUntil = System.currentTimeMillis() + 120_000; gb.shopAt = null; gb.nextThink = 0;
            return "grave at " + at + " with 3 of the 5 lost stacks (" + n + " missing)";
        });
        List<JSONObject> gs = null; long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 90_000) { TestB19CUtil.sleep(3000); gs = TestB19CUtil.recs("grave-short", g.getUsername()); if (!gs.isEmpty()) break; }
        log("RESULT grave-short: " + made + "; records " + (gs == null ? 0 : gs.size()) + (gs == null || gs.isEmpty() ? "" : ", lost " + cx(gs.get(0), "lost") + ", recovered " + cx(gs.get(0), "recovered") + ", missing " + cx(gs.get(0), "missing")) + " (expect 1, 5, 3, 2 ids)");
        // --- death-spike on a Rates fixture
        Player fresh = null; for (SmartAdventurers.Brain x : bs) { String k = x.bot.getUsername().toLowerCase(); if (!SmartAdventurers.HIGH_TIER.getOrDefault(k, false) && !SmartAdventurers.VETERAN.getOrDefault(k, false)) { fresh = x.bot; break; } }
        if (fresh == null) { log("RESULT death-spike: no fresh bot online"); return; }
        Economy.Rates r = Economy.roleRates(); long m0 = 50_000_000L;
        r.sample(m0); for (long m = m0 + 1; m < m0 + 120; m++) r.sample(m);
        int quiet = Economy.deathSpikes(r, m0 + 120);
        final Player fp = fresh; int s0 = TestB19CUtil.recs("death-spike", "-").size();
        for (int i = 0; i < 6; i++) { final int k = i; TestB19CUtil.game(() -> Failures.death(fp, Location.create(2500 + k * 64, 3500, 0), "Spike test " + k, "test", -1, null, false)); }
        int alerts = Economy.deathSpikes(r, m0 + 121);
        List<JSONObject> sp = TestB19CUtil.recs("death-spike", "-");
        JSONObject s = sp.size() > s0 ? sp.get(sp.size() - 1) : null;
        log("RESULT death-spike: quiet-hour alerts " + quiet + ", after 6 fresh deaths alerts " + alerts + ", records +" + (sp.size() - s0) + (s == null ? "" : ", role " + cx(s, "role") + ", prev " + cx(s, "prev") + ", cur " + cx(s, "cur") + ", bug " + s.get("bug")) + " (expect 0, 1, +1, fresh 0 -> 6, b-death-spike-fresh)");
    }
}
