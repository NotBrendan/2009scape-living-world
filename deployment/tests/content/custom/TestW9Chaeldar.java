package content.custom;

import content.global.skill.slayer.Master;
import content.global.skill.slayer.Tasks;
import core.game.world.map.Location;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * W9 lane D: Chaeldar (-Dbots.slayer.chaeldar), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW9Chaeldar <addons-src>/content/custom
 * Run it twice: bare (every flag off: the flag-off no-op checks) and with the switch on
 *   -Dbots.slayer=true -Dbots.slayer.openCheck=true -Dbots.slayer.park=true -Dbots.fight.foodGate=true -Dbots.slayer.vannaka=true -Dbots.slayer.chaeldar=true
 * Pure rules are checked directly; the hooks are read from the source (each one tests Slayer.CHAELDAR first).
 */
public final class TestW9Chaeldar {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW9Chaeldar] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW9Chaeldar]   missing: " + p); return false; } return true; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String sl = read(dir, "Slayer.java"), sa = read(dir, "SmartAdventurers.java"), mb = read(dir, "MarketBrain.java"), w9 = read(dir, "W9Chaeldar.java");
        boolean on = Slayer.CHAELDAR;
        Master T = Master.TURAEL, V = Master.VANNAKA, C = Master.CHAELDAR;

        // ---------------------------------------------------------------- 0 the switch
        check("C0 switch: Slayer.CHAELDAR = -Dbots.slayer.chaeldar && Slayer.VANNAKA (" + on + ")", on == (Boolean.getBoolean("bots.slayer.chaeldar") && Slayer.VANNAKA));
        check("C0b W9Chaeldar.ON follows Slayer.CHAELDAR", W9Chaeldar.ON == on);
        check("C0c the flag alone (no ladder) is off", Boolean.getBoolean("bots.slayer.chaeldar") && !Slayer.VANNAKA ? !on : true);

        // ---------------------------------------------------------------- 1 who qualifies (pure)
        check("C1 can: combat 70, eff 70, core, Lost City, food, 2 open -> yes", W9Chaeldar.can(70, 70, true, true, true, 2));
        check("C2 can: combat 69 or eff 69 -> no", !W9Chaeldar.can(69, 69, true, true, true, 9) && !W9Chaeldar.can(80, 69, true, true, true, 9));
        check("C3 can: no top (combat 126, the high tier Vannaka never takes) -> yes", W9Chaeldar.can(126, 126, true, true, true, 5) && W9Chaeldar.can(138, 120, true, true, true, 2));
        check("C4 can: each of core / Lost City / food missing -> no",
                !W9Chaeldar.can(100, 100, false, true, true, 5) && !W9Chaeldar.can(100, 100, true, false, true, 5) && !W9Chaeldar.can(100, 100, true, true, false, 5));
        check("C5 can: 1 open task (under OPEN_MIN " + W9Chaeldar.OPEN_MIN + ") -> no; -1 (not measured) -> no", !W9Chaeldar.can(100, 100, true, true, true, 1) && !W9Chaeldar.can(100, 100, true, true, true, -1));
        check("C6 Lost City: seeded history or the quest log", W9Chaeldar.lostCityOk(true, false) && W9Chaeldar.lostCityOk(false, true) && !W9Chaeldar.lostCityOk(false, false));
        check("C7 2009 rules: the engine's Chaeldar needs combat 70 and no Slayer level (MIN_COMBAT " + W9Chaeldar.MIN_COMBAT + ")", W9Chaeldar.MIN_COMBAT == 70);

        // ---------------------------------------------------------------- 2 the pick and the errand gate (pure)
        boolean offSame = true;
        for (Master base : new Master[] {T, V, null}) for (boolean c : new boolean[] {true, false}) offSame &= W9Chaeldar.pick(false, base, c) == base;
        check("C8 pick off: the ladder's answer untouched (Turael, Vannaka, none; can or not)", offSame);
        check("C9 pick on: can -> Chaeldar over Turael / Vannaka / none; cannot -> the ladder's answer",
                W9Chaeldar.pick(true, T, true) == C && W9Chaeldar.pick(true, V, true) == C && W9Chaeldar.pick(true, null, true) == C
                && W9Chaeldar.pick(true, V, false) == V && W9Chaeldar.pick(true, T, false) == T && W9Chaeldar.pick(true, null, false) == null);
        check("C10 eligible: off -> no; no job -> no; 69 -> no; 70 and 110 -> yes (Vannaka's gate says no at 110)",
                !W9Chaeldar.eligible(false, true, 100) && !W9Chaeldar.eligible(true, false, 100) && !W9Chaeldar.eligible(true, true, 69)
                && W9Chaeldar.eligible(true, true, 70) && W9Chaeldar.eligible(true, true, 110) && !Slayer.eligibleLadder(true, true, 110));
        check("C11 food: npc level " + W9Chaeldar.NPC_LEVEL + " wants 11 food at combat 105, none at combat 138 (P87Fight rule)",
                P87Fight.foodNeed(W9Chaeldar.NPC_LEVEL, 105) == 11 && P87Fight.foodNeed(W9Chaeldar.NPC_LEVEL, 138) == 0);

        // ---------------------------------------------------------------- 3 her task list
        Set<Tasks> al = W9Chaeldar.allow();
        boolean lvl = true, quest = true, deny = true;
        for (Tasks t : al) { lvl &= t.levelReq <= 1; quest &= t.questReq == null; deny &= !Slayer.VANNAKA_DENY.contains(t.name()); }
        check("C12 list: " + al.size() + " tasks, every one Slayer level 1, no quest requirement, none denied", al.size() >= 8 && lvl && quest && deny);
        check("C13 list: fire giants, greater + lesser demons, hellhounds, trolls, kalphites in",
                al.contains(Tasks.FIRE_GIANTS) && al.contains(Tasks.GREATER_DEMONS) && al.contains(Tasks.LESSER_DEMONS) && al.contains(Tasks.HELLHOUNDS) && al.contains(Tasks.TROLLS) && al.contains(Tasks.KALPHITES));
        check("C14 list: abyssal demons (85), banshee (deny), dagannoths / elves / jungle horrors (quests) out",
                !al.contains(Tasks.ABYSSAL_DEMONS) && !al.contains(Tasks.BANSHEE) && !al.contains(Tasks.DAGANNOTHS) && !al.contains(Tasks.ELVES) && !al.contains(Tasks.JUNGLE_HORRORS));
        boolean onHer = true; for (Tasks t : al) { boolean f = false; for (Master.Task mt : C.tasks) if (mt != null && mt.task == t) f = true; onHer &= f; }
        check("C15 list: every task is on the engine's own Chaeldar table", onHer);
        System.out.println("[TestW9Chaeldar]   list " + al);

        // ---------------------------------------------------------------- 4 Zanaris and the shed (pure)
        check("C16 her tile = npc_spawns 1598 (2447,4429); shed = ZanarisPlugin's exit (3201,3169)",
                W9Chaeldar.AT.getX() == 2447 && W9Chaeldar.AT.getY() == 4429 && W9Chaeldar.SHED_HINT.getX() == 3201 && W9Chaeldar.SHED_HINT.getY() == 3169);
        check("C17 Zanaris box: her tile and the shed landing (2452,4473) in; the shed, Edgeville and the dungeons out",
                W9Chaeldar.inZanaris(W9Chaeldar.AT) && W9Chaeldar.inZanaris(Location.create(2452, 4473, 0)) && !W9Chaeldar.inZanaris(W9Chaeldar.SHED_HINT)
                && !W9Chaeldar.inZanaris(Location.create(3094, 3470, 0)) && !W9Chaeldar.inZanaris(Location.create(3147, 9914, 0)) && !W9Chaeldar.inZanaris(Location.create(2447, 4429, 1)));
        Location fally = Location.create(2965, 3380, 0);
        check("C18 dist: 0 inside Zanaris, else to the shed (Lumbridge walks, Falador " + (int) W9Chaeldar.dist(fally, W9Chaeldar.SHED_HINT) + " tiles)",
                W9Chaeldar.dist(Location.create(2400, 4440, 0), W9Chaeldar.SHED_HINT) == 0 && W9Chaeldar.dist(fally, W9Chaeldar.SHED_HINT) == fally.getDistance(W9Chaeldar.SHED_HINT)
                && W9Chaeldar.dist(Location.create(3222, 3218, 0), W9Chaeldar.SHED_HINT) < AfkTasks.Runner.TELEPORT_MIN && W9Chaeldar.dist(null, W9Chaeldar.SHED_HINT) == Double.MAX_VALUE);

        // ---------------------------------------------------------------- 5 Slayer's master helpers: off = old answers, on = hers
        if (!on) {
            check("C19 off: masterAt/masterPlace(CHAELDAR) give Turael's (no new branch taken)", Slayer.masterAt(C) == Slayer.TURAEL && "Burthorpe".equals(Slayer.masterPlace(C)));
            check("C20 off: allowFor(CHAELDAR) = Turael's ALLOW; stand(CHAELDAR) = Turael", Slayer.allowFor(C) == Slayer.ALLOW && Slayer.stand(C) == Slayer.TURAEL);
            check("C21 off: masterDist(CHAELDAR) = the old getDistance to masterAt", Slayer.masterDist(fally, C, null) == fally.getDistance(Slayer.TURAEL));
        } else {
            check("C19 on: masterAt/masterPlace(CHAELDAR) = her tile, Zanaris", Slayer.masterAt(C) == W9Chaeldar.AT && "Zanaris".equals(Slayer.masterPlace(C)));
            check("C20 on: allowFor(CHAELDAR) = her list; Vannaka and Turael unchanged", Slayer.allowFor(C) == W9Chaeldar.allow() && Slayer.allowFor(T) == Slayer.ALLOW && Slayer.masterAt(V) == Slayer.VANNAKA_AT);
            check("C21 on: masterDist(CHAELDAR) inside Zanaris = 0", Slayer.masterDist(Location.create(2440, 4430, 0), C, null) == 0);
        }
        check("C22 masterName(CHAELDAR) = Chaeldar (the ladder and assigned lines)", "Chaeldar".equals(Slayer.masterName(C)));

        // ---------------------------------------------------------------- 6 lines (pure)
        Map<String, Long> next = new HashMap<>();
        boolean l1 = W9Chaeldar.logDue(next, "a", 1_000L), l2 = !W9Chaeldar.logDue(next, "a", 2_000L), l3 = W9Chaeldar.logDue(next, "b", 2_000L), l4 = W9Chaeldar.logDue(next, "a", 1_000L + W9Chaeldar.LOG_GAP_MS);
        check("C23 ladder line once per bot per " + W9Chaeldar.LOG_GAP_MS / 60_000 + " min", l1 && l2 && l3 && l4);
        String hl = W9Chaeldar.line(new int[] {3, 4, 5, 2, 1, 1, 1, 1});
        check("C24 hourly line: '[Slayer] chaeldar hourly on=... qualify=3/4 ...' (lanecall sub-lane 'chaeldar')", hl.startsWith("[Slayer] chaeldar hourly on=" + on + " qualify=3/4 ladder=5 picked=2 enters=1 leaves=1 assigned=1 holding=1"));
        check("C25 every per-bot line starts '[Slayer] chaeldar <bot>'", all(w9, "\"[Slayer] chaeldar \" + bot.getUsername() + \" ladder combat ", "\"[Slayer] chaeldar \" + bot.getUsername() + \" enters Zanaris",
                "\"[Slayer] chaeldar \" + bot.getUsername() + \" leaves Zanaris", "\"[Slayer] chaeldar \" + bot.getUsername() + \" assigned "));

        // ---------------------------------------------------------------- 7 wiring: each hook behind the switch, no RNG draw in the new class
        check("C26 wiring: Slayer hooks each test CHAELDAR first", all(sl,
                "static final boolean CHAELDAR = Boolean.getBoolean(\"bots.slayer.chaeldar\") && VANNAKA;",
                "if (CHAELDAR && m == Master.CHAELDAR) return W9Chaeldar.dist(here);",
                "if (CHAELDAR && m == Master.CHAELDAR) return W9Chaeldar.out(); if (m != Master.VANNAKA) return TURAEL;",
                "m == Master.VANNAKA ? VANNAKA_AT : CHAELDAR && m == Master.CHAELDAR ? W9Chaeldar.AT : TURAEL",
                "m == Master.VANNAKA ? vannakaAllow() : CHAELDAR && m == Master.CHAELDAR ? W9Chaeldar.allow() : ALLOW",
                "boolean v = master == Master.VANNAKA, c = CHAELDAR && master == Master.CHAELDAR;",
                "if (CHAELDAR && !combatJob) combatJob = W9Chaeldar.eligible(true, b.lastJob != null, bot.getProperties().getCurrentCombatLevel());",
                "if (CHAELDAR && to == Master.CHAELDAR && W9Chaeldar.go(bot, s)) return; go(bot, masterAt(to), s); return; }",
                "gm != Master.VANNAKA && !(CHAELDAR && gm == Master.CHAELDAR)",
                "if (CHAELDAR && s.master == Master.CHAELDAR) W9Chaeldar.assigned(bot, t.name(), m.getAmount());",
                "if (CHAELDAR) m = W9Chaeldar.choose(b, m, combat, eff, done);",
                "if (DOOR) surface(b.bot, \"errand end\"); if (CHAELDAR) try { W9Chaeldar.leave(b.bot, \"errand end\"); }")); // train-64 fix 1: the errand-end hook is wrapped
        int ladder = sl.indexOf("SmartAdventurers.RNG.nextBoolean(), FIX6, FIX7);"), hook = sl.indexOf("if (CHAELDAR) m = W9Chaeldar.choose(");
        check("C27 the Chaeldar pick runs after the ladder's coin (draw order kept)", ladder > 0 && hook > ladder && hook - ladder < 200);
        check("C28 wiring: brain start and the hourly timer behind the switch", all(sa, "if (Slayer.CHAELDAR) try { W9Chaeldar.leave(bot, \"brain start\"); }")
                && all(mb, "if (Boolean.getBoolean(\"bots.slayer.chaeldar\") && Slayer.CHAELDAR) TIMER.schedule("));
        String w9c = w9.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", ""); // code only (comments name these words)
        check("C29 W9Chaeldar draws no RNG and only Slayer.go can pay", !w9c.contains("RNG") && !w9c.contains("payTeleport") && !w9c.contains("Random") && w9c.contains("Slayer.go(bot, shed, s);"));
        check("C30 W9Chaeldar never touches Slayer points or the task directly", !w9c.contains("SlayerPoints") && !w9c.contains("generate(") && !w9c.contains(".clear()") && !w9c.contains("setTask"));

        System.out.println("[TestW9Chaeldar] DONE " + pass + " pass, " + fail + " fail (switch " + (on ? "on" : "off") + ")");
        System.exit(fail == 0 ? 0 : 1);
    }
}
