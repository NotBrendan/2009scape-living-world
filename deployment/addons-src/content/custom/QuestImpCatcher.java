package content.custom;

import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import static content.custom.QuestScript.onFloor;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Imp Catcher: Wizard Mizgog (706) on the top floor of the Wizards' Tower wants his four beads back (red 1470,
 * yellow 1472, black 1474, white 1476; any imp drops them). Start: options 1 ("Give me a quest please.") then 1
 * ("I'll try"). Hand-in is continue only, all four at once (875 Magic XP, an amulet of accuracy, 1 QP).
 * Beads from the pack, the bank or bot stock, else imps in the fields south of Falador. Stairs are explicit steps.
 */
final class QuestImpCatcher {
    private QuestImpCatcher() { }

    static final content.data.Quests Q = content.data.Quests.IMP_CATCHER;
    static final int MIZGOG = 706;
    static final int[] BEADS = {1470, 1472, 1474, 1476};
    static final Location TOWER = Location.create(3105, 3161, 0);       // inside the Wizards' Tower, by the staircase (12536 at 3103,3159; 12538 at 3104,3160 on the top floor)
    static final Location MIZGOG_AT = Location.create(3103, 3163, 2);
    static final Location IMPS = Location.create(2970, 3290, 0);         // imps roam the fields south of Falador

    static boolean allBeads(Player p) { for (int b : BEADS) if (p.getInventory().getAmount(b) < 1) return false; return true; }

    /** Up the tower; before the quest starts these are skipped once it has (a resumed bot hunting imps doesn't climb back up first). */
    static void climb(QuestScript s, String what, boolean beforeStart) {
        java.util.function.Predicate<Player> started = p -> beforeStart && stageAtLeast(Q, 10).test(p);
        s.step(new QuestSteps.Walk("walk into the Wizards' Tower" + what, TOWER, 4).done(p -> started.test(p) || p.getLocation().getZ() > 0 || p.getLocation().getDistance(TOWER) <= 4));
        s.step(new QuestSteps.Interact("up the tower stairs", null, "staircase", "Climb-up", TOWER, 10, false).until(onFloor(1)).done(p -> started.test(p) || p.getLocation().getZ() >= 1));
        s.step(new QuestSteps.Interact("up to the top floor", null, "staircase", "Climb-up", TOWER.transform(0, 0, 1), 10, false).until(onFloor(2)).done(p -> started.test(p) || p.getLocation().getZ() >= 2));
        s.step(new QuestSteps.Walk("over to Mizgog", MIZGOG_AT, 3).done(p -> started.test(p) || p.getLocation().getZ() == 2 && p.getLocation().getDistance(MIZGOG_AT) <= 3));
    }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "imp", "Imp Catcher").startAt(3109, 3162, 0).minutes(45).items(1478).xp(875 * 0.3);
        climb(s, "", true);
        s.step(new QuestSteps.Talk("ask Mizgog for a quest", new int[]{MIZGOG}, 1, 1).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));
        s.step(new QuestSteps.Interact("down a floor", null, "staircase", "Climb-down", TOWER.transform(0, 0, 2), 10, false).until(onFloor(1)).done(p -> p.getLocation().getZ() <= 1 || allBeads(p)));
        s.step(new QuestSteps.Interact("down to the ground floor", null, "staircase", "Climb-down", TOWER.transform(0, 0, 1), 10, false).until(onFloor(0)).done(p -> p.getLocation().getZ() == 0 || allBeads(p)));
        for (int b : BEADS) s.step(new QuestSteps.Collect("look for bead " + b + " in the bank and the Exchange", b, 1).stock().done(p -> p.getInventory().getAmount(b) >= 1 || stageAtLeast(Q, 100).test(p)).optional());
        s.step(new QuestSteps.Kill("hunt imps for the beads", new String[]{"Imp"}, BEADS, 400, IMPS).spots(Location.create(2935, 3325, 0), Location.create(2995, 3272, 0), Location.create(2957, 3308, 0), Location.create(2942, 3250, 0), Location.create(3010, 3271, 0)).until(QuestImpCatcher::allBeads).limit(6000).done(QuestImpCatcher::allBeads));
        climb(s, " again", false);
        s.step(new QuestSteps.Talk("give Mizgog his beads", new int[]{MIZGOG}).until(stageAtLeast(Q, 100)));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(new QuestSteps.Interact("down a floor", null, "staircase", "Climb-down", TOWER.transform(0, 0, 2), 10, false).until(onFloor(1)).done(p -> p.getLocation().getZ() <= 1));
        s.exit(new QuestSteps.Interact("down to the ground floor", null, "staircase", "Climb-down", TOWER.transform(0, 0, 1), 10, false).until(onFloor(0)).done(onFloor(0)));
        return s;
    }
}
