package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * P76 D3 (-Dtest.afkbig=true): the big-net task. Words resolve, the spot tile is standable, a player without the net is
 * refused, and a player with a big net (Fishing 40) fishes at Catherby and only uses the big-net (Net/Harpoon) spots.
 * Prints CHECK <name> PASS|FAIL and DONE.
 */
public final class TestAfkBigNet implements StartupListener {
    static void log(String s) { System.out.println("[TestAfkBig] " + s); }
    static final class Human extends AIPlayer {
        final StringBuilder seen = new StringBuilder();
        Human(Location l) { super(l); }
        @Override public void sendMessage(String m) { String s = m.replaceAll("<col=[0-9a-f]+>", ""); seen.append(s).append(" | "); System.out.println("[TestAfkBig]   <- " + s); }
    }
    static int failed;
    static void check(String n, boolean ok, String why) { if (!ok) failed++; log("CHECK " + n + " " + (ok ? "PASS" : "FAIL") + " (" + why + ")"); }
    static Human make(Location l) throws Exception {
        Human h = new Human(l);
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(h, false);
        AfkTasks.OWNERS.add(h.getUsername().toLowerCase());
        h.getInventory().clear(); h.getBank().clear(); h.getEquipment().clear();
        h.getSkills().setStaticLevel(Skills.FISHING, 40); h.getSkills().setLevel(Skills.FISHING, 40);
        return h;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.afkbig")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; Human none, fish; double xp0; int bad;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 60) {
                        for (String w : new String[]{"big net", "bignet", "mackerel", "cod", "bass", "big net fishing"})
                            check("word_" + w.replace(' ', '_'), AfkTasks.WORDS.get(w) == AfkTasks.Task.BIG_NET, String.valueOf(AfkTasks.WORDS.get(w)));
                        AfkTasks.Spot[] sp = AfkTasks.SPOTS.get(AfkTasks.Task.BIG_NET);
                        Location at = sp[0].at;
                        boolean st = NavGrid.ready() ? NavGrid.standable(at.getX(), at.getY()) : (OffGround.flag(at.getZ(), at.getX(), at.getY()) & (NavGrid.SOLID | NavGrid.UNREAD)) == 0;
                        check("spot_standable", st, at.toString());
                        StringBuilder sb = new StringBuilder();
                        for (core.game.node.entity.npc.NPC n : core.game.world.map.RegionManager.getLocalNpcs(at, 24)) if (n.getName() != null && n.getName().contains("Fishing")) sb.append(n.getId()).append('@').append(n.getLocation().getX()).append(',').append(n.getLocation().getY()).append(' ');
                        log("DUMP fishing spots: " + sb);
                        Location bank = Location.create(2809, 3441, 0);
                        none = make(bank); fish = make(bank);
                        fish.getInventory().add(new Item(305, 1));
                        AfkTasks.handle(none, "afk bignet".split(" "));
                        check("no_net_refused", AfkTasks.RUNNING.get(none.getUsername().toLowerCase()) == null && none.seen.toString().contains("big fishing net"), none.seen.toString());
                        xp0 = fish.getSkills().getExperience(Skills.FISHING);
                        AfkTasks.handle(fish, "afk big net".split(" "));
                        check("runner_started", AfkTasks.RUNNING.get(fish.getUsername().toLowerCase()) != null, fish.seen.toString());
                    }
                    if (t > 60 && t % 100 == 0) {
                        AfkTasks.Runner r = AfkTasks.RUNNING.get(fish.getUsername().toLowerCase());
                        log("t" + (t - 60) + " at " + fish.getLocation() + (r == null ? " none" : " state=" + r.state + " last=" + r.last + " collected=" + r.collected));
                    }
                    if (t > 60) { // a fish action on a small-net spot would show as a target npc id outside the big-net list
                        AfkTasks.Runner r = AfkTasks.RUNNING.get(fish.getUsername().toLowerCase());
                        if (r != null && r.lastScan instanceof core.game.node.entity.npc.NPC && !AfkTasks.has(AfkTasks.BIGNET_SPOTS, ((core.game.node.entity.npc.NPC) r.lastScan).getId())) bad++;
                    }
                    if (t == 60 + Integer.getInteger("test.afkbig.ticks", 700)) {
                        AfkTasks.Runner r = AfkTasks.RUNNING.get(fish.getUsername().toLowerCase());
                        double gain = fish.getSkills().getExperience(Skills.FISHING) - xp0;
                        int fishCount = fish.getInventory().getAmount(353) + fish.getInventory().getAmount(341) + fish.getInventory().getAmount(363) + fish.getInventory().getAmount(401) + fish.getBank().getAmount(353) + fish.getBank().getAmount(341) + fish.getBank().getAmount(363) + fish.getBank().getAmount(401);
                        check("fished", fishCount > 0, "xp+" + (int) gain + " fish=" + fishCount + " collected=" + (r == null ? -1 : r.collected));
                        check("only_bignet_spots", bad == 0, "bad=" + bad);
                        log("CHECKS failed=" + failed);
                        AfkTasks.stop(fish, "test over"); AfkTasks.stop(none, "test over");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
