package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.world.map.Location;

import static content.custom.QuestScript.attr;
import static content.custom.QuestScript.attrInt;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.onFloor;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Sheep Shearer: Fred the Farmer (758) wants 20 balls of wool. Start: Topic "I'm looking for a quest." then options
 * 1, 1, 1 (stage 10). Shears from the general store, the bots' WOOL task in Fred's pen, then the spinning wheel on
 * the first floor of Lumbridge castle. Hand-in is continue only and takes partial deliveries
 * (sheep-shearer:num-balls-of-wool-delivered); 20 delivered -> stage 90 -> finished (150 Crafting XP, 60 gp, 1 QP).
 */
final class QuestSheepShearer {
    private QuestSheepShearer() { }

    static final content.data.Quests Q = content.data.Quests.SHEEP_SHEARER;
    static final int FRED = 758, SHEARS = 1735, WOOL = 1737, BALL = 1759;
    static final Location FRED_AT = Location.create(3189, 3273, 0);    // inside his house (stopping 4 tiles short left bots outside the wall)
    static final Location PEN = Location.create(3203, 3266, 0);
    static final Location GENERAL_STORE = Location.create(3214, 3243, 0);
    static final Location CASTLE_STAIRS = Location.create(3205, 3209, 0);   // by the south-west staircase of Lumbridge castle (36773 at 3204,3207)
    static final Location WHEEL = Location.create(3209, 3213, 1);           // spinning wheel 36970 at 3209,3212, first floor (door 3207,3210 on the way)

    static int delivered(Player p) { return attrInt(p, "sheep-shearer:num-balls-of-wool-delivered"); }
    static int needed(Player p) { return Math.max(0, 20 - delivered(p)); }
    static boolean wooled(Player p) { return p.getQuestRepository().getStage(Q) >= 90 || p.getInventory().getAmount(BALL) >= needed(p); }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "sheep", "Sheep Shearer").startAt(3189, 3273, 0).minutes(30).coins(60).xp(150).near(3);
        s.step(new QuestSteps.Walk("walk into Fred's house", FRED_AT, 1).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(FRED_AT) <= 1));
        s.step(new QuestSteps.Talk("ask Fred for work", new int[]{FRED}, "I'm looking for a quest.", 1, 1, 1).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        s.step(new QuestSteps.Collect("get 20 balls of wool", BALL, 20).count(QuestSheepShearer::needed)
                .then(new QuestSteps.Collect("get some shears", SHEARS, 1).then(new QuestSteps.PickUp("pick up the shears in Fred's house", SHEARS, Location.create(3192, 3272, 0)).limit(120)).buy(new QuestSteps.Buy("buy shears", SHEARS, 1, "the Lumbridge general store", GENERAL_STORE)).done(p -> has(SHEARS, 1).test(p) || wooled(p) || p.getInventory().getAmount(WOOL) >= needed(p) - p.getInventory().getAmount(BALL)))
                .then(new QuestSteps.Collect("get the wool", WOOL, 20).count(p -> needed(p) - p.getInventory().getAmount(BALL))
                        .then(new QuestSteps.Gather("shear Fred's sheep", AfkTasks.Task.WOOL, WOOL, 20, PEN).count(p -> needed(p) - p.getInventory().getAmount(BALL)))
                        .done(p -> wooled(p) || p.getInventory().getAmount(WOOL) >= needed(p) - p.getInventory().getAmount(BALL)))
                .then(new QuestSteps.Walk("walk to the castle stairs", CASTLE_STAIRS, 3).done(p -> p.getLocation().getZ() > 0 || wooled(p) || p.getLocation().getDistance(CASTLE_STAIRS) <= 3))
                .then(new QuestSteps.Interact("go upstairs in the castle", null, "staircase", "Climb-up", CASTLE_STAIRS, 6, false).until(onFloor(1)).done(p -> p.getLocation().getZ() == 1 || wooled(p)))
                .then(new QuestSteps.Walk("walk to the spinning wheel", WHEEL, 2).done(p -> wooled(p) || p.getLocation().getZ() == 1 && p.getLocation().getDistance(WHEEL) <= 2))
                .then(new QuestSteps.Custom("spin the wool", 1200, QuestSheepShearer::startSpinning, r -> {
                    Player p = r.bot;
                    if (wooled(p)) return QuestSteps.St.DONE;
                    if (p.getInventory().getAmount(WOOL) == 0) return QuestSteps.St.FAILED;
                    if (!p.getPulseManager().hasPulseRunning()) startSpinning(r);
                    return QuestSteps.St.RUNNING;
                }).done(QuestSheepShearer::wooled))
                .done(QuestSheepShearer::wooled));

        s.step(new QuestSteps.Interact("go back downstairs", null, "staircase", "Climb-down", CASTLE_STAIRS.transform(0, 0, 1), 8, false).until(onFloor(0)).done(onFloor(0)));
        s.step(new QuestSteps.Walk("back into Fred's house", FRED_AT, 1));
        s.step(new QuestSteps.Talk("give Fred the wool", new int[]{FRED}).picks(p -> attr(p, "sheep-shearer:is-penguin-sheep-sheared") ? new Object[]{1} : new Object[0])
                .until(p -> stageAtLeast(Q, 90).test(p) || delivered(p) >= 20));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        return s;
    }

    /** At the wheel: spin what the pack holds (the pulse doesn't check for a wheel, so the step stands the bot next to one first). */
    static void startSpinning(QuestRunner r) {
        Player p = r.bot;
        int n = Math.min(p.getInventory().getAmount(WOOL), needed(p) - p.getInventory().getAmount(BALL));
        if (n <= 0) return;
        try {
            p.getPulseManager().run(new content.global.skill.crafting.spinning.SpinningPulse(p, new Item(WOOL), n, content.global.skill.crafting.spinning.SpinningItem.WOOL));
        } catch (Throwable t) { r.log("  spinning failed to start: " + t); }
    }
}
