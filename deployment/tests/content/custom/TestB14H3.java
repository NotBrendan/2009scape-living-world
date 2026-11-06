package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Trial only (-Dtest.b14h3=true): rec-review 10-23 batch14 item3. Asks a bot "are you a bot?" (and two other phrasings)
 * 400 times each through BotChat.Brain.answer and lists every distinct reply; a reply claiming to be a person
 * ("That's a person") must be gone, the other four kept.
 */
public final class TestB14H3 implements StartupListener {
    static void log(String s) { System.out.println("[TestB14H3] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b14h3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 150) return false;
                try {
                    AIPlayer b = TestCR1.bot(Location.create(3222, 3218, 0), 10, 1, 1, 1);
                    AIPlayer asker = TestA1.human(Location.create(3223, 3218, 0), 900300);
                    BotChat.Persona who = new BotChat.Persona(BotChat.Persona.Style.values()[0]); // the plain style: no verbal tics on top
                    Set<String> seen = new TreeSet<>(); int person = 0;
                    for (String q : new String[]{"are you a bot?", "r u a bot", "bot?"}) for (int i = 0; i < 400; i++) {
                        List<String> out = BotChat.Brain.answer(b, who, asker, q, true);
                        if (out == null) continue;
                        for (String s : out) { seen.add(s); if (s.contains("That's a person")) person++; }
                    }
                    for (String s : seen) log("reply: " + s);
                    log("RESULT distinct replies " + seen.size() + ", person-claim replies " + person + " (of 1200 asks)");
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
