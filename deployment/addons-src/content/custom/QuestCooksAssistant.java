package content.custom;

import core.game.interaction.IntType;
import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import static content.custom.QuestScript.attr;
import static content.custom.QuestScript.attrInt;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.onFloor;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Cook's Assistant: the Lumbridge cook (278) wants an egg, a bucket of milk and a pot of flour.
 * Start: options 1, 1, 4 (stage 0 -> 10). Hand-in: continue only; each item is taken separately
 * (cooks_assistant:egg/milk/flour_submitted), the quest finishes once all three are in (300 Cooking XP, 1 QP).
 * Flour is made like a player: wheat from the field, up two ladders at the Mill Lane mill, grain in the hopper,
 * the controls, back down and the pot on the flour bin.
 */
final class QuestCooksAssistant {
    private QuestCooksAssistant() { }

    static final content.data.Quests Q = content.data.Quests.COOKS_ASSISTANT;
    static final int COOK = 278, EGG = 1944, MILK = 1927, FLOUR = 1933, BUCKET = 1925, POT = 1931, GRAIN = 1947;
    static final int[] FLOUR_BINS = {1782, 5792, 22420, 22421, 24070, 36878}; // MillingListener; at Mill Lane the map holds a null-named wrapper (36880 at 3166,3306) whose child for this bot is the bin
    static final Location KITCHEN = Location.create(3208, 3213, 0);
    static final Location GENERAL_STORE = Location.create(3214, 3243, 0);   // Lumbridge general store (shopkeepers 520/521)
    static final Location DAIRY_COW = Location.create(3253, 3274, 0);       // dairy cows (8689) at 3252,3275 and 3254,3272, by Gillie Groats
    static final Location MILL = Location.create(3165, 3306, 0);            // Mill Lane mill, ground floor (ladder 3164,3307 on every floor; doors 3166-3167,3302)
    static final Location INSIDE = Location.create(3166, 3305, 0);          // inside the mill, past the doors at y 3302
    static final Location WHEAT = Location.create(3160, 3295, 0);           // the field by the mill (AfkTasks WHEAT_F)

    static boolean given(Player p, String what) { return attr(p, "cooks_assistant:" + what + "_submitted") || p.getQuestRepository().getStage(Q) >= 100; }
    static boolean milled(Player p) { return attrInt(p, "milling:grain") > 0; }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "cooks", "Cook's Assistant").startAt(3209, 3215, 0).minutes(20).xp(300).unlock(20);
        s.step(new QuestSteps.Walk("walk to the Lumbridge kitchen", KITCHEN, 4).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(KITCHEN) <= 4));
        s.step(new QuestSteps.Talk("ask the cook what's wrong", new int[]{COOK}, 1, 1, 4).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        s.step(new QuestSteps.Collect("get an egg", EGG, 1)
                .then(new QuestSteps.PickUp("pick up an egg by Fred's chickens", EGG, Location.create(3191, 3276, 0)))
                .then(new QuestSteps.Gather("pick up an egg at the chicken farm", AfkTasks.Task.EGGS, EGG, 1, Location.create(3229, 3299, 0)))
                .done(p -> has(EGG, 1).test(p) || given(p, "egg")));

        s.step(new QuestSteps.Collect("get a bucket of milk", MILK, 1)
                .then(new QuestSteps.Collect("get an empty bucket", BUCKET, 1).buy(new QuestSteps.Buy("buy a bucket", BUCKET, 1, "the Lumbridge general store", GENERAL_STORE)).done(has(BUCKET, 1)))
                .then(new QuestSteps.Walk("walk into the dairy", DAIRY_COW, 1))
                .then(new QuestSteps.Interact("milk the dairy cow", new int[]{8689, 12111}, null, "Milk", DAIRY_COW, 10, false).until(has(MILK, 1))) // the option handler walks up to the cow; the bucket use-with (old UseWithHandler) does not
                .done(p -> has(MILK, 1).test(p) || given(p, "milk")));

        s.step(new QuestSteps.Collect("get a pot of flour", FLOUR, 1)
                .then(new QuestSteps.Collect("get an empty pot", POT, 1).buy(new QuestSteps.Buy("buy a pot", POT, 1, "the Lumbridge general store", GENERAL_STORE)).done(has(POT, 1)))
                .then(new QuestSteps.Collect("get some grain", GRAIN, 1)
                        .then(new QuestSteps.Gather("pick wheat by the mill", AfkTasks.Task.WHEAT, GRAIN, 1, WHEAT))
                        .done(p -> has(GRAIN, 1).test(p) || milled(p) || attrInt(p, "milling:hopper") > 0))
                .then(new QuestSteps.Walk("walk into the mill", INSIDE, 1).done(p -> p.getLocation().getZ() > 0 || milled(p) || p.getLocation().getDistance(INSIDE) <= 1))
                .then(new QuestSteps.Interact("climb the mill ladder", null, "ladder", "Climb-up", MILL, 8, false).until(p -> p.getLocation().getZ() >= 1).done(p -> p.getLocation().getZ() >= 1 || milled(p)))
                .then(new QuestSteps.Interact("climb to the top floor", null, "ladder", "Climb-up", MILL.transform(0, 0, 1), 8, false).until(onFloor(2)).done(p -> p.getLocation().getZ() >= 2 || milled(p)))
                .then(new QuestSteps.UseOn("put the grain in the hopper", GRAIN, null, "hopper", IntType.SCENERY, MILL.transform(0, 0, 2), 8).until(p -> attrInt(p, "milling:hopper") > 0 || milled(p)).done(p -> attrInt(p, "milling:hopper") > 0 || milled(p)))
                .then(new QuestSteps.Interact("work the hopper controls", null, "hopper controls", "Operate", MILL.transform(0, 0, 2), 8, false).until(QuestCooksAssistant::milled).done(QuestCooksAssistant::milled))
                .then(new QuestSteps.Interact("climb down a floor", null, "ladder", "Climb-down", MILL.transform(0, 0, 2), 8, false).until(p -> p.getLocation().getZ() <= 1).done(p -> p.getLocation().getZ() <= 1))
                .then(new QuestSteps.Interact("climb down to the ground", null, "ladder", "Climb-down", MILL.transform(0, 0, 1), 8, false).until(onFloor(0)).done(onFloor(0)))
                .then(new QuestSteps.UseOn("fill the pot at the flour bin", POT, FLOUR_BINS, null, IntType.SCENERY, MILL, 8).until(has(FLOUR, 1)))
                .done(p -> has(FLOUR, 1).test(p) || given(p, "flour")));

        s.step(new QuestSteps.Walk("back to the kitchen", KITCHEN, 4));
        s.step(new QuestSteps.Talk("hand the cook the ingredients", new int[]{COOK}).until(p -> stageAtLeast(Q, 100).test(p) || given(p, "egg") && given(p, "milk") && given(p, "flour")));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        return s;
    }
}
