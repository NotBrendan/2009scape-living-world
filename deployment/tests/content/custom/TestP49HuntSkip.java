package content.custom;

import core.game.world.map.Location;

/**
 * Plain-java unit (no world): P49 hunt.skipUnreachable. huntBlock is the pure rule; skipHunt adds the flag (with bot null
 * it is only called for spots where the flag decides before the quest check, so this test uses huntBlock for the
 * Nature Spirit cases and checks the flag through SKIP_UNREACHABLE_HUNTS).
 * java -cp out;server.jar [-Dhunt.skipUnreachable=true] content.custom.TestP49HuntSkip
 */
public class TestP49HuntSkip {
    public static void main(String[] a) {
        int bad = 0;
        Location giants = Location.create(2811, 3506, 0), plateau = Location.create(2923, 3612, 0);
        Location[] snails = {Location.create(3421, 3302, 0), Location.create(3437, 3312, 0), Location.create(3437, 3299, 0), Location.create(3428, 3314, 0)};
        Location[] free = {Location.create(3512, 3480, 0), Location.create(3442, 3458, 0), Location.create(3430, 3400, 0), Location.create(3565, 3290, 0),
            Location.create(2866, 9780, 0), Location.create(2829, 3512, 0), Location.create(3222, 3218, 0), Location.create(3421, 3302, 1)};
        if (!"pocket".equals(Resources.huntBlock(giants, false))) { bad++; System.out.println("RESULT giants not pocket"); }
        if (!"pocket".equals(Resources.huntBlock(plateau, true))) { bad++; System.out.println("RESULT plateau not pocket"); }
        for (Location l : snails) {
            if (!"myre".equals(Resources.huntBlock(l, false))) { bad++; System.out.println("RESULT snail " + l + " not myre without NS"); }
            if (Resources.huntBlock(l, true) != null) { bad++; System.out.println("RESULT snail " + l + " blocked with NS"); }
        }
        for (Location l : free) if (Resources.huntBlock(l, false) != null) { bad++; System.out.println("RESULT free " + l + " blocked"); }
        boolean on = Resources.SKIP_UNREACHABLE_HUNTS;
        if (on != Boolean.getBoolean("hunt.skipUnreachable")) { bad++; System.out.println("RESULT flag mismatch"); }
        if (!on && Resources.skipHunt(null, giants)) { bad++; System.out.println("RESULT skip with flag off"); }
        if (on && !Resources.skipHunt(null, giants)) { bad++; System.out.println("RESULT no skip with flag on"); }
        System.out.println("[TestP49HuntSkip] flag " + on + ": " + (bad == 0 ? "PASS" : "FAIL " + bad));
    }
}
