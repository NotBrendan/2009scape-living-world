package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import java.util.HashMap;
import java.util.Map;

/**
 * Trial only, never shipped (P83). -Dtest.p83=true -Dquest.wolfWhistle=true -Dquest.arravFix=true. The live train-33 failures:
 * Wolf Whistle (to stage 100 each):
 *  w0 stage 30, two wolf bones, movement + interaction lock left on (step "give Pikkupstix the wolf bones": never opened);
 *  w1 stage 40, every supply and the key in the BANK (step "infuse": short of the pouch supplies 0/0/0/0);
 *  w2 stage 40, only charms and bones in the bank, 1,000 gp (shards and pouches from Pikkupstix's shop);
 *  w3 stage 50, the pouch and Howl scrolls in the BANK (step "climb the ladder with the spirit wolf pouch");
 *  w4 stage 50, no pouch or scroll anywhere, supplies in the bank (back to the cellar, then the ladder).
 * Shield of Arrav (-Dquest.arravFix), Arrav pool emptied for the trial (restored at the end):
 *  a0 Phoenix at stage 70 with two Phoenix halves, nobody else: set aside without a FAILED, its spare in the pool, mayStart false;
 *  a1 Black Arm at stage 70 with two halves, started after a0 is set aside: takes a0's spare from the pool, finishes (stage 100);
 *  a0 again once a1 is done: mayStart true, takes a1's spare, finishes.
 * PASS = all of it. Cap -Dtest.p83.ticks (6000).
 */
public final class TestP83Quests implements StartupListener {
    static final String T = "[TestP83Quests] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p83")) return;
        System.out.println(T + "armed wolfWhistle=" + QuestWolfWhistle.ON + " arravFix=" + QuestShieldOfArrav.FIX);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static AIPlayer bot(Location at, content.data.Quests q, int stage, long coins) {
        AIPlayer b = new AIPlayer(at);
        try { b.getInventory().clear(); b.getBank().clear(); b.getEquipment().clear(); } catch (Throwable ignored) { }
        SmartAdventurers.finishTutorial(b);
        QuestBook.q(b).test = true;
        Ledger.Wallet.give(b, coins);
        b.getQuestRepository().setStageNonmonotonic(b.getQuestRepository().getQuest(q), stage);
        return b;
    }

    static int ws(Player p) { return p.getQuestRepository().getStage(QuestWolfWhistle.Q); }
    static int as(Player p) { return p.getQuestRepository().getStage(QuestShieldOfArrav.Q); }

    static void go() {
        final QuestScript wolf = QuestBook.SCRIPTS.get(QuestWolfWhistle.SLUG), arrav = QuestBook.SCRIPTS.get(QuestShieldOfArrav.SLUG);
        if (wolf == null || arrav == null) { System.out.println(T + "RESULT FAIL no script wolf " + (wolf != null) + " arrav " + (arrav != null)); System.out.println(T + "DONE"); return; }
        final int BONES = QuestWolfWhistle.BONES, GOLD = QuestWolfWhistle.GOLD, EMPTY = QuestWolfWhistle.EMPTY, SHARDS = QuestWolfWhistle.SHARDS;
        Location pk = QuestWolfWhistle.PIKK_AT;
        final AIPlayer[] w = new AIPlayer[5];
        w[0] = bot(pk.transform(0, 1, 0), QuestWolfWhistle.Q, 30, 500);
        w[0].getInventory().add(new Item(BONES, 2));
        w[0].getLocks().lockMovement(100000); w[0].getLocks().lockInteractions(100000);
        w[1] = bot(pk.transform(-1, 0, 0), QuestWolfWhistle.Q, 40, 500);
        for (Item it : new Item[]{new Item(BONES, 2), new Item(GOLD, 2), new Item(EMPTY, 2), new Item(SHARDS, 14), new Item(QuestWolfWhistle.KEY, 1)}) w[1].getBank().add(it);
        w[2] = bot(pk.transform(1, 0, 0), QuestWolfWhistle.Q, 40, 1000);
        w[2].getBank().add(new Item(BONES, 2)); w[2].getBank().add(new Item(GOLD, 2)); w[2].getBank().add(new Item(QuestWolfWhistle.KEY, 1));
        w[3] = bot(pk.transform(0, -1, 0), QuestWolfWhistle.Q, 50, 500);
        w[3].getBank().add(new Item(QuestWolfWhistle.POUCH, 1)); w[3].getBank().add(new Item(QuestWolfWhistle.HOWL, 10));
        w[4] = bot(pk.transform(1, 1, 0), QuestWolfWhistle.Q, 50, 500);
        for (Item it : new Item[]{new Item(BONES, 2), new Item(GOLD, 2), new Item(EMPTY, 2), new Item(SHARDS, 14)}) w[4].getBank().add(it);
        for (int i = 0; i < w.length; i++) {
            QuestRunner.start(w[i], wolf, 0, true);
            System.out.println(T + "wolf " + i + " " + w[i].getUsername() + " stage " + ws(w[i]));
        }

        // Arrav: an empty pool for the trial
        final Map<Integer, Integer> savedPool = new HashMap<>();
        QuestShieldOfArrav.Pool.load();
        synchronized (QuestShieldOfArrav.Pool.class) { savedPool.putAll(QuestShieldOfArrav.Pool.N); QuestShieldOfArrav.Pool.N.clear(); }
        final AIPlayer a0 = bot(QuestShieldOfArrav.CURATOR_AT, QuestShieldOfArrav.Q, 70, 500);
        a0.setAttribute("/save:phoenix-gang", true);
        a0.getInventory().add(new Item(QuestShieldOfArrav.PHOENIX_HALF, 2));
        final AIPlayer a1 = bot(QuestShieldOfArrav.CURATOR_AT.transform(2, 0, 0), QuestShieldOfArrav.Q, 70, 500);
        a1.setAttribute("/save:black-arm-gang", true);
        a1.getInventory().add(new Item(QuestShieldOfArrav.BLACKARM_HALF, 2));
        System.out.println(T + "arrav a0 " + a0.getUsername() + " phoenix " + QuestShieldOfArrav.phoenix(a0) + ", a1 " + a1.getUsername() + " phoenix " + QuestShieldOfArrav.phoenix(a1));
        QuestRunner.start(a0, arrav, 0, true);

        final long cap = Integer.getInteger("test.p83.ticks", 6000);
        GameWorld.getPulser().submit(new Pulse(5) {
            long ticks; final String[] res = new String[w.length]; String ar; int aPhase; long aT;
            boolean gateBefore, gateAfter; String whyBefore;
            @Override public boolean pulse() {
                ticks += 5;
                boolean all = true;
                for (int i = 0; i < w.length; i++) {
                    if (res[i] != null) continue;
                    Player p = w[i];
                    boolean active = QuestRunner.active(p);
                    if (ws(p) >= 100 && !active) res[i] = "PASS tick " + ticks;
                    else if (!active || ticks >= cap) res[i] = "FAIL active " + active + " stage " + ws(p) + " at " + p.getLocation() + " tick " + ticks;
                    if (res[i] != null) System.out.println(T + "wolf " + i + " " + res[i]);
                    else all = false;
                }
                if (ar == null) {
                    all = false;
                    if (aPhase == 0 && !QuestRunner.active(a0)) { // a0 ended: must be set aside (stage 70, not abandoned), its spare in the pool
                        whyBefore = QuestShieldOfArrav.whyNot(a0); gateBefore = whyBefore == null;
                        int pool = QuestShieldOfArrav.Pool.count(QuestShieldOfArrav.PHOENIX_HALF);
                        boolean aside = as(a0) == 70 && !QuestBook.parked(a0, arrav) && pool == 1;
                        System.out.println(T + "a0 ended tick " + ticks + " stage " + as(a0) + " parked " + QuestBook.parked(a0, arrav) + " pool phoenix " + pool + " whyNot " + whyBefore);
                        if (!aside || gateBefore) { ar = "FAIL a0 not set aside cleanly"; }
                        else { aPhase = 1; aT = ticks; QuestRunner.start(a1, arrav, 0, true); }
                    } else if (aPhase == 1 && !QuestRunner.active(a1)) {
                        System.out.println(T + "a1 ended tick " + ticks + " stage " + as(a1) + " pool blackarm " + QuestShieldOfArrav.Pool.count(QuestShieldOfArrav.BLACKARM_HALF));
                        whyBefore = QuestShieldOfArrav.whyNot(a0); gateAfter = whyBefore == null;
                        if (as(a1) < 100 || !gateAfter) ar = "FAIL a1 stage " + as(a1) + " a0 gate " + whyBefore;
                        else { aPhase = 2; QuestRunner.start(a0, arrav, 0, true); }
                    } else if (aPhase == 2 && !QuestRunner.active(a0)) {
                        ar = as(a0) >= 100 ? "PASS a0 " + as(a0) + " a1 " + as(a1) + " tick " + ticks : "FAIL a0 second run stage " + as(a0);
                    } else if (ticks >= cap) ar = "FAIL cap phase " + aPhase + " a0 " + as(a0) + " a1 " + as(a1);
                    if (ar != null) { System.out.println(T + "arrav " + ar); all = all && true; }
                }
                if (!all && (ar == null || ticks < cap)) { boolean wDone = true; for (String r : res) wDone &= r != null; if (!(wDone && ar != null)) return false; }
                synchronized (QuestShieldOfArrav.Pool.class) { QuestShieldOfArrav.Pool.N.clear(); QuestShieldOfArrav.Pool.N.putAll(savedPool); QuestShieldOfArrav.Pool.save(); }
                int pass = 0; StringBuilder sb = new StringBuilder();
                for (int i = 0; i < w.length; i++) { if (res[i].startsWith("PASS")) pass++; sb.append(" w").append(i).append(res[i].startsWith("PASS") ? " PASS" : " FAIL"); }
                boolean ok = pass == w.length && ar.startsWith("PASS");
                System.out.println(T + "RESULT " + (ok ? "PASS" : "FAIL") + " wolf " + pass + "/" + w.length + sb + " | arrav " + ar);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
