package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (P77, pattern of TestP72QuestTrial). -Dtest.p77=true: -Dp77.n (2) bots per group.
 * rune3: Rune Mysteries stage 10, the air talisman in the BANK, Lumbridge courtyard, resumed at step 0 (live step 3
 *   "give Sedridor the talisman: dialogue ended with picks left [1]": WizardTowerPlugin.SedridorDialogue stage 51 removes
 *   1438 from the pack and ends when it isn't there).
 * rune5: stage 30, the research package (290) in the BANK, Lumbridge courtyard, step 0 (live step 5: Aubury's
 *   "I kind of don't have it with me" branch, stage stays 30).
 * rune6: stage 40, Lumbridge courtyard 3224,3220, step 0 (live step 6 "nobody to talk to (ids [553])": the walk to
 *   Aubury counted as done at stage 40).
 * romeo: Romeo & Juliet stage 60, cadava potion in the pack, in Juliet's room, started at "give Juliet the potion"
 *   (live step 18 "back to Romeo: time limit 800 ticks [last heading for the Ladder at [3187, 3414, 1]]"); odd bots
 *   carry no coins (no paid teleport out of a wrong floor).
 * PASS = quest complete (stage 100). Cap: -Dtest.p77.min (45).
 */
public final class TestP77QuestTrial implements StartupListener {
    static final String T = "[TestP77QuestTrial] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p77")) return;
        System.out.println(T + "armed romeoFix=" + QuestRomeoAndJuliet.FIX + " keepQuestItems=" + QuestRunner.KEEP_QUEST_ITEMS);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static void broke(Player b) {
        try {
            int a = b.getInventory().getAmount(995); if (a > 0) b.getInventory().remove(new Item(995, a));
            a = b.getBank().getAmount(995); if (a > 0) b.getBank().remove(new Item(995, a));
        } catch (Throwable ignored) { }
    }

    static void go() {
        QuestScript rs = QuestBook.SCRIPTS.get("runemysteries"), js = QuestBook.SCRIPTS.get("romeojuliet");
        if (rs == null || js == null) { System.out.println(T + "RESULT FAIL no script " + (rs == null) + " " + (js == null)); System.out.println(T + "DONE"); return; }
        int ji = TestP72QuestTrial.index(js, "give Juliet the potion");
        if (ji < 0) { System.out.println(T + "RESULT FAIL no step"); System.out.println(T + "DONE"); return; }
        final int n = Math.max(1, Integer.getInteger("p77.n", 2));
        final String[] groups = {"rune3", "rune5", "rune6", "romeo"};
        final int G = groups.length;
        final Player[] bots = new Player[G * n];
        final String[] names = new String[G * n];
        for (int k = 0; k < n; k++) {
            Location lum = Location.create(3222 + k % 3, 3219 + k / 3 % 2, 0);
            AIPlayer a = TestP72QuestTrial.bot(lum, QuestRuneMysteries.Q, 10);
            a.getBank().add(new Item(QuestRuneMysteries.TALISMAN, 1));
            QuestRunner.start(a, rs, 0, true);
            bots[k] = a; names[k] = "rune3_" + k;
            AIPlayer b = TestP72QuestTrial.bot(lum, QuestRuneMysteries.Q, 30);
            b.getBank().add(new Item(QuestRuneMysteries.PACKAGE, 1));
            QuestRunner.start(b, rs, 0, true);
            bots[n + k] = b; names[n + k] = "rune5_" + k;
            AIPlayer c = TestP72QuestTrial.bot(Location.create(3224, 3220 - k % 2, 0), QuestRuneMysteries.Q, 40);
            QuestRunner.start(c, rs, 0, true);
            bots[2 * n + k] = c; names[2 * n + k] = "rune6_" + k;
            AIPlayer d = TestP72QuestTrial.bot(Location.create(3159, 3425 + k % 2, 1), QuestRomeoAndJuliet.Q, 60);
            d.getInventory().add(new Item(QuestRomeoAndJuliet.POTION, 1));
            if ((k & 1) == 1) broke(d);
            QuestRunner.start(d, js, ji, true);
            bots[3 * n + k] = d; names[3 * n + k] = "romeo_" + k;
        }
        final long cap = Integer.getInteger("test.p77.min", 45) * 100L;
        GameWorld.getPulser().submit(new Pulse(5) {
            long ticks; final String[] res = new String[G * n]; final boolean[] cut = new boolean[n];
            content.data.Quests q(int i) { return i < 3 * n ? QuestRuneMysteries.Q : QuestRomeoAndJuliet.Q; }
            @Override public boolean pulse() {
                ticks += 5;
                boolean all = true;
                for (int i = 0; i < G * n; i++) {
                    if (res[i] != null) continue;
                    Player p = bots[i];
                    int st = TestP72QuestTrial.stage(p, q(i));
                    if (i >= 3 * n && !cut[i - 3 * n] && st >= 70) { cut[i - 3 * n] = true; System.out.println(T + names[i] + " stage 70 tick " + ticks + " at " + p.getLocation()); }
                    if (st >= 100) res[i] = "PASS tick " + ticks + " " + p.getLocation();
                    else if (!QuestRunner.active(p) || ticks >= cap) res[i] = "FAIL active " + QuestRunner.active(p) + " at " + p.getLocation() + " tick " + ticks + " stage " + st;
                    if (res[i] != null) System.out.println(T + names[i] + " " + res[i]);
                    else all = false;
                }
                if (!all) return false;
                int pass = 0;
                int[] gp = new int[G];
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < G * n; i++) { if (res[i].startsWith("PASS")) { pass++; gp[i / n]++; } sb.append(" | ").append(names[i]).append(' ').append(res[i].split(" at ")[0]); }
                StringBuilder g = new StringBuilder();
                for (int k = 0; k < G; k++) g.append(' ').append(groups[k]).append(' ').append(gp[k]).append('/').append(n);
                System.out.println(T + "RESULT " + (pass == G * n ? "PASS" : "FAIL") + " " + pass + "/" + (G * n) + g + " romeoFix=" + QuestRomeoAndJuliet.FIX + sb);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
