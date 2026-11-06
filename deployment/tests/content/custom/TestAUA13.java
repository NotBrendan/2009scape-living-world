package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;

/**
 * Trial only (-Dtest.aua13=true): audit 10-23 A-13 and the AfkTasks side of A-11.
 * A-13: a runner flees (auto-retaliate off), then the player logs out mid-flee. What does the save hold for "retaliation"?
 *       (Read from PlayerSaver.saveSettings into a JSON object in memory; nothing is written to disk.)
 * A-11: AfkTasks.begin(..., at = a spot east of the Salve) for a 0-coin bot: goTo finishes the runner at once
 *       ("Can't afford the trip east"). Is a halted runner left in RUNNING? And SmartAdventurers' order (goTo, then put).
 */
public final class TestAUA13 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUA13] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aua13")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, b, c, d; AfkTasks.Runner ra, rd;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        // A-13, logout mid-flee
                        a = TestCR1.bot(Location.create(3257, 3270, 0), 40, 40, 40, 40);
                        a.getProperties().setRetaliating(true);
                        ra = new AfkTasks.Runner(a, AfkTasks.Task.COW, true, 0, false, true);
                        AfkTasks.RUNNING.put(AfkTasks.key(a), ra);
                        GameWorld.getPulser().submit(ra);
                        // A-13 control: flee, then the normal restore (recover/halt), then logout
                        d = TestCR1.bot(Location.create(3257, 3270, 0), 40, 40, 40, 40);
                        d.getProperties().setRetaliating(true);
                        rd = new AfkTasks.Runner(d, AfkTasks.Task.COW, true, 0, false, true);
                        AfkTasks.RUNNING.put(AfkTasks.key(d), rd);
                        GameWorld.getPulser().submit(rd);
                    }
                    if (t == 160) {
                        ra.flee("test flee");
                        log("A-13 after flee: retaliating " + a.getProperties().isRetaliating() + ", state " + ra.state);
                        rd.flee("test flee");
                        rd.halt();
                        log("A-13 control after flee+halt: retaliating " + d.getProperties().isRetaliating() + ", logout hook left " + d.logoutListeners.keySet());
                    }
                    if (t == 163) {
                        a.clear(); // the logout (finishClear runs the logout listeners, then the engine saves)
                        JSONObject root = new JSONObject();
                        new core.game.node.entity.player.info.login.PlayerSaver(a).saveSettings(root);
                        Object ret = ((JSONObject) root.get("settings")).get("retaliation");
                        log("RESULT A-13 logout mid-flee: save would hold retaliation=" + ret + " (" + (Boolean.TRUE.equals(ret) ? "restored" : "LEFT OFF") + "); in RUNNING " + AfkTasks.RUNNING.containsKey(AfkTasks.key(a)));
                        d.clear();
                        JSONObject r2 = new JSONObject();
                        new core.game.node.entity.player.info.login.PlayerSaver(d).saveSettings(r2);
                        log("RESULT A-13 control (halted before logout): retaliation=" + ((JSONObject) r2.get("settings")).get("retaliation"));
                    }
                    if (t == 200) {
                        // A-11, AfkTasks.begin with a chosen spot across the Salve, 0 coins
                        b = TestCR1.bot(null, 40, 40, 40, 40);
                        for (Item it : b.getBank().toArray()) if (it != null && it.getId() == 995) b.getBank().remove(it);
                        b.getInventory().remove(new Item(995, b.getInventory().getAmount(995)));
                        AfkTasks.Spot east = new AfkTasks.Spot("Canifis test spot", 3494, 3488);
                        AfkTasks.begin(b, AfkTasks.Task.F_MAN, true, true, false, false, false, 0, "keep", false, true, 0, east);
                        AfkTasks.Runner r = AfkTasks.RUNNING.get(AfkTasks.key(b));
                        log("RESULT A-11 begin(): coins " + Ledger.Wallet.coins(b) + ", in RUNNING " + (r != null) + (r != null ? " (halted " + r.halted + ", last '" + r.last + "')" : ""));
                        // A-11, the SmartAdventurers order: new Runner, goTo, then RUNNING.put and submit
                        c = TestCR1.bot(null, 40, 40, 40, 40);
                        for (Item it : c.getBank().toArray()) if (it != null && it.getId() == 995) c.getBank().remove(it);
                        c.getInventory().remove(new Item(995, c.getInventory().getAmount(995)));
                        AfkTasks.Runner rc = new AfkTasks.Runner(c, AfkTasks.Task.F_MAN, true, 0, false, true);
                        rc.sell = true; rc.spot = east.at;
                        Object ok = null;
                        try { ok = AfkTasks.Runner.class.getDeclaredMethod("goTo", AfkTasks.State.class, Location.class).invoke(rc, AfkTasks.State.TO_SPOT, east.at); } catch (Throwable e) { log("goTo call failed " + e); }
                        AfkTasks.RUNNING.put(AfkTasks.key(c), rc);
                        GameWorld.getPulser().submit(rc);
                        log("RESULT A-11 caller order (goTo then put): goTo returned " + ok + ", runner halted " + rc.halted + ", in RUNNING " + AfkTasks.RUNNING.containsKey(AfkTasks.key(c)) + ", last '" + rc.last + "'");
                    }
                    if (t == 300) {
                        log("RESULT A-11 100 ticks later: begin() bot in RUNNING " + AfkTasks.RUNNING.containsKey(AfkTasks.key(b)) + ", caller-order bot in RUNNING " + AfkTasks.RUNNING.containsKey(AfkTasks.key(c)));
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
