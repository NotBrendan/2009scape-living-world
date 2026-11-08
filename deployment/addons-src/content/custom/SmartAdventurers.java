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
        Job(AfkTasks.Task task, int skill, int level) { this.task = task; this.skill = skill; this.level = level; }
        int[] products() { return hunt != null ? hunt.products : task.products; }
        String label() { return hunt != null ? hunt.name + "s" : task.label; }
    }

    static volatile List<Job> JOBS = new ArrayList<>();
    static {
        AfkTasks.Task[] t = AfkTasks.Task.values();
        for (AfkTasks.Task task : t) {
            if (task.kind == AfkTasks.Kind.SMELT || task.products == null) continue;
            if (task.kind == AfkTasks.Kind.GROUND && task != AfkTasks.Task.SNAPE) continue; // snape grass is a real job (D1); the other ground tasks aren't
            if (task == AfkTasks.Task.AUTO || task == AfkTasks.Task.BEST_ROCK || task == AfkTasks.Task.BEST_TREE) continue;
            if (AfkTasks.SPOTS.get(task) == null) continue; // must know where to go
            int skill = -1, level = 1;
            switch (task.kind) {
                case TREE: skill = Skills.WOODCUTTING; level = treeLevel(task); break;
                case ROCK: skill = Skills.MINING; level = AfkTasks.Runner.oreLevel(task.products[0]); break;
                case FISH: skill = Skills.FISHING; level = task == AfkTasks.Task.NET ? 1 : task == AfkTasks.Task.BAIT ? 5 : task == AfkTasks.Task.LURE ? 20 : task == AfkTasks.Task.CAGE ? 40 : 35; break;
                case THIEVE: skill = Skills.THIEVING; level = task == AfkTasks.Task.WOOL ? 1 : task == AfkTasks.Task.MAN ? 1 : task == AfkTasks.Task.FARMER ? 10 : task == AfkTasks.Task.WARRIOR ? 25 : 40; break;
                case FIGHT: skill = -1; level = fightLevel(task); break;
                default: break;
            }
            if (task == AfkTasks.Task.SNAPE) { skill = -1; level = 35; } // hobgoblins (level 28) share the peninsula
            JOBS.add(new Job(task, skill, level));
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
                            try { spawn(); } catch (Throwable t) { System.out.println("[SmartAdv] spawn failed: " + t); try { Failures.Life.loginFailed("adventurer", null, t); } catch (Throwable ignored) { } } // batch 19c §8.1
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
                            try { spawnHigh(); } catch (Throwable t) { System.out.println("[SmartAdv] high-tier spawn failed: " + t); try { Failures.Life.loginFailed("high", null, t); } catch (Throwable ignored) { } } // batch 19c §8.1
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
                        Job j = new Job(AfkTasks.Task.AUTO, -1, h.npcLevel >= 80 ? 90 : Math.max(3, (int) (h.npcLevel * 1.3) + 5)); // the big things: combat 90 plus prayer and a shield
                        j.hunt = h;
                        all.add(j);
                    }
                    JOBS = all; // swap the whole list; the game thread only ever reads it
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

    /** Puts a profile's saved pack, bank and quiver back. A haul it was carrying when the server went down goes to the Exchange through the ledger (it is paid when it sells), as if it had got to the bank. */
    static void restoreItems(AIPlayer bot, Profile prof) {
        if (!prof.items) return;
        try {
            bot.getInventory().clear();
            for (Item it : bot.getBank().toArray()) if (it != null && it.getId() != 995) bot.getBank().remove(it);
            int hauled = 0;
            for (int[] x : prof.bank) bot.getBank().add(new Item(x[0], x[1]));
            for (int[] x : prof.pack) {
                if (!keepsInPack(x[0]) && MarketBrain.tradeable(x[0])) { Ledger.supply(bot, x[0], x[1]); hauled += x[1]; if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] " + bot.getUsername() + " haul item " + x[0] + " x" + x[1]); continue; }
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
                try { final long at = System.nanoTime(); final JSONArray arr = buildProfiles(); if (arr != null) TIMER.schedule(new TimerTask() { public void run() { writeProfiles(arr, at); } }, 0); } catch (Throwable t) { System.out.println("[SmartAdv] save failed: " + t); try { Failures.Life.saveFailed(profileFile().getName(), t); } catch (Throwable ignored) { } } // batch 19c §10
                try { overdue(System.currentTimeMillis()); } catch (Throwable ignored) { } // batch 19c §8.1 return-overdue
                try { retryRestores(System.currentTimeMillis()); } catch (Throwable t) { System.out.println("[S-10] retry pass: " + t); } // batch 22 S-10 (after the snapshot: a retried bot is saved by the next one)
                Watchdog.fpulse("SmartAdventurers.saveProfiles", null, System.nanoTime() - t0); // batch 20 (19b+c follow-up 4)
                return true;
            }
        });
    }

    /** Shutdown (and anything already on the game thread): build and write now. */
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
        String task = b.runner != null ? "runner" : b.shopAt != null ? "shop" : b.graveAt != null ? "grave" : b.delivering ? "delivery" : b.mill != 0 ? "mill" : Deals.involved(bot) ? "deal" : PlayerTrades.SESSIONS.containsKey(bot) ? "trade" : null;
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
            if (bot != null) try { bot.clear(); } catch (Throwable ignored) { }
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
        if (m.graveAt != null || m.blessUntil > now || m.delivering || m.shopAt != null || m.mill != 0 || m.agiStep >= 0 || m.starUntil > 0 || m.errandFor != null) return null;
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
        AIPlayer bot = new CombatBotAssembler().MeleeAdventurer(tier, start);
        humanName(bot); // P31 fix 1: before anything is filed under the name
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

    /** Someone we know: same name, the levels they had earned, their gear and coins. */
    static void restore(Profile prof, Location start) {
        AIPlayer named = prof.direct ? directBot(prof.name, start) : namedBot(prof.name, start); // P31 fix 1: a name without the counter tail is set directly
        AIPlayer bot = named != null ? named : new CombatBotAssembler().MeleeAdventurer(prof.high ? CombatBotAssembler.Tier.HIGH : prof.veteran ? CombatBotAssembler.Tier.MED : CombatBotAssembler.Tier.LOW, start);
        if (named == null && prof.direct) humanName(bot); // P31 fix 1: its stand-in name has no counter tail either (the renames below follow it)
        if (restoringProf == prof) restoringBot = bot; // batch 22 S-10: restoreSafe discards it if anything below throws before BOTS.add
        if (named == null) try { if (Failures.gate("namelost|" + prof.name, Long.MAX_VALUE / 4)) Failures.event("lifecycle", bot, "login", "name-lost", prof.name + " -> " + bot.getUsername(), null, Failures.ctx("was", prof.name, "now", bot.getUsername())); } catch (Throwable ignored) { } // batch 19c §8.1 (one per profile per boot)
        for (int i = 0; i < 24; i++) if (prof.levels[i] > 0) { bot.getSkills().setStaticLevel(i, prof.levels[i]); bot.getSkills().setLevel(i, prof.levels[i]); }
        restoreXp(bot, prof.xp);
        try { QuestBook.rename(prof.name, bot.getUsername()); QuestBook.restore(bot); } catch (Throwable t) { System.out.println("[Quests] restore for " + bot.getUsername() + ": " + t); } // saved attributes, then quest stages; before seedQuests
        if (Boolean.getBoolean("xp.debug")) System.out.println("[XPDBG] restored " + bot.getUsername() + " bad=[" + xpCheck(bot) + "]");
        bot.getSkills().updateCombatLevel();
        if (prof.coins > 0) Ledger.Wallet.restore(bot, prof.coins); // its own coins back, not new money
        long restoredTo = Ledger.Wallet.coins(bot); // batch 19c §10 restore-short (read only)
        long collected = Ledger.Wallet.collect(bot); // anything that sold while it was away
        if (restoringProf == prof) restoringCollected = collected; // batch 22 S-10
        if (S10_FAULT_NAME != null) s10Fault("restore", prof.name); // batch 22 S-10 trial fault injection (TestB22S10 only)
        restoreItems(bot, prof); // pack, bank and the exact arrow count first, so anything wear() moves to the pack isn't cleared (audit #12)
        try { int held = 0; if (prof.items) { for (Item it : bot.getInventory().toArray()) if (it != null) held++; for (Item it : bot.getBank().toArray()) if (it != null && it.getId() != 995) held++; }
            int want = prof.pack.size() + prof.bank.size();
            if (restoredTo < prof.coins || prof.items && want > 0 && held == 0) Failures.event("save", bot, "load", "restore-short", restoredTo < prof.coins ? "coins short" : "items empty", null, Failures.ctx("profile", prof.name, "coins", restoredTo, "saved", prof.coins, "items", held, "savedItems", want, "file", "smartbots.json")); } catch (Throwable ignored) { } // batch 19c §10
        if (prof.gear.isEmpty() && !prof.items && Ledger.Wallet.coins(bot) < 600) // audit 10-23 S-6: only a legacy profile (no saved items); a player-rules death can leave a modern one with no gear. Remembered from before gear was owned: the same start a newcomer gets, so nobody comes back naked
            Ledger.Wallet.give(bot, prof.veteran ? 20_000 + RNG.nextInt(40_000) : 600);
        if (prof.gear.isEmpty()) { new CombatBotAssembler().gearMeleeBot(bot); payForGear(bot); } // first time: buy what the levels allow
        else { for (int id : prof.gear) { if (id >= 882 && id <= 892) continue; wear(bot, id); } try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } }  // theirs already, no re-buying
        if (!bot.getUsername().equalsIgnoreCase(prof.name)) Ledger.rename(prof.name, bot.getUsername()); // its lots and money owed follow it
        String how = bot.getUsername().equalsIgnoreCase(prof.name) ? "is back" : "is back as " + bot.getUsername() + " (was " + prof.name + ")";
        if (collected > 0) how += ", collected " + String.format("%,d", collected) + " gp from sales while away";
        int fav = prof.fav != -2 ? prof.fav : bestGatherSkill(bot);
        if (prof.high) { bot.getSkills().rechargePrayerPoints(); armForDragons(bot); }
        if (prof.catchphrase != null && prof.grudge != null) STORY.put(bot.getUsername().toLowerCase(Locale.ROOT), new String[]{prof.catchphrase, prof.grudge});
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
            if (rallied >= 3 || b.bot == null || !b.bot.isActive() || b.runner != null || b.shopAt != null || b.delivering || b.mill != 0 || b.graveAt != null || b.agiStep >= 0 || b.starUntil > 0) continue;
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
        finishTutorial(bot);
        seedAgility(bot, veteran, high);
        seedStyle(bot, fav);
        try { Gates.seedQuests(bot, high); } catch (Throwable t) { System.out.println("[SmartAdv] quests for " + bot.getUsername() + ": " + t); } // a quest log that fits its levels
        try { bot.getProperties().setRetaliating(true); } catch (Throwable ignored) { } // auto-retaliate on, like every 2009 player (bots died quietly to highwaymen on shop walks)
        Brain brain = new Brain();
        brain.nextThink = System.currentTimeMillis() + 5_000 + RNG.nextInt(FAST ? 5_000 : 60_000); // spread the first trips over a minute after a restart
        try { PlayerTrades.sweepPulseMap(); } catch (Throwable ignored) { } // never in the server's pulse map, not even for 30 s
        brain.fav = fav;
        brain.goal = goal;
        brain.home = home;
        HIGH_TIER.put(key, high);
        HOME.put(key, home);
        BRAINS.put(key, brain);
        { Map<String, Long> bl = SAVED_BLACKLISTS.get(key); if (bl != null) brain.blacklist.putAll(bl); Integer dn = SAVED_DEATHS.get(key); if (dn != null) brain.deaths = dn; } // combat review 10-23 #5: what killed it before the restart
        ENLISTED.putIfAbsent(key, System.currentTimeMillis());
        STORY.computeIfAbsent(key, k -> new String[]{CATCHPHRASES[RNG.nextInt(CATCHPHRASES.length)], GRUDGES[RNG.nextInt(GRUDGES.length)]});
        new GeneralBotCreator(brain, bot);
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
        BOTS.add(bot);
        System.out.println("[SmartAdv] " + bot.getUsername() + " " + how + " (combat " + bot.getProperties().getCurrentCombatLevel()
                + ", fishing " + bot.getSkills().getStaticLevel(Skills.FISHING) + ", mining " + bot.getSkills().getStaticLevel(Skills.MINING)
                + ", woodcutting " + bot.getSkills().getStaticLevel(Skills.WOODCUTTING) + ", trade " + skillName(fav) + ", from " + BotLife.Town.values()[Math.max(0, home) % BotLife.Town.values().length].name + ")");
        Crew.join(brain);
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

    /** Logs a bot out for a break (B13). Its profile is kept in OFFLINE and it comes back later by the restore path. */
    static void logOut(AIPlayer bot, Brain brain) {
        try { midTask(bot, brain); } catch (Throwable ignored) { } // batch 19c §8.1
        if (brain != null) brain.dressBack();
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
        AfkTasks.Runner runner;
        AfkTasks.Task current;
        long nextThink = System.currentTimeMillis() + 5_000, nextChat;
        int trips;
        Job lastJob;
        int fav = -2, goal, home;
        // B17 for real: a walk to an actual shop before a trip that needs a tool or food.
        Location shopAt; String shopName; Job pendingJob; long shopDeadline; boolean skipShopOnce, goalTrip, armourTrip, armourChecked;
        // Trading layer D: a plank run. Logs -> sawmill (fee destroys coins) -> the house (planks destroyed building).
        int mill; int millLog, millPlank, millCount; long millStart, millNext;
        // Trading point 1: an arranged meet. An artisan asked for something; this bot fetches it and brings it to them.
        Player deliverTo; int deliverId; boolean delivering; long deliverStart, deliverArrived, nextErrand;
        // S9/S12: after a death, run back to the gravestone like a player and take the items back.
        Location graveAt; long graveUntil;
        Location graveFrom, graveFor, shopFrom, shopFor; // batch 18 item2: where this grave run / shop walk started (set on its first tick), for the scenario record
        long blessUntil; // batch 15 item3: walking to a crewmate's gravestone to bless it (the think loop waits)
        boolean grudgeHunt, grudgeShouted; long nextTollMutter;
        int stashHelm, stashShield; // casual look at the Exchange: helm and shield off, back on before the next trip
        // Agility: an occasional 10-lap session on the Gnome Stronghold course, like a player training it up.
        int agiStep = -1, agiLaps, agiTries, agiStall; long agiUntil, agiWait, nextAgility = System.currentTimeMillis() + (FAST ? 60_000 : 30 * 60_000); double agiXp, agiBest = Double.MAX_VALUE;
        boolean agilityNudge; // set when the route map turns a bot away from a shortcut its Agility is too low for
        double shopBest = Double.MAX_VALUE; int shopStalls; Location shopLast;
        Location viaFor, viaLast; int viaTurns; // pathing review 10-23 A-5: the shop walk these via waypoints belong to, the last one taken, how often it changed
        final List<int[]> shopBuy = new ArrayList<>(); // {item, amount}
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
            if (shopRetal != null && shopAt == null) endShopGuard(); // batch 20 B1: the shop walk is over
            if (companion != null) { if (!companion.isActive()) Companions.leave(this, null); return; } // in a player's crew: Companions drives the bot
            if (QuestRunner.active(bot)) return; // on a quest: QuestRunner drives the bot, a step a tick
            if (blessUntil > now) return; // batch 15 item3: blessing a crewmate's grave (its pulse drives the bot)
            if (graveAt != null && Deals.involved(bot)) { graveTick(now); return; } // audit 10-23 S-2: a grave run first (the stone crumbles while the deal waits)
            if (Deals.involved(bot)) return; // selling at a callout or walking to buy one: stay with it (it used to walk off to the next trip mid-sale, review 10-22)
            if (shopAt != null) { shopGuard(); shopTick(now); return; } // batch 20 B1
            if (mill != 0) { millTick(now); return; }
            if (delivering) { deliverTick(now); return; }
            if (graveAt != null) { graveTick(now); return; }
            if (agiStep >= 0) { agilityTick(now); return; }
            if (runner != null) {
                if (AfkTasks.RUNNING.containsKey(bot.getUsername().toLowerCase(Locale.ROOT))) { // still working
                    if (Boolean.getBoolean("smartbots.styleDebug") && styleOf(bot) != MELEE && RNG.nextInt(5) == 0) { core.game.node.Node v = bot.getProperties().getCombatPulse().getVictim(); System.out.println("[StyleDbg] " + bot.getUsername() + " at " + bot.getLocation() + " victim " + (v == null ? "-" : v.getName()) + " style " + bot.getProperties().getCombatPulse().getStyle() + " rangedXp " + (int) bot.getSkills().getExperience(Skills.RANGE) + " magicXp " + (int) bot.getSkills().getExperience(Skills.MAGIC) + " runes " + bot.getInventory().getAmount(558) + "/" + bot.getInventory().getAmount(562) + "/" + bot.getInventory().getAmount(556) + " runner " + runner.last); }
                    if (styleOf(bot) == MAGIC && bot.getProperties().getAutocastSpell() != null) { int[] s = spell(); if (bot.getInventory().getAmount(s[1]) < s[2] || bot.getInventory().getAmount(s[3]) < s[4]) { bot.getProperties().setAutocastSpell(null); say(BotChat.pick("Out of runes. Staff it is.", "No runes left. Bonk mode.")); } }
                    if (grudgeHunt && !grudgeShouted && runner.collected > 0) { grudgeShouted = true; String c = catchOf(bot); say((c != null ? c + " " : "") + BotChat.pick("That's one less of you lot.", "Never liked them. Never will.", "Personal, that one.")); }
                    if (crew != null) try { Scenes.Crews.banter(this, now); } catch (Throwable ignored) { } // batch 28: crew mates at one spot talk now and then, only with a player in sight (Scenes.java)
                    if ("the Al Kharid gate toll".equals(grudgeOf(bot)) && now > nextTollMutter) {
                        Location l = bot.getLocation();
                        if (l.getX() >= 3268 && l.getX() <= 3330 && l.getY() >= 3130 && l.getY() <= 3240 && l.getZ() == 0) { nextTollMutter = now + 3 * 3_600_000L; say(BotChat.pick("Ten gold for a gate. Robbery, every time.", "Still not paying that toll. Never.")); }
                    }
                    return;
                }
                trips++;
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
                runner = null;
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
                    if (crew != null) { if (crew.leader == this) for (Brain m : crew.members) if (m != this) m.shiftEnd = Math.min(m.shiftEnd, now + 60_000); crew.leave(this); } // the crew winds down with its leader
                    if (bot instanceof AIPlayer) { final Brain me = this; GameWorld.getPulser().submit(new core.game.system.task.Pulse(3) { @Override public boolean pulse() { logOut((AIPlayer) bot, me); return true; } }); }
                    nextThink = now + 60_000;
                    return;
                }
                if (shopAt != null) return; // just set off to buy its goal
                if (planPlankRun(now)) return;
                if (sinceLoiter >= (BREAK_SOON ? 1 : 2 + RNG.nextInt(3))) loiter(now); // every few trips: a breather at the Exchange where people are (every trip under the TestHumanLook-only BREAK_SOON)
                return;
            }
            if (now >= idleUntil) dressBack(); // break over: kit back on before anything else
            if (now > shiftEnd + 1_800_000L && now >= idleUntil && shopAt == null && !delivering && graveAt == null && mill == 0 && OFFLINE.size() < COUNT * 2 / 5) { // half an hour past the shift and still about: home
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
            if (crew != null && crew.leader != this) { // follow the leader's call
                Job lead = crew.job;
                Brain L = crew.leader;
                boolean leaderBusy = L == null || L.shopAt != null || L.mill != 0 || L.graveAt != null || L.delivering || L.agiStep >= 0 || L.starUntil > now;
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
            Job job = deliverTo == null && now > nextErrand ? Deals.errand(this) : null; // an artisan short of something nearby?
            if (job == null && deliverTo == null && now > nextErrand && !LOG_ASKS.isEmpty()) job = builderErrand(now); // a builder short of oak logs?
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
            if (RNG.nextBoolean()) { // half the breaks at the nearest hangout instead (deals still happen at the Exchange)
                Object[] best = null; double bd = 160;
                for (Object[] h : HANGOUTS) { double d = Location.create((Integer) h[1], (Integer) h[2], 0).getDistance(bot.getLocation()); if (d < bd) { bd = d; best = h; } }
                if (best != null) { loiterAt = Location.create((Integer) best[1], (Integer) best[2], 0); loiterName = (String) best[0]; }
            }
            if (loiterAt == GRAND_EXCHANGE && bot.getLocation().getDistance(GRAND_EXCHANGE) > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.payTeleport(bot)) Spectacle.teleport(bot, GRAND_EXCHANGE.transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0)); // otherwise it walks there; batch 15 item1
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
            for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) if (r.labelOverride != null && r.labelOverride.equals(h.name + "s")) n++;
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
            boolean outAndAbout = runner != null || shopAt != null || mill != 0 || agiStep >= 0 || starUntil > 0 || delivering || QuestBook.midQuest(bot) || companion != null; // audit B-5: a crewmate's death counts too (Companions sees graveAt and lets it go)
            boolean engineDeath = diedAt != null; // combat review 10-23 #6
            // audit 10-23 S-2: a death while idle (between trips, loitering, in a deal) is handled too, without blaming a job or a spot
            if (!outAndAbout && engineDeath) { idleDeath(); lastSeen = now; jumpFrom = null; diedAt = null; return; }
            if (outAndAbout && (engineDeath || lastSeen != null && atRespawn && (lastSeen.getDistance(LUMBRIDGE) > 40 || jumpFrom != null) && findGrave() != null)) { // audit 10-23 S-7: a teleport home (a crewmate following its human, a runner's teleport) is no death without a gravestone // audit B-12: deaths within 40 tiles (goblins over the river) went unnoticed; a jump counts only with a gravestone (a paid teleport to the Lumbridge men/courtyard spots isn't a death)
                if (engineDeath) lastSeen = diedAt; // combat review 10-23 #6: where it fell
                else if (jumpFrom != null) lastSeen = jumpFrom; // the last place it stood alive: the grave is there
                core.game.node.entity.combat.graves.Grave grave = findGrave(); // combat review 10-23 #11/#12: only a non-NPC death leaves one (Player.finalizeDeath)
                if (runner == null) { // died on an errand (audit #28): drop it and do what a player would
                    shopAt = null; pendingJob = null; mill = 0; if (agiStep >= 0) agiStep = -1; if (starUntil > 0) endStar(); if (delivering) endDelivery(false);
                    if (errandFor != null) try { Failures.Econ.errandDropped(bot, errandId, errandQty, "death"); } catch (Throwable ignored) { } // batch 19c §4.4
                    if (errandFor != null) { try { Player eh = Ledger.Wallet.findOnline(errandFor); if (eh != null && !eh.isArtificial()) eh.sendMessage("<col=0b5e1f>" + bot.getUsername() + " died on your errand; the order is off, sorry."); } catch (Throwable ignored) { } errandFor = null; errandId = 0; errandQty = 0; } // (review 10-22)
                }
                deaths++;
                String where = lastJob != null ? lastJob.label() : "somewhere";
                int tier = Crew.tierOf(this), cb = 0; try { cb = bot.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
                System.out.println("[SmartAdv] DEATH " + bot.getUsername() + " died near " + lastSeen + " while on " + where + " (death " + deaths + ", tier " + (tier == 2 ? "high" : tier == 1 ? "veteran" : "fresh") + ", combat " + cb + ", killed by " + (engineDeath ? diedKiller : "unknown") + ", food left " + (engineDeath ? String.valueOf(diedFood) : "?") + ")"); // combat review 10-23 #10
                Economy.DEATHS.incrementAndGet(); // audit 10-23 batch14 item5
                try { Failures.econNote(bot, Failures.DEATH); Failures.death(bot, lastSeen, engineDeath ? diedKiller : "unknown", where, -1, null, false); } catch (Throwable ignored) { } // batch 19c §6 repeat-death / death-spike
                try { Heat.death(lastSeen); } catch (Throwable ignored) { } // the heat map remembers where bots die
                if (lastJob != null) blacklist.put(lastJob.label(), System.currentTimeMillis() + 24L * 3600 * 1000);
                DEATHS.addFirst(new String[]{bot.getUsername(), where, String.valueOf(System.currentTimeMillis())}); while (DEATHS.size() > 30) DEATHS.removeLast();
                if (lastJob != null) { java.util.List<Long> l = SPOT_DEATHS.computeIfAbsent(lastJob.label(), k -> new java.util.ArrayList<>()); synchronized (l) { l.add(System.currentTimeMillis()); } }
                try { saveDeathMemorySoon(); } catch (Throwable t) { System.out.println("[SmartAdv] death memory: " + t); } // combat review 10-23 #5: it used to forget at every restart
                try { if (runner != null) { runner.bankingOut = true; runner.finish("Died."); } } catch (Throwable ignored) { } // combat review 10-23 #12: the trip ends here: no walk to a bank that fights the grave run (or, fed by the killing blow's size, tabs out again and again); what is left in the pack is sold to bot stock, as a failed trip's haul is
                runner = null;
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
            if (agi >= 35) return false;                                    // past the Gnome course
            if (agilityNudge) return true;
            nextAgility = now + (FAST ? 60_000 : 20 * 60_000);
            return RNG.nextInt(FAST ? 2 : 8) == 0 || Boolean.getBoolean("smartbots.testAgility");
        }

        void startAgility(long now) {
            agilityNudge = false; agiStep = 0; agiLaps = 0; agiTries = 0; agiStall = 0; agiWait = 0; agiBest = Double.MAX_VALUE;
            agiUntil = now + (FAST ? 8 * 60_000 : 45 * 60_000);
            nextAgility = now + (FAST ? 5 * 60_000 : 4 * 3_600_000L);
            say(BotChat.pick("Off to the Gnome course. Ten laps.", "Agility time. Gnome Stronghold.", "Can't climb a thing. Gnome course it is.", "Agility day. Gnome course.", "Those gnome nets won't climb themselves.", "Time to fall off a log. Gnome course."));
            BotChat.activity(bot, () -> "running laps on the Gnome Stronghold agility course");
            System.out.println("[SmartAdv] " + bot.getUsername() + " heading to the Gnome course (agility " + bot.getSkills().getStaticLevel(Skills.AGILITY) + ")");
        }

        void endAgility(String line) {
            if (line != null) say(line);
            System.out.println("[SmartAdv] " + bot.getUsername() + " finished agility: " + agiLaps + " laps, step " + agiStep + ", now level " + bot.getSkills().getStaticLevel(Skills.AGILITY));
            agiStep = -1; BotChat.clearActivity(bot);
            nextThink = System.currentTimeMillis() + 3_000;
        }

        void agilityTick(long now) {
            if (now > agiUntil) { endAgility(BotChat.pick("Legs are done. That'll do.", "Enough laps for today.")); return; }
            if (bot.getWalkingQueue().isMoving() || bot.getPulseManager().hasPulseRunning()) return;
            Location here = bot.getLocation();
            double xp = bot.getSkills().getExperience(Skills.AGILITY);
            if (agiWait > 0) { // crossing the last obstacle: XP means it worked
                if (xp > agiXp) {
                    agiWait = 0; agiTries = 0; agiStep++;
                    if (agiStep >= GNOME.length) {
                        agiStep = 0; agiLaps++;
                        if (agiLaps >= 10) { endAgility(BotChat.pick("Ten laps. Feeling lighter already.", "Done. Ten laps.")); return; }
                        if (agiLaps == 5 && RNG.nextInt(2) == 0) say("Halfway.");
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
        boolean highAlch(long now) {
            if (now < nextAlch || bot.getSkills().getStaticLevel(Skills.MAGIC) < 55 || !nearGE(bot)) return false;
            nextAlch = now + 10 * 60_000;
            if (MarketBrain.stockOf(561) < 60) return false;
            int bow = 0; for (int b : ALCH_BOWS) if (MarketBrain.isSurplus(b) && MarketBrain.stockOf(b) >= 20) { bow = b; break; }
            if (bow == 0) return false;
            int alch = 0; try { alch = core.cache.def.impl.ItemDefinition.forId(bow).getAlchemyValue(true); } catch (Throwable ignored) { }
            int cost = MarketBrain.value(bow) + MarketBrain.value(561);
            if (alch <= cost) return false; // no profit in it: a player wouldn't
            int n = 10;
            if (Ledger.Wallet.coins(bot) < (long) cost * n) return false;
            if (!Ledger.alchRoom((long) (alch - cost) * n)) return false;
            Item wep = null; try { wep = bot.getEquipment().get(3); } catch (Throwable ignored) { }
            boolean fireStaff = wep != null && (wep.getId() == 1387 || wep.getId() == 1393 || wep.getId() == 1401 || wep.getId() == 3053 || wep.getId() == 3054);
            if (!fireStaff) { if (MarketBrain.stockOf(554) < 5 * n + 50 || Ledger.Wallet.coins(bot) < (long) (cost + 5 * MarketBrain.value(554)) * n) return false; }
            if (Ledger.consume(bot, bow, n, "alch") < n) return false;
            if (Ledger.consume(bot, 561, n, "alch") < n) return false;
            if (!fireStaff && Ledger.consume(bot, 554, 5 * n, "alch") < 5 * n) return false; // five fire runes a cast without a fire staff
            Ledger.Wallet.give(bot, (long) alch * n);
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
            if (j.hunt != null) return Math.max(1, Math.min(4, j.hunt.spawnsAtSpot / 3));
            switch (j.task) {
                case ADAMANT: return 2;      // two rocks at the swamp mine, two on Karamja
                case MITHRIL: case GOLD: case SILVER: return 2;
                case RUNITE: return 1;
                case COAL: case IRON: return 6;
                default: return 4;
            }
        }

        /** How many other smart adventurers are already on this task. */
        static int crowd(AfkTasks.Task t) {
            int n = 0;
            for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) if (r.task == t && r.p.isArtificial()) n++;
            return n;
        }

        /** The best bounty this bot can get: weight / (distance + risk), skipping surplus items. */
        static final boolean DEBUG_JOBS = Boolean.getBoolean("jobs.debug");

        /** Combat a job check should trust: a 1-Defence bot with 70 Attack is not really combat 64 (owner notes #1). */
        int effectiveCombat() { return Math.min(bot.getProperties().getCurrentCombatLevel(), bot.getSkills().getStaticLevel(Skills.DEFENCE) + 25); }

        /** Monsters that killed bots in numbers on 2026-10-20 for reasons combat level doesn't cover. */
        boolean deadlyFor(HuntMap.Hunt h, int combat) {
            String n = h.name.toLowerCase(Locale.ROOT);
            if (n.contains("banshee") && !(bot.getEquipment().get(0) != null && bot.getEquipment().get(0).getId() == 4166)) return true; // needs earmuffs
            if ((n.contains("blamish") || n.contains("zogre") || n.contains("crawling hand")) && combat < 60) return true; // Mort Myre, Jiggig, the Slayer Tower
            return false;
        }

        Job chooseJob() { boolean f = false; try { f = Scenes.Crews.filter(this); } catch (Throwable ignored) { } return chooseJob(f); } // batch 28: a crew leader looks near home first
        Job chooseJob(boolean crewFilter) {
            String th = System.getProperty("smartbots.testHunt"); // trials only: send rangers and mages to one monster
            if (th != null && styleOf(bot) != MELEE) for (Job j : JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(th) && Gates.canEnter(bot, j.hunt.spot)) { if (bot.getLocation().getDistance(j.hunt.spot) > 20) bot.getProperties().setTeleportLocation(j.hunt.spot); return j; }
            List<String> dbg = new ArrayList<>();
            Job best = null;
            double bestScore = 0;
            int combat = effectiveCombat();
            long nowMs = System.currentTimeMillis();
            boolean broke = broke();
            int[] planBudget = {PLAN_PER_CHOICE}; // batch 26 fix 3: new (uncached) plans this choice may run
            for (Job j : JOBS) {
                if (!reachable(j)) continue; // behind a quest, guild, key or toll it doesn't have (it used to buy food and bait first, then abort at the gate; review 10-22)
                double weight = jobWeight(j);
                boolean coinJob = j.hunt == null && j.task.kind == AfkTasks.Kind.THIEVE && j.task.products != null && j.task.products.length > 0 && j.task.products[0] == 995;
                if (weight <= 0 && !(broke && coinJob)) continue; // coins are never "wanted" by the market, but a skint bot needs them
                if (j.hunt != null && j.hunt.name.toLowerCase(Locale.ROOT).contains("dragon") && !hasAntifire()) continue; // never without a shield
                if (j.hunt != null && combat < 70 && poisonous(j.hunt.name)) continue; // S11: poison killed two bots on their first night; leave those to the strong
                if (j.hunt != null && deadlyFor(j.hunt, combat)) continue;
                int spotDeaths = recentDeaths(j.label());
                if (spotDeaths >= 2) continue; // two of us died there today: nobody goes back for a day
                if (j.hunt != null) {
                    int need = j.level + 10 * spotDeaths;
                    if (magicUser(j.hunt.name)) need = Math.max(need, j.hunt.npcLevel * 2 + 10);
                    if (combat < need) continue;
                }
                if (blacklist.getOrDefault(j.label(), 0L) > nowMs) continue; // a spot that killed us recently
                if (!stuckLegs.isEmpty() && stuckLegs.getOrDefault(legKey(j.label(), bot.getLocation()), 0L) > nowMs) continue; // batch 26 fix 2: stuck on the way there from here lately
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
                if (PLAN_JOBS && dist > PLAN_FROM) { // batch 26 fix 3: the walk is the planned length (1.5-2x the straight line), not the straight line
                    int pl = plannedTiles(bot, j.hunt != null ? j.hunt.spot : nearestSpot(j), planBudget, j.label());
                    if (pl == -1 && PLAN_NO_ROUTE) continue; // no walkable route on the planner's grid: not a job to walk to
                    if (pl > 0) dist = Math.max(dist, pl);
                }
                // A trip it can only make by teleport (320+ tiles, or across the Salve into Morytania from the west) needs the fare in the
                // bank first: a 3 gp fighter walked for Mort Myre snails and then undead chickens from Al Kharid and stood at the gate 27 min (audit §13)
                boolean acrossSalve = j.hunt != null && j.hunt.spot.getX() >= 3400 && j.hunt.spot.getY() < 6400 && bot.getLocation().getX() < 3400;
                long coinsNow = Ledger.Wallet.coins(bot);
                if (acrossSalve && coinsNow < Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556)) + 200) continue;
                if (Preflight.ON && coinsNow < Preflight.fare() + 200 && Preflight.leavingMorytania(bot.getLocation(), j.hunt != null ? j.hunt.spot : nearestSpot(j))) continue; // batch 20 A2: west out of Morytania needs the teleport fare
                if (dist > AfkTasks.Runner.TELEPORT_MIN && coinsNow < 5_000) continue; // under 5k the runner walks (start() sets walker), and a 320-tile walk is where bots get stuck
                int busy = j.hunt != null ? crowdHunt(j.hunt) : crowd(j.task);
                int party = crew != null && crew.leader == this ? crew.members.size() : 1;
                if (busy + party > capacity(j)) continue; // the patch is full: two adamantite rocks can't feed five miners
                if (j.hunt != null && j.hunt.npcLevel >= 80 && (bot.getSkills().getStaticLevel(Skills.PRAYER) < 43 || !hasAntifire())) continue; // the big things need prayer and a shield
                double score = weight / (1 + dist / 150.0) * (0.8 + RNG.nextDouble() * 0.4);
                if (lastJob != null && j == lastJob) score *= 0.5; // vary the day
                score /= 1 + busy; // don't all pile onto the same patch
                if (broke) { // money first: whatever sells for most that the bot can get
                    // Expected coins for one trip: gathering ~20 units of its best product; hunts ~15 kills x the drop table's
                    // expected value (a rare 5k drop at 1% is worth 50, not 5,000); pickpocketing pays coins on the spot and
                    // nothing waits in a queue, so it counts double for a bot that is broke now.
                    double v = 0;
                    if (j.hunt != null) { double ev = 0; for (int k = 0; k < j.hunt.products.length; k++) if (j.hunt.products[k] != 995 && !MarketBrain.isSurplus(j.hunt.products[k])) ev += MarketBrain.value(j.hunt.products[k]) * j.hunt.chance[k]; v = ev * 15; }
                    else { int top = 0; for (int id : j.products()) if (id != 995 && !MarketBrain.isSurplus(id)) top = Math.max(top, MarketBrain.value(id)); v = top * 20.0; }
                    if (coinJob) v = Math.max(v, 2 * 40 * (j.task == AfkTasks.Task.WARRIOR ? 18 : j.task == AfkTasks.Task.FARMER ? 9 : 3));
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
                if (DEBUG_JOBS && HIGH_TIER.getOrDefault(bot.getUsername().toLowerCase(Locale.ROOT), false)) dbg.add(String.format("%s=%.2f", j.label(), score));
                if (score > bestScore) { bestScore = score; best = j; }
            }
            if (crewFilter) { if (best == null) { Scenes.Crews.fellBack(this); return chooseJob(false); } Scenes.Crews.picked(this, best); } // batch 28: nothing near home: the nearest job, logged
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

        static final int[] BOWS = {841, 843, 849, 853};            // shortbow, oak, willow, maple
        static final int[] BOW_LEVEL = {1, 5, 20, 30};
        static final int[] ARROWS = {882, 884, 886, 888, 890};      // bronze, iron, steel, mithril, adamant
        static final int[] ARROW_LEVEL = {1, 1, 5, 20, 30};
        static final int[] ARROW_MAX_BOW = {0, 0, 1, 2, 3};         // weakest bow that can fire each arrow

        int bestBow() { int r = bot.getSkills().getStaticLevel(Skills.RANGE), b = 0; for (int i = 0; i < BOWS.length; i++) if (r >= BOW_LEVEL[i]) b = i; return b; }

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
                kit.add(new int[]{4, hide ? 1135 : 1129});
                kit.add(new int[]{7, r >= 40 ? 1099 : 1095});
                kit.add(new int[]{9, r >= 40 ? 1065 : 1063});
                kit.add(new int[]{3, BOWS[bestBow()]});
            } else {
                Item w = bot.getEquipment().get(3);
                if (w == null || w.getId() != 1387) { List<int[]> staff = new ArrayList<>(); staff.add(new int[]{1387, 1}); return staff; } // the staff first (Zaff's)
                kit.add(new int[]{0, 579}); kit.add(new int[]{7, 1011});
                if (MarketBrain.stockOf(577) > 0) kit.add(new int[]{4, 577}); // no shop sells the robe top; only if someone made one
            }
            List<int[]> out = new ArrayList<>(); long cost = 0, purse = Ledger.Wallet.coins(bot) - 300;
            for (int[] k : kit) {
                Item cur = bot.getEquipment().get(k[0]);
                if (cur != null && cur.getId() == k[1]) continue;
                if (k[0] == 0 && stashHelm != 0) continue;
                boolean replace = cur == null || metal(cur) || k[0] == 3; // the weapon is the style
                if (k[0] == 3 && cur != null && st == RANGED) for (int i = 0; i < BOWS.length; i++) if (cur.getId() == BOWS[i] && i >= bestBow()) replace = false;
                if (!replace) continue;
                long v = Math.max(1, MarketBrain.value(k[1]));
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
            return Ledger.Wallet.coins(bot) >= 15_000 && (VETERAN.getOrDefault(key, false) || HIGH_TIER.getOrDefault(key, false));
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
        boolean planPlankRun(long now) {
            if (shopAt != null || !builder() || now < millNext || RNG.nextInt(5) != 0) return false;
            millNext = now + (FAST ? 120_000 : 90 * 60_000);
            int[] pick = null; int have = 0;
            long coins = Ledger.Wallet.coins(bot);
            for (int[] m : MILL) { int n = bot.getBank().getAmount(m[0]); if (n >= 10 && n > have && (m[0] == 1521 || coins >= 40_000)) { have = n; pick = m; } }
            if (pick == null) {
                for (int[] m : MILL) {
                    long gate = m[0] == 1521 ? 5_000 : 20_000; // oak needs little; teak and mahogany are for the well-off (40k+)
                    if (m[0] != 1521 && coins < 40_000) continue;
                    if (nearGE(bot) && MarketBrain.stockOf(m[0]) >= 40 && coins >= 27L * (MarketBrain.value(m[0]) + m[2]) + gate) { pick = m; break; }
                }
                if (pick == null) { askForLogs(now); return false; }
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
            if (j.skill >= 0 && bot.getSkills().getStaticLevel(j.skill) < j.level) return false;
            if (j.skill < 0 && combat < j.level) return false;
            if (j.hunt != null) {
                if (j.hunt.name.toLowerCase(Locale.ROOT).contains("dragon") && !hasAntifire()) return false;
                if (combat < 70 && poisonous(j.hunt.name)) return false;
                if (deadlyFor(j.hunt, combat)) return false;
                if (magicUser(j.hunt.name) && combat < j.hunt.npcLevel * 2 + 10) return false;
                if (j.hunt.npcLevel >= 80 && (bot.getSkills().getStaticLevel(Skills.PRAYER) < 43 || !hasAntifire())) return false;
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
                if (crowd(j.task) >= capacity(j)) continue;
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
                    int n = Math.min(millCount, bot.getBank().getAmount(millLog));
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
                    if (build > 0) { bot.getBank().remove(new Item(millPlank, Math.min(build, bot.getBank().getAmount(millPlank)))); bot.getInventory().remove(new Item(millPlank, bot.getInventory().getAmount(millPlank))); PLANKS_BUILT += build; }
                    if (sell > 0 && bot.getBank().remove(new Item(millPlank, sell))) { Ledger.supply(bot, millPlank, sell); PLANKS_SOLD += sell; }
                    try { bot.getSkills().addExperience(Skills.CONSTRUCTION, build * 30.0, true); } catch (Throwable ignored) { }
                    System.out.println("[SmartAdv] " + bot.getUsername() + " built with " + build + " x " + MarketBrain.name(millPlank).toLowerCase(Locale.ROOT) + (sell > 0 ? ", sold " + sell + " spare as notes" : ""));
                    BotChat.news(String.format(BotChat.pick("%s has been building again. Whole house smells of %s.", "Saw %s hauling %s through the Rimmington portal. Another room, apparently."), bot.getUsername(), MarketBrain.name(millPlank).toLowerCase(Locale.ROOT)));
                    mill = 0; BotChat.clearActivity(bot);
                    return;
                }
                default: mill = 0;
            }
        }

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
            boolean needTool = job.task.tools != null && !AfkTasks.hasAny(p, job.task.tools);
            int food = foodHeld();
            boolean needFood = (job.task.kind == AfkTasks.Kind.FIGHT || job.task.kind == AfkTasks.Kind.THIEVE || job.hunt != null) && food < 8;
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
                    int t = job.task.tools == AfkTasks.AXES ? usableTool(job.task.tools, AXE_BY_LEVEL, p.getSkills().getStaticLevel(Skills.WOODCUTTING), stock)
                            : job.task.tools == AfkTasks.PICKAXES ? usableTool(job.task.tools, PICK_BY_LEVEL, p.getSkills().getStaticLevel(Skills.MINING), stock)
                            : stock.contains(job.task.tools[0]) ? job.task.tools[0] : -1;
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
            if (viaFor != this.shopAt) { viaFor = this.shopAt; viaLast = null; viaTurns = 0; } // pathing review 10-23 A-5: a new shop walk
            Location via = viaTurns > 6 ? null : AfkTasks.Runner.bridgeVia(bot, here, shopAt); if (via != null && here.getDistance(via) > 3) shopAt = via; // rivers, walled towns, the jail: go round
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

        /** Kit pieces the Exchange has in stock are bought there (the smith, fletcher or crafter who made them is paid). */
        void kitFromExchange() {
            int got = 0;
            for (int[] pc : armourNeeds()) {
                if (MarketBrain.stockOf(pc[0]) <= 0) continue;
                if (Ledger.consume(bot, pc[0], 1, "kit") == 1) { if (wearKit(pc[0])) got++; } // not worn: wear() has put it in the pack or bank (audit 10-23 S-1)
            }
            if (got > 0) { try { bot.getEquipment().refresh(); } catch (Throwable ignored) { } if (RNG.nextInt(2) == 0) say(BotChat.pick("Picked up some kit off the Exchange.", "New gear from the Exchange. Lovely.")); }
        }

        int held(int id) { return bot.getBank().getAmount(id) + bot.getInventory().getAmount(id) + (bot.getEquipment().get(13) != null && bot.getEquipment().get(13).getId() == id ? bot.getEquipment().get(13).getAmount() : 0); }

        /** Arrows or runes it still needs for a fight trip: {item, amount}. */
        List<int[]> supplyNeeds() {
            List<int[]> out = new ArrayList<>();
            int st = styleOf(bot);
            if (st == RANGED) { int a = bestArrow(); int h = held(a); if (h < 100) out.add(new int[]{a, 200 - h}); }
            else if (st == MAGIC) { int[] s = spell(); int casts = 150;
                int h1 = held(s[1]); if (h1 < casts * s[2] / 2) out.add(new int[]{s[1], casts * s[2] - h1});
                int h2 = held(s[3]); if (h2 < casts * s[4] / 2) out.add(new int[]{s[3], casts * s[4] - h2}); }
            return out;
        }

        /** Arrows and runes from Exchange stock first: that's the fletcher's and runecrafter's custom. */
        void suppliesFromExchange() {
            for (int[] su : supplyNeeds()) {
                int take = Math.min(su[1], Math.max(0, MarketBrain.stockOf(su[0])));
                if (take <= 0) continue;
                int got = Ledger.consume(bot, su[0], take, "ammo");
                if (got > 0) bot.getBank().add(new Item(su[0], got));
            }
        }

        /** Arrows into the quiver, runes into the pack, the right spell set to autocast. */
        void equipSupplies() {
            int st = styleOf(bot);
            try {
                if (st == RANGED) {
                    int a = bestArrow();
                    int n = bot.getBank().getAmount(a); if (n > 0) bot.getBank().remove(new Item(a, n));
                    int i = bot.getInventory().getAmount(a); if (i > 0) { bot.getInventory().remove(new Item(a, i)); n += i; }
                    Item cur = bot.getEquipment().get(13);
                    if (cur != null && cur.getId() != a && n > 0) { bot.getEquipment().remove(cur); bot.getBank().add(cur); }
                    if (n > 0 && !bot.getEquipment().add(new Item(a, n), -1, true, false)) bot.getBank().add(new Item(a, n));
                } else if (st == MAGIC) {
                    int[] s = spell();
                    for (int r : new int[]{s[1], s[3]}) { int n = bot.getBank().getAmount(r); if (n > 0 && bot.getBank().remove(new Item(r, n)) && !bot.getInventory().add(new Item(r, n))) bot.getBank().add(new Item(r, n)); }
                    Item staff = bot.getEquipment().get(3);
                    boolean ready = staff != null && staff.getId() == 1387 && bot.getInventory().getAmount(s[1]) >= s[2] && bot.getInventory().getAmount(s[3]) >= s[4];
                    bot.getProperties().setAutocastSpell(ready ? (core.game.node.entity.combat.spell.CombatSpell) core.game.node.entity.player.link.SpellBookManager.SpellBook.MODERN.getSpell(s[0]) : null);
                }
                bot.getEquipment().refresh();
                if (Boolean.getBoolean("afk.debug")) { Item w = bot.getEquipment().get(3), q = bot.getEquipment().get(13); System.out.println("[SmartAdv] " + bot.getUsername() + (st == RANGED ? " ranger" : " mage") + ": weapon " + (w == null ? "none" : w.getName()) + ", ammo " + (q == null ? "none" : q.getAmount() + " " + q.getName()) + ", autocast " + bot.getProperties().getAutocastSpell() + ", style " + bot.getProperties().getCombatPulse().getStyle()); }
            } catch (Throwable t) { if (Boolean.getBoolean("afk.debug")) System.out.println("[SmartAdv] supplies " + bot.getUsername() + ": " + t); }
        }

        void start(Job job) {
            if (shopRetal != null) endShopGuard(); // batch 20 review: retaliation back on before the job's runner saves it (or the runner's restore would keep it off)
            dressBack();
            noJobSince = 0; // batch 19c
            try { Failures.tripCoins(bot, Ledger.Wallet.coins(bot), true, null, job.label(), trips); if (job.task.kind == AfkTasks.Kind.THIEVE || job.task.kind == AfkTasks.Kind.FIGHT || job.task.kind == AfkTasks.Kind.GROUND || job.hunt != null) Failures.econNote(bot, Failures.THIEVE); } catch (Throwable ignored) { } // batch 19c §4.1: the trip window (fights and pickups may loot coins: [Fable] all of them flagged)
            goodFoodOnly = job.hunt != null && job.hunt.npcLevel >= 25;
            // A tool in the bank is taken out, like a player would, instead of buying another one.
            if (job.task.tools != null && !AfkTasks.hasAny(bot, job.task.tools)) for (int t : job.task.tools) { if (bot.getBank().getAmount(t) > 0 && bot.getBank().remove(new Item(t, 1))) { if (!bot.getInventory().add(new Item(t, 1))) bot.getBank().add(new Item(t, 1)); break; } }
            if (fightJob(job) && styleOf(bot) == MELEE) { // Defence well behind Attack: train it, like a player avoiding the glass-cannon trap
                int att = bot.getSkills().getStaticLevel(Skills.ATTACK), str = bot.getSkills().getStaticLevel(Skills.STRENGTH), def = bot.getSkills().getStaticLevel(Skills.DEFENCE);
                String st = def < att - 10 ? "defence" : str < att ? "strength" : RNG.nextBoolean() ? "attack" : "strength";
                try { AfkTasks.setStyle(bot, st); } catch (Throwable ignored) { }
            }
            if (fightJob(job) && shopAt == null && !armourChecked && !exchangeChecked) {
                exchangeChecked = true;
                if (nearGE(bot)) { kitFromExchange(); if (styleOf(bot) != MELEE) suppliesFromExchange(); potionsFromExchange(job); otherPotionsFromExchange(job); } // batch 15 items3-4
                else if (exchangeHasWhatINeed(job)) { // walk (or pay to teleport) to the Grand Exchange first, like a player
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
                if (RNG.nextInt(3) == 0) say(styleOf(bot) == RANGED ? BotChat.pick("Quiver's full. Let's go.", "Bow strung, arrows in. Off we go.", "Arrows ready. Off we go.", "Bow in hand. Let's hunt.") : BotChat.pick("Runes in the pack. Time for some fire.", "Staff, runes, robe. Ready.", "Fire bolts ready.", "Staff charged. Let's go."));
            }
            Player p = bot;
            if (job.task.tools == AfkTasks.AXES && !AfkTasks.hasAny(p, AfkTasks.AXES)) { int t = buyTool(p, AXE_BY_LEVEL, p.getSkills().getStaticLevel(Skills.WOODCUTTING)); if (t > 0) packOrBank(p, new Item(t)); }
            if (job.task.tools == AfkTasks.PICKAXES && !AfkTasks.hasAny(p, AfkTasks.PICKAXES)) { int t = buyTool(p, PICK_BY_LEVEL, p.getSkills().getStaticLevel(Skills.MINING)); if (t > 0) packOrBank(p, new Item(t)); }
            if (job.task.tools != null && job.task.tools != AfkTasks.AXES && job.task.tools != AfkTasks.PICKAXES && !AfkTasks.hasAny(p, job.task.tools)) {
                int tool = job.task.tools[0];
                boolean got = nearGE(p) && MarketBrain.stockOf(tool) > 5 ? Ledger.consume(p, tool, 1, "tool") == 1 : Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(tool)));
                if (got) packOrBank(p, new Item(tool));
            }
            if (job.task.kind == AfkTasks.Kind.FIGHT || job.task.kind == AfkTasks.Kind.THIEVE || job.hunt != null) { buyFood(job.hunt != null ? job.hunt.npcLevel : 20); packFood(); } // audit B-2: and into the pack
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
            Location spotAt; String spotName;
            if (job.hunt != null) { spotAt = job.hunt.spot; spotName = "the " + job.hunt.name.toLowerCase(Locale.ROOT) + "s"; }
            else {
                AfkTasks.Spot[] spots = AfkTasks.SPOTS.get(job.task);
                AfkTasks.Spot spot = null;
                for (AfkTasks.Spot s : spots) if (Gates.canEnter(p, s.at) && (spot == null || s.at.getDistance(p.getLocation()) < spot.at.getDistance(p.getLocation()))) spot = s;
                if (spot == null) spot = spots[0];
                spotAt = spot.at; spotName = spot.name;
            }
            if (Gates.desert(spotAt)) packWater(); // combat review 10-23 #9: nothing carried water, and the heat killed bots past the Shantay Pass
            int want = 0;
            for (int id : job.products()) want = Math.max(want, Math.max(0, MarketBrain.target(id) - MarketBrain.stockOf(id)));
            int tripMax = Integer.getInteger("smartbots.tripMax", 56); // test knob: short trips
            if (errandFor != null && errandQty > 0) { tripMax = errandQty; want = Math.max(want, errandQty); } // a player's order: exactly what they asked for
            // Prayer: a fighter under 43 Prayer buries the bones it gets (like players did on the way up), so Protect
            // from Melee comes within reach in time; at 43+ the bones go to market as before. Veterans and fighters always,
            // fresh bots one trip in two (they need the money from bones more).
            int prayer = p.getSkills().getStaticLevel(Skills.PRAYER);
            boolean fightTrip = job.hunt != null || job.task.kind == AfkTasks.Kind.FIGHT;
            String pk = p.getUsername().toLowerCase(Locale.ROOT);
            boolean buries = fightTrip && prayer < 43 && (VETERAN.getOrDefault(pk, false) || fav == -1 || RNG.nextBoolean());
            AfkTasks.Runner r = new AfkTasks.Runner(p, job.task, true, Math.min(tripMax, Math.max(Math.min(14, tripMax), want)), buries, true);
            if (job.hunt != null) { r.targetsOverride = job.hunt.names; r.productsOverride = job.hunt.products; r.labelOverride = job.hunt.name + "s"; }
            if (job.task.kind == AfkTasks.Kind.FISH) {
                int need = 1; for (int id : job.products()) need = Math.max(need, COOK_LEVEL.getOrDefault(id, 1));
                boolean canCook = p.getSkills().getStaticLevel(Skills.COOKING) >= need;
                r.cookHaul = errandFor != null ? errandCook : canCook && !(fav == Skills.FISHING && RNG.nextInt(10) < 6); // a fisher sells raw 60% of the time; an errand is cooked only if the order was (review 10-22)
            }
            r.sell = true;
            r.styleSkill = pick(new Integer[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE});
            r.spot = spotAt;
            r.walker = Ledger.Wallet.coins(p) < 5_000; // the poor walk; the rich take the teleport
            r.usePrayer = job.hunt != null && job.hunt.npcLevel >= 60 && p.getSkills().getStaticLevel(Skills.PRAYER) >= 37; // batch 15 item1: 37 magic, 40 missiles, 43 melee (prayerTick checks which)
            r.setDelay(3); // a third of the owner's bot rate: 50 of these must stay cheap
            if (!AfkTasks.launch(r, AfkTasks.State.TO_SPOT, spotAt)) System.out.println("[SmartAdv] " + p.getUsername() + " couldn't set out for " + job.label() + ": " + r.last + " (audit 10-23 A-11: not left in RUNNING)");
            runner = r;
            try { Spectacle.maybeRace(this, job); } catch (Throwable ignored) { }
            current = job.task;
            lastJob = job;
            final String where = spotName;
            BotChat.activity(p, () -> "off getting " + job.label().toLowerCase(Locale.ROOT) + (where != null ? " near " + where : ""));
            Mood m = mood();
            if (m == Mood.GRUMPY) { if (RNG.nextInt(2) == 0) say(BotChat.pick("Back out. Not happy about it.", "Fine. " + job.label() + ". Whatever.", "Someone has to, and apparently it's me again.", "Here we go again.", "Fine. I'm going.")); }
            else if (m == Mood.TIRED) { if (RNG.nextInt(2) == 0) say(String.format(BotChat.pick("One more run of %s, then bed.", "Last trip for %s today, I mean it this time.", "Last %s trip of the day.", "%s, then sleep. That's the deal."), job.label().toLowerCase(Locale.ROOT))); }
            else if (m == Mood.CHATTY || RNG.nextInt(2) == 0) say(String.format(pick(SET_OUT), job.label().toLowerCase(Locale.ROOT)));
            System.out.println("[SmartAdv] " + p.getUsername() + " -> " + (job.hunt != null ? "hunt " + job.hunt.name : job.task.key) + " at " + spotName + " (bounty " + String.format("%.2f", jobWeight(job)) + ")");
        }
    }

    // ------------------------------------------------------------ batch 26 (stuck review fixes 2 and 3)

    static final boolean LEG_ON = !"false".equals(System.getProperty("jobs.legCooldown"));
    static final long LEG_COOL_MS = Long.getLong("jobs.legCooldownMin", 120L) * 60_000L;
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
        NavPlanner.Route r;
        try { r = NavPlanner.plan(start, to, NavWalker.agility(bot)); } catch (Throwable t) { return -2; }
        int tiles = r == null ? -1 : r.tiles;
        if (g != NavPlanner.gen) return -2; // the graph changed under the plan
        PLANNED.put(k, new int[]{g, tiles});
        plannedRuns++;
        if (tiles < 0) { plannedNoRoute++; System.out.println("[Jobs] no planned route " + start + " -> " + to + " (" + job + ")" + (PLAN_NO_ROUTE ? ": skipped" : ": kept on its straight distance") + " when chosen from " + o); }
        return tiles;
    }

    /** For the status command: how many smart adventurers are out working. */
    static int working() {
        int n = 0;
        for (Player p : Repository.getPlayers()) if (p != null && p.isArtificial() && AfkTasks.RUNNING.containsKey(p.getUsername().toLowerCase(Locale.ROOT))) n++;
        return n;
    }
}
