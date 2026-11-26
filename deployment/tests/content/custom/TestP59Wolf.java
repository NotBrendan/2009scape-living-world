package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only, never shipped (P59). -Dtest.p59unit=true: Wolf Whistle registration follows -Dquest.wolfWhistle exactly
 * (unset / =false: no script, byte-same quest list; =true: script "wolfwhistle" with its steps and checkpoints).
 * Expected: -Dtest.p59unit.want=on|off.
 */
public final class TestP59Wolf implements StartupListener {
    static final String T = "[TestP59Wolf] ";
    static int pass, fail;

    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println(T + (ok ? "PASS " : "FAIL ") + name); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p59unit")) return;
        GameWorld.getPulser().submit(new Pulse(20) {
            @Override public boolean pulse() {
                try { runChecks(); } catch (Throwable x) { System.out.println(T + "FAIL setup " + x); x.printStackTrace(System.out); fail++; }
                System.out.println(T + "RESULT " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail) + " flag=" + System.getProperty("quest.wolfWhistle"));
                System.out.println(T + "DONE");
                return true;
            }
        });
    }

    static void runChecks() {
        boolean want = "on".equals(System.getProperty("test.p59unit.want", "off"));
        check("ON constant matches the flag (" + QuestWolfWhistle.ON + ")", QuestWolfWhistle.ON == want);
        QuestBook.registerWave1();
        QuestScript s = QuestBook.SCRIPTS.get(QuestWolfWhistle.SLUG);
        check("script registered only with the flag (registered=" + (s != null) + ")", (s != null) == want);
        check("17 live scripts unchanged (" + QuestBook.SCRIPTS.size() + ")", QuestBook.SCRIPTS.size() == (want ? 18 : 17));
        check("byQuest(WOLF_WHISTLE) follows the flag", (QuestBook.byQuest(content.data.Quests.WOLF_WHISTLE) != null) == want);
        QuestScript b = QuestWolfWhistle.build(); // builds without the flag too (no world access in build)
        check("build has 20+ steps (" + b.steps.size() + ")", b.steps.size() >= 20);
        int noDone = 0;
        for (QuestSteps.Step st : b.steps) if (st.done == null && !(st instanceof QuestSteps.WaitStage)) { noDone++; System.out.println(T + "  no checkpoint: " + st.name); }
        check("every step but the wait has a checkpoint (" + noDone + " without)", noDone == 0);
        check("header: start 2926,3443,0, 30 min, near 2, no levels, no prereqs", b.start.equals(QuestWolfWhistle.PIKK_AT) && b.estMinutes == 30 && b.maxNear == 2 && b.levels.length == 0 && b.prereqs.length == 0);
        check("exit step present", b.exit.size() == 1);
        check("only the body search is optional", countOptional(b) == 1);
        // P69 #1 (source): upLadder calls direct() once, then only waits for in-cutscene (DIRECT_WAIT_TICKS) before failing
        try {
            String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("test.p69.src", "deployment/addons-src/content/custom"), "QuestWolfWhistle.java")), java.nio.charset.StandardCharsets.UTF_8);
            int up = src.indexOf("static QuestSteps.Step upLadder("), end = src.indexOf("static QuestSteps.Step houseWalk(", up);
            String body = up > 0 && end > up ? src.substring(up, end) : "";
            int wait = body.indexOf("if (st[4] > 0) {"), call = body.indexOf("direct(p, s)");
            boolean once = wait > 0 && call > wait && body.indexOf("direct(p, s)", call + 1) < 0 && body.contains("else if (direct(p, s)) { st[1]++; st[4] = 1; return QuestSteps.St.RUNNING; }")
                    && body.contains("st[3] = 0; st[4] = 0; }") && QuestWolfWhistle.DIRECT_WAIT_TICKS >= 10;
            check("P69 #1 upLadder starts the cutscene directly at most once per step, then waits " + QuestWolfWhistle.DIRECT_WAIT_TICKS + " ticks", once);
        } catch (java.io.IOException x) { check("P69 source readable (-Dtest.p69.src): " + x, false); }
        check("trapdoor: only the form this bot sees counts (no world: false, no throw)", !QuestWolfWhistle.canClimbDown(new core.game.bots.AIPlayer(core.game.world.map.Location.create(3222, 3218, 0))));
    }

    static int countOptional(QuestScript b) { int n = 0; for (QuestSteps.Step st : b.steps) if (st.optional) n++; return n; }
}
