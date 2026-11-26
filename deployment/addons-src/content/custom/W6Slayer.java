package content.custom;

import core.game.world.map.Location;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * W6 wave 3 Slayer (-Dbots.slayer.vannakaHunts, default off; the design notes section 3): hunts for
 * Vannaka tasks the log reported as "no hunt for X (Vannaka)". Off: nothing here is called (every hook is behind ON) and
 * HuntMap, JOBS, the seats and the gates are train-61's exactly.
 *
 * Each hunt built here is a task holder's hunt only (HuntMap.Hunt.tag set, so chooseJob and AfkTasks skip it for other
 * bots, as the slayer8 extra spots), with its own seat cap and a combat/prayer/food gate on top of the general danger rules.
 *  - Hellhound (task HELLHOUNDS): Taverley dungeon 2866,9840, 10 of the 13 spawns there within 15 tiles. The area lies behind
 *    the dusty-key gate (offline clipping BFS from the cache: no path from the ladder foot 2884,9798; 202 steps from inside the
 *    gate 2922,9803, west past the black demons and north up x 2871), so: a Gates box needing the dusty key, P82's key walk
 *    for it (behindKeyGate), and only with -Dnav.p82Fix and -Dnav.p84Fix on (the key walk). Combat 90, Prayer 43 (protect
 *    from melee), 10 food; the npcLevel 80+ rule asks for an anti-dragon shield too. 2 seats.
 *  - Vampire (task VAMPIRES): the Morytania surface spot HuntMap already finds (3631,3471, 6 spawns; p38-navport: reached
 *    from Canifis in 197 tiles). Never built before: the family's first id (1023) has no Attack option, so HuntMap skipped
 *    the whole family. Priest in Peril by the Morytania gate as before. Combat 84 (the job level), 8 food. 2 seats.
 * Parked (see the design notes): GREEN_DRAGONS, EARTH_WARRIORS (Wilderness only), ICE_WARRIOR (walled
 * pocket on White Wolf Mountain, Taverley's ice cave not reached from the ladder, Asgarnian Ice Dungeon next to skeletal
 * wyverns), KALPHITES, OTHERWORDLY_BEING, WEREWOLVES, ANKOU, DAGANNOTHS (no walk in: rope lair, Zanaris, God Wars,
 * Stronghold of Security, Horror from the Deep areas).
 */
final class W6Slayer {
    private W6Slayer() { }
    static final boolean ON = Boolean.getBoolean("bots.slayer.vannakaHunts");
    /** -Dbots.slayer.iceGiantFix (default off): HuntMap drops the Ice giant hunt whose spot is in the White Wolf Mountain pocket
     *  (p38-navport on nav-p0: all 4 surface spawns 2804-2824, 3506-3514 in a 274-tile region, box 2800-2828 x 3500-3518, no door
     *  tile, cut off from Taverley and Catherby). No other Ice giant spawn is usable: Taverley dungeon's ice cave (2880-2891,
     *  9927-9965) is reached neither from the ladder nor through the key gate (cache clipping BFS); Asgarnian Ice Dungeon has no
     *  walk entrance and skeletal wyverns 23-30 tiles off; the rest is the Wilderness or the Chaos Tunnels. Dropped like the P93
     *  pockets: ICE_GIANTS leaves the served Vannaka list ("no hunt for ICE_GIANTS (Vannaka)"), so it is never rolled as open. */
    static final boolean ICE_FIX = Boolean.getBoolean("bots.slayer.iceGiantFix");
    static final int ICE_X0 = 2800, ICE_Y0 = 3500, ICE_X1 = 2828, ICE_Y1 = 3518;
    static final AtomicInteger iceDropped = new AtomicInteger();
    static boolean icePocket(String name, Location spot) { return "Ice giant".equals(name) && spot != null && spot.getZ() == 0 && spot.getX() >= ICE_X0 && spot.getX() <= ICE_X1 && spot.getY() >= ICE_Y0 && spot.getY() <= ICE_Y1; }
    static boolean anyOn() { return ON || ICE_FIX; }

    static final class Spot {
        final String name, tag; final Location at; final int spawns, seats, combat, prayer, food; final boolean keyWalk;
        Spot(String name, String tag, Location at, int spawns, int seats, int combat, int prayer, int food, boolean keyWalk) {
            this.name = name; this.tag = tag; this.at = at; this.spawns = spawns; this.seats = seats; this.combat = combat; this.prayer = prayer; this.food = food; this.keyWalk = keyWalk;
        }
    }
    /** name (HuntMap's family first-id name) -> the hunt. at == null: HuntMap's own densest surface spot is used. */
    static final Spot[] SPOTS = {
            new Spot("Hellhound", "Taverley dungeon", Location.create(2866, 9840, 0), 10, 2, 90, 43, 10, true),
            new Spot("Vampire", "Morytania", null, 0, 2, 84, 0, 8, false),
    };
    /** The hellhounds' Gates box (dusty key) and the extra P82 key-walk goal box: x 2845-2880, y 9816-9860 (dungeon plane). */
    static final int HELL_X0 = 2845, HELL_Y0 = 9816, HELL_X1 = 2880, HELL_Y1 = 9860;
    static boolean hellBox(int x, int y, int z) { return z == 0 && x >= HELL_X0 && x <= HELL_X1 && y >= HELL_Y0 && y <= HELL_Y1; }

    static final Map<HuntMap.Hunt, Spot> BUILT = Collections.synchronizedMap(new IdentityHashMap<>());
    static final AtomicInteger built = new AtomicInteger(), closedLevel = new AtomicInteger(), closedPrayer = new AtomicInteger(), closedFood = new AtomicInteger(), skippedKeyWalk = new AtomicInteger();

    /** Pure: the W6 spot for a HuntMap family, or null. keyWalk spots need the key walk (P82 + P84); a spot with a given tile
     *  is only for a family with no surface hunt (under 3 surface spawns, no curated spot); a surface spot (at null) only for
     *  a family that has one (3+ surface spawns, no curated spot). */
    static Spot spotFor(boolean on, String name, boolean curated, int surface, boolean keyWalkOk) {
        if (!on || name == null || curated) return null;
        for (Spot s : SPOTS) {
            if (!s.name.equals(name)) continue;
            if (s.keyWalk && !keyWalkOk) { skippedKeyWalk.incrementAndGet(); return null; }
            if (s.at != null ? surface >= 3 : surface < 3) return null;
            return s;
        }
        return null;
    }
    static boolean keyWalkOk() { return P82.ON && P84.ON; }

    /** HuntMap: mark the hunt as W6 (tag, spawns at a given spot) once it is built. */
    static void built(HuntMap.Hunt h, Spot s) {
        h.tag = s.tag;
        if (s.at != null) h.spawnsAtSpot = s.spawns;
        BUILT.put(h, s); built.incrementAndGet();
        System.out.println("[W6] Vannaka hunt " + HuntMap.label(h) + " at " + h.spot + " (level " + h.npcLevel + ", " + h.spawnsAtSpot + " spawns, " + s.seats + " seats, needs combat " + s.combat + (s.prayer > 0 ? " prayer " + s.prayer : "") + " food " + s.food + ")");
    }
    static Spot of(HuntMap.Hunt h) { return h == null ? null : BUILT.get(h); }

    /** Seats of a W6 hunt, or -1 (not a W6 hunt: the general rule). */
    static int seats(HuntMap.Hunt h) { Spot s = of(h); return s == null ? -1 : s.seats; }

    /** Pure gate: null = open, else the reason. */
    static String gate(Spot s, int combat, int prayer, int food) {
        if (s == null) return null;
        if (combat < s.combat) return "w6level";
        if (prayer < s.prayer) return "w6prayer";
        if (food < s.food) return "w6food";
        return null;
    }
    /** The gate for a bot (chooseJob, able, Slayer.openGate). Counts each refusal. */
    static String closed(SmartAdventurers.Brain b, HuntMap.Hunt h, int combat) {
        Spot s = of(h);
        if (s == null) return null;
        int prayer = b.bot.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.PRAYER);
        String r = gate(s, combat, prayer, s.food > 0 ? b.foodFor(h) : 0);
        if ("w6level".equals(r)) closedLevel.incrementAndGet(); else if ("w6prayer".equals(r)) closedPrayer.incrementAndGet(); else if ("w6food".equals(r)) closedFood.incrementAndGet();
        return r;
    }

    static String line() {
        StringBuilder b = new StringBuilder();
        synchronized (BUILT) { for (Map.Entry<HuntMap.Hunt, Spot> e : BUILT.entrySet()) b.append(HuntMap.label(e.getKey())).append('=').append(SmartAdventurers.Brain.crowdHunt(e.getKey())).append(' '); }
        return "[W6] on=[" + (ON ? "vannakaHunts" : "") + (ON && ICE_FIX ? " " : "") + (ICE_FIX ? "iceGiantFix" : "") + "] iceDropped=" + iceDropped + " built=" + built + " busy=[" + b.toString().trim() + "] closedLevel=" + closedLevel + " closedPrayer=" + closedPrayer + " closedFood=" + closedFood + " skippedKeyWalk=" + skippedKeyWalk;
    }
}
