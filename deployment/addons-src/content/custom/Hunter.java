package content.custom;

import content.global.skill.hunter.HunterManager;
import content.global.skill.hunter.TrapWrapper;
import content.global.skill.hunter.Traps;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * W8 wave 4 lane B item 3 (the design notes section 4, Hunter row): -Dbots.hunter (+ -Dbots.hunter.chins),
 * both default OFF.
 *
 * A Hunter run is a trip-end errand of its own (like the farm and bone runs): walk from wherever the bot is to the Feldip
 * hunting grounds (QuestSteps.Mover: the planner, doors, the long-walk teleport), lay the engine's own traps there with the
 * same calls a player's clicks make (Traps.create on the trap item = "Lay"; Traps.dismantle on the trap scenery = "Check"
 * or "Dismantle"), wait, take, re-lay, then bank the catch at Yanille.
 *   - bird snares (10006) for crimson swifts (npc 5073, Hunter 1; engine reward: bones, red feathers, raw bird meat);
 *   - with .chins and Hunter 63+, box traps (10008) for carnivorous chinchompas (npc 5080, the engine's level 63). The plan's
 *     53+ grey chinchompas live at Piscatoris, which has no planner route (p38-navport: "no corridor"), so they are not used.
 * Routes (p38-navport.py, live nav-p0.bin, 11-19): Yanille bank 2612,3093 -> swifts 2607,2915 and -> chinchompas
 * 2557,2918 plan with no doors, and back.
 *
 * Caps: traps per bot = the engine's limit, at most 3; per area at most AREA_TRAPS bot traps (-Dbots.hunter.areaTraps, 9)
 * and AREA_BOTS bots (-Dbots.hunter.areaBots, 3); a bot runs at most once per COOLDOWN.
 * Economy: catches only from the engine's TrapSetting reward (as a player). Trap items: the bot's bank, else bot stock
 * (Ledger.consume: sellers paid), else bought at guide price (Wallet.pay: the shop way, a coin sink). Nothing is minted.
 * Its own Random (seeded, -Dbots.hunter.seed) so the Brain's RNG draw order is the same with the flag on or off.
 * With the flags off nothing here runs: every hook tests ON first. Pure rules first (TestW8Wave4b).
 */
final class Hunter {
    private Hunter() { }

    static final boolean ON = Boolean.getBoolean("bots.hunter");
    static final boolean CHINS = ON && Boolean.getBoolean("bots.hunter.chins");
    static final int SNARE = 10006, BOX = 10008, SWIFT = 5073, RED_CHIN = 5080, CHIN_LEVEL = 63;
    static final int KIND_SWIFT = 0, KIND_CHIN = 1;
    /** Area anchors {x, y} (spawns from npc_spawns.json), the bank, the radius a bot keeps its traps in. */
    static final int[][] AREA = {{2607, 2915}, {2557, 2920}};
    static final String[] AREA_NAME = {"the Feldip crimson swifts", "the Feldip chinchompas"};
    static final int BANK_X = 2612, BANK_Y = 3092, RADIUS = 5;
    static final int PER_BOT = 3;
    static final int AREA_TRAPS = Math.max(1, Integer.getInteger("bots.hunter.areaTraps", 9));
    static final int AREA_BOTS = Math.max(1, Integer.getInteger("bots.hunter.areaBots", 3));
    static final long RUN_MS = Long.getLong("bots.hunter.runMs", 20 * 60_000L);
    static final long COOLDOWN = Long.getLong("bots.hunter.cooldownMs", 2 * 3_600_000L);
    static final long TRAP_MAX_MS = 75_000L; // an empty trap older than this is taken up and re-laid (before the engine's own time-up drops it)
    /** w13d routes-t65 #1: Yanille -> Feldip passes 1 tile from the level-53 ogres (aggressive to combat 106; 25 path tiles within 5 of a spawn).
     *  The ogre-free detour is 516/461 tiles against 330/297 and no 2009 player walks it, so the gate is the faithful fix: combat 40 as the
     *  route note's food-gate level for an npc level of 53 (-Dbots.hunter.minCombat, was 15). */
    static final int MIN_COMBAT = Math.max(3, Math.min(126, Integer.getInteger("bots.hunter.minCombat", 40))), MIN_FREE = 10;
    /** w19 (q22/q25 WORSE: deaths, stuck): a run that starts east of White Wolf Mountain walks over it (ON-arm deaths at x 2847-2866,
     *  y 3478-3508 by white wolves level 25/38; runs ended "stuck at [2855, 3476]" with no teleport fare) and leaves the bot west of
     *  it, so its next job walks back over. White wolves (38) stay aggressive to combat 76: a bot under 77 starts a run only from the
     *  west side (x under 2840: Catherby, Seers', Ardougne, Yanille). -Dbots.hunter.wolfCombat (0 = no gate, the train-70 rule). */
    static final int WOLF_X = 2840, WOLF_COMBAT = Math.max(0, Math.min(127, Integer.getInteger("bots.hunter.wolfCombat", 77)));
    static final Random RNG = new Random(Long.getLong("bots.hunter.seed", 4L));

    static volatile int refusedWolf; static volatile int runs, refusedArea, refusedKit, laid, layFail, checks, relays, banked, walkFail, noManager, ended;
    static volatile long spent;
    /** Engine catch items the bot banks: bones, red feather, raw bird meat, red chinchompa. */
    static final int[] PRODUCTS = {526, 10088, 9978, 10034};
    static final Map<Integer, Integer> GOT = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ pure rules

    /** Which quarry: chinchompas with .chins on at 63+, else swifts. */
    static int kind(int hunter, boolean chins) { return chins && hunter >= CHIN_LEVEL ? KIND_CHIN : KIND_SWIFT; }
    /** The engine's trap limit by level (1 under 20, +1 each 20), at most PER_BOT. */
    static int trapsFor(int hunter) { return Math.min(PER_BOT, 1 + Math.max(0, Math.min(99, hunter)) / 20); }
    /** Room in the area for one more bot with this many traps. */
    static boolean areaOk(int trapsThere, int botsThere, int want, int capTraps, int capBots) { return botsThere < capBots && trapsThere + want <= capTraps; }
    /** A run may start: not maxed, fit to fight off a stray, room in the pack, past the cooldown. */
    static boolean fit(int hunter, int combat, int free, long now, long last, long cooldown) {
        return hunter < 99 && combat >= MIN_COMBAT && free >= MIN_FREE && (last == 0 || now - last >= cooldown);
    }

    /** w19: the walk to Feldip from x does not cross White Wolf Mountain, or the bot is past the wolves' aggression. */
    static boolean wolfSafe(int x, int combat, int wolfCombat) { return x < WOLF_X || combat >= wolfCombat; }

    // ------------------------------------------------------------------ in world

    static final class Run {
        final SmartAdventurers.Brain br; final int kind; final Location area, bank; final QuestSteps.Mover mv = new QuestSteps.Mover();
        int phase; long until, start; int want, caught0; int xp0; final Map<Location, Long> layAt = new ConcurrentHashMap<>(); Location stepTo; int stall;
        Run(SmartAdventurers.Brain br, int kind) {
            this.br = br; this.kind = kind;
            area = Location.create(AREA[kind][0], AREA[kind][1], 0); bank = Location.create(BANK_X, BANK_Y, 0);
        }
    }
    static final int WALK = 0, HUNT = 1, COLLECT = 2, BANK = 3;
    static final Map<String, Run> RUNS = new ConcurrentHashMap<>();
    static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }
    static boolean on(SmartAdventurers.Brain br) { return br != null && br.bot != null && RUNS.containsKey(key(br.bot)); }

    static int trapItem(int kind) { return kind == KIND_CHIN ? BOX : SNARE; }
    static Traps trap(int kind) { return kind == KIND_CHIN ? Traps.BOX_TRAP : Traps.BIRD_SNARE; }

    /** {traps, bots} of bot runs in an area. */
    static int[] inArea(int kind) {
        int t = 0, b = 0;
        for (Run r : RUNS.values()) {
            if (r.kind != kind) continue;
            b++;
            try { HunterManager hm = HunterManager.getInstance(r.br.bot); t += hm == null ? r.want : Math.max(r.want, hm.getTrapAmount()); } catch (Throwable x) { t += r.want; }
        }
        return new int[]{t, b};
    }

    /**
     * w11 fix 1: the engine attaches a HunterManager in its LoginListener, which runs only on the LoginParser path; a bot
     * (AIPlayer) never logs in that way, so getInstance was always null and no run ever started (x1: noManager=38, runs=0).
     * Once-guard as Slayer.ensure: attach only when the attribute is missing (a second login would add a second tick hook).
     * Called only from plan (behind Hunter.ON), after the RNG draw: the draw order is unchanged.
     */
    static HunterManager ensure(Player p) {
        if (p.getAttribute("hunter-manager", null) == null) new HunterManager().login(p);
        return HunterManager.getInstance(p);
    }

    /** Trip end (Brain.tick, before the farm run): start a run, or false. */
    static boolean plan(SmartAdventurers.Brain br, long now) {
        if (!ON || br == null) return false;
        Player p = br.bot;
        if (p == null || !p.isArtificial() || br.busy() || p.inCombat() || on(br)) return false;
        int hunter = p.getSkills().getStaticLevel(Skills.HUNTER);
        Long last = LAST.get(key(p));
        if (!fit(hunter, p.getProperties().getCurrentCombatLevel(), p.getInventory().freeSlots(), now, last == null ? 0 : last, COOLDOWN)) return false;
        if (!wolfSafe(p.getLocation().getX(), p.getProperties().getCurrentCombatLevel(), WOLF_COMBAT)) { refusedWolf++; return false; } // w19: before the draw; asked again next trip end (no cooldown booked)
        if (RNG.nextInt(hunter < 20 ? 4 : 8) != 0) return false; // a newcomer to the skill tries it more often
        int kind = kind(hunter, CHINS), want = trapsFor(hunter);
        int[] there = inArea(kind);
        if (!areaOk(there[0], there[1], want, AREA_TRAPS, AREA_BOTS)) { refusedArea++; LAST.put(key(p), now - COOLDOWN / 2); return false; }
        try { if (ensure(p) == null) { noManager++; LAST.put(key(p), now); return false; } } catch (Throwable t) { noManager++; LAST.put(key(p), now); return false; } // w11 fix 1: was getInstance only (noManager=38, runs=0 in x1)
        int item = trapItem(kind);
        long c0 = Ledger.Wallet.coins(p);
        if (!kit(p, item, want)) { refusedKit++; LAST.put(key(p), now); return false; }
        spent += Math.max(0, c0 - Ledger.Wallet.coins(p));
        Run r = new Run(br, kind);
        r.want = want; r.start = now; r.xp0 = (int) p.getSkills().getExperience(Skills.HUNTER); r.mv.reset(r.area, 3);
        RUNS.put(key(p), r); LAST.put(key(p), now); runs++; br.nextThink = now + 600;
        System.out.println("[Hunter] " + p.getUsername() + " -> " + AREA_NAME[kind] + " (Hunter " + hunter + ", " + want + " " + MarketBrain.name(item).toLowerCase(Locale.ROOT) + "s, spent " + Math.max(0, c0 - Ledger.Wallet.coins(p)) + " gp)");
        try { BotChat.activity(p, () -> "hunting at " + AREA_NAME[kind]); } catch (Throwable ignored) { }
        return true;
    }

    /** n trap items in the pack: pack, bank, bot stock (paid), else bought at guide price. */
    static boolean kit(Player p, int id, int n) {
        int need = n - p.getInventory().getAmount(id);
        if (need <= 0) return true;
        if (p.getInventory().freeSlots() < need) return false;
        int bank = Math.min(need, p.getBank().getAmount(id));
        if (bank > 0 && p.getBank().remove(new Item(id, bank))) {
            for (int i = 0; i < bank; i++) if (!p.getInventory().add(new Item(id))) { p.getBank().add(new Item(id, bank - i)); return false; }
            need -= bank;
        }
        if (need <= 0) return true;
        int got = MarketBrain.stockOf(id) > need + 5 ? Ledger.consume(p, id, need, "hunter") : 0;
        long paid = 0;
        if (got <= 0) {
            long cost = Math.max(1, MarketBrain.value(id)) * (long) need;
            if (Ledger.Wallet.coins(p) < cost * 4 || !Ledger.Wallet.pay(p, cost)) return false;
            got = need; paid = cost;
        }
        for (int i = 0; i < got; i++) if (!p.getInventory().add(new Item(id)) && !p.getBank().add(new Item(id))) { W8Polish.unplaced(p, id, got - i, W8Polish.refundCoins(paid, i, got), "hunter kit"); return false; } // t63 fix 3: the unplaced part's coins back (or back on the Exchange)
        return p.getInventory().getAmount(id) >= n;
    }

    /** Brain.tick: drive a run. True while one is active (the Brain does nothing else that tick). */
    static boolean tick(SmartAdventurers.Brain br, long now) {
        try { return tick0(br, now); } catch (Throwable t) { fail(br, t); return false; } // t63 fix 4: never the world tick
    }

    static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();
    static volatile int tickFails;
    /** t63 fix 4: a throw in a run drops the run; logged once per bot. */
    static void fail(SmartAdventurers.Brain br, Throwable t) {
        tickFails++;
        Player p = br == null ? null : br.bot;
        String k; try { k = p == null ? "?" : key(p); } catch (Throwable e) { k = "?"; }
        if (FAILED.add(k)) System.out.println("[Hunter] " + k + " run failed: " + t + " (run dropped; logged once per bot)");
        if (!"?".equals(k)) RUNS.remove(k);
    }

    static boolean tick0(SmartAdventurers.Brain br, long now) {
        if (!ON || br == null || br.bot == null) return false;
        Run r = RUNS.get(key(br.bot));
        if (r == null) return false;
        Player p = br.bot;
        br.nextThink = now + 600; // a step a tick while hunting (as the farm run)
        try {
            if (!p.isActive()) { end(r, "logged out"); return false; }
            if (p.getPulseManager().hasPulseRunning() || p.getLocks().isInteractionLocked()) return true;
            switch (r.phase) {
                case WALK: {
                    QuestSteps.St s = r.mv.tick(p);
                    if (s == QuestSteps.St.DONE) { r.phase = HUNT; r.until = now + RUN_MS; r.caught0 = 0; }
                    else if (s == QuestSteps.St.FAILED) { walkFail++; end(r, "couldn't walk there (" + r.mv.last + ")"); }
                    return true;
                }
                case HUNT: hunt(r, p, now); return true;
                case COLLECT: collect(r, p); return true;
                case BANK: {
                    QuestSteps.St s = r.mv.tick(p);
                    if (s == QuestSteps.St.DONE) { bank(r, p); end(r, "done"); }
                    else if (s == QuestSteps.St.FAILED) { walkFail++; end(r, "couldn't walk to the bank (" + r.mv.last + ")"); }
                    return true;
                }
                default: end(r, "bad phase"); return false;
            }
        } catch (Throwable t) {
            fail(br, t);
            return false;
        }
    }

    static List<TrapWrapper> traps(Player p) {
        HunterManager hm = HunterManager.getInstance(p);
        return hm == null ? new ArrayList<>() : new ArrayList<>(hm.getTraps());
    }

    static void hunt(Run r, Player p, long now) {
        // take a full or fallen trap, or an empty one up too long (re-laid below)
        for (TrapWrapper w : traps(p)) {
            if (w.getObject() == null) continue;
            Long at = r.layAt.get(w.getObject().getLocation());
            boolean old = at != null && now - at > TRAP_MAX_MS && !w.isBusy();
            if (w.isCaught() || w.isFailed() || old) {
                if (!near(p, w.getObject().getLocation())) return;
                r.layAt.remove(w.getObject().getLocation());
                w.getType().dismantle(p, w.getObject());
                if (w.isCaught()) checks++; else relays++;
                return;
            }
        }
        if (now > r.until || p.getInventory().freeSlots() < 3) { r.phase = COLLECT; return; }
        int have = traps(p).size(), item = trapItem(r.kind);
        if (have >= r.want || p.getInventory().getAmount(item) <= 0) return; // wait for a catch
        Location here = p.getLocation();
        if (here.getDistance(r.area) > RADIUS + 2 || !free(here)) {
            if (p.getWalkingQueue().isMoving()) return;
            Location to = freeTile(r.area, here);
            if (to == null || ++r.stall > 12) { r.phase = COLLECT; return; }
            walk(p, to);
            return;
        }
        Item it = null;
        for (int i = 0; i < p.getInventory().capacity(); i++) { Item x = p.getInventory().get(i); if (x != null && x.getId() == item) { it = x; break; } }
        if (it == null) return;
        trap(r.kind).create(p, it); // the engine's TrapCreatePulse (what "Lay" runs)
        if (!p.getPulseManager().hasPulseRunning()) { layFail++; if (++r.stall > 12) r.phase = COLLECT; else walk(p, freeTile(r.area, here)); return; }
        r.layAt.put(here, now); r.stall = 0; laid++;
    }

    /** Take up every trap (the trap items come back), then the bank. */
    static void collect(Run r, Player p) {
        for (TrapWrapper w : traps(p)) {
            if (w.getObject() == null) continue;
            if (!near(p, w.getObject().getLocation())) return;
            boolean caught = w.isCaught();
            w.getType().dismantle(p, w.getObject());
            if (caught) checks++;
            return;
        }
        r.phase = BANK; r.mv.reset(r.bank, 2);
    }

    /** At the bank: the catch and the trap items into the bot's own bank. */
    static void bank(Run r, Player p) {
        int n = 0;
        int[] ids = {PRODUCTS[0], PRODUCTS[1], PRODUCTS[2], PRODUCTS[3], SNARE, BOX};
        for (int id : ids) {
            int c = p.getInventory().getAmount(id);
            if (c <= 0) continue;
            if (p.getInventory().remove(new Item(id, c))) {
                if (!p.getBank().add(new Item(id, c))) { p.getInventory().add(new Item(id, c)); continue; }
                if (id != SNARE && id != BOX) { n += c; GOT.merge(id, c, Integer::sum); }
            }
        }
        banked += n;
        System.out.println("[Hunter] " + p.getUsername() + " banked " + n + " catch items at Yanille (" + AREA_NAME[r.kind] + ", +" + ((int) p.getSkills().getExperience(Skills.HUNTER) - r.xp0) + " Hunter xp, Hunter " + p.getSkills().getStaticLevel(Skills.HUNTER) + ")");
    }

    static void end(Run r, String why) {
        Player p = r.br.bot;
        RUNS.remove(key(p)); ended++;
        if (!"done".equals(why)) System.out.println("[Hunter] " + p.getUsername() + " run ended: " + why);
        try { for (TrapWrapper w : traps(p)) if (w.getObject() != null && near(p, w.getObject().getLocation())) w.getType().dismantle(p, w.getObject()); } catch (Throwable ignored) { } // one near the bot comes back; the engine times out the rest
    }

    static boolean near(Player p, Location l) {
        if (p.getLocation().getDistance(l) <= 1.5) return true;
        if (!p.getWalkingQueue().isMoving()) walk(p, l);
        return false;
    }

    static boolean free(Location l) {
        try { return RegionManager.getObject(l) == null && !RegionManager.isClipped(l); } catch (Throwable t) { return false; }
    }

    static Location freeTile(Location anchor, Location from) {
        Location best = null; double bd = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dy = -RADIUS; dy <= RADIUS; dy++) {
            Location t = anchor.transform(dx, dy, 0);
            if (!free(t)) continue;
            double d = t.getDistance(from) + RNG.nextInt(3);
            if (d < bd) { bd = d; best = t; }
        }
        return best;
    }

    static void walk(Player p, Location to) {
        if (to == null) return;
        try { core.game.world.map.path.Path path = core.game.world.map.path.Pathfinder.find(p, to); if (path != null && path.isSuccessful()) path.walk(p); } catch (Throwable ignored) { }
    }

    static String line() {
        StringBuilder g = new StringBuilder();
        for (int id : PRODUCTS) g.append(id).append('=').append(GOT.getOrDefault(id, 0)).append(' ');
        return "[Hunter] on=" + ON + " chins=" + CHINS + " runs=" + runs + " active=" + RUNS.size() + " laid=" + laid + " layFail=" + layFail + " checks=" + checks + " relays=" + relays
                + " banked=" + banked + " got=[" + g.toString().trim() + "] spent=" + spent + " refusedArea=" + refusedArea + " refusedKit=" + refusedKit + " noManager=" + noManager + " walkFail=" + walkFail + " ended=" + ended + (tickFails > 0 ? " tickFails=" + tickFails : "") + (refusedWolf > 0 ? " refusedWolf=" + refusedWolf : "");
    }
}
