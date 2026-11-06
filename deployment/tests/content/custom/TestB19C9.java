package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Trial only (-Dtest.b19c9=true -Dsmartbots.count=4 -Dafk.debug=true): batch 19c builder B, B2 = the skilling detectors (design 7):
 * Runner.finish -> Failures.jobEnd (A's table) and the no-gain / no-xp rule in tickOnce. Throwaway AIPlayers with test runners (3-tick
 * pulse like a brain's), on Lumbridge castle's top floor where no tree, rock or NPC is in reach ([Fable] instead of removing world objects:
 * "the trees removed" is a spot with no trees; collected is preset to 3 so the runner waits for respawns instead of quitting):
 *   J1 woodcutting (axe held) with no tree in reach      -> one no-gain, rsub spot-depleted, at 1,000 WORK ticks, ctx.task TREE, then none (once per trip)
 *   J2 copper mining without a pickaxe at the swamp mine  -> wrong-tool (from A's finish table, or no-gain's sub-code wrong-tool)
 *   J3 chickens with no chicken in reach (collected 1)    -> no-xp at 1,000 WORK ticks
 *   J4 a drop-mode runner whose pack is 27 pots + an axe  -> finish "Inventory is full of things I won't drop." -> inventory-full
 *   J5 a runner that finishes with "Target reached."      -> no skilling record
 * DONE after ~13 minutes.
 */
public final class TestB19C9 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C9] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c9")) return;
        new Thread(TestB19C9::run, "TestB19C9").start();
    }

    static AfkTasks.Runner runner(AIPlayer b, AfkTasks.Task t, boolean bank, int stopAfter, Location spot) {
        AfkTasks.Runner r = new AfkTasks.Runner(b, t, bank, stopAfter, false, false);
        r.spot = spot; r.setDelay(3);
        AfkTasks.RUNNING.put(b.getUsername().toLowerCase(java.util.Locale.ROOT), r); GameWorld.getPulser().submit(r);
        return r;
    }

    static void run() {
        List<Player> made = new ArrayList<>();
        try {
            for (int i = 0; i < 600 && Watchdog.Daily.day == null; i++) Thread.sleep(500);
            Thread.sleep(25_000);
            log("start: failures " + (Watchdog.FAIL_ON ? "on" : "off") + ", skilling domain " + (Failures.on("skilling") ? "on" : "off"));
            final Location top = Location.create(3208, 3220, 2), top2 = Location.create(3206, 3216, 2), mine = Location.create(3228, 3147, 0);
            final AfkTasks.Runner[] r = new AfkTasks.Runner[5]; final AIPlayer[] b = new AIPlayer[5];
            int ng0 = TestB19C8.daily("no-gain"), nx0 = TestB19C8.daily("no-xp"), wt0 = TestB19C8.daily("wrong-tool"), inf0 = TestB19C8.daily("inventory-full");
            TestB19C8.onGame(() -> {
                b[0] = TestB19C8.spawn(top); made.add(b[0]); b[0].getInventory().add(new Item(1351)); // bronze axe
                r[0] = runner(b[0], AfkTasks.Task.TREE, false, 0, top); r[0].collected = 3; r[0].questSpots = new AfkTasks.Spot[]{new AfkTasks.Spot("test top floor", top.getX(), top.getY())};
                b[1] = TestB19C8.spawn(mine); made.add(b[1]);
                r[1] = runner(b[1], AfkTasks.Task.COPPER, false, 0, mine);
                b[2] = TestB19C8.spawn(top2); made.add(b[2]);
                for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { b[2].getSkills().setStaticLevel(sk, 20); b[2].getSkills().setLevel(sk, 20); } b[2].getSkills().updateCombatLevel();
                r[2] = runner(b[2], AfkTasks.Task.CHICKEN, false, 0, top2); r[2].collected = 1; r[2].questSpots = new AfkTasks.Spot[]{new AfkTasks.Spot("test top floor", top2.getX(), top2.getY())};
                b[3] = TestB19C8.spawn(top); made.add(b[3]); b[3].getInventory().add(new Item(1351)); for (int i = 0; i < 27; i++) b[3].getInventory().add(new Item(1931));
                r[3] = runner(b[3], AfkTasks.Task.TREE, false, 0, top);
                b[4] = TestB19C8.spawn(top); made.add(b[4]); b[4].getInventory().add(new Item(1351));
                r[4] = runner(b[4], AfkTasks.Task.TREE, false, 1, top); r[4].collected = 1;
            });
            for (int i = 0; i < 5; i++) log("J" + (i + 1) + " " + b[i].getUsername() + " " + r[i].task.name() + " at " + b[i].getLocation());
            TestB19C8.sleepTicks(40);
            List<JSONObject> rs = TestB19C8.records();
            log("RESULT J4 inventory-full: " + TestB19C8.of(rs, b[3].getUsername(), "inventory-full").size() + " (expect 1), runner halted " + r[3].halted + " last '" + r[3].last + "': " + TestB19C8.brief(TestB19C8.first(TestB19C8.of(rs, b[3].getUsername(), "inventory-full"))));
            int j5 = 0; for (JSONObject x : rs) if (b[4].getUsername().equals(x.get("bot")) && "skilling".equals(x.get("domain"))) j5++;
            log("RESULT J5 Target reached: halted " + r[4].halted + ", skilling records for it " + j5 + " (expect 0)");
            for (int i = 0; i < 140 && !(r[0].noGainNoted && r[2].noGainNoted && (r[1].halted || r[1].noGainNoted)); i++) TestB19C8.sleepTicks(10);
            TestB19C8.sleepTicks(150); // past the rule's window again: still one per trip
            rs = TestB19C8.records();
            List<JSONObject> j1 = TestB19C8.of(rs, b[0].getUsername(), "no-gain");
            JSONObject c1 = TestB19C8.first(j1) == null ? null : (JSONObject) TestB19C8.first(j1).get("ctx");
            log("RESULT J1 no-gain: " + j1.size() + " (expect 1, once per trip), rsub '" + (TestB19C8.first(j1) == null ? "?" : TestB19C8.first(j1).get("rsub")) + "' (expect spot-depleted), ctx.task " + (c1 == null ? "?" : c1.get("task")) + ", sinceGainTicks " + (c1 == null ? "?" : c1.get("sinceGainTicks")) + " (expect ~1000), unstucks " + (c1 == null ? "?" : c1.get("unstucks")) + "; bug " + (TestB19C8.first(j1) == null ? "?" : TestB19C8.first(j1).get("bug")) + "; Daily no-gain +" + (TestB19C8.daily("no-gain") - ng0));
            log("RESULT J1 replay spec in ctx: " + (c1 == null ? "?" : c1.get("replay")) + " (A moves ctx.replay into the bug entry); fid in the log ring: " + r[0].noGainFid);
            List<JSONObject> j2 = new ArrayList<>(); for (JSONObject x : rs) if (b[1].getUsername().equals(x.get("bot")) && "skilling".equals(x.get("domain"))) j2.add(x);
            String j2s = ""; for (JSONObject x : j2) j2s += x.get("reason") + "/" + x.get("rsub") + " ";
            log("RESULT J2 copper without a pickaxe: skilling records " + j2.size() + ": " + j2s.trim() + " (expect wrong-tool as the code or the rsub); runner halted " + r[1].halted + " last '" + r[1].last + "'; Daily wrong-tool +" + (TestB19C8.daily("wrong-tool") - wt0));
            List<JSONObject> j3 = TestB19C8.of(rs, b[2].getUsername(), "no-xp");
            log("RESULT J3 no-xp: " + j3.size() + " (expect 1): " + TestB19C8.brief(TestB19C8.first(j3)) + "; Daily no-xp +" + (TestB19C8.daily("no-xp") - nx0));
            log("RESULT J4 Daily inventory-full +" + (TestB19C8.daily("inventory-full") - inf0) + "; skilling lines well formed: " + TestB19C8.wellFormed(rs, "skilling"));
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        finally {
            try { TestB19C8.onGame(() -> { for (Player p : made) { try { AfkTasks.Runner x = AfkTasks.RUNNING.get(p.getUsername().toLowerCase(java.util.Locale.ROOT)); if (x != null) x.halt(); ((AIPlayer) p).clear(); } catch (Throwable ignored) { } } }); } catch (Throwable ignored) { }
            log("DONE");
        }
    }
}
