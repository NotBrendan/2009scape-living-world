package content.custom;

import content.global.skill.farming.FarmingPatch;
import content.global.skill.farming.Patch;
import content.global.skill.farming.Plantable;
import content.global.skill.farming.timers.CropGrowth;
import core.game.node.entity.player.Player;
import core.game.system.timer.TimerManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * W2 lane F: FarmSave, plain java (no world), with the engine's real CropGrowth / Patch classes from server.jar:
 *   ON : java -Dbots.farming=true -Dbots.farming.trees.save=true -cp "<build>;server.jar" content.custom.TestFarmSave <addons-src>/content/custom <empty temp dir> <cache copy>
 *   OFF: java -Dbots.farming=true -cp "<build>;server.jar" content.custom.TestFarmSave <addons-src>/content/custom <empty temp dir> <cache copy>
 * Third argument: a COPY of the game cache folder (read only, for varbit definitions; never a world's own folder).
 * The bot is a bare Player and the Brain a bare Brain (no constructor run: AIPlayer and SmartAdventurers need game data to load),
 * given a name, a TimerManager, varbit storage and a packet dispatch with no session. That is enough for the engine's real
 * save, parse and offline catch-up and for Brain.farmLogout. enlist, saveCrops, shutdown and nameClash need the loaded world:
 * here they are checked by source text (H1-H6) and their rule (pulseDue); in-world checks in the design notes.
 */
public final class TestFarmSave {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestFarmSave] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }

    static Object unsafeNew(Class<?> c) throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); f.setAccessible(true);
        Object u = f.get(null);
        return u.getClass().getMethod("allocateInstance", Class.class).invoke(u, c);
    }
    static void setField(Object o, String name, Object v) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(o, v); return; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static Player bot(String name) throws Exception {
        Player b = (Player) unsafeNew(Player.class);
        setField(b, "name", name);
        b.timers = new TimerManager(b);
        b.varpManager = new core.game.node.entity.player.VarpManager(b); b.varpMap = new java.util.HashMap<>(); b.saveVarp = new java.util.HashMap<>(); // the engine's save reads the patch varbit
        setField(b, "packetDispatch", new core.game.node.entity.player.link.PacketDispatch(b)); // and its parse writes it (sendVarp)
        setField(b, "details", unsafeNew(core.game.node.entity.player.info.PlayerDetails.class)); // no session: the packet is dropped, as for a bot
        return b;
    }
    /** Plants by field setters only (Patch.plant needs varbits): the same fields the engine's save writes. */
    /** A Brain without its constructor (that would load SmartAdventurers and the game data): farmLogout reads only its own fields. */
    static SmartAdventurers.Brain brain() throws Exception { return (SmartAdventurers.Brain) unsafeNew(SmartAdventurers.Brain.class); }
    static Patch plant(Player bot, FarmingPatch fp, Plantable what, int stage, long nextGrowth) {
        CropGrowth t = FarmSave.timerOf(bot);
        if (t == null) { t = new CropGrowth(); bot.timers.registerTimer(t); }
        Patch p = t.getPatch(fp, true);
        p.setPlantable(what); p.setCurrentGrowthStage(stage); p.setNextGrowth(nextGrowth); p.setProtectionPaid(true); p.setHarvestAmt(3);
        return p;
    }
    static Patch patchOf(Player bot, FarmingPatch fp) { CropGrowth t = FarmSave.timerOf(bot); return t == null ? null : t.getPatch(fp, false); }
    static String slurp(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    static void spit(File f, String s) throws Exception { f.getParentFile().mkdirs(); Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }
    static int count(String hay, String needle) { int n = 0; for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + 1)) n++; return n; }

    public static void main(String[] args) throws Exception {
        String src = args[0]; File tmp = new File(args[1]);
        try { core.cache.Cache.init(args[2]); } catch (Throwable t) { throw new Exception("cache " + args[2] + ": " + t, t); } // read only: varbit definitions for Patch.getCurrentState (a COPY of the cache folder, never a world's)
        File dir = new File(tmp, "farm"); System.setProperty("bots.farming.trees.save.dir", dir.getPath());
        long now = System.currentTimeMillis(), due = now + 100 * 60_000L, grow = now + 20 * 60_000L;
        final String NAME = "Unit Bob";
        File f = FarmSave.file(NAME), bad = new File(dir, f.getName() + ".bad");

        // ---- pure ----
        check("N file name: lower case, space and odd chars to _, never empty or a path",
                "unit_bob.json".equals(FarmSave.fileName("Unit Bob")) && "a_b-9.json".equals(FarmSave.fileName(" A_b-9 ")) && "______x.json".equals(FarmSave.fileName("../..\\x")) && "_.json".equals(FarmSave.fileName(""))
                        && FarmSave.sameName("Unit Bob", "unit_bob") && !FarmSave.sameName("Unit Bob", "Unit Bobb") && !FarmSave.sameName(null, "x"));
        check("D dueOnRestore: future kept, past or within a minute = now + 1 min",
                FarmSave.dueOnRestore(now + 3_600_000L, now) == now + 3_600_000L && FarmSave.dueOnRestore(now - 5, now) == now + 60_000L && FarmSave.dueOnRestore(now + 30_000L, now) == now + 60_000L);
        JSONObject crops = new JSONObject(); JSONArray rows = new JSONArray(); rows.add(new JSONObject()); crops.put("patches", rows);
        String good = FarmSave.wrap(NAME, now, "FALADOR", due, crops);
        FarmSave.Rec r = FarmSave.read(good, "unit_bob", now, 50, k -> k.equals("FALADOR"));
        check("W wrap then read: " + r.bad, r.bad == null && "FALADOR".equals(r.site) && r.due == due && r.savedAt == now && r.crops != null);
        String[][] bads = {
                {"null text", null}, {"empty", "  "}, {"garbage", "{\"v\":1,\"name\":\"Unit"}, {"an array", "[1,2]"},
                {"version 2", good.replace("\"v\":1", "\"v\":2")}, {"version missing", good.replace("\"v\":1", "\"w\":1")},
                {"other bot's name", FarmSave.wrap("Someone Else", now, "FALADOR", due, crops)},
                {"unknown site", FarmSave.wrap(NAME, now, "ATLANTIS", due, crops)},
                {"due 0", FarmSave.wrap(NAME, now, "FALADOR", 0, crops)},
                {"stale (49 h)", FarmSave.wrap(NAME, now - 49 * 3_600_000L, "FALADOR", due, crops)},
                {"clock (saved 11 min ahead)", FarmSave.wrap(NAME, now + 11 * 60_000L, "FALADOR", due, crops)},
                {"no crops", good.replace("\"crops\"", "\"corps\"")}, {"patches not an array", good.replace("[{}]", "7")},
                {"no rows", good.replace("[{}]", "[]")}, {"a row that is not an object", good.replace("[{}]", "[5]")},
                {"savedAt a string", good.replace("\"savedAt\":" + now, "\"savedAt\":\"x\"")}};
        StringBuilder missed = new StringBuilder();
        for (String[] b : bads) { FarmSave.Rec x = FarmSave.read(b[1], NAME, now, 50, k -> k.equals("FALADOR")); if (x == null || x.bad == null) missed.append(b[0]).append("; "); }
        check("B read refuses " + bads.length + " bad wrappers, never throws" + (missed.length() > 0 ? " (accepted: " + missed + ")" : ""), missed.length() == 0);
        JSONArray many = new JSONArray(); for (int i = 0; i < 51; i++) many.add(new JSONObject()); JSONObject mc = new JSONObject(); mc.put("patches", many);
        check("B too many rows refused, 48 h old still fine", FarmSave.read(FarmSave.wrap(NAME, now, "FALADOR", due, mc), NAME, now, 50, null).bad != null
                && FarmSave.read(FarmSave.wrap(NAME, now - 47 * 3_600_000L, "FALADOR", due, crops), NAME, now, 50, null).bad == null);
        check("S Farming.siteIndex: keys found, junk -1", Farming.siteIndex("FALADOR") == 0 && Farming.siteIndex("ARDOUGNE") == 2 && Farming.siteIndex("falador") == -1 && Farming.siteIndex(null) == -1);

        // ---- wiring (source text): the five hooks sit where the design says ----
        String sa = slurp(new File(src, "SmartAdventurers.java"));
        int enl = sa.indexOf("static void enlist("), put = sa.indexOf("BRAINS.put(key, brain);", enl), res = sa.indexOf("FarmSave.restore(bot, bot.getUsername()", enl), gbc = sa.indexOf("new GeneralBotCreator(brain, bot)", enl);
        check("H1 enlist restores after BRAINS.put and before the script starts, behind FarmSave.ON, in a try", enl > 0 && put > enl && res > put && res < gbc && sa.lastIndexOf("if (FarmSave.ON) try {", res) > put);
        int sd = sa.indexOf("public void shutdown()"), sdc = sa.indexOf("saveCrops(null)", sd), sdp = sa.indexOf("saveProfiles();", sd);
        check("H2 shutdown writes crop files before the profile book", sd > 0 && sdc > sd && sdc < sdp);
        int bp = sa.indexOf("final JSONArray arr = buildProfiles(); if (arr != null) TIMER.schedule"), pc = sa.lastIndexOf("saveCrops(null)", bp);
        check("H3 10-minute pulse writes crop files just before the profile snapshot", bp > 0 && pc > 0 && bp - pc < 250);
        int nc = sa.indexOf("static void nameClash("), rn = sa.indexOf("FarmSave.rename(old, fresh)", nc), lo = sa.indexOf("Profile p = logOut(bot, brain);", nc);
        check("H4 nameClash renames the file after the logout save", nc > 0 && lo > nc && rn > lo && rn - nc < 1500);
        int ef = sa.indexOf("void endFarm(boolean gaveUp, String why)"), es = sa.indexOf("FarmSave.save(bot, bot.getUsername(), Farming.SITES[farmSite].key, farmDue, \"run end\")", ef), ez = sa.indexOf("farm = 0; farmAct = 0;", ef);
        check("H5 endFarm saves once farmDue is final and before the run state is cleared; one FarmSave.restore and 4 FarmSave.save calls in all (w9 trees: + farmSaveKeyed)", ef > 0 && es > ef && es < ez && count(sa, "FarmSave.restore(") == 1 && count(sa, "FarmSave.save(") == 4);
        int sc = sa.indexOf("static int saveCrops(String why)");
        check("H6 saveCrops: off = 0 before any work; walks BRAINS, due from FarmSave.pulseDue", sc > 0 && sa.indexOf("if (!FarmSave.ON) return 0;", sc) - sc < 80 && sa.indexOf("FarmSave.pulseDue(b.farmDue, b.farm, now)", sc) - sc < 500 && sa.indexOf("for (Brain b : BRAINS.values())", sc) - sc < 300);

        Player a = bot(NAME);
        check("P bare bot: name " + a.getUsername() + ", timer manager, no crop timer yet", NAME.equalsIgnoreCase(a.getUsername()) && FarmSave.timerOf(a) == null && FarmSave.planted(null) == 0);
        plant(a, FarmingPatch.S_FALADOR_ALLOTMENT_NW, Plantable.POTATO_SEED, 1, grow);
        plant(a, FarmingPatch.S_FALADOR_HERB_NE, Plantable.GUAM_SEED, 2, grow + 60_000L);
        FarmSave.timerOf(a).getPatch(FarmingPatch.S_FALADOR_ALLOTMENT_SE, true); // touched, nothing planted

        if (!FarmSave.ON) { // ---- flag off: nothing read, written or moved; the logout counts the crops lost as before
            spit(f, good); long len = f.length(), mod = f.lastModified();
            check("OFF save false, restore null, rename no-op, file untouched", !FarmSave.save(a, NAME, "FALADOR", due, "unit") && FarmSave.restore(bot(NAME), NAME, now) == null
                    && f.isFile() && f.length() == len && f.lastModified() == mod && !bad.exists());
            FarmSave.rename(NAME, "Other Name");
            check("OFF rename leaves the file", f.isFile() && !FarmSave.file("Other Name").exists());
            SmartAdventurers.Brain br = brain(); br.bot = a; br.farmSite = 0; br.farmDue = due; long ab = Farming.ABANDONED; br.farmLogout();
            check("OFF Brain.farmLogout: ABANDONED +1, farmDue 0, file untouched", Farming.ABANDONED == ab + 1 && br.farmDue == 0 && f.length() == len && f.lastModified() == mod);
            check("OFF totals carry no save counters", !Farming.totals().contains("saved=") && FarmSave.SAVED == 0 && FarmSave.RESTORED == 0 && FarmSave.BAD == 0);
            System.out.println("[TestFarmSave] OFF " + pass + " pass, " + fail + " fail");
            System.exit(fail == 0 ? 0 : 1);
        }

        // ---- engine round trip: the real CropGrowth.save -> file -> the real CropGrowth.parse on a new bot ----
        check("R save: true, file on disk, no temp left", FarmSave.save(a, NAME, "FALADOR", due, "unit") && f.isFile() && !new File(dir, f.getName() + ".tmp").exists() && FarmSave.SAVED == 1);
        JSONObject disk = (JSONObject) new JSONParser().parse(slurp(f));
        check("R file: v 1, name, site, due, engine rows " + ((JSONArray) ((JSONObject) disk.get("crops")).get("patches")).size(),
                ((Number) disk.get("v")).intValue() == 1 && NAME.equals(disk.get("name")) && "FALADOR".equals(disk.get("site")) && ((Number) disk.get("due")).longValue() == due
                        && ((JSONArray) ((JSONObject) disk.get("crops")).get("patches")).size() >= 2);
        Player b = bot(NAME);
        FarmSave.Rec got = FarmSave.restore(b, NAME, now + 5 * 60_000L);
        Patch pa = patchOf(b, FarmingPatch.S_FALADOR_ALLOTMENT_NW), ph = patchOf(b, FarmingPatch.S_FALADOR_HERB_NE);
        check("R restore on a new bot: 2 planted, site 0, due kept", got != null && got.planted == 2 && got.siteIdx == 0 && got.due == due && FarmSave.RESTORED == 1);
        check("R patches equal: potato stage 1, guam stage 2, nextGrowth, paid protection, harvest amount, owner is the new bot",
                pa != null && ph != null && pa.getPlantable() == Plantable.POTATO_SEED && pa.getCurrentGrowthStage() == 1 && pa.getNextGrowth() == grow && pa.getProtectionPaid() && pa.getHarvestAmt() == 3
                        && ph.getPlantable() == Plantable.GUAM_SEED && ph.getCurrentGrowthStage() == 2 && ph.getNextGrowth() == grow + 60_000L && pa.getPlayer() == b);
        Patch pe = patchOf(b, FarmingPatch.S_FALADOR_ALLOTMENT_SE);
        check("R the empty patch stays empty; the file is kept until the next save", (pe == null || pe.getPlantable() == null) && f.isFile());
        Player b2 = bot(NAME); FarmSave.Rec late = FarmSave.restore(b2, NAME, due - 10_000L);
        check("R a second restart restores again (idempotent); a due about to pass = check in 1 min", late != null && late.planted == 2 && late.due == due - 10_000L + 60_000L);
        check("R a bot that already has crops keeps them (file left alone)", FarmSave.restore(b, NAME, now) == null && f.isFile() && patchOf(b, FarmingPatch.S_FALADOR_ALLOTMENT_NW).getPlantable() == Plantable.POTATO_SEED);

        // ---- missing ----
        Player c = bot("Nobody Here");
        int bad0 = FarmSave.BAD;
        check("M missing file: null, no timer added, nothing counted, nothing created", FarmSave.restore(c, "Nobody Here", now) == null && FarmSave.timerOf(c) == null && FarmSave.BAD == bad0 && !FarmSave.file("Nobody Here").exists());

        // ---- corrupt files through restore ----
        String onDisk = slurp(f);
        String[][] corrupt = {
                {"truncated", onDisk.substring(0, onDisk.length() / 2)}, {"zero bytes", ""}, {"binary junk", "\u0000\u0001�PK\u0003"},
                {"wrong version", onDisk.replace("\"v\":1", "\"v\":9")}, {"another bot's file", onDisk.replace("\"name\":\"" + NAME + "\"", "\"name\":\"Zed\"")},
                {"unknown site", onDisk.replace("\"site\":\"FALADOR\"", "\"site\":\"MOON\"")},
                {"patch ordinal out of range", onDisk.replaceAll("\"patch-ordinal\":\\d+", "\"patch-ordinal\":99999")},
                {"plantable ordinal out of range", onDisk.replaceAll("\"patch-plantable-ordinal\":\\d+", "\"patch-plantable-ordinal\":99999")},
                {"a boolean turned to text", onDisk.replace("\"patch-dead\":false", "\"patch-dead\":\"no\"")},
                {"nextGrowth not a number", onDisk.replaceAll("\"patch-nextGrowth\":\"?\\d+\"?", "\"patch-nextGrowth\":\"soon\"")},
                {"stale", onDisk.replaceAll("\"savedAt\":\\d+", "\"savedAt\":" + (now - 72 * 3_600_000L))}};
        StringBuilder wrong = new StringBuilder();
        for (String[] cc : corrupt) {
            if (cc[1].equals(onDisk)) { wrong.append(cc[0]).append(" (test edit did not apply); "); continue; }
            spit(f, cc[1]); if (bad.exists()) bad.delete();
            Player n = bot(NAME); CropGrowth own = new CropGrowth(); n.timers.registerTimer(own); int before = FarmSave.BAD;
            FarmSave.Rec x = null; Throwable thrown = null;
            try { x = FarmSave.restore(n, NAME, now); } catch (Throwable t) { thrown = t; }
            boolean ok = thrown == null && x == null && !f.exists() && bad.isFile() && FarmSave.BAD == before + 1 && FarmSave.timerOf(n) == own && FarmSave.planted(own) == 0;
            if (!ok) wrong.append(cc[0]).append(thrown != null ? " threw " + thrown : x != null ? " was restored" : f.exists() ? " file still there" : !bad.isFile() ? " no .bad" : " timer or counter wrong").append("; ");
        }
        check("C " + corrupt.length + " corrupt files: null, moved to .bad, bad +1, the bot's own timer untouched and empty, no throw" + (wrong.length() > 0 ? " -> " + wrong : ""), wrong.length() == 0);
        byte[] big = new byte[(int) FarmSave.MAX_BYTES + 10]; java.util.Arrays.fill(big, (byte) ' '); Files.write(f.toPath(), big);
        check("C an oversized file is set aside unread", FarmSave.restore(bot(NAME), NAME, now) == null && !f.exists() && bad.isFile());

        // ---- K: growth missed while away is replayed by the engine's own catch-up at restore (registerTimer -> onRegister) ----
        Player k0 = bot(NAME); plant(k0, FarmingPatch.S_FALADOR_ALLOTMENT_NW, Plantable.POTATO_SEED, 1, now - 3 * 3_600_000L); // due to grow 3 h ago, protection paid (no disease roll)
        boolean ksaved = FarmSave.save(k0, NAME, "FALADOR", now - 60 * 60_000L, "unit"); if (bad.exists()) bad.delete();
        Player k1 = bot(NAME); int kb = FarmSave.BAD; FarmSave.Rec kx = null; Throwable kt = null;
        try { kx = FarmSave.restore(k1, NAME, now); } catch (Throwable t) { kt = t; }
        Patch kp = patchOf(k1, FarmingPatch.S_FALADOR_ALLOTMENT_NW);
        check("K away 3 h: catch-up ran, potato stage 1 -> " + (kp == null ? "?" : kp.getCurrentGrowthStage() + (kp.isGrown() ? " (grown)" : "")) + ", alive, overdue CHECK = in 1 min",
                ksaved && kt == null && kx != null && kx.planted == 1 && FarmSave.BAD == kb && kp != null && kp.getPlantable() == Plantable.POTATO_SEED && kp.getCurrentGrowthStage() > 1 && kp.isGrown() && !kp.isDead() && !kp.isDiseased()
                        && kx.due == now + 60_000L);
        System.out.println("[TestFarmSave] info K nextGrowth moved by " + (kp == null ? 0 : (kp.getNextGrowth() - (now - 3 * 3_600_000L)) / 60_000) + " min, due in " + (kx == null ? -1 : (kx.due - now) / 1000) + " s");

        // ---- write failure: the folder is a file ----
        File blocker = new File(tmp, "blocker"); spit(blocker, "x");
        System.setProperty("bots.farming.trees.save.dir", new File(blocker, "farm").getPath());
        int wf = FarmSave.WRITE_FAILED; boolean wrote = FarmSave.save(a, NAME, "FALADOR", due, "unit");
        check("F write failure: false, writeFailed +1, no throw", !wrote && FarmSave.WRITE_FAILED == wf + 1);
        SmartAdventurers.Brain lost = brain(); lost.bot = a; lost.farmSite = 0; lost.farmDue = due;
        long ab0 = Farming.ABANDONED; lost.farmLogout();
        check("F real caller Brain.farmLogout when the write fails: crops counted lost (ABANDONED +1), farmDue 0", Farming.ABANDONED == ab0 + 1 && lost.farmDue == 0);
        System.setProperty("bots.farming.trees.save.dir", dir.getPath());

        // ---- real callers: Brain.farmLogout and saveCrops ----
        if (f.exists()) f.delete();
        SmartAdventurers.Brain br = brain(); br.bot = a; br.farmSite = 0; br.farmDue = due;
        Farming.ACTIVE.add("unit bob");
        long ab1 = Farming.ABANDONED;
        check("L pulseDue (saveCrops rule): crops due = that time; mid-run with nothing due yet = 15 min; idle = 0 (no file)",
                FarmSave.pulseDue(due, 0, now) == due && FarmSave.pulseDue(due, 3, now) == due && FarmSave.pulseDue(0, 3, now) == now + 15 * 60_000L && FarmSave.pulseDue(0, 0, now) == 0);
        f.delete(); br.farmLogout();
        check("L real caller Brain.farmLogout: file written, NOT abandoned, farmDue 0, seat freed", f.isFile() && Farming.ABANDONED == ab1 && br.farmDue == 0 && !Farming.ACTIVE.contains("unit bob"));
        Player back = bot(NAME); FarmSave.Rec rb = FarmSave.restore(back, NAME, System.currentTimeMillis());
        check("L the bot that comes back has the same two crops and the same due", rb != null && rb.planted == 2 && rb.due == due && patchOf(back, FarmingPatch.S_FALADOR_HERB_NE).getPlantable() == Plantable.GUAM_SEED);

        // ---- nothing planted / due 0 removes the file ----
        check("Z due 0 (crops given up): save false and the file is gone", !FarmSave.save(a, NAME, "FALADOR", 0, "unit") && !f.exists());
        FarmSave.save(a, NAME, "FALADOR", due, "unit");
        Player empty = bot(NAME); empty.timers.registerTimer(new CropGrowth());
        check("Z nothing planted: save false and the file is gone", f.isFile() && !FarmSave.save(empty, NAME, "FALADOR", due, "unit") && !f.exists());

        // ---- review fix: a logout with nothing due and empty patches removes an older file (no second harvest); crops in the ground keep it ----
        FarmSave.save(a, NAME, "FALADOR", due, "unit");
        SmartAdventurers.Brain cut = brain(); cut.bot = empty; cut.farmSite = 0; cut.farmDue = 0; cut.farmLogout();
        check("R real caller Brain.farmLogout, nothing due, patches empty: the older file is removed, nothing comes back", !f.exists() && FarmSave.restore(bot(NAME), NAME, System.currentTimeMillis()) == null);
        FarmSave.save(a, NAME, "FALADOR", due, "unit");
        SmartAdventurers.Brain twice = brain(); twice.bot = a; twice.farmSite = 0; twice.farmDue = 0; long ab2 = Farming.ABANDONED; twice.farmLogout();
        check("R real caller Brain.farmLogout, nothing due but crops in the ground (a second logout call): file kept, not abandoned", f.isFile() && Farming.ABANDONED == ab2);

        // ---- rename ----
        FarmSave.save(a, NAME, "FALADOR", due, "unit");
        FarmSave.rename(NAME, "New Name"); File nf = FarmSave.file("New Name");
        Player rn2 = bot("New Name"); FarmSave.Rec rr = FarmSave.restore(rn2, "New Name", now);
        check("X rename: old file gone, new file restores under the new name, the old name finds nothing", !f.exists() && nf.isFile() && rr != null && rr.planted == 2 && FarmSave.restore(bot(NAME), NAME, now) == null);
        FarmSave.rename("No Such Bot", "Whoever");
        check("X rename of a bot without a file does nothing", !FarmSave.file("Whoever").exists());

        check("T totals: save counters at the very end of the [Farm] tally", Farming.totals().matches(".* saved=\\d+ restored=\\d+ bad=\\d+ writeFailed=\\d+$") && Farming.totals(Farming.V2).indexOf("saved=") < 0);
        System.out.println("[TestFarmSave] ON " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
