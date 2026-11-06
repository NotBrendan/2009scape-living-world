package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/** Trial only (-Dtest.afk=true): every ::afk option, typed by a (non-bot) player, as the server sees it. */
public final class TestAfk implements StartupListener {
    static void log(String s) { System.out.println("[TestAfk] " + s); }

    static final class Human extends AIPlayer {
        Human(Location l) { super(l); }
        @Override public void sendMessage(String m) { System.out.println("[TestAfk]   <- " + m.replaceAll("<col=[0-9a-f]+>", "")); }
    }

    static Human me;

    static void cmd(String line) {
        log("::afk " + line);
        String[] parts = ("afk " + line).split(" ");
        AfkTasks.handle(me, parts);
        AfkTasks.Runner r = AfkTasks.RUNNING.get(me.getUsername().toLowerCase());
        if (r != null) log("   runner: " + r.task.key + " bank=" + r.bank + " sell=" + r.sell + " smelt=" + r.smeltHaul + " cook=" + r.cookHaul + " keepAll=" + r.keepAll + " stopAfter=" + r.stopAfter + " stopLevel=" + r.stopLevel + " bury=" + r.bury + " loot=" + r.loot + " style=" + Skills.SKILL_NAME[r.styleSkill] + " spot=" + r.spot + " state=" + r.state);
        else log("   runner: none");
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.afk")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 60) {
                        me = new Human(Location.create(3087, 3236, 0)); // Draynor, by the trees
                        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(me, false);
                        AfkTasks.OWNERS.add(me.getUsername().toLowerCase());
                        me.getInventory().clear(); me.getBank().clear();
                        for (int sk : new int[]{Skills.WOODCUTTING, Skills.MINING, Skills.FISHING, Skills.THIEVING, Skills.SMITHING}) { me.getSkills().setStaticLevel(sk, 20); me.getSkills().setLevel(sk, 20); }
                        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { me.getSkills().setStaticLevel(sk, 30); me.getSkills().setLevel(sk, 30); }
                        log("player " + me.getUsername() + " artificial=" + me.isArtificial());
                        for (String c : new String[]{"help", "list", "list mining", "list combat", "status", "dragons", "logs"}) cmd(c);
                        me.getInventory().add(new Item(1351)); // bronze axe
                        for (String c : new String[]{"logs", "oak", "willow", "tree x50 at draynor", "logs at falador", "logs cook", "logs str", "logs bury", "logs x", "logs bnak", "logs to 100", "logs drop to 25",
                                "start best_tree bank 0 keep 0 1 0", "start oak keep 100 keep 0 1 25", "start best_tree drop 20 keep 0 1 0"}) cmd(c);
                        me.getInventory().add(new Item(1265)); // bronze pickaxe
                        for (String c : new String[]{"ore", "copper smelt sell", "iron x100 smelt", "coal", "essence", "start best_rock smeltsell 0 keep 0 1 0", "ore cook"}) cmd(c);
                        me.getInventory().add(new Item(303)); // small net
                        for (String c : new String[]{"shrimp cook sell", "fish cook", "lobster", "trout", "net smelt"}) cmd(c);
                        for (String c : new String[]{"pickpocket men", "pickpocket farmers", "pickpocket guards", "wool", "flax at seers", "eggs", "snape grass", "ground", "potatoes drop"}) cmd(c);
                        for (String c : new String[]{"fight", "cows str bury", "cowhides def x200", "hill giants bury to 40 noloot", "men att", "warriors at al kharid", "big bones", "herbs", "rock crabs", "cows at lumbridge str", "start c_cowhides keep 100 strength 0 1 0"}) cmd(c);
                        for (String c : new String[]{"bars", "bronze bars x28", "steel bars", "start smelt_best bank 0 keep 0 1 0"}) cmd(c);
                        // batch 23 T7: the "then" words and the commands the plugin 2.0 window builds (T6), then the error cases
                        me.getInventory().add(new Item(946)); me.getInventory().add(new Item(590)); me.getInventory().add(new Item(2347)); // knife, tinderbox, hammer
                        for (String c : new String[]{"iron then smelt bank", "iron then smelt sell", "iron then smelt then smith platebody bank", "best_rock then smelt then smith sell at falador",
                                "oak then fletch bank", "oak then fletch longbow sell", "logs then fletch", "logs then burn", "oak then burn",
                                "lobster then cook sell", "shrimp then cook bank", "c_wool then spin bank", "c_flax then spin sell", "flax then spin",
                                "smith platebody sell", "smith bank", "cook bank", "cook", "fletch", "burn", "spin", "bury", "smelt", "smelt iron", "smithing"}) cmd(c);
                        // errors: each must answer with a message and none may say "something went wrong" (grep the log)
                        for (String c : new String[]{"lobster then fletch", "logs then smelt", "ore then burn", "wool then smelt", "copper then smith dragon", "iron then cook",
                                "then smelt", "iron then smelt then cook", "oak then fletch longbow smith", "tin then smith platebody sell x"}) cmd(c);
                        me.getInventory().remove(new Item(2347)); // no hammer: the message names it
                        for (String c : new String[]{"iron then smelt then smith platebody bank", "smith platebody"}) cmd(c);
                        me.getInventory().remove(new Item(946)); // no knife
                        for (String c : new String[]{"oak then fletch longbow bank", "fletch"}) cmd(c);
                        cmd("gold str");
                        cmd("stop");
                        cmd("logs x5"); // a real run from here: Draynor trees
                    }
                    if (t == 160) { AfkTasks.Runner r = AfkTasks.RUNNING.get(me.getUsername().toLowerCase()); log("after 60 s: " + (r == null ? "finished" : r.describe()) + ", logs in pack " + me.getInventory().getAmount(1511)); cmd("finish"); }
                    if (t == 300) { cmd("status"); log("DONE, bank logs " + me.getBank().getAmount(1511) + ", pack logs " + me.getInventory().getAmount(1511)); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); return true; }
                return false;
            }
        });
    }
}
