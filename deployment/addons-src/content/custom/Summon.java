package content.custom;

import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * P62 (the design notes section 5 step 2): Summoning training for bots that finished Wolf Whistle.
 * Off by default: -Dbots.summoning=true turns it on (-Dbots.summoning.max=<n> caps errands running at once, default 20).
 * With the flag off nothing here is called: SmartAdventurers and AfkTasks guard every call with ON, and the Brain's new state
 * field `summon` stays 0.
 *
 * What a bot does with the flag on:
 *  - keeps the charms its kills drop (AfkTasks.Runner.pickUpLoot picks them up, deposit banks them, never sells: untradeable);
 *  - at a trip end by a bank, every hour at most, it picks the best pouch its Summoning level allows that it has charms and a
 *    secondary for (its own banked secondary first, else it buys the secondary from the bots' Exchange stock through Ledger.consume, which pays
 *    the sellers, no mint), takes them from the bank, walks to Pikkupstix in Taverley, pays the shop (Wallet.pay: spirit shards
 *    25 gp, empty pouches 1 gp, a coin sink), opens the trapdoor, infuses the pouches at the cellar obelisk with the server's own
 *    SummoningCreator.create, renews its Summoning points there, climbs back up and banks the pouches and any leftovers.
 *  - No familiar is ever summoned in this step (beast-of-burden spills, F1/F2), so saves carry no familiar block.
 *
 * The pick, the budget and the fleet cap live here, not in SmartAdventurers, so TestP62Summon can check them without a world.
 */
final class Summon {
    private Summon() { }

    static final boolean ON = Boolean.getBoolean("bots.summoning");
    /** Errands running at once, all bots together. */
    static final int MAX = Math.max(1, Integer.getInteger("bots.summoning.max", 20));
    /** Trial only: the next trip end by a bank runs at once (no 1-in-3 roll, no hour wait). */
    static final boolean TEST = Boolean.getBoolean("smartbots.testSummon");
    /** P79 S1+S2 (the design notes): -Dbots.summoning.mood (needs bots.summoning). A per-bot desire score replaces the hourly 1-in-3 gate, the spend rule is B, and Wolf Whistle gets a higher quest weight near Taverley/Falador. Off = today's code. */
    static final boolean MOOD = ON && Boolean.getBoolean("bots.summoning.mood");

    /** P79 S1: fixed interest 0.2-1.0 per bot from a hash of the name (some bots love Summoning, some ignore it). */
    static double interest(String name) {
        int h = name == null ? 0 : name.toLowerCase(Locale.ROOT).hashCode();
        h ^= h >>> 16; h *= 0x45d9f3b; h ^= h >>> 16; h *= 0x45d9f3b; h ^= h >>> 16;
        return 0.2 + 0.8 * ((h & 0xffff) / 65535.0);
    }

    /**
     * P79 S1: the chance a trip end by a bank starts an errand, 0..0.95. interest x (0.15 base + charm pile up to 0.35 at 100
     * charms + Summoning lagging combat up to 0.30 at 40 levels behind + session time up to 0.15 at 4 h); x 0.03 while in the
     * 1-3 h cooldown after a run.
     */
    static double desire(double interest, long charms, int sumLvl, int combat, long sessionMs, boolean cooling) {
        double s = 0.15 + 0.35 * Math.min(1.0, Math.max(0, charms) / 100.0)
                + 0.30 * Math.min(1.0, Math.max(0, combat - sumLvl) / 40.0)
                + 0.15 * Math.min(1.0, Math.max(0, sessionMs) / (4 * 3_600_000.0));
        s *= Math.max(0.2, Math.min(1.0, interest));
        if (cooling) s *= 0.03;
        return Math.max(0, Math.min(0.95, s));
    }

    /** P79 S1: one desire roll per bot at most this often (the trip-end check runs every trip). */
    static long rollGapMs(boolean fast) { return fast ? 30_000 : 15 * 60_000; }
    /** P79 S1: the "just did it" cooldown after a run starts, 1-3 h (fast: 2-6 min). */
    static long coolMs(boolean fast, double u) { double k = Math.max(0, Math.min(1, u)); return fast ? (long) (120_000 + 240_000 * k) : (long) (3_600_000 + 7_200_000 * k); }
    /** P79 S1: a passed roll that found no pouch to make looks again after this long. */
    static long noPickGapMs(boolean fast) { return fast ? 60_000 : 30 * 60_000; }

    /** P79 S2: spend rule A (today, 10%) or B (approved): max(10% of coins, min(1,500 gp, 40% of coins)). */
    static long budget(long coins, boolean ruleB) {
        long c = Math.max(0, coins), a = c * BUDGET_PCT / 100;
        return ruleB ? Math.max(a, Math.min(1_500, c * 40 / 100)) : a;
    }

    /** P79 option B: the smallest run under mood (only the budget step goes below MIN_N). */
    static final int MOOD_MIN_N = 2;

    /**
     * P79 option B: can the bot pay for the cheapest run it qualifies for (MOOD_MIN_N pouches of a row its level allows with
     * MIN_N+ charms banked, secondaries it lacks at their value) under rule B? No: the desire roll is not spent.
     */
    static boolean affordable(int level, long coins, IntUnaryOperator charmBank, IntUnaryOperator secBank, IntUnaryOperator secValue) {
        long budget = budget(coins, true);
        for (int[] r : POUCHES) {
            if (r[1] > level || charmBank.applyAsInt(r[2]) < MIN_N) continue;
            long unit = (long) r[4] * SHARD_PRICE + POUCH_PRICE, val = Math.max(1, secValue.applyAsInt(r[3]));
            if (cost(unit, val, MOOD_MIN_N, Math.max(0, secBank.applyAsInt(r[3]))) <= budget) return true;
        }
        return false;
    }
    static volatile long POOR;

    // P79 tally (mood only): desire starts and the score band of each roll (<0.1, 0.1-0.3, 0.3-0.6, 0.6+)
    static final long[] BAND = new long[4];
    static volatile long DESIRE_STARTS;
    static synchronized void band(double s) { BAND[s < 0.1 ? 0 : s < 0.3 ? 1 : s < 0.6 ? 2 : 3]++; }

    static final int GOLD = 12158, GREEN = 12159, CRIMSON = 12160, BLUE = 12163;
    static final int[] CHARMS = {GOLD, GREEN, CRIMSON, BLUE};
    static final int SHARDS = 12183, EMPTY = 12155;
    static final int SHARD_PRICE = 25, POUCH_PRICE = 1; // Pikkupstix's shop 141 (shards 25 gp; the empty pouch is a 1 gp item)
    static final int MAX_N = 12, MIN_N = 3, BUDGET_PCT = 10, MIN_STOCK = 20;

    /**
     * Training pouches whose secondary the bots' economy carries (bones, ore, sand, logs, bars, compost, meat).
     * {pouch id, level, charm, secondary, shards}; ids, levels and recipes from SummoningPouch (live jar).
     */
    static final int[][] POUCHES = {
            {12047, 1, GOLD, 2859, 7},     // spirit wolf: wolf bones
            {12043, 4, GOLD, 2138, 8},     // dreadfowl: raw chicken
            {12009, 16, GOLD, 440, 7},     // granite crab: iron ore
            {12049, 18, GREEN, 1783, 45},  // desert wyrm: bucket of sand
            {12067, 23, BLUE, 2134, 75},   // albino rat: raw rat meat
            {12091, 28, GREEN, 6032, 47},  // compost mound: compost
            {12021, 33, GREEN, 1519, 72},  // beaver: willow logs
            {12073, 36, BLUE, 2349, 102},  // bronze minotaur: bronze bar
            {12075, 46, BLUE, 2351, 125},  // iron minotaur: iron bar
    };

    /** P92 S4 1.3 (only when Bob.ON): beast of burden pouches, same columns. Rows after POUCHES in row(i). */
    static final int[][] BOB_POUCHES = {
            {12019, 13, GOLD, 3363, 9},    // thorny snail: thin snail
            {12063, 25, BLUE, 3138, 51},   // spirit kalphite: potato cactus
            {12087, 40, GOLD, 6010, 11},   // bull ant: marigolds
            {12007, 52, GOLD, 9978, 12},   // spirit terrorbird: raw bird meat
            {12031, 67, GOLD, 7939, 1},    // war tortoise: tortoise shell
    };
    /** Summoning in combat v2 (only read when Bob.COMBAT2): extra combat rows, same columns. Rows after BOB_POUCHES in row(i). */
    static final int[][] COMBAT2_POUCHES = {
            {12077, 56, BLUE, 2353, 141},  // steel minotaur: steel bar
    };
    static int[] row(int i) { return i < POUCHES.length ? POUCHES[i] : i < POUCHES.length + BOB_POUCHES.length ? BOB_POUCHES[i - POUCHES.length] : COMBAT2_POUCHES[i - POUCHES.length - BOB_POUCHES.length]; }

    // Taverley: Pikkupstix's shop, his trapdoor, the cellar obelisk and its ladder (QuestWolfWhistle constants, trial 1 tiles)
    static final Location PIKK_AT = Location.create(2926, 3443, 0);
    static final Location TAV_GATE_E = Location.create(2939, 3450, 0); // P74: outside Taverley's east gate (2935-36,3450); the walk goes through it from the Falador side
    /** P74: a walk from the east or south of Taverley (Falador, Draynor...) takes the east gate first: the learned route (nav off or auto-reverted) parked bots at 2945,3407 against the hedge, timing out the Falador -> Taverley walk (TestP62Trial 1/4 without -Dnav). */
    static boolean viaEastGate(Location here) { return here != null && here.getZ() == 0 && here.getX() >= 2937 && here.getY() <= 3470 && here.getDistance(TAV_GATE_E) > 2.5; }
    static final Location TRAPDOOR_AT = Location.create(2927, 3444, 0);
    static final Location CELLAR = Location.create(2209, 5348, 0);
    static final Location CELLAR_LADDER_AT = Location.create(2209, 5349, 0);
    static final int TRAPDOOR = 28675, TRAPDOOR_OPEN = 28676, CELLAR_LADDER = 28714;

    static boolean isCharm(int id) { return id == GOLD || id == GREEN || id == CRIMSON || id == BLUE; }

    /** Wolf Whistle complete (Summoning unlocked: SummonFamiliarPlugin / FamiliarManager refuse it before). */
    static boolean unlocked(Player p) {
        try { return p.getQuestRepository().isComplete(content.data.Quests.WOLF_WHISTLE); } catch (Throwable t) { return false; }
    }

    /** A smart bot with the flag on and Wolf Whistle done keeps the charms it sees dropped. */
    static boolean keepCharm(boolean on, int id, boolean artificial, boolean unlocked) {
        return on && artificial && unlocked && isCharm(id);
    }

    /**
     * The pick. Returns {row in POUCHES, n, nBuy (secondaries to buy through Ledger)} or null for no errand.
     * Highest level first that the bot can make at least MIN_N of: charms from the bank, secondaries from the bank, then (only
     * from Ledger stock that holds MIN_STOCK or more; pack room 2 + 2n slots (charms and
     * shards stack, pouches and secondaries do not); the whole cost (shards, pouches, bought secondaries) within BUDGET_PCT of
     * the purse.
     */
    static int[] choose(int level, long coins, int freeSlots, IntUnaryOperator charmBank, IntUnaryOperator secBank,
                        IntUnaryOperator secBuyable, IntUnaryOperator secValue) {
        return choose(level, coins, freeSlots, charmBank, secBank, secBuyable, secValue, false);
    }

    /** As above; fighter (Bob.fightsALot, read only under -Dbots.summoning.combat): combat pouches first until COMBAT_KEEP are banked. */
    static int[] choose(int level, long coins, int freeSlots, IntUnaryOperator charmBank, IntUnaryOperator secBank,
                        IntUnaryOperator secBuyable, IntUnaryOperator secValue, boolean fighter) {
        if (level < 1 || freeSlots < 2 + 2 * MIN_N) { why(0); return null; }
        int room = Math.min(MAX_N, (freeSlots - 2) / 2);
        long budget = budget(coins, MOOD); // P79 S2: rule B under -Dbots.summoning.mood, else 10% (as before)
        if (fighter && Bob.COMBAT) { int[] c = combatPick(level, room, budget, MOOD ? MOOD_MIN_N : MIN_N, charmBank, secBank, secBuyable, secValue); if (c != null) { COMBAT_PICKS++; return c; } }
        if (Bob.ON) { int[] b = bobPick(level, room, budget, charmBank, secBank, secBuyable, secValue); if (b != null) return b; } // P92 S4 1.3
        int worst = 1; // P74b: the furthest a row got: 1 charms, 2 secondaries, 3 budget
        for (int i = POUCHES.length - 1; i >= 0; i--) {
            int[] r = POUCHES[i];
            if (r[1] > level) continue;
            int charms = charmBank.applyAsInt(r[2]);
            if (charms < MIN_N) continue;
            int own = Math.max(0, secBank.applyAsInt(r[3]));
            int stock = Math.max(0, secBuyable.applyAsInt(r[3]));
            if (stock < MIN_STOCK) stock = 0;
            int n = Math.min(room, Math.min(charms, own + stock));
            if (n < MIN_N) { worst = Math.max(worst, 2); continue; }
            long unit = (long) r[4] * SHARD_PRICE + POUCH_PRICE;
            long val = Math.max(1, secValue.applyAsInt(r[3]));
            int lo = MOOD ? MOOD_MIN_N : MIN_N; // P79 option B: under mood a bot whose budget can't cover 3 makes 2
            while (n >= lo && cost(unit, val, n, own) > budget) n--;
            if (n < lo) { worst = 3; continue; }
            return new int[]{i, n, Math.max(0, n - own)};
        }
        why(worst);
        return null;
    }

    static final int COMBAT_KEEP = 2;
    static volatile long COMBAT_PICKS;
    /**
     * Summoning in combat: a bot that fights a lot and has fewer than COMBAT_KEEP usable combat pouches banked makes the highest
     * combat row it can (Bob.COMBAT_ROWS; the beaver is skipped), before the beast of burden pick. Same charms, stock, room and
     * budget checks as choose (lo = its smallest batch); null = the normal pick.
     */
    static int[] combatPick(int level, int room, long budget, int lo, IntUnaryOperator charmBank, IntUnaryOperator secBank, IntUnaryOperator secBuyable, IntUnaryOperator secValue) {
        if (Bob.COMBAT2) return combatPick2(level, room, budget, lo, charmBank, secBank, secBuyable, secValue);
        int have = 0;
        for (int[] r : POUCHES) if (r[1] <= level && Bob.isCombatPouch(r[0])) have += Math.max(0, secBank.applyAsInt(r[0]));
        if (have >= COMBAT_KEEP) return null;
        for (int i = POUCHES.length - 1; i >= 0; i--) {
            int[] r = POUCHES[i];
            if (r[1] > level || !Bob.isCombatPouch(r[0])) continue;
            int charms = charmBank.applyAsInt(r[2]);
            if (charms < MIN_N) continue;
            int own = Math.max(0, secBank.applyAsInt(r[3]));
            int stock = Math.max(0, secBuyable.applyAsInt(r[3]));
            if (stock < MIN_STOCK) stock = 0;
            int n = Math.min(room, Math.min(charms, own + stock));
            if (n < MIN_N) continue;
            long unit = (long) r[4] * SHARD_PRICE + POUCH_PRICE;
            long val = Math.max(1, secValue.applyAsInt(r[3]));
            while (n >= lo && cost(unit, val, n, own) > budget) n--;
            if (n < lo) continue;
            return new int[]{i, n, Math.max(0, n - own)};
        }
        return null;
    }

    /**
     * v2 (-Dbots.summoning.combat2): as combatPick over every row (steel minotaur too), and the fighter also keeps scrolls: while it
     * owns fewer than Bob.SCROLL_LOW scrolls it makes a batch of the best row with a real special (part of it becomes scrolls at the
     * obelisk, Bob.scrollBatch). Rows with a special are tried first. Same charms, stock, room and budget checks.
     */
    static int[] combatPick2(int level, int room, long budget, int lo, IntUnaryOperator charmBank, IntUnaryOperator secBank, IntUnaryOperator secBuyable, IntUnaryOperator secValue) {
        int all = POUCHES.length + BOB_POUCHES.length + COMBAT2_POUCHES.length, have = 0, scrolls = 0;
        for (int i = 0; i < all; i++) { int[] r = row(i); if (r[1] <= level && Bob.isCombatPouch(r[0])) { have += Math.max(0, secBank.applyAsInt(r[0])); int[] s = Bob.specOf(r[0]); if (s != null) scrolls += Math.max(0, secBank.applyAsInt(s[1])); } }
        boolean low = scrolls < Bob.SCROLL_LOW;
        if (have >= COMBAT_KEEP && !low) return null;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1 && have >= COMBAT_KEEP) break;
            for (int i = all - 1; i >= 0; i--) {
                int[] r = row(i);
                if (r[1] > level || !Bob.isCombatPouch(r[0]) || (pass == 0 && Bob.specOf(r[0]) == null)) continue;
                int charms = charmBank.applyAsInt(r[2]);
                if (charms < MIN_N) continue;
                int own = Math.max(0, secBank.applyAsInt(r[3]));
                int stock = Math.max(0, secBuyable.applyAsInt(r[3]));
                if (stock < MIN_STOCK) stock = 0;
                int n = Math.min(room, Math.min(charms, own + stock));
                if (n < MIN_N) continue;
                long unit = (long) r[4] * SHARD_PRICE + POUCH_PRICE;
                long val = Math.max(1, secValue.applyAsInt(r[3]));
                while (n >= lo && cost(unit, val, n, own) > budget) n--;
                if (n < lo) continue;
                return new int[]{i, n, Math.max(0, n - own)};
            }
        }
        return null;
    }

    /** P92 S4 1.3: 2 pouches of the best beast of burden row the level allows, when fewer than 2 are owned and the row passes the checks. */
    static int[] bobPick(int level, int room, long budget, IntUnaryOperator charmBank, IntUnaryOperator secBank, IntUnaryOperator secBuyable, IntUnaryOperator secValue) {
        for (int i = BOB_POUCHES.length - 1; i >= 0; i--) {
            int[] r = BOB_POUCHES[i];
            if (r[1] > level) continue;
            if (secBank.applyAsInt(r[0]) >= 2) return null; // owns enough of its best row: the normal pick
            int n = 2;
            if (room < n) return null;
            if (charmBank.applyAsInt(r[2]) < n) continue; // s4b M1: a row short of charms, secondary or budget falls through to the next row down
            int own = Math.max(0, secBank.applyAsInt(r[3]));
            int stock = Math.max(0, secBuyable.applyAsInt(r[3]));
            if (stock < MIN_STOCK) stock = 0;
            long shop = own + stock < n ? bobShop(r[3]) : 0; // s4b M1: the Exchange's own stock, paid in full, for the one secondary no bot can gather
            if (own + stock < n && shop <= 0) continue;
            if (cost((long) r[4] * SHARD_PRICE + POUCH_PRICE, Math.max(shop, Math.max(1, secValue.applyAsInt(r[3]))), n, own) > budget) continue;
            return new int[]{POUCHES.length + i, n, Math.max(0, n - own)};
        }
        return null;
    }

    /**
     * s4b M1: thin snails (thorny snail pouch, the only beast of burden at 13) are hunted in Mort Myre, where no bot goes, so no
     * bot ever sells one. A bot short of them for its 2 beast pouches buys the shortfall at the bank for this fixed price (a coin
     * sink through Wallet.pay; read only under Bob.ON). 0 = this secondary has no such source.
     */
    static final int THIN_SNAIL = 3363, SNAIL_PRICE = Math.max(1, Integer.getInteger("bots.summoning.bob.snailPrice", 150));
    static long bobShop(int secId) { return secId == THIN_SNAIL ? SNAIL_PRICE : 0; }

    /** P74b: why choose() found nothing (slots, charms, secondaries, budget), shown in the tally as noPickWhy. */
    static final int[] WHY = new int[4];
    static synchronized void why(int k) { if (ON) WHY[k]++; }

    static long cost(long unit, long secVal, int n, int own) { return unit * n + secVal * Math.max(0, n - own); }

    /** Shop bill at Pikkupstix for n pouches of this row (shards + empty pouches). */
    static long shopBill(int row, int n) { return ((long) row(row)[4] * SHARD_PRICE + POUCH_PRICE) * n; }

    // fleet cap: bots on an errand right now (a set, so a double end never counts twice)
    static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    static boolean seat(String name) { if (ACTIVE.size() >= MAX) return false; return ACTIVE.add(name.toLowerCase(Locale.ROOT)); }
    static void free(String name) { ACTIVE.remove(name.toLowerCase(Locale.ROOT)); }

    /** P69 #13: seats are freed only in endSummon, so a brain dropped without logOut leaked one. When the seats are full, drop each whose bot holds no errand. */
    static int sweep(java.util.function.Predicate<String> holds) {
        int n = 0;
        for (String k : ACTIVE) if (!holds.test(k) && ACTIVE.remove(k)) n++;
        if (n > 0) System.out.println("[Summon] freed " + n + " stale seat(s) (no errand behind them)");
        return n;
    }
    static boolean seat(String name, java.util.function.Predicate<String> holds) { if (ACTIVE.size() >= MAX) sweep(holds); return seat(name); }

    static volatile long RUNS, POUCHES_MADE, SPENT, BOUGHT, GAVE_UP, CHARMS_KEPT;
    static volatile double XP;

    // Why a trip end did not start an errand: one [Summon] tally line every 15 min (2 min under -Dtraders.fast).
    static final int R_BUSY = 0, R_COMBAT = 1, R_WAIT = 2, R_NOT_BANK = 3, R_LOCKED = 4, R_CAP = 5, R_NO_PICK = 6, R_TAKE_FAIL = 7, R_BUY_FAIL = 8, R_STARTED = 9;
    static final String[] REASONS = {"busy", "combat", "wait", "notBank", "noWolfWhistle", "fleetCap", "noPick", "takeFail", "buyFail", "started"};
    static final long[] TALLY = new long[REASONS.length];
    private static long tallyAt;

    static synchronized boolean skip(int reason, long now) {
        TALLY[reason]++;
        if (tallyAt == 0) tallyAt = now;
        else if (now - tallyAt >= (Boolean.getBoolean("traders.fast") ? 120_000 : 15 * 60_000)) { tallyAt = now; System.out.println("[Summon] tally " + tallyLine() + " " + totals() + (Bob.COMBAT ? Bob.combatField() : "")); if (Bob.ON) System.out.println(Bob.bobLine()); if (FightRules.STEER || FightRules.LOOT_AFTER) System.out.println("[FightRules] tally" + FightRules.line()); } // P92 S4
        return false;
    }

    static synchronized String tallyLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < REASONS.length; i++) sb.append(i == 0 ? "" : " ").append(REASONS[i]).append('=').append(TALLY[i]);
        if (MOOD) sb.append(" desireStarts=").append(DESIRE_STARTS).append(" poor=").append(POOR).append(" band=").append(BAND[0]).append('/').append(BAND[1]).append('/').append(BAND[2]).append('/').append(BAND[3]); // P79
        if (Bob.FUN) sb.append(Bob.tallyLine()); // P86 S5
        if (WHY[0] + WHY[1] + WHY[2] + WHY[3] > 0) sb.append(" noPickWhy=slots:").append(WHY[0]).append(",charms:").append(WHY[1]).append(",secondaries:").append(WHY[2]).append(",budget:").append(WHY[3]); // P74b
        return sb.toString();
    }

    static String totals() {
        return String.format(Locale.ROOT, "runs=%d pouches=%d xp=%.1f spent=%d bought=%d gaveUp=%d active=%d charmsKept=%d", RUNS, POUCHES_MADE, XP, SPENT, BOUGHT, GAVE_UP, ACTIVE.size(), CHARMS_KEPT);
    }

    static boolean inCellar(Location l) { return l.getX() >= 2180 && l.getX() <= 2240 && l.getY() >= 5320 && l.getY() <= 5380; }
}
