package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * P87 unit: food gate and flee-loop breaker. -Dp87.expect=on (run with -Dbots.fight.foodGate=true -Dbots.fight.fleeBreak=true) or off (neither).
 * A foodless, penniless bot is refused a fire-giant hunt with ON and allowed with OFF; with food in the bank it is allowed either way;
 * two flees at one hunt inside 30 min trigger the rest (blacklist, no hunts for 60 min, streak cleared) with ON only. Enable with -Dtest.p87=true.
 */
public final class TestP87Fight implements StartupListener {
    static void log(String s) { System.out.println("[TestP87Fight] " + s); }

    @Override public void startup() {
        if (!"true".equalsIgnoreCase(System.getProperty("test.p87", ""))) return;
        GameWorld.getPulser().submit(new Run());
    }

    static final class Run extends Pulse {
        Run() { super(1); }
        final boolean wantOn = "on".equals(System.getProperty("p87.expect", "off"));
        int ticks, phase, passes, fails, wait; AIPlayer b;

        void mark(String id, boolean ok, String why) { log((ok ? "PASS " : "FAIL ") + id + " | " + why); if (ok) passes++; else fails++; }

        void clear(int id) {
            try { int n = b.getInventory().getAmount(id); if (n > 0) b.getInventory().remove(new Item(id, n)); } catch (Throwable ignored) { }
            try { int n = b.getBank().getAmount(id); if (n > 0) b.getBank().remove(new Item(id, n)); } catch (Throwable ignored) { }
        }

        @Override public boolean pulse() {
            ticks++;
            try {
                switch (phase) {
                    case 0:
                        if (!NavPlanner.built) { if (ticks > 1500) { mark("planner", false, "NavPlanner not built"); return finish(); } return false; }
                        b = TestCR1.bot(Location.create(3222, 3218, 0), 30, 20, 20, 20); phase = 1; wait = 0; return false;
                    case 1: {
                        if (b.getLocation().getDistance(Location.create(3222, 3218, 0)) > 3) { if (++wait > 20) { mark("placed", false, "at " + b.getLocation()); return finish(); } return false; }
                        mark("flags", P87Fight.FOOD == wantOn && P87Fight.BREAK == wantOn, "FOOD=" + P87Fight.FOOD + " BREAK=" + P87Fight.BREAK + " expect " + wantOn);
                        // pure functions
                        mark("foodNeed", P87Fight.foodNeed(13, 40) == 0 && P87Fight.foodNeed(2, 3) == 0 && P87Fight.foodNeed(21, 40) == 0 && P87Fight.foodNeed(36, 40) == 6 && P87Fight.foodNeed(86, 60) == 10 && P87Fight.foodNeed(170, 90) == 20,
                            P87Fight.foodNeed(13, 40) + "," + P87Fight.foodNeed(86, 60) + "," + P87Fight.foodNeed(170, 90));
                        mark("gate", !P87Fight.gate(3, 10, 0, 0) && P87Fight.gate(0, 10, 12, 0) && P87Fight.gate(0, 10, 0, 4000), "closed/open/open");
                        mark("fleeBreak", P87Fight.fleeBreak(new long[] { 0, 10 * 60_000L }, 10 * 60_000L) && !P87Fight.fleeBreak(new long[] { 0, 40 * 60_000L }, 40 * 60_000L)
                            && !P87Fight.fleeBreak(new long[] { 0, 0 }, 40 * 60_000L) && !P87Fight.fleeBreak(new long[] { 0, 5 * 60_000L }, 40 * 60_000L), "10min true, 40min false");
                        // live bot
                        SmartAdventurers.Brain br = new SmartAdventurers.Brain();
                        br.bot = b; br.scriptAPI = new core.game.bots.ScriptAPI(b); br.nextExtra = Long.MAX_VALUE; br.nextChat = Long.MAX_VALUE;
                        SmartAdventurers.BRAINS.put(b.getUsername().toLowerCase(java.util.Locale.ROOT), br);
                        HuntMap.Hunt h = new HuntMap.Hunt(); h.name = "Fire Giant"; h.npcLevel = 86; h.names = new String[] { "Fire giant" }; h.spot = Location.create(2575, 9885, 0);
                        SmartAdventurers.Job j = new SmartAdventurers.Job(AfkTasks.Task.AUTO, -1, 3); j.hunt = h;
                        for (int f : AfkTasks.Runner.FOOD) clear(f);
                        clear(995);
                        int combat = b.getProperties().getCurrentCombatLevel();
                        boolean closed = P87Fight.closedByFood(br, h, combat);
                        mark("foodless refused", closed == wantOn, "closedByFood=" + closed + " combat " + combat + " held " + br.foodFor(h));
                        b.getBank().add(new Item(385, 12));
                        mark("fed allowed", !P87Fight.closedByFood(br, h, combat), "held " + br.foodFor(h));
                        // P87b: trout counts for a level-30 hunt at combat 30
                        clear(385);
                        b.getInventory().add(new Item(333, 8));
                        HuntMap.Hunt h30 = new HuntMap.Hunt(); h30.name = "Level30"; h30.npcLevel = 30; h30.names = new String[] { "x" }; h30.spot = h.spot;
                        mark("trout opens lvl30", !P87Fight.closedByFood(br, h30, 30), "need " + P87Fight.foodNeed(30, 30) + " held " + P87Fight.foodForFight(br, h30));
                        clear(333);
                        // flee breaker
                        br.lastJob = j; long t0 = System.currentTimeMillis() - 20 * 60_000L;
                        br.slaySt.streak = true;
                        boolean f1 = P87Fight.onFlee(b, "low on health with no food left", t0);
                        boolean f2 = P87Fight.onFlee(b, "low on health with no food left", t0 + 10 * 60_000L);
                        mark("one flee no rest", !f1, "first flee -> " + f1);
                        mark("two flees rest", f2 == wantOn, "second flee -> " + f2);
                        mark("rest state", P87Fight.resting(br) == wantOn && (br.blacklist.getOrDefault(j.label(), 0L) > System.currentTimeMillis()) == wantOn && (br.slaySt.streak != wantOn),
                            "resting " + P87Fight.resting(br) + " blacklist " + br.blacklist.get(j.label()) + " streak " + br.slaySt.streak);
                        SmartAdventurers.BRAINS.remove(b.getUsername().toLowerCase(java.util.Locale.ROOT));
                        return finish();
                    }
                    default: return finish();
                }
            } catch (Throwable t) { mark("exception", false, String.valueOf(t)); t.printStackTrace(); return finish(); }
        }

        boolean finish() {
            log("RESULT " + (fails == 0 ? "PASS" : "FAIL") + " " + passes + "/" + (passes + fails) + " expect=" + (wantOn ? "on" : "off"));
            try { if (b != null) b.clear(); } catch (Throwable ignored) { }
            log("DONE");
            return true;
        }
    }
}
