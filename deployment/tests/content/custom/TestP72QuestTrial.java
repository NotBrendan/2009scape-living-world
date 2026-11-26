package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (P72, pattern of TestP51Trial). -Dtest.p72=true: -Dp72.n (2) bots per quest, side by side.
 * pirates: stage 10, rum stashed, Luthas' job, crate empty, at the plantation, started at "get bananas for the crate"
 *   (live step 11 bananas 9/10 and step 24 Wydin's back door). PASS = quest complete; also logs "wydin" when the
 *   bot reached the back room or had the rum back.
 * arrav: stage 70, Black Arm member, in the hall east of the house door 24381 (3190,3384) after a helper opened it
 *   (live step 59 "no door at [3190, 3384, 0]"), started at step 0 as live bots resume. PASS = the Black Arm shield.
 * ghost: stage 30, amulet worn, north of the Wizards' Tower bridge, started at "get the ghost's skull" (live step 10
 *   "short of Skull": the 2147 climb fallback left bots at 3103,3162). PASS = quest complete.
 * Cap: -Dtest.p72.min (40).
 */
public final class TestP72QuestTrial implements StartupListener {
    static final String T = "[TestP72QuestTrial] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p72")) return;
        System.out.println(T + "armed piratesFix=" + QuestPiratesTreasure.FIX + " arravFix=" + QuestShieldOfArrav.FIX);
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
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { b.getSkills().setStaticLevel(sk, 30); b.getSkills().setLevel(sk, 30); }
        b.getQuestRepository().setStageNonmonotonic(b.getQuestRepository().getQuest(q), stage);
        return b;
    }
    static int stage(Player p, content.data.Quests q) { return p.getQuestRepository().getStage(q); }
    static int index(QuestScript s, String name) {
        for (int i = 0; i < s.steps.size(); i++) if (s.steps.get(i).name.equals(name)) return i;
        return -1;
    }

    static void go() {
        QuestScript ps = QuestBook.SCRIPTS.get("piratestreasure"), as = QuestBook.SCRIPTS.get(QuestShieldOfArrav.SLUG), gs = QuestBook.SCRIPTS.get("restlessghost");
        if (ps == null || as == null || gs == null) { System.out.println(T + "RESULT FAIL no script " + (ps == null) + " " + (as == null) + " " + (gs == null)); System.out.println(T + "DONE"); return; }
        int pi = index(ps, "get bananas for the crate"), gi = index(gs, "get the ghost's skull");
        if (pi < 0 || gi < 0) { System.out.println(T + "RESULT FAIL no step " + pi + " " + gi); System.out.println(T + "DONE"); return; }
        final int n = Math.max(1, Integer.getInteger("p72.n", 2));
        final int G = Boolean.getBoolean("p72.ali") ? 4 : 3; // p72.ali: a fourth group, Prince Ali stage 60 in the cell at 3121,3241 (live step 35)
        final Player[] bots = new Player[G * n];
        final String[] names = new String[G * n];
        QuestScript als = QuestBook.SCRIPTS.get("princeali");
        int ali = als == null ? -1 : index(als, "out of the cell");
        if (G == 4 && ali < 0) { System.out.println(T + "RESULT FAIL no princeali step"); System.out.println(T + "DONE"); return; }

        // arrav first: a helper opens the hall door the way a player's Open does, then the bots start in the hall
        Location hallDoor = QuestShieldOfArrav.HALL_DOOR;
        AIPlayer helper = new AIPlayer(Location.create(3191, 3384, 0));
        core.game.node.scenery.Scenery d = QuestSteps.findScenery(helper, hallDoor, 0, null, "door", "Open");
        System.out.println(T + "hall door before " + (d == null ? "none closed" : d.getId() + "@" + d.getLocation() + " rot " + d.getRotation()));
        if (d != null) { try { core.game.global.action.DoorActionHandler.handleDoor(helper, d); } catch (Throwable x) { System.out.println(T + "helper open " + x); } }
        core.game.node.scenery.Scenery d2 = QuestSteps.findScenery(helper, hallDoor, 0, null, "door", "Open");
        System.out.println(T + "hall door after helper " + (d2 == null ? "none closed (open)" : d2.getId() + "@" + d2.getLocation()));

        for (int k = 0; k < n; k++) {
            AIPlayer a = bot(Location.create(3192 + (k % 2), 3384 + (k / 2 % 2), 0), QuestShieldOfArrav.Q, 70);
            a.setAttribute("/save:blackarm-mission", true);
            a.setAttribute("/save:black-arm-gang", true);
            QuestRunner.start(a, as, 0, true);
            bots[n + k] = a; names[n + k] = "arrav" + k;
        }
        for (int k = 0; k < n; k++) {
            AIPlayer p = bot(QuestPiratesTreasure.PLANTATION.transform(k % 3, k / 3, 0), QuestPiratesTreasure.Q, 10);
            p.setAttribute("/save:stashed-rum", true);
            p.getSavedData().getGlobalData().setLuthasTask(true);
            QuestRunner.start(p, ps, pi, true);
            bots[k] = p; names[k] = "pirates" + k;
        }
        for (int k = 0; k < n; k++) {
            AIPlayer g = bot(Boolean.getBoolean("p72.ghostLive") ? Location.create(3102, 3161, 0) : Location.create(3113 + k % 3, 3178, 0), QuestRestlessGhost.Q, 30); // ghostLive: where live bots clicked 2147 from
            g.getEquipment().add(new Item(QuestRestlessGhost.AMULET, 1));
            QuestRunner.start(g, gs, gi, true);
            bots[2 * n + k] = g; names[2 * n + k] = "ghost" + k;
        }
        for (int k = 0; G == 4 && k < n; k++) {
            AIPlayer a = bot(Location.create(3121 + k % 2, 3241, 0), QuestPrinceAliRescue.Q, 60);
            QuestRunner.start(a, als, ali, true);
            bots[3 * n + k] = a; names[3 * n + k] = "ali" + k;
        }
        final boolean[] wydin = new boolean[n], skull = new boolean[n];
        final long cap = Integer.getInteger("test.p72.min", 40) * 100L;
        GameWorld.getPulser().submit(new Pulse(5) {
            long ticks; final String[] res = new String[G * n];
            boolean passed(int i) {
                Player p = bots[i];
                if (i < n) return QuestPiratesTreasure.done(p);
                if (i < 2 * n) return QuestShieldOfArrav.blackShieldOrLater(p);
                if (i < 3 * n) return stage(p, QuestRestlessGhost.Q) >= 100;
                return !QuestPrinceAliRescue.inCell(p);
            }
            @Override public boolean pulse() {
                ticks += 5;
                boolean all = true;
                for (int i = 0; i < G * n; i++) {
                    if (res[i] != null) continue;
                    Player p = bots[i];
                    if (i < n && !wydin[i] && (QuestPiratesTreasure.inBackRoom(p) || QuestPiratesTreasure.rumBack(p) && !QuestPiratesTreasure.onKaramja(p))) { wydin[i] = true; System.out.println(T + names[i] + " wydin tick " + ticks); }
                    if (i >= 2 * n && i < 3 * n && !skull[i - 2 * n] && p.getInventory().getAmount(QuestRestlessGhost.SKULL) > 0) { skull[i - 2 * n] = true; System.out.println(T + names[i] + " skull tick " + ticks); }
                    if (passed(i)) res[i] = "PASS tick " + ticks + " " + p.getLocation();
                    else if (!QuestRunner.active(p) || ticks >= cap) res[i] = "FAIL active " + QuestRunner.active(p) + " at " + p.getLocation() + " tick " + ticks + " stage " + p.getQuestRepository().getStage(i < n ? QuestPiratesTreasure.Q : i < 2 * n ? QuestShieldOfArrav.Q : i < 3 * n ? QuestRestlessGhost.Q : QuestPrinceAliRescue.Q);
                    if (res[i] != null) System.out.println(T + names[i] + " " + res[i]);
                    else all = false;
                }
                if (!all) return false;
                int pass = 0;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < G * n; i++) { if (res[i].startsWith("PASS")) pass++; sb.append(" | ").append(names[i]).append(' ').append(res[i].split(" at ")[0]); }
                System.out.println(T + "RESULT " + (pass == G * n ? "PASS" : "FAIL") + " " + pass + "/" + (G * n) + " piratesFix=" + QuestPiratesTreasure.FIX + " arravFix=" + QuestShieldOfArrav.FIX + sb);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
