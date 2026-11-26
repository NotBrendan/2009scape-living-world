package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.path.Path;
import core.game.world.map.path.Pathfinder;

/**
 * P92 quest fixes unit (-Dquest.p92Fix). Two parts:
 *  - pure (no world): main() -> P92.handOut (stage/items rule for the Pikkupstix hand-out) and P92.altarSide.
 *    Offline: java -cp <build>;server.jar [-Dquest.p92Fix=true] content.custom.TestP92Quests
 *  - world (-Dtest.p92=true, a farm slot): the pure checks again, then the Wizards' Tower basement as the cache has it
 *    (the Large door 33060 shut at 3111,9559; no path from the stall tile 3110,9567 to the altar; a path from the door's
 *    east side), and the step lists: the (P92) steps are in Wolf Whistle and The Restless Ghost only with the flag ON.
 * Lines: "[TestP92Quests] RESULT PASS/FAIL pass=N fail=M" then "[TestP92Quests] DONE".
 */
public final class TestP92Quests implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestP92Quests] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }

    public static void main(String[] a) { pure(); log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail); log("DONE"); System.exit(fail == 0 ? 0 : 1); }

    static void pure() {
        check("flag P92.ON == quest.p92Fix (" + Boolean.getBoolean("quest.p92Fix") + ")", P92.ON == Boolean.getBoolean("quest.p92Fix"));
        // live 11-17 Stepbro246: stage 40, need 2, bones 2, charms 0, pouches 2, shards 14 -> two charms, nothing else
        int[] h = P92.handOut(40, false, 2, 0, 2, 14);
        check("stage 40, charms 0 -> 2 charms only", h != null && h[0] == 2 && h[1] == 0 && h[2] == 0);
        h = P92.handOut(50, false, 2, 0, 0, 0);
        check("stage 50, nothing -> 2 charms, 2 pouches, 14 shards", h != null && h[0] == 2 && h[1] == 2 && h[2] == 14);
        h = P92.handOut(40, false, 1, 0, 1, 3);
        check("one pouch to make -> 1 charm, 4 shards", h != null && h[0] == 1 && h[1] == 0 && h[2] == 4);
        check("kit held (pack + bank) -> none", P92.handOut(40, false, 2, 2, 2, 14) == null);
        check("more than the kit -> none", P92.handOut(50, false, 2, 9, 5, 300) == null);
        check("stage 30 -> none", P92.handOut(30, false, 2, 0, 0, 0) == null);
        check("stage 60 -> none", P92.handOut(60, false, 2, 0, 0, 0) == null);
        check("stage 100 -> none", P92.handOut(100, false, 2, 0, 0, 0) == null);
        check("infused -> none", P92.handOut(40, true, 2, 0, 0, 0) == null);
        check("need 0 -> none", P92.handOut(50, false, 0, 0, 0, 0) == null);
        check("same answer twice (no marker: a restart changes nothing)", java.util.Arrays.equals(P92.handOut(40, false, 2, 0, 2, 14), P92.handOut(40, false, 2, 0, 2, 14)));
        check("cap 1..", P92.CAP >= 1);
        check("altar side: altar 3118,9567 and the door's east 3112,9559", P92.altarSide(3118, 9567, 0) && P92.altarSide(3112, 9559, 0) && P92.altarSide(3111, 9559, 0));
        check("not altar side: stall 3110,9567, door west 3110,9559, ladder 3104,9575, surface 3118,3167", !P92.altarSide(3110, 9567, 0) && !P92.altarSide(3110, 9559, 0)
                && !P92.altarSide(3104, 9575, 0) && !P92.altarSide(3118, 3167, 0) && !P92.altarSide(3118, 9567, 1));
    }

    static boolean names(QuestScript s, String part) {
        for (QuestSteps.Step st : s.steps) {
            if (st.name.contains(part)) return true;
            if (st instanceof QuestSteps.Collect) for (QuestSteps.Step x : ((QuestSteps.Collect) st).plan) if (x.name.contains(part)) return true;
        }
        return false;
    }

    static boolean reaches(Location from, Location to) {
        Path p = Pathfinder.find(from, to);
        if (p == null || !p.isSuccessful() || p.getPoints().isEmpty()) return false;
        core.game.world.map.Point e = p.getPoints().peekLast();
        return e.getX() == to.getX() && e.getY() == to.getY();
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p92")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 50) return false;
                try {
                    pure();
                    check("the altar-room door 33060 stands shut at 3111,9559", QuestRestlessGhost.doorShut());
                    check("no path from the stall tile 3110,9567 to the altar (the wall)", !reaches(Location.create(3110, 9567, 0), QuestRestlessGhost.ALTAR));
                    check("path from the door's east side to the altar", reaches(QuestRestlessGhost.DOOR_E, QuestRestlessGhost.ALTAR));
                    check("path from the stall tile to the door's west side", reaches(Location.create(3110, 9567, 0), QuestRestlessGhost.DOOR_W));
                    boolean on = P92.ON;
                    check("ghost: (P92) door steps " + (on ? "in" : "absent"), names(QuestRestlessGhost.build(), "(P92)") == on);
                    check("wolf whistle: (P92) settle steps " + (on ? "in" : "absent"), names(QuestWolfWhistle.build(), "(P92)") == on);
                } catch (Throwable e) { fail++; log("error " + e); e.printStackTrace(); }
                log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
                log("DONE");
                return true;
            }
        });
    }
}
