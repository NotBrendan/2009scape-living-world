package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * quest1 unit (pure, no bots): the -Dquest.resume item state of QuestBook. A bot's quest entry (active, step, stage, got) is saved
 * to JSON text, memory is cleared, the text is read back, and the reloaded entry must name the same quest and step and put the same
 * items back in the pack from the bank (QuestBook.putGot / readGot / planPull). Also: a missing, older-version or corrupt got entry
 * loads as empty without an error, and nothing is written when there is nothing to keep.
 * Offline with main() (java -cp server.jar;out content.custom.TestQuestResume); in a world with -Dtest.questResume=true it runs the
 * same checks, adds the flag wiring and the M3 hold count against the loaded scripts, prints
 * "[TestQuestResume] RESULT PASS/FAIL pass=N fail=M" and then "[TestQuestResume] DONE".
 * -Dquestresume.expect=on|off (optional) also checks QuestBook.RESUME against it.
 */
public final class TestQuestResume implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestQuestResume] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.questResume")) return;
        GameWorld.getPulser().submit(new Pulse(20) {
            @Override public boolean pulse() {
                try { checks(); fix2(); world(); fix3(false); world3(); } catch (Throwable ex) { fail++; log("error " + ex); }
                log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
                log("DONE");
                return true;
            }
        });
    }

    public static void main(String[] a) {
        try { checks(); fix2(); fix3(true); } catch (Throwable ex) { fail++; log("error " + ex); }
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
        log("DONE");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * quest3 (-Dquest.fix3): the login hold, driven the way SmartAdventurers.restoreItems drives it: QuestBook.holdCount /
     * holdEntry with the bot's user name as getUsername() gives it (mixed case) and the saved pack an entry a slot. The book
     * entry is the one a bot has after Sheep Shearer, Doric's Quest and Imp Catcher were started and abandoned and the 24 h
     * park ran out (no active quest, no running park, the stages saved), with Cook's Assistant finished.
     * Offline (main) the entry goes through the real save and load: QuestBook.snapshot -> write -> quests.json in a temp
     * folder -> BOTS cleared -> QuestBook.load, as a restart does. In a world it is put in the book directly (the live book
     * is never cleared) and world3() then runs restoreItems itself on a bot.
     */
    static void fix3(boolean offline) throws Exception {
        final String user = "Zz_Quest3_Bot", key = "zz_quest3_bot";
        final boolean keep = QuestRunner.KEEP_QUEST_ITEMS && QuestBook.ON, f3 = keep && QuestRunner.FIX3;
        log("quest.fix3=" + QuestRunner.FIX3 + " quest.keepQuestItems=" + QuestRunner.KEEP_QUEST_ITEMS + " quest.resume=" + QuestBook.RESUME + (offline ? " (offline: through quests.json)" : " (in a world)"));
        check("FIX3 follows -Dquest.fix3", QuestRunner.FIX3 == Boolean.getBoolean("quest.fix3"));
        if (offline) Class.forName("content.custom.AfkTasks"); // no world: AfkTasks first, or its Task enum starts AfkTasks' static block half-built (a world has it loaded long before a bot logs in)
        QuestBook.registerWave1(); // as QuestBook.startup does
        check("the scripts loaded (sheep, doric, imp, cooks, shieldofarrav)", QuestBook.SCRIPTS.containsKey("sheep") && QuestBook.SCRIPTS.containsKey("doric") && QuestBook.SCRIPTS.containsKey("imp") && QuestBook.SCRIPTS.containsKey("cooks") && QuestBook.SCRIPTS.containsKey("shieldofarrav"));
        java.io.File tmp = null; String oldPath = core.ServerConstants.DATA_PATH; Map<String, QuestBook.BotQ> oldBots = null;
        try {
            QuestBook.BotQ b = new QuestBook.BotQ();
            b.stages.put("SHEEP_SHEARER", 10); b.stages.put("DORICS_QUEST", 10); b.stages.put("IMP_CATCHER", 10); b.stages.put("COOKS_ASSISTANT", 100); b.stages.put("SHIELD_OF_ARRAV", 60);
            b.parked.put("sheep", System.currentTimeMillis() - 60_000L); // the park ran out a minute ago
            b.gotFor = "imp"; b.got.put(ORANGE_MAIL, 1);
            boolean gotKept = true;
            if (offline) {
                tmp = java.nio.file.Files.createTempDirectory("quest3test").toFile();
                core.ServerConstants.DATA_PATH = tmp.getAbsolutePath();
                oldBots = new HashMap<>(QuestBook.BOTS); QuestBook.BOTS.clear();
                QuestBook.BOTS.put(key, b);
                String json = QuestBook.snapshot();
                QuestBook.write(json, System.nanoTime());
                check("offline: quests.json written with the open stages", QuestBook.file().exists() && json.contains("\"SHEEP_SHEARER\":10") && json.contains("\"IMP_CATCHER\":10"));
                QuestBook.BOTS.clear();
                QuestBook.load();
                QuestBook.BotQ r = QuestBook.BOTS.get(key);
                check("offline: the entry came back from quests.json with no active quest and no running park", r != null && r != b && r.active == null && r.parked.isEmpty() && r.stages.getOrDefault("SHEEP_SHEARER", 0) == 10 && r.stages.getOrDefault("COOKS_ASSISTANT", 0) == 100);
                gotKept = QuestBook.RESUME; // got is only saved with -Dquest.resume
            } else QuestBook.BOTS.put(key, b);

            int ball = QuestBook.holdCount(user, WOOL), clay = QuestBook.holdCount(user, 434), copper = QuestBook.holdCount(user, 436), iron = QuestBook.holdCount(user, 440);
            int beads = 0; for (int bead : new int[]{1470, 1472, 1474, 1476}) beads += QuestBook.holdCount(user, bead);
            int egg = QuestBook.holdCount(user, 1944), logs = QuestBook.holdCount(user, 1511), coins = QuestBook.holdCount(user, COINS), tail = QuestBook.holdCount(user, 300);
            int bows = QuestBook.holdCount(user, 767), mail = QuestBook.holdCount(user, ORANGE_MAIL);
            log("holdCount: balls " + ball + ", clay " + clay + ", copper " + copper + ", iron " + iron + ", beads " + beads + ", egg " + egg + ", logs " + logs + ", coins " + coins + ", rat tail " + tail + ", crossbows " + bows + ", saved run item " + mail);
            check("sheep: the park ran out, the 20 balls of wool are " + (f3 ? "still held" : "not held (fix3 off)"), ball == (f3 ? 20 : 0));
            check("doric: 6 clay, 4 copper and 2 iron " + (f3 ? "held" : "not held (fix3 off)"), clay == (f3 ? 6 : 0) && copper == (f3 ? 4 : 0) && iron == (f3 ? 2 : 0));
            check("imp: one of each of the four beads " + (f3 ? "held" : "not held (fix3 off)"), beads == (f3 ? 4 : 0));
            check("arrav at stage 60 (set aside or park over): the two crossbows " + (f3 ? "held" : "not held (fix3 off)"), bows == (f3 ? 2 : 0));
            check("the saved run item of an open quest is " + (f3 && gotKept ? "held" : "not held"), mail == (f3 && gotKept ? 1 : 0));
            check("a finished quest (cooks at 100) holds nothing", egg == 0);
            check("a quest never started (witch) holds nothing; logs and coins never", tail == 0 && logs == 0 && coins == 0);

            // the saved pack as captureItems writes it: one entry a slot for an unstackable item (25 balls of wool, 2 logs)
            java.util.List<int[]> savedPack = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) savedPack.add(new int[]{WOOL, 1});
            savedPack.add(new int[]{1511, 1}); savedPack.add(new int[]{1511, 1});
            Map<Integer, Integer> soFar = new HashMap<>(); int heldBalls = 0, heldLogs = 0, oldWay = 0;
            for (int[] x : savedPack) { int h = QuestBook.holdEntry(user, x[0], x[1], soFar); if (x[0] == WOOL) heldBalls += h; else heldLogs += h; }
            for (int[] x : savedPack) if (x[0] == WOOL) oldWay += Math.min(x[1], QuestBook.holdCount(user, x[0])); // the fix3-off expression of restoreItems
            check("holdEntry over a 25-slot pack keeps " + (f3 ? "20, the other 5 are a haul" : "none (fix3 off)") + " (got " + heldBalls + "; the per-entry way keeps " + oldWay + ")", heldBalls == (f3 ? 20 : 0) && heldLogs == 0);
            check("the per-entry way (fix3 off in restoreItems) is unchanged: every slot it is asked about", oldWay == (f3 ? 25 : 0));
            Map<Integer, Integer> one = new HashMap<>();
            check("holdEntry on a stackable entry of 30 keeps the hold count once", QuestBook.holdEntry(user, 434, 30, one) == (f3 ? 6 : 0) && QuestBook.holdEntry(user, 434, 30, one) == 0);

            // an active quest and a running park hold as before, with or without fix3
            QuestBook.BotQ live = QuestBook.BOTS.get(key);
            live.active = "sheep";
            check("an active quest holds its 20 as before", QuestBook.holdCount(user, WOOL) == (keep ? 20 : 0));
            live.active = null; live.stages.remove("SHEEP_SHEARER"); live.parked.put("sheep", System.currentTimeMillis() + 60_000L);
            check("a running park holds its 20 as before", QuestBook.holdCount(user, WOOL) == (keep ? 20 : 0));
            live.parked.clear(); live.stages.put("SHEEP_SHEARER", 100);
            check("finished: nothing held", QuestBook.holdCount(user, WOOL) == 0);
            check("openStage: 1..99 only", QuestBook.openStage(1) && QuestBook.openStage(99) && !QuestBook.openStage(0) && !QuestBook.openStage(100) && !QuestBook.openStage(null));
        } finally {
            QuestBook.BOTS.remove(key);
            if (offline) {
                if (oldBots != null) { QuestBook.BOTS.clear(); QuestBook.BOTS.putAll(oldBots); }
                core.ServerConstants.DATA_PATH = oldPath;
                if (tmp != null) try { java.io.File[] fs = new java.io.File(tmp, "quests").listFiles(); if (fs != null) for (java.io.File x : fs) x.delete(); new java.io.File(tmp, "quests").delete(); tmp.delete(); } catch (Throwable ignored) { }
            }
        }
    }

    /** quest3, in a world only: SmartAdventurers.restoreItems itself on a bot whose book entry has an open, unparked Sheep Shearer. */
    static void world3() {
        core.game.bots.AIPlayer bot = null; String key = null;
        try {
            bot = new core.game.bots.AIPlayer(core.game.world.map.Location.create(3222, 3218, 0));
            key = QuestBook.key(bot);
            bot.getInventory().clear(); bot.getBank().clear();
            QuestBook.BotQ b = new QuestBook.BotQ();
            b.stages.put("SHEEP_SHEARER", 10);
            QuestBook.BOTS.put(key, b);
            SmartAdventurers.Profile prof = new SmartAdventurers.Profile();
            prof.name = bot.getUsername(); prof.items = true;
            for (int i = 0; i < 25; i++) prof.pack.add(new int[]{WOOL, 1});
            prof.pack.add(new int[]{1511, 1}); prof.pack.add(new int[]{1511, 1});
            SmartAdventurers.restoreItems(bot, prof);
            int balls = bot.getInventory().getAmount(WOOL), logs = bot.getInventory().getAmount(1511);
            boolean f3 = QuestRunner.KEEP_QUEST_ITEMS && QuestBook.ON && QuestRunner.FIX3;
            check("restoreItems: an open, unparked Sheep Shearer keeps " + (f3 ? "20 of 25 balls of wool in the pack" : "none (fix3 off)") + " (pack has " + balls + ", bank " + bot.getBank().getAmount(WOOL) + ")", balls == (f3 ? 20 : 0) && bot.getBank().getAmount(WOOL) == 0);
            boolean logsStay = SmartAdventurers.keepsInPack(1511) || !MarketBrain.tradeable(1511); // quest4: restoreItems' own rule for what is not a haul (in a real world logs have an equip id, so keepsInPack holds and they stay)
            log("restoreItems: logs in the pack " + logs + " (keepsInPack " + SmartAdventurers.keepsInPack(1511) + ", tradeable " + MarketBrain.tradeable(1511) + "), quest hold for logs " + QuestBook.holdCount(bot.getUsername(), 1511));
            check("restoreItems: the logs follow restoreItems' own keep rule, the quest hold adds none (pack has " + logs + ")", logs == (logsStay ? 2 : 0) && QuestBook.holdCount(bot.getUsername(), 1511) == 0);
        } catch (Throwable t) { check("world3 threw " + t, false); }
        finally {
            if (key != null) QuestBook.BOTS.remove(key);
            if (bot != null) try { bot.getInventory().clear(); bot.clear(); } catch (Throwable ignored) { }
        }
    }

    static final int ORANGE_MAIL = 286, BLUE_MAIL = 287, WOOL = 1759, COINS = 995;

    /** quest2: the pure halves of the three fixes (no world). */
    @SuppressWarnings("unchecked")
    static void fix2() {
        check("goblin: a banked orange mail is wanted in the pack before stage 20", QuestGoblinDiplomacy.madeWant(10, 20, 0, 1) == 1);
        check("goblin: one already in the pack is wanted too (the Collect checkpoint then holds)", QuestGoblinDiplomacy.madeWant(10, 20, 1, 0) == 1);
        check("goblin: none owned, nothing wanted (the dye chain runs)", QuestGoblinDiplomacy.madeWant(10, 20, 0, 0) == 0);
        check("goblin: shown already, nothing wanted", QuestGoblinDiplomacy.madeWant(20, 20, 0, 1) == 0 && QuestGoblinDiplomacy.madeWant(30, 30, 0, 1) == 0);
        check("goblin: the blue mail is still wanted at stage 20", QuestGoblinDiplomacy.madeWant(20, 30, 0, 1) == 1);
        check("useon: pack empty, bank has it, no click yet: one pulled", QuestSteps.UseOn.pullCount(0, 3, 0) == 1);
        check("useon: in the pack, nothing pulled", QuestSteps.UseOn.pullCount(1, 3, 0) == 0);
        check("useon: not in the bank, nothing pulled", QuestSteps.UseOn.pullCount(0, 0, 0) == 0);
        check("useon: used up by a click, nothing pulled", QuestSteps.UseOn.pullCount(0, 3, 1) == 0);
        check("p92: the same day's count is carried", P92.carry("2026-11-17", 2, "2026-11-17") == 2);
        check("p92: another day, a missing day or a bad count starts at 0", P92.carry("2026-11-16", 2, "2026-11-17") == 0 && P92.carry(null, 2, "2026-11-17") == 0 && P92.carry("2026-11-17", -1, "2026-11-17") == 0);
        String n = "zz_quest2_p92";
        try {
            P92.hand(n, "d1"); P92.hand(n, "d1");
            JSONObject j = new JSONObject(); P92.put(j, n, "d1");
            String text = j.toJSONString();
            P92.HANDED.remove(n); P92.DAY.remove(n);
            P92.read((JSONObject) new JSONParser().parse(text), n, "d1");
            check("p92: two hand-outs survive a save and load on the same day", P92.handed(n, "d1") == 2);
            check("p92: the next day starts at 0", P92.handed(n, "d2") == 0);
            P92.read((JSONObject) new JSONParser().parse(text), n, "d2");
            check("p92: yesterday's entry is not carried", P92.handed(n, "d2") == 0);
            JSONObject bad = new JSONObject(); bad.put("p92Day", 7); bad.put("p92Handed", "x"); P92.read(bad, n, "d2"); P92.read(null, n, "d2"); P92.read(new JSONObject(), n, "d2");
            check("p92: a malformed or missing entry reads as 0", P92.handed(n, "d2") == 0);
            JSONObject none = new JSONObject(); P92.put(none, n, "d2");
            check("p92: nothing written at 0", none.isEmpty());
        } catch (Throwable t) { check("p92 save and load threw " + t, false); }
        finally { P92.HANDED.remove(n); P92.DAY.remove(n); }
        try {
            QuestSteps.Step u = new QuestSteps.UseOn("t", 1965, new int[]{1}, null, core.game.interaction.IntType.SCENERY, null, 1);
            java.util.Set<Integer> k = new java.util.HashSet<>(); QuestRunner.collectIds(QuestBlackKnightsFortress.guard(u), k);
            java.util.Map<Integer, Integer> m = new java.util.HashMap<>(); QuestBook.needs(QuestBlackKnightsFortress.guard(u), m);
            check("guarded: collectIds and needs see inside a Guarded step only with -Dquest.fix2", k.contains(1965) == QuestRunner.FIX2 && m.containsKey(1965) == QuestRunner.FIX2);
            k.clear(); QuestRunner.collectIds(u, k);
            check("guarded: a bare UseOn is seen either way", k.contains(1965));
        } catch (Throwable t) { log("SKIP guarded checks offline (" + t + ")"); }
    }

    /** One bot's entry as snapshot() writes it (the keys the resume reads), then as text. */
    @SuppressWarnings("unchecked")
    static String save(QuestBook.BotQ b) {
        JSONObject j = new JSONObject();
        if (b.active != null) { j.put("active", b.active); j.put("step", b.step); j.put("stage", b.stage); }
        QuestBook.putGot(j, b);
        return j.toJSONString();
    }

    /** The entry as load() reads it. */
    static QuestBook.BotQ load(String text) throws Exception {
        JSONObject j = (JSONObject) new JSONParser().parse(text);
        QuestBook.BotQ b = new QuestBook.BotQ();
        if (j.get("active") != null) { b.active = String.valueOf(j.get("active")); b.step = ((Number) j.get("step")).intValue(); b.stage = j.get("stage") == null ? 0 : ((Number) j.get("stage")).intValue(); }
        QuestBook.readGot(j, b);
        return b;
    }

    static final String[] SLUG = {"goblindiplomacy"}; static final java.util.Set<Integer> RUN_GOT = new java.util.HashSet<>();
    /** resumeItems on plain maps: moves what startPull says from the bank to the pack (-1: this start is not for the saved quest). */
    static int pull(QuestBook.BotQ b, Map<Integer, Integer> pack, Map<Integer, Integer> bank) {
        int moved = 0;
        List<int[]> plan = QuestBook.startPull(b, SLUG[0], RUN_GOT, id -> pack.getOrDefault(id, 0), id -> bank.getOrDefault(id, 0)); // hunt-b: the same call QuestRunner.start -> onStart -> resumeItems makes
        if (plan == null) return -1;
        for (int[] t : plan) { bank.merge(t[0], -t[1], Integer::sum); pack.merge(t[0], t[1], Integer::sum); moved += t[1]; }
        return moved;
    }

    static void checks() throws Exception {
        // a run of Goblin Diplomacy stopped at step 17 holding the orange and the blue mail and 3 wool
        QuestBook.BotQ b = new QuestBook.BotQ();
        b.active = "goblindiplomacy"; b.step = 17; b.stage = 10; b.gotFor = "goblindiplomacy";
        b.got.put(ORANGE_MAIL, 1); b.got.put(BLUE_MAIL, 1); b.got.put(WOOL, 3);
        String text = save(b);
        check("saved text carries gotV, gotFor and got", text.contains("\"gotV\":1") && text.contains("\"gotFor\":\"goblindiplomacy\"") && text.contains("[286,1]") && text.contains("[1759,3]"));

        // memory cleared: only the text is left
        b = null;
        QuestBook.BotQ r = load(text);
        check("reload: same quest", "goblindiplomacy".equals(r.active));
        check("reload: same step", r.step == 17);
        check("reload: same stage", r.stage == 10);
        check("reload: got is for the same quest", "goblindiplomacy".equals(r.gotFor));
        check("reload: the three item ids with their amounts", r.got.size() == 3 && r.got.getOrDefault(ORANGE_MAIL, 0) == 1 && r.got.getOrDefault(BLUE_MAIL, 0) == 1 && r.got.getOrDefault(WOOL, 0) == 3);

        // between the runs the blue mail and 2 wool went to the bank; the bank also holds 40 unrelated wool
        Map<Integer, Integer> pack = new HashMap<>(), bank = new HashMap<>();
        pack.put(ORANGE_MAIL, 1); pack.put(WOOL, 1);
        bank.put(BLUE_MAIL, 1); bank.put(WOOL, 42); bank.put(1511, 500);
        int moved = pull(r, pack, bank);
        check("resume pull moved 3 items", moved == 3);
        check("resume: the pack holds every saved item again", pack.getOrDefault(ORANGE_MAIL, 0) == 1 && pack.getOrDefault(BLUE_MAIL, 0) == 1 && pack.getOrDefault(WOOL, 0) == 3);
        check("resume: no more than the saved amount left the bank", bank.getOrDefault(WOOL, 0) == 40 && bank.getOrDefault(BLUE_MAIL, 0) == 0 && bank.getOrDefault(1511, 0) == 500);
        check("resume twice moves nothing more", pull(r, pack, bank) == 0);
        check("hunt-b: the run knows the three saved ids again", RUN_GOT.size() == 3 && RUN_GOT.contains(ORANGE_MAIL) && RUN_GOT.contains(BLUE_MAIL) && RUN_GOT.contains(WOOL));
        { // hunt-b: a quest picked again after a park or a set-aside has no active entry: the start still pulls; another quest never does
            QuestBook.BotQ aside = load(text); aside.active = null; aside.step = 0; RUN_GOT.clear();
            Map<Integer, Integer> p4 = new HashMap<>(), b4 = new HashMap<>(); b4.put(BLUE_MAIL, 1);
            check("hunt-b: picked again (no active entry): the start pulls", pull(aside, p4, b4) == 1 && p4.getOrDefault(BLUE_MAIL, 0) == 1 && RUN_GOT.contains(BLUE_MAIL));
            SLUG[0] = "cooks"; RUN_GOT.clear(); b4.put(WOOL, 5);
            check("hunt-b: a start of another quest pulls nothing and learns nothing", pull(aside, p4, b4) == -1 && b4.get(WOOL) == 5 && RUN_GOT.isEmpty());
            SLUG[0] = "goblindiplomacy";
        }

        // the bank has less than was saved: take what there is, never go negative
        Map<Integer, Integer> p2 = new HashMap<>(), b2 = new HashMap<>();
        b2.put(WOOL, 2);
        check("short bank: takes the 2 it has", pull(r, p2, b2) == 2 && p2.getOrDefault(WOOL, 0) == 2 && b2.getOrDefault(WOOL, 0) == 0 && !p2.containsKey(BLUE_MAIL));
        // the pack already holds more than was saved: nothing moves
        Map<Integer, Integer> p3 = new HashMap<>(), b3 = new HashMap<>();
        p3.put(WOOL, 9); p3.put(ORANGE_MAIL, 1); p3.put(BLUE_MAIL, 2); b3.put(WOOL, 5);
        check("full pack: nothing taken", pull(r, p3, b3) == 0 && b3.get(WOOL) == 5);

        // tolerant reads
        check("missing got: empty, no error", load("{\"active\":\"cooks\",\"step\":3,\"stage\":10}").got.isEmpty() && load("{\"active\":\"cooks\",\"step\":3,\"stage\":10}").step == 3);
        check("no active quest: empty entry", load("{}").active == null && load("{}").got.isEmpty());
        check("other version: ignored", load("{\"gotV\":2,\"gotFor\":\"cooks\",\"got\":[[1944,1]]}").got.isEmpty());
        check("corrupt got (a string): ignored", load("{\"gotV\":1,\"gotFor\":\"cooks\",\"got\":\"x\"}").got.isEmpty());
        QuestBook.BotQ bad = load("{\"active\":\"cooks\",\"step\":2,\"gotV\":1,\"gotFor\":\"cooks\",\"got\":[[1944,1],[\"a\",1]]}");
        check("corrupt got (a bad pair): all of it dropped, the step kept", bad.got.isEmpty() && bad.gotFor == null && bad.step == 2);
        QuestBook.BotQ odd = load("{\"gotV\":1,\"gotFor\":\"cooks\",\"got\":[[995,50],[1944,0],[-4,2],[1927,1]]}");
        check("coins, zero amounts and bad ids are not kept", odd.got.size() == 1 && odd.got.getOrDefault(1927, 0) == 1);

        // nothing to keep: nothing written
        QuestBook.BotQ e = new QuestBook.BotQ();
        e.active = "cooks"; e.step = 1;
        check("empty got writes no got keys", !save(e).contains("got"));
        e.got.put(COINS + 1, 1);
        check("got without a quest name writes no got keys", !save(e).contains("got"));

        check("planPull is in id order", order(QuestBook.planPull(r.got, id -> 0, id -> 10)));
    }

    static boolean order(List<int[]> l) { for (int i = 1; i < l.size(); i++) if (l.get(i - 1)[0] >= l.get(i)[0]) return false; return l.size() == 3; }

    /** In a world: the flag wiring and the M3 hold count against the real scripts (a made-up bot name, removed afterwards). */
    static void world() {
        String ex = System.getProperty("questresume.expect");
        log("quest.resume=" + QuestBook.RESUME + " quest.keepQuestItems=" + QuestRunner.KEEP_QUEST_ITEMS);
        if (ex != null) check("QuestBook.RESUME is " + ex, QuestBook.RESUME == "on".equals(ex));
        check("RESUME follows -Dquest.resume", QuestBook.RESUME == Boolean.getBoolean("quest.resume"));
        QuestBook.registerWave1();
        String name = "zz_questresume_test";
        try {
            QuestBook.BotQ b = new QuestBook.BotQ();
            b.active = "sheep"; b.step = 3; b.gotFor = "sheep"; b.got.put(ORANGE_MAIL, 1);
            QuestBook.BOTS.put(name, b);
            int wool = QuestBook.holdCount(name, WOOL), mail = QuestBook.holdCount(name, ORANGE_MAIL), logs = QuestBook.holdCount(name, 1511), coins = QuestBook.holdCount(name, COINS);
            if (QuestRunner.KEEP_QUEST_ITEMS && QuestBook.ON) {
                check("M3: Sheep Shearer holds its 20 wool at login (got " + wool + ")", wool == 20);
                check("M3: a saved run item is held", mail == 1);
                check("M3: logs and coins are not held", logs == 0 && coins == 0);
                b.active = null;
                check("M3: no quest, nothing held", QuestBook.holdCount(name, WOOL) == 0);
                b.parked.put("sheep", System.currentTimeMillis() + 60_000L);
                check("M3: a parked quest still holds its items", QuestBook.holdCount(name, WOOL) == 20 && QuestBook.holdCount(name, ORANGE_MAIL) == 1);
            } else check("M3 off (keepQuestItems or quests off): nothing held", wool == 0 && mail == 0 && logs == 0 && coins == 0);
            check("M3: an unknown bot holds nothing", QuestBook.holdCount("zz_nobody_here", WOOL) == 0);
            String snap = QuestBook.snapshot();
            check("snapshot writes got only with the flag", snap.contains("\"gotFor\":\"sheep\"") == QuestBook.RESUME);
        } finally { QuestBook.BOTS.remove(name); }
    }
}
