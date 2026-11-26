package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** P56 A1 (-Dtest.afksmelt=true): "::afk smelt_best bank" for a (non-bot) player from five banks; each must reach a furnace and make bars. */
public final class TestAfkSmelt implements StartupListener {
    static void log(String s) { System.out.println("[TestAfkSmelt] " + s); }

    static class Human extends AIPlayer {
        final String tag;
        Human(String tag, Location l) { super(l); this.tag = tag; }
        @Override public void sendMessage(String m) { System.out.println("[TestAfkSmelt]   " + tag + " <- " + m.replaceAll("<col=[0-9a-f]+>", "")); }
    }

    static final boolean SET2 = Integer.getInteger("test.afksmelt.set", 1) == 2; // set 2: the other banks, and Lumbridge upstairs with the ores already in the pack
    static final String[] TAGS = SET2 ? new String[]{"varrock_e", "draynor", "falador_e", "lumbridge_pack"} : new String[]{"varrock_w", "falador", "edgeville", "lumbridge", "alkharid"};
    static final Location[] AT = SET2 ? new Location[]{Location.create(3253, 3420, 0), Location.create(3092, 3243, 0), Location.create(3013, 3355, 0), Location.create(3208, 3220, 2)}
            : new Location[]{Location.create(3185, 3438, 0), Location.create(2946, 3368, 0), Location.create(3094, 3491, 0), Location.create(3208, 3220, 2), Location.create(3269, 3167, 0)};
    static final int TICKS = Integer.getInteger("test.afksmelt.ticks", 200);
    static Human[] hs = new Human[TAGS.length];
    static String[] lastLine = new String[TAGS.length];
    static boolean[] reachedSmelting = new boolean[TAGS.length];

    static Human make(int i) throws Exception {
        Human h = new Human(TAGS[i], AT[i]);
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(h, false);
        AfkTasks.OWNERS.add(h.getUsername().toLowerCase());
        h.getInventory().clear(); h.getBank().clear(); h.getEquipment().clear();
        for (int sk : new int[]{Skills.SMITHING, Skills.MINING}) { h.getSkills().setStaticLevel(sk, 30); h.getSkills().setLevel(sk, 30); }
        h.getBank().add(new Item(440, 27)); h.getBank().add(new Item(453, 54)); h.getBank().add(new Item(436, 28)); h.getBank().add(new Item(438, 28));
        if (TAGS[i].endsWith("_pack")) { for (int k = 0; k < 9; k++) h.getInventory().add(new Item(440)); for (int k = 0; k < 18; k++) h.getInventory().add(new Item(453)); h.getBank().remove(new Item(440, 9)); h.getBank().remove(new Item(453, 18)); }
        return h;
    }

    static int bars(Player p) { int n = 0; for (int id : new int[]{2349, 2351, 2353}) n += p.getInventory().getAmount(id) + p.getBank().getAmount(id); return n; }

    // P56 A2/A3/A4: parse checks on a separate player ("parse"); each prints CHECK <name> PASS|FAIL
    static Human pp;
    static String lastMsg = "";
    static final class Parser extends AIPlayer {
        Parser(Location l) { super(l); }
        @Override public void sendMessage(String m) { lastMsg = m.replaceAll("<col=[0-9a-f]+>", ""); if (lastMsg.contains("has come back here")) relocMsg = true; System.out.println("[TestAfkSmelt]   parse <- " + lastMsg); }
    }
    static Parser parser;
    static volatile boolean relocMsg;
    static int checksFailed;
    static AfkTasks.Runner pcmd(String line) {
        lastMsg = "";
        AfkTasks.stop(parser, "reset");
        log("parse ::afk " + line);
        AfkTasks.handle(parser, ("afk " + line).split(" "));
        return AfkTasks.RUNNING.get(parser.getUsername().toLowerCase());
    }
    static void check(String name, boolean ok) { if (!ok) checksFailed++; log("CHECK " + name + " " + (ok ? "PASS" : "FAIL") + " (last message: " + lastMsg + ")"); }
    static void parseChecks() throws Exception {
        parser = new Parser(Location.create(3222, 3218, 0));
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(parser, false);
        AfkTasks.OWNERS.add(parser.getUsername().toLowerCase());
        parser.getInventory().clear(); parser.getBank().clear();
        for (int sk : new int[]{Skills.MINING, Skills.SMITHING, Skills.FISHING, Skills.COOKING, Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { parser.getSkills().setStaticLevel(sk, 45); parser.getSkills().setLevel(sk, 45); }
        parser.getInventory().add(new Item(1265)); parser.getInventory().add(new Item(303)); parser.getInventory().add(new Item(995, 500));
        AfkTasks.Runner r;
        r = pcmd("gold bank"); check("gold_bank_mines_gold", r != null && r.task == AfkTasks.Task.GOLD);
        r = pcmd("gold then smelt bank"); check("gold_then_smelt_mines_gold", r != null && r.task == AfkTasks.Task.GOLD);
        r = pcmd("gold ore keep"); check("gold_ore_keep_mines_gold", r != null && r.task == AfkTasks.Task.GOLD);
        r = pcmd("gold bars"); check("gold_bars_smelts", r != null && r.task == AfkTasks.Task.SMELT_GOLD);
        r = pcmd("gold str"); check("gold_str_is_money_plan", r == null || r.task != AfkTasks.Task.GOLD);
        r = pcmd("gold"); check("gold_alone_is_money_plan", r == null || r.task != AfkTasks.Task.GOLD);
        r = pcmd("net then cook drop"); check("cook_drop_rejected", r == null && lastMsg.contains("throw the food away"));
        r = pcmd("rock crabs bury"); check("crab_bury_rejected", r == null && lastMsg.contains("no bones"));
        r = pcmd("c_beads bury"); check("imp_bury_rejected", r == null && lastMsg.contains("no bones"));
        r = pcmd("clay then smelt bank"); check("clay_smelt_rejected", r == null && lastMsg.contains("mining ore"));
        r = pcmd("cows bury"); check("cow_bury_accepted", r != null && r.bury);
        r = pcmd("net then cook bank"); check("cook_bank_accepted", r != null);
        check("cook_xp_by_fish", AfkTasks.Runner.cookXp(377) == 120 && AfkTasks.Runner.cookXp(317) == 30 && AfkTasks.Runner.cookXp(383) == 210);
        // P70 flaw 2: the gold plan is melee only; ranged/mage words are refused (the window no longer sends them)
        r = pcmd("gold ranged"); check("gold_ranged_rejected", r == null && lastMsg.contains("melee only"));
        r = pcmd("gold mage"); check("gold_mage_rejected", r == null && lastMsg.contains("melee only"));
        // P70 flaw 17: the furnace cache keys on the Al Kharid gate side (x<3268 skips the Al Kharid furnace)
        AfkTasks.stop(parser, "reset");
        Location home = parser.getLocation();
        AfkTasks.Runner fr = new AfkTasks.Runner(parser, AfkTasks.Task.SMELT_IRON, true, 0, false, false);
        parser.setLocation(Location.create(3270, 3186, 0)); Location east = fr.nearestFurnace();
        parser.setLocation(Location.create(3266, 3186, 0)); Location west = fr.nearestFurnace();
        parser.setLocation(home);
        log("furnace east=" + east + " west=" + west);
        check("furnace_cache_gate_side", east != null && east.getX() == 3274 && (west == null || west.getX() != 3274));
        // P70 flaw 18: the respawn give-up scales with the target's respawn (floor afk.respawnGiveUp, 150 in this test)
        int base = AfkTasks.Runner.RESPAWN_GIVEUP_TICKS;
        int gCopper = new AfkTasks.Runner(parser, AfkTasks.Task.COPPER, true, 0, false, false).respawnGiveUp();
        int gRunite = new AfkTasks.Runner(parser, AfkTasks.Task.RUNITE, true, 0, false, false).respawnGiveUp();
        int gMagic = new AfkTasks.Runner(parser, AfkTasks.Task.MAGIC, true, 0, false, false).respawnGiveUp();
        log("respawn give-up base=" + base + " copper=" + gCopper + " runite=" + gRunite + " magic=" + gMagic);
        check("respawn_giveup_scaled", gCopper == Math.max(base, 110) && gRunite == Math.max(base, 2510) && gMagic == Math.max(base, 1310) && gRunite > gMagic && gMagic > gCopper);
        r = pcmd("c_eggs"); // the relocate give-up (A3): driven directly, -Dafk.respawnGiveUp=150
        check("reloc_runner_started", r != null);
        // P70: the started eggs runner walked to the Lumbridge chicken farm (its own relocate) and an egg pickup is a gain,
        // which rightly resets the give-up window, so the check raced the walk (failed twice on train-20). The give-up rule
        // itself is checked on an unpulsed runner of the same task: no walking, no gains, only respawnWait() every 50 ticks.
        AfkTasks.stop(parser, "reset");
        relocRunner = new AfkTasks.Runner(parser, AfkTasks.Task.EGGS, true, 0, false, false);
        AfkTasks.RUNNING.put(parser.getUsername().toLowerCase(), relocRunner);
    }
    static AfkTasks.Runner relocRunner;
    // A1 give-up: a 6th player from Varrock west; once on the furnace leg its no-progress clock is wound back, so the next tick must stop it with a message
    static Human stall; static boolean stallWound, stallMsgSeen; static AfkTasks.Runner stallRunner;
    static final class StallHuman extends Human {
        StallHuman(Location l) { super("stall", l); }
        @Override public void sendMessage(String m) { super.sendMessage(m); if (m.contains("Couldn't reach the furnace")) stallMsgSeen = true; }
    }

    static void cmd(int i, String line) {
        log(TAGS[i] + " ::afk " + line);
        AfkTasks.handle(hs[i], ("afk " + line).split(" "));
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.afksmelt")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 60) {
                        for (int i = 0; i < TAGS.length; i++) { hs[i] = make(i); cmd(i, "smelt_best bank"); }
                        if (Boolean.getBoolean("test.afksmelt.parse")) {
                            parseChecks();
                            stall = new StallHuman(AT[0]);
                            java.lang.reflect.Field fa = Player.class.getDeclaredField("artificial"); fa.setAccessible(true); fa.setBoolean(stall, false);
                            AfkTasks.OWNERS.add(stall.getUsername().toLowerCase());
                            stall.getInventory().clear(); stall.getBank().clear();
                            for (int sk : new int[]{Skills.SMITHING, Skills.MINING}) { stall.getSkills().setStaticLevel(sk, 30); stall.getSkills().setLevel(sk, 30); }
                            stall.getBank().add(new Item(440, 27)); stall.getBank().add(new Item(453, 54));
                            log("stall ::afk smelt_best bank"); AfkTasks.handle(stall, "afk smelt_best bank".split(" "));
                            stallRunner = AfkTasks.RUNNING.get(stall.getUsername().toLowerCase());
                        }
                        AfkTasks.Runner.COST_NS = 0; AfkTasks.Runner.COST_N = 0;
                    }
                    if (stallRunner != null && !stallWound && stallRunner.state == AfkTasks.State.TO_FURNACE) { stallWound = true; stallRunner.furnaceSince = GameWorld.getTicks() - 100000; stallRunner.furnaceBars = 0; log("stall: furnace clock wound back at t" + (t - 60)); }
                    if (stallRunner != null && stallWound && AfkTasks.RUNNING.get(stall.getUsername().toLowerCase()) != stallRunner) { check("furnace_no_progress_gives_up", stallMsgSeen); stallRunner = null; }
                    if (relocRunner != null && t > 60 && (t - 60) % 50 == 0) { // A3: what relocate() does every ~100 ticks while nothing respawns
                        if (AfkTasks.RUNNING.get(parser.getUsername().toLowerCase()) == relocRunner) { log("reloc t" + (t - 60) + " respawnWait"); relocRunner.respawnWait(); }
                        if (AfkTasks.RUNNING.get(parser.getUsername().toLowerCase()) != relocRunner) { check("reloc_gives_up", relocMsg); relocRunner = null; }
                    }
                    if (t > 60 && t <= 60 + TICKS) {
                        for (int i = 0; i < TAGS.length; i++) {
                            Human h = hs[i];
                            AfkTasks.Runner r = AfkTasks.RUNNING.get(h.getUsername().toLowerCase());
                            String line;
                            if (r == null) line = "runner none";
                            else {
                                if (r.state == AfkTasks.State.SMELTING) reachedSmelting[i] = true;
                                Location nf = r.nearestFurnace();
                                line = "state=" + r.state + " last=" + r.last + " dest=" + r.dest + " furnace=" + nf + " bars=" + bars(h) + " busy=" + r.busy();
                            }
                            if (!line.equals(lastLine[i]) || (t - 60) % 25 == 0) log(TAGS[i] + " t" + (t - 60) + " at " + h.getLocation() + " " + line);
                            lastLine[i] = line;
                        }
                    }
                    if (t == 61 + TICKS) {
                        int pass = 0;
                        for (int i = 0; i < TAGS.length; i++) {
                            boolean ok = bars(hs[i]) > 0 && reachedSmelting[i];
                            if (ok) pass++;
                            log("RESULT " + TAGS[i] + " " + (ok ? "PASS" : "FAIL") + " bars=" + bars(hs[i]) + " smelting=" + reachedSmelting[i] + " at " + hs[i].getLocation());
                        }
                        long n = Math.max(1, AfkTasks.Runner.COST_N);
                        log("COST pulses=" + AfkTasks.Runner.COST_N + " avg_us=" + (AfkTasks.Runner.COST_NS / n / 1000) + " total_ms=" + (AfkTasks.Runner.COST_NS / 1_000_000));
                        if (relocRunner != null) check("reloc_gives_up", false);
                        if (stallRunner != null) check("furnace_no_progress_gives_up", false);
                        if (parser != null) log("CHECKS failed=" + checksFailed);
                        log("SUMMARY " + pass + "/" + TAGS.length + " PASS");
                        for (int i = 0; i < TAGS.length; i++) cmd(i, "stop");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
