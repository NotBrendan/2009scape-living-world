package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.path.Path;
import core.game.world.map.path.Pathfinder;

/**
 * Trial only (-Dtest.prq2=true): pathing review 10-23 Q-2, a broke bot in Taverley dungeon given a quest walk to the goblin
 * generals (2957,3511). Leg 1: the live failure tile (2903,9799). Leg 2: a tile that walks to the ladder up (2884,9797) but is
 * more than 10 tiles from it (found with the server's pathfinder from the ladder's foot).
 */
public final class TestPRQ2 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRQ2] " + s); }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prq2")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, ticks, leg; AIPlayer bot; final QuestSteps.Mover m = new QuestSteps.Mover(); String said = ""; Location leg2;
            @Override public boolean pulse() {
                try {
                    if (++t < 45) return false;
                    if (bot == null) { bot = new AIPlayer(Location.create(2884, 9798, 0)); return false; }
                    if (leg == 0) {
                        if (t < 50) return false;
                        for (int r = 22; r >= 12 && leg2 == null; r--) for (int dx = -r; dx <= r && leg2 == null; dx++) for (int dy = -r; dy <= r && leg2 == null; dy++) {
                            if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                            Location c = Location.create(2884 + dx, 9798 + dy, 0);
                            Path p = Pathfinder.find(bot, c);
                            if (p == null || !p.isSuccessful() || p.getPoints().isEmpty()) continue;
                            core.game.world.map.Point e = p.getPoints().peekLast();
                            if (e.getX() == c.getX() && e.getY() == c.getY() && p.getPoints().size() <= r + 12) leg2 = c;
                        }
                        log("leg 2 start (walks to the ladder, " + (leg2 == null ? "-" : (int) leg2.getDistance(Location.create(2884, 9797, 0)) + " tiles from it") + "): " + leg2);
                        begin(Location.create(2903, 9799, 0)); leg = 1; return false;
                    }
                    if (++ticks < 4) return false;
                    QuestSteps.St s = m.tick(bot);
                    if (!m.last.equals(said)) { said = m.last; log("   tick " + ticks + " at " + bot.getLocation() + ": " + m.last); }
                    boolean up = bot.getLocation().getY() < 6400;
                    if (s == QuestSteps.St.FAILED || up || ticks > 320) {
                        log("RESULT leg " + leg + ": " + (s == QuestSteps.St.FAILED ? "FAILED: " + m.last : up ? "out of the dungeon at " + bot.getLocation() + " (" + m.last + ")" : "TIMEOUT at " + bot.getLocation()) + " after " + ticks + " ticks");
                        if (leg == 2 || leg2 == null) { log("DONE"); return true; }
                        begin(leg2); leg = 2;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
            void begin(Location at) {
                bot.getPulseManager().clear(); bot.getWalkingQueue().reset(); bot.getProperties().setTeleportLocation(at);
                m.reset(Location.create(2957, 3511, 0), 3); ticks = 0; said = "";
                log("LEG " + (leg + 1) + ": bot at " + at + " with " + Ledger.Wallet.coins(bot) + " coins, quest walk to the goblin generals (2957,3511)");
            }
        });
    }
}
