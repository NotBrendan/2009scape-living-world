package core.game.ge;

import core.ServerConstants;
import core.api.Commands;
import core.api.ContentAPIKt;
import core.api.StartupListener;
import core.cache.def.impl.ItemDefinition;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.info.PlayerDetails;
import core.game.system.command.Privilege;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.repository.Repository;
import core.tools.Log;
import core.tools.SystemLogger;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Shadows the server's GrandExchange (the overlay comes before server.jar on the classpath, like BotPrices). A line for
 * line Java port of GrandExchange.kt with the same public members, plus two changes from the 2026-10-22 audit:
 *
 *  E-2: a player's buy filled from bot stock (tryExchangeWithBots) is reported to MarketBrain.botStockFilled, so the
 *       bot suppliers are paid only for units that really left bot stock (they used to be paid for every completed
 *       player buy, including alt-to-alt trades, and the 7-day diff dropped real purchases).
 *  E-6: a bot-stock take (a negative bot offer from MarketBrain.takeLocked) never takes the pile below zero; the
 *       worker tells MarketBrain how much it really took so the reservation is released then, not on a timer.
 */
public final class GrandExchange implements StartupListener, Commands {
    public static final Companion Companion = new Companion();

    private boolean isRunning;
    private static final LinkedBlockingDeque<GrandExchangeOffer> pendingOffers = new LinkedBlockingDeque<>();
    private static final String GET_SPECIFIC_OFFER_BY_UID = "SELECT * FROM player_offers WHERE uid = ?;";
    private static final String GET_MATCHES_FROM_PLAYER_OFFERS = "SELECT * FROM player_offers WHERE item_id = ? AND is_sale = ? AND offer_state < 4 AND NOT offer_state = 2;";
    private static final String GET_MATCH_FROM_BOT_OFFERS = "SELECT * FROM bot_offers WHERE item_id = ?;";

    public GrandExchange() { }

    public final boolean isRunning() { return isRunning; }
    public final void setRunning(boolean b) { isRunning = b; }

    public final void boot() {
        if (isRunning) return;
        SystemLogger.logGE("Initializing GE Update Worker");
        new Thread(() -> {
            Thread.currentThread().setName("GE Update Worker");
            while (true) {
                try {
                    GrandExchangeOffer offer = pendingOffers.takeFirst();
                    if (offer == JOB_MARK) { runJobs(); continue; } // audit 10-23 X-3
                    if (offer.isBot() && offer.getAmount() < 0) { takeFromBotStock(offer); continue; } // audit E-6
                    if (offer.isBot()) { addToBotStock(offer.getItemID(), rawAmount(offer)); continue; } // audit 10-23 X-1: writeNew adds at most botstock_limit
                    offer.writeNew();
                    offer = Companion.getOfferByUid(offer.getUid());
                    if (offer == null) continue;
                    final GrandExchangeOffer o = offer;
                    List<GrandExchangeOffer> matches = selectPotentialMatches(o);
                    if (MATCH_DELAY_MS > 0 && !matches.isEmpty()) { SystemLogger.logGE("[GE] test hook: holding " + matches.size() + " matches for " + MATCH_DELAY_MS + " ms"); Thread.sleep(MATCH_DELAY_MS); } // audit 10-23 X-3 test hook (-Dtest.ge.matchDelayMs), off by default
                    matches.sort(Comparator.comparingInt(it -> o.getSell() ? -it.getOfferedValue() : it.getOfferedValue()));
                    for (GrandExchangeOffer match : matches) {
                        if (o.getSell() ? match.getOfferedValue() >= o.getOfferedValue() : match.getOfferedValue() <= o.getOfferedValue()) Companion.exchange(o, match);
                    }
                    if (!o.isBot() && o.getAmountLeft() > 0 && !o.getSell()) tryExchangeWithBots(o);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) { // the Kotlin worker died on any exception; keep the Exchange running
                    SystemLogger.logGE("[GE] worker error: " + t);
                }
            }
        }).start();
        isRunning = true;
    }

    /**
     * audit 10-23 X-3: work that rewrites player offers (the bot buyer's fills) runs on this worker, in turn with the
     * player offers, so it can never interleave with a match that read the same offer. A job is queued with
     * Companion.runOnWorker; the JOB_MARK entry in the offer queue wakes the worker to run the jobs waiting.
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> JOBS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final GrandExchangeOffer JOB_MARK = new GrandExchangeOffer();
    private static final long MATCH_DELAY_MS = Long.getLong("test.ge.matchDelayMs", 0L);
    private static void runJobs() {
        Runnable r;
        while ((r = JOBS.poll()) != null) { try { r.run(); } catch (Throwable t) { SystemLogger.logGE("[GE] worker job failed: " + t); } }
    }

    /** Audit E-6: a take is applied with a floor at zero, and the real amount taken is reported back. */
    private static void takeFromBotStock(GrandExchangeOffer offer) {
        final int id = offer.getItemID(), want = -offer.getAmount();
        final int[] took = {0};
        GEDB.run(conn -> {
            try (Statement st = conn.createStatement()) {
                ResultSet r = st.executeQuery("SELECT amount FROM bot_offers WHERE item_id = " + id);
                int have = r.next() ? r.getInt(1) : 0;
                took[0] = Math.max(0, Math.min(want, have));
                if (took[0] > 0) st.executeUpdate("UPDATE bot_offers SET amount = " + (have - took[0]) + " WHERE item_id = " + id);
            } catch (Throwable t) { SystemLogger.logGE("[GE] bot stock take failed: " + t); }
            return Unit.INSTANCE;
        });
        try { content.custom.MarketBrain.takeApplied(id, want, took[0]); } catch (Throwable ignored) { }
    }

    /**
     * audit 10-23 X-1: the engine's bot-offer getters report at most botstock_limit (default 5000), so its writeNew added
     * at most that much. The raw amount is the property's backing field.
     */
    private static java.lang.reflect.Field amountField;
    static int rawAmount(GrandExchangeOffer o) {
        try { if (amountField == null) { java.lang.reflect.Field f = GrandExchangeOffer.class.getDeclaredField("amount"); f.setAccessible(true); amountField = f; } return amountField.getInt(o); }
        catch (Throwable t) { return o.getAmount(); }
    }
    /** audit 10-23 X-1: a supply adds its whole amount to the pile (SQL amount = amount + n), never a capped one. */
    private static void addToBotStock(int id, int n) {
        if (n <= 0) return;
        GEDB.run(conn -> {
            try (Statement st = conn.createStatement()) {
                if (st.executeUpdate("UPDATE bot_offers SET amount = amount + " + n + " WHERE item_id = " + id) == 0) st.executeUpdate("INSERT INTO bot_offers(item_id,amount) values(" + id + "," + n + ")");
            } catch (Throwable t) { SystemLogger.logGE("[GE] bot stock supply failed: " + t); }
            return Unit.INSTANCE;
        });
    }

    private void tryExchangeWithBots(GrandExchangeOffer offer) {
        if (content.custom.Bounty.wantedNow(offer.getItemID())) return; // morning review 10-23: bot stock isn't sold while the item is on the bounty board
        final GrandExchangeOffer[] bot = {null};
        final int before = offer.getCompletedAmount();
        GEDB.run(conn -> {
            try {
                PreparedStatement query = conn.prepareStatement(GET_MATCH_FROM_BOT_OFFERS);
                query.setInt(1, offer.getItemID());
                ResultSet res = query.executeQuery();
                if (res.next()) {
                    int have = res.getInt("amount");
                    // audit 10-23 X-2: units reserved for queued bot takes are not for sale (the take would have floored to zero and the same units left twice).
                    // Reservations are made while holding this lock (MarketBrain.takeFromStock), so the count is exact here.
                    int free = have - content.custom.MarketBrain.reservedTakes(offer.getItemID());
                    if (free > 0) {
                        GrandExchangeOffer b = GrandExchangeOffer.Companion.fromBotQuery(res);
                        b.setAmount(free);
                        b.setTimeStamp(offer.getTimeStamp() - 1L);
                        bot[0] = b;
                        Companion.exchange(offer, b);
                        int took = offer.getCompletedAmount() - before;
                        if (took > 0) try (PreparedStatement up = conn.prepareStatement("UPDATE bot_offers SET amount = ? WHERE item_id = ?")) { // audit 10-23 X-1: the engine wrote min(left, botstock_limit)
                            up.setInt(1, have - took); up.setInt(2, offer.getItemID()); up.executeUpdate();
                        }
                    }
                }
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            return Unit.INSTANCE;
        });
        int filled = offer.getCompletedAmount() - before;
        if (filled > 0 && bot[0] != null) { // audit E-2: these units really came out of bot stock
            try { content.custom.MarketBrain.botStockFilled(offer.getItemID(), filled, bot[0].getOfferedValue(), offer.getPlayerUID()); } catch (Throwable ignored) { }
        }
    }

    private List<GrandExchangeOffer> selectPotentialMatches(GrandExchangeOffer offer) {
        List<GrandExchangeOffer> matches = new ArrayList<>();
        GEDB.run(conn -> {
            try {
                PreparedStatement query = conn.prepareStatement(GET_MATCHES_FROM_PLAYER_OFFERS);
                query.setInt(1, offer.getItemID());
                query.setBoolean(2, !offer.getSell());
                ResultSet res = query.executeQuery();
                while (res.next()) matches.add(GrandExchangeOffer.Companion.fromQuery(res));
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            return Unit.INSTANCE;
        });
        return matches;
    }

    @Override
    public void defineCommands() {
        define("addbotoffer", Privilege.ADMIN, "::addbotoffer <lt>item-id<gt> <lt>amount<gt>", "Adds a GE bot sell offer for the given item ID and amount.", (player, strings) -> {
            int id = Integer.parseInt(strings[1]);
            int amount = Integer.parseInt(strings[2]);
            Companion.addBotOffer(id, amount);
            notify(player, "Added " + amount + "x " + ContentAPIKt.getItemName(id) + " to the bot offers.", false);
            return Unit.INSTANCE;
        });
        define("bange", Privilege.ADMIN, "::bange <lt>item-id<gt>", "Bans/blacklists the specified item from GE trades.", (player, strings) -> {
            int id = Integer.parseInt(strings[1]);
            PriceIndex.INSTANCE.banItem(id);
            notify(player, "Banned " + ContentAPIKt.getItemName(id) + " from GE trade.", false);
            return Unit.INSTANCE;
        });
        define("allowge", Privilege.ADMIN, "::allowge <lt>item-id<gt>", "Allows/whitelists the specified item to be traded on the GE.", (player, strings) -> {
            int id = Integer.parseInt(strings[1]);
            PriceIndex.INSTANCE.allowItem(id);
            notify(player, "Allowed " + ContentAPIKt.getItemName(id) + " for GE trade.", false);
            return Unit.INSTANCE;
        });
    }

    @Override public void startup() { GEDB.INSTANCE.init(); boot(); }

    @Override public void reject(Player player, String... message) { Commands.DefaultImpls.reject(this, player, message); }
    @Override public void notify(Player player, String message, boolean b) { Commands.DefaultImpls.notify(this, player, message, b); }
    @Override public void define(String name, Privilege privilege, String usage, String description, Function2<? super Player, ? super String[], Unit> handle) { Commands.DefaultImpls.define(this, name, privilege, usage, description, handle); }

    public static int getRecommendedPrice(int itemID, boolean fromBot) { return Companion.getRecommendedPrice(itemID, fromBot); }
    public static String getOfferStats(int itemID, boolean sale) { return Companion.getOfferStats(itemID, sale); }

    public static final class Companion {
        private Companion() { }
        public Companion(kotlin.jvm.internal.DefaultConstructorMarker m) { }

        public final LinkedBlockingDeque<GrandExchangeOffer> getPendingOffers() { return pendingOffers; }

        /** audit 10-23 X-3: runs r on the GE worker, after every offer queued before it. */
        public final void runOnWorker(Runnable r) { JOBS.add(r); pendingOffers.addLast(JOB_MARK); }

        /**
         * audit 10-23 X-3, on the GE worker only: the bot buyer takes up to want units of a player's sell offer. The offer is
         * re-read here and must still be open, at the same item and price; the fill and the bot-stock add are one worker
         * step. Returns the units bought (0 when a player's buy got there first).
         */
        public final int botBuyFill(long uid, int itemId, int price, int want) {
            final int[] got = {0};
            GEDB.run(conn -> {
                GrandExchangeOffer o = getOfferByUid(uid);
                if (o == null || !o.getSell() || o.isBot() || o.getItemID() != itemId || o.getOfferedValue() != price) return Unit.INSTANCE;
                if (o.getOfferState() != OfferState.REGISTERED && o.getOfferState() != OfferState.UPDATED) return Unit.INSTANCE;
                int n = Math.min(want, o.getAmountLeft());
                if (n <= 0) return Unit.INSTANCE;
                int coins = n * price;
                o.setCompletedAmount(o.getCompletedAmount() + n);
                o.addWithdrawItem(995, coins);
                o.setTotalCoinExchange(o.getTotalCoinExchange() + coins);
                if (o.getAmountLeft() < 1) o.setOfferState(OfferState.COMPLETED);
                o.update();
                addToBotStock(itemId, n);
                got[0] = n;
                return Unit.INSTANCE;
            });
            return got[0];
        }

        private GrandExchangeOffer getOfferByUid(long uid) {
            final GrandExchangeOffer[] offer = {null};
            GEDB.run(conn -> {
                try {
                    PreparedStatement query = conn.prepareStatement(GET_SPECIFIC_OFFER_BY_UID);
                    query.setLong(1, uid);
                    ResultSet res = query.executeQuery();
                    if (res.next()) offer[0] = GrandExchangeOffer.Companion.fromQuery(res);
                } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
                return Unit.INSTANCE;
            });
            return offer[0];
        }

        public final int getRecommendedPrice(int itemID, boolean fromBot) {
            int base = PriceIndex.INSTANCE.getValue(itemID);
            if (fromBot) base = (int) (Math.max(BotPrices.getPrice(itemID), base) * 1.10);
            return base;
        }

        public static int getRecommendedPrice$default(Companion c, int itemID, boolean fromBot, int mask, Object unused) {
            if ((mask & 2) != 0) fromBot = false;
            return c.getRecommendedPrice(itemID, fromBot);
        }

        public final String getOfferStats(int itemID, boolean sale) {
            StringBuilder sb = new StringBuilder();
            GEDB.run(conn -> {
                try {
                    int totalAmount = 0, bestPrice = 0;
                    Statement stmt = conn.createStatement();
                    if (!sale) {
                        int botAmt = 0, botPrice = 0;
                        ResultSet playerOffers = stmt.executeQuery("SELECT * from player_offers where item_id = " + itemID + " AND is_sale = 1 AND offer_state < 4 AND NOT offer_state = 2");
                        while (playerOffers.next()) {
                            GrandExchangeOffer o = GrandExchangeOffer.Companion.fromQuery(playerOffers);
                            totalAmount += o.getAmountLeft();
                            if (o.getOfferedValue() < bestPrice || bestPrice == 0) bestPrice = o.getOfferedValue();
                        }
                        stmt.close();
                        stmt = conn.createStatement();
                        ResultSet botOffers = stmt.executeQuery("SELECT * from bot_offers where item_id = " + itemID);
                        if (botOffers.next()) {
                            GrandExchangeOffer o = GrandExchangeOffer.Companion.fromBotQuery(botOffers);
                            botAmt = o.getAmount();
                            botPrice = getRecommendedPrice(itemID, true);
                        }
                        sb.append("Player Stock: <col=FFFFFF>").append(totalAmount).append("  ");
                        sb.append("</col>  Lowest Price: <col=FFFFFF>").append(bestPrice).append("<br>");
                        sb.append("-".repeat(50));
                        sb.append("</col><br>Bot Stock: <col=FFFFFF>").append(botAmt).append("  ");
                        sb.append("</col>  Bot Price: <col=FFFFFF>").append(botPrice);
                    } else {
                        ResultSet buyOffers = stmt.executeQuery("SELECT * from player_offers where item_id = " + itemID + " AND is_sale = 0 AND offer_state < 4 AND NOT offer_state = 2");
                        while (buyOffers.next()) {
                            GrandExchangeOffer o = GrandExchangeOffer.Companion.fromQuery(buyOffers);
                            totalAmount += o.getAmountLeft();
                            if (o.getOfferedValue() > bestPrice) bestPrice = o.getOfferedValue();
                        }
                        sb.append("Buy Offers: <col=FFFFFF>").append(totalAmount).append("    ");
                        sb.append("</col>Highest Offer: <col=FFFFFF>").append(bestPrice).append("</col>");
                    }
                    stmt.close();
                } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
                return Unit.INSTANCE;
            });
            return sb.toString();
        }

        public final boolean addBotOffer(int itemID, int amount) {
            if (!PriceIndex.canTrade(itemID)) return false;
            ItemDefinition itemDef = ItemDefinition.forId(itemID); // noted offers can not be bought
            GrandExchangeOffer offer = GrandExchangeOffer.Companion.createBotOffer(itemDef.isUnnoted() ? itemID : itemDef.getNoteId(), amount, true);
            pendingOffers.addLast(offer);
            return true;
        }

        public final boolean dispatch(Player player, GrandExchangeOffer offer) {
            if (offer.getAmount() < 1) { ContentAPIKt.sendMessage(player, "You must choose the quantity you wish to buy!"); return false; }
            if (offer.getOfferedValue() < 1) { ContentAPIKt.sendMessage(player, "You must choose the price you wish to buy for!"); return false; }
            if (offer.getOfferState() != OfferState.PENDING || offer.getUid() != 0L) {
                ContentAPIKt.log(GrandExchange.class, Log.WARN, "[GE] DISPATCH FAILURE: " + offer.getOfferState().name() + ", UID: " + offer.getUid());
                return false;
            }
            if (player.isArtificial()) { offer.setPlayerUID(PlayerDetails.getDetails("2009scape").getUid()); offer.setBot(true); }
            else offer.setPlayerUID(player.getDetails().getUid());
            offer.setOfferState(OfferState.REGISTERED);
            if (offer.getSell() && !player.isArtificial()) {
                Repository.sendGrandExchangeNews(player.getUsername() + " just offered " + offer.getAmount() + " " + ContentAPIKt.getItemName(offer.getItemID()) + " on the GE.", 12, "CC6600"); // the Kotlin defaults
            }
            if (ServerConstants.I_AM_A_CHEATER) {
                GrandExchangeOffer otherO = new GrandExchangeOffer();
                otherO.setItemID(offer.getItemID());
                otherO.setAmount(offer.getAmount());
                otherO.setSell(!offer.getSell());
                otherO.setOfferedValue(offer.getOfferedValue());
                offer.writeNew();
                GameWorld.getPulser().submit(new Pulse(5) {
                    @Override public boolean pulse() {
                        GrandExchangeOffer offer2 = getOfferByUid(offer.getUid());
                        if (offer2 == null) return false;
                        exchange(offer2, otherO);
                        return true;
                    }
                });
                return true;
            }
            pendingOffers.add(offer);
            return true;
        }

        public final void exchange(GrandExchangeOffer offer, GrandExchangeOffer other) {
            if (offer.getSell() == other.getSell()) return; // don't exchange if they are both buy/sell offers
            int amount = Math.min(offer.getAmount() - offer.getCompletedAmount(), other.getAmount() - other.getCompletedAmount());
            if (amount == 0) return;
            GrandExchangeOffer seller = offer.getSell() ? offer : other;
            GrandExchangeOffer buyer = offer == seller ? other : offer;
            boolean sellerBias = seller.getTimeStamp() > buyer.getTimeStamp();
            if (seller.getOfferedValue() > buyer.getOfferedValue()) return; // the buyer bids less than the seller asks
            seller.setCompletedAmount(seller.getCompletedAmount() + amount);
            buyer.setCompletedAmount(buyer.getCompletedAmount() + amount);
            seller.addWithdrawItem(995, amount * (sellerBias ? buyer.getOfferedValue() : seller.getOfferedValue()));
            buyer.addWithdrawItem(seller.getItemID(), amount);
            if (!sellerBias) buyer.addWithdrawItem(995, amount * (buyer.getOfferedValue() - seller.getOfferedValue()));
            if (seller.getAmountLeft() < 1) seller.setOfferState(OfferState.COMPLETED);
            if (buyer.getAmountLeft() < 1) buyer.setOfferState(OfferState.COMPLETED);
            int totalCoinXC = (sellerBias ? buyer.getOfferedValue() : seller.getOfferedValue()) * amount;
            seller.setTotalCoinExchange(seller.getTotalCoinExchange() + totalCoinXC);
            buyer.setTotalCoinExchange(buyer.getTotalCoinExchange() + totalCoinXC);
            if (canUpdatePriceIndex(seller, buyer)) PriceIndex.INSTANCE.addTrade(offer.getItemID(), amount, totalCoinXC / amount);
            for (GrandExchangeOffer entity : new GrandExchangeOffer[]{buyer, seller}) {
                entity.update();
                Player player = Repository.INSTANCE.getUid_map().get(entity.getPlayerUID());
                if (player == null) continue;
                GrandExchangeRecords records = GrandExchangeRecords.getInstance(player);
                records.visualizeRecords();
                records.setUpdateNotification(true);
            }
        }

        private boolean canUpdatePriceIndex(GrandExchangeOffer seller, GrandExchangeOffer buyer) {
            if (seller.getPlayerUID() == buyer.getPlayerUID()) return false;
            if (!ServerConstants.BOTS_INFLUENCE_PRICE_INDEX && (seller.isBot() || buyer.isBot())) return false;
            return true;
        }

        public final List<GrandExchangeOffer> getValidOffers() {
            List<GrandExchangeOffer> offers = new ArrayList<>();
            GEDB.run(conn -> {
                try {
                    Statement stmt = conn.createStatement();
                    ResultSet results = stmt.executeQuery("SELECT * FROM player_offers WHERE offer_state < 4 AND NOT offer_state = 2");
                    while (results.next()) offers.add(GrandExchangeOffer.Companion.fromQuery(results));
                    stmt.close();
                } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
                return Unit.INSTANCE;
            });
            return offers;
        }

        public final List<GrandExchangeOffer> getBotOffers() {
            List<GrandExchangeOffer> offers = new ArrayList<>();
            GEDB.run(conn -> {
                try {
                    Statement stmt = conn.createStatement();
                    ResultSet results = stmt.executeQuery("SELECT item_id,amount FROM bot_offers WHERE amount > 0");
                    while (results.next()) offers.add(GrandExchangeOffer.Companion.fromBotQuery(results));
                    stmt.close();
                } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
                return Unit.INSTANCE;
            });
            return offers;
        }

        public final int getBotstockForId(int itemId) {
            final int[] total = {0};
            GEDB.run(conn -> {
                try {
                    PreparedStatement stmt = conn.prepareStatement("SELECT sum(amount) FROM bot_offers WHERE amount > 0 AND item_id = ?");
                    stmt.setInt(1, itemId);
                    ResultSet results = stmt.executeQuery();
                    while (results.next()) total[0] += results.getInt(1);
                } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
                return Unit.INSTANCE;
            });
            return total[0];
        }
    }
}
