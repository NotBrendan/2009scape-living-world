package content.custom;

import core.game.dialogue.DialogueInterpreter;
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

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "romeojuliet", "Romeo & Juliet").startAt(3211, 3425, 0).minutes(35).xp(0).unlock(40);
        s.step(new QuestSteps.Walk("walk to Romeo", ROMEO_AT, 4).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(ROMEO_AT) <= 4));
        s.step(longTalk(new QuestSteps.Talk("offer Romeo a hand", new int[]{ROMEO}, 2, 1)).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        s.step(new QuestSteps.Walk("walk up to Juliet", JULIET_AT, 3).done(p -> stageAtLeast(Q, 20).test(p) || p.getLocation().getZ() == 1 && p.getLocation().getDistance(JULIET_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("take Juliet's message", new int[]{JULIET})).until(stageAtLeast(Q, 20)).done(stageAtLeast(Q, 20)));

        s.step(new QuestSteps.Walk("back to Romeo", ROMEO_AT, 4).done(p -> stageAtLeast(Q, 30).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(ROMEO_AT) <= 4));
        s.step(longTalk(new QuestSteps.Talk("give Romeo the message", new int[]{ROMEO}, 4)).until(stageAtLeast(Q, 30)).done(stageAtLeast(Q, 30)));

        s.step(new QuestSteps.Walk("walk to the church", LAWRENCE_AT, 3).done(p -> stageAtLeast(Q, 40).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(LAWRENCE_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("ask Father Lawrence for help", new int[]{LAWRENCE})).until(stageAtLeast(Q, 40)).done(stageAtLeast(Q, 40)));

        s.step(new QuestSteps.Walk("walk to the Apothecary", APOTHECARY_AT, 3).done(p -> stageAtLeast(Q, 50).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(APOTHECARY_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("ask the Apothecary for the potion", new int[]{APOTHECARY})).until(stageAtLeast(Q, 50)).done(stageAtLeast(Q, 50)));

        s.step(new QuestSteps.Collect("get cadava berries", BERRIES, 1)
                .then(new QuestSteps.Walk("walk to the cadava bushes", BUSHES_AT, 6))
                .then(new QuestSteps.Interact("pick cadava berries", BUSHES, null, "Pick-from", BUSHES_AT, 12, false).until(has(BERRIES, 1)))
                .done(p -> has(BERRIES, 1).test(p) || stageAtLeast(Q, 60).test(p)));

        s.step(new QuestSteps.Walk("back to the Apothecary", APOTHECARY_AT, 3).done(p -> stageAtLeast(Q, 60).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(APOTHECARY_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("hand over the berries", new int[]{APOTHECARY})).until(stageAtLeast(Q, 60)).done(stageAtLeast(Q, 60)));

        s.step(new QuestSteps.Walk("back up to Juliet", JULIET_AT, 3).done(p -> stageAtLeast(Q, 70).test(p) || p.getLocation().getZ() == 1 && p.getLocation().getDistance(JULIET_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("give Juliet the potion", new int[]{JULIET})).done(stageAtLeast(Q, 70)));
        s.step(cutscene("watch Juliet drink it", 70));

        s.step(new QuestSteps.Walk("back to Romeo", ROMEO_AT, 4).done(p -> stageAtLeast(Q, 100).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(ROMEO_AT) <= 4));
        s.step(longTalk(new QuestSteps.Talk("tell Romeo the plan", new int[]{ROMEO})).done(stageAtLeast(Q, 100)));
        s.step(cutscene("watch Romeo find Juliet", 100));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(new QuestSteps.Walk("back to Varrock square", ROMEO_AT, 6).done(p -> p.getLocation().getZ() == 0 && p.getLocation().getDistance(ROMEO_AT) <= 6));
        return s;
    }
}
