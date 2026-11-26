package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * P93 unit: HuntMap.p93Pocket matches the two walled hunt pockets (Zogre 2468,3046; Giant wasp 2891,3002) and nothing else, and
 * with -Dnav.p93Fix=true no built hunt of those names sits within 20 tiles of its pocket (with the flag off they are still built).
 * In a world with -Dtest.p93=true it waits for HuntMap, prints "[TestP93] RESULT PASS/FAIL pass=N fail=M" then "[TestP93] DONE".
 */
public final class TestP93 implements StartupListener {
    static int pass, fail;
    static void log(String s) { System.out.println("[TestP93] " + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p93")) return;
        GameWorld.getPulser().submit(new Pulse(20) {
            int waits;
            @Override public boolean pulse() {
                if (HuntMap.HUNTS.isEmpty() && ++waits < 60) return false; // wait (up to ~12 min) for HuntMap.build
                log("hunts=" + HuntMap.HUNTS.size() + " flag=" + HuntMap.P93_FIX + " dropped=" + HuntMap.p93Dropped + " after " + waits + " waits");
                try { checks(); world(); } catch (Throwable ex) { fail++; log("error " + ex); }
                log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
                log("DONE");
                return true;
            }
        });
    }

    static void checks() {
        check("pocket Zogre 2468,3046", HuntMap.p93Pocket("Zogre", Location.create(2468, 3046, 0)));
        check("pocket Zogre 2482,3046", HuntMap.p93Pocket("Zogre", Location.create(2482, 3046, 0)));
        check("pocket Giant wasp 2891,3002", HuntMap.p93Pocket("Giant wasp", Location.create(2891, 3002, 0)));
        check("not Zogre far away 2449,9429", !HuntMap.p93Pocket("Zogre", Location.create(2449, 9429, 0)));
        check("not Goblin 2468,3046", !HuntMap.p93Pocket("Goblin", Location.create(2468, 3046, 0)));
        check("not null", !HuntMap.p93Pocket(null, null));
    }

    static void world() {
        int left = 0;
        for (HuntMap.Hunt h : HuntMap.HUNTS) {
            boolean named = false;
            if (h.names != null) for (String n : h.names) if (HuntMap.p93Pocket(n, h.spot)) named = true;
            if (HuntMap.p93Pocket(h.name, h.spot) || named) { left++; log("pocket hunt built: " + h.name + " at " + h.spot); }
        }
        if (HuntMap.P93_FIX) {
            check("flag on: no pocket hunt built (left=" + left + ")", left == 0);
            check("flag on: dropped >= 2 (" + HuntMap.p93Dropped + ")", HuntMap.p93Dropped >= 2);
        } else {
            check("flag off: nothing dropped", HuntMap.p93Dropped == 0);
            check("flag off: pocket hunts still built (left=" + left + ")", left >= 2);
        }
    }
}
