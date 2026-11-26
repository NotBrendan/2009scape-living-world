package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.interaction.Option;
import core.game.node.Node;
import core.game.node.entity.Entity;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.zone.MapZone;
import core.game.world.map.zone.ZoneBorders;
import core.game.world.map.zone.ZoneBuilder;

/**
 * Trial only, never shipped (P67). -Dtest.p67=true -Dquest.wolfWhistle=true. Live train-21 failures, all at once:
 *  - crowd: 6 stage-10 bots start the quest in Pikkupstix's house on the same tick;
 *  - drop: 2 stage-10 bots whose house-ladder clicks are swallowed with no lock held (a test zone answers interact() true for
 *    them only), as on live: "ladder 28572 clicked 5 times, no cutscene", P63's stale-lock check silent;
 *  - resume: a stage-10 and a stage-40 bot that resume in Lumbridge (live: "nobody to talk to (ids [6971]) within 20 of
 *    [3222, 3218, 0]" at step 17).
 * PASS per bot: stage 10 = stage 20 or the cutscene; stage 40 = in the cellar or the pouches infused. Cap -Dtest.p67.ticks (2400).
 */
public final class TestP67WolfCrowd implements StartupListener {
    static final String T = "[TestP67WolfCrowd] ";
    static final String DROP = "p67-drop";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p67")) return;
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
        MapZone zone = new MapZone("p67 ladder drop", true) {
            @Override public void configure() { register(new ZoneBorders(2918, 3436, 2934, 3452)); }
            @Override public boolean interact(Entity e, Node target, Option option) {
                return e instanceof Player && e.getAttribute(DROP, false) && target != null && target.getId() == QuestWolfWhistle.LADDER_UP;
            }
        };
        ZoneBuilder.configure(zone);
        // x, y, stage, kind (0 crowd, 1 drop, 2 resume)
        final int[][] cases = {
            {2926, 3444, 10, 0}, {2925, 3443, 10, 0}, {2927, 3443, 10, 0}, {2926, 3445, 10, 0}, {2924, 3444, 10, 0}, {2927, 3445, 10, 0},
            {2926, 3444, 10, 1}, {2925, 3443, 10, 1},
            {3222, 3218, 10, 2}, {3224, 3218, 40, 2}};
        final int n = cases.length;
        final Player[] bots = new Player[n];
        for (int i = 0; i < n; i++) {
            AIPlayer b = new AIPlayer(Location.create(cases[i][0], cases[i][1], 0));
            try { b.getInventory().clear(); b.getBank().clear(); b.getEquipment().clear(); } catch (Throwable ignored) { }
            SmartAdventurers.finishTutorial(b);
            QuestBook.q(b).test = true;
            Ledger.Wallet.give(b, 500);
            b.getQuestRepository().setStageNonmonotonic(b.getQuestRepository().getQuest(QuestWolfWhistle.Q), cases[i][2]);
            if (cases[i][3] == 1) b.setAttribute(DROP, true);
            bots[i] = b;
        }
        for (int i = 0; i < n; i++) { // the same tick: the crowd
            QuestRunner.start(bots[i], ws, 0, true);
            System.out.println(T + "bot " + i + " " + bots[i].getUsername() + " at " + bots[i].getLocation() + " stage " + stage(bots[i]) + " kind " + cases[i][3]);
        }
        final long cap = Integer.getInteger("test.p67.ticks", 2400);
        GameWorld.getPulser().submit(new Pulse(1) {
            long ticks; final String[] res = new String[n];
            @Override public boolean pulse() {
                ticks++;
                boolean all = true;
                for (int i = 0; i < n; i++) {
                    if (res[i] != null) continue;
                    Player p = bots[i];
                    boolean ok = cases[i][2] == 10 ? stage(p) >= 20 || QuestWolfWhistle.inScene(p) : QuestWolfWhistle.inCellar(p) || QuestWolfWhistle.infused(p) || stage(p) >= 50;
                    if (ok) res[i] = "PASS tick " + ticks + " stage " + stage(p);
                    else if (!QuestRunner.active(p) || ticks >= cap) res[i] = "FAIL active " + QuestRunner.active(p) + " stage " + stage(p) + " at " + p.getLocation() + " tick " + ticks;
                    if (res[i] != null) System.out.println(T + "bot " + i + " kind " + cases[i][3] + " " + res[i]);
                    else all = false;
                }
                if (!all) return false;
                int[] pass = new int[3], tot = new int[3];
                for (int i = 0; i < n; i++) { tot[cases[i][3]]++; if (res[i].startsWith("PASS")) pass[cases[i][3]]++; }
                boolean ok = pass[0] == tot[0] && pass[1] == tot[1] && pass[2] == tot[2];
                System.out.println(T + "RESULT " + (ok ? "PASS" : "FAIL") + " crowd " + pass[0] + "/" + tot[0] + " drop " + pass[1] + "/" + tot[1] + " resume " + pass[2] + "/" + tot[2]);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
