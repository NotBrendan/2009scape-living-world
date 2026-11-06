package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.cache.def.impl.SceneryDefinition;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Gates: "you need X to be here" (2026-10-21: "capture those tunnels and quest based logic areas ... let the bots
 * know when stats or quest area is in play"). design: the design notes (not published).
 *
 * A gate is a place (a box of tiles) and a rule built from all/any/leaf: quest done, skill level, quest points, item
 * carried/worn/banked, coins, Attack+Strength. Gates come from a curated 2009 table, and shortcut objects come from the
 * server's own AgilityShortcut handlers (exact Agility levels) plus a scan of every region the surveyors have walked.
 * Tunnel surveyors (Explorers) test the shortcuts; doors that stay shut for a door surveyor are matched to gates.
 * Saved in data/survey/gates.json.
 */
public final class Gates implements StartupListener {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("gates", "true"));

    // ------------------------------------------------------------ rules

    abstract static class Rule {
        abstract boolean ok(Player p);
        abstract String need();
    }

    static Rule quest(String enumName, String label) {
        content.data.Quests q = null;
        try { q = content.data.Quests.valueOf(enumName); } catch (Throwable ignored) { }
        final content.data.Quests fq = q;
        return new Rule() {
            boolean ok(Player p) {
                if (fq == null) return true;
                Set<String> seeded = SEEDED.get(p.getUsername().toLowerCase(Locale.ROOT));
                if (seeded != null && seeded.contains(fq.name())) return true;
                if (!implemented(p, fq)) return seeded == null; // the server has no such quest: players get in as the server allows; bots go by their history
                try { return p.getQuestRepository().isComplete(fq); } catch (Throwable t) { return false; }
            }
            String need() { return label; }
        };
    }

    static Rule skill(int sk, int lvl, String label) {
        return new Rule() {
            boolean ok(Player p) { return p.getSkills().getStaticLevel(sk) >= lvl; }
            String need() { return label + " " + lvl; }
        };
    }

    static Rule qp(int n) {
        return new Rule() {
            boolean ok(Player p) { try { return p.getQuestRepository().getPoints() >= n || seededPoints(p) >= n; } catch (Throwable t) { return false; } }
            String need() { return n + " quest points"; }
        };
    }

    static Rule item(int id, String label) {
        return new Rule() {
            boolean ok(Player p) { return p.getInventory().containsItem(new Item(id)) || p.getEquipment().containsItem(new Item(id)) || p.getBank().containsItem(new Item(id)); }
            String need() { return label; }
        };
    }

    static Rule coins(int n) {
        return new Rule() {
            boolean ok(Player p) { return !p.isArtificial() || Ledger.Wallet.coins(p) >= n; }
            String need() { return n + " gp toll"; }
        };
    }

    /** combat review 10-23 #9: full or part-full waterskins (4, 3, 2, 1 sips); the desert heat drinks from the pack only. */
    static final int[] WATERSKINS = {1823, 1825, 1827, 1829};
    static final int WATERSKIN_EMPTY = 1831;
    static boolean carriesWater(Player p) {
        for (int id : WATERSKINS) if (p.getInventory().getAmount(id) > 0) return true;
        return false;
    }
    /** In the heat past the Shantay Pass (the shantay-desert gate's box). */
    static boolean desert(Location l) {
        for (Gate gt : at(l)) if (gt.id.equals("shantay-desert")) return true;
        return false;
    }
    /** combat review 10-23 #9: a bot goes into the desert with water in its pack, or with the coins to buy some before the trip (SmartAdventurers.Brain.packWater). */
    static Rule water() {
        return new Rule() {
            boolean ok(Player p) {
                if (!p.isArtificial() || carriesWater(p)) return true;
                return Ledger.Wallet.coins(p) >= 5 + 4L * Math.max(1, MarketBrain.value(WATERSKINS[0]));
            }
            String need() { return "a waterskin"; }
        };
    }

    static Rule attStr(int sum) {
        return new Rule() {
            boolean ok(Player p) { int a = p.getSkills().getStaticLevel(Skills.ATTACK), s = p.getSkills().getStaticLevel(Skills.STRENGTH); return a + s >= sum || a >= 99 || s >= 99; }
            String need() { return "Attack + Strength " + sum + " (or 99 in one)"; }
        };
    }

    static Rule any(Rule... rs) {
        return new Rule() {
            boolean ok(Player p) { for (Rule r : rs) if (r.ok(p)) return true; return false; }
            String need() { StringBuilder b = new StringBuilder(); for (Rule r : rs) { if (b.length() > 0) b.append(" or "); b.append(r.need()); } return b.toString(); }
        };
    }

    static Rule all(Rule... rs) {
        return new Rule() {
            boolean ok(Player p) { for (Rule r : rs) if (!r.ok(p)) return false; return true; }
            String need() { StringBuilder b = new StringBuilder(); for (Rule r : rs) { if (b.length() > 0) b.append(" and "); b.append(r.need()); } return b.toString(); }
        };
    }

    // ------------------------------------------------------------ gates

    static final class Gate {
        final String id, name, kind; final int x0, y0, x1, y1; final Rule rule;
        boolean confirmed; // a door surveyor found a door here that stayed shut
        Gate(String id, String name, String kind, int x0, int y0, int x1, int y1, Rule rule) { this.id = id; this.name = name; this.kind = kind; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.rule = rule; }
        int[][] notches = new int[0][]; // batch 21 B1b: tiles of the box outside the area's walls (x0, y0, x1, y1 each)
        Gate notch(int nx0, int ny0, int nx1, int ny1) { walled = true; notches = Arrays.copyOf(notches, notches.length + 1); notches[notches.length - 1] = new int[]{nx0, ny0, nx1, ny1}; return this; }
        /** review fix 3: the box (less its notches) was checked tile by tile against a clipping flood fill (TestB21G BOX): only such a box can strand a bot (Preflight.stranded). notch() sets it; a box that matched with no notch calls walled(). */
        boolean walled;
        Gate walled() { walled = true; return this; }
        boolean contains(Location l) {
            int x = l.getX(), y = l.getY();
            if (x < x0 || x > x1 || y < y0 || y > y1) return false;
            for (int[] n : notches) if (x >= n[0] && x <= n[2] && y >= n[1] && y <= n[3]) return false;
            return true;
        }
        Location centre() { return Location.create((x0 + x1) / 2, (y0 + y1) / 2, 0); }
    }

    static final List<Gate> GATES = new ArrayList<>();
    static final Map<Integer, List<Gate>> BY_CELL = new HashMap<>();

    static Gate g(String id, String name, String kind, int x0, int y0, int x1, int y1, Rule r) { Gate gt = new Gate(id, name, kind, x0, y0, x1, y1, r); GATES.add(gt); return gt; }

    /** The 2009 table. Boxes are approximate; the door survey confirms them (see `confirmed`). */
    static {
        g("morytania", "Morytania (east of the Salve)", "quest", 3400, 3150, 3910, 3600, quest("PRIEST_IN_PERIL", "Priest in Peril")); // from the river: the Mort Myre snails at 3409-3423 were outside the box (audit 10-22 09:50)
        g("west-ardougne", "West Ardougne", "quest", 2440, 3265, 2556, 3336, quest("PLAGUE_CITY", "Plague City"));
        g("shilo", "Shilo Village", "quest", 2817, 2944, 2880, 3005, quest("SHILO_VILLAGE", "Shilo Village"));
        g("kharazi", "the Kharazi Jungle", "quest", 2760, 2880, 2960, 2940, quest("LEGENDS_QUEST", "Legends' Quest"));
        g("tirannwn", "Tirannwn (Isafdar and Lletya)", "quest", 2150, 3090, 2380, 3340, quest("REGICIDE", "Regicide"));
        g("ape-atoll", "Ape Atoll", "quest", 2690, 2690, 2820, 2810, quest("MONKEY_MADNESS", "Monkey Madness"));
        g("fremennik-isles", "Neitiznot and Jatizso", "quest", 2300, 3770, 2440, 3900, quest("THE_FREMENNIK_TRIALS", "The Fremennik Trials"));
        g("miscellania", "Miscellania and Etceteria", "quest", 2490, 3840, 2640, 3900, quest("THE_FREMENNIK_TRIALS", "The Fremennik Trials"));
        g("lunar-isle", "Lunar Isle", "quest", 2050, 3840, 2200, 3960, quest("LUNAR_DIPLOMACY", "Lunar Diplomacy"));
        g("trollheim", "Trollheim and the Troll Stronghold", "quest", 2820, 3610, 2940, 3730, quest("DEATH_PLATEAU", "Death Plateau"));
        g("sophanem", "Sophanem and Menaphos", "quest", 3200, 2740, 3330, 2810, quest("ICTHLARINS_LITTLE_HELPER", "Icthlarin's Little Helper"));
        g("mos-le-harmless", "Mos Le'Harmless", "quest", 3650, 2930, 3860, 3070, quest("CABIN_FEVER", "Cabin Fever"));
        g("zanaris", "Zanaris", "quest", 2370, 4410, 2430, 4480, quest("LOST_CITY", "Lost City"));
        g("heroes-guild", "the Heroes' Guild", "quest", 2890, 3506, 2903, 3516, quest("HEROES_QUEST", "Heroes' Quest"));
        g("legends-guild", "the Legends' Guild", "quest", 2723, 3349, 2740, 3385, quest("LEGENDS_QUEST", "Legends' Quest"));
        g("champions-guild", "the Champions' Guild", "quest", 3188, 3354, 3195, 3363, qp(32));
        g("fishing-guild", "the Fishing Guild", "skill", 2584, 3394, 2620, 3426, skill(Skills.FISHING, 68, "Fishing"));
        g("mining-guild", "the Mining Guild", "skill", 3016, 9733, 3055, 9760, skill(Skills.MINING, 60, "Mining"));
        g("ranging-guild", "the Ranging Guild", "skill", 2650, 3415, 2683, 3446, skill(Skills.RANGE, 40, "Ranged"));
        g("wizards-guild", "the Wizards' Guild", "skill", 2584, 3080, 2596, 3095, skill(Skills.MAGIC, 66, "Magic"));
        g("cooks-guild", "the Cooks' Guild", "skill", 3138, 3440, 3148, 3449, all(skill(Skills.COOKING, 32, "Cooking"), item(1949, "a chef's hat")));
        g("crafting-guild", "the Crafting Guild", "skill", 2929, 3278, 2943, 3292, all(skill(Skills.CRAFTING, 40, "Crafting"), item(1757, "a brown apron")));
        g("warriors-guild", "the Warriors' Guild", "skill", 2838, 3533, 2876, 3555, attStr(130)) // batch 21 B1b: 36 box tiles a walker reaches from outside without a door (clipping flood fill from the walkway 2870,3556): the south-east yard and the east-wall alcoves
            .notch(2867, 3535, 2873, 3535).notch(2868, 3534, 2873, 3534).notch(2868, 3536, 2870, 3536).notch(2874, 3533, 2876, 3536).notch(2876, 3537, 2876, 3538)
            .notch(2876, 3542, 2876, 3543).notch(2876, 3549, 2876, 3550).notch(2876, 3554, 2876, 3555)
            .notch(2845, 3535, 2847, 3535).notch(2848, 3533, 2848, 3536).notch(2849, 3533, 2859, 3533).notch(2860, 3533, 2860, 3536).notch(2861, 3534, 2861, 3536).notch(2862, 3535, 2864, 3536) // review fix 3: 31 south-side tiles reached from outside the box's south edge, not from the walkway (TestB21G BOX, ring flood)
            ; // B1: x 2877 (the east door tile) and y 3556 (the walkway north of the guild) are outside its walls (clipping flags, TestB21G)
        g("edgeville-hill-giants", "the Edgeville dungeon hill giants", "key", 3096, 9824, 3125, 9852, item(983, "a brass key")); // batch 26 fix 4: behind the brass key door; bots climbed down and stalled at the landing 3096,9867 (33 rescues/24 h). TestB26: the 11 hill giants spawn at 3102-3119, 9829-9846
        g("taverley-blue-dragons", "the Taverley blue dragons", "key", 2890, 9785, 2945, 9815, any(item(1590, "a dusty key"), skill(Skills.AGILITY, 70, "Agility")));
        g("shantay-desert", "the desert past the Shantay Pass", "toll", 3140, 2830, 3520, 3114, all(coins(5), water())); // combat review 10-23 #9: and water for the heat
        for (Gate gt : GATES) {
            if ((gt.x1 - gt.x0) * (gt.y1 - gt.y0) > 600 * 600) continue;
            for (int cx = gt.x0 / RouteMap.CELL; cx <= gt.x1 / RouteMap.CELL; cx++) for (int cy = gt.y0 / RouteMap.CELL; cy <= gt.y1 / RouteMap.CELL; cy++)
                BY_CELL.computeIfAbsent((cx << 16) | cy, k -> new ArrayList<>()).add(gt);
        }
    }

    static List<Gate> at(Location l) {
        if (l == null) return Collections.emptyList();
        List<Gate> c = BY_CELL.get(RouteMap.cell(l.getX(), l.getY()));
        if (c == null) return Collections.emptyList();
        List<Gate> out = new ArrayList<>();
        for (Gate gt : c) if (gt.contains(l)) out.add(gt);
        return out;
    }

    /** May this player be at l? (Everyone may leave a gated area; this is about getting in.) */
    static boolean canEnter(Player p, Location l) {
        if (!ON || p == null || l == null) return true;
        for (Gate gt : at(l)) if (!gt.rule.ok(p)) return false;
        return true;
    }

    /** What's missing to be at l, e.g. "Priest in Peril", or null. */
    static String missing(Player p, Location l) {
        for (Gate gt : at(l)) if (p == null || !gt.rule.ok(p)) return gt.rule.need();
        return null;
    }

    /** Route planning: the cell has a gate this walker fails. Memoised per plan in {@code memo}. */
    static boolean cellBlocked(int cell, Player p, Map<Gate, Boolean> memo) {
        if (!ON || p == null) return false;
        List<Gate> c = BY_CELL.get(cell);
        if (c == null) return false;
        Location mid = Location.create(RouteMap.cx(cell) * RouteMap.CELL + 4, RouteMap.cy(cell) * RouteMap.CELL + 4, 0);
        for (Gate gt : c) {
            if (!gt.contains(mid)) continue; // the box only clips this cell
            Boolean ok = memo.get(gt);
            if (ok == null) { ok = gt.rule.ok(p); memo.put(gt, ok); }
            if (!ok) return true;
        }
        return false;
    }

    /** A gate by (part of) its name, for chat: "canifis" -> Morytania. */
    static Gate byName(String q) {
        if (q == null) return null;
        q = q.toLowerCase(Locale.ROOT).replaceAll("^(the|a) ", "").trim();
        if (q.length() < 3) return null;
        for (Gate gt : GATES) if (gt.name.toLowerCase(Locale.ROOT).contains(q) || gt.id.contains(q.replace(' ', '-'))) return gt;
        return null;
    }

    // ------------------------------------------------------------ quest history for smart bots

    /** {quest enum name, min combat, min total level, chance % for an ordinary bot}; the high tier always has the first five. */
    static final Object[][] SEED = {
            {"DRAGON_SLAYER", 70, 0, 40}, {"MONKEY_MADNESS", 75, 900, 35}, {"LOST_CITY", 40, 500, 55}, {"THE_FREMENNIK_TRIALS", 45, 600, 45}, {"PRIEST_IN_PERIL", 35, 0, 85},
            {"PRINCE_ALI_RESCUE", 0, 0, 80}, {"PLAGUE_CITY", 0, 250, 70}, {"DEATH_PLATEAU", 40, 0, 60}, {"SHILO_VILLAGE", 60, 700, 50}, {"REGICIDE", 70, 1100, 30},
            {"ICTHLARINS_LITTLE_HELPER", 40, 600, 35}, {"LUNAR_DIPLOMACY", 60, 1000, 25}, {"HEROES_QUEST", 70, 900, 45}, {"LEGENDS_QUEST", 85, 1300, 25}, {"CABIN_FEVER", 50, 900, 25},
            {"COOKS_ASSISTANT", 0, 0, 90}, {"RUNE_MYSTERIES", 0, 0, 85}, {"WATERFALL_QUEST", 25, 0, 75}, {"DRUIDIC_RITUAL", 0, 150, 70}, {"THE_RESTLESS_GHOST", 0, 0, 75},
    };
    static final Map<String, Integer> SEEDED_POINTS = new ConcurrentHashMap<>();
    static final Map<String, Set<String>> SEEDED = new ConcurrentHashMap<>();       // bot -> quests in its history (incl. ones the server doesn't have)
    static final Map<content.data.Quests, Boolean> IMPLEMENTED = new ConcurrentHashMap<>();

    /** Does the server have this quest? (Asking about a missing one logs an error every time.) */
    static boolean implemented(Player p, content.data.Quests q) {
        return IMPLEMENTED.computeIfAbsent(q, k -> { try { return p.getQuestRepository().getQuest(k) != null; } catch (Throwable t) { return false; } });
    }

    static int seededPoints(Player p) { return SEEDED_POINTS.getOrDefault(p.getUsername().toLowerCase(Locale.ROOT), 0); }

    /** A believable quest log from the bot's levels, the same every time (from its name). Returns how many are done. */
    static int seedQuests(Player bot, boolean high) {
        if (!ON || bot == null) return 0;
        int combat = bot.getProperties().getCurrentCombatLevel(), total = 0;
        for (int i = 0; i < 24; i++) total += bot.getSkills().getStaticLevel(i);
        int h = bot.getUsername().toLowerCase(Locale.ROOT).hashCode(), done = 0, points = 0;
        Set<String> history = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < SEED.length; i++) {
            Object[] s = SEED[i];
            boolean has = high && i < 5;
            if (!has && combat >= (Integer) s[1] && total >= (Integer) s[2]) has = Math.floorMod(h * 31 + i * 7919, 100) < (Integer) s[3];
            if (!has) continue;
            history.add((String) s[0]);
            try {
                content.data.Quests q = content.data.Quests.valueOf((String) s[0]);
                if (QuestBook.skipSeed(bot, q)) continue; // a scripted quest the bot does for real (QuestBook)
                if (!implemented(bot, q)) { done++; continue; }
                core.game.node.entity.player.link.quest.Quest quest = bot.getQuestRepository().getQuest(q);
                if (quest != null && !quest.isCompleted(bot)) bot.getQuestRepository().setStage(quest, 100);
                if (quest != null) points += quest.getQuestPoints();
                done++;
            } catch (Throwable ignored) { }
        }
        SEEDED_POINTS.put(bot.getUsername().toLowerCase(Locale.ROOT), points);
        SEEDED.put(bot.getUsername().toLowerCase(Locale.ROOT), history);
        // a high-level fighter who has been to the blue dragons kept the dusty key, like a player would
        if (high && !bot.getBank().containsItem(new Item(1590)) && !bot.getInventory().containsItem(new Item(1590))) try { bot.getBank().add(new Item(1590)); } catch (Throwable ignored) { }
        return done;
    }

    /** Every quest a gate rule asks for, done; the guild items and the dusty key in the bank: for the Agility-99 tunnel surveyors only (data collectors), so the shortcuts behind quest gates get tested. */
    static int seedAll(Player bot) {
        if (bot == null) return 0;
        int done = 0;
        for (String name : new String[]{"PRIEST_IN_PERIL", "PLAGUE_CITY", "SHILO_VILLAGE", "LEGENDS_QUEST", "REGICIDE", "MONKEY_MADNESS", "THE_FREMENNIK_TRIALS", "LUNAR_DIPLOMACY", "DEATH_PLATEAU", "ICTHLARINS_LITTLE_HELPER", "CABIN_FEVER", "LOST_CITY", "HEROES_QUEST", "BIOHAZARD", "UNDERGROUND_PASS", "JUNGLE_POTION", "NATURE_SPIRIT", "THE_GRAND_TREE", "TREE_GNOME_VILLAGE", "WATERFALL_QUEST", "FIGHT_ARENA", "DWARF_CANNON", "RUNE_MYSTERIES", "DRUIDIC_RITUAL"}) {
            try {
                content.data.Quests q = content.data.Quests.valueOf(name);
                if (!implemented(bot, q)) continue;
                core.game.node.entity.player.link.quest.Quest quest = bot.getQuestRepository().getQuest(q);
                if (quest != null && !quest.isCompleted(bot)) bot.getQuestRepository().setStage(quest, 100);
                if (quest != null) done++;
            } catch (Throwable ignored) { }
        }
        SEEDED_POINTS.put(bot.getUsername().toLowerCase(Locale.ROOT), 300);
        for (int it : new int[]{1590, 1949, 1757}) try { if (!bot.getBank().containsItem(new Item(it))) bot.getBank().add(new Item(it)); } catch (Throwable ignored) { } // dusty key, chef's hat, brown apron
        return done;
    }

    // ------------------------------------------------------------ shortcuts (tunnels, pipes, walls, stepping stones...)

    static final class Shortcut {
        int id, x, y, z, level = -1; String name, option, src;
        int lowOk, lowFail, highOk, highFail; int[] to; long tested;
        long lowT, highT; String lowStamp, highStamp, gate; // batch 18 item1: when each band last tested it, the game data/build stamp its result was recorded under, the gate learned ("Agility 26", "agility or quest", "items")
        String key() { return x + "," + y + "," + z; }
        Location at() { return Location.create(x, y, z); }
        /** The Agility a bot needs: the server's own level, else what the tests showed. */
        int needs() { if (level >= 0) return level; if (lowOk > 0) return 1; if (highOk > 0) return 70; return 1; }
        /** batch 18 item1: the level the engine asks for (AgilityShortcut handler, else the listener shortcuts' known levels), or -1. */
        int engineLevel() { if (level >= 0) return level; Integer l = LISTENER_LEVEL.get(id); return l != null ? l : -1; }
        /** batch 18 item1: needs items no seeker carries (a crossbow and a mithril grapple). */
        boolean itemGated() { return (option != null && option.toLowerCase(Locale.ROOT).contains("grapple")) || (name != null && name.toLowerCase(Locale.ROOT).contains("grapple")); }
        /** batch 18 item1: this band's result is settled (crossed; failed from SETTLE_SIDES sides; or failed where the other band crossed: a learned gate) under today's game data/build stamp. */
        boolean settled(boolean high) {
            int ok = high ? highOk : lowOk, fail = high ? highFail : lowFail;
            String st = high ? highStamp : lowStamp;
            if (ok + fail == 0 || st == null || !st.equals(stamp(this))) return false;
            if (ok > 0 || fail >= SETTLE_SIDES) return true;
            return !high && highOk > 0;
        }
    }
    static final int SETTLE_SIDES = 3; // batch 18 item1: failed from three sides for a band = settled for it (2509,3253 had 74 "nothing happened" records over many boots)
    /** batch 18 item1: shortcuts the server runs from InteractionListeners (not AgilityShortcut): levels from their handlers (javap) and 2009 data. */
    static final Map<Integer, Integer> LISTENER_LEVEL = new HashMap<>();
    static { LISTENER_LEVEL.put(9311, 21); LISTENER_LEVEL.put(9312, 21); LISTENER_LEVEL.put(37703, 28); } // Grand Exchange tunnel (GrandExchangeShortcut), Draynor Manor railing (DraynorManorShortcutListener)
    /** batch 18 item1: the build stamp: server.jar size plus the shortcut handler table; with the object's own definition it is the stamp a settled result is kept under. */
    static volatile String BUILD;
    static String build() {
        String b = BUILD;
        if (b != null) return b;
        long jar = 0;
        try { jar = new File(core.Server.class.getProtectionDomain().getCodeSource().getLocation().toURI()).length(); } catch (Throwable ignored) { }
        int h = 0;
        for (Map.Entry<Integer, Object[]> e : new java.util.TreeMap<>(HANDLER).entrySet()) h = h * 31 + e.getKey() * 17 + java.util.Arrays.hashCode(e.getValue());
        return BUILD = Long.toHexString(jar) + "-" + Integer.toHexString(h);
    }
    static String stamp(Shortcut s) {
        String def = "";
        try { SceneryDefinition d = SceneryDefinition.forId(s.id); if (d != null) def = d.getName() + java.util.Arrays.toString(d.getOptions()); } catch (Throwable ignored) { }
        return build() + "-" + Integer.toHexString((def + "|" + s.engineLevel()).hashCode());
    }

    static final Map<Integer, Object[]> HANDLER = new ConcurrentHashMap<>();      // object id -> {level, option}
    static final Map<String, Shortcut> SHORTCUTS = new ConcurrentHashMap<>();     // "x,y,z" -> shortcut
    static final Map<String, JSONObject> SHUT = new ConcurrentHashMap<>();       // doors that stayed shut for a surveyor
    static final Set<Integer> SCANNED = ConcurrentHashMap.newKeySet();           // regions already scanned
    static final Deque<Integer> TO_SCAN = new ArrayDeque<>();

    static final Pattern NAME = Pattern.compile(".*\\b(tunnel|pipe|hole|crevice|gap|stile|fence|wall|log|logs|stepping stone|stones|rock|rocks|rockslide|vine|vines|rope|ropeswing|tree|branch|monkeybars|monkey bars|railing|ledge|floor|bridge|plank|boulder|underwall|cave|crack|steps|handholds)\\b.*");
    static final String[] CROSS = {"squeeze-through", "crawl-through", "climb-over", "cross", "jump-over", "jump-across", "balance", "walk-across", "swing-on", "jump", "squeeze-past", "jump-to",
            "climb-through", "pass", "walk-on", "crawl", "climb-under", "squeeze-under", "jump-on", "leap", "shimmy", "swing-across", "climb-across", "squeeze-into", "crawl-under"};

    /** The crossing option, if this object is a shortcut. */
    static String shortcutOption(Scenery s) {
        if (s == null || s.getDefinition() == null) return null;
        String[] opts = s.getDefinition().getOptions();
        if (opts == null) return null;
        Object[] h = HANDLER.get(s.getId());
        if (h != null) { for (String o : opts) if (o != null && o.equalsIgnoreCase((String) h[1])) return o; for (String o : opts) if (o != null && !o.equalsIgnoreCase("examine")) return o; }
        String n = s.getName() == null ? "" : s.getName().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches()) return null;
        for (String o : opts) if (o != null) for (String c : CROSS) if (o.equalsIgnoreCase(c)) return o;
        if (n.contains("tunnel") || n.contains("pipe") || n.contains("underwall")) for (String o : opts) if (o != null && (o.equalsIgnoreCase("climb-into") || o.equalsIgnoreCase("enter"))) return o;
        return null;
    }

    static Shortcut note(Scenery s, String option) {
        String k = s.getLocation().getX() + "," + s.getLocation().getY() + "," + s.getLocation().getZ();
        Shortcut sc = SHORTCUTS.get(k);
        if (sc != null) return sc;
        sc = new Shortcut();
        sc.id = s.getId(); sc.x = s.getLocation().getX(); sc.y = s.getLocation().getY(); sc.z = s.getLocation().getZ();
        sc.name = s.getName(); sc.option = option;
        Object[] h = HANDLER.get(s.getId());
        if (h != null) { sc.level = (Integer) h[0]; sc.src = "server"; } else sc.src = "scan";
        SHORTCUTS.put(k, sc);
        return sc;
    }

    /** The server's AgilityShortcut handlers: object id -> exact Agility level. */
    static void readHandlers() {
        int n = 0;
        try {
            for (Map.Entry<Integer, SceneryDefinition> e : SceneryDefinition.getDefinitions().entrySet()) {
                SceneryDefinition d = e.getValue();
                if (d == null || d.getHandlers() == null) continue;
                for (Map.Entry<String, Object> h : d.getHandlers().entrySet()) {
                    if (!(h.getValue() instanceof content.global.skill.agility.AgilityShortcut)) continue;
                    content.global.skill.agility.AgilityShortcut a = (content.global.skill.agility.AgilityShortcut) h.getValue();
                    String opt = h.getKey().startsWith("option:") ? h.getKey().substring(7) : (a.getOption() != null && a.getOption().length > 0 ? a.getOption()[0] : "");
                    HANDLER.put(e.getKey(), new Object[]{a.getLevel(), opt});
                    n++;
                }
            }
        } catch (Throwable t) { System.out.println("[Gates] could not read the shortcut handlers: " + t); }
        System.out.println("[Gates] " + HANDLER.size() + " shortcut objects known from the server's code (" + n + " handlers)");
    }

    /** Scans one region (64x64, ground floor and the dungeon below it) for shortcut objects. Game thread. */
    static void scanRegion(int region) {
        int bx = (region >> 8) << 6, by = (region & 0xff) << 6, found = 0;
        for (int dx = 0; dx < 64; dx++) for (int dy = 0; dy < 64; dy++) {
            Scenery s;
            try { s = RegionManager.getObject(0, bx + dx, by + dy); } catch (Throwable t) { continue; }
            if (s == null || s.getLocation() == null) continue;
            String o = shortcutOption(s);
            if (o == null) continue;
            Location l = s.getLocation();
            if (!SHORTCUTS.containsKey(l.getX() + "," + l.getY() + "," + l.getZ())) { note(s, o); found++; }
        }
        SCANNED.add(region);
        if (found > 0 && Boolean.getBoolean("gates.debug")) System.out.println("[Gates] region " + region + ": " + found + " shortcuts");
    }

    /** Queue every region the surveyors have walked. */
    static void queueWalkedRegions() {
        Set<Integer> regions = new java.util.TreeSet<>();
        for (String k : Surveyors.TILES.keySet()) {
            try { String[] p = k.split(","); int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]); if (y > 6400 || !"0".equals(p[2])) continue; regions.add(((x >> 6) << 8) | (y >> 6)); } catch (Throwable ignored) { }
        }
        for (int r : regions) if (!SCANNED.contains(r)) TO_SCAN.add(r);
        System.out.println("[Gates] " + TO_SCAN.size() + " walked regions to scan for shortcuts (" + SCANNED.size() + " done before)");
    }

    // ------------------------------------------------------------ learning from the surveyors

    /** A tunnel surveyor used a shortcut. */
    static synchronized void tested(Shortcut sc, int agility, boolean ok, Location from, Location to) {
        if (agility >= 50) { if (ok) sc.highOk++; else sc.highFail++; } else { if (ok) sc.lowOk++; else sc.lowFail++; }
        if (ok && to != null) sc.to = new int[]{to.getX(), to.getY(), to.getZ(), from.getX(), from.getY()};
        sc.tested = System.currentTimeMillis();
        if (agility >= 50) { sc.highT = sc.tested; sc.highStamp = stamp(sc); } else { sc.lowT = sc.tested; sc.lowStamp = stamp(sc); } // batch 18 item1
        learnGate(sc); // batch 18 item1
        if (ok && to != null && from != null && to.getZ() == 0 && from.getZ() == 0 && to.getY() < 6400 && from.getY() < 6400) RouteMap.portal(sc, from, to, agility);
    }

    /** batch 18 item1: records the gate once the data shows one: the engine's level, items, or "crossed with Agility 99 and the quests, not with 1". */
    static void learnGate(Shortcut sc) {
        String g = null;
        if (sc.itemGated()) g = "items (a crossbow and a mithril grapple)";
        else if (sc.engineLevel() > 1) g = "Agility " + sc.engineLevel();
        else if (sc.highOk > 0 && sc.lowOk == 0 && sc.lowFail > 0) g = "agility or quest (crossed at 99 with the quests, not at 1)";
        if (g != null && !g.equals(sc.gate)) { boolean fresh = sc.gate == null; sc.gate = g; learned++; if (!fresh || Boolean.getBoolean("gates.debug") || loaded) System.out.println("[Gates] gate learned for " + sc.name + " at " + sc.key() + ": " + g); }
    }
    static int learned; static volatile boolean loaded;

    /** batch 18 item1: could a seeker with this Agility (and every gated quest, if seeded) pass it, as far as the engine data and the tests say? */
    static boolean couldPass(Shortcut sc, int agility, boolean quests) {
        if (sc.itemGated()) return false;
        int lvl = sc.engineLevel();
        if (lvl >= 0 && agility < lvl) return false;
        if (!quests && sc.highOk > 0 && sc.lowOk == 0 && sc.lowFail > 0) return false; // the learned gate: crossed with 99 and the quests, failed without
        return true;
    }

    /** batch 18 item1: settled vs still to test, per band (low = Agility 1 without quests, high = 99 with every gated quest). */
    static String seedCounts() {
        int all = 0, lowSet = 0, lowTo = 0, lowGated = 0, highSet = 0, highTo = 0, highGated = 0, out = 0, lowWait = 0, highWait = 0;
        for (Shortcut s : SHORTCUTS.values()) {
            all++;
            if (s.z != 0 || s.y > 6400 || Heat.wildLevel(s.x, s.y) > 0) { out++; continue; }
            boolean questArea = !at(s.at()).isEmpty();
            if (s.settled(false)) lowSet++; else if (!couldPass(s, 1, false) || questArea) lowGated++; else if (waiting(s, false)) lowWait++; else lowTo++;
            if (s.settled(true)) highSet++; else if (!couldPass(s, 99, true)) highGated++; else if (waiting(s, true)) highWait++; else highTo++;
        }
        return all + " shortcuts (" + out + " off the surface or in the Wilderness, never tested); agility 1: " + lowSet + " settled, " + lowGated + " gated for it, " + lowWait + " unsettled (retest from another side when a week old), " + lowTo + " to test now; agility 99: " + highSet + " settled, " + highGated + " gated for it, " + highWait + " unsettled (a week), " + highTo + " to test now (build stamp " + build() + ")";
    }
    /** batch 18 item1: unsettled (one or two fails) under today's stamp and tested less than a week ago (Explorers.Tunneller.due). */
    static boolean waiting(Shortcut s, boolean high) {
        int tests = high ? s.highOk + s.highFail : s.lowOk + s.lowFail;
        String st = high ? s.highStamp : s.lowStamp;
        return tests > 0 && st != null && st.equals(stamp(s)) && System.currentTimeMillis() - (high ? s.highT : s.lowT) < 7L * 24 * 3600_000L;
    }

    /** A door surveyor found a door that stayed shut: a quest, key or toll door. Confirms a gate within 6 tiles. */
    static void shutDoor(JSONObject rec, Location at) {
        String k = at.getX() + "," + at.getY() + "," + at.getZ();
        SHUT.put(k, rec);
        for (Gate gt : GATES) {
            if (at.getX() >= gt.x0 - 6 && at.getX() <= gt.x1 + 6 && at.getY() >= gt.y0 - 6 && at.getY() <= gt.y1 + 6 && !gt.confirmed) {
                gt.confirmed = true;
                System.out.println("[Gates] door at " + at + " confirms the gate to " + gt.name);
            }
        }
    }

    static void selfTest() {
        int n = 0;
        for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) {
            Player bot = br.bot; if (bot == null || !bot.isActive()) continue;
            if (n++ >= 4) break;
            StringBuilder q = new StringBuilder();
            Set<String> hist = SEEDED.get(bot.getUsername().toLowerCase(Locale.ROOT)); if (hist != null) for (String x : hist) q.append(x.toLowerCase(Locale.ROOT)).append(' ');
            System.out.println("[Gates] test: " + bot.getUsername() + " combat " + bot.getProperties().getCurrentCombatLevel() + " quests: " + q);
            for (int[] t : new int[][]{{3491, 3487}, {3429, 3555}, {2607, 3410}, {2849, 2986}, {3212, 3428}}) {
                Location to = Location.create(t[0], t[1], 0);
                RouteMap.Walker w = new RouteMap.Walker(bot, RouteMap.agility(bot));
                java.util.List<Location> r = RouteMap.plan(Location.create(3212, 3428, 0), to, w);
                System.out.println("[Gates] test:   to " + to + " canEnter=" + canEnter(bot, to) + (missing(bot, to) != null ? " (needs " + missing(bot, to) + ")" : "") + " route=" + (r == null ? "none" : r.size() + " waypoints"));
            }
            int able = 0, all = 0; for (SmartAdventurers.Job j : SmartAdventurers.JOBS) { all++; if (br.reachable(j)) able++; }
            System.out.println("[Gates] test:   jobs reachable " + able + "/" + all);
        }
        System.out.println("[Gates] test: " + SHORTCUTS.size() + " shortcuts, " + RouteMap.portalCount() + " portals, " + HANDLER.size() + " shortcut ids from the server");
    }

    // ------------------------------------------------------------ startup, save, load

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots() || !ON) return;
        readHandlers();
        load(); loaded = true; // batch 18 item1
        GameWorld.getPulser().submit(new Pulse(100) { // after the surveyors have loaded their tile census
            boolean queued;
            @Override public boolean pulse() {
                if (!queued) { queued = true; queueWalkedRegions(); setDelay(2); }
                Integer r = TO_SCAN.poll();
                if (r == null) { System.out.println("[Gates] shortcut scan done: " + SHORTCUTS.size() + " shortcuts known"); try { save(); } catch (Throwable ignored) { } return true; }
                try { scanRegion(r); } catch (Throwable t) { SCANNED.add(r); }
                return false;
            }
        });
        new java.util.Timer("Gates", true).schedule(new java.util.TimerTask() { public void run() { try { save(); } catch (Throwable t) { System.out.println("[Gates] save failed: " + t); } } }, 600_000, 600_000);
        if (Boolean.getBoolean("gates.test")) GameWorld.getPulser().submit(new Pulse(300) { // trials only
            @Override public boolean pulse() { try { selfTest(); } catch (Throwable t) { System.out.println("[Gates] test: " + t); t.printStackTrace(); } return true; }
        });
        if (Boolean.getBoolean("gates.test")) GameWorld.getPulser().submit(new Pulse(300) { // trials only: a walker crosses a learned shortcut
            @Override public boolean pulse() {
                RouteMap.Portal pt = null;
                for (java.util.List<RouteMap.Portal> l : RouteMap.PORTALS.values()) for (RouteMap.Portal x : l) if (pt == null && x.sc.level > 1) pt = x;
                if (pt == null) { System.out.println("[Gates] test: no portal yet"); return false; }
                int dx = Integer.signum(pt.tx - pt.fx), dy = Integer.signum(pt.ty - pt.fy);
                Location start = Location.create(pt.fx - dx * 2, pt.fy - dy * 2, 0), end = Location.create(pt.tx + dx * 25, pt.ty + dy * 25, 0);
                core.game.bots.AIPlayer w = new core.game.bots.SkillingBotAssembler().produce(core.game.bots.SkillingBotAssembler.Wealth.POOR, start);
                w.getSkills().setStaticLevel(Skills.AGILITY, 99); w.getSkills().setLevel(Skills.AGILITY, 99);
                System.out.println("[Gates] test: walker " + w.getUsername() + " from " + start + " to " + end + " past " + pt.sc.name + " at " + pt.sc.key() + "; plan " + RouteMap.plan(start, end, new RouteMap.Walker(w, 99)));
                final RouteMap.Portal fpt = pt;
                RouteMap.Route forced = new RouteMap.Route(); forced.portals.put(0, pt); forced.add(Location.create(pt.fx, pt.fy, 0)); forced.add(Location.create(pt.tx, pt.ty, 0)); forced.add(end);
                RouteMap.PLANS.put(w.getUsername().toLowerCase(), new Object[]{RouteMap.cell(end.getX(), end.getY()), forced, 0}); // this route, to test using the shortcut
                GameWorld.getPulser().submit(new Pulse(3) {
                    int n;
                    @Override public boolean pulse() {
                        Location hop = RouteMap.nextHop(w, end);
                        if (!w.getWalkingQueue().isMoving() && !RouteMap.usingShortcut(w)) w.walkToPosSmart(hop != null ? hop : end);
                        if (++n % 10 == 0) System.out.println("[Gates] test: walker at " + w.getLocation() + " hop " + hop);
                        if (w.getLocation().getDistance(end) < 6 || n > 120) { System.out.println("[Gates] test: walker finished at " + w.getLocation() + ", portal ok " + fpt.ok + " fail " + fpt.fail); return true; }
                        return false;
                    }
                });
                return true;
            }
        });
        System.out.println("[Gates] " + GATES.size() + " gates (quest areas, guilds, keys, tolls), " + SHORTCUTS.size() + " shortcuts and " + SHUT.size() + " shut doors remembered");
    }

    static File file() { return new File(new File(ServerConstants.DATA_PATH, "survey"), "gates.json"); }

    @SuppressWarnings("unchecked")
    static synchronized void save() throws Exception {
        JSONObject root = new JSONObject();
        JSONArray scs = new JSONArray();
        for (Shortcut s : SHORTCUTS.values()) {
            JSONObject j = new JSONObject();
            j.put("id", s.id); j.put("x", s.x); j.put("y", s.y); j.put("z", s.z); j.put("name", s.name); j.put("option", s.option); j.put("level", s.level); j.put("src", s.src);
            j.put("lowOk", s.lowOk); j.put("lowFail", s.lowFail); j.put("highOk", s.highOk); j.put("highFail", s.highFail); j.put("tested", s.tested);
            j.put("lowT", s.lowT); j.put("highT", s.highT); if (s.lowStamp != null) j.put("lowStamp", s.lowStamp); if (s.highStamp != null) j.put("highStamp", s.highStamp); if (s.gate != null) j.put("gate", s.gate); // batch 18 item1
            if (s.to != null) { JSONArray a = new JSONArray(); for (int v : s.to) a.add(v); j.put("to", a); }
            scs.add(j);
        }
        root.put("shortcuts", scs);
        JSONObject shut = new JSONObject(); shut.putAll(SHUT); root.put("shutDoors", shut);
        JSONArray sc = new JSONArray(); sc.addAll(SCANNED); root.put("scannedRegions", sc);
        JSONArray gs = new JSONArray();
        for (Gate gt : GATES) { JSONObject j = new JSONObject(); j.put("id", gt.id); j.put("name", gt.name); j.put("kind", gt.kind); j.put("box", boxOf(gt)); j.put("needs", gt.rule.need()); j.put("confirmed", gt.confirmed); gs.add(j); }
        root.put("gates", gs);
        Ledger.writeAtomic(file(), root.toJSONString());
    }

    @SuppressWarnings("unchecked")
    static JSONArray boxOf(Gate gt) { JSONArray a = new JSONArray(); a.add(gt.x0); a.add(gt.y0); a.add(gt.x1); a.add(gt.y1); return a; }

    static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : Integer.parseInt(String.valueOf(o)); }

    static synchronized void load() {
        File f = file();
        if (!f.exists()) return;
        try {
            JSONObject root = (JSONObject) new JSONParser().parse(new FileReader(f));
            JSONArray scs = (JSONArray) root.get("shortcuts");
            if (scs != null) for (Object o : scs) {
                JSONObject j = (JSONObject) o; Shortcut s = new Shortcut();
                s.id = num(j.get("id")); s.x = num(j.get("x")); s.y = num(j.get("y")); s.z = num(j.get("z")); s.name = String.valueOf(j.get("name")); s.option = String.valueOf(j.get("option"));
                s.level = num(j.get("level")); s.src = String.valueOf(j.get("src"));
                s.lowOk = num(j.get("lowOk")); s.lowFail = num(j.get("lowFail")); s.highOk = num(j.get("highOk")); s.highFail = num(j.get("highFail")); s.tested = ((Number) j.get("tested")).longValue();
                Object[] h = HANDLER.get(s.id); if (h != null) { s.level = (Integer) h[0]; s.src = "server"; }
                // batch 18 item1: per-band clocks and stamps; results recorded before stamps existed are adopted under today's stamp (the seed is not retraced)
                s.lowT = j.get("lowT") instanceof Number ? ((Number) j.get("lowT")).longValue() : (s.lowOk + s.lowFail > 0 ? s.tested : 0);
                s.highT = j.get("highT") instanceof Number ? ((Number) j.get("highT")).longValue() : (s.highOk + s.highFail > 0 ? s.tested : 0);
                s.lowStamp = j.get("lowStamp") != null ? String.valueOf(j.get("lowStamp")) : (s.lowOk + s.lowFail > 0 ? stamp(s) : null);
                s.highStamp = j.get("highStamp") != null ? String.valueOf(j.get("highStamp")) : (s.highOk + s.highFail > 0 ? stamp(s) : null);
                s.gate = j.get("gate") != null ? String.valueOf(j.get("gate")) : null;
                JSONArray to = (JSONArray) j.get("to");
                if (to != null) { s.to = new int[to.size()]; for (int i = 0; i < s.to.length; i++) s.to[i] = num(to.get(i)); }
                if (s.to != null && s.to.length >= 5 && s.to[2] == s.z && (s.to[1] > 6400) == (s.y > 6400)) { // an "exit" on the same side as the approach is a walk-up, not a crossing (review 10-22)
                    int dx1 = s.to[0] - s.x, dy1 = s.to[1] - s.y, dx2 = s.to[3] - s.x, dy2 = s.to[4] - s.y;
                    if (dx1 * dx2 + dy1 * dy2 >= 0) { System.out.println("[Gates] dropped a same-side exit for " + s.name + " at " + s.key() + " (retest due)"); s.to = null; s.lowOk = 0; s.highOk = 0; }
                }
                SHORTCUTS.put(s.key(), s);
                learnGate(s); // batch 18 item1
                if (s.to != null && s.to.length >= 5 && s.to[2] == 0 && (s.lowOk + s.highOk) > 0) RouteMap.portal(s, Location.create(s.to[3], s.to[4], 0), Location.create(s.to[0], s.to[1], 0), s.lowOk > 0 ? 1 : 99);
            }
            JSONObject shut = (JSONObject) root.get("shutDoors");
            if (shut != null) for (Object k : shut.keySet()) SHUT.put(String.valueOf(k), (JSONObject) shut.get(k));
            JSONArray sc = (JSONArray) root.get("scannedRegions");
            if (sc != null) for (Object o : sc) SCANNED.add(num(o));
            JSONArray gs = (JSONArray) root.get("gates");
            if (gs != null) for (Object o : gs) { JSONObject j = (JSONObject) o; if (Boolean.TRUE.equals(j.get("confirmed"))) for (Gate gt : GATES) if (gt.id.equals(j.get("id"))) gt.confirmed = true; }
        } catch (Throwable t) { System.out.println("[Gates] could not read gates.json: " + t); Ledger.quarantine(f, "Gates", t, false, true); } // audit 10-23 batch14 item1
        finally { loaded = true; }
        try { System.out.println("[Gates] seed: " + seedCounts() + "; " + learned + " gates known from the engine data and the recorded tests"); } catch (Throwable t) { System.out.println("[Gates] seed counts: " + t); } // batch 18 item1
    }
}
