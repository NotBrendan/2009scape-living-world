package content.custom;

/**
 * Plain-java unit (no world): P51 -Dquest.arravFix and -Dquest.romeoFix.
 * Arrav: with the flag the first step gives a stage-70 bot in no gang its gang back; without it the step list is unchanged.
 * Romeo: with the flag "find Juliet's message" runs right before "walk up to Juliet" and "find the cadava potion" right
 * before "get cadava berries"; without it neither step exists.
 * java -cp out;server.jar [-Dquest.arravFix=true -Dquest.romeoFix=true] content.custom.TestP51Quests
 */
public class TestP51Quests {
    static int index(QuestScript s, String name) {
        for (int i = 0; i < s.steps.size(); i++) if (s.steps.get(i).name.equals(name)) return i;
        return -1;
    }

    public static void main(String[] a) throws Exception {
        Class.forName("content.custom.AfkTasks"); // the outer class first, as the server loads it
        int bad = 0;
        boolean af = QuestShieldOfArrav.FIX, rf = QuestRomeoAndJuliet.FIX;

        QuestScript s = QuestShieldOfArrav.build();
        int gang = index(s, "give back the lost gang"), door = index(s, "through the Black Arm door");
        if (door < 0) { bad++; System.out.println("RESULT no Black Arm door step"); }
        if (af ? gang != 0 : gang != -1) { bad++; System.out.println("RESULT gang step at " + gang); }
        if (QuestShieldOfArrav.ROOM_W != (af ? 3182 : 3180)) { bad++; System.out.println("RESULT room west edge " + QuestShieldOfArrav.ROOM_W); }

        QuestScript r = QuestRomeoAndJuliet.build();
        int msg = index(r, "find Juliet's message"), up = index(r, "walk up to Juliet");
        int pot = index(r, "find the cadava potion"), ber = index(r, "get cadava berries");
        if (up < 0 || ber < 0) { bad++; System.out.println("RESULT romeo steps missing " + up + " " + ber); }
        if (rf ? msg != up - 1 : msg != -1) { bad++; System.out.println("RESULT message step at " + msg + ", Juliet at " + up); }
        if (rf ? pot != ber - 1 : pot != -1) { bad++; System.out.println("RESULT potion step at " + pot + ", berries at " + ber); }
        if (r.steps.size() != (rf ? 22 : 20)) { bad++; System.out.println("RESULT romeo steps " + r.steps.size()); }

        System.out.println("[TestP51Quests] arravFix " + af + " romeoFix " + rf + ": arrav steps " + s.steps.size() + ", romeo steps " + r.steps.size() + ", " + (bad == 0 ? "PASS" : "FAIL " + bad));
    }
}
