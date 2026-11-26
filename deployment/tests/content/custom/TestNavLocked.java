package content.custom;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * w2 slayer bats: NavLocked (-Dbots.slayer.batsFix), the Dig Site workmen's gate east of Varrock. Plain java, no server:
 *   javac --release 11 -d out deployment/addons-src/content/custom/NavLocked.java deployment/tests/content/custom/TestNavLocked.java
 *   java -cp out content.custom.TestNavLocked [path to nav-p0.bin]
 * Checks:
 *   D: the flag is off by default (run with no -Dbots.slayer.batsFix).
 *   O: off: close changes no byte and records nothing.
 *   C: on: both gate tiles lose DOOR and keep their west wall; no other byte changes; a second close changes nothing.
 *   R: restore puts the grid back byte for byte (what NavGrid.save writes).
 *   B: a grid that does not cover the tiles is untouched.
 *   F: (with a nav-p0.bin) the real file: both tiles are door tiles with only a west wall before, plain wall tiles after,
 *      so the step 3295,y -> 3296,y is shut for y = 3428, 3429 by the NavGrid.canStep rule.
 */
public class TestNavLocked {
    static int fails = 0;
    static void check(String id, boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + id + ": " + what); if (!ok) fails++; }
    static final int WW = 16, DOOR = 64, BLOCK = 1 | 32;

    /** NavGrid.canStep for a west->east step between two tile bytes whose door tiles have one wall or none. */
    static boolean stepEast(int a, int b) {
        if ((b & BLOCK) != 0) return false;
        if ((a & 4) == 0 && (b & WW) == 0) return true;
        return doorEdge(a, 4) || doorEdge(b, WW);
    }
    static boolean doorEdge(int t, int bit) { if ((t & DOOR) == 0) return false; int w = t & 30; return ((w != 0 ? w : 30) & bit) != 0; }

    public static void main(String[] args) throws Exception {
        check("D", !NavLocked.ON, "flag off by default");
        int x0 = 3200, y0 = 3400, w = 128, h = 64;
        byte[] g = new byte[w * h];
        for (int i = 0; i < g.length; i++) g[i] = (byte) ((i * 7) % 31 == 0 ? 2 : 0);
        int i1 = (3428 - y0) * w + (3296 - x0), i2 = (3429 - y0) * w + (3296 - x0);
        g[i1] = (byte) (DOOR | WW); g[i2] = (byte) (DOOR | WW);
        byte[] orig = g.clone();
        Map<Integer, Integer> raw = new HashMap<>();
        check("O", NavLocked.close(g, x0, y0, w, h, false, raw) == 0 && Arrays.equals(g, orig) && raw.isEmpty(), "off: no change, nothing recorded");
        int n = NavLocked.close(g, x0, y0, w, h, true, raw);
        boolean others = true; for (int i = 0; i < g.length; i++) if (i != i1 && i != i2 && g[i] != orig[i]) others = false;
        check("C1", n == 2 && g[i1] == WW && g[i2] == WW && others, "on: 2 tiles lose DOOR, keep the west wall, nothing else changes (n=" + n + ")");
        check("C2", raw.size() == 2 && raw.get(i1) == (DOOR | WW) && raw.get(i2) == (DOOR | WW), "the old bytes are recorded");
        check("C3", NavLocked.close(g, x0, y0, w, h, true, raw) == 0 && raw.get(i1) == (DOOR | WW), "a second close changes nothing");
        byte[] out = g.clone(); NavLocked.restore(out, raw);
        check("R", Arrays.equals(out, orig) && g[i1] == WW, "restore gives the loaded bytes back; the live grid stays closed");
        byte[] far = new byte[64]; Arrays.fill(far, (byte) DOOR); byte[] far0 = far.clone(); Map<Integer, Integer> r2 = new HashMap<>();
        check("B", NavLocked.close(far, 2048, 2496, 8, 8, true, r2) == 0 && Arrays.equals(far, far0) && r2.isEmpty(), "a grid without the tiles is untouched");
        if (args.length > 0) {
            int X0, Y0, W, H; byte[] f;
            try (DataInputStream d = new DataInputStream(new GZIPInputStream(new FileInputStream(args[0])))) {
                d.readInt(); X0 = d.readInt(); Y0 = d.readInt(); W = d.readInt(); H = d.readInt(); f = new byte[W * H]; d.readFully(f);
            }
            byte[] f0 = f.clone(); Map<Integer, Integer> r3 = new HashMap<>();
            int a8 = (3428 - Y0) * W + (3295 - X0), b8 = a8 + 1, a9 = (3429 - Y0) * W + (3295 - X0), b9 = a9 + 1;
            check("F1", stepEast(f[a8], f[b8]) && stepEast(f[a9], f[b9]) && (f[b8] & 0xFF) == (DOOR | WW) && (f[b9] & 0xFF) == (DOOR | WW), "real grid before: gate tiles are doors (bytes " + (f[b8] & 0xFF) + ", " + (f[b9] & 0xFF) + "), the step east is open");
            int m = NavLocked.close(f, X0, Y0, W, H, true, r3); int diff = 0; for (int i = 0; i < f.length; i++) if (f[i] != f0[i]) diff++;
            check("F2", m == 2 && diff == 2 && !stepEast(f[a8], f[b8]) && !stepEast(f[a9], f[b9]), "real grid after: 2 bytes differ, the step east is shut at y 3428 and 3429");
            NavLocked.restore(f, r3);
            check("F3", Arrays.equals(f, f0), "real grid restored byte for byte");
        } else System.out.println("SKIP F: no nav-p0.bin given");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
