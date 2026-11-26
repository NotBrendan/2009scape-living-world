package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * W0 lane A0 (mechanics-finish-plan-2026-11-18): forced-state seeding for scenario runs on farm worlds. Default off, one flag each:
 *   -Dsmartbots.seed.summoning=<lvl>   Summoning level (full points) + Wolf Whistle done
 *   -Dsmartbots.seed.pouches=<id:n,..> pouches into the bank (topped up to n)
 *   -Dsmartbots.seed.farming=<lvl>     Farming level
 *   -Dsmartbots.seed.seeds=<id:n,..>   seeds (any item) into the bank (topped up to n)
 *   -Dsmartbots.seed.slayer=<lvl>      Slayer level
 *   -Dsmartbots.seed.coins=<n>         coins set to exactly n (0 = broke), all in the bank
 *   -Dsmartbots.seed.at=<x,y,z>        every bot starts on this tile (e.g. a bank by the farm under test)
 *   -Dsmartbots.seed.hunt=<name,..>    every bot only hunts (SmartAdventurers.chooseJob); a list is shared out by index pair, so both halves of a split get the same spots
 *   -Dsmartbots.seed.grow=true         farming: a bot's planted patches are forced grown as its run ends and its CHECK is due a minute later
 *   -Dsmartbots.seed.fill=<id:free>    farming: as a CHECK reaches the patches the pack is filled with item <id> down to <free> free slots (the live full-pack harvest)
 *   -Dsmartbots.seed.task=<monster>:<n> W2 lane S: every bot starts holding this Slayer task from Turael (a Tasks name, e.g. BEARS:10; n 1..255, a bare name = 10; needs -Dbots.slayer=true)
 *   With .seed.slayer or .seed.task set, .seed.hunt yields to a bot that holds a Slayer task (so the task trip code decides).
 *   -Dsmartbots.seed.magic=<lvl>      Magic level (W8 alchAll / anyStaff scenarios)
 *   -Dsmartbots.seed.levels=<skill:lvl,..> any skills by Skills constant name, case-insensitive (e.g. runecrafting:44,strength:70,attack:70); raised, never lowered; an unknown name is logged once and skipped
 *   -Dsmartbots.seed.job=<TASK>        every bot takes this non-hunt AfkTasks job when it is in JOBS (e.g. LIMPWURT; W8 herbSupply)
 *   -Dsmartbots.seed.rcstick=true      altarLoop: the altar portal does not move the runecrafter (forces the walk-back give-up; W8 altarLoop)
 *   -Dsmartbots.seed.scen=<name,..>    W8: judge these scenarios (Scen.java) and print [Scen] PASS/FAIL <name>; .seed.ticks=<n> (default 6000)
 *   -Dsmartbots.seed.combat=<lvl>      w9 scen2: Attack, Strength, Defence and Hitpoints raised (never lowered) to the least common level that gives combat >= lvl (4..126); Defence to lvl-25 at least first
 *   -Dsmartbots.seed.quest=<NAME,..>   w9 scen2: these quests done (content.data.Quests names, e.g. PRIEST_IN_PERIL) and in the bot's Gates quest history
 *   -Dsmartbots.seed.master=<name>     w9 scen2: the master a .seed.task is held from: TURAEL (default) or VANNAKA
 *   -Dsmartbots.seed.scenStock=<id:n,..> w14 scen: bot stock on the Exchange topped up to n as world lots (Ledger.supply), once, from the judge (needs .seed.scen)
 *   -Dsmartbots.seed.split=<feature>   half ON / half OFF inside one world by bot index (even = ON, odd = OFF); "combat" is wired (Bob.combatPlan1)
 * Applied once per bot at creation (SmartAdventurers.enlist). Farm guard = the harness markers the existing test knobs use
 * (Bob.FARM = -Dtest.tickprobe) plus the two run.sh always passes (-Dfarm.gamePort, -D2009scape.bindAddress=127.0.0.1); the live
 * start line has none of them. Refused -> one line "[Seed] refused (live)" and nothing is ever seeded.
 * Pure helpers (parse, allowed, half) are checked by TestSeed without a world; this class only reads system properties at init.
 */
public final class Seed {
    private Seed() { }

    static final String P = "smartbots.seed.";
    static final String[] KEYS = {"summoning", "pouches", "farming", "seeds", "slayer", "coins", "split", "at", "hunt", "grow", "fill", "task", "combat", "quest", "master", "magic", "job", "rcstick", "scen", "ticks", "scenStock", "levels", "scenStockBot"};
    static final boolean ASKED = asked(k -> System.getProperty(P + k));
    static final boolean ALLOWED = allowed(Boolean.getBoolean("test.tickprobe"), System.getProperty("farm.gamePort"), System.getProperty("2009scape.bindAddress"));
    /** Any seed flag given on a farm world. Everything below is a no-op when false. */
    static final boolean ON = ASKED && ALLOWED;
    static final int SUMMONING = lvl("summoning"), FARMING = lvl("farming"), SLAYER = lvl("slayer"), MAGIC = lvl("magic");
    /** .seed.levels as {Skills id, level}; empty when off or unset. */
    static final int[][] LEVELS = ON ? skillLevels(Skills.class, System.getProperty(P + "levels")) : new int[0][];
    /** W8 scenarios (Scen.java): the names to judge, the ticks to run, the forced job, the altar-portal fault. */
    static final String[] SCEN = ON ? Scen.names(System.getProperty(P + "scen")) : new String[0];
    static final int TICKS = ON ? (int) Math.max(100, Math.min(1_000_000, num(System.getProperty(P + "ticks"), 6000))) : 0;
    static final String JOB = ON ? System.getProperty(P + "job", "").trim().toUpperCase(Locale.ROOT) : "";
    static final boolean RC_STICK = ON && Boolean.getBoolean(P + "rcstick");
    static final long COINS = ON ? num(System.getProperty(P + "coins"), -1) : -1; // -1 = leave the bot's coins alone
    static final int[][] POUCHES = ON ? pairs(System.getProperty(P + "pouches")) : new int[0][], SEEDS = ON ? pairs(System.getProperty(P + "seeds")) : new int[0][];
    static final boolean STOCK_BOT = ON && "true".equalsIgnoreCase(System.getProperty(P + "scenStockBot")); // w21: scenStock lots belong to the first bot online (its buyers pay that bot), not to "world"
    static final int[][] STOCK = ON ? pairs(System.getProperty(P + "scenStock")) : new int[0][]; // w14 scen: bot stock topped up by the judge (Scen.stock)
    static final String SPLIT = ON ? System.getProperty(P + "split", "").trim().toLowerCase(Locale.ROOT) : "";
    static final String[] HUNTS = ON ? names(System.getProperty(P + "hunt")) : new String[0];
    static final boolean GROW = ON && Boolean.getBoolean(P + "grow");
    static final int[] FILL = ON ? fill(System.getProperty(P + "fill")) : null;
    static final int[] AT = ON ? tile(System.getProperty(P + "at")) : null;
    /** {TASKS_NAME, amount} of the pre-held Slayer task, null = none (flag off, refused, or not "<monster>:<n>"). */
    static final String[] TASK = ON ? task(System.getProperty(P + "task")) : null;
    /** w9 scen2: the combat level to reach (0 = leave levels alone), the quests to mark done, the master a held task is from. */
    static final int COMBAT = ON ? combatTarget(System.getProperty(P + "combat")) : 0;
    static final String[] QUESTS = ON ? quests(System.getProperty(P + "quest")) : new String[0];
    static final String MASTER = ON ? master(System.getProperty(P + "master")) : "TURAEL";
    static final java.util.Set<String> TASKED = ConcurrentHashMap.newKeySet();
    /** Per bot: the NPC it last attacked and the kills counted this trip (an earlier target found dead when the next attack starts, or at trip end). */
    static final Map<String, core.game.node.entity.npc.NPC> TARGET = new ConcurrentHashMap<>();
    static final Map<String, Integer> KILLS = new ConcurrentHashMap<>();
    static final Map<String, Integer> INDEX = new ConcurrentHashMap<>();
    static final AtomicInteger NEXT = new AtomicInteger();
    static volatile int seeded;
    static final int COIN_ID = 995;

    static {
        if (ASKED && !ALLOWED) System.out.println("[Seed] refused (live)");
        else if (ON) System.out.println("[Seed] on: summoning=" + SUMMONING + " pouches=" + POUCHES.length + " farming=" + FARMING + " seeds=" + SEEDS.length
                + " slayer=" + SLAYER + " coins=" + COINS + " split=" + (SPLIT.isEmpty() ? "none" : SPLIT) + " hunt=" + String.join("/", HUNTS) + " grow=" + GROW + " fill=" + (FILL == null ? "none" : FILL[0] + ":" + FILL[1]) + " at=" + (AT == null ? "none" : AT[0] + "," + AT[1] + "," + AT[2]) + " task=" + (TASK == null ? "none" : TASK[0] + ":" + TASK[1])
                + " magic=" + MAGIC + " job=" + (JOB.isEmpty() ? "none" : JOB) + " rcstick=" + RC_STICK + " scen=" + String.join(",", SCEN) + " ticks=" + TICKS
                + " combat=" + COMBAT + " quest=" + (QUESTS.length == 0 ? "none" : String.join(",", QUESTS)) + " master=" + MASTER);
    }

    // ---- pure helpers (TestSeed)

    /** Is any seed flag set (a blank value counts as not set). */
    static boolean asked(java.util.function.Function<String, String> prop) {
        for (String k : KEYS) { String v = prop.apply(k); if (v != null && !v.trim().isEmpty()) return true; }
        return false;
    }

    /** Farm world = harness flag + the harness's own port flag + a 127.0.0.1-only listener. Anything else is treated as live. */
    static boolean allowed(boolean tickprobe, String farmPort, String bind) {
        return tickprobe && farmPort != null && !farmPort.trim().isEmpty() && "127.0.0.1".equals(bind);
    }

    /** "id:n,id:n" -> {{id, n}, ...}; a bare id means 1; bad or non-positive entries are dropped. */
    static int[][] pairs(String s) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (s != null) for (String part : s.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length == 0) continue; // ":" alone splits to nothing
            long id = num(kv[0], -1), n = kv.length > 1 ? num(kv[1], -1) : 1;
            if (kv.length <= 2 && id > 0 && n > 0 && id < 65536 && n <= Integer.MAX_VALUE) out.add(new int[] {(int) id, (int) n});
        }
        return out.toArray(new int[0][]);
    }

    /** "id:free" -> {id, free slots to leave (0..27)}, null when not that. */
    static int[] fill(String s) {
        if (s == null) return null;
        String[] kv = s.trim().split(":");
        if (kv.length != 2) return null;
        long id = num(kv[0], -1), free = num(kv[1], -1);
        return id > 0 && id < 65536 && free >= 0 && free <= 27 ? new int[] {(int) id, (int) free} : null;
    }

    /** "<monster>:<n>" -> {NAME, n}: the name trimmed, upper case, spaces as "_" (letters and "_" only: a Tasks constant such as
     *  BEARS or CAVE_BUGS), n 1..255 and 10 when left out; null when not that. The name is matched to a task on the world side. */
    static String[] task(String s) {
        if (s == null) return null;
        String[] kv = s.trim().split(":", -1);
        if (kv.length < 1 || kv.length > 2) return null;
        String name = kv[0].trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (name.isEmpty() || name.length() > 40 || !name.matches("[A-Z_]+")) return null;
        long n = kv.length == 2 ? num(kv[1], -1) : 10;
        return n >= 1 && n <= 255 ? new String[] {name, Long.toString(n)} : null;
    }

    /** The task constant for a parsed name: exact, or the plural / singular of it (COW -> COWS, DOGS -> DOG); null = no such task. */
    static <E extends Enum<E>> E taskFor(Class<E> tasks, String name) {
        if (name == null || name.isEmpty()) return null;
        String[] tries = {name, name + "S", name.endsWith("S") ? name.substring(0, name.length() - 1) : name};
        for (String t : tries) for (E e : tasks.getEnumConstants()) if (e.name().equals(t)) return e;
        return null;
    }

    /** Does the forced hunt apply: not to a task holder when the scenario seeds Slayer. */
    static boolean huntApplies(boolean slayerSeeded, boolean hasTask) { return !(slayerSeeded && hasTask); }

    /** "a, b" -> {"a", "b"} (blank entries dropped; W8: "_" reads as a space, so "Ice_giant" needs no shell quoting). */
    static String[] names(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s != null) for (String part : s.split(",")) if (!part.trim().isEmpty()) out.add(part.trim().replace('_', ' '));
        return out.toArray(new String[0]);
    }

    /** The hunt for a bot index: pairs (0,1), (2,3), ... share one name, so an ON bot and an OFF bot always go to the same spot. */
    static String huntAt(String[] hunts, int index) { return hunts == null || hunts.length == 0 || index < 0 ? null : hunts[(index / 2) % hunts.length]; }

    /** A bot name as one log word. */
    static String word(String name) { return name == null ? "?" : name.trim().replace(' ', '_'); }

    /** "x,y,z" (z optional) -> {x, y, z}, null when not a tile. */
    static int[] tile(String s) {
        if (s == null) return null;
        String[] a = s.trim().split(",");
        if (a.length < 2 || a.length > 3) return null;
        long x = num(a[0], -1), y = num(a[1], -1), z = a.length == 3 ? num(a[2], -1) : 0;
        return x > 0 && y > 0 && x < 16384 && y < 16384 && z >= 0 && z <= 3 ? new int[] {(int) x, (int) y, (int) z} : null;
    }

    static long num(String s, long dflt) {
        try { return s == null || s.trim().isEmpty() ? dflt : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    /** A level flag clamped to 1..99, 0 when not set / not a number. */
    static int level(String s) { long v = num(s, 0); return v <= 0 ? 0 : (int) Math.min(99, v); }

    /** w9 scen2: a combat level flag: 0 when not set / not a number / under 4, else clamped to 126. */
    static int combatTarget(String s) { long v = num(s, 0); return v < 4 ? 0 : (int) Math.min(126, v); }

    /** The engine's combat level (Skills.calculateCombatLevel, read from server.jar): 0.25 x (Defence + Hitpoints + Prayer/2) plus the best
     *  of 0.325 x (Attack + Strength), 0.325 x 3 x Ranged/2, 0.325 x 3 x Magic/2 (halves floored), truncated. */
    static int combatOf(int att, int str, int def, int hp, int pray, int rng, int mag) {
        double base = 0.25 * (def + hp + Math.floor(pray * 0.5));
        double melee = 0.325 * (att + str), range = 0.325 * (Math.floor(rng / 2.0) * 3.0), magic = 0.325 * (Math.floor(mag / 2.0) * 3.0);
        return (int) (base + Math.max(melee, Math.max(range, magic)));
    }

    /** The least level L (1..99) that, with Attack/Strength/Defence/Hitpoints raised to at least L (never lowered), makes combat >= target.
     *  lv = {attack, strength, defence, hitpoints, prayer, ranged, magic} as the bot has them. 0 = nothing to raise (target 0, or already there). */
    static int meleeFor(int target, int[] lv) {
        if (target <= 0 || lv == null || lv.length < 7) return 0;
        if (combatOf(lv[0], lv[1], lv[2], lv[3], lv[4], lv[5], lv[6]) >= target) return 0;
        for (int l = 1; l <= 99; l++)
            if (combatOf(Math.max(lv[0], l), Math.max(lv[1], l), Math.max(lv[2], l), Math.max(lv[3], l), lv[4], lv[5], lv[6]) >= target) return l;
        return 99;
    }

    /** w9 scen2: Defence a combat seed raises first, so SmartAdventurers.Brain.effectiveCombat (min(combat, Defence + 25)) reaches the target too. */
    static int defenceFloor(int target) { return target <= 0 ? 0 : Math.max(0, Math.min(99, target - 25)); }

    /** w9 scen2: "a, Priest in peril" ->{"A", "PRIEST_IN_PERIL"}: upper case, spaces as "_", letters/digits/"_" only (a Quests enum name),
     *  40 characters at most, once each; anything else dropped. Whether the name is a quest is decided on the world side. */
    static String[] quests(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s != null) for (String part : s.split(",")) {
            String q = part.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (!q.isEmpty() && q.length() <= 40 && q.matches("[A-Z][A-Z0-9_]*") && !out.contains(q)) out.add(q);
        }
        return out.toArray(new String[0]);
    }

    /** w9 scen2: the master for a held task: "vannaka" (any case) -> VANNAKA, anything else (blank, junk, turael) -> TURAEL. */
    static String master(String s) { return s != null && "VANNAKA".equals(s.trim().toUpperCase(Locale.ROOT)) ? "VANNAKA" : "TURAEL"; }

    /** The OFF half of a split: odd creation index, and only for the feature named by the flag. */
    static boolean offHalf(String split, String feature, int index) {
        return split != null && !split.isEmpty() && split.equals(feature) && index >= 0 && (index & 1) == 1;
    }

    static String halfName(String split, int index) { return split == null || split.isEmpty() || index < 0 ? "-" : (index & 1) == 1 ? "OFF" : "ON"; }

    /** "skill:lvl,.." -> {{id, lvl}, ...}: names are the int constants of skills (case-insensitive); lvl clamps like level(); an entry without a good level is dropped; an unknown name prints one line and is skipped. */
    static int[][] skillLevels(Class<?> skills, String s) {
        if (s == null) return new int[0][];
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (String part : s.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2 || kv[0].trim().isEmpty()) continue;
            int lv = level(kv[1]);
            if (lv <= 0) continue;
            int id = skillId(skills, kv[0]);
            if (id < 0) { System.out.println("[Seed] levels: unknown skill " + kv[0].trim()); continue; }
            out.add(new int[]{id, lv});
        }
        return out.toArray(new int[0][]);
    }

    /** The value of the public static int constant with this name (case-insensitive; counts like NUM_SKILLS are not skills), -1 when there is none. */
    static int skillId(Class<?> skills, String name) {
        if (name == null) return -1;
        String n = name.trim().toUpperCase(Locale.ROOT);
        if (n.isEmpty() || n.startsWith("NUM_")) return -1;
        try {
            java.lang.reflect.Field f = skills.getField(n);
            int m = f.getModifiers();
            if (f.getType() != int.class || !java.lang.reflect.Modifier.isStatic(m) || !java.lang.reflect.Modifier.isFinal(m)) return -1;
            int v = f.getInt(null);
            return v >= 0 ? v : -1;
        } catch (Throwable t) { return -1; }
    }

    private static int lvl(String k) { return ON ? level(System.getProperty(P + k)) : 0; }

    // ---- world side

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    /** A bot made by another path than enlist (a restore) is seeded the first time anything asks about it. */
    static int indexOf(Player p) {
        if (p == null || !p.isArtificial()) return -1;
        Integer i = INDEX.get(key(p));
        if (i == null && ON) { apply(p); i = INDEX.get(key(p)); }
        return i == null ? -1 : i;
    }

    /** SmartAdventurers.chooseJob: the hunt this bot is sent to, null = choose as normal. */
    static String hunt(Player p, boolean hasTask) { return ON && HUNTS.length > 0 && huntApplies(SLAYER > 0 || TASK != null, hasTask) ? huntAt(HUNTS, indexOf(p)) : null; }

    /** W8 herbSupply: SmartAdventurers.chooseJob: the non-hunt job (an AfkTasks.Task name) this bot is sent to, null = choose as normal. */
    static String job(Player p) { return ON && !JOB.isEmpty() && p != null && p.isArtificial() ? JOB : null; }

    /** Is this bot in the OFF half for the feature (false when no split, flag off, or not a seeded bot). */
    static boolean off(Player p, String feature) { return ON && !SPLIT.isEmpty() && offHalf(SPLIT, feature, indexOf(p)); }

    static String half(Player p) { return halfName(SPLIT, indexOf(p)); }

    /** SmartAdventurers.enlist: once per bot name per boot. */
    static void apply(Player p) {
        if (!ON || p == null || !p.isArtificial()) return;
        String k = key(p);
        if (INDEX.containsKey(k)) return;
        int idx = NEXT.getAndIncrement(); INDEX.put(k, idx);
        try {
            if (SUMMONING > 0 || POUCHES.length > 0) {
                try {
                    core.game.node.entity.player.link.quest.Quest q = p.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE);
                    if (q != null && !q.isCompleted(p)) p.getQuestRepository().setStage(q, 100);
                } catch (Throwable ignored) { }
            }
            raise(p, Skills.SUMMONING, SUMMONING); raise(p, Skills.FARMING, FARMING); raise(p, Skills.SLAYER, SLAYER); raise(p, Skills.MAGIC, MAGIC);
            for (int[] sl : LEVELS) raise(p, sl[0], sl[1]);
            if (COMBAT > 0) { // w9 scen2: vannakaHunts needs combat 84+ (the fleet's Vannaka bots were 48-78)
                core.game.node.entity.skill.Skills sk = p.getSkills();
                raise(p, Skills.DEFENCE, defenceFloor(COMBAT)); // the Brain's effectiveCombat is min(combat, Defence + 25)
                int l = meleeFor(COMBAT,new int[]{sk.getStaticLevel(Skills.ATTACK), sk.getStaticLevel(Skills.STRENGTH), sk.getStaticLevel(Skills.DEFENCE), sk.getStaticLevel(Skills.HITPOINTS),
                        sk.getStaticLevel(Skills.PRAYER), sk.getStaticLevel(Skills.RANGE), sk.getStaticLevel(Skills.MAGIC)});
                raise(p, Skills.ATTACK, l); raise(p, Skills.STRENGTH, l); raise(p, Skills.DEFENCE, l); raise(p, Skills.HITPOINTS, l);
                try { sk.updateCombatLevel(); } catch (Throwable ignored) { }
            }
            for (String qn : QUESTS) { // w9 scen2: quest done (when the server has it) and in the Gates history (when the bot has one), so the gate opens either way
                try {
                    content.data.Quests q = content.data.Quests.valueOf(qn);
                    core.game.node.entity.player.link.quest.Quest quest = Gates.implemented(p, q) ? p.getQuestRepository().getQuest(q) : null;
                    if (quest != null && !quest.isCompleted(p)) p.getQuestRepository().setStage(quest, 100);
                    java.util.Set<String> hist = Gates.SEEDED.get(key(p));
                    if (hist != null) hist.add(q.name());
                } catch (Throwable t) { System.out.println("[Seed] quest " + qn + " " + word(p.getUsername()) + ": " + t); }
            }
            for (int[] it : POUCHES) top(p, it[0], it[1]);
            for (int[] it : SEEDS) top(p, it[0], it[1]);
            if (COINS >= 0) {
                int inv = p.getInventory().getAmount(COIN_ID), bank = p.getBank().getAmount(COIN_ID);
                if (inv > 0) p.getInventory().remove(new Item(COIN_ID, inv));
                if (bank > 0) p.getBank().remove(new Item(COIN_ID, bank));
                if (COINS > 0) p.getBank().add(new Item(COIN_ID, (int) Math.min(Integer.MAX_VALUE, COINS)));
            }
            if (AT != null) p.getProperties().setTeleportLocation(core.game.world.map.Location.create(AT[0], AT[1], AT[2]));
            seeded++;
            if (SCEN.length > 0) Scen.start(SCEN, TICKS); // W8: the judge starts with the first seeded bot
            System.out.println("[Seed] " + word(p.getUsername()) + " #" + idx + " half=" + halfName(SPLIT, idx) + " summoning=" + p.getSkills().getStaticLevel(Skills.SUMMONING)
                    + " farming=" + p.getSkills().getStaticLevel(Skills.FARMING) + " slayer=" + p.getSkills().getStaticLevel(Skills.SLAYER)
                    + (COMBAT > 0 ? " combat=" + p.getProperties().getCurrentCombatLevel() : "") + (QUESTS.length > 0 ? " quests=" + QUESTS.length : "")
                    + " coins=" + (p.getBank().getAmount(COIN_ID) + (long) p.getInventory().getAmount(COIN_ID)));
        } catch (Throwable t) { System.out.println("[Seed] " + p.getUsername() + ": " + t); }
    }

    /** SmartAdventurers.enlist, once the Brain exists: with .seed.task the bot holds the task (Slayer.hold = the state an errand leaves). Once per bot name per boot. */
    static void task(SmartAdventurers.Brain b, Player p) {
        if (!ON || TASK == null || b == null || p == null || !p.isArtificial()) return;
        if (!TASKED.add(key(p))) return;
        try {
            content.global.skill.slayer.Tasks t = taskFor(content.global.skill.slayer.Tasks.class, TASK[0]);
            content.global.skill.slayer.Master from = "VANNAKA".equals(MASTER) ? content.global.skill.slayer.Master.VANNAKA : content.global.skill.slayer.Master.TURAEL; // w9 scen2: .seed.master
            boolean held = t != null && Slayer.hold(b, p, t, Integer.parseInt(TASK[1]), System.currentTimeMillis(), from);
            System.out.println("[Seed] " + word(p.getUsername()) + " task=" + TASK[0] + ":" + TASK[1] + " from=" + MASTER + (held ? " held" : t == null ? " refused (no such Slayer task)" : " refused (bots.slayer off, or the server did not keep the task)"));
        } catch (Throwable t) { System.out.println("[Seed] task " + p.getUsername() + ": " + t); }
    }

    private static void raise(Player p, int skill, int lvl) {
        if (lvl <= 0) return;
        if (p.getSkills().getStaticLevel(skill) < lvl) p.getSkills().setStaticLevel(skill, lvl);
        p.getSkills().setLevel(skill, p.getSkills().getStaticLevel(skill));
    }

    private static void top(Player p, int id, int n) {
        int have = p.getBank().getAmount(id) + p.getInventory().getAmount(id);
        if (have < n) p.getBank().add(new Item(id, n - have));
    }

    /** Scenario log lines (seed flags on only): one per fact the runner counts. */
    static void note(Player p, String what) {
        if (ON && p != null && p.isArtificial()) System.out.println("[Scen] " + word(p.getUsername()) + " half=" + half(p) + " " + what);
    }

    /** AfkTasks trip end: kills made on the trip, for the per-half kill rate. */
    static void trip(Player p, int kills, long startedAt, String job) {
        if (!ON || p == null || !p.isArtificial()) return;
        String k = key(p);
        core.game.node.entity.npc.NPC was = TARGET.remove(k);
        if (dead(was)) KILLS.merge(k, 1, Integer::sum);
        Integer kc = KILLS.remove(k);
        if (kills <= 0) return;
        System.out.println("[Scen] " + word(p.getUsername()) + " half=" + half(p) + " trip kills=" + (kc == null ? 0 : kc) + " attacks=" + kills + " min=" + (System.currentTimeMillis() - startedAt) / 60_000 + " job=" + job);
    }

    /** AfkTasks fight tick, as a new attack starts: the target before this one counts as a kill when it is dead or gone. */
    static void attack(Player p, core.game.node.entity.npc.NPC n) {
        if (!ON || p == null || n == null || !p.isArtificial()) return;
        String k = key(p);
        core.game.node.entity.npc.NPC was = TARGET.put(k, n);
        if (was != null && was != n && dead(was)) KILLS.merge(k, 1, Integer::sum);
    }

    static boolean dead(core.game.node.entity.npc.NPC n) {
        try { return n != null && (!n.isActive() || core.game.node.entity.combat.DeathTask.isDead(n) || n.getSkills().getLifepoints() <= 0); } catch (Throwable t) { return false; }
    }

    /** SmartAdventurers.farmFinish: with .seed.grow every planted patch at the site is forced grown (as TestFarming U4) and the CHECK is due in a minute. */
    static long grown(Player p, int site, long due, long now) {
        if (!ON || !GROW || p == null || !p.isArtificial() || due == 0) return due;
        int forced = 0;
        try {
            for (core.game.node.scenery.Scenery s : Farming.find(site)) {
                content.global.skill.farming.Patch pt = content.global.skill.farming.FarmingPatch.forObject(s).getPatchFor(p, true);
                if (pt.getPlantable() == null) continue;
                for (int k = 0; k < 8 && !pt.isGrown(); k++) { pt.setWatered(true); pt.setNextGrowth(0); pt.update(); }
                if (pt.isGrown()) forced++;
            }
        } catch (Throwable t) { System.out.println("[Seed] grow " + p.getUsername() + ": " + t); }
        note(p, "farm grow forced=" + forced + " site=" + Farming.SITES[site].key);
        return forced > 0 ? now + 60_000L : due;
    }

    /** SmartAdventurers farm run, as a CHECK reaches the patches: with .seed.fill the pack is filled down to the asked free slots. */
    static void fillPack(Player p, boolean check) {
        if (!ON || FILL == null || !check || p == null || !p.isArtificial()) return;
        int n = 0;
        try { while (p.getInventory().freeSlots() > FILL[1] && p.getInventory().add(new Item(FILL[0], 1))) n++; } catch (Throwable t) { System.out.println("[Seed] fill " + p.getUsername() + ": " + t); }
        note(p, "farm fill added=" + n + " free=" + p.getInventory().freeSlots());
    }
}
