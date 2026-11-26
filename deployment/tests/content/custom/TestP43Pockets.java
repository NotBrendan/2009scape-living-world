package content.custom;

import core.game.world.map.Location;

/**
 * Plain-java unit (no world): P43 resources.skipPockets. Run twice: without the flag every spot is kept; with
 * -Dresources.skipPockets=true spots inside the two pocket boxes are skipped and mainland spots kept.
 * java -cp out;server.jar [-Dresources.skipPockets=true] content.custom.TestP43Pockets
 */
public class TestP43Pockets {
    public static void main(String[] a) {
        boolean on = Resources.SKIP_POCKETS; int bad = 0;
        Location[] inA = {Location.create(2808, 3504, 0), Location.create(2816, 3496 + 4, 0), Location.create(2800, 3504, 0)};
        Location[] inB = {Location.create(2923, 3612, 0), Location.create(2885, 3600, 0)};
        Location[] inC = {Location.create(2840, 3240, 0), Location.create(2810, 3228, 0), Location.create(2868, 3314, 0)};
        Location[] main = {Location.create(3222, 3218, 0), Location.create(2809, 3441, 0), Location.create(2799, 3504, 0), Location.create(2829, 3512, 0), Location.create(2879, 3621, 0), Location.create(2808, 3504, 1), Location.create(2809, 3240, 0), Location.create(2869, 3300, 0), Location.create(2840, 3227, 0), Location.create(2840, 3315, 0), Location.create(2840, 3240, 1)};
        for (Location l : inA) { boolean got = Resources.skipSpot(l); if (got != on) { bad++; System.out.println("RESULT A " + l + " skip=" + got + " want " + on); } }
        for (Location l : inB) { boolean got = Resources.skipSpot(l); if (got != on) { bad++; System.out.println("RESULT B " + l + " skip=" + got + " want " + on); } }
        for (Location l : inC) { boolean got = Resources.skipSpot(l); if (got != on) { bad++; System.out.println("RESULT C(Crandor) " + l + " skip=" + got + " want " + on); } }
        for (Location l : main) { boolean got = Resources.skipSpot(l); if (got) { bad++; System.out.println("RESULT mainland " + l + " skip=" + got + " want false"); } }
        System.out.println("[TestP43Pockets] flag " + on + ": " + (bad == 0 ? "PASS" : "FAIL " + bad));
    }
}
