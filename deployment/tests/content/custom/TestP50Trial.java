package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only, never shipped (P50, pattern of TestP47GoblinTrial). -Dtest.p50=true: two bots, run side by side.
 * Ernest: stage 20, piranhas already killed, standing in the north room of Draynor Manor (where live bots start step 12),
 * started at "get the pressure gauge". PASS = a pressure gauge (271) in the pack.
 * Pirates: stage 10, "stashed-rum" set but no GlobalData.luthasTask, ten bananas in the pack, by the crate, started at
 * "walk to Luthas" (the live state: steps 7-10 skipped, Fill does nothing). PASS = 10 bananas in the crate (or paid).
 * Without the flags both are expected to FAIL; with -Dquest.ernestFix / -Dquest.piratesFix both should PASS.
 * Cap: -Dtest.p50.min (15).
 */
public final class TestP50Trial implements StartupListener {
    static final String T = "[TestP50Trial] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p50")) return;
        System.out.println(T + "armed ernestFix=" + QuestErnestTheChicken.FIX + " piratesFix=" + QuestPiratesTreasure.FIX);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static int index(QuestScript s, String name) {
        for (int i = 0; i < s.steps.size(); i++) if (s.steps.get(i).name.equals(name)) return i;
        return -1;
    }

    static AIPlayer bot(core.game.world.map.Location at) {
        AIPlayer b = new AIPlayer(at);
        try { b.getInventory().clear(); b.getBank().clear(); b.getEquipment().clear(); } catch (Throwable ignored) { }
        SmartAdventurers.finishTutorial(b);
        QuestBook.q(b).test = true;
        Ledger.Wallet.give(b, 500);
        return b;
    }

    static void go() {
        QuestScript es = QuestBook.SCRIPTS.get("ernest"), ps = QuestBook.SCRIPTS.get("piratestreasure");
        if (es == null || ps == null) { System.out.println(T + "RESULT FAIL no script " + (es == null) + " " + (ps == null)); System.out.println(T + "DONE"); return; }
        int ei = index(es, "get the pressure gauge"), pi = index(ps, "walk to Luthas");
        if (ei < 0 || pi < 0) { System.out.println(T + "RESULT FAIL no step " + ei + " " + pi); System.out.println(T + "DONE"); return; }

        final AIPlayer e = bot(QuestErnestTheChicken.NORTH_IN);
        e.getQuestRepository().setStageNonmonotonic(e.getQuestRepository().getQuest(QuestErnestTheChicken.Q), 20);
        e.setAttribute("/save:piranhas-killed", true);
        System.out.println(T + "ernest " + e.getUsername() + " at " + e.getLocation() + " step " + ei);
        QuestRunner.start(e, es, ei, true);

        final AIPlayer p = bot(core.game.world.map.Location.create(2942, 3151, 0)); // where live bots clicked from
        p.getQuestRepository().setStageNonmonotonic(p.getQuestRepository().getQuest(QuestPiratesTreasure.Q), 10);
        p.setAttribute("/save:stashed-rum", true);
        p.getSavedData().getGlobalData().setLuthasTask(false);
        p.getInventory().add(new Item(QuestPiratesTreasure.BANANA, 10));
        System.out.println(T + "pirates " + p.getUsername() + " at " + p.getLocation() + " step " + pi + " task " + QuestPiratesTreasure.luthasTask(p) + " hired " + QuestPiratesTreasure.hired(p));
        QuestRunner.start(p, ps, pi, true);

        final long cap = Integer.getInteger("test.p50.min", 15) * 100L;
        GameWorld.getPulser().submit(new Pulse(5) {
            long ticks; String eRes, pRes;
            @Override public boolean pulse() {
                ticks += 5;
                if (eRes == null) {
                    if (e.getInventory().getAmount(QuestErnestTheChicken.GAUGE) > 0) eRes = "PASS gauge at tick " + ticks + " " + e.getLocation();
                    else if (!QuestRunner.active(e) || ticks >= cap) eRes = "FAIL no gauge, active " + QuestRunner.active(e) + " at " + e.getLocation() + " tick " + ticks;
                    if (eRes != null) System.out.println(T + "ernest " + eRes);
                }
                if (pRes == null) {
                    if (QuestPiratesTreasure.crateFull(p)) pRes = "PASS crate " + QuestPiratesTreasure.inCrate(p) + " at tick " + ticks;
                    else if (!QuestRunner.active(p) || ticks >= cap) pRes = "FAIL crate " + QuestPiratesTreasure.inCrate(p) + " task " + QuestPiratesTreasure.luthasTask(p) + " pack " + p.getInventory().getAmount(QuestPiratesTreasure.BANANA) + " active " + QuestRunner.active(p) + " tick " + ticks;
                    if (pRes != null) System.out.println(T + "pirates " + pRes);
                }
                if (eRes == null || pRes == null) return false;
                boolean pass = eRes.startsWith("PASS") && pRes.startsWith("PASS");
                System.out.println(T + "RESULT " + (pass ? "PASS" : "FAIL") + " ernestFix=" + QuestErnestTheChicken.FIX + " piratesFix=" + QuestPiratesTreasure.FIX + " ernest " + eRes + " | pirates " + pRes);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
