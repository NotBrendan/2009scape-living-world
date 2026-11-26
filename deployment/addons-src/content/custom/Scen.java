package content.custom;

import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * W8 scenarios (the in-world checks that block shipping train-62's FULL-gate flags). Test-only, farm worlds only:
 *   -Dsmartbots.seed.scen=<name,..>   the scenarios to judge (fix9b, capFix, alchAll, anyStaff, herbSupply, altarLoop, iceGiantFix;
 *                                     w9 scen2: herbs2, vannakaHunts, the design notes)
 *   -Dsmartbots.seed.ticks=<n>        judge after n game ticks from the first seeded bot (default 6000 = 1 h)
 * Each scenario prints one line at the deadline:  [Scen] PASS <name> k=v ...   or   [Scen] FAIL <name> k=v ... why=<first rule broken>
 * and a progress line ([Scen] tick <n> <name> ...) every 500 ticks. Every hook below returns at once unless Seed.ON (seed flags
 * given AND the farm guard passed), so with the flags off nothing here runs, nothing is counted and no RNG is drawn.
 * The judge is a game Pulse (game thread): it only reads counters. The rules are pure (TestScen).
 */
final class Scen {
    private Scen() { }

    static final String[] NAMES = {"fix9b", "capFix", "alchAll", "anyStaff", "herbSupply", "altarLoop", "iceGiantFix", "herbs2", "vannakaHunts", "tele", "prayerStat", "kit2", "wwQuest", "weaponByFoe", "heal"};
    static final Map<String, AtomicInteger> C = new ConcurrentHashMap<>();
    /** altarLoop: runecrafter -> the time its altar run started (removed when the run ends). */
    static final Map<String, Long> RC_OPEN = new ConcurrentHashMap<>();
    static final long RC_MAX_MS = 15 * 60_000L;
    static volatile long alchMaxHour;
    static volatile String gameThread, limpThread;
    static volatile boolean started;

    static int get(String k) { AtomicInteger a = C.get(k); return a == null ? 0 : a.get(); }
    static void add(String k, int n) { C.computeIfAbsent(k, x -> new AtomicInteger()).addAndGet(n); }

    // ---------------------------------------------------------------- pure rules (TestScen)

    /** Al Kharid, the Shantay Pass and the Desert Wolf spot: where a fix9b walk can stall. */
    static boolean passBox(int x, int y, int z) { return z == 0 && x >= 3240 && x <= 3330 && y >= 3040 && y <= 3240; }
    /** White Wolf Mountain's walled Ice giant pocket (W6Slayer.ICE_*), plus a 2-tile rim. */
    static boolean icePocket(int x, int y, int z) { return z == 0 && x >= W6Slayer.ICE_X0 - 2 && x <= W6Slayer.ICE_X1 + 2 && y >= W6Slayer.ICE_Y0 - 2 && y <= W6Slayer.ICE_Y1 + 2; }
    /** A Runner.finish reason that is a stall. */
    static boolean stuckWhy(String why) { return why != null && (why.startsWith("Stuck at") || why.startsWith("Got stuck") || why.startsWith("Couldn't find a path")); }
    /** A paid teleport's coin movement as designed: paid = lost (0..cap], refused = nothing moved. delta = coins before - after. */
    static boolean fareOk(boolean ok, long delta, long want) { return ok ? delta > 0 && delta <= Math.max(100, want) : delta == 0; }
    /** capFix at a trip's settle: a trip that began at the cap may add no counted leg; exempt legs never outnumber the paid ones. */
    static boolean capOver(int telesBefore, int counted, int cap) { return telesBefore >= cap && counted > 0; }
    static boolean exemptOver(int exempt, int paid, boolean reset) { return !reset && exempt > paid; }
    /** anyStaff conservation for one w6BasicStaff call: coins out only for goods in; coins never up. */
    static String staffBad(long coinsOut, int staffIn, int runesIn) {
        if (coinsOut < 0) return "minted";
        if (coinsOut > 0 && staffIn <= 0 && runesIn <= 0) return "paidNothing";
        return null;
    }

    /** w9 herbs2: a herb seed from HERBS_UP (the rows above ranarr only herbs2 adds); a grimy herb a herb patch grows. */
    static boolean herbUp(int seed) { for (int[] r : W6Herbs.HERBS_UP) if (r[1] == seed) return true; return false; }
    static boolean grimy(int id) { return W6Herbs.has(W6Herbs.GRIMY, id); }
    /** w9 vannakaHunts: the tasks W6Slayer builds hunts for (Vampire, Hellhound). */
    static boolean w6Task(String task) { return "VAMPIRES".equals(task) || "HELLHOUNDS".equals(task); }
    /** w9 vannakaHunts: deaths at a Vannaka hunt read as a loop at 3 or more, or at 2 with fewer task kills than deaths. */
    static boolean deathLoop(int deaths, int kills) { return deaths >= 3 || deaths >= 2 && deaths > kills; }

    /** w14 tele: one payTeleport call with bots.magic.tele on. A cast from its own runes moves no coins; coins never go up; a refused call that bought no rune moves none. */
    static String teleBad(boolean cast, boolean boughtRunes, boolean ok, long coinsOut) {
        if (coinsOut < 0) return "minted";
        if (cast && !boughtRunes && coinsOut != 0) return "castPaid";
        if (!cast && !ok && !boughtRunes && coinsOut > 0) return "paidNothing";
        return null;
    }
    /** w14 kit2: one kit2 piece off the Exchange costs at most its unit price (its own unit comes back free); coins never go up. */
    static String kitBad(long coinsOut, long unit) { return coinsOut < 0 ? "minted" : coinsOut > Math.max(1, unit) ? "overpaid" : null; }
    /** w14 heal: a bunyip heal is the owner's lifepoints going up by exactly 2 between two ticks (regeneration is 1, food is 3 or more). */
    static boolean healStep(int before, int after) { return after - before == 2; }

    /** The verdict: null = PASS, else the first rule broken. g reads a counter. */
    static String verdict(String name, java.util.function.ToIntFunction<String> g, long[] x) {
        switch (name) {
            case "fix9b":
                if (g.applyAsInt("pass.south") < 1) return "no paid crossing south";
                if (g.applyAsInt("pass.north") < 1) return "no crossing north";
                if (g.applyAsInt("pass.fareBad") > 0) return "south fare not 5 gp";
                if (g.applyAsInt("pass.cantPay") > 0) return "could not pay the pass";
                if (g.applyAsInt("pass.stuck") > 0) return "stuck by the pass";
                if (g.applyAsInt("too_many_steps") > 0) return "too many steps";
                if (g.applyAsInt("pass.noRoute") > 0) return "no route to a desert spot";
                return null;
            case "capFix":
                if (g.applyAsInt("cap.trips") < 1) return "no task trip settled";
                if (g.applyAsInt("fare.paid") < 1) return "no paid teleport";
                if (g.applyAsInt("fare.bad") > 0) return "fare coins not as designed";
                if (g.applyAsInt("cap.over") > 0) return "counted leg past the cap";
                if (g.applyAsInt("cap.exemptOver") > 0) return "more exempt legs than paid";
                return null;
            case "alchAll": // x = {alchDone, maxHourGp, cap, capped, needCap}
                if (x[0] < 1) return "no routine alch";
                if (x[1] > x[2]) return "hour cap broken " + x[1] + ">" + x[2];
                if (x[4] > 0 && x[3] < 1) return "cap never reached (set a low perHour)";
                return null;
            case "anyStaff": // x = {staffBought + staffWorn}
                if (g.applyAsInt("staff.calls") < 1) return "no basic-staff call";
                if (x[0] < 1) return "no basic staff bought or worn";
                if (g.applyAsInt("staff.minted") > 0) return "coins up on a staff call";
                if (g.applyAsInt("staff.paidNothing") > 0) return "coins out, nothing placed";
                return null;
            case "herbSupply": // x = {limpStarts, playerSpot (1 = visible), botSpot (1 = present), sameThread}
                if (x[2] < 1) return "no bot limpwurt spot";
                if (x[3] < 1) return "limpwurt spot not written on the game thread";
                if (x[1] > 0) return "limpwurt spot visible to players";
                if (x[0] < 1) return "no limpwurt job started";
                return null;
            case "altarLoop": // x = {loops, crafted, backGiveUps, stick, oldestOpenMs}
                if (g.applyAsInt("rc.runs") < 1) return "no altar run";
                if (x[4] > RC_MAX_MS) return "a run open " + x[4] / 60_000 + " min";
                if (x[3] > 0 && x[2] < 1) return "no give-up from the altar room";
                if (x[3] == 0 && x[1] < 1) return "nothing crafted";
                return null;
            case "iceGiantFix": // x = {iceDropped}
                if (x[0] < 1) return "pocket hunt not dropped";
                if (g.applyAsInt("ice.pocket") > 0) return "a bot in the walled pocket";
                return null;
            case "herbs2": // x = {W6Herbs.herbsUp (herb steps that picked a seed above ranarr)}; h2.harvest is reported, not required
                if (g.applyAsInt("h2.plantUp") < 1) return g.applyAsInt("h2.plant") > 0 ? "herb planted, none above ranarr" : "no herb planted";
                if (x[0] < 1) return "herbs2 pick not counted";
                return null;
            case "vannakaHunts": // x = {W6Slayer.built}
                if (x[0] < 1) return "no Vannaka hunt built";
                if (g.applyAsInt("vh.trips") < 1) return "no task trip to a Vannaka hunt";
                if (deathLoop(g.applyAsInt("vh.deaths"), g.applyAsInt("vh.kills"))) return "death loop";
                if (g.applyAsInt("vh.kills") < 1) return "no task kill";
                return null;
            case "tele": // x = {W6Magic.teleCast, teleBought, teleGp}
                if (x[0] < 3) return "fewer than 3 teleport casts";
                if (g.applyAsInt("tele.minted") > 0) return "coins up on a teleport call";
                if (g.applyAsInt("tele.castPaid") > 0) return "coins out on a cast from own runes";
                if (g.applyAsInt("tele.paidNothing") > 0) return "coins out, no teleport and no runes";
                return null;
            case "prayerStat": // x = {coin-drain alarms since the judge started}
                if (g.applyAsInt("pray.trips") < 3) return "fewer than 3 stat-prayer trips";
                if (g.applyAsInt("pray.neg") > 0) return "prayer points under zero";
                if (x[0] > 0) return "coin-drain alarm in the window";
                return null;
            case "kit2": // x = {W6Combat.kitBought}
                if (g.applyAsInt("kit2.bought") < 1) return "no kit2 piece bought";
                if (g.applyAsInt("kit2.minted") > 0) return "coins up on a kit2 buy";
                if (g.applyAsInt("kit2.overpaid") > 0) return "kit2 buy above the unit price";
                return null;
            case "wwQuest": // x = {W6Combat.wwBoosted, coin-drain alarms since the judge started}
                if (x[0] < 1) return "no boosted Wolf Whistle weight";
                if (x[1] > 0) return "coin-drain alarm in the window";
                return null;
            case "weaponByFoe": // x = {foeCrush + foeSlash, foeSwaps, foeMaceBought}
                if (g.applyAsInt("foe.calls") < 1) return "no weapon pick by foe";
                if (x[1] < 1) return "no weapon swapped for a foe";
                if (g.applyAsInt("foe.minted") > 0) return "coins up on a weapon pick";
                if (g.applyAsInt("foe.paidNothing") > 0) return "coins out, no weapon";
                return null;
            case "heal": // x = {healPlans, healSummonOk, healBought}
                if (x[0] < 1) return "no bunyip trip planned";
                if (x[1] < 1) return "no bunyip summoned";
                if (g.applyAsInt("heal.heals") < 1) return "no bunyip heal seen";
                if (g.applyAsInt("heal.minted") > 0) return "coins up on a heal plan";
                if (g.applyAsInt("heal.paidNothing") > 0) return "coins out, no pouch";
                return null;
            default: return "unknown scenario";
        }
    }

    // ---------------------------------------------------------------- hooks (all no-ops unless Seed.ON)

    static boolean on(Player p) { return Seed.ON && p != null && p.isArtificial(); }

    /** AfkTasks.Runner.passHop, after a crossing. paidHere = the 5 gp was taken here; delta = coins before - after. */
    static void pass(Player p, boolean south, boolean paidHere, long delta) {
        if (!on(p)) return;
        add(south ? "pass.south" : "pass.north", 1);
        if (south && paidHere ? delta != AfkTasks.Runner.PASS_FARE : delta != 0) add("pass.fareBad", 1);
        Seed.note(p, "pass " + (south ? "south" : "north") + " paid=" + delta);
    }
    static void passCantPay(Player p) { if (on(p)) add("pass.cantPay", 1); }

    /** Runner.finish: stalls by the pass, any finish inside the ice pocket. */
    static void finish(Player p, String why) {
        if (!on(p)) return;
        Location l = p.getLocation();
        if (stuckWhy(why) && passBox(l.getX(), l.getY(), l.getZ())) { add("pass.stuck", 1); Seed.note(p, "stuck by the pass: " + why); }
        if (icePocket(l.getX(), l.getY(), l.getZ())) add("ice.pocket", 1);
    }

    /** Watchdog rescue (stuck at). */
    static void stuck(Player p, Location at) {
        if (!on(p) || at == null) return;
        if (passBox(at.getX(), at.getY(), at.getZ())) { add("pass.stuck", 1); Seed.note(p, "watchdog stuck by the pass at " + at.getX() + "," + at.getY()); }
        if (icePocket(at.getX(), at.getY(), at.getZ())) add("ice.pocket", 1);
    }

    /** AfkTasks fight tick: an attack on an NPC in the ice pocket. */
    static void attack(Player p, Location at) { if (on(p) && at != null && icePocket(at.getX(), at.getY(), at.getZ())) add("ice.pocket", 1); }

    static void tooManySteps(Player p) { if (on(p)) add("too_many_steps", 1); }

    /** Slayer.openGate: no planned route to a desert spot (fix9b's old failure). */
    static void noRoute(Player p, Location spot) { if (on(p) && spot != null && passBox(spot.getX(), spot.getY(), spot.getZ()) && Gates.desert(spot)) add("pass.noRoute", 1); }

    /** Ledger.Wallet.payTeleport wrapper: the coins one paid teleport moved. */
    static void fare(Player p, String why, boolean ok, long delta, long want) {
        if (!on(p)) return;
        if (ok) add("fare.paid", 1);
        if (!fareOk(ok, delta, want)) { add("fare.bad", 1); Seed.note(p, "fare " + why + " ok=" + ok + " coinsOut=" + delta + " want=" + want); }
    }

    /** Slayer.settle, before capCount: one settled task trip. */
    static void capTrip(Player p, int telesBefore, int counted, int exempt, int paid, boolean reset) {
        if (!on(p)) return;
        add("cap.trips", 1); add("cap.counted", counted); add("cap.exempt", exempt);
        if (capOver(telesBefore, counted, Slayer.TELE_CAP)) { add("cap.over", 1); Seed.note(p, "cap over: task total " + telesBefore + " + " + counted); }
        if (exemptOver(exempt, paid, reset)) { add("cap.exemptOver", 1); Seed.note(p, "exempt " + exempt + " > paid " + paid); }
    }

    /** SmartAdventurers.w6BasicStaff, around the call. */
    static long[] staffBefore(Player p) { return on(p) ? new long[]{Ledger.Wallet.coins(p), held(p, W6Magic.BASIC_STAFF), held(p, W6Magic.MIND) + held(p, W6Magic.AIR)} : null; }
    static void staffAfter(Player p, long[] b) {
        if (b == null || !on(p)) return;
        add("staff.calls", 1);
        String bad = staffBad(b[0] - Ledger.Wallet.coins(p), (int) (held(p, W6Magic.BASIC_STAFF) - b[1]), (int) (held(p, W6Magic.MIND) + held(p, W6Magic.AIR) - b[2]));
        if (bad != null) { add("staff." + bad, 1); Seed.note(p, "staff " + bad + " coins " + b[0] + "->" + Ledger.Wallet.coins(p)); }
    }
    static long held(Player p, int id) {
        long n = 0;
        try { n += p.getInventory().getAmount(id); n += p.getBank().getAmount(id); n += p.getEquipment().getAmount(id); } catch (Throwable ignored) { }
        return n;
    }

    /** The herbSupply limpwurt pulse (SmartAdventurers): which thread wrote AfkTasks.SPOTS. */
    static void limpPut() { if (Seed.ON) limpThread = Thread.currentThread().getName(); }

    /** RcRun: a run started / ended for this runecrafter. */
    static void rcStart(Player p) { if (on(p)) { add("rc.runs", 1); RC_OPEN.put(p.getUsername(), System.currentTimeMillis()); } }
    static void rcEnd(Player p) { if (on(p)) RC_OPEN.remove(p.getUsername()); }

    /** w9 herbs2: SmartAdventurers.farmWork, a herb patch took the seed (seed = the patch's plantable item id, -1 unknown). */
    static void herbPlant(Player p, int seed) {
        if (!on(p)) return;
        add("h2.plant", 1);
        if (herbUp(seed)) add("h2.plantUp", 1);
        Seed.note(p, "herb planted seed=" + seed + (herbUp(seed) ? " (above ranarr)" : ""));
    }
    /** w9 herbs2: farmWork, a harvest at a herb patch (got = crops gained this step). */
    static void herbHarvest(Player p, int crop, int got) {
        if (!on(p) || got <= 0 || !grimy(crop)) return;
        add("h2.harvests", 1); add("h2.harvest", got);
    }

    /** w9 vannakaHunts: Slayer.taskTrip, a task trip started; counted when it is to a W6 (vannakaHunts) hunt. */
    static void slayTrip(Player p, content.global.skill.slayer.Tasks t, SmartAdventurers.Job j) {
        if (!on(p) || j == null || W6Slayer.of(j.hunt) == null) return;
        add("vh.trips", 1);
        Seed.note(p, "vannaka hunt trip " + (t == null ? "?" : t.name()) + " -> " + j.label());
    }
    /** w9 vannakaHunts: Slayer.settle, task kills counted at a trip end; only the W6 tasks (VAMPIRES, HELLHOUNDS). */
    static void slayKills(Player p, content.global.skill.slayer.Tasks t, int n) {
        if (!on(p) || t == null || n <= 0 || !w6Task(t.name())) return;
        add("vh.kills", n);
    }
    /** w9 vannakaHunts: SmartAdventurers death, the bot's last job; counted when it was a W6 hunt. */
    static void death(Player p, SmartAdventurers.Job j) {
        if (!on(p) || j == null || W6Slayer.of(j.hunt) == null) return;
        add("vh.deaths", 1);
        Seed.note(p, "died at a Vannaka hunt " + j.label());
    }

    // ---- w14 scen (the design notes): tele, prayerStat, kit2, wwQuest, weaponByFoe, heal

    /** Coins now, for a hook that needs a before value (0 with the seed flags off: nothing is read). */
    static long coins0(Player p) { return on(p) ? Ledger.Wallet.coins(p) : 0; }

    /** Ledger.Wallet.payTeleport with bots.magic.tele on, around the call. */
    static long[] teleBefore(Player p) { return on(p) ? new long[]{Ledger.Wallet.coins(p), W6Magic.teleCast, W6Magic.teleBought, W6Magic.teleShort} : null; }
    static void teleAfter(Player p, long[] b, boolean ok, String why) {
        if (b == null || !on(p)) return;
        boolean cast = W6Magic.teleCast > b[1], runes = W6Magic.teleBought > b[2] || W6Magic.teleShort > b[3];
        long out = b[0] - Ledger.Wallet.coins(p);
        add("tele.calls", 1); if (cast) add("tele.cast", 1); else if (ok) add("tele.fare", 1);
        String bad = teleBad(cast, runes, ok, out);
        if (bad != null) { add("tele." + bad, 1); Seed.note(p, "tele " + bad + " " + why + " cast=" + cast + " ok=" + ok + " coinsOut=" + out); }
    }

    /** W6Fight.tripEnd, at the [W6c] prayer line: one trip that used stat prayers. */
    static void prayTrip(Player p, int fights, double pointsLeft) {
        if (!on(p)) return;
        add("pray.trips", 1); add("pray.fights", fights);
        if (pointsLeft < 0) add("pray.neg", 1);
    }

    /** SmartAdventurers.kitFromExchange, at the [W6c] kit2 bought line. c0 = coins0 before the buy. */
    static void kit2(Player p, int id, long c0) {
        if (!on(p)) return;
        add("kit2.bought", 1);
        long out = c0 - Ledger.Wallet.coins(p);
        String bad = kitBad(out, Ledger.price(id));
        if (bad != null) { add("kit2." + bad, 1); Seed.note(p, "kit2 " + bad + " item " + id + " coinsOut=" + out); }
    }

    /** SmartAdventurers.start, around w8WeaponByFoe: coins out only for a weapon in (a mace, or the bronze scimitar of a failed swap). */
    static long weapons(Player p) { long n = held(p, 1321); for (int m : W8Polish.MACES) n += held(p, m); return n; }
    static long[] foeBefore(Player p) { return on(p) ? new long[]{Ledger.Wallet.coins(p), weapons(p)} : null; }
    static void foeAfter(Player p, long[] b) {
        if (b == null || !on(p)) return;
        add("foe.calls", 1);
        String bad = staffBad(b[0] - Ledger.Wallet.coins(p), (int) (weapons(p) - b[1]), 0);
        if (bad != null) { add("foe." + bad, 1); Seed.note(p, "weaponByFoe " + bad + " coins " + b[0] + "->" + Ledger.Wallet.coins(p)); }
    }

    /** Bob.combatPlan1, around w8HealPlan: coins out only for a bunyip pouch in. */
    static long[] healBefore(Player p) { return on(p) ? new long[]{Ledger.Wallet.coins(p), held(p, W8Polish.BUNYIP), earned()} : null; }
    /** w21: every coin Ledger has credited to a supplier so far; a buy that went bot to bot raises it by the buyer's coins out. */
    static long earned() { long n = 0; try { synchronized (Ledger.class) { for (long v : Ledger.EARNED.values()) n += v; } } catch (Throwable ignored) { } return n; }
    /** w21: how one paid bunyip plan is filed: toBots (suppliers credited exactly what the buyer paid), sunk (anything else); multi = more than one pouch in. */
    static String buyKind(long coinsOut, long earnedUp) { return coinsOut <= 0 ? null : earnedUp == coinsOut ? "toBots" : "sunk"; }
    static void healAfter(Player p, long[] b, boolean planned) {
        if (b == null || !on(p)) return;
        add("heal.calls", 1); if (planned) add("heal.plans", 1);
        String bad = staffBad(b[0] - Ledger.Wallet.coins(p), (int) (held(p, W8Polish.BUNYIP) - b[1]), 0);
        if (bad != null) { add("heal." + bad, 1); Seed.note(p, "heal " + bad + " coins " + b[0] + "->" + Ledger.Wallet.coins(p)); }
        long out = b[0] - Ledger.Wallet.coins(p), in = held(p, W8Polish.BUNYIP) - b[1]; String kind = buyKind(out, earned() - b[2]); // w21: the buy path
        if (kind != null) { add("heal.buys", 1); add("heal.spent", (int) out); add("heal." + kind, 1); if (in > 1) add("heal.multi", 1); Seed.note(p, "heal buy " + kind + " paid=" + out + " pouches+" + in + " coins=" + Ledger.Wallet.coins(p)); }
    }
    /** Bob.combatSummon, a bunyip is out: watch the owner's lifepoints each tick while it stays out; +2 in one tick is its heal. */
    static void healSummon(final Player p) {
        if (!on(p)) return;
        add("heal.summoned", 1);
        try {
            core.game.world.GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
                int last = -1, n, lines;
                @Override public boolean pulse() {
                    try {
                        content.global.skill.summoning.familiar.Familiar f = p.getFamiliarManager().getFamiliar();
                        if (f == null || f.getPouchId() != W8Polish.BUNYIP || ++n > 6000) return true;
                        int hp = p.getSkills().getLifepoints();
                        if (last >= 0 && hp == last + 1) add("heal.ones", 1);
                        if (last >= 0 && healStep(last, hp)) {
                            add("heal.heals", 1);
                            if (lines++ < 3) System.out.println("[Scen] heal " + Seed.word(p.getUsername()) + " bunyip heal +2 (lifepoints " + last + " -> " + hp + ")");
                        }
                        last = hp;
                        return false;
                    } catch (Throwable t) { return true; }
                }
            });
        } catch (Throwable t) { System.out.println("[Scen] heal watch: " + t); }
    }

    /** -Dsmartbots.seed.scenStock: world lots into bot stock (Ledger.supply, as a bot's own supply), topped up once from the judge's first beat (game thread). */
    static void stock() {
        Player by = null; // w21 -Dsmartbots.seed.scenStockBot: the lots are one bot's own supply, so a buyer's coins go to a bot
        if (Seed.STOCK_BOT) try { for (Player q : core.game.world.repository.Repository.getPlayers()) if (q != null && q.isArtificial()) { by = q; break; } } catch (Throwable t) { System.out.println("[Scen] stock owner: " + t); }
        if (Seed.STOCK_BOT) System.out.println("[Scen] stock owner " + (by == null ? "world (no bot online)" : Seed.word(by.getUsername())));
        for (int[] it : Seed.STOCK) {
            try {
                int have = core.game.ge.GrandExchange.Companion.getBotstockForId(it[0]);
                if (have < it[1]) { Ledger.supply(by, it[0], it[1] - have); System.out.println("[Scen] stock " + it[0] + " topped up " + have + " -> " + it[1]); }
            } catch (Throwable t) { System.out.println("[Scen] stock " + it[0] + ": " + t); }
        }
    }
    static long alarms() { return (long) CoinWatch.dropAlarms + CoinWatch.paidAlarms; }
    static volatile long alarms0;

    // ---------------------------------------------------------------- the judge (game Pulse, started from Seed.apply)

    static synchronized void start(String[] names, int ticks) {
        if (started || !Seed.ON || names.length == 0) return;
        started = true; alarms0 = alarms();
        final int t0 = core.game.world.GameWorld.getTicks(), end = t0 + Math.max(100, ticks);
        System.out.println("[Scen] judge on: " + String.join(",", names) + " for " + ticks + " ticks (tick " + t0 + " -> " + end + ")");
        core.game.world.GameWorld.getPulser().submit(new core.game.system.task.Pulse(50) {
            int beat;
            @Override public boolean pulse() {
                try {
                    gameThread = Thread.currentThread().getName();
                    if (beat == 0 && Seed.STOCK.length > 0) stock();
                    alchMaxHour = Math.max(alchMaxHour, W6Magic.alchHourGp);
                    int now = core.game.world.GameWorld.getTicks();
                    boolean last = now >= end;
                    if (last || ++beat % 10 == 0) for (String n : names) System.out.println("[Scen] " + (last ? line(n) : "tick " + (now - t0) + " " + n + " " + facts(n)));
                    return last;
                } catch (Throwable t) { System.out.println("[Scen] judge: " + t); return false; }
            }
        });
    }

    static long[] extra(String n) {
        switch (n) {
            case "alchAll": return new long[]{W6Magic.alchDone, alchMaxHour, W6Magic.ALCH_HOUR, W6Magic.alchCapped, W6Magic.ALCH_HOUR < 20_000L ? 1 : 0};
            case "anyStaff": return new long[]{W6Magic.staffBought + W6Magic.staffWorn};
            case "herbSupply": {
                boolean player = false, bot = false;
                try { AfkTasks.Spot[] a = AfkTasks.spotsFor(AfkTasks.Task.LIMPWURT, false), b = AfkTasks.spotsFor(AfkTasks.Task.LIMPWURT, true); player = a != null && a.length > 0; bot = b != null && b.length > 0; } catch (Throwable ignored) { }
                boolean same = limpThread != null && limpThread.equals(gameThread);
                return new long[]{W6Herbs.limpStarts, player ? 1 : 0, bot ? 1 : 0, same ? 1 : 0};
            }
            case "altarLoop": {
                long oldest = 0, now = System.currentTimeMillis();
                for (Long t : RC_OPEN.values()) oldest = Math.max(oldest, now - t);
                return new long[]{RcRun.loops, RcRun.crafted, RcRun.backGiveUps, Seed.RC_STICK ? 1 : 0, oldest};
            }
            case "iceGiantFix": return new long[]{W6Slayer.iceDropped.get()};
            case "herbs2": return new long[]{W6Herbs.herbsUp};
            case "vannakaHunts": return new long[]{W6Slayer.built.get()};
            case "tele": return new long[]{W6Magic.teleCast, W6Magic.teleBought, W6Magic.teleGp};
            case "prayerStat": return new long[]{alarms() - alarms0};
            case "kit2": return new long[]{W6Combat.kitBought};
            case "wwQuest": return new long[]{W6Combat.wwBoosted, alarms() - alarms0};
            case "weaponByFoe": return new long[]{W8Polish.foeCrush + W8Polish.foeSlash, W8Polish.foeSwaps, W8Polish.foeMaceBought};
            case "heal": return new long[]{W8Polish.healPlans, W8Polish.healSummonOk, W8Polish.healBought};
            default: return new long[0];
        }
    }

    static String facts(String n) {
        StringBuilder b = new StringBuilder();
        String pre = n.equals("fix9b") ? "pass." : n.equals("capFix") ? "ca" : n.equals("anyStaff") ? "staff." : n.equals("altarLoop") ? "rc." : n.equals("iceGiantFix") ? "ice." : n.equals("herbs2") ? "h2." : n.equals("vannakaHunts") ? "vh." : n.equals("tele") ? "tele." : n.equals("prayerStat") ? "pray." : n.equals("kit2") ? "kit2." : n.equals("weaponByFoe") ? "foe." : n.equals("heal") ? "heal." : "\0";
        new java.util.TreeMap<>(C).forEach((k, v) -> { if (k.startsWith(pre) || n.equals("capFix") && k.startsWith("fare.") || n.equals("fix9b") && k.equals("too_many_steps")) b.append(k).append('=').append(v.get()).append(' '); });
        long[] x = extra(n);
        if (x.length > 0) b.append("x=").append(java.util.Arrays.toString(x).replace(" ", ""));
        if (n.equals("herbSupply")) b.append(" limpThread=").append(limpThread).append(" gameThread=").append(gameThread);
        return b.toString().trim();
    }

    static String line(String n) {
        String why = verdict(n, Scen::get, extra(n));
        return (why == null ? "PASS " : "FAIL ") + n + " " + facts(n) + (why == null ? "" : " why=" + why.replace(' ', '_'));
    }

    static String[] names(String s) {
        if (s == null || s.trim().isEmpty()) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String a : s.split(",")) { String t = a.trim(); for (String k : NAMES) if (k.equalsIgnoreCase(t) && !out.contains(k)) out.add(k); }
        return out.toArray(new String[0]);
    }
}
