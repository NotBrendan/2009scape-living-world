package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * P61 ::afk batch B (-Dtest.afkb=true): non-bot players run one of each new task: the six stalls, rock crabs (loot list),
 * runite (refusal), ranged (runs out of arrows), mage/ranged gear refusals, a combat retreat, and the walk search cap.
 * Prints CHECK <name> PASS|FAIL, RESULT lines and SUMMARY.
 */
public final class TestAfkBatchB implements StartupListener {
    static void log(String s) { System.out.println("[TestAfkB] " + s); }

    static final class Human extends AIPlayer {
        final String tag; final StringBuilder seen = new StringBuilder();
        Human(String tag, Location l) { super(l); this.tag = tag; }
        @Override public void sendMessage(String m) { String s = m.replaceAll("<col=[0-9a-f]+>", ""); seen.append(s).append(" | "); System.out.println("[TestAfkB]   " + tag + " <- " + s); }
    }

    static final int TICKS = Integer.getInteger("test.afkb.ticks", 400);
    static final String[] STALLS = {"stall_baker", "stall_silk", "stall_fur", "stall_silver", "stall_gem", "stall_seed"};
    static final Location ARDY = Location.create(2662, 3305, 0), DRAY = Location.create(3080, 3250, 0);
    static Human[] st = new Human[STALLS.length];
    static double[] xp0 = new double[STALLS.length];
    static int[] maxFinds = new int[STALLS.length], maxGot = new int[STALLS.length];
    static Human crab, ranger, retreat, parse, misfire, stallHit; // P70: misfire = ranged with ammo its bow can't fire; stallHit = stall run under a continuous attack
    static boolean retreatSeen;
    static int failed;

    static Human make(String tag, Location l) throws Exception {
        Human h = new Human(tag, l);
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(h, false);
        AfkTasks.OWNERS.add(h.getUsername().toLowerCase());
        h.getInventory().clear(); h.getBank().clear(); h.getEquipment().clear();
        return h;
    }
    static void lvl(Player p, int sk, int v) { p.getSkills().setStaticLevel(sk, v); p.getSkills().setLevel(sk, v); }
    static AfkTasks.Runner rn(Player p) { return AfkTasks.RUNNING.get(p.getUsername().toLowerCase()); }
    static void cmd(Human h, String line) { log(h.tag + " ::afk " + line); h.seen.setLength(0); AfkTasks.handle(h, ("afk " + line).split(" ")); }
    static void check(String name, boolean ok, String why) { if (!ok) failed++; log("CHECK " + name + " " + (ok ? "PASS" : "FAIL") + " (" + why + ")"); }

    /** Every scenery with a steal option, and every NPC, near a tile (ids and names for the stall table / crab check). */
    static void dump(String tag, Location c, int r) {
        StringBuilder sb = new StringBuilder();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
            core.game.node.scenery.Scenery s = core.game.world.map.RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
            if (s == null || s.getInteraction() == null) continue;
            for (core.game.interaction.Option o : s.getInteraction().getOptions()) if (o != null && o.getName() != null && o.getName().toLowerCase().startsWith("steal")) { sb.append(s.getId()).append(' ').append(s.getName()).append('@').append(s.getLocation().getX()).append(',').append(s.getLocation().getY()).append("; "); break; }
        }
        log("DUMP " + tag + " stalls: " + sb);
        sb.setLength(0);
        for (core.game.node.entity.npc.NPC n : core.game.world.map.RegionManager.getLocalNpcs(c, r)) sb.append(n.getId()).append(' ').append(n.getName()).append("; ");
        log("DUMP " + tag + " npcs: " + sb);
    }

    static void setup() throws Exception {
        if (Boolean.getBoolean("test.afkb.dump")) { dump("ardougne", ARDY, 16); dump("draynor", DRAY, 8); dump("crabs", Location.create(2674, 3712, 0), 16); }
        for (int i = 0; i < STALLS.length; i++) {
            Location at = STALLS[i].equals("stall_seed") ? DRAY : ARDY;
            st[i] = make(STALLS[i], at);
            for (int sk : new int[]{Skills.THIEVING, Skills.HITPOINTS, Skills.DEFENCE}) lvl(st[i], sk, 80);
            st[i].getInventory().add(new Item(379, 5)); // lobsters: food for a caught run
            xp0[i] = st[i].getSkills().getExperience(Skills.THIEVING);
            cmd(st[i], STALLS[i] + " bank");
            check("stall_started_" + STALLS[i], rn(st[i]) != null && rn(st[i]).task.kind == AfkTasks.Kind.STALL, st[i].seen.toString());
        }
        // level gate: Thieving 1 at the gem stall
        parse = make("parse", Location.create(3222, 3218, 0));
        lvl(parse, Skills.THIEVING, 1); lvl(parse, Skills.MINING, 85);
        parse.getInventory().add(new Item(1275));
        cmd(parse, "stall_gem"); check("stall_gem_level_gate", rn(parse) == null && parse.seen.toString().contains("75"), parse.seen.toString());
        cmd(parse, "runite"); check("runite_no_spot_message", rn(parse) == null && parse.seen.toString().contains("no runite rock"), parse.seen.toString());
        cmd(parse, "chickens mage"); check("mage_needs_autocast", rn(parse) == null && parse.seen.toString().contains("autocast"), parse.seen.toString());
        cmd(parse, "chickens ranged"); check("ranged_needs_bow", rn(parse) == null && parse.seen.toString().contains("wield a bow"), parse.seen.toString());
        cmd(parse, "gold ranged"); check("gold_ranged_rejected", rn(parse) == null && parse.seen.toString().contains("melee only"), parse.seen.toString()); // P70 flaw 2
        cmd(parse, "magic"); check("magic_word_is_still_trees", rn(parse) == null || rn(parse).task == AfkTasks.Task.MAGIC, parse.seen.toString());
        AfkTasks.stop(parse, "reset");
        // rock crabs (Rellekka beach): the player's run counts the drop table
        crab = make("crab", Location.create(2674, 3712, 0));
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) lvl(crab, sk, 70);
        crab.getInventory().add(new Item(379, 5));
        cmd(crab, "rock crabs bank");
        AfkTasks.Runner cr = rn(crab);
        check("crab_loot_list", cr != null && AfkTasks.has(cr.products(), 995) && AfkTasks.has(cr.products(), 407) && AfkTasks.Task.ROCK_CRAB.products.length == 0, cr == null ? crab.seen.toString() : "products=" + cr.products().length + " task.products=" + AfkTasks.Task.ROCK_CRAB.products.length);
        // ranged: shortbow worn, 5 bronze arrows in the pack, Lumbridge chickens
        ranger = make("ranger", Location.create(3233, 3295, 0));
        for (int sk : new int[]{Skills.RANGE, Skills.HITPOINTS, Skills.DEFENCE}) lvl(ranger, sk, 40);
        ranger.getEquipment().add(new Item(841), -1, true, false); ranger.getEquipment().refresh();
        ranger.getInventory().add(new Item(882, 5));
        cmd(ranger, "chickens ranged noloot");
        AfkTasks.Runner rr = rn(ranger);
        check("ranged_started", rr != null && rr.rangedRun && rr.styleSkill == Skills.RANGE, ranger.seen.toString());
        // P70 flaw 3: a longbow (unknown to SmartAdventurers.BOWS, so ammoFor takes any arrow) with rune arrows the engine won't fire
        misfire = make("misfire", Location.create(3233, 3295, 0));
        for (int sk : new int[]{Skills.RANGE, Skills.HITPOINTS, Skills.DEFENCE}) lvl(misfire, sk, 40);
        misfire.getEquipment().add(new Item(839), -1, true, false); misfire.getEquipment().add(new Item(892, 100), -1, true, false); misfire.getEquipment().refresh();
        cmd(misfire, "chickens ranged noloot");
        check("misfire_started", rn(misfire) != null && rn(misfire).rangedRun, misfire.seen.toString());
        // P70 flaw 5: a baker-stall run that is attacked without a break (combat-time held in the future every tick from t+40)
        stallHit = make("stallhit", ARDY);
        for (int sk : new int[]{Skills.THIEVING, Skills.HITPOINTS, Skills.DEFENCE}) lvl(stallHit, sk, 80);
        cmd(stallHit, "stall_baker bank");
        check("stallhit_started", rn(stallHit) != null, stallHit.seen.toString());
        // combat retreat: 2 of 10 life points, no food, fighting men at Lumbridge
        retreat = make("retreat", Location.create(3222, 3218, 0));
        lvl(retreat, Skills.HITPOINTS, 10); retreat.getSkills().setLifepoints(2);
        lvl(retreat, Skills.ATTACK, 1); lvl(retreat, Skills.DEFENCE, 1);
        cmd(retreat, "men bank");
        check("retreat_started", rn(retreat) != null, retreat.seen.toString());
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.afkb")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 60) setup();
                    if (t > 60 && t <= 60 + TICKS) {
                        for (int i = 0; i < STALLS.length; i++) {
                            AfkTasks.Runner r = rn(st[i]);
                            if (r != null) { maxFinds[i] = Math.max(maxFinds[i], r.walkFinds); maxGot[i] = Math.max(maxGot[i], r.collected); }
                            if ((t - 60) % 50 == 0) log(STALLS[i] + " t" + (t - 60) + " at " + st[i].getLocation() + (r == null ? " runner none" : " state=" + r.state + " last=" + r.last + " collected=" + r.collected) + " xp+" + (int) (st[i].getSkills().getExperience(Skills.THIEVING) - xp0[i]));
                        }
                        if (t >= 100 && stallHit != null) stallHit.setAttribute("combat-time", System.currentTimeMillis() + 3000L); // P70 flaw 5
                        AfkTasks.Runner rt = rn(retreat);
                        if (rt != null && (rt.state == AfkTasks.State.RETREAT || rt.state == AfkTasks.State.RECOVER)) retreatSeen = true;
                        if (retreat.seen.toString().contains("running to safety")) retreatSeen = true;
                        if ((t - 60) % 50 == 0) {
                            AfkTasks.Runner c = rn(crab), g = rn(ranger);
                            log("crab t" + (t - 60) + (c == null ? " none" : " state=" + c.state + " last=" + c.last + " collected=" + c.collected) + " | ranger " + (g == null ? "none" : "state=" + g.state + " last=" + g.last) + " quiver=" + ranger.getEquipment().get(13) + " | retreat " + (rt == null ? "none" : rt.state + " " + rt.last));
                        }
                    }
                    if (t == 61 + TICKS) {
                        int pass = 0;
                        for (int i = 0; i < STALLS.length; i++) {
                            int gain = (int) (st[i].getSkills().getExperience(Skills.THIEVING) - xp0[i]);
                            boolean ok = maxGot[i] > 0;
                            if (ok) pass++;
                            log("RESULT " + STALLS[i] + " " + (ok ? "PASS" : "FAIL") + " collected=" + maxGot[i] + " xp+" + gain + " maxWalkFinds=" + maxFinds[i] + " at " + st[i].getLocation() + " msgs: " + st[i].seen);
                            check("walk_cap_" + STALLS[i], maxFinds[i] <= AfkTasks.Runner.WALK_FINDS, "max " + maxFinds[i]);
                        }
                        check("ranged_out_of_ammo_stops", rn(ranger) == null && ranger.seen.toString().contains("Out of ammo"), ranger.seen.toString());
                        check("combat_retreat", retreatSeen, retreat.seen.toString());
                        check("ranged_misfire_gives_up", rn(misfire) == null && misfire.seen.toString().contains("gained no Ranged xp"), "xp=" + (int) misfire.getSkills().getExperience(Skills.RANGE) + " " + misfire.seen); // P70 flaw 3
                        check("stall_continuous_attack_ends", rn(stallHit) == null && stallHit.seen.toString().contains("without a break"), stallHit.seen.toString()); // P70 flaw 5
                        log("crab collected=" + (rn(crab) == null ? "stopped" : rn(crab).collected) + " msgs: " + crab.seen);
                        log("CHECKS failed=" + failed);
                        log("SUMMARY stalls " + pass + "/" + STALLS.length + " PASS");
                        for (Human h : st) AfkTasks.stop(h, "test over");
                        for (Human h : new Human[]{crab, ranger, retreat, misfire, stallHit}) AfkTasks.stop(h, "test over");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
