package content.custom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain-java unit (no world): P46 -Dquest.keepQuestItems. makeRoom's pass-1 order (QuestRunner.bankOrder) must bank the
 * most-slotted ids first (Prince Ali trial pack: the 22 logs before the lone blonde wig) and leave out ids this run got
 * (Goblin Diplomacy's orange and blue mail, which are not keep-set ids). Flag off: slotsFor is the old slot count.
 * java -cp out;server.jar [-Dquest.keepQuestItems=true] content.custom.TestP46KeepItems
 */
public class TestP46KeepItems {
    public static void main(String[] a) throws Exception {
        Class.forName("content.custom.AfkTasks");
        boolean on = QuestRunner.KEEP_QUEST_ITEMS; int bad = 0;
        if (on != Boolean.getBoolean("quest.keepQuestItems")) { bad++; System.out.println("RESULT flag mismatch"); }
        if (!on && QuestRunner.slotsFor(null, 995, 15) != 15) { bad++; System.out.println("RESULT flag off: slotsFor changed"); }

        // Prince Ali: the trial's pack, the quest's own keep set (without -Dquest.princeAliFix the wig is not in it)
        Set<Integer> keep = new HashSet<>();
        for (QuestSteps.Step st : QuestPrinceAliRescue.build().steps) QuestRunner.collectIds(st, keep);
        List<Integer> pack = new ArrayList<>(Arrays.asList(QuestPrinceAliRescue.YELLOW_WIG, QuestPrinceAliRescue.PASTE, QuestPrinceAliRescue.SKIRT,
                QuestPrinceAliRescue.BEER, QuestPrinceAliRescue.ROPE));
        for (int i = 0; i < 22; i++) pack.add(1511);
        List<Integer> order = QuestRunner.bankOrder(pack, keep, new HashSet<>());
        if (order.isEmpty() || order.get(0) != 1511) { bad++; System.out.println("RESULT ali order " + order + " want logs first"); }
        if (!QuestPrinceAliRescue.FIX && order.indexOf(QuestPrinceAliRescue.YELLOW_WIG) < order.indexOf(1511)) { bad++; System.out.println("RESULT ali wig before logs " + order); }

        // Goblin Diplomacy: the dyed mails are made by item-on-item (not keep ids); this run got them, so pass 1 skips them
        Set<Integer> gk = new HashSet<>();
        for (QuestSteps.Step st : QuestGoblinDiplomacy.build().steps) QuestRunner.collectIds(st, gk);
        int orange = QuestGoblinDiplomacy.ORANGE_MAIL, blue = QuestGoblinDiplomacy.BLUE_MAIL;
        if (gk.contains(orange) || gk.contains(blue)) System.out.println("note: dyed mail already in the keep set");
        List<Integer> gp = Arrays.asList(orange, blue, 526, 526, 526, 1511, 995);
        Set<Integer> got = new HashSet<>(Arrays.asList(orange, blue));
        List<Integer> go = QuestRunner.bankOrder(gp, gk, got);
        if (go.contains(orange) || go.contains(blue) || go.contains(995)) { bad++; System.out.println("RESULT goblin order " + go + " has a got id or coins"); }
        if (go.isEmpty() || go.get(0) != 526) { bad++; System.out.println("RESULT goblin order " + go + " want bones first"); }

        System.out.println("[TestP46KeepItems] flag " + on + ": ali " + order.subList(0, Math.min(3, order.size())) + ", goblin " + go + ", " + (bad == 0 ? "PASS" : "FAIL " + bad));
    }
}
