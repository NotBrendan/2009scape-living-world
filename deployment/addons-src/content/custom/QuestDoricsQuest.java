package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.world.map.Location;

import static content.custom.QuestScript.stageAtLeast;

/**
 * Doric's Quest: Doric (284, north of Falador) wants 6 clay, 4 copper ore and 2 iron ore. Start: Topic
 * "I wanted to use your anvils." then "Yes I will get you the materials." (stage 10; he hands over a bronze
 * pickaxe if you have none). Hand-in is continue only and all-or-nothing (1300 Mining XP, 180 gp, 1 QP).
 * Ores come from the pack, the bank, bot stock (they're glut goods), else the Rimmington mine.
 */
final class QuestDoricsQuest {
    private QuestDoricsQuest() { }

    static final content.data.Quests Q = content.data.Quests.DORICS_QUEST;
    static final int DORIC = 284, CLAY = 434, COPPER = 436, IRON = 440;
    static final Location DORIC_AT = Location.create(2952, 3450, 0);    // inside his house (doors 2949,3450 and 2952,3452); 2954,3450 is outside the east wall
    static final Location RIMMINGTON_MINE = Location.create(2977, 3240, 0);

    static boolean handedIn(Player p) { return p.getQuestRepository().getStage(Q) >= 100; }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "doric", "Doric's Quest").startAt(2952, 3451, 0).minutes(25).coins(180).xp(1300).unlock(30).level(Skills.MINING, 15);
        s.step(new QuestSteps.Walk("walk into Doric's house", DORIC_AT, 1).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(DORIC_AT) <= 1));
        s.step(new QuestSteps.Talk("offer Doric a hand", new int[]{DORIC}, "I wanted to use your anvils.", "Yes I will get you the materials.").until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));
        s.step(new QuestSteps.Collect("get 6 clay", CLAY, 6).then(new QuestSteps.Gather("mine clay at Rimmington", AfkTasks.Task.CLAY, CLAY, 6, RIMMINGTON_MINE)));
        s.step(new QuestSteps.Collect("get 4 copper ore", COPPER, 4).then(new QuestSteps.Gather("mine copper at Rimmington", AfkTasks.Task.COPPER, COPPER, 4, RIMMINGTON_MINE)));
        s.step(new QuestSteps.Collect("get 2 iron ore", IRON, 2).then(new QuestSteps.Gather("mine iron at Rimmington", AfkTasks.Task.IRON, IRON, 2, RIMMINGTON_MINE)));
        s.step(new QuestSteps.Walk("back into Doric's house", DORIC_AT, 1));
        s.step(new QuestSteps.Talk("give Doric the ores", new int[]{DORIC}).until(QuestDoricsQuest::handedIn));
        return s;
    }
}
