package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trial only (-Dtest.b25magic=true -Dsmartbots.count=0 -Dafk.alchForce=true): batch 25 Magic phase 1, the cases of
 * the design notes section 5 (A, B, D, E, F) and the design notes 4 and 7.4 (MA, MF).
 * Run twice: planner off, then -Dnav=true (the mode is printed in the totals). Optional -Dtest.b25magic.only=A1,MF1.
 * Items go in the bank (Q2); a staff counts only wielded. Solo cases (kill switch D1, MA7) flip a world-wide switch, so
 * they start after every other case has ended. One line per case:
 * "[TestB25AfkMagic] B25M <case> PASS|FAIL|SKIP ticks=<n> casts=<n> runes=<a->b> coins=<a->b> items=<a->b> mxp=<a->b> | why",
 * then "B25M TOTAL mode=... pass= fail= skip= failureFiles=a->b econ: playerAlched+=<gp> botAlched+=<gp>" (E1 is in the
 * totals: players' alchs never move Ledger.ALCHED). Cases needing a spell row the table lacks print SKIP.
 * Superheat (B, F4-F8, MF5, MF6) from the superheat row on. UNVERIFIED: the Draynor bank tile, the Varrock SE mine tile, oak shortbow 843, rune platebody 1127, mithril platebody 1121, bronze dagger 1205.
 */
public final class TestB25AfkMagic implements StartupListener {
    static void log(String s) { System.out.println("[TestB25AfkMagic] " + s); }

    static final class Human extends AIPlayer {
        List<String> msgs;
        Human(Location l) { super(l); }
        @Override public void sendMessage(String m) {
            String s = m.replaceAll("<col=[0-9a-f]+>", "");
            if (msgs == null) msgs = new ArrayList<>();
            msgs.add(s);
            System.out.println("[TestB25AfkMagic]   " + getUsername() + " <- " + s);
        }
        boolean said(String part) { if (msgs != null) for (String s : msgs) if (s.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT))) return true; return false; }
    }

    static final class Case {
        final String id; final int budget; final boolean solo; String need;
        Human h; String key; AfkTasks.Runner r;
        int ticks, phase, item, runes0, coins0, items0, firstTrip = -1, stallAt = -1;
        double mxp0;
        String verdict, why;
        boolean done, started;
        Case(String id, int budget, boolean solo, String need) { this.id = id; this.budget = budget; this.solo = solo; this.need = need; }
    }

    static final int COINS = 995, FIRE = 554, NATURE = 561, STAFF = 1387, BOW = 843, RUNE_BODY = 1127, MITH_BODY = 1121, DAGGER = 1205, SWORD = 1277;
    static final Location BANK = Location.create(3092, 3245, 0), MINE = Location.create(3287, 3366, 0); // Draynor bank floor, Varrock SE mine
    static final int PICK = 1265, IRON_ORE = 440, COAL = 453, IRON_BAR = 2351, STEEL_BAR = 2353;
    static final Map<String, Case> CASES = new LinkedHashMap<>();
    static boolean nav;

    static boolean wanted(String id) {
        String only = System.getProperty("test.b25magic.only", "").trim();
        if (only.isEmpty()) return true;
        for (String s : only.split(",")) if (s.trim().equalsIgnoreCase(id)) return true;
        return false;
    }

    static Human human(Location at) throws Exception {
        Human h = null;
        for (int i = 0; i < 5; i++) {
            h = new Human(at);
            boolean dup = false;
            for (Case c : CASES.values()) if (c.h != null && c.key.equals(h.getUsername().toLowerCase(Locale.ROOT))) dup = true;
            if (!dup) break;
        }
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(h, false);
        AfkTasks.OWNERS.add(h.getUsername().toLowerCase(Locale.ROOT));
        h.getInventory().clear(); h.getBank().clear();
        Item w = h.getEquipment().get(3); if (w != null) h.getEquipment().remove(w);
        h.setAttribute("tutorial:complete", true); // spare PC: without it Skills.addExperience clamps xp at 175 and AfkMagic reads "xp did not rise" (stall x2)
        return h;
    }
    static void level(Player p, int sk, int lv) { p.getSkills().setStaticLevel(sk, lv); p.getSkills().setLevel(sk, lv); }
    static void bank(Player p, int id, int n) { if (n > 0) p.getBank().add(new Item(id, n)); }
    static void wield(Player p, int id) { p.getEquipment().add(new Item(id), -1, true, false); }
    static int n(Player p, int id) { return p.getInventory().getAmount(id) + p.getBank().getAmount(id); }
    static double mxp(Player p) { return p.getSkills().getExperience(Skills.MAGIC); }
    static int casts(Case c) { return c.r == null || c.r.magic == null ? 0 : c.r.magic.casts; }
    static boolean running(Case c) { return c.r != null && !c.r.halted && AfkTasks.RUNNING.get(c.key) == c.r; }

    static void cmd(Case c, String line) {
        log(c.id + " ::afk " + line);
        AfkTasks.handle(c.h, ("afk " + line).split(" "));
        c.r = AfkTasks.RUNNING.get(c.key);
    }
    /** The line with 'force' added when the item loses money at today's prices (the profit guard is A3's case, not A1's). */
    static String paying(Case c, String line, int id, String spell) {
        AfkMagic.Spell s = AfkMagic.SPELLS.get(spell);
        return s != null && AfkMagic.profit(c.h, s, id) < 0 ? line + " force" : line;
    }
    static void mark(Case c, int item) { c.item = item; c.runes0 = n(c.h, NATURE); c.coins0 = n(c.h, COINS); c.items0 = n(c.h, item); c.mxp0 = mxp(c.h); }

    /** Each case: levels and bank, then the first ::afk line. */
    static void setup(Case c) throws Exception {
        if (c.need != null && !AfkMagic.SPELLS.containsKey(c.need)) { c.why = "no '" + c.need + "' row in afk-spells.json"; result(c, "SKIP"); return; }
        c.h = human("B1".equals(c.id) ? MINE : BANK); c.key = c.h.getUsername().toLowerCase(Locale.ROOT);
        Human h = c.h;
        level(h, Skills.MAGIC, 55);
        switch (c.id) {
            case "A1": bank(h, BOW, 100); bank(h, NATURE, 100); wield(h, STAFF); mark(c, BOW); cmd(c, paying(c, "alch oak shortbow x100", BOW, "alch_high")); break;
            case "A2": bank(h, BOW, 100); wield(h, STAFF); mark(c, BOW); cmd(c, "alch oak shortbow"); break;
            case "A3": bank(h, DAGGER, 20); bank(h, NATURE, 20); wield(h, STAFF); mark(c, DAGGER); cmd(c, "alch bronze dagger"); break;
            case "A4": level(h, Skills.MAGIC, 21); bank(h, BOW, 20); bank(h, NATURE, 20); bank(h, FIRE, 100); mark(c, BOW); c.runes0 = n(h, FIRE); cmd(c, paying(c, "alch oak shortbow low x20", BOW, "alch_low")); break;
            case "A5": case "MF4": bank(h, BOW, 20); bank(h, NATURE, 20); bank(h, STAFF, 1); mark(c, BOW); cmd(c, "alch oak shortbow"); break;
            case "A6": if (!AfkMagic.sim()) { c.why = "needs -Dafk.magic.sim=true (its own run)"; result(c, "SKIP"); return; }
                bank(h, BOW, 20); bank(h, NATURE, 20); wield(h, STAFF); mark(c, BOW); cmd(c, paying(c, "alch oak shortbow x20", BOW, "alch_high")); break;
            case "D1": bank(h, BOW, 20); bank(h, NATURE, 20); wield(h, STAFF); mark(c, BOW);
                System.setProperty("afk.magic", "false"); AfkMagic.checkedAt = 0; cmd(c, "alch oak shortbow"); System.setProperty("afk.magic", "true"); AfkMagic.checkedAt = 0; AfkMagic.on(); break;
            case "F1": level(h, Skills.MAGIC, 1); bank(h, BOW, 20); bank(h, NATURE, 20); wield(h, STAFF); mark(c, BOW); cmd(c, "alch oak shortbow"); break;
            case "F2": level(h, Skills.MAGIC, 30); bank(h, BOW, 20); bank(h, NATURE, 20); wield(h, STAFF); mark(c, BOW); cmd(c, paying(c, "alch oak shortbow auto x20", BOW, "alch_low")); break;
            case "F7": bank(h, BOW, 20); bank(h, NATURE, 20); mark(c, BOW); cmd(c, "alch oak shortbow"); break;
            case "F9": case "MF3": bank(h, BOW, 100); bank(h, NATURE, "F9".equals(c.id) ? 30 : 10); wield(h, STAFF); mark(c, BOW); cmd(c, paying(c, "alch oak shortbow", BOW, "alch_high")); break;
            case "MA5": bank(h, RUNE_BODY, 3); bank(h, NATURE, 10); wield(h, STAFF); mark(c, RUNE_BODY); cmd(c, "alch rune platebody"); break;
            case "MA6": bank(h, MITH_BODY, 5); bank(h, NATURE, 10); wield(h, STAFF); mark(c, MITH_BODY);
                AfkMagic.DAILY.put(c.key, new Object[]{AfkMagic.today(), AfkMagic.dailyCap() - AfkMagic.alchValue(MITH_BODY, true) - 1}); // room for exactly one cast
                cmd(c, "alch mithril platebody force"); break;
            case "MA7": bank(h, BOW, 100); bank(h, NATURE, 100); wield(h, STAFF); mark(c, BOW); cmd(c, paying(c, "alch oak shortbow", BOW, "alch_high")); break;
            case "MA8": bank(h, BOW, 30); bank(h, NATURE, 40); wield(h, STAFF); mark(c, BOW); cmd(c, paying(c, "alch oak shortbow", BOW, "alch_high")); break;
            case "MA9": bank(h, BOW, 20); bank(h, NATURE, 20); wield(h, STAFF); mark(c, BOW); cmd(c, paying(c, "alch oak shortbow x20", BOW, "alch_high")); break;
            case "MF1": level(h, Skills.MAGIC, 41); bank(h, BOW, 20); bank(h, NATURE, 20); wield(h, STAFF); mark(c, BOW); cmd(c, "alch oak shortbow"); break;
            case "MF2": level(h, Skills.MAGIC, 5); bank(h, BOW, 20); bank(h, NATURE, 20); wield(h, STAFF); mark(c, BOW); cmd(c, "alch oak shortbow auto"); break;
            case "W1": bank(h, BOW, 5); bank(h, 845, 5); bank(h, NATURE, 5); wield(h, STAFF); mark(c, BOW); cmd(c, "alch oak"); break; // R2: two names match
            case "W2": cmd(c, "logs auto"); break;
            case "B1": level(h, Skills.MAGIC, 43); level(h, Skills.MINING, 60); level(h, Skills.SMITHING, 15); h.getInventory().add(new Item(1275)); h.getInventory().add(new Item(NATURE, 25)); bank(h, NATURE, 30); wield(h, STAFF);
                mark(c, IRON_BAR); cmd(c, "iron then superheat x30"); break;
            case "B2": level(h, Skills.MAGIC, 43); level(h, Skills.SMITHING, 30); bank(h, IRON_ORE, 20); bank(h, COAL, 40); bank(h, NATURE, 20); wield(h, STAFF); mark(c, STEEL_BAR); cmd(c, "superheat steel"); break;
            case "F4": case "MF6": level(h, Skills.MAGIC, 30); level(h, Skills.MINING, 15); level(h, Skills.SMITHING, 15); h.getInventory().add(new Item(PICK)); bank(h, NATURE, 20); wield(h, STAFF); mark(c, IRON_BAR);
                cmd(c, "iron then superheat" + ("F4".equals(c.id) ? " auto" : "")); break;
            case "F5": level(h, Skills.MAGIC, 50); level(h, Skills.MINING, 15); level(h, Skills.SMITHING, 15); h.getInventory().add(new Item(PICK)); wield(h, STAFF); mark(c, IRON_BAR); cmd(c, "iron then superheat auto"); break;
            case "F6": level(h, Skills.MAGIC, 50); level(h, Skills.MINING, 20); level(h, Skills.SMITHING, 50); h.getInventory().add(new Item(PICK)); bank(h, NATURE, 20); wield(h, STAFF); mark(c, IRON_BAR); cmd(c, "mithril then superheat auto"); break;
            case "F8": level(h, Skills.MAGIC, 1); level(h, Skills.MINING, 15); level(h, Skills.SMITHING, 1); h.getInventory().add(new Item(PICK)); mark(c, IRON_BAR); cmd(c, "iron then superheat auto"); break;
            case "MF5": level(h, Skills.MAGIC, 43); level(h, Skills.MINING, 10); level(h, Skills.SMITHING, 10); h.getInventory().add(new Item(PICK)); bank(h, NATURE, 20); wield(h, STAFF); mark(c, IRON_BAR); cmd(c, "ore then superheat"); break;
            case "MF7": c.why = "the window is M4, not phase 1"; result(c, "SKIP"); return;
            default: c.why = "no setup"; result(c, "FAIL"); return;
        }
        c.started = true;
    }

    static String yes(Case c, String why) { c.why = why; return "PASS"; }
    static String no(Case c, String why) { c.why = why; return "FAIL"; }
    static String refused(Case c, String part, boolean nothingTaken) {
        if (c.r != null) return no(c, "a run started");
        if (!c.h.said(part)) return no(c, "no '" + part + "' in the refusal");
        if (nothingTaken && (n(c.h, c.item) != c.items0 || c.h.getInventory().getAmount(c.item) > 0)) return no(c, "items moved");
        return yes(c, "refused: '" + part + "'");
    }

    /** null = keep going. */
    static String judge(Case c) {
        Human h = c.h;
        if (c.ticks > c.budget) return no(c, "over budget (" + c.budget + "), state " + (c.r == null ? "-" : c.r.state));
        boolean run = running(c);
        if (run && c.r.state == AfkTasks.State.SMELTING && c.firstTrip < 0) c.firstTrip = h.getInventory().getAmount(c.item);
        switch (c.id) {
            case "A1": case "A6": case "MA9": case "F2":
                if ("MA9".equals(c.id) && casts(c) >= 1 && c.stallAt < 0) { // a row the server refuses from now on (level 99): two refusals end it
                    AfkMagic.Spell b = c.r.magic.spell, x = new AfkMagic.Spell();
                    x.key = b.key; x.name = b.name; x.handler = b.handler; x.id = b.id; x.level = 99; x.xp = b.xp; x.delay = b.delay; x.runes = b.runes; x.runeNames = b.runeNames;
                    c.r.magic.spell = x; c.stallAt = c.ticks;
                }
                if (run) return null;
                if ("MA9".equals(c.id)) return h.said("cast failed twice") && c.ticks - c.stallAt <= 40 ? yes(c, "ended " + (c.ticks - c.stallAt) + " ticks after the bad row") : no(c, "no 'cast failed twice' within 40 ticks");
                int want = "A1".equals(c.id) ? 100 : 20;
                boolean high = !"F2".equals(c.id);
                double per = AfkMagic.SPELLS.get(high ? "alch_high" : "alch_low").xp;
                if ("F2".equals(c.id) && !h.said("low alchemy instead")) return no(c, "no fallback line");
                if (casts(c) != want || c.items0 - n(h, c.item) != want || c.runes0 - n(h, NATURE) != want) return no(c, "casts " + casts(c) + ", items -" + (c.items0 - n(h, c.item)) + ", nature -" + (c.runes0 - n(h, NATURE)));
                if (n(h, COINS) - c.coins0 != (long) want * AfkMagic.alchValue(c.item, high)) return no(c, "coins +" + (n(h, COINS) - c.coins0) + " vs " + want + " x " + AfkMagic.alchValue(c.item, high));
                if (Math.abs(mxp(h) - c.mxp0 - want * per) > want * per * 0.01) return no(c, "Magic xp +" + (mxp(h) - c.mxp0));
                return yes(c, want + " casts, counts match");
            case "A2": return refused(c, "nature rune", true);
            case "A3":
                if (c.phase == 0) {
                    if (AfkMagic.profit(h, AfkMagic.SPELLS.get(AfkMagic.SPELLS.containsKey("alch_high") ? "alch_high" : "alch_low"), DAGGER) >= 0) return yes(c, "UNVERIFIED: bronze dagger pays at today's prices, nothing to refuse");
                    String v = refused(c, "loses", true); if (!"PASS".equals(v)) return v;
                    c.phase = 1; cmd(c, "alch bronze dagger force x5"); return null;
                }
                if (run) return null;
                return casts(c) == 5 ? yes(c, "refused with the gp numbers, force ran 5") : no(c, "force ran " + casts(c));
            case "A4":
                if (run) return null;
                return casts(c) == 20 && c.runes0 - n(h, FIRE) == 60 && n(h, NATURE) == 0 ? yes(c, "20 low casts, fire -60") : no(c, "casts " + casts(c) + ", fire -" + (c.runes0 - n(h, FIRE)) + ", nature left " + n(h, NATURE));
            case "A5": return refused(c, "wield it", true);
            case "MF4":
                if (c.phase == 0) { String v = refused(c, "wield it", true); if (!"PASS".equals(v)) return v; c.phase = 1; cmd(c, paying(c, "alch oak shortbow auto x3", BOW, "alch_high")); return null; }
                if (c.phase == 1) {
                    if (run) return null;
                    Item w = h.getEquipment().get(3);
                    if (casts(c) != 3 || w == null || w.getId() != STAFF) return no(c, "auto: casts " + casts(c) + ", weapon " + (w == null ? "-" : w.getId()));
                    w = h.getEquipment().get(3); h.getEquipment().remove(w); bank(h, STAFF, 1); wield(h, SWORD); c.r = null; c.phase = 2;
                    cmd(c, "alch oak shortbow auto"); return null;
                }
                if (run) return no(c, "ran with a sword wielded (no fire runes)");
                Item w2 = h.getEquipment().get(3);
                return w2 != null && w2.getId() == SWORD ? yes(c, "offered, auto wielded the staff, never swapped the sword") : no(c, "the sword was swapped");
            case "D1": return refused(c, "off on this world", true);
            case "F1": { String v = refused(c, "needs magic", true); return "PASS".equals(v) && !h.said("train magic to 21") ? no(c, "no training offer") : v; }
            case "F7": return refused(c, "fire rune", true);
            case "F9": case "MF3":
                if (run) return null;
                int nat = "F9".equals(c.id) ? 30 : 10;
                return casts(c) == nat && h.said("out of nature rune") && h.said("phase 2") ? yes(c, nat + " casts then out of nature runes") : no(c, "casts " + casts(c));
            case "MA5":
                if (c.phase == 0) {
                    String v = refused(c, "worth", true); if (!"PASS".equals(v)) return v;
                    System.clearProperty("afk.alchForce"); c.phase = 1; cmd(c, "alch rune platebody force x1"); System.setProperty("afk.alchForce", "true");
                    return c.r != null ? no(c, "force ran with afk.alchForce off") : null;
                }
                if (c.phase == 1) { c.phase = 2; cmd(c, "alch rune platebody force x1"); return c.r == null ? no(c, "force refused with afk.alchForce on") : null; }
                if (run) return null;
                return casts(c) == 1 ? yes(c, "refused, refused without afk.alchForce, ran with it") : no(c, "casts " + casts(c));
            case "MA6":
                if (run) return null;
                return casts(c) == 1 && h.said("limit is reached") ? yes(c, "stopped at the cap after 1 cast") : no(c, "casts " + casts(c));
            case "MA7":
                if (c.phase == 0 && casts(c) >= 2) { c.phase = 1; c.stallAt = c.ticks; try { new File(core.ServerConstants.DATA_PATH, "afk.magic.off").createNewFile(); } catch (Throwable e) { return no(c, "couldn't write afk.magic.off: " + e); } }
                if (run) return c.phase == 1 && c.ticks - c.stallAt > 110 ? no(c, "still running 110 ticks after afk.magic.off") : null;
                new File(core.ServerConstants.DATA_PATH, "afk.magic.off").delete(); AfkMagic.checkedAt = 0; AfkMagic.on();
                return c.phase == 1 && h.said("switched off") ? yes(c, "ended " + (c.ticks - c.stallAt) + " ticks after the file") : no(c, "ended without the kill switch");
            case "MA8":
                if (run) return null;
                return c.firstTrip == 26 && casts(c) == 30 && n(h, NATURE) == 10 && n(h, BOW) == 0 ? yes(c, "first trip 26, 30 casts, 10 nature left") : no(c, "first trip " + c.firstTrip + ", casts " + casts(c) + ", nature " + n(h, NATURE) + ", bows " + n(h, BOW));
            case "MF1":
                if (c.phase == 0) {
                    String v = refused(c, "needs magic 55", true); if (!"PASS".equals(v)) return v;
                    if (!h.said(" low") || !h.said("train magic to 55")) return no(c, "no low / training offer");
                    c.phase = 1; cmd(c, paying(c, "alch oak shortbow auto x5", BOW, "alch_low")); return null;
                }
                if (run) return null;
                return casts(c) == 5 && h.said("low alchemy instead") ? yes(c, "offered low + training, auto ran low") : no(c, "auto casts " + casts(c));
            case "MF2": { String v = refused(c, "train magic to 21", true); return v; }
            case "W1": return refused(c, "say the full name", true);
            case "B1":
                if (run && (casts(c) < 10 || c.r.state == AfkTasks.State.SMELTING)) return null;
                if (c.r == null) return no(c, "refused");
                if (c.r.process != AfkTasks.Process.SUPERHEAT || c.r.stationWalks > 0) return no(c, "process " + c.r.process + ", station walks " + c.r.stationWalks);
                return n(h, IRON_BAR) == casts(c) && c.runes0 - n(h, NATURE) == casts(c) && casts(c) >= 10 ? yes(c, casts(c) + " iron bars superheated on the spot, nature -" + casts(c)) : no(c, "bars " + n(h, IRON_BAR) + ", casts " + casts(c) + ", nature -" + (c.runes0 - n(h, NATURE)));
            case "B2":
                if (run) return null;
                return casts(c) == 20 && n(h, STEEL_BAR) == 20 && n(h, COAL) == 0 && n(h, IRON_ORE) == 0 && n(h, NATURE) == 0 ? yes(c, "20 steel bars, coal -40, iron -20, nature -20") : no(c, "casts " + casts(c) + ", steel " + n(h, STEEL_BAR) + ", coal " + n(h, COAL) + ", iron " + n(h, IRON_ORE) + ", nature " + n(h, NATURE));
            case "F4": case "F5":
                if (c.r == null) return no(c, "no run");
                return c.r.process == AfkTasks.Process.SMELT && h.said("smelting at the furnace instead") ? yes(c, "furnace fallback taken") : no(c, "process " + c.r.process);
            case "F6":
                if (c.r == null) return no(c, "no run");
                return c.r.task == AfkTasks.Task.SILVER && c.r.process == AfkTasks.Process.SUPERHEAT && h.said("superheating silver instead") ? yes(c, "silver chosen at Mining 20") : no(c, "task " + c.r.task + ", process " + c.r.process);
            case "F8":
                if (c.r == null) return no(c, "no run");
                return c.r.task == AfkTasks.Task.IRON && c.r.process == AfkTasks.Process.NONE && h.said("just mining") ? yes(c, "plain iron mining") : no(c, "task " + c.r.task + ", process " + c.r.process);
            case "MF5": return refused(c, "iron, silver or gold", true);
            case "MF6": { String v = refused(c, "then smelt", true); return "PASS".equals(v) && !h.said("train magic to 43") ? no(c, "no training offer") : v; }
            case "W2": return refused(c, "is for magic jobs", false);
            default: return no(c, "no judge");
        }
    }

    static void result(Case c, String v) {
        c.verdict = v; c.done = true;
        Player h = c.h;
        log("B25M " + c.id + " " + v + " ticks=" + c.ticks + " casts=" + casts(c)
                + (h == null ? "" : " runes=" + c.runes0 + "->" + n(h, NATURE) + " coins=" + c.coins0 + "->" + n(h, COINS) + " items=" + c.items0 + "->" + (c.item > 0 ? n(h, c.item) : 0) + " mxp=" + (int) c.mxp0 + "->" + (int) mxp(h)) + " | " + c.why);
        if (c.r != null && running(c)) AfkTasks.handle(c.h, new String[]{"afk", "stop"});
    }

    static int failureFiles() { try { return count(Failures.failDir()); } catch (Throwable t) { return -1; } }
    static int count(File d) { File[] fs = d == null ? null : d.listFiles(); int k = 0; if (fs != null) for (File f : fs) k += f.isDirectory() ? count(f) : 1; return k; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b25magic")) return;
        String[][] table = {
                {"A1", "1500", "", "alch_high"}, {"A2", "100", "", null}, {"A3", "400", "", null}, {"A4", "400", "", null}, {"A5", "100", "", "alch_high"},
                {"A6", "400", "", "alch_high"}, {"F1", "100", "", null}, {"F2", "400", "", "alch_high"}, {"F7", "100", "", "alch_high"}, {"F9", "600", "", "alch_high"},
                {"MA5", "400", "", "alch_high"}, {"MA6", "400", "", "alch_high"}, {"MA8", "600", "", "alch_high"}, {"MA9", "400", "", "alch_high"},
                {"MF1", "400", "", "alch_high"}, {"MF2", "100", "", null}, {"MF3", "400", "", "alch_high"}, {"MF4", "400", "", "alch_high"}, {"MF7", "1", "", null}, {"W1", "100", "", null}, {"W2", "100", "", null},
                {"B1", "2500", "", "superheat"}, {"B2", "400", "", "superheat"}, {"F4", "50", "", "superheat"}, {"F5", "50", "", "superheat"}, {"F6", "50", "", "superheat"},
                {"F8", "50", "", "superheat"}, {"MF5", "100", "", "superheat"}, {"MF6", "100", "", "superheat"},
                {"D1", "100", "solo", null}, {"MA7", "400", "solo", "alch_high"},
        };
        for (String[] t : table) CASES.put(t[0], new Case(t[0], Integer.parseInt(t[1]), "solo".equals(t[2]), t[3]));
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, go = -1, fail0; long botAlched0, playerAlched0; boolean soloStarted;
            @Override public boolean pulse() {
                t++;
                try {
                    if (go < 0) {
                        if (t < 150 || NavGrid.building) { if (t > 3000) { log("setup: a nav build still running at tick 3000"); return finish(); } return false; }
                        nav = NavWalker.ON; go = t; fail0 = failureFiles(); botAlched0 = Ledger.ALCHED; playerAlched0 = Ledger.PLAYER_ALCHED;
                        log("start at tick " + t + ": mode " + (nav ? "planner-on" : "planner-off") + ", spells " + AfkMagic.SPELLS.keySet() + ", sim " + AfkMagic.sim() + ", alchForce " + AfkMagic.forceOk() + ", failure files " + fail0);
                        for (Case c : CASES.values()) if (!wanted(c.id)) { c.done = true; c.verdict = null; }
                        start(false);
                        return false;
                    }
                    boolean open = false;
                    for (Case c : CASES.values()) {
                        if (c.done || !c.started) { if (!c.done) open = true; continue; }
                        c.ticks++;
                        String v = judge(c);
                        if (v != null) { result(c, v); continue; }
                        open = true;
                        if (c.ticks % 100 == 0) log(c.id + " t=" + c.ticks + " state=" + (c.r == null ? "-" : c.r.state) + " casts=" + casts(c) + " last=" + (c.r == null ? "-" : c.r.last));
                    }
                    boolean normalOpen = false;
                    for (Case c : CASES.values()) if (!c.solo && !c.done) normalOpen = true;
                    if (!normalOpen && !soloStarted) { soloStarted = true; start(true); return false; }
                    if (open) return false;
                    int pass = 0, fail = 0, skip = 0;
                    for (Case c : CASES.values()) { if ("PASS".equals(c.verdict)) pass++; else if ("FAIL".equals(c.verdict)) fail++; else if ("SKIP".equals(c.verdict)) skip++; }
                    long botMoved = Ledger.ALCHED - botAlched0, playerMoved = Ledger.PLAYER_ALCHED - playerAlched0;
                    boolean e1 = Integer.getInteger("smartbots.count", 0) > 0 || botMoved == 0;
                    log("B25M E1 " + (e1 ? "PASS" : "FAIL") + " ticks=" + (t - go) + " | players' alchs " + (botMoved == 0 ? "left Ledger.ALCHED alone" : "moved Ledger.ALCHED by " + botMoved) + " (bots: " + Integer.getInteger("smartbots.count", 0) + ")");
                    if (e1) pass++; else fail++;
                    int fail1 = failureFiles();
                    log("B25M TOTAL mode=" + (nav ? "planner-on" : "planner-off") + " pass=" + pass + " fail=" + fail + " skip=" + skip
                            + " failureFiles=" + fail0 + "->" + fail1 + (fail1 != fail0 ? " (CHANGED)" : "") + " econ: playerAlched+=" + playerMoved + " botAlched+=" + botMoved);
                    return finish();
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); return finish(); }
            }
            void start(boolean solo) {
                for (Case c : CASES.values()) {
                    if (c.done || c.solo != solo) continue;
                    try { setup(c); } catch (Throwable e) { c.why = "setup error " + e; e.printStackTrace(System.out); result(c, "FAIL"); }
                }
                // D1 flips -Dafk.magic and puts it back inside its setup, before MA7 (next in the table) starts
            }
            boolean finish() { log("DONE"); return true; }
        });
    }
}
