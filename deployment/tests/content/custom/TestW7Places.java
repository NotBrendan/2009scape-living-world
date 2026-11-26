package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W7 places, wave 4 (the design notes section 4), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW7Places <addons-src>/content/custom
 * Run it twice: bare (both flags off) and with -Dbots.agility.barb=true -Dbots.fish.guild=true (the F case checks the
 * statics follow the properties). Per flag: the pure rules (W7Places) and the wiring read from the source
 * (SmartAdventurers and MarketBrain cannot class-init outside a world): each hook sits behind its flag and train-61's
 * expression is still there for the flag-off path. Routes: the design notes (p38-navport).
 */
public final class TestW7Places {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW7Places] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW7Places]   missing: " + p); return false; } return true; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sa = read(dir, "SmartAdventurers.java"), mb = read(dir, "MarketBrain.java"), run = read(dir, "W7Run.java");

        // F: the statics follow their properties, default off
        check("F flags follow -D (default off)", W7Places.AGI_BARB == Boolean.getBoolean("bots.agility.barb") && W7Places.FISH_GUILD == Boolean.getBoolean("bots.fish.guild")
                && W7Places.anyOn() == (W7Places.AGI_BARB || W7Places.FISH_GUILD) && W7Places.line().startsWith("[W7] on=[" + W7Places.flags() + "]"));

        // ---- Agility: Barbarian Outpost
        boolean off = true;
        for (int a = 1; a <= 99; a++) for (int seats = 0; seats <= 5; seats++) if (W7Places.noAgility(false, a, seats) != (a >= 35)) off = false;
        check("barb off: wantsAgility's rule is train-61's 'agi >= 35' at every level and seat count", off && !W7Places.barbCourse(false, 40) && !W7Places.barbCourse(false, 99));
        check("barb on: under 35 trains (Gnome), 35 to cap-1 trains while a seat is free, cap and up never",
                !W7Places.noAgility(true, 1, 99) && !W7Places.noAgility(true, 34, 99) && !W7Places.noAgility(true, 35, 0) && !W7Places.noAgility(true, W7Places.BARB_CAP - 1, W7Places.BARB_SEATS - 1)
                        && W7Places.noAgility(true, 35, W7Places.BARB_SEATS) && W7Places.noAgility(true, W7Places.BARB_CAP, 0) && W7Places.noAgility(true, 99, 0)
                        && !W7Places.barbCourse(true, 34) && W7Places.barbCourse(true, 35) && W7Places.BARB_LEVEL == 35 && W7Places.BARB_CAP > 35);
        W7Places.BARB_SEAT.clear();
        W7Places.barbSit("a", 1_000); W7Places.barbSit("b", 2_000); W7Places.barbSit("a", 1_500);
        boolean seats = W7Places.barbSeatsUsed(0) == 2 && W7Places.barbSeatsUsed(1_500) == 1 && W7Places.barbSeatsUsed(2_000) == 0;
        W7Places.barbStand("b");
        check("barb seats: one per bot, freed on leaving, and a seat times out on its own (a rescued bot cannot leak it)", seats && W7Places.barbSeatsUsed(0) == 1);
        W7Places.BARB_SEAT.clear();
        check("barb zones (nav grid flood fill): outside, yard, course, upstairs, the pit",
                W7Places.barbZone(2544, 3569, 0) == W7Places.Z_OUT && W7Places.barbZone(2545, 3570, 0) == W7Places.Z_OUT && W7Places.barbZone(2546, 3569, 0) == W7Places.Z_YARD
                        && W7Places.barbZone(2552, 3561, 0) == W7Places.Z_YARD && W7Places.barbZone(2549, 3556, 0) == W7Places.Z_YARD && W7Places.barbZone(2552, 3557, 0) == W7Places.Z_COURSE
                        && W7Places.barbZone(2551, 3554, 0) == W7Places.Z_COURSE && W7Places.barbZone(2545, 3542, 0) == W7Places.Z_COURSE && W7Places.barbZone(2534, 3545, 0) == W7Places.Z_COURSE
                        && W7Places.barbZone(2538, 3553, 0) == W7Places.Z_COURSE && W7Places.barbZone(2536, 3547, 1) == W7Places.Z_COURSE && W7Places.barbZone(2549, 9951, 0) == W7Places.Z_PIT
                        && W7Places.barbZone(2528, 3545, 0) == W7Places.Z_OUT && W7Places.barbZone(2554, 3557, 0) == W7Places.Z_OUT && W7Places.barbZone(2474, 3438, 0) == W7Places.Z_OUT
                        && W7Places.barbZone(3222, 3218, 0) == W7Places.Z_OUT && W7Places.barbZone(2546, 3569, 1) == W7Places.Z_OUT);
        check("barb next action: in = travel, pipe, laps; out = pipe, gate, end; the pit first either way",
                W7Places.barbAct(W7Places.Z_OUT, false) == W7Places.A_TRAVEL && W7Places.barbAct(W7Places.Z_YARD, false) == W7Places.A_PIPE_IN && W7Places.barbAct(W7Places.Z_COURSE, false) == W7Places.A_LAP
                        && W7Places.barbAct(W7Places.Z_COURSE, true) == W7Places.A_PIPE_OUT && W7Places.barbAct(W7Places.Z_YARD, true) == W7Places.A_GATE_OUT && W7Places.barbAct(W7Places.Z_OUT, true) == W7Places.A_END
                        && W7Places.barbAct(W7Places.Z_PIT, false) == W7Places.A_PIT && W7Places.barbAct(W7Places.Z_PIT, true) == W7Places.A_PIT);
        check("barb step resync: upstairs = ledge then ladder, a ledge fall goes on to wall 1, the lane between the walls only goes on",
                W7Places.barbStep(2, 2538, 3546, 1) == W7Places.STEP_LEDGE && W7Places.barbStep(3, 2532, 3547, 1) == W7Places.STEP_LADDER && W7Places.barbStep(3, 2534, 3545, 0) == W7Places.STEP_WALL1
                        && W7Places.barbStep(4, 2532, 3546, 0) == W7Places.STEP_WALL1 && W7Places.barbStep(5, 2537, 3553, 0) == W7Places.STEP_WALL2 && W7Places.barbStep(5, 2540, 3553, 0) == W7Places.STEP_WALL3
                        && W7Places.barbStep(0, 2549, 3553, 0) == 0 && W7Places.barbStep(1, 2545, 3542, 0) == 1 && W7Places.barbStep(0, 2543, 3553, 0) == 0 && W7Places.barbStep(5, 2535, 3553, 0) == 5);
        check("barb step done: xp for every obstacle, the ground floor for the ladder; hurt under 6 lifepoints",
                W7Places.barbDone(0, true, 0) && !W7Places.barbDone(0, false, 0) && W7Places.barbDone(W7Places.STEP_LADDER, false, 0) && !W7Places.barbDone(W7Places.STEP_LADDER, true, 1)
                        && W7Places.barbHurt(5) && !W7Places.barbHurt(6));
        boolean table = W7Places.BARB.length == 8 && Math.abs(W7Places.BARB_LAP_XP - 152.2) < 1e-9;
        int[] ids = {2282, 2294, 20211, 2302, 0, 1948, 1948, 1948};
        for (int i = 0; i < W7Places.BARB.length && table; i++) {
            Object[] o = W7Places.BARB[i];
            table = o.length == 9 && (Integer) o[0] == ids[i] && W7Places.barbZone((Integer) o[3], (Integer) o[4], (Integer) o[5]) == W7Places.Z_COURSE
                    && ((Integer) o[7] == 0 || W7Places.barbZone((Integer) o[7], (Integer) o[8], 0) == W7Places.Z_COURSE);
        }
        check("barb course table: the engine's 5 obstacle ids + the ladder, every object and start tile inside the course; gate and pipe tiles on the right sides",
                table && W7Places.barbZone(W7Places.GATE_OUT[0], W7Places.GATE_OUT[1], 0) == W7Places.Z_OUT && W7Places.barbZone(W7Places.GATE_IN[0], W7Places.GATE_IN[1], 0) == W7Places.Z_YARD
                        && W7Places.barbZone(W7Places.PIPE_N[0], W7Places.PIPE_N[1], 0) == W7Places.Z_YARD && W7Places.barbZone(W7Places.PIPE_S[0], W7Places.PIPE_S[1], 0) == W7Places.Z_COURSE
                        && (Integer) W7Places.BARB[0][7] == 2551 && (Integer) W7Places.BARB[0][8] == 3554);
        check("barb wiring: three hooks behind the flag, train-61's rule kept for flag off, the Gnome code untouched",
                all(sa, "if (W7Places.AGI_BARB ? W7Places.noAgility(true, agi, W7Places.barbSeatsUsed(now)) : agi >= 35) return false;",
                        "if (W7Places.AGI_BARB) { barb = null; if (W7Places.barbCourse(true, bot.getSkills().getStaticLevel(Skills.AGILITY))) { W7Run.startBarb(this, now); return; } }",
                        "if (W7Places.AGI_BARB && barb != null) { W7Run.barbTick(this, now); return; }",
                        "Object[] ob = GNOME[agiStep];", "if (now > agiUntil) { endAgility(BotChat.pick(\"Legs are done. That'll do.\", \"Enough laps for today.\")); return; }")
                        && sa.indexOf("if (W7Places.AGI_BARB ? W7Places.noAgility") < sa.indexOf("if (agilityNudge) return true;")
                        && all(run, "core.game.global.action.DoorActionHandler.handleAutowalkDoor(bot, (Scenery) g);", "if (s.leaving && zone != W7Places.Z_OUT && now > s.hardUntil) { hopOut(", "W7Places.barbStand(key(b.bot));", "b.endAgility(s.why);"));

        // ---- Fishing: the Fishing Guild
        boolean goff = true;
        for (int row = 0; row <= 3; row++) for (int lvl = 1; lvl <= 99; lvl += 7) if (W7Places.guildPick(false, row, false, lvl, true, 0, 0)) goff = false;
        check("guild off: never picked; the shark row's capacity is the Catherby cap", goff && W7Places.sharkCapacity(false, 2) == 2 && W7Places.sharkCapacity(false, 4) == 4);
        check("guild on: Fishing 68, the door open, a free seat, no errand; cage and harpoon share seats, the shark row has its own",
                W7Places.guildPick(true, W7Places.ROW_CAGE, false, 68, true, 0, 0) == (W7Places.GUILD_CAP > 0) && !W7Places.guildPick(true, W7Places.ROW_CAGE, false, 67, true, 0, 0)
                        && !W7Places.guildPick(true, W7Places.ROW_CAGE, false, 99, false, 0, 0) && !W7Places.guildPick(true, W7Places.ROW_HARPOON, true, 99, true, 0, 0)
                        && !W7Places.guildPick(true, W7Places.ROW_NONE, false, 99, true, 0, 0)
                        && W7Places.guildPick(true, W7Places.ROW_HARPOON, false, 80, true, W7Places.GUILD_CAP - 1, 99) == (W7Places.GUILD_CAP > 0) && !W7Places.guildPick(true, W7Places.ROW_HARPOON, false, 80, true, W7Places.GUILD_CAP, 0)
                        && W7Places.guildPick(true, W7Places.ROW_SHARK, false, 76, true, 99, 0) == (W7Places.GUILD_SHARK_SEATS > 0) && !W7Places.guildPick(true, W7Places.ROW_SHARK, false, 76, true, 0, W7Places.GUILD_SHARK_SEATS)
                        && W7Places.sharkCapacity(true, 2) == 2 + W7Places.GUILD_SHARK_SEATS);
        check("guild rows and box: cage, harpoon, shark; the spot and the bank stand are inside Gates' fishing-guild box, Catherby is not",
                W7Places.guildRow(true, false, false) == W7Places.ROW_CAGE && W7Places.guildRow(false, true, false) == W7Places.ROW_HARPOON && W7Places.guildRow(false, true, true) == W7Places.ROW_SHARK
                        && W7Places.guildRow(false, false, false) == W7Places.ROW_NONE && W7Places.inGuild(W7Places.GUILD_SPOT[0], W7Places.GUILD_SPOT[1]) && W7Places.inGuild(2586, 3420)
                        && !W7Places.inGuild(2837, 3432) && !W7Places.inGuild(2611, 3393) && W7Places.GUILD_LEVEL == 68);
        check("guild wiring: the spot swap, the raw-fish rule and the shark capacity sit behind the flag; W5a's lines are unchanged",
                all(sa, "if (W7Places.FISH_GUILD && job.task.kind == AfkTasks.Kind.FISH && W7Run.guildStart(p, job, errandFor != null || job.chain != 0)) { w7Guild = true; spotAt = W7Run.GUILD_AT; spotName = W7Places.GUILD_NAME; }",
                        "Location spotAt; String spotName; boolean w7Guild = false;", "if (w7Guild) r.cookHaul = false;",
                        "if (W7Places.FISH_GUILD && W5aSkills.SHARK && j.sharkRow) return W7Places.sharkCapacity(true, W5aSkills.SHARK_CAP);",
                        "if (W5aSkills.SHARK && j.sharkRow) return W5aSkills.SHARK_CAP;", "if (W5aSkills.COOK_FIRE && r.cookHaul) w5FireKit(p, spotAt);")
                        && sa.indexOf("if (W7Places.FISH_GUILD && W5aSkills.SHARK && j.sharkRow)") < sa.indexOf("if (W5aSkills.SHARK && j.sharkRow) return W5aSkills.SHARK_CAP;")
                        && sa.indexOf("if (w7Guild) r.cookHaul = false;") < sa.indexOf("if (W5aSkills.COOK_FIRE && r.cookHaul) w5FireKit(p, spotAt);")
                        && sa.indexOf("r.cookHaul = errandFor != null ? errandCook") < sa.indexOf("if (w7Guild) r.cookHaul = false;"));
        check("hourly line: its own timer, only with a W7 flag on", all(mb, "if (W7Places.anyOn()) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(W7Places.line()); }"));

        System.out.println("[TestW7Places] SUMMARY " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
