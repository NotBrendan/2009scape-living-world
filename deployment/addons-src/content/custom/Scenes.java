package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * batch 28: scenes near players (roadmap 2026-11 ideas 2, 3, 9, 12; design: the design notes (not published)).
 *  Greet       a bot on a break near Lumbridge greets a player under combat 10, points at the chickens or the bank and walks
 *              beside them a few steps (the town local greets too, no escort). Once per player per 6 h, per bot per 10 min,
 *              one greet a minute server wide, one escort per hub at a time.
 *  Crews       a crew leader picks jobs within 120 tiles of the crew's home town (nearest job as a fallback, logged); crew
 *              mates at the same spot talk now and then, only with a player within 20 tiles.
 *  Show        one spectacle per hub per 30 min (on top of the 15-min per-bot and 90-s per-area timers); a due hub rolls
 *              1 in 2 instead of 1 in 6 so the show comes soon after the slot opens.
 *  RescueTalk  a bot near a rescued bot's stuck spot (or its landing) remarks on the teleport, once.
 * Money: none. No coin, item or xp moves here; every hook is one guarded line in try/catch, so the host path runs as before
 * when a part is off. Switches: -Dscenes=false (all), -Dscenes.greet / .crews / .show / .rescueTalk=false (one part),
 * data/scenes.off (checked at startup and every 60 s; off until restart). Trials: -Dscenes.showMin, -Dscenes.greetCoolMin,
 * -Dscenes.standin=<bot name> (that bot counts as a player, Companions.TEST_HUMANS), -Dscenes.ticks (the off-file pulse).
 */
public final class Scenes implements StartupListener {
    static final boolean FLAG = !"false".equalsIgnoreCase(System.getProperty("scenes", "true"));
    static final int GREET = 0, CREWS = 1, SHOW = 2, RESCUE = 3;
    static final String[] PART = {"greet", "crews", "show", "rescueTalk"};
    /** Each part's own switch, read once at startup; trials flip one at a time for the per-switch checks. */
    static final boolean[] PARTS = {part(PART[0]), part(PART[1]), part(PART[2]), part(PART[3])};
    static final String OFF_FILE = "scenes.off";
    static final int TICKS = Math.max(10, Integer.getInteger("scenes.ticks", 100)); // 100 ticks = 60 s
    static volatile boolean stopped;
    // counters (trials and soaks; like Spectacle.shows)
    static volatile int greets, localGreets, escorts, escortEnds, crewPicks, crewFallbacks, banters, shows, showsHeld, rescueTalks;
    static final int[] LINES = new int[4]; // lines said, per part (the off checks: a part that is off says nothing)
    static volatile String lastLine, stopWhy;
    static final Random RNG = new Random();

    static boolean part(String k) { return !"false".equalsIgnoreCase(System.getProperty("scenes." + k, "true")); }
    static boolean on(int part) { return FLAG && !stopped && PARTS[part]; }
    static void log(String s) { lastLine = s; System.out.println("[Scenes] " + s); }
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }
    static String pick(String[] a) { return a[RNG.nextInt(a.length)]; }
    static void say(int part, Player bot, String s) { LINES[part]++; BotChat.say(bot, s); }
    static int lines() { return LINES[0] + LINES[1] + LINES[2] + LINES[3]; }
    static File offFile() { return new File(ServerConstants.DATA_PATH, OFF_FILE); }
    /** Straight-line tiles on one plane; MAX_VALUE across planes or for a missing spot. */
    static double dist(Location a, Location b) { return a == null || b == null || a.getZ() != b.getZ() ? Double.MAX_VALUE : a.getDistance(b); }

    /** A player (or a trial stand-in) within range of a spot, same plane. */
    static boolean seen(Location at, int range) {
        if (at == null) return false;
        for (Player h : Repository.getPlayers()) if (h != null && h.isActive() && Companions.human(h) && dist(h.getLocation(), at) <= range) return true;
        return false;
    }

    @Override public void startup() {
        if (!FLAG) { log("off (-Dscenes=false)"); return; }
        String si = System.getProperty("scenes.standin");
        if (si != null && !si.trim().isEmpty()) { Companions.TEST_HUMANS.add(si.trim().toLowerCase(Locale.ROOT)); log("stand-in " + si.trim() + " counts as a player (trials and soaks only)"); }
        if (offFile().exists()) { stop(OFF_FILE + " present"); return; }
        if (Greet.STORE.enabled) { // P41 D4: -Dscenes.greetPersist; off = no file read or written
            LinkedHashMap<String, Long> m = Greet.STORE.load(System.currentTimeMillis());
            synchronized (Greet.GREETED) { for (Map.Entry<String, Long> e : m.entrySet()) Greet.GREETED.putIfAbsent(e.getKey(), e.getValue()); }
            log("greet store: " + Greet.STORE.loaded + " loaded, " + Greet.STORE.expired + " expired, file " + Greet.STORE.file.getPath());
        }
        GameWorld.getPulser().submit(new Pulse(TICKS) {
            @Override public boolean pulse() {
                if (Scenes.stopped) return true; // qualified: Pulse has its own stop()
                try { if (Scenes.offFile().exists()) { Scenes.stop(OFF_FILE + " present"); return true; } } catch (Throwable t) { Scenes.log("pulse: " + t); }
                return false;
            }
        });
        log("on: greet " + PARTS[GREET] + ", crews " + PARTS[CREWS] + ", show " + PARTS[SHOW] + " (one a hub every " + Show.minutes() + " min), rescueTalk " + PARTS[RESCUE] + (RescueTalk.EAR ? " (rescueEar on)" : ""));
    }

    static void stop(String why) { stopped = true; stopWhy = why; log("off: " + why); }

    // ---------------------------------------------------------------- where the hubs are

    /** The hubs the scenes use: the BotLife towns (TownSpots). Picked per call. */
    interface SceneHubs {
        final class Spot {
            final String name; final Location at; final int radius;
            Spot(String name, Location at, int radius) { this.name = name; this.at = at; this.radius = radius; }
            /** Artificial players within the radius now (cached 10 s). */
            int bots() {
                long now = System.currentTimeMillis();
                long[] c = BOTS.get(name);
                if (c != null && c[0] > now) return (int) c[1];
                int n = 0;
                for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && p.isArtificial() && dist(p.getLocation(), at) <= radius) n++;
                BOTS.put(name, new long[]{now + 10_000, n});
                return n;
            }
        }
        Map<String, long[]> BOTS = new ConcurrentHashMap<>();
        List<Spot> all(); // never null; may be empty
        /** Nearest by straight line on the same plane; null when there are none. */
        default Spot nearest(Location l) {
            Spot best = null; double bd = Double.MAX_VALUE;
            if (l != null) for (Spot s : all()) { double d = dist(s.at, l); if (d < bd) { bd = d; best = s; } }
            return best;
        }
        // When Batch 27 ships, its hub census source (HubCensus) plugs in here ahead of TownSpots.
        static SceneHubs get() { return TownSpots.I; }
    }

    static final class TownSpots implements SceneHubs {
        static final TownSpots I = new TownSpots();
        static final List<Spot> SPOTS;
        static { List<Spot> l = new ArrayList<>(); for (BotLife.Town t : BotLife.Town.values()) l.add(new Spot(t.name, t.spot, 20)); l.add(new Spot("Grand Exchange", SmartAdventurers.GRAND_EXCHANGE, 20)); /* P36: the GE break spot is a show hub */ SPOTS = Collections.unmodifiableList(l); }
        @Override public List<Spot> all() { return SPOTS; }
    }

    // ---------------------------------------------------------------- idea 2: newcomer greeting + escort

    static final String[] GREET_LINES = {"Hello there. New here?", "Welcome to Lumbridge.", "First day? Don't mind the goblins.",
            "Nice to see a new face round here.", "You look new. Need the way to anything?", "Lumbridge is a good place to start."};
    static final String[] POINT_CHICKENS = {"Chickens are east, through the gate past the castle. Easy kills and feathers.",
            "Head east over the bridge for the chickens. Bring a bit of food.", "The chicken pen is east, by the farmhouse.",
            "Chickens east, cows north of them. Both are good at your level."};
    static final String[] POINT_BANK = {"The bank is at the top of the castle, up the stairs.", "Bank's in the castle, top floor. Two flights up.",
            "Need a bank? Castle, up the stairs, all the way.", "Castle stairs, top floor, that's the bank."};
    static final String[] BYE = {"This is me. Good luck.", "Right, I'll leave you to it.", "That's your way. See you around.",
            "Off you go. Mind the goblins over the river.", "I'm heading back. Take care.", "You've got it from here."};

    static final class Greet {
        static final String HUB = "Lumbridge";
        static final int RANGE = 10, LOCAL_RANGE = 6, NEAR_HUB = 40, ESCORT_MS = 45_000;
        static final Map<String, Long> NEXT = new ConcurrentHashMap<>();        // bot -> next greet (10 min)
        static final Map<String, Long> LOCAL_CHECK = new ConcurrentHashMap<>(); // town local -> next look (3 s; its tick runs every tick)
        static final LinkedHashMap<String, Long> GREETED = new LinkedHashMap<>(); // player -> greeted until (6 h); synchronized on itself; memory only unless -Dscenes.greetPersist
        static final GreetStore STORE = new GreetStore(new File(ServerConstants.DATA_PATH, "scenes-greeted.txt"), GreetStore.flag()); // P41 D4, default off
        static final Map<String, Escort> ESCORTS = new ConcurrentHashMap<>();   // bot -> its escort (one per bot, one per hub)
        static volatile long GLOBAL_NEXT;
        static volatile boolean testNoLocal;                                    // trials: the local stays out of the adventurer cases
        static volatile String lastWhy, lastGreet; static volatile int lastTiles = -1; static volatile Player lastBot; static volatile Location lastAt; // trials
        // P57 -Dscenes.greetGuard (default OFF; phase3-design.md section 7): busy lists, inCombat, death/rescue/logout resets, no long walk-back, no names in logs
        static final boolean GUARD = "true".equalsIgnoreCase(System.getProperty("scenes.greetGuard", "false"));
        static final long SALT = new java.security.SecureRandom().nextLong(); // per boot: player# tags are stable within a boot only
        static volatile String lastWalkback; static volatile int drops, walkbackSkips; // trials

        /** P57: true while this bot walks a newcomer (guard on only; off = always false, today's behaviour). */
        static boolean escorting(Player bot) { return GUARD && bot != null && ESCORTS.containsKey(key(bot)); }

        /** P57: end an escort now (death, logout, rescue): no goodbye line, no walk-back. No-op with the guard off. */
        static void drop(Player bot, String why) {
            if (!GUARD || bot == null) return;
            if (ESCORTS.remove(key(bot)) == null) return;
            escortEnds++; drops++; lastWhy = why; lastBot = bot;
            log("escort " + bot.getUsername() + " end " + why + " dropped");
        }

        /** P57: a log tag for a human (or stand-in) that never names them: player# + 6 hex of SHA-1(per-boot salt + key). */
        static String redact(Player p) { return p == null ? "player#null" : redact(key(p)); }
        static String redact(String k) {
            try {
                byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest((SALT + ":" + k).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return String.format("player#%02x%02x%02x", h[0], h[1], h[2]);
            } catch (Throwable t) { return "player#" + Integer.toHexString((Long.hashCode(SALT) ^ k.hashCode()) & 0xffffff); }
        }

        /** P57: guard reasons checked ahead of the old ones (null = none). */
        static String guardWhy(SmartAdventurers.Brain br) {
            if (!GUARD) return null;
            Player b = br.bot;
            if (b.getAttribute("state:death", false)) return "death";
            if (b.inCombat()) return "combat";
            if (br.companion != null || QuestRunner.active(b) || Deals.involved(b) || PlayerTrades.SESSIONS.containsKey(b)) return "busy";
            return null;
        }

        /** P57: no walk-back after far/z/death/busy or from over 20 tiles out (a rescue teleport). */
        static boolean skipWalkBack(String why, Location at, Location greetAt) {
            if (!GUARD) return false;
            return "far".equals(why) || "z".equals(why) || "death".equals(why) || "busy".equals(why) || at == null || greetAt == null || dist(at, greetAt) > 20;
        }

        static final class Escort {
            final Player player; final Location from, greetAt; final long since; final String hub;
            Escort(Player player, Location from, Location greetAt, long since, String hub) { this.player = player; this.from = from; this.greetAt = greetAt; this.since = since; this.hub = hub; }
        }

        static long coolMs() { return Math.max(1, Integer.getInteger("scenes.greetCoolMin", 360)) * 60_000L; }
        static long greetedUntil(String k) { synchronized (GREETED) { return GREETED.getOrDefault(k, 0L); } }

        /** From think's break branch (one line). True while this bot greets or walks someone (think does nothing else then). */
        static boolean tick(SmartAdventurers.Brain br, long now) {
            if (br == null || br.bot == null) return false;
            if (ESCORTS.containsKey(key(br.bot))) return true; // the escort pulse has the bot
            if (!on(GREET)) return false;
            Player p = newcomer(br.bot, now, RANGE);
            if (p == null) return false;
            boolean walk = true;
            for (Escort e : ESCORTS.values()) if (HUB.equals(e.hub)) walk = false; // one escort per hub at a time
            greet(br.bot, p, now, !walk);
            if (walk) escort(br, p, now);
            return true;
        }

        /** From BotLife.TownFolk.tick (one line): the town local greets too, no escort (open question 1). */
        static boolean tickLocal(Player bot, long now, List<String> greeted) {
            if (bot == null || testNoLocal || !on(GREET)) return false;
            String bk = key(bot);
            if (LOCAL_CHECK.getOrDefault(bk, 0L) > now) return false;
            LOCAL_CHECK.put(bk, now + 3_000);
            Player p = newcomer(bot, now, LOCAL_RANGE);
            if (p == null) return false;
            greet(bot, p, now, true);
            if (greeted != null && !greeted.contains(p.getUsername())) { greeted.add(p.getUsername()); if (greeted.size() > 20) greeted.remove(0); } // its own hello stays quiet
            return true;
        }

        /** The nearest player (or stand-in) under combat 10 within range of the bot, near Lumbridge, not greeted lately. */
        static Player newcomer(Player bot, long now, int range) {
            if (GLOBAL_NEXT > now || NEXT.getOrDefault(key(bot), 0L) > now) return null;
            Location bl = bot.getLocation();
            if (bl == null || bl.getZ() != 0) return null;
            if (GUARD && bot.inCombat()) return null; // P57 A3
            SceneHubs hubs = SceneHubs.get();
            SceneHubs.Spot near = hubs.nearest(bl);
            if (near == null || !HUB.equals(near.name) || dist(bl, near.at) > NEAR_HUB + range) return null; // cheap: most bots are nowhere near
            Player best = null; double bd = range + 0.5;
            for (Player p : Repository.getPlayers()) {
                if (p == null || p == bot || !p.isActive() || !Companions.human(p)) continue;
                double d = dist(p.getLocation(), bl);
                if (d > bd || p.getProperties().getCurrentCombatLevel() >= 10) continue;
                SceneHubs.Spot at = hubs.nearest(p.getLocation());
                if (at == null || !HUB.equals(at.name) || dist(p.getLocation(), at.at) > NEAR_HUB) continue;
                if (greetedUntil(key(p)) > now) continue;
                if (GUARD && p.inCombat()) continue; // P57 A3: no hello to a player mid-fight
                best = p; bd = d;
            }
            return best;
        }

        static void greet(Player bot, Player p, long now, boolean local) {
            String pk = key(p);
            LinkedHashMap<String, Long> snap = null;
            synchronized (GREETED) {
                GREETED.remove(pk); GREETED.put(pk, now + coolMs());
                while (GREETED.size() > 500) { Iterator<String> it = GREETED.keySet().iterator(); it.next(); it.remove(); } // oldest first
                if (STORE.enabled) snap = new LinkedHashMap<>(GREETED);
            }
            if (snap != null) STORE.save(snap); // P41 D4: outside the lock; never throws
            NEXT.put(key(bot), now + 10 * 60_000L);
            GLOBAL_NEXT = now + 60_000L; // set before the first line: two bots in one tick can't both greet
            if (local) localGreets++; else greets++;
            log(lastGreet = (local ? "greet-local " : "greet ") + bot.getUsername() + " -> " + (GUARD ? redact(p) : p.getUsername()) + " at " + HUB + " d=" + (int) Math.round(dist(bot.getLocation(), p.getLocation())));
            try { bot.face(p); } catch (Throwable ignored) { }
            Spectacle.anim(bot, 863); // the wave
            say(GREET, bot, pick(GREET_LINES));
            final String point = pointLine(p.getLocation());
            final boolean letGo = local;
            GameWorld.getPulser().submit(new Pulse(3) {
                @Override public boolean pulse() {
                    try { if (bot.isActive() && Scenes.on(GREET)) Scenes.say(GREET, bot, point); if (letGo) bot.face(null); } catch (Throwable ignored) { }
                    return true;
                }
            });
        }

        /** Chickens or the bank, whichever is nearer the player. */
        static String pointLine(Location at) {
            Location chick = null;
            try { AfkTasks.Spot[] sp = AfkTasks.SPOTS.get(AfkTasks.Task.CHICKEN); if (sp != null && sp.length > 0) chick = sp[0].at; } catch (Throwable ignored) { }
            double dc = chick == null ? Double.MAX_VALUE : dist(chick, at), db = dist(AfkTasks.LUMBRIDGE_UPSTAIRS_BANK, at);
            return pick(dc <= db ? POINT_CHICKENS : POINT_BANK);
        }

        static void escort(SmartAdventurers.Brain br, Player p, long now) {
            final Escort e = new Escort(p, p.getLocation(), br.bot.getLocation(), now, HUB);
            ESCORTS.put(key(br.bot), e);
            escorts++;
            br.idleUntil = Math.max(br.idleUntil, now + ESCORT_MS + 5_000); // the break covers the walk
            GameWorld.getPulser().submit(new Pulse(2) {
                @Override public boolean pulse() {
                    try {
                        long t = System.currentTimeMillis();
                        Location pl = p.getLocation(), bl = br.bot.getLocation();
                        double moved = dist(pl, e.from), gap = dist(pl, bl);
                        if (GUARD && !ESCORTS.containsKey(key(br.bot))) return true; // P57: dropped (death/rescue/logout) - the pulse just stops
                        String gw = Greet.guardWhy(br);
                        String why = gw != null ? gw : !Scenes.on(GREET) ? "off" : !br.bot.isActive() ? "bot" : !p.isActive() ? "gone" : br.runner != null ? "job"
                                : pl.getZ() != bl.getZ() || pl.getZ() != e.from.getZ() ? "z" : moved >= 10 ? "dist10" : gap > 10 ? "far" : t - e.since > ESCORT_MS ? "timeout" : null;
                        if (why == null) { if (gap > 2) { br.bot.face(p); br.scriptAPI.randomWalkTo(pl, 1); } return false; }
                        Greet.end(br, e, why, moved, t);
                    } catch (Throwable x) { ESCORTS.remove(key(br.bot)); Scenes.log("escort " + br.bot.getUsername() + " error " + x); }
                    return true;
                }
            });
        }

        static void end(SmartAdventurers.Brain br, Escort e, String why, double moved, long t) {
            ESCORTS.remove(key(br.bot));
            escortEnds++;
            int tiles = moved > 10_000 ? -1 : (int) Math.round(moved), secs = (int) ((t - e.since) / 1000);
            lastWhy = why; lastTiles = tiles; lastBot = br.bot; lastAt = e.greetAt;
            log("escort " + br.bot.getUsername() + " end " + why + " tiles=" + tiles + " secs=" + secs);
            if (!br.bot.isActive()) return;
            if (!"off".equals(why) && !(GUARD && "death".equals(why))) say(GREET, br.bot, pick(BYE));
            try { br.bot.face(null); } catch (Throwable ignored) { }
            if (br.runner == null && skipWalkBack(why, br.bot.getLocation(), e.greetAt)) { // P57 A5
                walkbackSkips++; lastWalkback = why;
                log("walkback skipped " + why);
                br.idleUntil += t - e.since;
            } else if (br.runner == null) {
                try { br.scriptAPI.randomWalkTo(e.greetAt, 2); } catch (Throwable ignored) { } // back toward where it stood (not awaited)
                br.idleUntil += t - e.since; // the break is not shortened by the walk
            }
        }
    }

    // ---------------------------------------------------------------- idea 3: crews hunt near home

    static final String[] CALL = {"Spread out a bit, %s, you're on my toes.", "Mine's nearly down. Take the next one.", "Keep the bones, I've got enough.",
            "Who's got food left?", "Same again after this, or back to town?", "Watch your left, %s.", "This lot are slower than the last.",
            "Nearly full. One more and we go."};
    static final String[] ANSWER = {"Got it.", "I've a few. Shout if you need one.", "Back to town. I'm nearly full.", "On it.", "Fine by me.", "One more then."};

    static final class Crews {
        static final int HOME_RANGE = 120, MATE_RANGE = 8;
        static final Map<SmartAdventurers.Crew, Long> NEXT_BANTER = Collections.synchronizedMap(new WeakHashMap<>());

        /** chooseJob's switch: only a crew leader with at least one mate and a home town. */
        static boolean filter(SmartAdventurers.Brain b) {
            SmartAdventurers.Crew c = b == null ? null : b.crew;
            return on(CREWS) && c != null && c.leader == b && c.members.size() >= 2 && c.home >= 0;
        }

        static BotLife.Town town(SmartAdventurers.Crew c) { BotLife.Town[] t = BotLife.Town.values(); return t[Math.floorMod(c.home, t.length)]; }
        static Location surface(Location l) { return l.getY() > 6400 ? Location.create(l.getX(), l.getY() - 6400, 0) : l; }

        /** The job has a spot (its hunt's, or any of its task's) within 120 tiles of the crew's home town. */
        static boolean nearHome(SmartAdventurers.Crew c, SmartAdventurers.Job j) {
            try {
                Location h = town(c).spot;
                if (j.hunt != null) return surface(j.hunt.spot).getDistance(h) <= HOME_RANGE;
                AfkTasks.Spot[] sp = AfkTasks.SPOTS.get(j.task);
                if (sp != null) for (AfkTasks.Spot s : sp) if (surface(s.at).getDistance(h) <= HOME_RANGE) return true;
                return false;
            } catch (Throwable t) { return true; } // never block a pick on an error
        }

        /** The filtered pick found nothing: count it, log it, and chooseJob runs once more without the filter. */
        static void fellBack(SmartAdventurers.Brain b) {
            crewFallbacks++;
            try { log("crew " + b.crew.name() + " fallback: no job within " + HOME_RANGE + " of " + town(b.crew).name); } catch (Throwable ignored) { }
        }

        /** The filtered pick found a job near home: count it and log it, one line a pick (lets a rescue be tied to a pick). */
        static void picked(SmartAdventurers.Brain b, SmartAdventurers.Job j) {
            crewPicks++;
            try { log("crew " + b.crew.name() + " pick " + b.bot.getUsername() + " -> " + j.label()); } catch (Throwable ignored) { }
        }

        /** From think's runner branch (one line, every think): a crew mate within 8 tiles and a player in sight; 120-180 s a crew. */
        static boolean banter(SmartAdventurers.Brain b, long now) {
            if (!on(CREWS) || b == null || b.bot == null || b.crew == null) return false;
            try {
                SmartAdventurers.Crew c = b.crew;
                if (c.members.size() < 2 || NEXT_BANTER.getOrDefault(c, 0L) > now) return false;
                Location at = b.bot.getLocation();
                List<Player> mates = new ArrayList<>();
                for (SmartAdventurers.Brain m : c.members) if (m != b && m.bot != null && m.bot.isActive() && dist(m.bot.getLocation(), at) <= MATE_RANGE) mates.add(m.bot);
                if (mates.isEmpty() || !seen(at, Spectacle.SEE)) return false; // nobody to hear it (open question 4)
                Long cool = BotChat.BOT_COOLDOWN.get(key(b.bot));
                if (cool != null && cool > now) return false; // just talked to someone
                NEXT_BANTER.put(c, now + 120_000 + RNG.nextInt(60_001));
                banters++;
                Player mate = mates.get(RNG.nextInt(mates.size()));
                log("banter " + c.name() + ": " + b.bot.getUsername() + " (" + (mates.size() + 1) + " together)");
                say(CREWS, b.bot, String.format(pick(CALL), mate.getUsername()));
                if (RNG.nextBoolean()) { LINES[CREWS]++; BotChat.speak(mate, b.bot, Collections.singletonList(pick(ANSWER)), 3); }
                return true;
            } catch (Throwable t) { return false; }
        }
    }

    // ---------------------------------------------------------------- idea 9: one show per hub per 30 min

    static final class Show {
        static final Map<String, Long> HUB_NEXT = new ConcurrentHashMap<>(); // hub name -> next show allowed
        static final Map<String, Integer> HELD = new ConcurrentHashMap<>();  // hub name -> shows held back (trials and soaks)

        static int minutes() { return Math.max(1, Integer.getInteger("scenes.showMin", 30)); }

        static SceneHubs.Spot hubOf(Location l) {
            SceneHubs.Spot s = SceneHubs.get().nearest(l);
            return s != null && dist(l, s.at) <= s.radius + 20 ? s : null;
        }

        /** Spectacle's gate: -1 = this hub's slot is not due (held), 2 = due at a hub (roll 1 in 2), 0 = not at a hub or off (the old rule). */
        static int roll(Location l, long now) {
            if (!on(SHOW)) return 0;
            try {
                SceneHubs.Spot s = hubOf(l);
                if (s == null) return 0;
                if (HUB_NEXT.getOrDefault(s.name, 0L) > now) { showsHeld++; HELD.merge(s.name, 1, Integer::sum); return -1; }
                return 2;
            } catch (Throwable t) { return 0; }
        }

        /** Spectacle's mark, once the pair is fixed: the hub's next slot opens in 30 min. */
        static void started(Location l, long now) {
            if (!on(SHOW)) return;
            try {
                SceneHubs.Spot s = hubOf(l);
                if (s == null) return;
                HUB_NEXT.put(s.name, now + minutes() * 60_000L);
                shows++;
                log("show at " + s.name + " next in " + minutes() + " min");
            } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- idea 12: rescue comment

    static final String[] STUCK = {"Was that %s? Gone in a puff of smoke.", "%s got stuck again. Happens to the best of us.", "There goes %s. Teleport tax.",
            "Someone should fix that path. Poor %s.", "Lost %s to a fence. Again.", "Saw that, %s. Not judging."};
    static final String[] LANDING = {"Where did you spring from, %s?", "%s, you look like you walked into a wall for ten minutes.",
            "Bad path, %s? There's a lot of those.", "Welcome back, %s. Next time take the road.", "Teleported in, did you, %s? Fair enough.",
            "You're white as a sheet, %s."};
    // P66 -Dscenes.rescueEar (C1): in-world lines only - nothing about getting stuck, paths, walls or teleports
    static final String[] STUCK_EAR = {"Was that %s? Gone in a puff of smoke.", "Where's %s off to in such a hurry?", "Saw that, %s. Not judging.",
            "%s must have remembered something at the bank.", "Off you go then, %s.", "Bye, %s. Mind how you go."};
    static final String[] LANDING_EAR = {"Where'd you come from, %s?", "Afternoon, %s.", "Welcome back, %s.", "Busy day, %s?",
            "You're white as a sheet, %s.", "There you are, %s. Thought we'd lost you."};

    static final class RescueTalk {
        static final Map<String, Long> NEXT_TALK = new ConcurrentHashMap<>(), NEXT_ABOUT = new ConcurrentHashMap<>(); // witness 10 min, rescued bot 30 min
        static volatile long GLOBAL_NEXT; // 2 min server wide
        static volatile String lastTalk; // trials
        // P66 -Dscenes.rescueEar (default OFF; phase3-design.md section 3): C1 in-world lines, C2 busy witness skipped, C3 a human hears at most 2 remarks per 30 min
        static final boolean EAR = "true".equalsIgnoreCase(System.getProperty("scenes.rescueEar", "false"));
        static final int HEAR_RANGE = 20, HEAR_CAP = 2; static final long HEAR_WINDOW = 30 * 60_000L;
        static final Map<String, long[]> HEARD = new ConcurrentHashMap<>(); // human key -> times of the last HEAR_CAP remarks heard (memory only, never logged)
        static volatile int heldBusy, heldCap, heldHuman; static volatile long nextHeldLog; static volatile String lastHeld; // trials + status

        /** From Watchdog.rescue after its log line (one line): at most one remark per rescue, from a bot that saw it. */
        static void saw(Player rescued, Location stuckAt, Location landedAt, long now) {
            if (!on(RESCUE) || rescued == null || !rescued.isArtificial()) return; // a real player is never the subject
            if (EAR && Companions.human(rescued)) { held("human", now); return; } // P66: a stand-in counts as a human: never the subject
            if (GLOBAL_NEXT > now || NEXT_ABOUT.getOrDefault(key(rescued), 0L) > now) return;
            try {
                Player atStuck = witness(rescued, stuckAt, 12, now), atLand = witness(rescued, landedAt, 8, now);
                boolean hs = atStuck != null && seen(stuckAt, 20), hl = atLand != null && seen(landedAt, 20);
                Player w; boolean landing;
                if (hs || (!hl && atStuck != null)) { w = atStuck; landing = false; } // the scene someone sees; else the stuck spot
                else if (hl) { w = atLand; landing = true; }
                else return;
                int heard = 0, most = 0;
                if (EAR) {
                    java.util.List<String> ears = hearers(w, now);
                    if (ears == null) { held("cap", now); return; }
                    for (String k : ears) note(k, now);
                    heard = ears.size(); for (String k : ears) most = Math.max(most, count(k, now));
                }
                NEXT_TALK.put(key(w), now + 10 * 60_000L); NEXT_ABOUT.put(key(rescued), now + 30 * 60_000L); GLOBAL_NEXT = now + 2 * 60_000L;
                rescueTalks++;
                log(lastTalk = "rescue-talk " + w.getUsername() + " about " + rescued.getUsername() + " at " + (landing ? "landing" : "stuck") + (EAR ? " heard=" + heard + " most=" + most : ""));
                LINES[RESCUE]++;
                String[] set = EAR ? (landing ? LANDING_EAR : STUCK_EAR) : (landing ? LANDING : STUCK);
                BotChat.speak(w, null, Collections.singletonList(String.format(pick(set), rescued.getUsername())), 4); // after the rescued bot's own line
            } catch (Throwable ignored) { }
        }

        /** The nearest other bot (not a stand-in) within range of the spot, off its own cooldown (and, with the ear on, not busy). */
        static Player witness(Player rescued, Location at, int range, long now) {
            if (at == null) return null;
            Player best = null; double bd = range + 0.5; boolean sawBusy = false;
            for (Player p : Repository.getPlayers()) {
                if (p == null || p == rescued || !p.isActive() || !p.isArtificial() || Companions.human(p)) continue;
                double d = dist(p.getLocation(), at);
                if (d > bd || NEXT_TALK.getOrDefault(key(p), 0L) > now) continue;
                if (EAR && busy(p, now)) { sawBusy = true; continue; } // P66 C2
                best = p; bd = d;
            }
            if (best == null && sawBusy) held("busy", now);
            return best;
        }

        /** P66 C2: a bot that is dead, fighting, escorting, on an errand, questing, dealing or with a companion says nothing. Same clauses as the busy lists. */
        static boolean busy(Player p, long now) {
            if (p == null) return true;
            try {
                if (p.getAttribute("state:death", false) || p.inCombat()) return true;
                if (Scenes.Greet.escorting(p) || QuestRunner.active(p) || Deals.involved(p) || PlayerTrades.SESSIONS.containsKey(p) || AfkTasks.RUNNING.containsKey(key(p))) return true;
                SmartAdventurers.Brain m = SmartAdventurers.BRAINS.get(key(p));
                if (m == null) return false;
                return m.runner != null || m.errandNoStar() || m.starUntil > now || m.companion != null || m.errandFor != null;
            } catch (Throwable t) { return true; }
        }

        /** P66 C3: keys of the humans within HEAR_RANGE of the speaker, or null when any of them already heard HEAR_CAP remarks in the window. */
        static java.util.List<String> hearers(Player speaker, long now) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Player p : Repository.getPlayers()) {
                if (p == null || !p.isActive() || !Companions.human(p) || dist(p.getLocation(), speaker.getLocation()) > HEAR_RANGE) continue;
                String k = key(p);
                if (count(k, now) >= HEAR_CAP) return null;
                out.add(k);
            }
            return out;
        }

        static int count(String k, long now) {
            long[] t = HEARD.get(k); if (t == null) return 0;
            int n = 0; synchronized (t) { for (long x : t) if (x > 0 && now - x < HEAR_WINDOW) n++; }
            return n;
        }

        static void note(String k, long now) {
            if (now >= nextPrune) { nextPrune = now + 60_000L; prune(now); } // P69 #15
            long[] t = HEARD.computeIfAbsent(k, x -> new long[HEAR_CAP]);
            synchronized (t) { int oldest = 0; for (int i = 1; i < t.length; i++) if (t[i] < t[oldest]) oldest = i; t[oldest] = now; }
        }

        /** P66: logout of a human (or a stand-in) forgets what it heard. */
        static void forget(Player p) { if (p != null) try { HEARD.remove(key(p)); } catch (Throwable ignored) { } }

        /**
         * P69 #15: forget() runs only for a stand-in's logOut, never for a real human's, so HEARD grew by one entry per human ever
         * near a remark. Entries whose every time is outside HEAR_WINDOW count 0 anyway (count()), so dropping them changes nothing
         * but the memory. Run from note() at most once a minute; returns how many went.
         */
        static volatile long nextPrune;
        static int prune(long now) {
            int n = 0;
            for (Map.Entry<String, long[]> e : HEARD.entrySet()) {
                long[] t = e.getValue(); boolean live = false;
                synchronized (t) { for (long x : t) if (x > 0 && now - x < HEAR_WINDOW) { live = true; break; } }
                if (!live && HEARD.remove(e.getKey(), t)) n++;
            }
            return n;
        }

        /** P66: count a held remark; at most one log line a minute so a held stream never floods the log. Never names a human. */
        static void held(String why, long now) {
            if ("busy".equals(why)) heldBusy++; else if ("cap".equals(why)) heldCap++; else heldHuman++;
            lastHeld = why;
            if (now < nextHeldLog) return;
            nextHeldLog = now + 60_000L;
            log("rescue-ear held " + why + " (busy " + heldBusy + ", cap " + heldCap + ", human " + heldHuman + ")");
        }
    }
}
