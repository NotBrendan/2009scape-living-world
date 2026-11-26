package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.bots.AIRepository;
import core.game.bots.CombatBot;
import core.game.bots.CombatBotAssembler;
import core.game.bots.GeneralBotCreator;
import core.game.bots.Script;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;
import core.game.world.map.RegionManager;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Smart adventurers (phase 2): bots that pick a bounty from the MarketBrain they can actually get,
 * walk there, hunt or gather it with the same code the owner's AFK bot uses (real combat, loot,
 * banking, eating, fleeing), sell only that haul to bot stock, and then pick the next bounty.
 * Their tools come out of bot stock (a negative bot offer), the same as the artisans' materials.
 */
public final class SmartAdventurers implements StartupListener, core.api.ShutdownListener {
    static volatile boolean SEA_CAP = !"false".equals(System.getProperty("sea.cap")); // batch 21e: at most one paid sea crossing per shop walk (-Dsea.cap=false: the batch 21 way)
    static final long SEA_COOL_MS = Math.max(0L, Long.getLong("sea.coolMs", 900_000L)); // batch 21e: and none to the same shop again for this long (default 15 min)

    /** Server going down (restart, backup): settle splits, stash traders' hauls, then save every bot, artisan and the ledger, so nothing a bot owns is lost. */
    @Override
    public void shutdown() {
        try { // batch 17 item4 (audit O-3): SystemTermination has already stopped the Major Update Worker (running=false, interrupt), but the tick it was in can still be running; wait for it (3 s at most) so the snapshot below isn't taken mid-tick
            Thread w = GameWorld.getMajorUpdateWorker().getWorker(); long t0 = System.currentTimeMillis();
            if (w != null && w != Thread.currentThread() && w.isAlive()) { w.join(3_000); System.out.println("[SmartAdv] shutdown: waited " + (System.currentTimeMillis() - t0) + " ms for the game tick to finish" + (w.isAlive() ? " (still running: saving anyway)" : "")); }
            else System.out.println("[SmartAdv] shutdown: no game tick to wait for (worker " + (w == null ? "missing" : w == Thread.currentThread() ? "is this thread" : "already stopped") + ")");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown tick wait: " + t); }
        Ledger.Wallet.SHUTTING_DOWN = true; // audit 10-23 X-7: before anything credits a bot
        try { reclaimGravesForShutdown(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown graves: " + t); }
        try { QuestBook.save(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown quests: " + t); } // after graves, before profiles
        try { Deals.settleForShutdown(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown splits: " + t); }
        try { TravellingTraders.stashForShutdown(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown traders: " + t); }
        try { Artisans.settleForShutdown(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown artisan batches: " + t); } // audit E-17
        try { Artisans.saveAll(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown artisans: " + t); }
        try { ServiceBots.saveAll(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown service bots: " + t); }
        try { RouteMap.save(); Resources.save(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown route map: " + t); }
        try { Bob.leaveAll("shutdown"); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown familiars: " + t); } // P86 hook 13
        try { long[] ct = CoinWatch.total(); long owed = 0; for (Long v : Ledger.Wallet.OFFLINE.values()) if (v != null) owed += v; System.out.println("[CoinWatch] shutdown total " + ct[0] + " gp, " + ct[1] + " bots (owed " + owed + " gp of it)"); } catch (Throwable t) { System.out.println("[CoinWatch] shutdown total: " + t); } // restart-coin-drop: compare with the "[CoinWatch] boot book" line of the next boot
        try { int n = saveCrops(null); if (FarmSave.ON) System.out.println("[FarmSave] shutdown: " + n + " crop files written"); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown crops: " + t); } // W2
        try { saveProfiles(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown profiles: " + t); }
        try { saveDeathMemory(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown death memory: " + t); } // combat review 10-23 #5
        try { Ledger.save(); } catch (Throwable t) { System.out.println("[SmartAdv] shutdown ledger: " + t); }
        try { BotChat.Memory.save(); BotChat.Trust.save(); } catch (Throwable ignored) { }
        System.out.println("[SmartAdv] saved everything for shutdown");
    }
    /** The server is stopping: a bot still walking to its grave takes its kit back now, so the restart doesn't lose it (consultant check Q9). */
    @SuppressWarnings("unchecked")
    static void reclaimGravesForShutdown() {
        int bots = 0, stacks = 0;
        java.util.Map<Integer, Object> graves;
        try {
            java.lang.reflect.Field f = core.game.node.entity.combat.graves.GraveController.class.getDeclaredField("activeGraves");
            f.setAccessible(true); graves = (java.util.Map<Integer, Object>) f.get(null);
        } catch (Throwable t) { return; }
        for (Object g : graves.values().toArray()) {
            core.game.node.entity.combat.graves.Grave gr = (core.game.node.entity.combat.graves.Grave) g;
            Brain br = BRAINS.get(String.valueOf(gr.getOwnerUsername()).toLowerCase(Locale.ROOT));
            if (br == null || br.bot == null) { // a real player's grave (Rose lost 7.4k gp of kit to a restart once, bugs-fixed #30)
                try {
                    String owner = String.valueOf(gr.getOwnerUsername());
                    Player hp = Ledger.Wallet.findOnline(owner);
                    java.util.List<int[]> kept = new java.util.ArrayList<>();
                    for (core.game.node.item.GroundItem gi : gr.getItems()) {
                        if (gi == null || !gi.isActive()) continue;
                        Item it = new Item(gi.getId(), gi.getAmount());
                        try { core.game.node.item.GroundItemManager.destroy(gi); } catch (Throwable ignored) { }
                        if (hp != null && !hp.isArtificial()) { if (!hp.getBank().add(it)) hp.getInventory().add(it); } else kept.add(new int[]{it.getId(), it.getAmount()});
                    }
                    if (hp != null && !hp.isArtificial()) hp.sendMessage("<col=0b5e1f>The server is restarting: your gravestone's items are in your bank.");
                    else if (!kept.isEmpty()) { GRAVE_RECOVERY.computeIfAbsent(owner.toLowerCase(Locale.ROOT), k -> new java.util.ArrayList<>()).addAll(kept); saveGraveRecovery(); System.out.println("[SmartAdv] shutdown: " + kept.size() + " item stacks from " + owner + "'s grave kept for their next login"); }
                    try { gr.setTicksRemaining(1); } catch (Throwable ignored) { }
                } catch (Throwable t) { System.out.println("[SmartAdv] player grave at shutdown: " + t); }
                continue;
            }
            int got = 0;
            for (core.game.node.item.GroundItem gi : gr.getItems()) {
                if (gi == null || !gi.isActive()) continue;
                Item it = new Item(gi.getId(), gi.getAmount());
                try { core.game.node.item.GroundItemManager.destroy(gi); } catch (Throwable ignored) { }
                if (!br.bot.getBank().add(it)) br.bot.getInventory().add(it);
                got++;
            }
            try { gr.setTicksRemaining(1); } catch (Throwable ignored) { }
            br.graveAt = null; bots++; stacks += got;
        }
        if (bots > 0) System.out.println("[SmartAdv] shutdown: " + bots + " bots took " + stacks + " item stacks back from their graves");
    }

    /** Items from a real player's gravestone that a restart would have destroyed, waiting for their next login (data/eco/grave-recovery.json). */
    static final Map<String, java.util.List<int[]>> GRAVE_RECOVERY = new java.util.concurrent.ConcurrentHashMap<>();
    static File graveRecoveryFile() { return new File(core.ServerConstants.GRAND_EXCHANGE_DATA_PATH, "grave-recovery.json"); }
    @SuppressWarnings("unchecked")
    static synchronized void saveGraveRecovery() {
        JSONObject root = new JSONObject();
        for (Map.Entry<String, java.util.List<int[]>> e : GRAVE_RECOVERY.entrySet()) { JSONArray a = new JSONArray(); for (int[] it : e.getValue()) { JSONArray p = new JSONArray(); p.add(it[0]); p.add(it[1]); a.add(p); } root.put(e.getKey(), a); }
        try { Ledger.writeAtomic(graveRecoveryFile(), root.toJSONString()); } catch (Throwable t) { System.out.println("[SmartAdv] grave recovery save: " + t); }
    }
    static synchronized void loadGraveRecovery() {
        File f = graveRecoveryFile(); if (!f.exists()) return;
        JSONObject root;
        try (FileReader r = new FileReader(f)) { root = (JSONObject) new JSONParser().parse(r); }
        catch (Throwable t) { Ledger.quarantine(f, "SmartAdv", t, true, false); return; } // audit 10-23 O-1: real players' items; the file is moved aside, new graves still saved
        int bad = 0;
        for (Object k : root.keySet()) {
            try { java.util.List<int[]> l = new java.util.ArrayList<>(); for (Object o : (JSONArray) root.get(k)) { JSONArray p = (JSONArray) o; l.add(new int[]{((Number) p.get(0)).intValue(), ((Number) p.get(1)).intValue()}); } GRAVE_RECOVERY.put(String.valueOf(k), l); }
            catch (Throwable t) { bad++; System.out.println("[SmartAdv] grave recovery entry " + k + " could not be read: " + t + " (audit 10-23 O-1)"); }
        }
        if (bad > 0) Ledger.quarantine(f, "SmartAdv", bad + " unreadable grave entries", false, false); // a copy keeps them for a hand fix
        if (!GRAVE_RECOVERY.isEmpty()) System.out.println("[SmartAdv] " + GRAVE_RECOVERY.size() + " players have gravestone items waiting for them");
    }
    /** Called every minute for each real player online: hands back anything a restart took from their grave. */
    static void recoverGraveFor(Player hp) {
        java.util.List<int[]> l = GRAVE_RECOVERY.remove(hp.getUsername().toLowerCase(Locale.ROOT));
        if (l == null || l.isEmpty()) return;
        int n = 0;
        for (int[] it : l) { Item item = new Item(it[0], it[1]); if (!hp.getBank().add(item)) hp.getInventory().add(item); n++; }
        saveGraveRecovery();
        hp.sendMessage("<col=0b5e1f>Your gravestone was still up when the server restarted: its " + n + " item stacks are in your bank.");
        System.out.println("[SmartAdv] " + hp.getUsername() + " got " + n + " item stacks back from a restart-time grave");
    }

    static final Random RNG = new Random();
    static final Timer TIMER = new Timer("SmartAdventurers", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");
    /** combat review 10-23 #14 (owner, 10-23 11:25 UTC: on by default): NPC-killed bots die by the player rules (keep 3, gravestone, grave run). Off with -Dbots.deathLoss=false or a file data/bots.deathloss.off (re-read every minute). */
    static volatile boolean DEATH_LOSS = deathLossSwitch();
    static boolean deathLossSwitch() { try { return !"false".equalsIgnoreCase(System.getProperty("bots.deathLoss", "true")) && !new File(ServerConstants.DATA_PATH, "bots.deathloss.off").exists(); } catch (Throwable t) { return true; } }
    static final int COUNT = Integer.getInteger("smartbots.count", 90);
    /** E27: a handful of combat 90+ bots with prayer and anti-dragon shields for the dungeon hunts. */
    static final int HIGH = Integer.getInteger("smartbots.high", 10);
    /** Every smart adventurer spawned so far, so the market can tell which bounties someone can actually fill. */
    static final List<Player> BOTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final int[] GATHER_SKILLS = {Skills.FISHING, Skills.MINING, Skills.WOODCUTTING, Skills.THIEVING, Skills.COOKING, Skills.FIREMAKING};
    static final Location LUMBRIDGE = Location.create(3222, 3218, 0);

    /** Jobs an adventurer can take: an AfkTasks task plus the level it needs. */
    static final class Job {
        final AfkTasks.Task task; final int skill, level;
        HuntMap.Hunt hunt; // set for drop-table hunts
        int chain; // P78 (batch 29 chains): Batch29.Chains.SMELT / COOK on a chain job (Batch29.Chains.variant), 0 on every JOBS entry
        int[] prodOv; String labelOv; int spotRule; boolean sharkRow; // W5a (-Dbots.fish.shark): set only on the two harpoon rows with the flag on; null/0/false everywhere else
        boolean rcRow; // W8 (-Dbots.rc.runs): true only on the one rune-run row, which exists only with the flag on
        Job(AfkTasks.Task task, int skill, int level) { this.task = task; this.skill = skill; this.level = level; }
        int[] products() { return hunt != null ? hunt.products : prodOv != null ? prodOv : task.products; }
        String label() { return hunt != null ? HuntMap.label(hunt) : labelOv != null ? labelOv : task.label; }
    }

    static volatile List<Job> JOBS = new ArrayList<>();
    static {
        AfkTasks.Task[] t = AfkTasks.Task.values();
        for (AfkTasks.Task task : t) {
            if (task.kind == AfkTasks.Kind.SMELT || task.products == null) continue;
            if (task.kind == AfkTasks.Kind.GROUND && task != AfkTasks.Task.SNAPE) continue; // snape grass is a real job (D1); the other ground tasks aren't
            if (task == AfkTasks.Task.AUTO || task == AfkTasks.Task.BEST_ROCK || task == AfkTasks.Task.BEST_TREE) continue;
            if (task == AfkTasks.Task.BIG_NET) continue; // P76 review: big net is a players-only ::afk task (not a new bot job; the FISH level switch would also give it 35)
            if (AfkTasks.SPOTS.get(task) == null) continue; // must know where to go
            int skill = -1, level = 1;
            switch (task.kind) {
                case TREE: skill = Skills.WOODCUTTING; level = treeLevel(task); break;
                case ROCK: skill = Skills.MINING; level = AfkTasks.Runner.oreLevel(task.products[0]); break;
                case FISH: skill = Skills.FISHING; level = task == AfkTasks.Task.NET ? 1 : task == AfkTasks.Task.BAIT ? 5 : task == AfkTasks.Task.LURE ? 20 : task == AfkTasks.Task.CAGE ? 40 : 35; break;
                case THIEVE: skill = Skills.THIEVING; level = task == AfkTasks.Task.WOOL ? 1 : task == AfkTasks.Task.MAN ? 1 : task == AfkTasks.Task.FARMER ? 10 : task == AfkTasks.Task.WARRIOR ? 25 : W7cSkills.ardyLevel(task.key, 40); break; // W7c: master farmer 38, knight 55 (rows exist only with -Dbots.thieve.ardy)
                case FIGHT: skill = -1; level = fightLevel(task); break;
                case STALL: { int[] nd = AfkTasks.NEEDS.get(task); skill = Skills.THIEVING; level = nd != null ? nd[1] : 99; break; } // W5b stalls: only the baker's and silk stalls have products, and only with -Dbots.thieve.stalls
                default: break;
            }
            if (task == AfkTasks.Task.SNAPE) { skill = -1; level = 35; } // hobgoblins (level 28) share the peninsula
            JOBS.add(new Job(task, skill, level));
            if (W5aSkills.SHARK && task == AfkTasks.Task.HARPOON) { // W5a (-Dbots.fish.shark): tuna/swordfish row keeps 35; sharks get their own row at 76 on the Net/Harpoon spots
                Job h = JOBS.get(JOBS.size() - 1); h.prodOv = W5aSkills.harpoonFish(true, false, task.products); h.spotRule = W5aSkills.spotRule(true, false);
                Job s = new Job(task, skill, W5aSkills.SHARK_LEVEL); s.prodOv = W5aSkills.harpoonFish(true, true, task.products); s.spotRule = W5aSkills.spotRule(true, true); s.sharkRow = true; s.labelOv = "Sharks";
                JOBS.add(s);
            }
        }
    }

    static int treeLevel(AfkTasks.Task t) {
        switch (t) { case OAK: return 15; case WILLOW: return 30; case MAPLE: return 45; case YEW: return 60; case MAGIC: return 75; default: return 1; }
    }

    /** Combat level a bot should have before taking on this fight. */
    static int fightLevel(AfkTasks.Task t) {
        switch (t) {
            case HILL_GIANT: case BIGBONES: case LIMPWURT: return 35;
            case MOSS_GIANT: return 45;
            case F_GUARD: case HERB_DRUIDS: return 25;
            case WIZ_RUNES: case BARBARIAN: case AL_KHARID: return 15;
            case ROCK_CRAB: return 20;
            default: return 3;
        }
    }

    static { if (RcRuns.ON) JOBS.add(RcRuns.row()); } // W8 (-Dbots.rc.runs): one rune-run row (carrier task ESSENCE, no products); flag off: no row
    static { if (W17b.ON) JOBS.add(W17b.row()); } // W17b (-Dbots.rc.natureLaw, default off): one nature-run row after the rune-run row; flag off: no row

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        loadProfiles();
        loadGraveRecovery();
        loadDeathMemory(); // combat review 10-23 #5
        DEATH_LOSS = deathLossSwitch(); System.out.println("[SmartAdv] bots killed by monsters " + (DEATH_LOSS ? "die by the player rules (keep 3, gravestone)" : "keep everything (player-rules deaths switched off)"));
        TIMER.schedule(new TimerTask() { public void run() { boolean on = deathLossSwitch(); if (on != DEATH_LOSS) { DEATH_LOSS = on; System.out.println("[SmartAdv] player-rules deaths for bots " + (on ? "on" : "off")); } } }, 60_000, 60_000); // combat review 10-23 #14
        TIMER.schedule(new TimerTask() { public void run() { try { saveProfilesSoon(); } catch (Throwable t) { System.out.println("[SmartAdv] save failed: " + t); } } }, FAST ? 60_000 : 600_000, FAST ? 60_000 : 600_000);
        for (int i = 0; i < COUNT; i++) {
            long delay = FAST ? 20_000 + i * 2_000L : 120_000 + i * 15_000L;
            TIMER.schedule(new TimerTask() {
                @Override
                public void run() {
                    GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { // players are made on the game thread
                        @Override
                        public boolean pulse() {
                            try { phStart(); spawn(); phEnd("spawn"); } catch (Throwable t) { System.out.println("[SmartAdv] spawn failed: " + t); try { Failures.Life.loginFailed("adventurer", null, t); } catch (Throwable ignored) { } } // batch 19c §8.1
                            return true;
                        }
                    });
                }
            }, delay);
        }
        for (int i = 0; i < Math.max(HIGH, SAVED_HIGH.size()); i++) { // everyone we remember comes back, even if more than HIGH
            long delay = FAST ? 25_000 + (COUNT + i) * 2_000L : 150_000 + (COUNT + i) * 15_000L;
            TIMER.schedule(new TimerTask() {
                @Override
                public void run() {
                    GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
                        @Override
                        public boolean pulse() {
                            try { phStart(); spawnHigh(); phEnd("spawnHigh"); } catch (Throwable t) { System.out.println("[SmartAdv] high-tier spawn failed: " + t); try { Failures.Life.loginFailed("high", null, t); } catch (Throwable ignored) { } } // batch 19c §8.1
                            return true;
                        }
                    });
                }
            }, delay);
        }
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    HuntMap.build();
                    List<Job> all = new ArrayList<>(JOBS);
                    for (HuntMap.Hunt h : HuntMap.HUNTS) {
                        Job j = new Job(AfkTasks.Task.AUTO, -1, Slayer.huntLevel7(Slayer.FIX7, h.name, h.npcLevel >= 80 ? 90 : Math.max(3, (int) (h.npcLevel * 1.3) + 5))); // the big things: combat 90 plus prayer and a shield
                        j.hunt = h;
                        all.add(j);
                    }
                    JOBS = all; // swap the whole list; the game thread only ever reads it
                    if (W6Herbs.SUPPLY) for (HuntMap.Hunt h : HuntMap.HUNTS) if (h.tag == null && W6Herbs.LIMP_HUNT.equals(h.name)) { // W6 (-Dartisans.herbSupply): limpwurt roots become a job (errands, bounties) at the hill giant hunt's spot
                        final AfkTasks.Spot[] limpAt = {AfkTasks.sp("the hill giants", h.spot.getX(), h.spot.getY())};
                        final Job limp = new Job(AfkTasks.Task.LIMPWURT, -1, fightLevel(AfkTasks.Task.LIMPWURT));
                        GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() { // t62 fix 1: SPOTS is a plain map the game thread reads: written there, then the job joins JOBS
                            try { AfkTasks.SPOTS.put(AfkTasks.Task.LIMPWURT, limpAt); Scen.limpPut(); List<Job> nj = new ArrayList<>(JOBS); nj.add(limp); JOBS = nj; System.out.println("[W6h] limpwurt job at " + limpAt[0].at.getX() + "," + limpAt[0].at.getY()); } catch (Throwable t) { System.out.println("[W6h] limpwurt job failed: " + t); }
                            return true; } });
                        break;
                    }
                    int added = 0;
                    for (HuntMap.Hunt h : HuntMap.HUNTS) for (int id : h.products) if (id != 995 && MarketBrain.CATALOGUE.add(id)) added++; // dragon bones and hides get bounties too
                    System.out.println("[SmartAdv] " + added + " hunt drops added to the market catalogue");
                    System.out.println("[SmartAdv] " + all.size() + " jobs known after adding hunts");
                } catch (Throwable t) { System.out.println("[SmartAdv] hunt map failed: " + t); }
            }
        }, FAST ? 10_000 : 60_000); // after the price index is up
        System.out.println("[SmartAdv] " + COUNT + " smart adventurers scheduled, " + JOBS.size() + " jobs known");
        if (Integer.getInteger("smartbots.testGoal", 0) > 0) TIMER.schedule(new TimerTask() { // trial only: send one bot shopping for a goal
            @Override public void run() {
                GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() {
                    for (Brain br : BRAINS.values()) {
                        if (br.bot == null || !br.bot.isActive() || br.runner != null) continue;
                        int g = Integer.getInteger("smartbots.testGoal", 0);
                        Ledger.Wallet.give(br.bot, MarketBrain.value(g) + 5_000L);
                        br.goal = g;
                        System.out.println("[SmartAdv] TEST: " + br.bot.getUsername() + " given a goal of " + MarketBrain.name(g) + ", obtainable=" + obtainable(br.bot, g) + ", stock=" + MarketBrain.stockOf(g));
                        br.checkGoal();
                        return true;
                    }
                    return false;
                } });
            }
        }, FAST ? 70_000 : 3_600_000);
        if (Boolean.getBoolean("smartbots.testDeath")) TIMER.schedule(new TimerTask() { // trial only: kill one bot that is out on a trip
            @Override public void run() {
                GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() {
                    for (Player b : BOTS) {
                        if (b == null || !b.isActive() || !AfkTasks.RUNNING.containsKey(b.getUsername().toLowerCase(Locale.ROOT)) || b.getLocation().getDistance(LUMBRIDGE) < 60) continue;
                        System.out.println("[SmartAdv] TEST: killing " + b.getUsername() + " at " + b.getLocation() + " wearing " + java.util.Arrays.toString(b.getEquipment().toArray()));
                        b.getImpactHandler().manualHit(b, b.getSkills().getLifepoints() + 5, core.game.node.entity.combat.ImpactHandler.HitsplatType.NORMAL);
                        return true;
                    }
                    return false;
                } });
            }
        }, FAST ? 150_000 : 3_600_000);
    }

    // ---- adventurers that level up and keep it: profiles saved to data/smartbots.json every 10 minutes
    static final class Profile { String name; boolean veteran, high, direct; int[] levels = new int[24]; double[] xp; List<Integer> gear = new ArrayList<>(); long coins; int fav = -2, goal, home = -1; long backAt; String catchphrase, grudge;
        // the owner 2026-10-20: everything a player keeps over a logout is kept too: pack, bank (coins are in `coins`), the arrow stack.
        boolean items; List<int[]> pack = new ArrayList<>(), bank = new ArrayList<>(); int ammoId, ammoAmt; }

    /** Pack, bank (not coins) and quiver of a live bot, into a profile. */
    static void captureItems(Player b, Profile p) {
        p.items = true; p.pack.clear(); p.bank.clear(); p.ammoId = 0; p.ammoAmt = 0;
        try {
            for (Item it : b.getInventory().toArray()) if (it != null && it.getId() != 995) p.pack.add(new int[]{it.getId(), it.getAmount()});
            for (Item it : b.getBank().toArray()) if (it != null && it.getId() != 995) p.bank.add(new int[]{it.getId(), it.getAmount()});
            if (Bob.ON) Bob.addCarried(b, p.bank); // P92 S4 1.9: a beast's load is saved as banked (profile only)
            Item q = b.getEquipment().get(13);
            if (q != null) { p.ammoId = q.getId(); p.ammoAmt = q.getAmount(); }
        } catch (Throwable t) { System.out.println("[SmartAdv] could not read items of " + b.getUsername() + ": " + t); }
    }

    @SuppressWarnings("unchecked")
    static void putItems(JSONObject j, Profile p) {
        if (!p.items) return;
        JSONArray pk = new JSONArray(); for (int[] x : p.pack) { JSONArray e = new JSONArray(); e.add(x[0]); e.add(x[1]); pk.add(e); }
        JSONArray bk = new JSONArray(); for (int[] x : p.bank) { JSONArray e = new JSONArray(); e.add(x[0]); e.add(x[1]); bk.add(e); }
        j.put("pack", pk); j.put("bank", bk);
        if (p.ammoId > 0) { JSONArray a = new JSONArray(); a.add(p.ammoId); a.add(p.ammoAmt); j.put("ammo", a); }
    }

    static void readItems(JSONObject j, Profile p) {
        if (!(j.get("pack") instanceof JSONArray)) return;
        p.items = true;
        for (Object o : (JSONArray) j.get("pack")) { JSONArray e = (JSONArray) o; p.pack.add(new int[]{((Number) e.get(0)).intValue(), ((Number) e.get(1)).intValue()}); }
        if (j.get("bank") instanceof JSONArray) for (Object o : (JSONArray) j.get("bank")) { JSONArray e = (JSONArray) o; p.bank.add(new int[]{((Number) e.get(0)).intValue(), ((Number) e.get(1)).intValue()}); }
        if (j.get("ammo") instanceof JSONArray) { JSONArray a = (JSONArray) j.get("ammo"); p.ammoId = ((Number) a.get(0)).intValue(); p.ammoAmt = ((Number) a.get(1)).intValue(); }
    }

    /** Things a player keeps on them between trips; anything else found in a restored pack was a haul in progress. */
    static boolean keepsInPack(int id) {
        for (int f : AfkTasks.Runner.FOOD) if (f == id) return true;
        if (AfkTasks.hasId(AfkTasks.AXES, id) || AfkTasks.hasId(AfkTasks.PICKAXES, id)) return true;
        if (id >= 554 && id <= 566 || id >= 882 && id <= 892) return true;                       // runes, arrows
        for (int t : new int[]{303, 305, 307, 309, 311, 301, 313, 314, 590, 2347, 1755, 233, 2434, 139, 141, 143, 1733, 1734, 946}) if (t == id) return true; // nets, rods, harpoon, pot, bait, feathers, tinderbox, hammer, chisel, pestle, prayer pots, needle, thread, knife
        try { core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id); if (d != null && (d.getEquipId() > 0 || d.hasAction("drink"))) return true; } catch (Throwable ignored) { }
        return false;
    }

    /**
     * P40 B5 (-Dbots.bootSpread=true, default off): a restored haul goes to the Exchange a few stacks a tick instead of all
     * in the restore pulse (each Ledger.supply books a GE bot offer: two GE-database lookups per stack; a 20-stack haul made
     * the restore pulse 100 ms on the farm and up to 276 ms live). Game thread only (restore and the drain pulse both are).
     */
    static final boolean BOOT_SPREAD = Boolean.getBoolean("bots.bootSpread");
    static final int HAUL_PER_TICK = Math.max(1, Integer.getInteger("bots.bootSpread.haulPerTick", 3));
    static final java.util.ArrayDeque<Object[]> HAUL_Q = new java.util.ArrayDeque<>();
    static boolean haulDraining;
    static int haulMaxTick, haulDrained; // read by TestP40Boot
    static void haulLater(Player who, int id, int n) {
        HAUL_Q.addLast(new Object[]{who, id, n});
        if (haulDraining) return;
        haulDraining = true;
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
            @Override
            public boolean pulse() {
                int k = 0;
                while (k < HAUL_PER_TICK && !HAUL_Q.isEmpty()) {
                    Object[] e = HAUL_Q.pollFirst(); k++;
                    try { Ledger.supply((Player) e[0], (Integer) e[1], (Integer) e[2]); } catch (Throwable t) { System.out.println("[SmartAdv] haul to the Exchange failed: " + t); }
                }
                haulDrained += k; if (k > haulMaxTick) haulMaxTick = k;
                if (!HAUL_Q.isEmpty()) return false;
                haulDraining = false;
                return true;
            }
        });
    }

    /** Puts a profile's saved pack, bank and quiver back. A haul it was carrying when the server went down goes to the Exchange through the ledger (it is paid when it sells), as if it had got to the bank. */
    static void restoreItems(AIPlayer bot, Profile prof) {
        if (!prof.items) return;
        try {
            bot.getInventory().clear();
            for (Item it : bot.getBank().toArray()) if (it != null && it.getId() != 995) bot.getBank().remove(it);
            int hauled = 0;
            for (int[] x : prof.bank) bot.getBank().add(new Item(x[0], x[1]));
            Map<Integer, Integer> heldSoFar = QuestRunner.FIX3 && QuestRunner.KEEP_QUEST_ITEMS ? new java.util.HashMap<>() : null; // quest3: the hold is counted over the whole pack
            for (int[] x : prof.pack) {
                int held = heldSoFar != null ? QuestBook.holdEntry(bot.getUsername(), x[0], x[1], heldSoFar) : QuestRunner.KEEP_QUEST_ITEMS ? Math.min(x[1], QuestBook.holdCount(bot.getUsername(), x[0])) : 0; // quest1 M3: a quest's own items are not a haul
                if (held > 0) { if (!bot.getInventory().add(new Item(x[0], held))) bot.getBank().add(new Item(x[0], held)); if (held >= x[1]) continue; x = new int[]{x[0], x[1] - held}; }
                if (!keepsInPack(x[0]) && MarketBrain.tradeable(x[0])) { if (BOOT_SPREAD) haulLater(bot, x[0], x[1]); else Ledger.supply(bot, x[0], x[1]); hauled += x[1]; if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + bot.getUsername() + " haul item " + x[0] + " x" + x[1]); continue; }
                if (!bot.getInventory().add(new Item(x[0], x[1]))) bot.getBank().add(new Item(x[0], x[1]));
            }
            if (prof.ammoId > 0 && prof.ammoAmt > 0) { Item cur = bot.getEquipment().get(13); if (cur != null) bot.getEquipment().remove(cur); bot.getEquipment().add(new Item(prof.ammoId, prof.ammoAmt), -1, true, false); }
            bot.getInventory().refresh(); bot.getEquipment().refresh();
            if (hauled > 0) System.out.println("[SmartAdv] " + bot.getUsername() + " came back carrying " + hauled + " items of a haul: sent to the Exchange");
            if (Boolean.getBoolean("afk.debug")) { StringBuilder sb = new StringBuilder(); for (Item it : bot.getInventory().toArray()) if (it != null) sb.append(it.getId()).append('x').append(it.getAmount()).append(' '); System.out.println("[SmartAdv] " + bot.getUsername() + " pack after restore: " + sb); }
        } catch (Throwable t) { System.out.println("[SmartAdv] could not restore items of " + bot.getUsername() + ": " + t); }
    }
    /** A2: a catchphrase and a grudge per bot, generated once and saved: key -> {catchphrase, grudge, home town name}. */
    static final Map<String, String[]> STORY = new java.util.concurrent.ConcurrentHashMap<>();
    static String grudgeOf(Player p) { String[] s = p == null ? null : STORY.get(p.getUsername().toLowerCase(Locale.ROOT)); return s == null || s.length < 2 ? null : s[1]; }
    static String catchOf(Player p) { String[] s = p == null ? null : STORY.get(p.getUsername().toLowerCase(Locale.ROOT)); return s == null || s.length < 1 ? null : s[0]; }
    /** Grudges against monsters a bot can actually go and fight: grudge -> words in the monster's name. */
    static final Map<String, String[]> GRUDGE_FOES = new java.util.HashMap<>();
    static {
        GRUDGE_FOES.put("goblins", new String[]{"goblin"}); GRUDGE_FOES.put("hill giants", new String[]{"hill giant"});
        GRUDGE_FOES.put("chickens", new String[]{"chicken"}); GRUDGE_FOES.put("seagulls", new String[]{"seagull"});
        GRUDGE_FOES.put("mugger ambushes", new String[]{"mugger"}); GRUDGE_FOES.put("the Varrock guards", new String[]{"guard"});
        GRUDGE_FOES.put("dwarves who won't share the furnace", new String[]{"dwarf"});
    }
    static boolean grudgeFoe(Player p, String monster) {
        String[] w = GRUDGE_FOES.get(grudgeOf(p));
        if (w == null || monster == null) return false;
        String m = monster.toLowerCase(Locale.ROOT);
        for (String x : w) if (m.contains(x)) return true;
        return false;
    }
    static final String[] CATCHPHRASES = {"Every ore counts.", "Coin in, coin out.", "If it's free, it's mine.", "Bank first, questions later.", "Never skip breakfast.", "Trust the pickaxe.", "Rome wasn't mined in a day.", "Fish don't bite themselves.", "Steady hands, full pockets.", "A goblin a day keeps the boredom away.", "Bigger rocks, bigger dreams.", "Mind your back.", "Today's the day. Probably.", "Keep calm and bank it.", "Can't stop, won't stop.", "Onwards and upwards.", "Another day, another coin.", "Could be worse. Could be raining.", "Mind the gap!", "That's the spirit.", "Slow and steady.", "No rest for the wicked.", "Easy money, hard walk.", "Right, where was I?", "You've got to laugh.", "Keep your pick sharp.", "Bank's the best view in town.", "One more then I'm done. Probably.", "Fortune favours the stubborn.", "Never trust a quiet goblin."};
    static final String[] GRUDGES = {"goblins", "the Lumbridge cook", "hill giants", "the Exchange clerks", "the rain in Rimmington", "the Al Kharid gate toll", "that gnome on the glider", "chickens", "the Draynor bank guard", "dwarves who won't share the furnace", "the Wizards' Tower stairs", "mugger ambushes", "the Varrock guards", "seagulls", "the Port Sarim monks"};

    /** The story of a bot for the chat: {catchphrase, grudge, home town}, or null for a bot that isn't ours. */
    static String[] story(Player bot) {
        if (bot == null) return null;
        String key = bot.getUsername().toLowerCase(Locale.ROOT);
        String[] st = STORY.get(key);
        if (st == null) return null;
        int home = HOME.getOrDefault(key, -1);
        return new String[]{st[0], st[1], home >= 0 ? BotLife.Town.values()[home % BotLife.Town.values().length].name : "nowhere in particular"};
    }
    static final Map<String, Boolean> HIGH_TIER = new java.util.concurrent.ConcurrentHashMap<>();
    /** This boot: when each bot first went out, and when it was first paid for something (for the Economy page). */
    static final Map<String, Long> ENLISTED = new java.util.concurrent.ConcurrentHashMap<>(), FIRST_PAID = new java.util.concurrent.ConcurrentHashMap<>();
    /** Living brains by bot name (for the economy page) and the last deaths (name, where, when). */
    static final Map<String, Brain> BRAINS = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Deque<String[]> DEATHS = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** S11 (shared): job label -> times of deaths there. Two deaths in a day closes the spot to everyone for 24 h, and
     *  every death raises the combat level bots want before going (word gets round). Overnight 2026-10-20: 58 deaths in 8 h. */
    static final Map<String, java.util.List<Long>> SPOT_DEATHS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * combat review 10-23 #5: SPOT_DEATHS, each bot's blacklist and its death count, kept over a restart (61 starts in 3.4 days
     * let Black demons kill 6 in 14 h where the rule allows 2). data/eco/death-memory.json; a missing or older file loads empty.
     */
    static final Map<String, Map<String, Long>> SAVED_BLACKLISTS = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, Integer> SAVED_DEATHS = new java.util.concurrent.ConcurrentHashMap<>();
    static File deathMemoryFile() { return new File(core.ServerConstants.GRAND_EXCHANGE_DATA_PATH, "death-memory.json"); }

    static void loadDeathMemory() {
        try {
            File f = deathMemoryFile(); if (!f.exists()) return;
            JSONObject root = (JSONObject) new JSONParser().parse(new FileReader(f));
            long cut = System.currentTimeMillis() - 24L * 3600 * 1000, now = System.currentTimeMillis();
            int spots = 0, bots = 0;
            if (root.get("spots") instanceof JSONObject) for (Object e : ((JSONObject) root.get("spots")).entrySet()) {
                Map.Entry<?, ?> me = (Map.Entry<?, ?>) e; if (!(me.getValue() instanceof JSONArray)) continue;
                java.util.List<Long> l = new java.util.ArrayList<>();
                for (Object t : (JSONArray) me.getValue()) if (t instanceof Number && ((Number) t).longValue() >= cut) l.add(((Number) t).longValue());
                if (!l.isEmpty()) { SPOT_DEATHS.put(String.valueOf(me.getKey()), l); spots++; }
            }
            if (root.get("bots") instanceof JSONObject) for (Object e : ((JSONObject) root.get("bots")).entrySet()) {
                Map.Entry<?, ?> me = (Map.Entry<?, ?>) e; if (!(me.getValue() instanceof JSONObject)) continue;
                JSONObject b = (JSONObject) me.getValue(); String name = String.valueOf(me.getKey()).toLowerCase(Locale.ROOT);
                if (b.get("deaths") instanceof Number) SAVED_DEATHS.put(name, ((Number) b.get("deaths")).intValue());
                Map<String, Long> bl = new java.util.HashMap<>();
                if (b.get("blacklist") instanceof JSONObject) for (Object x : ((JSONObject) b.get("blacklist")).entrySet()) { Map.Entry<?, ?> xe = (Map.Entry<?, ?>) x; if (xe.getValue() instanceof Number && ((Number) xe.getValue()).longValue() > now) bl.put(String.valueOf(xe.getKey()), ((Number) xe.getValue()).longValue()); }
                if (!bl.isEmpty()) SAVED_BLACKLISTS.put(name, bl);
                bots++;
            }
            System.out.println("[SmartAdv] death memory: " + spots + " spots with deaths in the last day, " + bots + " bots' death records");
        } catch (Throwable t) { System.out.println("[SmartAdv] death memory load: " + t); Ledger.quarantine(deathMemoryFile(), "SmartAdv", t, false, true); } // audit 10-23 batch14 item1: kept and never saved over this boot
    }

    /** Built on the game thread (where deaths are handled), written on the timer. */
    @SuppressWarnings("unchecked")
    static void saveDeathMemorySoon() {
        final long at = System.nanoTime(); final String json = buildDeathMemory();
        TIMER.schedule(new TimerTask() { public void run() { writeDeathMemory(json, at); } }, 0);
    }
    static void saveDeathMemory() { writeDeathMemory(buildDeathMemory(), System.nanoTime()); }
    static long deathMemoryWrittenAt = Long.MIN_VALUE;
    static synchronized void writeDeathMemory(String json, long builtAt) {
        if (builtAt < deathMemoryWrittenAt) return; // an older snapshot never overwrites a newer one
        deathMemoryWrittenAt = builtAt;
        try { Ledger.writeAtomic(deathMemoryFile(), json); } catch (Throwable t) { System.out.println("[SmartAdv] death memory save: " + t); }
    }
    @SuppressWarnings("unchecked")
    static String buildDeathMemory() {
        long now = System.currentTimeMillis(), cut = now - 24L * 3600 * 1000;
        for (Map.Entry<String, Brain> e : BRAINS.entrySet()) { // the live brains are the newest word on their own bots
            Brain br = e.getValue(); Map<String, Long> bl = new java.util.HashMap<>();
            for (Map.Entry<String, Long> x : new java.util.ArrayList<>(br.blacklist.entrySet())) if (x.getValue() != null && x.getValue() > now) bl.put(x.getKey(), x.getValue());
            if (bl.isEmpty()) SAVED_BLACKLISTS.remove(e.getKey()); else SAVED_BLACKLISTS.put(e.getKey(), bl);
            if (br.deaths > 0) SAVED_DEATHS.put(e.getKey(), br.deaths);
        }
        JSONObject root = new JSONObject(), spots = new JSONObject(), bots = new JSONObject();
        for (Map.Entry<String, java.util.List<Long>> e : SPOT_DEATHS.entrySet()) {
            JSONArray a = new JSONArray(); synchronized (e.getValue()) { for (Long t : e.getValue()) if (t != null && t >= cut) a.add(t); }
            if (!a.isEmpty()) spots.put(e.getKey(), a);
        }
        java.util.Set<String> names = new java.util.HashSet<>(SAVED_DEATHS.keySet()); names.addAll(SAVED_BLACKLISTS.keySet());
        for (String n : names) {
            JSONObject b = new JSONObject(); b.put("deaths", SAVED_DEATHS.getOrDefault(n, 0));
            JSONObject bl = new JSONObject(); Map<String, Long> m = SAVED_BLACKLISTS.get(n); if (m != null) for (Map.Entry<String, Long> x : m.entrySet()) if (x.getValue() > now) bl.put(x.getKey(), x.getValue());
            b.put("blacklist", bl); bots.put(n, b);
        }
        root.put("spots", spots); root.put("bots", bots);
        return root.toJSONString();
    }

    static int recentDeaths(String label) {
        java.util.List<Long> l = SPOT_DEATHS.get(label);
        if (l == null) return 0;
        long cut = System.currentTimeMillis() - 24L * 3600 * 1000;
        synchronized (l) { l.removeIf(t -> t < cut); return l.size(); }
    }

    /** Monsters that hit with magic through melee armour: a melee bot needs a big margin. */
    static boolean magicUser(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("mage") || n.contains("wizard") || n.contains("sorcer") || n.contains("necromancer") || n.contains("shaman") || n.contains("druid");
    }
    static final Map<String, Integer> HOME = new java.util.concurrent.ConcurrentHashMap<>();
    static final List<Profile> SAVED = new ArrayList<>();
    /** High-tier profiles are restored by spawnHigh(), never by the regular slots (they used to sit past slot 90 and be lost, and 10 new ones were minted each restart). */
    static final List<Profile> SAVED_HIGH = new ArrayList<>();
    static int spawnedHigh;
    /** Bots on a break (logged out for a few hours); their offers stay live and their coins accrue in the ledger. */
    static final List<Profile> OFFLINE = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final Map<String, Integer> FAV = new java.util.concurrent.ConcurrentHashMap<>(), GOAL = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, Boolean> VETERAN = new java.util.concurrent.ConcurrentHashMap<>();
    static int spawned;

    static File profileFile() { return new File(ServerConstants.DATA_PATH, "smartbots.json"); }

    static final java.util.Set<String> KNOWN_NAMES = java.util.concurrent.ConcurrentHashMap.newKeySet(); // every name in the profile file (lower case)

    static void loadProfiles() {
        File f = profileFile();
        if (!f.exists()) return;
        JSONArray arr;
        try (FileReader r = new FileReader(f)) { arr = (JSONArray) new JSONParser().parse(r); }
        catch (Throwable t) { Ledger.quarantine(f, "SmartAdv", t, false, true); return; } // audit 10-23 O-1: an unreadable book is never saved over
        int dupes = 0, bad = 0; java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object o : arr) {
            try { // audit 10-23 O-1: one bad entry used to stop every later bot loading (and the next save dropped them all)
                JSONObject j = (JSONObject) o; Profile p = new Profile();
                p.name = String.valueOf(j.get("name")); KNOWN_NAMES.add(p.name.toLowerCase(Locale.ROOT)); if (!seen.add(p.name.toLowerCase(Locale.ROOT))) { dupes++; continue; } p.veteran = Boolean.TRUE.equals(j.get("veteran")); // audit B-14: a name twice in the book was restored twice (coins and all); the first (live) copy wins
                JSONArray lv = (JSONArray) j.get("levels");
                for (int i = 0; i < 24 && i < lv.size(); i++) p.levels[i] = ((Number) lv.get(i)).intValue();
                if (j.get("gear") instanceof JSONArray) for (Object g : (JSONArray) j.get("gear")) p.gear.add(((Number) g).intValue());
                if (j.get("coins") != null) p.coins = ((Number) j.get("coins")).longValue();
                if (j.get("fav") != null) p.fav = ((Number) j.get("fav")).intValue();
                if (j.get("goal") != null) p.goal = ((Number) j.get("goal")).intValue();
                if (j.get("home") != null) p.home = ((Number) j.get("home")).intValue();
                p.high = Boolean.TRUE.equals(j.get("high"));
                p.direct = Boolean.TRUE.equals(j.get("direct")); if (p.direct) DIRECT_NAMES.add(p.name.toLowerCase(Locale.ROOT)); // P31 fix 1: a name without the counter tail, restored by directBot
                if (j.get("catch") != null) p.catchphrase = String.valueOf(j.get("catch"));
                if (j.get("xp") instanceof JSONArray) { JSONArray xa = (JSONArray) j.get("xp"); p.xp = new double[24]; for (int i = 0; i < 24 && i < xa.size(); i++) p.xp[i] = ((Number) xa.get(i)).doubleValue(); }
                if (j.get("grudge") != null) p.grudge = String.valueOf(j.get("grudge"));
                readItems(j, p);
                if (p.levels[3] >= 10) { if (p.high) SAVED_HIGH.add(p); else SAVED.add(p); }
            } catch (Throwable t) {
                bad++; BAD_PROFILES.add(o); // written back unchanged by every save, so nothing in it is lost
                Object n = o instanceof JSONObject ? ((JSONObject) o).get("name") : null;
                System.out.println("[SmartAdv] profile entry " + (n != null ? n : "#" + (dupes + bad + SAVED.size() + SAVED_HIGH.size())) + " could not be read, kept as it is: " + t + " (audit 10-23 O-1)");
            }
        }
        if (bad > 0) Ledger.quarantine(f, "SmartAdv", bad + " unreadable entries", false, false);
        if (bad > 0) try { Failures.event("save", null, "load", "profile-unreadable", bad + " unreadable entries", null, Failures.ctx("bad", bad, "loaded", SAVED.size() + SAVED_HIGH.size(), "file", f.getName())); } catch (Throwable ignored) { } // batch 19c §10
        System.out.println("[SmartAdv] " + SAVED.size() + " adventurers and " + SAVED_HIGH.size() + " high-tier remembered from last time" + (dupes > 0 ? " (" + dupes + " duplicate entries skipped)" : "") + (bad > 0 ? " (" + bad + " unreadable entries kept as they are)" : ""));
        long coins = 0; for (Profile p : SAVED) coins += p.coins; for (Profile p : SAVED_HIGH) coins += p.coins;
        System.out.println("[CoinWatch] boot book " + coins + " gp in " + (SAVED.size() + SAVED_HIGH.size()) + " profiles (owed coins are in the ledger, not in this figure)"); // restart-coin-drop: equals the last "[CoinWatch] shutdown total" minus its owed part when nothing is lost
        for (Object o : BAD_PROFILES) if (o instanceof JSONObject && ((JSONObject) o).get("coins") instanceof Number) coins += ((Number) ((JSONObject) o).get("coins")).longValue();
        try { sanityCheck(f, SAVED.size() + SAVED_HIGH.size() + bad, coins); } catch (Throwable t) { System.out.println("[SmartAdv] sanity check: " + t); } // audit 10-23 batch14 item2
    }

    /**
     * audit 10-23 batch14 item2: data/eco/last-save.json holds the bot count and the coins in the book at the last smartbots.json save. A
     * book that loads with under half of either (a truncated but valid file, an old copy put back) is not saved over this boot:
     * the same copy-and-block as an unreadable book. Check it by hand, then put the right smartbots.json back or delete
     * last-save.json, and restart. No sidecar (first boot) or a tiny world (under 4 bots, under 10,000 gp): no check.
     */
    static File lastSaveFile() { return new File(core.ServerConstants.GRAND_EXCHANGE_DATA_PATH, "last-save.json"); }
    static void sanityCheck(File book, int count, long coins) throws Exception {
        File sf = lastSaveFile();
        if (!sf.exists()) { System.out.println("[SmartAdv] sanity check: no " + sf.getName() + " yet (first boot with it); loaded " + count + " bots, " + coins + " gp"); return; }
        JSONObject j = (JSONObject) new JSONParser().parse(new String(java.nio.file.Files.readAllBytes(sf.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        long lastCount = ((Number) j.get("count")).longValue(), lastCoins = ((Number) j.get("coins")).longValue();
        boolean fewBots = lastCount >= 4 && count * 2L < lastCount, fewCoins = lastCoins >= 10_000 && coins * 2L < lastCoins;
        if (!fewBots && !fewCoins) { System.out.println("[SmartAdv] sanity check ok: " + count + " bots, " + coins + " gp (last save " + lastCount + " bots, " + lastCoins + " gp)"); return; }
        String why = "SANITY CHECK FAILED: loaded " + count + " bots and " + coins + " gp, the last save had " + lastCount + " bots and " + lastCoins + " gp (under half)";
        System.out.println("[SmartAdv] !!!!! " + why + ". smartbots.json is NOT saved this boot; check it by hand, then restore the right file or delete data/eco/" + sf.getName() + " and restart (audit 10-23 batch14 item2)");
        Ledger.quarantine(book, "SmartAdv", why, false, true);
        try { Failures.event("save", null, "boot", "sanity-failed", "under half of the last save", null, Failures.ctx("count", count, "coins", coins, "lastCount", lastCount, "lastCoins", lastCoins, "file", book.getName())); } catch (Throwable ignored) { } // batch 19c §10 (no names)
    }
    @SuppressWarnings("unchecked")
    static void writeLastSave(JSONArray arr) {
        long coins = 0; for (Object o : arr) if (o instanceof JSONObject && ((JSONObject) o).get("coins") instanceof Number) coins += ((Number) ((JSONObject) o).get("coins")).longValue();
        try { Failures.Econ.worldSave(coins, arr.size()); } catch (Throwable ignored) { } // batch 19c §4.1 coin-drift-world (numbers only, this timer thread)
        JSONObject j = new JSONObject(); j.put("count", arr.size()); j.put("coins", coins); j.put("at", java.time.Instant.now().toString());
        try { Ledger.writeAtomic(lastSaveFile(), j.toJSONString()); } catch (Throwable t) { System.out.println("[SmartAdv] last-save sidecar: " + t); }
    }
    /** audit 10-23 O-1: profile entries that failed to load, saved back unchanged (unless a bot of that name was saved this boot). */
    static final List<Object> BAD_PROFILES = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Audit B-14: the 10-minute save reads BOTS, OFFLINE and the banks on the game thread (a pulse), then writes the file on the timer. */
    static void saveProfilesSoon() {
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
            @Override public boolean pulse() {
                final long t0 = System.nanoTime(); // batch 20 (19b+c follow-up 4)
                try { saveCrops(null); } catch (Throwable ignored) { } // W2: crop files from the same moment as the profile book
                try { final long at = System.nanoTime(); final JSONArray arr = buildProfiles(); if (arr != null) TIMER.schedule(new TimerTask() { public void run() { writeProfiles(arr, at); } }, 0); } catch (Throwable t) { System.out.println("[SmartAdv] save failed: " + t); try { Failures.Life.saveFailed(profileFile().getName(), t); } catch (Throwable ignored) { } } // batch 19c §10
                try { overdue(System.currentTimeMillis()); } catch (Throwable ignored) { } // batch 19c §8.1 return-overdue
                try { retryRestores(System.currentTimeMillis()); } catch (Throwable t) { System.out.println("[S-10] retry pass: " + t); } // batch 22 S-10 (after the snapshot: a retried bot is saved by the next one)
                Watchdog.fpulse("SmartAdventurers.saveProfiles", null, System.nanoTime() - t0); // batch 20 (19b+c follow-up 4)
                return true;
            }
        });
    }

    /** Shutdown (and anything already on the game thread): build and write now. */
    /** W2 FarmSave: writes the crop file of every online bot with crops (10-minute pulse, shutdown). A bot mid-run with nothing due yet is saved with a 15 min check. Returns files written. */
    static int saveCrops(String why) {
        if (!FarmSave.ON) return 0;
        int n = 0; long now = System.currentTimeMillis();
        for (Brain b : BRAINS.values()) try {
            if (b == null || b.bot == null || b.farmSite < 0 || b.farmSite >= Farming.SITES.length) continue;
            final String tk = b.treeSaveKey(); // w9 trees: a tree-only bot's key (null with the flag off)
            long due = tk != null ? b.treeDue : FarmSave.pulseDue(b.farmDue, b.farm, now);
            if (due != 0 && FarmSave.save(b.bot, b.bot.getUsername(), tk != null ? tk : Farming.SITES[b.farmSite].key, due, why)) n++;
        } catch (Throwable ignored) { }
        return n;
    }
    static void saveProfiles() { long at = System.nanoTime(); JSONArray arr = buildProfiles(); if (arr != null) writeProfiles(arr, at); }

    static long profilesWrittenAt = Long.MIN_VALUE;

    static final long OVERDUE_MS = Long.getLong("failures.overdueSec", 600L) * 1000L; // batch 19c §8.1: 10 min late (a property for trials)
    /** batch 19c §8.1 return-overdue: a profile still on a break 10 min after its backAt (game thread, the 10-minute save pulse). */
    static void overdue(long now) {
        for (Profile p : OFFLINE) {
            if (p == null || p.backAt <= 0 || p.backAt >= now - OVERDUE_MS) continue;
            if (!Failures.gate("overdue|" + p.name, 3_600_000L)) continue;
            Failures.event("lifecycle", null, "login", "return-overdue", p.name + " " + (now - p.backAt) / 60_000 + " min late", null, Failures.ctx("profile", p.name, "lateMin", (now - p.backAt) / 60_000, "role", p.high ? "high" : "adventurer"));
        }
    }
    /** batch 19c §8.1: the end of a break (was the inline pulse body in logOut; same calls, plus login-failed at the catch). */
    static void comeBack(Profile p) {
        restoringProf = p; // batch 22 S-10: in the book while it is out of OFFLINE and not yet in BOTS (a shutdown save in between)
        OFFLINE.remove(p);
        restoreSafe(p, LUMBRIDGE.transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0), "comeBack", 0); // batch 22 S-10: was dropped from both lists when restore threw
    }

    // ---- batch 22 S-10: a profile whose restore or enlist throws is kept (RETRY) and saved, and tried again on the 10-minute save pulse.
    static final class Retry {
        final Profile p; final String where; final int attempts; final long nextAt; final boolean gaveUp;
        Retry(Profile p, String where, int attempts, long nextAt, boolean gaveUp) { this.p = p; this.where = where; this.attempts = attempts; this.nextAt = nextAt; this.gaveUp = gaveUp; }
    }
    static final List<Retry> RETRY = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final int RESTORE_TRIES = Math.max(1, Integer.getInteger("smartbots.restoreTries", 3)); // tries per boot, the first one included
    static final long RETRY_MS = Math.max(0L, Long.getLong("smartbots.retrySec", 600L)) * 1000L;
    /** Game thread: the profile being restored now and the bot made for it (both null between restores); buildProfiles writes the profile while it is in neither list. */
    static volatile Profile restoringProf;
    static volatile AIPlayer restoringBot;
    static volatile long restoringCollected; // coins Wallet.collect moved into restoringBot (put back owed if that bot is discarded)
    static volatile int s10Failed, s10Recovered, s10GaveUp, s10Live; // trials (TestB22S10)
    /** TestB22S10 only: throw at "restore" (after the coins) or "enlist" (before BOTS.add) for this profile name ("*": any), S10_FAULT_LEFT times. */
    static volatile String S10_FAULT_NAME, S10_FAULT_AT;
    static volatile int S10_FAULT_LEFT;
    static void s10Fault(String at, String name) {
        String n = S10_FAULT_NAME;
        if (n == null || S10_FAULT_LEFT <= 0 || !at.equals(S10_FAULT_AT) || !"*".equals(n) && !n.equalsIgnoreCase(name)) return;
        S10_FAULT_LEFT--;
        throw new IllegalStateException("S-10 fault injection at " + at);
    }

    /** Game thread. restore() that never loses the profile: on a throw it goes to RETRY (one [S-10] line), the half-made bot is discarded. */
    static boolean restoreSafe(Profile p, Location start, String where, int triedBefore) {
        restoringProf = p; restoringBot = null; restoringCollected = 0;
        try {
            restore(p, start);
            if (triedBefore > 0) { s10Recovered++; System.out.println("[S-10] " + p.name + " restored on try " + (triedBefore + 1) + "/" + RESTORE_TRIES + " (" + where + ")"); }
            return true;
        } catch (Throwable t) {
            AIPlayer half = restoringBot;
            if (half != null && BOTS.contains(half)) { s10Live++; System.out.println("[S-10] restore of " + p.name + " threw after it was enlisted (" + where + "): " + t + "; it is up as " + half.getUsername() + " and saved from BOTS"); return true; }
            int n = triedBefore + 1; boolean last = n >= RESTORE_TRIES;
            String undo = discardHalf(half, p, restoringCollected);
            RETRY.add(new Retry(p, where, n, System.currentTimeMillis() + RETRY_MS, last)); // before restoringProf is cleared: always in one list
            s10Failed++; if (last) s10GaveUp++;
            System.out.println("[S-10] restore of " + p.name + " failed (" + where + ", try " + n + "/" + RESTORE_TRIES + "): " + t + undo
                    + (last ? "; no more tries this boot, the profile stays in smartbots.json" : "; kept in the retry list, next try in " + RETRY_MS / 1000 + " s"));
            try { Failures.Life.loginFailed(p.high ? "high" : "adventurer", p.name, t); } catch (Throwable ignored) { } // batch 19c §8.1, as before
            return false;
        } finally { restoringProf = null; restoringBot = null; restoringCollected = 0; }
    }

    /** S-10: the bot a failed restore made (not in BOTS) is logged out like logOut does, and what restore moved onto it goes back to the profile's name. */
    static String discardHalf(AIPlayer half, Profile p, long collected) {
        if (half == null) return "";
        String name = half.getUsername(), key = name.toLowerCase(Locale.ROOT);
        try { Brain b = BRAINS.remove(key); if (b != null) { b.running = false; if (b.crew != null) b.crew.leave(b); } } catch (Throwable ignored) { }
        try { AfkTasks.RUNNING.remove(key); } catch (Throwable ignored) { }
        try { BotChat.clearActivity(half); } catch (Throwable ignored) { }
        try { RouteMap.forget(key); } catch (Throwable ignored) { }
        try { Preflight.forget(half); } catch (Throwable ignored) { }
        try { NavWalker.forget(half); } catch (Throwable ignored) { }
        if (collected > 0) Ledger.Wallet.OFFLINE.merge(key, collected, Long::sum); // the inverse of Wallet.collect: still owed, not lost with the discarded bot
        if (!name.equalsIgnoreCase(p.name)) { try { QuestBook.rename(name, p.name); } catch (Throwable ignored) { } try { Ledger.rename(name, p.name); } catch (Throwable ignored) { } }
        boolean cleared = true;
        try { half.clear(); } catch (Throwable t) { cleared = false; }
        return "; half-made bot " + name + (cleared ? " logged out" : " could not be logged out") + (collected > 0 ? ", " + collected + " gp put back as owed" : "");
    }

    /** Game thread (the 10-minute save pulse): one more try for each retry that is due. */
    static void retryRestores(long now) {
        for (Retry r : RETRY) {
            if (r.gaveUp || r.nextAt > now) continue;
            restoringProf = r.p; // in the book between the remove and the restore
            if (!RETRY.remove(r)) { restoringProf = null; continue; }
            restoreSafe(r.p, LUMBRIDGE.transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0), "retry of " + r.where, r.attempts);
        }
    }
    /** batch 19c §8.1 logout-mid-task (game thread, top of logOut). A quest is by design (stopHere keeps the checkpoint): counted only. */
    static void midTask(AIPlayer bot, Brain b) {
        if (b == null) return;
        String task = b.runner != null ? "runner" : b.shopAt != null ? "shop" : b.graveAt != null ? "grave" : b.delivering ? "delivery" : b.mill != 0 ? "mill" : Scenes.Greet.escorting(bot) ? "escort" : b.bone != 0 ? "bury" : b.w37 != null ? "craft" : b.summon != 0 ? "summon" : b.slay != 0 ? "slayer" : b.farm != 0 ? "farm" : Deals.involved(bot) ? "deal" : PlayerTrades.SESSIONS.containsKey(bot) ? "trade" : BRAIN_FIX1 && b.errand() ? (b.agiStep >= 0 ? "agility" : "star") : null;
        if (task == null) { if (QuestRunner.active(bot)) Failures.counted("logout-mid-quest"); return; }
        Failures.event("lifecycle", bot, "logout", "logout-mid-task", task, null, Failures.ctx("task", task, "shiftOverMin", (System.currentTimeMillis() - b.shiftEnd) / 60_000));
    }
    /** An older snapshot never overwrites a newer one (the shutdown save can overtake a queued timer write). */
    static synchronized void writeProfiles(JSONArray arr, long builtAt) {
        if (builtAt < profilesWrittenAt) return;
        profilesWrittenAt = builtAt;
        try { Ledger.writeAtomic(profileFile(), arr.toJSONString()); if (!Ledger.blocked(profileFile())) writeLastSave(arr); } catch (Throwable t) { System.out.println("[SmartAdv] could not save profiles: " + t); try { Failures.Life.saveFailed(profileFile().getName(), t); } catch (Throwable ignored) { } } // batch 19c §10 save-failed // audit 10-23 batch14 item2: the sidecar only follows a real save
    }

    /** The profile book as saved (format unchanged), or null when it must not be written. One entry per name (audit B-14: a bot mid-logout was in BOTS and OFFLINE). */
    @SuppressWarnings("unchecked")
    static JSONArray buildProfiles() {
        JSONArray arr = new JSONArray();
        java.util.Set<String> written = new java.util.HashSet<>();
        for (Player b : BOTS) {
            if (b == null || !b.isActive()) continue;
            if (!written.add(b.getUsername().toLowerCase(Locale.ROOT))) continue;
            JSONObject j = new JSONObject(); JSONArray lv = new JSONArray();
            for (int i = 0; i < 24; i++) lv.add(b.getSkills().getStaticLevel(i));
            JSONArray gear = new JSONArray();
            try { Item[] eq = b.getEquipment().toArray(); for (int s = 0; s < eq.length; s++) if (eq[s] != null && s != 13) gear.add(eq[s].getId()); if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] save " + b.getUsername() + ": equipment slots " + eq.length + ", worn " + gear.size() + ", coins " + Ledger.Wallet.coins(b)); } catch (Throwable t) { System.out.println("[SmartAdv] could not read gear of " + b.getUsername() + ": " + t); }
            String key = b.getUsername().toLowerCase(Locale.ROOT);
            Brain sb = BRAINS.get(key); if (sb != null) { if (sb.stashHelm != 0) gear.add(sb.stashHelm); if (sb.stashShield != 0) gear.add(sb.stashShield); } // off for the break, still theirs
            j.put("name", b.getUsername()); j.put("veteran", VETERAN.getOrDefault(key, false)); j.put("levels", lv); j.put("gear", gear); j.put("coins", Ledger.Wallet.coins(b));
            j.put("fav", FAV.getOrDefault(key, -2)); j.put("goal", GOAL.getOrDefault(key, 0));
            j.put("home", HOME.getOrDefault(key, -1)); j.put("high", HIGH_TIER.getOrDefault(key, false));
            if (DIRECT_NAMES.contains(key)) j.put("direct", true); // P31 fix 1
            JSONArray xa = new JSONArray(); for (int i = 0; i < 24; i++) xa.add(Math.floor(b.getSkills().getExperience(i) * 10) / 10.0); j.put("xp", xa);
            String[] st = STORY.get(key); if (st != null) { j.put("catch", st[0]); j.put("grudge", st[1]); }
            Profile items = new Profile(); captureItems(b, items); putItems(j, items);
            arr.add(j);
        }
        // Remembered bots that have not been spawned yet this boot (spawns take ~25 min) must not drop out of the book.
        List<Profile> unspawned = new ArrayList<>();
        for (int i = spawned; i < SAVED.size(); i++) unspawned.add(SAVED.get(i));
        for (int i = spawnedHigh; i < SAVED_HIGH.size(); i++) unspawned.add(SAVED_HIGH.get(i));
        for (Profile p : unspawned) { if (p == null || p.name != null && !written.add(p.name.toLowerCase(Locale.ROOT))) continue;
            arr.add(savedJson(p));
        }
        // batch 22 S-10: profiles whose restore threw (RETRY), and the one being restored right now unless its bot is already in BOTS.
        // Each guarded: a profile that cannot be written (only a hand-made one, e.g. TestB19C6's levels=null) is skipped, never the whole save.
        List<Profile> held = new ArrayList<>(); for (Retry r : RETRY) held.add(r.p);
        { Profile rp = restoringProf; AIPlayer rb = restoringBot; if (rp != null && (rb == null || !BOTS.contains(rb))) held.add(rp); }
        for (Profile p : held) { if (p == null || p.name != null && written.contains(p.name.toLowerCase(Locale.ROOT))) continue;
            try { JSONObject j = savedJson(p); if (p.name != null) written.add(p.name.toLowerCase(Locale.ROOT)); arr.add(j); }
            catch (Throwable t) { if (Failures.gate("s10save|" + p.name, 3_600_000L)) System.out.println("[S-10] retry profile " + p.name + " could not be written, left out of this save: " + t); }
        }
        for (Profile p : OFFLINE) { // on a break: keep them in the book so they come back after a restart too
            if (p.name != null && !written.add(p.name.toLowerCase(Locale.ROOT))) continue; // audit B-14
            arr.add(savedJson(p));
        }
        for (Object o : BAD_PROFILES) { Object n = o instanceof JSONObject ? ((JSONObject) o).get("name") : null; if (n == null || written.add(String.valueOf(n).toLowerCase(Locale.ROOT))) arr.add(o); } // audit 10-23 O-1
        if (arr.size() < Math.min(SAVED.size(), 5)) return null; // never wipe a good file with an empty one (e.g. right after a failed start)
        return arr;
    }

    /** A profile not on line (unspawned, on a break, or S-10 retry) as saved; the format the unspawned and OFFLINE loops wrote inline before batch 22. */
    @SuppressWarnings("unchecked")
    static JSONObject savedJson(Profile p) {
        JSONObject j = new JSONObject(); JSONArray lv = new JSONArray(); JSONArray gear = new JSONArray();
        for (int k = 0; k < 24; k++) lv.add(p.levels[k]);
        for (int g : p.gear) gear.add(g);
        j.put("name", p.name); j.put("veteran", p.veteran); j.put("levels", lv); j.put("gear", gear); j.put("coins", p.coins); j.put("fav", p.fav); j.put("goal", p.goal); j.put("home", p.home); j.put("high", p.high);
        if (p.catchphrase != null) j.put("catch", p.catchphrase); if (p.grudge != null) j.put("grudge", p.grudge);
        if (p.direct) j.put("direct", true); // P31 fix 1
        if (p.xp != null) { JSONArray xa = new JSONArray(); for (double x : p.xp) xa.add(x); j.put("xp", xa); }
        putItems(j, p);
        return j;
    }

    /**
     * Creates a bot with a chosen name. AIPlayer(name, location) reads data/botdata/<file> and uses a random line
     * of it as the bot's name (lines with spaces, underscores or a leading # are skipped, for ever, so the name is
     * sanitised first). The file is removed again right after. Returns null if the name is taken or anything fails.
     */
    static AIPlayer namedBot(String wanted, Location at) {
        if (wanted == null || Repository.getPlayerByName(wanted) != null) return null;
        // The server builds a bot name as <line from the data file> + its running counter (AIPlayer.currentUID).
        // Split the saved name into those two parts and set the counter so the same name comes out again.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)(\\d{1,3})$").matcher(wanted); // the counter is a few digits; a long digit tail (an inflated counter, see below) means a fresh name instead
        if (wanted.matches(".*\\d{5,}$")) return null;
        if (!m.matches()) return null;
        String base = m.group(1).replaceAll("[ _#:]", "");
        int number;
        try { number = Integer.parseInt(m.group(2)); } catch (Exception e) { return null; }
        if (base.isEmpty() || base.length() + m.group(2).length() > 20) return null;
        String key = "smart-" + base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        File f = new File(ServerConstants.BOT_DATA_PATH, key);
        Integer previous = null;
        java.lang.reflect.Field uid = null;
        try {
            uid = AIPlayer.class.getDeclaredField("currentUID");
            uid.setAccessible(true);
            previous = uid.getInt(null);
            uid.setInt(null, number - 1); // the constructor increments before it appends
            try (FileWriter w = new FileWriter(f)) { w.write(base + "\n"); }
            AIPlayer bot = new AIPlayer(key, at);
            uid.setInt(null, Math.min(999, Math.max(previous, uid.getInt(null)))); // never hand a later fresh bot a number already in use; never let a restore inflate the counter (a saved Kabal199592 once pushed it to 199591)
            if (!bot.getUsername().equalsIgnoreCase(wanted)) System.out.println("[SmartAdv] wanted the name " + wanted + " but got " + bot.getUsername());
            return bot;
        } catch (Throwable t) {
            System.out.println("[SmartAdv] could not recreate " + wanted + ": " + t);
            try { if (uid != null && previous != null) uid.setInt(null, Math.max(previous, uid.getInt(null))); } catch (Throwable ignored) { }
            return null;
        } finally {
            f.delete();
        }
    }

    // ---- P31 fix 1 (human-look-1): names without the server's counter tail. AIPlayer names a bot <stock name> + <its uid + 1>,
    // so every bot read like "Kabal199" (102 of 102 on live ended in digits; only 16% of the stock names carry digits). A NEW bot
    // (spawn/spawnHigh only) drops the tail right after it is made, before anything is filed under its name. It keeps the bare
    // stock name; 1 in 12 of those not already ending in a digit get 1-2 random digits, so about 17% end in digits overall (the
    // stock file: 16%; 1 in 6 on top of the ~10% that already end in one gave ~25%). Such a profile is saved with "direct":true and restored by directBot (namedBot
    // can only rebuild <base><counter>). Saved bots keep their names. Off with -Dbots.humanNames=false (new bots as before).
    static final boolean HUMAN_NAMES = !"false".equalsIgnoreCase(System.getProperty("bots.humanNames", "true"));
    static final java.util.Set<String> DIRECT_NAMES = java.util.concurrent.ConcurrentHashMap.newKeySet(); // lower case: names set directly (no counter tail)
    static volatile int humanNamed, humanNamedDigits, humanNameKept; // for the trials (TestHumanLook)

    /** The bare name for a stock base (null: keep the old one). 1 in 12 get 1-2 digits; never a name {@code taken} says is in use. */
    static String pickHumanName(String base, java.util.function.Predicate<String> taken, Random rng) {
        if (base == null) return null;
        base = base.replaceAll("[_#:]", " ").replaceAll(" +", " ").trim();
        if (base.length() < 3 || base.length() > 12 || !Character.isLetterOrDigit(base.charAt(0)) || !base.matches(".*[A-Za-z].*") || !base.matches("[A-Za-z0-9 ]+")) return null; // player-style: letters, digits, spaces, at least one letter
        boolean endsInDigit = Character.isDigit(base.charAt(base.length() - 1));
        for (int t = 0; t < 6; t++) {
            boolean digits = t > 0 || !endsInDigit && rng.nextInt(12) == 0; // taken: try again with digits, like a player whose name is gone
            String n = base;
            if (digits) { String d = String.valueOf(1 + rng.nextInt(rng.nextBoolean() ? 9 : 99)); if (base.length() + d.length() > 12) continue; n = base + d; }
            n = core.tools.StringUtils.formatDisplayName(n);
            if (!taken.test(n.toLowerCase(Locale.ROOT))) return n;
        }
        return null;
    }

    /** True when a name is in use anywhere a bot or player name is filed (lower case in). */
    static boolean nameTaken(String lower) {
        if (KNOWN_NAMES.contains(lower) || DIRECT_NAMES.contains(lower) || BRAINS.containsKey(lower)) return true;
        try { if (Ledger.Wallet.findOnline(lower) != null) return true; } catch (Throwable ignored) { }
        try { if (Artisans.TRADE_OF.containsKey(lower) || ServiceBots.knows(lower)) return true; } catch (Throwable ignored) { }
        for (Profile p : OFFLINE) if (p.name != null && p.name.equalsIgnoreCase(lower)) return true;
        for (Profile p : SAVED) if (p.name != null && p.name.equalsIgnoreCase(lower)) return true;
        for (Profile p : SAVED_HIGH) if (p.name != null && p.name.equalsIgnoreCase(lower)) return true;
        return Ledger.isPlayerSave(lower);
    }

    static void setUsername(AIPlayer bot, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = AIPlayer.class.getDeclaredField("username"); // getUsername() returns this field; the client shows it
        f.setAccessible(true);
        f.set(bot, name);
    }

    /** A fresh bot (game thread, straight after the assembler made it): the counter tail goes, see above. */
    static void humanName(AIPlayer bot) {
        if (!HUMAN_NAMES || bot == null) return;
        String was = bot.getUsername();
        try {
            String tail = String.valueOf(bot.getUid() + 1); // the constructor appends currentUID + 1, then stores currentUID as uid
            String base = was != null && was.endsWith(tail) ? was.substring(0, was.length() - tail.length()) : null;
            String n = pickHumanName(base, SmartAdventurers::nameTaken, RNG);
            if (n == null) { humanNameKept++; return; }
            setUsername(bot, n);
            String k = n.toLowerCase(Locale.ROOT);
            DIRECT_NAMES.add(k); KNOWN_NAMES.add(k);
            humanNamed++; if (Character.isDigit(n.charAt(n.length() - 1))) humanNamedDigits++;
            if (Boolean.getBoolean("afk.debug") || Boolean.getBoolean("test.humanlook")) System.out.println("[HumanLook] name " + was + " -> " + n);
        } catch (Throwable t) { humanNameKept++; System.out.println("[HumanLook] could not rename " + was + ": " + t); }
    }

    /** Restore of a "direct" profile: a bot under exactly that name (null if it is online already or anything fails). */
    static AIPlayer directBot(String wanted, Location at) {
        if (wanted == null || wanted.trim().isEmpty() || Ledger.Wallet.findOnline(wanted) != null) return null;
        if (NameGuard.blocksRestore(NameGuard.ON, Ledger.isPlayerSave(wanted.toLowerCase(Locale.ROOT)))) { NameGuard.blockedRestores++; System.out.println("[HumanLook] name guard: " + wanted + " is a player's account now; restored under a new name"); return null; } // train-3 audit finding 2
        File f = new File(ServerConstants.BOT_DATA_PATH, "smart-direct");
        AIPlayer bot = null;
        try {
            try (FileWriter w = new FileWriter(f)) { w.write("Restoring\n"); } // a placeholder name; the counter moves on as for any new bot
            bot = new AIPlayer("smart-direct", at);
            setUsername(bot, wanted);
            DIRECT_NAMES.add(wanted.toLowerCase(Locale.ROOT));
            return bot;
        } catch (Throwable t) {
            System.out.println("[HumanLook] could not recreate " + wanted + ": " + t);
            if (bot != null) try { Bob.leave(bot, "clear"); bot.clear(); } catch (Throwable ignored) { } // P86 hook 5 (F14)
            return null;
        } finally { f.delete(); }
    }

    /**
     * The assembler hands out the best gear a bot's levels allow, for free. Make it real: cheap pieces
     * (under 200 gp) count as bought in a shop; anything better must come out of bot stock, and if the
     * market has none the bot goes without it until someone supplies it.
     */
    static void payForGear(AIPlayer bot) {
        StringBuilder bought = new StringBuilder(), missing = new StringBuilder();
        try {
            for (Item it : bot.getEquipment().toArray()) {
                if (it == null) continue;
                int id = it.getId();
                if (MarketBrain.value(id) < 200) { if (Ledger.Wallet.pay(bot, Math.max(1, MarketBrain.value(id)))) continue; bot.getEquipment().remove(it); continue; } // shop stuff: paid for now (audit #6), or left in the shop
                if (Ledger.consume(bot, id, 1, "gear") == 1) { if (bought.length() > 0) bought.append(", "); bought.append(it.getName()); continue; }
                bot.getEquipment().remove(it);
                if (missing.length() > 0) missing.append(", "); missing.append(it.getName());
            }
            // Nobody walks around naked: fill empty weapon/body/legs/helm slots with iron from a shop, paid for.
            // A newcomer starts in bronze like a real new account (keeps most of its 600 gp for tools and food);
            // a bot with savings buys iron. Upgrades come later through its goal list.
            boolean poor = Ledger.Wallet.coins(bot) < 5_000;
            int[][] starter = poor ? new int[][]{{3, 1321}, {4, 1117}, {7, 1075}, {0, 1155}}  // bronze scimitar, platebody, platelegs, full helm
                                   : new int[][]{{3, 1323}, {4, 1115}, {7, 1067}, {0, 1153}}; // iron
            for (int[] st : starter) {
                if (bot.getEquipment().get(st[0]) != null) continue;
                int price = Math.max(1, MarketBrain.value(st[1]));
                if (!Ledger.Wallet.pay(bot, price)) break;
                wear(bot, st[1]);
            }
            try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
        } catch (Throwable t) { System.out.println("[SmartAdv] gear purchase failed for " + bot.getUsername() + ": " + t); return; }
        if (bought.length() > 0 || missing.length() > 0) System.out.println("[SmartAdv] " + bot.getUsername() + " bought from stock: " + (bought.length() > 0 ? bought : "nothing") + (missing.length() > 0 ? "; can't buy (none for sale or can't afford), going without: " + missing : "") + " (wallet " + Ledger.Wallet.coins(bot) + " gp)");
    }

    /**
     * batch 15 item3: a bot died and left a gravestone. One crewmate (its bot crew, or the same player's Companions crew)
     * within 30 tiles, not fighting, not hurt and not busy, walks over and uses the stone's own "bless" option (Prayer 70,
     * GraveController.onGraveBlessed: up to an hour) or, below 70 Prayer, "repair" (Prayer 2: up to 5 minutes), through
     * the engine's interaction (InteractionListeners.run walks it there first, as a player's click does). Never into the
     * Wilderness, a cell or hunt spot stronger than the blesser, or where the killer is still standing.
     */
    static Brain crewBless(Brain dead, core.game.node.entity.combat.graves.Grave g) {
        if (dead == null || dead.bot == null || g == null || !g.isActive()) return null;
        Location at = g.getLocation();
        java.util.Set<Brain> mates = new java.util.LinkedHashSet<>();
        if (dead.crew != null) mates.addAll(dead.crew.members);
        if (dead.companion != null) { Companions.Crew c = Companions.CREWS.get(Companions.key(dead.companion)); if (c != null) mates.addAll(c.bots); }
        mates.remove(dead);
        if (mates.isEmpty()) return null;
        long now = System.currentTimeMillis();
        Brain best = null; String bestOpt = null; double bestD = 99; String why = null;
        for (Brain m : mates) {
            String opt = blessOption(m, g, at, now);
            if (opt == null) continue;
            String danger = graveDanger(at, dead.diedKiller, m.bot);
            if (danger != null) { why = danger; continue; }
            double d = m.bot.getLocation().getDistance(at) + (AfkTasks.RUNNING.containsKey(m.bot.getUsername().toLowerCase(Locale.ROOT)) ? 10 : 0); // one between trips first
            if (d < bestD) { bestD = d; best = m; bestOpt = opt; }
        }
        if (best == null) { if (why != null) System.out.println("[SmartAdv] nobody blesses " + dead.bot.getUsername() + "'s grave at " + at + ": " + why); return null; }
        final Brain m = best; final String opt = bestOpt; final Player b = m.bot; final String owner = dead.bot.getUsername();
        AfkTasks.Runner r = AfkTasks.RUNNING.get(b.getUsername().toLowerCase(Locale.ROOT));
        if (r != null) r.halt(); // the trip ends here, as a player would drop what they were doing
        if (b.getDetails().getUid() == 0 && b instanceof AIPlayer) b.getDetails().accountInfo.setUid(0x40000000 + ((AIPlayer) b).getUid()); // every bot's uid is 0: the engine refuses "your own" stone
        m.blessUntil = now + 75_000;
        BotChat.activity(b, () -> "blessing " + owner + "'s grave");
        System.out.println("[SmartAdv] " + b.getUsername() + " goes to " + opt + " " + owner + "'s grave at " + at + " (" + (int) b.getLocation().getDistance(at) + " tiles, prayer " + b.getSkills().getStaticLevel(Skills.PRAYER) + ")");
        final String flag = opt.equals("bless") ? "blessed" : "repaired";
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(2, b) {
            int n, tries;
            void end(String how) { m.blessUntil = 0; BotChat.clearActivity(b); m.nextThink = System.currentTimeMillis() + 3_000; System.out.println("[SmartAdv] " + b.getUsername() + " grave " + opt + " for " + owner + ": " + how); }
            @Override public boolean pulse() {
                try {
                    if (!b.isActive() || !g.isActive()) { end("the grave is gone"); return true; }
                    if (g.getAttribute(flag, false) && n > 0) {
                        BotChat.say(b, BotChat.pick("There you go, " + owner + ". Get your stuff.", "Grave's " + flag + ". Run, " + owner + "!", "Done. Don't make a habit of it.", flag.equals("blessed") ? "Blessed. You owe me one." : "Patched it up. Five more minutes."));
                        end("done at " + b.getLocation() + ", " + g.getTicksRemaining() + " ticks left"); return true;
                    }
                    n++;
                    if (n > 36) { end("timed out " + (int) b.getLocation().getDistance(at) + " tiles short"); return true; }
                    boolean fighting = false; try { fighting = b.inCombat() || b.getProperties().getCombatPulse().isAttacking(); } catch (Throwable ignored) { }
                    if (fighting) { end("attacked on the way, gave up"); return true; } // in danger now: the stone isn't worth it
                    if (!b.getPulseManager().hasPulseRunning() && !b.getWalkingQueue().isMoving()) {
                        if (Boolean.getBoolean("smartbots.blessDebug")) System.out.println("[SmartAdv] bless try " + tries + " by " + b.getUsername() + " at " + b.getLocation() + " for the stone at " + at);
                        if (tries++ >= 3) { end("couldn't reach it (" + (int) b.getLocation().getDistance(at) + " tiles short)"); return true; }
                        if (tries > 1 && b.getLocation().getZ() == at.getZ() && b.getLocation().getDistance(at) < 2) { // standing next to it, but the walk-and-click never fired (the stone's destination tile is unreachable from some sides): the click itself, as on arrival
                            try { b.faceLocation(at); } catch (Throwable ignored) { }
                            kotlin.jvm.functions.Function2<core.game.node.entity.player.Player, core.game.node.Node, Boolean> h = core.game.interaction.InteractionListeners.get(g.getId(), core.game.interaction.IntType.NPC.ordinal(), opt);
                            if (h != null) { h.invoke(b, g); return false; }
                        }
                        core.game.interaction.InteractionListeners.run(g.getId(), core.game.interaction.IntType.NPC, opt, b, g); // walks there, then the engine's own option
                    }
                    return false;
                } catch (Throwable t) { end("error " + t); return true; }
            }
        });
        return m;
    }

    /** batch 15 item3: "bless" (Prayer 70, points over 10), "repair" (Prayer 2), or null when this crewmate can't or shouldn't go. */
    static String blessOption(Brain m, core.game.node.entity.combat.graves.Grave g, Location at, long now) {
        Player b = m == null ? null : m.bot;
        if (b == null || !b.isActive() || b.getAttribute("state:death", false)) return null;
        if (m.errand() || m.blessUntil > now || m.errandFor != null) return null;
        if (QuestRunner.active(b) || Deals.involved(b) || PlayerTrades.SESSIONS.containsKey(b)) return null;
        try { if (b.inCombat() || b.getProperties().getCombatPulse().isAttacking()) return null; } catch (Throwable ignored) { } // in danger itself
        if (b.getSkills().getLifepoints() < b.getSkills().getMaximumLifepoints() * 3 / 5) return null;
        if (b.getLocation().getZ() != at.getZ() || b.getLocation().getDistance(at) > 30) return null;
        int pray = b.getSkills().getStaticLevel(Skills.PRAYER); double pts = b.getSkills().getPrayerPoints();
        if (pray >= 70 && pts > 10 && !g.getAttribute("blessed", false)) return "bless";
        if (pray >= 2 && pts >= 1 && !g.getAttribute("repaired", false)) return "repair";
        return null;
    }

    /** batch 15 item3: why the grave's spot is no place for this bot to walk into, or null. */
    static String graveDanger(Location at, String killer, Player b) {
        if (core.game.world.map.zone.impl.WildernessZone.isInZone(at) || Heat.wildLevel(at.getX(), at.getY()) > 0) return "Wilderness";
        int cb = 3; try { cb = b.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
        Integer lvl = Heat.DANGER.get(RouteMap.cell(at.getX(), at.getY()));
        if (lvl != null && lvl > cb) return "monsters up to level " + lvl + " there (combat " + cb + ")";
        for (HuntMap.Hunt h : HuntMap.HUNTS) if (h.spot != null && h.spot.getZ() == at.getZ() && h.spot.getDistance(at) <= 15 && h.npcLevel * 3 >= cb * 2) return "the " + h.name + " hunt spot";
        String kn = killer == null ? null : killer.replaceAll(" \\(level \\d+\\)$", "");
        if ("a player".equals(kn)) return "a player killed it";
        if (kn != null && !kn.startsWith("no monster")) {
            for (core.game.node.entity.npc.NPC n : RegionManager.getLocalNpcs(at, 10)) if (n != null && n.isActive() && !(n instanceof core.game.node.entity.combat.graves.Grave) && n.getSkills().getLifepoints() > 0 && kn.equalsIgnoreCase(n.getName())) return "the " + kn + " that killed it is still there";
        }
        return null;
    }

    /**
     * Puts an item on a bot in its proper slot. EquipmentContainer.add(item, true) equips FROM the inventory, so it fails
     * silently when the item isn't in the pack, which is why restored and bought gear was never worn (2026-10-20: 58 of
     * 102 bots naked, the high tier wearing only a shield). Takes the item out of the pack if it is there; anything
     * already in that slot goes to the pack (or the bank when the pack is full). Returns false if it can't be worn; the item
     * is then in the pack or the bank (the pack's own copy if it had one, otherwise a new one), so callers never add it again
     * (audit 10-23 S-1: three callers banked a second copy).
     */
    static boolean wear(Player bot, int id) {
        int slot = -1; boolean hadOne = false, taken = false;
        try {
            Item it = new Item(id);
            Object cfg = it.getDefinition().getConfiguration("equipment_slot", -1);
            slot = cfg instanceof Number ? ((Number) cfg).intValue() : -1;
            hadOne = bot.getInventory().getAmount(id) > 0;
            if (slot < 0) { if (!hadOne && !bot.getInventory().add(it)) bot.getBank().add(it); return false; } // audit 10-23 S-1
            if (hadOne) taken = bot.getInventory().remove(new Item(id)); // audit 10-23 S-1: only a copy really taken out is put back below
            Item old = bot.getEquipment().get(slot);
            if (old != null) {
                bot.getEquipment().remove(old);
                if (bot.isArtificial() && old.getId() != id && BRAINS.containsKey(bot.getUsername().toLowerCase(Locale.ROOT)) && MarketBrain.value(old.getId()) >= 100 && MarketBrain.tradeable(old.getId())) {
                    Ledger.supply(bot, old.getId(), Math.max(1, old.getAmount())); // on the Exchange for a poorer bot (it is paid when it sells)
                    if (RNG.nextInt(3) == 0) BotChat.say(bot, "Upgraded. Old " + MarketBrain.name(old.getId()).toLowerCase(Locale.ROOT) + "'s on the Exchange if anyone wants it.");
                } else if (!bot.getInventory().add(old)) bot.getBank().add(old);
            }
            if (bot.getEquipment().add(it, -1, true, false)) { try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } return true; } // fire = bonuses, attack speed, appearance
            if (hadOne && !taken) return false; // still in the pack
            hadOne = true; taken = false; // from here the copy below is the one placed (the catch must not add another)
            if (!bot.getInventory().add(it)) bot.getBank().add(it);
            return false;
        } catch (Throwable t) { // audit 10-23 S-1: worn after all, or one copy back in the pack or bank (it used to be lost here)
            try {
                if (slot >= 0) { Item w = bot.getEquipment().get(slot); if (w != null && w.getId() == id) return true; }
                if (taken || !hadOne) { Item it = new Item(id); if (!bot.getInventory().add(it)) bot.getBank().add(it); }
            } catch (Throwable ignored) { }
            return false;
        }
    }

    /** Empty weapon/body/legs/helm slots filled from "the shop", paid; bronze when poor, iron with savings. */
    static void fillEmptySlots(AIPlayer bot) {
        boolean poor = Ledger.Wallet.coins(bot) < 5_000;
        int[][] starter = poor ? new int[][]{{3, 1321}, {4, 1117}, {7, 1075}, {0, 1155}} : new int[][]{{3, 1323}, {4, 1115}, {7, 1067}, {0, 1153}};
        for (int[] st : starter) {
            if (bot.getEquipment().get(st[0]) != null) continue;
            int price = Math.max(1, MarketBrain.value(st[1]));
            if (!Ledger.Wallet.pay(bot, price)) break;
            wear(bot, st[1]);
        }
    }

    static void spawn() {
        Location start = LUMBRIDGE.transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0);
        if (spawned < SAVED.size()) { Profile p = SAVED.get(spawned); spawned++; restoreSafe(p, start, "spawn", 0); return; } // batch 22 S-10: a profile that throws goes to RETRY instead of blocking the queue
        spawned++;
        // New blood: three in four start green and must earn their levels; one in four is a veteran who can supply the high-end stuff.
        boolean veteran = RNG.nextInt(4) == 0;
        CombatBotAssembler.Tier tier = veteran ? CombatBotAssembler.Tier.MED : CombatBotAssembler.Tier.LOW;
        AIPlayer bot = new CombatBotAssembler().MeleeAdventurer(tier, start); ph("create");
        humanName(bot); ph("name"); // P31 fix 1: before anything is filed under the name
        boolean miner = veteran && RNG.nextInt(3) == 0;
        for (int skill : GATHER_SKILLS) {
            int lvl = veteran ? 55 + RNG.nextInt(36) : 10 + RNG.nextInt(26);
            if (miner && skill == Skills.MINING) lvl = 85 + RNG.nextInt(10);
            bot.getSkills().setStaticLevel(skill, lvl);
            bot.getSkills().setLevel(skill, lvl);
        }
        Ledger.Wallet.give(bot, veteran ? 20_000 + RNG.nextInt(40_000) : 600); // a nest egg, or enough for an iron set
        payForGear(bot);
        // A trade to follow (B15): the miner mines; everyone else leans towards whatever they are best at, a few just fight.
        int fav = miner ? Skills.MINING : RNG.nextInt(5) == 0 ? -1 : bestGatherSkill(bot);
        enlist(bot, veteran, false, fav, 0, RNG.nextInt(BotLife.Town.values().length), veteran ? "set out (veteran)" : "set out (fresh)");
    }

    /** E27: a seasoned fighter. Combat 90+, prayer for the big hunts, an anti-dragon shield and Dragon Slayer behind them. */
    static void spawnHigh() {
        Location start = LUMBRIDGE.transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0);
        if (spawnedHigh < SAVED_HIGH.size()) { Profile p = SAVED_HIGH.get(spawnedHigh); spawnedHigh++; restoreSafe(p, start, "spawnHigh", 0); return; } // the ones we know come back first (batch 22 S-10: a failure goes to RETRY)
        spawnedHigh++;
        AIPlayer bot = new CombatBotAssembler().MeleeAdventurer(CombatBotAssembler.Tier.HIGH, start);
        humanName(bot); // P31 fix 1
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { int l = 85 + RNG.nextInt(11); bot.getSkills().setStaticLevel(sk, l); bot.getSkills().setLevel(sk, l); }
        int pray = 52 + RNG.nextInt(19); bot.getSkills().setStaticLevel(Skills.PRAYER, pray); bot.getSkills().setLevel(Skills.PRAYER, pray);
        for (int skill : GATHER_SKILLS) { int lvl = 60 + RNG.nextInt(31); bot.getSkills().setStaticLevel(skill, lvl); bot.getSkills().setLevel(skill, lvl); }
        bot.getSkills().updateCombatLevel();
        bot.getSkills().rechargePrayerPoints();
        Ledger.Wallet.give(bot, 120_000 + RNG.nextInt(130_000)); // years of adventuring behind them
        payForGear(bot);
        armForDragons(bot);
        if (Boolean.getBoolean("xp.debug")) System.out.println("[XPDBG] new high " + bot.getUsername() + " bad=[" + xpCheck(bot) + "]");
        enlist(bot, true, true, -1, 0, RNG.nextInt(BotLife.Town.values().length), "set out (high tier)");
    }

    /** Anti-dragon shield from the shop, Dragon Slayer done: the prerequisites before any dragon (S7). */
    static void armForDragons(AIPlayer bot) {
        try {
            if (!bot.getEquipment().containsItem(new Item(1540)) && !bot.getEquipment().containsItem(new Item(11283)) && !bot.getEquipment().containsItem(new Item(11284))) {
                if (Ledger.Wallet.pay(bot, Math.max(50, MarketBrain.value(1540)))) { wear(bot, 1540); bot.getEquipment().refresh(); }
            }
        } catch (Throwable t) { System.out.println("[SmartAdv] shield for " + bot.getUsername() + ": " + t); }
        try {
            core.game.node.entity.player.link.quest.Quest q = bot.getQuestRepository().getQuest(content.data.Quests.DRAGON_SLAYER);
            if (q != null && !q.isCompleted(bot)) bot.getQuestRepository().setStage(q, 100);
        } catch (Throwable t) { System.out.println("[SmartAdv] quest flag for " + bot.getUsername() + ": " + t); }
    }

    /** P40 B5: per-phase timing of one spawn/restore pulse (-Dtest.bootspread=true; logs only spawns of 40 ms or more). */
    static final boolean BOOT_PROBE = Boolean.getBoolean("test.bootspread");
    static long phT0, phLast; static StringBuilder phSb;
    static void phStart() { if (!BOOT_PROBE) return; phT0 = phLast = System.nanoTime(); phSb = new StringBuilder(); }
    static void ph(String n) { if (!BOOT_PROBE || phSb == null) return; long t = System.nanoTime(); phSb.append(' ').append(n).append('=').append((t - phLast) / 1_000_000); phLast = t; }
    static void phEnd(String kind) { if (!BOOT_PROBE || phSb == null) return; long ms = (System.nanoTime() - phT0) / 1_000_000; if (ms >= 40) System.out.println("[BootSpread] " + kind + " " + ms + " ms:" + phSb); phSb = null; }

    /** Someone we know: same name, the levels they had earned, their gear and coins. */
    static void restore(Profile prof, Location start) {
        AIPlayer named = prof.direct ? directBot(prof.name, start) : namedBot(prof.name, start); // P31 fix 1: a name without the counter tail is set directly
        AIPlayer bot = named != null ? named : new CombatBotAssembler().MeleeAdventurer(prof.high ? CombatBotAssembler.Tier.HIGH : prof.veteran ? CombatBotAssembler.Tier.MED : CombatBotAssembler.Tier.LOW, start);
        ph("create");
        if (named == null && prof.direct) humanName(bot); // P31 fix 1: its stand-in name has no counter tail either (the renames below follow it)
        if (restoringProf == prof) restoringBot = bot; // batch 22 S-10: restoreSafe discards it if anything below throws before BOTS.add
        if (named == null) try { if (Failures.gate("namelost|" + prof.name, Long.MAX_VALUE / 4)) Failures.event("lifecycle", bot, "login", "name-lost", prof.name + " -> " + bot.getUsername(), null, Failures.ctx("was", prof.name, "now", bot.getUsername())); } catch (Throwable ignored) { } // batch 19c §8.1 (one per profile per boot)
        for (int i = 0; i < 24; i++) if (prof.levels[i] > 0) { bot.getSkills().setStaticLevel(i, prof.levels[i]); bot.getSkills().setLevel(i, prof.levels[i]); }
        restoreXp(bot, prof.xp); ph("levels");
        try { QuestBook.rename(prof.name, bot.getUsername()); QuestBook.restore(bot); } catch (Throwable t) { System.out.println("[Quests] restore for " + bot.getUsername() + ": " + t); } // saved attributes, then quest stages; before seedQuests
        if (Boolean.getBoolean("xp.debug")) System.out.println("[XPDBG] restored " + bot.getUsername() + " bad=[" + xpCheck(bot) + "]");
        bot.getSkills().updateCombatLevel();
        ph("quests");
        if (prof.coins > 0) Ledger.Wallet.restore(bot, prof.coins); // its own coins back, not new money
        long restoredTo = Ledger.Wallet.coins(bot); // batch 19c §10 restore-short (read only)
        long collected = Ledger.Wallet.collect(bot); // anything that sold while it was away
        if (restoringProf == prof) restoringCollected = collected; // batch 22 S-10
        if (S10_FAULT_NAME != null) s10Fault("restore", prof.name); // batch 22 S-10 trial fault injection (TestB22S10 only)
        ph("coins"); restoreItems(bot, prof); ph("items"); // pack, bank and the exact arrow count first, so anything wear() moves to the pack isn't cleared (audit #12)
        try { int held = 0; if (prof.items) { for (Item it : bot.getInventory().toArray()) if (it != null) held++; for (Item it : bot.getBank().toArray()) if (it != null && it.getId() != 995) held++; }
            int want = prof.pack.size() + prof.bank.size();
            if (restoredTo < prof.coins || prof.items && want > 0 && held == 0) Failures.event("save", bot, "load", "restore-short", restoredTo < prof.coins ? "coins short" : "items empty", null, Failures.ctx("profile", prof.name, "coins", restoredTo, "saved", prof.coins, "items", held, "savedItems", want, "file", "smartbots.json")); } catch (Throwable ignored) { } // batch 19c §10
        if (prof.gear.isEmpty() && !prof.items && Ledger.Wallet.coins(bot) < 600) // audit 10-23 S-6: only a legacy profile (no saved items); a player-rules death can leave a modern one with no gear. Remembered from before gear was owned: the same start a newcomer gets, so nobody comes back naked
            Ledger.Wallet.give(bot, prof.veteran ? 20_000 + RNG.nextInt(40_000) : 600);
        if (prof.gear.isEmpty()) { new CombatBotAssembler().gearMeleeBot(bot); payForGear(bot); } // first time: buy what the levels allow
        else { for (int id : prof.gear) { if (id >= 882 && id <= 892) continue; wear(bot, id); } try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } }  // theirs already, no re-buying
        ph("gear");
        if (!bot.getUsername().equalsIgnoreCase(prof.name)) Ledger.rename(prof.name, bot.getUsername()); // its lots and money owed follow it
        String how = bot.getUsername().equalsIgnoreCase(prof.name) ? "is back" : "is back as " + bot.getUsername() + " (was " + prof.name + ")";
        if (collected > 0) how += ", collected " + String.format("%,d", collected) + " gp from sales while away";
        int fav = prof.fav != -2 ? prof.fav : bestGatherSkill(bot);
        if (prof.high) { bot.getSkills().rechargePrayerPoints(); armForDragons(bot); }
        if (prof.catchphrase != null && prof.grudge != null) STORY.put(bot.getUsername().toLowerCase(Locale.ROOT), new String[]{prof.catchphrase, prof.grudge});
        ph("pre");
        enlist(bot, prof.veteran, prof.high, fav, prof.goal, prof.home >= 0 ? prof.home : RNG.nextInt(BotLife.Town.values().length), how);
        try { Failures.tripCoins(bot, Ledger.Wallet.coins(bot), true, null, "restore", -1); } catch (Throwable ignored) { } // batch 19c §4.1: a window from the restore to the first trip
    }

    /**
     * Skills.setStaticLevel resets a skill's XP to the bottom of the level, so every restart used to throw away the
     * progress towards the next level (and bots that restart often never level). Put the saved XP back exactly.
     */
    /** Debug: skills whose XP sits below the bottom of their level (should never happen). */
    static String xpCheck(Player b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) { int lv = b.getSkills().getStaticLevel(i); double x = b.getSkills().getExperience(i); if (lv > 1 && x < b.getSkills().getExperienceByLevel(lv) - 1) sb.append(i).append(":").append(lv).append("/").append((int) x).append(" "); }
        return sb.toString();
    }

    static void restoreXp(AIPlayer bot, double[] xp) {
        if (xp == null) return;
        try {
            java.lang.reflect.Field f = core.game.node.entity.skill.Skills.class.getDeclaredField("experience");
            f.setAccessible(true);
            double[] cur = (double[]) f.get(bot.getSkills());
            for (int i = 0; i < 24 && i < xp.length; i++) if (xp[i] > cur[i]) cur[i] = xp[i];
        } catch (Throwable t) { System.out.println("[SmartAdv] could not restore XP for " + bot.getUsername() + ": " + t); }
    }

    /** Hands the bot a brain and puts it on the books. */
    /**
     * The server caps every skill at 175 XP for anyone who hasn't finished Tutorial Island (Skills.addExperience checks
     * the "tutorial:complete" attribute). Bots never did, so every skill they trained was knocked back to 175 XP and no
     * bot could level by training. Mark them done, and lift any skill already knocked below its level back to the base.
     */
    static void finishTutorial(Player bot) {
        try { bot.setAttribute("/save:tutorial:complete", true); bot.setAttribute("tutorial:complete", true); } catch (Throwable ignored) { }
        try {
            java.lang.reflect.Field f = core.game.node.entity.skill.Skills.class.getDeclaredField("experience");
            f.setAccessible(true);
            double[] xp = (double[]) f.get(bot.getSkills());
            for (int i = 0; i < 24; i++) { int lv = bot.getSkills().getStaticLevel(i); double base = bot.getSkills().getExperienceByLevel(lv); if (lv > 1 && xp[i] < base) xp[i] = base; }
        } catch (Throwable ignored) { }
    }

    /**
     * the owner's answer to the review (2026-10-20): every bot had Agility 1, so none could use the shortcuts the surveyors
     * map. Veterans and the high tier get a level a player of their combat would have had; fresh bots stay at 1 and train
     * on the Gnome course. Only applied while Agility is still 1, so restored progress is never overwritten.
     */
    /**
     * W3 fix 3 (-Dbots.summoning.wwSeed, needs -Dbots.summoning): the whole Summoning lane waits on Wolf Whistle (s59: started=0,
     * noWolfWhistle 451). A fixed share (34%, by name) of veterans and high-tier bots get the quest marked done, its 276 Summoning
     * XP and, only with -Dbots.summoning.wwSeed.charms, their first gold charms (25, high tier 40: untradeable). Once per bot: a bot that already has the quest gets nothing.
     */
    static void seedWolfWhistle(Player bot, boolean veteran, boolean high) {
        if (!W3Skills.WW_SEED || !Summon.ON || bot == null) return;
        try {
            if (!W3Skills.wwPick(bot.getUsername(), veteran, high, W3Skills.WW_SHARE)) { W3Skills.wwSkipped++; return; }
            core.game.node.entity.player.link.quest.Quest q = bot.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE);
            if (q == null) return;
            if (q.isCompleted(bot)) { W3Skills.wwAlready++; return; }
            bot.getQuestRepository().setStage(q, 100);
            if (bot.getSkills().getStaticLevel(Skills.SUMMONING) <= 1) bot.getSkills().addExperience(Skills.SUMMONING, W3Skills.WW_XP, true);
            int n = W3Skills.wwCharms(high);
            if (n > 0) bot.getBank().add(new Item(Summon.GOLD, n)); // review 11-19: 0 unless -Dbots.summoning.wwSeed.charms (no items from nothing by default)
            W3Skills.wwSeeded++;
            System.out.println("[Summon] (W3) wwSeed " + bot.getUsername() + ": Wolf Whistle done, +" + n + " gold charms (" + (high ? "high tier" : "veteran") + ", summoning " + bot.getSkills().getStaticLevel(Skills.SUMMONING) + ")");
        } catch (Throwable t) { System.out.println("[Summon] (W3) wwSeed " + bot.getUsername() + ": " + t); }
    }

    static void seedAgility(Player bot, boolean veteran, boolean high) {
        try {
            if (bot.getSkills().getStaticLevel(Skills.AGILITY) > 1) return;
            int cb = bot.getProperties().getCurrentCombatLevel(), lvl;
            if (high) lvl = 60 + RNG.nextInt(16);
            else if (cb >= 90) lvl = 48 + RNG.nextInt(15);
            else if (cb >= 60) lvl = 30 + RNG.nextInt(18);
            else if (veteran || cb >= 40) lvl = 15 + RNG.nextInt(16);
            else return;
            bot.getSkills().setStaticLevel(Skills.AGILITY, lvl); bot.getSkills().setLevel(Skills.AGILITY, lvl);
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- combat archetypes (2026-10-20)
    // About 35% of fighters are rangers or mages, picked from the name so a bot keeps its style across restarts without
    // a save change. They buy bows, arrows, leather and dragonhide, staves and runes, which gives the fletcher, crafter
    // and runecrafter artisans real bot customers (Exchange stock first, paying the artisan through the ledger).
    static final int MELEE = 0, RANGED = 1, MAGIC = 2;

    /** The Varrock crier calls the bounty board: idle adventurers in the square answer and go for the top item they can fetch. */
    /** Audit B-4: called from the MarketBrain timer; the rally itself now runs on the game thread. */
    static void crierRally(Player crier) {
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() {
            try { crierRallyNow(crier); } catch (Throwable t) { System.out.println("[SmartAdv] crier rally: " + t); }
            return true; } });
    }

    static void crierRallyNow(Player crier) {
        List<Object[]> board = Bounty.board();
        if (board.isEmpty()) return;
        int rallied = 0;
        for (Brain b : BRAINS.values()) {
            if (rallied >= 3 || b.bot == null || !b.bot.isActive() || b.runner != null || b.errand()) continue;
            if (!Companions.idle(b)) continue; // audit B-4: not a crewmate, an errand runner, a quester or a bot in a deal
            if (b.bot.getLocation().getZ() != crier.getLocation().getZ() || b.bot.getLocation().getDistance(crier.getLocation()) > 15) continue;
            for (Object[] e : board) {
                int id = (Integer) e[0];
                Job j = b.jobFor(id);
                if (j == null) continue;
                final Brain me = b; final Job job = j; final String nm = String.valueOf(e[1]).toLowerCase(Locale.ROOT);
                GameWorld.getPulser().submit(new core.game.system.task.Pulse(4 + 2 * rallied) { @Override public boolean pulse() {
                    if (!Companions.idle(me) || me.starUntil > 0) return true; // audit B-4: something else came up in the meantime
                    try { me.bot.face(crier); me.say(BotChat.pick("Bonus on " + nm + "? I'm on it.", nm + "? Say no more.", "Right, " + nm + " it is.")); me.idleUntil = 0; me.start(job); } catch (Throwable ignored) { }
                    return true; } });
                rallied++;
                break;
            }
        }
        if (rallied > 0) System.out.println("[SmartAdv] crier rally: " + rallied + " adventurers answered the board");
    }

    static int styleFor(String name, int fav) {
        if (fav != -1 || name == null) return MELEE;
        int h = Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode() * 31 + 7, 100);
        int force = Integer.getInteger("smartbots.forceStyle", -1); // trials only
        if (force >= 0 && h >= 35) return force;
        return h < 18 ? RANGED : h < 35 ? MAGIC : MELEE;
    }

    /** train-5 F1-F4 (mage and ranged fixes): on unless data/style5.off exists, checked at most once a minute (not latched). F5's [StyleTrip] line ignores it. */
    static volatile long style5At; static volatile boolean style5On = true;
    static boolean style5() {
        long now = System.currentTimeMillis();
        if (now - style5At >= 60_000) { style5At = now; try { style5On = !new File(ServerConstants.DATA_PATH, "style5.off").exists(); } catch (Throwable t) { style5On = true; } }
        return style5On;
    }
    static void style5Recheck() { style5At = 0; } // tests: read the off-file again now
    static Brain brainOf(Player p) { return p == null ? null : BRAINS.get(p.getUsername().toLowerCase(Locale.ROOT)); }
    static boolean meleeFallback(Player p) { Brain b = brainOf(p); return b != null && b.meleeFallback; }
    static boolean castTrip(Player p) { Brain b = brainOf(p); return b != null && b.castTrip; }
    static boolean isArrow(int id) { for (int a : Brain.ARROWS) if (a == id) return true; return false; }
    static int bowIndex(int id) { for (int i = 0; i < Brain.BOWS.length; i++) if (Brain.BOWS[i] == id) return i; return -1; }
    /** F3: can the worn bow fire this arrow (ARROW_MAX_BOW). */
    static boolean bowFires(int bowId, int arrowId) { int b = bowIndex(bowId); if (b < 0) return false; for (int i = 0; i < Brain.ARROWS.length; i++) if (Brain.ARROWS[i] == arrowId) return Brain.ARROW_MAX_BOW[i] <= b; return false; }
    /** F2 B: on a player-rules death a ranger or mage keeps its style weapon (a BOWS id or the staff 1387) in place of the cheapest kept item. */
    static boolean keepStyleWeapon(core.game.container.Container[] c) {
        Item sw = null;
        for (Item it : c[1].toArray()) if (it != null && (bowIndex(it.getId()) >= 0 || it.getId() == 1387) && (sw == null || MarketBrain.value(it.getId()) > MarketBrain.value(sw.getId()))) sw = it;
        if (sw == null) return false;
        for (Item it : c[0].toArray()) if (it != null && (bowIndex(it.getId()) >= 0 || it.getId() == 1387)) return false;
        Item cheap = null;
        for (Item it : c[0].toArray()) if (it != null && (cheap == null || (long) MarketBrain.value(it.getId()) * it.getAmount() < (long) MarketBrain.value(cheap.getId()) * cheap.getAmount())) cheap = it;
        Item one = new Item(sw.getId(), 1);
        if (!c[1].remove(one)) return false;
        if (cheap != null) { Item ch = new Item(cheap.getId(), cheap.getAmount(), cheap.getCharge()); if (c[0].remove(ch)) c[1].add(ch); }
        c[0].add(one);
        return true;
    }

    static int styleOf(Player p) {
        if (p == null) return MELEE;
        String k = p.getUsername().toLowerCase(Locale.ROOT);
        return styleFor(k, FAV.getOrDefault(k, -2));
    }

    /** A ranger or mage who has never trained it gets Ranged/Magic in line with its Attack (once; saved XP keeps it). */
    static void seedStyle(Player bot, int fav) {
        try {
            int st = styleFor(bot.getUsername(), fav);
            int skill = st == RANGED ? Skills.RANGE : st == MAGIC ? Skills.MAGIC : -1;
            if (skill < 0) return;
            int att = bot.getSkills().getStaticLevel(Skills.ATTACK);
            if (bot.getSkills().getStaticLevel(skill) >= Math.max(2, att - 5)) return;
            bot.getSkills().setStaticLevel(skill, att); bot.getSkills().setLevel(skill, att);
        } catch (Throwable ignored) { }
    }

    /** batch 17 item1: every bot's account uid was 0 (a second bot's grave collapsed the first's, the hit log lumped all bots as one): 0x40000000 + its AIPlayer number, unique per login and far above real account uids. */
    static void stampUid(Player b) {
        try { if (b instanceof AIPlayer && b.getDetails().getUid() == 0) b.getDetails().accountInfo.setUid(0x40000000 + ((AIPlayer) b).getUid()); } catch (Throwable ignored) { }
    }

    static void enlist(AIPlayer bot, boolean veteran, boolean high, int fav, int goal, int home, String how) {
        String key = bot.getUsername().toLowerCase(Locale.ROOT);
        stampUid(bot); // batch 17 item1: a uid of its own from the start (graves, the hit log and the "helped" credit are filed by it)
        finishTutorial(bot); ph("e.tut");
        seedAgility(bot, veteran, high);
        seedWolfWhistle(bot, veteran, high); // W3 fix 3 (-Dbots.summoning.wwSeed, default off)
        seedStyle(bot, fav); ph("e.seed");
        try { Gates.seedQuests(bot, high); } catch (Throwable t) { System.out.println("[SmartAdv] quests for " + bot.getUsername() + ": " + t); } // a quest log that fits its levels
        try { bot.getProperties().setRetaliating(true); } catch (Throwable ignored) { } // auto-retaliate on, like every 2009 player (bots died quietly to highwaymen on shop walks)
        ph("e.quests");
        Seed.apply(bot); // W0 A0: scenario seeding (farm worlds only, flags off = no-op)
        SeedFire.apply(bot); // W8 lane I: test-only switch-fire seeding (-Dsmartbots.seed.fire, farm worlds only, off = no-op)
        Brain brain = new Brain();
        if (Slayer.DOOR) try { Slayer.surface(bot, "brain start"); } catch (Throwable ignored) { } // slayer4: a bot left underground by Vannaka climbs back up
        if (Slayer.DURADEL) try { W13Duradel.leave(bot, "brain start"); } catch (Throwable ignored) { } // w13 lane B: a bot left in Shilo rides back to Brimhaven
        if (Slayer.CHAELDAR) try { W9Chaeldar.leave(bot, "brain start"); } catch (Throwable ignored) { } // w9 lane D: a bot left in Zanaris goes back out to the shed
        if (W17a.ON) try { W17a.rescue(bot, "brain start"); } catch (Throwable ignored) { } // W17a (-Dbots.str.defenders, default off): a bot left on an upper floor of the Warriors' Guild goes back to its door
        brain.nextThink = System.currentTimeMillis() + 5_000 + RNG.nextInt(FAST ? 5_000 : 60_000); // spread the first trips over a minute after a restart
        try { PlayerTrades.sweepPulseMap(); } catch (Throwable ignored) { } // never in the server's pulse map, not even for 30 s
        brain.fav = fav;
        brain.goal = goal;
        brain.home = home;
        HIGH_TIER.put(key, high);
        HOME.put(key, home);
        BRAINS.put(key, brain);
        if (FarmSave.ON) try { FarmSave.Rec fr = FarmSave.restore(bot, bot.getUsername(), System.currentTimeMillis()); if (fr != null && fr.siteIdx >= 0) { brain.farmSite = fr.siteIdx; brain.farmDue = fr.due; Farming.ACTIVE.add(key); } if (fr != null && W9Trees.RUN) brain.treeRestore(fr, System.currentTimeMillis()); } catch (Throwable t) { System.out.println("[FarmSave] restore for " + bot.getUsername() + ": " + t); } // W2: saved crops back (break end, restart, retry); a bad file never stops the login
        { Map<String, Long> bl = SAVED_BLACKLISTS.get(key); if (bl != null) brain.blacklist.putAll(bl); Integer dn = SAVED_DEATHS.get(key); if (dn != null) brain.deaths = dn; } // combat review 10-23 #5: what killed it before the restart
        ENLISTED.putIfAbsent(key, System.currentTimeMillis());
        STORY.computeIfAbsent(key, k -> new String[]{CATCHPHRASES[RNG.nextInt(CATCHPHRASES.length)], GRUDGES[RNG.nextInt(GRUDGES.length)]});
        ph("e.state"); new GeneralBotCreator(brain, bot); ph("e.gbc"); Seed.task(brain, bot); SeedFire.task(brain, bot); // W2 lane S: scenario seed of a pre-held Slayer task (farm worlds only, flag off = no-op)
        AIRepository.getPulseRepository().remove(key); // TradeModule cast bug
        // The server's BotScriptPulse only ticks a script while the bot has no pulse of its own running. A bot that keeps
        // fishing after its trip is "finished" therefore never thinks again (seen: fishers stuck for ever after one trip).
        // So the brain gets its own heartbeat; think() is throttled, so being called twice costs nothing.
        GameWorld.getPulser().submit(new core.game.system.task.Pulse(3) {
            @Override
            public boolean pulse() {
                if (!bot.isActive()) return true; // logged out or cleared: the heartbeat ends with it
                try { brain.tick(); } catch (Throwable ignored) { }
                return false;
            }
        });
        VETERAN.put(key, veteran);
        FAV.put(key, fav);
        GOAL.put(key, goal);
        if (S10_FAULT_NAME != null) { Profile rp = restoringProf; s10Fault("enlist", rp != null ? rp.name : bot.getUsername()); } // batch 22 S-10 trial fault injection (TestB22S10 only)
        BOTS.add(bot); ph("e.misc");
        System.out.println("[SmartAdv] " + bot.getUsername() + " " + how + " (combat " + bot.getProperties().getCurrentCombatLevel()
                + ", fishing " + bot.getSkills().getStaticLevel(Skills.FISHING) + ", mining " + bot.getSkills().getStaticLevel(Skills.MINING)
                + ", woodcutting " + bot.getSkills().getStaticLevel(Skills.WOODCUTTING) + ", trade " + skillName(fav) + ", from " + BotLife.Town.values()[Math.max(0, home) % BotLife.Town.values().length].name + ")");
        ph("e.print"); Crew.join(brain); ph("e.crew");
    }

    // ---- D23 crews: two to four bots from the same town that take jobs together, talk on the way and share out.
    static final class Crew {
        static final List<Crew> CREWS = new java.util.concurrent.CopyOnWriteArrayList<>();
        final int home;
        final List<Brain> members = new java.util.concurrent.CopyOnWriteArrayList<>();
        Brain leader;
        Job job; long jobSetAt;
        Crew(int home) { this.home = home; }

        String name() { return leader != null && leader.bot != null ? leader.bot.getUsername() + "'s crew" : "a crew"; }

        static int tierOf(Brain b) {
            String k = b.bot == null ? "" : b.bot.getUsername().toLowerCase(Locale.ROOT);
            return HIGH_TIER.getOrDefault(k, false) ? 2 : VETERAN.getOrDefault(k, false) ? 1 : 0;
        }

        static void join(Brain b) {
            if (b.crew != null || b.bot == null) return;
            for (Crew c : CREWS) {
                if (c.home != b.home || c.members.size() >= 4 || c.members.isEmpty()) continue;
                if (c.leader != null && tierOf(c.leader) != tierOf(b)) continue; // fresh with fresh, veterans with veterans (owner notes #4)
                if (RNG.nextInt(3) == 0) continue; // not everyone wants company
                c.members.add(b); b.crew = c;
                Player lead = c.leader != null ? c.leader.bot : null;
                if (lead != null && lead.isActive()) {
                    b.say(BotChat.pick("Alright if I tag along, " + lead.getUsername() + "?", "Room for one more, " + lead.getUsername() + "?", "Oi " + lead.getUsername() + ", where are we off to?", "Mind if I join you, ", "Room in the group, "));
                    BotChat.speak(lead, b.bot, java.util.Arrays.asList(BotChat.pick("Course. Keep up.", "Always. Bring food.", "Go on then. You carry the bones.", "Sure. Don't slow us down.", "The more the merrier.", "Welcome aboard.")), 3);
                }
                System.out.println("[SmartAdv] " + b.bot.getUsername() + " joined " + c.name() + " (" + c.members.size() + " from " + BotLife.Town.values()[c.home % BotLife.Town.values().length].name + ")");
                return;
            }
            if (RNG.nextInt(3) != 0) return; // most start out alone; a third open a crew others can join
            Crew c = new Crew(b.home); c.leader = b; c.members.add(b); b.crew = c; CREWS.add(c);
        }

        void leave(Brain b) {
            members.remove(b);
            if (b.crew == this) b.crew = null;
            if (members.isEmpty()) { CREWS.remove(this); return; }
            if (leader == b) { leader = members.get(0); job = null; }
        }

        /** After a trip: the richest hands the poorest a tenth of the gap, and says so. Loot shared, roughly, like mates do. */
        void shareOut(Brain from) {
            Brain rich = null, poor = null;
            for (Brain m : members) {
                if (m.bot == null || !m.bot.isActive()) continue;
                if (rich == null || Ledger.Wallet.coins(m.bot) > Ledger.Wallet.coins(rich.bot)) rich = m;
                if (poor == null || Ledger.Wallet.coins(m.bot) < Ledger.Wallet.coins(poor.bot)) poor = m;
            }
            if (rich == null || poor == null || rich == poor || rich != from) return;
            long gap = Ledger.Wallet.coins(rich.bot) - Ledger.Wallet.coins(poor.bot);
            if (gap < 2_000) return;
            long share = gap / 10;
            if (Ledger.Wallet.pay(rich.bot, share)) {
                Ledger.Wallet.give(poor.bot, share);
                rich.say(String.format(BotChat.pick("Here, your share, %s. %s.", "%s, that's %s for you. Don't spend it all on trout.", "Split's %2$s each, %1$s. Roughly."), poor.bot.getUsername(), String.format("%,d", share) + " gp"));
                if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + rich.bot.getUsername() + " shared " + share + " gp with " + poor.bot.getUsername());
            }
        }

        void mourn(Brain dead) {
            for (Brain m : members) {
                if (m == dead || m.bot == null || !m.bot.isActive()) continue;
                BotChat.speak(m.bot, null, java.util.Arrays.asList(String.format(BotChat.pick("Hang on %s, I'll bless your grave.", "Not %s! Right, grave duty.", "%s is down. Someone grab their stuff before it rots."), dead.bot.getUsername())), 2 + RNG.nextInt(3));
            }
        }
    }

    static int bestGatherSkill(Player bot) {
        int best = Skills.FISHING;
        for (int sk : new int[]{Skills.FISHING, Skills.MINING, Skills.WOODCUTTING, Skills.THIEVING}) if (bot.getSkills().getStaticLevel(sk) > bot.getSkills().getStaticLevel(best)) best = sk;
        return best;
    }

    static String skillName(int sk) {
        if (sk == Skills.FISHING) return "fishing"; if (sk == Skills.MINING) return "mining"; if (sk == Skills.WOODCUTTING) return "woodcutting"; if (sk == Skills.THIEVING) return "thieving";
        return sk < 0 ? "fighting" : "skill " + sk;
    }

    /** Things a bot saves up for (B16): item, the skill it needs and the level. */
    static final int[][] GOALS = {
            {1333, Skills.ATTACK, 40}, {1163, Skills.DEFENCE, 40}, {1127, Skills.DEFENCE, 40}, {1079, Skills.DEFENCE, 40}, {1201, Skills.DEFENCE, 40}, {4131, Skills.DEFENCE, 40},
            {1704, -1, 0}, {6568, -1, 0}, {3751, Skills.DEFENCE, 45}, {1305, Skills.ATTACK, 60}, {4587, Skills.ATTACK, 60}, {1187, Skills.DEFENCE, 60}, {11732, Skills.DEFENCE, 60}, {4151, Skills.ATTACK, 70},
            {1093, Skills.DEFENCE, 40}, {1215, Skills.ATTACK, 60},  // rune plateskirt (Seddus), dragon dagger (Rasolo)
            // mid-level goals from the shops a 2009 player used (consultant check Q6: 81 of 102 bots had no goal)
            {1331, Skills.ATTACK, 30}, {1329, Skills.ATTACK, 20}, {1123, Skills.DEFENCE, 30}, {1073, Skills.DEFENCE, 30}, {1161, Skills.DEFENCE, 30}, {1199, Skills.DEFENCE, 30},
            {1121, Skills.DEFENCE, 20}, {1071, Skills.DEFENCE, 20}, {1159, Skills.DEFENCE, 20}, {1197, Skills.DEFENCE, 20}, {1135, Skills.RANGE, 40}, {861, Skills.RANGE, 50}, {1731, -1, 0}};

    /** Something worth saving for: usable at this bot's levels, not already worn, dearer than what it has in the bank. */
    /**
     * Where a goal can really be bought when the Exchange has none: the 2009 specialty shop that sells it, from the
     * shop list (map-data). Some need a quest a player would have done first; those are for the high tier only
     * (Dragon Slayer for Oziach's rune platebody, Fremennik Trials for berserker helms, Monkey Madness for dragon scimitars).
     * Goals with no shop and no stock (glory, obsidian cape, dragon sq shield, whip, dragon boots, dragon longsword)
     * are only chosen while the Exchange actually has one. Found 2026-10-20 by the owner: all goal items had 0 stock and
     * no source, so no bot ever bought one.
     */
    static final java.util.Set<Integer> HIGH_ONLY_SHOP = new java.util.HashSet<>(java.util.Arrays.asList(1127, 3751, 4587));

    static Object[] goalShop(int id) {
        for (Object[] shop : BotChat.WorldData.shops) for (int sid : (int[]) shop[3]) if (sid == id) return shop;
        return null;
    }

    /** Gear a player can't wear without a quest (audit #15): rune platebody (Dragon Slayer), dragon dagger and longsword (Lost City), dragon scimitar (Monkey Madness), berserker helm (Fremennik Trials). Only the high tier has done those. */
    static final java.util.Set<Integer> QUEST_GEAR = new java.util.HashSet<>(java.util.Arrays.asList(1127, 1215, 1305, 4587, 3751));
    static boolean questOk(Player bot, int id) { return !QUEST_GEAR.contains(id) || HIGH_TIER.getOrDefault(bot.getUsername().toLowerCase(Locale.ROOT), false); }

    static boolean obtainable(Player bot, int id) {
        if (!questOk(bot, id)) return false;
        if (MarketBrain.stockOf(id) > 0) return true;
        if (goalShop(id) == null) return false;
        return !HIGH_ONLY_SHOP.contains(id) || HIGH_TIER.getOrDefault(bot.getUsername().toLowerCase(Locale.ROOT), false);
    }

    static int pickGoal(Player bot) {
        long coins = Ledger.Wallet.coins(bot);
        List<Integer> ok = new ArrayList<>();
        for (int[] g : GOALS) {
            if (g[1] >= 0 && bot.getSkills().getStaticLevel(g[1]) < g[2]) continue;
            if (!obtainable(bot, g[0])) continue; // something it could actually buy
            if (MarketBrain.stockOf(g[0]) <= 0) { Object[] gs = goalShop(g[0]); if (gs != null && !Preflight.ok(bot, Location.create((Integer) gs[1], (Integer) gs[2], 0))) continue; } // batch 20 A1: a shop it may not walk to (a guild, Morytania, out of Morytania broke)
            boolean worn = false;
            try { for (Item it : bot.getEquipment().toArray()) if (it != null && it.getId() == g[0]) worn = true; } catch (Throwable ignored) { }
            if (worn) continue;
            int v = MarketBrain.value(g[0]);
            if (v > coins && v < Math.max(20_000, coins * 40)) ok.add(g[0]);
        }
        return ok.isEmpty() ? 0 : ok.get(RNG.nextInt(ok.size()));
    }

    /** Logs a bot out for a break (B13). Its profile is kept in OFFLINE and it comes back later by the restore path. Returns the profile (nameClash renames it). */
    static Profile logOut(AIPlayer bot, Brain brain) {
        try { Bob.leave(bot, "logout"); } catch (Throwable ignored) { } // P86 hook 4 (F14: bot.clear never removes a familiar)
        try { Scenes.Greet.drop(bot, "logout"); } catch (Throwable ignored) { } // P57 A6
        try { Scenes.RescueTalk.forget(bot); } catch (Throwable ignored) { } // P66: a stand-in forgets what it heard
        try { midTask(bot, brain); } catch (Throwable ignored) { } // batch 19c §8.1
        if (brain != null) brain.dressBack();
        if (brain != null && brain.summon != 0) try { brain.endSummon(true, "logout"); } catch (Throwable ignored) { } // P62: banks the errand's items, out of the cellar
        if (brain != null && Farming.ON) try { brain.farmLogout(); } catch (Throwable ignored) { } // C8: crops are never saved; counted as abandoned
        if (BRAIN_FIX1 && brain != null && brain.bone != 0) try { brain.endBone(true); } catch (Throwable ignored) { }
        if (W37Craft.ANY && brain != null && brain.w37 != null) try { W37Craft.end(brain, true, "logout"); } catch (Throwable ignored) { brain.w37 = null; } // w37-craft: products sold, the rest banked
        if (brain != null && brain.bone != 0) brain.bone = 0; // P53: a bury run ends with the session (bones left in the pack are saved with it)
        if (brain != null) brain.slay = 0; // P54
        if (brain != null && brain.starUntil > 0) try { brain.endStar(); } catch (Throwable ignored) { } // frees its AT_STAR slot (five leaks and nobody mined stars until a restart)
        if (brain != null && brain.errandFor != null) { try { Player eh = Ledger.Wallet.findOnline(brain.errandFor); if (eh != null && !eh.isArtificial()) eh.sendMessage("<col=0b5e1f>" + bot.getUsername() + " logged off before finishing your errand; the order is off, sorry."); } catch (Throwable ignored) { } brain.errandFor = null; }
        try { PlayerTrades.dropGives(bot); } catch (Throwable ignored) { } // a quest item waiting for a trade doesn't go into the saved pack
        String key = bot.getUsername().toLowerCase(Locale.ROOT);
        Profile p = new Profile();
        p.name = bot.getUsername(); p.veteran = VETERAN.getOrDefault(key, false);
        for (int i = 0; i < 24; i++) p.levels[i] = bot.getSkills().getStaticLevel(i);
        p.xp = new double[24]; for (int i = 0; i < 24; i++) p.xp[i] = bot.getSkills().getExperience(i);
        try { Item[] eq = bot.getEquipment().toArray(); for (int s = 0; s < eq.length; s++) if (eq[s] != null && s != 13) p.gear.add(eq[s].getId()); } catch (Throwable ignored) { }
        captureItems(bot, p); // pack, bank and quiver come back with it
        p.coins = Ledger.Wallet.coins(bot); p.fav = brain.fav; p.goal = brain.goal; p.home = brain.home; p.high = HIGH_TIER.getOrDefault(key, false);
        p.direct = DIRECT_NAMES.contains(key); // P31 fix 1: comes back through directBot
        try { Failures.tripCoins(bot, p.coins, false, "logout", brain.lastJob != null ? brain.lastJob.label() : null, brain.trips); } catch (Throwable ignored) { } // batch 19c §4.1
        String[] st = STORY.get(key); if (st != null) { p.catchphrase = st[0]; p.grudge = st[1]; }
        if (brain.crew != null) brain.crew.leave(brain);
        BRAINS.remove(key);
        try { RouteMap.forget(key); } catch (Throwable ignored) { }
        try { Preflight.forget(bot); } catch (Throwable ignored) { } // batch 20 A3: a climb in progress
        try { NavWalker.forget(bot); } catch (Throwable ignored) { } // audit B-19: its planner route (STATES) leaked and was reused after the break
        long away = Integer.getInteger("smartbots.awaySec", 0) > 0 ? Integer.getInteger("smartbots.awaySec", 0) * 1000L : FAST ? 90_000 + RNG.nextInt(60_000) : 3_600_000L + RNG.nextInt(3 * 3_600_000);
        p.backAt = System.currentTimeMillis() + away;
        OFFLINE.add(p);
        BOTS.remove(bot);
        AfkTasks.RUNNING.remove(key);
        BotChat.clearActivity(bot);
        if (brain != null) brain.running = false; // audit 10-23 S-5: the engine's BotScriptPulse ends only on !running (it ticked the cleared bot for the rest of uptime)
        try { bot.clear(); } catch (Throwable t) { System.out.println("[SmartAdv] could not log " + p.name + " out: " + t); }
        System.out.println("[SmartAdv] " + p.name + " logged out for " + (away / 60_000) + " min (" + OFFLINE.size() + " on a break)");
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
                    @Override
                    public boolean pulse() {
                        comeBack(p); // batch 19c: the same two steps, in a method (login-failed at its catch)
                        return true;
                    }
                });
            }
        }, away);
        return p;
    }

    /**
     * Train-3 audit finding 2 (-Dbots.nameGuard=true, NameGuard): {@code holder}, an active real player, has this bot's display
     * name now. The bot goes on a break by the usual path (logOut: profile, coins, items kept) and its profile is renamed, so it
     * comes back under a name nobody holds; its queued lots, the coins owed to it and its quest book follow the new name, not
     * the player. No free name (a 12-char name with nothing left to add): the bot keeps its name, one line per bot per boot,
     * tried again next think. Game thread.
     */
    static void nameClash(AIPlayer bot, Brain brain, Player holder) {
        String old = bot.getUsername();
        String fresh = pickHumanName(old, k -> k.equalsIgnoreCase(old) || nameTaken(k), RNG);
        if (fresh == null) { if (NameGuard.WARNED.add(old.toLowerCase(Locale.ROOT))) System.out.println("[HumanLook] name clash: " + old + " is a player's account now and no free name was found; the bot keeps its name"); return; }
        Profile p = logOut(bot, brain);
        if (p == null) return;
        String k = fresh.toLowerCase(Locale.ROOT);
        p.name = fresh; p.direct = true; DIRECT_NAMES.add(k); KNOWN_NAMES.add(k);
        try { QuestBook.rename(old, fresh); } catch (Throwable ignored) { }
        try { FarmSave.rename(old, fresh); } catch (Throwable ignored) { } // W2: its crop file too
        try { Ledger.rename(old, fresh); } catch (Throwable ignored) { } // its lots and the coins owed to it follow the profile, not the player
        NameGuard.clashes++;
        System.out.println("[HumanLook] name clash: " + old + " is a player's account now (" + (holder.isArtificial() ? "trial stand-in" : "real player") + "); the bot is on a break and comes back as " + fresh);
        try { Failures.event("lifecycle", null, "login", "name-clash", old + " -> " + fresh, null, Failures.ctx("was", old, "now", fresh)); } catch (Throwable ignored) { }
    }

    static final Location GRAND_EXCHANGE = Location.create(3165, 3487, 0);
    static final int[][] SHOP_FOOD = {{333, 7}, {329, 9}, {379, 12}, {373, 14}}; // trout, salmon, lobster, swordfish: what the bot buys to fight on, with what each heals (combat review 10-23 #7)
    static final int SHARK = 385; // heals 20; only out of bot stock (no 2009 shop sells it)

    /** True when this is something an adventurer can go out and gather or hunt for (any level). */
    static boolean gatherable(int itemId) {
        for (Job j : JOBS) for (int id : j.products()) if (id == itemId) return true;
        return false;
    }

    /** True when at least one smart adventurer alive right now has the levels to go and get this item. */
    static boolean capable(int itemId) {
        for (Job j : JOBS) {
            boolean makes = false;
            for (int id : j.products()) if (id == itemId) makes = true;
            if (!makes) continue;
            for (Player b : BOTS) {
                if (b == null || !b.isActive()) continue;
                if (j.skill >= 0 ? b.getSkills().getStaticLevel(j.skill) >= j.level : b.getProperties().getCurrentCombatLevel() >= j.level) return true;
            }
        }
        return false;
    }

    static final int[] AXE_BY_LEVEL = {1351, 1349, 1353, 1355, 1357, 1359};      // bronze..rune
    static final int[] PICK_BY_LEVEL = {1265, 1267, 1269, 1273, 1271, 1275};

    /** Buys a tool out of bot stock if there is one; otherwise the bot is assumed to have bought it in a shop. */
    /** At the Grand Exchange (where a player has to be to buy from it). */
    static boolean nearGE(Player p) { return p != null && p.getLocation().getZ() == 0 && p.getLocation().getDistance(GRAND_EXCHANGE) <= 30; }

    static int buyTool(Player p, int[] options, int skillLevel) {
        int idx = skillLevel >= 41 ? 5 : skillLevel >= 31 ? 4 : skillLevel >= 21 ? 3 : skillLevel >= 6 ? 2 : skillLevel >= 1 ? 1 : 0;
        for (int i = idx; i >= 0; i--) {
            int id = options[i];
            if (nearGE(p) && MarketBrain.stockOf(id) > 5 && Ledger.consume(p, id, 1, "tool") == 1) return id;
        }
        int shop = options[Math.min(1, idx)]; // bronze/iron from a shop, only if the bot can pay (audit #4: it used to get it anyway)
        return Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(shop))) ? shop : -1;
    }

    static <T> T pick(T[] a) { return a[RNG.nextInt(a.length)]; }
    static String pick(String[] a) { return BotChat.pick(a); } // lines: no repeats in a short window

    static final String[] SET_OUT = {
            "Market's out of %s. I'll go get some.", "Nobody's selling %s, so it's me then.", "Off to find %s, back soon.",
            "Someone's got to fetch %s. Might as well be me.", "Heard the GE wants %s. On it.",
            "Right then. %s won't gather itself.", "The Exchange is crying out for %s.", "%s run. Wish me luck.",
            "Out for %s. Back before supper.", "Price of %s is up. Time to work.", "Nobody else is doing %s, so I am.",
            "Going for %s. Don't sell my spot.", "%s. Again. It pays.", "Off I go. %s won't wait.",
            "Pick sharp, boots on. %s.", "Let's see how much %s I can carry.", "One more %s trip. Promise."};
    static final String[] DONE = {"That's a bag of %s on the Exchange.", "Sold my %s. Decent trip.", "%s delivered. What's next?",
            "%s banked. Not bad.", "Good run of %s, that.", "%s in. Feet aching.", "That's the %s sorted.", "Done with %s for now.",
            "A tidy bit of %s. Pays the bills.", "%s on the Exchange. Someone'll want it.", "Back with %s. Who's buying?", "%s done. Nearly fell asleep."};

    enum Mood { FINE, CHATTY, TIRED, GRUMPY }

    static final class Brain extends Script {
        /** brain1 H1: the bot's own errands except the star (sites that test starUntil against "now" add it themselves). No runner: a job is not an errand. */
        boolean errandNoStar() { return Hunter.ON && Hunter.on(this) || W17a.ON && W17a.on(this) || shopAt != null || mill != 0 || bone != 0 || summon != 0 || farm != 0 || slay != 0 || graveAt != null || delivering || agiStep >= 0 || Scenes.Greet.escorting(bot) || rcRun != null; } // t63 fix 4: an altar runner (rcRun, null with bots.rc.runs off) is busy too
        /** brain1 H1: on an errand of its own (shop, mill, bone, summon, farm, slay, grave, delivery, agility, escort, star). */
        boolean errand() { return errandNoStar() || starUntil > 0 || w37 != null; } // w37-craft: a gem or potion run is an errand (null with the flags off)
        /** brain1 H1: anything at all holds the bot: a job, an errand, a player's crew or order, a quest. */
        boolean busy() { return runner != null || errand() || companion != null || errandFor != null || QuestRunner.active(bot); }
        boolean cjFell, cjRolled; // brain1 L2: chooseJob crew fallback (only used with -Dbrain.fix1)
        /** brain1 L1 (-Dbrain.fix1): a Watchdog rescue also ends the mill, slayer, agility, star and delivery errands (the shop walk stays: it resumes from the bank). */
        void errandsRescued() { if (mill != 0) { mill = 0; BotChat.clearActivity(bot); } if (slay != 0) { slay = 0; if (slaySt != null) slaySt.errandTo = null; BotChat.clearActivity(bot); } if (agiStep >= 0) agiStep = -1; if (starUntil > 0) endStar(); if (delivering) endDelivery(false); }
        AfkTasks.Runner runner;
        AfkTasks.Task current;
        long nextThink = System.currentTimeMillis() + 5_000, nextChat;
        int trips;
        Job lastJob;
        final BrokeSkip zeroSkip = BrokeSkip.ON ? new BrokeSkip() : null; // W4 guards (-Dbots.brokeJob.zeroSkip): null with the flag off
        int fav = -2, goal, home;
        int tripsN, fightTripsN; // Summoning in combat (-Dbots.summoning.combat): trips launched this session, and how many were fights
        // B17 for real: a walk to an actual shop before a trip that needs a tool or food.
        Location shopAt; String shopName; Job pendingJob; long shopDeadline; boolean skipShopOnce, goalTrip, armourTrip, armourChecked;
        // Trading layer D: a plank run. Logs -> sawmill (fee destroys coins) -> the house (planks destroyed building).
        int mill; int millLog, millPlank, millCount; long millStart, millNext;
        int summon, summonRow, summonN, summonBuy, summonWait, summonClicks, summonLvl0, summonPouch0; long summonStart, summonNext, summonSpent, summonCool, summonRollAt; double summonXp0; boolean summonTested, summonVia; final QuestSteps.Mover summonMove = new QuestSteps.Mover(); // P62 summoning errand (-Dbots.summoning, Summon.java); summon stays 0 with the flag off
        int farm, farmSite, farmIdx, farmWait, farmStall, farmAct, farmClicks, farmBase, farmCrop; long farmStart, farmNext, farmDue, farmSpent; boolean farmTested, farmHerb, farmPlant, farmNew, farmNewHerb, farmCheck; int farmFails; final QuestSteps.Mover farmMove = new QuestSteps.Mover(); // C8 farming (-Dbots.farming, Farming.java); farm and farmDue stay 0 with the flag off
        int treeSite = -1, treeSapling = -1, treePayId = -1, treePayQty, treeFails, treeGot, treeProd = -1, treeProdBase; long treeDue, treeNext; boolean farmTree, treeCheck, treePlanted; String treeDigWhat = ""; final int[] treeBase0 = new int[W9Trees.FRUITS.length]; // w9 trees (-Dbots.farming.trees.run, W9Trees.java): farmTree stays false and treeDue 0 with the flag off
        int[] w10Legs = W10Skills.NO_LEGS; int w10Leg = -1, w10LegGot, w10LegPlant; // W10 lane M (-Dbots.farming.herbRun): this run's extra herb sites and the one being worked (-1 = the home site); w10Leg stays -1 with the flag off
        int bone, boneId, boneCount, boneBought, boneLeft, boneStall, bonePrayer0; long boneStart, boneNext, boneSpent; double boneXp0; boolean boneTested; // P53 prayer run (-Dbots.prayerRun, PrayerRun.java); bone stays 0 with the flag off
        int slay; final Slayer.St slaySt = new Slayer.St(); // P54 slayer v1 (-Dbots.slayer): 0 off, 1 walk to Turael, 2 talk
        // Trading point 1: an arranged meet. An artisan asked for something; this bot fetches it and brings it to them.
        Player deliverTo; int deliverId; boolean delivering; long deliverStart, deliverArrived, nextErrand;
        // S9/S12: after a death, run back to the gravestone like a player and take the items back.
        Location graveAt; long graveUntil;
        Location graveFrom, graveFor, shopFrom, shopFor; // batch 18 item2: where this grave run / shop walk started (set on its first tick), for the scenario record
        long blessUntil; // batch 15 item3: walking to a crewmate's gravestone to bless it (the think loop waits)
        boolean grudgeHunt, grudgeShouted; long nextTollMutter;
        int stashHelm, stashShield; // casual look at the Exchange: helm and shield off, back on before the next trip
        // Agility: an occasional 10-lap session on the Gnome Stronghold course, like a player training it up.
        int agiStep = -1, agiLaps, agiTries, agiStall, agiGoal = 10; long agiUntil, agiWait, nextAgility = System.currentTimeMillis() + (FAST ? 60_000 : 30 * 60_000); double agiXp, agiBest = Double.MAX_VALUE;
        W7Run.Barb barb; // W7 (-Dbots.agility.barb): the Barbarian Outpost visit's state; null on a Gnome visit and always with the flag off
        RcRuns.Run rcRun; // W8 (-Dbots.rc.runs): an air/mind altar run in progress; always null with the flag off
        boolean agilityNudge; // set when the route map turns a bot away from a shortcut its Agility is too low for
        double shopBest = Double.MAX_VALUE; int shopStalls; Location shopLast;
        Boolean viaPlan; // P73 B6: has the planner a route from the walk's start to this shop (asked once per shop walk)
        boolean viaPlanned() {
            if (viaPlan == null) { boolean ok = false; try { ok = NavPlanner.built && NavPlanner.reachable(bot.getLocation(), shopAt); } catch (Throwable ignored) { } viaPlan = ok; }
            return viaPlan;
        }
        Location viaFor, viaLast; int viaTurns; // pathing review 10-23 A-5: the shop walk these via waypoints belong to, the last one taken, how often it changed
        final List<int[]> shopBuy = new ArrayList<>(); // {item, amount}
        int toolBoughtId; long toolBoughtAt; // P65 v1.3 (-Dbots.slayer): the last single item bought on foot, for the tool-loop guard
        AfkTasks.Runner sea; Location seaFor; // batch 17 item3: the sea crossing of the current shop walk (AfkTasks.Runner.crossSea) and the shop it was for
        int seaPaid, seaPaidAll; Location seaPaidShop; long seaPaidAt; // batch 21e: paid sea crossings on this shop walk and in all (tests); the shop and time of the last one (the per-bot cooldown)
        Crew crew; long crewWaitSince;
        QuestScript questPick; // chooseJob's quest offer beat every job: think() starts it (QuestBook)
        // Companions (2026-10-21): following a real player's lead, or fetching something a player asked for
        Player companion; String errandFor; int errandId, errandQty; boolean errandCook;
        // B13 shifts, B14 routines, B18 moods
        final long shiftStart = System.currentTimeMillis();
        long shiftEnd = shiftStart + (Integer.getInteger("smartbots.shiftSec", 0) > 0 ? Integer.getInteger("smartbots.shiftSec", 0) * 1000L : FAST ? 240_000 + RNG.nextInt(180_000) : 2 * 3_600_000L + RNG.nextInt(3 * 3_600_000));
        long idleUntil, moodUntil, nextGoalTalk, nextBrokeTalk;
        Mood mood = Mood.FINE;
        int sinceLoiter;

        Mood mood() { return System.currentTimeMillis() > moodUntil ? (System.currentTimeMillis() > shiftEnd - (shiftEnd - shiftStart) / 4 ? Mood.TIRED : Mood.FINE) : mood; }
        void feel(Mood m, long forMs) { mood = m; moodUntil = System.currentTimeMillis() + forMs; }

        @Override
        public Script newInstance() { return null; }

        void say(String s) {
            BotChat.say(bot, s);
        }

        @Override
        public void tick() {
            final long t0 = System.nanoTime(); // batch 20 (19b+c follow-up 4): the brain's cost per tick
            try { tick0(); } finally { Watchdog.fpulse("SmartAdventurers.Brain", bot, System.nanoTime() - t0); }
        }

        void tick0() {
            if (bot != null) endDialogue = !QuestRunner.active(bot); // the server's bot pulse closes any dialogue while an interaction is still registered: never a quester's (live 10-23: Rain293, Mrnl274)
            try { sampleJump(); } catch (Throwable ignored) { }
            if (diedAt != null && bot != null && !bot.getAttribute("state:death", false)) try { checkDeath(); } catch (Throwable t) { System.out.println("[SmartAdv] death check: " + t); Watchdog.fexc("SmartAdventurers.Brain.checkDeath", bot, t); } // combat review 10-23 #6/#14: straight after the respawn, even for a crewmate (Companions holds think() off)
            try { think(); } catch (Throwable t) { System.out.println("[SmartAdv] " + (bot != null ? bot.getUsername() : "?") + " brain error: " + t); nextThink = System.currentTimeMillis() + 10_000; Watchdog.fexc("SmartAdventurers.Brain.think", bot, t); } // batch 19a B3
        }

        void think() {
            if (bot == null || !bot.isActive()) return;
            long now = System.currentTimeMillis();
            if (now < nextThink) return;
            nextThink = now + 4_000;
            try { Spectacle.partyCheck(now); } catch (Throwable t) { System.out.println("[SmartAdv] party check: " + t); } // batch 16 item3 (throttled to 30 s inside)
            checkDeath();
            if (NameGuard.ON && bot instanceof AIPlayer) { Player holder = NameGuard.clashWith(bot); if (holder != null) { nameClash((AIPlayer) bot, this, holder); return; } } // train-3 audit finding 2: a real player holds this name now
            if (shopRetal != null && shopAt == null) endShopGuard(); // batch 20 B1: the shop walk is over
            if (BRAIN_FIX1 && starUntil > 0 && now >= starUntil) endStar();
            if (companion != null) { if (!companion.isActive()) Companions.leave(this, null); return; } // in a player's crew: Companions drives the bot
            if (QuestRunner.active(bot)) return; // on a quest: QuestRunner drives the bot, a step a tick
            if (blessUntil > now) return; // batch 15 item3: blessing a crewmate's grave (its pulse drives the bot)
            if (graveAt != null && Deals.involved(bot)) { graveTick(now); return; } // audit 10-23 S-2: a grave run first (the stone crumbles while the deal waits)
            if (Deals.involved(bot)) return; // selling at a callout or walking to buy one: stay with it (it used to walk off to the next trip mid-sale, review 10-22)
            if (shopAt != null) { shopGuard(); shopTick(now); return; } // batch 20 B1
            if (mill != 0) { millTick(now); return; }
            if (bone != 0) { boneTick(now); return; } // P53: only ever set by planBoneRun (flag on)
            if (w37 != null) { W37Craft.tick(this, now); return; } // w37-craft: only ever set by W37Craft.plan (-Dbots.craft.gems / -Dbots.herb.potions)
            if (summon != 0) { summonTick(now); return; } // P62: only ever set by planSummonRun (flag on)
            if (Farming.ON && farm != 0) { farmTick(now); return; } // C8: only ever set by planFarmRun (flag on)
            if (Hunter.ON && Hunter.tick(this, now)) return; // W8 (-Dbots.hunter): a hunting run (only ever started by Hunter.plan)
            if (W17a.ON && W17a.tick(this, now)) return; // W17a (-Dbots.str.defenders, default off): a Warriors' Guild defenders run (only ever started by W17a.plan)
            if (slay != 0) { Slayer.tick(this, now); return; } // P54
            if (Slayer.ON && Slayer.OPEN_CHECK && slaySt.had != null) Slayer.watch(this, now); // P80 round 2 (flag off: never called)
            if (delivering) { deliverTick(now); return; }
            if (graveAt != null) { graveTick(now); return; }
            if (agiStep >= 0) { agilityTick(now); return; }
            if (rcRun != null) { RcRuns.tick(this, now); return; } // W8 (-Dbots.rc.runs): never set with the flag off
            if (runner != null) {
                if (AfkTasks.RUNNING.containsKey(bot.getUsername().toLowerCase(Locale.ROOT))) { // still working
                    if (Boolean.getBoolean("smartbots.styleDebug") && styleOf(bot) != MELEE && RNG.nextInt(5) == 0) { core.game.node.Node v = bot.getProperties().getCombatPulse().getVictim(); System.out.println("[StyleDbg] " + bot.getUsername() + " at " + bot.getLocation() + " victim " + (v == null ? "-" : v.getName()) + " style " + bot.getProperties().getCombatPulse().getStyle() + " rangedXp " + (int) bot.getSkills().getExperience(Skills.RANGE) + " magicXp " + (int) bot.getSkills().getExperience(Skills.MAGIC) + " runes " + bot.getInventory().getAmount(558) + "/" + bot.getInventory().getAmount(562) + "/" + bot.getInventory().getAmount(556) + " runner " + runner.last); }
                    runesCheck();
                    if (grudgeHunt && !grudgeShouted && runner.collected > 0) { grudgeShouted = true; String c = catchOf(bot); say((c != null ? c + " " : "") + BotChat.pick("That's one less of you lot.", "Never liked them. Never will.", "Personal, that one.")); }
                    if (crew != null) try { Scenes.Crews.banter(this, now); } catch (Throwable ignored) { } // batch 28: crew mates at one spot talk now and then, only with a player in sight (Scenes.java)
                    if ("the Al Kharid gate toll".equals(grudgeOf(bot)) && now > nextTollMutter) {
                        Location l = bot.getLocation();
                        if (l.getX() >= 3268 && l.getX() <= 3330 && l.getY() >= 3130 && l.getY() <= 3240 && l.getZ() == 0) { nextTollMutter = now + 3 * 3_600_000L; say(BotChat.pick("Ten gold for a gate. Robbery, every time.", "Still not paying that toll. Never.")); }
                    }
                    return;
                }
                trips++;
                if (Batch29.on(Batch29.CHAINS)) try { if (lastJob != null && lastJob.chain != 0) Batch29.Chains.done(this, runner); else Batch29.Chains.other(runner); } catch (Throwable ignored) { } // P78: chain counters
                if (zeroSkip != null) try { if (zeroSkip.note(lastJob, runner.collected, runner.endWhy, GameWorld.getTicks())) System.out.println("[Broke] " + bot.getUsername() + " rests " + lastJob.label() + " (free) for " + BrokeSkip.minutes() + " min: 0 collected (" + BrokeSkip.counts() + ")"); } catch (Throwable ignored) { } // W4 guards: a free job that ended on "Nothing for ..." with 0 collected rests for a cooldown
                try { Spectacle.tripDone(this, runner.collected); } catch (Throwable ignored) { }
                if (runner.collected > 0 && RNG.nextInt(3) == 0) say(String.format(pick(DONE), runner.label().toLowerCase(Locale.ROOT)));
                if (runner.collected >= 12) BotChat.news(String.format(BotChat.pick(
                        "Saw %1$s come past the bank with a sack of %2$s. Good day for it, apparently.",
                        "%1$s has been at the %2$s all afternoon. Came back grinning.",
                        "If you want %2$s, %1$s just unloaded a pile of them. Won't last.",
                        "%1$s sold the lot of %2$s this morning and went straight back out. No rest for the greedy."),
                        bot.getUsername(), runner.label().toLowerCase(Locale.ROOT)));
                if (runner.collected >= 20) feel(Mood.CHATTY, 10 * 60_000);
                armourChecked = false; exchangeChecked = false;
                runner = null; Bob.leave(bot, "runner end"); // P86 hook 11
                try { Failures.tripCoins(bot, Ledger.Wallet.coins(bot), false, "trip_end", lastJob != null ? lastJob.label() : null, trips); } catch (Throwable ignored) { } // batch 19c §4.1
                if (errandFor != null) { try { Companions.errandDone(this); } catch (Throwable t) { errandFor = null; System.out.println("[SmartAdv] errand for " + bot.getUsername() + ": " + t); } nextThink = now + 5_000; return; } // a player's order: hold it at the bank for them
                if (goal == 0) { goal = pickGoal(bot); GOAL.put(bot.getUsername().toLowerCase(Locale.ROOT), goal); if (Boolean.getBoolean("afk.debug") || goal > 0) System.out.println("[SmartAdv] " + bot.getUsername() + " goal pick: " + (goal > 0 ? MarketBrain.name(goal) : "none") + " (coins " + Ledger.Wallet.coins(bot) + ")"); if (goal > 0 && RNG.nextInt(2) == 0) say(BotChat.pick("Saving for a " + MarketBrain.name(goal).toLowerCase(Locale.ROOT) + ".", "Right. A " + MarketBrain.name(goal).toLowerCase(Locale.ROOT) + " next.")); } // a goalless bot keeps looking (Q6)
                sinceLoiter++;
                nextThink = now + (FAST ? 5_000 : 20_000 + RNG.nextInt(40_000));
                if (Boolean.getBoolean("xp.debug")) System.out.println("[XPDBG] trip end " + bot.getUsername() + " job " + (lastJob != null ? lastJob.label() : "?") + " bad=[" + xpCheck(bot) + "]");
                if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + bot.getUsername() + " trip " + trips + " done: " + Ledger.Wallet.coins(bot) + " gp, mood " + mood() + ", shift " + (now > shiftEnd ? "over" : (shiftEnd - now) / 60_000 + " min left") + ", " + OFFLINE.size() + " on a break");
                try { checkGoal(); } catch (Throwable t) { System.out.println("[SmartAdv] goal check failed for " + bot.getUsername() + ": " + t); }
                try { checkCape(); } catch (Throwable t) { System.out.println("[SmartAdv] cape check failed for " + bot.getUsername() + ": " + t); } // batch 15 item5
                if (crew != null) try { crew.shareOut(this); } catch (Throwable ignored) { }
                if (now > shiftEnd && OFFLINE.size() < COUNT * 2 / 5) { // end of the shift: bank's done, say goodnight (before errands; audit: 44 breaks a day for 96 bots)
                    say(BotChat.pick("Right, that's me for today. Night all.", "Logging. Back on later.", "Done for now. Offers are up if you need anything.", "Knackered. Off for a bit.", "Off to bed. Night!", "That's my lot. See you all later.", "Logging off. Don't sell everything while I'm gone.", "Night night. Back tomorrow. Probably."));
                    if (crew != null) { if (crew.leader == this) for (Brain m : crew.members) if (m != this && !(BRAIN_FIX1 && Farming.ON && m.farmDue != 0)) m.shiftEnd = Math.min(m.shiftEnd, now + 60_000); crew.leave(this); } // the crew winds down with its leader
                    if (bot instanceof AIPlayer) { final Brain me = this; GameWorld.getPulser().submit(new core.game.system.task.Pulse(3) { @Override public boolean pulse() { logOut((AIPlayer) bot, me); return true; } }); }
                    nextThink = now + 60_000;
                    return;
                }
                if (shopAt != null) return; // just set off to buy its goal
                if (Slayer.ON && Slayer.SETTLE_FIRST) Slayer.settleFirst(this, now); // slayer4 rider M1: the slayer bookkeeping before the errands below (order unchanged)
                if (W6Magic.ALCH_ALL && bot.getSkills().getStaticLevel(Skills.MAGIC) >= W6Magic.ALCH_LEVEL && now >= nextAlch) try { W6Magic.alchRoutine++; highAlch(now, true); } catch (Throwable t) { System.out.println("[Magic] alch routine for " + bot.getUsername() + ": " + t); } // W6 Magic (-Dbots.magic.alchAll): High Alchemy as a trip-end routine for any 55+ bot (instant: no return)
                if (planPlankRun(now)) return;
                if (W5aSkills.FIRE_LINE && planFireLine(now)) return; // W5a (-Dbots.fire.line): a pack of surplus logs burnt in a line by the bank
                if (Hunter.ON && Hunter.plan(this, now)) return; // W8 (-Dbots.hunter): snares at Feldip (and box traps with .chins)
                if (W17a.ON && W17a.plan(this, now)) return; // W17a (-Dbots.str.defenders, default off): tokens at the animators, then cyclopes for the next defender (no RNG)
                if (Farming.ON && planFarmRun(now)) return; // C8: allotments + herb patch (-Dbots.farming)
                if (PrayerRun.ON && planBoneRun(now)) return; // P53: bury bones at the bank (-Dbots.prayerRun)
                if (Summon.ON && planSummonRun(now)) return; // P62: summoning pouches at Taverley (-Dbots.summoning)
                if (Slayer.ON && Slayer.tripEnd(this, now)) return; // P54 slayer v1 (flag off: never called)
                if (W37Craft.ANY && W37Craft.plan(this, now)) return; // w37-craft (-Dbots.craft.gems, -Dbots.herb.potions, default off): the last errand, so every older one goes first
                if (sinceLoiter >= (BREAK_SOON ? 1 : Hubs.pushBreaks() ? 1 + RNG.nextInt(2) : 2 + RNG.nextInt(3))) loiter(now); // every few trips: a breather at the Exchange where people are (every trip under the TestHumanLook-only BREAK_SOON); batch 27 fix 3: every 1-2 while a hub is under its band (-Dhubs.breaks)
                return;
            }
            if (now >= idleUntil) dressBack(); // break over: kit back on before anything else
            if (Bob.FUN) Bob.think(this, now); // P86 S5: the visit is over -> the fun familiar goes
            if (now > shiftEnd + 1_800_000L && now >= idleUntil && shopAt == null && !delivering && graveAt == null && mill == 0 && !Scenes.Greet.escorting(bot) && bone == 0 && w37 == null && summon == 0 && slay == 0 && OFFLINE.size() < COUNT * 2 / 5) { // half an hour past the shift and still about: home
                say(BotChat.pick("Right, I'm off. Night.", "Long day. Logging.", "That's enough for me. Later, all."));
                if (crew != null) crew.leave(this);
                if (bot instanceof AIPlayer) { final Brain me = this; GameWorld.getPulser().submit(new core.game.system.task.Pulse(3) { @Override public boolean pulse() { logOut((AIPlayer) bot, me); return true; } }); }
                nextThink = now + 60_000;
                return;
            }
            if (now < idleUntil) { // hanging around the Exchange: shuffle about, maybe say something, maybe do a deal
                try { if (Scenes.Greet.tick(this, now)) return; } catch (Throwable ignored) { } // batch 28: greet a newcomer near Lumbridge and walk with them a bit (Scenes.java)
                if (starUntil > now) { starTick(now); return; }
                if (partyUntil > now) { Spectacle.partyTick(this, now); return; } // batch 16 item3: off to the party room, or dancing there
                if (loiterExtra(now)) return; // rec-review 10-23 batch14 item2: only with a real player within 20 tiles
                double ld = bot.getLocation().getDistance(loiterAt);
                if (loiterAt == GRAND_EXCHANGE && ld > 25 && ld > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.payTeleport(bot)) Spectacle.teleport(bot, GRAND_EXCHANGE.transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0)); // paid now (audit #16); batch 15 item1
                else if (ld > 8) { if (!bot.getWalkingQueue().isMoving()) { Location goal = spot(); Location step = ld <= 25 ? goal : Location.create(bot.getLocation().getX() + (int) Math.round((loiterAt.getX() - bot.getLocation().getX()) * 25 / ld), bot.getLocation().getY() + (int) Math.round((loiterAt.getY() - bot.getLocation().getY()) * 25 / ld), 0); try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { } } }
                else if (HUMAN_LOOK && unstack()) { } // P31 fix 3: never two bots on one tile at a hangout
                else if (RNG.nextInt(3) == 0) scriptAPI.randomWalkTo(spot(), HUMAN_LOOK ? 3 : 5);
                else if (HUMAN_LOOK) { if (now >= nextEmote) { nextEmote = now + 30_000 + RNG.nextInt(60_000); if (RNG.nextInt(3) == 0) idleEmote(); } } // P31 fix 3: now and then, not every few seconds
                else if (RNG.nextInt(4) == 0) { // on a break: emotes, like players idling at a hangout (2026-10-20)
                    int[] em = mood() == Mood.TIRED ? new int[]{2111, 2111, 857, 860} : mood() == Mood.CHATTY ? new int[]{861, 862, 863, 865, 866} : mood() == Mood.GRUMPY ? new int[]{859, 856, 857} : new int[]{857, 863, 861, 855, 866, 2111};
                    try { bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(em[RNG.nextInt(em.length)])); breakEmotes++; } catch (Throwable ignored) { }
                }
                if (Spectacle.tick(this, now)) return; // a show for the people watching (Spectacle.java)
                if (RNG.nextInt(6) == 0 && highAlch(now)) return; // a mage on a break alchs surplus bows (second consultant check A)
                if (nearGE(bot) && RNG.nextInt(8) == 0) { try { checkGoal(); } catch (Throwable ignored) { } if (shopAt != null) return; }
                if (now > nextChat && RNG.nextInt(4) == 0) { if (!directDeal()) say(idleLine()); nextChat = now + 40_000; }
                return;
            }
            if (crew != null && crew.leader != this && !(Slayer.FIX8 && Slayer.tripFirst(this))) { // follow the leader's call (slayer8 c: not while its own task trip is due)
                Job lead = crew.job;
                Brain L = crew.leader;
                boolean leaderBusy = L == null || L.errandNoStar() || L.starUntil > now;
                if (lead != null && !able(lead)) lead = null; // can't do what the leader picked: go its own way, no waiting
                if (lead != null && now - crew.jobSetAt < 180_000 && lastJob != lead) {
                    if (RNG.nextInt(2) == 0 && crew.leader.bot != null) say(String.format(BotChat.pick("Wait up, %s!", "Right behind you, %s.", "%s, you'd better be right about this one.", "Coming, %s!", "Hold on, %s!", "Right with you, %s."), crew.leader.bot.getUsername()));
                    crewWaitSince = 0;
                    start(lead);
                    return;
                }
                if (crewWaitSince == 0) crewWaitSince = now;
                if (!leaderBusy && crew.job != null && able(crew.job) && now - crewWaitSince < 150_000) { nextThink = now + 8_000; return; } // give the leader a moment to decide
                crewWaitSince = 0;
            }
            if (starUntil > now) { starTick(now); return; }
            if (wantsStar(now)) { startStar(now); return; }
            if (wantsAgility(now)) { startAgility(now); return; }
            final boolean trip8 = Slayer.FIX8 && Slayer.tripFirst(this); // slayer8 c: a held task whose spot is open goes before a player's errand
            Job job = deliverTo == null && now > nextErrand && !trip8 ? Deals.errand(this) : null; // an artisan short of something nearby?
            if (job == null && deliverTo == null && now > nextErrand && !LOG_ASKS.isEmpty() && !trip8) job = builderErrand(now); // a builder short of oak logs?
            if (job == null && deliverTo == null && QuestBook.resume(this)) return; // a quest half done before a break or a restart
            if (job == null) job = chooseJob();
            if (questPick != null) { QuestScript qs = questPick; questPick = null; QuestRunner.start(bot, qs, 0, false); if (RNG.nextInt(2) == 0) say(BotChat.pick("Off to help with " + qs.display + ".", "Time I did " + qs.display + ".", "Right. " + qs.display + ". How hard can it be?", "Anyone done " + qs.display + "? Starting it now.", "Quest time. " + qs.display + ".", "Going to do " + qs.display + ". Wish me luck.", "Finally getting round to " + qs.display + ".")); return; } // batch 15 item1: more variants
            if (crew != null && crew.leader == this && job != null) { crew.job = job; crew.jobSetAt = now; }
            if (job == null) {
                try { noJob(now); } catch (Throwable ignored) { } // batch 19c §4.5/§8.1
                if (now > nextChat) { say(pick(new String[]{"Market looks well stocked today.", "Nothing worth fetching right now.", "Taking a breather."})); nextChat = now + 120_000; }
                nextThink = now + (FAST ? 20_000 : 90_000);
                return;
            }
            start(job);
        }

        /** Bank-standing like a player: off with the helm and shield while hanging about the Exchange. */
        void dressDown() {
            String k = bot.getUsername().toLowerCase(Locale.ROOT);
            if (!VETERAN.getOrDefault(k, false) && !HIGH_TIER.getOrDefault(k, false) || stashHelm != 0 || stashShield != 0) return;
            try {
                Item h = bot.getEquipment().get(0), s = bot.getEquipment().get(5);
                if (h != null && bot.getEquipment().remove(h)) stashHelm = h.getId();
                if (s != null && bot.getEquipment().remove(s)) stashShield = s.getId();
                bot.getEquipment().refresh();
            } catch (Throwable ignored) { }
        }

        void dressBack() {
            if (stashHelm == 0 && stashShield == 0) return;
            // audit #13: if something new went on meanwhile (a goal helm, a bought shield), keep it and bank the old piece
            if (stashHelm != 0) { if (bot.getEquipment().get(0) == null) wear(bot, stashHelm); else bot.getBank().add(new Item(stashHelm)); }
            if (stashShield != 0) { if (bot.getEquipment().get(5) == null) wear(bot, stashShield); else bot.getBank().add(new Item(stashShield)); }
            stashHelm = 0; stashShield = 0;
            try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
        }

        /** Where people hang about between jobs (owner notes #3): {name, x, y}. */
        static final Object[][] HANGOUTS = {
                {"the Lumbridge castle courtyard", 3222, 3219}, {"the Varrock fountain", 3212, 3429}, {"Falador park", 2995, 3378},
                {"the Rising Sun Inn", 2956, 3370}, {"Draynor market", 3082, 3249}, {"the Port Sarim docks", 3043, 3236},
                {"Catherby beach", 2843, 3432}, {"the Edgeville well", 3085, 3500}, {"the Al Kharid palace gardens", 3293, 3175},
                {"the Seers' Village bank", 2725, 3488}, {"the Barbarian Village fire", 3082, 3417}, {"the Ardougne market", 2662, 3306}};
        Location loiterAt = GRAND_EXCHANGE; String loiterName = "the Grand Exchange";

        void loiter(long now) {
            sinceLoiter = 0;
            dressDown();
            idleUntil = now + (BREAK_SOON ? 180_000 + RNG.nextInt(120_000) : FAST ? 20_000 : 60_000 + RNG.nextInt(120_000)); // BREAK_SOON: long enough to reach the hangout before the break ends
            loiterAt = GRAND_EXCHANGE; loiterName = "the Grand Exchange";
            Hubs.Hub ub = Hubs.on() ? Hubs.underBreakHub(bot.getLocation()) : null; // batch 27 fix 3: the under hub this break goes to (pure: no RNG, no state)
            if (!(Hubs.BREAKS && ub != null && ub.anchor == GRAND_EXCHANGE) && RNG.nextBoolean()) { // half the breaks at the nearest hangout instead (deals still happen at the Exchange)
                Object[] best = null; double bd = 160;
                for (Object[] h : HANGOUTS) { double d = Location.create((Integer) h[1], (Integer) h[2], 0).getDistance(bot.getLocation()); if (d < bd) { bd = d; best = h; } }
                if (best != null) { loiterAt = Location.create((Integer) best[1], (Integer) best[2], 0); loiterName = (String) best[0]; }
            }
            if (Hubs.on()) { Object[] hb = Hubs.breakSpot(bot.getLocation(), loiterAt); if (hb != null) { loiterAt = (Location) hb[0]; loiterName = (String) hb[1]; } } // batch 27: an under-band hub takes the break, an over-band one is avoided (off/none flagged: untouched)
            if (Bob.FUN) Bob.funSummon(this, now, loiterAt, loiterAt == GRAND_EXCHANGE); // P86 S5: a walked visit may take a wolf or dreadfowl along
            if (loiterAt == GRAND_EXCHANGE && bot.getLocation().getDistance(GRAND_EXCHANGE) > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.payTeleport(bot)) Spectacle.teleport(bot, GRAND_EXCHANGE.transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0)); // otherwise it walks there; batch 15 item1
            else if (ub != null && loiterAt == ub.anchor) idleUntil += Hubs.walkMs(bot.getLocation(), loiterAt); // batch 27 fix 3: walking to an under hub: the break starts on arrival, not on the road (1.5 tiles/s, cap 4 min; 0 with -Dhubs.breaks=false)
            loiterSpot = HUMAN_LOOK ? spreadTile(loiterAt) : null; // P31 fix 3: its own tile near the hangout, not the hangout tile everyone used
            nextEmote = now + 10_000 + RNG.nextInt(30_000);
            final String ln = loiterName;
            BotChat.activity(bot, () -> "hanging around " + ln + (mood() == Mood.TIRED ? ", about done for the day" : ""));
        }

        // ---- P31 fix 3 (human-look-1): tile spread and idle emotes on a break. Every bot used to walk to the exact hangout tile
        // (loiterAt) and shuffle within 5 of it, so bots stood on one tile at the Draynor bank or the fountain. Now each break has
        // its own walkable tile 1-2 from the hangout (loiterSpot), shuffles stay within 3 of it, and a bot sharing a tile with
        // another steps off. Emotes come every 30-90 s with a 1 in 3 chance (was 1 in 4 of every think, about every 16 s), and
        // now and then the bot turns to face a bot nearby. Off with -Dbots.humanLook=false (the old break exactly).
        static final boolean HUMAN_LOOK = !"false".equalsIgnoreCase(System.getProperty("bots.humanLook", "true"));
        /** TEST ONLY (TestHumanLook): -Dtest.look.breakSoon=true, read only with -Dtest.humanlook=true, makes every trip end in a 3-5 min break,
         *  so the T1/E1 trial gets break bots inside its window (with traders.fast a 20 s break ended before the bot reached the hangout).
         *  Off by default; no effect in normal runs. */
        static final boolean BREAK_SOON = Boolean.getBoolean("test.humanlook") && Boolean.getBoolean("test.look.breakSoon");
        static final int[] IDLE_EMOTES = {855, 856, 857, 863, 866}; // yes, no, think, wave, dance
        static volatile int breakEmotes, breakFaces, unstacks; // for the trials (TestHumanLook); breakEmotes counts the old emotes too
        Location loiterSpot; long nextEmote;

        /** Where this break's bot stands: its own spot when it belongs to the current hangout, else the hangout tile. */
        Location spot() { return HUMAN_LOOK && loiterSpot != null && loiterAt != null && loiterSpot.getZ() == loiterAt.getZ() && loiterSpot.getDistance(loiterAt) <= 3 ? loiterSpot : loiterAt; }

        static boolean walkable(int z, int x, int y) {
            int f; try { f = RegionManager.getClippingFlag(z, x, y); } catch (Throwable t) { return false; }
            return f != -1 && (f & 0x200000) == 0 && (f & 0x100) == 0; // the same test as the bench and altar picks
        }

        /** Other active players (bots or not) standing on this exact tile. */
        int othersOn(Location l) {
            int n = 0;
            try { for (Player p : RegionManager.getLocalPlayers(l, 1)) if (p != null && p != bot && p.isActive() && p.getLocation().getX() == l.getX() && p.getLocation().getY() == l.getY() && p.getLocation().getZ() == l.getZ()) n++; } catch (Throwable ignored) { }
            return n;
        }

        /** A walkable, free tile 1-2 from the hangout (same floor); the hangout tile itself if none turns up. */
        Location spreadTile(Location c) {
            if (c == null) return null;
            for (int t = 0; t < 16; t++) {
                int dx = RNG.nextInt(5) - 2, dy = RNG.nextInt(5) - 2;
                if (dx == 0 && dy == 0) continue;
                Location l = Location.create(c.getX() + dx, c.getY() + dy, c.getZ());
                if (walkable(l.getZ(), l.getX(), l.getY()) && othersOn(l) == 0) return l;
            }
            return c;
        }

        /** Standing on someone's tile at the hangout: step to a free one. True when it moved (that think is used up). */
        boolean unstack() {
            if (bot.getWalkingQueue().isMoving() || othersOn(bot.getLocation()) == 0) return false;
            Location to = spreadTile(loiterAt);
            if (to == null || to == loiterAt && othersOn(loiterAt) > 0) return false;
            loiterSpot = to; unstacks++;
            try { ((AIPlayer) bot).walkToPosSmart(to); } catch (Throwable ignored) { return false; }
            return true;
        }

        /** One emote; 1 in 4 times it first turns to a bot within 4 tiles, like someone chatting at the bank. */
        void idleEmote() {
            int[] em = mood() == Mood.TIRED ? new int[]{855, 857, 2111} : mood() == Mood.CHATTY ? new int[]{863, 866, 861, 855} : mood() == Mood.GRUMPY ? new int[]{856, 857} : IDLE_EMOTES;
            if (RNG.nextInt(4) == 0) {
                try { Player near = null; double nd = 5; for (Player p : RegionManager.getLocalPlayers(bot, 4)) { if (p == null || p == bot || !p.isActive()) continue; double d = p.getLocation().getDistance(bot.getLocation()); if (d < nd && d > 0) { nd = d; near = p; } }
                    if (near != null) { bot.faceLocation(near.getLocation()); breakFaces++; } } catch (Throwable ignored) { }
            }
            try { bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(em[RNG.nextInt(em.length)])); breakEmotes++; } catch (Throwable ignored) { }
        }

        // rec-review 10-23 batch14 item2: a few extras on a break, ONLY while a real player is within 20 tiles
        // (Spectacle.watcher): pray at a nearby church altar (the animation; 2009 altars took no bones), stand about at
        // a bench, or light one log from the bank with a tinderbox (the engine's own FireMakingPulse, so real XP and a
        // real fire). Each lasts seconds; at most one a break and 5-10 min apart per bot, across breaks (batch 16 item4; was two a break, a minute apart). With nobody near this
        // returns false before touching RNG or any state, so the break is exactly the old one.
        static final int EXTRA_ALTAR = 1, EXTRA_BENCH = 2, EXTRA_FIRE = 3;
        static final int[][] FIRE_LOGS = {{1515, 60}, {1517, 45}, {1519, 30}, {1521, 15}, {1511, 1}}; // yew, maple, willow, oak, logs
        static volatile int loiterExtras; // started, for the trials
        int extraKind, extrasThisBreak, extraLog; Location extraAt, extraFace; long extraUntil, nextExtra, extraBreak; boolean extraArrived, extraTinder;
        // batch 16 item1: a glut of logs on the Exchange (MarketBrain.isSurplus) gets burned. At the GE a watched bot on a
        // break buys a few of the surplus logs from bot stock (Ledger.consume: paid, oldest lot first, never created) and
        // lights them one after another with the engine's FireMakingPulse. At most SURPLUS_PER_BREAK a break and
        // SURPLUS_PER_HOUR across the server; what it doesn't light goes to its bank.
        static final int SURPLUS_PER_BREAK = 4, SURPLUS_PER_HOUR = 40;
        static long surplusHour; static int surplusBurned; static volatile int surplusBought; // surplusBought: for the trials
        int extraPackBefore, extraLeft; boolean extraSurplus; long extraCool, extraRollBreak; boolean extraRollOk; // extraCool..: batch 16 item4
        long surplusBreakOf; // surplusBreakOf: the break (its idleUntil) that already had its surplus logs

        boolean loiterExtra(long now) {
            if (extraKind != 0) {
                if (now > extraUntil || now >= idleUntil) { endExtra(); return false; }
                return extraTick(now);
            }
            if (now < nextExtra || bot.getWalkingQueue().isMoving() || bot.getLocation().getDistance(loiterAt) > 8) return false;
            if (Spectacle.watcher(bot) == null) return false; // nobody to see it: the break as it always was
            if (extraBreak != idleUntil) { extraBreak = idleUntil; extrasThisBreak = 0; }
            if (extraRollBreak != idleUntil) { extraRollBreak = idleUntil; extraRollOk = RNG.nextInt(3) == 0; } // batch 16 item4: one 1-in-3 roll when a watcher first turns up this break (it used to roll every think: an extra within ~12 s)
            if (extrasThisBreak >= 1 || idleUntil - now < 20_000 || !extraRollOk) return false; // batch 16 item4: one extra a break (was two)
            if (extraCool == 0) extraCool = 300_000 + RNG.nextInt(300_001); // batch 16 item4: this bot's own 5-10 min between extras, chosen once
            nextExtra = now + extraCool; // across breaks too (it was 60-90 s, and only within a break)
            Location here = bot.getLocation(), altar = null, bench = null; double ad = 99, bd = 99;
            for (int dx = -24; dx <= 24; dx++) for (int dy = -24; dy <= 24; dy++) { // a church in sight of the spot
                core.game.node.scenery.Scenery o;
                try { o = RegionManager.getObject(here.getZ(), here.getX() + dx, here.getY() + dy); } catch (Throwable t) { continue; }
                if (o == null || o.getDefinition() == null || o.getDefinition().getName() == null) continue;
                String n = o.getDefinition().getName().toLowerCase(Locale.ROOT); double d = o.getLocation().getDistance(here);
                if (n.equals("altar") && prayable(o) && d < ad) { ad = d; altar = o.getLocation(); }
                else if (n.contains("bench") && d <= 10 && d < bd) { bd = d; bench = o.getLocation(); }
            }
            List<Integer> can = new ArrayList<>();
            if (altar != null) can.add(EXTRA_ALTAR);
            if (bench != null) can.add(EXTRA_BENCH);
            if (fireLog() != 0 || surplusLog(now) != 0) can.add(EXTRA_FIRE); // batch 16 item1: or a surplus log from the Exchange
            if (can.isEmpty()) return false;
            extraKind = can.get(RNG.nextInt(can.size())); extrasThisBreak++; loiterExtras++; extraArrived = false;
            extraFace = extraKind == EXTRA_ALTAR ? altar : extraKind == EXTRA_BENCH ? bench : null;
            extraAt = extraFace == null ? here : standBy(extraFace);
            if (extraAt == null) { extraKind = 0; return false; }
            extraUntil = Math.min(now + 40_000, idleUntil - 6_000); // over inside the break, so endExtra always runs
            if (Boolean.getBoolean("afk.debug") || Boolean.getBoolean("test.b14h2")) System.out.println("[SmartAdv] " + bot.getUsername() + " break extra " + (extraKind == EXTRA_ALTAR ? "altar at " + altar : extraKind == EXTRA_BENCH ? "bench at " + bench : "fire") + " (watched by " + Spectacle.watcher(bot).getUsername() + ")");
            return extraTick(now);
        }

        static boolean prayable(core.game.node.scenery.Scenery o) { String[] op = o.getDefinition().getOptions(); if (op != null) for (String x : op) if (x != null && x.toLowerCase(Locale.ROOT).startsWith("pray")) return true; return false; } // "Pray-at"

        /** The open tile next to an object (one or two out) nearest the bot, or null. */
        Location standBy(Location o) {
            Location best = null; double bd = 99;
            for (int r = 1; r <= 2 && best == null; r++) for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                int f; try { f = RegionManager.getClippingFlag(o.getZ(), o.getX() + dx, o.getY() + dy); } catch (Throwable t) { continue; }
                if (f == -1 || (f & 0x200000) != 0 || (f & 0x100) != 0) continue;
                Location c = Location.create(o.getX() + dx, o.getY() + dy, o.getZ()); double d = c.getDistance(bot.getLocation());
                if (d < bd) { bd = d; best = c; }
            }
            return best;
        }

        /** A log it can burn: in the bank, with a tinderbox in the bank or the pack, and room for both. */
        int fireLog() {
            int fm = bot.getSkills().getStaticLevel(Skills.FIREMAKING);
            if (bot.getInventory().getAmount(590) <= 0 && bot.getBank().getAmount(590) <= 0 || bot.getInventory().freeSlots() < 2) return 0;
            try { if (bot.getZoneMonitor().isInZone("bank") || RegionManager.getObject(bot.getLocation()) != null) return 0; } catch (Throwable t) { return 0; } // the engine refuses there
            for (int[] l : FIRE_LOGS) if (fm >= l[1] && bot.getBank().getAmount(l[0]) > 0) return l[0];
            return 0;
        }

        /** batch 16 item1: a log in surplus on the Exchange it could buy and burn here, or 0. */
        int surplusLog(long now) {
            if (!nearGE(bot) || surplusBreakOf == idleUntil) return 0; // one batch a break
            if (now - surplusHour > 3_600_000L) { surplusHour = now; surplusBurned = 0; }
            if (surplusBurned >= SURPLUS_PER_HOUR) return 0;
            int fm = bot.getSkills().getStaticLevel(Skills.FIREMAKING);
            if (bot.getInventory().getAmount(590) <= 0 && bot.getBank().getAmount(590) <= 0 || bot.getInventory().freeSlots() < 3) return 0;
            try { if (bot.getZoneMonitor().isInZone("bank") || RegionManager.getObject(bot.getLocation()) != null) return 0; } catch (Throwable t) { return 0; } // the engine refuses there
            for (int[] l : FIRE_LOGS) if (fm >= l[1] && MarketBrain.isSurplus(l[0]) && MarketBrain.stockOf(l[0]) > SURPLUS_PER_BREAK && Ledger.Wallet.coins(bot) >= 2L * Math.max(1, MarketBrain.value(l[0]))) return l[0];
            return 0;
        }

        /** batch 16 item1: the next log of the batch, while the pack still has one; false when the fires are done. */
        boolean nextFire() {
            if (bot.getPulseManager().hasPulseRunning()) return true; // still lighting (or stepping off) the last one
            if (extraLeft <= 0 || bot.getInventory().getAmount(extraLog) <= extraPackBefore) return false;
            Item logItem = null; for (int i = 0; i < bot.getInventory().capacity() && logItem == null; i++) { Item it = bot.getInventory().get(i); if (it != null && it.getId() == extraLog) logItem = it; }
            if (logItem == null) return false;
            try { if (bot.getZoneMonitor().isInZone("bank") || RegionManager.getObject(bot.getLocation()) != null) return false; } catch (Throwable t) { return false; } // stepped onto something: the rest go to the bank
            extraLeft--;
            try { bot.getPulseManager().run(new content.global.skill.firemaking.FireMakingPulse(bot, logItem, null)); } catch (Throwable t) { return false; }
            return true;
        }

        boolean extraTick(long now) {
            if (extraKind == EXTRA_FIRE) {
                if (!extraArrived) {
                    extraArrived = true; int sl = surplusLog(now); extraSurplus = sl != 0; extraLog = extraSurplus ? sl : fireLog(); // batch 16 item1: the Exchange's glut first
                    if (extraLog == 0) { endExtra(); return false; }
                    extraPackBefore = bot.getInventory().getAmount(extraLog);
                    extraTinder = bot.getInventory().getAmount(590) <= 0;
                    if (extraTinder && !move(bot.getBank(), bot.getInventory(), 590, 1)) { extraTinder = false; endExtra(); return false; } // batch 16 item5: never lost between the two
                    if (extraSurplus) { // batch 16 item1: bought from bot stock, paid; a few, lit one after another
                        int want = Math.min(2 + RNG.nextInt(SURPLUS_PER_BREAK - 1), Math.min(bot.getInventory().freeSlots() - 1, SURPLUS_PER_HOUR - surplusBurned));
                        int took = want <= 0 ? 0 : Ledger.consume(bot, extraLog, want, "firemaking");
                        if (took > 0 && !bot.getInventory().add(new Item(extraLog, took)) && !bot.getBank().add(new Item(extraLog, took))) core.game.node.item.GroundItemManager.create(new Item(extraLog, took), bot.getLocation(), bot); // paid for either way; batch 16 item5: never lost
                        if (took <= 0 || bot.getInventory().getAmount(extraLog) <= extraPackBefore) { endExtra(); return false; }
                        surplusBurned += took; surplusBought += took; extraLeft = took - 1; surplusBreakOf = idleUntil;
                        extraUntil = Math.min(idleUntil - 6_000, now + 15_000L * (took + 1));
                        if (Boolean.getBoolean("afk.debug") || Boolean.getBoolean("test.b16a")) System.out.println("[SmartAdv] " + bot.getUsername() + " bought " + took + " surplus " + MarketBrain.name(extraLog) + " to burn (stock now " + MarketBrain.stockOf(extraLog) + ", " + surplusBurned + " this hour)");
                    } else if (!move(bot.getBank(), bot.getInventory(), extraLog, 1)) { endExtra(); return false; } // batch 16 item5
                    Item logItem = null; for (int i = 0; i < bot.getInventory().capacity() && logItem == null; i++) { Item it = bot.getInventory().get(i); if (it != null && it.getId() == extraLog) logItem = it; } // the pack's own item: the pulse replaces its slot
                    if (logItem == null) { endExtra(); return false; }
                    try { bot.getPulseManager().run(new content.global.skill.firemaking.FireMakingPulse(bot, logItem, null)); } catch (Throwable t) { if (Boolean.getBoolean("test.b14h2")) t.printStackTrace(System.out); endExtra(); return false; }
                    if (Boolean.getBoolean("test.b14h2")) System.out.println("[SmartAdv] " + bot.getUsername() + " fire pulse started: running " + bot.getPulseManager().hasPulseRunning() + ", pack logs " + bot.getInventory().getAmount(extraLog) + ", at " + bot.getLocation());
                    if (extraSurplus) { if (RNG.nextInt(2) == 0) say(BotChat.pick("Logs are going for nothing on the Exchange. Might as well burn some.", "Market's drowning in logs. Fire, anyone?", "Cheap logs, warm hands.")); } // batch 16 item1
                    else if (RNG.nextInt(3) == 0) say(BotChat.pick("Bit nippy. Fire.", "Might as well get some firemaking in.", "Gather round, it's a fire."));
                    if (!extraSurplus) extraUntil = Math.min(extraUntil, now + 20_000);
                    return true;
                }
                if (extraSurplus && !nextFire()) { endExtra(); return false; } // batch 16 item1: the next log, or done
                return true; // the pulse lights it; the bot stands by it
            }
            if (!extraArrived) {
                if (extraAt == null || bot.getLocation().getDistance(extraAt) > 2) {
                    if (!bot.getWalkingQueue().isMoving()) try { ((AIPlayer) bot).walkToPosSmart(extraAt); } catch (Throwable t) { endExtra(); return false; }
                    return true;
                }
                extraArrived = true;
                try { bot.faceLocation(extraFace); } catch (Throwable ignored) { }
                Spectacle.anim(bot, extraKind == EXTRA_ALTAR ? 645 : RNG.nextBoolean() ? 857 : 2111); // pray at the altar; think or yawn by the bench
                if (Boolean.getBoolean("afk.debug") || Boolean.getBoolean("test.b14h2")) System.out.println("[SmartAdv] " + bot.getUsername() + " break extra " + (extraKind == EXTRA_ALTAR ? "prays at the altar" : "stands by the bench") + " at " + bot.getLocation());
                extraUntil = Math.min(extraUntil, now + (extraKind == EXTRA_ALTAR ? 8_000 : 15_000 + RNG.nextInt(10_000)));
                if (extraKind == EXTRA_ALTAR && RNG.nextInt(3) == 0) say(BotChat.pick("A quick word with Saradomin.", "Never hurts to pray.", "Bless this haul."));
            }
            return true;
        }

        /** batch 16 item5: n of an item from one container to the other, or nothing at all (the item goes back if the add is refused). */
        static boolean move(core.game.container.Container from, core.game.container.Container to, int id, int n) {
            if (n <= 0 || from.getAmount(id) < n || !to.hasSpaceFor(new Item(id, n))) return false;
            if (!from.remove(new Item(id, n))) return false;
            if (to.add(new Item(id, n))) return true;
            if (!from.add(new Item(id, n))) System.out.println("[SmartAdv] move: " + n + " x " + id + " refused by both containers"); // cannot happen: its own room was just freed
            return false;
        }

        void endExtra() {
            if (extraKind == EXTRA_FIRE && extraLog != 0 && Boolean.getBoolean("test.b14h2")) System.out.println("[SmartAdv] " + bot.getUsername() + " fire extra over: pack logs " + bot.getInventory().getAmount(extraLog) + ", running " + bot.getPulseManager().hasPulseRunning() + ", FM xp " + (int) bot.getSkills().getExperience(Skills.FIREMAKING));
            if (extraKind == EXTRA_FIRE && extraLog != 0) { // what the pulse didn't use goes back to the bank
                int spare = Math.max(0, Math.min(extraSurplus ? SURPLUS_PER_BREAK : 1, bot.getInventory().getAmount(extraLog) - extraPackBefore)); // batch 16 item1: every unlit one
                if (spare > 0) move(bot.getInventory(), bot.getBank(), extraLog, spare); // batch 16 item5: a full bank leaves them in the pack, never nowhere
                if (spare > 0) try { if (bot.getBank().freeSlots() == 0) Failures.bankFull(bot, "break-extra", extraLog); } catch (Throwable ignored) { } // batch 19c §4.5
                if (extraTinder) move(bot.getInventory(), bot.getBank(), 590, 1); // batch 16 item5
            }
            extraKind = 0; extraLog = 0; extraTinder = false; extraAt = null; extraFace = null; extraSurplus = false; extraLeft = 0; extraPackBefore = 0;
        }

        String idleLine() {
            switch (mood()) {
                case CHATTY: return BotChat.pick("Good haul today. Drinks are on me. Figuratively.", "Anyone else having a decent day? Mine's been great.", "Bank's looking healthier than it has in weeks.", "What a day. Everything's going right.", "If anyone needs a hand, I'm in a giving mood.", "Feeling lucky today. Don't jinx it.", "Did you see that haul? No? Shame.");
                case TIRED: return BotChat.pick("One more trip and I'm done.", "Long day. Feet hurt, if feet can hurt here.", "Might call it soon.", "Eyes are closing on their own.", "If I sit down I won't get up.", "My bed is calling. Loudly.", "Nearly done. Nearly.");
                case GRUMPY: return BotChat.pick("Don't talk to me about hill giants.", "Still annoyed about earlier.", "Lost good gear today. Not in the mood.", "Not today.", "Everything's gone wrong since breakfast.", "If one more goblin looks at me...", "I'd like a quiet five minutes. Thanks.");
                default:
                    String[] st = STORY.get(bot.getUsername().toLowerCase(Locale.ROOT));
                    if (st != null && RNG.nextInt(4) == 0) return RNG.nextBoolean() ? st[0] : "Don't talk to me about " + st[1] + ".";
                    if (goal > 0) return String.format(BotChat.pick("Saving for a %s. %s to go.", "One day I'll have a %s. About %s short.", "Every trip is another step towards a %s. %s left.", "The %s fund needs %s more.", "%2$s away from a %1$s. So close.", "Dreaming about that %s. %s short."), MarketBrain.name(goal).toLowerCase(Locale.ROOT), String.format("%,d", Math.max(0, MarketBrain.value(goal) - Ledger.Wallet.coins(bot))) + " gp");
                    return BotChat.pick("Quiet at the Exchange today.", "Prices are all over the place lately.", "Anyone selling a decent pickaxe?", "Just checking my offers.", "Anyone know if coal's up?", "Thinking about my next trip.", "Feet up for a minute.", "Watching the world go by.", "These prices make no sense.", "Who keeps buying all the lobsters?", "Should've bought rune when it was cheap.", "I could do with a holiday.");
            }
        }

        /**
         * D24: a face-to-face deal. Another idle bot standing nearby happens to be the one whose lot is next in the queue
         * for something this bot wants (food for the next trip, or its goal item): the buyer takes it off the ledger,
         * the seller gets paid through the usual FIFO, and the two of them haggle out loud so it reads as a real trade.
         */
        long nextSpectacle;
        long partyUntil; Location partyAt; boolean partyArrived; // batch 16 item3

        /** The nearest human within range, or null. Spectacle only happens when someone is there to see it. */
        Player audience(int range) {
            Player best = null; double bd = range + 1;
            for (Player h : Repository.getPlayers()) {
                if (h == null || !Companions.human(h) || !h.isActive() || h.getLocation().getZ() != bot.getLocation().getZ()) continue; // batch 28: a trial stand-in counts
                double d = h.getLocation().getDistance(bot.getLocation());
                if (d < bd) { bd = d; best = h; }
            }
            return best;
        }

        /**
         * D25 (light): two idle bots put on a show when a player is watching: a wager settled with real coins, an
         * arm-wrestle, a dance-off or a boast about the day's haul, with emotes, lines a few ticks apart and a loser
         * who pays up. Nothing is created: the stake moves from one wallet to the other.
         */
        boolean spectacle(long now) {
            if (now < nextSpectacle) return false;
            Player crowd = audience(10);
            if (crowd == null) return false;
            if (Scenes.Show.roll(bot.getLocation(), now) < 0) return false; // batch 28: this hub had its show in the last 30 min
            Brain other = null;
            for (Brain b : BRAINS.values()) {
                if (b == this || b.bot == null || !b.bot.isActive() || b.runner != null || b.bot.getLocation().getDistance(bot.getLocation()) > 6 || now < b.nextSpectacle) continue;
                other = b; break;
            }
            if (other == null) return false;
            nextSpectacle = now + 15 * 60_000; other.nextSpectacle = now + 15 * 60_000;
            Scenes.Show.started(bot.getLocation(), now); // batch 28
            final Player a = bot, b = other.bot;
            long stake = Math.min(Math.min(Ledger.Wallet.coins(a), Ledger.Wallet.coins(b)) / 10, 2_000L);
            final long bet = Math.max(0, stake - stake % 100);
            stake = bet;
            int show = RNG.nextInt(3);
            boolean iWin = RNG.nextBoolean();
            final Player winner = iWin ? a : b, loser = iWin ? b : a;
            List<String> mine = new ArrayList<>(), theirs = new ArrayList<>();
            int anim;
            switch (show) {
                case 0: // arm wrestle
                    mine.add(String.format(BotChat.pick("%s. Arm wrestle. %s on it.", "Right %s, best of one. %s says you lose."), b.getUsername(), stake > 0 ? String.format("%,d", stake) + " gp" : "Pride"));
                    theirs.add(BotChat.pick("You're on.", "Easy money.", "Bring it."));
                    anim = 2113; // flex-ish (think)
                    break;
                case 1: // dance-off
                    mine.add(String.format(BotChat.pick("Dance-off, %s. The crowd decides.", "%s! Dance-off. Loser buys the lobsters."), b.getUsername()));
                    theirs.add(BotChat.pick("Oh it's on.", "You'll regret this.", "Watch and learn."));
                    anim = 866; // dance
                    break;
                default: // boast
                    mine.add(String.format(BotChat.pick("%s, bet you I banked more than you today.", "Reckon my haul beat yours, %s. %s on it."), b.getUsername(), stake > 0 ? String.format("%,d", stake) + " gp" : "A trout"));
                    theirs.add(BotChat.pick("Not a chance.", "Go on then, show us.", "You wish."));
                    anim = 862; // cheer
            }
            try { a.getAnimator().animate(new core.game.world.update.flag.context.Animation(anim)); } catch (Throwable ignored) { }
            BotChat.speak(a, b, mine, 1);
            BotChat.speak(b, a, theirs, 4);
            GameWorld.getPulser().submit(new core.game.system.task.Pulse(7) {
                @Override
                public boolean pulse() {
                    try {
                        try { b.getAnimator().animate(new core.game.world.update.flag.context.Animation(anim)); } catch (Throwable ignored) { }
                        if (!winner.isActive() || !loser.isActive()) return true;
                        boolean paid = bet > 0 && Ledger.Wallet.pay(loser, bet);
                        if (paid) Ledger.Wallet.give(winner, bet);
                        try { Failures.econNote(winner, Failures.SPECTACLE); Failures.econNote(loser, Failures.SPECTACLE); } catch (Throwable ignored) { } // batch 19c §4.1
                        String st = String.format("%,d", bet) + " gp";
                        BotChat.speak(winner, loser, java.util.Arrays.asList(BotChat.pick("Ha! Pay up.", "Told you. " + (paid ? st + ", cheers." : "Pride intact."), "And THAT is how it's done.")), 1);
                        BotChat.speak(loser, winner, java.util.Arrays.asList(BotChat.pick("Best of three?", paid ? "Fine. " + st + ". Daylight robbery." : "Fine, you win.", "The crowd was biased.")), 4);
                        try { winner.getAnimator().animate(new core.game.world.update.flag.context.Animation(862)); } catch (Throwable ignored) { }
                        if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] spectacle: " + a.getUsername() + " vs " + b.getUsername() + " for " + crowd.getUsername() + ", " + winner.getUsername() + " won " + (paid ? st : "nothing"));
                    } catch (Throwable ignored) { }
                    return true;
                }
            });
            return true;
        }

        boolean directDeal() {
            if (bot.getLocation().getDistance(GRAND_EXCHANGE) > 25) return false;
            List<int[]> wantList = new ArrayList<>();
            if (goal > 0) wantList.add(new int[]{goal, 1});
            try { for (int[] pc : armourNeeds()) wantList.add(new int[]{pc[0], 1}); } catch (Throwable ignored) { }
            for (int f : new int[]{379, 373, 333, 329, 361}) wantList.add(new int[]{f, 10});
            Brain other = null; int dealId = 0, dealN = 0;
            for (Brain b : BRAINS.values()) {
                if (b == this || b.bot == null || !b.bot.isActive() || b.runner != null || b.bot.getLocation().getDistance(bot.getLocation()) > 6) continue;
                for (int[] w : wantList) {
                    int lot = Ledger.waiting(b.bot.getUsername(), w[0]); // any lot of it in the ledger, not only the head of the queue
                    if (lot <= 0) continue;
                    other = b; dealId = w[0]; dealN = Math.min(w[1], lot); break;
                }
                if (other != null) break;
            }
            if (other == null) return false;
            String seller = other.bot.getUsername();
            for (int id : new int[]{dealId}) {
                int n = dealN;
                int price = MarketBrain.value(id);
                if (Ledger.Wallet.coins(bot) < (long) price * n + 500 || MarketBrain.stockOf(id) < n) continue;
                int got = Ledger.consumeFrom(bot, seller, id, n);
                if (got <= 0) continue;
                String nm = MarketBrain.name(id).toLowerCase(Locale.ROOT);
                if (id == goal) { wear(bot, id); try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } goal = pickGoal(bot); GOAL.put(bot.getUsername().toLowerCase(Locale.ROOT), goal); }
                else if (n == 1) { if (wearKit(id)) { try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } } } // an armour piece it was missing; if it can't be worn, wear() has put it in the pack or bank (audit 10-23 S-1) rec-review 10-23 batch14 item1: wearKit
                else bot.getBank().add(new Item(id, got));
                String ask = String.format(BotChat.pick("%s, still got that %s? I'll take %d.", "Oi %s. %s. How much for %d?", "%1$s, do us a deal on %3$d %2$s."), seller, nm, got); // positional: the plain %d got the item name (audit §11 format exception)
                if (ask.contains("%")) ask = seller + ", how much for " + got + " " + nm + "?";
                say(ask);
                final String total = String.format("%,d", (long) price * got);
                BotChat.speak(other.bot, bot, java.util.Arrays.asList(String.format(BotChat.pick("Guide price, %s gp the lot. Mates' rates.", "%s and not a coin less.", "Call it %s. Don't tell the clerks."), total)), 3);
                BotChat.speak(bot, other.bot, java.util.Arrays.asList(BotChat.pick("Done.", "Deal. Pleasure doing business.", "Robbery, but fine.")), 6);
                System.out.println("[SmartAdv] deal: " + bot.getUsername() + " bought " + got + " x " + nm + " from " + seller + " at the Exchange for " + total + " gp");
                return true;
            }
            return false;
        }

        /**
         * batch 15 item5: a real 99 (static level, from xp) and 99,000 gp: the cape of accomplishment and its hood, as the
         * skill master sells them (Skillcape.getItems: trimmed once it has two 99s, Skillcape.trim then trims the older
         * ones, as LevelUp does for a player). Paid through the Wallet (coins leave the game: a sink). The cape goes on if the
         * cape slot is free, the hood to the bank. The engine skips the 99 news for bots, so it is posted here. One cape at
         * a time server-wide (a few minutes apart) so a restart full of old 99s is no news flood.
         */
        static volatile long nextCape;
        boolean checkCape() {
            if (bot == null || !bot.isActive()) return false;
            long now = System.currentTimeMillis();
            if (now < nextCape) return false;
            for (int sk = 0; sk < core.game.global.Skillcape.SKILLCAPES.length && sk < 24; sk++) {
                if (!core.game.global.Skillcape.isMaster(bot, sk)) continue;
                int base = core.game.global.Skillcape.SKILLCAPES[sk];
                if (owns(base) || owns(base + 1)) continue; // has this cape already, trimmed or not
                if (Ledger.Wallet.coins(bot) < 99_000) return false;
                QuestRunner.makeRoom(bot, 2);
                if (bot.getInventory().freeSlots() < 2) return false;
                if (!Ledger.Wallet.pay(bot, 99_000)) return false;
                nextCape = now + (FAST ? 30_000 : 5 * 60_000L);
                for (Item it : core.game.global.Skillcape.getItems(bot, sk)) if (!bot.getInventory().add(it)) bot.getBank().add(it);
                if (bot.getSkills().getMasteredSkills() > 1) try { core.game.global.Skillcape.trim(bot); } catch (Throwable ignored) { } // the older capes too
                try { bot.getEmoteManager().unlock(core.game.node.entity.player.link.emote.Emotes.SKILLCAPE); } catch (Throwable ignored) { }
                int cape = bot.getInventory().getAmount(base + 1) > 0 ? base + 1 : base;
                boolean worn = false;
                if (bot.getEquipment().get(1) == null && bot.getInventory().getAmount(cape) > 0) worn = wear(bot, cape);
                if (bot.getInventory().getAmount(base + 2) > 0 && bot.getInventory().remove(new Item(base + 2)) && !bot.getBank().add(new Item(base + 2))) bot.getInventory().add(new Item(base + 2)); // the hood: banked
                String skn = Skills.SKILL_NAME[sk];
                String line = bot.getUsername() + " has just achieved level 99 " + skn + "!";
                try { Repository.sendNews(line, 12, "CC6600"); } catch (Throwable ignored) { }
                BotChat.news(String.format(BotChat.pick("%1$s got 99 %2$s. Bought the cape the same day.", "Seen %1$s's new cape? 99 %2$s. Won't shut up about it.", "%1$s is a master of %2$s now. Cape and everything."), bot.getUsername(), skn));
                say(BotChat.pick("99 " + skn + "! Cape's mine.", "Finally. 99 " + skn + ".", "Got the cape! 99 " + skn + ".", "99! " + skn + " done."));
                System.out.println("[SmartAdv] " + bot.getUsername() + " bought the " + MarketBrain.name(cape) + " for 99,000 gp (" + (worn ? "worn" : "kept, cape slot taken") + "); news: " + line);
                return true;
            }
            return false;
        }
        boolean owns(int id) { try { return bot.getInventory().getAmount(id) > 0 || bot.getBank().getAmount(id) > 0 || bot.getEquipment().getAmount(id) > 0; } catch (Throwable t) { return false; } }

        /** B16: buys the goal item the day it can; picks a new one afterwards. */
        void checkGoal() {
            String key = bot.getUsername().toLowerCase(Locale.ROOT);
            if (goal <= 0 || !worthHaving(goal) || !obtainable(bot, goal)) { goal = pickGoal(bot); GOAL.put(key, goal); return; }
            int price = MarketBrain.value(goal);
            if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] goal check " + bot.getUsername() + ": " + MarketBrain.name(goal) + " price " + price + " coins " + Ledger.Wallet.coins(bot) + " stock " + MarketBrain.stockOf(goal) + " shop " + (goalShop(goal) != null));
            if (Ledger.Wallet.coins(bot) < price + 1_000) return;
            if (bot.getInventory().getAmount(goal) > 0) { wear(bot, goal); goal = pickGoal(bot); GOAL.put(key, goal); return; }
            if (MarketBrain.stockOf(goal) <= 0) {
                // None on the Exchange: walk to the specialty shop that sells it (the shop trip machinery), buy it there.
                Object[] shop = goalShop(goal);
                if (shop == null) return;
                if (!Preflight.ok(bot, Location.create((Integer) shop[1], (Integer) shop[2], 0))) { goal = pickGoal(bot); GOAL.put(key, goal); return; } // batch 20 A1
                shopAt = Location.create((Integer) shop[1], (Integer) shop[2], 0); shopName = (String) shop[0];
                shopBuy.clear(); shopBuy.add(new int[]{goal, 1}); pendingJob = null; goalTrip = true;
                shopDeadline = System.currentTimeMillis() + (FAST ? 240_000 : 15 * 60_000);
                shopBest = Double.MAX_VALUE; shopStalls = 0;
                final String gn = MarketBrain.name(goal).toLowerCase(Locale.ROOT), sn = shopName;
                say(String.format(BotChat.pick("Saved up at last. Off to %2$s for that %1$s.", "Right, %2$s. Time to buy the %1$s.", "Enough coin for a %1$s. %2$s, here I come.", "%2$s, here I come. The %1$s is mine today.", "Enough saved for the %1$s. Off to %2$s."), gn, sn));
                BotChat.activity(bot, () -> "off to " + sn + " to buy a " + gn);
                System.out.println("[SmartAdv] " + bot.getUsername() + " heading to " + sn + " to buy its goal: " + gn);
                return;
            }
            if (!nearGE(bot)) return; // bought next time it's at the Exchange (on a break there)
            if (Ledger.consume(bot, goal, 1, "goal") != 1) return;
            String nm = MarketBrain.name(goal);
            wear(bot, goal); try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
            say(String.format(BotChat.pick("Finally! Got my %s.", "Bought the %s. Months of fishing, that.", "Look at this. %s. Worth every coin.", "Look at it. My very own %s.", "The %s is mine! Took long enough.", "Worth every trip. One %s."), nm.toLowerCase(Locale.ROOT)));
            BotChat.news(String.format(BotChat.pick("%s finally bought that %s they kept going on about.", "Saw %s at the bank with a brand new %s. Chuffed to bits.", "%s saved up and got a %s. Took them long enough."), bot.getUsername(), nm.toLowerCase(Locale.ROOT)));
            System.out.println("[SmartAdv] " + bot.getUsername() + " bought their goal: " + nm + " for " + price + " gp");
            feel(Mood.CHATTY, 15 * 60_000);
            goal = pickGoal(bot); GOAL.put(key, goal);
        }

        boolean worthHaving(int id) {
            try { for (Item it : bot.getEquipment().toArray()) if (it != null && it.getId() == id) return false; } catch (Throwable ignored) { }
            return true;
        }

        /** B15: critically broke means can't afford a tool or a few meals; then money comes first and the bot says so. */
        boolean broke() { return Ledger.Wallet.coins(bot) < 400; }

        /** B17: food for a fight or a thieving trip is bought, not conjured: out of bot stock first (paying the supplier), else from a shop. */
        /** Food that heals 7+ (trout, salmon, tuna, lobster, swordfish, monkfish, shark): what a real fight needs. */
        static final int[] GOOD_FOOD = {385, 7946, 373, 379, 361, 329, 333}; // best healers first (combat review 10-23 #7: packFood took trout before the sharks)
        boolean goodFoodOnly; // set per trip: hunts of level 25+ or a bot of combat 50+ won't count bread and shrimps

        int foodHeld() {
            int have = 0;
            try { for (int id : goodFoodOnly ? GOOD_FOOD : AfkTasks.Runner.FOOD) have += bot.getBank().getAmount(id) + bot.getInventory().getAmount(id); } catch (Throwable ignored) { }
            return have;
        }

        /**
         * Batch 22-1 (audit B22-4): food held (pack + bank) as a trip to this hunt would count it (good food only for level 25+).
         * A Morytania hunt is not picked below MORY_FOOD_MIN: a bot with no food walked in, tabbed out at half health (4 emergency
         * teleports), paid 3 more to leave Morytania, bought bread and went straight back: 13 paid actions in an hour.
         */
        int foodFor(HuntMap.Hunt h) {
            boolean saved = goodFoodOnly; goodFoodOnly = h.npcLevel >= 25;
            try { return foodHeld(); } finally { goodFoodOnly = saved; }
        }
        static final int MORY_FOOD_MIN = Math.max(0, Integer.getInteger("mory.foodMin", 8)); // -Dmory.foodMin=0: off

        /** Audit B-11: an item paid for goes in the pack, or the bank when the pack is full (it used to vanish). */
        static void packOrBank(Player p, Item it) { if (!p.getInventory().add(it) && !p.getBank().add(it)) try { Failures.Econ.itemLost(p, it.getId(), it.getAmount(), "packOrBank", "-"); } catch (Throwable ignored) { } } // batch 19c §4.2: the same two adds; the second failing is recorded

        void buyFood() { buyFood(20); }

        /**
         * Audit B-2: buyFood puts the food in the bank and nothing packed it, so fighters left with an empty pack and
         * fled "no food" at half health. Moves up to 12 (what the runner's restock keeps room for) into the pack.
         */
        void packFood() {
            int have = 0;
            for (int f : AfkTasks.Runner.FOOD) have += bot.getInventory().getAmount(f);
            int room = Math.min(12, bot.getInventory().freeSlots() - 6) - have; // leave room for the haul
            for (int f : goodFoodOnly ? GOOD_FOOD : AfkTasks.Runner.FOOD) {
                if (room <= 0) break;
                int n = Math.min(room, bot.getBank().getAmount(f));
                if (n <= 0 || !bot.getBank().remove(new Item(f, n))) continue;
                for (int i = 0; i < n; i++) if (!bot.getInventory().add(new Item(f))) { bot.getBank().add(new Item(f, n - i)); n = i; break; }
                room -= n;
            }
        }
        /** Enough for the target: 10 for small things, up to 20 for demons and giants (audit B9: 129 emergency teleports a day). */
        void buyFood(int npcLevel) {
            int have = foodHeld();
            int carry = Math.min(20, Math.max(10, npcLevel / 8));
            if (have >= carry - 2) return;
            int want = carry - have;
            // combat review 10-23 #7: the first affordable entry was always trout (heals 7), even for black demons. Now the
            // smallest food that heals what this fight needs, if the trip's food costs no more than a quarter of the purse;
            // else the best that does; else (a poor bot) the cheapest it can pay for, as before.
            int needHeal = healWanted(npcLevel);
            long purse = Ledger.Wallet.coins(bot);
            if (needHeal >= 18 && nearGE(bot) && MarketBrain.stockOf(SHARK) > want + 50 && (long) Math.max(1, MarketBrain.value(SHARK)) * want <= purse / 4) {
                int got = Ledger.consume(bot, SHARK, want, "food");
                if (got > 0) { bot.getBank().add(new Item(SHARK, got)); if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + bot.getUsername() + " bought " + got + " x " + MarketBrain.name(SHARK) + " for the trip"); return; }
            }
            if (W8Polish.FOOD_LADDER && nearGE(bot) && w8FoodLadder(npcLevel, want, purse)) return; // W8 (-Dbots.foodLadder): lobster/swordfish/monkfish/shark by purse and Hitpoints, bot stock only, under a price ceiling
            int[] order = new int[SHOP_FOOD.length]; int n = 0;
            for (int[] f : SHOP_FOOD) if (f[1] >= needHeal && (long) Math.max(1, MarketBrain.value(f[0])) * want <= purse / 4) { order[n++] = f[0]; break; }
            for (int k = SHOP_FOOD.length - 1; k >= 0; k--) if (SHOP_FOOD[k][1] < needHeal && (long) Math.max(1, MarketBrain.value(SHOP_FOOD[k][0])) * want <= purse / 4) { order[n++] = SHOP_FOOD[k][0]; break; }
            for (int[] f : SHOP_FOOD) { boolean dup = false; for (int k = 0; k < n; k++) if (order[k] == f[0]) dup = true; if (!dup) order[n++] = f[0]; }
            for (int id : order) {
                int price = Math.max(1, MarketBrain.value(id));
                if (Ledger.Wallet.coins(bot) < (long) price * want) continue;
                int got = nearGE(bot) && MarketBrain.stockOf(id) > want + 50 ? Ledger.consume(bot, id, want, "food") : 0;
                if (got <= 0 && Ledger.Wallet.pay(bot, (long) price * want)) got = want; // the shop
                if (got > 0) { bot.getBank().add(new Item(id, got)); if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + bot.getUsername() + " bought " + got + " x " + MarketBrain.name(id) + " for the trip"); return; }
            }
            if (W3Skills.CHEAP_FOOD && npcLevel <= W3Skills.CHEAP_MAX_LEVEL) { // W3 fix 6 (-Dbots.fight.cheapFood): the full carry was too dear: as many of the cheapest shop food as the purse less 20 gp pays for (coins leave the game)
                int cid = SHOP_FOOD[0][0], cp = Integer.MAX_VALUE; for (int[] f : SHOP_FOOD) { int v = Math.max(1, MarketBrain.value(f[0])); if (v < cp) { cp = v; cid = f[0]; } }
                int nb = W3Skills.cheapQty(want, P87Fight.foodNeed(npcLevel, effectiveCombat()) - 2 - have, Ledger.Wallet.coins(bot), cp);
                if (nb > 0 && Ledger.Wallet.pay(bot, (long) cp * nb)) { bot.getBank().add(new Item(cid, nb)); W3Skills.cheapBought += nb; System.out.println("[Fight] (W3) " + bot.getUsername() + " bought " + nb + " x " + MarketBrain.name(cid) + " on a thin purse (" + Ledger.Wallet.coins(bot) + " gp left)"); }
            }
        }

        /** W8 (-Dbots.wc.power / -Dbots.fish.power): every product of the task in glut. */
        static boolean w8Glut(AfkTasks.Task t) { if (t.products == null || t.products.length == 0) return false; for (int id : t.products) if (!MarketBrain.isSurplus(id)) return false; return true; }
        /** W8: the power kind this bot runs this task in (KIND_WC willows, KIND_FISH fly fishing), 0 = a normal trip. */
        static int w8PowerKind(Player p, AfkTasks.Task t) {
            int k = W8Polish.powerKind(W8Polish.WC_POWER, W8Polish.FISH_POWER, t == AfkTasks.Task.WILLOW, t == AfkTasks.Task.LURE);
            if (k == 0) return 0;
            int lvl = p.getSkills().getStaticLevel(k == W8Polish.KIND_WC ? Skills.WOODCUTTING : Skills.FISHING);
            return W8Polish.power(true, w8Glut(t), lvl, k == W8Polish.KIND_WC ? W8Polish.WC_POWER_MAX : W8Polish.FISH_POWER_MAX) ? k : 0;
        }

        /** W8 (-Dbots.foodLadder): the best rung this bot's Hitpoints and purse reach, out of bot stock (Ledger.consume pays the fishers and cooks). True = food bought. */
        boolean w8FoodLadder(int npcLevel, int want, long purse) {
            try {
                int L = W8Polish.LADDER.length; int[] price = new int[L], ceil = new int[L], stock = new int[L], why = new int[3];
                for (int k = 0; k < L; k++) { int id = W8Polish.LADDER[k][0]; price[k] = Math.max(1, MarketBrain.value(id)); ceil[k] = W8Polish.foodCeil(boneRef(id)); stock[k] = MarketBrain.stockOf(id); }
                int k = W8Polish.rung(bot.getSkills().getStaticLevel(Skills.HITPOINTS), npcLevel, purse, want, price, ceil, stock, why);
                if (k < 0) { if (npcLevel >= W8Polish.FOOD_MIN_NPC) { W8Polish.ladderCeil += why[0]; W8Polish.ladderNoStock += why[1]; W8Polish.ladderPoor += why[2]; } return false; }
                int id = W8Polish.LADDER[k][0];
                int got = Ledger.consume(bot, id, want, "food");
                if (got <= 0) { W8Polish.ladderNoStock++; return false; }
                bot.getBank().add(new Item(id, got));
                W8Polish.ladderBuys++; W8Polish.ladderFood += got; W8Polish.ladderRung[k]++;
                System.out.println("[W8] food " + bot.getUsername() + " bought " + got + " x " + MarketBrain.name(id) + " from bot stock (Hitpoints " + bot.getSkills().getStaticLevel(Skills.HITPOINTS) + ", npc " + npcLevel + ", " + price[k] + " gp each, ceiling " + ceil[k] + ")");
                return true;
            } catch (Throwable t) { System.out.println("[W8] food ladder for " + bot.getUsername() + ": " + t); return false; }
        }

        /** combat review 10-23 #9: four waterskins' worth in the pack for a desert job: from the bank first, empties refilled (a player fills them at the Shantay Pass well), the rest bought at the pass shop. Returns the skins carried. */
        int packWater() {
            int have = 0;
            for (int id : Gates.WATERSKINS) have += bot.getInventory().getAmount(id);
            for (int id : Gates.WATERSKINS) { int n = Math.min(4 - have, bot.getBank().getAmount(id)); if (n > 0 && bot.getBank().remove(new Item(id, n))) { if (!bot.getInventory().add(new Item(id, n))) { bot.getBank().add(new Item(id, n)); continue; } have += n; } }
            int empties = Math.min(4 - have, bot.getInventory().getAmount(Gates.WATERSKIN_EMPTY));
            if (empties > 0 && bot.getInventory().remove(new Item(Gates.WATERSKIN_EMPTY, empties))) { bot.getInventory().add(new Item(Gates.WATERSKINS[0], empties)); have += empties; }
            int buy = Math.min(4 - have, bot.getInventory().freeSlots());
            if (buy > 0 && Ledger.Wallet.pay(bot, (long) Math.max(1, MarketBrain.value(Gates.WATERSKINS[0])) * buy)) { bot.getInventory().add(new Item(Gates.WATERSKINS[0], buy)); have += buy; }
            if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + bot.getUsername() + " packs " + have + " waterskins for the desert");
            return have;
        }

        /** combat review 10-23 #7: hp one bite should give for this fight: bigger monsters hit harder, and a big health bar wants bigger bites. */
        int healWanted(int npcLevel) {
            int need = npcLevel >= 100 ? 14 : npcLevel >= 60 ? 12 : npcLevel >= 30 ? 9 : 7;
            int max = 10; try { max = bot.getSkills().getMaximumLifepoints(); } catch (Throwable ignored) { }
            return npcLevel >= 30 ? Math.max(need, Math.min(20, max / 7)) : need; // goblins never need swordfish
        }

        static double jobWeight(Job j) {
            double w = 0;
            if (j.hunt != null) {
                // Expected bounty per kill: what it drops, how often, how wanted.
                for (int k = 0; k < j.hunt.products.length; k++) {
                    int id = j.hunt.products[k];
                    if (id == 995 || MarketBrain.isSurplus(id)) continue;
                    w += MarketBrain.bounty(id) * j.hunt.chance[k];
                }
                return w * 1.5 * popularity(j.hunt.name); // a kill usually drops several wanted things at once
            }
            if (W5aSkills.SHARK && j.prodOv != null) return W5aSkills.fishWeight(j.prodOv, MarketBrain::bounty, MarketBrain::isSurplus); // W5a: a harpoon row weighs only the fish it can catch
            for (int id : j.task.products) if (id != 995 && !MarketBrain.isSurplus(id)) w = Math.max(w, MarketBrain.bounty(id));
            return w;
        }

        /**
         * Review C: what 2009 players actually camped. The classic money and Slayer monsters count three times as much as
         * obscure quest-area monsters, so high-level bots are seen at the dragons, demons and giants. (Green dragons are
         * Wilderness only, and bots never cross the ditch.)
         */
        static final java.util.Set<String> POPULAR = new java.util.HashSet<>(java.util.Arrays.asList(
                "blue dragon", "green dragon", "red dragon", "black dragon", "black demon", "lesser demon", "greater demon",
                "fire giant", "moss giant", "hill giant", "ice giant", "baby blue dragon", "hellhound", "ice warrior"));

        static double popularity(String name) {
            return name != null && POPULAR.contains(name.toLowerCase(Locale.ROOT)) ? 3.0 : 1.0;
        }

        static int crowdHunt(HuntMap.Hunt h) {
            int n = 0;
            for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) if (r.labelOverride != null && r.labelOverride.equals(HuntMap.label(h))) n++;
            return n;
        }

        final java.util.Map<String, Long> blacklist = new java.util.HashMap<>();
        /** batch 26 fix 2: "job label@bank" -> until when this bot won't walk that job from that bank again (a rescue or "Couldn't find a path" on the way). */
        final java.util.Map<String, Long> stuckLegs = new java.util.concurrent.ConcurrentHashMap<>();
        core.game.world.map.Location lastSeen;
        /** Audit B-12: where the bot was a heartbeat (3 ticks) before it turned up at the respawn spot, if it got there in one jump of 15+ tiles. */
        core.game.world.map.Location tickSeen, jumpFrom;
        /** audit 10-23 S-2: an engine death while not out on anything: logged, grave run (or re-gear), no blacklist and no SPOT_DEATHS. */
        void idleDeath() {
            deaths++;
            core.game.node.entity.combat.graves.Grave grave = findGrave();
            int cb = 0; try { cb = bot.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
            System.out.println("[SmartAdv] DEATH " + bot.getUsername() + " died near " + diedAt + " while idle (death " + deaths + ", combat " + cb + ", killed by " + diedKiller + ", " + (grave != null ? "gravestone there" : "no gravestone") + ") (audit 10-23 S-2)");
            Economy.DEATHS.incrementAndGet(); // audit 10-23 batch14 item5
            try { Failures.econNote(bot, Failures.DEATH); Failures.death(bot, diedAt, diedKiller, "idle", -1, null, true); } catch (Throwable ignored) { } // batch 19c §6 idle-death
            DEATHS.addFirst(new String[]{bot.getUsername(), "idle", String.valueOf(System.currentTimeMillis())}); while (DEATHS.size() > 30) DEATHS.removeLast();
            try { saveDeathMemorySoon(); } catch (Throwable ignored) { }
            nextThink = System.currentTimeMillis() + 15_000;
            if (grave != null) { graveAt = diedAt; graveUntil = System.currentTimeMillis() + Math.max(10, grave.getTicksRemaining()) * 600L; BotChat.activity(bot, () -> "running back to its gravestone"); }
            if (grave != null) try { crewBless(this, grave); } catch (Throwable t) { System.out.println("[SmartAdv] grave blessing for " + bot.getUsername() + ": " + t); } // batch 15 item3
            else GameWorld.getPulser().submit(new core.game.system.task.Pulse(10) { @Override public boolean pulse() { try { reGear(); } catch (Throwable ignored) { } return true; } });
        }

        void sampleJump() {
            if (bot == null) return;
            if (bot.getDetails().getUid() == 0) stampUid(bot); // batch 17 item1: any brain whose bot came by another way (a re-attach, a test rig) gets one on its first heartbeat
            core.game.world.map.Location here = bot.getLocation();
            if (tickSeen != null && here.getDistance(LUMBRIDGE) <= 6 && tickSeen.getDistance(here) > 15) jumpFrom = tickSeen; // nobody walks 15 tiles in 3 ticks: a respawn (or a paid teleport: checkDeath also wants a gravestone)
            // combat review 10-23 #6: the engine's own death state (DeathTask holds it 6+ ticks; this heartbeat is every 3), so an
            // NPC kill near Lumbridge counts too (it leaves no grave, and the jump rule wanted one). A paid teleport never sets it.
            if (diedAt == null && bot.getAttribute("state:death", false)) {
                Bob.leave(bot, "death"); // P86 hook 7
                diedAt = here.transform(0, 0, 0);
                diedKiller = killerName(); // combat review 10-23 #10: before DeathTask.stop clears the hit log
                diedFood = 0; for (int f : AfkTasks.Runner.FOOD) diedFood += bot.getInventory().getAmount(f);
                diedLost = 0; diedValue = 0; diedIds = null; // batch 19c §6
                if (DEATH_LOSS) try { playerRulesDeath(diedAt); } catch (Throwable t) { System.out.println("[SmartAdv] player-rules death for " + bot.getUsername() + ": " + t); } // combat review 10-23 #14 (off unless switched on)
                if (diedIds == null) try { core.game.container.Container[] dc = core.game.node.entity.combat.DeathTask.getContainers(bot); diedIds = new java.util.ArrayList<>(); for (Item it : dc[1].toArray()) if (it != null) { diedLost++; diedIds.add(it.getId()); diedValue += (long) Math.max(0, MarketBrain.value(it.getId())) * it.getAmount(); } } catch (Throwable ignored) { } // batch 19c §6: what an engine death would put in a grave (read only)
            }
            tickSeen = here;
        }
        core.game.world.map.Location diedAt; String diedKiller; int diedFood; // combat review 10-23 #6/#10: latched while the death animation plays
        final java.util.List<Integer> keptWorn = new java.util.ArrayList<>(); // batch 17 item2: kept items it was wearing when it died by the player rules

        /** batch 17 item2 (review S-8): after the respawn, the kept weapon and armour go back on (wear(), the normal equip path) before the grave run or re-gear, as a player would. */
        void wearKept() {
            if (keptWorn.isEmpty()) return;
            int n = 0;
            for (int id : new java.util.ArrayList<>(keptWorn)) if (bot.getInventory().getAmount(id) > 0 && wear(bot, id)) n++;
            keptWorn.clear();
            if (n > 0) { try { bot.getEquipment().refresh(); bot.getAppearance().sync(); } catch (Throwable ignored) { } System.out.println("[SmartAdv] " + bot.getUsername() + " put its " + n + " kept items back on after the respawn"); }
        }

        /**
         * combat review 10-23 #14: the engine lets an NPC-killed bot keep everything (Player.finalizeDeath returns early for an
         * artificial player killed by an NPC, then AIPlayer does fullRestore). With the switch on, the bot dies by the player
         * rules instead, while the death animation plays (before that early return): the 3 most valuable items are kept, the
         * rest go into a gravestone where it fell (the same engine calls a player's death makes), and the grave run fetches them.
         */
        void playerRulesDeath(core.game.world.map.Location at) {
            core.game.node.entity.Entity top = null; int most = 0;
            for (Map.Entry<core.game.node.entity.Entity, Integer> e : bot.getImpactHandler().getNpcImpactLog().entrySet()) if (e.getValue() > most && e.getKey() != bot) { most = e.getValue(); top = e.getKey(); }
            if (!(top instanceof core.game.node.entity.npc.NPC)) return; // a player kill or the heat: the engine's own rules already apply
            for (Integer uid : bot.getImpactHandler().getPlayerImpactLog().keySet()) if (uid != null && uid != bot.getDetails().getUid() && bot.getImpactHandler().getPlayerImpactLog().get(uid) > most) return; // a player did more: theirs, as the engine decides
            if (bot.getProperties().isSafeZone() || bot.getZoneMonitor().getType() == core.game.world.map.zone.ZoneType.SAFE.getId()) return; // same exceptions a player has
            if (!core.game.node.entity.combat.graves.GraveController.allowGenerate(bot)) return; // skulled, Wilderness, hardcore: not this path
            if (findGrave() != null) return; // one stone at a time
            core.game.container.Container[] c = core.game.node.entity.combat.DeathTask.getContainers(bot);
            if (style5() && styleOf(bot) != MELEE) try { keepStyleWeapon(c); } catch (Throwable t) { System.out.println("[SmartAdv] keeping the style weapon for " + bot.getUsername() + ": " + t); } // train-5 F2 B
            java.util.List<Item> lost = new java.util.ArrayList<>(); for (Item it : c[1].toArray()) if (it != null) lost.add(it);
            if (lost.isEmpty()) return;
            java.util.Set<Integer> wornAtDeath = new java.util.HashSet<>(); for (Item it : bot.getEquipment().toArray()) if (it != null) wornAtDeath.add(it.getId()); // batch 17 item2
            for (Item it : bot.getEquipment().toArray()) { if (it == null) continue; try { core.game.interaction.InteractionListeners.run(it.getId(), bot, it, false); } catch (Throwable ignored) { } } // the unequip hooks, as the engine runs them
            core.game.node.item.GroundItemManager.create(new Item(526), at, bot); // bones, as for a player
            if (bot.getDetails().getUid() == 0) bot.getDetails().accountInfo.setUid(0x40000000 + ((AIPlayer) bot).getUid()); // every bot's account uid is 0, and graves are filed by it: a second bot's stone replaced the first's
            core.game.node.entity.combat.graves.Grave g = core.game.node.entity.combat.graves.GraveController.produceGrave(core.game.node.entity.combat.graves.GraveController.getGraveType(bot));
            g.initialize(bot, at, lost.toArray(new Item[0]));
            bot.getEquipment().clear(); bot.getInventory().clear(); bot.getInventory().addAll(c[0]);
            try { bot.getEquipment().refresh(); bot.getAppearance().sync(); } catch (Throwable ignored) { }
            keptWorn.clear(); for (Item it : c[0].toArray()) if (it != null && !it.getDefinition().isStackable() && wornAtDeath.contains(it.getId())) keptWorn.add(it.getId()); // batch 17 item2: put back on after the respawn
            int kept = 0; for (Item it : c[0].toArray()) if (it != null) kept++;
            try { diedLost = lost.size(); diedIds = new java.util.ArrayList<>(); diedValue = 0; for (Item it : lost) { diedIds.add(it.getId()); diedValue += (long) Math.max(0, MarketBrain.value(it.getId())) * it.getAmount(); } Failures.Deaths.keptUnderRule(bot, kept, lost); } catch (Throwable ignored) { } // batch 19c §6
            System.out.println("[SmartAdv] " + bot.getUsername() + " died by the player rules: kept " + kept + ", " + lost.size() + " item stacks in a gravestone at " + at);
        }

        /** combat review 10-23 #10: whoever did the most damage (the engine's own choice of killer), or what else it was. */
        String killerName() {
            try {
                core.game.node.entity.Entity top = null; int most = 0;
                for (Map.Entry<core.game.node.entity.Entity, Integer> e : bot.getImpactHandler().getNpcImpactLog().entrySet()) if (e.getValue() > most && e.getKey() != bot) { most = e.getValue(); top = e.getKey(); }
                if (top != null) return top.getName() + (top instanceof core.game.node.entity.npc.NPC ? " (level " + ((core.game.node.entity.npc.NPC) top).getDefinition().getCombatLevel() + ")" : "");
                for (Integer uid : bot.getImpactHandler().getPlayerImpactLog().keySet()) if (uid != null && uid != bot.getDetails().getUid()) return "a player";
            } catch (Throwable ignored) { }
            return "no monster (heat, poison or a fall)";
        }
        int deaths;
        int diedLost; long diedValue; java.util.List<Integer> diedIds; // batch 19c §6: what the grave held at the death (player rules, or the engine's containers)
        long noJobSince, noJobLast, noJobShift; // batch 19c §4.5/§8.1: shift time without a job (bot-broke / idle-no-goal)
        static final long NOJOB_MS = Long.getLong("failures.noJobMin", 30L) * 60_000L;
        /** batch 19c §4.5 bot-broke (coins < 100) / §8.1 idle-no-goal: no job chosen for 30 min of shift time; one record per shift. */
        void noJob(long now) {
            if (noJobSince == 0 || now - noJobLast > 10 * 60_000L) noJobSince = now; // a gap: it was doing something else meanwhile
            noJobLast = now;
            if (now - noJobSince < NOJOB_MS || now > shiftEnd || noJobShift == shiftEnd || Deals.involved(bot)) return;
            noJobShift = shiftEnd;
            long coins = Ledger.Wallet.coins(bot), min = (now - noJobSince) / 60_000;
            if (coins < 100) Failures.event("economy", bot, "idle", "bot-broke", coins + " gp, no job for " + min + " min", null, Failures.ctx("coins", coins, "sinceMin", min, "key", "broke-" + Failures.roleOf(bot)));
            else Failures.event("lifecycle", bot, "idle", "idle-no-goal", "no job for " + min + " min", null, Failures.ctx("coins", coins, "goal", goal, "blacklist", blacklist.size(), "fav", fav, "sinceMin", min));
        }

        /** Monsters that poison. A low-level bot with a few trout dies to the poison after the fight, so these need combat 70. */
        static boolean poisonous(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            return n.contains("snake") || n.contains("tribesman") || n.contains("scorpion") || n.contains("poison spider") || n.contains("giant spider") || n.contains("cave slime") || n.contains("kalphite") || n.contains("wall beast") || n.contains("jungle spider") || n.contains("deadly red spider") || n.contains("bloodveld");
        }

        boolean hasAntifire() {
            try { for (Item it : bot.getEquipment().toArray()) if (it != null && (it.getId() == 1540 || it.getId() == 11283 || it.getId() == 11284)) return true; } catch (Throwable ignored) { }
            return false;
        }

        /** A bot that was out working and is suddenly at the Lumbridge respawn died: log it, avoid the spot, re-buy, grumble. */
        void checkDeath() {
            core.game.world.map.Location now = bot.getLocation();
            if (bot.getAttribute("state:death", false)) return; // combat review 10-23 #6: still falling over; act once it has respawned
            if (!keptWorn.isEmpty()) try { wearKept(); } catch (Throwable t) { keptWorn.clear(); System.out.println("[SmartAdv] wearing kept items for " + bot.getUsername() + ": " + t); } // batch 17 item2
            boolean atRespawn = now.getDistance(LUMBRIDGE) <= 6;
            boolean outAndAbout = runner != null || shopAt != null || mill != 0 || Scenes.Greet.escorting(bot) || bone != 0 || w37 != null || summon != 0 || farm != 0 || slay != 0 || agiStep >= 0 || starUntil > 0 || delivering || QuestBook.midQuest(bot) || companion != null; // audit B-5: a crewmate's death counts too (Companions sees graveAt and lets it go)
            boolean engineDeath = diedAt != null; // combat review 10-23 #6
            if (engineDeath) try { Scenes.Greet.drop(bot, "death"); } catch (Throwable ignored) { } // P57 A4: no escort after a death
            // audit 10-23 S-2: a death while idle (between trips, loitering, in a deal) is handled too, without blaming a job or a spot
            if (!outAndAbout && engineDeath) { idleDeath(); lastSeen = now; jumpFrom = null; diedAt = null; return; }
            if (outAndAbout && (engineDeath || lastSeen != null && atRespawn && (lastSeen.getDistance(LUMBRIDGE) > 40 || jumpFrom != null) && findGrave() != null)) { // audit 10-23 S-7: a teleport home (a crewmate following its human, a runner's teleport) is no death without a gravestone // audit B-12: deaths within 40 tiles (goblins over the river) went unnoticed; a jump counts only with a gravestone (a paid teleport to the Lumbridge men/courtyard spots isn't a death)
                if (engineDeath) lastSeen = diedAt; // combat review 10-23 #6: where it fell
                else if (jumpFrom != null) lastSeen = jumpFrom; // the last place it stood alive: the grave is there
                core.game.node.entity.combat.graves.Grave grave = findGrave(); // combat review 10-23 #11/#12: only a non-NPC death leaves one (Player.finalizeDeath)
                if (runner == null) { // died on an errand (audit #28): drop it and do what a player would
                    shopAt = null; pendingJob = null; mill = 0; if (BRAIN_FIX1) { if (bone != 0) try { endBone(true); } catch (Throwable ignored) { } if (slay != 0 && slaySt != null) slaySt.errandTo = null; } bone = 0; slay = 0; if (w37 != null) try { W37Craft.end(this, true, "death"); } catch (Throwable ignored) { w37 = null; } if (summon != 0) endSummon(true, "death"); if (Farming.ON && farm != 0) endFarm(true, "death"); if (agiStep >= 0) agiStep = -1; if (starUntil > 0) endStar(); if (delivering) endDelivery(false);
                    if (errandFor != null) try { Failures.Econ.errandDropped(bot, errandId, errandQty, "death"); } catch (Throwable ignored) { } // batch 19c §4.4
                    if (errandFor != null) { try { Player eh = Ledger.Wallet.findOnline(errandFor); if (eh != null && !eh.isArtificial()) eh.sendMessage("<col=0b5e1f>" + bot.getUsername() + " died on your errand; the order is off, sorry."); } catch (Throwable ignored) { } errandFor = null; errandId = 0; errandQty = 0; } // (review 10-22)
                }
                deaths++;
                String where = lastJob != null ? lastJob.label() : "somewhere";
                int tier = Crew.tierOf(this), cb = 0; try { cb = bot.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
                System.out.println("[SmartAdv] DEATH " + bot.getUsername() + " died near " + lastSeen + " while on " + where + " (death " + deaths + ", tier " + (tier == 2 ? "high" : tier == 1 ? "veteran" : "fresh") + ", combat " + cb + ", killed by " + (engineDeath ? diedKiller : "unknown") + ", food left " + (engineDeath ? String.valueOf(diedFood) : "?") + ")"); // combat review 10-23 #10
                Economy.DEATHS.incrementAndGet(); // audit 10-23 batch14 item5
                if (Seed.ON) Scen.death(bot, lastJob); // w9 vannakaHunts scenario (seed flags on only)
                try { Failures.econNote(bot, Failures.DEATH); Failures.death(bot, lastSeen, engineDeath ? diedKiller : "unknown", where, -1, null, false); } catch (Throwable ignored) { } // batch 19c §6 repeat-death / death-spike
                try { Heat.death(lastSeen); } catch (Throwable ignored) { } // the heat map remembers where bots die
                if (lastJob != null) blacklist.put(lastJob.label(), System.currentTimeMillis() + 24L * 3600 * 1000);
                DEATHS.addFirst(new String[]{bot.getUsername(), where, String.valueOf(System.currentTimeMillis())}); while (DEATHS.size() > 30) DEATHS.removeLast();
                if (lastJob != null) { java.util.List<Long> l = SPOT_DEATHS.computeIfAbsent(lastJob.label(), k -> new java.util.ArrayList<>()); synchronized (l) { l.add(System.currentTimeMillis()); } }
                try { saveDeathMemorySoon(); } catch (Throwable t) { System.out.println("[SmartAdv] death memory: " + t); } // combat review 10-23 #5: it used to forget at every restart
                try { if (runner != null) { runner.bankingOut = true; runner.finish("Died."); } } catch (Throwable ignored) { } // combat review 10-23 #12: the trip ends here: no walk to a bank that fights the grave run (or, fed by the killing blow's size, tabs out again and again); what is left in the pack is sold to bot stock, as a failed trip's haul is
                runner = null; Bob.leave(bot, "runner end"); // P86 hook 11
                AfkTasks.RUNNING.remove(bot.getUsername().toLowerCase(Locale.ROOT));
                BotChat.news(String.format(BotChat.pick("%s got killed out at the %s. Walked back from Lumbridge in a mood.", "Heard %s died at the %s. Gear and all. Don't mention it to them.", "%s is back from the dead. The %s got the better of them."), bot.getUsername(), where));
                say(BotChat.pick("Well. That was a death. Brilliant.", "Lost half my kit. Don't ask.", "Never going back there. Probably.", "Ow. Just... ow.", "That went badly.", "Back in Lumbridge. Again.", "Didn't see that coming.", "Remind me never to do that again."));
                feel(Mood.GRUMPY, 30 * 60_000);
                if (crew != null) try { crew.mourn(this); } catch (Throwable ignored) { }
                if (grave != null) try { crewBless(this, grave); } catch (Throwable t) { System.out.println("[SmartAdv] grave blessing for " + bot.getUsername() + ": " + t); } // batch 15 item3
                nextThink = System.currentTimeMillis() + 15_000;
                // The server kept its 3 most valuable items (now in its pack) and left a gravestone with the rest where it
                // fell. Go back for it, unless the same spot has killed bots twice today (then cut losses and re-buy).
                if (recentDeaths(where) < 2 && lastSeen != null && grave != null) { // combat review 10-23 #11: no grave (an NPC kill), no grave run
                    graveAt = lastSeen; graveUntil = System.currentTimeMillis() + Math.max(10, grave.getTicksRemaining()) * 600L; // combat review 10-23 #11: the stone's own time left (a bot's plaque lasts 2 min, not 4)
                    say(BotChat.pick("Right. Grave run.", "My stuff's still out there. Back in a minute.", "Not leaving my kit to rot.", "My stuff! Running back.", "Grave run, before it crumbles.", "Not losing that kit. Back I go."));
                    BotChat.activity(bot, () -> "running back to its gravestone");
                } else GameWorld.getPulser().submit(new core.game.system.task.Pulse(10) { @Override public boolean pulse() { try { reGear(); } catch (Throwable ignored) { } return true; } });
            }
            lastSeen = now; jumpFrom = null; diedAt = null; // combat review 10-23 #6
        }

        // ---------------------------------------------------------------- shooting stars (owner notes #4)
        long starUntil, nextStarLook; core.game.node.scenery.Scenery starObj;
        static final java.util.Set<String> AT_STAR = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** The live star from the server's plugin: {object, mining level, place name}, or null. */
        static Object[] star() {
            try {
                Class<?> c = Class.forName("content.global.activity.shootingstar.ShootingStarPlugin");
                java.lang.reflect.Field f = c.getDeclaredField("star"); f.setAccessible(true);
                Object s = f.get(null);
                if (s == null || !(Boolean) s.getClass().getMethod("isSpawned").invoke(s)) return null;
                Object obj = s.getClass().getMethod("getStarObject").invoke(s);
                int lvl = ((Number) s.getClass().getMethod("getMiningLevel").invoke(s)).intValue();
                String where = String.valueOf(s.getClass().getMethod("getLocation").invoke(s));
                return obj instanceof core.game.node.scenery.Scenery ? new Object[]{obj, lvl, where} : null;
            } catch (Throwable t) { return null; }
        }

        boolean wantsStar(long now) {
            if (now < nextStarLook || fav != Skills.MINING || crew != null && crew.leader != this) return false;
            nextStarLook = now + (FAST ? 30_000 : 3 * 60_000);
            if (AT_STAR.size() >= 5) return false;
            Object[] s = star();
            if (s == null || bot.getSkills().getStaticLevel(Skills.MINING) < (Integer) s[1]) return false;
            starObj = (core.game.node.scenery.Scenery) s[0];
            return starObj.getLocation().getZ() == 0 && starObj.getLocation().getDistance(bot.getLocation()) < 900 && AfkTasks.hasAny(bot, AfkTasks.PICKAXES);
        }

        void startStar(long now) {
            starUntil = now + (FAST ? 4 * 60_000 : 9 * 60_000); // getting there plus five minutes on the rock
            AT_STAR.add(bot.getUsername());
            Object[] s = star(); final String where = s != null ? (String) s[2] : "the star";
            say(BotChat.pick("Star's down near " + where + "! Who's coming?", "Shooting star at " + where + ". Pickaxes out!", "Star near " + where + ", I'm off.", "Stardust! Near " + where + "!"));
            BotChat.activity(bot, () -> "mining the shooting star near " + where);
            System.out.println("[SmartAdv] " + bot.getUsername() + " heading for the shooting star near " + where);
        }

        void starTick(long now) {
            Object[] s = star();
            if (s == null || now >= starUntil || s[0] != starObj && !(s[0] instanceof core.game.node.scenery.Scenery)) { endStar(); return; }
            starObj = (core.game.node.scenery.Scenery) s[0];
            if (bot.getPulseManager().hasPulseRunning() || bot.getWalkingQueue().isMoving()) return;
            Location at = starObj.getLocation(); double d = bot.getLocation().getDistance(at);
            if (d > 6) {
                if (d > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.coins(bot) >= 5_000 && Ledger.Wallet.payTeleport(bot)) { Location near = Surveyors.Surveyor.walkable(Location.create(at.getX() + RNG.nextInt(7) - 3, at.getY() + RNG.nextInt(7) - 3, 0)); Spectacle.teleport(bot, near != null ? near : at); return; } // batch 15 item1
                Location nav0 = NavWalker.ON ? NavWalker.step(bot, at) : null;
                Location step = nav0 != null ? nav0 : d <= 25 ? at : Location.create(bot.getLocation().getX() + (int) Math.round((at.getX() - bot.getLocation().getX()) * 25 / d), bot.getLocation().getY() + (int) Math.round((at.getY() - bot.getLocation().getY()) * 25 / d), 0);
                try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
                return;
            }
            if (RNG.nextInt(12) == 0) say(BotChat.pick("Dust for days.", "Mind your pick, it's crowded.", "Anyone else seen the sprite?", "Lovely bit of star, this.", "This star's a big one.", "Swing, swing, swing.", "Anyone got spare picks?", "My arms are going to fall off."));
            try { scriptAPI.interact(bot, starObj, "Mine"); } catch (Throwable ignored) { }
        }

        void endStar() {
            starUntil = 0; AT_STAR.remove(bot.getUsername()); BotChat.clearActivity(bot);
            int dust = bot.getInventory().getAmount(13727); // stardust: kept in the bank (it's for the sprite's rewards)
            if (dust > 0 && bot.getInventory().remove(new core.game.node.item.Item(13727, dust))) bot.getBank().add(new core.game.node.item.Item(13727, dust));
            System.out.println("[SmartAdv] " + bot.getUsername() + " finished at the star with " + dust + " stardust");
            nextThink = System.currentTimeMillis() + 3_000;
        }

        // Gnome Stronghold course, in order: {name, option, x, y, z} of each obstacle.
        static final Object[][] GNOME = {
                {"Log balance", "Walk-across", 2474, 3436, 0}, {"Obstacle net", "Climb-over", 2473, 3425, 0},
                {"Tree branch", "Climb", 2473, 3422, 1}, {"Balancing rope", "Walk-on", 2478, 3420, 2},
                {"Tree branch", "Climb-down", 2486, 3419, 2}, {"Obstacle net", "Climb-over", 2485, 3426, 0},
                {"Obstacle pipe", "Squeeze-through", 2484, 3431, 0}};
        static final Location GNOME_START = Location.create(2474, 3438, 0);

        boolean wantsAgility(long now) {
            if (crew != null || broke() || now < nextAgility) return false;
            int agi = bot.getSkills().getStaticLevel(Skills.AGILITY);
            if (W7Places.AGI_BARB ? W7Places.noAgility(true, agi, W7Places.barbSeatsUsed(now)) : agi >= 35) return false; // past the Gnome course (W7 -Dbots.agility.barb: 35 to 51 go to the Barbarian Outpost while it has a seat)
            if (agilityNudge) return true;
            nextAgility = now + (FAST ? 60_000 : 20 * 60_000);
            return RNG.nextInt(FAST ? 2 : 8) == 0 || Boolean.getBoolean("smartbots.testAgility");
        }

        void startAgility(long now) {
            if (W7Places.AGI_BARB) { barb = null; if (W7Places.barbCourse(true, bot.getSkills().getStaticLevel(Skills.AGILITY))) { W7Run.startBarb(this, now); return; } } // W7 (-Dbots.agility.barb)
            agilityNudge = false; agiStep = 0; agiLaps = 0; agiTries = 0; agiStall = 0; agiWait = 0; agiBest = Double.MAX_VALUE;
            agiGoal = W3Skills.lapsFor(W3Skills.AGI_LAPS, bot.getSkills().getStaticLevel(Skills.AGILITY)); // W3 fix 9 (-Dbots.agility.laps): 10 to 30 laps by level (flag off: 10)
            agiUntil = now + (FAST ? 8 * 60_000 : W3Skills.agiMinutes(agiGoal) * 60_000L); // 45 min for ten laps, as before
            nextAgility = now + (FAST ? 5 * 60_000 : 4 * 3_600_000L);
            say(BotChat.pick("Off to the Gnome course. Ten laps.", "Agility time. Gnome Stronghold.", "Can't climb a thing. Gnome course it is.", "Agility day. Gnome course.", "Those gnome nets won't climb themselves.", "Time to fall off a log. Gnome course."));
            BotChat.activity(bot, () -> "running laps on the Gnome Stronghold agility course");
            System.out.println("[SmartAdv] " + bot.getUsername() + " heading to the Gnome course (agility " + bot.getSkills().getStaticLevel(Skills.AGILITY) + ")");
        }

        void endAgility(String line) {
            if (line != null) say(line);
            System.out.println("[SmartAdv] " + bot.getUsername() + " finished agility: " + agiLaps + " laps, step " + agiStep + ", now level " + bot.getSkills().getStaticLevel(Skills.AGILITY) + (W3Skills.AGI_LAPS ? " (goal " + agiGoal + ", " + (line == null ? "-" : line) + ")" : ""));
            if (W3Skills.AGI_LAPS) { W3Skills.agiVisits++; W3Skills.agiLapsRun += agiLaps; }
            agiStep = -1; BotChat.clearActivity(bot);
            nextThink = System.currentTimeMillis() + 3_000;
        }

        void agilityTick(long now) {
            if (W7Places.AGI_BARB && barb != null) { W7Run.barbTick(this, now); return; } // W7 (-Dbots.agility.barb): the Barbarian Outpost visit has its own steps
            if (now > agiUntil) { endAgility(BotChat.pick("Legs are done. That'll do.", "Enough laps for today.")); return; }
            if (bot.getWalkingQueue().isMoving() || bot.getPulseManager().hasPulseRunning()) return;
            Location here = bot.getLocation();
            double xp = bot.getSkills().getExperience(Skills.AGILITY);
            if (agiWait > 0) { // crossing the last obstacle: XP means it worked
                if (xp > agiXp) {
                    agiWait = 0; agiTries = 0; agiStep++;
                    if (agiStep >= GNOME.length) {
                        agiStep = 0; agiLaps++;
                        if (agiLaps >= agiGoal) { endAgility(agiGoal == 10 ? BotChat.pick("Ten laps. Feeling lighter already.", "Done. Ten laps.") : BotChat.pick(agiGoal + " laps. Feeling lighter already.", "Done. " + agiGoal + " laps.")); return; }
                        if (agiLaps * 2 == agiGoal && RNG.nextInt(2) == 0) say("Halfway.");
                    }
                } else if (now < agiWait) return;
                else { agiWait = 0; if (++agiTries > 3) { endAgility("Can't get past this one. Another day."); return; } }
            }
            Object[] ob = GNOME[agiStep];
            final Location at = Location.create((Integer) ob[2], (Integer) ob[3], (Integer) ob[4]);
            if (agiStep == 0 && agiLaps == 0 && agiTries == 0 && (here.getZ() != 0 || here.getDistance(GNOME_START) > 14)) { // still getting there
                double d = here.getZ() != 0 ? 999 : here.getDistance(GNOME_START);
                if (d < agiBest - 1) { agiBest = d; agiStall = 0; } else agiStall++;
                long tp = Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556));
                if ((d > AfkTasks.Runner.TELEPORT_MIN || agiStall > 8) && Ledger.Wallet.coins(bot) >= tp * 3 && Ledger.Wallet.payTeleport(bot)) {
                    try { Spectacle.teleport(bot, GNOME_START); } catch (Throwable ignored) { } // batch 15 item1
                    agiStall = 0; return;
                }
                if (agiStall > 12) { endAgility("Can't find the way. Forget it."); return; }
                Location step = d <= 25 ? GNOME_START : Location.create(here.getX() + (int) Math.round((GNOME_START.getX() - here.getX()) * 25 / d), here.getY() + (int) Math.round((GNOME_START.getY() - here.getY()) * 25 / d), 0);
                try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
                return;
            }
            final String name = (String) ob[0], opt = (String) ob[1];
            core.game.node.Node n = null;
            try {
                n = scriptAPI.getNearestObjectByPredicate(node -> {
                    if (!(node instanceof core.game.node.scenery.Scenery) || node.getLocation().getZ() != at.getZ() || node.getLocation().getDistance(at) > 6) return false;
                    core.game.node.scenery.Scenery sc = (core.game.node.scenery.Scenery) node;
                    if (sc.getName() == null || !sc.getName().equalsIgnoreCase(name)) return false;
                    for (String o : sc.getDefinition().getOptions()) if (o != null && o.equalsIgnoreCase(opt)) return true;
                    return false;
                });
            } catch (Throwable ignored) { }
            if (n == null) { // off the course (fell, or wandered): back to the start of the lap
                if (++agiTries > 3) { endAgility("Lost the course. Done for today."); return; }
                System.out.println("[SmartAdv] " + bot.getUsername() + " can't see the " + name + " from " + here + "; back to the start");
                agiStep = 0;
                try { ((AIPlayer) bot).walkToPosSmart(GNOME_START); } catch (Throwable ignored) { }
                return;
            }
            agiXp = xp;
            try { scriptAPI.interact(bot, n, opt); } catch (Throwable t) { agiTries++; return; }
            agiWait = now + 15_000;
        }

        /** Equip what it carries (the 3 kept items, anything recovered), then buy only what is still missing. */
        void reGear() {
            if (!(bot instanceof AIPlayer)) return;
            for (Item it : bot.getInventory().toArray()) {
                if (it == null || it.getDefinition() == null || it.getDefinition().getEquipId() <= 0 || it.getId() == 995) continue;
                Object cfg = it.getDefinition().getConfiguration("equipment_slot", -1);
                int slot = cfg instanceof Number ? ((Number) cfg).intValue() : -1;
                if (slot < 0 || bot.getEquipment().get(slot) != null) continue; // keep what it already wears
                wear(bot, it.getId());
            }
            fillEmptySlots((AIPlayer) bot);
            try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
        }

        /** This bot's active gravestone, or null. */
        @SuppressWarnings("unchecked")
        core.game.node.entity.combat.graves.Grave findGrave() {
            try {
                java.lang.reflect.Field f = core.game.node.entity.combat.graves.GraveController.class.getDeclaredField("activeGraves");
                f.setAccessible(true);
                for (Object g : ((java.util.Map<Integer, Object>) f.get(null)).values()) {
                    core.game.node.entity.combat.graves.Grave gr = (core.game.node.entity.combat.graves.Grave) g;
                    if (bot.getUsername().equalsIgnoreCase(gr.getOwnerUsername())) return gr;
                }
            } catch (Throwable t) { System.out.println("[SmartAdv] grave lookup: " + t); }
            return null;
        }

        /** The grave: find it, walk (or pay to teleport) there, take everything back, let the stone crumble. */
        @SuppressWarnings("unchecked")
        void graveTick(long now) {
            if (graveFor != graveAt) { graveFor = graveAt; graveFrom = bot.getLocation(); Watchdog.ftrip(bot, graveFrom); } // batch 18 item2 // batch 19a: the trip's start
            if (now > graveUntil) { if (graveAt != null) Watchdog.scenario(bot, "failed_trip", "grave", graveFrom, graveAt, bot.getLocation(), "grave run timed out " + (int) bot.getLocation().getDistance(graveAt) + " tiles short"); // batch 18 item2
                try { Failures.Deaths.graveLost(bot, "timeout", diedLost, diedValue, graveAt != null ? (int) bot.getLocation().getDistance(graveAt) : -1, graveAt); } catch (Throwable ignored) { } // batch 19c §6
                System.out.println("[SmartAdv] " + bot.getUsername() + " grave run failed: timed out at " + bot.getLocation() + ", " + (graveAt != null ? (int) bot.getLocation().getDistance(graveAt) : -1) + " tiles short"); say(BotChat.pick("Too late, it's gone. Typical.", "Grave's crumbled. Never mind.", "Gone. Great.", "Grave's dust. Start again then.")); graveAt = null; BotChat.clearActivity(bot); reGear(); return; } // combat review 10-23 #11: failures are logged
            core.game.node.entity.combat.graves.Grave grave = findGrave();
            if (grave == null) { Watchdog.scenario(bot, "failed_trip", "grave", graveFrom, graveAt, bot.getLocation(), "the grave crumbled first, " + (int) bot.getLocation().getDistance(graveAt) + " tiles short"); // batch 18 item2
                try { Failures.Deaths.graveLost(bot, "crumbled", diedLost, diedValue, (int) bot.getLocation().getDistance(graveAt), graveAt); } catch (Throwable ignored) { } // batch 19c §6
                System.out.println("[SmartAdv] " + bot.getUsername() + " grave run failed: the grave crumbled first (" + (int) bot.getLocation().getDistance(graveAt) + " tiles short)"); graveAt = null; BotChat.clearActivity(bot); reGear(); return; } // combat review 10-23 #11: graveAt is only set when there was a grave, so gone means crumbled
            Location there = grave.getLocation();
            double d = bot.getLocation().getDistance(there);
            if (d > 3 || bot.getLocation().getZ() != there.getZ()) {
                if (bot.getWalkingQueue().isMoving()) return;
                if (d > 60 && Ledger.Wallet.payTeleport(bot)) {
                    Location near = Surveyors.Surveyor.walkable(Location.create(there.getX() + RNG.nextInt(5) - 2, there.getY() + RNG.nextInt(5) - 2, there.getZ()));
                    try { Spectacle.teleport(bot, near != null ? near : there); } catch (Throwable ignored) { } // batch 15 item1
                    return;
                }
                // combat review 10-23 #11: walking covers about a tile a tick; a stone that crumbles before it could get there isn't worth the walk
                int left = grave.getTicksRemaining();
                if (left >= 0 && d > left) {
                    try { Failures.Deaths.graveLost(bot, "too-far", diedLost, diedValue, (int) d, there); if (d > 60) Failures.Econ.fee(bot, "teleport", Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556))); } catch (Throwable ignored) { } // batch 19c §6 / §4.7
                    System.out.println("[SmartAdv] " + bot.getUsername() + " grave run skipped: too far (" + (int) d + " tiles, " + left * 6 / 10 + " s left" + (d > 60 ? ", no teleport fare" : "") + ")");
                    say(BotChat.pick("Too far to make it back in time. Never mind.", "Grave'll be dust before I get there. Forget it."));
                    graveAt = null; BotChat.clearActivity(bot); reGear(); return;
                }
                Location nav1 = NavWalker.ON ? NavWalker.step(bot, there) : null;
                Location step = nav1 != null ? nav1 : d <= 25 ? there : Location.create(bot.getLocation().getX() + (int) Math.round((there.getX() - bot.getLocation().getX()) * 25 / d), bot.getLocation().getY() + (int) Math.round((there.getY() - bot.getLocation().getY()) * 25 / d), there.getZ());
                try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
                return;
            }
            int got = 0; java.util.List<Integer> picked = new java.util.ArrayList<>(); // batch 19c §6
            for (core.game.node.item.GroundItem gi : grave.getItems()) {
                if (gi == null || !gi.isActive()) continue;
                Item it = new Item(gi.getId(), gi.getAmount());
                core.game.node.item.GroundItemManager.destroy(gi);
                if (!bot.getInventory().add(it) && !bot.getBank().add(it)) try { Failures.Econ.itemLost(bot, it.getId(), it.getAmount(), "grave", "-"); } catch (Throwable ignored) { } // batch 19c §4.2: the same two adds
                got++; picked.add(it.getId());
            }
            try { Failures.econNote(bot, Failures.GRAVE); java.util.List<Integer> miss = new java.util.ArrayList<>(); if (diedIds != null) { java.util.List<Integer> left = new java.util.ArrayList<>(picked); for (Integer id : diedIds) if (!left.remove(id) && miss.size() < 8) miss.add(id); } Failures.Deaths.graveShort(bot, diedLost, got, miss); } catch (Throwable ignored) { } // batch 19c §6 grave-short
            try { grave.setTicksRemaining(1); } catch (Throwable ignored) { } // empty now: let it crumble
            try { bot.face(grave); bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(827)); } catch (Throwable ignored) { }
            say(BotChat.pick("Got it all back. Phew.", "Everything's here. Lesson learned.", "Kit recovered. Never again. Probably.", "Everything's back. Close one.", "Phew. Kit recovered.", "All there. Lesson learned. Maybe."));
            System.out.println("[SmartAdv] " + bot.getUsername() + " recovered " + got + " item stacks from its grave");
            Economy.GRAVE_RECOVERIES.incrementAndGet(); // audit 10-23 batch14 item5
            graveAt = null; BotChat.clearActivity(bot);
            reGear();
        }

        static final int[] ALCH_BOWS = {839, 841, 843, 845, 847, 849, 851, 853, 855, 857, 859, 861};
        long nextAlch;
        /** High Alchemy: a bot with 55+ Magic buys up to 10 surplus bows and nature runes from the Exchange (paying the fletcher and the runecrafter) and alchs them. The coins are the game's, capped per day in Ledger. */
        boolean highAlch(long now) { return highAlch(now, false); }
        /** W6 Magic (-Dbots.magic.alchAll): routine = the trip-end call, anywhere (the bows and runes are bought from bot stock); flag on, the fleet hour cap on minted coins and Magic xp. */
        boolean highAlch(long now, boolean routine) {
            if (now < nextAlch || bot.getSkills().getStaticLevel(Skills.MAGIC) < 55 || !(routine || nearGE(bot))) return false;
            nextAlch = now + 10 * 60_000;
            if (MarketBrain.stockOf(561) < 60) return false;
            int bow = 0; for (int b : ALCH_BOWS) if (MarketBrain.isSurplus(b) && MarketBrain.stockOf(b) >= 20) { bow = b; break; }
            if (bow == 0) return false;
            int alch = 0; try { alch = core.cache.def.impl.ItemDefinition.forId(bow).getAlchemyValue(true); } catch (Throwable ignored) { }
            int cost = MarketBrain.value(bow) + MarketBrain.value(561);
            if (alch <= cost) return false; // no profit in it: a player wouldn't
            int n = 10;
            if (Ledger.Wallet.coins(bot) < (long) cost * n) return false;
            if (W6Magic.ALCH_ALL && !W6Magic.alchHourOk(now, (long) alch * n)) { W6Magic.alchCapped++; System.out.println("[Magic] alch " + bot.getUsername() + " capped: the fleet hour holds " + W6Magic.alchHourGp + " of " + W6Magic.ALCH_HOUR + " gp minted"); return false; } // W6 Magic: checked before the day's allowance is booked
            if (!Ledger.CLERK_FIX2 && !Ledger.alchRoom((long) (alch - cost) * n)) return false;
            Item wep = null; try { wep = bot.getEquipment().get(3); } catch (Throwable ignored) { }
            boolean fireStaff = wep != null && (wep.getId() == 1387 || wep.getId() == 1393 || wep.getId() == 1401 || wep.getId() == 3053 || wep.getId() == 3054);
            if (!fireStaff) { if (MarketBrain.stockOf(554) < 5 * n + 50 || Ledger.Wallet.coins(bot) < (long) (cost + 5 * MarketBrain.value(554)) * n) return false; }
            if (Ledger.CLERK_FIX2) { // econ3 M3: all three checked first, the allowance booked last, so a refusal costs nothing
                if (!Ledger.alchReady(n, MarketBrain.stockOf(bow), MarketBrain.stockOf(561), MarketBrain.stockOf(554), fireStaff, Ledger.Wallet.coins(bot), Ledger.price(bow), Ledger.price(561), Ledger.price(554))) return false;
                if (!Ledger.alchRoom((long) (alch - cost) * n)) return false;
            }
            if (Ledger.consume(bot, bow, n, "alch") < n) return false;
            if (Ledger.consume(bot, 561, n, "alch") < n) return false;
            if (!fireStaff && Ledger.consume(bot, 554, 5 * n, "alch") < 5 * n) return false; // five fire runes a cast without a fire staff
            Ledger.Wallet.give(bot, (long) alch * n);
            if (W6Magic.ALCH_ALL) { W6Magic.alchBook((long) alch * n, (long) (alch - cost) * n, n); try { bot.getSkills().addExperience(Skills.MAGIC, W6Magic.ALCH_XP * n, true); } catch (Throwable ignored) { } System.out.println("[Magic] alch " + bot.getUsername() + (routine ? " (routine)" : " (break)") + " minted " + ((long) alch * n) + " gp, fleet hour " + W6Magic.alchHourGp + "/" + W6Magic.ALCH_HOUR + ", day " + Ledger.alchedToday + "/" + Ledger.ALCH_DAILY_CAP); } // W6 Magic: the fleet caps logged with each alch
            try { Failures.econNote(bot, Failures.ALCH); } catch (Throwable ignored) { } // batch 19c §4.1
            final Player b2 = bot;
            GameWorld.getPulser().submit(new core.game.system.task.Pulse(2) { int i; @Override public boolean pulse() { try { b2.getAnimator().animate(new core.game.world.update.flag.context.Animation(713)); b2.graphics(new core.game.world.update.flag.context.Graphics(113, 96)); } catch (Throwable ignored) { } return ++i >= n; } });
            final int fbow = bow;
            BotChat.activity(bot, () -> "alching " + MarketBrain.name(fbow).toLowerCase(Locale.ROOT) + "s at the bank");
            if (RNG.nextInt(2) == 0) say(BotChat.pick("Alching " + MarketBrain.name(bow).toLowerCase(Locale.ROOT) + "s. Slow money, but money.", "Nats and bows. The 2009 way.", "High alch, high alch, high alch. Zzz.", "Cheaper than training Magic on chickens."));
            System.out.println("[SmartAdv] " + bot.getUsername() + " alched " + n + " x " + MarketBrain.name(bow) + " for " + (alch * n) + " gp (cost " + (cost * n) + ")");
            return true;
        }

        /** How many bots a job can keep busy at once, from how many rocks, trees or spawns the spots actually have. */
        static int capacity(Job j) {
            if (j.hunt != null) { if (W6Slayer.ON) { int s6 = W6Slayer.seats(j.hunt); if (s6 > 0) return s6; } return Math.max(1, Math.min(4, j.hunt.spawnsAtSpot / 3)); } // W6: a Vannaka hunt's own seat cap
            if (W7Places.FISH_GUILD && W5aSkills.SHARK && j.sharkRow) return W7Places.sharkCapacity(true, W5aSkills.SHARK_CAP); // W7 (-Dbots.fish.guild): the Catherby cap plus the guild's shark seats
            if (W5aSkills.SHARK && j.sharkRow) return W5aSkills.SHARK_CAP; // W5a: the Catherby Net/Harpoon spots are a player hotspot
            switch (j.task) {
                case ADAMANT: return 2;      // two rocks at the swamp mine, two on Karamja
                case MITHRIL: case GOLD: case SILVER: return 2;
                case RUNITE: return 1;
                case COAL: case IRON: return 6;
                default: return 4;
            }
        }

        /** How many other smart adventurers are already on this task. */
        /**
         * W3 fix 4 (-Dbots.jobShare): the share of the last smart-bot job starts that were pickpocketing / woodcutting, cached 5 s, into
         * W3Skills.thieveNow / logNow. w4-jobshare: it was the share of bots on such a runner at this moment, which a pickpocket trip of
         * about a minute keeps at 2-7 of 60 bots (k60 ON arms) while it is 44% of job starts, so the 15% cap never closed.
         */
        static void w3Shares() {
            long now = System.currentTimeMillis(); if (now < W3Skills.shareAt) return; W3Skills.shareAt = now + 5_000;
            double[] sh = W3Skills.startShares(); W3Skills.thieveNow = sh[0]; W3Skills.logNow = sh[1];
        }
        /** W5a (-Dbots.fish.shark): the bots on this job row (a harpoon row counts only runners with its spot rule). */
        static int crowdJob(Job j) {
            if (!W5aSkills.SHARK || j.spotRule == 0) return crowd(j.task);
            int n = 0;
            for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) if (r.task == j.task && r.p.isArtificial() && r.w5SpotRule == j.spotRule) n++;
            return n;
        }
        /** W5b stalls: a market stall job this bot may not take now (under combat 20, or the 3 seats are full). Only called with the flag on. */
        static boolean stallBlocked(Job j, int combat) {
            if (j.hunt != null || j.task.kind != AfkTasks.Kind.STALL) return false;
            return combat < W5bSkills.STALL_MIN_COMBAT || !W5bSkills.stallSeat(stallCrowd(), W5bSkills.STALL_SEATS);
        }

        /** W7c (-Dbots.thieve.ardy): a master farmer / knight job (or, for the shared market cap, a stall job) this bot may not take now. Only called with the flag on. */
        static boolean w7ArdyBlocked(Job j, int combat, boolean count) {
            if (j.hunt != null) return false;
            if (j.task.kind == AfkTasks.Kind.STALL) { // the shared Ardougne market cap: knights + stall bots at most 4 (the stalls keep their own 3 seats as well)
                boolean full = W5bSkills.STALLS && W7cSkills.marketFull(crowd(AfkTasks.Task.KNIGHT), stallCrowd(), true);
                if (full && count) W7cSkills.marketFullStalls++;
                return full;
            }
            if (!W7cSkills.ardyKey(j.task.key)) return false;
            return W7cSkills.ardyBlocked(j.task.key, combat, crowd(j.task), W5bSkills.STALLS ? stallCrowd() : 0, W5bSkills.STALLS, System.currentTimeMillis(), count);
        }

        /** W5b stalls: bots on any market stall right now (the seats are shared by all of them). */
        static int stallCrowd() {
            int n = 0;
            for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) if (r.task != null && r.task.kind == AfkTasks.Kind.STALL && r.p != null && r.p.isArtificial()) n++;
            return n;
        }

        static int crowd(AfkTasks.Task t) {
            int n = 0;
            for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) if (r.task == t && r.p.isArtificial()) n++;
            return n;
        }

        /** The best bounty this bot can get: weight / (distance + risk), skipping surplus items. */
        static final boolean DEBUG_JOBS = Boolean.getBoolean("jobs.debug");
        // P40 B4 (-Dbots.brokeJob, default off): a bot under the broke line takes a FREE earning job (gathering or thieving near
        // where it stands, a tool it already owns, no bait, no hunt) and sells the haul to a shop at the bank (AfkTasks.brokeShopSale).
        static boolean BROKE_JOB = Boolean.getBoolean("bots.brokeJob");
        static final int BROKE_LINE = 400, BROKE_RADIUS = Integer.getInteger("bots.brokeRadius", 200), BROKE_SHOP_PCT = 40;
        /** P40 B4: nothing to buy and no fare to pay: no hunt (ammo, runes, food), no bait or feathers, the tool already owned, a walk. */
        boolean freeJob(Job j, double dist) {
            if (j.hunt != null || dist > BROKE_RADIUS) return false;
            if (j.task.kind == AfkTasks.Kind.SMELT || j.task.kind == AfkTasks.Kind.BANK || j.task.kind == AfkTasks.Kind.FIGHT) return false;
            if (j.task == AfkTasks.Task.BAIT || j.task == AfkTasks.Task.LURE) return false;
            if (j.task.tools == null || j.task.tools.length == 0 || AfkTasks.hasAny(bot, j.task.tools)) return true;
            for (int t : j.task.tools) if (bot.getBank().getAmount(t) > 0) return true;
            return false;
        }

        /** Combat a job check should trust: a 1-Defence bot with 70 Attack is not really combat 64 (owner notes #1). */
        int effectiveCombat() { return Math.min(bot.getProperties().getCurrentCombatLevel(), bot.getSkills().getStaticLevel(Skills.DEFENCE) + 25); }

        /** Monsters that killed bots in numbers on 2026-10-20 for reasons combat level doesn't cover. */
        boolean deadlyFor(HuntMap.Hunt h, int combat) {
            String n = h.name.toLowerCase(Locale.ROOT);
            if (n.contains("banshee") && !(bot.getEquipment().get(0) != null && bot.getEquipment().get(0).getId() == 4166)) return true; // needs earmuffs
            if ((n.contains("blamish") || n.contains("zogre") || n.contains("crawling hand")) && combat < 60) return true; // Mort Myre, Jiggig, the Slayer Tower
            if (AfkTasks.Runner.P91 && AfkTasks.Runner.p91Blocks(true, HuntMap.slayerNeed(h), AfkTasks.Runner.slayerLevel(bot))) { AfkTasks.Runner.p91Count(AfkTasks.Runner.P91_HUNT); return true; } // P91: every hit would be zeroed
            return false;
        }

        /** W2 lane M combat steer (FightRules.STEER only): is this fight job's spot multi-combat? A task with several spots: any it may enter outside the Wilderness. */
        boolean steerMultiJob(Job j) {
            if (j.hunt != null) return Bob.steerMulti(j.hunt.spot);
            AfkTasks.Spot[] spots = AfkTasks.SPOTS.get(j.task);
            if (spots != null) for (AfkTasks.Spot s : spots) if (!Bob.wild(s.at) && Bob.steerMulti(s.at) && Gates.canEnter(bot, s.at)) return true;
            return false;
        }
        boolean w36Mine, w36Fish, w36Wc; // w36-silent2: the last choice floored a guild mining / guild fishing / log job (only touched under the flags)
        /** w36-silent2 (W36Silent): the floor weight for a log job under its share of the picks (-Dbots.wc.share2), a coal/mithril job of a Mining 60+ bot the
         *  Mining Guild takes now (-Dbots.mine.guild2, needs bots.mine.guild) and a lobster/harpoon job of a Fishing 68+ bot with a guild seat free (-Dbots.fish.guild2). */
        double w36Weight(Job j, double weight, long nowMs) {
            try {
                if (W36Silent.WC2 && j.task.kind == AfkTasks.Kind.TREE) {
                    double w = W36Silent.wcWeight(true, weight, true, W36Silent.wcUnder(W36Silent.treePicks, W36Silent.picks, W36Silent.WC_WANT_PCT));
                    if (w != weight) { W36Silent.wcFloored++; w36Wc = true; }
                    return w;
                }
                if (W36Silent.MINE2 && MineGuild.ON && MineGuild.serves(j.task.name()) && weight < W36Silent.FLOOR) {
                    Location top = Location.create(MineGuild.TOP[0], MineGuild.TOP[1], 0), in = Location.create(MineGuild.SPOT[0], MineGuild.SPOT[1], 0);
                    boolean near = top.getDistance(bot.getLocation()) <= W36Silent.GUILD2_R && Gates.canEnter(bot, in);
                    double w = W36Silent.guildWeight(true, weight, true, bot.getSkills().getStaticLevel(Skills.MINING), MineGuild.LEVEL, MineGuild.crowd(), MineGuild.CAP, W36Silent.mineOpen(nowMs), near);
                    if (w != weight) { W36Silent.mineFloored++; w36Mine = true; }
                    return w;
                }
                if (W36Silent.FISH2 && W7Places.FISH_GUILD && !j.sharkRow && (j.task == AfkTasks.Task.CAGE || j.task == AfkTasks.Task.HARPOON) && weight < W36Silent.FLOOR) {
                    double w = W36Silent.guildWeight(true, weight, true, bot.getSkills().getStaticLevel(Skills.FISHING), W7Places.GUILD_LEVEL, W7Run.guildSeats()[0], W7Places.GUILD_CAP, W36Silent.fishOpen(nowMs), Gates.canEnter(bot, W7Run.GUILD_AT));
                    if (w != weight) { W36Silent.fishFloored++; w36Fish = true; }
                    return w;
                }
            } catch (Throwable ignored) { }
            return weight;
        }
        Job chooseJob() { boolean f = false; try { f = Scenes.Crews.filter(this); } catch (Throwable ignored) { } return chooseJob(f); } // batch 28: a crew leader looks near home first
        Job chooseJob(boolean crewFilter) {
            String sh = Seed.hunt(bot, slaySt.had != null); // W0 A0: a scenario world sends every bot to its hunt (seed flags on only)
            String sj = Seed.job(bot); // W8 herbSupply: a scenario world sends every bot to one non-hunt job (seed flags on only)
            if (sj != null) for (Job j : JOBS) if (j.hunt == null && j.task != null && j.task.name().equals(sj)) return j;
            if (sh != null) for (Job j : JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(sh) && Gates.canEnter(bot, j.hunt.spot)) return j;
            String th = System.getProperty("smartbots.testHunt"); // trials only: send rangers and mages to one monster
            if (th != null && styleOf(bot) != MELEE) for (Job j : JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(th) && Gates.canEnter(bot, j.hunt.spot)) { if (bot.getLocation().getDistance(j.hunt.spot) > 20) Bob.tele(bot).getProperties().setTeleportLocation(j.hunt.spot); return j; }
            List<String> dbg = new ArrayList<>();
            if (W36Silent.MINE2 || W36Silent.FISH2 || W36Silent.WC2) { w36Mine = false; w36Fish = false; w36Wc = false; } // w36-silent2: which kinds this choice floored
            Job best = null;
            double bestScore = 0;
            int combat = effectiveCombat();
            long nowMs = System.currentTimeMillis();
            boolean broke = broke();
            boolean brokeFree = BROKE_JOB && broke; Job bestFree = null; double bestFreeScore = 0; // P40 B4
            final BrokeSkip zs = brokeFree ? zeroSkip : null; final int zsTick = zs != null ? GameWorld.getTicks() : 0; Job zsJob = null; double zsScore = 0; boolean repZs = false; // W4 guards: the best free job inside its zero-yield cooldown
            Job zsFar = null; double zsFarScore = 0; // W9 guards2 (zeroSkip only): the best free job past the broke radius, for a cooldown with nothing free inside it
            Job slayJob = null; double slayDist = 1e9; // P54 v1.1 task trip: the nearest matching hunt past every gate
            int[] planBudget = {PLAN_PER_CHOICE}; // batch 26 fix 3: new (uncached) plans this choice may run
            Job repJ = null; double repScore = 0, repDist = 0, nearMoney = Double.MAX_VALUE; boolean repMoney = false, repFree = false; // brain2 rule 2 (Brain2)
            final boolean farmPull = Farming.ON && farmDue != 0 && nowMs >= farmDue && farm == 0 && farmSite >= 0; java.util.Set<Job> pulledJ = null; // C8 pull home
            Job shareRaw = null, shareRawFree = null; double shareRawScore = 0, shareRawFreeScore = 0; java.util.Set<Job> flooredJ = null; // w4-jobshare (only touched under W3Skills.SHARE_ON): the best picks with thieving undamped, and the log jobs the floor lifted
            int steerOwn = 0; java.util.Set<Job> steeredJ = null; // W2 lane M combat steer (only touched under FightRules.STEER): 0 not asked yet, 1 owns a usable combat pouch, -1 does not
            for (Job j : JOBS) {
                if (j.rcRow) { double rs = RcRuns.score(this, j, combat, broke, RNG, nowMs); if (rs > bestScore) { bestScore = rs; best = j; } continue; } // W8 (-Dbots.rc.runs): the rune-run row is scored by RcRuns only (the row exists only with the flag on)
                if (!reachable(j)) continue; // behind a quest, guild, key or toll it doesn't have (it used to buy food and bait first, then abort at the gate; review 10-22)
                if (j.hunt != null && j.hunt.tag != null && !(Slayer.ON && Slayer.match(this, j))) continue; // slayer8: an extra spot is for the holders of its task (none exist with the flag off)
                if (W6Slayer.ON && j.hunt != null && W6Slayer.closed(this, j.hunt, combat) != null) continue; // W6: a Vannaka hunt's combat/prayer/food gate
                double weight = jobWeight(j);
                if (W3Skills.SHARE_ON && j.hunt == null) { w3Shares(); double w0 = weight; weight = W3Skills.logWeight(true, weight, j.task.kind == AfkTasks.Kind.TREE, W3Skills.logNow, W3Skills.LOG_WANT, W3Skills.LOG_FLOOR); if (weight != w0) { W3Skills.logFloored++; if (flooredJ == null) flooredJ = new java.util.HashSet<>(); flooredJ.add(j); } } // W3 fix 4 (-Dbots.jobShare): log jobs keep a floor weight until 8% of bots cut wood
                if (W5aSkills.MINE_POWER && j.hunt == null && j.task == AfkTasks.Task.IRON && weight <= 0 && (weight = W5aSkills.powerWeight(true, true, weight, W5aSkills.ironGlut(MarketBrain.stockOf(W5aSkills.IRON_ORE), MarketBrain.target(W5aSkills.IRON_ORE)), bot.getSkills().getStaticLevel(Skills.MINING))) <= 0) W5aSkills.powerNoGlut++; // W5a (-Dbots.mine.power): iron in glut still trains an under-60 miner (drop mode)
                if (W7cSkills.THIEVE_ARDY && j.hunt == null && W7cSkills.ardyKey(j.task.key)) weight = Math.max(weight, W7cSkills.ARDY_WEIGHT); // W7c (-Dbots.thieve.ardy): coins and most seeds carry no bounty: a floor weight, under the seats, the hour cap and jobShare's thieving damp
                if ((W8Polish.WC_POWER || W8Polish.FISH_POWER) && j.hunt == null) { int w8k = W8Polish.powerKind(W8Polish.WC_POWER, W8Polish.FISH_POWER, j.task == AfkTasks.Task.WILLOW, j.task == AfkTasks.Task.LURE); if (w8k != 0) weight = W8Polish.powerWeight(true, weight, w8Glut(j.task), bot.getSkills().getStaticLevel(w8k == W8Polish.KIND_WC ? Skills.WOODCUTTING : Skills.FISHING), w8k == W8Polish.KIND_WC ? W8Polish.WC_POWER_MAX : W8Polish.FISH_POWER_MAX); } // W8 (-Dbots.wc.power / -Dbots.fish.power): willows / trout in glut still train a low-level gatherer (drop mode)
                if ((W36Silent.MINE2 || W36Silent.FISH2 || W36Silent.WC2) && j.hunt == null) weight = w36Weight(j, weight, nowMs); // w36-silent2 (-Dbots.mine.guild2, -Dbots.fish.guild2, -Dbots.wc.share2): a floor weight for guild and log jobs the market does not want
                boolean coinJob = j.hunt == null && j.task.kind == AfkTasks.Kind.THIEVE && j.task.products != null && j.task.products.length > 0 && j.task.products[0] == 995;
                if (weight <= 0 && !(broke && coinJob) && !(Slayer.ON && Slayer.match(this, j))) continue; // coins are never "wanted" by the market, but a skint bot needs them; P54 v1.1: a slayer task hunt is wanted for the task (cows, bats drop only surplus)
                if (j.hunt != null && j.hunt.name.toLowerCase(Locale.ROOT).contains("dragon") && !hasAntifire()) continue; // never without a shield
                if (j.hunt != null && combat < 70 && poisonous(j.hunt.name)) continue; // S11: poison killed two bots on their first night; leave those to the strong
                if (j.hunt != null && deadlyFor(j.hunt, combat)) continue;
                if (j.hunt != null && Resources.skipHunt(bot, j.hunt.spot)) continue; // P49: -Dhunt.skipUnreachable (pocket, Mort Myre south)
                int spotDeaths = recentDeaths(j.label());
                if (spotDeaths >= 2) continue; // two of us died there today: nobody goes back for a day
                if (j.hunt != null) {
                    int need = j.level + 10 * spotDeaths;
                    if (magicUser(j.hunt.name)) need = Math.max(need, j.hunt.npcLevel * 2 + 10);
                    if (combat < need) continue;
                }
                if (blacklist.getOrDefault(j.label(), 0L) > nowMs) continue; // a spot that killed us recently
                if (!stuckLegs.isEmpty() && stuckLegs.getOrDefault(legKey(j.label(), bot.getLocation()), 0L) > nowMs) continue; // batch 26 fix 2: stuck on the way there from here lately
                if (j.hunt != null && (P87Fight.resting(this) || P87Fight.closedByFood(this, j.hunt, combat))) continue; // P87 (flags off: false)
                if (j.hunt != null && MORY_FOOD_MIN > 0 && Preflight.ON && Preflight.morytania(j.hunt.spot) && foodFor(j.hunt) < MORY_FOOD_MIN) continue; // batch 22-1: food check before (re-)entering Morytania; the shop trip of another job fills the bank first
                if (j.skill >= 0 && bot.getSkills().getStaticLevel(j.skill) < j.level) continue;
                if (j.skill < 0 && combat < j.level) continue;
                if (crewFilter && !Scenes.Crews.nearHome(crew, j)) continue; // batch 28: crews hunt within 120 tiles of home (fallback below)
                double dist = 400;
                if (j.hunt != null) { // dungeon spots (y + 6400 under the surface) count from the ground above, or every dungeon looks 6,000 tiles away
                    Location sp = j.hunt.spot, me = bot.getLocation();
                    Location a1 = sp.getY() > 6400 ? Location.create(sp.getX(), sp.getY() - 6400, 0) : sp, b1 = me.getY() > 6400 ? Location.create(me.getX(), me.getY() - 6400, 0) : me;
                    dist = a1.getDistance(b1);
                }
                else for (AfkTasks.Spot s : AfkTasks.SPOTS.get(j.task)) dist = Math.min(dist, s.at.getDistance(bot.getLocation()));
                if (Brain2.ON && j.hunt != null && brain2Gate(bot, j, planBudget, true) != null) continue; // brain2 rule 1: no bank the trip could use from the spot (the trip's own nearestBank0 test), or no planned bank -> spot route: refused before any fare
                if (PLAN_JOBS && dist > PLAN_FROM) { // batch 26 fix 3: the walk is the planned length (1.5-2x the straight line), not the straight line
                    int pl = plannedTiles(bot, j.hunt != null ? j.hunt.spot : nearestSpot(j), planBudget, j.label());
                    if (pl == -1 && PLAN_NO_ROUTE) continue;
                    if (pl == -1 && P73_FIX && j.hunt != null && (Ledger.Wallet.coins(bot) < 5_000 || dist <= AfkTasks.Runner.TELEPORT_MIN)) { p73Count("hunt-noroute", P73_HUNT); continue; } // P73 B2: a hunt spot with no walkable route (a walk into a wall; a 320+ tile trip with 5k+ is a teleport and stays) // no walkable route on the planner's grid: not a job to walk to
                    if (pl > 0) dist = Math.max(dist, pl);
                }
                // A trip it can only make by teleport (320+ tiles, or across the Salve into Morytania from the west) needs the fare in the
                // bank first: a 3 gp fighter walked for Mort Myre snails and then undead chickens from Al Kharid and stood at the gate 27 min (audit §13)
                boolean acrossSalve = j.hunt != null && j.hunt.spot.getX() >= 3400 && j.hunt.spot.getY() < 6400 && bot.getLocation().getX() < 3400;
                long coinsNow = Ledger.Wallet.coins(bot);
                if (acrossSalve && coinsNow < Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556)) + 200) continue;
                if (Preflight.ON && coinsNow < Preflight.fare() + 200 && Preflight.leavingMorytania(bot.getLocation(), j.hunt != null ? j.hunt.spot : nearestSpot(j))) continue; // batch 20 A2: west out of Morytania needs the teleport fare
                if (dist > AfkTasks.Runner.TELEPORT_MIN && coinsNow < 5_000) continue; // under 5k the runner walks (start() sets walker), and a 320-tile walk is where bots get stuck
                int busy = j.hunt != null ? crowdHunt(j.hunt) : W5aSkills.SHARK ? crowdJob(j) : crowd(j.task);
                int party = crew != null && crew.leader == this ? crew.members.size() : 1;
                if (W5bSkills.STALLS && j.hunt == null && j.task.kind == AfkTasks.Kind.STALL) { // W5b (-Dbots.thieve.stalls): 3 seats for the whole Ardougne market, and combat 20+ (the guards hit back)
                    if (combat < W5bSkills.STALL_MIN_COMBAT) { W5bSkills.stallLowCombat++; continue; }
                    if (!W5bSkills.stallSeat(stallCrowd(), W5bSkills.STALL_SEATS)) { W5bSkills.stallFull++; continue; }
                }
                if (W7cSkills.THIEVE_ARDY && w7ArdyBlocked(j, combat, true)) continue; // W7c (-Dbots.thieve.ardy): seats per job, combat 20+, starts per hour
                if (busy + party > (Slayer.FIX8 ? Slayer.seats(j, Slayer.ON && Slayer.match(this, j)) : capacity(j))) continue; // the patch is full: two adamantite rocks can't feed five miners (slayer8: a task's holder counts 2 spawns per bot)
                if (j.hunt != null && j.hunt.npcLevel >= 80 && (bot.getSkills().getStaticLevel(Skills.PRAYER) < 43 || !hasAntifire())) continue; // the big things need prayer and a shield
                if (Slayer.ON && (Slayer.FIX8 ? Slayer.match(this, j) && Slayer.better(this, j, dist, slayJob, slayDist) : dist < slayDist && Slayer.match(this, j))) { slayDist = dist; slayJob = j; } // P54 v1.1 (flag off: never called); slayer8: a spot of only the task's monsters first
                double score = weight / (1 + dist / 150.0) * (0.8 + RNG.nextDouble() * 0.4);
                if (lastJob != null && (j == lastJob || lastJob.chain != 0 && j.hunt == null && lastJob.task == j.task)) score *= 0.5; // vary the day (P78: a chain job counts as its raw job)
                score /= 1 + busy; // don't all pile onto the same patch
                if (broke) { // money first: whatever sells for most that the bot can get
                    // Expected coins for one trip: gathering ~20 units of its best product; hunts ~15 kills x the drop table's
                    // expected value (a rare 5k drop at 1% is worth 50, not 5,000); pickpocketing pays coins on the spot and
                    // nothing waits in a queue, so it counts double for a bot that is broke now.
                    double v = 0;
                    if (j.hunt != null) { double ev = 0; for (int k = 0; k < j.hunt.products.length; k++) if (j.hunt.products[k] != 995 && !MarketBrain.isSurplus(j.hunt.products[k])) ev += MarketBrain.value(j.hunt.products[k]) * j.hunt.chance[k]; v = ev * 15; }
                    else { int top = 0; for (int id : j.products()) if (id != 995 && !MarketBrain.isSurplus(id)) top = Math.max(top, MarketBrain.value(id)); v = top * 20.0; }
                    if (coinJob) v = Math.max(v, 2 * 40 * (j.task == AfkTasks.Task.WARRIOR ? 18 : j.task == AfkTasks.Task.FARMER ? 9 : j.task == AfkTasks.Task.KNIGHT ? W7cSkills.KNIGHT_COINS : 3)); // W7c: a knight's pocket is 50 gp (the row exists only with -Dbots.thieve.ardy)
                    if (Brain2.ON) { if (v > 0 && dist < nearMoney) nearMoney = dist; if (lastJob != null && (j == lastJob || lastJob.chain != 0 && j.hunt == null && lastJob.task == j.task)) { repJ = j; repMoney = v > 0; repDist = dist; } } // brain2 rule 2: the nearest money job, and the last job's place in it
                    if (BRAIN_FIX1) v = Math.max(v, 1) * (0.8 + RNG.nextDouble() * 0.4) * (lastJob != null && (j == lastJob || lastJob.chain != 0 && j.hunt == null && lastJob.task == j.task) ? 0.5 : 1.0);
                    score = v / (1 + dist / 150.0) / (1 + busy);
                } else {
                    if (j.skill == fav) score *= 1.6; // follows the trade: a miner mines even when lobsters pay better
                    String bk = bot.getUsername().toLowerCase(Locale.ROOT);
                    if (!VETERAN.getOrDefault(bk, false) && !HIGH_TIER.getOrDefault(bk, false) && home >= 0 && home < BotLife.Town.values().length) {
                        Location hl = BotLife.Town.values()[home].spot; double hd = 999; // fresh bots stay round home, like a new player
                        if (j.hunt != null) { Location sp = j.hunt.spot; hd = (sp.getY() > 6400 ? Location.create(sp.getX(), sp.getY() - 6400, 0) : sp).getDistance(hl); }
                        else for (AfkTasks.Spot s : AfkTasks.SPOTS.get(j.task)) hd = Math.min(hd, s.at.getDistance(hl));
                        if (hd <= 180) score *= 1.5;
                    }
                    if (j.hunt != null && grudgeFoe(bot, j.hunt.name)) score *= 1.4; // personal
                    if (crew != null && crew.leader == this && j.skill < 0) score *= 1.5; // crews are for fighting
                    if (HIGH_TIER.getOrDefault(bot.getUsername().toLowerCase(Locale.ROOT), false)) { // a combat 110 doesn't pick potatoes
                        if (j.hunt != null && j.hunt.npcLevel >= 80) score *= 3; else if (j.hunt != null && j.hunt.npcLevel >= 40) score *= 1.5; else if (j.level < 40) score *= 0.2;
                    }
                    int shortOf = 0; for (int id : j.products()) shortOf = Math.max(shortOf, Artisans.SHORT.getOrDefault(id, 0));
                    if (shortOf > 0) score *= 1 + 0.25 * Math.min(4, shortOf); // the smiths are waiting on this
                }
                double shareSc = score; // w4-jobshare: the score before the thieving damp
                if (W3Skills.SHARE_ON && j.hunt == null && j.task.kind == AfkTasks.Kind.THIEVE) { w3Shares(); double tf = W3Skills.thieveFactor(true, W3Skills.thieveNow, W3Skills.THIEVE_CAP); if (tf < 1 && W3Skills.brokeCoinKeep(W3Skills.BROKE_COINS, broke, coinJob)) { tf = 1; W3Skills.brokeKept++; } if (tf < 1) { score *= tf; W3Skills.thieveDamped++; } } // W3 fix 4: x0.1 while 15% of bots already pickpocket; W9 guards2 (-Dbots.jobShare.brokeCoins, default true): not a broke bot's coin job
                if (farmPull) { Location fl = j.hunt != null ? j.hunt.spot : nearestSpot(j); if (fl != null && Farming.nearestSite(fl) == farmSite) { score *= Farming.PULL; pulledJ = pulledJ == null ? new java.util.HashSet<>() : pulledJ; pulledJ.add(j); } } // C8 pull home: a due CHECK favours jobs in the farm's city, so the trip ends at a bank near it (farmDue stays 0 with the flag off)
                if (Hubs.on()) score *= Hubs.jobFactor(j, bot.getLocation()); // batch 27: x1.3 near a hub under its band 2 windows, x0.8 near one over it (off/none flagged: untouched)
                if (FightRules.STEER) { // W2 lane M (-Dbots.summoning.combatSteer): a combat pouch owner prefers fight jobs at a multi-combat spot, where the familiar fights by itself
                    if (steerOwn == 0) steerOwn = Bob.steerOwner(bot) ? 1 : -1;
                    double sf = FightRules.steerFactor(true, fightJob(j), steerOwn > 0 && fightJob(j) && steerMultiJob(j), j.hunt != null && Bob.wild(j.hunt.spot), steerOwn > 0, FightRules.STEER_X);
                    if (sf != 1.0) { score *= sf; FightRules.STEER_SEEN++; if (steeredJ == null) steeredJ = new java.util.HashSet<>(); steeredJ.add(j); }
                }
                if (DEBUG_JOBS && HIGH_TIER.getOrDefault(bot.getUsername().toLowerCase(Locale.ROOT), false)) dbg.add(String.format("%s=%.2f", j.label(), score));
                final boolean zsSkip = zs != null && zs.skipping(j, zsTick); // W4 guards (always false with the flag off)
                if (zsSkip) { if (score > zsScore && freeJob(j, dist)) { zsScore = score; zsJob = j; } }
                else if (brokeFree && score > bestFreeScore && freeJob(j, dist)) { bestFreeScore = score; bestFree = j; } // P40 B4
                if (zs != null && !zsSkip && dist > BROKE_RADIUS && score > zsFarScore && freeJob(j, BROKE_RADIUS)) { zsFarScore = score; zsFar = j; } // W9 guards2: free but past the radius (no RNG, flag off: never)
                if (repJ == j) { repScore = score; repFree = brokeFree && freeJob(j, dist); if (zsSkip && repFree) { repFree = false; repZs = true; } } // brain2 rule 2 (repJ is only set with Brain2.ON)
                Job pickJ = j; if (Batch29.on(Batch29.CHAINS)) try { Job c = Batch29.Chains.pick(this, j, score); if (c != null) { pickJ = c; score = Batch29.Chains.lastScore; } } catch (Throwable ignored) { } // P78: the chain job when it fits and pays
                if (score > bestScore) { bestScore = score; best = pickJ; }
                if (W3Skills.SHARE_ON) { if (shareSc > shareRawScore) { shareRawScore = shareSc; shareRaw = j; } if (brokeFree && shareSc > shareRawFreeScore && freeJob(j, dist)) { shareRawFreeScore = shareSc; shareRawFree = j; } } // w4-jobshare: what it would pick undamped
            }
            if (repJ != null && Brain2.keepRepeat(Brain2.ON, broke, repMoney, repDist, nearMoney)) { // brain2 rule 2: a broke bot's last job, when it is its nearest money job, competes at its full score (the x0.5 undone; jitter kept)
                double full = Brain2.fullScore(repScore); Job pk = repJ; Brain2.repeatKept++;
                if (repFree && full > bestFreeScore) { bestFreeScore = full; bestFree = repJ; }
                if (repZs && full > zsScore) { zsScore = full; zsJob = repJ; } // W4 guards
                if (Batch29.on(Batch29.CHAINS)) try { Job c = Batch29.Chains.pick(this, repJ, full); if (c != null) { pk = c; full = Batch29.Chains.lastScore; } } catch (Throwable ignored) { }
                if (full > bestScore) { bestScore = full; best = pk; }
            }
            if (zs != null && zsJob != null) { // W4 guards: the next free candidate; none: the resting job, as before
                Job take = zs.resolve(bestFree, zsJob, zsFar); // W9 guards2: the far free job before the resting one
                if (take == zsJob) { bestFree = zsJob; bestFreeScore = zsScore; if (zs.logDue(zsTick)) System.out.println("[Broke] " + bot.getUsername() + " keeps " + zsJob.label() + " (free) after 0 collected: no other free job (" + BrokeSkip.counts() + ")"); }
                else { boolean far = bestFree == null; if (far) { bestFree = zsFar; bestFreeScore = zsFarScore; } if (zs.logDue(zsTick)) System.out.println("[Broke] " + bot.getUsername() + " skips " + zsJob.label() + " (free) for " + BrokeSkip.minutes() + " min after 0 collected, takes " + bestFree.label() + (far ? " (past the broke radius)" : "") + " (" + BrokeSkip.counts() + ")"); }
            }
            if (W17b.ON && brokeFree && best != null && best.rcRow && W17b.mine(best) && bestScore > bestFreeScore) bestFree = null; // w18 (-Dbots.rc.natureLaw): a broke bot keeps the nature row when it out-earns its best free job (two fares, gated in W17b; the row exists only with the flag on)
            if (brokeFree && bestFree != null) { // P40 B4: broke: the best free job, not the best-paying one it would have to buy into
                if (bestFree != best) System.out.println("[Broke] " + bot.getUsername() + " at " + Ledger.Wallet.coins(bot) + " gp takes " + bestFree.label() + " (free) over " + (best == null ? "nothing" : best.label()));
                best = bestFree; bestScore = bestFreeScore;
                if (zs != null) zs.picked = bestFree; // W4 guards
            }
            if (W3Skills.SHARE_ON && best != null) { Job raw = brokeFree && shareRawFree != null ? shareRawFree : shareRaw; // w4-jobshare: one counted, rate-limited line when the rule changed the pick
                String why = W3Skills.shareWhy(flooredJ != null && flooredJ.contains(best), raw != null && raw != best && raw.hunt == null && raw.task.kind == AfkTasks.Kind.THIEVE, best.hunt == null && best.task.kind == AfkTasks.Kind.THIEVE, W3Skills.thieveNow, W3Skills.THIEVE_CAP);
                if (why != null) W3Skills.sharePick(bot.getUsername(), best.label(), raw == null ? "nothing" : raw.label(), why);
            }
            if (FightRules.STEER && best != null && steeredJ != null && steeredJ.contains(best)) { FightRules.STEER_PICKS++; System.out.println("[Steer] " + bot.getUsername() + " picks " + best.label() + " (multi-combat, x" + FightRules.STEER_X + ", " + steeredJ.size() + " steered jobs in reach)"); } // W2 lane M: a later slayer task trip or quest offer can still replace it
            if (farmPull && best != null && pulledJ != null && pulledJ.contains(best)) { Farming.PULLS++; System.out.println("[Farm] " + bot.getUsername() + " pulled home toward " + Farming.SITES[farmSite].key.toLowerCase(Locale.ROOT) + " (check due " + (nowMs - farmDue) / 60_000 + " min): " + best.label()); }
            if (Slayer.ON && slayJob == null && !cjFell) Slayer.noMatch(this, nowMs);
            if (crewFilter) cjRolled = BRAIN_FIX1 && Slayer.ON && slayJob != null && !farmPull;
            if (Slayer.ON && slayJob != null && !farmPull && !(cjFell && cjRolled) && Slayer.taskTrip(this, slayJob, broke)) { if (crewFilter) Scenes.Crews.picked(this, slayJob); return slayJob; } // P54 v1.1 task trip, 1 in 2
            if (crewFilter) { if (best == null) { Scenes.Crews.fellBack(this); if (BRAIN_FIX1) { cjFell = true; try { return chooseJob(false); } finally { cjFell = false; cjRolled = false; } } return chooseJob(false); } Scenes.Crews.picked(this, best); } // batch 28: nothing near home: the nearest job, logged
            try { questPick = QuestBook.pick(bot, bestScore, broke); } catch (Throwable t) { questPick = null; System.out.println("[Quests] pick for " + bot.getUsername() + ": " + t); }
            if (questPick != null) return null; // think() starts it
            if (DEBUG_JOBS && !dbg.isEmpty()) { dbg.sort((x, y) -> Double.compare(Double.parseDouble(y.substring(y.lastIndexOf('=') + 1)), Double.parseDouble(x.substring(x.lastIndexOf('=') + 1)))); System.out.println("[JobDbg] " + bot.getUsername() + " top: " + dbg.subList(0, Math.min(6, dbg.size()))); }
            if (broke && best != null && nowMs > nextBrokeTalk) { say(String.format(BotChat.pick("Skint. Need coin for a new %s. %s it is.", "Down to my last few coins. %s can wait, %s pays.", "Broke again. A new %s needs coins. %s.", "Pockets empty. %2$s should fix that."), fav >= 0 ? "pick" : "sword", best.label().toLowerCase(Locale.ROOT))); nextBrokeTalk = nowMs + 3_600_000; }
            return best;
        }

        static final int[] SHOP_FOODS = {379, 333, 329, 2309, 315, 1891, 2142}; // lobster, trout, salmon, bread, shrimps, cake, cooked meat

        // Matched armour sets a player would buy from NPC armour shops: {Attack/Defence level, helm, body, legs, kiteshield, scimitar}.
        // Slots: 0 helm, 4 body, 7 legs, 5 shield, 3 weapon. the owner's review 2026-10-20: 58 of 102 bots wore nothing and
        // the high tier wore only an anti-dragon shield, because unaffordable/unstocked gear was stripped and never replaced.
        static final int[][] ARMOUR_TIERS = {
                {40, 1163, 1127, 1079, 1201, 1333},   // rune
                {30, 1161, 1123, 1073, 1199, 1331},   // adamant
                {20, 1159, 1121, 1071, 1197, 1329},   // mithril
                {5, 1157, 1119, 1069, 1193, 1325},    // steel
                {1, 1153, 1115, 1067, 1191, 1323},    // iron
                {1, 1155, 1117, 1075, 1189, 1321}};   // bronze
        static final int[] ARMOUR_SLOTS = {0, 4, 7, 5, 3};

        /** The pieces the bot is missing, from the best matched tier its levels allow and its purse can stand (≤ 60% of its coins). */
        /** Somewhere a player could buy it: an NPC shop that stocks it, or the Exchange. */
        static boolean sold(int id) {
            if (MarketBrain.stockOf(id) > 0) return true;
            for (Object[] shop : BotChat.WorldData.shops) for (int sid : (int[]) shop[3]) if (sid == id) return true;
            return false;
        }

        static final String[] METAL = {"bronze", "iron", "steel", "black", "mithril", "adamant", "rune", "dragon", "white"};

        static boolean metal(Item it) {
            String n = it == null || it.getName() == null ? "" : it.getName().toLowerCase(Locale.ROOT);
            for (String m : METAL) if (n.startsWith(m)) return true;
            return false;
        }

        static final int[] BOWS = W6Combat.KIT2 ? W6Combat.BOWS2 : new int[]{841, 843, 849, 853, 857};       // W6 (-Dbots.ranged.kit2): + magic shortbow, rune arrows; off, train-61's tables // shortbow, oak, willow, maple, yew (train-5 F4; ignored with data/style5.off)
        static final int[] BOW_LEVEL = W6Combat.KIT2 ? W6Combat.BOW_LEVEL2 : new int[]{1, 5, 20, 30, 40};
        static final int[] ARROWS = W6Combat.KIT2 ? W6Combat.ARROWS2 : new int[]{882, 884, 886, 888, 890};      // bronze, iron, steel, mithril, adamant
        static final int[] ARROW_LEVEL = W6Combat.KIT2 ? W6Combat.ARROW_LEVEL2 : new int[]{1, 1, 5, 20, 30};
        static final int[] ARROW_MAX_BOW = W6Combat.KIT2 ? W6Combat.ARROW_MAX_BOW2 : new int[]{0, 0, 1, 2, 3};         // weakest bow that can fire each arrow
        /** train-5: the trip's arrow and spell (F1, picked to the purse), melee this trip (F2 fallback), autocast armed at the trip start (F3). */
        int tripArrow; int[] tripSpell; boolean meleeFallback, castTrip;

        int bestBow() { int r = bot.getSkills().getStaticLevel(Skills.RANGE), b = 0, top = style5() ? BOWS.length : W6Combat.KIT2 ? W6Combat.NO_STYLE5_BOWS : BOWS.length - 1; for (int i = 0; i < top; i++) if (r >= BOW_LEVEL[i]) b = i; return b; }

        /** F4: the bow the kit asks for; the yew only when the Exchange has one or a shop sells it (else maple, never waiting for a bow nobody sells). */
        int kitBow() {
            int b = bestBow();
            if (W6Combat.KIT2 && BOWS[b] == W6Combat.MAGIC_SHORTBOW && !w6OnSale(W6Combat.MAGIC_SHORTBOW)) b--; // W6 (-Dbots.ranged.kit2): the magic shortbow only when one is on sale, else the yew rule below
            if (BOWS[b] == 857 && MarketBrain.stockOf(857) <= 0) {
                boolean sold = false;
                try { for (Object[] shop : BotChat.WorldData.shops) for (int sid : (int[]) shop[3]) if (sid == 857) sold = true; } catch (Throwable ignored) { }
                if (!sold) b--;
            }
            return b;
        }

        /** W6 (-Dbots.ranged.kit2): is this kit piece on sale: bot stock on the Exchange, or a shop's list. */
        static boolean w6OnSale(int id) {
            if (MarketBrain.stockOf(id) > 0) return true;
            try { for (Object[] shop : BotChat.WorldData.shops) for (int sid : (int[]) shop[3]) if (sid == id) return true; } catch (Throwable ignored) { }
            return false;
        }

        int bestArrow() {
            int r = bot.getSkills().getStaticLevel(Skills.RANGE), bow = 0;
            Item w = bot.getEquipment().get(3);
            for (int i = 0; i < BOWS.length; i++) if (w != null && w.getId() == BOWS[i]) bow = i;
            int a = 0; for (int i = 0; i < ARROWS.length; i++) if (r >= ARROW_LEVEL[i] && ARROW_MAX_BOW[i] <= bow) a = i;
            return ARROWS[a];
        }

        /** {spell id, rune per cast, amount, rune, amount}: fire bolt / fire strike / wind strike, cast with a staff of fire. */
        int[] spell() {
            int m = bot.getSkills().getStaticLevel(Skills.MAGIC);
            if (m >= 59) return new int[]{38, 560, 1, 556, 4};   // fire blast: death runes (the runecrafter makes them)
            if (m >= 35) return new int[]{20, 562, 1, 556, 3};
            if (m >= 13) return new int[]{8, 558, 1, 556, 2};
            return new int[]{1, 558, 1, 556, 1};
        }

        /** A ranger's or mage's kit: {slot, item}. Metal armour on those slots is swapped out like a player would. */
        List<int[]> styleKit(int st) {
            int r = bot.getSkills().getStaticLevel(Skills.RANGE), d = bot.getSkills().getStaticLevel(Skills.DEFENCE);
            List<int[]> kit = new ArrayList<>();
            if (st == RANGED) {
                boolean hide = r >= 40 && d >= 40;
                kit.add(new int[]{0, r >= 20 ? 1169 : 1167});
                kit.add(new int[]{4, hide ? (W6Combat.KIT2 ? W6Combat.hidePick(0, r, Brain::w6OnSale) : 1135) : 1129}); // W6 (-Dbots.ranged.kit2): blue/red/black dragonhide at 50/60/70 when on sale
                kit.add(new int[]{7, r >= 40 ? (W6Combat.KIT2 ? W6Combat.hidePick(1, r, Brain::w6OnSale) : 1099) : 1095});
                kit.add(new int[]{9, r >= 40 ? (W6Combat.KIT2 ? W6Combat.hidePick(2, r, Brain::w6OnSale) : 1065) : 1063});
                kit.add(new int[]{3, BOWS[kitBow()]});
            } else {
                Item w = bot.getEquipment().get(3);
                if ((w == null || w.getId() != 1387) && !(W6Magic.ANY_STAFF && W6Magic.keepBasic(true, w == null ? -1 : w.getId(), Ledger.Wallet.coins(bot), MarketBrain.value(1387)))) { List<int[]> staff = new ArrayList<>(); if (!style5() || Ledger.Wallet.coins(bot) - Math.max(1, MarketBrain.value(1387)) >= 300) staff.add(new int[]{1387, 1}); return staff; } // the staff first (Zaff's); F2: only when it can pay and keep 300
                kit.add(new int[]{0, 579}); kit.add(new int[]{7, 1011});
                if (MarketBrain.stockOf(577) > 0) kit.add(new int[]{4, 577}); // no shop sells the robe top; only if someone made one
            }
            boolean f2 = style5(); long coins = Ledger.Wallet.coins(bot);
            List<int[]> out = new ArrayList<>(); long cost = 0, purse = coins - 300;
            for (int[] k : kit) {
                Item cur = bot.getEquipment().get(k[0]);
                if (k[0] == 3 && st == RANGED && W10Skills.THROWN && w10Thrown()) continue; // W10 lane M (-Dbots.ranged.thrown): no bow bought over its darts/knives
                if (cur != null && cur.getId() == k[1]) continue;
                if (k[0] == 0 && stashHelm != 0) continue;
                boolean replace = cur == null || metal(cur) || k[0] == 3 || W6Combat.KIT2 && st == RANGED && W6Combat.hideUpgrade(cur.getId(), k[1]); // the weapon is the style (W6 kit2: leather or a lower hide gives way to a better hide)
                if (k[0] == 3 && cur != null && st == RANGED) for (int i = 0; i < BOWS.length; i++) if (cur.getId() == BOWS[i] && i >= kitBow()) replace = false;
                if (!replace) continue;
                long v = Math.max(1, MarketBrain.value(k[1]));
                if (f2 && k[0] == 3 && st == RANGED) { // F2 weapon first: exempt from the 0.6 rule, bought whenever it leaves 300; else the best bow down the table that does
                    int i = bowIndex(k[1]); while (i > 0 && coins - Math.max(1, MarketBrain.value(BOWS[i])) < 300) i--;
                    v = Math.max(1, MarketBrain.value(BOWS[i]));
                    if (coins - v < 300) continue;
                    if (cur != null && bowIndex(cur.getId()) >= i) continue; // already wears that bow or a better one
                    out.add(0, new int[]{BOWS[i], 1}); continue; // first in the list, so the armour can't spend its coins; not in cost
                }
                if (cost + v > purse * 0.6) continue;
                cost += v; out.add(new int[]{k[1], 1});
            }
            return out;
        }

        List<int[]> armourNeeds() {
            int st = styleOf(bot);
            if (st != MELEE) return styleKit(st);
            int att = bot.getSkills().getStaticLevel(Skills.ATTACK), def = bot.getSkills().getStaticLevel(Skills.DEFENCE);
            long purse = Ledger.Wallet.coins(bot) - 300; // keep something for food and tools
            // Try the best tier its levels allow first; per slot, take the best piece at or below that tier that some shop
            // actually sells (rune helms and swords aren't in any shop we know, so those slots fall back to adamant/mithril).
            for (int cap = 0; cap < ARMOUR_TIERS.length; cap++) {
                if (def < ARMOUR_TIERS[cap][0] || att < ARMOUR_TIERS[cap][0]) continue;
                List<int[]> want = new ArrayList<>(); long cost = 0;
                for (int k = 0; k < ARMOUR_SLOTS.length; k++) {
                    int slot = ARMOUR_SLOTS[k];
                    if (bot.getEquipment().get(slot) != null || slot == 0 && stashHelm != 0 || slot == 5 && stashShield != 0) continue; // never replace what it wears (anti-dragon shield etc.)
                    for (int t = cap; t < ARMOUR_TIERS.length; t++) {
                        int id = ARMOUR_TIERS[t][k + 1];
                        if (!questOk(bot, id)) id = id == 1127 ? 1113 : id; // rune chainbody instead of the Dragon Slayer platebody
                        if (!sold(id)) continue;
                        want.add(new int[]{id, 1}); cost += Math.max(1, MarketBrain.value(id));
                        break;
                    }
                }
                if (want.isEmpty()) return upgrades(att, def, purse);             // dressed, or nothing for sale: maybe a better tier (rec-review 10-23 batch14 item1)
                if (cost <= purse * 0.6) return want;                             // the best set it can afford
            }
            return upgrades(att, def, purse); // rec-review 10-23 batch14 item1
        }

        // rec-review 10-23 batch14 item1: armour upgrades. A dressed melee bot whose Attack and Defence allow a better tier
        // than a worn table piece buys the next tier up for that slot (one tier step per shop visit, bronze -> iron -> steel ->
        // mithril -> adamant; rune stays with GOALS), the lot capped at 0.4 of (coins - 300). Never a downgrade, never a
        // non-table piece (rune, dragon, black, quest gear, anti-dragon shield), never a stashed helm or shield slot, never a
        // piece it already owns, and at most one upgrade visit per trip (upgradeTrip). Old pieces worth 100 gp or more go to
        // the Exchange in wear() (hand-me-downs, #44); cheaper ones are sold at the next general store (oldToSell).
        int upgradeTrip = -1, sellTripTrip = -1;                    // the trip whose shop visit bought an upgrade; the trip a sell walk was made on
        final List<Integer> oldToSell = new ArrayList<>();          // cheap old pieces waiting for a general store
        static final int[] UPGRADE_ORDER = {4, 1, 2, 0, 3};         // ARMOUR_SLOTS index: weapon, body, legs, helm, shield

        static int tierOf(int k, int id) { for (int t = 0; t < ARMOUR_TIERS.length; t++) if (ARMOUR_TIERS[t][k + 1] == id) return t; return -1; }

        List<int[]> upgrades(int att, int def, long purse) {
            List<int[]> out = new ArrayList<>();
            if (upgradeTrip == trips || purse <= 0) return out;
            int allow = -1; // the best tier (rune excluded) its levels allow
            for (int t = 1; t < ARMOUR_TIERS.length && allow < 0; t++) if (def >= ARMOUR_TIERS[t][0] && att >= ARMOUR_TIERS[t][0]) allow = t;
            if (allow < 0) return out;
            long cost = 0;
            for (int k : UPGRADE_ORDER) {
                int slot = ARMOUR_SLOTS[k];
                if (slot == 0 && stashHelm != 0 || slot == 5 && stashShield != 0) continue;
                Item cur = bot.getEquipment().get(slot);
                int wt = cur == null ? -1 : tierOf(k, cur.getId());
                if (wt <= allow) continue; // empty (the fill above), not a table piece, or already as good as it may wear
                for (int t = wt - 1; t >= allow; t--) { // one step up; a tier no shop or Exchange has is stepped over
                    int id = ARMOUR_TIERS[t][k + 1];
                    if (!questOk(bot, id) || !sold(id)) continue;
                    if (held(id) > 0) break; // owns one already (pack or bank): no second copy
                    long v = Math.max(1, MarketBrain.value(id));
                    if (cost + v <= purse * 0.4) { cost += v; out.add(new int[]{id, 1}); }
                    break;
                }
            }
            return out;
        }

        /** rec-review 10-23 batch14 item1: wear a bought kit piece; an upgrade marks the trip, a cheap old piece waits for a general store. */
        boolean wearKit(int id) {
            int slot = -1;
            for (int k = 0; k < ARMOUR_SLOTS.length && slot < 0; k++) if (tierOf(k, id) >= 0) slot = ARMOUR_SLOTS[k];
            Item old = slot >= 0 ? bot.getEquipment().get(slot) : null;
            boolean ok = wear(bot, id);
            if (ok && old != null && old.getId() != id) {
                upgradeTrip = trips;
                boolean exchange = MarketBrain.value(old.getId()) >= 100 && MarketBrain.tradeable(old.getId()); // wear() put it on the Exchange
                if (!exchange && oldToSell.size() < 20) oldToSell.add(old.getId());
                System.out.println("[SmartAdv] " + bot.getUsername() + " upgraded " + MarketBrain.name(old.getId()).toLowerCase(Locale.ROOT) + " to " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + (exchange ? "; old one to the Exchange" : "; old one to sell at a general store"));
            }
            return ok;
        }

        static boolean generalStore(String name) { return name != null && name.toLowerCase(Locale.ROOT).contains("general store"); }

        /** rec-review 10-23 batch14 item1: at a general store, sell the cheap old pieces (pack first, then the bank) at 40% of value. */
        void sellOld() {
            if (oldToSell.isEmpty()) return;
            StringBuilder sold = new StringBuilder(); long got = 0;
            for (int id : new ArrayList<>(oldToSell)) {
                boolean had = bot.getInventory().remove(new Item(id, 1)) || bot.getBank().remove(new Item(id, 1));
                if (!had) continue; // already gone (sold, dropped at a death)
                long price = MarketBrain.value(id) * 4L / 10;
                if (price > 0) Ledger.Wallet.give(bot, price);
                got += price;
                if (sold.length() > 0) sold.append(", ");
                sold.append(MarketBrain.name(id).toLowerCase(Locale.ROOT));
            }
            oldToSell.clear();
            if (sold.length() > 0) System.out.println("[SmartAdv] " + bot.getUsername() + " sold old " + sold + " at " + shopName + " for " + got + " gp");
        }

        /** rec-review 10-23 batch14 item1: straight after an armour trip, the nearest general store (within 120 tiles) for the old pieces. */
        boolean sellTrip(Job j) {
            if (oldToSell.isEmpty() || sellTripTrip == trips) return false; // one sell walk per trip at most
            Object[] best = null; double bd = 120;
            for (Object[] shop : BotChat.WorldData.shops) { if (!generalStore((String) shop[0])) continue; if (!Preflight.ok(bot, Location.create((Integer) shop[1], (Integer) shop[2], 0))) continue; /* batch 20 A1 */ double d = Location.create((Integer) shop[1], (Integer) shop[2], 0).getDistance(bot.getLocation()); if (d < bd) { bd = d; best = shop; } }
            if (best == null) return false; // none near: kept for the next general store it visits
            shopAt = Location.create((Integer) best[1], (Integer) best[2], 0); shopName = (String) best[0];
            shopBuy.clear(); pendingJob = j; sellTripTrip = trips;
            shopDeadline = System.currentTimeMillis() + (FAST ? 180_000 : 480_000); shopBest = Double.MAX_VALUE; shopStalls = 0;
            final String sn = shopName;
            if (RNG.nextInt(2) == 0) say(String.format(BotChat.pick("Old kit's going to %s.", "Flogging the old stuff at %s.", "Quick stop at %s to sell my old gear."), sn));
            BotChat.activity(bot, () -> "selling old armour at " + sn);
            return true;
        }

        /** The shop that sells most of these pieces, nearest on ties (Horvik's, the Falador shops, Peksa's, Seddus...). */
        Object[] armourShop(List<int[]> pieces) {
            Object[] best = null; int bestN = 0; double bestD = 0;
            for (Object[] shop : BotChat.WorldData.shops) {
                int n = 0; for (int sid : (int[]) shop[3]) for (int[] pc : pieces) if (pc[0] == sid) n++;
                if (n == 0) continue;
                if (!Preflight.ok(bot, Location.create((Integer) shop[1], (Integer) shop[2], 0))) continue; // batch 20 A1
                double d = Location.create((Integer) shop[1], (Integer) shop[2], 0).getDistance(bot.getLocation());
                if (n > bestN || (n == bestN && d < bestD)) { best = shop; bestN = n; bestD = d; }
            }
            return best;
        }

        // ---- layer D: the sawmill. Logs, planks and the operator's fee (2009 prices). The fee is the coin sink.
        static final int[][] MILL = {{1521, 8778, 250}, {6333, 8780, 500}, {6332, 8782, 1500}}; // oak, teak, mahogany
        static final Location SAWMILL = Location.create(3302, 3489, 0), HOUSE_PORTAL = Location.create(2954, 3224, 0);
        static final Map<Integer, Integer> COOK_LEVEL = new java.util.HashMap<>();
        static {
            int[][] c = {{317, 1}, {327, 1}, {345, 5}, {335, 15}, {349, 20}, {331, 25}, {359, 30}, {377, 40}, {363, 43}, {371, 45}, {7944, 62}, {383, 80}};
            for (int[] x : c) COOK_LEVEL.put(x[0], x[1]);
        }
        /** Builders who asked a woodcutter for logs because the Exchange was short: {builder name -> log id}. */
        static final Map<String, Integer> LOG_ASKS = new java.util.concurrent.ConcurrentHashMap<>();
        static final Map<String, Long> LOG_ASKED_AT = new java.util.concurrent.ConcurrentHashMap<>();
        static final int MILL_WALK = 1, MILL_PAY = 2, MILL_HOME = 3, MILL_BUILD = 4;
        static volatile long FEES_PAID, PLANKS_BUILT, PLANKS_SOLD;

        /** Is this bot the sort that builds? Enough coins to spend on a house and either a veteran or the high tier. */
        boolean builder() {
            String key = bot.getUsername().toLowerCase(Locale.ROOT);
            return Ledger.Wallet.coins(bot) >= W3Skills.plankCoins(W3Skills.PLANK_FIX, 15_000) && (VETERAN.getOrDefault(key, false) || HIGH_TIER.getOrDefault(key, false)); // W3 fix 8 (-Dbots.plankFix): 9,000 (flag off: 15,000)
        }

        /** The Exchange is short of oak logs: ask a woodcutter nearby to bring some (an errand, paid at 95% of guide). */
        void askForLogs(long now) {
            String me = bot.getUsername().toLowerCase(Locale.ROOT);
            if (now - LOG_ASKED_AT.getOrDefault(me, 0L) < (FAST ? 120_000 : 45 * 60_000)) return;
            if (bot.getBank().getAmount(1521) >= 10 || MarketBrain.stockOf(1521) >= 40) return;
            LOG_ASKED_AT.put(me, now); LOG_ASKS.put(me, 1521);
            if (RNG.nextInt(2) == 0) say(BotChat.pick("Anyone cutting oaks? I'll buy 27 off you.", "Need oak logs for the house. Paying guide.", "Oak logs wanted. Bring them to me, good price."));
        }

        /** A woodcutter between trips takes a builder's log request, if one is near and it can cut the logs. */
        Job builderErrand(long now) {
            for (Map.Entry<String, Integer> e : LOG_ASKS.entrySet()) {
                Brain b = BRAINS.get(e.getKey());
                if (b == null || b == this || b.bot == null || !b.bot.isActive()) { LOG_ASKS.remove(e.getKey()); continue; }
                if (now - LOG_ASKED_AT.getOrDefault(e.getKey(), 0L) > 30 * 60_000) { LOG_ASKS.remove(e.getKey()); continue; }
                if (b.bot.getLocation().getDistance(bot.getLocation()) > 150) continue;
                Job job = jobFor(e.getValue());
                if (job == null) continue;
                LOG_ASKS.remove(e.getKey());
                takeErrand(b.bot, e.getValue(), job);
                return job;
            }
            return null;
        }

        /** Logs already in the bank (bought at a callout) or 27 from the Exchange when it has them: then off to the mill. */
        /** W5a (-Dbots.cook.fire): a tinderbox and one log in the pack for a cooking fish trip whose spot is far from every range.
         *  Tinderbox from the bank, else bought at its guide price (the shop way, like the tools above); the log from the bank,
         *  else one plain log from bot stock (Ledger.consume: paid, never created). No log: no kit, the range as before. */
        void w5FireKit(Player p, Location spotAt) {
            double rd = 999;
            for (Location rg : AfkTasks.Runner.RANGES) rd = Math.min(rd, rg.getDistance(spotAt));
            if (Batch29.on(Batch29.CHAINS)) for (Location rg : AfkTasks.Runner.BOT_RANGES) rd = Math.min(rd, rg.getDistance(spotAt));
            if (!W5aSkills.wantKit(true, true, rd)) return;
            int fm = p.getSkills().getStaticLevel(Skills.FIREMAKING);
            int log = 0; for (int[] l : FIRE_LOGS) if (fm >= l[1] && p.getInventory().getAmount(l[0]) > 0) { log = l[0]; break; }
            boolean tinder = p.getInventory().getAmount(590) > 0;
            if (!tinder && p.getInventory().freeSlots() >= 2) {
                if (p.getBank().getAmount(590) > 0) tinder = move(p.getBank(), p.getInventory(), 590, 1);
                else if (Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(590)))) { packOrBank(p, new Item(590)); tinder = p.getInventory().getAmount(590) > 0; }
            }
            if (tinder && log == 0 && p.getInventory().freeSlots() >= 1) {
                for (int[] l : FIRE_LOGS) if (fm >= l[1] && p.getBank().getAmount(l[0]) > 0 && move(p.getBank(), p.getInventory(), l[0], 1)) { log = l[0]; break; }
                if (log == 0 && MarketBrain.stockOf(1511) > 5 && Ledger.consume(p, 1511, 1, "firemaking") == 1) { packOrBank(p, new Item(1511)); if (p.getInventory().getAmount(1511) > 0) log = 1511; }
            }
            if (tinder && log != 0) W5aSkills.fireKits++; else W5aSkills.fireNoKit++;
        }

        /** W5a (-Dbots.fire.line): where a line starts, a few steps outside the bank (the bank-loop BURN runner refuses a spot
         *  with a bank booth within 5 tiles; light() steps to a free tile 1-3 away and stops after LIGHT_MAX bad tiles). */
        static final AfkTasks.Spot[] W5_LINES = {AfkTasks.sp("the Varrock west bank fire line", 3187, 3430), AfkTasks.sp("the Draynor bank fire line", 3099, 3251)};
        /** W32 (-Dbots.fm.moreSpots): the two above plus three more, each over 5 tiles from its bank's booths on open ground
         *  (p38-navport.py on nav-p0.bin, 11-22: bank tile -> spot 10 / 25 / 9 tiles, and a clear run west of each). */
        static final AfkTasks.Spot[] W32_LINES = {W5_LINES[0], W5_LINES[1], AfkTasks.sp("the Falador east bank fire line", 3016, 3362),
                AfkTasks.sp("the Edgeville bank fire line", 3105, 3486), AfkTasks.sp("the Varrock east bank fire line", 3254, 3429)};
        long w5LineNext;
        int w32TreeJobs; // W32 (-Dbots.fletch.ownNormal): this bot's tree jobs whose log its Fletching cannot carve yet; 0 with the flag off

        /** W32 (-Dbots.fletch.ownNormal): the job to start. A plain tree job (no errand, delivery, crew, hunt or chain) for a log this
         *  bot's Fletching cannot carve becomes the normal-tree job 1 time in OWN_EVERY, by the bot's own counter (no RNG). */
        Job w32Normal(Job job) {
            if (job == null || job.rcRow || job.hunt != null || job.chain != 0 || errandFor != null || deliverTo != null || crew != null || job.task == null || job.task.kind != AfkTasks.Kind.TREE || job.task == AfkTasks.Task.TREE || job.task.products == null) return job;
            int log = job.task.products[0], lvl = bot.getSkills().getLevel(Skills.FLETCHING);
            if (W7cSkills.fletchNeed(log) <= 0 || lvl >= W7cSkills.fletchNeed(log)) return job;
            double near = -1; AfkTasks.Spot[] ts = AfkTasks.SPOTS.get(AfkTasks.Task.TREE); // w32 review: start() is past chooseJob's gates, so the walk is checked here
            if (ts != null) for (AfkTasks.Spot s : ts) if (Gates.canEnter(bot, s.at)) { double d = s.at.getDistance(bot.getLocation()); if (near < 0 || d < near) near = d; }
            if (!W7cSkills.ownNear(near, W7cSkills.OWN_REACH)) return job; // no normal trees within reach: its own job, not counted
            if (!W7cSkills.ownNormal(true, log, lvl, ++w32TreeJobs, W7cSkills.OWN_EVERY)) return job;
            for (Job j : JOBS) if (j.hunt == null && j.chain == 0 && !j.rcRow && j.task == AfkTasks.Task.TREE && blacklist.getOrDefault(j.label(), 0L) < System.currentTimeMillis()) {
                W7cSkills.ownNormalSwaps++;
                System.out.println("[W7c] ownNormal " + bot.getUsername() + " cuts normal logs instead of " + job.label().toLowerCase(Locale.ROOT) + " (Fletching " + lvl + " under " + W7cSkills.fletchNeed(log) + ", tree job " + w32TreeJobs + ")");
                return j;
            }
            return job;
        }

        /** W5a (-Dbots.fire.line): at a trip's end by Varrock west or Draynor bank, 1 in 4, a bot buys a pack of a surplus log
         *  from bot stock (Ledger.consume: paid at guide price, oldest lot first, never created) into its bank and runs the
         *  players' bank-loop BURN with it. Caps: LINE_BANK_CAP bots per bank, LINE_HOUR logs an hour server-wide, 90 min per bot,
         *  a quarter of its purse, and the Exchange keeps 50 of the log. A refused start leaves the logs in its bank (its own). */
        boolean planFireLine(long now) {
            if (errandFor != null || now < w5LineNext || RNG.nextInt(4) != 0) return false;
            Player p = bot;
            AfkTasks.Spot at = null;
            if (W5aSkills.MORE_SPOTS) { // W32 (-Dbots.fm.moreSpots): the nearest of five spots
                double[] d = new double[W32_LINES.length]; for (int i = 0; i < d.length; i++) d[i] = W32_LINES[i].at.getDistance(p.getLocation());
                int ni = W5aSkills.lineNear(d, W5aSkills.LINE_RADIUS); if (ni >= 0) at = W32_LINES[ni]; if (ni >= 2) W5aSkills.lineNewSpot++;
            } else
            for (AfkTasks.Spot s : W5_LINES) if (s.at.getDistance(p.getLocation()) <= 40) at = s;
            if (at == null) { W5aSkills.lineNoSpot++; return false; }
            int here = 0;
            for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) if (r != null && r.p != null && r.p.isArtificial() && r.task == AfkTasks.Task.BANK_LOOP && r.spot != null && r.spot.getDistance(at.at) <= 12) here++;
            if (!W5aSkills.lineSeat(here)) { W5aSkills.lineCapped++; return false; }
            int fm = p.getSkills().getStaticLevel(Skills.FIREMAKING);
            int[] st = new int[W5aSkills.LINE_LOGS.length], tg = new int[st.length];
            for (int i = 0; i < st.length; i++) { st[i] = MarketBrain.stockOf(W5aSkills.LINE_LOGS[i][0]); tg[i] = MarketBrain.target(W5aSkills.LINE_LOGS[i][0]); }
            int log = W5aSkills.lineLog(fm, st, tg);
            if (log == 0) { W5aSkills.lineNoLog++; return false; }
            int li = 0; while (W5aSkills.LINE_LOGS[li][0] != log) li++;
            int n = Math.min(W5aSkills.lineOver(st[li], tg[li]), W5aSkills.lineQty(W5aSkills.lineHourUsed(now), Ledger.Wallet.coins(p), MarketBrain.value(log)));
            if (n == 0) { W5aSkills.lineCapped++; return false; }
            w5LineNext = now + W5aSkills.LINE_GAP_MS;
            if (p.getInventory().getAmount(590) <= 0) {
                if (p.getInventory().freeSlots() < 1) return false;
                if (p.getBank().getAmount(590) > 0) move(p.getBank(), p.getInventory(), 590, 1);
                else if (Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(590)))) packOrBank(p, new Item(590));
                if (p.getInventory().getAmount(590) <= 0) return false;
            }
            if (!p.getBank().hasSpaceFor(new Item(log, n))) { W5aSkills.lineRefused++; return false; } // no bank room: nothing bought
            int took = Ledger.consume(p, log, n, "firemaking");
            if (took <= 0) return false;
            Item bought = new Item(log, took);
            if (!p.getBank().add(bought)) packOrBank(p, bought); // checked above; a refusal is recorded by packOrBank (Failures.Econ.itemLost), never dropped on the ground
            W5aSkills.lineUse(took);
            AfkTasks.begin(p, AfkTasks.Task.BANK_LOOP, true, false, false, false, false, 0, "keep", false, false, 0, at, AfkTasks.Process.BURN, "", true);
            AfkTasks.Runner r = AfkTasks.RUNNING.get(p.getUsername().toLowerCase(Locale.ROOT));
            if (r == null || r.task != AfkTasks.Task.BANK_LOOP) {
                W5aSkills.lineRefused++;
                String why; try { why = AfkTasks.chainProblem(p, AfkTasks.Task.BANK_LOOP, AfkTasks.Process.BURN, "", true, false, at.at); } catch (Throwable t) { why = t.toString(); }
                System.out.println("[Fire] line " + p.getUsername() + " refused at " + at.name + ": " + why + " (" + took + " x " + MarketBrain.name(log) + " stay in its bank)");
                return false;
            }
            runner = r; W5aSkills.lineStarts++; W5aSkills.lineLogs += took;
            System.out.println("[Fire] line " + p.getUsername() + " burns " + took + " x " + MarketBrain.name(log) + " at " + at.name + " (Firemaking " + fm + ", stock now " + MarketBrain.stockOf(log) + ", " + W5aSkills.lineHourUsed(now) + "/" + W5aSkills.LINE_HOUR + " this hour, " + (here + 1) + " at this bank)");
            BotChat.activity(p, () -> "burning a line of logs by the bank");
            return true;
        }

        boolean planPlankRun(long now) {
            if (shopAt != null || !builder() || now < millNext || RNG.nextInt(W3Skills.plankRoll(W3Skills.PLANK_FIX)) != 0) return false; // W3 fix 8: 1 in 3 (flag off: 1 in 5)
            if (W3Skills.PLANK_FIX) W3Skills.plankRolls++;
            millNext = now + (FAST ? 120_000 : 90 * 60_000);
            int[] pick = null; int have = 0;
            long coins = Ledger.Wallet.coins(bot);
            final boolean w7First = W7cSkills.plainFirst(W7cSkills.PLANK_TYPES, bot.getSkills().getStaticLevel(Skills.CONSTRUCTION)); // W7c (-Dbots.plankTypes): false with the flag off
            if (W7cSkills.PLANK_TYPES && w7First && W7cHooks.plainRun(this, bot, now, coins)) return true; // under Construction 15: plain planks with nails first; when that cannot be done (no nails, not at the GE) the old oak run below
            for (int[] m : MILL) { int n = bot.getBank().getAmount(m[0]); if (n >= 10 && n > have && (m[0] == 1521 || coins >= 40_000) && (!W7cSkills.PLANK_TYPES || W7cSkills.plankOk(m[1], bot.getSkills().getStaticLevel(Skills.CONSTRUCTION)))) { have = n; pick = m; } } // W7c (-Dbots.plankTypes): teak 35, mahogany 50 (oak stays open as the fallback under 15)
            if (pick == null) {
                for (int[] m : MILL) {
                    long gate = m[0] == 1521 ? (W3Skills.PLANK_FIX ? 1_500 : 5_000) : 20_000; // oak needs little; teak and mahogany are for the well-off (40k+)
                    if (m[0] != 1521 && coins < 40_000) continue;
                    if (W7cSkills.PLANK_TYPES && !W7cSkills.plankOk(m[1], bot.getSkills().getStaticLevel(Skills.CONSTRUCTION))) continue; // W7c: the plank's Construction level
                    if (nearGE(bot) && MarketBrain.stockOf(m[0]) >= W3Skills.plankStock(W3Skills.PLANK_FIX) && coins >= 27L * (MarketBrain.value(m[0]) + m[2]) + gate) { pick = m; break; } // W3 fix 8: 27 in stock is enough (flag off: 40)
                }
                if (pick == null && W7cSkills.PLANK_TYPES && !w7First && W7cHooks.plainRun(this, bot, now, coins)) return true; // W7c (-Dbots.plankTypes): 15+: plain planks, with nails from the smiths' stock, when no better plank can be had
                if (pick == null && W7cSkills.PLANK_TYPES && W7cHooks.geTrip(this, bot, now, coins)) return true; // w22 (-Dbots.plankTypes): away from the Exchange with no logs or nails banked: walk there for the plain plank run (live 11-21: 8 of 9 plank rolls ended "at the GE false")
                if (pick == null) { if (W3Skills.PLANK_FIX) { W3Skills.plankNoLogs++; System.out.println("[SmartAdv] " + bot.getUsername() + " wants a plank run but has no logs (oak in bank " + bot.getBank().getAmount(1521) + ", on the Exchange " + MarketBrain.stockOf(1521) + ", at the GE " + nearGE(bot) + ", " + coins + " gp)"); } askForLogs(now); return false; }
                int got = Ledger.consume(bot, pick[0], 27, "planks");
                if (got <= 0) return false;
                bot.getBank().add(new Item(pick[0], got));
                have = got;
            }
            millLog = pick[0]; millPlank = pick[1]; millCount = Math.min(27, have);
            mill = MILL_WALK; millStart = now;
            final String what = MarketBrain.name(millLog).toLowerCase(Locale.ROOT);
            BotChat.activity(bot, () -> "taking " + what + " to the sawmill");
            if (RNG.nextInt(2) == 0) say(String.format(BotChat.pick("Right, %d %s to the sawmill. The house won't build itself.", "Sawmill run. %d %s, as notes.", "Off to Varrock with %d %s for planks."), millCount, what));
            System.out.println("[SmartAdv] " + bot.getUsername() + " -> sawmill with " + millCount + " x " + what);
            return true;
        }

        /** Whether this bot could take that job at all (levels, combat, the danger rules). */
        boolean able(Job j) {
            int combat = effectiveCombat();
            if (!reachable(j)) return false; // behind a quest, guild, key or toll it doesn't have
            if (W5bSkills.STALLS && stallBlocked(j, combat)) return false; // W5b stalls: seats and combat gate (crews, errands)
            if (W7cSkills.THIEVE_ARDY && w7ArdyBlocked(j, combat, false)) return false; // W7c: the same for the master farmer and knight jobs
            if (P84.ON && j.hunt != null && Resources.skipHunt(bot, j.hunt.spot)) return false; // P84: a crew follower doesn't follow its leader to a spot no walk reaches
            if (j.skill >= 0 && bot.getSkills().getStaticLevel(j.skill) < j.level) return false;
            if (j.skill < 0 && combat < j.level) return false;
            if (j.hunt != null) {
                if (j.hunt.name.toLowerCase(Locale.ROOT).contains("dragon") && !hasAntifire()) return false;
                if (combat < 70 && poisonous(j.hunt.name)) return false;
                if (deadlyFor(j.hunt, combat)) return false;
                if (magicUser(j.hunt.name) && combat < j.hunt.npcLevel * 2 + 10) return false;
                if (j.hunt.npcLevel >= 80 && (bot.getSkills().getStaticLevel(Skills.PRAYER) < 43 || !hasAntifire())) return false;
                if (W6Slayer.ON && W6Slayer.closed(this, j.hunt, combat) != null) return false; // W6: crews too
            }
            return true;
        }

        /** batch 20 A2: the job's hunt spot, or the nearest of its spots this bot may enter (null when it has none). */
        Location nearestSpot(Job j) {
            if (j.hunt != null) return j.hunt.spot;
            AfkTasks.Spot[] spots = AfkTasks.SPOTS.get(j.task);
            if (spots == null) return null;
            Location best = null; double bd = Double.MAX_VALUE;
            for (AfkTasks.Spot s : spots) { if (!Gates.canEnter(bot, s.at)) continue; double d = s.at.getDistance(bot.getLocation()); if (d < bd) { bd = d; best = s.at; } }
            return best;
        }

        /** The job's spot (or one of its spots) is somewhere this bot is allowed to be (Gates). */
        boolean reachable(Job j) {
            if (j.hunt != null) return Gates.canEnter(bot, j.hunt.spot);
            AfkTasks.Spot[] spots = AfkTasks.SPOTS.get(j.task);
            if (spots == null) return true;
            for (AfkTasks.Spot s : spots) if (Gates.canEnter(bot, s.at)) return true;
            return false;
        }

        /** A gathering job this bot can take right now whose products include the item, or null. */
        Job jobFor(int itemId) {
            int combat = bot.getProperties().getCurrentCombatLevel();
            Job best = null; double bd = Double.MAX_VALUE;
            for (Job j : JOBS) {
                boolean makes = false; for (int id : j.products()) if (id == itemId) makes = true;
                if (!makes || j.hunt != null) continue;
                if (j.skill >= 0 && bot.getSkills().getStaticLevel(j.skill) < j.level) continue;
                if (j.skill < 0 && combat < j.level) continue;
                if (blacklist.getOrDefault(j.label(), 0L) > System.currentTimeMillis()) continue;
                if ((W5aSkills.SHARK ? crowdJob(j) : crowd(j.task)) >= capacity(j)) continue;
                if (W5bSkills.STALLS && stallBlocked(j, combat)) continue; // W5b stalls: errands and rallies keep the seats and the combat gate too
                if (W7cSkills.THIEVE_ARDY && w7ArdyBlocked(j, combat, false)) continue; // W7c: and the master farmer / knight gates
                if (!reachable(j)) continue; // audit B-9: errands and rallies picked jobs behind a gate, then goTo ended "Needs X"
                double dist = 400; for (AfkTasks.Spot s : AfkTasks.SPOTS.get(j.task)) dist = Math.min(dist, s.at.getDistance(bot.getLocation()));
                if (dist < bd) { bd = dist; best = j; }
            }
            return best;
        }

        /** Picked up an errand: fetch this item for that artisan. */
        void takeErrand(Player artisan, int id, Job job) {
            deliverTo = artisan; deliverId = id; nextErrand = System.currentTimeMillis() + 10 * 60_000;
            final String what = MarketBrain.name(id).toLowerCase(Locale.ROOT);
            say(String.format(BotChat.pick("On it, %s. Back with your %s.", "%s wants %s? Say no more.", "Right, %s for %s. Don't sell out before I'm back."), artisan.getUsername(), what));
            System.out.println("[SmartAdv] " + bot.getUsername() + " took an errand: " + what + " for " + artisan.getUsername());
        }

        /** The haul is banked: carry it to the artisan who asked (called from Deals.arrive, which keeps the items in the bank). */
        void startDelivery() {
            delivering = true; deliverStart = System.currentTimeMillis(); deliverArrived = 0;
            final String who = deliverTo.getUsername();
            BotChat.activity(bot, () -> "bringing " + MarketBrain.name(deliverId).toLowerCase(Locale.ROOT) + " to " + who);
        }

        void endDelivery(boolean sold) {
            if (!sold) { int n = bot.getBank().getAmount(deliverId); if (n > 0 && bot.getBank().remove(new Item(deliverId, n))) Ledger.supply(bot, deliverId, n); }
            delivering = false; deliverTo = null; deliverId = 0;
            BotChat.clearActivity(bot);
            nextThink = System.currentTimeMillis() + 3_000;
        }

        void deliverTick(long now) {
            Player a = deliverTo;
            if (a == null || !a.isActive()) { say("Where's he gone? Fine, Exchange it is."); endDelivery(false); return; }
            if (now - deliverStart > (FAST ? 240_000 : 10 * 60_000)) { System.out.println("[SmartAdv] " + bot.getUsername() + " gave up delivering to " + a.getUsername() + " (stuck at " + bot.getLocation() + ")"); endDelivery(false); return; }
            Location there = a.getLocation();
            double d = bot.getLocation().getDistance(there);
            if (d > 3 || bot.getLocation().getZ() != there.getZ()) {
                if (deliverArrived == 0 && d <= 12) deliverArrived = now; // close, but the artisan keeps moving: give it 90 s
                if (deliverArrived != 0 && now - deliverArrived > 90_000) { say(String.format(BotChat.pick("Stood me up, %s. Putting it on the Exchange.", "%s, I haven't got all day."), a.getUsername())); endDelivery(false); return; }
                if (d > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.coins(bot) >= 5_000 && Ledger.Wallet.payTeleport(bot)) {
                    Location near = Surveyors.Surveyor.walkable(Location.create(there.getX() + RNG.nextInt(7) - 3, there.getY() + RNG.nextInt(7) - 3, there.getZ()));
                    try { Spectacle.teleport(bot, near != null ? near : there); } catch (Throwable ignored) { } // batch 15 item1
                    return;
                }
                if (bot.getWalkingQueue().isMoving()) return;
                Location step = d <= 25 ? Deals.scatter(a) : Location.create(bot.getLocation().getX() + (int) Math.round((there.getX() - bot.getLocation().getX()) * 25 / d), bot.getLocation().getY() + (int) Math.round((there.getY() - bot.getLocation().getY()) * 25 / d), there.getZ());
                Location w = d <= 25 ? step : Surveyors.Surveyor.walkable(step);
                try { ((AIPlayer) bot).walkToPosSmart(w != null ? w : step); } catch (Throwable ignored) { }
                return;
            }
            // Here: hand over, get paid 95% of guide (the artisan saves the Exchange's bother, the gatherer saves the wait).
            int n = bot.getBank().getAmount(deliverId);
            int unit = Math.max(1, MarketBrain.value(deliverId) * 95 / 100);
            long total = (long) unit * n;
            if (n <= 0) { endDelivery(false); return; }
            try { bot.face(a); a.face(bot); } catch (Throwable ignored) { }
            if (!Ledger.Wallet.pay(a, total)) { // artisan can't pay: take what it can afford
                long can = Ledger.Wallet.coins(a) - 1_000; int afford = (int) Math.max(0, can / unit);
                if (afford <= 0) { BotChat.say(a, "Skint today. Sorry, " + bot.getUsername() + "."); endDelivery(false); return; }
                n = afford; total = (long) unit * n; if (!Ledger.Wallet.pay(a, total)) { endDelivery(false); return; }
            }
            bot.getBank().remove(new Item(deliverId, n));
            a.getBank().add(new Item(deliverId, n));
            Ledger.Wallet.give(bot, total);
            Deals.meetsToday++;
            String what = MarketBrain.name(deliverId).toLowerCase(Locale.ROOT);
            say(String.format(BotChat.pick("%d %s, as promised%s.", "Here, %d %s%s. Count them if you like."), n, what, n > 20 ? ", as notes" : ""));
            BotChat.speak(a, bot, java.util.Arrays.asList(String.format(BotChat.pick("Lovely. %s gp, and thanks.", "%s gp. Pleasure, as always."), String.format("%,d", total))), 3);
            System.out.println("[Deals] meet: " + bot.getUsername() + " delivered " + n + " x " + what + " to " + a.getUsername() + " for " + total + " gp");
            BotChat.news(String.format(BotChat.pick("%s ran %s out to %s's workshop. Saves the clerks a job.", "Saw %s drop off %s with %s. That's a supply line, that is."), bot.getUsername(), what, a.getUsername()));
            endDelivery(true);
        }

        void millGo(Location to) {
            Location here = bot.getLocation();
            double d = here.getDistance(to);
            if (d > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.payTeleport(bot)) {
                Location near = Surveyors.Surveyor.walkable(Location.create(to.getX() + RNG.nextInt(7) - 3, to.getY() + RNG.nextInt(7) - 3, 0));
                try { Spectacle.teleport(bot, near != null ? near : to); } catch (Throwable ignored) { } // batch 15 item1
                return;
            }
            if (bot.getWalkingQueue().isMoving()) return;
            Location step = d <= 25 ? to : Location.create(here.getX() + (int) Math.round((to.getX() - here.getX()) * 25 / d), here.getY() + (int) Math.round((to.getY() - here.getY()) * 25 / d), 0);
            Location w = d <= 25 ? null : Surveyors.Surveyor.walkable(step);
            try { ((AIPlayer) bot).walkToPosSmart(w != null ? w : step); } catch (Throwable ignored) { }
        }

        void millTick(long now) {
            if (now - millStart > (FAST ? 240_000 : 12 * 60_000)) { System.out.println("[SmartAdv] " + bot.getUsername() + " gave up the plank run (stuck at " + bot.getLocation() + ")"); mill = 0; BotChat.clearActivity(bot); return; }
            switch (mill) {
                case MILL_WALK:
                    if (bot.getLocation().getDistance(SAWMILL) > 5) { millGo(SAWMILL); return; }
                    mill = MILL_PAY; millNext = now; return;
                case MILL_PAY: {
                    int[] m = null; for (int[] x : MILL) if (x[0] == millLog) m = x;
                    if (m == null && W7cSkills.PLANK_TYPES && millLog == W7cSkills.PLAIN_LOG) m = W7cSkills.PLAIN_ROW; // W7c: the plain plank run (100 gp a plank)
                    int n = Math.min(millCount, bot.getBank().getAmount(millLog));
                    if (m == null) { mill = 0; BotChat.clearActivity(bot); return; } // s4b L5: a log the mill does not saw ends the run
                    long fee = (long) m[2] * n;
                    if (n <= 0 || !Ledger.Wallet.pay(bot, fee)) { if (n > 0) try { Failures.Econ.fee(bot, "sawmill", fee); } catch (Throwable ignored) { } mill = 0; BotChat.clearActivity(bot); return; } // batch 19c §4.7 (n > 0: the pay was tried and refused)
                    bot.getBank().remove(new Item(millLog, n));
                    bot.getBank().add(new Item(millPlank, n));
                    FEES_PAID += fee; millCount = n;
                    try { for (core.game.node.entity.npc.NPC npc : RegionManager.getLocalNpcs(bot, 6)) if (npc.getName() != null && npc.getName().toLowerCase(Locale.ROOT).contains("sawmill")) { bot.face(npc); break; } } catch (Throwable ignored) { }
                    System.out.println("[SmartAdv] " + bot.getUsername() + " paid the sawmill " + fee + " gp for " + n + " x " + MarketBrain.name(millPlank).toLowerCase(Locale.ROOT) + " (coins destroyed)");
                    if (RNG.nextInt(2) == 0) say(BotChat.pick("Daylight robbery, " + String.format("%,d", fee) + " gp for planks.", "Cheers. Pricey, but you can't argue with the operator.", "That's the planks sorted."));
                    mill = MILL_HOME; return;
                }
                case MILL_HOME:
                    if (bot.getLocation().getDistance(HOUSE_PORTAL) > 6) { millGo(HOUSE_PORTAL); return; }
                    mill = MILL_BUILD; millNext = now + (FAST ? 15_000 : 60_000 + RNG.nextInt(60_000));
                    BotChat.activity(bot, () -> "building in the house");
                    try { bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(3683)); } catch (Throwable ignored) { }
                    if (RNG.nextInt(2) == 0) say(BotChat.pick("Right, that table's not building itself.", "New room today. Mind the sawdust.", "Planks, nails, hammer. Let's go."));
                    return;
                case MILL_BUILD: {
                    if (now < millNext) { if (RNG.nextInt(4) == 0) try { bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(3683)); } catch (Throwable ignored) { } return; }
                    // Planks are USED: they leave the game here, like furniture in a house. A spare few (at most 20%) may go to the Exchange as notes.
                    int have = bot.getBank().getAmount(millPlank) + bot.getInventory().getAmount(millPlank);
                    int sell = RNG.nextInt(3) == 0 ? Math.min(have / 5, 10) : 0;
                    int build = have - sell;
                    final int w7Nails = W7cSkills.nailsPer(W7cSkills.PLANK_TYPES, millPlank); // W7c (-Dbots.plankTypes): 0 with the flag off and for oak and better
                    if (W7cSkills.PLANK_TYPES) build = W7cHooks.useNails(bot, millPlank, build); // plain planks take a nail each; a plank without one stays in the bank
                    if (W13c.CON_HOTSPOT) try { build -= W13c.hotspot(bot, millPlank, build, W7cSkills.PLANK_TYPES); } catch (Throwable t) { System.out.println("[W13c] hotspot for " + bot.getUsername() + ": " + t); } // W13 lane C (-Dbots.con.hotspot): one real hotspot in its house, its planks out of build (no RNG; flag off: not called)
                    if (build > 0) { bot.getBank().remove(new Item(millPlank, Math.min(build, bot.getBank().getAmount(millPlank)))); bot.getInventory().remove(new Item(millPlank, bot.getInventory().getAmount(millPlank))); PLANKS_BUILT += build; }
                    if (sell > 0 && bot.getBank().remove(new Item(millPlank, sell))) { Ledger.supply(bot, millPlank, sell); PLANKS_SOLD += sell; }
                    try { bot.getSkills().addExperience(Skills.CONSTRUCTION, build * W7cSkills.plankXp(W7cSkills.PLANK_TYPES, millPlank), true); } catch (Throwable ignored) { }
                    System.out.println("[SmartAdv] " + bot.getUsername() + " built with " + build + " x " + MarketBrain.name(millPlank).toLowerCase(Locale.ROOT) + (sell > 0 ? ", sold " + sell + " spare as notes" : "") + (W7cSkills.PLANK_TYPES ? W7cSkills.builtNote(millPlank, build, build * w7Nails, bot.getSkills().getStaticLevel(Skills.CONSTRUCTION)) : ""));
                    BotChat.news(String.format(BotChat.pick("%s has been building again. Whole house smells of %s.", "Saw %s hauling %s through the Rimmington portal. Another room, apparently."), bot.getUsername(), MarketBrain.name(millPlank).toLowerCase(Locale.ROOT)));
                    mill = 0; BotChat.clearActivity(bot);
                    return;
                }
                default: mill = 0;
            }
        }

        /** P53: standing within 10 tiles of a bank AfkTasks knows (or at the Exchange). */
        boolean atBank() {
            if (nearGE(bot)) return true;
            Location l = bot.getLocation();
            for (Location b : AfkTasks.BANKS) if (b.getZ() == l.getZ() && b.getDistance(l) <= 10) return true;
            return false;
        }

        /** W5b bone market: the bone's reference price, its PriceDrift base (read only; null with drift off or no base yet). */
        static Integer boneRef(int id) { try { return PriceDrift.LOADED ? PriceDrift.BASE.get(id) : null; } catch (Throwable t) { return null; } }

        /** P53 (phase4-design.md s6): at trip end by a bank, take up to 27 bones (own first, else bought surplus) and bury them. */
        boolean planBoneRun(long now) {
            if (!PrayerRun.ON || !bot.isArtificial()) return false;
            if (busy()) return PrayerRun.skip(PrayerRun.R_BUSY, now); // P69 #11: the busy checks planSummonRun has
            if (bot.inCombat()) return PrayerRun.skip(PrayerRun.R_COMBAT, now);
            boolean test = PrayerRun.TEST && !boneTested;
            if (!test && (now < boneNext || RNG.nextInt(4) != 0)) return PrayerRun.skip(PrayerRun.R_WAIT, now);
            if (!atBank()) return PrayerRun.skip(PrayerRun.R_NOT_BANK, now);
            boneNext = now + (FAST ? 120_000 : BRAIN_FIX1 ? 10 * 60_000 : 60 * 60_000);
            if (test) boneTested = true;
            int free = bot.getInventory().freeSlots();
            if (free < PrayerRun.MIN_N) return PrayerRun.skip(PrayerRun.R_SLOTS, now);
            boolean ge = nearGE(bot);
            int big = PrayerRun.BIG_BONES, norm = PrayerRun.BONES;
            long coins = Ledger.Wallet.coins(bot);
            int[] c = PrayerRun.boneChoice(bot.getSkills().getStaticLevel(Skills.PRAYER), coins,
                    bot.getBank().getAmount(big), bot.getBank().getAmount(norm),
                    ge && MarketBrain.isSurplus(big), ge && MarketBrain.isSurplus(norm),
                    ge ? MarketBrain.stockOf(big) : 0, ge ? MarketBrain.stockOf(norm) : 0,
                    MarketBrain.value(big), MarketBrain.value(norm), PrayerRun.hourLeft(now));
            if (c == null && W5bSkills.PRAYER_MARKET) { // W5b (-Dbots.prayerRun.market): buy from bot stock at any bank, dragon bones for 200k+ purses, under a price ceiling
                int dr = W5bSkills.DRAGON_BONES;
                c = W5bSkills.boneMarket(true, bot.getSkills().getStaticLevel(Skills.PRAYER), coins, MarketBrain.stockOf(dr), MarketBrain.stockOf(big), MarketBrain.stockOf(norm),
                        MarketBrain.value(dr), MarketBrain.value(big), MarketBrain.value(norm),
                        W5bSkills.boneCeil(boneRef(dr), W5bSkills.CEIL_X, W5bSkills.CEIL_DRAGON), W5bSkills.boneCeil(boneRef(big), W5bSkills.CEIL_X, W5bSkills.CEIL_BIG),
                        W5bSkills.boneCeil(boneRef(norm), W5bSkills.CEIL_X, W5bSkills.CEIL_NORM), PrayerRun.hourLeft(now));
                if (c != null) { W5bSkills.marketRuns++; if (c[0] == dr) W5bSkills.marketDragon++; }
            }
            if (c == null) return PrayerRun.skip(PrayerRun.why(bot.getSkills().getStaticLevel(Skills.PRAYER), ge, MarketBrain.isSurplus(big) || MarketBrain.isSurplus(norm)), now);
            int id = c[0], n = Math.min(c[1], free), bought = 0; long spent = 0;
            if (c[2] == 1) {
                int got = Ledger.consume(bot, id, n, "prayer"); // pays sellers at guide, no mint
                if (got <= 0) return PrayerRun.skip(PrayerRun.R_BUY_FAIL, now);
                PrayerRun.bought(now, got);
                spent = Math.max(0, coins - Ledger.Wallet.coins(bot));
                if (!bot.getInventory().add(new Item(id, got))) { bot.getBank().add(new Item(id, got)); System.out.println("[Prayer] " + bot.getUsername() + " could not carry " + got + " bought bones: banked"); return PrayerRun.skip(PrayerRun.R_BUY_FAIL, now); } // P69 #14: paid for, so never lost
                bought = got; n = got;
            } else {
                if (!bot.getBank().remove(new Item(id, n))) return false;
                if (!bot.getInventory().add(new Item(id, n))) { bot.getBank().add(new Item(id, n)); return false; }
            }
            boneId = id; boneCount = PrayerRun.inPack(bot, id); boneLeft = boneCount; boneStall = 0; boneBought = bought; boneSpent = spent;
            boneXp0 = bot.getSkills().getExperience(Skills.PRAYER); bonePrayer0 = bot.getSkills().getStaticLevel(Skills.PRAYER);
            if (BRAIN_FIX1) boneNext = now + (FAST ? 120_000 : 60 * 60_000); Bob.leave(bot, "busy"); bone = 1; boneStart = now; nextThink = now + 2_000;
            PrayerRun.RUNS++; PrayerRun.BOUGHT += bought; PrayerRun.SPENT += spent; PrayerRun.skip(PrayerRun.R_STARTED, now);
            BotChat.activity(bot, () -> "burying bones");
            if (RNG.nextInt(2) == 0) say(BotChat.pick("Prayer won't train itself. Bones, then back out.", "Burying this lot before the next trip.", "Few bones to put in the ground. Won't be long.", "Right, bones. Every bit of prayer helps."));
            System.out.println("[Prayer] " + bot.getUsername() + " -> burying " + n + " x " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + (bought > 0 ? " (bought " + bought + " for " + spent + " gp)" : " (from the bank)"));
            return true;
        }

        /** P53: one bone per call, every 2 ticks (the bury listener's own clock); done when the pack has none left. */
        void boneTick(long now) {
            if (now - boneStart > (FAST ? 120_000 : 5 * 60_000)) { endBone(true); return; }
            if (bot.inCombat()) { System.out.println("[Prayer] " + bot.getUsername() + " attacked, stops burying"); endBone(true); return; } // F4: fight back, not dig
            nextThink = now + 1_200;
            if (bot.getLocks().isInteractionLocked() || bot.getLocks().isMovementLocked()) return; // F3: the last bury's 2-tick lock (not a stall)
            int left = PrayerRun.inPack(bot, boneId);
            if (left <= 0) { endBone(false); return; }
            if (left < boneLeft) { boneLeft = left; boneStall = 0; } else if (++boneStall > 15) { endBone(true); return; }
            if (!PrayerRun.buryOne(bot, boneId)) endBone(true);
        }

        void endBone(boolean gaveUp) {
            int left = PrayerRun.inPack(bot, boneId);
            if (left > 0 && bot.getInventory().remove(new Item(boneId, left)) && !bot.getBank().add(new Item(boneId, left))) bot.getInventory().add(new Item(boneId, left));
            int buried = Math.max(0, boneCount - left);
            double xp = bot.getSkills().getExperience(Skills.PRAYER) - boneXp0;
            PrayerRun.BURIED += buried;
            System.out.println("[Prayer] " + bot.getUsername() + (gaveUp ? " gave up, " : " ") + "buried " + buried + " x " + MarketBrain.name(boneId).toLowerCase(Locale.ROOT)
                    + String.format(Locale.ROOT, " (+%.1f xp), prayer %d->%d, bought %d for %d gp", xp, bonePrayer0, bot.getSkills().getStaticLevel(Skills.PRAYER), boneBought, boneSpent)
                    + (gaveUp ? " at " + bot.getLocation() : ""));
            bone = 0; BotChat.clearActivity(bot);
        }

        // ---- P62 (summoning-design.md step 2): Summoning pouches at Taverley. Only ever entered with -Dbots.summoning (Summon.ON).
        static final int SUM_WALK = 1, SUM_BUY = 2, SUM_DOWN = 3, SUM_INFUSE = 4, SUM_RENEW = 5, SUM_UP = 6;
        static final int SUM_SCROLL = 7; int summonScrollN, summonScroll0; // Summoning in combat v2: pouches of this batch to turn into scrolls at the obelisk; scrolls carried before

        /** P79 S1 (-Dbots.summoning.mood): one desire roll per 15 min at most; Summon.desire has the score (interest, charm pile, level lag, session, cooldown). */
        boolean wantsSummon(long now) {
            if (now < summonNext || now < summonRollAt) return false;
            final core.game.container.impl.BankContainer bk = bot.getBank();
            if (!Summon.affordable(bot.getSkills().getStaticLevel(Skills.SUMMONING), Ledger.Wallet.coins(bot), bk::getAmount, bk::getAmount, MarketBrain::value)) { Summon.POOR++; summonRollAt = now + Summon.rollGapMs(FAST); return false; } // P79 option B: no roll it could not pay for
            summonRollAt = now + Summon.rollGapMs(FAST);
            long charms = 0; for (int id : Summon.CHARMS) charms += bot.getBank().getAmount(id);
            int cb = 3; try { cb = bot.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
            double s = Summon.desire(Summon.interest(bot.getUsername()), charms, bot.getSkills().getStaticLevel(Skills.SUMMONING), cb, now - shiftStart, now < summonCool);
            s = Bob.desire(s, bot); // P86 S5 design 2.3: +0.20 when points are low (flag off: unchanged)
            Summon.band(s);
            return RNG.nextDouble() < s;
        }

        /** At trip end by a bank: charms + secondaries out of the bank, then off to Pikkupstix (Summon.java has the pick). */
        boolean planSummonRun(long now) {
            if (!Summon.ON || !bot.isArtificial()) return false;
            if (busy()) return Summon.skip(Summon.R_BUSY, now);
            if (bot.inCombat()) return Summon.skip(Summon.R_COMBAT, now);
            if (!Summon.unlocked(bot)) return Summon.skip(Summon.R_LOCKED, now);
            boolean test = Summon.TEST && !summonTested;
            if (!test && (Summon.MOOD ? !wantsSummon(now) : (now < summonNext || RNG.nextInt(3) != 0))) return Summon.skip(Summon.R_WAIT, now); // P79 S1: desire score under -Dbots.summoning.mood
            if (!atBank()) return Summon.skip(Summon.R_NOT_BANK, now);
            summonNext = now + (Summon.MOOD ? Summon.noPickGapMs(FAST) : FAST ? 120_000 : BRAIN_FIX1 ? 10 * 60_000 : 60 * 60_000);
            if (test) summonTested = true;
            final core.game.container.impl.BankContainer bank = bot.getBank();
            int[] c = Summon.choose(bot.getSkills().getStaticLevel(Skills.SUMMONING), Ledger.Wallet.coins(bot), bot.getInventory().freeSlots(),
                    bank::getAmount, bank::getAmount, MarketBrain::stockOf, MarketBrain::value, Bob.COMBAT && Bob.fightsALot(fav, fightTripsN, tripsN)); // secondaries: own bank, else the bots' Exchange stock via Ledger (as the Wolf Whistle bones step)
            if (c == null) return Summon.skip(Summon.R_NO_PICK, now);
            if (!Summon.seat(bot.getUsername(), k -> { Brain b = BRAINS.get(k); return b != null && b.summon != 0; })) return Summon.skip(Summon.R_CAP, now);
            int[] r = Summon.row(c[0]);
            int n = c[1], buy = c[2], own = n - buy;
            long coins0 = Ledger.Wallet.coins(bot);
            // charms and own secondaries: bank -> pack (free slots checked by choose)
            if (!bank.remove(new Item(r[2], n))) { Summon.free(bot.getUsername()); return Summon.skip(Summon.R_TAKE_FAIL, now); }
            if (!bot.getInventory().add(new Item(r[2], n))) { bank.add(new Item(r[2], n)); Summon.free(bot.getUsername()); return Summon.skip(Summon.R_TAKE_FAIL, now); }
            if (own > 0) {
                if (!bank.remove(new Item(r[3], own))) { putBack(r[2], n); Summon.free(bot.getUsername()); return Summon.skip(Summon.R_TAKE_FAIL, now); }
                if (!bot.getInventory().add(new Item(r[3], own))) { bank.add(new Item(r[3], own)); putBack(r[2], n); Summon.free(bot.getUsername()); return Summon.skip(Summon.R_TAKE_FAIL, now); }
            }
            int bought = 0;
            if (buy > 0) {
                bought = Ledger.consume(bot, r[3], buy, "summoning"); // pays sellers at guide, no mint
                if (bought > 0 && !bot.getInventory().add(new Item(r[3], bought))) bank.add(new Item(r[3], bought));
                if (Bob.ON && bought < buy && Summon.bobShop(r[3]) > 0) { int more = buy - Math.max(0, bought); long fee = Summon.bobShop(r[3]) * more; if (Ledger.Wallet.pay(bot, fee)) { if (!bot.getInventory().add(new Item(r[3], more))) bank.add(new Item(r[3], more)); bought = Math.max(0, bought) + more; Summon.SPENT += fee; System.out.println("[Summon] bob " + bot.getUsername() + " bought " + more + " x " + MarketBrain.name(r[3]) + " at the bank for " + fee + " gp"); } } // s4b M1: thin snails have no bot source
                if (bought <= 0) { putBack(r[2], n); putBack(r[3], own); Summon.free(bot.getUsername()); return Summon.skip(Summon.R_BUY_FAIL, now); }
            }
            summonScrollN = 0; summonScroll0 = 0;
            summonRow = c[0]; summonPouch0 = bot.getInventory().getAmount(r[0]); summonN = Math.min(n, own + bought); // P69 #9: pouches carried before the run are not "made"
            summonBuy = bought; summonSpent = Math.max(0, coins0 - Ledger.Wallet.coins(bot)); // s4b L6: back on their own line (log stats only)
            summonXp0 = bot.getSkills().getExperience(Skills.SUMMONING); summonLvl0 = bot.getSkills().getStaticLevel(Skills.SUMMONING);
            summonWait = 0; summonClicks = 0; summonVia = Summon.viaEastGate(bot.getLocation()); summonMove.reset(summonVia ? Summon.TAV_GATE_E : Summon.PIKK_AT, summonVia ? 2 : 3); // P74: through the east gate first
            if (BRAIN_FIX1 && !Summon.MOOD) summonNext = now + (FAST ? 120_000 : 60 * 60_000); Bob.leave(bot, "busy"); summon = SUM_WALK; summonStart = now; nextThink = now + 600;
            if (Summon.MOOD) { summonBuy = bought; summonSpent = Math.max(0, coins0 - Ledger.Wallet.coins(bot)); summonCool = now + Summon.coolMs(FAST, RNG.nextDouble()); if (!test) Summon.DESIRE_STARTS++; } // P79: 1-3 h "just did it"; the P69 #9 comment swallowed these two assignments
            Summon.RUNS++; Summon.BOUGHT += bought; Summon.skip(Summon.R_STARTED, now);
            final String what = MarketBrain.name(r[0]).toLowerCase(Locale.ROOT);
            BotChat.activity(bot, () -> "making " + what + "es at Taverley");
            if (RNG.nextInt(2) == 0) say(BotChat.pick("Charms are piling up. Taverley, then back out.", "Off to Pikkupstix to infuse a few pouches.", "Summoning run. Won't be long.", "Few pouches to make at the obelisk."));
            System.out.println("[Summon] " + bot.getUsername() + " -> Taverley to make " + summonN + " x " + what + " (summoning " + summonLvl0 + (bought > 0 ? ", bought " + bought + " " + MarketBrain.name(r[3]).toLowerCase(Locale.ROOT) + " for " + summonSpent + " gp" : "") + ")");
            return true;
        }

        private void putBack(int id, int n) {
            if (n <= 0) return;
            int have = Math.min(n, bot.getInventory().getAmount(id));
            if (have > 0 && bot.getInventory().remove(new Item(id, have)) && !bot.getBank().add(new Item(id, have))) bot.getInventory().add(new Item(id, have));
        }

        /** One step per think (600 ms): walk, pay the shop, trapdoor, infuse, renew, climb back. Give-up 10 min (3 under -Dtraders.fast). */
        void summonTick(long now) {
            nextThink = now + 600;
            if (now - summonStart > (FAST ? 180_000 : 10 * 60_000)) { endSummon(true, "timeout"); return; }
            if (bot.inCombat()) { endSummon(true, "attacked"); return; }
            if (summonWait > 0) { summonWait--; return; }
            if (bot.getPulseManager().hasPulseRunning() && summon != SUM_INFUSE) return;
            int[] r = Summon.row(summonRow);
            boolean cellar = Summon.inCellar(bot.getLocation());
            switch (summon) {
                case SUM_WALK: {
                    if (cellar) { summon = SUM_UP; return; }
                    QuestSteps.St s = summonMove.tick(bot);
                    if (s == QuestSteps.St.FAILED) { endSummon(true, "walk " + summonMove.last); return; }
                    if (s == QuestSteps.St.DONE && summonVia) { summonVia = false; summonMove.reset(Summon.PIKK_AT, 3); return; } // P74: at the east gate, on to Pikkupstix
                    if (s == QuestSteps.St.DONE) { summon = SUM_BUY; }
                    return;
                }
                case SUM_BUY: {
                    long bill = Summon.shopBill(summonRow, summonN);
                    if (!Ledger.Wallet.pay(bot, bill)) { endSummon(true, "can't pay the shop " + bill + " gp"); return; }
                    summonSpent += bill; Summon.SPENT += bill;
                    if (!bot.getInventory().add(new Item(Summon.SHARDS, r[4] * summonN))) bot.getBank().add(new Item(Summon.SHARDS, r[4] * summonN));
                    for (int i = 0; i < summonN; i++) if (!bot.getInventory().add(new Item(Summon.EMPTY))) bot.getBank().add(new Item(Summon.EMPTY));
                    try { for (core.game.node.entity.npc.NPC npc : RegionManager.getLocalNpcs(bot, 8)) if (npc.getId() == 6971) { bot.faceLocation(npc.getLocation()); break; } } catch (Throwable ignored) { }
                    System.out.println("[Summon] " + bot.getUsername() + " paid Pikkupstix " + bill + " gp for " + (r[4] * summonN) + " shards and " + summonN + " pouches");
                    summon = SUM_DOWN; summonClicks = 0; summonMove.reset(Summon.TRAPDOOR_AT, 2);
                    return;
                }
                case SUM_DOWN: {
                    if (cellar) { summon = SUM_INFUSE; summonClicks = 0; return; }
                    if (bot.getLocation().getDistance(Summon.TRAPDOOR_AT) > 2.5) {
                        QuestSteps.St s = summonMove.tick(bot);
                        if (s == QuestSteps.St.FAILED) endSummon(true, "walk to the trapdoor " + summonMove.last);
                        return;
                    }
                    if (bot.getWalkingQueue().isMoving()) return;
                    if (++summonClicks > 8) { endSummon(true, "trapdoor did not let it down"); return; }
                    if (P82.ON && summonClicks > 4 && Summon.unlocked(bot)) { // P82 fix 4: 4 clicks and still on the surface (p79: 1 run in 4 gave up here): Wolf Whistle is done, so the trapdoor is this bot's to use; climb down like the dungeon entrances do
                        if (P82.TRAP.get() < 20) { String d = "?"; try { core.game.node.scenery.Scenery w = RegionManager.getObject(0, Summon.TRAPDOOR_AT.getX(), Summon.TRAPDOOR_AT.getY()); core.game.node.scenery.Scenery c = w == null ? null : QuestSteps.childOf(w, bot); d = "wrapper " + (w == null ? "none" : w.getId()) + " child " + (c == null ? "none" : c.getId()) + " varp1178 " + core.api.ContentAPIKt.getVarp(bot, 1178) + " canClimb " + QuestWolfWhistle.canClimbDown(bot) + " pulse " + bot.getPulseManager().hasPulseRunning() + " locked " + bot.getLocks().isInteractionLocked(); } catch (Throwable t) { d = "diag error " + t; } System.out.println("[P82] " + bot.getUsername() + " trapdoor: 4 clicks at " + bot.getLocation() + " did not take it down (" + d + "); climbs down"); }
                        P82.count("trapdoor-climb", P82.TRAP);
                        try { bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(827)); } catch (Throwable ignored) { }
                        bot.getProperties().setTeleportLocation(Summon.CELLAR); summonWait = 3; return;
                    }
                    QuestSteps.clearPopups(bot);
                    if (QuestWolfWhistle.canClimbDown(bot)) {
                        core.game.node.scenery.Scenery s = QuestSteps.findScenery(bot, Summon.TRAPDOOR_AT, 2, new int[]{Summon.TRAPDOOR_OPEN}, null, "Climb-down");
                        if (s == null || !QuestSteps.click(bot, s, "Climb-down", core.game.interaction.IntType.SCENERY)) { endSummon(true, "no open trapdoor"); return; }
                    } else {
                        core.game.node.scenery.Scenery s = QuestSteps.findScenery(bot, Summon.TRAPDOOR_AT, 2, new int[]{Summon.TRAPDOOR}, null, "Open");
                        if (s == null || !QuestSteps.click(bot, s, "Open", core.game.interaction.IntType.SCENERY)) { endSummon(true, "no trapdoor"); return; }
                    }
                    summonWait = 4;
                    return;
                }
                case SUM_INFUSE: {
                    if (!cellar) { endSummon(true, "not in the cellar"); return; }
                    int made = Math.max(0, bot.getInventory().getAmount(r[0]) - summonPouch0); // P69 #9: baseline at plan time
                    boolean sets = bot.getInventory().getAmount(r[2]) >= 1 && bot.getInventory().getAmount(r[3]) >= 1 && bot.getInventory().getAmount(Summon.EMPTY) >= 1 && bot.getInventory().getAmount(Summon.SHARDS) >= r[4];
                    if (Bob.COMBAT2 && summonScrollN == 0 && made > 0 && (made >= summonN || summonClicks > 0 && !sets) && Bob.specOf(r[0]) != null && Bob.fightsALot(fav, fightTripsN, tripsN)) {
                        int sc = Bob.specOf(r[0])[1];
                        summonScrollN = Bob.scrollBatch(made, bot.getBank().getAmount(r[0]) + bot.getInventory().getAmount(r[0]), bot.getBank().getAmount(sc) + bot.getInventory().getAmount(sc));
                        if (summonScrollN > 0) { summonScroll0 = bot.getInventory().getAmount(sc); summon = SUM_SCROLL; summonClicks = 0; return; }
                    }
                    if (made >= summonN || summonClicks > 0 && !sets) { summon = SUM_RENEW; summonClicks = 0; return; }
                    if (bot.getPulseManager().hasPulseRunning()) return; // the CreatePulse (3 ticks then the batch)
                    core.game.node.scenery.Scenery ob = QuestWolfWhistle.obelisk(bot, Summon.CELLAR, 12);
                    if (ob == null) { endSummon(true, "no cellar obelisk"); return; }
                    if (bot.getLocation().getDistance(ob.getLocation()) > 2.5) {
                        if (++summonClicks > 12) { endSummon(true, "could not reach the obelisk " + ob.getLocation()); return; }
                        try { core.game.world.map.path.Pathfinder.find(bot, ob).walk(bot); } catch (Throwable ignored) { }
                        summonWait = 3; return;
                    }
                    if (!sets) { endSummon(true, "short of the pouch supplies"); return; }
                    if (summonClicks > 20) { endSummon(true, "pouches not made"); return; }
                    summonClicks = Math.max(1, summonClicks + 1);
                    bot.faceLocation(ob.getLocation());
                    content.global.skill.summoning.SummoningCreator.create(bot, summonN - made, content.global.skill.summoning.SummoningPouch.get(r[0])); // the server's own infuse (level, items, xp)
                    summonWait = 3;
                    return;
                }
                case SUM_SCROLL: {
                    int[] sp = Bob.specOf(r[0]);
                    int done = sp == null ? 0 : Math.max(0, bot.getInventory().getAmount(sp[1]) - summonScroll0) / 10;
                    if (!cellar || sp == null || done >= summonScrollN || bot.getInventory().getAmount(r[0]) < 1 || summonClicks > 6) { summon = SUM_RENEW; summonClicks = 0; return; }
                    if (bot.getPulseManager().hasPulseRunning()) return;
                    core.game.node.scenery.Scenery ob = QuestWolfWhistle.obelisk(bot, Summon.CELLAR, 12);
                    content.global.skill.summoning.SummoningScroll scroll = content.global.skill.summoning.SummoningScroll.forPouch(r[0]);
                    if (ob == null || scroll == null || scroll.getItemId() != sp[1]) { summon = SUM_RENEW; summonClicks = 0; return; }
                    summonClicks++;
                    if (bot.getLocation().getDistance(ob.getLocation()) > 2.5) {
                        try { core.game.world.map.path.Pathfinder.find(bot, ob).walk(bot); } catch (Throwable ignored) { }
                        summonWait = 3; return;
                    }
                    bot.faceLocation(ob.getLocation());
                    content.global.skill.summoning.SummoningCreator.create(bot, summonScrollN - done, scroll);
                    summonWait = 3;
                    return;
                }
                case SUM_RENEW: {
                    if (bot.getSkills().getLevel(Skills.SUMMONING) >= bot.getSkills().getStaticLevel(Skills.SUMMONING) || !cellar) { summon = SUM_UP; summonClicks = 0; return; }
                    if (++summonClicks > 5) { summon = SUM_UP; summonClicks = 0; return; } // points are a nicety: never stay for them
                    core.game.node.scenery.Scenery ob = QuestWolfWhistle.obelisk(bot, Summon.CELLAR, 12);
                    if (ob == null) { summon = SUM_UP; summonClicks = 0; return; }
                    QuestSteps.clearPopups(bot);
                    QuestSteps.click(bot, ob, "Renew-points", core.game.interaction.IntType.SCENERY);
                    summonWait = 6;
                    return;
                }
                case SUM_UP: {
                    if (!cellar) { endSummon(false, null); return; }
                    if (bot.getWalkingQueue().isMoving()) return;
                    if (++summonClicks > 8) { // the ladder would not take it: out the way the quest left it (never leave a bot in the cellar)
                        bot.getPulseManager().clear(); Bob.tele(bot).getProperties().setTeleportLocation(Location.create(2926, 3444, 0));
                        endSummon(true, "ladder failed, moved up"); return;
                    }
                    QuestSteps.clearPopups(bot);
                    core.game.node.scenery.Scenery s = QuestSteps.findScenery(bot, Summon.CELLAR_LADDER_AT, 3, new int[]{Summon.CELLAR_LADDER}, null, "Climb");
                    if (s != null) QuestSteps.click(bot, s, "Climb", core.game.interaction.IntType.SCENERY);
                    summonWait = 4;
                    return;
                }
                default: endSummon(true, "state " + summon);
            }
        }

        /** Ends the errand from anywhere (done, gave up, death, rescue, logout): banks what it carried, never leaves it in the cellar. */
        void endSummon(boolean gaveUp, String why) {
            if (summon == 0) return;
            int[] r = Summon.row(summonRow);
            int[] sp2 = summonScrollN > 0 ? Bob.specOf(r[0]) : null;
            int scrolled = sp2 == null ? 0 : Math.max(0, bot.getInventory().getAmount(sp2[1]) - summonScroll0) / 10;
            int made = Math.max(0, bot.getInventory().getAmount(r[0]) - summonPouch0) + scrolled; // P69 #9 (summon2: a pouch turned into 10 scrolls was still made)
            boolean dead = "death".equals(why); // P69 #12: the pack went to the grave and respawn is pending: no cellar teleport, nothing to bank
            if (!dead && Summon.inCellar(bot.getLocation())) { try { Location up = Location.create(2926, 3444, 0); bot.getPulseManager().clear(); Bob.tele(bot).getProperties().setTeleportLocation(up); if ("logout".equals(why)) bot.setLocation(up); } catch (Throwable ignored) { } } // F6/F10: a logout saves the house, not the cellar
            int[] ids = {r[0], r[2], r[3], Summon.SHARDS, Summon.EMPTY};
            if (sp2 != null && !dead) { Bob.C2_SCROLLS_MADE += scrolled * 10L; if (scrolled > 0) System.out.println("[Summon] " + bot.getUsername() + " turned " + scrolled + " of the pouches into " + (scrolled * 10) + " scrolls"); putBack(sp2[1], bot.getInventory().getAmount(sp2[1])); }
            if (!dead) for (int id : ids) putBack(id, bot.getInventory().getAmount(id)); // into the bank (as the plank run does); left in the pack if the bank is full
            double xp = bot.getSkills().getExperience(Skills.SUMMONING) - summonXp0;
            Summon.POUCHES_MADE += made; Summon.XP += Math.max(0, xp); if (gaveUp) Summon.GAVE_UP++;
            System.out.println("[Summon] " + bot.getUsername() + (gaveUp ? " gave up (" + why + "), " : " ") + "made " + made + " x " + MarketBrain.name(r[0]).toLowerCase(Locale.ROOT)
                    + String.format(Locale.ROOT, " (+%.1f xp), summoning %d->%d, spent %d gp", xp, summonLvl0, bot.getSkills().getStaticLevel(Skills.SUMMONING), summonSpent)
                    + (gaveUp ? " at " + bot.getLocation() : ""));
            summon = 0; Summon.free(bot.getUsername()); BotChat.clearActivity(bot);
        }

        /** P62: a Watchdog rescue teleports the bot away mid-errand: the errand ends (items banked). */
        void summonRescued() { if (summon != 0) endSummon(true, "rescued"); }

        /** C8 review S2: a Watchdog rescue ends the farm run too (crops in the ground stay due). */
        void farmRescued() { if (farm != 0) endFarm(true, "rescued"); }

        // ---- C8 farming (-Dbots.farming, Farming.java, the design notes). farm stays 0 with the flag off. ----
        static final int FARM_WALK = 1, FARM_WORK = 2, FARM_BACK = 3;
        static final int FA_RAKE = 1, FA_COMPOST = 2, FA_SEED = 3, FA_WATER = 4, FA_HARVEST = 5, FA_CLEAR = 6, FA_CURE = 7;
        private int farmFlower = -1, farmNoCure, farmFlowerPass; // farm2 (-Dbots.farming.v2): the flower seed carried this run; patches whose cure did not take
        private int farmSold; // farm49: this run's crops sent to the Exchange (mid-run offloads + endFarm)
        private int farmWhy, farmNoWater; private final int[] farmCrops0 = new int[Farming.allCrops().length]; // C8 fix 1: patches whose watering did not take; crops carried before the run (not this run's to sell)

        /** At trip end by a bank: CHECK when crops are due (no dice), else PLANT 1 in 4 if the crop fits before the shift ends. */
        boolean planFarmRun(long now) {
            if (!Farming.ON || !bot.isArtificial()) return false;
            if (busy()) return Farming.skip(Farming.R_BUSY, now);
            if (bot.inCombat()) return Farming.skip(Farming.R_COMBAT, now);
            if (W9Trees.RUN && !(farmDue != 0 && now >= farmDue) && planTreeRun(now)) return true; // w9 trees (-Dbots.farming.trees.run): a tree CHECK or PLANT first unless allotment crops are due; off = never called
            final boolean check = farmDue != 0 && now >= farmDue;
            final boolean test = Farming.TEST && !farmTested;
            if (!check) {
                if (farmDue != 0) return Farming.skip(Farming.R_WAIT, now); // crops in the ground, not due yet
                if (!test && now < farmNext) return Farming.skip(Farming.R_WAIT, now);
                if (Farming.nearestSite(bot.getLocation()) < 0) return Farming.skip(Farming.R_FAR, now); // farm49: far from every farm costs no dice and no penalty
                if (!test && RNG.nextInt(2) != 0) return Farming.skip(Farming.R_WAIT, now);
            }
            if (!atBank()) return Farming.skip(Farming.R_NOT_BANK, now);
            if (!check) { farmNext = now + 10 * 60_000L; if (test) farmTested = true; } // farm49: a refusal after the dice waits 10 min (money refusals 30, below)
            int overdue = -1; // tiles walked for an overdue CHECK from another city, -1 when not one
            if (check && Farming.nearestSite(bot.getLocation()) != farmSite) { // review S1: a CHECK from another city waits for a trip end near the farm...
                Location fa = Farming.SITES[farmSite].anchor, me = bot.getLocation();
                if (now - farmDue < Farming.OVERDUE_MS || fa.getZ() != me.getZ() || fa.getDistance(me) > Farming.OVERDUE_R || (me.getX() < 2880) != (fa.getX() < 2880)) return Farming.skip(Farming.R_FAR, now); // farm47 Fable: never cross White Wolf Mountain for a check
                overdue = (int) fa.getDistance(me); // ...unless it is 20+ min overdue and the farm is in walking range (S1's 3-failed-checks rule still applies)
            }
            final int site = check ? farmSite : Farming.nearestSite(bot.getLocation());
            if (site < 0) return Farming.skip(Farming.R_FAR, now);
            final List<core.game.node.scenery.Scenery> pts = Farming.find(site);
            if (pts.isEmpty()) return Farming.skip(Farming.R_NO_SITE, now);
            if (bot.getInventory().freeSlots() < (check ? 6 : 8)) return Farming.skip(Farming.R_SLOTS, now);
            final int fit = Farming.fit(now, shiftEnd);
            if (!check && fit == 0) { farmNext = shiftEnd; return Farming.skip(Farming.R_FIT, now); } // farm49: it can never fit again this shift
            final String name = bot.getUsername();
            if (!check && !Farming.seat(name, k -> { Brain b = BRAINS.get(k); return b != null && (b.farm != 0 || b.farmDue != 0); })) return Farming.skip(Farming.R_CAP, now);
            // prep at the bank: tools, seeds that fit, compost already owned (section 4 step 3, economy section 5)
            final long coins0 = Ledger.Wallet.coins(bot);
            final long[] left = {Farming.budget(coins0)};
            farmWhy = Farming.R_BUY_FAIL;
            int nA = 0, nH = 0, nF = 0;
            for (core.game.node.scenery.Scenery o : pts) { content.global.skill.farming.FarmingPatch f = content.global.skill.farming.FarmingPatch.forObject(o); if (f != null && f.getType() == content.global.skill.farming.PatchType.ALLOTMENT) nA++; else if (Farming.V2 && f != null && f.getType() == content.global.skill.farming.PatchType.FLOWER_PATCH) nF++; else nH++; }
            final java.util.List<int[]> took = new ArrayList<>(); // {id, n} moved into the pack, put back on a refusal
            final int[] w10Cand = W10Skills.HERB_RUN ? w10Reach(site) : W10Skills.NO_LEGS; int w10Seeds = 0; // W10 lane M (-Dbots.farming.herbRun): the other herb sites this run can add (none with the flag off)
            boolean spade = farmGet(Farming.SPADE, 1, true, left, took);
            boolean rake = fit > 0 && spade && farmGet(Farming.RAKE, 1, true, left, took);
            boolean dibber = rake && farmGet(Farming.DIBBER, 1, true, left, took);
            boolean plant = fit > 0 && rake && dibber;
            int lvl = bot.getSkills().getStaticLevel(Skills.FARMING), aSeed = -1, hSeed = -1;
            if (plant && nA > 0) {
                final int q = Farming.SEEDS_PER_ALLOT * nA;
                final int fig = Farming.V2 && nF > 0 ? farmFlowerIn(pts) : -1;
                aSeed = Farming.V2 && nF > 0 ? Farming.pickAllotV2(lvl, fig, id -> farmCanSource(id, q, left[0])) : Farming.pickSeed(lvl, false, id -> farmCanSource(id, q, left[0]));
                if (aSeed > 0 && !farmGet(aSeed, q, Farming.shopStocked(aSeed), left, took)) aSeed = -1;
            }
            if (plant && fit == 2 && nH > 0) {
                final int q = Farming.SEEDS_PER_HERB * nH;
                final long w6Left = left[0], w6Coins = W6Herbs.HERBS2 ? Ledger.Wallet.coins(bot) : 0; if (W6Herbs.HERBS2) left[0] = W6Herbs.herbBudget(true, coins0, w6Left); // W6 (-Dbots.farming.herbs2): the herb seed's own allowance
                hSeed = Farming.pickSeed(lvl, true, id -> farmCanSource(id, q, left[0]) && (!W6Herbs.HERBS2 || W6Herbs.seedWorth(true, bot.getInventory().getAmount(id) + bot.getBank().getAmount(id), q, MarketBrain.value(id), MarketBrain.value(W6Herbs.cropOf(Farming.HERB_SEEDS, id)))));
                if (W10Skills.HERB_RUN && hSeed > 0) w10Seeds = w10SeedLegs(hSeed, q, left[0], w10Cand.length); // W10 lane M: one more herb seed a leg it can source (0 with the flag off)
                if (hSeed > 0 && !farmGet(hSeed, q + w10Seeds, false, left, took)) hSeed = -1;
                if (W6Herbs.HERBS2) { left[0] = W6Herbs.budgetAfter(w6Left, left[0]); long w6Spent = Math.max(0, w6Coins - Ledger.Wallet.coins(bot)); W6Herbs.picked(true, hSeed, w6Spent); W6Herbs.pickedSay(name, hSeed, w6Spent, now); } // w9 s62fire: pickedSay = the lane line
            }
            if (!spade || !check && (!plant || aSeed < 0 && hSeed < 0)) {
                for (int[] t : took) putBack(t[0], t[1]);
                int why = farmWhy;
                if (!check) { Farming.free(name); farmNext = now + 30 * 60_000L; } // farm49: coins change slowly
                else farmDue = now + 30 * 60_000L; // a CHECK that could not get a spade tries again later
                return Farming.skip(why, now);
            }
            if (plant) farmCan(left, took);
            if (Farming.V2) farmPrepV2(pts, lvl, aSeed, hSeed, nA, nH + w10Seeds, nF, check, left, took);
            else {
            int compost = Math.min(bot.getBank().getAmount(Farming.COMPOST), (aSeed > 0 ? nA : 0) + (hSeed > 0 ? nH + w10Seeds : 0)); // optional, never bought in v1
            if (compost > 0 && bot.getBank().remove(new Item(Farming.COMPOST, compost)) && !bot.getInventory().add(new Item(Farming.COMPOST, compost))) bot.getBank().add(new Item(Farming.COMPOST, compost));
            }
            long spent = Math.max(0, coins0 - Ledger.Wallet.coins(bot));
            farmSpent = spent; Farming.SEED_SPEND += spent;
            { int[] cr = Farming.allCrops(); for (int i = 0; i < cr.length; i++) farmCrops0[i] = bot.getInventory().getAmount(cr[i]); } farmNoWater = 0; farmSold = 0;
            farmSite = site; farmPlant = plant && (aSeed > 0 || hSeed > 0); farmHerb = hSeed > 0; farmNew = false; farmNewHerb = false;
            farmIdx = 0; farmAct = 0; farmClicks = 0; farmStall = 0; farmWait = 0; farmStart = now; nextThink = now + 600;
            Bob.leave(bot, "busy"); farm = FARM_WALK; farmCheck = check; if (!check) farmFails = 0; farmMove.reset(Farming.SITES[site].anchor, 3);
            Farming.RUNS++; if (overdue >= 0) Farming.OVERDUES++; Farming.skip(Farming.R_STARTED, now);
            final String key = Farming.SITES[site].key.toLowerCase(Locale.ROOT);
            BotChat.activity(bot, () -> "tending the farming patches at " + key);
            if (RNG.nextInt(2) == 0) say(BotChat.pick("Off to do a farm run.", "Patches should be ready about now."));
            System.out.println("[Farm] " + bot.getUsername() + " -> " + Farming.SITES[site].key + " to " + (check ? overdue >= 0 ? "check (overdue, " + overdue + " tiles)" : "check" : "plant") + " (farming " + lvl
                    + (aSeed > 0 ? ", " + MarketBrain.name(aSeed).toLowerCase(Locale.ROOT) : "") + (hSeed > 0 ? ", " + MarketBrain.name(hSeed).toLowerCase(Locale.ROOT) : "") + ", spent " + spent + " gp)");
            if (W10Skills.HERB_RUN) try { w10Start(site, w10Cand, hSeed > 0 ? w10Seeds / Farming.SEEDS_PER_HERB : 0, check, hSeed, now); } catch (Throwable t) { w10Legs = W10Skills.NO_LEGS; w10Leg = -1; System.out.println("[W10] herbRun for " + name + ": " + t); } // W10 lane M: the legs of this run (never with the flag off)
            return true;
        }

        /** farm2: the seed id of the live flower in the site's flower patch, -1 when it is empty or dead. */
        private int farmFlowerIn(List<core.game.node.scenery.Scenery> pts) {
            for (core.game.node.scenery.Scenery o : pts) {
                content.global.skill.farming.FarmingPatch f = content.global.skill.farming.FarmingPatch.forObject(o);
                if (f == null || f.getType() != content.global.skill.farming.PatchType.FLOWER_PATCH) continue;
                content.global.skill.farming.Patch p = f.getPatchFor(bot, true);
                return p.getPlantable() != null && !p.isDead() ? p.getPlantable().getItemID() : -1;
            }
            return -1;
        }
        /** farm2: a fully grown flower of the right kind stands in the flower patch: the engine never diseases this allotment crop. */
        private boolean farmGuarded(List<core.game.node.scenery.Scenery> pts, int allotSeed) {
            for (core.game.node.scenery.Scenery o : pts) {
                content.global.skill.farming.FarmingPatch f = content.global.skill.farming.FarmingPatch.forObject(o);
                if (f == null || f.getType() != content.global.skill.farming.PatchType.FLOWER_PATCH) continue;
                content.global.skill.farming.Patch p = f.getPatchFor(bot, true);
                return p.getPlantable() != null && !p.isDead() && !p.isDiseased() && p.isGrown() && p.getPlantable().getItemID() == Farming.flowerFor(allotSeed);
            }
            return false;
        }
        /** farm2 prep, after the v1 tools and seeds and inside the same budget: a flower seed when the flower patch is free, compost (owned supercompost,
         *  owned compost, then bought compost) for the patches that need it, and one plant cure. Every item is optional: a miss never refuses the run. */
        private void farmPrepV2(List<core.game.node.scenery.Scenery> pts, int lvl, int aSeed, int hSeed, int nA, int nH, int nF, boolean check, long[] left, java.util.List<int[]> took) {
            farmFlower = -1; farmNoCure = 0; farmFlowerPass = 0;
            final long c0 = Ledger.Wallet.coins(bot);
            if (nF > 0 && aSeed > 0) {
                int fs = Farming.flowerSeedToBuy(lvl, aSeed, farmFlowerIn(pts), check);
                if (fs > 0 && farmGet(fs, 1, true, left, took)) farmFlower = fs;
            }
            int need = Math.min(Farming.compostNeed(aSeed > 0 ? nA : 0, aSeed > 0 && farmGuarded(pts, aSeed), hSeed > 0 ? nH : 0), Math.max(0, bot.getInventory().freeSlots() - 3)); // buckets do not stack: keep 3 slots for crops
            int sup = Math.min(bot.getBank().getAmount(Farming.SUPERCOMPOST), need);
            if (sup > 0 && bot.getBank().remove(new Item(Farming.SUPERCOMPOST, sup))) { if (bot.getInventory().add(new Item(Farming.SUPERCOMPOST, sup))) need -= sup; else bot.getBank().add(new Item(Farming.SUPERCOMPOST, sup)); }
            need -= Math.min(need, bot.getInventory().getAmount(Farming.COMPOST));
            if (need > 0) {
                int own = Math.min(bot.getBank().getAmount(Farming.COMPOST), need);
                int buy = Farming.compostToBuy(need - own, MarketBrain.value(Farming.COMPOST), left[0]);
                if (own + buy > 0) farmGet(Farming.COMPOST, bot.getInventory().getAmount(Farming.COMPOST) + own + buy, true, left, took);
            }
            if (Farming.takeCure(bot.getInventory().getAmount(Farming.PLANT_CURE), bot.getBank().getAmount(Farming.PLANT_CURE), bot.getInventory().freeSlots())) farmGet(Farming.PLANT_CURE, 1, true, left, took);
            Farming.EXTRA_SPEND += Math.max(0, c0 - Ledger.Wallet.coins(bot));
        }
        /** farm2: the bucket to use on this empty patch, -1 for none: never on a flower-guarded allotment, and an allotment leaves one bucket for the herb patch planted this run. */
        private int farmCompostV2(boolean allot, int seed, List<core.game.node.scenery.Scenery> pts) {
            int sup = bot.getInventory().getAmount(Farming.SUPERCOMPOST), com = bot.getInventory().getAmount(Farming.COMPOST), keep = 0;
            if (sup + com <= 0 || allot && farmGuarded(pts, seed)) return -1;
            if (allot && farmHerb) for (core.game.node.scenery.Scenery o : pts) {
                content.global.skill.farming.FarmingPatch f = content.global.skill.farming.FarmingPatch.forObject(o);
                if (f == null || f.getType() != content.global.skill.farming.PatchType.HERB_PATCH) continue;
                content.global.skill.farming.Patch hp = f.getPatchFor(bot, true);
                if (hp.getPlantable() == null && hp.getCompost() == content.global.skill.farming.CompostType.NONE) keep = 1;
            }
            if (sup + com <= keep) return -1;
            return sup > 0 ? Farming.SUPERCOMPOST : Farming.COMPOST;
        }

        /** Owned (pack + bank) or buyable within the budget: Exchange stock for anything, the shop stand-in for shop goods. */
        private boolean farmCanSource(int id, int qty, long left) {
            int own = bot.getInventory().getAmount(id) + bot.getBank().getAmount(id);
            if (own >= qty) return true;
            int need = qty - own;
            if ((long) MarketBrain.value(id) * need > left) { farmWhy = Farming.R_BUDGET; return false; } // farm49: tally a price refusal as budget
            return MarketBrain.stockOf(id) >= need || Farming.shopStocked(id);
        }

        /** Puts qty of id in the pack: own bank, then Exchange stock (Ledger.consume, pays sellers), then (shop goods only) a coin-sink buy. Never mints. */
        private boolean farmGet(int id, int qty, boolean shopOk, long[] left, java.util.List<int[]> took) {
            int have = bot.getInventory().getAmount(id);
            if (have >= qty) return true;
            int need = qty - have;
            int take = Math.min(need, bot.getBank().getAmount(id));
            if (take > 0) {
                if (!bot.getBank().remove(new Item(id, take))) return false;
                if (!bot.getInventory().add(new Item(id, take))) { bot.getBank().add(new Item(id, take)); return false; }
                took.add(new int[]{id, take}); need -= take;
            }
            if (need <= 0) return true;
            long unit = MarketBrain.value(id);
            if (unit * need > left[0]) { farmWhy = Farming.R_BUDGET; return false; }
            long c0 = Ledger.Wallet.coins(bot);
            if (MarketBrain.stockOf(id) >= need) {
                int got = Ledger.consume(bot, id, need, "farming");
                if (got > 0) { if (bot.getInventory().add(new Item(id, got))) took.add(new int[]{id, got}); else bot.getBank().add(new Item(id, got)); need -= got; }
            }
            if (need > 0 && shopOk && Ledger.Wallet.pay(bot, unit * need)) {
                if (bot.getInventory().add(new Item(id, need))) took.add(new int[]{id, need}); else bot.getBank().add(new Item(id, need));
                need = 0;
            }
            left[0] -= Math.max(0, c0 - Ledger.Wallet.coins(bot));
            return need <= 0 && bot.getInventory().getAmount(id) >= qty;
        }

        /** A watering can in the pack: own (pack or bank), an empty one swapped for a full one (stands for the pump), else bought at the empty can's price. Optional. */
        private void farmCan(long[] left, java.util.List<int[]> took) {
            for (int id = 5333; id <= 5340; id++) if (bot.getInventory().getAmount(id) > 0) return;
            for (int id = 5333; id <= 5340; id++) if (bot.getBank().getAmount(id) > 0) {
                if (bot.getBank().remove(new Item(id, 1))) { if (bot.getInventory().add(new Item(id, 1))) { took.add(new int[]{id, 1}); return; } bot.getBank().add(new Item(id, 1)); }
            }
            if (bot.getInventory().remove(new Item(Farming.CAN_EMPTY, 1)) || bot.getBank().remove(new Item(Farming.CAN_EMPTY, 1))) { bot.getInventory().add(new Item(Farming.CAN_FULL, 1)); return; }
            long price = MarketBrain.value(Farming.CAN_EMPTY);
            if (price <= left[0] && Ledger.Wallet.pay(bot, price)) { left[0] -= price; bot.getInventory().add(new Item(Farming.CAN_FULL, 1)); }
        }

        /** One step per think (600 ms): walk to the farm, work the patches in order, walk back to the bank. Give-up 12 min (3 under -Dtraders.fast). */
        void farmTick(long now) {
            nextThink = now + 600;
            if (now - farmStart > (FAST ? 180_000 : 12 * 60_000)) { endFarm(true, "timeout"); return; }
            if (bot.inCombat()) { endFarm(true, "attacked"); return; }
            if (farmWait > 0) { farmWait--; return; }
            switch (farm) {
                case FARM_WALK: {
                    QuestSteps.St s = farmMove.tick(bot);
                    if (s == QuestSteps.St.FAILED) { endFarm(true, "walk " + farmMove.last); return; }
                    if (s == QuestSteps.St.DONE) { Seed.fillPack(bot, farmDue != 0); farm = FARM_WORK; farmIdx = 0; farmAct = 0; farmClicks = 0; farmStall = 0; }
                    return;
                }
                case FARM_WORK: farmWork(now); return;
                case FARM_BACK: {
                    QuestSteps.St s = farmMove.tick(bot);
                    if (s == QuestSteps.St.FAILED) { endFarm(true, "walk back " + farmMove.last); return; }
                    if (s == QuestSteps.St.DONE) endFarm(false, null);
                    return;
                }
                default: endFarm(true, "state " + farm);
            }
        }

        private Item farmPackItem(int id) { for (Item i : bot.getInventory().toArray()) if (i != null && i.getId() == id) return i; return null; }
        private int farmCanId() { for (int id = 5340; id >= 5333; id--) if (bot.getInventory().getAmount(id) > 0) return id; return -1; }
        private int farmSeedFor(boolean allot) {
            for (Item i : bot.getInventory().toArray()) if (i != null && Farming.isSeed(i.getId()) && Farming.isHerbSeed(i.getId()) != allot && i.getAmount() >= (allot ? Farming.SEEDS_PER_ALLOT : Farming.SEEDS_PER_HERB)) return i.getId();
            return -1;
        }
        static int patchDist(Location b, core.game.node.scenery.Scenery s) {
            Location o = s.getLocation();
            if (o.getZ() != b.getZ()) return 99;
            int sx = Math.max(1, s.getSizeX()), sy = Math.max(1, s.getSizeY());
            int dx = Math.max(0, Math.max(o.getX() - b.getX(), b.getX() - (o.getX() + sx - 1)));
            int dy = Math.max(0, Math.max(o.getY() - b.getY(), b.getY() - (o.getY() + sy - 1)));
            return Math.max(dx, dy);
        }
        private boolean farmUse(int id, core.game.node.scenery.Scenery s) {
            Item it = farmPackItem(id);
            if (it == null) return false;
            QuestSteps.clearPopups(bot);
            bot.faceLocation(s.getLocation());
            // C8 fix 3: call the patch's own use-with handler in place (the engine's "instant" branch of InteractionListeners.run). The
            // normal branch wraps it in a MovementPulse: when the path to the 1x1 patch wrapper ends "near", the handler never runs
            // ("I can't reach that"), and the pulse clears WEAK queued scripts, the kind the plant uses. The bot already stands within 2 tiles.
            try {
                if (bot.getLocks().isInteractionLocked()) return false;
                kotlin.jvm.functions.Function3<Player, core.game.node.Node, core.game.node.Node, Boolean> m = core.game.interaction.InteractionListeners.get(id, s.getId(), core.game.interaction.IntType.SCENERY.ordinal());
                if (m == null) return core.game.interaction.InteractionListeners.run(it, s, core.game.interaction.IntType.SCENERY, bot);
                bot.getWalkingQueue().reset();
                Boolean r = m.invoke(bot, it, s);
                return r == null || r;
            } catch (Throwable t) { System.out.println("[Farm] " + bot.getUsername() + " use " + id + " threw " + t); return false; }
        }
        /** Walks toward the patch until within 2 tiles; true while walking (the caller returns). Shared by the allotment and tree runs. */
        private boolean farmWalkTo(core.game.node.scenery.Scenery s) {
            if (patchDist(bot.getLocation(), s) <= 2) return false;
            if (bot.getWalkingQueue().isMoving()) { farmClicks--; if (++farmStall > 20) farmStalled("walk to the patch"); return true; }
            try { core.game.world.map.path.Pathfinder.find(bot, s).walk(bot); } catch (Throwable ignored) { }
            farmWait = 2; return true;
        }
        private void farmNextPatch() { farmIdx++; farmAct = 0; farmClicks = 0; farmStall = 0; farmWeeds(); }
        private void farmWeeds() { int w = bot.getInventory().getAmount(Farming.WEEDS); if (w > 0) bot.getInventory().remove(new Item(Farming.WEEDS, w)); }
        private void farmStalled(String what) {
            Farming.STALLS++;
            System.out.println("[Farm] " + bot.getUsername() + " stalled on " + what + " at patch " + farmIdx + " (" + (farmTree ? W9Trees.KEYS[treeSite] : Farming.SITES[farmSite].key) + ")"); // w9 trees: a tree run names its tree site
            bot.getPulseManager().clear();
            farmNextPatch();
        }

        /** One rule per think per patch (design section 4 a-f); an issued action waits for its success signal, 20 thinks at most. */
        void farmWork(long now) {
            if (farmTree) { treeWork(now); return; } // w9 trees (-Dbots.farming.trees.run): the run's one tree patch
            List<core.game.node.scenery.Scenery> pts = w10Leg >= 0 ? w10HerbPts() : Farming.find(farmSite); // W10 lane M: w10Leg is -1 with the flag off
            if (farmIdx >= pts.size() && farmFlowerPass == 1) {
                farmFlowerPass = 2; farmAct = 0; farmClicks = 0; farmStall = 0;
                for (int i = 0; i < pts.size(); i++) { content.global.skill.farming.FarmingPatch f = content.global.skill.farming.FarmingPatch.forObject(pts.get(i)); if (f != null && f.getType() == content.global.skill.farming.PatchType.FLOWER_PATCH) { farmIdx = i; break; } }
            }
            if (farmIdx >= pts.size()) { farmFinish(now); return; }
            core.game.node.scenery.Scenery s = pts.get(farmIdx);
            content.global.skill.farming.FarmingPatch fp = content.global.skill.farming.FarmingPatch.forObject(s);
            if (fp == null) { farmNextPatch(); return; }
            content.global.skill.farming.Patch p = fp.getPatchFor(bot, true);
            boolean allot = fp.getType() == content.global.skill.farming.PatchType.ALLOTMENT;
            final boolean flower = Farming.V2 && fp.getType() == content.global.skill.farming.PatchType.FLOWER_PATCH;
            if (farmFlowerPass == 2 && !flower) { farmFinish(now); return; }
            if (farmAct != 0) {
                boolean pulse = bot.getPulseManager().hasPulseRunning();
                boolean ok;
                switch (farmAct) {
                    case FA_RAKE: ok = !pulse && !p.isWeedy(); break;
                    case FA_COMPOST: ok = p.getCompost() != content.global.skill.farming.CompostType.NONE; break;
                    case FA_SEED: ok = p.getPlantable() != null; break;
                    case FA_WATER: ok = !pulse && p.isWatered(); break;
                    case FA_HARVEST: ok = !pulse; break;
                    case FA_CLEAR: p.clear(); if (flower) Farming.FLOWER_DIED++; else Farming.DIED++; System.out.println("[Farm] " + bot.getUsername() + (flower ? " dug out a dead flower at " : " cleared a dead/diseased ") + fp.name().toLowerCase(Locale.ROOT)); ok = true; break;
                    case FA_CURE: ok = !p.isDiseased(); break;
                    default: ok = true;
                }
                if (ok) {
                    int act = farmAct; farmAct = 0; farmStall = 0;
                    if (act >= FA_RAKE && act <= FA_CURE && act != FA_CLEAR) farmClicks = Farming.stepsAfterOk(Farming.FIX1, farmClicks, act == FA_HARVEST, act == FA_HARVEST ? Math.max(0, bot.getInventory().getAmount(farmCrop) - farmBase) : 0); // farm fix1: a step that did something is not counted; FA_CLEAR (and an unknown act) is always "ok", so it stays counted
                    if (act == FA_SEED && flower) { Farming.FLOWERS++; System.out.println("[Farm] " + bot.getUsername() + " planted " + (farmFlower == Farming.ROSEMARY_SEED ? "rosemary" : "marigold") + " at " + fp.name().toLowerCase(Locale.ROOT)); }
                    else if (act == FA_SEED) { Farming.PLANTED++; farmNew = true; if (!allot) farmNewHerb = true; if (!allot && Seed.ON) Scen.herbPlant(bot, p.getPlantable() == null ? -1 : p.getPlantable().getItemID()); } // w9 herbs2 scenario (seed flags on only)
                    if (act == FA_COMPOST && Farming.V2) { Farming.COMPOSTED++; System.out.println("[Farm] " + bot.getUsername() + " composted " + fp.name().toLowerCase(Locale.ROOT)); }
                    if (act == FA_CURE) { Farming.CURED++; System.out.println("[Farm] " + bot.getUsername() + " cured " + fp.name().toLowerCase(Locale.ROOT)); }
                    if (act == FA_RAKE) farmWeeds();
                    if (w10Leg >= 0 && act == FA_SEED && !flower) w10LegPlant++; // W10 lane M (flag on only)
                    if (act == FA_HARVEST) {
                        int got = Math.max(0, bot.getInventory().getAmount(farmCrop) - farmBase);
                        Farming.HARVESTED += got; if (w10Leg >= 0) w10LegGot += got; // W10 lane M (flag on only)
                        System.out.println("[Farm] " + bot.getUsername() + " harvested " + got + " x " + MarketBrain.name(farmCrop).toLowerCase(Locale.ROOT));
                        if (!allot && !flower && Seed.ON) Scen.herbHarvest(bot, farmCrop, got); // w9 herbs2 scenario (seed flags on only)
                        if (p.getPlantable() != null) { if (bot.getInventory().freeSlots() <= 0 && farmOffload() > 0) return; farmNextPatch(); } // farm49: pack full (the pulse stopped itself): offload crops and harvest again, else leave the rest
                    }
                    return;
                }
                ++farmStall;
                if (farmAct != FA_HARVEST && farmStall >= 8 && !pulse) { // C8 fix 1: the engine dropped the action (a weak queued script cleared by a pulse, or weeds back after the 5-min growth cycle): re-run the rules; farmClicks caps the retries per patch
                    System.out.println("[Farm] " + bot.getUsername() + " retry act " + farmAct + " patch " + farmIdx + " state " + p.getCurrentState() + " plantable " + p.getPlantable() + " watered " + p.isWatered() + " seeds " + farmSeedFor(allot) + " at " + bot.getLocation());
                    if (farmAct == FA_WATER) farmNoWater |= 1 << farmIdx; // watering is optional: once is enough to try
                    if (farmAct == FA_CURE) farmNoCure |= 1 << farmIdx; // farm2: the cure did not take: the next pass digs the patch out as v1 does
                    farmAct = 0; farmStall = 0; return;
                }
                if (farmStall > (farmAct == FA_HARVEST ? 80 : 20)) farmStalled("action " + farmAct);
                return;
            }
            if (bot.getPulseManager().hasPulseRunning()) return;
            final boolean flowerSeed = flower && farmPlant && farmFlower > 0 && bot.getInventory().getAmount(farmFlower) > 0;
            final int flowerStep = flower ? Farming.flowerStep(bot.getSkills().getStaticLevel(Skills.FARMING), farmFlower, flowerSeed, p.getPlantable() == null, farmFlowerPass == 2) : Farming.FL_SKIP;
            if (flower && (p.isDead() || p.isDiseased() ? !flowerSeed && bot.getInventory().getAmount(Farming.PLANT_CURE) <= 0 : flowerStep != Farming.FL_PLANT)) { // farm3: decided before the walk: no trip to a flower patch with nothing to do
                if (flowerStep == Farming.FL_LATER) farmFlowerPass = 1;
                farmNextPatch(); return;
            }
            if (++farmClicks > Farming.STEP_CAP) { Scen.tooManySteps(bot); farmStalled("too many steps"); return; }
            if (farmWalkTo(s)) return;
            content.global.skill.farming.Plantable pl = p.getPlantable();
            // a. dead or diseased: dig it out (the engine's spade only takes dead crops, and opens a dialogue otherwise)
            if (Farming.V2 && p.isDiseased() && !p.isDead() && (farmNoCure & (1 << farmIdx)) == 0 && bot.getInventory().getAmount(Farming.PLANT_CURE) > 0 && farmUse(Farming.PLANT_CURE, s)) { farmAct = FA_CURE; farmStall = 0; return; } // farm2: cure before digging out
            if (p.isDead() || p.isDiseased()) {
                try { bot.faceLocation(s.getLocation()); bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(830)); } catch (Throwable ignored) { }
                farmAct = FA_CLEAR; farmWait = 3; return;
            }
            // farm2: the flower patch. A live flower is never picked (grown, it guards the allotments for the rest of the shift); an empty patch is raked and planted.
            if (flower) {
                int fs = pl == null && flowerStep == Farming.FL_PLANT ? farmFlower : -1;
                if (fs < 0) { farmNextPatch(); return; }
                if (p.isWeedy()) { if (!farmUse(Farming.RAKE, s)) { farmNextPatch(); return; } farmAct = FA_RAKE; farmStall = 0; return; }
                if (p.getCurrentState() == 3) { if (farmUse(fs, s)) { farmAct = FA_SEED; farmStall = 0; return; } farmStalled("flower seed " + fs + " not taken"); return; }
                farmNextPatch(); return;
            }
            // b. grown: harvest with the engine's pulse
            if (pl != null && p.isGrown()) {
                if (bot.getInventory().getAmount(Farming.SPADE) <= 0 || bot.getInventory().freeSlots() <= 0 && farmOffload() <= 0) { farmNextPatch(); return; } // farm49: offload before skipping a grown patch
                core.game.system.task.Pulse h = null;
                try { h = content.global.skill.farming.CropHarvester.harvestPulse(bot, s, pl.getHarvestItem()); } catch (Throwable t) { System.out.println("[Farm] " + bot.getUsername() + " harvestPulse threw " + t); }
                if (h == null) { farmStalled("no harvest pulse"); return; }
                farmCrop = pl.getHarvestItem(); farmBase = bot.getInventory().getAmount(farmCrop);
                bot.faceLocation(s.getLocation());
                bot.getPulseManager().run(h);
                farmAct = FA_HARVEST; farmStall = 0; return;
            }
            // c. growing: water an allotment once
            if (pl != null) {
                int can = farmCanId();
                if (allot && !p.isWatered() && can > 0 && (farmNoWater & (1 << farmIdx)) == 0) { if (farmUse(can, s)) { farmAct = FA_WATER; farmStall = 0; return; } }
                farmNextPatch(); return;
            }
            int seed = farmPlant && (allot || farmHerb) ? farmSeedFor(allot) : -1;
            // d. weedy: rake it, only when this run plants here
            if (p.isWeedy()) {
                if (seed < 0 || !farmUse(Farming.RAKE, s)) { farmNextPatch(); return; }
                farmAct = FA_RAKE; farmStall = 0; return;
            }
            // e. weeded and empty: compost if held, then the seed
            if (p.getCurrentState() == 3 && seed > 0) {
                final int cid = Farming.V2 ? farmCompostV2(allot, seed, pts) : bot.getInventory().getAmount(Farming.COMPOST) > 0 ? Farming.COMPOST : -1;
                if (p.getCompost() == content.global.skill.farming.CompostType.NONE && cid > 0 && farmUse(cid, s)) { farmAct = FA_COMPOST; farmStall = 0; return; }
                if (farmUse(seed, s)) { farmAct = FA_SEED; farmStall = 0; return; }
                farmStalled("seed " + seed + " not taken");
                return;
            }
            // f. nothing to do here
            if (farmPlant && p.getCurrentState() == 3 && seed < 0 && (allot || farmHerb)) System.out.println("[Farm] " + bot.getUsername() + " skips " + fp.name().toLowerCase(Locale.ROOT) + ": fewer than " + (allot ? Farming.SEEDS_PER_ALLOT : Farming.SEEDS_PER_HERB) + " seeds in the pack");
            farmNextPatch();
        }

        /** When the next CHECK is due: a fresh planting = its grow time + 5 min, older crops still growing = 15 min, grown but left = 5 min, nothing = 0. */
        private long farmDueFrom(long now) {
            boolean growing = false, grown = false;
            for (core.game.node.scenery.Scenery s : Farming.find(farmSite)) {
                content.global.skill.farming.FarmingPatch fp = content.global.skill.farming.FarmingPatch.forObject(s);
                if (fp == null || Farming.V2 && fp.getType() == content.global.skill.farming.PatchType.FLOWER_PATCH) continue;
                content.global.skill.farming.Patch p = fp.getPatchFor(bot, true);
                if (p.getPlantable() == null) continue;
                if (p.isGrown() && !p.isDead()) grown = true; else growing = true;
            }
            if (W10Skills.HERB_RUN) for (int leg : w10Reach(farmSite)) { content.global.skill.farming.Patch p = w10HerbPatch(leg); if (p == null) continue; if (p.isGrown() && !p.isDead()) grown = true; else growing = true; } // W10 lane M: the other herb sites' crops (flag on only)
            if (farmNew) return now + Farming.growMs(farmNewHerb) + 5 * 60_000L;
            if (growing) return now + 15 * 60_000L;
            if (grown) return now + 5 * 60_000L;
            return 0;
        }

        private void farmFinish(long now) {
            if (W10Skills.HERB_RUN) try { if (w10NextLeg(now)) return; } catch (Throwable t) { System.out.println("[W10] herbRun leg for " + bot.getUsername() + ": " + t); } // W10 lane M: on to the next herb site (never with the flag off)
            farmWeeds();
            farmDue = Seed.grown(bot, farmSite, farmDueFrom(now), now); // W0 A0: .seed.grow (seed flags on only; off = farmDueFrom unchanged)
            if (farmDue == 0) Farming.free(bot.getUsername());
            farm = FARM_BACK; farmMove.reset(Farming.SITES[w10At()].bank, 4); // W10 lane M: w10At() is farmSite with the flag off
        }

        // ---- W10 lane M (-Dbots.farming.herbRun, W10Skills, the design notes): a farm run goes on to the herb patch of the
        // other sites it can walk to, on the run's own machinery (farmMove / farmWork / the step cap / farmOffload). Nothing below runs with the flag off. ----
        private int w10At() { return w10Leg >= 0 && w10Leg < w10Legs.length ? w10Legs[w10Leg] : farmSite; }
        private static int[] w10Reach(int home) {
            final int n = Farming.SITES.length; final int[][] at = new int[n][];
            for (int i = 0; i < n; i++) { Location a = Farming.SITES[i].anchor; at[i] = new int[]{a.getX(), a.getY(), a.getZ()}; }
            return W10Skills.reach(home, at, W10Skills.MAX_LEGS, W10Skills.LEG_R);
        }
        private static String[] w10Keys() { String[] k = new String[Farming.SITES.length]; for (int i = 0; i < k.length; i++) k[i] = Farming.SITES[i].key; return k; }
        /** The bot's crop in that site's herb patch, read from its crop timer (nothing added to it), null when there is none. */
        private content.global.skill.farming.Patch w10HerbPatch(int site) {
            content.global.skill.farming.FarmingPatch herb = null;
            for (content.global.skill.farming.FarmingPatch f : Farming.SITES[site].patches) if (f.getType() == content.global.skill.farming.PatchType.HERB_PATCH) herb = f;
            content.global.skill.farming.timers.CropGrowth t = FarmSave.timerOf(bot);
            if (herb == null || t == null) return null;
            for (content.global.skill.farming.Patch p : t.getPatches()) if (p != null && p.getPatch() == herb && p.getPlantable() != null) return p;
            return null;
        }
        /** The leg site's herb patch wrapper only. */
        private List<core.game.node.scenery.Scenery> w10HerbPts() {
            List<core.game.node.scenery.Scenery> out = new ArrayList<>();
            if (w10Leg < 0 || w10Leg >= w10Legs.length) return out;
            for (core.game.node.scenery.Scenery o : Farming.find(w10Legs[w10Leg])) { content.global.skill.farming.FarmingPatch f = content.global.skill.farming.FarmingPatch.forObject(o); if (f != null && f.getType() == content.global.skill.farming.PatchType.HERB_PATCH) out.add(o); }
            return out;
        }
        /** Extra herb seeds for the legs: the most legs (at most n) whose seeds it owns or can buy in the run's budget (and, with W6 herbs2, are worth it). */
        private int w10SeedLegs(int seed, int q, long left, int n) {
            final int why = farmWhy;
            try {
                for (int k = n; k > 0; k--) {
                    final int qq = q + Farming.SEEDS_PER_HERB * k;
                    if (farmCanSource(seed, qq, left) && (!W6Herbs.HERBS2 || W6Herbs.seedWorth(true, bot.getInventory().getAmount(seed) + bot.getBank().getAmount(seed), qq, MarketBrain.value(seed), MarketBrain.value(W6Herbs.cropOf(Farming.HERB_SEEDS, seed))))) return Farming.SEEDS_PER_HERB * k;
                }
                return 0;
            } finally { farmWhy = why; } // a leg refusal never becomes the run's refusal reason
        }
        private void w10Start(int site, int[] cand, int seedLegs, boolean check, int hSeed, long now) {
            w10Leg = -1; w10LegGot = 0; w10LegPlant = 0;
            final boolean[] crop = new boolean[cand.length];
            for (int i = 0; i < cand.length; i++) crop[i] = w10HerbPatch(cand[i]) != null;
            w10Legs = W10Skills.visit(cand, crop, seedLegs);
            if (w10Legs.length == 0) { if (cand.length > 0) W10Skills.herbNoLeg++; return; }
            W10Skills.herbRuns++; W10Skills.herbSeeds += Math.min(seedLegs, w10Legs.length) * Farming.SEEDS_PER_HERB;
            if (W10Skills.HERB_LINES.ok(now)) System.out.println("[W10] " + bot.getUsername() + " herbRun: " + W10Skills.legNames(w10Keys(), site, w10Legs) + " to " + (check ? "check" : "plant")
                    + (hSeed > 0 && seedLegs > 0 ? " (" + seedLegs * Farming.SEEDS_PER_HERB + " more " + MarketBrain.name(hSeed).toLowerCase(Locale.ROOT) + ")" : " (harvest only)"));
        }
        /** At the end of a site's patches: the leg just worked is counted, then the next leg's walk starts (a fresh 12-min give-up). False = the run is over. */
        private boolean w10NextLeg(long now) {
            if (w10Leg >= 0 && w10Leg < w10Legs.length) {
                W10Skills.herbLegsDone++; W10Skills.herbLegHarvest += w10LegGot; W10Skills.herbLegPlanted += w10LegPlant;
                if (W10Skills.HERB_LINES.ok(now)) System.out.println("[W10] " + bot.getUsername() + " herbRun leg " + Farming.SITES[w10Legs[w10Leg]].key.toLowerCase(Locale.ROOT) + ": harvested " + w10LegGot + ", planted " + w10LegPlant);
            }
            if (w10Leg + 1 >= w10Legs.length) return false;
            w10Leg++; w10LegGot = 0; w10LegPlant = 0;
            farmWeeds();
            farm = FARM_WALK; farmIdx = 0; farmAct = 0; farmClicks = 0; farmStall = 0; farmWait = 0; farmFlowerPass = 0; farmNoWater = 0; farmNoCure = 0; farmStart = now;
            farmMove.reset(Farming.SITES[w10Legs[w10Leg]].anchor, 3);
            W10Skills.herbLegs++;
            return true;
        }

        /** farm49: this run's crops out of the pack: Exchange stock first, then bank, then ground (C8 fix 2). Returns units that left the pack. */
        private int farmOffload() { return farmOffload(Farming.allCrops(), farmCrops0); }
        /** w9 trees: the same offload for any product list (a tree run's fruit), base = what the pack held before the run. */
        private int farmOffload(int[] cr, int[] base) {
            int moved = 0;
            for (int ci = 0; ci < cr.length; ci++) {
                int id = cr[ci];
                int n = bot.getInventory().getAmount(id) - base[ci]; // only this run's crops; what it carried before stays with it
                if (n <= 0 || !bot.getInventory().remove(new Item(id, n))) continue;
                int w0 = farmWaiting(id);
                try { Ledger.supply(bot, id, n); } catch (Throwable ignored) { }
                if (farmWaiting(id) - w0 >= n) { farmSold += n; moved += n; }
                else if (bot.getBank().add(new Item(id, n))) moved += n;
                else if (!bot.getInventory().add(new Item(id, n))) { core.game.node.item.GroundItemManager.create(new Item(id, n), bot.getLocation(), bot); moved += n; } // a refused supply returns silently; never let the crop vanish
            }
            return moved;
        }

        /** Ends the run from anywhere (done, gave up, death, rescue, logout): crops into Exchange stock, tools and seeds into the bank. */
        void endFarm(boolean gaveUp, String why) {
            if (farm == 0) return;
            if (farmTree) { endTreeRun(gaveUp, why); return; } // w9 trees (-Dbots.farming.trees.run): farmTree is false with the flag off
            long now = System.currentTimeMillis();
            boolean dead = "death".equals(why);
            if (farm != FARM_BACK) { try { farmDue = farmDueFrom(now); } catch (Throwable ignored) { } } // crops already in the ground stay due
            if (!dead) {
                farmOffload();
                farmWeeds();
                for (int id : new int[]{Farming.RAKE, Farming.DIBBER, Farming.SPADE, Farming.COMPOST, Farming.BUCKET, Farming.CAN_EMPTY}) putBack(id, bot.getInventory().getAmount(id));
                for (int id = 5333; id <= 5340; id++) putBack(id, bot.getInventory().getAmount(id));
                for (int id : Farming.allSeeds()) putBack(id, bot.getInventory().getAmount(id));
                if (Farming.V2) for (int id : new int[]{Farming.SUPERCOMPOST, Farming.PLANT_CURE, Farming.MARIGOLD_SEED, Farming.ROSEMARY_SEED}) putBack(id, bot.getInventory().getAmount(id));
            }
            if (farmCheck && !dead && !"logout".equals(why)) { // C8 review S1: a CHECK that keeps failing gives the patch set up
                if (!gaveUp || farm == FARM_BACK) farmFails = 0; // farm49 B2: a timeout on the walk back is not a failed check
                else if (farmDue != 0 && ++farmFails >= Farming.CHECK_FAILS) {
                    Farming.ABANDONED++; Farming.skip(Farming.R_DROPPED, now);
                    System.out.println("[Farm] " + bot.getUsername() + " gave up its crops at " + Farming.SITES[farmSite].key.toLowerCase(Locale.ROOT) + " after " + farmFails + " failed checks");
                    farmDue = 0; farmFails = 0;
                }
            }
            if (farmDue == 0) Farming.free(bot.getUsername());
            if (FarmSave.ON && !"logout".equals(why)) try { if (W9Trees.RUN) farmSaveKeyed("run end"); else FarmSave.save(bot, bot.getUsername(), Farming.SITES[farmSite].key, farmDue, "run end"); } catch (Throwable ignored) { } // W2: file matches the ground after every run (due 0 = file removed); a logout saves in farmLogout. w9 trees: a bot holding only a tree keeps its file under the tree key
            System.out.println("[Farm] " + bot.getUsername() + (gaveUp ? " gave up (" + why + ")" : " done") + " at " + Farming.SITES[farmSite].key.toLowerCase(Locale.ROOT)
                    + ", " + farmSold + " crops to the Exchange, spent " + farmSpent + " gp, " + (farmDue != 0 ? "next check in " + Math.max(0, (farmDue - now) / 60_000) + " min" : "nothing growing")
                    + (gaveUp ? " at " + bot.getLocation() : ""));
            if (W10Skills.HERB_RUN) { if (gaveUp && w10Leg >= 0 && W10Skills.HERB_LINES.ok(now)) System.out.println("[W10] " + bot.getUsername() + " herbRun leg " + Farming.SITES[w10At()].key.toLowerCase(Locale.ROOT) + " gave up (" + why + ")"); w10Legs = W10Skills.NO_LEGS; w10Leg = -1; } // W10 lane M
            farm = 0; farmAct = 0; farmNext = now + (gaveUp && farmDue == 0 ? 10 * 60_000L : 90 * 60_000L + RNG.nextInt(60 * 60_000)); // farm49 B4: a failed run with nothing growing retries soon
            BotChat.clearActivity(bot);
        }

        /** Units of a crop this bot has waiting in Exchange stock (Ledger keys a lot by the noted id). */
        private int farmWaiting(int id) {
            int n = Ledger.waiting(bot.getUsername(), id);
            try { core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id); if (d != null && d.getNoteId() > 0 && d.getNoteId() != id) n += Ledger.waiting(bot.getUsername(), d.getNoteId()); } catch (Throwable ignored) { }
            return n;
        }

        /** logOut: crops are never saved (section 3): counted as abandoned. */
        void farmLogout() {
            if (farm != 0) endFarm(true, "logout");
            if (farmDue != 0) {
                boolean kept = false;
                if (FarmSave.ON && farmSite >= 0 && farmSite < Farming.SITES.length) try { kept = FarmSave.save(bot, bot.getUsername(), Farming.SITES[farmSite].key, farmDue, "logout"); } catch (Throwable ignored) { } // W2: kept on disk, back with the bot (enlist)
                if (!kept) {
                    Farming.ABANDONED++;
                    System.out.println("[Farm] " + bot.getUsername() + " logged out with crops in the ground (lost)");
                }
                farmDue = 0;
            } else if (W9Trees.RUN && treeDue != 0) treeLogout(); // w9 trees (-Dbots.farming.trees.run): a bot holding only a tree files it under the tree's key
            else if (FarmSave.ON) try { FarmSave.logoutEmpty(bot, bot.getUsername()); } catch (Throwable ignored) { } // W2 review fix: a run cut short by the logout after its harvest (endFarm skips the save) must not leave the older file with the harvested crops in it
            if (W9Trees.RUN && treeDue != 0) { if (!FarmSave.ON) { W9Trees.LOST++; W9Trees.say(System.currentTimeMillis(), "[Farm] tree " + bot.getUsername() + " lost (logout) at " + treeAt()); } treeDue = 0; } // w9 trees: crops and a tree together: the allotment file above carries the tree's rows too (treeRestore finds it)
            Farming.free(bot.getUsername());
        }

        // ---- w9 trees (-Dbots.farming.trees.run, W9Trees.java, the design notes): tree and fruit-tree runs on the farm run's own
        // machinery (farm / farmMove / farmTick, farmGet / putBack / farmOffload, farmUse / farmWalkTo, the step cap). Nothing below runs with the flag off. ----
        static final int TA_CHECK = 11, TA_FRUIT = 12, TA_DIG = 13;

        private String treeAt() { return treeSite >= 0 && treeSite < W9Trees.SITES.length ? W9Trees.NAMES[treeSite] : "?"; }
        private int treeOwned(int id) { return id <= 0 ? 0 : bot.getInventory().getAmount(id) + bot.getBank().getAmount(id); }
        private static String treePlantName(content.global.skill.farming.Plantable pl) { return MarketBrain.name(pl.getItemID()).toLowerCase(Locale.ROOT); }
        private static int treeNoteOf(int id) {
            try { core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id); int n = d == null || d.isStackable() ? -1 : d.getNoteId(); return n > 0 && n != id ? n : -1; } catch (Throwable t) { return -1; }
        }
        /** The site's patch wrapper at its cache tile (W9Trees.SITES), null when that object is not there. */
        private static core.game.node.scenery.Scenery treeObj(int site) {
            try { int[] t = W9Trees.SITES[site]; core.game.node.scenery.Scenery o = RegionManager.getObject(t[W9Trees.SZ], t[W9Trees.SX], t[W9Trees.SY]); return o != null && o.getId() == t[W9Trees.SOBJ] ? o : null; } catch (Throwable t) { return null; }
        }
        /** The FarmSave key while the bot holds a tree and no allotment crops are due: the tree site's. Null otherwise, and always with the flag off. */
        String treeSaveKey() { return W9Trees.RUN && farmDue == 0 && treeDue != 0 && treeSite >= 0 && treeSite < W9Trees.KEYS.length ? W9Trees.KEYS[treeSite] : null; }
        /** The crop file under the key the bot's crops need: a tree-only bot under the tree key and treeDue, else the allotment site and farmDue as before
         *  (FarmSave.save removes the file when nothing is due). The engine's rows hold every patch, so allotments and a tree share one file. */
        private boolean farmSaveKeyed(String why) {
            final String tk = treeSaveKey();
            return FarmSave.save(bot, bot.getUsername(), tk != null ? tk : Farming.SITES[farmSite].key, tk != null ? treeDue : farmDue, why);
        }
        private void treeLogout() {
            boolean kept = false;
            if (FarmSave.ON) try { kept = farmSaveKeyed("logout"); } catch (Throwable ignored) { }
            if (!kept) { W9Trees.LOST++; W9Trees.say(System.currentTimeMillis(), "[Farm] tree " + bot.getUsername() + " lost (logout) at " + treeAt()); }
            treeDue = 0;
        }
        /** After FarmSave.restore: the tree in the restored rows (a tree-key file, or an allotment file that carried one) back on the Brain. A tree-key
         *  file gives its due; otherwise the due comes from the tree's stages left (soon when it is grown and waiting). */
        void treeRestore(FarmSave.Rec fr, long now) {
            try {
                content.global.skill.farming.timers.CropGrowth t = FarmSave.timerOf(bot);
                if (t == null) return;
                for (content.global.skill.farming.Patch p : t.getPatches()) {
                    if (p == null || p.getPlantable() == null) continue;
                    final int si = W9Trees.siteOfPatch(p.getPatch().name());
                    if (si < 0) continue;
                    final boolean waiting = p.isCheckHealth() || p.isDead() || p.isGrown() && (!W9Trees.fruit(si) || p.getFruitOrBerryCount() > 0);
                    treeSite = si;
                    treeDue = W9Trees.KEYS[si].equals(fr.site) ? fr.due : waiting ? now + FarmSave.SOON_MS
                            : W9Trees.dueAfter(true, false, p.isGrown(), W9Trees.fruit(si), 0, p.getPlantable().getStages() - p.getCurrentGrowthStage(), p.getStageGrowthMinutes(), now);
                    W9Trees.RESTORED++;
                    W9Trees.say(now, "[Farm] tree " + bot.getUsername() + " restored " + treePlantName(p.getPlantable()) + " at " + W9Trees.NAMES[si] + ", check in " + Math.max(0, (treeDue - now) / 60_000) + " min");
                    return;
                }
            } catch (Throwable e) { System.out.println("[Farm] tree restore for " + bot.getUsername() + ": " + e); }
        }

        /** At a trip end by a bank (planFarmRun, before the allotment chooser): a tree CHECK when due, from any bank within W9Trees.CHECK_R; else a PLANT
         *  at the nearest enabled site (W9Trees.chooseSite) when a sapling can be had (W8Polish.treePick / seedSource) and its check fits (plantOk).
         *  A tree check also brings a sapling when it can, to replant after the dig. False = no tree run: the allotment chooser goes on as before. */
        boolean planTreeRun(long now) {
            final boolean check = treeDue != 0 && now >= treeDue;
            if (!check && (treeDue != 0 || now < treeNext)) return false;
            if (!atBank()) return false;
            treeNext = now + W9Trees.REFUSE_MS;
            final Location me = bot.getLocation();
            final int lvl = bot.getSkills().getStaticLevel(Skills.FARMING);
            final int site;
            if (check) {
                if (treeSite < 0 || treeSite >= W9Trees.SITES.length) { treeDue = 0; return false; }
                if (!W9Trees.inRange(treeSite, me.getX(), me.getY(), me.getZ(), W9Trees.CHECK_R)) return W9Trees.skip(W9Trees.R_FAR);
                site = treeSite;
            } else {
                final boolean treeLvl = lvl >= W8Polish.TREE_ROWS[0][0], fruitLvl = lvl >= W8Polish.FRUIT_ROWS[0][0];
                if (!treeLvl && !fruitLvl) { treeNext = now + W9Trees.RUN_GAP_MS; return W9Trees.skip(W9Trees.R_LEVEL); }
                site = W9Trees.chooseSite(me.getX(), me.getY(), me.getZ(), treeLvl, fruitLvl, W9Trees.PLANT_R);
                if (site < 0) return W9Trees.skip(W9Trees.R_FAR);
            }
            if (treeObj(site) == null) return W9Trees.skip(W9Trees.R_NO_SITE);
            final boolean fruitKind = W9Trees.fruit(site);
            if (bot.getInventory().freeSlots() < (check && fruitKind ? 8 : 5)) return W9Trees.skip(W9Trees.R_SLOTS);
            final String name = bot.getUsername();
            if (!Farming.seat(name, k -> { Brain b = BRAINS.get(k); return b != null && (b.farm != 0 || b.farmDue != 0); }) && !check) return W9Trees.skip(W9Trees.R_CAP);
            // prep at the bank: spade (dig and plant), rake (plant), the sapling, the farmer's payment (noted), all under the run's budget
            final long coins0 = Ledger.Wallet.coins(bot);
            final long[] left = {W9Trees.budget(coins0)};
            final java.util.List<int[]> took = new ArrayList<>();
            final int[][] rows = W9Trees.rows(site);
            int why = W9Trees.R_SPADE, row = -1, sapling = -1, payId = -1, payQty = 0;
            final boolean spade = farmGet(Farming.SPADE, 1, true, left, took);
            if (spade && !(check && fruitKind)) { // a fruit tree stays standing after a check: no sapling for it
                why = W9Trees.R_SAPLING;
                final boolean rake = check || farmGet(Farming.RAKE, 1, true, left, took); // a dug-out patch is already weeded
                row = rake ? W8Polish.treePick(rows, lvl, seed -> treeSourced(rows, seed, left[0])) : -1;
                if (row >= 0 && !W9Trees.plantOk(FarmSave.ON, now, W8Polish.checkDue(now, rows[row][3]), shiftEnd) && !(W36Silent.TREES2 && !FarmSave.ON && W9Trees.plantOk(false, now, W36Silent.checkDue(true, false, now, rows[row][3]), shiftEnd))) { why = W9Trees.R_FIT; row = -1; }
                if (row >= 0) sapling = treeSaplingGet(rows[row], left, took);
                if (sapling > 0) {
                    W8Polish.treePlans++;
                    if (W36Silent.TREES2 && !FarmSave.ON) W36Silent.shortDue++; // w36-silent2 (-Dbots.farming.trees2fire): planned on the in-shift check
                    final int[] r = rows[row];
                    final int noted = r[4] > 0 ? treeNoteOf(r[4]) : -1;
                    final int owned = r[4] > 0 ? treeOwned(r[4]) + (noted > 0 ? bot.getInventory().getAmount(noted) : 0) : 0;
                    if (r[4] > 0 && W9Trees.payOk(r, owned, MarketBrain.stockOf(r[4]), MarketBrain.value(r[4]), left[0])) {
                        if (treePayGet(r[4], r[5], left)) { payId = r[4]; payQty = r[5]; }
                        else treeBankPay(r[4]); // a part payment goes back to the bank: the tree is planted unprotected
                    }
                }
            }
            if (!spade || !check && sapling < 0) {
                for (int[] t : took) putBack(t[0], t[1]);
                if (farmDue == 0) Farming.free(name);
                if (!check) treeNext = now + 3 * W9Trees.REFUSE_MS; // stock and coins change slowly
                else treeDue = now + W9Trees.RETRY_MS; // a CHECK that could not get a spade tries again later
                return W9Trees.skip(why);
            }
            final long spent = Math.max(0, coins0 - Ledger.Wallet.coins(bot));
            W9Trees.SPENT += spent; farmSpent = spent;
            for (int i = 0; i < W9Trees.FRUITS.length; i++) treeBase0[i] = bot.getInventory().getAmount(W9Trees.FRUITS[i]);
            farmTree = true; treeCheck = check; treeSite = site; treeSapling = sapling; treePayId = payId; treePayQty = payQty; treePlanted = false; treeGot = 0; treeProd = -1; treeDigWhat = "";
            farmIdx = 0; farmAct = 0; farmClicks = 0; farmStall = 0; farmWait = 0; farmStart = now; nextThink = now + 600; farmSold = 0;
            final int[] st = W9Trees.SITES[site];
            Bob.leave(bot, "busy"); farm = FARM_WALK; farmMove.reset(Location.create(st[W9Trees.SAX], st[W9Trees.SAY], st[W9Trees.SZ]), 3);
            W9Trees.RUNS++; if (check) W9Trees.CHECKS++;
            final String at = W9Trees.NAMES[site];
            BotChat.activity(bot, () -> (fruitKind ? "tending the fruit tree at " : "tending the tree patch at ") + at);
            W9Trees.say(now, "[Farm] tree " + name + " -> " + at + " to " + (check ? "check" : "plant") + (sapling > 0 ? " (" + MarketBrain.name(sapling).toLowerCase(Locale.ROOT)
                    + (payId > 0 ? ", pays " + payQty + " x " + MarketBrain.name(payId).toLowerCase(Locale.ROOT) : ", unprotected") + ")" : "") + ", farming " + lvl + ", spent " + spent + " gp");
            return true;
        }

        /** treePick's test: an owned sapling, or a seed with a source (W8Polish.seedSource) and a plant pot, inside the budget. */
        private boolean treeSourced(int[][] rows, int seed, long left) {
            final int r = W9Trees.rowOfSeed(rows, seed);
            if (r < 0) return false;
            final boolean pot = treeOwned(W9Trees.PLANT_POT) > 0 || treeOwned(W9Trees.PLANT_POT_FILLED) > 0;
            return W9Trees.saplingSource(treeOwned(rows[r][2]), treeOwned(seed), MarketBrain.stockOf(seed), MarketBrain.value(seed), pot, MarketBrain.value(W9Trees.PLANT_POT), left) > 0;
        }
        /** The sapling in the pack: an owned one, else grown from the seed (own bank, else bot stock via farmGet's Ledger.consume; never a shop) in a plant
         *  pot (owned, else the shop's price). The pot's growing time is skipped; the seed and the pot are used up (planting gives the pot back). -1 none. */
        private int treeSaplingGet(int[] row, long[] left, java.util.List<int[]> took) {
            final int sap = row[2], seed = row[1];
            if (treeOwned(sap) > 0 && farmGet(sap, 1, false, left, took)) return sap;
            if (!farmGet(seed, 1, false, left, took)) return -1;
            int pot = -1;
            if (treeOwned(W9Trees.PLANT_POT_FILLED) > 0 && farmGet(W9Trees.PLANT_POT_FILLED, 1, false, left, took)) pot = W9Trees.PLANT_POT_FILLED;
            else if (MarketBrain.value(W9Trees.PLANT_POT) > 0 && farmGet(W9Trees.PLANT_POT, 1, true, left, took)) pot = W9Trees.PLANT_POT; // never a free pot
            if (pot < 0) return -1;
            if (!bot.getInventory().remove(new Item(seed, 1))) return -1;
            if (!bot.getInventory().remove(new Item(pot, 1))) { bot.getInventory().add(new Item(seed, 1)); return -1; }
            if (!bot.getInventory().add(new Item(sap, 1))) { bot.getInventory().add(new Item(seed, 1)); bot.getInventory().add(new Item(pot, 1)); return -1; }
            took.add(new int[]{sap, 1}); W9Trees.GROWN++;
            return sap;
        }
        /** The farmer's payment in the pack, noted where the item has a note (25 coconuts in one slot): own pack and bank, then bot stock (Ledger.consume
         *  pays the sellers) under the budget. Never a shop. */
        private boolean treePayGet(int id, int qty, long[] left) {
            final int noted = treeNoteOf(id), carry = noted > 0 ? noted : id;
            int have = bot.getInventory().getAmount(id) + (noted > 0 ? bot.getInventory().getAmount(noted) : 0);
            if (have >= qty) return true;
            final int take = Math.min(qty - have, bot.getBank().getAmount(id));
            if (take > 0 && bot.getBank().remove(new Item(id, take))) {
                if (bot.getInventory().add(new Item(carry, take))) have += take; else bot.getBank().add(new Item(id, take));
            }
            final int need = qty - have;
            if (need > 0 && (long) MarketBrain.value(id) * need <= left[0] && MarketBrain.stockOf(id) >= need) {
                final long c0 = Ledger.Wallet.coins(bot);
                final int got = Ledger.consume(bot, id, need, "farming");
                if (got > 0) { if (bot.getInventory().add(new Item(carry, got))) have += got; else bot.getBank().add(new Item(id, got)); }
                left[0] -= Math.max(0, c0 - Ledger.Wallet.coins(bot));
            }
            return have >= qty;
        }
        /** Payment items left in the pack go back to the bank (unnoted). */
        private void treeBankPay(int id) {
            putBack(id, bot.getInventory().getAmount(id));
            final int noted = treeNoteOf(id), n = noted > 0 ? bot.getInventory().getAmount(noted) : 0;
            if (n > 0 && bot.getInventory().remove(new Item(noted, n)) && !bot.getBank().add(new Item(id, n))) bot.getInventory().add(new Item(noted, n));
        }
        /** The engine's "Pay" stand-in (FarmerPayOptionDialogue): the item, or its note, taken from the pack, and the patch protected. */
        private boolean treePay(content.global.skill.farming.Patch p) {
            final int id = treePayId, q = treePayQty, noted = treeNoteOf(id);
            final boolean paid = bot.getInventory().getAmount(id) >= q ? bot.getInventory().remove(new Item(id, q)) : noted > 0 && bot.getInventory().getAmount(noted) >= q && bot.getInventory().remove(new Item(noted, q));
            if (paid) p.setProtectionPaid(true);
            return paid;
        }
        private boolean treeHealth(core.game.node.scenery.Scenery s) {
            try { QuestSteps.clearPopups(bot); bot.faceLocation(s.getLocation()); return new content.global.skill.farming.HealthChecker().handle(bot, s, "check-health"); }
            catch (Throwable t) { System.out.println("[Farm] tree " + bot.getUsername() + " check-health threw " + t); return false; }
        }
        private boolean treePick(core.game.node.scenery.Scenery s) {
            try { QuestSteps.clearPopups(bot); bot.faceLocation(s.getLocation()); return new content.global.skill.farming.FruitAndBerryPicker().handle(bot, s, "pick-fruit"); }
            catch (Throwable t) { System.out.println("[Farm] tree " + bot.getUsername() + " pick threw " + t); return false; }
        }
        /** The spade stand-in (the engine's own dig clears a dead patch; DigUpPatchDialogue any other): the animation, then Patch.clear on the next check. */
        private void treeDig(core.game.node.scenery.Scenery s, String what) {
            try { bot.faceLocation(s.getLocation()); bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(830)); } catch (Throwable ignored) { }
            treeDigWhat = what; farmAct = TA_DIG; farmWait = 3; farmStall = 0;
        }
        private int treeGain() { return treeProd > 0 ? treeGot + Math.max(0, bot.getInventory().getAmount(treeProd) - treeProdBase) : 0; }
        /** A full pack while picking: this run's fruit to the Exchange (farmOffload), counted so the line still has it. */
        private int treeOffloadMid() {
            final int before = treeProd > 0 ? bot.getInventory().getAmount(treeProd) : 0;
            final int moved = farmOffload(W9Trees.FRUITS, treeBase0);
            if (treeProd > 0) { final int after = bot.getInventory().getAmount(treeProd); treeGot += Math.max(0, before - after); treeProdBase = Math.min(treeProdBase, after); }
            return moved;
        }
        private void treeFruitDone(long now) {
            final int g = treeGain();
            if (g > 0) { W9Trees.FRUIT_PICKED += g; W9Trees.say(now, "[Farm] tree " + bot.getUsername() + " fruit " + g + " x " + MarketBrain.name(treeProd).toLowerCase(Locale.ROOT) + " at " + treeAt()); }
            treeProd = -1; treeGot = 0;
        }

        /** One rule per think on the run's one patch (farmWork's pattern): dead or diseased -> dug out; check-health when the tree offers it; a fruit tree
         *  picked while it has fruit (and left standing); a grown tree dug out after its check; an empty patch raked and planted; then the farmer paid. */
        void treeWork(long now) {
            if (farmIdx >= 1) { treeFinish(now); return; }
            final core.game.node.scenery.Scenery s = treeObj(treeSite);
            final content.global.skill.farming.FarmingPatch fp = s == null ? null : content.global.skill.farming.FarmingPatch.forObject(s);
            if (fp == null) { farmStalled("tree patch missing"); return; }
            final content.global.skill.farming.Patch p = fp.getPatchFor(bot, true);
            final content.global.skill.farming.Plantable pl = p.getPlantable();
            final boolean fruitKind = W9Trees.fruit(treeSite);
            if (farmAct != 0) {
                final boolean pulse = bot.getPulseManager().hasPulseRunning();
                boolean ok;
                switch (farmAct) {
                    case FA_RAKE: ok = !pulse && !p.isWeedy(); break;
                    case FA_SEED: ok = pl != null; break;
                    case TA_CHECK: ok = !p.isCheckHealth(); break;
                    case TA_FRUIT: ok = !pulse; break;
                    case TA_DIG: {
                        final String what = pl != null ? treePlantName(pl) : "patch";
                        p.clear();
                        if ("dead".equals(treeDigWhat)) W9Trees.DIED++; else W9Trees.DUG++;
                        W9Trees.say(now, "[Farm] tree " + bot.getUsername() + " dig " + treeDigWhat + " " + what + " at " + treeAt());
                        ok = true; break;
                    }
                    default: ok = true;
                }
                if (ok) {
                    final int act = farmAct; farmAct = 0; farmStall = 0;
                    if (act != TA_DIG) farmClicks = Farming.stepsAfterOk(Farming.FIX1, farmClicks, act == TA_FRUIT, act == TA_FRUIT ? treeGain() : 0); // fix1's rule: a step that did something is given back (a dig is always counted, as FA_CLEAR)
                    if (act == FA_RAKE) farmWeeds();
                    if (act == FA_SEED) { treePlanted = true; W9Trees.PLANTED++; W9Trees.say(now, "[Farm] tree " + bot.getUsername() + " plant " + MarketBrain.name(treeSapling).toLowerCase(Locale.ROOT) + " at " + treeAt()); }
                    if (act == TA_CHECK) { W9Trees.CHECKED++; W9Trees.say(now, "[Farm] tree " + bot.getUsername() + " check " + (pl != null ? treePlantName(pl) : "tree") + " at " + treeAt()); }
                    return;
                }
                ++farmStall;
                if (farmAct != TA_FRUIT && farmStall >= 8 && !pulse) { farmAct = 0; farmStall = 0; return; } // C8 fix 1's retry: the rules run again, farmClicks caps it
                if (farmStall > 20) farmStalled("tree action " + farmAct);
                return;
            }
            if (bot.getPulseManager().hasPulseRunning()) return;
            if (++farmClicks > Farming.STEP_CAP) { Scen.tooManySteps(bot); farmStalled("too many steps"); return; }
            if (farmWalkTo(s)) return;
            // a. dead or diseased: dug out (curing a tree takes secateurs, which bots do not carry)
            if (pl != null && (p.isDead() || p.isDiseased())) { treeDig(s, "dead"); return; }
            // b. the health check, when the grown tree offers it (the Farming xp of a tree)
            if (pl != null && p.isCheckHealth()) {
                if (treeHealth(s)) { farmAct = TA_CHECK; farmStall = 0; return; }
                farmStalled("check-health not taken"); return;
            }
            // c. a grown fruit tree: picked while it has fruit and the pack has room, then left standing
            if (pl != null && p.isGrown() && fruitKind) {
                if (p.getFruitOrBerryCount() > 0 && (bot.getInventory().freeSlots() > 0 || treeOffloadMid() > 0)) {
                    if (treeProd < 0) { treeProd = pl.getHarvestItem(); treeProdBase = bot.getInventory().getAmount(treeProd); treeGot = 0; }
                    if (treePick(s)) { farmAct = TA_FRUIT; farmStall = 0; return; }
                }
                treeFruitDone(now); farmNextPatch(); return;
            }
            // d. a grown tree after its check (or a stump): dug out, so a sapling can go in
            if (pl != null && p.isGrown()) { treeDig(s, "grown"); return; }
            // e. empty: raked and planted when this run carries a sapling
            if (pl == null) {
                if (treeSapling <= 0 || treePlanted || bot.getInventory().getAmount(treeSapling) <= 0) { farmNextPatch(); return; }
                if (p.isWeedy()) { if (bot.getInventory().getAmount(Farming.RAKE) <= 0 || !farmUse(Farming.RAKE, s)) { farmNextPatch(); return; } farmAct = FA_RAKE; farmStall = 0; return; }
                if (p.getCurrentState() == 3) { if (farmUse(treeSapling, s)) { farmAct = FA_SEED; farmStall = 0; return; } farmStalled("sapling " + treeSapling + " not taken"); return; }
                farmNextPatch(); return;
            }
            // f. planted and growing: the farmer's payment, once, when this run brought it (W8Polish.payFarmer said so at the bank)
            if (!p.getProtectionPaid() && treePayId > 0) {
                final int id = treePayId, q = treePayQty;
                if (treePay(p)) { W9Trees.PAID++; W9Trees.say(now, "[Farm] tree " + bot.getUsername() + " pay " + q + " x " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + " at " + treeAt()); }
                treePayId = -1; return;
            }
            farmNextPatch();
        }

        /** When the next visit is due from the patch as it is now (W9Trees.dueAfter): 0 = nothing planted (or dead: the next plant run digs it). */
        private long treeDueNow(long now) {
            final core.game.node.scenery.Scenery s = treeObj(treeSite);
            final content.global.skill.farming.FarmingPatch fp = s == null ? null : content.global.skill.farming.FarmingPatch.forObject(s);
            if (fp == null) return treeDue;
            final content.global.skill.farming.Patch p = fp.getPatchFor(bot, true);
            final content.global.skill.farming.Plantable pl = p.getPlantable();
            if (pl == null || p.isDead()) return 0;
            final int[][] rows = W9Trees.rows(treeSite);
            final int row = W9Trees.rowOfSapling(rows, pl.getItemID());
            if (W36Silent.TREES2 && !FarmSave.ON && treePlanted && row >= 0) return W36Silent.checkDue(true, false, now, rows[row][3]); // w36-silent2 (-Dbots.farming.trees2fire): no save, so the check is due when the tree is grown
            return W9Trees.dueAfter(true, treePlanted, p.isGrown(), W9Trees.fruit(treeSite), row >= 0 ? rows[row][3] : 0, pl.getStages() - p.getCurrentGrowthStage(), p.getStageGrowthMinutes(), now);
        }
        private void treeFinish(long now) {
            farmWeeds(); treeFruitDone(now);
            treeDue = treeDueNow(now);
            final int[] st = W9Trees.SITES[treeSite];
            farm = FARM_BACK; farmMove.reset(Location.create(st[W9Trees.SBX], st[W9Trees.SBY], st[W9Trees.SZ]), 4);
        }
        /** Ends a tree run from anywhere (done, gave up, death, rescue, logout): fruit to the Exchange, tools, sapling, seed, pot and payment to the bank. */
        private void endTreeRun(boolean gaveUp, String why) {
            final long now = System.currentTimeMillis();
            final boolean dead = "death".equals(why);
            try { treeFruitDone(now); } catch (Throwable ignored) { }
            if (farm != FARM_BACK) { try { treeDue = treeDueNow(now); } catch (Throwable ignored) { } }
            if (!dead) {
                farmOffload(W9Trees.FRUITS, treeBase0);
                farmWeeds();
                for (int id : new int[]{Farming.RAKE, Farming.SPADE, W9Trees.PLANT_POT, W9Trees.PLANT_POT_FILLED}) putBack(id, bot.getInventory().getAmount(id));
                for (int id : W9Trees.saplings()) putBack(id, bot.getInventory().getAmount(id));
                for (int id : W9Trees.seeds()) putBack(id, bot.getInventory().getAmount(id));
                for (int id : Farming.allSeeds()) putBack(id, bot.getInventory().getAmount(id)); // Seed.fillPack (scenario seeding only)
                if (treePayId > 0) treeBankPay(treePayId);
            }
            if (treeCheck && !dead && !"logout".equals(why)) { // as C8 review S1: a tree CHECK that keeps failing gives the tree up
                if (!gaveUp || farm == FARM_BACK) treeFails = 0;
                else if (treeDue != 0 && ++treeFails >= Farming.CHECK_FAILS) {
                    W9Trees.ABANDONED++;
                    W9Trees.say(now, "[Farm] tree " + bot.getUsername() + " gave up its tree at " + treeAt() + " after " + treeFails + " failed checks");
                    treeDue = 0; treeFails = 0;
                }
            }
            if (farmDue == 0) Farming.free(bot.getUsername());
            if (FarmSave.ON && !"logout".equals(why)) try { farmSaveKeyed("tree run end"); } catch (Throwable ignored) { } // a logout saves in farmLogout
            W9Trees.say(now, "[Farm] tree " + bot.getUsername() + (gaveUp ? " gave up (" + why + ")" : " done") + " at " + treeAt() + ", spent " + farmSpent + " gp, "
                    + (treeDue != 0 ? "next check in " + Math.max(0, (treeDue - now) / 60_000) + " min" : "nothing growing") + (gaveUp ? " at " + bot.getLocation() : ""));
            farm = 0; farmAct = 0; farmTree = false; treeSapling = -1; treePayId = -1;
            treeNext = now + (gaveUp && treeDue == 0 ? W9Trees.REFUSE_MS : W9Trees.RUN_GAP_MS);
            BotChat.clearActivity(bot);
        }

        /** P69 #10: a Watchdog rescue ends the bone run too (else it keeps burying where it lands for up to 5 min). */
        W37Craft.Run w37; long w37At; int w37Turn; // w37-craft: null / 0 with both flags off
        void boneRescued() { if (bone != 0) { System.out.println("[Prayer] " + bot.getUsername() + " rescued, stops burying"); endBone(true); } }

        /** The best tool of this kind the bot may use that a shop stocks, or -1. */
        int usableTool(int[] kind, int[] byLevel, int level, Set<Integer> stock) {
            int idx = level >= 41 ? 5 : level >= 31 ? 4 : level >= 21 ? 3 : level >= 6 ? 2 : 1;
            for (int i = Math.min(idx, byLevel.length - 1); i >= 0; i--) if (stock.contains(byLevel[i])) return byLevel[i];
            return -1;
        }

        /**
         * Before a job, check what the bot is missing (its tool, or food for a fight) and, if a real shop stocks it,
         * walk there first. Returns true when a trip was started (the job starts once the shopping is done).
         */
        boolean planShopTrip(Job job) {
            if (skipShopOnce) { skipShopOnce = false; return false; }
            Player p = bot;
            int[] jobTools = P90.tools(job.task); // P90 fix 1: the essence mine needs a pickaxe too
            boolean needTool = jobTools != null && !AfkTasks.hasAny(p, jobTools);
            int food = foodHeld();
            boolean needFood = (job.task.kind == AfkTasks.Kind.FIGHT || job.task.kind == AfkTasks.Kind.THIEVE || job.hunt != null || W5bSkills.STALLS && job.task.kind == AfkTasks.Kind.STALL) && food < 8; // W5b stalls: food against the market guards
            List<int[]> supply = fightJob(job) && styleOf(bot) != MELEE ? supplyNeeds() : new ArrayList<>();
            boolean needSupply = !supply.isEmpty();
            if (!armourChecked) { // once per trip, before tools and food: dressed properly?
                armourChecked = true;
                // If not, walk to an armour shop and buy a matched set first (coins leave the game).
                List<int[]> pieces = armourNeeds();
                Object[] shop = pieces.isEmpty() ? null : armourShop(pieces);
                if (shop == null) return planShopTrip(job); // dressed, or nothing affordable: on to tools and food (no loop: armourChecked is set)
                Set<Integer> sells = new HashSet<>(); for (int sid : (int[]) shop[3]) sells.add(sid);
                pieces.removeIf(pc -> !sells.contains(pc[0])); // only what this shop has; the rest from the next shop next time
                shopAt = Location.create((Integer) shop[1], (Integer) shop[2], 0); shopName = (String) shop[0];
                shopBuy.clear(); shopBuy.addAll(pieces); pendingJob = job; armourTrip = true;
                shopDeadline = System.currentTimeMillis() + (FAST ? 180_000 : 480_000);
                shopBest = Double.MAX_VALUE; shopStalls = 0;
                final String sn = shopName;
                if (RNG.nextInt(2) == 0) say(String.format(BotChat.pick("Can't go out dressed like this. Off to %s.", "Need proper armour first. %s it is.", "Time for a new set. %s.", "Time for some proper armour. %s.", "My kit's a disgrace. %s, here I come.", "Upgrade time. Off to %s."), sn));
                BotChat.activity(bot, () -> "buying armour at " + sn);
                if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + p.getUsername() + " walking to " + sn + " for " + pieces.size() + " pieces of armour");
                return true;
            }
            if (!needTool && !needFood && !needSupply) return false;
            Object[] best = null; double bestD = 600; List<int[]> buy = null;
            Object[] sameBest = null; double sameD = 600; List<int[]> sameBuy = null; // P39 R7 (-Dreach.sea): the nearest shop on this side of the sea
            for (Object[] shop : BotChat.WorldData.shops) {
                int[] stockArr = (int[]) shop[3];
                Set<Integer> stock = new HashSet<>(); for (int id : stockArr) stock.add(id);
                List<int[]> here = new ArrayList<>();
                if (needTool) {
                    int t = jobTools == AfkTasks.AXES ? usableTool(jobTools, AXE_BY_LEVEL, p.getSkills().getStaticLevel(Skills.WOODCUTTING), stock)
                            : jobTools == AfkTasks.PICKAXES ? usableTool(jobTools, PICK_BY_LEVEL, p.getSkills().getStaticLevel(Skills.MINING), stock)
                            : stock.contains(jobTools[0]) ? jobTools[0] : -1;
                    if (t < 0) continue; // this shop can't sell the tool, and the tool is the point
                    here.add(new int[]{t, 1});
                }
                if (needFood) for (int f : SHOP_FOODS) { if (goodFoodOnly) { boolean good = false; for (int g : GOOD_FOOD) if (g == f) good = true; if (!good) continue; } if (stock.contains(f)) { here.add(new int[]{f, 10}); break; } }
                if (needSupply) { boolean any = false; for (int[] su : supply) if (stock.contains(su[0])) { here.add(new int[]{su[0], su[1]}); any = true; } if (!any && !needTool && !needFood) continue; }
                if (here.isEmpty()) continue;
                if (!Preflight.ok(p, Location.create((Integer) shop[1], (Integer) shop[2], 0))) continue; // batch 20 A1
                double d = Location.create((Integer) shop[1], (Integer) shop[2], 0).getDistance(p.getLocation());
                if (d < bestD) { bestD = d; best = shop; buy = here; }
                if (AfkTasks.Runner.REACH_SEA && d < sameD && !AfkTasks.Runner.acrossSea(p.getLocation(), Location.create((Integer) shop[1], (Integer) shop[2], 0))) { sameD = d; sameBest = shop; sameBuy = here; }
            }
            if (AfkTasks.Runner.REACH_SEA && sameBest != null && best != sameBest) { best = sameBest; buy = sameBuy; bestD = sameD; } // P39 R7: no 180 gp teleport across the sea when a shop this side has it; none this side: the old pick
            if (best == null) return false; // nobody nearby sells it: the old way (stock or a nominal shop)
            long cost = 0; for (int[] b : buy) cost += (long) Math.max(1, MarketBrain.value(b[0])) * b[1];
            if (Ledger.Wallet.coins(p) < cost) return false;
            shopAt = Location.create((Integer) best[1], (Integer) best[2], 0);
            shopName = (String) best[0];
            shopBuy.clear(); shopBuy.addAll(buy);
            pendingJob = job;
            shopDeadline = System.currentTimeMillis() + (FAST ? 180_000 : 480_000);
            shopBest = Double.MAX_VALUE; shopStalls = 0;
            String what = MarketBrain.name(buy.get(0)[0]).toLowerCase(Locale.ROOT);
            if (RNG.nextInt(2) == 0) say(String.format(BotChat.pick("Need a %s first. Off to %s.", "Can't go without a %s. %s it is.", "Quick stop at %2$s for a %1$s.", "No %s, no work. %s it is.", "Can't start without a %s. Popping to %s.", "Right, a %s. %s should have one."), what, shopName));
            final String sn = shopName;
            BotChat.activity(bot, () -> "walking to " + sn + " for supplies");
            if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + p.getUsername() + " walking " + (int) bestD + " tiles to " + shopName + " for " + what + " before " + job.label());
            return true;
        }

        Boolean shopRetal; kotlin.jvm.functions.Function1<Player, kotlin.Unit> shopRetalHook; // batch 20 B1
        int preBusy; // batch 20 review: consecutive BUSY answers from Preflight.out on this shop walk

        /**
         * batch 20 B1: attacked on a shop walk, the bot walks on (retaliation off until the walk ends, run on, its swing
         * stopped) instead of stopping to fight whatever is by the road; the walk's own low-health eat and teleport stay.
         */
        void shopGuard() {
            if (!AfkTasks.Runner.TRANSIT_ON) return;
            if (shopRetal == null) {
                if (!bot.inCombat()) return;
                shopRetal = bot.getProperties().isRetaliating();
                bot.getProperties().setRetaliating(false);
                shopRetalHook = pl -> { if (shopRetal != null) { pl.getProperties().setRetaliating(shopRetal); shopRetal = null; } return kotlin.Unit.INSTANCE; };
                try { bot.logoutListeners.put("shop-retaliate", shopRetalHook); } catch (Throwable ignored) { }
                bot.getSettings().setRunToggled(true);
            }
            if (bot.getProperties().getCombatPulse().isAttacking()) bot.getProperties().getCombatPulse().stop();
        }

        void endShopGuard() {
            if (shopRetal != null) { bot.getProperties().setRetaliating(shopRetal); shopRetal = null; }
            if (shopRetalHook != null) { try { bot.logoutListeners.remove("shop-retaliate", shopRetalHook); } catch (Throwable ignored) { } shopRetalHook = null; }
        }

        void shopTick(long now) {
            Location here = bot.getLocation();
            if (shopFor != shopAt) { shopFor = shopAt; shopFrom = here; seaPaid = 0; Watchdog.ftrip(bot, here); } // batch 18 item2: a new shop walk // batch 19a: the trip's start
            if (here.getDistance(shopAt) <= 5 || here.getDistance(shopAt) <= 9 && shopStalls >= 2) { finishShop(); return; } // at the door counts: shops sit inside buildings
            if (now > shopDeadline) {
                System.out.println("[SmartAdv] " + bot.getUsername() + " never reached " + shopName + " (stuck at " + here + "); buying elsewhere");
                try { Watchdog.scenario(bot, "never_reached", "shop", shopFrom, shopAt, here, (shopDeadline == 0 ? "gave up: couldn't cross the sea" : "not there by the deadline, closest " + (shopBest == Double.MAX_VALUE ? "?" : String.valueOf((int) shopBest)) + " tiles, " + shopStalls + " stalls") + " (" + shopName + ")"); Watchdog.Daily.note("shop", String.valueOf(shopName)); } catch (Throwable ignored) { } // batch 18 item2
                // Kit, arrows and runes still get bought (from "another shop", coins leave the game) so a ranger or mage isn't left without.
                for (int[] b : shopBuy) {
                    boolean supply = b[0] == 556 || b[0] == 558 || b[0] == 562 || b[0] == 560; for (int a : ARROWS) if (a == b[0]) supply = true;
                    if (!armourTrip && !supply) continue;
                    long cost = (long) Math.max(1, MarketBrain.value(b[0])) * b[1];
                    if (!Ledger.Wallet.pay(bot, cost)) continue;
                    if (armourTrip && b[1] == 1) { wearKit(b[0]); continue; } // rec-review 10-23 batch14 item1; worn, or wear() put it in the pack or bank (audit 10-23 S-1)
                    bot.getBank().add(new Item(b[0], b[1]));
                }
                Job j = pendingJob; shopAt = null; pendingJob = null; skipShopOnce = j != null; goalTrip = false; armourTrip = false; exchangeTrip = false; sea = null;
                if (j != null) start(j);
                return;
            }
            if (sea != null && seaFor != this.shopAt) sea = null; // batch 17 item3: an earlier shop walk's
            if (sea != null) { // batch 17 item3: crossing the sea for this shop
                if (sea.crossing) { if (shopDeadline < now + 60_000) shopDeadline = now + 60_000; return; } // the boat's own pulse drives it; the walk's clock waits
                boolean gaveUp = sea.halted; sea = null;
                if (gaveUp) { shopDeadline = 0; return; } // can't cross: given up the way a stalled walk is ("never reached ... buying elsewhere")
            }
            { // batch 20 A2/A3: out of Morytania westward, up from a dungeon, down from an upper floor, before the surface walk
                Preflight.Go pf = Preflight.out(bot, this.shopAt);
                if (pf == Preflight.Go.BUSY && ++preBusy > 30) pf = Preflight.Go.FAILED; // batch 20 review: a climb that never ends (2 min of thinks) gives the walk up
                if (pf == Preflight.Go.BUSY) { if (shopDeadline < now + 60_000) shopDeadline = now + 60_000; return; }
                preBusy = 0;
                if (pf == Preflight.Go.FAILED) { preBusy = 0; Preflight.forget(bot); System.out.println("[SmartAdv] " + bot.getUsername() + " can't set out for " + shopName + " from " + here); shopDeadline = 0; return; }
            }
            if (AfkTasks.Runner.acrossSea(here, this.shopAt) && bot.getProperties().getTeleportLocation() == null) { // batch 17 item3: a straight walk to a Karamja shop ended at the Port Sarim shore (17 of 21 Watchdog rescues there); the trip's crossing instead
                final Location target = this.shopAt;
                boolean cooling = seaPaidShop != null && seaPaidShop.getX() == target.getX() && seaPaidShop.getY() == target.getY() && now - seaPaidAt < SEA_COOL_MS;
                if (SEA_CAP && (seaPaid >= 1 || cooling)) { // batch 21e: one paid crossing per shop walk, and none again to the same shop for SEA_COOL_MS (a re-picked walk); -Dsea.cap=false turns it off
                    System.out.println("[SmartAdv] " + bot.getUsername() + " crossing the sea to " + shopName + " from " + here + ": not again (" + (seaPaid >= 1 ? "paid once on this walk" : "paid " + (now - seaPaidAt) / 1000 + " s ago") + ")");
                    shopDeadline = 0; return; // given up: "never reached ... buying elsewhere"
                }
                long before = Ledger.Wallet.coins(bot);
                sea = AfkTasks.Runner.crossSea(bot, target, () -> this.shopAt == target && bot.isActive()); seaFor = target;
                long fare = Math.max(0, before - Ledger.Wallet.coins(bot)); // batch 21e: the teleport's charge (the boat's 30 gp is paid on board, later)
                if (sea.crossing || fare > 0) { seaPaid++; seaPaidAll++; seaPaidShop = target; seaPaidAt = now; }
                System.out.println("[SmartAdv] " + bot.getUsername() + " crossing the sea to " + shopName + " from " + here + ": " + (sea.crossing ? "the boat" : sea.halted ? "can't (" + sea.last + ")" : sea.last + (fare > 0 ? " (" + fare + " gp)" : "")));
                if (!sea.crossing && !sea.halted) sea = null; // a teleport or the home teleport: the ordinary walk carries on from where it lands
                else if (sea.halted) { sea = null; shopDeadline = 0; }
                return;
            }
            if (bot.getWalkingQueue().isMoving()) return;
            Location shopAt = this.shopAt; // the walking target: where people stand at this shop, if the map knows (audit B5)
            try { Location w = RouteMap.walkedNear(shopAt, 3); if (w != null) shopAt = w; } catch (Throwable ignored) { } // only the tiles right at the counter
            int toll = AfkTasks.Runner.tollHop(bot, this.shopAt); // batch 19c: the same call, its answer kept
            if (toll < 0) try { Failures.Econ.toll(bot, "alkharid", 10); } catch (Throwable ignored) { } // batch 19c §4.7 toll-unaffordable
            if (toll < 0 && NavPlanner.TOLL_ON && AfkTasks.Runner.tollStuck(bot, this.shopAt)) { shopDeadline = 0; return; } // batch 21 A: can't pay: give the walk up now, not parked at the gate until the deadline (with -Dnav.toll=false the batch 20 way: it keeps trying and the Watchdog rescues it, rule-gate); review fix 2: only with no toll-free route
            if (toll > 0 || toll < 0 && !NavPlanner.TOLL_ON) return; // the Al Kharid gate on a shop walk; review fix 2: broke with a route round the fence: walks on as a non-payer
            int pass = AfkTasks.Runner.passHop(bot, this.shopAt, false); // batch 21 B2: the Shantay Pass (the desert shops: Nardah, Pollnivneach, the Bedabin camp)
            if (pass < 0) { shopDeadline = 0; return; }
            if (pass != 0) return;
            Location pv = AfkTasks.Runner.passVia(here, this.shopAt); if (pv != null) shopAt = pv; // batch 21 B2: across the pass: to the pass first
            if (viaFor != this.shopAt) { viaFor = this.shopAt; viaLast = null; viaTurns = 0; viaPlan = null; } // pathing review 10-23 A-5: a new shop walk
            Location via; if (P73_FIX && NavWalker.ON && viaPlanned()) { via = AfkTasks.Runner.avoidLeg(bot, here, shopAt, null); p73Count("shop-planned", P73_SHOP); } else via = viaTurns > 6 ? null : AfkTasks.Runner.bridgeVia(bot, here, shopAt); /* P73 B6: the planner has a route: no river/exit vias (the Lumbridge exit rules ping-ponged Al Kharid -> Port Sarim walks for minutes, "never reached Gerrant's" at 3025,3253 / 3026,3245); the jail AVOID box stays */ if (via != null && here.getDistance(via) > 3) shopAt = via; // rivers, walled towns, the jail: go round
            if (via != null && !via.equals(viaLast)) { viaLast = via; if (++viaTurns == 7) System.out.println("[SmartAdv] " + bot.getUsername() + " going round in circles on the way to " + shopName + " at " + here + "; straight there from now on"); } // pathing review 10-23 A-5: the rules took it round in a loop: without them for the rest of this walk (as the quest Mover does after two retries)
            if (bot.inCombat() && bot.getSkills().getLifepoints() <= bot.getSkills().getMaximumLifepoints() / 2) { // attacked on the way with no runner to defend it (3 deaths at the Draynor jail)
                if (Ledger.Wallet.payTeleport(bot)) { Location near = Surveyors.Surveyor.walkable(Location.create(shopAt.getX() + RNG.nextInt(5) - 2, shopAt.getY() + RNG.nextInt(5) - 2, 0)); try { Spectacle.teleport(bot, near != null ? near : shopAt); } catch (Throwable ignored) { } say(BotChat.pick("Nope! Teleporting.", "Not today. Tele.", "Off I go!")); return; } // batch 15 item1
                int[] f = AfkTasks.Runner.FOOD; for (int id : f) { if (bot.getInventory().getAmount(id) > 0) { try { bot.getInventory().remove(new Item(id, 1)); bot.getSkills().heal(10); } catch (Throwable ignored) { } break; } }
            }
            double d = here.getDistance(shopAt);
            if (d > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.coins(bot) >= 5_000 && Ledger.Wallet.payTeleport(bot)) {
                Location near = Surveyors.Surveyor.walkable(Location.create(shopAt.getX() + RNG.nextInt(9) - 4, shopAt.getY() + RNG.nextInt(9) - 4, 0));
                try { Spectacle.teleport(bot, near != null ? near : shopAt); } catch (Throwable ignored) { } // batch 15 item1
                return;
            }
            // On a learned route, progress means moving along it; off a route, getting closer as the crow flies.
            Location hop = null; if (NavWalker.ON) { try { hop = NavWalker.step(bot, shopAt, true); } catch (Throwable ignored) { } } // batch 21 A: pays the toll (tollHop above); the real planner first (the design notes)
            boolean mapHop = hop == null; // pathing review 10-23 R-2: the hop is RouteMap's
            if (hop == null) try { hop = RouteMap.nextHop(bot, shopAt); } catch (Throwable ignored) { }
            if (mapHop && hop != null && (hop.equals(here) || RouteMap.usingShortcut(bot))) { shopStalls = 0; shopLast = here; return; } // pathing review 10-23 R-2: nextHop just clicked the route's shortcut (it returns the bot's own tile while one is in use); walking to "here" reset the walk its click started, and the stall count then learned the shortcut as failing
            if (hop != null) { if (shopLast == null || here.getDistance(shopLast) >= 2) { shopStalls = 0; shopLast = here; } else shopStalls++; shopBest = Math.min(shopBest, d); }
            else if (d < shopBest - 2) { shopBest = d; shopStalls = 0; } else shopStalls++;
            long tp = Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556));
            boolean late = d > 30 && now > shopDeadline - (FAST ? 90_000 : 240_000); // half the time gone and still far off
            if ((shopStalls >= 4 || late) && Ledger.Wallet.coins(bot) >= tp * 3 && Ledger.Wallet.payTeleport(bot)) {
                Location near = Surveyors.Surveyor.walkable(Location.create(shopAt.getX() + RNG.nextInt(7) - 3, shopAt.getY() + RNG.nextInt(7) - 3, 0));
                try { Spectacle.teleport(bot, near != null ? near : shopAt); } catch (Throwable ignored) { } // batch 15 item1
                if (RNG.nextInt(2) == 0) say(BotChat.pick("Can't get through here. Teleporting.", "Blocked. Sod it, teleport.", "Road's shut. Taking the quick way."));
                shopStalls = 0; return;
            }
            if (shopStalls >= 3 && hop != null) { try { RouteMap.stuck(bot, shopAt); } catch (Throwable ignored) { } hop = null; Watchdog.flog(bot, "shop: route stuck, 3 stalls"); } // the route didn't work here: learn it, replan next time // batch 19a design 3.2
            if (shopStalls >= 2 && hop == null) {
                double ang = Math.atan2(shopAt.getY() - here.getY(), shopAt.getX() - here.getX()) + (RNG.nextBoolean() ? 1 : -1) * (Math.PI / 2 + RNG.nextDouble() * 0.6);
                Location side = Surveyors.Surveyor.walkable(Location.create(here.getX() + (int) Math.round(Math.cos(ang) * 18), here.getY() + (int) Math.round(Math.sin(ang) * 18), 0));
                if (side != null) { try { ((AIPlayer) bot).walkToPosSmart(side); } catch (Throwable ignored) { } shopStalls = 0; Watchdog.flog(bot, "shop: side step"); Watchdog.fdrv(bot, Failures.DRV_OLD); return; } // batch 19a design 3.1/3.2
            }
            if (hop == null) Watchdog.fdrv(bot, Failures.DRV_OLD); // batch 19a design 3.1: the old straight 25-tile hop
            Location step = hop != null ? hop : d <= 25 ? shopAt : Location.create(here.getX() + (int) Math.round((shopAt.getX() - here.getX()) * 25 / d), here.getY() + (int) Math.round((shopAt.getY() - here.getY()) * 25 / d), 0);
            Location w = d <= 25 || hop != null ? null : Surveyors.Surveyor.walkable(step);
            try { ((AIPlayer) bot).walkToPosSmart(w != null ? w : step); } catch (Throwable ignored) { }
        }

        /** batch 19c §4.5: the listed stock of a shop by its title (BotChat.WorldData.shops), or null when unknown. */
        static Set<Integer> shopStock(String name) {
            if (name == null) return null;
            for (Object[] s : BotChat.WorldData.shops) if (name.equals(s[0])) { Set<Integer> st = new HashSet<>(); for (int id : (int[]) s[3]) st.add(id); return st; }
            return null;
        }

        void finishShop() {
            Player p = bot;
            if (W7cSkills.PLANK_TYPES && w7PlankTrip != 0 && W7cHooks.atExchange(this, bot, System.currentTimeMillis())) return; // w22 (-Dbots.plankTypes): the plain plank walk to the Exchange ends here (flag off: the field stays 0)
            if (exchangeTrip) { // at the Grand Exchange: buy the kit, arrows, runes and potions here, then on with the job
                exchangeTrip = false;
                Job j = pendingJob;
                try { kitFromExchange(); if (styleOf(bot) != MELEE) suppliesFromExchange(); potionsFromExchange(j); otherPotionsFromExchange(j); } catch (Throwable t) { System.out.println("[SmartAdv] exchange buy failed for " + bot.getUsername() + ": " + t); }
                shopAt = null; pendingJob = null; nextThink = System.currentTimeMillis() + 3_000;
                if (j != null) start(j);
                return;
            }
            // Face whoever is behind the counter and buy, paying the shop's price in real coins.
            try {
                core.game.node.entity.npc.NPC keeper = null; double kd = 8;
                for (core.game.node.entity.npc.NPC n : RegionManager.getLocalNpcs(p, 8)) { double dd = n.getLocation().getDistance(p.getLocation()); if (dd < kd) { kd = dd; keeper = n; } }
                if (keeper != null) p.face(keeper);
            } catch (Throwable ignored) { }
            StringBuilder got = new StringBuilder(); long paid = 0;
            boolean[] paidLine = new boolean[shopBuy.size()]; int line = 0; long coins0 = Ledger.Wallet.coins(p); // batch 19c §4.5
            for (int[] b : shopBuy) {
                long price = (long) Math.max(1, MarketBrain.value(b[0])) * b[1];
                int li = line++;
                if (!Ledger.Wallet.pay(p, price)) continue;
                paidLine[li] = true;
                if (Slayer.ON && b[1] == 1) { toolBoughtId = b[0]; toolBoughtAt = System.currentTimeMillis(); } // P65 v1.3 tool-loop guard; P71: behind the flag (flag off: no write)
                if (b[1] > 1 && p.getInventory().freeSlots() < b[1]) p.getBank().add(new Item(b[0], b[1])); else for (int i = 0; i < b[1]; i++) packOrBank(p, new Item(b[0])); // audit B-11: a full pack lost a single paid item
                paid += price;
                if (got.length() > 0) got.append(", ");
                got.append(b[1] > 1 ? b[1] + " " : "").append(MarketBrain.name(b[0]).toLowerCase(Locale.ROOT));
            }
            try { Failures.econNote(p, Failures.SHOP); Failures.Econ.shopArrive(p, shopName, new ArrayList<>(shopBuy), shopStock(shopName), paidLine, coins0); } catch (Throwable ignored) { } // batch 19c §4.5
            if (got.length() > 0) {
                if (RNG.nextInt(2) == 0) say(BotChat.pick("Ta very much.", "Cheers, that'll do me.", "Lovely. Right, off to work.", "Pleasure as always."));
                System.out.println("[SmartAdv] " + p.getUsername() + " bought " + got + " at " + shopName + " for " + paid + " gp (on foot)");
            }
            if (armourTrip) {
                armourTrip = false;
                int worn = 0;
                for (int[] b : shopBuy) {
                    if (bot.getInventory().getAmount(b[0]) <= 0) continue;
                    if (wearKit(b[0])) worn++; // rec-review 10-23 batch14 item1
                }
                try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
                if (worn > 0 && RNG.nextInt(2) == 0) say(BotChat.pick("That's more like it.", "Looking the part now.", "New armour. Feels good.", "Feels good to be dressed properly.", "Now I look the part.", "Much better.", "Shiny."));
            }
            if (goalTrip) {
                goalTrip = false;
                int g = goal;
                if (g > 0 && bot.getInventory().getAmount(g) > 0) {
                    wear(bot, g); try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
                    String nm = MarketBrain.name(g);
                    say(String.format(BotChat.pick("Finally! Got my %s.", "Bought the %s. Months of work, that.", "Look at this. %s. Worth every coin.", "Look at it. My very own %s.", "The %s is mine! Took long enough.", "Worth every trip. One %s."), nm.toLowerCase(Locale.ROOT)));
                    BotChat.news(String.format(BotChat.pick("%s finally bought that %s they kept going on about.", "Saw %s walk out of %s with a brand new %s. Chuffed to bits."), bot.getUsername(), nm.toLowerCase(Locale.ROOT), shopName));
                    System.out.println("[SmartAdv] " + bot.getUsername() + " bought their goal at " + shopName + ": " + nm);
                    feel(Mood.CHATTY, 15 * 60_000);
                    goal = pickGoal(bot); GOAL.put(bot.getUsername().toLowerCase(Locale.ROOT), goal);
                }
            }
            Job j = pendingJob; shopAt = null; pendingJob = null;
            nextThink = System.currentTimeMillis() + 3_000;
            if (generalStore(shopName)) sellOld(); // rec-review 10-23 batch14 item1
            else if (sellTrip(j)) return;          // rec-review 10-23 batch14 item1: the old pieces first, then the job
            if (j != null) start(j);
        }

        boolean fightJob(Job job) { return job.hunt != null || job.task.kind == AfkTasks.Kind.FIGHT; }

        boolean exchangeChecked, exchangeTrip;
        long w7PlankTrip; // w22 (-Dbots.plankTypes): deadline of a walk to the Exchange for plain plank supplies, 0 = none

        /** Something on the Exchange worth the walk: kit it's missing, arrows/runes, prayer potions. */
        boolean exchangeHasWhatINeed(Job job) {
            for (int[] pc : armourNeeds()) if (MarketBrain.stockOf(pc[0]) > 0) return true;
            if (styleOf(bot) != MELEE) for (int[] su : supplyNeeds()) if (MarketBrain.stockOf(su[0]) > 0) return true;
            if (!potionBuys(job).isEmpty()) return true; // batch 15 items3-4
            return potionsWanted(job) > 0 && MarketBrain.stockOf(2434) > 0;
        }

        /** batch 15 items3-4: potions held of these families (any dose), bank and pack. */
        int heldPots(int... fams) {
            int n = 0;
            for (int f : fams) for (int id : AfkTasks.Runner.POTS[f]) n += bot.getBank().getAmount(id) + bot.getInventory().getAmount(id);
            return n;
        }

        /** batch 15 item4: in stock, to buy; none on the Exchange, the herbalist hears of it. */
        static void wantPot(List<Integer> out, int id) { if (MarketBrain.stockOf(id) > 0) out.add(id); else Artisans.wantPotion(id); }

        /**
         * batch 15 items3-4: the (3)-dose potions to buy for this hunt that the Exchange has: one antipoison against
         * monsters that poison, attack and strength (or combat) potions for a fighter, a ranging potion for a ranger, before
         * a level 50+ hunt. Only what it doesn't already hold; bought from bot stock (the herbalist who made it is paid).
         */
        List<Integer> potionBuys(Job job) {
            List<Integer> out = new ArrayList<>();
            if (job == null || job.hunt == null) return out;
            if (poisonous(job.hunt.name) && heldPots(AfkTasks.Runner.P_ANTI, AfkTasks.Runner.P_SANTI) == 0) {
                if (MarketBrain.stockOf(175) > 0) out.add(175); else wantPot(out, 181);
            }
            if (job.hunt.npcLevel >= 50) {
                int st = styleOf(bot);
                if (st == RANGED) { if (heldPots(AfkTasks.Runner.P_RANGE) == 0) wantPot(out, 169); }
                else if (st == MELEE) {
                    boolean att = heldPots(AfkTasks.Runner.P_ATT, AfkTasks.Runner.P_SATT, AfkTasks.Runner.P_COMBAT) > 0, str = heldPots(AfkTasks.Runner.P_STR, AfkTasks.Runner.P_SSTR, AfkTasks.Runner.P_COMBAT) > 0;
                    if (!att || !str) {
                        if (MarketBrain.stockOf(145) > 0 && MarketBrain.stockOf(157) > 0) { if (!att) out.add(145); if (!str) out.add(157); }
                        else if (MarketBrain.stockOf(121) > 0 && MarketBrain.stockOf(115) > 0) { if (!att) out.add(121); if (!str) out.add(115); }
                        else if (MarketBrain.stockOf(9741) > 0) out.add(9741);
                        else { if (!att) wantPot(out, 121); if (!str) wantPot(out, 115); }
                    }
                }
            }
            return out;
        }

        int potionsWanted(Job job) {
            if (job == null || job.hunt == null || job.hunt.npcLevel < 60 || bot.getSkills().getStaticLevel(Skills.PRAYER) < 43) return 0;
            int have = 0; for (int pot : new int[]{2434, 139, 141, 143}) have += bot.getBank().getAmount(pot) + bot.getInventory().getAmount(pot);
            return Math.max(0, 4 - have);
        }

        void potionsFromExchange(Job job) {
            int n = potionsWanted(job);
            if (n <= 0 || MarketBrain.stockOf(2434) <= 0) return;
            int got = Ledger.consume(bot, 2434, Math.min(n, MarketBrain.stockOf(2434)), "prayer");
            if (got > 0) bot.getBank().add(new Item(2434, got));
        }

        /** batch 15 items3-4: the antipoison and boost potions potionBuys names, one of each, out of bot stock. */
        void otherPotionsFromExchange(Job job) {
            for (int id : potionBuys(job)) if (MarketBrain.stockOf(id) > 0 && Ledger.consume(bot, id, 1, "potion") == 1) bot.getBank().add(new Item(id, 1));
        }

        /** W8 (-Dbots.kit.amulets): the best amulet for its style out of bot stock (the crafter or enchanter is paid). 1 = bought. */
        int w8Amulet() {
            int[] list = W8Polish.AMULET_BY_STYLE[Math.max(0, Math.min(2, styleOf(bot)))];
            Item cur = bot.getEquipment().get(2);
            int rank = W8Polish.amuletRank(list, cur == null ? -1 : cur.getId());
            if (rank == 0) return 0;
            int[] price = new int[list.length], stock = new int[list.length];
            for (int i = 0; i < list.length; i++) { price[i] = Math.max(1, MarketBrain.chargePrice(list[i])); stock[i] = MarketBrain.stockOf(list[i]); } // w13d BUG-1
            int i = W8Polish.amuletPick(list, rank, Ledger.Wallet.coins(bot), price, stock);
            if (i < 0) { W8Polish.amuletNone++; return 0; }
            if (Ledger.consume(bot, list[i], 1, "kit") != 1) { W8Polish.amuletNone++; return 0; }
            W8Polish.amuletBuys++;
            boolean worn = wear(bot, list[i]); // the old amulet goes to the Exchange or the bank
            System.out.println("[W8] amulet " + bot.getUsername() + " bought " + MarketBrain.name(list[i]) + " from bot stock (" + price[i] + " gp" + (worn ? "" : ", not worn") + ")");
            return 1;
        }

        boolean w8RangedOn; // W8 (-Dbots.ranged.melee): this melee bot wears a bow from its ranged trip
        /** W8 (-Dbots.ranged.melee): a bow it owns (or buys: bot stock, else a shop) and arrows for the trip; its melee weapon goes to the bank. */
        void w8RangedTrip(HuntMap.Hunt h) {
            int r = bot.getSkills().getStaticLevel(Skills.RANGE);
            int bow = -1; for (int i = BOWS.length - 2; i >= 0; i--) if (r >= BOW_LEVEL[i] && ownsOne(BOWS[i])) { bow = BOWS[i]; break; } // the yew stays with the rangers
            if (bow < 0) {
                int price = Math.max(1, MarketBrain.value(BOWS[0]));
                if (Ledger.Wallet.coins(bot) - (MarketBrain.CHARGE_CHECK && MarketBrain.stockOf(BOWS[0]) > 0 ? MarketBrain.chargePrice(BOWS[0]) : price) < 1_000) { W8Polish.rangedNoKit++; return; } // w13d BUG-1 (-Dmarket.chargeCheck): a stock bow is charged chargePrice
                if (!w8Room(BOWS[0], 1)) { W8Polish.noRoom++; W8Polish.rangedNoKit++; return; } // t63 fix 3: pay only with room in pack or bank
                int got = MarketBrain.stockOf(BOWS[0]) > 0 ? Ledger.consume(bot, BOWS[0], 1, "weapon") : 0;
                long paid = 0; if (got <= 0 && sold(BOWS[0]) && Ledger.Wallet.pay(bot, price)) { got = 1; paid = price; }
                if (got <= 0) { W8Polish.rangedNoKit++; return; }
                if (!w8Place(BOWS[0], 1, paid, false, "ranged bow")) { W8Polish.rangedNoKit++; return; } bow = BOWS[0]; W8Polish.rangedBowBought++;
            }
            int bi = 0; for (int i = 0; i < BOWS.length; i++) if (BOWS[i] == bow) bi = i;
            int arrow = ARROWS[0]; for (int i = 0; i < ARROWS.length; i++) if (r >= ARROW_LEVEL[i] && ARROW_MAX_BOW[i] <= bi && held(ARROWS[i]) >= W8Polish.RM_ARROWS) arrow = ARROWS[i];
            int have = held(arrow);
            if (have < W8Polish.RM_ARROWS) { // a trip's arrows: bot stock first (the fletcher is paid), else a shop; a quarter of the purse at most
                int need = W8Polish.RM_ARROWS - have, unit = Math.max(1, MarketBrain.value(arrow));
                if ((long) (MarketBrain.CHARGE_CHECK && MarketBrain.stockOf(arrow) >= need ? MarketBrain.chargePrice(arrow) : unit) * need > supplyBudget()) { W8Polish.rangedNoKit++; return; } // w13d BUG-1 (-Dmarket.chargeCheck): stock arrows are charged chargePrice
                if (!w8Room(arrow, need)) { W8Polish.noRoom++; W8Polish.rangedNoKit++; return; } // t63 fix 3: pay only with room in bank or pack
                int got = MarketBrain.stockOf(arrow) >= need ? Ledger.consume(bot, arrow, need, "arrows") : 0;
                long paid = 0; if (got <= 0 && sold(arrow) && Ledger.Wallet.pay(bot, paid = (long) unit * need)) got = need; else if (got <= 0) paid = 0;
                if (got <= 0) { W8Polish.rangedNoKit++; return; }
                if (!w8Place(arrow, got, paid, true, "ranged arrows")) { W8Polish.rangedNoKit++; return; }
            }
            if (!toPack(bow) || !stowWeapon() || !wear(bot, bow)) { W8Polish.rangedNoKit++; try { wearMeleeWeapon(); } catch (Throwable ignored) { } return; }
            w8RangedOn = true;
            int n = bot.getBank().getAmount(arrow); if (n > 0) bot.getBank().remove(new Item(arrow, n));
            int i = bot.getInventory().getAmount(arrow); if (i > 0) { bot.getInventory().remove(new Item(arrow, i)); n += i; }
            Item q = bot.getEquipment().get(13);
            if (q != null && q.getId() != arrow && n > 0) { bot.getEquipment().remove(q); bot.getBank().add(q); }
            if (n > 0 && !bot.getEquipment().add(new Item(arrow, n), -1, true, false)) bot.getBank().add(new Item(arrow, n));
            try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } // the bow keeps its own default style (W5b owns the ranged setStyle names)
            W8Polish.rangedTrips++;
            System.out.println("[W8] ranged " + bot.getUsername() + " takes a " + MarketBrain.name(bow) + " and " + n + " x " + MarketBrain.name(arrow) + " to " + HuntMap.label(h) + " (Ranged " + r + ")");
        }
        /** W8 (-Dbots.ranged.melee): after a ranged trip, the bow to the bank and the best scimitar it owns back on (train-61's melee fallback). */
        void w8RangedBack() {
            w8RangedOn = false;
            Item w = bot.getEquipment().get(3);
            boolean bowOn = false; if (w != null) for (int b : BOWS) if (b == w.getId()) bowOn = true;
            if (!bowOn) return;
            wearMeleeWeapon(); W8Polish.rangedBack++;
        }

        /** W8 (-Dbots.magic.enchant): enchant unenchanted amulets out of bot stock; amulets and runes paid; the enchanted ones go on the Exchange as this bot's stock. */
        void w8Enchant() {
            int m = bot.getSkills().getStaticLevel(Skills.MAGIC);
            int L = W8Polish.ENCH.length; int[] stock = new int[L]; boolean[] glut = new boolean[L];
            for (int i = 0; i < L; i++) { stock[i] = MarketBrain.stockOf(W8Polish.ENCH[i][0]); glut[i] = MarketBrain.isSurplus(W8Polish.ENCH[i][1]); }
            int row = W8Polish.enchRow(m, stock, glut);
            if (row < 0) return;
            int[] e = W8Polish.ENCH[row];
            long unit = (long) MarketBrain.chargePrice(e[0]) + MarketBrain.eitherPrice(W8Polish.COSMIC) + (long) MarketBrain.eitherPrice(e[3]) * e[4]; // w13d BUG-1: what consume (or the shop) really takes
            long now = System.currentTimeMillis();
            int n = W8Polish.enchQty(stock[row], W8Polish.enchHourUsed(now), Ledger.Wallet.coins(bot), unit);
            if (n <= 0) { W8Polish.enchCapped++; return; }
            int[][] runes = {{W8Polish.COSMIC, n}, {e[3], n * e[4]}};
            for (int[] rn : runes) { // the cast's runes are bought for it (the mage's own combat runes are left alone): bot stock (the runecrafter is paid), else a shop (coins leave the game)
                int need = rn[1];
                if (!w8Room(rn[0], need)) { W8Polish.noRoom++; W8Polish.enchCapped++; return; } // t63 fix 3: pay only with room in bank or pack
                int got = MarketBrain.stockOf(rn[0]) >= need ? Ledger.consume(bot, rn[0], need, "runes") : 0;
                long paid = 0; if (got <= 0 && sold(rn[0]) && Ledger.Wallet.pay(bot, paid = (long) Math.max(1, MarketBrain.value(rn[0])) * need)) got = need; else if (got <= 0) paid = 0;
                if (got <= 0) { W8Polish.enchCapped++; return; }
                if (!w8Place(rn[0], got, paid, true, "enchant runes")) { W8Polish.enchCapped++; return; }
            }
            for (int[] rn : runes) if (bot.getBank().getAmount(rn[0]) < rn[1]) { W8Polish.enchCapped++; return; } // the runes really are in the bank (a full bank refused them): no cast without them
            int got = Ledger.consume(bot, e[0], n, "enchant"); // the amulets: the crafters who made them are paid
            if (got <= 0) { W8Polish.enchCapped++; return; }
            for (int[] rn : runes) bot.getBank().remove(new Item(rn[0], rn[0] == W8Polish.COSMIC ? got : got * e[4])); // the runes are used up (what was bought for amulets the stock could not give stays in its bank)
            W8Polish.enchUse(got);
            Ledger.supply(bot, e[1], got); // on the Exchange as this bot's goods (it is paid when they sell)
            try { bot.getSkills().addExperience(Skills.MAGIC, got * e[5] / 10.0, true); } catch (Throwable ignored) { }
            W8Polish.enchRuns++; W8Polish.enchMade += got;
            System.out.println("[W8] enchant " + bot.getUsername() + " enchanted " + got + " x " + MarketBrain.name(e[0]) + " into " + MarketBrain.name(e[1]) + " (Magic " + m + ", " + W8Polish.enchHourUsed(now) + " of " + W8Polish.ENCH_HOUR + " this hour)");
        }

        long w8KitSpent; // W8 (-Dbots.kitAboveRune): gp this bot spent on pieces above rune this server run
        /** W8 (-Dbots.kitAboveRune): at most one piece above rune per Exchange visit, out of bot stock only (the seller is paid). 1 = bought. */
        int w8KitAboveRune() {
            int def = bot.getSkills().getStaticLevel(Skills.DEFENCE), str = bot.getSkills().getStaticLevel(Skills.STRENGTH);
            if (def < 50 || W8Polish.lowDef(bot.getUsername())) return 0;
            long coins = Ledger.Wallet.coins(bot);
            for (int k = 0; k < W8Polish.ABOVE.length; k++) {
                int slot = W8Polish.ABOVE_SLOTS[k]; int[][] list = W8Polish.ABOVE[k];
                if (slot == 0 && stashHelm != 0 || slot == 5 && stashShield != 0) continue;
                Item cur = bot.getEquipment().get(slot);
                int tableK = slot == 0 ? 0 : slot == 4 ? 1 : slot == 7 ? 2 : 3; // ARMOUR_SLOTS index
                int rank = W8Polish.rankOf(list, cur == null ? -1 : cur.getId(), cur != null && tierOf(tableK, cur.getId()) >= 0);
                if (rank <= 0) continue;
                int[] price = new int[list.length], stock = new int[list.length];
                boolean any = false;
                for (int i = 0; i < list.length; i++) { price[i] = Math.max(1, MarketBrain.chargePrice(list[i][0])); stock[i] = MarketBrain.stockOf(list[i][0]); /* w13d BUG-1 */ if (i < rank && stock[i] > 0 && def >= list[i][1] && str >= list[i][2]) any = true; }
                int i = W8Polish.pick(list, def, str, rank, coins, w8KitSpent, price, stock);
                if (i < 0) { if (any) W8Polish.kitCapped++; else W8Polish.kitNoStock++; continue; }
                int id = list[i][0];
                long before = Ledger.Wallet.coins(bot);
                if (Ledger.consume(bot, id, 1, "kit") != 1) { W8Polish.kitNoStock++; continue; }
                long paid = Math.max(0, before - Ledger.Wallet.coins(bot));
                w8KitSpent += paid; W8Polish.kitSpent += paid; W8Polish.kitBuys++;
                boolean worn = wear(bot, id); // the old piece goes to the Exchange or the bank, as for any kit upgrade; not worn: wear() put it in the pack or bank
                System.out.println("[W8] kit " + bot.getUsername() + " bought " + MarketBrain.name(id) + " from bot stock for " + paid + " gp (Defence " + def + ", spent " + w8KitSpent + " of " + W8Polish.KIT_CAP + (worn ? "" : ", not worn") + ")");
                return 1;
            }
            return 0;
        }

        /** Kit pieces the Exchange has in stock are bought there (the smith, fletcher or crafter who made them is paid). */
        void kitFromExchange() {
            int got = 0;
            if (W10Skills.THROWN && styleOf(bot) == RANGED) try { W10Hooks.thrown(this); } catch (Throwable t) { System.out.println("[W10] thrown for " + bot.getUsername() + ": " + t); } // W10 lane M (-Dbots.ranged.thrown): darts/knives this trip when cheaper than arrows, decided before styleKit (no RNG, got unchanged)
            for (int[] pc : armourNeeds()) {
                if (MarketBrain.stockOf(pc[0]) <= 0) continue;
                long sc0 = Scen.coins0(bot); // w14 scen kit2 (0, nothing read, unless Seed.ON)
                if (Ledger.consume(bot, pc[0], 1, "kit") == 1) { if (W6Combat.KIT2 && W6Combat.isKit2(pc[0])) { W6Combat.kitBought++; Scen.kit2(bot, pc[0], sc0); System.out.println("[W6c] kit2 " + bot.getUsername() + " bought " + MarketBrain.name(pc[0]) + " off the Exchange (Ranged " + bot.getSkills().getStaticLevel(Skills.RANGE) + ")"); } if (wearKit(pc[0])) got++; } // not worn: wear() has put it in the pack or bank (audit 10-23 S-1)
            }
            if (W8Polish.AMULETS) try { got += w8Amulet(); } catch (Throwable t) { System.out.println("[W8] amulet for " + bot.getUsername() + ": " + t); } // W8 (-Dbots.kit.amulets)
            if (W8Polish.ENCHANT && styleOf(bot) == MAGIC) try { w8Enchant(); } catch (Throwable t) { System.out.println("[W8] enchant for " + bot.getUsername() + ": " + t); } // W8 (-Dbots.magic.enchant)
            if (W8Polish.KIT_ABOVE_RUNE && styleOf(bot) == MELEE) try { got += w8KitAboveRune(); } catch (Throwable t) { System.out.println("[W8] kitAboveRune for " + bot.getUsername() + ": " + t); } // W8 (-Dbots.kitAboveRune)
            if (W10Skills.SPEC && styleOf(bot) == MELEE) try { W10Hooks.spec(this); } catch (Throwable t) { System.out.println("[W10] spec for " + bot.getUsername() + ": " + t); } // W10 lane M (-Dbots.attack.spec): a 60+ melee bot wields a spec weapon from bot stock (no RNG, got unchanged)
            if (W10Skills.ARROWS && styleOf(bot) == RANGED) try { W10Hooks.arrows(this); } catch (Throwable t) { System.out.println("[W10] arrows for " + bot.getUsername() + ": " + t); } // W10 (-Dbots.fletch.arrows): a ranger tips its own arrows from bot stock (no RNG, got unchanged)
            if (W10Skills.SUPERHEAT) try { W10Hooks.superheat(this); } catch (Throwable t) { System.out.println("[W10] superheat for " + bot.getUsername() + ": " + t); } // W10 (-Dbots.magic.superheat): a Magic 43+ bot superheats bot-stock ore into bars for the Exchange (no RNG, got unchanged)
            if (got > 0) { try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } if (RNG.nextInt(2) == 0) say(BotChat.pick("Picked up some kit off the Exchange.", "New gear from the Exchange. Lovely.")); }
        }

        /** W10 lane M (-Dbots.ranged.thrown): a ranger under the level cap wielding darts or knives; only ever asked behind W10Skills.THROWN. */
        boolean w10Thrown() { Item w = bot.getEquipment().get(3); return w != null && W10Skills.thrownIndex(w.getId()) >= 0 && styleOf(bot) == RANGED && bot.getSkills().getStaticLevel(Skills.RANGE) < W10Skills.THROWN_MAX; }
        /** t63 fix 3: room for n x id in the bank or the pack (checked before paying). */
        boolean w8Room(int id, int n) { Item it = new Item(id, n); return bot.getBank().hasSpaceFor(it) || bot.getInventory().hasSpaceFor(it); }
        /** t63 fix 3: n x id (paid: coins paid for them, 0 = from bot stock) into the bank or pack; neither takes them -> W8Polish.unplaced gives the coins back (or puts them back on the Exchange). */
        boolean w8Place(int id, int n, long paid, boolean bankFirst, String what) {
            boolean ok = bankFirst ? bot.getBank().add(new Item(id, n)) || bot.getInventory().add(new Item(id, n)) : bot.getInventory().add(new Item(id, n)) || bot.getBank().add(new Item(id, n));
            if (!ok) W8Polish.unplaced(bot, id, n, W8Polish.refundCoins(paid, 0, n), what);
            return ok;
        }
        int held(int id) { return bot.getBank().getAmount(id) + bot.getInventory().getAmount(id) + (bot.getEquipment().get(13) != null && bot.getEquipment().get(13).getId() == id ? bot.getEquipment().get(13).getAmount() : 0); }

        /** Out of runes mid-trip: autocast off (F1: for the trip's spell). With F3 on, the runner ends the trip on its next tick. */
        void runesCheck() {
            if (styleOf(bot) == MAGIC && bot.getProperties().getAutocastSpell() != null) { int[] s = style5() && tripSpell != null ? tripSpell : spell(); if (bot.getInventory().getAmount(s[1]) < s[2] || bot.getInventory().getAmount(s[3]) < s[4]) { bot.getProperties().setAutocastSpell(null); say(BotChat.pick("Out of runes. Staff it is.", "No runes left. Bonk mode.")); } }
        }

        /** train-5 F1: a trip's supplies cost about a quarter of the coins over the 300 kept back. */
        long supplyBudget() { return Math.max(0, (Ledger.Wallet.coins(bot) - 300) / 4); }

        /** F1: arrows already held (>= 40, best tier first) are the trip's; else the best tier whose 40 fit the budget (bronze always). */
        int pickTripArrow() {
            int best = bestArrow(), bi = 0; for (int i = 0; i < ARROWS.length; i++) if (ARROWS[i] == best) bi = i;
            for (int i = bi; i >= 0; i--) if (held(ARROWS[i]) >= 40) return ARROWS[i];
            long b = supplyBudget(); int i = bi;
            while (i > 0 && 40L * Math.max(1, MarketBrain.value(ARROWS[i])) > b) i--;
            if (W6Combat.KIT2) i = W6Combat.arrowTier(i, ARROWS, MarketBrain.stockOf(W6Combat.RUNE_ARROW)); // W6 (-Dbots.ranged.kit2): rune arrows only while bot stock covers a trip over the floor
            return ARROWS[i];
        }

        static final int[][] SPELL_TIERS = {{38, 560, 1, 556, 4}, {20, 562, 1, 556, 3}, {8, 558, 1, 556, 2}, {1, 558, 1, 556, 1}}; // the spell() rows, top down
        long castCost(int[] s) { return Math.max(1, (long) Math.max(1, MarketBrain.value(s[1])) * s[2] + (long) Math.max(1, MarketBrain.value(s[3])) * s[4]); }

        /** F1: from the level's spell down: runes held for 40 casts first, else the first tier whose 40 casts fit the budget (wind strike always). */
        int[] pickTripSpell() {
            if (W6Magic.ANY_STAFF && wornBasicStaff()) return W6Magic.WIND_STRIKE; // W6 Magic (-Dbots.magic.anyStaff): a basic staff casts wind strike only
            int top = 0, lvl = spell()[0]; for (int i = 0; i < SPELL_TIERS.length; i++) if (SPELL_TIERS[i][0] == lvl) top = i;
            for (int i = top; i < SPELL_TIERS.length; i++) { int[] s = SPELL_TIERS[i]; if (held(s[1]) >= 40 * s[2] && held(s[3]) >= 40 * s[4]) return s; }
            long b = supplyBudget(); int i = top;
            while (i < SPELL_TIERS.length - 1 && 40 * castCost(SPELL_TIERS[i]) > b) i++;
            return SPELL_TIERS[i];
        }

        /** Arrows or runes it still needs for a fight trip: {item, amount}. */
        List<int[]> supplyNeeds() {
            List<int[]> out = new ArrayList<>();
            int st = styleOf(bot);
            if (st == RANGED && W10Skills.THROWN && w10Thrown()) return out; // W10 lane M (-Dbots.ranged.thrown): the darts/knives are the ammo
            if (style5() && st == RANGED) { // F1: sized to the purse, the tier drops before the quantity
                int a = pickTripArrow(); tripArrow = a; int h = held(a);
                long v = Math.max(1, MarketBrain.value(a)), b = supplyBudget(), purse = Ledger.Wallet.coins(bot) - 300;
                if (h < 40 && 40 * v <= purse) out.add(new int[]{a, (int) Math.max(40, Math.min(W6Combat.KIT2 ? W6Combat.tripCap(a, 200) : 200, b / v)) - h}); // W6 kit2: the top arrow capped per trip
                return out;
            }
            if (style5() && st == MAGIC) {
                int[] s = pickTripSpell(); tripSpell = s;
                long cc = castCost(s), b = supplyBudget(), purse = Ledger.Wallet.coins(bot) - 300;
                if (40 * cc > purse) return out; // can't pay for 40 casts and keep 300: melee this trip (F2), the loot pays for the next
                int casts = (int) Math.max(40, Math.min(150, b / cc));
                int h1 = held(s[1]); if (h1 < 20 * s[2]) out.add(new int[]{s[1], casts * s[2] - h1});
                int h2 = held(s[3]); if (h2 < 20 * s[4]) out.add(new int[]{s[3], casts * s[4] - h2});
                return out;
            }
            if (st == RANGED) { int a = bestArrow(); int h = held(a); if (h < 100) out.add(new int[]{a, (W6Combat.KIT2 ? Math.max(h + 1, W6Combat.tripCap(a, 200)) : 200) - h}); }
            else if (st == MAGIC) { int[] s = spell(); int casts = 150;
                int h1 = held(s[1]); if (h1 < casts * s[2] / 2) out.add(new int[]{s[1], casts * s[2] - h1});
                int h2 = held(s[3]); if (h2 < casts * s[4] / 2) out.add(new int[]{s[3], casts * s[4] - h2}); }
            return out;
        }

        /** Arrows and runes from Exchange stock first: that's the fletcher's and runecrafter's custom. */
        void suppliesFromExchange() {
            for (int[] su : supplyNeeds()) {
                int take = Math.min(su[1], Math.max(0, MarketBrain.stockOf(su[0])));
                if (W6Combat.KIT2) take = W6Combat.takeCap(su[0], take, MarketBrain.stockOf(su[0])); // W6 (-Dbots.ranged.kit2): the top arrow leaves the Exchange its floor
                if (take <= 0) continue;
                int got = Ledger.consume(bot, su[0], take, "ammo");
                if (W6Combat.KIT2 && su[0] == W6Combat.RUNE_ARROW && got > 0) W6Combat.topArrows += got;
                if (got > 0) bot.getBank().add(new Item(su[0], got));
            }
        }

        /** Arrows into the quiver, runes into the pack, the right spell set to autocast. */
        void equipSupplies() {
            int st = styleOf(bot);
            try {
                if (st == RANGED) {
                    int a = bestArrow();
                    if (style5()) { // F1: the trip arrow (what it holds or bought); with none of it, any lower tier it does hold
                        a = pickTripArrow();
                        if (held(a) == 0) { int bi = 0; for (int i = 0; i < ARROWS.length; i++) if (ARROWS[i] == bestArrow()) bi = i; for (int i = bi; i >= 0; i--) if (held(ARROWS[i]) > 0) { a = ARROWS[i]; break; } }
                        tripArrow = a;
                    }
                    int n = bot.getBank().getAmount(a); if (n > 0) bot.getBank().remove(new Item(a, n));
                    int i = bot.getInventory().getAmount(a); if (i > 0) { bot.getInventory().remove(new Item(a, i)); n += i; }
                    Item cur = bot.getEquipment().get(13);
                    if (cur != null && cur.getId() != a && n > 0) { bot.getEquipment().remove(cur); bot.getBank().add(cur); }
                    if (n > 0 && !bot.getEquipment().add(new Item(a, n), -1, true, false)) bot.getBank().add(new Item(a, n));
                } else if (st == MAGIC) {
                    int[] s = spell();
                    if (style5()) { s = pickTripSpell(); tripSpell = s; }
                    if (W6Magic.ANY_STAFF && wornBasicStaff()) s = W6Magic.WIND_STRIKE; // W6 Magic: wind strike with a basic staff
                    for (int r : new int[]{s[1], s[3]}) { int n = bot.getBank().getAmount(r); if (n > 0 && bot.getBank().remove(new Item(r, n)) && !bot.getInventory().add(new Item(r, n))) bot.getBank().add(new Item(r, n)); }
                    Item staff = bot.getEquipment().get(3);
                    boolean ready = staff != null && (staff.getId() == 1387 || W6Magic.ANY_STAFF && staff.getId() == W6Magic.BASIC_STAFF) && bot.getInventory().getAmount(s[1]) >= s[2] && bot.getInventory().getAmount(s[3]) >= s[4];
                    bot.getProperties().setAutocastSpell(ready ? (core.game.node.entity.combat.spell.CombatSpell) core.game.node.entity.player.link.SpellBookManager.SpellBook.MODERN.getSpell(s[0]) : null);
                    castTrip = ready && bot.getProperties().getAutocastSpell() != null; // F3: armed at the trip start
                }
                bot.getEquipment().refresh();
                if (Boolean.getBoolean("afk.debug")) { Item w = bot.getEquipment().get(3), q = bot.getEquipment().get(13); System.out.println("[SmartAdv] " + bot.getUsername() + (st == RANGED ? " ranger" : " mage") + ": weapon " + (w == null ? "none" : w.getName()) + ", ammo " + (q == null ? "none" : q.getAmount() + " " + q.getName()) + ", autocast " + bot.getProperties().getAutocastSpell() + ", style " + bot.getProperties().getCombatPulse().getStyle()); }
            } catch (Throwable t) { if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] supplies " + bot.getUsername() + ": " + t); }
        }

        /** W5b defShare: Longrange (Ranged + Defence XP) while Defence is 10+ under Ranged; back to accurate once it has caught up. Only when the stance differs. */
        int w34RangedTrips; // W34 defShare2: this bot's ranged trip starts since the restart (flag on only)
        void w5bDefShare() {
            String want = W5bSkills.rangerStance(bot.getSkills().getStaticLevel(Skills.DEFENCE), bot.getSkills().getStaticLevel(Skills.RANGE));
            if (W34Skills.DEF_SHARE2) { // W34 (-Dbots.style.defShare2): gap 5, and this bot's every 2nd ranged trip while Defence is under Ranged (a counter, no RNG)
                int d34 = bot.getSkills().getStaticLevel(Skills.DEFENCE), r34 = bot.getSkills().getStaticLevel(Skills.RANGE), why34 = W34Skills.stanceWhy(d34, r34, ++w34RangedTrips, W34Skills.DEF_EVERY);
                want = W34Skills.rangerStance2(d34, r34, w34RangedTrips, W34Skills.DEF_EVERY);
                if (why34 != 0) W34Skills.longTrip(bot.getUsername(), d34, r34, why34);
            }
            int cur = -1; try { cur = bot.getProperties().getAttackStyle().getStyle(); } catch (Throwable ignored) { }
            boolean isLong = cur == core.game.node.entity.combat.equipment.WeaponInterface.STYLE_LONG_RANGE;
            if ("longrange".equals(want) == isLong) return;
            AfkTasks.setStyle(bot, want);
            if ("longrange".equals(want)) W5bSkills.defLong++; else W5bSkills.defBack++;
        }

        boolean ownsOne(int id) { return bot.getBank().getAmount(id) + bot.getInventory().getAmount(id) > 0; }

        /** One copy into the pack (from the bank if need be); false if it can't be. */
        boolean toPack(int id) {
            if (bot.getInventory().getAmount(id) > 0) return true;
            if (bot.getBank().getAmount(id) <= 0 || !bot.getBank().remove(new Item(id, 1))) return false;
            if (bot.getInventory().add(new Item(id, 1))) return true;
            bot.getBank().add(new Item(id, 1)); return false;
        }

        /** The worn weapon into the bank (not the Exchange, as wear() would send it): it is the bot's for the next trip. */
        boolean stowWeapon() {
            Item w = bot.getEquipment().get(3);
            if (w == null) return true;
            if (!bot.getEquipment().remove(w)) return false;
            if (bot.getBank().add(w) || bot.getInventory().add(w)) return true;
            bot.getEquipment().add(w, -1, true, false); return false;
        }

        /** train-5 F2: a ranger's own bow (best it can use) or a mage's staff back on when it wears something else and owns one. */
        void styleWeaponBack() {
            int st = styleOf(bot); Item w = bot.getEquipment().get(3);
            if (st == RANGED && W10Skills.THROWN && w10Thrown()) return; // W10 lane M (-Dbots.ranged.thrown): its darts/knives stay on
            if (st == RANGED ? w != null && bowIndex(w.getId()) >= 0 : w != null && w.getId() == 1387) return;
            if (W6Magic.ANY_STAFF && st == MAGIC && w != null && w.getId() == W6Magic.BASIC_STAFF && !ownsOne(1387)) return; // W6 Magic: its basic staff stays on until it owns the staff of fire
            int want = -1, r = bot.getSkills().getStaticLevel(Skills.RANGE);
            if (st == RANGED) { for (int i = BOWS.length - 1; i >= 0 && want < 0; i--) if (r >= BOW_LEVEL[i] && ownsOne(BOWS[i])) want = BOWS[i]; }
            else if (st == MAGIC && ownsOne(1387)) want = 1387;
            else if (W6Magic.ANY_STAFF && st == MAGIC && ownsOne(W6Magic.BASIC_STAFF)) want = W6Magic.BASIC_STAFF; // W6 Magic: else its basic staff
            if (want < 0 || !toPack(want) || !stowWeapon()) return;
            if (wear(bot, want)) { try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } System.out.println("[SmartAdv] " + bot.getUsername() + " back on its own " + MarketBrain.name(want).toLowerCase(Locale.ROOT)); }
        }

        /**
         * W3 fix 7 (-Dbots.style.restock): the last chance before styleFallback. A ranger with a bow and no arrows it can fire, a ranger
         * with no bow at all, or a mage with its staff and no runes buys 40+ arrows or casts of the best tier the purse less 100 gp pays
         * for (the Exchange at the GE, else the shop: coins leave the game). A mage without a staff is not helped here. True = bought.
         */
        boolean w3Restock() {
            int st = styleOf(bot); long coins = Ledger.Wallet.coins(bot);
            if (st == RANGED && W10Skills.THROWN && w10Thrown()) return false; // W10 lane M (-Dbots.ranged.thrown)
            Item w = bot.getEquipment().get(3), q = bot.getEquipment().get(13);
            if (st == RANGED) {
                boolean bow = w != null && bowIndex(w.getId()) >= 0;
                if (bow && q != null && bowFires(w.getId(), q.getId())) return false;
                int bowId = bow ? w.getId() : -1; long bowCost = 0;
                if (!bow) { for (int b : BOWS) if (bowId < 0 && ownsOne(b)) bowId = b; if (bowId < 0) { bowId = BOWS[0]; bowCost = Math.max(1, MarketBrain.value(BOWS[0])); } }
                if (!bow && bowCost == 0 && q != null && bowFires(bowId, q.getId())) { styleWeaponBack(); return false; } // its own bow is in the bank and the quiver is fine: only wear it
                int want = pickTripArrow(), top = 0; for (int i = 0; i < ARROWS.length; i++) if (ARROWS[i] == want) top = i;
                while (top > 0 && !bowFires(bowId, ARROWS[top])) top--;
                if (W6Combat.KIT2 && top > 0 && ARROWS[top] == W6Combat.RUNE_ARROW) top--; // W6 (-Dbots.ranged.kit2): the emergency restock can pay a shop price for arrows from nothing: never the top arrow
                long[] pr = new long[ARROWS.length]; for (int i = 0; i < pr.length; i++) pr[i] = Math.max(1, MarketBrain.checkEither(ARROWS[i])); // w13d BUG-1 (-Dmarket.chargeCheck): planned at the dearer of stock and shop
                int tier = W3Skills.restockTier(coins - bowCost, pr, top);
                int n = tier < 0 ? 0 : W3Skills.restockQty(coins - bowCost, pr[tier], W3Skills.RESTOCK_ARROWS);
                if (n <= 0) { W3Skills.restockNone++; return false; }
                if (bowCost > 0) { if (!Ledger.Wallet.pay(bot, bowCost)) return false; bot.getBank().add(new Item(BOWS[0])); W3Skills.restockBows++; }
                int got = nearGE(bot) && MarketBrain.stockOf(ARROWS[tier]) > n + 50 ? Ledger.consume(bot, ARROWS[tier], n, "ammo") : 0;
                if (got <= 0 && Ledger.Wallet.pay(bot, (long) Math.max(1, MarketBrain.value(ARROWS[tier])) * n)) got = n; // the shop takes the guide (= pr[tier] with market.chargeCheck off)
                if (got <= 0) { W3Skills.restockNone++; return bowCost > 0; }
                bot.getBank().add(new Item(ARROWS[tier], got)); W3Skills.restockArrows += got; tripArrow = ARROWS[tier];
                if (!bow) styleWeaponBack();
                System.out.println("[Style] (W3) " + bot.getUsername() + " restocked " + got + " x " + MarketBrain.name(ARROWS[tier]) + (bowCost > 0 ? " and a shortbow" : "") + " (" + Ledger.Wallet.coins(bot) + " gp left)");
                return true;
            }
            if (st == MAGIC) {
                if (w == null || w.getId() != 1387) { W3Skills.restockNone++; return false; }
                if (bot.getProperties().getAutocastSpell() != null) return false;
                int[] ts = pickTripSpell(); int top = 0; for (int i = 0; i < SPELL_TIERS.length; i++) if (SPELL_TIERS[i][0] == ts[0]) top = i;
                for (int i = top; i < SPELL_TIERS.length; i++) {
                    int[] sp = SPELL_TIERS[i]; int n = W3Skills.restockQty(coins, castCost(sp), W3Skills.RESTOCK_CASTS);
                    if (n <= 0) continue;
                    int n1 = Math.max(0, n * sp[2] - held(sp[1])), n2 = Math.max(0, n * sp[4] - held(sp[3]));
                    long cost = (long) Math.max(1, MarketBrain.value(sp[1])) * n1 + (long) Math.max(1, MarketBrain.value(sp[3])) * n2;
                    if (!Ledger.Wallet.pay(bot, cost)) continue;
                    if (n1 > 0) bot.getBank().add(new Item(sp[1], n1)); if (n2 > 0) bot.getBank().add(new Item(sp[3], n2));
                    W3Skills.restockRunes += n; tripSpell = sp;
                    System.out.println("[Style] (W3) " + bot.getUsername() + " restocked runes for " + n + " casts (spell level " + sp[0] + ", " + Ledger.Wallet.coins(bot) + " gp left)");
                    return true;
                }
                W3Skills.restockNone++;
            }
            return false;
        }

        /** W6 Magic: wears the basic staff (1379). */
        boolean wornBasicStaff() { Item w = bot.getEquipment().get(3); return w != null && w.getId() == W6Magic.BASIC_STAFF; }

        /**
         * W6 Magic (-Dbots.magic.anyStaff): a mage with no staff of fire on (styleWeaponBack already put on one it owns) and no
         * autocast wears its basic staff, or buys one at guide (the shop: coins leave the game), and holds wind strike runes for
         * 20+ casts (bought from bot stock, else at guide). True = it changed something (equipSupplies runs again).
         */
        boolean w6BasicStaff() {
            Item w = bot.getEquipment().get(3);
            if (w != null && w.getId() == 1387) return false;
            if (w != null && w.getId() == W6Magic.BASIC_STAFF && bot.getProperties().getAutocastSpell() != null) return false;
            int[] s = W6Magic.WIND_STRIKE;
            int heldCasts = Math.min(held(s[1]) / s[2], held(s[3]) / s[4]);
            boolean wearing = w != null && w.getId() == W6Magic.BASIC_STAFF, owns = wearing || ownsOne(W6Magic.BASIC_STAFF);
            long[] plan = W6Magic.basicPlan(Ledger.Wallet.coins(bot), owns, MarketBrain.value(W6Magic.BASIC_STAFF), castCost(s), heldCasts);
            if (plan == null) { W6Magic.staffNoCoins++; return false; }
            if (plan[0] > 0) { // t62 fix 4: pay only when the staff has a place (bank or pack); a refused add hands the coins back
                Item st = new Item(W6Magic.BASIC_STAFF);
                if (!W6Magic.staffRoom(bot.getBank().hasSpaceFor(st), bot.getInventory().hasSpaceFor(st))) { W6Magic.staffNoRoom++; return false; }
                if (!Ledger.Wallet.pay(bot, plan[0])) { W6Magic.staffNoCoins++; return false; }
                if (!bot.getBank().add(st) && !bot.getInventory().add(st)) { Ledger.Wallet.give(bot, plan[0]); W6Magic.staffRefund++; System.out.println("[Magic] (W6) " + bot.getUsername() + " basic staff not placed, " + plan[0] + " gp back"); return false; }
                W6Magic.staffBought++;
            }
            int n = (int) plan[1];
            if (n > 0) {
                int[] ids = {s[1], s[3]}, need = {Math.max(0, n * s[2] - held(s[1])), Math.max(0, n * s[4] - held(s[3]))};
                for (int i = 0; i < 2; i++) {
                    if (need[i] <= 0) continue;
                    Item ru = new Item(ids[i], need[i]); // t62 fix 4b: buy only with room for the runes (bank or pack); a refused add gives the coins back
                    if (!W6Magic.staffRoom(bot.getBank().hasSpaceFor(ru), bot.getInventory().hasSpaceFor(ru))) { W6Magic.staffRuneNoRoom++; continue; }
                    int g = MarketBrain.stockOf(ids[i]) > need[i] + 50 ? Ledger.consume(bot, ids[i], need[i], "ammo") : 0;
                    int gc = g; long paid = 0;
                    if (g < need[i] && Ledger.Wallet.pay(bot, paid = (long) Math.max(1, MarketBrain.value(ids[i])) * (need[i] - g))) g = need[i]; else paid = 0;
                    if (g > 0 && !bot.getBank().add(new Item(ids[i], g)) && !bot.getInventory().add(new Item(ids[i], g))) {
                        long back = W6Magic.runeRefund(gc, Ledger.price(ids[i]), paid);
                        if (back > 0) Ledger.Wallet.give(bot, back);
                        W6Magic.staffRuneRefund++; System.out.println("[Magic] (W6) " + bot.getUsername() + " " + g + " x " + MarketBrain.name(ids[i]) + " not placed, " + back + " gp back");
                    }
                }
                W6Magic.staffRunes += n; if (style5()) tripSpell = s;
            }
            if (!wearing) {
                if (!toPack(W6Magic.BASIC_STAFF) || !stowWeapon() || !wear(bot, W6Magic.BASIC_STAFF)) { System.out.println("[Magic] (W6) " + bot.getUsername() + " could not wear a basic staff"); return plan[0] > 0 || n > 0; }
                try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
                W6Magic.staffWorn++;
            }
            System.out.println("[Magic] (W6) " + bot.getUsername() + " mage with a basic staff" + (plan[0] > 0 ? " (bought for " + plan[0] + " gp)" : "") + (n > 0 ? ", runes for " + n + " wind strikes" : "") + " (" + Ledger.Wallet.coins(bot) + " gp left)");
            return true;
        }

        /** train-5 F2 C: a ranger with no bow or arrows, or a mage with no staff or runes, after the shop and the Exchange: melee on purpose this trip. */
        void styleFallback() {
            int st = styleOf(bot); long coins = Ledger.Wallet.coins(bot);
            Item w = bot.getEquipment().get(3), q = bot.getEquipment().get(13);
            String why = null;
            if (st == RANGED && W10Skills.THROWN && w10Thrown()) return; // W10 lane M (-Dbots.ranged.thrown): a thrown ranger is armed
            if (st == RANGED) { if (w == null || bowIndex(w.getId()) < 0) why = "ranger without a bow"; else if (q == null || !bowFires(w.getId(), q.getId())) why = "ranger without arrows"; }
            else if (st == MAGIC) { if (w == null || !W6Magic.isStaff(W6Magic.ANY_STAFF, w.getId())) why = "mage without a staff"; else if (bot.getProperties().getAutocastSpell() == null) why = "mage without runes"; }
            if (why == null) return;
            meleeFallback = true; castTrip = false;
            if (!(st == MAGIC && w != null && W6Magic.isStaff(W6Magic.ANY_STAFF, w.getId()))) wearMeleeWeapon(); // the staff is a weapon too: kept on
            try { AfkTasks.setStyle(bot, "strength"); } catch (Throwable ignored) { }
            Item nw = bot.getEquipment().get(3);
            System.out.println("[SmartAdv] " + bot.getUsername() + " " + why + " (" + coins + " gp): melee this trip (" + (nw == null ? "unarmed" : nw.getName()) + ")");
        }

        long w8MaceAt = -W8Polish.MACE_GAP_MS; // W8 (-Dbots.weaponByFoe): when this bot last bought a mace
        /** W8 (-Dbots.weaponByFoe): wear the weapon class this hunt is weak to (the old one goes to the bank, never the Exchange); a first mace is bought, paid. */
        void w8WeaponByFoe(HuntMap.Hunt h) { long[] sfb = Scen.foeBefore(bot); try { w8WeaponByFoe0(h); } finally { Scen.foeAfter(bot, sfb); } } // w14 scen weaponByFoe: judged around the pick (null, nothing read, unless Seed.ON)
        void w8WeaponByFoe0(HuntMap.Hunt h) {
            int weak = HuntMap.weakness(h), att = bot.getSkills().getStaticLevel(Skills.ATTACK);
            if (weak == W8Polish.CRUSH) W8Polish.foeCrush++; else W8Polish.foeSlash++;
            Item w = bot.getEquipment().get(3);
            boolean maceOn = w != null && W8Polish.isMace(w.getId());
            if (weak == W8Polish.SLASH) { // back to the scimitar it owns (only when a mace is worn: anything else is train-61's business)
                if (!maceOn) return;
                for (int[] t : ARMOUR_TIERS) { int id = t[5]; if (att < t[0] || !ownsOne(id)) continue; if (toPack(id) && stowWeapon() && wear(bot, id)) { W8Polish.foeSwaps++; System.out.println("[W8] weapon " + bot.getUsername() + " back to " + MarketBrain.name(id) + " for " + HuntMap.label(h)); } else if (bot.getEquipment().get(3) == null) wearMeleeWeapon(); return; }
                return;
            }
            if (maceOn) return;
            int[] lv = new int[ARMOUR_TIERS.length]; boolean[] own = new boolean[lv.length], can = new boolean[lv.length];
            for (int t = 0; t < lv.length; t++) { lv[t] = ARMOUR_TIERS[t][0]; own[t] = ownsOne(W8Polish.MACES[t]); can[t] = MarketBrain.stockOf(W8Polish.MACES[t]) > 0 || sold(W8Polish.MACES[t]); }
            int row = W8Polish.bestRow(att, lv, own);
            if (row < 0) { // no mace yet: buy the best it wields, bot stock first (the seller is paid), else a shop (coins leave the game)
                int b = W8Polish.bestRow(att, lv, can);
                if (b < 0) { W8Polish.foeNoMace++; return; }
                int id = W8Polish.MACES[b], price = Math.max(1, MarketBrain.value(id)); long now = System.currentTimeMillis();
                if (!W8Polish.maceAffordable(Ledger.Wallet.coins(bot), price, w8MaceAt, now)) { W8Polish.foeNoMace++; return; }
                int got = MarketBrain.stockOf(id) > 0 ? Ledger.consume(bot, id, 1, "weapon") : 0;
                if (got > 0) W8Polish.foeMaceStock++;
                else if (sold(id) && Ledger.Wallet.pay(bot, price)) got = 1;
                if (got <= 0) { W8Polish.foeNoMace++; return; }
                w8MaceAt = now; W8Polish.foeMaceBought++;
                packOrBank(bot, new Item(id, 1));
                row = b;
            }
            int id = W8Polish.MACES[row];
            if (toPack(id) && stowWeapon() && wear(bot, id)) { W8Polish.foeSwaps++; System.out.println("[W8] weapon " + bot.getUsername() + " wields a " + MarketBrain.name(id) + " (crush) for " + HuntMap.label(h) + " (Attack " + att + ")"); }
            if (bot.getEquipment().get(3) == null) try { wearMeleeWeapon(); } catch (Throwable ignored) { } // never left unarmed by a failed swap
            try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
        }

        /** The best scimitar it owns and can wield; else a bronze scimitar if that leaves 300; else unarmed (the bow off: it won't fire). */
        void wearMeleeWeapon() {
            if (!stowWeapon()) return;
            int att = bot.getSkills().getStaticLevel(Skills.ATTACK);
            for (int[] t : ARMOUR_TIERS) { int id = t[5]; if (att < t[0] || !ownsOne(id) || !toPack(id)) continue; if (wear(bot, id)) { try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } return; } }
            long v = Math.max(1, MarketBrain.value(1321));
            if (Ledger.Wallet.coins(bot) - v >= 300 && Ledger.Wallet.pay(bot, v)) { if (bot.getInventory().add(new Item(1321))) wear(bot, 1321); else bot.getBank().add(new Item(1321)); }
            try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
        }

        void start(Job job) {
            if (job != null && job.rcRow) { RcRuns.start(this, job); return; } // W8 (-Dbots.rc.runs): its own steps, not an AfkTasks runner (the row exists only with the flag on)
            if (W7cSkills.FLETCH_OWN && W7cSkills.OWN_NORMAL) { Job nj = w32Normal(job); if (nj != job) { start(nj); return; } } // W32 (-Dbots.fletch.ownNormal, default off): a low Fletching chopper's 1st, 5th, 9th ... tree job is normal logs (its own counter, no RNG); the normal-tree job is never swapped again
            if (P84.ON && job != null && job.hunt != null && Resources.skipHunt(bot, job.hunt.spot)) { // P84 fix 2/3: a crew lead's, rally's or bounty's pick of a hunt no walk reaches (the brain's own pick skips it already)
                P84.count("start-drop", P84.DROP, bot.getUsername() + " " + job.label() + " at " + job.hunt.spot.getX() + "," + job.hunt.spot.getY());
                blacklist.put(job.label(), System.currentTimeMillis() + 60 * 60_000L); nextThink = System.currentTimeMillis() + 3_000;
                return;
            }
            if (shopRetal != null) endShopGuard(); // batch 20 review: retaliation back on before the job's runner saves it (or the runner's restore would keep it off)
            dressBack();
            noJobSince = 0; // batch 19c
            meleeFallback = false; castTrip = false; // train-5: per trip
            if (fightJob(job) && styleOf(bot) != MELEE && style5()) try { styleWeaponBack(); } catch (Throwable t) { System.out.println("[SmartAdv] style weapon back for " + bot.getUsername() + ": " + t); } // F2: its own bow or staff back on (after a melee trip or a death) before anything is bought
            if (W8Polish.RANGED_MELEE && w8RangedOn) try { w8RangedBack(); } catch (Throwable t) { System.out.println("[W8] ranged back for " + bot.getUsername() + ": " + t); } // W8 (-Dbots.ranged.melee): the melee weapon back on after a ranged trip
            if (W8Polish.WEAPON_BY_FOE && job.hunt != null && styleOf(bot) == MELEE) try { w8WeaponByFoe(job.hunt); } catch (Throwable t) { System.out.println("[W8] weaponByFoe for " + bot.getUsername() + ": " + t); } // W8 (-Dbots.weaponByFoe): a mace for a foe weak to crush, the scimitar otherwise
            try { Failures.tripCoins(bot, Ledger.Wallet.coins(bot), true, null, job.label(), trips); if (job.task.kind == AfkTasks.Kind.THIEVE || job.task.kind == AfkTasks.Kind.FIGHT || job.task.kind == AfkTasks.Kind.GROUND || job.hunt != null) Failures.econNote(bot, Failures.THIEVE); } catch (Throwable ignored) { } // batch 19c §4.1: the trip window (fights and pickups may loot coins: [Fable] all of them flagged)
            goodFoodOnly = job.hunt != null && job.hunt.npcLevel >= 25;
            // A tool in the bank is taken out, like a player would, instead of buying another one.
            if (P90.tools(job.task) != null && !AfkTasks.hasAny(bot, P90.tools(job.task))) for (int t : P90.tools(job.task)) { if (bot.getBank().getAmount(t) > 0 && bot.getBank().remove(new Item(t, 1))) { if (!bot.getInventory().add(new Item(t, 1))) bot.getBank().add(new Item(t, 1)); break; } } // P90: a pickaxe in the bank for the essence mine too
            int[] loopTools = P90.tools(job.task); // P90: the essence pickaxe is guarded too (on-w03 bought 1066 pickaxes with a full bank)
            if (Slayer.ON && loopTools != null && Slayer.toolLoop(toolBoughtId, toolBoughtAt, loopTools, AfkTasks.hasAny(bot, loopTools), System.currentTimeMillis())) { // P65 v1.3: a full pack (and bank) can't take the tool just bought: no re-buy loop at the shop (v1.2 pair: 1199 harpoons, 7 watchdog hits at 2834,3442)
                blacklist.put(job.label(), System.currentTimeMillis() + 30 * 60_000L); toolBoughtId = 0; Slayer.TOOL_LOOPS++;
                System.out.println("[SmartAdv] " + bot.getUsername() + " bought a " + MarketBrain.name(loopTools[0]).toLowerCase(Locale.ROOT) + " but can't hold it (pack free " + bot.getInventory().freeSlots() + "); off " + job.label() + " for 30 min");
                nextThink = System.currentTimeMillis() + 5_000; return;
            }
            if (fightJob(job) && styleOf(bot) == MELEE) { // Defence well behind Attack: train it, like a player avoiding the glass-cannon trap
                int att = bot.getSkills().getStaticLevel(Skills.ATTACK), str = bot.getSkills().getStaticLevel(Skills.STRENGTH), def = bot.getSkills().getStaticLevel(Skills.DEFENCE);
                String st;
                if (W5bSkills.STANCE_PLAN || W5bSkills.BUILD_BIAS) { // W5b (-Dbots.stancePlan / -Dbots.buildBias): Attack to the next weapon tier first; a per-bot build from the name
                    int build = W5bSkills.buildOf(bot.getUsername());
                    st = W5bSkills.stance(W5bSkills.STANCE_PLAN, W5bSkills.BUILD_BIAS, build, att, str, def, RNG.nextBoolean());
                    W5bSkills.countStance(st, build, W5bSkills.BUILD_BIAS);
                } else st = def < att - 10 ? "defence" : str < att ? "strength" : RNG.nextBoolean() ? "attack" : "strength";
                if (W8Polish.KIT_ABOVE_RUNE) { String w8 = W8Polish.lowDefStance(true, bot.getUsername(), st, att, str); if (!w8.equals(st)) { W8Polish.lowDefStance++; st = w8; } } // W8 (-Dbots.kitAboveRune): a low-Defence build never trains Defence
                try { AfkTasks.setStyle(bot, st); } catch (Throwable ignored) { }
            }
            if (W8Polish.RANGED_MELEE && job.hunt != null && styleOf(bot) == MELEE && W8Polish.rangedTrip(true, bot.getUsername(), trips, bot.getSkills().getStaticLevel(Skills.RANGE), job.hunt.npcLevel)) try { w8RangedTrip(job.hunt); } catch (Throwable t) { System.out.println("[W8] ranged trip for " + bot.getUsername() + ": " + t); } // W8 (-Dbots.ranged.melee): a bow this trip
            if (fightJob(job) && shopAt == null && !armourChecked && !exchangeChecked) {
                exchangeChecked = true;
                if (nearGE(bot)) { kitFromExchange(); if (styleOf(bot) != MELEE) suppliesFromExchange(); potionsFromExchange(job); otherPotionsFromExchange(job); } // batch 15 items3-4
                else if (P90.climbOut(bot.getLocation(), GRAND_EXCHANGE) == null && exchangeHasWhatINeed(job)) { // P90 fix 2: no exchange walk from inside a dungeon (the job's runner climbs out first) // walk (or pay to teleport) to the Grand Exchange first, like a player
                    shopAt = GRAND_EXCHANGE.transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0); shopName = "the Grand Exchange";
                    shopBuy.clear(); pendingJob = job; exchangeTrip = true;
                    shopDeadline = System.currentTimeMillis() + (FAST ? 180_000 : 480_000); shopBest = Double.MAX_VALUE; shopStalls = 0;
                    if (RNG.nextInt(2) == 0) say(BotChat.pick("Exchange first. Need a few bits.", "Off to the Exchange for supplies.", "Quick trip to the GE before I head out.", "GE run before the trip.", "Supplies first, then work.", "Quick shop at the Exchange."));
                    BotChat.activity(bot, () -> "buying supplies at the Grand Exchange");
                    return;
                }
            }
            if (fightJob(job) && job.hunt != null && job.hunt.npcLevel >= 60) { // prayer potions into the pack for the fight
                for (int pot : new int[]{2434, 139, 141, 143}) { int n = Math.min(4, bot.getBank().getAmount(pot)); if (n > 0 && bot.getBank().remove(new Item(pot, n))) { if (!bot.getInventory().add(new Item(pot, n))) bot.getBank().add(new Item(pot, n)); } }
            }
            if (fightJob(job) && job.hunt != null && (poisonous(job.hunt.name) || job.hunt.npcLevel >= 50)) { // batch 15 items3-4: antipoison and boost potions into the pack
                for (int f = 1; f < AfkTasks.Runner.POTS.length; f++) {
                    if (f <= AfkTasks.Runner.P_SANTI ? !poisonous(job.hunt.name) : job.hunt.npcLevel < 50) continue;
                    if (f > AfkTasks.Runner.P_SANTI && (f == AfkTasks.Runner.P_RANGE) != (styleOf(bot) == RANGED)) continue; // batch 15 review: boost potions for its own style only
                    int left = f <= AfkTasks.Runner.P_SANTI ? 2 : 1;
                    for (int pot : AfkTasks.Runner.POTS[f]) { int n = Math.min(left, bot.getBank().getAmount(pot)); if (n > 0 && bot.getBank().remove(new Item(pot, n))) { left -= n; if (!bot.getInventory().add(new Item(pot, n))) bot.getBank().add(new Item(pot, n)); } }
                }
            }
            grudgeHunt = job.hunt != null && grudgeFoe(bot, job.hunt.name); grudgeShouted = false;
            if (grudgeHunt && RNG.nextInt(2) == 0) say(BotChat.pick("Can't stand " + grudgeOf(bot) + ". Off to thin them out.", "Right. " + job.hunt.name + "s. This is personal."));
            if (shopAt == null && planShopTrip(job)) return;
            if (fightJob(job) && styleOf(bot) != MELEE) {
                equipSupplies();
                if (W3Skills.RESTOCK && style5()) try { if (w3Restock()) equipSupplies(); } catch (Throwable t) { System.out.println("[Style] (W3) restock for " + bot.getUsername() + ": " + t); } // W3 fix 7 (-Dbots.style.restock): last-chance arrows, runes or a shortbow before the melee fallback
                if (W5bSkills.DEF_SHARE && styleOf(bot) == RANGED) try { w5bDefShare(); } catch (Throwable t) { System.out.println("[Style] (W5b) defShare for " + bot.getUsername() + ": " + t); } // W5b (-Dbots.style.defShare): Longrange while Defence lags Ranged by 10+
                if (W34Skills.DEF_SHARE2 && !W5bSkills.DEF_SHARE && styleOf(bot) == RANGED) try { w5bDefShare(); } catch (Throwable t) { System.out.println("[Style] (W34) defShare2 for " + bot.getUsername() + ": " + t); } // W34 (-Dbots.style.defShare2): the same hook when defShare itself is off
                if (W6Magic.ANY_STAFF && style5() && styleOf(bot) == MAGIC) try { final long[] w8 = Scen.staffBefore(bot); boolean w8r = w6BasicStaff(); Scen.staffAfter(bot, w8); if (w8r) equipSupplies(); } catch (Throwable t) { System.out.println("[Magic] (W6) basic staff for " + bot.getUsername() + ": " + t); } // W6 Magic (-Dbots.magic.anyStaff): last chance before the melee fallback
                if (style5()) try { styleFallback(); } catch (Throwable t) { System.out.println("[SmartAdv] melee fallback for " + bot.getUsername() + ": " + t); } // train-5 F2 C
                if (!meleeFallback && RNG.nextInt(3) == 0) say(styleOf(bot) == RANGED ? BotChat.pick("Quiver's full. Let's go.", "Bow strung, arrows in. Off we go.", "Arrows ready. Off we go.", "Bow in hand. Let's hunt.") : BotChat.pick("Runes in the pack. Time for some fire.", "Staff, runes, robe. Ready.", "Fire bolts ready.", "Staff charged. Let's go."));
            }
            Player p = bot;
            if (job.task.tools == AfkTasks.AXES && !AfkTasks.hasAny(p, AfkTasks.AXES)) { int t = buyTool(p, AXE_BY_LEVEL, p.getSkills().getStaticLevel(Skills.WOODCUTTING)); if (t > 0) packOrBank(p, new Item(t)); }
            if (P90.tools(job.task) == AfkTasks.PICKAXES && !AfkTasks.hasAny(p, AfkTasks.PICKAXES)) { int t = buyTool(p, PICK_BY_LEVEL, p.getSkills().getStaticLevel(Skills.MINING)); if (t > 0) { packOrBank(p, new Item(t)); if (job.task == AfkTasks.Task.ESSENCE) P90.count("pick", P90.PICK, p.getUsername() + " " + MarketBrain.name(t)); } }
            if (P90.ON && job.task == AfkTasks.Task.ESSENCE && !AfkTasks.hasAny(p, AfkTasks.PICKAXES)) { // P90 fix 1: no pickaxe and none bought: not off to re-click a rock for 20 min
                P90.count("no-pick", P90.NOPICK, p.getUsername() + " " + job.label());
                if (errandFor != null) {
                    try { Failures.Econ.errandDropped(bot, errandId, errandQty, "no-pickaxe"); } catch (Throwable ignored) { }
                    try { Player eh = Ledger.Wallet.findOnline(errandFor); if (eh != null && !eh.isArtificial()) eh.sendMessage("<col=0b5e1f>" + bot.getUsername() + " has no pickaxe for your essence errand; the order is off, sorry."); } catch (Throwable ignored) { }
                    errandFor = null; errandId = 0; errandQty = 0;
                }
                blacklist.put(job.label(), System.currentTimeMillis() + 60 * 60_000L); nextThink = System.currentTimeMillis() + 3_000;
                return;
            }
            if (job.task.tools != null && job.task.tools != AfkTasks.AXES && job.task.tools != AfkTasks.PICKAXES && !AfkTasks.hasAny(p, job.task.tools)) {
                int tool = job.task.tools[0];
                boolean got = nearGE(p) && MarketBrain.stockOf(tool) > 5 ? Ledger.consume(p, tool, 1, "tool") == 1 : Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(tool)));
                if (got) packOrBank(p, new Item(tool));
            }
            if (job.task.kind == AfkTasks.Kind.FIGHT || job.task.kind == AfkTasks.Kind.THIEVE || job.hunt != null || W5bSkills.STALLS && job.task.kind == AfkTasks.Kind.STALL) { buyFood(job.hunt != null ? job.hunt.npcLevel : 20); packFood(); } // audit B-2: and into the pack
            for (int[] bt : new int[][]{{313, job.task == AfkTasks.Task.BAIT ? 1 : 0}, {314, job.task == AfkTasks.Task.LURE ? 1 : 0}}) { // bait and feathers are bought, not conjured (audit #5)
                int need = Integer.getInteger("smartbots.tripMax", 56) + 24; // a trip's worth and a few spare (audit 10-21 B2: 50 ran out at fish 51)
                if (bt[1] == 0 || p.getInventory().getAmount(bt[0]) >= need) continue;
                int bankHas = p.getBank().getAmount(bt[0]);
                if (bankHas > 0 && p.getBank().remove(new Item(bt[0], bankHas))) { packOrBank(p, new Item(bt[0], bankHas)); if (bankHas >= need) continue; }
                int buy = need - p.getInventory().getAmount(bt[0]);
                int got = MarketBrain.stockOf(bt[0]) >= buy ? Ledger.consume(p, bt[0], buy, "bait") : 0;
                if (got <= 0 && Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(bt[0])) * (long) buy)) got = buy;
                if (got > 0) packOrBank(p, new Item(bt[0], got));
            }
            final int w8Power = (W8Polish.WC_POWER || W8Polish.FISH_POWER) && job.hunt == null && job.chain == 0 && errandFor == null ? w8PowerKind(p, job.task) : 0; // W8 (-Dbots.wc.power / -Dbots.fish.power): 0 with both flags off
            Location spotAt; String spotName; boolean w7Guild = false; // W7 (-Dbots.fish.guild): set only with the flag on
            if (job.hunt != null) { spotAt = job.hunt.spot; spotName = "the " + job.hunt.name.toLowerCase(Locale.ROOT) + "s"; }
            else {
                AfkTasks.Spot[] spots = AfkTasks.SPOTS.get(job.task);
                AfkTasks.Spot spot = null;
                for (AfkTasks.Spot s : spots) if (Gates.canEnter(p, s.at) && (spot == null || s.at.getDistance(p.getLocation()) < spot.at.getDistance(p.getLocation()))) spot = s;
                if (spot == null) spot = spots[0];
                if (FightRules.STEER && job.task.kind == AfkTasks.Kind.FIGHT && spots.length > 1) { // W2 lane M: of a fight task's spots, a combat pouch owner takes the nearest multi-combat one
                    boolean[] en = new boolean[spots.length], mu = new boolean[spots.length]; double[] di = new double[spots.length];
                    for (int i = 0; i < spots.length; i++) { en[i] = Gates.canEnter(p, spots[i].at); mu[i] = !Bob.wild(spots[i].at) && Bob.steerMulti(spots[i].at); di[i] = spots[i].at.getDistance(p.getLocation()); }
                    int si = FightRules.steerSpot(true, Bob.steerOwner(p), en, mu, di);
                    if (si >= 0 && spots[si] != spot) { System.out.println("[Steer] " + p.getUsername() + " spot " + spots[si].name + " (multi-combat) instead of " + spot.name + " for " + job.label()); spot = spots[si]; FightRules.STEER_SPOTS++; }
                }
                spotAt = spot.at; spotName = spot.name;
                if (W5aSkills.SHARK && job.sharkRow) { spotAt = AfkTasks.CATH_BIGNET.at; spotName = AfkTasks.CATH_BIGNET.name; } // W5a: sharks come from the Net/Harpoon spots, 3 tiles off the big-net spot
                if (W7Places.FISH_GUILD && job.task.kind == AfkTasks.Kind.FISH && W7Run.guildStart(p, job, errandFor != null || job.chain != 0)) { w7Guild = true; spotAt = W7Run.GUILD_AT; spotName = W7Places.GUILD_NAME; } // W7 (-Dbots.fish.guild): a Fishing 68+ cage/harpoon/shark trip takes a guild seat when one is free
                if (MineGuild.ON) { Location g = MineGuild.spotFor(p, job.task, spotAt); if (g != null) { spotAt = g; spotName = "the Mining Guild"; } } // W8 (-Dbots.mine.guild): Mining 60+, coal or mithril, the guild ladder nearer than the picked spot
                if (W36Silent.FISH2 && W36Silent.fishCounts(true, w7Guild, w36Fish, !job.sharkRow && (job.task == AfkTasks.Task.CAGE || job.task == AfkTasks.Task.HARPOON))) { W36Silent.fishTaken(System.currentTimeMillis()); if (!w7Guild) W36Silent.fishOutside++; if (w36Fish) System.out.println("[W36] fish2 " + p.getUsername() + " takes a floored " + job.task.key + (w7Guild ? " job to the Fishing Guild (starts " : " job, not to the guild (chain, errand or no seat; starts ") + W36Silent.fishStarts + ")"); } // w36-silent2 (-Dbots.fish.guild2); review fix 1: a floored start outside the guild uses the hour cap too
                if (W36Silent.MINE2 && MineGuild.ON) { // w36-silent2 (-Dbots.mine.guild2): the guild within the radius of the ladder, whatever spot was picked
                    Location g36 = MineGuild.inGuild(spotAt.getX(), spotAt.getY()) ? spotAt : errandFor == null && job.chain == 0 ? MineGuild.spotFor2(p, job.task) : null;
                    if (g36 != null) { spotAt = g36; spotName = "the Mining Guild"; W36Silent.mineTaken(System.currentTimeMillis()); if (w36Mine) System.out.println("[W36] mine2 " + p.getUsername() + " takes a floored " + job.task.key + " job to the Mining Guild (starts " + W36Silent.mineStarts + ")"); }
                }
                if (w8Power == W8Polish.KIND_FISH) { spotAt = AfkTasks.BARB_FISH.at; spotName = AfkTasks.BARB_FISH.name; } // W8 (-Dbots.fish.power): fly fishing at the Barbarian Village river
            }
            if (SlayerOnly.ONLY1 && job.hunt != null) SlayerOnly.kit(p, job.hunt); // W8 (-Dbots.slayer.only1): earmuffs worn for a banshee trip
            if (Gates.desert(spotAt)) packWater(); // combat review 10-23 #9: nothing carried water, and the heat killed bots past the Shantay Pass
            int want = 0;
            for (int id : job.products()) want = Math.max(want, Math.max(0, MarketBrain.target(id) - MarketBrain.stockOf(id)));
            int tripMax = Integer.getInteger("smartbots.tripMax", 56); // test knob: short trips
            if (errandFor != null && errandQty > 0) { tripMax = errandQty; want = Math.max(want, errandQty); } // a player's order: exactly what they asked for
            final boolean w7Trip = W7cSkills.THIEVE_ARDY && job.hunt == null && errandFor == null && W7cSkills.ardyTrip(job.task.key) > 0; // W7c (-Dbots.thieve.ardy): false for every other job
            if (w7Trip) { tripMax = W7cSkills.ardyTrip(job.task.key); want = tripMax; } // 30 knight pockets (1,500 coins counted) or 40 seeds a trip, not the 14 coins a coin job stops at
            // Prayer: a fighter under 43 Prayer buries the bones it gets (like players did on the way up), so Protect
            // from Melee comes within reach in time; at 43+ the bones go to market as before. Veterans and fighters always,
            // fresh bots one trip in two (they need the money from bones more).
            int prayer = p.getSkills().getStaticLevel(Skills.PRAYER);
            boolean fightTrip = job.hunt != null || job.task.kind == AfkTasks.Kind.FIGHT;
            if (Bob.COMBAT) { tripsN++; if (fightTrip) fightTripsN++; } // Summoning in combat: "fights a lot" for the pouch planner
            String pk = p.getUsername().toLowerCase(Locale.ROOT);
            boolean buries = fightTrip && prayer < 43 && (VETERAN.getOrDefault(pk, false) || fav == -1 || RNG.nextBoolean());
            final boolean w5Power = W5aSkills.MINE_POWER && job.hunt == null && job.chain == 0 && errandFor == null && job.task == AfkTasks.Task.IRON && W5aSkills.power(true, W5aSkills.ironGlut(MarketBrain.stockOf(W5aSkills.IRON_ORE), MarketBrain.target(W5aSkills.IRON_ORE)), p.getSkills().getStaticLevel(Skills.MINING)) || w8Power != 0; // W8: a wc/fish power trip uses mine.power's drop mode (false with the W8 flags off) // W5a (-Dbots.mine.power)
            AfkTasks.Runner r = new AfkTasks.Runner(p, job.task, !w5Power, w5Power ? tripMax : Math.min(tripMax, Math.max(Math.min(14, tripMax), want)), buries, true);
            if (w7Trip) W7cSkills.ardyStart(job.task.key, System.currentTimeMillis());
            if (W7cSkills.FLETCH_OWN && job.hunt == null && job.chain == 0 && errandFor == null && !w5Power && job.task.kind == AfkTasks.Kind.TREE) W7cHooks.fletchKit(p, r); // W7c (-Dbots.fletch.own): a knife in the pack; an errand's logs are delivered whole
            if (W16.SPIN_JOB && job.hunt == null && job.chain == 0 && errandFor == null && job.task == AfkTasks.Task.FLAX) W16.spinJob(p, r, spotAt); // W16 (-Dbots.craft.spinJob, default off): flax then spin at a ground-floor wheel
            if (job.hunt != null) { r.targetsOverride = job.hunt.names; r.productsOverride = job.hunt.products; r.labelOverride = HuntMap.label(job.hunt); }
            if (W6Herbs.SUPPLY) W6Herbs.started(true, job.hunt != null ? job.hunt.name : null, job.hunt == null && job.task == AfkTasks.Task.LIMPWURT, job.hunt == null && job.task == AfkTasks.Task.SNAPE); // W6: counters only
            if (W6Herbs.SUPPLY) W6Herbs.startedSay(p.getUsername(), job.hunt != null ? job.hunt.name : null, job.hunt == null && job.task == AfkTasks.Task.LIMPWURT, System.currentTimeMillis()); // w9 s62fire: the lane line (no RNG)
            if (job.task.kind == AfkTasks.Kind.FISH && w8Power == 0) { // W8 (-Dbots.fish.power): a power fisher never cooks
                int need = 1; for (int id : job.products()) need = Math.max(need, COOK_LEVEL.getOrDefault(id, 1));
                boolean canCook = p.getSkills().getStaticLevel(Skills.COOKING) >= need;
                r.cookHaul = errandFor != null ? errandCook : canCook && !(fav == Skills.FISHING && RNG.nextInt(10) < 6); // a fisher sells raw 60% of the time; an errand is cooked only if the order was (review 10-22)
                if (w7Guild) r.cookHaul = false; // W7 (-Dbots.fish.guild): guild fish is banked raw (no range leg planned there, no fire kit)
                if (W5aSkills.COOK_FIRE && r.cookHaul) w5FireKit(p, spotAt); // W5a (-Dbots.cook.fire): a tinderbox and a log for a spot far from every range
            }
            r.sell = true;
            if (W5aSkills.SHARK && job.hunt == null && job.task.kind == AfkTasks.Kind.FISH) { // W5a (-Dbots.fish.shark)
                if (job.spotRule != 0) r.w5SpotRule = job.spotRule;
                if (job.sharkRow) { r.productsOverride = job.prodOv; r.labelOverride = job.labelOv; W5aSkills.sharkStarts++; }
                else if (job.task == AfkTasks.Task.HARPOON) W5aSkills.harpoonStarts++;
                else if (job.task == AfkTasks.Task.CAGE) W5aSkills.cageStarts++;
            }
            if (w7Guild && r.w5SpotRule == 0 && job.task == AfkTasks.Task.HARPOON) r.w5SpotRule = W5aSkills.SPOT_NOT_BIGNET; // W7 (-Dbots.fish.guild): without the shark flag a guild harpooner still keeps off the guild's one Net/Harpoon (shark) spot
            if (w5Power && w8Power == 0) { r.sell = false; r.w5Vanish = true; W5aSkills.powerStarts++; System.out.println("[W5a] power " + p.getUsername() + " started iron drop (Mining " + p.getSkills().getStaticLevel(Skills.MINING) + ", iron ore stock " + MarketBrain.stockOf(W5aSkills.IRON_ORE) + ", trip " + tripMax + ")"); }
            if (w8Power != 0) { r.sell = false; r.cookHaul = false; r.w5Vanish = true; r.w8Power = w8Power; if (w8Power == W8Polish.KIND_WC) W8Polish.wcPowerStarts++; else W8Polish.fishPowerStarts++; System.out.println("[W8] power " + p.getUsername() + " started " + (w8Power == W8Polish.KIND_WC ? "willow drop (Woodcutting " + p.getSkills().getStaticLevel(Skills.WOODCUTTING) : "fly-fish drop at " + spotName + " (Fishing " + p.getSkills().getStaticLevel(Skills.FISHING)) + ", trip " + tripMax + ")"); } // W8 (-Dbots.wc.power / -Dbots.fish.power): drop mode, the drops vanish, no beast store
            r.styleSkill = pick(new Integer[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE});
            r.spot = spotAt;
            if (job.chain != 0) try { Batch29.Chains.apply(r, job, spotAt); } catch (Throwable ignored) { } // P78: process the pack, then bank it (legs re-checked from the real spot)
            r.walker = Ledger.Wallet.coins(p) < 5_000; // the poor walk; the rich take the teleport
            if (P73_FIX && r.walker && job.hunt != null && spotAt.getZ() == 0 && spotAt.getY() < 6400 && spotAt.getDistance(p.getLocation()) > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.coins(p) >= 3L * Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556))) { r.walker = false; p73Count("hunt-tele", P73_HUNT); } // P73 B2: chosen as a teleport trip (5k+), then the shop stop spent it under 5k: it walked 690 tiles towards moss giants 2201,3812 / pyrefiends 1845,3251 and stood in the Burthorpe corner 2838,3549; with 3 fares in hand it still takes the teleport
            r.usePrayer = job.hunt != null && job.hunt.npcLevel >= 60 && p.getSkills().getStaticLevel(Skills.PRAYER) >= 37; // batch 15 item1: 37 magic, 40 missiles, 43 melee (prayerTick checks which)
            r.setDelay(3); // a third of the owner's bot rate: 50 of these must stay cheap
            if (Bob.ON) Bob.plan(r); // P92 S4 1.4: a beast of burden pouch for this trip (level 13+, banked pouch, walked trip)
            if (Bob.COMBAT) Bob.combatPlan(r); // Summoning in combat: up to 2 combat pouches for a fight trip to a multi-combat spot
            if (!AfkTasks.launch(r, AfkTasks.State.TO_SPOT, spotAt)) System.out.println("[SmartAdv] " + p.getUsername() + " couldn't set out for " + job.label() + ": " + r.last + " (audit 10-23 A-11: not left in RUNNING)");
            runner = r;
            try { Spectacle.maybeRace(this, job); } catch (Throwable ignored) { }
            current = job.task;
            lastJob = job;
            if (W3Skills.SHARE_ON) W3Skills.noteStart(job.hunt == null && job.task.kind == AfkTasks.Kind.THIEVE ? 1 : job.hunt == null && job.task.kind == AfkTasks.Kind.TREE ? 2 : 0); // w4-jobshare: the share is of job starts
            final String where = spotName;
            BotChat.activity(p, () -> "off getting " + job.label().toLowerCase(Locale.ROOT) + (where != null ? " near " + where : ""));
            Mood m = mood();
            if (m == Mood.GRUMPY) { if (RNG.nextInt(2) == 0) say(BotChat.pick("Back out. Not happy about it.", "Fine. " + job.label() + ". Whatever.", "Someone has to, and apparently it's me again.", "Here we go again.", "Fine. I'm going.")); }
            else if (m == Mood.TIRED) { if (RNG.nextInt(2) == 0) say(String.format(BotChat.pick("One more run of %s, then bed.", "Last trip for %s today, I mean it this time.", "Last %s trip of the day.", "%s, then sleep. That's the deal."), job.label().toLowerCase(Locale.ROOT))); }
            else if (m == Mood.CHATTY || RNG.nextInt(2) == 0) say(String.format(pick(SET_OUT), job.label().toLowerCase(Locale.ROOT)));
            if (W36Silent.WC2) { boolean tree36 = job.hunt == null && job.task.kind == AfkTasks.Kind.TREE; W36Silent.picked(tree36); if (tree36 && w36Wc) System.out.println("[W36] wc2 " + p.getUsername() + " takes a floored " + job.task.key + " job (log picks " + W36Silent.treePicks + "/" + W36Silent.picks + ")"); } // w36-silent2 (-Dbots.wc.share2)
            if (W36Silent.MINE2 || W36Silent.FISH2 || W36Silent.WC2) { w36Mine = false; w36Fish = false; w36Wc = false; }
            System.out.println("[SmartAdv] " + p.getUsername() + " -> " + (job.hunt != null ? "hunt " + job.hunt.name : job.task.key) + " at " + spotName + " (bounty " + String.format("%.2f", jobWeight(job)) + ")" + (job.chain != 0 ? Batch29.Chains.label(job) : ""));
        }
    }

    // ------------------------------------------------------------ batch 26 (stuck review fixes 2 and 3)

    static final boolean LEG_ON = !"false".equals(System.getProperty("jobs.legCooldown"));
    static final long LEG_COOL_MS = Long.getLong("jobs.legCooldownMin", 120L) * 60_000L;
    /** P73 (track B, nav stuck spots): -Dnav.p73Fix=true (default off). B6 shop walks with a planner route skip the river/exit vias;
     *  B2 hunts whose spot the planner has no route to are not chosen; B1 the planner never takes the Al Kharid toll for a walk
     *  that starts and ends west of the gate; B3 a gate row for the Taverley black demons (Gates). Counts: [P73] lines. */
    static final boolean P73_FIX = Boolean.getBoolean("nav.p73Fix");
    static final boolean BRAIN_FIX1 = Boolean.getBoolean("brain.fix1"); // brain1: audit fixes that change always-on behaviour (default off; the design notes)
    static final boolean P73_TOLL_ON = P73_FIX && Boolean.getBoolean("nav.p73Toll"); // P73 B1 toll west-to-west: own flag, default off (pairs 3-4: jobs -6%)
    static final java.util.concurrent.atomic.AtomicInteger P73_SHOP = new java.util.concurrent.atomic.AtomicInteger(), P73_HUNT = new java.util.concurrent.atomic.AtomicInteger(), P73_TOLL = new java.util.concurrent.atomic.AtomicInteger();
    static void p73Count(String what, java.util.concurrent.atomic.AtomicInteger c) { int n = c.incrementAndGet(); if (n % 50 == 1) System.out.println("[P73] " + what + " " + n); }
    static final boolean PLAN_JOBS = !"false".equals(System.getProperty("jobs.planned")), PLAN_NO_ROUTE = Boolean.getBoolean("jobs.noRoute"); // batch 26 soak: off by default, the shadow grid has gaps (copper, oaks, shrimp and the essence mine read "no route")
    static final int PLAN_FROM = Integer.getInteger("jobs.planFrom", 100), PLAN_PER_CHOICE = Integer.getInteger("jobs.planPerChoice", 3);

    /** batch 26 fix 2: where a walk starts, as the nearest bank (index in AfkTasks.BANKS) within 40 tiles, else its 8x8 cell; underground counts from above. */
    static String origin(Location l) {
        Location s = l.getY() > 6400 ? Location.create(l.getX(), l.getY() - 6400, 0) : l;
        int bi = -1; double bd = 40;
        for (int i = 0; i < AfkTasks.BANKS.length; i++) { double d = AfkTasks.BANKS[i].getDistance(s); if (d <= bd) { bd = d; bi = i; } }
        return bi >= 0 ? "b" + bi : "c" + (s.getX() >> 3) + "," + (s.getY() >> 3);
    }
    static String legKey(String job, Location from) { return job + "@" + origin(from); }

    /** batch 26 fix 2: from Watchdog.rescue and the Runner's "Couldn't find a path": that job from that start is off for LEG_COOL_MS. */
    static void legFailed(Player p, AfkTasks.Runner r, String why) {
        if (!LEG_ON || p == null || r == null || r.state != AfkTasks.State.TO_SPOT || r.walkFrom == null) return;
        Brain br = BRAINS.get(p.getUsername().toLowerCase(Locale.ROOT));
        if (br == null || br.runner != r || br.lastJob == null) return;
        br.stuckLegs.put(legKey(br.lastJob.label(), r.walkFrom), System.currentTimeMillis() + LEG_COOL_MS);
        legsBlocked++;
        System.out.println("[SmartAdv] " + p.getUsername() + " won't walk to " + br.lastJob.label() + " from " + origin(r.walkFrom) + " " + r.walkFrom + " for " + LEG_COOL_MS / 60_000 + " min (" + why + ")");
    }
    static volatile int legsBlocked;
    /** batch 26 fix 2: true when this bot's runner is about to walk a leg (its job, from where it stands) that is cooling down. */
    static boolean legCooled(Player p, AfkTasks.Runner r) {
        if (!LEG_ON || p == null || r == null || !p.isArtificial()) return false;
        Brain br = BRAINS.get(p.getUsername().toLowerCase(Locale.ROOT));
        if (br == null || br.runner != r || br.lastJob == null || br.stuckLegs.isEmpty()) return false;
        if (br.stuckLegs.getOrDefault(legKey(br.lastJob.label(), p.getLocation()), 0L) <= System.currentTimeMillis()) return false;
        legsRefused++;
        return true;
    }
    static volatile int legsRefused;

    /** batch 26 fix 3: origin|target -> {planner gen, planned tiles or -1 for no route}. */
    static final Map<String, int[]> PLANNED = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile int plannedRuns, plannedNoRoute;

    /**
     * batch 26 fix 3: the planner's length of the walk from the bot's start (its bank) to a job spot: tiles, -1 = no route, -2 = not
     * known (no grid, a dungeon or across the sea/Salve, which go by boat or teleport, or this choice's budget of new plans is spent).
     */
    static int plannedTiles(Player bot, Location to, int[] budget, String job) {
        if (!NavPlanner.built || bot == null || to == null) return -2;
        Location from = bot.getLocation();
        if (from == null || from.getZ() != 0 || to.getZ() != 0 || from.getY() > 6400 || to.getY() > 6400) return -2;
        if (AfkTasks.Runner.acrossSea(from, to) || (from.getX() >= 3400) != (to.getX() >= 3400)) return -2;
        String o = origin(from), k = o + "|" + to.getX() + "," + to.getY();
        int g = NavPlanner.gen;
        int[] c = PLANNED.get(k);
        if (c != null && c[0] == g) return c[1];
        if (budget[0] <= 0) return -2;
        budget[0]--;
        Location start = o.startsWith("b") ? AfkTasks.BANKS[Integer.parseInt(o.substring(1))] : from;
        int tiles;
        Location via9 = Slayer.FIX9B ? AfkTasks.Runner.passVia(start, to) : null; // slayer9b: across the Shantay Pass the runner walks to the pass and hops: two planned legs (flag off: one, as before)
        try { tiles = passTiles(start, to, via9, NavWalker.agility(bot)); } catch (Throwable t) { return -2; }
        if (g != NavPlanner.gen) return -2; // the graph changed under the plan
        PLANNED.put(k, new int[]{g, tiles});
        plannedRuns++;
        if (tiles < 0) { plannedNoRoute++; System.out.println("[Jobs] no planned route " + start + " -> " + to + " (" + job + ")" + (PLAN_NO_ROUTE ? ": skipped" : ": kept on its straight distance") + " when chosen from " + o); }
        return tiles;
    }

    /** slayer9b: planned tiles start -> to, in two legs through the Shantay Pass when via (Runner.passVia's tile on start's side) is set. -1 = no route. */
    static int passTiles(Location start, Location to, Location via, int agility) {
        if (via == null) { NavPlanner.Route r = NavPlanner.plan(start, to, agility); return r == null ? -1 : r.tiles; }
        NavPlanner.Route r2 = NavPlanner.plan(via == AfkTasks.Runner.PASS_N ? AfkTasks.Runner.PASS_S : AfkTasks.Runner.PASS_N, to, agility);
        NavPlanner.Route r1 = r2 == null ? null : NavPlanner.plan(start, via, agility);
        return r1 == null ? -1 : r1.tiles + r2.tiles + 2;
    }

    /** brain2 rule 1 glue (Brain2.huntGate with the live banks, gates and planner): null = open, else "noBank" / "noBankRoute". One log line per hunt and reason. */
    static final java.util.Set<String> BRAIN2_SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static String brain2Gate(Player bot, Job j, int[] budget, boolean count) {
        if (!Brain2.ON || bot == null || j == null || j.hunt == null) return null;
        final Location sp = j.hunt.spot; final String label = j.label();
        String g = Brain2.huntGate(true, Slayer.FIX9, AfkTasks.Runner::desertSide, sp, AfkTasks.BANKS, b -> Gates.canEnter(bot, b), Brain2.ROUTE ? bi -> bankLegTiles(bi, sp, budget, label, bot) : null); // slayer9: fix9 off = the old test
        if (g == null) return null;
        if (count) { if ("noBank".equals(g)) Brain2.noBank++; else Brain2.noBankRoute++; }
        if (BRAIN2_SAID.add(label + "|" + g)) System.out.println("[Brain2] " + label + " at " + sp + " refused: " + g + " (first asked by " + bot.getUsername() + ")");
        return g;
    }
    /** brain2 rule 1: the planner's bank -> hunt spot leg, in plannedTiles' cache under its own "b<i>|x,y" key (same start tile, same value). -1 = no route, -2 = not known. */
    static int bankLegTiles(int bi, Location to, int[] budget, String job, Player bot) {
        if (!NavPlanner.built || to == null || bi < 0 || bi >= AfkTasks.BANKS.length) return -2;
        Location start = AfkTasks.BANKS[bi];
        if (to.getZ() != 0 || to.getY() > 6400) return -2;
        if (AfkTasks.Runner.acrossSea(start, to) || (start.getX() >= 3400) != (to.getX() >= 3400)) return -2;
        String k = "b" + bi + "|" + to.getX() + "," + to.getY();
        int g = NavPlanner.gen;
        int[] c = PLANNED.get(k);
        if (c != null && c[0] == g) return c[1];
        if (budget == null || budget[0] <= 0) return -2;
        budget[0]--;
        NavPlanner.Route r; int tiles;
        Location via9 = Slayer.FIX9 ? AfkTasks.Runner.passVia(start, to) : null; // slayer9: across the Shantay Pass the trip walks to the pass and hops (passVia, passHop): two planned legs, not one with no route
        try {
            if (via9 != null) {
                NavPlanner.Route r2 = NavPlanner.plan(via9 == AfkTasks.Runner.PASS_N ? AfkTasks.Runner.PASS_S : AfkTasks.Runner.PASS_N, to, NavWalker.agility(bot));
                r = r2 == null ? null : NavPlanner.plan(start, via9, NavWalker.agility(bot));
                tiles = r == null ? -1 : r.tiles + r2.tiles + 2;
            } else { r = NavPlanner.plan(start, to, NavWalker.agility(bot)); tiles = r == null ? -1 : r.tiles; }
        } catch (Throwable t) { return -2; }
        if (g != NavPlanner.gen) return -2;
        PLANNED.put(k, new int[]{g, tiles});
        plannedRuns++;
        if (tiles < 0) System.out.println("[Jobs] no bank route " + start + " -> " + to + " (" + job + "): bank b" + bi);
        return tiles;
    }

    /** For the status command: how many smart adventurers are out working. */
    static int working() {
        int n = 0;
        for (Player p : Repository.getPlayers()) if (p != null && p.isArtificial() && AfkTasks.RUNNING.containsKey(p.getUsername().toLowerCase(Locale.ROOT))) n++;
        return n;
    }
}
