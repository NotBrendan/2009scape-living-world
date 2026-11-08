package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.command.Command;
import core.game.system.command.CommandMapping;
import core.game.system.command.CommandPlugin;
import core.game.system.command.CommandSet;
import core.game.system.command.Privilege;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.repository.Repository;
import kotlin.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bot buy orders and the bounty board (plan C19/C21).
 *
 * Bots cannot hold Grand Exchange offers of their own (the server routes an artificial player's offer into the
 * shared bot stock), so a "buy order" is modelled here instead: for every item the market is short of that some
 * bot would use, the bots pay a premium on top of the guide price whenever a player's GE sale of it completes.
 * The premium is paid from the wallets of the artisans who want the item (nothing is created), credited to the
 * player's bank, and announced. `::market` lists the current board; the Varrock local calls it out hourly.
 */
public final class Bounty implements StartupListener {
    static final int PREMIUM_PERCENT = 25;
    static final int BOARD_SIZE = 8;
    /** Only everyday gatherables go on the board (no onyx bounties), and the artisans never bleed out paying them. */
    static final int MAX_GUIDE = 25_000, MAX_PER_SALE = 50_000;
    static final long DAILY_CAP = 500_000;
    static volatile long paidToday; static volatile String paidDay = Ledger.utcDay(); // audit 10-23 batch14 item3: the UTC day, kept in ledger.json (was 24 h from boot, zero after every restart)

    /** Items the artisans' recipes consume, so a "wanted" item has a real buyer. */
    static final Map<Integer, String> INPUT_USERS = new HashMap<>();

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        CommandSet.PLAYER.getPlugins().add(new MarketCommand());
        MarketCommand modern = new MarketCommand();
        CommandMapping.INSTANCE.register(new Command("market", Privilege.STANDARD, "", "", (player, args) -> {
            modern.parse(player, "market", args);
            return Unit.INSTANCE;
        }));
        System.out.println("[Bounty] ::market ready; bots pay " + PREMIUM_PERCENT + "% over guide for wanted items");
        if (Boolean.getBoolean("bounty.test")) new java.util.Timer("bounty-test", true).schedule(new java.util.TimerTask() {
            @Override public void run() {
                try {
                    for (Object[] row : board()) System.out.println("[Bounty-test] board: " + row[1] + " guide " + row[2] + " premium " + row[3] + " stock " + row[4]);
                    String[] c = crierLines();
                    System.out.println("[Bounty-test] crier: " + (c == null ? "(empty board)" : c[0] + " / " + c[1]));
                    System.out.println("[Bounty-test] artisan funds: " + artisanFunds() + " across " + Artisans.ARTISAN_BOTS.size() + " artisans");
                    List<Object[]> b = board();
                    if (!b.isEmpty() && !Artisans.ARTISAN_BOTS.isEmpty()) {
                        Player fake = Artisans.ARTISAN_BOTS.get(0);
                        long before = Ledger.Wallet.coins(fake);
                        playerSold(fake, (int) b.get(0)[0], 10);
                        System.out.println("[Bounty-test] simulated sale of 10 x " + b.get(0)[1] + ": payer/receiver " + fake.getUsername() + " wallet " + before + " -> " + Ledger.Wallet.coins(fake) + " (receives own share back; others' shares are the gain)");
                    }
                } catch (Throwable t) { System.out.println("[Bounty-test] failed: " + t); t.printStackTrace(); }
            }
        }, 150_000);
    }

    static void indexInputs() {
        if (!INPUT_USERS.isEmpty()) return;
        for (Artisans.Recipe r : Artisans.RECIPES) for (int id : r.inIds) INPUT_USERS.putIfAbsent(id, r.trade);
    }

    /** The board: the most wanted items that some bot would actually buy, with the premium price. */
    public static List<Object[]> board() {
        indexInputs();
        List<Object[]> out = new ArrayList<>();
        for (MarketBrain.Entry e : MarketBrain.topBounties(60)) {
            if (!SmartAdventurers.gatherable(e.id)) continue; // something a player can go and gather or hunt for, not a finished good
            boolean used = INPUT_USERS.containsKey(e.id) || SmartAdventurers.capable(e.id);
            if (!used) continue;
            int guide = MarketBrain.value(e.id);
            if (guide <= 0 || guide > MAX_GUIDE) continue;
            out.add(new Object[]{e.id, MarketBrain.name(e.id), guide, guide + guide * PREMIUM_PERCENT / 100, MarketBrain.stockOf(e.id)});
            if (out.size() >= BOARD_SIZE) break;
        }
        return out;
    }

    static boolean wanted(int id) {
        for (Object[] row : board()) if ((int) row[0] == id) return true;
        return false;
    }

    /**
     * batch 17 item2 (audit X-6): how many of a player's {@code qty} sold units the premium skips: their completed GE buys of the item
     * (player_offers, is_sale = 0, offers placed in the last 48 h; bot stock and other players alike) less the units already
     * exempted in that window (kept in memory: a restart forgets them, which only makes it stricter). Deals and traders keep no
     * per-player record and never sell a board item. Any failure: 0 (the premium as before).
     */
    static final long EXEMPT_WINDOW = 48L * 3_600_000;
    static final Map<String, java.util.Deque<long[]>> EXEMPTED = new java.util.concurrent.ConcurrentHashMap<>(); // "uid:item" -> {when, units}
    /**
     * P38 L8 (audit X-6, Deals/traders part), flag -Dbounty.exemptDeals (default off = nothing recorded, exempt() as before):
     * units a player bought from a bot at the bank (Deals) or from a travelling trader, kept like EXEMPTED ("uid:item" ->
     * {when, units}, in memory, 48 h). exempt() adds them to the GE buys, so the premium skips them too. Volatile, not final,
     * so TestP38Bounty can switch it within one run.
     */
    static volatile boolean exemptDeals = Boolean.getBoolean("bounty.exemptDeals");
    static volatile boolean failGeForTest; // TestP38Bounty case E only: the GE lookup throws
    static final Map<String, java.util.Deque<long[]>> BOT_BUYS = new java.util.concurrent.ConcurrentHashMap<>();
    public static void boughtFromBots(Player player, int id, int n) {
        if (!exemptDeals || player == null || n <= 0 || player.isArtificial()) return;
        try {
            java.util.Deque<long[]> d = BOT_BUYS.computeIfAbsent(player.getDetails().getUid() + ":" + id, k -> new java.util.ArrayDeque<>());
            synchronized (d) { d.addLast(new long[]{System.currentTimeMillis(), n}); }
        } catch (Throwable ignored) { }
    }
    static long botBought(int uid, int id, long since) {
        java.util.Deque<long[]> d = BOT_BUYS.get(uid + ":" + id);
        if (d == null) return 0;
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst()[0] < since) d.pollFirst();
            long s = 0; for (long[] u : d) s += u[1];
            return s;
        }
    }
    static int exempt(Player player, int id, int qty) { return exempt(player, id, qty, null); }
    /** fromBots (may be null): set to the units bought from bots in the window, for the log line. */
    static int exempt(Player player, int id, int qty, long[] fromBots) {
        try {
            int uid = player.getDetails().getUid(); long since = System.currentTimeMillis() - EXEMPT_WINDOW;
            long[] bought = {0};
            try {
                if (failGeForTest) throw new IllegalStateException("test: GE lookup fails");
                core.game.ge.GEDB.run(conn -> {
                    try (java.sql.PreparedStatement st = conn.prepareStatement("SELECT SUM(amount_complete) FROM player_offers WHERE player_uid = ? AND item_id = ? AND is_sale = 0 AND amount_complete > 0 AND time_stamp > ?")) {
                        st.setInt(1, uid); st.setInt(2, id); st.setLong(3, since);
                        java.sql.ResultSet rs = st.executeQuery(); if (rs.next()) bought[0] = rs.getLong(1);
                    } catch (Throwable t) { System.out.println("[Bounty] player purchases: " + t); }
                    return Unit.INSTANCE;
                });
            } catch (Throwable t) { bought[0] = 0; } // GE part fails: 0 for it (as before); the bot-buys part below still counts
            if (exemptDeals) { long b = botBought(uid, id, since); if (fromBots != null) fromBots[0] = b; bought[0] += b; }
            if (bought[0] <= 0) return 0;
            java.util.Deque<long[]> used = EXEMPTED.computeIfAbsent(uid + ":" + id, k -> new java.util.ArrayDeque<>());
            synchronized (used) {
                while (!used.isEmpty() && used.peekFirst()[0] < since) used.pollFirst();
                long done = 0; for (long[] u : used) done += u[1];
                int n = (int) Math.min(qty, Math.max(0, bought[0] - done));
                if (n > 0) used.addLast(new long[]{System.currentTimeMillis(), n});
                return n;
            }
        } catch (Throwable t) { return 0; }
    }

    /** What bots pay for a player's GE sale (morning review 10-23): ordinary items up to 90% of the reference, wanted items up to 100% (plus the premium). */
    public static final int BUYER_PERCENT = 90, BUYER_WANTED_PERCENT = 100;
    private static volatile java.util.Set<Integer> wantedIds = java.util.Collections.emptySet();
    private static volatile long wantedAt;

    /**
     * Is the item on the board now (cached for a minute)? While it is, bots sell it through no channel (Deals, travelling
     * traders, GE bot stock): a player could otherwise buy it from bot stock at guide and sell it back at guide + 25%.
     * Any thread may ask (the GE worker does); only the game thread rebuilds the list.
     */
    public static boolean wantedNow(int id) {
        if (System.currentTimeMillis() - wantedAt > 60_000 && !"GE Update Worker".equals(Thread.currentThread().getName())) {
            try { java.util.Set<Integer> s = new java.util.HashSet<>(); for (Object[] row : board()) s.add((int) row[0]); wantedIds = s; } catch (Throwable ignored) { }
            wantedAt = System.currentTimeMillis();
        }
        return wantedIds.contains(id);
    }

    /**
     * A player's sale of a wanted item completed on the GE: the bots that want it pay the premium between them,
     * into the player's bank. Called from the market brain with per-player sale deltas.
     */
    public static void playerSold(Player player, int id, int qty) {
        if (player == null || qty <= 0 || !wanted(id)) return;
        long[] fromBots = {0};
        int bought = exempt(player, id, qty, fromBots); // batch 17 item2 (audit X-6): no premium on what this player bought on the Exchange in the last 48 h
        if (bought > 0) System.out.println("[Bounty] " + player.getUsername() + " sold " + qty + " x " + MarketBrain.name(id) + "; " + bought + " of them bought on the Exchange in the last 48 h, no premium on those"
                + (fromBots[0] > 0 ? " (" + Math.min(bought, fromBots[0]) + " bought from bots)" : "")); // P38 L8: only with -Dbounty.exemptDeals
        if (bought >= qty) return;
        long premium = (long) MarketBrain.value(id) * (qty - bought) * PREMIUM_PERCENT / 100;
        String today = Ledger.utcDay(); if (!today.equals(paidDay)) { paidDay = today; paidToday = 0; } // audit 10-23 batch14 item3
        premium = Math.min(premium, Math.min(MAX_PER_SALE, Math.min(artisanFunds() / 20, DAILY_CAP - paidToday))); // 5% of the pool per sale, 500k a day
        if (premium <= 0) return;
        List<Player> payers = new ArrayList<>();
        for (Player b : Artisans.ARTISAN_BOTS) if (b != null && b.isActive() && Ledger.Wallet.coins(b) > 1_000) payers.add(b);
        if (payers.isEmpty()) return;
        long paid = 0, share = (premium + payers.size() - 1) / payers.size();
        for (Player b : payers) {
            long give = Math.min(share, Math.min(premium - paid, Ledger.Wallet.coins(b) - 1_000));
            if (give <= 0) continue;
            if (Ledger.Wallet.pay(b, give)) paid += give;
            if (paid >= premium) break;
        }
        if (paid <= 0) return;
        paidToday += paid;
        try {
            if (!player.getBank().add(new Item(995, (int) Math.min(Integer.MAX_VALUE, paid)))) player.getInventory().add(new Item(995, (int) paid));
        } catch (Throwable t) { return; }
        String nm = MarketBrain.name(id).toLowerCase(Locale.ROOT);
        player.sendMessage("<col=0000ff>The bots paid you a " + String.format("%,d", paid) + " gp bounty on top for " + qty + " " + nm + ". It's in your bank.");
        System.out.println("[Bounty] " + player.getUsername() + " sold " + qty + " x " + nm + "; bots paid a " + paid + " gp premium");
        try { // whoever's closest says thanks to your face
            Player near = null; double best = 15;
            for (Player b : Repository.getPlayers()) { if (b == null || !b.isArtificial() || !b.isActive()) continue; double d = b.getLocation().getDistance(player.getLocation()); if (d < best) { best = d; near = b; } }
            if (near != null) BotChat.speak(near, player, java.util.Arrays.asList(String.format(BotChat.pick("Cheers for the %2$s, %1$s. We've been waiting on those.", "%1$s! The %2$s. You're a lifesaver.", "That's the %2$s sorted then. Ta, %1$s."), player.getUsername(), nm)), 2);
        } catch (Throwable ignored) { }
        BotChat.news(String.format(BotChat.pick("%s just sold %s to the Exchange and the crafters chipped in a bounty. Nice work.", "Somebody finally brought %2$s in. %1$s, if you're wondering who to thank."), player.getUsername(), nm));
    }

    /** Two lines for the town crier, or null when the board is empty. */
    public static String[] crierLines() {
        List<Object[]> b = board();
        if (b.isEmpty()) return null;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(4, b.size()); i++) { if (names.length() > 0) names.append(i == Math.min(4, b.size()) - 1 ? " and " : ", "); names.append(((String) b.get(i)[1]).toLowerCase(Locale.ROOT)); }
        return new String[]{"Hear ye! The Exchange is short of " + names + "!", "Bots pay a quarter over the guide price for them. Type ::market for the list."};
    }

    static final class MarketCommand extends CommandPlugin {
        @Override
        public core.plugin.Plugin<Object> newInstance(Object arg) { return this; }

        @Override
        public boolean parse(Player player, String name, String[] args) {
            if (name == null || !name.equalsIgnoreCase("market")) return false;
            try {
                List<Object[]> b = board();
                if (b.isEmpty()) player.sendMessage("<col=0000ff>The bots have everything they need right now. Check back later.");
                else {
                    player.sendMessage("<col=0000ff>Bounty board: sell these on the Grand Exchange at up to the price shown and the bots buy them and add " + PREMIUM_PERCENT + "% of guide on top (paid to your bank). Other items: bots buy at up to " + BUYER_PERCENT + "% of guide.");
                    for (Object[] row : b) { int list = Math.max(1, MarketBrain.botRef((int) row[0]) * BUYER_WANTED_PERCENT / 100); player.sendMessage("<col=0b5e1f>" + row[1] + ": list at up to " + String.format("%,d", list) + " gp each, bounty +" + String.format("%,d", (int) row[3] - (int) row[2]) + " gp each (guide " + String.format("%,d", (int) row[2]) + "), " + row[4] + " in stock"); }
                    player.sendMessage("<col=0000ff>Bots have " + String.format("%,d", artisanFunds()) + " gp between them for bounties.");
                }
                int shown = 0;
                for (Deals.Offer o : Deals.OFFERS) { // deals you could walk to right now
                    if (o.done || o.seller == null || !o.seller.isActive()) continue;
                    String where = BotChat.WorldData.near(o.seller.getLocation().getX(), o.seller.getLocation().getY()).replace(", near ", "");
                    player.sendMessage("<col=0b5e1f>Selling at the bank" + (where.isEmpty() ? "" : " in " + where) + ": " + o.seller.getUsername() + ", " + o.qty + " " + MarketBrain.name(o.id).toLowerCase(java.util.Locale.ROOT) + " at " + o.unit + " each (trade them)."); shown++;
                }
                for (TravellingTraders.Trader t : TravellingTraders.ALL) {
                    if (t.bot == null || !t.bot.isActive()) continue;
                    String where = BotChat.WorldData.near(t.bot.getLocation().getX(), t.bot.getLocation().getY()).replace(", near ", "");
                    player.sendMessage("<col=0b5e1f>" + t.bot.getUsername() + " the " + t.kind.title + " is " + (where.isEmpty() ? "on the road" : "near " + where) + (t.phase == TravellingTraders.Phase.GATHER ? ", gathering" : t.phase == TravellingTraders.Phase.SEEK ? ", looking for a buyer" : ", selling") + "."); shown++;
                }
                if (shown == 0) player.sendMessage("<col=0b5e1f>No bank callouts or traders about right now.");
            } catch (Throwable t) {
                player.sendMessage("<col=ff0000>Market: something went wrong (" + t.getClass().getSimpleName() + ").");
            }
            return true;
        }
    }

    static long artisanFunds() {
        long total = 0;
        for (Player b : Artisans.ARTISAN_BOTS) if (b != null && b.isActive()) total += Math.max(0, Ledger.Wallet.coins(b) - 1_000);
        return total;
    }
}
