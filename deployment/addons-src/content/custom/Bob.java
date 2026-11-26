package content.custom;

import content.global.skill.summoning.familiar.BurdenBeast;
import content.global.skill.summoning.familiar.Familiar;
import content.global.skill.summoning.familiar.FamiliarManager;
import core.game.container.Container;
import core.game.container.impl.BankContainer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.map.Location;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * P86 Summoning S5 (the design notes section 2): "for fun" familiars. Off by default:
 * -Dbots.summoning.fun=true (needs -Dbots.summoning=true). Flag off = every hook returns on its first line (FUN false), so
 * nothing here ever touches a FamiliarManager.
 *
 * A bot that finished Wolf Whistle, about to WALK from a bank to a break hub, may take a spirit wolf (12047) or dreadfowl
 * (12043) pouch from its bank and summon it; the real NPC follows it there and stands with it. The visit end, the familiar's
 * own timer (4-6 min), or any exit below ends it. They carry nothing, so a timer end is harmless.
 *
 * S4 (beast of burden) is NOT built here: live bots are Summoning 4-5 and the first beast of burden needs 13. The parts of the
 * S4 Bob.java this file has are the ones S5 needs: OUT, the fleet cap, leave() (the one exit), leaveAll, sweep, the tally.
 *
 * Exits (design 1.8, all one-line hooks calling leave()): Spectacle.teleport, every raw setTeleportLocation (through tele()),
 * Watchdog.rescue, logOut and every bot.clear() (F14: Player.clear returns early for bots and never removes the familiar NPC),
 * death, AfkTasks.launch / Runner finish / stop / superseded / the stop command, the brain dropping its runner, bone / pouch /
 * slayer / quest starts, shutdown. leave() dismisses ONLY familiars this file summoned (OUT), never a quest's own wolf.
 */
final class Bob {
    private Bob() { }

    /** -Dbots.summoning.fun (needs bots.summoning). Read without touching Summon so unit tests need no world. */
    static final boolean FUN = Boolean.getBoolean("bots.summoning") && Boolean.getBoolean("bots.summoning.fun");
    /** P92 S4: -Dbots.summoning.bob (needs bots.summoning): beast of burden on gathering trips. Off = no hook does anything. */
    static final boolean ON = Boolean.getBoolean("bots.summoning") && Boolean.getBoolean("bots.summoning.bob");
    /** Either half on: the shared exits (leave, tele, leaveAll, sweep) work. Both off = they return on their first line. */
    static final boolean COMBAT = Boolean.getBoolean("bots.summoning") && Boolean.getBoolean("bots.summoning.combat");
    /** Summoning in combat v2: -Dbots.summoning.combat2 (needs bots.summoning.combat). Off = v1 exactly. */
    static final boolean COMBAT2 = COMBAT && Boolean.getBoolean("bots.summoning.combat2");
    static final boolean ANY = FUN || ON || COMBAT;
    /** train-57 fix5: -Dbots.summoning.fix5 (needs bots.summoning.fun). A fun familiar whose own timer ended in the engine leaves OUT at the bot's next think()
     *  instead of at the next sweep() (15 min tally or another bot's summon). Off = the old path exactly. Beasts: guard(); combat: combatGone() (summon4). */
    static final boolean FIX5 = FUN && Boolean.getBoolean("bots.summoning.fix5");
    /** Familiars out, all bots together (S4 + S5 share it): -Dbots.summoning.out, default 20, hard top 20. */
    static final int CAP = Math.max(1, Math.min(20, Integer.getInteger("bots.summoning.out", 20)));
    /** S5 seats of the CAP (design 2.5). */
    static final int FUN_SEATS = Math.min(6, CAP);
    /** Farm trials only: every eligible walked visit summons (no roll), 3-min gap instead of 2-4 h. */
    static final boolean FARM = Boolean.getBoolean("test.tickprobe"); // harness-only flag (COMMON): test knobs refuse to act anywhere else
    static final String TEST_PROP = System.getProperty("smartbots.testFun", "");
    static final boolean TEST = FARM && "true".equals(TEST_PROP);
    /** Farm only (-Dsmartbots.testFun=seed or true): a bot reaching an eligible visit gets Wolf Whistle, Summoning 4 and 2+2 pouches once (farm bots have none). */
    static final boolean TEST_SEED = FARM && ("seed".equals(TEST_PROP) || TEST);
    static final Set<String> SEEDED = ConcurrentHashMap.newKeySet(), TEST_OUT = ConcurrentHashMap.newKeySet();
    static volatile long SEEDS;
    /** Farm trials only: after 15 s out, force an exit, alternating a teleport and a logout, to prove those hooks. */
    static final boolean TEST_EXIT = FARM && Boolean.getBoolean("smartbots.testFunExit");

    static final int WOLF = 12047, FOWL = 12043;
    static final int WOLF_LVL = 1, FOWL_LVL = 4, FUN_COST = 1;
    /** S4 rows {pouch, level, points}: only so a fun summon never leaves too few points for a beast of burden the bot owns. */
    static final int[][] BOB_POINTS = {{12019, 13, 2}, {12063, 25, 3}, {12087, 40, 5}, {12007, 52, 6}, {12031, 67, 7}};

    static final String[] SUMMON_WOLF = {"Come on, wolf.", "Out you come.", "Stretch your legs.", "Walk with me a bit.", "Heel. Good."};
    static final String[] SUMMON_FOWL = {"Out you come.", "Stretch your legs.", "Walk with me a bit.", "Dreadfowl needs the exercise."};
    static final String[] DISMISS = {"Off you go.", "Thanks, that's all.", "Back in the pouch world with you."};
    static final long LINE_GAP_MS = 20 * 60_000L;

    /** Lower-case name -> the familiar this file summoned for it. */
    static final Map<String, Familiar> OUT = new ConcurrentHashMap<>();
    static final Set<String> FUN_KEYS = ConcurrentHashMap.newKeySet();
    static final Map<String, Long> FUN_NEXT = new ConcurrentHashMap<>(), LINE_AT = new ConcurrentHashMap<>(), OUT_AT = new ConcurrentHashMap<>();
    static final Map<String, Location> FROM = new ConcurrentHashMap<>();
    static final Random RNG = new Random();

    /** funSummon reach/skip counts: visits, teleportVisit, gap, locked, busy, place, notBank, noSlot, noPouch, roll. */
    static final long[] WHY = new long[10];
    static volatile long SUMMONED, DISMISSED, FUN_N, EXPIRED, ORPHANS, FAILS, CAPPED, LINES, RENEW_WANTS, PEAK, TEST_EXITS;

    // ---- pure rules (unit-tested without a world) ----

    /** Points of the best beast of burden row the bot could use (level and a banked pouch); 0 if none. */
    static int bestBobPoints(int level, IntUnaryOperator bank) {
        int best = 0;
        for (int[] r : BOB_POINTS) if (r[1] <= level && bank.applyAsInt(r[0]) >= 1) best = Math.max(best, r[2]);
        return best;
    }

    /** Which fun pouch to summon, 0 for none: the one of wolf/dreadfowl the bot can use and has more of; points stay >= keep. */
    static int funPouch(int level, int points, int wolves, int fowls, int keep) {
        if (points - FUN_COST < Math.max(0, keep) || points < FUN_COST) return 0;
        int w = level >= WOLF_LVL ? Math.max(0, wolves) : 0, f = level >= FOWL_LVL ? Math.max(0, fowls) : 0;
        if (w <= 0 && f <= 0) return 0;
        return f > w ? FOWL : WOLF;
    }

    /** A free seat for an S5 familiar: total under CAP and S5 under its 6. */
    static boolean seatFree(int out, int fun) { return out < CAP && fun < FUN_SEATS; }

    /** The visit is walked (no teleport): not the Exchange, or the Exchange within TELEPORT_MIN. */
    static boolean walked(boolean toExchange, double dist, int teleportMin) { return !toExchange || dist <= teleportMin; }

    /** Chance an eligible visit summons: 0.5 x interest (10%-50%). */
    static double chance(double interest) { return 0.5 * Math.max(0.2, Math.min(1.0, interest)); }

    /** Next allowed fun summon after one: 2 h + 0-2 h (test: 3 min). */
    static long gapMs(boolean test, double u) { double k = Math.max(0, Math.min(1, u)); return test ? 180_000L : (long) (7_200_000L + 7_200_000L * k); }

    /** Wilderness or deep underground: never. */
    static boolean badPlace(Location l) { return l == null || l.getZ() != 0 || l.getY() >= 3520; }

    /** Design 2.3 desire: +0.20 when the bot owns 2+ usable pouches and its points are below max(2, 30% of its level). */
    static double desirePlus(double s, int level, int points, int usablePouches) {
        if (usablePouches < 2 || points >= Math.max(2, (int) Math.ceil(0.3 * level))) return s;
        return Math.min(0.95, s + 0.20);
    }

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    // ---- hooks ----

    /** SA loiter, before the teleport test: a walked visit from a bank may take a fun familiar along. */
    static void funSummon(SmartAdventurers.Brain b, long now, Location dest, boolean toExchange) {
        if (!FUN || b == null) return;
        Player bot = b.bot;
        try {
            if (bot == null || !bot.isArtificial() || !bot.isActive() || dest == null) return;
            String k = key(bot);
            WHY[0]++;
            if (!walked(toExchange, bot.getLocation().getDistance(dest), AfkTasks.Runner.TELEPORT_MIN)) { WHY[1]++; return; }
            if (now < FUN_NEXT.getOrDefault(k, 0L)) { WHY[2]++; return; }
            if (TEST_SEED && SEEDED.add(k)) seed(bot);
            if (!Summon.unlocked(bot)) { WHY[3]++; return; }
            FamiliarManager fm = bot.getFamiliarManager();
            if (inUse(bot)) return;
            if (b.busy()) { WHY[4]++; return; }
            if (bot.inCombat() || badPlace(bot.getLocation()) || badPlace(dest)) { WHY[5]++; return; }
            if (!b.atBank()) { WHY[6]++; return; }
            if (bot.getInventory().freeSlots() < 1) { WHY[7]++; return; }
            sweep();
            if (!seatFree(OUT.size(), FUN_KEYS.size())) { CAPPED++; return; }
            BankContainer bk = bot.getBank();
            int lvl = bot.getSkills().getStaticLevel(Skills.SUMMONING), pts = bot.getSkills().getLevel(Skills.SUMMONING);
            int id = funPouch(lvl, pts, bk.getAmount(WOLF), bk.getAmount(FOWL), bestBobPoints(lvl, bk::getAmount));
            if (id == 0) { WHY[8]++; return; }
            if (!TEST && RNG.nextDouble() >= chance(Summon.interest(bot.getUsername()))) { WHY[9]++; FUN_NEXT.put(k, now + 20 * 60_000L); return; } // one roll per visit, at most every 20 min
            if (!bk.remove(new Item(id, 1))) return;
            if (!bot.getInventory().add(new Item(id, 1))) { bk.add(new Item(id, 1)); return; }
            fm.summon(new Item(id), false);
            Familiar f = fm.getFamiliar();
            if (f == null || f.getPouchId() != id) { // refused (zone, room): the pouch goes back
                if (bot.getInventory().remove(new Item(id, 1))) bk.add(new Item(id, 1));
                FAILS++; FUN_NEXT.put(k, now + 30 * 60_000L);
                return;
            }
            OUT.put(k, f); FUN_KEYS.add(k); OUT_AT.put(k, now); FROM.put(k, bot.getLocation());
            SUMMONED++; FUN_N++; PEAK = Math.max(PEAK, OUT.size());
            FUN_NEXT.put(k, now + gapMs(TEST, RNG.nextDouble()));
            System.out.println("[Summon] " + bot.getUsername() + " summoned a " + f.getName() + " for the walk (out=" + OUT.size() + ", fun=" + FUN_KEYS.size() + ")");
            line(bot, now, id == WOLF ? SUMMON_WOLF : SUMMON_FOWL);
        } catch (Throwable t) {
            System.out.println("[Summon] fun summon " + (bot != null ? bot.getUsername() : "?") + ": " + t);
            Watchdog.fexc("Bob.funSummon", bot, t);
        }
    }

    /** Farm-only seed (see TEST_SEED). */
    static void seed(Player bot) {
        try {
            core.game.node.entity.player.link.quest.Quest q = bot.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE);
            if (q != null && !q.isCompleted(bot)) bot.getQuestRepository().setStage(q, 100);
            if (bot.getSkills().getStaticLevel(Skills.SUMMONING) < FOWL_LVL) { bot.getSkills().setStaticLevel(Skills.SUMMONING, FOWL_LVL); }
            bot.getSkills().setLevel(Skills.SUMMONING, bot.getSkills().getStaticLevel(Skills.SUMMONING));
            bot.getBank().add(new Item(WOLF, 2)); bot.getBank().add(new Item(FOWL, 2));
            SEEDS++;
            System.out.println("[Summon] test seed " + bot.getUsername() + ": Wolf Whistle, Summoning " + bot.getSkills().getStaticLevel(Skills.SUMMONING) + ", 2 wolf + 2 dreadfowl pouches");
        } catch (Throwable t) { System.out.println("[Summon] test seed " + bot.getUsername() + ": " + t); }
    }

    /** SA think, every pass at the break check: the visit is over -> dismiss. Trials: forced exits. */
    static void think(SmartAdventurers.Brain b, long now) {
        if (!FUN || b == null || b.bot == null || OUT.isEmpty()) return;
        String k = key(b.bot);
        if (!FUN_KEYS.contains(k)) return; // S5 familiars only: an S4 beast of burden ends with its trip, not the visit
        if (FIX5 && funGone(b.bot, OUT.get(k))) { // fix5: the engine ended it (timer); drop the stale entry now, leave() counts it as expired
            Familiar f = OUT.get(k); long at = OUT_AT.getOrDefault(k, now);
            leave(b.bot, "expired");
            System.out.println("[Summon] " + b.bot.getUsername() + " fun familiar ended on its own timer (fix5, " + (f == null ? "?" : f.getName()) + ", " + (now - at) / 1000 + " s out, out=" + OUT.size() + ", fun=" + FUN_KEYS.size() + ")");
            return;
        }
        if (now >= b.idleUntil) { leave(b.bot, "trip end"); return; }
        if (TEST_EXIT && now - OUT_AT.getOrDefault(k, now) >= 15_000L) {
            long n = TEST_EXITS++;
            if (n % 2 == 0) { System.out.println("[Summon] test: teleport " + b.bot.getUsername()); Spectacle.teleport(b.bot, b.bot.getLocation().transform(0, 0, 0)); }
            else if (b.bot instanceof core.game.bots.AIPlayer && TEST_OUT.add(k)) { // once per bot
                System.out.println("[Summon] test: log out " + b.bot.getUsername());
                final core.game.bots.AIPlayer ai = (core.game.bots.AIPlayer) b.bot; final SmartAdventurers.Brain me = b;
                core.game.world.GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() { SmartAdventurers.logOut(ai, me); return true; } });
            }
        }
    }

    /** fix5: a tracked fun familiar the engine no longer has for this player (same test as combatGone). */
    static boolean funGone(Player p, Familiar tracked) { return combatGone(p, tracked); }

    /** One-line hook for raw setTeleportLocation sites: leave(p, "teleport") and give p back. */
    static Player tele(Player p) { if (ANY) leave(p, "teleport"); return p; }

    /** THE one exit. Safe any time, with no familiar, twice, with the flag off (returns at once). Dismisses only our own familiar. */
    static void leave(Player p, String why) {
        if (!ANY || p == null || OUT.isEmpty()) return;
        String k;
        try { k = key(p); } catch (Throwable t) { return; }
        Familiar f = OUT.remove(k);
        FUN_KEYS.remove(k);
        Long at = OUT_AT.remove(k); Location from = FROM.remove(k);
        if (COMBAT_KEYS.remove(k)) combatEnd(p, k, f, why, at);
        if (f == null) return;
        try {
            FamiliarManager fm = p.getFamiliarManager();
            if (fm != null && fm.getFamiliar() == f) {
                int d = -1, walked = -1;
                try { d = (int) Math.round(f.getLocation().getDistance(p.getLocation())); if (from != null) walked = (int) Math.round(from.getDistance(p.getLocation())); } catch (Throwable ignored) { }
                boolean beast = f instanceof BurdenBeast;
                if (beast) empty(p, (BurdenBeast) f, why); // S4: NEVER dismiss a loaded beast (design finding 2)
                if ("trip end".equals(why) && !beast) line(p, System.currentTimeMillis(), DISMISS);
                fm.dismiss();
                DISMISSED++;
                if (beast) { BOB_DISMISSED++; clearRunner(k, f); }
                System.out.println("[Summon] " + p.getUsername() + " dismissed its " + f.getName() + " (" + why + ", " + d + " tiles from it, walked " + walked + ", "
                        + (at == null ? "?" : (System.currentTimeMillis() - at) / 1000) + " s out, out=" + OUT.size() + ")");
            } else if (f.isActive() && (fm == null || fm.getFamiliar() != f)) { // detached: owner gone or replaced, NPC still in the world
                if (f instanceof BurdenBeast) { empty(p, (BurdenBeast) f, "orphan"); clearRunner(k, f); }
                try { f.dismiss(); } catch (Throwable t) { try { f.clear(); } catch (Throwable ignored) { } }
                ORPHANS++;
                System.out.println("[Summon] " + p.getUsername() + " orphan " + f.getName() + " removed (" + why + ")");
            } else { EXPIRED++; if (f instanceof BurdenBeast) clearRunner(k, f); } // its own timer ended it first
        } catch (Throwable t) {
            System.out.println("[Summon] leave " + k + " (" + why + "): " + t);
            Watchdog.fexc("Bob.leave", p, t);
        }
    }

    /** Shutdown: everyone's familiar goes before the profiles are saved. */
    static void leaveAll(String why) {
        if (!ANY) return;
        System.out.println("[Summon] fun " + why + ": out=" + OUT.size() + " summoned=" + SUMMONED + " dismissed=" + DISMISSED + " expired=" + EXPIRED + " orphans=" + ORPHANS + " peak=" + PEAK);
        for (Map.Entry<String, Familiar> e : OUT.entrySet()) {
            Player o = null;
            try { o = e.getValue().getOwner(); } catch (Throwable ignored) { }
            if (o != null) leave(o, why);
            else { Familiar f = OUT.remove(e.getKey()); FUN_KEYS.remove(e.getKey()); COMBAT_KEYS.remove(e.getKey()); CSTAT.remove(e.getKey()); if (f != null && f.isActive()) try { if (f instanceof BurdenBeast && ((BurdenBeast) f).getContainer().itemCount() > 0) { SPILLS += ((BurdenBeast) f).getContainer().itemCount(); System.out.println("[Summon] bob " + e.getKey() + ": no owner at " + why + ", " + ((BurdenBeast) f).getContainer().itemCount() + " items lost with the NPC"); ((BurdenBeast) f).getContainer().clear(); } f.clear(); ORPHANS++; } catch (Throwable ignored) { } }
        }
    }

    /** Drops OUT entries whose familiar is gone (timer end); removes an orphan NPC whose owner logged out (F14 last net). */
    static void sweep() {
        if (!ANY || OUT.isEmpty()) return;
        for (Map.Entry<String, Familiar> e : OUT.entrySet()) {
            Familiar f = e.getValue(); Player o = null;
            try { o = f.getOwner(); } catch (Throwable ignored) { }
            boolean ownerOk = o != null && o.isActive();
            boolean mine = ownerOk && o.getFamiliarManager() != null && o.getFamiliarManager().getFamiliar() == f;
            if (mine && f.isActive()) continue;
            if (o != null && ownerOk) { leave(o, "sweep"); continue; }
            OUT.remove(e.getKey()); FUN_KEYS.remove(e.getKey()); OUT_AT.remove(e.getKey()); FROM.remove(e.getKey()); COMBAT_KEYS.remove(e.getKey()); CSTAT.remove(e.getKey());
            if (f.isActive() && f instanceof BurdenBeast && o != null) empty(o, (BurdenBeast) f, "orphan"); // F14: the items go to the gone bot's bank, never the ground
            if (f.isActive()) { try { f.dismiss(); } catch (Throwable t) { try { f.clear(); } catch (Throwable ignored) { } } ORPHANS++; System.out.println("[Summon] orphan familiar of " + e.getKey() + " removed (sweep)"); }
            else EXPIRED++;
        }
    }

    /** SA wantsSummon: design 2.3 desire +0.20 (flag off: s unchanged). */
    static double desire(double s, Player bot) {
        if (!FUN || bot == null) return s;
        try {
            int lvl = bot.getSkills().getStaticLevel(Skills.SUMMONING), pts = bot.getSkills().getLevel(Skills.SUMMONING);
            BankContainer bk = bot.getBank();
            int n = bk.getAmount(WOLF) + (lvl >= FOWL_LVL ? bk.getAmount(FOWL) : 0);
            for (int[] r : BOB_POINTS) if (r[1] <= lvl) n += bk.getAmount(r[0]);
            double out = desirePlus(s, lvl, pts, n);
            if (out != s) RENEW_WANTS++;
            return out;
        } catch (Throwable t) { return s; }
    }

    /** At most one line a moment, 30%, only with a human near, never in a greet scene, 20 min apart per bot. */
    static void line(Player bot, long now, String[] pool) {
        try {
            String k = key(bot);
            if (now - LINE_AT.getOrDefault(k, 0L) < LINE_GAP_MS || RNG.nextDouble() >= 0.3) return;
            if (Spectacle.watcher(bot) == null || Scenes.Greet.escorting(bot)) return;
            LINE_AT.put(k, now); LINES++;
            BotChat.say(bot, BotChat.pick(pool));
        } catch (Throwable ignored) { }
    }

    /** Familiars of logged-in bots right now plus orphans in OUT (trial check: must equal OUT.size()). */
    static int fleetCount() {
        int n = 0;
        try { for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) if (b.bot != null && b.bot.isActive() && b.bot.getFamiliarManager() != null && b.bot.getFamiliarManager().hasFamiliar()) n++; } catch (Throwable ignored) { }
        return n;
    }

    /** Appended to the [Summon] tally (flag on only). */
    static String tallyLine() {
        sweep();
        return " fun: out=" + OUT.size() + " funOut=" + FUN_KEYS.size() + " peak=" + PEAK + " cap=" + CAP + " fleetFam=" + fleetCount() + " summoned=" + SUMMONED + " dismissed=" + DISMISSED
                + " expired=" + EXPIRED + " orphans=" + ORPHANS + " fails=" + FAILS + " capped=" + CAPPED + " lines=" + LINES + " renewWants=" + RENEW_WANTS + " why=" + java.util.Arrays.toString(WHY).replace(" ", "") + (TEST_SEED ? " seeds=" + SEEDS : "");
    }

    // =====================================================================================================================
    // P92 S4: beast of burden on gathering trips (design 1.2-1.9). Every hook below starts with `if (!ON) return`.
    // Products only: tools, food, coins, charms, pouches, runes, ammo and stackables never move. A loaded beast is never
    // dismissed: leave() empties it first (pack, then the bank directly), so nothing can reach the ground.
    // =====================================================================================================================

    /** {pouch, level, slots, minutes, points} from SummoningPouch + the NPC classes (live jar). */
    static final int[][] BOBS = {
            {12019, 13, 3, 16, 2},   // thorny snail
            {12063, 25, 6, 22, 3},   // spirit kalphite
            {12087, 40, 9, 30, 5},   // bull ant
            {12007, 52, 12, 36, 6},  // spirit terrorbird
            {12031, 67, 18, 43, 7},  // war tortoise
    };
    /** Farm only (-Dsmartbots.testBob=<pouch id>): each bot once gets the pouch's level, full points, Wolf Whistle and 2 pouches. */
    static final int TEST_BOB = FARM ? Integer.getInteger("smartbots.testBob", 0) : 0;
    static final Set<String> BOB_SEEDED = ConcurrentHashMap.newKeySet();
    /** plan() skips: notEligible, noPick, noSlot, notAtBank, hasFamiliar, takeFail. */
    static final long[] BWHY = new long[6];
    /** w29: -Dbots.summoning.bob.why (needs bots.summoning.bob). Log only: splits why[0] (notEligible) by the first eligible() test that failed,
     *  as " elig=[...]" at the end of the bob counter line. Off = the line and every counter as before. No RNG, no behaviour. */
    static final boolean WHY2 = ON && Boolean.getBoolean("bots.summoning.bob.why");
    /** eligible() fails, first test that failed: notBankTrip (drop/cross/quest hold/no task), kind (not tree/rock/fish/pick), processing (haul/process/chain), place, bankFar (none on plane 0 within 120), locked (no Wolf Whistle or a quest running), busy (companion/delivery/escort). */
    static final long[] EWHY = new long[7];
    static int eligCode(boolean bankTrip, boolean kind, boolean plain, boolean place, boolean bankNear, boolean unlocked, boolean free) {
        return !bankTrip ? 0 : !kind ? 1 : !plain ? 2 : !place ? 3 : !bankNear ? 4 : !unlocked ? 5 : !free ? 6 : -1;
    }
    /** Same tests in the same order as eligible(); only called after eligible() said no, under WHY2. */
    static void eligNote(AfkTasks.Runner r) {
        try {
            if (r == null || r.p == null) return;
            Player p = r.p;
            boolean bankTrip = p.isArtificial() && r.bank && !r.crossOnly && !r.questHold && r.task != null;
            boolean kind = r.task != null && kindOk(r.task.kind);
            boolean plain = !(r.smeltHaul || r.cookHaul || r.process != AfkTasks.Process.NONE || r.chainOut != null);
            boolean place = placeOk(r.spot);
            Location bk = place ? Watchdog.nearestBank(r.spot) : null;
            boolean bankNear = bk != null && bk.getZ() == 0 && bk.getDistance(r.spot) <= 120;
            boolean unlocked = Summon.unlocked(p) && !QuestRunner.active(p);
            SmartAdventurers.Brain b = SmartAdventurers.brainOf(p);
            boolean free = !(b != null && (b.companion != null || b.delivering || Scenes.Greet.escorting(p)));
            int c = eligCode(bankTrip, kind, plain, place, bankNear, unlocked, free);
            if (c >= 0) EWHY[c]++;
        } catch (Throwable ignored) { }
    }
    static volatile long BOB_PLANS, BOB_SUMMONED, BOB_DISMISSED, BOB_TRIPS, ITEMS_CARRIED, SPILLS, TO_BANK_DIRECT, LOST, BOB_FAILS, EVICTS, BOB_CAPPED, RE_SUMMONS;
    static { if (!FARM && Integer.getInteger("smartbots.testBob", 0) > 0) System.out.println("[Summon] smartbots.testBob ignored: not a farm world"); }

    // ---- pure rules ----

    /** The highest row the bot can use: level, a pouch (bank or pack) and the points. Null if none. */
    static int[] pickRow(int level, int points, IntUnaryOperator owned) {
        for (int i = BOBS.length - 1; i >= 0; i--) { int[] r = BOBS[i]; if (r[1] <= level && owned.applyAsInt(r[0]) >= 1 && points >= r[4]) return r; }
        return null;
    }

    static int[] rowOf(int pouch) { for (int[] r : BOBS) if (r[0] == pouch) return r; return null; }

    static boolean isBobPouch(int id) { return rowOf(id) != null; }

    /** Gathering kinds only: never fights, thieving, ground loot, smelting, bank loops or stalls. */
    static boolean kindOk(AfkTasks.Kind k) { return k == AfkTasks.Kind.TREE || k == AfkTasks.Kind.ROCK || k == AfkTasks.Kind.FISH || k == AfkTasks.Kind.PICK; }

    /** Plane 0, surface, not the essence mine, not the Wilderness (badPlace: y >= 3520). */
    static boolean placeOk(Location spot) { return spot != null && !badPlace(spot) && spot.getY() < 6400 && !AfkTasks.Runner.essenceMine(spot); }

    /** Products only: an unstackable, tradeable product worth <= 50,000 that is not a tool, food, charm, coins or a pouch. */
    static boolean storableId(int id, boolean product, boolean kept, boolean stackable, boolean tradeable, int value) {
        if (!product || kept || stackable || !tradeable || value > 50_000) return false;
        if (Summon.isCharm(id) || isBobPouch(id) || id == 995) return false;
        for (int f : AfkTasks.Runner.FOOD) if (f == id) return false;
        return true;
    }

    // ---- hooks ----

    static boolean eligible(AfkTasks.Runner r) {
        if (!ON || r == null || r.p == null) return false;
        Player p = r.p;
        if (!p.isArtificial() || !r.bank || r.crossOnly || r.questHold || r.task == null || !kindOk(r.task.kind)) return false;
        if (r.smeltHaul || r.cookHaul || r.process != AfkTasks.Process.NONE || r.chainOut != null) return false;
        if (!placeOk(r.spot)) return false;
        Location bk = Watchdog.nearestBank(r.spot);
        if (bk == null || bk.getZ() != 0 || bk.getDistance(r.spot) > 120) return false; // a walked trip: no planned teleport while it is out
        if (!Summon.unlocked(p) || QuestRunner.active(p)) return false;
        SmartAdventurers.Brain b = SmartAdventurers.brainOf(p);
        if (b != null && (b.companion != null || b.delivering || Scenes.Greet.escorting(p))) return false;
        return true;
    }

    static int owned(Player p, int id) { return p.getBank().getAmount(id) + p.getInventory().getAmount(id); }

    /** The pick for this bot now, or null (below level 13 always null: live bots are Summoning 4-5). */
    static int[] pick(Player p) {
        return pickRow(p.getSkills().getStaticLevel(Skills.SUMMONING), p.getSkills().getLevel(Skills.SUMMONING), id -> owned(p, id));
    }

    /** SA, just before AfkTasks.launch: at a bank, one pouch bank -> pack for this trip (r.bobPouch), else nothing. */
    static void plan(AfkTasks.Runner r) {
        if (!ON || r == null || r.p == null) return;
        Player p = r.p;
        r.bobPouch = 0; r.bobTried = false; r.bobPass = 0; r.bobCarried = 0;
        try {
            if (TEST_BOB > 0 && p.isArtificial() && BOB_SEEDED.add(key(p))) seedBob(p, TEST_BOB);
            if (!eligible(r)) { BWHY[0]++; if (WHY2) eligNote(r); return; }
            int[] row = pick(p);
            if (row == null) { BWHY[1]++; return; }
            FamiliarManager fm = p.getFamiliarManager();
            if (inUse(p) || r.famPouch != 0) { BWHY[4]++; return; }
            int id = row[0];
            if (p.getInventory().getAmount(id) < 1) {
                if (p.getInventory().freeSlots() < 1) { BWHY[2]++; return; }
                Location bk = Watchdog.nearestBank(p.getLocation());
                if (bk == null || bk.getDistance(p.getLocation()) > 10) { BWHY[3]++; return; }
                BankContainer bank = p.getBank();
                if (!bank.remove(new Item(id, 1))) { BWHY[5]++; return; }
                if (!p.getInventory().add(new Item(id, 1))) { bank.add(new Item(id, 1)); BWHY[5]++; return; }
            }
            r.bobPouch = id; BOB_PLANS++; Seed.note(p, "beast plan pouch=" + id + " job=" + r.label());
        } catch (Throwable t) { System.out.println("[Summon] bob plan " + p.getUsername() + ": " + t); Watchdog.fexc("Bob.plan", p, t); }
    }

    /** Farm-only seed for -Dsmartbots.testBob (and the unit test). */
    static void seedBob(Player p, int pouch) {
        int[] row = rowOf(pouch);
        if (row == null) { System.out.println("[Summon] testBob " + pouch + " is not a beast of burden pouch"); return; }
        try {
            core.game.node.entity.player.link.quest.Quest q = p.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE);
            if (q != null && !q.isCompleted(p)) p.getQuestRepository().setStage(q, 100);
        } catch (Throwable ignored) { }
        if (p.getSkills().getStaticLevel(Skills.SUMMONING) < row[1]) p.getSkills().setStaticLevel(Skills.SUMMONING, row[1]);
        p.getSkills().setLevel(Skills.SUMMONING, p.getSkills().getStaticLevel(Skills.SUMMONING));
        p.getBank().add(new Item(pouch, 2));
        System.out.println("[Summon] test bob seed " + p.getUsername() + ": Summoning " + p.getSkills().getStaticLevel(Skills.SUMMONING) + ", 2 x " + pouch);
    }

    /** The oldest S5 familiar gives up its seat to a beast of burden (design U4). */
    static boolean evictFun() {
        String oldest = null; long at = Long.MAX_VALUE;
        for (String k : FUN_KEYS) { long t = OUT_AT.getOrDefault(k, 0L); if (t < at) { at = t; oldest = k; } }
        if (oldest == null) return false;
        Familiar f = OUT.get(oldest); Player o = null;
        try { o = f == null ? null : f.getOwner(); } catch (Throwable ignored) { }
        if (o != null) leave(o, "seat for a beast of burden"); else { try { if (f != null) f.clear(); } catch (Throwable ignored) { } OUT.remove(oldest); FUN_KEYS.remove(oldest); } // S4 review: ownerless NPC is cleared, not leaked
        EVICTS++;
        return OUT.size() < CAP;
    }

    /** AFK work(), at the spot, once per leg. True when a beast of burden is out. */
    static boolean summon(AfkTasks.Runner r) {
        if (!ON || r == null || r.p == null || r.bobPouch == 0) return false;
        Player p = r.p; String k;
        try {
            k = key(p); // S4 review: key() inside the try
            FamiliarManager fm = p.getFamiliarManager();
            if (inUse(p)) return false;
            int[] row = rowOf(r.bobPouch);
            if (row == null || p.getInventory().getAmount(r.bobPouch) < 1) { r.bobPouch = 0; return false; }
            if (p.getSkills().getStaticLevel(Skills.SUMMONING) < row[1] || p.getSkills().getLevel(Skills.SUMMONING) < row[4]) { BOB_FAILS++; return false; }
            if (badPlace(p.getLocation()) || p.inCombat()) { BOB_FAILS++; return false; }
            sweep();
            if (OUT.size() >= CAP && !evictFun()) { BOB_CAPPED++; return false; }
            fm.summon(new Item(r.bobPouch), false);
            Familiar f = fm.getFamiliar();
            if (!(f instanceof BurdenBeast) || f.getPouchId() != r.bobPouch) {
                BOB_FAILS++;
                if (f != null && f.getPouchId() == r.bobPouch) try { fm.dismiss(); } catch (Throwable ignored) { } // never leave one out that we do not track
                return false; // refused (zone, room): the pouch stays in the pack and goes back at the next bank
            }
            OUT.put(k, f); OUT_AT.put(k, System.currentTimeMillis());
            r.bob = (BurdenBeast) f; r.bobHeld = 0;
            SUMMONED++; BOB_SUMMONED++; PEAK = Math.max(PEAK, OUT.size());
            System.out.println("[Summon] " + p.getUsername() + " summoned a " + f.getName() + " for its " + r.task.name().toLowerCase(Locale.ROOT) + " trip (out=" + OUT.size() + ")");
            return true;
        } catch (Throwable t) { BOB_FAILS++; System.out.println("[Summon] bob summon " + p.getUsername() + ": " + t); Watchdog.fexc("Bob.summon", p, t); return false; }
    }

    static boolean storable(AfkTasks.Runner r, Item it) {
        if (it == null || it.getId() == r.bobPouch) return false;
        core.cache.def.impl.ItemDefinition d = it.getDefinition();
        return d != null && storableId(it.getId(), r.isProduct(it), r.keep(it), d.isStackable(), d.isTradeable(), d.getValue());
    }

    /** AFK work(), pack full at the spot: products into the beast until it is full. Returns how many moved. */
    static int store(AfkTasks.Runner r) {
        if (!ON || r == null || r.bob == null) return 0;
        int moved = 0;
        try {
            Container c = r.bob.getContainer();
            for (Item it : r.p.getInventory().toArray()) {
                if (c.freeSlots() <= 0) break;
                if (!storable(r, it)) continue;
                int before = c.itemCount();
                r.bob.transfer(it, 1, false);
                if (c.itemCount() > before) moved++;
            }
            r.bobHeld = c.itemCount();
        } catch (Throwable t) { System.out.println("[Summon] bob store " + r.p.getUsername() + ": " + t); Watchdog.fexc("Bob.store", r.p, t); }
        ITEMS_CARRIED += moved;
        return moved;
    }

    /** At the bank: everything back to the pack. Returns how many came out. */
    static int unload(AfkTasks.Runner r) {
        if (!ON || r == null || r.bob == null) return 0;
        try {
            Container c = r.bob.getContainer();
            int before = c.itemCount();
            if (before == 0) return 0;
            r.bob.withdrawAll();
            r.bobHeld = c.itemCount();
            return Math.max(0, before - r.bobHeld);
        } catch (Throwable t) { System.out.println("[Summon] bob unload " + r.p.getUsername() + ": " + t); return 0; }
    }

    /** AFK deposit(), after the normal pass: true = another pass is needed (the beast's load is in the pack now). */
    static boolean bankPass(AfkTasks.Runner r) {
        if (!ON || r == null) return false;
        Player p = r.p;
        try {
            if (r.bob != null && r.bobPass < 3) {
                int n = unload(r);
                if (n > 0) { r.bobPass++; r.bobCarried += n; r.bankPause = 1; r.wait = 3 + RNG.nextInt(4); return true; } // a player's Withdraw-All, then banking again
            }
            r.bobPass = 0;
            if (r.bobCarried > 0) { BOB_TRIPS++; r.bobCarried = 0; }
            if (r.bob != null) {
                if (r.finishing) leave(p, "trip end");
                else if (r.bob.getTicks() < 600) {
                    int pouch = r.bobPouch;
                    leave(p, "trip end");
                    int[] row = rowOf(pouch);
                    if (row != null && p.getSkills().getLevel(Skills.SUMMONING) >= row[4] && p.getInventory().freeSlots() > 0 && p.getBank().remove(new Item(pouch, 1))) {
                        if (p.getInventory().add(new Item(pouch, 1))) { r.bobTried = false; RE_SUMMONS++; }
                        else { p.getBank().add(new Item(pouch, 1)); r.bobPouch = 0; }
                    } else r.bobPouch = 0;
                }
            }
            if (r.bob == null && r.bobPouch != 0 && (r.bobTried || r.finishing)) { // an unused pouch goes back to the bank
                if (p.getInventory().getAmount(r.bobPouch) > 0 && p.getInventory().remove(new Item(r.bobPouch, 1)) && !p.getBank().add(new Item(r.bobPouch, 1))) p.getInventory().add(new Item(r.bobPouch, 1));
                r.bobPouch = 0;
            }
        } catch (Throwable t) { System.out.println("[Summon] bob bank " + p.getUsername() + ": " + t); Watchdog.fexc("Bob.bankPass", p, t); }
        return false;
    }

    /** Every Runner pulse while a beast is out: gone / expiring / danger checks. */
    static void guard(AfkTasks.Runner r) {
        if (!ON || r == null || r.bob == null) return;
        Player p = r.p; BurdenBeast b = r.bob; String k;
        try {
            k = key(p); // S4 review: key() inside the try
            if (OUT.get(k) != b) { r.bob = null; r.bobHeld = 0; return; } // we already let it go
            FamiliarManager fm = p.getFamiliarManager();
            if (fm == null || fm.getFamiliar() != b || !b.isActive()) {
                if (r.bobHeld > 0) { SPILLS += r.bobHeld; System.out.println("[Summon] bob " + p.getUsername() + ": its " + b.getName() + " went with " + r.bobHeld + " items"); }
                LOST++; OUT.remove(k); OUT_AT.remove(k); r.bob = null; r.bobHeld = 0;
                return;
            }
            r.bobHeld = b.getContainer().itemCount();
            int t = b.getTicks();
            if (t <= 60) { leave(p, "timer"); return; }
            Location here = p.getLocation();
            boolean hurt = b.getSkills().getLifepoints() * 2 < b.getSkills().getMaximumLifepoints(); // s4b L3: also when the owner is not the one in combat
            if (r.state == AfkTasks.State.RETREAT || r.state == AfkTasks.State.RECOVER || hurt || here.getZ() != 0 || badPlace(here)) { leave(p, "danger"); return; }
            if (t <= 400 && r.bobHeld > 0 && r.state == AfkTasks.State.WORK) { Location bk = r.nearestBank(); if (bk != null) r.goTo(AfkTasks.State.TO_BANK, bk); }
        } catch (Throwable t) { System.out.println("[Summon] bob guard " + p.getUsername() + ": " + t); }
    }

    /** Takes everything out of a beast: to the pack (owner alive, not dying), the rest straight to the bank. Counted. */
    static void empty(Player p, BurdenBeast b, String why) {
        try {
            Container c = b.getContainer();
            if (c == null || c.itemCount() == 0) return;
            if (p.isActive() && !"death".equals(why) && !"orphan".equals(why)) try { b.withdrawAll(); } catch (Throwable ignored) { }
            int direct = 0, spilt = 0; StringBuilder ids = new StringBuilder();
            for (Item it : c.toArray()) {
                if (it == null) continue;
                Item copy = new Item(it.getId(), it.getAmount(), it.getCharge());
                if (p.getBank().add(copy)) { c.remove(new Item(it.getId(), it.getAmount())); direct += it.getAmount(); }
                else { spilt += it.getAmount(); ids.append(' ').append(it.getId()); }
            }
            TO_BANK_DIRECT += direct;
            if (spilt > 0) { SPILLS += spilt; System.out.println("[Summon] bob " + p.getUsername() + " (" + why + "): bank full, " + spilt + " items will drop:" + ids); }
        } catch (Throwable t) { System.out.println("[Summon] bob empty " + p.getUsername() + ": " + t); }
    }

    /** leave(): the bot's runner no longer has a beast. */
    static void clearRunner(String k, Familiar f) {
        try { AfkTasks.Runner r = AfkTasks.RUNNING.get(k); if (r != null && r.bob == f) { r.bob = null; r.bobHeld = 0; } } catch (Throwable ignored) { }
    }

    /** SA captureItems: a beast's load counts as banked in the PROFILE only (design 1.9), so a mid-trip save loses nothing. */
    static void addCarried(Player b, java.util.List<int[]> bank) {
        if (!ON || b == null) return;
        try {
            Familiar f = OUT.get(key(b));
            if (!(f instanceof BurdenBeast) || f.getOwner() != b) return;
            for (Item it : ((BurdenBeast) f).getContainer().toArray()) if (it != null) bank.add(new int[]{it.getId(), it.getAmount()});
        } catch (Throwable ignored) { }
    }

    /** The [Summon] bob counter line (flag on only). */
    static String bobLine() {
        sweep();
        return "[Summon] bob out=" + OUT.size() + " cap=" + CAP + " plans=" + BOB_PLANS + " summoned=" + BOB_SUMMONED + " dismissed=" + BOB_DISMISSED + " bobTrips=" + BOB_TRIPS
                + " itemsCarried=" + ITEMS_CARRIED + " spills=" + SPILLS + " toBankDirect=" + TO_BANK_DIRECT + " lost=" + LOST + " fails=" + BOB_FAILS + " capped=" + BOB_CAPPED
                + " evicts=" + EVICTS + " reSummons=" + RE_SUMMONS + " why=" + java.util.Arrays.toString(BWHY).replace(" ", "")
                + (WHY2 ? " elig=" + java.util.Arrays.toString(EWHY).replace(" ", "") : "");
    }

    // =====================================================================================================================
    // Summoning in combat (the design notes, -Dbots.summoning.combat). Every hook starts with `if (!COMBAT) return`.
    // Engine (Familiar.handleTickActions): a combat familiar attacks the owner's target by itself, but only when familiar, owner
    // and target are all in a multi-combat zone. So a pouch is taken only for a hunt spot in such a zone and summoned only while
    // the bot stands in one. Nothing here deals damage or orders an attack. The lifecycle is OUT + leave() above.
    // =====================================================================================================================

    /** THE one-familiar rule (fun, beast of burden, combat): true = this bot may not summon now. */
    static boolean inUse(Player p) {
        if (p == null) return true;
        FamiliarManager fm = p.getFamiliarManager();
        return inUse(fm == null || fm.hasFamiliar(), OUT.containsKey(key(p)));
    }
    static boolean inUse(boolean engineHasOne, boolean tracked) { return engineHasOne || tracked; }

    /** {pouch, level, points, minutes} of the Summon.POUCHES rows that fight (SummoningPouch + the NPC classes, live jar). */
    static final int[][] COMBAT_ROWS = COMBAT2
            ? new int[][]{{12047, 1, 1, 6}, {12043, 4, 1, 4}, {12009, 16, 2, 18}, {12049, 18, 1, 19}, {12067, 23, 1, 22}, {12091, 28, 6, 24}, {12073, 36, 3, 30}, {12075, 46, 9, 37}, {12077, 56, 9, 46}}
            : new int[][]{{12047, 1, 1, 6}, {12043, 4, 1, 4}, {12009, 16, 2, 18}, {12049, 18, 1, 19}, {12067, 23, 1, 22}, {12091, 28, 6, 24}, {12073, 36, 3, 30}, {12075, 46, 9, 37}};
    static final int COMBAT_TAKE = 2;

    // ---- v2 (-Dbots.summoning.combat2): scroll specials, so a familiar also helps in single-combat. Engine facts (server.jar, javap):
    // Familiar.executeSpecialMove(FamiliarSpecial) needs the special points (60 at summon, +15 per 50 ticks in handleTickActions) and
    // one scroll of SummoningScroll.forPouch in the pack, calls the familiar's own specialMove, then takes the scroll and the points.
    // It returns true even when specialMove refused, so "fired" is read from the scroll count. Familiar.canAttack ends in
    // MapZone.checkMulti, which counts a familiar as its owner: a targeted special lands in single-combat on the owner's own target.
    // Nothing here writes damage: the only call is executeSpecialMove.
    /** {pouch, scroll, special points, kind, max hit}. Kind 1 hits the owner's target (canCombatSpecial + sendFamiliarHit / bullRush),
     *  2 boosts the owner (granite crab Stony Shell: Defence +4), 3 dreadfowl (Dreadfowl Strike: the hit, or it starts the fowl's own attack). */
    static final int[][] SPECIALS = {{12043, 12445, 3, 3, 3}, {12009, 12533, 12, 2, 0}, {12049, 12460, 6, 1, 5}, {12073, 12461, 6, 1, 4}, {12075, 12462, 6, 1, 6}, {12077, 12463, 6, 1, 9}};
    static final int SCROLL_TAKE = 20, SCROLL_MIN = 3, SCROLL_LOW = 10, SCROLL_KEEP = 40;
    static final long SPEC_GAP_MS = 6_000, BOOST_GAP_MS = 60_000;
    static final String[] SPEC_WHY = {"fire", "noSpecial", "noScroll", "points", "cooldown", "notFighting", "noTarget", "player", "nearlyDead", "far", "boosted", "famBusy"};
    static volatile long C2_TRIPS, C2_SPECIALS, C2_SCROLLS_MADE, C2_SCROLLS_USED, C2_REFUSED, C2_BANKED;
    /** summon4: combatSpecial decisions by SPEC_WHY index (0 = fire). Not in the tally line; the unit test prints it. */
    static final long[] C2_WHY = new long[SPEC_WHY.length];

    static int[] specOf(int pouch) { for (int[] s : SPECIALS) if (s[0] == pouch) return s; return null; }
    static boolean isScroll(int id) { for (int[] s : SPECIALS) if (s[1] == id) return true; return false; }

    /** v2 pick. Multi-combat spot: v1's row. Single-combat spot: the highest row with a real special whose scrolls the bot owns (SCROLL_MIN+). */
    static int[] combatRow2(boolean multi, int level, int points, IntUnaryOperator owned) {
        if (multi) return combatRow(level, points, owned);
        for (int i = COMBAT_ROWS.length - 1; i >= 0; i--) {
            int[] r = COMBAT_ROWS[i], s = specOf(r[0]);
            if (s != null && r[1] <= level && r[2] <= points && owned.applyAsInt(r[0]) >= 1 && owned.applyAsInt(s[1]) >= SCROLL_MIN) return r;
        }
        return null;
    }

    /** Scrolls to move bank -> pack for a trip: up to SCROLL_TAKE carried. */
    static int scrollTake(int inPack, int inBank) { return Math.max(0, Math.min(Math.max(0, inBank), SCROLL_TAKE - Math.max(0, inPack))); }

    /** Pouch errand: pouches of the fresh batch to turn into scrolls (10 each): while under SCROLL_KEEP scrolls, at most half the batch, COMBAT_KEEP pouches stay. */
    static int scrollBatch(int made, int pouchesOwned, int scrollsOwned) {
        if (made <= 0 || scrollsOwned >= SCROLL_KEEP) return 0;
        int want = (SCROLL_KEEP - Math.max(0, scrollsOwned) + 9) / 10;
        return Math.max(0, Math.min(want, Math.min(made / 2, pouchesOwned - Summon.COMBAT_KEEP)));
    }

    /** May the trip's pouch be summoned where the bot stands: multi-combat (v1), or under v2 anywhere with a scroll in the pack. */
    static boolean summonHere(boolean multi, boolean combat2, int scrollsInPack) { return multi || (combat2 && scrollsInPack >= 1); }

    /**
     * The special-fire rule: 0 = fire, else an index into SPEC_WHY. targetKind 0 none, 1 monster, 2 player; hp = the target's
     * lifepoints; famOnTarget = the familiar's own combat pulse is already on that target.
     */
    static int specialWhy(int[] spec, int points, int scrolls, long sinceMs, boolean fighting, int targetKind, int hp, double famToTarget, double famToOwner, boolean boosted, boolean famOnTarget) {
        if (spec == null) return 1;
        if (scrolls < 1) return 2;
        if (!fighting) return 5;
        if (points < spec[2]) return 3;
        if (famToOwner > 15) return 9;
        if (spec[3] == 2) return boosted ? 10 : sinceMs < BOOST_GAP_MS ? 4 : 0;
        if (targetKind == 2) return 7;
        if (targetKind != 1) return 6;
        if (hp < Math.max(3, spec[4])) return 8;
        if (famToTarget > 8) return 9;
        if (sinceMs < SPEC_GAP_MS) return 4;
        if (spec[3] == 3 && famOnTarget) return 11;
        return 0;
    }

    /** AFK fight(), while the bot fights: at most one scroll special, through the engine's own Familiar.executeSpecialMove. True = a scroll was used. */
    static boolean combatSpecial(AfkTasks.Runner r) {
        if (!COMBAT2 || r == null || r.p == null || r.famPouch == 0 || r.famScroll == 0 || r.famSpecFails >= 3) return false;
        long now = System.currentTimeMillis();
        if (now < r.famSpecNext) return false;
        r.famSpecNext = now + 1_200;
        Player p = r.p;
        try {
            String k = key(p);
            Familiar f = OUT.get(k);
            if (f == null || !COMBAT_KEYS.contains(k) || !f.isActive() || f.getPouchId() != r.famPouch || p.getFamiliarManager().getFamiliar() != f) return false;
            if (p.getAttribute("state:death", false)) return false;
            int[] spec = specOf(r.famPouch);
            core.game.node.entity.combat.CombatPulse cp = p.getProperties().getCombatPulse();
            Object v = cp.isAttacking() ? cp.getVictim() : null;
            int kind = v instanceof Player ? 2 : v instanceof core.game.node.entity.npc.NPC && !(v instanceof Familiar) && ((core.game.node.entity.npc.NPC) v).isActive() && !core.game.node.entity.combat.DeathTask.isDead((core.game.node.entity.Entity) v) ? 1 : 0;
            core.game.node.entity.Entity t = kind == 1 ? (core.game.node.entity.Entity) v : null;
            int scrolls = p.getInventory().getAmount(r.famScroll);
            boolean boosted = p.getSkills().getLevel(Skills.DEFENCE) > p.getSkills().getStaticLevel(Skills.DEFENCE);
            core.game.node.entity.combat.CombatPulse fp = f.getProperties().getCombatPulse();
            boolean famOn = t != null && fp.isAttacking() && fp.getVictim() == t;
            int why = specialWhy(spec, f.getSpecialPoints(), scrolls, now - r.famSpecAt, p.inCombat() || cp.isAttacking(), kind, t == null ? 0 : t.getSkills().getLifepoints(),
                    t == null ? 99 : f.getLocation().getDistance(t.getLocation()), f.getLocation().getDistance(p.getLocation()), boosted, famOn);
            C2_WHY[why]++;
            if (why != 0) return false;
            r.famSpecAt = now;
            f.executeSpecialMove(new content.global.skill.summoning.familiar.FamiliarSpecial(spec[3] == 2 ? p : t));
            int used = scrolls - p.getInventory().getAmount(r.famScroll);
            if (used <= 0) { r.famSpecFails++; C2_REFUSED++; return false; }
            r.famSpecFails = 0; r.famSpecials++; C2_SPECIALS++; C2_SCROLLS_USED += used;
            long[] st = CSTAT.get(k);
            if (st != null && st.length > 3) st[3]++;
            return true;
        } catch (Throwable t) { r.famSpecFails++; System.out.println("[Summon] combat special " + p.getUsername() + ": " + t); Watchdog.fexc("Bob.combatSpecial", p, t); return false; }
    }

    /** v2, end of combatPlan: at a bank, scrolls in the pack that this trip does not use go back to the bank (never sold, never lost). */
    static void bankSpareScrolls(AfkTasks.Runner r) {
        if (!COMBAT2 || r == null || r.p == null || !r.p.isArtificial()) return;
        Player p = r.p;
        try {
            Location bk = Watchdog.nearestBank(p.getLocation());
            if (bk == null || bk.getDistance(p.getLocation()) > 10) return;
            for (int[] s : SPECIALS) {
                int n = p.getInventory().getAmount(s[1]);
                if (n < 1 || s[1] == r.famScroll) continue;
                if (!p.getInventory().remove(new Item(s[1], n))) continue;
                if (p.getBank().add(new Item(s[1], n))) C2_BANKED += n; else p.getInventory().add(new Item(s[1], n));
            }
        } catch (Throwable t) { Watchdog.fexc("Bob.bankSpareScrolls", p, t); }
    }

    /** Appended at the very END of the "[Summon] tally" line (combat2 on only). */
    static String combat2Field() { return " combat2: trips=" + C2_TRIPS + " specials=" + C2_SPECIALS + " scrollsMade=" + C2_SCROLLS_MADE + " scrollsUsed=" + C2_SCROLLS_USED; }
    static final Set<String> COMBAT_KEYS = ConcurrentHashMap.newKeySet(), COMBAT_SEEDED = ConcurrentHashMap.newKeySet();
    /** Per familiar out: {the runner's attacks at summon, fights the familiar joined, 1 while it is attacking}. */
    static final Map<String, long[]> CSTAT = new ConcurrentHashMap<>();
    /** combatPlan skips: notFightTrip, noPick, noSlot, notAtBank, otherFamiliar, takeFail, singleCombat. */
    static final long[] CWHY = new long[7];
    static volatile long C_PLANS, C_SUMMONED, C_RESUMMONS, C_ENDED, C_JOINED, C_SINGLE, C_FAILS, C_CAPPED, C_NOT_FIGHTER, C_NO_ROOM;
    /** Farm only (-Dsmartbots.testCombatFam=<pouch id>): each bot once gets the pouch's level, full points, Wolf Whistle and 4 pouches. */
    static final int TEST_COMBAT = FARM ? Integer.getInteger("smartbots.testCombatFam", 0) : 0;
    static { if (!FARM && Integer.getInteger("smartbots.testCombatFam", 0) > 0) System.out.println("[Summon] smartbots.testCombatFam ignored: not a farm world"); }

    static int[] combatRowOf(int pouch) { for (int[] r : COMBAT_ROWS) if (r[0] == pouch) return r; return null; }
    static boolean isCombatPouch(int id) { return combatRowOf(id) != null; }

    /** The best combat pouch the bot can use now: highest level row it owns one of, with the level and the points. Null if none. */
    static int[] combatRow(int level, int points, IntUnaryOperator owned) {
        for (int i = COMBAT_ROWS.length - 1; i >= 0; i--) { int[] r = COMBAT_ROWS[i]; if (r[1] <= level && r[2] <= points && owned.applyAsInt(r[0]) >= 1) return r; }
        return null;
    }

    /** "Fights a lot": a fighter by taste (fav -1), or at least half of 4+ trips this session were fights. */
    static boolean fightsALot(int fav, int fightTrips, int trips) { return fav == -1 || (trips >= 4 && fightTrips * 2 >= trips); }

    /** The engine's own multi-combat list (MultiwayCombatZone) for a tile; a player standing there has Properties.isMultiZone(). */
    static boolean multiAt(Location l) {
        if (l == null) return false;
        try {
            core.game.world.map.Region reg = core.game.world.map.RegionManager.forId(l.getRegionId());
            if (reg == null) return false;
            for (core.game.world.map.zone.RegionZone z : reg.getRegionZones()) {
                if (z.getZone() != core.game.world.map.zone.impl.MultiwayCombatZone.getInstance()) continue;
                if (z.getBorders() == null || z.getBorders().insideBorder(l)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    static boolean wild(Location l) { return l == null || (l.getY() >= 3520 && l.getY() < 4000); }

    // ---- W2 lane M combat steer (-Dbots.summoning.combatSteer, FightRules.STEER): only called behind that flag.
    /** multiAt per spot, remembered: the engine's zone list is fixed after startup and chooseJob asks for every job. */
    static final java.util.Map<Location, Boolean> STEER_MULTI = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean steerMulti(Location l) {
        if (l == null) return false;
        Boolean b = STEER_MULTI.get(l);
        if (b == null) { b = multiAt(l); STEER_MULTI.put(l, b); }
        return b;
    }
    /** A bot past Wolf Whistle that owns (bank or pack) a combat pouch it can summon at its level and points now: combatPlan's own row test. */
    static boolean steerOwner(Player p) {
        try {
            return p != null && p.isArtificial() && Summon.unlocked(p)
                    && combatRow(p.getSkills().getStaticLevel(Skills.SUMMONING), p.getSkills().getLevel(Skills.SUMMONING), id -> owned(p, id)) != null;
        } catch (Throwable t) { return false; }
    }

    static boolean combatEligible(AfkTasks.Runner r) {
        if (!COMBAT || r == null || r.p == null) return false;
        Player p = r.p;
        if (!p.isArtificial() || r.crossOnly || r.questHold || r.task == null || r.task.kind != AfkTasks.Kind.FIGHT) return false;
        if (wild(r.spot) || !Summon.unlocked(p) || QuestRunner.active(p)) return false;
        SmartAdventurers.Brain b = SmartAdventurers.brainOf(p);
        if (b != null && (b.companion != null || b.delivering || Scenes.Greet.escorting(p))) return false;
        return true;
    }

    /** Farm-only seed for -Dsmartbots.testCombatFam (and the unit test). */
    static void seedCombat(Player p, int pouch) {
        int[] row = combatRowOf(pouch);
        if (row == null) { System.out.println("[Summon] testCombatFam " + pouch + " is not a combat pouch row"); return; }
        try {
            core.game.node.entity.player.link.quest.Quest q = p.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE);
            if (q != null && !q.isCompleted(p)) p.getQuestRepository().setStage(q, 100);
        } catch (Throwable ignored) { }
        if (p.getSkills().getStaticLevel(Skills.SUMMONING) < row[1]) p.getSkills().setStaticLevel(Skills.SUMMONING, row[1]);
        p.getSkills().setLevel(Skills.SUMMONING, p.getSkills().getStaticLevel(Skills.SUMMONING));
        p.getBank().add(new Item(pouch, 4));
        if (COMBAT2 && specOf(pouch) != null) p.getBank().add(new Item(specOf(pouch)[1], 30));
        System.out.println("[Summon] test combat seed " + p.getUsername() + ": Summoning " + p.getSkills().getStaticLevel(Skills.SUMMONING) + ", 4 x " + pouch);
    }

    /** SA, just before AfkTasks.launch (after Bob.plan): at a bank, up to 2 of the best combat pouch bank -> pack for a fight trip to a multi-combat spot. */
    static void combatPlan(AfkTasks.Runner r) {
        if (!COMBAT || r == null || r.p == null) return;
        combatPlan1(r);
        if (COMBAT2) bankSpareScrolls(r);
    }

    static void combatPlan1(AfkTasks.Runner r) {
        Player p = r.p;
        r.famPouch = 0; r.famUsed = 0; r.famNext = 0;
        r.famScroll = 0; r.famSpecials = 0; r.famSpecFails = 0; r.famSpecNext = 0; r.famSpecAt = 0;
        try {
            if (TEST_COMBAT > 0 && p.isArtificial() && COMBAT_SEEDED.add(key(p))) seedCombat(p, TEST_COMBAT);
            if (Seed.off(p, "combat")) return; // W0 A0: the OFF half of a famcombat scenario world
            if (!combatEligible(r)) { CWHY[0]++; return; }
            if (r.bobPouch != 0) { CWHY[4]++; return; }
            if (W8Polish.SUMMON_HEAL && w8HealPlan(r)) return; // W8 (-Dbots.summoning.heal): a bunyip for a dragon hunt (after combat's own eligibility checks)
            boolean multi = multiAt(r.spot);
            if (!multi && !COMBAT2) { CWHY[6]++; return; }
            int[] row = COMBAT2 ? combatRow2(multi, p.getSkills().getStaticLevel(Skills.SUMMONING), p.getSkills().getLevel(Skills.SUMMONING), id -> owned(p, id))
                    : combatRow(p.getSkills().getStaticLevel(Skills.SUMMONING), p.getSkills().getLevel(Skills.SUMMONING), id -> owned(p, id));
            if (row == null) { CWHY[multi ? 1 : 6]++; return; }
            int id = row[0];
            BankContainer bank = p.getBank();
            Location bk = Watchdog.nearestBank(p.getLocation());
            boolean atBank = bk != null && bk.getDistance(p.getLocation()) <= 10;
            while (p.getInventory().getAmount(id) < COMBAT_TAKE && bank.getAmount(id) > 0) {
                int held = p.getInventory().getAmount(id);
                if (!atBank) { if (held == 0) CWHY[3]++; break; }
                if (p.getInventory().freeSlots() < (held == 0 ? 3 : 6)) { if (held == 0) CWHY[2]++; break; }
                if (!bank.remove(new Item(id, 1))) { if (held == 0) CWHY[5]++; break; }
                if (!p.getInventory().add(new Item(id, 1))) { bank.add(new Item(id, 1)); if (held == 0) CWHY[5]++; break; }
            }
            if (p.getInventory().getAmount(id) < 1) return;
            int[] spec = COMBAT2 ? specOf(id) : null;
            if (spec != null) {
                int take = atBank && (p.getInventory().getAmount(spec[1]) > 0 || p.getInventory().freeSlots() >= 2) ? scrollTake(p.getInventory().getAmount(spec[1]), bank.getAmount(spec[1])) : 0;
                if (take > 0 && bank.remove(new Item(spec[1], take)) && !p.getInventory().add(new Item(spec[1], take))) bank.add(new Item(spec[1], take));
                if (p.getInventory().getAmount(spec[1]) >= 1) r.famScroll = spec[1];
            }
            if (!multi && r.famScroll == 0) { CWHY[6]++; return; }
            r.famPouch = id; C_PLANS++; Seed.note(p, "combat plan pouch=" + id + " job=" + r.label());
        } catch (Throwable t) { System.out.println("[Summon] combat plan " + p.getUsername() + ": " + t); Watchdog.fexc("Bob.combatPlan", p, t); }
    }

    /**
     * summon3: the engine's own room test (FamiliarManager.summon: Familiar.getSpawnLocation -> RegionManager.getSpawnLocation needs size x size
     * unclipped tiles next to the owner, else "too big to summon here" and nothing happens). The spirit wolf is 2x2 and the Al Kharid
     * warriors' spot tile (3293,3170) has no such room: the first summon of every trip there was refused. True when unknown.
     */
    static boolean roomFor(Player p, int pouch) {
        try {
            content.global.skill.summoning.SummoningPouch sp = content.global.skill.summoning.SummoningPouch.get(pouch);
            Familiar proto = sp == null ? null : FamiliarManager.getFamiliars().get(sp.getNpcId());
            return proto == null || core.game.world.map.RegionManager.getSpawnLocation(p, proto) != null;
        } catch (Throwable t) { return true; }
    }

    /** summon4: the engine's room rule (RegionManager.getSpawnLocation, owner of size 1) for an owner standing on tile t. */
    static boolean roomAt(Location t, int size) {
        for (int i = 0; i < 8; i++) {
            core.game.world.map.Direction d = core.game.world.map.Direction.get(i);
            int sx = d.getStepX(), sy = d.getStepY();
            if (sx < 0) sx -= size - 1;
            if (sy < 0) sy -= size - 1;
            Location b = t.transform(sx, sy, 0);
            boolean ok = true;
            for (int x = 0; x < size && ok; x++) for (int y = 0; y < size; y++) if (core.game.world.map.RegionManager.isClipped(b.transform(x, y, 0))) { ok = false; break; }
            if (ok) return true;
        }
        return false;
    }

    /**
     * summon4: no room where the bot stands (it can fight on the cramped spot tile for a whole trip: 47 refusals in 5 minutes at the
     * Al Kharid warriors). Walks it to the nearest open tile within 6 that has room, in the same kind of combat zone. True = walking.
     */
    static boolean stepToRoom(Player p, int pouch) {
        try {
            content.global.skill.summoning.SummoningPouch sp = content.global.skill.summoning.SummoningPouch.get(pouch);
            Familiar proto = sp == null ? null : FamiliarManager.getFamiliars().get(sp.getNpcId());
            if (proto == null) return false;
            int size = Math.max(1, proto.size());
            Location at = p.getLocation(), best = null;
            double bd = 99;
            boolean multi = multiAt(at);
            for (int dx = -6; dx <= 6; dx++) for (int dy = -6; dy <= 6; dy++) {
                Location t = at.transform(dx, dy, 0);
                double d = at.getDistance(t);
                if (d == 0 || d >= bd || core.game.world.map.RegionManager.isClipped(t) || multiAt(t) != multi || !roomAt(t, size)) continue;
                best = t; bd = d;
            }
            if (best == null) return false;
            core.game.world.map.path.Path path = core.game.world.map.path.Pathfinder.find(p, best);
            if (path == null || !path.isSuccessful()) return false;
            p.getProperties().getCombatPulse().stop();
            path.walk(p);
            return true;
        } catch (Throwable t) { return false; }
    }

    /**
     * W8 (-Dbots.summoning.heal, only reached under COMBAT after combatEligible): a dragon hunt at Summoning 68+ takes a bunyip as the
     * trip's pouch: one from the bank (at a bank), else one bought from bot stock (Ledger.consume pays its maker; never created).
     * The engine's BunyipNPC heals the owner every 25 ticks. True = planned (combatPlan stops); false = the normal combat plan runs.
     */
    static boolean w8HealPlan(AfkTasks.Runner r) { long[] shb = Scen.healBefore(r.p); boolean shp = w8HealPlan0(r); Scen.healAfter(r.p, shb, shp); return shp; } // w14 scen heal: judged around the plan (null, nothing read, unless Seed.ON)
    static boolean w8HealPlan0(AfkTasks.Runner r) {
        Player p = r.p;
        if (!W8Polish.healTrip(true, r.label(), p.getSkills().getStaticLevel(Skills.SUMMONING))) return false;
        int id = W8Polish.BUNYIP;
        Location bk = Watchdog.nearestBank(p.getLocation());
        boolean atBank = bk != null && bk.getDistance(p.getLocation()) <= 10;
        if (W8Polish.healTake(p.getInventory().getAmount(id), p.getInventory().freeSlots())) { // w21: not only within 10 tiles of a bank (9 in 10 trip starts are not: no bunyip was ever taken); like the trip's tool, the banked pouch comes out at the start
            if (p.getBank().getAmount(id) < 1) {
                int price = Math.max(1, MarketBrain.checkPrice(id)); // w13d BUG-1 (-Dmarket.chargeCheck): consume charges chargePrice
                if (MarketBrain.stockOf(id) > 0 && W8Polish.healAffordable(Ledger.Wallet.coins(p), price) && Ledger.consume(p, id, 1, "summoning") == 1) { p.getBank().add(new Item(id, 1)); W8Polish.healBought++; }
            }
            if (p.getBank().getAmount(id) > 0 && p.getBank().remove(new Item(id, 1)) && !p.getInventory().add(new Item(id, 1))) p.getBank().add(new Item(id, 1));
        }
        if (p.getInventory().getAmount(id) < 1) {
            W8Polish.healNoPouch++;
            if (W8Polish.healNoPouch <= 12) System.out.println("[W8] heal " + p.getUsername() + " no bunyip for " + r.label() + ": atBank=" + atBank + " free=" + p.getInventory().freeSlots() + " bank=" + p.getBank().getAmount(id) + " stock=" + MarketBrain.stockOf(id) + " coins=" + Ledger.Wallet.coins(p) + " price=" + MarketBrain.checkPrice(id)); // w21: why (first 12)
            return false;
        }
        r.famPouch = id; W8Polish.healPlans++; C_PLANS++;
        System.out.println("[W8] heal " + p.getUsername() + " takes a bunyip to " + r.label() + " (Summoning " + p.getSkills().getStaticLevel(Skills.SUMMONING) + ")");
        return true;
    }

    /** AFK work(), at the spot: summon the trip's combat pouch when none is out (first time, or again after the timer or its death). */
    static boolean combatSummon(AfkTasks.Runner r) {
        if (!COMBAT || r == null || r.p == null || r.famPouch == 0) return false;
        long now = System.currentTimeMillis();
        if (now < r.famNext) return false;
        Player p = r.p;
        try {
            String k = key(p);
            // summon4: the engine ended our combat familiar itself (its timer, or it died) and nothing told Bob: the OUT entry stayed until some
            // sweep() (the 15 min tally, another bot's summon), and inUse() refused the trip's second pouch all that time. Let it go here.
            if (COMBAT_KEYS.contains(k) && combatGone(p, OUT.get(k))) { leave(p, "expired"); }
            if (inUse(p)) { if (OUT.containsKey(k) && !COMBAT_KEYS.contains(k)) r.famNext = now + 30_000; return false; }
            r.famNext = now + 30_000;
            int[] row = combatRowOf(r.famPouch);
            if (row == null && W8Polish.SUMMON_HEAL) row = W8Polish.healRow(true, r.famPouch); // W8 (-Dbots.summoning.heal): the bunyip row
            if (row == null || p.getInventory().getAmount(r.famPouch) < 1) { r.famPouch = 0; return false; }
            if (p.getAttribute("state:death", false) || wild(p.getLocation())) return false;
            if (!summonHere(p.getProperties().isMultiZone(), COMBAT2, r.famScroll == 0 ? 0 : p.getInventory().getAmount(r.famScroll)) && !(W8Polish.SUMMON_HEAL && r.famPouch == W8Polish.BUNYIP)) { C_SINGLE++; r.famNext = now + 120_000; return false; } // W8 (-Dbots.summoning.heal): a healer is summoned in single-combat too
            if (p.getSkills().getStaticLevel(Skills.SUMMONING) < row[1] || p.getSkills().getLevel(Skills.SUMMONING) < row[2]) { C_FAILS++; r.famNext = now + 300_000; return false; }
            if (!roomFor(p, r.famPouch)) { C_NO_ROOM++; r.famNext = now + (stepToRoom(p, r.famPouch) ? 3_000 : 6_000); return false; } // summon3: no room on this tile: not a failure, try again from the next tile of the fight
            sweep();
            if (OUT.size() >= CAP && !evictFun()) { C_CAPPED++; return false; }
            FamiliarManager fm = p.getFamiliarManager();
            fm.summon(new Item(r.famPouch), false);
            Familiar f = fm.getFamiliar();
            if (f == null || f.getPouchId() != r.famPouch) {
                C_FAILS++; r.famNext = now + 300_000;
                System.out.println("[Summon] combat summon " + p.getUsername() + ": the engine refused pouch " + r.famPouch + " at " + p.getLocation() + " (summon3)");
                return false;
            }
            OUT.put(k, f); OUT_AT.put(k, now); COMBAT_KEYS.add(k); CSTAT.put(k, new long[]{r.kills, 0, 0, 0});
            SUMMONED++; C_SUMMONED++; PEAK = Math.max(PEAK, OUT.size());
            if (r.famUsed++ > 0) C_RESUMMONS++;
            if (W8Polish.SUMMON_HEAL && r.famPouch == W8Polish.BUNYIP) { W8Polish.healSummonOk++; Scen.healSummon(p); } // W8; w14 scen heal watch (no-op unless Seed.ON)
            if (!f.isCombatFamiliar()) C_NOT_FIGHTER++;
            Location at = p.getLocation();
            System.out.println("[Summon] " + p.getUsername() + " summoned a " + f.getName() + " for combat at " + r.label() + " (" + at.getX() + "," + at.getY() + ") (combat=" + f.isCombatFamiliar()
                    + ", pouch " + r.famUsed + " of the trip, out=" + OUT.size() + ")");
            return true;
        } catch (Throwable t) { C_FAILS++; r.famNext = now + 300_000; System.out.println("[Summon] combat summon " + p.getUsername() + ": " + t); Watchdog.fexc("Bob.combatSummon", p, t); return false; }
    }

    /** summon4: a tracked familiar that the engine no longer has for this player (FamiliarManager.dismiss at the timer's end or on death). */
    static boolean combatGone(Player p, Familiar tracked) {
        if (p == null || tracked == null) return false;
        FamiliarManager fm = p.getFamiliarManager();
        return fm != null && fm.getFamiliar() != tracked && !tracked.isActive();
    }

    /** Every Runner pulse on a trip with a combat pouch: counts the fights the familiar joins (its own combat pulse; read only). */
    static void combatGuard(AfkTasks.Runner r) {
        if (!COMBAT || r == null || r.p == null || CSTAT.isEmpty()) return;
        try {
            String k = key(r.p);
            long[] st = CSTAT.get(k); Familiar f = OUT.get(k);
            if (st == null || f == null || !f.isActive()) return;
            boolean att = f.getProperties().getCombatPulse().isAttacking();
            if (att && st[2] == 0) { st[1]++; C_JOINED++; }
            st[2] = att ? 1 : 0;
        } catch (Throwable ignored) { }
    }

    /** leave(): the combat familiar's trip summary. The dismissal itself is leave()'s. */
    static void combatEnd(Player p, String k, Familiar f, String why, Long at) {
        long[] st = CSTAT.remove(k);
        C_ENDED++;
        try {
            long attacks = -1;
            AfkTasks.Runner r = AfkTasks.RUNNING.get(k);
            if (r != null && st != null) attacks = Math.max(0, r.kills - st[0]);
            System.out.println("[Summon] " + p.getUsername() + "'s " + (f == null ? "familiar" : f.getName()) + " fought alongside (joined " + (st == null ? 0 : st[1]) + " fights, " + attacks
                    + " attacks by the bot, " + (at == null ? "?" : (System.currentTimeMillis() - at) / 1000) + " s out; " + why + ")");
            if (COMBAT2) {
                C2_TRIPS++;
                System.out.println("[Summon] " + p.getUsername() + " trip with " + (f == null ? "familiar" : f.getName()) + ": kills " + Math.max(0, attacks) + " in "
                        + String.format(Locale.ROOT, "%.1f", at == null ? 0.0 : (System.currentTimeMillis() - at) / 60000.0) + " min, specials " + (st == null || st.length < 4 ? 0 : st[3]));
            }
        } catch (Throwable ignored) { }
    }

    /** Appended at the END of the "[Summon] tally" line (flag on only). */
    static String combatField() {
        return " combat: out=" + COMBAT_KEYS.size() + " plans=" + C_PLANS + " summoned=" + C_SUMMONED + " reSummons=" + C_RESUMMONS + " ended=" + C_ENDED + " fightsJoined=" + C_JOINED
                + " singleZone=" + C_SINGLE + " fails=" + C_FAILS + " capped=" + C_CAPPED + " notFighter=" + C_NOT_FIGHTER + " noRoom=" + C_NO_ROOM + " why=" + java.util.Arrays.toString(CWHY).replace(" ", "") + (COMBAT2 ? combat2Field() : "");
    }
}
