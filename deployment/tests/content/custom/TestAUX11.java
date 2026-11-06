package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.aux11=true, after prep_x11.sh put garbage in price-base.json, with traders.fast so drift runs at 90 s):
 * audit 10-23 X-11. Runs drift once more by hand at 2 minutes, then reports what is in price-base.json.
 */
public final class TestAUX11 implements StartupListener {
    static void log(String s) { System.out.println("[TestAUX11] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.aux11")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t != 220) return false;
                try {
                    PriceDrift.drift();
                    String s = new String(java.nio.file.Files.readAllBytes(PriceDrift.file().toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    StringBuilder bad = new StringBuilder(); java.io.File[] l = PriceDrift.file().getParentFile().listFiles(); if (l != null) for (java.io.File x : l) if (x.getName().startsWith("price-base.json.bad-")) bad.append(x.getName()).append(' ');
                    log("RESULT loaded " + PriceDrift.LOADED + ", BASE entries " + PriceDrift.BASE.size() + "; price-base.json now " + (s.startsWith("GARBAGE") ? "untouched (the original bad file)" : "REWRITTEN (" + s.length() + " bytes)") + "; copies: " + (bad.length() == 0 ? "none" : bad.toString().trim()));
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
