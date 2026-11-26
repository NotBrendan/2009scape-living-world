package content.custom;

import core.game.dialogue.DialogueInterpreter;
import core.game.node.item.Item;
import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Romeo & Juliet: Romeo (639, Varrock square), Juliet (637, upstairs in the house west of Varrock), Father Lawrence
 * (640, Varrock church), the Apothecary (638, south-west Varrock). Stages (RomeoJuliet.java, RJCutscenePlugin.java,
 * JulietDialogue.java, FatherLawrenceDialogue.java, ApothecaryDialogue.java): 10 Romeo asked, 20 Juliet's message (755)
 * in hand, 30 message delivered, 40 Lawrence's plan, 50 berries wanted, 60 cadava potion (756) in hand, 70 Juliet drank it,
 * 100 done (5 QP, no coins, no items).
 * Romeo at stage 0 (RJCutscenePlugin.RomeoDialogue): options 2 ("Perhaps I could help to find her for you?") then
 * 1 ("Yes okay, I'll let her know."). Stage 20: continue until a menu "How are you? / Where can I find Father Lawrence? /
 * Have you heard anything from Juliet? / Ok, thanks.", pick 4 ("Ok, thanks."); the stage is already 30 by then.
 * Juliet at stages 10 and 60 and Lawrence at 30, the Apothecary at 40 and 50 are continue only.
 * Cadava berries (753): RomeoQuestPlugin.java, option "Pick-from" on bushes 23625/23626 (23627 is the empty bush, regrows
 * after 30 ticks); every third pick empties the bush.
 * Two cutscenes: Juliet at stage 60 ends her dialogue and starts "Juliet Cutscene", Romeo at stage 70 starts "Romeo & Juliet
 * Cutscene". Both open their own continue-only dialogue and close/reopen it between screens, so a Custom step clicks
 * continue and never closes anything (DialogueDriver would close it in the gaps).
 * NPC spawns from data/configs/npc_spawns.json: 639 {3211,3425,0} (wanders), 637 {3158,3425,1}, 640 {3253,3487,0}, 638 {3195,3404,0}.
 */
final class QuestRomeoAndJuliet {
    private QuestRomeoAndJuliet() { }

    static final content.data.Quests Q = content.data.Quests.ROMEO_JULIET;
    static final int ROMEO = 639, JULIET = 637, LAWRENCE = 640, APOTHECARY = 638;
    static final int BERRIES = 753;
    static final int[] BUSHES = {23625, 23626};
    static final Location ROMEO_AT = Location.create(3211, 3425, 0);         // Romeo's spawn tile, Varrock square
    static final Location JULIET_AT = Location.create(3158, 3425, 1);        // Juliet's spawn tile, first floor
    static final Location LAWRENCE_AT = Location.create(3253, 3487, 0);      // Father Lawrence's spawn tile, Varrock church
    static final Location APOTHECARY_AT = Location.create(3195, 3404, 0);    // the Apothecary's spawn tile
    static final Location BUSHES_AT = Location.create(3270, 3369, 0);        // cadava bushes south-east of Varrock (NOT verified in the map data)

    /**
     * P51 -Dquest.romeoFix (default off). Live 11-12/13: bots that come back to the quest at stage 20 or 60 skip Juliet and
     * the Apothecary on the stage alone, but Juliet's message (755) or the cadava potion (756) is no longer in the pack.
     * Romeo at stage 20 checks the PACK for 755 (RJCutscenePlugin.RomeoDialogue.open): without it he runs his 300..310
     * "Juliet?" chat and ends, so "give Romeo the message: dialogue ended with picks left [4]". Juliet at stage 60 checks the
     * pack for 756 (JulietDialogue.open): without it she only says hello, no cutscene starts and "watch Juliet drink it" runs
     * out its 700 ticks. Fix: a copy in the bank goes back into the pack (room made first); with none anywhere, Juliet
     * (stage 20, JulietDialogue 105..107) and the Apothecary (stage 60 with berries, ApothecaryDialogue.open) hand out a new
     * one, so those steps now count as done only with the item in the pack.
     */
    static final boolean FIX = Boolean.getBoolean("quest.romeoFix");
    static final int MESSAGE = 755, POTION = 756;

    /** At stage [from, to) and the item isn't in the pack: makes a slot and brings a banked copy back. True if it is in the pack now. */
    static boolean toPack(Player p, int id) {
        if (p.getInventory().getAmount(id) > 0) return true;
        if (p.getBank().getAmount(id) < 1) return false;
        QuestRunner.makeRoom(p, 1);
        if (p.getInventory().freeSlots() < 1 || !p.getBank().remove(new Item(id, 1))) return false;
        if (p.getInventory().add(new Item(id, 1))) return true;
        p.getBank().add(new Item(id, 1));
        return false;
    }

    static boolean atStage(Player p, int from, int to) { return stageAtLeast(Q, from).test(p) && !stageAtLeast(Q, to).test(p); }
    /** Stage at least [stage], and at [stage] itself the item is in the pack. */
    static java.util.function.Predicate<Player> holding(int stage, int next, int id) {
        return p -> stageAtLeast(Q, next).test(p) || stageAtLeast(Q, stage).test(p) && p.getInventory().getAmount(id) > 0;
    }

    static QuestSteps.Step fetch(String name, int stage, int next, int id) {
        return new QuestSteps.Custom(name, 2, null, r -> {
            Player p = r.bot;
            if (p.getInventory().getAmount(id) < 1 && p.getInventory().freeSlots() < 1) QuestRunner.makeRoom(p, 1); // a new copy needs a slot or it lands on the floor
            boolean banked = p.getInventory().getAmount(id) < 1 && p.getBank().getAmount(id) > 0;
            if (toPack(p, id)) { if (banked) r.log("  took " + id + " back out of the bank"); }
            else r.log("  " + id + " is gone: asking for a new one");
            return QuestSteps.St.DONE;
        }).done(p -> !atStage(p, stage, next) || p.getInventory().getAmount(id) > 0);
    }

    static QuestSteps.Talk longTalk(QuestSteps.Talk t) { t.limitTicks = 450; return t; }

    /** Clicks continue through a cutscene's dialogue until the stage lands. Never closes the dialogue: the scripts close and reopen it between screens. */
    static QuestSteps.Step cutscene(String name, int stage) {
        final int[] wait = {0};
        return new QuestSteps.Custom(name, 700, r -> wait[0] = 0, r -> {
            Player p = r.bot;
            if (stageAtLeast(Q, stage).test(p)) return QuestSteps.St.DONE;
            if (wait[0] > 0) { wait[0]--; return QuestSteps.St.RUNNING; }
            DialogueInterpreter di = p.getDialogueInterpreter();
            int box = DialogueDriver.chatboxId(p);
            if (di.getDialogue() != null && box > 0 && !DialogueDriver.isOptions(box) && di.activeTopics.isEmpty()) {
                try { di.handle(box, DialogueDriver.CONTINUE_CHILD); } catch (Throwable t) { return QuestSteps.St.FAILED; }
                wait[0] = 1 + (int) (Math.random() * 3);
            }
            return QuestSteps.St.RUNNING;
        }).done(stageAtLeast(Q, stage));
    }

    /**
     * P77 (-Dquest.romeoFix). Live step 18 "back to Romeo: time limit 800 ticks [last heading for the Ladder at [3187, 3414, 1]]":
     * a walk to Romeo from Juliet's floor asks the floor walker for a way down, and QuestFloors.Climber.pick scores stairs by
     * distance to the TARGET (Romeo, 53 tiles east) with only a small pull towards the bot, so it can pick a first-floor ladder
     * in another building that can't be walked to. This step's target is the ground under Juliet, so the climber picks her own
     * staircase; it ends the moment the bot is on the ground floor and the usual walk to Romeo follows.
     */
    static QuestSteps.Step downstairs(String name, int stage) {
        QuestSteps.Step w = new QuestSteps.Walk(name, Location.create(JULIET_AT.getX(), JULIET_AT.getY(), 0), 8)
                .done(p -> stageAtLeast(Q, stage).test(p) || p.getLocation().getZ() == 0);
        w.recheck = true;
        return w;
    }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "romeojuliet", "Romeo & Juliet").startAt(3211, 3425, 0).minutes(35).xp(0).unlock(40);
        s.step(new QuestSteps.Walk("walk to Romeo", ROMEO_AT, 4).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(ROMEO_AT) <= 4));
        s.step(longTalk(new QuestSteps.Talk("offer Romeo a hand", new int[]{ROMEO}, 2, 1)).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        if (FIX) s.step(fetch("find Juliet's message", 20, 30, MESSAGE));
        final java.util.function.Predicate<Player> msg = FIX ? holding(20, 30, MESSAGE) : stageAtLeast(Q, 20);
        s.step(new QuestSteps.Walk("walk up to Juliet", JULIET_AT, 3).done(p -> msg.test(p) || p.getLocation().getZ() == 1 && p.getLocation().getDistance(JULIET_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("take Juliet's message", new int[]{JULIET})).until(msg).done(msg));

        if (FIX) s.step(downstairs("down from Juliet's room", 30));
        s.step(new QuestSteps.Walk("back to Romeo", ROMEO_AT, 4).done(p -> stageAtLeast(Q, 30).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(ROMEO_AT) <= 4));
        s.step(longTalk(new QuestSteps.Talk("give Romeo the message", new int[]{ROMEO}, 4)).until(stageAtLeast(Q, 30)).done(stageAtLeast(Q, 30)));

        s.step(new QuestSteps.Walk("walk to the church", LAWRENCE_AT, 3).done(p -> stageAtLeast(Q, 40).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(LAWRENCE_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("ask Father Lawrence for help", new int[]{LAWRENCE})).until(stageAtLeast(Q, 40)).done(stageAtLeast(Q, 40)));

        s.step(new QuestSteps.Walk("walk to the Apothecary", APOTHECARY_AT, 3).done(p -> stageAtLeast(Q, 50).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(APOTHECARY_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("ask the Apothecary for the potion", new int[]{APOTHECARY})).until(stageAtLeast(Q, 50)).done(stageAtLeast(Q, 50)));

        if (FIX) s.step(fetch("find the cadava potion", 60, 70, POTION));
        final java.util.function.Predicate<Player> pot = FIX ? holding(60, 70, POTION) : stageAtLeast(Q, 60);
        s.step(new QuestSteps.Collect("get cadava berries", BERRIES, 1)
                .then(new QuestSteps.Walk("walk to the cadava bushes", BUSHES_AT, 6))
                .then(new QuestSteps.Interact("pick cadava berries", BUSHES, null, "Pick-from", BUSHES_AT, 12, false).until(has(BERRIES, 1)))
                .done(p -> has(BERRIES, 1).test(p) || pot.test(p)));

        s.step(new QuestSteps.Walk("back to the Apothecary", APOTHECARY_AT, 3).done(p -> pot.test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(APOTHECARY_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("hand over the berries", new int[]{APOTHECARY})).until(pot).done(pot));

        s.step(new QuestSteps.Walk("back up to Juliet", JULIET_AT, 3).done(p -> stageAtLeast(Q, 70).test(p) || p.getLocation().getZ() == 1 && p.getLocation().getDistance(JULIET_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("give Juliet the potion", new int[]{JULIET})).done(stageAtLeast(Q, 70)));
        s.step(cutscene("watch Juliet drink it", 70));

        if (FIX) s.step(downstairs("down from Juliet's room", 100));
        s.step(new QuestSteps.Walk("back to Romeo", ROMEO_AT, 4).done(p -> stageAtLeast(Q, 100).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(ROMEO_AT) <= 4));
        s.step(longTalk(new QuestSteps.Talk("tell Romeo the plan", new int[]{ROMEO})).done(stageAtLeast(Q, 100)));
        s.step(cutscene("watch Romeo find Juliet", 100));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(new QuestSteps.Walk("back to Varrock square", ROMEO_AT, 6).done(p -> p.getLocation().getZ() == 0 && p.getLocation().getDistance(ROMEO_AT) <= 6));
        return s;
    }
}
