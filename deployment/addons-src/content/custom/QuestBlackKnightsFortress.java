package content.custom;

import core.game.bots.AIPlayer;
import core.game.interaction.IntType;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;

import java.util.function.Predicate;

import static content.custom.QuestScript.attrInt;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Black Knights' Fortress: Sir Amik Varze (608, Falador castle second floor, spawn 2962,3337,2) -> the fortress on Ice
 * Mountain in a guard's disguise -> listen at the grill -> a cabbage down the hole -> back to Sir Amik.
 * Server files: content/region/asgarnia/falador/quest/blackknightsfortress/ (BlackKnightsFortress.java,
 * SirAmikVarzeDialogue.java, FortressGuardDialogue.java, BKFortressPlugin.java, BKListenDialogue.java, BKCabbagePlugin.java).
 *
 * Requirement (SirAmikVarzeDialogue stage 0): 12 quest points, else he only offers "I don't I'm just looking around."
 * Start (SirAmikVarzeDialogue): option 1 ("I seek a quest!"), option 1 ("I laugh in the face of danger!"), option 1
 * ("Ok, I'll do my best."): the dossier (9589) and quest.start = stage 10 (Quest.start); with a full pack he says
 * "You don't have enough inventory space" and the quest does NOT start. Never read the dossier: its Read handler
 * (BKFortressPlugin) deletes it. Stages 10/20: continue only, no change.
 * Stage 30 (SirAmikVarzeDialogue): continue only: 2500 coins (pack, else the floor), quest.finish: 3 QP (BlackKnightsFortress
 * constructor: 3 QP), stage 100.
 *
 * Disguise (FortressGuardDialogue.UNIFORM / BKFortressPlugin 2337): bronze med helm (1139) AND iron chainbody (1101),
 * WORN (allInEquipment). Shops (data/configs/shops.json): Wayne's Chains (shop 58, npc 581 at 2975,3314) sells 1101,
 * Peksa's Helmet Shop (shop 98, npc 538 at 3076,3429) sells 1139.
 * Cabbage: a normal cabbage 1965 (BKCabbagePlugin: 1967, the Draynor Manor cabbage, is "the wrong sort of cabbage!").
 * Picked from "Cabbage" 1161 "Pick" (FieldPickingPlugin CABBAGE_0 1161 -> 1965); the patch next to the fortress is at
 * 3050-3053,3503-3505 (cache map). Wydin's Food Store (shop 77, npc 557 at 3012,3203) sells 1965.
 *
 * The fortress (BKFortressPlugin; positions from the cache map, rooms from a wall flood-fill of the cache objects):
 *  - Sturdy door 2337 "Open" at 3016,3514,0 (wall on its north edge): from y 3514 (outside) only with the disguise worn
 *    (else guard 4604 talks), from y 3515 (inside) always. Leads into the entrance hall (3008-3019,3513-3518).
 *  - Wall 2341 "Push" at 3016,3517,0 (south edge): hall <-> the ladder room 3015-3016,3517-3519.
 *  - Ladders 17148 "Climb-up" / 17149 "Climb-down". BKFortressPlugin fixes these destinations: 17148 at 3015,3519,0 ->
 *    3015,3518,1; 17148 at 3021,3510,0 -> 3022,3510,1; 17149 at 3017,3516,2 -> 3017,3515,1; 17149 at 3016,3519,2 ->
 *    3016,3518,1; 17149 at 3015,3519,1 -> 3015,3518,0; 17149 at 3023,3513,2 / 3025,3513,2 -> one tile south on z1.
 *    The rest (17148 at 3016,3519,1 and 3017,3516,1, 17149 at 3021,3510,1, 17159 at 3022,3518,0) use the server's
 *    generic ClimbActionHandler.climbLadder.
 *  - Grill 2342 "Listen-at" at 3026,3507,0, in the grill room (3021-3026,3510 and 3025-3026,3506-3509), reached only
 *    down the ladder 17149 at 3021,3510,1. Listening opens BKListenDialogue (992752973, continue only): stage 20.
 *  - Door 2338 "Open" at 3020,3515,0 (west edge): from the hall (x <= 3019) guard 4605 warns (FortressGuardDialogue,
 *    3 args): option 2 ("I don't care. I'm going in anyway.") opens it and a Black Knight 179 attacks; from the meeting
 *    room (x > 3019) it just opens. The meeting room (3020-3030,3511-3518, z0) holds five aggressive Black Knights 179
 *    (npc_spawns.json; 42 hp, attack/strength/defence 25, npc_configs.json).
 *  - Ladder 17159 "Climb-up" at 3022,3518,0 -> the north corridor on z1 (3022-3029,3517-3518 and 3030,3510-3516);
 *    ladder 17160 "Climb-down" at 3022,3518,1 -> 3022,3517,0 (BKFortressPlugin).
 *  - Wall 2341 "Push" at 3030,3510,1 (south edge): north corridor <-> the hole room (3026-3032,3504-3509, z1).
 *  - Hole 2336 at 3031,3507,1 (no options): cabbage 1965 on it at stage 20 (BKCabbagePlugin, a legacy UseWithHandler)
 *    opens BKListenDialogue with 2 args (continue only); its last screen takes the cabbage and sets stage 30.
 * Route in: door, push the wall, up, up, down, up, down, the upper sturdy door 2339, down, listen (see NOOK); back the same way, push the wall,
 * through the guard's door, up from the meeting room, push the wall, cabbage. Out: push the wall back, down the ladder
 * 17160, the meeting-room door, the sturdy door.
 *
 * Every fortress step runs inside {@link Guarded}: auto-retaliate off for the step (so a knight's hit doesn't cancel the
 * click's walk) and food eaten at half hitpoints. Checkpoints are "which room am I in" counters, so a bot restored
 * anywhere on the route carries on from there; one that died outside walks back in through the sturdy door.
 * Rewards: 2500 coins, 3 QP. No XP.
 */
final class QuestBlackKnightsFortress {
    private QuestBlackKnightsFortress() { }

    static final content.data.Quests Q = content.data.Quests.BLACK_KNIGHTS_FORTRESS;
    static final int AMIK = 608;
    static final int CHAINBODY = 1101, HELM = 1139, CABBAGE = 1965;
    static final int STURDY_DOOR = 2337, GUARD_DOOR = 2338, PUSH_WALL = 2341, GRILL = 2342, HOLE = 2336;
    static final int LADDER_UP = 17148, LADDER_DOWN = 17149, MEETING_LADDER_UP = 17159, MEETING_LADDER_DOWN = 17160;
    static final int CABBAGE_PATCH = 1161;
    static final int HEAD = 0, BODY = 4;                                 // equipment slots
    static final String OLD_HEAD = "quest-bkf:old-head", OLD_BODY = "quest-bkf:old-body";

    static final Location AMIK_AT = Location.create(2962, 3337, 2);      // his spawn tile (walks 1), castle second floor
    static final Location WAYNE = Location.create(2973, 3312, 0);        // Wayne's Chains, Falador (581 at 2975,3314)
    static final Location PEKSA = Location.create(3076, 3427, 0);        // Peksa's Helmet Shop, Barbarian Village (538 at 3076,3429)
    static final Location WYDIN = Location.create(3013, 3204, 0);        // Wydin's Food Store, Port Sarim (557 at 3012,3203)
    static final Location PATCH_SIDE = Location.create(3052, 3502, 0);   // south of the cabbage patch 3050-3053,3503-3505
    static final Location PATCH = Location.create(3051, 3504, 0);
    static final Location DOOR_OUTSIDE = Location.create(3016, 3513, 0); // south of the sturdy door 2337 (3016,3514)
    static final Location STURDY_AT = Location.create(3016, 3514, 0);
    static final Location WALL_HALL = Location.create(3016, 3517, 0);
    static final Location WALL_HOLE = Location.create(3030, 3510, 1);
    static final Location GUARD_DOOR_AT = Location.create(3020, 3515, 0);
    static final Location GRILL_AT = Location.create(3026, 3507, 0);
    static final Location HOLE_AT = Location.create(3031, 3507, 1);

    // ------------------------------------------------------------------ rooms (rows of {y, x0, x1}, from a flood-fill of the cache walls)

    static final int[][] HALL = {{3513, 3009, 3011}, {3513, 3019, 3019}, {3514, 3008, 3013}, {3514, 3019, 3019}, {3515, 3008, 3019}, {3516, 3008, 3019}, {3517, 3008, 3012}, {3517, 3019, 3019}, {3518, 3009, 3011}};
    static final int[][] LADDER_ROOM = {{3517, 3015, 3016}, {3518, 3015, 3016}, {3519, 3015, 3016}};          // z0 behind the push wall; z1 the same box above it
    static final int[][] GRILL_ROOM = {{3506, 3025, 3026}, {3507, 3025, 3026}, {3508, 3025, 3026}, {3509, 3025, 3026}, {3510, 3021, 3026}};
    static final int[][] MEETING = {{3511, 3021, 3030}, {3512, 3021, 3030}, {3513, 3020, 3030}, {3514, 3020, 3030}, {3515, 3020, 3030}, {3516, 3020, 3030}, {3517, 3020, 3029}, {3518, 3020, 3028}};
    static final int[][] WEST1 = {{3506, 3025, 3025}, {3507, 3025, 3025}, {3508, 3025, 3025}, {3509, 3025, 3025}, {3510, 3021, 3025}, {3511, 3021, 3025}, {3512, 3021, 3025}, {3513, 3019, 3021}, {3514, 3019, 3019}, {3515, 3015, 3019}, {3516, 3015, 3019}, {3517, 3019, 3021}, {3518, 3020, 3021}};
    static final int[][] MID1 = {{3513, 3022, 3024}, {3514, 3020, 3024}, {3515, 3020, 3024}, {3516, 3021, 3024}};
    static final int[][] EAST1 = {{3510, 3026, 3029}, {3511, 3026, 3029}, {3512, 3026, 3029}, {3513, 3025, 3029}, {3514, 3025, 3029}, {3515, 3025, 3028}, {3516, 3025, 3027}};
    static final int[][] NORTH1 = {{3510, 3030, 3030}, {3511, 3030, 3030}, {3512, 3030, 3030}, {3513, 3030, 3030}, {3514, 3030, 3030}, {3515, 3030, 3030}, {3516, 3029, 3030}, {3517, 3022, 3029}, {3518, 3022, 3028}};
    static final int[][] HOLE_ROOM = {{3504, 3028, 3030}, {3505, 3027, 3031}, {3506, 3026, 3032}, {3507, 3026, 3032}, {3508, 3026, 3031}, {3509, 3026, 3030}};

    static boolean in(Player p, int z, int[][] rows) {
        Location l = p.getLocation();
        if (l.getZ() != z || l.getY() > 6400) return false;
        for (int[] r : rows) if (l.getY() == r[0] && l.getX() >= r[1] && l.getX() <= r[2]) return true;
        return false;
    }

    static boolean upper(Player p) { Location l = p.getLocation(); return l.getZ() >= 2 && l.getX() >= 3008 && l.getX() <= 3032 && l.getY() >= 3503 && l.getY() <= 3520; }

    static boolean inFortress(Player p) {
        return in(p, 0, HALL) || in(p, 0, LADDER_ROOM) || in(p, 0, GRILL_ROOM) || in(p, 0, MEETING) || in(p, 1, LADDER_ROOM) || in(p, 1, WEST1)
                || in(p, 1, MID1) || in(p, 1, EAST1) || in(p, 1, NORTH1) || in(p, 1, HOLE_ROOM) || upper(p);
    }

    static boolean outside(Player p) { return !inFortress(p); }

    // bk-trial2 (both1): the first-floor room the ladder 17149 at 3017,3516,2 lands in (3015-3019,3515-3516, then MID1 through
    // the door 14749 at 3019,3515,1) does not reach the grill-room ladder 17149 at 3021,3510,1: that ladder stands in a nook
    // (NOOK) walled on three sides, its only way in the sturdy door 2339 at 3025,3511,1 from the east wing (EAST1), barrels
    // 17153 blocking the corner at 3020,3513. The bot clicked it from 3022-3024,3513 five times. The route is: up the ladder
    // 17148 at 3023,3513,1 (MID1) to the middle of the top floor, down 17149 at 3025,3513,2 (-> 3025,3514,1, EAST1), through
    // 2339 (no door config: an auto-walk door), down 17149 at 3021,3510,1. Back the same way.
    static final int[][] NOOK = {{3510, 3021, 3025}, {3511, 3021, 3025}, {3512, 3021, 3025}, {3513, 3021, 3021}};
    static final int STURDY_DOOR_UP = 2339;

    static boolean topWest(Player p) { return upper(p) && p.getLocation().getZ() == 2 && p.getLocation().getX() <= 3018; }
    static boolean topMiddle(Player p) { return upper(p) && p.getLocation().getZ() == 2 && p.getLocation().getX() >= 3019; }
    static boolean westOrMid1(Player p) { return !in(p, 1, NOOK) && (in(p, 1, WEST1) || in(p, 1, MID1)); }

    /** Where a stage-10 bot is on the way to the grill: 0 outside (or somewhere off the route), 1 hall ... 9 grill room. */
    static int legGrill(Player p) {
        if (in(p, 0, GRILL_ROOM)) return 9;
        if (in(p, 1, NOOK)) return 8;
        if (in(p, 1, EAST1)) return 7;
        if (topMiddle(p)) return 6;
        if (westOrMid1(p)) return 5;
        if (topWest(p)) return 4;
        if (in(p, 1, LADDER_ROOM)) return 3;
        if (in(p, 0, LADDER_ROOM)) return 2;
        if (in(p, 0, HALL)) return 1;
        return 0;
    }

    /** Where a stage-20 bot is on the way to the hole: 0 grill room ... 11 hole room; outside counts as 7 (it comes in by the sturdy door into the hall). */
    static int legHole(Player p) {
        if (in(p, 1, HOLE_ROOM)) return 11;
        if (in(p, 1, NORTH1)) return 10;
        if (in(p, 0, MEETING)) return 9;
        if (in(p, 0, HALL)) return 8;
        if (in(p, 0, LADDER_ROOM) || outside(p)) return 7;
        if (in(p, 1, LADDER_ROOM)) return 6;
        if (topWest(p)) return 5;
        if (westOrMid1(p)) return 4;
        if (topMiddle(p)) return 3;
        if (in(p, 1, EAST1)) return 2;
        if (in(p, 1, NOOK)) return 1;
        if (in(p, 0, GRILL_ROOM)) return 0;
        return -1;
    }

    /** Where a stage-30 bot is on the way out: 0 hole room, 1 north corridor, 2 meeting room, 3 hall, 4 outside. */
    static int legOut(Player p) {
        if (outside(p)) return 4;
        if (in(p, 0, HALL)) return 3;
        if (in(p, 0, MEETING)) return 2;
        if (in(p, 1, NORTH1)) return 1;
        if (in(p, 1, HOLE_ROOM)) return 0;
        return -1;
    }

    static boolean disguised(Player p) { return p.getEquipment().contains(HELM, 1) && p.getEquipment().contains(CHAINBODY, 1); }
    static boolean worn(Player p, int id) { return p.getEquipment().contains(id, 1); }

    // ------------------------------------------------------------------ helpers

    /**
     * Wraps a fortress step: auto-retaliate off while it runs (a knight's hit makes a retaliating bot fight and drops the
     * click's walk, five times over) and food eaten at half hitpoints, as the fight runner does.
     */
    static final class Guarded extends QuestSteps.Step {
        final QuestSteps.Step in; int cool; Boolean retaliated;
        Guarded(QuestSteps.Step in) { super(in.name, in.limitTicks); this.in = in; this.xpStep = in.xpStep; this.recheck = in.recheck; }
        @Override void begin(QuestRunner r) {
            super.begin(r); cool = 0;
            try { retaliated = r.bot.getProperties().isRetaliating(); r.bot.getProperties().setRetaliating(false); } catch (Throwable ignored) { }
            in.begin(r);
        }
        @Override QuestSteps.St tick(QuestRunner r) {
            Player p = r.bot;
            if (cool > 0) cool--;
            int hp = p.getSkills().getLifepoints(), max = p.getSkills().getMaximumLifepoints();
            if (cool == 0 && hp * 2 <= max) {
                for (Item it : p.getInventory().toArray()) {
                    if (it == null || !QuestSteps.contains(AfkTasks.Runner.FOOD, it.getId())) continue;
                    try { new core.game.bots.ScriptAPI(p).forceEat(it.getId()); } catch (Throwable ignored) { }
                    cool = 4;
                    break;
                }
            }
            // A door's auto-walk locks the bot for 4 ticks (DoorActionHandler lock(4)) and a click made meanwhile is dropped
            // (InteractPlugin: interaction locked). bk-trial1: the push right after the sturdy door was lost that way.
            if (p.getLocks().isInteractionLocked() || p.getLocks().isMovementLocked()) return QuestSteps.St.RUNNING;
            QuestSteps.St s = in.tick(r);
            if (s == QuestSteps.St.FAILED) failure = in.failure;
            return s;
        }
        @Override void end(QuestRunner r) {
            try { in.end(r); } catch (Throwable ignored) { }
            try { if (retaliated != null) r.bot.getProperties().setRetaliating(retaliated); } catch (Throwable ignored) { }
            retaliated = null;
        }
        @Override String doing() { return in.doing(); }
        @Override String trace() { return in.trace(); }
    }

    static QuestSteps.Step guard(QuestSteps.Step s) { return new Guarded(s); }

    /**
     * The guard's door 2338 from the hall: the click, his warning and option 2 ("I don't care. I'm going in anyway."). both4
     * showed the warning's chatbox closing under the driver ("no chatbox, picks left [2]", seen FortressGuardDialogue@50)
     * twice running, after both3 had gone through in 17 ticks. If the talk fails, this does what option 2 does
     * (FortressGuardDialogue stage 54: handleAutowalkDoor on 3020,3515) from the tile in front of the door.
     */
    static final class GuardDoor extends QuestSteps.Step {
        final QuestSteps.Step click; boolean fallback; int wait, tries;
        GuardDoor(QuestSteps.Step click) { super(click.name, click.limitTicks); this.click = click; this.xpStep = click.xpStep; }
        @Override void begin(QuestRunner r) { super.begin(r); fallback = false; wait = 0; tries = 0; click.begin(r); }
        @Override QuestSteps.St tick(QuestRunner r) {
            Player p = r.bot;
            if (in(p, 0, MEETING)) return QuestSteps.St.DONE;
            if (!fallback) {
                QuestSteps.St s = click.tick(r);
                if (s != QuestSteps.St.FAILED) return s;
                r.log("  the guard's warning didn't go through (" + click.failure + "): opening the door as his option 2 does");
                fallback = true; wait = 2;
                QuestSteps.clearPopups(p);
                return QuestSteps.St.RUNNING;
            }
            if (locked(p) || p.getWalkingQueue().isMoving()) return QuestSteps.St.RUNNING;
            if (wait > 0) { wait--; return QuestSteps.St.RUNNING; }
            if (++tries > 4) return fail("guard's door: still at " + p.getLocation());
            Location front = Location.create(3019, 3515, 0);
            if (!p.getLocation().equals(front)) {
                try { if (p instanceof AIPlayer) ((AIPlayer) p).walkToPosSmart(front); } catch (Throwable ignored) { }
                wait = 2;
                return QuestSteps.St.RUNNING;
            }
            Scenery d = QuestSteps.findScenery(p, GUARD_DOOR_AT, 0, new int[]{GUARD_DOOR}, null, "Open");
            if (d == null) return fail("no guard's door at " + GUARD_DOOR_AT);
            QuestSteps.clearPopups(p);
            core.game.global.action.DoorActionHandler.handleAutowalkDoor(p, d);
            wait = 4;
            return QuestSteps.St.RUNNING;
        }
        @Override void end(QuestRunner r) { try { click.end(r); } catch (Throwable ignored) { } }
        @Override String trace() { return click.trace(); }
    }

    static QuestSteps.Interact climb(String name, int id, String option, Location at) {
        return new QuestSteps.Interact(name, new int[]{id}, null, option, at, 0, false);
    }

    static boolean locked(Player p) { return p.getLocks().isInteractionLocked() || p.getLocks().isMovementLocked(); }

    /**
     * Push a secret wall 2341 from the tile in front of it ({@code from}). bk-trial1 showed the plain Interact looping to its
     * time limit: its first click came while the sturdy door's auto-walk still had the bot locked, so it was dropped, and the
     * Interact's door rule then opened the nearest doors in turn: the sturdy door from inside walks you back out to y 3514
     * (BKFortressPlugin, DoorActionHandler.getEndLocation), the guard's door 2338 opens his warning. So this step never opens
     * a door: it waits out locks, walks to the tile, clicks Push, and after two clicks that didn't take calls the wall's own
     * handler action (BKFortressPlugin 2341: handleAutowalkDoor), as the climb fallback does for stairs.
     */
    static QuestSteps.Step push(String name, Location wall, Location from, Predicate<Player> until) {
        final int[] st = {0, 0, 0}; // clicks, wait, walks
        return new QuestSteps.Custom(name, 200, r -> { st[0] = 0; st[1] = 0; st[2] = 0; }, r -> {
            Player p = r.bot;
            if (until.test(p)) return QuestSteps.St.DONE;
            if (locked(p) || p.getWalkingQueue().isMoving()) return QuestSteps.St.RUNNING;
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            if (!p.getLocation().equals(from)) {
                if (p.getLocation().getZ() != from.getZ() || p.getLocation().getDistance(from) > 16 || ++st[2] > 15) { r.log("  " + name + ": can't get to " + from + " from " + p.getLocation()); return QuestSteps.St.FAILED; }
                try { if (p instanceof AIPlayer) ((AIPlayer) p).walkToPosSmart(from); } catch (Throwable ignored) { }
                st[1] = 2;
                return QuestSteps.St.RUNNING;
            }
            Scenery w = QuestSteps.findScenery(p, wall, 0, new int[]{PUSH_WALL}, null, "Push");
            if (w == null) { r.log("  " + name + ": no wall at " + wall); return QuestSteps.St.FAILED; }
            if (++st[0] > 5) { r.log("  " + name + ": 5 pushes from " + from + " didn't take"); return QuestSteps.St.FAILED; }
            QuestSteps.clearPopups(p);
            if (st[0] <= 2) QuestSteps.click(p, w, "Push", IntType.SCENERY);
            else { r.log("  push fallback on " + PUSH_WALL + "@" + wall); p.getPacketDispatch().sendMessage("You push against the wall. You find a secret passage."); core.game.global.action.DoorActionHandler.handleAutowalkDoor(p, w); }
            st[1] = 4;
            return QuestSteps.St.RUNNING;
        });
    }

    static QuestSteps.St wearDisguise(QuestRunner r) {
        Player p = r.bot;
        if (disguised(p)) return QuestSteps.St.DONE;
        QuestRunner.makeRoom(p, 3);
        int[][] parts = {{HEAD, HELM}, {BODY, CHAINBODY}};
        String[] keys = {OLD_HEAD, OLD_BODY};
        for (int i = 0; i < parts.length; i++) {
            int slot = parts[i][0], id = parts[i][1];
            if (worn(p, id)) continue;
            Item old = p.getEquipment().get(slot);
            if (old != null && old.getId() != id && attrInt(p, keys[i]) == 0) p.setAttribute(keys[i], old.getId());
            if (!QuestRestlessGhost.equip(p, id)) { r.log("  couldn't wear " + MarketBrain.name(id)); return QuestSteps.St.FAILED; }
        }
        if (disguised(p)) { r.log("  wearing the guard's disguise (bronze med helm, iron chainbody)"); return QuestSteps.St.DONE; }
        return QuestSteps.St.FAILED;
    }

    static QuestSteps.St restoreArmour(QuestRunner r) {
        Player p = r.bot;
        for (String k : new String[]{OLD_HEAD, OLD_BODY}) {
            int old = attrInt(p, k);
            p.removeAttribute(k);
            if (old <= 0 || worn(p, old)) continue;
            if (p.getInventory().getAmount(old) == 0 && p.getBank().getAmount(old) > 0 && p.getInventory().freeSlots() > 0 && p.getBank().remove(new Item(old, 1))) {
                if (!p.getInventory().add(new Item(old, 1))) p.getBank().add(new Item(old, 1));
            }
            if (p.getInventory().getAmount(old) > 0) QuestRestlessGhost.equip(p, old);
        }
        return QuestSteps.St.DONE;
    }

    static QuestScript build() {
        // Levels: five aggressive Black Knights (42 hp, 25 attack/strength) in the meeting room and more on the way; the
        // bot doesn't fight back (Guarded), it eats and climbs out of reach, so it needs hitpoints and some defence.
        QuestScript s = QuestScript.of(Q, "blackknights", "Black Knights' Fortress").startAt(2962, 3337, 2).minutes(35).coins(2500).xp(0).unlock(10)
                .qp(12).level(Skills.HITPOINTS, 30).level(Skills.DEFENCE, 20);

        s.step(new QuestSteps.Walk("walk up to Sir Amik Varze", AMIK_AT, 3).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 2 && p.getLocation().getDistance(AMIK_AT) <= 3));
        s.step(new QuestSteps.Custom("make room for the dossier", 5, null, r -> { QuestRunner.makeRoom(r.bot, 1); return QuestSteps.St.DONE; }).done(stageAtLeast(Q, 10))); // a full pack: no dossier, no quest
        s.step(new QuestSteps.Talk("ask Sir Amik for a quest", new int[]{AMIK}, 1, 1, 1).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        s.step(new QuestSteps.Collect("get an iron chainbody", CHAINBODY, 1)
                .buy(new QuestSteps.Buy("buy an iron chainbody at Wayne's Chains", CHAINBODY, 1, "Wayne's Chains", WAYNE))
                .done(p -> has(CHAINBODY, 1).test(p) || worn(p, CHAINBODY) || stageAtLeast(Q, 30).test(p)));
        s.step(new QuestSteps.Collect("get a bronze med helm", HELM, 1)
                .buy(new QuestSteps.Buy("buy a bronze med helm at Peksa's", HELM, 1, "Peksa's Helmet Shop", PEKSA))
                .done(p -> has(HELM, 1).test(p) || worn(p, HELM) || stageAtLeast(Q, 30).test(p)));
        s.step(new QuestSteps.Collect("get a cabbage", CABBAGE, 1).stock()
                .then(new QuestSteps.Walk("walk to the cabbage patch by the monastery", PATCH_SIDE, 2))
                .then(new QuestSteps.Interact("pick a cabbage", new int[]{CABBAGE_PATCH}, null, "Pick", PATCH, 3, false).until(has(CABBAGE, 1)))
                .buy(new QuestSteps.Buy("buy a cabbage at Wydin's", CABBAGE, 1, "Wydin's Food Store", WYDIN))
                .done(p -> has(CABBAGE, 1).test(p) || stageAtLeast(Q, 30).test(p)));
        s.step(new QuestSteps.Custom("pack food for the knights", 5, null, r -> { QuestRunner.makeRoom(r.bot, 8); QuestRunner.packFood(r.bot, 6); return QuestSteps.St.DONE; })
                .done(p -> inFortress(p) || stageAtLeast(Q, 30).test(p)));

        // in through the guards' entrance
        s.step(new QuestSteps.Walk("walk to the fortress's sturdy door", DOOR_OUTSIDE, 1).done(p -> inFortress(p) || stageAtLeast(Q, 30).test(p)));
        s.step(new QuestSteps.Custom("put on the guard's disguise", 20, null, QuestBlackKnightsFortress::wearDisguise).done(p -> disguised(p) || inFortress(p) || stageAtLeast(Q, 30).test(p)));
        s.step(guard(new QuestSteps.Interact("open the sturdy door in disguise", new int[]{STURDY_DOOR}, null, "Open", STURDY_AT, 0, false).until(p -> in(p, 0, HALL)))
                .done(p -> inFortress(p) || stageAtLeast(Q, 30).test(p)));

        // to the grill: push the wall, up, up, down, down, listen
        s.step(guard(push("push the secret wall", WALL_HALL, Location.create(3016, 3516, 0), p -> in(p, 0, LADDER_ROOM)))
                .done(p -> stageAtLeast(Q, 20).test(p) || legGrill(p) >= 2));
        s.step(guard(climb("up the ladder behind the wall", LADDER_UP, "Climb-up", Location.create(3015, 3519, 0)).until(p -> in(p, 1, LADDER_ROOM)))
                .done(p -> stageAtLeast(Q, 20).test(p) || legGrill(p) >= 3));
        s.step(guard(climb("up to the top floor", LADDER_UP, "Climb-up", Location.create(3016, 3519, 1)).until(p -> p.getLocation().getZ() == 2))
                .done(p -> stageAtLeast(Q, 20).test(p) || legGrill(p) >= 4));
        s.step(guard(climb("down the ladder to the east", LADDER_DOWN, "Climb-down", Location.create(3017, 3516, 2)).until(p -> in(p, 1, WEST1)))
                .done(p -> stageAtLeast(Q, 20).test(p) || legGrill(p) >= 5));
        s.step(guard(climb("up the middle ladder", LADDER_UP, "Climb-up", Location.create(3023, 3513, 1)).until(QuestBlackKnightsFortress::topMiddle))
                .done(p -> stageAtLeast(Q, 20).test(p) || legGrill(p) >= 6));
        s.step(guard(climb("down to the east wing", LADDER_DOWN, "Climb-down", Location.create(3025, 3513, 2)).until(p -> in(p, 1, EAST1)))
                .done(p -> stageAtLeast(Q, 20).test(p) || legGrill(p) >= 7));
        s.step(guard(new QuestSteps.Interact("through the sturdy door to the ladder", new int[]{STURDY_DOOR_UP}, null, "Open", Location.create(3025, 3511, 1), 0, false).until(p -> in(p, 1, NOOK)))
                .done(p -> stageAtLeast(Q, 20).test(p) || legGrill(p) >= 8));
        s.step(guard(climb("down to the grill room", LADDER_DOWN, "Climb-down", Location.create(3021, 3510, 1)).until(p -> in(p, 0, GRILL_ROOM)))
                .done(p -> stageAtLeast(Q, 20).test(p) || legGrill(p) >= 9));
        s.step(guard(new QuestSteps.Interact("listen at the grill", new int[]{GRILL}, null, "Listen-at", GRILL_AT, 0, false).picks().until(stageAtLeast(Q, 20)))
                .done(stageAtLeast(Q, 20)));

        // to the hole: back the way it came (up, the sturdy door, up, down, up, down, down), push the wall, the guard's door,
        // up from the meeting room, push the wall
        s.step(guard(climb("up out of the grill room", LADDER_UP, "Climb-up", Location.create(3021, 3510, 0)).until(p -> in(p, 1, NOOK)))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 1));
        s.step(guard(new QuestSteps.Interact("back through the sturdy door", new int[]{STURDY_DOOR_UP}, null, "Open", Location.create(3025, 3511, 1), 0, false).until(p -> in(p, 1, EAST1)))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 2));
        s.step(guard(climb("up from the east wing", LADDER_UP, "Climb-up", Location.create(3025, 3513, 1)).until(QuestBlackKnightsFortress::topMiddle))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 3));
        s.step(guard(climb("down the middle ladder", LADDER_DOWN, "Climb-down", Location.create(3023, 3513, 2)).until(QuestBlackKnightsFortress::westOrMid1))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 4));
        s.step(guard(climb("up to the top floor again", LADDER_UP, "Climb-up", Location.create(3017, 3516, 1)).until(QuestBlackKnightsFortress::topWest))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 5));
        s.step(guard(climb("down the ladder to the west", LADDER_DOWN, "Climb-down", Location.create(3016, 3519, 2)).until(p -> in(p, 1, LADDER_ROOM)))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 6));
        s.step(guard(climb("down behind the secret wall", LADDER_DOWN, "Climb-down", Location.create(3015, 3519, 1)).until(p -> in(p, 0, LADDER_ROOM)))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 7));
        s.step(guard(push("push the wall back into the hall", WALL_HALL, WALL_HALL, p -> in(p, 0, HALL)))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 8 || outside(p)));
        // a bot that came back from outside: the walk, disguise and sturdy door steps above already brought it into the hall
        s.step(guard(new GuardDoor(new QuestSteps.Interact("go through the guard's door anyway", new int[]{GUARD_DOOR}, null, "Open", GUARD_DOOR_AT, 0, false).picks(2).until(p -> in(p, 0, MEETING))))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 9));
        s.step(guard(climb("up the ladder in the meeting room", MEETING_LADDER_UP, "Climb-up", Location.create(3022, 3518, 0)).until(p -> in(p, 1, NORTH1)))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 10));
        s.step(guard(push("push the wall to the hole", WALL_HOLE, WALL_HOLE, p -> in(p, 1, HOLE_ROOM)))
                .done(p -> stageAtLeast(Q, 30).test(p) || legHole(p) >= 11));
        s.step(guard(new QuestSteps.UseOn("drop the cabbage down the hole", CABBAGE, new int[]{HOLE}, null, IntType.SCENERY, HOLE_AT, 1).picks().until(stageAtLeast(Q, 30)))
                .done(stageAtLeast(Q, 30)));

        // out: push the wall back, down to the meeting room, its door, the sturdy door
        s.step(guard(push("push the wall back", WALL_HOLE, Location.create(3030, 3509, 1), p -> in(p, 1, NORTH1)))
                .done(p -> stageAtLeast(Q, 100).test(p) || legOut(p) >= 1));
        s.step(guard(climb("down to the meeting room", MEETING_LADDER_DOWN, "Climb-down", Location.create(3022, 3518, 1)).until(p -> in(p, 0, MEETING)))
                .done(p -> stageAtLeast(Q, 100).test(p) || legOut(p) >= 2));
        s.step(guard(new QuestSteps.Interact("out through the meeting-room door", new int[]{GUARD_DOOR}, null, "Open", GUARD_DOOR_AT, 0, false).until(p -> in(p, 0, HALL)))
                .done(p -> stageAtLeast(Q, 100).test(p) || legOut(p) >= 3));
        s.step(guard(new QuestSteps.Interact("out through the sturdy door", new int[]{STURDY_DOOR}, null, "Open", STURDY_AT, 0, false).until(QuestBlackKnightsFortress::outside))
                .done(p -> stageAtLeast(Q, 100).test(p) || legOut(p) >= 4));

        s.step(new QuestSteps.Walk("back up to Sir Amik", AMIK_AT, 3).done(p -> stageAtLeast(Q, 100).test(p) || p.getLocation().getZ() == 2 && p.getLocation().getDistance(AMIK_AT) <= 3));
        s.step(new QuestSteps.Custom("make room for the reward", 5, null, r -> { QuestRunner.makeRoom(r.bot, 1); return QuestSteps.St.DONE; }).done(stageAtLeast(Q, 100)));
        s.step(new QuestSteps.Talk("tell Sir Amik the potion is ruined", new int[]{AMIK}).until(stageAtLeast(Q, 100)).done(stageAtLeast(Q, 100)));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));

        s.exit(new QuestSteps.Custom("put the old helmet and body back on", 20, null, QuestBlackKnightsFortress::restoreArmour));
        s.exit(new QuestSteps.Walk("back down to the castle courtyard", Location.create(2965, 3343, 0), 6).done(p -> p.getLocation().getZ() == 0));
        return s;
    }
}
