package content.custom;

/**
 * W7 places, wave 4 (the design notes section 4): two new places for the smart adventurers. Each has
 * its own -D flag, default OFF; with a flag off its caller runs train-61's code unchanged.
 *
 *   -Dbots.agility.barb   the Barbarian Outpost course for Agility 35 to 51 (the Gnome course stays for under 35). The bar
 *                         crawl is waived for bots at the outpost gate; the pipe into the course is the engine's own
 *                         (Agility 35). Seats capped (3), a visit always walks back out of the fence before it ends.
 *   -Dbots.fish.guild     the Fishing Guild as an extra spot set for Fishing 68+: lobster (cage) and tuna/swordfish
 *                         (harpoon) fishers take a guild seat when one is free (3), and with -Dbots.fish.shark the shark
 *                         row gets one more seat there (the guild's Net/Harpoon spot). The guild bank is already in
 *                         AfkTasks.BANKS and the level door is Gates' "fishing-guild" box.
 *
 * This class holds only the flags, the tables, the pure rules and the counters, so plain java can test it without a world
 * (TestW7Places). Nothing here touches a server class; the world side is W7Run.
 */
final class W7Places {
    private W7Places() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }

    // ------------------------------------------------------------------ Agility: the Barbarian Outpost course
    static final boolean AGI_BARB = flag("bots.agility.barb");
    /** The Gnome course's old ceiling, the outpost pipe's level (engine PipeShortcut 20210), and where this course stops paying (the Wilderness course is 52). */
    static final int GNOME_CAP = 35, BARB_LEVEL = 35;
    static final int BARB_CAP = Math.max(BARB_LEVEL + 1, Integer.getInteger("bots.agility.barb.cap", 52));
    static final int BARB_SEATS = Math.max(1, Integer.getInteger("bots.agility.barb.seats", 3));
    /** A fall costs lifepoints / 12, 2 at least (AgilityCourse.getHitAmount): under this many lifepoints the bot walks out instead of trying an obstacle. */
    static final int BARB_MIN_LIFE = 6;
    /** After the visit's time or laps are up, this long to walk out through the pipe and the gate before the last-resort hop. */
    static final long BARB_LEAVE_MS = 4 * 60_000L;
    static volatile int barbVisits, barbLaps, barbFalls, barbGateWalk, barbGateHop, barbPipe, barbPipeFail, barbPit, barbRescueHop, barbLowLife;

    /** wantsAgility's first rule. Flag off: 35 and up never train (train-61). On: 35 to cap-1 train too, when the outpost has a free seat. */
    static boolean noAgility(boolean on, int agi, int seatsUsed) {
        if (!on) return agi >= GNOME_CAP;
        if (agi < BARB_LEVEL) return false;
        return agi >= BARB_CAP || seatsUsed >= BARB_SEATS;
    }
    /** startAgility: does this visit go to the outpost (flag on, 35 or more)? */
    static boolean barbCourse(boolean on, int agi) { return on && agi >= BARB_LEVEL; }

    /** Seats: bot -> the time its visit must be over by. Time-stamped so a rescued or logged-out bot cannot leak a seat. */
    static final java.util.Map<String, Long> BARB_SEAT = new java.util.concurrent.ConcurrentHashMap<>();
    static int barbSeatsUsed(long now) { int n = 0; for (Long t : BARB_SEAT.values()) if (t != null && t > now) n++; return n; }
    static void barbSit(String who, long until) { BARB_SEAT.put(who, until); }
    static void barbStand(String who) { BARB_SEAT.remove(who); }

    /**
     * The course in order: {object id (0: by name), name, option, x, y, z of the object, search radius, stand x, stand y}.
     * Ids, options and xp from the engine's BarbarianOutpostCourse (javap); object tiles from the surveyors' shortcut log
     * (Ropeswing 2551,3550; Log balance 2550,3546; walls 2536/2539/2542,3553) and the nav grid. Stand 0,0: no walk first.
     * Step 4 (the ladder down from the ledge) gives no xp: it is done when the bot is on the ground floor.
     */
    static final Object[][] BARB = {
            {2282, "Ropeswing", "Swing-on", 2551, 3550, 0, 3, 2551, 3554},
            {2294, "Log balance", "Walk-across", 2550, 3546, 0, 3, 2551, 3546},
            {20211, "Obstacle net", "Climb-over", 2538, 3546, 0, 3, 2539, 3546},
            {2302, "Balancing ledge", "Walk-across", 2535, 3547, 1, 4, 0, 0},
            {0, "Ladder", "Climb-down", 2532, 3545, 1, 4, 0, 0},
            {1948, "Crumbling wall", "Climb-over", 2536, 3553, 0, 0, 2535, 3553},
            {1948, "Crumbling wall", "Climb-over", 2539, 3553, 0, 0, 0, 0},
            {1948, "Crumbling wall", "Climb-over", 2542, 3553, 0, 0, 0, 0}};
    static final int STEP_LEDGE = 3, STEP_LADDER = 4, STEP_WALL1 = 5, STEP_WALL2 = 6, STEP_WALL3 = 7;
    /** One lap's xp: 22 + 13.5 + 8 + 22 + 3 x 13.5 and the 46.2 lap bonus. */
    static final double BARB_LAP_XP = 22 + 13.5 + 8 + 22 + 3 * 13.5 + 46.2;

    /** Gate (ids 2115/2116, bar crawl) between x 2545 and 2546 at y 3569-3570; pipe 20210 at 2552,3559 between the yard (north) and the course. */
    /** w13d routes-t65 #2: from Seers/Catherby the planner walks south round by 2566,3413 and its first 169 tiles open the straight-line gap,
     *  so travel's stall > 8 bought a teleport. East of BARB_VIA_X (below the White Wolf Mountain line, north of y 3400) the bot walks to
     *  BARB_VIA first and its progress is measured along via + via-to-gate. */
    static final int[] BARB_VIA = {2566, 3413};
    static final int BARB_VIA_X = 2600, BARB_VIA_MAX_X = 2880, BARB_VIA_MIN_Y = 3400;
    static boolean barbVia(int x, int y) { return x > BARB_VIA_X && x < BARB_VIA_MAX_X && y > BARB_VIA_MIN_Y; }
    static final int[] GATE_OUT = {2544, 3569}, GATE_IN = {2546, 3569}, GATE_AT = {2545, 3569}, PIPE_AT = {2552, 3559}, PIPE_N = {2552, 3561}, PIPE_S = {2552, 3557};
    static final int[] GATE_IDS = {2115, 2116};
    static final int PIPE_ID = 20210;

    /** Where a tile is: outside, the yard between the gate and the pipe, the fenced course (both floors), the pit under the rope. From the nav grid (flood fill, gate shut). */
    static final int Z_OUT = 0, Z_YARD = 1, Z_COURSE = 2, Z_PIT = 3;
    static int barbZone(int x, int y, int z) {
        if (y >= 9940 && y <= 9965 && x >= 2535 && x <= 2560) return Z_PIT;
        if (y > 6400) return Z_OUT;
        if (z == 1) return x >= 2528 && x <= 2553 && y >= 3542 && y <= 3556 ? Z_COURSE : Z_OUT;
        if (z != 0) return Z_OUT;
        if (x >= 2550 && x <= 2553 && y >= 3556 && y <= 3559) return Z_COURSE; // the pipe's south side
        if (x >= 2529 && x <= 2553 && y >= 3542 && y <= 3555) return Z_COURSE;
        if (x >= 2528 && x <= 2545 && y == 3556) return Z_COURSE;
        if (x == 2528 && y >= 3552 && y <= 3555) return Z_COURSE; // the west alcove by the walls
        if (x >= 2546 && x <= 2549 && y >= 3556 && y <= 3559) return Z_YARD;
        if (x >= 2546 && x <= 2555 && y >= 3560 && y <= 3573) return Z_YARD;
        return Z_OUT;
    }

    /** What the visit does next, from where the bot is and whether it is on its way out. */
    static final int A_END = 0, A_TRAVEL = 1, A_PIPE_IN = 2, A_LAP = 3, A_PIPE_OUT = 4, A_GATE_OUT = 5, A_PIT = 6;
    static int barbAct(int zone, boolean leaving) {
        if (zone == Z_PIT) return A_PIT;
        if (leaving) return zone == Z_COURSE ? A_PIPE_OUT : zone == Z_YARD ? A_GATE_OUT : A_END;
        return zone == Z_COURSE ? A_LAP : zone == Z_YARD ? A_PIPE_IN : A_TRAVEL;
    }

    /**
     * The obstacle the bot is really at, from where it stands (a fall, a failed click or a slow tick can leave the counted
     * step behind): upstairs it is the ledge, or the ladder once across (x 2533 or less); on the ground a ledge/ladder
     * step is over (fell off, or climbed down); inside the fenced lane between the walls the next wall is the only way on.
     */
    static int barbStep(int step, int x, int y, int z) {
        if (z == 1) return x <= 2533 ? STEP_LADDER : STEP_LEDGE;
        if (step == STEP_LEDGE || step == STEP_LADDER) return STEP_WALL1;
        if (y >= 3552 && y <= 3554) {
            if (x == 2537 || x == 2538) return STEP_WALL2;
            if (x == 2540 || x == 2541) return STEP_WALL3;
        }
        return step;
    }
    /** Did the step work? xp for every obstacle but the ladder (ground floor reached). */
    static boolean barbDone(int step, boolean xpUp, int z) { return step == STEP_LADDER ? z == 0 : xpUp; }
    /** Too hurt for another obstacle. */
    static boolean barbHurt(int lifepoints) { return lifepoints < BARB_MIN_LIFE; }

    // ------------------------------------------------------------------ Fishing: the Fishing Guild
    static final boolean FISH_GUILD = flag("bots.fish.guild");
    static final int GUILD_LEVEL = 68;
    static final int GUILD_CAP = Math.max(0, Integer.getInteger("bots.fish.guild.cap", 3));
    static final int GUILD_SHARK_SEATS = Math.max(0, Integer.getInteger("bots.fish.guild.sharkSeats", 1));
    /** The pier tile the runner works from: every guild spot is within 12 of it (Cage/Harpoon npc 333 x7, Net/Harpoon npc 334 at 2612,3411; npc_spawns.json). */
    static final int[] GUILD_SPOT = {2604, 3415};
    static final String GUILD_NAME = "the Fishing Guild piers";
    static volatile int guildCage, guildHarpoon, guildShark, guildFull, guildNoEntry;

    /** Gates' "fishing-guild" box. */
    static boolean inGuild(int x, int y) { return x >= 2584 && x <= 2620 && y >= 3394 && y <= 3426; }

    /** Rows: 0 not a guild fish, 1 cage (lobster), 2 harpoon (tuna/swordfish), 3 the shark row. */
    static final int ROW_NONE = 0, ROW_CAGE = 1, ROW_HARPOON = 2, ROW_SHARK = 3;
    static int guildRow(boolean cage, boolean harpoon, boolean sharkRow) { return sharkRow ? ROW_SHARK : cage ? ROW_CAGE : harpoon ? ROW_HARPOON : ROW_NONE; }

    /**
     * Does this fishing job start at the guild? Flag on, a guild row, no player's order riding on it, Fishing 68, the door
     * open to this bot (Gates) and a seat free: cage and harpoon share GUILD_CAP seats, the shark row has its own.
     */
    static boolean guildPick(boolean on, int row, boolean errand, int fishing, boolean canEnter, int seatsUsed, int sharkSeatsUsed) {
        if (!on || row == ROW_NONE || errand || fishing < GUILD_LEVEL || !canEnter) return false;
        return row == ROW_SHARK ? sharkSeatsUsed < GUILD_SHARK_SEATS : seatsUsed < GUILD_CAP;
    }
    /** The shark row's capacity: the Catherby cap, and with the guild flag on the guild's shark seats on top (used only at the guild: guildPick fills them first). */
    static int sharkCapacity(boolean on, int catherbyCap) { return on ? catherbyCap + GUILD_SHARK_SEATS : catherbyCap; }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return AGI_BARB || FISH_GUILD; }
    static String flags() { return ((AGI_BARB ? "agiBarb " : "") + (FISH_GUILD ? "fishGuild " : "")).trim(); }
    static String line() {
        return "[W7] on=[" + flags() + "] barbVisits=" + barbVisits + " barbLaps=" + barbLaps + " barbFalls=" + barbFalls + " barbGateWalk=" + barbGateWalk
                + " barbGateHop=" + barbGateHop + " barbPipe=" + barbPipe + " barbPipeFail=" + barbPipeFail + " barbPit=" + barbPit + " barbRescueHop=" + barbRescueHop
                + " barbLowLife=" + barbLowLife + " barbSeats=" + barbSeatsUsed(System.currentTimeMillis())
                + " guildCage=" + guildCage + " guildHarpoon=" + guildHarpoon + " guildShark=" + guildShark + " guildFull=" + guildFull + " guildNoEntry=" + guildNoEntry;
    }
}
