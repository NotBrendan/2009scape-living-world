package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.quest.Quest;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The quests bots can do (wave 1: Cook's Assistant, Sheep Shearer, Doric's Quest, Witch's Potion, Imp Catcher), which
 * one a bot should pick, the caps that keep it from turning into a quest-bot server, and data/quests/quests.json:
 * every bot's active quest and checkpoint, parked quests, completions and its saved attributes, so a restart picks
 * up where it was and never pays a reward twice.
 *
 * Flags: -Dquests=false off; -Dquests.coinCap (100000/UTC day); -Dquests.share (0.15 of online adventurers);
 * -Dquests.only (trials: bots pick a quest whenever one is eligible); -Dquests.noseed (trials: don't seed scripted quests);
 * -Dquests.test=SLUG (one fresh bot at the start, every step logged); -Dquests.testItems (cooks: the three items up front);
 * -Dquests.noStock (never buy quest goods from bot stock).
 */
public final class QuestBook implements StartupListener {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("quests", "true"));
    static final long COIN_CAP = Long.getLong("quests.coinCap", 100_000L);
    static final double SHARE = Double.parseDouble(System.getProperty("quests.share", "0.15"));
    static final boolean ONLY = Boolean.getBoolean("quests.only");
    static final Random RNG = new Random();

    static final Map<String, QuestScript> SCRIPTS = new LinkedHashMap<>();

    static final class BotQ {
        String active; int step; int stage;
        final Map<String, Long> parked = new HashMap<>();
        final Map<String, Integer> stages = new HashMap<>();
        final Map<String, Object[]> attrs = new HashMap<>(); // key (without /save:) -> {type, value}
        final List<int[]> pack = new ArrayList<>(), bank = new ArrayList<>(); // test bots only (adventurers keep theirs in smartbots.json)
        int completions, shifts; long lastFinish; boolean test; int[] tile; // tile: test bots come back where they were
        final Map<String, List<Long>> deaths = new HashMap<>(); // audit 10-23 Q-20: slug -> times this bot died on that quest (last 24 h)
    }
    static final Map<String, BotQ> BOTS = new ConcurrentHashMap<>();
    /** slug -> UTC day -> {starts, completions, failures} */
    static final Map<String, Map<String, int[]>> LOG = new ConcurrentHashMap<>();
    static String coinDay = ""; static long coinsToday; static boolean capLogged;
    static int hStarted, hFinished, hParked; static long hCoins;

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }
    static String today() { return LocalDate.now(ZoneOffset.UTC).toString(); }
    static File dir() { return new File(ServerConstants.DATA_PATH, "quests"); }
    static File file() { return new File(dir(), "quests.json"); }

    static void register(java.util.function.Supplier<QuestScript> build) { QuestScript s = build.get(); s.factory = build; SCRIPTS.put(s.slug, s); }

    static synchronized void registerWave1() {
        if (!SCRIPTS.isEmpty()) return;
        register(QuestCooksAssistant::build);
        register(QuestSheepShearer::build);
        register(QuestDoricsQuest::build);
        register(QuestWitchsPotion::build);
        register(QuestImpCatcher::build);
        register(QuestRuneMysteries::build);
        register(QuestRomeoAndJuliet::build);
        register(QuestRestlessGhost::build);
        register(QuestErnestTheChicken::build);
        register(QuestGoblinDiplomacy::build);
        register(QuestPrinceAliRescue::build);
        register(QuestVampireSlayer::build);
        register(QuestKnightsSword::build);
        register(QuestPiratesTreasure::build);
        register(QuestBlackKnightsFortress::build);
        register(QuestShieldOfArrav::build);
        register(QuestDruidicRitual::build);
        for (String off : System.getProperty("quests.off", OFF_DEFAULT).split(",")) { QuestScript q = SCRIPTS.get(off.trim()); if (q != null) q.enabled = false; } // scripts not yet proven from an empty pack ship switched off
    }

    /** Scripts off unless -Dquests.off says otherwise (empty = all on). */
    static final String OFF_DEFAULT = ""; // Black Knights', Prince Ali and Ernest passed their trials 10-23 (morning review C1, C3) // one bot can only join one gang: parked until a two-bot crew or a Black Arm half-certificate source exists (2026-10-23)

    static QuestScript byQuest(content.data.Quests q) { for (QuestScript s : SCRIPTS.values()) if (s.quest == q) return s; return null; }

    @Override
    public void startup() {
        if (!ON) { System.out.println("[Quests] off (-Dquests=false)"); return; }
        registerWave1();
        load();
        StringBuilder offList = new StringBuilder(); for (QuestScript q : SCRIPTS.values()) if (!q.enabled) offList.append(offList.length() > 0 ? "," : "").append(q.slug);
        System.out.println("[Quests] " + SCRIPTS.size() + " scripts loaded (waves 1-2)" + (offList.length() > 0 ? ", off: " + offList : "") + ", coin cap " + COIN_CAP + "/day" + (ONLY ? ", quests.only" : "") + (Boolean.getBoolean("quests.noseed") ? ", noseed" : ""));
        java.util.Timer t = new java.util.Timer("QuestBook", true);
        long every = SmartAdventurers.FAST ? 60_000 : 600_000;
        t.schedule(new java.util.TimerTask() { public void run() { saveSoon(true); } }, every, every); // the snapshot is taken on the game thread, the file written off it (audit 10-22 Q-H2)
        t.schedule(new java.util.TimerTask() { public void run() { hourly(); } }, 3_600_000, 3_600_000);
        String test = System.getProperty("quests.test");
        String probe = System.getProperty("quests.probe");
        if (probe != null) GameWorld.getPulser().submit(new Pulse(10) { @Override public boolean pulse() { try { probe(probe); } catch (Throwable e) { System.out.println("[Quests] probe failed: " + e); } return true; } });
        if (test != null) GameWorld.getPulser().submit(new Pulse(30) { @Override public boolean pulse() { try { for (String one : test.split(",")) spawnTest(one.trim()); } catch (Throwable e) { System.out.println("[Quests] test spawn failed: " + e); e.printStackTrace(System.out); } return true; } });
    }

    static synchronized void hourly() {
        System.out.println("[Quests] hour: started " + hStarted + ", finished " + hFinished + ", parked " + hParked + ", coins paid " + coinsToday + "/" + COIN_CAP + " today (" + hCoins + " this hour), questing now " + QuestRunner.ACTIVE.size());
        hStarted = hFinished = hParked = 0; hCoins = 0;
    }

    // ------------------------------------------------------------------ choosing

    static BotQ q(Player p) { return BOTS.computeIfAbsent(key(p), k -> new BotQ()); }

    static int[] dayLog(String slug) { return LOG.computeIfAbsent(slug, k -> new ConcurrentHashMap<>()).computeIfAbsent(today(), k -> new int[3]); }

    static boolean parked(Player p, QuestScript s) { BotQ b = BOTS.get(key(p)); Long until = b == null ? null : b.parked.get(s.slug); return until != null && until > System.currentTimeMillis(); }

    static int adventurersOnline() { int n = 0; for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) if (b.bot != null && b.bot.isActive()) n++; return n; }

    /** Scripts switched off by their failure rate, and the day: back on at midnight UTC. */
    static final Map<String, String> DISABLED_TODAY = new ConcurrentHashMap<>();
    static final java.util.Set<String> DISABLED_NOTED = ConcurrentHashMap.newKeySet(); // batch 19c B1: slug|day already recorded as quest-disabled-today
    static final java.util.Set<String> FINISHED = ConcurrentHashMap.newKeySet(); // batch 19c B1: bot|slug finished this boot (reward-twice)

    static void reenable() {
        for (Map.Entry<String, String> e : DISABLED_TODAY.entrySet()) if (!e.getValue().equals(today())) { QuestScript q = SCRIPTS.get(e.getKey()); if (q != null) q.enabled = true; DISABLED_TODAY.remove(e.getKey()); System.out.println("[Quests] " + e.getKey() + " back on for a new day"); }
    }

    static synchronized boolean coinCapHit() {
        if (!today().equals(coinDay)) { coinDay = today(); coinsToday = 0; capLogged = false; }
        return coinsToday >= COIN_CAP;
    }

    static List<QuestScript> eligible(Player bot) {
        List<QuestScript> out = new ArrayList<>();
        if (!ON || bot == null) return out;
        if (!ONLY && QuestRunner.ACTIVE.size() >= Math.max(1, (int) Math.floor(adventurersOnline() * SHARE))) return out;
        reenable();
        boolean capped = coinCapHit();
        for (QuestScript s : SCRIPTS.values()) {
            if (!s.enabled || s.complete(bot) || parked(bot, s) || !s.requirementsMet(bot)) continue;
            if (s.quest == content.data.Quests.SHIELD_OF_ARRAV && !QuestShieldOfArrav.mayStart(bot)) continue; // batch 17 items1-2: Baraek's coins, and a Black Arm bot only when key/crossbows/a Phoenix partner exist
            if (s.coins > 0 && capped) { if (!capLogged) { capLogged = true; System.out.println("[Quests] coin cap " + COIN_CAP + " reached for today: coin-paying quests are off until midnight UTC"); } continue; }
            int near = 0;
            for (QuestRunner r : QuestRunner.ACTIVE.values()) if (r.script == s && s.start != null && (r.bot.getLocation().getDistance(s.start) <= 30 || r.stage() == 0)) near++; // there, or still on the way to the giver
            if (near >= s.maxNear) continue;
            out.add(s);
        }
        return out;
    }

    static double weight(Player bot, QuestScript s) {
        double w = (s.coins + 0.9 * s.itemValue() + 0.05 * s.usefulXp + s.unlockValue) / (1 + s.estMinutes / 30.0);
        BotQ b = BOTS.get(key(bot));
        if (b == null || b.shifts < 3) w *= 1.3; // new players did the starter quests
        if (b != null && System.currentTimeMillis() - b.lastFinish < 3_600_000L) w *= 0.5; // one just now: back to work for a bit
        if (s.quest == content.data.Quests.IMP_CATCHER) { int beads = 0; for (int id : new int[]{1470, 1472, 1474, 1476}) if (bot.getInventory().getAmount(id) + bot.getBank().getAmount(id) > 0) beads++; if (beads < 2) w *= 0.3; }
        return w;
    }

    /** chooseJob's quest offer: the best eligible quest if it beats the best job (always, under -Dquests.only). */
    static QuestScript pick(Player bot, double bestJobScore, boolean broke) {
        if (shiftOver(bot)) return null; // no new quest at the end of a shift
        QuestScript best = null; double bw = 0;
        for (QuestScript s : eligible(bot)) {
            if (broke && s.coins <= 0) continue; // a skint bot only does quests that pay
            double w = weight(bot, s) * (0.8 + RNG.nextDouble() * 0.4);
            if (w > bw) { bw = w; best = s; }
        }
        if (best == null) return null;
        if (ONLY || bw > bestJobScore) return best;
        return null;
    }

    static boolean shiftOver(Player bot) {
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        return br != null && System.currentTimeMillis() > br.shiftEnd;
    }

    /** Has a quest on the go (it may be paused for a grave run or a break)? */
    static boolean midQuest(Player bot) {
        if (bot == null) return false;
        if (QuestRunner.active(bot)) return true;
        BotQ b = BOTS.get(key(bot));
        return b != null && b.active != null;
    }

    /** Back after a break or a restart with a quest half done: carry on with it first. */
    static boolean resume(SmartAdventurers.Brain b) {
        if (!ON || b.bot == null || QuestRunner.active(b.bot)) return false;
        if (System.currentTimeMillis() > b.shiftEnd) return false; // stopped for the end of a shift: log off first, carry on next shift (audit Q-M1)
        BotQ q = BOTS.get(key(b.bot));
        if (q == null || q.active == null) return false;
        QuestScript s = SCRIPTS.get(q.active);
        if (s == null || !s.enabled || s.complete(b.bot) || parked(b.bot, s)) {
            if (Watchdog.FAIL_ON) try { String why = s == null ? "missing" : !s.enabled ? "disabled" : s.complete(b.bot) ? "complete" : "parked"; Watchdog.fev("quest", b.bot, "quest_resume", "resume-dropped", q.active + " " + why, null, Watchdog.ctx("quest", q.active, "savedStep", q.step, "why", why)); } catch (Throwable ignored) { } // batch 19c B1
            q.active = null; return false;
        }
        int saved = q.step; // batch 19c B1: started() resets it
        QuestRunner r = QuestRunner.start(b.bot, s, 0, false); // from the top: every finished step's checkpoint holds and is skipped, and a bot back in Lumbridge walks to the giver again
        if (r != null) r.savedStep = saved; // batch 19c B1: checkpoint-regressed reads it at the first open step
        return true;
    }

    // ------------------------------------------------------------------ bookkeeping from QuestRunner

    static synchronized void started(Player bot, QuestScript s, int idx) {
        BotQ b = q(bot); b.active = s.slug; b.step = idx; b.stage = s.stage(bot);
        if (idx == 0) { dayLog(s.slug)[0]++; hStarted++; Economy.QUEST_STARTS.incrementAndGet(); } // audit 10-23 batch14 item5: counted for economy.json
    }

    static synchronized void checkpoint(Player bot, QuestScript s, int idx) {
        BotQ b = q(bot); b.active = s.slug; b.step = idx; b.stage = s.stage(bot);
        if (b.stage > 0) b.stages.put(s.quest.name(), b.stage);
    }

    static synchronized void finished(Player bot, QuestScript s, long coins) {
        BotQ b = q(bot);
        if (Watchdog.FAIL_ON) try { if (FINISHED.size() > 20_000) FINISHED.clear(); if (!FINISHED.add(key(bot) + "|" + s.slug)) Watchdog.fev("quest", bot, "quest_reward", "reward-twice", "finished again", null, Watchdog.ctx("quest", s.slug, "completions", b.completions, "paid", coins)); } catch (Throwable ignored) { } // batch 19c B1 [Fable]: a second finish of this quest by this bot this boot (the saved stage 100 is set by checkpoint() just before every real finish, so "stage 100 before the call" would fire on all of them; a finish after a restart is QuestRunner's complete-at-the-start clause)
        b.active = null; b.step = 0; b.completions++; b.lastFinish = System.currentTimeMillis();
        b.stages.put(s.quest.name(), Math.max(100, s.stage(bot)));
        dayLog(s.slug)[1]++; hFinished++; Economy.QUEST_DONE.incrementAndGet(); // audit 10-23 batch14 item5
        coinCapHit(); coinsToday += coins; hCoins += coins;
        captureAttrs(bot, b);
        saveSoon(false); // a crash before the 10-minute save must not let it do (and be paid for) the quest again (audit Q-M4)
    }

    /** audit 10-23 Q-20: deaths on one quest within a day before it is parked for 24 h (resume() then drops it). */
    static final int DEATHS_TO_PARK = Integer.getInteger("quests.deathsToPark", 2);

    /** audit 10-23 Q-20: a bot died mid-quest. The checkpoint is kept for the grave run, but a second death on the same quest in a day parks it. */
    static synchronized void questDeath(Player bot, QuestScript s) {
        BotQ b = q(bot); long now = System.currentTimeMillis();
        List<Long> l = b.deaths.computeIfAbsent(s.slug, k -> new ArrayList<>());
        l.removeIf(t -> t < now - 24 * 3_600_000L); l.add(now);
        if (l.size() < DEATHS_TO_PARK) { System.out.println("[Quests] " + bot.getUsername() + " " + s.slug + ": death " + l.size() + " of " + DEATHS_TO_PARK + " today on this quest"); return; }
        b.parked.put(s.slug, now + 24 * 3_600_000L);
        if (s.slug.equals(b.active)) { b.active = null; b.step = 0; }
        int st = s.stage(bot); if (st > 0) b.stages.put(s.quest.name(), st);
        int[] d = dayLog(s.slug); d[2]++; hParked++;
        System.out.println("[Quests] " + bot.getUsername() + " " + s.slug + ": parked for 24 h after " + l.size() + " deaths today (audit 10-23 Q-20)");
        saveSoon(false);
    }

    static synchronized void abandoned(Player bot, QuestScript s) {
        BotQ b = q(bot); b.active = null; b.step = 0;
        b.parked.put(s.slug, System.currentTimeMillis() + 24 * 3_600_000L);
        int st = s.stage(bot); if (st > 0) b.stages.put(s.quest.name(), st);
        int[] d = dayLog(s.slug); d[2]++; hParked++;
        if (d[2] >= 3 && d[2] > 0.3 * (d[1] + d[2])) { s.enabled = false; DISABLED_TODAY.put(s.slug, today()); System.out.println("[Quests] " + s.display + " disabled for today: " + d[2] + " abandoned, " + d[1] + " finished (over 30% failures)");
            if (Watchdog.FAIL_ON && DISABLED_NOTED.add(s.slug + "|" + today())) try { Watchdog.fev("quest", null, "quest_rate", "quest-disabled-today", d[2] + " abandoned, " + d[1] + " finished", null, Watchdog.ctx("quest", s.slug, "abandoned", d[2], "finished", d[1])); } catch (Throwable ignored) { } } // batch 19c B1: server-level, one per quest per day
        saveSoon(false);
    }

    /** 20 completions and under 10% failures over the last 7 days: the script works, so it can replace the seeded flag. */
    static boolean proven(QuestScript s) {
        Map<String, int[]> m = LOG.get(s.slug);
        if (m == null) return false;
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(7).toString();
        int done = 0, failed = 0;
        for (Map.Entry<String, int[]> e : m.entrySet()) if (e.getKey().compareTo(from) >= 0) { done += e.getValue()[1]; failed += e.getValue()[2]; }
        return done >= 20 && failed < 0.1 * (done + failed);
    }

    /** Gates.seedQuests: leave this quest unseeded so the bot does it for real? */
    static synchronized boolean skipSeed(Player bot, content.data.Quests quest) {
        if (!ON) return false;
        QuestScript s = byQuest(quest);
        if (s == null) return false;
        if (Boolean.getBoolean("quests.noseed")) return true;
        BotQ b = BOTS.get(key(bot));
        if (b != null && (s.slug.equals(b.active) || b.stages.containsKey(quest.name()))) return true; // it's doing it, or did it for real: its own stage counts
        return proven(s);
    }

    // ------------------------------------------------------------------ attributes and stages

    /** Game thread only: reads the bot's live attribute map. Built aside and swapped in, so a failure never leaves half a map. */
    static void captureAttrs(Player p, BotQ b) {
        try {
            core.game.node.entity.impl.GameAttributes ga = p.getGameAttributes();
            Map<String, Object[]> fresh = new HashMap<>();
            for (String k : new ArrayList<>(ga.getSavedAttributes())) {
                Object v = ga.getAttributes().get(k);
                if (v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite())) continue;
                String t = v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Boolean ? "b" : v instanceof Double ? "d" : v instanceof String ? "s" : null;
                if (t != null) fresh.put(k, new Object[]{t, v});
            }
            b.attrs.clear(); b.attrs.putAll(fresh);
        } catch (Throwable t) { System.out.println("[Quests] attributes of " + p.getUsername() + ": " + t); }
        for (QuestScript s : SCRIPTS.values()) { int st = s.stage(p); if (st > 0) b.stages.put(s.quest.name(), st); }
    }

    /** Game thread: keep a stopping bot's attributes (Fred's wool count) without waiting for the 10-minute save. */
    static synchronized void captureSoon(Player p) { if (ON && p != null) captureAttrs(p, q(p)); }

    /** A bot that came back under another name (restore couldn't get the old one): its quest record follows it, as Ledger.rename does for lots. */
    static synchronized void rename(String from, String to) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return;
        BotQ b = BOTS.remove(from.toLowerCase(Locale.ROOT));
        if (b != null) BOTS.put(to.toLowerCase(Locale.ROOT), b);
    }

    /** At restore: saved attributes first, then the quest stages (after levels and XP, before seedQuests). Never pays rewards. */
    static synchronized void restore(Player bot) {
        try { restore0(bot); }
        catch (Throwable t) { if (Watchdog.FAIL_ON) try { Watchdog.fev("quest", bot, "quest_resume", "restore-failed", t.getClass().getSimpleName(), null, Watchdog.ctx("exc", t.getClass().getName(), "msg", String.valueOf(t.getMessage()).length() > 120 ? String.valueOf(t.getMessage()).substring(0, 120) : String.valueOf(t.getMessage()))); } catch (Throwable ignored) { } throw t; } // batch 19c B1: recorded, then the caller's catch (SmartAdventurers.restore) as before
    }

    static void restore0(Player bot) {
        if (!ON) return;
        registerWave1();
        BotQ b = BOTS.get(key(bot));
        if (b == null) return;
        b.shifts++;
        for (Map.Entry<String, Object[]> e : new ArrayList<>(b.attrs.entrySet())) {
            try {
                Object[] tv = e.getValue(); Object v = tv[1];
                if (v == null) continue;
                switch (String.valueOf(tv[0])) {
                    case "i": v = ((Number) v).intValue(); break;
                    case "l": v = ((Number) v).longValue(); break;
                    case "d": v = ((Number) v).doubleValue(); break;
                    case "b": v = Boolean.valueOf(String.valueOf(v)); break;
                    default: v = String.valueOf(v);
                }
                bot.setAttribute("/save:" + e.getKey(), v);
            } catch (Throwable ignored) { } // one bad attribute never stops the stages below (audit Q-M3)
        }
        int n = 0;
        for (Map.Entry<String, Integer> e : b.stages.entrySet()) {
            try {
                Quest q = bot.getQuestRepository().getQuest(content.data.Quests.valueOf(e.getKey()));
                if (q == null) continue;
                bot.getQuestRepository().setStageNonmonotonic(q, e.getValue());
                n++;
            } catch (Throwable t) { System.out.println("[Quests] stage " + e.getKey() + " for " + bot.getUsername() + ": " + t); }
        }
        try { bot.getQuestRepository().syncPoints(); } catch (Throwable ignored) { }
        if (n > 0 || b.active != null) System.out.println("[Quests] " + bot.getUsername() + " restored " + n + " quest stages, " + b.attrs.size() + " attributes" + (b.active != null ? ", resumes " + b.active + " at step " + b.step : ""));
    }

    // ------------------------------------------------------------------ quests.json

    /** Shutdown (the game loop has stopped): snapshot and write here. */
    static void save() {
        if (!ON) return;
        long at = System.nanoTime();
        write(snapshot(), at);
    }

    static volatile long lastSoon;
    /** audit 10-23 Q-22: a save asked for inside the 30 s throttle is put off to the end of it, never dropped. */
    static final java.util.concurrent.atomic.AtomicBoolean SOON_PENDING = new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.Timer SOON_TIMER = new java.util.Timer("QuestBook-soon", true);

    /**
     * Snapshot on the game thread (the bots' attribute maps are only safe to read there), write the file on a short-lived
     * thread. From the timer (always) or right after a finish or an abandon (at most every 30 s).
     */
    static void saveSoon(boolean always) {
        if (!ON) return;
        long now = System.currentTimeMillis();
        if (!always && now - lastSoon < 30_000) {
            if (SOON_PENDING.compareAndSet(false, true)) // audit 10-23 Q-22: it used to be dropped (a finish could wait 10 min to reach the file)
                try { SOON_TIMER.schedule(new java.util.TimerTask() { public void run() { SOON_PENDING.set(false); saveSoon(true); } }, Math.max(1, lastSoon + 30_000 - now)); } catch (Throwable e) { SOON_PENDING.set(false); }
            return;
        }
        lastSoon = now;
        try {
            GameWorld.getPulser().submit(new Pulse(1) {
                @Override public boolean pulse() {
                    try { final long at = System.nanoTime(); final String json = snapshot(); if (json != null) new Thread(() -> write(json, at), "QuestBook-save").start(); } catch (Throwable e) { System.out.println("[Quests] save failed: " + e); }
                    return true;
                }
            });
        } catch (Throwable e) { System.out.println("[Quests] save not queued: " + e); }
    }

    static final Object WRITE = new Object();

    static long writtenAt = Long.MIN_VALUE;
    static volatile long savedAt; // batch 19c (design 10 save-stale): wall time of the last successful quests.json write (0 = none this boot)

    /** audit 10-23 Q-22: builtAt is when the snapshot was taken; an older snapshot never lands after a newer one (save threads can finish out of order). */
    static void write(String json, long builtAt) {
        if (json == null) return;
        synchronized (WRITE) {
            if (builtAt < writtenAt) { System.out.println("[Quests] an older quests.json snapshot was not written over a newer one (audit 10-23 Q-22)"); return; }
            writtenAt = builtAt;
            try { dir().mkdirs(); Ledger.writeAtomic(file(), json); savedAt = System.currentTimeMillis(); } catch (Throwable t) { System.out.println("[Quests] save failed: " + t); if (Watchdog.FAIL_ON) try { Failures.Life.saveFailed("quests.json", t); } catch (Throwable ignored) { } } // batch 19c: design 10 save-failed; savedAt for save-stale
        }
    }

    @SuppressWarnings("unchecked")
    static synchronized String snapshot() {
        for (QuestRunner r : QuestRunner.ACTIVE.values()) { BotQ b = q(r.bot); b.active = r.script.slug; b.step = r.idx; b.stage = r.stage(); }
        for (Map.Entry<String, BotQ> e : BOTS.entrySet()) {
            Player p = Ledger.Wallet.findOnline(e.getKey());
            if (p == null) continue;
            BotQ b = e.getValue();
            captureAttrs(p, b);
            if (b.test) { Location l = p.getLocation(); b.tile = new int[]{l.getX(), l.getY(), l.getZ()}; b.pack.clear(); b.bank.clear(); for (Item it : p.getInventory().toArray()) if (it != null) b.pack.add(new int[]{it.getId(), it.getAmount()}); for (Item it : p.getBank().toArray()) if (it != null) b.bank.add(new int[]{it.getId(), it.getAmount()}); }
        }
        JSONObject root = new JSONObject();
        JSONObject bots = new JSONObject();
        for (Map.Entry<String, BotQ> e : BOTS.entrySet()) {
            BotQ b = e.getValue(); JSONObject j = new JSONObject();
            if (b.active != null) { j.put("active", b.active); j.put("step", b.step); j.put("stage", b.stage); }
            JSONObject pk = new JSONObject(); long now = System.currentTimeMillis(); for (Map.Entry<String, Long> x : b.parked.entrySet()) if (x.getValue() > now) pk.put(x.getKey(), x.getValue()); j.put("parked", pk);
            JSONObject st = new JSONObject(); st.putAll(b.stages); j.put("stages", st);
            JSONObject at = new JSONObject(); for (Map.Entry<String, Object[]> x : b.attrs.entrySet()) { JSONArray a = new JSONArray(); a.add(x.getValue()[0]); a.add(x.getValue()[1]); at.put(x.getKey(), a); } j.put("attrs", at);
            j.put("completions", b.completions); j.put("lastFinish", b.lastFinish); j.put("shifts", b.shifts);
            if (!b.deaths.isEmpty()) { JSONObject dd = new JSONObject(); long cut = now - 24 * 3_600_000L; for (Map.Entry<String, List<Long>> x : b.deaths.entrySet()) { JSONArray a = new JSONArray(); for (Long t : x.getValue()) if (t != null && t >= cut) a.add(t); if (!a.isEmpty()) dd.put(x.getKey(), a); } if (!dd.isEmpty()) j.put("questDeaths", dd); } // audit 10-23 Q-20
            if (b.test) { if (b.tile != null) { JSONArray t = new JSONArray(); for (int v : b.tile) t.add(v); j.put("tile", t); } j.put("test", true); j.put("pack", items(b.pack)); j.put("bank", items(b.bank)); }
            bots.put(e.getKey(), j);
        }
        root.put("bots", bots);
        JSONObject log = new JSONObject();
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(14).toString();
        for (Map.Entry<String, Map<String, int[]>> e : LOG.entrySet()) { JSONObject d = new JSONObject(); for (Map.Entry<String, int[]> x : e.getValue().entrySet()) if (x.getKey().compareTo(from) >= 0) { JSONArray a = new JSONArray(); for (int v : x.getValue()) a.add(v); d.put(x.getKey(), a); } log.put(e.getKey(), d); }
        root.put("log", log);
        root.put("coinDay", coinDay); root.put("coinsToday", coinsToday);
        return root.toJSONString();
    }

    @SuppressWarnings("unchecked")
    static JSONArray items(List<int[]> l) { JSONArray a = new JSONArray(); for (int[] i : l) { JSONArray x = new JSONArray(); x.add(i[0]); x.add(i[1]); a.add(x); } return a; }

    static void readItems(Object o, List<int[]> into) { if (o instanceof JSONArray) for (Object x : (JSONArray) o) { JSONArray a = (JSONArray) x; into.add(new int[]{((Number) a.get(0)).intValue(), ((Number) a.get(1)).intValue()}); } }

    static synchronized void load() {
        File f = file();
        if (!f.exists()) return;
        try (FileReader r = new FileReader(f)) {
            JSONObject root = (JSONObject) new JSONParser().parse(r);
            JSONObject bots = (JSONObject) root.get("bots");
            if (bots != null) for (Object k : bots.keySet()) {
                JSONObject j = (JSONObject) bots.get(k); BotQ b = new BotQ();
                if (j.get("active") != null) { b.active = String.valueOf(j.get("active")); b.step = ((Number) j.get("step")).intValue(); b.stage = j.get("stage") == null ? 0 : ((Number) j.get("stage")).intValue(); }
                JSONObject pk = (JSONObject) j.get("parked"); if (pk != null) for (Object x : pk.keySet()) b.parked.put(String.valueOf(x), ((Number) pk.get(x)).longValue());
                JSONObject st = (JSONObject) j.get("stages"); if (st != null) for (Object x : st.keySet()) b.stages.put(String.valueOf(x), ((Number) st.get(x)).intValue());
                JSONObject at = (JSONObject) j.get("attrs"); if (at != null) for (Object x : at.keySet()) { JSONArray a = (JSONArray) at.get(x); b.attrs.put(String.valueOf(x), new Object[]{a.get(0), a.get(1)}); }
                if (j.get("completions") != null) b.completions = ((Number) j.get("completions")).intValue();
                if (j.get("lastFinish") != null) b.lastFinish = ((Number) j.get("lastFinish")).longValue();
                if (j.get("shifts") != null) b.shifts = ((Number) j.get("shifts")).intValue();
                if (j.get("questDeaths") instanceof JSONObject) for (Object x : ((JSONObject) j.get("questDeaths")).keySet()) { Object a = ((JSONObject) j.get("questDeaths")).get(x); if (a instanceof JSONArray) { List<Long> l = new ArrayList<>(); for (Object t : (JSONArray) a) if (t instanceof Number) l.add(((Number) t).longValue()); b.deaths.put(String.valueOf(x), l); } } // audit 10-23 Q-20
                if (j.get("tile") instanceof JSONArray) { JSONArray t = (JSONArray) j.get("tile"); b.tile = new int[]{((Number) t.get(0)).intValue(), ((Number) t.get(1)).intValue(), ((Number) t.get(2)).intValue()}; }
                if (Boolean.TRUE.equals(j.get("test"))) { b.test = true; readItems(j.get("pack"), b.pack); readItems(j.get("bank"), b.bank); }
                BOTS.put(String.valueOf(k), b);
            }
            JSONObject log = (JSONObject) root.get("log");
            if (log != null) for (Object k : log.keySet()) { JSONObject d = (JSONObject) log.get(k); Map<String, int[]> m = new ConcurrentHashMap<>(); for (Object x : d.keySet()) { JSONArray a = (JSONArray) d.get(x); m.put(String.valueOf(x), new int[]{((Number) a.get(0)).intValue(), ((Number) a.get(1)).intValue(), ((Number) a.get(2)).intValue()}); } LOG.put(String.valueOf(k), m); }
            if (root.get("coinDay") != null) coinDay = String.valueOf(root.get("coinDay"));
            if (root.get("coinsToday") != null) coinsToday = ((Number) root.get("coinsToday")).longValue();
            int active = 0; for (BotQ b : BOTS.values()) if (b.active != null) active++;
            System.out.println("[Quests] loaded " + BOTS.size() + " bots (" + active + " mid-quest), coins paid today " + coinsToday);
        } catch (Throwable t) {
            // keep the unreadable file for a person to look at; the next save starts a fresh book instead of overwriting it (audit Q-M3)
            BOTS.clear(); LOG.clear();
            File bad = new File(dir(), "quests.json.bad-" + System.currentTimeMillis());
            boolean moved = f.renameTo(bad);
            System.out.println("[Quests] load failed: " + t + (moved ? "; kept as " + bad.getName() : "; could not move it aside"));
        }
    }

    // ------------------------------------------------------------------ trials

    /** -Dquests.probe="x0,y0,x1,y1,z[,filter];...": prints the scenery (id, name, options) in each box, for writing scripts. */
    static void probe(String spec) {
        for (String box : spec.split(";")) {
            String[] a = box.split(",");
            int x0 = Integer.parseInt(a[0].trim()), y0 = Integer.parseInt(a[1].trim()), x1 = Integer.parseInt(a[2].trim()), y1 = Integer.parseInt(a[3].trim()), z = Integer.parseInt(a[4].trim());
            String filter = a.length > 5 ? a[5].trim().toLowerCase(Locale.ROOT) : null;
            for (int rx = x0 >> 6; rx <= x1 >> 6; rx++) for (int ry = y0 >> 6; ry <= y1 >> 6; ry++) {
                try { core.game.world.map.Region r = core.game.world.map.RegionManager.forId((rx << 8) | ry); if (r != null && !r.isLoaded()) core.game.world.map.Region.load(r); } catch (Throwable ignored) { }
            }
            if (filter != null && filter.startsWith("#")) { // "#name": every object on the plane list in the box, renderable or not
                String want = filter.substring(1);
                java.util.Set<core.game.world.map.RegionPlane> seen = new java.util.HashSet<>();
                for (int x = x0; x <= x1; x += 8) for (int y = y0; y <= y1; y += 8) {
                    core.game.world.map.RegionPlane pl = core.game.world.map.RegionManager.getRegionPlane(Location.create(x, y, z));
                    if (!seen.add(pl)) continue;
                    for (core.game.node.scenery.Scenery s : pl.getObjectList()) {
                        if (s == null) continue; Location l = s.getLocation();
                        if (l.getX() < x0 || l.getX() > x1 || l.getY() < y0 || l.getY() > y1) continue;
                        String n = String.valueOf(s.getName()).toLowerCase(Locale.ROOT);
                        if (!want.isEmpty() && !n.contains(want)) continue;
                        System.out.println("[Probe] list " + l + " id " + s.getId() + " '" + s.getName() + "' renderable " + s.isRenderable() + " type " + s.getType());
                    }
                }
                continue;
            }
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) {
                core.game.node.scenery.Scenery s = null;
                try { s = core.game.world.map.RegionManager.getObject(z, x, y); } catch (Throwable ignored) { }
                if (s == null || s.getName() == null || s.getName().equals("null")) continue;
                if (filter != null && !s.getName().toLowerCase(Locale.ROOT).contains(filter)) continue;
                StringBuilder o = new StringBuilder();
                try { for (core.game.interaction.Option op : s.getInteraction().getOptions()) if (op != null) o.append(op.getName()).append('/'); } catch (Throwable ignored) { }
                if (filter == null && o.length() == 0) continue;
                System.out.println("[Probe] " + x + "," + y + "," + z + " id " + s.getId() + " '" + s.getName() + "' " + o + " type " + s.getType() + " rot " + s.getRotation());
            }
        }
    }

    /** -Dquests.test=SLUG: one fresh, unseeded bot at the quest start with an empty pack; every step is logged with its ticks. */
    static void spawnTest(String slug) {
        QuestScript s = SCRIPTS.get(slug);
        if (s == null) { System.out.println("[Quests] test: no script '" + slug + "' (have " + SCRIPTS.keySet() + ")"); return; }
        Location at = s.start != null ? s.start : Location.create(3222, 3218, 0);
        String resumeName = null; BotQ saved = null;
        for (Map.Entry<String, BotQ> e : BOTS.entrySet()) if (e.getValue().test && slug.equals(e.getValue().active)) { resumeName = e.getKey(); saved = e.getValue(); }
        AIPlayer bot = null;
        if (resumeName != null) {
            if (saved.tile != null) at = Location.create(saved.tile[0], saved.tile[1], saved.tile[2]);
            bot = SmartAdventurers.namedBot(resumeName, at);
            if (bot == null) System.out.println("[Quests] test: could not recreate " + resumeName + "; starting fresh");
        }
        boolean resumed = bot != null;
        if (resumed) rename(resumeName, bot.getUsername());
        if (bot == null) bot = new AIPlayer(at);
        try { bot.getInventory().clear(); bot.getBank().clear(); bot.getEquipment().clear(); } catch (Throwable ignored) { }
        int gather = Integer.getInteger("quests.testLevel", 20);
        for (int sk : new int[]{Skills.MINING, Skills.WOODCUTTING, Skills.FISHING, Skills.COOKING, Skills.CRAFTING, Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, gather); bot.getSkills().setLevel(sk, gather); }
        bot.getSkills().updateCombatLevel();
        SmartAdventurers.finishTutorial(bot);
        BotQ b = q(bot); b.test = true;
        if (resumed) {
            for (int[] i : saved.pack) bot.getInventory().add(new Item(i[0], i[1]));
            for (int[] i : saved.bank) bot.getBank().add(new Item(i[0], i[1]));
            restore(bot);
            System.out.println("[Quests] TEST resumed " + bot.getUsername() + " for " + s.display + " at step " + saved.step + ", stage " + s.stage(bot) + ", pack " + bot.getInventory().itemCount() + " items, coins " + Ledger.Wallet.coins(bot));
            int savedStep = saved.step; // batch 19c B1: started() resets it
            QuestRunner tr = QuestRunner.start(bot, s, 0, true);
            if (tr != null) tr.savedStep = savedStep; // batch 19c B1: checkpoint-regressed for a resumed test bot too
            resultLine(bot, s);
            return;
        }
        Ledger.Wallet.give(bot, Integer.getInteger("quests.testCoins", 5_000));
        int testQp = Integer.getInteger("quests.testQp", 0); // trials only: quests that need quest points (Black Knights' Fortress, 12)
        if (testQp > 0) try { bot.getQuestRepository().incrementPoints(testQp); } catch (Throwable ignored) { }
        if (Boolean.getBoolean("quests.testItems") && s.quest == content.data.Quests.COOKS_ASSISTANT) { bot.getInventory().add(new Item(1944)); bot.getInventory().add(new Item(1927)); bot.getInventory().add(new Item(1933)); }
        System.out.println("[Quests] TEST " + bot.getUsername() + " spawned at " + at + " for " + s.display + ": stage " + s.stage(bot) + ", QP " + bot.getQuestRepository().getPoints() + ", cooking xp " + (int) bot.getSkills().getExperience(Skills.COOKING) + ", multiplier " + bot.getSkills().experienceMultiplier + ", pack " + bot.getInventory().itemCount());
        QuestRunner.start(bot, s, 0, true);
        resultLine(bot, s);
    }

    /** The result line once the test quest is over: complete, stage, QP and XP gained since the spawn or the resume. */
    static void resultLine(AIPlayer tb, QuestScript s) {
        final double[] xp0 = new double[24]; for (int i = 0; i < 24; i++) xp0[i] = tb.getSkills().getExperience(i);
        final int qp0 = tb.getQuestRepository().getPoints();
        GameWorld.getPulser().submit(new Pulse(5) { // the result line once it is over
            int after;
            @Override public boolean pulse() {
                if (QuestRunner.active(tb) || after++ < 2) return false; // two more beats: the reward scroll closes a few ticks after the finish
                StringBuilder xp = new StringBuilder();
                for (int i = 0; i < 24; i++) { double d = tb.getSkills().getExperience(i) - xp0[i]; if (d >= 1) xp.append(Skills.SKILL_NAME[i]).append(" +").append((int) d).append(" "); }
                System.out.println("[Quests] TEST RESULT " + s.slug + ": complete=" + s.complete(tb) + " stage " + s.stage(tb) + ", QP " + qp0 + " -> " + tb.getQuestRepository().getPoints() + ", xp " + xp + ", modal open " + tb.hasModalOpen() + ", coins " + Ledger.Wallet.coins(tb));
                return true;
            }
        });
    }
}
