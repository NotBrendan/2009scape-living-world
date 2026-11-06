package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import core.game.world.repository.Repository;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * batch 19c B4 (the design notes 9): replays of non-path bugs, from the bug entry's "replay" spec in bugs.json.
 *
 * Live (coin and item neutral, design 9.1): the one live kind is "shop-reach" for shop-* bugs ({"kind":"shop",...}): a Scenarios.Replayer
 * takes a synthetic scenario (source "shop_reach", walker "shop", from the spec's "from" to the shop) and walks it with its own neutral
 * shop walk (no teleports, no buying; coins/items changed = skipped); on arrival the shop's stock is read the way the brain reads it
 * (BotChat.WorldData.shops' stock array) and the bug gets a replay: pass = every wanted id is in stock, fail = the stock list; a walk that
 * did not arrive = skipped. At most one per bug per 3 days and 5 a day on a world.
 *
 * Farm (-Dsurvey.replay.econ=N concurrent, farm worlds only: -Dfailures.host=farm-*; never starts while a real player is online): kinds
 * shop (a throwaway adventurer brain with the spec's coins walks shopTick with a forced shopBuy: pass = arrived and bought), ge (one
 * Ledger.consume of 1 unit, the take applied within 60 s and its reservation cleared, the unit supplied back: pass = all three and the
 * bot stock back where it was), quest (a throwaway test bot at the spec's stage runs QuestRunner from the spec's step, 30 minutes:
 * pass = the step completes), job (a throwaway bot with the level and tools works the task at the spot for 5 minutes: pass = collected
 * or XP), danger (the aggressive NPCs within 10 tiles of the spot against the spec's combat level, read without any walk or fight
 * [Fable]: always pass, the list is the finding). Results go to the bug's replays (Failures.replayResult, world = Failures.HOST).
 * Farm state never travels back (batch 19 9): only bugs.json replays/status merge. Logging and replays only: no live bot's decision changes.
 */
public final class EconReplayer implements StartupListener {
    static final int ECON = Integer.getInteger("survey.replay.econ", 0);
    static final boolean DEBUG = Scenarios.DEBUG;
    static final String SHOP_REACH = "shop_reach";
    static final long AGAIN_MS = 3 * 86_400_000L;
    static final int PER_DAY = 5;

    /** One open bug with a replay spec (read off the game thread from bugs.json). */
    static final class Spec { String bug, reason, kind; Map<String, Object> m = new LinkedHashMap<>(); long lastHere; }
    static volatile List<Spec> SPECS = Collections.emptyList();
    static final Map<String, Long> TRIED = new ConcurrentHashMap<>();          // bug id -> this boot's last attempt
    static final Map<String, Spec> BY_SC = new ConcurrentHashMap<>();          // synthetic scenario id -> its spec (live shop-reach)
    static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();          // farm jobs in flight (bug ids)
    static final Map<Long, Integer> DONE_DAY = new ConcurrentHashMap<>();      // UTC day -> attempts started
    static volatile long refusedSaid;
    static volatile int liveStarted, farmStarted, results;

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots() || !Watchdog.FAIL_ON) return;
        long first = Scenarios.FAST ? 15_000 : 60_000, every = Scenarios.FAST ? 30_000 : 300_000;
        Scenarios.TIMER.scheduleAtFixedRate(new TimerTask() { @Override public void run() { try { refresh(); } catch (Throwable t) { if (DEBUG) System.out.println("[EconReplay] refresh: " + t); } } }, first, every);
        boolean farm = ECON > 0 && Failures.FARM;
        if (farm) GameWorld.getPulser().submit(new Pulse(50) { @Override public boolean pulse() { long t0 = System.nanoTime(); try { farmTick(); } catch (Throwable t) { System.out.println("[EconReplay] " + t); Watchdog.fexc("EconReplayer.farmTick", null, t); } Watchdog.fpulse("EconReplayer", null, System.nanoTime() - t0); return false; } });
        System.out.println("[EconReplay] shop-reach on the rescue replayers (" + Scenarios.REPLAY + "); farm econ replays " + (farm ? "on (" + ECON + " at a time)" : ECON > 0 ? "OFF: not a farm world (-Dfailures.host=farm-...)" : "off") + "; host " + Failures.HOST);
    }

    static long day(long ms) { return Math.floorDiv(ms, 86_400_000L); }
    static int today() { return DONE_DAY.getOrDefault(day(System.currentTimeMillis()), 0); }
    static void counted() { long d = day(System.currentTimeMillis()); DONE_DAY.keySet().removeIf(k -> k != d); DONE_DAY.merge(d, 1, Integer::sum); }

    /** Timer thread: open bugs with a replay spec, and when this host last replayed each (from the file's own replays). */
    @SuppressWarnings("unchecked")
    static void refresh() {
        File f = new File(Scenarios.rDir(), "bugs.json"); if (!f.exists()) f = new File(Scenarios.sDir(), "bugs.json");
        if (!f.exists()) { SPECS = Collections.emptyList(); return; }
        List<Spec> out = new ArrayList<>();
        try (java.io.Reader rd = new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
            Object o = new JSONParser().parse(rd);
            Object bugs = o instanceof JSONObject ? ((JSONObject) o).get("bugs") : null;
            if (!(bugs instanceof JSONObject)) { SPECS = out; return; }
            for (Object k : ((JSONObject) bugs).keySet()) {
                Object e = ((JSONObject) bugs).get(k);
                if (!(e instanceof JSONObject)) continue;
                JSONObject b = (JSONObject) e;
                if (!"open".equals(b.get("status")) || !(b.get("replay") instanceof JSONObject)) continue;
                Spec s = new Spec(); s.bug = String.valueOf(k); s.reason = String.valueOf(b.get("reason")); s.m.putAll((JSONObject) b.get("replay")); s.kind = String.valueOf(s.m.get("kind"));
                if (b.get("replays") instanceof JSONArray) for (Object r : (JSONArray) b.get("replays")) if (r instanceof JSONObject && Failures.HOST.equals(((JSONObject) r).get("world")) && ((JSONObject) r).get("t") instanceof Number) s.lastHere = Math.max(s.lastHere, ((Number) ((JSONObject) r).get("t")).longValue());
                out.add(s);
            }
        } catch (Throwable t) { if (DEBUG) System.out.println("[EconReplay] bugs.json: " + t); return; } // keep the last good list
        SPECS = out;
        if (DEBUG) System.out.println("[EconReplay] " + out.size() + " open bugs with a replay spec");
    }

    static boolean due(Spec s, long now) {
        Long t = TRIED.get(s.bug);
        return !RUNNING.contains(s.bug) && (t == null || now - t >= AGAIN_MS) && now - s.lastHere >= AGAIN_MS;
    }

    // ------------------------------------------------------------ spec readers
    static int[] ints(Object o) { if (!(o instanceof List) || ((List<?>) o).size() < 3) return null; List<?> l = (List<?>) o; try { return new int[]{((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue(), ((Number) l.get(2)).intValue()}; } catch (Throwable t) { return null; } }
    static long num(Object o, long d) { return o instanceof Number ? ((Number) o).longValue() : d; }
    static Location loc(int[] a) { return a == null ? null : Location.create(a[0], a[1], a[2]); }
    /** [[id, n], ...] -> ids and counts. */
    static List<int[]> buys(Object o) { List<int[]> l = new ArrayList<>(); if (o instanceof List) for (Object x : (List<?>) o) if (x instanceof List && ((List<?>) x).size() >= 2) try { l.add(new int[]{((Number) ((List<?>) x).get(0)).intValue(), ((Number) ((List<?>) x).get(1)).intValue()}); } catch (Throwable ignored) { } return l; }
    /** The shop entry (name, x, y, stock[]) whose name slugs to the spec's shop. */
    static Object[] shop(Object slug) {
        if (slug == null) return null;
        String want = String.valueOf(slug);
        for (Object[] sh : new ArrayList<>(BotChat.WorldData.shops)) if (want.equals(Failures.slug(String.valueOf(sh[0]))) || want.equalsIgnoreCase(String.valueOf(sh[0]))) return sh;
        return null;
    }
    static Location shopAt(Object[] sh) { return Location.create((Integer) sh[1], (Integer) sh[2], 0); }
    static Location startFor(Spec s, Location to) {
        Location from = loc(ints(s.m.get("from")));
        if (from != null) return from;
        Location bank = null; double bd = Double.MAX_VALUE; // as Scenarios.seedOne: the nearest bank that is not the destination
        for (Location b : AfkTasks.BANKS) { double d = b.getDistance(to); if (d > 8 && d < bd) { bd = d; bank = b; } }
        return bank;
    }

    static boolean humanOnline() {
        for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && !p.isArtificial()) return true;
        return false;
    }

    static void result(Spec s, String sid, String bot, String result, String why) {
        results++;
        System.out.println("[EconReplay] " + s.bug + " (" + s.kind + ") " + result + " by " + bot + ": " + why);
        try { Failures.replayResult(s.bug, sid, bot, result, null, why); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ live: shop-reach on a Scenarios.Replayer (game thread)

    /** Scenarios.schedule: is there a shop-reach to walk (a replayer is spawned for it even when the scenario list is empty)? */
    static boolean shopReachDue() {
        if (!Watchdog.FAIL_ON || today() >= PER_DAY) return false;
        return nextShopReach(System.currentTimeMillis()) != null; // batch 20 (19b+c follow-up 1): the same pick as takeShopReach
    }

    /**
     * batch 20 (19b+c follow-up 1): the one pick shopReachDue and takeShopReach share: {spec, shop, to, from, sid}, or null.
     * shopReachDue used to skip the start-tile and in-flight (BY_SC) checks, so it said yes to a spec takeShopReach then
     * refused, and a replayer was spawned for it every 30 s.
     */
    static Object[] nextShopReach(long now) {
        for (Spec s : SPECS) {
            if (!"shop".equals(s.kind) || !due(s, now)) continue;
            Object[] sh = shop(s.m.get("shop")); if (sh == null) continue;
            Location to = shopAt(sh), from = startFor(s, to); if (from == null) continue;
            String id = SHOP_REACH + "|shop|" + Scenarios.cell(from) + "|" + Scenarios.cell(to);
            if (BY_SC.containsKey(id)) continue;
            return new Object[]{s, sh, to, from, id};
        }
        return null;
    }

    /** Scenarios.Replayer.pick: the next shop-reach as a synthetic scenario (source shop_reach, walker shop), or null. Claimed here. */
    static Scenarios.Sc takeShopReach() {
        if (!Watchdog.FAIL_ON || today() >= PER_DAY) return null;
        long now = System.currentTimeMillis();
        Object[] n = nextShopReach(now);
        if (n == null) return null;
        Spec s = (Spec) n[0]; Object[] sh = (Object[]) n[1]; Location to = (Location) n[2], from = (Location) n[3];
        Scenarios.Sc sc = new Scenarios.Sc();
        sc.source = SHOP_REACH; sc.walker = "shop"; sc.from = Scenarios.xyz(from); sc.to = Scenarios.xyz(to); sc.t = now; sc.reason = s.bug; sc.activity = "checking the stock at " + sh[0];
        sc.id = (String) n[4];
        BY_SC.put(sc.id, s); TRIED.put(s.bug, now); counted(); liveStarted++;
        if (DEBUG) System.out.println("[EconReplay] shop-reach for " + s.bug + ": " + from + " -> " + sh[0] + " at " + to);
        return sc;
    }

    /** Scenarios.Replayer.end for a shop_reach scenario: the walk's result plus the stock read -> the bug's replay. Never throws. */
    static void shopReachEnd(Scenarios.Replayer r, String result, String why) {
        try {
            if (r == null || r.sc == null) return;
            Spec s = BY_SC.remove(r.sc.id); if (s == null) return;
            String bot = r.bot == null ? "-" : r.bot.getUsername();
            if (!"pass".equals(result)) { result(s, r.sc.id, bot, "skipped", "did not reach the shop (" + result + "): " + why); return; }
            Object[] sh = shop(s.m.get("shop"));
            if (sh == null) { result(s, r.sc.id, bot, "skipped", "shop no longer known"); return; }
            int[] stock = (int[]) sh[3];
            Set<Integer> in = new java.util.HashSet<>(); for (int id : stock) in.add(id); // the read the brain uses (stock.contains)
            List<Integer> missing = new ArrayList<>(); for (int[] b : buys(s.m.get("buy"))) if (!in.contains(b[0])) missing.add(b[0]);
            if (missing.isEmpty()) result(s, r.sc.id, bot, "pass", "arrived; wanted ids in stock at " + sh[0]);
            else result(s, r.sc.id, bot, "fail", "arrived; not in stock " + missing + "; stock " + java.util.Arrays.toString(stock));
        } catch (Throwable t) { if (DEBUG) System.out.println("[EconReplay] shop-reach end: " + t); }
    }

    // ------------------------------------------------------------ farm (game thread, every 30 s)

    static final List<Job> JOBS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static void farmTick() {
        JOBS.removeIf(j -> j.over);
        if (JOBS.size() >= ECON || today() >= PER_DAY * 4) return;
        long now = System.currentTimeMillis();
        Spec pick = null;
        for (Spec s : SPECS) if (!"shop-reach".equals(s.kind) && ("shop".equals(s.kind) || "ge".equals(s.kind) || "quest".equals(s.kind) || "job".equals(s.kind) || "danger".equals(s.kind)) && due(s, now)) { pick = s; break; }
        if (pick == null) return;
        if (humanOnline()) { if (now - refusedSaid > 600_000L) { refusedSaid = now; System.out.println("[EconReplay] a real player is online: no farm replays until they leave"); } return; }
        Job j;
        switch (pick.kind) {
            case "shop": j = new ShopJob(); break;
            case "ge": j = new GeJob(); break;
            case "quest": j = new QuestJob(); break;
            case "job": j = new TaskJob(); break;
            default: j = new DangerJob(); break;
        }
        j.s = pick; TRIED.put(pick.bug, now); RUNNING.add(pick.bug); counted(); farmStarted++;
        JOBS.add(j);
        try { if (!j.begin()) { j.done("skipped", j.why == null ? "could not start" : j.why); return; } } catch (Throwable t) { j.done("skipped", "start failed: " + t); return; }
        GameWorld.getPulser().submit(j);
    }

    /** One farm replay: begin() on the game thread, then a 1-tick pulse until done(). The throwaway bot is cleared at the end. */
    abstract static class Job extends Pulse {
        Spec s; AIPlayer bot; boolean over; int ticks; String why;
        Job() { super(1); }
        abstract boolean begin();
        abstract void tick();
        @Override public boolean pulse() {
            if (over) return true;
            ticks++;
            long t0 = System.nanoTime();
            try { tick(); } catch (Throwable t) { done("skipped", "error " + t); Watchdog.fexc("EconReplayer." + s.kind, bot, t); }
            Watchdog.fpulse("EconReplayer", bot, System.nanoTime() - t0);
            return over;
        }
        AIPlayer spawn(Location at) {
            AIPlayer b = new AIPlayer(at); SmartAdventurers.finishTutorial(b);
            try { b.getInventory().clear(); b.getBank().clear(); } catch (Throwable ignored) { }
            return b;
        }
        void done(String result, String w) {
            if (over) return;
            over = true; RUNNING.remove(s.bug);
            result(s, null, bot == null ? "-" : bot.getUsername(), result, w);
            if (bot != null) try { AfkTasks.Runner r = AfkTasks.RUNNING.get(bot.getUsername().toLowerCase(Locale.ROOT)); if (r != null) r.halt(); bot.clear(); } catch (Throwable ignored) { }
        }
        int have(int id) { return bot.getInventory().getAmount(id) + bot.getBank().getAmount(id); }
    }

    /** kind shop: a throwaway brain's shopTick with the spec's buy list and coins (farm only: coins are given). */
    static final class ShopJob extends Job {
        SmartAdventurers.Brain br; Location to; List<int[]> buy; int[] before;
        @Override boolean begin() {
            Object[] sh = shop(s.m.get("shop")); if (sh == null) { why = "shop not known"; return false; }
            to = shopAt(sh); Location from = startFor(s, to); if (from == null) { why = "no start tile"; return false; }
            buy = buys(s.m.get("buy")); if (buy.isEmpty()) { why = "nothing to buy in the spec"; return false; }
            bot = spawn(from);
            long cost = 0; for (int[] b : buy) cost += (long) Math.max(1, MarketBrain.value(b[0])) * b[1];
            long coins = Math.max(num(s.m.get("coins"), 0), cost + 100);
            Ledger.Wallet.give(bot, coins);
            br = new SmartAdventurers.Brain(); br.bot = bot; br.scriptAPI = new core.game.bots.ScriptAPI(bot); br.nextThink = Long.MAX_VALUE;
            br.shopAt = to; br.shopName = String.valueOf(sh[0]); br.shopBuy.clear(); for (int[] b : buy) br.shopBuy.add(new int[]{b[0], b[1]});
            br.shopDeadline = System.currentTimeMillis() + 10 * 60_000L; br.shopBest = Double.MAX_VALUE; br.shopStalls = 0;
            before = new int[buy.size()]; for (int i = 0; i < buy.size(); i++) before[i] = have(buy.get(i)[0]);
            if (DEBUG) System.out.println("[EconReplay] shop " + s.bug + ": " + bot.getUsername() + " with " + coins + " gp from " + from + " to " + sh[0]);
            return true;
        }
        @Override void tick() {
            if (!bot.isActive()) { done("skipped", "the replay bot was gone"); return; }
            if (ticks > 12 * 100) { done("gave_up", "not done after 12 minutes"); return; }
            if (ticks % 3 != 0) return;
            if (br.shopAt != null) { br.shopTick(System.currentTimeMillis()); if (br.shopAt != null) return; }
            int got = 0; for (int i = 0; i < buy.size(); i++) got += Math.max(0, have(buy.get(i)[0]) - before[i]);
            boolean arrived = bot.getLocation().getDistance(to) <= 9;
            done(arrived && got > 0 ? "pass" : "fail", (arrived ? "arrived" : "never reached (at " + bot.getLocation() + ")") + ", got " + got + " of the wanted items");
        }
    }

    /** kind ge: one unit taken from bot stock and supplied back; the GE worker must apply the take within 60 s. */
    static final class GeJob extends Job {
        int id, n, reserved0, took, phase; long stock0, t0;
        @Override boolean begin() {
            id = (int) num(s.m.get("item"), -1); n = (int) Math.max(1, num(s.m.get("n"), 1));
            if (id <= 0) { why = "no item in the spec"; return false; }
            bot = spawn(Location.create(3164, 3487, 0)); // the Grand Exchange
            Ledger.Wallet.give(bot, (long) Math.max(1, MarketBrain.value(id)) * n + 100);
            stock0 = core.game.ge.GrandExchange.Companion.getBotstockForId(id); reserved0 = MarketBrain.reservedTakes(id);
            took = Ledger.consume(bot, id, n, "replay");
            if (took <= 0) { why = "nothing in bot stock to take (stock " + stock0 + ")"; return false; }
            t0 = System.currentTimeMillis();
            return true;
        }
        @Override void tick() {
            long now = System.currentTimeMillis();
            if (phase == 0) {
                if (MarketBrain.reservedTakes(id) <= reserved0) { phase = 1; Ledger.supply(bot, id, took); t0 = now; return; }
                if (now - t0 > 60_000L) { Ledger.supply(bot, id, took); done("fail", "take of " + took + " x " + id + " not applied in 60 s (reserved " + MarketBrain.reservedTakes(id) + ", was " + reserved0 + ")"); }
                return;
            }
            if (now - t0 < 20_000L) return; // the supply's own bot offer is applied by the worker too
            long stock = core.game.ge.GrandExchange.Companion.getBotstockForId(id);
            done(stock == stock0 ? "pass" : "fail", "take applied, reservation cleared; bot stock " + stock0 + " -> " + stock);
        }
    }

    /** kind quest: a throwaway test bot at the spec's stage runs the quest from the spec's step (30 minutes). */
    static final class QuestJob extends Job {
        QuestScript qs; int step; QuestRunner run;
        @Override boolean begin() {
            QuestBook.registerWave1();
            qs = QuestBook.SCRIPTS.get(String.valueOf(s.m.get("slug"))); if (qs == null) { why = "no script " + s.m.get("slug"); return false; }
            step = (int) Math.max(0, num(s.m.get("step"), 0));
            bot = spawn(qs.start != null ? qs.start : Location.create(3222, 3218, 0));
            int lvl = Integer.getInteger("quests.testLevel", 20);
            for (int sk : new int[]{Skills.MINING, Skills.WOODCUTTING, Skills.FISHING, Skills.COOKING, Skills.CRAFTING, Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { bot.getSkills().setStaticLevel(sk, lvl); bot.getSkills().setLevel(sk, lvl); }
            bot.getSkills().updateCombatLevel();
            Ledger.Wallet.give(bot, 5_000);
            QuestBook.q(bot).test = true;
            int stage = (int) num(s.m.get("stage"), -1);
            if (stage > 0) try { bot.getQuestRepository().setStageNonmonotonic(bot.getQuestRepository().getQuest(qs.quest), stage); } catch (Throwable t) { why = "stage " + stage + ": " + t; return false; }
            run = QuestRunner.start(bot, qs, step, true);
            if (DEBUG) System.out.println("[EconReplay] quest " + s.bug + ": " + bot.getUsername() + " runs " + qs.slug + " from step " + step + " at stage " + stage);
            return run != null;
        }
        @Override void tick() {
            if (run.idx > step || qs.complete(bot)) { done("pass", "step " + step + " completed in " + ticks + " ticks"); return; }
            if (!QuestRunner.active(bot) || run.over) { done("fail", "stopped at step " + run.idx + (run.idx < run.steps.size() ? " (" + run.steps.get(run.idx).failure + ")" : "")); return; }
            if (ticks > 30 * 100) { done("gave_up", "step " + step + " not done in 30 minutes"); run.stopHere(false); }
        }
    }

    /** kind job: a throwaway bot with the level and tools works the task at the spot for 5 minutes. */
    static final class TaskJob extends Job {
        AfkTasks.Runner r; double xp0;
        @Override boolean begin() {
            AfkTasks.Task task; try { task = AfkTasks.Task.valueOf(String.valueOf(s.m.get("task"))); } catch (Throwable t) { why = "no task " + s.m.get("task"); return false; }
            Location spot = loc(ints(s.m.get("spot"))); if (spot == null) { why = "no spot"; return false; }
            bot = spawn(spot);
            int lvl = (int) num(s.m.get("level"), -1);
            int sk = task.kind == AfkTasks.Kind.TREE ? Skills.WOODCUTTING : task.kind == AfkTasks.Kind.ROCK ? Skills.MINING : task.kind == AfkTasks.Kind.FISH ? Skills.FISHING : task.kind == AfkTasks.Kind.THIEVE ? Skills.THIEVING : task.kind == AfkTasks.Kind.SMELT ? Skills.SMITHING : task.kind == AfkTasks.Kind.FIGHT ? Skills.ATTACK : -1;
            if (sk >= 0 && lvl > 0) { bot.getSkills().setStaticLevel(sk, lvl); bot.getSkills().setLevel(sk, lvl); bot.getSkills().updateCombatLevel(); }
            boolean anyTool = false;
            if (s.m.get("tools") instanceof List) for (Object o : (List<?>) s.m.get("tools")) if (o instanceof Number) { bot.getInventory().add(new Item(((Number) o).intValue())); anyTool = true; }
            if (!anyTool && task.tools != null && task.tools.length > 0) bot.getInventory().add(new Item(task.tools[0]));
            for (int i = 0; i < 24; i++) xp0 += bot.getSkills().getExperience(i);
            r = new AfkTasks.Runner(bot, task, false, 0, false, false);
            r.spot = spot; r.setDelay(3);
            if (!AfkTasks.launch(r, AfkTasks.State.WORK, spot)) { why = "the runner would not start: " + r.last; return false; }
            if (DEBUG) System.out.println("[EconReplay] job " + s.bug + ": " + bot.getUsername() + " works " + task.name() + " at " + spot);
            return true;
        }
        @Override void tick() {
            if (ticks < 5 * 100 && !r.halted) return;
            double xp = 0; for (int i = 0; i < 24; i++) xp += bot.getSkills().getExperience(i);
            boolean ok = r.collected > 0 || xp > xp0 + 0.5;
            done(ok ? "pass" : "fail", (r.halted ? "finished early (" + r.last + ")" : "5 minutes") + ": collected " + r.collected + ", xp +" + (int) (xp - xp0));
        }
    }

    /** kind danger: the aggressive NPCs within 10 tiles of the spot and their levels against the spec's combat level; no walk, no fight [Fable]. */
    static final class DangerJob extends Job {
        @Override boolean begin() { if (loc(ints(s.m.get("spot"))) != null) return true; why = "no spot"; return false; }
        @Override void tick() {
            Location spot = loc(ints(s.m.get("spot")));
            int cb = (int) num(s.m.get("cb"), -1);
            StringBuilder b = new StringBuilder(); int n = 0;
            List<NPC> l = RegionManager.getLocalNpcs(spot, 10);
            if (l != null) for (NPC x : l) { if (x == null || !x.isActive() || !x.isAggressive()) continue; if (n++ < 8) b.append(b.length() > 0 ? ", " : "").append(x.getName()).append(" lvl ").append(x.getProperties().getCurrentCombatLevel()).append(cb > 0 && x.getProperties().getCurrentCombatLevel() * 2 > cb * 3 ? " (strong)" : ""); }
            done("pass", n + " aggressive NPCs within 10 of " + spot.getX() + "," + spot.getY() + (cb > 0 ? " vs cb " + cb : "") + (n > 0 ? ": " + b : ""));
        }
    }
}
