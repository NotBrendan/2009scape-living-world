package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trial only (-Dtest.b19c8=true -Dsmartbots.count=2 -Dquests.noseed=true -Dfailures.cap.quest=200,1000): batch 19c builder B, B1 = the
 * quest detectors (design 5). [Fable] synthetic one-step quest scripts (test steps that stall, fail or finish on cue) instead of the real
 * Cook's Assistant with the hopper removed: the same QuestRunner paths, deterministic and in ~14 minutes. Throwaway AIPlayers only.
 *   Q1 a step that never ends (limit 600 ticks)  -> step-stalled at 500 ticks per attempt (ctx doing/trace/ticks), step-failed x2 at the time
 *      limit, quest-abandoned whose fid is in data/quests/failures.jsonl
 *   Q2 three bots on a step that fails at once     -> step-failed (counted twice per bot, the 60 s per-bot gap writes one), quest-abandoned x3,
 *      then quest-disabled-today once (server-level, bot "-")
 *   Q3 QuestBook.resume with active cooks, step 4, stage 0 -> checkpoint-regressed (savedStep 4, firstOpen 0); active "nosuch" -> resume-dropped
 *   Q4 QuestBook.finished twice                    -> reward-twice once
 *   Q5 a 500 gp script whose last step pays nothing -> reward-missing; a 100 gp script that pays 1,000 -> reward-oversized
 *   Q6 a boss step whose lock another bot holds, 650 ticks -> no step-stalled, the step never began
 * Every check reads the day's failures file (after Failures.flushNow) and the Daily "fail" counters. DONE at the end.
 */
public final class TestB19C8 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C8] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c8")) return;
        new Thread(TestB19C8::run, "TestB19C8").start();
    }

    // ------------------------------------------------------------ test steps
    static final class Stuck extends QuestSteps.Step {
        Stuck(String n, int lim) { super(n, lim); }
        @Override QuestSteps.St tick(QuestRunner r) { return QuestSteps.St.RUNNING; }
        @Override String doing() { return "testing the hopper"; }
        @Override String trace() { return "hopper missing (test)"; }
    }
    static final class FailNow extends QuestSteps.Step {
        FailNow(String n) { super(n, 100); }
        @Override QuestSteps.St tick(QuestRunner r) { return fail("no hopper here (test)"); }
    }
    static final class Finish extends QuestSteps.Step {
        final content.data.Quests q; final long pay;
        Finish(content.data.Quests q, long pay) { super("finish (test)", 100); this.q = q; this.pay = pay; }
        @Override QuestSteps.St tick(QuestRunner r) {
            if (pay > 0) Ledger.Wallet.give(r.bot, pay);
            try { r.bot.getQuestRepository().setStageNonmonotonic(r.bot.getQuestRepository().getQuest(q), 100); } catch (Throwable t) { log("stage: " + t); }
            return QuestSteps.St.DONE;
        }
    }

    // ------------------------------------------------------------ helpers (shared shape with TestB19C9-C12)
    static void onGame(Runnable r) throws InterruptedException {
        java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { try { r.run(); } catch (Throwable t) { log("game-thread step: " + t); t.printStackTrace(System.out); } l.countDown(); return true; } });
        l.await(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    static AIPlayer spawn(Location at) { AIPlayer b = new AIPlayer(at); SmartAdventurers.finishTutorial(b); return b; }
    static int daily(String code) { Map<String, Integer> m = Watchdog.Daily.TODAY.get("fail"); Integer v = m == null ? null : m.get(code); return v == null ? 0 : v; }
    /** The day's records (after a flush): every line that parses. */
    static List<JSONObject> records() {
        Failures.flushNow(8_000);
        List<JSONObject> out = new ArrayList<>();
        File f = new File(new File(Scenarios.rDir(), "failures"), Watchdog.Daily.utc(System.currentTimeMillis()) + ".jsonl");
        if (!f.exists()) return out;
        try { for (String l : Files.readAllLines(f.toPath())) try { out.add((JSONObject) new JSONParser().parse(l)); } catch (Throwable ignored) { } } catch (Throwable t) { log("read " + f + ": " + t); }
        return out;
    }
    static List<JSONObject> of(List<JSONObject> rs, String bot, String reason) { List<JSONObject> o = new ArrayList<>(); for (JSONObject r : rs) if ((bot == null || bot.equals(r.get("bot"))) && reason.equals(r.get("reason"))) o.add(r); return o; }
    static String brief(JSONObject r) { if (r == null) return "none"; return r.get("fid") + " " + r.get("domain") + "/" + r.get("src") + "/" + r.get("reason") + " bug " + r.get("bug") + " rsub '" + r.get("rsub") + "' ctx " + r.get("ctx"); }
    static void sleepTicks(int t) throws InterruptedException { Thread.sleep(t * 600L); }

    static void run() {
        List<Player> made = new ArrayList<>();
        try {
            for (int i = 0; i < 600 && Watchdog.Daily.day == null; i++) Thread.sleep(500);
            Thread.sleep(25_000);
            log("start: failures " + (Watchdog.FAIL_ON ? "on" : "off") + ", quest domain " + (Failures.on("quest") ? "on" : "off"));
            final Location lum = Location.create(3222, 3218, 0);
            final AIPlayer[] a = new AIPlayer[1], boss = new AIPlayer[1];
            final QuestScript stall = QuestScript.of(content.data.Quests.DORICS_QUEST, "t19c8-stall", "Test stall").startAt(3222, 3218, 0);
            stall.step(new Stuck("check the hopper", 600));
            final QuestScript bossS = QuestScript.of(content.data.Quests.IMP_CATCHER, "t19c8-boss", "Test boss").startAt(3222, 3218, 0);
            bossS.step(new Stuck("test boss", 2000).boss());
            int f0 = daily("step-failed"), s0 = daily("step-stalled"), ab0 = daily("quest-abandoned");
            onGame(() -> {
                a[0] = spawn(lum); made.add(a[0]); QuestRunner.start(a[0], stall, 0, true);
                boss[0] = spawn(lum.transform(2, 0, 0)); made.add(boss[0]); QuestRunner.BOSS_LOCK.put("test boss", "someone-else-test"); QuestRunner.start(boss[0], bossS, 0, true);
            });
            log("Q1 " + a[0].getUsername() + " on a stuck step (limit 600); Q6 " + boss[0].getUsername() + " waiting on a boss lock");
            // ---- Q2: three bots, a step that fails at once
            final QuestScript failS = QuestScript.of(content.data.Quests.ERNEST_THE_CHICKEN, "t19c8-fail", "Test fail").startAt(3222, 3218, 0);
            failS.step(new FailNow("fail at once"));
            final List<AIPlayer> q2 = new ArrayList<>(); int dis0 = daily("quest-disabled-today");
            for (int i = 0; i < 3; i++) { onGame(() -> { AIPlayer b = spawn(lum.transform(-2, 0, 0)); made.add(b); q2.add(b); QuestRunner.start(b, failS, 0, true); }); sleepTicks(8); }
            // ---- Q3: resume
            final AIPlayer[] r3 = new AIPlayer[2]; int cr0 = daily("checkpoint-regressed"), rd0 = daily("resume-dropped");
            onGame(() -> {
                QuestScript cooks = QuestBook.SCRIPTS.get("cooks");
                r3[0] = spawn(lum); made.add(r3[0]);
                QuestBook.BotQ q = QuestBook.q(r3[0]); q.active = "cooks"; q.step = 4; q.test = true;
                SmartAdventurers.Brain br = new SmartAdventurers.Brain(); br.bot = r3[0]; br.shiftEnd = System.currentTimeMillis() + 3_600_000L;
                boolean ok = QuestBook.resume(br);
                log("Q3 resume of " + r3[0].getUsername() + " (cooks step 4, stage " + (cooks == null ? "?" : cooks.stage(r3[0])) + "): " + ok);
                r3[1] = spawn(lum); made.add(r3[1]);
                QuestBook.BotQ q1 = QuestBook.q(r3[1]); q1.active = "nosuch-quest"; q1.step = 2; q1.test = true;
                SmartAdventurers.Brain b1 = new SmartAdventurers.Brain(); b1.bot = r3[1]; b1.shiftEnd = System.currentTimeMillis() + 3_600_000L;
                log("Q3 resume of " + r3[1].getUsername() + " (nosuch-quest): " + QuestBook.resume(b1));
            });
            sleepTicks(10);
            onGame(() -> { QuestRunner r = QuestRunner.of(r3[0]); if (r != null) r.stopHere(false); });
            // ---- Q4: finished twice
            final AIPlayer[] r4 = new AIPlayer[1]; int rt0 = daily("reward-twice");
            final QuestScript sheep = QuestScript.of(content.data.Quests.SHEEP_SHEARER, "t19c8-sheep", "Test sheep");
            onGame(() -> { r4[0] = spawn(lum); made.add(r4[0]); QuestBook.finished(r4[0], sheep, 0); QuestBook.finished(r4[0], sheep, 0); });
            // ---- Q5: reward-missing / reward-oversized
            final AIPlayer[] r5 = new AIPlayer[2]; int rm0 = daily("reward-missing"), ro0 = daily("reward-oversized");
            final QuestScript miss = QuestScript.of(content.data.Quests.ROMEO_JULIET, "t19c8-miss", "Test missing reward").coins(500); miss.step(new Finish(content.data.Quests.ROMEO_JULIET, 0));
            final QuestScript big = QuestScript.of(content.data.Quests.RUNE_MYSTERIES, "t19c8-big", "Test big reward").coins(100); big.step(new Finish(content.data.Quests.RUNE_MYSTERIES, 1_000));
            onGame(() -> { r5[0] = spawn(lum); made.add(r5[0]); QuestRunner.start(r5[0], miss, 0, true); r5[1] = spawn(lum); made.add(r5[1]); QuestRunner.start(r5[1], big, 0, true); });
            sleepTicks(20);
            List<JSONObject> rs = records();
            JSONObject cr = first(of(rs, r3[0].getUsername(), "checkpoint-regressed")), rd = first(of(rs, r3[1].getUsername(), "resume-dropped"));
            log("RESULT Q3 checkpoint-regressed: " + of(rs, r3[0].getUsername(), "checkpoint-regressed").size() + " (expect 1), Daily +" + (daily("checkpoint-regressed") - cr0) + ": " + brief(cr));
            log("RESULT Q3 resume-dropped: " + of(rs, r3[1].getUsername(), "resume-dropped").size() + " (expect 1, ctx why missing), Daily +" + (daily("resume-dropped") - rd0) + ": " + brief(rd));
            log("RESULT Q4 reward-twice: " + of(rs, r4[0].getUsername(), "reward-twice").size() + " (expect 1), Daily +" + (daily("reward-twice") - rt0) + ": " + brief(first(of(rs, r4[0].getUsername(), "reward-twice"))));
            log("RESULT Q5 reward-missing: " + of(rs, r5[0].getUsername(), "reward-missing").size() + " (expect 1), Daily +" + (daily("reward-missing") - rm0) + ": " + brief(first(of(rs, r5[0].getUsername(), "reward-missing")))
                    + "; reward-oversized: " + of(rs, r5[1].getUsername(), "reward-oversized").size() + " (expect 1), Daily +" + (daily("reward-oversized") - ro0) + ": " + brief(first(of(rs, r5[1].getUsername(), "reward-oversized"))));
            int ab = 0, sf = 0; for (AIPlayer b : q2) { ab += of(rs, b.getUsername(), "quest-abandoned").size(); sf += of(rs, b.getUsername(), "step-failed").size(); }
            List<JSONObject> dis = of(rs, "-", "quest-disabled-today");
            log("RESULT Q2 three fail-at-once bots: step-failed written " + sf + " (expect 3: the second of each within 60 s is throttled), quest-abandoned " + ab + " (expect 3); quest-disabled-today " + dis.size() + " (expect 1), Daily +" + (daily("quest-disabled-today") - dis0) + ": " + brief(first(dis)) + "; script enabled now " + failS.enabled);
            // ---- wait for Q1 (two attempts of 600 ticks) and Q6
            for (int i = 0; i < 1500 && QuestRunner.active(a[0]); i += 25) sleepTicks(25);
            sleepTicks(10);
            rs = records();
            String an = a[0].getUsername();
            List<JSONObject> st = of(rs, an, "step-stalled"), fl = of(rs, an, "step-failed"), abd = of(rs, an, "quest-abandoned");
            log("RESULT Q1 step-stalled: " + st.size() + " (expect 2, one per attempt), Daily +" + (daily("step-stalled") - s0) + "; first: " + brief(first(st)));
            JSONObject c0 = first(st) == null ? null : (JSONObject) first(st).get("ctx");
            log("RESULT Q1 stall ctx: ticks " + (c0 == null ? "?" : c0.get("ticks")) + " (expect 500), doing '" + (c0 == null ? "?" : c0.get("doing")) + "', trace '" + (c0 == null ? "?" : c0.get("trace")) + "', bug " + (first(st) == null ? "?" : first(st).get("bug")) + " (expect b-step-stalled-t19c8-stall-0)");
            log("RESULT Q1 step-failed: " + fl.size() + " (expect 2), attempts " + attempts(fl) + "; Daily step-failed +" + (daily("step-failed") - f0) + " (expect 2 + Q2's 6)");
            String fid = first(abd) == null ? null : String.valueOf(first(abd).get("fid"));
            boolean inFile = false;
            try { File qf = new File(QuestBook.dir(), "failures.jsonl"); if (qf.exists() && fid != null) for (String l : Files.readAllLines(qf.toPath())) if (l.contains("\"fid\":\"" + fid + "\"") && l.contains(an)) inFile = true; } catch (Throwable t) { log("quests failures.jsonl: " + t); }
            log("RESULT Q1 quest-abandoned: " + abd.size() + " (expect 1), fid " + fid + " in data/quests/failures.jsonl " + inFile + "; Daily quest-abandoned +" + (daily("quest-abandoned") - ab0) + " (expect 4)");
            QuestRunner br = QuestRunner.of(boss[0]);
            log("RESULT Q6 boss lock held by another bot: step-stalled for " + boss[0].getUsername() + " " + of(rs, boss[0].getUsername(), "step-stalled").size() + " (expect 0); runner " + (br == null ? "gone" : "idx " + br.idx + " begun " + br.begun + " stepTicks " + br.stepTicks) + " (expect begun false)");
            int other = 0; for (JSONObject r : rs) if ("quest".equals(r.get("domain")) && String.valueOf(r.get("bot")).matches("(?i).*") && !mine(made, r)) other++;
            log("RESULT records: quest domain lines today " + countDomain(rs, "quest") + " (" + other + " from bots outside this test); every quest line has domain/src/reason/bug: " + wellFormed(rs, "quest"));
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        finally {
            try { onGame(() -> { QuestRunner.BOSS_LOCK.remove("test boss"); for (Player p : made) { try { QuestRunner r = QuestRunner.of(p); if (r != null) r.stopHere(false); ((AIPlayer) p).clear(); } catch (Throwable ignored) { } } }); } catch (Throwable ignored) { }
            log("DONE");
        }
    }

    static JSONObject first(List<JSONObject> l) { return l.isEmpty() ? null : l.get(0); }
    static String attempts(List<JSONObject> l) { StringBuilder b = new StringBuilder(); for (JSONObject r : l) { Object c = r.get("ctx"); b.append(c instanceof JSONObject ? ((JSONObject) c).get("attempt") : "?").append(' '); } return b.toString().trim(); }
    static boolean mine(List<Player> made, JSONObject r) { for (Player p : made) if (p.getUsername().equals(r.get("bot"))) return true; return "-".equals(r.get("bot")); }
    static int countDomain(List<JSONObject> rs, String d) { int n = 0; for (JSONObject r : rs) if (d.equals(r.get("domain"))) n++; return n; }
    static boolean wellFormed(List<JSONObject> rs, String d) { for (JSONObject r : rs) if (d.equals(r.get("domain")) && (r.get("src") == null || r.get("reason") == null || r.get("bug") == null || r.get("fid") == null)) return false; return true; }
}
