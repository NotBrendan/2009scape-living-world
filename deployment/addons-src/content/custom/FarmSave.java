package content.custom;

import content.global.skill.farming.FarmingPatch;
import content.global.skill.farming.Patch;
import content.global.skill.farming.timers.CropGrowth;
import core.game.node.entity.player.Player;
import core.game.system.timer.RSTimer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * W2 lane F (the design notes): a bot's crops survive a logout and a restart.
 * Off by default: -Dbots.farming.trees.save=true (needs bots.farming). Off = no file is read, written, moved or deleted.
 *
 * One file per bot with something planted: data/botdata/farm/<name>.json = a small wrapper (v, name, savedAt, site, due) around
 * the rows the engine's own CropGrowth.save writes; restore feeds them to the engine's own CropGrowth.parse on a detached timer
 * and swaps it onto the bot only when that worked (registerTimer then runs the engine's offline catch-up).
 * A missing file means weedy patches as before; a bad or stale file is moved to <name>.json.bad and the bot starts empty.
 * Nothing here throws into its caller. Callers: SmartAdventurers enlist (restore), Brain.farmLogout / endFarm (save),
 * saveCrops (10-minute pulse and shutdown), nameClash (rename).
 */
final class FarmSave {
    private FarmSave() { }

    static final boolean ON = Farming.ON && Boolean.getBoolean("bots.farming.trees.save");
    static final int V = 1;
    /** A file older than this is not trusted (the flag was off for a while, or the name went to another bot). */
    static final long MAX_AGE_MS = Math.max(1L, Long.getLong("bots.farming.trees.save.maxAgeH", 48L)) * 3_600_000L;
    /** savedAt may be this far ahead of now (clock steps) before the file is refused. */
    static final long CLOCK_SLACK_MS = 600_000L;
    static final long MAX_BYTES = 262_144L;
    static final long SOON_MS = 60_000L;

    static int SAVED, RESTORED, BAD, WRITE_FAILED;
    static String totals() { return " saved=" + SAVED + " restored=" + RESTORED + " bad=" + BAD + " writeFailed=" + WRITE_FAILED; }

    // ---- pure part (no engine classes): names, wrapper, checks ----

    static File dir() {
        String d = System.getProperty("bots.farming.trees.save.dir");
        if (d != null && !d.trim().isEmpty()) return new File(d.trim());
        return new File(new File(core.ServerConstants.DATA_PATH, "botdata"), "farm");
    }
    /** Lower case; anything but a-z, 0-9 and '-' becomes '_' (a space and '_' are the same name in game). */
    static String fileName(String name) {
        StringBuilder b = new StringBuilder();
        for (char c : String.valueOf(name).trim().toLowerCase(Locale.ROOT).toCharArray()) b.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' ? c : '_');
        return (b.length() == 0 ? "_" : b.toString()) + ".json";
    }
    static File file(String name) { return new File(dir(), fileName(name)); }
    static boolean sameName(String a, String b) { return a != null && b != null && fileName(a).equals(fileName(b)); }

    @SuppressWarnings("unchecked")
    static String wrap(String name, long savedAt, String site, long due, JSONObject crops) {
        JSONObject o = new JSONObject();
        o.put("v", V); o.put("name", name); o.put("savedAt", savedAt); o.put("site", site); o.put("due", due); o.put("crops", crops);
        return o.toJSONString();
    }

    /** What a file holds, or why it is refused (bad != null). */
    static final class Rec {
        String bad, site, name; long due, savedAt; JSONObject crops; int siteIdx = -1, planted;
        static Rec bad(String why) { Rec r = new Rec(); r.bad = why; return r; }
    }

    private static long num(Object o) { if (o instanceof Number) return ((Number) o).longValue(); throw new IllegalArgumentException("not a number"); }

    /** The wrapper checks (the rows themselves are judged by the engine's parse). Never throws. */
    static Rec read(String text, String name, long now, int maxRows, Predicate<String> siteOk) {
        try {
            if (text == null || text.trim().isEmpty()) return Rec.bad("empty");
            Object root = new JSONParser().parse(text);
            if (!(root instanceof JSONObject)) return Rec.bad("not an object");
            JSONObject o = (JSONObject) root;
            if (!(o.get("v") instanceof Number) || num(o.get("v")) != V) return Rec.bad("version " + o.get("v"));
            Rec r = new Rec();
            if (!(o.get("name") instanceof String) || !sameName((String) o.get("name"), name)) return Rec.bad("name " + o.get("name"));
            r.name = (String) o.get("name");
            if (!(o.get("savedAt") instanceof Number) || !(o.get("due") instanceof Number)) return Rec.bad("times");
            r.savedAt = num(o.get("savedAt")); r.due = num(o.get("due"));
            if (r.savedAt > now + CLOCK_SLACK_MS) return Rec.bad("clock (saved " + (r.savedAt - now) / 60_000 + " min ahead)");
            if (now - r.savedAt > MAX_AGE_MS) return Rec.bad("stale (" + (now - r.savedAt) / 3_600_000L + " h old)");
            if (r.due <= 0) return Rec.bad("due " + r.due);
            if (!(o.get("site") instanceof String) || siteOk != null && !siteOk.test((String) o.get("site"))) return Rec.bad("site " + o.get("site"));
            r.site = (String) o.get("site");
            if (!(o.get("crops") instanceof JSONObject)) return Rec.bad("no crops");
            r.crops = (JSONObject) o.get("crops");
            Object rows = r.crops.get("patches");
            if (!(rows instanceof JSONArray)) return Rec.bad("no patches array");
            if (((JSONArray) rows).isEmpty()) return Rec.bad("no rows");
            if (((JSONArray) rows).size() > maxRows) return Rec.bad(((JSONArray) rows).size() + " rows");
            for (Object row : (JSONArray) rows) if (!(row instanceof JSONObject)) return Rec.bad("row is not an object");
            return r;
        } catch (Throwable t) { return Rec.bad("unreadable (" + t.getClass().getSimpleName() + ")"); }
    }

    /** The CHECK time for a bot that just came back: the saved one, or in a minute when that has passed. */
    static long dueOnRestore(long savedDue, long now) { return savedDue > now + SOON_MS ? savedDue : now + SOON_MS; }

    /** saveCrops: the due time to file for an online bot. Crops due = that; mid-run with nothing due yet = a check in 15 min; else 0 (no file). */
    static long pulseDue(long farmDue, int farm, long now) { return farmDue != 0 ? farmDue : farm != 0 ? now + 15 * 60_000L : 0; }

    /** The site keys restore accepts: Farming.SITES' keys; with trees on (-Dbots.farming.trees.run) also a tree site's key (W9Trees.KEYS).
     *  The file format is unchanged; a tree key file written with the flag on is set aside as "site" when the flag is off again. */
    static boolean siteOk(String key, boolean trees) { return Farming.siteIndex(key) >= 0 || trees && W9Trees.siteIndex(key) >= 0; }

    // ---- files ----

    /** Temp file, fsync, atomic rename (plain replace where the filesystem has no atomic move). */
    static void writeAtomic(File f, String text) throws java.io.IOException {
        File d = f.getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs() && !d.isDirectory()) throw new java.io.IOException("cannot make " + d);
        File tmp = new File(d, f.getName() + ".tmp");
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
            fo.write(text.getBytes(StandardCharsets.UTF_8)); fo.flush();
            try { fo.getFD().sync(); } catch (java.io.IOException ignored) { }
        }
        try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.AccessDeniedException e) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    /** A refused file leaves the way: <name>.json.bad (one kept), deleted if even that fails. */
    static void aside(File f, String name, String why) {
        BAD++;
        try { Files.move(f.toPath(), new File(f.getParentFile(), f.getName() + ".bad").toPath(), StandardCopyOption.REPLACE_EXISTING); }
        catch (Throwable t) { try { Files.deleteIfExists(f.toPath()); } catch (Throwable ignored) { } }
        System.out.println("[FarmSave] " + name + " file set aside (" + why + ")");
    }

    static void forget(String name) { try { Files.deleteIfExists(file(name).toPath()); } catch (Throwable ignored) { } }

    /**
     * Brain.farmLogout with nothing due (W2 review fix): when the bot's patches are empty too, an older file is stale (its crops were
     * harvested in the run the logout cut short) and would bring them back on the next login. Crops still in the ground: file kept.
     */
    static boolean logoutEmpty(Player bot, String name) {
        if (!ON || bot == null || name == null) return false;
        try { if (planted(timerOf(bot)) > 0) return false; File f = file(name); if (!f.isFile()) return false; forget(name); System.out.println("[FarmSave] " + name + " stale file removed (logout, nothing in the ground)"); return true; }
        catch (Throwable t) { return false; }
    }

    // ---- engine part ----

    /** The bot's live crop timer (active or just registered, not one waiting to be removed), or null. */
    static CropGrowth timerOf(Player bot) {
        if (bot == null || bot.timers == null) return null;
        for (RSTimer t : bot.timers.getActiveTimers()) if (t instanceof CropGrowth && !bot.timers.getToRemoveTimers().contains(t)) return (CropGrowth) t;
        for (RSTimer t : bot.timers.getNewTimers()) if (t instanceof CropGrowth && !bot.timers.getToRemoveTimers().contains(t)) return (CropGrowth) t;
        return null;
    }
    static int planted(CropGrowth t) { int n = 0; if (t != null) for (Patch p : t.getPatches()) if (p != null && p.getPlantable() != null) n++; return n; }

    /**
     * Writes the bot's patches. due 0 or nothing planted = nothing to keep: the file is removed. why null = quiet (the 10-minute pulse).
     * True only when a file with at least one planted patch is on disk afterwards.
     */
    static boolean save(Player bot, String name, String site, long due, String why) {
        if (!ON || bot == null || name == null) return false;
        try {
            CropGrowth t = timerOf(bot);
            int n = planted(t);
            if (n == 0 || due <= 0 || site == null) { forget(name); return false; }
            JSONObject crops = new JSONObject();
            t.save(crops, bot);
            writeAtomic(file(name), wrap(name, System.currentTimeMillis(), site, due, crops));
            SAVED++;
            if (why != null) System.out.println("[FarmSave] " + name + " saved " + n + " planted (" + why + ")");
            return true;
        } catch (Throwable t) {
            WRITE_FAILED++;
            System.out.println("[FarmSave] " + name + " write failed: " + t);
            return false;
        }
    }

    /**
     * Puts saved patches back on a bot that just came online. Null when there is nothing to restore (flag off, no file, refused file,
     * nothing planted, or the bot already has crops). Otherwise siteIdx / due are set for the Brain and the engine has caught up.
     */
    static Rec restore(Player bot, String name, long now) {
        if (!ON || bot == null || name == null || bot.timers == null) return null;
        File f;
        try { f = file(name); if (!f.isFile()) return null; } catch (Throwable t) { return null; }
        CropGrowth fresh = null;
        try {
            if (f.length() > MAX_BYTES) { aside(f, name, "too big (" + f.length() + " bytes)"); return null; }
            Rec r = read(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), name, now, FarmingPatch.values().length, k -> siteOk(k, W9Trees.RUN));
            if (r.bad != null) { aside(f, name, r.bad); return null; }
            CropGrowth old = timerOf(bot);
            if (planted(old) > 0) { System.out.println("[FarmSave] " + name + " already has crops, file left alone"); return null; }
            CropGrowth parsed = new CropGrowth();
            try { parsed.parse(r.crops, bot); } catch (Throwable t) { aside(f, name, "rows (" + t.getClass().getSimpleName() + ")"); return null; }
            if (planted(parsed) == 0) { forget(name); return null; }
            if (old != null) bot.timers.removeTimer(old);
            fresh = parsed;
            bot.timers.registerTimer(fresh); // onRegister: the engine's offline catch-up
            r.planted = planted(fresh); r.siteIdx = Farming.siteIndex(r.site); r.due = dueOnRestore(r.due, now);
            RESTORED++;
            System.out.println("[FarmSave] " + name + " restored " + r.planted + " planted at " + r.site + ", check in " + (r.due - now) / 60_000 + " min, away " + Math.max(0, now - r.savedAt) / 60_000 + " min");
            return r;
        } catch (Throwable t) {
            if (fresh != null) try { bot.timers.removeTimer(fresh); } catch (Throwable ignored) { }
            aside(f, name, "restore (" + t + ")");
            return null;
        }
    }

    /** nameClash: the crops follow the profile to its new name. */
    @SuppressWarnings("unchecked")
    static void rename(String old, String fresh) {
        if (!ON || old == null || fresh == null) return;
        File a = null;
        try {
            a = file(old);
            if (!a.isFile()) return;
            JSONObject o = (JSONObject) new JSONParser().parse(new String(Files.readAllBytes(a.toPath()), StandardCharsets.UTF_8));
            o.put("name", fresh);
            writeAtomic(file(fresh), o.toJSONString());
            if (!file(fresh).equals(a)) Files.deleteIfExists(a.toPath());
            System.out.println("[FarmSave] " + old + " -> " + fresh + ": crop file renamed");
        } catch (Throwable t) { if (a != null && a.isFile()) aside(a, old, "rename (" + t.getClass().getSimpleName() + ")"); }
    }
}
