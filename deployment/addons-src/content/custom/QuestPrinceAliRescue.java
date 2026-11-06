package content.custom;

import core.game.bots.AIPlayer;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import static content.custom.QuestScript.attr;
import static content.custom.QuestScript.attrInt;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Prince Ali Rescue: Hassan (923, Al Kharid palace, spawn 3302,3163) -> Osman (924, spawn 3286,3180) -> Leela (915,
 * east of Draynor, spawn 3108,3264) -> Joe the door guard (916, 3122,3245), Lady Keli (919, 3128,3245, walks 2) and
 * Prince Ali (920, 3123,3241) in the jail east of Draynor -> Hassan for the 700 coins.
 * Start (HassanDialogue): option 1 ("Can I help you? You must need some help here in the desert."): stage 10.
 * Osman (OsmanDialogue in GardenObjectsPlugin.kt) at stage 10: option 1 ("What is the first thing I must do?"), and at
 * the end option 3 ("Okay, I better go find some things."): stage 20. At stage 20 with the key print (2423) and a bronze
 * bar (2349) in the pack: continue only, both go, stage 30.
 * Key print: Lady Keli (LadyKeliDialogue, any stage below 60) option 1 ("Heard of you? You are famous in ...!") then 2
 * ("What is your latest plan then?"): with soft clay (1761) in the pack it becomes the print. Soft clay = clay (434)
 * used with a bucket of water (1929) (SoftclayPlugin; with one clay in the pack it mixes at once).
 * Leela (LeelaDialogue) at stage 30: continue only, bronze key (2418), stage 40. At 40/50 without a key (pack or bank)
 * she sells another for 15 coins from the pack (continue only).
 * Wig: Ned (743, Draynor, 3098,3257; NedDialogue) Topic "I'd like to talk about Prince Ali Rescue." (shown at stages
 * 20-50), then (NedPARDialogue) options 1 ("Ned, could you make other things from wool?"), 2 ("How about some sort of
 * wig?"), 1 ("I have that now. Please, make me a wig."): 3 balls of wool (1759) -> wig (2421). Yellow dye (1765) used on
 * the wig -> blonde wig 2419 (WigDyePlugin, legacy item-on-item).
 * Aggie (922, Draynor, 3086,3261, walks 7; AggieDialogue) at stages 20-60 opens with five options: 1 ("Could you think
 * of a way to make skin paste?") then 1 ("Yes please. Mix me some skin paste.") takes ashes (592), redberries (1951),
 * a pot of flour (1933) and a bucket (1929) or jug (1937) of water -> skin paste (2424); 5 ("Can you make dyes for me
 * please?") then 2 ("What do you need to make yellow dye?") takes 2 onions (1957) and 5 coins from the pack -> 1765.
 * Pink skirt (1013): Thessalia's Fine Clothes (shops.json 60). Rope (954), buckets of water and bronze bars: the
 * Shantay Pass Shop (shops.json 35, 836 at 3304,3124). Redberries and pots of flour: Wydin's Food Store (77). Beer:
 * the Rusty Anchor bartender (734, 3045,3256). No shop sells ashes (see the ashes step).
 * Joe (JoeGuardDialogue) at stage 40 with 3 beers (1917): continue only, all three go, /save:guard-drunk.
 * Rope on Keli (RopeOnLadyKeli listener) at stage 40-50 with guard-drunk: stage 50, keli-gone = now + 350 ticks
 * (not saved; she is hidden for that player meanwhile; a fresh rope ties her again).
 * Prison door 2881 at 3123,3243 (PrinceAliRescuePlugin): at stage 50 opens (auto-walk) only while keli-gone holds and
 * with the key in the pack; at stage 60 always. Prince Ali (PrinceAliDialogue) at stage 50 with paste, wig, key and
 * skirt: continue only, all four go, stage 60. Hassan at stage 60: continue only finishes it: 700 coins, 3 QP.
 * Jail guards (917, level ~26, aggressive) stand around the jail (3122-3125,3249-3250).
 */
final class QuestPrinceAliRescue {
    private QuestPrinceAliRescue() { }

    static final content.data.Quests Q = content.data.Quests.PRINCE_ALI_RESCUE;
    static final int HASSAN = 923, OSMAN = 924, LEELA = 915, JOE = 916, KELI = 919, PRINCE = 920, NED = 743, AGGIE = 922;
    static final int ROPE = 954, SKIRT = 1013, YELLOW_WIG = 2419, WIG = 2421, PASTE = 2424, KEY = 2418, PRINT = 2423;
    static final int SOFT_CLAY = 1761, CLAY = 434, BRONZE_BAR = 2349, BALL_OF_WOOL = 1759, WOOL = 1737, SHEARS = 1735;
    static final int YELLOW_DYE = 1765, ONION = 1957, ASHES = 592, REDBERRIES = 1951, FLOUR = 1933, WATER = 1929, JUG_OF_WATER = 1937, BEER = 1917, COINS = 995;
    static final int PRISON_DOOR = 2881, JAIL_DOOR = 1536;

    static final Location HASSAN_AT = Location.create(3301, 3163, 0);
    static final Location OSMAN_AT = Location.create(3287, 3180, 0);
    static final Location SHANTAY_SHOP = Location.create(3303, 3123, 0);     // Shantay Pass Shop (836 at 3304,3124)
    static final Location THESSALIA = Location.create(3204, 3417, 0);        // Thessalia's Fine Clothes, Varrock (548 at 3205,3416)
    static final Location WYDIN = Location.create(3013, 3204, 0);            // Wydin's Food Store, Port Sarim (557 at 3012,3203)
    static final Location RUSTY_ANCHOR = Location.create(3046, 3256, 0);     // Rusty Anchor bar, Port Sarim (bartender 734 at 3045,3256)
    static final Location SARAH = Location.create(3038, 3291, 0);            // Sarah's Farming Shop (2304 at 3039,3292; sells onions, shops.json 75)
    static final Location ONIONS = Location.create(2950, 3251, 0);           // the onion patch north of Rimmington (as QuestWitchsPotion)
    static final Location RIMMINGTON_MINE = Location.create(2977, 3240, 0);  // clay (as QuestDoricsQuest)
    static final Location DRAYNOR_MARKET = Location.create(3082, 3249, 0);
    static final Location FRED_PEN = Location.create(3203, 3266, 0);         // sheep (as QuestSheepShearer)
    static final Location LUMBRIDGE_STORE = Location.create(3214, 3243, 0);
    static final Location WHEEL = Location.create(3209, 3213, 1);            // spinning wheel 36970 at 3209,3212, Lumbridge castle first floor
    static final Location AGGIE_AT = Location.create(3086, 3259, 0);         // in her house (door 1530 at 3088,3258)
    static final Location NED_AT = Location.create(3098, 3256, 0);           // in his house (door 1530 at 3101,3258)
    static final Location NED_DOOR_AT = Location.create(3101, 3258, 0);      // Ned's door 1530, wall on the tile's east edge
    static final Location NED_DOOR_OUT = Location.create(3102, 3258, 0);
    static final Location NED_IN = Location.create(3099, 3258, 0);
    static final int NED_DOOR = 1530;
    static final Location LEELA_AT = Location.create(3109, 3263, 0);
    static final Location JAIL_IN = Location.create(3125, 3245, 0);          // inside the jail, between Joe and Keli (outer door 1536 at 3128,3246)
    static final Location JAIL_DOOR_AT = Location.create(3128, 3246, 0);     // the jail's outer door 1536, wall on the tile's north edge
    static final Location JAIL_DOOR_OUT = Location.create(3128, 3247, 0);    // the gap in the spear wall north of it
    static final Location KELI_AT = Location.create(3128, 3245, 0);
    static final Location PRISON_DOOR_AT = Location.create(3123, 3243, 0);   // wall on the tile's north edge: the cell is south of it
    static final Location PRINCE_AT = Location.create(3123, 3241, 0);

    static int stage(Player p) { return p.getQuestRepository().getStage(Q); }
    static boolean freed(Player p) { return stage(p) >= 60; }
    static boolean keyMade(Player p) { return stage(p) >= 30; }
    static boolean printed(Player p) { return has(PRINT, 1).test(p) || keyMade(p); }
    static boolean pasted(Player p) { return has(PASTE, 1).test(p) || freed(p); }
    static boolean wigged(Player p) { return has(YELLOW_WIG, 1).test(p) || freed(p); }
    static boolean drunk(Player p) { return attr(p, "guard-drunk") || stage(p) >= 50; }
    static boolean keliGone(Player p) { return attrInt(p, "keli-gone") > GameWorld.getTicks(); }
    /** The jail's outer room (walls from the cache map: west edge of x 3121, east edge of 3130, north edge of y 3246, the cell wall on y 3243). */
    static boolean inJail(Player p) { Location l = p.getLocation(); return l.getZ() == 0 && l.getX() >= 3121 && l.getX() <= 3130 && l.getY() >= 3244 && l.getY() <= 3246; }
    static boolean inCell(Player p) { Location l = p.getLocation(); return l.getZ() == 0 && Math.abs(l.getX() - PRINCE_AT.getX()) <= 2 && l.getY() >= 3240 && l.getY() <= 3243; }

    /** Buckets of water still needed: one for the soft clay, one for the skin paste (Aggie takes a jug instead). */
    static int waterNeed(Player p) {
        int n = (printed(p) || has(SOFT_CLAY, 1).test(p) ? 0 : 1) + (pasted(p) || has(JUG_OF_WATER, 1).test(p) ? 0 : 1);
        return n;
    }

    /** Balls of wool still needed for the wig. */
    static int woolNeed(Player p) { return has(WIG, 1).test(p) || wigged(p) ? 0 : 3; }

    /**
     * One item used on another from the pack, as the client's item-on-item does (the listener first, else the legacy
     * UseWithHandler; Ernest's fish food the same way). {@code single}: only one of {@code used} in the pack, the rest
     * banked (two clay open a "how many?" screen instead of mixing, SoftclayPlugin).
     */
    static QuestSteps.Step mix(String name, int used, int with, int product, boolean single) {
        final int[] st = {0, 0}; // tries, wait
        return new QuestSteps.Custom(name, 80, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (has(product, 1).test(p)) return QuestSteps.St.DONE;
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            if (p.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (++st[0] > 4) return QuestSteps.St.FAILED;
            if (single) {
                int extra = p.getInventory().getAmount(used) - 1;
                if (extra > 0 && p.getInventory().remove(new Item(used, extra))) { if (!p.getBank().add(new Item(used, extra))) p.getInventory().add(new Item(used, extra)); }
            }
            Item a = slot(p, used), b = slot(p, with);
            if (a == null || b == null) { r.log("  " + name + ": missing " + (a == null ? used : with)); return QuestSteps.St.FAILED; }
            QuestSteps.clearPopups(p);
            Item x = (st[0] & 1) == 1 ? a : b, y = x == a ? b : a; // the other way round on the second try
            boolean ran = false;
            try { ran = InteractionListeners.run(x, y, IntType.ITEM, p); } catch (Throwable ignored) { }
            if (!ran) try { QuestRunner.legacyUseWith(p, x, y); } catch (Throwable ignored) { }
            st[1] = 3;
            return QuestSteps.St.RUNNING;
        });
    }

    /**
     * Through the jail's outer door 1536 (3128,3246), in or out. princeali-trial2 showed the plain walk to JAIL_IN ending at
     * 3120,3244-3245, just outside the jail's west wall (x 3121), and passing as "close enough" by the walk's 6-tile rule:
     * the door was 8 tiles round the corner, out of the walker's door reach, and Joe (3122,3245) stood on the other side of
     * the wall, so every Talk-to "never opened". This walks round to the door, opens it like a player (an ordinary door,
     * 1536 -> 1537) and walks through.
     */
    static QuestSteps.Step jailDoor(String name, boolean in) {
        final int[] st = {0, 0}; // tries, wait
        final Location to = in ? JAIL_IN : JAIL_DOOR_OUT;
        java.util.function.Predicate<Player> there = p -> in ? inJail(p) : !inJail(p) && !inCell(p);
        return new QuestSteps.Custom(name, 250, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (there.test(p)) return QuestSteps.St.DONE;
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getLocks().isMovementLocked() || p.getLocks().isInteractionLocked()) return QuestSteps.St.RUNNING;
            if (++st[0] > 30) { r.log("  " + name + ": still " + (inJail(p) ? "inside" : "outside") + " at " + p.getLocation()); return QuestSteps.St.FAILED; }
            Scenery door = QuestSteps.findScenery(p, JAIL_DOOR_AT, 0, new int[]{JAIL_DOOR}, null, "Open");
            if (door != null && door.isActive() && p.getLocation().getDistance(JAIL_DOOR_AT) <= 10 && (st[0] & 1) == 1) { // shut: open it (the click walks up to it); every other try a walk, in case the click can't path
                QuestSteps.clearPopups(p);
                QuestSteps.click(p, door, "Open", IntType.SCENERY);
                st[1] = 3;
                return QuestSteps.St.RUNNING;
            }
            // open (or not yet near it): walk through, the long way round the jail's walls to the door's side first
            Location via = in && !inJail(p) && p.getLocation().getDistance(JAIL_DOOR_OUT) > 1 ? JAIL_DOOR_OUT : !in && inJail(p) && p.getLocation().getDistance(JAIL_DOOR_AT) > 1 ? JAIL_DOOR_AT : to;
            try { if (p instanceof AIPlayer) ((AIPlayer) p).walkToPosSmart(via); } catch (Throwable ignored) { }
            st[1] = 2;
            return QuestSteps.St.RUNNING;
        });
    }

    /** Ned's house (walls from the cache map: x 3096-3101, y 3256-3261; the only door on the east edge of 3101,3258). */
    static boolean inNeds(Player p) { Location l = p.getLocation(); return l.getZ() == 0 && l.getX() >= 3096 && l.getX() <= 3101 && l.getY() >= 3256 && l.getY() <= 3261; }

    /**
     * Into Ned's house through its door, as jailDoor does for Keli's jail. c1 trials: the radius-2 walk to NED_AT (just
     * inside the south wall) could end at 3098,3254-3255, outside that wall: the door was then out of the Talk's door reach
     * (3 tiles) and, with Ned beside the south wall, the Talk's walk-up was skipped (within 1.5), so every Talk-to "never
     * opened" across the wall.
     */
    static QuestSteps.Step nedDoor() {
        final int[] st = {0, 0}; // tries, wait
        return new QuestSteps.Custom("into Ned's house", 250, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (inNeds(p)) return QuestSteps.St.DONE;
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getLocks().isMovementLocked() || p.getLocks().isInteractionLocked()) return QuestSteps.St.RUNNING;
            if (++st[0] > 30) { r.log("  into Ned's house: still outside at " + p.getLocation()); return QuestSteps.St.FAILED; }
            Scenery door = QuestSteps.findScenery(p, NED_DOOR_AT, 0, new int[]{NED_DOOR}, null, "Open");
            if (door != null && door.isActive() && p.getLocation().getDistance(NED_DOOR_AT) <= 10 && (st[0] & 1) == 1) { // shut: open it (the click walks up to it)
                QuestSteps.clearPopups(p);
                QuestSteps.click(p, door, "Open", IntType.SCENERY);
                st[1] = 3;
                return QuestSteps.St.RUNNING;
            }
            Location via = p.getLocation().getDistance(NED_DOOR_OUT) > 1 ? NED_DOOR_OUT : NED_IN; // round to the door's side first
            try { if (p instanceof AIPlayer) ((AIPlayer) p).walkToPosSmart(via); } catch (Throwable ignored) { }
            st[1] = 2;
            return QuestSteps.St.RUNNING;
        });
    }

    static Item slot(Player p, int id) {
        for (int i = 0; i < 28; i++) { Item x = p.getInventory().get(i); if (x != null && x.getId() == id) return x; }
        return null;
    }

    /** Spin what the pack holds at the wheel (as QuestSheepShearer: the pulse doesn't check for a wheel). */
    static QuestSteps.Step spin() {
        return new QuestSteps.Custom("spin the wool", 600, QuestPrinceAliRescue::startSpinning, r -> {
            Player p = r.bot;
            if (p.getInventory().getAmount(BALL_OF_WOOL) >= woolNeed(p)) return QuestSteps.St.DONE;
            if (p.getInventory().getAmount(WOOL) == 0) return QuestSteps.St.FAILED;
            if (!p.getPulseManager().hasPulseRunning()) startSpinning(r);
            return QuestSteps.St.RUNNING;
        });
    }

    static void startSpinning(QuestRunner r) {
        Player p = r.bot;
        int n = Math.min(p.getInventory().getAmount(WOOL), woolNeed(p) - p.getInventory().getAmount(BALL_OF_WOOL));
        if (n <= 0) return;
        try {
            p.getPulseManager().run(new content.global.skill.crafting.spinning.SpinningPulse(p, new Item(WOOL), n, content.global.skill.crafting.spinning.SpinningItem.WOOL));
        } catch (Throwable t) { r.log("  spinning failed to start: " + t); }
    }

    static QuestSteps.Step coins(String name, int n) {
        return new QuestSteps.Collect(name, COINS, n).done(has(COINS, n));
    }

    static QuestScript build() {
        // Hitpoints 20: the aggressive jail guards (917) around Keli's jail swing at anyone walking in.
        QuestScript s = QuestScript.of(Q, "princeali", "Prince Ali Rescue").startAt(3302, 3163, 0).minutes(75).coins(700).unlock(20)
                .level(Skills.ATTACK, 48).level(Skills.STRENGTH, 48).level(Skills.DEFENCE, 48).level(Skills.HITPOINTS, 48); // combat 53+: AggressiveBehavior leaves targets over twice its level alone, so the level-26 jail guards don't interrupt the talks (a level-30 bot died there in the trial)
        s.step(new QuestSteps.Walk("walk to Hassan in the palace", HASSAN_AT, 3).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(HASSAN_AT) <= 3));
        s.step(new QuestSteps.Talk("offer Hassan help", new int[]{HASSAN}, 1).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));
        s.step(new QuestSteps.Walk("walk to Osman", OSMAN_AT, 3).done(p -> stageAtLeast(Q, 20).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(OSMAN_AT) <= 3));
        s.step(new QuestSteps.Talk("ask Osman what's needed", new int[]{OSMAN}, 1, 3).until(stageAtLeast(Q, 20)).done(stageAtLeast(Q, 20)));

        // ---- Al Kharid: the Shantay Pass Shop for water, rope and the bronze bar
        s.step(new QuestSteps.Collect("get buckets of water", WATER, 2).count(QuestPrinceAliRescue::waterNeed).stock()
                .buy(new QuestSteps.Buy("buy buckets of water", WATER, 2, "the Shantay Pass Shop", SHANTAY_SHOP))
                .done(p -> p.getInventory().getAmount(WATER) >= waterNeed(p)));
        s.step(new QuestSteps.Collect("get a rope", ROPE, 1).stock()
                .buy(new QuestSteps.Buy("buy a rope", ROPE, 1, "the Shantay Pass Shop", SHANTAY_SHOP))
                .done(p -> has(ROPE, 1).test(p) || stage(p) >= 50));
        s.step(new QuestSteps.Collect("get a bronze bar", BRONZE_BAR, 1).stock()
                .buy(new QuestSteps.Buy("buy a bronze bar", BRONZE_BAR, 1, "the Shantay Pass Shop", SHANTAY_SHOP))
                .done(p -> has(BRONZE_BAR, 1).test(p) || keyMade(p)));
        s.step(new QuestSteps.Collect("get soft clay", SOFT_CLAY, 1).stock()
                .then(new QuestSteps.Collect("get some clay", CLAY, 1)
                        .then(new QuestSteps.Gather("mine clay at Rimmington", AfkTasks.Task.CLAY, CLAY, 1, RIMMINGTON_MINE))
                        .done(has(CLAY, 1)))
                .then(mix("wet the clay", CLAY, WATER, SOFT_CLAY, true))
                .done(p -> has(SOFT_CLAY, 1).test(p) || printed(p)));

        // ---- the wool for the wig (Lumbridge on the way west), the skirt (Varrock)
        s.step(new QuestSteps.Collect("get 3 balls of wool", BALL_OF_WOOL, 3).count(QuestPrinceAliRescue::woolNeed).stock()
                .then(new QuestSteps.Collect("get some wool", WOOL, 3).count(p -> woolNeed(p) - p.getInventory().getAmount(BALL_OF_WOOL))
                        .then(new QuestSteps.Collect("get some shears", SHEARS, 1).buy(new QuestSteps.Buy("buy shears", SHEARS, 1, "the Lumbridge general store", LUMBRIDGE_STORE)).done(has(SHEARS, 1)))
                        .then(new QuestSteps.Gather("shear Fred's sheep", AfkTasks.Task.WOOL, WOOL, 3, FRED_PEN).count(p -> woolNeed(p) - p.getInventory().getAmount(BALL_OF_WOOL)))
                        .done(p -> p.getInventory().getAmount(WOOL) + p.getInventory().getAmount(BALL_OF_WOOL) >= woolNeed(p)))
                .then(new QuestSteps.Walk("up to the spinning wheel", WHEEL, 2))
                .then(spin())
                .done(p -> p.getInventory().getAmount(BALL_OF_WOOL) >= woolNeed(p)));
        s.step(new QuestSteps.Collect("get a pink skirt", SKIRT, 1).stock()
                .buy(new QuestSteps.Buy("buy a pink skirt", SKIRT, 1, "Thessalia's Fine Clothes", THESSALIA))
                .done(p -> has(SKIRT, 1).test(p) || freed(p)));

        // ---- Port Sarim: berries, flour, beer, onions; ashes
        s.step(new QuestSteps.Collect("get redberries", REDBERRIES, 1).stock()
                .buy(new QuestSteps.Buy("buy redberries", REDBERRIES, 1, "Wydin's Food Store", WYDIN))
                .done(p -> has(REDBERRIES, 1).test(p) || pasted(p)));
        s.step(new QuestSteps.Collect("get a pot of flour", FLOUR, 1).stock()
                .buy(new QuestSteps.Buy("buy a pot of flour", FLOUR, 1, "Wydin's Food Store", WYDIN))
                .done(p -> has(FLOUR, 1).test(p) || pasted(p)));
        s.step(new QuestSteps.Collect("get 3 beers for the guard", BEER, 3).stock()
                .buy(new QuestSteps.Buy("buy 3 beers", BEER, 3, "the Rusty Anchor", RUSTY_ANCHOR))
                .done(p -> has(BEER, 3).test(p) || drunk(p)));
        s.step(new QuestSteps.Collect("get 2 onions", ONION, 2)
                .then(new QuestSteps.Gather("pick onions", AfkTasks.Task.ONION, ONION, 2, ONIONS))
                .buy(new QuestSteps.Buy("buy onions", ONION, 2, "Sarah's Farming Shop", SARAH))
                .done(p -> has(ONION, 2).test(p) || has(YELLOW_DYE, 1).test(p) || wigged(p)));
        // No NPC sells ashes and the bots have no firemaking task: bot stock, else the guide-price fallback (UNCERTAIN, see the reply)
        s.step(new QuestSteps.Collect("get some ashes", ASHES, 1).stock()
                .buy(new QuestSteps.Buy("buy ashes at the market", ASHES, 1, "the Draynor market", DRAYNOR_MARKET))
                .done(p -> has(ASHES, 1).test(p) || pasted(p)));

        // ---- Draynor: Aggie's paste and dye, Ned's wig, Keli's key print
        s.step(coins("put coins in the pack for Aggie", 5).done(p -> has(COINS, 5).test(p) || has(YELLOW_DYE, 1).test(p) || wigged(p)));
        s.step(new QuestSteps.Collect("get the skin paste", PASTE, 1)
                .then(new QuestSteps.Walk("walk to Aggie's house", AGGIE_AT, 2))
                .then(new QuestSteps.Talk("ask Aggie for skin paste", new int[]{AGGIE}, 1, 1).until(has(PASTE, 1)))
                .done(QuestPrinceAliRescue::pasted));
        s.step(new QuestSteps.Collect("get yellow dye", YELLOW_DYE, 1)
                .then(new QuestSteps.Walk("walk to Aggie's house", AGGIE_AT, 2))
                .then(new QuestSteps.Talk("ask Aggie for yellow dye", new int[]{AGGIE}, 5, 2).until(has(YELLOW_DYE, 1)))
                .done(p -> has(YELLOW_DYE, 1).test(p) || wigged(p)));
        s.step(new QuestSteps.Collect("get a wig from Ned", WIG, 1)
                .then(new QuestSteps.Walk("walk to Ned's house", NED_AT, 2))
                .then(nedDoor())
                .then(new QuestSteps.Talk("ask Ned to knit a wig", new int[]{NED}, "I'd like to talk about Prince Ali Rescue.", 1, 2, 1).until(has(WIG, 1)))
                .done(p -> has(WIG, 1).test(p) || wigged(p)));
        s.step(mix("dye the wig blonde", YELLOW_DYE, WIG, YELLOW_WIG, false).done(QuestPrinceAliRescue::wigged));
        s.step(new QuestSteps.Collect("get a print of Keli's key", PRINT, 1)
                .then(new QuestSteps.Walk("walk to the jail door", JAIL_DOOR_OUT, 2).done(p -> inJail(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(JAIL_DOOR_OUT) <= 2))
                .then(jailDoor("into Keli's jail", true))
                .then(new QuestSteps.Talk("flatter Keli into showing the key", new int[]{KELI}, 1, 2).until(has(PRINT, 1)))
                .done(QuestPrinceAliRescue::printed));

        // ---- Al Kharid: Osman makes the key
        s.step(new QuestSteps.Walk("back to Osman", OSMAN_AT, 3).done(QuestPrinceAliRescue::keyMade));
        s.step(new QuestSteps.Talk("give Osman the print and the bar", new int[]{OSMAN}).until(QuestPrinceAliRescue::keyMade).done(QuestPrinceAliRescue::keyMade));

        // ---- Draynor: the key from Leela, the guard, Keli, the cell, the prince
        s.step(coins("put coins in the pack for a spare key", 15).done(p -> has(COINS, 15).test(p) || has(KEY, 1).test(p) || freed(p)));
        s.step(new QuestSteps.Collect("get the cell key", KEY, 1)
                .then(new QuestSteps.Walk("walk to Leela", LEELA_AT, 3))
                .then(new QuestSteps.Talk("collect the key from Leela", new int[]{LEELA}).until(has(KEY, 1)))
                .done(p -> has(KEY, 1).test(p) || freed(p)));
        s.step(new QuestSteps.Walk("walk to the jail door", JAIL_DOOR_OUT, 2).done(p -> freed(p) || inCell(p) || inJail(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(JAIL_DOOR_OUT) <= 2));
        s.step(jailDoor("into Keli's jail", true).done(p -> freed(p) || inCell(p) || inJail(p)));
        s.step(new QuestSteps.Talk("get Joe the guard drunk", new int[]{JOE}).until(QuestPrinceAliRescue::drunk).done(QuestPrinceAliRescue::drunk));
        // keli-gone isn't saved and lasts 350 ticks: a bot back after a restart at stage 50 needs a fresh rope and ties her again
        java.util.function.Predicate<Player> tied = p -> keliGone(p) || freed(p) || inCell(p);
        s.step(new QuestSteps.Collect("get a rope for Keli", ROPE, 1).stock()
                .buy(new QuestSteps.Buy("buy a rope", ROPE, 1, "the Shantay Pass Shop", SHANTAY_SHOP))
                .done(p -> has(ROPE, 1).test(p) || tied.test(p)));
        s.step(new QuestSteps.Walk("back to the jail door", JAIL_DOOR_OUT, 2).done(p -> tied.test(p) || inJail(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(JAIL_DOOR_OUT) <= 2));
        s.step(jailDoor("back into Keli's jail", true).done(p -> tied.test(p) || inJail(p)));
        s.step(new QuestSteps.UseOn("tie Keli up with the rope", ROPE, new int[]{KELI}, null, IntType.NPC, KELI_AT, 8).until(QuestPrinceAliRescue::keliGone).done(tied));
        s.step(new QuestSteps.Interact("unlock the cell door", new int[]{PRISON_DOOR}, null, "Open", PRISON_DOOR_AT, 3, false).until(QuestPrinceAliRescue::inCell).done(p -> inCell(p) || freed(p)));
        // both3: the prison door is an auto-walk door that locks the bot for 4 ticks (DoorActionHandler lock(4)); the Talk-to
        // made at once was dropped ("dialogue never opened"), and the Talk's retry then opened the nearest door, the cell's,
        // walking the bot back out. Let the lock run out first.
        s.step(new QuestSteps.Custom("let the cell door's walk finish", 20, null, r -> r.bot.getLocks().isInteractionLocked() || r.bot.getLocks().isMovementLocked() || r.bot.getWalkingQueue().isMoving() ? QuestSteps.St.RUNNING : QuestSteps.St.DONE)
                .done(QuestPrinceAliRescue::freed));
        s.step(new QuestSteps.Talk("give the prince the disguise", new int[]{PRINCE}).until(QuestPrinceAliRescue::freed).done(QuestPrinceAliRescue::freed));
        s.step(new QuestSteps.Interact("out of the cell", new int[]{PRISON_DOOR}, null, "Open", PRISON_DOOR_AT, 3, false).until(p -> !inCell(p)).done(p -> !inCell(p)));
        s.step(jailDoor("out of the jail", false).done(p -> stageAtLeast(Q, 100).test(p) || !inJail(p) && !inCell(p)));

        // ---- Al Kharid: the reward
        s.step(new QuestSteps.Walk("back to Hassan in the palace", HASSAN_AT, 3));
        s.step(new QuestSteps.Talk("collect the reward from Hassan", new int[]{HASSAN}).until(stageAtLeast(Q, 100)));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        return s;
    }
}
