package core.game.ge;

import core.cache.def.impl.ItemDefinition;

/**
 * Shadows the server's BotPrices (the overlay comes before server.jar on the classpath). The built-in table priced many
 * items far above guide (mithril scimitar: guide 624, bots charged 1,144), so players paid max(table, guide) x 1.10 for
 * bot stock, price drift could never make anything cheaper, and suppliers were paid at guide anyway. Now the bot price
 * IS the guide price (drift included); the Exchange still adds its 10% on top. the owner's notes 2026-10-20 (#3).
 */
public final class BotPrices {
    public static final Companion Companion = new Companion();

    public static int getPrice(int id) { return Companion.getPrice(id); }

    public static final class Companion {
        public final int getPrice(int id) {
            try {
                int g = PriceIndex.INSTANCE.getValue(id);
                // audit 10-23 X-4: never under the bot buyer's reference (70-150% of the base). Alts trading at 1 gp pushed the guide
                // down, bought bot stock at 1.1 x that and sold it back to the bot buyer at 0.9 x botRef: about +14% a loop.
                int ref = 0; try { ref = content.custom.MarketBrain.botRefFor(id); } catch (Throwable ignored) { }
                if (g > 0 || ref > 0) return Math.max(g, ref);
            } catch (Throwable ignored) { }
            try { return ItemDefinition.forId(id).getValue(); } catch (Throwable t) { return 1; }
        }
    }
}
