package content.custom;

import java.util.List;

/**
 * Plain-java unit (no world): P50 -Dquest.ernestFix and -Dquest.piratesFix.
 * Ernest: with the flag the pressure-gauge step walks out to the fountain (nav walker) before pouring and searching;
 * without it the plan is unchanged (pour, search). Pirates: with the flag a job/banana check runs right before
 * "fill the crate with bananas"; without it the step list is unchanged.
 * java -cp out;server.jar [-Dquest.ernestFix=true -Dquest.piratesFix=true] content.custom.TestP50Quests
 */
public class TestP50Quests {
    public static void main(String[] a) throws Exception {
        Class.forName("content.custom.AfkTasks"); // the outer class first, as the server loads it
        int bad = 0;
        boolean ef = QuestErnestTheChicken.FIX, pf = QuestPiratesTreasure.FIX;

        QuestScript e = QuestErnestTheChicken.build();
        QuestSteps.Collect gauge = null;
        for (QuestSteps.Step s : e.steps) if (s.name.equals("get the pressure gauge")) gauge = (QuestSteps.Collect) s;
        if (gauge == null) { bad++; System.out.println("RESULT no gauge step"); }
        else {
            List<QuestSteps.Step> pl = gauge.plan;
            String first = pl.isEmpty() ? "?" : pl.get(0).name, last = pl.isEmpty() ? "?" : pl.get(pl.size() - 1).name;
            int want = ef ? 3 : 2;
            if (pl.size() != want) { bad++; System.out.println("RESULT gauge plan " + pl.size() + " want " + want); }
            if (ef != first.equals("walk out to the fountain")) { bad++; System.out.println("RESULT gauge plan starts with " + first); }
            if (!last.equals("search the fountain")) { bad++; System.out.println("RESULT gauge plan ends with " + last); }
        }

        QuestScript p = QuestPiratesTreasure.build();
        int fill = -1, check = -1;
        for (int i = 0; i < p.steps.size(); i++) {
            String n = p.steps.get(i).name;
            if (n.equals("fill the crate with bananas")) fill = i;
            if (n.equals("check the job and the bananas")) check = i;
        }
        if (fill < 0) { bad++; System.out.println("RESULT no fill step"); }
        if (pf ? check != fill - 1 : check != -1) { bad++; System.out.println("RESULT check step at " + check + ", fill at " + fill); }

        System.out.println("[TestP50Quests] ernestFix " + ef + " piratesFix " + pf + ": ernest steps " + e.steps.size() + ", pirates steps " + p.steps.size() + ", " + (bad == 0 ? "PASS" : "FAIL " + bad));
    }
}
