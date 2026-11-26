package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.p90=true; needs TestCR1.java; run with -Dnav=true -Dwatchdog.min=5 like live): P90
 * (the design notes). Pure: P90.tools and P90.climbOut.
 * Leg E: essence job from Aubury's shop, no pickaxe, 500 gp: ON = a pickaxe is bought and essence mined, then the pick is taken
 *   away and the runner stops at once ("[P90] stop"); OFF = no pickaxe, nothing mined in 600 ticks at the mine.
 * Leg B: the same with 0 gp: ON = the job is dropped (no runner, "[P90] no-pick"); OFF = a runner starts anyway.
 * Leg V: Karamja volcano dungeon 2839,9557, broke, a surface copper job: ON = climbs out within 400 ticks ("[P90] climb");
 *   OFF = still underground after 400 ticks.
 * -Dp90.expect=on: SUMMARY PASS when every ON check holds; off: PASS when every leg shows the old behaviour.
 */
public final class TestP90 implements StartupListener {
    static void log(String s) { System.out.println("[TestP90] " + s); }
    static final int[] ESS = {1436, 7936};

    static boolean eq(Location a, int x, int y) { return a != null && a.getX() == x && a.getY() == y && a.getZ() == 0; }
    static Location L(int x, int y) { return Location.create(x, y, 0); }

    static boolean pure(boolean on) {
        boolean ok = true;
        ok &= on ? P90.tools(AfkTasks.Task.ESSENCE) == AfkTasks.PICKAXES : P90.tools(AfkTasks.Task.ESSENCE) == null;
        ok &= P90.tools(AfkTasks.Task.COPPER) == AfkTasks.PICKAXES;
        ok &= P90.tools(AfkTasks.Task.NET) == AfkTasks.Task.NET.tools;
        Location[] a = P90.climbOut(L(2839, 9549), L(2870, 3110)), b = P90.climbOut(L(2838, 9557), L(3164, 3487)),
                c = P90.climbOut(L(2884, 9798), L(2900, 3400)), d = P90.climbOut(L(3096, 9867), L(3100, 3480));
        if (on) {
            ok &= a != null && eq(a[0], 2857, 9569) && eq(a[1], 2856, 3168);
            ok &= b != null && eq(b[0], 2857, 9569) && eq(b[1], 2856, 3168);
            ok &= c != null && eq(c[0], 2884, 9798) && eq(c[1], 2884, 3396);
            ok &= d != null && eq(d[0], 3096, 9867) && eq(d[1], 3095, 3468);
        } else ok &= a == null && b == null && c == null && d == null;
        ok &= P90.climbOut(L(2856, 3168), L(2870, 3110)) == null; // surface start
        ok &= P90.climbOut(L(2839, 9549), L(2840, 9565)) == null; // goal underground
        ok &= P90.climbOut(L(2924, 4848), L(3253, 3401)) == null; // essence mine
        ok &= P90.climbOut(Location.create(2839, 9549, 1), L(2870, 3110)) == null; // z != 0
        ok &= P90.climbOut(L(2570, 9890), L(2870, 3110)) == null; // unlisted dungeon
        return ok;
    }

    static SmartAdventurers.Brain br(AIPlayer b) { return SmartAdventurers.BRAINS.get(b.getUsername().toLowerCase(java.util.Locale.ROOT)); }

    static void stripPicks(AIPlayer b) {
        for (int id : AfkTasks.PICKAXES) {
            b.getInventory().remove(new Item(id, b.getInventory().getAmount(id)));
            b.getBank().remove(new Item(id, b.getBank().getAmount(id)));
            try { Item w = b.getEquipment().get(3); if (w != null && w.getId() == id) b.getEquipment().replace(null, 3); } catch (Throwable ignored) { }
        }
    }

    static AIPlayer smart(Location from, String leg, int coins) {
        AIPlayer b = TestCR1.bot(from, 85, 85, 85, 85);
        SmartAdventurers.enlist(b, false, false, -1, 0, 0, "test bot (p90 leg " + leg + ")");
        br(b).nextThink = Long.MAX_VALUE;
        b.getInventory().remove(new Item(995, b.getInventory().getAmount(995))); b.getBank().remove(new Item(995, b.getBank().getAmount(995)));
        if (coins > 0) b.getInventory().add(new Item(995, coins));
        for (int f = 0; f < 6; f++) b.getInventory().add(new Item(379, 1));
        stripPicks(b);
        try { b.getProperties().setTeleportLocation(from); } catch (Throwable ignored) { }
        return b;
    }

    static boolean startJob(AIPlayer b, AfkTasks.Task task) {
        SmartAdventurers.Brain br = br(b);
        SmartAdventurers.Job j = new SmartAdventurers.Job(task, Skills.MINING, 1);
        br.skipShopOnce = true; br.armourChecked = true; br.exchangeChecked = true;
        br.start(j); br.nextThink = Long.MAX_VALUE;
        log(b.getUsername() + " at " + b.getLocation() + " started " + j.label() + ": runner " + (br.runner != null) + ", pickaxe " + AfkTasks.hasAny(b, AfkTasks.PICKAXES));
        return br.runner != null;
    }

    static int ess(AIPlayer b) { int n = 0; for (int id : ESS) n += b.getInventory().getAmount(id) + b.getBank().getAmount(id); return n; }
    static void park(AIPlayer b) { try { SmartAdventurers.Brain br = br(b); if (br.runner != null) br.runner.stop(); br.nextThink = Long.MAX_VALUE; b.getPulseManager().clear(); b.getProperties().setTeleportLocation(L(3222, 3218)); } catch (Throwable ignored) { } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p90")) return;
        final boolean expectOn = "on".equalsIgnoreCase(System.getProperty("p90.expect", "on"));
        log("start: nav.p90Fix=" + P90.ON + " expect=" + (expectOn ? "on" : "off"));
        final boolean pure = pure(P90.ON);
        log("pure checks " + (pure ? "OK" : "FAIL"));
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, mark, stop0, nopick0, climb0; AIPlayer eb, bb, vb;
            boolean eOn, eOff, bOn, bOff, vOn, vOff, eMined, eTook;
            @Override public boolean pulse() {
                try {
                    t++;
                    switch (phase) {
                        case 0:
                            if (t < 200) return false;
                            eb = smart(L(3253, 3401), "E", 500); mark = t; phase = 1; return false;
                        case 1:
                            if (t - mark < 6) return false;
                            if (!startJob(eb, AfkTasks.Task.ESSENCE)) { log("RESULT leg E: no runner (pickaxe " + AfkTasks.hasAny(eb, AfkTasks.PICKAXES) + ")"); phase = 3; return false; }
                            mark = t; phase = 2; return false;
                        case 2: {
                            SmartAdventurers.Brain br = br(eb); int age = t - mark; Location at = eb.getLocation();
                            if (age % 100 == 0) log("leg E tick " + age + ": at " + at + ", ess " + ess(eb) + ", pick " + AfkTasks.hasAny(eb, AfkTasks.PICKAXES) + (br.runner != null ? " | " + br.runner.last : " | no runner"));
                            if (!eMined && ess(eb) > 0) {
                                eMined = true; stop0 = P90.STOP.get(); stripPicks(eb); eTook = true; mark = t;
                                log("leg E: mined at tick " + age + " at " + at + "; pickaxe taken away");
                                return false;
                            }
                            if (eTook) {
                                boolean stopped = P90.STOP.get() > stop0;
                                if (stopped || age >= 60) {
                                    eOn = stopped;
                                    log("RESULT leg E (ON): mined, then " + (stopped ? "stopped at once without a pickaxe" : "kept going without a pickaxe") + " (runner " + (br.runner != null ? br.runner.last : "gone") + ")");
                                    park(eb); phase = 3;
                                }
                                return false;
                            }
                            if (age >= 600 || br.runner == null) {
                                eOff = ess(eb) == 0 && !AfkTasks.hasAny(eb, AfkTasks.PICKAXES) && br.runner != null && AfkTasks.Runner.essenceMine(at);
                                log("RESULT leg E: " + age + " ticks, at " + at + ", ess " + ess(eb) + ", pick " + AfkTasks.hasAny(eb, AfkTasks.PICKAXES) + ", runner " + (br.runner != null ? br.runner.last : "gone") + " -> " + (eOff ? "IDLE AT THE ROCK (old bug)" : "other"));
                                park(eb); phase = 3;
                            }
                            return false;
                        }
                        case 3:
                            bb = smart(L(3253, 3401), "B", 0); mark = t; phase = 4; return false;
                        case 4: {
                            if (t - mark < 6) return false;
                            nopick0 = P90.NOPICK.get();
                            boolean r = startJob(bb, AfkTasks.Task.ESSENCE);
                            bOn = !r && P90.NOPICK.get() == nopick0 + 1;
                            bOff = r && !AfkTasks.hasAny(bb, AfkTasks.PICKAXES);
                            log("RESULT leg B: runner " + r + ", no-pick +" + (P90.NOPICK.get() - nopick0) + " -> " + (bOn ? "DROPPED" : bOff ? "SENT WITHOUT A PICKAXE (old)" : "other"));
                            park(bb); mark = t; phase = 5; return false;
                        }
                        case 5:
                            if (t - mark < 10) return false;
                            vb = smart(L(2839, 9557), "V", 0); vb.getInventory().add(new Item(1265, 1)); mark = t; phase = 6; return false;
                        case 6:
                            if (t - mark < 6) return false;
                            climb0 = P90.CLIMB.get();
                            if (!startJob(vb, AfkTasks.Task.COPPER)) { log("RESULT leg V: no runner"); phase = 8; return false; }
                            mark = t; phase = 7; return false;
                        case 7: {
                            SmartAdventurers.Brain br = br(vb); int age = t - mark; Location at = vb.getLocation();
                            if (age % 50 == 0) log("leg V tick " + age + ": at " + at + (br.runner != null ? " | " + br.runner.last + " dest " + br.runner.dest : " | no runner"));
                            boolean out = at.getY() < 6400;
                            if (out || age >= 400 || br.runner == null) {
                                int climbs = P90.CLIMB.get() - climb0;
                                vOn = out && climbs >= 1;
                                vOff = !out && climbs == 0;
                                log("RESULT leg V: " + (out ? "OUT at " : "still underground at ") + at + " in " + age + " ticks, climb +" + climbs + " -> " + (vOn ? "CLIMBED OUT" : vOff ? "STUCK UNDERGROUND (old)" : "other"));
                                park(vb); phase = 8;
                            }
                            return false;
                        }
                        default: {
                            boolean pass = expectOn ? pure && eOn && bOn && vOn : pure && eOff && bOff && vOff;
                            log("SUMMARY " + (pass ? "PASS" : "FAIL") + " p90Fix=" + P90.ON + " expect=" + (expectOn ? "on" : "off") + ": pure " + pure + ", E " + (eOn ? "on" : eOff ? "off" : "other") + ", B " + (bOn ? "on" : bOff ? "off" : "other") + ", V " + (vOn ? "on" : vOff ? "off" : "other")
                                    + " (pick " + P90.PICK.get() + ", no-pick " + P90.NOPICK.get() + ", stop " + P90.STOP.get() + ", climb " + P90.CLIMB.get() + ")");
                            log("DONE"); return true;
                        }
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("SUMMARY FAIL error"); log("DONE"); return true; }
            }
        });
    }
}
