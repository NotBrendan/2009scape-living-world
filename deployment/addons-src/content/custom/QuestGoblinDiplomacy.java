package content.custom;

import core.game.dialogue.DialogueInterpreter;
import core.game.dialogue.DialoguePlugin;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;

import java.util.function.Predicate;

import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Goblin Diplomacy: General Wartface (4494) and General Bentnoze (4493) in the Goblin Village (spawns 2957,3512 and
 * 2957,3513; data/configs/npc_spawns.json). Stages (GoblinDiplomacy.java, GDiplomacyCutscene.java): 10 started (orange armour
 * wanted), 20 orange shown (blue wanted), 30 blue shown (brown wanted), 100 done. Reward (GoblinDiplomacy.finish): 5 QP,
 * 200 Crafting XP, a gold bar (2357). No requirements.
 *
 * Generals' dialogue (GDiplomacyCutscene.GoblinGeneralDialogue, random chatter first, then always the same menu after 3 lines):
 *   stage 0 menu: "Why are you arguing about the colour of your armour? / Wouldn't you prefer peace? / Do you want me to
 *     pick an armour colour for you?" -> option 3, then continue only; quest.start at the end of it (stage 10).
 *   stage 10/20/30 menu: "Why are you arguing... / Wouldn't you prefer peace? / I have some <colour> armour here." (option 3;
 *     without the item it reads "Where am I meant to get <colour> amour?", so the hand-in needs the item first). Option 3 with
 *     the item: one player line, then the dialogue ends and the "Goblin Diplomacy Cutscene" (a dynamic region copy of
 *     region 11830) starts. In it: continue screens only; after "Put on this armour!" (plugin stage 62) the next click starts a
 *     7 to 16 tick pulse with plain messages ("Grubfoot goes into the changing room..."), and clicking continue again there
 *     would restart that pulse, so the cutscene step clicks the stage 62 screen once and then waits. The mail is removed and
 *     the stage rises by 10 at plugin stage 120 (or quest.finish at 103 in the last round).
 * Items: goblin mail 288 (brown, the plain mail), orange mail 286, blue mail 287 (GoblinDiplomacyPlugin.GoblinMail).
 *   Dyeing = use the dye on the mail (UseWithHandler GoblinMailPlugin: orange dye 1769 -> 286, blue dye 1767 -> 287; the dye
 *   and the mail are both used up). Orange dye = red dye 1763 used on yellow dye 1765 (GoblinMailPlugin.Dyes.ORANGE).
 * Goblin mail sources: crates 16559 (2959,3514,0), 16560 (2951,3508,0), 16561 (2955,3498,2, upstairs, skipped) with option
 *   "Search" give one mail each, once every 500 ticks per crate per player (GoblinDiplomacyPlugin: attribute "crate:<id>").
 *   Crate positions come from the map file (region 11830 / 11574 land data read out of data/cache with data/configs/xteas.json),
 *   not from memory. Goblins also drop 288 (drop_tables.json, weight 5, rare), so goblins are only the last fallback.
 * Dyes (AggieDialogue.java, Aggie 922 at 3086,3261 Draynor): "Can you make dyes for me please?" is option 4 of her menu
 *   "What could you make for me? / Cool, do you turn people into frogs? / You mad old witch... / Can you make dyes for me
 *   please?" (option 5 while Prince Ali Rescue is at stage 20..60, when "Could you think of a way to make skin paste?" is
 *   added on top); then "What sort of dye would you like?" -> menu "What do you need to make a red dye? / ...yellow dye? /
 *   ...blue dye?" options 1/2/3. Each costs 5 coins: red 3 redberries (1951), yellow 2 onions (1957), blue 2 woad leaves (1793).
 *   One dye per conversation.
 * Woad leaves: Wyson the gardener (36, 3020,3373 Falador park; WysonTheGardenerDialogue.kt): menu "Yes please, I need woad
 *   leaves. / Sorry, but I'm not interested." option 1, then "How about 5/10/15/20 coins?" option 4 (20 coins = 2 leaves).
 * Redberries: bushes 23628 at (3272,3375) and (3277,3371) (map file), option "Pick-from" (RedberryBushPlugin.java): one per
 *   pick, the bush (shared counter) goes empty (23630) after every third pick for 30 ticks.
 * Onions: the Lumbridge farm patch via AfkTasks.Task.ONION, or bot stock (onion is a glut good).
 */
final class QuestGoblinDiplomacy {
    private QuestGoblinDiplomacy() { }

    static final content.data.Quests Q = content.data.Quests.GOBLIN_DIPLOMACY;
    static final int WARTFACE = 4494, BENTNOZE = 4493, AGGIE = 922, WYSON = 36;
    static final int[] GENERALS = {WARTFACE, BENTNOZE};
    static final int MAIL = 288, ORANGE_MAIL = 286, BLUE_MAIL = 287;
    static final int RED_DYE = 1763, YELLOW_DYE = 1765, BLUE_DYE = 1767, ORANGE_DYE = 1769;
    static final int REDBERRY = 1951, ONION = 1957, WOAD = 1793;
    static final int CRATE_A = 16559, CRATE_B = 16560;
    static final int[] BUSHES = {23628, 23629, 23630};
    static final Location GENERALS_AT = Location.create(2957, 3511, 0);
    static final Location CRATE_A_AT = Location.create(2959, 3514, 0);
    static final Location CRATE_B_AT = Location.create(2951, 3508, 0);
    static final Location WYSON_AT = Location.create(3020, 3373, 0);
    static final Location AGGIE_AT = Location.create(3086, 3261, 0);
    static final Location ONIONS_AT = Location.create(3189, 3268, 0);        // AfkTasks.ONION_F, the onion patch by the Lumbridge farm
    static final Location BUSHES_AT = Location.create(3274, 3373, 0);        // between the two redberry bushes
    static final Location VILLAGE_GOBLINS = Location.create(2954, 3503, 0);  // village goblins spawn 2943..2963 x 3467..3510
    static final Location WYDIN = Location.create(3013, 3204, 0);            // Wydin's Food Store, Port Sarim (557 at 3012,3203; redberries 1951, shops.json 77): quest fix 10-23
    static final int[] FULL_BUSHES = {23628, 23629};                         // quest fix 10-23: 23630 is a picked-empty bush (RedberryBushPlugin, back after 30 ticks)

    static int stage(Player p) { return p.getQuestRepository().getStage(Q); }
    static boolean orangeDone(Player p) { return stage(p) >= 20 || has(ORANGE_MAIL, 1).test(p); }
    static boolean blueDone(Player p) { return stage(p) >= 30 || has(BLUE_MAIL, 1).test(p); }
    /** Raw goblin mails still to be dyed. */
    static int rawForDye(Player p) { return (orangeDone(p) ? 0 : 1) + (blueDone(p) ? 0 : 1); }
    static boolean enoughRaw(Player p) { return p.getInventory().getAmount(MAIL) >= rawForDye(p); }
    static boolean nearGenerals(Player p) { return p.getLocation().getZ() == 0 && p.getLocation().getDistance(GENERALS_AT) <= 40; }

    static QuestSteps.Talk longTalk(QuestSteps.Talk t) { t.limitTicks = 450; return t; }

    static Item slot(Player p, int id) {
        for (int i = 0; i < 28; i++) { Item x = p.getInventory().get(i); if (x != null && x.getId() == id) return x; }
        return null;
    }

    /** Uses one item on another from the pack (dye on dye, dye on mail), as the client's use-with would. */
    static QuestSteps.Step useItem(String name, int usedId, int baseId, Predicate<Player> finished) {
        final int[] st = {0, 0}; // tries, wait
        return new QuestSteps.Custom(name, 80, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (finished.test(p)) return QuestSteps.St.DONE;
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            if (p.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (++st[0] > 4) return QuestSteps.St.FAILED;
            Item used = slot(p, usedId), base = slot(p, baseId);
            if (used == null || base == null) return QuestSteps.St.FAILED;
            QuestSteps.clearPopups(p);
            boolean ran = false;
            try { ran = InteractionListeners.run(used, base, IntType.ITEM, p); } catch (Throwable ignored) { }
            if (!ran) try { QuestRunner.legacyUseWith(p, used, base); } catch (Throwable ignored) { }
            st[1] = 3;
            return QuestSteps.St.RUNNING;
        }).done(finished);
    }

    /** Picks three redberries; the bushes empty for 30 ticks after every third pick (shared counter), so it just keeps clicking. */
    static QuestSteps.Step pickBerries() {
        final int[] st = {0, 0}; // wait, misses
        return new QuestSteps.Custom("pick redberries", 900, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (has(REDBERRY, 3).test(p)) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            if (p.getPulseManager().hasPulseRunning() || p.getWalkingQueue().isMoving()) return QuestSteps.St.RUNNING;
            Scenery bush = QuestSteps.findScenery(p, BUSHES_AT, 8, FULL_BUSHES, null, "Pick-from"); // quest fix 10-23: a bush with berries on it, not the nearest (an emptied one only says "no berries left")
            if (bush == null) { if (++st[1] > 60) { r.log("  no redberry bush with berries near " + BUSHES_AT + " for 300 ticks"); return QuestSteps.St.FAILED; } st[0] = 5; return QuestSteps.St.RUNNING; }
            QuestRunner.makeRoom(p, 3);
            QuestSteps.clearPopups(p);
            if (!QuestSteps.click(p, bush, "Pick-from", IntType.SCENERY)) return QuestSteps.St.FAILED;
            st[0] = 6;
            return QuestSteps.St.RUNNING;
        }).done(has(REDBERRY, 3));
    }

    /** Clicks continue through the cutscene's dialogue until the stage lands. Skips repeat clicks on the changing-room screen (plugin stage 62). */
    static QuestSteps.Step cutscene(String name, int stage) {
        final int[] st = {0, 0}; // wait, clicked-the-changing-room-screen
        return new QuestSteps.Custom(name, 900, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (stageAtLeast(Q, stage).test(p)) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            DialogueInterpreter di = p.getDialogueInterpreter();
            DialoguePlugin d = di.getDialogue();
            int box = DialogueDriver.chatboxId(p);
            if (d != null && box > 0 && !DialogueDriver.isOptions(box) && di.activeTopics.isEmpty()) {
                if (d.stage == 62) { if (st[1] == 1) return QuestSteps.St.RUNNING; st[1] = 1; }
                try { di.handle(box, DialogueDriver.CONTINUE_CHILD); } catch (Throwable t) { return QuestSteps.St.FAILED; }
                st[0] = 1 + (int) (Math.random() * 3);
            }
            return QuestSteps.St.RUNNING;
        }).done(stageAtLeast(Q, stage));
    }

    /** After a cutscene ends the bot is put back at the village; wait for it. */
    static QuestSteps.Step backFromCutscene() {
        return new QuestSteps.Custom("wait to be back in the village", 250, null, r -> nearGenerals(r.bot) ? QuestSteps.St.DONE : QuestSteps.St.RUNNING).done(QuestGoblinDiplomacy::nearGenerals);
    }

    static QuestSteps.Step crate(String name, int id, Location at, Predicate<Player> enough) {
        return new QuestSteps.Interact(name, new int[]{id}, null, "Search", at, 4, false).until(enough).done(enough).optional();
    }

    static QuestSteps.Step handIn(String what, int newStage) {
        return longTalk(new QuestSteps.Talk("show the generals the " + what + " armour", GENERALS, 3)).done(stageAtLeast(Q, newStage));
    }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "goblindiplomacy", "Goblin Diplomacy").startAt(2957, 3511, 0).minutes(45).items(2357).xp(200);
        Predicate<Player> brownOk = p -> stage(p) >= 100 || has(MAIL, 1).test(p);

        // start
        s.step(new QuestSteps.Walk("walk to the goblin generals", GENERALS_AT, 4).done(p -> stage(p) >= 10 || nearGenerals(p) && p.getLocation().getDistance(GENERALS_AT) <= 4));
        s.step(longTalk(new QuestSteps.Talk("ask the generals about their armour", GENERALS, 3)).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        // two raw mails for dyeing: the crates beside the generals, else bot stock, else goblins
        s.step(crate("search the first crate", CRATE_A, CRATE_A_AT, QuestGoblinDiplomacy::enoughRaw));
        s.step(crate("search the second crate", CRATE_B, CRATE_B_AT, QuestGoblinDiplomacy::enoughRaw));
        s.step(new QuestSteps.Collect("get goblin mail to dye", MAIL, 2).count(QuestGoblinDiplomacy::rawForDye).stock()
                .then(new QuestSteps.Kill("kill goblins for their mail", new String[]{"Goblin"}, new int[]{MAIL}, 20, VILLAGE_GOBLINS)
                        .spots(Location.create(3245, 3246, 0), Location.create(3185, 3247, 0)).until(QuestGoblinDiplomacy::enoughRaw).limit(6000))
                .done(QuestGoblinDiplomacy::enoughRaw));

        // woad leaves for the blue dye (Wyson, 20 coins for 2)
        s.step(new QuestSteps.Collect("get 2 woad leaves", WOAD, 2).stock()
                .then(new QuestSteps.Walk("walk to Wyson the gardener", WYSON_AT, 5))
                .then(QuestSteps.pocketCoins(20)).then(longTalk(new QuestSteps.Talk("buy woad leaves from Wyson", new int[]{WYSON}, 1, 4)).until(has(WOAD, 2)))
                .done(p -> blueDone(p) || has(BLUE_DYE, 1).test(p) || has(WOAD, 2).test(p)));
        // onions for the yellow dye
        s.step(new QuestSteps.Collect("get 2 onions", ONION, 2)
                .then(new QuestSteps.Gather("pick onions at the Lumbridge farm", AfkTasks.Task.ONION, ONION, 2, ONIONS_AT))
                .done(p -> orangeDone(p) || has(YELLOW_DYE, 1).test(p) || has(ORANGE_DYE, 1).test(p) || has(ONION, 2).test(p)));
        // redberries for the red dye
        s.step(new QuestSteps.Collect("get 3 redberries", REDBERRY, 3).stock()
                .then(new QuestSteps.Walk("walk to the redberry bushes", BUSHES_AT, 6))
                .then(pickBerries())
                .buy(new QuestSteps.Buy("buy redberries from Wydin", REDBERRY, 3, "Wydin's Food Store", WYDIN)) // quest fix 10-23: live "short of Redberries (1/3)" x3: no bush picking left it short with nowhere else to go (Prince Ali buys his there too)
                .done(p -> orangeDone(p) || has(RED_DYE, 1).test(p) || has(ORANGE_DYE, 1).test(p) || has(REDBERRY, 3).test(p)));

        // Aggie makes the three dyes
        java.util.function.Function<Player, Object[]> aggieMenu = p -> {
            int par = p.getQuestRepository().getStage(content.data.Quests.PRINCE_ALI_RESCUE);
            return new Object[]{par >= 20 && par <= 60 ? 5 : 4};
        };
        s.step(new QuestSteps.Collect("get red dye", RED_DYE, 1).stock()
                .then(new QuestSteps.Walk("walk to Aggie", AGGIE_AT, 4))
                .then(QuestSteps.pocketCoins(5)).then(longTalk(new QuestSteps.Talk("ask Aggie for red dye", new int[]{AGGIE}, 4, 1)).picks(p -> new Object[]{aggieMenu.apply(p)[0], 1}).until(has(RED_DYE, 1)))
                .done(p -> orangeDone(p) || has(RED_DYE, 1).test(p) || has(ORANGE_DYE, 1).test(p)));
        s.step(new QuestSteps.Collect("get yellow dye", YELLOW_DYE, 1).stock()
                .then(new QuestSteps.Walk("walk to Aggie", AGGIE_AT, 4))
                .then(QuestSteps.pocketCoins(5)).then(longTalk(new QuestSteps.Talk("ask Aggie for yellow dye", new int[]{AGGIE}, 4, 2)).picks(p -> new Object[]{aggieMenu.apply(p)[0], 2}).until(has(YELLOW_DYE, 1)))
                .done(p -> orangeDone(p) || has(YELLOW_DYE, 1).test(p) || has(ORANGE_DYE, 1).test(p)));
        s.step(new QuestSteps.Collect("get blue dye", BLUE_DYE, 1).stock()
                .then(new QuestSteps.Walk("walk to Aggie", AGGIE_AT, 4))
                .then(QuestSteps.pocketCoins(5)).then(longTalk(new QuestSteps.Talk("ask Aggie for blue dye", new int[]{AGGIE}, 4, 3)).picks(p -> new Object[]{aggieMenu.apply(p)[0], 3}).until(has(BLUE_DYE, 1)))
                .done(p -> blueDone(p) || has(BLUE_DYE, 1).test(p)));

        // mix and dye
        s.step(useItem("mix red and yellow into orange dye", RED_DYE, YELLOW_DYE, p -> orangeDone(p) || has(ORANGE_DYE, 1).test(p)));
        s.step(useItem("dye a goblin mail orange", ORANGE_DYE, MAIL, QuestGoblinDiplomacy::orangeDone));
        s.step(useItem("dye a goblin mail blue", BLUE_DYE, MAIL, QuestGoblinDiplomacy::blueDone));

        // back at the village: the brown (plain) mail; the crates are ready again after 500 ticks, goblins are the last fallback
        s.step(new QuestSteps.Walk("back to the goblin generals", GENERALS_AT, 4).done(p -> stage(p) >= 100 || p.getLocation().getZ() == 0 && p.getLocation().getDistance(GENERALS_AT) <= 4));
        s.step(crate("search the first crate again", CRATE_A, CRATE_A_AT, brownOk));
        s.step(crate("search the second crate again", CRATE_B, CRATE_B_AT, brownOk));
        s.step(new QuestSteps.Collect("get one more goblin mail", MAIL, 1).stock()
                .then(new QuestSteps.Kill("kill goblins for their mail", new String[]{"Goblin"}, new int[]{MAIL}, 20, VILLAGE_GOBLINS)
                        .spots(Location.create(3245, 3246, 0), Location.create(3185, 3247, 0)).until(has(MAIL, 1)).limit(6000))
                .done(brownOk));

        // the three showings: orange, blue, brown
        s.step(handIn("orange", 20));
        s.step(cutscene("watch Grubfoot try the orange armour", 20));
        s.step(backFromCutscene());
        s.step(handIn("blue", 30));
        s.step(cutscene("watch Grubfoot try the blue armour", 30));
        s.step(backFromCutscene());
        s.step(handIn("brown", 100));
        s.step(cutscene("watch Grubfoot try the brown armour", 100));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(backFromCutscene());
        return s;
    }
}
