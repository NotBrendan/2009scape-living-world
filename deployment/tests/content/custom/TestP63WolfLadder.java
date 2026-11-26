package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/**
 * Trial only, never shipped (P63). -Dtest.p63=true -Dquest.wolfWhistle=true: bots at stage 10 start Wolf Whistle at the
 * tiles the live failures logged ("ladder 28572 clicked 5 times, no cutscene" at 2926,3444 / 2925,3443), some with a
 * stale movement or interaction lock (InteractPlugin drops a walk-option click silently while either is set).
 * PASS per bot = stage 20 reached (or the cutscene started) before the runner gives up. Cap -Dtest.p63.ticks (900).
 */
public final class TestP63WolfLadder implements StartupListener {
    static final String T = "[TestP63WolfLadder] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p63")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static int stage(Player p) { return p.getQuestRepository().getStage(QuestWolfWhistle.Q); }

    static void go() {
        QuestScript ws = QuestBook.SCRIPTS.get(QuestWolfWhistle.SLUG);
        if (ws == null) { System.out.println(T + "RESULT FAIL no script"); System.out.println(T + "DONE"); return; }
        try {
            Scenery s = RegionManager.getObject(0, 2925, 3442, QuestWolfWhistle.LADDER_UP);
            System.out.println(T + "ladder " + (s == null ? "none" : s.getId() + " at " + s.getLocation() + " type " + s.getType() + " rot " + s.getRotation()));
        } catch (Throwable x) { System.out.println(T + "ladder lookup " + x); }
        // tile, lock: 0 none, 1 movement, 2 interaction
        final int[][] cases = {{2926, 3444, 0}, {2925, 3443, 0}, {2926, 3444, 1}, {2925, 3443, 2}};
        final int n = cases.length;
        final Player[] bots = new Player[n];
        for (int i = 0; i < n; i++) {
            AIPlayer b = new AIPlayer(Location.create(cases[i][0], cases[i][1], 0));
            try { b.getInventory().clear(); b.getBank().clear(); b.getEquipment().clear(); } catch (Throwable ignored) { }
            SmartAdventurers.finishTutorial(b);
            QuestBook.q(b).test = true;
            Ledger.Wallet.give(b, 500);
            b.getQuestRepository().setStageNonmonotonic(b.getQuestRepository().getQuest(QuestWolfWhistle.Q), 10);
            if (cases[i][2] == 1) b.getLocks().lockMovement(100000);
            if (cases[i][2] == 2) b.getLocks().lockInteractions(100000);
            bots[i] = b;
            System.out.println(T + "bot " + i + " " + b.getUsername() + " at " + b.getLocation() + " stage " + stage(b) + " lock " + cases[i][2]);
            QuestRunner.start(b, ws, 0, true);
        }
        final long cap = Integer.getInteger("test.p63.ticks", 900);
        GameWorld.getPulser().submit(new Pulse(1) {
            long ticks; final String[] res = new String[n];
            @Override public boolean pulse() {
                ticks++;
                boolean all = true;
                for (int i = 0; i < n; i++) {
                    if (res[i] != null) continue;
                    Player p = bots[i];
                    if (stage(p) >= 20 || QuestWolfWhistle.inScene(p)) res[i] = "PASS tick " + ticks + " stage " + stage(p) + " scene " + QuestWolfWhistle.inScene(p);
                    else if (!QuestRunner.active(p) || ticks >= cap) res[i] = "FAIL active " + QuestRunner.active(p) + " stage " + stage(p) + " at " + p.getLocation() + " tick " + ticks;
                    if (res[i] != null) System.out.println(T + "bot " + i + " lock " + cases[i][2] + " " + res[i]);
                    else all = false;
                }
                if (!all) return false;
                boolean pass = true; StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) { pass &= res[i].startsWith("PASS"); sb.append(" | ").append(i).append(" lock ").append(cases[i][2]).append(' ').append(res[i], 0, Math.min(14, res[i].length())); }
                System.out.println(T + "RESULT " + (pass ? "PASS" : "FAIL") + " bots " + n + sb);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
