package content.custom;

import core.game.node.entity.player.Player;
import core.game.world.map.Location;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * batch 20 Workstream A: one pre-flight for goal, shop and job walks.
 *
 * <p>Pick time ({@link #ok}): a target is refused when a gate rule says the bot may not be there (A1: the Warriors' Guild
 * without attack + strength 130, Morytania without Priest in Peril, the Fishing Guild under 68, Neitiznot without the
 * Fremennik Trials), when it is off the surface grid (A3: underground y > 6400 or upstairs z > 0; the planner only knows
 * the surface, and curated dungeon hunts go through {@code AfkTasks.Runner.entrance} instead), or when the bot is in
 * Morytania, the target is west of the Salve and it can't pay the teleport out (A2).
 *
 * <p>Walk time ({@link #out}): before a surface walk starts, a bot east of the Salve heading west pays a teleport (A2:
 * Morytania's surface is its own grid piece, the walk west always ended in "no route"; 138 records at Canifis, 19 in
 * Mort Myre); a bot underground pays the teleport up (the old walk when broke); a bot upstairs climbs down with the
 * quest climber, then the teleport if no stair works. Kill switch: {@code -Dpreflight=false}.
 */
final class Preflight {
    private Preflight() { }

    static final boolean ON = !"false".equals(System.getProperty("preflight"));

    /** Morytania's surface box (the gate table's), any plane. */
    static final int MORY_X0 = 3400, MORY_X1 = 3910, MORY_Y0 = 3150, MORY_Y1 = 3600, SALVE_X = 3400;

    static boolean offGrid(Location l) { return l != null && (l.getZ() != 0 || l.getY() > 6400); }

    static boolean morytania(Location l) {
        return l != null && l.getY() < 6400 && l.getX() >= MORY_X0 && l.getX() <= MORY_X1 && l.getY() >= MORY_Y0 && l.getY() <= MORY_Y1;
    }

    /** From Morytania to anywhere west of the Salve: the reverse of the eastbound rule in {@code AfkTasks.Runner.goTo}. */
    static boolean leavingMorytania(Location from, Location to) {
        return from != null && to != null && morytania(from) && to.getY() < 6400 && to.getX() < SALVE_X;
    }

    /** What {@code Ledger.Wallet.payTeleport} charges at most. */
    static long fare() { return Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556)); }

    /** Why this bot may not set out for t, or null when it may. */
    static String refuse(Player bot, Location t) {
        if (t == null) return "no destination";
        if (bot == null) return null;
        if (!Gates.canEnter(bot, t)) return "needs " + Gates.missing(bot, t);
        if (offGrid(t)) return "off the surface map (" + t.getX() + "," + t.getY() + "," + t.getZ() + ")";
        if (leavingMorytania(bot.getLocation(), t) && Ledger.Wallet.coins(bot) < fare()) return "can't afford the teleport out of Morytania";
        if (Ledger.Wallet.coins(bot) < 10 && NavPlanner.needsToll(bot.getLocation(), t)) return "can't pay the Al Kharid toll"; // batch 21 A; review fix 2: kept for a real toll-only target (never true today: the fence's north end joins both sides, so broke bots walk round it)
        String wild = wilderness(bot.getLocation(), t);
        if (wild != null) return wild; // batch 21 B6
        String isl = island(bot.getLocation(), t);
        if (isl != null) return isl;
        return null;
    }

    static final boolean WILD_ON = !"false".equals(System.getProperty("preflight.wild"));

    /**
     * batch 21 B6: t is past the Wilderness ditch and the bot is not, or null. Bots never cross the ditch (HuntMap and the
     * job spots leave the Wilderness out); the planner never had a way over it either, and with void tiles open it sent
     * walks to the Wilderness shops (Battle Runes, the cape shops) to the void west of the Gnome Stronghold.
     * -Dpreflight.wild=false turns it off.
     */
    static String wilderness(Location here, Location t) {
        if (!WILD_ON || here == null || t == null || t.getZ() != 0 || t.getY() > 6400) return null;
        if (Heat.wildLevel(t.getX(), t.getY()) <= 0 || Heat.wildLevel(here.getX(), here.getY()) > 0) return null;
        return "past the Wilderness ditch (bots don't cross it)";
    }

    static final boolean ISLANDS_ON = !"false".equals(System.getProperty("preflight.islands"));
    static final int ISLAND_MAX = 50_000; // tiles: the mainland region is ~285,000; Karamja's pieces, Morytania (~43,000) have their own rules

    /**
     * batch 21 (map verification): t is in a small region of its own that the walker can't reach from here (Crandor 1,901
     * tiles, the Void Knights' outpost 597, Pirates' Cove 321, a ladder-only pocket at 2800-2828, 3500-3518), or null. Karamja
     * (boat or teleport) and Morytania (the Salve rules) are not refused here.
     */
    static String island(Location here, Location t) {
        if (!ISLANDS_ON || !NavPlanner.built || here == null || t == null || offGrid(t) || offGrid(here)) return null;
        Location ts = NavPlanner.standNear(t), hs = NavPlanner.standNear(here);
        if (ts == null || hs == null) return null;
        int rt = NavPlanner.regionAt(ts), rh = NavPlanner.regionAt(hs);
        if (rt == 0 || rh == 0 || rt == rh || NavPlanner.regionSize(rt) >= ISLAND_MAX) return null;
        if (AfkTasks.Runner.karamja(t) || morytania(t)) return null;
        if (AfkTasks.Runner.passJoins(rh, rt)) return null; // batch 21 B2: the desert is through the Shantay Pass (passVia, passHop)
        return "on an island or pocket the walker can't reach (" + NavPlanner.regionSize(rt) + " tiles)";
    }

    /**
     * batch 21 (map verification): why this bot, about to walk to target, can't walk out of where it stands, or null. Stranded
     * in a small region the target isn't in (3 live Crandor bots on a skeleton hunt); or inside a guild-sized gate box whose
     * rule it fails: the Warriors' Guild door (15653) runs the 130 check on everyone who opens it, from either side, so an
     * under-level bot inside can't leave (61 live records started inside, bots at combat 48-70).
     */
    static String stranded(Player bot, Location target) {
        if (!ISLANDS_ON || bot == null || target == null || !bot.isArtificial()) return null;
        Location here = bot.getLocation();
        if (offGrid(here)) return null; // the off-grid rules
        String box = boxStranded(bot, here, target);
        if (box != null) return box;
        if (!NavPlanner.built || offGrid(target)) return null;
        Location hs = NavPlanner.standNear(here), ts = NavPlanner.standNear(target);
        if (hs == null || ts == null) return null;
        int rh = NavPlanner.regionAt(hs), rt = NavPlanner.regionAt(ts);
        if (rh == 0 || rt == 0 || rh == rt || NavPlanner.regionSize(rh) >= ISLAND_MAX) return null;
        if (AfkTasks.Runner.karamja(here) || morytania(here)) return null;
        if (AfkTasks.Runner.passJoins(rh, rt)) return null; // batch 21 B2: out of the desert through the Shantay Pass
        return "stranded in a region of " + NavPlanner.regionSize(rh) + " tiles";
    }

    /**
     * review fix 3: inside a guild-sized gate box whose rule the bot fails, with the target outside it. Only boxes checked
     * against the walls count (Gate.walled: the Warriors' Guild today). The other guild boxes (Cooks', Crafting, Wizards',
     * Ranging, Fishing, Champions', Heroes', Legends', Mining) are hand-drawn with a margin over the street, so a bot on a
     * public road inside one would pay a teleport for nothing. TestB21G BOX floods every guild box to show which tiles.
     */
    static String boxStranded(Player bot, Location here, Location target) {
        for (Gates.Gate g : Gates.at(here)) if (g.walled && (g.x1 - g.x0) * (g.y1 - g.y0) < 10_000 && !g.contains(target) && !g.rule.ok(bot)) return "inside " + g.name + " without " + g.rule.need();
        return null;
    }

    /** Pick time: may this bot choose t as a goal, shop or job target? */
    static boolean ok(Player bot, Location t) {
        if (!ON) return true;
        String why = refuse(bot, t);
        if (why != null && Boolean.getBoolean("afk.debug") && bot != null) System.out.println("[Preflight] " + bot.getUsername() + " skips " + t + ": " + why);
        return why == null;
    }

    enum Go { CLEAR, BUSY, FAILED }

    static final Map<String, QuestFloors.Climber> CLIMB = new ConcurrentHashMap<>();

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    static void forget(Player p) { if (p != null) CLIMB.remove(key(p)); }

    /**
     * Walk time, called before each step of a surface walk to {@code target}. CLEAR: walk on. BUSY: a teleport or a climb
     * is under way, call again next tick. FAILED: the bot can't get out (broke in Morytania, no stair and no fare
     * upstairs): give the walk up.
     */
    static Go out(Player bot, Location target) {
        if (!ON || bot == null || target == null || !bot.isArtificial()) return Go.CLEAR;
        if (bot.getProperties().getTeleportLocation() != null) return Go.BUSY; // one already paid for: lands next tick
        if (offGrid(target)) { forget(bot); return Go.CLEAR; } // the caller's own way in (dungeon entrance, quest floors)
        Location here = bot.getLocation();
        if (leavingMorytania(here, target)) {
            forget(bot);
            if (Ledger.Wallet.payTeleport(bot)) { tele(bot, target, "out of Morytania"); return Go.BUSY; }
            try { AfkTasks.Runner.tollNote(bot, "salve", fare()); } catch (Throwable ignored) { }
            return Go.FAILED;
        }
        String str = stranded(bot, target);
        if (str != null) { // batch 21: a paid teleport out, as a player would
            forget(bot);
            if (Ledger.Wallet.payTeleport(bot)) { tele(bot, target, "out (" + str + ")"); return Go.BUSY; }
            return Go.FAILED;
        }
        if (here.getY() > 6400) {
            forget(bot);
            if (Ledger.Wallet.payTeleport(bot)) { tele(bot, target, "out of the dungeon"); return Go.BUSY; }
            return Go.CLEAR; // broke: the old walk, as before batch 20
        }
        if (here.getZ() > 0) {
            QuestFloors.Climber c = CLIMB.computeIfAbsent(key(bot), k -> new QuestFloors.Climber());
            QuestSteps.St st;
            try { st = c.tick(bot, Location.create(here.getX(), here.getY(), 0)); } catch (Throwable t) { st = QuestSteps.St.FAILED; }
            if (st == QuestSteps.St.RUNNING) return Go.BUSY;
            forget(bot);
            if (bot.getLocation().getZ() == 0) return Go.CLEAR;
            if (Ledger.Wallet.payTeleport(bot)) { tele(bot, target, "down (" + c.last + ")"); return Go.BUSY; }
            return Go.FAILED;
        }
        forget(bot);
        return Go.CLEAR;
    }

    static void tele(Player bot, Location target, String why) {
        Location land = Watchdog.landing(target);
        try { bot.getPulseManager().clear(); } catch (Throwable ignored) { }
        Spectacle.teleport(bot, land);
        System.out.println("[Preflight] " + bot.getUsername() + " teleports " + why + " to " + land + " for " + target);
    }
}
