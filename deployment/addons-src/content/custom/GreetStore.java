package content.custom;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P41 D4: the Scenes greet cooldown (player key -> greeted-until millis) kept in a small file so a restart does not
 * re-greet everyone. Pure Java, no server imports (unit test TestP41Greet runs without a world). Behind
 * -Dscenes.greetPersist (default false); a disabled store never reads or writes. Format: one "key TAB untilMillis" line
 * per entry, oldest first. Save = whole map to a temp file, then rename over the target. IO errors are counted and
 * logged once; the greeting goes on.
 */
final class GreetStore {
    static final int CAP = 500;
    final File file;
    final boolean enabled;
    volatile int loaded, expired, corrupt, ioErrors, saves;
    private volatile boolean logged;

    GreetStore(File file, boolean enabled) { this.file = file; this.enabled = enabled; }

    static boolean flag() { return Boolean.getBoolean("scenes.greetPersist"); }

    /** Reads the file into a new map (oldest first), dropping entries whose until <= now; empty when disabled or absent. */
    LinkedHashMap<String, Long> load(long now) {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        loaded = 0; expired = 0; corrupt = 0;
        if (!enabled || file == null || !file.isFile()) return m;
        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                int t = line.indexOf('\t');
                long until;
                try {
                    if (t <= 0) throw new NumberFormatException();
                    until = Long.parseLong(line.substring(t + 1).trim());
                } catch (NumberFormatException e) { corrupt++; continue; }
                if (until <= now) { expired++; continue; }
                String k = line.substring(0, t);
                m.remove(k); m.put(k, until);
            }
        } catch (IOException e) { ioError("load", e); }
        cap(m);
        loaded = m.size();
        return m;
    }

    /** Writes the whole snapshot (temp file, then rename). Does nothing when disabled. Never throws. */
    void save(Map<String, Long> snap) {
        if (!enabled || file == null || snap == null) return;
        File tmp = new File(file.getPath() + ".tmp");
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            int skip = Math.max(0, snap.size() - CAP), i = 0;
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, Long> e : snap.entrySet()) {
                    if (i++ < skip || e.getKey() == null || e.getValue() == null) continue;
                    w.write(e.getKey()); w.write('\t'); w.write(Long.toString(e.getValue())); w.write('\n');
                }
            }
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException atomic) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            saves++;
        } catch (Throwable e) { ioError("save", e); }
    }

    static void cap(LinkedHashMap<String, Long> m) {
        while (m.size() > CAP) { Iterator<String> it = m.keySet().iterator(); it.next(); it.remove(); } // oldest first
    }

    private void ioError(String what, Throwable e) {
        ioErrors++;
        if (logged) return;
        logged = true;
        System.out.println("[Scenes] greet store " + what + " failed (further errors counted, not logged): " + e);
    }
}
