package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * w13d (plain java, no world): BUG-1 (purse checks at the guide, consume charges botRef under clerk.fix2) and the two route fixes.
 * Run in four modes: {clerk.fix2 off/on} x {market.chargeCheck off/on}. Arg 0 = the addons-src content/custom dir (wiring checks).
 */
public final class TestW13d {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW13d] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static void seed(int id, int guide, int base) { MarketBrain.VALUE_CACHE.put(id, new long[]{guide, System.currentTimeMillis()}); PriceDrift.BASE.put(id, base); }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        boolean fix2 = Boolean.getBoolean("clerk.fix2"), cc = Boolean.getBoolean("market.chargeCheck");
        check("F flags: CLERK_FIX2 " + Ledger.CLERK_FIX2 + ", CHARGE_CHECK " + MarketBrain.CHARGE_CHECK, Ledger.CLERK_FIX2 == fix2 && MarketBrain.CHARGE_CHECK == cc);
        PriceDrift.LOADED = true;
        final int CRASH = 990_001, DEAR = 990_002;
        seed(CRASH, 40, 100);  // guide crashed to 40% of base: botRef = the 70% floor
        seed(DEAR, 200, 100);  // guide at 200%: botRef = the 150% ceiling
        check("P botRef clamps: 70 / 150", MarketBrain.botRef(CRASH) == 70 && MarketBrain.botRef(DEAR) == 150);
        // chargePrice mirrors Ledger.price (what consume takes)
        check("P chargePrice == Ledger.price (" + MarketBrain.chargePrice(CRASH) + ", " + MarketBrain.chargePrice(DEAR) + ")",
                MarketBrain.chargePrice(CRASH) == Ledger.price(CRASH) && MarketBrain.chargePrice(DEAR) == Ledger.price(DEAR));
        check("P chargePrice: botRef with clerk.fix2, the guide without", fix2 ? MarketBrain.chargePrice(CRASH) == 70 && MarketBrain.chargePrice(DEAR) == 150
                : MarketBrain.chargePrice(CRASH) == 40 && MarketBrain.chargePrice(DEAR) == 200);
        check("P eitherPrice: the dearer of consume and the shop's guide", MarketBrain.eitherPrice(CRASH) == (fix2 ? 70 : 40) && MarketBrain.eitherPrice(DEAR) == 200);
        check("P checkPrice/checkEither: the guide with market.chargeCheck off (old check unchanged)",
                cc || MarketBrain.checkPrice(CRASH) == 40 && MarketBrain.checkEither(CRASH) == 40 && MarketBrain.checkPrice(DEAR) == 200);
        check("P checkPrice/checkEither = chargePrice/eitherPrice with market.chargeCheck on",
                !cc || MarketBrain.checkPrice(CRASH) == MarketBrain.chargePrice(CRASH) && MarketBrain.checkEither(DEAR) == MarketBrain.eitherPrice(DEAR));

        // the bunyip buy path (Bob.java, summoning.heal, live): purse share 10% of (coins - 300)
        int B = W8Polish.BUNYIP; seed(B, 600, 1500); // botRef 1050
        long coins = 8_300; // share 800: passes at the guide 600, not at the charged 1050
        boolean ok = W8Polish.healAffordable(coins, Math.max(1, MarketBrain.checkPrice(B)));
        boolean overpays = ok && MarketBrain.chargePrice(B) > (coins - 300) * W8Polish.HEAL_SHARE;
        check("B bunyip: with fix2 + chargeCheck the 1050 charge is refused on an 800 share; flag off = the old pass (BUG-1 kept as was)",
                fix2 && cc ? !ok : ok);
        check("B bunyip: never charged over its share once the check is on", !(cc && overpays));
        check("B bunyip wiring: Bob checks at checkPrice", read(dir, "Bob.java").contains("int price = Math.max(1, MarketBrain.checkPrice(id));"));

        // the other live paths are gated; the not-live switches use the charged price directly
        String sa = read(dir, "SmartAdventurers.java"), hk = read(dir, "W10Hooks.java");
        check("W ranged.melee arrows + bow gated", sa.contains("(MarketBrain.CHARGE_CHECK && MarketBrain.stockOf(arrow) >= need ? MarketBrain.chargePrice(arrow) : unit) * need > supplyBudget()")
                && sa.contains("(MarketBrain.CHARGE_CHECK && MarketBrain.stockOf(BOWS[0]) > 0 ? MarketBrain.chargePrice(BOWS[0]) : price) < 1_000"));
        check("W style.restock plans at checkEither, the shop still takes the guide", sa.contains("pr[i] = Math.max(1, MarketBrain.checkEither(ARROWS[i]))")
                && sa.contains("Ledger.Wallet.pay(bot, (long) Math.max(1, MarketBrain.value(ARROWS[tier])) * n)"));
        check("W W10 hooks: no purse price left at the guide", !hk.contains("MarketBrain.value(") && hk.split("MarketBrain.chargePrice\\(", -1).length - 1 >= 14);

        // routes
        check("R hunter gate: combat " + Hunter.MIN_COMBAT + " (default 40, the Feldip ogres are level 53)",
                Hunter.MIN_COMBAT == (Integer.getInteger("bots.hunter.minCombat") == null ? 40 : Hunter.MIN_COMBAT) && !Hunter.fit(5, Hunter.MIN_COMBAT - 1, 20, 10_000, 0, 1000) && Hunter.fit(5, Hunter.MIN_COMBAT, 20, 10_000, 0, 1000));
        check("R barb via: Seers and Catherby walk by 2566,3413; Ardougne north, the Fishing Guild, the GE and the via itself do not",
                W7Places.barbVia(2726, 3492) && W7Places.barbVia(2809, 3440) && !W7Places.barbVia(2617, 3332) && !W7Places.barbVia(2586, 3420)
                && !W7Places.barbVia(3165, 3474) && !W7Places.barbVia(2566, 3413));
        String w7 = read(dir, "W7Run.java");
        check("R barb via wiring: walks to the via, stall on via progress, the teleport test keeps the straight distance (Seers via-sum 336 > tele.min 320)", w7.contains("walkFar(bot, via != null ? via : out);")
                && w7.contains("if (prog < s.best - 1) { s.best = prog; s.stall = 0; }") && w7.contains("if ((d > AfkTasks.Runner.TELEPORT_MIN || s.stall > 8)"));
        System.out.println("[TestW13d] " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
