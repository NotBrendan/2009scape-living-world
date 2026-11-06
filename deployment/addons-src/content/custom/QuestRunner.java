package content.custom;

import core.game.node.Node;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.quest.Quest;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives one bot through one quest script, a step a tick (Pulse(1)). The real quest stage is read every tick: a step
 * whose checkpoint already holds is skipped, and the quest counts as done the moment the server says it is. A step
 * that fails twice parks the quest for that bot for a day and writes a line to data/quests/failures.jsonl.
 */
final class QuestRunner extends Pulse {
    static final Map<String, QuestRunner> ACTIVE = new ConcurrentHashMap<>();
    static final Map<String, String> BOSS_LOCK = new ConcurrentHashMap<>(); // boss name -> bot doing it

    final Player bot;
    final QuestScript script;
    final boolean test;
    int idx, stepTicks, tailTicks, totalTicks;
    boolean begun, over;
    final Map<Integer, Integer> fails = new HashMap<>();
    long snapCoins; Map<Integer, Integer> snapItems = new HashMap<>();
    Double savedMult;
    final long startedAt = System.currentTimeMillis();
    Set<Integer> keep;
    // batch 19c B1 (design 5): quest detectors' state (logging only): one step-stalled per step attempt, the resumed run's saved step, a snapshot taken, complete at the start
    boolean stallNoted, resumeChecked, snapped, completeAtStart; int savedStep = -1;

    /** This run's own steps: a step keeps per-run state (its dialogue, its walker), so two bots on one quest never share one. */
    final java.util.List<QuestSteps.Step> steps, exit;

    QuestRunner(Player bot, QuestScript script, int idx, boolean test) {
        super(1, bot);
        this.bot = bot; this.script = script; this.idx = Math.max(0, idx); this.test = test;
        QuestScript fresh = script.factory != null ? script.factory.get() : script;
        this.steps = fresh.steps; this.exit = fresh.exit;
    }

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    static final Map<String, Boolean> EXITING = new ConcurrentHashMap<>(); // done, walking back out (Wizards' Tower stairs)

    static boolean active(Player p) { return p != null && (ACTIVE.containsKey(key(p)) || EXITING.containsKey(key(p))); }

    static QuestRunner of(Player p) { return p == null ? null : ACTIVE.get(key(p)); }

    /** A bot in an instanced area (cutscene, boss room) is where the quest put it: never "stuck" (Watchdog). */
    static boolean inCutscene(Player p) {
        try { return active(p) && p.getViewport().getRegion() instanceof core.game.world.map.build.DynamicRegion; } catch (Throwable t) { return false; }
    }

    // batch 18 item2: where a step began, where its walk goes, and whether its failure was movement (walk / time limit on a walking step)
    Location stepFrom;
    static Location dest(QuestSteps.Step s) {
        if (s instanceof QuestSteps.Walk) return ((QuestSteps.Walk) s).to;
        if (s instanceof QuestSteps.Buy) return ((QuestSteps.Buy) s).at;
        if (s instanceof QuestSteps.Kill) return ((QuestSteps.Kill) s).spot;
        if (s instanceof QuestSteps.Gather) return ((QuestSteps.Gather) s).spot;
        if (s instanceof QuestSteps.Collect) { QuestSteps.Collect c = (QuestSteps.Collect) s; QuestSteps.Step sub = c.phase == 1 && c.sub < c.plan.size() ? c.plan.get(c.sub) : c.buy; return sub != null && sub != s ? dest(sub) : null; }
        return null;
    }
    static boolean movement(QuestSteps.Step s) {
        boolean walker = s instanceof QuestSteps.Walk || s instanceof QuestSteps.Buy || s instanceof QuestSteps.Collect || s instanceof QuestSteps.Gather || s instanceof QuestSteps.Kill;
        String f = s.failure;
        return walker && f != null && (f.contains("walk to ") || f.contains("time limit"));
    }
    Location stepDest() { return idx < steps.size() ? dest(steps.get(idx)) : null; }
    String stepTrace() { try { return idx < steps.size() ? String.valueOf(steps.get(idx).trace()) : ""; } catch (Throwable t) { return ""; } }

    /** Is the quest walking the bot somewhere (Watchdog.trying)? */
    static boolean walking(Player p) {
        QuestRunner r = of(p);
        if (r == null || r.idx >= r.steps.size()) return false;
        QuestSteps.Step s = r.steps.get(r.idx);
        return s instanceof QuestSteps.Walk || s instanceof QuestSteps.Buy || s instanceof QuestSteps.Collect || s instanceof QuestSteps.Gather || s instanceof QuestSteps.Kill;
    }

    static QuestRunner start(Player bot, QuestScript s, int idx, boolean test) {
        QuestRunner old = ACTIVE.get(key(bot));
        if (old != null) old.stopHere(false); // clean up its step (talk lock, xp multiplier, boss lock), not just flag it
        AfkTasks.Runner r = AfkTasks.RUNNING.get(key(bot));
        if (r != null) r.halt();
        QuestRunner q = new QuestRunner(bot, s, idx, test);
        if (idx == 0 && Watchdog.FAIL_ON) try { q.completeAtStart = s.complete(bot); } catch (Throwable ignored) { } // batch 19c B1: reward-twice's second clause
        ACTIVE.put(key(bot), q);
        holdDialogues(bot); // at once: the brain's own tick restores it after the quest
        GameWorld.getPulser().submit(q);
        BotChat.activity(bot, () -> q.doing());
        q.log("starts " + s.display + " at step " + idx + " (stage " + s.stage(bot) + ")");
        QuestBook.started(bot, s, idx);
        if (!test && idx == 0) startNews(bot, s); // batch 15 item1
        return q;
    }

    /** batch 15 item1: a fresh quest start is gossip too, at most one start line every few minutes (no spam). */
    static volatile long nextStartNews;
    static void startNews(Player bot, QuestScript s) {
        long now = System.currentTimeMillis();
        if (now < nextStartNews) return;
        nextStartNews = now + 4 * 60_000L;
        BotChat.news(String.format(BotChat.pick("Saw %1$s heading off to do %2$s. Good luck to them.", "%1$s has taken on %2$s. We'll see how that goes.", "Word is %1$s started %2$s today.", "%1$s was asking about %2$s down the pub. Off doing it now."), bot.getUsername(), s.display));
    }

    /**
     * The server's bot pulse (GeneralBotCreator) closes the chatbox and dialogue of a bot whose talk-to interaction is still
     * registered when the NPC's first screen shows (scripts.getActiveScript() != null && hasModalOpen() && endDialogue):
     * the live early closes of 10-23 (Rain293, Mrnl274, Tgkiller165). A quester's dialogues belong to the DialogueDriver.
     */
    /** Coins a step took into the pack (pocketCoins, a reward) go back to the bank: in the pack they drop into a grave on a death. */
    static void bankCoins(Player bot) {
        try {
            int n = bot.getInventory().getAmount(995);
            if (n > 0 && bot.getInventory().remove(new Item(995, n)) && !bot.getBank().add(new Item(995, n))) bot.getInventory().add(new Item(995, n));
        } catch (Throwable ignored) { }
    }

    static void holdDialogues(Player bot) {
        try { SmartAdventurers.Brain b = SmartAdventurers.BRAINS.get(key(bot)); if (b != null) b.endDialogue = false; } catch (Throwable ignored) { }
    }

    String doing() {
        if (idx >= steps.size()) return "finishing " + script.display;
        return "doing " + script.display + ": " + steps.get(idx).doing().toLowerCase(Locale.ROOT);
    }

    int stage() { return script.stage(bot); }

    void log(String s) { System.out.println("[Quests] " + bot.getUsername() + " " + script.slug + ": " + s); if (Watchdog.FAIL_ON && Watchdog.traced(bot)) Watchdog.flog(bot, "quest " + script.slug + ": " + s); } // batch 19a design 3.2: step begin/done/fail lines into the log ring

    /**
     * The engine stops a pulse whose bot went inactive without calling pulse() again (Pulse.update), so the logout
     * check in pulse() alone would leave ACTIVE, the talk lock and the xp multiplier behind (audit 10-22 Q-H1).
     */
    @Override
    public void stop() {
        super.stop();
        if (!over) stopHere(false);
        ACTIVE.remove(key(bot), this);
    }

    @Override
    public boolean pulse() {
        if (over) return true;
        if (!bot.isActive()) { stopHere(false); return true; } // logged out: quests.json keeps the checkpoint
        if (died()) { log("died at " + deathAt + ": stopping here, the grave run first"); stopHere(true); QuestBook.questDeath(bot, script); return true; } // audit 10-23 Q-20: counted; two in a day park the quest
        long t0 = System.nanoTime(); // batch 19c B3 (design 8.2): this pulse's cost
        try { return step(); } catch (Throwable t) {
            log("error " + t);
            Watchdog.fexc("QuestRunner.step", bot, t); // batch 19a B3
            t.printStackTrace(System.out);
            abandon("error " + t);
            return true;
        } finally { Watchdog.fpulse("QuestRunner", bot, System.nanoTime() - t0); } // batch 19c B3
    }

    // ------------------------------------------------------------------ batch 19c B1 (design 5): quest failure records (logging only; Failures.event via Watchdog.fev)

    /** batch 19c B1: the step's ctx (quest, step, name, doing, trace, ticks, limit, stage). */
    Map<String, Object> stepCtx(QuestSteps.Step s) {
        String doing = null, tr = null; int st = -1;
        try { doing = s.doing(); } catch (Throwable ignored) { } try { tr = s.trace(); } catch (Throwable ignored) { } try { st = stage(); } catch (Throwable ignored) { }
        return Watchdog.ctx("quest", script.slug, "step", idx, "name", s.name, "doing", doing, "trace", tr == null ? null : tr.length() > 160 ? tr.substring(0, 160) : tr, "ticks", stepTicks, "limit", s.limitTicks, "stage", st);
    }

    /** batch 19c B1: the farm replay spec for a quest step bug (design 9.2; A carries ctx.replay into the bug entry). */
    Map<String, Object> replaySpec() { int st = -1; try { st = stage(); } catch (Throwable ignored) { } return Watchdog.ctx("kind", "quest", "slug", script.slug, "step", idx, "stage", st); }

    /** batch 19c B1 step-stalled: checked every 100 ticks; a walk still on its way is path's, a boss step waiting on another holder is not a stall. */
    void stallCheck(QuestSteps.Step s) {
        if (stallNoted || stepTicks < Math.max(500, s.limitTicks / 2)) return;
        Location d = dest(s);
        if (d != null && walking(bot) && (bot.getLocation().getZ() != d.getZ() || bot.getLocation().getDistance(d) > 5)) return; // a trip in flight: the path records cover it
        String holder = s.boss ? BOSS_LOCK.get(s.name) : null;
        if (holder != null && !holder.equals(key(bot))) return; // ctx.waitingFor case: no record while waiting
        stallNoted = true;
        Map<String, Object> c = stepCtx(s); c.put("replay", replaySpec());
        Watchdog.fev("quest", bot, "quest_stall", "step-stalled", s.name + " " + stepTicks + " ticks", bot.getLocation(), c);
    }

    boolean step() {
        totalTicks++;
        if (script.complete(bot)) { complete(); return true; }
        if (idx >= steps.size()) {
            if (++tailTicks > 60) { abandon("all steps done but the quest isn't complete (stage " + stage() + ")"); return true; }
            return false;
        }
        QuestSteps.Step s = steps.get(idx);
        if (!begun) {
            if (s.checkpoint(bot)) { log("step " + idx + " " + s.name + ": already done, skipped"); idx++; return false; }
            if (savedStep >= 0 && !resumeChecked) { // batch 19c B1 checkpoint-regressed: once, at the first open step of a resumed run
                resumeChecked = true;
                if (idx < savedStep - 1) { int at = -1; try { at = QuestBook.q(bot).attrs.size(); } catch (Throwable ignored) { } Map<String, Object> c = Watchdog.ctx("quest", script.slug, "savedStep", savedStep, "firstOpen", idx, "stage", stage(), "attrs", at); c.put("replay", replaySpec()); Watchdog.fev("quest", bot, "quest_resume", "checkpoint-regressed", "saved " + savedStep + ", open at " + idx, bot.getLocation(), c); }
            }
            if (s.boss && !bossFree(s)) return false;
            snapshot();
            if (s.xpStep) { savedMult = bot.getSkills().experienceMultiplier; bot.getSkills().experienceMultiplier = 1.0; }
            if (s instanceof QuestBlackKnightsFortress.Guarded) bankCoins(bot); // a dungeon or a fight: coins drop into a grave
            s.begin(this);
            begun = true; stepTicks = 0; stepFrom = bot.getLocation(); stallNoted = false; // batch 18 item2 // batch 19c B1: one step-stalled per attempt
            Watchdog.ftrip(bot, stepFrom); // batch 19a: the trip's start
            log("step " + idx + " " + s.name + " (stage " + stage() + ")");
        }
        QuestSteps.St st = s.tick(this);
        stepTicks++;
        if (stepTicks % 200 == 0) log("  ... " + s.doing() + " for " + stepTicks + " ticks at " + bot.getLocation() + (s.trace() != null ? " [" + s.trace() + "]" : ""));
        if (stepTicks % 100 == 0 && Watchdog.FAIL_ON && !stallNoted) try { stallCheck(s); } catch (Throwable ignored) { } // batch 19c B1 step-stalled ([Fable] every 100 ticks, so the 500-tick rule fires at 500)
        if (st == QuestSteps.St.RUNNING && stepTicks > s.limitTicks) st = s.fail("time limit " + s.limitTicks + " ticks");
        if (st == QuestSteps.St.RUNNING && s.recheck && s.checkpoint(bot)) st = QuestSteps.St.DONE;
        if (st == QuestSteps.St.RUNNING) return false;
        endStep(s);
        if (st == QuestSteps.St.DONE) {
            log("step " + idx + " " + s.name + " done in " + stepTicks + " ticks (stage " + stage() + ")");
            idx++;
            QuestBook.checkpoint(bot, script, idx);
            if (script.complete(bot)) { complete(); return true; }
            if (!test && shiftOver()) { log("shift over: stopping at step " + idx + ", back to it after the break"); stopHere(true); return true; }
            return false;
        }
        if (movement(s)) Watchdog.scenario(bot, "quest_fail", "quest", stepFrom, dest(s), bot.getLocation(), script.slug + " step " + idx + " " + s.name + ": " + s.failure); // batch 18 item2: a walk that failed or ran out of time
        if (s.optional) { log("step " + idx + " " + s.name + " skipped: " + s.failure); idx++; return false; }
        int f = fails.merge(idx, 1, Integer::sum);
        log("step " + idx + " " + s.name + " FAILED (" + f + "): " + s.failure + (s.trace() != null ? " [" + s.trace() + "]" : ""));
        if (!movement(s) && Watchdog.FAIL_ON) try { Map<String, Object> c = stepCtx(s); c.put("failure", s.failure); c.put("attempt", f); c.put("replay", replaySpec()); Watchdog.fev("quest", bot, "quest_fail", "step-failed", s.name + ": " + s.failure, bot.getLocation(), c); } catch (Throwable ignored) { } // batch 19c B1 (movement failures are path's quest_fail)
        if (f >= 2) { abandon(s.name + ": " + s.failure); return true; }
        return false; // try the step again
    }

    void endStep(QuestSteps.Step s) {
        try { s.end(this); } catch (Throwable ignored) { }
        if (savedMult != null) { bot.getSkills().experienceMultiplier = savedMult; savedMult = null; }
        if (s.boss) BOSS_LOCK.values().remove(key(bot));
        begun = false;
    }

    boolean bossFree(QuestSteps.Step s) {
        String holder = BOSS_LOCK.get(s.name);
        if (holder != null && !holder.equals(key(bot))) return false;
        if (s instanceof QuestSteps.Kill) { // never with a player watching the boss spot
            Location spot = ((QuestSteps.Kill) s).spot;
            for (Player h : core.game.world.repository.Repository.getPlayers()) if (h != null && !h.isArtificial() && h.isActive() && h.getLocation().getZ() == spot.getZ() && h.getLocation().getDistance(spot) <= 15) return false;
        }
        BOSS_LOCK.put(s.name, key(bot));
        return true;
    }

    int lastHp = -1; Location deathAt, lastTile;

    /** A death mid-quest (hp to 0, or the respawn jump to Lumbridge): the brain's checkDeath and grave run take over (audit Q-M2). */
    boolean died() {
        int hp = bot.getSkills().getLifepoints();
        Location here = bot.getLocation();
        boolean respawned = lastTile != null && here.getDistance(SmartAdventurers.LUMBRIDGE) <= 6 && lastTile.getDistance(SmartAdventurers.LUMBRIDGE) > 40 && lastHp >= 0 && lastHp < bot.getSkills().getMaximumLifepoints() / 2;
        boolean dead = hp <= 0 && lastHp > 0 || respawned;
        if (dead) deathAt = lastTile != null ? lastTile : here;
        lastHp = hp; lastTile = here;
        return dead;
    }

    boolean shiftOver() {
        SmartAdventurers.Brain b = SmartAdventurers.BRAINS.get(key(bot));
        return b != null && System.currentTimeMillis() > b.shiftEnd;
    }

    /** Coins and items (pack + bank) before the step that finishes the quest: the difference afterwards is the reward. */
    void snapshot() {
        snapCoins = Ledger.Wallet.coins(bot);
        snapItems = items(bot);
        snapped = true; // batch 19c B1: the reward rules only read a run that took one
    }

    /** batch 19c B1 (design 5): reward-missing / reward-twice (complete at the start) / reward-oversized at complete(), after the reward is counted. */
    void rewardCheck(long coins, long itemValue, String got) {
        int qp = safeQp();
        if (completeAtStart) { Watchdog.fev("quest", bot, "quest_reward", "reward-twice", "complete at the start", bot.getLocation(), Watchdog.ctx("quest", script.slug, "completions", QuestBook.q(bot).completions, "paid", coins, "atStart", true)); return; }
        if (!snapped) return;
        if (coins == 0 && itemValue == 0 && (script.coins > 0 || script.rewardItems.length > 0) && deathAt == null)
            Watchdog.fev("quest", bot, "quest_reward", "reward-missing", "paid 0 of " + script.coins + " gp", bot.getLocation(), Watchdog.ctx("quest", script.slug, "expectCoins", script.coins, "paid", 0, "items", "", "qp", qp));
        else if (script.coins > 0 && coins > 3L * script.coins || itemValue > 100_000L)
            Watchdog.fev("quest", bot, "quest_reward", "reward-oversized", "paid " + coins + " gp, items " + itemValue + " gp", bot.getLocation(), Watchdog.ctx("quest", script.slug, "expectCoins", script.coins, "paid", coins, "itemValue", itemValue, "items", got.length() > 120 ? got.substring(0, 120) : got, "qp", qp));
    }

    static Map<Integer, Integer> items(Player p) {
        Map<Integer, Integer> m = new HashMap<>();
        for (Item it : p.getInventory().toArray()) if (it != null && it.getId() != 995) m.merge(it.getId(), it.getAmount(), Integer::sum);
        for (Item it : p.getBank().toArray()) if (it != null && it.getId() != 995) m.merge(it.getId(), it.getAmount(), Integer::sum);
        return m;
    }

    void complete() {
        over = true;
        ACTIVE.remove(key(bot));
        bankCoins(bot);
        if (begun) endStep(steps.get(Math.min(idx, steps.size() - 1)));
        // the reward scroll (interface 277) blocks walking until it is closed
        GameWorld.getPulser().submit(new Pulse(2, bot) { int n; @Override public boolean pulse() { try { if (bot.hasModalOpen()) bot.getInterfaceManager().close(); bot.getDialogueInterpreter().close(); } catch (Throwable ignored) { } return ++n >= 5; } }); // a few times: some finishes (the cauldron) open the scroll a tick or two later
        long coins = Math.max(0, Ledger.Wallet.coins(bot) - snapCoins);
        long itemValue = 0; StringBuilder got = new StringBuilder();
        Map<Integer, Integer> now = items(bot);
        for (Map.Entry<Integer, Integer> e : now.entrySet()) {
            int more = e.getValue() - snapItems.getOrDefault(e.getKey(), 0);
            if (more <= 0) continue;
            itemValue += (long) more * Math.max(0, MarketBrain.value(e.getKey()));
            got.append(more).append(" x ").append(MarketBrain.name(e.getKey())).append("; ");
        }
        Ledger.questPaid(coins, itemValue);
        if (Watchdog.FAIL_ON && Watchdog.traced(bot)) { try { Failures.econNote(bot, Failures.QUEST); } catch (Throwable ignored) { } try { rewardCheck(coins, itemValue, got.toString()); } catch (Throwable ignored) { } } // batch 19c: design 4.1 QUEST flag, design 5 reward codes
        wearRewards(now); // batch 15 item2
        long mins = Math.max(1, (System.currentTimeMillis() - startedAt) / 60_000);
        log("COMPLETE in " + totalTicks + " ticks (" + mins + " min): stage " + stage() + ", QP " + safeQp() + ", paid " + coins + " gp" + (got.length() > 0 ? ", items " + got : "") + " (" + itemValue + " gp)");
        QuestBook.finished(bot, script, coins);
        BotChat.clearActivity(bot);
        if (!test) {
            BotChat.say(bot, BotChat.pick("Quest complete! " + script.display + ".", "Done! " + script.display + " finished.", "Finally. " + script.display + ", sorted.", script.display + " done. Easy.", "Woo! " + script.display + " complete.", "That's " + script.display + " done. Next!", "Another quest point. " + script.display + ".", "Done " + script.display + ". Wasn't so bad.")); // batch 15 item1: more variants
            BotChat.news(String.format(BotChat.pick("%1$s finished %2$s today. Wouldn't stop talking about it.", "Heard %1$s sorted out %2$s. About time someone did.", "%1$s has done %2$s. Another name in the book."), bot.getUsername(), script.display));
            SmartAdventurers.Brain b = SmartAdventurers.BRAINS.get(key(bot));
            if (b != null) b.nextThink = System.currentTimeMillis() + 5_000;
        }
        if (!exit.isEmpty()) runExit();
    }

    /** batch 15 item2: the amulet of accuracy (Imp Catcher) and the gold ring (Pirate's Treasure) go on if that slot is empty. */
    static final int[] WEAR_REWARDS = {1478, 1635};
    void wearRewards(Map<Integer, Integer> now) {
        for (int id : WEAR_REWARDS) {
            try {
                if (now.getOrDefault(id, 0) - snapItems.getOrDefault(id, 0) <= 0) continue; // not this quest's reward
                Object cfg = new Item(id).getDefinition().getConfiguration("equipment_slot", -1);
                int slot = cfg instanceof Number ? ((Number) cfg).intValue() : -1;
                if (slot < 0 || bot.getEquipment().get(slot) != null) continue; // that slot is taken: it stays where it is
                if (bot.getInventory().getAmount(id) == 0) { // in the bank (a full pack): into the pack first, so wear() moves that copy, never a new one
                    if (bot.getInventory().freeSlots() < 1 || !bot.getBank().remove(new Item(id))) continue;
                    if (!bot.getInventory().add(new Item(id))) { bot.getBank().add(new Item(id)); continue; }
                }
                boolean on = SmartAdventurers.wear(bot, id);
                log("reward " + MarketBrain.name(id) + (on ? " worn" : " not worn (kept)"));
            } catch (Throwable t) { log("reward wear " + id + ": " + t); }
        }
    }

    /** The way back out after the reward, step by step; a step that fails just ends it (the Watchdog catches strays). */
    void runExit() {
        EXITING.put(key(bot), true);
        final QuestRunner me = this;
        GameWorld.getPulser().submit(new Pulse(1, bot) {
            int i = 0, t = 0; boolean b;
            @Override public boolean pulse() {
                try {
                    if (!bot.isActive() || i >= exit.size()) { EXITING.remove(key(bot)); return true; }
                    QuestSteps.Step s = exit.get(i);
                    if (!b) { if (s.checkpoint(bot)) { i++; return false; } s.begin(me); b = true; t = 0; }
                    QuestSteps.St st = s.tick(me);
                    if (st == QuestSteps.St.RUNNING && ++t > s.limitTicks) st = s.fail("time limit");
                    if (st == QuestSteps.St.RUNNING) return false;
                    s.end(me); b = false;
                    if (st == QuestSteps.St.FAILED) { log("exit step " + s.name + " failed: " + s.failure); EXITING.remove(key(bot)); return true; }
                    log("exit step " + s.name + " done in " + t + " ticks");
                    i++;
                    return false;
                } catch (Throwable e) { EXITING.remove(key(bot)); return true; }
            }
        });
    }

    int safeQp() { try { return bot.getQuestRepository().getPoints(); } catch (Throwable t) { return -1; } }

    /** Stop without blame (shift over, logged out): the checkpoint stays so the bot picks it up later. */
    void stopHere(boolean save) {
        if (over && !ACTIVE.containsValue(this)) return;
        over = true;
        if (begun && idx < steps.size()) endStep(steps.get(idx));
        ACTIVE.remove(key(bot), this);
        bankCoins(bot);
        BotChat.clearActivity(bot);
        if (save) QuestBook.checkpoint(bot, script, idx);
        QuestBook.captureSoon(bot); // partial hand-ins (Fred's wool count) survive a logout
    }

    void abandon(String why) {
        over = true;
        bankCoins(bot);
        if (begun && idx < steps.size()) endStep(steps.get(idx));
        ACTIVE.remove(key(bot));
        BotChat.clearActivity(bot);
        String stepName = idx < steps.size() ? steps.get(idx).name : "(end)";
        String trace = idx < steps.size() ? steps.get(idx).trace() : null;
        log("ABANDONED at step " + idx + " " + stepName + ": " + why + (trace != null ? " [" + trace + "]" : ""));
        String fid = null; // batch 19c B1 quest-abandoned (before abandoned(): the 30% rule's quest-disabled-today comes after it)
        if (Watchdog.FAIL_ON) try { Map<String, Object> c = Watchdog.ctx("quest", script.slug, "step", idx, "why", why, "trace", trace == null ? null : trace.length() > 160 ? trace.substring(0, 160) : trace); c.put("replay", replaySpec()); fid = Watchdog.fev("quest", bot, "quest_fail", "quest-abandoned", stepName + ": " + why, bot.getLocation(), c); } catch (Throwable ignored) { }
        QuestBook.abandoned(bot, script);
        try {
            JSONObject j = new JSONObject();
            j.put("time", System.currentTimeMillis()); j.put("bot", bot.getUsername()); j.put("quest", script.slug); j.put("step", idx); j.put("stepName", stepName);
            j.put("failure", why); j.put("seen", trace); j.put("stage", stage());
            if (fid != null) j.put("fid", fid); // batch 19c B1: the failure record's id (one key added; the line is otherwise as before)
            Location l = bot.getLocation(); j.put("tile", l.getX() + "," + l.getY() + "," + l.getZ());
            final File f = new File(QuestBook.dir(), "failures.jsonl"); final String line = j.toJSONString();
            // batch 22 O-6: written on the SurveyWriter thread (was the game thread), after the weekly gzip rotation (Surveyors.rotateWeekly,
            // failures-YYYY-Www.jsonl.gz, 4 kept). One task: the SurveyWriter thread is this file's only writer, so the rotation never races an append.
            SurveyWriter.task(() -> {
                try { f.getParentFile().mkdirs(); Surveyors.rotateWeekly(f.getParentFile(), "failures.jsonl", "[Quests]");
                    try (FileWriter w = new FileWriter(f, true)) { w.write(line + "\n"); } }
                catch (Throwable t) { System.out.println("[Quests] failure log: " + t); }
            });
        } catch (Throwable t) { System.out.println("[Quests] failure log: " + t); }
        if (!test && Math.random() < 0.5) BotChat.say(bot, BotChat.pick("Can't make head nor tail of this quest. Later.", "Giving up on " + script.display + " for today.", "This quest can wait."));
    }

    // ------------------------------------------------------------------ helpers the steps use

    /** Frees pack slots by banking what this quest doesn't need (bots bank from anywhere, as AfkTasks does at trip end). */
    static void makeRoom(Player p, int slots) {
        if (p.getInventory().freeSlots() >= slots) return;
        QuestRunner r = of(p);
        Set<Integer> keep = r != null ? r.keepIds() : new HashSet<>();
        for (Item it : p.getInventory().toArray()) {
            if (p.getInventory().freeSlots() >= slots) return;
            if (it == null || keep.contains(it.getId()) || it.getId() == 995) continue;
            Item copy = new Item(it.getId(), it.getAmount(), it.getCharge());
            if (p.getInventory().remove(copy)) { if (!p.getBank().add(copy)) p.getInventory().add(copy); }
        }
    }

    Set<Integer> keepIds() {
        if (keep != null) return keep;
        Set<Integer> k = new HashSet<>();
        for (QuestSteps.Step s : steps) collectIds(s, k);
        for (int f : AfkTasks.Runner.FOOD) k.add(f);
        k.add(1735); k.add(1925); k.add(1931); // shears, bucket, pot
        for (int t : AfkTasks.PICKAXES) k.add(t);
        for (int t : AfkTasks.AXES) k.add(t);
        for (int rune = 554; rune <= 566; rune++) k.add(rune); // a mage's runes (audit Q-M5)
        for (int arrow = 882; arrow <= 892; arrow++) k.add(arrow);
        return keep = k;
    }

    static void collectIds(QuestSteps.Step s, Set<Integer> k) {
        if (s instanceof QuestSteps.Collect) { QuestSteps.Collect c = (QuestSteps.Collect) s; k.add(c.itemId); for (QuestSteps.Step x : c.plan) collectIds(x, k); if (c.buy != null) collectIds(c.buy, k); }
        else if (s instanceof QuestSteps.UseOn) k.add(((QuestSteps.UseOn) s).itemId);
        else if (s instanceof QuestSteps.Gather) k.add(((QuestSteps.Gather) s).itemId);
        else if (s instanceof QuestSteps.Buy) k.add(((QuestSteps.Buy) s).itemId);
        else if (s instanceof QuestSteps.Kill) for (int d : ((QuestSteps.Kill) s).drops) k.add(d);
    }

    /** Quest ingredients that are also food go to the bank before a fight; Collect takes them back out. */
    static void bankQuestFood(Player p) {
        QuestRunner r = of(p);
        if (r == null) return;
        Set<Integer> need = new HashSet<>();
        for (QuestSteps.Step s : r.steps) collectIds(s, need);
        for (int f : AfkTasks.Runner.FOOD) {
            if (!need.contains(f)) continue;
            int n = p.getInventory().getAmount(f);
            if (n > 0 && p.getInventory().remove(new Item(f, n))) { if (!p.getBank().add(new Item(f, n))) p.getInventory().add(new Item(f, n)); }
        }
    }

    /** A few pieces of food for a quest fight: from the bank, else bought (coins leave the game, as a shop takes them). */
    static void packFood(Player p, int n) {
        QuestRunner r = of(p);
        Set<Integer> need = new HashSet<>();
        if (r != null) for (QuestSteps.Step s : r.steps) collectIds(s, need);
        int have = 0; for (int f : AfkTasks.Runner.FOOD) if (!need.contains(f)) have += p.getInventory().getAmount(f);
        for (int f : AfkTasks.Runner.FOOD) {
            if (have >= n) return;
            if (need.contains(f)) continue;
            int b = Math.min(n - have, p.getBank().getAmount(f));
            if (b > 0 && p.getBank().remove(new Item(f, b))) { if (p.getInventory().add(new Item(f, b))) have += b; else p.getBank().add(new Item(f, b)); }
        }
        if (have >= n) return;
        int bread = 2309, want = n - have;
        if (Ledger.Wallet.pay(p, (long) Math.max(1, MarketBrain.value(bread)) * want)) for (int i = 0; i < want; i++) p.getInventory().add(new Item(bread));
    }

    /** The old use-with system (UseWithHandler plugins) for items the listener table doesn't know. */
    static boolean legacyUseWith(Player p, Item used, Node with) {
        core.game.interaction.UseWithHandler.run(new core.game.interaction.NodeUsageEvent(p, 0, used, with));
        return true;
    }
}
