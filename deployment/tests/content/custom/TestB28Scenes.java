package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.io.File;
import java.util.*;

/**
 * Trial only (-Dtest.b28scenes=true -Dsmartbots.count=0 -Dscenes.showMin=2 -Dscenes.greetCoolMin=5): batch 28, scenes near
 * players (Scenes.java). A stand-in is a test bot in Companions.TEST_HUMANS; four tracks run side by side after a 30 s settle:
 *  G  greet (Lumbridge): G1 one greet names the stand-in (combat 3); G2 the stand-in walks 14 tiles east, the escort ends with
 *     dist10/far at 8-12 tiles and the bot is within 12 tiles of its greet tile 30 s later; G3 away and back, still one greet;
 *     G4 the Lumbridge local greets a second stand-in, no escort.
 *  C  crews: C1 a crew of 3 (home Lumbridge); C2 40 leader picks, 36+ within 120 tiles of home; C3 one banter, still one 100 s on.
 *  S  shows (Varrock, showMin 2): S1 one show; S2 none more in the next 110 s, the hub gate held some back; S3 stand-in 60 tiles
 *     off, slot cleared: no show in 90 s.
 *  R  rescue talk (Draynor): R1 one remark naming witness and rescued; R2 caps hold it at one; R3 no witness, no remark.
 * Then the per-switch checks (OC crews, OR rescueTalk, OG greet + OS show off together for 60 s: no counter moves, no line) and
 * F (data/scenes.off: the 60 s pulse turns everything off). Kill-switch runs (-Dscenes=false, or all four parts false): K = the
 * same scenes for 150 s, every counter 0, no line, nothing on. Soak mode (-Dtest.b28scenes=soak): one stand-in moved between
 * Lumbridge, Varrock and Falador every 10 min, counters logged. Ends with RESULT lines and DONE.
 */
public final class TestB28Scenes implements StartupListener {
    static void log(String s) { System.out.println("[TestB28Scenes] " + s); }
    static Location L(int x, int y) { return Location.create(x, y, 0); }
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    @Override public void startup() {
        String mode = System.getProperty("test.b28scenes", "");
        if ("soak".equalsIgnoreCase(mode)) { soak(); return; }
        if (!"true".equalsIgnoreCase(mode)) return;
        GameWorld.getPulser().submit(new Run());
    }

    static String counters() {
        return "greets " + Scenes.greets + ", local " + Scenes.localGreets + ", escorts " + Scenes.escorts + "/" + Scenes.escortEnds + ", crewPicks " + Scenes.crewPicks
                + ", fallbacks " + Scenes.crewFallbacks + ", banters " + Scenes.banters + ", shows " + Scenes.shows + " (held " + Scenes.showsHeld + "), rescueTalks "
                + Scenes.rescueTalks + ", lines " + Scenes.lines() + ", Spectacle.shows " + Spectacle.shows + ", Watchdog.rescued " + Watchdog.rescued;
    }

    static final Location COWS = Location.create(3257, 3270, 0); // the Lumbridge cow field (AfkTasks gold plan spot)

    /** Soak: one stand-in, Lumbridge / Varrock / Falador in turn every 10 min; counters every 10 min.
     *  Test-only -Dscenes.standinPark=<plan> (e.g. "gehangout,crew"): the stand-in parks 30 min per plan step instead;
     *  a step may carry its own length in minutes, e.g. "lumbridge:10,ge:25,crew:25" (P36: Lumbridge first, for the greet path).
     *  lumbridge = the Lumbridge town spot (BotLife.Town.LUMBRIDGE, 3222,3218).
     *  gehangout = the Grand Exchange break spot (SmartAdventurers.GRAND_EXCHANGE, where Exchange/hangout breaks and shows happen);
     *  crew = the nearest crew leader's job spot once it is working there (re-checked each minute, moves at most every 5 min),
     *  the Lumbridge cow field while no crew is at work. The last step holds to the end. */
    static void soak() {
        String plan = System.getProperty("scenes.standinPark", "").trim();
        final String[] raw = plan.isEmpty() ? new String[0] : plan.toLowerCase(Locale.ROOT).split("\\s*,\\s*");
        final String[] steps = new String[raw.length]; final int[] ends = new int[raw.length]; // ends[i] = last minute of step i
        for (int i = 0, end = 0; i < raw.length; i++) {
            int c = raw[i].indexOf(':'), len = 30;
            if (c > 0) { try { len = Math.max(1, Integer.parseInt(raw[i].substring(c + 1).trim())); } catch (NumberFormatException ignored) { } }
            steps[i] = c > 0 ? raw[i].substring(0, c).trim() : raw[i]; end += len; ends[i] = end;
        }
        GameWorld.getPulser().submit(new Pulse(100) { // 60 s
            AIPlayer si; int min, step = -1, movedMin = -99; Location parkedAt; String parkedWhy = "";
            final Location[] at = {BotLife.Town.LUMBRIDGE.spot.transform(6, 2, 0), BotLife.Town.VARROCK.spot.transform(2, 3, 0), BotLife.Town.FALADOR.spot.transform(2, 2, 0)};
            @Override public boolean pulse() {
                try {
                    if (si == null) {
                        si = TestCR1.bot(at[0], 10, 1, 1, 1); Companions.TEST_HUMANS.add(key(si));
                        log("soak stand-in " + si.getUsername() + " at Lumbridge" + (steps.length > 0 ? ", park plan " + String.join(",", raw) + " (30 min a step unless given)" : ""));
                        return false;
                    }
                    min++;
                    if (steps.length > 0) {
                        park();
                        if (min % 10 == 0) log("soak t=" + min + " min, stand-in parked at " + parkedAt + " (" + parkedWhy + "); " + counters());
                        return false;
                    }
                    if (min % 10 == 0) { Location to = at[(min / 10) % at.length]; si.getProperties().setTeleportLocation(to); log("soak t=" + min + " min, stand-in to " + to + "; " + counters()); }
                } catch (Throwable t) { log("soak: " + t); }
                return false;
            }

            void park() {
                int s = 0; while (s < steps.length - 1 && min > ends[s]) s++;
                if (s != step) { step = s; parkedAt = null; movedMin = -99; log("soak park step " + steps[s] + " at t=" + min + " min"); }
                String st = steps[s];
                if (st.equals("crew")) {
                    Object[] c = workingCrew(si.getLocation());
                    if (c != null) {
                        Location to = (Location) c[0];
                        if ((parkedAt == null || parkedAt.getDistance(to) > 20) && min - movedMin >= 5) move(to, "crew " + c[1] + " at work");
                    } else if (parkedAt == null) move(COWS, "Lumbridge cows, no crew at work yet");
                } else if (st.startsWith("lumb")) {
                    if (parkedAt == null) move(BotLife.Town.LUMBRIDGE.spot, "Lumbridge town spot");
                } else if (parkedAt == null) {
                    if (!st.startsWith("ge") && !st.startsWith("hangout")) log("soak park: unknown step " + st + ", using the Grand Exchange");
                    move(SmartAdventurers.GRAND_EXCHANGE, "Grand Exchange break spot");
                }
            }

            void move(Location to, String why) {
                si.getProperties().setTeleportLocation(to); parkedAt = to; parkedWhy = why; movedMin = min;
                log("soak park t=" + min + " min, stand-in to " + to + " (" + why + ")");
            }
        });
    }

    /** The working crew leader (runner in WORK state, on the surface) nearest to l: {its location, crew name}, or null. */
    static Object[] workingCrew(Location l) {
        Object[] best = null; double bd = Double.MAX_VALUE;
        for (SmartAdventurers.Crew c : SmartAdventurers.Crew.CREWS) {
            SmartAdventurers.Brain b = c.leader;
            if (b == null || b.bot == null || !b.bot.isActive() || c.members.size() < 2) continue;
            AfkTasks.Runner r = b.runner;
            if (r == null || r.state != AfkTasks.State.WORK) continue;
            Location at = b.bot.getLocation();
            if (at == null || at.getZ() != 0 || at.getY() > 6400) continue;
            double d = at.getDistance(l);
            if (d < bd) { bd = d; best = new Object[]{Location.create(at.getX(), at.getY(), 0), c.name()}; }
        }
        return best;
    }

    static final class Run extends Pulse {
        Run() { super(1); }
        long t0, settle; int fails, ticks; boolean done, kmode, began;
        final List<AIPlayer> made = new ArrayList<>();
        final List<String> humans = new ArrayList<>();
        final List<SmartAdventurers.Brain> thinkers = new ArrayList<>(), registered = new ArrayList<>();
        SmartAdventurers.Crew crew;
        // greet
        int aPhase, aSteps; long aT; AIPlayer n1, n2, n3; SmartAdventurers.Brain g1, g2; Location aFrom; int aEsc0, aLoc0;
        // shows
        int bPhase, bShows0; long bT; AIPlayer v; SmartAdventurers.Brain s1, s2, s3; int bHeld0;
        // crews
        int cPhase; long cT; SmartAdventurers.Brain cb1, cb2, cb3; final List<Location> cPicks = new ArrayList<>(); int cGuard;
        // rescue
        int rPhase; long rT; AIPlayer x, w, y; Location rSpot;
        // off checks + file
        int oPhase; long oT; int[] snap;
        static final Location STUCK = L(3110, 3262), NEAR_W = L(3112, 3266);

        void mark(String id, boolean pass, String what) { log("RESULT " + id + " " + what + " -> " + (pass ? "PASS" : "FAIL")); if (!pass) fails++; }

        AIPlayer bot(Location at, boolean newbie) { AIPlayer b = newbie ? TestCR1.bot(at, 10, 1, 1, 1) : TestCR1.bot(at, 30, 20, 20, 20); made.add(b); return b; }
        AIPlayer standin(Location at, boolean newbie) { AIPlayer b = bot(at, newbie); Companions.TEST_HUMANS.add(key(b)); humans.add(key(b)); return b; }

        SmartAdventurers.Brain brain(AIPlayer b, Location at, boolean register, boolean think) {
            SmartAdventurers.Brain br = new SmartAdventurers.Brain();
            br.bot = b; br.scriptAPI = new core.game.bots.ScriptAPI(b); br.nextThink = 0; br.loiterAt = at; br.loiterName = "the test spot";
            br.idleUntil = System.currentTimeMillis() + 30 * 60_000L; br.nextExtra = Long.MAX_VALUE; br.home = 0;
            if (register) { SmartAdventurers.BRAINS.put(key(b), br); registered.add(br); }
            if (think) thinkers.add(br);
            return br;
        }

        void tp(Player p, Location to) { p.getProperties().setTeleportLocation(to); }
        boolean allPartsOff() { for (boolean b : Scenes.PARTS) if (b) return false; return true; }

        @Override public boolean pulse() {
            if (done) return true;
            long now = System.currentTimeMillis();
            if (t0 == 0) t0 = now;
            ticks++;
            try {
                if (now - t0 > 860_000) { log("RESULT TIMEOUT phases A" + aPhase + " B" + bPhase + " C" + cPhase + " R" + rPhase + " O" + oPhase + "; " + counters()); log("RESULT UNITS " + (fails + 1) + " FAIL"); endTest(); return true; }
                if (now - t0 < 30_000) return false; // the world settles
                if (!began) { began = true; settle = ticks; kmode = !Scenes.FLAG || allPartsOff(); setup(now); return false; }
                if (ticks - settle >= 6) for (SmartAdventurers.Brain br : thinkers) { // the test's bots think like adventurers on a break
                    br.idleUntil = Math.max(br.idleUntil, now + 10 * 60_000L);
                    try { br.think(); } catch (Throwable t) { log("think " + br.bot.getUsername() + ": " + t); br.nextThink = now + 10_000; }
                }
                if (ticks - settle < 8) return false;
                if (kmode) return kTick(now);
                aTick(now); bTick(now); cTick(now); rTick(now);
                if (aPhase >= 99 && bPhase >= 99 && cPhase >= 99 && rPhase >= 99) return oTick(now);
                return false;
            } catch (Throwable e) { log("RESULT exception: " + e); e.printStackTrace(); log("RESULT UNITS " + (fails + 1) + " FAIL"); endTest(); return true; }
        }

        void setup(long now) {
            File off = Scenes.offFile();
            if (off.exists() && !kmode) log("WARNING " + off + " present at the start");
            log("setup: scenes " + Scenes.FLAG + ", parts " + Arrays.toString(Scenes.PARTS) + ", stopped " + Scenes.stopped + ", hubs from " + Scenes.SceneHubs.get().getClass().getSimpleName() + (kmode ? " -> kill-switch run" : ""));
            if (!kmode) { Scenes.Greet.testNoLocal = true; Scenes.Show.HUB_NEXT.put("Lumbridge", Long.MAX_VALUE); } // the local and Lumbridge shows stay out of G1-G3
            n1 = standin(L(3230, 3220), true); aFrom = L(3230, 3220);
            g1 = brain(bot(L(3226, 3218), false), L(3226, 3218), false, true);
            g2 = brain(bot(L(3227, 3222), false), L(3227, 3222), false, true);
            v = standin(L(3212, 3431), false);
            s1 = brain(bot(L(3210, 3428), false), L(3210, 3428), true, true);
            s2 = brain(bot(L(3214, 3428), false), L(3214, 3428), true, true);
            s3 = brain(bot(L(3212, 3425), false), L(3212, 3425), true, true);
            Scenes.Show.HUB_NEXT.remove("Varrock");
            cb1 = brain(bot(L(3215, 3230), false), null, false, false);
            cb2 = brain(bot(L(3216, 3231), false), null, false, false);
            cb3 = brain(bot(L(3217, 3229), false), null, false, false);
            x = bot(L(3110, 3262), false); w = bot(L(3114, 3264), false); y = bot(L(3140, 3300), false);
            aT = bT = cT = rT = now;
        }

        void makeCrew() {
            crew = new SmartAdventurers.Crew(0); crew.leader = cb1;
            for (SmartAdventurers.Brain b : new SmartAdventurers.Brain[]{cb1, cb2, cb3}) { crew.members.add(b); b.crew = crew; }
            SmartAdventurers.Crew.CREWS.add(crew);
        }

        void resetShowTimers() { for (SmartAdventurers.Brain b : new SmartAdventurers.Brain[]{s1, s2, s3}) b.nextSpectacle = 0; Spectacle.AREA_BUSY.clear(); }
        void resetRescueCaps() { Scenes.RescueTalk.NEXT_TALK.clear(); Scenes.RescueTalk.NEXT_ABOUT.clear(); Scenes.RescueTalk.GLOBAL_NEXT = 0; }

        // ------------------------------------------------ G: greet + escort
        void aTick(long now) {
            switch (aPhase) {
                case 0: // G1: one greet, naming the stand-in
                    if (Scenes.greets >= 1) {
                        mark("G1", Scenes.greets == 1 && Scenes.Greet.lastGreet != null && Scenes.Greet.lastGreet.contains("-> " + n1.getUsername() + " "), "greets " + Scenes.greets + ", line: " + Scenes.Greet.lastGreet);
                        aPhase = 1; aT = now; aSteps = 0; return;
                    }
                    if (now - aT > 90_000) { mark("G1", false, "no greet in 90 s; " + counters()); aPhase = 4; aT = now; }
                    return;
                case 1: // G2: the stand-in walks east 2 tiles every 2 ticks, 7 steps
                    if (Scenes.escortEnds >= 1) {
                        String why = Scenes.Greet.lastWhy; int tiles = Scenes.Greet.lastTiles;
                        mark("G2", Scenes.escorts == 1 && ("dist10".equals(why) || "far".equals(why)) && tiles >= 8 && tiles <= 12, "escorts " + Scenes.escorts + ", end " + why + " tiles=" + tiles + " (want dist10/far, 8-12)");
                        aPhase = 2; aT = now; return;
                    }
                    if (now - aT > 3_000 && ticks % 2 == 0 && aSteps < 7) { aSteps++; tp(n1, aFrom.transform(2 * aSteps, 0, 0)); }
                    if (now - aT > 60_000) { mark("G2", false, "no escort end in 60 s, escorts " + Scenes.escorts); aPhase = 4; aT = now; }
                    return;
                case 2: // G2b: back near the greet tile 30 s after the end
                    if (now - aT < 30_000) return;
                    Player eb = Scenes.Greet.lastBot; Location ga = Scenes.Greet.lastAt;
                    double d = eb == null || ga == null ? -1 : eb.getLocation().getDistance(ga);
                    mark("G2B", d >= 0 && d <= 12, "escort bot " + (eb == null ? "-" : eb.getUsername()) + " " + String.format(Locale.ROOT, "%.1f", d) + " tiles from its greet tile 30 s after (want <= 12)");
                    aPhase = 3; aT = now; tp(n1, L(3222, 3265)); return;
                case 3: // G3: away and back; still one greet a minute later
                    if (aSteps >= 0 && now - aT > 10_000) { tp(n1, aFrom); aSteps = -1; }
                    if (now - aT < 70_000) return;
                    mark("G3", Scenes.greets == 1, "greets after away-and-back " + Scenes.greets + " (want 1)");
                    aPhase = 4; aT = now; return;
                case 4: // G4: the town local greets a second stand-in, no escort
                    Companions.TEST_HUMANS.remove(key(n1)); tp(n1, L(3222, 3265));
                    Scenes.Greet.NEXT.put(key(g1.bot), Long.MAX_VALUE); Scenes.Greet.NEXT.put(key(g2.bot), Long.MAX_VALUE);
                    Scenes.Greet.testNoLocal = false;
                    n2 = standin(BotLife.Town.LUMBRIDGE.spot, true);
                    aEsc0 = Scenes.escorts; aLoc0 = Scenes.localGreets; aPhase = 5; aT = now; return;
                case 5:
                    if (Scenes.localGreets > aLoc0) {
                        mark("G4", Scenes.localGreets == aLoc0 + 1 && Scenes.escorts == aEsc0 && Scenes.Greet.lastGreet.startsWith("greet-local "), "local greets " + (Scenes.localGreets - aLoc0) + ", escorts " + aEsc0 + " -> " + Scenes.escorts + ", line: " + Scenes.Greet.lastGreet);
                        aPhase = 99; Companions.TEST_HUMANS.remove(key(n2)); return;
                    }
                    if (now - aT > 150_000) {
                        Player near = null; double nd = 99;
                        for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && p.isArtificial() && !made.contains(p) && p.getLocation().getDistance(BotLife.Town.LUMBRIDGE.spot) < nd) { nd = p.getLocation().getDistance(BotLife.Town.LUMBRIDGE.spot); near = p; }
                        mark("G4", false, "no local greet in 150 s; nearest other bot to the Lumbridge spot " + (near == null ? "none" : near.getUsername() + " at " + (int) nd));
                        aPhase = 99; Companions.TEST_HUMANS.remove(key(n2));
                    }
                    return;
                default:
            }
        }

        // ------------------------------------------------ S: one show per hub per window
        void bTick(long now) {
            switch (bPhase) {
                case 0: // S1
                    if (Scenes.shows > bShows0) {
                        mark("S1", Scenes.shows == bShows0 + 1 && Scenes.Show.HUB_NEXT.containsKey("Varrock"), "shows " + Scenes.shows + " (" + Scenes.lastLine + ") after " + (now - bT) / 1000 + " s");
                        bPhase = 1; bT = now; bHeld0 = Scenes.Show.HELD.getOrDefault("Varrock", 0); resetShowTimers(); return;
                    }
                    if (now - bT > 150_000) { mark("S1", false, "no show at Varrock in 150 s; " + counters()); bPhase = 2; }
                    return;
                case 1: // S2: no second show inside the window, the hub gate held some back
                    if (now - bT < 110_000) return;
                    int held = Scenes.Show.HELD.getOrDefault("Varrock", 0) - bHeld0;
                    mark("S2", Scenes.shows == bShows0 + 1 && held > 0, "shows " + Scenes.shows + " in the window (want " + (bShows0 + 1) + "), held at Varrock " + held);
                    bPhase = 2; return;
                case 2: // S3: no watcher, no show
                    tp(v, L(3212, 3371)); Scenes.Show.HUB_NEXT.remove("Varrock"); resetShowTimers();
                    bShows0 = Scenes.shows; bT = now; bPhase = 3; return;
                case 3:
                    if (now - bT < 90_000) return;
                    mark("S3", Scenes.shows == bShows0, "stand-in 60 tiles off, slot open: shows " + bShows0 + " -> " + Scenes.shows + " in 90 s (want no change)");
                    bPhase = 99; return;
                default:
            }
        }

        // ------------------------------------------------ C: crew near home, banter
        void cTick(long now) {
            switch (cPhase) {
                case 0:
                    makeCrew();
                    mark("C1", SmartAdventurers.Crew.CREWS.size() == 1 && crew.members.size() == 3 && Scenes.Crews.filter(cb1), "crews " + SmartAdventurers.Crew.CREWS.size() + ", members " + crew.members.size() + ", filter on the leader " + Scenes.Crews.filter(cb1));
                    cPhase = 1; return;
                case 1: // C2: 40 picks, 20 a tick
                    if (!picks(40)) return;
                    int near = 0; for (Location l : cPicks) if (Scenes.dist(Scenes.Crews.surface(l), BotLife.Town.LUMBRIDGE.spot) <= 120) near++;
                    mark("C2", cPicks.size() == 40 && near >= 36, "leader picks within 120 of Lumbridge " + near + "/" + cPicks.size() + ", crewPicks " + Scenes.crewPicks + ", fallbacks " + Scenes.crewFallbacks);
                    cPhase = 2; return;
                case 2: { // C3: one banter now
                    boolean said = Scenes.Crews.banter(cb1, now);
                    log("C3 first banter " + said + ", banters " + Scenes.banters + ", line: " + Scenes.lastLine);
                    cT = now; cPhase = 3; return;
                }
                case 3: // ... and no more for 100 s
                    for (SmartAdventurers.Brain b : new SmartAdventurers.Brain[]{cb1, cb2, cb3}) Scenes.Crews.banter(b, now);
                    if (now - cT < 100_000) return;
                    mark("C3", Scenes.banters == 1 && Scenes.LINES[Scenes.CREWS] >= 1, "banters " + Scenes.banters + " after 100 s of calls (want 1), crew lines " + Scenes.LINES[Scenes.CREWS]);
                    cPhase = 99; return;
                default:
            }
        }

        /** n leader picks, 20 a tick, into cPicks (cleared first); true when done. */
        boolean picks(int n) {
            SmartAdventurers.BRAINS.put(key(cb1.bot), cb1); cb1.shiftEnd = 0; // shift over: QuestBook offers no quest, so every pick is a job
            try { for (int i = 0; i < 20 && cPicks.size() < n; i++) {
                cb1.lastJob = null; cb1.questPick = null;
                SmartAdventurers.Job j = cb1.chooseJob();
                if (j == null) { if (++cGuard > 2000) cPicks.add(L(0, 0)); continue; } // a quest won the pick; L(0,0) ends a stuck loop
                Location l = spotOf(j); if (l != null) cPicks.add(l);
            } } finally { SmartAdventurers.BRAINS.remove(key(cb1.bot)); }
            return cPicks.size() >= n;
        }

        Location spotOf(SmartAdventurers.Job j) {
            if (j.hunt != null) return j.hunt.spot;
            AfkTasks.Spot[] sp = AfkTasks.SPOTS.get(j.task);
            Location best = null; double bd = Double.MAX_VALUE;
            if (sp != null) for (AfkTasks.Spot s : sp) { double d = s.at.getDistance(BotLife.Town.LUMBRIDGE.spot); if (d < bd) { bd = d; best = s.at; } }
            return best;
        }

        // ------------------------------------------------ R: rescue talk
        void rTick(long now) {
            switch (rPhase) {
                case 0: { // R1
                    Watchdog.rescue(x, STUCK, 5);
                    String t = Scenes.RescueTalk.lastTalk;
                    mark("R1", Scenes.rescueTalks == 1 && t != null && t.contains(w.getUsername() + " about " + x.getUsername()), "rescueTalks " + Scenes.rescueTalks + ", line: " + t);
                    rT = now; rPhase = 1; return;
                }
                case 1: // R2: 30 s on, X again and then Y beside W
                    if (now - rT < 30_000) return;
                    tp(y, NEAR_W); rT = now; rPhase = 2; return;
                case 2:
                    if (now - rT < 3_000) return;
                    Watchdog.rescue(x, STUCK, 5);
                    Watchdog.rescue(y, NEAR_W, 5);
                    mark("R2", Scenes.rescueTalks == 1, "after X again and Y beside W: rescueTalks " + Scenes.rescueTalks + " (want 1)");
                    rPhase = 3; return;
                case 3: { // R3: a spot with nobody near it or its bank
                    resetRescueCaps();
                    Location[] tries = {L(3170, 3300), L(3260, 3330), L(2990, 3300), L(3140, 3170), L(3300, 3300), L(2940, 3250), L(3190, 3370)};
                    rSpot = null;
                    for (Location s : tries) {
                        Location bank = Watchdog.nearestBank(s, y);
                        if (Scenes.RescueTalk.witness(y, s, 13, now) == null && (bank == null || Scenes.RescueTalk.witness(y, bank, 9, now) == null)) { rSpot = s; break; }
                    }
                    if (rSpot == null) { mark("R3", false, "no empty spot among " + tries.length); rPhase = 99; return; }
                    tp(y, rSpot); rT = now; rPhase = 4; return;
                }
                case 4:
                    if (now - rT < 3_000) return;
                    int t0r = Scenes.rescueTalks;
                    Watchdog.rescue(y, rSpot, 5);
                    mark("R3", Scenes.rescueTalks == t0r, "no witness at " + rSpot + ": rescueTalks " + t0r + " -> " + Scenes.rescueTalks + " (want no change)");
                    rPhase = 99; return;
                default:
            }
        }

        // ------------------------------------------------ per-switch checks, then the off file
        boolean oTick(long now) {
            switch (oPhase) {
                case 0: {
                    // crews off
                    Scenes.PARTS[Scenes.CREWS] = false; Scenes.Crews.NEXT_BANTER.clear();
                    int b0 = Scenes.banters, l0 = Scenes.LINES[Scenes.CREWS], p0 = Scenes.crewPicks;
                    boolean said = Scenes.Crews.banter(cb1, now), filt = Scenes.Crews.filter(cb1);
                    cPicks.clear(); cGuard = 0; while (!picks(10)) { }
                    int far = 0; for (Location l : cPicks) if (Scenes.dist(Scenes.Crews.surface(l), BotLife.Town.LUMBRIDGE.spot) > 120) far++;
                    mark("OC", !said && !filt && Scenes.banters == b0 && Scenes.LINES[Scenes.CREWS] == l0 && Scenes.crewPicks == p0, "-Dscenes.crews=false: banter " + said + ", filter " + filt + ", banters " + b0 + " -> " + Scenes.banters + ", crewPicks " + p0 + " -> " + Scenes.crewPicks + " (picks beyond 120: " + far + "/10, info)");
                    Scenes.PARTS[Scenes.CREWS] = true;
                    // rescue talk off
                    Scenes.PARTS[Scenes.RESCUE] = false; resetRescueCaps();
                    int t0r = Scenes.rescueTalks, rl = Scenes.LINES[Scenes.RESCUE], rs = Watchdog.rescued;
                    Watchdog.rescue(x, STUCK, 5);
                    mark("OR", Scenes.rescueTalks == t0r && Scenes.LINES[Scenes.RESCUE] == rl && Watchdog.rescued == rs + 1, "-Dscenes.rescueTalk=false: rescueTalks " + t0r + " -> " + Scenes.rescueTalks + ", Watchdog.rescued " + rs + " -> " + Watchdog.rescued + " (want +1)");
                    Scenes.PARTS[Scenes.RESCUE] = true;
                    // greet and show off together for 60 s
                    Scenes.PARTS[Scenes.GREET] = false; Scenes.PARTS[Scenes.SHOW] = false;
                    n3 = standin(L(3226, 3220), true);
                    Scenes.Greet.NEXT.clear(); synchronized (Scenes.Greet.GREETED) { Scenes.Greet.GREETED.clear(); } Scenes.Greet.GLOBAL_NEXT = 0; Scenes.Greet.testNoLocal = false;
                    tp(v, L(3212, 3431)); Scenes.Show.HUB_NEXT.clear(); resetShowTimers();
                    snap = new int[]{Scenes.greets, Scenes.localGreets, Scenes.escorts, Scenes.LINES[Scenes.GREET], Scenes.shows, Scenes.showsHeld, Spectacle.shows};
                    oT = now; oPhase = 1; return false;
                }
                case 1:
                    if (now - oT < 60_000) return false;
                    mark("OG", Scenes.greets == snap[0] && Scenes.localGreets == snap[1] && Scenes.escorts == snap[2] && Scenes.LINES[Scenes.GREET] == snap[3], "-Dscenes.greet=false 60 s, a fresh stand-in by two idle bots: greets " + snap[0] + " -> " + Scenes.greets + ", local " + snap[1] + " -> " + Scenes.localGreets + ", greet lines " + snap[3] + " -> " + Scenes.LINES[Scenes.GREET]);
                    mark("OS", Scenes.shows == snap[4] && Scenes.showsHeld == snap[5], "-Dscenes.show=false 60 s: Scenes.shows " + snap[4] + " -> " + Scenes.shows + ", held " + snap[5] + " -> " + Scenes.showsHeld + " (Spectacle.shows " + snap[6] + " -> " + Spectacle.shows + ", info)");
                    for (int i = 0; i < Scenes.PARTS.length; i++) Scenes.PARTS[i] = true;
                    Companions.TEST_HUMANS.remove(key(n3)); tp(n3, L(3222, 3265)); // no greet starting just before the file check
                    try { new File(Scenes.offFile().getPath()).createNewFile(); } catch (Throwable t) { log("could not write " + Scenes.offFile() + ": " + t); }
                    oT = now; oPhase = 2; return false;
                case 2: // F: the pulse sees data/scenes.off within a minute
                    if (!Scenes.stopped && now - oT < 100_000) return false;
                    boolean anyOn = false; for (int i = 0; i < 4; i++) anyOn |= Scenes.on(i);
                    mark("F", Scenes.stopped && !anyOn && Scenes.stopWhy != null && Scenes.stopWhy.contains("scenes.off"), Scenes.offFile().getPath() + ": stopped " + Scenes.stopped + " (" + Scenes.stopWhy + ") after " + (now - oT) / 1000 + " s, any part on " + anyOn);
                    log("counters " + counters());
                    log("RESULT UNITS " + (fails == 0 ? "PASS" : fails + " FAIL"));
                    endTest(); return true;
                default: return true;
            }
        }

        // ------------------------------------------------ K: kill switch (-Dscenes=false, or every part false)
        boolean kTick(long now) {
            if (cPhase == 0) { makeCrew(); cPhase = 1; }
            Scenes.Crews.banter(cb1, now); Scenes.Crews.banter(cb2, now);
            if (rPhase == 0 && now - rT > 10_000) {
                Watchdog.rescue(x, STUCK, 5);
                cPicks.clear(); cGuard = 0; while (!picks(10)) { }
                rPhase = 1;
            }
            if (now - aT < 150_000) return false;
            boolean anyOn = false; for (int i = 0; i < 4; i++) anyOn |= Scenes.on(i);
            int sum = Scenes.greets + Scenes.localGreets + Scenes.escorts + Scenes.escortEnds + Scenes.crewPicks + Scenes.crewFallbacks + Scenes.banters + Scenes.shows + Scenes.showsHeld + Scenes.rescueTalks;
            boolean k = !anyOn && sum == 0 && Scenes.lines() == 0 && Scenes.Greet.ESCORTS.isEmpty() && Scenes.Show.HUB_NEXT.isEmpty() && nothingKept();
            mark("K", k, (Scenes.FLAG ? "every part false" : "-Dscenes=false") + " 150 s with a stand-in, idle bots, a crew and a rescue: " + counters() + ", any part on " + anyOn);
            log("RESULT UNITS " + (fails == 0 ? "PASS" : fails + " FAIL"));
            endTest(); return true;
        }

        boolean nothingKept() { return Scenes.Crews.NEXT_BANTER.isEmpty() && Scenes.RescueTalk.NEXT_ABOUT.isEmpty() && Scenes.Greet.GREETED.isEmpty(); }

        void endTest() {
            done = true;
            try { Scenes.Greet.testNoLocal = false; } catch (Throwable ignored) { }
            try { File f = Scenes.offFile(); if (f.exists() && f.delete()) log("removed " + f); } catch (Throwable ignored) { }
            for (String h : humans) Companions.TEST_HUMANS.remove(h);
            for (SmartAdventurers.Brain b : registered) SmartAdventurers.BRAINS.remove(key(b.bot));
            if (crew != null) SmartAdventurers.Crew.CREWS.remove(crew);
            for (AIPlayer b : made) try { b.clear(); } catch (Throwable ignored) { }
            log("DONE");
        }
    }
}
