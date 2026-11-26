package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (P51, pattern of TestP50Trial). -Dtest.p51=true: four bots side by side, each started at step 0
 * the way live bots resume.
 * arrav: Shield of Arrav stage 70, "blackarm-mission" set (so the Black Arm steps run) but no gang, in Katrine's room
 * (3181,3387, where live bots clicked door 2399 from). PASS = through the door (or upstairs, or the shield).
 * msgBank: Romeo & Juliet stage 20 by Romeo, Juliet's message (755) in the bank only. PASS = stage 30.
 * msgGone: stage 20 by Romeo, no message anywhere (Juliet must hand out a new one). PASS = stage 30.
 * potBank: stage 60 by Juliet upstairs, the cadava potion (756) in the bank only. PASS = stage 70.
 * Without the flags all four are expected to FAIL; with -Dquest.arravFix / -Dquest.romeoFix all should PASS.
 * Cap: -Dtest.p51.min (15).
 */
public final class TestP51Trial implements StartupListener {
    static final String T = "[TestP51Trial] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p51")) return;
        System.out.println(T + "armed arravFix=" + QuestShieldOfArrav.FIX + " romeoFix=" + QuestRomeoAndJuliet.FIX);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static AIPlayer bot(Location at, content.data.Quests q, int stage) {
        AIPlayer b = new AIPlayer(at);
        try { b.getInventory().clear(); b.getBank().clear(); b.getEquipment().clear(); } catch (Throwable ignored) { }
        SmartAdventurers.finishTutorial(b);
        QuestBook.q(b).test = true;
        Ledger.Wallet.give(b, 500);
        b.getQuestRepository().setStageNonmonotonic(b.getQuestRepository().getQuest(q), stage);
        return b;
    }

    static int stage(Player p, content.data.Quests q) { return p.getQuestRepository().getStage(q); }

    static void go() {
        QuestScript as = QuestBook.SCRIPTS.get(QuestShieldOfArrav.SLUG), rs = QuestBook.SCRIPTS.get("romeojuliet");
        if (as == null || rs == null) { System.out.println(T + "RESULT FAIL no script " + (as == null) + " " + (rs == null)); System.out.println(T + "DONE"); return; }

        final AIPlayer a = bot(Location.create(3181, 3387, 0), QuestShieldOfArrav.Q, 70);
        a.setAttribute("/save:blackarm-mission", true);
        System.out.println(T + "arrav " + a.getUsername() + " phoenix " + QuestShieldOfArrav.phoenix(a) + " lostGang " + QuestShieldOfArrav.lostGang(a));
        QuestRunner.start(a, as, 0, true);

        final AIPlayer mb = bot(QuestRomeoAndJuliet.ROMEO_AT, QuestRomeoAndJuliet.Q, 20);
        mb.getBank().add(new Item(QuestRomeoAndJuliet.MESSAGE, 1));
        QuestRunner.start(mb, rs, 0, true);

        final AIPlayer mg = bot(QuestRomeoAndJuliet.ROMEO_AT, QuestRomeoAndJuliet.Q, 20);
        QuestRunner.start(mg, rs, 0, true);

        final AIPlayer pb = bot(QuestRomeoAndJuliet.JULIET_AT, QuestRomeoAndJuliet.Q, 60);
        pb.getBank().add(new Item(QuestRomeoAndJuliet.POTION, 1));
        QuestRunner.start(pb, rs, 0, true);

        final Player[] bots = {a, mb, mg, pb};
        final String[] names = {"arrav", "msgBank", "msgGone", "potBank"};
        final long cap = Integer.getInteger("test.p51.min", 15) * 100L;
        GameWorld.getPulser().submit(new Pulse(5) {
            long ticks; final String[] res = new String[4];
            boolean passed(int i) {
                Player p = bots[i];
                if (i == 0) return QuestShieldOfArrav.behindBlackArmDoor(p) || QuestShieldOfArrav.upHouse(p) || QuestShieldOfArrav.blackShieldOrLater(p);
                return stage(p, QuestRomeoAndJuliet.Q) >= (i == 3 ? 70 : 30);
            }
            @Override public boolean pulse() {
                ticks += 5;
                boolean all = true;
                for (int i = 0; i < 4; i++) {
                    if (res[i] != null) continue;
                    Player p = bots[i];
                    if (passed(i)) res[i] = "PASS tick " + ticks + " " + p.getLocation();
                    else if (!QuestRunner.active(p) || ticks >= cap) res[i] = "FAIL active " + QuestRunner.active(p) + " at " + p.getLocation() + " tick " + ticks
                            + (i == 0 ? " gang " + QuestScript.attr(p, "black-arm-gang") : " stage " + stage(p, QuestRomeoAndJuliet.Q));
                    if (res[i] != null) System.out.println(T + names[i] + " " + res[i]);
                    else all = false;
                }
                if (!all) return false;
                boolean pass = true;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) { pass &= res[i].startsWith("PASS"); sb.append(" | ").append(names[i]).append(' ').append(res[i]); }
                System.out.println(T + "RESULT " + (pass ? "PASS" : "FAIL") + " arravFix=" + QuestShieldOfArrav.FIX + " romeoFix=" + QuestRomeoAndJuliet.FIX + sb);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
