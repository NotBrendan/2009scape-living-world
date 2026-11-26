package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.world.map.Location;

import static content.custom.QuestScript.stageAtLeast;

/**
 * Rune Mysteries: Duke Horacio (741, Lumbridge castle first floor) gives the air talisman, Sedridor (300, Wizards' Tower
 * basement) wants it delivered, then a research package goes to Aubury (553, Varrock rune shop), his notes come back to
 * Sedridor. Stages (RuneMysteries.java / WizardTowerPlugin.java): 10 talisman given, 20 talisman handed to Sedridor,
 * 30 package in hand, 40 package delivered, 50 notes in hand, 100 done.
 * Duke (DukeHoracioDialogue.kt, DukeHoracioRMDialogue.kt): options 1 ("Have you any quests for me?") then, in the quest
 * file, options 1 ("Sure, no problem."): stage 10 and the air talisman (1438).
 * Sedridor at stage 10 (SedridorDialogue in WizardTowerPlugin.java): options 3 ("I'm looking for the head wizard."), then
 * 1 ("Ok, here you are.") and 1 ("Yes, certainly."): talisman taken, package (290) given, stage 30. If a bot is at stage 20
 * (talisman handed over, "No, I'm busy." chosen earlier) the conversation resumes at "Yes, certainly.": option 1 only.
 * Aubury at stage 30 (AuburyDialoguePlugin): options 3 ("I have been sent here with a package for you.") then continue
 * only: stage 40. Talking again (stage 40) is continue only: notes (291) given, stage 50.
 * Sedridor at stage 50: continue only (the notes are taken and the air talisman handed back, stage 100): 1 QP, the
 * Runecrafting skill, access to the rune essence (Sedridor and Aubury teleport you to the mine afterwards).
 * NPC spawns from data/configs/npc_spawns.json: 741 {3212,3220,1}, 300 {3105,9569,0}, 553 {3253,3403,0}.
 */
final class QuestRuneMysteries {
    private QuestRuneMysteries() { }

    static final content.data.Quests Q = content.data.Quests.RUNE_MYSTERIES;
    static final int DUKE = 741, SEDRIDOR = 300, AUBURY = 553;
    static final int TALISMAN = 1438, PACKAGE = 290, NOTES = 291;
    static final Location DUKE_AT = Location.create(3212, 3220, 1);          // the Duke's spawn tile (castle first floor)
    static final Location SEDRIDOR_AT = Location.create(3105, 9569, 0);      // his spawn tile in the tower basement
    static final Location AUBURY_AT = Location.create(3253, 3403, 0);        // his spawn tile inside the Varrock rune shop
    static final Location TOWER_OUT = Location.create(3109, 3162, 0);        // outside the tower, east of the door at 3107,3162

    static boolean underground(Player p) { return p.getLocation().getY() > 6400; }

    /** Sedridor's lines are long: more than the usual 150 ticks. */
    static QuestSteps.Talk longTalk(QuestSteps.Talk t) { t.limitTicks = 450; return t; }

    /**
     * P77 (quest-only step fix, no flag). Live 11-13..15: bots that come back to the quest find the item the next talk needs
     * in the BANK (AFK trips bank the pack between runs). Sedridor at stage 10 (SedridorDialogue 51) removes 1438 from the
     * pack and ends the chat when it isn't there: "dialogue ended with picks left [1]". Aubury at stage 30 checks the pack for
     * 290 ("I kind of don't have it with me", stage stays 30): "dialogue ended but its condition didn't hold". Sedridor at
     * 50 checks the pack for 291. A banked copy goes back into the pack (a slot made first). With none anywhere the
     * server's own lost-item lines take over (Sedridor re-sends the package at 30, Aubury has duplicate notes at 50).
     */
    static QuestSteps.Step fetch(String name, int from, int to, int id) {
        return new QuestSteps.Custom(name, 2, null, r -> {
            Player p = r.bot;
            if (p.getInventory().getAmount(id) > 0) return QuestSteps.St.DONE;
            if (p.getBank().getAmount(id) < 1) { r.log("  " + id + " is in neither pack nor bank"); return QuestSteps.St.DONE; }
            if (p.getInventory().freeSlots() < 1) QuestRunner.makeRoom(p, 1);
            if (p.getInventory().freeSlots() > 0 && p.getBank().remove(new Item(id, 1))) {
                if (p.getInventory().add(new Item(id, 1))) r.log("  took " + id + " back out of the bank");
                else p.getBank().add(new Item(id, 1));
            }
            return QuestSteps.St.DONE;
        }).done(p -> !(stageAtLeast(Q, from).test(p) && !stageAtLeast(Q, to).test(p)) || p.getInventory().getAmount(id) > 0);
    }

    /** P77: his rune-essence speech after the notes (SedridorDialogue 900..930+) ran past 450 ticks in 2 of 24 trial runs. */
    static QuestSteps.Talk notesTalk(QuestSteps.Talk t) { t.limitTicks = 900; return t; }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "runemysteries", "Rune Mysteries").startAt(3212, 3220, 1).minutes(35).items(TALISMAN).xp(0).unlock(150);
        s.step(new QuestSteps.Walk("walk up to Duke Horacio", DUKE_AT, 3).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 1 && p.getLocation().getDistance(DUKE_AT) <= 3));
        s.step(longTalk(new QuestSteps.Talk("ask the Duke for a quest", new int[]{DUKE}, 1, 1)).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        s.step(new QuestSteps.Walk("walk down to Sedridor", SEDRIDOR_AT, 3).done(p -> stageAtLeast(Q, 30).test(p) || underground(p) && p.getLocation().getDistance(SEDRIDOR_AT) <= 3));
        s.step(fetch("find the talisman", 10, 20, TALISMAN));
        s.step(longTalk(new QuestSteps.Talk("give Sedridor the talisman", new int[]{SEDRIDOR}).picks(p -> stageAtLeast(Q, 20).test(p) ? new Object[]{1} : new Object[]{3, 1, 1}))
                .until(stageAtLeast(Q, 30)).done(stageAtLeast(Q, 30)));

        s.step(new QuestSteps.Walk("walk to Aubury's rune shop", AUBURY_AT, 3).done(p -> stageAtLeast(Q, 50).test(p) || p.getLocation().getZ() == 0 && !underground(p) && p.getLocation().getDistance(AUBURY_AT) <= 3));
        s.step(fetch("find the package", 30, 40, PACKAGE));
        s.step(longTalk(new QuestSteps.Talk("give Aubury the package", new int[]{AUBURY}, 3)).until(stageAtLeast(Q, 40)).done(stageAtLeast(Q, 40)));
        s.step(longTalk(new QuestSteps.Talk("get Aubury's notes", new int[]{AUBURY})).until(stageAtLeast(Q, 50)).done(stageAtLeast(Q, 50)));

        s.step(new QuestSteps.Walk("back down to Sedridor", SEDRIDOR_AT, 3).done(p -> stageAtLeast(Q, 100).test(p) || underground(p) && p.getLocation().getDistance(SEDRIDOR_AT) <= 3));
        s.step(fetch("find the notes", 50, 100, NOTES));
        s.step(notesTalk(new QuestSteps.Talk("give Sedridor the notes", new int[]{SEDRIDOR})).until(stageAtLeast(Q, 100)).done(stageAtLeast(Q, 100)));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(new QuestSteps.Walk("back out of the tower", TOWER_OUT, 3).done(p -> !underground(p) && p.getLocation().getZ() == 0 && p.getLocation().getDistance(TOWER_OUT) <= 3));
        return s;
    }
}
