package content.custom;

import content.global.skill.crafting.gem.GemCutPulse;
import content.global.skill.crafting.gem.Gems;
import content.global.skill.herblore.FinishedPotion;
import content.global.skill.herblore.GenericPotion;
import content.global.skill.herblore.HerblorePulse;
import content.global.skill.herblore.UnfinishedPotion;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;

import java.util.Locale;

/**
 * w37-craft (results/w37-craft.md): two bank-side errands for ordinary adventurer bots, each behind its own default-OFF flag.
 *
 *   -Dbots.craft.gems    Crafting 20+: uncut sapphires/emeralds/rubies/diamonds (own bank first, else bot stock) are cut with a
 *                        chisel by the engine's GemCutPulse (the pulse a player's chisel click starts). The cut gems go on
 *                        sale as bot stock under the bot's name (banked instead while the market is in surplus of them).
 *   -Dbots.herb.potions  Druidic Ritual done and Herblore 3+: grimy herbs (own bank, else bot stock) are cleaned by the engine's
 *                        "clean" item listener, put into vials of water and finished with the secondary by HerblorePulse
 *                        (attack, strength, prayer, super attack, super strength). The potions go on sale the same way.
 *
 * When: the last trip-end errand in Brain.tick (after every existing errand had its turn), at a bank, at most once per bot per
 * GAP_MIN minutes and RUNS_HOUR runs server-wide per hour and flag; a run lasts 5 minutes at most. So it cannot starve a job.
 *
 * Economy: nothing is created. Gems, herbs, limpwurt and snape grass come from the bot's bank or are bought from bot stock
 * (Ledger.consume pays the sellers). Chisel, vials of water and eyes of newt come from the bank or bot stock first, else from
 * a shop at the guide price (Wallet.pay, a coin sink: the SmithOwn hammer pattern). A purchase costs a quarter of the purse
 * at most. Products are made only by the engine pulses, from those inputs.
 *
 * No RNG anywhere in this class (clocks and counters only). With both flags off nothing here runs and Brain.w37 stays null.
 */
final class W37Craft {
    private W37Craft() { }

    static final boolean GEMS = Boolean.getBoolean("bots.craft.gems");
    static final boolean POTIONS = Boolean.getBoolean("bots.herb.potions");
    static final boolean ANY = GEMS || POTIONS;
    static final boolean FAST = Boolean.getBoolean("traders.fast");
    static final long GAP_MS = Math.max(1, Integer.getInteger("bots.w37.gapMin", FAST ? 2 : 45)) * 60_000L;
    static final long RETRY_MS = Math.max(1, Integer.getInteger("bots.w37.retryMin", FAST ? 1 : 10)) * 60_000L;
    static final int RUNS_HOUR = Math.max(1, Integer.getInteger("bots.w37.runsPerHour", 12));
    static final int MIN_N = 3, GEM_MAX = 14, POT_MAX = 13, STOCK_PCT = 50;
    static final long MIN_PURSE = 2_000, RUN_MS = 5 * 60_000L;
    static final int CHISEL = 1755, VIAL = 227, NEWT = 221, LIMP = 225, SNAPE = 231;

    /** {uncut, cut, Crafting level} */
    static final int[][] GEM_ROWS = {{1623, 1607, 20}, {1621, 1605, 27}, {1619, 1603, 34}, {1617, 1601, 43}};
    static final String[] GEM_NAMES = {"SAPPHIRE", "EMERALD", "RUBY", "DIAMOND"};
    /** {grimy, clean, clean level, unfinished, secondary, potion level, product, secondary sold in a shop (1/0)} */
    static final int[][] POT_ROWS = {
            {199, 249, 3, 91, NEWT, 3, 121, 1},     // attack
            {203, 253, 11, 95, LIMP, 12, 115, 0},   // strength
            {207, 257, 25, 99, SNAPE, 38, 139, 0},  // prayer
            {209, 259, 40, 101, NEWT, 45, 145, 1},  // super attack
            {213, 263, 54, 105, LIMP, 55, 157, 0}}; // super strength
    static final String[] UNF_NAMES = {"GUAM", "TARROMIN", "RANARR", "IRIT", "KWUARM"};
    static final String[] FIN_NAMES = {"ATTACK_POTION", "STRENGTH_POTION", "PRAYER_POTION", "SUPER_ATTACK", "SUPER_STRENGTH"};

    static volatile int gemRuns, gemsCut, gemsBought, gemSold, gemSkipLevel, gemSkipNone, gemSkipKit, gemSkipCap, gemGaveUp;
    static volatile int potRuns, potsMade, herbsBought, herbsCleaned, potSold, potSkipLevel, potSkipQuest, potSkipNone, potSkipCap, potGaveUp;
    static volatile int skipBusy, skipNotBank, skipSlots, skipPoor, shopBuys;
    static volatile long spent;
    static final long[] HOUR_START = new long[2]; static final int[] HOUR_RUNS = new int[2];

    // ------------------------------------------------------------------ pure rules (TestW37Craft)

    /** A bot's next try is due (its own clock; 0 = never tried). */
    static boolean due(long now, long nextAt) { return now >= nextAt; }

    /** Hourly window per flag (0 gems, 1 potions): may another run start now. */
    static synchronized boolean capOk(int kind, long now, int perHour) {
        if (now - HOUR_START[kind] >= 3_600_000L) { HOUR_START[kind] = now; HOUR_RUNS[kind] = 0; }
        return HOUR_RUNS[kind] < perHour;
    }
    static synchronized void capUse(int kind) { HOUR_RUNS[kind]++; }

    /** Units of bot stock an adventurer may buy: STOCK_PCT of it, so the artisans keep the rest. */
    static int buyable(int stock, int pct) { return stock <= 0 ? 0 : (int) ((long) stock * pct / 100); }

    /** How many units a purse may pay for with a quarter of its coins (0 below MIN_PURSE). */
    static int afford(long purse, long unit, long minPurse) {
        if (purse < minPurse) return 0;
        return (int) Math.min(Integer.MAX_VALUE, purse / 4 / Math.max(1, unit));
    }

    /** The best gem row for this level with at least min units to hand (own[i] + buy[i]); highest level wins. -1 = none. */
    static int pickGem(int level, int[] own, int[] buy, int min) {
        int best = -1;
        for (int i = 0; i < GEM_ROWS.length; i++) {
            if (GEM_ROWS[i][2] > level || own[i] + buy[i] < min) continue;
            if (best < 0 || GEM_ROWS[i][2] > GEM_ROWS[best][2]) best = i;
        }
        return best;
    }

    /** The best potion row: clean and mix levels met, min herbs and min secondaries to hand; highest potion level wins. */
    static int pickPotion(int level, int[] herbs, int[] secs, int min) {
        int best = -1;
        for (int i = 0; i < POT_ROWS.length; i++) {
            if (POT_ROWS[i][2] > level || POT_ROWS[i][5] > level || herbs[i] < min || secs[i] < min) continue;
            if (best < 0 || POT_ROWS[i][5] > POT_ROWS[best][5]) best = i;
        }
        return best;
    }

    /** Batch size: the smallest limit; under min -> 0. */
    static int batch(int min, int... limits) {
        int n = Integer.MAX_VALUE;
        for (int l : limits) n = Math.min(n, l);
        return n < min ? 0 : n;
    }

    static String line() {
        return "[W37] on=[" + (GEMS ? "gems" : "") + (GEMS && POTIONS ? " " : "") + (POTIONS ? "potions" : "") + "] gemRuns=" + gemRuns + " gemsCut=" + gemsCut
                + " gemsBought=" + gemsBought + " gemSold=" + gemSold + " gemSkip=level:" + gemSkipLevel + ",none:" + gemSkipNone + ",kit:" + gemSkipKit + ",cap:" + gemSkipCap
                + " gemGaveUp=" + gemGaveUp + " potRuns=" + potRuns + " potsMade=" + potsMade + " herbsBought=" + herbsBought + " herbsCleaned=" + herbsCleaned + " potSold=" + potSold
                + " potSkip=level:" + potSkipLevel + ",quest:" + potSkipQuest + ",none:" + potSkipNone + ",cap:" + potSkipCap + " potGaveUp=" + potGaveUp
                + " skip=busy:" + skipBusy + ",notBank:" + skipNotBank + ",slots:" + skipSlots + ",poor:" + skipPoor + " shopBuys=" + shopBuys + " spent=" + spent;
    }

    // ------------------------------------------------------------------ in world

    /** One errand of one bot. kind 0 = gems, 1 = potions. */
    static final class Run {
        int kind, row, n, step, left, stall, made0, bought;
        boolean unfDone, reserved; // review fix 2: reserved = the plan checked the whole batch against the purse, so the run buys skip the per-buy purse rule
        long start, coins0; double xp0; int lvl0;
        Pulse pulse;
    }
    static final int S_CUT = 1, S_CLEAN = 2, S_UNF = 3, S_MIX = 4;

    static int inPack(Player p, int id) { return p.getInventory().getAmount(id); }
    static String nm(int id) { return MarketBrain.name(id).toLowerCase(Locale.ROOT); }

    /** n units into the pack: own bank first, then bot stock (the sellers are paid), then a shop when shopOk. Returns the count now added. */
    static int take(Player p, int id, int n, boolean stockOk, boolean shopOk, Run r) {
        int got = 0;
        if (n <= 0) return 0;
        int bank = Math.min(n, p.getBank().getAmount(id));
        if (bank > 0 && p.getBank().remove(new Item(id, bank))) {
            if (p.getInventory().add(new Item(id, bank))) got += bank; else p.getBank().add(new Item(id, bank));
        }
        if (got < n && stockOk) {
            long unit0 = Math.max(1, MarketBrain.chargePrice(id));
            int want = Math.min(n - got, Math.min(buyable(MarketBrain.stockOf(id), STOCK_PCT), r != null && r.reserved ? (int) Math.min(Integer.MAX_VALUE, Ledger.Wallet.coins(p) / unit0) : afford(Ledger.Wallet.coins(p), unit0, MIN_PURSE)));
            int b = want > 0 ? Ledger.consume(p, id, want, "w37") : 0;
            if (b > 0) {
                if (!p.getInventory().add(new Item(id, b))) { p.getBank().add(new Item(id, b)); System.out.println("[W37] " + p.getUsername() + " could not carry " + b + " bought " + nm(id) + ": banked"); }
                else { got += b; if (r != null) r.bought += b; }
            }
        }
        if (got < n && shopOk) {
            long unit = Math.max(1, MarketBrain.value(id));
            int want = Math.min(n - got, r != null && r.reserved ? (int) Math.min(Integer.MAX_VALUE, Ledger.Wallet.coins(p) / unit) : afford(Ledger.Wallet.coins(p), unit, MIN_PURSE));
            if (want > 0 && Ledger.Wallet.pay(p, unit * want)) {
                if (p.getInventory().add(new Item(id, want))) { got += want; shopBuys += want; }
                else if (!p.getBank().add(new Item(id, want))) W8Polish.unplaced(p, id, want, W8Polish.refundCoins(unit * want, 0, want), "w37 shop");
            }
        }
        return got;
    }

    /** Brain.tick trip-end list, last errand. True = a run started (the brain returns). */
    static boolean plan(SmartAdventurers.Brain b, long now) {
        if (!ANY || b == null || b.bot == null || !b.bot.isArtificial() || b.w37 != null) return false;
        if (!due(now, b.w37At)) return false;
        if (b.busy()) { skipBusy++; return false; }
        if (b.bot.inCombat()) { skipBusy++; return false; }
        if (!b.atBank()) { skipNotBank++; return false; }
        b.w37At = now + RETRY_MS;
        Player p = b.bot;
        if (p.getInventory().freeSlots() < 2 * MIN_N + 1) { skipSlots++; return false; }
        // the two errands take turns per bot (its own counter), so one flag never shadows the other
        boolean gemsFirst = !POTIONS || GEMS && (b.w37Turn++ & 1) == 0;
        Run r = gemsFirst ? planGems(p, now) : planPotions(p, now);
        if (r == null && GEMS && POTIONS) r = gemsFirst ? planPotions(p, now) : planGems(p, now);
        if (r == null) return false;
        r.start = now; b.w37 = r; b.w37At = now + GAP_MS; b.nextThink = now + 2_000;
        try { Bob.leave(p, "busy"); } catch (Throwable ignored) { }
        final int kind = r.kind;
        BotChat.activity(p, () -> kind == 0 ? "cutting gems" : "mixing potions");
        return true;
    }

    static Run planGems(Player p, long now) {
        if (!GEMS) return null;
        int level = p.getSkills().getLevel(Skills.CRAFTING);
        if (level < GEM_ROWS[0][2]) { gemSkipLevel++; return null; }
        if (!capOk(0, now, RUNS_HOUR)) { gemSkipCap++; return null; }
        long purse = Ledger.Wallet.coins(p);
        int[] own = new int[GEM_ROWS.length], buy = new int[GEM_ROWS.length];
        for (int i = 0; i < GEM_ROWS.length; i++) {
            own[i] = p.getBank().getAmount(GEM_ROWS[i][0]) + inPack(p, GEM_ROWS[i][0]);
            buy[i] = MarketBrain.isSurplus(GEM_ROWS[i][1]) ? 0 : Math.min(buyable(MarketBrain.stockOf(GEM_ROWS[i][0]), STOCK_PCT), afford(purse, Math.max(1, MarketBrain.chargePrice(GEM_ROWS[i][0])), MIN_PURSE));
        }
        int row = pickGem(level, own, buy, MIN_N);
        if (row < 0) { gemSkipNone++; if (purse < MIN_PURSE) skipPoor++; return null; }
        if (!chisel(p)) { gemSkipKit++; return null; } // review 5: the chisel before any gem is paid for
        int uncut = GEM_ROWS[row][0];
        int n = batch(MIN_N, GEM_MAX, p.getInventory().freeSlots() + inPack(p, uncut), own[row] + buy[row]);
        if (n <= 0) { skipSlots++; return null; }
        Run r = new Run(); r.kind = 0; r.row = row; r.coins0 = purse;
        int need = n - inPack(p, uncut);
        if (need > 0) take(p, uncut, need, true, false, r);
        r.n = inPack(p, uncut);
        if (r.n < 1) { gemSkipNone++; if (p.getInventory().remove(new Item(CHISEL, 1)) && !p.getBank().add(new Item(CHISEL, 1))) p.getInventory().add(new Item(CHISEL, 1)); return null; }
        r.left = r.n; r.step = S_CUT; r.made0 = inPack(p, GEM_ROWS[row][1]);
        r.xp0 = p.getSkills().getExperience(Skills.CRAFTING); r.lvl0 = p.getSkills().getStaticLevel(Skills.CRAFTING);
        capUse(0); gemRuns++; gemsBought += r.bought; long cost = Math.max(0, purse - Ledger.Wallet.coins(p)); spent += cost;
        System.out.println("[W37] gems " + p.getUsername() + " -> cutting " + r.n + " x " + nm(uncut) + (r.bought > 0 ? " (bought " + r.bought + " for " + cost + " gp)" : " (own)") + ", Crafting " + level);
        return r;
    }

    /** A chisel in the pack: already there, from the bank, else bought at its guide price (coin sink). */
    static boolean chisel(Player p) {
        if (inPack(p, CHISEL) > 0) return true;
        return take(p, CHISEL, 1, true, true, null) > 0;
    }

    static boolean questDone(Player p) {
        try { return p.getQuestRepository().isComplete(content.data.Quests.DRUIDIC_RITUAL); } catch (Throwable t) { return false; }
    }

    /** Coins a potion batch of n must buy: herbs, vials and secondaries the bot does not own, at their prices. */
    static long potCost(int n, int ownHerb, long herbUnit, int ownVial, long vialUnit, int ownSec, long secUnit) {
        return Math.max(0, n - ownHerb) * Math.max(1, herbUnit) + Math.max(0, n - ownVial) * Math.max(1, vialUnit) + Math.max(0, n - ownSec) * Math.max(1, secUnit);
    }

    /** The largest batch <= n (and >= min, else 0) whose purchases fit a quarter of the purse; buying nothing always fits. */
    static int potFit(int n, int min, long purse, long minPurse, int ownHerb, long herbUnit, int ownVial, long vialUnit, int ownSec, long secUnit) {
        for (; n >= min; n--) {
            long c = potCost(n, ownHerb, herbUnit, ownVial, vialUnit, ownSec, secUnit);
            if (c == 0 || purse >= minPurse && c <= purse / 4) return n;
        }
        return 0;
    }

    static Run planPotions(Player p, long now) {
        if (!POTIONS) return null;
        if (!questDone(p)) { potSkipQuest++; return null; }
        int level = p.getSkills().getLevel(Skills.HERBLORE);
        if (level < POT_ROWS[0][5]) { potSkipLevel++; return null; }
        if (!capOk(1, now, RUNS_HOUR)) { potSkipCap++; return null; }
        long purse = Ledger.Wallet.coins(p);
        int[] herbs = new int[POT_ROWS.length], secs = new int[POT_ROWS.length], ownH = new int[POT_ROWS.length];
        int resume = -1;
        for (int i = 0; i < POT_ROWS.length; i++) {
            int[] w = POT_ROWS[i];
            // review fix 2: clean herbs and unfinished potions an earlier run left in the bank count as herbs in hand
            ownH[i] = p.getBank().getAmount(w[0]) + p.getBank().getAmount(w[1]) + p.getBank().getAmount(w[3]);
            boolean left = p.getBank().getAmount(w[1]) + p.getBank().getAmount(w[3]) > 0;
            boolean surplus = MarketBrain.isSurplus(w[6]);
            if (surplus && !left) continue; // the market has enough of this potion (leftovers are still finished)
            herbs[i] = ownH[i] + (surplus ? 0 : buyable(MarketBrain.stockOf(w[0]), STOCK_PCT));
            secs[i] = p.getBank().getAmount(w[4]) + buyable(MarketBrain.stockOf(w[4]), STOCK_PCT) + (w[7] == 1 ? POT_MAX : 0);
            if (left && w[5] <= level && secs[i] > 0) resume = i; // leftovers first, before any new herb is bought
        }
        int min = resume >= 0 ? 1 : MIN_N;
        int row = resume >= 0 ? resume : pickPotion(level, herbs, secs, MIN_N);
        if (row < 0) { potSkipNone++; if (purse < MIN_PURSE) skipPoor++; return null; }
        int[] w = POT_ROWS[row];
        int ownVial = p.getBank().getAmount(VIAL) + p.getBank().getAmount(w[3]), ownSec = p.getBank().getAmount(w[4]); // an unfinished potion needs no vial
        int n = batch(min, POT_MAX, p.getInventory().freeSlots() / 2, herbs[row], secs[row]);
        // review fix 2: the whole batch (herbs + vials + secondaries to buy) must fit a quarter of the purse before a herb is bought
        n = potFit(n, min, purse, MIN_PURSE, ownH[row], MarketBrain.chargePrice(w[0]), ownVial, Math.max(MarketBrain.chargePrice(VIAL), MarketBrain.value(VIAL)),
                ownSec, Math.max(MarketBrain.chargePrice(w[4]), MarketBrain.value(w[4])));
        if (n <= 0) { skipPoor++; return null; }
        Run r = new Run(); r.kind = 1; r.row = row; r.coins0 = purse; r.reserved = true;
        int got = take(p, w[3], n, false, false, r);                 // unfinished potions left over
        got += take(p, w[1], n - got, false, false, r);              // clean herbs left over
        if (got < n) got += take(p, w[0], n - got, true, false, r);  // grimy: bank, then bot stock
        r.n = got;
        if (r.n < 1) { potSkipNone++; return null; }
        r.made0 = inPack(p, w[6]); r.left = inPack(p, w[0]); r.step = S_CLEAN; // no grimy in the pack: the first think moves on to the vials or the secondary
        r.xp0 = p.getSkills().getExperience(Skills.HERBLORE); r.lvl0 = p.getSkills().getStaticLevel(Skills.HERBLORE);
        capUse(1); potRuns++; herbsBought += r.bought;
        System.out.println("[W37] potions " + p.getUsername() + " -> cleaning " + inPack(p, w[0]) + " x " + nm(w[0]) + (r.bought > 0 ? " (bought " + r.bought + ")" : " (own)")
                + (inPack(p, w[1]) + inPack(p, w[3]) > 0 ? " and resuming " + inPack(p, w[1]) + " clean, " + inPack(p, w[3]) + " unfinished" : "") + " for " + nm(w[6]) + ", Herblore " + level);
        return r;
    }

    /** Brain.tick while b.w37 is set: one step a think. */
    static void tick(SmartAdventurers.Brain b, long now) {
        Run r = b.w37; Player p = b.bot;
        if (r == null) return;
        if (now - r.start > RUN_MS) { end(b, true, "time"); return; }
        if (p.inCombat()) { end(b, true, "attacked"); return; }
        b.nextThink = now + 1_200;
        if (r.step == S_CLEAN) { cleanStep(b, r, p); return; }
        int input = r.kind == 0 ? GEM_ROWS[r.row][0] : r.step == S_UNF ? POT_ROWS[r.row][1] : POT_ROWS[r.row][3];
        int left = inPack(p, input);
        if (left <= 0) { next(b, r, p); return; }
        if (left < r.left) { r.left = left; r.stall = 0; return; }
        boolean running = r.pulse != null && r.pulse.isRunning();
        if (!running && r.kind == 1 && inPack(p, r.step == S_UNF ? VIAL : POT_ROWS[r.row][4]) <= 0) { next(b, r, p); return; } // review 3: fewer vials or secondaries than herbs: move on, the rest is banked for the next run
        if (++r.stall > 15) { end(b, true, "stalled"); return; }
        if (!running && (r.stall == 1 || r.stall == 8)) startPulse(r, p, left);
    }

    /** One grimy herb a think through the engine's "clean" listener (its xp, its level and quest checks). */
    static void cleanStep(SmartAdventurers.Brain b, Run r, Player p) {
        if (p.getLocks().isInteractionLocked()) return;
        int grimy = POT_ROWS[r.row][0];
        int left = inPack(p, grimy);
        if (left <= 0) { next(b, r, p); return; }
        if (left < r.left) { r.left = left; r.stall = 0; } else if (++r.stall > 15) { end(b, true, "clean stalled"); return; }
        for (Item it : p.getInventory().toArray()) {
            if (it == null || it.getId() != grimy) continue;
            if (!InteractionListeners.run(grimy, IntType.ITEM, "clean", p, it)) { end(b, true, "no clean option"); return; }
            if (inPack(p, grimy) < left) herbsCleaned++;
            return;
        }
    }

    /** The step's input is used up: fetch the next input and start its pulse, or finish. */
    static void next(SmartAdventurers.Brain b, Run r, Player p) {
        if (r.kind == 0) { end(b, false, ""); return; }
        if (r.step == S_MIX) { boolean none = inPack(p, POT_ROWS[r.row][6]) <= r.made0; end(b, none, none ? "nothing mixed" : ""); return; }
        int[] w = POT_ROWS[r.row];
        if (r.step == S_CLEAN) {
            int clean = inPack(p, w[1]);
            if (clean < 1 && inPack(p, w[3]) < 1) { end(b, true, "no clean herbs"); return; }
            if (clean > 0) take(p, VIAL, clean - inPack(p, VIAL), true, true, r);
            if (clean > 0 && inPack(p, VIAL) < 1 && inPack(p, w[3]) < 1) { end(b, true, "no vials"); return; }
            r.step = S_UNF;
            if (clean > 0 && inPack(p, VIAL) > 0) { r.left = clean + 1; r.stall = 0; r.pulse = null; return; }
            // resumed unfinished potions only (or no vial for the clean ones): straight on to the secondary
        }
        int unf = inPack(p, w[3]);
        if (unf < 1) { end(b, true, "no unfinished potions"); return; }
        take(p, w[4], unf - inPack(p, w[4]), true, w[7] == 1, r);
        if (inPack(p, w[4]) < 1) { end(b, true, "no " + nm(w[4])); return; }
        r.step = S_MIX; r.left = unf + 1; r.stall = 0; r.pulse = null;
    }

    static void startPulse(Run r, Player p, int left) {
        try {
            if (r.kind == 0) {
                Gems g = Gems.valueOf(GEM_NAMES[r.row]);
                r.pulse = new GemCutPulse(p, new Item(GEM_ROWS[r.row][0]), left, g);
            } else {
                int[] w = POT_ROWS[r.row];
                boolean unf = r.step == S_UNF;
                GenericPotion gp = unf ? GenericPotion.transform(UnfinishedPotion.valueOf(UNF_NAMES[r.row])) : GenericPotion.transform(FinishedPotion.valueOf(FIN_NAMES[r.row]));
                int n = Math.min(left, inPack(p, unf ? VIAL : w[4]));
                if (n < 1) return;
                r.pulse = new HerblorePulse(p, new Item(unf ? VIAL : w[3]), n, gp);
            }
            p.getPulseManager().run(r.pulse);
        } catch (Throwable t) { System.out.println("[W37] pulse for " + p.getUsername() + ": " + t); }
    }

    /** Ends the run: products go on sale as bot stock (banked while the market is in surplus), everything else to the bank. */
    static void end(SmartAdventurers.Brain b, boolean gaveUp, String why) {
        Run r = b.w37; Player p = b.bot;
        if (r == null) return;
        b.w37 = null;
        try { if (r.pulse != null && r.pulse.isRunning()) r.pulse.stop(); } catch (Throwable ignored) { }
        int product = r.kind == 0 ? GEM_ROWS[r.row][1] : POT_ROWS[r.row][6];
        int made = Math.max(0, inPack(p, product) - r.made0), sold = 0;
        if (made > 0 && p.getInventory().remove(new Item(product, made))) {
            if (!MarketBrain.isSurplus(product)) { try { Ledger.supply(p, product, made); sold = made; } catch (Throwable t) { if (!p.getBank().add(new Item(product, made))) p.getInventory().add(new Item(product, made)); } }
            else if (!p.getBank().add(new Item(product, made))) p.getInventory().add(new Item(product, made));
        }
        int[] rest = r.kind == 0 ? new int[]{GEM_ROWS[r.row][0], CHISEL} : new int[]{POT_ROWS[r.row][0], POT_ROWS[r.row][1], POT_ROWS[r.row][3], POT_ROWS[r.row][4], VIAL};
        for (int id : rest) {
            int k = inPack(p, id);
            if (k > 0 && p.getInventory().remove(new Item(id, k)) && !p.getBank().add(new Item(id, k))) p.getInventory().add(new Item(id, k));
        }
        int skill = r.kind == 0 ? Skills.CRAFTING : Skills.HERBLORE;
        double xp = p.getSkills().getExperience(skill) - r.xp0;
        long cost = Math.max(0, r.coins0 - Ledger.Wallet.coins(p));
        if (r.kind == 0) { gemsCut += made; gemSold += sold; if (gaveUp) gemGaveUp++; }
        else { potsMade += made; potSold += sold; spent += cost; if (gaveUp) potGaveUp++; }
        System.out.println("[W37] " + (r.kind == 0 ? "gems " : "potions ") + p.getUsername() + (gaveUp ? " gave up (" + why + "), " : " ") + (r.kind == 0 ? "cut " : "made ") + made + " x " + nm(product)
                + String.format(Locale.ROOT, " (+%.1f xp), %s %d->%d, sold %d, spent %d gp", xp, r.kind == 0 ? "crafting" : "herblore", r.lvl0, p.getSkills().getStaticLevel(skill), sold, cost));
        BotChat.clearActivity(p);
    }
}
