package content.custom;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.LinkedHashMap;

/**
 * P41 D4: GreetStore round trip with no world (plain main). Run: java -cp <tests build> content.custom.TestP41Greet
 * Simulated restarts = new store instances on the same file.
 */
public final class TestP41Greet {
    static int pass, fail;

    static void check(String name, boolean ok, String why) {
        if (ok) { pass++; System.out.println("[TestP41Greet] " + name + " RESULT PASS"); }
        else { fail++; System.out.println("[TestP41Greet] " + name + " RESULT FAIL " + why); }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("p41greet").toFile();
        File f = new File(dir, "scenes-greeted.txt");
        long now = System.currentTimeMillis(), h6 = 6 * 3_600_000L;

        GreetStore a = new GreetStore(f, true);
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("alice", now + h6); m.put("bob", now - 1_000); m.put("carol", now + h6 / 2);
        a.save(m);
        check("1 save", f.isFile() && a.saves == 1 && a.ioErrors == 0, "file=" + f.isFile() + " saves=" + a.saves + " io=" + a.ioErrors);

        GreetStore b = new GreetStore(f, true);
        LinkedHashMap<String, Long> mb = b.load(now);
        check("2 restart load", b.loaded == 2 && b.expired == 1 && !mb.containsKey("bob") && mb.get("alice") == now + h6,
                "loaded=" + b.loaded + " expired=" + b.expired + " keys=" + mb.keySet());
        mb.put("dave", now + h6);
        b.save(mb);

        GreetStore c = new GreetStore(f, true);
        LinkedHashMap<String, Long> mc = c.load(now);
        check("3 second restart", c.loaded == 3 && c.expired == 0 && mc.containsKey("dave"), "loaded=" + c.loaded + " keys=" + mc.keySet());

        try (FileWriter w = new FileWriter(f, true)) { w.write("garbage-no-tab\n"); w.write("eve\tnot-a-number\n"); }
        GreetStore d = new GreetStore(f, true);
        LinkedHashMap<String, Long> md = d.load(now);
        check("4 corrupt lines", d.corrupt == 2 && d.loaded == 3 && md.size() == 3, "corrupt=" + d.corrupt + " loaded=" + d.loaded);

        LinkedHashMap<String, Long> big = new LinkedHashMap<>();
        for (int i = 0; i < 520; i++) big.put("p" + i, now + h6);
        GreetStore e = new GreetStore(f, true);
        e.save(big);
        LinkedHashMap<String, Long> me = new GreetStore(f, true).load(now);
        check("5 cap 500", me.size() == 500 && !me.containsKey("p19") && me.containsKey("p20") && me.containsKey("p519"), "size=" + me.size());

        File off = new File(dir, "off-greeted.txt");
        GreetStore o = new GreetStore(off, false);
        o.save(m);
        LinkedHashMap<String, Long> mo = new GreetStore(f, false).load(now);
        check("6 flag off", !off.exists() && o.saves == 0 && mo.isEmpty(), "exists=" + off.exists() + " saves=" + o.saves + " loaded=" + mo.size());

        File bad = new File(new File(dir, "not-a-dir.txt"), "x.txt");
        Files.write(new File(dir, "not-a-dir.txt").toPath(), new byte[0]);
        GreetStore g = new GreetStore(bad, true);
        g.save(m); g.save(m);
        check("7 io error counted", g.ioErrors == 2 && g.saves == 0, "io=" + g.ioErrors + " saves=" + g.saves);

        check("8 default flag off", !GreetStore.flag(), "scenes.greetPersist=" + System.getProperty("scenes.greetPersist"));

        System.out.println("[TestP41Greet] SUMMARY " + pass + " pass, " + fail + " fail");
        System.out.println("[TestP41Greet] DONE");
        System.exit(fail == 0 ? 0 : 1);
    }
}
