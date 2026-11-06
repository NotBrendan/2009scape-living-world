package content.custom;

import core.api.StartupListener;
import core.cache.def.impl.ItemDefinition;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.request.trade.TradeModule;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * the owner's answer to the review (2026-10-20): any idle bot at a bank or workshop accepts a player's trade for things on
 * its own want-list, at guide price. A player offers 20 lobsters, or the rune scimitar the bot is saving for; the bot
 * checks its wallet, puts the guide-price coins in the window, accepts, and says thanks.
 *
 * Want-list (the buyer consumption lock, so nobody can dump junk on a bot): its goal item, armour pieces it is missing
 * for the tier it can wear, food if it fights, bones while it still buries them, and for an artisan the inputs of its
 * trade. Anything else in the window and the bot says it has no use for it and won't accept. A bot that is busy (on a
 * trip, a shop run, a delivery) says so and points the player to the bank.
 */
public final class PlayerTrades implements StartupListener {
    static final Random RNG = new Random();
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("playertrades", "true"));
    static final int[] FOODS = {379, 373, 385, 7946, 361, 333, 329, 2309, 315, 1891, 2142, 2140};
    static final int[] BONES = {526, 532, 536};
    static int boughtToday; static long coinsToday;

    static final class Session {
        final Player bot, human;
        String sig = ""; long sigSince = System.currentTimeMillis(), opened = System.currentTimeMillis();
        int offered; boolean done; final Map<Integer, Integer> expect = new HashMap<>(), before = new HashMap<>();
        long guide; int tries; long ask = -1, lastAsk = -1; // haggling: guide total, how often they pushed, a price named before the bot offered
        boolean refine; int refineProduct, refineQty; // the player wants the artisan to make something from these, for a cut
        int give; // the bot is handing this item over for nothing (a quest partner's half, key or candlestick)
        Session(Player bot, Player human) { this.bot = bot; this.human = human; }
    }

    static final Map<Player, Session> SESSIONS = new HashMap<>();
    static final Map<String, Long> BUSY_TOLD = new HashMap<>();
    /** bot:player -> {item, expiry}: an item the bot has put in its pack to hand over when the player trades it (Companions). */
    static final Map<String, long[]> GIVES = new java.util.concurrent.ConcurrentHashMap<>();

    /** The bot takes the item into its pack and waits for the player to trade; five minutes, then it's dropped again. */
    static boolean give(Player bot, Player human, int itemId) {
        if (bot == null || human == null) return false;
        String k = key(bot) + ":" + key(human);
        long[] old = GIVES.remove(k);
        if (old != null && bot.getInventory().getAmount((int) old[0]) > 0) bot.getInventory().remove(new Item((int) old[0], 1));
        if (bot.getInventory().getAmount(itemId) == 0 && !bot.getInventory().add(new Item(itemId, 1))) return false;
        GIVES.put(k, new long[]{itemId, System.currentTimeMillis() + 5 * 60_000L});
        return true;
    }

    /** A bot logging out: any item it was holding out for a player goes back to nothing (it came from nothing). */
    static void dropGives(Player bot) {
        if (bot == null) return;
        String pre = key(bot) + ":";
        for (Map.Entry<String, long[]> e : GIVES.entrySet()) {
            if (!e.getKey().startsWith(pre)) continue;
            GIVES.remove(e.getKey());
            int id = (int) e.getValue()[0];
            if (bot.getInventory().getAmount(id) > 0) bot.getInventory().remove(new Item(id, 1));
            if (bot.getBank().getAmount(id) > 0) bot.getBank().remove(new Item(id, 1));
        }
    }

    static void sweepGives(long now) {
        for (Map.Entry<String, long[]> e : GIVES.entrySet()) {
            if (e.getValue()[1] > now) continue;
            GIVES.remove(e.getKey());
            Player bot = Ledger.Wallet.findOnline(e.getKey().substring(0, e.getKey().indexOf(':')));
            if (bot != null && bot.getInventory().getAmount((int) e.getValue()[0]) > 0 && !SESSIONS.containsKey(bot)) { bot.getInventory().remove(new Item((int) e.getValue()[0], 1)); BotChat.say(bot, BotChat.pick("Didn't want it then. Fine.", "Changed your mind? Suit yourself.")); }
        }
    }

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots() || !ON) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            @Override public boolean pulse() { try { tick(); } catch (Throwable t) { System.out.println("[PlayerTrades] tick: " + t); } return false; }
        });
        BotChat.LISTENERS.add(PlayerTrades::heard); // a player naming their price in the window
        System.out.println("[PlayerTrades] idle bots buy what they use from players, at guide");
    }

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    static long nextSweep;

    /** Removes every non-DoublingMoney bot from the server's pulse map so a completed trade can never hit the bad cast. */
    static void sweepPulseMap() {
        try {
            java.util.Iterator<Map.Entry<String, core.game.bots.GeneralBotCreator.BotScriptPulse>> it = core.game.bots.AIRepository.getPulseRepository().entrySet().iterator();
            int n = 0;
            while (it.hasNext()) {
                Map.Entry<String, core.game.bots.GeneralBotCreator.BotScriptPulse> e = it.next();
                Object script = e.getValue() == null ? null : e.getValue().getBotScript();
                if (!(script instanceof content.global.bots.DoublingMoney)) { it.remove(); n++; }
            }
            if (n > 0 && Boolean.getBoolean("afk.debug")) System.out.println("[PlayerTrades] took " + n + " bots out of the pulse map");
        } catch (Throwable t) { System.out.println("[PlayerTrades] pulse map sweep: " + t); }
    }

    static void tick() {
        long now = System.currentTimeMillis();
        if (now > nextSweep) { nextSweep = now + 30_000; sweepPulseMap(); sweepGives(now); }
        // 1. Players asking a bot to trade.
        for (Player h : Repository.getPlayers()) {
            if (h == null || h.isArtificial() || !h.isActive()) continue;
            Player bot = h.getRequestManager().getTarget();
            if (bot == null || !bot.isArtificial() || SESSIONS.containsKey(bot)) continue;
            String k = key(bot);
            SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(k);
            boolean artisan = Artisans.TRADE_OF.containsKey(k);
            if (br == null && !artisan) continue;                     // traders and callout sellers have their own trade code
            boolean selling = false; for (Deals.Offer o : Deals.OFFERS) if (o.seller == bot) selling = true;
            if (selling) { // a callout or errand seller: accept the request so the window opens; Deals.humanTrade runs it from there (review 10-22: nobody could ever trade a seller)
                if (TradeModule.getExtension(bot) == null) { InteractionListeners.run(-1, IntType.PLAYER, "trade with", bot, h); if (br != null) br.nextThink = now + 5_000; try { bot.getWalkingQueue().reset(); bot.face(h); } catch (Throwable ignored) { } }
                continue;
            }
            long[] giving = GIVES.remove(k + ":" + key(h)); // a quest partner handing something over: anywhere, not just at a bank
            if (giving == null && !artisan && !idle(br)) {
                String tk = k + ":" + key(h);
                if (now - BUSY_TOLD.getOrDefault(tk, 0L) > 60_000) {
                    BUSY_TOLD.put(tk, now);
                    BotChat.say(bot, BotChat.pick("Bit busy, " + h.getUsername() + ". Catch me at the bank.", "Can't stop now. Find me at a bank later.", "Mid-trip, sorry. Bank later?", "Not now, mate. Bank later?", "Hands full. Catch me at a bank.", "Working! Find me at the bank."));
                }
                continue;
            }
            InteractionListeners.run(-1, IntType.PLAYER, "trade with", bot, h);
            Session s = new Session(bot, h);
            if (giving != null) s.give = (int) giving[0];
            SESSIONS.put(bot, s);
            if (br != null) br.nextThink = now + 5_000;
            try { bot.getWalkingQueue().reset(); bot.face(h); } catch (Throwable ignored) { }
        }
        // 2. Open windows.
        for (Session s : SESSIONS.values().toArray(new Session[0])) {
            try { session(s, now); } catch (Throwable t) { System.out.println("[PlayerTrades] " + t); end(s, false); }
        }
    }

    /** Between trips (or loitering) at a bank or the Exchange, not on any errand. */
    static boolean idle(SmartAdventurers.Brain br) {
        if (br.runner != null || br.shopAt != null || br.delivering || br.graveAt != null || br.mill != 0) return false;
        Location at = br.bot.getLocation();
        if (at.getDistance(SmartAdventurers.GRAND_EXCHANGE) <= 25) return true;
        for (Location b : AfkTasks.BANKS) if (b.getDistance(at) <= 15) return true;
        return false;
    }

    static void session(Session s, long now) {
        Player bot = s.bot, h = s.human;
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        if (br != null) br.nextThink = now + 5_000; // stand still while the window is open
        TradeModule mine = TradeModule.getExtension(bot);
        if (mine == null) { // window closed: did it go through?
            if (now - s.opened < 3_000) return;               // still opening
            end(s, received(s));
            return;
        }
        if (now - s.opened > 180_000 || !h.isActive()) { if (now - s.opened > 180_000) try { int it = s.give > 0 ? s.give : s.expect.isEmpty() ? -1 : s.expect.keySet().iterator().next(); Failures.Econ.tradeTimeout(bot, it, it > 0 && s.give <= 0 ? s.expect.get(it) : 1, s.offered); } catch (Throwable ignored) { } mine.decline(); return; } // batch 19c §4.4 trade-timeout (nothing about the human)
        TradeModule theirs = TradeModule.getExtension(h);
        if (theirs == null) return;
        if (s.give > 0) { // handing an item over for nothing: put it up, accept, accept again on the second screen
            if (s.offered == 0) {
                int slot = bot.getInventory().getSlot(new Item(s.give));
                if (slot < 0) { BotChat.say(bot, "Lost it. Sorry."); mine.decline(); return; }
                s.before.put(s.give, held(bot, s.give)); // before the offer: offer() takes it out of the pack at once (review 10-22)
                mine.getContainer().offer(slot, 1);
                s.offered = -2; s.sigSince = now;
                BotChat.say(bot, BotChat.pick("There. Accept it.", "Take it. Accept, go on.", "That's yours. Accept."));
                return;
            }
            if (now - s.sigSince > 1_500 && !mine.isAccepted()) mine.setAccepted(true, true);
            return;
        }
        // What the player is offering, as unnoted ids.
        Map<Integer, Integer> offer = new HashMap<>();
        StringBuilder sig = new StringBuilder();
        for (Item it : theirs.getContainer().toArray()) {
            if (it == null) continue;
            int id = it.getId();
            ItemDefinition d = ItemDefinition.forId(id);
            if (d != null && !d.isUnnoted() && d.getNoteId() > 0) id = d.getNoteId();
            offer.merge(id, it.getAmount(), Integer::sum);
            sig.append(it.getId()).append('x').append(it.getAmount()).append(',');
        }
        if (!sig.toString().equals(s.sig)) {
            if (s.offered > 0) { // changed after the bot put its coins up: start over
                BotChat.say(bot, "You changed it. Start again, mate.");
                mine.decline(); return;
            }
            s.sig = sig.toString(); s.sigSince = now; return;
        }
        if (offer.isEmpty() || now - s.sigSince < 2_000) return;      // let them finish putting things in
        if (s.offered == 0 && s.refine && Artisans.TRADE_OF.containsKey(key(bot))) { // a refining job: no coins change hands; the product comes later
            Artisans.Trade trade = Artisans.tradeOf(bot);
            if (offer.size() != 1) { BotChat.say(bot, "One kind of thing at a time, please."); s.sigSince = now + 30_000; return; }
            Map.Entry<Integer, Integer> e = offer.entrySet().iterator().next();
            Artisans.Recipe r = Artisans.refineRecipe(trade, e.getKey());
            if (r == null) { BotChat.say(bot, BotChat.pick("I can't make anything from " + MarketBrain.name(e.getKey()).toLowerCase(Locale.ROOT) + ".", "Not my trade, " + MarketBrain.name(e.getKey()).toLowerCase(Locale.ROOT) + ".")); s.sigSince = now + 30_000; return; }
            int per = 1; for (int k = 0; k < r.inIds.length; k++) if (r.inIds[k] == e.getKey()) per = Math.max(1, r.inAmts[k]);
            int batches = e.getValue() / per;
            if (batches <= 0) { BotChat.say(bot, "That's not enough for one batch."); s.sigSince = now + 30_000; return; }
            int pct = BotChat.Trust.of(h, bot) >= 3 ? 90 : 85;
            s.refineProduct = r.product; s.refineQty = Math.max(1, batches * r.amount * pct / 100);
            s.expect.clear(); s.expect.putAll(offer); for (int id : offer.keySet()) s.before.put(id, held(bot, id));
            s.offered = -1; // no coins: the marker that we've agreed
            BotChat.say(bot, String.format("%d %s for %d %s, %d%% back, left in your bank when they're done. Accept.", s.refineQty, MarketBrain.name(r.product).toLowerCase(Locale.ROOT), e.getValue(), MarketBrain.name(e.getKey()).toLowerCase(Locale.ROOT), pct));
            return;
        }
        if (s.offered == 0) {
            long total = 0;
            for (Map.Entry<Integer, Integer> e : offer.entrySet()) {
                int id = e.getKey(), n = e.getValue();
                if (id == 995) { BotChat.say(bot, "Keep your coins, I'm buying not selling."); s.sigSince = now + 30_000; return; }
                int want = want(bot, br, id);
                if (want <= 0) { BotChat.say(bot, BotChat.pick("No use for " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + ", mate.", "What would I do with " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + "?")); s.sigSince = now + 30_000; return; }
                if (n > want) { BotChat.say(bot, "I only need " + want + " " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + "."); s.sigSince = now + 30_000; return; }
                total += (long) Math.max(1, MarketBrain.botRef(id)) * n; // audit E-4: a price alts can't push up
            }
            if (total > Ledger.Wallet.coins(bot) - 300 || total > Integer.MAX_VALUE) { BotChat.say(bot, "Can't afford that lot. " + String.format("%,d", total) + " gp at guide."); s.sigSince = now + 30_000; return; }
            // Coins out of the bank into the window.
            int amt = (int) total;
            int inBank = bot.getBank().getAmount(995), take = Math.min(amt, inBank);
            if (take > 0) bot.getBank().remove(new Item(995, take));
            if (!bot.getInventory().add(new Item(995, take))) { bot.getBank().add(new Item(995, take)); return; }
            int slot = bot.getInventory().getSlot(new Item(995));
            if (slot < 0 || bot.getInventory().getAmount(995) < amt) { bot.getInventory().remove(new Item(995, take)); bot.getBank().add(new Item(995, take)); return; }
            mine.getContainer().offer(slot, amt);
            s.offered = amt; s.guide = total; s.expect.clear(); s.expect.putAll(offer);
            for (int id : offer.keySet()) s.before.put(id, held(bot, id));
            BotChat.say(bot, BotChat.pick(String.format("%,d gp, guide price. Deal?", amt), String.format("I'll give you %,d. That's guide.", amt), String.format("%,d gp, fair and square. Accept?", amt), String.format("Guide's %,d gp. There you go.", amt)));
            if (s.ask > 0) { long a = s.ask; s.ask = -1; ask(s, a, now); } // they named a price before the window was ready
            return;
        }
        if (!mine.isAccepted()) mine.setAccepted(true, true);
    }

    // ------------------------------------------------------------------ haggling (2026-10-21)

    /** A player in a buy window with a bot names their price: "120 each", "make it 5k", "10% more", "can you do 2k". */
    static boolean heard(Player speaker, String text) {
        if (speaker == null || speaker.isArtificial()) return false;
        Session s = null;
        for (Session x : SESSIONS.values()) if (x.human == speaker) { s = x; break; }
        if (s == null) return false;
        int n = 0; for (int q : s.expect.values()) n += q;
        Haggle.Bid b = Haggle.read(text, n);
        long now = System.currentTimeMillis();
        String lt = text.toLowerCase(Locale.ROOT);
        if (Artisans.TRADE_OF.containsKey(key(s.bot)) && s.offered == 0 && lt.matches(".*\\b(refine|cook (?:these|them|my|those)|smith (?:these|them|my)|smelt (?:these|them|my)|fletch (?:these|them|my)|clean (?:these|them|my)|make (?:these|them|those|something) (?:into|out of|from)|turn (?:these|them) into|process (?:these|them)|do (?:these|them) for me|can you (?:cook|smith|smelt|make|fletch|craft|clean) (?:these|them|my)|for a cut|your cut)\\b.*")) {
            s.refine = true; s.sigSince = now;
            BotChat.say(s.bot, BotChat.pick("Put them in the window. I keep " + (BotChat.Trust.of(speaker, s.bot) >= 3 ? "a tenth" : "15%") + " for the work.", "Go on then. You get " + (BotChat.Trust.of(speaker, s.bot) >= 3 ? "90%" : "85%") + " back when they're done.", "Hand them over. I'll leave the finished lot in your bank."));
            return true;
        }
        if (b.agree && s.offered > 0) { BotChat.say(s.bot, BotChat.pick("Accept it then!", "Hit accept, " + speaker.getUsername() + ".", "Lovely. Accept when ready.")); return true; }
        if (!b.hasPrice()) {
            if (b.cheaper || b.text.matches(".*\\b(more|higher|better|that'?s low|too low|lowball|low ball|cheap ?skate|stingy|tight)\\b.*")) {
                BotChat.say(s.bot, s.offered > 0 ? BotChat.pick("That's guide, " + speaker.getUsername() + ". I don't pay over guide.", "Guide's guide. That's the price.", "Guide's what it's worth and guide's what I pay.") : "Put it in the window first."); // X-10: no more "name a price": the bot never pays over botRef
                return true;
            }
            return false;
        }
        if (s.offered == 0) { // window still filling: remember the price for when the bot puts its coins up
            long a = b.total(-1, Math.max(1, n), -1, false);
            if (a > 0 && n > 0 && b.amount >= 0) { s.ask = a; BotChat.say(s.bot, "Let me see what you've got first."); return true; }
            return false;
        }
        long want = b.total(s.offered, Math.max(1, n), s.lastAsk, true);
        if (b.middle) want = (s.offered + (s.lastAsk > 0 ? s.lastAsk : s.guide)) / 2; // X-10: "meet in the middle" with no earlier ask is guide, not 110%
        if (want < 0) return false;
        s.lastAsk = want;
        ask(s, want, now);
        return true;
    }

    /**
     * The player wants {\code want} gp in total. X-10 (2026-10-29): the bot never pays over guide (100% of botRef);
     * before, it paid up to 105% and met a 105-115% ask at 105%, which with Deals selling at 91-95% was a +9-14% loop on the
     * artisan float. The opening offer is already guide, so a higher ask only gets a refusal: polite up to 15% over, blunt past it.
     */
    static void ask(Session s, long want, long now) {
        Player bot = s.bot; String hn = s.human.getUsername();
        if (want <= s.offered) { BotChat.say(bot, BotChat.pick("That's less than I'm offering! Take the " + Haggle.gp(s.offered) + ".", "I'm already giving you " + Haggle.gp(s.offered) + ", " + hn + ".")); return; }
        long cap = s.guide; // 100% of botRef, the same figure the opening offer shows and the trade pays
        long wallet = Ledger.Wallet.coins(bot) - 300;
        if (s.tries >= 2) { BotChat.say(bot, BotChat.pick(Haggle.gp(s.offered) + " gp is my last word.", "No more, " + hn + ". " + Haggle.gp(s.offered) + " or nothing.", "Final offer: " + Haggle.gp(s.offered) + ".")); return; }
        s.tries++;
        long to;
        if (want <= cap) to = want; // only when the opening offer was short of guide (it never is: offered == guide), kept so the window can be topped up to guide
        else if (want <= s.guide * 115 / 100) { BotChat.say(bot, BotChat.pick("Guide's " + Haggle.gp(s.guide) + ", " + hn + ", and guide's what I pay.", "Can't go over guide, mate. " + Haggle.gp(s.offered) + " is the price.", "That's over guide. " + Haggle.gp(s.offered) + " is what it's worth.")); return; }
        else { BotChat.say(bot, BotChat.pick(Haggle.gp(want) + "? The Exchange is cheaper than that, " + hn + ".", "Guide's " + Haggle.gp(s.guide) + ". I'm not paying " + Haggle.gp(want) + ".", "Way over guide, mate. " + Haggle.gp(s.offered) + ".")); return; }
        if (to > wallet) { BotChat.say(bot, "Can't stretch to that, " + hn + ". " + Haggle.gp(s.offered) + " is all I can do."); return; }
        if (to <= s.offered || !raise(s, (int) (to - s.offered))) { BotChat.say(bot, Haggle.gp(s.offered) + " is my best, " + hn + "."); return; }
        BotChat.say(bot, to == want ? BotChat.pick("Go on then, " + Haggle.gp(to) + ".", "Fine. " + Haggle.gp(to) + " gp, accept it.", "You've got yourself a deal: " + Haggle.gp(to) + ".") : BotChat.pick("Best I can do is " + Haggle.gp(to) + ".", "Meet you at " + Haggle.gp(to) + ". Accept?", Haggle.gp(to) + ", and that's me being generous."));
    }

    /** Puts {\code extra} more coins in the bot's side of the window. */
    static boolean raise(Session s, int extra) {
        Player bot = s.bot;
        TradeModule mine = TradeModule.getExtension(bot);
        if (mine == null || extra <= 0 || mine.getStage() != 0) return false;
        int inBank = bot.getBank().getAmount(995);
        if (inBank < extra) return false;
        if (!bot.getBank().remove(new Item(995, extra))) return false;
        if (!bot.getInventory().add(new Item(995, extra))) { bot.getBank().add(new Item(995, extra)); return false; }
        int slot = bot.getInventory().getSlot(new Item(995));
        if (slot < 0) return false;
        mine.getContainer().offer(slot, extra);
        s.offered += extra;
        return true;
    }

    /** What this bot is after, for "what are you buying" (names, nearest first in importance). */
    static java.util.List<String> wantList(Player bot) {
        java.util.List<String> out = new java.util.ArrayList<>();
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        Artisans.Trade trade = Artisans.tradeOf(bot);
        if (trade != null) {
            for (Artisans.Recipe r : Artisans.RECIPES) {
                if (!trade.name().equalsIgnoreCase(r.trade) || r.inIds == null) continue;
                for (int in : r.inIds) { String nm = MarketBrain.name(in).toLowerCase(Locale.ROOT); if (!out.contains(nm) && want(bot, br, in) > 0) out.add(nm); }
            }
            return out;
        }
        if (br == null) return out;
        if (br.goal > 0 && bot.getEquipment().getAmount(br.goal) == 0) out.add("a " + MarketBrain.name(br.goal).toLowerCase(Locale.ROOT));
        try { for (int[] pc : br.armourNeeds()) { String nm = MarketBrain.name(pc[0]).toLowerCase(Locale.ROOT); if (!out.contains(nm)) out.add(nm); } } catch (Throwable ignored) { }
        for (int[] su : br.supplyNeeds()) out.add(MarketBrain.name(su[0]).toLowerCase(Locale.ROOT));
        for (int f : FOODS) if (want(bot, br, f) > 0) { out.add("food (lobsters, swordfish and the like)"); break; }
        if (bot.getSkills().getStaticLevel(Skills.PRAYER) < 43) out.add("bones");
        return out;
    }

    /** How many more of this the bot would actually use; 0 if none. */
    static int want(Player bot, SmartAdventurers.Brain br, int id) {
        if (MarketBrain.value(id) <= 0) return 0;
        Artisans.Trade trade = Artisans.tradeOf(bot);
        if (trade != null) {
            for (Artisans.Recipe r : Artisans.RECIPES) {
                if (!trade.name().equalsIgnoreCase(r.trade) || r.inIds == null) continue;
                for (int in : r.inIds) if (in == id) return Math.max(0, 500 - bot.getBank().getAmount(id));
            }
            return 0;
        }
        if (br == null) return 0;
        if (id == br.goal && bot.getEquipment().getAmount(id) == 0) return 1;
        for (int[] su : br.supplyNeeds()) if (su[0] == id) return su[1]; // arrows for a ranger, runes for a mage
        try { for (int[] pc : br.armourNeeds()) if (pc[0] == id) return 1; } catch (Throwable ignored) { }
        for (int f : FOODS) if (f == id) return Math.max(0, 56 - bot.getBank().getAmount(id) - bot.getInventory().getAmount(id));
        if (bot.getSkills().getStaticLevel(Skills.PRAYER) < 43) for (int b : BONES) if (b == id) return Math.max(0, 200 - bot.getBank().getAmount(id));
        return 0;
    }

    static boolean received(Session s) {
        if (s.give > 0) return held(s.bot, s.give) < s.before.getOrDefault(s.give, 1); // it left the bot's pack: the player has it
        if (s.offered == 0 || s.expect.isEmpty()) return false;
        for (Map.Entry<Integer, Integer> e : s.expect.entrySet()) {
            if (held(s.bot, e.getKey()) - s.before.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /** In the pack, counting the noted form. */
    static int held(Player bot, int id) {
        ItemDefinition d = ItemDefinition.forId(id);
        return bot.getInventory().getAmount(id) + (d != null && d.getNoteId() > 0 ? bot.getInventory().getAmount(d.getNoteId()) : 0);
    }

    static void end(Session s, boolean ok) {
        SESSIONS.remove(s.bot);
        Player bot = s.bot;
        try { Failures.econNote(bot, Failures.PLAYER_TRADE); if (ok && s.give <= 0 && !s.refine && s.expect.size() == 1) { Map.Entry<Integer, Integer> e1 = s.expect.entrySet().iterator().next(); if (e1.getValue() > 0) Failures.Econ.dealPrice(bot, e1.getKey(), s.offered / e1.getValue(), "player", "trade_end"); } } catch (Throwable ignored) { } // batch 19c §4.1 / §4.6: the bot's own accepted unit only
        // Coins left in the pack (a declined trade) go back to the bank, where the wallet keeps them.
        int c = bot.getInventory().getAmount(995);
        if (c > 0 && bot.getInventory().remove(new Item(995, c))) bot.getBank().add(new Item(995, c));
        if (s.give > 0) { // a quest partner's hand-over
            if (!ok) { if (bot.getInventory().getAmount(s.give) > 0) bot.getInventory().remove(new Item(s.give, 1)); BotChat.say(bot, BotChat.pick("Suit yourself.", "No? Fine. Forget we spoke.")); return; }
            BotChat.Trust.add(s.human, bot, 1);
            BotChat.say(bot, BotChat.pick("Not a word to anyone.", "Good luck with it. We never met.", "Go on then, finish it."));
            System.out.println("[PlayerTrades] " + bot.getUsername() + " handed " + MarketBrain.name(s.give) + " to " + s.human.getUsername() + " (quest partner)");
            return;
        }
        if (!ok) return;
        if (s.refine && s.refineProduct > 0) { // the raw goods go to the artisan's bank; the player is owed the product
            for (Map.Entry<Integer, Integer> e : s.expect.entrySet()) {
                int id = e.getKey();
                ItemDefinition d = ItemDefinition.forId(id);
                if (d != null && d.getNoteId() > 0 && bot.getInventory().getAmount(d.getNoteId()) > 0) { int nn = bot.getInventory().getAmount(d.getNoteId()); bot.getInventory().remove(new Item(d.getNoteId(), nn)); bot.getBank().add(new Item(id, nn)); }
                int inv = bot.getInventory().getAmount(id); if (inv > 0 && bot.getInventory().remove(new Item(id, inv))) bot.getBank().add(new Item(id, inv));
                Deals.Owed w = new Deals.Owed(bot, s.human, s.refineProduct, s.refineQty); w.rawId = id; w.rawQty = e.getValue(); Deals.OWED.add(w);
            }
            Artisans.OWED_PRODUCTS.merge(s.refineProduct, s.refineQty, Integer::sum);
            BotChat.Trust.add(s.human, bot, 1);
            BotChat.say(bot, BotChat.pick("Lovely. I'll get on it; check your bank in a bit.", "Right, leave it with me.", "They'll be in your bank once they're done."));
            System.out.println("[PlayerTrades] " + bot.getUsername() + " took a refining job from " + s.human.getUsername() + ": " + s.refineQty + " x " + MarketBrain.name(s.refineProduct));
            return;
        }
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        for (Map.Entry<Integer, Integer> e : s.expect.entrySet()) {
            int id = e.getKey(), n = e.getValue();
            ItemDefinition d = ItemDefinition.forId(id);
            if (d != null && d.getNoteId() > 0 && bot.getInventory().getAmount(d.getNoteId()) > 0) {
                int nn = bot.getInventory().getAmount(d.getNoteId());
                bot.getInventory().remove(new Item(d.getNoteId(), nn)); bot.getBank().add(new Item(id, nn));
            }
            boolean wearIt = br != null && (id == br.goal || (d != null && d.getEquipId() > 0 && bot.getEquipment().getAmount(id) == 0 && n == 1));
            if (wearIt && SmartAdventurers.wear(bot, id)) {
                if (id == br.goal) { br.goal = SmartAdventurers.pickGoal(bot); SmartAdventurers.GOAL.put(key(bot), br.goal); }
                continue;
            }
            int inv = bot.getInventory().getAmount(id);
            if (inv > 0 && bot.getInventory().remove(new Item(id, inv))) bot.getBank().add(new Item(id, inv));
        }
        try { bot.getEquipment().refresh(); } catch (Throwable ignored) { }
        boughtToday++; coinsToday += s.offered;
        BotChat.Trust.add(s.human, bot, 1);
        Ledger.PAID += s.offered; // coins went to a player: out of the bots' economy
        BotChat.say(bot, BotChat.pick("Cheers mate, needed that!", "Nice one, " + s.human.getUsername() + ". Exactly what I was after.", "Pleasure. Saved me a trip.", "Lovely, just what I was after.", "Sorted. Thanks!", "That'll do nicely. Cheers.", "Pleasure doing business!", "Saved me a walk. Ta!"));
        StringBuilder what = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : s.expect.entrySet()) what.append(e.getValue()).append(" x ").append(MarketBrain.name(e.getKey())).append("; ");
        System.out.println("[PlayerTrades] " + bot.getUsername() + " bought " + what + "from player " + s.human.getUsername() + " for " + s.offered + " gp");
    }
}
