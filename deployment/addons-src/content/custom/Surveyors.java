package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.bots.GeneralBotCreator;
import core.game.bots.Script;
import core.game.bots.SkillingBotAssembler;
import core.game.node.entity.skill.Skills;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pathing surveyors (2026-10-20): a few bots whose only job is to walk the map in zig-zags, back and forth,
 * and record what the walker actually does: every leg (from, to, result, ticks, the tiles it passed through), where it
 * got stuck and the clipping flags around that spot, and a running tile census. Two kinds: low level (Agility 1,
 * combat 3) and high level (Agility 75+, combat 90), so legs that only the high ones complete point at agility
 * shortcuts the pathfinder takes for granted. Data lands in data/survey/ for the better pathfinding to come:
 *   legs.jsonl   one JSON object per leg: {t, bot, agility, from, to, result, ticks, path, flags?}
 *   tiles.json   "x,y,z" -> visits (saved every 10 minutes)
 * Flags: -Dsurvey.count (default 0 since batch 18: retired, the code path kept; half low, half high), -Dsurvey.step (tiles between waypoints, default 48).
 */
public final class Surveyors implements StartupListener {
    static final Random RNG = new Random();
    static final Timer TIMER = new Timer("Surveyors", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");
    static final int COUNT = Integer.getInteger("survey.count", 0); // batch 18 item1: surface route-map surveying retired (was 14); -Dsurvey.count=N brings them back; tiles.json still loads and saves below (Surveyor.walkable, the census)
    /** Surveyors that walk with the owner's real levels (read from his save at spawn), so the data shows what HE can use. */
    static final int CLONES = Integer.getInteger("survey.clones", 0); // batch 18 item1: the clones walk the same blind zig-zag (the owner 02:38: no blind surface wandering): off by default too (was 8), -Dsurvey.clones=N
    static final String CLONE_OF = System.getProperty("survey.cloneOf", "");
    static final int STEP = Integer.getInteger("survey.step", 48);
    static final Map<String, Integer> TILES = new ConcurrentHashMap<>();
    static final List<String> PENDING = new ArrayList<>(); // leg records waiting to be appended
    static int legs, blocked;

    /** The surface the surveyors cover: the free-to-play and members' mainland, never the Wilderness. */
    static final int X0 = 2300, X1 = 3520, Y0 = 2900, Y1 = 3515;
    /** The low-level pair stays in the home kingdoms. */
    static final int LX0 = 2900, LX1 = 3400, LY0 = 3100, LY1 = 3515;

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        new File(ServerConstants.DATA_PATH, "survey").mkdirs();
        try { // the tile census carries on across restarts (it used to start from zero every boot)
            File tf = new File(new File(ServerConstants.DATA_PATH, "survey"), "tiles.json");
            if (tf.exists()) { JSONObject j = (JSONObject) new org.json.simple.parser.JSONParser().parse(new java.io.FileReader(tf)); for (Object k : j.keySet()) TILES.put(String.valueOf(k), ((Number) j.get(k)).intValue()); }
            System.out.println("[Survey] " + TILES.size() + " tiles remembered");
        } catch (Throwable t) { System.out.println("[Survey] could not read tiles: " + t); Ledger.quarantine(new File(new File(ServerConstants.DATA_PATH, "survey"), "tiles.json"), "Survey", t, false, true); } // audit 10-23 batch14 item1
        for (int i = 0; i < COUNT; i++) {
            final boolean high = i % 2 == 1;
            TIMER.schedule(new TimerTask() {
                @Override
                public void run() {
                    GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
                        @Override
                        public boolean pulse() { try { spawn(high); } catch (Throwable t) { System.out.println("[Survey] spawn failed: " + t); try { Failures.Life.loginFailed("surveyor", null, t); } catch (Throwable ignored) { } } return true; }
                    });
                }
            }, FAST ? 30_000 + i * 3_000L : 200_000 + i * 20_000L);
        }
        for (int i = 0; i < CLONES; i++) {
            TIMER.schedule(new TimerTask() {
                @Override
                public void run() {
                    GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
                        @Override
                        public boolean pulse() { try { spawnClone(); } catch (Throwable t) { System.out.println("[Survey] clone spawn failed: " + t); } return true; }
                    });
                }
            }, FAST ? 45_000 + i * 3_000L : 280_000 + i * 20_000L);
        }
        TIMER.schedule(new TimerTask() { @Override public void run() { flush(); } }, 600_000, 600_000);
        TIMER.schedule(new TimerTask() { @Override public void run() { rotateLegs(new File(ServerConstants.DATA_PATH, "survey")); } }, 0); // batch 17 item2: at boot too, off the game thread
        System.out.println("[Survey] " + COUNT + " pathing surveyors scheduled (" + (COUNT + 1) / 2 + " low level, " + COUNT / 2 + " high level), " + CLONES + " clone surveyors (survey.count / survey.clones; batch 18 item1: default 0, retired), " + TILES.size() + " census tiles loaded, data in data/survey/");
    }

    /** A surveyor with the owner's static levels (all 24 skills) copied from data/players/<name>.json. */
    static void spawnClone() {
        if (CLONE_OF.isEmpty()) { System.out.println("[Survey] no clone surveyors: set -Dsurvey.cloneOf=<account> to copy a player's levels"); return; }
        int[] levels = new int[24];
        try {
            JSONObject d = (JSONObject) new org.json.simple.parser.JSONParser().parse(new java.io.FileReader(new File(ServerConstants.PLAYER_SAVE_PATH, CLONE_OF + ".json")));
            JSONArray skills = (JSONArray) d.get("skills");
            for (Object o : skills) {
                JSONObject sk = (JSONObject) o;
                int id = Integer.parseInt(String.valueOf(sk.get("id"))), st = Integer.parseInt(String.valueOf(sk.get("static")));
                if (id >= 0 && id < 24) levels[id] = st;
            }
        } catch (Throwable t) { System.out.println("[Survey] could not read " + CLONE_OF + "'s levels: " + t); return; }
        AIPlayer bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(3222, 3218, 0));
        for (int i = 0; i < 24; i++) if (levels[i] > 0) { bot.getSkills().setStaticLevel(i, levels[i]); bot.getSkills().setLevel(i, levels[i]); }
        bot.getSkills().updateCombatLevel();
        Surveyor s = new Surveyor(true);
        s.clone = true;
        new GeneralBotCreator(s, bot);
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(4) {
            @Override public boolean pulse() { if (!bot.isActive()) return true; try { s.tick(); } catch (Throwable t) { System.out.println("[Survey] " + bot.getUsername() + ": " + t); } return false; }
        });
        BotChat.activity(bot, () -> "surveying the roads for the map");
        System.out.println("[Survey] " + bot.getUsername() + " set out as a clone surveyor with " + CLONE_OF + "'s levels (agility " + levels[Skills.AGILITY] + ")");
    }

    static void spawn(boolean high) {
        Location start = high ? Location.create(2965, 3380, 0) : Location.create(3222, 3218, 0); // Falador / Lumbridge
        AIPlayer bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, start);
        int agility = high ? 75 + RNG.nextInt(20) : 1;
        bot.getSkills().setStaticLevel(Skills.AGILITY, agility); bot.getSkills().setLevel(Skills.AGILITY, agility);
        if (high) for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, 90); bot.getSkills().setLevel(sk, 90); }
        bot.getSkills().updateCombatLevel();
        SmartAdventurers.finishTutorial(bot);
        Surveyor s = new Surveyor(high);
        new GeneralBotCreator(s, bot);
        // Own heartbeat: the server only ticks a script while the bot has no pulse running, and a walking bot always has one.
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(4) {
            @Override public boolean pulse() { if (!bot.isActive()) return true; try { s.tick(); } catch (Throwable t) { System.out.println("[Survey] " + bot.getUsername() + ": " + t); } return false; }
        });
        BotChat.activity(bot, () -> "surveying the roads for the map" + (high ? " (the hard way round)" : ""));
        System.out.println("[Survey] " + bot.getUsername() + " set out as a " + (high ? "high" : "low") + "-level surveyor (agility " + agility + ")");
    }

    static final class Surveyor extends Script {
        final boolean high;
        boolean clone;
        final List<Location> route = new ArrayList<>();
        int at = -1;
        Location legFrom, target, lastPos;
        long legStart, nextThink, stillSince;
        final List<int[]> path = new ArrayList<>();
        int failedHops;
        Location hopFrom;

        Surveyor(boolean high) { this.high = high; }

        @Override public Script newInstance() { return null; }

        /** A zig-zag over the area: rows STEP apart, alternating direction, so every stretch is walked both ways over time. */
        void plan() {
            route.clear();
            int x0 = high ? X0 : LX0, x1 = high ? X1 : LX1, y0 = high ? Y0 : LY0, y1 = high ? Y1 : LY1;
            int offset = RNG.nextInt(STEP); // a different lattice each pass
            boolean right = RNG.nextBoolean();
            for (int y = y0 + offset; y < y1; y += STEP) {
                if (right) for (int x = x0 + offset; x < x1; x += STEP) route.add(Location.create(x, y, 0));
                else for (int x = x1 - offset; x > x0; x -= STEP) route.add(Location.create(x, y, 0));
                right = !right;
            }
            if (RNG.nextBoolean()) java.util.Collections.reverse(route);
            // Pick up where the survey left off: waypoints in 32x32 cells nobody has walked yet go first, well-walked
            // cells last (the census in tiles.json survives restarts). Order within each group stays a zig-zag.
            java.util.Map<String, Integer> cells = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, Integer> e : TILES.entrySet()) {
                String[] xy = e.getKey().split(",");
                try { cells.merge((Integer.parseInt(xy[0]) >> 5) + "," + (Integer.parseInt(xy[1]) >> 5), e.getValue(), Integer::sum); } catch (Throwable ignored) { }
            }
            List<Location> fresh = new ArrayList<>(), seen = new ArrayList<>();
            for (Location l : route) (cells.getOrDefault((l.getX() >> 5) + "," + (l.getY() >> 5), 0) < 20 ? fresh : seen).add(l);
            route.clear(); route.addAll(fresh); route.addAll(seen);
            at = -1;
        }

        /** The nearest walkable tile within 16 of a lattice point, or null (sea, cliffs, unmapped). Flag -1 = no map
         *  data; 0x200000 = blocked (water, rock); 0x100 = a wall object in the tile. */
        static Location walkable(Location p) {
            for (int r = 0; r <= 16; r += 2)
                for (int dx = -r; dx <= r; dx += 2) for (int dy = -r; dy <= r; dy += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int f;
                    try { f = RegionManager.getClippingFlag(p.getZ(), p.getX() + dx, p.getY() + dy); } catch (Throwable t) { continue; }
                    if (f != -1 && (f & 0x200000) == 0 && (f & 0x100) == 0) return Location.create(p.getX() + dx, p.getY() + dy, p.getZ()); // on the caller's floor (review 10-22)
                }
            return null;
        }

        void next() {
            Location pick = null;
            for (int tries = 0; tries < 30 && pick == null; tries++) {
                if (route.isEmpty() || at + 1 >= route.size()) plan();
                pick = walkable(route.get(++at)); // skip lattice points out at sea or inside mountains
            }
            if (pick == null) { nextThink = System.currentTimeMillis() + 10_000; return; }
            target = pick;
            legFrom = bot.getLocation();
            legStart = System.currentTimeMillis();
            path.clear();
            stillSince = 0;
            lastPos = bot.getLocation();
            hop();
        }

        /** The pathfinder only looks ~50 tiles ahead (max_pathfind_dist), so a leg is walked in hops of 30 toward the waypoint. */
        void hop() {
            Location here = bot.getLocation();
            double d = here.getDistance(target);
            // Hop length shrinks after a failed hop (30, 18, 10), and the hop point itself is snapped onto walkable ground.
            int len = failedHops == 0 ? 30 : failedHops == 1 ? 18 : 10;
            Location raw = d <= len ? target : Location.create(here.getX() + (int) Math.round((target.getX() - here.getX()) * len / d), here.getY() + (int) Math.round((target.getY() - here.getY()) * len / d), 0);
            Location step = raw == target ? target : walkable(raw);
            if (step == null) step = raw;
            try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable t) { try { scriptAPI.walkTo(step); } catch (Throwable ignored) { } }
        }

        @Override
        public void tick() {
            if (bot == null || !bot.isActive()) return;
            long now = System.currentTimeMillis();
            if (now < nextThink) return;
            nextThink = now + 2_400; // every 4 ticks
            Location here = bot.getLocation();
            TILES.merge(here.getX() + "," + here.getY() + "," + here.getZ(), 1, Integer::sum);
            try { Resources.scan(bot); } catch (Throwable ignored) { } // where to mine, fish, chop, fight (2026-10-21)
            if (target == null) { next(); return; }
            path.add(new int[]{here.getX(), here.getY()});
            double dist = here.getDistance(target);
            if (dist <= 2) { record("ok", null); next(); return; }
            boolean moved = lastPos == null || !lastPos.equals(here);
            if (moved) stillSince = 0; else if (stillSince == 0) stillSince = now;
            lastPos = here;
            if (!bot.getWalkingQueue().isMoving() && (stillSince == 0 || now - stillSince <= 12_000)) { // finished a hop (or hasn't started): next hop
                if (hopFrom != null && hopFrom.equals(here)) failedHops = Math.min(2, failedHops + 1); else failedHops = 0;
                hopFrom = here;
                hop(); return;
            }
            if (!bot.getWalkingQueue().isMoving() && stillSince > 0 && now - stillSince > 12_000) {
                // Standing still for 12 s short of the waypoint: the walker gave up or something is in the way. Note the clipping around here.
                record(dist > 60 ? "blocked" : "short", flagsAround(here));
                blocked++;
                // Skip ahead the way a player would: teleport to the waypoint and carry on (the leg itself is what we wanted to learn).
                try { Spectacle.teleport(bot, target); } catch (Throwable ignored) { } // batch 15 item1
                target = null;
                nextThink = now + 3_000;
                return;
            }
            if (now - legStart > 240_000) { record("timeout", flagsAround(here)); try { Spectacle.teleport(bot, target); } catch (Throwable ignored) { } target = null; } // batch 15 item1
        }

        /** Clipping flags in a 7x7 around a spot, as "dx,dy:flag" for non-zero flags. */
        static JSONArray flagsAround(Location c) {
            JSONArray arr = new JSONArray();
            try {
                for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) {
                    int f = RegionManager.getClippingFlag(c.getZ(), c.getX() + dx, c.getY() + dy);
                    if (f != 0) arr.add(dx + "," + dy + ":" + f);
                }
            } catch (Throwable ignored) { }
            return arr;
        }

        @SuppressWarnings("unchecked")
        void record(String result, JSONArray flags) {
            legs++;
            JSONObject j = new JSONObject();
            j.put("t", System.currentTimeMillis()); j.put("bot", bot.getUsername()); j.put("high", high); j.put("kind", clone ? "clone" : high ? "high" : "low"); j.put("agility", bot.getSkills().getStaticLevel(Skills.AGILITY));
            j.put("from", loc(legFrom)); j.put("to", loc(target)); j.put("at", loc(bot.getLocation())); j.put("result", result);
            j.put("ticks", (System.currentTimeMillis() - legStart) / 600);
            JSONArray p = new JSONArray(); for (int[] xy : path) { JSONArray t = new JSONArray(); t.add(xy[0]); t.add(xy[1]); p.add(t); } j.put("path", p);
            if (flags != null) j.put("flags", flags);
            boolean full; synchronized (PENDING) { PENDING.add(j.toJSONString()); full = PENDING.size() >= 20; }
            if (full) TIMER.schedule(new TimerTask() { @Override public void run() { flush(); } }, 0); // audit A-2: on the timer, not the game thread (the 600-750 ms "CRITICALLY long" pulses); still outside the PENDING lock (A-1)
            if (!result.equals("ok") && Boolean.getBoolean("afk.debug")) System.out.println("[Survey] " + bot.getUsername() + " " + result + " on the way from " + legFrom + " to " + target + " (stopped at " + bot.getLocation() + ")");
        }

        static JSONArray loc(Location l) { JSONArray a = new JSONArray(); if (l != null) { a.add(l.getX()); a.add(l.getY()); a.add(l.getZ()); } return a; }
    }

    /** batch 17 item2: archives kept beside legs.jsonl (newest first by name: legs-YYYY-Www.jsonl.gz sorts by week). */
    static final int KEEP_ARCHIVES = 4;

    /** batch 17 item2: the ISO week of a time, as "YYYY-Www" (UTC). */
    static String isoWeek(long millis) {
        java.time.LocalDate d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return String.format(Locale.ROOT, "%d-W%02d", d.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR), d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** batch 17 item2: the archives in dir, oldest first. RouteMap.train reads them too. */
    static File[] legArchives(File dir) { return weeklyArchives(dir, "legs.jsonl"); }

    /** batch 22 O-6: the weekly archives of dir/name (name-YYYY-Www.jsonl.gz for name.jsonl), oldest first. */
    static File[] weeklyArchives(File dir, String name) {
        String base = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
        File[] a = dir.listFiles((d, n) -> n.startsWith(base + "-") && n.endsWith(".jsonl.gz"));
        if (a == null) return new File[0];
        java.util.Arrays.sort(a, java.util.Comparator.comparing(File::getName));
        return a;
    }

    /**
     * batch 17 item2 (legs.jsonl grew ~77 KB an hour, unbounded): when legs.jsonl was last written in an earlier ISO week than
     * now, it is gzipped to legs-YYYY-Www.jsonl.gz (its own week) and a fresh legs.jsonl starts; only the newest 4 archives stay.
     * Runs under the flush lock (the only writer of legs.jsonl), on the Surveyors timer thread. Crash-safe: the file is first
     * renamed to legs.jsonl.rotating (picked up again next time), the archive is written to a .tmp and moved into place.
     */
    static synchronized void rotateLegs(File dir) { rotateWeekly(dir, "legs.jsonl", "[Survey]"); }

    /**
     * batch 22 O-6: rotateLegs for any append-only name.jsonl in dir (same steps, names and log lines; tag is the log prefix).
     * Not synchronized itself: the caller runs it where it is the only writer of that file (rotateLegs: the flush lock;
     * QuestRunner's data/quests/failures.jsonl: the SurveyWriter thread). Returns true when a file was rotated.
     */
    static boolean rotateWeekly(File dir, String name, String tag) {
        String base = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
        try {
            File legs = new File(dir, name), rot = new File(dir, name + ".rotating");
            String now = isoWeek(System.currentTimeMillis());
            if (!rot.exists()) {
                if (!legs.exists() || legs.length() == 0 || isoWeek(legs.lastModified()).equals(now)) return false;
                long lm = legs.lastModified();
                if (!legs.renameTo(rot)) { System.out.println(tag + " " + name + " rotation: could not rename"); return false; }
                rot.setLastModified(lm);
            }
            String week = isoWeek(rot.lastModified());
            File gz = new File(dir, base + "-" + week + ".jsonl.gz"), tmp = new File(dir, gz.getName() + ".tmp");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
                if (gz.exists()) java.nio.file.Files.copy(gz.toPath(), fo); // the same week again (a leftover): a second gzip member, readable as one stream
                java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(new java.io.BufferedOutputStream(fo, 1 << 16));
                java.nio.file.Files.copy(rot.toPath(), z);
                z.finish(); z.flush();
            }
            try { java.nio.file.Files.move(tmp.toPath(), gz.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (Throwable t) { java.nio.file.Files.move(tmp.toPath(), gz.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            long size = rot.length();
            if (!rot.delete()) System.out.println(tag + " " + name + " rotation: could not delete " + rot.getName());
            File[] all = weeklyArchives(dir, name); int pruned = 0;
            for (int i = 0; i < all.length - KEEP_ARCHIVES; i++) if (all[i].delete()) pruned++;
            System.out.println(tag + " " + name + " rotated: " + size / 1024 + " KB of week " + week + " -> " + gz.getName() + " (" + gz.length() / 1024 + " KB), fresh " + name + " for " + now + ", " + pruned + " old archive(s) pruned, " + Math.min(all.length, KEEP_ARCHIVES) + " kept");
            return true;
        } catch (Throwable t) { System.out.println(tag + " " + name + " rotation failed: " + t); return false; }
    }

    @SuppressWarnings("unchecked")
    static synchronized void flush() {
        List<String> out;
        synchronized (PENDING) { out = new ArrayList<>(PENDING); PENDING.clear(); }
        try {
            File dir = new File(ServerConstants.DATA_PATH, "survey"); dir.mkdirs();
            rotateLegs(dir); // batch 17 item2: a new ISO week starts a fresh legs.jsonl (the old one gzipped beside it)
            if (!out.isEmpty()) try (FileWriter w = new FileWriter(new File(dir, "legs.jsonl"), true)) { for (String s : out) { w.write(s); w.write('\n'); } }
            JSONObject tiles = new JSONObject(); tiles.putAll(TILES);
            Ledger.writeAtomic(new File(dir, "tiles.json"), tiles.toJSONString()); // audit A-2: all at once
            System.out.println("[Survey] " + legs + " legs recorded so far (" + blocked + " blocked or short), " + TILES.size() + " tiles visited");
            Resources.save();
        } catch (Throwable t) { System.out.println("[Survey] could not write: " + t); }
    }
}
