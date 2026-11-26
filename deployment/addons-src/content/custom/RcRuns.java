package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.map.Location;
import core.game.world.update.flag.context.Animation;
import core.game.world.update.flag.context.Graphics;

import java.util.Locale;

/**
 * W8 Runecrafting wave 5 (the design notes section 5): -Dbots.rc.runs, default OFF.
 *
 * Low-level smart adventurers do air and mind altar runs, like players starting out after Rune Mysteries: at the route's bank
 * they take the talisman (own, bank, or bought from bot stock) and essence (their own bank first, then bot stock through
 * Ledger.consume, which pays the sellers), walk to the ruins, enter (Rune Mysteries done and the talisman carried, as the
 * engine's ruin), craft on the engine altar object, leave by the portal and walk back to the bank, where the runes go on the
 * Exchange (Ledger.supply) and the talisman goes back in the bank. One rune per essence (no level multiplier), the engine's
 * XP per essence. Nothing is granted: no essence, no talisman, no runes from nowhere.
 *
 * The job is one SmartAdventurers.JOBS row (carrier task ESSENCE so every JOBS reader that looks up a task's spots stays
 * NPE-safe; no products, so bounties, errands and rallies never pick it). Brain.chooseJob scores it here (score) and skips
 * the rest of its loop for it; Brain.start hands it to start; Brain.think runs tick while Brain.rcRun is set. With the flag
 * off the row does not exist and none of these is reached.
 *
 * Routes (RcRun.ROUTES rows, walked with p38-navport.py on nav-p0.bin, 2026-11-19):
 *   air   Falador East bank 3012,3355 <-> ruins 2983,3292   108 / 100, no doors
 *   mind  Falador West bank 2946,3368 <-> ruins 2980,3514   216 / 212, no doors
 * Pure rules (gate, pick, essence count, seats, hour cap) are static and tested without a world (TestW8Rc).
 */
final class RcRuns {
    private RcRuns() { }

    static final boolean ON = Boolean.getBoolean("bots.rc.runs");
    static final String ALTARS = System.getProperty("bots.rc.runs.altars", "air,mind");
    /** "Low-level": combat at most this, Runecrafting under MAX_RC (nature runes at 44 are past these runs). */
    static final int MAX_COMBAT = Integer.getInteger("bots.rc.runs.maxCombat", 50), MAX_RC = Integer.getInteger("bots.rc.runs.maxRc", 44);
    static final int SEATS = clamp(Integer.getInteger("bots.rc.runs.seats", 3), 1, 8);
    static final int HOUR = clamp(Integer.getInteger("bots.rc.runs.perHour", 6), 1, 30);
    static final int TRIP = clamp(Integer.getInteger("bots.rc.runs.trip", 26), 5, 27);
    static final int MIN_ESS = 5;
    static final double WEIGHT = Math.max(0, parse(System.getProperty("bots.rc.runs.weight"), 0.6));
    static final long RUN_LIMIT = 20 * 60_000L, HOUR_MS = 3_600_000L, STALL_REST = 30 * 60_000L;
    static final String LABEL = "Rune runs";

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static double parse(String s, double d) { try { return s == null ? d : Double.parseDouble(s.trim()); } catch (Throwable t) { return d; } }

    // ------------------------------------------------------------------ pure rules

    static final int OK = 0, NO_COMBAT = 1, NO_RC = 2, NO_QUEST = 3, NO_SEAT = 4, NO_HOUR = 5;

    /** Who may start a run now: a low-level bot (combat <= maxCombat, Runecrafting < maxRc) with Rune Mysteries, a free seat, under the hour cap. */
    static int gate(int combat, int rc, boolean quest, int seatsUsed, int seats, int hourUsed, int hour, int maxCombat, int maxRc) {
        if (combat > maxCombat) return NO_COMBAT;
        if (rc >= maxRc) return NO_RC;
        if (!quest) return NO_QUEST;
        if (seatsUsed >= seats) return NO_SEAT;
        if (hourUsed >= hour) return NO_HOUR;
        return OK;
    }

    /** The engine's Runecrafting level for a route's rune (Rune enum: air 1, mind 2, water 5, earth 9, fire 14, body 20). */
    static int level(String name) {
        switch (name.toLowerCase(Locale.ROOT)) { case "air": return 1; case "mind": return 2; case "water": return 5; case "earth": return 9; case "fire": return 14; case "body": return 20; default: return 99; }
    }

    /** The run's weight for a rune: its bounty, floored at the lane weight; 0 while the market has far too much of it. */
    static double weight(boolean surplus, double bounty, double floor) { return surplus ? 0 : Math.max(floor, bounty); }

    /** Which listed altar: the highest weight the bot's level allows (ties: the first listed). -1: none. */
    static int pick(String[] names, boolean[] surplus, double[] bounty, int rc, String csv, double floor) {
        int best = -1; double bw = 0;
        for (int i = 0; i < names.length; i++) {
            if (!RcRun.listed(csv, names[i]) || rc < level(names[i])) continue;
            double w = weight(surplus[i], bounty[i], floor);
            if (w > bw) { bw = w; best = i; }
        }
        return best;
    }

    /** Essence for one run: the pack's free slots (one kept for the talisman) up to TRIP; with the rune wanted, no more than the shortfall (at least MIN_ESS). 0: not worth the walk. */
    static int essFor(int freeSlots, int trip, int want, boolean keyInPack) {
        int n = Math.min(trip, freeSlots - (keyInPack ? 0 : 1));
        if (want > 0) n = Math.min(n, Math.max(MIN_ESS, want));
        return n >= MIN_ESS ? n : 0;
    }

    /** Seats taken now (a seat is held until its run ends or its time runs out). */
    static int seatsUsed(java.util.Map<String, Long> seats, long now) { int n = 0; for (Long t : seats.values()) if (t != null && t > now) n++; return n; }

    /** Starts inside the last hour. */
    static int hourUsed(java.util.Deque<Long> starts, long now) { int n = 0; for (Long t : starts) if (t != null && now - t < HOUR_MS) n++; return n; }

    // ------------------------------------------------------------------ counters ([RC2] hourly line, own timer in MarketBrain)

    static final java.util.Map<String, Long> SEAT = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Deque<Long> STARTS = new java.util.concurrent.ConcurrentLinkedDeque<>();
    static final java.util.Map<String, Integer> CRAFTED_AT = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile int picks, starts, done, crafted, essBank, essBought, noEss, keysBought, keysFromBank, noKey, stalls, returnStalls, backGiveUps, seatFull, hourFull, noQuest, timeouts;
    static volatile long xp;

    /** The [RC2] line runs on its own timer only with altarLoop2 or rc.runs on. */
    static boolean anyOn() { return RcRun.LOOP2 || ON; }

    static String line() {
        Integer second = RcRun.CRAFTED_AT.get(RcRun.SECOND.toLowerCase(Locale.ROOT));
        return "[RC2] hourly on=[" + (RcRun.LOOP2 ? (RcRun.ON ? "altarLoop2" : "altarLoop2(idle: needs altarLoop)") : "") + (RcRun.LOOP2 && ON ? "," : "") + (ON ? "rcRuns" : "") + "]"
                + " second=" + RcRun.SECOND + " list=" + RcRun.LIST + " secondCrafted=" + (second == null ? 0 : second)
                + " runs: altars=" + ALTARS + " picks=" + picks + " starts=" + starts + " done=" + done + " crafted=" + crafted + " at=" + CRAFTED_AT + " xp=" + xp
                + " essBank=" + essBank + " essBought=" + essBought + " noEss=" + noEss + " keysBought=" + keysBought + " keysFromBank=" + keysFromBank + " noKey=" + noKey
                + " stalls=" + stalls + " returnStalls=" + returnStalls + " backGiveUps=" + backGiveUps + " timeouts=" + timeouts
                + " seatFull=" + seatFull + " hourFull=" + hourFull + " noQuest=" + noQuest + " seats=" + seatsUsed(SEAT, System.currentTimeMillis()) + "/" + SEATS + (tickFails > 0 ? " tickFails=" + tickFails : "");
    }

    // ------------------------------------------------------------------ the JOBS row and the choice (game thread)

    /** The one JOBS row (only built with the flag on). */
    static SmartAdventurers.Job row() {
        SmartAdventurers.Job j = new SmartAdventurers.Job(AfkTasks.Task.ESSENCE, Skills.RUNECRAFTING, 1);
        j.prodOv = new int[0]; j.labelOv = LABEL; j.rcRow = true;
        return j;
    }

    /** Built on first use (flag on only): reading RcRuns.ON from SmartAdventurers' static block must not class-init Gates early with the flag off. */
    static volatile Gates.Rule quest;
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    static boolean quest(Player p) { try { if (quest == null) quest = Gates.quest("RUNE_MYSTERIES", "Rune Mysteries"); return quest.ok(p); } catch (Throwable t) { return false; } }

    /** The listed altar for this bot now (RcRun.ROUTES row), or null. */
    static RcRun.Route routeFor(Player p) {
        int n = RcRun.ROUTES.length; String[] names = new String[n]; boolean[] sur = new boolean[n]; double[] bty = new double[n];
        for (int i = 0; i < n; i++) { RcRun.Route r = RcRun.ROUTES[i]; names[i] = r.name; sur[i] = MarketBrain.isSurplus(r.rune); bty[i] = MarketBrain.bounty(r.rune); }
        int i = pick(names, sur, bty, p.getSkills().getStaticLevel(Skills.RUNECRAFTING), ALTARS, WEIGHT);
        return i < 0 ? null : RcRun.ROUTES[i];
    }

    static int essOwned(Player p) { int n = 0; try { n += p.getBank().getAmount(RcRun.ESSENCE) + p.getBank().getAmount(RcRun.RUNE_ESSENCE) + p.getInventory().getAmount(RcRun.ESSENCE) + p.getInventory().getAmount(RcRun.RUNE_ESSENCE); } catch (Throwable ignored) { } return n; }
    static int essAffordable(Player p) {
        int best = 0;
        for (int id : new int[]{RcRun.ESSENCE, RcRun.RUNE_ESSENCE}) { int st = MarketBrain.stockOf(id); long c = Ledger.Wallet.coins(p) / Math.max(1, MarketBrain.value(id)); best = Math.max(best, (int) Math.min(st, c)); }
        return best;
    }

    /** Talisman only for adventurers (a tiara would take the helm's slot): own, bank, or bot stock it can pay for. */
    static String keySource(Player p, RcRun.Route r) {
        boolean inBank = false, packSlot = false; int tal = 0;
        try { inBank = p.getBank().containsItem(new Item(r.talisman)); } catch (Throwable ignored) { }
        try { packSlot = p.getInventory().freeSlots() > 0; } catch (Throwable ignored) { }
        try { tal = core.game.ge.GrandExchange.Companion.getBotstockForId(r.talisman); } catch (Throwable ignored) { }
        boolean afford = Ledger.Wallet.coins(p) >= Math.max(1, MarketBrain.value(r.talisman));
        return RcRun.keySource(RcRun.own(p, r), inBank, afford ? tal : 0, 0, false, packSlot);
    }

    /**
     * Brain.chooseJob's score for the row (flag on only), or -1 when it cannot be taken now. Same shape as every other job:
     * weight / (1 + dist / 150) x jitter / (1 + busy), x0.5 when it was the last job. The one RNG draw comes after every filter.
     */
    static double score(SmartAdventurers.Brain b, SmartAdventurers.Job j, int combat, boolean broke, java.util.Random rng, long now) {
        Player p = b.bot;
        if (W17b.ON && W17b.mine(j)) return W17b.score(b, j, broke, rng, now); // W17b (-Dbots.rc.natureLaw, default off): the nature row has its own rules
        if (broke || b.crew != null) return -1; // essence costs coins; crews are for fighting (and a lead's pick would pull its crew along)
        if (b.blacklist.getOrDefault(j.label(), 0L) > now) return -1;
        int g = gate(combat, p.getSkills().getStaticLevel(Skills.RUNECRAFTING), quest(p), seatsUsed(SEAT, now), SEATS, hourUsed(STARTS, now), HOUR, MAX_COMBAT, MAX_RC);
        if (g == NO_SEAT) seatFull++; else if (g == NO_HOUR) hourFull++; else if (g == NO_QUEST) noQuest++;
        if (g != OK) return -1;
        RcRun.Route r = routeFor(p);
        if (r == null || keySource(p, r) == null) return -1;
        if (essOwned(p) + essAffordable(p) < MIN_ESS) return -1;
        double dist = r.bank().getDistance(p.getLocation());
        if (dist > AfkTasks.Runner.TELEPORT_MIN) return -1; // a low-level walker: no 320+ tile walk to the bank
        double w = weight(MarketBrain.isSurplus(r.rune), MarketBrain.bounty(r.rune), WEIGHT);
        if (w <= 0) return -1;
        double s = w / (1 + dist / 150.0) * (0.8 + rng.nextDouble() * 0.4) / (1 + seatsUsed(SEAT, now));
        if (b.lastJob == j) s *= 0.5;
        return s;
    }

    // ------------------------------------------------------------------ one run (Brain.rcRun)

    enum Leg { BANK, RUINS, ALTAR, CRAFT, BACK }

    static class Run { // W17b: not final, W17b.NRun (a nature run) extends it
        final RcRun.Route r; final long startedAt;
        Leg leg = Leg.BANK;
        long legStart, lastMove, until;
        int backTries, ess;
        Location lastLoc;
        Run(RcRun.Route r, long now) { this.r = r; this.startedAt = now; this.legStart = now; this.lastMove = now; }
        void next(Leg l, long now) { leg = l; legStart = now; lastMove = now; }
    }

    /** Brain.start for the row: re-checked (a crew lead's pick reaches here too); no RNG draw. */
    static void start(SmartAdventurers.Brain b, SmartAdventurers.Job job) {
        if (W17b.ON && W17b.mine(job)) { W17b.start(b, job); return; } // W17b (-Dbots.rc.natureLaw, default off)
        long now = System.currentTimeMillis();
        Player p = b.bot;
        int combat = p.getProperties().getCurrentCombatLevel();
        RcRun.Route r = gate(combat, p.getSkills().getStaticLevel(Skills.RUNECRAFTING), quest(p), seatsUsed(SEAT, now), SEATS, hourUsed(STARTS, now), HOUR, MAX_COMBAT, MAX_RC) == OK ? routeFor(p) : null;
        if (r == null) { b.lastJob = job; b.nextThink = now + 5_000; return; } // refused (a crew lead's or a stale pick): lastJob set so a follower does not retry it every think
        SEAT.put(key(p), now + RUN_LIMIT); STARTS.addLast(now); while (STARTS.size() > 64) STARTS.pollFirst();
        starts++; picks++;
        Run run = new Run(r, now); run.lastLoc = p.getLocation();
        b.rcRun = run; b.lastJob = job;
        BotChat.activity(p, () -> "doing " + r.name + " rune runs");
        System.out.println("[RC2] " + p.getUsername() + " -> " + r.name + " altar run (Runecrafting " + p.getSkills().getStaticLevel(Skills.RUNECRAFTING) + ", combat " + combat + ", seats " + seatsUsed(SEAT, now) + "/" + SEATS + ")");
    }

    static void walk(SmartAdventurers.Brain b, Location dest) {
        Player bot = b.bot;
        if (bot.getWalkingQueue().isMoving()) return;
        Location hop = null;
        if (dest.getY() < 4700) {
            if (NavWalker.ON) { try { hop = NavWalker.step(bot, dest); } catch (Throwable ignored) { } }
            if (hop == null) try { hop = RouteMap.nextHop(bot, dest); } catch (Throwable ignored) { }
            if (RouteMap.usingShortcut(bot)) return;
        }
        b.scriptAPI.walkTo(hop != null ? hop : dest);
    }

    static boolean fetchKey(Player p, RcRun.Route r) {
        String src = keySource(p, r);
        if (src == null) return false;
        if ("own".equals(src)) return true;
        Item t = new Item(r.talisman, 1);
        if ("bank".equals(src)) {
            if (p.getBank().remove(t)) { if (p.getInventory().add(t)) { keysFromBank++; return true; } p.getBank().add(t); }
            return false;
        }
        int took = Ledger.consume(p, r.talisman, 1, "rc-key");
        if (took <= 0) return false;
        if (!p.getInventory().add(t)) { Ledger.supply(p, r.talisman, 1); return false; } // back on sale: nothing lost, nothing minted
        keysBought++;
        System.out.println("[RC2] " + p.getUsername() + " bought a " + t.getName().toLowerCase(Locale.ROOT) + " for the " + r.name + " altar");
        return true;
    }

    /** Essence into the pack: the bot's own bank first, then bot stock (Ledger.consume pays the sellers). Returns the count in the pack. */
    static int fetchEssence(Player p, RcRun.Route r) {
        int want = Math.max(0, MarketBrain.target(r.rune) - MarketBrain.stockOf(r.rune));
        int have = p.getInventory().getAmount(RcRun.ESSENCE) + p.getInventory().getAmount(RcRun.RUNE_ESSENCE);
        int n = essFor(p.getInventory().freeSlots() + have, TRIP, want, true) - have;
        for (int id : new int[]{RcRun.ESSENCE, RcRun.RUNE_ESSENCE}) {
            if (n <= 0) break;
            int k = Math.min(n, Math.min(p.getBank().getAmount(id), p.getInventory().freeSlots()));
            if (k > 0 && p.getBank().remove(new Item(id, k))) { if (p.getInventory().add(new Item(id, k))) { n -= k; essBank += k; } else p.getBank().add(new Item(id, k)); }
        }
        if (n > 0) {
            int id = MarketBrain.stockOf(RcRun.ESSENCE) >= MarketBrain.stockOf(RcRun.RUNE_ESSENCE) ? RcRun.ESSENCE : RcRun.RUNE_ESSENCE;
            int k = Math.min(n, p.getInventory().freeSlots());
            int took = k > 0 ? Ledger.consume(p, id, k, "rc-ess") : 0;
            if (took > 0) {
                int placed = 0; for (int i = 0; i < took; i++) if (p.getInventory().add(new Item(id, 1))) placed++;
                if (placed < took) Ledger.supply(p, id, took - placed); // back on sale
                essBought += placed;
            }
        }
        return p.getInventory().getAmount(RcRun.ESSENCE) + p.getInventory().getAmount(RcRun.RUNE_ESSENCE);
    }

    /** Leftover essence back into the bot's bank (it paid for it; the next run takes it first). */
    static void bankEssence(Player p) {
        for (int id : new int[]{RcRun.ESSENCE, RcRun.RUNE_ESSENCE}) try { int k = p.getInventory().getAmount(id); if (k > 0 && p.getInventory().remove(new Item(id, k)) && !p.getBank().add(new Item(id, k))) p.getInventory().add(new Item(id, k)); } catch (Throwable ignored) { }
    }

    /** The runes in the pack go on the Exchange (bot stock, under this bot's name). */
    static int sellRunes(Player p, RcRun.Route r) {
        int k = 0;
        try { k = p.getInventory().getAmount(r.rune); if (k > 0 && p.getInventory().remove(new Item(r.rune, k))) Ledger.supply(p, r.rune, k); else k = 0; } catch (Throwable ignored) { }
        return k;
    }

    static void end(SmartAdventurers.Brain b, long now, String why) {
        Player p = b.bot; Run run = b.rcRun;
        if (run != null) {
            int sold = sellRunes(p, run.r);
            RcRun.bankKey(p, run.r);
            bankEssence(p);
            System.out.println("[RC2] " + p.getUsername() + " finished the " + run.r.name + " altar run: " + why + " (" + (now - run.startedAt) / 1000 + " s" + (sold > 0 ? ", " + sold + " runes on the GE" : "") + ")");
        }
        SEAT.remove(key(p));
        b.rcRun = null;
        BotChat.clearActivity(p);
        b.nextThink = now + 5_000;
    }

    /** A stall before the craft: out of the altar room if inside, essence and talisman back in the bank, the job rests for this bot. */
    static void stall(SmartAdventurers.Brain b, long now, String why) {
        Run run = b.rcRun; Player p = b.bot;
        stalls++;
        System.out.println("[RC2] stall at " + run.r.name + " altar by " + p.getUsername() + ": " + why + " after " + (now - run.legStart) / 1000 + " s on leg " + run.leg.name().toLowerCase(Locale.ROOT));
        if (p.getLocation().getY() > 4700) try { p.getProperties().setTeleportLocation(run.r.ruin()); } catch (Throwable ignored) { } // out through the portal, as the engine's portal does
        b.blacklist.put(LABEL, now + STALL_REST);
        end(b, now, "stall: " + why);
    }

    /** Brain.think while b.rcRun is set (every think, ~4 s; a run asks for the next one sooner). t63 fix 4: a throw drops the run (logged once per bot) and never reaches the world tick. */
    static void tick(SmartAdventurers.Brain b, long now) {
        if (W17b.ON && b != null && b.rcRun instanceof W17b.NRun) { W17b.tick(b, now); return; } // W17b (-Dbots.rc.natureLaw, default off): never an NRun with the flag off
        try { tick0(b, now); } catch (Throwable t) { fail(b, now, t); }
    }

    static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static volatile int tickFails;
    /** t63 fix 4: out of the altar room if inside, the run ended (seat freed); if even that throws, the run is just dropped. */
    static void fail(SmartAdventurers.Brain b, long now, Throwable t) {
        tickFails++;
        Player p = b == null ? null : b.bot;
        String k; try { k = p == null ? "?" : key(p); } catch (Throwable e) { k = "?"; }
        if (FAILED.add(k)) System.out.println("[RC2] tick failed for " + k + ": " + t + " (run dropped; logged once per bot)");
        if (b == null) return;
        Run run = b.rcRun;
        try { if (run != null && p != null && p.getLocation().getY() > 4700) p.getProperties().setTeleportLocation(run.r.ruin()); } catch (Throwable ignored) { }
        try { end(b, now, "error: " + t); } catch (Throwable e) { SEAT.remove(k); b.rcRun = null; b.nextThink = now + 5_000; }
    }

    static void tick0(SmartAdventurers.Brain b, long now) {
        Run run = b.rcRun; Player bot = b.bot; RcRun.Route r = run.r;
        b.nextThink = now + 1_200;
        if (now - run.startedAt > RUN_LIMIT) { timeouts++; if (run.leg.ordinal() < Leg.CRAFT.ordinal()) stall(b, now, "run over " + RUN_LIMIT / 60_000 + " min"); else { if (bot.getLocation().getY() > 4700) try { Spectacle.teleport(bot, r.bank()); } catch (Throwable t) { try { bot.getProperties().setTeleportLocation(r.bank()); } catch (Throwable ignored) { } } end(b, now, "run over " + RUN_LIMIT / 60_000 + " min"); } return; }
        Location at = bot.getLocation();
        if (run.lastLoc == null || !at.equals(run.lastLoc)) { run.lastLoc = at; run.lastMove = now; }
        switch (run.leg) {
            case BANK: {
                if (at.getDistance(r.bank()) > 4) {
                    if (RcRun.stalled(now, run.legStart, run.lastMove, RcRun.WALK_LIMIT)) { stall(b, now, "walk to the bank"); return; }
                    walk(b, r.bank());
                    return;
                }
                if (!fetchKey(bot, r)) { noKey++; stall(b, now, "no " + r.name + " talisman (own, bank or bot stock)"); return; }
                run.ess = fetchEssence(bot, r);
                if (run.ess < MIN_ESS) { noEss++; stall(b, now, "no essence (" + run.ess + " in the pack)"); return; }
                run.next(Leg.RUINS, now);
                return;
            }
            case RUINS: {
                if (at.getDistance(r.ruin()) > 2) {
                    if (RcRun.stalled(now, run.legStart, run.lastMove, RcRun.WALK_LIMIT)) { stall(b, now, "walk to the ruins"); return; }
                    walk(b, r.ruin());
                    return;
                }
                if (!RcRun.own(bot, r)) { stall(b, now, "talisman gone at the ruins"); return; }
                if (!quest(bot)) { stall(b, now, "no Rune Mysteries"); return; }
                if (Gates.ON && !Gates.canEnter(bot, r.end())) { stall(b, now, "ruins gate: " + Gates.missing(bot, r.end())); return; }
                try { bot.animate(new Animation(827)); } catch (Throwable ignored) { }
                bot.getProperties().setTeleportLocation(r.end()); // as the engine's ruin listener
                run.next(Leg.ALTAR, now);
                return;
            }
            case ALTAR: {
                if (at.getY() < 4700) { if (now - run.legStart > 10_000) stall(b, now, "did not land in the altar room"); return; }
                Location altar = RcRun.findAltar(r);
                if (altar == null) { stall(b, now, "no engine altar object near " + r.endX + "," + r.endY); return; }
                if (at.getDistance(altar) > 3) {
                    if (RcRun.stalled(now, run.legStart, run.lastMove, RcRun.INSIDE_LIMIT)) { stall(b, now, "walk to the altar"); return; }
                    if (!bot.getWalkingQueue().isMoving()) b.scriptAPI.walkTo(altar);
                    return;
                }
                try { bot.faceLocation(altar); } catch (Throwable ignored) { }
                try { bot.getAnimator().animate(new Animation(791), new Graphics(186, 100)); } catch (Throwable ignored) { }
                run.until = now + 3_000;
                run.next(Leg.CRAFT, now);
                return;
            }
            case CRAFT: {
                if (now < run.until) return;
                int n = 0;
                for (int id : new int[]{RcRun.ESSENCE, RcRun.RUNE_ESSENCE}) { int k = bot.getInventory().getAmount(id); if (k > 0 && bot.getInventory().remove(new Item(id, k))) n += k; }
                if (n > 0) {
                    if (!bot.getInventory().add(new Item(r.rune, n))) Ledger.supply(bot, r.rune, n); // runes stack in one slot; if even that fails they go straight on sale
                    double gx = RcRun.xp(r, n);
                    try { bot.getSkills().addExperience(Skills.RUNECRAFTING, gx, true); } catch (Throwable ignored) { }
                    crafted += n; xp += (long) gx; CRAFTED_AT.merge(r.name, n, Integer::sum);
                    System.out.println("[RC2] crafted " + n + " at " + r.name + " altar by " + bot.getUsername() + " (" + (now - run.startedAt) / 1000 + " s from the start, Runecrafting " + bot.getSkills().getStaticLevel(Skills.RUNECRAFTING) + ")");
                }
                bot.getProperties().setTeleportLocation(r.ruin()); // the portal
                run.next(Leg.BACK, now);
                return;
            }
            case BACK: {
                if (at.getY() > 4700) {
                    int act = RcRun.backAction(now - run.legStart, run.backTries);
                    if (act == RcRun.BACK_RETRY_PORTAL) { run.backTries++; run.legStart = now; run.lastMove = now; bot.getProperties().setTeleportLocation(r.ruin()); }
                    else if (act == RcRun.BACK_GIVE_UP) {
                        returnStalls++; backGiveUps++;
                        System.out.println("[RC2] stall at " + r.name + " altar by " + bot.getUsername() + ": still in the altar room after " + run.backTries + " portal tries; set at the bank");
                        try { Spectacle.teleport(bot, r.bank()); } catch (Throwable t) { try { bot.getProperties().setTeleportLocation(r.bank()); } catch (Throwable ignored) { } }
                        done++; end(b, now, "set at the bank after the portal tries");
                    }
                    return;
                }
                if (at.getDistance(r.bank()) > 4) {
                    if (RcRun.stalled(now, run.legStart, run.lastMove, RcRun.WALK_LIMIT)) { returnStalls++; done++; end(b, now, "stall on the walk back to the bank (runes sold where it stands)"); return; }
                    walk(b, r.bank());
                    return;
                }
                done++;
                end(b, now, "back at the bank");
                return;
            }
        }
    }
}
