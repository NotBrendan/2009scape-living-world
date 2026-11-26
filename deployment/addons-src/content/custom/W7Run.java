package content.custom;

import core.game.bots.AIPlayer;
import core.game.node.Node;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;

import java.util.Locale;

/**
 * W7 places, wave 4: the world side of W7Places (rules, tables and counters are there, and unit-tested).
 * Every entry point here is reached only behind -Dbots.agility.barb or -Dbots.fish.guild.
 */
final class W7Run {
    private W7Run() { }

    // ------------------------------------------------------------------ Agility: the Barbarian Outpost course (-Dbots.agility.barb)

    /** One visit's state (Brain.barb): null unless the bot is on a Barbarian Outpost visit. The lap counters are the Brain's agi* fields. */
    static final class Barb {
        boolean leaving, inside, inPit;
        String why;
        long hardUntil, passAt, pitAt;
        int passKind, passZone = -1, gateTries, pipeTries, pitTries, stall;
        double best = Double.MAX_VALUE;
    }
    static final int PASS_GATE = 1, PASS_PIPE = 2;

    static Location loc(int[] xy) { return Location.create(xy[0], xy[1], 0); }
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    /** startAgility for a bot of 35+: the same fields the Gnome visit sets, a seat, and the visit's state. */
    static void startBarb(SmartAdventurers.Brain b, long now) {
        Player bot = b.bot;
        int agi = bot.getSkills().getStaticLevel(Skills.AGILITY);
        b.agilityNudge = false; b.agiStep = 0; b.agiLaps = 0; b.agiTries = 0; b.agiStall = 0; b.agiWait = 0; b.agiBest = Double.MAX_VALUE;
        b.agiGoal = W3Skills.lapsFor(W3Skills.AGI_LAPS, agi);
        b.agiUntil = now + (SmartAdventurers.FAST ? 8 * 60_000 : W3Skills.agiMinutes(b.agiGoal) * 60_000L);
        b.nextAgility = now + (SmartAdventurers.FAST ? 5 * 60_000 : 4 * 3_600_000L);
        b.barb = new Barb();
        W7Places.barbSit(key(bot), b.agiUntil + W7Places.BARB_LEAVE_MS);
        W7Places.barbVisits++;
        b.say(BotChat.pick("Off to the Barbarian Outpost. Rope swing day.", "Agility time. The barbarians' course.", "Outgrown the gnome nets. Barbarian Outpost it is.", "Time to fall in a pit. Barbarian course."));
        BotChat.activity(bot, () -> "running laps on the Barbarian Outpost agility course");
        System.out.println("[W7] barb " + bot.getUsername() + " heading to the Barbarian Outpost course (agility " + agi + ", goal " + b.agiGoal + " laps, seats " + W7Places.barbSeatsUsed(now) + "/" + W7Places.BARB_SEATS + ")");
    }

    static void leave(SmartAdventurers.Brain b, long now, String why) {
        Barb s = b.barb;
        if (s.leaving) return;
        s.leaving = true; s.why = why; s.hardUntil = now + W7Places.BARB_LEAVE_MS;
        s.passKind = 0; s.passZone = -1; s.gateTries = 0; s.pipeTries = 0; s.stall = 0; b.agiWait = 0;
    }

    static void finish(SmartAdventurers.Brain b) {
        Barb s = b.barb;
        W7Places.barbStand(key(b.bot)); W7Places.barbLaps += b.agiLaps;
        System.out.println("[W7] barb " + b.bot.getUsername() + " left the Barbarian Outpost course: " + b.agiLaps + " of " + b.agiGoal + " laps, agility " + b.bot.getSkills().getStaticLevel(Skills.AGILITY) + " (" + (s.why == null ? "-" : s.why) + ")");
        b.barb = null;
        b.endAgility(s.why);
    }

    /** Last resort: the visit could not walk out in time (or is somewhere it has no way out of). Lands outside the gate; counted and logged. */
    static void hopOut(SmartAdventurers.Brain b, long now, String why) {
        Barb s = b.barb;
        if (!s.leaving) leave(b, now, why);
        s.passKind = 0; s.passZone = -1;
        W7Places.barbRescueHop++;
        System.out.println("[W7] barb " + b.bot.getUsername() + " hop out from " + b.bot.getLocation() + " (" + why + ")");
        try { Spectacle.teleport(b.bot, loc(W7Places.GATE_OUT)); } catch (Throwable ignored) { }
    }

    static void walkFar(Player bot, Location dest) {
        Location here = bot.getLocation();
        double d = here.getZ() != 0 ? 999 : here.getDistance(dest);
        Location nav = null;
        try { if (NavWalker.ON && here.getZ() == 0) nav = NavWalker.step(bot, dest); } catch (Throwable ignored) { }
        Location step = nav != null ? nav : d <= 25 ? dest : Location.create(here.getX() + (int) Math.round((dest.getX() - here.getX()) * 25 / d), here.getY() + (int) Math.round((dest.getY() - here.getY()) * 25 / d), 0);
        try { ((AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
    }
    static void walkNear(Player bot, Location dest) { try { ((AIPlayer) bot).walkToPosSmart(dest); } catch (Throwable ignored) { } }

    static Node scenery(SmartAdventurers.Brain b, int[] ids, String name, String opt, Location at, int radius) {
        try {
            return b.scriptAPI.getNearestObjectByPredicate(node -> {
                if (!(node instanceof Scenery) || node.getLocation().getZ() != at.getZ() || node.getLocation().getDistance(at) > radius) return false;
                Scenery sc = (Scenery) node;
                if (ids != null) { for (int id : ids) if (sc.getId() == id) return true; return false; }
                if (sc.getName() == null || !sc.getName().equalsIgnoreCase(name)) return false;
                for (String o : sc.getDefinition().getOptions()) if (o != null && o.equalsIgnoreCase(opt)) return true;
                return false;
            });
        } catch (Throwable t) { return null; }
    }

    static void barbTick(SmartAdventurers.Brain b, long now) {
        final Player bot = b.bot; final Barb s = b.barb;
        Location here = bot.getLocation();
        int zone = W7Places.barbZone(here.getX(), here.getY(), here.getZ());
        if (s.passKind != 0 && zone != s.passZone) { // the gate or the pipe let it through
            if (s.passKind == PASS_GATE) W7Places.barbGateWalk++; else W7Places.barbPipe++;
            s.passKind = 0; s.passZone = -1; s.gateTries = 0; s.pipeTries = 0; s.stall = 0;
        }
        if (zone != W7Places.Z_OUT) s.inside = true;
        if (!s.leaving && now > b.agiUntil) leave(b, now, BotChat.pick("Legs are done. That'll do.", "Enough laps for today."));
        if (s.leaving && zone != W7Places.Z_OUT && now > s.hardUntil) { hopOut(b, now, "out of time on the way out"); return; }
        if (bot.getWalkingQueue().isMoving() || bot.getPulseManager().hasPulseRunning()) return;
        if (!s.leaving && zone == W7Places.Z_OUT && s.inside) leave(b, now, "Lost the course. Done for today."); // died or was rescued off the course
        if (!s.leaving && zone == W7Places.Z_COURSE && W7Places.barbHurt(bot.getSkills().getLifepoints())) { W7Places.barbLowLife++; leave(b, now, "Too bruised for another fall. Done."); }
        switch (W7Places.barbAct(zone, s.leaving)) {
            case W7Places.A_END: finish(b); return;
            case W7Places.A_TRAVEL: travel(b, now, here); return;
            case W7Places.A_PIPE_IN: pipe(b, now, here, zone, true); return;
            case W7Places.A_PIPE_OUT: pipe(b, now, here, zone, false); return;
            case W7Places.A_GATE_OUT: gate(b, now, here, zone, false); return;
            case W7Places.A_PIT: pit(b, now, here); return;
            default: lap(b, now, here); return;
        }
    }

    /** Outside: to the tile west of the gate (the Gnome visit's rule: a paid teleport when far or stalled, else on foot), then through. */
    static void travel(SmartAdventurers.Brain b, long now, Location here) {
        final Player bot = b.bot; final Barb s = b.barb;
        Location out = loc(W7Places.GATE_OUT);
        double d = here.getZ() != 0 ? 999 : here.getDistance(out);
        if (d <= 2) { gate(b, now, here, W7Places.Z_OUT, true); return; }
        Location via = here.getZ() == 0 && W7Places.barbVia(here.getX(), here.getY()) ? loc(W7Places.BARB_VIA) : null; // w13d: Seers/Catherby walk round by 2566,3413
        double prog = via != null ? here.getDistance(via) + via.getDistance(out) : d; // w13d: progress along via + via-to-gate, never under the straight d, so the switch to the gate is progress
        if (prog < s.best - 1) { s.best = prog; s.stall = 0; } else s.stall++;
        long tp = Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556));
        if ((d > AfkTasks.Runner.TELEPORT_MIN || s.stall > 8) && Ledger.Wallet.coins(bot) >= tp * 3 && Ledger.Wallet.payTeleport(bot)) {
            try { Spectacle.teleport(bot, out); } catch (Throwable ignored) { }
            s.stall = 0; return;
        }
        if (s.stall > 12) { leave(b, now, "Can't find the way. Forget it."); return; }
        walkFar(bot, via != null ? via : out);
    }

    /**
     * The outpost gate. The engine opens it only after the Barbarian bar crawl (BarbarianOutpostCourse.handle, ids 2115/2116);
     * for bots the crawl is waived: the same DoorActionHandler.handleAutowalkDoor call the engine makes for a finished crawl.
     * Twice without getting through and the bot is set on the tile across the gate (2 tiles; counted as barbGateHop).
     */
    static void gate(SmartAdventurers.Brain b, long now, Location here, int zone, boolean in) {
        final Player bot = b.bot; final Barb s = b.barb;
        Location stand = loc(in ? W7Places.GATE_OUT : W7Places.GATE_IN), across = loc(in ? W7Places.GATE_IN : W7Places.GATE_OUT);
        if (s.passKind == PASS_GATE) {
            if (now < s.passAt) return;
            s.passKind = 0; s.passZone = -1; s.gateTries++;
        }
        if (here.getDistance(stand) > 1) {
            if (++s.stall > 12) { s.stall = 0; s.gateTries = 2; } else { walkNear(bot, stand); return; }
        }
        Node g = s.gateTries >= 2 ? null : scenery(b, W7Places.GATE_IDS, null, null, loc(W7Places.GATE_AT), 3);
        if (g == null) {
            W7Places.barbGateHop++; s.gateTries = 0; s.stall = 0;
            System.out.println("[W7] barb " + bot.getUsername() + " set across the outpost gate (" + (in ? "in" : "out") + ") from " + here);
            try { Spectacle.teleport(bot, across); } catch (Throwable ignored) { }
            return;
        }
        s.stall = 0;
        try { core.game.global.action.DoorActionHandler.handleAutowalkDoor(bot, (Scenery) g); } catch (Throwable t) { s.gateTries++; return; }
        s.passKind = PASS_GATE; s.passZone = zone; s.passAt = now + 8_000;
    }

    /** The obstacle pipe between the yard and the course: the engine's own shortcut (Agility 35, 10 xp). */
    static void pipe(SmartAdventurers.Brain b, long now, Location here, int zone, boolean in) {
        final Player bot = b.bot; final Barb s = b.barb;
        Location stand = loc(in ? W7Places.PIPE_N : W7Places.PIPE_S);
        if (s.passKind == PASS_PIPE) {
            if (now < s.passAt) return;
            s.passKind = 0; s.passZone = -1;
            if (++s.pipeTries >= 3) { W7Places.barbPipeFail++; s.pipeTries = 0; if (in) leave(b, now, "Can't get through the pipe. Another day."); else hopOut(b, now, "the pipe would not let it out"); return; }
        }
        if (here.getDistance(stand) > 2) {
            if (++s.stall > 12) { s.stall = 0; if (in) leave(b, now, "Can't reach the pipe. Another day."); else hopOut(b, now, "no way to the pipe"); return; }
            walkNear(bot, stand); return;
        }
        s.stall = 0;
        Node n = scenery(b, new int[]{W7Places.PIPE_ID}, null, null, loc(W7Places.PIPE_AT), 3);
        if (n == null) { s.passKind = PASS_PIPE; s.passZone = zone; s.passAt = now; return; } // counts as a failed try next tick
        try { b.scriptAPI.interact(bot, n, "Squeeze-through"); } catch (Throwable ignored) { }
        s.passKind = PASS_PIPE; s.passZone = zone; s.passAt = now + 12_000;
    }

    /** Fell off the rope: the pit under it (2549,9951). The ladder is the way back up into the course. */
    static void pit(SmartAdventurers.Brain b, long now, Location here) {
        final Player bot = b.bot; final Barb s = b.barb;
        if (!s.inPit) { s.inPit = true; s.pitTries = 0; s.pitAt = 0; W7Places.barbPit++; }
        if (now < s.pitAt) return;
        if (++s.pitTries > 3) { hopOut(b, now, "no ladder out of the pit"); return; }
        Node n = scenery(b, null, "Ladder", "Climb-up", here, 15);
        if (n != null) try { b.scriptAPI.interact(bot, n, "Climb-up"); } catch (Throwable ignored) { }
        s.pitAt = now + 8_000;
    }

    static void lap(SmartAdventurers.Brain b, long now, Location here) {
        final Player bot = b.bot; final Barb s = b.barb;
        s.inPit = false;
        double xp = bot.getSkills().getExperience(Skills.AGILITY);
        if (b.agiWait > 0) {
            if (W7Places.barbDone(b.agiStep, xp > b.agiXp, here.getZ())) {
                b.agiWait = 0; b.agiTries = 0; b.agiStep++;
                if (b.agiStep >= W7Places.BARB.length) {
                    b.agiStep = 0; b.agiLaps++;
                    if (b.agiLaps >= b.agiGoal) { leave(b, now, BotChat.pick(b.agiGoal + " laps. The barbarians were not impressed.", "Done. " + b.agiGoal + " laps.")); return; }
                }
            } else if (now < b.agiWait) return;
            else { b.agiWait = 0; W7Places.barbFalls++; if (++b.agiTries > (b.agiStep == 0 ? 6 : 3)) { leave(b, now, "Can't get past this one. Another day."); return; } }
        }
        b.agiStep = W7Places.barbStep(b.agiStep, here.getX(), here.getY(), here.getZ());
        Object[] ob = W7Places.BARB[b.agiStep];
        final Location at = Location.create((Integer) ob[3], (Integer) ob[4], (Integer) ob[5]);
        int sx = (Integer) ob[7], sy = (Integer) ob[8];
        if (sx != 0 && here.getZ() == 0) { // to the obstacle's start tile first (the rope swings only from 2551,3554)
            Location stand = Location.create(sx, sy, 0);
            if (here.getDistance(stand) > (b.agiStep == 0 ? 0 : 2)) {
                if (++s.stall > 12) { s.stall = 0; leave(b, now, "Lost the course. Done for today."); return; }
                walkNear(bot, stand); return;
            }
        }
        s.stall = 0;
        int id = (Integer) ob[0];
        Node n = scenery(b, id > 0 ? new int[]{id} : null, (String) ob[1], (String) ob[2], at, (Integer) ob[6]);
        if (n == null) {
            if (++b.agiTries > 3) { leave(b, now, "Lost the course. Done for today."); return; }
            System.out.println("[W7] barb " + bot.getUsername() + " can't see the " + ob[1] + " from " + here + " (step " + b.agiStep + ")");
            return;
        }
        b.agiXp = xp;
        try { b.scriptAPI.interact(bot, n, (String) ob[2]); } catch (Throwable t) { b.agiTries++; return; }
        b.agiWait = now + (b.agiStep == 0 ? 8_000 : 15_000);
    }

    // ------------------------------------------------------------------ Fishing: the Fishing Guild (-Dbots.fish.guild)

    static final Location GUILD_AT = loc(W7Places.GUILD_SPOT);

    /** Smart bots fishing at the guild now: {cage + harpoon runners, shark runners}. */
    static int[] guildSeats() {
        int n = 0, sh = 0;
        for (AfkTasks.Runner r : AfkTasks.RUNNING.values()) {
            if (r == null || r.p == null || !r.p.isArtificial() || r.task == null || r.task.kind != AfkTasks.Kind.FISH || r.spot == null) continue;
            if (!W7Places.inGuild(r.spot.getX(), r.spot.getY())) continue;
            if (r.w5SpotRule == W5aSkills.SPOT_BIGNET) sh++; else n++;
        }
        return new int[]{n, sh};
    }

    /** A cage, harpoon or shark job's start: true when this trip fishes at the guild (the caller swaps the spot). */
    static boolean guildStart(Player p, SmartAdventurers.Job job, boolean errand) {
        int row = W7Places.guildRow(job.task == AfkTasks.Task.CAGE, job.task == AfkTasks.Task.HARPOON, job.sharkRow);
        int fishing = p.getSkills().getStaticLevel(Skills.FISHING);
        if (row == W7Places.ROW_NONE || errand || fishing < W7Places.GUILD_LEVEL) return false;
        boolean enter = false;
        try { enter = Gates.canEnter(p, GUILD_AT); } catch (Throwable ignored) { }
        int[] seats = guildSeats();
        if (!W7Places.guildPick(true, row, false, fishing, enter, seats[0], seats[1])) { if (enter) W7Places.guildFull++; else W7Places.guildNoEntry++; return false; }
        if (row == W7Places.ROW_SHARK) W7Places.guildShark++; else if (row == W7Places.ROW_CAGE) W7Places.guildCage++; else W7Places.guildHarpoon++;
        System.out.println("[W7] guild " + p.getUsername() + " fishes " + job.label().toLowerCase(Locale.ROOT) + " at the Fishing Guild (Fishing " + fishing + ", seats " + (seats[0] + (row == W7Places.ROW_SHARK ? 0 : 1)) + "/" + W7Places.GUILD_CAP + ", shark " + (seats[1] + (row == W7Places.ROW_SHARK ? 1 : 0)) + "/" + W7Places.GUILD_SHARK_SEATS + ")");
        return true;
    }
}
