package content.custom;

import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.map.Location;

import static content.custom.QuestScript.attrInt;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * The Knight's Sword: the Squire (606, Falador castle courtyard, spawn 2974,3343,0) -> Reldo (2660/2661, Varrock palace
 * library, spawn 3208,3496,0) -> Thurgo (604, Mudskipper peninsula south of Port Sarim, spawn 3001,3144,0) with a
 * redberry pie -> the Squire -> the portrait (666) from Sir Vyvin's cupboard -> Thurgo -> blurite ore (668) from the
 * Asgarnian Ice Dungeon + 2 iron bars (2351) -> Thurgo makes the blurite sword (667) -> the Squire.
 * NPC spawns: data/configs/npc_spawns.json (Sir Vyvin 605 at 2985,3343,2 walks 2; he plays no part, see the cupboard).
 *
 * Stages (TheKnightsSword.java journal, FaladorSquireDialogue.java, ReldoDialogue.java, ThuroDialogue.java):
 * 10 Squire asked, 20 Reldo told us about the Imcando, 30 Thurgo ate the pie, 40 Thurgo wants a picture, 50 the
 * Squire told us about the portrait, 60 Thurgo has the portrait (wants 2 iron bars + blurite ore), 100 done.
 *
 * Dialogue picks (1-based option buttons):
 * - Squire (FaladorSquireDialogue): every talk opens "Chat / Talk about the Falador Achievement Diary": 1 ("Chat").
 *   Stage 0: then 1 ("And how is life as a squire?"), 2 ("I can make a new sword if you like..."), 1 ("So would these
 *   dwarves make another one?"), 1 ("Ok, I'll give it a go.") -> stage 10. Stage 40: 1 then continue -> 50. Stage 60 with
 *   the sword in the PACK (containsItem): 1 then continue -> the sword is taken and quest.finish.
 * - Reldo (ReldoDialogue) at Knight's Sword stage 10: 1 ("Hello stranger.", also option 1 in his 3-option Lost Tribe
 *   menu), then 3 ("What do you know about Imcando dwarves?") -> stage 20 at the end (case 48).
 * - Thurgo (ThuroDialogue) opens "Skillcape of Smithing. / Something else.": 2 ("Something else.") reaches the quest.
 *   Stage 20 with a redberry pie (2325) in the pack: then 2 ("Would you like some redberry pie?") -> pie taken, stage 30.
 *   Stage 30: 2 then continue -> 40. Stage 50 with the portrait in the pack: 2 then continue -> portrait taken, 60.
 *   Stage 60 with 668 and 2 x 2351 in the PACK: 2 then continue -> both taken, the sword 667 handed over.
 *
 * Portrait (FaladorNodePlugin): cupboard 2271 "Open" turns it into 2272 (shared, SceneryBuilder.replace); 2272 "Search"
 * gives the portrait if none is in the pack. There is NO check on Sir Vyvin (or the quest stage) in this server, so the
 * bot just opens and searches. Cupboard 2271 at 2984,3336,2 (cache map, MapDump); the castle's ladders 11727 (z0
 * 2994,3341 / 2983,3351) and staircase 11734 (2984,3337,1) are left to the floor walker.
 * Blurite (MiningNode / SkillingResource): rocks 33220/33221, Mining 10, ore 668. In the cache map the only blurite
 * rocks near Thurgo are in the Asgarnian Ice Dungeon: 33220 at 3049,9566 / 3059,9564 / 3067,9582, 33221 at 3067,9581,
 * among aggressive ice giants (111: 70 hp, att/str/def 40) and ice warriors (125: 59 hp, att/str/def 47) from
 * npc_spawns.json, with hobgoblins (122), pirates (184) and muggers (175) between them and the ladder. Way in: trapdoor
 * 9472 "Climb-down" at 3008,3150,0 (no handler of its own: ClimbActionHandler's trapdoor rule) to the ladder 32015
 * "Climb-up" at 3008,9550,0. Thurgo's "under this cliff" cave is this dungeon here.
 * Redberry pie (shops.json): only Romily Weaklax (3205, shop 158) sells it, in the Cooks' Guild (spawn 3139,3448,0);
 * ChefGuildDoorPlugin door 2712 (3143,3443) wants Cooking 32 and a chef's hat (1949, Fancy Clothes Store shop 59,
 * owner 554 at 3281,3398) worn. Hence the Cooking 32 requirement below. Iron bars: Drogo's Mining Emporium (shop 125,
 * Drogo 579 at 3034,9845 in the Dwarven Mine; ladder 30942 3019,3450 -> 3018,9850 in the stair survey).
 * Requirements: Mining 10 (journal, and the blurite rock level). Reward (TheKnightsSword.finish): 1 QP, 12,725 Smithing XP.
 */
final class QuestKnightsSword {
    private QuestKnightsSword() { }

    static final content.data.Quests Q = content.data.Quests.THE_KNIGHTS_SWORD;
    static final int SQUIRE = 606, THURGO = 604;
    static final int[] RELDO = {2660, 2661};                              // ReldoDialogue.getIds
    static final int PIE = 2325, PORTRAIT = 666, SWORD = 667, ORE = 668, BAR = 2351, CHEFS_HAT = 1949;
    static final int CUPBOARD_SHUT = 2271, CUPBOARD_OPEN = 2272, TRAPDOOR = 9472, LADDER_UP = 32015;
    static final int HEAD = 0;                                           // EquipmentContainer.SLOT_HAT
    static final String OLD_HEAD = "quest-ks:old-head";
    static final Location SQUIRE_AT = Location.create(2974, 3343, 0);     // his spawn tile, castle courtyard (walks 1)
    static final Location RELDO_AT = Location.create(3208, 3496, 0);      // his spawn tile, palace library (walks 1)
    static final Location THURGO_AT = Location.create(3001, 3144, 0);     // his spawn tile (doesn't walk)
    static final Location CUPBOARD_AT = Location.create(2984, 3336, 2);   // 2271, Sir Vyvin's room
    static final Location CUPBOARD_SIDE = Location.create(2985, 3336, 2); // east of it (NOT verified walkable: the Interact click paths the rest)
    static final Location FANCY_DRESS = Location.create(3281, 3398, 0);   // Fancy-dress shop owner 554 (sells 1949, shops.json 59)
    static final Location GUILD_IN = Location.create(3141, 3446, 0);      // inside the Cooks' Guild, north of the door 2712 at 3143,3443
    static final Location ROMILY_AT = Location.create(3139, 3448, 0);     // Romily Weaklax 3205 (sells 2325, shops.json 158)
    static final Location DROGO_AT = Location.create(3034, 9845, 0);      // Drogo 579 (sells 2351, shops.json 125)
    static final Location TRAPDOOR_AT = Location.create(3008, 3150, 0);
    static final Location TRAPDOOR_SIDE = Location.create(3008, 3152, 0);
    static final Location LADDER_AT = Location.create(3008, 9550, 0);
    static final Location LADDER_SIDE = Location.create(3008, 9552, 0);
    static final Location BLURITE = Location.create(3058, 9571, 0);       // middle of the four blurite rocks (all within 22 of it)

    static boolean under(Player p) { return p.getLocation().getY() > 6400; }
    /** The Asgarnian Ice Dungeon (y about 9540-9600), not the Dwarven Mine (y 9740+) where Drogo sells the iron bars. */
    static boolean inIce(Player p) { return under(p) && p.getLocation().getY() < 9700; }
    static boolean done(Player p) { return stageAtLeast(Q, 100).test(p); }
    static boolean swordInHand(Player p) { return has(SWORD, 1).test(p) || p.getBank().contains(SWORD, 1) || p.getEquipment().contains(SWORD, 1); }

    /** Wear an item as the client's Wear click does, the bots' own wear as the fallback (as QuestRestlessGhost.equip). */
    static boolean equip(Player p, int id) {
        Item it = null;
        for (int i = 0; i < 28; i++) { Item x = p.getInventory().get(i); if (x != null && x.getId() == id) { it = x; break; } }
        if (it == null) return p.getEquipment().contains(id, 1);
        try { InteractionListeners.run(id, IntType.ITEM, "wear", p, it); } catch (Throwable ignored) { }
        if (!p.getEquipment().contains(id, 1)) SmartAdventurers.wear(p, id);
        return p.getEquipment().contains(id, 1);
    }

    static QuestSteps.St wearHat(QuestRunner r) {
        Player p = r.bot;
        if (p.getEquipment().contains(CHEFS_HAT, 1)) return QuestSteps.St.DONE;
        if (p.getInventory().getAmount(CHEFS_HAT) == 0) return QuestSteps.St.FAILED;
        Item old = p.getEquipment().get(HEAD);
        if (old != null && old.getId() != CHEFS_HAT) p.setAttribute(OLD_HEAD, old.getId());
        if (equip(p, CHEFS_HAT)) { r.log("  wearing a chef's hat for the Cooks' Guild"); return QuestSteps.St.DONE; }
        return QuestSteps.St.FAILED;
    }

    static QuestSteps.St oldHatBack(QuestRunner r) {
        Player p = r.bot;
        int old = attrInt(p, OLD_HEAD);
        if (old > 0 && p.getInventory().getAmount(old) > 0) equip(p, old);
        p.removeAttribute(OLD_HEAD);
        return QuestSteps.St.DONE;
    }

    static QuestSteps.Talk talk(QuestSteps.Talk t) { t.limitTicks = 450; return t; } // the Squire and Reldo talk at length

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "knightssword", "The Knight's Sword").startAt(2974, 3343, 0).minutes(50).xp(12725 * 0.5).unlock(10)
                .level(Skills.MINING, 10)
                .level(Skills.COOKING, 32)      // the pie: Romily's shop in the Cooks' Guild is the only seller (see above)
                // the blurite rocks stand among ice giants and ice warriors (both aggressive): enough to mine one ore and walk out
                .level(Skills.ATTACK, 40).level(Skills.STRENGTH, 40).level(Skills.DEFENCE, 40).level(Skills.HITPOINTS, 40); // the ice dungeon (raised after a trial death)

        // the Squire
        s.step(new QuestSteps.Walk("walk to the Squire in Falador castle", SQUIRE_AT, 3).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(SQUIRE_AT) <= 3));
        s.step(talk(new QuestSteps.Talk("offer the Squire a new sword", new int[]{SQUIRE}, 1, 1, 2, 1, 1)).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        // Reldo
        s.step(new QuestSteps.Walk("walk to Reldo in the palace library", RELDO_AT, 3).done(p -> stageAtLeast(Q, 20).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(RELDO_AT) <= 3));
        s.step(talk(new QuestSteps.Talk("ask Reldo about the Imcando dwarves", RELDO, 1, 3)).until(stageAtLeast(Q, 20)).done(stageAtLeast(Q, 20)));

        // a redberry pie: the bank or bot stock, else Romily in the Cooks' Guild (chef's hat on)
        s.step(new QuestSteps.Collect("get a redberry pie", PIE, 1).stock()
                .then(new QuestSteps.Collect("get a chef's hat", CHEFS_HAT, 1).stock()
                        .buy(new QuestSteps.Buy("buy a chef's hat", CHEFS_HAT, 1, "the Varrock fancy-dress shop", FANCY_DRESS))
                        .done(p -> has(CHEFS_HAT, 1).test(p) || p.getEquipment().contains(CHEFS_HAT, 1)))
                .then(new QuestSteps.Custom("put the chef's hat on", 20, null, QuestKnightsSword::wearHat).done(p -> p.getEquipment().contains(CHEFS_HAT, 1)))
                .then(new QuestSteps.Walk("into the Cooks' Guild", GUILD_IN, 2))
                .buy(new QuestSteps.Buy("buy a redberry pie from Romily", PIE, 1, "Romily Weaklax's pie shop", ROMILY_AT))
                .done(p -> has(PIE, 1).test(p) || stageAtLeast(Q, 30).test(p)));
        s.step(new QuestSteps.Custom("take the chef's hat off", 20, null, QuestKnightsSword::oldHatBack).done(p -> attrInt(p, OLD_HEAD) == 0));

        // Thurgo: the pie, then the sword
        s.step(new QuestSteps.Walk("walk to Thurgo", THURGO_AT, 2).done(p -> stageAtLeast(Q, 40).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(THURGO_AT) <= 2));
        s.step(new QuestSteps.Talk("give Thurgo the redberry pie", new int[]{THURGO}, 2, 2).until(stageAtLeast(Q, 30)).done(stageAtLeast(Q, 30)));
        s.step(new QuestSteps.Talk("ask Thurgo to make the sword", new int[]{THURGO}, 2).until(stageAtLeast(Q, 40)).done(stageAtLeast(Q, 40)));

        // back to the Squire for the portrait's whereabouts
        s.step(new QuestSteps.Walk("back to the Squire", SQUIRE_AT, 3).done(p -> stageAtLeast(Q, 50).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(SQUIRE_AT) <= 3));
        s.step(talk(new QuestSteps.Talk("ask the Squire for a picture of the sword", new int[]{SQUIRE}, 1)).until(stageAtLeast(Q, 50)).done(stageAtLeast(Q, 50)));

        // the portrait from Sir Vyvin's cupboard (second floor)
        s.step(new QuestSteps.Collect("get the portrait", PORTRAIT, 1)
                .then(new QuestSteps.Walk("up to Sir Vyvin's room", CUPBOARD_SIDE, 2))
                .then(new QuestSteps.Custom("make room for the portrait", 5, null, r -> { QuestRunner.makeRoom(r.bot, 1); return QuestSteps.St.DONE; }))
                .then(new QuestSteps.Interact("open Sir Vyvin's cupboard", new int[]{CUPBOARD_SHUT}, null, "Open", CUPBOARD_AT, 4, false)
                        .until(p -> has(PORTRAIT, 1).test(p) || QuestSteps.findScenery(p, CUPBOARD_AT, 2, new int[]{CUPBOARD_OPEN}, null, "Search") != null))
                .then(new QuestSteps.Interact("search the cupboard", new int[]{CUPBOARD_OPEN}, null, "Search", CUPBOARD_AT, 4, false).picks().until(has(PORTRAIT, 1)))
                .done(p -> has(PORTRAIT, 1).test(p) || stageAtLeast(Q, 60).test(p)));

        // Thurgo gets the portrait (the floor walker takes the bot back down)
        s.step(new QuestSteps.Walk("back to Thurgo", THURGO_AT, 2).done(p -> stageAtLeast(Q, 60).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(THURGO_AT) <= 2));
        s.step(new QuestSteps.Talk("give Thurgo the portrait", new int[]{THURGO}, 2).until(stageAtLeast(Q, 60)).done(stageAtLeast(Q, 60)));

        // two iron bars: the bank or bot stock, else Drogo's in the Dwarven Mine
        s.step(new QuestSteps.Collect("get 2 iron bars", BAR, 2).stock()
                .buy(new QuestSteps.Buy("buy 2 iron bars from Drogo", BAR, 2, "Drogo's Mining Emporium", DROGO_AT))
                .done(p -> has(BAR, 2).test(p) || swordInHand(p) || done(p)));

        // blurite ore from the Asgarnian Ice Dungeon
        s.step(new QuestSteps.Collect("get blurite ore", ORE, 1)
                .then(new QuestSteps.Custom("pack food for the ice dungeon", 5, null, r -> { QuestRunner.makeRoom(r.bot, 10); QuestRunner.packFood(r.bot, 10); return QuestSteps.St.DONE; }).done(QuestKnightsSword::inIce)) // c1-ks: bought bars at Drogo's, so "under" held and the bot went in with no food (and died)
                .then(new QuestSteps.Walk("walk to the trapdoor on Mudskipper Point", TRAPDOOR_SIDE, 2).done(p -> under(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(TRAPDOOR_SIDE) <= 2))
                .then(new QuestSteps.Interact("climb down into the ice dungeon", new int[]{TRAPDOOR}, null, "Climb-down", TRAPDOOR_AT, 6, false).until(QuestKnightsSword::under).done(QuestKnightsSword::under))
                .then(new QuestSteps.Gather("mine blurite among the ice warriors", AfkTasks.Task.IRON, ORE, 1, BLURITE).products(ORE))
                .done(p -> has(ORE, 1).test(p) || swordInHand(p) || done(p)));
        // Guarded (from Black Knights' Fortress): eats at half health on the way out; a level-40 bot died here in the trial with 10 food in its pack
        s.step(new QuestBlackKnightsFortress.Guarded(new QuestSteps.Walk("back to the dungeon ladder", LADDER_SIDE, 2)).done(p -> !under(p) || p.getLocation().getDistance(LADDER_SIDE) <= 2));
        s.step(new QuestBlackKnightsFortress.Guarded(new QuestSteps.Interact("climb out of the ice dungeon", new int[]{LADDER_UP}, null, "Climb-up", LADDER_AT, 6, false).until(p -> !under(p))).done(p -> !under(p)));

        // Thurgo makes the sword
        s.step(new QuestSteps.Collect("get the blurite sword", SWORD, 1)
                .then(new QuestSteps.Walk("back to Thurgo with the ore and bars", THURGO_AT, 2))
                .then(new QuestSteps.Custom("make room for the sword", 5, null, r -> { QuestRunner.makeRoom(r.bot, 1); return QuestSteps.St.DONE; }))
                .then(new QuestSteps.Talk("give Thurgo the blurite and iron bars", new int[]{THURGO}, 2).until(has(SWORD, 1)))
                .done(p -> has(SWORD, 1).test(p) || done(p)));

        // the Squire gets his sword
        s.step(new QuestSteps.Walk("back to the Squire with the sword", SQUIRE_AT, 3).done(p -> done(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(SQUIRE_AT) <= 3));
        s.step(talk(new QuestSteps.Talk("give the Squire the sword", new int[]{SQUIRE}, 1)).until(QuestKnightsSword::done).done(QuestKnightsSword::done));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        return s;
    }
}
