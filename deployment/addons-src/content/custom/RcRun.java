package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import core.game.world.update.flag.context.Animation;
import core.game.world.update.flag.context.Graphics;

/**
 * W6 Runecrafting (the design notes section 3, wave 3): -Dartisans.altarLoop, default OFF.
 *
 * One real altar loop for the runecrafter artisan. Off, the runecrafter does what train-61 does: it buys essence on the
 * Exchange, walks to its workshop (Varrock east bank) and "crafts" there. On, when the planner picked a rune whose altar is
 * in the route table (and listed in -Dartisans.altarLoop.altars, default "earth"): bank (fetch the talisman), walk to the
 * mysterious ruins, enter them (talisman carried or tiara worn, the ruins gate in Gates.java), walk to the engine's altar
 * object, craft, leave by the portal and walk back to the bank. The essence and the runes are the same as on the old path
 * (one rune per essence, the recipe's amount: the engine's level multiplier is NOT applied, so rune supply per essence is
 * unchanged); only where it happens and the XP (the engine's per-essence value) differ.
 *
 * Fallback: any stall before the craft (no key, a gate, no altar object, a walk leg over its time limit or 30 s without
 * moving) hands the batch back to the old workshop path at once, so every essence bought still becomes runes. Two stalls
 * at one altar inside an hour park that altar for an hour (old path only). A stall on the walk back costs nothing (the
 * runes were sold at the altar).
 *
 * Routes checked offline with an offline route checker (not included) on nav-p0.bin (2026-11-19), bank -> ruins / ruins -> bank, tiles:
 *   earth  Varrock East bank 3253,3420 <-> ruins 3304,3475   122 / 130, no doors  (the runecrafter's own workshop bank: shortest)
 *   air    Falador East bank 3012,3355 <-> ruins 2983,3292   108 / 100, no doors  (but 422 tiles from the workshop)
 *   fire   Al Kharid bank    3269,3167 <-> ruins 3312,3253   141 / 141, no doors  (264 from the workshop)
 * The altar rooms (y 4800+) are outside nav-p0.bin; inside, the walk is a plain walkTo of under 15 tiles.
 *
 * Pure rules (route table, key check, stall and park rules, XP) are static and tested without a world (TestRcRun).
 */
final class RcRun {
    static final boolean ON = Boolean.getBoolean("artisans.altarLoop");
    static final String ALTARS = System.getProperty("artisans.altarLoop.altars", "earth");
    /** W8 wave 5 (-Dartisans.altarLoop2, default OFF; needs -Dartisans.altarLoop): a second altar route for the runecrafter, added to the list (default body: Varrock West bank, 84 tiles from the workshop). */
    static final boolean LOOP2 = Boolean.getBoolean("artisans.altarLoop2");
    static final String SECOND = System.getProperty("artisans.altarLoop2.altar", "body");
    /** The list the runecrafter's batches use: ALTARS itself with altarLoop2 off (the same string), else ALTARS plus the second route. */
    static final String LIST = withSecond(LOOP2, ALTARS, SECOND);
    static String withSecond(boolean on, String csv, String second) {
        if (!on || second == null || second.trim().isEmpty() || listed(csv, second)) return csv;
        return csv == null || csv.trim().isEmpty() ? second.trim() : csv + "," + second.trim();
    }

    static final int RUNNING = 0, DONE = 1, FALLBACK = 2;
    static final long WALK_LIMIT = 240_000L, STILL_LIMIT = 30_000L, INSIDE_LIMIT = 60_000L, PARK_WINDOW = 3_600_000L, PARK_FOR = 3_600_000L;
    static final int PARK_STALLS = 2;
    /** t62 fix 2: portal retries on the walk back, 10 s apart, before the bot is set at its bank. */
    static final int BACK_TRIES = 3; static final long BACK_RETRY = 10_000L;
    static final int ESSENCE = 7936, RUNE_ESSENCE = 1436;

    // ------------------------------------------------------------------ route table

    static final class Route {
        final String name; final int rune, talisman, tiara, altarObj, portalObj; final double xp;
        final int bankX, bankY, ruinX, ruinY, endX, endY, navOut, navBack;
        long parkedUntil; final long[] stallAt = new long[PARK_STALLS];
        Location altarAt; boolean altarLooked;
        final boolean w6; // W8: true for the three W6 rows (gated by the W6 line in Gates), false for the W8 rows (mind, body)
        Route(String name, int rune, int talisman, int tiara, int altarObj, int portalObj, double xp, int bankX, int bankY, int ruinX, int ruinY, int endX, int endY, int navOut, int navBack) {
            this(true, name, rune, talisman, tiara, altarObj, portalObj, xp, bankX, bankY, ruinX, ruinY, endX, endY, navOut, navBack);
        }
        Route(boolean w6, String name, int rune, int talisman, int tiara, int altarObj, int portalObj, double xp, int bankX, int bankY, int ruinX, int ruinY, int endX, int endY, int navOut, int navBack) {
            this.w6 = w6; this.name = name; this.rune = rune; this.talisman = talisman; this.tiara = tiara; this.altarObj = altarObj; this.portalObj = portalObj; this.xp = xp;
            this.bankX = bankX; this.bankY = bankY; this.ruinX = ruinX; this.ruinY = ruinY; this.endX = endX; this.endY = endY; this.navOut = navOut; this.navBack = navBack;
        }
        Location bank() { return Location.create(bankX, bankY, 0); }
        Location ruin() { return Location.create(ruinX, ruinY, 0); }
        Location end() { return Location.create(endX, endY, 0); }
        /** The 64x64 region of the altar room (the gate box in Gates.java). */
        int[] room() { int x0 = endX >> 6 << 6, y0 = endY >> 6 << 6; return new int[]{x0, y0, x0 + 63, y0 + 63}; }
    }

    /** From the engine (content.global.skill.runecrafting MysteriousRuin / Altar / Talisman / Tiara / Rune, server.jar 11-19). */
    static final Route[] ROUTES = {
            new Route("air", 556, 1438, 5527, 2478, 2465, 5.0, 3012, 3355, 2983, 3292, 2841, 4829, 108, 100),
            new Route("earth", 557, 1440, 5535, 2481, 2468, 6.5, 3253, 3420, 3304, 3475, 2655, 4830, 122, 130),
            new Route("fire", 554, 1442, 5537, 2482, 2469, 7.0, 3269, 3167, 3312, 3253, 2574, 4849, 141, 141),
            // W8 wave 5 rows (engine values checked in TestRcRun; routes walked leg by leg with p38-navport.py on nav-p0.bin, 2026-11-19):
            //   mind  Falador West bank 2946,3368 <-> ruins 2980,3514   216 / 212, no doors  (bots.rc.runs; Falador East 261/267, Edgeville 297/293)
            //   body  Varrock West bank 3185,3436 <-> ruins 3051,3443   187 / 195, no doors  (altarLoop2; the workshop -> Varrock West 84; Edgeville 115/115 but 280 from the workshop)
            new Route(false, "mind", 558, 1448, 5529, 2479, 2466, 5.5, 2946, 3368, 2980, 3514, 2793, 4828, 216, 212),
            new Route(false, "body", 559, 1446, 5533, 2483, 2470, 7.5, 3185, 3436, 3051, 3443, 2521, 4834, 187, 195),
    };

    /** W8: the route row by name, or null. */
    static Route byName(String name) { for (Route r : ROUTES) if (r.name.equalsIgnoreCase(name)) return r; return null; }

    /** W8: the altar-room gate for a row the W6 line in Gates does not cover: a W8 row in use (altarLoop2's second route, or an rc.runs altar), or a W6 row rc.runs uses while altarLoop is off. */
    static boolean gateNew(Route r, boolean loopOn, boolean loop2, String second, boolean runsOn, String runsCsv) {
        boolean runs = runsOn && listed(runsCsv, r.name);
        if (r.w6) return !loopOn && runs;
        return loopOn && loop2 && r.name.equalsIgnoreCase(second) || runs;
    }

    /** t63 fix 2: may the altarLoop take this row? W6 rows always (the W6 Gates line gates them); a W8 row only as altarLoop2's second route (gateNew gates that one). */
    static boolean loopRow(Route r, boolean loop2, String second) { return r != null && (r.w6 || loop2 && r.name.equalsIgnoreCase(second)); }

    /** w20 (NOFIRE fix): is this recipe altarLoop2's second-route rune (essence in, the SECOND row's rune out)? Only asked behind LOOP2. */
    static boolean secondRecipe(boolean loopOn, boolean loop2, String second, int product, int[] inIds) {
        return loopOn && loop2 && route(product, inIds, second) != null;
    }
    /** w20: the second rune is planned up to twice its market target (the artisans' own rule for a glut of inputs), so the route is walked before the pile is bare. */
    static int secondWant(boolean isSecond, int want, int target) { return isSecond ? Math.max(want, 2 * target) : want; } // w20 review F1: twice the base target, never on top of the glut rule's own doubling
    /**
     * w20: the plan score's lift for the second rune while it is under that target. Without it the value weighting (log10 of the
     * price, doubled at zero stock) put every dearer rune first: in q21/q21b the runecrafter made death, law, chaos and air
     * (46 batches) and never one body rune, so the second route was never walked. Under a player's order (+10) and a wanted potion (+8).
     */
    static final double SECOND_LIFT = 6.0;
    static double secondLift(boolean isSecond) { return isSecond ? SECOND_LIFT : 0.0; }

    static boolean listed(String csv, String name) {
        if (csv == null) return false;
        for (String s : csv.split(",")) if (s.trim().equalsIgnoreCase(name)) return true;
        return false;
    }

    /** The loop route for a recipe: its product has a row, the row is listed, and every input is essence. Else null (old path). */
    static Route route(int product, int[] inIds, String csv) {
        if (inIds == null || inIds.length == 0) return null;
        for (int id : inIds) if (id != ESSENCE && id != RUNE_ESSENCE) return null;
        for (Route r : ROUTES) if (r.rune == product && listed(csv, r.name)) return r;
        return null;
    }

    /** The engine's ruin test: the tiara worn, or the talisman used on the ruins (carried). */
    static boolean keyOk(boolean tiaraWorn, boolean talismanCarried) { return tiaraWorn || talismanCarried; }

    /** Where the key comes from, in order: own (worn/carried), bank, bot stock (talisman, then tiara if the head slot is free). Null: none, the batch takes the old path. */
    static String keySource(boolean own, boolean inBank, int talismanStock, int tiaraStock, boolean headFree, boolean packSlot) {
        if (own) return "own";
        if (inBank && packSlot) return "bank";
        if (talismanStock > 0 && packSlot) return "stock-talisman";
        if (tiaraStock > 0 && headFree) return "stock-tiara";
        return null;
    }

    /** A walk leg stalls when it runs past its limit or the bot has not moved for STILL_LIMIT. */
    static boolean stalled(long now, long legStart, long lastMove, long limit) {
        return now - legStart > limit || now - lastMove > STILL_LIMIT;
    }

    /** Records a stall at time now in the ring; true when PARK_STALLS stalls fall inside PARK_WINDOW (the altar is then parked). */
    static boolean noteStall(long[] ring, long now) {
        System.arraycopy(ring, 0, ring, 1, ring.length - 1);
        ring[0] = now;
        for (long t : ring) if (t <= 0 || now - t > PARK_WINDOW) return false;
        return true;
    }

    static boolean parked(Route r, long now) { return now < r.parkedUntil; }

    static final int BACK_WAIT = 0, BACK_RETRY_PORTAL = 1, BACK_GIVE_UP = 2;
    /** t62 fix 2: still in the altar room on the walk back: wait BACK_RETRY, retry the portal up to BACK_TRIES times, then give up. */
    static int backAction(long sinceTry, int tries) {
        if (sinceTry <= BACK_RETRY) return BACK_WAIT;
        return tries < BACK_TRIES ? BACK_RETRY_PORTAL : BACK_GIVE_UP;
    }

    /** XP for the batch as the engine gives it per essence (RuneCraftPulse, multiplier 1). */
    static double xp(Route r, int essence) { return r.xp * Math.max(0, essence); }

    // ------------------------------------------------------------------ counters ([RC] hourly line, own timer in MarketBrain)

    static volatile int loops, crafted, workshopRunes, stalls, fallbacks, noKey, keysBought, keysFromBank, parkedSkips, returnStalls, parks, backGiveUps;
    static final java.util.Map<String, Integer> CRAFTED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    static String line() {
        return "[RC] hourly on=[altarLoop] altars=" + ALTARS + " loops=" + loops + " crafted=" + crafted + " at=" + CRAFTED_AT + " workshopRunes=" + workshopRunes
                + " stalls=" + stalls + " fallbacks=" + fallbacks + " returnStalls=" + returnStalls + " noKey=" + noKey + " keysBought=" + keysBought
                + " keysFromBank=" + keysFromBank + " parks=" + parks + " parkedSkips=" + parkedSkips + " backGiveUps=" + backGiveUps;
    }

    // ------------------------------------------------------------------ the loop (game thread, inside Artisans.Artisan.tick)

    enum Leg { BANK, RUINS, ALTAR, CRAFT, BACK }

    final Route r;
    final long startedAt;
    Leg leg = Leg.BANK;
    long legStart, lastMove, until;
    int backTries; // t62 fix 2
    Location lastLoc;
    static final java.util.Map<String, Long> NO_KEY_SAID = new java.util.concurrent.ConcurrentHashMap<>();

    RcRun(Route r, long now) { this.r = r; this.startedAt = now; this.legStart = now; this.lastMove = now; }

    /** Called once a runecrafting batch is bought. Null: the old path (no route, parked, or no key anywhere). Uses no Artisans.RNG draw. */
    static RcRun start(Artisans.Artisan a, Artisans.Plan plan, long now) {
        if (!ON || a == null || a.bot == null || plan == null || plan.r == null) return null;
        Route r = route(plan.r.product, plan.r.inIds, LIST); // W8: LIST == ALTARS with altarLoop2 off
        if (r == null) return null;
        if (!loopRow(r, LOOP2, SECOND)) return null; // t63 fix 2: a W8 row (mind/body) only as altarLoop2's named second route, the one row Gates gates for the loop (defaults: earth, a W6 row)
        if (parked(r, now)) { parkedSkips++; return null; }
        Player bot = a.bot;
        if (keySourceFor(bot, r) == null) {
            noKey++; fallbacks++;
            Long said = NO_KEY_SAID.get(bot.getUsername());
            if (said == null || now - said > 1_800_000L) {
                NO_KEY_SAID.put(bot.getUsername(), now);
                System.out.println("[RC] stall at " + r.name + " altar by " + bot.getUsername() + ": no " + r.name + " talisman or tiara (own, bank or bot stock); fallback to the workshop");
            }
            return null;
        }
        loops++;
        RcRun run = new RcRun(r, now);
        run.lastLoc = bot.getLocation();
        return run;
    }

    static boolean own(Player p, Route r) {
        boolean worn = false, carried = false;
        try { worn = p.getEquipment().containsItem(new Item(r.tiara)); } catch (Throwable ignored) { }
        try { carried = p.getInventory().containsItem(new Item(r.talisman)); } catch (Throwable ignored) { }
        return keyOk(worn, carried);
    }

    static String keySourceFor(Player p, Route r) {
        boolean inBank = false, headFree = false, packSlot = false; int tal = 0, tia = 0;
        try { inBank = p.getBank().containsItem(new Item(r.talisman)); } catch (Throwable ignored) { }
        try { headFree = p.getEquipment().get(0) == null; } catch (Throwable ignored) { }
        try { packSlot = p.getInventory().freeSlots() > 0; } catch (Throwable ignored) { }
        try { tal = core.game.ge.GrandExchange.Companion.getBotstockForId(r.talisman); } catch (Throwable ignored) { }
        try { tia = core.game.ge.GrandExchange.Companion.getBotstockForId(r.tiara); } catch (Throwable ignored) { }
        boolean afford = Ledger.Wallet.coins(p) >= Math.max(1, MarketBrain.value(r.talisman));
        boolean affordT = Ledger.Wallet.coins(p) >= Math.max(1, MarketBrain.value(r.tiara));
        return keySource(own(p, r), inBank, afford ? tal : 0, affordT ? tia : 0, headFree, packSlot);
    }

    /** At the bank: take the key out of the bank or buy it from bot stock (Ledger.consume pays the sellers). False: no key. */
    static boolean fetchKey(Player p, Route r) {
        String src = keySourceFor(p, r);
        if (src == null) return false;
        if ("own".equals(src)) return true;
        if ("bank".equals(src)) {
            Item t = new Item(r.talisman, 1);
            if (p.getBank().remove(t)) { if (p.getInventory().add(t)) { keysFromBank++; return true; } p.getBank().add(t); }
            return false;
        }
        int id = "stock-tiara".equals(src) ? r.tiara : r.talisman;
        int took = Ledger.consume(p, id, 1, "rc-key");
        if (took <= 0) return false;
        boolean placed;
        if (id == r.tiara) placed = p.getEquipment().get(0) == null && p.getEquipment().replace(new Item(id, 1), 0) == null;
        else placed = p.getInventory().add(new Item(id, 1));
        if (!placed) { Ledger.supply(p, id, 1); return false; } // back on sale under its name: nothing lost, nothing minted
        keysBought++;
        System.out.println("[RC] " + p.getUsername() + " bought a " + new Item(id).getName().toLowerCase() + " for the " + r.name + " altar");
        return own(p, r);
    }

    /** The talisman goes back in the bank at the end of a loop (the pack is not kept over a restart; the bank is). */
    static void bankKey(Player p, Route r) {
        try { Item t = new Item(r.talisman, 1); if (p.getInventory().containsItem(t) && p.getInventory().remove(t) && !p.getBank().add(t)) p.getInventory().add(t); } catch (Throwable ignored) { }
    }

    /** Engine altar object in the altar room, found once per route (scan around the landing tile). */
    static Location findAltar(Route r) {
        if (r.altarLooked) return r.altarAt;
        r.altarLooked = true;
        for (int d = 0; d <= 16 && r.altarAt == null; d++)
            for (int dx = -d; dx <= d && r.altarAt == null; dx++)
                for (int dy = -d; dy <= d; dy++) {
                    if (Math.abs(dx) != d && Math.abs(dy) != d) continue;
                    Scenery s = null;
                    try { s = RegionManager.getObject(0, r.endX + dx, r.endY + dy); } catch (Throwable ignored) { }
                    if (s != null && s.getId() == r.altarObj) { r.altarAt = s.getLocation(); break; }
                }
        return r.altarAt;
    }

    void walk(Artisans.Artisan a, Location dest) {
        Player bot = a.bot;
        if (bot.getWalkingQueue().isMoving()) return;
        Location hop = null;
        if (dest.getY() < 4700) {
            if (NavWalker.ON) { try { hop = NavWalker.step(bot, dest); } catch (Throwable ignored) { } }
            if (hop == null) try { hop = RouteMap.nextHop(bot, dest); } catch (Throwable ignored) { }
            if (RouteMap.usingShortcut(bot)) return;
        }
        a.scriptAPI.walkTo(hop != null ? hop : dest);
    }

    void nextLeg(Leg l, long now) { leg = l; legStart = now; lastMove = now; }

    /** A stall before the craft: leave the altar room if inside, bank the key, hand the batch to the old path. */
    int stall(Artisans.Artisan a, long now, String why) {
        Player bot = a.bot;
        stalls++; fallbacks++;
        System.out.println("[RC] stall at " + r.name + " altar by " + bot.getUsername() + ": " + why + " after " + (now - legStart) / 1000 + " s on leg " + leg.name().toLowerCase() + "; fallback to the workshop");
        if (noteStall(r.stallAt, now)) { r.parkedUntil = now + PARK_FOR; parks++; System.out.println("[RC] " + r.name + " altar parked for " + PARK_FOR / 60_000 + " min after " + PARK_STALLS + " stalls (old path only)"); }
        if (bot.getLocation().getY() > 4700) try { bot.getProperties().setTeleportLocation(r.ruin()); } catch (Throwable ignored) { } // out through the portal, as the engine's portal does
        bankKey(bot, r);
        return FALLBACK;
    }

    int tick(Artisans.Artisan a, long now) {
        Player bot = a.bot;
        Location at = bot.getLocation();
        if (lastLoc == null || !at.equals(lastLoc)) { lastLoc = at; lastMove = now; }
        switch (leg) {
            case BANK: {
                if (at.getDistance(r.bank()) > 4) {
                    if (stalled(now, legStart, lastMove, WALK_LIMIT)) return stall(a, now, "walk to the bank");
                    walk(a, r.bank());
                    return RUNNING;
                }
                if (!fetchKey(bot, r)) return stall(a, now, "no key at the bank");
                nextLeg(Leg.RUINS, now);
                BotChat.activity(bot, () -> "taking essence to the " + r.name + " altar");
                return RUNNING;
            }
            case RUINS: {
                if (at.getDistance(r.ruin()) > 2) {
                    if (stalled(now, legStart, lastMove, WALK_LIMIT)) return stall(a, now, "walk to the ruins");
                    walk(a, r.ruin());
                    return RUNNING;
                }
                if (!own(bot, r)) return stall(a, now, "key gone at the ruins");
                if (Gates.ON && !Gates.canEnter(bot, r.end())) return stall(a, now, "ruins gate: " + Gates.missing(bot, r.end()));
                try { bot.animate(new Animation(827)); } catch (Throwable ignored) { }
                bot.getProperties().setTeleportLocation(r.end()); // as the engine's ruin listener: a teleport to the altar room's landing
                nextLeg(Leg.ALTAR, now);
                return RUNNING;
            }
            case ALTAR: {
                if (at.getY() < 4700) { if (now - legStart > 10_000) return stall(a, now, "did not land in the altar room"); return RUNNING; }
                Location altar = findAltar(r);
                if (altar == null) return stall(a, now, "no engine altar object near " + r.endX + "," + r.endY);
                if (at.getDistance(altar) > 3) {
                    if (stalled(now, legStart, lastMove, INSIDE_LIMIT)) return stall(a, now, "walk to the altar");
                    if (!bot.getWalkingQueue().isMoving()) a.scriptAPI.walkTo(altar);
                    return RUNNING;
                }
                try { bot.faceLocation(altar); } catch (Throwable ignored) { }
                try { bot.getAnimator().animate(new Animation(791), new Graphics(186, 100)); } catch (Throwable ignored) { }
                until = now + 3_000;
                nextLeg(Leg.CRAFT, now);
                return RUNNING;
            }
            case CRAFT: {
                if (now < until) return RUNNING;
                Artisans.Plan plan = a.plan;
                if (plan != null) {
                    int n = plan.r.amount * plan.times;
                    int forSale = Deals.settleOwed(bot, plan.r.product, n); // splits first, as the workshop path
                    if (forSale > 0) Ledger.supply(bot, plan.r.product, forSale);
                    a.made += n;
                    try { bot.getSkills().addExperience(core.game.node.entity.skill.Skills.RUNECRAFTING, xp(r, plan.times), true); } catch (Throwable ignored) { }
                    crafted += n; CRAFTED_AT.merge(r.name, n, Integer::sum);
                    System.out.println("[RC] crafted " + n + " at " + r.name + " altar by " + bot.getUsername() + " (" + (now - startedAt) / 1000 + " s from the buy)");
                    System.out.println("[Artisans] " + bot.getUsername() + " put " + n + " x " + plan.r.name + " on the GE"); // the workshop path's line, so rune counts read the same
                    a.plan = null; // sold: a shutdown from here on has nothing to hand back (settleForShutdown skips plan == null)
                }
                if (!Seed.RC_STICK) bot.getProperties().setTeleportLocation(r.ruin()); // the portal: back to the ruins (W8 .seed.rcstick: a portal that fails, scenario worlds only)
                nextLeg(Leg.BACK, now);
                return RUNNING;
            }
            case BACK: {
                if (at.getY() > 4700) { // t62 fix 2: the portal is retried BACK_TRIES times, 10 s apart, then the bot is set at its bank
                    int act = backAction(now - legStart, backTries);
                    if (act == BACK_RETRY_PORTAL) { backTries++; legStart = now; lastMove = now; if (!Seed.RC_STICK) bot.getProperties().setTeleportLocation(r.ruin()); }
                    else if (act == BACK_GIVE_UP) {
                        returnStalls++; backGiveUps++;
                        System.out.println("[RC] stall at " + r.name + " altar by " + bot.getUsername() + ": still in the altar room after " + backTries + " portal tries (runes already sold); set at the bank");
                        if (noteStall(r.stallAt, now)) { r.parkedUntil = now + PARK_FOR; parks++; System.out.println("[RC] " + r.name + " altar parked for " + PARK_FOR / 60_000 + " min after " + PARK_STALLS + " stalls (old path only)"); }
                        try { Spectacle.teleport(bot, r.bank()); } catch (Throwable t) { try { bot.getProperties().setTeleportLocation(r.bank()); } catch (Throwable ignored) { } }
                        bankKey(bot, r);
                        return DONE;
                    }
                    return RUNNING;
                }
                if (at.getDistance(r.bank()) > 4) {
                    if (stalled(now, legStart, lastMove, WALK_LIMIT)) {
                        returnStalls++;
                        System.out.println("[RC] stall at " + r.name + " altar by " + bot.getUsername() + ": walk back to the bank after " + (now - legStart) / 1000 + " s (runes already sold; no fallback needed)");
                        bankKey(bot, r);
                        return DONE;
                    }
                    walk(a, r.bank());
                    return RUNNING;
                }
                bankKey(bot, r);
                return DONE;
            }
        }
        return DONE;
    }
}
