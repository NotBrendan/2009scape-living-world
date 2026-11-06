package content.custom;

import content.region.misthalin.draynor.quest.ernest.ErnestTheChickenPlugin.Lever;
import content.region.misthalin.draynor.quest.ernest.ErnestTheChickenPlugin.LeverCacheExtension;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static content.custom.QuestScript.attr;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Ernest the Chicken: Veronica (285, outside Draynor Manor, spawn 3110,3330) -> Professor Oddenstein (286, top floor,
 * spawn 3111,3367,2; Ernest the chicken 288 at 3110,3366,2) wants an oil can (277), a pressure gauge (271) and a
 * rubber tube (276). Start (VeronicaDialogue): option 1 ("Aha, sounds like a quest. I'll help."): stage 10.
 * Oddenstein (ProfessorOddensteinPlugin) at stage 10: option 1 ("I'm looking for a guy called Ernest."): stage 20.
 * Hand-in is continue only with all three in the pack; he locks the player, turns 288 back into Ernest (287) and
 * opens Ernest's dialogue (continue only, ErnestDialogue), whose close finishes the quest (300 coins, 4 QP).
 * Ground spawns (ground_spawns.json): fish food 272 at 3108,3356,1; poison 273 at 3097,3366,0; spade 952 at
 * 3120,3359,0; rubber tube 276 at 3111,3367,0 (behind door 131, locked without key 275); oil can 277 at 3092,9755 (basement).
 * Pressure gauge: poison on fish food = poisoned fish food 274 (FishfoodPlugin, legacy item-on-item); 274 on the fountain
 * 153 sets /save:piranhas-killed (PoisonFountainListener); "Search" the fountain opens ManorFountainDialogue (continue only)
 * which hands over the gauge. Key: "Search" the compost heap 152 with a spade in the pack (DraynorManorPlugin).
 * Basement (DraynorManorPlugin): library bookcases 155/156 "Search" (from x > 3096) -> the secret room; ladder 133
 * Climb-down -> 3117,9753; back up by ladder 32015 (WizardTowerPlugin: near 3117,9753 -> 3092,3361); lever 160 "Pull"
 * -> back into the library. The front doors 134/135 won't open from inside (y >= 3354).
 * Lever puzzle (ErnestTheChickenPlugin): levers A-F = 11451/2, 11453/4, 11455/6, 11457/8, 11459/60, 11461/2 "Pull" toggles
 * (LeverCacheExtension, varp 33); doors 11450 ("slightly ajar", "Open" walks you through) or 11449 (locked) by varp 668
 * from calculateDoorConfig. The bot walks a fixed sequence of pulls and doors worked out offline from that
 * function (see SOLUTION). Lever states only save "down" (LeverCacheExtension.save), so a bot that leaves mid-puzzle
 * comes back to downs it had undone: handled by the fixed resume / ladder-room fix-up in Basement.
 */
final class QuestErnestTheChicken {
    private QuestErnestTheChicken() { }

    static final content.data.Quests Q = content.data.Quests.ERNEST_THE_CHICKEN;
    static final int VERONICA = 285, ODDENSTEIN = 286;
    static final int GAUGE = 271, FISH_FOOD = 272, POISON = 273, POISONED = 274, KEY = 275, TUBE = 276, OIL = 277, SPADE = 952;
    static final int FOUNTAIN = 153, COMPOST = 152, LADDER_DOWN = 133, LADDER_UP = 32015, SECRET_LEVER = 160;
    static final int[] BOOKCASES = {155, 156};
    static final Location VERONICA_AT = Location.create(3110, 3328, 0);   // just south of her, outside the manor gate
    static final Location ODDENSTEIN_AT = Location.create(3109, 3366, 2);
    static final Location MANOR = Location.create(3105, 3355, 0);         // the middle of the grounds: fountain and compost heap are looked up within 30 of it
    static final Location FISH_FOOD_AT = Location.create(3108, 3356, 1);
    static final Location POISON_AT = Location.create(3097, 3366, 0);
    static final Location SPADE_AT = Location.create(3120, 3359, 0);
    static final Location TUBE_AT = Location.create(3111, 3367, 0);
    static final Location LIBRARY = Location.create(3099, 3358, 0);       // east of the bookcases at 3097,3358-3359
    static final Location HALL_IN = Location.create(3108, 3355, 0);       // waypoints into the library (rooms below)
    static final Location STAIR_IN = Location.create(3108, 3360, 0);
    static final Location NORTH_IN = Location.create(3105, 3371, 0);
    static final Location CORRIDOR_IN = Location.create(3103, 3366, 0);
    static final Location TUBE_ROOM_IN = Location.create(3110, 3367, 0);
    static final Location SECRET_ROOM = Location.create(3093, 3360, 0);   // ladder 133; 32015 lands at 3092,3361
    static final Location SECRET_LEVER_AT = Location.create(3096, 3357, 0);
    static final Location BASEMENT_LADDER = Location.create(3117, 9754, 0);
    static final Location OIL_AT = Location.create(3092, 9755, 0);
    static final Location OUTSIDE = Location.create(3109, 3340, 0);       // the courtyard in front of the manor

    static boolean under(Player p) { return p.getLocation().getY() > 6400; }
    static boolean inBox(Player p, int xa, int xb, int ya, int yb) { Location l = p.getLocation(); return l.getZ() == 0 && l.getX() >= xa && l.getX() <= xb && l.getY() >= ya && l.getY() <= yb; }

    /*
     * Ground-floor rooms from the cache map (MapAscii, walls are tile edges; doors 11470 unless noted):
     * hall x 3105-3112, y 3354-3357 (front doors 134/135 on its south edge at 3108-3109,3353) -> door 3109,3358 (south
     * edge) -> staircase room x 3105-3112, y 3358-3368 (staircase 11498 at 3108,3362; door 131 at 3107,3367, east edge,
     * into the tube room x 3108-3112, y 3366-3368) -> door 3106,3368 (north edge) -> north room x 3102-3119, y 3369-3373
     * -> corridor x 3102-3104, y 3364-3368 -> door 3103,3364 (south edge) -> library x 3097-3104, y 3354-3363 (bookcases
     * 155/156 at 3097,3358-3359; its south and east walls are solid, so a walk that stops "close enough" outside at
     * 3101,3353 can't click in). West rooms x 3097-3101, y 3364-3373 (poison room, door 3099,3366; door 3101,3371 to the
     * north room). Secret room x 3091-3096, y 3354-3363 (ladder 133 at 3092,3362, lever 160 at 3096,3357).
     */
    static boolean inSecretRoom(Player p) { return inBox(p, 3091, 3096, 3354, 3363); }
    static boolean inLibrary(Player p) { return inBox(p, 3097, 3104, 3354, 3363); }
    static boolean inCorridor(Player p) { return inBox(p, 3102, 3104, 3364, 3368); }
    static boolean inNorthRoom(Player p) { return inBox(p, 3102, 3119, 3369, 3373); }
    static boolean inTubeRoom(Player p) { return inBox(p, 3108, 3112, 3366, 3368); }
    static boolean inStairRoom(Player p) { return inBox(p, 3105, 3112, 3358, 3368) && !inTubeRoom(p); }
    static boolean inWestRooms(Player p) { return inBox(p, 3097, 3101, 3364, 3373); }
    static boolean inHall(Player p) { return inBox(p, 3105, 3112, 3354, 3357); }

    /** How far along the way to the bookcase: 6 secret room or basement, 5 library, 4 corridor, 3 north room, 2 staircase or west rooms, 1 hall, 0 elsewhere. */
    static int libraryRank(Player p) {
        if (under(p) || inSecretRoom(p)) return 6;
        if (inLibrary(p)) return 5;
        if (inCorridor(p)) return 4;
        if (inNorthRoom(p)) return 3;
        if (inStairRoom(p) || inWestRooms(p)) return 2;
        return inHall(p) ? 1 : 0;
    }

    /**
     * Walks into a room and is only done once the bot is inside it (QuestSteps.Walk counts "stuck within 6 tiles" as
     * arrived, which left it outside the library wall). When the walker gives up or stops short, the door toward the
     * target is opened, a few times, before failing.
     */
    static QuestSteps.Step intoRoom(String name, Location to, java.util.function.Predicate<Player> inside) {
        final QuestSteps.Mover m = new QuestSteps.Mover();
        final int[] doors = {0};
        return new QuestSteps.Custom(name, QuestSteps.WALK_LIMIT, r -> { m.reset(to, 1); doors[0] = 0; }, r -> {
            Player p = r.bot;
            if (inside.test(p)) return QuestSteps.St.DONE;
            QuestSteps.St w = m.tick(p);
            if (w == QuestSteps.St.RUNNING) return QuestSteps.St.RUNNING;
            if (p.getWalkingQueue().isMoving()) return QuestSteps.St.RUNNING;
            if (++doors[0] > 4) { r.log("  " + name + ": not inside at " + p.getLocation() + " (" + m.last + ")"); return QuestSteps.St.FAILED; }
            boolean opened = QuestSteps.openDoorToward(p, to, 6);
            r.log("  " + name + ": stopped at " + p.getLocation() + (opened ? ", opened a door" : ", walking on") + " (" + m.last + ")");
            m.reset(to, 1);
            return QuestSteps.St.RUNNING;
        }).done(inside);
    }
    /** The bookcase is searched from inside the library only (the handler needs x > 3096 and the click can't path through the library's walls). */
    static QuestSteps.Step searchBookcase() {
        final QuestSteps.Interact ia = new QuestSteps.Interact("search the bookcase", BOOKCASES, null, "Search", LIBRARY, 4, false).until(p -> inSecretRoom(p) || under(p));
        return new QuestSteps.Custom("search the bookcase", 300, ia::begin, r -> {
            Player p = r.bot;
            if (inSecretRoom(p) || under(p)) return QuestSteps.St.DONE;
            if (!inLibrary(p) && !p.getWalkingQueue().isMoving() && !p.getPulseManager().hasPulseRunning()) { r.log("  search the bookcase: not in the library (at " + p.getLocation() + ")"); return QuestSteps.St.FAILED; }
            QuestSteps.St s = ia.tick(r);
            if (s == QuestSteps.St.FAILED) r.log("  search the bookcase: " + ia.failure + " [" + ia.trace() + "]");
            return s;
        }).done(p -> inSecretRoom(p) || under(p));
    }

    static boolean handedIn(Player p) { return stageAtLeast(Q, 100).test(p) || attr(p, "ernest-hide"); }
    static boolean gauged(Player p) { return has(GAUGE, 1).test(p) || stageAtLeast(Q, 100).test(p); }
    static boolean oiled(Player p) { return has(OIL, 1).test(p) || stageAtLeast(Q, 100).test(p); }
    static boolean tubed(Player p) { return has(TUBE, 1).test(p) || stageAtLeast(Q, 100).test(p); }

    // ------------------------------------------------------------------ the basement: a fixed lever-and-door sequence

    /**
     * Rooms from the cache map of region 12440 (walls are tile edges): 0 SW (oil can) x <= 3099, y <= 9757; 1 LADDER
     * (ladder 32015 at 3117,9754, lever A 3108,9745, lever B 3118,9752) x >= 3100, y <= 9757; 2 W1 x 3096-3099,
     * y 9758-9762; 3 M1 x 3100-3104, y 9758-9762; 4 E (lever C 3112,9760, lever D 3108,9767) x >= 3105, y >= 9758;
     * 5 W2 (lever E 3097,9767, lever F 3096,9765) x <= 3099, y >= 9763; 6 M2 x 3100-3104, y >= 9763.
     * Doors: wrappers 137-145 (children 11449 shut, no options / 11450 ajar, "Open") on varbits 1794-1802 = varp 668
     * bits 0-8; levers: wrappers 146-151 (children down/up) on varbits 1788-1793 = varp 33 bits 1-6 (cache).
     */
    static final int SW = 0, LADDER_ROOM = 1, W1 = 2, M1 = 3, E = 4, W2 = 5, M2 = 6;
    static final String[] ROOM_NAMES = {"SW", "ladder", "W1", "M1", "E", "W2", "M2"};
    static final int DOOR_OPEN = 11450, DOOR_LOCKED = 11449;

    static int room(Location l) {
        int x = l.getX(), y = l.getY();
        if (y <= 9757) return x <= 3099 ? SW : LADDER_ROOM;
        if (x >= 3105) return E;
        boolean north = y >= 9763;
        return x <= 3099 ? (north ? W2 : W1) : (north ? M2 : M1);
    }

    /** The lever tiles (cache: wrappers 146-151), A-F in {@link Lever} order, and the room each is pulled from. */
    static final int[][] LEVER_AT = {{3108, 9745}, {3118, 9752}, {3112, 9760}, {3108, 9767}, {3097, 9767}, {3096, 9765}};
    static final int[] LEVER_ROOM = {LADDER_ROOM, LADDER_ROOM, E, E, W2, W2};

    /**
     * One move of the fixed solution: pull a lever to a state, or walk through a door. For a door the server's
     * getDestination stands the player on the near side before "Open" and getWalkData walks it to the far side
     * (ErnestTheChickenPlugin.getDestination / LeverCacheExtension.getWalkData).
     */
    static final class Move {
        final Lever lever; final boolean up;               // a pull
        final int doorX, doorY, from, to;                  // a door
        Move(Lever lever, boolean up) { this.lever = lever; this.up = up; doorX = doorY = 0; from = to = LEVER_ROOM[lever.ordinal()]; }
        Move(int doorX, int doorY, int from, int to) { lever = null; up = false; this.doorX = doorX; this.doorY = doorY; this.from = from; this.to = to; }
        boolean pull() { return lever != null; }
        @Override public String toString() { return pull() ? "pull " + name(lever) + (up ? " up" : " down") + " in " + ROOM_NAMES[from] : "door " + doorX + "," + doorY + " " + ROOM_NAMES[from] + " -> " + ROOM_NAMES[to]; }
    }

    /**
     * The solution, worked out offline from a verbatim port of LeverCacheExtension.calculateDoorConfig (the varp 668
     * value each lever state gives) and the door varbits above, breadth first over (lever states, room) from all up in
     * the ladder room. Varp 668 after each pull: A down 4 (M1-E), B down 132 (+ ladder-E), D down 396 (ladder-E, M1-E,
     * ladder-M1, W1-M1), B up 328 (ladder-M1, W1-M1, W1-W2), F down 166 (W2-M2, M1-M2, M1-E, ladder-E), A up 64,
     * B down 222 (SW-ladder, ladder-E, M1-E, W1-M1, W1-W2, W2-M2). The way back out is the SW door again (still 222).
     */
    static final Move[] SOLUTION = {
            new Move(Lever.LEVER_A, false),                 // 0  ladder: A down              (668 = 4)
            new Move(Lever.LEVER_B, false),                 // 1  ladder: B down              (668 = 132)
            new Move(3108, 9758, LADDER_ROOM, E),           // 2  3108,9757 -> 3108,9759
            new Move(Lever.LEVER_D, false),                 // 3  E: D down                   (668 = 396)
            new Move(3108, 9758, E, LADDER_ROOM),           // 4  3108,9759 -> 3108,9757
            new Move(Lever.LEVER_B, true),                  // 5  ladder: B up                (668 = 328)
            new Move(3102, 9758, LADDER_ROOM, M1),          // 6  3102,9757 -> 3102,9759
            new Move(3100, 9760, M1, W1),                   // 7  3101,9760 -> 3099,9760
            new Move(3097, 9763, W1, W2),                   // 8  3097,9762 -> 3097,9764
            new Move(Lever.LEVER_F, false),                 // 9  W2: F down                  (668 = 166)
            new Move(3100, 9765, W2, M2),                   // 10 3099,9765 -> 3101,9765
            new Move(3102, 9763, M2, M1),                   // 11 3102,9764 -> 3102,9762
            new Move(3105, 9760, M1, E),                    // 12 3104,9760 -> 3106,9760
            new Move(3108, 9758, E, LADDER_ROOM),           // 13 3108,9759 -> 3108,9757
            new Move(Lever.LEVER_A, true),                  // 14 ladder: A up                (668 = 64)
            new Move(Lever.LEVER_B, false),                 // 15 ladder: B down              (668 = 222)
            new Move(3100, 9755, LADDER_ROOM, SW)};         // 16 3101,9755 -> 3099,9755: the oil can's room
    static final int SW_DOOR = 16;
    static final Move SW_OUT = new Move(3100, 9755, SW, LADDER_ROOM); // back out with the can (668 still 222)

    /** Lever states (true = up) before move k of the solution. */
    static boolean[] before(int k) {
        boolean[] s = new boolean[6];
        java.util.Arrays.fill(s, true);
        for (int i = 0; i < k; i++) if (SOLUTION[i].pull()) s[SOLUTION[i].lever.ordinal()] = SOLUTION[i].up;
        return s;
    }

    /**
     * Walks the fixed solution move by move. Where it starts: in the oil can's room = done; in the ladder room with the
     * SW door ajar = straight through it; else the furthest move whose room and lever states are exactly the bot's.
     * Anything else in the ladder room (downs saved from an earlier visit: LeverCacheExtension only ever saves downs,
     * and reads them back on entering the region) is put right first with the ladder room's own levers: A up and B down
     * (that opens the SW door for most saved states, e.g. the A,B,D,F downs this solution leaves), and failing that
     * B up too (all up again if C-F are up). Each pull is checked against the server's lever state, each door against
     * the room the bot lands in, and nothing is clicked while the bot is still locked from the last door (the door walk
     * locks for 4 ticks, and a click while locked is dropped: InteractPlugin).
     * {@code home}: back out through the SW door to the ladder room.
     */
    static final class Basement {
        final boolean home;
        int k, wait, idle, tries, fixups, resolves, ticks;
        Move doing;
        Basement(boolean home) { this.home = home; }

        void begin(QuestRunner r) { k = -1; wait = 0; idle = 0; tries = 0; fixups = 0; resolves = 0; ticks = 0; doing = null; }

        QuestSteps.St tick(QuestRunner r) {
            Player p = r.bot;
            ticks++;
            if (!under(p)) return fail(r, "not in the basement (at " + p.getLocation() + ")");
            if (!home && oiled(p)) return QuestSteps.St.DONE;
            if (wait > 0) { wait--; return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getPulseManager().hasPulseRunning()) { idle = 0; return QuestSteps.St.RUNNING; }
            if (p.getLocks().isInteractionLocked() || p.getLocks().isMovementLocked()) { idle = 0; return QuestSteps.St.RUNNING; }
            int here = room(p.getLocation());
            LeverCacheExtension ext = LeverCacheExtension.extend(p);
            if (doing != null) { // the last click: did it take?
                boolean took = doing.pull() ? ext.isUp(doing.lever) == doing.up : here == doing.to;
                if (took) {
                    r.log("  " + doing + " done at tick " + ticks + " (levers " + states(ext) + ", varp 668 " + doorVarp(p) + ", at " + p.getLocation() + ")");
                    if (k >= 0 && k < SOLUTION.length && doing == SOLUTION[k]) k++;
                    doing = null; tries = 0;
                    wait = 1; // a tick to settle (the door walk unlocks, the varps reach the bot)
                    return QuestSteps.St.RUNNING;
                }
                if (++idle < 10) return QuestSteps.St.RUNNING;
                r.log("  " + doing + ": no change after the click (try " + tries + ", at " + p.getLocation() + ", levers " + states(ext) + ")");
                doing = null;
                if (++tries > 3) return fail(r, "gave up on move " + k + " after 4 clicks");
            }
            if (home) {
                if (here == LADDER_ROOM) return QuestSteps.St.DONE;
                if (here != SW) return fail(r, "heading out but in room " + ROOM_NAMES[here] + ", not the oil can's room (levers " + states(ext) + ", doors " + doors(p) + ")");
                return act(r, p, SW_OUT, ext);
            }
            if (here == SW) return QuestSteps.St.DONE;
            if (k < 0 || k >= SOLUTION.length || !fits(p, SOLUTION[k], here, ext, k)) {
                if (++resolves > 20) return fail(r, "lost the way too often (room " + ROOM_NAMES[here] + ", levers " + states(ext) + ")");
                k = resolve(here, p, ext);
                if (k < 0) { // off the solution: put A and B right in the ladder room
                    if (here != LADDER_ROOM) return fail(r, "off the fixed route in room " + ROOM_NAMES[here] + " (levers " + states(ext) + ", doors " + doors(p) + ")");
                    Move fix = !ext.isUp(Lever.LEVER_A) ? new Move(Lever.LEVER_A, true)
                            : ext.isUp(Lever.LEVER_B) && fixups == 0 ? new Move(Lever.LEVER_B, false)
                            : !ext.isUp(Lever.LEVER_B) ? new Move(Lever.LEVER_B, true) : null;
                    if (fix == null || ++fixups > 4) return fail(r, "levers " + states(ext) + " from an earlier visit: no fixed way from here (doors " + doors(p) + ")");
                    r.log("  levers " + states(ext) + " aren't on the route (saved downs?): " + fix);
                    return act(r, p, fix, ext);
                }
                r.log("  on the fixed route at move " + k + " (" + SOLUTION[k] + "; room " + ROOM_NAMES[here] + ", levers " + states(ext) + ", varp 668 " + doorVarp(p) + ")");
            }
            return act(r, p, SOLUTION[k], ext);
        }

        /** Whether move k can be made from here as things are: its room and lever states, and for a door that door ajar. */
        static boolean fits(Player p, Move m, int here, LeverCacheExtension ext, int k) {
            if (here != m.from) return false;
            return m.pull() ? java.util.Arrays.equals(state(ext), before(k)) : doorOpen(p, m);
        }

        static int resolve(int here, Player p, LeverCacheExtension ext) {
            if (here == LADDER_ROOM && doorOpen(p, SOLUTION[SW_DOOR])) return SW_DOOR;
            boolean[] now = state(ext);
            for (int i = SOLUTION.length - 1; i >= 0; i--) {
                Move m = SOLUTION[i];
                if (m.from == here && java.util.Arrays.equals(now, before(i)) && (m.pull() || doorOpen(p, m))) return i;
            }
            return -1;
        }

        QuestSteps.St act(QuestRunner r, Player p, Move m, LeverCacheExtension ext) {
            QuestSteps.clearPopups(p);
            Scenery obj;
            String opt;
            if (m.pull()) {
                if (ext.isUp(m.lever) == m.up) { if (k >= 0 && k < SOLUTION.length && m == SOLUTION[k]) k++; return QuestSteps.St.RUNNING; }
                int[] t = LEVER_AT[m.lever.ordinal()];
                obj = QuestSteps.findScenery(p, Location.create(t[0], t[1], 0), 0, m.lever.getObjectIds(), null, null);
                opt = "Pull";
                if (obj == null) return fail(r, "no lever " + name(m.lever) + " at " + t[0] + "," + t[1]);
            } else {
                obj = door(p, m.doorX, m.doorY);
                opt = "Open";
                if (obj == null || obj.getId() != DOOR_OPEN) return fail(r, m + ": the door is shut (levers " + states(ext) + ", varp 668 " + doorVarp(p) + ", doors " + doors(p) + ")");
            }
            if (!QuestSteps.click(p, obj, opt, IntType.SCENERY)) return fail(r, m + ": no " + opt + " option on " + obj.getId());
            doing = m; idle = 0; wait = 1;
            return QuestSteps.St.RUNNING;
        }

        QuestSteps.St fail(QuestRunner r, String why) { r.log("  basement: " + why); return QuestSteps.St.FAILED; }
    }

    static boolean doorOpen(Player p, Move m) { Scenery s = door(p, m.doorX, m.doorY); return s != null && s.getId() == DOOR_OPEN; }

    static boolean[] state(LeverCacheExtension ext) { boolean[] s = new boolean[6]; for (Lever lv : Lever.values()) s[lv.ordinal()] = ext.isUp(lv); return s; }

    static String name(Lever lv) { return lv.name().replace("LEVER_", ""); }

    static String states(LeverCacheExtension ext) { StringBuilder b = new StringBuilder(); for (Lever lv : Lever.values()) b.append(name(lv)).append(ext.isUp(lv) ? "^" : "v"); return b.toString(); }

    static int doorVarp(Player p) { try { return core.api.ContentAPIKt.getVarp(p, 668); } catch (Throwable t) { return -1; } }

    /** This bot's view of a door: 11450 (ajar) or 11449 (shut), as its varp 668 shows it. */
    static Scenery door(Player p, int x, int y) { return QuestSteps.findScenery(p, Location.create(x, y, 0), 0, new int[]{DOOR_OPEN, DOOR_LOCKED}, null, null); }

    static final int[][] ALL_DOORS = {{3105, 9765}, {3100, 9765}, {3105, 9760}, {3100, 9760}, {3100, 9755}, {3102, 9763}, {3097, 9763}, {3108, 9758}, {3102, 9758}};

    static String doors(Player p) {
        StringBuilder b = new StringBuilder();
        for (int[] d : ALL_DOORS) {
            Scenery s = door(p, d[0], d[1]);
            b.append(d[0]).append(',').append(d[1]).append(s == null ? " none" : s.getId() == DOOR_OPEN ? " open" : " shut").append("; ");
        }
        return b.toString();
    }

    static QuestSteps.Step basement(String name, boolean home) {
        Basement b = new Basement(home);
        return new QuestSteps.Custom(name, 3000, b::begin, b::tick);
    }

    // ------------------------------------------------------------------ small helpers

    /** Walks up to an object found by id within {@code radius} of {@code center} (the fountain and compost heap: no fixed tile in the data). */
    static QuestSteps.Step approach(String name, int[] ids, Location center, int radius) {
        final QuestSteps.Mover m = new QuestSteps.Mover();
        final Location[] at = {null};
        return new QuestSteps.Custom(name, QuestSteps.WALK_LIMIT, r -> { at[0] = null; m.dest = null; }, r -> {
            Player p = r.bot;
            if (at[0] == null) {
                if (p.getLocation().getZ() != center.getZ() || QuestFloors.under(p.getLocation())) { // get onto the ground floor first
                    if (m.dest == null) m.reset(center, 6);
                    QuestSteps.St w = m.tick(p);
                    if (w == QuestSteps.St.FAILED) return QuestSteps.St.FAILED;
                    if (!QuestFloors.samePlane(p.getLocation(), center)) return QuestSteps.St.RUNNING;
                }
                Scenery s = QuestSteps.findScenery(p, center, radius, ids, null, null);
                if (s == null) { r.log("  " + name + ": nothing with ids " + java.util.Arrays.toString(ids) + " within " + radius + " of " + center); return QuestSteps.St.FAILED; }
                at[0] = s.getLocation();
                m.reset(at[0], 1);
                r.log("  " + name + ": it's at " + at[0]);
            }
            QuestSteps.St w = m.tick(p);
            double d = p.getLocation().getDistance(at[0]);
            if (w == QuestSteps.St.DONE || d <= 2.5 || w == QuestSteps.St.FAILED && d <= 6) return QuestSteps.St.DONE;
            return w;
        });
    }

    /** Poison on the fish food, as the client's item-on-item does (FishfoodPlugin: used = poison, with = fish food). */
    static QuestSteps.Step mixFishFood() {
        final int[] st = {0, 0}; // tries, wait
        return new QuestSteps.Custom("poison the fish food", 60, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (has(POISONED, 1).test(p)) return QuestSteps.St.DONE;
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            if (p.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (++st[0] > 4) return QuestSteps.St.FAILED;
            Item poison = slot(p, POISON), food = slot(p, FISH_FOOD);
            if (poison == null || food == null) return QuestSteps.St.FAILED;
            QuestSteps.clearPopups(p);
            boolean ran = false;
            try { ran = InteractionListeners.run(poison, food, IntType.ITEM, p); } catch (Throwable ignored) { }
            if (!ran) try { QuestRunner.legacyUseWith(p, poison, food); } catch (Throwable ignored) { }
            st[1] = 3;
            return QuestSteps.St.RUNNING;
        });
    }

    static Item slot(Player p, int id) {
        for (int i = 0; i < 28; i++) { Item x = p.getInventory().get(i); if (x != null && x.getId() == id) return x; }
        return null;
    }

    /** Ernest's thanks, opened by the professor a few ticks after the hand-in; its close finishes the quest. */
    static QuestSteps.Step ernestThanks() {
        final DialogueDriver[] d = {null};
        return new QuestSteps.Custom("listen to Ernest's thanks", 120, r -> d[0] = null, r -> {
            Player p = r.bot;
            if (stageAtLeast(Q, 100).test(p)) return QuestSteps.St.DONE;
            if (d[0] == null) { if (DialogueDriver.open(p)) d[0] = new DialogueDriver(p); return QuestSteps.St.RUNNING; }
            DialogueDriver.Status s = d[0].tick();
            if (s == DialogueDriver.Status.FAILED) { r.log("  Ernest's dialogue: " + d[0].failure()); return QuestSteps.St.FAILED; }
            if (s == DialogueDriver.Status.DONE) d[0] = null;
            return QuestSteps.St.RUNNING;
        });
    }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "ernest", "Ernest the Chicken").startAt(3110, 3331, 0).minutes(45).coins(300).unlock(10);
        s.step(new QuestSteps.Walk("walk to Veronica outside the manor", VERONICA_AT, 3).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(VERONICA_AT) <= 3));
        s.step(new QuestSteps.Talk("offer Veronica help", new int[]{VERONICA}, 1).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        s.step(new QuestSteps.Walk("up to Professor Oddenstein", ODDENSTEIN_AT, 3).done(p -> stageAtLeast(Q, 20).test(p) || p.getLocation().getZ() == 2 && p.getLocation().getDistance(ODDENSTEIN_AT) <= 3));
        s.step(new QuestSteps.Talk("ask Oddenstein about Ernest", new int[]{ODDENSTEIN}, 1).until(stageAtLeast(Q, 20)).done(stageAtLeast(Q, 20)));

        // inside first: the fish food, the poison and the spade, then the basement; one trip outside for the fountain and the compost heap
        s.step(new QuestSteps.Collect("get poisoned fish food", POISONED, 1)
                .then(new QuestSteps.Collect("get the fish food", FISH_FOOD, 1)
                        .then(new QuestSteps.Walk("walk to the fish food upstairs", FISH_FOOD_AT, 1))
                        .then(new QuestSteps.PickUp("pick up the fish food", FISH_FOOD, FISH_FOOD_AT))
                        .done(has(FISH_FOOD, 1)))
                .then(new QuestSteps.Collect("get the poison", POISON, 1)
                        .then(new QuestSteps.Walk("walk to the poison", POISON_AT, 1))
                        .then(new QuestSteps.PickUp("pick up the poison", POISON, POISON_AT))
                        .done(has(POISON, 1)))
                .then(mixFishFood())
                .done(p -> has(POISONED, 1).test(p) || attr(p, "piranhas-killed") || gauged(p)));
        s.step(new QuestSteps.Collect("get a spade", SPADE, 1)
                .then(new QuestSteps.Walk("walk to the spade", SPADE_AT, 1))
                .then(new QuestSteps.PickUp("pick up the spade", SPADE, SPADE_AT))
                .buy(new QuestSteps.Buy("buy a spade", SPADE, 1, "Sarah's Farming Shop", Location.create(3039, 3292, 0)))
                .done(p -> has(SPADE, 1).test(p) || has(KEY, 1).test(p) || tubed(p)));

        s.step(new QuestSteps.Collect("get the oil can", OIL, 1)
                .then(intoRoom("walk into the manor hall", HALL_IN, p -> libraryRank(p) >= 1))
                .then(intoRoom("into the staircase room", STAIR_IN, p -> libraryRank(p) >= 2))
                .then(intoRoom("into the north room", NORTH_IN, p -> libraryRank(p) >= 3))
                .then(intoRoom("into the corridor by the library", CORRIDOR_IN, p -> libraryRank(p) >= 4))
                .then(intoRoom("through the door into the library", LIBRARY, p -> libraryRank(p) >= 5))
                .then(searchBookcase())
                .then(new QuestSteps.Interact("down the ladder to the basement", new int[]{LADDER_DOWN}, null, "Climb-down", SECRET_ROOM, 6, false).until(QuestErnestTheChicken::under).done(QuestErnestTheChicken::under))
                .then(basement("work the levers to the oil can", false).done(p -> under(p) && room(p.getLocation()) == SW))
                .then(new QuestSteps.PickUp("pick up the oil can", OIL, OIL_AT))
                .done(QuestErnestTheChicken::oiled));
        s.step(basement("back through the doors to the ladder", true).done(p -> !under(p) || room(p.getLocation()) == LADDER_ROOM));
        s.step(new QuestSteps.Interact("up the ladder", new int[]{LADDER_UP}, null, "Climb-up", BASEMENT_LADDER, 8, false).until(p -> !under(p)).done(p -> !under(p)));
        s.step(new QuestSteps.Interact("pull the lever by the secret door", new int[]{SECRET_LEVER}, null, "Pull", SECRET_LEVER_AT, 4, false).until(p -> !inSecretRoom(p)).done(p -> !inSecretRoom(p) && !under(p)));
        s.step(intoRoom("out of the library to the corridor", CORRIDOR_IN, p -> !inLibrary(p))); // its only door is on the north side
        s.step(intoRoom("on into the north room", NORTH_IN, p -> !inLibrary(p) && !inCorridor(p)));

        s.step(new QuestSteps.Collect("get the pressure gauge", GAUGE, 1)
                .then(new QuestSteps.UseOn("pour the poisoned food in the fountain", POISONED, new int[]{FOUNTAIN}, null, IntType.SCENERY, MANOR, 30).until(p -> attr(p, "piranhas-killed")).done(p -> attr(p, "piranhas-killed")))
                .then(new QuestSteps.Interact("search the fountain", new int[]{FOUNTAIN}, null, "Search", MANOR, 30, false).picks().until(has(GAUGE, 1)))
                .done(QuestErnestTheChicken::gauged));
        s.step(new QuestSteps.Collect("get the key", KEY, 1)
                .then(approach("walk to the compost heap", new int[]{COMPOST}, MANOR, 30))
                .then(new QuestSteps.Interact("dig through the compost heap", new int[]{COMPOST}, null, "Search", MANOR, 30, false).picks().until(has(KEY, 1)))
                .done(p -> has(KEY, 1).test(p) || tubed(p)));
        s.step(new QuestSteps.Collect("get the rubber tube", TUBE, 1)
                .then(intoRoom("walk into the manor hall", HALL_IN, p -> inHall(p) || inStairRoom(p) || inTubeRoom(p) || inNorthRoom(p) || inCorridor(p) || inWestRooms(p)))
                .then(intoRoom("into the staircase room", STAIR_IN, p -> inStairRoom(p) || inTubeRoom(p)))
                .then(intoRoom("unlock door 131 into the tube room", TUBE_ROOM_IN, QuestErnestTheChicken::inTubeRoom))
                .then(new QuestSteps.PickUp("pick up the tube", TUBE, TUBE_AT).limit(900))
                .done(QuestErnestTheChicken::tubed));

        s.step(new QuestSteps.Walk("back up to Oddenstein", ODDENSTEIN_AT, 3));
        s.step(new QuestSteps.Talk("give Oddenstein the parts", new int[]{ODDENSTEIN}).until(QuestErnestTheChicken::handedIn).done(QuestErnestTheChicken::handedIn));
        s.step(ernestThanks().done(stageAtLeast(Q, 100)));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(new QuestSteps.Walk("back down to the ground floor", Location.create(3108, 3358, 0), 4).done(p -> p.getLocation().getZ() == 0));
        s.exit(new QuestSteps.Walk("out of the manor", OUTSIDE, 4));
        return s;
    }
}
