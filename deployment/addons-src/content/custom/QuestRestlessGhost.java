package content.custom;

import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import static content.custom.QuestScript.attrInt;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * The Restless Ghost: Father Aereck (456, Lumbridge church, spawn 3244,3205) -> Father Urhney (458, swamp shack,
 * spawn 3148,3175) for the Amulet of ghostspeak (552) -> the ghost (457) by the coffin in the graveyard shed -> the
 * skull (964) on the altar in the Wizards' Tower basement -> the skull on the open coffin.
 * Start (FatherAereckDialogue): options 4 ("I'm looking for a quest.") then 1 ("Ok, let me help then."): stage 10.
 * Urhney (FatherUhrneyDialogue) at stage 10: option 2 ("Father Aereck sent me to talk to you."): the amulet, stage 20,
 * /save:restless-ghost:urhney (he needs a free slot or he ends the talk). A lost amulet: option 2 ("I've lost the
 * Amulet of Ghostspeak.") gives a spare unless he sees one in the pack, worn or in the bank.
 * The ghost (RestlessGhostDialogue) only talks if 552 is WORN (getEquipment().contains); continue only: stage 30.
 * The ghost is one shared NPC at 3250,3195, invisible until someone opens the closed coffin 2145 (it shows for 100
 * ticks); open swaps it permanently to 15061 (RestlessGhostPlugin). The altar (15050, or 15051 once varp 728 = 5)
 * "Search" at stage 30 gives the skull, sets stage 40 and spawns skeleton 459 (level 13, aggressive, only attackable
 * by this player, cleared when the player is 16+ tiles away) at 3120,9568. Skull on 2145 says "Maybe I should open it
 * first."; on 15061 or 15052 it finishes the quest (RestlessGhostSkull, a legacy UseWithHandler): 1125 Prayer XP, 1 QP.
 * Basement: ladder 2147 (3104,3162) Climb-down -> 3104,9576; ladder 32015 (3103,9576) Climb-up -> 3105,3162 (WizardTowerPlugin).
 */
final class QuestRestlessGhost {
    private QuestRestlessGhost() { }

    static final content.data.Quests Q = content.data.Quests.THE_RESTLESS_GHOST;
    static final int AERECK = 456, GHOST = 457, URHNEY = 458, SKELETON = 459, AMULET = 552, SKULL = 964;
    static final int CLOSED_COFFIN = 2145, OPEN_COFFIN = 15061;
    static final int[] OPEN_COFFINS = {15061, 15052};                     // RestlessGhostSkull handlers (2145 only asks to open it)
    static final int[] ALTARS = {15050, 15051};                           // RestlessGhostPlugin COFFIN_IDS "search"
    static final int NECK = 2;                                            // equipment slot
    static final String OLD_NECK = "quest-rg:old-neck";                   // the amulet it wore before (worn again on the way out)
    static final Location CHURCH = Location.create(3243, 3207, 0);       // inside the church, by Aereck (spawn 3244,3205, doesn't walk)
    static final Location SHACK = Location.create(3147, 3175, 0);        // Urhney's shack, west of Lumbridge swamp (spawn 3148,3175, walks 1)
    static final Location SHED = Location.create(3249, 3194, 0);         // the graveyard shed (ghost 3250,3195; the coffin is next to it)
    static final Location TOWER = Location.create(3105, 3161, 0);        // inside the Wizards' Tower, by the ladder 2147 at 3104,3162
    static final Location BASEMENT_LADDER = Location.create(3104, 9575, 0); // 32015 at 3103,9576
    static final Location ALTAR = Location.create(3118, 9567, 0);        // the altar room (the skeleton spawns at 3120,9568)

    static boolean wearing(Player p) { return p.getEquipment().contains(AMULET, 1); }
    static boolean under(Player p) { return p.getLocation().getY() > 6400; }

    /** The skeleton the altar woke for this bot: alive and close. */
    static boolean skeletonGone(Player p) {
        try {
            for (NPC n : RegionManager.getLocalNpcs(p, 16)) if (n != null && n.getId() == SKELETON && n.isActive() && !n.isInvisible() && n.getAttribute("player", null) == p) return false;
        } catch (Throwable ignored) { }
        return true;
    }

    static boolean coffinOpen(Player p) { return QuestSteps.findScenery(p, SHED, 8, OPEN_COFFINS, null, null) != null; }

    static boolean ghostShowing(Player p) {
        try {
            for (NPC n : RegionManager.getLocalNpcs(SHED, 10)) if (n != null && n.getId() == GHOST && n.isActive() && !n.isInvisible() && !n.isHidden(p)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Wear an item from the pack as the client's Wear click does (EquipHandler), the bots' own wear as the fallback. */
    static boolean equip(Player p, int id) {
        Item it = null;
        for (int i = 0; i < 28; i++) { Item x = p.getInventory().get(i); if (x != null && x.getId() == id) { it = x; break; } }
        if (it == null) return p.getEquipment().contains(id, 1);
        try { InteractionListeners.run(id, IntType.ITEM, "wear", p, it); } catch (Throwable ignored) { }
        if (!p.getEquipment().contains(id, 1)) SmartAdventurers.wear(p, id);
        return p.getEquipment().contains(id, 1);
    }

    static QuestSteps.St wearAmulet(QuestRunner r) {
        Player p = r.bot;
        if (wearing(p)) return QuestSteps.St.DONE;
        if (p.getInventory().getAmount(AMULET) == 0) return QuestSteps.St.FAILED;
        Item old = p.getEquipment().get(NECK);
        if (old != null && old.getId() != AMULET) p.setAttribute(OLD_NECK, old.getId());
        if (equip(p, AMULET)) { r.log("  wearing the amulet of ghostspeak" + (old != null ? " (took off " + MarketBrain.name(old.getId()) + ")" : "")); return QuestSteps.St.DONE; }
        return QuestSteps.St.FAILED;
    }

    /**
     * Open the coffin so the ghost shows (it only appears when the closed coffin is opened). The server never shuts it
     * again (15061 has no working Close), so a coffin someone left open is tried once with Close, then the bot talks to
     * the ghost where it stands: forSkull = just needs it open for the skull.
     */
    static QuestSteps.Step openCoffin(String name, boolean forSkull) {
        final int[] st = {0, 0}; // clicks, wait
        return new QuestSteps.Custom(name, 200, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (forSkull ? coffinOpen(p) : ghostShowing(p)) return QuestSteps.St.DONE;
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (st[0] >= 5) {
                if (coffinOpen(p)) { r.log("  the coffin was left open and won't shut: talking to the ghost where it stands"); return QuestSteps.St.DONE; }
                return QuestSteps.St.FAILED;
            }
            Scenery closed = QuestSteps.findScenery(p, SHED, 8, new int[]{CLOSED_COFFIN}, null, "Open");
            if (closed != null) { QuestSteps.clearPopups(p); QuestSteps.click(p, closed, "Open", IntType.SCENERY); st[0]++; st[1] = 4; return QuestSteps.St.RUNNING; }
            Scenery open = QuestSteps.findScenery(p, SHED, 8, new int[]{OPEN_COFFIN}, null, "Close");
            if (open != null && st[0] < 2) { QuestSteps.clearPopups(p); QuestSteps.click(p, open, "Close", IntType.SCENERY); st[0]++; st[1] = 4; return QuestSteps.St.RUNNING; }
            st[0] = 5;
            return QuestSteps.St.RUNNING;
        });
    }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "restlessghost", "The Restless Ghost").startAt(3243, 3207, 0).minutes(25).xp(1125 * 0.5).unlock(5).level(Skills.HITPOINTS, 15);
        s.step(new QuestSteps.Walk("walk into Lumbridge church", CHURCH, 2).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(CHURCH) <= 2));
        s.step(new QuestSteps.Talk("ask Father Aereck for a quest", new int[]{AERECK}, 4, 1).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        s.step(new QuestSteps.Walk("walk to Father Urhney's shack", SHACK, 2).done(p -> stageAtLeast(Q, 20).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(SHACK) <= 2));
        s.step(new QuestSteps.Custom("make room for the amulet", 5, null, r -> { QuestRunner.makeRoom(r.bot, 1); return QuestSteps.St.DONE; }).done(stageAtLeast(Q, 20)));
        s.step(new QuestSteps.Talk("tell Urhney that Aereck sent me", new int[]{URHNEY}, 2).until(stageAtLeast(Q, 20)).done(stageAtLeast(Q, 20)));
        s.step(new QuestSteps.Collect("get the amulet of ghostspeak", AMULET, 1)
                .then(new QuestSteps.Walk("back to Urhney's shack", SHACK, 2))
                .then(new QuestSteps.Custom("make room for the amulet", 5, null, r -> { QuestRunner.makeRoom(r.bot, 1); return QuestSteps.St.DONE; }))
                .then(new QuestSteps.Talk("tell Urhney I lost the amulet", new int[]{URHNEY}, 2).until(has(AMULET, 1)))
                .done(p -> has(AMULET, 1).test(p) || wearing(p) || stageAtLeast(Q, 30).test(p)));
        s.step(new QuestSteps.Custom("wear the amulet of ghostspeak", 20, null, QuestRestlessGhost::wearAmulet).done(p -> wearing(p) || stageAtLeast(Q, 30).test(p)));

        s.step(new QuestSteps.Walk("walk to the graveyard shed", SHED, 2).done(p -> stageAtLeast(Q, 30).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(SHED) <= 2));
        s.step(openCoffin("open the coffin", false).done(stageAtLeast(Q, 30)));
        s.step(new QuestSteps.Talk("talk to the ghost", new int[]{GHOST}).until(stageAtLeast(Q, 30)).done(stageAtLeast(Q, 30)));

        s.step(new QuestSteps.Collect("get the ghost's skull", SKULL, 1)
                .then(new QuestSteps.Walk("walk into the Wizards' Tower", TOWER, 4).done(p -> under(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(TOWER) <= 4))
                .then(new QuestSteps.Interact("down the ladder to the basement", new int[]{2147}, null, "Climb-down", TOWER, 10, false).until(QuestRestlessGhost::under).done(QuestRestlessGhost::under))
                .then(new QuestSteps.Walk("walk to the altar", ALTAR, 3))
                .then(new QuestSteps.Interact("search the altar", ALTARS, null, "Search", ALTAR, 10, false).until(has(SKULL, 1)))
                .done(p -> has(SKULL, 1).test(p) || stageAtLeast(Q, 100).test(p)));
        s.step(new QuestSteps.Kill("fight off the skeleton", new String[]{"Skeleton"}, new int[0], 1, ALTAR).until(QuestRestlessGhost::skeletonGone).limit(600).done(QuestRestlessGhost::skeletonGone).optional());
        s.step(new QuestSteps.Walk("back to the basement ladder", BASEMENT_LADDER, 2).done(p -> !under(p) || p.getLocation().getDistance(BASEMENT_LADDER) <= 2));
        s.step(new QuestSteps.Interact("up the ladder", new int[]{32015}, null, "Climb-up", BASEMENT_LADDER, 8, false).until(p -> !under(p)).done(p -> !under(p)));

        s.step(new QuestSteps.Walk("back to the graveyard shed", SHED, 2));
        s.step(openCoffin("open the coffin for the skull", true).done(p -> coffinOpen(p) || stageAtLeast(Q, 100).test(p)));
        s.step(new QuestSteps.UseOn("put the skull in the coffin", SKULL, OPEN_COFFINS, null, IntType.SCENERY, SHED, 8).until(stageAtLeast(Q, 100)));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(new QuestSteps.Custom("put the old amulet back on", 20, null, r -> {
            Player p = r.bot;
            int old = attrInt(p, OLD_NECK);
            if (old > 0 && p.getInventory().getAmount(old) > 0) equip(p, old);
            p.removeAttribute(OLD_NECK);
            return QuestSteps.St.DONE;
        }));
        return s;
    }
}
