package content.custom;

import core.game.node.entity.player.Player;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P87 (slayer design audit items 1 and 2). Both flags default off; off = today's path.
 * -Dbots.fight.foodGate=true: a combat hunt is closed for a bot that cannot be fed for the npc level (chooseJob, Slayer.openGate).
 * -Dbots.fight.fleeBreak=true: 2 flees at one hunt inside 30 min: that hunt is blacklisted 60 min, the bot takes no combat hunt for
 * 60 min (it picks other work), and its slayer streak is cleared.
 */
final class P87Fight {
    private P87Fight() { }

    static final boolean FOOD = Boolean.getBoolean("bots.fight.foodGate");
    static final boolean BREAK = Boolean.getBoolean("bots.fight.fleeBreak");
    static final long WINDOW_MS = 30 * 60_000L, REST_MS = 60 * 60_000L, DEDUPE_MS = 60_000L;
    static final int FOOD_COST = 25; // gp an item, what buyFood pays for the cheap stuff

    /** Food a hunt of this npc level needs for a bot of this combat: 0 for easy targets (level 15 or less, or
     *  at most two thirds of the bot's combat, as a player fights those bare), else clamp(level/8, 6, 20). */
    static int foodNeed(int npcLevel, int combat) {
        if (npcLevel <= 15 || npcLevel * 3 <= combat * 2) return 0;
        return Math.max(6, Math.min(20, npcLevel / 8));
    }

    /** Open when food held (pack + bank) reaches need-2, or the purse could buy the rest (buyFood spends up to a quarter of it). */
    static boolean gate(int held, int need, int bank, long coins) {
        if (need <= 0) return true;
        int have = held + bank, want = need - 2;
        if (have >= want) return true;
        return coins / 4 >= (long) (want - have) * FOOD_COST;
    }

    /** P87-only: food id -> hp healed, for what a fight can use (foodFor/GOOD_FOOD stay as they are for other callers). */
    static final int[][] HEAL = {{385, 20}, {7946, 16}, {373, 14}, {379, 12}, {361, 10}, {329, 9}, {351, 8}, {333, 7}};

    /** Food held (pack + bank) that heals enough for the hunt: any item healing at least min(7, heal a hunt of this level wants),
     *  so trout, salmon, pike and tuna count for level 25+ hunts, not only the top few. */
    static int foodForFight(SmartAdventurers.Brain b, HuntMap.Hunt h) {
        int min = h.npcLevel >= 25 ? 7 : 1, have = 0;
        try {
            for (int[] f : HEAL) if (f[1] >= min) have += b.bot.getBank().getAmount(f[0]) + b.bot.getInventory().getAmount(f[0]);
            if (h.npcLevel < 25) return Math.max(have, b.foodFor(h));
        } catch (Throwable ignored) { }
        return have;
    }

    /** W3 fix 6: the cheapest food buyFood can get from a shop (SHOP_FOOD), in gp. */
    static int cheapPrice() { int cp = Integer.MAX_VALUE; for (int[] f : SmartAdventurers.SHOP_FOOD) cp = Math.min(cp, Math.max(1, MarketBrain.value(f[0]))); return cp == Integer.MAX_VALUE ? FOOD_COST : cp; }

    /** True when the bot should not take this hunt for want of food. */
    static boolean closedByFood(SmartAdventurers.Brain b, HuntMap.Hunt h, int combat) {
        if (!FOOD || h == null) return false;
        try {
            int need = foodNeed(h.npcLevel, combat);
            boolean closed = !gate(foodForFight(b, h), need, 0, Ledger.Wallet.coins(b.bot));
            if (closed && W3Skills.CHEAP_FOOD && W3Skills.cheapGate(true, h.npcLevel, W3Skills.CHEAP_MAX_LEVEL, foodForFight(b, h), need, Ledger.Wallet.coins(b.bot), cheapPrice())) { // W3 fix 6 (-Dbots.fight.cheapFood): a low hunt on the cheapest food the whole purse buys
                closed = false;
                if (now() - LASTLOG.getOrDefault(b.bot.getUsername() + "|cheap", 0L) > 10 * 60_000L) { LASTLOG.put(b.bot.getUsername() + "|cheap", now()); W3Skills.cheapOpened++; System.out.println("[Fight] (W3) " + b.bot.getUsername() + " kept " + h.name + " open on cheap food (held " + foodForFight(b, h) + ", need " + need + ", " + Ledger.Wallet.coins(b.bot) + " gp)"); }
            }
            if (closed && now() - LASTLOG.getOrDefault(b.bot.getUsername(), 0L) > 10 * 60_000L) {
                LASTLOG.put(b.bot.getUsername(), now());
                System.out.println("[Fight] (P87) " + b.bot.getUsername() + " closed " + h.name + " for no food (held " + foodForFight(b, h) + ", need " + need + ")");
            }
            return closed;
        } catch (Throwable t) { return false; }
    }

    /** Pure: two flee times (ms) inside WINDOW_MS of now. */
    static boolean fleeBreak(long[] fleeTimes, long now) {
        int n = 0;
        if (fleeTimes != null) for (long t : fleeTimes) if (now - t <= WINDOW_MS && now >= t) n++;
        return n >= 2;
    }

    static final Map<String, long[]> FLEES = new ConcurrentHashMap<>(); // bot|label -> last two flee times
    static final Map<String, Long> REST = new ConcurrentHashMap<>();    // bot -> rest end
    static int BREAKS;
    static final Map<String, Long> LASTLOG = new ConcurrentHashMap<>();
    static long now() { return System.currentTimeMillis(); }

    static boolean resting(SmartAdventurers.Brain b) {
        if (!BREAK || b == null) return false;
        Long t = REST.get(b.bot.getUsername().toLowerCase(Locale.ROOT));
        return t != null && t > System.currentTimeMillis();
    }

    /** Called from AfkTasks.Runner.flee for every flee. Returns true when this one tripped the rest. */
    static boolean onFlee(Player p, String why) { return onFlee(p, why, System.currentTimeMillis()); }

    static boolean onFlee(Player p, String why, long now) {
        if (!BREAK) return false;
        try {
            SmartAdventurers.Brain b = SmartAdventurers.brainOf(p);
            if (b == null || b.lastJob == null || b.lastJob.hunt == null) return false;
            return record(b, b.lastJob.label(), why, now);
        } catch (Throwable t) { System.out.println("[Fight] (P87) onFlee " + t); return false; }
    }

    static boolean record(SmartAdventurers.Brain b, String label, String why, long now) {
        String key = b.bot.getUsername().toLowerCase(Locale.ROOT) + "|" + label;
        long[] f = FLEES.computeIfAbsent(key, k -> new long[] { 0, 0 });
        synchronized (f) {
            if (f[1] != 0 && now - f[1] < DEDUPE_MS) return false; // one flee event, re-entered
            f[0] = f[1]; f[1] = now;
            if (f[0] == 0 || !fleeBreak(f, now)) return false;
            f[0] = 0; f[1] = 0;
        }
        b.blacklist.put(label, now + REST_MS);
        REST.put(b.bot.getUsername().toLowerCase(Locale.ROOT), now + REST_MS);
        try { b.slaySt.streak = false; } catch (Throwable ignored) { }
        BREAKS++;
        System.out.println("[Fight] (P87) " + b.bot.getUsername() + " gives " + label + " a rest (2 flees, last: " + why + ")");
        try { if (SmartAdventurers.RNG.nextInt(2) == 0) b.say("Not going back there without food."); } catch (Throwable ignored) { }
        return true;
    }
}
