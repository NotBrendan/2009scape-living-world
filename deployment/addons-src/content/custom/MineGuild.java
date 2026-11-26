package content.custom;

import core.game.node.entity.player.Player;
import core.game.world.map.Location;

/**
 * W8 wave 4 lane B item 2 (the design notes section 4, Mining row): -Dbots.mine.guild, default OFF.
 *
 * A bot with Mining 60+ on a coal or mithril job mines in the Mining Guild under Falador when the guild's ladder is
 * closer than the spot the Brain picked, at most CAP bots at once (-Dbots.mine.guild.cap, 6). The way in is the Runner's
 * dungeon-entrance walk (AfkTasks.Runner.entrance: walk to the ladder, climb, walk to the spot), the way out P90's climb.
 *
 * Route check (an offline route checker (not included) on nav-p0.bin, 11-19): Falador east bank 3013,3356 -> ladder 3017,3339 (55
 * steps, no doors), Falador west bank 2946,3368 -> ladder (100), ladder -> east bank (55). The ladder (2113 at 3018-3020,
 * 3338-3340) is reached from the open side; the engine's own climb checks Mining 60 (MiningGuildPlugin), and Gates'
 * "mining-guild" box (3016,9733-3055,9760, skill Mining 60) already refuses any bot under 60 the spot. The surface route has
 * no door, so no nav tiles are locked (the NavLocked pattern is not needed here).
 *
 * The Dwarven Mine is not wired: the underground is off the nav grid and no survey (rocks, stairs) covers it; see the note.
 * Pure parts (no engine classes at class init) for TestW8Wave4b.
 */
final class MineGuild {
    private MineGuild() { }

    static final boolean ON = Boolean.getBoolean("bots.mine.guild");
    static final int LEVEL = 60;
    static final int CAP = Math.max(1, Integer.getInteger("bots.mine.guild.cap", 6));
    /** {landing for a paid teleport (Falador), the ladder's top, the ladder's foot}, from MiningGuildPlugin and stairs.jsonl. */
    static final int[] LAND = {2965, 3380}, TOP = {3017, 3339}, FOOT = {3021, 9739};
    /** The spot: the middle of the guild; the Runner scans 18 tiles round the bot, 22 round the spot (the whole guild). */
    static final int[] SPOT = {3036, 9745};
    static final int X0 = 3016, Y0 = 9733, X1 = 3055, Y1 = 9760;
    /** Tasks the guild serves (AfkTasks.Task names): its rocks are coal and mithril. */
    static final String[] TASKS = {"COAL", "MITHRIL"};

    static volatile int starts, refusedCrowd, closerElsewhere;

    static boolean inGuild(int x, int y) { return x >= X0 && x <= X1 && y >= Y0 && y <= Y1; }
    static boolean serves(String task) { for (String t : TASKS) if (t.equals(task)) return true; return false; }

    /** Pure: take the guild over the picked spot. */
    static boolean wants(boolean on, int mining, String task, double dLadder, double dSpot, int crowd, int cap) {
        return on && mining >= LEVEL && serves(task) && dLadder < dSpot && crowd < cap;
    }

    /** Runner.entrance for a guild tile: {land, top, foot}; null elsewhere or off. */
    static Location[] entrance(Location under) {
        if (!ON || under == null || !inGuild(under.getX(), under.getY())) return null;
        return new Location[]{Location.create(LAND[0], LAND[1], 0), Location.create(TOP[0], TOP[1], 0), Location.create(FOOT[0], FOOT[1], 0)};
    }

    /** Bots now running at the guild. */
    static int crowd() {
        int n = 0;
        try { for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) if (r != null && r.spot != null && r.p != null && r.p.isArtificial() && inGuild(r.spot.getX(), r.spot.getY())) n++; } catch (Throwable ignored) { }
        return n;
    }

    /** Brain.start, after the spot is picked: the guild's spot instead, or null to keep the picked one. */
    static Location spotFor(Player p, AfkTasks.Task task, Location picked) {
        if (!ON || p == null || task == null || picked == null || !serves(task.name())) return null;
        int lvl = p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.MINING);
        if (lvl < LEVEL) return null;
        Location top = Location.create(TOP[0], TOP[1], 0), spot = Location.create(SPOT[0], SPOT[1], 0);
        double dl = top.getDistance(p.getLocation()), ds = picked.getDistance(p.getLocation());
        int c = crowd();
        if (!wants(true, lvl, task.name(), dl, ds, c, CAP)) { if (c >= CAP) refusedCrowd++; else closerElsewhere++; return null; }
        if (!Gates.canEnter(p, spot)) return null;
        starts++;
        return spot;
    }

    /** w36-silent2 (-Dbots.mine.guild2, W36Silent.mineTakes): the guild for a served job when the ladder is within the radius, whatever spot was picked. */
    static Location spotFor2(Player p, AfkTasks.Task task) {
        if (!ON || p == null || task == null) return null;
        int lvl = p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.MINING);
        Location top = Location.create(TOP[0], TOP[1], 0), spot = Location.create(SPOT[0], SPOT[1], 0);
        if (!W36Silent.mineTakes(true, lvl, serves(task.name()), top.getDistance(p.getLocation()), crowd(), CAP)) return null;
        if (!Gates.canEnter(p, spot)) return null;
        starts++;
        return spot;
    }

    static String line() {
        return "[MineGuild] on=" + ON + " starts=" + starts + " refusedCrowd=" + refusedCrowd + " closerElsewhere=" + closerElsewhere + " cap=" + CAP;
    }
}
