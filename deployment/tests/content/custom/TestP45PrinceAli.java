package content.custom;

import java.util.HashSet;
import java.util.Set;

/**
 * Plain-java unit (no world): P45 -Dquest.princeAliFix. The blonde wig (2419) must be in the runner's keep set (the ids
 * QuestRunner.makeRoom never banks) with the flag, and the plan must be unchanged without it (same step count, wig not kept).
 * java -cp out;server.jar [-Dquest.princeAliFix=true] content.custom.TestP45PrinceAli
 */
public class TestP45PrinceAli {
    public static void main(String[] a) throws Exception {
        Class.forName("content.custom.AfkTasks"); // the outer class first, as the server loads it (Task first trips AfkTasks' static init)
        boolean on = QuestPrinceAliRescue.FIX; int bad = 0;
        QuestScript s = QuestPrinceAliRescue.build();
        Set<Integer> keep = new HashSet<>();
        for (QuestSteps.Step st : s.steps) QuestRunner.collectIds(st, keep);
        boolean kept = keep.contains(QuestPrinceAliRescue.YELLOW_WIG);
        if (kept != on) { bad++; System.out.println("RESULT wig kept=" + kept + " want " + on); }
        int[] disguise = {QuestPrinceAliRescue.PASTE, QuestPrinceAliRescue.KEY, QuestPrinceAliRescue.SKIRT, QuestPrinceAliRescue.BEER, QuestPrinceAliRescue.ROPE};
        for (int id : disguise) if (!keep.contains(id)) { bad++; System.out.println("RESULT item " + id + " not kept"); }
        int dye = -1;
        for (int i = 0; i < s.steps.size(); i++) { String n = s.steps.get(i).name; if (n.equals("get the blonde wig") || n.equals("dye the wig blonde")) dye = i; }
        if (dye != 19) { bad++; System.out.println("RESULT dye step at " + dye + " want 19 (saved checkpoints index steps)"); }
        if (s.steps.size() != 40) { bad++; System.out.println("RESULT steps " + s.steps.size() + " want 40"); }
        boolean isCollect = dye >= 0 && s.steps.get(dye) instanceof QuestSteps.Collect;
        if (isCollect != on) { bad++; System.out.println("RESULT dye step Collect=" + isCollect + " want " + on); }
        System.out.println("[TestP45PrinceAli] flag " + on + ": steps " + s.steps.size() + ", " + (bad == 0 ? "PASS" : "FAIL " + bad));
    }
}
