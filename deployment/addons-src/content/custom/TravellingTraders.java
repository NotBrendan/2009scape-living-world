package content.custom;

import core.api.StartupListener;
import core.cache.def.impl.ItemDefinition;
import core.game.bots.AIPlayer;
import core.game.bots.AIRepository;
import core.game.bots.GeneralBotCreator;
import core.game.bots.Script;
import core.game.bots.SkillingBotAssembler;
import core.game.ge.GrandExchange;
import core.game.ge.PriceIndex;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.request.trade.TradeModule;
import core.game.node.item.Item;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Travelling traders: four bots (a miner, a fishmonger and two treasure hunters) that
 * roam their home area gathering a haul, then visit online players in turn and offer it
 * for sale. A player buys the whole haul by trading the bot and putting up the asking
 * price in coins. If nobody buys, the haul goes into the bots' Grand Exchange stock and
 * the trader starts a new gathering trip. Loaded from the overlay by the ClassScanner.
 */
public final class TravellingTraders implements StartupListener {
    static final List<Trader> ALL = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final Map<String, Trader> CLAIMED = new java.util.concurrent.ConcurrentHashMap<>(); // player -> the trader working on them
    static final Map<String, Long> PITCHED = new java.util.concurrent.ConcurrentHashMap<>();   // player -> no other pitch before this

    /** Server going down: a haul a trader is carrying goes to the Exchange through the ledger instead of vanishing. */
    static void stashForShutdown() {
        for (Trader t : ALL) {
            try {
                for (Map.Entry<Integer, Integer> h : t.haul.entrySet()) Ledger.supply(t.bot, h.getKey(), h.getValue());
                t.haul.clear();
            } catch (Throwable ignored) { }
        }
    }
    static final Random RNG = new Random();
    static final Timer TIMER = new Timer("TravellingTraders", true);
    static final int COINS = 995;
    static final boolean FAST = Boolean.getBoolean("traders.fast");

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        spawn(Kind.MINER, Location.create(3285, 3367, 0), 45_000);          // south-east Varrock mine
        spawn(Kind.FOOD, Location.create(2836, 3434, 0), 70_000);           // Catherby shore
        spawn(Kind.TREASURE, Location.create(3201, 3233, 0), 95_000);       // Lumbridge outskirts
        spawn(Kind.TREASURE, Location.create(2965, 3393, 0), 120_000);      // Falador outskirts
        System.out.println("[Traders] scheduling 4 travelling traders");
    }

    static void spawn(Kind kind, Location home, long delayMs) {
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() { GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) { @Override public boolean pulse() { spawnNow(kind, home); return true; } }); } // made on the game thread (audit #27)
        }, FAST ? 5_000 : delayMs);
    }

    static void spawnNow(Kind kind, Location home) {
        {
            {
                try {
                    boolean[] back = new boolean[1];
                    AIPlayer bot = ServiceBots.make("trader:" + kind.name() + "@" + home.getX() + "," + home.getY(), home, SkillingBotAssembler.Wealth.RICH, back);
                    if (!back[0]) Ledger.Wallet.give(bot, 5_000); // a new trader's float, for tools
                    SmartAdventurers.finishTutorial(bot);
                    Trader tr = new Trader(kind, home); ALL.add(tr);
                    new GeneralBotCreator(tr, bot);
                    final AIPlayer tb = bot; // heartbeat: a fishing or mining pulse left behind would stop the script ticking (seen in trial)
                    GameWorld.getPulser().submit(new core.game.system.task.Pulse(5) { @Override public boolean pulse() { if (!tb.isActive()) return true; if (tb.getPulseManager().hasPulseRunning() && !AfkTasks.RUNNING.containsKey(tb.getUsername().toLowerCase(java.util.Locale.ROOT))) { try { tr.tick(); } catch (Throwable ignored) { } } return false; } });
                    // TradeModule casts any bot found in PulseRepository to DoublingMoney when a trade completes;
                    // a Trader there makes every finished trade throw and the buyer loses their coins.
                    AIRepository.getPulseRepository().remove(bot.getUsername().toLowerCase());
                    BotChat.activity(bot, () -> "travelling about as a " + kind.title.toLowerCase() + ", selling what I've got");
                    System.out.println("[Traders] " + kind.title + " " + bot.getUsername() + " set out from " + home);
                } catch (Throwable t) {
                    System.out.println("[Traders] spawn failed: " + t);
                    try { Failures.Life.loginFailed("trader", null, t); } catch (Throwable ignored) { } // batch 19c §8.1
                }
            }
        }
    }

    // ---------------------------------------------------------------- kinds of trader

    enum Kind {
        MINER("travelling miner", 5_000, new int[][]{
            // id, weight, min, max
            {453, 30, 10, 40}, {440, 25, 10, 40}, {447, 14, 4, 15}, {449, 6, 2, 6},
            {436, 8, 10, 30}, {438, 8, 10, 30}, {442, 6, 5, 15}, {444, 6, 3, 10},
            {2353, 10, 3, 10}, {2359, 6, 2, 6}, {2361, 3, 1, 3},
            {1623, 6, 1, 4}, {1621, 5, 1, 3}, {1619, 3, 1, 2}},
            new int[][]{ // rare finds: id, weight, min, max
            {451, 30, 1, 3}, {2363, 20, 1, 2}, {1617, 25, 1, 2}, {1631, 15, 1, 1}, {1615, 5, 1, 1}}, 15,
            new String[]{
                "Ho there, %s! Fresh out of the deep mines, still dusty from the tunnels.",
                "%s! Stop right there, I've hauled ore halfway across Gielinor for you.",
                "Ah, %s, just the adventurer I hoped to bump into. My sack's heavier than my pick!",
                "%s! Fresh ore, mined this morning. My arms still ache.",
                "Oi, %s! Fancy some ore? Saves you the mine and the dwarves.",
                "%s, a word? I've a sack of ore with your name on it."},
            new String[]{
                "A smith like you could do a lot with this lot.",
                "Mined it myself. Mostly. The dwarves won't miss it.",
                "Saves you a week of swinging a pickaxe!",
                "Every lump hand-picked. Well, pickaxed.",
                "Smiths love me. Their customers love them. Circle of life.",
                "Coal's the secret. Always more coal."}),
        FOOD("travelling fishmonger", 4_000, new int[][]{
            {379, 30, 5, 20}, {373, 20, 4, 15}, {385, 10, 2, 8}, {7946, 14, 4, 15},
            {361, 12, 5, 20}, {365, 8, 3, 10}, {329, 12, 8, 25}, {333, 12, 8, 25}, {351, 6, 5, 15}, {1891, 5, 2, 6}},
            new int[][]{
            {391, 50, 3, 8}, {397, 50, 3, 8}}, 12,
            new String[]{
                "%s! Hungry? You look hungry. Everyone who fights looks hungry.",
                "Fresh catch, %s! Well, fresh-ish. Caught it this morning. Somewhere.",
                "Ahoy, %s! Straight off the Catherby boats and cooked to perfection.",
                "%s! Hungry work, adventuring. I've got just the thing.",
                "Fresh catch, %s! Caught it, cooked it, carried it here.",
                "%s, food for your next fight? Come and look."},
            new String[]{
                "Never go into a fight without a full stomach, I always say.",
                "Cooked it myself. Only burnt a few.",
                "Sharks for the brave, lobsters for the wise!",
                "Never fight on an empty stomach.",
                "I only burnt the ones I ate.",
                "Catherby's finest, mostly."}),
        TREASURE("treasure hunter", 8_000, new int[][]{
            {561, 14, 5, 30}, {563, 12, 5, 20}, {560, 10, 3, 15}, {562, 10, 5, 25}, {564, 6, 3, 12},
            {565, 3, 2, 8}, {554, 8, 30, 120}, {536, 8, 1, 4}, {532, 10, 3, 10},
            {207, 4, 1, 2}, {209, 6, 1, 3}, {211, 6, 1, 3}, {213, 4, 1, 3},
            {1623, 8, 1, 3}, {1621, 7, 1, 2}, {1619, 5, 1, 2}, {2359, 5, 2, 6}, {1329, 4, 1, 1}},
            new int[][]{
            {1731, 18, 1, 1}, {1725, 18, 1, 1}, {1617, 14, 1, 2}, {1615, 8, 1, 1}, {1704, 7, 1, 1},
            {1333, 9, 1, 1}, {1215, 7, 1, 1}, {1123, 6, 1, 1}, {1163, 5, 1, 1}, {1201, 3, 1, 1}, {1079, 3, 1, 1},
            {1149, 1, 1, 1}, {4587, 1, 1, 1}}, 25,
            new String[]{
                "Psst, %s! Over here. I've found things. Interesting things.",
                "%s! You won't believe what I dug up near a goblin camp.",
                "Well met, %s. Treasure for sale, no questions asked, few answers given.",
                "%s! Runes, herbs and the odd shiny thing. Interested?",
                "Over here, %s. The druids didn't need these any more.",
                "%s, I've been fighting wizards all morning. Buy something and make it worth it."},
            new String[]{
                "Don't ask where the rune helm came from. A gentleman never tells.",
                "Every coin I make goes into a bigger spade.",
                "Some of it is still slightly cursed, but only slightly.",
                "The dark wizards won't be needing these.",
                "Herbs from druids, runes from wizards. Honest work.",
                "Everything's been checked for curses. By me. Mostly."});

        final String title;
        final int haulValue;
        final int[][] pool, rares;
        final int rareChance; // percent of hauls that also carry one rare find
        final String[] greetings, quips;

        Kind(String title, int haulValue, int[][] pool, int[][] rares, int rareChance, String[] greetings, String[] quips) {
            this.title = title;
            this.haulValue = haulValue;
            this.pool = pool;
            this.rares = rares;
            this.rareChance = rareChance;
            this.greetings = greetings;
            this.quips = quips;
        }
    }

    // ---------------------------------------------------------------- the bot script

    enum Phase { GATHER, SEEK, PITCH, TRADING, STASH }

    /** The watchdog teleported this trader out of somewhere it was stuck: drop the player it was walking to. */
    static void unstick(Player bot) {
        for (Trader t : ALL) {
            if (t.bot != bot || (t.phase != Phase.SEEK && t.phase != Phase.PITCH)) continue;
            if (t.target != null) CLAIMED.remove(t.target.getUsername().toLowerCase(), t);
            t.target = null;
            t.enter(Phase.SEEK);
        }
    }

    static final class Trader extends Script {
        final Kind kind;
        final Location home;
        Phase phase = Phase.GATHER;
        // Bot scripts don't run every game tick, so phases are timed by the wall clock.
        long phaseStart = System.currentTimeMillis();
        final Set<String> cues = new HashSet<>();
        int timer = 0;
        int gatherSecs;
        final Map<Integer, Integer> haul = new LinkedHashMap<>();
        final Set<String> visited = new HashSet<>();
        Player target;
        int price;
        int coinsBeforeTrade;
        int rare = -1;
        boolean offered;

        Trader(Kind kind, Location home) {
            this.kind = kind;
            this.home = home;
            // High stats so monsters leave the trader alone on its travels.
            for (int s : new int[]{0, 1, 2, 3, 4, 6}) skills.put(s, 90);
            newTrip();
        }

        void newTrip() {
            if (bs != null) { Bargain.close(bot.getUsername(), bs.player); bs = null; } // batch 24
            phase = Phase.GATHER;
            gatherSecs = FAST ? 40 : 1800 + RNG.nextInt(1800); // 30 to 60 minutes
            enter(Phase.GATHER);
            visited.clear();
            haul.clear();
        }

        void enter(Phase next) {
            if ((next == Phase.SEEK || next == Phase.STASH || next == Phase.GATHER) && target != null) CLAIMED.remove(target.getUsername().toLowerCase(), this);
            phase = next;
            phaseStart = System.currentTimeMillis();
            cues.clear();
            timer = 0;
            if (next == Phase.PITCH) { haggles = 0; lastBid = -1; countered = false; floorPrice = 0; BotChat.LISTENERS.remove(haggler); BotChat.LISTENERS.add(haggler); }
            else if (next != Phase.TRADING) BotChat.LISTENERS.remove(haggler);
        }

        Kind myKind() { return kind; } // for the chat listener (a field initialiser can't read kind directly)
        int haggles; long lastBid = -1; boolean countered;
        int floorPrice; // 92% of the pitch and of the haul's reference value, for the whole haggle (morning review 10-23; 86% in audit E-14; each accepted offer used to lower the next floor: ~62% in two)
        /** Hears the target name a price and bargains: near enough is a deal, a fair bit under gets a counter, cheek gets a quip. */
        final BotChat.Listener haggler = (speaker, text) -> {
            if (target == null || speaker != target || price <= 0) return false;
            String lt = text.toLowerCase();
            int lotQty = 0; for (int q : haul.values()) lotQty += q;
            Haggle.Bid bid = Haggle.read(text, lotQty); // the shared haggle reader (2026-10-21)
            boolean hasNumber = bid.hasPrice() || bid.qty > 0;
            if (bid.agree) { // "ok" / "deal" / "sure"
                say(pick(new String[]{"Lovely. Trade me and put up exactly " + gp(price) + " gp.", "Deal! " + gp(price) + " gp in the trade window, " + target.getUsername() + ".", "Shake on it. Trade me, " + gp(price) + " gp."}));
                tell(target, "[" + nickname() + "] Trade me and offer exactly " + gp(price) + " coins.");
                return true;
            }
            if (!hasNumber && lt.matches(".*\\b(no|nah|no thanks|not interested|go away|leave me alone|stop|no ty|nope)\\b.*")) { // a polite exit
                say(pick(new String[]{"No worries, " + target.getUsername() + ". Safe travels!", "Fair enough. I'll leave you be.", "Another time, then. Good luck out there."}));
                PITCHED.put(target.getUsername().toLowerCase(), System.currentTimeMillis() + 60 * 60_000L); // nobody bothers them for an hour
                CLAIMED.remove(target.getUsername().toLowerCase(), this);
                target = null; enter(Phase.SEEK); return true;
            }
            String tn = target.getUsername();
            if (!hasNumber && lt.matches(".*\\b(too expensive|too much|expensive|cheaper|discount|lower|deal\\?|best price|rip off|ripoff|scam)\\b.*")) {
                say(pick(new String[]{"Name your price, " + tn + ". I'm listening.", "Make me an offer. A real one.", "Cheaper than the Exchange already! But go on, try me.", "Say a number, " + tn + ". We'll see."}));
                return true;
            }
            if (!hasNumber && lt.matches(".*\\b(where did you get|where'd you get|stolen|is it legit|legit|fresh|real)\\b.*")) {
                say(myKind() == Kind.MINER ? pick(new String[]{"Mined it myself, south of Varrock. Ask the rocks.", "Honest pickaxe work, every lump."}) : myKind() == Kind.FOOD ? pick(new String[]{"Caught it this morning and cooked it on a proper range.", "Fresh as the Catherby tide."}) : pick(new String[]{"Druids and dark wizards don't need much where they're going.", "Won fair and square in a fight. Mostly fair."}));
                return true;
            }
            if (!hasNumber && lt.matches("^(hi|hello|hey|hiya|yo|sup|howdy|greetings|alright|ello)\\b.*")) { say(pick(new String[]{"Hello, " + tn + "! Have a look at the goods.", "Ah, " + tn + ", you're a sight for sore eyes. Buying?", "Hey " + tn + "! Something for everyone."})); return true; }
            if (!hasNumber && lt.matches(".*\\b(who are you|what are you|what do you sell|what is this)\\b.*")) { say("I'm a " + myKind().title + ", " + tn + ". " + pick(new String[]{"I gather it, I sell it, you profit.", "Travelling, selling, and avoiding goblins.", "Best prices outside the Exchange."})); return true; }
            if (!hasNumber && lt.matches(".*\\b(maybe|later|thinking|hmm|let me think|one sec|wait)\\b.*")) { say(pick(new String[]{"Take your time. Not too much time.", "I'll wait. Briefly.", "No rush. Well, a little rush."})); return true; }
            if (!hasNumber && lt.matches(".*\\b(thanks|thank you|ty|cheers)\\b.*")) { say(pick(new String[]{"Pleasure, " + tn + ".", "Any time.", "Spread the word!"})); return true; }
            if (!hasNumber && lt.matches(".*\\b(how much|price|cost|what have you got|whatcha got|what you got|what do you have|show me)\\b.*")) {
                say("I've got " + describe(4) + (haul.size() > 4 ? " and more" : "") + ". The lot for " + gp(price) + " gp.");
                tell(target, "[" + nickname() + "] For sale: " + describe(99) + ". All of it for " + gp(price) + " gp. Trade me and offer exactly that.");
                return true;
            }
            String name = target.getUsername();
            if (bid.qty > 0 && bid.qty < lotQty) { // "i'll take 10", "10 for 500": it's one lot
                say(pick(new String[]{"It's one lot, " + name + ". All " + lotQty + " for " + gp(price) + " gp.", "I don't split it. The lot, " + gp(price) + " gp.", "All or nothing, friend. " + gp(price) + " gp for everything."}));
                return true;
            }
            if (bid.each && haul.size() > 1) { say(pick(new String[]{"It's a mixed lot, " + name + ". Give me a price for the lot.", "Each? It's all sorts. Price the lot.", "Can't price it each, it's a mix. Offer for the lot."})); return true; }
            if (!bid.hasPrice()) {
                if (bid.asks) { say(pick(new String[]{gp(price) + " gp for the lot, " + name + ".", "The lot's " + gp(price) + " gp. Make me an offer if you like."})); return true; }
                return false;
            }
            long offer = bid.total(price, lotQty, lastBid, haul.size() == 1);
            if (offer < 0 || offer > 100_000_000L) return false;
            if (Bargain.on()) return bargain(offer, bid.free, name); // batch 24: the one engine, behind -Dbargain.on (default off)
            if (offer == 0 || bid.free) { say(pick(new String[]{"Free? I've got goblins to feed, " + name + ".", "Nothing's free on the road. " + gp(price) + " gp.", "Ha! Nice try."})); return true; }
            lastBid = offer;
            if (offer >= price) { say(pick(new String[]{"Done! " + gp(price) + " gp and it's yours, " + name + ".", "That's the price. Trade me and offer " + gp(price) + " gp."})); return true; }
            if (haggles >= 2) { say(pick(new String[]{gp(price) + " gp is my final price, " + name + ". Take it or leave it.", "No more haggling. " + gp(price) + " gp, final."})); return true; }
            haggles++;
            if (floorPrice == 0) { long ref = 0; for (Map.Entry<Integer, Integer> h : haul.entrySet()) ref += (long) Deals.ref(h.getKey()) * h.getValue(); floorPrice = (int) Math.max(1, Math.max(price * 92L / 100, Math.min(Integer.MAX_VALUE, ref * 92 / 100))); } // morning review 10-23: 92% of the pitch and of the bot buyer's reference (was 86%), so buying here and selling to the bot buyer (90%) never pays
            if (offer >= Math.max(price * 0.8, floorPrice)) { price = (int) offer; say(pick(new String[]{"Alright, " + gp(price) + " gp. You drive a hard bargain, " + name + ".", "Fine. " + gp(price) + " gp. Trade me and offer exactly that."})); tell(target, "[" + nickname() + "] Deal at " + gp(price) + " gp. Trade me and offer exactly " + gp(price) + " coins."); return true; }
            if (offer >= price * 0.55) { price = (int) Math.max(floorPrice, Haggle.round((price + offer) / 2)); countered = true; say(pick(new String[]{"Meet me halfway: " + gp(price) + " gp.", "I can't go that low. " + gp(price) + " gp and we shake on it."})); tell(target, "[" + nickname() + "] Counter-offer: " + gp(price) + " gp. Trade me and offer exactly that."); return true; }
            say(pick(new String[]{gp((int) offer) + " gp? For this lot? You've been talking to the goblins, " + name + ".", "Ha! " + gp((int) offer) + " gp wouldn't cover my boots. " + gp(price) + " gp.", "Cheeky. " + gp(price) + " gp, and that's generous."}));
            return true;
        };

        Bargain.Session bs; // batch 24: this trader's open haggle (the target), null when none
        /** Batch 24: the Bargain engine decides; this keeps the old wording hooks and settles nothing (the trade window does). */
        boolean bargain(long offer, boolean free, String name) {
            long now = System.currentTimeMillis();
            String who = target.getUsername();
            if (bs == null || !bs.player.equalsIgnoreCase(who) || Bargain.session(bot.getUsername(), who) != bs)
                bs = Bargain.sell(bot.getUsername(), who, price, bargainFloor(), BargainHooks.trust(target, bot), BargainHooks.afk(target)); // batch 24b: with the 24 h trust recovery
            Bargain.Result r = Bargain.decide(bs, offer, free, now);
            lastBid = offer;
            if (r.lowball) BargainHooks.lowball(target); // batch 24b item 2
            say(Bargain.line(r.lineKey, BargainHooks.style(bot), name, gp((int) r.total)));
            if (r.verdict == Bargain.Verdict.ACCEPT || r.verdict == Bargain.Verdict.COUNTER) price = (int) r.total;
            if (r.verdict == Bargain.Verdict.ACCEPT) tell(target, "[" + nickname() + "] Trade me and offer exactly " + gp(price) + " coins.");
            if (r.verdict == Bargain.Verdict.WALK) {
                if (r.trustStrike) BargainHooks.strike(target, bot);
                PITCHED.merge(who.toLowerCase(), r.holdUntil, Math::max);
                CLAIMED.remove(who.toLowerCase(), this);
                bs = null; target = null; enter(Phase.SEEK);
            }
            return true;
        }
        /** Batch 24 floor: per item max(92% of Deals.ref, low alch), summed, and never under 92% of the pitch (today's floor). */
        long bargainFloor() {
            long f = 0;
            for (Map.Entry<Integer, Integer> h : haul.entrySet()) {
                long alch = 0; try { alch = ItemDefinition.forId(h.getKey()).getAlchemyValue(false); } catch (Throwable ignored) { }
                f += Math.max(Deals.ref(h.getKey()) * 92L / 100, alch) * h.getValue();
            }
            return Math.max(f, price * 92L / 100);
        }

        int secs() {
            return (int) ((System.currentTimeMillis() - phaseStart) / 1000);
        }

        /** True once per phase, the first time the phase has run for at least {@code atSecs}. */
        boolean cue(String key, int atSecs) {
            return secs() >= atSecs && cues.add(key);
        }

        @Override
        public Script newInstance() {
            return null;
        }

        @Override
        public void tick() {
            if (bot == null || !bot.isActive()) return;
            timer++;
            try {
                switch (phase) {
                    case GATHER: gather(); break;
                    case SEEK: seek(); break;
                    case PITCH: pitch(); break;
                    case TRADING: trading(); break;
                    case STASH: stash(); break;
                }
            } catch (Throwable t) {
                System.out.println("[Traders] " + bot.getUsername() + " error in " + phase + ": " + t);
                phase = Phase.STASH;
            }
        }

        AfkTasks.Runner runner;

        /** What this trader gathers for real (2026-10-20: it used to roll a random haul). */
        AfkTasks.Task task() {
            switch (kind) {
                case MINER: return AfkTasks.Task.BEST_ROCK;
                case FOOD: { int f = bot.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.FISHING); return f >= 40 ? AfkTasks.Task.CAGE : f >= 20 ? AfkTasks.Task.LURE : AfkTasks.Task.NET; }
                default: return RNG.nextBoolean() ? AfkTasks.Task.HERB_DRUIDS : AfkTasks.Task.WIZ_RUNES; // druids' herbs, dark wizards' runes
            }
        }

        /** Gather: go and mine, fish (and cook) or hunt for real, banking the haul; then pack it to sell. */
        void gather() {
            String k = bot.getUsername().toLowerCase(java.util.Locale.ROOT);
            if (runner == null) {
                if (timer == 1) clearCoins();
                AfkTasks.Task t = task();
                if (shopAt != null) { shopTick(); return; } // walking to a shop for a tool
                if (t.tools != null && !AfkTasks.hasAny(bot, t.tools)) { // a tool in the bank comes out; otherwise walk to a shop that sells it
                    int tool = t.tools[0];
                    if (bot.getBank().getAmount(tool) > 0 && bot.getBank().remove(new Item(tool, 1))) bot.getInventory().add(new Item(tool, 1));
                    else { if (!goShop(tool, 1)) enter(Phase.STASH); return; }
                }
                if (t == AfkTasks.Task.LURE && bot.getInventory().getAmount(314) < 20) { if (!goShop(314, 50)) enter(Phase.STASH); return; }
                AfkTasks.ensureHerbSpots();
                AfkTasks.Spot[] spots = AfkTasks.SPOTS.get(t);
                if (spots == null || spots.length == 0) { enter(Phase.STASH); return; }
                AfkTasks.Spot sp = spots[0]; for (AfkTasks.Spot s : spots) if (s.at.getDistance(home) < sp.at.getDistance(home)) sp = s;
                Location at = sp.at;
                if (at.getDistance(home) > 100) { Location near = spotNearHome(t, home); if (near != null) at = near; } // pathing review 10-23 T-2: the Catherby fishmonger was sent ~240 tiles to Draynor (the only net spot listed) and stuck on the way
                AfkTasks.Runner r = new AfkTasks.Runner(bot, t, true, FAST ? 8 : 60, false, true);
                r.sell = false; // keep it in the bank to sell to players
                r.cookHaul = kind == Kind.FOOD;
                r.spot = at; r.setDelay(3);
                AfkTasks.launch(r, AfkTasks.State.TO_SPOT, at); // audit 10-23 A-11
                runner = r;
                BotChat.activity(bot, () -> "out " + t.label.toLowerCase() + " for a haul to sell");
                return;
            }
            if (AfkTasks.RUNNING.containsKey(k) && secs() < gatherSecs) return; // still working
            // Pathing review 10-23 T-1: finish() with goods in the pack walks them to a bank and returns without halting, and the
            // runner kept driving the bot while the trader packed (before the deposit, so the haul was short) and walked off to a
            // player; when the deposit came, halt() cleared the trader's walk or trade. Now the trader waits for the runner to halt.
            if (runner != null && !runner.halted && AfkTasks.RUNNING.get(k) == runner) {
                if (!runner.finishing) { try { runner.finish("Enough for a haul."); } catch (Throwable ignored) { } }
                if (!runner.halted && secs() < gatherSecs + (FAST ? 120 : 600)) return; // banking the last of it; it halts itself after the deposit
                if (!runner.halted) { try { runner.halt(); } catch (Throwable ignored) { } System.out.println("[Traders] " + bot.getUsername() + " gave up waiting for the bank trip"); }
            }
            if (AfkTasks.RUNNING.containsKey(k)) { try { AfkTasks.RUNNING.get(k).finish("Enough for a haul."); } catch (Throwable ignored) { } AfkTasks.RUNNING.remove(k); }
            runner = null;
            packFromBank();
            if (haul.isEmpty()) { newTrip(); return; }
            if (price < (FAST ? 50 : 800)) { // not worth a sales pitch (two copper ore): straight to the Exchange, then out again
                for (Map.Entry<Integer, Integer> h : haul.entrySet()) Ledger.supply(bot, h.getKey(), h.getValue());
                haul.clear(); bankTools(); bot.getInventory().clear(); newTrip(); return;
            }
            enter(Phase.SEEK);
            System.out.println("[Traders] " + bot.getUsername() + " packed " + describe(6) + " for " + price + " gp (gathered)");
        }

        // ---- shopping on foot, like the adventurers (the owner: "physically get their stuff")
        Location shopAt; String shopName; int shopItem, shopAmt, shopStalls; long shopUntil; double shopBest; Location shopLastPos;

        /** Picks the nearest real shop that stocks the item and sets off; false if no shop sells it or it can't pay. */
        boolean goShop(int item, int amount) {
            long cost = (long) Math.max(1, PriceIndex.INSTANCE.getValue(item)) * amount;
            if (Ledger.Wallet.coins(bot) < cost) return false;
            Object[] best = null; double bd = Double.MAX_VALUE;
            for (Object[] shop : BotChat.WorldData.shops) {
                boolean sells = false; for (int sid : (int[]) shop[3]) if (sid == item) sells = true;
                if (!sells) continue;
                double d = Location.create((Integer) shop[1], (Integer) shop[2], 0).getDistance(bot.getLocation());
                if (d < bd) { bd = d; best = shop; }
            }
            if (best == null) return false;
            shopAt = Location.create((Integer) best[1], (Integer) best[2], 0); shopName = (String) best[0];
            shopItem = item; shopAmt = amount; shopStalls = 0; shopBest = Double.MAX_VALUE;
            shopUntil = System.currentTimeMillis() + (FAST ? 180_000 : 480_000);
            say(pick(new String[]{"Need a new " + name(item).toLowerCase() + ". Off to " + shopName + ".", shopName + " first. Can't work without a " + name(item).toLowerCase() + "."}));
            BotChat.activity(bot, () -> "walking to " + shopName + " for a " + name(shopItem).toLowerCase());
            System.out.println("[Traders] " + bot.getUsername() + " walking to " + shopName + " for " + amount + " x " + name(item));
            return true;
        }

        void shopTick() {
            if (bot.getWalkingQueue().isMoving()) return;
            Location here = bot.getLocation(); double d = here.getDistance(shopAt);
            boolean there = d <= 5 || d <= 9 && shopStalls >= 2;
            if (there || System.currentTimeMillis() > shopUntil) { // at the counter (or gave up on the walk: bought on the way)
                long cost = (long) Math.max(1, PriceIndex.INSTANCE.getValue(shopItem)) * shopAmt;
                if (Ledger.Wallet.pay(bot, cost)) { if (!bot.getInventory().add(new Item(shopItem, shopAmt))) bot.getBank().add(new Item(shopItem, shopAmt)); System.out.println("[Traders] " + bot.getUsername() + " bought " + shopAmt + " x " + name(shopItem) + (there ? " at " + shopName : " on the way")); }
                shopAt = null; timer = 0; return;
            }
            boolean still = shopLastPos != null && shopLastPos.getX() == here.getX() && shopLastPos.getY() == here.getY(); shopLastPos = here;
            if (d < shopBest - 2) { shopBest = d; shopStalls = 0; } else if (still) { shopStalls++; if (shopStalls == 12 && !RouteMap.usingShortcut(bot)) { try { RouteMap.stuck(bot, shopAt); } catch (Throwable ignored) { } } } // standing still ~20 s: the map learns the blocked way, once (review 10-22: a detour hop is not a stall)
            if (d > AfkTasks.Runner.TELEPORT_MIN && Ledger.Wallet.payTeleport(bot)) { Spectacle.teleport(bot, standOn(shopAt)); return; } // pathing review 10-23 T-4: a standable tile at the shop, not the raw coordinate (4 of 161 shop tiles are solid); batch 15 item1
            Location hop = null; if (NavWalker.ON) { try { hop = NavWalker.step(bot, shopAt); } catch (Throwable ignored) { } }
            if (hop == null) try { hop = RouteMap.nextHop(bot, shopAt); } catch (Throwable ignored) { } // the learned route first
            if (RouteMap.usingShortcut(bot)) return; // pathing review 10-23 R-2: the route's shortcut is being used; walking to "here" reset the walk its click had started
            Location step = hop != null ? hop : d <= 25 ? shopAt : Location.create(here.getX() + (int) Math.round((shopAt.getX() - here.getX()) * 25 / d), here.getY() + (int) Math.round((shopAt.getY() - here.getY()) * 25 / d), 0);
            try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
        }

        /** Pathing review 10-23 T-2: a spot of the same kind within 100 tiles of home where a spot offers this task's option (Catherby's net spots for a Catherby fishmonger), or null. */
        static Location spotNearHome(AfkTasks.Task t, Location home) {
            Location best = null; double bd = 100;
            for (Map.Entry<AfkTasks.Task, AfkTasks.Spot[]> e : AfkTasks.SPOTS.entrySet()) {
                if (e.getKey().kind != t.kind || e.getValue() == null) continue;
                for (AfkTasks.Spot s : e.getValue()) {
                    double d = s.at.getDistance(home);
                    if (d < bd && offers(s.at, t)) { bd = d; best = s.at; }
                }
            }
            return best;
        }

        /** An NPC within 15 tiles of l that is one of the task's targets and offers its option (Net, Lure, Cage...). */
        static boolean offers(Location l, AfkTasks.Task t) {
            if (t.option == null || t.targets == null) return false;
            try {
                for (core.game.node.entity.npc.NPC n : core.game.world.map.RegionManager.getLocalNpcs(l, 15)) {
                    if (n == null || n.getName() == null) continue;
                    boolean named = false; for (String nm : t.targets) if (nm.equalsIgnoreCase(n.getName())) named = true;
                    if (!named) continue;
                    for (core.game.interaction.Option o : n.getInteraction().getOptions()) if (o != null && t.option.equalsIgnoreCase(o.getName())) return true;
                }
            } catch (Throwable ignored) { }
            return false;
        }

        /** Pathing review 10-23 T-4: a standable tile at or near l (its region read first: an unloaded region reads as unknown), else l. */
        static Location standOn(Location l) {
            try { core.game.world.map.Region r = core.game.world.map.RegionManager.forId(((l.getX() >> 6) << 8) | (l.getY() >> 6)); if (r != null && !r.isLoaded()) core.game.world.map.Region.load(r); } catch (Throwable ignored) { }
            Location w = null; try { w = Surveyors.Surveyor.walkable(l); } catch (Throwable ignored) { }
            return w != null ? w : l;
        }

        /** The banked haul (not tools, coins or food it carries) into the pack as notes, priced to sell. */
        void packFromBank() {
            haul.clear(); rare = -1;
            for (Item it : bot.getBank().toArray()) {
                if (it == null || it.getId() == COINS) continue;
                AfkTasks.Task t = task();
                if (t.tools != null && AfkTasks.hasId(t.tools, it.getId())) continue;
                if (it.getId() == 314 || it.getId() == 313) continue;
                if (!PriceIndex.canTrade(it.getId())) continue;
                if (Bounty.wantedNow(it.getId())) continue; // morning review 10-23: on the bounty board, so not for sale (it stays banked)
                if (!bot.getBank().remove(new Item(it.getId(), it.getAmount()))) continue;
                ItemDefinition dd = ItemDefinition.forId(it.getId());
                int base = dd != null && !dd.isUnnoted() && dd.getNoteId() > 0 ? dd.getNoteId() : it.getId(); // one stack per item, noted or not
                haul.merge(base, it.getAmount(), Integer::sum);
                for (int[] e : kind.rares) if (e[0] == it.getId()) rare = it.getId();
            }
            price = 0;
            for (Map.Entry<Integer, Integer> h : haul.entrySet()) price += sellPrice(h.getKey()) * h.getValue();
            for (Map.Entry<Integer, Integer> h : haul.entrySet()) if (!bot.getInventory().add(new Item(noted(h.getKey()), h.getValue()))) bot.getBank().add(new Item(h.getKey(), h.getValue()));
        }

        void rollHaul() {
            haul.clear();
            rare = -1;
            int picks = 2 + RNG.nextInt(3);
            int value = 0;
            for (int i = 0; i < picks * 4 && haul.size() < picks; i++) {
                int[] e = pick(kind.pool);
                if (Bounty.wantedNow(e[0])) continue; // morning review 10-23: not while it is on the bounty board
                int amount = e[2] + RNG.nextInt(e[3] - e[2] + 1);
                int unit = sellPrice(e[0]);
                if (unit <= 0) continue;
                if (value + unit * amount > kind.haulValue) amount = Math.max(1, (kind.haulValue - value) / unit);
                if (value + unit * amount > kind.haulValue && !haul.isEmpty()) continue;
                haul.merge(e[0], amount, Integer::sum);
                value += unit * amount;
            }
            // Now and then a rare find rides along on top of the everyday goods.
            if (RNG.nextInt(100) < kind.rareChance) {
                int[] e = pick(kind.rares);
                if (!Bounty.wantedNow(e[0])) { // morning review 10-23: not while it is on the bounty board
                rare = e[0];
                haul.merge(e[0], e[2] + RNG.nextInt(e[3] - e[2] + 1), Integer::sum);
                }
            }
            price = 0;
            for (Map.Entry<Integer, Integer> h : haul.entrySet()) price += sellPrice(h.getKey()) * h.getValue();
            // Load the goods as notes so a big haul fits in the inventory and the trade window.
            bankTools();
            bot.getInventory().clear();
            for (Map.Entry<Integer, Integer> h : haul.entrySet()) bot.getInventory().add(new Item(noted(h.getKey()), h.getValue()));
        }

        int[] pick(int[][] list) {
            int total = 0;
            for (int[] e : list) total += e[1];
            int r = RNG.nextInt(total);
            for (int[] e : list) { r -= e[1]; if (r < 0) return e; }
            return list[0];
        }

        // Head for the next online player this trip hasn't visited.
        void seek() {
            if (approach()) return;
            // the owner 2026-10-20: two traders sometimes landed on the same player. Now each player is pitched by one
            // trader at a time, at most once every 25 minutes by anyone, and traders pick the nearest free player
            // (not all the first one in the list), keeping clear of a player another trader is working on.
            Player best = null; double bd = Double.MAX_VALUE; long now = System.currentTimeMillis();
            for (Player p : Repository.getPlayers()) {
                if (p == null || p.isArtificial() || !p.isActive()) continue;
                String pk = p.getUsername().toLowerCase();
                if (visited.contains(pk) || CLAIMED.containsKey(pk) || PITCHED.getOrDefault(pk, 0L) > now) continue;
                Location l = p.getLocation();
                if (l == null || l.getZ() != 0 || inWilderness(l) || l.getY() > 4200) continue;
                if (p.inCombat() || p.getInterfaceManager().isOpened()) continue;
                boolean crowded = false;
                for (Map.Entry<String, Trader> c : CLAIMED.entrySet()) { Player o = Repository.getPlayerByName(c.getKey()); if (o != null && o.getLocation().getDistance(l) < 30) crowded = true; }
                if (crowded) continue;
                double d = l.getDistance(bot.getLocation());
                if (d < bd) { bd = d; best = p; }
            }
            if (best == null) {
                if (secs() > 60) enter(Phase.STASH);
                return;
            }
            target = best;
            visited.add(best.getUsername().toLowerCase());
            CLAIMED.put(best.getUsername().toLowerCase(), this);
            PITCHED.put(best.getUsername().toLowerCase(), System.currentTimeMillis() + 25 * 60_000L);
            // the owner 2026-10-20: never pop up next to the player (it landed traders in water). Arrive at a known safe
            // place near them (a bank, a town square, a hangout), then walk over like anyone would.
            Location to = best.getLocation();
            if (to.getDistance(bot.getLocation()) > 60) {
                Location safe = nearestSafe(to);
                if (safe != null && Ledger.Wallet.payTeleport(bot)) Spectacle.teleport(bot, safe); // batch 15 item1
            }
            approachUntil = System.currentTimeMillis() + (FAST ? 90_000 : 240_000);
            BotChat.activity(bot, () -> "walking over to someone with something to sell");
        }

        long approachUntil;

        /** Known safe ground: banks, town squares, the Exchange and the hangouts. */
        static Location nearestSafe(Location near) {
            java.util.List<Location> spots = new java.util.ArrayList<>();
            for (Location b : AfkTasks.BANKS) spots.add(b);
            for (BotLife.Town t : BotLife.Town.values()) spots.add(t.spot);
            spots.add(SmartAdventurers.GRAND_EXCHANGE);
            for (Object[] h : SmartAdventurers.Brain.HANGOUTS) spots.add(Location.create((Integer) h[1], (Integer) h[2], 0));
            Location best = null; double bd = Double.MAX_VALUE;
            for (Location l : spots) { if (l.getZ() != 0) continue; double d = l.getDistance(near); if (d < bd) { bd = d; best = l; } }
            if (best == null) return null;
            Location w = Surveyors.Surveyor.walkable(best);
            return w != null ? w : best;
        }

        /** Walking over to the chosen player; the pitch starts once it's beside them. */
        boolean approach() {
            if (target == null) return false;
            if (!target.isActive() || target.getLocation().getZ() != 0) { CLAIMED.remove(target.getUsername().toLowerCase(), this); target = null; return true; }
            double d = bot.getLocation().getDistance(target.getLocation());
            if (d <= 3) { bot.face(target); enter(Phase.PITCH); offered = false; return true; }
            if (System.currentTimeMillis() > approachUntil) { CLAIMED.remove(target.getUsername().toLowerCase(), this); target = null; return true; } // couldn't get there: try someone else
            if (bot.getWalkingQueue().isMoving()) return true;
            Location here = bot.getLocation(), there = target.getLocation();
            Location hop = null; if (d > 20 && NavWalker.ON) { try { hop = NavWalker.step(bot, there); } catch (Throwable ignored) { } }
            if (hop == null && d > 20) try { hop = RouteMap.nextHop(bot, there); } catch (Throwable ignored) { } // far off: the learned route; close in: straight to them
            if (RouteMap.usingShortcut(bot)) return true; // pathing review 10-23 R-2: the route's shortcut is being used
            Location step = hop != null ? hop : d <= 20 ? there.transform(RNG.nextInt(3) - 1, RNG.nextInt(3) - 1, 0) : Location.create(here.getX() + (int) Math.round((there.getX() - here.getX()) * 20 / d), here.getY() + (int) Math.round((there.getY() - here.getY()) * 20 / d), 0);
            Location w = Surveyors.Surveyor.walkable(step);
            try { ((AIPlayer) bot).walkToPosSmart(w != null ? w : step); } catch (Throwable ignored) { }
            return true;
        }

        // Introduce the haul, remind once or twice, then move on after about three minutes.
        void pitch() {
            if (target == null || !target.isActive()) { enter(Phase.SEEK); return; }
            String name = target.getUsername();
            if (cue("hello", 1)) { say(String.format(pick(kind.greetings), name)); System.out.println("[Traders] " + bot.getUsername() + " pitched " + describe(3) + " for " + gp(price) + " gp to " + name + " at " + bot.getLocation()); }
            if (rare > 0 && cue("rare", 14)) say(pick(new String[]{
                    "And for the discerning buyer... a genuine " + name(rare).toLowerCase() + "!",
                    "Did I mention the " + name(rare).toLowerCase() + "? Found it myself. Honest.",
                    "Pride of the collection: one " + name(rare).toLowerCase() + ". Don't tell the guards."}));
            if (cue("goods", 5)) {
                String g = describe(3) + (haul.size() > 3 ? " and more" : "");
                say(pick(new String[]{"I've got " + g + ".", "Today's goods: " + g + ".", "Feast your eyes: " + g + ".", "Fresh in: " + g + ".", "In the sack: " + g + "."}));
                tell(target, "[" + nickname() + "] I'm a " + kind.title + ". For sale: " + describe(99) + ".");
            }
            if (cue("price", 9)) {
                say(pick(new String[]{"The lot for " + gp(price) + " gp. Trade me and put up exactly that!", gp(price) + " gp takes the lot. Trade me.", "Yours for " + gp(price) + " gp. Cheaper than the Exchange.", "Call it " + gp(price) + " gp, all of it. Trade me and offer that.", gp(price) + " gp. A bargain, and you know it."}));
                tell(target, "[" + nickname() + "] All of it for " + gp(price) + " gp. Trade me and offer exactly " + gp(price) + " coins.");
            }
            if (cue("quip", 40)) say(pick(kind.quips));
            if (cue("once", 90)) say(pick(new String[]{"Still " + gp(price) + " gp for the lot, " + name + ". Going once...", "Going once, " + name + "! " + gp(price) + " gp.", gp(price) + " gp, " + name + ". Last chance coming up."}));
            if (cue("twice", 150)) say(pick(new String[]{"Going twice... I'll find another buyer soon.", "Going twice! Someone else will snap it up.", "Twice! Don't say I didn't warn you."}));
            double away = target.getLocation().getDistance(bot.getLocation());
            if (away > 35 || target.getLocation().getZ() != bot.getLocation().getZ()) { // they left (ran off or teleported): no chasing across the map
                say(pick(new String[]{"And they're off. Fair enough.", "Gone! Some people.", "Right. Next customer."}));
                CLAIMED.remove(target.getUsername().toLowerCase(), this);
                target = null; enter(Phase.SEEK); return;
            }
            if (away > 8 && secs() > 15 && timer % 8 == 0) {
                Location w = Surveyors.Surveyor.walkable(target.getLocation().transform(RNG.nextInt(3) - 1, RNG.nextInt(3) - 1, 0));
                scriptAPI.walkTo(w != null ? w : target.getLocation()); // follow at a polite distance, onto walkable ground
            }
            if (target.getRequestManager().getTarget() == bot) {
                InteractionListeners.run(-1, IntType.PLAYER, "trade with", bot, target);
                coinsBeforeTrade = bot.getInventory().getAmount(COINS);
                offered = false;
                enter(Phase.TRADING);
                return;
            }
            if (secs() >= 180) {
                say(pick(new String[]{"No sale? Fair enough. Safe travels, " + name + "!",
                        "Your loss, " + name + "! Off to find a richer adventurer.",
                        "Another time then. Mind the goblins!"}));
                target = null;
                enter(Phase.SEEK);
            }
        }

        // Inside the trade window: put the haul up, accept once the price is offered.
        void trading() {
            TradeModule mine = TradeModule.getExtension(bot);
            if (mine == null) {
                if (bot.getInventory().getAmount(COINS) >= coinsBeforeTrade + price && price > 0) {
                    say(pick(new String[]{"Pleasure doing business, " + target.getUsername() + "!",
                            "Sold! Spend it wisely. Or don't, I'm not your mother.",
                            "A fine deal. Off I go to find more!"}));
                    System.out.println("[Traders] " + bot.getUsername() + " sold its haul to " + target.getUsername() + " for " + price + " gp");
                    try { if (haul.size() == 1) { Map.Entry<Integer, Integer> h1 = haul.entrySet().iterator().next(); if (h1.getValue() > 0) Failures.Econ.dealPrice(bot, h1.getKey(), (long) price / h1.getValue(), "player", "trader_sale"); } } catch (Throwable ignored) { } // batch 19c §4.6: the trader's accepted unit (one-item hauls)
                    if (bs != null && bs.player.equalsIgnoreCase(target.getUsername()) && Bargain.session(bot.getUsername(), bs.player) == bs) {
                        Bargain.sold(bs, price, System.currentTimeMillis()); // batch 24: books the discount, exit rule, deals log
                        try { int q = 0; for (int v : haul.values()) q += v; BargainHooks.dealt(target, bot, haul.size() == 1 ? name(haul.keySet().iterator().next()) : "haul", q, price); } catch (Throwable ignored) { } // batch 24b item 2
                    }
                    bs = null;
                    if (Bounty.exemptDeals) try { for (Map.Entry<Integer, Integer> h : haul.entrySet()) Bounty.boughtFromBots(target, h.getKey(), h.getValue()); } catch (Throwable ignored) { } // P38 L8 (audit X-6): no bounty premium on the haul
                    haul.clear();
                    clearCoins(); // its takings go in the bank (they used to be deleted)
                    bankTools();
                    bot.getInventory().clear();
                    target = null;
                    newTrip();
                    return;
                }
                if (secs() > 20) { // trade closed without a sale: keep pitching to the same player for a bit
                    say(pick(new String[]{"Changed your mind? The offer stands for a little longer.", "No? Offer's still open for a bit.", "Think it over. I'll be here a minute."}));
                    enter(Phase.PITCH);
                    phaseStart -= 120_000; // about a minute left before moving on
                    cues.add("hello"); cues.add("goods"); cues.add("price"); cues.add("quip"); cues.add("once");
                }
                return;
            }
            phaseStart = System.currentTimeMillis(); // still trading
            if (mine.getTarget() != target) { mine.decline(); return; }
            if (!offered) {
                for (Item item : bot.getInventory().toArray()) {
                    if (item == null || item.getId() == COINS) continue;
                    mine.getContainer().offer(bot.getInventory().getSlot(item), item.getAmount());
                }
                offered = true;
                return;
            }
            TradeModule theirs = TradeModule.getExtension(target);
            if (theirs == null || mine.isAccepted()) return;
            int coins = theirs.getContainer().getAmount(COINS);
            boolean onlyCoins = true;
            for (Item item : theirs.getContainer().toArray()) if (item != null && item.getId() != COINS) onlyCoins = false;
            if (coins >= price && onlyCoins) mine.setAccepted(true, true);
            else if (!onlyCoins && RNG.nextInt(20) == 0) say(pick(new String[]{"Just coins, please. I'm selling today, not buying.", "Coins only, friend. No swaps.", "I don't need your bits, just the gold."}));
            else if (coins > 0 && coins < price && RNG.nextInt(20) == 0) say(pick(new String[]{"That's " + gp(price - coins) + " gp short, friend.", gp(price - coins) + " gp more and it's yours.", "Bit light. " + gp(price - coins) + " gp to go."}));
        }

        // Nobody bought: the goods go into the bots' Grand Exchange stock, then a new trip.
        void stash() {
            if (!haul.isEmpty()) {
                for (Map.Entry<Integer, Integer> h : haul.entrySet()) Ledger.supply(bot, h.getKey(), h.getValue());
                System.out.println("[Traders] " + bot.getUsername() + " stashed an unsold haul on the GE: " + describe(99));
                say(pick(new String[]{"Nobody's buying today. Into the Exchange it goes!",
                        "Right, I'll leave this lot with the Grand Exchange clerks.",
                        "Back to work then. There's always more out there."}));
            }
            haul.clear();
            clearCoins();
            bankTools();
            bot.getInventory().clear();
            newTrip(); // walks out to its spot (paid teleport only if far), no free teleport home
        }

        /** Tools, bait and feathers go to the bank, not the bin, before any inventory clear (audit B6; audit 10-22 E-15: the small-haul, load and sold paths still deleted them). */
        void bankTools() {
            for (Item it : bot.getInventory().toArray()) {
                if (it == null) continue;
                AfkTasks.Task t = task();
                if ((t.tools != null && AfkTasks.hasId(t.tools, it.getId())) || it.getId() == 313 || it.getId() == 314) { if (bot.getInventory().remove(it)) bot.getBank().add(it); }
            }
        }

        // ------------------------------------------------------------ helpers

        /** Coins in the pack go to the bank (its wallet); they used to be deleted. */
        void clearCoins() {
            int c = bot.getInventory().getAmount(COINS);
            if (c > 0 && bot.getInventory().remove(new Item(COINS, c))) bot.getBank().add(new Item(COINS, c));
        }

        String nickname() {
            return bot.getUsername() + " the " + kind.title;
        }

        void say(String text) {
            BotChat.say(bot, text);
        }

        static void tell(Player p, String text) {
            for (String line : BotChat.chunks(text, 75)) p.getPacketDispatch().sendMessage(line);
        }

        String describe(int max) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Integer, Integer> h : haul.entrySet()) {
                if (parts.size() >= max) break;
                parts.add(h.getValue() + " " + name(h.getKey()).toLowerCase());
            }
            return String.join(", ", parts);
        }

        static <T> T pick(T[] options) {
            return options[RNG.nextInt(options.length)];
        }

        static String pick(String[] options) { return BotChat.pick(options); } // lines: no repeats in a short window
    }

    // Halfway between what bots pay (guide) and what they charge (bot price), per item.
    static int sellPrice(int id) {
        int guide = PriceIndex.INSTANCE.getValue(id);
        int botPrice = GrandExchange.Companion.getRecommendedPrice(id, true);
        return Math.max(1, Math.max((guide + botPrice) / 2, Deals.ref(id) * 92 / 100)); // morning review 10-23: never under 92% of the bot buyer's reference
    }

    static int noted(int id) {
        ItemDefinition d = ItemDefinition.forId(id);
        return d != null && !d.isStackable() && d.getNoteId() > 0 ? d.getNoteId() : id;
    }

    static String name(int id) {
        ItemDefinition d = ItemDefinition.forId(id);
        return d == null || d.getName() == null ? "item " + id : d.getName();
    }

    static String gp(int n) {
        return String.format("%,d", n);
    }

    static boolean inWilderness(Location l) {
        return l.getX() >= 2944 && l.getX() <= 3392 && l.getY() >= 3520 && l.getY() <= 3970;
    }
}
