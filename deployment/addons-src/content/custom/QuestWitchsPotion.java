package content.custom;

import core.game.interaction.IntType;
import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Witch's Potion: Hetty (307, Rimmington). Start: options 1 ("I am in search of a quest.") then 1
 * ("Yes help me become one with my darker side."): stage 20. She wants an onion, a rat's tail (rats only drop it
 * while the quest is started), burnt meat (cooked meat used on a range burns on purpose) and an eye of newt (Betty's
 * shop, Port Sarim). Raw beef on a range asks "cook or make sinew?" (CookingDialogue): option 1 dries it into sinew, option 2 cooks. Hand-in is continue only (stage 40), then "Drink from" the cauldron (2024) finishes it
 * (325 Magic XP, 1 QP).
 */
final class QuestWitchsPotion {
    private QuestWitchsPotion() { }

    static final content.data.Quests Q = content.data.Quests.WITCHS_POTION;
    static final int HETTY = 307, ONION = 1957, TAIL = 300, BURNT = 2146, COOKED = 2142, RAW_BEEF = 2132, NEWT = 221, CAULDRON = 2024;
    static final Location HETTY_AT = Location.create(2967, 3205, 0);
    static final Location HETTY_IN = Location.create(2966, 3205, 0);       // inside her house (door 2964,3206); the range house next door is walled off from it
    static final Location RATS = Location.create(2960, 3204, 0);
    static final Location BETTY = Location.create(3013, 3258, 0);          // Betty's Magic Emporium, Port Sarim
    static final Location ONIONS = Location.create(2950, 3251, 0);         // the onion patch north of Rimmington (3366 at 2946-2954 x 3249-3253)
    static final Location COWS = Location.create(3027, 3306, 0);           // the cows on the farm south of Falador
    static final Location RANGE = Location.create(2970, 3209, 0);          // the range (9682) in the house next to Hetty's
    static final Location RANGE_SIDE = Location.create(2969, 3209, 0);     // standing next to it, inside (a wall separates it from Hetty's house)

    static boolean handedIn(Player p) { return p.getQuestRepository().getStage(Q) >= 40; }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "witch", "Witch's Potion").startAt(2968, 3206, 0).minutes(25).xp(325).unlock(10);
        s.step(new QuestSteps.Walk("walk to Hetty's house", HETTY_AT, 4).done(p -> stageAtLeast(Q, 20).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(HETTY_AT) <= 4));
        s.step(new QuestSteps.Talk("ask Hetty for a quest", new int[]{HETTY}, 1, 1).until(stageAtLeast(Q, 20)).done(stageAtLeast(Q, 20)));

        s.step(new QuestSteps.Collect("get a rat's tail", TAIL, 1)
                .then(new QuestSteps.Kill("kill a rat for its tail", new String[]{"Rat"}, new int[]{TAIL}, 1, RATS).until(has(TAIL, 1)))
                .done(p -> has(TAIL, 1).test(p) || handedIn(p)));
        s.step(new QuestSteps.Collect("get an onion", ONION, 1)
                .then(new QuestSteps.Gather("pick an onion", AfkTasks.Task.ONION, ONION, 1, ONIONS))
                .done(p -> has(ONION, 1).test(p) || handedIn(p)));
        s.step(new QuestSteps.Collect("get an eye of newt", NEWT, 1)
                .buy(new QuestSteps.Buy("buy an eye of newt from Betty", NEWT, 1, "Betty's Magic Emporium", BETTY))
                .done(p -> has(NEWT, 1).test(p) || handedIn(p)));
        s.step(new QuestSteps.Collect("get some burnt meat", BURNT, 1)
                .then(new QuestSteps.Collect("get some cooked meat", COOKED, 1)
                        .then(new QuestSteps.Collect("get raw beef", RAW_BEEF, 1)
                                .then(new QuestSteps.Kill("kill a cow for beef", new String[]{"Cow"}, new int[]{RAW_BEEF}, 1, COWS).until(has(RAW_BEEF, 1)))
                                .done(p -> has(RAW_BEEF, 1).test(p) || has(BURNT, 1).test(p)))
                        .then(new QuestSteps.Walk("walk into the house with the range", RANGE_SIDE, 1))
                        .then(new QuestSteps.UseOn("cook the beef on a range", RAW_BEEF, null, "range", IntType.SCENERY, RANGE, 12).single().picks(2).until(p -> has(COOKED, 1).test(p) || has(BURNT, 1).test(p)))
                        .done(p -> has(COOKED, 1).test(p) || has(BURNT, 1).test(p)))
                .then(new QuestSteps.Walk("walk into the house with the range", RANGE_SIDE, 1))
                .then(new QuestSteps.UseOn("burn the meat on purpose", COOKED, null, "range", IntType.SCENERY, RANGE, 12).single().until(has(BURNT, 1)))
                .done(p -> has(BURNT, 1).test(p) || handedIn(p)));

        s.step(new QuestSteps.Walk("back into Hetty's house", HETTY_IN, 1));
        s.step(new QuestSteps.Talk("give Hetty the ingredients", new int[]{HETTY}).until(QuestWitchsPotion::handedIn).done(QuestWitchsPotion::handedIn));
        s.step(new QuestSteps.Interact("drink from the cauldron", new int[]{CAULDRON}, null, "Drink from", HETTY_AT, 8, false).until(stageAtLeast(Q, 100)));
        return s;
    }
}
