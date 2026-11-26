package content.custom;

/**
 * W8 wave 5 polish (the design notes section 5). Every item has its own -D flag, default OFF; with a
 * flag off its caller runs train-61's code unchanged (same branches, same RNG draws).
 *
 *   -Dbots.foodLadder    a fighter's trip food climbs a ladder by purse and Hitpoints level (lobster, swordfish, monkfish,
 *                        shark), bought only from bot stock through Ledger.consume (sellers paid), never above a price
 *                        ceiling of CEIL_X times the item's reference price
 *   -Dbots.wc.power      a low-level woodcutter (under 45) chops willows in drop mode while willow logs are in glut
 *   -Dbots.fish.power    a low-level fisher (under 40) fly-fishes the Barbarian Village river in drop mode while trout and
 *                        salmon are in glut; both reuse mine.power's drop-and-vanish (no ground item) and skip Bob.store
 *   -Dbots.weaponByFoe   a melee bot on a hunt wears the weapon class the foe is weak to: a mace (crush) for skeletons,
 *                        rock crabs, ankous and the like, else its scimitar (slash); the mace is its second weapon class,
 *                        bought once (bot stock first, else a shop that sells it) under a spend cap
 *   -Dbots.kitAboveRune  a melee bot at the GE buys one piece above rune per visit (granite 50, dragon 60, Barrows 70) from bot
 *                        stock only (Ledger.consume: never a shop, never created), under a per-piece share and a per-bot cap;
 *                        about 1 bot in 12 is a low-Defence build that never trains Defence on a melee trip
 *   -Dbots.kit.amulets   a bot at the GE wears the best amulet for its style from bot stock (one per visit, a purse share)
 *   -Dbots.ranged.melee  about 1 melee bot in 5 takes every third low hunt as a ranged trip: a bow and arrows it owns or buys
 *                        (bot stock, else a shop), its melee weapon back in the bank, then back on at the next trip
 *   -Dbots.magic.enchant a mage at the GE enchants unenchanted amulets from bot stock (sapphire 7, emerald 27, ruby 49, diamond
 *                        57): amulets and runes paid (Ledger.consume / shop), the enchanted amulets go on the Exchange as its
 *                        stock; server-wide hourly cap
 *   -Dbots.summoning.heal  only with -Dbots.summoning and -Dbots.summoning.combat on: a Summoning 68+ bot on a dragon hunt takes a
 *                        bunyip (the engine's BunyipNPC heals its owner every 25 ticks) as the trip's familiar, from its bank or
 *                        bought from bot stock; Bob.combatSummon summons it in single-combat too
 *   -Dbots.farming.trees (needs -Dbots.farming): RULES ONLY in this wave (no in-world hook yet, see the W8 notes): the tree and
 *                        fruit-tree seed pick (bot stock or the bot's own drops, never a shop), the farmer's payment, and the
 *                        next-day health check time, kept inside FarmSave's file rules (no format change)
 *
 * This class holds only the flags, the pure rules and the counters, so plain java can test it without a world
 * (TestW8Polish). Nothing here touches a server class.
 */
final class W8Polish {
    private W8Polish() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }
    static double dbl(String k, double d) { try { String v = System.getProperty(k); return v == null ? d : Double.parseDouble(v.trim()); } catch (Throwable t) { return d; } }

    // ------------------------------------------------------------------ 1 Hitpoints: bots.foodLadder
    static final boolean FOOD_LADDER = flag("bots.foodLadder");
    /** {food id, heals, Hitpoints level from which a bot reaches for it}, cheapest rung first. */
    static final int[][] LADDER = {{379, 12, 1}, {373, 14, 40}, {7946, 16, 55}, {385, 20, 70}};
    /** Ceiling = this times the reference price (PriceDrift base); above it the bot leaves the food to players. */
    static final double FOOD_CEIL_X = Math.max(1.0, dbl("bots.foodLadder.ceilX", 1.5));
    /** The trip's food may cost at most purse / FOOD_PURSE_DIV (a per-bot spend cap; buyFood's own rule is a quarter). */
    static final int FOOD_PURSE_DIV = Math.max(4, Integer.getInteger("bots.foodLadder.purseDiv", 5));
    /** Bot stock left for players after a buy (never empty the Exchange). */
    static final int FOOD_STOCK_LEFT = 50;
    /** Ladder only for fights that want real food (npc level 30+: goblins keep trout). */
    static final int FOOD_MIN_NPC = 30;
    static volatile int ladderBuys, ladderFood, ladderCeil, ladderNoStock, ladderPoor;
    static final int[] ladderRung = new int[LADDER.length];

    /** Ceiling gp per item: CEIL_X x the reference when there is one (> 0), else 0 (= no ceiling known: no ladder buy). */
    static int foodCeil(Integer ref) { return ref != null && ref > 0 ? (int) Math.max(1, Math.round(ref * FOOD_CEIL_X)) : 0; }

    /**
     * The rung to buy: the best one this Hitpoints level reaches whose `want` costs at most the purse share, whose price is
     * under its ceiling and whose bot stock keeps FOOD_STOCK_LEFT; walks down the ladder; -1 = none (buyFood carries on as
     * before). price/ceil/stock are per rung. Tallies why a rung was refused into why[0..2] = ceiling, stock, purse.
     */
    static int rung(int hpLevel, int npcLevel, long purse, int want, int[] price, int[] ceil, int[] stock, int[] why) {
        if (npcLevel < FOOD_MIN_NPC || want <= 0) return -1;
        for (int k = LADDER.length - 1; k >= 0; k--) {
            if (hpLevel < LADDER[k][2]) continue;
            if (ceil[k] <= 0 || price[k] > ceil[k]) { if (why != null) why[0]++; continue; }
            if (stock[k] < want + FOOD_STOCK_LEFT) { if (why != null) why[1]++; continue; }
            if ((long) Math.max(1, price[k]) * want > purse / FOOD_PURSE_DIV) { if (why != null) why[2]++; continue; }
            return k;
        }
        return -1;
    }
    static int ladderIndex(int id) { for (int k = 0; k < LADDER.length; k++) if (LADDER[k][0] == id) return k; return -1; }

    // ------------------------------------------------------------------ 2 Woodcutting, Fishing: bots.wc.power, bots.fish.power
    static final boolean WC_POWER = flag("bots.wc.power"), FISH_POWER = flag("bots.fish.power");
    static final int WC_POWER_MAX = 45, FISH_POWER_MAX = 40; // maples at 45; lobsters (cage) at 40
    static final double POWER_WEIGHT = 0.3; // as W5a's mine.power: a power trip only wins when little else pays
    static final int KIND_WC = 1, KIND_FISH = 2;
    static volatile int wcPowerStarts, wcPowerDropped, fishPowerStarts, fishPowerDropped;
    /** Power mode: every product in glut and the gatherer under the level cap. */
    static boolean power(boolean on, boolean glut, int level, int max) { return on && glut && level < max; }
    /** chooseJob: a job whose weight the glut zeroed gets the power weight, for a gatherer it suits. */
    static double powerWeight(boolean on, double weight, boolean glut, int level, int max) { return weight <= 0 && power(on, glut, level, max) ? POWER_WEIGHT : weight; }
    /** Which power kind (KIND_WC / KIND_FISH) a job is, 0 = none: willows for wc.power, fly fishing for fish.power. */
    static int powerKind(boolean wcOn, boolean fishOn, boolean willowJob, boolean lureJob) { return wcOn && willowJob ? KIND_WC : fishOn && lureJob ? KIND_FISH : 0; }
    static void powerDropped(int kind, int n) { if (kind == KIND_WC) wcPowerDropped += n; else if (kind == KIND_FISH) fishPowerDropped += n; }

    // ------------------------------------------------------------------ 3 Attack: bots.weaponByFoe
    static final boolean WEAPON_BY_FOE = flag("bots.weaponByFoe");
    static final int SLASH = 0, CRUSH = 1;
    /** Maces, one per SmartAdventurers.ARMOUR_TIERS row (rune, adamant, mithril, steel, iron, bronze): the second weapon class. */
    static final int[] MACES = {1432, 1430, 1428, 1424, 1420, 1422};
    /** The weakness column: npc name fragments (lower case) weak to crush; every other foe takes the scimitar (slash). */
    static final String[] CRUSH_WEAK = {"skeleton", "rock crab", "ankou", "ghoul", "zombie", "giant crab"};
    /** A mace may cost at most this share of (coins - 300), once per bot per MACE_GAP_MS. */
    static final double MACE_SHARE = 0.2;
    static final long MACE_GAP_MS = 6 * 3_600_000L;
    static volatile int foeCrush, foeSlash, foeSwaps, foeMaceBought, foeMaceStock, foeNoMace;
    /** The weakness of a hunt by its npc name: CRUSH or SLASH. */
    static int weakness(String npcName) {
        if (npcName == null) return SLASH;
        String n = npcName.toLowerCase(java.util.Locale.ROOT);
        for (String w : CRUSH_WEAK) if (n.contains(w)) return CRUSH;
        return SLASH;
    }
    /** The best tier row (index into ARMOUR_TIERS / MACES) an Attack level wields, given each row's level; -1 none. levels best first. */
    static int bestRow(int att, int[] rowLevel, boolean[] usable) {
        for (int t = 0; t < rowLevel.length; t++) if (att >= rowLevel[t] && (usable == null || usable[t])) return t;
        return -1;
    }
    /** Room for a mace: under the share of (coins - 300) and the per-bot gap since its last mace buy. */
    static boolean maceAffordable(long coins, int price, long lastBuy, long now) { return now - lastBuy >= MACE_GAP_MS && price > 0 && price <= (coins - 300) * MACE_SHARE; }
    static boolean isMace(int id) { for (int m : MACES) if (m == id) return true; return false; }

    // ------------------------------------------------------------------ 4 Defence: bots.kitAboveRune
    static final boolean KIT_ABOVE_RUNE = flag("bots.kitAboveRune");
    /** Per equipment slot, best first: {item id, Defence, Strength}. Ids and levels from item_configs.json (11-19). */
    static final int[][] ABOVE_HELM = {{4716, 70, 0}, {4724, 70, 0}, {4745, 70, 0}, {4753, 70, 0}, {1149, 60, 0}, {10589, 50, 50}},
            ABOVE_BODY = {{4720, 70, 0}, {4728, 70, 0}, {4749, 70, 0}, {4757, 70, 0}, {3140, 60, 0}, {10564, 50, 50}},
            ABOVE_LEGS = {{4722, 70, 0}, {4730, 70, 0}, {4751, 70, 0}, {4759, 70, 0}, {4087, 60, 0}, {6809, 50, 50}},
            ABOVE_SHIELD = {{3122, 50, 50}};
    static final int[] ABOVE_SLOTS = {0, 4, 7, 5};
    static final int[][][] ABOVE = {ABOVE_HELM, ABOVE_BODY, ABOVE_LEGS, ABOVE_SHIELD};
    /** One piece may cost at most this share of (coins - 300); a bot spends at most KIT_CAP gp on such pieces per server run. */
    static final double KIT_SHARE = 0.3;
    static final long KIT_CAP = Math.max(0, Long.getLong("bots.kitAboveRune.cap", 3_000_000L));
    static final int LOWDEF_MOD = 12;
    static volatile int kitBuys, kitCapped, kitNoStock, lowDefStance;
    static volatile long kitSpent;
    /** Rank of a worn item in a slot's list (0 best); list.length = an empty slot or a lower piece it may replace; -1 = keep it. */
    static int rankOf(int[][] list, int wornId, boolean replaceable) {
        if (wornId < 0) return list.length;
        for (int i = 0; i < list.length; i++) if (list[i][0] == wornId) return i;
        return replaceable ? list.length : -1;
    }
    /** The piece to buy for one slot (index into list), or -1: better than the worn one, levels met, in bot stock, under the share and the cap. */
    static int pick(int[][] list, int def, int str, int curRank, long coins, long spent, int[] price, int[] stock) {
        for (int i = 0; i < list.length && i < curRank; i++) {
            if (def < list[i][1] || str < list[i][2] || stock[i] <= 0) continue;
            if (price[i] <= 0 || price[i] > (coins - 300) * KIT_SHARE || spent + price[i] > KIT_CAP) continue;
            return i;
        }
        return -1;
    }
    /** A low-Defence build ("pure"): about 1 bot in 12, fixed by its name. */
    static boolean lowDef(String name) { return name != null && Math.floorMod(name.toLowerCase(java.util.Locale.ROOT).hashCode(), LOWDEF_MOD) == 5; }
    /** The melee stance with the flag on: a low-Defence build trains Strength (or Attack) where the old rule said Defence. */
    static String lowDefStance(boolean on, String name, String st, int att, int str) { return on && "defence".equals(st) && lowDef(name) ? (str < att ? "strength" : "attack") : st; }

    // ------------------------------------------------------------------ 5 Strength/Ranged/Magic: bots.kit.amulets, bots.ranged.melee, bots.magic.enchant
    static final boolean AMULETS = flag("bots.kit.amulets"), RANGED_MELEE = flag("bots.ranged.melee"), ENCHANT = flag("bots.magic.enchant");
    /** Amulets by style (SmartAdventurers MELEE 0, RANGED 1, MAGIC 2), best first. Glory(4) 1712, power 1731, strength 1725, accuracy 1478, magic 1727. */
    static final int[][] AMULET_BY_STYLE = {{1712, 1731, 1725, 1478}, {1712, 1731, 1478}, {1712, 1731, 1727}};
    static final double AMULET_SHARE = 0.1;
    static volatile int amuletBuys, amuletNone;
    /** Rank of the worn amulet in the style's list (0 best); list.length = none or another amulet. */
    static int amuletRank(int[] list, int wornId) { for (int i = 0; i < list.length; i++) if (list[i] == wornId) return i; return list.length; }
    /** The amulet to buy (index), or -1: better than the worn one, in bot stock, at most AMULET_SHARE of (coins - 300). */
    static int amuletPick(int[] list, int curRank, long coins, int[] price, int[] stock) {
        for (int i = 0; i < list.length && i < curRank; i++) if (stock[i] > 0 && price[i] > 0 && price[i] <= (coins - 300) * AMULET_SHARE) return i;
        return -1;
    }

    static final int RM_MOD = 5, RM_EVERY = 3, RM_ARROWS = 60;
    static volatile int rangedTrips, rangedBowBought, rangedNoKit, rangedBack;
    /** t63 fix 3: paid-for items with no room in pack or bank. noRoom = refused before paying; unplacedN = refused after (coins back or back on the Exchange). */
    static volatile int noRoom, unplacedN;
    /** t63 fix 3: coins back for the items of a purchase not placed: the share of what was paid in coins (0 when they came from bot stock). */
    static long refundCoins(long paid, int placed, int got) { if (got <= 0 || paid <= 0) return 0; int un = Math.max(0, Math.min(got, got - placed)); return paid * un / got; }
    /** t63 fix 3: n x id were paid for but neither pack nor bank took them: coins back if paid in coins, else back on the Exchange under the bot's name (the seller keeps its pay). */
    static void unplaced(core.game.node.entity.player.Player p, int id, int n, long back, String what) {
        unplacedN++;
        if (back > 0) Ledger.Wallet.give(p, back); else if (n > 0) Ledger.supply(p, id, n);
        System.out.println("[W8] " + what + ": " + p.getUsername() + " " + n + " x " + MarketBrain.name(id) + " not placed (pack and bank full), " + (back > 0 ? back + " gp back" : "back on the Exchange"));
    }
    /** About 1 melee bot in 5, fixed by its name. */
    static boolean rangedMelee(String name) { return name != null && Math.floorMod(name.toLowerCase(java.util.Locale.ROOT).hashCode() * 31 + 7, RM_MOD) == 2; }
    /** A ranged trip: one of those bots, every third trip, a hunt its Ranged level can take (npc level at most Ranged + 15). */
    static boolean rangedTrip(boolean on, String name, int trip, int rangedLevel, int npcLevel) { return on && rangedMelee(name) && trip % RM_EVERY == 0 && npcLevel <= rangedLevel + 15; }

    /** {unenchanted, enchanted, Magic level, element rune, element runes per cast, xp x10}; one cosmic rune per cast. */
    static final int[][] ENCH = {{1700, 1731, 57, 557, 10, 670}, {1698, 1725, 49, 554, 5, 590}, {1696, 1729, 27, 556, 3, 370}, {1694, 1727, 7, 555, 1, 175}};
    static final int COSMIC = 564, ENCH_MAX = 10, ENCH_STOCK_LEFT = 5;
    static final int ENCH_HOUR = Math.max(0, Integer.getInteger("bots.magic.enchant.perHour", 100));
    static volatile int enchRuns, enchMade, enchCapped;
    static volatile long enchHourAt; static volatile int enchHourN;
    /** The row to enchant (index into ENCH), or -1: Magic level met, unenchanted stock over the floor, the enchanted one not in glut. */
    static int enchRow(int magic, int[] stock, boolean[] glut) {
        for (int i = 0; i < ENCH.length; i++) if (magic >= ENCH[i][2] && stock[i] > ENCH_STOCK_LEFT && !glut[i]) return i;
        return -1;
    }
    /** How many: at most ENCH_MAX, the stock over the floor, the hour's room and a quarter of (coins - 300) at unitCost each. */
    static int enchQty(int stock, int hourUsed, long coins, long unitCost) {
        long n = Math.min(ENCH_MAX, Math.min(stock - ENCH_STOCK_LEFT, ENCH_HOUR - hourUsed));
        n = Math.min(n, Math.max(0, coins - 300) / 4 / Math.max(1, unitCost));
        return (int) Math.max(0, n);
    }
    static synchronized int enchHourUsed(long now) { if (now - enchHourAt > 3_600_000L) { enchHourAt = now; enchHourN = 0; } return enchHourN; }
    static synchronized void enchUse(int n) { enchHourN += n; }

    // ------------------------------------------------------------------ 6 Summoning: bots.summoning.heal (needs bots.summoning.combat)
    static final boolean SUMMON_HEAL = flag("bots.summoning") && flag("bots.summoning.combat") && flag("bots.summoning.heal");
    /** The engine's BUNYIP_POUCH (SummoningPouch, javap 11-19): pouch 12029, level 68, 7 points; Bob.COMBAT_ROWS columns {pouch, level, points, minutes}. */
    static final int BUNYIP = 12029, SWALLOW_WHOLE = 12438;
    static final int[] BUNYIP_ROW = {BUNYIP, 68, 7, 44};
    static final double HEAL_SHARE = 0.1;
    static volatile int healPlans, healBought, healNoPouch, healSummonOk;
    /** Bob.combatSummon's row for a pouch the COMBAT_ROWS table does not hold: the bunyip with the flag on, else null (train-61). */
    static int[] healRow(boolean on, int pouch) { return on && pouch == BUNYIP ? BUNYIP_ROW : null; }
    /** A bunyip trip: a dragon hunt (by its label) and Summoning 68+. */
    static boolean healTrip(boolean on, String label, int summoning) { return on && label != null && label.toLowerCase(java.util.Locale.ROOT).contains("dragon") && summoning >= BUNYIP_ROW[1]; }
    /** w21: the trip's bunyip is fetched (bank, else bot stock) when the pack holds none and has 3 free slots; no bank-distance rule. */
    static boolean healTake(int inPack, int freeSlots) { return inPack < 1 && freeSlots >= 3; }
    /** A bunyip pouch may cost at most HEAL_SHARE of (coins - 300). */
    static boolean healAffordable(long coins, int price) { return price > 0 && price <= (coins - 300) * HEAL_SHARE; }

    // ------------------------------------------------------------------ 7 Farming: bots.farming.trees (rules only)
    static final boolean TREES = flag("bots.farming") && flag("bots.farming.trees");
    /** {Farming level, seed, sapling, grow minutes, payment item, payment qty}, best last. Ids from item_configs.json (11-19): saplings
     *  are untradeable there, so a bot's input is the tradeable seed (bot stock or a nest drop) grown into a sapling. Payment -1 = none known. */
    static final int[][] TREE_ROWS = {
            {15, 5312, 5370, 200, 5968, 1},   // oak: tomatoes(5)
            {30, 5313, 5371, 280, 5386, 1},   // willow: apples(5)
            {45, 5314, 5372, 320, 5396, 1},   // maple: oranges(5)
            {60, 5315, 5373, 400, 6016, 10},  // yew: cactus spines
            {75, 5316, 5374, 480, 5974, 25}}; // magic: coconuts
    static final int[][] FRUIT_ROWS = {
            {27, 5283, 5496, 960, 5986, 9},   // apple: sweetcorn
            {33, 5284, 5497, 960, -1, 0},     // banana (apples(5) x4: the payment row is not verified, left unprotected)
            {39, 5285, 5498, 960, -1, 0},     // orange
            {42, 5286, 5499, 960, -1, 0},     // curry
            {51, 5287, 5500, 960, 5982, 10},  // pineapple: watermelons
            {57, 5288, 5501, 960, 2114, 10},  // papaya: pineapples
            {68, 5289, 5502, 960, 5972, 15}}; // palm: papaya fruit
    static final long TREE_MARGIN_MS = 30 * 60_000L, NEXT_DAY_MS = 20 * 3_600_000L;
    /** FarmSave refuses a file older than its maxAgeH (48 h default): a check due later than this after the save is never planned.
     *  t63 fix 5: FarmSave's own setting (the same property, default and floor as FarmSave.MAX_AGE_MS; read here so W8Polish never class-inits FarmSave or Farming). */
    static final long FARMSAVE_MAX_AGE_MS = Math.max(1L, Long.getLong("bots.farming.trees.save.maxAgeH", 48L)) * 3_600_000L;
    static volatile int treePlans;
    /** Seed source: 1 = the bot owns one (bank/pack: a nest or a drop), 2 = bot stock (Ledger.consume pays the seller), 0 = none (never a shop). */
    static int seedSource(int owned, int stock) { return owned > 0 ? 1 : stock > 0 ? 2 : 0; }
    /** The best row (index) the level reaches whose seed has a source; -1 none. */
    static int treePick(int[][] rows, int level, java.util.function.IntPredicate sourced) {
        for (int i = rows.length - 1; i >= 0; i--) if (level >= rows[i][0] && sourced.test(rows[i][1])) return i;
        return -1;
    }
    /** Pay the farmer to protect the tree: the payment item is owned in full (or buyable from bot stock), else plant unprotected. */
    static boolean payFarmer(int[] row, int owned, int stock) { return row[4] > 0 && owned + stock >= row[5]; }
    /** The health check: the next day after planting at the earliest, and never before the tree is grown. */
    static long checkDue(long plantedAt, int growMin) { return plantedAt + Math.max(growMin * 60_000L + TREE_MARGIN_MS, NEXT_DAY_MS); }
    /** A check FarmSave can carry: due after the save and inside its max file age (else the file would be refused as stale on restore). */
    static boolean saveFits(long savedAt, long due) { return due > savedAt && due - savedAt < FARMSAVE_MAX_AGE_MS; }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return FOOD_LADDER || WC_POWER || FISH_POWER || WEAPON_BY_FOE || KIT_ABOVE_RUNE || AMULETS || RANGED_MELEE || ENCHANT || SUMMON_HEAL || TREES; }
    static String flags() {
        StringBuilder b = new StringBuilder();
        if (FOOD_LADDER) b.append("foodLadder ");
        if (WC_POWER) b.append("wcPower ");
        if (FISH_POWER) b.append("fishPower ");
        if (WEAPON_BY_FOE) b.append("weaponByFoe ");
        if (KIT_ABOVE_RUNE) b.append("kitAboveRune ");
        if (AMULETS) b.append("kitAmulets ");
        if (RANGED_MELEE) b.append("rangedMelee ");
        if (ENCHANT) b.append("magicEnchant ");
        if (SUMMON_HEAL) b.append("summoningHeal ");
        if (TREES) b.append(W9Trees.RUN ? "farmingTrees(run) " : "farmingTrees(rulesOnly) "); // w9 trees: wired with -Dbots.farming.trees.run
        return b.toString().trim();
    }
    static String line() {
        StringBuilder b = new StringBuilder("[W8] on=[" + flags() + "]");
        if (FOOD_LADDER) b.append(" ladderBuys=").append(ladderBuys).append(" ladderFood=").append(ladderFood).append(" ladderRung=").append(java.util.Arrays.toString(ladderRung))
                .append(" ladderCeil=").append(ladderCeil).append(" ladderNoStock=").append(ladderNoStock).append(" ladderPoor=").append(ladderPoor);
        if (WC_POWER) b.append(" wcPowerStarts=").append(wcPowerStarts).append(" wcPowerDropped=").append(wcPowerDropped);
        if (FISH_POWER) b.append(" fishPowerStarts=").append(fishPowerStarts).append(" fishPowerDropped=").append(fishPowerDropped);
        if (WEAPON_BY_FOE) b.append(" foeCrush=").append(foeCrush).append(" foeSlash=").append(foeSlash).append(" foeSwaps=").append(foeSwaps).append(" foeMaceBought=").append(foeMaceBought).append(" foeMaceStock=").append(foeMaceStock).append(" foeNoMace=").append(foeNoMace);
        if (KIT_ABOVE_RUNE) b.append(" kitBuys=").append(kitBuys).append(" kitSpent=").append(kitSpent).append(" kitCapped=").append(kitCapped).append(" kitNoStock=").append(kitNoStock).append(" lowDefStance=").append(lowDefStance);
        if (AMULETS) b.append(" amuletBuys=").append(amuletBuys).append(" amuletNone=").append(amuletNone);
        if (RANGED_MELEE) b.append(" rangedTrips=").append(rangedTrips).append(" rangedBowBought=").append(rangedBowBought).append(" rangedNoKit=").append(rangedNoKit).append(" rangedBack=").append(rangedBack);
        if (ENCHANT) b.append(" enchRuns=").append(enchRuns).append(" enchMade=").append(enchMade).append(" enchCapped=").append(enchCapped);
        if (noRoom > 0 || unplacedN > 0) b.append(" noRoom=").append(noRoom).append(" unplaced=").append(unplacedN); // t63 fix 3 (silent while both are 0)
        if (SUMMON_HEAL) b.append(" healPlans=").append(healPlans).append(" healBought=").append(healBought).append(" healNoPouch=").append(healNoPouch).append(" healSummoned=").append(healSummonOk);
        if (TREES) b.append(" treePlans=").append(treePlans);
        return b.toString();
    }
}
