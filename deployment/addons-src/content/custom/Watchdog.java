package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stuck bots (2026-10-21: "I am seeing a bot in cage in a zoo. He cant get out. If Bots are stuck for a loooong
 * time you should have them force teleported back").
 *
 * Every 30 s it looks at every travelling bot (smart adventurers, traders, the herb gatherer). A bot that has stayed within 8 tiles of the same place for 20 minutes
 * ({@code -Dwatchdog.min}), gained no XP in that time, and isn't somewhere a player would happily stand around (a bank,
 * the Exchange, a hangout, its own workshop or town) is stuck: it is teleported to the nearest bank like a player using
 * home teleport (free, as the server would rescue a stuck player), its task is ended so it picks a new one, and the spot
 * is remembered (data/eco/traps2.json; twice makes it a trap). A bot that is merely idle at a work spot (not trying to
 * go anywhere) isn't teleported: it gives the job up and walks off. Remembered traps are never used as a hunt spot again and nobody
 * teleports straight into one. The Ardougne zoo enclosures are traps from the start.
 */
public final class Watchdog implements StartupListener {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("watchdog", "true"));
    static final long STUCK_MS = Long.getLong("watchdog.min", 20) * 60_000L;

    static final class Seen { Location anchor; long since; double xp; }
    static final Map<String, Seen> SEEN = new ConcurrentHashMap<>();
    static final Map<Integer, Integer> TRAPS = new ConcurrentHashMap<>(); // RouteMap cell -> times a bot was rescued there
    static int rescued;
    static final int TRAP_CAP = 5; // pathing review 10-23 W-1
    static volatile String testBot; // trials only
    static volatile boolean loaded;

    /** Fenced-off places nobody can walk out of: the Ardougne zoo enclosures (the visitor path is outside these boxes). */
    static final int[][] ENCLOSURES = {
            {2590, 3262, 2642, 3289}, // Ardougne zoo: penguins, monkeys, scorpions, snakes, camels, unicorns, bears, wolves, jogre, cyclops
    };

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots() || !ON) return;
        load();
        Daily.start(); // batch 18 item4: the daily stuck report
        GameWorld.getPulser().submit(new Pulse(50) {
            @Override public boolean pulse() { long t0 = System.nanoTime(); try { tick(); } catch (Throwable t) { System.out.println("[Watchdog] " + t); fexc("Watchdog.tick", null, t); } fpulse("Watchdog.tick", null, System.nanoTime() - t0); return false; } // batch 19a B3 // batch 19c B3: its cost
        });
        if (FAIL_ON) GameWorld.getPulser().submit(new Pulse(1) { // batch 19c B3 (design 8.2) tick-overrun: [Fable] its own 1-tick pulse (the 19a sampler is in A's Failures and runs every 2 ticks); one int read a tick
            @Override public boolean pulse() { try { tickSample(); } catch (Throwable ignored) { } return false; }
        });
        if (Boolean.getBoolean("watchdog.test")) GameWorld.getPulser().submit(new Pulse(200) { // trials only: put a bot in the unicorn pen
            @Override public boolean pulse() {
                for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) if (br.bot != null && br.bot.isActive()) {
                    br.bot.getProperties().setTeleportLocation(Location.create(2628, 3268, 0));
                    testBot = key(br.bot); System.out.println("[Watchdog] test: put " + br.bot.getUsername() + " in the unicorn pen"); return true;
                }
                return false; // no bot out yet: try again
            }
        });
        System.out.println("[Watchdog] stuck bots are rescued after " + STUCK_MS / 60_000 + " min; " + TRAPS.size() + " trap spots remembered");
    }

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    // ------------------------------------------------------------ batch 19a: failure-logging hooks (Builder B; Failures is Builder A's)
    /** batch 19a B7: -Dfailures=false = batch 18: every hook below is a no-op (no Failures call at all). */
    static final boolean FAIL_ON = !"false".equalsIgnoreCase(System.getProperty("failures", "true"));
    /** batch 19a B2: the scenario sources that are failures (no_route/redirect/seed/prove are not). */
    static final java.util.Set<String> FAIL_SRC = new java.util.HashSet<>(java.util.Arrays.asList("rescue", "idle_giveup", "never_reached", "failed_trip", "quest_fail"));
    static boolean traced(Player p) { return p != null && p.isArtificial(); } // batch 19a: never a real player (::afk runners, real questers)
    /** batch 19a B2: one failure record (domain "path"); returns the fid or null. Never throws. */
    static String fail(Player bot, String src, String sid, Goal g, Location stall, String why) {
        if (!FAIL_ON || !traced(bot)) return null;
        try { return Failures.failure(bot, "path", src, sid, g, stall, why); } catch (Throwable t) { return null; }
    }
    /** batch 19a B3: next to an add-on pulse's own catch print. bot may be null (or a real player: then passed as null). */
    static void fexc(String where, Player bot, Throwable t) { if (FAIL_ON) try { Failures.exception(where, traced(bot) ? bot : null, t); } catch (Throwable ignored) { } }
    /** batch 19a design 3.1: who is driving the walk (one map get + two stores inside Failures). */
    static void fdrv(Player bot, byte drv) { if (FAIL_ON && traced(bot)) try { Failures.driver(bot, drv); } catch (Throwable ignored) { } }
    /** batch 19a design 3.2: a line for the bot's log ring (a reference store). */
    static void flog(Player bot, String line) { if (FAIL_ON && line != null && traced(bot)) try { Failures.log(bot, line); } catch (Throwable ignored) { } }
    /** batch 19a: beside each batch 18 *From assignment (the trip's start). */
    static void ftrip(Player bot, Location from) { if (FAIL_ON && traced(bot)) try { Failures.tripStart(bot, from); } catch (Throwable ignored) { } }
    // batch 19c (design 3.1, Builder B): one-line wrappers for the B-side detector hooks; every one is a no-op with -Dfailures=false and never throws
    /** batch 19c: a detector record of any domain via Failures.event (A's); bot null = server-level; a real player is never passed (returns null). */
    static String fev(String domain, Player bot, String src, String reason, String rsub, Location at, Map<String, Object> ctx) { if (!FAIL_ON || bot != null && !traced(bot)) return null; try { return Failures.event(domain, bot, src, reason, rsub, at, ctx); } catch (Throwable t) { return null; } }
    /** batch 19c: an ordered ctx map from key, value pairs (nulls kept out). */
    static Map<String, Object> ctx(Object... kv) { Map<String, Object> m = new java.util.LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]); return m; }
    static volatile String pulseTopSrc; static volatile long pulseTopNs, pulseTopMin; // batch 19c design 8.2: the heaviest add-on pulse this minute (tick-overrun's topPulse); game thread writes
    /** batch 19c design 8.2: an add-on pulse's cost (the caller's two nanoTime reads) -> Failures.pulseCost (A's long-pulse rule); keeps the minute's heaviest source here. */
    static void fpulse(String source, Player bot, long nanos) {
        if (!FAIL_ON) return;
        try {
            long m = System.currentTimeMillis() / 60_000L;
            if (m != pulseTopMin) { pulseTopMin = m; pulseTopNs = 0; pulseTopSrc = null; }
            if (nanos > pulseTopNs) { pulseTopNs = nanos; pulseTopSrc = source; }
            Failures.pulseCost(source, traced(bot) ? bot : null, nanos); // batch 19c: every pulse (A keeps the EWMA per source and the 100 ms rule)
        } catch (Throwable ignored) { }
    }

    /** Inside one of the hard-coded enclosures (pathing review 10-23 W-1: only these skip the idle and normal-place checks). */
    static boolean enclosure(Location l) {
        if (l == null || l.getZ() != 0) return false;
        for (int[] b : ENCLOSURES) if (l.getX() >= b[0] && l.getY() >= b[1] && l.getX() <= b[2] && l.getY() <= b[3]) return true;
        return false;
    }

    /** Inside a known enclosure or a spot where a bot has been rescued before. */
    static boolean trap(Location l) {
        if (l == null || l.getZ() != 0) return false;
        if (enclosure(l)) return true;
        load();
        return TRAPS.getOrDefault(RouteMap.cell(l.getX(), l.getY()), 0) >= 2; // twice: once could be bad luck
    }

    /** Where a teleport aimed at {@code l} should land: there, unless it's a trap; then the nearest bank. */
    static Location landing(Location l) {
        if (!trap(l)) return l;
        Location b = nearestBank(l, null, false); // batch 22-1: gates and the toll skipped; the sea is the line below's
        if (b != null && SEA_LAND && AfkTasks.Runner.acrossSea(b, l)) return AfkTasks.Runner.karamja(l) ? AfkTasks.Runner.BOAT_LAND_KARAMJA : AfkTasks.Runner.BOAT_LAND_SARIM; // batch 21e: a trap cell on Karamja (the Shrimp and Parrot's, live) sent the sea-crossing teleport to the Ardougne south bank, still the mainland: the shop walk paid the crossing again, 490 times in 6 h; the dock on the target's side instead
        return b != null ? b : l;
    }
    static final boolean SEA_LAND = !"false".equals(System.getProperty("sea.land")); // batch 21e: -Dsea.land=false: the nearest bank even across the sea (the batch 21 way)

    static Location nearestBank(Location from) { return nearestBank(from, null, true); }
    static Location nearestBank(Location from, Player p) { return nearestBank(from, p, true); }

    /**
     * Batch 22-1 (findings 2a): the nearest bank the bot can get to from here without crossing a gate, a toll or the sea: skips a bank
     * in a quest/skill gate box (the player's own test with p; with no player any gated bank: Shilo, the Fishing Guild), Al Kharid's
     * bank from the west of the toll gate (x under 3268; not from the desert, y under 3140, whose pass is the other way) and,
     * when noSea, a bank across the water (Karamja against the mainland); if that leaves none, the nearest one that is only gated or
     * across the water is not skipped for the sea, and last the plain nearest (the old behaviour), so a rescue always has somewhere to go. -Dbank.reach=false: the old plain nearest always.
     */
    static Location nearestBank(Location from, Player p, boolean noSea) {
        int y = from.getY() > 6400 ? from.getY() - 6400 : from.getY(); // underground: the bank above
        Location surface = Location.create(from.getX(), y, 0);
        Location best = null, land = null, any = null; double bd = Double.MAX_VALUE, ld = Double.MAX_VALUE, ad = Double.MAX_VALUE;
        for (Location b : AfkTasks.BANKS) {
            double d = b.getDistance(surface);
            if (d < ad) { ad = d; any = b; }
            if (!BANK_REACH) continue;
            if (d < ld && bankReachable(surface, b, p, false)) { ld = d; land = b; }
            if (d < bd && bankReachable(surface, b, p, noSea)) { bd = d; best = b; }
        }
        return best != null ? best : land != null ? land : any; // no bank on this side of the water that it may use: one across it (a teleport does not mind), then the old nearest
    }
    static final boolean BANK_REACH = !"false".equals(System.getProperty("bank.reach", "true"));

    /** The batch 22-1 test behind nearestBank: false for a bank across a gate, a toll or (noSea) the sea from {@code from}. Pure apart from Gates. */
    static boolean bankReachable(Location from, Location b, Player p, boolean noSea) {
        if (b.getX() == 3270 && from.getX() < 3268 && from.getY() >= 3140) return false; // Al Kharid: behind the toll gate
        if (p != null ? !Gates.canEnter(p, b) : !Gates.at(b).isEmpty()) return false;
        return !(noSea && AfkTasks.Runner.acrossSea(from, b));
    }

    static double xp(Player p) {
        double t = 0;
        for (int i = 0; i < 24; i++) try { t += p.getSkills().getExperience(i); } catch (Throwable ignored) { }
        return t;
    }

    /** Bots that are meant to travel: smart adventurers, travelling traders, the herb gatherer. */
    static boolean ours(Player p) {
        if (SmartAdventurers.BRAINS.containsKey(key(p))) return true;
        for (TravellingTraders.Trader t : TravellingTraders.ALL) if (t.bot == p) return true;
        return ServiceBots.LIVE.get("herbs") == p;
    }

    /** Somewhere a player stands about on purpose: no rescue. */
    static boolean normalPlace(Player p, Location at) {
        if (at.getZ() == 0 && at.getDistance(SmartAdventurers.GRAND_EXCHANGE) <= 25) return true;
        for (Location b : AfkTasks.BANKS) if (b.getDistance(at) <= 12) return true;
        for (Object[] h : SmartAdventurers.Brain.HANGOUTS) if (Location.create((Integer) h[1], (Integer) h[2], 0).getDistance(at) <= 12) return true;
        return false;
    }

    static void tick() {
        long now = System.currentTimeMillis();
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Player p : Repository.getPlayers()) {
            if (p == null || !p.isArtificial() || !p.isActive()) continue;
            String k = key(p);
            live.add(k);
            if (!ours(p)) continue; // the server's own skilling bots stand at one spot by design; artisans and town folk stay home
            boolean pitching = false;
            for (TravellingTraders.Trader t : TravellingTraders.ALL) if (t.bot == p && (t.phase == TravellingTraders.Phase.PITCH || t.phase == TravellingTraders.Phase.TRADING)) pitching = true; // SEEK and STASH walks can be stuck like anyone (review 10-22)
            if (pitching) { SEEN.remove(k); continue; } // a trader standing with a player is working
            boolean busyTrading = false; Object busyWith = null; // batch 19c B3: the session/offer object for deal-deadlock
            for (PlayerTrades.Session s : PlayerTrades.SESSIONS.values()) if (s.bot == p) { busyTrading = true; busyWith = s; }
            for (Deals.Offer o : Deals.OFFERS) if (o.seller == p) { busyTrading = true; if (busyWith == null) busyWith = o; }
            if (FAIL_ON) try { deadlockCheck(p, k, busyWith, now); } catch (Throwable ignored) { } // batch 19c B3 (design 4.4)
            if (busyTrading) { SEEN.remove(k); continue; }
            SmartAdventurers.Brain cb = SmartAdventurers.BRAINS.get(k);
            if (cb != null && (cb.companion != null || cb.agiStep >= 0 || cb.starUntil > now)) { SEEN.remove(k); continue; }
            if (QuestRunner.inCutscene(p)) { SEEN.remove(k); continue; } // a quest cutscene or instance: never stuck // in a player's crew, on the Agility course or at a star: those have their own timeouts (review 10-22)
            Location at = p.getLocation();
            double x = xp(p);
            Seen s = SEEN.get(k);
            if (s == null || s.anchor.getZ() != at.getZ() || s.anchor.getDistance(at) > 8 || x > s.xp + 0.5) {
                if (s == null) { s = new Seen(); SEEN.put(k, s); }
                s.anchor = at; s.since = now; s.xp = x;
                continue;
            }
            boolean inTrap = trap(at);
            if (k.equals(testBot)) System.out.println("[Watchdog] test: " + p.getUsername() + " at " + at + " still " + (now - s.since) / 1000 + " s, trap " + inTrap + ", doing " + BotChat.activityOf(p));
            long limit = inTrap ? Math.min(STUCK_MS, 5 * 60_000L) : STUCK_MS; // in a known trap: don't wait the full 20 min
            if (now - s.since < limit) continue;
            // Pathing review 10-23 W-1: a learned trap (a cell with 2+ rescues) skipped these two checks too, so a bot idling there
            // (a fishing spot waiting to come back) was "rescued" after 5 minutes and added another count: the cell fed itself
            // (313 of 595 rescues were at the 5-minute limit). Only the hard-coded enclosures skip them now.
            boolean caged = enclosure(at);
            if (!caged && normalPlace(p, at)) { s.since = now; continue; }
            if (!caged && !trying(p)) { idle(p, at, (now - s.since) / 60_000, inTrap); s.since = now; continue; } // standing about at a spot with nothing to do: not stuck // batch 18 item1: inTrap for the reason
            rescue(p, at, (now - s.since) / 60_000, s.anchor, inTrap, caged); // batch 18 item1: the anchor and why, for the scenario record
            s.anchor = null; SEEN.remove(k);
        }
        try { Daily.tick(now); } catch (Throwable ignored) { } // batch 18 item4: the UTC rollover and the 10-minute save
        if (FAIL_ON) try { health(live, now); } catch (Throwable t) { if (Boolean.getBoolean("failures.debug")) System.out.println("[Watchdog] health checks: " + t); } // batch 19c B3 (design 8.1/8.2/10): logging only
        // forget bots that logged out
        SEEN.keySet().retainAll(live);
    }

    // ------------------------------------------------------------ batch 19c B3: health, lifecycle and save checks on the 30 s tick (game thread; logging only)
    static final Map<String, Object[]> BUSY = new ConcurrentHashMap<>();      // bot key -> {offer/session object, ticks skipped with it, recorded}
    static final Map<String, Location> LAST_AT = new ConcurrentHashMap<>();   // adventurer key -> its tile at the last tick it was live (vanished's lastSeen)
    static final Map<String, Integer> GONE_TICKS = new ConcurrentHashMap<>(); // adventurer key -> ticks in BRAINS while not live and not on a break
    static int healthN;
    static final int SCRIPT_LIMIT = 75; // the engine ticks only the first 75 BotScriptPulses a tick
    static long starvedAt;
    static final boolean[] STALE = new boolean[3];

    /** batch 19c B3 deal-deadlock: a bot skipped as "busy trading" with the same session (or a claimed offer) for more than 10 ticks (5 min). One record per object. */
    static void deadlockCheck(Player p, String k, Object with, long now) {
        if (with instanceof Deals.Offer) { Deals.Offer o = (Deals.Offer) with; if (o.buyer == null || o.holdUntil > now) with = null; } // [Fable] an unclaimed offer or one held for a human by design is waiting, not locked
        if (with == null) { BUSY.remove(k); return; }
        Object[] b = BUSY.get(k);
        if (b == null || b[0] != with) { BUSY.put(k, new Object[]{with, 1, false}); return; }
        int n = (Integer) b[1] + 1; b[1] = n;
        if (n <= 10 || (Boolean) b[2]) return;
        b[2] = true;
        String kind, other; long age;
        if (with instanceof PlayerTrades.Session) { PlayerTrades.Session s = (PlayerTrades.Session) with; kind = "session"; other = "player"; age = (now - s.opened) / 1000; }
        else { Deals.Offer o = (Deals.Offer) with; kind = "offer"; other = o.buyer != null && o.buyer.isArtificial() ? o.buyer.getUsername() : "player"; age = (now - o.created) / 1000; }
        fev("economy", p, "watchdog", "deal-deadlock", kind + " " + age + " s", null, ctx("kind", kind, "age", age, "other", other));
    }

    /** batch 19c B3: the periodic checks (each cheap: map sizes and field reads; one stat of ledger.json every 5 min). */
    static void health(java.util.Set<String> live, long now) {
        healthN++;
        // script-starvation (design 8.2): the engine's bot pulse map over 75
        java.util.HashMap<String, core.game.bots.GeneralBotCreator.BotScriptPulse> pm = core.game.bots.AIRepository.getPulseRepository();
        // batch 20 (19b+c follow-up 3): the engine's map is a plain HashMap that bot spawns and the pulse-map sweeps change; iterating
        // it here threw a (caught) ConcurrentModificationException now and then. A snapshot (retried, short) is read instead; a tick
        // whose copy keeps colliding skips the check.
        java.util.List<Map.Entry<String, core.game.bots.GeneralBotCreator.BotScriptPulse>> snap = null;
        for (int tries = 0; snap == null && tries < 3; tries++) {
            try { snap = new java.util.ArrayList<>(); for (Map.Entry<String, core.game.bots.GeneralBotCreator.BotScriptPulse> e : pm.entrySet()) snap.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue())); }
            catch (java.util.ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) { snap = null; }
        }
        int n = snap == null ? 0 : snap.size();
        if (snap != null && n > SCRIPT_LIMIT) {
            clean("b-script-starvation-scripts", false);
            if (now - starvedAt >= 3_600_000L) {
                starvedAt = now;
                java.util.List<String> ours = new java.util.ArrayList<>(); Map<String, Integer> roles = new java.util.TreeMap<>(); int i = 0;
                for (Map.Entry<String, core.game.bots.GeneralBotCreator.BotScriptPulse> e : snap) {
                    String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
                    boolean adv = SmartAdventurers.BRAINS.containsKey(key);
                    Object sc = e.getValue() == null ? null : e.getValue().getBotScript();
                    roles.merge(adv ? "adventurer" : sc instanceof Scenarios.Replayer ? "replayer" : sc == null ? "none" : sc.getClass().getSimpleName(), 1, Integer::sum);
                    if (i++ >= SCRIPT_LIMIT && adv && ours.size() < 5) ours.add(e.getKey());
                }
                fev("health", null, "scripts", "script-starvation", n + " bot scripts, limit " + SCRIPT_LIMIT, null, ctx("source", "scripts", "scripts", n, "limit", SCRIPT_LIMIT, "ours", ours, "roles", roles));
            }
        } else if (snap != null) { if (starvedAt > 0) starvedAt = 0; clean("b-script-starvation-scripts", true); }
        // vanished (design 8.1): an adventurer brain whose bot left without logOut, two ticks in a row
        java.util.Set<String> away = new java.util.HashSet<>(); for (SmartAdventurers.Profile pr : SmartAdventurers.OFFLINE) if (pr != null && pr.name != null) away.add(pr.name.toLowerCase(Locale.ROOT));
        for (Map.Entry<String, SmartAdventurers.Brain> e : SmartAdventurers.BRAINS.entrySet()) {
            String k = e.getKey(); SmartAdventurers.Brain br = e.getValue();
            if (live.contains(k)) { GONE_TICKS.remove(k); if (br.bot != null) LAST_AT.put(k, br.bot.getLocation()); continue; }
            if (away.contains(k)) { GONE_TICKS.remove(k); continue; }
            int t = GONE_TICKS.merge(k, 1, Integer::sum);
            if (t != 2 || br.bot == null) continue;
            Location ls = LAST_AT.get(k); Goal g = null; try { g = goal(br.bot); } catch (Throwable ignored) { }
            fev("lifecycle", br.bot, "lifecycle", "vanished", "left without logOut", ls, ctx("role", "adventurer", "lastSeen", ls == null ? null : new int[]{ls.getX(), ls.getY(), ls.getZ()}, "lastGoal", g == null ? null : g.walker + " " + g.kind));
        }
        GONE_TICKS.keySet().retainAll(SmartAdventurers.BRAINS.keySet()); LAST_AT.keySet().retainAll(SmartAdventurers.BRAINS.keySet());
        BUSY.keySet().retainAll(live);
        // save-stale (design 10): profiles and quests every tick (field reads), the ledger's file time every 10th tick
        long up = now - BOOT;
        try { File pf = SmartAdventurers.profileFile(); long w = SmartAdventurers.profilesWrittenAt; long age = w == Long.MIN_VALUE ? up : (System.nanoTime() - w) / 1_000_000L; stale(0, pf.getName(), Math.min(age, up), (SmartAdventurers.FAST ? 3 : 30) * 60_000L, Ledger.blocked(pf)); } catch (Throwable ignored) { }
        try { long w = QuestBook.savedAt; if (QuestBook.ON) stale(1, QuestBook.file().getName(), w == 0 ? up : Math.min(now - w, up), 30 * 60_000L, Ledger.blocked(QuestBook.file())); } catch (Throwable ignored) { }
        if (healthN % 10 == 0) try { File lf = Ledger.file(); long w = lf.exists() ? lf.lastModified() : 0; stale(2, lf.getName(), w == 0 ? up : Math.min(now - w, up), 3 * 3_600_000L, Ledger.blocked(lf)); } catch (Throwable ignored) { }
    }

    /** batch 19c save-stale: one record per stale spell (the clock starts at boot plus one period), and a self-clear after 3 clean checks. */
    static void stale(int i, String file, long ageMs, long limitMs, boolean blocked) {
        String id = "b-save-stale-" + Failures.slug(file); // A's save key: the file name slugged
        if (ageMs > limitMs && !blocked) {
            clean(id, false);
            if (STALE[i]) return;
            STALE[i] = true;
            fev("save", null, "save", "save-stale", file + " " + ageMs / 60_000L + " min old", null, ctx("file", file, "ageMin", ageMs / 60_000L, "blocked", false));
        } else { STALE[i] = false; clean(id, true); }
    }

    /** batch 19c design 9.3: a periodic detector's check for one bug id -> A's Failures.periodic (the 3rd/6th/9th clean check in a row appends a live pass replay). */
    static void clean(String bugId, boolean ok) { try { Failures.periodic(bugId, !ok); } catch (Throwable ignored) { } }

    // ------------------------------------------------------------ batch 19c B3: tick-overrun (design 8.2) from the engine's own per-tick work time
    static final long BOOT = System.currentTimeMillis(); static long overLastRec; static int overN, overMax, overHead; static final long[] OVER_T = new long[5]; static final int[] OVER_MS = new int[5]; // batch 19c: the last 5 overruns (a rolling minute)
    /** Game thread, every tick (Pulse(1)): one int read. A tick over 600 ms is an overrun; 5 within 60 s or one over 2,000 ms is a record, then one per 10 min. */
    static void tickSample() {
        int ms = core.integrations.grafana.Grafana.Companion.getTotalTickTime();
        if (ms <= 600) return;
        long now = System.currentTimeMillis();
        OVER_T[overHead] = now; OVER_MS[overHead] = ms; overHead = (overHead + 1) % 5;
        overN = 0; overMax = 0; for (int i = 0; i < 5; i++) if (OVER_T[i] > 0 && now - OVER_T[i] <= 60_000L) { overN++; overMax = Math.max(overMax, OVER_MS[i]); } // [Fable] a rolling 60 s, not the calendar minute (5 overruns split 3+2 over a minute boundary were missed)
        if (now - BOOT < 120_000L || now - overLastRec < 600_000L) return; // [Fable] boot ticks are slow by nature (as pulseCost's 120 s)
        if (overN < 5 && ms <= 2_000) return;
        overLastRec = now;
        int bots = 0, humans = 0; for (Player p : Repository.getPlayers()) { if (p == null || !p.isActive()) continue; if (p.isArtificial()) bots++; else humans++; }
        String top = null; try { top = Failures.topPulse(); } catch (Throwable ignored) { } if (top == null && pulseTopSrc != null) top = pulseTopSrc + " " + pulseTopNs / 1_000_000L + " ms"; // A's minute top, else this side's
        fev("health", null, "tick", "tick-overrun", overN + " overruns, max " + overMax + " ms", null, ctx("source", "tick", "tickMs", overMax, "overruns1m", overN, "bots", bots, "humans", humans, "topPulse", top == null ? "-" : top));
    }

    /** Is the bot trying to get somewhere (and not getting there), rather than standing at a spot with nothing to do? */
    static boolean trying(Player p) {
        String k = key(p);
        AfkTasks.Runner r = AfkTasks.RUNNING.get(k);
        if (r != null && (r.state == AfkTasks.State.TO_BANK || r.state == AfkTasks.State.TO_SPOT || r.state == AfkTasks.State.RETREAT || r.state == AfkTasks.State.TO_FURNACE)) return true;
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(k);
        if (br != null && (br.shopAt != null || br.delivering || br.exchangeTrip || br.graveAt != null)) return true;
        if (QuestRunner.walking(p)) return true; // a quest walk or gather
        NavWalker.State ns = NavWalker.STATES.get(k); // batch 19b design §10: a live planner route (not consumed, goal > 4 tiles off, planned or advanced in the last 2 min) is trying; leftover routes age out
        NavPlanner.Route nr = ns != null ? ns.route : null; Location nd = ns != null ? ns.dest : null; // batch 19b: read once
        if (nr != null && nd != null && ns.hop < nr.hops.size() && p.getLocation().getDistance(nd) > 4 && System.currentTimeMillis() - Math.max(ns.plannedT, ns.hopT) < 120_000) return true;
        Object[] pl = RouteMap.PLANS.get(k);
        if (pl != null && pl.length > 3 && System.currentTimeMillis() - (Long) pl[3] < 120_000) return true; // a fresh plan; stale ones survive teleports and finished trips (review 10-22)
        long[] st = RouteMap.STUCKS.get(k);
        return st != null && System.currentTimeMillis() - st[1] < 10 * 60_000L;
    }

    /** Idle at a work spot for 20 min (nothing to fish, everything killed by others): give up the job like a player would. */
    static void idle(Player p, Location at, long mins) { idle(p, at, mins, false); } // batch 18 item1: the old signature

    static void idle(Player p, Location at, long mins, boolean inTrap) {
        String doing = BotChat.activityOf(p);
        Goal g = goal(p); // batch 18 item1: what it was doing, its walker's last word, how far from its goal, the planner's state
        String why = "idle " + (inTrap ? "in a learned trap cell (5 min limit)" : "for the full limit") + ", " + g.walker + " " + g.kind + ", planner " + g.navShort;
        String line = "[Watchdog] " + p.getUsername() + " idle at " + at + " for " + mins + " min" + (doing != null ? " (" + doing + ")" : "") + "; giving up that job"
                + " | walker " + g.walker + " (" + g.kind + "), last '" + g.last + "', goal " + (g.to != null ? g.to + " " + (int) at.getDistance(g.to) + " tiles away" : "none") + ", planner " + g.nav + ", routemap " + g.map + (inTrap ? ", learned trap cell" : ""); // batch 18 item1
        System.out.println(line); flog(p, line); // batch 19a design 3.2: the same line into the bot's log ring
        scenario(p, "idle_giveup", g.walker, g.from != null ? g.from : at, g.to, at, why, g); // batch 18 item1 // batch 19a B2: + the failure record (fid into the scenario)
        Daily.note("idle", why); // batch 18 item1: the daily report's idle reasons
        final Player bot = p;
        GameWorld.getPulser().submit(new Pulse(1) {
            @Override public boolean pulse() {
                try { AfkTasks.Runner r = AfkTasks.RUNNING.get(key(bot)); if (r != null) r.finish("Nothing doing here. Moving on."); } catch (Throwable ignored) { }
                BotChat.say(bot, BotChat.pick("Nothing doing here. Moving on.", "Too crowded. I'm off.", "Waste of time, this. Elsewhere.", "Right, I give up on this spot."));
                return true;
            }
        });
    }

    static void rescue(Player p, Location at, long mins) { rescue(p, at, mins, null, false, false); } // batch 18 item1: the old signature (tests call it)

    static void rescue(Player p, Location at, long mins, Location anchor, boolean inTrap, boolean caged) { rescue(p, at, mins, anchor, inTrap, caged, null); }

    /** batch 21 A: from Failures.loopCheck (game thread): a bot going round in circles on a trip is rescued like a stuck one. */
    static void loopFound(Player p, Location at, int visits, int span) {
        if (p == null || at == null || !p.isActive() || !ours(p)) return;
        loops++;
        rescue(p, at, 5, null, false, false, "loop: tile " + at.getX() + "," + at.getY() + " entered " + visits + " times in 5 min, loop " + span + " tiles wide");
    }
    static int loops;
    /** batch 26 fix 1: -Drescue.trip=false: every rescue goes to the bank (the batch 21 way). */
    static final boolean TRIP_ON = !"false".equals(System.getProperty("rescue.trip"));
    static int tripRescues;

    static void rescue(Player p, Location at, long mins, Location anchor, boolean inTrap, boolean caged, String loopWhy) {
        Location bank = nearestBank(at, p);
        if (bank == null) return;
        String doing = BotChat.activityOf(p);
        try { Goal g = goal(p); scenario(p, "rescue", g.walker, g.from != null ? g.from : anchor != null ? anchor : at, g.to, at, (loopWhy != null ? loopWhy : "stuck " + mins + " min" + (caged ? " in an enclosure" : inTrap ? " in a learned trap cell" : "")) + ", " + g.walker + " " + g.kind + ", planner " + g.navShort, g); } catch (Throwable ignored) { } // batch 18 item1: before the teleport clears the walk // batch 19a B2: + the failure record
        fdrv(p, Failures.DRV_WATCHDOG); // batch 19a design 3.1: after the record (it reports the walker that stalled), the teleport is ours
        try { p.getPulseManager().clear(); p.getWalkingQueue().reset(); } catch (Throwable ignored) { }
        String path = loopWhy != null ? "loop" : caged ? "enclosure" : inTrap ? "trap" : "anchor"; // batch 26 fix 5: which check fired
        AfkTasks.Runner tr = AfkTasks.RUNNING.get(key(p));
        try { SmartAdventurers.legFailed(p, tr, "rescued, " + path); } catch (Throwable ignored) { } // batch 26 fix 2: that job from that bank, not on foot again for a while
        Location land = null; long fare = 0;
        try { // batch 26 fix 1: a mid-trip rescue finishes the trip with the paid teleport the Runner's own stall rule would take (AfkTasks walk(), payTeleport); free only to the bank
            if (TRIP_ON && tr != null && !tr.halted && tr.dest != null && p.isArtificial() && !trap(tr.dest)
                    && (tr.state == AfkTasks.State.TO_SPOT || tr.state == AfkTasks.State.TO_BANK || tr.state == AfkTasks.State.TO_FURNACE)) {
                long c0 = Ledger.Wallet.coins(p);
                if (Ledger.Wallet.payTeleport(p, "rescue-trip")) { land = landing(tr.dest); fare = c0 - Ledger.Wallet.coins(p); }
            }
        } catch (Throwable t) { land = null; }
        if (land != null) {
            Spectacle.teleport(p, land);
            tr.best = Double.MAX_VALUE; tr.retries = 0; tr.sinceProgress = 0; tr.stallTeles++; tr.last = "teleported (rescue)";
            RouteMap.PLANS.remove(key(p));
            rescued++; tripRescues++;
            NavWalker.rescued(p);
            String line = "[Watchdog] " + p.getUsername() + " stuck at " + at + " for " + mins + " min" + (doing != null ? " (" + doing + ")" : "") + ", paid teleport (" + fare + " gp) to " + land + " to finish the trip to " + tr.dest + ", path " + path;
            System.out.println(line); flog(p, line);
            try { Scenes.RescueTalk.saw(p, at, land, System.currentTimeMillis()); } catch (Throwable ignored) { } // batch 28: a bot nearby remarks on it (Scenes.java)
            final Player bot = p;
            GameWorld.getPulser().submit(new Pulse(1) {
                @Override public boolean pulse() { BotChat.say(bot, BotChat.pick("Sod this, I'm teleporting.", "Right, teleport. Should have done that first.", "Not walking round that again.")); return true; }
            });
            save();
            return;
        }
        // batch 15 item1: the old 714 animation (played for nobody most of the time) is now the helper's, only when watched
        Spectacle.teleport(p, bank); // batch 15 item1
        if (at.getY() < 6400 && loopWhy == null) TRAPS.merge(RouteMap.cell(at.getX(), at.getY()), 1, (a, b) -> Math.min(TRAP_CAP, a + b)); // pathing review 10-23 W-1: capped (2 already makes it a trap)
        RouteMap.PLANS.remove(key(p)); // the TRAPS entry remembers the spot; no bogus "failed toward the bank" edge (review 10-22)
        rescued++;
        NavWalker.rescued(p); // planner-guard-fix F1: charged to the planner only if it was walking this bot
        String line = "[Watchdog] " + p.getUsername() + " stuck at " + at + " for " + mins + " min" + (doing != null ? " (" + doing + ")" : "") + ", teleported to the bank at " + bank + ", path " + path; // batch 26 fix 5: the path tag
        System.out.println(line); flog(p, line); // batch 19a design 3.2
        try { Scenes.RescueTalk.saw(p, at, bank, System.currentTimeMillis()); } catch (Throwable ignored) { } // batch 28
        // End its task so it chooses a new one from the bank instead of walking straight back in.
        final Player bot = p;
        GameWorld.getPulser().submit(new Pulse(1) {
            @Override public boolean pulse() {
                try {
                    AfkTasks.Runner r = AfkTasks.RUNNING.get(key(bot));
                    if (r != null) r.finish("Got stuck, teleported out.");
                    TravellingTraders.unstick(bot);
                } catch (Throwable ignored) { }
                BotChat.say(bot, BotChat.pick("Finally! Out of there.", "Home teleport. Never again.", "Right, that was embarrassing.", "Stuck for ages. Teleported out.", "How did I even get in there?"));
                return true;
            }
        });
        save();
    }

    // ------------------------------------------------------------ batch 18: scenario records and the daily stuck report

    /** batch 18 item1: what a bot is trying to do, from its own state (the Watchdog, NavWalker and the trip hooks share it). */
    static final class Goal { String walker = "other", kind = "other", last = "", nav = "no planner state", navShort = "none", map = "none"; Location from, to; }

    static Goal goal(Player p) {
        Goal g = new Goal();
        if (p == null) return g;
        String k = key(p);
        try {
            SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(k);
            AfkTasks.Runner r = AfkTasks.RUNNING.get(k);
            QuestRunner q = QuestRunner.of(p);
            if (br != null && br.graveAt != null) { g.walker = "grave"; g.kind = "grave"; g.to = br.graveAt; g.from = br.graveFrom; }
            else if (br != null && br.shopAt != null) { g.walker = "shop"; g.kind = "shop"; g.to = br.shopAt; g.from = br.shopFrom; g.last = String.valueOf(br.shopName); }
            else if (q != null && QuestRunner.walking(p)) { g.walker = "quest"; g.kind = "quest step"; g.to = q.stepDest(); g.from = q.stepFrom; g.last = q.stepTrace(); }
            else if (r != null) {
                g.walker = q != null ? "quest" : "afk";
                AfkTasks.State st = r.state;
                g.kind = st == AfkTasks.State.TO_BANK ? "bank" : st == AfkTasks.State.TO_SPOT ? "job spot" : st == AfkTasks.State.TO_FURNACE ? "furnace" : st == AfkTasks.State.RETREAT ? "retreat" : "at the job (" + st.name().toLowerCase(Locale.ROOT) + ")";
                g.to = r.dest != null && st != AfkTasks.State.WORK ? r.dest : r.spot; g.from = r.walkFrom; g.last = r.last;
            }
            else if (br != null && br.partyUntil > System.currentTimeMillis() && br.partyAt != null) { g.walker = "party"; g.kind = "party"; g.to = br.partyAt; }
            else if (br != null && br.crew != null) { g.walker = "crew"; g.kind = "crew"; }
            else for (TravellingTraders.Trader t : TravellingTraders.ALL) if (t.bot == p) { g.walker = "trader"; g.kind = "trader " + t.phase.name().toLowerCase(Locale.ROOT); }
        } catch (Throwable ignored) { }
        try {
            NavWalker.State s = NavWalker.STATES.get(k);
            if (s == null) { g.nav = NavWalker.ON ? "no planner state" : "planner off"; g.navShort = NavWalker.ON ? "none" : "off"; }
            else if (s.route != null) { g.nav = "route, hop " + s.hop + "/" + s.route.hops.size() + " to " + s.dest + ", still " + s.still; g.navShort = "route"; if (g.to == null) g.to = s.dest; if (g.from == null) g.from = s.from; }
            else { g.nav = "NO ROUTE to " + s.dest; g.navShort = "no route"; if (g.to == null) g.to = s.dest; if (g.from == null) g.from = s.from; }
            Object[] pl = RouteMap.PLANS.get(k);
            if (pl != null && pl.length > 3) g.map = "plan " + (System.currentTimeMillis() - (Long) pl[3]) / 1000 + " s old";
            long[] stk = RouteMap.STUCKS.get(k);
            if (stk != null) g.map += ", " + stk[0] + " stucks, last " + (System.currentTimeMillis() - stk[1]) / 1000 + " s ago";
        } catch (Throwable ignored) { }
        return g;
    }

    /** The destination type for a NavWalker log line: the bot's own goal when dest is (near) it, else a bank tile, else the activity line. */
    static String destType(Player p, Location dest) {
        try {
            Goal g = goal(p);
            if (g.to != null && dest != null && g.to.getDistance(dest) <= 40 && !"other".equals(g.kind)) return g.kind.startsWith("at the job") ? "job spot" : g.kind;
            if (dest != null) for (Location b : AfkTasks.BANKS) if (b.getDistance(dest) <= 8) return "bank";
            String a = BotChat.activityOf(p);
            if (a != null) { a = a.toLowerCase(Locale.ROOT); if (a.contains("quest") || a.startsWith("doing ")) return "quest step"; if (a.contains("shop") || a.contains("buying")) return "shop"; if (a.contains("bank")) return "bank"; if (a.startsWith("off getting")) return "job spot"; if (a.contains("grave")) return "grave"; }
            return "trader".equals(g.walker) || "party".equals(g.walker) || "crew".equals(g.walker) ? g.walker : "other";
        } catch (Throwable t) { return "other"; }
    }

    /** batch 18: scenario records by source since boot (trials and the report read them). */
    static final Map<String, java.util.concurrent.atomic.AtomicInteger> SCEN = new ConcurrentHashMap<>();
    static final boolean ECHO = Boolean.getBoolean("scenario.echo"); // trials: one [Scenario] line per record

    /** batch 18 items 1-3: one scenario record (builder F's Scenarios.record does the dedupe, cap and file), plus the daily counters. Never throws. */
    static void scenario(Player bot, String source, String walker, Location from, Location to, Location stuckAt, String reason) { scenario(bot, source, walker, from, to, stuckAt, reason, null); }

    /** batch 19a B2: g = the Watchdog's own goal when the caller has one (rescue, idle); a failure source also gets one failure record, its fid in the scenario line. */
    static void scenario(Player bot, String source, String walker, Location from, Location to, Location stuckAt, String reason, Goal g) {
        try {
            String act = bot != null ? BotChat.activityOf(bot) : null;
            SCEN.computeIfAbsent(source, x -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            if (ECHO) System.out.println("[Scenario] " + source + " " + walker + " " + (bot != null ? bot.getUsername() : "?") + " from " + from + " to " + to + " stuck " + stuckAt + ": " + reason + (act != null ? " (" + act + ")" : ""));
            String fid = null;
            if (FAIL_ON && source != null && FAIL_SRC.contains(source) && traced(bot)) try { // batch 19a B2: the record (A's Failures builds it); the walk's own from/to/walker win over the goal's
                Goal fg = g != null ? g : goal(bot);
                if (walker != null) fg.walker = walker; if (from != null) fg.from = from; if (to != null) fg.to = to;
                String sid = from != null && to != null ? Scenarios.id(source, walker == null ? "other" : walker.toLowerCase(Locale.ROOT), from, to) : null;
                fid = fail(bot, source, sid, fg, stuckAt != null ? stuckAt : bot.getLocation(), reason);
            } catch (Throwable ignored) { }
            Scenarios.record(bot, source, walker, act, from, to, stuckAt, reason, fid); // batch 19a: fid (null = the batch 18 line)
            if (stuckAt != null && ("rescue".equals(source) || "never_reached".equals(source) || "idle_giveup".equals(source))) {
                String c = (stuckAt.getX() >> 3) + "," + (stuckAt.getY() >> 3) + "," + stuckAt.getZ();
                Daily.note("cell", c + "|" + source);
                if (act != null) Daily.note("act", c + "\t" + (act.length() > 70 ? act.substring(0, 70) : act));
            }
        } catch (Throwable ignored) { }
    }

    /**
     * batch 18 item4 (the owner 10-25: daily, not weekly): data/reports/stuck-YYYY-MM-DD.md, written at the UTC rollover off the
     * game thread (newest 30 kept). Counters live in memory (note() is a map merge) and are saved every 10 minutes and at the
     * rollover to data/reports/stuck-counts.json (8 days), so the 7-day column survives restarts. partial() writes today's so far.
     */
    static final class Daily {
        static final Map<String, Map<String, Integer>> TODAY = new ConcurrentHashMap<>();
        static final java.util.TreeMap<String, Map<String, Map<String, Integer>>> DAYS = new java.util.TreeMap<>(); // guarded by Daily.class; today's entry is a snapshot
        static volatile String day; // null until the saved counts are read
        static long lastSave;
        static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "StuckReport"); t.setDaemon(true); return t; });
        static final java.time.format.DateTimeFormatter D = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneOffset.UTC);

        static String utc(long ms) { return D.format(java.time.Instant.ofEpochMilli(ms)); }
        static String minusDays(String d, int n) { return java.time.LocalDate.parse(d).minusDays(n).toString(); }
        static File dir() { return new File(ServerConstants.DATA_PATH, "reports"); }

        static void note(String sec, String key) {
            if (day == null || key == null) return;
            Map<String, Integer> m = TODAY.computeIfAbsent(sec, x -> new ConcurrentHashMap<>());
            if (m.size() < 3_000 || m.containsKey(key)) m.merge(key, 1, Integer::sum);
        }

        static Map<String, Map<String, Integer>> snap() {
            Map<String, Map<String, Integer>> c = new java.util.HashMap<>();
            for (Map.Entry<String, Map<String, Integer>> e : TODAY.entrySet()) c.put(e.getKey(), new java.util.HashMap<>(e.getValue()));
            return c;
        }

        static void start() {
            IO.submit(() -> {
                try {
                    dir().mkdirs();
                    String today = utc(System.currentTimeMillis());
                    String prev;
                    synchronized (Daily.class) {
                        readJson();
                        Map<String, Map<String, Integer>> t = DAYS.get(today);
                        if (t != null) for (Map.Entry<String, Map<String, Integer>> e : t.entrySet()) TODAY.computeIfAbsent(e.getKey(), x -> new ConcurrentHashMap<>()).putAll(e.getValue());
                        prev = DAYS.lowerKey(today);
                    }
                    day = today; lastSave = System.currentTimeMillis();
                    if (prev != null && !new File(dir(), "stuck-" + prev + ".md").exists()) write(prev, false); // down over a rollover: that day's report now
                } catch (Throwable t) { System.out.println("[Watchdog] stuck report start: " + t); day = utc(System.currentTimeMillis()); }
            });
        }

        /** From the Watchdog tick (every 30 s): the rollover and the 10-minute save. Cheap: a date format and, when due, one map copy. */
        static void tick(long now) {
            final String d = day;
            if (d == null) return;
            String nd = utc(now);
            if (!nd.equals(d)) {
                final Map<String, Map<String, Integer>> s = snap(); TODAY.clear(); day = nd; lastSave = now;
                IO.submit(() -> { try { synchronized (Daily.class) { DAYS.put(d, s); } saveJson(); write(d, false); } catch (Throwable t) { System.out.println("[Watchdog] stuck report: " + t); } });
            } else if (now - lastSave > 600_000L) {
                lastSave = now; final Map<String, Map<String, Integer>> s = snap();
                IO.submit(() -> { try { synchronized (Daily.class) { DAYS.put(d, s); } saveJson(); } catch (Throwable t) { System.out.println("[Watchdog] stuck counts: " + t); } });
            }
        }

        /** Today's report so far (trials, or an operator); returns the file, waiting up to 10 s for the writer thread. */
        static File partial() {
            final String d = day != null ? day : utc(System.currentTimeMillis());
            final Map<String, Map<String, Integer>> s = snap();
            try { return IO.submit(() -> { synchronized (Daily.class) { DAYS.put(d, s); } saveJson(); return write(d, true); }).get(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Throwable t) { System.out.println("[Watchdog] stuck report: " + t); return null; }
        }

        static void readJson() {
            File f = new File(dir(), "stuck-counts.json");
            if (!f.exists()) return;
            try (FileReader rd = new FileReader(f)) {
                JSONObject o = (JSONObject) new JSONParser().parse(rd);
                for (Object dk : o.keySet()) {
                    Map<String, Map<String, Integer>> dm = new java.util.HashMap<>();
                    JSONObject secs = (JSONObject) o.get(dk);
                    for (Object sk : secs.keySet()) { Map<String, Integer> m = new java.util.HashMap<>(); JSONObject so = (JSONObject) secs.get(sk); for (Object kk : so.keySet()) m.put(String.valueOf(kk), ((Number) so.get(kk)).intValue()); dm.put(String.valueOf(sk), m); }
                    DAYS.put(String.valueOf(dk), dm);
                }
            } catch (Throwable t) { System.out.println("[Watchdog] could not read stuck counts: " + t); }
        }

        @SuppressWarnings("unchecked")
        static void saveJson() {
            JSONObject o = new JSONObject();
            synchronized (Daily.class) {
                while (DAYS.size() > 8) DAYS.pollFirstEntry();
                for (Map.Entry<String, Map<String, Map<String, Integer>>> e : DAYS.entrySet()) {
                    JSONObject secs = new JSONObject();
                    for (Map.Entry<String, Map<String, Integer>> s : e.getValue().entrySet()) { JSONObject so = new JSONObject(); so.putAll(s.getValue()); secs.put(s.getKey(), so); }
                    o.put(e.getKey(), secs);
                }
            }
            try { dir().mkdirs(); Ledger.writeAtomic(new File(dir(), "stuck-counts.json"), o.toJSONString()); } catch (Throwable t) { System.out.println("[Watchdog] could not save stuck counts: " + t); saveFailed("stuck-counts.json", t); } // batch 19c (design 10)
        }

        /** Section counts: [that day, the 7 days ending on it]. */
        static Map<String, int[]> counts(String d, String sec) {
            Map<String, int[]> out = new java.util.HashMap<>();
            synchronized (Daily.class) {
                for (Map.Entry<String, Map<String, Map<String, Integer>>> e : DAYS.subMap(minusDays(d, 6), true, d, true).entrySet()) {
                    Map<String, Integer> m = e.getValue().get(sec); if (m == null) continue;
                    boolean that = e.getKey().equals(d);
                    for (Map.Entry<String, Integer> c : m.entrySet()) { int[] v = out.computeIfAbsent(c.getKey(), x -> new int[2]); v[1] += c.getValue(); if (that) v[0] += c.getValue(); }
                }
            }
            return out;
        }

        static java.util.List<Map.Entry<String, int[]>> top(Map<String, int[]> m, int n) {
            java.util.List<Map.Entry<String, int[]>> l = new java.util.ArrayList<>(m.entrySet());
            l.sort((a, b) -> a.getValue()[0] != b.getValue()[0] ? b.getValue()[0] - a.getValue()[0] : a.getValue()[1] != b.getValue()[1] ? b.getValue()[1] - a.getValue()[1] : a.getKey().compareTo(b.getKey()));
            return l.subList(0, Math.min(n, l.size()));
        }

        static String cellText(String c) {
            String[] a = c.split(","); int x = Integer.parseInt(a[0]) << 3, y = Integer.parseInt(a[1]) << 3;
            return x + "-" + (x + 7) + ", " + y + "-" + (y + 7) + ", " + a[2];
        }

        /** Cell -> [that day, 7 days] summed over the three sources, and per cell [rescues, never reached, idle] for that day. */
        static Map<String, int[]> cells(String d, Map<String, int[]> bySrc) {
            Map<String, int[]> cell = new java.util.HashMap<>();
            for (Map.Entry<String, int[]> e : counts(d, "cell").entrySet()) {
                String k = e.getKey(); int bar = k.indexOf('|'); if (bar < 0) continue;
                String c = k.substring(0, bar), src = k.substring(bar + 1);
                int[] v = cell.computeIfAbsent(c, x -> new int[2]); v[0] += e.getValue()[0]; v[1] += e.getValue()[1];
                if (bySrc != null) bySrc.computeIfAbsent(c, x -> new int[3])["rescue".equals(src) ? 0 : "never_reached".equals(src) ? 1 : 2] += e.getValue()[0];
            }
            return cell;
        }

        static File write(String d, boolean partial) throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("# Stuck report ").append(d).append(" (UTC)").append(partial ? ", partial: written " + java.time.Instant.now().toString().substring(11, 16) + " UTC" : "").append("\n\n");
            sb.append("\"24 h\" is this UTC day").append(partial ? " so far" : "").append("; \"7 days\" is this day and the six before it. Cells are 8x8 tiles (x range, y range, plane).\n\n");
            Map<String, int[]> bySrc = new java.util.HashMap<>();
            Map<String, int[]> cell = cells(d, bySrc);
            Map<String, String> mainAct = new java.util.HashMap<>(); Map<String, Integer> actN = new java.util.HashMap<>();
            for (Map.Entry<String, int[]> e : counts(d, "act").entrySet()) {
                String k = e.getKey(); int tab = k.indexOf('\t'); if (tab < 0) continue;
                String c = k.substring(0, tab); int n = e.getValue()[0] * 10_000 + e.getValue()[1];
                if (n > actN.getOrDefault(c, -1)) { actN.put(c, n); mainAct.put(c, k.substring(tab + 1)); }
            }
            Map<String, int[]> prevCells = cells(minusDays(d, 1), null); prevCells.values().removeIf(v -> v[0] == 0);
            java.util.Set<String> prevTop = new java.util.HashSet<>(); for (Map.Entry<String, int[]> e : top(prevCells, 20)) prevTop.add(e.getKey());
            sb.append("## Top stuck cells (rescues + never-reached + idle give-ups)\n\n| cell | 24 h | 7 days | rescues | never reached | idle | main activity | |\n|---|---|---|---|---|---|---|---|\n");
            for (Map.Entry<String, int[]> e : top(cell, 20)) {
                int[] w = bySrc.getOrDefault(e.getKey(), new int[3]);
                sb.append("| ").append(cellText(e.getKey())).append(" | ").append(e.getValue()[0]).append(" | ").append(e.getValue()[1]).append(" | ").append(w[0]).append(" | ").append(w[1]).append(" | ").append(w[2])
                  .append(" | ").append(md(mainAct.getOrDefault(e.getKey(), "-"))).append(" | ").append(e.getValue()[0] > 0 && !prevTop.contains(e.getKey()) ? "new today" : "").append(" |\n");
            }
            if (cell.isEmpty()) sb.append("| none | | | | | | | |\n");
            section(sb, "Never-reached shops", "shop", counts(d, "shop"));
            section(sb, "No-route destinations (type @ destination cell)", "destination", counts(d, "noroute"));
            section(sb, "Outside-grid destinations (the planner skips these; the old walker takes them)", "destination cell", counts(d, "outside"));
            section(sb, "Redirects by reason", "reason", counts(d, "redirect"));
            section(sb, "Idle give-ups by reason", "reason", counts(d, "idle"));
            if (FAIL_ON) try { failures(sb, d); } catch (Throwable t) { sb.append("\n(failure sections: ").append(t).append(")\n"); } // batch 19a B6 (design 8): not written with -Dfailures=false
            replays(sb, d);
            try { failed(sb, d); } catch (Throwable t) { sb.append("\n(failed replays: ").append(t).append(")\n"); } // batch 18 rc3: named bugs
            dir().mkdirs();
            File f = new File(dir(), "stuck-" + d + ".md");
            Ledger.writeAtomic(f, sb.toString());
            File[] old = dir().listFiles((x, n) -> n.startsWith("stuck-") && n.endsWith(".md"));
            if (old != null && old.length > 30) { java.util.Arrays.sort(old); for (int i = 0; i < old.length - 30; i++) old[i].delete(); } // the newest 30 (names sort by date)
            System.out.println("[Watchdog] stuck report " + (partial ? "(partial) " : "") + "written: " + f.getPath());
            return f;
        }

        static String md(String s) { return s.replace("|", "/").replace("\n", " ").replace("\t", " "); }

        // ------------------------------------------------------------ batch 19a B6 (design 8): failures and bugs
        // Counters (Builder A's Failures calls these, any thread; a map merge each): note("fail", reason) for every failure, written or not;
        // note("failcap", src) for each record dropped by a cap; note("bug", bugId) for each record grouped into a bug;
        // note("exc", where + "\t" + exception simple class + "\t" + first frame) for each exception. Bug metadata (place, rsub, example,
        // status, first) is read from bugs.json here on the StuckReport thread ([Fable] B6: the design 14 contract has no in-memory accessor).
        static final String[] REASONS = {"death", "combat-interrupt", "timeout", "outside-grid", "rule-gate", "door-closed", "npc-blocking", "no-route", "handoff", "wrong-side-hop", "oscillation", "loop", "trap-cell", "blocked-object", "engine-pathfinder-giveup", "unknown"};

        static int[] sum(Map<String, int[]> m) { int[] s = new int[2]; for (int[] v : m.values()) { s[0] += v[0]; s[1] += v[1]; } return s; }

        /** bugs.json's "bugs" object (Scenarios.rDir(), else data/survey), or empty. StuckReport thread only. */
        static JSONObject bugsJson() {
            File f = new File(Scenarios.rDir(), "bugs.json"); if (!f.exists()) f = new File(new File(ServerConstants.DATA_PATH, "survey"), "bugs.json"); // batch 19a review S4: rDir(), where Failures writes it
            if (!f.exists()) return new JSONObject();
            try (FileReader rd = new FileReader(f)) { Object o = new JSONParser().parse(rd); Object b = o instanceof JSONObject ? ((JSONObject) o).get("bugs") : null; return b instanceof JSONObject ? (JSONObject) b : new JSONObject(); }
            catch (Throwable t) { System.out.println("[Watchdog] stuck report: could not read bugs.json: " + t); return new JSONObject(); }
        }

        static String str(JSONObject o, String k) { Object v = o == null ? null : o.get(k); return v == null ? null : String.valueOf(v); }

        /** The reason a bug id starts with ("b-<reason>-..."), or null. */
        static String reasonOf(String id) { for (String r : BY_LENGTH) if (id.startsWith("b-" + r + "-")) return r; return id.startsWith("b-exception-") ? "exception" : null; } // batch 19c B5: every domain's codes, longest first (death-spike before death)

        // batch 19c B5 (design 4-10): each domain's reason codes in the design's order (the report's Failures-by-reason blocks); index = DOMAINS
        static final String[] DOMAINS = {"path", "economy", "quest", "combat", "skilling", "lifecycle", "health", "save"};
        static final String[][] DOMAIN_CODES = {REASONS,
                {"coin-vanished", "coin-appeared", "coin-drift-world", "item-lost-on-handover", "item-unpaid-stock", "stock-shortfall", "ge-take-stalled", "ge-supply-refused", "ge-fills-unpaid", "ge-pending-backlog", "ge-player-offers-stale",
                 "deal-stood-up", "deal-settle-failed", "split-cancelled", "trade-timeout", "botswap-failed", "errand-dropped", "deal-deadlock", "shop-out-of-stock", "shop-cant-afford", "shop-loop", "bank-full", "bot-broke",
                 "price-outlier", "deal-price-outlier", "pay-failed-with-coins", "toll-unaffordable", "fee-unaffordable", "coin-drain"}, // batch 22-1: CoinWatch's reason (it was missing, so the daily report could not name it)
                {"step-stalled", "step-failed", "quest-abandoned", "resume-dropped", "checkpoint-regressed", "restore-failed", "reward-missing", "reward-twice", "reward-oversized", "quest-disabled-today"},
                {"repeat-death", "death-spike", "kept-under-rule", "grave-short", "grave-lost", "idle-death"},
                {"inventory-full", "spot-depleted", "wrong-tool", "out-of-supplies", "spot-too-dangerous", "no-facility", "no-gain", "no-xp"},
                {"login-failed", "name-lost", "return-overdue", "vanished", "logout-mid-task", "logout-mid-quest", "idle-no-goal"},
                {"tick-overrun", "long-pulse", "memory-growth", "gc-pressure", "script-starvation", "exception-storm", "thread-violation", "io-failed"},
                {"sanity-failed", "file-quarantined", "save-refused", "save-failed", "save-stale", "profile-unreadable", "restore-short"}};
        static final String[] BY_LENGTH; static { java.util.List<String> l = new java.util.ArrayList<>(); for (String[] c : DOMAIN_CODES) l.addAll(java.util.Arrays.asList(c)); l.sort((a, b) -> b.length() - a.length()); BY_LENGTH = l.toArray(new String[0]); }
        /** batch 19c B5: a code's domain ("exception" is health's; unknown: "?"). */
        static String domainOf(String code) { if (code == null) return "?"; if ("exception".equals(code)) return "health"; for (int i = 0; i < DOMAIN_CODES.length; i++) for (String c : DOMAIN_CODES[i]) if (c.equals(code)) return DOMAINS[i]; return "?"; }
        // batch 19c B5: a "failcap" note is the record's src; its domain by the design's src names ([Fable] quest_fail and logout are shared: path and lifecycle take them). A "domain|src" key (if A notes one) is read directly.
        static final String[][] SRC_DOMAIN = {{"path", "rescue", "idle_giveup", "never_reached", "failed_trip", "quest_fail", "replay_fail", "replay_timeout", "replay_skip"},
                {"economy", "trip_end", "profile_save", "shop", "bank", "toll", "fee", "deal", "trade", "botswap", "stock", "ge", "price", "wallet", "watchdog"},
                {"quest", "quest_stall", "quest_resume", "quest_reward", "quest_rate"}, {"combat", "death", "death_rate", "grave"}, {"skilling", "job_end", "no_gain"},
                {"lifecycle", "login", "logout", "lifecycle", "idle"}, {"health", "tick", "pulse", "memory", "scripts", "exception", "exception_rate", "thread", "io"}, {"save", "boot", "load", "save"}};
        static Map<String, int[]> capByDomain(Map<String, int[]> cap) {
            Map<String, int[]> out = new java.util.HashMap<>();
            for (Map.Entry<String, int[]> e : cap.entrySet()) {
                String k = e.getKey(), dom = null; int bar = k.indexOf('|');
                if (bar > 0) dom = k.substring(0, bar); else for (String[] s : SRC_DOMAIN) { for (int i = 1; i < s.length && dom == null; i++) if (s[i].equals(k)) dom = s[0]; if (dom != null) break; }
                int[] v = out.computeIfAbsent(dom == null ? "path" : dom, x -> new int[2]); v[0] += e.getValue()[0]; v[1] += e.getValue()[1]; // [Fable] an unknown src counts as path (19a's only emitter)
            }
            return out;
        }

        /** "b-rule-gate-204,201,0-alkharid-toll" -> "3264-3279, 3216-3231, 0" (16x16 cluster; "dest " when the id uses the destination). */
        static String bugPlace(String id) {
            try {
                String r = reasonOf(id); if (r == null || "exception".equals(r)) return "-";
                String rest = id.substring(r.length() + 3); boolean dest = rest.startsWith("dest-"); if (dest) rest = rest.substring(5);
                String[] a = rest.split(",", 3); int x = Integer.parseInt(a[0]) << 4, y = Integer.parseInt(a[1]) << 4; String z = a[2].split("-", 2)[0];
                return (dest ? "dest " : "") + x + "-" + (x + 15) + ", " + y + "-" + (y + 15) + ", " + z;
            } catch (Throwable t) { return "-"; }
        }

        static void failures(StringBuilder sb, String d) {
            String from = minusDays(d, 6), prevD = minusDays(d, 1);
            Map<String, int[]> fr = counts(d, "fail");
            int[] cap = sum(counts(d, "failcap")); Map<String, int[]> exc = counts(d, "exc");
            int[] excN = sum(exc);
            JSONObject meta = bugsJson();
            Map<String, int[]> bc = counts(d, "bug"), bprev = counts(prevD, "bug");
            // the file's own day counts (live counts, written at most once a minute): the larger of the two wins
            for (Object k : meta.keySet()) {
                Object e = meta.get(k); if (!(e instanceof JSONObject)) continue;
                Object days = ((JSONObject) e).get("days"); if (!(days instanceof JSONObject)) continue;
                int c24 = 0, c7 = 0, p24 = 0;
                for (Object dk : ((JSONObject) days).keySet()) {
                    String ds = String.valueOf(dk); Object n = ((JSONObject) days).get(dk); int v = n instanceof Number ? ((Number) n).intValue() : 0;
                    if (ds.equals(d)) c24 += v;
                    if (ds.equals(prevD)) p24 += v;
                    if (ds.compareTo(from) >= 0 && ds.compareTo(d) <= 0) c7 += v;
                }
                String id = String.valueOf(k);
                int[] v = bc.computeIfAbsent(id, x -> new int[2]); v[0] = Math.max(v[0], c24); v[1] = Math.max(v[1], c7);
                int[] pv = bprev.computeIfAbsent(id, x -> new int[2]); pv[0] = Math.max(pv[0], p24);
            }
            if (excN[0] == 0 && excN[1] == 0) for (Map.Entry<String, int[]> e : bc.entrySet()) if (e.getKey().startsWith("b-exception-")) { excN[0] += e.getValue()[0]; excN[1] += e.getValue()[1]; }
            // batch 19c B5 (design 2): one block per domain; the path block keeps the 19a rows (14 codes, capped, exceptions) directly under the heading
            Map<String, int[]> capBy = capByDomain(counts(d, "failcap"));
            sb.append("\n## Failures by reason (data/survey/failures/)\n\n| path | 24 h | 7 days |\n|---|---|---|\n");
            for (String r : REASONS) { int[] v = fr.getOrDefault(r, new int[2]); sb.append("| ").append(r).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" |\n"); }
            int[] pc = capBy.getOrDefault("path", new int[2]);
            sb.append("| capped | ").append(pc[0]).append(" | ").append(pc[1]).append(" |\n");
            sb.append("| exceptions | ").append(excN[0]).append(" | ").append(excN[1]).append(" |\n");
            java.util.Set<String> shownCodes = new java.util.HashSet<>(java.util.Arrays.asList(REASONS)); shownCodes.add("exception");
            for (int di = 1; di < DOMAINS.length; di++) {
                sb.append("\n| ").append(DOMAINS[di]).append(" | 24 h | 7 days |\n|---|---|---|\n");
                for (String r : DOMAIN_CODES[di]) { int[] v = fr.getOrDefault(r, new int[2]); shownCodes.add(r); sb.append("| ").append(r).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" |\n"); }
                int[] c = capBy.getOrDefault(DOMAINS[di], new int[2]); sb.append("| capped | ").append(c[0]).append(" | ").append(c[1]).append(" |\n");
            }
            java.util.List<String> otherCodes = new java.util.ArrayList<>(); for (String r : fr.keySet()) if (!shownCodes.contains(r)) otherCodes.add(r); java.util.Collections.sort(otherCodes);
            if (!otherCodes.isEmpty()) { sb.append("\n| other | 24 h | 7 days |\n|---|---|---|\n"); for (String r : otherCodes) { int[] v = fr.get(r); sb.append("| ").append(md(r)).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" |\n"); } } // a code this build does not list (a newer detector): shown, never lost
            if (cap[0] > 0) sb.append("\nfailures capped: ").append(cap[0]).append(" (records dropped by the hourly/daily caps; the counters and bugs still moved)\n");
            int econAt = sb.length(); // batch 19c B5: the "Economy today" line goes here once the bugs are read
            int econRecords = 0; for (String r : DOMAIN_CODES[1]) econRecords += fr.getOrDefault(r, new int[2])[0];
            // which bugs are new today: bugs.json "first", else no count on the six days before
            java.util.Set<String> seenBefore = new java.util.HashSet<>();
            synchronized (Daily.class) {
                for (Map.Entry<String, Map<String, Map<String, Integer>>> e : DAYS.subMap(from, true, d, false).entrySet()) {
                    Map<String, Integer> m = e.getValue().get("bug");
                    if (m != null) for (Map.Entry<String, Integer> c : m.entrySet()) if (c.getValue() > 0) seenBefore.add(c.getKey());
                }
            }
            java.util.List<String[]> open = new java.util.ArrayList<>(), fresh = new java.util.ArrayList<>();
            for (Map.Entry<String, int[]> e : bc.entrySet()) {
                String id = e.getKey(); if (id.startsWith("b-exception-") || e.getValue()[0] + e.getValue()[1] == 0) continue; // exceptions have their own section
                JSONObject m = meta.get(id) instanceof JSONObject ? (JSONObject) meta.get(id) : null;
                String st = str(m, "status");
                boolean isNew; String first = str(m, "first");
                if (first != null) { try { isNew = utc(Long.parseLong(first)).equals(d); } catch (Throwable t) { isNew = !seenBefore.contains(id); } } else isNew = !seenBefore.contains(id);
                Object ex = m == null ? null : m.get("examples");
                String example = ex instanceof org.json.simple.JSONArray && !((org.json.simple.JSONArray) ex).isEmpty() ? String.valueOf(((org.json.simple.JSONArray) ex).get(((org.json.simple.JSONArray) ex).size() - 1)) : "-";
                String place = str(m, "place") != null ? str(m, "place") : bugPlace(id);
                String r = str(m, "reason") != null ? str(m, "reason") : String.valueOf(reasonOf(id)); String rs = str(m, "rsub");
                int[] v = e.getValue(); int p = bprev.getOrDefault(id, new int[2])[0];
                String change = isNew ? "new" : (v[0] - p > 0 ? "+" : "") + (v[0] - p);
                String total = String.valueOf(m != null && m.get("count") instanceof Number ? ((Number) m.get("count")).intValue() : v[1]);
                String dom = str(m, "domain") != null ? str(m, "domain") : domainOf(r); // batch 19c B5
                String[] row = {id, String.valueOf(v[0]), String.valueOf(v[1]), place, r + (rs != null && !rs.isEmpty() ? " / " + rs : ""), example, st == null ? "?" : st, change, total, dom};
                if (isNew && v[0] > 0) fresh.add(row);
                if (st == null || "open".equals(st)) open.add(row);
            }
            java.util.Comparator<String[]> by = (a, b) -> { int x = Integer.parseInt(b[1]) - Integer.parseInt(a[1]); if (x != 0) return x; x = Integer.parseInt(b[2]) - Integer.parseInt(a[2]); return x != 0 ? x : a[0].compareTo(b[0]); };
            open.sort(by); fresh.sort(by);
            { // batch 19c B5: the Economy today line (records, top 3 open economy bugs by 24 h, coin-drift-world status)
                StringBuilder e = new StringBuilder("\nEconomy today: ").append(econRecords).append(" records; top economy bugs: ");
                int k = 0; for (String[] w : open) if ("economy".equals(w[9]) && Integer.parseInt(w[1]) > 0 && k < 3) { e.append(k++ > 0 ? ", " : "").append(w[0]).append(" (").append(w[1]).append(")"); }
                if (k == 0) e.append("none");
                String drift = null; for (Map.Entry<String, int[]> x : bc.entrySet()) if (x.getKey().startsWith("b-coin-drift-world-")) { JSONObject m = meta.get(x.getKey()) instanceof JSONObject ? (JSONObject) meta.get(x.getKey()) : null; drift = (m != null && str(m, "status") != null ? str(m, "status") : "?") + ", " + x.getValue()[0] + " today"; }
                e.append("; coin-drift-world: ").append(drift == null ? "clear" : drift).append("\n");
                sb.insert(econAt, e);
            }
            sb.append("\n## Top open bugs (data/survey/bugs.json)\n\n| bug | domain | 24 h | 7 d | place | reason / detail | example scenario | status | change |\n|---|---|---|---|---|---|---|---|---|\n"); // batch 19c B5: domain after the id
            for (int i = 0; i < Math.min(20, open.size()); i++) { String[] w = open.get(i); sb.append("| ").append(md(w[0])).append(" | ").append(md(w[9])).append(" | ").append(w[1]).append(" | ").append(w[2]).append(" | ").append(md(w[3])).append(" | ").append(md(w[4])).append(" | ").append(md(w[5])).append(" | ").append(md(w[6])).append(" | ").append(w[7]).append(" |\n"); }
            if (open.isEmpty()) sb.append("| none | | | | | | | | |\n");
            sb.append("\n## New bugs since yesterday\n\n| bug | place | reason | count | example scenario |\n|---|---|---|---|---|\n");
            for (int i = 0; i < Math.min(20, fresh.size()); i++) { String[] w = fresh.get(i); sb.append("| ").append(md(w[0])).append(" | ").append(md(w[3])).append(" | ").append(md(w[4])).append(" | ").append(w[8]).append(" | ").append(md(w[5])).append(" |\n"); }
            if (fresh.isEmpty()) sb.append("| none | | | | |\n");
            sb.append("\n## Exceptions\n\n| where | class | 24 h | 7 d | first frame |\n|---|---|---|---|---|\n");
            int shown = 0;
            for (Map.Entry<String, int[]> e : top(exc, 10)) { String[] a = e.getKey().split("\t", 3); sb.append("| ").append(md(a[0])).append(" | ").append(a.length > 1 ? md(a[1]) : "-").append(" | ").append(e.getValue()[0]).append(" | ").append(e.getValue()[1]).append(" | ").append(a.length > 2 ? md(a[2]) : "-").append(" |\n"); shown++; }
            if (shown == 0) { // no "exc" notes: the exception bugs ("b-exception-<where>-<Class>")
                Map<String, int[]> eb = new java.util.HashMap<>();
                for (Map.Entry<String, int[]> e : bc.entrySet()) if (e.getKey().startsWith("b-exception-") && e.getValue()[0] + e.getValue()[1] > 0) eb.put(e.getKey(), e.getValue());
                for (Map.Entry<String, int[]> e : top(eb, 10)) {
                    String w = e.getKey().substring(12); int dash = w.lastIndexOf('-');
                    sb.append("| ").append(md(dash > 0 ? w.substring(0, dash) : w)).append(" | ").append(md(dash > 0 ? w.substring(dash + 1) : "-")).append(" | ").append(e.getValue()[0]).append(" | ").append(e.getValue()[1]).append(" | - |\n"); shown++;
                }
            }
            if (shown == 0) sb.append("| none | | | | |\n");
        }

        static void section(StringBuilder sb, String title, String col, Map<String, int[]> m) {
            sb.append("\n## ").append(title).append("\n\n| ").append(col).append(" | 24 h | 7 days |\n|---|---|---|\n");
            for (Map.Entry<String, int[]> e : top(m, 15)) sb.append("| ").append(md(e.getKey())).append(" | ").append(e.getValue()[0]).append(" | ").append(e.getValue()[1]).append(" |\n");
            if (m.isEmpty()) sb.append("| none | | |\n");
        }

        static final java.util.regex.Pattern RT = java.util.regex.Pattern.compile("\"t\"\\s*:\\s*(\\d+)"), RR = java.util.regex.Pattern.compile("\"result\"\\s*:\\s*\"(\\w+)\""), RL = java.util.regex.Pattern.compile("\"lag\"\\s*:\\s*(-?[0-9.]+)");

        /** The replayers' results (builder F's data/survey/scenario-results.jsonl): pass / fail / gave_up / skipped and the median incident-to-result lag ("lag", seconds). */
        static void replays(StringBuilder sb, String d) {
            String[] kinds = {"pass", "fail", "gave_up", "skipped"};
            int[][] n = new int[2][kinds.length]; java.util.List<Double> lag1 = new java.util.ArrayList<>(), lag7 = new java.util.ArrayList<>();
            String from = minusDays(d, 6);
            File f = Scenarios.resultsFile(); // batch 18 rc3: honours -Dscenarios.results.dir (review note)
            boolean any = false;
            if (f.exists()) try (java.io.BufferedReader rd = new java.io.BufferedReader(new FileReader(f))) {
                for (String l; (l = rd.readLine()) != null; ) {
                    java.util.regex.Matcher mt = RT.matcher(l), mr = RR.matcher(l);
                    if (!mt.find() || !mr.find()) continue;
                    String ld = utc(Long.parseLong(mt.group(1)));
                    if (ld.compareTo(from) < 0 || ld.compareTo(d) > 0) continue;
                    int i = java.util.Arrays.asList(kinds).indexOf(mr.group(1)); if (i < 0) continue;
                    any = true;
                    java.util.regex.Matcher ml = RL.matcher(l); Double lag = ml.find() ? Double.valueOf(ml.group(1)) : null;
                    n[1][i]++; if (lag != null) lag7.add(lag);
                    if (ld.equals(d)) { n[0][i]++; if (lag != null) lag1.add(lag); }
                }
            } catch (Throwable t) { sb.append("\n(could not read scenario-results.jsonl: ").append(t).append(")\n"); }
            sb.append("\n## Replays (").append(System.getProperty("scenarios.results.dir") == null ? "data/survey/scenario-results.jsonl" : md(f.getPath())).append(")\n\n| result | 24 h | 7 days |\n|---|---|---|\n"); // batch 18 rc3: the file actually read
            for (int i = 0; i < kinds.length; i++) sb.append("| ").append(kinds[i]).append(" | ").append(n[0][i]).append(" | ").append(n[1][i]).append(" |\n");
            sb.append("| median incident-to-result (s) | ").append(median(lag1)).append(" | ").append(median(lag7)).append(" |\n");
            if (!any) sb.append("\n").append(f.exists() ? "No results in this window." : "No results file yet.").append("\n");
        }

        /**
         * batch 18 rc3: "Failed replays (named bugs)": each id whose latest result (skips aside) on day d is a fail, or the gave_up that closed it
         * as a fail (Scenarios' 3-day rule, replayed here over the whole results file up to d); newest first, at most 40. StuckReport thread only.
         */
        @SuppressWarnings("unchecked")
        static void failed(StringBuilder sb, String d) {
            sb.append("\n## Failed replays (named bugs)\n\n| at (UTC) | id | source | walker | from -> to | failAt | why | nav |\n|---|---|---|---|---|---|---|---|\n");
            java.util.List<JSONObject> rows = new java.util.ArrayList<>(Scenarios.readResults());
            rows.sort(java.util.Comparator.comparingLong(j -> Scenarios.num(j.get("t"), 0)));
            Map<String, Scenarios.Res> m = new java.util.HashMap<>(); Map<String, Object[]> last = new java.util.HashMap<>(); // id -> {row, why}
            for (JSONObject j : rows) {
                long t = Scenarios.num(j.get("t"), 0); String ld = utc(t); if (ld.compareTo(d) > 0) break;
                String id = String.valueOf(j.get("id")), res = String.valueOf(j.get("result"));
                boolean closed = Scenarios.noteResult(m, id, res, t);
                if (!ld.equals(d) || "skipped".equals(res)) continue;
                if ("fail".equals(res)) last.put(id, new Object[]{j, String.valueOf(j.get("why"))});
                else if (closed) last.put(id, new Object[]{j, Scenarios.GAVE_WHY + " (" + j.get("why") + ")"});
                else last.remove(id); // a later pass (or a plain gave_up) that day
            }
            java.util.List<Object[]> l = new java.util.ArrayList<>(last.values());
            l.sort((a, b) -> Long.compare(Scenarios.num(((JSONObject) b[0]).get("t"), 0), Scenarios.num(((JSONObject) a[0]).get("t"), 0)));
            Map<String, String[]> sc = new java.util.HashMap<>(); boolean read = false; // id -> {source, walker, from, to}: Scenarios.INDEX, else scenarios.jsonl
            int shown = 0;
            for (Object[] e : l) {
                if (shown >= 40) { sb.append("| ").append(l.size() - 40).append(" more not shown | | | | | | | |\n"); break; }
                JSONObject j = (JSONObject) e[0]; String id = String.valueOf(j.get("id"));
                Scenarios.Sc s = Scenarios.INDEX.get(id);
                if (s != null) sc.put(id, new String[]{s.source, s.walker, Scenarios.tile(s.from), Scenarios.tile(s.to)});
                if (!sc.containsKey(id) && !read) { read = true; for (JSONObject r : Scenarios.readScenarios()) { String rid = String.valueOf(r.get("id")); if (last.containsKey(rid)) sc.put(rid, new String[]{String.valueOf(r.get("source")), String.valueOf(r.get("walker")), Scenarios.tile(Scenarios.ints(r.get("from"))), Scenarios.tile(Scenarios.ints(r.get("to")))}); } }
                String[] w = sc.getOrDefault(id, new String[]{"?", "?", "?", "?"});
                int[] fa = Scenarios.ints(j.get("failAt")); String nav = String.valueOf(j.get("nav")); if (nav.length() > 120) nav = nav.substring(0, 117) + "...";
                sb.append("| ").append(java.time.Instant.ofEpochMilli(Scenarios.num(j.get("t"), 0)).toString().substring(11, 16)).append(" | ").append(md(id)).append(" | ").append(md(w[0])).append(" | ").append(md(w[1]))
                  .append(" | ").append(w[2]).append(" -> ").append(w[3]).append(" | ").append(fa == null ? "-" : Scenarios.tile(fa)).append(" | ").append(md(String.valueOf(e[1]))).append(" | ").append(md(nav)).append(" |\n");
                shown++;
            }
            if (l.isEmpty()) sb.append("| none | | | | | | | |\n");
        }

        static String median(java.util.List<Double> l) {
            if (l.isEmpty()) return "n/a";
            java.util.Collections.sort(l); int m = l.size() / 2;
            double v = l.size() % 2 == 1 ? l.get(m) : (l.get(m - 1) + l.get(m)) / 2;
            return String.format(Locale.ROOT, "%.0f", v);
        }
    }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file(); if (!f.exists()) return;
            JSONObject d = (JSONObject) new JSONParser().parse(new FileReader(f));
            for (Object c : d.keySet()) TRAPS.put(Integer.parseInt(String.valueOf(c)), ((Number) d.get(c)).intValue());
        } catch (Throwable t) { System.out.println("[Watchdog] could not read traps: " + t); }
    }

    /** batch 19c (design 10 save-failed): one record per failed write of a bot file, never throws. */
    static void saveFailed(String file, Throwable t) { if (FAIL_ON) try { Failures.Life.saveFailed(file, t); } catch (Throwable ignored) { } } // A's helper: one per file per 10 min

    static File file() { return new File(ServerConstants.GRAND_EXCHANGE_DATA_PATH, "traps3.json"); } // traps3: after the bait fix; earlier files were full of fishing spots // traps2: only bots that were trying to leave count (the first version also counted idle bots)

    @SuppressWarnings("unchecked")
    static synchronized void save() {
        JSONObject d = new JSONObject();
        for (Map.Entry<Integer, Integer> e : TRAPS.entrySet()) d.put(String.valueOf(e.getKey()), e.getValue());
        try { Ledger.writeAtomic(file(), d.toJSONString()); } catch (Throwable t) { System.out.println("[Watchdog] could not save traps: " + t); saveFailed(file().getName(), t); } // batch 19c (design 10)
    }
}
