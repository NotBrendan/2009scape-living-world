package content.custom;

import content.global.skill.smithing.smelting.Bar;
import core.game.container.Container;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.update.flag.context.Animation;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Batch 25 Magic AFK, phase 1 (the design notes R1-R10 and section 7, batch23-magic-plan.md 3-5):
 * alchemy from the bank and superheat (from the bank, or on the spot while mining) for players. Spells are rows in afk-spells.json (R5), never code. The cast is the server's own:
 * ModernListeners.alchemize / access$superheat after SpellListener.requires, as the spellbook lambdas do (M0); the
 * Runner only waits for the Magic xp (R6) and counts. Guards, in begin order: kill switch (R4), level ladder, staff,
 * runes, the item, value (R2), profit (Q3), the player's daily cap (R3). Every refusal names the alternative; the word
 * 'auto' takes it and says so (section 7.2). Bots' highAlch (SmartAdventurers) and Ledger.alchRoom are not touched:
 * player alch coins go to Ledger.PLAYER_ALCHED and the [AfkMagic] ECON line (R10).
 */
final class AfkMagic {
    private AfkMagic() { }

    static final int COINS = 995;

    static final class Spell {
        String key, name, handler, fallback; int id, level, delay, trainTo; double xp;
        int[][] runes = new int[0][];   // {rune id, amount}
        String[] runeNames = new String[0];
        boolean alch() { return handler != null && handler.startsWith("alch"); }
        boolean high() { return "alch_high".equals(handler); }
    }

    /** One player's Magic job: set by resolve, handed to the Runner through PENDING (Runner.chain takes it). */
    static final class Run {
        Spell spell; int item; boolean force, auto;
        int castsLeft, casts, stall, runesUsed, itemsUsed, coinsSeen;
        long coinsMade; double xpSeen; boolean pending, told, r6Told;
        int runesAt = -1;              // R6 second signal: runesHeld just before the pending cast (-1: none)
        String why = "";               // the fallback taken, for the start line and the log
        AfkTasks.Task task;            // superheat: the job the ladder settled on (a mining task, BANK_LOOP, or a furnace job)
        AfkTasks.Process pr = AfkTasks.Process.NONE; // spell == null: the ladder chose a job without Magic (pr SMELT / NONE)
        String want = "";
    }
    static final Map<String, Run> PENDING = new HashMap<>();

    // ------------------------------------------------------------------ data (R5)

    static final Map<Integer, int[]> STAFFS = new HashMap<>();
    static final Map<String, Spell> SPELLS = load();

    static Map<String, Spell> load() {
        Map<String, Spell> out = new LinkedHashMap<>();
        try (InputStream in = AfkMagic.class.getResourceAsStream("afk-spells.json")) {
            if (in == null) throw new IllegalStateException("afk-spells.json missing beside the classes");
            JSONObject root = (JSONObject) new JSONParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            JSONObject st = (JSONObject) root.get("staffs");
            if (st != null) for (Object k : st.keySet()) {
                JSONArray a = (JSONArray) st.get(k); int[] ids = new int[a.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = ((Number) a.get(i)).intValue();
                STAFFS.put(Integer.parseInt(String.valueOf(k)), ids);
            }
            for (Object o : (JSONArray) root.get("spells")) {
                JSONObject j = (JSONObject) o;
                Spell s = new Spell();
                s.key = (String) j.get("key"); s.name = (String) j.get("name"); s.handler = (String) j.get("handler");
                s.fallback = (String) j.get("fallback");
                s.id = ((Number) j.get("id")).intValue(); s.level = ((Number) j.get("level")).intValue();
                s.xp = ((Number) j.get("xp")).doubleValue(); s.delay = Math.max(1, ((Number) j.getOrDefault("delay", 5L)).intValue());
                s.trainTo = ((Number) j.getOrDefault("trainTo", (long) s.level)).intValue();
                JSONArray rs = (JSONArray) j.get("runes");
                s.runes = new int[rs.size()][]; s.runeNames = new String[rs.size()];
                for (int i = 0; i < rs.size(); i++) {
                    JSONObject r = (JSONObject) rs.get(i);
                    s.runes[i] = new int[]{((Number) r.get("id")).intValue(), ((Number) r.get("amount")).intValue()};
                    s.runeNames[i] = String.valueOf(r.get("name"));
                }
                out.put(s.key, s);
            }
            System.out.println("[AfkMagic] loaded " + out.size() + " spells from afk-spells.json");
        } catch (Throwable t) { System.out.println("[AfkMagic] couldn't load afk-spells.json (Magic AFK jobs refuse): " + t); }
        return out;
    }

    // ------------------------------------------------------------------ switches and caps (R2, R3, R4, R9)

    static volatile boolean ON = true;
    static volatile long checkedAt;
    /** R4: -Dafk.magic=false or the file data/afk.magic.off, re-read at most once a minute (like nav.on / afk.playernav.off). */
    static boolean on() {
        long now = System.currentTimeMillis();
        if (now - checkedAt >= 60_000) {
            checkedAt = now;
            boolean f;
            try { f = !"false".equalsIgnoreCase(System.getProperty("afk.magic", "true")) && !new java.io.File(core.ServerConstants.DATA_PATH, "afk.magic.off").exists(); } catch (Throwable t) { f = true; }
            if (f != ON) { ON = f; System.out.println("[AfkMagic] Magic AFK jobs " + (f ? "on" : "off (afk.magic / data/afk.magic.off)")); }
        }
        return ON;
    }
    static long valueMax() { return Long.getLong("afk.alchValueMax", 10_000); }
    /** R9: 'force' past the value guard only where -Dafk.alchForce=true (the farm); live keeps it off for the first week. */
    static boolean forceOk() { return Boolean.getBoolean("afk.alchForce"); }
    static long dailyCap() { return Long.getLong("afk.alchCap", 250_000); }
    static int maxCasts() { return Integer.getInteger("afk.maxCasts", 1500); }
    static boolean sim() { return Boolean.getBoolean("afk.magic.sim"); }

    /** R3: coins a player's alchs made per UTC day: {day, coins}. In memory: a restart starts the day's count again. */
    static final Map<String, Object[]> DAILY = new HashMap<>();
    static String today() { return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(); }
    static synchronized long alchedToday(String user) {
        Object[] d = DAILY.get(user);
        return d == null || !today().equals(d[0]) ? 0 : (Long) d[1];
    }
    static synchronized void addAlched(String user, long coins) {
        DAILY.put(user, new Object[]{today(), alchedToday(user) + coins});
        Ledger.playerAlched(coins);
    }

    // ------------------------------------------------------------------ helpers

    static String key(Player p) { return AfkTasks.key(p); }
    static void red(Player p, String s) { p.sendMessage("<col=ff0000>AFK: " + s); }
    static void green(Player p, String s) { p.sendMessage("<col=0b5e1f>AFK: " + s); }
    static int magic(Player p) { return p.getSkills().getStaticLevel(Skills.MAGIC); }
    static String lc(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
    static String gp(long n) { return String.format(Locale.ROOT, "%,d", n); }

    static int alchValue(int id, boolean high) { try { return core.cache.def.impl.ItemDefinition.forId(id).getAlchemyValue(high); } catch (Throwable t) { return 0; } }
    static boolean stackable(int id) { try { return new Item(id).getDefinition().isStackable(); } catch (Throwable t) { return false; } }

    /** The wielded staff stands in for this rune (Q6: only a wielded staff counts, as in the game). */
    static boolean staffCovers(Player p, int rune) {
        int[] ids = STAFFS.get(rune);
        Item w = p.getEquipment().get(3);
        return ids != null && w != null && AfkTasks.has(ids, w.getId());
    }
    /** A staff for this rune in the pack or bank (0 = none). */
    static int staffInStock(Player p, int rune) {
        int[] ids = STAFFS.get(rune);
        if (ids != null) for (int id : ids) if (p.getInventory().getAmount(id) > 0 || p.getBank().getAmount(id) > 0) return id;
        return 0;
    }
    /** Casts the pack and bank's runes allow (staff-aware). */
    static int runeCasts(Player p, Spell s) {
        int n = Integer.MAX_VALUE;
        for (int[] r : s.runes) if (!staffCovers(p, r[0])) n = Math.min(n, (p.getInventory().getAmount(r[0]) + p.getBank().getAmount(r[0])) / r[1]);
        return n;
    }
    /** The first rune short for one cast in the pack and bank, or -1. */
    static int shortRune(Player p, Spell s, boolean packOnly) {
        for (int k = 0; k < s.runes.length; k++) {
            int[] r = s.runes[k];
            if (staffCovers(p, r[0])) continue;
            int have = p.getInventory().getAmount(r[0]) + (packOnly ? 0 : p.getBank().getAmount(r[0]));
            if (have < r[1]) return k;
        }
        return -1;
    }
    static int runeCost(Player p, Spell s) {
        int c = 0;
        for (int[] r : s.runes) if (!staffCovers(p, r[0])) c += r[1] * Math.max(0, MarketBrain.value(r[0]));
        return c;
    }
    static int profit(Player p, Spell s, int id) { return alchValue(id, s.high()) - Math.max(0, MarketBrain.value(id)) - runeCost(p, s); }
    static boolean isRune(Spell s, int id) { for (int[] r : s.runes) if (r[0] == id) return true; return false; }
    /** R2: 0 = fine, 1 = worth more than the value guard, 2 = untradeable. */
    static int blocked(int id) {
        if (!MarketBrain.tradeable(id)) return 2;
        return MarketBrain.value(id) > valueMax() ? 1 : 0;
    }

    /** The bank's alchable stacks for this spell, best profit first: {id, amount, profit, blocked}. */
    static List<int[]> alchables(Player p, Spell s) {
        List<int[]> out = new ArrayList<>();
        for (Item it : p.getBank().toArray()) {
            if (it == null || it.getId() == COINS || isRune(s, it.getId())) continue;
            if (alchValue(it.getId(), s.high()) <= 0) continue;
            out.add(new int[]{it.getId(), it.getAmount(), profit(p, s, it.getId()), blocked(it.getId())});
        }
        out.sort((a, b) -> Integer.compare(b[2], a[2]));
        return out;
    }
    /** The best stack that passes the value guard, has profit >= 0 and at least {@code min} items (0 = none). */
    static int bestStack(Player p, Spell s, int min, int except) {
        for (int[] a : alchables(p, s)) if (a[0] != except && a[3] == 0 && a[2] >= 0 && a[1] >= min) return a[0];
        return 0;
    }

    /** The bank item called {@code want}: an exact name, else the one stack whose name has every word. -1 = more than one matches (R2). */
    static int itemNamed(Player p, String want, StringBuilder twins) {
        int found = 0;
        for (Item it : p.getBank().toArray()) {
            if (it == null) continue;
            String n = lc(MarketBrain.name(it.getId()));
            if (n.equals(want) || n.equals(want.replaceAll("s$", ""))) return it.getId();
        }
        for (Item it : p.getBank().toArray()) {
            if (it == null) continue;
            String n = lc(MarketBrain.name(it.getId()));
            boolean all = true;
            for (String x : want.split(" ")) if (!x.isEmpty() && !n.contains(x) && !(x.endsWith("s") && n.contains(x.substring(0, x.length() - 1)))) all = false;
            if (!all) continue;
            if (found != 0 && found != it.getId()) { twins.append(twins.length() > 0 ? ", " : "").append(n); found = -1; }
            else if (found == 0) { found = it.getId(); twins.append(n); }
        }
        return found;
    }

    /** "Try: ..." for a level the player is short of: the castable rung below, then training. */
    static String levelTry(Player p, Spell s, String job) {
        List<String> alt = new ArrayList<>();
        for (Spell f = SPELLS.get(s.fallback); f != null; f = SPELLS.get(f.fallback)) {
            if (magic(p) >= f.level) { alt.add("::afk " + job + " low (" + f.name.toLowerCase(Locale.ROOT) + "), or the word auto"); break; }
        }
        int to = s.trainTo > 0 ? s.trainTo : s.level;
        for (Spell f = SPELLS.get(s.fallback); f != null && alt.isEmpty(); f = SPELLS.get(f.fallback)) to = f.level; // nothing castable: train to the lowest rung
        alt.add("train Magic to " + to + " first");
        return " Try: " + String.join(", or ", alt) + ".";
    }

    // ------------------------------------------------------------------ ::afk alch list (R8)

    static void list(Player p, String low) {
        Spell s = SPELLS.get(low != null || !SPELLS.containsKey("alch_high") ? "alch_low" : "alch_high");
        if (s == null) { red(p, "no spell table (afk-spells.json) on this world."); return; }
        if (low == null && s.high() && magic(p) < s.level && SPELLS.containsKey("alch_low")) { // P37: below 55 price what they can cast
            green(p, s.name + " needs Magic " + s.level + " (you have " + magic(p) + "), so these are " + SPELLS.get("alch_low").name + " prices.");
            list(p, "low"); return;
        }
        List<int[]> all = alchables(p, s);
        if (all.isEmpty()) { green(p, "nothing in your bank " + s.name.toLowerCase(Locale.ROOT) + " pays for."); return; }
        green(p, s.name + " from your bank (gp a cast after the item's price and the runes" + (staffCovers(p, 554) ? ", staff wielded" : "") + "):");
        int n = 0;
        for (int[] a : all) {
            if (++n > 8) break;
            String tag = a[3] == 1 ? " (worth over " + gp(valueMax()) + " gp: needs force)" : a[3] == 2 ? " (untradeable: never)" : a[2] < 0 ? " (loses: needs force)" : "";
            p.sendMessage("<col=0b5e1f>  " + lc(MarketBrain.name(a[0])) + " x" + a[1] + ": " + (a[2] >= 0 ? "+" : "") + a[2] + " gp" + tag);
        }
        if (all.size() > 8) p.sendMessage("<col=0b5e1f>  ... and " + (all.size() - 8) + " more. ::afk alch <item>, or ::afk alch auto for the best.");
    }

    // ------------------------------------------------------------------ the start: guards and the fallback ladder (7.1)

    /**
     * The Magic job for ::afk alch [item] [low] [force] [auto] [x<n>], or null after the refusal was sent. Each guard
     * either passes, steps down a rung (auto, said in chat), or refuses naming the alternative.
     */
    static Run resolveAlch(Player p, String want, boolean low, boolean force, boolean auto, int count) {
        if (!on()) { red(p, "Magic AFK jobs are off on this world."); return null; }
        Spell s = SPELLS.get(low || !SPELLS.containsKey("alch_high") ? "alch_low" : "alch_high");
        if (s == null) { red(p, "no spell table (afk-spells.json) on this world."); return null; }
        Run run = new Run(); run.force = force; run.auto = auto;
        String job = "alch" + (want.isEmpty() ? "" : " " + want);
        // 1) Magic level: high -> low (auto), else name the rung and training
        while (magic(p) < s.level) {
            Spell f = SPELLS.get(s.fallback);
            if (!auto || f == null) {
                red(p, s.name.toLowerCase(Locale.ROOT) + " needs Magic " + s.level + " (you have " + magic(p) + ")." + (f == null && s.fallback == null && !s.high() ? " No alchemy below Magic " + s.level + "." : "") + levelTry(p, s, job));
                return null;
            }
            run.why += "Magic " + magic(p) + ", so " + f.name.toLowerCase(Locale.ROOT) + " instead. ";
            s = f;
        }
        // 2) a staff for the fire runes: equip it only into an empty weapon slot (Q6 / 7.5 point 6), and only with auto
        int fire = 554;
        if (isRune(s, fire) && !staffCovers(p, fire) && runeCasts(p, s) == 0 && shortRune(p, s, false) >= 0 && s.runes[shortRune(p, s, false)][0] == fire) {
            int staff = staffInStock(p, fire);
            if (staff > 0 && p.getEquipment().get(3) == null) {
                if (!auto) { red(p, s.name.toLowerCase(Locale.ROOT) + " needs fire runes; you have a " + lc(MarketBrain.name(staff)) + ". Wield it, or add the word auto and I'll wield it."); return null; }
                if (wield(p, staff)) run.why += "Wielded your " + lc(MarketBrain.name(staff)) + " for the fire runes. ";
            }
        }
        // 3) runes: a lower rung that needs fewer (auto), else name the rune and the shop (buying is phase 2)
        int sr = shortRune(p, s, false);
        if (sr >= 0) {
            Spell f = SPELLS.get(s.fallback);
            boolean lower = f != null && magic(p) >= f.level && shortRune(p, f, false) < 0;
            if (auto && lower) { run.why += "Not enough " + s.runeNames[sr] + "s for " + s.name.toLowerCase(Locale.ROOT) + ", so " + f.name.toLowerCase(Locale.ROOT) + " instead. "; s = f; }
            else { int rid = s.runes[sr][0];
            red(p, "alch: you need " + s.runes[sr][1] + " " + s.runeNames[sr] + (s.runes[sr][1] > 1 ? "s" : "") + " a cast in your bank or pack" + (STAFFS.containsKey(rid) ? " (a wielded staff counts)" : "") + "."
                    + (lower ? " Try: ::afk " + job + " low, or the word auto." : "")
                    + " Buying runes (cap 2,000 gp) comes in phase 2." + AfkTasks.shopHint(p, AfkTasks.ids(rid)));
            return null; }
        }
        // 4) the item: named, else (auto) the best stack
        int id;
        if (want.isEmpty()) {
            id = auto ? bestStack(p, s, 10, 0) : 0;
            if (id == 0) {
                int best = bestStack(p, s, 1, 0);
                red(p, "say which item to alch" + (best > 0 ? " (best in your bank: " + lc(MarketBrain.name(best)) + ", +" + profit(p, s, best) + " gp a cast)" : "") + ". ::afk alch list shows them" + (auto ? "; auto needs a stack of 10 or more that pays." : ", ::afk alch auto takes the best."));
                return null;
            }
            run.why += "Alching " + lc(MarketBrain.name(id)) + " (best profit in your bank). ";
        } else {
            StringBuilder twins = new StringBuilder();
            id = itemNamed(p, want, twins);
            if (id == -1) { red(p, "'" + want + "' matches " + twins + ": say the full name."); return null; }
            if (id == 0 || id == COINS) {
                int best = auto ? bestStack(p, s, 10, 0) : 0;
                if (best == 0) { red(p, id == COINS ? "coins are already gold." : "no '" + want + "' in your bank. ::afk alch list shows what you can alch."); return null; }
                run.why += "No " + want + " in your bank, so " + lc(MarketBrain.name(best)) + " (best profit). ";
                id = best;
            }
        }
        // 5) value guard (R2, R9) and 6) profit (Q3): the next stack that passes (auto), else refuse with the numbers
        for (int round = 0; round < 2; round++) {
            int b = blocked(id), pr = profit(p, s, id);
            boolean valueOk = b == 0 || b == 1 && force && forceOk();
            boolean profitOk = pr >= 0 || force;
            if (valueOk && profitOk) break;
            int next = bestStack(p, s, 10, id);
            String nm = lc(MarketBrain.name(id));
            String reason = b == 2 ? nm + " can't be traded, so it is never alched here"
                    : b == 1 && !valueOk ? nm + " is worth " + gp(MarketBrain.value(id)) + " gp (over " + gp(valueMax()) + ")" + (force && !forceOk() ? "; force isn't allowed for that on this world" : forceOk() ? "; add force to alch it anyway" : "")
                    : nm + " loses " + (-pr) + " gp a cast (alch " + alchValue(id, s.high()) + ", price " + MarketBrain.value(id) + ", runes " + runeCost(p, s) + "); add force to do it anyway";
            if (auto && next > 0 && round == 0) { run.why += cap(reason) + ", so " + lc(MarketBrain.name(next)) + " instead. "; id = next; continue; }
            red(p, reason + "." + (next > 0 ? " Try: ::afk alch " + lc(MarketBrain.name(next)) + " (+" + profit(p, s, next) + " gp a cast)." : ""));
            return null;
        }
        // 7) the player's daily cap (R3): no rung avoids it
        long done = alchedToday(key(p));
        if (done + alchValue(id, s.high()) > dailyCap()) {
            red(p, capMsg(done) + " Superheating makes no coins.");
            return null;
        }
        run.spell = s; run.item = id; run.castsLeft = count > 0 ? Math.min(count, maxCasts()) : maxCasts();
        if (!run.why.isEmpty()) {
            green(p, run.why.trim());
            System.out.println("[AfkMagic] fallback " + p.getUsername() + " alch" + (low ? " low" : "") + (want.isEmpty() ? "" : " '" + want + "'") + " -> " + s.key + " " + lc(MarketBrain.name(id)) + ": " + run.why.trim());
        }
        return run;
    }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    static String capMsg(long done) { return "today's alch limit is reached (" + gp(done) + " of " + gp(dailyCap()) + " gp); it resets at 00:00 UTC."; }

    /** Wields a staff from the pack or bank into the empty weapon slot (never swaps: the caller checked the slot). */
    static boolean wield(Player p, int staff) {
        try {
            Item it = new Item(staff);
            boolean fromPack = p.getInventory().getAmount(staff) > 0;
            if (!(fromPack ? p.getInventory().remove(it) : p.getBank().remove(it))) return false;
            if (p.getEquipment().add(it, -1, true, false)) { try { p.getEquipment().refresh(); } catch (Throwable ignored) { } return true; }
            if (fromPack) p.getInventory().add(it); else p.getBank().add(it);
        } catch (Throwable t) { System.out.println("[AfkMagic] wield " + staff + " failed for " + p.getUsername() + ": " + t); }
        return false;
    }

    // ------------------------------------------------------------------ superheat (M0: Magic 43, 4 fire + 1 nature, 53 xp; the bar's Smithing level)

    static final int COAL = 453;
    /** Bars superheat makes from the bank, best first (Runner.BAR_ORDER: no rune bar). */
    static Bar[] bars() { return AfkTasks.Runner.BAR_ORDER; }
    /** Mining jobs superheat can follow: single-ore bars only (Q9), best first. {task, bar}. */
    static Object[][] heatRocks() { return new Object[][]{{AfkTasks.Task.GOLD, Bar.GOLD}, {AfkTasks.Task.SILVER, Bar.SILVER}, {AfkTasks.Task.IRON, Bar.IRON}}; }

    static AfkTasks.Rec heatRec(AfkTasks.Process pr, Bar b, Spell s) {
        Item[] ores = b.getOres();
        int[] in = new int[ores.length], amt = new int[ores.length];
        for (int k = 0; k < ores.length; k++) { in[k] = ores[k].getId(); amt[k] = Math.max(1, ores[k].getAmount()); }
        return new AfkTasks.Rec(pr, b.getProduct().getId(), 1, b.getLevel(), in, amt, s.xp, b.getProduct().getName());
    }
    static Bar barMaking(int product) { for (Bar b : bars()) if (b.getProduct().getId() == product) return b; return null; }
    static String barName(Bar b) { return lc(b.getProduct().getName()).replace(" bar", ""); }
    static Bar barNamed(String want) {
        String w = lc(want).replace("bars", "").replace("bar", "").replace("adamantite", "adamant").trim();
        if (w.isEmpty()) return null;
        for (Bar b : bars()) if (barName(b).equals(w) || lc(b.getProduct().getName()).replace("adamantite", "adamant").startsWith(w)) return b;
        return null;
    }
    static int sets(Container c, Bar b) {
        int n = Integer.MAX_VALUE;
        for (Item o : b.getOres()) n = Math.min(n, c.getAmount(o.getId()) / Math.max(1, o.getAmount()));
        return n;
    }
    /** The best bar the bank holds ore for at this Smithing level, or null. */
    static Bar bestBankBar(Player p, int smith) { for (Bar b : bars()) if (b.getLevel() <= smith && sets(p.getBank(), b) > 0) return b; return null; }
    static Bar rockBar(AfkTasks.Task t) { for (Object[] r : heatRocks()) if (r[0] == t) return (Bar) r[1]; return null; }
    /** The best mining job whose ore the player can mine and superheat into a bar, or null. */
    static AfkTasks.Task bestHeatRock(Player p) {
        int mine = p.getSkills().getLevel(Skills.MINING), smith = p.getSkills().getLevel(Skills.SMITHING);
        for (Object[] r : heatRocks()) {
            Bar b = (Bar) r[1];
            if (b.getLevel() <= smith && AfkTasks.Runner.oreLevel(b.getOres()[0].getId()) <= mine) return (AfkTasks.Task) r[0];
        }
        return null;
    }
    static AfkTasks.Task smeltTask(Bar b) {
        AfkTasks.Task t = AfkTasks.WORDS.get(barName(b) + " bars");
        return t != null && t.kind == AfkTasks.Kind.SMELT ? t : null;
    }

    /** The job without Magic the ladder offers (and auto takes): bars at the furnace, else just mining. {command words, Run}. */
    static Object[] furnaceAlt(Player p, AfkTasks.Task task, Bar bar, boolean loop) {
        Run alt = new Run();
        if (loop) {
            AfkTasks.Task st = bar == null ? null : smeltTask(bar);
            if (st == null) return null;
            alt.task = st; alt.pr = AfkTasks.Process.NONE;
            return new Object[]{"::afk smelt " + barName(bar), alt, "smelting " + barName(bar) + " bars at the furnace"};
        }
        alt.task = task;
        if (AfkTasks.barToMineFor(p, task) != null) { alt.pr = AfkTasks.Process.SMELT; return new Object[]{"::afk " + AfkTasks.wordFor(task) + " then smelt", alt, "smelting at the furnace"}; }
        alt.pr = AfkTasks.Process.NONE;
        return new Object[]{"::afk " + AfkTasks.wordFor(task), alt, "just mining " + lc(task.label)};
    }
    /** auto: take the job without Magic, say why; else refuse naming it. */
    static Run stepOff(Player p, Object[] alt, boolean auto, String reason, String train) {
        if (auto && alt != null) {
            Run a = (Run) alt[1];
            a.why = reason + "; " + alt[2] + " instead.";
            green(p, a.why);
            System.out.println("[AfkMagic] fallback " + p.getUsername() + " superheat -> " + a.task.key + " " + a.pr.word + ": " + reason);
            return a;
        }
        red(p, reason + "." + (alt != null ? " Try: " + alt[0] + (auto ? "" : ", or the word auto") : "") + (train != null ? (alt != null ? ", or " : " Try: ") + train : "") + ".");
        return null;
    }

    /**
     * ::afk superheat [bar] [auto] (bank loop) or ::afk <ore> then superheat [auto] (while mining). Returns the Magic job,
     * a job without Magic the ladder chose (spell null, auto only), or null after the refusal.
     */
    static Run resolveHeat(Player p, AfkTasks.Task task, String want, boolean auto, int count) {
        if (!on()) { red(p, "Magic AFK jobs are off on this world."); return null; }
        Spell s = SPELLS.get("superheat");
        if (s == null) { red(p, "no superheat row in afk-spells.json on this world."); return null; }
        boolean loop = task == AfkTasks.Task.BANK_LOOP;
        int smith = p.getSkills().getLevel(Skills.SMITHING), mine = p.getSkills().getLevel(Skills.MINING);
        Run run = new Run(); run.auto = auto; run.task = task;
        Bar bar;
        if (loop) { // bars from the bank (Q9: steel from banked coal)
            bar = barNamed(want);
            if (!want.isEmpty() && bar == null) { red(p, "no bar called '" + want + "'; superheat makes bronze, iron, silver, steel, gold, mithril or adamant bars."); return null; }
            Bar lower = bestBankBar(p, smith);
            if (bar != null && (bar.getLevel() > smith || sets(p.getBank(), bar) == 0)) {
                String reason = bar.getLevel() > smith ? barName(bar) + " bars need Smithing " + bar.getLevel() + " (you have " + smith + ")" : "no ore for " + barName(bar) + " bars in your bank";
                if (!auto || lower == null) { red(p, reason + "." + (lower != null ? " Try: ::afk superheat " + barName(lower) + ", or the word auto." : "")); return null; }
                run.why += cap(reason) + ", so " + barName(lower) + " bars instead. ";
                bar = lower;
            }
            if (bar == null) bar = lower;
            if (bar == null) { red(p, "nothing in your bank to superheat at Smithing " + smith + " (ore for bronze, iron, silver, steel, gold, mithril or adamant bars)."); return null; }
        } else { // while mining: iron, silver or gold, cast on the spot when the pack is full
            bar = task == AfkTasks.Task.BEST_ROCK ? null : rockBar(task);
            boolean fits = bar != null && bar.getLevel() <= smith && AfkTasks.Runner.oreLevel(bar.getOres()[0].getId()) <= mine;
            if (!fits) {
                AfkTasks.Task alt = bestHeatRock(p);
                String reason = bar == null ? (task == AfkTasks.Task.BEST_ROCK ? "superheat while mining needs iron, silver or gold ore" : "superheat while mining makes iron, silver or gold bars, not from " + lc(task.label))
                        : bar.getLevel() > smith ? barName(bar) + " bars need Smithing " + bar.getLevel() + " (you have " + smith + ")" : lc(task.label) + " needs Mining " + AfkTasks.Runner.oreLevel(bar.getOres()[0].getId()) + " (you have " + mine + ")";
                if (alt != null && auto) { run.why += cap(reason) + ", so superheating " + barName(rockBar(alt)) + " instead (Mining " + mine + ", Smithing " + smith + "). "; task = alt; bar = rockBar(alt); run.task = alt; }
                else if (alt != null) { red(p, reason + ". Try: ::afk " + AfkTasks.wordFor(alt) + " then superheat, or the word auto."); return null; }
                else {
                    Run a = new Run(); a.task = task; a.pr = AfkTasks.Process.NONE;
                    return stepOff(p, new Object[]{"::afk " + AfkTasks.wordFor(task), a, "just mining " + lc(task.label)}, auto, cap(reason) + " at Mining " + mine + " / Smithing " + smith, null);
                }
            }
        }
        // Magic level: superheat has no lower rung; the furnace (or plain mining) is the job without Magic
        if (magic(p) < s.level) return stepOff(p, furnaceAlt(p, task, bar, loop), auto, "Superheat needs Magic " + s.level + " (you have " + magic(p) + ")", "train Magic to " + s.trainTo + " first");
        // staff for the fire runes, as for alchemy
        int sr = shortRune(p, s, false);
        if (sr >= 0 && s.runes[sr][0] == 554 && !staffCovers(p, 554)) {
            int staff = staffInStock(p, 554);
            if (staff > 0 && p.getEquipment().get(3) == null) {
                if (!auto) { red(p, "superheat needs fire runes; you have a " + lc(MarketBrain.name(staff)) + ". Wield it, or add the word auto and I'll wield it."); return null; }
                if (wield(p, staff)) run.why += "Wielded your " + lc(MarketBrain.name(staff)) + " for the fire runes. ";
                sr = shortRune(p, s, false);
            }
        }
        if (sr >= 0) {
            int rid = s.runes[sr][0];
            Object[] alt = furnaceAlt(p, task, bar, loop);
            if (auto && alt != null) return stepOff(p, alt, true, "No " + s.runeNames[sr] + "s", null);
            red(p, "superheat: you need " + s.runes[sr][1] + " " + s.runeNames[sr] + (s.runes[sr][1] > 1 ? "s" : "") + " a cast in your bank or pack" + (STAFFS.containsKey(rid) ? " (a wielded staff counts)" : "") + "."
                    + (alt != null ? " Try: " + alt[0] + ", or the word auto." : "") + " Buying runes (cap 2,000 gp) comes in phase 2." + AfkTasks.shopHint(p, AfkTasks.ids(rid)));
            return null;
        }
        run.spell = s; run.item = bar.getProduct().getId(); run.want = barName(bar);
        run.castsLeft = count > 0 ? Math.min(count, maxCasts()) : maxCasts();
        if (!loop && shortRune(p, s, true) >= 0) run.why += "Runes come from your bank at each trip: the first load is banked as ore unless your pack holds them. ";
        if (!run.why.isEmpty()) {
            green(p, run.why.trim());
            System.out.println("[AfkMagic] fallback " + p.getUsername() + " superheat" + (want.isEmpty() ? "" : " '" + want + "'") + " -> " + task.key + " " + barName(bar) + ": " + run.why.trim());
        }
        return run;
    }

    /**
     * Runner.deposit on a mining trip (superheat while mining): book, check the stops, then runes for a pack of ore.
     * No runes left in the bank: the furnace (or plain mining) takes over for the rest of the run, said once.
     */
    static void topUp(AfkTasks.Runner r) {
        Run m = r.magic; Player p = r.p;
        settle(r);
        String stop = stopReason(r);
        if (stop != null) { r.finish(stop); return; }
        int sr = shortRune(p, m.spell, false);
        if (sr >= 0) {
            Object[] alt = furnaceAlt(p, r.task, barMaking(m.item), false);
            Run a = (Run) alt[1];
            econ(r, "out of " + m.spell.runeNames[sr] + "s");
            green(p, "no " + m.spell.runeNames[sr] + "s left in your bank: " + alt[2] + " instead (" + m.casts + " superheated).");
            System.out.println("[AfkMagic] fallback " + p.getUsername() + " superheat -> " + a.pr.word + " mid-run: out of " + m.spell.runeNames[sr] + "s");
            r.chain(a.pr, "");
            return;
        }
        int stacks = 0;
        for (int[] q : m.spell.runes) if (!staffCovers(p, q[0]) && p.getInventory().getAmount(q[0]) == 0) stacks++;
        int n = Math.min(Math.min(Math.max(0, p.getInventory().freeSlots() - stacks), m.castsLeft), runeCasts(p, m.spell));
        for (int[] q : m.spell.runes) {
            if (staffCovers(p, q[0])) continue;
            int need = q[1] * n - p.getInventory().getAmount(q[0]);
            if (need > 0) take(p, q[0], need);
        }
        econ(r, "trip");
    }

    // ------------------------------------------------------------------ the Runner's side

    /** Runner.chain: takes the resolved job (or null: the Runner refuses at the first step). */
    static void attach(AfkTasks.Runner r) {
        r.magic = PENDING.remove(key(r.p));
        if (r.magic == null) return;
        r.magic.coinsSeen = r.p.getInventory().getAmount(COINS);
        r.magic.xpSeen = r.p.getSkills().getExperience(Skills.MAGIC);
    }

    /** The step's recipe for what {@code c} holds: one alch target a cast. */
    static AfkTasks.Rec rec(AfkTasks.Runner r, AfkTasks.Process pr, Container c) {
        Run m = r.magic;
        if (m == null || m.spell == null) return null;
        if (m.spell.alch()) return c.getAmount(m.item) > 0 ? new AfkTasks.Rec(pr, COINS, alchValue(m.item, m.spell.high()), m.spell.level, AfkTasks.ids(m.item), AfkTasks.ids(1), m.spell.xp, MarketBrain.name(m.item)) : null;
        Bar b = barMaking(m.item);
        if (b == null) return null;
        AfkTasks.Rec rec = heatRec(pr, b, m.spell);
        return rec.setsIn(c) > 0 ? rec : null;
    }

    /** Runner.keep: the spell's runes never leave the pack at the booth (R7). */
    static boolean keeps(AfkTasks.Runner r, int id) { return r.magic != null && r.magic.spell != null && isRune(r.magic.spell, id); }

    /** Books the last cast: Magic xp rose (R6), or (R6 second signal) the spell's runes left the pack, and the coins it made (R3, R10). */
    static void settle(AfkTasks.Runner r) {
        Run m = r.magic; Player p = r.p;
        double xp = p.getSkills().getExperience(Skills.MAGIC);
        if (m.pending) {
            boolean cast = xp > m.xpSeen;
            if (!cast && m.runesAt > 0 && r6() && runesHeld(p, m.spell) < m.runesAt) { // an xp lock or the 200m cap is not a failed cast
                cast = true;
                if (!m.r6Told) { m.r6Told = true; System.out.println("[AfkMagic] R6 " + p.getUsername() + " " + m.spell.key + ": cast counted on runes used (no Magic xp)"); }
            }
            if (cast) m.stall = 0; else m.stall++;
            m.pending = false; m.runesAt = -1;
        }
        m.xpSeen = xp;
        if (m.spell.alch()) {
            int c = p.getInventory().getAmount(COINS);
            if (c > m.coinsSeen) { long g = c - m.coinsSeen; m.coinsMade += g; addAlched(key(p), g); }
            m.coinsSeen = c;
        }
    }

    /** R6 second signal: the pack's count of the spell's runes that no wielded staff covers. */
    static int runesHeld(Player p, Spell s) { int n = 0; for (int[] q : s.runes) if (!staffCovers(p, q[0])) n += p.getInventory().getAmount(q[0]); return n; }
    /** R6 second signal on unless -Dafk.magic.r6=false or the file data/afk.magic.r6.off (read only when a cast gave no xp). */
    static boolean r6() {
        try { return !"false".equalsIgnoreCase(System.getProperty("afk.magic.r6", "true")) && !new java.io.File(core.ServerConstants.DATA_PATH, "afk.magic.r6.off").exists(); } catch (Throwable t) { return true; }
    }

    /** Why the job must end now, or null. */
    static String stopReason(AfkTasks.Runner r) {
        Run m = r.magic;
        if (!on()) return "Magic AFK jobs were switched off on this world.";
        if (m.stall >= 2) return word(m) + ": cast failed twice (" + m.casts + " cast).";
        if (m.castsLeft <= 0) return word(m) + ": " + m.casts + " casts done.";
        if (m.spell.alch()) {
            long done = alchedToday(key(r.p));
            if (done + alchValue(m.item, m.spell.high()) > dailyCap()) return cap(capMsg(done));
        }
        return null;
    }
    static String word(Run m) { return m.spell.alch() ? "alch" : "superheat"; }

    /** The Runner's process step (State.SMELTING): one cast on the next target in the pack. */
    static void castOne(AfkTasks.Runner r, AfkTasks.Rec rec) {
        Run m = r.magic; Player p = r.p;
        if (m == null) { r.finish("The Magic job lost its spell; start it again."); return; }
        settle(r);
        String stop = stopReason(r);
        if (stop != null) { r.finish(stop); return; }
        Item target = r.packItem(target(rec));
        if (target == null) { r.afterProcess(); return; }
        if (shortRune(p, m.spell, true) >= 0) { r.afterProcess(); return; } // the bank trip restocks, or ends it naming the rune
        int runes0 = runesHeld(p, m.spell); // R6 second signal
        int done = sim() ? castSim(r, rec, target) : castReal(p, m.spell, target);
        if (done < 0) { r.finish("Magic AFK can't cast on this server build (the spell code wasn't found); nothing was used."); return; }
        if (done == 0) { m.stall++; r.wait = m.spell.delay; return; } // the server refused (its message says why); twice ends the run
        m.pending = true; m.runesAt = runes0; m.casts++; m.castsLeft--; m.itemsUsed++;
        for (int[] q : m.spell.runes) if (!staffCovers(p, q[0])) m.runesUsed += q[1];
        r.made = m.casts; r.collected++;
        r.last = m.spell.name.toLowerCase(Locale.ROOT) + " " + lc(rec.name);
        if (r.overlay != null) { try { r.overlay.setAmount(m.casts); } catch (Throwable ignored) { } }
        r.wait = m.spell.delay;
    }
    /** The item the spell is cast on: the recipe's first input that isn't coal (superheat on the iron ore makes steel when 2 coal are in the pack). */
    static int target(AfkTasks.Rec rec) { for (int id : rec.in) if (id != COAL) return id; return rec.in[0]; }

    /** Runner.deposit for a Magic bank loop: book, check the stops, take the next trip (R7 sizing), start. */
    static void trip(AfkTasks.Runner r) {
        Run m = r.magic; Player p = r.p;
        if (m == null) { r.finish("The Magic job lost its spell; start it again."); return; }
        settle(r);
        String stop = stopReason(r);
        if (stop != null) { r.finish(stop); return; }
        AfkTasks.Rec rec = rec(r, r.process, p.getBank());
        if (rec == null) { r.finish(word(m) + ": no " + lc(MarketBrain.name(m.item)) + " left in your bank (" + m.casts + " cast)."); return; }
        int sr = shortRune(p, m.spell, false);
        if (sr >= 0) { r.finish(word(m) + ": out of " + m.spell.runeNames[sr] + "s (" + m.casts + " cast). Buying runes comes in phase 2." + AfkTasks.shopHint(p, AfkTasks.ids(m.spell.runes[sr][0]))); return; }
        int stacks = 0;
        for (int[] q : m.spell.runes) if (!staffCovers(p, q[0]) && p.getInventory().getAmount(q[0]) == 0) stacks++;
        if (m.spell.alch() && p.getInventory().getAmount(COINS) == 0) stacks++; // R7: one slot stays for the coins
        int slots = p.getInventory().freeSlots() - stacks;
        int n = Math.min(Math.min(slots / Math.max(1, rec.perSet()), rec.setsIn(p.getBank())), Math.min(m.castsLeft, runeCasts(p, m.spell)));
        if (n <= 0) { r.finish(word(m) + ": no room in the pack for a cast (" + p.getInventory().freeSlots() + " free slots)."); return; }
        for (int k = 0; k < rec.in.length; k++) if (!take(p, rec.in[k], rec.inAmt[k] * n)) { r.finish(word(m) + ": the bank wouldn't give up the " + lc(MarketBrain.name(rec.in[k])) + "."); return; }
        for (int[] q : m.spell.runes) {
            if (staffCovers(p, q[0])) continue;
            int need = q[1] * n - p.getInventory().getAmount(q[0]);
            if (need > 0 && !take(p, q[0], need)) { r.finish(word(m) + ": the bank wouldn't give up the runes."); return; }
        }
        r.last = "took " + n + " casts for " + m.spell.name.toLowerCase(Locale.ROOT);
        if (!m.told) { m.told = true; green(p, "took " + n + " " + lc(MarketBrain.name(rec.in[0])) + " and the runes for " + n + " casts from your bank (" + m.spell.name.toLowerCase(Locale.ROOT) + ")."); }
        econ(r, "restock");
        if (!r.startProcess()) { r.finish("Couldn't start " + m.spell.name.toLowerCase(Locale.ROOT) + " with what came out of the bank."); return; }
        r.wait = 2;
    }

    /** Bank -> pack: stackables in one stack, the rest one per slot. */
    static boolean take(Player p, int id, int n) {
        if (n <= 0) return true;
        if (p.getBank().getAmount(id) < n || !p.getBank().remove(new Item(id, n))) return false;
        if (stackable(id)) p.getInventory().add(new Item(id, n));
        else for (int i = 0; i < n; i++) p.getInventory().add(new Item(id));
        return true;
    }

    /** Runner.finish: the run's last ECON line. */
    static void end(AfkTasks.Runner r, String why) {
        if (r.magic == null || r.magic.spell == null) return;
        settle(r);
        econ(r, "end: " + why);
    }

    /** "[AfkMagic] ECON <user> <spell> <item> casts= coins+= runes-= items-= player_alched_today= total=" (soak.sh greps ECON). */
    static void econ(AfkTasks.Runner r, String when) {
        Run m = r.magic;
        System.out.println("[AfkMagic] ECON " + r.p.getUsername() + " " + m.spell.key + " " + lc(MarketBrain.name(m.item)).replace(' ', '_') + " casts=" + m.casts + " coins+=" + m.coinsMade
                + " runes-=" + m.runesUsed + " items-=" + m.itemsUsed + " player_alched_today=" + alchedToday(key(r.p)) + " player_alched_total=" + Ledger.PLAYER_ALCHED + " (" + when + ")");
    }

    // ------------------------------------------------------------------ the cast (M0: the server's own code)

    static int api;  // 0 not looked up, 1 found, -1 missing
    static Object listener;
    static Method requires, alchemize, superheat;

    /** ModernListeners (a fresh instance: defineListeners is not run, so nothing registers twice) and its methods, once. */
    static synchronized boolean api() {
        if (api != 0) return api > 0;
        try {
            Class<?> c = Class.forName("content.global.skill.magic.modern.ModernListeners");
            listener = c.getDeclaredConstructor().newInstance();
            for (Method mt : c.getMethods()) if (mt.getName().equals("requires") && mt.getParameterCount() == 4) requires = mt;
            alchemize = c.getMethod("alchemize", Player.class, Item.class, boolean.class, boolean.class);
            try { superheat = c.getMethod("access$superheat", c, Player.class, Item.class); } catch (NoSuchMethodException e) { System.out.println("[AfkMagic] no access$superheat: superheat jobs end with a message"); }
            api = requires != null ? 1 : -1;
        } catch (Throwable t) { api = -1; System.out.println("[AfkMagic] the server's spell code wasn't found: " + t); }
        System.out.println("[AfkMagic] real casts " + (api > 0 ? "via ModernListeners (requires + alchemize" + (superheat != null ? " + superheat)" : ")") : "unavailable: Magic jobs end with a message (-Dafk.magic.sim=true for trials)"));
        return api > 0;
    }

    static Item[] runeItems(Spell s) {
        Item[] out = new Item[s.runes.length];
        for (int k = 0; k < out.length; k++) out[k] = new Item(s.runes[k][0], s.runes[k][1]);
        return out;
    }

    static boolean refusedTold;
    /** 1 = cast, 0 = the server refused (requires or the spell said why), -1 = no spell code. */
    static int castReal(Player p, Spell s, Item target) {
        if (!api()) return -1;
        try { requires.invoke(listener, p, s.level, runeItems(s), new int[0]); }
        catch (InvocationTargetException e) { return 0; } // level, runes, staff or the magic delay: the server told the player
        catch (Throwable t) { System.out.println("[AfkMagic] requires failed: " + t); return -1; }
        try {
            if (s.alch()) alchemize.invoke(listener, p, target, s.high(), false);
            else if ("superheat".equals(s.handler) && superheat != null) superheat.invoke(null, listener, p, target);
            else return -1;
            return 1;
        } catch (InvocationTargetException e) {
            if (!refusedTold) { refusedTold = true; System.out.println("[AfkMagic] " + s.key + " threw for " + p.getUsername() + ": " + e.getCause()); }
            return 0;
        } catch (Throwable t) { System.out.println("[AfkMagic] " + s.key + " call failed: " + t); return -1; }
    }

    static boolean simTold;
    /** -Dafk.magic.sim=true only (trials, A6): the Runner does the spell itself. Never on live without the GO/NO-GO. */
    static int castSim(AfkTasks.Runner r, AfkTasks.Rec rec, Item target) {
        Player p = r.p; Spell s = r.magic.spell;
        if (!simTold) { simTold = true; System.out.println("[AfkMagic] simulated casts (afk.magic.sim): the Runner removes the runes and the item and pays out itself"); }
        if (magic(p) < s.level) return 0;
        for (int[] q : s.runes) if (!staffCovers(p, q[0]) && p.getInventory().getAmount(q[0]) < q[1]) return 0;
        for (int k = 0; k < rec.in.length; k++) if (p.getInventory().getAmount(rec.in[k]) < rec.inAmt[k]) return 0;
        for (int[] q : s.runes) if (!staffCovers(p, q[0])) p.getInventory().remove(new Item(q[0], q[1]));
        for (int k = 0; k < rec.in.length; k++) p.getInventory().remove(new Item(rec.in[k], rec.inAmt[k]));
        if (s.alch()) { int v = alchValue(rec.in[0], s.high()); if (v > 0) p.getInventory().add(new Item(COINS, v)); }
        else p.getInventory().add(new Item(rec.product, Math.max(1, rec.amount)));
        p.getSkills().addExperience(Skills.MAGIC, s.xp, true);
        p.animate(new Animation(s.alch() ? 713 : 725));
        return 1;
    }
}
