package content.custom;

import content.global.skill.fletching.log.CraftItemWithLogScript;
import content.global.skill.fletching.log.LogCraftInfo;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;

import java.util.Locale;

/**
 * W7 skills, wave 4 lane "chains": the engine side of W7cSkills (packs, banks, the Ledger, the engine's fletching script).
 * Every method here is called only behind its W7cSkills flag; the rules and the counters are in W7cSkills.
 * Nothing is minted: logs and nails come out of bot stock through Ledger.consume (which pays the sellers), the knife is
 * paid for like the bots' other shop tools, and the fletching is the engine's own log -> product action.
 */
final class W7cHooks {
    private W7cHooks() { }

    // ------------------------------------------------------------------ Construction: bots.plankTypes

    static int nailsIn(core.game.container.Container c) { int n = 0; for (int id : W7cSkills.NAILS) n += c.getAmount(id); return n; }

    /**
     * planPlankRun's last resort (no oak, teak or mahogany the builder may use and can get): a run of plain planks, which
     * need a nail each. Logs from the bank, else 27 from the Exchange; nails from the bank, else from the smiths' stock
     * (both through the Ledger, at the GE). True = the run is set up like any other plank run (millTick takes it from here).
     */
    /**
     * w22: planPlankRun found no plank run and the builder is away from the Exchange (live 11-21/22: 8 of 9 plank rolls, so
     * plainRun never had logs or nails to buy and the flag printed nothing in a day). When the plain run could be made at
     * the Exchange as stock and purse stand now, walk there (the shop walk every Exchange supply trip uses); atExchange
     * makes the run on arrival. No random draw; nothing is bought until the bot stands at the Exchange.
     */
    static boolean geTrip(SmartAdventurers.Brain b, Player bot, long now, long coins) {
        if (b.shopAt != null || b.w7PlankTrip > now || SmartAdventurers.nearGE(bot) || bot.getLocation().getZ() != 0) return false;
        if (P90.climbOut(bot.getLocation(), SmartAdventurers.GRAND_EXCHANGE) != null) return false; // not from inside a dungeon
        int[] ns = new int[W7cSkills.NAILS.length], np = new int[W7cSkills.NAILS.length];
        for (int i = 0; i < ns.length; i++) { ns[i] = MarketBrain.stockOf(W7cSkills.NAILS[i]); np[i] = Math.max(1, MarketBrain.value(W7cSkills.NAILS[i])); }
        int bankLogs = bot.getBank().getAmount(W7cSkills.PLAIN_LOG), bankNails = nailsIn(bot.getBank());
        if (!W7cSkills.tripWorth(bankLogs, bankNails, MarketBrain.stockOf(W7cSkills.PLAIN_LOG), Math.max(1, MarketBrain.value(W7cSkills.PLAIN_LOG)), ns, np, coins, 1_500)) return false;
        int h = Math.floorMod(bot.getUsername().hashCode(), 25);
        b.shopAt = SmartAdventurers.GRAND_EXCHANGE.transform(h % 5 - 2, h / 5 - 2, 0); b.shopName = "the Grand Exchange";
        b.shopBuy.clear(); b.pendingJob = null; b.exchangeTrip = false; b.goalTrip = false; b.armourTrip = false;
        b.shopDeadline = now + (SmartAdventurers.FAST ? 180_000 : 480_000); b.shopBest = Double.MAX_VALUE; b.shopStalls = 0;
        b.w7PlankTrip = b.shopDeadline; W7cSkills.geTrips++;
        BotChat.activity(bot, () -> "off to the Grand Exchange for logs and nails");
        System.out.println("[SmartAdv] (W7c) " + bot.getUsername() + " -> the Grand Exchange for a plain plank run (logs in bank " + bankLogs + ", nails in bank " + bankNails + ", " + coins + " gp)");
        return true;
    }

    /** w22: finishShop's first step while a geTrip is open. True = this was that walk's arrival (the run is set up, or its "can't" line printed); false = some other shop, or too late: the usual finishShop. */
    static boolean atExchange(SmartAdventurers.Brain b, Player bot, long now) {
        long until = b.w7PlankTrip; b.w7PlankTrip = 0;
        if (now > until + 60_000 || !SmartAdventurers.nearGE(bot) || !"the Grand Exchange".equals(b.shopName) || !b.shopBuy.isEmpty() || b.pendingJob != null) return false;
        b.shopAt = null; b.nextThink = now + 3_000;
        if (!plainRun(b, bot, now, Ledger.Wallet.coins(bot))) BotChat.clearActivity(bot);
        return true;
    }

    static boolean plainRun(SmartAdventurers.Brain b, Player bot, long now, long coins) {
        boolean atGE = SmartAdventurers.nearGE(bot);
        int[] ns = new int[W7cSkills.NAILS.length], np = new int[W7cSkills.NAILS.length];
        for (int i = 0; i < ns.length; i++) { ns[i] = MarketBrain.stockOf(W7cSkills.NAILS[i]); np[i] = Math.max(1, MarketBrain.value(W7cSkills.NAILS[i])); }
        int bankLogs = bot.getBank().getAmount(W7cSkills.PLAIN_LOG), bankNails = nailsIn(bot.getBank());
        int[] plan = W7cSkills.plainPlan(bankLogs, bankNails, atGE, MarketBrain.stockOf(W7cSkills.PLAIN_LOG), Math.max(1, MarketBrain.value(W7cSkills.PLAIN_LOG)), ns, np, coins, 1_500);
        if (plan == null) {
            if (bankLogs >= W7cSkills.PLANK_MIN || atGE) { W7cSkills.noNails++; System.out.println("[SmartAdv] (W7c) " + bot.getUsername() + " wants a plain plank run but can't (logs in bank " + bankLogs + ", nails in bank " + bankNails + ", nails on the Exchange " + java.util.Arrays.toString(ns) + ", at the GE " + atGE + ", " + coins + " gp)"); }
            return false;
        }
        int logs = plan[0], boughtNails = 0;
        if (plan[2] >= 0 && plan[3] > 0) { // the nails first: without them the logs are not bought
            int id = W7cSkills.NAILS[plan[2]];
            int got = Ledger.consume(bot, id, plan[3], "nails");
            if (got > 0) { Item it = new Item(id, got); if (!bot.getBank().add(it)) SmartAdventurers.Brain.packOrBank(bot, it); boughtNails = got; W7cSkills.nailsBought += got; }
            logs = Math.min(logs, (nailsIn(bot.getBank()) + nailsIn(bot.getInventory())) / W7cSkills.NAILS_PER);
            if (logs < W7cSkills.PLANK_MIN) { W7cSkills.noNails++; return false; }
        }
        if (plan[1] > 0) {
            int got = Ledger.consume(bot, W7cSkills.PLAIN_LOG, Math.min(plan[1], logs), "planks");
            if (got <= 0) return false;
            bot.getBank().add(new Item(W7cSkills.PLAIN_LOG, got));
            logs = Math.min(logs, bot.getBank().getAmount(W7cSkills.PLAIN_LOG));
        }
        if (logs < 1) return false;
        b.millLog = W7cSkills.PLAIN_LOG; b.millPlank = W7cSkills.PLAIN_PLANK; b.millCount = logs;
        b.mill = SmartAdventurers.Brain.MILL_WALK; b.millStart = now;
        W7cSkills.plainRuns++;
        BotChat.activity(bot, () -> "taking logs to the sawmill");
        System.out.println("[SmartAdv] " + bot.getUsername() + " -> sawmill with " + logs + " x logs (W7c plain planks, " + (nailsIn(bot.getBank()) + nailsIn(bot.getInventory())) + " nails held, " + boughtNails + " bought)");
        return true;
    }

    /**
     * The build step: how many of `build` planks can be built, taking their nails (plain planks only) out of the bank and
     * the pack, cheapest metal first. Planks without a nail stay in the bank for the next run.
     */
    static int useNails(Player bot, int plank, int build) {
        int per = W7cSkills.nailsPer(true, plank);
        if (build > 0) W7cSkills.typedBuilds++;
        if (per <= 0 || build <= 0) return Math.max(0, build);
        int can = W7cSkills.buildable(build, nailsIn(bot.getBank()) + nailsIn(bot.getInventory()), per);
        int left = can * per;
        for (int id : W7cSkills.NAILS) {
            for (core.game.container.Container c : new core.game.container.Container[]{bot.getBank(), bot.getInventory()}) {
                int n = Math.min(left, c.getAmount(id));
                if (n > 0 && c.remove(new Item(id, n))) left -= n;
            }
        }
        int removed = can * per - left; // a remove that failed: build only what was paid for in nails
        W7cSkills.nailsUsed += removed;
        return removed / per;
    }

    // ------------------------------------------------------------------ Fletching: bots.fletch.own

    /**
     * Job start, a woodcutting job that is not an errand: the bot carries a knife (its own from the pack or the bank, else
     * bought at the shop price like its other tools: coins out, no mint). With one in the pack the runner may fletch.
     */
    static void fletchKit(Player p, AfkTasks.Runner r) {
        boolean had = p.getInventory().getAmount(W7cSkills.KNIFE) > 0;
        if (!had && p.getInventory().freeSlots() >= 1) {
            if (p.getBank().getAmount(W7cSkills.KNIFE) > 0) { if (p.getBank().remove(new Item(W7cSkills.KNIFE)) && !p.getInventory().add(new Item(W7cSkills.KNIFE))) p.getBank().add(new Item(W7cSkills.KNIFE)); }
            else if (Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(W7cSkills.KNIFE)))) { if (!p.getInventory().add(new Item(W7cSkills.KNIFE))) p.getBank().add(new Item(W7cSkills.KNIFE)); W7cSkills.fletchKnives++; }
            if (p.getInventory().getAmount(W7cSkills.KNIFE) > 0) r.baseline.merge(W7cSkills.KNIFE, 1, Integer::sum); // the runner's "already had" list was taken before the knife: never sold or counted as haul
        }
        if (p.getInventory().getAmount(W7cSkills.KNIFE) > 0) r.w7Fletch = 1;
    }

    /**
     * A full pack on a woodcutting trip (r.w7Fletch: 0 not a fletcher, 1 ready, 2 the engine script is carving, 3 done for
     * this pack; deposit() sets 3 back to 1). True = the runner waits this tick; false = carry on (the bank).
     */
    static boolean fletch(AfkTasks.Runner r) {
        Player p = r.p;
        if (r.w7Fletch == 2) {
            // the script is queued a tick before it runs: wait the first ticks out, then for as long as it is carving (3 ticks a log, with slack)
            int el = core.game.world.GameWorld.getTicks() - r.w7FletchTick, per = 1; for (int[] f : W7cSkills.FLETCH) if (f[1] == r.w7FletchId) per = f[3];
            boolean all = p.getInventory().getAmount(r.w7FletchId) - r.w7FletchHad >= r.w7FletchLogs * per;
            if (!all && (el < 4 || r.busy() && el < 3 * r.w7FletchLogs + 12)) { r.last = "fletching " + MarketBrain.name(r.w7FletchId); return true; }
            fletchDone(r);
            return false;
        }
        if (r.w7Fletch != 1) return false;
        r.w7Fletch = 3; // one try per pack, whatever comes of it
        if (p.getInventory().getAmount(W7cSkills.KNIFE) <= 0) return false;
        int level = p.getSkills().getLevel(Skills.FLETCHING);
        for (int log : W7cSkills.FLETCH_LOGS) {
            int logs = p.getInventory().getAmount(log);
            int n = W7cSkills.fletchCount(logs, W7cSkills.FLETCH_SHARE);
            if (n <= 0) continue;
            int row = W7cSkills.fletchPick(log, level, MarketBrain::stockOf, W7cSkills.FLETCH_MAX_STOCK);
            if (row < 0) { W7cSkills.fletchNoPick++; continue; }
            int[] f = W7cSkills.FLETCH[row];
            try {
                p.getPulseManager().clear();
                new CraftItemWithLogScript(p, LogCraftInfo.valueOf(W7cSkills.FLETCH_INFO[row]), n).invoke(); // the engine's own carve: a log out, the product in, its XP, 3 ticks each
            } catch (Throwable t) { System.out.println("[W7c] fletch " + p.getUsername() + ": " + t); return false; }
            r.w7Fletch = 2; r.w7FletchId = f[1]; r.w7FletchHad = p.getInventory().getAmount(f[1]); r.w7FletchLogs = n; r.w7FletchTick = core.game.world.GameWorld.getTicks();
            r.last = "fletching " + MarketBrain.name(f[1]); r.wait = 1;
            W7cSkills.fletchRuns++;
            return true;
        }
        return false;
    }

    /** The carve has ended (or the trip went to the bank under it): count what was made, once. */
    static void fletchDone(AfkTasks.Runner r) {
        if (r.w7Fletch != 2) return;
        r.w7Fletch = 3;
        Player p = r.p;
        int made = Math.max(0, p.getInventory().getAmount(r.w7FletchId) - r.w7FletchHad);
        int[] row = null; for (int[] f : W7cSkills.FLETCH) if (f[1] == r.w7FletchId) row = f;
        int used = row == null ? 0 : Math.min(r.w7FletchLogs, (made + row[3] - 1) / row[3]);
        W7cSkills.fletchMade += made; W7cSkills.fletchLogs += used;
        r.lastCount = r.countProducts(); // the logs that went into the knife are not a loss the watchdog should see
        System.out.println("[W7c] fletch " + p.getUsername() + " made " + made + " x " + MarketBrain.name(r.w7FletchId).toLowerCase(Locale.ROOT) + " from " + used + " of " + r.w7FletchLogs + " logs before banking (Fletching " + p.getSkills().getStaticLevel(Skills.FLETCHING) + ")");
    }
}
