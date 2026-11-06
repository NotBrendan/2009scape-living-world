package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.command.CommandSystem;
import core.game.system.task.Pulse;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trial only (-Dtest.b25client=true -Dsmartbots.count=0): the Batch 25 Magic window's client path for a player below
 * Magic 55 (P34 step 2). Unlike TestB25AfkMagic (which calls AfkTasks.handle directly), every line here goes through
 * CommandSystem.parse, the entry the client's "::" command packet reaches, so the CommandMapping "afk" registration and
 * the AfkCommand wrapper (which turns an exception into "AFK: something went wrong") are on the path.
 * Steps, one player at Magic 40 with a staff of fire wielded and alchables + nature runes in the bank:
 *   W0 "afk"                     the window's open toggle (plugin ProcessCommand on bare ::afk); the server sees status
 *   L1 "afk alch list"           typed in chat
 *   L2 "afk lowalch list"        the window: Magic / Low alchemy / "Just list my alchables with profit"
 *   H1 "afk alch <item>"         high alchemy at 40 (the window greys it; typed in chat): refused naming 55 + low
 *   R1 "afk lowalch <item>"      the window: Magic / Low alchemy / "Alch the item, bank the coins"
 * One line per step: "[TestB25ClientPath] CP <step> PASS|FAIL | why", then "CP TOTAL pass= fail=", then DONE.
 */
public final class TestB25ClientPath implements StartupListener {
    static void log(String s) { System.out.println("[TestB25ClientPath] " + s); }

    static final class Human extends AIPlayer {
        final List<String> msgs = new ArrayList<>();
        Human(Location l) { super(l); }
        @Override public void sendMessage(String m) {
            String s = m.replaceAll("<col=[0-9a-f]+>", "");
            msgs.add(s);
            System.out.println("[TestB25ClientPath]   " + getUsername() + " <- " + s);
        }
        String since(int from) { StringBuilder b = new StringBuilder(); for (int i = from; i < msgs.size(); i++) b.append(msgs.get(i).toLowerCase(Locale.ROOT)).append('\n'); return b.toString(); }
    }

    static final int NATURE = 561, STAFF = 1387, BOW = 843, MAGIC_LEVEL = 40;
    /** Bank stock: oak shortbow plus a few smithed items, so the window has a paying low-alch item if prices allow one. */
    static final int[][] STOCK = {{BOW, 20}, {1205, 20}, {1277, 20}, {1121, 5}, {1161, 5}, {1199, 5}, {1315, 5}};
    static final Location BANK = Location.create(3092, 3245, 0);

    static Human h; static String key;
    static int pass, fail;

    static void verdict(String step, boolean ok, String why) {
        if (ok) pass++; else fail++;
        log("CP " + step + " " + (ok ? "PASS" : "FAIL") + " | " + why);
    }

    /** The client path: the text after "::" goes to CommandSystem.parse, as the command packet does. */
    static int send(String line) {
        int from = h.msgs.size();
        log("send ::" + line);
        boolean handled = CommandSystem.Companion.getCommandSystem().parse(h, line);
        if (!handled) log("  parse returned false");
        return from;
    }
    static boolean crashed(String out) { return out.contains("something went wrong") || out.contains("exception"); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b25client")) return;
        core.game.world.GameWorld.getPulser().submit(new Pulse(1) {
            int t, step, mark, item = BOW, waited; String itemName = "oak shortbow";
            @Override public boolean pulse() {
                t++;
                try {
                    if (step == 0) {
                        if (t < 150 || NavGrid.building) { if (t > 3000) { log("setup: nav build still running at tick 3000"); return done(); } return false; }
                        h = new Human(BANK);
                        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(h, false);
                        key = h.getUsername().toLowerCase(Locale.ROOT);
                        AfkTasks.OWNERS.add(key);
                        h.getInventory().clear(); h.getBank().clear();
                        Item w = h.getEquipment().get(3); if (w != null) h.getEquipment().remove(w);
                        h.setAttribute("tutorial:complete", true);
                        h.getSkills().setStaticLevel(Skills.MAGIC, MAGIC_LEVEL); h.getSkills().setLevel(Skills.MAGIC, MAGIC_LEVEL);
                        for (int[] s : STOCK) h.getBank().add(new Item(s[0], s[1]));
                        h.getBank().add(new Item(NATURE, 100));
                        h.getEquipment().add(new Item(STAFF), -1, true, false);
                        // the item a player would type: the best paying low-alch item in the bank, else oak shortbow
                        AfkMagic.Spell low = AfkMagic.SPELLS.get("alch_low");
                        if (low != null) for (int[] a : AfkMagic.alchables(h, low)) if (a[3] == 0 && a[2] >= 0) { item = a[0]; break; }
                        itemName = MarketBrain.name(item).toLowerCase(Locale.ROOT);
                        log("start at tick " + t + ": player " + key + " magic " + h.getSkills().getStaticLevel(Skills.MAGIC) + ", spells " + AfkMagic.SPELLS.keySet()
                                + ", magic on " + AfkMagic.on() + ", lowalch item '" + itemName + "' (" + item + ") profit " + (low == null ? "n/a" : String.valueOf(AfkMagic.profit(h, low, item))));
                        step = 1; return false;
                    }
                    switch (step) {
                        case 1: { // W0: the window's open toggle
                            String out = h.since(send("afk"));
                            verdict("W0", !crashed(out) && out.contains("afk:"), "bare ::afk (window toggle) -> " + (out.contains("not running") ? "status 'not running'" : "'" + out.trim().replace('\n', '|') + "'"));
                            step = 2; return false;
                        }
                        case 2: { // L1: alch list typed in chat
                            String out = h.since(send("afk alch list"));
                            boolean listed = out.contains("from your bank") && (out.contains(" gp") || out.contains("nothing in your bank"));
                            verdict("L1", !crashed(out) && listed, listed ? "list shown" : "no list line");
                            // below 55 (P37): low alchemy prices, with one line saying High Alchemy needs Magic 55
                            boolean high = out.contains("high alchemy from your bank");
                            boolean lowList = out.contains("low alchemy from your bank") || out.contains("nothing in your bank low alchemy");
                            boolean note = out.contains("needs magic 55");
                            log("CP L1 note: header prices " + (high ? "HIGH alchemy (player is Magic " + MAGIC_LEVEL + ", below 55)" : lowList ? "low alchemy" : "unknown") + (note ? ", needs-55 note shown" : ", no needs-55 note"));
                            verdict("L1b", !high && lowList && note, high ? "alch list at Magic " + MAGIC_LEVEL + " offers High Alchemy profits (needs 55)" : !lowList ? "no low alchemy prices below 55" : !note ? "low alchemy prices but no needs-55 note" : "low alchemy prices + needs-55 note below 55");
                            step = 3; return false;
                        }
                        case 3: { // L2: the window's lowalch "list" then-choice
                            String out = h.since(send("afk lowalch list"));
                            boolean listed = out.contains("low alchemy from your bank") || out.contains("nothing in your bank");
                            verdict("L2", !crashed(out) && listed && !out.contains("high alchemy"), listed ? "low alchemy list shown" : "no low alchemy list");
                            step = 4; return false;
                        }
                        case 4: { // H1: high alchemy at 40 (greyed in the window, typed in chat)
                            String out = h.since(send("afk alch " + itemName));
                            AfkTasks.Runner r = AfkTasks.RUNNING.get(key);
                            boolean refused = r == null && out.contains("55");
                            verdict("H1", !crashed(out) && refused, refused ? "refused naming Magic 55" + (out.contains("low") ? " and offering low" : "") : "not refused (runner " + (r != null) + ")");
                            if (r != null) AfkTasks.handle(h, new String[]{"afk", "stop"});
                            step = 5; return false;
                        }
                        case 5: { // R1: the window's lowalch start
                            mark = send("afk lowalch " + itemName); waited = 0;
                            step = 6; return false;
                        }
                        case 6: {
                            waited++;
                            AfkTasks.Runner r = AfkTasks.RUNNING.get(key);
                            String out = h.since(mark);
                            int casts = r == null || r.magic == null ? 0 : r.magic.casts;
                            if (crashed(out)) { verdict("R1", false, "exception reply"); step = 7; return false; }
                            if (casts > 0) {
                                boolean low = r.magic.spell != null && r.magic.spell == AfkMagic.SPELLS.get("alch_low");
                                verdict("R1", low, "accepted, casts=" + casts + " after " + waited + " ticks" + (low ? ", low alchemy" : ", spell not named low"));
                                AfkTasks.handle(h, new String[]{"afk", "stop"}); step = 7; return false;
                            }
                            if (r == null && waited > 2) { // refused: must be a named reason, not a level refusal (40 >= 21)
                                boolean levelRefusal = out.contains("needs magic");
                                verdict("R1", !levelRefusal && out.contains("afk:"), "refused: '" + out.trim().replace('\n', '|') + "'");
                                step = 7; return false;
                            }
                            if (waited > 300) { verdict("R1", false, "no cast in 300 ticks, state " + (r == null ? "-" : r.state)); if (r != null) AfkTasks.handle(h, new String[]{"afk", "stop"}); step = 7; }
                            return false;
                        }
                        default:
                            log("CP TOTAL pass=" + pass + " fail=" + fail);
                            return done();
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); fail++; log("CP TOTAL pass=" + pass + " fail=" + fail); return done(); }
            }
            boolean done() { log("DONE"); return true; }
        });
    }
}
