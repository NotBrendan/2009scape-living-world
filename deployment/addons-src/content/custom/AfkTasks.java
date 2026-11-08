package content.custom;

import content.data.consumables.Consumables;
import content.global.skill.gather.mining.MiningNode;
import content.global.skill.smithing.smelting.Bar;
import content.global.skill.smithing.smelting.SmeltingPulse;
import core.api.StartupListener;
import core.game.bots.ScriptAPI;
import core.game.container.Container;
import core.game.ge.GrandExchange;
import core.game.ge.PriceIndex;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.interaction.Option;
import core.game.node.Node;
import core.game.node.entity.combat.equipment.WeaponInterface;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.GroundItem;
import core.game.node.item.GroundItemManager;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.system.command.CommandPlugin;
import core.game.system.command.CommandSet;
import core.game.system.command.Command;
import core.game.system.command.CommandMapping;
import core.game.system.command.Privilege;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.Point;
import core.game.world.map.RegionManager;
import core.game.world.map.path.Path;
import core.game.world.map.path.Pathfinder;
import core.game.world.update.flag.context.Animation;
import kotlin.Unit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * AFK task runner: the trip engine every bot uses (Runner), plus the optional ::afk command for the accounts named in -Dafk.owners.
 *
 *   ::afk <what> [bank|drop|sell] [cook] [smelt] [x<count>] [to <level>] [at <place>] [att|str|def|shared] [bury] [noloot]
 *   ::afk start <task> <bank|drop|sell|keep|cook|smelt|smeltsell> <stopAfter> <style> <bury 0|1> <loot 0|1> <stopLevel>  (old form, kept)
 *   ::afk stop | finish | status | help | list [skill] | gold [style]
 * (the design notes)
 *
 * Works around where the player stands when it starts: chops, mines, fishes, picks,
 * pickpockets or fights there, and when the inventory is full either walks to the
 * nearest bank and back or drops the haul. Only accounts in OWNERS may use it.
 */
public final class AfkTasks implements StartupListener {
    /** Accounts allowed to use ::afk, from -Dafk.owners=name1,name2 (case does not matter). Empty by default, so ::afk is off. */
    static final Set<String> OWNERS = ownersFromProperty();

    static Set<String> ownersFromProperty() {
        Set<String> s = new HashSet<>();
        for (String n : System.getProperty("afk.owners", "").split(",")) {
            n = n.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) s.add(n);
        }
        return s;
    }
    static final Random RNG = new Random();
    // audit 10-23 A-11: a runner that goTo() already ended (a toll or gate it can't pass, "Can't afford the trip east") is
    // never stored. Callers put after goTo, and a halted runner left here kept the bot "working" for ever (nothing removes it).
    static final Map<String, Runner> RUNNING = new HashMap<String, Runner>() {
        @Override public Runner put(String k, Runner r) { return r != null && r.halted ? get(k) : super.put(k, r); }
    };
    /** audit 10-23 A-11: starts a runner's walk, then stores and submits it only if the walk didn't end it (a toll, a gate, a fare). */
    static boolean launch(Runner r, State s, Location where) {
        if (!r.goTo(s, where) || r.halted) return false;
        RUNNING.put(key(r.p), r);
        GameWorld.getPulser().submit(r);
        return true;
    }
    static final boolean DEBUG = Boolean.getBoolean("afk.debug");

    enum Kind { TREE, ROCK, FISH, PICK, THIEVE, FIGHT, GROUND, SMELT, BANK } // BANK: batch 23 T4, a process fed from the bank (players only)

    static final int[] AXES = {1351, 1349, 1353, 1361, 1355, 1357, 1359, 6739};
    static final int[] PICKAXES = {1265, 1267, 1269, 1273, 1271, 1275, 15259};
    static final int[] HERBS = {199, 201, 203, 205, 207, 209, 211, 213, 215, 217, 219, 2485, 3049, 3051};
    static final int[] RUNES = {554, 555, 556, 557, 558, 559, 561, 562, 563, 564};
    static final int[] BEADS = {1470, 1472, 1474, 1476};
    static final int[] BARS = {2349, 2351, 2353, 2355, 2357, 2359, 2361};
    static final int BONES = 526, BIG_BONES = 532, FEATHER = 314, COWHIDE = 1739, COINS = 995;

    enum Task {
        // Woodcutting
        BEST_TREE("best_tree", "Best trees for my level", Kind.TREE, names("Tree", "Dead tree", "Evergreen", "Achey tree", "Oak", "Willow", "Maple tree", "Yew", "Magic tree"), "Chop down", ids(1511, 2862, 1521, 1519, 1517, 1515, 1513), AXES),
        TREE("tree", "Trees", Kind.TREE, names("Tree", "Dead tree", "Evergreen"), "Chop down", ids(1511), AXES),
        OAK("oak", "Oaks", Kind.TREE, names("Oak"), "Chop down", ids(1521), AXES),
        WILLOW("willow", "Willows", Kind.TREE, names("Willow"), "Chop down", ids(1519), AXES),
        MAPLE("maple", "Maples", Kind.TREE, names("Maple tree"), "Chop down", ids(1517), AXES),
        YEW("yew", "Yews", Kind.TREE, names("Yew"), "Chop down", ids(1515), AXES),
        MAGIC("magic", "Magic trees", Kind.TREE, names("Magic tree"), "Chop down", ids(1513), AXES),
        // Mining (target is the ore the rock gives)
        BEST_ROCK("best_rock", "Best ore for my level", Kind.ROCK, null, "Mine", ids(436, 438, 434, 440, 442, 453, 444, 447, 449, 451), PICKAXES),
        COPPER("copper", "Copper", Kind.ROCK, null, "Mine", ids(436), PICKAXES),
        TIN("tin", "Tin", Kind.ROCK, null, "Mine", ids(438), PICKAXES),
        CLAY("clay", "Clay", Kind.ROCK, null, "Mine", ids(434), PICKAXES),
        IRON("iron", "Iron", Kind.ROCK, null, "Mine", ids(440), PICKAXES),
        SILVER("silver", "Silver", Kind.ROCK, null, "Mine", ids(442), PICKAXES),
        COAL("coal", "Coal", Kind.ROCK, null, "Mine", ids(453), PICKAXES),
        GOLD("gold", "Gold", Kind.ROCK, null, "Mine", ids(444), PICKAXES),
        MITHRIL("mithril", "Mithril", Kind.ROCK, null, "Mine", ids(447), PICKAXES),
        ADAMANT("adamant", "Adamantite", Kind.ROCK, null, "Mine", ids(449), PICKAXES),
        RUNITE("runite", "Runite", Kind.ROCK, null, "Mine", ids(451), PICKAXES),
        // Fishing (target is the spot option)
        NET("net", "Shrimp/anchovies", Kind.FISH, names("Fishing spot"), "Net", ids(317, 321), ids(303)),
        BAIT("bait", "Sardine/herring/pike", Kind.FISH, names("Fishing spot"), "Bait", ids(327, 345, 349), ids(307)),
        LURE("lure", "Trout/salmon", Kind.FISH, names("Fishing spot"), "Lure", ids(335, 331), ids(309)),
        CAGE("cage", "Lobster", Kind.FISH, names("Fishing spot"), "Cage", ids(377), ids(301)),
        HARPOON("harpoon", "Tuna/swordfish/shark", Kind.FISH, names("Fishing spot"), "Harpoon", ids(359, 371, 383), ids(311)),
        // Thieving
        MAN("pp_man", "Men and women", Kind.THIEVE, names("Man", "Woman"), "Pickpocket", ids(COINS), null),
        FARMER("pp_farmer", "Farmers", Kind.THIEVE, names("Farmer"), "Pickpocket", ids(COINS, 5318), null),
        WARRIOR("pp_warrior", "Warrior women/Al-Kharid warriors", Kind.THIEVE, names("Warrior woman", "Al-Kharid warrior"), "Pickpocket", ids(COINS), null),
        GUARD("pp_guard", "Guards", Kind.THIEVE, names("Guard"), "Pickpocket", ids(COINS), null),
        // Combat (products are what gets looted when looting is on)
        AUTO("auto", "Best target for my level", Kind.FIGHT, null, null, ids(BONES), null),
        CHICKEN("chicken", "Chickens", Kind.FIGHT, names("Chicken"), null, ids(FEATHER, BONES), null),
        COW("cow", "Cows", Kind.FIGHT, names("Cow", "Cow calf"), null, ids(COWHIDE, BONES), null),
        GOBLIN("goblin", "Goblins", Kind.FIGHT, names("Goblin"), null, ids(BONES), null),
        RAT("giant_rat", "Giant rats", Kind.FIGHT, names("Giant rat"), null, ids(BONES), null),
        F_MAN("man", "Men and women", Kind.FIGHT, names("Man", "Woman"), null, ids(BONES), null),
        AL_KHARID("al_kharid", "Al-Kharid warriors", Kind.FIGHT, names("Al-Kharid warrior"), null, ids(BONES), null),
        F_GUARD("guard", "Guards", Kind.FIGHT, names("Guard"), null, ids(BONES), null),
        BARBARIAN("barbarian", "Barbarians", Kind.FIGHT, names("Barbarian"), null, ids(BONES), null),
        ROCK_CRAB("rock_crab", "Rock crabs", Kind.FIGHT, names("Rock crab", "Rocks"), null, ids(), null),
        HILL_GIANT("hill_giant", "Hill giants", Kind.FIGHT, names("Hill Giant"), null, ids(BIG_BONES), null),
        MOSS_GIANT("moss_giant", "Moss giants", Kind.FIGHT, names("Moss giant"), null, ids(BIG_BONES), null),
        // Collecting
        FEATHERS("c_feathers", "Feathers (chickens)", Kind.FIGHT, names("Chicken"), null, ids(FEATHER), null),
        COWHIDES("c_cowhides", "Cowhides (cows)", Kind.FIGHT, names("Cow", "Cow calf"), null, ids(COWHIDE), null),
        BIGBONES("c_bigbones", "Big bones (hill giants)", Kind.FIGHT, names("Hill Giant"), null, ids(BIG_BONES), null),
        FLAX("c_flax", "Flax", Kind.PICK, names("Flax"), "Pick", ids(1779), null),
        WHEAT("c_wheat", "Wheat", Kind.PICK, names("Wheat"), "Pick", ids(1947), null),
        CABBAGE("c_cabbage", "Cabbages", Kind.PICK, names("Cabbage"), "Pick", ids(1965), null),
        POTATO("c_potato", "Potatoes", Kind.PICK, names("Potato"), "Pick", ids(1942), null),
        ONION("c_onion", "Onions", Kind.PICK, names("Onion"), "Pick", ids(1957), null),
        BEEF("c_beef", "Raw beef (cows)", Kind.FIGHT, names("Cow", "Cow calf"), null, ids(2132), null),
        RAW_CHICKEN("c_rawchicken", "Raw chicken (chickens)", Kind.FIGHT, names("Chicken"), null, ids(2138), null),
        RAT_MEAT("c_ratmeat", "Raw rat meat (giant rats)", Kind.FIGHT, names("Giant rat"), null, ids(2134), null),
        BONES_ANY("c_bones", "Bones (goblins, men, rats)", Kind.FIGHT, names("Goblin", "Man", "Woman", "Giant rat"), null, ids(BONES), null),
        COINS_MEN("c_coins", "Coins (men and women)", Kind.FIGHT, names("Man", "Woman"), null, ids(COINS), null),
        LIMPWURT("c_limpwurt", "Limpwurt roots (hill giants)", Kind.FIGHT, names("Hill Giant"), null, ids(225, BIG_BONES), null),
        HERB_DRUIDS("c_herbs", "Herbs (chaos druids)", Kind.FIGHT, names("Chaos druid"), null, HERBS, null),
        IMP_BEADS("c_beads", "Beads (imps)", Kind.FIGHT, names("Imp"), null, BEADS, null),
        WIZ_RUNES("c_runes", "Runes (dark wizards)", Kind.FIGHT, names("Dark wizard"), null, RUNES, null),
        WOOL("c_wool", "Wool (shear sheep)", Kind.THIEVE, names("Sheep"), "Shear", ids(1737), ids(1735)),
        EGGS("c_eggs", "Eggs (ground spawns)", Kind.GROUND, null, null, ids(1944), null),
        SNAPE("c_snape", "Snape grass", Kind.GROUND, null, null, ids(231), null),                      // for prayer potions (second consultant check D1)
        ESSENCE("c_ess", "Rune essence", Kind.ROCK, names("Rune Essence"), "Mine", ids(1436, 7936), null), // Aubury's mine (D3)
        SMELT_BEST("smelt_best", "Best bars for my level (ores from bank)", Kind.SMELT, null, null, BARS, null),
        SMELT_BRONZE("smelt_bronze", "Bronze bars", Kind.SMELT, names("BRONZE"), null, ids(2349), null),
        SMELT_IRON("smelt_iron", "Iron bars", Kind.SMELT, names("IRON"), null, ids(2351), null),
        SMELT_SILVER("smelt_silver", "Silver bars", Kind.SMELT, names("SILVER"), null, ids(2355), null),
        SMELT_STEEL("smelt_steel", "Steel bars", Kind.SMELT, names("STEEL"), null, ids(2353), null),
        SMELT_GOLD("smelt_gold", "Gold bars", Kind.SMELT, names("GOLD"), null, ids(2357), null),
        SMELT_MITHRIL("smelt_mithril", "Mithril bars", Kind.SMELT, names("MITHRIL"), null, ids(2359), null),
        SMELT_ADAMANT("smelt_adamant", "Adamant bars", Kind.SMELT, names("ADAMANT"), null, ids(2361), null),
        GROUND_ANY("c_ground", "Anything lying around (respawning items)", Kind.GROUND, null, null, null, null),
        // batch 23 T4 (design 2.2): the bank loop of a Process (smith, cook, fletch, burn, spin, bury from the bank). Players only:
        // products null keeps it out of the bots' jobs (SmartAdventurers.JOBS) and the market catalogues (MarketBrain).
        BANK_LOOP("from_bank", "Processing from the bank", Kind.BANK, null, null, null, null);

        final String key, label, option;
        final Kind kind;
        final String[] targets;
        final int[] products, tools;

        Task(String key, String label, Kind kind, String[] targets, String option, int[] products, int[] tools) {
            this.key = key; this.label = label; this.kind = kind; this.targets = targets;
            this.option = option; this.products = products; this.tools = tools;
        }

        static Task byKey(String key) {
            for (Task t : values()) if (t.key.equalsIgnoreCase(key)) return t;
            return null;
        }
    }

    static String[] names(String... n) { return n; }
    static int[] ids(int... i) { return i; }
    static boolean has(int[] a, int v) { if (a != null) for (int x : a) if (x == v) return true; return false; }

    /** Banks a player can walk to, as the tile in front of the booths. Lumbridge's is upstairs, so it is left out. */
    /** Tile in front of each furnace; Al Kharid's is behind the toll gate. */
    static final Location[] FURNACES = {
            Location.create(3227, 3255, 0), // Lumbridge
            Location.create(2973, 3370, 0), // Falador
            Location.create(3108, 3500, 0), // Edgeville
            Location.create(3274, 3186, 0), // Al Kharid
    };

    static final class Spot {
        final String name; final Location at;
        Spot(String name, int x, int y) { this.name = name; this.at = Location.create(x, y, 0); }
    }

    static Spot sp(String n, int x, int y) { return new Spot(n, x, y); }

    /** Chaos druids have no fixed spot in the table: take the surface spot the hunt map found from the spawn data. */
    static void ensureHerbSpots() {
        if (SPOTS.get(Task.HERB_DRUIDS) != null) return;
        for (HuntMap.Hunt h : HuntMap.HUNTS) if (h.name != null && h.name.equalsIgnoreCase("Chaos druid") && h.spot.getY() < 6400) { SPOTS.put(Task.HERB_DRUIDS, new Spot[]{sp("the chaos druids", h.spot.getX(), h.spot.getY())}); return; }
    }

    static final Spot VSE = sp("the Varrock south-east mine", 3287, 3366), VSW = sp("the Varrock south-west mine", 3180, 3371),
            RIM = sp("the Rimmington mine", 2977, 3240), LSE = sp("the Lumbridge swamp mine", 3228, 3148),
            LSW = sp("the west Lumbridge swamp mine", 3146, 3150), BARB = sp("Barbarian Village mine", 3081, 3421),
            LUMTREES = sp("the trees west of Lumbridge", 3164, 3221), DRAY = sp("Draynor", 3087, 3236),
            SEERS_MAPLE = sp("the Seers' Village maples", 2727, 3501), EDGE_YEW = sp("the Edgeville yews", 3087, 3476),
            SEERS_MAGIC = sp("the Seers' Village magic trees", 2702, 3397), FALA_YEW = sp("the Falador yews", 3000, 3313),
            DRAY_FISH = sp("the Draynor fishing spots", 3087, 3228), BARB_FISH = sp("the Barbarian Village river", 3104, 3424),
            LUM_FISH = sp("the Lumbridge river", 3239, 3244), CATH_FISH = sp("the Catherby fishing spots", 2837, 3432),
            CATH_NET = sp("the Catherby bait spots", 2848, 3428), // pathing review 10-23 T-2: the Net/Bait spots (2844,3429 and 2853-2855,3423), within 18 of here
            LUM_COWS = sp("the Lumbridge cow field", 3257, 3270), FAL_COWS = sp("the Falador farm cows", 3032, 3305),
            LUM_CHICK = sp("the Lumbridge chicken pen", 3233, 3295), FAL_CHICK = sp("the Falador farm chickens", 3017, 3291),
            LUM_GOB = sp("the Lumbridge goblins", 3250, 3235), LUM_RATS = sp("the Lumbridge swamp rats", 3197, 3205),
            LUM_MEN = sp("Lumbridge", 3222, 3218), BARB_VIL = sp("Barbarian Village", 3080, 3420),
            DARK_WIZ = sp("the dark wizards' circle", 3226, 3370), FLAX_F = sp("the Seers' Village flax field", 2742, 3445),
            WHEAT_F = sp("the wheat field north of Lumbridge", 3160, 3295), CABBAGE_F = sp("the cabbage patch south of Falador", 3054, 3290),
            POTATO_F = sp("the potato field", 3146, 3285), ONION_F = sp("the onion patch by the Lumbridge farm", 3189, 3268),
            SHEEP_F = sp("the Lumbridge sheep pen", 3200, 3266), EGG_F = sp("the Lumbridge chicken farm", 3229, 3299),
            VARROCK_GUARDS = sp("Varrock square", 3213, 3424), DRAY_MARKET = sp("Draynor market", 3080, 3250),
            KARA_MINE = sp("the Karamja mine south of Brimhaven", 2846, 3034); // gold, mithril and adamantite in the jungle

    static final Map<Task, Spot[]> SPOTS = new HashMap<>();
    static {
        SPOTS.put(Task.COPPER, new Spot[]{VSE, LSE, RIM});
        SPOTS.put(Task.TIN, new Spot[]{VSE, VSW, LSE, RIM});
        SPOTS.put(Task.CLAY, new Spot[]{VSW, RIM});
        SPOTS.put(Task.IRON, new Spot[]{VSE, VSW, RIM});
        SPOTS.put(Task.SILVER, new Spot[]{VSW});
        SPOTS.put(Task.COAL, new Spot[]{BARB, LSW});
        SPOTS.put(Task.GOLD, new Spot[]{RIM, KARA_MINE});
        SPOTS.put(Task.MITHRIL, new Spot[]{LSW, KARA_MINE});
        SPOTS.put(Task.ADAMANT, new Spot[]{LSW, KARA_MINE});
        SPOTS.put(Task.BEST_ROCK, new Spot[]{VSE, VSW, RIM, LSE});
        SPOTS.put(Task.TREE, new Spot[]{LUMTREES, DRAY});
        SPOTS.put(Task.OAK, new Spot[]{LUMTREES, DRAY});
        SPOTS.put(Task.WILLOW, new Spot[]{DRAY, LUMTREES});
        SPOTS.put(Task.MAPLE, new Spot[]{SEERS_MAPLE});
        SPOTS.put(Task.YEW, new Spot[]{EDGE_YEW, FALA_YEW});
        SPOTS.put(Task.MAGIC, new Spot[]{SEERS_MAGIC});
        SPOTS.put(Task.BEST_TREE, new Spot[]{DRAY, LUMTREES});
        SPOTS.put(Task.NET, new Spot[]{DRAY_FISH}); // pathing review 10-23 T-2: not Catherby (a small-net runner there caught nothing in 3 minutes on the trial server; cause not found)
        SPOTS.put(Task.BAIT, new Spot[]{DRAY_FISH, CATH_NET}); // pathing review 10-23 T-2: Catherby too (Draynor was the only one, ~240 tiles from a Catherby fisher; 19 fish in 3 minutes there on the trial server)
        SPOTS.put(Task.LURE, new Spot[]{BARB_FISH, LUM_FISH});
        SPOTS.put(Task.CAGE, new Spot[]{CATH_FISH});
        SPOTS.put(Task.HARPOON, new Spot[]{CATH_FISH});
        for (Task t : new Task[]{Task.COW, Task.COWHIDES, Task.BEEF}) SPOTS.put(t, new Spot[]{LUM_COWS, FAL_COWS});
        for (Task t : new Task[]{Task.CHICKEN, Task.FEATHERS, Task.RAW_CHICKEN}) SPOTS.put(t, new Spot[]{LUM_CHICK}); // the Falador coop is fenced: bots stood at its wall (4 watchdog rescues in 49 min, audit §13)
        SPOTS.put(Task.GOBLIN, new Spot[]{LUM_GOB});
        SPOTS.put(Task.RAT, new Spot[]{LUM_RATS});
        SPOTS.put(Task.RAT_MEAT, new Spot[]{LUM_RATS});
        SPOTS.put(Task.BONES_ANY, new Spot[]{LUM_GOB, LUM_MEN});
        for (Task t : new Task[]{Task.F_MAN, Task.MAN, Task.COINS_MEN}) SPOTS.put(t, new Spot[]{LUM_MEN});
        SPOTS.put(Task.BARBARIAN, new Spot[]{BARB_VIL});
        SPOTS.put(Task.WIZ_RUNES, new Spot[]{DARK_WIZ});
        SPOTS.put(Task.F_GUARD, new Spot[]{VARROCK_GUARDS});
        SPOTS.put(Task.GUARD, new Spot[]{VARROCK_GUARDS});
        SPOTS.put(Task.FARMER, new Spot[]{sp("the Draynor wheat field", 3118, 3273), sp("the Lumbridge farm", 3162, 3295)}); // audit 10-21 B4: no farmer at the market // batch 26 fix 4: were 3124,3276 (inside the field, through its gate) and 3156,3300 (the old walker pressed into the farm's west fence, ~160 rescues/24 h); TestB26 SPOTS: 3118,3273 is 4 tiles from the field's farmer with no door, 3162,3295 is the farm's open south side (0 doors)
        // Men and Al Kharid warriors, so a skint bot has an honest way to its first coins (pickpocketing pays on the spot).
        if (SPOTS.get(Task.MAN) == null) SPOTS.put(Task.MAN, new Spot[]{sp("the Lumbridge men", 3222, 3218), sp("the Varrock square folk", 3212, 3428), sp("the Draynor villagers", 3093, 3247)});
        if (SPOTS.get(Task.WARRIOR) == null) SPOTS.put(Task.WARRIOR, new Spot[]{sp("the Al Kharid warriors", 3293, 3170)});
        if (SPOTS.get(Task.AL_KHARID) == null) SPOTS.put(Task.AL_KHARID, new Spot[]{sp("the Al Kharid warriors", 3293, 3170)}); // P16 item 3: "warriors at al kharid" had no spot (fight task, same palace as WARRIOR)
        SPOTS.put(Task.FLAX, new Spot[]{FLAX_F});
        SPOTS.put(Task.SNAPE, new Spot[]{sp("the hobgoblin peninsula", 2906, 3296)}); // batch 21 item 3: was 2970,3182, a water tile off Mudskipper Point (40 of 194 replay fails); the snape spawns are 2905,3297 and 2907,3295 (ground_spawns.json), standable and reachable on nav-p0.bin
        SPOTS.put(Task.ESSENCE, new Spot[]{sp("the rune essence mine", 2911, 4832)});
        SPOTS.put(Task.WHEAT, new Spot[]{WHEAT_F});
        SPOTS.put(Task.CABBAGE, new Spot[]{CABBAGE_F});
        SPOTS.put(Task.POTATO, new Spot[]{POTATO_F});
        SPOTS.put(Task.ONION, new Spot[]{ONION_F});
        SPOTS.put(Task.WOOL, new Spot[]{SHEEP_F});
        SPOTS.put(Task.EGGS, new Spot[]{EGG_F});
    }

    static final Map<Integer, Spot[]> ORE_SPOTS = new HashMap<>();
    static {
        for (Task t : new Task[]{Task.COPPER, Task.TIN, Task.CLAY, Task.IRON, Task.SILVER, Task.COAL, Task.GOLD, Task.MITHRIL, Task.ADAMANT})
            ORE_SPOTS.put(t.products[0], SPOTS.get(t));
    }

    static final Location[] BANKS = {
            Location.create(3092, 3245, 0), // Draynor
            Location.create(3185, 3438, 0), // Varrock west
            Location.create(3253, 3421, 0), // Varrock east
            Location.create(2946, 3370, 0), // Falador west
            Location.create(3012, 3356, 0), // Falador east
            Location.create(3094, 3492, 0), // Edgeville
            Location.create(3270, 3167, 0), // Al Kharid
            Location.create(2809, 3441, 0), // Catherby
            Location.create(2726, 3492, 0), // Seers' Village
            Location.create(2617, 3332, 0), // Ardougne north
            Location.create(2654, 3284, 0), // Ardougne south
            Location.create(2612, 3092, 0), // Yanille
            Location.create(2586, 3420, 0), // Fishing Guild
            Location.create(3512, 3480, 0), // Canifis
            Location.create(2852, 2955, 0), // Shilo Village
    };

    @Override
    public void startup() {
        // Player ::commands are switched off on this world (world.player_commands = false), so the
        // modern command mapping would silently ignore a non-admin. The legacy PLAYER command set
        // is open to everyone; the owner check in handle() keeps it to the listed accounts.
        CommandSet.PLAYER.getPlugins().add(new AfkCommand());
        // With world.player_commands = true the modern mapping handles it (no "Deprecated command" line).
        AfkCommand modern = new AfkCommand();
        for (String name : new String[]{"afk", "afkstop"}) {
            CommandMapping.INSTANCE.register(new Command(name, Privilege.STANDARD, "", "", (player, args) -> {
                modern.parse(player, name, args);
                return Unit.INSTANCE;
            }));
        }
        System.out.println(OWNERS.isEmpty() ? "[AfkTasks] ::afk disabled (start with -Dafk.owners=name1,name2 to enable it)" : "[AfkTasks] ::afk enabled for " + OWNERS);
    }

    static final class AfkCommand extends CommandPlugin {
        @Override
        public core.plugin.Plugin<Object> newInstance(Object arg) { return this; }

        @Override
        public boolean parse(Player player, String name, String[] args) {
            if (name == null) return false;
            String n = name.toLowerCase(Locale.ROOT);
            if (!n.equals("afk") && !n.equals("afkstop")) return false;
            try {
                if (n.equals("afkstop")) handle(player, new String[]{"afk", "stop"});
                else handle(player, args);
            } catch (Throwable t) {
                player.sendMessage("<col=ff0000>AFK: something went wrong (" + t.getClass().getSimpleName() + ").");
                System.out.println("[AfkTasks] command failed: " + t);
            }
            return true;
        }
    }

    static void handle(Player player, String[] args) {
        if (!OWNERS.contains(player.getUsername().toLowerCase(Locale.ROOT))) {
            player.sendMessage("Nothing interesting happens.");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "stop": stop(player, "Stopped."); return;
            case "status": {
                Runner r = RUNNING.get(key(player));
                player.sendMessage(r == null ? "AFK: not running. ::afk help for the options." : "AFK: " + r.describe());
                player.sendMessage("AFK walking: " + Runner.navMode()); // batch 23 T2: which walking mode a player's runner uses
                return;
            }
            case "list": list(player, args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null); return;
            case "help": case "?": help(player); return;
            case "gold": {
                String st = args.length > 2 ? styleWord(args[2]) : "keep";
                if (st == null) { player.sendMessage("<col=ff0000>AFK: style is att, str, def or shared."); return; }
                startGold(player, st);
                return;
            }
            case "start": break; // the old positional form, kept as it was (the client window and old habits)
            case "finish": { // bank what you have, then stop (instead of aborting mid-trip)
                Runner r = RUNNING.get(key(player));
                if (r == null) { player.sendMessage("AFK: not running."); return; }
                r.finishWanted = true; r.finish("Finishing up: banking first.");
                player.sendMessage("<col=0b5e1f>AFK: finishing. Banking the haul, then stopping.");
                return;
            }
            default: startWords(player, args); return; // ::afk yews x500 at falador (redesign 2026-10-23)
        }
        Task task = args.length > 2 ? Task.byKey(args[2]) : null;
        if (task == null) { player.sendMessage("AFK: unknown task. Try ::afk list"); return; }
        boolean bank = args.length <= 3 || !args[3].equalsIgnoreCase("drop");
        String mode = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "bank";
        boolean sell = mode.equals("sell") || mode.equals("smeltsell");
        boolean smeltHaul = mode.startsWith("smelt");
        boolean keepAll = mode.equals("keep");          // bank everything, sell nothing to the Exchange
        boolean cookHaul = mode.startsWith("cook");     // cook the catch at a range before banking
        int stopAfter = args.length > 4 ? parse(args[4]) : 0;
        String style = args.length > 5 ? args[5].toLowerCase(Locale.ROOT) : "keep";
        boolean bury = args.length > 6 && args[6].equals("1");
        boolean loot = args.length <= 7 || args[7].equals("1");
        int stopLevel = args.length > 8 ? parse(args[8]) : 0;
        begin(player, task, bank, sell, smeltHaul, cookHaul, keepAll, stopAfter, style, bury, loot, stopLevel, null);
    }

    /** Starts a task (both command forms end here). {@code at} is a chosen spot to walk to first, or null for "here". */
    static void begin(Player player, Task task, boolean bank, boolean sell, boolean smeltHaul, boolean cookHaul, boolean keepAll, int stopAfter, String style, boolean bury, boolean loot, int stopLevel, Spot at) {
        Process pr = smeltHaul && task.kind == Kind.ROCK ? Process.SMELT : cookHaul && task.kind == Kind.FISH ? Process.COOK : Process.NONE;
        begin(player, task, bank, sell, smeltHaul, cookHaul, keepAll, stopAfter, style, bury, loot, stopLevel, at, pr, "", false);
    }

    /**
     * batch 23 T4: {@code pr} is the chain's process and {@code want} the chosen product words ("" = best for the levels).
     * {@code checked}: the word form asked for the chain, so it is checked up front (the old positional form keeps its old
     * lenient smelt/cook flags, which fall back to plain gathering).
     */
    static void begin(Player player, Task task, boolean bank, boolean sell, boolean smeltHaul, boolean cookHaul, boolean keepAll, int stopAfter, String style, boolean bury, boolean loot, int stopLevel, Spot at, Process pr, String want, boolean checked) {
        if (task.kind == Kind.BANK || checked && pr != Process.NONE) {
            String why = chainProblem(player, task, pr, want == null ? "" : want, bank, sell, at != null ? at.at : player.getLocation());
            if (why != null) { player.sendMessage("<col=ff0000>AFK: " + why); return; }
        }
        if (task.tools != null && !hasAny(player, task.tools)) {
            player.sendMessage("<col=ff0000>AFK: you need " + toolName(task) + " for that." + shopHint(player, task.tools)); // batch 23 T5: the shop's name, message only
            return;
        }
        if (task == Task.ESSENCE && !hasAny(player, PICKAXES)) { player.sendMessage("<col=ff0000>AFK: you need a pickaxe for that." + shopHint(player, PICKAXES)); return; } // redesign: it had no tool check
        if (task.kind == Kind.FISH && task == Task.BAIT && player.getInventory().getAmount(313) == 0) {
            player.sendMessage("<col=ff0000>AFK: you need fishing bait for that." + shopHint(player, ids(313)));
            return;
        }
        if (task.kind == Kind.FISH && task == Task.LURE && player.getInventory().getAmount(FEATHER) == 0) {
            player.sendMessage("<col=ff0000>AFK: you need feathers for fly fishing." + shopHint(player, ids(FEATHER)));
            return;
        }
        stop(player, null);
        if (task.kind == Kind.FIGHT && !style.equals("keep")) setStyle(player, style);
        System.out.println("[AfkTasks] " + player.getUsername() + " started " + task.key + (sell ? " sell" : bank ? " bank" : " drop") + (cookHaul ? " cook" : "") + (smeltHaul ? " smelt" : "") + (pr != Process.NONE ? " chain=" + pr.word + (want != null && !want.isEmpty() ? " '" + want + "'" : "") : "") + " at " + player.getLocation()
                + (at != null ? " -> " + at.name : "") + " mining=" + player.getSkills().getStaticLevel(Skills.MINING) + " smithing=" + player.getSkills().getStaticLevel(Skills.SMITHING)
                + " pickaxe=" + hasAny(player, PICKAXES));
        Runner r = new Runner(player, task, bank, stopAfter, bury, loot);
        r.sell = sell;
        r.smeltHaul = smeltHaul && task.kind == Kind.ROCK;
        r.keepAll = keepAll;
        r.cookHaul = cookHaul && task.kind == Kind.FISH;
        if (checked && pr != Process.NONE || task.kind == Kind.BANK) r.chain(pr, want); // batch 23 T4: sets smeltHaul / cookHaul too
        r.stopLevel = Math.min(99, stopLevel);
        r.styleSkill = style.equals("strength") ? Skills.STRENGTH : style.equals("defence") ? Skills.DEFENCE : Skills.ATTACK;
        if (at != null) { r.spot = at.at; if (!r.goTo(State.TO_SPOT, at.at)) return; } // audit 10-23 A-11: goTo ended it (finish() said why)
        RUNNING.put(key(player), r);
        GameWorld.getPulser().submit(r);
        player.sendMessage("<col=0b5e1f>AFK: " + (task.kind == Kind.THIEVE && task != Task.WOOL ? "Pickpocketing " + task.label.toLowerCase(Locale.ROOT) : task.label) + " started" + (at != null ? ", walking to " + at.name : "") + ". " + (r.process != Process.NONE && r.process != Process.SMELT && r.process != Process.COOK ? r.chainText() : task.kind == Kind.SMELT ? "Taking ores from the bank to the nearest furnace." : r.smeltHaul ? "Smelting the ore into bars, then " + (sell ? "selling" : "banking") + " them." : r.cookHaul ? "Cooking the catch, then " + (sell ? "selling" : "banking") + " it." : sell ? "Selling the haul to the GE bots at the nearest bank." : bank ? "Banking at the nearest bank." : "Dropping when full.")
                + (stopAfter > 0 ? " Stops after " + stopAfter + "." : "") + (stopLevel > 0 ? " Stops at level " + Math.min(99, stopLevel) + "." : ""));
    }

    // ------------------------------------------------------------------ the word form (redesign 2026-10-23, the design notes)

    /** Player words for each task. Every enum key is a word too (the bots and old command lines use them). */
    static final Map<String, Task> WORDS = new HashMap<>();
    /** {skill, level} a named target needs, so a player hears why instead of a task that finds nothing. */
    static final Map<Task, int[]> NEEDS = new HashMap<>();
    static final Map<Task, String> FIRST_WORD = new HashMap<>(); // the word the list shows
    static void w(Task t, String... ws) { FIRST_WORD.putIfAbsent(t, ws[0]); for (String x : ws) WORDS.put(x, t); }
    static {
        for (Task t : Task.values()) WORDS.put(t.key, t);
        w(Task.BEST_TREE, "logs", "wood", "woodcutting", "chop"); w(Task.TREE, "tree", "normal tree", "normal logs"); w(Task.OAK, "oak", "oak logs"); w(Task.WILLOW, "willow", "willow logs");
        w(Task.MAPLE, "maple", "maple tree", "maple logs"); w(Task.YEW, "yew", "yew logs"); w(Task.MAGIC, "magic", "magic tree", "magic logs");
        w(Task.BEST_ROCK, "ore", "mine", "mining", "rocks"); w(Task.COPPER, "copper", "copper ore"); w(Task.TIN, "tin", "tin ore"); w(Task.CLAY, "clay"); w(Task.IRON, "iron", "iron ore");
        w(Task.SILVER, "silver", "silver ore"); w(Task.COAL, "coal"); w(Task.GOLD, "gold", "gold ore"); w(Task.MITHRIL, "mithril", "mithril ore"); w(Task.ADAMANT, "adamant", "adamantite", "adamantite ore", "addy");
        w(Task.RUNITE, "runite", "runite ore"); w(Task.ESSENCE, "essence", "ess", "rune essence", "pure essence");
        w(Task.NET, "fish", "fishing", "shrimp", "shrimps", "anchovies", "anchovy", "small net"); w(Task.BAIT, "sardine", "herring", "pike", "bait fishing"); w(Task.LURE, "trout", "salmon", "fly fishing");
        w(Task.CAGE, "lobster", "lobsters"); w(Task.HARPOON, "tuna", "swordfish", "shark", "harpoon fishing");
        w(Task.MAN, "pickpocket men", "pickpocket", "pickpocket man", "pickpocket women", "thieving"); w(Task.FARMER, "pickpocket farmers", "pickpocket farmer"); w(Task.WARRIOR, "pickpocket warriors", "pickpocket warrior");
        w(Task.GUARD, "pickpocket guards", "pickpocket guard");
        w(Task.AUTO, "fight", "combat", "best target", "anything"); w(Task.CHICKEN, "chicken", "chickens"); w(Task.COW, "cow", "cows"); w(Task.GOBLIN, "goblin", "goblins"); w(Task.RAT, "rat", "rats", "giant rats", "giant rat");
        w(Task.F_MAN, "men", "man", "women", "woman"); w(Task.AL_KHARID, "warriors", "al kharid warriors", "al-kharid warriors"); w(Task.F_GUARD, "guards"); w(Task.BARBARIAN, "barbarian", "barbarians");
        w(Task.ROCK_CRAB, "crabs", "rock crabs", "rock crab"); w(Task.HILL_GIANT, "hill giants", "hill giant", "giants"); w(Task.MOSS_GIANT, "moss giants", "moss giant");
        w(Task.FEATHERS, "feathers", "feather"); w(Task.COWHIDES, "cowhides", "cowhide", "hides"); w(Task.BIGBONES, "big bones"); w(Task.BEEF, "beef", "raw beef"); w(Task.RAW_CHICKEN, "raw chicken");
        w(Task.RAT_MEAT, "rat meat", "raw rat meat"); w(Task.BONES_ANY, "bones"); w(Task.COINS_MEN, "coins"); w(Task.LIMPWURT, "limpwurt", "limpwurts", "limpwurt roots");
        w(Task.HERB_DRUIDS, "herbs", "chaos druids", "druids"); w(Task.IMP_BEADS, "beads", "imp beads", "imps"); w(Task.WIZ_RUNES, "runes", "dark wizards", "wizards");
        w(Task.FLAX, "flax"); w(Task.WHEAT, "wheat", "grain"); w(Task.CABBAGE, "cabbage", "cabbages"); w(Task.POTATO, "potato", "potatoes"); w(Task.ONION, "onion", "onions");
        w(Task.WOOL, "wool", "sheep", "shear sheep"); w(Task.EGGS, "egg", "eggs"); w(Task.SNAPE, "snape", "snape grass"); w(Task.GROUND_ANY, "ground", "ground items");
        w(Task.SMELT_BEST, "bars", "smelting", "smithing"); w(Task.SMELT_BRONZE, "bronze", "bronze bars", "bronze bar"); w(Task.SMELT_IRON, "iron bars", "iron bar"); w(Task.SMELT_SILVER, "silver bars", "silver bar");
        w(Task.SMELT_STEEL, "steel", "steel bars", "steel bar"); w(Task.SMELT_GOLD, "gold bars", "gold bar"); w(Task.SMELT_MITHRIL, "mithril bars", "mithril bar"); w(Task.SMELT_ADAMANT, "adamant bars", "adamant bar", "adamantite bars");
        Object[][] needs = {{Task.OAK, Skills.WOODCUTTING, 15}, {Task.WILLOW, Skills.WOODCUTTING, 30}, {Task.MAPLE, Skills.WOODCUTTING, 45}, {Task.YEW, Skills.WOODCUTTING, 60}, {Task.MAGIC, Skills.WOODCUTTING, 75},
                {Task.IRON, Skills.MINING, 15}, {Task.SILVER, Skills.MINING, 20}, {Task.COAL, Skills.MINING, 30}, {Task.GOLD, Skills.MINING, 40}, {Task.MITHRIL, Skills.MINING, 55}, {Task.ADAMANT, Skills.MINING, 70}, {Task.RUNITE, Skills.MINING, 85},
                {Task.BAIT, Skills.FISHING, 5}, {Task.LURE, Skills.FISHING, 20}, {Task.HARPOON, Skills.FISHING, 35}, {Task.CAGE, Skills.FISHING, 40},
                {Task.SMELT_IRON, Skills.SMITHING, 15}, {Task.SMELT_SILVER, Skills.SMITHING, 20}, {Task.SMELT_STEEL, Skills.SMITHING, 30}, {Task.SMELT_GOLD, Skills.SMITHING, 40}, {Task.SMELT_MITHRIL, Skills.SMITHING, 50}, {Task.SMELT_ADAMANT, Skills.SMITHING, 70},
                {Task.FARMER, Skills.THIEVING, 10}, {Task.WARRIOR, Skills.THIEVING, 25}, {Task.GUARD, Skills.THIEVING, 40}};
        for (Object[] m : needs) NEEDS.put((Task) m[0], new int[]{(Integer) m[1], (Integer) m[2]});
    }

    static String styleWord(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "att": case "attack": case "accurate": return "attack";
            case "str": case "strength": case "aggressive": return "strength";
            case "def": case "defence": case "defense": case "defensive": return "defence";
            case "shared": case "controlled": return "shared";
            case "keep": return "keep";
            default: return null;
        }
    }

    static final String OPTION_WORDS = "bank|keep|drop|sell|cook|smelt|smeltsell|smith|fletch|burn|spin|then|bury|noloot|to|lvl|level|at|in|near|alch|superheat|low|high|force|auto|x?\\d+|(lvl|level)\\d+"; // batch 25: alch, low, high, force, auto

    /** batch 23 T4: the product words after smith / fletch ("smith iron platebody"), up to the next option or style word. */
    static String productWords(List<String> w, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < w.size() && styleWord(w.get(i)) == null && !w.get(i).matches(OPTION_WORDS); i++) sb.append(sb.length() > 0 ? " " : "").append(w.get(i));
        return sb.toString();
    }

    /** ::afk <what> [options in any order]. */
    static void startWords(Player p, String[] args) {
        List<String> w = new ArrayList<>();
        for (int i = 1; i < args.length; i++) for (String s : args[i].toLowerCase(Locale.ROOT).replace(',', ' ').trim().split("\\s+")) if (!s.isEmpty()) w.add(s);
        Task task = null; int used = 0;
        List<Process> procs = new ArrayList<>(); String want = "";
        // batch 23 T4: a process word first is a job from the bank: ::afk smith platebody, ::afk cook, ::afk fletch, ::afk burn, ::afk spin, ::afk bury
        Process lead = w.isEmpty() ? Process.NONE : Process.of(w.get(0));
        if (lead == Process.ALCH && w.size() > 1 && w.get(1).equals("list")) { AfkMagic.list(p, w.contains("low") || w.get(0).equals("lowalch") ? "low" : null); return; } // batch 25 R8
        List<String> magicWords = new ArrayList<>(); // batch 25: low, high, force, auto
        if (w.size() > 0 && w.get(0).equals("lowalch")) magicWords.add("low");
        if (lead == Process.SMELT) { // ::afk smelt [iron] = the old bars-from-the-bank tasks
            Task bars = w.size() > 1 ? WORDS.get(w.get(1) + " bars") : null;
            task = bars != null && bars.kind == Kind.SMELT ? bars : Task.SMELT_BEST; used = bars != null && bars.kind == Kind.SMELT ? 2 : 1;
        } else if (lead != Process.NONE && !w.get(0).equals("smithing")) { // "smithing" stays the old word for smelting from the bank
            task = Task.BANK_LOOP; used = 1; procs.add(lead);
            want = productWords(w, 1); used += want.isEmpty() ? 0 : want.split(" ").length; // smith platebody, fletch longbow, cook lobster, burn oak, bury big bones
        }
        for (int n = Math.min(4, w.size()); n >= 1 && task == null; n--) { // the longest phrase at the front: "big bones", "pickpocket men"
            String ph = String.join(" ", w.subList(0, n));
            task = WORDS.get(ph);
            if (task == null && ph.endsWith("s")) task = WORDS.get(ph.substring(0, ph.length() - 1));
            if (task != null) used = n;
        }
        if (task == null) { p.sendMessage("<col=ff0000>AFK: I don't know '" + (w.isEmpty() ? "" : w.get(0)) + "'. Try ::afk list or ::afk help."); return; }
        boolean bank = true, sell = false, bury = false, loot = true;
        int count = 0, level = 0; String style = "keep"; Spot at = null;
        for (int i = used; i < w.size(); i++) {
            String s = w.get(i);
            String st = styleWord(s);
            if (s.equals("bank") || s.equals("keep")) { bank = true; sell = false; } // 'keep' stays 'bank' (Deliver.KEEP: burn/bury leave nothing to deliver anyway)
            else if (s.equals("drop")) { bank = false; sell = false; }
            else if (s.equals("sell")) { bank = true; sell = true; }
            else if (s.equals("then")) { } // batch 23 T4: "iron then smelt then smith platebody sell" reads as a sentence; the word itself adds nothing
            else if (s.equals("smeltsell")) { procs.add(Process.SMELT); sell = true; }
            else if (Process.of(s) != Process.NONE && Process.of(s) != Process.BURY) { // batch 23 T4: cook, smelt, smith <item>, fletch [<item>], burn, spin
                Process pr = Process.of(s);
                if (procs.contains(pr)) continue;
                procs.add(pr);
                if (pr == Process.SMITH || pr == Process.FLETCH) { String x = productWords(w, i + 1); if (!x.isEmpty()) { want = x; i += x.split(" ").length; } }
            }
            else if (s.equals("bury")) bury = true;
            else if (s.equals("noloot")) loot = false;
            else if (s.equals("low") || s.equals("high") || s.equals("force") || s.equals("auto")) magicWords.add(s); // batch 25
            else if (s.matches("x?\\d{1,6}")) count = Integer.parseInt(s.replace("x", "")); // at most six digits: a longer number overflowed parseInt ("something went wrong")
            else if (s.matches("(lvl|level)\\d{1,3}")) level = Integer.parseInt(s.replaceAll("\\D", ""));
            else if ((s.equals("to") || s.equals("lvl") || s.equals("level")) && i + 1 < w.size() && w.get(i + 1).matches("\\d{1,3}")) level = Integer.parseInt(w.get(++i));
            else if (s.equals("at") || s.equals("in") || s.equals("near")) {
                StringBuilder place = new StringBuilder();
                while (i + 1 < w.size() && styleWord(w.get(i + 1)) == null && !w.get(i + 1).matches(OPTION_WORDS)) place.append(place.length() > 0 ? " " : "").append(w.get(++i));
                at = spotNamed(task, place.toString(), p); // batch 23 T5: the surveyors' clusters too ("coal at dwarven")
                if (at == null) { p.sendMessage("<col=ff0000>AFK: no place called '" + place + "' for " + task.label.toLowerCase(Locale.ROOT) + ". Places: " + places(task, p) + "."); return; }
            }
            else if (st != null) style = st;
            else { p.sendMessage("<col=ff0000>AFK: I don't know '" + s + "'. Try ::afk help."); return; }
        }
        // batch 23 T4: one process per job; "smelt then smith" (mining) is the one pair. The chain's own checks (job fits, tool,
        // recipe level, station within 220 tiles) are chainProblem's, in begin.
        if (procs.size() == 2 && procs.contains(Process.SMELT) && procs.contains(Process.SMITH)) procs.remove(Process.SMELT); // smith on a mining job smelts first anyway
        if (procs.size() > 1) { p.sendMessage("<col=ff0000>AFK: one process per job (" + procs.get(0).word + " or " + procs.get(1).word + "); 'smelt then smith' is the one pair."); return; }
        Process chainPr = procs.isEmpty() ? Process.NONE : procs.get(0);
        if (task.kind == Kind.SMELT && chainPr != Process.NONE) { p.sendMessage("<col=ff0000>AFK: bars from the bank only smelt; try ::afk smith <item> to smith bars from your bank."); return; }
        boolean cook = chainPr == Process.COOK, smelt = chainPr == Process.SMELT || chainPr == Process.SMITH;
        if (!style.equals("keep") && task.kind != Kind.FIGHT) { p.sendMessage("<col=ff0000>AFK: a combat style only matters when fighting."); return; }
        if (bury && task.kind != Kind.FIGHT) { p.sendMessage("<col=ff0000>AFK: 'bury' is for fights that drop bones."); return; }
        if (level > 99) { p.sendMessage("<col=ff0000>AFK: levels go up to 99."); return; }
        if (!magicWords.isEmpty() && !chainPr.magic()) { p.sendMessage("<col=ff0000>AFK: '" + magicWords.get(0) + "' is for Magic jobs (::afk alch <item> [low] [force] [auto])."); return; } // batch 25
        if (chainPr.magic()) { // batch 25 (AfkMagic): the guards and the fallback ladder; the resolved job rides to Runner.chain in PENDING
            if (chainPr == Process.ALCH && task != Task.BANK_LOOP) { p.sendMessage("<col=ff0000>AFK: 'alch' works from your bank (::afk alch <item>)."); return; }
            if (chainPr == Process.SUPERHEAT && task != Task.BANK_LOOP && task.kind != Kind.ROCK) { p.sendMessage("<col=ff0000>AFK: 'superheat' is for mining ore (::afk iron then superheat), or ::afk superheat <bar> from your bank."); return; }
            boolean auto = magicWords.contains("auto");
            AfkMagic.Run run = chainPr == Process.ALCH ? AfkMagic.resolveAlch(p, want, magicWords.contains("low"), magicWords.contains("force"), auto, count)
                    : AfkMagic.resolveHeat(p, task, want, auto, count);
            if (run == null) return;
            if (run.spell == null) { // auto chose a job without Magic: the furnace (ore then smelt, or bars from the bank) or just mining
                begin(p, run.task, bank, sell, run.pr == Process.SMELT, false, false, count, style, false, true, level, at, run.pr, "", true);
                return;
            }
            task = run.task != null ? run.task : task;
            want = run.spell.alch() ? MarketBrain.name(run.item).toLowerCase(Locale.ROOT) : run.want; count = 0; // x<n> is the run's cast limit (Run.castsLeft), not "made"
            AfkMagic.PENDING.put(key(p), run);
            begin(p, task, task.kind == Kind.BANK || bank, task.kind != Kind.BANK && sell, false, false, false, 0, style, false, true, level, at, chainPr, want, true);
            AfkMagic.PENDING.remove(key(p)); // begin refused before Runner.chain took it
            return;
        }
        int[] need = NEEDS.get(task);
        if (need != null && p.getSkills().getStaticLevel(need[0]) < need[1]) { p.sendMessage("<col=ff0000>AFK: " + task.label + " needs " + Skills.SKILL_NAME[need[0]] + " " + need[1] + " (you have " + p.getSkills().getStaticLevel(need[0]) + ")."); return; }
        begin(p, task, bank, sell, smelt, cook, false, count, style, bury, loot, level, at, chainPr, want, true);
    }

    static Spot spotNamed(Task t, String place) {
        Spot[] spots = SPOTS.get(t);
        if (spots == null || place.isEmpty()) return null;
        for (Spot s : spots) {
            String n = s.name.toLowerCase(Locale.ROOT).replace("'", "");
            boolean all = true;
            for (String x : place.replace("'", "").split(" ")) if (!n.contains(x)) all = false;
            if (all) return s;
        }
        return null;
    }

    static String places(Task t) {
        Spot[] spots = SPOTS.get(t);
        if (spots == null) return "none known: stand where you want to work and leave out 'at'";
        StringBuilder sb = new StringBuilder();
        for (Spot s : spots) sb.append(sb.length() > 0 ? ", " : "").append(s.name.replaceFirst("^the ", ""));
        return sb.toString();
    }

    // ------------------------------------------------------------------ batch 23 T5: smarter finding (design 2.3, 3)

    /** {@link #spotNamed(Task, String)}, then the clusters the surveyors found for this job within 400 tiles of the player (nearest first). */
    static Spot spotNamed(Task t, String place, Player p) {
        Spot s = spotNamed(t, place);
        if (s != null || place == null || place.isEmpty() || p == null) return s;
        for (int pass = 0; pass < 2; pass++) {
            Spot best = null; double d = Double.MAX_VALUE;
            for (Spot f : foundSpots(t, t.products, p, p.getLocation(), pass == 1)) {
                if (!named(f.name, place)) continue;
                double dist = f.at.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = f; }
            }
            if (best != null) return best;
        }
        return null;
    }

    static boolean named(String name, String place) {
        String n = name.toLowerCase(Locale.ROOT).replace("'", "");
        for (String x : place.toLowerCase(Locale.ROOT).replace("'", "").split(" ")) if (!x.isEmpty() && !n.contains(x)) return false;
        return true;
    }

    /** The task's own places, then up to six found ones near the player. */
    static String places(Task t, Player p) {
        StringBuilder sb = new StringBuilder();
        Spot[] spots = SPOTS.get(t);
        if (spots != null) sb.append(places(t));
        if (p != null) {
            List<Spot> f = foundSpots(t, t.products, p, p.getLocation(), false);
            if (f.isEmpty()) f = foundSpots(t, t.products, p, p.getLocation(), true);
            final Location here = p.getLocation();
            f.sort((a, b) -> Double.compare(a.at.getDistance(here), b.at.getDistance(here)));
            int n = 0;
            for (Spot s : f) { if (n++ >= 6) break; sb.append(sb.length() > 0 ? ", " : "").append(s.name.replaceFirst("^the ", "")); }
        }
        return sb.length() > 0 ? sb.toString() : places(t);
    }

    /**
     * The surveyors' busy clusters (Resources.SEEN, seen at least twice: the same rule and stand tile as
     * Resources.nearest / nearestMatching) for one key, within {@code radius} of {@code from}. {@code exact}: the key must
     * equal {@code key}, else start with it. Every cluster, not only the nearest, so a tried one can be skipped.
     */
    static List<Location> resourceClusters(String key, boolean exact, Location from, double radius) {
        List<Location> out = new ArrayList<>();
        try {
            Resources.load();
            for (Map.Entry<String, Map<Integer, Integer>> e : Resources.SEEN.entrySet()) {
                String k = e.getKey();
                if (exact ? !k.equals(key) : !k.startsWith(key)) continue;
                for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) {
                    if (c.getValue() < 2) continue;
                    int[] b = RouteMap.BEST.get(c.getKey());
                    Location l = b != null ? Location.create(b[0], b[1], 0) : Location.create(RouteMap.cx(c.getKey()) * RouteMap.CELL + 4, RouteMap.cy(c.getKey()) * RouteMap.CELL + 4, 0);
                    if (from == null || l.getDistance(from) <= radius) out.add(l);
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** "the coal rocks near Barbarian Village" (the map label within 45 tiles), else "... at x, y". */
    static String foundName(String noun, Location l) {
        String near = "";
        try { near = BotChat.WorldData.near(l.getX(), l.getY()); } catch (Throwable ignored) { }
        return "the " + noun + (near.isEmpty() ? " at " + l.getX() + ", " + l.getY() : " " + near.replaceFirst("^, ", ""));
    }

    static void addFound(List<Spot> out, String key, boolean exact, String noun, Player p, Location from, double radius) {
        for (Location l : resourceClusters(key, exact, from, radius)) if (spotOk(p, from, l)) out.add(new Spot(foundName(noun, l), l.getX(), l.getY()));
    }

    /** A found spot a player may walk to: the surface, not behind the Al Kharid gate from outside (relocate's rule), no gate rule against him. */
    static boolean spotOk(Player p, Location from, Location l) {
        if (l == null || l.getZ() != 0 || l.getY() >= 6400) return false;
        if (l.getX() >= 3268 && from.getX() < 3268 && l.getY() < 3330) return false;
        try { return Gates.canEnter(p, l); } catch (Throwable t) { return false; }
    }

    static int treeLvl(String name) {
        String[] n = Task.BEST_TREE.targets;
        for (int i = 0; i < n.length; i++) if (n[i].equalsIgnoreCase(name)) return Runner.TREE_LEVELS[i];
        return 1;
    }

    /**
     * Spots for a job that the static SPOTS table doesn't list, within 400 tiles of {@code from}: {@code hunts=false} the
     * surveyors' Resources clusters (rock:, tree:, fish:, pick:, pickpocket:, npc:), {@code hunts=true} HuntMap's spawn
     * spots for a fight. Surface only (no planner underground), players' rules (spotOk). Empty when nothing is known.
     */
    static List<Spot> foundSpots(Task t, int[] prods, Player p, Location from, boolean hunts) {
        List<Spot> out = new ArrayList<>();
        if (t == null || p == null || from == null || from.getZ() != 0 || from.getY() >= 6400) return out;
        final double R = 400;
        try {
            if (hunts) {
                if (t.kind != Kind.FIGHT || t.targets == null) return out;
                for (HuntMap.Hunt h : new ArrayList<>(HuntMap.HUNTS)) {
                    if (h == null || h.spot == null || h.spot.getDistance(from) > R) continue;
                    boolean match = false;
                    for (String tg : t.targets) {
                        if (tg.equalsIgnoreCase(h.name)) match = true;
                        if (h.names != null) for (String n : h.names) if (tg.equalsIgnoreCase(n)) match = true;
                    }
                    if (!match || !spotOk(p, from, h.spot)) continue;
                    out.add(new Spot(foundName((h.name != null ? h.name : t.targets[0]).toLowerCase(Locale.ROOT), h.spot), h.spot.getX(), h.spot.getY()));
                }
                return out;
            }
            switch (t.kind) {
                case ROCK:
                    if (t == Task.ESSENCE || prods == null) break; // Aubury's mine: a teleport, not a walk
                    for (int id : prods) {
                        if (t == Task.BEST_ROCK && Runner.oreLevel(id) > p.getSkills().getLevel(Skills.MINING)) continue;
                        String n = MarketBrain.name(id).toLowerCase(Locale.ROOT); // Resources.scan's key: "rock:" + MarketBrain.name(reward)
                        addFound(out, "rock:" + n, true, n.replaceFirst(" ore$", "") + " rocks", p, from, R);
                    }
                    break;
                case TREE:
                    if (t.targets == null) break;
                    for (String tg : t.targets) {
                        if (t == Task.BEST_TREE && treeLvl(tg) > p.getSkills().getLevel(Skills.WOODCUTTING)) continue;
                        String n = tg.toLowerCase(Locale.ROOT);
                        addFound(out, "tree:" + n, true, n.equals("tree") ? "trees" : n.endsWith(" tree") ? n + "s" : n + " trees", p, from, R);
                    }
                    break;
                case FISH:
                    if (t.option != null) addFound(out, "fish:" + t.option.toLowerCase(Locale.ROOT), true, t.option.toLowerCase(Locale.ROOT) + " fishing spots", p, from, R);
                    break;
                case PICK:
                    if (t.targets != null) for (String tg : t.targets) addFound(out, "pick:" + tg.toLowerCase(Locale.ROOT), true, tg.toLowerCase(Locale.ROOT), p, from, R);
                    break;
                case THIEVE:
                    if (t.targets != null && "Pickpocket".equals(t.option)) for (String tg : t.targets) addFound(out, "pickpocket:" + tg.toLowerCase(Locale.ROOT), true, tg.toLowerCase(Locale.ROOT), p, from, R);
                    break;
                case FIGHT:
                    if (t.targets != null) for (String tg : t.targets) addFound(out, "npc:" + tg.toLowerCase(Locale.ROOT) + " (", false, tg.toLowerCase(Locale.ROOT), p, from, R); // key "npc:cow (level 2)"
                    break;
                default: break;
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * Stations the static table doesn't list: the trade's spots (Artisans.Trade.spots) and the surveyors' place:* clusters,
     * within 220 tiles of {@code from} and more than 20 tiles from every static entry (a known station keeps its hand-checked
     * tile; a cluster's stand tile can be behind a counter). Surface only.
     */
    static List<Location> extraStations(Location[] statics, Location[] trade, Location from, String... keys) {
        List<Location> out = new ArrayList<>();
        if (from == null) return out;
        List<Location> c = new ArrayList<>();
        if (trade != null) c.addAll(Arrays.asList(trade));
        for (String k : keys) c.addAll(resourceClusters(k, true, from, 220));
        for (Location l : c) {
            if (l == null || l.getZ() != 0 || l.getY() >= 6400 || l.getDistance(from) > 220) continue;
            boolean known = false;
            if (statics != null) for (Location s : statics) if (s.getDistance(l) < 20) { known = true; break; }
            for (Location o : out) if (o.getDistance(l) < 8) known = true; // two cells of one bank
            if (!known) out.add(l);
        }
        return out;
    }

    static final Location LUMBRIDGE_UPSTAIRS_BANK = Location.create(3208, 3220, 0); // upstairs (z=2): left out on purpose, like BANKS

    /**
     * " The nearest shop that sells one is X, near Y." from the shop list the bots buy from (BotChat.WorldData.shops,
     * SmartAdventurers.goalShop's data; goalShop itself when no shop's tile is nearer). A message only: nothing is bought.
     */
    static String shopHint(Player p, int[] ids) {
        if (p == null || ids == null || ids.length == 0) return "";
        try {
            Location here = p.getLocation();
            Location surface = Location.create(here.getX(), here.getY() >= 6400 ? here.getY() - 6400 : here.getY(), 0);
            Object[] best = null; double bd = Double.MAX_VALUE;
            for (Object[] sh : new ArrayList<>(BotChat.WorldData.shops)) {
                boolean sells = false;
                for (int id : ids) if (has((int[]) sh[3], id)) { sells = true; break; }
                if (!sells) continue;
                double d = Location.create((Integer) sh[1], (Integer) sh[2], 0).getDistance(surface);
                if (d < bd) { bd = d; best = sh; }
            }
            if (best == null) for (int id : ids) { best = SmartAdventurers.goalShop(id); if (best != null) break; }
            if (best == null) return "";
            return " The nearest shop that sells one is " + best[0] + BotChat.WorldData.near((Integer) best[1], (Integer) best[2]) + ".";
        } catch (Throwable t) { return ""; }
    }

    static void help(Player p) {
        p.sendMessage("<col=0b5e1f>AFK: ::afk <what> [options]. What: logs, oak, ore, coal, fish, lobster, fight, cows, cowhides, bars, flax ... (::afk list).");
        p.sendMessage("<col=0b5e1f>Options in any order: bank (default), drop or sell; x500 = stop after 500 (made, with a process); to 60 = stop at level 60.");
        p.sendMessage("<col=0b5e1f>Then: ore then smelt [then smith <item>], fish then cook, logs then fletch [<item>] or burn, wool/flax then spin.");
        p.sendMessage("<col=0b5e1f>From your bank: ::afk bars, smith <item>, cook, fletch, burn (stand outside the bank), spin, bury.");
        p.sendMessage("<col=0b5e1f>Magic: ::afk alch <item> [low] [force] [auto] [x<n>], ::afk alch list, ::afk superheat <bar>, ::afk iron then superheat (runes from your bank, a wielded staff counts)."); // batch 25
        p.sendMessage("<col=0b5e1f>at <place> walks there first (::afk yew at falador). Fights: att, str, def or shared; bury; noloot.");
        p.sendMessage("<col=0b5e1f>Also: ::afk stop, ::afk finish (bank, then stop), ::afk status, ::afk gold (best money for your levels), ::afk list <skill>.");
    }

    static final String[][] GROUPS = {{"woodcutting", "TREE"}, {"mining", "ROCK"}, {"fishing", "FISH"}, {"smithing", "SMELT"}, {"thieving", "THIEVE"}, {"combat", "FIGHT"}, {"gathering", "PICK", "GROUND"}};

    static boolean inGroup(Task t, String[] g) { for (int k = 1; k < g.length; k++) if (t.kind.name().equals(g[k])) return true; return false; }

    static String wordFor(Task t) { return FIRST_WORD.getOrDefault(t, t.key); }

    static void list(Player p, String skill) {
        if (skill == null) {
            StringBuilder sb = new StringBuilder();
            for (String[] g : GROUPS) { int n = 0; for (Task t : Task.values()) if (inGroup(t, g)) n++; sb.append(sb.length() > 0 ? ", " : "").append(g[0]).append(" (").append(n).append(")"); }
            p.sendMessage("<col=0b5e1f>AFK: " + sb + ", processing. ::afk list <skill> for the targets.");
            return;
        }
        if ("processing".startsWith(skill)) { // batch 23 T4
            p.sendMessage("<col=0b5e1f>AFK processing: after a job, then smelt, smith <item>, cook, fletch [<item>], burn or spin; from your bank: smith <item>, cook, fletch, burn, spin, bury (tools: hammer, knife, tinderbox).");
            return;
        }
        for (String[] g : GROUPS) {
            if (!g[0].startsWith(skill)) continue;
            StringBuilder sb = new StringBuilder();
            for (Task t : Task.values()) {
                if (!inGroup(t, g)) continue;
                int[] need = NEEDS.get(t);
                sb.append(sb.length() > 0 ? ", " : "").append(wordFor(t)).append(need != null ? " (" + need[1] + ")" : "");
            }
            p.sendMessage("<col=0b5e1f>AFK " + g[0] + ": " + sb + ".");
            return;
        }
        p.sendMessage("<col=ff0000>AFK: skills are woodcutting, mining, fishing, smithing, thieving, combat, gathering, processing.");
    }

    /** One way to make money: a task at a spot, with an items-per-hour estimate from the player's level. */
    static final class GoldPlan {
        final String name; final Task task; final Location spot; final int skill, minLevel;
        final double base, perLevel, cap;
        GoldPlan(String name, Task task, Location spot, int skill, int minLevel, double base, double perLevel, double cap) {
            this.name = name; this.task = task; this.spot = spot; this.skill = skill; this.minLevel = minLevel;
            this.base = base; this.perLevel = perLevel; this.cap = cap;
        }
    }

    // Rough items per hour including bank trips: base + perLevel * level, capped. Values come from the live GE guide.
    static final GoldPlan[] GOLD_PLANS = {
        new GoldPlan("cowhides at the Lumbridge cow field", Task.COWHIDES, Location.create(3257, 3270, 0), -1, 3, 40, 4, 220),
        new GoldPlan("cowhides at the Falador farm cows", Task.COWHIDES, Location.create(3032, 3305, 0), -1, 3, 40, 4, 220),
        new GoldPlan("ore at the Varrock south-east mine", Task.BEST_ROCK, Location.create(3287, 3366, 0), Skills.MINING, 1, 40, 7, 450),
        new GoldPlan("ore at the Varrock south-west mine", Task.BEST_ROCK, Location.create(3180, 3371, 0), Skills.MINING, 1, 40, 7, 450),
        new GoldPlan("coal at Barbarian Village", Task.COAL, Location.create(3081, 3421, 0), Skills.MINING, 30, 60, 4, 320),
        new GoldPlan("logs at Draynor", Task.BEST_TREE, Location.create(3087, 3236, 0), Skills.WOODCUTTING, 1, 60, 6, 450),
        new GoldPlan("logs west of Lumbridge", Task.BEST_TREE, Location.create(3164, 3221, 0), Skills.WOODCUTTING, 1, 60, 6, 450),
        new GoldPlan("shrimp at Draynor", Task.NET, Location.create(3087, 3229, 0), Skills.FISHING, 1, 120, 6, 450),
        new GoldPlan("pickpocketing men in Lumbridge", Task.MAN, Location.create(3222, 3218, 0), Skills.THIEVING, 1, 300, 15, 1200),
    };

    /** Best gold-per-hour plan within walking range that the player has the level and tools for. */
    static Object[] pickGold(Player p) {
        GoldPlan best = null;
        double bestRate = 0;
        String bestNote = "";
        int melee = Math.max(p.getSkills().getStaticLevel(Skills.STRENGTH), p.getSkills().getStaticLevel(Skills.ATTACK));
        for (GoldPlan g : GOLD_PLANS) {
            if (g.task.tools != null && !hasAny(p, g.task.tools)) continue;
            if (g.spot.getDistance(p.getLocation()) > 400 || g.spot.getZ() != p.getLocation().getZ()) continue;
            int lvl = g.skill >= 0 ? p.getSkills().getStaticLevel(g.skill) : melee;
            if (g.skill >= 0 && lvl < g.minLevel) continue;
            if (g.skill < 0 && p.getProperties().getCurrentCombatLevel() < g.minLevel) continue;
            double perHour = Math.min(g.cap, g.base + g.perLevel * lvl);
            int value = itemValueFor(g.task, p);
            double rate = perHour * value;
            if (g.task.kind == Kind.THIEVE) rate = perHour * 3; // men give about 3 coins a success
            rate *= 1.0 - Math.min(0.4, g.spot.getDistance(p.getLocation()) / 1000.0); // walking there costs time
            if (rate > bestRate) { bestRate = rate; best = g; bestNote = (int) perHour + " items/hr at ~" + value + " gp"; }
        }
        return best == null ? null : new Object[]{best, (int) bestRate, bestNote};
    }

    /** What one gathered item sells for, using the best item the player can actually get from that task. */
    static int itemValueFor(Task t, Player p) {
        int best = 0;
        if (t.products == null) return 1;
        for (int id : t.products) {
            if (t == Task.BEST_ROCK) {
                int need = id == 436 || id == 438 || id == 434 ? 1 : id == 440 ? 15 : id == 442 ? 20 : id == 453 ? 30 : id == 444 ? 40 : id == 447 ? 55 : id == 449 ? 70 : 85;
                if (p.getSkills().getStaticLevel(Skills.MINING) < need) continue;
                if (id == 442 || id == 444 || id == 447 || id == 449 || id == 451 || id == 453 || id == 434) continue; // not at these mines
            }
            if (t == Task.BEST_TREE) {
                int need = id == 1521 ? 15 : id == 1519 ? 30 : id == 1517 ? 45 : id == 1515 ? 60 : id == 1513 ? 75 : 1;
                if (p.getSkills().getStaticLevel(Skills.WOODCUTTING) < need) continue;
                if (id == 1517 || id == 1515 || id == 1513 || id == 2862) continue; // not at these spots
            }
            try { if (PriceIndex.canTrade(id)) best = Math.max(best, PriceIndex.INSTANCE.getValue(id)); } catch (Throwable ignored) { }
        }
        return Math.max(1, best);
    }

    static void startGold(Player player, String style) {
        Object[] pick = pickGold(player);
        if (pick == null) {
            player.sendMessage("<col=ff0000>AFK: no gold plan fits right now. Carry an axe, pickaxe or small net, or get to combat level 3.");
            return;
        }
        GoldPlan g = (GoldPlan) pick[0];
        stop(player, null);
        if (g.task.kind == Kind.FIGHT && !style.equals("keep")) setStyle(player, style);
        Runner r = new Runner(player, g.task, true, 0, false, true);
        r.sell = true;
        r.styleSkill = style.equals("strength") ? Skills.STRENGTH : style.equals("defence") ? Skills.DEFENCE : Skills.ATTACK; // same meaning as ::afk start (it defaulted to Strength here)
        r.spot = g.spot;
        if (!r.goTo(State.TO_SPOT, g.spot)) return; // audit 10-23 A-11: goTo ended it (finish() said why)
        RUNNING.put(key(player), r);
        GameWorld.getPulser().submit(r);
        System.out.println("[AfkTasks] " + player.getUsername() + " gold plan: " + g.name + " ~" + pick[1] + " gp/hr");
        player.sendMessage("<col=0b5e1f>AFK: gold plan is " + g.name + ", about " + String.format("%,d", (Integer) pick[1]) + " gp/hr (" + pick[2] + "). Walking there now; the haul is sold at the bank.");
    }

    static int parse(String s) { try { return Math.max(0, Integer.parseInt(s)); } catch (Exception e) { return 0; } }
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    static void stop(Player player, String msg) {
        Runner r = RUNNING.remove(key(player));
        if (r != null) r.halt();
        if (msg != null && r != null) { player.sendMessage("<col=0b5e1f>AFK: " + msg); r.summary(); }
        else if (msg != null) player.sendMessage("AFK: not running.");
    }

    static boolean hasAny(Player p, int[] items) {
        for (int id : items) if (p.getInventory().containsAtLeastOneItem(id) || p.getEquipment().containsAtLeastOneItem(id)) return true;
        return false;
    }

    static String toolName(Task t) {
        if (t.tools == AXES) return "an axe";
        if (t.tools == PICKAXES) return "a pickaxe";
        if (t == Task.WOOL) return "shears";
        switch (t) {
            case NET: return "a small fishing net";
            case BAIT: return "a fishing rod";
            case LURE: return "a fly fishing rod";
            case CAGE: return "a lobster pot";
            case HARPOON: return "a harpoon";
            default: return "the right tool";
        }
    }

    /** Picks the combat style that trains the chosen skill, if the wielded weapon has it. */
    static boolean hasId(int[] list, int id) { if (list != null) for (int x : list) if (x == id) return true; return false; }

    static void setStyle(Player player, String style) {
        int want;
        switch (style) {
            case "attack": want = WeaponInterface.STYLE_ACCURATE; break;
            case "strength": want = WeaponInterface.STYLE_AGGRESSIVE; break;
            case "defence": want = WeaponInterface.STYLE_DEFENSIVE; break;
            case "shared": want = WeaponInterface.STYLE_CONTROLLED; break;
            default: return;
        }
        WeaponInterface w = player.getExtension(WeaponInterface.class);
        if (w == null || w.getWeaponInterface() == null) return;
        WeaponInterface.AttackStyle[] styles = w.getWeaponInterface().getAttackStyles();
        for (int i = 0; i < styles.length; i++) {
            if (styles[i].getStyle() != want) continue;
            player.getSettings().toggleAttackStyleIndex(i);
            player.getProperties().setAttackStyle(styles[i]);
            player.sendMessage("AFK: combat style set to train " + style + ".");
            return;
        }
        player.sendMessage("AFK: your weapon has no style for " + style + ", keeping the current one.");
    }

    // ------------------------------------------------------------------ batch 23 T4: gather -> process -> deliver (the design notes 2.2-2.4)

    /** Where a process step happens. NONE = anywhere: at the spot, or at the bank for a bank loop (a fire is lit at the start tile). */
    enum Station {
        NONE("anywhere"), FURNACE("furnace"), ANVIL("anvil"), RANGE("range"), WHEEL("spinning wheel");
        final String label;
        Station(String label) { this.label = label; }
    }

    /**
     * What happens to the goods before delivery. SMELT and COOK are the old smeltHaul / cookHaul flags (Runner.process()
     * keeps those fields in step, and the bots still set the fields directly). Phase 1 only (D6/D7: no Magic, Farming,
     * tanning, herblore, runecrafting).
     */
    enum Process {
        NONE(Station.NONE, -1, 0, "", ""),
        SMELT(Station.FURNACE, Skills.SMITHING, 0, "smelt", "smelting"),
        SMITH(Station.ANVIL, Skills.SMITHING, 2347, "smith", "smithing"),
        COOK(Station.RANGE, Skills.COOKING, 0, "cook", "cooking"),
        FLETCH(Station.NONE, Skills.FLETCHING, 946, "fletch", "fletching"),
        BURN(Station.NONE, Skills.FIREMAKING, 590, "burn", "burning"),
        SPIN(Station.WHEEL, Skills.CRAFTING, 0, "spin", "spinning"),
        BURY(Station.NONE, Skills.PRAYER, 0, "bury", "burying"),
        ALCH(Station.NONE, Skills.MAGIC, 0, "alch", "alching"), // batch 25 Magic phase 1 (AfkMagic): the spell is the Runner's AfkMagic.Run, not a recipe
        SUPERHEAT(Station.NONE, Skills.MAGIC, 0, "superheat", "superheating"); // batch 25: from the bank, or on the spot when a mining pack is full
        final Station station; final int skill, tool; final String word, ing;
        Process(Station station, int skill, int tool, String word, String ing) { this.station = station; this.skill = skill; this.tool = tool; this.word = word; this.ing = ing; }
        /** Fires and buried bones leave nothing to deliver: the pack empties itself. */
        boolean consumes() { return this == BURN || this == BURY; }
        /** batch 25: a spell from afk-spells.json does the step (AfkMagic); recipes(), the 220-tile station check and x<n> as "made" don't apply. */
        boolean magic() { return this == ALCH || this == SUPERHEAT; }
        String toolName() { return tool == 2347 ? "a hammer" : tool == 946 ? "a knife" : tool == 590 ? "a tinderbox" : "nothing"; }
        static Process of(String w) {
            if (w == null) return NONE;
            switch (w) {
                case "smelt": case "smelting": return SMELT;
                case "smith": case "smithing": return SMITH;
                case "cook": case "cooking": return COOK;
                case "fletch": case "fletching": return FLETCH;
                case "burn": case "firemaking": return BURN;
                case "spin": case "spinning": return SPIN;
                case "bury": return BURY;
                case "alch": case "alchemy": case "alching": case "highalch": case "lowalch": return ALCH; // batch 25
                case "superheat": case "superheating": return SUPERHEAT;
                default: return NONE;
            }
        }
    }

    /** Where the finished goods go. KEEP only means something for BURN/BURY (nothing left to deliver); elsewhere it banks, as before. */
    enum Deliver {
        BANK, SELL, DROP, KEEP;
        static Deliver of(boolean bank, boolean sell, boolean keep) { return sell ? SELL : !bank ? DROP : keep ? KEEP : BANK; }
    }

    /** One process recipe: inputs -> product (product 0 = consumed: a fire, a buried bone). xp only for the steps the runner does itself. */
    static final class Rec {
        final Process pr; final int product, amount, level; final int[] in, inAmt; final double xp; final String name;
        Rec(Process pr, int product, int amount, int level, int[] in, int[] inAmt, double xp, String name) {
            this.pr = pr; this.product = product; this.amount = amount; this.level = level; this.in = in; this.inAmt = inAmt; this.xp = xp; this.name = name == null ? "" : name;
        }
        int perSet() { int n = 0; for (int a : inAmt) n += a; return n; }
        int setsIn(Container c) { int n = Integer.MAX_VALUE; for (int k = 0; k < in.length; k++) n = Math.min(n, c.getAmount(in[k]) / inAmt[k]); return n; }
    }

    /** Smithing xp per bar used (bronze 12.5 ... rune 75); only these bars' anvil recipes are offered. */
    static final Map<Integer, Double> SMITH_XP = new HashMap<>();
    /** Fletching xp per action for the knife-on-log products (arrow shafts, unstrung bows); stocks are left out. */
    static final Map<Integer, Double> FLETCH_XP = new HashMap<>();
    /** {log, Firemaking level}. */
    static final int[][] FIRE_LOGS = {{1513, 75}, {1515, 60}, {1517, 45}, {1519, 30}, {1521, 15}, {2862, 1}, {1511, 1}};
    /** Bones a player can bury, best first. */
    static final int[] BURY_BONES = {536, 534, 532, 2859, 530, 526};
    /** {input, product, Crafting level}: ball of wool is not in artisan-recipes.json; bow string matches its crafting row. */
    static final int[][] SPIN_RECIPES = {{1779, 1777, 10}, {1737, 1759, 1}};
    static {
        double[][] sx = {{2349, 12.5}, {2351, 25}, {2353, 37.5}, {2359, 50}, {2361, 62.5}, {2363, 75}};
        for (double[] s : sx) SMITH_XP.put((int) s[0], s[1]);
        double[][] fx = {{52, 5}, {50, 5}, {48, 10}, {54, 16.5}, {56, 25}, {60, 33.3}, {58, 41.5}, {64, 50}, {62, 58.3}, {68, 67.5}, {66, 75}, {72, 83.3}, {70, 91.5}};
        for (double[] f : fx) FLETCH_XP.put((int) f[0], f[1]);
    }

    /** Artisans loads artisan-recipes.json only when bots are on; a player's chain needs it (and Runner.COOK) either way. */
    static volatile boolean recipesTried; // one try: recipes() runs every process step
    static void ensureRecipes() {
        if (recipesTried || !Artisans.RECIPES.isEmpty()) return;
        synchronized (Artisans.RECIPES) {
            if (recipesTried || !Artisans.RECIPES.isEmpty()) return;
            recipesTried = true;
            try { Artisans.loadRecipes(); System.out.println("[AfkTasks] loaded " + Artisans.RECIPES.size() + " artisan recipes for the player processes"); }
            catch (Throwable t) { System.out.println("[AfkTasks] couldn't load artisan-recipes.json: " + t); }
        }
    }

    /** Every recipe of a process (no level or stock filter). */
    static List<Rec> recipes(Process pr) {
        List<Rec> out = new ArrayList<>();
        if (pr == Process.SMITH || pr == Process.FLETCH || pr == Process.COOK) ensureRecipes();
        switch (pr) {
            case SMITH:
                for (Artisans.Recipe r : Artisans.RECIPES) {
                    if (!"SMITHING".equals(r.trade) || r.inIds == null || r.inIds.length != 1) continue;
                    Double x = SMITH_XP.get(r.inIds[0]);
                    if (has(AXES, r.product) || has(PICKAXES, r.product)) continue; // a smithed axe or pickaxe counts as a tool (keep()) and would never leave the pack
                    if (x != null) out.add(new Rec(pr, r.product, r.amount, r.level, r.inIds, r.inAmts, x * r.inAmts[0], r.name));
                }
                break;
            case FLETCH:
                for (Artisans.Recipe r : Artisans.RECIPES) {
                    if (!"FLETCHING".equals(r.trade) || r.inIds == null || r.inIds.length != 1 || r.inAmts[0] != 1) continue;
                    Double x = FLETCH_XP.get(r.product);
                    if (x != null) out.add(new Rec(pr, r.product, r.amount, r.level, r.inIds, r.inAmts, x, r.name));
                }
                break;
            case COOK:
                for (Map.Entry<Integer, int[]> e : Runner.COOK.entrySet()) out.add(new Rec(pr, e.getValue()[0], 1, e.getValue()[1], ids(e.getKey()), ids(1), 0, MarketBrain.name(e.getValue()[0])));
                break;
            case SPIN:
                for (int[] s : SPIN_RECIPES) out.add(new Rec(pr, s[1], 1, s[2], ids(s[0]), ids(1), 0, MarketBrain.name(s[1])));
                break;
            case BURN:
                for (int[] l : FIRE_LOGS) out.add(new Rec(pr, 0, 0, l[1], ids(l[0]), ids(1), 0, MarketBrain.name(l[0])));
                break;
            case BURY:
                for (int b : BURY_BONES) out.add(new Rec(pr, 0, 0, 1, ids(b), ids(1), 0, MarketBrain.name(b)));
                break;
            default: break;
        }
        return out;
    }

    /** "platebody", "oak longbow": every word in the recipe's name (a plural 's' forgiven). Empty = anything. */
    static boolean wants(Rec r, String want) {
        if (want == null || want.isEmpty()) return true;
        String n = r.name.toLowerCase(Locale.ROOT);
        for (String x : want.split(" ")) if (!x.isEmpty() && !n.contains(x) && !(x.endsWith("s") && n.contains(x.substring(0, x.length() - 1)))) return false;
        return true;
    }

    /** The best recipe (highest level, then most inputs) at this level, matching the wanted words, whose inputs pass the filter (null = any). */
    static Rec bestRec(Process pr, String want, int level, java.util.function.IntPredicate inputOk, Container has) {
        Rec best = null;
        for (Rec r : recipes(pr)) {
            if (r.level > level || !wants(r, want)) continue;
            boolean ok = true;
            if (inputOk != null) for (int in : r.in) if (!inputOk.test(in)) ok = false;
            if (!ok || has != null && r.setsIn(has) <= 0) continue;
            if (best == null || r.level > best.level || r.level == best.level && r.perSet() > best.perSet()) best = r;
        }
        return best;
    }

    /** Every id a process takes or makes (a bank loop's products, so cooked food isn't "kept" in the pack as food). */
    static int[] chainIds(Process pr, boolean inputs, boolean outputs) {
        Set<Integer> s = new java.util.LinkedHashSet<>();
        for (Rec r : recipes(pr)) { if (inputs) for (int in : r.in) s.add(in); if (outputs && r.product > 0) s.add(r.product); }
        if (pr == Process.SMELT && outputs) for (int b : BARS) s.add(b);
        int[] out = new int[s.size()]; int n = 0; for (int id : s) out[n++] = id;
        return out;
    }

    /** The bar a mine-and-smelt runner will mine for (Runner.barToMine without a runner), or null. */
    static Bar barToMineFor(Player p, Task t) {
        int smith = p.getSkills().getLevel(Skills.SMITHING), mine = p.getSkills().getLevel(Skills.MINING);
        int mustUse = t == Task.BEST_ROCK || t.products == null ? -1 : t.products[0];
        for (Bar b : Runner.BAR_ORDER) {
            boolean uses = mustUse < 0;
            for (Item ore : b.getOres()) if (ore.getId() == mustUse) uses = true;
            if (!uses || b.getLevel() > smith) continue;
            boolean ok = true;
            for (Item ore : b.getOres()) if (Runner.oreLevel(ore.getId()) > mine || ORE_SPOTS.get(ore.getId()) == null) ok = false;
            if (ok) return b;
        }
        return null;
    }

    /** The nearest station of a kind within 220 tiles of {@code from} (Al Kharid's only from inside the gate), or null. */
    static Location stationNear(Station st, Location from) {
        if (from == null) return null;
        List<Location> c = new ArrayList<>();
        switch (st) {
            case FURNACE: c.addAll(Arrays.asList(FURNACES)); c.addAll(extraStations(FURNACES, Artisans.Trade.SMELTING.spots, from, "place:furnace")); break; // batch 23 T5: the same union as Runner.nearestFurnace for a player
            case RANGE: c.addAll(Arrays.asList(Runner.RANGES)); c.addAll(extraStations(Runner.RANGES, Artisans.Trade.COOKING.spots, from, "place:range", "place:cooking range")); break;
            case ANVIL: c.addAll(Arrays.asList(Artisans.Trade.SMITHING.spots)); try { Location r = Resources.nearest("place:anvil", from); if (r != null) c.add(r); } catch (Throwable ignored) { } break;
            case WHEEL: try { Location r = Resources.nearest("place:spinning wheel", from); if (r != null) c.add(r); } catch (Throwable ignored) { } break; // [UNVERIFIED] no ground-floor wheel list: the surveyors' finds only
            default: return null;
        }
        Location best = null; double d = 220;
        for (Location l : c) {
            if (l.getX() >= 3268 && l.getY() < 3330 && l.getY() > 3100 && from.getX() < 3268) continue; // behind the Al Kharid gate
            double dist = l.getDistance(from);
            if (dist < d) { d = dist; best = l; }
        }
        return best;
    }

    /**
     * batch 23 T4: why this chain can't start, or null. Checks the job fits the process (fletch is for logs ...), the
     * tool, a recipe at the player's level for what the chain brings, and a station within 220 tiles of the spot.
     */
    static String chainProblem(Player p, Task t, Process pr, String want, boolean bank, boolean sell, Location from) {
        if (pr == Process.NONE) return t.kind == Kind.BANK ? "say what to make from the bank: smith, cook, fletch, burn, spin, bury or alch." : null;
        if (pr.magic()) return null; // batch 25: AfkMagic.resolveAlch checked it in startWords
        boolean loop = t.kind == Kind.BANK;
        if (!loop) {
            switch (pr) {
                case SMELT: case SMITH: if (t.kind != Kind.ROCK || t == Task.CLAY || t == Task.ESSENCE) return "'" + pr.word + "' is for mining ore (::afk " + (pr == Process.SMELT ? "bars" : "smith <item>") + " works from your bank)."; break;
                case COOK: if (t.kind != Kind.FISH) return "'cook' is for fishing (::afk cook works from your bank)."; break;
                case FLETCH: case BURN: if (t.kind != Kind.TREE) return "'" + pr.word + "' is for woodcutting (::afk " + pr.word + " works from your bank)."; break;
                case SPIN: if (t != Task.WOOL && t != Task.FLAX) return "'spin' is for wool or flax (::afk spin works from your bank)."; break;
                case BURY: return "'bury' is for fights that drop bones, or ::afk bury from your bank.";
                default: break;
            }
        } else if (!bank) return "'drop' doesn't fit a job from the bank.";
        if (pr.consumes() && sell) return "nothing is left to sell after " + pr.ing + ".";
        if (pr.tool > 0 && !hasAny(p, ids(pr.tool))) return "you need " + pr.toolName() + " in your pack to " + pr.word + "." + shopHint(p, ids(pr.tool)); // batch 23 T5
        int lvl = p.getSkills().getLevel(pr.skill);
        String skill = Skills.SKILL_NAME[pr.skill];
        Rec r;
        if (pr == Process.SMELT) {
            r = null;
            if (barToMineFor(p, t) == null) return "can't smelt " + t.label.toLowerCase(Locale.ROOT) + " into bars at Smithing " + lvl + " / Mining " + p.getSkills().getLevel(Skills.MINING) + ".";
        } else if (loop) {
            r = bestRec(pr, want, lvl, null, p.getBank());
            if (r == null && bestRec(pr, want, lvl, null, null) != null) return "nothing in your bank to " + pr.word + (want.isEmpty() ? "" : " into " + want) + ".";
        } else if (pr == Process.SMITH) {
            Bar b = barToMineFor(p, t);
            if (b == null) return "can't mine and smelt " + t.label.toLowerCase(Locale.ROOT) + " into bars at Smithing " + lvl + " / Mining " + p.getSkills().getLevel(Skills.MINING) + ".";
            final int bar = b.getProduct().getId();
            r = bestRec(pr, want, lvl, id -> id == bar, null);
            if (r == null) {
                Rec any = bestRec(pr, want, 99, id -> id == bar, null);
                return any != null ? any.name + " needs " + skill + " " + any.level + " (you have " + lvl + ")." : "nothing to smith from " + b.getProduct().getName().toLowerCase(Locale.ROOT) + "s" + (want.isEmpty() ? "" : " called '" + want + "'") + ".";
            }
        } else {
            final int[] got = t.products;
            r = bestRec(pr, want, lvl, id -> has(got, id), null);
        }
        if (r == null && pr != Process.SMELT) {
            final int[] got = loop ? null : t.products;
            Rec any = bestRec(pr, want, 99, got == null ? null : id -> has(got, id), null);
            if (any != null) return any.name + " needs " + skill + " " + any.level + " to " + pr.word + " (you have " + lvl + ").";
            return "nothing to " + pr.word + (want.isEmpty() ? "" : " called '" + want + "'") + (loop ? "." : " from " + t.label.toLowerCase(Locale.ROOT) + ".");
        }
        if (pr == Process.SMITH && !loop && stationNear(Station.FURNACE, from) == null) return "no furnace within 220 tiles of the spot to smelt the ore first.";
        if (pr.station != Station.NONE && stationNear(pr.station, from) == null)
            return "no " + pr.station.label + " within 220 tiles of " + (loop ? "here" : "the spot") + " for " + pr.ing + (pr.station == Station.WHEEL ? " (only ground-floor wheels the surveyors have seen count)" : "") + ".";
        if (loop && pr == Process.BURN && from != null) { // fires can't be lit in the bank: the runner lights them where the player stood
            for (int dx = -5; dx <= 5; dx++) for (int dy = -5; dy <= 5; dy++) {
                Scenery s = RegionManager.getObject(from.getZ(), from.getX() + dx, from.getY() + dy);
                if (s != null && s.getName() != null && (s.getName().equalsIgnoreCase("Bank booth") || s.getName().equalsIgnoreCase("Bank chest"))) return "stand a few steps outside the bank, where fires can be lit, then start.";
            }
        }
        return null;
    }

    enum State { WORK, TO_BANK, BANKING, TO_SPOT, RETREAT, RECOVER, TO_FURNACE, SMELTING } // batch 23 T4: TO_FURNACE / SMELTING are also the walk to any station and the process step (names kept: Failures and Watchdog read them)

    static final class Runner extends Pulse {
        final Player p;
        final Task task;
        // Dynamic hunts (smart adventurers) override what the task enum says.
        String[] targetsOverride; int[] productsOverride; String labelOverride;
        boolean cookHaul; // bots: cook the catch at a range before selling it
        boolean walker;   // bots without the coins for teleports walk everywhere
        Spot[] questSpots; // a quest's own spots (imps round Falador): relocate() cycles these instead of the task's
        boolean questHold; // gathering for a quest (QuestSteps.Collect): leave its dialogues alone, keep the haul in the pack
        static final int TELEPORT_MIN = Integer.getInteger("tele.min", 320); // tiles; walked below this
        boolean usePrayer; // high tier: protect from melee against the big things, prayer potions bought when low
        int potions;
        // batch 15 item2: potion families, 4 doses down to 1, for the one-line trip log (and items 3-4: what to drink)
        static final int[][] POTS = {{2434, 139, 141, 143}, {2446, 175, 177, 179}, {2448, 181, 183, 185}, {2428, 121, 123, 125}, {113, 115, 117, 119},
                {2436, 145, 147, 149}, {2440, 157, 159, 161}, {9739, 9741, 9743, 9745}, {2444, 169, 171, 173}};
        static final String[] POT_NAMES = {"prayer", "antipoison", "super antipoison", "attack", "strength", "super attack", "super strength", "combat", "ranging"};
        static final int P_ANTI = 1, P_SANTI = 2, P_ATT = 3, P_STR = 4, P_SATT = 5, P_SSTR = 6, P_COMBAT = 7, P_RANGE = 8;
        static int potFamily(int id) { for (int f = 0; f < POTS.length; f++) for (int d : POTS[f]) if (d == id) return f; return -1; }
        static int potDoses(int id) { int f = potFamily(id); if (f < 0) return 0; for (int k = 0; k < 4; k++) if (POTS[f][k] == id) return 4 - k; return 0; }
        final int[] potsTaken = new int[POTS.length], dosesAtStart = new int[POTS.length]; // batch 15 item2
        boolean boostPots; // batch 15 item4: set when the trip set out with attack/strength/combat/ranging potions
        int specials;      // batch 15 item5: special attacks used this trip
        int[] dosesNow() { int[] d = new int[POTS.length]; for (Item it : p.getInventory().toArray()) { if (it == null) continue; int f = potFamily(it.getId()); if (f >= 0) d[f] += potDoses(it.getId()) * it.getAmount(); } return d; }
        /** batch 15 items3-4: the carried potion of these families with the fewest doses left (finish the open one first), or -1. */
        int carriedPot(int... fams) {
            int best = -1, bd = 9;
            for (Item it : p.getInventory().toArray()) { if (it == null) continue; int f = potFamily(it.getId()); if (f < 0) continue; for (int w : fams) if (w == f && potDoses(it.getId()) < bd) { bd = potDoses(it.getId()); best = it.getId(); } }
            return best;
        }
        /** batch 15 item2: one line per trip, only when it set out with potions or used a special. */
        void logPotions() {
            StringBuilder took = new StringBuilder(), used = new StringBuilder();
            int[] now = dosesNow();
            for (int f = 0; f < POTS.length; f++) {
                if (potsTaken[f] > 0) took.append(took.length() > 0 ? ", " : "").append(POT_NAMES[f]).append(' ').append(potsTaken[f]).append(" (").append(dosesAtStart[f]).append(" doses)");
                int u = Math.max(0, dosesAtStart[f] - now[f]);
                if (u > 0) used.append(used.length() > 0 ? ", " : "").append(POT_NAMES[f]).append(' ').append(u).append(" doses");
            }
            if (took.length() == 0 && specials == 0) return;
            System.out.println("[AfkTasks] " + p.getUsername() + " potions this trip: took " + (took.length() > 0 ? took : "none") + "; used " + (used.length() > 0 ? used : "none") + (specials > 0 ? "; specials " + specials : ""));
        }
        static final Map<Integer, int[]> COOK = new HashMap<>(); // raw -> {cooked, level}

        String[] targets() { return targetsOverride != null ? targetsOverride : task.targets; }
        String label() { return labelOverride != null ? labelOverride : task.label; }

        int[] products() {
            int[] base = productsOverride != null ? productsOverride : task.products;
            if (!cookHaul || base == null) return base;
            int[] out = new int[base.length * 2];
            int n = 0;
            for (int id : base) { out[n++] = id; int[] c = COOK.get(id); if (c != null) out[n++] = c[0]; }
            return java.util.Arrays.copyOf(out, n);
        }
        final boolean bank, bury, loot;
        final int stopAfter;
        final ScriptAPI api;
        Location spot;
        ScriptAPI.BottingOverlay overlay;
        State state = State.WORK;
        int collected, lastCount, wait, idleTicks, lootTicks;
        Location dest;
        Location walkFrom; // batch 18 item2: where the current goTo() walk started (the scenario record)
        Location underDest; // a dungeon spot: walk to the entrance first, climb down, then on to here
        int bankPause;                   // bots: one short pause at the booth before depositing
        Location routeLast;              // where it was when it last made progress (route following)
        long shortcutSince;              // pathing review 10-23 R-2: when walk() first saw the route's shortcut in use
        Location lastNodeAt; int lastNodeId; // the rock or tree it was working, to notice when it runs out
        double best = Double.MAX_VALUE;
        int sinceProgress, retries;
        int stallTeles; static final int STALL_TELES_MAX = Math.max(1, Integer.getInteger("afk.stallTeles", 2)); // batch 22 B21-8: paid stall teleports per goTo (retries reset after each, so a trap dest whose landing can't reach it paid every ~200 ticks until broke)
        boolean finishing, halted;
        // batch 23 T2 (design 4.1): player travel mode. A player's runner walks with the bots' route knowledge (the planner,
        // the Al Kharid toll and Shantay Pass hops, the bridge and Taverley detours, gate and island refusals) but never
        // teleports, never takes the boat and writes no failure records. -Dafk.playerNav=false, or the file
        // data/afk.playernav.off (re-read at most once a minute, like nav.on), puts players back on the plain walk.
        static final java.util.Map<String, long[]> SPENT = new java.util.concurrent.ConcurrentHashMap<>(); // batch 23 pre-review 2.1: per-run fee total of each player's Runner (reset in the constructor)
        static final long SPEND_CAP = Long.getLong("afk.spendCap", 100L); // gp of the player's own coins one run may pay at gates and passes
        static volatile boolean PLAYER_NAV = playerNavWanted();
        static volatile long playerNavRead = System.currentTimeMillis();
        static boolean playerNavWanted() {
            try { return !"false".equalsIgnoreCase(System.getProperty("afk.playerNav", "true")) && !new java.io.File(core.ServerConstants.DATA_PATH, "afk.playernav.off").exists(); } catch (Throwable t) { return true; }
        }
        static boolean playerNav() {
            long now = System.currentTimeMillis();
            if (now - playerNavRead >= 60_000L) {
                playerNavRead = now;
                boolean f = playerNavWanted();
                if (f != PLAYER_NAV) { PLAYER_NAV = f; System.out.println("[AfkTasks] player travel mode " + (f ? "on" : "off (afk.playerNav / data/afk.playernav.off)")); }
            }
            return PLAYER_NAV;
        }
        /** P32 (TestB16H leg A): the Lumbridge westbound via (NORTH_W) and the per-trip via memory (viaDone). DEFAULT OFF:
         *  on only with -Dafk.bridgeFix=true, and data/afk.bridgefix.off forces it off even then. Read once at class load.
         *  Bots and players share this path, so turning it on changes bot walking too. Off = the old behaviour exactly. */
        static final boolean BRIDGE_FIX = bridgeFixOn();
        /** P39 reachability: master -Dreach=true (default off); -Dreach.<fix> overrides one fix (defaults to the master). Off = train-4 exactly.
         *  myre (Gates: Mort Myre box), bridge (bridgeVia: the Lumbridge deck), shilo (Gates: Shilo closed to bots), gates (NavGrid: the
         *  built-in multi-tile gate record), sea (SmartAdventurers: a same-side shop before one across the sea; own switch, not the master). Gates and NavGrid read the same properties. */
        static final boolean REACH = Boolean.getBoolean("reach");
        static boolean reach(String sub) { return Boolean.parseBoolean(System.getProperty("reach." + sub, String.valueOf(REACH))); }
        static final boolean REACH_MYRE = reach("myre"), REACH_BRIDGE = reach("bridge"), REACH_SHILO = reach("shilo"), REACH_GATES = reach("gates");
        /** P39 R7: its own switch (-Dreach.sea=true), NOT the master: changes which shops bots visit, turned on separately. */
        static final boolean REACH_SEA = Boolean.getBoolean("reach.sea");
        static boolean bridgeFixOn() {
            try { return "true".equalsIgnoreCase(System.getProperty("afk.bridgeFix", "false")) && !new java.io.File(core.ServerConstants.DATA_PATH, "afk.bridgefix.off").exists(); } catch (Throwable t) { return false; }
        }
        /** Walks with the route knowledge (planner, tolls, the pass, detours, gate/island refusals): every bot, and players while player travel mode is on. */
        boolean travels() { return p.isArtificial() || playerNav(); }
        static String navMode() {
            return playerNav() ? "travel mode (" + (NavWalker.ON ? "route planner on" : "route planner off: learned routes and detours") + ", tolls from your pack, no teleports or boats)" : "plain walk (player travel mode off)";
        }
        /** batch 23 T2: the coins a walker has to hand at a gate: a bot its wallet (bank + pack, as before), a player the coins in the pack. */
        static long gateCoins(Player who) {
            if (who == null) return 0;
            if (who.isArtificial()) return Ledger.Wallet.coins(who);
            try { return who.getInventory().getAmount(995); } catch (Throwable t) { return 0; }
        }
        /** batch 23 T2: pays a gate or toll. Bots through Ledger.Wallet.pay (unchanged); a player from the pack only (never the bank from afar). */
        static boolean payGate(Player who, long amount) { return payGate(who, amount, "toll"); }
        static boolean payGate(Player who, long amount, String kind) { return payGate(who, amount, kind, kind); }
        static boolean payGate(Player who, long amount, String kind, String why) { // P16 merge: bots keep 22.1's payFee ([Paid] line, why = desert-toll/alkharid-toll/shantay-pass)
            if (who != null && who.isArtificial()) return Ledger.Wallet.payFee(who, amount, why);
            if (amount <= 0) return true;
            if (who == null || amount > Integer.MAX_VALUE) return false;
            long[] sp = SPENT.computeIfAbsent(key(who), k -> new long[2]); // batch 23 pre-review 2.1: [0] = gp paid this run, [1] = 1 once the cap stopped a payment
            if (sp[0] + amount > SPEND_CAP) { // the cap holds the player's own coins, as bug 296's cap does for bots
                sp[1] = 1;
                try { who.sendMessage("AFK: stopping, paying " + amount + " gp more would pass your " + SPEND_CAP + " gp limit for this run (" + sp[0] + " gp spent on fees)."); } catch (Throwable ignored) { }
                System.out.println("[Afk] " + who.getUsername() + " refused " + amount + " gp " + kind + ": run total " + sp[0] + " gp, cap " + SPEND_CAP + " gp");
                return false;
            }
            boolean ok;
            try { ok = who.getInventory().getAmount(995) >= amount && who.getInventory().remove(new Item(995, (int) amount)); } catch (Throwable t) { ok = false; }
            if (ok) { sp[0] += amount; System.out.println("[Afk] " + who.getUsername() + " paid " + amount + " gp " + kind); }
            return ok;
        }
        /** batch 23 pre-review 2.1: gp a player's run has paid in fees so far (0 for bots, whose coins Ledger.Wallet already caps). */
        static long spentGp(Player who) { long[] sp = who == null ? null : SPENT.get(key(who)); return sp == null ? 0 : sp[0]; }
        /** True once the spend cap refused a fee for this player's run: the Runner then stops with the cap message, not "carry N coins". */
        static boolean capHit(Player who) { long[] sp = who == null ? null : SPENT.get(key(who)); return sp != null && sp[1] != 0; }
        static String capMsg(Player who) { return "AFK: spent " + spentGp(who) + " gp on fees this run, stopping (limit " + SPEND_CAP + " gp; -Dafk.spendCap)."; }
        // batch 23 T2: players' loop ring (Failures.loopStat over this runner's own 128 tiles; no Failures records for players)
        int[] loopT, loopS; int loopH, loopSince = Integer.MIN_VALUE; Location loopLast;
        boolean replanned; // this goTo's one re-plan for a stuck player is used
        // P16 item 2 (players, planner off): per-goTo memory so a walk can't circle a via point, click one door for ever, or take 800 ticks to say stuck
        final java.util.Map<Location, int[]> viaSeen = new java.util.HashMap<>(); // via -> {best distance x10, tick of that best}
        final java.util.Set<Location> viaSkip = new java.util.HashSet<>();        // vias given up on this goTo
        final java.util.Set<Location> viaDone = new java.util.HashSet<>();        // vias reached this goTo (cleared in goTo): b26 loops fix 2 adds the AVOID via it was sent to (viaSent); P32 BRIDGE_FIX adds any via aimed at (viaAim)
        Location viaSent;                                                         // b26 loops fix 2: the AVOID via this trip was sent to
        Location viaAim;                                                          // P32 (BRIDGE_FIX only): the via the last detour step aimed at
        final java.util.Set<Location> doorsTried = new java.util.HashSet<>();     // doors clicked for DOOR_TICKS without a way opening
        Location doorAt; int doorTick, progTick;
        static final int VIA_TICKS = 25, DOOR_TICKS = 5, PLAYER_STUCK_TICKS = 100; // two stages of PLAYER_STUCK_TICKS (re-plan, then stop) = ~200 ticks
        boolean keepAll;        // players: ::afk ... keep: nothing sold at the booth
        boolean finishWanted;   // players: ::afk finish: bank first, then stop
        String last = "";
        // Danger sense: biggest hit taken, hp last tick, retreats so far, where we were last tick.
        int bigHit, lastHp, retreats, recoverTicks, dangerFlees;
        int lastBite = -10;    // combat review 10-23 #1: world tick of the last bite (a player eats every 3 ticks)
        int emergencies;       // combat review 10-23 #2: emergency teleports this trip
        Location lastLoc;
        String fleeReason, quitWhenSafe;
        int sinceGain;
        // batch 19c B2 (design 7 no-gain / no-xp, logging only): WORK ticks since the last gain, unstucks since it, the XP total at the last check, the busy flag over the window, once per trip
        int workNoGain, unstucks, lastTickN = -1, xpCheckN; double xpLast = -1; boolean notBusySeen, noGainNoted, scanEmpty; String noGainFid; // scanEmpty: the last target scan found no node
        Object lootPulse;
        int lootStuck;
        final Set<GroundItem> ignored = new HashSet<>();
        boolean sell, smeltHaul;
        // batch 23 T4 (design 2.2-2.3): the chain. NONE for every bot: bots still set cookHaul / smeltHaul and run exactly as before.
        Process process = Process.NONE;
        String want = "";          // the chosen product words ("platebody"), "" = the best the levels allow
        boolean countFinal;        // a player's chain: x<count> and the overlay count the final product (made), not the gathered one
        int made, finalLast;
        int[] finalIds = new int[0], chainOut;  // what "made" counts (for BURN/BURY: the logs/bones used up); chainOut: extra products (fletched, spun, smithed)
        int procStall, procLastIn = -1, procLastId, stationWalks;
        // P20 (K4) firemaking: the tile of the pulse in flight, the XP before it, the last tile that lit (TestB23Afk prints it),
        // tiles tried this trip, bad tiles so far, the last refusal note, and tiles whose dropped log must not be re-looted.
        Location lightAt, litAt; double lightXp; int lightBad; String lightNote;
        int lightLog, lightPack; // P32: the log id and its pack count before the pulse (second "did it light" signal)
        final Set<Location> lightTried = new HashSet<>();
        final Map<Location, Integer> litDrops = new HashMap<>();
        static final int LIGHT_MAX = 3, LIT_DROP_TICKS = 200;
        boolean cookSim;           // the server's cooking pulse wasn't usable for this runner: the old simple cook
        AfkMagic.Run magic;        // batch 25: the Magic job (spell, item, counts); null for every other chain and every bot
        boolean tripTold;
        long soldCoins;
        int noTargetTicks, barsMade;
        // Mine-for-bars: when the bank has no ores, mine a bag's worth in the bar's ratio, then smelt.
        Bar mineBar;
        final Map<Integer, Integer> oreNeeds = new HashMap<>();
        int mineOre = -1;
        final Set<Spot> triedSpots = new HashSet<>();
        int stopLevel, styleSkill = Skills.ATTACK, foodTaken;
        final long startedAt = System.currentTimeMillis();
        final double[] startXp = new double[Skills.SKILL_NAME.length];
        // What was already in the bag when the task started; never banked, sold or dropped.
        final Map<Integer, Integer> baseline = new HashMap<>();
        final Map<GroundItem, Integer> tries = new HashMap<>();

        Runner(Player p, Task task, boolean bank, int stopAfter, boolean bury, boolean loot) {
            super(1, p);
            if (!p.isArtificial()) SPENT.remove(key(p)); // batch 23 pre-review 2.1: a new run starts at 0 gp spent
            this.p = p; this.task = task; this.bank = bank; this.stopAfter = stopAfter;
            this.bury = bury; this.loot = loot || task.key.startsWith("c_");
            this.api = new ScriptAPI(p);
            this.spot = p.getLocation().transform(0, 0, 0);
            this.lastCount = countProducts();
            this.lastHp = p.getSkills().getLifepoints();
            for (int i = 0; i < startXp.length; i++) startXp[i] = p.getSkills().getExperience(i);
            for (Item it : p.getInventory().toArray()) if (it != null) baseline.merge(it.getId(), it.getAmount(), Integer::sum);
            for (Item it : p.getInventory().toArray()) { if (it == null) continue; int f = potFamily(it.getId()); if (f < 0) continue; potsTaken[f] += it.getAmount(); dosesAtStart[f] += potDoses(it.getId()) * it.getAmount(); if (f >= P_ATT) boostPots = true; } // batch 15 item2/4
            this.lastLoc = this.spot;
            try {
                overlay = api.getOverlay();
                overlay.init();
                overlay.setTitle("AFK: " + label());
                overlay.setTaskLabel(stopAfter > 0 ? "Collected (of " + stopAfter + "):" : "Collected:");
                overlay.setAmount(0);
            } catch (Throwable ignored) { overlay = null; }
        }

        String describe() {
            String st = state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (!"smelt".equals(processing) && (state == State.TO_FURNACE || state == State.SMELTING)) { // batch 23 T4: the station walk and step under their own names
                Process pr = Process.of(processing);
                st = state == State.SMELTING ? pr.ing : "to the " + pr.station.label;
            }
            return label() + (countFinal ? (task.kind == Kind.BANK ? "" : " then " + process.ing) + ", " + made + " made" : ", " + collected + " collected") + (stopAfter > 0 ? " of " + stopAfter : "") + ", " + st + (DEBUG ? " [" + last + "]" : "");
        }

        /** Bots: whatever this trip gathered goes straight to bot stock, even if the bank trip failed. */
        void sellLeftovers() {
            Map<Integer, Integer> budget = new HashMap<>(baseline);
            Map<Integer, Integer> byId = new java.util.LinkedHashMap<>(); // runner profiling 10-23: one Ledger.supply per item type (each is GE database work on the game thread; 28 logs were 28 calls)
            for (Item it : p.getInventory().toArray()) {
                if (it == null || keep(it)) continue;
                int n = gainedIn(it, budget);
                if (n <= 0) continue;
                Item part = new Item(it.getId(), n, it.getCharge());
                if (p.getInventory().remove(part)) byId.merge(it.getId(), n, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> e : byId.entrySet()) { try { Ledger.supply(p, e.getKey(), e.getValue()); } catch (Throwable ignored) { } }
        }

        /** The guide price the booth sells at, 0 when the Exchange doesn't trade it (two GE database queries). */
        static int guidePrice(int id) {
            try { if (PriceIndex.canTrade(id)) return PriceIndex.INSTANCE.getValue(id); } catch (Throwable ignored) { }
            return 0;
        }

        /** End-of-session report: time, xp per skill, items, coins and food used. */
        void summary() {
            long mins = Math.max(1, (System.currentTimeMillis() - startedAt) / 60000);
            StringBuilder xp = new StringBuilder();
            for (int i = 0; i < startXp.length; i++) {
                int gained = (int) (p.getSkills().getExperience(i) - startXp[i]);
                if (gained <= 0) continue;
                if (xp.length() > 0) xp.append(", ");
                xp.append(String.format("%s +%,d", Skills.SKILL_NAME[i], gained));
            }
            p.sendMessage("<col=0b5e1f>AFK summary: " + mins + " min, " + collected + " collected" + (countFinal && made > 0 ? ", " + made + (process.consumes() ? " used " + process.ing : " made " + process.ing) : "") + (barsMade > 0 ? ", " + barsMade + " bars smelted" : "") + (cooked > 0 ? ", " + cooked + " cooked" : "") + (soldCoins > 0 ? ", " + String.format("%,d", soldCoins) + " gp from sales" : "") + (foodTaken > 0 ? ", " + foodTaken + " food used from bank" : "") + ".");
            if (xp.length() > 0) p.sendMessage("<col=0b5e1f>XP gained: " + xp + ".");
            if (DEBUG) System.out.println("[AfkTasks] " + p.getUsername() + " summary: " + mins + " min, " + collected + " collected, xp " + xp);
        }

        Boolean savedRetaliate;
        // audit 10-23 A-13: puts auto-retaliate back at logout (the logout listeners run before the save). A logout mid-flee
        // saved retaliation=false: the runner's pulse only notices the player has gone after the save.
        kotlin.jvm.functions.Function1<Player, Unit> retaliateHook;

        /** Auto-retaliate off until restoreRetaliate(); the logout hook puts it back if the player leaves first (audit 10-23 A-13). */
        void retaliateOff() {
            if (savedRetaliate == null) savedRetaliate = p.getProperties().isRetaliating();
            p.getProperties().setRetaliating(false);
            if (retaliateHook == null) { // audit 10-23 A-13 (the hook never removes itself: the logout listeners are being iterated when it runs)
                retaliateHook = pl -> { if (savedRetaliate != null) { pl.getProperties().setRetaliating(savedRetaliate); savedRetaliate = null; } return Unit.INSTANCE; };
                try { p.logoutListeners.put("afk-retaliate", retaliateHook); } catch (Throwable ignored) { }
            }
        }

        static final boolean TRANSIT_ON = !"false".equals(System.getProperty("transit.run")); // batch 20 B1 kill switch
        boolean transit; // batch 20 B1: retaliation is off for this walk (attacked on the way)

        /**
         * batch 20 B1: on the way to the spot or the bank a roadside attacker doesn't stop the walk (33 bugs, 95 records:
         * ghouls by the Salve, Trollheim, the white wolves). Retaliation goes off (back on at the spot or the bank), run on,
         * any swing the bot started is stopped, and walk() steps on. Eating and the danger flee ran first (eatIfNeeded).
         * A fight job's last 10 tiles are the job itself, so retaliation stays there.
         */
        void transitGuard() {
            if (!TRANSIT_ON || !p.isArtificial()) return;
            boolean walking = (state == State.TO_SPOT || state == State.TO_BANK) && dest != null && boat == null
                    && p.getLocation().getDistance(dest) > (task.kind == Kind.FIGHT && state == State.TO_SPOT ? 10 : 2);
            if (walking) {
                if (!transit || savedRetaliate == null) { // recover() may have put it back on since
                    if (!p.inCombat()) return; // batch 20 review: the cheap check first (attackers() scans the local NPCs)
                    transit = true; retaliateOff();
                    p.getSettings().setRunToggled(true);
                    last = "attacked on the way, running on";
                    if (DEBUG) System.out.println("[AfkTasks] " + p.getUsername() + " attacked on the way to " + dest + ", running on");
                }
                if (p.getProperties().getCombatPulse().isAttacking()) p.getProperties().getCombatPulse().stop();
                return;
            }
            if (transit && state != State.RETREAT && state != State.RECOVER) { transit = false; restoreRetaliate(); } // arrived (a flee keeps it off and recover() puts it back)
        }

        void restoreRetaliate() {
            if (savedRetaliate != null) { p.getProperties().setRetaliating(savedRetaliate); savedRetaliate = null; }
            if (retaliateHook != null) { try { p.logoutListeners.remove("afk-retaliate", retaliateHook); } catch (Throwable ignored) { } retaliateHook = null; } // audit 10-23 A-13
        }

        void halt() {
            halted = true;
            RUNNING.remove(key(p), this); // audit 10-23 A-11: a halted runner never stays in RUNNING (only its own entry)
            restoreRetaliate();
            if (usePrayer) { try { p.getPrayer().reset(); } catch (Throwable ignored) { } }
            if (p.isArtificial()) { try { logPotions(); } catch (Throwable ignored) { } } // batch 15 item2: replaces the prayer-only line
            try {
                p.getPulseManager().clear();
                p.getInterfaceManager().closeOverlay();
            } catch (Throwable ignored) { }
            stop();
        }

        boolean bankingOut;

        /**
         * Pathing review 10-23 T-1: another runner was put in RUNNING for this bot while this one still ran (a trader's
         * finish() banks the goods out and returns without halting; the trader then started its next trip). Two runners
         * fought over the walk, and this one's halt() at its deposit cleared the new one's pulses. It bows out instead,
         * leaving the pulse manager, the walk and RUNNING to the newer runner.
         */
        void superseded() {
            halted = true;
            restoreRetaliate();
            if (usePrayer) { try { p.getPrayer().reset(); } catch (Throwable ignored) { } }
            System.out.println("[AfkTasks] " + p.getUsername() + " " + label() + " runner (" + state.name().toLowerCase(Locale.ROOT) + ") stops: a newer runner took over");
            stop();
        }

        void finish(String why) {
            Watchdog.flog(p, "finish: " + why); // batch 19a design 3.2 (bots only, inside)
            if (crossOnly) { System.out.println("[AfkTasks] " + p.getUsername() + " sea crossing ends: " + why); last = why; halted = true; crossing = false; return; } // batch 17 item3: not a trip: nothing to bank, sell or summarise
            // A bot with goods walks them to a bank first, like a player would, instead of them vanishing into stock from a field.
            if ((p.isArtificial() || finishWanted) && !questHold && !bankingOut && !halted && countProducts() > 0 && state != State.BANKING && nearestBank() != null && nearestBank().getDistance(p.getLocation()) < 220) {
                bankingOut = true; finishing = true; goalMsg = why;
                goTo(State.TO_BANK, nearestBank());
                return;
            }
            RUNNING.remove(key(p), this); // audit B-16: only its own entry (a newer runner for the same bot may already be in)
            try { NavWalker.forget(p); } catch (Throwable ignored) { } // audit B-19: the trip is over; the next one to the same spot plans afresh from wherever the bot is
            try { RouteMap.PLANS.remove(key(p)); RouteMap.USING.remove(key(p)); } catch (Throwable ignored) { } // pathing review 10-23 A-8: the learned-route plan too (a stale plan made the next walk count as "on route")
            if (p.isArtificial() && !questHold) sellLeftovers(); // a bot never carries a failed haul around for ever (a quest haul stays: the quest needs it)
            System.out.println("[AfkTasks] " + p.getUsername() + " finished: " + why + " (" + collected + " collected, " + barsMade + " bars)");
            if (magic != null) try { AfkMagic.end(this, why); } catch (Throwable ignored) { } // batch 25: the run's ECON line
            if (Watchdog.FAIL_ON && Watchdog.traced(p)) try { Failures.jobEnd(p, this, why); } catch (Throwable ignored) { } // batch 19c B2 (design 7): A's finish-reason table
            p.sendMessage("<col=0b5e1f>AFK: " + why);
            summary();
            halt();
        }

        @Override
        public void stop() {
            super.stop();
            restoreRetaliate(); // audit 10-23 A-13: also when the engine stops the pulse (player gone)
            Runner cur = RUNNING.get(key(p));
            if (cur == this) RUNNING.remove(key(p)); // a pulse the engine stopped (player gone) must not look "running"
        }

        @Override
        public boolean pulse() {
            if (halted) return true;
            if (!p.isActive()) { RUNNING.remove(key(p), this); halted = true; return true; } // audit B-16
            Runner cur = RUNNING.get(key(p));
            if (cur != null && cur != this) { superseded(); return true; } // pathing review 10-23 T-1: a newer runner drives this bot now
            long t0 = System.nanoTime(); // batch 19c B3 (design 8.2): this pulse's cost
            try {
                tickOnce();
            } catch (Throwable t) {
                System.out.println("[AfkTasks] " + p.getUsername() + ": " + t);
                Watchdog.fexc("AfkTasks.Runner.tickOnce", p, t); // batch 19a B3 (a real player's runner: no bot passed)
                finish("Stopped after an error.");
            }
            Watchdog.fpulse("AfkTasks.Runner", p, System.nanoTime() - t0); // batch 19c B3
            return halted;
        }

        void tickOnce() {
            // Level-up and other chat pop-ups pause actions, so clear them like the bot engine does.
            if (!questHold && boat == null && !QuestRunner.active(p) && (p.hasModalOpen() || p.getDialogueInterpreter().getDialogue() != null)) {
                p.getInterfaceManager().closeChatbox();
                p.getDialogueInterpreter().close();
                p.getInterfaceManager().close();
            }
            int now = countProducts();
            if (now > lastCount) { collected += now - lastCount; sinceGain = 0; triedSpots.clear(); if (overlay != null) overlay.setAmount(collected); workNoGain = 0; unstucks = 0; notBusySeen = false; } // batch 19c B2: a gain resets the no-gain window
            else if (state == State.WORK && task.kind != Kind.FIGHT && task.kind != Kind.SMELT && task.kind != Kind.BANK && mineBar == null) sinceGain++;
            // Nothing gained for a long while (spot moved, stuck interaction): drop the action and pick a new target.
            if (sinceGain > 150) { sinceGain = 0; p.getPulseManager().clear(); p.scripts.reset(); last = "unstuck"; unstucks++; } // batch 19c B2: counted
            lastCount = now;
            if (countFinal) trackMade(); // batch 23 T4: players' chains count the final product
            if (Watchdog.FAIL_ON && !noGainNoted && p.isArtificial()) try { noGainTick(); } catch (Throwable ignored) { } // batch 19c B2 (design 7): logging only

            if (p.getAttribute("state:death", false)) return; // combat review 10-23 #12: dying (the death animation): no eating, fleeing or teleporting out of it
            if (sense()) return;
            if (eatIfNeeded()) return; // combat review 10-23 #1: eating and the flee checks come before the action wait (they were blind 6-9 ticks after every attack or bite)
            transitGuard(); // batch 20 B1
            if (wait > 0) { wait--; return; }
            if (p.isArtificial() && !finishing && state == State.WORK && Gates.desert(p.getLocation()) && !Gates.carriesWater(p)) { finish("Out of water."); return; } // combat review 10-23 #9: the last waterskin is empty, so out of the heat like a player
            if (!finishing && stopAfter > 0 && countFinal && made >= stopAfter) { // batch 23 T4 (design 2.3): "x100 then smelt" = 100 bars
                finishing = true;
                if (process.consumes() || !bank || nearestBank() == null) { finish("Target reached (" + made + " made)."); return; }
                if (state == State.WORK || state == State.SMELTING && process.station == Station.NONE) { p.getPulseManager().clear(); goalMsg = "Target reached (" + made + " made)."; goTo(State.TO_BANK, nearestBank()); return; }
                goalMsg = "Target reached (" + made + " made)."; // at or on the way to a station: that step ends, then the bank visit finishes it
            }
            if (!finishing && stopAfter > 0 && !countFinal && collected >= stopAfter) {
                finishing = true;
                if (bank && state == State.WORK && nearestBank() != null) { goTo(State.TO_BANK, nearestBank()); return; }
                if (!bank || nearestBank() == null) { finish("Target reached."); return; }
            }
            int sk = trainedSkill();
            if (!finishing && stopLevel > 0 && sk >= 0 && p.getSkills().getStaticLevel(sk) >= stopLevel) {
                finishing = true;
                String why = Skills.SKILL_NAME[sk] + " reached level " + stopLevel + ".";
                if (bank && state == State.WORK && countProducts() > 0 && nearestBank() != null) { goalMsg = why; goTo(State.TO_BANK, nearestBank()); return; }
                finish(why);
                return;
            }

            switch (state) {
                case WORK: work(); break;
                case TO_BANK: walk(State.BANKING); break;
                case BANKING: deposit(); break;
                case TO_SPOT: walk(State.WORK); break;
                case RETREAT:
                    if (p.getProperties().getCombatPulse().isAttacking()) p.getProperties().getCombatPulse().stop();
                    walk(State.RECOVER);
                    break;
                case RECOVER: recover(); break;
                case TO_FURNACE: walk(State.SMELTING); break;
                case SMELTING: smelt(); break;
            }
        }

        String goalMsg;

        /** batch 19c B2 (design 7): products: 1,000 WORK ticks without a gain or 3 unstucks without one; FIGHT/SMELT/mine-for-bars: no XP over 1,000 WORK ticks (two reads every 100). Once per trip; game ticks, so the 3-tick bot pulse counts right. */
        void noGainTick() {
            int t = GameWorld.getTicks();
            int dt = lastTickN < 0 ? 0 : Math.max(0, Math.min(10, t - lastTickN)); lastTickN = t;
            if (state != State.WORK) return;
            workNoGain += dt;
            if (!busy()) notBusySeen = true;
            if (task.kind == Kind.FIGHT || task.kind == Kind.SMELT || mineBar != null) {
                if ((xpCheckN += dt) < 100) return;
                xpCheckN = 0;
                double x = 0; for (int i = 0; i < startXp.length; i++) x += p.getSkills().getExperience(i);
                if (xpLast < 0 || x > xpLast + 0.5) { xpLast = x; workNoGain = 0; notBusySeen = false; return; }
                if (workNoGain >= 1000) noGain("no-xp");
            } else if (workNoGain >= 1000 || unstucks >= 3) noGain("no-gain");
        }

        /** batch 19c B2: the record, its sub-code in rsub (first match), and a log-ring line so the later path record (idle give-up) carries the fid. */
        void noGain(String code) {
            noGainNoted = true;
            int sk = trainedSkill(), lvl = sk >= 0 ? p.getSkills().getStaticLevel(sk) : -1;
            boolean wrongLevel = false;
            if (task.kind == Kind.TREE && targets() != null) { int need = 99; for (String n : targets()) need = Math.min(need, treeLevel(n)); wrongLevel = lvl >= 0 && lvl < need; } // [Fable] trees are the one task with a level table here
            String sub = p.getInventory().isFull() ? "inventory-full" : task.tools != null && task.tools.length > 0 && !hasAny(p, task.tools) ? "wrong-tool" : wrongLevel ? "wrong-level"
                    : scanEmpty || last != null && last.startsWith("no ") || !triedSpots.isEmpty() ? "spot-depleted" : !notBusySeen ? "busy-stuck" : "unknown";
            java.util.List<Integer> held = new java.util.ArrayList<>();
            if (task.tools != null) for (int id : task.tools) if (p.getInventory().getAmount(id) > 0 || p.getEquipment().getAmount(id) > 0) held.add(id);
            int[] at = spot == null ? null : new int[]{spot.getX(), spot.getY(), spot.getZ()};
            Map<String, Object> c = Watchdog.ctx("task", task.name(), "state", state.name(), "last", last == null ? null : last.length() > 80 ? last.substring(0, 80) : last, "collected", collected, "sinceGainTicks", workNoGain, "unstucks", unstucks,
                    "tools", held, "level", lvl, "spot", at, "inv", Watchdog.ctx("n", p.getInventory().itemCount(), "free", p.getInventory().freeSlots()));
            c.put("replay", Watchdog.ctx("kind", "job", "task", task.name(), "spot", at, "level", lvl, "tools", held));
            noGainFid = Watchdog.fev("skilling", p, "no_gain", code, sub, spot, c);
            if (noGainFid != null) Watchdog.flog(p, code + " " + noGainFid + " (" + sub + ")"); // [Fable] design 7's ctx.noGainFid: the 19a failure() has no ctx, so the link rides in the log ring
        }

        /** The skill this task levels, or -1 when it has none (picking, ground items). */
        int trainedSkill() {
            if (process != Process.NONE) return process.skill; // batch 23 T4: 'to <level>' is the process skill (Smithing for smelt, Fletching for fletch); bots never set a process
            switch (task.kind) {
                case TREE: return Skills.WOODCUTTING;
                case ROCK: return Skills.MINING;
                case FISH: return Skills.FISHING;
                case THIEVE: return task == Task.WOOL ? -1 : Skills.THIEVING;
                case FIGHT: return styleSkill;
                case SMELT: return Skills.SMITHING;
                default: return -1;
            }
        }

        static final int[] TREE_LEVELS = {1, 1, 1, 1, 15, 30, 45, 60, 75}; // matches BEST_TREE names

        int treeLevel(String name) {
            String[] n = Task.BEST_TREE.targets;
            for (int i = 0; i < n.length; i++) if (n[i].equalsIgnoreCase(name)) return TREE_LEVELS[i];
            return 1;
        }

        boolean busy() {
            return p.getPulseManager().hasPulseRunning() || p.scripts.getActiveScript() != null || p.getLocks().isInteractionLocked();
        }

        // ------------------------------------------------------------------ working

        void work() {
            if (task.kind == Kind.SMELT) { smeltCycle(); return; }
            if (task.kind == Kind.BANK) { bankLoop(); return; } // batch 23 T4
            if (smeltHaul && task.kind == Kind.ROCK) {
                if (mineBar == null && !planSmeltHaul()) { /* fell back to plain mining */ }
                else { if (!busy()) mineForBars(); return; }
            }
            if (p.getInventory().isFull()) {
                if (cookHaul && rawFishCount() > 0) {
                    Location r = nearestRange();
                    if (r != null) { processing = "cook"; goTo(State.TO_FURNACE, r); return; }
                }
                if (smeltHaul && bestBar(false) != null) {
                    Location f = nearestFurnace();
                    if (f != null) { goTo(State.TO_FURNACE, f); return; }
                }
                if (!smeltHaul && !cookHaul && process != Process.NONE && startProcess()) return; // batch 23 T4: fletch, burn (on the spot) or spin (at the wheel) before delivering
                if (bank) {
                    Location b = nearestBank();
                    if (b == null) { finish("No bank close enough to walk to; use Drop here instead."); return; }
                    goTo(State.TO_BANK, b);
                } else {
                    dropProducts();
                }
                return;
            }
            if (task.kind == Kind.FIGHT) { fight(); return; }
            if (busy()) { idleTicks = 0; last = "busy " + p.getPulseManager().getCurrent() + " " + p.scripts.getActiveScript(); return; }
            if (p.getLocation().getDistance(spot) > (essenceMine(spot) ? 40 : 16)) { goTo(State.TO_SPOT, spot); return; } // pathing review 10-23 A-1: the essence rocks are ~21 tiles from the mine's middle (the spot): 16 sent bots back to the middle every ~30 s
            if (p.isArtificial() && ((task == Task.BAIT && p.getInventory().getAmount(313) == 0) || (task == Task.LURE && p.getInventory().getAmount(FEATHER) == 0))) { // audit 10-21 B2
                finish(task == Task.BAIT ? "Out of bait." : "Out of feathers."); return;
            }
            if (task.kind == Kind.GROUND) {
                if (pickUpLoot()) { idleTicks = 0; noTargetTicks = 0; wait = 1; return; }
                if (++noTargetTicks > 100) { noTargetTicks = 0; relocate(); return; }
                if (++idleTicks > 12) { idleTicks = 0; api.randomWalkTo(spot, 4); }
                return;
            }

            Node target;
            switch (task.kind) {
                case TREE: case PICK: target = nearestScenery(); break;
                case ROCK: target = nearestRock(); break;
                default: target = nearestNpc(); break;
            }
            scanEmpty = target == null; // batch 19c B2: no-gain's spot-depleted sub-code
            if (target == null) {
                if (++noTargetTicks > (collected == 0 ? 20 : 110)) { noTargetTicks = 0; relocate(); return; } // respawns take a while
                if (++idleTicks > 6) { idleTicks = 0; api.randomWalkTo(spot, 4); }
                return;
            }
            noTargetTicks = 0;
            idleTicks = 0;
            if (p.isArtificial() && lastNodeAt != null && target instanceof Scenery) {
                Scenery was = RegionManager.getObject(lastNodeAt.getZ(), lastNodeAt.getX(), lastNodeAt.getY());
                boolean gone = was == null || was.getId() != lastNodeId;
                if (gone && !target.getLocation().equals(lastNodeAt)) { // it ran out: a moment of AFK before moving on, like a player
                    lastNodeAt = null;
                    wait = 2 + RNG.nextInt(6);
                    int r = RNG.nextInt(4);
                    if (r == 0) api.randomWalkTo(p.getLocation(), 1);
                    else if (r == 1) p.faceLocation(p.getLocation().transform(RNG.nextInt(3) - 1, RNG.nextInt(3) - 1, 0));
                    return;
                }
            }
            if (target instanceof Scenery) { lastNodeAt = target.getLocation(); lastNodeId = ((Scenery) target).getId(); }
            last = "interact " + target.getName() + "@" + target.getLocation();
            interact(target, task.option, target instanceof NPC ? IntType.NPC : IntType.SCENERY);
            wait = 1 + RNG.nextInt(2);
        }

        static final core.game.node.entity.player.link.prayer.PrayerType[] PROTECTS = {core.game.node.entity.player.link.prayer.PrayerType.PROTECT_FROM_MELEE, core.game.node.entity.player.link.prayer.PrayerType.PROTECT_FROM_MISSILES, core.game.node.entity.player.link.prayer.PrayerType.PROTECT_FROM_MAGIC}; // batch 15 item1

        /** batch 15 item1: the protection for whatever is hitting us (its current combat style), or null when the bot hasn't the level. */
        core.game.node.entity.player.link.prayer.PrayerType protectFor() {
            Object a = p.getAttribute("combat-attacker", null);
            if (!(a instanceof core.game.node.entity.Entity) || !((core.game.node.entity.Entity) a).isActive()) a = p.getProperties().getCombatPulse().getVictim();
            core.game.node.entity.combat.CombatStyle st = a instanceof core.game.node.entity.Entity ? ((core.game.node.entity.Entity) a).getProperties().getCombatPulse().getStyle() : null;
            core.game.node.entity.player.link.prayer.PrayerType want = st == core.game.node.entity.combat.CombatStyle.RANGE ? PROTECTS[1] : st == core.game.node.entity.combat.CombatStyle.MAGIC ? PROTECTS[2] : PROTECTS[0];
            int lvl = want == PROTECTS[0] ? 43 : want == PROTECTS[1] ? 40 : 37;
            return p.getSkills().getStaticLevel(Skills.PRAYER) >= lvl ? want : null;
        }

        /** S8/E27: a bot that prays keeps a protection prayer up while hunting and drinks (buys) a prayer potion when low. batch 15 item1: picked by the attacker's style. */
        void prayerTick() {
            if (!usePrayer || !p.isArtificial()) return;
            try {
                core.game.node.entity.player.link.prayer.Prayer pr = p.getPrayer();
                double points = p.getSkills().getPrayerPoints();
                if (points < 8) {
                    // A dose from a potion it carries (4 -> 3 -> 2 -> 1 -> vial), like a player; none left, no more praying.
                    int[] doses = {2434, 139, 141, 143}; boolean got = false;
                    for (int k = doses.length - 1; k >= 0 && !got; k--) {
                        if (p.getInventory().getAmount(doses[k]) <= 0 || !p.getInventory().remove(new Item(doses[k], 1))) continue;
                        p.getInventory().add(new Item(k + 1 < doses.length ? doses[k + 1] : 229, 1)); got = true;
                        try { p.getAnimator().animate(new core.game.world.update.flag.context.Animation(829)); } catch (Throwable ignored) { }
                    }
                    if (got) { p.getSkills().incrementPrayerPoints(Math.min(p.getSkills().getStaticLevel(Skills.PRAYER), 7 + p.getSkills().getStaticLevel(Skills.PRAYER) / 4)); potions++; last = "drank a prayer potion"; } // 7 + level/4 per dose (was level/4.0*4 = a full restore every sip, audit 10-22)
                    else { for (core.game.node.entity.player.link.prayer.PrayerType t : PROTECTS) if (pr.get(t)) pr.toggle(t); return; } // batch 15 item1: whichever protection was up
                }
                boolean inFight = p.inCombat() || p.getProperties().getCombatPulse().isAttacking();
                core.game.node.entity.player.link.prayer.PrayerType want = inFight && points >= 1 ? protectFor() : null; // batch 15 item1
                for (core.game.node.entity.player.link.prayer.PrayerType t : PROTECTS) if (t != want && pr.get(t)) pr.toggle(t);
                if (want != null && !pr.get(want)) pr.toggle(want);
            } catch (Throwable ignored) { }
        }

        /** batch 15 item4: in a fight, a bot that set out with boost potions drinks one once the last boost has worn off. */
        void boostTick() {
            if (!boostPots || !p.isArtificial() || !p.inCombat() || GameWorld.getTicks() - lastBite < 3) return;
            Skills s = p.getSkills();
            int id = -1;
            boolean ranged = p.getProperties().getCombatPulse().getStyle() == core.game.node.entity.combat.CombatStyle.RANGE; // batch 15 review: only the potions for the style it fights with
            if (ranged && s.getLevel(Skills.RANGE) <= s.getStaticLevel(Skills.RANGE)) id = carriedPot(P_RANGE);
            if (!ranged && id < 0 && s.getLevel(Skills.ATTACK) <= s.getStaticLevel(Skills.ATTACK)) id = carriedPot(P_SATT, P_ATT, P_COMBAT);
            if (!ranged && id < 0 && s.getLevel(Skills.STRENGTH) <= s.getStaticLevel(Skills.STRENGTH)) id = carriedPot(P_SSTR, P_STR, P_COMBAT);
            if (id < 0) { if (carriedPot(P_ATT, P_STR, P_SATT, P_SSTR, P_COMBAT, P_RANGE) < 0) boostPots = false; return; } // all drunk: stop looking
            api.forceEat(id); lastBite = GameWorld.getTicks(); last = "drank a " + POT_NAMES[potFamily(id)] + " potion";
        }

        /** batch 15 item5: spec weapons and their special energy (the engine's handlers: dragon dagger, longsword, scimitar, whip, magic shortbow). */
        static int specCost(int weapon) {
            switch (weapon) {
                case 1215: case 1231: case 5680: case 5698: case 1305: return 25;
                case 4151: return 50;
                case 4587: case 861: return 55;
                default: return 0;
            }
        }

        /** batch 15 item5: a bot wielding a spec weapon uses the special when it has the energy, like a player. */
        void specTick() {
            if (!p.isArtificial()) return;
            Item w = p.getEquipment().get(3);
            int cost = w == null ? 0 : specCost(w.getId());
            if (cost <= 0) return;
            core.game.node.entity.player.link.Settings st = p.getSettings();
            if (st.isSpecialToggled() || st.getSpecialEnergy() < cost) return; // setSpecialToggled flips the state: only called when it is off
            st.setSpecialToggled(true); specials++; last = "special attack";
        }

        void fight() {
            prayerTick();
            boolean attacking = p.getProperties().getCombatPulse().isAttacking();
            // Chasing a target we never manage to hit (blocked path, taken by someone else): give up on it.
            if (attacking && !p.inCombat()) {
                if (++chaseTicks > 15) {
                    Object v = p.getProperties().getCombatPulse().getVictim();
                    if (v instanceof NPC) skipNpcs.add((NPC) v);
                    p.getProperties().getCombatPulse().stop();
                    chaseTicks = 0; last = "gave up chase";
                    return;
                }
            } else chaseTicks = 0;
            if (p.inCombat() || attacking) { lootTicks = 4; last = "combat inC=" + p.inCombat(); boostTick(); if (p.inCombat()) specTick(); return; } // batch 15 items4-5
            // Out of combat but stuck on the same walk or pickup while standing still: let it go.
            Object cur = p.getPulseManager().getCurrent();
            if (busy() && cur == lootPulse && !p.getWalkingQueue().isMoving()) {
                if (++lootStuck > 10) { p.getPulseManager().clear(); p.scripts.reset(); lootPulse = null; lootStuck = 0; last = "unstuck"; return; }
            } else { lootPulse = cur; lootStuck = 0; }
            if (p.getWalkingQueue().isMoving()) return;
            if (busy()) { last = "fbusy " + p.getPulseManager().getCurrent() + " " + p.scripts.getActiveScript(); return; }
            if (loot && lootTicks > 0) { lootTicks--; if (lootTicks == 0 && pickUpLoot()) { lootTicks = 2; } return; }
            if (bury && buryBones()) { wait = 2; return; }
            if (loot && pickUpLoot()) { wait = 1; return; }
            if (p.getLocation().getDistance(spot) > 16) { goTo(State.TO_SPOT, spot); return; }
            NPC n = nearestNpc();
            scanEmpty = n == null; // batch 19c B2
            if (n == null) {
                if (halted) return;
                if (++noTargetTicks > (collected == 0 && kills == 0 ? 25 : 110)) { noTargetTicks = 0; relocate(); return; }
                if (++idleTicks > 8) { idleTicks = 0; api.randomWalkTo(spot, 5); }
                return;
            }
            noTargetTicks = 0;
            idleTicks = 0;
            last = "attack " + n.getName();
            kills++;
            p.attack(n);
            wait = 2;
        }

        boolean pickUpLoot() {
            if (p.getInventory().isFull()) return false;
            GroundItem nearest = null;
            double d = 9;
            List<GroundItem> near = new ArrayList<>(); // audit B-17: only the 8x8 chunks within 9 tiles, not a copy of the whole world's ground items
            Location me = p.getLocation();
            for (int cx = (me.getX() - 9) >> 3; cx <= (me.getX() + 9) >> 3; cx++) for (int cy = (me.getY() - 9) >> 3; cy <= (me.getY() + 9) >> 3; cy++)
                try { near.addAll(core.game.world.map.RegionManager.getRegionChunk(Location.create(cx << 3, cy << 3, me.getZ())).getItems()); } catch (Throwable ignored) { }
            for (GroundItem g : near) {
                if (g == null || !g.isActive() || g.isRemoved() || ignored.contains(g)) continue;
                if (g.getLocation().getZ() != p.getLocation().getZ()) continue;
                boolean wanted = products() == null ? g.isAutoSpawn() : has(products(), g.getId());
                if (!wanted && !(bury && g.getId() == BONES) && !(bury && g.getId() == BIG_BONES)) continue;
                if (g.isPrivate() && !g.droppedBy(p)) continue;
                if (litDrop(g)) continue; // P20: a log dropped by a light that failed is not loot (no pick-up/re-light loop)
                double dist = g.getLocation().getDistance(p.getLocation());
                if (dist < d && g.getLocation().getDistance(spot) <= 18) { d = dist; nearest = g; }
            }
            if (DEBUG) System.out.println("[AfkTasks] " + p.getUsername() + " loot -> " + nearest);
            if (nearest == null) return false;
            // Standing on or next to it: take it straight away (the walk-to-item route can hang on the same tile).
            if (d <= 1.5 && p.getInventory().hasSpaceFor(new Item(nearest.getId(), nearest.getAmount()))) {
                Item got = new Item(nearest.getId(), nearest.getAmount(), nearest.getCharge());
                if (GroundItemManager.destroy(nearest) != null) p.getInventory().add(got);
                return true;
            }
            // Otherwise walk onto its tile; a few tries per item, then it is skipped (unreachable).
            if (tries.merge(nearest, 1, Integer::sum) > 3) { ignored.add(nearest); return false; }
            Path path = Pathfinder.find(p, nearest.getLocation());
            if (path == null || !path.isSuccessful()) { ignored.add(nearest); return false; }
            path.walk(p);
            if (tries.size() > 200) tries.clear();
            if (ignored.size() > 1000) ignored.clear();
            return true;
        }

        int bonesKept; boolean boneFlip;

        boolean buryBones() {
            // Bots bury half and keep half (owner notes #7): Prayer still climbs, and bones still reach the Exchange.
            if (p.isArtificial()) {
                int n = p.getInventory().getAmount(BONES) + p.getInventory().getAmount(BIG_BONES);
                if (n <= bonesKept) return false;
                boneFlip = !boneFlip;
                if (boneFlip) { bonesKept++; return false; }
            }
            for (Item it : p.getInventory().toArray()) {
                if (it == null || (it.getId() != BONES && it.getId() != BIG_BONES)) continue;
                Option bury = null;
                for (Option o : it.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase("bury")) bury = o;
                if (bury == null) return false;
                if (!InteractionListeners.run(it.getId(), IntType.ITEM, "bury", p, it)) it.getInteraction().handleItemOption(p, bury, p.getInventory());
                lastCount = countProducts(); // a buried bone is not a lost one
                return true;
            }
            return false;
        }

        /** How hard the current threat hits: the worst seen so far, or everything attacking us at once (multi-combat). */
        int expectedHit() {
            int sum = 0; // combat review 10-23 #8: three monsters on us hit as three, not as the strongest one
            for (NPC n : attackers()) sum += maxHitOf(n);
            int h = Math.max(bigHit, Math.min(sum, p.getSkills().getMaximumLifepoints()));
            return Math.max(h, 1);
        }

        List<NPC> attackers() {
            List<NPC> list = new ArrayList<>();
            for (NPC n : RegionManager.getLocalNpcs(p, 8)) {
                if (n == null || !n.isActive()) continue;
                try {
                    if (n.getProperties().getCombatPulse().getVictim() == p) list.add(n);
                } catch (Throwable ignored) { }
            }
            return list;
        }

        boolean eatIfNeeded() {
            boolean poisoned = false; try { poisoned = core.api.ContentAPIKt.isPoisoned(p); } catch (Throwable ignored) { } // batch 15 item3
            if (state == State.RECOVER && !p.inCombat() && !poisoned) return false; // batch 15 item3: the poison keeps hitting while it rests
            Skills s = p.getSkills();
            int hp = s.getLifepoints(), max = s.getMaximumLifepoints();
            if (poisoned && p.isArtificial() && GameWorld.getTicks() - lastBite >= 3) { // batch 15 item3: drink the antipoison it carries
                int a = carriedPot(P_SANTI, P_ANTI);
                if (a > 0) { api.forceEat(a); lastBite = GameWorld.getTicks(); last = "drank an antipoison"; return true; }
            }
            int hit = expectedHit();
            // Eat before a worst-case hit (plus one more) could take us under a quarter of our health.
            boolean low = hp <= Math.max(p.isArtificial() || poisoned ? (max * 3) / 5 : max / 2, hit * 2 + max / 4); // bots eat at 60%; batch 15 item3: anyone poisoned too
            if (low && hp < max) {
                if (GameWorld.getTicks() - lastBite < 3 && foodCount() > 0) return true; // combat review 10-23 #1: still swallowing; a player eats once every 3 ticks
                for (Item it : p.getInventory().toArray()) {
                    if (it == null || !has(FOOD, it.getId()) || has(products(), it.getId())) continue;
                    api.forceEat(it.getId());
                    lastBite = GameWorld.getTicks(); // combat review 10-23 #1: a 3-tick eat delay instead of wait = 2 (9 ticks between bites for bots)
                    return true;
                }
            }
            if (state == State.RETREAT || state == State.RECOVER) return false;
            List<NPC> hitting = attackers();
            if (task.kind != Kind.FIGHT && task.kind != Kind.THIEVE && hitting.isEmpty()) return false;
            String why = null;
            int cb = 99; try { cb = p.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
            if (foodCount() == 0 && hp <= Math.max(hit * 2, cb < 60 ? max / 2 : (max * 2) / 5)) why = "low on health with no food left"; // audit B9
            else if (hp <= hit) why = "one more hit could kill you";
            else {
                for (NPC n : hitting) {
                    String d = dangerOf(n);
                    if (d != null) { why = "attacked by " + d; break; }
                }
            }
            if (why == null) return false;
            flee(why);
            return true;
        }

        void flee(String why) {
            if (why.startsWith("attacked by") && ++dangerFlees >= 2)
                quitWhenSafe = "Dangerous monsters keep attacking you here (" + why.substring(12) + "). Pick a safer spot.";
            if (++retreats > 6) { finish("Retreated too many times (" + why + "); this spot is too tough for now."); return; }
            fleeReason = why;
            if (DEBUG) System.out.println("[AfkTasks] " + p.getUsername() + " flee: " + why);
            p.sendMessage("<col=ff0000>AFK: " + why + ", running to safety.");
            p.getPulseManager().clear();
            p.getProperties().getCombatPulse().stop();
            // Fighting back while running away just stops us in our tracks.
            retaliateOff();
            p.getSettings().setRunToggled(true);
            Location b = nearestBank(); // pathing review 10-23 A-3: banks this bot may enter only (Shilo's from Karamja ended the trip mid-flight)
            if (b == null || (b.getDistance(p.getLocation()) <= 3 && !attackers().isEmpty())) b = escapePoint(); // pathing review 10-23 A-2: already at the bank and still hit there: away from them, not "to" where it stands
            Location tele = nearestBank();
            if (tele == null && p.getLocation().getY() > 6400) tele = bankAbove(); // audit B-1: underground, the bank above (nearestBank only knows surface banks); pathing review 10-23 A-3: one it may enter
            if (tele == null && p.isArtificial()) tele = nearestOpenBankAnywhere(); // combat review 10-23 #3: a bot's tab works 220+ tiles from a bank too (the walking target stays as it was)
            // A bot with no food left or one hit from death tabs out like a player (owner notes #1), if it can pay.
            // pathing review 10-23 A-2: at most two a trip (combat review #2's cap was only checked once safe, so a bot still
            // being hit at the landing re-fled and tabbed again every 30 ticks); after that it runs on foot.
            if (p.isArtificial() && tele != null && emergencies < 2 && (why.contains("no food") || why.contains("one more hit")) && Ledger.Wallet.payTeleport(p)) {
                BotChat.say(p, BotChat.pick("Out of food, tabbing!", "Nope. Tabbing out.", "Too close. Teleporting.", "Food's gone, I'm out!", "Nope nope nope. Teleport."));
                Spectacle.teleport(p, tele); // batch 15 item1
                b = tele;
                emergencies++; // combat review 10-23 #2
                System.out.println("[AfkTasks] " + p.getUsername() + " emergency teleport (" + why + ") to " + tele);
            }
            goTo(State.RETREAT, b);
        }

        /** A reachable tile about 15 steps away that ends up as far as possible from whatever is attacking. */
        Location escapePoint() {
            Location c = p.getLocation();
            List<NPC> foes = attackers();
            Location best = null;
            double bestScore = -1;
            for (int i = 0; i < 16; i++) {
                double r = Math.PI * 2 * i / 16;
                Location t = c.transform((int) Math.round(Math.cos(r) * 15), (int) Math.round(Math.sin(r) * 15), 0);
                Path path = Pathfinder.find(p, t);
                if (path == null || !path.isSuccessful() || path.getPoints().isEmpty()) continue;
                Point end = path.getPoints().peekLast();
                double score = Math.hypot(end.getX() - c.getX(), end.getY() - c.getY());
                for (NPC n : foes) score = Math.min(score, Math.hypot(end.getX() - n.getLocation().getX(), end.getY() - n.getLocation().getY()));
                if (score > bestScore) { bestScore = score; best = Location.create(end.getX(), end.getY(), c.getZ()); }
            }
            return best != null ? best : c.transform(1, 0, 0);
        }

        /** Rest somewhere safe until health is back, then go back to work. */
        void recover() {
            Skills s = p.getSkills();
            if (p.inCombat() || !attackers().isEmpty()) {
                if (++recoverTicks > 10) { recoverTicks = 0; if (dangerFlees > 0) dangerFlees--; flee(fleeReason == null ? "still being chased" : fleeReason); } // pathing review 10-23 A-2: a re-flee counts as a retreat (retreats-- here undid flee()'s ++, so "Retreated too many times" never came)
                return;
            }
            recoverTicks = 0;
            if (quitWhenSafe != null) { finish(quitWhenSafe); return; }
            if (p.isArtificial() && emergencies >= 2) { finish("Teleported out twice this trip; that's enough for one trip."); return; } // combat review 10-23 #2: a cap per trip
            // Safe now, but hungry: if the bank has food, fetch it instead of resting for minutes.
            if (foodCount() == 0 && !nearBooth() && !foodTrip && bankHasFood()) {
                Location b = nearestBank();
                if (b == null && p.isArtificial() && p.getLocation().getY() > 6400 && Ledger.Wallet.payTeleport(p)) { // audit B-1: no walkable bank underground, so teleport to the one above
                    b = bankAbove(); // pathing review 10-23 A-3: one it may enter
                    Spectacle.teleport(p, b); // batch 15 item1
                    System.out.println("[AfkTasks] " + p.getUsername() + " teleports out of the dungeon for food to " + b);
                }
                if (b != null) { foodTrip = true; p.sendMessage("<col=0b5e1f>AFK: out of food, fetching more from the bank."); goTo(State.RETREAT, b); return; }
            }
            if (foodCount() == 0 && nearBooth() && restockFood() > 0) {
                foodTrip = false;
                p.sendMessage("<col=0b5e1f>AFK: restocked food, heading back.");
                p.getSettings().setRunToggled(false);
                restoreRetaliate();
                goTo(State.TO_SPOT, spot);
                return;
            }
            // combat review 10-23 #2: a bot with no food on it and none in the bank used to rest and walk back with nothing to
            // eat, then tab out again (163 of 361 emergency teleports were repeats). It ends the trip; the next one buys food.
            if (p.isArtificial() && (task.kind == Kind.FIGHT || task.kind == Kind.THIEVE) && foodCount() == 0 && !bankHasFood()) { finish("Out of food."); return; }
            if (s.getLifepoints() * 10 >= s.getMaximumLifepoints() * 9) {
                p.sendMessage("<col=0b5e1f>AFK: health is back, returning to work.");
                retreats = 0; dangerFlees = Math.max(0, dangerFlees - 1);
                p.getSettings().setRunToggled(false);
                restoreRetaliate();
                goTo(State.TO_SPOT, spot);
            } else if (overlay != null) {
                overlay.setTaskLabel("Resting: " + s.getLifepoints() + "/" + s.getMaximumLifepoints() + " hp");
            }
        }

        /** Per-tick awareness: hits taken, deaths, being moved away. Returns true when the task ended. */
        boolean sense() {
            int hp = p.getSkills().getLifepoints();
            if (hp < lastHp) bigHit = Math.max(bigHit, lastHp - hp);
            lastHp = hp;
            Location now = p.getLocation();
            if (!p.isArtificial() && lastLoc != null && now.getDistance(lastLoc) > 30) { // bots teleport on purpose
                finish("You were moved far away (died or a random event), so I stopped.");
                return true;
            }
            lastLoc = now.transform(0, 0, 0);
            if (overlay != null && state != State.RECOVER) overlay.setTaskLabel(stopAfter > 0 ? "Collected (of " + stopAfter + "):" : "Collected:");
            return false;
        }

        void interact(Node node, String option, IntType type) {
            Option opt = null;
            for (Option o : node.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) opt = o;
            if (opt == null) return;
            if (!InteractionListeners.run(node.getId(), type, opt.getName(), p, node)) node.getInteraction().handle(p, opt);
        }

        boolean hasOption(Node node, String option) {
            if (node.getInteraction() == null) return false;
            for (Option o : node.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) return true;
            return false;
        }

        boolean nameMatches(String name) {
            if (name == null) return false;
            if (targets() == null) return true;
            for (String t : targets()) if (t.equalsIgnoreCase(name)) return true;
            return false;
        }

        /** A player (not a bot) standing next to this node and busy with it: a bot leaves it to them. */
        Player humanOn(Location node) {
            if (!p.isArtificial()) return null;
            for (Player h : core.game.world.repository.Repository.getPlayers()) {
                if (h == null || h.isArtificial() || !h.isActive() || h.getLocation().getZ() != node.getZ() || h.getLocation().getDistance(node) > 1.5) continue;
                if (h.getPulseManager().hasPulseRunning() && !h.getWalkingQueue().isMoving()) return h;
            }
            return null;
        }

        /** A player is fighting this monster: a bot leaves it to them (single or multi combat). */
        Player humanFighting(NPC n) {
            if (!p.isArtificial()) return null;
            for (Player h : core.game.world.repository.Repository.getPlayers()) {
                if (h == null || h.isArtificial() || !h.isActive() || h.getLocation().getDistance(n.getLocation()) > 12) continue;
                try { if (h.getProperties().getCombatPulse().getVictim() == n) return h; } catch (Throwable ignored) { }
            }
            return null;
        }

        long nextYieldTalk;

        void yieldTo(Player h) {
            long now = System.currentTimeMillis();
            if (now < nextYieldTalk) return;
            nextYieldTalk = now + 10 * 60_000;
            try { p.face(h); } catch (Throwable ignored) { }
            BotChat.say(p, BotChat.pick("ah all yours mate", "gl on drops", "oh, didn't see you there. carry on", "plenty more about, no worries", "you take it", "after you", "gl", "go for it mate"));
        }

        int scanSkip; // after an empty scan, the next two ticks skip it (audit S3: 30,000 long ticks a day from these loops)

        Node nearestScenery() {
            if (scanSkip > 0) { scanSkip--; return null; }
            Scenery best = null;
            double d = Double.MAX_VALUE;
            Location c = p.getLocation();
            for (int dx = -18; dx <= 18; dx++) for (int dy = -18; dy <= 18; dy++) {
                Scenery s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
                if (s == null || !s.isActive() || !nameMatches(s.getName()) || !hasOption(s, task.option)) continue;
                if (s.getLocation().getDistance(spot) > 22) continue;
                Player taken = humanOn(s.getLocation()); if (taken != null) { yieldTo(taken); continue; }
                double dist = s.getLocation().getDistance(c);
                if (task == Task.BEST_TREE) {
                    int lvl = treeLevel(s.getName());
                    if (lvl > p.getSkills().getLevel(Skills.WOODCUTTING)) continue;
                    dist -= lvl * 10; // a better tree beats a closer one
                }
                if (dist < d) { d = dist; best = s; }
            }
            if (best == null) scanSkip = 3;
            return best;
        }

        Node nearestRock() {
            if (scanSkip > 0) { scanSkip--; return null; }
            Scenery best = null;
            double d = Double.MAX_VALUE;
            Location c = p.getLocation();
            for (int dx = -16; dx <= 16; dx++) for (int dy = -16; dy <= 16; dy++) {
                Scenery s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
                if (s == null || !s.isActive()) continue;
                MiningNode m = MiningNode.forId(s.getId());
                if (m == null) continue;
                double dist = s.getLocation().getDistance(c);
                if (task == Task.BEST_ROCK) {
                    if (!has(products(), m.getReward()) || m.getLevel() > p.getSkills().getLevel(Skills.MINING)) continue;
                    dist -= m.getLevel() * 10; // a better ore beats a closer rock
                } else if (m.getReward() != products()[0]) continue;
                if (s.getLocation().getDistance(spot) > 22) continue;
                Player taken = humanOn(s.getLocation()); if (taken != null) { yieldTo(taken); continue; }
                if (dist < d) { d = dist; best = s; }
            }
            if (best == null) scanSkip = 3;
            return best;
        }

        NPC nearestNpc() {
            NPC best = null;
            double bestScore = -Double.MAX_VALUE;
            int me = myLevel();
            NPC tooStrong = null;
            for (NPC n : RegionManager.getLocalNpcs(p, 15)) {
                if (n == null || !n.isActive() || !nameMatches(n.getName()) || skipNpcs.contains(n)) continue;
                if (n.getLocation().getDistance(spot) > 18) continue;
                if (task.kind == Kind.FIGHT) {
                    if (!n.getDefinition().hasAction("attack")) continue;
                    if (!n.getProperties().isMultiZone() && n.inCombat()) continue;
                    Player fighting = humanFighting(n); if (fighting != null) { yieldTo(fighting); continue; } // theirs, even in multi
                    if (dangerOf(n) != null) { tooStrong = n; continue; }
                } else if (!hasOption(n, task.option)) continue;
                double dist = n.getLocation().getDistance(p.getLocation());
                // Auto mode prefers the toughest safe target (more xp per kill); everything else just the closest.
                double score = task == Task.AUTO ? Math.min(n.getDefinition().getCombatLevel(), me + 5) * 3 - dist : -dist;
                if (score > bestScore) { bestScore = score; best = n; }
            }
            if (best == null && tooStrong != null && ++strongSeen > 20) {
                finish("Everything here is too strong for you right now: " + dangerOf(tooStrong) + ".");
            }
            if (best != null) strongSeen = 0;
            if (skipNpcs.size() > 30) skipNpcs.clear();
            return best;
        }

        int strongSeen, chaseTicks, kills;
        final Set<NPC> skipNpcs = new HashSet<>();

        int myLevel() {
            return p.getProperties().getCurrentCombatLevel();
        }

        /** Highest damage this NPC can deal in one hit, from its own combat formula when available. */
        int maxHitOf(NPC n) {
            try {
                int h = n.getSwingHandler(false).calculateHit(n, p, 1.0);
                if (h > 0) return h;
            } catch (Throwable ignored) { }
            return 1 + n.getDefinition().getCombatLevel() / 5;
        }

        int foodCount() {
            int c = 0;
            for (Item it : p.getInventory().toArray()) if (it != null && has(FOOD, it.getId()) && !has(products(), it.getId())) c++;
            return c;
        }

        /** Why this NPC is too risky to fight, or null when it is fine. */
        String dangerOf(NPC n) {
            int lvl = n.getDefinition().getCombatLevel(), me = myLevel();
            int hit = maxHitOf(n), hp = p.getSkills().getMaximumLifepoints();
            int buffer = hp + foodCount() * 4; // roughly what food in the bag adds
            if (hit * 2 >= hp) return n.getName() + " (level " + lvl + ") can hit " + hit + " and you have " + hp + " max health";
            if (lvl > me * 2 + 10 && hit * 5 > buffer) return n.getName() + " is level " + lvl + " and you are " + me;
            return null;
        }

        // ------------------------------------------------------------------ inventory

        int countProducts() {
            int n = 0;
            for (Item it : p.getInventory().toArray()) if (it != null && isProduct(it)) n += it.getAmount();
            return n;
        }

        void dropProducts() {
            Map<Integer, Integer> budget = new HashMap<>(baseline);
            for (Item it : p.getInventory().toArray()) {
                if (it == null || !isProduct(it) || it.getId() == COINS) continue;
                if (task == Task.LURE && it.getId() == FEATHER) continue;
                int n = gainedIn(it, budget);
                if (n <= 0) continue;
                if (p.getInventory().remove(new Item(it.getId(), n))) {
                    GroundItem g = GroundItemManager.create(new Item(it.getId(), n), p.getLocation(), p);
                    if (g != null) ignored.add(g); // never pick our own drops back up
                }
            }
            lastCount = countProducts();
            if (p.getInventory().isFull()) finish("Inventory is full of things I won't drop.");
            wait = 1;
        }

        /** Splits each slot into "already had" and "collected", consuming the baseline as it goes. */
        int gainedIn(Item it, Map<Integer, Integer> budget) {
            int had = budget.getOrDefault(it.getId(), 0);
            int keepN = Math.min(had, it.getAmount());
            if (keepN > 0) budget.put(it.getId(), had - keepN);
            return it.getAmount() - keepN;
        }

        boolean isProduct(Item it) {
            if (smeltHaul && has(BARS, it.getId())) return true;
            if (chainOut != null && has(chainOut, it.getId())) return true; // batch 23 T4: the fletched / spun / smithed goods are the haul too
            return products() == null ? !keep(it) : has(products(), it.getId());
        }

        boolean keep(Item it) {
            int id = it.getId();
            if (has(task.tools, id) || has(AXES, id) || has(PICKAXES, id) || id == COINS) return true;
            if (AfkMagic.keeps(this, id)) return true; // batch 25: the spell's runes stay in the pack
            if (id == 313 && task == Task.BAIT) return true;
            if (id == FEATHER && task == Task.LURE) return true;
            if (id >= 1823 && id <= 1831 && (id & 1) == 1) return true; // combat review 10-23 #9: waterskins (full to empty) stay in the pack for the next desert trip
            if (Consumables.getConsumableById(id) != null && !has(products(), id)) return true;
            return false;
        }

        static final int[] FOOD = {385, 7946, 373, 379, 361, 351, 329, 333, 347, 325, 319, 315, 2309, 2142, 2140, 1891};

        /** Takes food from the bank (best first) so the bag holds about 8, for fighting and thieving tasks. */
        int restockFood() {
            if (task.kind != Kind.FIGHT && task.kind != Kind.THIEVE) return 0;
            int want = Math.min(8 - foodCount(), p.getInventory().freeSlots() - 4);
            int got = 0;
            for (int id : FOOD) {
                if (want <= 0) break;
                int have = p.getBank().getAmount(id);
                if (have <= 0) continue;
                int n = Math.min(have, want);
                if (p.getBank().remove(new Item(id, n))) {
                    for (int i = 0; i < n; i++) p.getInventory().add(new Item(id));
                    baseline.merge(id, n, Integer::sum); // withdrawn food is "already had", never sold
                    got += n; want -= n;
                }
            }
            if (got > 0) { foodTaken += got; p.sendMessage("<col=0b5e1f>AFK: took " + got + " food from your bank."); }
            return got;
        }

        void deposit() {
            if (p.isArtificial() && bankPause == 0) { bankPause = 1; wait = 8 + RNG.nextInt(9); return; } // stands at the booth a few seconds, like a player sorting the bank
            bankPause = 0;
            Scenery booth = null;
            Location c = p.getLocation();
            for (int dx = -7; dx <= 7 && booth == null; dx++) for (int dy = -7; dy <= 7; dy++) {
                Scenery s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
                if (s != null && s.getName() != null && (s.getName().equalsIgnoreCase("Bank booth") || s.getName().equalsIgnoreCase("Bank chest"))) { booth = s; break; }
            }
            if (booth == null) { // audit B10: try the next bank once before giving up, and say where this happened
                if (p.isArtificial() && !bankRetried) {
                    bankRetried = true;
                    Location alt = nearestBankExcept(c);
                    if (alt != null) { System.out.println("[AfkTasks] " + p.getUsername() + " found no booth at " + c + "; trying the bank at " + alt); goTo(State.TO_BANK, alt); return; }
                }
                finish("Couldn't find the bank booth at " + c + "."); return;
            }
            p.faceLocation(booth.getLocation());
            Container bankC = p.getBank();
            int moved = 0, soldItems = 0;
            long coins = 0;
            Map<Integer, Integer> budget = new HashMap<>(baseline);
            Map<Integer, Integer> haulById = new HashMap<>();
            Map<Integer, Integer> priceById = new HashMap<>(), worldById = new java.util.LinkedHashMap<>(); // runner profiling 10-23: a price lookup and a Ledger.supply per item type, not per slot (GE database work on the game thread)
            for (Item it : p.getInventory().toArray()) {
                if (it == null || keep(it)) continue;
                int n = gainedIn(it, budget);
                if (n <= 0) continue;
                Item part = new Item(it.getId(), n, it.getCharge());
                boolean sellable = !(task.kind == Kind.SMELT && !has(BARS, it.getId())) // never sell the ores we smelt from
                        && !(task.kind == Kind.BANK && !has(finalIds, it.getId())); // batch 23 T4: a bank loop sells only what it made (left-over bars, logs, raw fish go back)
                if (sell && sellable) {
                    // Sold to the GE bots at the guide price; the goods join the bots' GE stock.
                    int price = priceById.computeIfAbsent(it.getId(), Runner::guidePrice); // runner profiling 10-23
                    if (price > 0 && !keepAll && p.getInventory().remove(part)) {
                        if (p.isArtificial()) {
                            // A bot is paid when the goods sell (FIFO ledger), never at the booth: that was paying it twice.
                            // Unstackable goods arrive one item at a time here, so add them up and decide per item type below.
                            haulById.merge(it.getId(), n, Integer::sum);
                        } else {
                            worldById.merge(it.getId(), n, Integer::sum); // supplied below, once per item type; paid at the booth below; the lot credits nobody (third consultant check: double pay)
                            coins += (long) price * n;
                        }
                        soldItems += n;
                        moved++;
                        continue;
                    }
                }
                if (bankC.add(part)) { p.getInventory().remove(part); moved++; }
            }
            for (Map.Entry<Integer, Integer> w : worldById.entrySet()) { try { Ledger.supply(null, w.getKey(), w.getValue()); } catch (Throwable ignored) { } } // runner profiling 10-23
            // The whole haul per item type: offer it to whoever is in this bank first (Deals keeps it in the bank
            // meanwhile), otherwise it goes on the Exchange through the ledger.
            for (Map.Entry<Integer, Integer> h : haulById.entrySet()) {
                boolean held = false;
                // In the bank first, then the deal (audit #25: a split took goods that were never banked, so the haul was kept and paid for twice).
                Item haul = new Item(h.getKey(), h.getValue());
                boolean banked = bankC.add(haul);
                try { if (banked && Deals.arrive(p, h.getKey(), h.getValue())) held = true; } catch (Throwable t) { System.out.println("[Deals] arrive failed for " + p.getUsername() + ": " + t); t.printStackTrace(); }
                if (!held) { if (banked) bankC.remove(haul); try { Ledger.supply(p, h.getKey(), h.getValue()); } catch (Throwable ignored) { } }
            }
            if (coins > 0) {
                int amt = (int) Math.min(Integer.MAX_VALUE, coins);
                if (!bankC.add(new Item(COINS, amt))) p.getInventory().add(new Item(COINS, amt));
                soldCoins += coins;
                p.sendMessage("<col=0b5e1f>AFK: sold " + soldItems + " items for " + amt + " coins (" + soldCoins + " this session), coins are in your bank.");
                if (overlay != null) overlay.setTitle("AFK: " + label() + " (" + soldCoins + " gp)");
            }
            lastCount = countProducts();
            if (moved == 0 && p.getInventory().isFull()) { finish("Bank is full or nothing to deposit."); return; }
            if (!finishing) restockFood();
            if (finishing) { finish(goalMsg != null ? goalMsg + " Haul banked." : "Target reached and banked."); return; }
            if (task.kind == Kind.BANK && magic != null) { AfkMagic.trip(this); return; } // batch 25: R7 sizing, runes, the stops
            if (task.kind == Kind.BANK) { // batch 23 T4 (design 2.3): the bank loop's next trip
                if (!withdrawInputs()) { finish("Nothing left in your bank to " + process.word + (want.isEmpty() ? "" : " into " + want) + (made > 0 ? " (" + made + " made)." : ".")); return; }
                if (!startProcess()) { finish("Couldn't start " + process.ing + " with what came out of the bank."); return; }
                wait = 2;
                return;
            }
            if (task.kind == Kind.SMELT) {
                if (!withdrawOres()) { startMining(); return; }
                Location f = nearestFurnace();
                if (f == null) { finish("No furnace close enough to this bank."); return; }
                goTo(State.TO_FURNACE, f);
                wait = 2;
                return;
            }
            if (magic != null) { AfkMagic.topUp(this); if (halted || finishing) return; } // batch 25: runes for the next pack of ore, or the furnace takes over
            goTo(State.TO_SPOT, spot);
            wait = 2;
        }

        boolean foodTrip;

        boolean bankHasFood() {
            if (task.kind != Kind.FIGHT && task.kind != Kind.THIEVE) return false;
            for (int id : FOOD) if (p.getBank().getAmount(id) > 0) return true;
            return false;
        }

        boolean nearBooth() {
            Location c = p.getLocation();
            for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) {
                Scenery sc = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
                if (sc != null && sc.getName() != null && (sc.getName().equalsIgnoreCase("Bank booth") || sc.getName().equalsIgnoreCase("Bank chest"))) return true;
            }
            return false;
        }

        Location nearestFurnace() {
            Location best = null;
            double d = 220;
            for (Location f : FURNACES) {
                if (f.getX() == 3274 && p.getLocation().getX() < 3268) continue;
                double dist = f.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = f; }
            }
            if (!p.isArtificial()) for (Location f : extraStations(FURNACES, Artisans.Trade.SMELTING.spots, p.getLocation(), "place:furnace")) { // batch 23 T5: players only (bots keep the static table)
                if (behindToll(f)) continue;
                double dist = f.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = f; }
            }
            return best;
        }

        /** batch 23 T5: a found station behind the Al Kharid gate while the player is outside it (stationNear's box). */
        boolean behindToll(Location l) {
            return l.getX() >= 3268 && l.getY() < 3330 && l.getY() > 3100 && p.getLocation().getX() < 3268;
        }

        static final Bar[] BAR_ORDER = {Bar.ADAMANT, Bar.MITHRIL, Bar.GOLD, Bar.STEEL, Bar.SILVER, Bar.IRON, Bar.BRONZE};

        /** How many of this bar the given container's ores make. */
        int setsOf(Bar b, Container c) {
            int n = Integer.MAX_VALUE;
            for (Item ore : b.getOres()) n = Math.min(n, c.getAmount(ore.getId()) / ore.getAmount());
            return n;
        }

        /** Best bar we can smelt now (from the bag) or fetch (from the bank), respecting a chosen bar. */
        Bar bestBar(boolean fromBank) {
            int lvl = p.getSkills().getLevel(Skills.SMITHING);
            for (Bar b : BAR_ORDER) {
                if (task.kind == Kind.SMELT && targets() != null && !b.name().equals(targets()[0])) continue;
                if (b.getLevel() > lvl) continue;
                if (setsOf(b, fromBank ? p.getBank() : p.getInventory()) > 0) return b;
            }
            return null;
        }

        /** Takes a bag's worth of ores for the best bar out of the bank. */
        boolean withdrawOres() {
            Bar b = bestBar(true);
            if (b == null) return false;
            int slotsPerSet = 0;
            for (Item ore : b.getOres()) slotsPerSet += ore.getAmount();
            int sets = Math.min(p.getInventory().freeSlots() / slotsPerSet, setsOf(b, p.getBank()));
            if (sets <= 0) return false;
            for (Item ore : b.getOres()) {
                int n = ore.getAmount() * sets;
                if (!p.getBank().remove(new Item(ore.getId(), n))) return false;
                for (int i = 0; i < n; i++) p.getInventory().add(new Item(ore.getId()));
            }
            p.sendMessage("<col=0b5e1f>AFK: took ores for " + sets + " " + b.getProduct().getName().toLowerCase(Locale.ROOT) + "s.");
            return true;
        }

        /** Smithing activity: bank -> furnace -> bank, until the bank runs out of ores. */
        void smeltCycle() {
            if (busy()) return;
            if (mineBar != null) { mineForBars(); return; }
            if (bestBar(false) != null) {
                Location f = nearestFurnace();
                if (f == null) { finish("No furnace close enough; start near Edgeville, Falador or Lumbridge."); return; }
                if (f.getDistance(p.getLocation()) <= 3) { state = State.SMELTING; return; }
                goTo(State.TO_FURNACE, f);
                return;
            }
            Location b = nearestBank();
            if (b == null) { finish("No bank close enough to walk to."); return; }
            goTo(State.TO_BANK, b);
        }

        /** The bar to make by mining: the chosen one, or the best one both Smithing and Mining allow. */
        Bar barToMine() {
            return barToMine(-1);
        }

        /** Best bar both levels allow; for a mining task with smelting on, it must use the chosen ore. */
        Bar barToMine(int mustUse) {
            int smith = p.getSkills().getLevel(Skills.SMITHING), mine = p.getSkills().getLevel(Skills.MINING);
            for (Bar b : BAR_ORDER) {
                if (task.kind == Kind.SMELT && targets() != null && !b.name().equals(targets()[0])) continue;
                if (mustUse > 0) {
                    boolean uses = false;
                    for (Item ore : b.getOres()) if (ore.getId() == mustUse) uses = true;
                    if (!uses) continue;
                }
                if (b.getLevel() > smith) continue;
                boolean ok = true;
                for (Item ore : b.getOres()) if (oreLevel(ore.getId()) > mine || ORE_SPOTS.get(ore.getId()) == null) ok = false;
                if (ok) return b;
            }
            return null;
        }

        static int oreLevel(int id) {
            switch (id) {
                case 440: return 15; case 442: return 20; case 453: return 30; case 444: return 40;
                case 447: return 55; case 449: return 70; case 451: return 85; default: return 1;
            }
        }

        boolean haulPlanTold;

        /** Mining + smelting: pick the bar and how much of each ore fills the bag. False = can't smelt, just mine. */
        boolean planSmeltHaul() {
            Bar b = barToMine(task == Task.BEST_ROCK ? -1 : products()[0]);
            if (b == null) {
                smeltHaul = false;
                p.sendMessage("<col=ff0000>AFK: can't smelt " + label().toLowerCase(Locale.ROOT) + " into bars at Smithing "
                        + p.getSkills().getLevel(Skills.SMITHING) + " / Mining " + p.getSkills().getLevel(Skills.MINING) + ", so I'll just mine and bank it.");
                System.out.println("[AfkTasks] " + p.getUsername() + " smelt haul impossible for " + task.key);
                return false;
            }
            int per = 0;
            for (Item ore : b.getOres()) per += ore.getAmount();
            int free = p.getInventory().freeSlots();
            for (Item ore : b.getOres()) free += p.getInventory().getAmount(ore.getId()); // ore already carried counts
            int sets = Math.max(1, free / per);
            oreNeeds.clear();
            StringBuilder plan = new StringBuilder();
            for (Item ore : b.getOres()) {
                oreNeeds.put(ore.getId(), ore.getAmount() * sets);
                if (plan.length() > 0) plan.append(" and ");
                plan.append(ore.getAmount() * sets).append(" ").append(new Item(ore.getId()).getName().toLowerCase(Locale.ROOT));
            }
            mineBar = b;
            mineOre = -1;
            if (!haulPlanTold) {
                haulPlanTold = true;
                p.sendMessage("<col=0b5e1f>AFK: mining " + plan + " per trip, then smelting " + sets + " " + b.getProduct().getName().toLowerCase(Locale.ROOT) + "s at the nearest furnace.");
            }
            System.out.println("[AfkTasks] " + p.getUsername() + " smelt haul plan: " + plan + " -> " + b.name());
            return true;
        }

        /** Bank had nothing: work out a bag of ores in the bar's ratio and go mine it. */
        void startMining() {
            Bar b = barToMine();
            if (b == null) { finish("No ores in your bank, and your Mining/Smithing levels can't make " + (targets() == null ? "any bar" : label().toLowerCase(Locale.ROOT)) + " from scratch."); return; }
            if (!hasAny(p, PICKAXES)) { finish("No ores left in your bank and no pickaxe to mine more."); return; }
            System.out.println("[AfkTasks] " + p.getUsername() + " bank out of ore, mining for " + b.name());
            int per = 0;
            for (Item ore : b.getOres()) per += ore.getAmount();
            int sets = Math.max(1, p.getInventory().freeSlots() / per);
            oreNeeds.clear();
            StringBuilder plan = new StringBuilder();
            for (Item ore : b.getOres()) {
                oreNeeds.put(ore.getId(), ore.getAmount() * sets);
                if (plan.length() > 0) plan.append(" and ");
                plan.append(ore.getAmount() * sets).append(" ").append(new Item(ore.getId()).getName().toLowerCase(Locale.ROOT));
            }
            mineBar = b;
            mineOre = -1;
            state = State.WORK;
            p.sendMessage("<col=0b5e1f>AFK: no ores in the bank for " + b.getProduct().getName().toLowerCase(Locale.ROOT) + "s, mining " + plan + " first (" + sets + " bars).");
        }

        /** Mines whichever needed ore is furthest behind; once the bag is filled, smelt it. */
        void mineForBars() {
            int want = -1;
            double worst = 1.0;
            for (Map.Entry<Integer, Integer> e : oreNeeds.entrySet()) {
                double ratio = p.getInventory().getAmount(e.getKey()) / (double) e.getValue();
                if (ratio < worst) { worst = ratio; want = e.getKey(); }
            }
            if (want < 0 || p.getInventory().isFull()) {
                mineBar = null; mineOre = -1;
                Location f = nearestFurnace();
                if (f == null) { finish("No furnace close enough to the mine."); return; }
                p.sendMessage("<col=0b5e1f>AFK: got the ore, heading to the furnace.");
                goTo(State.TO_FURNACE, f);
                return;
            }
            if (want != mineOre) { mineOre = want; triedSpots.clear(); noTargetTicks = 0; }
            Scenery rock = nearestRockFor(want);
            if (rock == null) {
                // That ore's rocks are mined out right now: work on another ore we still need while they respawn.
                for (Map.Entry<Integer, Integer> e : oreNeeds.entrySet()) {
                    if (e.getKey() == want || p.getInventory().getAmount(e.getKey()) >= e.getValue()) continue;
                    Scenery other = nearestRockFor(e.getKey());
                    if (other != null) { rock = other; break; }
                }
            }
            if (rock == null) {
                if (++noTargetTicks > 45) { noTargetTicks = 0; relocateForOre(want); } // rocks respawn: wait a while before moving on
                return;
            }
            noTargetTicks = 0;
            last = "mining " + want + " for " + mineBar.name();
            interact(rock, "Mine", IntType.SCENERY);
            wait = 1 + RNG.nextInt(2);
        }

        Scenery nearestRockFor(int ore) {
            Scenery best = null;
            double d = Double.MAX_VALUE;
            Location c = p.getLocation();
            for (int dx = -16; dx <= 16; dx++) for (int dy = -16; dy <= 16; dy++) {
                Scenery s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
                if (s == null || !s.isActive()) continue;
                MiningNode m = MiningNode.forId(s.getId());
                if (m == null || m.getReward() != ore) continue;
                if (humanOn(s.getLocation()) != null) continue;
                double dist = s.getLocation().getDistance(c);
                if (dist < d) { d = dist; best = s; }
            }
            return best;
        }

        void relocateForOre(int ore) {
            Spot[] list = ORE_SPOTS.get(ore);
            Spot best = null;
            double d = 400;
            if (list != null) for (Spot s2 : list) {
                if (triedSpots.contains(s2)) continue;
                double dist = s2.at.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = s2; }
            }
            if (best == null && !p.isArtificial()) { // batch 23 T5: players: the surveyors' clusters of this ore (bots unchanged)
                Task rock = null;
                for (Task t : Task.values()) if (t.kind == Kind.ROCK && t != Task.BEST_ROCK && t.products != null && t.products.length == 1 && t.products[0] == ore) rock = t;
                if (rock != null) for (Spot s2 : foundSpots(rock, rock.products, p, p.getLocation(), false)) {
                    boolean tried = false;
                    for (Spot t : triedSpots) if (t.at.getDistance(s2.at) < 20) { tried = true; break; }
                    if (tried) continue;
                    double dist = s2.at.getDistance(p.getLocation());
                    if (dist < d) { d = dist; best = s2; }
                }
            }
            String name = new Item(ore).getName().toLowerCase(Locale.ROOT);
            if (best == null) { finish("Couldn't find any " + name + " rocks I can reach."); return; }
            triedSpots.add(best);
            p.sendMessage("<col=0b5e1f>AFK: heading to " + best.name + " for " + name + ".");
            spot = best.at;
            goTo(State.TO_SPOT, best.at);
        }

        /** At the furnace: smelt everything we can, then bank (and sell) the bars. */
        String processing = "smelt";

        static final Location[] RANGES = {Location.create(3211, 3215, 0), Location.create(2817, 3443, 0), Location.create(3273, 3180, 0), Location.create(2970, 3210, 0)};

        Location nearestRange() {
            Location best = null;
            double d = 400;
            for (Location r : RANGES) {
                if (r.getX() == 3273 && p.getLocation().getX() < 3268) continue;
                double dist = r.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = r; }
            }
            if (!p.isArtificial()) for (Location r : extraStations(RANGES, Artisans.Trade.COOKING.spots, p.getLocation(), "place:range", "place:cooking range")) { // batch 23 T5: players only, found ones within 220
                if (behindToll(r)) continue;
                double dist = r.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = r; }
            }
            return best;
        }

        int rawFishCount() {
            int n = 0;
            int lvl = p.getSkills().getLevel(Skills.COOKING);
            for (Item it : p.getInventory().toArray()) {
                if (it == null) continue;
                int[] c = COOK.get(it.getId());
                if (c != null && c[1] <= lvl) n += it.getAmount();
            }
            return n;
        }

        /**
         * At the range: cook everything cookable, then carry on to the bank. Bots: the old simple cook (4 fish a tick, flat
         * 30 xp, its own burn odds), unchanged. Players: the server's own cooking pulse (batch 23 T4, D5: honest xp and
         * burns), or the simple cook when that pulse can't be used (logged once).
         */
        void cook() {
            if (busy()) return;
            if (!p.isArtificial() && !cookSim) { cookReal(); return; } // batch 23 T4 (D5)
            int lvl = p.getSkills().getLevel(Skills.COOKING);
            int done = 0;
            for (Item it : p.getInventory().toArray()) {
                if (it == null || done >= 4) continue;
                int[] c = COOK.get(it.getId());
                if (c == null || c[1] > lvl) continue;
                if (p.getInventory().remove(new Item(it.getId(), 1))) {
                    // Burns like a player would: more often at low level, never past 40 over the requirement.
                    boolean burnt = RNG.nextInt(100) < Math.max(0, 35 - (lvl - c[1]));
                    if (!burnt) { p.getInventory().add(new Item(c[0])); cooked++; }
                    p.getSkills().addExperience(Skills.COOKING, 30, true);
                    done++;
                }
            }
            if (done > 0) { p.animate(new Animation(896)); last = "cooking"; wait = 1; return; }
            if (!p.isArtificial()) { afterProcess(); return; } // batch 23 T4: a player's chain delivers its own way (bank, sell or drop)
            Location bk = nearestBank();
            if (bk == null) { finish("No bank close enough to walk to."); return; }
            lastCount = countProducts();
            goTo(State.TO_BANK, bk);
        }

        int cooked;

        /** The station step: smelting here, cooking in cook(), every other process in processStep() (batch 23 T4). */
        void smelt() {
            if ("cook".equals(processing)) { cook(); return; }
            if (!"smelt".equals(processing)) { processStep(); return; } // batch 23 T4: smith, fletch, burn, spin, bury
            if (busy()) return;
            Bar b = bestBar(false);
            if (b != null) {
                int n = setsOf(b, p.getInventory());
                last = "smelting " + n + " " + b.name();
                p.getPulseManager().run(new SmeltingPulse(p, null, b, n));
                barsMade += n;
                wait = 2;
                return;
            }
            if (process == Process.SMITH && startProcess()) return; // batch 23 T4: mine, smelt, smith: the bars go to the anvil before the bank
            if (countFinal && !bank) { afterProcess(); return; }     // batch 23 T4: "ore then smelt drop"
            Location bk = nearestBank();
            if (bk == null) { finish("No bank close enough to walk to."); return; }
            goTo(State.TO_BANK, bk);
        }

        // ------------------------------------------------------------------ batch 23 T4: the chain (design 2.2-2.3)

        Scenery stationScenery;

        /** Sets the chain (players' word form and every bank loop). Keeps smeltHaul / cookHaul in step, so the old paths run SMELT and COOK. */
        void chain(Process pr, String w) {
            process = pr == null ? Process.NONE : pr;
            want = w == null ? "" : w.trim();
            smeltHaul = (process == Process.SMELT || process == Process.SMITH) && task.kind == Kind.ROCK;
            cookHaul = process == Process.COOK && task.kind == Kind.FISH;
            countFinal = !p.isArtificial() && process != Process.NONE;
            finalIds = process.consumes() ? chainIds(process, true, false) : chainIds(process, false, true);
            magic = null;
            if (process.magic()) { AfkMagic.attach(this); finalIds = new int[0]; } // batch 25: "made" = casts, counted by AfkMagic.castOne
            chainOut = process == Process.FLETCH || process == Process.SPIN || process == Process.SMITH ? chainIds(process, false, true) : null;
            if (task.kind == Kind.BANK && !process.ing.isEmpty()) {
                productsOverride = chainIds(process, true, true); // what the bank loop moves: never "kept" in the pack as food, never left behind
                labelOverride = Character.toUpperCase(process.ing.charAt(0)) + process.ing.substring(1) + (want.isEmpty() ? "" : " " + want) + " from the bank";
            }
            finalLast = countIds(finalIds);
            if (overlay != null && countFinal) {
                try { overlay.setTitle("AFK: " + label()); overlay.setTaskLabel(stopAfter > 0 ? "Made (of " + stopAfter + "):" : "Made:"); overlay.setAmount(0); } catch (Throwable ignored) { }
            }
        }

        /** The old setters' names (design 4.1 T4): the fields stay too, because the bots write them directly. */
        Runner smeltHaul(boolean on) { chain(on ? Process.SMELT : Process.NONE, want); if (!on) smeltHaul = false; return this; }
        Runner cookHaul(boolean on) { chain(on ? Process.COOK : Process.NONE, want); if (!on) cookHaul = false; return this; }

        /** The chain in the start message. */
        String chainText() {
            String d = process.consumes() ? "" : sell ? ", then selling" : !bank ? ", then dropping" : ", then banking";
            String what = want.isEmpty() ? " (the best your level allows)" : " (" + want + ")";
            if (task.kind == Kind.BANK) return "Taking from your bank, " + process.ing + what + (process.station != Station.NONE ? " at the nearest " + process.station.label : "") + d + (process == Process.BURN ? "; fires are lit where you stand now." : ".");
            return "Then " + process.ing + what + (process == Process.SMITH ? " at the anvil after smelting" : process.station != Station.NONE ? " at the nearest " + process.station.label : " on the spot") + d + ".";
        }

        int countIds(int[] list) {
            int n = 0;
            if (list != null) for (Item it : p.getInventory().toArray()) if (it != null && has(list, it.getId())) n += it.getAmount();
            return n;
        }

        /** "made" for x<count> and the overlay: the final product appearing (not while banking), or for BURN/BURY the logs and bones used up at the step. */
        void trackMade() {
            int f = countIds(finalIds), was = made;
            if (process.consumes()) { if (state == State.SMELTING && f < finalLast) made += finalLast - f; }
            else if (f > finalLast && state != State.BANKING) made += f - finalLast;
            finalLast = f;
            if (made != was && overlay != null) { try { overlay.setAmount(made); } catch (Throwable ignored) { } }
        }

        /** The recipe for what this container holds: the wanted one, else the best the process skill allows. */
        Rec pickRecFor(Process pr, Container c) {
            if (pr == Process.NONE || pr.skill < 0) return null;
            if (pr.magic()) return AfkMagic.rec(this, pr, c); // batch 25
            return bestRec(pr, want, p.getSkills().getLevel(pr.skill), null, c);
        }

        Location nearestStation(Station st) {
            switch (st) {
                case FURNACE: return nearestFurnace();
                case RANGE: return nearestRange();
                default: return stationNear(st, p.getLocation());
            }
        }

        /** Starts the process step for what the pack holds: walks to the station, or for an anywhere process starts here. False = nothing to process. */
        boolean startProcess() {
            if (pickRecFor(process, p.getInventory()) == null) return false;
            processing = process.word; procStall = 0; procLastIn = -1; stationWalks = 0; lightAt = null; lightBad = 0; lightTried.clear(); // P20
            Location at = process.station != Station.NONE ? nearestStation(process.station) : task.kind == Kind.BANK && process == Process.BURN ? spot : null;
            if (process.station != Station.NONE && at == null) { finish("No " + process.station.label + " close enough for " + process.ing + "."); return true; }
            if (at == null || at.getDistance(p.getLocation()) <= 2) { state = State.SMELTING; return true; }
            goTo(State.TO_FURNACE, at);
            return true;
        }

        /** The step is done (nothing left it can use): deliver (design 2.3). */
        void afterProcess() {
            processing = "smelt"; procStall = 0; procLastIn = -1; stationWalks = 0; stationScenery = null;
            lastCount = countProducts();
            if (task.kind == Kind.BANK || bank && !process.consumes()) {
                Location b = nearestBank();
                if (b == null) { finish("No bank close enough to walk to."); return; }
                goTo(State.TO_BANK, b);
                return;
            }
            if (!process.consumes()) { dropProducts(); if (halted) return; }
            state = State.WORK; // back to the job (work() walks back to the spot if a fire line drifted off it)
        }

        /** Bank loop (design 2.3): inputs in the pack -> process them, else -> the bank (deposit() withdraws the next trip). */
        void bankLoop() {
            if (busy()) return;
            if (startProcess()) return;
            Location b = nearestBank();
            if (b == null) { finish("No bank close enough to walk to."); return; }
            goTo(State.TO_BANK, b);
        }

        /** Takes one trip's inputs for the best recipe the bank can feed. False = the bank has none left. */
        boolean withdrawInputs() {
            Rec r = pickRecFor(process, p.getBank());
            if (r == null) return false;
            int sets = Math.min(p.getInventory().freeSlots() / Math.max(1, r.perSet()), r.setsIn(p.getBank()));
            if (sets <= 0) return false;
            for (int k = 0; k < r.in.length; k++) {
                int n = r.inAmt[k] * sets;
                if (!p.getBank().remove(new Item(r.in[k], n))) return false;
                for (int i = 0; i < n; i++) p.getInventory().add(new Item(r.in[k]));
            }
            last = "took " + sets + " sets for " + r.name;
            if (!tripTold) { tripTold = true; p.sendMessage("<col=0b5e1f>AFK: took " + sets * r.perSet() + " " + MarketBrain.name(r.in[0]).toLowerCase(Locale.ROOT) + " from your bank for " + process.ing + "."); }
            return true;
        }

        /** Counts the same input staying put across step attempts: how many attempts in a row changed nothing. */
        int stallCount(Rec r) {
            int now = p.getInventory().getAmount(r.in[0]);
            if (r.in[0] == procLastId && procLastIn >= 0 && now >= procLastIn) procStall++; else procStall = 0;
            procLastId = r.in[0]; procLastIn = now;
            return procStall;
        }

        static final String[][] STATION_NAMES = {{}, {"furnace"}, {"anvil"}, {"range", "cooking range"}, {"spinning wheel"}};

        Scenery sceneryNear(Station st, int radius) {
            Scenery best = null; double d = Double.MAX_VALUE;
            Location c = p.getLocation();
            for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) {
                Scenery s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
                if (s == null || !s.isActive() || s.getName() == null) continue;
                boolean match = false;
                for (String n : STATION_NAMES[st.ordinal()]) if (s.getName().equalsIgnoreCase(n)) match = true;
                if (!match) continue;
                double dist = s.getLocation().getDistance(c);
                if (dist < d) { d = dist; best = s; }
            }
            return best;
        }

        /** At the station: the object found and stood next to (walks closer, at most 3 times). False = not yet (or finished). */
        boolean atStation(Station st) {
            if (st == Station.NONE) return true;
            if (stationScenery != null && stationScenery.isActive() && stationScenery.getLocation().getDistance(p.getLocation()) <= 3) return true; // the one found last step
            Scenery s = sceneryNear(st, 9); // a surveyed station is a cell's best tile: the object is within its 8x8 cell
            if (s == null) { finish("No " + st.label + " found here (" + p.getLocation() + ")."); return false; }
            if (s.getLocation().getDistance(p.getLocation()) > 3) {
                if (++stationWalks > 3) { finish("Couldn't get next to the " + st.label + " at " + s.getLocation() + "."); return false; }
                goTo(State.TO_FURNACE, s.getLocation());
                return false;
            }
            stationScenery = s;
            p.faceLocation(s.getLocation());
            return true;
        }

        /** One process step (not smelting or cooking). */
        void processStep() {
            if (busy()) return;
            Process pr = Process.of(processing);
            if (pr == Process.NONE || pr == Process.COOK || pr == Process.SMELT) { processing = "smelt"; state = State.WORK; return; }
            Rec r = pickRecFor(pr, p.getInventory());
            if (r == null) { afterProcess(); return; }
            if (pr.tool > 0 && !hasAny(p, ids(pr.tool))) { finish("No " + pr.toolName().replaceFirst("^an? ", "") + " in the pack any more."); return; }
            if (!atStation(pr.station)) return;
            switch (pr) {
                case SMITH: case FLETCH: makeOne(r); return;
                case BURN: light(r); return;
                case SPIN: spin(r); return;
                case BURY:
                    if (stallCount(r) >= 3) { finish("Couldn't bury the " + r.name.toLowerCase(Locale.ROOT) + "."); return; }
                    buryOne(r.in[0]); wait = 1; return;
                case ALCH: case SUPERHEAT: AfkMagic.castOne(this, r); return; // batch 25
                default: afterProcess();
            }
        }

        /**
         * Smithing and fletching: one item per step, done by the runner (no engine pulse for these is used in the addons yet,
         * and the skill dialogues need a click). Inputs out, product in, the real per-item xp (SMITH_XP / FLETCH_XP), the
         * trade's animation, and about a real item's time: 5 ticks at the anvil, 3 with the knife.
         */
        void makeOne(Rec r) {
            for (int k = 0; k < r.in.length; k++) if (p.getInventory().getAmount(r.in[k]) < r.inAmt[k]) { afterProcess(); return; }
            for (int k = 0; k < r.in.length; k++) if (!p.getInventory().remove(new Item(r.in[k], r.inAmt[k]))) { afterProcess(); return; }
            if (!p.getInventory().add(new Item(r.product, r.amount))) {
                for (int k = 0; k < r.in.length; k++) for (int i = 0; i < r.inAmt[k]; i++) p.getInventory().add(new Item(r.in[k]));
                finish("No room in the pack for the " + r.name.toLowerCase(Locale.ROOT) + ".");
                return;
            }
            p.getSkills().addExperience(r.pr.skill, r.xp, true);
            p.animate(new Animation(r.pr == Process.SMITH ? 898 : 1248));
            last = r.pr.ing + " " + r.name;
            wait = r.pr == Process.SMITH ? 4 : 2;
        }

        /** The pack's own item (the engine pulses replace its slot), or null. */
        Item packItem(int id) {
            for (int i = 0; i < p.getInventory().capacity(); i++) { Item it = p.getInventory().get(i); if (it != null && it.getId() == id) return it; }
            return null;
        }

        /** Firemaking with the engine's FireMakingPulse (as the bots' fires, SmartAdventurers). P20 (K4): the engine drops the
         *  log, then refuses silently ("You can't light a fire here") on a tile with scenery/a fire or in a bank zone, so the
         *  tile is checked first and the player steps to a free tile 1-3 away; a light that gave no XP counts as a failure,
         *  its tile is avoided, and its dropped log is not re-looted (pickUpLoot). LIGHT_MAX bad tiles -> the job stops. */
        void light(Rec r) {
            if (p.getWalkingQueue().isMoving()) { wait = 1; return; }
            if (lightAt != null) { // the last pulse has ended: did it light?
                boolean xp = p.getSkills().getExperience(Skills.FIREMAKING) > lightXp;
                String lit2 = xp ? null : litWithoutXp(lightAt); // P32: an xp lock or the 200m cap is not a failure
                if (lit2 != null) lightNote = "lit at " + lightAt.getX() + "," + lightAt.getY() + " without xp (" + lit2 + ")";
                if (xp || lit2 != null) { litAt = lightAt; lightBad = 0; lightTried.clear(); }
                else {
                    lightNote = "no fire at " + lightAt.getX() + "," + lightAt.getY();
                    litDrops.put(lightAt, GameWorld.getTicks()); lightTried.add(lightAt); lightBad++;
                    p.sendMessage("<col=0b5e1f>AFK: the fire didn't light at " + lightAt.getX() + "," + lightAt.getY() + "; trying another spot.");
                }
                lightAt = null;
            }
            if (lightBad >= LIGHT_MAX) { finish("Couldn't light a fire on " + LIGHT_MAX + " spots here: start somewhere open (off the road, out of the bank)."); return; }
            if (stallCount(r) >= 8) { finish("Couldn't light a fire here: start somewhere open (off the road, out of the bank)."); return; }
            Item log = packItem(r.in[0]);
            if (log == null) { afterProcess(); return; }
            Location at = p.getLocation();
            String why = lightBlock(at);
            if (why != null || lightTried.contains(at)) {
                lightBad++;
                if (lightBad >= LIGHT_MAX) { finish("Couldn't light a fire on " + LIGHT_MAX + " spots here: start somewhere open (off the road, out of the bank)."); return; }
                Location to = freeLightTile(at);
                lightNote = "can't light at " + at.getX() + "," + at.getY() + " (" + (why == null ? "tried" : why) + ")" + (to == null ? ", no free tile" : " -> " + to.getX() + "," + to.getY());
                if (to == null) { finish("Couldn't light a fire here (" + (why == null ? "no fire" : why) + ", no free tile within 3): start somewhere open."); return; }
                lightTried.add(at);
                Path path = Pathfinder.find(p, to);
                if (path == null || !path.isSuccessful()) { finish("Couldn't walk to a free tile to light a fire: start somewhere open."); return; }
                path.walk(p);
                last = "stepping to " + to.getX() + "," + to.getY() + " to light";
                wait = 2;
                return;
            }
            lightXp = p.getSkills().getExperience(Skills.FIREMAKING);
            lightLog = log.getId(); lightPack = p.getInventory().getAmount(lightLog); // P32
            try { p.getPulseManager().run(new content.global.skill.firemaking.FireMakingPulse(p, log, null)); }
            catch (Throwable t) { finish("Couldn't light a fire (" + t.getClass().getSimpleName() + ")."); return; }
            lightAt = at;
            last = "lighting " + r.name + " at " + at.getX() + "," + at.getY();
            wait = 1;
        }

        /** P32 (P20 GO/NO-GO condition 2): a second "did it light" signal for a light that gave no XP (an xp lock, the 200m
         *  cap): a fire on the tile (it was free of scenery when lit), or the log left the pack and is not lying on the tile
         *  (a refusal leaves the dropped log there; an interrupted pulse leaves it in the pack). Returns which, or null.
         *  Off: -Dafk.light2=false or data/afk.light2.off (then XP alone decides, as before). */
        String litWithoutXp(Location at) {
            if (at == null || !light2()) return null;
            try {
                Scenery s = RegionManager.getObject(at);
                if (s != null && s.getName() != null && s.getName().toLowerCase(Locale.ROOT).contains("fire")) return "fire";
                if (lightLog > 0 && p.getInventory().getAmount(lightLog) < lightPack) {
                    GroundItem g = GroundItemManager.get(lightLog, at, p);
                    if (g == null || !g.isActive()) return "log gone";
                }
            } catch (Throwable t) { return null; }
            return null;
        }
        static boolean light2() {
            try { return !"false".equalsIgnoreCase(System.getProperty("afk.light2", "true")) && !new java.io.File(core.ServerConstants.DATA_PATH, "afk.light2.off").exists(); } catch (Throwable t) { return true; }
        }

        /** Why the engine's FireMakingPulse would refuse this tile (its own checks: scenery/a fire on it, a bank zone), or null. */
        String lightBlock(Location at) {
            try {
                if (p.getZoneMonitor().isInZone("bank")) return "bank";
                Scenery s = RegionManager.getObject(at);
                if (s != null) return s.getName() == null || s.getName().isEmpty() ? "scenery " + s.getId() : s.getName().toLowerCase(Locale.ROOT);
            } catch (Throwable t) { return "check failed"; }
            return null;
        }

        /** The nearest tile 1-3 away with no scenery, not tried this trip, that the player can walk onto exactly; or null. */
        Location freeLightTile(Location c) {
            for (int rad = 1; rad <= 3; rad++)
                for (int dx = -rad; dx <= rad; dx++) for (int dy = -rad; dy <= rad; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != rad) continue;
                    Location t = c.transform(dx, dy, 0);
                    if (lightTried.contains(t)) continue;
                    try { if (RegionManager.getObject(t) != null) continue; } catch (Throwable ignored) { continue; }
                    Path path = Pathfinder.find(p, t);
                    if (path == null || !path.isSuccessful() || path.getPoints().isEmpty()) continue;
                    Point end = path.getPoints().peekLast();
                    if (end.getX() == t.getX() && end.getY() == t.getY()) return t;
                }
            return null;
        }

        /** P20: a log this player dropped on a tile where the fire didn't light, within the last LIT_DROP_TICKS: not loot. */
        boolean litDrop(GroundItem g) {
            if (litDrops.isEmpty() || !g.droppedBy(p)) return false;
            Integer t = litDrops.get(g.getLocation());
            if (t == null) return false;
            if (GameWorld.getTicks() - t > LIT_DROP_TICKS) { litDrops.remove(g.getLocation()); return false; }
            return true;
        }

        /** Spinning with the engine's SpinningPulse (as the quests do); the wheel itself is checked by atStation. */
        void spin(Rec r) {
            if (stallCount(r) >= 3) { finish("The spinning wheel didn't take the " + r.name.toLowerCase(Locale.ROOT) + "."); return; }
            content.global.skill.crafting.spinning.SpinningItem si = spinningItem(r.in[0]);
            if (si == null) { finish("Spinning " + MarketBrain.name(r.in[0]).toLowerCase(Locale.ROOT) + " isn't available on this server."); return; }
            int n = p.getInventory().getAmount(r.in[0]);
            try { p.getPulseManager().run(new content.global.skill.crafting.spinning.SpinningPulse(p, new Item(r.in[0]), n, si)); }
            catch (Throwable t) { finish("Couldn't spin (" + t.getClass().getSimpleName() + ")."); return; }
            last = "spinning " + n + " " + r.name;
            wait = 2;
        }

        /** The SpinningItem for an input: WOOL is used by the quests; FLAX is looked up by name ([UNVERIFIED] constant name: null if absent). */
        static content.global.skill.crafting.spinning.SpinningItem spinningItem(int input) {
            String nm = input == 1737 ? "WOOL" : input == 1779 ? "FLAX" : null;
            if (nm == null) return null;
            try { for (content.global.skill.crafting.spinning.SpinningItem s : content.global.skill.crafting.spinning.SpinningItem.values()) if (s.name().equals(nm)) return s; } catch (Throwable ignored) { }
            return null;
        }

        /** One bone through its own "Bury" option (the engine's handler: its xp and delay), as buryBones does in fights. */
        void buryOne(int id) {
            Item it = packItem(id);
            if (it == null) return;
            Option bury = null;
            for (Option o : it.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase("bury")) bury = o;
            if (bury == null) { finish("The " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + " can't be buried."); return; }
            if (!InteractionListeners.run(it.getId(), IntType.ITEM, "bury", p, it)) it.getInteraction().handleItemOption(p, bury, p.getInventory());
            last = "burying " + MarketBrain.name(id);
        }

        /** D5: a player's cooking through the server's StandardCookingPulse at the range (reflection: no addon has called it yet). */
        void cookReal() {
            Rec r = pickRecFor(Process.COOK, p.getInventory());
            if (r == null) { afterProcess(); return; }
            if (!atStation(Station.RANGE)) return;
            if (stallCount(r) >= 3) { cookSim = true; System.out.println("[AfkTasks] " + p.getUsername() + " the server's cooking pulse made no progress on " + r.name + " at " + stationScenery + ": simple cook from now on"); return; }
            int n = p.getInventory().getAmount(r.in[0]);
            Pulse pl = cookPulse(p, stationScenery, r.in[0], r.product, n);
            if (pl == null) { cookSim = true; return; }
            p.getPulseManager().run(pl);
            last = "cooking " + n + " " + r.name;
            wait = 2;
        }

        static volatile java.lang.reflect.Constructor<?> COOK_CTOR;
        static volatile boolean cookLooked;

        /** new content.global.skill.cooking.StandardCookingPulse(player, range, raw, cooked, amount), or null (logged once) when it isn't there. */
        static Pulse cookPulse(Player p, Scenery range, int raw, int cooked, int n) {
            try {
                if (!cookLooked) {
                    cookLooked = true;
                    COOK_CTOR = Class.forName("content.global.skill.cooking.StandardCookingPulse").getConstructor(Player.class, Scenery.class, int.class, int.class, int.class);
                }
                return COOK_CTOR == null ? null : (Pulse) COOK_CTOR.newInstance(p, range, raw, cooked, n);
            } catch (Throwable t) {
                System.out.println("[AfkTasks] the server's cooking pulse can't be used (" + t + "): players get the simple cook");
                COOK_CTOR = null;
                return null;
            }
        }

        /** Nothing to do here: walk to the nearest known spot for this task that we haven't tried yet. */
        boolean waitingTold;

        void relocate() {
            Spot[] list = questSpots != null ? questSpots : SPOTS.get(task);
            Spot found = null;
            if (list == null && !p.isArtificial() && questSpots == null && !questHold) found = foundSpot(); // batch 23 T5: no SPOTS for this job: the surveyors' clusters, then HuntMap
            if (found != null) { goFound(found); return; }
            if (list == null) {
                if (p.isArtificial()) { finish(collected == 0 ? "Nothing to hunt here." : "Spot ran dry."); return; } // bots pick a new job
                // No other spot known for this task: keep waiting here for respawns instead of quitting.
                if (!waitingTold) { waitingTold = true; p.sendMessage("<col=0b5e1f>AFK: nothing here right now, waiting for respawns."); }
                return;
            }
            Spot best = null;
            double d = 400;
            if (list != null) for (Spot s2 : list) {
                if (triedSpots.contains(s2) || s2.at.getDistance(spot) < 12) continue;
                if (s2.at.getX() >= 3268 && p.getLocation().getX() < 3268 && s2.at.getY() < 3330) continue; // behind the Al Kharid gate
                double dist = s2.at.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = s2; }
            }
            if (best == null && !p.isArtificial() && questSpots == null && !questHold) best = foundSpot(); // batch 23 T5: SPOTS first, then the found ones (bots: unchanged)
            if (best == null) {
                if (questHold) { triedSpots.clear(); return; } // a quest kill waits for respawns where the quest says
                if (collected == 0) { finish("Nothing for " + label().toLowerCase(Locale.ROOT) + " here, and I've tried every spot I know nearby."); return; }
                triedSpots.clear(); // everything was busy: wait here for respawns and try the round again later
                return;
            }
            goFound(best);
        }

        void goFound(Spot best) {
            triedSpots.add(best);
            p.sendMessage("<col=0b5e1f>AFK: nothing for " + label().toLowerCase(Locale.ROOT) + " here, walking to " + best.name + ".");
            spot = best.at;
            goTo(State.TO_SPOT, best.at);
        }

        /**
         * batch 23 T5 (design 2.3 "Spot ran dry"): after SPOTS, the nearest untried Resources cluster for this job, then for a
         * fight HuntMap's spawn spot; 400 tiles. A cluster within 20 tiles of the current or a tried spot is the same place
         * (the node scans reach 22 tiles round the spot). Players only: the caller checks.
         */
        Spot foundSpot() {
            Location here = p.getLocation();
            for (int pass = 0; pass < 2; pass++) {
                Spot best = null; double d = 400;
                for (Spot s2 : foundSpots(task, products(), p, here, pass == 1)) {
                    if (spot != null && s2.at.getDistance(spot) < 20) continue;
                    boolean tried = false;
                    for (Spot t : triedSpots) if (t.at.getDistance(s2.at) < 20) { tried = true; break; }
                    if (tried) continue;
                    double dist = s2.at.getDistance(here);
                    if (dist < d) { d = dist; best = s2; }
                }
                if (best != null) return best;
            }
            return null;
        }

        boolean bankRetried;
        String tollPaid; // audit B-18: the toll area this trip has paid to enter
        Location nearestBankExcept(Location not) {
            Location best = null; double d = 220;
            for (Location b : BANKS) {
                if (b.getDistance(not) <= 12) continue;
                if (b.getX() == 3270 && p.getLocation().getX() < 3268) continue;
                if (!Gates.canEnter(p, b)) continue; // pathing review 10-23 A-3
                double dist = b.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = b; }
            }
            return best;
        }

        /** Pathing review 10-23 A-3: underground, the bank above that this bot may enter (Watchdog.nearestBank's way, gate-aware); any bank above when none is open. */
        Location bankAbove() {
            Location from = p.getLocation();
            Location surface = Location.create(from.getX(), from.getY() > 6400 ? from.getY() - 6400 : from.getY(), 0);
            Location best = null; double bd = Double.MAX_VALUE;
            for (Location b : BANKS) {
                if (!Gates.canEnter(p, b)) continue;
                double d = b.getDistance(surface);
                if (d < bd) { bd = d; best = b; }
            }
            return best != null ? best : Watchdog.nearestBank(from);
        }

        /** combat review 10-23 #3: the nearest bank at any distance that this bot may stand in (the Seers' side's nearest is inside the Fishing Guild). */
        Location nearestOpenBankAnywhere() {
            Location best = null; double d = Double.MAX_VALUE;
            for (Location b : BANKS) {
                if (b.getX() == 3270 && p.getLocation().getX() < 3268) continue; // Al Kharid: behind the toll gate
                if (!Gates.canEnter(p, b)) continue;
                double dist = b.getDistance(p.getLocation());
                if (dist < d) { d = dist; best = b; }
            }
            return best;
        }

        Location nearestBank() {
            if (essenceMine(p.getLocation())) return Location.create(3253, 3421, 0); // Varrock east, through the portal
            Location best = null;
            double d = 220;
            for (Location b : BANKS) {
                // Al Kharid's bank is behind the toll gate; only use it from inside Al Kharid.
                if (b.getX() == 3270 && p.getLocation().getX() < 3268) continue;
                if (!Gates.canEnter(p, b)) continue; // pathing review 10-23 A-3: from Karamja the nearest was Shilo's (a quest box): the flee, the bank walk and finish() all ended at its edge
                double dist = b.getDistance(p.getLocation());
                if (dist < d && !playerBankShut(b)) { d = dist; best = b; }
            }
            if (!p.isArtificial()) for (Location b : extraStations(BANKS, null, p.getLocation(), "place:bank")) { // batch 23 T5: players only; banks the surveyors saw that BANKS lacks
                if (b.getDistance(LUMBRIDGE_UPSTAIRS_BANK) < 12) continue; // Lumbridge's is upstairs: left out, as in BANKS
                if (behindToll(b)) continue; // the Al Kharid rule
                if (!Gates.canEnter(p, b)) continue;
                double dist = b.getDistance(p.getLocation());
                if (dist < d && !playerBankShut(b)) { d = dist; best = b; }
            }
            return best;
        }

        /** P17 item 3: the quest each quest gate stands for (Gates keeps it inside the rule). */
        static final Map<String, String> GATE_QUEST = new HashMap<>();
        static {
            String[][] q = {{"morytania", "PRIEST_IN_PERIL"}, {"west-ardougne", "PLAGUE_CITY"}, {"shilo", "SHILO_VILLAGE"}, {"kharazi", "LEGENDS_QUEST"},
                {"tirannwn", "REGICIDE"}, {"ape-atoll", "MONKEY_MADNESS"}, {"fremennik-isles", "THE_FREMENNIK_TRIALS"}, {"miscellania", "THE_FREMENNIK_TRIALS"},
                {"lunar-isle", "LUNAR_DIPLOMACY"}, {"trollheim", "DEATH_PLATEAU"}, {"sophanem", "ICTHLARINS_LITTLE_HELPER"}, {"mos-le-harmless", "CABIN_FEVER"},
                {"zanaris", "LOST_CITY"}, {"heroes-guild", "HEROES_QUEST"}, {"legends-guild", "LEGENDS_QUEST"}};
            for (String[] e : q) GATE_QUEST.put(e[0], e[1]);
        }

        /**
         * P17 item 3 (B2): a bank a player may not be sent to. Gates lets a player through a quest the server doesn't have
         * (Shilo Village), so from Karamja with a full pack the walk headed into the jungle for Shilo's bank instead of
         * "No bank close enough". For a player outside it: a quest gate whose quest isn't implemented, or the bank across
         * the sea or on an island/pocket the walker can't reach. Bots keep their old choice.
         */
        boolean playerBankShut(Location b) {
            if (p.isArtificial()) return false;
            Location here = p.getLocation();
            for (Gates.Gate gt : Gates.at(b)) {
                if (!"quest".equals(gt.kind) || gt.contains(here)) continue;
                String qn = GATE_QUEST.get(gt.id);
                if (qn == null) continue; // a quest-point gate: Gates' rule already decided
                try { if (!Gates.implemented(p, content.data.Quests.valueOf(qn))) return true; } catch (Throwable t) { return true; }
            }
            try { if (acrossSea(here, b) || Preflight.island(here, b) != null) return true; } catch (Throwable ignored) { }
            return false;
        }

        // ------------------------------------------------------------------ walking

        /** False when this ended the runner (finish(): a gate, a toll or a trip it can't pay for); the caller must not run it then (audit 10-23 A-11). */
        boolean goTo(State s, Location where) {
            if (s == State.TO_SPOT && SmartAdventurers.legCooled(p, this)) { finish("Won't walk this leg again for now (stuck on it lately)."); return false; } // batch 26 fix 2: a looping runner never re-chooses, so check its leg here too
            try { NavWalker.shadow(p, where, "goTo " + s); } catch (Throwable ignored) { } // -Dnav.shadow: what the planner thinks of this walk
            final Location asked = where; // batch 16 item6: where the trip carries on to once the boat lands
            boolean boatOk = !noBoat; noBoat = false; boat = null;
            state = s; dest = where; best = Double.MAX_VALUE; sinceProgress = 0; retries = 0; stallTeles = 0; // batch 22 B21-8
            walkFrom = p.getLocation(); // batch 18 item2
            viaSeen.clear(); viaSkip.clear(); doorsTried.clear(); doorAt = null; progTick = GameWorld.getTicks(); // P16 item 2
            viaSent = null; viaDone.clear(); viaAim = null; // b26 loops fix 2 / P32: vias reached count this trip only
            replanned = false; loopSince = Failures.sec(System.currentTimeMillis()); loopLast = null; // batch 23 T2: a player's loop ring counts this trip only, with one re-plan per trip
            Watchdog.ftrip(p, walkFrom); if (s == State.RETREAT) Watchdog.fdrv(p, Failures.DRV_FLEE); // batch 19a: the trip's start; a retreat is a flee (bots only, inside)
            p.getPulseManager().clear();
            underDest = null;
            boolean telePending = p.getProperties().getTeleportLocation() != null; // pathing review 10-23 A-4: flee()/recover() just paid for one (it lands next tick, so getLocation() is still the old tile)
            if (p.isArtificial() && where != null && essenceMine(p.getLocation()) && !essenceMine(where)) { // leaving through the portal to Aubury's shop
                p.getProperties().setTeleportLocation(Location.create(3253, 3398, 0));
            }
            Location[] ent = p.isArtificial() && where != null && where.getY() > 6400 && p.getLocation().getY() < 6400 ? entrance(where) : null;
            if (ent == null && p.isArtificial() && where != null && essenceMine(where) && !essenceMine(p.getLocation())) ent = new Location[]{Location.create(3253, 3401, 0), Location.create(3253, 3401, 0), where}; // Aubury teleports you in
            if (ent != null) { underDest = where; dest = where = ent[1]; if (Boolean.getBoolean("afk.debug")) System.out.println("[AfkTasks] " + p.getUsername() + " heading for the dungeon entrance at " + ent[1] + " from " + p.getLocation()); } // the owner 2026-10-20: walk in through the town, not teleport into the dungeon
            // Bots on a long trip teleport, like a player using a teleport spell; anything under ~320 tiles is walked
            // (the owner's answer to the review: players didn't burn a law rune to go Draynor to Falador).
            if (travels() && where != null && !Gates.canEnter(p, where)) { // a quest, skill or key the bot doesn't have: no going there (batch 23 T2: a player gets the same refusal as a message)
                last = "can't go there (" + Gates.missing(p, where) + ")";
                finish("Needs " + Gates.missing(p, where) + " to get there."); return !halted;
            }
            if (travels() && where != null) for (Gates.Gate gt : Gates.at(where)) // tolls are paid on the way in, like at the Shantay Pass
                if ("toll".equals(gt.kind) && !gt.contains(p.getLocation()) && !gt.id.equals(tollPaid)) { // audit B-18: once per crossing, not on every goTo of the trip
                    if (!payGate(p, 5, "toll", "desert-toll")) { tollNote(p, gt.id, 5); finish(!p.isArtificial() && capHit(p) ? capMsg(p) : "Can't pay the toll into " + gt.name + (p.isArtificial() ? "." : ": carry 5 coins in your pack.")); return !halted; } // and no free entry when broke // batch 19c (design 4.7) // batch 23 T2: a player pays from the pack
                    tollPaid = gt.id; break;
                }
            boolean acrossSalve = where != null && where.getY() < 6400 && where.getX() >= 3400 && p.getLocation().getX() < 3400; // Morytania from the west: the Salve has one crossing, teleport like a player (audit §13)
            boolean acrossSea = acrossSea(p.getLocation(), where); // batch 14: Karamja is an island; from Port Sarim the walk ended at the shore, so teleport like the Salve // batch 17 item3: the test shared with shop walks
            if (!p.isArtificial() && travels() && where != null) { // batch 23 T2 (D3): no teleport or boat for a player: refuse a walk into the sea or onto an island
                if (acrossSea) { last = "across the sea"; finish("That's across the sea. Take the boat (Port Sarim or Brimhaven) yourself, then Start again."); return !halted; }
                String isl = ent == null ? Preflight.island(p.getLocation(), where) : null;
                if (isl != null) { last = "can't walk there (" + isl + ")"; finish("Can't walk there: it's " + isl + "."); return !halted; }
            }
            if (acrossSea) acrossSalve = true;
            boolean leavingMory = Preflight.ON && p.isArtificial() && Preflight.leavingMorytania(p.getLocation(), where); // batch 20 A2: west out of Morytania (its surface is its own grid piece: "no route", 157 records at Canifis and Mort Myre)
            boolean offStart = Preflight.ON && p.isArtificial() && where != null && ent == null && Preflight.offGrid(p.getLocation()) && !Preflight.offGrid(where); // batch 20 A3: up from a dungeon or down from a floor before a surface walk
            String strandedWhy = p.isArtificial() && where != null && ent == null ? Preflight.stranded(p, where) : null; // batch 21: an island, a pocket, inside the Warriors' Guild under-level
            boolean mustTele = leavingMory || offStart || strandedWhy != null;
            if (acrossSea && boatOk && p.isArtificial() && !telePending && ent == null && Ledger.Wallet.coins(p) >= BOAT_FARE) { // batch 16 item6: near the docks, take the boat like a player (30 gp, no teleport charge)
                boolean out = karamja(where);
                Location board = out ? QuestPiratesTreasure.SEAMEN_AT : QuestPiratesTreasure.CUSTOMS_AT;
                Location landAt = out ? BOAT_LAND_KARAMJA : BOAT_LAND_SARIM;
                boolean farOn = where.getDistance(landAt) > TELEPORT_MIN; // batch 16 review: bound far past the landing, the teleport straight away as batch 14 did (it paid 30 gp for the boat and then the teleport too)
                boolean fareFits = p.getInventory().getAmount(995) >= BOAT_FARE || p.getInventory().freeSlots() > 0; // batch 16 review: pocketCoins can't move the fare into a full pack (the crossing failed after a dead walk)
                if (!farOn && fareFits && board.getDistance(p.getLocation()) <= BOAT_NEAR && (out || QuestPiratesTreasure.onKaramja(p))) {
                    boat = new Boat(out, asked, s, board);
                    dest = board; last = "walking to the boat";
                    return !halted;
                }
            }
            if (p.isArtificial() && !telePending && (!walker || acrossSalve || mustTele) && where != null && (where.getDistance(p.getLocation()) > TELEPORT_MIN || acrossSalve || mustTele)) { // pathing review 10-23 A-4: not a second charge (and landing) on top of a teleport already paid for // across the Salve even a walker pays (payTeleport decides)
                // A teleport costs what a player pays: a law rune and three airs (coin sink A6). No coins, no teleport.
                Location land = ent != null ? ent[0] : Watchdog.landing(where); // never straight into a fenced-off spot
                if (Ledger.Wallet.payTeleport(p, acrossSea ? "sea-crossing" : "long-teleport")) { Spectacle.teleport(p, land); last = "teleported"; } // batch 15 item1
                else if (acrossSea && karamja(p.getLocation()) && homeTeleport(p)) { last = "home teleport (no fare)"; return !halted; } // batch 16 item7: broke on Karamja, the free home teleport like a 2009 player (a bot stuck there failed every mainland job, 104 times in a morning)
                else if (acrossSea) { tollNote(p, "boat-karamja", BOAT_FARE); finish("Can't afford the trip across the sea."); return !halted; } // no walking into the sea // batch 19c (design 4.7)
                else if (strandedWhy != null) { finish("Can't get out: " + strandedWhy + ", and no coins for a teleport."); return !halted; } // batch 21
                else if (leavingMory) { tollNote(p, "salve", Preflight.fare()); finish("Can't afford the trip west out of Morytania."); return !halted; } // batch 20 A2: no walking to the Salve from the east either
                else if (acrossSalve) { tollNote(p, "salve", BOAT_FARE); finish("Can't afford the trip east."); return !halted; } // no walking to the Salve (audit 10-22 09:50) // batch 19c [Fable]: need = the boat fare figure (payTeleport's own charge is runes)
                else last = "walking (no teleport money)";
            }
            return !halted;
        }

        /**
         * Dungeon entrances a 2009 player used: {where the teleport lands, the ladder/trapdoor on the surface, the foot of
         * it underground}. Taverley dungeon via the Falador teleport and the walk west past the bank; Edgeville dungeon via
         * the trapdoor by the bank; the Karamja volcano via Brimhaven. Spots in other dungeons keep the direct teleport.
         */
        /** Karamja's surface (Brimhaven up to its docks, then the rest of the island south of the north coast); Crandor and Rimmington are outside it. */
        static final Location MAINLAND_REF = Location.create(3222, 3218, 0); // batch 21: Lumbridge, a tile of the mainland region

        static boolean karamja(Location l) {
            if (!karamjaBox(l)) return false;
            // batch 21 (map verification): the box's east leg (x to 3000, y to 3185) also covers 729 mainland tiles (Mudskipper
            // Point, where the old snape spot 2970,3182 was a water tile 6 tiles off the mainland; it now sits at the real spawns, 2906,3296): a Musa Point
            // bot sent there walked at the sea. Once the planner is up, a tile whose nearest standable tile is in the mainland's
            // region is not Karamja.
            if (NavPlanner.built) try {
                Location s = NavPlanner.standNear(l), m = NavPlanner.standNear(MAINLAND_REF);
                int rm = m == null ? 0 : NavPlanner.regionAt(m);
                if (s != null && rm != 0 && NavPlanner.regionAt(s) == rm) return false;
            } catch (Throwable ignored) { }
            return true;
        }

        static boolean karamjaBox(Location l) {
            if (l == null || l.getZ() != 0 || l.getY() >= 6400) return false;
            int x = l.getX(), y = l.getY();
            return y >= 2880 && (x >= 2740 && x < 2815 && y <= 3235 || x >= 2815 && x < 2880 && y <= 3205 || x >= 2815 && x <= 3000 && y <= 3185); // batch 17 item3: the north coast east of Brimhaven (2815-2879, up to 3205; Crandor starts past 3215) was "not Karamja": a shop walk there turned back for a second crossing
        }

        /** Both on the surface, one on Karamja and the other not: no walking there (batch 14). */
        static boolean acrossSea(Location from, Location to) {
            return from != null && to != null && to.getZ() == 0 && to.getY() < 6400 && from.getZ() == 0 && from.getY() < 6400 && karamja(to) != karamja(from);
        }

        // ------------------------------------------------------------------ batch 17 item3: the crossing for a walk that isn't a trip

        boolean crossOnly; // batch 17 item3: only crossing the sea for a shop walk: finish() ends it without the trip's bank walk, haul sale or summary
        volatile boolean crossing; // batch 17 item3: its per-tick pulse is still driving the walk to the boat and the crossing

        /**
         * batch 17 item3: a smart bot's shop walk across the sea gets a trip's crossing, through goTo() with all its guards:
         * the boat when near the docks and able to pay (30 gp), else the paid teleport, else the free home teleport off
         * Karamja; when none of those is possible the returned runner is halted ("Can't afford the trip across the sea.")
         * and the caller gives the walk up. While the boat is used a 1-tick pulse drives walk() (boarding, the dialogue,
         * the landing, the fallback to the teleport) and clears {@code crossing} once the bot is on the far side, the
         * runner halted, {@code still} says the walk is off, or 300 ticks have passed. The runner is never in RUNNING.
         */
        static Runner crossSea(Player p, Location to, java.util.function.BooleanSupplier still) {
            Runner r = new Runner(p, Task.NET, false, 0, false, false);
            r.crossOnly = true;
            r.goTo(State.TO_SPOT, to);
            if (r.halted || r.boat == null) return r; // a teleport (lands next tick), the home teleport, or can't cross
            r.crossing = true;
            GameWorld.getPulser().submit(new Pulse(1, p) {
                int n;
                @Override public boolean pulse() {
                    boolean done = r.halted || !p.isActive() || ++n > 300 || !still.getAsBoolean() || r.boat == null && !acrossSea(p.getLocation(), to) && p.getProperties().getTeleportLocation() == null;
                    if (!done) { try { r.walk(State.WORK); } catch (Throwable t) { System.out.println("[AfkTasks] " + p.getUsername() + " sea crossing: " + t); done = true; } }
                    if (!done && r.state == State.WORK) done = true; // the runner's walk ended at the shop itself
                    if (done) r.crossing = false;
                    return done;
                }
                @Override public void stop() { super.stop(); r.crossing = false; }
            });
            return r;
        }

        // ------------------------------------------------------------------ batch 16 item6: the Karamja boat

        static final int BOAT_FARE = 30, BOAT_NEAR = 120, BOAT_LAND_TICKS = 100; // gp; tiles from the boarding spot; 60 s from reaching it to landing
        static final Location BOAT_LAND_KARAMJA = Location.create(2956, 3146, 0), BOAT_LAND_SARIM = Location.create(3029, 3217, 0); // batch 16 review: where each crossing puts the bot ashore
        /** A crossing in progress: the walk to the boarding spot, then the Pirate's Treasure crossing steps, then the trip resumes. */
        static final class Boat {
            final boolean out; final Location fin, board; final State st;
            final java.util.List<QuestSteps.Step> steps; QuestRunner qr;
            int idx = -1, boardTick; long coinsAtBoard; boolean landed;
            Boat(boolean out, Location fin, State st, Location board) { this.out = out; this.fin = fin; this.st = st; this.board = board; this.steps = out ? QuestPiratesTreasure.boatToKaramja() : QuestPiratesTreasure.boatToPortSarim(); }
        }
        Boat boat;
        boolean noBoat; // the boat failed: the next goTo takes the batch 14 teleport

        boolean boatLanded(Boat b) { return b.out ? QuestPiratesTreasure.onKaramja(p) : !QuestPiratesTreasure.onKaramja(p); }

        /** One tick of the crossing; false while the bot is still walking to the boarding spot (the ordinary walk goes on). */
        boolean boatTick() {
            Boat b = boat;
            Location here = p.getLocation();
            if (b.idx < 0) {
                if (here.getZ() != 0 || here.getDistance(b.board) > 4) return false;
                b.qr = new QuestRunner(p, QuestPiratesTreasure.BOAT, 0, false);
                b.idx = 0; b.boardTick = GameWorld.getTicks(); b.coinsAtBoard = Ledger.Wallet.coins(p);
                p.getPulseManager().clear(); p.getWalkingQueue().reset();
                b.steps.get(0).begin(b.qr);
                last = "boarding the boat";
                return true;
            }
            if (!b.landed && boatLanded(b)) {
                b.landed = true;
                long spent = b.coinsAtBoard - Ledger.Wallet.coins(p);
                if (spent > 0) Ledger.PAID += spent; // the fare leaves the game, as the teleport's coins did
                if (p.isArtificial()) CoinWatch.paid(p, "boat"); // batch 22-1: boat fares count as paid actions (CoinWatch)
                System.out.println("[AfkTasks] " + p.getUsername() + " took the boat to " + (b.out ? "Karamja" : "Port Sarim") + " (" + spent + " gp) bound for " + b.fin);
                last = "crossed by boat";
            }
            if (!b.landed && GameWorld.getTicks() - b.boardTick > BOAT_LAND_TICKS) { boatFallback("not across after 60 s"); return true; }
            QuestSteps.St r;
            QuestSteps.Step st = b.steps.get(b.idx);
            try { r = st.checkpoint(p) ? QuestSteps.St.DONE : st.tick(b.qr); } catch (Throwable t) { r = QuestSteps.St.FAILED; st.failure = String.valueOf(t); }
            if (r == QuestSteps.St.RUNNING) return true;
            try { st.end(b.qr); } catch (Throwable ignored) { }
            if (r == QuestSteps.St.FAILED && !b.landed) { boatFallback(st.name + ": " + st.failure); return true; }
            if (r == QuestSteps.St.DONE && ++b.idx < b.steps.size()) { b.steps.get(b.idx).begin(b.qr); return true; }
            // across (or a step after the landing failed): off the deck if still on it, and on with the trip
            QuestSteps.clearPopups(p);
            if (QuestPiratesTreasure.onDeck(p) && !p.getLocks().isMovementLocked()) p.getProperties().setTeleportLocation(Location.create(2956, 3146, 0)); // where the gangplank puts you (PortsObjectPlugin)
            boat = null;
            goTo(b.st, b.fin);
            return true;
        }

        void boatFallback(String why) {
            Boat b = boat;
            try { if (b.idx >= 0 && b.idx < b.steps.size()) b.steps.get(b.idx).end(b.qr); } catch (Throwable ignored) { }
            QuestSteps.clearPopups(p);
            System.out.println("[AfkTasks] " + p.getUsername() + " no boat (" + why + "): the teleport instead");
            boat = null; noBoat = true;
            goTo(b.st, b.fin);
        }

        /** batch 16 item7: the free Lumbridge home teleport, once per 30 minutes per bot as in 2009; false while on cooldown. */
        static final Map<String, Long> HOME_TELE = new java.util.concurrent.ConcurrentHashMap<>();
        static boolean homeTeleport(Player p) {
            long now = System.currentTimeMillis();
            Long at = HOME_TELE.get(p.getUsername().toLowerCase());
            if (at != null && now - at < 30 * 60_000L) return false;
            HOME_TELE.put(p.getUsername().toLowerCase(), now);
            Spectacle.teleport(p, Location.create(3222, 3218, 0));
            System.out.println("[AfkTasks] " + p.getUsername() + " used the home teleport off Karamja (no fare)");
            return true;
        }

        static boolean essenceMine(Location l) { return l != null && l.getY() >= 4800 && l.getY() <= 4870 && l.getX() >= 2880 && l.getX() <= 2940; }

        static Location[] entrance(Location under) {
            int x = under.getX(), y = under.getY();
            if (x >= 2800 && x <= 2970 && y >= 9660 && y <= 9900)
                return new Location[]{Location.create(2965, 3380, 0), Location.create(2884, 3396, 0), Location.create(2884, 9798, 0)};
            if (x >= 3080 && x <= 3160 && y >= 9820 && y <= 9990)
                return new Location[]{Location.create(3087, 3496, 0), Location.create(3095, 3468, 0), Location.create(3096, 9867, 0)};
            if (x >= 2820 && x <= 2880 && y >= 9540 && y <= 9600)
                return new Location[]{Location.create(2800, 3180, 0), Location.create(2856, 3168, 0), Location.create(2857, 9569, 0)};
            return null;
        }

        void walk(State next) {
            if (boat != null && boatTick()) return; // batch 16 item6
            if (REACH_MYRE && Gates.swampGatePass(p)) { last = "through the Mort Myre gate"; return; } // P39 R1: the warning the gate opened for a bot that has started Nature Spirit
            double dist = p.getLocation().getDistance(dest);
            if (dist <= 2 && underDest != null) { // at the ladder or trapdoor: climb down like a player, then on to the spot
                Location[] ent = entrance(underDest);
                Watchdog.fdrv(p, Failures.DRV_CLIMBER); // batch 19a design 3.1: the entrance climb
                try { p.getAnimator().animate(new core.game.world.update.flag.context.Animation(827)); } catch (Throwable ignored) { }
                p.getProperties().setTeleportLocation(ent != null ? ent[2] : underDest);
                dest = underDest; underDest = null; best = Double.MAX_VALUE; sinceProgress = 0; retries = 0; last = "climbed down";
                System.out.println("[AfkTasks] " + p.getUsername() + " walked to the dungeon entrance at " + p.getLocation() + " and climbed down");
                return;
            }
            if (dist <= 2) {
                state = next;
                return;
            }
            Location now = p.getLocation();
            if (next != State.RECOVER && !p.isArtificial() && travels() && playerLoop(now)) return; // batch 23 T2: going round in circles
            if (RouteMap.usingShortcut(p)) { // climbing through a tunnel or over a wall
                // Pathing review 10-23 R-2: only nextHop settles a shortcut (out at the far side, or its 12 s up), and this
                // returned before step() could call it, so the walk waited for ever once over. Ask it here; give up waiting after 15 s.
                if (shortcutSince == 0) shortcutSince = System.currentTimeMillis();
                try { RouteMap.nextHop(p, dest); } catch (Throwable ignored) { }
                if (RouteMap.usingShortcut(p) && System.currentTimeMillis() - shortcutSince < 15_000) { sinceProgress = 0; progTick = GameWorld.getTicks(); last = "using a shortcut"; return; }
            }
            shortcutSince = 0;
            boolean onRoute = RouteMap.PLANS.containsKey(p.getUsername().toLowerCase());
            if (onRoute && routeLast != null && now.getDistance(routeLast) >= 1) { sinceProgress = 0; routeLast = now; if (dist < best - 0.5) progTick = GameWorld.getTicks(); best = Math.min(best, dist); } // P17 item 1: route movement alone is not progress for the player stuck rule (a circling walk reset it for ever)
            else if (dist < best - 0.5) { best = dist; sinceProgress = 0; routeLast = now; progTick = GameWorld.getTicks(); }
            else if (next != State.RECOVER && !p.isArtificial() && travels() && GameWorld.getTicks() - progTick > PLAYER_STUCK_TICKS) { // P16 item 2c: players say stuck within ~200 ticks (was two stages of ~410)
                progTick = GameWorld.getTicks(); playerStuck("no progress"); return;
            }
            else if (next == State.RECOVER && sinceProgress > 6 && !attackers().isEmpty()) {
                dest = escapePoint(); best = Double.MAX_VALUE; sinceProgress = 0; last = "new escape";
                return;
            }
            else if (++sinceProgress > 40) {
                try { RouteMap.stuck(p, dest); } catch (Throwable ignored) { } // the route map learns this way didn't work
                sinceProgress = 0;
                if (++retries > 4) {
                    if (next == State.RECOVER) { state = State.RECOVER; return; } // rest where we are
                    if (!p.isArtificial() && travels()) { playerStuck("no progress"); return; } // batch 23 T2: a player gets one re-plan, then a message; never a teleport
                    long c0 = Ledger.Wallet.coins(p);
                    if (p.isArtificial() && stallTeles < STALL_TELES_MAX && Ledger.Wallet.payTeleport(p)) { // bots take the teleport a player would (paid, audit #16), instead of giving up // batch 22 B21-8: at most STALL_TELES_MAX per goTo
                        stallTeles++;
                        Location land = Watchdog.landing(dest);
                        System.out.println("[AfkTasks] " + p.getUsername() + " stuck at " + p.getLocation() + ", paid stall teleport " + stallTeles + "/" + STALL_TELES_MAX + " (" + (c0 - Ledger.Wallet.coins(p)) + " gp) to " + land + " for " + dest); // batch 22 B21-8: was afk.debug only, with no fare
                        Spectacle.teleport(p, land); p.getPulseManager().clear(); // batch 15 item1
                        best = Double.MAX_VALUE; retries = 0; sinceProgress = 0; last = "teleported";
                        return;
                    }
                    if (p.isArtificial()) Watchdog.scenario(p, "failed_trip", "afk", walkFrom, dest, p.getLocation(), "no progress after 5 tries walking " + (next == State.WORK ? "to the spot" : "to the " + next.name().toLowerCase(Locale.ROOT)) + ", " + (int) dist + " tiles short, last '" + last + "'"); // batch 18 item2
                    try { SmartAdventurers.legFailed(p, this, "couldn't find a path"); } catch (Throwable ignored) { } // batch 26 fix 2
                    finish("Couldn't find a path " + (next == State.WORK ? "back to the spot." : "to the bank.")); return;
                }
                api.randomWalkTo(p.getLocation(), 6); // shake loose from whatever is in the way
                return;
            }
            if (!p.getWalkingQueue().isMoving() && !p.getPulseManager().hasPulseRunning()) step(dist);
        }

        /**
         * batch 23 T2: a player's loop detector, the bots' rule (Failures.loopStat: LOOP_VISITS entries of one tile in 5 minutes
         * of this trip, spanning LOOP_SPAN tiles or more) over this runner's own ring. Counted on entering a new tile only.
         * Writes no Failures or Watchdog records. True when it acted (re-planned or finished).
         */
        boolean playerLoop(Location now) {
            if (!Failures.LOOP_ON || now == null || now.equals(loopLast)) return false;
            loopLast = now;
            if (loopT == null) { loopT = new int[128]; loopS = new int[128]; }
            int pk = Failures.pack(now), s = Failures.sec(System.currentTimeMillis());
            long st = Failures.loopStat(loopT, loopS, loopH, pk, s, loopSince);
            loopT[loopH] = pk; loopS[loopH] = s; loopH = (loopH + 1) & 127;
            if (!Failures.loopRescue((int) (st >>> 32), (int) st)) return false;
            playerStuck("going round in circles");
            return true;
        }

        /** batch 23 T2: a stuck player's walk: forget the plan and plan once more from here; the second time, stop with a message. Never a teleport. */
        void playerStuck(String why) {
            Location h = p.getLocation();
            if (!replanned) {
                replanned = true;
                try { NavWalker.forget(p); RouteMap.PLANS.remove(key(p)); RouteMap.USING.remove(key(p)); } catch (Throwable ignored) { }
                if (loopT != null) { java.util.Arrays.fill(loopT, 0); java.util.Arrays.fill(loopS, 0); }
                loopSince = Failures.sec(System.currentTimeMillis()); loopLast = null;
                best = Double.MAX_VALUE; sinceProgress = 0; retries = 0; last = "re-planning (" + why + ")";
                return;
            }
            last = "stuck (" + why + ")";
            finish("Stuck at " + h.getX() + "," + h.getY() + ": try walking a few tiles and Start again.");
        }

        /** Walks a leg of up to 18 tiles that gets closer to dest, fanning out sideways around obstacles. */
        /** Walk towards the next waypoint of the learned route (RouteMap) when there is one; else straight at dest. */
        /** At the Al Kharid gate with the destination on the other side: pay the toll and step through (audit B10). */
        boolean tollGate() {
            if (!travels() || dest == null) return false; // batch 23 T2: players in travel mode too
            int r = tollHop(p, dest);
            if (r == 0) return false;
            if (r < 0 && !p.isArtificial() && capHit(p)) { finish(capMsg(p)); return true; } // batch 23 pre-review 2.1: the spend cap stops the run, no walk round the fence
            if (r < 0) { // review fix 2: broke at the gate: round the fence's north end as a non-payer (NavWalker.step below plans without the toll under 10 gp)
                if (!NavPlanner.TOLL_ON || tollStuck(p, dest)) { finish(p.isArtificial() ? "Can't pay the Al Kharid toll." : "Can't pay the Al Kharid toll: carry 10 coins in your pack."); return true; }
                last = "can't pay the toll: round the fence"; return false;
            }
            best = Double.MAX_VALUE; sinceProgress = 0; last = "paid the toll";
            return true;
        }

        /**
         * review fix 2: a bot that can't pay at the Al Kharid gate (tollHop -1) walks round the fence's north end instead of
         * giving up. True (give up) only when the planner is off or finds no toll-free route from here.
         */
        static boolean tollStuck(Player p, Location dest) {
            if (!NavWalker.ON || !NavPlanner.built || p == null || dest == null) return true;
            TOLL_CALLS.incrementAndGet();
            final Location here = p.getLocation();
            final int agility = NavWalker.agility(p), band = Heat.band(p);
            final boolean memo = TOLL_CACHE && here != null; final int g = NavPlanner.gen;
            final long mk = memo ? tollKey(here, dest, band, agility) : 0L;
            final String who = memo ? key(p) : null;
            if (memo) {
                TollMemo m = TOLL_MEMO.get(who);
                if (m != null && m.key == mk && m.gen == g && m.until > System.currentTimeMillis()) { TOLL_HITS.incrementAndGet(); return m.stuck; }
            }
            TOLL_PLANS.incrementAndGet();
            boolean stuck;
            try { stuck = NavPlanner.plan(here, dest, agility, band, false) == null; } catch (Throwable t) { stuck = true; }
            if (memo && g == NavPlanner.gen && NavPlanner.built) { // never keep an answer from a graph that was swapped during the plan
                long now = System.currentTimeMillis();
                if (TOLL_MEMO.size() > 2_000) TOLL_MEMO.values().removeIf(x -> x.until <= now);
                TOLL_MEMO.put(who, new TollMemo(mk, g, now + TOLL_TTL, stuck));
            }
            return stuck;
        }

        /**
         * Audit B21-6: tollStuck ran a full toll-free plan (corridor + a tile search of up to 20,000 steps) on every call,
         * and its callers call it on every idle tick a broke bot spends in the gate box. Its answer is now kept per bot for
         * TOLL_TTL ms while the graph, the destination's 8x8 cell, the bot's 4x4 cell, its combat band and agility are the
         * same. One entry per bot; NavWalker.forget (a trip's finish, a toll hop, a detour) drops it.
         * -Dnav.tollcache=false: plan on every call (the batch 21 way); -Dnav.tollcacheMs=N sets the time (default 4000).
         */
        static volatile boolean TOLL_CACHE = !"false".equals(System.getProperty("nav.tollcache"));
        static final long TOLL_TTL = Math.max(0L, Long.getLong("nav.tollcacheMs", 4_000L));
        static final class TollMemo {
            final long key, until; final int gen; final boolean stuck;
            TollMemo(long key, int gen, long until, boolean stuck) { this.key = key; this.gen = gen; this.until = until; this.stuck = stuck; }
        }
        static final Map<String, TollMemo> TOLL_MEMO = new java.util.concurrent.ConcurrentHashMap<>();
        static final java.util.concurrent.atomic.AtomicLong TOLL_CALLS = new java.util.concurrent.atomic.AtomicLong(), TOLL_PLANS = new java.util.concurrent.atomic.AtomicLong(), TOLL_HITS = new java.util.concurrent.atomic.AtomicLong(); // B21-6: counts (TestB21D)
        static void tollForget(Player p) { if (p != null) TOLL_MEMO.remove(key(p)); }
        static long tollKey(Location here, Location dest, int band, int agility) {
            return ((dest.getX() >> 3) & 0xFFFL) | (((dest.getY() >> 3) & 0xFFFL) << 12) | (((here.getX() >> 2) & 0xFFFL) << 24) | (((here.getY() >> 2) & 0xFFFL) << 36)
                | ((band & 3L) << 48) | ((agility & 0x7FL) << 50) | ((dest.getZ() & 3L) << 57) | ((here.getZ() & 3L) << 59);
        }

        /** review fix 2: under 10 gp with the planner on, the Al Kharid exit rule (to the gate) is skipped: the planner walks round the fence. */
        static boolean cantPayToll(Player who) {
            return who != null && NavPlanner.TOLL_ON && NavWalker.ON && NavPlanner.built && gateCoins(who) < 10; // batch 23 T2: gateCoins = Ledger.Wallet.coins for bots
        }

        /** batch 21 B2: at the Shantay Pass with the destination across it: through (passHop). */
        boolean passGate() {
            if (!travels() || dest == null) return false; // batch 23 T2: players in travel mode too
            int r = passHop(p, dest, "shantay-desert".equals(tollPaid));
            if (r == 0) return false;
            if (r < 0) { finish(!p.isArtificial() && capHit(p) ? capMsg(p) : p.isArtificial() ? "Can't pay for the Shantay Pass." : "Can't pay for the Shantay Pass: carry 5 coins in your pack."); return true; }
            best = Double.MAX_VALUE; sinceProgress = 0; last = "through the Shantay Pass";
            return true;
        }

        /** batch 19c (design 4.7 toll-unaffordable, Builder B's file for A's code): a bot that could not pay a gate or a crossing. Logging only; A's economy throttle keeps it to one per bot per window. */
        static void tollNote(Player p, String gate, long need) {
            if (!Watchdog.FAIL_ON || !Watchdog.traced(p)) return;
            try { Failures.Econ.toll(p, gate, need); } catch (Throwable ignored) { } // A's helper: one per bot per gate per hour
        }

        /** At the Al Kharid gate with the destination beyond it: pays 10 gp and steps through. 1 = done, 0 = not at the gate or not crossing, -1 = can't pay. */
        static int tollHop(Player p, Location dest) {
            Location here = p.getLocation();
            if (dest == null || here.getZ() != 0 || Math.abs(here.getX() - 3268) > 4 || here.getY() < 3224 || here.getY() > 3231) return 0;
            boolean eastward = dest.getX() >= 3268 && here.getX() <= 3267, westward = dest.getX() <= 3267 && here.getX() >= 3268; // batch 21 B2: x 3268 is the east side (the fence is its west edge): a bot standing there bound east paid and hopped again, and a goal at x 3268 had no side
            if (!eastward && !westward) return 0;
            if (!payGate(p, 10, "toll", "alkharid-toll")) { tollNote(p, "alkharid", 10); return -1; } // batch 19c (design 4.7): logged only // batch 23 T2: payGate = Ledger.Wallet.pay for bots, the pack for a player
            p.getProperties().setTeleportLocation(Location.create(eastward ? 3270 : 3266, 3227, 0)); // the 10 gp are gone from the game, as at the real gate
            try { NavWalker.forget(p); } catch (Throwable ignored) { } // batch 21 B2: plan again from the far side (the old route's door hop at the gate sent the bot back to x 3268, where it paid and hopped east again, round and round)
            if (!p.isArtificial()) p.sendMessage("You pay 10 coins and pass through the toll gate."); // batch 23 T2: a player is never made to speak
            else if (RNG.nextInt(3) == 0) BotChat.say(p, BotChat.pick("Ten coins. Daylight robbery.", "There's your toll.", "Ten gp to walk through a gate. Honestly."));
            return 1;
        }

        /**
         * batch 21 B2: the Shantay Pass (3302-3306, 3116) is a solid object in the grid, so the desert is a nav region of its
         * own (86,910 tiles on live's grid) and the planner has no way in: live bots bound for the Ugthanki (3268,3052) stood
         * at the Al Kharid toll gate (the EXITS rule) or walked off to the void by Yanille. A walk across the pass now goes to
         * the pass first (passVia) and through it (passHop): 5 gp going south, as at the real pass (a Runner that paid the
         * desert toll at launch is not charged twice), free going north. -Dnav.shantay=false turns both off.
         */
        static final boolean PASS_ON = !"false".equals(System.getProperty("nav.shantay"));
        static final Location PASS_N = Location.create(3304, 3117, 0), PASS_S = Location.create(3304, 3115, 0);
        static final int PASS_FARE = 5;

        /** Is l on the desert side of the Shantay Pass? The desert's nav region when the grid has it apart, else the gate box. */
        static boolean desertSide(Location l) {
            if (l == null || l.getZ() != 0 || l.getY() > 6400) return false;
            try {
                if (NavPlanner.built) {
                    int rd = NavPlanner.regionAt(PASS_S), rn = NavPlanner.regionAt(PASS_N);
                    if (rd != 0 && rn != 0 && rd != rn) { Location s = NavPlanner.standNear(l); if (s != null) return NavPlanner.regionAt(s) == rd; }
                }
            } catch (Throwable ignored) { }
            return Gates.desert(l);
        }

        /** Are nav regions r1 and r2 the two sides of the Shantay Pass (for Preflight's island and stranded rules)? */
        static boolean passJoins(int r1, int r2) {
            if (!PASS_ON || r1 == 0 || r2 == 0 || r1 == r2) return false;
            int rd = NavPlanner.regionAt(PASS_S), rn = NavPlanner.regionAt(PASS_N);
            return rd != 0 && rn != 0 && rd != rn && (r1 == rd && r2 == rn || r1 == rn && r2 == rd);
        }

        /** The pass tile on this side when from and to are on opposite sides of the Shantay Pass (walk there first), else null. */
        static Location passVia(Location from, Location to) {
            if (!PASS_ON || from == null || to == null || from.getZ() != 0 || to.getZ() != 0 || from.getY() > 6400 || to.getY() > 6400) return null;
            boolean a = desertSide(from), b = desertSide(to);
            if (a == b) return null;
            return a ? PASS_S : PASS_N;
        }

        /** At the Shantay Pass and crossing it: through (5 gp south unless paid; free north). 1 = done, 0 = not here or not crossing, -1 = can't pay. */
        static int passHop(Player p, Location dest, boolean paid) {
            if (!PASS_ON || p == null || dest == null) return 0;
            Location here = p.getLocation();
            if (here.getZ() != 0 || Math.abs(here.getX() - 3304) > 3 || here.getY() < 3112 || here.getY() > 3120) return 0;
            Location via = passVia(here, dest);
            if (via == null || here.getDistance(via) > 3) return 0;
            boolean south = via == PASS_N;
            if (south && !paid && !payGate(p, PASS_FARE, "pass", "shantay-pass")) { tollNote(p, "shantay", PASS_FARE); return -1; } // batch 23 T2: payGate (bots unchanged)
            p.getProperties().setTeleportLocation(south ? PASS_S : PASS_N);
            try { NavWalker.forget(p); } catch (Throwable ignored) { } // plan again from the far side
            return 1;
        }

        /** Rivers a straight line walks into: the River Lum south of the Lumbridge bridge (second audit: 61 failed Bob's Axes trips in 37 min from the east bank). */
        static final int[][] RIVERS = {{3236, 3246, 3150, 3222, 3242, 3226}, {3236, 3246, 3230, 3300, 3242, 3226}}; // x0 x1 y0 y1 -> bridge x y
        /** Walled places with one way out: inside the box and bound elsewhere, go to the exit first. x0 y0 x1 y1 -> exit x y */
        static final int[][] EXITS = {{3268, 3140, 3330, 3330, 3270, 3227},   // Al Kharid: the toll gate (then tollGate() pays and steps through)
                                      {3200, 3205, 3237, 3232, 3242, 3218},   // Lumbridge castle courtyard: out over the east drawbridge
                                      {3238, 3208, 3250, 3228, 3245, 3240}};  // just outside the drawbridge, bound west: round the north side of the castle first
        /** Places a weak bot walks round: x0 y0 x1 y1 -> via x y, below this combat. */
        // The Draynor jail guards (ids 447-449, 917; level 26, aggressive) stand at 3114-3127 x 3238-3250 and wander up to 6 tiles.
        // The first box (10-21) was drawn south-west of the village and missed them: six shop-walk deaths at 3092-3126 x 3213-3255 in 24 h (audit §13).
        static final int[][] AVOID = {{3106, 3230, 3135, 3258, 3102, 3226, 55, 3138, 3226}}; // under combat 55: round the south-west corner, then the bank from the south; [7],[8] = via 2 (b26 loops fix 2: along the south edge, east of the box)
        /** b26 loops fix 2 kill switch: false = one AVOID via, re-sent whenever > 3 tiles from it (the Draynor jail ping-pong). */
        static final boolean AVOID2 = !"false".equals(System.getProperty("nav.avoid2"));
        static boolean avoidVia(Location v) { for (int[] a : AVOID) if ((v.getX() == a[4] && v.getY() == a[5]) || (a.length > 8 && v.getX() == a[7] && v.getY() == a[8])) return true; return false; }
        static Location bridgeVia(Location from, Location to) { return bridgeVia(null, from, to); }
        static Location bridgeVia(Player who, Location from, Location to) { return bridgeVia(who, from, to, null); } // shop walks, scenarios, quests: no per-trip memory (old behaviour)
        /** done: the Runner's per-trip memory of reached vias (b26 loops fix 2: AVOID vias while AVOID2; P32: Lumbridge vias while BRIDGE_FIX), else null. */
        static Location bridgeVia(Player who, Location from, Location to, java.util.Set<Location> done) {
            if (from == null || to == null || from.getZ() != 0 || to.getZ() != 0) return null;
            for (int[] b : EXITS) {
                boolean in = from.getX() >= b[0] && from.getX() <= b[2] && from.getY() >= b[1] && from.getY() <= b[3];
                boolean out = !(to.getX() >= b[0] && to.getX() <= b[2] && to.getY() >= b[1] && to.getY() <= b[3]);
                // Pathing review 10-23 A-5: bound south of the castle (Bob's Axes 3228,3203) from east of its east wall, the lane
                // between the wall and the river (x 3232-3240) runs straight down to it. The two Lumbridge rules sent such a bot
                // north round the castle, its next hop south-west landed in the courtyard box, that box sent it back out east, and
                // so on for ever (148 "never reached Bob's" in 3 days).
                if (REACH_BRIDGE && b[0] == 3238 && from.getY() >= 3225 && to.getY() <= 3232) continue; // P39 R3 (west/south-west goals only: north-bound walks keep the round-the-north via, farm pair 11-07): on the bridge deck (y 3225-3226) or the lane's north end: no via (it sent westbound walks back to the east bank: the 3245,3239 <-> 3242,3226 loop); y 3208-3224 keep the rule
                if ((b[0] == 3238 || (b[0] == 3200 && from.getX() >= 3232)) && to.getY() < 3205 && to.getX() >= 3220) continue; // only toward Bob's: a south-west goal (the Wizards' Tower) still goes round (batch 12 soak: 2 quest walks timed out in the courtyard without this)
                if (b[0] == 3238 && !(to.getX() < 3238 || to.getY() > 3232)) continue; // the drawbridge rule: anything west or north goes round the north side first (south-west too: the straight line cuts through the castle)
                // P32 (TestB16H leg A, BRIDGE_FIX only): the row-3 via 3245,3240 is on the EAST bank north of the bridge, and the box takes
                // in the bridge deck, so a westbound walk already crossing was turned round, walked back over the bridge, and looped.
                // Westbound: go to NORTH_W on the west bank once per trip (from the box widened to the bridge's east end), then walk straight.
                if (BRIDGE_FIX && done != null && b[0] == 3238 && to.getX() < 3238) { // done == null (shop walks, scenarios, quests): old rule
                    boolean inBox = from.getX() >= 3238 && from.getX() <= 3252 && from.getY() >= 3208 && from.getY() <= 3229;
                    if (inBox && !done.contains(NORTH_W) && from.getDistance(NORTH_W) > 3) return NORTH_W;
                    continue; // past it, or reached this trip: the straight walk (its 49-tile leg clears the castle from here)
                }
                if (BRIDGE_FIX && done != null && (b[0] == 3200 || b[0] == 3238) && done.contains(Location.create(b[4], b[5], 0))) continue; // P32: a Lumbridge exit reached this trip is not re-picked (Al Kharid untouched)
                if (b[0] == 3268 && b[1] == 3140 && cantPayToll(who)) continue; // review fix 2: can't pay: not sent to the gate (the planner goes round the fence's north end)
                if (b[0] == 3268 && b[1] == 3140 && to.getX() >= 3268 && to.getY() < 3140) continue; // batch 21 B2: south of Al Kharid (the Shantay Pass, the desert) is walked, not reached through the toll gate (bots bound for 3268,3052 stood at the gate, where tollHop has no side for x 3268)
                if (in && out && from.getDistance(Location.create(b[4], b[5], 0)) > 3) return Location.create(b[4], b[5], 0);
            }
            if (who != null) {
                int cb = 99; try { cb = who.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
                for (int[] a : AVOID) {
                    if (cb >= a[6]) continue;
                    if (AVOID2 && done != null && a.length > 8) { // b26 loops fix 2: via 1 reached this trip -> via 2; via 2 reached -> this box is done (no more ping-pong back to via 1)
                        if (done.contains(Location.create(a[7], a[8], 0))) continue;
                        if (done.contains(Location.create(a[4], a[5], 0))) { if (crosses(from, to, a[0], a[1], a[2], a[3]) && from.getDistance(Location.create(a[7], a[8], 0)) > 3) return Location.create(a[7], a[8], 0); continue; }
                    }
                    if (crosses(from, to, a[0], a[1], a[2], a[3]) && from.getDistance(Location.create(a[4], a[5], 0)) > 3) return Location.create(a[4], a[5], 0);
                }
            }
            if (who != null && !who.isArtificial()) { // P17 (players; bots keep their old rules): the Al Kharid fence (x 3267, y 3140-3330) seen from outside
                Location target = riverVia(from, to); if (target == null) target = to;
                // P17 item 2 (G1/K1): north of the fence's end and bound west of it, a straight line that meets the fence ran down its
                // east side to the toll gate ("Can't pay the Al Kharid toll"); go round the north end (3262-3270,3331 is free) first.
                if (from.getX() >= 3268 && from.getY() > 3330 && target.getX() < 3268 && fenceY(from, target) <= 3330 && from.getDistance(FENCE_N) > 3) return FENCE_N;
                // P19b (G1/K1 planner off): past the north end, the RIVERS bridge via (3242,3226) sent the walk south down the east bank,
                // and the long leg ran down x 3266 (3266,3284 is on the Al Kharid side: there the fence is the cow field's east side,
                // west of x 3267.5) to 3270,3241 and the gate. Bound west of the river, go round the river's north end (y 3300) first.
                if (from.getX() > 3246 && from.getX() <= 3290 && from.getY() > 3300 && from.getY() <= 3340 && to.getX() < 3236 && riverVia(from, to) != null && from.getDistance(WEST_N) > 3) return WEST_N;
                // P17 item 1 (F2): bound into Al Kharid from west of the fence, the walk had no via and circled at 3264,3202; to the free
                // tile 2 west of the gate (inside tollHop's window), where tollGate() pays and hops. A non-payer keeps the old walk.
                boolean toBox = to.getX() >= 3268 && to.getX() <= 3330 && to.getY() >= 3140 && to.getY() <= 3330;
                if (from.getX() < 3268 && toBox && !cantPayToll(who)) {
                    int fy = fenceY(from, to);
                    if (fy >= 3140 && fy <= 3330 && from.getDistance(TOLL_W) > 3) { Location rv = riverVia(from, TOLL_W); return rv != null ? rv : TOLL_W; }
                }
            }
            return riverVia(from, to);
        }

        /** P17: the free tile 2 west of the Al Kharid toll gate (tollHop's window) and the free tile past the fence's north end. */
        static final Location TOLL_W = Location.create(3265, 3227, 0), FENCE_N = Location.create(3265, 3331, 0);
        /** P19b: west of the River Lum, past its north end (RIVERS y1 3300): the way to Lumbridge from the fence's north end. */
        static final Location WEST_N = Location.create(3232, 3305, 0);
        /** P32 (BRIDGE_FIX only): west bank of the Lum, north of the castle's north-east corner: where a westbound walk from the
         *  drawbridge or the bridge goes first. Not yet checked standable on the map (spare PC; fallbacks 3235,3238 / 3230,3242). */
        static final Location NORTH_W = Location.create(3233, 3240, 0);

        /** P17: where the straight line a -> b crosses the Al Kharid fence line (x 3267.5); Integer.MAX_VALUE when it does not cross it. */
        static int fenceY(Location a, Location b) {
            if ((a.getX() < 3268) == (b.getX() < 3268)) return Integer.MAX_VALUE;
            double t = (3267.5 - a.getX()) / (double) (b.getX() - a.getX());
            return (int) Math.round(a.getY() + t * (b.getY() - a.getY()));
        }

        /** The RIVERS rule on its own (P17: also used for the fence rules' legs). */
        static Location riverVia(Location from, Location to) {
            for (int[] r : RIVERS) {
                boolean fromWest = from.getX() < r[0], toWest = to.getX() < r[0], fromEast = from.getX() > r[1], toEast = to.getX() > r[1];
                if (!((fromWest && toEast) || (fromEast && toWest))) continue;
                // where the straight line meets the river band
                double t = ((r[0] + r[1]) / 2.0 - from.getX()) / (double) (to.getX() - from.getX());
                int y = (int) Math.round(from.getY() + t * (to.getY() - from.getY()));
                if (y >= r[2] && y <= r[3]) return Location.create(r[4], r[5], 0);
            }
            return null;
        }

        /** Does the straight line from a to b pass through the box? (sampled every 4 tiles) */
        static boolean crosses(Location a, Location b, int x0, int y0, int x1, int y1) {
            double d = a.getDistance(b); int n = Math.max(1, (int) (d / 4));
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n; int x = (int) Math.round(a.getX() + t * (b.getX() - a.getX())), y = (int) Math.round(a.getY() + t * (b.getY() - a.getY()));
                if (x >= x0 && x <= x1 && y >= y0 && y <= y1) return true;
            }
            return false;
        }

        /**
         * Pathing review 10-23 A-7: the walk from the Falador teleport (2965,3380) to the Taverley-dungeon ladder (2884,3396).
         * Falador's west wall (x 2935-2937, y 3330-3388) has no way through (only the crumbling-wall shortcut), so the straight
         * walk and the learned route both stuck against it at 2937-2945 x 3373-3385 (13 Watchdog rescues on 10-22/23). A
         * player leaves by the north gate, goes up the road to the Taverley gate and walks south through Taverley.
         */
        Location taverleyVia() {
            if (underDest == null || dest == null || dest.getX() != 2884 || dest.getY() != 3396) return null; // only on the way to that ladder
            Location h = p.getLocation();
            if (h.getZ() != 0) return null;
            int x = h.getX(), y = h.getY();
            if (x >= 2938 && x <= 2975 && y >= 3330 && y <= 3395) return Location.create(2964, 3398, 0); // inside Falador: out of the north gate
            if (x >= 2937 && x <= 2975 && y >= 3396 && y <= 3455 && h.getDistance(Location.create(2937, 3450, 0)) > 3) return Location.create(2937, 3450, 0); // north of it: up the road to the Taverley gate
            if (x >= 2934 && x <= 2941 && y >= 3445 && y <= 3455) { // at the gate: open it like a player, then through into Taverley
                Scenery gate = null; double gd = 5;
                for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) {
                    Scenery sc = RegionManager.getObject(0, x + dx, y + dy);
                    if (sc == null || sc.getName() == null || !sc.getName().toLowerCase(Locale.ROOT).contains("gate") || !hasOption(sc, "Open")) continue;
                    if (sc.getLocation().getDistance(h) < gd) { gd = sc.getLocation().getDistance(h); gate = sc; }
                }
                if (gate != null && !p.getPulseManager().hasPulseRunning()) { last = "opening the Taverley gate"; interact(gate, "Open", IntType.SCENERY); return h; }
                return Location.create(2930, 3448, 0);
            }
            return null;
        }

        void step(double dist) {
            if (tollGate()) return; // at the Al Kharid gate: pay and hop first, whichever planner chose the way
            if (passGate()) return; // batch 21 B2: at the Shantay Pass, crossing: through
            Location pv = travels() ? passVia(p.getLocation(), dest) : null; // batch 23 T2: travels() (bots, and players in travel mode)
            if (pv != null) { Location real = dest; dest = pv; try { step(Math.max(1, p.getLocation().getDistance(pv))); } finally { dest = real; } return; } // batch 21 B2: to the pass first (same side, so no deeper)
            if (NavWalker.ON && travels()) { // the real planner first (the design notes); the old rules below stay as the fallback until it measures better
                Location nav = NavWalker.step(p, dest, p.isArtificial() || gateCoins(p) >= 10); // batch 21 A: this walker pays the toll (tollGate above) // batch 23 T2: a player only with 10 coins in the pack
                if (nav != null) { Location real = dest; dest = nav; try { stepTo(Math.max(1, p.getLocation().getDistance(nav))); last = "nav: " + last; } finally { dest = real; } return; }
            }
            Location via = travels() ? taverleyVia() : null;
            if (via != null && via.equals(p.getLocation())) return; // pathing review 10-23 A-7: the gate click is under way
            if (BRIDGE_FIX && viaAim != null && p.getLocation().getDistance(viaAim) <= 3) { viaDone.add(viaAim); viaAim = null; } // P32: a via this trip aimed at and came within 3 of is reached (bridgeVia never returns a via within 3, so it is recorded here)
            if (AVOID2 && viaSent != null && p.getLocation().getDistance(viaSent) <= 3) viaDone.add(viaSent); // b26 loops fix 2: the AVOID via it was sent to is reached
            if (via == null && travels()) { via = bridgeVia(p, p.getLocation(), dest, (AVOID2 || BRIDGE_FIX) ? viaDone : null); if (AVOID2 && via != null && avoidVia(via)) viaSent = via; }
            if (via != null && !p.isArtificial() && !viaGood(via)) via = null; // P16 item 2a: a via leg that stopped closing in (circling it, or ping-pong between two) is dropped for this goTo
            if (via != null && p.getLocation().getDistance(via) > 3) { try { RouteMap.PLANS.remove(p.getUsername().toLowerCase(Locale.ROOT)); NavWalker.forget(p); } catch (Throwable ignored) { } if (BRIDGE_FIX) viaAim = via; Location real = dest; dest = via; // batch 17 item9: plan again from where the detour ends (the old plan sent it back east: Draynor jail ping-pong)
                try { stepTo(p.getLocation().getDistance(via)); last = "to the bridge: " + last; } finally { dest = real; } return; }
            Location real = dest;
            Location hop = null;
            try { hop = RouteMap.nextHop(p, dest); } catch (Throwable ignored) { }
            // Pathing review 10-23 R-2: nextHop just clicked the route's shortcut (it returns the bot's own tile while one is
            // in use). stepTo(0) divided by zero, fell through to the door scan or a random walk, and either replaced the click.
            if (hop != null && (hop.equals(p.getLocation()) || RouteMap.usingShortcut(p))) { sinceProgress = 0; last = "using a shortcut"; return; }
            if (hop == null || hop.equals(real)) { Watchdog.fdrv(p, Failures.DRV_OLD); stepTo(dist); return; } // batch 19a design 3.1: the old straight walk
            dest = hop;
            try { stepTo(p.getLocation().getDistance(hop)); last = "route: " + last; } finally { dest = real; }
        }

        /**
         * Runner profiling 10-23: Pathfinder.find is about 90% of the runner's cost (each search clears and floods a 104x104
         * grid), and a bot boxed in by a fence or a river ran all 11 searches below in one pulse, again on every call while it
         * was stuck: the live "Long running pulse - AfkTasks$Runner" lines. Now at most STEP_FINDS searches a call; the next
         * call from the same tile towards the same point carries on down the same list. When nothing worked from a tile, the
         * next calls from that tile (for 30 ticks) go straight to the door / shake-loose part instead of repeating them.
         */
        static final int STEP_FINDS = 4;
        Location stepFrom, stepFor, stepFailAt, stepFailFor; // runner profiling 10-23
        int stepNext, stepFailTick;

        /** P16 item 2a (players): true while the walk still closes in on this via; after VIA_TICKS without a new best distance it is skipped for the rest of the goTo. */
        boolean viaGood(Location via) {
            if (viaSkip.contains(via)) return false;
            int t = GameWorld.getTicks(), d10 = (int) (p.getLocation().getDistance(via) * 10);
            int[] v = viaSeen.get(via);
            if (v == null || d10 < v[0] - 5) { viaSeen.put(via, new int[]{d10, t}); return true; }
            if (t - v[1] <= VIA_TICKS) return true;
            viaSkip.add(via); last = "gave up on the via " + via.getX() + "," + via.getY();
            try { RouteMap.PLANS.remove(key(p)); NavWalker.forget(p); } catch (Throwable ignored) { }
            return false;
        }

        void stepTo(double dist) {
            Location c = p.getLocation();
            double ux = (dest.getX() - c.getX()) / dist, uy = (dest.getY() - c.getY()) / dist;
            // runner profiling 10-23: where this call starts in the list (0, 1 long legs; 2-10 the fan; 11 the door part)
            int from = c.equals(stepFrom) && dest.equals(stepFor) ? stepNext : 0;
            if (from == 0 && c.equals(stepFailAt) && dest.equals(stepFailFor) && GameWorld.getTicks() - stepFailTick < 30) from = 11;
            stepFrom = null; stepNext = 0;
            int idx = 0, finds = 0;
            // First try a long leg: the pathfinder routes around fences and rivers when it can see the whole way.
            int tried = -1;
            for (int far : new int[]{49, 40}) { // pathing review 10-23 A-8: was 60, past the engine's 50-tile search limit (max_pathfind_dist), so that leg always failed
                if (dist <= 18) break;
                int l2 = (int) Math.min(dist, far);
                if (l2 == tried) { idx++; continue; } // within 40 both legs aim at dest: one search is enough
                tried = l2;
                if (idx++ < from) continue; // runner profiling 10-23: searched on an earlier call
                if (finds++ >= STEP_FINDS) { stepLater(c, idx - 1); return; }
                Location t = dist <= far ? dest : c.transform((int) Math.round(ux * l2), (int) Math.round(uy * l2), 0);
                Path path = Pathfinder.find(p, t);
                if (path == null || !path.isSuccessful() || path.getPoints().isEmpty()) continue;
                Point end = path.getPoints().peekLast();
                double nd = Math.hypot(end.getX() - dest.getX(), end.getY() - dest.getY());
                if (nd < dist - 8) { path.walk(p); last = "long leg to " + end.getX() + "," + end.getY(); return; }
            }
            int len = (int) Math.min(dist, 18);
            idx = 2;
            for (int a : new int[]{0, 25, -25, 50, -50, 80, -80, 115, -115}) {
                if (idx++ < from) continue; // runner profiling 10-23
                if (finds++ >= STEP_FINDS) { stepLater(c, idx - 1); return; }
                double r = Math.toRadians(a + (RNG.nextInt(11) - 5));
                double vx = ux * Math.cos(r) - uy * Math.sin(r), vy = ux * Math.sin(r) + uy * Math.cos(r);
                Location t = dist <= 18 && a == 0 ? dest : c.transform((int) Math.round(vx * len), (int) Math.round(vy * len), 0);
                Path path = Pathfinder.find(p, t);
                if (path == null || !path.isSuccessful() || path.getPoints().isEmpty()) continue;
                Point end = path.getPoints().peekLast();
                double nd = Math.hypot(end.getX() - dest.getX(), end.getY() - dest.getY());
                if (nd < dist - 2 || (dist <= 18 && nd <= 2)) { path.walk(p); last = "step " + a + " to " + end.getX() + "," + end.getY(); return; }
            }
            if (from < 11) { stepFailAt = c; stepFailFor = dest; stepFailTick = GameWorld.getTicks(); } // runner profiling 10-23: every search from here failed
            // Boxed in (pen, building): open the nearest closed gate or door and try again next tick.
            Scenery door = null;
            double dd = 25;
            for (int dx = -24; dx <= 24; dx++) for (int dy = -24; dy <= 24; dy++) {
                Scenery sc = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
                if (sc == null || sc.getName() == null || !hasOption(sc, "Open")) continue;
                String nm = sc.getName().toLowerCase(Locale.ROOT);
                if (!nm.contains("gate") && !nm.contains("door")) continue;
                if (!p.isArtificial() && doorsTried.contains(sc.getLocation())) continue; // P16 item 2b: tried, no way opened
                double di = sc.getLocation().getDistance(c);
                if (di < dd) { dd = di; door = sc; }
            }
            if (!p.isArtificial() && travels()) { // P16 item 2b: one door for DOOR_TICKS, then it is marked tried and the player is told stuck
                int t = GameWorld.getTicks();
                if (door != null && !door.getLocation().equals(doorAt)) { if (doorAt != null) doorsTried.add(doorAt); doorAt = door.getLocation(); doorTick = t; } // a new door: the last one is done with
                if (door != null && door.getLocation().getDistance(c) > 2.5) doorTick = t; // P17 item 4 (K5): DOOR_TICKS count from standing at the door (2.5: a double gate's other leaf), not from choosing it up to 24 tiles away; the no-progress rule bounds a door it never reaches
                if (door != null && t - doorTick > DOOR_TICKS) { doorsTried.add(doorAt); doorAt = null; playerStuck("no way through the door"); return; } // the 100-tick no-progress rule bounds a run of doors
            }
            if (door != null) { last = "opening " + door.getName(); interact(door, "Open", IntType.SCENERY); return; }
            last = "no step found";
            api.randomWalkTo(c, 5);
        }

        /** Runner profiling 10-23: this call's searches are used up; the next call from c towards dest starts at candidate i. */
        void stepLater(Location c, int i) {
            stepFrom = c; stepFor = dest; stepNext = i;
            last = "still looking for a way";
        }
    }
}
