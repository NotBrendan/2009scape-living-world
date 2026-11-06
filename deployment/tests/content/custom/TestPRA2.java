package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.pra2=true): pathing review 10-23 A-2. A fighting bot with no food, kept at 10/60 hp, standing at the
 * Draynor bank with a man who keeps attacking it (scripted: an aggressive monster beside the bank). Its runner (3-tick
 * pulse) flees, tabs to the bank it stands at, and in RECOVER is still being hit. For 600 ticks: emergency teleports,
 * the retreat counter, coins spent, how it ended.
 */
public final class TestPRA2 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRA2] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.pra2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, k, lastEm, lastRet = -1; AIPlayer b; NPC man; AfkTasks.Runner r; long coins0; StringBuilder em = new StringBuilder();
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 60) { b = TestCR1.bot(Location.create(3092, 3244, 0), 60, 20, 20, 20); b.getBank().add(new Item(995, 100_000)); return false; }
                    if (t == 64) {
                        man = NPC.create(1, Location.create(3093, 3243, 0)); man.init();
                        r = new AfkTasks.Runner(b, AfkTasks.Task.COW, true, 0, false, false);
                        r.setDelay(3); AfkTasks.RUNNING.put(b.getUsername().toLowerCase(), r); GameWorld.getPulser().submit(r);
                        coins0 = Ledger.Wallet.coins(b);
                        log("bot " + b.getUsername() + " at " + b.getLocation() + " (Draynor bank), no food, " + coins0 + " coins; a man attacks it");
                    }
                    if (r == null || t < 64) return false;
                    k++;
                    if (b.getSkills().getLifepoints() != 10) b.getSkills().setLifepoints(10);
                    if (man.isActive() && man.getLocation().getDistance(b.getLocation()) <= 20 && man.getProperties().getCombatPulse().getVictim() != b) man.attack(b);
                    if (r.emergencies != lastEm) { lastEm = r.emergencies; em.append(k).append(' '); log("   tick " + k + ": emergency teleport #" + r.emergencies + " (state " + r.state + ", retreats " + r.retreats + ", at " + b.getLocation() + ")"); }
                    if (r.retreats != lastRet) { lastRet = r.retreats; log("   tick " + k + ": retreats " + r.retreats + ", state " + r.state + ", at " + b.getLocation()); }
                    if (r.halted || k >= 600) {
                        log("RESULT after " + k + " ticks: " + r.emergencies + " emergency teleports (at ticks " + em.toString().trim() + "), retreats " + r.retreats + ", coins spent " + (coins0 - Ledger.Wallet.coins(b)) + ", runner " + (r.halted ? "finished" : "STILL RUNNING in " + r.state) + ", at " + b.getLocation());
                        if (!r.halted) r.halt();
                        man.clear(); log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
