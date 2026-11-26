package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.map.Location;
import core.game.world.update.flag.context.Animation;
import core.game.world.update.flag.context.Graphics;

import java.util.Locale;

/**
 * W17b (2026-11-21): nature rune runs on Karamja behind the existing switch -Dbots.rc.natureLaw (default OFF; until now it
 * was eligibility only, W13c.natureLaw). Law (Entrana) is NOT built: its boat takes no weapons or armour and is not in the
 * walker, so it stays a later job.
 *
 * One run, by a player's rules (nothing is granted: no essence, no talisman, no coins, no runes from nowhere):
 *   Draynor bank 3092,3243: the nature talisman (own, bank, or bought from bot stock, RcRuns.fetchKey; a worn tiara counts)
 *     and pure essence from the bot's own bank (never bought, never rune essence: the engine's nature altar takes pure only)
 *   w30: the paid crossing straight to the nature ruins 2871,3021 (Ledger.Wallet.payTeleport "sea-crossing", as every bot
 *     walk across the sea without a boat). The W17b boat (30 gp to Musa Point 2956,3146, then a 510-tile walk) never
 *     arrived: the walk's only way west is the Brimhaven gate 2816,3182-3183, which bots do not get through (boatWalks).
 *   enter (talisman or tiara, Rune Mysteries, as the engine's ruin) to 2400,4835, craft on altar object 2486 (9 xp each,
 *     one rune per essence), out by the portal
 *   w30: the paid crossing back to the Draynor bank; there the runes go on the Exchange (Ledger.supply) and the talisman
 *     and leftover essence go into the bank. No coins for it: the run ends at the ruins (runes on the Exchange, talisman
 *     and essence into the bot's bank) and the bot's next job leaves the island its own way. The start gate asks for
 *     both fares, so that is the fallback only. A finished run rests the row for the bot (RUN_REST, 30 min).
 * A stall away from the bank (w30) puts the talisman and the essence into the bot's own bank; runes stay in the pack; the job rests for this bot (STALL_REST).
 *
 * Wiring: one SmartAdventurers.JOBS row (built only with the flag on, after the bots.rc.runs row), marked rcRow so the
 * existing three rune-run hooks reach it; RcRuns.score / start / tick hand over to this class for it (label test / NRun).
 * Off: no row, so none of this is reached; class init reads properties only (no RNG, no other class loaded).
 * On: one RNG draw per score, after every filter, as every other job.
 * Lines start "[NatureRun] " then the bot's name.
 */
final class W17b {
    private W17b() { }

    static final boolean ON = Boolean.getBoolean("bots.rc.natureLaw");
    static final String LABEL = "Nature rune runs";
    static final int SEATS = Math.max(1, Math.min(8, Integer.getInteger("bots.rc.nature.seats", 2)));
    static final int MIN_ESS = Math.max(1, Math.min(27, Integer.getInteger("bots.rc.nature.minEss", 20)));
    static final int TRIP = 27;
    /** w18: 0.6 lost to nearly every local job (the row has no trade or home bonus and is cut by the walk to Draynor): one start in two 3 h arms. */
    static final double WEIGHT_DEF = 2.0;
    static final double WEIGHT = weightProp(System.getProperty("bots.rc.nature.weight"));
    static double weightProp(String s) { try { return s == null ? WEIGHT_DEF : Math.max(0, Double.parseDouble(s.trim())); } catch (Throwable t) { return WEIGHT_DEF; } }

    static final int LEVEL = 44, RUNE = 561, TALISMAN = 1462, TIARA = 5541, ALTAR_OBJ = 2486, PORTAL_OBJ = 2473, FARE = 30;
    static final double XP = 9.0;
    static final int BANK_X = 3092, BANK_Y = 3243, SEAMEN_X = 3028, SEAMEN_Y = 3220, CUSTOMS_X = 2953, CUSTOMS_Y = 3149;
    static final int RUIN_X = 2871, RUIN_Y = 3021, END_X = 2400, END_Y = 4835;
    /** crossSea boards only within this many tiles of the boarding spot (AfkTasks.Runner.BOAT_NEAR); the run walks to DOCK_AT tiles itself. */
    static final int DOCK_RULE = 120, DOCK_AT = 8;
    static final long RUN_LIMIT = 45 * 60_000L, MAIN_WALK = 240_000L, KARA_WALK = 12 * 60_000L, SEA_LIMIT = 240_000L, STALL_REST = 60 * 60_000L;

    // ------------------------------------------------------------------ pure rules (TestW17b)

    static final int OK = 0, OFF = 1, NO_RC = 2, NO_KEY = 3, NO_QUEST = 4, NO_COINS = 5, NO_ESS = 6, NOT_MAINLAND = 7, NO_SEAT = 8;

    /** Who may start a nature run: flag on, Runecrafting 44, a nature talisman or tiara it has or can get, Rune Mysteries, both fares (plus the talisman's price when it must buy one), enough pure essence of its own, on the mainland, a free seat. */
    static int gate(boolean on, int rc, boolean key, boolean quest, long coins, long need, int pure, int minEss, boolean mainland, int seatsUsed, int seats) {
        if (!on) return OFF;
        if (rc < LEVEL) return NO_RC;
        if (!key) return NO_KEY;
        if (!quest) return NO_QUEST;
        if (coins < need) return NO_COINS;
        if (pure < minEss) return NO_ESS;
        if (!mainland) return NOT_MAINLAND;
        if (seatsUsed >= seats) return NO_SEAT;
        return OK;
    }

    /** Coins a run needs at the start: the fare out and back, plus the talisman when it comes from bot stock. */
    static long need(boolean buyKey, long keyPrice) { return 2L * FARE + (buyKey ? Math.max(1, keyPrice) : 0); }

    /** w30: coins a run needs at the start now that the crossing is the paid one straight to the ruins: both crossing fares, out and back (w30 review: with one, a bot with 180-359 gp ended at the ruins with nothing), plus the talisman when it comes from bot stock. */
    static long need(boolean buyKey, long keyPrice, long fare) { return 2L * Math.max(1, fare) + (buyKey ? Math.max(1, keyPrice) : 0); }
    /** w30 review: a bot rests the row this long after a finished run (each run is two paid "sea-crossing" actions; CoinWatch alarms on 25 an hour per bot and 60 an hour per kind). -Dbots.rc.nature.restMin, 30 by default, 0 = no rest. */
    static final long RUN_REST = Math.max(0, Math.min(24 * 60, Integer.getInteger("bots.rc.nature.restMin", 30))) * 60_000L;
    /** When the row may be scored again for a bot whose run ended at time now: a stall rests STALL_REST, a finished run RUN_REST. */
    static long restUntil(long now, boolean stalled) { return now + (stalled ? STALL_REST : RUN_REST); }
    /** w30: Musa Point (the 30 gp boat's landing) has no walk to the nature ruins: the only way west is the Brimhaven gate at 2816,3182-3183, which no bot has passed (survey legs.jsonl: 10 of 10 blocked there; q28/q28b: 35 runs stood at 2816,3181). */
    static boolean boatWalks() { return false; }

    /** Pure essence for one trip: what the pack takes (one slot kept for the fare when no coins are in the pack), what the bot owns, at most TRIP. */
    static int essFor(int freeSlots, boolean coinsInPack, int owned) { return Math.max(0, Math.min(TRIP, Math.min(owned, freeSlots - (coinsInPack ? 0 : 1)))); }

    /** w18: what the bank leg puts into the bank before the essence comes out: everything but the coins, the talisman and pure essence (q09: a pack with 13 free slots left the run 13 essence, under MIN_ESS). */
    static boolean stow(int id) { return id != 995 && id != TALISMAN && id != RcRun.ESSENCE; }

    /** w18: a broke bot's score, in coins as Brain.chooseJob's broke branch: one trip of runes at guide price (the run costs it two fares, which the gate checks). */
    static double brokeScore(long runeValue, int pure, double dist, int seatsUsed, double jitter) { return Math.max(1, runeValue) * (double) Math.min(TRIP, Math.max(0, pure)) * jitter / (1 + dist / 150.0) / (1 + seatsUsed); }

    enum Leg { BANK, DOCK_OUT, SEA_OUT, RUINS, ALTAR, CRAFT, DOCK_BACK, SEA_BACK, HOME }

    /** The leg after l on a run with no trouble (null after HOME). */
    static Leg after(Leg l) { return l == Leg.HOME ? null : Leg.values()[l.ordinal() + 1]; }

    /** Where a leg walks to, {x, y}; null for a leg that does not walk on the surface (the crossings, the altar room). */
    static int[] target(Leg l) {
        switch (l) {
            case BANK: case HOME: return new int[]{BANK_X, BANK_Y};
            case DOCK_OUT: return new int[]{SEAMEN_X, SEAMEN_Y};
            case RUINS: return new int[]{RUIN_X, RUIN_Y};
            case DOCK_BACK: return new int[]{CUSTOMS_X, CUSTOMS_Y};
            default: return null;
        }
    }

    /** True for the legs walked on Karamja (the bot must still be on the island there). */
    static boolean onIsland(Leg l) { return l == Leg.RUINS || l == Leg.DOCK_BACK; }

    /** The crossing is asked for only at the dock itself, well inside crossSea's 120-tile rule. */
    static boolean atDock(double tiles) { return tiles <= DOCK_AT && DOCK_AT <= DOCK_RULE; }

    // ------------------------------------------------------------------ row, score, start (game thread; flag on only)

    /** Built on first use, so that reading ON with the flag off loads nothing else. */
    static final class R {
        static final RcRun.Route NATURE = new RcRun.Route(false, "nature", RUNE, TALISMAN, TIARA, ALTAR_OBJ, PORTAL_OBJ, XP, BANK_X, BANK_Y, RUIN_X, RUIN_Y, END_X, END_Y, 663, 663);
        static final Location SEAMEN = Location.create(SEAMEN_X, SEAMEN_Y, 0), CUSTOMS = Location.create(CUSTOMS_X, CUSTOMS_Y, 0);
    }

    static final java.util.Map<String, Long> SEAT = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static volatile int starts, done, crafted, stalls;

    static SmartAdventurers.Job row() {
        SmartAdventurers.Job j = new SmartAdventurers.Job(AfkTasks.Task.ESSENCE, Skills.RUNECRAFTING, LEVEL);
        j.prodOv = new int[0]; j.labelOv = LABEL; j.rcRow = true;
        return j;
    }

    /** W17b review: the name as one word (bot names can hold spaces; the log readers match one \S+ after the tag). */
    static String nm(Player p) { try { return p.getUsername().replace(' ', '_'); } catch (Throwable t) { return "?"; } }
    /** W17b review: inside the nature altar room (the only place the run's portal step may act). */
    static boolean inRoom(Location l) { return l != null && l.getY() > 4700 && l.getDistance(R.NATURE.end()) <= 64; }

    static boolean mine(SmartAdventurers.Job j) { return j != null && LABEL.equals(j.labelOv); }

    static int pure(Player p) { int n = 0; try { n = p.getBank().getAmount(RcRun.ESSENCE) + p.getInventory().getAmount(RcRun.ESSENCE); } catch (Throwable ignored) { } return n; }
    static boolean mainland(Location l) { return l != null && l.getZ() == 0 && l.getY() < 6400 && !AfkTasks.Runner.karamja(l); }
    static boolean island(Location l) { return l != null && l.getZ() == 0 && l.getY() < 6400 && AfkTasks.Runner.karamja(l); }

    /** w30: what Ledger.Wallet.payTeleport charges (a law rune and three airs, 100 gp at least). */
    static long crossFare() { try { return Math.max(100, Ledger.price(563) + 3L * Ledger.price(556)); } catch (Throwable t) { return 180; } }

    static int gate(Player p, long now) {
        String src = RcRuns.keySource(p, R.NATURE);
        boolean buy = src != null && src.startsWith("stock");
        int rc = p.getSkills().getStaticLevel(Skills.RUNECRAFTING);
        boolean inBank = false; try { inBank = p.getBank().containsItem(new Item(TALISMAN)); } catch (Throwable ignored) { }
        boolean key = (src != null || inBank) && "nature".equals(W13c.natureLaw(rc, true, false, false));
        return gate(ON, rc, key, RcRuns.quest(p), Ledger.Wallet.coins(p), need(buy, MarketBrain.value(TALISMAN), crossFare()), pure(p), MIN_ESS, mainland(p.getLocation()), RcRuns.seatsUsed(SEAT, now), SEATS);
    }

    // w18: why the row is not taken, counted per score call and printed every 10 min (q09 gave no way to tell)
    static final int[] REFUSED = new int[NO_SEAT + 1];
    static volatile int scored, rCrew, rRest, rFar, rSurplus, brokeScored;
    static volatile long tallyAt;
    static void tally(long now) {
        if (now < tallyAt) return;
        tallyAt = now + 600_000L;
        System.out.println("[NatureRun] " + "tally scored=" + scored + " broke=" + brokeScored + " starts=" + starts + " done=" + done + " stalls=" + stalls + " crew=" + rCrew + " rest=" + rRest + " noRc=" + REFUSED[NO_RC] + " noKey=" + REFUSED[NO_KEY] + " noQuest=" + REFUSED[NO_QUEST]
                + " noCoins=" + REFUSED[NO_COINS] + " noEss=" + REFUSED[NO_ESS] + " notMainland=" + REFUSED[NOT_MAINLAND] + " noSeat=" + REFUSED[NO_SEAT] + " far=" + rFar + " surplus=" + rSurplus);
    }

    /**
     * Brain.chooseJob's score for the row, or -1. The one RNG draw comes after every filter.
     * w18: a broke bot (under 400 gp) is no longer refused: the run costs two fares, which the gate checks, and it is the best money it has;
     * its score is in coins, as chooseJob's broke branch (brokeScore).
     */
    static double score(SmartAdventurers.Brain b, SmartAdventurers.Job j, boolean broke, java.util.Random rng, long now) {
        Player p = b.bot;
        tally(now);
        if (b.crew != null) { rCrew++; return -1; }
        if (b.blacklist.getOrDefault(j.label(), 0L) > now) { rRest++; return -1; }
        int g = gate(p, now);
        if (g != OK) { if (g >= 0 && g < REFUSED.length) REFUSED[g]++; return -1; }
        double dist = R.NATURE.bank().getDistance(p.getLocation());
        if (dist > AfkTasks.Runner.TELEPORT_MIN) { rFar++; return -1; }
        double w = RcRuns.weight(MarketBrain.isSurplus(RUNE), MarketBrain.bounty(RUNE), WEIGHT);
        if (w <= 0) { rSurplus++; return -1; }
        scored++;
        double jitter = 0.8 + rng.nextDouble() * 0.4;
        int used = RcRuns.seatsUsed(SEAT, now);
        if (broke) { brokeScored++; return brokeScore(MarketBrain.value(RUNE), pure(p), dist, used, jitter) * (b.lastJob == j ? 0.5 : 1.0); }
        double s = w / (1 + dist / 150.0) * jitter / (1 + used);
        if (b.lastJob == j) s *= 0.5;
        return s;
    }

    static final class NRun extends RcRuns.Run {
        Leg at = Leg.BANK;
        AfkTasks.Runner sea;
        int portalTries;
        NRun(long now) { super(R.NATURE, now); }
        void go(Leg l, long now) { at = l; legStart = now; lastMove = now; }
    }

    /** Brain.start for the row: re-checked, no RNG draw. */
    static void start(SmartAdventurers.Brain b, SmartAdventurers.Job job) {
        long now = System.currentTimeMillis();
        Player p = b.bot;
        b.lastJob = job;
        if (gate(p, now) != OK) { b.nextThink = now + 5_000; return; }
        SEAT.put(RcRuns.key(p), now + RUN_LIMIT);
        starts++;
        NRun run = new NRun(now); run.lastLoc = p.getLocation();
        b.rcRun = run;
        BotChat.activity(p, () -> "doing nature rune runs");
        System.out.println("[NatureRun] " + nm(p) + " starts a nature run (Runecrafting " + p.getSkills().getStaticLevel(Skills.RUNECRAFTING) + ", " + pure(p) + " pure essence, " + Ledger.Wallet.coins(p) + " gp)");
    }

    // ------------------------------------------------------------------ one run

    static void release(SmartAdventurers.Brain b, long now) {
        try { SEAT.remove(RcRuns.key(b.bot)); } catch (Throwable ignored) { }
        b.rcRun = null;
        try { BotChat.clearActivity(b.bot); } catch (Throwable ignored) { }
        b.nextThink = now + 5_000;
    }

    /** At the bank only: runes on the Exchange, talisman and leftover pure essence into the bank. */
    static int unload(Player p) {
        int sold = RcRuns.sellRunes(p, R.NATURE);
        RcRun.bankKey(p, R.NATURE);
        try { int k = p.getInventory().getAmount(RcRun.ESSENCE); if (k > 0 && p.getInventory().remove(new Item(RcRun.ESSENCE, k)) && !p.getBank().add(new Item(RcRun.ESSENCE, k))) p.getInventory().add(new Item(RcRun.ESSENCE, k)); } catch (Throwable ignored) { }
        return sold;
    }

    /** w30: a stall away from the bank puts the talisman and the pure essence into the bot's own bank, as RcRuns.end does for the other altars (q28: 26 essence left in the pack are no job's haul, so every job after it ended "Bank is full or nothing to deposit"). Runes stay in the pack for the next bank leg. */
    static void stowRun(Player p) {
        RcRun.bankKey(p, R.NATURE);
        try { int k = p.getInventory().getAmount(RcRun.ESSENCE); if (k > 0 && p.getInventory().remove(new Item(RcRun.ESSENCE, k)) && !p.getBank().add(new Item(RcRun.ESSENCE, k))) p.getInventory().add(new Item(RcRun.ESSENCE, k)); } catch (Throwable ignored) { }
    }

    static void stall(SmartAdventurers.Brain b, NRun run, long now, String why) {
        Player p = b.bot;
        stalls++;
        System.out.println("[NatureRun] " + nm(p) + " stall: " + why + " after " + (now - run.legStart) / 1000 + " s on leg " + run.at.name().toLowerCase(Locale.ROOT) + " at " + p.getLocation());
        try { if (inRoom(p.getLocation())) p.getProperties().setTeleportLocation(R.NATURE.ruin()); } catch (Throwable ignored) { } // out through the portal
        try { if (p.getLocation().getDistance(R.NATURE.bank()) <= 4) unload(p); else stowRun(p); } catch (Throwable ignored) { }
        b.blacklist.put(LABEL, restUntil(now, true));
        release(b, now);
    }

    /** RcRuns.tick hands over here while Brain.rcRun is an NRun. A throw drops the run (logged once per bot). */
    static void tick(SmartAdventurers.Brain b, long now) {
        try { tick0(b, (NRun) b.rcRun, now); }
        catch (Throwable t) {
            String k; try { k = RcRuns.key(b.bot); } catch (Throwable e) { k = "?"; }
            if (FAILED.add(k)) System.out.println("[NatureRun] " + k + " tick failed: " + t + " (run dropped; logged once per bot)");
            try { stowRun(b.bot); } catch (Throwable ignored) { } // w30
            try { b.blacklist.put(LABEL, restUntil(now, true)); } catch (Throwable ignored) { }
            release(b, now);
        }
    }

    /** Walk leg: true when the bot stands within reach of the leg's spot; else it walks on (or the run stalls). */
    static boolean arrive(SmartAdventurers.Brain b, NRun run, long now, Location to, int reach, long limit, String what) {
        if (b.bot.getLocation().getDistance(to) <= reach) return true;
        if (RcRun.stalled(now, run.legStart, run.lastMove, limit)) { stall(b, run, now, "walk to " + what); return false; }
        RcRuns.walk(b, to);
        return false;
    }

    /** w30: the crossing is the paid teleport every other bot walk across the sea falls back to (AfkTasks.Runner.goTo: Ledger.Wallet.payTeleport "sea-crossing", Spectacle.teleport to Watchdog.landing). True when paid and under way. */
    static boolean fly(SmartAdventurers.Brain b, NRun run, long now, Location to, Leg next) {
        Player bot = b.bot;
        if (!Ledger.Wallet.payTeleport(bot, "sea-crossing")) return false;
        run.sea = null;
        Spectacle.teleport(bot, Watchdog.landing(to));
        System.out.println("[NatureRun] " + nm(bot) + " crosses " + (next == Leg.SEA_OUT ? "to the nature ruins" : "back to the mainland") + " (paid teleport)");
        run.go(next, now);
        return true;
    }

    static void tick0(SmartAdventurers.Brain b, NRun run, long now) {
        Player bot = b.bot; RcRun.Route r = run.r;
        b.nextThink = now + 1_200;
        if (now - run.startedAt > RUN_LIMIT) { stall(b, run, now, "run over " + RUN_LIMIT / 60_000 + " min"); return; }
        Location at = bot.getLocation();
        if (run.lastLoc == null || !at.equals(run.lastLoc)) { run.lastLoc = at; run.lastMove = now; }
        if (onIsland(run.at) && at.getY() < 4700 && !island(at)) { stall(b, run, now, "no longer on Karamja (death or teleport)"); return; }
        switch (run.at) {
            case BANK: {
                if (!arrive(b, run, now, r.bank(), 4, MAIN_WALK, "the bank")) return;
                RcRuns.sellRunes(bot, r); // runes left in the pack by a stalled run
                for (Item it : bot.getInventory().toArray()) { // w18: the pack is emptied into the bot's own bank first (all but coins, talisman, essence)
                    if (it == null || !stow(it.getId())) continue;
                    Item copy = new Item(it.getId(), it.getAmount(), it.getCharge());
                    if (bot.getInventory().remove(copy) && !bot.getBank().add(copy)) bot.getInventory().add(copy);
                }
                if (!RcRuns.fetchKey(bot, r) || !RcRun.own(bot, r)) { stall(b, run, now, "no nature talisman or tiara (own, bank or bot stock)"); return; }
                int have = bot.getInventory().getAmount(RcRun.ESSENCE);
                int n = essFor(bot.getInventory().freeSlots() + have, bot.getInventory().getAmount(995) > 0, have + bot.getBank().getAmount(RcRun.ESSENCE)) - have;
                if (n > 0 && bot.getBank().remove(new Item(RcRun.ESSENCE, n)) && !bot.getInventory().add(new Item(RcRun.ESSENCE, n))) bot.getBank().add(new Item(RcRun.ESSENCE, n));
                run.ess = bot.getInventory().getAmount(RcRun.ESSENCE);
                if (run.ess < MIN_ESS) { stall(b, run, now, "only " + run.ess + " pure essence in the pack"); return; }
                if (Ledger.Wallet.coins(bot) < 2L * crossFare()) { stall(b, run, now, "no " + 2L * crossFare() + " gp for the two crossings"); return; } // w30 review: both paid crossings (were two boat fares)
                System.out.println("[NatureRun] " + nm(bot) + " leaves the bank with " + run.ess + " pure essence");
                run.go(Leg.DOCK_OUT, now);
                return;
            }
            case DOCK_OUT: {
                if (!mainland(at)) { stall(b, run, now, "not on the mainland"); return; }
                if (bot.getInventory().getAmount(RcRun.ESSENCE) < 1 || !RcRun.own(bot, r)) { stall(b, run, now, "essence or talisman gone before the boat (death)"); return; } // W17b review: no fare paid for an empty trip
                if (!fly(b, run, now, r.ruin(), Leg.SEA_OUT)) stall(b, run, now, "no coins for the crossing"); // w30: not the boat (boatWalks)
                return;
            }
            case SEA_OUT: case SEA_BACK: {
                boolean out = run.at == Leg.SEA_OUT;
                boolean over = (out ? island(at) : mainland(at)) && (run.sea == null || !run.sea.crossing) && bot.getProperties().getTeleportLocation() == null;
                if (over) { run.sea = null; run.go(out ? Leg.RUINS : Leg.HOME, now); return; }
                if (run.sea != null && run.sea.halted || now - run.legStart > SEA_LIMIT) stall(b, run, now, "not across the sea");
                return;
            }
            case RUINS: {
                if (!arrive(b, run, now, r.ruin(), 2, KARA_WALK, "the nature ruins")) return;
                if (!RcRun.own(bot, r)) { stall(b, run, now, "talisman gone at the ruins"); return; }
                if (!RcRuns.quest(bot)) { stall(b, run, now, "no Rune Mysteries"); return; }
                if (Gates.ON && !Gates.canEnter(bot, r.end())) { stall(b, run, now, "ruins gate: " + Gates.missing(bot, r.end())); return; }
                try { bot.animate(new Animation(827)); } catch (Throwable ignored) { }
                bot.getProperties().setTeleportLocation(r.end()); // as the engine's ruin listener
                System.out.println("[NatureRun] " + nm(bot) + " enters the nature altar (" + (now - run.startedAt) / 1000 + " s from the start)");
                run.go(Leg.ALTAR, now);
                return;
            }
            case ALTAR: {
                if (at.getY() < 4700) { if (now - run.legStart > 10_000) stall(b, run, now, "did not land in the altar room"); return; }
                Location altar = RcRun.findAltar(r);
                if (altar == null) { stall(b, run, now, "no engine altar object " + ALTAR_OBJ + " near " + END_X + "," + END_Y); return; }
                if (at.getDistance(altar) > 3) {
                    if (RcRun.stalled(now, run.legStart, run.lastMove, RcRun.INSIDE_LIMIT)) { stall(b, run, now, "walk to the altar"); return; }
                    if (!bot.getWalkingQueue().isMoving()) b.scriptAPI.walkTo(altar);
                    return;
                }
                try { bot.faceLocation(altar); } catch (Throwable ignored) { }
                try { bot.getAnimator().animate(new Animation(791), new Graphics(186, 100)); } catch (Throwable ignored) { }
                run.until = now + 3_000;
                run.go(Leg.CRAFT, now);
                return;
            }
            case CRAFT: {
                if (!inRoom(at)) { stall(b, run, now, "left the altar room before crafting (death or teleport)"); return; } // W17b review: no portal step from outside the room
                if (now < run.until) return;
                int n = bot.getInventory().getAmount(RcRun.ESSENCE);
                if (n > 0 && bot.getInventory().remove(new Item(RcRun.ESSENCE, n))) {
                    if (!bot.getInventory().add(new Item(RUNE, n))) { bot.getInventory().add(new Item(RcRun.ESSENCE, n)); stall(b, run, now, "no pack slot for the runes"); return; }
                    try { bot.getSkills().addExperience(Skills.RUNECRAFTING, RcRun.xp(r, n), true); } catch (Throwable ignored) { }
                    crafted += n;
                    System.out.println("[NatureRun] " + nm(bot) + " crafted " + n + " nature runes");
                }
                bot.getProperties().setTeleportLocation(r.ruin()); // the portal
                run.go(Leg.DOCK_BACK, now);
                return;
            }
            case DOCK_BACK: {
                if (at.getY() > 4700) { // still in the altar room: the portal again
                    if (now - run.legStart < 10_000) return;
                    if (++run.portalTries > 5) { stall(b, run, now, "still in the altar room after " + (run.portalTries - 1) + " portal tries"); return; }
                    run.legStart = now; run.lastMove = now; bot.getProperties().setTeleportLocation(r.ruin());
                    return;
                }
                if (bot.getProperties().getTeleportLocation() != null) return; // the portal step has not landed yet
                if (fly(b, run, now, r.bank(), Leg.SEA_BACK)) return; // w30: paid back to the bank when the bot can
                int sold = RcRuns.sellRunes(bot, r); // w30: else the run ends at the ruins, as RcRuns' walk-back stall does: runes on the Exchange from where it stands, talisman and leftover essence into its own bank; the next job leaves the island its own way
                stowRun(bot);
                done++;
                System.out.println("[NatureRun] " + nm(bot) + " done at the ruins, no coins for the way back: " + sold + " nature runes on the GE (" + (now - run.startedAt) / 1000 + " s, runs " + done + ", stalls " + stalls + ")");
                b.blacklist.put(LABEL, restUntil(now, false)); // w30 review
                release(b, now);
                return;
            }
            case HOME: {
                if (!arrive(b, run, now, r.bank(), 4, MAIN_WALK, "the bank")) return;
                int sold = unload(bot);
                done++;
                System.out.println("[NatureRun] " + nm(bot) + " back at the bank: " + sold + " nature runes on the GE (" + (now - run.startedAt) / 1000 + " s, runs " + done + ", stalls " + stalls + ")");
                b.blacklist.put(LABEL, restUntil(now, false)); // w30 review
                release(b, now);
                return;
            }
        }
    }
}
