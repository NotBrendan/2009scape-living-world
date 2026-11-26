package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * W6 skills wave 3 combat lane (the design notes section 3: Prayer, Ranged, Summoning), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW6Combat <addons-src>/content/custom
 * Run it twice: bare (every flag off) and with the three -D flags set (the F case checks the statics follow the properties).
 * Per flag: the pure rule (W6Combat), and the wiring read from the source (SmartAdventurers, AfkTasks, QuestBook and
 * MarketBrain cannot class-init outside a world): each hook sits behind its own flag and train-61's expression is still
 * there for the flag-off path. The in-world checks are in the design notes.
 */
public final class TestW6Combat {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW6Combat] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW6Combat]   missing: " + p); return false; } return true; }
    static int count(String src, String part) { int n = 0; for (int i = src.indexOf(part); i >= 0; i = src.indexOf(part, i + 1)) n++; return n; }
    static boolean eq(String[] a, String... b) { return Arrays.equals(a, b); }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), at = read(dir, "AfkTasks.java"), qb = read(dir, "QuestBook.java"), mb = read(dir, "MarketBrain.java"), wf = read(dir, "W6Fight.java");

        // F: the three statics follow their properties, default off
        String[] keys = {"bots.prayer.stat", "bots.ranged.kit2", "bots.summoning.wwQuest"};
        boolean[] st = {W6Combat.PRAYER_STAT, W6Combat.KIT2, W6Combat.WW_QUEST};
        boolean follow = true; int on = 0; for (int i = 0; i < keys.length; i++) { if (st[i] != Boolean.getBoolean(keys[i])) follow = false; if (st[i]) on++; }
        check("F flags: 3 statics follow their -D properties (" + on + " on), anyOn " + W6Combat.anyOn() + ", line: " + W6Combat.line(),
                follow && W6Combat.anyOn() == (on > 0) && W6Combat.flags().split(" ").length == (on == 0 ? 1 : on) && W6Combat.line().startsWith("[W6c] on=["));

        // 1 Prayer: the prayers by style and level (13/31/34 melee; 26/44 ranged; 27/45 magic), none under 13
        check("P1 stat prayers: none under 13; melee 13 -> superhuman strength, 16 + improved reflexes, 31 ultimate, 34 + incredible reflexes",
                W6Combat.statPrayers(W6Combat.MELEE, 12).length == 0 && W6Combat.statPrayers(W6Combat.RANGED, 12).length == 0
                        && eq(W6Combat.statPrayers(W6Combat.MELEE, 13), "SUPERHUMAN_STRENGTH")
                        && eq(W6Combat.statPrayers(W6Combat.MELEE, 16), "SUPERHUMAN_STRENGTH", "IMPROVED_REFLEXES")
                        && eq(W6Combat.statPrayers(W6Combat.MELEE, 31), "ULTIMATE_STRENGTH", "IMPROVED_REFLEXES")
                        && eq(W6Combat.statPrayers(W6Combat.MELEE, 34), "ULTIMATE_STRENGTH", "INCREDIBLE_REFLEXES")
                        && eq(W6Combat.statPrayers(W6Combat.MELEE, 99), "ULTIMATE_STRENGTH", "INCREDIBLE_REFLEXES"));
        check("P2 stat prayers: ranged sharp/hawk/eagle eye at 13/26/44, magic mystic will/lore/might at 13/27/45, one prayer each",
                eq(W6Combat.statPrayers(W6Combat.RANGED, 13), "SHARP_EYE") && eq(W6Combat.statPrayers(W6Combat.RANGED, 26), "HAWK_EYE") && eq(W6Combat.statPrayers(W6Combat.RANGED, 44), "EAGLE_EYE")
                        && eq(W6Combat.statPrayers(W6Combat.MAGIC, 13), "MYSTIC_WILL") && eq(W6Combat.statPrayers(W6Combat.MAGIC, 27), "MYSTIC_LORE") && eq(W6Combat.statPrayers(W6Combat.MAGIC, 45), "MYSTIC_MIGHT"));
        boolean named = true; for (String[] row : W6Combat.STAT_NAMES) for (String n : row) if (!Arrays.asList(W6Combat.ALL_STAT).contains(n)) named = false;
        check("P3 every stat prayer is in the switch-off list (" + W6Combat.ALL_STAT.length + ")", named && W6Combat.ALL_STAT.length == 10);
        // the drain budget: only in a fight, a protecting bot keeps half its level, 1 point to stay on and 3 to come back
        check("P4 budget: off out of a fight; a plain bot prays down to 1 point and restarts at 3; under 13 never",
                !W6Combat.prayNow(true, false, 40, 40, false) && W6Combat.prayNow(true, true, 1, 40, false) && !W6Combat.prayNow(true, true, 0.5, 40, false)
                        && !W6Combat.prayNow(false, true, 2.5, 40, false) && W6Combat.prayNow(false, true, 3, 40, false) && !W6Combat.prayNow(false, true, 12, 12, false));
        check("P5 budget: a bot with a protection prayer keeps half its level (43: stops under 22.5, restarts at 24.5)",
                W6Combat.reserve(43, true) == 21.5 && W6Combat.reserve(43, false) == 0 && W6Combat.prayNow(true, true, 22.5, 43, true) && !W6Combat.prayNow(true, true, 22.4, 43, true)
                        && !W6Combat.prayNow(false, true, 24, 43, true) && W6Combat.prayNow(false, true, 24.5, 43, true));
        // altars: Lumbridge church lies between the cow field and the castle; nothing near Falador; a bad row is skipped
        int[][] tb = {{3243, 3209}, {3254, 3482}};
        check("P6 altar: under half the points and 13+ only; picked when on the way (leg <= 60, detour <= 40), smallest detour wins",
                W6Combat.needsAltar(10, 40) && !W6Combat.needsAltar(20, 40) && !W6Combat.needsAltar(0, 12)
                        && W6Combat.altarPick(3258, 3260, 3208, 3220, tb, null, 60, 40) == 0      // the east field to the castle: past the church
                        && W6Combat.altarPick(3300, 3180, 3270, 3167, tb, null, 60, 40) == -1     // Al Kharid's side: the church is over 60 tiles away
                        && W6Combat.altarPick(3240, 3500, 3253, 3421, tb, null, 60, 40) == 1      // north of Varrock to the east bank
                        && W6Combat.altarPick(2965, 3380, 2946, 3368, tb, null, 60, 40) == -1     // Falador: no altar in reach
                        && W6Combat.altarPick(3258, 3260, 3208, 3220, tb, new int[]{W6Combat.ALTAR_BAD, 0}, 60, 40) == -1);
        check("P7 altar table: " + W6Combat.ALTARS.length + " rows, a -D list parses and a bad one falls back",
                W6Combat.ALTARS.length == W6Combat.ALTAR_MISSES.length && W6Combat.altars("3243,3209; 3254,3482", tb).length == 2 && W6Combat.altars("3243,3209; 10,20", null)[1][1] == 20
                        && W6Combat.altars("x", tb) == tb && W6Combat.altars(" ", tb) == tb);
        check("P8 wiring: prayTick after prayerTick for bots on a fight; off on any walk; the altar leg in the TO_BANK tick; trip line at halt",
                all(at, "if (W6Combat.PRAYER_STAT && p.isArtificial() && task.kind == Kind.FIGHT) W6Fight.prayTick(this);",
                        "if (w6 != null && w6.on && state != State.WORK) W6Fight.off(this);",
                        "case TO_BANK: if (W6Combat.PRAYER_STAT && W6Fight.altarTick(this)) break; walk(State.BANKING); break;",
                        "W6Fight.St w6; boolean w6Only;")
                        && count(at, "if (w6 != null) W6Fight.tripEnd(this);") == 2 && count(at, "if (usePrayer) { try { p.getPrayer().reset(); } catch (Throwable ignored) { } }") == 2
                        && all(wf, "r.interact(altar, opt, IntType.SCENERY)", "W6Combat.altarPick(here.getX(), here.getY(), r.dest.getX(), r.dest.getY(), W6Combat.ALTARS, W6Combat.ALTAR_MISSES, W6Combat.ALTAR_LEG, W6Combat.ALTAR_DETOUR)",
                                "if (s == null || !s.counted || s.altarDone) return false;", "W6Combat.prayNow(s.on, fight || now - s.lastFight <= W6Combat.LINGER, pts, lvl, r.usePrayer)"));

        // 2 Ranged kit 2: the tables
        check("R1 tables: six bows and arrows, train-61's five first and unchanged; magic shortbow at 50, rune arrows at 40 from a yew bow up",
                Arrays.equals(Arrays.copyOf(W6Combat.BOWS2, 5), new int[]{841, 843, 849, 853, 857}) && W6Combat.BOWS2[5] == 861 && Arrays.equals(W6Combat.BOW_LEVEL2, new int[]{1, 5, 20, 30, 40, 50})
                        && Arrays.equals(Arrays.copyOf(W6Combat.ARROWS2, 5), new int[]{882, 884, 886, 888, 890}) && W6Combat.ARROWS2[5] == 892
                        && Arrays.equals(W6Combat.ARROW_LEVEL2, new int[]{1, 1, 5, 20, 30, 40}) && Arrays.equals(W6Combat.ARROW_MAX_BOW2, new int[]{0, 0, 1, 2, 3, 4}) && W6Combat.NO_STYLE5_BOWS == 4);
        java.util.function.IntPredicate allSale = id -> true, none = id -> false, blueOnly = id -> id == 2499 || id == 2493 || id == 2487;
        check("R2 hide: green at 40-49, blue 50, red 60, black 70 when on sale; the best tier on sale below; green when nothing is",
                W6Combat.hidePick(0, 49, allSale) == 1135 && W6Combat.hidePick(0, 50, allSale) == 2499 && W6Combat.hidePick(1, 60, allSale) == 2495 && W6Combat.hidePick(2, 70, allSale) == 2491
                        && W6Combat.hidePick(0, 99, blueOnly) == 2499 && W6Combat.hidePick(1, 99, none) == 1099 && W6Combat.hidePick(2, 99, none) == 1065);
        check("R3 hide swap: leather or a lower hide gives way to a better one; never a downgrade, never another kind of armour",
                W6Combat.hideUpgrade(1129, 1135) && W6Combat.hideUpgrade(1135, 2499) && W6Combat.hideUpgrade(2493, 2497) && !W6Combat.hideUpgrade(2503, 2499) && !W6Combat.hideUpgrade(2499, 2499)
                        && !W6Combat.hideUpgrade(1127, 2499) && !W6Combat.hideUpgrade(1135, 1129) && W6Combat.isKit2(861) && W6Combat.isKit2(892) && W6Combat.isKit2(2487) && !W6Combat.isKit2(1135) && !W6Combat.isKit2(890));
        int sd = W6Combat.topStepDown;
        check("R4 top arrow: rune only while stock covers 40 over the floor (" + W6Combat.TOP_FLOOR + "), else adamant; other tiers untouched",
                W6Combat.arrowTier(5, W6Combat.ARROWS2, 40 + W6Combat.TOP_FLOOR) == 5 && W6Combat.arrowTier(5, W6Combat.ARROWS2, 39 + W6Combat.TOP_FLOOR) == 4 && W6Combat.arrowTier(4, W6Combat.ARROWS2, 0) == 4
                        && W6Combat.arrowTier(0, W6Combat.ARROWS2, 0) == 0 && W6Combat.topStepDown == sd + 1);
        check("R5 top arrow caps: " + W6Combat.TOP_CAP + " a trip (others 200), a buy leaves the floor on the Exchange",
                W6Combat.tripCap(892, 200) == Math.min(200, W6Combat.TOP_CAP) && W6Combat.tripCap(890, 200) == 200
                        && W6Combat.takeCap(892, 500, 1000) == W6Combat.TOP_CAP && W6Combat.takeCap(892, 60, 80) == Math.max(0, Math.min(60, 80 - W6Combat.TOP_FLOOR))
                        && W6Combat.takeCap(892, 60, W6Combat.TOP_FLOOR) == 0 && W6Combat.takeCap(890, 60, 60) == 60);
        check("R6 wiring (tables): flag off the five train-61 arrays, written as before",
                all(sa, "static final int[] BOWS = W6Combat.KIT2 ? W6Combat.BOWS2 : new int[]{841, 843, 849, 853, 857};", "static final int[] BOW_LEVEL = W6Combat.KIT2 ? W6Combat.BOW_LEVEL2 : new int[]{1, 5, 20, 30, 40};",
                        "static final int[] ARROWS = W6Combat.KIT2 ? W6Combat.ARROWS2 : new int[]{882, 884, 886, 888, 890};", "static final int[] ARROW_LEVEL = W6Combat.KIT2 ? W6Combat.ARROW_LEVEL2 : new int[]{1, 1, 5, 20, 30};",
                        "static final int[] ARROW_MAX_BOW = W6Combat.KIT2 ? W6Combat.ARROW_MAX_BOW2 : new int[]{0, 0, 1, 2, 3};", "top = style5() ? BOWS.length : W6Combat.KIT2 ? W6Combat.NO_STYLE5_BOWS : BOWS.length - 1;"));
        check("R7 wiring (kit, arrows): hide and bow picks, the swap rule, the tier/cap/floor rules, each behind KIT2 with the old value in the off branch",
                all(sa, "kit.add(new int[]{4, hide ? (W6Combat.KIT2 ? W6Combat.hidePick(0, r, Brain::w6OnSale) : 1135) : 1129});", "kit.add(new int[]{7, r >= 40 ? (W6Combat.KIT2 ? W6Combat.hidePick(1, r, Brain::w6OnSale) : 1099) : 1095});",
                        "kit.add(new int[]{9, r >= 40 ? (W6Combat.KIT2 ? W6Combat.hidePick(2, r, Brain::w6OnSale) : 1065) : 1063});",
                        "boolean replace = cur == null || metal(cur) || k[0] == 3 || W6Combat.KIT2 && st == RANGED && W6Combat.hideUpgrade(cur.getId(), k[1]);",
                        "if (W6Combat.KIT2 && BOWS[b] == W6Combat.MAGIC_SHORTBOW && !w6OnSale(W6Combat.MAGIC_SHORTBOW)) b--;",
                        "if (W6Combat.KIT2) i = W6Combat.arrowTier(i, ARROWS, MarketBrain.stockOf(W6Combat.RUNE_ARROW));",
                        "Math.min(W6Combat.KIT2 ? W6Combat.tripCap(a, 200) : 200, b / v)", "(W6Combat.KIT2 ? Math.max(h + 1, W6Combat.tripCap(a, 200)) : 200) - h",
                        "if (W6Combat.KIT2) take = W6Combat.takeCap(su[0], take, MarketBrain.stockOf(su[0]));", "if (W6Combat.KIT2 && top > 0 && ARROWS[top] == W6Combat.RUNE_ARROW) top--;"));
        check("R8 wiring (arrows back): the sweep before a full pack banks; pickUpLoot keeps its full-pack return and takes only the quiver's arrow on a sweep",
                all(at, "if (W6Combat.KIT2 && W6Fight.arrowSweep(this)) return;", "if (p.getInventory().isFull() && !w6Only) return false;",
                        "if (w6Only && !(ownArrow(g) && p.getEquipment().get(13) != null && p.getEquipment().get(13).getId() == g.getId())) continue;", "if (W6Combat.KIT2 && w6 != null) w6.sweep = 0;")
                        && count(at, "if (W6Combat.KIT2) W6Fight.back(got.getAmount(), w6Only);") == 2 && all(wf, "r.w6Only = true;", "finally { r.w6Only = false; }", "if (s.sweep > 30) return false;"));

        // 3 Summoning: Wolf Whistle's weight
        check("S1 wwQuest: x" + W6Combat.WW_MULT + " for combat " + W6Combat.WW_COMBAT + "+, x1 under; with the mood boost (3x) the two make x" + W6Combat.WW_MULT + " together, never less than x1",
                W6Combat.wwFactor(W6Combat.WW_COMBAT - 1, 1.0) == 1.0 && W6Combat.wwFactor(W6Combat.WW_COMBAT, 1.0) == W6Combat.WW_MULT
                        && Math.abs(3.0 * W6Combat.wwFactor(90, 3.0) - Math.max(3.0, W6Combat.WW_MULT)) < 1e-9 && W6Combat.wwFactor(90, 100.0) == 1.0 && W6Combat.num("x", 4.0) == 4.0 && W6Combat.num("2.5", 4.0) == 2.5);
        check("S2 wiring: one weight line behind WW_QUEST after the mood line; pick's random draw unchanged; wwSeed / charms untouched",
                all(qb, "if (Summon.MOOD && s.quest == content.data.Quests.WOLF_WHISTLE) w *= wolfWhistleBoost(bot); // P79 S3",
                        "if (W6Combat.WW_QUEST && s.quest == content.data.Quests.WOLF_WHISTLE) { double f = W6Combat.wwFactor(combatOf(bot), Summon.MOOD ? wolfWhistleBoost(bot) : 1.0);",
                        "double w = weight(bot, s) * (0.8 + RNG.nextDouble() * 0.4);")
                        && !read(dir, "W6Combat.java").contains("W3Skills") && !wf.contains("W3Skills")
                        && all(sa, "if (!W3Skills.WW_SEED || !Summon.ON || bot == null) return;"));

        // G: every hook in the callers is behind a flag (or the null w6 state), the hourly line behind anyOn()
        int hooks = count(sa, "W6Combat.KIT2") + count(at, "W6Combat.KIT2") + count(at, "W6Combat.PRAYER_STAT") + count(qb, "W6Combat.WW_QUEST");
        int raw = count(sa, "W6Combat.") + count(at, "W6Combat.") + count(qb, "W6Combat.") + count(at, "W6Fight.") + count(sa, "W6Fight.") + count(qb, "W6Fight.");
        check("G guard: " + hooks + " flag tests in the three callers (" + raw + " W6 references); the hourly [W6c] line is behind anyOn()",
                hooks >= 24 && all(mb, "if (W6Combat.anyOn()) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(W6Combat.line()); } catch (Throwable ignored) { } } }"));

        System.out.println("[TestW6Combat] SUMMARY " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
