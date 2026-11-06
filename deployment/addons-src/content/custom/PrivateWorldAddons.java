package content.custom;

import content.global.bots.CannonballSmelter;
import content.global.bots.CoalMiner;
import content.global.bots.LobsterCatcher;
import content.global.bots.SharkCatcher;
import core.api.StartupListener;
import core.game.bots.GeneralBotCreator;
import core.game.bots.SkillingBotAssembler;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.ge.GrandExchangeRecords;
import core.game.ge.OfferState;
import core.game.ge.PriceIndex;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;
import core.tools.SystemLogger;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Private-world additions, loaded from the overlay classpath by the server's ClassScanner.
 *
 * 1. Extra supply bots: coal miners, cannonball smelters, lobster and shark fishers.
 * 2. Bot buyers: player GE sell offers listed at or below 85% of the guide price (audit E-14; guide clamped per E-4), and at least
 *    five minutes old, are bought at the asking price, up to a daily coin cap per player.
 *    The bought items become bot stock (resold at the usual bot price). These trades never
 *    touch the price index.
 */
public final class PrivateWorldAddons implements StartupListener {
    private static final int EXTRA_COAL_MINERS = 4;
    private static final int EXTRA_CANNONBALL_SMELTERS = 2;
    private static final int EXTRA_LOBSTER_FISHERS = 0;
    private static final int EXTRA_SHARK_FISHERS = 0;

    private static final int BUYER_DAILY_CAP_GP = 250_000;
    private static final long BUYER_MIN_OFFER_AGE_MS = 5 * 60_000L;
    private static final int BUYER_INTERVAL_TICKS = 200; // 2 minutes at 600 ms per tick
    private static final long BUYER_START_DELAY_MS = 60_000L;
    private static final int COINS = 995;
    private static final ZoneId DAY_ZONE = ZoneId.of("America/New_York");

    private static final Random RANDOM = new Random();
    private static final Timer TIMER = new Timer("PrivateWorldAddons", true);
    private static final Map<Integer, Integer> spentToday = new java.util.concurrent.ConcurrentHashMap<>(); // audit 10-23 batch14 item3: read by the ledger save on its timer
    private static volatile LocalDate spentDay = null;
    private static final long BUYER_GLOBAL_CAP_GP = Long.getLong("botbuyer.globalCap", 1_000_000); // audit E-4: new coins per day, all players together
    private static volatile long spentAll;
    static volatile long BUYER_FILLS; // audit 10-23 batch14 item5

    /** audit 10-23 batch14 item3: the buyer's spend today, into and out of ledger.json ("caps"), keyed by its day (America/New_York). */
    @SuppressWarnings("unchecked")
    static void capsTo(org.json.simple.JSONObject c) {
        LocalDate d = spentDay; if (d == null) return;
        org.json.simple.JSONObject per = new org.json.simple.JSONObject();
        for (Map.Entry<Integer, Integer> e : spentToday.entrySet()) per.put(String.valueOf(e.getKey()), e.getValue());
        c.put("buyerDay", d.toString()); c.put("buyerSpentAll", spentAll); c.put("buyerSpentToday", per);
    }
    static String capsFrom(org.json.simple.JSONObject c) {
        String today = LocalDate.now(DAY_ZONE).toString(); // its own day (America/New_York), as before
        if (!today.equals(c.get("buyerDay")) || !(c.get("buyerSpentAll") instanceof Number)) return null;
        spentDay = LocalDate.parse(today); spentAll = ((Number) c.get("buyerSpentAll")).longValue(); spentToday.clear();
        Object per = c.get("buyerSpentToday");
        if (per instanceof org.json.simple.JSONObject) for (Object k : ((org.json.simple.JSONObject) per).keySet()) { Object v = ((org.json.simple.JSONObject) per).get(k); if (v instanceof Number) spentToday.put(Integer.parseInt(String.valueOf(k)), ((Number) v).intValue()); }
        return "bot buyer " + spentAll + " gp (" + spentToday.size() + " accounts)";
    }

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings != null && settings.getEnable_bots()) {
            spawnExtraBots();
        }
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                GameWorld.getPulser().submit(new Pulse(BUYER_INTERVAL_TICKS) {
                    @Override
                    public boolean pulse() {
                        try {
                            buyPlayerOffers();
                        } catch (Throwable t) {
                            SystemLogger.logGE("[BotBuyer] check failed: " + t);
                        }
                        return false; // keep running
                    }
                });
                SystemLogger.logGE("[BotBuyer] started: offers at or below 85% of guide, "
                        + BUYER_MIN_OFFER_AGE_MS / 60_000 + " min old, cap " + BUYER_DAILY_CAP_GP + " gp/player/day");
            }
        }, BUYER_START_DELAY_MS);
    }

    private static void spawnExtraBots() {
        SkillingBotAssembler assembler = new SkillingBotAssembler();
        for (int i = 0; i < EXTRA_COAL_MINERS; i++) {
            later(() -> new GeneralBotCreator(new CoalMiner(),
                    assembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3037, 9737, 0))));
        }
        for (int i = 0; i < EXTRA_CANNONBALL_SMELTERS; i++) {
            later(() -> new GeneralBotCreator(new CannonballSmelter(),
                    assembler.produce(SkillingBotAssembler.Wealth.AVERAGE, Location.create(3013, 3356, 0))));
        }
        for (int i = 0; i < EXTRA_LOBSTER_FISHERS; i++) {
            later(() -> new GeneralBotCreator(new LobsterCatcher(), Location.create(2805, 3435, 0)));
        }
        for (int i = 0; i < EXTRA_SHARK_FISHERS; i++) {
            later(() -> new GeneralBotCreator(new SharkCatcher(), Location.create(2604, 3421, 0)));
        }
        int total = EXTRA_COAL_MINERS + EXTRA_CANNONBALL_SMELTERS + EXTRA_LOBSTER_FISHERS + EXTRA_SHARK_FISHERS;
        SystemLogger.logStartup("[ExtraBots] scheduling " + total + " extra supply bots");
    }

    /** Spread spawns out like the stock adventurer spawner does. */
    private static void later(Runnable spawn) {
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    spawn.run();
                } catch (Throwable t) {
                    SystemLogger.logStartup("[ExtraBots] spawn failed: " + t);
                    try { Failures.Life.loginFailed("extra", null, t); } catch (Throwable ignored) { } // batch 20 (19b+c follow-up 4)
                }
            }
        }, 20_000L + RANDOM.nextInt(100_000));
    }

    private static void buyPlayerOffers() {
        LocalDate today = LocalDate.now(DAY_ZONE);
        if (!today.equals(spentDay)) {
            spentToday.clear();
            spentDay = today;
            spentAll = 0;
        }
        long now = System.currentTimeMillis();
        for (GrandExchangeOffer offer : GrandExchange.Companion.getValidOffers()) {
            if (!offer.getSell() || offer.isBot() || offer.getPlayerUID() <= 0) continue;
            OfferState state = offer.getOfferState();
            if (state != OfferState.REGISTERED && state != OfferState.UPDATED) continue;
            int left = offer.getAmountLeft();
            int item = offer.getItemID();
            int price = offer.getOfferedValue();
            if (left <= 0 || item == COINS || price <= 0) continue;
            if (now - offer.getTimeStamp() < BUYER_MIN_OFFER_AGE_MS) continue;

            int guide = MarketBrain.botRef(item); // audit E-4: not the raw guide, which alt-to-alt trades can push up
            int cap = Bounty.wantedNow(item) ? Bounty.BUYER_WANTED_PERCENT : Bounty.BUYER_PERCENT; // morning review 10-23: 90% (wanted 100%); bots never sell under 92% and never sell wanted items, so no loop pays
            if (guide <= 0 || price > (long) guide * cap / 100) continue;

            int uid = offer.getPlayerUID();
            int spent = spentToday.getOrDefault(uid, 0);
            int amount = (int) Math.min(left, Math.min(BUYER_DAILY_CAP_GP - spent, BUYER_GLOBAL_CAP_GP - spentAll) / (long) price); // audit E-4: and a cap for all players together
            if (amount <= 0) continue;
            int coins = amount * price;
            spentAll += coins;
            spentToday.put(uid, spent + coins); // reserved now; what the worker can't buy is handed back in bought()

            // audit 10-23 X-3: the fill runs on the GE worker, which re-reads the offer, so a player's buy matched against the
            // same offer can no longer be filled from it as well (both used to write their own copy: units delivered twice).
            final long offerUid = offer.getUid(); final int fItem = item, fPrice = price, fAmount = amount, fUid = uid; final LocalDate day = spentDay;
            GrandExchange.Companion.runOnWorker(() -> {
                int got = GrandExchange.Companion.botBuyFill(offerUid, fItem, fPrice, fAmount);
                GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { bought(day, fUid, fItem, fPrice, fAmount, got, offerUid); return true; } });
            });
        }
    }

    /** audit 10-23 X-3, back on the game thread after the worker's fill: return the unspent budget, then the records, bounty and log. */
    private static void bought(LocalDate day, int uid, int item, int price, int wanted, int got, long offerUid) {
        if (got < wanted && day != null && day.equals(spentDay)) {
            long back = (long) (wanted - got) * price;
            spentAll = Math.max(0, spentAll - back);
            spentToday.computeIfPresent(uid, (k, v) -> (int) Math.max(0, v - back));
        }
        if (got < wanted) SystemLogger.logGE("[BotBuyer] offer " + offerUid + " had " + got + " of the " + wanted + " left when the worker reached it");
        if (got <= 0) return;
        BUYER_FILLS += (long) got * price; // audit 10-23 batch14 item5: new coins the bot buyer paid players (a real mint), for data/metrics.json
        Player player = Repository.getPlayerByUid(uid);
        if (player != null) {
            GrandExchangeRecords records = GrandExchangeRecords.getInstance(player);
            records.visualizeRecords();
            records.setUpdateNotification(true);
            try { Bounty.playerSold(player, item, got); } catch (Throwable ignored) { } // audit E-3: the bounty is paid only on sales that went into bot stock
        }
        SystemLogger.logGE("[BotBuyer] bought " + got + "x item " + item + " at " + price
                + " gp from player " + uid + " (offer " + offerUid + ")");
    }
}
