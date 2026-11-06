package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/** Trial only (-Dtest.b1=true): a hill-giant fighter in Taverley dungeon, out of food, flees; then recovers with food banked. */
public final class TestB1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB1] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer bot; AfkTasks.Runner r; long coins0;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 60) {
                        bot = new AIPlayer(Location.create(2913, 9832, 0)); // Taverley dungeon, hill giants
                        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, 40); bot.getSkills().setLevel(sk, 40); }
                        bot.getInventory().clear(); bot.getBank().clear();
                        Ledger.Wallet.give(bot, 10_000);
                        r = new AfkTasks.Runner(bot, AfkTasks.Task.BIGBONES, true, 0, false, true);
                        AfkTasks.RUNNING.put(bot.getUsername().toLowerCase(), r);
                        GameWorld.getPulser().submit(r);
                        coins0 = Ledger.Wallet.coins(bot);
                        log("fighter " + bot.getUsername() + " at " + bot.getLocation() + ", runner nearestBank=" + r.nearestBank() + ", coins " + coins0);
                    }
                    if (t == 64) { bot.getSkills().setLifepoints(8); r.flee("low on health with no food left"); log("flee called: state " + r.state + ", dest " + r.dest); }
                    if (t == 72) {
                        log("FLEE RESULT at " + bot.getLocation() + " (underground=" + (bot.getLocation().getY() > 6400) + "), coins " + Ledger.Wallet.coins(bot) + " (paid " + (coins0 - Ledger.Wallet.coins(bot)) + "), state " + r.state + ", dest " + r.dest);
                        // part 2: back underground, safe, no food in the pack but some in the bank
                        bot.getProperties().setTeleportLocation(Location.create(2913, 9832, 0));
                        bot.getBank().add(new Item(379, 20)); bot.getSkills().setLifepoints(32); // lobsters; health above the flee line
                        // batch 21 B1: "safe" for real. Hill giants (level 28) only attack players up to twice their level; at 40s the bot
                        // is 49, and part 1's attacker (a giant bat) still chased it (stopped at 72-75, before it lands). With one on it recover() rests in combat and
                        // re-flees after 10 ticks (correct), so RECOVER ended underground or on the surface by the luck of the aggro.
                        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE}) { bot.getSkills().setStaticLevel(sk, 75); bot.getSkills().setLevel(sk, 75); }
                        try { bot.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
                    }
                    if (t >= 72 && t <= 75) for (NPC n : RegionManager.getLocalNpcs(Location.create(2913, 9832, 0), 16)) // batch 21 B1: safe means nobody on it
                        if (n != null && n.getProperties().getCombatPulse().getVictim() == bot) { n.getProperties().getCombatPulse().stop(); log("tick " + t + ": stopped " + n.getName() + " (still after it from part 1)"); }
                    if (t == 76) { coins0 = Ledger.Wallet.coins(bot); bot.getSkills().setLifepoints(32); log("before recover at " + bot.getLocation() + " coins " + Ledger.Wallet.coins(bot) + " pack food " + bot.getInventory().getAmount(379)); r.recover(); log("recover called at " + bot.getLocation() + ": coins " + Ledger.Wallet.coins(bot) + " pack food " + bot.getInventory().getAmount(379) + " bank food " + bot.getBank().getAmount(379) + ", state " + r.state + ", dest " + r.dest + ", foodTrip " + r.foodTrip); }
                    if (t == 84) {
                        boolean under = bot.getLocation().getY() > 6400; // batch 21 F: out of food underground, the runner fetches it from the bank above (batch 20 off-grid pre-flight)
                        log("RECOVER RESULT at " + bot.getLocation() + " (underground=" + under + "), paid " + (coins0 - Ledger.Wallet.coins(bot)) + ", state " + r.state + ", dest " + r.dest + ", attackers " + r.attackers().size() + ", combat " + bot.getProperties().getCurrentCombatLevel() + ", way out " + (r.emergencies >= 2 ? "re-flee (still in combat from part 1: a second emergency teleport)" : "food trip") + " (expect underground=false) -> " + (under ? "FAIL" : "PASS"));
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); return true; }
                return false;
            }
        });
    }
}
