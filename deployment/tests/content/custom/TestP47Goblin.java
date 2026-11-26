package content.custom;

/**
 * Plain-java unit (no world): P47 -Dquest.goblinFix. The colour varp the fix writes before each showing must be the value the
 * server's own GoblinDiplomacy.getConfig gives for that stage (varp 62), and that value must make the generals ask for the
 * right armour next (GrubFoot.forConfig: the colour after the one with that value): 10 -> ORANGE, 20 -> BLUE, 30 -> BROWN.
 * Varp 0 (a stage restored without a tab sync) reads as NORMAL first, the stall's cause. The plan keeps its step count.
 * java -cp out;server.jar [-Dquest.goblinFix=true] content.custom.TestP47Goblin
 */
public class TestP47Goblin {
    public static void main(String[] a) throws Exception {
        Class.forName("content.custom.AfkTasks"); // the outer class first, as the server loads it
        int bad = 0;
        content.region.asgarnia.goblinvillage.quest.goblindiplomacy.GoblinDiplomacy gd = new content.region.asgarnia.goblinvillage.quest.goblindiplomacy.GoblinDiplomacy();
        int[] stages = {10, 20, 30}; String[] want = {"ORANGE", "BLUE", "BROWN"};
        for (int i = 0; i < stages.length; i++) {
            int[] c = gd.getConfig(null, stages[i]);
            int mine = QuestGoblinDiplomacy.colourFor(stages[i]);
            if (c[0] != QuestGoblinDiplomacy.COLOUR_VARP || c[1] != mine) { bad++; System.out.println("RESULT stage " + stages[i] + " server varp " + c[0] + "=" + c[1] + ", fix writes " + mine); }
            String next = nextFor(mine);
            if (!want[i].equals(next)) { bad++; System.out.println("RESULT stage " + stages[i] + " value " + mine + " asks for " + next + " want " + want[i]); }
        }
        if (!"NORMAL".equals(nextFor(0))) { bad++; System.out.println("RESULT varp 0 asks for " + nextFor(0) + " (expected NORMAL: the stall's cause)"); }
        if (QuestGoblinDiplomacy.colourFor(0) != -1 || QuestGoblinDiplomacy.colourFor(100) != -1) { bad++; System.out.println("RESULT colourFor touches stage 0/100"); }
        QuestScript s = QuestGoblinDiplomacy.build();
        if (s.steps.size() != 27) { bad++; System.out.println("RESULT steps " + s.steps.size() + " want 27 (saved checkpoints index steps)"); }
        System.out.println("[TestP47Goblin] flag " + QuestGoblinDiplomacy.FIX + ": steps " + s.steps.size() + ", " + (bad == 0 ? "PASS" : "FAIL " + bad));
    }

    /** GrubFoot.forConfig without a player: the enum constant after the one whose value matches, BROWN at the end, NORMAL if none. */
    static String nextFor(int varp) {
        Object[] v = content.region.asgarnia.goblinvillage.quest.goblindiplomacy.GrubFoot.values();
        for (int i = 0; i < v.length; i++) {
            if (((content.region.asgarnia.goblinvillage.quest.goblindiplomacy.GrubFoot) v[i]).getValue() == varp)
                return i + 1 >= v.length ? "BROWN" : ((Enum<?>) v[i + 1]).name();
        }
        return "NORMAL";
    }
}
