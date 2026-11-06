package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.aua11=true): audit 10-23 A-11. A bot with 0 coins at Lumbridge is started (Brain.start) on a job
 * east of the Salve: goTo finishes the runner at once ("Can't afford the trip east", or a gate it can't pass). Is the
 * halted runner left in AfkTasks.RUNNING (the brain then thinks it is working for ever)?
 */
public final class TestAUA11 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUA11] " + s); }
    static Location spotOf(SmartAdventurers.Job j) {
        if (j.hunt != null) return j.hunt.spot;
        AfkTasks.Spot[] s = AfkTasks.SPOTS.get(j.task); return s == null || s.length == 0 ? null : s[0].at;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aua11")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer b; SmartAdventurers.Brain br; String key;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 200) {
                        SmartAdventurers.Job job = null;
                        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) { Location s = spotOf(j); if (s != null && s.getX() >= 3400 && s.getY() < 6400) { boolean all = true; for (AfkTasks.Spot x : AfkTasks.SPOTS.getOrDefault(j.task, new AfkTasks.Spot[0])) if (j.hunt == null && !(x.at.getX() >= 3400)) all = false; if (all && (job == null || j.hunt == null)) job = j; } }
                        if (job == null) { log("no job east of the Salve in JOBS (" + SmartAdventurers.JOBS.size() + " jobs)"); log("DONE"); return true; }
                        b = TestCR1.bot(null, 40, 40, 40, 40);
                        key = b.getUsername().toLowerCase(java.util.Locale.ROOT);
                        br = TestCR1.rig(b, false);
                        br.exchangeChecked = true;
                        for (Item it : b.getBank().toArray()) if (it != null && it.getId() == 995) b.getBank().remove(it);
                        b.getInventory().remove(new Item(995, b.getInventory().getAmount(995)));
                        log("bot " + b.getUsername() + " at " + b.getLocation() + ", coins " + Ledger.Wallet.coins(b) + "; job " + job.label() + " at " + spotOf(job));
                        br.start(job);
                        AfkTasks.Runner r = AfkTasks.RUNNING.get(key);
                        log("right after start: in RUNNING " + (r != null) + (r != null ? ", halted " + r.halted + ", last '" + r.last + "'" : "") + "; brain runner " + (br.runner != null ? "halted " + br.runner.halted + ", last '" + br.runner.last + "'" : "none"));
                    }
                    if (t == 300) {
                        AfkTasks.Runner r = AfkTasks.RUNNING.get(key);
                        log("RESULT 100 ticks later: in RUNNING " + (r != null) + (r != null ? " (halted " + r.halted + ")" : "") + ", bot at " + b.getLocation() + "; the brain's still-working check would say " + (AfkTasks.RUNNING.containsKey(key) ? "WORKING (stuck)" : "free for a new job"));
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
