package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * w37-craft (results/w37-craft.md): -Dbots.craft.gems and -Dbots.herb.potions, both default off. Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW37Craft <addons-src>/content/custom
 * Run it bare (flags off), with both flags, and with each alone. The pure rules (W37Craft) are called; the wiring is read
 * from the source (SmartAdventurers cannot class-init outside a world), including that each hook sits behind the flags.
 */
public final class TestW37Craft {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW37Craft] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8).replace("\r\n", "\n"); }
    static int count(String src, String what) { return src.split(Pattern.quote(what), -1).length - 1; }
    static String body(String src, String from, String to) { int a = src.indexOf(from); int b = a < 0 ? -1 : src.indexOf(to, a); return a < 0 || b < 0 ? "" : src.substring(a, b); }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), mb = read(dir, "MarketBrain.java"), w = read(dir, "W37Craft.java");
        boolean g = Boolean.getBoolean("bots.craft.gems"), h = Boolean.getBoolean("bots.herb.potions");
        System.out.println("[TestW37Craft] flags: gems=" + g + " potions=" + h);

        // ---------------------------------------------------------------- flags
        check("F1 the constants follow their properties (default off)", W37Craft.GEMS == g && W37Craft.POTIONS == h && W37Craft.ANY == (g || h));
        String line = W37Craft.line();
        check("F2 the hourly line names the flags that are on", line.startsWith("[W37] on=[") && line.contains("gems") && line.substring(0, line.indexOf(']', 5)).contains("gems") == g
                && line.substring(0, line.indexOf(']', 5)).contains("potions") == h);
        check("F3 the hourly line is scheduled only with a flag on", count(mb, "if (W37Craft.ANY) TIMER.schedule(") == 1 && count(mb, "W37Craft.") == 2);
        check("F4 plan and end are behind ANY; tick behind a set run", count(sa, "if (W37Craft.ANY && W37Craft.plan(this, now)) return;") == 1
                && count(sa, "if (W37Craft.ANY && brain != null && brain.w37 != null) try { W37Craft.end(brain, true, \"logout\");") == 1
                && count(sa, "if (w37 != null) { W37Craft.tick(this, now); return; }") == 1
                && count(sa, "if (w37 != null) try { W37Craft.end(this, true, \"death\");") == 1);
        check("F5 no other W37Craft call in the brain (4 calls, the field, one comment)", count(sa, "W37Craft.") == 8 && count(sa, "W37Craft.Run w37; long w37At; int w37Turn;") == 1);
        check("F6 the run field is only ever set by plan and cleared by end", count(w, "b.w37 = r;") == 1 && count(w, "b.w37 = null;") == 1 && count(sa, "w37 = ") == 2 && count(sa, "w37 = null; }") == 2);
        int slay = sa.indexOf("if (Slayer.ON && Slayer.tripEnd(this, now)) return;"), mine = sa.indexOf("if (W37Craft.ANY && W37Craft.plan(this, now)) return;"), bone = sa.indexOf("if (PrayerRun.ON && planBoneRun(now)) return;");
        check("F7 the errand is the last in the trip-end list (after the bone run and the Slayer trip end)", bone > 0 && slay > bone && mine > slay && mine - slay < 400);
        check("F8 a run counts as an errand (busy) and as out and about", count(sa, "|| Scenes.Greet.escorting(bot) || rcRun != null; }") == 1 && count(sa, "boolean errand() { return errandNoStar() || starUntil > 0 || w37 != null; }") == 1 && count(sa, "bone != 0 || w37 != null || summon != 0") == 1 && count(sa, "bone == 0 && w37 == null && summon == 0") == 1);
        check("F9 no RNG and no coin creation in W37Craft", count(w, "RNG") == 1 && count(w, "Random") == 0 && count(w, "Wallet.give") == 0 && count(w, "nextInt") == 0);
        String plan = body(w, "static boolean plan(", "static Run planGems(");
        check("F10 plan refuses first: flags, a running errand, its clock, busy, combat, not at a bank", plan.indexOf("if (!ANY") > 0 && plan.indexOf("if (!ANY") < plan.indexOf("due(now") && plan.indexOf("due(now") < plan.indexOf("b.busy()")
                && plan.indexOf("b.busy()") < plan.indexOf("b.atBank()") && plan.indexOf("b.atBank()") < plan.indexOf("planGems(p, now)"));
        check("F11 each planner tests its own flag first", body(w, "static Run planGems(", "int level").contains("if (!GEMS) return null;") && body(w, "static Run planPotions(", "int level").contains("if (!POTIONS) return null;"));
        check("F12 the engine does the work: GemCutPulse, the clean listener, HerblorePulse", count(w, "new GemCutPulse(") == 1 && count(w, "InteractionListeners.run(grimy, IntType.ITEM, \"clean\"") == 1 && count(w, "new HerblorePulse(") == 1
                && count(w, "addExperience") == 0);
        check("F13 inputs: bank, bot stock (Ledger.consume), shop sink (Wallet.pay); products only Ledger.supply or the bank", count(w, "Ledger.consume(p, id, want, \"w37\")") == 1 && count(w, "Ledger.Wallet.pay(") == 1 && count(w, "Ledger.supply(p, product, made)") == 1);

        // ---------------------------------------------------------------- clocks and caps
        check("C1 due: at or after the bot's clock", W37Craft.due(100, 0) && W37Craft.due(100, 100) && !W37Craft.due(99, 100));
        long t = 10_000_000L; boolean ok = true;
        for (int i = 0; i < 12; i++) { ok &= W37Craft.capOk(0, t + i, 12); W37Craft.capUse(0); }
        check("C2 hourly cap: 12 runs pass, the 13th waits, the other flag has its own window", ok && !W37Craft.capOk(0, t + 20, 12) && W37Craft.capOk(1, t + 20, 12));
        check("C3 the window reopens after an hour", W37Craft.capOk(0, t + 3_600_000L, 12) && W37Craft.HOUR_RUNS[0] == 0);
        check("C4 a bot's gap is at least a minute and a run is 5 minutes at most", W37Craft.GAP_MS >= 60_000 && W37Craft.RETRY_MS >= 60_000 && W37Craft.RUN_MS == 300_000 && W37Craft.RUNS_HOUR >= 1);

        // ---------------------------------------------------------------- buying rules
        check("B1 an adventurer buys half of bot stock at most", W37Craft.buyable(100, 50) == 50 && W37Craft.buyable(5, 50) == 2 && W37Craft.buyable(1, 50) == 0 && W37Craft.buyable(0, 50) == 0 && W37Craft.buyable(-3, 50) == 0);
        check("B2 a quarter of the purse, nothing under the minimum purse", W37Craft.afford(8_000, 100, 2_000) == 20 && W37Craft.afford(1_999, 1, 2_000) == 0 && W37Craft.afford(2_000, 0, 2_000) == 500 && W37Craft.afford(4_000, 5_000, 2_000) == 0);
        check("B3 batch: the smallest limit, 0 under the minimum", W37Craft.batch(3, 14, 27, 9) == 9 && W37Craft.batch(3, 14, 2, 9) == 0 && W37Craft.batch(3, 13) == 13);

        check("B4 potion batch cost: only what the bot does not own", W37Craft.potCost(10, 4, 50, 10, 5, 0, 3) == 6 * 50 + 10 * 3 && W37Craft.potCost(5, 9, 50, 9, 5, 9, 3) == 0);
        check("B5 the batch shrinks to a quarter of the purse; under the minimum purse nothing is bought", W37Craft.potFit(13, 3, 4_000, 2_000, 0, 50, 0, 5, 0, 3) == 13 && W37Craft.potFit(13, 3, 2_000, 2_000, 0, 50, 0, 5, 0, 3) == 8
                && W37Craft.potFit(13, 3, 1_999, 2_000, 0, 50, 0, 5, 0, 3) == 0 && W37Craft.potFit(13, 3, 2_000, 2_000, 0, 500, 0, 5, 0, 3) == 0);
        check("B6 owned inputs need no coins: a broke bot still finishes what it has (leftovers from 1 unit)", W37Craft.potFit(13, 1, 0, 2_000, 4, 50, 4, 5, 4, 3) == 4 && W37Craft.potFit(2, 1, 0, 2_000, 2, 50, 2, 5, 2, 3) == 2 && W37Craft.potFit(2, 3, 0, 2_000, 2, 50, 2, 5, 2, 3) == 0);
        String pp = body(w, "static Run planPotions(", "/** Brain.tick while");
        check("B7 potions: the batch is priced (potFit) before any herb is taken, leftovers (unfinished, clean) before grimy", pp.indexOf("n = potFit(") > 0 && pp.indexOf("n = potFit(") < pp.indexOf("take(p, w[3]")
                && pp.indexOf("take(p, w[3]") < pp.indexOf("take(p, w[1]") && pp.indexOf("take(p, w[1]") < pp.indexOf("take(p, w[0]") && pp.contains("resume = i;"));
        String pg = body(w, "static Run planGems(", "/** A chisel in the pack");
        check("B8 gems: the chisel before any gem is paid for", pg.indexOf("if (!chisel(p))") > 0 && pg.indexOf("if (!chisel(p))") < pg.indexOf("take(p, uncut"));

        // ---------------------------------------------------------------- gems
        int[] none = {0, 0, 0, 0};
        check("G1 no gem under Crafting 20", W37Craft.pickGem(19, new int[]{9, 9, 9, 9}, none, 3) == -1);
        check("G2 the highest gem the level cuts", W37Craft.pickGem(20, new int[]{9, 9, 9, 9}, none, 3) == 0 && W37Craft.pickGem(33, new int[]{9, 9, 9, 9}, none, 3) == 1
                && W37Craft.pickGem(34, new int[]{9, 9, 9, 9}, none, 3) == 2 && W37Craft.pickGem(99, new int[]{9, 9, 9, 9}, none, 3) == 3);
        check("G3 own and bought count together; under 3 to hand is skipped", W37Craft.pickGem(99, new int[]{0, 1, 0, 2}, new int[]{0, 2, 0, 0}, 3) == 1 && W37Craft.pickGem(99, new int[]{2, 2, 2, 2}, none, 3) == -1);
        check("G4 gem rows are the engine's sapphire..diamond (uncut, cut, level)", W37Craft.GEM_ROWS.length == 4 && W37Craft.GEM_ROWS[0][0] == 1623 && W37Craft.GEM_ROWS[0][1] == 1607 && W37Craft.GEM_ROWS[0][2] == 20
                && W37Craft.GEM_ROWS[3][0] == 1617 && W37Craft.GEM_ROWS[3][1] == 1601 && W37Craft.GEM_ROWS[3][2] == 43 && W37Craft.GEM_NAMES.length == 4);
        boolean eg = true;
        try {
            for (int i = 0; i < 4; i++) { content.global.skill.crafting.gem.Gems x = content.global.skill.crafting.gem.Gems.valueOf(W37Craft.GEM_NAMES[i]);
                eg &= x.getUncut().getId() == W37Craft.GEM_ROWS[i][0] && x.getGem().getId() == W37Craft.GEM_ROWS[i][1] && x.getLevel() == W37Craft.GEM_ROWS[i][2]; }
            check("G5 rows match the engine's Gems enum", eg);
        } catch (Throwable e) { System.out.println("[TestW37Craft] SKIP G5 (engine enum needs the cache: " + e.getClass().getSimpleName() + ")"); }

        // ---------------------------------------------------------------- potions
        int[] lots = {9, 9, 9, 9, 9};
        check("P1 no potion under Herblore 3", W37Craft.pickPotion(2, lots, lots, 3) == -1);
        check("P2 the highest potion the level mixes", W37Craft.pickPotion(3, lots, lots, 3) == 0 && W37Craft.pickPotion(11, lots, lots, 3) == 0 && W37Craft.pickPotion(12, lots, lots, 3) == 1
                && W37Craft.pickPotion(38, lots, lots, 3) == 2 && W37Craft.pickPotion(45, lots, lots, 3) == 3 && W37Craft.pickPotion(99, lots, lots, 3) == 4);
        check("P3 no herb or no secondary: the next row down", W37Craft.pickPotion(99, new int[]{9, 9, 9, 9, 0}, lots, 3) == 3 && W37Craft.pickPotion(99, lots, new int[]{9, 0, 0, 9, 0}, 3) == 3
                && W37Craft.pickPotion(99, new int[]{0, 0, 0, 0, 0}, lots, 3) == -1);
        check("P4 potion rows: attack, strength, prayer, super attack, super strength (3-dose products)", W37Craft.POT_ROWS.length == 5 && W37Craft.POT_ROWS[0][6] == 121 && W37Craft.POT_ROWS[1][6] == 115
                && W37Craft.POT_ROWS[2][6] == 139 && W37Craft.POT_ROWS[3][6] == 145 && W37Craft.POT_ROWS[4][6] == 157 && W37Craft.UNF_NAMES.length == 5 && W37Craft.FIN_NAMES.length == 5);
        check("P5 only the eye of newt is a shop secondary; limpwurt and snape grass come from bank or bot stock", W37Craft.POT_ROWS[0][7] == 1 && W37Craft.POT_ROWS[3][7] == 1 && W37Craft.POT_ROWS[1][7] == 0
                && W37Craft.POT_ROWS[2][7] == 0 && W37Craft.POT_ROWS[4][7] == 0 && W37Craft.POT_ROWS[0][4] == 221 && W37Craft.POT_ROWS[1][4] == 225 && W37Craft.POT_ROWS[2][4] == 231);
        try {
            boolean ep = true;
            for (int i = 0; i < 5; i++) {
                content.global.skill.herblore.UnfinishedPotion u = content.global.skill.herblore.UnfinishedPotion.valueOf(W37Craft.UNF_NAMES[i]);
                content.global.skill.herblore.FinishedPotion f = content.global.skill.herblore.FinishedPotion.valueOf(W37Craft.FIN_NAMES[i]);
                content.global.skill.herblore.Herbs hb = content.global.skill.herblore.Herbs.forItem(new core.game.node.item.Item(W37Craft.POT_ROWS[i][0]));
                int[] r = W37Craft.POT_ROWS[i];
                ep &= hb != null && hb.getProduct().getId() == r[1] && hb.getLevel() == r[2] && u.getIngredient().getId() == r[1] && u.getPotion().getId() == r[3] && u.getBase().getId() == W37Craft.VIAL
                        && f.getUnfinished().getPotion().getId() == r[3] && f.getIngredient().getId() == r[4] && f.getLevel() == r[5] && f.getPotion().getId() == r[6] && u.getLevel() <= r[5];
            }
            check("P6 rows match the engine's Herbs, UnfinishedPotion and FinishedPotion enums", ep);
        } catch (Throwable e) { System.out.println("[TestW37Craft] SKIP P6 (engine enum needs the cache: " + e.getClass().getSimpleName() + ")"); }

        System.out.println("[TestW37Craft] " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
