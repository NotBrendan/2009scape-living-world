package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P78 (master plan C6, from batch 29 cb4f786; design: the design notes (not published) sections 0 and 1): skilling chains with banking.
 * A gather job may run as a chain: mine -> smelt -> bank, or fish -> cook -> bank (the Runner's own smeltHaul / cookHaul paths,
 * delivered to the bank instead of sold raw). Scored in chooseJob as the raw job x 0.9 x demand. The GE ring and quest pairs of
 * batch 29 are not ported.
 * Money: none. Chains use the Runner's deposit; no Ledger, Wallet or reward call. Every hook is one guarded line in try/catch.
 * Switches: -Dbots.chains=true (default false = off, host paths unchanged), -Dbatch29=false (master), data/batch29.off
 * (checked at startup and every 60 s; off until restart). Knobs: -Dchains.share (0.10), -Dchains.legMax (150: fits the Catherby cage/harpoon and Lumbridge lure chains; mining chains need more and their bars sell under the ore),
 * -Db29.hourTicks (the hourly "[B29] chains" line, 6000).
 */
public final class Batch29 implements StartupListener {
    static final boolean MASTER = !"false".equalsIgnoreCase(System.getProperty("batch29", "true"));
    static final int CHAINS = 0;
    /** The part switch, read once at startup; default false. */
    static final boolean[] PARTS = {Boolean.getBoolean("bots.chains")};
    static final String OFF_FILE = "batch29.off";
    static final int HOUR_TICKS = Math.max(100, Integer.getInteger("b29.hourTicks", 6000));
    static volatile boolean stopped;
    static volatile String stopWhy;
    static final int[] LINES = new int[1]; // chat lines said (chains say nothing; kept for the kill-switch check)

    static boolean on(int part) { return MASTER && !stopped && PARTS[part]; }
    static boolean anyOn() { return on(CHAINS); }
    static void log(String s) { System.out.println("[B29] " + s); }
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }
    static int lines() { return LINES[0]; }
    static File offFile() { return new File(ServerConstants.DATA_PATH, OFF_FILE); }
    static double dbl(String k, double d) { try { return Double.parseDouble(System.getProperty(k, String.valueOf(d))); } catch (Throwable t) { return d; } }

    @Override public void startup() {
        if (!MASTER) { log("off (-Dbatch29=false)"); return; }
        if (!anyOn()) return; // chains off (the default): no pulse, no line
        if (offFile().exists()) { stop(OFF_FILE + " present"); return; }
        GameWorld.getPulser().submit(new Pulse(100) { // 60 s: the off file
            @Override public boolean pulse() {
                if (Batch29.stopped) return true;
                try { if (Batch29.offFile().exists()) { Batch29.stop(OFF_FILE + " present"); return true; } } catch (Throwable t) { Batch29.log("pulse: " + t); }
                return false;
            }
        });
        GameWorld.getPulser().submit(new Pulse(HOUR_TICKS) { // the hourly line
            @Override public boolean pulse() {
                if (Batch29.stopped) return true;
                try { hourly(); } catch (Throwable t) { Batch29.log("hourly: " + t); }
                return false;
            }
        });
        log("on: chains (share " + Chains.SHARE + ", legMax " + Chains.LEG_MAX + ")");
    }

    static void stop(String why) { stopped = true; stopWhy = why; log("off: " + why); }

    static void hourly() { if (on(CHAINS)) log(Chains.line()); }

    static final class Chains {
        static final int NONE = 0, SMELT = 1, COOK = 2;
        static final double SHARE = dbl("chains.share", 0.10);
        static final int LEG_MAX = Integer.getInteger("chains.legMax", 150);
        static volatile int started, trips, aborted, made, picked;
        static final Map<SmartAdventurers.Job, SmartAdventurers.Job> VARIANT = new ConcurrentHashMap<>(); // raw job -> its chain job (identity keys)
        static double lastScore;
        static long shareAt; static double shareNow;
        static final int[][] COOKED = {{335, 333}, {331, 329}, {377, 379}, {359, 361}, {371, 373}, {383, 385}}; // raw -> cooked

        static String word(int c) { return c == SMELT ? "smelt" : c == COOK ? "cook" : "none"; }

        /** The process a chain of this raw job would use, or NONE (only the four chains of design 1.2). */
        static int processFor(SmartAdventurers.Job j) {
            if (j == null || j.hunt != null || j.chain != NONE) return NONE;
            switch (j.task) {
                case COPPER: case TIN: case IRON: return SMELT;
                case LURE: case CAGE: case HARPOON: return COOK;
                default: return NONE;
            }
        }

        static SmartAdventurers.Job variant(SmartAdventurers.Job j) {
            int pr = processFor(j);
            if (pr == NONE) return null;
            return VARIANT.computeIfAbsent(j, k -> { SmartAdventurers.Job c = new SmartAdventurers.Job(k.task, k.skill, k.level); c.chain = pr; if (W5aSkills.SHARK) { c.prodOv = k.prodOv; c.labelOv = k.labelOv; c.spotRule = k.spotRule; c.sharkRow = k.sharkRow; } return c; });
        }

        /** chooseJob (one line): the chain job of j when it fits and scores higher than the raw sale; its score in lastScore. */
        static SmartAdventurers.Job pick(SmartAdventurers.Brain br, SmartAdventurers.Job j, double score) {
            if (!on(CHAINS) || br == null || br.bot == null || score <= 0) return null;
            SmartAdventurers.Job c = variant(j);
            if (c == null || !fits(br, c)) return null;
            double dm = demand(c);
            double s = score * 0.9 * dm; // design 1.4: the 0.9 pays for the longer trip, demand makes it worth it or not
            if (W34Skills.SMELT_NUDGE && c.chain == SMELT) { int lk = W34Skills.nextLook(dm); s = score * W34Skills.smeltFactor(true, dm, lk, W34Skills.NUDGE_EVERY); if (s > score) W34Skills.lifted(dm, lk); } // W34 (-Dbots.chains.smeltNudge): full weight in demand, every 4th other look at 1.05 (a counter, no RNG)
            if (s <= score) return null;
            lastScore = s; picked++;
            return c;
        }

        /** The bar a chain of this rock job makes and its level, or null. */
        static int[] bar(SmartAdventurers.Job c) {
            if (c.task == AfkTasks.Task.IRON) return new int[]{2351, 15};
            if (c.task == AfkTasks.Task.COPPER || c.task == AfkTasks.Task.TIN) return new int[]{2349, 1};
            return null;
        }

        static int cookedOf(int raw) { for (int[] x : COOKED) if (x[0] == raw) return x[1]; return -1; }

        /** design 1.4: 1.25 when the product (x yield) is worth 20%+ more than what goes into it, else 0.8. */
        static double demand(SmartAdventurers.Job c) {
            double prod, raw;
            if (c.chain == SMELT) {
                int[] b = bar(c); if (b == null) return 0.8;
                prod = MarketBrain.value(b[0]) * (b[0] == 2351 ? 0.5 : 1.0); // iron smelts at 50% without the ring
                raw = b[0] == 2351 ? MarketBrain.value(440) : MarketBrain.value(436) + MarketBrain.value(438);
                if (W3Skills.SMELT_FIX) { // W3 wave 1 fix 1 (-Dbots.chains.smeltFix): bars scarce or ore in glut makes the chain worth it
                    boolean glut = b[0] == 2351 ? MarketBrain.isSurplus(440) : MarketBrain.isSurplus(436) || MarketBrain.isSurplus(438);
                    double d = W3Skills.smeltDemand(true, prod, raw, Artisans.SHORT.getOrDefault(b[0], 0), MarketBrain.stockOf(b[0]), Artisans.target(b[0]), glut);
                    if (d > 1 && !(raw > 0 && prod >= raw * 1.2)) W3Skills.smeltScarce++;
                    return d;
                }
            } else {
                int r = c.task.products[0], k = cookedOf(r); if (k < 0) return 0.8;
                prod = MarketBrain.value(k); raw = MarketBrain.value(r);
            }
            return raw > 0 && prod >= raw * 1.2 ? 1.25 : 0.8;
        }

        static boolean fits(SmartAdventurers.Brain br, SmartAdventurers.Job c) {
            Player p = br.bot;
            if (p == null || c.chain == NONE || br.broke()) return false; // a broke bot keeps the coin-paying raw sale
            if (c.chain == SMELT) { int[] b = bar(c); if (b == null || p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.SMITHING) < b[1]) return false; }
            else { Integer need = SmartAdventurers.Brain.COOK_LEVEL.get(c.task.products[0]); if (p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.COOKING) < (need == null ? 1 : need)) return false; }
            if (SHARE <= 0 || share() >= SHARE) return false;
            Location[] legs = legs(p, c);
            if (legs == null) return false;
            if (!short3(legs)) return false;
            return Gates.canEnter(p, legs[1]) && Gates.canEnter(p, legs[2]);
        }

        /** {gather spot, station, bank}: the spot start() would pick, the static station nearest it, the bank nearest the station. */
        static Location[] legs(Player p, SmartAdventurers.Job c) {
            AfkTasks.Spot[] spots = AfkTasks.SPOTS.get(c.task);
            if (spots == null || spots.length == 0) return null;
            AfkTasks.Spot spot = null;
            for (AfkTasks.Spot s : spots) if (Gates.canEnter(p, s.at) && (spot == null || s.at.getDistance(p.getLocation()) < spot.at.getDistance(p.getLocation()))) spot = s;
            if (spot == null) spot = spots[0];
            return legsFrom(spot.at, c.chain);
        }

        /** {spot, station, bank} from a given gather spot. */
        static Location[] legsFrom(Location at, int chain) {
            if (at == null) return null;
            Location st = null, bank = null; double d = Double.MAX_VALUE;
            java.util.List<Location> stations = new java.util.ArrayList<>(java.util.Arrays.asList(chain == SMELT ? AfkTasks.FURNACES : AfkTasks.Runner.RANGES));
            if (chain != SMELT) stations.addAll(java.util.Arrays.asList(AfkTasks.Runner.BOT_RANGES)); // P78b: chains are bots only
            for (Location f : stations) {
                if ((f.getX() == 3274 || f.getX() == 3273) && at.getX() < 3268) continue; // the Al Kharid toll, as the Runner skips it
                double x = f.getDistance(at); if (x < d) { d = x; st = f; }
            }
            if (st == null) return null;
            d = Double.MAX_VALUE;
            for (Location b : AfkTasks.BANKS) {
                if (b.getX() == 3270 && st.getX() < 3268) continue;
                double x = b.getDistance(st); if (x < d) { d = x; bank = b; }
            }
            return bank == null ? null : new Location[]{at, st, bank};
        }

        /** Share of online adventurers on a chain trip now (cached 5 s). */
        static double share() {
            long now = System.currentTimeMillis();
            if (now < shareAt) return shareNow;
            RUNS.removeIf(x -> now - x.startedAt > 4 * 3_600_000L); // a chain runner halted without the done branch (rescue, logout)
            int all = 0, on = 0;
            for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
                if (b == null || b.bot == null || !b.bot.isActive()) continue;
                all++;
                if (b.runner != null && RUNS.contains(b.runner) && AfkTasks.RUNNING.containsKey(key(b.bot))) on++;
            }
            shareNow = all == 0 ? 0 : on / (double) all; shareAt = now + 5_000;
            return shareNow;
        }

        /** start() (one line): the Runner processes the pack and banks it instead of selling raw. */
        static boolean short3(Location[] legs) {
            if (legs == null) return false;
            for (int i = 0; i < 3; i++) if (legs[i].getDistance(legs[(i + 1) % 3]) > LEG_MAX) return false;
            return true;
        }
        static volatile int dropped; static volatile boolean lastApplied;
        static final Set<AfkTasks.Runner> RUNS = ConcurrentHashMap.newKeySet(); // runners that really run a chain (identity)
        static void apply(AfkTasks.Runner r, SmartAdventurers.Job job, Location spotAt) {
            lastApplied = false;
            if (!short3(legsFrom(spotAt, job.chain))) { dropped++; return; } // P78 trial: the bot moved since chooseJob and start() took a farther spot: run it as the raw job
            if (job.chain == SMELT) { r.smeltHaul = true; r.cookHaul = false; r.sell = false; }
            else if (job.chain == COOK) { r.cookHaul = true; r.smeltHaul = false; r.sell = false; }
            started++; shareAt = 0; lastApplied = true; RUNS.add(r);
            if (W34Skills.SMELT_NUDGE && job.chain == SMELT) try { W34Skills.smeltStart(r.p.getUsername(), job.task.key); } catch (Throwable ignored) { } // W34: one line per smelt chain start (flag on only)
            if (SmithOwn.ON && job.chain == SMELT) SmithOwn.apply(r, r.p, job.task, spotAt); // W8 (-Dbots.smith.own): the third leg, smelt -> smith at the anvil -> bank
        }

        static String label(SmartAdventurers.Job job) { return job == null || job.chain == NONE || !lastApplied ? "" : " chain=" + word(job.chain) + " bank"; }

        /** The runner-done branch (one line). */
        static void done(SmartAdventurers.Brain br, AfkTasks.Runner r) {
            if (SmithOwn.ON) SmithOwn.done(r); // W8 (-Dbots.smith.own)
            if (r == null || !RUNS.remove(r)) { other(r); return; } // dropped at start: an ordinary trip
            trips++;
            if (r.collected <= 0) aborted++; else made += r.collected;
            if (r != null) { chainMs += System.currentTimeMillis() - r.startedAt; System.out.println("[B29] chain done " + word(br.lastJob == null ? NONE : br.lastJob.chain) + " " + r.task.key + ": " + r.collected + " collected, " + r.barsMade + " bars, " + (System.currentTimeMillis() - r.startedAt) / 1000 + " s"); }
        }
        static volatile long chainMs, otherMs; static volatile int otherTrips;
        /** The runner-done branch for a non-chain trip while chains are on (trip-time comparison only). */
        static void other(AfkTasks.Runner r) { if (r != null) { otherTrips++; otherMs += System.currentTimeMillis() - r.startedAt; } }

        static String line() { return "chains: " + trips + " trips, " + aborted + " aborted, " + started + " started, " + dropped + " dropped at start, " + made + " gathered; avg trip " + (trips == 0 ? 0 : chainMs / trips / 1000) + " s chain vs " + (otherTrips == 0 ? 0 : otherMs / otherTrips / 1000) + " s other (" + otherTrips + "), share now " + String.format("%.2f", shareNow) + ", picked " + picked + ", demand " + demands(); }
        static String demands() { StringBuilder b = new StringBuilder(); for (SmartAdventurers.Job j : SmartAdventurers.JOBS) { SmartAdventurers.Job c = variant(j); if (c != null) b.append(c.task.key).append('=').append(demand(c)).append(' '); } return b.toString().trim(); }
    }

}
