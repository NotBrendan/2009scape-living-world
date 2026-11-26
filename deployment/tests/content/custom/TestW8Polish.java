package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W8 wave 5 polish (the design notes section 5), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW8Polish <addons-src>/content/custom
 * Run it twice: bare (every flag off) and with every W8 -D flag set (the F case checks the statics follow the properties).
 * Per item: the pure rule (W8Polish) and the wiring read from the source (SmartAdventurers, AfkTasks and MarketBrain cannot
 * class-init outside a world): each hook sits behind its own flag and train-61's line is still there for the flag-off path.
 */
public final class TestW8Polish {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW8Polish] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW8Polish]   missing: " + p); return false; } return true; }

    static String lowDefName() { for (int n = 0; n < 1000; n++) if (W8Polish.lowDef("bot" + n)) return "bot" + n; return null; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String hm = read(dir, "HuntMap.java"), sa = read(dir, "SmartAdventurers.java"), mb = read(dir, "MarketBrain.java"), at = read(dir, "AfkTasks.java");

        // F: the statics follow their properties, default off
        String[] keys = {"bots.foodLadder", "bots.wc.power", "bots.fish.power", "bots.weaponByFoe", "bots.kitAboveRune", "bots.kit.amulets", "bots.ranged.melee", "bots.magic.enchant"};
        boolean[] st = {W8Polish.FOOD_LADDER, W8Polish.WC_POWER, W8Polish.FISH_POWER, W8Polish.WEAPON_BY_FOE, W8Polish.KIT_ABOVE_RUNE, W8Polish.AMULETS, W8Polish.RANGED_MELEE, W8Polish.ENCHANT};
        boolean follow = true; int on = 0; for (int i = 0; i < keys.length; i++) { if (st[i] != Boolean.getBoolean(keys[i])) follow = false; if (st[i]) on++; }
        check("F flags: " + keys.length + " statics follow their -D properties (" + on + " on), anyOn " + W8Polish.anyOn() + ", line: " + W8Polish.line(),
                follow && W8Polish.anyOn() == (on > 0) && W8Polish.line().startsWith("[W8] on=[") && (on > 0 || W8Polish.line().equals("[W8] on=[]")));
        check("F timer: MarketBrain prints the [W8] line on its own timer, only when a W8 flag is on",
                all(mb, "if (W8Polish.anyOn()) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(W8Polish.line()); }"));

        // 1 Hitpoints: food ladder. Rungs lobster(1) swordfish(40) monkfish(55) shark(70).
        int[] price = {150, 300, 400, 800}, ceil = {225, 450, 600, 1200}, stock = {500, 500, 500, 500};
        int[] why = new int[3];
        boolean lowNpc = W8Polish.rung(99, 20, 10_000_000, 10, price, ceil, stock, why) == -1;          // goblins: no ladder
        boolean hp99 = W8Polish.rung(99, 80, 10_000_000, 10, price, ceil, stock, why) == 3;              // rich, 99 hp: shark
        boolean hp60 = W8Polish.rung(60, 80, 10_000_000, 10, price, ceil, stock, why) == 2;              // monkfish
        boolean hp45 = W8Polish.rung(45, 80, 10_000_000, 10, price, ceil, stock, why) == 1;              // swordfish
        boolean hp20 = W8Polish.rung(20, 80, 10_000_000, 10, price, ceil, stock, why) == 0;              // lobster
        boolean poor = W8Polish.rung(99, 80, 20_000, 10, price, ceil, stock, why) == 2;                  // purse/5 = 4000: 10 sharks 8000 too dear, 10 monkfish 4000 fit
        int[] w2 = new int[3];
        boolean poor2 = W8Polish.rung(99, 80, 19_999, 10, price, ceil, stock, w2) == 1 && w2[2] == 2;   // shark, monk too dear -> swordfish
        int[] dear = {150, 300, 400, 5000}; int[] w3 = new int[3];
        boolean ceilCut = W8Polish.rung(99, 80, 10_000_000, 10, dear, ceil, stock, w3) == 2 && w3[0] == 1; // shark over its ceiling
        int[] thin = {500, 500, 500, 59}; int[] w4 = new int[3];
        boolean stockCut = W8Polish.rung(99, 80, 10_000_000, 10, price, ceil, thin, w4) == 2 && w4[1] == 1; // 59 < 10 + 50
        int[] noRef = {0, 0, 0, 0};
        boolean noCeil = W8Polish.rung(99, 80, 10_000_000, 10, price, noRef, stock, null) == -1;
        boolean broke = W8Polish.rung(99, 80, 100, 10, price, ceil, stock, null) == -1;
        check("1 ladder rule: npc<30 none, hp 99/60/45/20 -> shark/monk/sword/lobster, purse share, ceiling, stock floor, no ref = no buy",
                lowNpc && hp99 && hp60 && hp45 && hp20 && poor && poor2 && ceilCut && stockCut && noCeil && broke);
        check("1 ceiling: CEIL_X x reference, 0 without one; purse share at most a quarter",
                W8Polish.foodCeil(100) == Math.round(100 * W8Polish.FOOD_CEIL_X) && W8Polish.foodCeil(null) == 0 && W8Polish.foodCeil(0) == 0 && W8Polish.FOOD_PURSE_DIV >= 4
                        && W8Polish.ladderIndex(7946) == 2 && W8Polish.ladderIndex(333) == -1);
        check("1 wiring: buyFood calls the ladder behind FOOD_LADDER and nearGE, buys via Ledger.consume only, old order loop kept",
                all(sa, "if (W8Polish.FOOD_LADDER && nearGE(bot) && w8FoodLadder(npcLevel, want, purse)) return;",
                        "int got = Ledger.consume(bot, id, want, \"food\");",
                        "for (int[] f : SHOP_FOOD) if (f[1] >= needHeal && (long) Math.max(1, MarketBrain.value(f[0])) * want <= purse / 4) { order[n++] = f[0]; break; }")
                        && !sa.substring(sa.indexOf("boolean w8FoodLadder("), sa.indexOf("boolean w8FoodLadder(") + 2000).contains("Wallet.pay"));

        // 2 Woodcutting/Fishing power: glut + under the cap, only the willow / lure job, drops vanish, no beast store
        check("2 power rule: on+glut+low level only; weight lift only for a zeroed weight; kinds per job",
                W8Polish.power(true, true, 30, 45) && !W8Polish.power(false, true, 30, 45) && !W8Polish.power(true, false, 30, 45) && !W8Polish.power(true, true, 45, 45)
                        && W8Polish.powerWeight(true, 0, true, 25, 40) == W8Polish.POWER_WEIGHT && W8Polish.powerWeight(true, 0.8, true, 25, 40) == 0.8 && W8Polish.powerWeight(false, 0, true, 25, 40) == 0
                        && W8Polish.powerKind(true, true, true, false) == W8Polish.KIND_WC && W8Polish.powerKind(true, true, false, true) == W8Polish.KIND_FISH
                        && W8Polish.powerKind(false, true, true, false) == 0 && W8Polish.powerKind(true, false, false, true) == 0 && W8Polish.powerKind(true, true, false, false) == 0);
        int wd0 = W8Polish.wcPowerDropped, fd0 = W8Polish.fishPowerDropped; W8Polish.powerDropped(W8Polish.KIND_WC, 5); W8Polish.powerDropped(W8Polish.KIND_FISH, 3); W8Polish.powerDropped(0, 9);
        check("2 power counters: dropped items counted per kind", W8Polish.wcPowerDropped == wd0 + 5 && W8Polish.fishPowerDropped == fd0 + 3);
        check("2 power wiring: w8Power is 0 with both flags off, w5Power gains '|| w8Power != 0' (the Runner line is train-61's), drops vanish (no GroundItem), Bob.store skipped, BV river spot",
                all(sa, "final int w8Power = (W8Polish.WC_POWER || W8Polish.FISH_POWER) && job.hunt == null && job.chain == 0 && errandFor == null ? w8PowerKind(p, job.task) : 0;",
                        "AfkTasks.Runner r = new AfkTasks.Runner(p, job.task, !w5Power, w5Power ? tripMax : Math.min(tripMax, Math.max(Math.min(14, tripMax), want)), buries, true);", " || w8Power != 0; // W8: a wc/fish power trip uses mine.power's drop mode", "if (w5Power && w8Power == 0) { r.sell = false;", "if (job.task.kind == AfkTasks.Kind.FISH && w8Power == 0) {",
                        "if (w8Power == W8Polish.KIND_FISH) { spotAt = AfkTasks.BARB_FISH.at;", "if (w8Power != 0) { r.sell = false; r.cookHaul = false; r.w5Vanish = true; r.w8Power = w8Power;",
                        "if ((W8Polish.WC_POWER || W8Polish.FISH_POWER) && j.hunt == null) { int w8k")
                        && all(at, "if (w8Power != 0) { /* W8", "} else if (Bob.ON && bob != null && bob.getTicks() > 400 && !(W5aSkills.MINE_POWER && w5Vanish) && Bob.store(this) > 0) return;",
                        "if (w5Vanish && w8Power != 0) { W8Polish.powerDropped(w8Power, n); continue; }", "if (w5Vanish) { W5aSkills.powerDropped += n; continue; }",
                        "GroundItem g = GroundItemManager.create(new Item(it.getId(), n), p.getLocation(), p);"));

        // 3 Attack: weapon by foe. Weakness column, best mace row by Attack, spend cap, wiring
        int[] lv = {40, 30, 20, 5, 1, 1};
        check("3 weakness: skeletons/rock crabs/ankous crush, dragons/giants/men slash, null slash",
                W8Polish.weakness("Skeleton") == W8Polish.CRUSH && W8Polish.weakness("Rock Crab") == W8Polish.CRUSH && W8Polish.weakness("Ankou") == W8Polish.CRUSH
                        && W8Polish.weakness("Blue dragon") == W8Polish.SLASH && W8Polish.weakness("Hill Giant") == W8Polish.SLASH && W8Polish.weakness(null) == W8Polish.SLASH);
        check("3 mace rows: one per ARMOUR_TIERS row, best row by Attack and ownership, spend cap + 6 h gap",
                W8Polish.MACES.length == 6 && W8Polish.isMace(1432) && !W8Polish.isMace(1333)
                        && W8Polish.bestRow(45, lv, null) == 0 && W8Polish.bestRow(25, lv, null) == 2 && W8Polish.bestRow(1, lv, null) == 4
                        && W8Polish.bestRow(45, lv, new boolean[]{false, false, true, false, false, false}) == 2 && W8Polish.bestRow(10, lv, new boolean[6]) == -1
                        && W8Polish.maceAffordable(10_300, 2000, -W8Polish.MACE_GAP_MS, 0) && !W8Polish.maceAffordable(10_300, 2001, -W8Polish.MACE_GAP_MS, 0)
                        && !W8Polish.maceAffordable(1_000_000, 2000, 0, W8Polish.MACE_GAP_MS - 1) && !W8Polish.maceAffordable(100, 1, -W8Polish.MACE_GAP_MS, 0));
        check("3 wiring: hook behind WEAPON_BY_FOE for melee hunts, old weapon stowed (never wear()'s Exchange path), mace paid via Ledger.consume or Wallet.pay, HuntMap column",
                all(sa, "if (W8Polish.WEAPON_BY_FOE && job.hunt != null && styleOf(bot) == MELEE) try { w8WeaponByFoe(job.hunt); }",
                        "if (toPack(id) && stowWeapon() && wear(bot, id)) { W8Polish.foeSwaps++;", "int got = MarketBrain.stockOf(id) > 0 ? Ledger.consume(bot, id, 1, \"weapon\") : 0;",
                        "else if (sold(id) && Ledger.Wallet.pay(bot, price)) got = 1;", "if (fightJob(job) && styleOf(bot) != MELEE && style5()) try { styleWeaponBack(); }")
                        && all(hm, "static int weakness(Hunt h) { return h == null ? W8Polish.SLASH : W8Polish.weakness(h.name); }"));


        // 4 Defence: above rune from bot stock, per-piece share and per-bot cap, low-Defence builds
        int[][] helm = W8Polish.ABOVE_HELM; int[] hp = {900_000, 900_000, 900_000, 900_000, 60_000, 40_000}, hs = {1, 0, 0, 0, 3, 3};
        boolean k70 = W8Polish.pick(helm, 70, 70, helm.length, 10_000_000, 0, hp, hs) == 0;            // rich, Def 70: Dharok's helm
        boolean k60 = W8Polish.pick(helm, 65, 1, helm.length, 10_000_000, 0, hp, hs) == 4;             // Def 65: dragon med helm
        boolean k50 = W8Polish.pick(helm, 55, 50, helm.length, 10_000_000, 0, hp, new int[]{1, 0, 0, 0, 0, 3}) == 5; // no dragon in stock: granite
        boolean kStr = W8Polish.pick(helm, 55, 40, helm.length, 10_000_000, 0, hp, new int[]{1, 0, 0, 0, 0, 3}) == -1; // granite wants Str 50
        boolean kShare = W8Polish.pick(helm, 70, 70, helm.length, 300_000, 0, hp, hs) == 4;             // 0.3 x 299,700 < 900k: dragon
        boolean kCap = W8Polish.pick(helm, 70, 70, helm.length, 10_000_000, W8Polish.KIT_CAP - 50_000, hp, hs) == 5; // cap leaves room for granite only
        boolean kBetter = W8Polish.pick(helm, 70, 70, 4, 10_000_000, 0, hp, new int[]{0, 0, 0, 0, 3, 3}) == -1; // wears dragon: granite is no upgrade
        boolean kNoStock = W8Polish.pick(helm, 70, 70, helm.length, 10_000_000, 0, hp, new int[6]) == -1;
        check("4 kit rule: best piece its levels and stock allow, share of the purse, per-bot cap, never a downgrade, stock only",
                k70 && k60 && k50 && kStr && kShare && kCap && kBetter && kNoStock && W8Polish.KIT_SHARE <= 0.3);
        check("4 rank: empty slot and table pieces replaceable, other kit (anti-dragon shield, quest gear) kept",
                W8Polish.rankOf(helm, -1, false) == helm.length && W8Polish.rankOf(helm, 1163, true) == helm.length && W8Polish.rankOf(helm, 1149, false) == 4
                        && W8Polish.rankOf(W8Polish.ABOVE_SHIELD, 1540, false) == -1 && W8Polish.ABOVE.length == W8Polish.ABOVE_SLOTS.length);
        int pures = 0; for (int n = 0; n < 1200; n++) if (W8Polish.lowDef("bot" + n)) pures++;
        check("4 low-Defence builds: about 1 in 12 (" + pures + " of 1200), stance never defence for them, unchanged for others and with the flag off",
                pures > 60 && pures < 150 && W8Polish.lowDefStance(false, "x", "defence", 50, 40).equals("defence")
                        && (W8Polish.lowDef("bot5") ? true : W8Polish.lowDefStance(true, "bot5", "defence", 50, 40).equals("defence"))
                        && lowDefName() != null && W8Polish.lowDefStance(true, lowDefName(), "defence", 50, 40).equals("strength") && W8Polish.lowDefStance(true, lowDefName(), "defence", 50, 50).equals("attack")
                        && W8Polish.lowDefStance(true, lowDefName(), "attack", 50, 40).equals("attack"));
        check("4 wiring: kitFromExchange calls the hook behind KIT_ABOVE_RUNE (Ledger.consume only, no Wallet.pay), stance hook after the old rule",
                all(sa, "if (W8Polish.KIT_ABOVE_RUNE && styleOf(bot) == MELEE) try { got += w8KitAboveRune(); }", "if (Ledger.consume(bot, id, 1, \"kit\") != 1) { W8Polish.kitNoStock++; continue; }",
                        "} else st = def < att - 10 ? \"defence\" : str < att ? \"strength\" : RNG.nextBoolean() ? \"attack\" : \"strength\";\r\n                if (W8Polish.KIT_ABOVE_RUNE) { String w8 = W8Polish.lowDefStance(true,")
                        && !sa.substring(sa.indexOf("int w8KitAboveRune() {"), sa.indexOf("/** Kit pieces the Exchange has in stock")).contains("Wallet.pay"));


        // 5 amulets: best for the style from bot stock, a purse share, never a downgrade
        int[] mel = W8Polish.AMULET_BY_STYLE[0], ap = {120_000, 9_000, 1_500, 1_000}, as = {1, 2, 2, 2};
        check("5a amulets: rich melee takes glory, a poorer one power, worn power keeps (only glory is better), none in stock -> none, magic list has the amulet of magic",
                W8Polish.amuletPick(mel, W8Polish.amuletRank(mel, -1), 10_000_000, ap, as) == 0 && W8Polish.amuletPick(mel, mel.length, 100_300, ap, as) == 1
                        && W8Polish.amuletPick(mel, W8Polish.amuletRank(mel, 1731), 100_300, ap, as) == -1 && W8Polish.amuletRank(mel, 1731) == 1
                        && W8Polish.amuletPick(mel, mel.length, 10_000_000, ap, new int[4]) == -1 && W8Polish.amuletRank(mel, 1692) == mel.length
                        && W8Polish.AMULET_BY_STYLE[2][2] == 1727 && W8Polish.AMULET_BY_STYLE[1][2] == 1478);
        // 5b ranged trips: about 1 melee bot in 5, every third trip, hunts within Ranged + 15
        int rm = 0; String rmName = null; for (int n = 0; n < 1000; n++) if (W8Polish.rangedMelee("bot" + n)) { rm++; if (rmName == null) rmName = "bot" + n; }
        check("5b ranged.melee: about 1 in 5 (" + rm + " of 1000), every third trip, npc <= Ranged + 15, never with the flag off",
                rm > 140 && rm < 260 && rmName != null && W8Polish.rangedTrip(true, rmName, 3, 10, 25) && !W8Polish.rangedTrip(true, rmName, 4, 10, 25)
                        && !W8Polish.rangedTrip(true, rmName, 3, 10, 26) && !W8Polish.rangedTrip(false, rmName, 3, 10, 25));
        // 5c enchant: rows by Magic level, floors, hourly cap, a quarter of the purse
        int[] es = {0, 30, 30, 30}; boolean[] eg = new boolean[4];
        check("5c enchant rule: best row the level reaches with stock over 5 and no glut; qty capped by 10, stock, the hour and a quarter of the purse",
                W8Polish.enchRow(60, es, eg) == 1 && W8Polish.enchRow(30, es, eg) == 2 && W8Polish.enchRow(6, es, eg) == -1 && W8Polish.enchRow(30, es, new boolean[]{false, false, true, false}) == 3
                        && W8Polish.enchRow(30, new int[]{0, 0, 5, 6}, eg) == 3
                        && W8Polish.enchQty(30, 0, 10_000_000, 1000) == 10 && W8Polish.enchQty(8, 0, 10_000_000, 1000) == 3 && W8Polish.enchQty(30, W8Polish.ENCH_HOUR - 2, 10_000_000, 1000) == Math.min(2, 10)
                        && W8Polish.enchQty(30, 0, 4_300, 1000) == 1 && W8Polish.enchQty(30, 0, 300, 1000) == 0
                        && W8Polish.ENCH[3][0] == 1694 && W8Polish.ENCH[3][1] == 1727 && W8Polish.ENCH[0][1] == 1731);
        check("5 wiring: GE hooks behind AMULETS/ENCHANT, ranged trip and weapon back behind RANGED_MELEE, every input paid (Ledger.consume / Wallet.pay), output via Ledger.supply",
                all(sa, "if (W8Polish.AMULETS) try { got += w8Amulet(); }", "if (W8Polish.ENCHANT && styleOf(bot) == MAGIC) try { w8Enchant(); }",
                        "if (W8Polish.RANGED_MELEE && w8RangedOn) try { w8RangedBack(); }", "if (W8Polish.RANGED_MELEE && job.hunt != null && styleOf(bot) == MELEE && W8Polish.rangedTrip(true,",
                        "int got = Ledger.consume(bot, e[0], n, \"enchant\");", "Ledger.supply(bot, e[1], got);", "if (Ledger.consume(bot, list[i], 1, \"kit\") != 1) { W8Polish.amuletNone++; return 0; }"));


        // 6 Summoning heal: needs bots.summoning + bots.summoning.combat + bots.summoning.heal; bunyip row; dragon hunts at 68+
        String bob = read(dir, "Bob.java");
        boolean healWant = Boolean.getBoolean("bots.summoning") && Boolean.getBoolean("bots.summoning.combat") && Boolean.getBoolean("bots.summoning.heal");
        check("6 heal flag: on only with summoning + summoning.combat + summoning.heal (" + W8Polish.SUMMON_HEAL + "), counted in anyOn",
                W8Polish.SUMMON_HEAL == healWant && (!healWant || W8Polish.anyOn()));
        check("6 heal rule: bunyip row (engine 12029, level 68, 7 points), dragon hunts at Summoning 68+, never with the flag off, purse share",
                W8Polish.healRow(true, 12029) == W8Polish.BUNYIP_ROW && W8Polish.healRow(false, 12029) == null && W8Polish.healRow(true, 12047) == null
                        && W8Polish.BUNYIP_ROW[1] == 68 && W8Polish.BUNYIP_ROW[2] == 7
                        && W8Polish.healTrip(true, "Blue dragons", 68) && W8Polish.healTrip(true, "Black dragons (Taverley)", 90) && !W8Polish.healTrip(true, "Blue dragons", 67)
                        && !W8Polish.healTrip(true, "Hill Giants", 99) && !W8Polish.healTrip(false, "Blue dragons", 99) && !W8Polish.healTrip(true, null, 99)
                        && W8Polish.healAffordable(10_300, 1000) && !W8Polish.healAffordable(10_300, 1001));
        check("6 heal take (w21): fetched when the pack holds none and has 3 free slots, at a bank or not; never a second one, never into a full pack",
                W8Polish.healTake(0, 3) && W8Polish.healTake(0, 28) && !W8Polish.healTake(1, 28) && !W8Polish.healTake(0, 2) && !bob.contains("getAmount(id) < 1 && atBank &&"));
        check("6 heal wiring: plan after combat's eligibility and BoB checks, the row only when COMBAT_ROWS has none, single-combat summon only for the bunyip, pouch from bank or Ledger.consume",
                all(bob, "            if (r.bobPouch != 0) { CWHY[4]++; return; }\r\n            if (W8Polish.SUMMON_HEAL && w8HealPlan(r)) return;",
                        "if (!combatEligible(r)) { CWHY[0]++; return; }",
                        "if (row == null && W8Polish.SUMMON_HEAL) row = W8Polish.healRow(true, r.famPouch);",
                        "&& !(W8Polish.SUMMON_HEAL && r.famPouch == W8Polish.BUNYIP)) { C_SINGLE++; r.famNext = now + 120_000; return false; }",
                        "Ledger.consume(p, id, 1, \"summoning\") == 1",
                        "if (W8Polish.healTake(p.getInventory().getAmount(id), p.getInventory().freeSlots())) {")
                        && bob.indexOf("if (!combatEligible(r)) { CWHY[0]++; return; }") < bob.indexOf("if (W8Polish.SUMMON_HEAL && w8HealPlan(r)) return;"));


        // 7 Farming trees (rules only): seed pick, payment, next-day check inside FarmSave's file age; FarmSave's format untouched
        String fs = read(dir, "FarmSave.java");
        boolean treesWant = Boolean.getBoolean("bots.farming") && Boolean.getBoolean("bots.farming.trees");
        int[][] tr = W8Polish.TREE_ROWS;
        check("7 trees flag: needs bots.farming too (" + W8Polish.TREES + "); seed source owned > stock > none (never a shop)",
                W8Polish.TREES == treesWant && W8Polish.seedSource(1, 0) == 1 && W8Polish.seedSource(0, 3) == 2 && W8Polish.seedSource(0, 0) == 0);
        check("7 trees pick: best row the level reaches with a sourced seed; saplings untradeable so the input is the seed",
                W8Polish.treePick(tr, 50, id -> true) == 2 && W8Polish.treePick(tr, 50, id -> id == 5312) == 0 && W8Polish.treePick(tr, 14, id -> true) == -1
                        && W8Polish.treePick(W8Polish.FRUIT_ROWS, 68, id -> true) == 6 && tr[0][1] == 5312 && tr[0][2] == 5370 && W8Polish.FRUIT_ROWS[0][2] == 5496);
        long t0 = 1_000_000_000L;
        check("7 trees payment and check: paid only when the item is there in full; check the next day, after growth, inside FarmSave's 48 h",
                W8Polish.payFarmer(tr[3], 4, 6) && !W8Polish.payFarmer(tr[3], 4, 5) && !W8Polish.payFarmer(W8Polish.FRUIT_ROWS[1], 99, 99)
                        && W8Polish.checkDue(t0, 200) == t0 + W8Polish.NEXT_DAY_MS && W8Polish.checkDue(t0, 2000) == t0 + 2000 * 60_000L + W8Polish.TREE_MARGIN_MS
                        && W8Polish.saveFits(t0, W8Polish.checkDue(t0, 960)) && !W8Polish.saveFits(t0, t0 + 49 * 3_600_000L) && !W8Polish.saveFits(t0, t0)
                        && all(fs, "static final int V = 1;", "Long.getLong(\"bots.farming.trees.save.maxAgeH\", 48L)", "o.put(\"v\", V); o.put(\"name\", name); o.put(\"savedAt\", savedAt); o.put(\"site\", site); o.put(\"due\", due); o.put(\"crops\", crops);")
                        && !fs.contains("W8Polish"));

        // t63 fix 3: pay only with room; a refused add after paying gives the coins back (or the items back on the Exchange)
        boolean rc3 = W8Polish.refundCoins(300, 0, 3) == 300 && W8Polish.refundCoins(300, 1, 3) == 200 && W8Polish.refundCoins(300, 3, 3) == 0 && W8Polish.refundCoins(0, 0, 5) == 0
                && W8Polish.refundCoins(50, -2, 5) == 50 && W8Polish.refundCoins(50, 0, 0) == 0;
        String rt = sa.substring(sa.indexOf("void w8RangedTrip("), sa.indexOf("void w8RangedBack()")), en = sa.substring(sa.indexOf("void w8Enchant()"), sa.indexOf("long w8KitSpent;"));
        check("t63 fix 3: refundCoins shares the coins paid over the unplaced items; ranged bow/arrows and enchant runes need room before paying and go through w8Place " + rc3,
                rc3 && all(sa, "boolean w8Room(int id, int n) {", "boolean w8Place(int id, int n, long paid, boolean bankFirst, String what) {", "if (!ok) W8Polish.unplaced(bot, id, n, W8Polish.refundCoins(paid, 0, n), what);")
                        && all(rt, "if (!w8Room(BOWS[0], 1)) { W8Polish.noRoom++;", "if (!w8Place(BOWS[0], 1, paid, false, \"ranged bow\"))", "if (!w8Room(arrow, need)) { W8Polish.noRoom++;", "if (!w8Place(arrow, got, paid, true, \"ranged arrows\"))")
                        && all(en, "if (!w8Room(rn[0], need)) { W8Polish.noRoom++;", "if (!w8Place(rn[0], got, paid, true, \"enchant runes\"))")
                        && rt.indexOf("w8Room(arrow, need)") < rt.indexOf("Ledger.consume(bot, arrow, need") && en.indexOf("w8Room(rn[0], need)") < en.indexOf("Ledger.consume(bot, rn[0], need")
                        && !rt.contains("bot.getBank().add(new Item(arrow, got));") && !rt.contains("packOrBank(bot, new Item(BOWS[0], 1));") && !en.contains("bot.getBank().add(new Item(rn[0], got));"));

        String w8s = read(dir, "W8Polish.java"), ageExpr = "Math.max(1L, Long.getLong(\"bots.farming.trees.save.maxAgeH\", 48L)) * 3_600_000L";
        check("t63 fix 5: the trees save limit is FarmSave's maxAgeH setting (same expression in both), not a fixed 48 h",
                W8Polish.FARMSAVE_MAX_AGE_MS == Math.max(1L, Long.getLong("bots.farming.trees.save.maxAgeH", 48L)) * 3_600_000L
                        && all(w8s, "static final long FARMSAVE_MAX_AGE_MS = " + ageExpr + ";") && all(fs, "static final long MAX_AGE_MS = " + ageExpr + ";") && !w8s.contains("FARMSAVE_MAX_AGE_MS = 48 * 3_600_000L"));

        System.out.println("[TestW8Polish] " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
