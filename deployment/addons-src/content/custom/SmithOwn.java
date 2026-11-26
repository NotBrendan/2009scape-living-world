package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.world.map.Location;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * W8 wave 4 lane B item 1 (the design notes section 4, Smithing row): -Dbots.smith.own, default OFF.
 *
 * A bot on a Batch29 mine -> smelt -> bank chain gets a third step: after the furnace it walks to the nearest anvil and
 * smiths its own bars into the best item its Smithing level allows, with the engine's SmithingPulse (the same pulse a
 * player's anvil click starts: bars out, item in, the engine's xp). The Runner's own Process.SMITH chain does the walking
 * (startProcess -> atStation(ANVIL) -> processStep); only the make step is swapped from makeOne to the engine pulse, and
 * only for runners this class started.
 *
 * Demand rule (the artisan smiths sell the same goods): an item is chosen only while the market is not in surplus of it
 * and holds under half its artisan target (-Dbots.smith.own.share, 0.5), so bots fill the bottom of the shelf and the
 * artisans keep the top. Items queued are capped server-wide per hour (-Dbots.smith.own.perHour, 300).
 *
 * Economy: nothing is created. Bars come from the bot's own ore through the engine's SmeltingPulse; items from those bars
 * through SmithingPulse. The hammer comes from the bot's bank, else is bought at its guide price (Wallet.pay: a coin sink,
 * the shop pattern the tools and the W5a tinderbox use).
 *
 * With the flag off nothing here runs: the two hooks (Batch29.Chains.apply, AfkTasks.Runner.processStep) test ON first.
 */
final class SmithOwn {
    private SmithOwn() { }

    static final boolean ON = Boolean.getBoolean("bots.smith.own");
    static final int HAMMER = 2347;
    static final int PER_HOUR = Math.max(1, Integer.getInteger("bots.smith.own.perHour", 300));
    static final double SHARE = parse(System.getProperty("bots.smith.own.share"), 0.5); // t63 fix 6: a bad value falls back to 0.5 (it used to throw at class load, flag on or off)
    static double parse(String s, double d) { try { if (s == null) return d; double v = Double.parseDouble(s.trim()); return Double.isNaN(v) ? d : v; } catch (Throwable t) { return d; } }
    /** Engine smithing items with a quest requirement in SmithingPulse.checkRequirements (dart tips: Tourist Trap; claws: Death Plateau). */
    static final String[] QUEST_WORDS = {"dart", "claw"};

    static volatile int starts, refusedDemand, refusedKit, refusedAnvil, refusedCap, hammersBought, hammersBank, pulses, queued, pulseFail;
    static volatile long hourStart; static volatile int hourQueued;
    /** Runners that smith their own bars with the engine pulse (identity). */
    static final Set<AfkTasks.Runner> RUNS = ConcurrentHashMap.newKeySet();
    /** Runners whose pulse refused to start once: their remaining steps use the Runner's own makeOne. */
    static final Set<AfkTasks.Runner> FALLBACK = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ pure rules (TestW8Wave4b)

    /** Bots may make this item: market not in surplus of it and its stock under SHARE of the artisan target. */
    static boolean demandOk(int stock, int target, boolean surplus, double share) {
        return !surplus && target > 0 && stock < target * share;
    }

    /** rows {bar, level, product, barsPerItem}: the index of the best row for this bar at this level whose product passes ok
     *  (highest level, then most bars per item), or -1. */
    static int pick(int[][] rows, int bar, int level, IntPredicate ok) {
        int best = -1;
        for (int i = 0; i < rows.length; i++) {
            int[] r = rows[i];
            if (r[0] != bar || r[1] > level || !ok.test(r[2])) continue;
            if (best < 0 || r[1] > rows[best][1] || r[1] == rows[best][1] && r[3] > rows[best][3]) best = i;
        }
        return best;
    }

    static boolean questItem(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String w : QUEST_WORDS) if (n.contains(w)) return true;
        return false;
    }

    /** Hourly window: may another n items be queued now. */
    static synchronized boolean capOk(long now, int n) {
        if (now - hourStart >= 3_600_000L) { hourStart = now; hourQueued = 0; }
        return hourQueued + n <= PER_HOUR;
    }
    static synchronized void capUse(int n) { hourQueued += n; }

    static String line() {
        return "[SmithOwn] on=" + ON + " starts=" + starts + " refusedDemand=" + refusedDemand + " refusedKit=" + refusedKit + " refusedAnvil=" + refusedAnvil
                + " refusedCap=" + refusedCap + " hammersBank=" + hammersBank + " hammersBought=" + hammersBought + " pulses=" + pulses + " queued=" + queued
                + " pulseFail=" + pulseFail + " hourQueued=" + hourQueued + "/" + PER_HOUR + " running=" + RUNS.size();
    }

    // ------------------------------------------------------------------ in world

    /** The recipe a bot smiths from this bar, or null (no level, no demand). */
    static AfkTasks.Rec choose(Player p, int bar) {
        java.util.List<AfkTasks.Rec> all = AfkTasks.recipes(AfkTasks.Process.SMITH);
        java.util.List<AfkTasks.Rec> use = new java.util.ArrayList<>();
        for (AfkTasks.Rec r : all) {
            if (r.in.length != 1 || questItem(r.name)) continue;
            content.global.skill.smithing.Bars b = content.global.skill.smithing.Bars.forId(r.product);
            if (b == null || b.getLevel() != r.level) continue; // the engine pulse must make exactly this item at this level
            use.add(r);
        }
        int[][] rows = new int[use.size()][];
        for (int i = 0; i < rows.length; i++) { AfkTasks.Rec r = use.get(i); rows[i] = new int[]{r.in[0], r.level, r.product, r.inAmt[0]}; }
        int i = pick(rows, bar, p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.SMITHING),
                id -> demandOk(MarketBrain.stockOf(id), Artisans.target(id), MarketBrain.isSurplus(id), SHARE));
        return i < 0 ? null : use.get(i);
    }

    /** Batch29.Chains.apply, after a SMELT chain was applied: turn it into mine -> smelt -> smith -> bank when the bot can. */
    static void apply(AfkTasks.Runner r, Player p, AfkTasks.Task task, Location spotAt) {
        if (!ON || r == null || p == null || task == null) return;
        RUNS.removeIf(x -> now0() - x.startedAt > 4 * 3_600_000L); FALLBACK.retainAll(RUNS); // a runner halted without Batch29's done branch
        content.global.skill.smithing.smelting.Bar sb = AfkTasks.barToMineFor(p, task); // the bar the Runner's smelt haul will make (planSmeltHaul)
        int bar = sb == null ? -1 : sb.getProduct().getId();
        if (bar <= 0) { refusedDemand++; return; }
        long now = System.currentTimeMillis();
        if (!capOk(now, 1)) { refusedCap++; return; }
        AfkTasks.Rec rec = choose(p, bar);
        if (rec == null) { refusedDemand++; return; }
        Location[] legs = Batch29.Chains.legsFrom(spotAt, Batch29.Chains.SMELT);
        Location anvil = legs == null ? null : AfkTasks.stationNear(AfkTasks.Station.ANVIL, legs[1]);
        if (anvil == null || anvil.getDistance(legs[1]) > Batch29.Chains.LEG_MAX || anvil.getDistance(legs[2]) > Batch29.Chains.LEG_MAX || !Gates.canEnter(p, anvil)) { refusedAnvil++; return; }
        if (!hammer(p)) { refusedKit++; return; }
        r.chain(AfkTasks.Process.SMITH, rec.name);
        r.sell = false; // chain() leaves sell alone; Batch29 set it false: bank the goods
        RUNS.add(r); FALLBACK.remove(r);
        starts++;
        System.out.println("[SmithOwn] " + p.getUsername() + " smiths " + rec.name + " from " + MarketBrain.name(bar) + " at the anvil " + anvil.getX() + "," + anvil.getY()
                + " (Smithing " + p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.SMITHING) + ", stock " + MarketBrain.stockOf(rec.product) + "/" + Artisans.target(rec.product) + ")");
    }

    /** A hammer in the pack: already there, from the bank, else bought at its guide price. */
    static boolean hammer(Player p) {
        if (p.getInventory().getAmount(HAMMER) > 0) return true;
        if (p.getInventory().freeSlots() < 1) return false;
        if (p.getBank().getAmount(HAMMER) > 0) {
            if (p.getBank().remove(new Item(HAMMER, 1))) {
                if (p.getInventory().add(new Item(HAMMER, 1))) { hammersBank++; return true; }
                p.getBank().add(new Item(HAMMER, 1));
            }
            return false;
        }
        long cost = Math.max(1, MarketBrain.value(HAMMER));
        if (Ledger.Wallet.coins(p) < cost * 20) return false; // a hammer is not worth the last of a poor bot's coins
        if (!Ledger.Wallet.pay(p, cost)) return false;
        if (!p.getInventory().add(new Item(HAMMER, 1)) && !p.getBank().add(new Item(HAMMER, 1))) { W8Polish.unplaced(p, HAMMER, 1, W8Polish.refundCoins(cost, 0, 1), "smith-own hammer"); return false; } // t63 fix 3: coins back
        hammersBought++;
        return p.getInventory().getAmount(HAMMER) > 0;
    }

    /** Runner.processStep at the anvil: the engine's SmithingPulse for this runner's recipe. False = use makeOne. */
    static boolean pulse(AfkTasks.Runner r, AfkTasks.Rec rec) {
        if (!ON || !RUNS.contains(r) || FALLBACK.contains(r)) return false;
        Player p = r.p;
        content.global.skill.smithing.Bars b = content.global.skill.smithing.Bars.forId(rec.product);
        int n = rec.setsIn(p.getInventory());
        if (b == null || n <= 0) return false;
        if (!capOk(System.currentTimeMillis(), n)) { n = Math.max(0, PER_HOUR - hourQueued); if (n <= 0) { refusedCap++; r.afterProcess(); return true; } } // over the hour's cap: bank the bars (never the uncapped makeOne)
        p.getPulseManager().run(new content.global.skill.smithing.SmithingPulse(p, new Item(rec.product), b, n));
        if (!p.getPulseManager().hasPulseRunning()) {
            pulseFail++; FALLBACK.add(r);
            System.out.println("[SmithOwn] " + p.getUsername() + " anvil pulse didn't start for " + rec.name + "; the runner makes them itself");
            return false;
        }
        capUse(n); pulses++; queued += n;
        r.last = "smithing " + n + " " + rec.name;
        return true;
    }

    /** The runner ended (Batch29.Chains.done). */
    static long now0() { return System.currentTimeMillis(); }
    static void done(AfkTasks.Runner r) { if (r != null) { RUNS.remove(r); FALLBACK.remove(r); } }
}
