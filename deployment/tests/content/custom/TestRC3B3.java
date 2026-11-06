package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only (-Dtest.rc3b3=true -Dtraders.fast=true -Dsmartbots.count=2 -Dsmartbots.high=0 -Dafk.debug=true, no survey.* role
 * flags; SEED=live): batch 18 rc3 change 3, live default counts. Reports Explorers' counts (1 shortcut seeker at Agility 99, 6
 * off-ground explorers, 6 door explorers) and waits up to 4 min after the seeker's spawn for it to test or retire (the startup lines
 * "[Explore] ... scheduled", "[Explore] ... set out as a tunnel surveyor (agility N)" and "[Gates] seed:" are in the log).
 */
public final class TestRC3B3 implements StartupListener {
    static void log(String s) { System.out.println("[TestRC3B3] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.rc3b3")) return;
        final long t0 = System.currentTimeMillis();
        log("counts: survey.tunnels " + Explorers.TUNNELS + ", survey.offground " + Explorers.OFFGROUND + ", survey.doors " + Explorers.DOORS + ", survey.stairs " + Explorers.STAIRS);
        GameWorld.getPulser().submit(new Pulse(10) {
            @Override public boolean pulse() {
                long now = System.currentTimeMillis();
                int retired = Explorers.TUNNELS_RETIRED.get();
                if (retired == 0 && now - t0 < 5 * 60_000L) return false;
                log("RESULT seekers " + Explorers.TUNNELS + ", off-ground " + Explorers.OFFGROUND + ", doors " + Explorers.DOORS + ", seekers retired " + retired + " after " + (now - t0) / 1000 + " s; off-ground spawned " + OffGround.SPAWNED.get());
                log("RESULT " + (Explorers.TUNNELS == 1 && Explorers.OFFGROUND == 6 && Explorers.DOORS == 6 ? "PASS: live defaults 1 / 6 / 6" : "FAIL"));
                log("DONE");
                return true;
            }
        });
    }
}
