package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;

import java.util.Locale;

/**
 * W10 lane L: the engine side of W10Skills (bank, Ledger, XP, the cast animation). Both methods are called only from
 * SmartAdventurers.Brain.kitFromExchange (at the Grand Exchange), only behind their W10Skills flag, inside a try/catch
 * Throwable. Nothing is minted and no shop is used: every input comes out of bot stock through Ledger.consume (its makers are
 * paid by the buyer), the superheated bars go back on the Exchange through Ledger.supply, the arrows to the bot's bank.
 * Bought inputs that are not used stay in the bank (W8's t63 placement, which refunds when neither bank nor pack has room).
 * No RNG is drawn here.
 */
final class W10Hooks {
    private W10Hooks() { }

    static int bank(Player p, int id) { return p.getBank().getAmount(id); }

    /** Out of bot stock into the bank; returns the units placed (0 when bot stock or the purse gave nothing). */
    static int buy(SmartAdventurers.Brain b, int id, int n, String why) {
        if (n <= 0) return 0;
        int got = Ledger.consume(b.bot, id, n, why);
        if (got <= 0) return 0;
        return b.w8Place(id, got, 0, true, why) ? got : 0;
    }

    /** Swap k x a and k x c in the bank for k x out (both inputs must be there); false = nothing changed. */
    static boolean swap(Player p, int a, int c, int out, int k) {
        if (k <= 0 || bank(p, a) < k || bank(p, c) < k) return false;
        if (!p.getBank().remove(new Item(a, k))) return false;
        if (!p.getBank().remove(new Item(c, k))) { p.getBank().add(new Item(a, k)); return false; }
        if (p.getBank().add(new Item(out, k)) || p.getInventory().add(new Item(out, k))) return true;
        p.getBank().add(new Item(a, k)); p.getBank().add(new Item(c, k)); // no room for the product: the inputs go back as they were
        return false;
    }

    // ------------------------------------------------------------------ bots.fletch.arrows

    static void arrows(SmartAdventurers.Brain b) {
        Player p = b.bot;
        long now = System.currentTimeMillis();
        if (!W10Skills.take(W10Skills.ARROW_NEXT, p.getUsername(), now, W10Skills.ARROW_GAP_MS)) return; // one try per gap, whatever comes of it
        int fl = p.getSkills().getStaticLevel(Skills.FLETCHING);
        int L = W10Skills.ARROW_ROWS.length;
        int[] tipStock = new int[L], tipPrice = new int[L], arrowPrice = new int[L];
        for (int i = 0; i < L; i++) { int[] r = W10Skills.ARROW_ROWS[i]; tipStock[i] = MarketBrain.stockOf(r[1]); tipPrice[i] = Math.max(1, MarketBrain.chargePrice(r[1])); arrowPrice[i] = Math.max(1, MarketBrain.chargePrice(r[0])); } // w13d BUG-1: the price consume charges
        int held = bank(p, W10Skills.HEADLESS), hs = MarketBrain.stockOf(W10Skills.HEADLESS), ss = MarketBrain.stockOf(W10Skills.SHAFT), fs = MarketBrain.stockOf(W10Skills.FEATHER);
        int src = W10Skills.headSource(held, hs, ss, fs);
        if (src == W10Skills.SRC_NONE) { W10Skills.arrowNoPick++; return; }
        long hc = W10Skills.headCost(src, MarketBrain.chargePrice(W10Skills.HEADLESS), MarketBrain.chargePrice(W10Skills.SHAFT), MarketBrain.chargePrice(W10Skills.FEATHER)); // w13d BUG-1
        int row = W10Skills.arrowRow(fl, b.bestArrow(), tipStock, tipPrice, arrowPrice, hc);
        if (row < 0) { W10Skills.arrowNoPick++; return; }
        int[] r = W10Skills.ARROW_ROWS[row];
        long before = Ledger.Wallet.coins(p);
        int n = W10Skills.arrowQty(tipStock[row] - W10Skills.FLOOR, W10Skills.headLeft(src, held, hs, ss, fs), W10Skills.ARROW_HOUR.left(now), before, tipPrice[row] + hc, W10Skills.ARROW_MAX);
        if (n <= 0 || !b.w8Room(r[1], n) || !b.w8Room(r[0], n)) { W10Skills.arrowCapped++; return; }
        int tips = buy(b, r[1], n, "fletch");
        if (tips <= 0) { W10Skills.arrowNoPick++; return; }
        int feathered = 0;
        if (src == W10Skills.SRC_STOCK) buy(b, W10Skills.HEADLESS, Math.max(0, tips - held), "fletch");
        else if (src == W10Skills.SRC_SHAFTS) { // feather the shafts first: shaft + feather -> headless arrow, 1 XP each
            int s = buy(b, W10Skills.SHAFT, tips, "fletch"), f = s > 0 ? buy(b, W10Skills.FEATHER, s, "fletch") : 0;
            int k = Math.min(s, f); // only what was bought for this: the bot's own shafts and feathers stay as they are
            if (swap(p, W10Skills.SHAFT, W10Skills.FEATHER, W10Skills.HEADLESS, k)) {
                feathered = k;
                try { p.getSkills().addExperience(Skills.FLETCHING, k * W10Skills.HEADLESS_XP10 / 10.0, true); } catch (Throwable ignored) { }
            }
        }
        int k = Math.min(tips, Math.min(bank(p, W10Skills.HEADLESS), bank(p, r[1])));
        boolean made = swap(p, W10Skills.HEADLESS, r[1], r[0], k);
        long spent = Math.max(0, before - Ledger.Wallet.coins(p));
        W10Skills.arrowSpent += spent; W10Skills.arrowsFeathered += feathered;
        if (!made) { W10Skills.arrowCapped++; if (W10Skills.ARROW_LINES.ok(now)) System.out.println("[W10] " + p.getUsername() + " arrows: bought " + tips + " x " + MarketBrain.name(r[1]) + " but tipped none (headless in bank " + bank(p, W10Skills.HEADLESS) + "); they wait in the bank"); return; }
        try { p.getSkills().addExperience(Skills.FLETCHING, k * r[3] / 10.0, true); } catch (Throwable ignored) { }
        W10Skills.ARROW_HOUR.use(now, k);
        W10Skills.arrowRuns++; W10Skills.arrowsMade += k;
        if (W10Skills.ARROW_LINES.ok(now)) System.out.println("[W10] " + p.getUsername() + " arrows: tipped " + k + " x " + MarketBrain.name(r[0]).toLowerCase(Locale.ROOT) + " (headless " + W10Skills.SRC_NAMES[src] + (feathered > 0 ? ", " + feathered + " feathered" : "") + ", Fletching " + fl + ", " + spent + " gp to bot stock, fleet hour " + W10Skills.ARROW_HOUR.used(now) + "/" + W10Skills.ARROW_HOUR.cap + ")");
        final int fk = k; final int fa = r[0];
        BotChat.activity(p, () -> "fletching " + fk + " " + MarketBrain.name(fa).toLowerCase(Locale.ROOT) + "s at the Exchange");
    }

    // ------------------------------------------------------------------ bots.magic.superheat

    static void superheat(SmartAdventurers.Brain b) {
        Player p = b.bot;
        int m = p.getSkills().getStaticLevel(Skills.MAGIC);
        if (m < W10Skills.HEAT_LEVEL) return; // not a try: no gap booked
        long now = System.currentTimeMillis();
        if (!W10Skills.take(W10Skills.HEAT_NEXT, p.getUsername(), now, W10Skills.HEAT_GAP_MS)) return;
        int s = p.getSkills().getStaticLevel(Skills.SMITHING);
        Item wep = null; try { wep = p.getEquipment().get(3); } catch (Throwable ignored) { }
        boolean staff = wep != null && W10Skills.fireStaff(wep.getId());
        int L = W10Skills.HEAT_ROWS.length;
        int[] oreStock = new int[L], barPrice = new int[L]; boolean[] glut = new boolean[L]; long[] cost = new long[L];
        int coal = MarketBrain.stockOf(W10Skills.COAL), nat = MarketBrain.stockOf(W10Skills.NATURE), fire = MarketBrain.stockOf(W10Skills.FIRE);
        for (int i = 0; i < L; i++) {
            int[] r = W10Skills.HEAT_ROWS[i];
            oreStock[i] = MarketBrain.stockOf(r[2]); barPrice[i] = Math.max(1, MarketBrain.chargePrice(r[0])); glut[i] = MarketBrain.isSurplus(r[0]);
            cost[i] = W10Skills.castCost(MarketBrain.chargePrice(r[2]), MarketBrain.chargePrice(W10Skills.COAL), r[3], MarketBrain.chargePrice(W10Skills.NATURE), MarketBrain.chargePrice(W10Skills.FIRE), staff); // w13d BUG-1: cost and ceiling at the charged price
        }
        int row = W10Skills.heatRow(m, s, oreStock, coal, glut, cost, barPrice);
        if (row < 0) { W10Skills.heatNoPick++; W10Skills.heatWhy(W10Skills.heatRowShort(m, s, oreStock, coal, glut, cost, barPrice)); return; } // w24: the reason, for the hourly line only
        int[] r = W10Skills.HEAT_ROWS[row];
        long before = Ledger.Wallet.coins(p);
        int n = W10Skills.heatQty(oreStock[row] - W10Skills.HEAT_FLOOR, coal - W10Skills.HEAT_FLOOR, r[3], nat - W10Skills.HEAT_FLOOR, fire - W10Skills.HEAT_FLOOR, staff, W10Skills.HEAT_HOUR.left(now), before, cost[row], W10Skills.HEAT_MAX);
        if (n <= 0 || !b.w8Room(W10Skills.NATURE, n) || !staff && !b.w8Room(W10Skills.FIRE, n * W10Skills.FIRES) || r[3] > 0 && !b.w8Room(W10Skills.COAL, n * r[3]) || !b.w8Room(r[2], n)) { W10Skills.heatCapped++; W10Skills.heatWhy(n <= 0 ? W10Skills.heatShort(oreStock[row] - W10Skills.HEAT_FLOOR, coal - W10Skills.HEAT_FLOOR, r[3], nat - W10Skills.HEAT_FLOOR, fire - W10Skills.HEAT_FLOOR, staff, W10Skills.HEAT_HOUR.left(now), before, cost[row], W10Skills.HEAT_MAX) : W10Skills.WHY_ROOM); return; } // w24
        // runes first, then coal, then ore: each into the bank, so what the next step can't match stays the bot's own
        // k counts only units bought for these casts: the bot's own runes, coal and ore in the bank are never used up here
        int k = Math.min(n, buy(b, W10Skills.NATURE, n, "superheat"));
        if (!staff && k > 0) k = Math.min(k, buy(b, W10Skills.FIRE, k * W10Skills.FIRES, "superheat") / W10Skills.FIRES);
        if (r[3] > 0 && k > 0) k = Math.min(k, buy(b, W10Skills.COAL, k * r[3], "superheat") / r[3]);
        if (k > 0) k = Math.min(k, buy(b, r[2], k, "superheat"));
        long spent = Math.max(0, before - Ledger.Wallet.coins(p));
        W10Skills.heatSpent += spent;
        if (k <= 0) { W10Skills.heatCapped++; W10Skills.heatWhy(W10Skills.WHY_BUY); if (W10Skills.HEAT_LINES.ok(now)) System.out.println("[W10] " + p.getUsername() + " superheat: bought inputs for " + MarketBrain.name(r[0]) + " but cast none (" + spent + " gp); they wait in the bank"); return; }
        // the casts: the inputs out of the bank, the bars on the Exchange as its own stock
        if (!p.getBank().remove(new Item(W10Skills.NATURE, k))) { W10Skills.heatCapped++; return; }
        if (!staff && !p.getBank().remove(new Item(W10Skills.FIRE, k * W10Skills.FIRES))) { p.getBank().add(new Item(W10Skills.NATURE, k)); W10Skills.heatCapped++; return; }
        if (r[3] > 0 && !p.getBank().remove(new Item(W10Skills.COAL, k * r[3]))) { p.getBank().add(new Item(W10Skills.NATURE, k)); if (!staff) p.getBank().add(new Item(W10Skills.FIRE, k * W10Skills.FIRES)); W10Skills.heatCapped++; return; }
        if (!p.getBank().remove(new Item(r[2], k))) { p.getBank().add(new Item(W10Skills.NATURE, k)); if (!staff) p.getBank().add(new Item(W10Skills.FIRE, k * W10Skills.FIRES)); if (r[3] > 0) p.getBank().add(new Item(W10Skills.COAL, k * r[3])); W10Skills.heatCapped++; return; }
        Ledger.supply(p, r[0], k); // on the Exchange as this bot's goods (it is paid when they sell)
        try { p.getSkills().addExperience(Skills.MAGIC, k * W10Skills.HEAT_XP10 / 10.0, true); p.getSkills().addExperience(Skills.SMITHING, k * r[4] / 10.0, true); } catch (Throwable ignored) { }
        W10Skills.HEAT_HOUR.use(now, k);
        W10Skills.heatRuns++; W10Skills.heatBars += k;
        if (W10Skills.HEAT_LINES.ok(now)) System.out.println("[W10] " + p.getUsername() + " superheat: " + k + " x " + MarketBrain.name(r[2]).toLowerCase(Locale.ROOT) + " into " + MarketBrain.name(r[0]).toLowerCase(Locale.ROOT) + " for the Exchange (Magic " + m + ", Smithing " + s + (staff ? ", fire staff" : "") + ", " + spent + " gp to bot stock, fleet hour " + W10Skills.HEAT_HOUR.used(now) + "/" + W10Skills.HEAT_HOUR.cap + ")");
        final Player fp = p; final int casts = Math.min(k, 27);
        core.game.world.GameWorld.getPulser().submit(new core.game.system.task.Pulse(2) { int i; @Override public boolean pulse() { try { fp.getAnimator().animate(new core.game.world.update.flag.context.Animation(725)); fp.graphics(new core.game.world.update.flag.context.Graphics(148, 96)); } catch (Throwable ignored) { } return ++i >= casts; } });
        final int fb = r[0];
        BotChat.activity(p, () -> "superheating " + MarketBrain.name(fb).toLowerCase(Locale.ROOT) + "s at the Exchange");
    }

    // ------------------------------------------------------------------ bots.attack.spec (W10 lane M)

    /** Called from kitFromExchange behind W10Skills.SPEC && MELEE: wield an owned spec weapon again, else buy one from bot stock (one try per gap). */
    static void spec(SmartAdventurers.Brain b) {
        Player p = b.bot;
        int att = p.getSkills().getStaticLevel(Skills.ATTACK);
        if (att < W10Skills.SPEC_LEVEL) return;
        Item w = p.getEquipment().get(3);
        int worn = w == null ? -1 : w.getId(), owned = -1;
        for (int i = 0; i < W10Skills.SPEC_ROWS.length && owned < 0; i++) { int id = W10Skills.SPEC_ROWS[i][0]; if (b.ownsOne(id) && SmartAdventurers.questOk(p, id)) owned = i; }
        int act = W10Skills.specAction(worn, worn >= 0 && W8Polish.isMace(worn), worn >= 0 && SmartAdventurers.Brain.tierOf(4, worn) >= 0, owned, att);
        if (act == W10Skills.SP_NONE) return;
        long now = System.currentTimeMillis();
        if (act == W10Skills.SP_WIELD) {
            int id = W10Skills.SPEC_ROWS[owned][0];
            if (wield(b, id)) { W10Skills.specWields++; if (W10Skills.SPEC_LINES.ok(now)) System.out.println("[W10] " + p.getUsername() + " spec: wields its " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + " again (Attack " + att + ")"); }
            return;
        }
        if (!W10Skills.take(W10Skills.SPEC_NEXT, p.getUsername(), now, W10Skills.SPEC_GAP_MS)) return; // one buy try per gap, whatever comes of it
        int n = W10Skills.SPEC_ROWS.length;
        boolean[] quest = new boolean[n]; int[] stock = new int[n], price = new int[n];
        for (int i = 0; i < n; i++) { int id = W10Skills.SPEC_ROWS[i][0]; quest[i] = SmartAdventurers.questOk(p, id); stock[i] = MarketBrain.stockOf(id); price[i] = Math.max(1, MarketBrain.chargePrice(id)); } // w13d BUG-1
        long coins = Ledger.Wallet.coins(p);
        long capLeft = W8Polish.KIT_ABOVE_RUNE ? Math.max(0L, W8Polish.KIT_CAP - b.w8KitSpent) : Long.MAX_VALUE; // with kitAboveRune on, one budget for both
        int row = W10Skills.specPick(att, quest, stock, price, coins, capLeft);
        if (row < 0) { W10Skills.specNoPick++; return; }
        int id = W10Skills.SPEC_ROWS[row][0];
        if (!b.w8Room(id, 1)) { W10Skills.specNoPick++; return; }
        int got = buy(b, id, 1, "kit");
        if (got <= 0) { W10Skills.specNoPick++; return; }
        long paid = Math.max(0L, coins - Ledger.Wallet.coins(p));
        W10Skills.specBuys++; W10Skills.specSpent += paid;
        if (W8Polish.KIT_ABOVE_RUNE) b.w8KitSpent += paid;
        boolean on = wield(b, id);
        if (W10Skills.SPEC_LINES.ok(now)) System.out.println("[W10] " + p.getUsername() + " spec: bought a " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + " from bot stock for " + paid + " gp (Attack " + att
                + ", " + Ledger.Wallet.coins(p) + " gp left" + (on ? ", wields it" : ", not worn") + (worn >= 0 ? ", old " + MarketBrain.name(worn).toLowerCase(Locale.ROOT) + " to the bank" : "") + ")");
    }

    /** The weapon on, the old one to the bank (weaponByFoe's swap); never left unarmed. */
    static boolean wield(SmartAdventurers.Brain b, int id) {
        boolean ok = b.toPack(id) && b.stowWeapon() && SmartAdventurers.wear(b.bot, id);
        if (b.bot.getEquipment().get(3) == null) try { b.wearMeleeWeapon(); } catch (Throwable ignored) { }
        try { b.bot.getEquipment().refresh(); } catch (Throwable ignored) { }
        return ok;
    }

    // ------------------------------------------------------------------ bots.ranged.thrown (W10 lane M)

    /** Called first in kitFromExchange behind W10Skills.THROWN && RANGED: darts or knives for this trip when cheaper than arrows (and a bow), else back to its bow. */
    static void thrown(SmartAdventurers.Brain b) {
        Player p = b.bot;
        int r = p.getSkills().getStaticLevel(Skills.RANGE);
        Item w = p.getEquipment().get(3);
        int wornRow = w == null ? -1 : W10Skills.thrownIndex(w.getId());
        if (r >= W10Skills.THROWN_MAX) { if (wornRow >= 0) back(b, "Ranged " + r); return; }
        int L = W10Skills.THROWN_ROWS.length, n = W10Skills.THROWN_TRIP;
        int[] held = new int[L], stock = new int[L], price = new int[L];
        for (int i = 0; i < L; i++) { int id = W10Skills.THROWN_ROWS[i][0]; held[i] = b.held(id) + (i == wornRow ? w.getAmount() : 0); stock[i] = MarketBrain.stockOf(id); price[i] = Math.max(1, MarketBrain.chargePrice(id)); } // w13d BUG-1
        boolean ownsBow = false;
        for (int bw : SmartAdventurers.Brain.BOWS) if (b.ownsOne(bw) || w != null && w.getId() == bw) ownsBow = true;
        int a = b.bestArrow();
        long ac = W10Skills.arrowCost(n, b.held(a), MarketBrain.chargePrice(a), ownsBow, MarketBrain.chargePrice(SmartAdventurers.Brain.BOWS[b.kitBow()])); // w13d BUG-1
        long coins = Ledger.Wallet.coins(p);
        int row = W10Skills.thrownRow(r, held, stock, price, n, ac, coins, wornRow);
        long now = System.currentTimeMillis();
        if (row < 0) { if (wornRow >= 0) back(b, "arrows cost " + ac + " gp"); else W10Skills.thrownNoPick++; return; }
        int id = W10Skills.THROWN_ROWS[row][0], need = Math.max(0, n - held[row]), got = 0;
        if (need > 0) {
            if (!b.w8Room(id, need)) { W10Skills.thrownNoPick++; return; }
            got = buy(b, id, need, "ammo");
            if (got <= 0 && held[row] <= 0) { W10Skills.thrownNoPick++; return; }
        }
        long paid = Math.max(0L, coins - Ledger.Wallet.coins(p));
        if (!wieldThrown(b, id)) { W10Skills.thrownNoPick++; return; }
        W10Skills.thrownTrips++; W10Skills.thrownBought += got; W10Skills.thrownSpent += paid;
        Item on = p.getEquipment().get(3);
        if (row != wornRow || got > 0) if (W10Skills.THROWN_LINES.ok(now)) System.out.println("[W10] " + p.getUsername() + " thrown: " + (on == null ? 0 : on.getAmount()) + " x " + MarketBrain.name(id).toLowerCase(Locale.ROOT)
                + " (Ranged " + r + (got > 0 ? ", bought " + got + " from bot stock for " + paid + " gp" : ", its own") + "; arrows" + (ownsBow ? "" : " and a bow") + " would cost " + ac + " gp)");
    }

    /** Every unit of id it holds into the weapon slot; the bow (or other weapon) to the bank. False = nothing changed that matters (it keeps what it wore). */
    static boolean wieldThrown(SmartAdventurers.Brain b, int id) {
        Player p = b.bot;
        Item w = p.getEquipment().get(3);
        boolean same = w != null && w.getId() == id;
        if (!same && !b.stowWeapon()) return false;
        int n = p.getBank().getAmount(id), i = p.getInventory().getAmount(id);
        if (n > 0 && !p.getBank().remove(new Item(id, n))) n = 0;
        if (i > 0 && !p.getInventory().remove(new Item(id, i))) i = 0;
        int total = n + i;
        if (total <= 0) return same;
        if (same) { Item cur = p.getEquipment().get(3); if (cur != null && cur.getId() == id && p.getEquipment().remove(cur)) total += cur.getAmount(); }
        if (p.getEquipment().add(new Item(id, total), -1, true, false)) { try { p.getEquipment().refresh(); } catch (Throwable ignored) { } return true; }
        if (!p.getBank().add(new Item(id, total))) SmartAdventurers.Brain.packOrBank(p, new Item(id, total)); // not worn: all of it back to the bank
        if (p.getEquipment().get(3) == null) try { b.styleWeaponBack(); } catch (Throwable ignored) { }
        return false;
    }

    /** The thrown stack to the bank and its own bow back on (styleKit buys one at this same visit when it owns none). */
    static void back(SmartAdventurers.Brain b, String why) {
        Item w = b.bot.getEquipment().get(3);
        if (w == null || !b.stowWeapon()) return;
        W10Skills.thrownBack++;
        try { b.styleWeaponBack(); } catch (Throwable ignored) { }
        if (W10Skills.THROWN_LINES.ok(System.currentTimeMillis())) System.out.println("[W10] " + b.bot.getUsername() + " thrown: " + w.getAmount() + " x " + MarketBrain.name(w.getId()).toLowerCase(Locale.ROOT) + " to the bank, back to the bow (" + why + ")");
    }
}
