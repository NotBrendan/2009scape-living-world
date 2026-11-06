package content.custom;

import core.ServerConstants;
import core.game.interaction.IntType;
import core.game.node.entity.player.Player;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Floors for the quest walker (wave 2, 2026-10-23). A walk whose target is on another floor, or underground when the
 * bot is on the surface (or the other way), climbs first: it picks a staircase, ladder or trapdoor that goes the right
 * way, walks to it, clicks it as the client would, and checks that the floor changed. Candidates come from the stair
 * surveyors' records (data/survey/stairs.jsonl: climbs that worked) and from a scan of the objects around the target
 * and the bot; one that doesn't take the bot anywhere is struck off for that walk and the next is tried.
 *
 * Planes: z (0 ground, 1 first floor, 2 second) and "underground" = y above 6400 (dungeons and basements sit 6400
 * tiles north of the land above them).
 */
final class QuestFloors {
    private QuestFloors() { }

    static boolean under(Location l) { return l.getY() > 6400; }

    /** Same floor: the same z and both above or both below ground. */
    static boolean samePlane(Location a, Location b) { return a.getZ() == b.getZ() && under(a) == under(b); }

    /** The target as seen from the bot's plane: underground targets have the land above them 6400 tiles south. */
    static Location projected(Location here, Location dest) {
        int y = dest.getY();
        if (under(dest) && !under(here)) y -= 6400;
        else if (!under(dest) && under(here)) y += 6400;
        return Location.create(dest.getX(), y, here.getZ());
    }

    /** Which way to go: true = up. A bot upstairs bound for a basement goes down first. */
    static boolean goUp(Location here, Location dest) {
        if (under(here) != under(dest)) return under(here); // out of a dungeon: up; into one (from any floor): down
        return dest.getZ() > here.getZ();
    }

    static final class Rec { int x, y, z, id; String option; }
    static volatile List<Rec> KNOWN;

    /** Climbs the stair surveyors saw work (up, down, into or out of a dungeon), loaded once. */
    static List<Rec> known() {
        List<Rec> k = KNOWN;
        if (k != null) return k;
        List<Rec> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File f = new File(new File(ServerConstants.DATA_PATH, "survey"), "stairs.jsonl");
        if (f.exists()) try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            JSONParser p = new JSONParser();
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    JSONObject j = (JSONObject) p.parse(line);
                    String res = String.valueOf(j.get("result"));
                    if (!res.startsWith("up") && !res.startsWith("down")) continue;
                    JSONArray at = (JSONArray) j.get("at");
                    Rec c = new Rec();
                    c.x = ((Number) at.get(0)).intValue(); c.y = ((Number) at.get(1)).intValue(); c.z = ((Number) at.get(2)).intValue();
                    c.id = ((Number) j.get("id")).intValue(); c.option = String.valueOf(j.get("option"));
                    if (seen.add(c.x + "," + c.y + "," + c.z + "#" + c.id + c.option)) out.add(c);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) { System.out.println("[Quests] stairs.jsonl: " + t); }
        KNOWN = out;
        System.out.println("[Quests] " + out.size() + " known climbs loaded for the floor walker");
        return out;
    }

    static boolean climbOption(String o, boolean up) {
        String l = o.toLowerCase(Locale.ROOT);
        return up ? l.equals("climb-up") || l.equals("go-up") || l.equals("walk-up") : l.equals("climb-down") || l.equals("go-down") || l.equals("walk-down"); // walk-up/down: Draynor Manor's basement stairs
    }

    static String optionOf(Scenery s, boolean up) {
        try { for (core.game.interaction.Option o : s.getInteraction().getOptions()) if (o != null && climbOption(o.getName(), up)) return o.getName(); } catch (Throwable ignored) { }
        return null;
    }

    /** Gets a bot onto the plane of {@code dest}; the Mover walks the rest. */
    static final class Climber {
        Scenery stair; String option; Location dest; int clicks, waited, picks; String last = "";
        final Set<String> tried = new HashSet<>();
        final QuestSteps.Mover walk = new QuestSteps.Mover();
        Location before;

        int misses, attempts;
        void reset() { stair = null; option = null; clicks = 0; waited = 0; picks = 0; misses = 0; tried.clear(); before = null; }

        QuestSteps.St tick(Player bot, Location target) {
            Location here = bot.getLocation();
            if (dest == null || !dest.equals(target)) { reset(); dest = target; }
            if (before != null && !samePlane(before, here)) { // the last climb took us somewhere
                last = "climbed to " + here; stair = null; clicks = 0; waited = 0; before = null;
                return QuestSteps.St.RUNNING;
            }
            boolean up = goUp(here, target);
            if (stair == null) {
                if (++picks > 4) { last = "no stair took me " + (up ? "up" : "down") + " (tried " + tried + ")"; return QuestSteps.St.FAILED; }
                stair = pick(bot, here, target, up);
                if (stair == null && ++misses < 10) { picks--; last = "looking for a " + (up ? "way up" : "way down"); return QuestSteps.St.RUNNING; } // the map may still be loading round a fresh bot
                if (stair == null && under(here) && !under(target) && Ledger.Wallet.payTeleport(bot)) { // a bot deep in a dungeon (between hunts) when the quest begins: no way up in sight, so it teleports out as it would for any long trip (live 10-23: "no way up found near [3211, 9825]")
                    bot.getPulseManager().clear(); Spectacle.teleport(bot, Watchdog.landing(target)); misses = 0; picks = 0; last = "teleported out of the dungeon"; return QuestSteps.St.RUNNING; // batch 15 item1
                }
                if (stair == null) { last = "no " + (up ? "way up" : "way down") + " found near " + projected(here, target); return QuestSteps.St.FAILED; }
                option = optionOf(stair, up); attempts = 0;
                walk.reset(stair.getLocation(), 3); clicks = 0; waited = 0;
                last = "heading for the " + stair.getName() + " at " + stair.getLocation();
            }
            if (clicks == 0) {
                double d = here.getDistance(stair.getLocation());
                if (d > 3) { // up to it, the walker opening doors on the way; 3 tiles is "there" (a stair tile can't always be stood next to: Draynor Manor's first floor stalled at 3)
                    QuestSteps.St w = walk.tick(bot);
                    if (w == QuestSteps.St.RUNNING) return w;
                    if (w == QuestSteps.St.FAILED && d > 8) { strike("couldn't reach it: " + walk.last); return QuestSteps.St.RUNNING; }
                }
                before = here;
                QuestSteps.clearPopups(bot);
                if (!QuestSteps.click(bot, stair, option, IntType.SCENERY)) { strike("no " + option); return QuestSteps.St.RUNNING; }
                clicks = 1; waited = 0; last = option + " on the " + stair.getName() + " at " + stair.getLocation();
                return QuestSteps.St.RUNNING;
            }
            if (bot.getWalkingQueue().isMoving() || bot.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (++waited < 8) return QuestSteps.St.RUNNING;
            if (clicks == 1 && here.getDistance(stair.getLocation()) <= 3) { // the click didn't take: the climb handler directly, as its listener would (as Interact does)
                boolean ok = false;
                try { ok = core.game.global.action.ClimbActionHandler.climbLadder(bot, stair, option.toLowerCase(Locale.ROOT)); } catch (Throwable ignored) { }
                if (!ok) {
                    Location to = null;
                    try { to = core.game.global.action.ClimbActionHandler.getDestination(stair); } catch (Throwable ignored) { }
                    if (to != null) { core.game.global.action.ClimbActionHandler.climb(bot, up ? core.game.global.action.ClimbActionHandler.CLIMB_UP : core.game.global.action.ClimbActionHandler.CLIMB_DOWN, to); ok = true; }
                }
                clicks = 2; waited = 0; last = "climb fallback on the " + stair.getName() + (ok ? "" : " (no destination)");
                return QuestSteps.St.RUNNING;
            }
            if (clicks == 1) { before = here; QuestSteps.click(bot, stair, option, IntType.SCENERY); clicks = 2; waited = 0; return QuestSteps.St.RUNNING; } // walked up to it on the first click: once more
            if (++attempts < 3) { // a shut door between us and it, most likely: open the nearest, walk up again, click again
                QuestSteps.openDoorToward(bot, stair.getLocation(), 6);
                clicks = 0; waited = 0; walk.reset(stair.getLocation(), 3); last = "a door in the way of the " + stair.getName() + "?";
                return QuestSteps.St.RUNNING;
            }
            strike("it didn't take me anywhere");
            return QuestSteps.St.RUNNING;
        }

        void strike(String why) {
            if (stair != null) tried.add(stair.getId() + "@" + stair.getLocation());
            last = "the " + (stair != null ? stair.getName() + " at " + stair.getLocation() : "stair") + ": " + why;
            stair = null; before = null;
        }

        /** The stair going the right way that is closest to the target (seen from this floor), a little biased towards the bot. */
        Scenery pick(Player bot, Location here, Location target, boolean up) {
            Location ref = projected(here, target);
            Scenery best = null; double bs = Double.MAX_VALUE;
            for (Rec c : known()) { // climbs that worked for a surveyor
                if (c.z != here.getZ() || (c.y > 6400) != under(here) || !climbOption(c.option, up)) continue;
                if (Math.hypot(c.x - ref.getX(), c.y - ref.getY()) > 40) continue;
                Scenery s = null;
                try { s = RegionManager.getObject(c.z, c.x, c.y, c.id); } catch (Throwable ignored) { }
                if (s == null || tried.contains(s.getId() + "@" + s.getLocation()) || optionOf(s, up) == null) continue;
                double sc = s.getLocation().getDistance(ref) + 0.2 * s.getLocation().getDistance(here) - 3; // a proven climb gets a small head start
                if (sc < bs) { bs = sc; best = s; }
            }
            for (Location c : new Location[]{ref, here}) { // and whatever stands around the target and the bot
                int r = c == ref ? 15 : 10;
                for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
                    Scenery s;
                    try { s = RegionManager.getObject(here.getZ(), c.getX() + dx, c.getY() + dy); } catch (Throwable t) { continue; }
                    if (s == null || !s.isActive() || s.getName() == null) continue;
                    String n = s.getName().toLowerCase(Locale.ROOT);
                    if (!n.contains("stair") && !n.contains("ladder") && !n.contains("trapdoor")) continue;
                    if (tried.contains(s.getId() + "@" + s.getLocation()) || optionOf(s, up) == null) continue;
                    double sc = s.getLocation().getDistance(ref) + 0.2 * s.getLocation().getDistance(here);
                    if (sc < bs) { bs = sc; best = s; }
                }
            }
            if (best == null) for (Location c : new Location[]{ref, here}) { // the per-tile lookup can miss objects the region hasn't handed out yet (as QuestSteps.findScenery found): its object list
                try {
                    for (Scenery s : RegionManager.getRegionPlane(c).getObjectList()) {
                        if (s == null || s.getName() == null || s.getLocation() == null || s.getLocation().getZ() != here.getZ() || s.getLocation().getDistance(c) > 15) continue;
                        String n = s.getName().toLowerCase(Locale.ROOT);
                        if (!n.contains("stair") && !n.contains("ladder") && !n.contains("trapdoor")) continue;
                        if (tried.contains(s.getId() + "@" + s.getLocation()) || optionOf(s, up) == null) continue;
                        double sc = s.getLocation().getDistance(ref) + 0.2 * s.getLocation().getDistance(here);
                        if (sc < bs) { bs = sc; best = s; }
                    }
                } catch (Throwable ignored) { }
            }
            if (best == null && under(here) != under(target)) { // pathing review 10-23 Q-2: into or out of a dungeon, the target's projection is
                // often no dungeon at all (Taverley dungeon at 2903,9799 bound for the goblin generals looked for a way up near
                // 2957,9911), and the bot's own scan is only 10 tiles: the nearest way up or down near the bot itself, proven climbs
                // within 60 tiles first, then anything in the bot's region within 40
                for (Rec c : known()) {
                    if (c.z != here.getZ() || (c.y > 6400) != under(here) || !climbOption(c.option, up)) continue;
                    if (Math.hypot(c.x - here.getX(), c.y - here.getY()) > 60) continue;
                    Scenery s = null;
                    try { s = RegionManager.getObject(c.z, c.x, c.y, c.id); } catch (Throwable ignored) { }
                    if (s == null || tried.contains(s.getId() + "@" + s.getLocation()) || optionOf(s, up) == null) continue;
                    double sc = s.getLocation().getDistance(here);
                    if (sc < bs) { bs = sc; best = s; }
                }
                if (best == null) try {
                    for (Scenery s : RegionManager.getRegionPlane(here).getObjectList()) {
                        if (s == null || s.getName() == null || s.getLocation() == null || s.getLocation().getZ() != here.getZ() || s.getLocation().getDistance(here) > 40) continue;
                        String n = s.getName().toLowerCase(Locale.ROOT);
                        if (!n.contains("stair") && !n.contains("ladder") && !n.contains("trapdoor")) continue;
                        if (tried.contains(s.getId() + "@" + s.getLocation()) || optionOf(s, up) == null) continue;
                        double sc = s.getLocation().getDistance(here);
                        if (sc < bs) { bs = sc; best = s; }
                    }
                } catch (Throwable ignored) { }
            }
            return best;
        }
    }
}
