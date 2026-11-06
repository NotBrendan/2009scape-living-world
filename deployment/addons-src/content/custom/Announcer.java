package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.world.GameWorld;
import core.game.world.repository.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Lets the admin shell talk to players without an in-game admin account:
 * anything written to data/announce.txt is broadcast as news to everyone online, then the file
 * is emptied. One line per message. Example from the live server:
 *   echo "The server restarts in 1 minute. Please bank and log out." | sudo tee /opt/2009scape/game/data/announce.txt
 */
public final class Announcer implements StartupListener {
    static final Timer TIMER = new Timer("Announcer", true);

    @Override
    public void startup() {
        File f = new File(ServerConstants.DATA_PATH, "announce.txt");
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    // audit C-4: rename, then read and delete (a line appended between the read and the truncate was lost);
                    // the next writer creates a fresh announce.txt. A .reading file left by a crash is said first.
                    File taken = new File(f.getParentFile(), "announce.txt.reading");
                    if (!taken.exists()) {
                        if (!f.exists() || f.length() == 0) return;
                        Files.move(f.toPath(), taken.toPath());
                        Thread.sleep(250); // a writer that opened the file just before the rename finishes into the renamed file
                    }
                    String text = new String(Files.readAllBytes(taken.toPath()), StandardCharsets.UTF_8);
                    Files.delete(taken.toPath());
                    for (String line : text.split("\\r?\\n")) {
                        String msg = line.trim();
                        if (msg.isEmpty()) continue;
                        // Said three times, 20 s apart, as news AND as a red chatbox line to everyone online, so nobody
                        // misses it. A restart warning counts down ("in 1 minute", "in 40 seconds", "in 20 seconds").
                        boolean restart = msg.toLowerCase().contains("restarts in 1 minute");
                        for (int i = 0; i < 3; i++) {
                            final String say = i == 0 || !restart ? msg : msg.replaceFirst("(?i)in 1 minute", i == 1 ? "in 40 seconds" : "in 20 seconds");
                            GameWorld.getPulser().submit(new core.game.system.task.Pulse(1 + i * 33) { // ~20 s per step (0.6 s ticks)
                                @Override
                                public boolean pulse() {
                                    Repository.sendNews(say);
                                    for (core.game.node.entity.player.Player p : Repository.getPlayers()) {
                                        if (p == null || p.isArtificial() || !p.isActive()) continue;
                                        try { p.sendMessage("<col=ff0000>" + say); } catch (Throwable ignored) { }
                                    }
                                    return true;
                                }
                            });
                        }
                        System.out.println("[Announcer] " + msg + " (x3)");
                    }
                } catch (Throwable t) {
                    System.out.println("[Announcer] failed: " + t);
                }
            }
        }, 10_000, 2_000);
        System.out.println("[Announcer] watching " + f.getAbsolutePath());
    }
}
