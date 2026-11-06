package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * Trial only (-Dtest.b15b1=true, with -Dbotchat.debug=true): batch 15 item1, quest presence. Two bots start a quest a tick
 * apart (QuestRunner.start, as think() does): the gossip pool should get one start line (the second is inside the rate
 * limit). Then 36 finishes (QuestRunner.complete, test off) by 12 bots: the [Say] lines show the finish-line variety.
 */
public final class TestB15B1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15B1] " + s); }
    static int newsWith(String a, String b) {
        int n = 0;
        synchronized (BotChat.class) { for (String s : BotChat.NEWS) if (s.contains(a) && s.contains(b)) n++; }
        return n;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15b1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; List<AIPlayer> bots = new ArrayList<>(); QuestScript q1, q2; QuestRunner r1, r2;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        QuestBook.registerWave1();
                        java.util.Iterator<QuestScript> it = QuestBook.SCRIPTS.values().iterator(); q1 = it.next(); q2 = it.next();
                        for (int i = 0; i < 12; i++) { AIPlayer b = TestCR1.bot(Location.create(3210 + i % 6, 3240 + i / 6, 0), 30, 20, 20, 20); TestCR1.rig(b, false); bots.add(b); }
                        log("12 bots; quests " + q1.display + ", " + q2.display);
                    }
                    if (t == 155) { r1 = QuestRunner.start(bots.get(0), q1, 0, false); log("started " + bots.get(0).getUsername() + " on " + q1.display); }
                    if (t == 156) { r2 = QuestRunner.start(bots.get(1), q2, 0, false); log("started " + bots.get(1).getUsername() + " on " + q2.display); }
                    if (t == 158) {
                        int a = newsWith(bots.get(0).getUsername(), q1.display), b = newsWith(bots.get(1).getUsername(), q2.display);
                        log("RESULT start news: first start " + a + " line(s), second start a tick later " + b + " line(s)");
                        synchronized (BotChat.class) { for (String s : BotChat.NEWS) if (s.contains(bots.get(0).getUsername()) || s.contains(bots.get(1).getUsername())) log("  news: " + s); }
                        r1.stopHere(false); r2.stopHere(false);
                    }
                    if (t == 162) {
                        StringBuilder names = new StringBuilder();
                        for (int i = 0; i < 36; i++) {
                            AIPlayer b = bots.get(i % 12);
                            QuestRunner r = new QuestRunner(b, q1, 0, false);
                            r.snapshot(); r.complete();
                            if (i < 12) names.append(b.getUsername()).append(' ');
                        }
                        log("RESULT 36 finishes done by " + names.toString().trim() + " (finish chat lines are the [Say] lines)");
                    }
                    if (t == 200) { log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
