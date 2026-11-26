package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;

/**
 * W6 Magic (-Dbots.magic.tele): the world side of a bot's real teleport cast, called first thing in
 * Ledger.Wallet.payTeleport. Game thread only (payTeleport's callers are; Ledger.consume and the bank asserts hold).
 * Returns true when the cast was made (runes gone, xp given): payTeleport then returns true without charging the fare.
 * Returns false with nothing taken (short of level, runes or coins for the shortfall): the old fare runs unchanged.
 * No coins or items are created: own runes are destroyed, the shortfall is bought from bot stock (sellers paid).
 */
final class W6MagicCast {
    private W6MagicCast() { }

    static int held(Player p, int id) { try { return p.getInventory().getAmount(id) + p.getBank().getAmount(id); } catch (Throwable t) { return 0; } }

    static boolean castTele(Player p, String why) {
        if (p == null || !p.isArtificial()) return false;
        int magic = p.getSkills().getStaticLevel(Skills.MAGIC);
        int weapon = 0; try { Item w = p.getEquipment().get(3); if (w != null) weapon = w.getId(); } catch (Throwable ignored) { }
        int row = W6Magic.pickTele(magic, weapon, id -> held(p, id));
        if (row < 0) { W6Magic.teleNoLevel++; return false; }
        int[][] runes = W6Magic.teleRunes(row, weapon);
        int[] shortN = new int[runes.length], stock = new int[runes.length]; long[] price = new long[runes.length];
        boolean any = false;
        for (int i = 0; i < runes.length; i++) {
            shortN[i] = W6Magic.shortOf(runes[i][1], held(p, runes[i][0]));
            stock[i] = MarketBrain.stockOf(runes[i][0]); price[i] = Ledger.price(runes[i][0]);
            if (shortN[i] > 0) any = true;
        }
        if (any && !W6Magic.canBuy(shortN, stock, price, Ledger.Wallet.coins(p))) { W6Magic.teleShort++; return false; }
        // buy the shortfall into the bank first; a short buy keeps what it got (paid for) and falls back to the fare
        long gp = 0; int bought = 0;
        for (int i = 0; i < runes.length; i++) {
            if (shortN[i] <= 0) continue;
            int got = Ledger.consume(p, runes[i][0], shortN[i], "teleport-cast");
            if (got > 0) { p.getBank().add(new Item(runes[i][0], got)); gp += Math.max(1, price[i]) * got; bought += got; }
            if (got < shortN[i]) { W6Magic.teleShort++; System.out.println("[Magic] tele " + p.getUsername() + " " + why + ": bought " + got + " of " + shortN[i] + " x " + MarketBrain.name(runes[i][0]) + ", the fare instead (runes kept in the bank)"); return false; }
        }
        // t62 fix 3: never cast without the runes. Checked before any rune leaves; a remove that still comes up short puts back
        // what this cast took and the fare runs instead (bought runes stay in the bank, paid for).
        if (!W6Magic.allHeld(runes, id -> held(p, id))) { W6Magic.teleMissing++; System.out.println("[Magic] tele " + p.getUsername() + " " + why + ": runes missing at the cast, the fare instead"); return false; }
        // the cast: runes out of the bank first (a mage's pack holds its combat runes), then the pack
        int fromBank = 0, fromPack = 0;
        int[][] took = new int[runes.length][2]; int ri = 0;
        for (int[] r : runes) {
            int need = r[1];
            int b = Math.min(need, p.getBank().getAmount(r[0]));
            if (b > 0 && p.getBank().remove(new Item(r[0], b))) { need -= b; fromBank += b; took[ri][0] = b; }
            int k = Math.min(need, p.getInventory().getAmount(r[0]));
            if (k > 0 && p.getInventory().remove(new Item(r[0], k))) { need -= k; fromPack += k; took[ri][1] = k; }
            ri++;
            if (need > 0) { // put back what this cast took, then the fare
                for (int j = 0; j < ri; j++) {
                    if (took[j][0] > 0 && !p.getBank().add(new Item(runes[j][0], took[j][0]))) SmartAdventurers.Brain.packOrBank(p, new Item(runes[j][0], took[j][0]));
                    if (took[j][1] > 0) SmartAdventurers.Brain.packOrBank(p, new Item(runes[j][0], took[j][1]));
                }
                W6Magic.teleMissing++;
                System.out.println("[Magic] tele " + p.getUsername() + " " + why + ": " + need + " x " + MarketBrain.name(r[0]) + " missing at the cast, runes put back, the fare instead");
                return false;
            }
        }
        int[] t = W6Magic.TELES[row];
        try { p.getSkills().addExperience(Skills.MAGIC, t[5] / 10.0, true); } catch (Throwable ignored) { }
        try { CoinWatch.paid(p, why); } catch (Throwable ignored) { } // the teleport still counts in the paid-action alarm (same rate as the fare)
        W6Magic.teleCast++; if (bought > 0) { W6Magic.teleBought++; W6Magic.teleGp += gp; } else W6Magic.teleRunesOwn++;
        System.out.println("[Magic] tele " + p.getUsername() + " " + why + " cast " + W6Magic.TELE_NAMES[row] + " Teleport (Magic " + magic + "): runes bank " + fromBank + " pack " + fromPack + " bought " + bought + (bought > 0 ? " for " + gp + " gp" : ""));
        return true;
    }
}
