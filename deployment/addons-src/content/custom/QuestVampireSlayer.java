package content.custom;

import core.game.interaction.IntType;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;

import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Vampire Slayer: Morgan (755, his house in Draynor, spawn 3100,3268) -> Dr Harlow (756, Blue Moon Inn, Varrock, spawn
 * 3222,3398) -> Count Draynor (757) in the Draynor Manor basement.
 * Start (MorganDialogue): option 2 ("Ok, I'm up for an adventure."): quest.start = stage 10.
 * Harlow (DrHarlowDialogue) at stage 10: option 2 ("Morgan needs your help!"): stage 20. At stage 20 with a beer (1917)
 * in the pack: continue only, the beer goes, stage 30 and a stake (1549). At stage 30 with no stake in pack or bank he
 * hands out another (continue only).
 * Garlic (1550): the cupboard 33502 "Open" at 3096,3269,1 upstairs in Morgan's house gives a clove every time
 * (VampireSlayerPlugin); stairs 1722 Climb-up at 3099,3266,0 / 1723 Climb-down at 3100,3266,1 (cache map, the floor
 * walker climbs them). Hammer (2347): any general store.
 * Basement (VampireSlayerPlugin): stairs 32835 "Walk-down" at 3115,3357,0 -> 3077,9770; stairs 32836 "Walk-up" at
 * 3077,9768 -> 3115,3356 (neither is a climb option the floor walker knows, so both are explicit steps). Coffin 2614
 * "Open" at 3077,9775 at stage 30, with a stake or a hammer in the pack, spawns Count Draynor (757) for this player only
 * (attribute "count", isAttackable only for that player, cleared 16+ tiles away), who attacks at once.
 * The fight (VampireSlayerNPC): 40 hp, attack 20, defence 20, strength 1 (npc_configs.json). Without both stake AND
 * hammer in the pack every hit is zeroed and he heals 10 a hit. Every tick he heals 1 in 7: random(1,12) = 1-11 hp
 * without garlic in the pack, random(1,2) = always 1 with it (RandomFunction: max exclusive), so garlic is what makes
 * him killable at these levels. At 0 hp with a hammer the stake is used up and, at stage 30, the quest finishes:
 * 4825 Attack XP (VampireSlayer.finish), 3 QP.
 */
final class QuestVampireSlayer {
    private QuestVampireSlayer() { }

    static final content.data.Quests Q = content.data.Quests.VAMPIRE_SLAYER;
    static final int MORGAN = 755, HARLOW = 756, COUNT = 757;
    static final int STAKE = 1549, GARLIC = 1550, HAMMER = 2347, BEER = 1917;
    static final int CUPBOARD = 33502, STAIRS_DOWN = 32835, STAIRS_UP = 32836, COFFIN = 2614;
    static final Location MORGAN_AT = Location.create(3099, 3268, 0);     // in his house (spawn 3100,3268, doesn't walk; door 1530 at 3098,3270)
    static final Location CUPBOARD_SIDE = Location.create(3097, 3269, 1); // next to the cupboard 33502 at 3096,3269,1
    static final Location HARLOW_AT = Location.create(3223, 3398, 0);     // Blue Moon Inn (Harlow 3222,3398 walks 3; bartender 733 at 3226,3399)
    static final Location BLUE_MOON_BAR = Location.create(3225, 3399, 0);
    static final Location VARROCK_STORE = Location.create(3216, 3414, 0); // Varrock general store (shopkeeper 522 at 3218,3415; sells 2347, shops.json 40)
    static final Location WYDIN = Location.create(3013, 3204, 0);         // Wydin's Food Store, Port Sarim (557 at 3012,3203; sells garlic, shops.json 77)
    static final Location MANOR_STAIRS = Location.create(3115, 3358, 0);  // inside the manor by the basement stairs 32835 (3115,3357)
    static final Location BASEMENT = Location.create(3077, 9770, 0);      // where the stairs land
    static final Location BASEMENT_STAIRS = Location.create(3077, 9769, 0); // by 32836 (3077,9768)
    static final Location COFFIN_AT = Location.create(3077, 9775, 0);
    static final Location COFFIN_SIDE = Location.create(3077, 9773, 0);   // south of the coffin, off the candle tiles (3076/3079,9772 and 3075/3080,9778 burn)
    static final Location OUTSIDE = Location.create(3109, 3340, 0);       // the courtyard in front of the manor (as QuestErnestTheChicken)

    static boolean under(Player p) { return p.getLocation().getY() > 6400; }
    static boolean done(Player p) { return stageAtLeast(Q, 100).test(p); }

    /** The Count this bot woke (VampireSlayerPlugin stores him on the player as "count"). */
    static NPC count(Player p) {
        Object o = p.getAttribute("count", null);
        return o instanceof NPC && ((NPC) o).isActive() ? (NPC) o : null;
    }

    static int food(Player p) { int n = 0; for (int f : AfkTasks.Runner.FOOD) n += p.getInventory().getAmount(f); return n; }

    /**
     * The boss fight, one step so the boss lock covers it all: wait while a player is near the coffin, open it, then
     * fight the Count directly (the bots' fight runner skips an NPC already in combat, and he attacks the moment he
     * rises), eating at 60% like the runner. Ends when the server finishes the quest.
     */
    static QuestSteps.Step fight() {
        final QuestSteps.Mover m = new QuestSteps.Mover();
        final int[] st = {0, 0, 0, 0}; // coffin clicks, wait, eat cooldown, waiting-for-player log
        return new QuestSteps.Custom("fight Count Draynor", 3000, r -> { st[0] = 0; st[1] = 0; st[2] = 0; st[3] = 0; m.reset(COFFIN_SIDE, 1); }, r -> {
            Player p = r.bot;
            if (done(p)) return QuestSteps.St.DONE;
            if (!under(p)) { r.log("  not in the basement (at " + p.getLocation() + ")"); return QuestSteps.St.FAILED; }
            if (p.getInventory().getAmount(HAMMER) < 1 || p.getInventory().getAmount(STAKE) < 1) { r.log("  no stake and hammer in the pack"); return QuestSteps.St.FAILED; }
            if (st[2] > 0) st[2]--;
            NPC c = count(p);
            if (c == null) {
                if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
                for (Player h : core.game.world.repository.Repository.getPlayers()) { // the boss rule: never with a player watching
                    if (h != null && !h.isArtificial() && h.isActive() && h.getLocation().getZ() == 0 && h.getLocation().getDistance(COFFIN_AT) <= 15) {
                        if (st[3]++ % 100 == 0) r.log("  a player is in the crypt: waiting");
                        return QuestSteps.St.RUNNING;
                    }
                }
                if (p.getLocation().getDistance(COFFIN_SIDE) > 2) { QuestSteps.St w = m.tick(p); if (w == QuestSteps.St.FAILED) return w; if (w == QuestSteps.St.RUNNING && p.getLocation().getDistance(COFFIN_SIDE) > 2) return w; }
                if (p.getWalkingQueue().isMoving()) return QuestSteps.St.RUNNING;
                if (st[0] >= 6) { r.log("  the coffin wouldn't give up the Count (6 opens)"); return QuestSteps.St.FAILED; }
                Scenery coffin = QuestSteps.findScenery(p, COFFIN_AT, 4, new int[]{COFFIN}, null, "Open");
                if (coffin == null) { r.log("  no coffin 2614 near " + COFFIN_AT); return QuestSteps.St.FAILED; }
                QuestSteps.clearPopups(p);
                QuestSteps.click(p, coffin, "Open", IntType.SCENERY);
                st[0]++; st[1] = 4;
                return QuestSteps.St.RUNNING;
            }
            int hp = p.getSkills().getLifepoints(), max = p.getSkills().getMaximumLifepoints();
            if (hp * 5 <= max * 3 && st[2] == 0) { // eat at 60%, as the fight runner does for bots
                for (Item it : p.getInventory().toArray()) {
                    if (it == null || !QuestSteps.contains(AfkTasks.Runner.FOOD, it.getId())) continue;
                    try { new core.game.bots.ScriptAPI(p).forceEat(it.getId()); } catch (Throwable ignored) { }
                    st[2] = 3;
                    break;
                }
            }
            if (food(p) == 0 && hp * 4 <= max) { r.log("  out of food at " + hp + "/" + max + " hp"); return QuestSteps.St.FAILED; }
            try { if (p.getProperties().getCombatPulse().getVictim() != c) p.attack(c); } catch (Throwable ignored) { }
            return QuestSteps.St.RUNNING;
        });
    }

    static QuestScript build() {
        // Levels: the Count only heals 1 hp in 7 ticks with garlic, so the bot has to out-damage that against defence 20:
        // Attack 25 to land hits and Strength 30 for a 4-5 max hit; he hits with strength 1, so Hitpoints 20 is plenty
        // with the food below.
        QuestScript s = QuestScript.of(Q, "vampireslayer", "Vampire Slayer").startAt(3100, 3268, 0).minutes(35).xp(4800 * 0.5).unlock(5)
                .level(Skills.ATTACK, 25).level(Skills.STRENGTH, 30).level(Skills.HITPOINTS, 20);
        s.step(new QuestSteps.Walk("walk into Morgan's house", MORGAN_AT, 2).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(MORGAN_AT) <= 2));
        s.step(new QuestSteps.Talk("offer Morgan help", new int[]{MORGAN}, 2).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        s.step(new QuestSteps.Collect("get some garlic", GARLIC, 1).stock()
                .then(new QuestSteps.Walk("upstairs to Morgan's cupboard", CUPBOARD_SIDE, 1))
                .then(new QuestSteps.Interact("take garlic from the cupboard", new int[]{CUPBOARD}, null, "Open", CUPBOARD_SIDE, 4, false).until(has(GARLIC, 1)))
                .buy(new QuestSteps.Buy("buy garlic from Wydin", GARLIC, 1, "Wydin's Food Store", WYDIN))
                .done(p -> has(GARLIC, 1).test(p) || done(p)));
        s.step(new QuestSteps.Walk("back down to the ground floor", MORGAN_AT, 3).done(p -> p.getLocation().getZ() == 0 || done(p)));

        s.step(new QuestSteps.Walk("walk to the Blue Moon Inn", HARLOW_AT, 3).done(p -> stageAtLeast(Q, 20).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(HARLOW_AT) <= 3));
        s.step(new QuestSteps.Talk("tell Dr Harlow Morgan needs him", new int[]{HARLOW}, 2).until(stageAtLeast(Q, 20)).done(stageAtLeast(Q, 20)));
        s.step(new QuestSteps.Collect("get Harlow a beer", BEER, 1).stock()
                .buy(new QuestSteps.Buy("buy a beer at the bar", BEER, 1, "the Blue Moon Inn bar", BLUE_MOON_BAR))
                .done(p -> has(BEER, 1).test(p) || stageAtLeast(Q, 30).test(p)));
        s.step(new QuestSteps.Walk("back over to Harlow", HARLOW_AT, 3).done(stageAtLeast(Q, 30)));
        s.step(new QuestSteps.Custom("make room for the stake", 5, null, r -> { QuestRunner.makeRoom(r.bot, 1); return QuestSteps.St.DONE; }).done(stageAtLeast(Q, 30))); // a full pack drops it on the floor
        s.step(new QuestSteps.Talk("give Harlow the beer", new int[]{HARLOW}).until(stageAtLeast(Q, 30)).done(stageAtLeast(Q, 30)));
        s.step(new QuestSteps.Collect("get the stake", STAKE, 1) // given with the beer; a lost one: Harlow hands out another at stage 30
                .then(new QuestSteps.Walk("back to Harlow for a stake", HARLOW_AT, 3))
                .then(new QuestSteps.Talk("ask Harlow for another stake", new int[]{HARLOW}).until(has(STAKE, 1)))
                .done(p -> has(STAKE, 1).test(p) || done(p)));
        s.step(new QuestSteps.Collect("get a hammer", HAMMER, 1).stock()
                .buy(new QuestSteps.Buy("buy a hammer", HAMMER, 1, "the Varrock general store", VARROCK_STORE))
                .done(p -> has(HAMMER, 1).test(p) || done(p)));
        s.step(new QuestSteps.Custom("pack food for the Count", 5, null, r -> { QuestRunner.makeRoom(r.bot, 10); QuestRunner.packFood(r.bot, 10); return QuestSteps.St.DONE; }).done(p -> under(p) || done(p)));

        s.step(new QuestSteps.Walk("into Draynor Manor to the basement stairs", MANOR_STAIRS, 2).done(p -> under(p) || done(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(MANOR_STAIRS) <= 2));
        s.step(new QuestSteps.Interact("walk down to the basement", new int[]{STAIRS_DOWN}, null, "Walk-down", MANOR_STAIRS, 4, false).until(QuestVampireSlayer::under).done(p -> under(p) || done(p)));
        s.step(fight().boss().done(QuestVampireSlayer::done));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));

        s.exit(new QuestSteps.Walk("back to the basement stairs", BASEMENT_STAIRS, 2).done(p -> !under(p) || p.getLocation().getDistance(BASEMENT_STAIRS) <= 2));
        s.exit(new QuestSteps.Interact("walk up out of the basement", new int[]{STAIRS_UP}, null, "Walk-up", BASEMENT_STAIRS, 4, false).until(p -> !under(p)).done(p -> !under(p)));
        s.exit(new QuestSteps.Walk("out of the manor", OUTSIDE, 4));
        return s;
    }
}
