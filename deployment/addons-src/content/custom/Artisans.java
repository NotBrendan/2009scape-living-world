package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.bots.AIRepository;
import core.game.bots.GeneralBotCreator;
import core.game.bots.Script;
import core.game.bots.SkillingBotAssembler;
import core.game.ge.GrandExchange;
import core.game.ge.GrandExchangeOffer;
import core.game.ge.PriceIndex;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.update.flag.context.Animation;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Artisans: bots that turn surplus GE bot stock into the items nobody is selling.
 *
 * A "market brain" scores every recipe (from artisan-recipes.json, taken from the server's own
 * skill data) by how far its product's bot stock is below a value-based target, and only if the
 * inputs are actually in bot stock. The artisan then BUYS those inputs out of bot stock (a negative
 * bot offer on the GE worker queue, the same path the GE uses), walks to a real workplace, works
 * the batch with the right animation, and puts the products on the GE. Nothing appears from nowhere:
 * every bar, bow or potion is paid for with ore, logs or herbs that other bots gathered.
 */
public final class Artisans implements StartupListener {
    static final Random RNG = new Random();
    static final Timer TIMER = new Timer("Artisans", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");
    static final List<Recipe> RECIPES = new ArrayList<>();
    /** Bot stock we never dig below, so players can still buy the raw materials. */
    static final int KEEP_INPUT = 50;
    /** Products owed to real players who handed an artisan raw goods (player refining splits): product -> units still to make. */
    static final Map<Integer, Integer> OWED_PRODUCTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The recipe of this trade that turns the item into something, needing nothing else but shop staples; or null. */
    static Recipe refineRecipe(Trade t, int inputId) {
        if (t == null) return null;
        for (Recipe r : RECIPES) {
            if (!r.trade.equals(t.name()) || r.inIds == null) continue;
            boolean uses = false, others = true;
            for (int in : r.inIds) { if (in == inputId) uses = true; else if (!STAPLES.contains(in)) others = false; }
            if (uses && others) return r;
        }
        return null;
    }
    static final java.util.Set<Integer> STAPLES = new java.util.HashSet<>(java.util.Arrays.asList(227, 229, 1925, 1931, 1935, 1733, 1734, 1755, 2347)); // vial of water, vial, bucket, pot, jug, needle, thread, chisel, hammer

    static final class Recipe {
        String trade, name;
        int product, amount, level;
        int[] inIds, inAmts;
    }

    /** Where each trade works, and the animation it plays while working. */
    enum Trade {
        SMELTING("smelter", 3243, new Location[]{Location.create(3227, 3255, 0), Location.create(2973, 3370, 0), Location.create(3108, 3500, 0)},
                new String[]{"The furnace is roaring today.", "Nobody's been selling %s, so I'm smelting some.", "Ore in, bars out. Simple work."}),
        SMITHING("smith", 898, new Location[]{Location.create(3188, 3426, 0), Location.create(3229, 3437, 0)},
                new String[]{"Hammer and anvil, all day long.", "The GE has no %s. I'll forge a batch.", "Mind the sparks!"}),
        FLETCHING("fletcher", 1248, new Location[]{Location.create(2725, 3490, 0), Location.create(3208, 3228, 0)},
                new String[]{"Whittling away the afternoon.", "Not a single %s for sale. Fixing that.", "Straight grain makes straight shafts."}),
        COOKING("cook", 883, new Location[]{Location.create(2817, 3443, 0), Location.create(3211, 3215, 0)},
                new String[]{"Smells good, doesn't it?", "Folk need %s. Into the pan it goes.", "Don't burn it, don't burn it..."}),
        HERBLORE("herbalist", 363, new Location[]{Location.create(2898, 3428, 0)},
                new String[]{"A pinch of this, a vial of that.", "There's no %s on the market. Brewing.", "Careful, that one's still bubbling."}),
        CRAFTING("crafter", 1249, new Location[]{Location.create(3191, 3410, 0), Location.create(3277, 3191, 0)},
                new String[]{"Needle, thread and patience.", "Making %s; the shelves were empty.", "Fine work takes a steady hand."}),
        RUNECRAFTING("runecrafter", 791, new Location[]{Location.create(3253, 3420, 0)},
                new String[]{"Essence in, runes out.", "Mages have been asking for %s.", "Feel that hum? That's the talisman."});

        final String title;
        final int anim;
        final Location[] spots;
        final String[] lines;

        Trade(String title, int anim, Location[] spots, String[] lines) {
            this.title = title; this.anim = anim; this.spots = spots; this.lines = lines;
        }

        static Trade of(String s) {
            for (Trade t : values()) if (t.name().equalsIgnoreCase(s)) return t;
            return null;
        }
    }

    /** The artisan bots alive, for the bounty board (they pay the premiums). */
    static final List<AIPlayer> ARTISAN_BOTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static int skillOf(Trade t) {
        switch (t) {
            case SMITHING: case SMELTING: return core.game.node.entity.skill.Skills.SMITHING;
            case FLETCHING: return core.game.node.entity.skill.Skills.FLETCHING;
            case COOKING: return core.game.node.entity.skill.Skills.COOKING;
            case HERBLORE: return core.game.node.entity.skill.Skills.HERBLORE;
            case CRAFTING: return core.game.node.entity.skill.Skills.CRAFTING;
            case RUNECRAFTING: return core.game.node.entity.skill.Skills.RUNECRAFTING;
            default: return -1;
        }
    }

    /** A craftsman who opened shop knows the trade: once, a level of 60-85 if it's lower (saved XP keeps it after that). */
    static void seedTrade(AIPlayer bot, Trade t) {
        // the owner 2026-10-20: artisans are the exception to the levels rule: they get the level their hardest recipe
        // needs, so every product of the trade can be made. Real XP still comes from the work.
        int s = skillOf(t);
        if (s < 0) return;
        int need = 1;
        for (Recipe r : RECIPES) if (t.name().equals(r.trade)) need = Math.max(need, r.level);
        if (bot.getSkills().getStaticLevel(s) >= need) return;
        bot.getSkills().setStaticLevel(s, need); bot.getSkills().setLevel(s, need);
    }

    // ---------------------------------------------------------------- persistence: data/eco/artisans.json
    static final List<org.json.simple.JSONObject> SAVED_ARTISANS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static java.io.File artisanFile() { return new java.io.File(core.ServerConstants.GRAND_EXCHANGE_DATA_PATH, "artisans.json"); }

    static void loadSaved() {
        java.io.File f = artisanFile(); if (!f.exists()) return;
        org.json.simple.JSONArray arr;
        try (java.io.FileReader r = new java.io.FileReader(f)) { arr = (org.json.simple.JSONArray) new org.json.simple.parser.JSONParser().parse(r); }
        catch (Throwable t) { Ledger.quarantine(f, "Artisans", t, false, true); return; } // audit 10-23 O-1 (X-12): never saved over; fresh artisans this boot are not saved
        int bad = 0;
        for (Object o : arr) {
            if (o instanceof org.json.simple.JSONObject && ((org.json.simple.JSONObject) o).get("trade") != null && ((org.json.simple.JSONObject) o).get("name") != null && ((org.json.simple.JSONObject) o).get("bank") instanceof org.json.simple.JSONArray) SAVED_ARTISANS.add((org.json.simple.JSONObject) o);
            else { bad++; BAD_ARTISANS.add(o); System.out.println("[Artisans] an artisans.json entry could not be read, kept as it is (audit 10-23 O-1)"); }
        }
        if (bad > 0) Ledger.quarantine(f, "Artisans", bad + " unreadable entries", false, false);
    }
    /** audit 10-23 O-1: entries that failed to load, written back unchanged by every save. */
    static final List<Object> BAD_ARTISANS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static synchronized org.json.simple.JSONObject takeSaved(Trade trade) {
        for (org.json.simple.JSONObject j : SAVED_ARTISANS) if (trade.name().equals(j.get("trade"))) { SAVED_ARTISANS.remove(j); return j; }
        return null;
    }

    static void restoreArtisan(AIPlayer bot, org.json.simple.JSONObject j) {
        try {
            org.json.simple.JSONArray lv = (org.json.simple.JSONArray) j.get("levels"), xp = (org.json.simple.JSONArray) j.get("xp");
            if (lv != null) for (int i = 0; i < 24 && i < lv.size(); i++) { int l = ((Number) lv.get(i)).intValue(); if (l > 0) { bot.getSkills().setStaticLevel(i, l); bot.getSkills().setLevel(i, l); } }
            if (xp != null) { double[] x = new double[24]; for (int i = 0; i < 24 && i < xp.size(); i++) x[i] = ((Number) xp.get(i)).doubleValue(); SmartAdventurers.restoreXp(bot, x); }
            bot.getInventory().clear();
            for (Item it : bot.getBank().toArray()) if (it != null) bot.getBank().remove(it);
            for (Object o : (org.json.simple.JSONArray) j.get("bank")) { org.json.simple.JSONArray e = (org.json.simple.JSONArray) o; bot.getBank().add(new Item(((Number) e.get(0)).intValue(), ((Number) e.get(1)).intValue())); }
            if (j.get("pack") instanceof org.json.simple.JSONArray) for (Object o : (org.json.simple.JSONArray) j.get("pack")) { org.json.simple.JSONArray e = (org.json.simple.JSONArray) o; Item it = new Item(((Number) e.get(0)).intValue(), ((Number) e.get(1)).intValue()); if (!bot.getInventory().add(it)) bot.getBank().add(it); }
            long collected = Ledger.Wallet.collect(bot); // what sold while the server was down
            System.out.println("[Artisans] " + bot.getUsername() + " is back with " + Ledger.Wallet.coins(bot) + " gp and " + ((org.json.simple.JSONArray) j.get("bank")).size() + " bank stacks" + (collected > 0 ? " (collected " + collected + " gp)" : ""));
        } catch (Throwable t) { System.out.println("[Artisans] could not restore " + bot.getUsername() + ": " + t); try { Failures.Life.loginFailed("artisan", bot.getUsername(), t); } catch (Throwable ignored) { } } // batch 19c §8.1
    }

    @SuppressWarnings("unchecked")
    static synchronized void saveAll() {
        org.json.simple.JSONArray arr = new org.json.simple.JSONArray();
        for (AIPlayer b : ARTISAN_BOTS) {
            Trade t = tradeOf(b);
            if (b == null || t == null) continue;
            org.json.simple.JSONObject j = new org.json.simple.JSONObject();
            j.put("name", b.getUsername()); j.put("trade", t.name());
            org.json.simple.JSONArray lv = new org.json.simple.JSONArray(), xp = new org.json.simple.JSONArray();
            for (int i = 0; i < 24; i++) { lv.add(b.getSkills().getStaticLevel(i)); xp.add(Math.floor(b.getSkills().getExperience(i) * 10) / 10.0); }
            j.put("levels", lv); j.put("xp", xp);
            org.json.simple.JSONArray bank = new org.json.simple.JSONArray(), pack = new org.json.simple.JSONArray();
            for (Item it : b.getBank().toArray()) if (it != null) { org.json.simple.JSONArray e = new org.json.simple.JSONArray(); e.add(it.getId()); e.add(it.getAmount()); bank.add(e); }
            for (Item it : b.getInventory().toArray()) if (it != null) { org.json.simple.JSONArray e = new org.json.simple.JSONArray(); e.add(it.getId()); e.add(it.getAmount()); pack.add(e); }
            j.put("bank", bank); j.put("pack", pack);
            arr.add(j);
        }
        for (org.json.simple.JSONObject j : SAVED_ARTISANS) arr.add(j); // not spawned yet this boot: keep them
        arr.addAll(BAD_ARTISANS); // audit 10-23 O-1
        if (arr.isEmpty()) return;
        try { Ledger.writeAtomic(artisanFile(), arr.toJSONString()); } catch (java.io.IOException e) { System.out.println("[Artisans] could not save: " + e); try { Failures.Life.saveFailed(artisanFile().getName(), e); } catch (Throwable ignored) { } } // batch 19c §10
    }
    static final Map<String, Trade> TRADE_OF = new java.util.concurrent.ConcurrentHashMap<>();
    static Trade tradeOf(Player p) { return p == null ? null : TRADE_OF.get(p.getUsername().toLowerCase(java.util.Locale.ROOT)); }

    // Two of the busiest trades, one of the rest.
    static final Trade[] CREW = {Trade.SMITHING, Trade.SMITHING, Trade.SMELTING, Trade.SMELTING, Trade.FLETCHING, Trade.COOKING,
            Trade.HERBLORE, Trade.CRAFTING, Trade.RUNECRAFTING, Trade.COOKING, Trade.FLETCHING}; // audit E4: 14k raw fish and 20k logs needed a second cook and fletcher

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        try {
            loadRecipes();
        } catch (Throwable t) {
            System.out.println("[Artisans] could not load recipes: " + t);
            return;
        }
        int i = 0;
        for (Trade trade : CREW) {
            long delay = FAST ? 5_000 + i * 1_000 : 60_000 + i * 20_000L;
            i++;
            TIMER.schedule(new TimerTask() {
                @Override
                public void run() {
                    // Players must be created on the game thread, never from a Timer.
                    GameWorld.getPulser().submit(new core.game.system.task.Pulse(1) {
                        @Override
                        public boolean pulse() {
                            try {
                                Location home = trade.spots[RNG.nextInt(trade.spots.length)];
                                org.json.simple.JSONObject saved = takeSaved(trade);
                                AIPlayer bot = saved != null ? SmartAdventurers.namedBot(String.valueOf(saved.get("name")), home) : null;
                                boolean back = bot != null;
                                if (bot == null && saved == null) bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.AVERAGE, home);
                                if (!back && saved != null) { bot = new SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.AVERAGE, home); } // name couldn't be recreated: same craftsman under a new name
                                if (saved != null) { Ledger.rename(String.valueOf(saved.get("name")), bot.getUsername()); restoreArtisan(bot, saved); } // same craftsman, same bank and coins (2026-10-20)
                                else Ledger.Wallet.give(bot, 40_000 + RNG.nextInt(40_000)); // a new craftsman's float
                                new GeneralBotCreator(new Artisan(trade, home), bot);
                                ARTISAN_BOTS.add(bot);
                                SmartAdventurers.finishTutorial(bot); // or their crafting XP is capped at 175
                                seedTrade(bot, trade);
                                TRADE_OF.put(bot.getUsername().toLowerCase(java.util.Locale.ROOT), trade);
                                // Keep TradeModule from treating this bot as a DoublingMoney bot.
                                AIRepository.getPulseRepository().remove(bot.getUsername().toLowerCase());
                                System.out.println("[Artisans] " + bot.getUsername() + " opened shop as a " + trade.title);
                            } catch (Throwable t) {
                                System.out.println("[Artisans] spawn failed: " + t);
                                try { Failures.Life.loginFailed("artisan", null, t); } catch (Throwable ignored) { } // batch 19c §8.1
                            }
                            return true;
                        }
                    });
                }
            }, delay);
        }
        loadSaved();
        TIMER.schedule(new TimerTask() { public void run() { try { saveAll(); ServiceBots.saveAll(); } catch (Throwable t) { System.out.println("[Artisans] save failed: " + t); } } }, FAST ? 60_000 : 600_000, FAST ? 60_000 : 600_000);
        System.out.println("[Artisans] " + RECIPES.size() + " recipes, " + CREW.length + " artisans scheduled" + (SAVED_ARTISANS.isEmpty() ? "" : ", " + SAVED_ARTISANS.size() + " coming back"));
    }

    static void loadRecipes() throws Exception {
        InputStream in = Artisans.class.getResourceAsStream("artisan-recipes.json");
        if (in == null) throw new IllegalStateException("artisan-recipes.json missing");
        JSONArray arr = (JSONArray) new JSONParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            if (Trade.of((String) j.get("trade")) == null) continue;
            Recipe r = new Recipe();
            r.trade = ((String) j.get("trade")).toUpperCase();
            r.name = String.valueOf(j.get("productName"));
            r.product = ((Number) j.get("product")).intValue();
            r.amount = Math.max(1, ((Number) j.getOrDefault("amount", 1L)).intValue());
            r.level = ((Number) j.getOrDefault("level", 1L)).intValue();
            JSONArray ins = (JSONArray) j.get("inputs");
            if (ins == null || ins.isEmpty()) continue;
            r.inIds = new int[ins.size()];
            r.inAmts = new int[ins.size()];
            for (int k = 0; k < ins.size(); k++) {
                JSONObject in2 = (JSONObject) ins.get(k);
                r.inIds[k] = ((Number) in2.get("id")).intValue();
                r.inAmts[k] = Math.max(1, ((Number) in2.get("amount")).intValue());
            }
            RECIPES.add(r); // tradeability is checked when choosing; the price index isn't loaded yet at startup
            if (r.trade.equals("COOKING") && r.inIds.length == 1 && r.inAmts[0] == 1 && r.amount == 1)
                AfkTasks.Runner.COOK.put(r.inIds[0], new int[]{r.product, r.level});
        }
    }

    static boolean tradeable(int id) { return MarketBrain.tradeable(id); }

    static int value(int id) { return MarketBrain.value(id); }

    static int stock(int id) {
        try { return GrandExchange.Companion.getBotstockForId(id); } catch (Throwable t) { return 0; }
    }

    /** How much of an item the market should hold: plenty of cheap starter gear, a few pieces of rune. */
    /**
     * owner notes #2: only products something buys get the full stock target. Inputs of another recipe (bars,
     * unstrung bows, arrowtips), gear sold in shops or wanted as a goal, food, potions, runes, arrows, bows, leather and
     * dragonhide, or anything players have bought. Niche goods (tiaras, sickles, warhammers) stop at 10.
     */
    static final java.util.Set<Integer> INPUTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static boolean active(int id) {
        if (INPUTS.isEmpty()) for (Recipe r : RECIPES) if (r.inIds != null) for (int in : r.inIds) INPUTS.add(in);
        if (INPUTS.contains(id) || MarketBrain.DEMAND.getOrDefault(id, 0.0) > 0) return true;
        if (id >= 554 && id <= 566 || id >= 882 && id <= 892 || id >= 841 && id <= 861) return true; // runes, arrows, bows
        if (MarketBrain.SHOP_GEAR.contains(id)) return true;
        for (int k : new int[]{1129, 1095, 1063, 1167, 1169, 1135, 1099, 1065}) if (k == id) return true; // ranger kit
        try {
            core.cache.def.impl.ItemDefinition d = core.cache.def.impl.ItemDefinition.forId(id);
            if (d != null && (d.hasAction("eat") || d.hasAction("drink"))) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    static int target(int id) {
        int v = value(id);
        int base = v < 50 ? 150 : v < 500 ? 200 : v < 5_000 ? 100 : v < 40_000 ? 40 : 12; // cheap stuff capped lower: 1,800 bronze bars helped nobody
        if (!active(id)) base = Math.min(base, 10); // nobody buys it: make a few for the shelf, not 200
        return base + MarketBrain.demandBoost(id, base);
    }

    /** A planned batch: the recipe and how many times to make it. */
    /** Inputs that stopped a wanted recipe being planned in the last ten minutes: the gatherers weight these up. */
    static final Map<Integer, Integer> SHORT = new java.util.concurrent.ConcurrentHashMap<>();
    static long shortSince;

    /** batch 15 item4: potions hunters went to buy and found none of (id -> when): the herbalist puts these first, still only below target and from bought inputs. */
    static final Map<Integer, Long> POTION_WANTS = new java.util.concurrent.ConcurrentHashMap<>();
    static void wantPotion(int id) { POTION_WANTS.put(id, System.currentTimeMillis()); }

    static final class Plan {
        Recipe r;
        int times;
        double score;
    }

    /** The market brain: the recipe whose product is most needed and whose inputs are on hand. */
    /** Purchases queued on the GE worker but maybe not written yet, so two artisans don't buy the same ore twice. */
    static final Map<Integer, Integer> RESERVED = new HashMap<>();
    static long reservedAt;

    static synchronized Plan choose(Trade trade, int skillLevel) {
        if (System.currentTimeMillis() - reservedAt > 30_000) RESERVED.clear();
        // The market brain's stock map (refreshed every 5 minutes) is good enough to plan from; buyInputs checks the real pile.
        Map<Integer, Integer> stock = new HashMap<>(MarketBrain.stock);
        if (stock.isEmpty()) return null;
        for (Map.Entry<Integer, Integer> e : RESERVED.entrySet()) stock.merge(e.getKey(), -e.getValue(), Integer::sum);
        Plan best = null;
        if (System.currentTimeMillis() - shortSince > 600_000) { SHORT.clear(); shortSince = System.currentTimeMillis(); }
        for (Recipe r : RECIPES) {
            if (!r.trade.equals(trade.name()) || r.level > skillLevel || !tradeable(r.product)) continue;
            int have = stock.getOrDefault(r.product, 0);
            int want = target(r.product);
            boolean glut = false; for (int in : r.inIds) if (MarketBrain.isSurplus(in)) glut = true;
            if (glut && active(r.product)) want = Math.max(want, 2 * target(r.product)); // burn through a glut of inputs (consultant check Q1)
            int owedQ = OWED_PRODUCTS.getOrDefault(r.product, 0); // a player is waiting for these: make them whatever the market says
            if (have >= want && owedQ == 0) continue;
            if (owedQ > 0) want = Math.max(want, have + owedQ);
            // How many batches the inputs allow without digging under KEEP_INPUT.
            int times = Math.min(28, (int) Math.ceil((want - have) / (double) r.amount));
            for (int k = 0; k < r.inIds.length; k++) {
                int avail = stock.getOrDefault(r.inIds[k], 0) - KEEP_INPUT;
                times = Math.min(times, avail / r.inAmts[k]);
                if (avail < r.inAmts[k]) SHORT.merge(r.inIds[k], 1, Integer::sum); // this input is what the smiths are waiting on
            }
            if (times <= 0) continue;
            double gap = (want - have) / (double) want; // 1.0 = none at all
            double score = gap * gap * Math.log10(10 + value(r.product)) * (have == 0 ? 2 : 1) + RNG.nextDouble() * 0.2;
            if (owedQ > 0) score += 10; // the player's order first
            if (POTION_WANTS.getOrDefault(r.product, 0L) > System.currentTimeMillis() - 1_800_000) score += 8; // batch 15 item4: a hunter wanted these (ahead of the value weighting, behind a player's order)
            if (best == null || score > best.score) {
                best = new Plan();
                best.r = r; best.times = times; best.score = score;
            }
        }
        return best;
    }

    /** Takes the inputs out of bot stock through the GE worker queue (serialised with every other GE update). */
    /** Batch 22-1: a walk has gone on long enough for the paid teleport (90 s since walkStart, which the teleport resets). */
    static boolean teleDue(long now, long walkStart) { return now - walkStart > 90_000; }

    static Location nearestBank(Location from, Player who) {
        Location best = null; double bd = 220;
        for (Location b : AfkTasks.BANKS) { if (b.getX() == 3270 && from.getX() < 3268) continue; double d = b.getDistance(from); if (d < bd && Watchdog.bankReachable(from, b, who, true)) { bd = d; best = b; } } // batch 22-1: gates and sea too
        return best;
    }

    static synchronized boolean buyInputs(Plan plan, Player who) {
        reservedAt = System.currentTimeMillis();
        // Inputs the artisan already holds in its bank (bought at the bank or handed over for a split) come first.
        int[] fromBank = new int[plan.r.inIds.length], need = new int[plan.r.inIds.length];
        long cost = 0;
        for (int k = 0; k < plan.r.inIds.length; k++) {
            int total = plan.r.inAmts[k] * plan.times;
            int inBank = 0; try { inBank = who.getBank().getAmount(plan.r.inIds[k]); } catch (Throwable ignored) { }
            fromBank[k] = Math.min(total, inBank);
            need[k] = total - fromBank[k];
            cost += (long) MarketBrain.value(plan.r.inIds[k]) * need[k];
        }
        if (Ledger.Wallet.coins(who) < cost) return false; // can't afford the batch: wait for sales
        // Check every input against the real pile first, then take; never let a row go negative.
        int[] shop = new int[plan.r.inIds.length]; // staples bought from a shop when the Exchange is short (vials of water, pots, buckets): coins leave the game (D)
        for (int k = 0; k < plan.r.inIds.length; k++) {
            if (need[k] <= 0) continue;
            int have;
            try { have = GrandExchange.Companion.getBotstockForId(plan.r.inIds[k]); } catch (Throwable t) { return false; }
            if (have - KEEP_INPUT < need[k]) {
                if (!STAPLES.contains(plan.r.inIds[k])) return false;
                shop[k] = need[k] - Math.max(0, have - KEEP_INPUT);
                if (Ledger.Wallet.coins(who) < cost + (long) Math.max(1, MarketBrain.value(plan.r.inIds[k])) * shop[k]) return false;
            }
        }
        int[] got = new int[plan.r.inIds.length]; // audit E-16: what this batch already took, handed back if a later input fails
        for (int k = 0; k < plan.r.inIds.length; k++) {
            if (fromBank[k] > 0 && who.getBank().remove(new Item(plan.r.inIds[k], fromBank[k]))) got[k] += fromBank[k];
            if (shop[k] > 0) { if (!Ledger.Wallet.pay(who, (long) Math.max(1, MarketBrain.value(plan.r.inIds[k])) * shop[k])) { giveBack(plan, who, got, "couldn't get every input for"); return false; } got[k] += shop[k]; need[k] -= shop[k]; }
            if (need[k] <= 0) continue;
            RESERVED.merge(plan.r.inIds[k], need[k], Integer::sum);
            int took = Ledger.consume(who, plan.r.inIds[k], need[k], "craft");
            got[k] += Math.max(0, took);
            if (took < need[k]) { giveBack(plan, who, got, "couldn't get every input for"); return false; }
        }
        return true;
    }

    /** Audit E-16: inputs already taken (from its bank, a shop or paid for out of stock) go into the artisan's bank, used first next time. */
    static void giveBack(Plan plan, Player who, int[] got, String why) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < got.length; k++) if (got[k] > 0) {
            if (!who.getBank().add(new Item(plan.r.inIds[k], got[k]))) Ledger.Wallet.give(who, (long) Math.max(1, MarketBrain.value(plan.r.inIds[k])) * got[k]);
            sb.append(sb.length() > 0 ? ", " : "").append(got[k]).append(" x ").append(new Item(plan.r.inIds[k]).getName());
        }
        if (sb.length() > 0) System.out.println("[Artisans] " + who.getUsername() + " " + why + " " + plan.r.name + "; kept " + sb + " in the bank");
    }

    static final List<Artisan> SCRIPTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Audit E-17: server going down mid-batch: the inputs it bought go back in its bank (saved), instead of being lost. */
    static void settleForShutdown() {
        for (Artisan a : SCRIPTS) {
            try {
                if (a.bot == null || a.plan == null || (a.step != Step.WALK && a.step != Step.WORK)) continue;
                int[] got = new int[a.plan.r.inIds.length];
                for (int k = 0; k < got.length; k++) got[k] = a.plan.r.inAmts[k] * a.plan.times;
                giveBack(a.plan, a.bot, got, "stopped mid-batch (shutdown) of");
                a.plan = null; a.step = Step.CHOOSE;
            } catch (Throwable t) { System.out.println("[Artisans] shutdown batch: " + t); }
        }
    }

    static <T> T pick(T[] a) {
        return a[RNG.nextInt(a.length)];
    }

    enum Step { CHOOSE, WALK, WORK, IDLE, BANK }
    static final java.util.concurrent.ExecutorService PLANNER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Artisans-planner"); t.setDaemon(true); return t; });

    static final class Artisan extends Script {
        final Trade trade;
        Location home;
        Location bankAt; int[] waitingFor; boolean bankSaid; long nextBankTrip; // audit E3
        Step step = Step.CHOOSE;
        Plan plan;
        java.util.concurrent.CompletableFuture<Plan> planning;
        long until, nextChat = System.currentTimeMillis() + 30_000, walkStart;
        int made;

        Artisan(Trade trade, Location home) {
            this.trade = trade;
            this.home = home;
            SCRIPTS.add(this); // audit E-17
            for (int s : new int[]{0, 1, 2, 3, 4, 6}) skills.put(s, 80); // monsters leave them alone
        }

        @Override
        public Script newInstance() {
            return null;
        }

        void say(String s) {
            BotChat.say(bot, s);
        }

        @Override
        public void tick() {
            if (bot == null || !bot.isActive()) return;
            long now = System.currentTimeMillis();
            switch (step) {
                case CHOOSE: {
                    // Scoring 470 recipes is done on a worker thread so the game tick never waits for it.
                    if (planning == null) {
                        final java.util.concurrent.CompletableFuture<Plan> f = new java.util.concurrent.CompletableFuture<>();
                        planning = f;
                        final int lvl = skillOf(trade) >= 0 ? bot.getSkills().getStaticLevel(skillOf(trade)) : 99; // its real level (2026-10-20)
                        PLANNER.execute(() -> { try { f.complete(choose(trade, lvl)); } catch (Throwable t) { f.complete(null); } });
                        return;
                    }
                    if (!planning.isDone()) return;
                    plan = planning.getNow(null);
                    planning = null;
                    if (plan == null || !buyInputs(plan, bot)) {
                        // Nothing to buy on the Exchange: stand at the nearest bank for a while, where gatherers arrive with
                        // hauls (splits and callouts find an artisan within 15 tiles), then try the Exchange again (audit E3).
                        Location bank = nearestBank(bot.getLocation(), bot);
                        if (plan != null && bank != null && now > nextBankTrip) {
                            for (int in : plan.r.inIds) SHORT.merge(in, 1, Integer::sum); // the gatherers hear what the smiths are waiting on
                            waitingFor = plan.r.inIds; bankAt = bank; bankSaid = false;
                            step = Step.BANK; until = now + (FAST ? 60_000 : 8 * 60_000); nextBankTrip = now + 60 * 60_000L; walkStart = now; // (review 10-22)
                            BotChat.activity(bot, () -> "waiting at the bank for " + new Item(waitingFor[0]).getName().toLowerCase());
                            return;
                        }
                        step = Step.IDLE;
                        until = now + (FAST ? 20_000 : 120_000 + RNG.nextInt(120_000));
                        return;
                    }
                    StringBuilder bought = new StringBuilder();
                    for (int k = 0; k < plan.r.inIds.length; k++) {
                        if (bought.length() > 0) bought.append(", ");
                        bought.append(plan.r.inAmts[k] * plan.times).append(" x ").append(new Item(plan.r.inIds[k]).getName());
                    }
                    System.out.println("[Artisans] " + bot.getUsername() + " bought " + bought + " to make " + plan.r.amount * plan.times + " x " + plan.r.name);
                    home = trade.spots[RNG.nextInt(trade.spots.length)];
                    walkStart = now;
                    step = Step.WALK;
                    break;
                }
                case WALK: {
                    if (bot.getLocation().getDistance(home) <= 4) {
                        step = Step.WORK;
                        until = now + (FAST ? 10_000 : Math.min(240_000, 3_000L * plan.times + 20_000));
                        final Plan working = plan;
                        BotChat.activity(bot, () -> "making " + working.r.name.toLowerCase() + ", being the local " + trade.title.toLowerCase());
                        if (RNG.nextInt(3) > 0) say(String.format(pick(trade.lines), plan.r.name.toLowerCase()));
                        return;
                    }
                    // Walk; if the road is blocked for long, take the shortcut a real player would (teleport).
                    if (teleDue(now, walkStart) && Ledger.Wallet.payTeleport(bot, "artisan-home")) { try { RouteMap.stuck(bot, home); } catch (Throwable ignored) { } Spectacle.teleport(bot, home); walkStart = now; break; } // batch 22-1: walkStart reset, one paid teleport per 90 s of walking, not one per tick; // the map learns the blocked way, then the paid teleport (audit #16); batch 15 item1
                    if (now - walkStart > 300_000) { Spectacle.teleport(bot, home); walkStart = now; break; } // broke and five minutes out: home anyway (there was no timeout at all, review 10-22); batch 15 item1
                    Location hop = null; if (NavWalker.ON) { try { hop = NavWalker.step(bot, home); } catch (Throwable ignored) { } }
                    if (hop == null) try { hop = RouteMap.nextHop(bot, home); } catch (Throwable ignored) { }
                    if (RouteMap.usingShortcut(bot)) break; // pathing review 10-23 R-2: the route's shortcut is being used; walkTo (a new movement pulse) replaced its interaction
                    scriptAPI.walkTo(hop != null ? hop : home);
                    break;
                }
                case WORK: {
                    bot.animate(new Animation(trade.anim));
                    if (now < until) return;
                    int forSale = Deals.settleOwed(bot, plan.r.product, plan.r.amount * plan.times); // splits first
                    if (forSale > 0) Ledger.supply(bot, plan.r.product, forSale);
                    made += plan.r.amount * plan.times;
                    if (skillOf(trade) >= 0) try { bot.getSkills().addExperience(skillOf(trade), (10.0 + plan.r.level) * plan.times, true); } catch (Throwable ignored) { } // XP like anyone doing the work
                    System.out.println("[Artisans] " + bot.getUsername() + " put " + plan.r.amount * plan.times + " x " + plan.r.name + " on the GE");
                    if (plan.r.amount * plan.times >= 20) BotChat.news(String.format(BotChat.pick(
                            "%1$s has been hammering out %2$s all morning. The whole street smells of it.",
                            "Need %2$s? %1$s makes them. Decent work, fair price.",
                            "%1$s's workshop was busy today. %2$s, dozens of them, straight to the clerks."),
                            bot.getUsername(), plan.r.name.toLowerCase()));
                    if (RNG.nextInt(4) == 0) say("Fresh " + plan.r.name.toLowerCase() + " on the Grand Exchange!");
                    step = Step.CHOOSE;
                    break;
                }
                case BANK: {
                    double bd = bot.getLocation().getDistance(bankAt);
                    if (bd > 4) {
                        if (teleDue(now, walkStart) && Ledger.Wallet.payTeleport(bot, "artisan-bank")) { try { RouteMap.stuck(bot, bankAt); } catch (Throwable ignored) { } Spectacle.teleport(bot, bankAt); walkStart = now; } // batch 22-1: walkStart reset (it repaid every tick when the landing was over 4 tiles from bankAt); // batch 15 item1
                        else if (!bot.getWalkingQueue().isMoving()) { Location hop = null; if (NavWalker.ON) { try { hop = NavWalker.step(bot, bankAt); } catch (Throwable ignored) { } } if (hop == null) try { hop = RouteMap.nextHop(bot, bankAt); } catch (Throwable ignored) { } if (!RouteMap.usingShortcut(bot)) scriptAPI.walkTo(hop != null ? hop : bankAt); } // pathing review 10-23 R-2: not while the route's shortcut is being used (walkTo, a new movement pulse, replaced its interaction)
                        if (bd > 4 && now < until - 120_000) return;
                    }
                    if (!bankSaid) {
                        bankSaid = true; walkStart = now;
                        String want = new Item(waitingFor[0]).getName().toLowerCase();
                        say(pick(new String[]{"Buying " + want + " here at the bank. Better than the Exchange.", "Anyone with " + want + "? I'm buying, right here.", "Short of " + want + ". Trade me at the bank, I pay guide."}));
                    }
                    if (now < until) { if (RNG.nextInt(8) == 0) scriptAPI.randomWalkTo(bankAt, 3); return; }
                    step = Step.CHOOSE; // back to the Exchange, then home
                    break;
                }
                case IDLE: {
                    if (now > nextChat) {
                        say(pick(new String[]{"Quiet day. The market has all it needs.", "Waiting on materials...",
                                "If anyone's selling ore or logs, I'm buying.", pick(trade.lines).replace("%s", "goods")}));
                        nextChat = now + 90_000 + RNG.nextInt(90_000);
                    }
                    if (bot.getLocation().getDistance(home) > 20) scriptAPI.teleport(home);
                    else if (RNG.nextInt(6) == 0) scriptAPI.randomWalkTo(home, 4);
                    if (now > until) step = Step.CHOOSE;
                    break;
                }
            }
        }
    }
}
