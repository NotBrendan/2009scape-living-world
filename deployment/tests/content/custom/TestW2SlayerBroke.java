package content.custom;

import content.global.skill.slayer.SlayerManager;
import content.global.skill.slayer.Tasks;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.map.Location;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * W2 lane S (2026-11-19): the broke-bot task trip (-Dbots.slayer.brokeTrip) and the pre-held task seed (-Dsmartbots.seed.task).
 * Plain java, no world, no port, no pulser. The flags are read once at class load, so run it with the flag off and again with it on:
 *   cd <game dir>;  java [-Dbots.slayer=true [-Dbots.slayer.brokeTrip=true]] -cp "<build>;server.jar" content.custom.TestW2SlayerBroke <addons-src>/content/custom <server .conf>
 * <game dir> = a folder holding data/ (cache, configs, botdata), e.g. a COPY of farm/pristine/game; the conf is a world's test.conf.
 * Only the server's config parser and cache are loaded (what the item / NPC definitions and AIPlayer's name list read).
 *  P   pure rule: freeTrip, brokeStops, tripDue.
 *  S   seed parsing and guard: Seed.task, Seed.taskFor, "task" counts as asked, this jvm (no harness markers) seeds nothing.
 *  W   (source) enlist calls Seed.task once the brain exists; Seed.task and Slayer.hold are gated; taskTrip asks the rule.
 *  With the conf (the real caller, Brain.chooseJob -> Slayer.taskTrip; needs -Dbots.slayer=true, else these are counted as FAIL):
 *  C00 Seed.task(brain, bot) in this jvm (no harness markers) seeds nothing.
 *  C0  Slayer.hold (what Seed.task calls): the bot holds GOBLINS x8 in the server's SlayerManager and in the brain's state.
 *  C1  control, 1,000 gp at the Lumbridge goblins: chooseJob returns the goblin job (the trip is reachable here with either flag state).
 *  C2  0 gp, same place, streak: chooseJob returns the goblin job with brokeTrip, never without (the old behaviour).
 *  C3  0 gp, no streak: the 1-in-2 dice still applies with brokeTrip (some, not all, of 60); none without.
 *  C4  0 gp, the spot a teleport away: Slayer.taskTrip refuses with either flag state (not a free trip); chooseJob does not take it.
 *  C5  Slayer.tripFirst (needs fix6 + fix8): a broke holder's trip goes first with brokeTrip only.
 */
public final class TestW2SlayerBroke {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW2SlayerBroke] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String f) throws Exception { return new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8); }
    static final Location GOBLINS = Location.create(3250, 3235, 0), FAR = Location.create(2662, 3305, 0); // AfkTasks.LUM_GOB; Ardougne (588 tiles away)

    public static void main(String[] a) throws Exception {
        final boolean on = Slayer.BROKE_TRIP;
        System.out.println("[TestW2SlayerBroke] flags: bots.slayer " + Slayer.ON + ", brokeTrip " + on + ", fix8 " + Slayer.FIX8 + ", openCheck " + Slayer.OPEN_CHECK + ", clerk.fix2 " + Ledger.CLERK_FIX2);
        pure(); seed();
        if (a.length > 0) wiring(a[0]);
        if (a.length > 1) {
            try { caller(a[1], on); } catch (Throwable t) { check("C the real-caller checks ran: " + t, false); t.printStackTrace(System.out); }
        } else System.out.println("[TestW2SlayerBroke] NOT RUN: C0-C5 (no server conf given)");
        System.out.println("[TestW2SlayerBroke] DONE " + pass + " pass, " + fail + " fail (brokeTrip " + on + ")");
        System.exit(fail > 0 ? 1 : 0);
    }

    static void pure() {
        check("P1 freeTrip: flag on + broke + within walking range only",
                Slayer.freeTrip(true, true, false) && !Slayer.freeTrip(false, true, false) && !Slayer.freeTrip(true, false, false) && !Slayer.freeTrip(true, true, true));
        check("P2 brokeStops: a broke bot is stopped unless the trip is free; a bot with coins never",
                Slayer.brokeStops(true, false) && !Slayer.brokeStops(true, true) && !Slayer.brokeStops(false, false) && !Slayer.brokeStops(false, true));
        boolean old = !Slayer.tripDue(true, true, true, 0, true) && Slayer.tripDue(true, true, false, 0, true);
        boolean rest = !Slayer.tripDue(true, false, Slayer.brokeStops(true, true), 0, true) && !Slayer.tripDue(true, true, Slayer.brokeStops(true, true), Slayer.SPOT_CAP, true)
                && !Slayer.tripDue(true, true, Slayer.brokeStops(true, true), 0, false) && !Slayer.tripDue(false, true, Slayer.brokeStops(true, true), 0, true);
        check("P3 tripDue: broke refuses (old rule " + old + "); a free trip passes; task, spot cap, dice and the master flag still gate it " + rest,
                old && Slayer.tripDue(true, true, Slayer.brokeStops(true, true), 0, true) && !Slayer.tripDue(true, true, Slayer.brokeStops(true, false), 0, true) && rest);
        check("P4 the broke line is the brain's (400 gp)", Slayer.BROKE8 == 400 && SmartAdventurers.Brain.BROKE_LINE == 400);
    }

    static void seed() {
        String[] t = Seed.task("bears:10"), t2 = Seed.task(" Cave bugs : 3 "), t3 = Seed.task("COWS");
        boolean good = t != null && "BEARS".equals(t[0]) && "10".equals(t[1]) && t2 != null && "CAVE_BUGS".equals(t2[0]) && "3".equals(t2[1]) && t3 != null && "COWS".equals(t3[0]) && "10".equals(t3[1])
                && Seed.task("goblins:255") != null && Seed.task("goblins:1") != null;
        String[] junk = {null, "", "  ", ":", ":5", "cows:", "cows:0", "cows:-3", "cows:256", "cows:x", "cows:5:6", "c0ws:5", "cows;5", "cows,bats:5", "12:5"};
        boolean bad = true; for (String j : junk) if (Seed.task(j) != null) { bad = false; System.out.println("[TestW2SlayerBroke] not refused: " + j); }
        check("S1 Seed.task: name:n parsed (upper case, spaces as _, bare name = 10, n 1..255) " + good + ", junk null and never thrown " + bad, good && bad);
        check("S2 Seed.taskFor: exact, plural and singular forms; unknown -> null",
                Seed.taskFor(Tasks.class, "BEARS") == Tasks.BEARS && Seed.taskFor(Tasks.class, "BEAR") == Tasks.BEARS && Seed.taskFor(Tasks.class, "DOGS") == Tasks.DOG
                        && Seed.taskFor(Tasks.class, "GOBLINS") == Tasks.GOBLINS && Seed.taskFor(Tasks.class, "DRAGONFRUIT") == null && Seed.taskFor(Tasks.class, "") == null && Seed.taskFor(Tasks.class, null) == null);
        Map<String, String> m = new HashMap<>(); m.put("task", "bears:10");
        boolean asked = Seed.asked(m::get); m.put("task", " "); boolean blank = !Seed.asked(m::get);
        check("S3 guard: .seed.task counts as a seed flag " + asked + " (blank does not " + blank + "); allowed() needs all three harness markers",
                asked && blank && Seed.allowed(true, "43601", "127.0.0.1") && !Seed.allowed(false, "43601", "127.0.0.1") && !Seed.allowed(true, null, "127.0.0.1") && !Seed.allowed(true, "43601", "0.0.0.0"));
        boolean quiet = true; try { Seed.task(null, null); } catch (Throwable e) { quiet = false; }
        check("S4 this jvm (no harness markers): allowed " + Seed.ALLOWED + ", on " + Seed.ON + ", TASK null, Seed.task(brain, bot) is a no-op; the hunt yields to a holder when a task is seeded",
                !Seed.ALLOWED && !Seed.ON && Seed.TASK == null && quiet && Seed.TASKED.isEmpty() && !Seed.huntApplies(true, true) && Seed.huntApplies(true, false) && Seed.huntApplies(false, true));
    }

    static void wiring(String dir) throws Exception {
        String sa = read(dir + "/SmartAdventurers.java"), seed = read(dir + "/Seed.java"), sl = read(dir + "/Slayer.java");
        int en = sa.indexOf("static void enlist("), brain = sa.indexOf("Brain brain = new Brain();", en), gbc = sa.indexOf("new GeneralBotCreator(brain, bot);", en), hook = sa.indexOf("Seed.task(brain, bot);", en);
        boolean w1 = en > 0 && brain > en && gbc > brain && hook > gbc && hook - gbc < 80 && sa.indexOf("Seed.task(", hook + 1) < 0;
        boolean w2 = seed.contains("if (!ON || TASK == null || b == null || p == null || !p.isArtificial()) return;") && seed.contains("static final String[] TASK = ON ? task(System.getProperty(P + \"task\")) : null;")
                && seed.contains("huntApplies(SLAYER > 0 || TASK != null, hasTask)") && sl.contains("if (!ON || b == null || bot == null || t == null || n <= 0) return false;");
        boolean w3 = sl.contains("static final boolean BROKE_TRIP = Boolean.getBoolean(\"bots.slayer.brokeTrip\");")
                && sl.contains("final boolean free = BROKE_TRIP && broke && freeTrip(true, true, tripFar(b, nearest));")
                && sl.contains("if (!tripDue(true, s.had != null, BROKE_TRIP ? brokeStops(broke, free) : broke, spotSlayers(b, nearest), s.streak || SmartAdventurers.RNG.nextInt(TRIP_ODDS) == 0)) return false;")
                && sl.contains("!BROKE_TRIP && b.broke());") && sa.contains("Slayer.taskTrip(this, slayJob, broke)");
        check("W wiring: enlist seeds the task once the brain exists " + w1 + ", seed and hold gated " + w2 + ", taskTrip / tripFirst ask the flag and chooseJob passes its broke " + w3, w1 && w2 && w3);
    }

    // ---- the real caller, offline

    static SmartAdventurers.Job goblinJob() { for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt == null && j.task == AfkTasks.Task.GOBLIN) return j; return null; }

    static void coins(AIPlayer b, int n) {
        b.getInventory().clear(); for (Item it : b.getBank().toArray()) if (it != null) b.getBank().remove(it);
        if (n > 0) b.getInventory().add(new Item(995, n));
    }

    static void rearm(SmartAdventurers.Brain br, boolean streak) {
        Slayer.St s = br.slaySt; s.streak = streak; s.trip = null; s.parkedUntil = 0; s.teles = 0; s.paidSnap = -1; s.exempt = 0; s.capLog = 0;
        br.blacklist.clear(); br.stuckLegs.clear(); br.lastJob = null;
    }

    static int trips(SmartAdventurers.Brain br, SmartAdventurers.Job want, int n, boolean streak) {
        int got = 0;
        for (int i = 0; i < n; i++) { rearm(br, streak); SmartAdventurers.Job j = br.chooseJob(false); if (j == want && br.slaySt.trip == want) got++; }
        return got;
    }

    static void caller(String conf, boolean on) throws Throwable {
        core.game.system.config.ServerConfigParser.INSTANCE.parse(conf);
        core.cache.Cache.init(core.ServerConstants.CACHE_PATH);
        Class.forName("content.custom.AfkTasks"); // the server's order: AfkTasks before its Task enum
        SmartAdventurers.Job gob = goblinJob();
        if (!Slayer.ON || gob == null) { check("C needs -Dbots.slayer=true (" + Slayer.ON + ") and the goblin job (" + (gob != null) + ")", false); return; }
        AIPlayer b = new AIPlayer(GOBLINS);
        int[][] lv = {{Skills.HITPOINTS, 40}, {Skills.ATTACK, 40}, {Skills.STRENGTH, 40}, {Skills.DEFENCE, 40}};
        for (int[] l : lv) { b.getSkills().setStaticLevel(l[0], l[1]); b.getSkills().setLevel(l[0], l[1]); }
        try { b.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
        SmartAdventurers.Brain br = new SmartAdventurers.Brain();
        br.bot = b; br.scriptAPI = new core.game.bots.ScriptAPI(b); br.nextThink = Long.MAX_VALUE;
        Slayer.St s = br.slaySt;

        Seed.task(br, b); // refused here (no harness markers): nothing held, nothing recorded
        check("C00 Seed.task(brain, bot) without the harness markers seeds nothing: task " + s.had + ", server task " + Slayer.hasTask(b), s.had == null && !Slayer.hasTask(b) && Seed.TASKED.isEmpty());

        boolean held = Slayer.hold(br, b, Seed.taskFor(Tasks.class, Seed.task("goblin:8")[0]), 8, System.currentTimeMillis());
        SlayerManager m = SlayerManager.getInstance(b);
        check("C0 Slayer.hold (Seed.task's call): held " + held + ", server task " + (m.hasTask() ? m.getTask() + " x" + m.getAmount() : "none") + ", brain task " + s.had + " names " + s.names + ", match on the goblin job",
                held && m.hasTask() && m.getTask() == Tasks.GOBLINS && m.getAmount() == 8 && s.had == Tasks.GOBLINS && s.names.contains("goblin") && s.lastAmount == 8 && Slayer.hasTask(b) && Slayer.match(br, gob));

        coins(b, 1000);
        int c1 = trips(br, gob, 5, true);
        check("C1 control, 1,000 gp (broke " + br.broke() + ") at the goblins: chooseJob -> taskTrip takes the goblin job " + c1 + "/5 (expect 5)", !br.broke() && c1 == 5);

        coins(b, 0);
        int before = Slayer.BROKE_TRIPS, c2 = trips(br, gob, 5, true), counted = Slayer.BROKE_TRIPS - before;
        check("C2 0 gp (broke " + br.broke() + "), streak: chooseJob -> taskTrip takes the goblin job " + c2 + "/5 (expect " + (on ? "5" : "0, the old behaviour") + "), free trips counted " + counted,
                br.broke() && (on ? c2 == 5 && counted == 5 : c2 == 0 && counted == 0));

        coins(b, 399);
        int c2b = trips(br, gob, 5, true);
        coins(b, 400);
        int c2c = trips(br, gob, 5, true);
        check("C2b the line: 399 gp " + c2b + "/5 (expect " + (on ? 5 : 0) + "), 400 gp " + c2c + "/5 (expect 5)", c2b == (on ? 5 : 0) && c2c == 5);

        coins(b, 0);
        int c3 = trips(br, gob, 60, false);
        check("C3 0 gp, no streak (the 1-in-" + Slayer.TRIP_ODDS + " dice): " + c3 + "/60 (expect " + (on ? "some, not all" : "0") + ")", on ? c3 > 5 && c3 < 55 : c3 == 0);

        b.setLocation(FAR); b.getProperties().setTeleportLocation(null);
        rearm(br, true);
        boolean far = Slayer.tripFar(br, gob), took = Slayer.taskTrip(br, gob, true);
        int c4 = trips(br, gob, 5, true);
        check("C4 0 gp, " + (int) FAR.getDistance(GOBLINS) + " tiles from the spot (far " + far + "): taskTrip took " + took + " (expect false), chooseJob " + c4 + "/5 (expect 0)", far && !took && c4 == 0);
        b.setLocation(GOBLINS);

        if (Slayer.FIX8) {
            rearm(br, true); coins(b, 0); boolean first0 = Slayer.tripFirst(br);
            coins(b, 1000); boolean first1 = Slayer.tripFirst(br);
            check("C5 tripFirst (fix8): broke holder " + first0 + " (expect " + on + "), with 1,000 gp " + first1 + " (expect true)", first0 == on && first1);
        } else System.out.println("[TestW2SlayerBroke] NOT RUN: C5 (needs -Dbots.slayer.fix6=true -Dbots.slayer.fix8=true)");
    }
}
