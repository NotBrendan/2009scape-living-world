package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.item.Item;

/**
 * A bot-to-bot hand-over that looks like a trade (the Shield of Arrav partners, QuestShieldOfArrav.Meet): the two bots
 * stand together facing each other and each hands the other exactly one item, or one side hands one item for nothing (the
 * Phoenix key lent to a Black Arm friend). It all happens in one call on the game thread, pack to pack: both items are
 * checked, both removed, both added; if anything fails, everything is put back. Nothing is ever held in between, so a
 * logout at any point can neither lose an item nor make a second one.
 */
final class BotSwap {
    private BotSwap() { }

    /** a hands one aGives (0 = nothing) to b, b hands one bGives (0 = nothing) to a. Both must be in the packs already. */
    static synchronized boolean swap(Player a, int aGives, Player b, int bGives, String what) {
        if (a == null || b == null || a == b || !a.isActive() || !b.isActive()) return false;
        if (aGives <= 0 && bGives <= 0) return false;
        if (a.getLocation().getZ() != b.getLocation().getZ() || a.getLocation().getDistance(b.getLocation()) > 3) return false;
        if (aGives > 0 && a.getInventory().getAmount(aGives) < 1) return false;
        if (bGives > 0 && b.getInventory().getAmount(bGives) < 1) return false;
        Item fromA = aGives > 0 ? new Item(aGives, 1) : null, fromB = bGives > 0 ? new Item(bGives, 1) : null;
        if (fromA != null && !a.getInventory().remove(fromA)) return false;
        if (fromB != null && !b.getInventory().remove(fromB)) { if (fromA != null) a.getInventory().add(fromA); return false; }
        boolean toA = fromB == null || a.getInventory().add(fromB);
        boolean toB = fromA == null || b.getInventory().add(fromA);
        if (!toA || !toB) { // put everything back where it was
            if (toA && fromB != null) a.getInventory().remove(fromB);
            if (toB && fromA != null) b.getInventory().remove(fromA);
            boolean backA = fromA == null || a.getInventory().add(fromA); // batch 19c §4.2: the put-back results, read for item-lost-on-handover only
            boolean backB = fromB == null || b.getInventory().add(fromB);
            System.out.println("[BotSwap] " + a.getUsername() + " <-> " + b.getUsername() + ": no room, " + what + " put back");
            try { Failures.Econ.botswapFailed(a, b, aGives, bGives, what); if (!backA) Failures.Econ.itemLost(a, aGives, 1, "botswap", "bot"); if (!backB) Failures.Econ.itemLost(b, bGives, 1, "botswap", "bot"); } catch (Throwable ignored) { } // batch 19c §4.4 / §4.2
            return false;
        }
        try { a.faceLocation(b.getLocation()); b.faceLocation(a.getLocation()); } catch (Throwable ignored) { }
        System.out.println("[BotSwap] " + a.getUsername() + " gave " + (fromA != null ? "1 x " + MarketBrain.name(aGives) + " (" + aGives + ")" : "nothing") + ", " + b.getUsername() + " gave " + (fromB != null ? "1 x " + MarketBrain.name(bGives) + " (" + bGives + ")" : "nothing") + " at " + a.getLocation() + ": " + what);
        return true;
    }
}
