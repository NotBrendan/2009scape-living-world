package content.custom;

import core.api.StartupListener;
import core.cache.def.impl.ItemDefinition;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.request.trade.TradeModule;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import core.game.world.repository.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bot-to-bot trading at hubs (the design notes, points 2 and 3 with the guards).
 *
 * CALLOUTS: a bot back from a trip with 20+ of something stands at the bank and says "Selling 80 coal at 160 each
 * (under guide), trade me" for 25 s before it goes to the Exchange. Another bot in the same bank that will USE the
 * item (food it eats, an input of its trade) waits ~10 s, walks to a free tile next to the seller, says "ill take em",
 * and the two swap items for coins (over 20 units move as bank notes). A human who trades the seller inside the
 * window buys the lot first, through the normal trade screen. Nobody taking it: it goes on the Exchange as before.
 *
 * SPLITS: raw fish, ore or grimy herbs handed to an artisan of the right trade standing in the same bank come back
 * as 85% of the finished goods (the artisan keeps the rest as the fee) when the next batch is done, or as coins at
 * 85% of value if no batch happens within 30 minutes.
 *
 * Guards: 90 s meet timeout (claim dropped, seller grumbles), buyer consumption lock, tile scatter.
 */
public final class Deals implements StartupListener {

    /** Server going down: every open split is paid in coins now, so no gatherer loses what it handed over. */
    static void settleForShutdown() {
        for (Owed w : OWED) {
            OWED.remove(w);
            long coins = (long) MarketBrain.value(w.product) * w.qty;
            if (Ledger.Wallet.pay(w.artisan, coins)) Ledger.Wallet.credit(w.gatherer.getUsername(), coins);
        }
        for (Offer o : OFFERS) { // a callout in progress: the haul goes to the Exchange as it would have
            try { if (!o.onRequest && o.seller.getBank().remove(new Item(o.id, o.qty))) Ledger.supply(o.seller, o.id, o.qty); } catch (Throwable ignored) { }
            OFFERS.remove(o);
        }
    }
    static final Random RNG = new Random();
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("deals", "true"));
    static final int WINDOW_MS = 60_000, WAIT_MS = 10_000, MEET_MS = 90_000; // 25 s callouts matched almost nobody (audit E3); a minute is still a quick stop
    static final int DISCOUNT_PERCENT = 92;

    /** The price bots haggle from: the guide, or the bot buyer's reference when that is higher, so a bot never sells under what the bot buyer pays. */
    static int ref(int id) { return Math.max(MarketBrain.value(id), MarketBrain.botRef(id)); }
    static final int MIN = Integer.getInteger("deals.min", 20); // haul size worth a callout (test knob)
    static final List<Offer> OFFERS = new CopyOnWriteArrayList<>();
    static volatile int calloutsMade; // audit E3: callouts were invisible in the log
    static final List<Owed> OWED = new CopyOnWriteArrayList<>();
    static volatile int tradesToday, splitsToday, humanBuys, meetsToday;
    static final java.util.Map<String, Long> ERRAND_ASKED = new java.util.concurrent.ConcurrentHashMap<>(); // artisan -> last ask

    static final class Offer {
        final Player seller; final int id, unit; final long created; int qty; // qty drops when a player buys part of it
        Player buyer; long claimedAt, acceptedAt; boolean announcedTaker, done, mates;
        Offer(Player seller, int id, int qty, int unit) { this.seller = seller; this.id = id; this.qty = qty; this.unit = unit; this.created = System.currentTimeMillis(); }
        long total() { return (long) unit * qty; }
        Player humanBuyer;
        // Haggling (2026-10-21): per player {agreed total, agreed qty, tries, counter total, counter qty, their last bid}
        final Map<String, long[]> hag = new HashMap<>();
        long lastDue;       // what the player in the window agreed to pay
        long holdUntil;     // a player is haggling or has a deal: no bot buyer, no expiry, until then
        boolean onRequest;  // put up because a player asked for it: what doesn't sell stays in the bank
        boolean errand;     // fetched on a player's order (Companions): if they never collect, it goes to the Exchange
        long[] hag(Player p) { return hag.computeIfAbsent(p.getUsername().toLowerCase(Locale.ROOT), k -> new long[]{0, 0, 0, 0, 0, -1}); }
        long dueFrom(Player p) { long[] h = hag.get(p.getUsername().toLowerCase(Locale.ROOT)); return h != null && h[0] > 0 ? h[0] : total(); }
        int qtyFor(Player p) { long[] h = hag.get(p.getUsername().toLowerCase(Locale.ROOT)); return h != null && h[0] > 0 && h[1] > 0 ? (int) Math.min(qty, h[1]) : qty; }
    }

    static final class Owed {
        final Player artisan, gatherer; final int product; int qty; final long since; // qty shrinks as batches are delivered (review 10-22)
        Owed(Player artisan, Player gatherer, int product, int qty) { this.artisan = artisan; this.gatherer = gatherer; this.product = product; this.qty = qty; this.since = System.currentTimeMillis(); }
        int rawId, rawQty; // what the gatherer handed over: returned if the artisan can't pay (audit #9)
        int took; // how much of it was still in the artisan's bank at the refund
    }

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots() || !ON) return;
        GameWorld.getPulser().submit(new Pulse(2) {
            @Override public boolean pulse() { try { tick(); } catch (Throwable t) { System.out.println("[Deals] tick: " + t); } return false; }
        });
        BotChat.LISTENERS.add(Deals::heard); // players haggling at a callout
        if (Boolean.getBoolean("tradetalk.test")) GameWorld.getPulser().submit(new Pulse(250) { // trials only
            @Override public boolean pulse() { try { TradeTalk.selfTest(); } catch (Throwable t) { System.out.println("[TradeTest] " + t); t.printStackTrace(); } return true; }
        });
        System.out.println("[Deals] bank callouts, hub deals and refining splits on");
    }

    static String name(int id) { return MarketBrain.name(id).toLowerCase(Locale.ROOT); }

    /** Selling at a callout, or the chosen buyer on the way: the bot's own think loop waits. */
    static boolean involved(Player p) {
        if (p == null) return false;
        for (Offer o : OFFERS) if (!o.done && (o.seller == p || o.buyer == p)) return true;
        return false;
    }

    // ------------------------------------------------------------------ hauls arriving at a bank

    /**
     * Called from the bank deposit. Returns true when the haul was taken off the seller's hands here (as a split or a
     * callout) and must NOT be supplied to the Exchange by the caller; the items stay in the seller's bank meanwhile.
     */
    public static boolean arrive(Player seller, int id, int n) {
        if (Boolean.getBoolean("afk.debug")) System.out.println("[Deals] arrive " + (seller != null ? seller.getUsername() : "?") + " " + n + " x " + name(id) + " (tradeable " + MarketBrain.tradeable(id) + ")");
        boolean dbg = Boolean.getBoolean("afk.debug");
        if (!ON || seller == null || !seller.isArtificial()) { if (dbg) System.out.println("[Deals]  no: off/not a bot"); return false; }
        SmartAdventurers.Brain eb = SmartAdventurers.BRAINS.get(seller.getUsername().toLowerCase(Locale.ROOT));
        if (eb != null && eb.errandFor != null && eb.errandId == id) return true; // a player's order: stays in the bank until the trip ends (Companions.errandDone)
        boolean hatesClerks = "the Exchange clerks".equals(SmartAdventurers.grudgeOf(seller)); // would rather sell at the bank
        if (n < (Ledger.Wallet.coins(seller) < 400 || hatesClerks ? Math.min(MIN, 10) : MIN)) { if (dbg) System.out.println("[Deals]  no: too few (" + n + " < " + MIN + ")"); return false; } // a broke bot sells from 10, to get paid on the spot
        if (!(seller instanceof AIPlayer) || !MarketBrain.tradeable(id)) { if (dbg) System.out.println("[Deals]  no: " + seller.getClass().getSimpleName() + " tradeable=" + MarketBrain.tradeable(id)); return false; }
        SmartAdventurers.Brain brain = SmartAdventurers.BRAINS.get(seller.getUsername().toLowerCase(Locale.ROOT));
        if (brain != null && brain.deliverTo != null && brain.deliverId == id) { brain.startDelivery(); return true; } // promised to an artisan: carry it there
        if (trySplit(seller, id, n)) return true;
        if (Bounty.wantedNow(id)) { if (dbg) System.out.println("[Deals]  no: on the bounty board"); return false; } // morning review 10-23: the Exchange is short of it; bots don't sell it
        int guide = ref(id);
        if (guide <= 0) { if (dbg) System.out.println("[Deals]  no: guide 0"); return false; }
        for (Offer o : OFFERS) if (o.seller == seller) { if (dbg) System.out.println("[Deals]  no: already calling out"); return false; } // one callout at a time
        Player mate = BotChat.Trust.trustedNear(seller, 15); // a player this bot knows well is in the bank
        int unit = Math.max(1, guide * (mate != null ? 91 : DISCOUNT_PERCENT) / 100); // morning review 10-23: never under the 92%/91% floors (was 88/90)
        Offer o = new Offer(seller, id, n, unit);
        o.mates = mate != null;
        calloutsMade++;
        if (calloutsMade % 25 == 0) System.out.println("[Deals] " + calloutsMade + " bank callouts so far this boot, " + tradesToday + " bot buys, " + humanBuys + " player buys");
        if (mate != null) BotChat.say(seller, "Mates' rates for you, " + mate.getUsername() + ": " + unit + " each.");
        OFFERS.add(o);
        BotChat.say(seller, String.format(BotChat.pick("Selling %d %s at %d each, under guide. Trade me.", "%d %s going cheap, %d ea. Trade me before the clerks get them.", "Anyone want %d %s? %d each, saves you the Exchange.", "%d %s, %d a piece. Below guide. Trade me.", "Anyone? %d %s at %d. Cheaper than the clerks.", "Fresh %2$s, %1$d of them, %3$d each. Trade me quick.", "%d %s going at %d each. First come, first served."), n, name(id), unit));
        if (hatesClerks && RNG.nextInt(3) == 0) BotChat.say(seller, BotChat.pick("Rather sell to a person than those clerks.", "Clerks can whistle for it."));
        try { BotChat.chimeCallout(seller, name(id), unit); } catch (Throwable ignored) { }
        BotChat.activity(seller, () -> "selling " + n + " " + name(id) + " at the bank");
        if (Boolean.getBoolean("afk.debug")) System.out.println("[Deals] " + seller.getUsername() + " calls out " + n + " x " + name(id) + " at " + unit + " ea");
        return true;
    }

    /**
     * Point 1, arranged meets. An artisan within 120 tiles whose trade is short of an input this bot can gather asks for
     * it: the bot takes that job and, when the haul is banked, carries it to the artisan instead of the Exchange.
     * One ask per artisan per 10 minutes; the bot must have the levels and the spot must have room.
     */
    static SmartAdventurers.Job errand(SmartAdventurers.Brain brain) {
        if (!ON || brain.bot == null || RNG.nextInt(3) != 0) return null;
        long now = System.currentTimeMillis();
        for (Player a : Artisans.ARTISAN_BOTS) {
            if (a == null || !a.isActive() || a.getLocation().getDistance(brain.bot.getLocation()) > 120) continue;
            if (now - ERRAND_ASKED.getOrDefault(a.getUsername(), 0L) < 10 * 60_000) continue;
            Artisans.Trade t = Artisans.tradeOf(a);
            if (t == null) continue;
            for (java.util.Map.Entry<Integer, Integer> e : Artisans.SHORT.entrySet()) {
                int id = e.getKey();
                if (!t.name().equals(Bounty.INPUT_USERS.get(id))) continue;
                SmartAdventurers.Job job = brain.jobFor(id);
                if (job == null) continue;
                ERRAND_ASKED.put(a.getUsername(), now);
                BotChat.speak(a, brain.bot, java.util.Arrays.asList(String.format(BotChat.pick("%s! Fetch me some %s and I'll pay over the clerks.", "Oi %s, I'm out of %s. Bring me a load?", "%s, if you're heading out: %s. Good money."), brain.bot.getUsername(), name(id))), 1);
                brain.takeErrand(a, id, job);
                return job;
            }
        }
        return null;
    }

    /** A gatherer's raw goods go to an artisan of the matching trade standing in the same bank, for a share of the output. */
    static boolean trySplit(Player seller, int id, int n) {
        String trade = Bounty.INPUT_USERS.get(id);
        if (trade == null) return false;
        Player artisan = null;
        for (Player a : Artisans.ARTISAN_BOTS) {
            if (a == null || !a.isActive() || a.getLocation().getDistance(seller.getLocation()) > 35 || a.getLocation().getZ() != seller.getLocation().getZ()) continue; // the anvil, furnace or range next door (second consultant check)
            Artisans.Trade t = Artisans.tradeOf(a);
            if (t != null && t.name().equals(trade)) { artisan = a; break; }
        }
        if (artisan == null) return false;
        for (Owed w : OWED) if (w.gatherer == seller) return false; // one open split per gatherer
        // Any recipe of the trade that takes this item: one-input (fish, iron ore, herbs) or more, provided the artisan
        // already holds the other inputs (coal for steel/mithril/adamant, vials of water for potions). Best = most batches.
        Artisans.Recipe recipe = null; int batches = 0, inAmt = 0;
        for (Artisans.Recipe r : Artisans.RECIPES) {
            if (!r.trade.equals(trade)) continue;
            int idx = -1; for (int k = 0; k < r.inIds.length; k++) if (r.inIds[k] == id) idx = k;
            if (idx < 0) continue;
            int b = n / r.inAmts[idx];
            for (int k = 0; k < r.inIds.length && b > 0; k++) {
                if (k == idx) continue;
                int held = 0; try { held = artisan.getBank().getAmount(r.inIds[k]) + artisan.getInventory().getAmount(r.inIds[k]); } catch (Throwable ignored) { }
                b = Math.min(b, held / r.inAmts[k]);
            }
            if (b > batches) { batches = b; recipe = r; inAmt = r.inAmts[idx]; }
        }
        if (recipe == null || batches <= 0) return false;
        int used = batches * inAmt;
        int out = Math.max(1, (int) Math.floor(batches * recipe.amount * 0.85));
        if (!seller.getBank().remove(new Item(id, used))) return false;
        artisan.getBank().add(new Item(id, used));
        Owed ow = new Owed(artisan, seller, recipe.product, out); ow.rawId = id; ow.rawQty = used;
        OWED.add(ow);
        splitsToday++;
        final String what = name(id), prod = name(recipe.product);
        BotChat.say(seller, String.format(BotChat.pick("%s, do us %d %s out of these, keep a few for yourself.", "Oi %s. %d %s, as notes. Usual split?", "%s! %d %s for you. I'll take my share in %s."), artisan.getUsername(), used, what, prod));
        BotChat.speak(artisan, seller, java.util.Arrays.asList(BotChat.pick("Go on then. Come back for your " + prod + ".", "Usual rate. Give me a bit.", "Leave them with me.")), 3);
        try { seller.face(artisan); } catch (Throwable ignored) { }
        System.out.println("[Deals] split: " + seller.getUsername() + " gave " + artisan.getUsername() + " " + used + " x " + what + " for " + out + " x " + prod);
        BotChat.news(String.format(BotChat.pick("%s and %s have a thing going: %s in, %s out, no coins involved.", "Saw %s hand %s a sack of %s at the bank. They'll be splitting the %s."), seller.getUsername(), artisan.getUsername(), what, prod));
        return true;
    }

    /** The artisan finished a batch: pay what it owes from it first. Returns how many units are left for the Exchange. */
    public static int settleOwed(Player artisan, int product, int made) {
        int left = made;
        for (Owed w : OWED) {
            if (w.artisan != artisan || w.product != product || left <= 0) continue;
            int give = Math.min(left, w.qty);
            left -= give;
            w.qty -= give; if (w.qty <= 0) OWED.remove(w); // a 28-a-batch cook owing 85 keeps owing 57 (review 10-22)
            if (!w.gatherer.isArtificial()) Artisans.OWED_PRODUCTS.merge(product, -give, (a, b) -> Math.max(0, a + b));
            if (w.gatherer.isActive()) {
                if (!w.gatherer.getBank().add(new Item(product, give)) && !w.gatherer.getInventory().add(new Item(product, give))) Ledger.Wallet.credit(w.gatherer.getUsername(), (long) Math.max(1, MarketBrain.value(product)) * give); // a full bank doesn't eat the split (review 10-22)
                BotChat.say(artisan, String.format(BotChat.pick("%s, your %d %s are in. Kept a few for the trouble.", "That's %2$d %3$s for you, %1$s. Pleasure."), w.gatherer.getUsername(), give, name(product))); // positional: the plain %d got the name (IllegalFormatConversionException d != String, audit §11)
            } else Ledger.Wallet.credit(w.gatherer.getUsername(), (long) MarketBrain.value(product) * give); // gone for the day: coins instead
            System.out.println("[Deals] split settled: " + artisan.getUsername() + " gave " + w.gatherer.getUsername() + " " + give + " x " + name(product));
        }
        return left;
    }

    // ------------------------------------------------------------------ the tick

    static void tick() {
        long now = System.currentTimeMillis();
        for (Offer o : OFFERS) {
            if (o.done) { OFFERS.remove(o); continue; }
            if (!o.seller.isActive()) { OFFERS.remove(o); continue; }
            if (humanTrade(o, now)) continue;
            if (o.buyer != null) { meet(o, now); continue; }
            if (o.holdUntil > now) continue; // a player is haggling or has a deal agreed
            if (now - o.created > WINDOW_MS + (o.mates ? 10_000 : 0) && now - o.holdUntil > WAIT_MS) { expire(o); continue; }
            if (now - o.created >= WAIT_MS + (o.mates ? 10_000 : 0)) {
                Player b = findBuyer(o);
                if (b != null) { o.buyer = b; o.claimedAt = now; walkTo(b, scatter(o.seller)); }
            }
        }
        for (Owed w : OWED) {
            if (now - w.since < 30 * 60_000) continue;
            OWED.remove(w);
            long coins = (long) MarketBrain.value(w.product) * w.qty;
            if (Ledger.Wallet.pay(w.artisan, coins)) { Ledger.Wallet.credit(w.gatherer.getUsername(), coins); System.out.println("[Deals] split paid in coins instead: " + w.artisan.getUsername() + " -> " + w.gatherer.getUsername() + " " + coins + " gp"); }
            else if (w.rawId > 0 && w.artisan.getBank().getAmount(w.rawId) > 0 && w.artisan.getBank().remove(new Item(w.rawId, w.took = Math.min(w.rawQty, w.artisan.getBank().getAmount(w.rawId))))) { // can't pay: hand the raw goods back
                int back = w.took; // only what was taken from the artisan (was min(rawQty, rawQty): the whole lot came back even when part was already used, audit 10-22)
                if (w.gatherer.isActive()) w.gatherer.getBank().add(new Item(w.rawId, back)); else Ledger.supply(w.gatherer, w.rawId, back); // E-5 follow-up: this line had slipped into the comment above, so the goods vanished
                System.out.println("[Deals] split cancelled: " + w.artisan.getUsername() + " couldn't pay, " + back + " x " + name(w.rawId) + " returned to " + w.gatherer.getUsername());
                try { Failures.Econ.splitCancelled(w.artisan, w.product, w.rawId, back); } catch (Throwable ignored) { } // batch 19c §4.4
            }
        }
    }

    static void expire(Offer o) {
        OFFERS.remove(o);
        o.done = true;
        BotChat.clearActivity(o.seller);
        if (o.onRequest && !o.errand) { if (RNG.nextInt(2) == 0) BotChat.say(o.seller, BotChat.pick("Never mind then. Back in the bank they go.", "Offer's off. Ask again if you change your mind.", "Alright, keeping them.")); return; }
        if (o.errand) try { Failures.Econ.errandDropped(o.seller, o.id, o.qty, "expired"); } catch (Throwable ignored) { } // batch 19c §4.4
        if (o.errand) { String who = o.hag.isEmpty() ? "they" : o.hag.keySet().iterator().next(); if (o.seller.getBank().remove(new Item(o.id, o.qty))) Ledger.supply(o.seller, o.id, o.qty); BotChat.say(o.seller, BotChat.pick("Never came for them. Exchange it is.", who + " never showed. Clerks can have the lot.")); System.out.println("[Deals] errand unclaimed: " + o.seller.getUsername() + " sent " + o.qty + " x " + name(o.id) + " to the Exchange"); return; }
        if (o.seller.getBank().remove(new Item(o.id, o.qty))) Ledger.supply(o.seller, o.id, o.qty);
        if (RNG.nextInt(3) == 0) BotChat.say(o.seller, BotChat.pick("No takers. Into the Exchange it goes.", "Fine, the clerks can have it.", "Your loss. Off to the Exchange.", "Nobody? Fine. Exchange.", "Clerks it is then.", "Your loss, everyone. Off to the Exchange."));
    }

    /** The consumption lock: a bot may buy only what it will use itself. */
    static int wants(Player b, int id, int qty) {
        if (b == o_sellerPlaceholder) return 0;
        String key = b.getUsername().toLowerCase(Locale.ROOT);
        if (SmartAdventurers.BRAINS.containsKey(key)) { // adventurer: food it eats, or logs for the sawmill if it builds
            SmartAdventurers.Brain brain = SmartAdventurers.BRAINS.get(key);
            for (int[] m : SmartAdventurers.Brain.MILL) if (m[0] == id && brain != null && brain.builder() && b.getBank().getAmount(id) < 27) return Math.min(qty, 27);
            boolean food = false; for (int f : AfkTasks.Runner.FOOD) if (f == id) food = true;
            if (!food) return 0;
            int have = 0; try { for (int f : AfkTasks.Runner.FOOD) have += b.getBank().getAmount(f) + b.getInventory().getAmount(f); } catch (Throwable ignored) { }
            return have >= 12 ? 0 : Math.min(qty, 20);
        }
        if (Artisans.ARTISAN_BOTS.contains(b)) { // artisan: an input of its own trade
            Artisans.Trade t = Artisans.tradeOf(b);
            String trade = Bounty.INPUT_USERS.get(id);
            if (t == null || trade == null || !t.name().equals(trade)) return 0;
            return qty;
        }
        return 0;
    }
    static final Player o_sellerPlaceholder = null;

    static Player findBuyer(Offer o) {
        Player best = null; double bd = 99;
        for (Player b : Repository.getPlayers()) {
            if (b == null || b == o.seller || !b.isArtificial() || !b.isActive() || b.getLocation().getZ() != o.seller.getLocation().getZ()) continue;
            double d = b.getLocation().getDistance(o.seller.getLocation());
            if (d > (Artisans.ARTISAN_BOTS.contains(b) ? 35 : 15) || d >= bd) continue; // artisans hear from the workshop next door
            if (AfkTasks.RUNNING.containsKey(b.getUsername().toLowerCase(Locale.ROOT))) continue; // busy on a trip
            if (QuestRunner.active(b)) continue; // on a quest (audit 10-22)
            int want = wants(b, o.id, o.qty);
            if (want <= 0 || Ledger.Wallet.coins(b) < (long) o.unit * want) continue;
            bd = d; best = b;
        }
        return best;
    }

    /** Tile scatter: a free tile next to the seller, never one another player stands on. */
    static Location scatter(Player seller) {
        Location c = seller.getLocation();
        List<Location> free = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
            if (dx == 0 && dy == 0) continue;
            Location l = c.transform(dx, dy, 0);
            int f; try { f = RegionManager.getClippingFlag(l.getZ(), l.getX(), l.getY()); } catch (Throwable t) { continue; }
            if (f == -1 || (f & 0x200000) != 0 || (f & 0x100) != 0) continue;
            boolean taken = false;
            for (Player p : RegionManager.getLocalPlayers(l, 1)) if (p != null && p.getLocation().equals(l)) taken = true;
            if (!taken) free.add(l);
        }
        return free.isEmpty() ? c : free.get(RNG.nextInt(free.size()));
    }

    static void walkTo(Player b, Location l) { try { ((AIPlayer) b).walkToPosSmart(l); } catch (Throwable ignored) { } }

    static void meet(Offer o, long now) {
        Player b = o.buyer;
        if (!b.isActive() || now - o.claimedAt > MEET_MS) { // stood up
            try { Failures.Econ.stoodUp(b, o.seller, o.id, o.qty, o.claimedAt); } catch (Throwable ignored) { } // batch 19c §4.4 deal-stood-up (rule inside)
            o.buyer = null; o.announcedTaker = false;
            BotChat.say(o.seller, BotChat.pick("Stood me up. Putting it on the Exchange.", "Right, not waiting all day.", "Never mind. Exchange.", "Where'd they go? Exchange it is."));
            expire(o);
            return;
        }
        double d = b.getLocation().getDistance(o.seller.getLocation());
        if (d > 2.5) { if (!b.getWalkingQueue().isMoving()) walkTo(b, scatter(o.seller)); return; }
        if (!o.announcedTaker) {
            o.announcedTaker = true;
            try { b.face(o.seller); o.seller.face(b); } catch (Throwable ignored) { }
            BotChat.say(b, BotChat.pick("ill take em", "I'll take them.", "Go on, I'll have those.", "mine", "i'll have those", "go on, ill take them", "deal, trade me"));
            o.claimedAt = now - MEET_MS + 4_000; // settle in a couple of ticks
            return;
        }
        if (now - o.claimedAt < MEET_MS - 1_500) return;
        settle(o, b);
    }

    static void settle(Offer o, Player b) {
        int qty = Math.min(o.qty, wants(b, o.id, o.qty));
        if (qty <= 0) { expire(o); return; }
        long total = (long) o.unit * qty;
        if (!Ledger.Wallet.pay(b, total)) { try { Failures.Econ.settleFailed(b, o.seller, "pay", o.id, qty, total); } catch (Throwable ignored) { } expire(o); return; } // batch 19c §4.4
        if (!o.seller.getBank().remove(new Item(o.id, qty))) { boolean back = Ledger.Wallet.give(b, total); try { Failures.Econ.settleFailed(b, o.seller, "remove", o.id, qty, total); if (!back) Failures.Econ.itemLost(b, 995, total, "deal.settle", "bot"); } catch (Throwable ignored) { } expire(o); return; } // batch 19c §4.4 / §4.2
        Ledger.Wallet.give(o.seller, total);
        try { Failures.econNote(b, Failures.DEAL_BUY); Failures.econNote(o.seller, Failures.DEAL_SELL); Failures.Econ.dealPrice(o.seller, o.id, o.unit, "bot", "deal_settle"); } catch (Throwable ignored) { } // batch 19c §4.1 / §4.6
        SmartAdventurers.FIRST_PAID.putIfAbsent(o.seller.getUsername().toLowerCase(Locale.ROOT), System.currentTimeMillis());
        b.getBank().add(new Item(o.id, qty));
        tradesToday++;
        o.done = true; OFFERS.remove(o);
        BotChat.clearActivity(o.seller);
        String notes = qty > 20 ? ", as notes" : "";
        BotChat.say(o.seller, String.format(BotChat.pick("%d %s%s. Cheers, %s.", "Done. %d %s%s, pleasure doing business, %s."), qty, name(o.id), notes, b.getUsername()));
        System.out.println("[Deals] " + o.seller.getUsername() + " sold " + qty + " x " + name(o.id) + " to " + b.getUsername() + " at the bank for " + total + " gp");
        if (o.qty > qty && !o.onRequest) { o.seller.getBank().remove(new Item(o.id, o.qty - qty)); Ledger.supply(o.seller, o.id, o.qty - qty); }
    }

    // ------------------------------------------------------------------ a human steps in

    static boolean humanTrade(Offer o, long now) {
        TradeModule mine = TradeModule.getExtension(o.seller);
        if (mine == null) {
            if (o.acceptedAt == 0) { // no window (or the human closed it before the bot accepted): anything put up goes back to the bank
                try { int nid = ItemDefinition.forId(o.id).getNoteId(); int inv = o.seller.getInventory().getAmount(o.id) + (nid > 0 ? o.seller.getInventory().getAmount(nid) : 0); if (inv > 0) { if (o.seller.getInventory().getAmount(o.id) > 0) o.seller.getInventory().remove(new Item(o.id, o.seller.getInventory().getAmount(o.id))); if (nid > 0 && o.seller.getInventory().getAmount(nid) > 0) o.seller.getInventory().remove(new Item(nid, o.seller.getInventory().getAmount(nid))); o.seller.getBank().add(new Item(o.id, inv)); } } catch (Throwable ignored) { }
                return false;
            }
            // The window closed after the bot accepted (audit #24): did the trade go through, or did the player back out?
            o.acceptedAt = 0;
            int noteId = ItemDefinition.forId(o.id).getNoteId();
            int left = o.seller.getInventory().getAmount(o.id) + (noteId > 0 ? o.seller.getInventory().getAmount(noteId) : 0);
            if (left < o.qty && left > 0) { // bought part of it: the rest goes back in the bank and the callout carries on
                int sold = o.qty - left; long paid = o.lastDue;
                bargainSold(o, sold, paid); // batch 24b stage 2
                try {
                    if (o.seller.getInventory().getAmount(o.id) > 0) o.seller.getInventory().remove(new Item(o.id, o.seller.getInventory().getAmount(o.id)));
                    if (noteId > 0 && o.seller.getInventory().getAmount(noteId) > 0) o.seller.getInventory().remove(new Item(noteId, o.seller.getInventory().getAmount(noteId)));
                    o.seller.getBank().add(new Item(o.id, left));
                } catch (Throwable ignored) { }
                o.qty = left; humanBuys++;
                try { Failures.econNote(o.seller, Failures.PLAYER_TRADE); } catch (Throwable ignored) { } // batch 19c §4.1: paid through the trade window, not the Wallet
                if (o.humanBuyer != null) { BotChat.Trust.add(o.humanBuyer, o.seller, 1); o.hag.remove(o.humanBuyer.getUsername().toLowerCase(Locale.ROOT)); }
                System.out.println("[Deals] " + o.seller.getUsername() + " sold " + sold + " x " + name(o.id) + " to player " + (o.humanBuyer != null ? o.humanBuyer.getUsername() : "?") + " for " + paid + " gp, " + left + " left");
                BotChat.say(o.seller, BotChat.pick("Pleasure. " + left + " left, " + o.unit + " each.", "Cheers! Still " + left + " going at " + o.unit + ".", "Sold. " + left + " more if anyone wants them."));
                o.holdUntil = 0;
                return false;
            }
            if (left < o.qty) {
                bargainSold(o, o.qty, o.lastDue > 0 ? o.lastDue : o.total()); // batch 24b stage 2
                o.done = true; OFFERS.remove(o); humanBuys++;
                try { Failures.econNote(o.seller, Failures.PLAYER_TRADE); } catch (Throwable ignored) { } // batch 19c §4.1
                if (o.errand) { SmartAdventurers.Brain sb = SmartAdventurers.BRAINS.get(o.seller.getUsername().toLowerCase(Locale.ROOT)); if (sb != null) sb.idleUntil = 0; } // collected: back to work
                BotChat.clearActivity(o.seller);
                if (o.humanBuyer != null) { BotChat.Trust.add(o.humanBuyer, o.seller, 1); BotChat.news(String.format("%s beat the bots to %s's %s at the bank. Quick hands.", o.humanBuyer.getUsername(), o.seller.getUsername(), name(o.id))); }
                System.out.println("[Deals] " + o.seller.getUsername() + " sold " + o.qty + " x " + name(o.id) + " to player " + (o.humanBuyer != null ? o.humanBuyer.getUsername() : "?") + " for " + (o.lastDue > 0 ? o.lastDue : o.total()) + " gp");
                return true;
            }
            try { // backed out: the haul goes back in the bank and the callout carries on
                if (o.seller.getInventory().getAmount(o.id) > 0) o.seller.getInventory().remove(new Item(o.id, o.seller.getInventory().getAmount(o.id)));
                if (noteId > 0 && o.seller.getInventory().getAmount(noteId) > 0) o.seller.getInventory().remove(new Item(noteId, o.seller.getInventory().getAmount(noteId)));
                o.seller.getBank().add(new Item(o.id, left));
            } catch (Throwable ignored) { }
            BotChat.say(o.seller, "Changed your mind? Offer's still open.");
            return false;
        }
        Player target = mine.getTarget();
        if (target == null || target.isArtificial()) return false;
        if (o.buyer != null && o.buyer.isArtificial()) { o.buyer = null; o.announcedTaker = false; } // the human beat the bot to it
        o.claimedAt = now; // a human in the window stops the clock
        o.holdUntil = Math.max(o.holdUntil, now + 5_000);
        if (!offeredToHuman(o, mine, target)) return true;
        TradeModule theirs = TradeModule.getExtension(target);
        if (theirs == null || mine.isAccepted()) return true;
        int want = o.qtyFor(target), shown = inWindow(o, mine); // audit E-1: the window must hold what the deal covers, no more
        if (shown != want) { fixWindow(o, mine, shown, want); return true; }
        int coins = theirs.getContainer().getAmount(995);
        boolean onlyCoins = true;
        for (Item it : theirs.getContainer().toArray()) if (it != null && it.getId() != 995) onlyCoins = false;
        long due = o.dueFrom(target);
        if (onlyCoins && coins >= due) {
            mine.setAccepted(true, true);
            o.acceptedAt = now; o.humanBuyer = target; o.lastDue = due; // done only once the window closes with the goods gone
        } else if (coins > 0 && coins < due && RNG.nextInt(15) == 0) BotChat.say(o.seller, BotChat.pick("That's " + String.format("%,d", due - coins) + " gp short, friend.", String.format("%,d", due - coins) + " gp more and it's a deal.", "Bit light. " + String.format("%,d", due) + " gp, we said."));
        return true;
    }

    /** Puts the haul in the trade window (noted when there are more than a few), once. */
    static boolean offeredToHuman(Offer o, TradeModule mine, Player target) {
        if (mine.getContainer().getAmount(o.id) > 0 || (ItemDefinition.forId(o.id).getNoteId() > 0 && mine.getContainer().getAmount(ItemDefinition.forId(o.id).getNoteId()) > 0)) return true;
        Player s = o.seller;
        int noteId = ItemDefinition.forId(o.id).getNoteId();
        Item inv = noteId > 0 && o.qty > 1 ? new Item(noteId, o.qty) : new Item(o.id, o.qty);
        if (s.getInventory().getAmount(inv.getId()) < o.qty) {
            if (!s.getBank().remove(new Item(o.id, o.qty))) return false;
            if (!s.getInventory().add(inv)) { s.getBank().add(new Item(o.id, o.qty)); return false; }
        }
        int slot = s.getInventory().getSlot(inv);
        if (slot >= 0) mine.getContainer().offer(slot, o.qtyFor(target));
        return true;
    }

    /** Audit E-1: how many of the haul sit in the bot's side of the window, noted or not. */
    static int inWindow(Offer o, TradeModule mine) {
        int nid = ItemDefinition.forId(o.id).getNoteId();
        return mine.getContainer().getAmount(o.id) + (nid > 0 ? mine.getContainer().getAmount(nid) : 0);
    }

    /** Audit E-1: brings the window to the agreed quantity on the first screen; anything else (or a failed change) declines. */
    static void fixWindow(Offer o, TradeModule mine, int shown, int want) {
        int nid = ItemDefinition.forId(o.id).getNoteId();
        if (mine.getStage() == 0) {
            try {
                if (shown > want) {
                    int id = mine.getContainer().getAmount(o.id) > 0 ? o.id : nid;
                    int slot = mine.getContainer().getSlot(new Item(id));
                    if (slot >= 0) mine.getContainer().withdraw(slot, shown - want);
                } else {
                    Item inv = nid > 0 && o.seller.getInventory().getAmount(nid) > 0 ? new Item(nid) : new Item(o.id);
                    int slot = o.seller.getInventory().getSlot(inv);
                    if (slot >= 0) mine.getContainer().offer(slot, want - shown);
                }
            } catch (Throwable ignored) { }
            if (inWindow(o, mine) == want) return;
        }
        System.out.println("[Deals] " + o.seller.getUsername() + " declined a trade: " + shown + " x " + name(o.id) + " in the window, deal was for " + want);
        try { mine.decline(); } catch (Throwable ignored) { }
        BotChat.say(o.seller, "Wrong amount in the window. Trade me again.");
    }

    // ------------------------------------------------------------------ haggling (2026-10-21)

    /** A player said something near a callout: is it about the deal? Price questions, offers, "i'll take 10", "deal". */
    static boolean heard(Player speaker, String text) {
        if (speaker == null || speaker.isArtificial() || OFFERS.isEmpty() || text == null) return false;
        String lt = text.toLowerCase(Locale.ROOT);
        Offer best = null; double bd = Double.MAX_VALUE; boolean bestNamed = false, bestItem = false;
        for (Offer o : OFFERS) {
            if (o.done || o.seller == null || !o.seller.isActive() || o.seller.getLocation().getZ() != speaker.getLocation().getZ()) continue;
            if (o.buyer != null && o.buyer.isArtificial() && o.announcedTaker) continue; // a bot already said "mine"
            double d = o.seller.getLocation().getDistance(speaker.getLocation());
            boolean named = lt.contains(o.seller.getUsername().toLowerCase(Locale.ROOT));
            String nm = name(o.id), stem = nm.endsWith("s") ? nm.substring(0, nm.length() - 1) : nm;
            boolean item = lt.contains(stem) || lt.contains(nm.replaceAll("^raw |^cooked ", ""));
            if (d > (named ? 20 : 12)) continue;
            double score = d - (named ? 50 : 0) - (item ? 20 : 0);
            if (score < bd) { bd = score; best = o; bestNamed = named; bestItem = item; }
        }
        if (best == null) return false;
        try { return haggle(best, speaker, text, bestNamed || bestItem); } catch (Throwable t) { System.out.println("[Deals] haggle: " + t); return false; }
    }

    static String each(long total, int n) { return n > 1 ? " (about " + String.format("%,d", (total + n / 2) / n) + " each)" : ""; }

    /**
     * Batch 24b stage 2: the Bargain engine prices a Deals offer (same guards as stage 1: floor = 92% of guide, 91% for a
     * mate, never above the ask; the trader's daily discount budget; the exit rule). It only sets the hag state the old
     * code sets ({agreed total, qty, tries, counter total, counter qty}); the trade window still settles, through
     * offeredToHuman/humanTrade, and {@link #bargainSold} books the sale.
     */
    static boolean bargain(Offer o, Player h, long[] s, int n, long ask, long floor, long bid, boolean free, long now) {
        String bot = o.seller.getUsername(), hn = h.getUsername();
        boolean lowball = free || bid <= 0 || bid < ask * Bargain.COUNTER_AT;
        if (Bargain.holdUntil(bot, hn) > now && !lowball) { // walked away from this player: no haggling until the hold ends (a lowball goes on to the abuse hold)
            BotChat.say(o.seller, BotChat.pick("I said good day, " + hn + ". " + Haggle.gp(ask) + " gp or nothing.", "Not haggling with you today. " + Haggle.gp(ask) + " gp."));
            return true;
        }
        Bargain.Session bs = Bargain.session(bot, hn);
        if (bs == null || bs.list != ask || bs.units != n) { bs = Bargain.sell(bot, hn, ask, floor, BargainHooks.trust(h, o.seller), BargainHooks.afk(h)); bs.units = n; }
        Bargain.Result r = Bargain.decide(bs, bid, free, now);
        if (r.lowball) BargainHooks.lowball(h);
        BotChat.say(o.seller, Bargain.line(r.lineKey, BargainHooks.style(o.seller), hn, Haggle.gp(r.total)));
        switch (r.verdict) {
            case ACCEPT: s[0] = r.total; s[1] = n; s[3] = 0; o.holdUntil = now + 60_000; break;
            case COUNTER: case FINAL: if (r.total < ask) { s[3] = r.total; s[4] = n; } else s[3] = 0; break; // "deal" takes the quote (step 1)
            case WALK:
                if (r.trustStrike) BargainHooks.strike(h, o.seller);
                o.hag.remove(hn.toLowerCase(Locale.ROOT)); o.holdUntil = 0;
                break;
            default: break; // REFUSE: the quote stands
        }
        System.out.println("[Bargain] deals " + bot + " <- " + hn + " bid " + bid + " for " + n + " (ask " + ask + ", floor " + bs.floor + "): " + r); // rare (players haggling only); trials read it
        return true;
    }

    /** Batch 24b stage 2: a finished window sale of {@code qty} for {@code paid}: books it in the engine (discount, exit rule, deals log) if the engine priced that qty. */
    static void bargainSold(Offer o, int qty, long paid) {
        try {
            Player h = o.humanBuyer; if (h == null) return;
            Bargain.Session bs = Bargain.session(o.seller.getUsername(), h.getUsername());
            if (bs == null) return;
            if (bs.units == qty && paid > 0) { Bargain.sold(bs, paid, System.currentTimeMillis()); BargainHooks.dealt(h, o.seller, name(o.id), qty, paid); }
            else Bargain.close(o.seller.getUsername(), h.getUsername());
        } catch (Throwable t) { System.out.println("[Deals] bargain sold: " + t); }
    }

    static boolean haggle(Offer o, Player h, String text, boolean pointed) {
        long now = System.currentTimeMillis();
        String hn = h.getUsername(), item = name(o.id);
        if (TradeModule.getExtension(o.seller) != null) { // audit E-1: no new terms while a trade window is open
            if (RNG.nextInt(3) == 0) BotChat.say(o.seller, "Finish the trade first, " + hn + ". The window has the deal in it.");
            return true;
        }
        Haggle.Bid b = Haggle.read(text, o.qty);
        long[] st = o.hag.get(hn.toLowerCase(Locale.ROOT));
        boolean pending = st != null && st[3] > 0;
        boolean shortMsg = b.text.split(" ").length <= 6;
        // Nothing in it but deal words? ("how much for 10?", "too expensive"). Otherwise it may be about something else.
        String rest = b.text.replaceAll("[0-9.,?!%/']", " ").replaceAll("\\b(how|much|many|price|prices|for|the|lot|them|those|these|it|that|is|are|they|each|ea|what|whats|s|of|a|all|please|pls|mate|you|selling|sell|still|left|got|any|do|does|cost|x|k|m|gp|coins|per|piece|apiece|now|then|ok|so|and|or|r|your|yours|want|i|ll|take|can|could|get|buy|would|will|give|pay|offer|meet|middle|in|at|half|off|deal|sure|fine|too|expensive|cheaper|discount|lower|bit|steep|pricey|me|my|some|how's|hows|about|re|m|d|ve|em|one|ones|have|need|like|just|only|best|is|be|go|down|to|there|here|guide|less|more|split|difference)\\b", " ").trim();
        boolean dealWords = rest.isEmpty() || rest.contains(item) || rest.contains(item.replaceAll("s$", ""));
        if (!pointed && !dealWords && !b.hasPrice() && b.qty == 0) return false;
        int guide = Math.max(1, ref(o.id));
        // 1. "deal" / "ok" after the seller countered
        if (b.agree && pending) {
            st[0] = st[3]; st[1] = st[4]; st[3] = 0; o.holdUntil = now + 60_000;
            BotChat.say(o.seller, BotChat.pick("Deal. Trade me and put up " + Haggle.gp(st[0]) + " gp.", "Done, " + hn + ". " + Haggle.gp(st[0]) + " gp, trade me.", "Shake on it. " + Haggle.gp(st[0]) + " gp in the window and they're yours."));
            return true;
        }
        if (b.refuse && pending) { st[3] = 0; BotChat.say(o.seller, BotChat.pick("Suit yourself. Still " + o.unit + " each.", "Your loss, " + hn + ".", "Fair enough. Offer stands at " + o.unit + ".")); return true; }
        // 2. what's it cost / how many
        boolean infoQ = b.text.matches(".*\\b(how many|how much|price|what (?:are|r) (?:you|u) selling|what you selling|whatcha selling|still selling|still got|any left|got left|how much each|is it|are they|selling what|what is it|what are they)\\b.*");
        if (!b.hasPrice() && (b.asks || infoQ) && (pointed || shortMsg)) {
            int n = b.qty > 0 ? Math.min(b.qty, o.qty) : o.qty;
            if (b.qty > o.qty) BotChat.say(o.seller, "Only got " + o.qty + ", " + hn + ".");
            BotChat.say(o.seller, BotChat.pick(n + " " + item + " at " + o.unit + " each, " + Haggle.gp((long) o.unit * n) + " gp" + (n < o.qty ? " for those." : " for the lot.") + " Trade me.",
                    o.unit + " each, " + hn + ". " + (n == o.qty ? "All " + n + " for " : n + " for ") + Haggle.gp((long) o.unit * n) + ".",
                    Haggle.gp((long) o.unit * n) + " gp for " + n + ". Guide's " + guide + ", so you're saving."));
            if (b.qty > 0 && b.qty < o.qty) { long[] s = o.hag(h); s[0] = (long) o.unit * n; s[1] = n; }
            else if (b.lot) { long[] s = o.hag(h); s[0] = o.total(); s[1] = o.qty; s[3] = 0; } // "how much for the lot" after "i'll take 10": the lot now (audit 10-21 "part-lot haggling after the lot": the deal stayed at 10)
            o.holdUntil = Math.max(o.holdUntil, now + 20_000);
            return true;
        }
        // "actually, the lot" / "i'll take the lot": everything at the asking price (it stayed at an earlier part deal)
        if (b.lot && b.qty == 0 && !b.hasPrice() && (b.buying || b.cued || pointed)) {
            long[] s = o.hag(h); s[0] = o.total(); s[1] = o.qty; s[3] = 0;
            o.holdUntil = now + 60_000;
            BotChat.say(o.seller, BotChat.pick("All " + o.qty + " then, " + hn + ". " + Haggle.gp(s[0]) + " gp. Trade me.", "The lot it is: " + o.qty + " " + item + ", " + Haggle.gp(s[0]) + " gp."));
            return true;
        }
        // 3. "i'll take 10" / "can i get 25"
        if (b.qty > 0 && !b.hasPrice() && (b.buying || b.cued || pointed)) {
            int n = Math.min(b.qty, o.qty);
            long[] s = o.hag(h); s[0] = (long) o.unit * n; s[1] = n; s[3] = 0;
            o.holdUntil = now + 60_000;
            BotChat.say(o.seller, (b.qty > o.qty ? "Only got " + o.qty + ". " : "") + BotChat.pick(n + " for " + Haggle.gp(s[0]) + " gp, " + hn + ". Trade me.", "Sure. " + n + " " + item + ", " + Haggle.gp(s[0]) + " gp. Trade me.", "Coming up: " + n + " for " + Haggle.gp(s[0]) + "."));
            return true;
        }
        if (b.agree && st != null && st[0] > 0) { o.holdUntil = now + 60_000; BotChat.say(o.seller, BotChat.pick("Trade me then, " + hn + ". " + Haggle.gp(st[0]) + " gp.", "Go on, trade me. " + Haggle.gp(st[0]) + " gp, like we said.")); return true; }
        if (b.agree && pointed && !pending) { long[] s = o.hag(h); if (s[0] == 0) { s[0] = o.total(); s[1] = o.qty; } o.holdUntil = now + 60_000; BotChat.say(o.seller, "Trade me then, " + hn + ". " + Haggle.gp(s[0]) + " gp."); return true; }
        // 4. an offer
        if (b.hasPrice() && (b.cued || pointed)) {
            long[] s = o.hag(h);
            int n = b.qty > 0 ? Math.min(b.qty, o.qty) : b.lot ? o.qty : s[1] > 0 && s[0] > 0 ? (int) s[1] : o.qty;
            long ask = (long) o.unit * n;
            long bid = b.total(ask, n, s[5], true);
            if (bid < 0) return false;
            s[5] = bid;
            o.holdUntil = now + 45_000;
            boolean mate = BotChat.Trust.trustedNear(o.seller, 15) == h || o.mates;
            long floor = (long) guide * n * (mate ? 91 : 92) / 100; // morning review 10-23: above what the bot buyer pays (90%); was 85/86 (audit E-14), 76/80 before
            if (Bargain.on()) return bargain(o, h, s, n, ask, floor, bid, b.free, now); // batch 24b stage 2: the one engine, behind -Dbargain.on (default off)
            double accept = s[2] == 0 ? (mate ? 0.92 : 0.96) : (mate ? 0.88 : 0.92);
            if (bid == 0 || b.free) { s[2]++; BotChat.say(o.seller, BotChat.pick("Free? I've got bills, " + hn + ".", "Nice try. " + o.unit + " each.", "Ha! Nothing's free at a bank.")); return true; }
            if (bid >= ask) { s[0] = ask; s[1] = n; s[3] = 0; BotChat.say(o.seller, BotChat.pick("That's my price anyway! Trade me, " + Haggle.gp(ask) + " gp.", "Easy. " + Haggle.gp(ask) + " gp, trade me.")); return true; }
            if (bid >= ask * accept && bid >= floor) { // audit E-14: an accepted bid is never under the floor either (92% of the ask was 84% of guide)
                s[0] = bid; s[1] = n; s[3] = 0; o.holdUntil = now + 60_000;
                BotChat.say(o.seller, BotChat.pick("Go on then. " + Haggle.gp(bid) + " gp for " + n + ". Trade me.", "Fine, " + hn + ", " + Haggle.gp(bid) + each(bid, n) + ". Trade me.", "You drive a hard bargain. " + Haggle.gp(bid) + " gp, deal.", "Alright, " + Haggle.gp(bid) + ". Put it in the window."));
                return true;
            }
            if (s[2] >= 2) { long c = s[3] > 0 ? s[3] : ask; BotChat.say(o.seller, BotChat.pick(Haggle.gp(c) + " gp, final offer, " + hn + ".", "No lower. " + Haggle.gp(c) + each(c, n) + ", take it or leave it.", "I've given you my best: " + Haggle.gp(c) + ".")); s[3] = c; s[4] = n; return true; }
            s[2]++;
            if (bid >= floor) {
                long c = Math.max(floor, Haggle.round((ask + bid) / 2));
                if (c >= ask) c = ask;
                s[3] = c; s[4] = n;
                BotChat.say(o.seller, BotChat.pick("Can't do " + Haggle.gp(bid) + ". Meet me at " + Haggle.gp(c) + each(c, n) + "?", Haggle.gp(c) + " and they're yours, " + hn + ".", "How about " + Haggle.gp(c) + "? Say deal.", "Split the difference: " + Haggle.gp(c) + "."));
                return true;
            }
            BotChat.say(o.seller, BotChat.pick(Haggle.gp(bid) + "? They're already under guide, " + hn + ". " + o.unit + " each.", "Not a chance. Guide's " + guide + " each and I'm asking " + o.unit + ".", "Ha! The clerks pay more than that.", "Try again, " + hn + ". " + o.unit + " each."));
            return true;
        }
        // 5. "too expensive" without a number
        if (b.cheaper && (pointed || shortMsg)) {
            BotChat.say(o.seller, BotChat.pick("It's under guide already! Make me an offer.", "Name a price, " + hn + ".", "Guide's " + guide + ". I'm asking " + o.unit + ". Go on, offer.", "Cheaper than the Exchange, friend. But try me."));
            o.holdUntil = Math.max(o.holdUntil, now + 20_000);
            return true;
        }
        return false;
    }

    /**
     * A player asked a bot standing at a bank for something it has ("do you have any coal?", "sell me your lobsters"):
     * the bot puts it up for that player just like a callout, at a little under guide. Returns what the bot says, or
     * null when it can't (not in its bank, it needs it itself, it's busy, or it's already selling something).
     */
    static String sellOnRequest(Player bot, Player human, int id) {
        if (!ON || bot == null || human == null || !MarketBrain.tradeable(id)) return null;
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(bot.getUsername().toLowerCase(Locale.ROOT));
        if (br == null) return null;
        String item = name(id);
        int have = bot.getBank().getAmount(id);
        if (have <= 0 && bot.getEquipment().getAmount(id) > 0) return "I'm wearing it, " + human.getUsername() + ". Not for sale.";
        if (have <= 0) return null;
        if (Bounty.wantedNow(id)) return BotChat.pick("Can't, the Exchange is short of " + item + ". They're going to the clerks.", "Not those, we're short of " + item + " ourselves.");
        if (PlayerTrades.want(bot, br, id) > 0 || id == br.goal) return BotChat.pick("I need my " + item + ", sorry.", "Keeping those. I use them.", "Not selling, I'm using them.");
        for (int[] su : br.supplyNeeds()) if (su[0] == id) return "Those are for me, sorry. I use them.";
        for (Offer o : OFFERS) if (o.seller == bot) return "One sale at a time. I'm selling " + name(o.id) + " right now.";
        if (!PlayerTrades.idle(br)) return BotChat.pick("I've got some in the bank. Catch me at one and I'll sell you some.", "In the bank, yes. Find me there.");
        int guide = ref(id);
        if (guide <= 0) return null;
        boolean mate = BotChat.Trust.trustedNear(bot, 15) == human;
        int unit = Math.max(1, guide * (mate ? 91 : 95) / 100);
        Offer o = new Offer(bot, id, have, unit);
        o.onRequest = true; o.mates = mate;
        o.holdUntil = System.currentTimeMillis() + 60_000;
        long[] s = o.hag(human); s[0] = o.total(); s[1] = have;
        OFFERS.add(o);
        BotChat.activity(bot, () -> "selling " + item + " to " + human.getUsername());
        return BotChat.pick("Got " + have + " " + item + " in the bank. " + unit + " each, " + Haggle.gp(o.total()) + " the lot. Trade me.",
                have + " of them, " + human.getUsername() + ". " + unit + " a piece. Trade me, or say how many.",
                "Sure. " + have + " at " + unit + " each" + (mate ? ", mates' rates" : "") + ". Trade me.");
    }
}
