package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * W8 lane I (2026-11-19, the design notes): TEST-ONLY seeding so a switch under test fires in a
 * 3-hour pair. Many new switches never fired in a 60-bot test world because almost no bot met their entry state (level,
 * items, quest, coins, place); a switch that never fires cannot pass its gate.
 *
 *   -Dsmartbots.seed.fire=<switch,switch,..>  switches to feed: the full -D name (bots.fish.shark) or without its first
 *                                             segment (fish.shark); case does not matter; unknown names are dropped with
 *                                             one warning line
 *   -Dsmartbots.seed.fireShare=<0..100>       percent of bots that get each switch's state (default 20)
 *
 * A bot is picked per switch by a hash of its name and the switch name (no RNG draw, the same bots in both arms of a pair,
 * different bots for different switches). State is only raised or topped up, never lowered, except the "broke" recipes,
 * which set coins. Farm worlds only: the same guard as Seed (test.tickprobe + farm.gamePort + 127.0.0.1), and the
 * smartbots.seed. prefix is policed out of live by the deploy scripts. Flag unset: apply/task return at once, nothing is
 * read, drawn or logged. Put the same string on BOTH arms of a pair so the arms differ only by the switch itself.
 */
final class SeedFire {
    private SeedFire() { }

    static final String FLAG = Seed.P + "fire", SHARE_FLAG = Seed.P + "fireShare";
    static final int DEFAULT_SHARE = 20;

    /** One switch's entry state. coinsMin tops coins up to at least this; coinsSet (>= 0) sets coins exactly (broke tests). */
    static final class Recipe {
        final String name; final int[][] skills; final int[][] bank; final long coinsMin, coinsSet; final String[] quests; final int[] at; final String task;
        Recipe(String name, int[][] skills, int[][] bank, long coinsMin, long coinsSet, String[] quests, int[] at, String task) {
            this.name = name; this.skills = skills; this.bank = bank; this.coinsMin = coinsMin; this.coinsSet = coinsSet; this.quests = quests; this.at = at; this.task = task;
        }
        String shortName() { int i = name.indexOf('.'); return i < 0 ? name : name.substring(i + 1); }
    }

    static int[][] s(int... kv) { int[][] r = new int[kv.length / 2][]; for (int i = 0; i < r.length; i++) r[i] = new int[]{kv[2 * i], kv[2 * i + 1]}; return r; }
    static final int[][] NONE = new int[0][];
    static final String[] NOQ = new String[0];
    static final String WW = "WOLF_WHISTLE";

    // Item ids: harpoon 311, lobster pot 301, tinderbox 590, iron pickaxe 1267, knife 946, runes air 556 water 555 earth 557
    // fire 554 mind 558 law 563 nature 561, lobster 379, pouches spirit wolf 12047, dreadfowl 12043, thorny snail 12019, bull ant 12087.
    static final Recipe[] RECIPES = {
        new Recipe("bots.fish.shark", s(Skills.FISHING, 76), s(311, 1), -1, -1, NOQ, null, null),
        new Recipe("bots.fish.guild", s(Skills.FISHING, 68), s(301, 1, 311, 1), -1, -1, NOQ, null, null),
        new Recipe("bots.fire.line", s(Skills.FIREMAKING, 45), s(590, 1), 5_000, -1, NOQ, new int[]{3185, 3436, 0}, null), // Varrock west bank
        new Recipe("bots.mine.power", s(Skills.MINING, 30), s(1267, 1), -1, -1, NOQ, null, null),
        new Recipe("bots.prayer.stat", s(Skills.PRAYER, 31), NONE, -1, -1, NOQ, null, null),
        new Recipe("bots.ranged.kit2", s(Skills.RANGE, 50, Skills.DEFENCE, 40), NONE, 20_000, -1, NOQ, null, null),
        new Recipe("bots.magic.tele", s(Skills.MAGIC, 45), s(563, 20, 556, 100, 555, 20, 557, 20, 554, 20), -1, -1, NOQ, null, null),
        new Recipe("bots.magic.alchAll", s(Skills.MAGIC, 55), s(561, 50, 554, 250), -1, -1, NOQ, null, null),
        new Recipe("bots.magic.anyStaff", s(Skills.MAGIC, 25), s(556, 100, 558, 100), -1, -1, NOQ, null, null),
        new Recipe("bots.farming.herbs2", s(Skills.FARMING, 50), NONE, 20_000, -1, NOQ, null, null),
        new Recipe("bots.plankTypes", s(Skills.CONSTRUCTION, 50), NONE, 30_000, -1, NOQ, null, null),
        new Recipe("bots.fletch.own", s(Skills.WOODCUTTING, 30, Skills.FLETCHING, 20), s(946, 1), -1, -1, NOQ, null, null),
        new Recipe("bots.thieve.ardy", s(Skills.THIEVING, 55), NONE, -1, -1, NOQ, null, null),
        new Recipe("bots.thieve.stalls", s(Skills.THIEVING, 20), NONE, -1, -1, NOQ, null, null),
        new Recipe("bots.agility.barb", s(Skills.AGILITY, 40), NONE, -1, -1, NOQ, null, null),
        new Recipe("bots.summoning.fix5", s(Skills.SUMMONING, 4), s(12047, 2, 12043, 2), -1, -1, new String[]{WW}, null, null),
        new Recipe("bots.summoning.bob", s(Skills.SUMMONING, 40), s(12019, 2, 12087, 2), -1, -1, new String[]{WW}, null, null),
        new Recipe("bots.summoning.combatSteer", s(Skills.SUMMONING, 18), s(12047, 4), -1, -1, new String[]{WW}, null, null),
        new Recipe("bots.summoning.combat2", s(Skills.SUMMONING, 10), s(12043, 4, 12445, 30), -1, -1, new String[]{WW}, null, null), // w20: dreadfowl pouches + its scrolls (Bob.SPECIALS row 1): no bot owned a combat pouch in q06/q23
        new Recipe("bots.slayer.vannakaHunts", s(Skills.ATTACK, 85, Skills.STRENGTH, 85, Skills.DEFENCE, 85, Skills.HITPOINTS, 85, Skills.PRAYER, 43, Skills.SLAYER, 50),
                s(379, 20), 5_000, -1, new String[]{"PRIEST_IN_PERIL"}, null, "VAMPIRES:10"),
        new Recipe("bots.slayer.brokeTrip", NONE, NONE, -1, 40, NOQ, null, null),
        new Recipe("bots.brokeJob.zeroSkip", NONE, NONE, -1, 0, NOQ, null, null),
    };

    static final String RAW = System.getProperty(FLAG);
    static final boolean ASKED = RAW != null && !RAW.trim().isEmpty();
    static final boolean ON = ASKED && Seed.ALLOWED;
    static final List<String> UNKNOWN = new ArrayList<>();
    static final Recipe[] ACTIVE = ON ? parse(RAW, UNKNOWN) : new Recipe[0];
    static final int SHARE = ON ? share(System.getProperty(SHARE_FLAG)) : 0;
    static final Set<String> DONE = ConcurrentHashMap.newKeySet(), TASKED = ConcurrentHashMap.newKeySet();
    static volatile int seeded;

    static {
        if (ASKED && !Seed.ALLOWED) System.out.println("[Seed] fire refused (live)");
        else if (ON) {
            StringBuilder b = new StringBuilder();
            for (Recipe r : ACTIVE) b.append(b.length() == 0 ? "" : ",").append(r.name);
            System.out.println("[Seed] fire on: share=" + SHARE + "% switches=" + (b.length() == 0 ? "none" : b));
            if (!UNKNOWN.isEmpty()) System.out.println("[Seed] fire WARNING: ignored unknown switch(es): " + String.join(",", UNKNOWN));
        }
    }

    // ---- pure helpers (TestSeedFire)

    /** The recipe for a switch name (full or without its first segment, any case); null when none. */
    static Recipe find(String name) {
        if (name == null) return null;
        String n = name.trim();
        for (Recipe r : RECIPES) if (r.name.equalsIgnoreCase(n) || r.shortName().equalsIgnoreCase(n)) return r;
        return null;
    }

    /** "a,b,c" -> the known recipes in order, duplicates once; unknown names go to {@code unknown}. */
    static Recipe[] parse(String raw, List<String> unknown) {
        List<Recipe> out = new ArrayList<>();
        if (raw == null) return new Recipe[0];
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            Recipe r = find(p);
            if (r == null) { if (unknown != null) unknown.add(p); continue; }
            if (!out.contains(r)) out.add(r);
        }
        return out.toArray(new Recipe[0]);
    }

    /** Share in percent: blank/junk = 20, clamped 0..100. */
    static int share(String s) {
        if (s == null || s.trim().isEmpty()) return DEFAULT_SHARE;
        try { return Math.max(0, Math.min(100, Integer.parseInt(s.trim()))); } catch (NumberFormatException e) { return DEFAULT_SHARE; }
    }

    /** Is this bot in this switch's share: a stable hash of name + switch, no random draw. */
    static boolean picked(String bot, String sw, int share) {
        if (bot == null || sw == null || share <= 0) return false;
        if (share >= 100) return true;
        return Math.floorMod((bot.toLowerCase(Locale.ROOT) + "|" + sw).hashCode(), 100) < share;
    }

    /** What a recipe touches, so tests can use a fake bot. */
    interface Target {
        int level(int skill); void raise(int skill, int lvl);
        int bank(int id); void bankAdd(int id, int n);
        long coins(); void setCoins(long n); void addCoins(long n);
        void quest(String enumName); void at(int x, int y, int z);
    }

    /** Give one bot a recipe's state: levels raised (never lowered), bank items topped up, coins topped/set, quests done, start tile. */
    static String give(Target t, Recipe r) {
        StringBuilder b = new StringBuilder();
        for (int[] sk : r.skills) { if (t.level(sk[0]) < sk[1]) t.raise(sk[0], sk[1]); b.append(" s").append(sk[0]).append('=').append(t.level(sk[0])); }
        for (int[] it : r.bank) { int have = t.bank(it[0]); if (have < it[1]) t.bankAdd(it[0], it[1] - have); b.append(" i").append(it[0]).append('=').append(t.bank(it[0])); }
        if (r.coinsSet >= 0) t.setCoins(r.coinsSet);
        else if (r.coinsMin > 0 && t.coins() < r.coinsMin) t.addCoins(r.coinsMin - t.coins());
        if (r.coinsSet >= 0 || r.coinsMin > 0) b.append(" coins=").append(t.coins());
        for (String q : r.quests) { t.quest(q); b.append(" quest=").append(q); }
        if (r.at != null) { t.at(r.at[0], r.at[1], r.at[2]); b.append(" at=").append(r.at[0]).append(',').append(r.at[1]).append(',').append(r.at[2]); }
        return b.toString();
    }

    // ---- world hooks

    /** SmartAdventurers.enlist, right after Seed.apply: once per bot name per boot. */
    static void apply(Player p) {
        if (!ON || p == null || !p.isArtificial()) return;
        String name = p.getUsername();
        if (name == null || !DONE.add(name.toLowerCase(Locale.ROOT))) return;
        Target t = null;
        for (Recipe r : ACTIVE) {
            if (!picked(name, r.name, SHARE)) continue;
            try {
                if (t == null) t = world(p);
                String got = give(t, r);
                try { p.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
                seeded++;
                System.out.println("[Seed] fire " + r.name + " " + Seed.word(name) + got + (r.task != null ? " task=" + r.task + " (at enlist)" : ""));
            } catch (Throwable e) { System.out.println("[Seed] fire " + r.name + " " + Seed.word(name) + ": " + e); }
        }
    }

    /** SmartAdventurers.enlist, after Seed.task (the Brain exists): a recipe with a Slayer task holds it. */
    static void task(SmartAdventurers.Brain b, Player p) {
        if (!ON || b == null || p == null || !p.isArtificial()) return;
        String name = p.getUsername();
        if (name == null || !TASKED.add(name.toLowerCase(Locale.ROOT))) return;
        for (Recipe r : ACTIVE) {
            if (r.task == null || !picked(name, r.name, SHARE)) continue;
            try {
                String[] tk = Seed.task(r.task);
                content.global.skill.slayer.Tasks t = tk == null ? null : Seed.taskFor(content.global.skill.slayer.Tasks.class, tk[0]);
                boolean held = t != null && Slayer.hold(b, p, t, Integer.parseInt(tk[1]), System.currentTimeMillis());
                System.out.println("[Seed] fire " + r.name + " " + Seed.word(name) + " task=" + r.task + (held ? " held" : t == null ? " refused (no such Slayer task)" : " refused (bots.slayer off, or not kept)"));
            } catch (Throwable e) { System.out.println("[Seed] fire task " + Seed.word(name) + ": " + e); }
        }
    }

    static Target world(Player p) {
        return new Target() {
            public int level(int skill) { return p.getSkills().getStaticLevel(skill); }
            public void raise(int skill, int lvl) { p.getSkills().setStaticLevel(skill, lvl); p.getSkills().setLevel(skill, lvl); }
            public int bank(int id) { return p.getBank().getAmount(id); }
            public void bankAdd(int id, int n) { p.getBank().add(new Item(id, n)); }
            public long coins() { return p.getBank().getAmount(Seed.COIN_ID) + (long) p.getInventory().getAmount(Seed.COIN_ID); }
            public void setCoins(long n) {
                int inv = p.getInventory().getAmount(Seed.COIN_ID), bk = p.getBank().getAmount(Seed.COIN_ID);
                if (inv > 0) p.getInventory().remove(new Item(Seed.COIN_ID, inv));
                if (bk > 0) p.getBank().remove(new Item(Seed.COIN_ID, bk));
                if (n > 0) p.getBank().add(new Item(Seed.COIN_ID, (int) Math.min(Integer.MAX_VALUE, n)));
            }
            public void addCoins(long n) { if (n > 0) p.getBank().add(new Item(Seed.COIN_ID, (int) Math.min(Integer.MAX_VALUE, n))); }
            public void quest(String enumName) {
                content.data.Quests q = content.data.Quests.valueOf(enumName);
                core.game.node.entity.player.link.quest.Quest qq = p.getQuestRepository().getQuest(q);
                if (qq != null && !qq.isCompleted(p)) p.getQuestRepository().setStage(qq, 100);
            }
            public void at(int x, int y, int z) { p.getProperties().setTeleportLocation(core.game.world.map.Location.create(x, y, z)); }
        };
    }
}
