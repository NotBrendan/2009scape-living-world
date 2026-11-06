package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.auq20=true): audit 10-23 Q-20. A bot on Sheep Shearer is killed twice; after each death the brain's
 * resume() is asked to carry on. Right: the first death resumes, the second parks the quest (no third attempt).
 */
public final class TestAUQ20 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUQ20] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.auq20")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, deaths, next = 200; AIPlayer b; SmartAdventurers.Brain br; QuestScript s; StringBuilder res = new StringBuilder();
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 200) {
                        s = QuestBook.SCRIPTS.get("sheep");
                        b = TestCR1.bot(null, 10, 1, 1, 1);
                        br = TestCR1.rig(b, false); br.shiftEnd = System.currentTimeMillis() + 3_600_000L;
                        QuestRunner.start(b, s, 0, false);
                        log("bot " + b.getUsername() + " started " + s.slug + ": active " + QuestRunner.active(b));
                        next = 215;
                    }
                    if (t == next && deaths < 3) {
                        if (QuestRunner.active(b)) { TestCR1.killBy(b, TestCR1.npcAt(b)); deaths++; log("death " + deaths + " (quest running)"); next = t + 30; }
                        else { log("no quest running at death " + (deaths + 1) + ": stop"); deaths = 3; next = t + 1; }
                    }
                    if (t == next - 10 && deaths > 0 && deaths <= 3) {
                        boolean again = QuestBook.resume(br);
                        res.append("after death ").append(deaths).append(": resume ").append(again ? "RESTARTED" : "no").append("; ");
                        log("after death " + deaths + ": quest still running " + QuestRunner.active(b) + ", resume() -> " + again + ", parked " + QuestBook.parked(b, s));
                    }
                    if (deaths >= 3 && t >= next || t > 600) {
                        log("RESULT " + res + "parked now " + QuestBook.parked(b, s));
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
